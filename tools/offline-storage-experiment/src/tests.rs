use super::*;
use crate::sidecar::*;
use crate::snapshot::Snapshot;
use redb::{ReadableDatabase, StorageBackend};
use std::fs::{self, OpenOptions};
use std::io::{Cursor, Write};
use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, Ordering};

static NEXT: AtomicU64 = AtomicU64::new(0);
fn folder() -> PathBuf {
    let root = std::env::var_os("OFFLINE_STORAGE_TEST_DIR")
        .map(PathBuf::from)
        .unwrap_or_else(|| {
            PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../target/offline-storage-tests")
        });
    let nonce = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap()
        .as_nanos();
    let p = root.join(format!(
        "{}-{nonce}-{}",
        std::process::id(),
        NEXT.fetch_add(1, Ordering::Relaxed)
    ));
    fs::create_dir_all(&p).unwrap();
    p
}
fn data(epoch: u64, seed: u64) -> Vec<u8> {
    let count = 128u64;
    let mut v = Vec::new();
    v.extend(b"RCSTOR01");
    v.extend(1u32.to_le_bytes());
    v.extend(32u32.to_le_bytes());
    v.extend(count.to_le_bytes());
    v.extend(epoch.to_le_bytes());
    v.extend(seed.to_le_bytes());
    v.extend([0; 24]);
    for i in 0..count {
        let key = (i * 65 + 17) % count;
        let value = (key as i64) * 3 - seed as i64;
        v.extend(key.to_le_bytes());
        v.extend(value.to_le_bytes());
        v.extend(((key % 256) as u32).to_le_bytes());
        v.extend(((key >> 4) as u32 & 15).to_le_bytes());
        v.extend((1 + key % 7).to_le_bytes());
    }
    v
}
fn parsed(bytes: &[u8]) -> (Binding, Vec<Entry>) {
    let (h, e) = scan_bytes(bytes).unwrap();
    let e = finish_index(&h, e).unwrap();
    let source: [u8; 32] = Sha256::digest(bytes).into();
    (Binding::new(&h, source, &e), e)
}

#[test]
fn independent_golden_little_endian_and_index() {
    let d = data(7, 9);
    let (h, e) = scan_bytes(&d).unwrap();
    assert_eq!(
        h,
        Header {
            count: 128,
            epoch: 7,
            seed: 9
        }
    );
    assert_eq!(
        e[0],
        Entry {
            key: 17,
            offset: 64,
            value: 42,
            group: 17,
            flags: 1,
            version: 4
        }
    );
    let sorted = finish_index(&h, e).unwrap();
    assert_eq!(sorted[0].value, -9);
    assert_eq!(scan_reader(Cursor::new(&d), d.len()).unwrap().0, h);
}

#[test]
fn malformed_headers_bounds_and_record_domains_fail() {
    for position in [0, 8, 12, 16, 40] {
        let mut d = data(7, 9);
        d[position] ^= 255;
        assert!(scan_bytes(&d).is_err(), "header position {position}");
    }
    let mut zero_epoch = data(7, 9);
    zero_epoch[24..32].fill(0);
    assert!(scan_bytes(&zero_epoch).is_err());
    let d = data(7, 9);
    for end in [0, 7, 63, d.len() - 1] {
        assert!(scan_bytes(&d[..end]).is_err());
    }
    let mut extra = d.clone();
    extra.push(0);
    assert!(scan_bytes(&extra).is_err());
    for offset in [64 + 16, 64 + 20, 64 + 24] {
        let mut bad = d.clone();
        let width = if offset == 88 { 8 } else { 4 };
        bad[offset..offset + width].fill(if offset == 88 { 0 } else { 255 });
        assert!(scan_bytes(&bad).is_err());
    }
    let mut duplicate = d.clone();
    let first = duplicate[64..72].to_vec();
    duplicate[96..104].copy_from_slice(&first);
    let (h, e) = scan_bytes(&duplicate).unwrap();
    assert!(finish_index(&h, e).is_err());
}

#[test]
fn private_mapping_matches_buffered_and_hash() {
    let p = folder();
    let d = data(7, 9);
    fs::write(p.join("input"), &d).unwrap();
    let mut s = Snapshot::copy_from(&p.join("input"), &p.join("owned")).unwrap();
    assert_eq!(s.sha256().unwrap(), <[u8; 32]>::from(Sha256::digest(&d)));
    let buffered = s.scan_buffered().unwrap();
    let map = s.map(0, s.len()).unwrap();
    assert_eq!(map.bytes(), d);
    assert_eq!(scan_bytes(map.bytes()).unwrap(), buffered);
}

#[test]
fn writer_truncate_rename_denied_until_owner_release() {
    let p = folder();
    let d = data(7, 9);
    let owned = p.join("owned");
    fs::write(p.join("input"), &d).unwrap();
    let s = Snapshot::copy_from(&p.join("input"), &owned).unwrap();
    let map = s.map(0, s.len()).unwrap();
    for trunc in [false, true] {
        let e = OpenOptions::new()
            .write(true)
            .truncate(trunc)
            .open(&owned)
            .unwrap_err();
        assert_eq!(e.raw_os_error(), Some(32));
    }
    assert_eq!(
        fs::rename(&owned, p.join("renamed"))
            .unwrap_err()
            .raw_os_error(),
        Some(32)
    );
    assert_eq!(map.bytes(), d);
    drop(map);
    assert!(OpenOptions::new().write(true).open(&owned).is_err());
    drop(s);
    {
        let mut f = OpenOptions::new().write(true).open(&owned).unwrap();
        f.write_all(b"released").unwrap();
        f.set_len(8).unwrap();
    }
    fs::rename(&owned, p.join("renamed")).unwrap();
    assert_eq!(fs::read(p.join("renamed")).unwrap(), b"released");
}

#[test]
fn mapping_bounds_fail_then_valid_retry() {
    let p = folder();
    fs::write(p.join("input"), data(7, 9)).unwrap();
    let s = Snapshot::copy_from(&p.join("input"), &p.join("owned")).unwrap();
    for (offset, len) in [(0, 0), (u64::MAX, 1), (1, s.len()), (0, MAX_BYTES + 1)] {
        assert!(s.map(offset, len).is_err());
    }
    assert_eq!(s.map(3, 17).unwrap().bytes(), &data(7, 9)[3..20]);
}

#[test]
fn existing_destination_empty_and_oversized_source_rejected() {
    let p = folder();
    fs::write(p.join("input"), data(7, 9)).unwrap();
    fs::write(p.join("owned"), b"keep").unwrap();
    assert!(Snapshot::copy_from(&p.join("input"), &p.join("owned")).is_err());
    assert_eq!(fs::read(p.join("owned")).unwrap(), b"keep");
    fs::write(p.join("empty"), []).unwrap();
    assert!(Snapshot::copy_from(&p.join("empty"), &p.join("new")).is_err());
    let f = std::fs::File::create(p.join("large")).unwrap();
    f.set_len(MAX_BYTES as u64 + 1).unwrap();
    drop(f);
    assert!(Snapshot::copy_from(&p.join("large"), &p.join("new")).is_err());
}

#[test]
fn commit_abort_reopen_and_stable_read_snapshot() {
    let p = folder();
    let path = p.join("index.redb");
    let (b1, e1) = parsed(&data(7, 9));
    let (b2, e2) = parsed(&data(8, 10));
    let db = open(&path, true).unwrap();
    build(&db, &b1, &e1).unwrap();
    let old = db.begin_read().unwrap();
    let old_query = query_hash(&old, &b1).unwrap();
    {
        let tx = db.begin_write().unwrap();
        {
            let mut table = tx.open_table(INDEX).unwrap();
            table
                .insert(e2[0].key, e2[0].encode_value().as_slice())
                .unwrap();
        }
        tx.abort().unwrap();
    }
    assert_eq!(
        query_hash(&db.begin_read().unwrap(), &b1).unwrap(),
        old_query
    );
    build(&db, &b2, &e2).unwrap();
    assert_eq!(verify_index(&old, &b1).unwrap(), b1.index);
    assert_eq!(query_hash(&old, &b1).unwrap(), old_query);
    assert!(validate(&old, &b2).is_err());
    {
        let current = db.begin_read().unwrap();
        assert_eq!(verify_index(&current, &b2).unwrap(), b2.index);
        assert!(validate(&current, &b1).is_err());
    }
    drop(old);
    drop(db);
    let reopened = open(&path, false).unwrap();
    assert_eq!(
        verify_index(&reopened.begin_read().unwrap(), &b2).unwrap(),
        b2.index
    );
    assert!(fs::metadata(path).unwrap().len() <= MAX_DATABASE_BYTES);
}

#[test]
fn stale_source_schema_epoch_count_and_index_binding_rejected() {
    let p = folder();
    let (b, e) = parsed(&data(7, 9));
    let db = open(&p.join("index"), true).unwrap();
    build(&db, &b, &e).unwrap();
    let read = db.begin_read().unwrap();
    for field in 0..5 {
        let mut wrong = b.clone();
        match field {
            0 => wrong.source[0] ^= 1,
            1 => wrong.schema += 1,
            2 => wrong.epoch += 1,
            3 => wrong.count += 1,
            _ => wrong.index[0] ^= 1,
        }
        assert!(validate(&read, &wrong).is_err());
    }
    assert!(validate(&read, &parsed(&data(7, 10)).0).is_err());
}

#[test]
fn missing_metadata_and_modified_index_fail() {
    let p = folder();
    let (b, e) = parsed(&data(7, 9));
    let db = open(&p.join("index"), true).unwrap();
    build(&db, &b, &e).unwrap();
    let tx = db.begin_write().unwrap();
    {
        let mut table = tx.open_table(INDEX).unwrap();
        let mut wrong = e[0].clone();
        wrong.value += 1;
        table
            .insert(wrong.key, wrong.encode_value().as_slice())
            .unwrap();
    }
    tx.commit().unwrap();
    assert!(verify_index(&db.begin_read().unwrap(), &b).is_err());
    let tx = db.begin_write().unwrap();
    {
        tx.open_table(META).unwrap().remove(0).unwrap();
    }
    tx.commit().unwrap();
    assert!(validate(&db.begin_read().unwrap(), &b).is_err());
}

#[test]
fn backend_byte_cap_refuses_growth_and_crossing_write() {
    let p = folder();
    let backend = CappedBackend::open(&p.join("cap"), true, 4096).unwrap();
    backend.set_len(4096).unwrap();
    assert!(backend.set_len(4097).is_err());
    assert!(backend.write(4095, &[1, 2]).is_err());
    assert!(backend.write(u64::MAX, &[1]).is_err());
    assert_eq!(backend.len().unwrap(), 4096);
    backend.write(0, b"ok").unwrap();
    let mut out = [0; 2];
    backend.read(0, &mut out).unwrap();
    assert_eq!(&out, b"ok");
}

#[test]
fn database_construction_failure_respects_cap_then_fresh_retry() {
    let p = folder();
    let small = p.join("too-small");
    let backend = CappedBackend::open(&small, true, 1024).unwrap();
    assert!(redb::Database::builder()
        .create_with_backend(backend)
        .is_err());
    assert!(fs::metadata(small).unwrap().len() <= 1024);
    let (b, e) = parsed(&data(7, 9));
    let db = open(&p.join("valid"), true).unwrap();
    build(&db, &b, &e).unwrap();
    assert_eq!(
        verify_index(&db.begin_read().unwrap(), &b).unwrap(),
        b.index
    );
}

#[test]
fn forged_value_encoding_and_build_digest_rejected() {
    assert!(Entry::decode_value(0, &[0; 31]).is_err());
    let p = folder();
    let (mut b, e) = parsed(&data(7, 9));
    let db = open(&p.join("index"), true).unwrap();
    b.index[0] ^= 1;
    assert!(build(&db, &b, &e).is_err());
}
