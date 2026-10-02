//! Region tools: scan / readall / writechunk / worldscan over real .mca files.
//!
//! - `scan <file.mca> [--nbt]`
//! - `worldscan <world/region-dir> [--nbt]` — scans every region, prints a JSON summary
//! - `readall <world/region-dir> <out-hashes.json>` — SHA-256 per decompressed chunk payload
//!   (the semantic comparator input for the shadow-read campaign)
use region_io::{read_chunk, scan, RegionWriter};
use sha2::{Digest, Sha256};
use std::collections::BTreeMap;
use std::fs;
use std::path::PathBuf;

fn main() {
    let args: Vec<String> = std::env::args().collect();
    match args.get(1).map(String::as_str) {
        Some("scan") => {
            let nbt = args.iter().any(|a| a == "--nbt");
            let report = scan(&PathBuf::from(&args[2]), nbt).unwrap();
            println!(
                "{} file_bytes={} present={} absent={} bad={} overlap={} decompress_fail={} nbt_fail={} gzip={} zlib={} payload_bytes={}",
                report.path.display(),
                report.file_bytes,
                report.present_chunks,
                report.absent_chunks,
                report.bad_entries.len(),
                report.overlapping_sectors,
                report.decompress_failures.len(),
                report.nbt_parse_failures.len(),
                report.gzip_chunks,
                report.zlib_chunks,
                report.total_payload_bytes
            );
        }
        Some("worldscan") => {
            let nbt = args.iter().any(|a| a == "--nbt");
            let dir = PathBuf::from(&args[2]);
            let mut total = (0u64, 0u64, 0u64, 0u64); // files, present, bad, nbt_fail
            let mut entries: Vec<_> = fs::read_dir(&dir)
                .expect("region dir")
                .filter_map(|e| e.ok())
                .map(|e| e.path())
                .filter(|p| p.extension().map(|x| x == "mca").unwrap_or(false))
                .collect();
            entries.sort();
            for path in entries {
                let report = scan(&path, nbt).unwrap();
                total.0 += 1;
                total.1 += report.present_chunks as u64;
                total.2 += report.bad_entries.len() as u64;
                total.3 += report.nbt_parse_failures.len() as u64;
                if report.overlapping_sectors
                    || !report.bad_entries.is_empty()
                    || !report.decompress_failures.is_empty()
                    || !report.nbt_parse_failures.is_empty()
                {
                    println!(
                        "PROBLEM {} overlap={} bad={} decompress_fail={} nbt_fail={}",
                        report.path.display(),
                        report.overlapping_sectors,
                        report.bad_entries.len(),
                        report.decompress_failures.len(),
                        report.nbt_parse_failures.len()
                    );
                }
            }
            println!(
                "WORLDSCAN files={} present_chunks={} bad_entries={} nbt_failures={}",
                total.0, total.1, total.2, total.3
            );
        }
        Some("readall") => {
            let dir = PathBuf::from(&args[2]);
            let out = PathBuf::from(&args[3]);
            let mut hashes: BTreeMap<String, String> = BTreeMap::new();
            let mut entries: Vec<_> = fs::read_dir(&dir)
                .expect("region dir")
                .filter_map(|e| e.ok())
                .map(|e| e.path())
                .filter(|p| p.extension().map(|x| x == "mca").unwrap_or(false))
                .collect();
            entries.sort();
            for path in entries {
                let stem = path.file_stem().unwrap().to_string_lossy().to_string();
                for z in 0..32u8 {
                    for x in 0..32u8 {
                        if let Ok(Some(rec)) = read_chunk(&path, x, z) {
                            let mut h = Sha256::new();
                            h.update(&rec.payload);
                            hashes.insert(
                                format!("{stem}:{x},{z}"),
                                format!("{:x}:{}", h.finalize(), rec.payload.len()),
                            );
                        }
                    }
                }
            }
            let json = hashes
                .iter()
                .map(|(k, v)| format!("\"{k}\": \"{v}\""))
                .collect::<Vec<_>>()
                .join(",\n");
            fs::write(&out, format!("{{\n{json}\n}}\n")).unwrap();
            println!("READALL hashes={}", hashes.len());
        }
        Some("writechunk") => {
            // writechunk <file.mca> <x> <z> <raw-bytes-file>
            let path = PathBuf::from(&args[2]);
            let x: u8 = args[3].parse().unwrap();
            let z: u8 = args[4].parse().unwrap();
            let raw = fs::read(&args[5]).unwrap();
            let mut w = RegionWriter::open(&path).unwrap();
            w.write_chunk(x, z, &raw).unwrap();
            w.commit().unwrap();
            println!("WROTE {path:?} {x},{z} raw_len={}", raw.len());
        }
        _ => {
            eprintln!("usage: region_tools scan|worldscan|readall|writechunk ...");
            std::process::exit(2);
        }
    }
}
