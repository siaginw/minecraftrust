//! Bounded properties against an independent owned-input/wire reference model.
//! Concrete shrunk failures are saved by the shared dev-only harness.
use native_chunk::{OwnedPacketSnapshot, SnapshotRejection};
use proptest::prelude::*;
use proptest::test_runner::TestCaseResult;
#[path = "../../../tools/testing/rust_property_support.rs"]
mod property_support;

#[derive(Clone)]
struct Model {
    requested: u16,
    present: u16,
    empty: u16,
    full: bool,
    sky: bool,
    varieties: usize,
    state_bias: u32,
    global_bits: u8,
    light: u8,
    biome: u8,
    generation: u64,
    incarnation: u64,
}

impl Model {
    fn mask(&self) -> u16 {
        self.requested & self.present & if self.full { !self.empty } else { u16::MAX }
    }
    fn state(&self, y: usize, index: usize) -> u32 {
        if self.empty & (1 << y) != 0 {
            0
        } else {
            (index % self.varieties + 1) as u32 + self.state_bias
        }
    }
    fn transport(&self) -> Vec<u8> {
        let mut bytes = vec![0u8; 128];
        bytes[..8].copy_from_slice(b"RCSNAP01");
        bytes[9] = 1;
        bytes[10] = u8::from(self.full) | (u8::from(self.sky) << 1);
        bytes[11] = 1;
        bytes[12] = self.global_bits;
        bytes[13] = 1;
        bytes[28..36].copy_from_slice(&self.generation.to_be_bytes());
        bytes[36..38].copy_from_slice(&self.requested.to_be_bytes());
        bytes[38..40].copy_from_slice(&self.mask().to_be_bytes());
        for offset in [40, 48, 56] {
            bytes[offset..offset + 8].copy_from_slice(&1u64.to_be_bytes());
        }
        for offset in [80, 88] {
            bytes[offset..offset + 8].copy_from_slice(&self.incarnation.to_be_bytes());
        }
        bytes.extend_from_slice(&(self.mask().count_ones() as u16).to_be_bytes());
        for y in 0..16 {
            if self.mask() & (1 << y) == 0 {
                continue;
            }
            let count = if self.empty & (1 << y) == 0 {
                4096u16
            } else {
                0
            };
            bytes.extend_from_slice(&[y as u8, 0]);
            bytes.extend_from_slice(&count.to_be_bytes());
            for index in 0..4096 {
                bytes.extend_from_slice(&self.state(y, index).to_be_bytes());
            }
            for i in 0..2048 {
                bytes.push(self.light.wrapping_add(y as u8).wrapping_add(i as u8));
            }
            if self.sky {
                for i in 0..2048 {
                    bytes.push(self.light.wrapping_sub(y as u8).wrapping_sub(i as u8));
                }
            }
        }
        if self.full {
            for i in 0..256 {
                bytes.push(self.biome.wrapping_add(i as u8));
            }
        }
        bytes
    }
}

struct Wire<'a> {
    data: &'a [u8],
    cursor: usize,
}
impl<'a> Wire<'a> {
    fn take(&mut self, n: usize) -> &'a [u8] {
        assert!(n <= self.data.len() - self.cursor, "wire truncated");
        let result = &self.data[self.cursor..self.cursor + n];
        self.cursor += n;
        result
    }
    fn varint(&mut self) -> usize {
        let mut value = 0usize;
        for shift in (0..35).step_by(7) {
            let b = self.take(1)[0];
            value |= usize::from(b & 127) << shift;
            if b < 128 {
                return value;
            }
        }
        panic!("wire varint too long");
    }
}

// This reader never calls native palette, packing or section-selection helpers.
// It stops at the exact permitted tail, and only then compares discovered count.
fn verify_wire(bytes: &[u8], emitted: u16, model: &Model) -> TestCaseResult {
    let tail = if model.full { 256 } else { 0 };
    prop_assert!(bytes.len() >= tail);
    let section_end = bytes.len() - tail;
    let expected_ys: Vec<_> = (0..16).filter(|y| model.mask() & (1 << y) != 0).collect();
    let mut wire = Wire {
        data: bytes,
        cursor: 0,
    };
    let mut found = 0;
    while wire.cursor < section_end {
        prop_assert!(found < expected_ys.len(), "extra complete section");
        let y = expected_ys[found];
        let bits = wire.take(1)[0] as usize;
        prop_assert!((4..=8).contains(&bits) || bits == usize::from(model.global_bits));
        let length = wire.varint();
        prop_assert!(
            (bits <= 8 && (1..=1usize << bits).contains(&length)) || (bits > 8 && length == 0)
        );
        let palette: Vec<_> = (0..length).map(|_| wire.varint() as u32).collect();
        let word_count = wire.varint();
        prop_assert_eq!(word_count, 4096 * bits / 64);
        let words: Vec<_> = (0..word_count)
            .map(|_| u64::from_be_bytes(wire.take(8).try_into().unwrap()))
            .collect();
        for cell in 0..4096 {
            let bit = cell * bits;
            let mut value = words[bit / 64] >> (bit % 64);
            if bit % 64 + bits > 64 {
                value |= words[bit / 64 + 1] << (64 - bit % 64);
            }
            value &= (1 << bits) - 1;
            let state = if bits <= 8 {
                prop_assert!((value as usize) < palette.len());
                palette[value as usize]
            } else {
                value as u32
            };
            prop_assert_eq!(state, model.state(y, cell));
        }
        for (i, &byte) in wire.take(2048).iter().enumerate() {
            prop_assert_eq!(
                byte,
                model.light.wrapping_add(y as u8).wrapping_add(i as u8)
            );
        }
        if model.sky {
            for (i, &byte) in wire.take(2048).iter().enumerate() {
                prop_assert_eq!(
                    byte,
                    model.light.wrapping_sub(y as u8).wrapping_sub(i as u8)
                );
            }
        }
        found += 1;
    }
    prop_assert_eq!(wire.cursor, section_end);
    if model.full {
        for (i, &byte) in wire.take(256).iter().enumerate() {
            prop_assert_eq!(byte, model.biome.wrapping_add(i as u8));
        }
    }
    prop_assert_eq!(wire.cursor, bytes.len());
    prop_assert_eq!(found, emitted.count_ones() as usize);
    prop_assert_eq!(emitted, model.mask());
    Ok(())
}

fn model(v: &[u64]) -> Model {
    Model {
        requested: v[0] as u16,
        present: v[1] as u16,
        empty: v[2] as u16,
        full: v[3] != 0,
        sky: v[4] != 0,
        varieties: [1, 15, 16, 17, 31, 32, 127, 128, 255, 256, 257, 300][v[5] as usize % 12],
        state_bias: 0,
        global_bits: 16,
        light: v[6] as u8,
        biome: v[7] as u8,
        generation: v[8],
        incarnation: v[9],
    }
}

fn models() -> impl Strategy<Value = Vec<u64>> {
    (
        any::<u16>(),
        any::<u16>(),
        any::<u16>(),
        any::<bool>(),
        any::<bool>(),
        0u64..12,
        any::<u8>(),
        any::<u8>(),
        1u64..=i64::MAX as u64,
        1u64..=i64::MAX as u64,
    )
        .prop_map(|v| {
            vec![
                v.0 as u64, v.1 as u64, v.2 as u64, v.3 as u64, v.4 as u64, v.5, v.6 as u64,
                v.7 as u64, v.8, v.9,
            ]
        })
}

fn encode_model(model: &Model) -> TestCaseResult {
    let input = model.transport();
    let snapshot = OwnedPacketSnapshot::from_transport(&input)
        .map_err(|e| TestCaseError::fail(format!("{e:?}")))?;
    let mut output = vec![0xcc; 262144];
    let result = snapshot
        .encode(&mut output)
        .map_err(|e| TestCaseError::fail(format!("{e:?}")))?;
    verify_wire(&output[..result.bytes_written], result.emitted_mask, model)
}

#[test]
fn arbitrary_owned_presence_selection_palette_light_and_biomes() {
    property_support::run("owned_semantics", models(), |v| encode_model(&model(v)));
}

#[test]
fn owned_capacity_failure_retry_and_later_input_mutation() {
    property_support::run("owned_capacity_retry", models(), |v| {
        let model = model(v);
        let mut input = model.transport();
        let snapshot = OwnedPacketSnapshot::from_transport(&input).unwrap();
        input.fill(0xff);
        let mut output = vec![0; 262144];
        let result = snapshot.encode(&mut output).unwrap();
        if result.bytes_written > 0 {
            let mut short = vec![0; result.bytes_written - 1];
            prop_assert_eq!(
                snapshot.encode(&mut short),
                Err(SnapshotRejection::Capacity)
            );
        }
        // Legacy section capacity preflight reserves five bytes per palette ID.
        // Preserve that conservative contract for palettes up to 256 entries.
        let mut retry = vec![0; result.bytes_written + 5 * 256];
        prop_assert_eq!(snapshot.encode(&mut retry), Ok(result));
        prop_assert_eq!(
            &retry[..result.bytes_written],
            &output[..result.bytes_written]
        );
        verify_wire(&retry[..result.bytes_written], result.emitted_mask, &model)
    });
}

#[test]
fn incarnation_generation_eligibility_rejection_and_retry() {
    let strategy = (models(), 0u64..10).prop_map(|(mut v, mutation)| {
        v.push(mutation);
        v
    });
    property_support::run("owned_admission_retry", strategy, |v| {
        let model = model(v);
        let good = model.transport();
        let mut bad = good.clone();
        let expected = match v[10] {
            0 => {
                bad[28..36].fill(0);
                SnapshotRejection::ChunkReplaced
            }
            1 => {
                bad[28] |= 128;
                SnapshotRejection::ChunkReplaced
            }
            2 => {
                bad[88..96].copy_from_slice(&(model.incarnation ^ 1).to_be_bytes());
                SnapshotRejection::ChunkReplaced
            }
            3 => {
                bad[80..96].fill(0);
                SnapshotRejection::ChunkReplaced
            }
            4 => {
                bad[72..80].copy_from_slice(&1u64.to_be_bytes());
                SnapshotRejection::CaptureChanged
            }
            5 => {
                bad[64] = 128;
                bad[72] = 128;
                SnapshotRejection::CaptureChanged
            }
            6 => {
                bad[56..64].copy_from_slice(&2u64.to_be_bytes());
                SnapshotRejection::OffThread
            }
            7 => {
                bad[48..64].fill(0);
                SnapshotRejection::OffThread
            }
            8 => {
                bad[13] = 255;
                SnapshotRejection::UnknownWriter
            }
            _ => {
                bad[11] = 3;
                SnapshotRejection::UnsupportedStorage
            }
        };
        prop_assert_eq!(
            OwnedPacketSnapshot::from_transport(&bad).err(),
            Some(expected)
        );
        prop_assert!(OwnedPacketSnapshot::from_transport(&good).is_ok());
        Ok(())
    });
}

#[test]
fn arbitrary_missing_selected_section_never_returns_success() {
    let strategy = (models(), 0u64..16).prop_map(|(mut v, y)| {
        v.push(y);
        v
    });
    property_support::run("owned_missing_retry", strategy, |v| {
        let mut model = model(v);
        let y = v[10] as u16;
        model.present &= !(1 << y);
        let good = model.transport();
        let mut bad = good.clone();
        bad[36..38].copy_from_slice(&(model.requested | (1 << y)).to_be_bytes());
        bad[38..40].copy_from_slice(&(model.mask() | (1 << y)).to_be_bytes());
        prop_assert_eq!(
            OwnedPacketSnapshot::from_transport(&bad).err(),
            Some(SnapshotRejection::MissingSection)
        );
        model.present |= 1 << y;
        model.requested |= 1 << y;
        model.empty &= !(1 << y);
        encode_model(&model)
    });
}

#[test]
fn fill_clear_palette_and_eligibility_sequences() {
    let strategy = prop::collection::vec(0u64..65536, 1..9);
    property_support::run("owned_state_sequence", strategy, |operations| {
        let mut model = Model {
            requested: 0xffff,
            present: 0,
            empty: 0,
            full: true,
            sky: false,
            varieties: 1,
            state_bias: 0,
            global_bits: 16,
            light: 0,
            biome: 0,
            generation: 1,
            incarnation: 17,
        };
        for op in operations {
            let y = (op & 15) as u8;
            match (op >> 4) % 6 {
                0 => {
                    model.present |= 1 << y;
                    model.empty &= !(1 << y);
                }
                1 => {
                    model.present |= 1 << y;
                    model.empty |= 1 << y;
                }
                2 => {
                    model.present &= !(1 << y);
                }
                3 => {
                    model.varieties = [15, 16, 255, 256, 300][((op >> 7) % 5) as usize];
                }
                4 => {
                    model.light = *op as u8;
                    model.biome = (*op >> 8) as u8;
                }
                _ => {
                    model.incarnation += 1;
                    model.generation += 3;
                }
            }
            model.full = op & 0x1000 != 0;
            model.sky = op & 0x2000 != 0;
            encode_model(&model)?;
        }
        Ok(())
    });
}

#[test]
fn model_boundaries_cover_zero_max_identity_and_all_sections() {
    // Explicit deterministic fixtures protect mutation-sensitive boundaries,
    // independent of which inputs the random generator happens to choose.
    for present in [0, 1, 0x8000, 0xffff] {
        for varieties in [1, 15, 16, 255, 256, 300] {
            let model = Model {
                requested: 0xffff,
                present,
                empty: 0,
                full: true,
                sky: true,
                varieties,
                state_bias: 0,
                global_bits: 16,
                light: 255,
                biome: 255,
                generation: i64::MAX as u64,
                incarnation: i64::MAX as u64,
            };
            encode_model(&model).unwrap();
        }
    }
}

#[test]
fn maximum_epoch_and_overdeclared_section_count_are_not_ambiguous() {
    let model = Model {
        requested: 0x8001,
        present: 0x8001,
        empty: 0,
        full: true,
        sky: true,
        varieties: 17,
        state_bias: 0,
        global_bits: 16,
        light: 1,
        biome: 2,
        generation: 7,
        incarnation: 9,
    };
    let mut input = model.transport();
    for offset in [64, 72] {
        input[offset..offset + 8].copy_from_slice(&(i64::MAX as u64).to_be_bytes());
    }
    assert!(
        OwnedPacketSnapshot::from_transport(&input).is_ok(),
        "maximum nonnegative Java epoch is valid"
    );
    input[128..130].copy_from_slice(&3u16.to_be_bytes());
    assert_eq!(
        OwnedPacketSnapshot::from_transport(&input).err(),
        Some(SnapshotRejection::MaskMismatch)
    );
}

// These are transport-construction stages, not claims about live Forge writers.
// Each range contains incomplete prefixes within one copy/validation boundary.
fn progress_ranges(model: &Model) -> [Vec<std::ops::Range<usize>>; 6] {
    let mut ranges: [Vec<std::ops::Range<usize>>; 6] = Default::default();
    ranges[0].push(0..130); // Header, provenance and selected-section count.
    let mut offset = 130;
    for y in 0..16 {
        if model.mask() & (1 << y) == 0 {
            continue;
        }
        for (stage, size) in [(1, 4), (2, 4096 * 4), (3, 2048)] {
            ranges[stage].push(offset..offset + size);
            offset += size;
        }
        if model.sky {
            ranges[4].push(offset..offset + 2048);
            offset += 2048;
        }
    }
    if model.full {
        ranges[5].push(offset..offset + 256);
    }
    ranges
}

fn no_snapshot_or_encode_result(input: &[u8], reason: SnapshotRejection) -> TestCaseResult {
    let parsed = OwnedPacketSnapshot::from_transport(input);
    prop_assert!(
        parsed.as_ref().ok().is_none(),
        "rejection exposed an owned snapshot"
    );
    let mut output = [0xa5; 32];
    // Use the public consumer chain: only an actual validated snapshot can call
    // encode. A rejection has neither bytes_written nor an emitted section mask.
    let result = parsed.and_then(|snapshot| snapshot.encode(&mut output));
    prop_assert_eq!(result, Err(reason));
    prop_assert_eq!(output, [0xa5; 32], "rejected input reached encoding");
    Ok(())
}

#[test]
fn partial_transport_progress_never_publishes_and_complete_retry_succeeds() {
    let strategy = (models(), 0u64..7, any::<u64>()).prop_map(|(mut v, stage, position)| {
        v.extend([stage, position]);
        v
    });
    property_support::run("owned_partial_publication", strategy, |v| {
        let mut model = model(v);
        // Make every copy stage reachable while preserving arbitrary other bits,
        // palettes, lights, biomes and independent generation/incarnation values.
        model.requested |= 1;
        model.present |= 1;
        model.empty &= !1;
        model.full = true;
        model.sky = true;
        let good = model.transport();
        if v[10] == 6 {
            let mut trailing = good.clone();
            trailing.push(v[11] as u8);
            no_snapshot_or_encode_result(&trailing, SnapshotRejection::MalformedSnapshot)?;
        } else {
            let ranges = progress_ranges(&model);
            let candidates = &ranges[v[10] as usize];
            let selected = &candidates[(v[11] % candidates.len() as u64) as usize];
            let cut = selected.start + (v[11] % (selected.end - selected.start) as u64) as usize;
            no_snapshot_or_encode_result(&good[..cut], SnapshotRejection::MalformedSnapshot)?;
        }
        // The rejected temporary parse does not contaminate a complete retry.
        encode_model(&model)
    });
}

#[test]
fn late_wide_id_decisions_cannot_publish_partial_sections() {
    let strategy = (models(), 0u64..4096, any::<u32>(), any::<bool>()).prop_map(
        |(mut v, cell, high, global)| {
            v.extend([cell, u64::from(high), u64::from(global)]);
            v
        },
    );
    property_support::run("owned_late_width_publication", strategy, |v| {
        let mut model = model(v);
        model.requested |= 0x8000;
        model.present |= 0x8000;
        model.empty &= !0x8000;
        model.varieties = if v[12] == 0 { 1 } else { 300 };
        model.full = true;
        let mut bad = model.transport();
        let selected = model.mask().count_ones() as usize;
        let stride = 4 + 4096 * 4 + 2048 + if model.sky { 2048 } else { 0 };
        let cell = 130 + (selected - 1) * stride + 4 + v[10] as usize * 4;
        let too_wide = if v[12] == 0 {
            // Uniform/local storage makes the native u16 guard indispensable;
            // the independent global-palette guard cannot mask its removal.
            65_536 + v[11] as u32 % (u32::MAX - 65_536)
        } else {
            // All preceding section data fits nine bits. The final section has
            // a global palette and one value at the first forbidden ID.
            bad[12] = 9;
            512
        };
        bad[cell..cell + 4].copy_from_slice(&too_wide.to_be_bytes());
        no_snapshot_or_encode_result(&bad, SnapshotRejection::ExtendedId)?;
        encode_model(&model)
    });
}

#[test]
fn rejected_lifecycle_transition_cannot_publish_or_relabel_owned_history() {
    property_support::run("owned_lifecycle_publication", models(), |v| {
        let mut original = model(v);
        original.requested |= 1;
        original.present |= 1;
        original.empty &= !1;
        let mut old_input = original.transport();
        let historical = OwnedPacketSnapshot::from_transport(&old_input).unwrap();
        let mut before = vec![0; 262144];
        let old_result = historical.encode(&mut before).unwrap();
        let mut replacement = original.clone();
        let next_id = |id| if id == i64::MAX as u64 { 1 } else { id + 1 };
        replacement.generation = next_id(original.generation);
        replacement.incarnation = next_id(original.incarnation);
        replacement.light = original.light.wrapping_add(1);
        replacement.biome = original.biome.wrapping_add(1);
        let mut incomplete = replacement.transport();
        incomplete[88..96].copy_from_slice(&original.incarnation.to_be_bytes());
        no_snapshot_or_encode_result(&incomplete, SnapshotRejection::ChunkReplaced)?;
        old_input.fill(0xff);
        let mut after = vec![0; 262144];
        prop_assert_eq!(historical.encode(&mut after), Ok(old_result));
        prop_assert_eq!(
            &before[..old_result.bytes_written],
            &after[..old_result.bytes_written]
        );
        prop_assert_eq!(historical.metadata().generation, original.generation);
        prop_assert_eq!(
            historical.metadata().incarnation_start,
            original.incarnation
        );
        // Historical owned A remains A, not a token authorizing publication for
        // replacement B. Current live publication is intentionally outside Rust.
        let current = OwnedPacketSnapshot::from_transport(&replacement.transport()).unwrap();
        prop_assert_eq!(current.metadata().generation, replacement.generation);
        prop_assert_eq!(current.metadata().incarnation_end, replacement.incarnation);
        encode_model(&replacement)
    });
}

#[test]
fn every_copy_stage_requires_complete_transport_before_publication() {
    let model = Model {
        requested: 0x8021,
        present: 0x8021,
        empty: 0,
        full: true,
        sky: true,
        varieties: 1,
        state_bias: 0,
        global_bits: 16,
        light: 17,
        biome: 31,
        generation: 11,
        incarnation: 29,
    };
    let input = model.transport();
    for cut in 0..130 {
        no_snapshot_or_encode_result(&input[..cut], SnapshotRejection::MalformedSnapshot).unwrap();
    }
    for ranges in progress_ranges(&model).iter().skip(1) {
        for range in ranges {
            for cut in [
                range.start,
                range.start + 1,
                range.start + 2,
                range.start + 3,
                range.start + (range.end - range.start) / 2,
                range.end - 1,
            ] {
                no_snapshot_or_encode_result(&input[..cut], SnapshotRejection::MalformedSnapshot)
                    .unwrap();
            }
        }
    }
    let mut trailing = input;
    trailing.push(0);
    no_snapshot_or_encode_result(&trailing, SnapshotRejection::MalformedSnapshot).unwrap();
    encode_model(&model).unwrap();
}

#[test]
fn publication_width_boundaries_distinguish_local_ids_from_global_values() {
    let mut model = Model {
        requested: 1,
        present: 1,
        empty: 0,
        full: true,
        sky: false,
        varieties: 1,
        state_bias: 65_534,
        global_bits: 9,
        light: 0,
        biome: 0,
        generation: i64::MAX as u64,
        incarnation: i64::MAX as u64,
    };
    // A local palette holds explicit IDs, so its highest native u16 ID is valid
    // even when the snapshot's global width would not represent that value.
    encode_model(&model).unwrap();
    model.state_bias = 65_535;
    no_snapshot_or_encode_result(&model.transport(), SnapshotRejection::ExtendedId).unwrap();
    model.state_bias = 257;
    model.varieties = 255; // 255 nonair + air: still local, max ID 512 is valid.
    encode_model(&model).unwrap();
    model.state_bias = 256;
    model.varieties = 256; // 256 nonair + air: global, ID 512 cannot fit nine bits.
    no_snapshot_or_encode_result(&model.transport(), SnapshotRejection::ExtendedId).unwrap();
    model.state_bias = 0;
    encode_model(&model).unwrap();
    let mut invalid_lifecycle = model.transport();
    invalid_lifecycle[80..96].fill(0);
    no_snapshot_or_encode_result(&invalid_lifecycle, SnapshotRejection::ChunkReplaced).unwrap();
}
