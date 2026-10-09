use offline_storage_experiment::*;
use offline_storage_experiment::{sidecar, snapshot::Snapshot};
use redb::ReadableDatabase;
use std::path::Path;
use std::time::Instant;

fn run(args: &[String]) -> AnyResult<()> {
    if args.len() != 7
        || args[4].len() != 32
        || args[5].len() != 64
        || !args[4..=5]
            .iter()
            .all(|s| s.bytes().all(|b| b.is_ascii_hexdigit()))
    {
        return Err(
            invalid("expected mode source new_output session_hex32 challenge_hex64 epoch").into(),
        );
    }
    let mode = &args[1];
    if !["buffered", "mmap", "crash-uncommitted", "recover"].contains(&mode.as_str()) {
        return Err(invalid("unknown mode").into());
    }
    let epoch = args[6].parse::<u64>()?;
    if epoch == 0 {
        return Err(invalid("zero epoch").into());
    }
    let folder = Path::new(&args[3]);
    if mode != "recover" {
        std::fs::create_dir(folder)?;
    }
    let total = Instant::now();
    let start = Instant::now();
    let mut snapshot = Snapshot::copy_from(
        Path::new(&args[2]),
        &folder.join(if mode == "recover" {
            "recovery-owned.bin"
        } else {
            "owned.bin"
        }),
    )?;
    let copy_ns = start.elapsed().as_nanos();
    let start = Instant::now();
    let source = snapshot.sha256()?;
    let hash_ns = start.elapsed().as_nanos();
    let mut map_ns = 0;
    let (h, entries, scan_ns) = if mode == "mmap" {
        let start = Instant::now();
        let view = snapshot.map(0, snapshot.len())?;
        map_ns = start.elapsed().as_nanos();
        let start = Instant::now();
        let (h, e) = scan_bytes(view.bytes())?;
        (h, e, start.elapsed().as_nanos())
    } else {
        let start = Instant::now();
        let (h, e) = snapshot.scan_buffered()?;
        (h, e, start.elapsed().as_nanos())
    };
    if h.epoch != epoch {
        return Err(invalid("source epoch mismatch").into());
    }
    let start = Instant::now();
    let entries = finish_index(&h, entries)?;
    let binding = sidecar::Binding::new(&h, source, &entries);
    let index_ns = start.elapsed().as_nanos();
    let path = folder.join("index.redb");
    let start = Instant::now();
    let db = sidecar::open(&path, mode != "recover")?;
    if mode != "recover" {
        sidecar::build(&db, &binding, &entries)?;
    }
    let commit_ns = start.elapsed().as_nanos();
    if mode == "crash-uncommitted" {
        let tx = db.begin_write()?;
        {
            let mut table = tx.open_table(sidecar::INDEX)?;
            let mut wrong = entries[0].clone();
            wrong.value += 1;
            table.insert(wrong.key, wrong.encode_value().as_slice())?;
            let mut meta = tx.open_table(sidecar::META)?;
            meta.insert(0, b"uncommitted".as_slice())?;
        }
        println!("{{\"schema\":\"OFFLINE_STORAGE_CRASH_CONTROL_V1\",\"session\":\"{}\",\"challenge\":\"{}\",\"stage\":\"active_uncommitted_transaction\"}}",args[4],args[5]);
        use std::io::Write;
        std::io::stdout().flush()?;
        std::process::exit(71);
    }
    let start = Instant::now();
    let read = db.begin_read()?;
    let verified = sidecar::verify_index(&read, &binding)?;
    let verify_ns = start.elapsed().as_nanos();
    let start = Instant::now();
    let query = sidecar::query_hash(&read, &binding)?;
    let query_ns = start.elapsed().as_nanos();
    drop(read);
    drop(db);
    let start = Instant::now();
    let reopened = sidecar::open(&path, false)?;
    let read = reopened.begin_read()?;
    sidecar::verify_index(&read, &binding)?;
    if sidecar::query_hash(&read, &binding)? != query {
        return Err(invalid("reopen query drift").into());
    }
    drop(read);
    drop(reopened);
    let reopen_ns = start.elapsed().as_nanos();
    let total_ns = total.elapsed().as_nanos();
    let sidecar_bytes = std::fs::metadata(&path)?.len();
    if sidecar_bytes > sidecar::MAX_DATABASE_BYTES {
        return Err(invalid("sidecar cap").into());
    }
    println!("{{\"schema\":\"OFFLINE_STORAGE_SAMPLE_V1\",\"session\":\"{}\",\"challenge\":\"{}\",\"mode\":\"{}\",\"epoch\":{},\"records\":{},\"source_bytes\":{},\"source_sha256\":\"{}\",\"index_sha256\":\"{}\",\"query_sha256\":\"{}\",\"sidecar_bytes\":{},\"production_authority\":false,\"timings_ns\":{{\"snapshot_copy_sync\":{},\"source_hash\":{},\"map_create\":{},\"scan\":{},\"index_sort_hash\":{},\"sidecar_open_commit\":{},\"sidecar_verify\":{},\"sidecar_queries\":{},\"sidecar_reopen_verify\":{},\"complete\":{}}}}}",args[4],args[5],mode,epoch,h.count,snapshot.len(),hex(&source),hex(&verified),hex(&query),sidecar_bytes,copy_ns,hash_ns,map_ns,scan_ns,index_ns,commit_ns,verify_ns,query_ns,reopen_ns,total_ns);
    Ok(())
}
fn main() {
    if let Err(error) = run(&std::env::args().collect::<Vec<_>>()) {
        eprintln!("CONTROLLED_ERROR: {error}");
        std::process::exit(2)
    }
}
