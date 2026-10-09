use region_io::live_read::{RegionReader, READ_MISSING, READ_SUCCESS};
fn main() {
    let dir = std::path::Path::new(
        "target/authority-review/region-write-campaign-A-20261003-110408/server/world/region",
    );
    let mut fails = Vec::new();
    let mut ok = 0;
    let mut missing = 0;
    for entry in std::fs::read_dir(dir).unwrap() {
        let p = entry.unwrap().path();
        if p.extension().map(|e| e != "mca").unwrap_or(true) {
            continue;
        }
        let r = RegionReader::open(&p).unwrap();
        for slot in 0..1024 {
            let mut out = Vec::new();
            match r
                .read_chunk((slot % 32) as u8, (slot / 32) as u8, &mut out)
                .unwrap()
            {
                READ_SUCCESS => ok += 1,
                READ_MISSING => missing += 1,
                other => fails.push((
                    p.file_name().unwrap().to_string_lossy().to_string(),
                    slot,
                    other,
                )),
            }
        }
    }
    println!("ok={} missing={} fails={}", ok, missing, fails.len());
    for f in fails.iter().take(5) {
        println!("  {:?}", f);
    }
}
