mod candidates;
mod lossless;
mod region;
use serde_json::{Value, json};
use std::{
    fs,
    path::{Path, PathBuf},
    time::Instant,
};

fn fixture_path(path: &Path) -> Result<PathBuf, String> {
    let root = Path::new(env!("CARGO_MANIFEST_DIR"))
        .parent()
        .unwrap()
        .parent()
        .unwrap()
        .join("target")
        .canonicalize()
        .map_err(|e| e.to_string())?;
    let normalized = path.canonicalize().map_err(|e| e.to_string())?;
    if !normalized.starts_with(root) {
        return Err("fixture path outside isolated target".into());
    }
    Ok(normalized)
}
fn first_difference(expected: &[u8], actual: &[u8]) -> Option<Value> {
    let offset = expected
        .iter()
        .zip(actual)
        .position(|(a, b)| a != b)
        .or_else(|| (expected.len() != actual.len()).then_some(expected.len().min(actual.len())))?;
    let start = offset.saturating_sub(12);
    Some(
        json!({"offset":offset,"expected_len":expected.len(),"actual_len":actual.len(),
        "context_start":start,"expected_context":&expected[start..expected.len().min(offset+20)],
        "actual_context":&actual[start.min(actual.len())..actual.len().min(offset+20)]}),
    )
}
fn process(
    parser: &str,
    input: &Path,
    oracle: &Path,
    output: &Path,
    iterations: usize,
) -> Result<Value, String> {
    let initial = region::read_bounded(input, region::MAX_REGION)?;
    if let Err(e) = region::validate(&initial) {
        return Ok(json!({"status":"GUARD_REJECT","reason":e}));
    }
    let raw = match region::decompress(region::compressed_chunk(&initial, 0)?) {
        Ok(v) => v,
        Err(e) => return Ok(json!({"status":"GUARD_REJECT","reason":e})),
    };
    let scan = match lossless::scan(&raw) {
        Ok(v) => v,
        Err(e) => return Ok(json!({"status":"GUARD_REJECT","reason":e})),
    };
    let expected = region::read_bounded(oracle, lossless::MAX_RAW)?;
    let expected_edit = match lossless::edit(&raw, &scan) {
        Ok(v) => v,
        Err(e) => return Ok(json!({"status":"GUARD_REJECT","reason":e})),
    };
    if expected_edit != expected {
        return Err("JAVA_ORACLE_MISMATCH".into());
    }
    let mut parse_samples = Vec::new();
    for _ in 0..5 {
        let start = Instant::now();
        for _ in 0..100 {
            if let Err(e) = candidates::parse_only(parser, &raw) {
                return Ok(json!({"status":"CANDIDATE_REJECT","reason":e}));
            }
        }
        parse_samples.push(start.elapsed().as_nanos() as u64);
    }
    let mut samples = Vec::new();
    for iteration in 0..iterations {
        let destination = output.join(format!("ordered-{iteration}.mca"));
        fs::write(&destination, &initial).map_err(|e| e.to_string())?;
        let start = Instant::now();
        let mut stages = Vec::new();
        let mut checkpoint = Instant::now();
        let source = region::read_bounded(input, region::MAX_REGION)?;
        region::validate(&source)?;
        stages.push(checkpoint.elapsed().as_nanos() as u64);
        checkpoint = Instant::now();
        let plain = region::decompress(region::compressed_chunk(&source, 0)?)?;
        stages.push(checkpoint.elapsed().as_nanos() as u64);
        checkpoint = Instant::now();
        let preflight = lossless::scan(&plain)?;
        let encoded = match candidates::edit(parser, &plain, &preflight) {
            Ok(v) => v,
            Err(e) => return Ok(json!({"status":"CANDIDATE_REJECT","reason":e})),
        };
        stages.push(checkpoint.elapsed().as_nanos() as u64);
        checkpoint = Instant::now();
        if let Some(difference) = first_difference(&expected, &encoded) {
            fs::write(output.join("FIRST-DIVERGENCE.actual.nbt"), &encoded)
                .map_err(|e| e.to_string())?;
            return Ok(
                json!({"status":"PRESERVATION_LOSS","first_divergence":difference,"rejected_attempt_publication":false,"earlier_exact_attempts":iteration,"parse_100_ns":parse_samples}),
            );
        }
        let compressed = region::compress(&encoded)?;
        stages.push(checkpoint.elapsed().as_nanos() as u64);
        checkpoint = Instant::now();
        let mut writer = region::Writer::open(&destination)?;
        let transaction = writer.prepare(0, &compressed)?;
        writer.commit(transaction, "none")?;
        stages.push(checkpoint.elapsed().as_nanos() as u64);
        checkpoint = Instant::now();
        let reread = region::read_bounded(&destination, region::MAX_REGION)?;
        region::validate(&reread)?;
        let decoded = region::decompress(region::compressed_chunk(&reread, 0)?)?;
        if decoded != expected {
            return Err("published bytes differ".into());
        }
        if reread[4..initial.len()] != initial[4..] {
            return Err("unrelated region bytes changed".into());
        }
        stages.push(checkpoint.elapsed().as_nanos() as u64);
        samples.push(json!({"total_ns":start.elapsed().as_nanos() as u64,"stage_ns":stages}));
    }
    Ok(
        json!({"status":"EXACT_PASS","parser":parser,"raw_bytes":raw.len(),"nodes":scan.nodes,
        "data_version":scan.data_version,"max_neid":scan.max_neid,"parse_100_ns":parse_samples,
        "pipeline_samples":samples,"publication":true,"stage_order":["read_validate","decompress","bounded_parse_extract_modify_encode","preservation_check_compress","ordered_sync_rename","reopen_validate"]}),
    )
}

fn controls(input: &Path, output: &Path) -> Result<Value, String> {
    let initial = region::read_bounded(input, region::MAX_REGION)?;
    region::validate(&initial)?;
    let raw = region::decompress(region::compressed_chunk(&initial, 0)?)?;
    let edited = lossless::edit(&raw, &lossless::scan(&raw)?)?;
    let compressed = region::compress(&edited)?;
    let mut checks = Vec::new();
    for fault in ["partial-write", "before-sync", "before-rename"] {
        let path = output.join(format!("fault-{fault}.mca"));
        fs::write(&path, &initial).map_err(|e| e.to_string())?;
        let mut writer = region::Writer::open(&path)?;
        let t = writer.prepare(0, &compressed)?;
        if writer.commit(t, fault).is_ok() || fs::read(&path).map_err(|e| e.to_string())? != initial
        {
            return Err("rollback control".into());
        }
        let retry = writer.prepare(0, &compressed)?;
        writer.commit(retry, "none")?;
        checks.push(fault);
    }
    let path = output.join("ordered-aba.mca");
    fs::write(&path, &initial).map_err(|e| e.to_string())?;
    let mut writer = region::Writer::open(&path)?;
    let stale = writer.prepare(0, &compressed)?;
    let current = writer.prepare(0, &compressed)?;
    writer.commit(current, "none")?;
    let committed = fs::read(&path).map_err(|e| e.to_string())?;
    if writer.commit(stale, "none").is_ok()
        || fs::read(&path).map_err(|e| e.to_string())? != committed
    {
        return Err("stale result accepted".into());
    }
    checks.push("stale-ordered-write");
    let foreign_path = output.join("foreign-writer.mca");
    fs::write(&foreign_path, &initial).map_err(|e| e.to_string())?;
    let mut foreign = region::Writer::open(&foreign_path)?;
    let wrong = writer.prepare(0, &compressed)?;
    if foreign.commit(wrong, "none").is_ok()
        || fs::read(&foreign_path).map_err(|e| e.to_string())? != initial
    {
        return Err("foreign writer transaction accepted".into());
    }
    checks.push("foreign-writer");
    let foreign_pending = foreign_path.with_extension("pending-1");
    fs::write(&foreign_pending, b"retain sentinel").map_err(|e| e.to_string())?;
    let blocked = foreign.prepare(0, &compressed)?;
    if foreign.commit(blocked, "none").is_ok()
        || fs::read(&foreign_path).map_err(|e| e.to_string())? != initial
        || fs::read(&foreign_pending).map_err(|e| e.to_string())? != b"retain sentinel"
    {
        return Err("temporary collision touched existing file".into());
    }
    checks.push("temporary-name-collision");
    #[cfg(windows)]
    {
        use std::os::windows::fs::OpenOptionsExt;
        let locked_path = output.join("rename-locked.mca");
        fs::write(&locked_path, &initial).map_err(|e| e.to_string())?;
        let mut locked_writer = region::Writer::open(&locked_path)?;
        let lock = fs::OpenOptions::new()
            .read(true)
            .share_mode(1)
            .open(&locked_path)
            .map_err(|e| e.to_string())?;
        let blocked = locked_writer.prepare(0, &compressed)?;
        if locked_writer.commit(blocked, "none").is_ok()
            || fs::read(&locked_path).map_err(|e| e.to_string())? != initial
        {
            return Err("OS rename refusal changed target".into());
        }
        drop(lock);
        let retry = locked_writer.prepare(0, &compressed)?;
        locked_writer.commit(retry, "none")?;
        checks.push("actual-windows-rename-refusal-and-retry");
    }
    let other = writer.prepare(0, &compressed)?;
    fs::write(&path, &initial).map_err(|e| e.to_string())?;
    if writer.commit(other, "none").is_ok() {
        return Err("external-change accepted".into());
    }
    checks.push("external-change");
    let mut overlap = initial.clone();
    overlap[4..8].copy_from_slice(&initial[0..4]);
    if region::validate(&overlap).is_ok() {
        return Err("overlap accepted".into());
    }
    checks.push("overlap");
    for code in [1, 3, 128, 130, 255] {
        let mut bad = initial.clone();
        bad[8196] = code;
        if region::validate(&bad).is_ok() {
            return Err("compression accepted".into());
        }
    }
    checks.push("unsupported-compression-and-external-flags");
    let mut bad = initial.clone();
    bad[8192..8196].copy_from_slice(&u32::MAX.to_be_bytes());
    if region::validate(&bad).is_ok() {
        return Err("length accepted".into());
    }
    checks.push("region-length");
    let bomb = vec![0; lossless::MAX_RAW + 1];
    use std::io::Write;
    let mut enc = flate2::write::ZlibEncoder::new(Vec::new(), flate2::Compression::new(6));
    enc.write_all(&bomb).map_err(|e| e.to_string())?;
    if region::decompress(&enc.finish().map_err(|e| e.to_string())?).is_ok() {
        return Err("bomb accepted".into());
    }
    checks.push("zip-bomb");
    let mut trailing = compressed.clone();
    trailing.push(0);
    if region::decompress(&trailing).is_ok()
        || region::decompress(&compressed[..compressed.len() - 1]).is_ok()
    {
        return Err("stream boundary accepted".into());
    }
    checks.push("truncated-and-trailing-zlib");
    let mut full = initial.clone();
    full.resize(region::MAX_REGION, 0);
    if region::replace_chunk(&full, 0, &compressed).is_ok() {
        return Err("region capacity accepted".into());
    }
    checks.push("region-capacity");
    if region::compress(&vec![0; lossless::MAX_RAW + 1]).is_ok() {
        return Err("encode capacity accepted".into());
    }
    checks.push("encode-capacity");
    Ok(json!({"status":"PASS","checks":checks,"count":checks.len(),"production_authority":false}))
}

fn main() {
    let args: Vec<_> = std::env::args().collect();
    let result = (|| -> Result<Value, String> {
        match args.get(1).map(String::as_str){
            Some("memory-limit-control") => {
                let mut allocations=Vec::new();
                for _ in 0..32 { allocations.push(vec![37u8;8*1024*1024]); std::hint::black_box(&allocations); }
                Ok(json!({"unexpected_memory_success":true}))
            }
            Some("timeout-control") => { std::thread::sleep(std::time::Duration::from_secs(60)); Ok(json!({"unexpected_timeout_success":true})) }
            Some("process") if args.len()==7 => {
                let input=fixture_path(Path::new(&args[3]))?;let oracle=fixture_path(Path::new(&args[4]))?;
                let output=fixture_path(Path::new(&args[5]))?;
                let iterations:usize=args[6].parse().map_err(|_|"iterations")?;
                if iterations==0 || iterations>20{return Err("iteration limit".into());}
                process(&args[2],&input,&oracle,&output,iterations)
            }
            Some("controls") if args.len()==4 => controls(&fixture_path(Path::new(&args[2]))?,&fixture_path(Path::new(&args[3]))?),
            Some("backend") => Ok(json!({"backend":"zlib-rs via Cargo feature selection verified by runner","level":6})),
            _=>Err("usage process parser input oracle output iterations | controls input output | backend".into()),
        }
    })();
    match result {
        Ok(v) => println!("{}", v),
        Err(e) => {
            eprintln!("{}", json!({"status":"ERROR","reason":e}));
            std::process::exit(1);
        }
    }
}
