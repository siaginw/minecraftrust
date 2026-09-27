use packet_buffer_pipeline_experiment::{allocation, pipeline::*};
use std::collections::BTreeMap;
use std::hint::black_box;
use std::io::{Read, Write};
use std::net::{Shutdown, TcpListener, TcpStream};
use std::path::Path;
use std::sync::{Arc, Barrier};
use std::thread;
use std::time::{Duration, Instant};

#[global_allocator]
static ALLOCATOR: allocation::TrackingAllocator = allocation::TrackingAllocator;

const CASES: [(u32, usize, i32); 7] = [
    (1, 16, -1),
    (0, 255, 256),
    (2, 256, 256),
    (3, 257, 256),
    (0, 4096, 0),
    (4, 32768, 1024),
    (5, 65536, 256),
];
fn hash(bytes: &[u8]) -> u64 {
    bytes.iter().fold(0xcbf29ce484222325, |h, b| {
        (h ^ u64::from(*b)).wrapping_mul(0x100000001b3)
    })
}
fn done(
    hub: &mut Hub,
    frames: &mut BTreeMap<(usize, u64), Vec<u8>>,
    ids: &[ConnectionId],
    capture: bool,
) {
    let c = hub.next_completion().unwrap();
    if capture {
        let index = ids
            .iter()
            .position(|id| *id == c.ticket().connection())
            .unwrap();
        frames.insert((index, c.ticket().sequence()), c.plaintext().unwrap());
    }
    if let Err(e) = hub.accept(c) {
        panic!("completion rejected {:?}", e.error)
    }
}
fn pump(hub: &mut Hub, ids: &[ConnectionId], streams: &mut [TcpStream], quantum: usize) -> bool {
    let mut empty = true;
    for (index, id) in ids.iter().enumerate() {
        empty &= hub.send_once(*id, &mut streams[index], quantum).unwrap();
    }
    empty
}
fn run(dir: &Path, shared: bool, slow: bool, quantum: usize, capture: bool) {
    std::fs::create_dir_all(dir).unwrap();
    let snapshots: Vec<_> = CASES
        .iter()
        .map(|(seed, length, _)| Snapshot::new(*seed, *length).unwrap())
        .collect();
    let before = allocation::snapshot();
    let mut hub = Hub::new(MAX_JOBS, OUTPUT_BUDGET);
    let mut ids = Vec::new();
    let mut streams = Vec::new();
    let mut receivers = Vec::new();
    let barrier = Arc::new(Barrier::new(5));
    for index in 0..4 {
        let listener = TcpListener::bind((std::net::Ipv4Addr::LOCALHOST, 0)).unwrap();
        listener.set_nonblocking(true).unwrap();
        let address = listener.local_addr().unwrap();
        let start = barrier.clone();
        receivers.push(thread::spawn(move || {
            let _scope = allocation::stage(6);
            let deadline = Instant::now() + Duration::from_secs(8);
            let (mut reader, _) = loop {
                match listener.accept() {
                    Ok(pair) => break pair,
                    Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => {
                        assert!(Instant::now() < deadline, "accept timeout");
                        thread::sleep(Duration::from_millis(1));
                    }
                    Err(e) => panic!("accept {e}"),
                }
            };
            // Accepted Windows sockets inherit the listener's nonblocking mode.
            reader.set_nonblocking(false).unwrap();
            reader
                .set_read_timeout(Some(Duration::from_secs(5)))
                .unwrap();
            socket2::SockRef::from(&reader)
                .set_recv_buffer_size(4096)
                .unwrap();
            start.wait();
            if slow {
                thread::sleep(Duration::from_millis(80));
            }
            let mut output = Vec::with_capacity(2 * MAX_BODY);
            let mut scratch = [0; 113];
            let mut copied = 0usize;
            let mut reads = 0usize;
            loop {
                let n = reader.read(&mut scratch).unwrap();
                if n == 0 {
                    break;
                }
                assert!(output.len() + n <= 2 * MAX_BODY, "receiver byte bound");
                output.extend_from_slice(&scratch[..n]);
                copied += n;
                reads += 1;
                assert!(Instant::now() < deadline, "receive deadline");
            }
            (output, copied, reads)
        }));
        let stream = TcpStream::connect_timeout(&address, Duration::from_secs(2)).unwrap();
        stream.set_nonblocking(true).unwrap();
        stream.set_nodelay(true).unwrap();
        socket2::SockRef::from(&stream)
            .set_send_buffer_size(1024)
            .unwrap();
        streams.push(stream);
        ids.push(hub.connect(index as u32, [index as u8 + 1; 16]).unwrap());
    }
    let now = Instant::now();
    barrier.wait();
    let mut bodies = Bodies::new();
    let body_budget = bodies.budget.clone();
    let job_budget = hub.budget.clone();
    let mut frames = BTreeMap::new();
    let mut pauses = 0;
    let deadline = Instant::now() + Duration::from_secs(6);
    for (case, (_, _, threshold)) in CASES.iter().enumerate() {
        for (index, id) in ids.iter().enumerate() {
            let body = if shared {
                bodies.encode(&snapshots[case], (index % 2) as u8)
            } else {
                bodies.encode_uncached(&snapshots[case], (index % 2) as u8)
            }
            .unwrap();
            loop {
                match hub.submit(*id, body.clone(), *threshold, Fault::None) {
                    Ok(ticket) => {
                        // Exercise a real cancellation/order hole in every stream campaign.
                        if index == 0 && case == 3 {
                            hub.cancel(ticket).unwrap();
                        }
                        break;
                    }
                    Err(Error::Backpressure) => {
                        pauses += 1;
                        if hub.outstanding() > 0 {
                            done(&mut hub, &mut frames, &ids, capture);
                        }
                        let sent = hub.traffic.sent;
                        pump(&mut hub, &ids, &mut streams, quantum);
                        if hub.outstanding() == 0 && hub.traffic.sent == sent {
                            thread::sleep(Duration::from_millis(1));
                        }
                        assert!(Instant::now() < deadline, "admission timeout");
                    }
                    Err(e) => panic!("admission {e:?}"),
                }
            }
        }
    }
    while hub.outstanding() > 0 {
        done(&mut hub, &mut frames, &ids, capture);
        pump(&mut hub, &ids, &mut streams, quantum);
    }
    let mut finished = [false; 4];
    while !finished.iter().all(|v| *v) {
        let sent = hub.traffic.sent;
        for (index, id) in ids.iter().enumerate() {
            if !finished[index] && hub.send_once(*id, &mut streams[index], quantum).unwrap() {
                streams[index].shutdown(Shutdown::Write).unwrap();
                finished[index] = true;
            }
        }
        assert!(Instant::now() < deadline, "send deadline");
        if hub.traffic.sent == sent {
            thread::sleep(Duration::from_millis(1));
        } else {
            thread::yield_now();
        }
    }
    let mut received = Vec::new();
    let mut read_copied = 0;
    let mut reads = 0;
    for receiver in receivers {
        let (bytes, copies, n) = receiver.join().unwrap();
        received.push(bytes);
        read_copied += copies;
        reads += n;
    }
    let elapsed = now.elapsed().as_nanos();
    hub.quiesce();
    let traffic = hub.traffic;
    let retained_copied = bodies.copied;
    let retained_generated = bodies.generated;
    let hits = bodies.shared_hits;
    let body_stats = body_budget.stats();
    let job_stats = job_budget.stats();
    assert_eq!(traffic.sent, read_copied as u64);
    assert_eq!(traffic.encrypted, traffic.sent);
    assert_eq!(job_stats.jobs, 0);
    assert!(body_stats.peak_bytes <= BODY_BUDGET && job_stats.peak_bytes <= OUTPUT_BUDGET);
    bodies.clear();
    drop(bodies);
    assert_eq!(body_budget.stats().bytes, 0);
    drop(hub);
    drop(streams);
    let after = allocation::snapshot();
    // Evidence-file writes occur after metering. Optional reference capture was
    // metered in the oracle stage; measured campaigns disable that capture.
    let _oracle = allocation::stage(7);
    let mut manifest = std::fs::File::create(dir.join("manifest.tsv")).unwrap();
    for (index, bytes) in received.iter().enumerate() {
        std::fs::write(dir.join(format!("connection-{index}.wire")), bytes).unwrap();
        println!("wire\t{index}\t{}\t{}", bytes.len(), hash(bytes));
        for (case, (seed, length, threshold)) in CASES.iter().enumerate() {
            if index == 0 && case == 3 {
                continue;
            }
            writeln!(
                manifest,
                "{index}\t{case}\t{seed}\t{length}\t{threshold}\t{}\t{}",
                index % 2,
                index + 1
            )
            .unwrap();
        }
    }
    for ((index, sequence), bytes) in frames {
        std::fs::write(
            dir.join(format!("connection-{index}-packet-{sequence}.frame")),
            bytes,
        )
        .unwrap();
    }
    println!("pipeline\t{}\t{}\t{quantum}\t{elapsed}\t{retained_copied}\t{hits}\t{pauses}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{read_copied}\t{reads}",u8::from(shared),u8::from(slow),body_stats.peak_bytes,job_stats.peak_jobs,job_stats.peak_bytes,traffic.compressed_input,traffic.compressed_output,traffic.passthrough_copied,traffic.initialized_output,traffic.header_generated,traffic.header_copied,traffic.encrypted,traffic.sent,traffic.writes,traffic.os_short_writes,traffic.would_block,traffic.compression_ns);
    println!("encryption_ns\t{}", traffic.encryption_ns);
    println!("retained_generated\t{retained_generated}");
    for (index, (a, b)) in before.iter().zip(after).enumerate() {
        println!(
            "allocation\t{}\t{}\t{}\t{}\t{}",
            allocation::NAMES[index],
            b.allocations - a.allocations,
            b.requested - a.requested,
            b.deallocations - a.deallocations,
            b.freed - a.freed
        );
    }
}
fn handles() {
    for repeat in -1..5 {
        for size in [64, 4096, 65536] {
            let original = vec![19u8; size];
            let pointer = original.as_ptr();
            let owned = Arc::new(original);
            assert_eq!(pointer, owned.as_ptr());
            let original = vec![19u8; size];
            let pointer = original.as_ptr();
            let bytes = bytes::Bytes::from(original);
            assert_eq!(pointer, bytes.as_ptr());
            let arc = || {
                let before = allocation::snapshot();
                let start = Instant::now();
                let mut sum = 0u64;
                for _ in 0..100_000 {
                    let copy = black_box(owned.clone());
                    let view = &copy[size / 4..size / 2];
                    sum += u64::from(black_box(view[0]));
                }
                let elapsed = start.elapsed().as_nanos();
                let after = allocation::snapshot();
                (elapsed, sum, after[0].allocations - before[0].allocations)
            };
            let modern = || {
                let before = allocation::snapshot();
                let start = Instant::now();
                let mut sum = 0u64;
                for _ in 0..100_000 {
                    let view = black_box(bytes.clone().slice(size / 4..size / 2));
                    sum += u64::from(black_box(view[0]));
                }
                let elapsed = start.elapsed().as_nanos();
                let after = allocation::snapshot();
                (elapsed, sum, after[0].allocations - before[0].allocations)
            };
            let (a, b) = if repeat % 2 == 0 {
                let b = modern();
                (arc(), b)
            } else {
                let a = arc();
                (a, modern())
            };
            assert_eq!(a.1, b.1);
            println!(
                "handles\t{repeat}\t{size}\t{}\t{}\t{}\t{}",
                a.0, b.0, a.2, b.2
            );
        }
    }
}
fn main() {
    let args: Vec<_> = std::env::args().collect();
    match args.get(1).map(String::as_str) {
        Some("run") => run(
            Path::new(&args[2]),
            args[3] == "1",
            args[4] == "1",
            args[5].parse().unwrap(),
            args[6] == "1",
        ),
        Some("handles") => handles(),
        _ => panic!("run DIRECTORY SHARED SLOW QUANTUM CAPTURE or handles"),
    }
}
