use aes::cipher::KeyIvInit;
use packet_buffer_pipeline_experiment::pipeline::*;
use std::collections::BTreeMap;
use std::io::{self, IoSlice, Write};
use std::sync::Arc;

fn body(seed: u32) -> Arc<Body> {
    Bodies::new()
        .encode(&Snapshot::new(seed, 1024).unwrap(), 0)
        .unwrap()
}
fn accept(hub: &mut Hub, c: Completion) {
    if let Err(e) = hub.accept(c) {
        panic!("unexpected rejection {:?}", e.error)
    }
}
fn collect(hub: &mut Hub, id: ConnectionId) -> Vec<u8> {
    let mut bytes = Vec::new();
    while !hub.send_once(id, &mut bytes, usize::MAX).unwrap() {}
    bytes
}

#[test]
fn equivalent_recipients_share_exact_body_other_rules_and_generations_do_not() {
    let mut bodies = Bodies::new();
    let s = Snapshot::new(42, 1024).unwrap();
    let a = bodies.encode(&s, 0).unwrap();
    let b = bodies.encode(&s, 0).unwrap();
    assert!(Arc::ptr_eq(&a, &b));
    let masked = bodies.encode(&s, 1).unwrap();
    assert!(!Arc::ptr_eq(&a, &masked));
    assert_ne!(a.bytes(), masked.bytes());
    let changed = bodies.encode(&s.changed(), 0).unwrap();
    assert_ne!(a.bytes(), changed.bytes());
    assert_eq!(bodies.shared_hits, 1);
    assert_eq!(bodies.copied, 2016);
    let meter = bodies.budget.clone();
    bodies.clear();
    assert_eq!(meter.stats().jobs, 3);
    drop((a, b, masked, changed));
    assert_eq!(meter.stats().bytes, 0);
}

#[test]
fn bounded_body_budget_does_not_ignore_reader_retention() {
    let mut bodies = Bodies::new();
    let mut retained = Vec::new();
    for i in 0..8 {
        retained.push(
            bodies
                .encode(&Snapshot::new(i, MAX_BODY).unwrap(), 0)
                .unwrap(),
        );
    }
    bodies.clear();
    assert_eq!(bodies.budget.stats().bytes, BODY_BUDGET);
    assert!(matches!(
        bodies.encode(&Snapshot::new(9, MAX_BODY).unwrap(), 0),
        Err(Error::Backpressure)
    ));
    retained.pop();
    assert!(bodies
        .encode(&Snapshot::new(10, MAX_BODY).unwrap(), 0)
        .is_ok());
}

#[test]
fn real_workers_complete_out_of_order_but_cipher_order_is_continuous() {
    let mut hub = Hub::new(4, OUTPUT_BUDGET);
    let id = hub.connect(1, [7; 16]).unwrap();
    let gate = Arc::new(Gate::default());
    let first = hub
        .submit(id, body(1), -1, Fault::Gate(gate.clone()))
        .unwrap();
    let second = hub.submit(id, body(2), 256, Fault::None).unwrap();
    let c = hub.next_completion().unwrap();
    assert_eq!(c.ticket(), second);
    let b = c.plaintext().unwrap();
    accept(&mut hub, c);
    assert_eq!(hub.queued(id), 0);
    assert_eq!(hub.traffic.encrypted, 0);
    gate.release();
    let c = hub.next_completion().unwrap();
    assert_eq!(c.ticket(), first);
    let mut a = c.plaintext().unwrap();
    a.extend(b);
    accept(&mut hub, c);
    let actual = collect(&mut hub, id);
    cfb8::Encryptor::<aes::Aes128>::new(&[7; 16].into(), &[7; 16].into()).encrypt(&mut a);
    assert_eq!(actual, a);
    assert_eq!(hub.budget.stats().bytes, 0);
    assert_eq!(hub.traffic.sent, hub.traffic.encrypted);
}

#[test]
fn foreign_connection_ticket_and_completion_cannot_consume_local_work() {
    let mut a = Hub::new(2, OUTPUT_BUDGET);
    let mut b = Hub::new(2, OUTPUT_BUDGET);
    let ca = a.connect(1, [1; 16]).unwrap();
    let cb = b.connect(1, [1; 16]).unwrap();
    assert_eq!(
        a.submit(cb, body(1), -1, Fault::None).err(),
        Some(Error::Foreign)
    );
    let ta = a.submit(ca, body(1), -1, Fault::None).unwrap();
    let tb = b.submit(cb, body(1), -1, Fault::None).unwrap();
    assert_eq!(a.cancel(tb), Err(Error::Foreign));
    assert_eq!(a.outstanding(), 1);
    let foreign = b.next_completion().unwrap();
    let rejected = a.accept(foreign).err().unwrap();
    assert_eq!(rejected.error, Error::Foreign);
    assert_eq!(a.outstanding(), 1);
    accept(&mut b, *rejected.completion);
    let c = a.next_completion().unwrap();
    assert_eq!(c.ticket(), ta);
    accept(&mut a, c);
}

#[test]
fn cancellation_holds_reservation_until_real_worker_quiescence_and_skips_order_hole() {
    let mut hub = Hub::new(1, OUTPUT_BUDGET);
    let id = hub.connect(1, [3; 16]).unwrap();
    let gate = Arc::new(Gate::default());
    let ticket = hub
        .submit(id, body(1), 256, Fault::Gate(gate.clone()))
        .unwrap();
    hub.cancel(ticket).unwrap();
    assert_eq!(hub.budget.stats().jobs, 1);
    assert_eq!(
        hub.submit(id, body(2), 256, Fault::None).err(),
        Some(Error::Backpressure)
    );
    gate.release();
    let c = hub.next_completion().unwrap();
    assert_eq!(hub.budget.stats().jobs, 1);
    accept(&mut hub, c);
    assert_eq!(hub.budget.stats().jobs, 0);
    assert_eq!(hub.queued(id), 0);
    assert_eq!(hub.traffic.encrypted, 0);
    let next = hub.submit(id, body(2), 256, Fault::None).unwrap();
    assert_eq!(next.sequence(), 1);
    let c = hub.next_completion().unwrap();
    accept(&mut hub, c);
    assert!(!collect(&mut hub, id).is_empty());
}

#[test]
fn unload_reconnect_discards_old_generation_without_advancing_new_cipher() {
    let mut hub = Hub::new(3, OUTPUT_BUDGET);
    let old = hub.connect(4, [1; 16]).unwrap();
    let gate = Arc::new(Gate::default());
    hub.submit(old, body(1), -1, Fault::Gate(gate.clone()))
        .unwrap();
    hub.close(old).unwrap();
    let new = hub.connect(4, [2; 16]).unwrap();
    assert_ne!(old, new);
    let ticket = hub.submit(new, body(2), -1, Fault::None).unwrap();
    assert_eq!(ticket.sequence(), 0);
    let c = hub.next_completion().unwrap();
    assert_eq!(c.ticket(), ticket);
    let mut expected = c.plaintext().unwrap();
    accept(&mut hub, c);
    gate.release();
    let c = hub.next_completion().unwrap();
    accept(&mut hub, c);
    assert_eq!(hub.discarded, 1);
    cfb8::Encryptor::<aes::Aes128>::new(&[2; 16].into(), &[2; 16].into()).encrypt(&mut expected);
    assert_eq!(collect(&mut hub, new), expected);
    assert_eq!(hub.budget.stats().jobs, 0);
}

struct Short {
    bytes: Vec<u8>,
    calls: usize,
    fail: bool,
}
impl Write for Short {
    fn write(&mut self, b: &[u8]) -> io::Result<usize> {
        self.write_vectored(&[IoSlice::new(b)])
    }
    fn flush(&mut self) -> io::Result<()> {
        Ok(())
    }
    fn write_vectored(&mut self, parts: &[IoSlice<'_>]) -> io::Result<usize> {
        self.calls += 1;
        if self.fail && self.calls > 2 {
            return Err(io::ErrorKind::ConnectionReset.into());
        }
        if self.calls.is_multiple_of(5) {
            return Err(io::ErrorKind::WouldBlock.into());
        }
        let mut n = 0;
        for part in parts {
            for byte in part.iter() {
                if n == 3 {
                    return Ok(n);
                }
                self.bytes.push(*byte);
                n += 1;
            }
        }
        Ok(n)
    }
}
#[test]
fn partial_vectored_writes_resume_without_reencrypting_or_duplicating() {
    let mut hub = Hub::new(2, OUTPUT_BUDGET);
    let id = hub.connect(1, [4; 16]).unwrap();
    hub.submit(id, body(1), -1, Fault::None).unwrap();
    let c = hub.next_completion().unwrap();
    let mut expected = c.plaintext().unwrap();
    accept(&mut hub, c);
    let encrypted = hub.traffic.encrypted;
    let mut out = Short {
        bytes: Vec::new(),
        calls: 0,
        fail: false,
    };
    while !hub.send_once(id, &mut out, 17).unwrap() {}
    cfb8::Encryptor::<aes::Aes128>::new(&[4; 16].into(), &[4; 16].into()).encrypt(&mut expected);
    assert_eq!(out.bytes, expected);
    assert_eq!(hub.traffic.encrypted, encrypted);
    assert!(hub.traffic.os_short_writes > 0 && hub.traffic.would_block > 0);
    assert_eq!(hub.budget.stats().bytes, 0);
}

#[test]
fn scripted_partial_writer_error_is_terminal_and_pending_work_is_not_reused() {
    let mut hub = Hub::new(2, OUTPUT_BUDGET);
    let id = hub.connect(1, [4; 16]).unwrap();
    hub.submit(id, body(1), -1, Fault::None).unwrap();
    let c = hub.next_completion().unwrap();
    accept(&mut hub, c);
    let mut out = Short {
        bytes: Vec::new(),
        calls: 0,
        fail: true,
    };
    assert!(!hub.send_once(id, &mut out, 17).unwrap());
    assert!(!hub.send_once(id, &mut out, 17).unwrap());
    assert!(hub.send_once(id, &mut out, 17).is_err());
    assert_eq!(
        hub.submit(id, body(2), -1, Fault::None).err(),
        Some(Error::Closed)
    );
    assert_eq!(hub.queued(id), 0);
    assert_eq!(hub.budget.stats().bytes, 0);
    assert_eq!(out.bytes.len(), 6); // A committed network prefix cannot be rolled back.
}

#[test]
fn actual_loopback_reset_after_prefix_closes_connection_and_releases_pending_buffers() {
    use std::io::Read;
    use std::net::{TcpListener, TcpStream};
    use std::thread;
    use std::time::{Duration, Instant};
    let listener = TcpListener::bind((std::net::Ipv4Addr::LOCALHOST, 0)).unwrap();
    let address = listener.local_addr().unwrap();
    let receiver = thread::spawn(move || {
        let (mut stream, _) = listener.accept().unwrap();
        stream
            .set_read_timeout(Some(Duration::from_secs(2)))
            .unwrap();
        let mut prefix = [0u8; 32];
        stream.read_exact(&mut prefix).unwrap();
        socket2::SockRef::from(&stream)
            .set_linger(Some(Duration::ZERO))
            .unwrap();
        drop(stream);
        prefix
    });
    let mut socket = TcpStream::connect_timeout(&address, Duration::from_secs(2)).unwrap();
    socket.set_nonblocking(true).unwrap();
    socket.set_nodelay(true).unwrap();
    let mut hub = Hub::new(4, OUTPUT_BUDGET);
    let id = hub.connect(1, [6; 16]).unwrap();
    let b = Bodies::new()
        .encode(&Snapshot::new(33, MAX_BODY).unwrap(), 0)
        .unwrap();
    for _ in 0..3 {
        hub.submit(id, b.clone(), -1, Fault::None).unwrap();
    }
    let mut plain = BTreeMap::new();
    for _ in 0..3 {
        let c = hub.next_completion().unwrap();
        plain.insert(c.ticket().sequence(), c.plaintext().unwrap());
        accept(&mut hub, c);
    }
    let mut expected = plain.into_values().flatten().collect::<Vec<_>>();
    cfb8::Encryptor::<aes::Aes128>::new(&[6; 16].into(), &[6; 16].into()).encrypt(&mut expected);
    hub.send_once(id, &mut socket, 17).unwrap();
    hub.send_once(id, &mut socket, 17).unwrap();
    let prefix = receiver.join().unwrap();
    assert_eq!(prefix, &expected[..32]);
    let deadline = Instant::now() + Duration::from_secs(2);
    loop {
        match hub.send_once(id, &mut socket, 17) {
            Err(_) => break,
            Ok(_) => {
                assert!(Instant::now() < deadline, "peer reset was not observed");
                thread::sleep(Duration::from_millis(1));
            }
        }
    }
    assert_eq!(hub.budget.stats().bytes, 0);
    assert_eq!(
        hub.submit(id, b, -1, Fault::None).err(),
        Some(Error::Closed)
    );
    assert_eq!(hub.queued(id), 0);
}

#[test]
fn compression_failure_is_terminal_and_capacity_rejection_does_not_consume_sequence() {
    let mut small = Hub::new(2, 1);
    let id = small.connect(1, [9; 16]).unwrap();
    assert_eq!(
        small.submit(id, body(1), -1, Fault::None).err(),
        Some(Error::Backpressure)
    );
    assert_eq!(small.outstanding(), 0);
    let mut hub = Hub::new(2, OUTPUT_BUDGET);
    let id = hub.connect(1, [9; 16]).unwrap();
    let t = hub
        .submit(id, body(1), 256, Fault::CompressionFailure)
        .unwrap();
    assert_eq!(t.sequence(), 0);
    let c = hub.next_completion().unwrap();
    assert_eq!(hub.budget.stats().jobs, 1);
    accept(&mut hub, c);
    assert_eq!(
        hub.submit(id, body(1), 256, Fault::None).err(),
        Some(Error::Closed)
    );
    assert_eq!(hub.budget.stats().jobs, 0);
}

#[test]
fn completed_slow_output_still_holds_admission_budget_until_send() {
    let mut hub = Hub::new(1, OUTPUT_BUDGET);
    let id = hub.connect(1, [1; 16]).unwrap();
    hub.submit(id, body(1), 256, Fault::None).unwrap();
    let c = hub.next_completion().unwrap();
    accept(&mut hub, c);
    assert_eq!(hub.outstanding(), 0);
    assert_eq!(hub.budget.stats().jobs, 1);
    assert_eq!(
        hub.submit(id, body(2), 256, Fault::None).err(),
        Some(Error::Backpressure)
    );
    collect(&mut hub, id);
    let t = hub.submit(id, body(2), 256, Fault::None).unwrap();
    assert_eq!(t.sequence(), 1);
    let c = hub.next_completion().unwrap();
    accept(&mut hub, c);
    collect(&mut hub, id);
    assert_eq!(hub.budget.stats().jobs, 0);
}

#[test]
fn actual_slow_loopback_receiver_exercises_would_block_without_unbounded_queue() {
    use std::io::Read;
    use std::net::{Shutdown, TcpListener, TcpStream};
    use std::sync::mpsc;
    use std::thread;
    use std::time::{Duration, Instant};
    let listener = TcpListener::bind((std::net::Ipv4Addr::LOCALHOST, 0)).unwrap();
    let address = listener.local_addr().unwrap();
    let (ready_tx, ready_rx) = mpsc::channel();
    let (release_tx, release_rx) = mpsc::channel();
    let receiver = thread::spawn(move || {
        let (mut stream, _) = listener.accept().unwrap();
        stream
            .set_read_timeout(Some(Duration::from_secs(5)))
            .unwrap();
        socket2::SockRef::from(&stream)
            .set_recv_buffer_size(8192)
            .unwrap();
        ready_tx.send(()).unwrap();
        release_rx.recv_timeout(Duration::from_secs(7)).unwrap();
        let mut wire = Vec::new();
        let mut scratch = [0; 8192];
        loop {
            let n = stream.read(&mut scratch).unwrap();
            if n == 0 {
                break;
            }
            assert!(wire.len() + n < 3 * 1024 * 1024);
            wire.extend_from_slice(&scratch[..n]);
        }
        wire
    });
    let mut socket = TcpStream::connect_timeout(&address, Duration::from_secs(2)).unwrap();
    socket.set_nonblocking(true).unwrap();
    socket.set_nodelay(true).unwrap();
    socket2::SockRef::from(&socket)
        .set_send_buffer_size(1024)
        .unwrap();
    ready_rx.recv_timeout(Duration::from_secs(2)).unwrap();
    let mut hub = Hub::new(16, OUTPUT_BUDGET);
    let id = hub.connect(1, [8; 16]).unwrap();
    let body = Bodies::new()
        .encode(&Snapshot::new(17, MAX_BODY).unwrap(), 0)
        .unwrap();
    let deadline = Instant::now() + Duration::from_secs(8);
    let mut frame = None;
    let mut released = false;
    for _ in 0..32 {
        loop {
            match hub.submit(id, body.clone(), -1, Fault::None) {
                Ok(_) => break,
                Err(Error::Backpressure) => {
                    if hub.outstanding() > 0 {
                        let c = hub.next_completion().unwrap();
                        if frame.is_none() {
                            frame = c.plaintext();
                        }
                        accept(&mut hub, c);
                    }
                    let before = hub.traffic.sent;
                    hub.send_once(id, &mut socket, 65536).unwrap();
                    if !released && hub.traffic.would_block > 0 {
                        release_tx.send(()).unwrap();
                        released = true;
                    }
                    if before == hub.traffic.sent {
                        thread::sleep(Duration::from_millis(1));
                    }
                    assert!(Instant::now() < deadline, "bounded send timeout");
                }
                Err(e) => panic!("submit {e:?}"),
            }
        }
    }
    while hub.outstanding() > 0 {
        let c = hub.next_completion().unwrap();
        if frame.is_none() {
            frame = c.plaintext();
        }
        accept(&mut hub, c);
        hub.send_once(id, &mut socket, 65536).unwrap();
    }
    while !hub.send_once(id, &mut socket, 65536).unwrap() {
        assert!(Instant::now() < deadline);
        thread::sleep(Duration::from_millis(1));
    }
    if !released {
        release_tx.send(()).unwrap();
    }
    socket.shutdown(Shutdown::Write).unwrap();
    let mut wire = receiver.join().unwrap();
    cfb8::Decryptor::<aes::Aes128>::new(&[8; 16].into(), &[8; 16].into()).decrypt(&mut wire);
    let frame = frame.unwrap();
    assert_eq!(wire.len(), frame.len() * 32);
    for part in wire.chunks_exact(frame.len()) {
        assert_eq!(part, frame);
    }
    assert!(
        hub.traffic.would_block > 0,
        "real socket pressure was not exercised"
    );
    assert!(hub.budget.stats().peak_bytes <= OUTPUT_BUDGET);
    assert_eq!(hub.budget.stats().bytes, 0);
    assert_eq!(hub.traffic.sent, wire.len() as u64);
    println!(
        "SOCKET_PRESSURE would_block={} short_writes={} bytes={} peak_reserved={}",
        hub.traffic.would_block,
        hub.traffic.os_short_writes,
        hub.traffic.sent,
        hub.budget.stats().peak_bytes
    );
}

#[test]
fn canceled_order_hole_retains_credit_until_preceding_work_quiesces() {
    let mut hub = Hub::new(2, OUTPUT_BUDGET);
    let id = hub.connect(1, [3; 16]).unwrap();
    let gate = Arc::new(Gate::default());
    hub.submit(id, body(1), -1, Fault::Gate(gate.clone()))
        .unwrap();
    let canceled = hub.submit(id, body(2), -1, Fault::None).unwrap();
    hub.cancel(canceled).unwrap();
    let c = hub.next_completion().unwrap();
    assert_eq!(c.ticket(), canceled);
    accept(&mut hub, c);
    let held = hub.budget.stats().jobs;
    let blocked = hub.submit(id, body(3), -1, Fault::None).err();
    gate.release();
    assert_eq!(
        held, 2,
        "canceled order hole released admission credit too early"
    );
    assert_eq!(blocked, Some(Error::Backpressure));
    let c = hub.next_completion().unwrap();
    accept(&mut hub, c);
    assert_eq!(hub.queued(id), 1);
    assert_eq!(hub.budget.stats().jobs, 1);
    collect(&mut hub, id);
    assert_eq!(hub.budget.stats().jobs, 0);
}

#[test]
fn abandoned_completion_handle_cannot_grow_controller_tracking_without_bound() {
    let mut hub = Hub::new(1, OUTPUT_BUDGET);
    let id = hub.connect(1, [1; 16]).unwrap();
    hub.submit(id, body(1), -1, Fault::None).unwrap();
    drop(hub.next_completion().unwrap());
    assert_eq!(hub.budget.stats().jobs, 0);
    assert_eq!(hub.outstanding(), 1);
    assert_eq!(
        hub.submit(id, body(2), -1, Fault::None).err(),
        Some(Error::Backpressure)
    );
    // Abandonment is fail-closed until teardown; it cannot manufacture a gap
    // skip, advance the cipher, or release tracking space for another job.
    assert_eq!(hub.queued(id), 0);
    assert_eq!(hub.traffic.encrypted, 0);
}
