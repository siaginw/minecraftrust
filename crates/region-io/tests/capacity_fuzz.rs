//! Goal §5 forensics: reproduce the live CAPACITY_ERROR-on-small-payload
//! (lastFailLen=8188) observed in the Gate C ON campaign. Fuzzes the exact
//! live sequence: engine writes with realistic stream sizes across all 1024
//! slots, monotonic Java tickets, RegionFileCache-style reopen cycles (fresh
//! engine from disk, tickets re-seeded at floors=0), and occasional
//! note_external_write fallbacks.

use region_io::live::{LiveRegionFile, STATUS_SUCCESS};

use std::io::{Read, Seek, SeekFrom, Write};

fn zlib_stream(seed: u64, len: usize) -> Vec<u8> {
    use std::io::Write;
    let mut z = flate2::write::ZlibEncoder::new(Vec::new(), flate2::Compression::default());
    // mixed compressibility like real chunks
    let mut x = seed | 1;
    let mut raw = vec![0u8; len];
    for b in raw.iter_mut() {
        x ^= x << 13;
        x ^= x >> 7;
        x ^= x << 17;
        *b = (x >> 16) as u8;
    }
    z.write_all(&raw).unwrap();
    z.finish().unwrap()
}

fn temp(name: &str) -> std::path::PathBuf {
    let dir = std::env::temp_dir().join("regionio-capacity-fuzz");
    std::fs::create_dir_all(&dir).unwrap();
    let p = dir.join(name);
    let _ = std::fs::remove_file(&p);
    p
}

/// A vanilla fallback: writes a record directly with vanilla semantics and
/// returns the entry it installed (for note_external_write).
fn vanilla_fallback(path: &std::path::Path, slot: usize, stream: &[u8]) -> u32 {
    // allocate at the current file end
    let mut f = std::fs::OpenOptions::new()
        .read(true)
        .write(true)
        .open(path)
        .unwrap();
    let file_len = f.metadata().unwrap().len() as usize;
    let needed = (stream.len() + 5).div_ceil(4096).max(1);
    let start = std::cmp::max(file_len.div_ceil(4096), 2);
    f.seek(SeekFrom::Start((start * 4096) as u64)).unwrap();
    let total = (stream.len() + 1) as u32;
    f.write_all(&total.to_be_bytes()).unwrap();
    f.write_all(&[2u8]).unwrap();
    f.write_all(stream).unwrap();
    let pad = needed * 4096 - (stream.len() + 5);
    if pad > 0 {
        f.write_all(&vec![0u8; pad]).unwrap();
    }
    let entry = ((start as u32) << 8) | (needed as u32 & 0xFF);
    f.seek(SeekFrom::Start((slot * 4) as u64)).unwrap();
    f.write_all(&entry.to_be_bytes()).unwrap();
    f.seek(SeekFrom::Start((4096 + slot * 4) as u64)).unwrap();
    f.write_all(&42u32.to_be_bytes()).unwrap();
    f.flush().unwrap();
    entry
}

#[test]
fn fuzz_live_capacity_small_payloads() {
    let path = temp("fuzz.mca");
    let mut tickets = [0u64; 1024];
    let mut op = 0u64;
    let mut capacity_failures: Vec<(u64, usize, usize, usize)> = Vec::new();
    for round in 0..60u64 {
        let engine = LiveRegionFile::open(&path).unwrap();
        // ~240 writes per engine generation (models one RegionFile lifetime)
        for _ in 0..240 {
            op += 1;
            let slot = ((op * 29 + round * 7) as usize) % 1024;
            // realistic chunk stream sizes: 1KB .. 60KB, occasional 120KB
            let raw_len = match (op * 13) % 32 {
                0..=19 => 1_000 + ((op * 37) as usize % 8_000),
                20..=29 => 8_000 + ((op * 53) as usize % 30_000),
                _ => 40_000 + ((op * 71) as usize % 90_000),
            };
            let stream = zlib_stream(op, raw_len);
            tickets[slot] += 1;
            match engine.write_chunk((slot % 32) as u8, (slot / 32) as u8, &stream, tickets[slot]) {
                Ok(_) => {}
                Err(region_io::live::STATUS_CAPACITY_ERROR) => {
                    let needed = (stream.len() + 5).div_ceil(4096).max(1);
                    let st = engine.debug_capacity_state();
                    let site = region_io::live::DEBUG_CAP_LOCK.lock().unwrap().take();
                    panic!(
                        "op {} slot {} stream {} needed {}: CAPACITY; \
                         used_len={} tail_free={} runs_beyond_map={} site={:?}",
                        op,
                        slot,
                        stream.len(),
                        needed,
                        st.0,
                        st.1,
                        st.2,
                        site
                    );
                }
                Err(c) => panic!("op {} slot {} unexpected status {}", op, slot, c),
            }
            // occasional coordinated vanilla fallback (goal §8 direction 1)
            if op % 97 == 0 {
                let fslot = (op as usize * 11) % 1024;
                let entry = vanilla_fallback(
                    &path,
                    fslot,
                    &zlib_stream(op + 500_000, 2_000 + (op as usize % 9_000)),
                );
                tickets[fslot] += 1;
                assert_eq!(
                    engine
                        .note_external_write(
                            (fslot % 32) as u8,
                            (fslot / 32) as u8,
                            entry,
                            tickets[fslot]
                        )
                        .map(|_| STATUS_SUCCESS)
                        .unwrap_or(1),
                    STATUS_SUCCESS
                );
            }
        }
        // engine dropped here = RegionFileCache eviction + reopen
    }
    assert!(
        capacity_failures.is_empty(),
        "CAPACITY_ERROR on small payloads: {:?}",
        &capacity_failures[..capacity_failures.len().min(5)]
    );
}
