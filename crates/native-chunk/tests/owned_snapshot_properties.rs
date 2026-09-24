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
            (index % self.varieties + 1) as u32
        }
    }
    fn transport(&self) -> Vec<u8> {
        let mut bytes = vec![0u8; 128];
        bytes[..8].copy_from_slice(b"RCSNAP01");
        bytes[9] = 1;
        bytes[10] = u8::from(self.full) | (u8::from(self.sky) << 1);
        bytes[11] = 1;
        bytes[12] = 16;
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
        prop_assert!((4..=8).contains(&bits) || bits == 16);
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
