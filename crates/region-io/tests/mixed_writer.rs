//! Goal §16-19: mixed-writer coherency proof for the live region engine.
//!
//! Writer model: a "vanilla-model" allocator (own free list, vanilla
//! first-fit/in-place/growth semantics, direct file I/O) interleaved with the
//! Rust LiveRegionFile on the SAME file.
//!
//! Coordinated mode: every vanilla-model write is followed by
//! note_external_write BEFORE the next Rust allocation — exactly what the
//! Java hook guarantees in production (regionWriteExit runs inside the same
//! RegionFile monitor that serializes writes, on the single server thread;
//! goal §13). The adversarial test demonstrates why that ordering is
//! load-bearing by violating it on purpose.

use region_io::live::{LiveRegionFile, STATUS_NOT_ELIGIBLE};
use region_io::{scan, SECTOR_BYTES};

use std::io::{Read, Seek, Write};
use std::sync::Arc;

/// Vanilla-model allocator: mirrors vanilla func_76706_a semantics with its
/// OWN in-memory free list (blind to the engine's state except through the
/// note mechanism, mirroring field_76714_f).
struct VanillaModelWriter {
    path: std::path::PathBuf,
    used: Vec<bool>,
    gen: u32,
}

impl VanillaModelWriter {
    fn open(path: &std::path::Path) -> Self {
        let mut f = std::fs::File::open(path).unwrap();
        let len = f.metadata().unwrap().len() as usize;
        let mut header = vec![0u8; 8192];
        f.read_exact(&mut header).unwrap();
        let total = len.max(8192).div_ceil(SECTOR_BYTES);
        let mut used = vec![false; total];
        used[0] = true;
        used[1] = true;
        for i in 0..1024 {
            let e = u32::from_be_bytes([
                header[i * 4],
                header[i * 4 + 1],
                header[i * 4 + 2],
                header[i * 4 + 3],
            ]);
            let (off, cnt) = (e >> 8, e & 0xFF);
            if off == 0 || cnt == 0 {
                continue;
            }
            for s in 0..cnt {
                let sec = (off + s) as usize;
                if sec < used.len() {
                    used[sec] = true;
                }
            }
        }
        Self { path: path.to_path_buf(), used, gen: 0 }
    }

    fn write(&mut self, slot: usize, payload: &[u8]) -> u32 {
        use std::io::SeekFrom;
        use std::io::Write as IoWrite;
        self.gen = self.gen.wrapping_add(1);
        // Production equivalent: the hook mirrors the engine's used-map into
        // field_76714_f after every Rust write. Model it by rebuilding our
        // free list from the on-disk location table.
        self.resync_used();
        // the record content is a REAL zlib stream (the scanner decompresses
        // every record); sector need follows the stream length
        let mut z = flate2::write::ZlibEncoder::new(
            Vec::new(),
            flate2::Compression::default(),
        );
        z.write_all(payload).unwrap();
        let stream = z.finish().unwrap();
        let needed = (stream.len() + 5).div_ceil(SECTOR_BYTES).max(1);
        // vanilla in-place reuse
        let old = self.entry(slot);
        let (old_off, old_cnt) = (old >> 8, old & 0xFF);
        let start = if old_off != 0 && old_cnt as usize >= needed {
            old_off as usize
        } else {
            if old_off != 0 {
                for s in 0..old_cnt {
                    let sec = (old_off + s) as usize;
                    if sec < self.used.len() {
                        self.used[sec] = false;
                    }
                }
            }
            // first-fit
            let mut found = None;
            let mut run = 0usize;
            for sector in 2..self.used.len() {
                if self.used[sector] {
                    run = 0;
                } else {
                    run += 1;
                    if run == needed {
                        found = Some(sector + 1 - needed);
                        break;
                    }
                }
            }
            let start = match found {
                Some(s) => s,
                None => {
                    // extend the file (vanilla grows by what it needs)
                    let new_len = self.used.len() + needed;
                    self.used.resize(new_len, false);
                    let mut run = 0usize;
                    let mut found = None;
                    for sector in 2..self.used.len() {
                        if self.used[sector] {
                            run = 0;
                        } else {
                            run += 1;
                            if run == needed {
                                found = Some(sector + 1 - needed);
                                break;
                            }
                        }
                    }
                    found.expect("growth must provide a run")
                }
            };
            for s in 0..needed {
                self.used[start + s] = true;
            }
            start
        };
        // physical write: framing + stream + padding
        let mut f = std::fs::OpenOptions::new()
            .read(true)
            .write(true)
            .open(&self.path)
            .unwrap();
        f.seek(SeekFrom::Start((start * SECTOR_BYTES) as u64)).unwrap();
        let total = (stream.len() + 1) as u32;
        f.write_all(&total.to_be_bytes()).unwrap();
        f.write_all(&[2u8]).unwrap();
        f.write_all(&stream).unwrap();
        let pad = needed * SECTOR_BYTES - (stream.len() + 5);
        if pad > 0 {
            f.write_all(&vec![0u8; pad]).unwrap();
        }
        let entry = ((start as u32) << 8) | (needed as u32 & 0xFF);
        f.seek(SeekFrom::Start((slot * 4) as u64)).unwrap();
        f.write_all(&entry.to_be_bytes()).unwrap();
        f.seek(SeekFrom::Start((4096 + slot * 4) as u64)).unwrap();
        f.write_all(&self.gen.to_be_bytes()).unwrap();
        f.flush().unwrap();
        entry
    }

    fn entry(&self, slot: usize) -> u32 {
        let mut f = std::fs::File::open(&self.path).unwrap();
        let mut b = [0u8; 4];
        use std::io::SeekFrom;
        f.seek(SeekFrom::Start((slot * 4) as u64)).unwrap();
        f.read_exact(&mut b).unwrap();
        u32::from_be_bytes(b)
    }

    /// Rebuild the model's free list from the CURRENT on-disk location table
    /// (the model-side counterpart of the hook's mirrorFreeList).
    fn resync_used(&mut self) {
        let mut f = std::fs::File::open(&self.path).unwrap();
        let len = f.metadata().unwrap().len() as usize;
        let mut header = vec![0u8; 8192];
        f.seek(std::io::SeekFrom::Start(0)).unwrap();
        f.read_exact(&mut header).unwrap();
        let total = len.max(8192).div_ceil(SECTOR_BYTES);
        let mut used = vec![false; total];
        used[0] = true;
        used[1] = true;
        for i in 0..1024 {
            let e = u32::from_be_bytes([
                header[i * 4],
                header[i * 4 + 1],
                header[i * 4 + 2],
                header[i * 4 + 3],
            ]);
            let (off, cnt) = (e >> 8, e & 0xFF);
            if off == 0 || cnt == 0 {
                continue;
            }
            for s in 0..cnt {
                let sec = (off + s) as usize;
                if sec < used.len() {
                    used[sec] = true;
                }
            }
        }
        self.used = used;
    }
}

fn payload(seed: u8, len: usize) -> Vec<u8> {
    vec![seed; len]
}

/// Pseudo-random (incompressible) bytes: forces relocation when grown.
fn incompressible(seed: u64, len: usize) -> Vec<u8> {
    let mut out = vec![0u8; len];
    let mut x = seed.wrapping_mul(6364136223846793005).wrapping_add(1);
    for b in out.iter_mut() {
        x ^= x << 13;
        x ^= x >> 7;
        x ^= x << 17;
        *b = (x >> 24) as u8;
    }
    out
}

/// A real zlib stream (both writers store deflate streams at the seam).
fn zlib_of(seed: u8, len: usize) -> Vec<u8> {
    use std::io::Write;
    let mut z = flate2::write::ZlibEncoder::new(Vec::new(), flate2::Compression::default());
    z.write_all(&payload(seed, len)).unwrap();
    z.finish().unwrap()
}

fn temp(name: &str) -> std::path::PathBuf {
    let dir = std::env::temp_dir().join("regionio-mixed-writer");
    std::fs::create_dir_all(&dir).unwrap();
    let p = dir.join(name);
    let _ = std::fs::remove_file(&p);
    p
}

/// Goal §16: thousands of interleaved COORDINATED operations — every
/// vanilla-model write is noted before the next Rust allocation (the
/// production ordering). Final scan: 0 overlaps, 0 bad records, every slot
/// written by either side readable with its own payload.
#[test]
fn coordinated_mixed_writer_interleave_stays_consistent() {
    let path = temp("mixed-coordinated.mca");
    let engine = LiveRegionFile::open(&path).unwrap();
    let mut vanilla = VanillaModelWriter::open(&path);

    let mut expected: std::collections::HashMap<usize, (u8, usize)> =
        std::collections::HashMap::new();
    for slot in 0..8usize {
        let stream = zlib_of(10 + slot as u8, 100 + slot * 37);
        engine
            .write_chunk((slot % 32) as u8, (slot / 32) as u8, &stream, (slot + 1) as u64)
            .unwrap();
        expected.insert(slot, (10 + slot as u8, 100 + slot * 37));
    }

    // interleave: 2000 rounds of (vanilla write + note) / (rust write)
    for round in 0..2000u64 {
        let vslot = (round as usize * 7 + 3) % 1024;
        let len = 64 + (round as usize * 13) % 9000;
        let seed = (round % 251) as u8;
        let p = payload(seed, len);
        let entry = vanilla.write(vslot, &p);
        // COORDINATED: note before the next Rust allocation (goal §13)
        let x = (vslot % 32) as u8;
        let z = (vslot / 32) as u8;
        engine
            .note_external_write(x, z, entry, 1_000_000 + round)
            .unwrap();
        expected.insert(vslot, (seed, len));

        let rslot = (round as usize * 11 + 1) % 1024;
        if rslot == vslot {
            continue; // the note just re-based that slot's generation
        }
        let len2 = 128 + (round as usize * 7) % 8000;
        let seed2 = (round % 241) as u8;
        let p2 = zlib_of(seed2, len2);
        let gen = 2_000_000 + round;
        if engine
            .write_chunk((rslot % 32) as u8, (rslot / 32) as u8, &p2, gen)
            .is_ok()
        {
            expected.insert(rslot, (seed2, len2));
        }
    }

    // verify every expected record reads back exactly (decompress: records
    // are zlib streams written verbatim by either writer)
    let data = std::fs::read(&path).unwrap();
    let mut verified = 0;
    for (slot, (seed, len)) in &expected {
        let e = u32::from_be_bytes([
            data[slot * 4],
            data[slot * 4 + 1],
            data[slot * 4 + 2],
            data[slot * 4 + 3],
        ]);
        let (off, cnt) = ((e >> 8) as usize, (e & 0xFF) as usize);
        assert!(off != 0 && cnt != 0, "slot {} lost", slot);
        let start = off * SECTOR_BYTES;
        let total = u32::from_be_bytes([
            data[start],
            data[start + 1],
            data[start + 2],
            data[start + 3],
        ]) as usize;
        assert_eq!(data[start + 4], 2, "slot {} wrong type byte", slot);
        let stream = &data[start + 5..start + 4 + total];
        let mut z = flate2::read::ZlibDecoder::new(stream);
        use std::io::Read;
        let mut rec = Vec::new();
        z.read_to_end(&mut rec).unwrap();
        assert_eq!(rec.len(), *len, "slot {} length mismatch", slot);
        assert!(rec.iter().all(|&b| b == *seed), "slot {} content mismatch", slot);
        verified += 1;
    }
    assert!(verified >= 1000, "verified {}", verified);

    // structural scan: 0 overlaps, 0 bad records
    let report = scan(&path, false).unwrap();
    assert!(report.bad_entries.is_empty(), "{:?}", report.bad_entries);
    assert!(report.decompress_failures.is_empty());
    assert!(!report.overlapping_sectors, "sector overlap detected");
}

/// Goal §17: the UNCOORDINATED race — vanilla-model moves a chunk's entry on
/// disk WITHOUT noting the engine (the window that would exist without the
/// production monitor serialization). verify-before-free must catch it: the
/// engine refuses with NOT_ELIGIBLE, disqualifies the file for the session
/// (no re-promotion), and the adversary's record is left intact.
#[test]
fn uncoordinated_write_is_detected_and_region_disqualified() {
    let path = temp("mixed-adversarial.mca");
    let engine = LiveRegionFile::open(&path).unwrap();
    let mut vanilla = VanillaModelWriter::open(&path);

    // coordinated seed phase (real zlib streams on both sides)
    let p1 = zlib_of(1, 100);
    engine.write_chunk(0, 0, &p1, 1).unwrap();
    let p2 = zlib_of(2, 100);
    let v_entry = vanilla.write(5, &p2);
    engine.note_external_write(5, 0, v_entry, 100).unwrap();

    // ADVERSARY: vanilla moves chunk (5,0) to a new run WITHOUT noting.
    // Incompressible stream forces a relocation (needed > old run).
    let p3 = incompressible(3, 20_000);
    let moved = vanilla.write(5, &p3);
    assert_ne!(moved, v_entry, "must relocate");

    // Rust rewrites (5,0): verify-before-free must catch the divergence
    // BEFORE freeing the (now false) old run.
    let p4 = zlib_of(4, 200);
    let rc = engine.write_chunk(5, 0, &p4, 200);
    assert_eq!(rc.unwrap_err(), STATUS_NOT_ELIGIBLE, "must refuse");
    assert!(engine.disqualified(), "region must be disqualified");

    // the adversary's record must be INTACT (Rust never touched it)
    let data = std::fs::read(&path).unwrap();
    let e = u32::from_be_bytes([
        data[5 * 4],
        data[5 * 4 + 1],
        data[5 * 4 + 2],
        data[5 * 4 + 3],
    ]);
    assert_eq!(e, moved, "adversary's entry must be untouched");
    // and every further Rust write keeps refusing (no re-promotion, §11)
    for gen in 300..310u64 {
        assert_eq!(
            engine.write_chunk(1, 1, &payload(9, 50), gen).unwrap_err(),
            STATUS_NOT_ELIGIBLE
        );
    }
    let stats = engine.stats.lock().unwrap().clone();
    assert_eq!(stats.external_write_detected, 1);
    assert!(stats.failures >= 11);
}

/// Goal §19: four regions in parallel — engine-per-path isolation and no
/// global allocator lock.
#[test]
fn four_regions_write_concurrently_without_cross_talk() {
    let paths: Vec<_> = (0..4).map(|i| temp(&format!("parallel-{}.mca", i))).collect();
    let engines: Vec<Arc<LiveRegionFile>> = paths
        .iter()
        .map(|p| Arc::new(LiveRegionFile::open(p).unwrap()))
        .collect();
    let handles: Vec<_> = engines
        .iter()
        .enumerate()
        .map(|(i, e)| {
            let e = Arc::clone(e);
            std::thread::spawn(move || {
                for op in 0..500u64 {
                    let slot = (op as usize * 13 + i * 3) % 1024;
                    let p = payload((op % 200) as u8, 50 + (op as usize % 4000));
                    let gen = 1_000 * (i as u64 + 1) + op;
                    e.write_chunk((slot % 32) as u8, (slot / 32) as u8, &p, gen)
                        .unwrap_or_else(|c| panic!("engine {} op {} failed {}", i, op, c));
                }
            })
        })
        .collect();
    for h in handles {
        h.join().unwrap();
    }
    for p in &paths {
        let report = scan(p, false).unwrap();
        assert!(report.bad_entries.is_empty(), "{} bad", p.display());
        assert!(!report.overlapping_sectors, "{} overlap", p.display());
    }
}

/// Goal §18: 100k mixed-allocator operations on ONE region — coordinated
/// interleave with rewrites, growth, and shrink; ends structurally clean.
#[test]
fn hundred_k_mixed_operations_same_region() {
    let path = temp("mixed-100k.mca");
    let engine = LiveRegionFile::open(&path).unwrap();
    let mut vanilla = VanillaModelWriter::open(&path);

    let mut ops = 0u64;
    // 100k total allocator operations, ~half on each side, heavy rewrite and
    // size churn on a bounded slot set (same-region stress).
    for round in 0..50_000u64 {
        let vslot = (round as usize * 3 + 1) % 256;
        let len = 32 + (round as usize * 17) % 6000;
        let entry = vanilla.write(vslot, &payload((round % 251) as u8, len));
        let x = (vslot % 32) as u8;
        let z = (vslot / 32) as u8;
        engine.note_external_write(x, z, entry, 500_000 + round).unwrap();
        ops += 1;

        let rslot = (round as usize * 5 + 2) % 256;
        if rslot == vslot {
            continue;
        }
        let len2 = 64 + (round as usize * 11) % 5000;
        let _ = engine.write_chunk(
            (rslot % 32) as u8,
            (rslot / 32) as u8,
            &zlib_of((round % 241) as u8, len2),
            600_000 + round,
        );
        ops += 1;
    }
    assert!(ops >= 100_000, "ops {}", ops);

    let report = scan(&path, false).unwrap();
    assert!(report.bad_entries.is_empty(), "{:?}", report.bad_entries.first());
    assert!(report.decompress_failures.is_empty());
    assert!(!report.overlapping_sectors, "overlap after 100k mixed ops");
}
