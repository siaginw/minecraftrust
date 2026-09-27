use allocator_experiment::{meter, workload::*};
use native_observability::process_memory;
use serde_json::json;
use std::{
    io::Write,
    path::{Path, PathBuf},
    sync::{
        atomic::{AtomicUsize, Ordering},
        Arc,
    },
    time::Instant,
};
#[global_allocator]
static ALLOCATOR: meter::Tracking = meter::Tracking;
fn write_json(path: &Path, value: &serde_json::Value) -> Result<(), String> {
    let mut file = std::fs::OpenOptions::new()
        .create_new(true)
        .write(true)
        .open(path)
        .map_err(|e| e.to_string())?;
    file.write_all(serde_json::to_string_pretty(value).unwrap().as_bytes())
        .map_err(|e| e.to_string())
}
fn check_path(path: &Path) -> Result<(), String> {
    let root = Path::new(env!("CARGO_MANIFEST_DIR"))
        .parent()
        .unwrap()
        .parent()
        .unwrap()
        .join("target")
        .canonicalize()
        .map_err(|e| e.to_string())?;
    let parent = path
        .parent()
        .ok_or("output parent")?
        .canonicalize()
        .map_err(|e| e.to_string())?;
    if !parent.starts_with(root) {
        return Err("output outside isolated target".into());
    }
    Ok(())
}
fn memory() -> serde_json::Value {
    let m = process_memory();
    json!({"working_set_bytes":m.working_set_bytes,"private_commit_bytes":m.private_commit_bytes,"allocator_usable_bytes":null,"pure_fragmentation_bytes":null})
}
fn correctness(path: &Path) -> Result<(), String> {
    check_path(path)?;
    std::fs::create_dir(path).map_err(|e| e.to_string())?;
    let fixtures: Arc<Vec<_>> = Arc::new((0..8).map(fixture).collect());
    let mut digests = Vec::new();
    for scratch in [Scratch::Vec, Scratch::Arena] {
        for delivery in [Delivery::SameThread, Delivery::CrossThread] {
            let b = batch(fixtures.clone(), scratch, delivery, true)?;
            digests.push(b.digest);
            if b.drops != 8 {
                return Err("drop mismatch".into());
            }
        }
    }
    if digests.iter().any(|d| *d != digests[0]) {
        return Err("FIRST_DIVERGENCE mode digest".into());
    }
    let drops = Arc::new(AtomicUsize::new(0));
    for i in 0..8 {
        let item = produce(i as u32, &fixtures[i], Scratch::Arena, true, drops.clone())?;
        std::fs::write(path.join(format!("packet-{i}.zlib")), &item.packet)
            .map_err(|e| e.to_string())?;
        std::fs::write(
            path.join(format!("persistence-{i}.zlib")),
            &item.persistence,
        )
        .map_err(|e| e.to_string())?;
    }
    if drops.load(Ordering::SeqCst) != 8 {
        return Err("published artifact drop count".into());
    }
    write_json(
        &path.join("result.json"),
        &json!({"schema":"H17_CORRECTNESS_V1","status":"PASS","backend":meter::NAME,"publications_per_mode":8,"modes":4,"digests":digests,"independent_packet_cells":4*8*4*4096,"nbt_exact_preservation":true,"production_allocator_changed":false}),
    )
}
#[derive(Clone, Copy)]
struct Row {
    sample: i32,
    ns: u64,
    cpu: Option<u64>,
    before: meter::Counts,
    after: meter::Counts,
    working: Option<u64>,
    private: Option<u64>,
    digest: u64,
    drops: usize,
    retained: usize,
    arena: usize,
    cross: usize,
    retained_after_exit: usize,
    retained_live: u64,
    retained_working: Option<u64>,
    retained_private: Option<u64>,
}
fn run(
    path: &Path,
    scratch: Scratch,
    delivery: Delivery,
    samples: usize,
    chunks: usize,
) -> Result<(), String> {
    if !(1..=32).contains(&samples) || !(1..=MAX_CHUNKS).contains(&chunks) {
        return Err("sample/chunk bounds".into());
    }
    check_path(path)?;
    let start_counts = meter::snapshot();
    let start_memory = memory();
    let fixtures: Arc<Vec<_>> = Arc::new((0..chunks as u32).map(fixture).collect());
    let mut rows = Vec::with_capacity(samples + 3);
    let mut expected = None;
    let campaign_cpu_start = meter::cpu_ns();
    let campaign_start = Instant::now();
    for sample in -3..samples as i32 {
        meter::reset_peak();
        let before = meter::snapshot();
        let cpu = meter::cpu_ns();
        let now = Instant::now();
        let batch = batch(fixtures.clone(), scratch, delivery, false)?;
        let ns = u64::try_from(now.elapsed().as_nanos()).map_err(|_| "time overflow")?;
        let end_cpu = meter::cpu_ns();
        let after = meter::snapshot();
        let mem = process_memory();
        if before.overflow || after.overflow || after.failures != before.failures {
            return Err("allocation counter/failure".into());
        }
        if let Some(digest) = expected {
            if digest != batch.digest {
                return Err("FIRST_DIVERGENCE repeated batch".into());
            }
        } else {
            expected = Some(batch.digest)
        }
        rows.push(Row {
            sample,
            ns,
            cpu: cpu.zip(end_cpu).and_then(|(a, b)| b.checked_sub(a)),
            before,
            after,
            working: mem.working_set_bytes,
            private: mem.private_commit_bytes,
            digest: batch.digest,
            drops: batch.drops,
            retained: batch.max_retained_payload,
            arena: batch.arena_reserved_max,
            cross: batch.cross_thread_final_frees,
            retained_after_exit: batch.retained_after_producer_exit_payload,
            retained_live: batch.retained_requested_live,
            retained_working: batch.retained_working_set,
            retained_private: batch.retained_private_commit,
        });
    }
    let campaign_wall_ns =
        u64::try_from(campaign_start.elapsed().as_nanos()).map_err(|_| "time overflow")?;
    let campaign_cpu_ns = campaign_cpu_start
        .zip(meter::cpu_ns())
        .and_then(|(a, b)| b.checked_sub(a));
    drop(fixtures);
    let final_counts = meter::snapshot();
    let final_memory = memory();
    let values:Vec<_>=rows.iter().map(|r|json!({"sample":r.sample,"warmup":r.sample<0,"wall_ns":r.ns,"process_cpu_ns":r.cpu,"alloc_before":meter::json(r.before),"alloc_after":meter::json(r.after),"allocated_delta":r.after.allocated-r.before.allocated,"freed_delta":r.after.freed-r.before.freed,"working_set_bytes":r.working,"private_commit_bytes":r.private,"private_minus_requested_live_proxy":r.private.map(|n|n as i64-r.after.live as i64),"pure_fragmentation_bytes":null,"digest":r.digest,"destructors":r.drops,"consumer_max_retained_payload_bytes":r.retained,"arena_reserved_max":r.arena,"cross_thread_final_frees":r.cross,"retained_after_producer_exit_payload_bytes":r.retained_after_exit,"retained_requested_live_bytes":r.retained_live,"retained_working_set_bytes":r.retained_working,"retained_private_commit_bytes":r.retained_private})).collect();
    write_json(
        path,
        &json!({"schema":"H17_ALLOCATOR_RUN_V1","status":"PASS","backend":meter::NAME,"scratch":scratch.name(),"delivery":delivery.name(),"samples":samples,"chunks_per_batch":chunks,"warmup_batches":3,"campaign_including_warmup_wall_ns":campaign_wall_ns,"campaign_including_warmup_cpu_ns":campaign_cpu_ns,"production_allocator_changed":false,"scope":"retained-section and packet/compression + lossless NBT in-memory load/edit/recompress + publish/consume/final-drop; excludes disk, JNI, socket and server","start_counts":meter::json(start_counts),"start_memory":start_memory,"after_input_drop_counts":meter::json(final_counts),"after_input_drop_memory":final_memory,"rows":values}),
    )
}
fn main() -> Result<(), String> {
    let args: Vec<_> = std::env::args().collect();
    if args.get(1).is_some_and(|v| v == "--capacity-control") {
        let mut original = vec![1u8, 2, 3];
        let failed = original.try_reserve_exact(512 * 1024 * 1024).is_err();
        assert_eq!(original, [1, 2, 3]);
        println!(
            "{}",
            json!({"schema":"H17_CAPACITY_CONTROL_V1","backend":meter::NAME,"reservation_rejected":failed,"original_preserved":true,"requested_bytes":512*1024*1024})
        );
        return if failed {
            Ok(())
        } else {
            Err("reservation unexpectedly accepted under Job cap".into())
        };
    }
    let path = PathBuf::from(args.get(2).ok_or("output")?);
    match args.get(1).map(String::as_str) {
        Some("--correctness") => correctness(&path),
        Some("--run") => {
            let scratch = match args.get(3).map(String::as_str) {
                Some("vec") => Scratch::Vec,
                Some("arena") => Scratch::Arena,
                _ => return Err("scratch".into()),
            };
            let delivery = match args.get(4).map(String::as_str) {
                Some("same") => Delivery::SameThread,
                Some("cross") => Delivery::CrossThread,
                _ => return Err("delivery".into()),
            };
            run(
                &path,
                scratch,
                delivery,
                args.get(5)
                    .ok_or("samples")?
                    .parse()
                    .map_err(|_| "samples")?,
                args.get(6).ok_or("chunks")?.parse().map_err(|_| "chunks")?,
            )
        }
        _ => Err("mode".into()),
    }
}
