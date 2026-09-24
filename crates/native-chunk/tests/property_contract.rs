use native_chunk::NativeChunk;
use proptest::prelude::*;
use proptest::test_runner::TestCaseResult;
#[path = "../../../tools/testing/rust_property_support.rs"]
mod property_support;

fn refresh(chunk: &mut NativeChunk, y: u8, filled: bool) {
    chunk.refresh_section(
        y,
        &[if filled { u16::from(y) + 1 } else { 0 }; 4096],
        Some(&[y; 2048]),
        Some(&[255 - y; 2048]),
    );
}

// Independent scanner for this model's deliberately uniform sections. Walks
// until bytes end, not popcount(mask), and verifies every palette index/light.
fn scan(bytes: &[u8], sky: bool, full: bool) -> Result<Vec<u8>, TestCaseError> {
    let mut end = bytes.len();
    if full {
        prop_assert!(end >= 256);
        end -= 256;
        prop_assert_eq!(&bytes[end..], &[42; 256]);
    }
    let section_len = 6 + 2048 + 2048 + if sky { 2048 } else { 0 };
    prop_assert_eq!(end % section_len, 0);
    let mut ys = Vec::new();
    for data in bytes[..end].chunks_exact(section_len) {
        prop_assert_eq!(&data[..3], &[4, 2, 0]);
        prop_assert!((1..=16).contains(&data[3]));
        let y = data[3] - 1;
        prop_assert_eq!(&data[4..6], &[128, 2]);
        prop_assert!(data[6..2054].iter().all(|b| *b == 0x11));
        prop_assert!(data[2054..4102].iter().all(|b| *b == y));
        if sky {
            prop_assert!(data[4102..].iter().all(|b| *b == 255 - y));
        }
        ys.push(y);
    }
    Ok(ys)
}

fn verify(
    chunk: &mut NativeChunk,
    selected: u16,
    present: u16,
    sky: bool,
    full: bool,
    mode: u64,
) -> TestCaseResult {
    chunk.primary_bit_mask = selected;
    let expected_len = selected.count_ones() as usize * (4102 + if sky { 2048 } else { 0 })
        + if full { 256 } else { 0 };
    // The existing encoder reserves up to five bytes per palette ID. Uniform
    // sections use one-byte IDs, so eight spare bytes suffice for its final
    // conservative section check. Exact wire capacity may legally reject.
    let capacity = match mode % 3 {
        0 => expected_len.saturating_sub(1),
        1 => expected_len,
        _ => expected_len + 8,
    };
    let mut out = vec![0xa5; capacity];
    let mut offset = 0;
    let result = chunk.encode_packet_payload(sky, full, &mut out, &mut offset);
    if selected & !present != 0 || capacity < expected_len {
        prop_assert!(result.is_err());
        prop_assert_eq!(offset, 0);
    } else if result.is_err() {
        prop_assert!(selected != 0 && !full && capacity < expected_len + 8);
        prop_assert_eq!(offset, 0);
    }
    if result.is_err() {
        // Repair missing sections, retry with sufficient capacity. Every encode
        // after an error must produce fresh, coupled successful metadata.
        for y in 0..16 {
            if selected & (1 << y) != 0 {
                refresh(chunk, y, true);
            }
        }
        out.resize(expected_len + 8, 0);
        offset = 0;
    }
    let result = if result.is_err() {
        chunk.encode_packet_payload(sky, full, &mut out, &mut offset)
    } else {
        result
    };
    let result = result.map_err(|e| TestCaseError::fail(e))?;
    prop_assert_eq!(result.bytes_written, expected_len);
    prop_assert_eq!(offset, expected_len);
    prop_assert_eq!(result.emitted_mask, selected);
    let ys = scan(&out[..result.bytes_written], sky, full)?;
    let expected: Vec<_> = (0..16u8).filter(|y| selected & (1 << y) != 0).collect();
    prop_assert_eq!(ys.len(), result.emitted_mask.count_ones() as usize);
    prop_assert_eq!(ys, expected);
    Ok(())
}

#[test]
fn arbitrary_sparse_presence_capacity_and_retry() {
    let strategy = (
        any::<u16>(),
        any::<u16>(),
        any::<bool>(),
        any::<bool>(),
        0u64..3,
    )
        .prop_map(|(selected, present, sky, full, mode)| {
            vec![
                selected as u64,
                present as u64,
                sky as u64,
                full as u64,
                mode,
            ]
        });
    property_support::run("mask_presence", strategy, |v| {
        prop_assert_eq!(v.len(), 5);
        let mut chunk = NativeChunk::new(0, 0, 0, 1);
        chunk.biomes.fill(42);
        for y in 0..16 {
            if v[1] & (1 << y) != 0 {
                refresh(&mut chunk, y, true);
            }
        }
        verify(
            &mut chunk,
            v[0] as u16,
            v[1] as u16,
            v[2] != 0,
            v[3] != 0,
            v[4],
        )
    });
}

#[test]
fn fill_clear_refresh_encode_sequences_match_presence_model() {
    // Each compact operation has independent fields: Y (0..15), mutation
    // (fill/clear/refresh/encode), sky/full flags and capacity boundary mode.
    let strategy = prop::collection::vec(0u64..768, 1..33);
    property_support::run("mutation_sequence", strategy, |ops| {
        let mut chunk = NativeChunk::new(0, 0, 0, 1);
        chunk.biomes.fill(42);
        let mut present = 0u16;
        for &op in ops {
            let y = (op % 16) as u8;
            match (op / 16) % 4 {
                0 | 2 => {
                    refresh(&mut chunk, y, true);
                    present |= 1 << y;
                }
                1 => {
                    refresh(&mut chunk, y, false);
                    present &= !(1 << y);
                }
                _ => {}
            }
            prop_assert_eq!(chunk.primary_bit_mask, present);
            verify(
                &mut chunk,
                present,
                present,
                op & 64 != 0,
                op & 128 != 0,
                op / 256,
            )?;
        }
        Ok(())
    });
}
