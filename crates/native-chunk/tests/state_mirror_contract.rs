//! §7 state-coherence regression for the mutation-seam mirror: a mirrored
//! "Java write" must be visible in the native cell IMMEDIATELY — no
//! refresh, no section pull — with exactly one mutation-version advance
//! per semantic write and the section dirty bit set.

use native_chunk::chunk::{NativeChunk, CHUNK_PRIMER_SIZE};
use native_chunk::registry::{ChunkKey, ChunkRegistry};

fn registered(reg: &ChunkRegistry, dim: i32, cx: i32, cz: i32) -> ChunkKey {
    let primer = [0u32; CHUNK_PRIMER_SIZE];
    let chunk =
        NativeChunk::from_primer(dim, cx, cz, &primer, &[0u8; 256], reg.next_generation_id());
    let key = ChunkKey::new(dim, cx, cz);
    reg.insert(chunk);
    key
}

#[test]
fn mirror_write_visible_without_refresh_then_remove() {
    let reg = ChunkRegistry::new();
    let key = registered(&reg, 0, 300, 300);
    let (x, y, z) = (4usize, 64usize, 9usize);

    // native starts AIR
    assert_eq!(reg.get_block_state(key, x, y, z), Some(0));

    // "Java writes torch" through the seam mirror — BEFORE any refresh
    let gen0 = reg.chunk_generation(key).unwrap();
    let dirty0 = reg.dirty_mask(key).unwrap_or(0);
    assert!(reg.mirror_block_state(key, x, y, z, 1).unwrap());
    assert_eq!(
        reg.get_block_state(key, x, y, z),
        Some(1),
        "mirror must be visible immediately"
    );
    let dirty1 = reg.dirty_mask(key).unwrap_or(0);
    assert_eq!(
        dirty1 & (1 << (y / 16)),
        1 << (y / 16),
        "section dirty bit must be set"
    );
    let gen1 = reg.chunk_generation(key).unwrap();
    assert_eq!(
        gen1, gen0,
        "chunk generation is identity (version is separate)"
    );

    // version advanced exactly once for one semantic write
    let arc = reg
        .get(&native_chunk::ChunkHandle {
            key,
            generation_id: gen0,
        })
        .unwrap();
    let v0 = arc.read().unwrap().mutation_generation();
    assert!(
        !reg.mirror_block_state(key, x, y, z, 1).unwrap(),
        "same state = no transition"
    );
    let v0b = arc.read().unwrap().mutation_generation();
    reg.mirror_block_state(key, x, y, z, 2).unwrap();
    let v1 = arc.read().unwrap().mutation_generation();
    assert_eq!(
        v1 - v0b,
        1,
        "exactly one version advance per semantic write"
    );
    assert!(v0b >= v0);

    // "Java removes the torch"
    assert!(reg.mirror_block_state(key, x, y, z, 0).unwrap());
    assert_eq!(reg.get_block_state(key, x, y, z), Some(0));
}

#[test]
fn mirror_covers_section_and_chunk_boundaries() {
    let reg = ChunkRegistry::new();
    let key = registered(&reg, 0, 310, 310);
    // section boundary cells (y=15/16 border inside one chunk)
    assert!(reg.mirror_block_state(key, 0, 15, 0, 76_916).unwrap());
    assert!(reg.mirror_block_state(key, 0, 16, 0, 108_517).unwrap());
    assert_eq!(reg.get_block_state(key, 0, 15, 0), Some(76_916));
    assert_eq!(reg.get_block_state(key, 0, 16, 0), Some(108_517));
    let mask = reg.dirty_mask(key).unwrap_or(0);
    assert_eq!(mask & (1 | 2), 1 | 2, "both sections' dirty bits set");

    // chunk boundary: a DIFFERENT chunk that was never registered
    let absent = ChunkKey::new(0, 311, 310);
    assert!(reg.chunk_generation(absent).is_none());
    // mirror on an unregistered chunk is a benign no-op (worldgen pre-load):
    // onLoad fullSync owns initial state there
    let probe = reg.mirror_block_state(absent, 0, 16, 0, 5);
    assert!(probe.is_none());
}

#[test]
fn mirror_allocates_absent_section_for_solid_state() {
    let reg = ChunkRegistry::new();
    let key = registered(&reg, 0, 320, 320);
    // solid into an absent (all-air, never allocated) section
    assert!(reg.mirror_block_state(key, 8, 200, 8, 76_916).unwrap());
    assert_eq!(reg.get_block_state(key, 8, 200, 8), Some(76_916));
    // air into an absent section is a no-op that does not allocate
    let gen = reg.chunk_generation(key).unwrap();
    let arc = reg
        .get(&native_chunk::ChunkHandle {
            key,
            generation_id: gen,
        })
        .unwrap();
    let v_before = arc.read().unwrap().mutation_generation();
    assert!(!reg.mirror_block_state(key, 8, 220, 8, 0).unwrap());
    let v_after = arc.read().unwrap().mutation_generation();
    assert_eq!(
        v_after, v_before,
        "air into absent section: no version churn"
    );
}
