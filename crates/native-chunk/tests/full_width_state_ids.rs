//! Full-width state identity (goal: remove the u16 global-state-ID ceiling).
//!
//! Revelation registries exceed 0xFFFF (known live id: 76,916). Sections,
//! chunks, the registry, and the packet encoder must preserve exact ids —
//! store, read, mutate, section refresh, and wire encoding included. The
//! u16 that remains anywhere in the pipeline is a LOCAL palette index
//! (wire format), never a global state id.

use native_chunk::chunk::{NativeChunk, CHUNK_PRIMER_SIZE};
use native_chunk::registry::ChunkKey;
use native_chunk::section::NativeSection;

const HIGH_A: u32 = 76_916; // known live Revelation id
const HIGH_B: u32 = 108_517;
const LOW_STONE: u32 = 16;
const LOW_TORCH: u32 = 1;

#[test]
fn section_preserves_full_width_ids_store_read_mutate() {
    let mut sec = NativeSection::new(4);
    sec.set_block(0, 0, 0, HIGH_A);
    sec.set_block(15, 15, 15, HIGH_B);
    sec.set_block(1, 0, 0, LOW_STONE);
    assert_eq!(sec.get_block(0, 0, 0), HIGH_A, "high id must round-trip");
    assert_eq!(sec.get_block(15, 15, 15), HIGH_B);
    assert_eq!(sec.get_block(1, 0, 0), LOW_STONE);
    assert_eq!(sec.non_air_count, 3);

    // mutate high -> low -> air, watching counts
    assert!(sec.set_block(0, 0, 0, LOW_TORCH));
    assert_eq!(sec.get_block(0, 0, 0), LOW_TORCH);
    assert!(sec.set_block(0, 0, 0, 0));
    assert_eq!(sec.get_block(0, 0, 0), 0);
    assert_eq!(sec.non_air_count, 2);

    // the same id written again is a no-op (no double count)
    assert!(!sec.set_block(15, 15, 15, HIGH_B));
    assert_eq!(sec.non_air_count, 2);
}

#[test]
fn section_replace_states_and_slice_are_full_width() {
    let mut states = [0u32; 4096];
    states[0] = HIGH_A;
    states[4095] = HIGH_B;
    states[17] = LOW_STONE;
    let mut sec = NativeSection::new(2);
    sec.replace_states(&states, None, None);
    let read = sec.states_as_slice();
    assert_eq!(read[0], HIGH_A);
    assert_eq!(read[4095], HIGH_B);
    assert_eq!(read[17], LOW_STONE);
    assert_eq!(sec.non_air_count, 3);
}

#[test]
fn chunk_primer_carries_full_width_ids() {
    let mut primer = [0u32; CHUNK_PRIMER_SIZE];
    // primer index layout (x << 12) | (z << 8) | y
    primer[(0usize << 12) | (0 << 8) | 70] = HIGH_A;
    primer[(8usize << 12) | (8 << 8) | 70] = HIGH_B;
    let biomes = [0u8; 256];
    let mut chunk = NativeChunk::from_primer(0, 3, 7, &primer, &biomes, 1);
    assert_eq!(chunk.get_block_state(0, 70, 0), HIGH_A);
    assert_eq!(chunk.get_block_state(8, 70, 8), HIGH_B);

    // mutate through the authoritative path
    let res = chunk
        .set_block_state(0, 70, 0, LOW_TORCH)
        .unwrap_status_ok();
    assert_eq!(res, ());
    assert_eq!(chunk.get_block_state(0, 70, 0), LOW_TORCH);

    // to_primer round-trips high ids exactly (no narrowing anywhere)
    let mut back = [0u32; CHUNK_PRIMER_SIZE];
    chunk.to_primer(&mut back);
    assert_eq!(back[(8usize << 12) | (8 << 8) | 70], HIGH_B);
}

/// Small helper so the chunk test reads cleanly.
trait UnwrapStatusOk {
    fn unwrap_status_ok(&self) -> ();
}
impl UnwrapStatusOk for native_chunk::chunk::BlockMutationResult {
    fn unwrap_status_ok(&self) {
        assert_eq!(
            self.status,
            native_chunk::chunk::BlockMutationResult::STATUS_SUCCESS
        );
    }
}

#[test]
fn registry_state_round_trip_full_width() {
    let reg = native_chunk::registry::ChunkRegistry::new();
    let mut primer = [0u32; CHUNK_PRIMER_SIZE];
    primer[(4usize << 12) | (9 << 8) | 64] = HIGH_A;
    let chunk = NativeChunk::from_primer(0, 10, 11, &primer, &[0u8; 256], reg.next_generation_id());
    let key = ChunkKey::new(0, 10, 11);
    reg.insert(chunk);
    assert_eq!(reg.get_block_state(key, 4, 64, 9), Some(HIGH_A));

    // full-width authoritative mutation through the registry
    assert!(reg.set_block_state(key, 4, 64, 9, HIGH_B).is_some());
    assert_eq!(reg.get_block_state(key, 4, 64, 9), Some(HIGH_B));

    // section refresh path (the M4 pull) carries high ids too
    let mut states = [0u32; 4096];
    states[NativeSection::block_index(4, 0, 9)] = HIGH_A;
    let mask = reg.refresh_section(key, 4, &states, None, None);
    assert_eq!(mask, Some(0), "refresh clears the dirty bit");
    assert_eq!(reg.get_block_state(key, 4, 64, 9), Some(HIGH_A));
}

#[test]
fn wire_encoding_represents_high_ids_via_local_palette() {
    // A section whose ids exceed u16 must still encode: the wire format's
    // LOCAL palette (u16 indexes) is a different concept from the global
    // id (full-width VarInt palette entries on the wire).
    let mut sec = NativeSection::new(3);
    sec.set_block(2, 2, 2, HIGH_A);
    sec.set_block(3, 3, 3, HIGH_B);
    sec.set_block(4, 4, 4, LOW_STONE);
    let mut buf = [0u8; 65536];
    let mut off = 0;
    sec.encode_wire(&mut buf, &mut off, true).expect("encode");
    // bits_per_block must be a LOCAL palette mode (<= 8), never a width
    // rejection; global-raw packing only applies when explicitly chosen.
    assert!(off > 0);
    assert!(buf[0] <= 8, "local palette expected, got bits={}", buf[0]);
    // palette count VarInt: 4 entries (air + 3)
    assert_eq!(buf[1], 4);
    // decode the palette entries back: VarInts after the count — entry
    // values must be the exact full-width ids (0, then the three states)
    let mut p = 2;
    let mut decoded = Vec::new();
    for _ in 0..4 {
        let mut v: u64 = 0;
        let mut shift = 0;
        loop {
            let b = buf[p];
            p += 1;
            v |= ((b & 0x7F) as u64) << shift;
            shift += 7;
            if b & 0x80 == 0 {
                break;
            }
        }
        decoded.push(v as u32);
    }
    assert!(
        decoded.contains(&HIGH_A),
        "palette must carry {} verbatim, got {:?}",
        HIGH_A,
        decoded
    );
    assert!(decoded.contains(&HIGH_B));
    assert!(decoded.contains(&LOW_STONE));
}

#[test]
fn fill_and_recalculate_accept_full_width() {
    let mut sec = NativeSection::new(1);
    sec.fill(HIGH_A);
    assert_eq!(sec.non_air_count, 4096);
    assert_eq!(sec.get_block(5, 5, 5), HIGH_A);
    sec.recalculate_counts();
    assert_eq!(sec.non_air_count, 4096);
}
