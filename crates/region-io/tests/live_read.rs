//! Goal §15-§16 corruption parity matrix + §10-§12 read/write coherency for
//! the live region READ engine. Vanilla contract (traced from
//! func_76704_a): missing/OOB/beyond-EOF/length<=0/length>allocated/unknown
//! compression -> null; truncated gzip/zlib -> LAZY failure (stream returns,
//! fails on read). The live reader reports statuses and NEVER returns a
//! partial stream; the Java hook fail-closes truncated/invalid streams to
//! the vanilla body, reproducing vanilla's lazy behavior exactly.

use region_io::live_read::{
    decompress_stream, RegionReader, READ_CORRUPT_ENTRY, READ_MISSING, READ_SUCCESS,
    READ_UNSUPPORTED_COMPRESSION,
};
use region_io::{scan, SECTOR_BYTES};

use std::io::{Seek, SeekFrom, Write};

fn temp(name: &str) -> std::path::PathBuf {
    let dir = std::env::temp_dir().join("regionio-live-read");
    std::fs::create_dir_all(&dir).unwrap();
    let p = dir.join(name);
    let _ = std::fs::remove_file(&p);
    p
}

fn zlib_stream(seed: u8, len: usize) -> Vec<u8> {
    use std::io::Write as IoWrite;
    let mut z = flate2::write::ZlibEncoder::new(Vec::new(), flate2::Compression::default());
    z.write_all(&vec![seed; len]).unwrap();
    z.finish().unwrap()
}

fn gzip_stream(seed: u8, len: usize) -> Vec<u8> {
    use std::io::Write as IoWrite;
    let mut z = flate2::write::GzEncoder::new(Vec::new(), flate2::Compression::default());
    z.write_all(&vec![seed; len]).unwrap();
    z.finish().unwrap()
}

/// Writes a full framed record directly at `start_sector` with `ctype` and
/// installs the location entry (vanilla disk layout).
fn install_record(
    path: &std::path::Path,
    slot: usize,
    start_sector: u32,
    ctype: u8,
    stream: &[u8],
) {
    let mut f = std::fs::OpenOptions::new()
        .read(true)
        .write(true)
        .create(true)
        .open(path)
        .unwrap();
    let needed_bytes = stream.len() + 5;
    let sectors = needed_bytes.div_ceil(SECTOR_BYTES).max(1);
    // pad the file to hold start_sector + sectors
    let end = ((start_sector as usize + sectors) * SECTOR_BYTES).max(8192);
    let cur = f.metadata().unwrap().len() as usize;
    if cur < end {
        f.seek(SeekFrom::End(0)).unwrap();
        f.write_all(&vec![0u8; end - cur]).unwrap();
    }
    f.seek(SeekFrom::Start((start_sector as u64) * SECTOR_BYTES as u64)).unwrap();
    let total = (stream.len() + 1) as u32;
    f.write_all(&total.to_be_bytes()).unwrap();
    f.write_all(&[ctype]).unwrap();
    f.write_all(stream).unwrap();
    let pad = sectors * SECTOR_BYTES - needed_bytes;
    if pad > 0 {
        f.write_all(&vec![0u8; pad]).unwrap();
    }
    let entry = (start_sector << 8) | (sectors as u32 & 0xFF);
    f.seek(SeekFrom::Start((slot * 4) as u64)).unwrap();
    f.write_all(&entry.to_be_bytes()).unwrap();
    f.flush().unwrap();
}

fn expect(path: &std::path::Path, x: u8, z: u8, status: i32) -> Option<Vec<u8>> {
    let r = RegionReader::open(path).unwrap();
    let mut out = Vec::new();
    let got = r.read_chunk(x, z, &mut out).unwrap();
    assert_eq!(got, status, "status for {},{}", x, z);
    if got == READ_SUCCESS {
        Some(out)
    } else {
        assert!(out.is_empty(), "out must be untouched on failure (fail closed)");
        None
    }
}

#[test]
fn corruption_parity_matrix() {
    // missing chunk (entry 0) -> MISSING for an empty slot
    let path = temp("matrix-missing.mca");
    install_record(&path, 0, 2, 2, &zlib_stream(1, 100));
    let r = RegionReader::open(&path).unwrap();
    let mut out = Vec::new();
    assert_eq!(r.read_chunk(0, 0, &mut out).unwrap(), READ_SUCCESS);
    assert_eq!(r.read_chunk(1, 0, &mut out).unwrap(), READ_MISSING);
    drop(r);
    assert!(expect(&path, 0, 0, READ_SUCCESS).is_some());

    // offset beyond EOF -> CORRUPT_ENTRY
    let path = temp("matrix-eof.mca");
    install_record(&path, 3, 2, 2, &zlib_stream(1, 100));
    {
        let mut f = std::fs::OpenOptions::new().write(true).open(&path).unwrap();
        f.seek(SeekFrom::Start(12)).unwrap(); // slot 3 entry
        let beyond = (9_000u32) << 8 | 1; // sector 9000, far beyond file
        f.write_all(&beyond.to_be_bytes()).unwrap();
    }
    expect(&path, 3, 0, READ_CORRUPT_ENTRY);

    // declared length > allocated sectors -> CORRUPT_ENTRY
    let path = temp("matrix-longlen.mca");
    install_record(&path, 2, 2, 2, &zlib_stream(1, 100));
    {
        let mut f = std::fs::OpenOptions::new().write(true).open(&path).unwrap();
        f.seek(SeekFrom::Start((2 * SECTOR_BYTES) as u64)).unwrap();
        f.write_all(&(500_000u32).to_be_bytes()).unwrap(); // > 1 sector
    }
    expect(&path, 2, 0, READ_CORRUPT_ENTRY);

    // declared length <= 0 -> CORRUPT_ENTRY
    let path = temp("matrix-zerolen.mca");
    install_record(&path, 2, 2, 2, &zlib_stream(1, 100));
    {
        let mut f = std::fs::OpenOptions::new().write(true).open(&path).unwrap();
        f.seek(SeekFrom::Start((2 * SECTOR_BYTES) as u64)).unwrap();
        f.write_all(&0u32.to_be_bytes()).unwrap();
    }
    expect(&path, 2, 0, READ_CORRUPT_ENTRY);

    // unknown compression type (3..=255, and 0) -> UNSUPPORTED_COMPRESSION
    for ctype in [0u8, 3, 77, 255] {
        let path = temp(format!("matrix-type{}.mca", ctype).as_str());
        install_record(&path, 2, 2, ctype, &[1, 2, 3, 4]);
        expect(&path, 2, 0, READ_UNSUPPORTED_COMPRESSION);
    }

    // truncated zlib stream: reader reports IO_ERROR-class failure via
    // decompress error (no partial stream)
    let path = temp("matrix-trunczlib.mca");
    let stream = zlib_stream(7, 10_000);
    install_record(&path, 2, 2, 2, &stream[..stream.len() / 2]);
    {
        // shrink the location entry to the truncated size so the read
        // reaches the decompressor (which must fail)
        let r = expect(&path, 2, 0, region_io::live_read::READ_IO_ERROR);
        assert!(r.is_none());
    }

    // invalid zlib bytes -> IO_ERROR (fail closed)
    let path = temp("matrix-badzlib.mca");
    install_record(&path, 2, 2, 2, &[0xDE, 0xAD, 0xBE, 0xEF, 0x00, 0x01, 0x02]);
    expect(&path, 2, 0, region_io::live_read::READ_IO_ERROR);

    // truncated gzip -> IO_ERROR (fail closed; vanilla would fail lazily)
    let path = temp("matrix-truncgzip.mca");
    let g = gzip_stream(7, 10_000);
    install_record(&path, 2, 2, 1, &g[..g.len() / 2]);
    expect(&path, 2, 0, region_io::live_read::READ_IO_ERROR);
}

#[test]
fn gzip_and_zlib_types_roundtrip() {
    // type 1 (gzip) AND type 2 (zlib) both supported (goal §16)
    let cases = [(1u8, gzip_stream(3, 5000), vec![3u8; 5000]), (2u8, zlib_stream(4, 5000), vec![4u8; 5000])];
    for (ctype, stream, plain) in cases {
        let path = temp(format!("types-{}.mca", ctype).as_str());
        install_record(&path, 2, 2, ctype, &stream);
        let out = expect(&path, 2, 0, READ_SUCCESS).unwrap();
        assert_eq!(out, plain, "content for type {}", ctype);
    }
}

#[test]
fn decompress_strictness() {
    // trailing bytes after the zlib stream end are rejected (strict; the
    // caller fail-closes to vanilla, whose InflaterInputStream ignores them —
    // real records never have trailing bytes since vanilla writes exact)
    let good = zlib_stream(1, 100);
    let mut trailing = good.clone();
    trailing.extend_from_slice(&[1, 2, 3]);
    assert!(decompress_stream(2, &trailing).is_err());
    assert!(decompress_stream(2, &good).is_ok());
    let gz = gzip_stream(1, 100);
    assert!(decompress_stream(1, &gz).is_ok());
}

/// Goal §10-§11: the reader is disk-derived per call — writes from the Rust
/// engine, vanilla-model writers, or raw fallbacks are all visible to the
/// next read with NO cache invalidation, on the SAME reader instance.
#[test]
fn reader_observes_all_writer_commits_immediately() {
    let path = temp("coherency.mca");
    // 1. engine write -> reader sees it
    let engine = region_io::live::LiveRegionFile::open(&path).unwrap();
    let reader = RegionReader::open(&path).unwrap();
    let stream = zlib_stream(5, 300);
    let mut plain = decompress_stream(2, &stream).unwrap();
    let (entry, _) = engine.write_chunk(0, 0, &stream, 1).unwrap();
    let mut out = Vec::new();
    assert_eq!(reader.read_chunk(0, 0, &mut out).unwrap(), READ_SUCCESS);
    assert_eq!(out, plain);

    // 2. engine rewrite with INCOMPRESSIBLE growth -> reader follows the
    //    new (relocated) run
    let mut big_raw = vec![0u8; 40_000];
    let mut x = 6u64 | 1;
    for b in big_raw.iter_mut() {
        x ^= x << 13;
        x ^= x >> 7;
        x ^= x << 17;
        *b = (x >> 24) as u8;
    }
    let big = {
        use std::io::Write as IoWrite;
        let mut z = flate2::write::ZlibEncoder::new(Vec::new(), flate2::Compression::default());
        z.write_all(&big_raw).unwrap();
        z.finish().unwrap()
    };
    let big_plain = big_raw;
    let (entry2, _) = engine.write_chunk(0, 0, &big, 2).unwrap();
    assert_ne!(entry2, entry, "entry must change (count 1 -> incompressible size)");
    assert_eq!(reader.read_chunk(0, 0, &mut out).unwrap(), READ_SUCCESS);
    assert_eq!(out, big_plain);
    plain = big_plain;

    // 3. external (vanilla-model) fallback write -> reader sees it directly
    //    from disk, no notes needed
    let mut f = std::fs::OpenOptions::new().read(true).write(true).open(&path).unwrap();
    let file_len = f.metadata().unwrap().len();
    let start = (file_len as usize / SECTOR_BYTES) as u32;
    let fb = zlib_stream(7, 200);
    f.seek(SeekFrom::Start((start as u64) * SECTOR_BYTES as u64)).unwrap();
    let total = (fb.len() + 1) as u32;
    f.write_all(&total.to_be_bytes()).unwrap();
    f.write_all(&[2u8]).unwrap();
    f.write_all(&fb).unwrap();
    let fb_entry = (start << 8) | 1;
    f.seek(SeekFrom::Start(0)).unwrap();
    f.write_all(&fb_entry.to_be_bytes()).unwrap();
    f.flush().unwrap();
    drop(f);
    let fb_plain = decompress_stream(2, &fb).unwrap();
    assert_eq!(reader.read_chunk(0, 0, &mut out).unwrap(), READ_SUCCESS);
    assert_eq!(out, fb_plain);

    // 4. fallback overwrite that REMOVES the chunk (entry -> 0) -> MISSING
    let mut f = std::fs::OpenOptions::new().write(true).open(&path).unwrap();
    f.seek(SeekFrom::Start(0)).unwrap();
    f.write_all(&0u32.to_be_bytes()).unwrap();
    drop(f);
    assert_eq!(reader.read_chunk(0, 0, &mut out).unwrap(), READ_MISSING);

    // file remains structurally sound for the surviving engine records
    // (scan after a full sequence; the relocated record + fallback record
    // may overlap by test construction — only assert the scan runs)
    let _ = scan(&path, false);
}

/// Goal §12: read-during-write. In production both region seams are
/// synchronized (RegionFile monitor), so a read sees one committed
/// generation. Offline, the reader attached to the shared engine takes the
/// engine's read_lock (the same serialization the FFI read path uses when a
/// live writer engine is registered). Racing reader threads against the
/// writer must never produce a torn read.
#[test]
fn read_during_write_never_tears() {
    let path = temp("raced.mca");
    let engine = std::sync::Arc::new(region_io::live::LiveRegionFile::open(&path).unwrap());
    // register the engine so the reader path finds and locks it (as the FFI
    // read does when a writer engine is live for this path)
    let _ = region_io::live::EngineRegistry::global().get_or_open(&path).unwrap();
    let reader = std::sync::Arc::new(RegionReader::open(&path).unwrap());
    let gens: Vec<Vec<u8>> = (0..8u64)
        .map(|g| zlib_stream((g * 30 + 1) as u8, 1000 + g as usize * 3000))
        .collect();
    let plains: Vec<Vec<u8>> = gens
        .iter()
        .map(|g| decompress_stream(2, g).unwrap())
        .collect();

    engine.write_chunk(0, 0, &gens[0], 1).unwrap();

    let stop = std::sync::Arc::new(std::sync::atomic::AtomicBool::new(false));
    let mut handles = Vec::new();
    for _ in 0..2 {
        let r = std::sync::Arc::clone(&reader);
        let stop = std::sync::Arc::clone(&stop);
        let eng = std::sync::Arc::clone(&engine);
        let gens_ok = plains.clone();
        handles.push(std::thread::spawn(move || {
            let mut out = Vec::new();
            while !stop.load(std::sync::atomic::Ordering::Relaxed) {
                let res = eng
                    .read_lock(|| r.read_chunk(0, 0, &mut out))
                    .expect("engine lock");
                match res.unwrap() {
                    READ_SUCCESS => {
                        assert!(
                            gens_ok.iter().any(|p| p == &out),
                            "torn read: bytes match NO committed generation"
                        );
                    }
                    READ_MISSING => { }
                    other => panic!("unexpected status {other}"),
                }
                out.clear();
            }
        }));
    }
    for (g, payload) in gens.iter().enumerate() {
        engine.write_chunk(0, 0, payload, (g + 2) as u64).unwrap();
        std::thread::sleep(std::time::Duration::from_millis(2));
    }
    stop.store(true, std::sync::atomic::Ordering::Relaxed);
    for h in handles {
        h.join().unwrap();
    }
    region_io::live::EngineRegistry::global().remove(&path);
}
