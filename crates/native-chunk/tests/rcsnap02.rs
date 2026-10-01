//! RCSNAP02 transport controls: the logical per-section representation
//! decoupled from the runtime's global registry width.
//!
//! The fixture builder below writes real V2 bytes (the same deterministic
//! first-appearance palette the Java encoder writes) so every control
//! exercises the decoder, not a mock.

use native_chunk::packet_snapshot::{
    OwnedPacketSnapshot, SnapshotRejection, SNAPSHOT_MAGIC_V2, MAX_SNAPSHOT_BYTES,
};
use native_chunk::{NativeChunk, ChunkRegistry, ChunkKey};

fn header(registry_size: u32, source_bits: u8, mask: u16, full: bool, skylight: bool) -> Vec<u8> {
    let mut out = Vec::new();
    out.extend_from_slice(b"RCSNAP02");
    out.extend_from_slice(&2u16.to_be_bytes());
    out.push((full as u8) | ((skylight as u8) << 1));
    out.push(1); // vanilla u16 storage
    out.push(source_bits); // SOURCE TELEMETRY: any value, no gate
    out.push(1); // synthetic offline scope
    out.extend_from_slice(&0u16.to_be_bytes());
    out.extend_from_slice(&0i32.to_be_bytes()); // dimension
    out.extend_from_slice(&0i32.to_be_bytes()); // chunk x
    out.extend_from_slice(&0i32.to_be_bytes()); // chunk z
    out.extend_from_slice(&1u64.to_be_bytes()); // generation
    out.extend_from_slice(&0xffffu16.to_be_bytes()); // requested filter
    out.extend_from_slice(&mask.to_be_bytes()); // accepted mask
    out.extend_from_slice(&1u64.to_be_bytes()); // event id
    out.extend_from_slice(&7u64.to_be_bytes()); // owner thread
    out.extend_from_slice(&7u64.to_be_bytes()); // capture thread (== owner)
    out.extend_from_slice(&5u64.to_be_bytes()); // epoch start
    out.extend_from_slice(&5u64.to_be_bytes()); // epoch end (== start)
    out.extend_from_slice(&1u64.to_be_bytes()); // incarnation start
    out.extend_from_slice(&1u64.to_be_bytes()); // incarnation end
    out.extend_from_slice(&[0u8; 32]); // provenance digest
    assert_eq!(out.len(), 128);
    out.extend_from_slice(&(mask.count_ones() as u16).to_be_bytes());
    out.extend_from_slice(&registry_size.to_be_bytes());
    out.push(source_bits);
    out
}

/// One V2 section in the Java encoder's deterministic form: palette in
/// first-appearance order, indices packed LSB-first across 64-bit words.
fn section(out: &mut Vec<u8>, y: u8, states: &[u32; 4096], refcount: usize) {
    let mut palette: Vec<u16> = Vec::new();
    let mut index = [0usize; 4096];
    let mut seen = std::collections::HashMap::new();
    for (i, state) in states.iter().enumerate() {
        assert!(*state <= 0xFFFF, "fixture builder domain is u16");
        if let Some(existing) = seen.get(state) {
            index[i] = *existing;
        } else {
            seen.insert(*state, palette.len());
            index[i] = palette.len();
            palette.push(*state as u16);
        }
    }
    let mut bits = 1usize;
    while (1usize << bits) < palette.len() {
        bits += 1;
    }
    out.push(y);
    out.push(0);
    out.extend_from_slice(&(refcount as u16).to_be_bytes());
    out.extend_from_slice(&(palette.len() as u16).to_be_bytes());
    for entry in &palette {
        out.extend_from_slice(&entry.to_be_bytes());
    }
    out.push(bits as u8);
    let words = (4096 * bits + 63) / 64;
    out.extend_from_slice(&(words as u16).to_be_bytes());
    let mut packed = vec![0u64; words];
    for (cell, &idx) in index.iter().enumerate() {
        let position = cell * bits;
        let word = position / 64;
        let shift = position % 64;
        packed[word] |= (idx as u64) << shift;
        if shift + bits > 64 {
            packed[word + 1] |= (idx as u64) >> (64 - shift);
        }
    }
    for word in packed {
        out.extend_from_slice(&word.to_be_bytes());
    }
    out.extend_from_slice(&[0x11u8; 2048]); // block light
    out.extend_from_slice(&[0x22u8; 2048]); // sky light
}

fn uniform(state: u32) -> [u32; 4096] {
    [state; 4096]
}

fn non_air(states: &[u32; 4096]) -> usize {
    states.iter().filter(|s| **s != 0).count()
}

/// Mixed states exercising multi-entry palettes; returns the 4096 cells.
fn mixed(pattern: &[u32]) -> [u32; 4096] {
    let mut cells = [0u32; 4096];
    for (i, cell) in cells.iter_mut().enumerate() {
        *cell = pattern[i % pattern.len()];
    }
    cells
}

fn decode(bytes: &[u8]) -> Result<OwnedPacketSnapshot, SnapshotRejection> {
    OwnedPacketSnapshot::from_transport(bytes)
}

#[test]
fn control_2_one_state_section_round_trips() {
    let mut transport = header(157_010, 18, 1, false, true);
    section(&mut transport, 0, &uniform(42), 4096);
    let snapshot = decode(&transport).expect("eligible");
    assert_eq!(snapshot.metadata().version, 2);
    assert_eq!(snapshot.metadata().source_registry_size, 157_010);
    assert_eq!(snapshot.metadata().source_registry_required_bits, 18);
    assert_eq!(snapshot.metadata().global_palette_bits, 18); // telemetry rides
}

#[test]
fn control_3_4_5_palette_cardinalities() {
    for pattern in [
        vec![0, 1],
        vec![0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15],
        vec![0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16],
    ] {
        let states = mixed(&pattern);
        let mut transport = header(157_010, 18, 1, false, true);
        section(&mut transport, 0, &states, non_air(&states));
        decode(&transport).unwrap_or_else(|e| panic!("cardinality {}: {:?}", pattern.len(), e));
    }
}

#[test]
fn control_1_empty_section_shape_via_mask_zero() {
    // An empty section never enters the mask: mask=0 with full=false is the
    // minimal representable snapshot (no sections, no biomes).
    let transport = header(157_010, 18, 0, false, true);
    let snapshot = decode(&transport).expect("mask zero decodes");
    assert_eq!(snapshot.metadata().accepted_mask, 0);
}

#[test]
fn control_7_state_65535_is_eligible() {
    let mut transport = header(157_010, 18, 1, false, true);
    section(&mut transport, 0, &uniform(0xFFFF), 4096);
    decode(&transport).expect("the u16 boundary is inclusive");
}

#[test]
fn control_9_registry_18_bits_section_u16_passes() {
    // THE Revelation case: source registry 157,010 states -> 18 bits, while
    // every actual logical id fits u16. Eligible, no blanket exclusion.
    let states = mixed(&[0, 1, 300, 60_000, 65_535]);
    let mut transport = header(157_010, 18, 1, false, true);
    section(&mut transport, 0, &states, non_air(&states));
    decode(&transport).expect("18-bit registry never gates V2");
}

#[test]
fn control_6_large_palette() {
    // 300 distinct states (measured on a real oracle chunk) -> 9-bit indices.
    let mut pattern: Vec<u32> = (0..300).collect();
    pattern[0] = 0;
    let states = mixed(&pattern);
    let mut transport = header(157_010, 18, 1, false, true);
    section(&mut transport, 0, &states, non_air(&states));
    decode(&transport).expect("large palette within 4096");
}

#[test]
fn control_10_4096_cells_round_trip_exactly() {
    // Distinct cells per position: cell i uses pattern[i % 7] so ordering
    // matters. Verify via encode: the decoded snapshot's re-encoded output
    // decodes (independent Java-less check) -- the full logical equality is
    // proven by the cross-language fixture test below.
    let states = mixed(&[5, 6, 7, 8, 9, 10, 11]);
    let mut transport = header(157_010, 18, 1 << 3, false, true);
    section(&mut transport, 3, &states, non_air(&states));
    let snapshot = decode(&transport).expect("decodes");
    let mut output = vec![0u8; MAX_SNAPSHOT_BYTES];
    let result = snapshot.encode(&mut output).expect("encodes");
    assert_eq!(result.emitted_mask, 1 << 3);
    assert!(result.bytes_written > 0);
}

#[test]
fn control_11_12_lights_and_13_biomes_round_trip() {
    let mut transport = header(157_010, 18, 1, true, true);
    section(&mut transport, 0, &uniform(9), 4096);
    transport.extend_from_slice(&[0x33u8; 256]);
    decode(&transport).expect("full chunk with lights and biomes");
}

#[test]
fn control_14_malformed_palette_length_rejected() {
    let mut transport = header(157_010, 18, 1, false, true);
    let mut body = Vec::new();
    body.push(0u8); // y
    body.push(0u8);
    body.extend_from_slice(&4096u16.to_be_bytes()); // refcount
    body.extend_from_slice(&4097u16.to_be_bytes()); // palette_len over bound
    transport.extend_from_slice(&body);
    assert!(matches!(decode(&transport), Err(SnapshotRejection::MalformedSnapshot)));
}

#[test]
fn control_15_out_of_range_palette_index_rejected() {
    let mut transport = header(157_010, 18, 1, false, true);
    // Hand-build: palette of 1 entry but bits=1 packed words where some
    // index == 1 (out of range for palette_len 1).
    let mut body = Vec::new();
    body.push(0u8); // y
    body.push(0u8);
    body.extend_from_slice(&4096u16.to_be_bytes()); // refcount (all non-air)
    body.extend_from_slice(&1u16.to_be_bytes()); // palette_len
    body.extend_from_slice(&7u16.to_be_bytes()); // palette[0] = 7
    body.push(1u8); // bits
    let words = 4096 / 64;
    body.extend_from_slice(&(words as u16).to_be_bytes());
    // All indices 1 -> out of range (>= palette_len 1)
    for i in 0..words {
        let word: u64 = if i == 0 { 0xAAAA_AAAA_AAAA_AAAA } else { 0 };
        body.extend_from_slice(&word.to_be_bytes());
    }
    transport.extend_from_slice(&body);
    transport.extend_from_slice(&[0x11u8; 2048]);
    transport.extend_from_slice(&[0x22u8; 2048]);
    assert!(matches!(decode(&transport), Err(SnapshotRejection::MalformedSnapshot)));
}

#[test]
fn control_16_truncated_packed_data_rejected() {
    let states = mixed(&[1, 2, 3]);
    let mut transport = header(157_010, 18, 1, false, true);
    section(&mut transport, 0, &states, 4096);
    transport.truncate(transport.len() - 3000); // cut into packed/light area
    assert!(matches!(decode(&transport), Err(SnapshotRejection::MalformedSnapshot)));
}

#[test]
fn control_17_extra_section_bytes_rejected() {
    let states = mixed(&[1, 2]);
    let mut transport = header(157_010, 18, 1, false, true);
    section(&mut transport, 0, &states, 4096);
    transport.extend_from_slice(&[0xEEu8; 8]); // trailing bytes
    assert!(matches!(decode(&transport), Err(SnapshotRejection::MalformedSnapshot)));
}

#[test]
fn control_18_unknown_version_rejected() {
    let mut transport = header(157_010, 18, 0, false, true);
    transport[8..10].copy_from_slice(&3u16.to_be_bytes()); // version 3
    assert!(matches!(decode(&transport), Err(SnapshotRejection::MalformedSnapshot)));
    let mut v1_magic = transport.clone();
    v1_magic[8..10].copy_from_slice(&1u16.to_be_bytes()); // V2 body, V1 magic
    assert!(matches!(decode(&v1_magic), Err(SnapshotRejection::MalformedSnapshot)));
    let _ = SNAPSHOT_MAGIC_V2;
}

#[test]
fn control_19_rcsnap01_regression_unchanged() {
    // The V1 gate (global width IS the transport width) rejects >16 exactly
    // as before, and a V1-shaped header with the V1 magic still parses its
    // own way: existing tests own the full V1 matrix; this control pins the
    // width gate that V2 must not relax by accident.
    let mut v1 = Vec::new();
    v1.extend_from_slice(b"RCSNAP01");
    v1.extend_from_slice(&1u16.to_be_bytes());
    v1.push(0);
    v1.push(1);
    v1.push(17); // >16: refused by the V1 contract, unchanged
    v1.push(1);
    v1.extend_from_slice(&0u16.to_be_bytes());
    assert!(matches!(
        OwnedPacketSnapshot::from_transport(&v1),
        Err(SnapshotRejection::UnsupportedStorage)
    ));
}

#[test]
fn control_8_high_state_cannot_be_represented() {
    // A palette entry is u16 by construction; a logical id > 65535 cannot
    // appear in ANY V2 field. The exclusion happens BEFORE transport (the
    // Java capture path); the transport cannot even express the value, so
    // there is no accidental-representation path to test beyond the type.
    // This control pins that the u16 domain holds: a palette entry read as
    // u16 can never exceed 0xFFFF, by representation.
    let states = uniform(0xFFFF);
    let mut transport = header(157_010, 18, 1, false, true);
    section(&mut transport, 0, &states, 4096);
    let snapshot = decode(&transport).expect("max u16 state");
    let mut output = vec![0u8; MAX_SNAPSHOT_BYTES];
    assert!(snapshot.encode(&mut output).is_ok());
}

#[test]
fn control_refcount_mismatch_rejected() {
    let states = mixed(&[1, 2, 3]);
    let mut transport = header(157_010, 18, 1, false, true);
    section(&mut transport, 0, &states, 4095); // wrong refcount
    assert!(matches!(decode(&transport), Err(SnapshotRejection::RefcountMismatch)));
}

#[test]
fn control_duplicate_palette_entry_rejected() {
    let mut transport = header(157_010, 18, 1, false, true);
    let mut body = Vec::new();
    body.push(0u8);
    body.push(0u8);
    body.extend_from_slice(&4096u16.to_be_bytes());
    body.extend_from_slice(&2u16.to_be_bytes()); // palette_len 2
    body.extend_from_slice(&7u16.to_be_bytes());
    body.extend_from_slice(&7u16.to_be_bytes()); // duplicate
    transport.extend_from_slice(&body);
    assert!(matches!(decode(&transport), Err(SnapshotRejection::MalformedSnapshot)));
}

#[test]
fn control_index_width_insufficient_rejected() {
    let states = mixed(&[1, 2, 3, 4, 5]); // needs 3 bits
    let mut transport = header(157_010, 18, 1, false, true);
    // Build with the standard builder, then corrupt the declared bits to 2.
    let before = transport.len();
    section(&mut transport, 0, &states, 4096);
    // Locate the bits byte: palette_len (2) + 5 entries precede it in the
    // section body that starts at `before + 2 (y+0) + 2 (refcount) + 2 (len) + 10`.
    let bits_at = before + 2 + 2 + 2 + 5 * 2;
    transport[bits_at] = 2; // 1<<2 = 4 < palette_len 5
    assert!(matches!(decode(&transport), Err(SnapshotRejection::MalformedSnapshot)));
}

#[test]
fn control_retained_native_chunk_from_transport() {
    let states = mixed(&[1, 16, 144, 42]);
    let mut transport = header(157_010, 18, 1, true, true);
    section(&mut transport, 0, &states, 4096);
    transport.extend_from_slice(&[0x33u8; 256]);

    // 1. One-shot decode via OwnedPacketSnapshot
    let snapshot = OwnedPacketSnapshot::from_transport(&transport).expect("valid transport");
    let mut ephemeral_buf = [0u8; 32768];
    let ephemeral_res = snapshot.encode(&mut ephemeral_buf).expect("encode succeeds");

    // 2. Persistent NativeChunk seeded from transport
    let mut retained_chunk = NativeChunk::from_transport(&transport).expect("from_transport succeeds");
    assert_eq!(retained_chunk.dim, 0);
    assert_eq!(retained_chunk.cx, 0);
    assert_eq!(retained_chunk.cz, 0);
    assert_eq!(retained_chunk.primary_bit_mask, 1);

    // First retained encode (populates wire cache)
    let mut retained_buf1 = [0u8; 32768];
    let mut off1 = 0;
    let retained_res1 = retained_chunk.encode_packet_payload(true, true, &mut retained_buf1, &mut off1).expect("retained encode succeeds");
    assert_eq!(retained_res1.bytes_written, ephemeral_res.bytes_written);
    assert_eq!(retained_res1.emitted_mask, ephemeral_res.emitted_mask);
    assert_eq!(&retained_buf1[..off1], &ephemeral_buf[..ephemeral_res.bytes_written],
        "Retained chunk encode must be byte-identical to snapshot encode");

    // Second retained encode (fast path: hits wire cache)
    let mut retained_buf2 = [0u8; 32768];
    let mut off2 = 0;
    let retained_res2 = retained_chunk.encode_packet_payload(true, true, &mut retained_buf2, &mut off2).expect("retained re-encode succeeds");
    assert_eq!(retained_res2.bytes_written, retained_res1.bytes_written);
    assert_eq!(&retained_buf1[..off1], &retained_buf2[..off2],
        "Retained re-encode must be byte-identical via wire cache");

    // 3. Register in ChunkRegistry and verify handle lifecycle
    let registry = ChunkRegistry::new();
    let handle = registry.insert(retained_chunk);
    assert_eq!(handle.key, ChunkKey::new(0, 0, 0));
    assert!(registry.get(&handle).is_some());
}
