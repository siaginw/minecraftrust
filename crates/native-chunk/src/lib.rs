pub mod section;
pub mod chunk;
pub mod registry;

pub use section::{NativeSection, SectionFlags};
pub use chunk::{NativeChunk, ChunkLifecycle, CHUNK_PRIMER_SIZE, BIOME_ARRAY_SIZE};
pub use registry::{ChunkRegistry, ChunkHandle, ChunkKey};

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_section_index_mapping() {
        for y in 0..16 {
            for z in 0..16 {
                for x in 0..16 {
                    let idx = NativeSection::xyz_to_index(x, y, z);
                    let (rx, ry, rz) = NativeSection::index_to_xyz(idx);
                    assert_eq!((x, y, z), (rx, ry, rz));
                }
            }
        }
    }

    #[test]
    fn test_section_blocks_and_wire() {
        let mut sec = NativeSection::new(3);
        assert_eq!(sec.y_index, 3);
        assert_eq!(sec.flags & SectionFlags::NON_AIR, 0);

        // Fill with alternating block states
        for y in 0..16 {
            for z in 0..16 {
                for x in 0..16 {
                    let state = if (x + y + z) % 2 == 0 { 1u16 } else { 3u16 };
                    sec.set_block(x, y, z, state);
                }
            }
        }

        assert_eq!(sec.get_block(0, 0, 0), 1);
        assert_eq!(sec.get_block(1, 0, 0), 3);
        assert_eq!(sec.non_air_count, 4096);

        // Encode wire - need mutable reference for cache
        let mut buffer = [0u8; 16384];
        let mut offset = 0;
        let res = sec.encode_wire(&mut buffer, &mut offset, true);
        assert!(res.is_ok());
        // Wire size: bits_per_block (1) + palette_len (1) + palette (2 varints) + data_len (2 varints) + 2048 data + 2048 block_light + 2048 sky_light
        assert!(offset > 6144);
    }

    #[test]
    fn test_chunk_primer_bidirectional_roundtrip() {
        let mut primer = [0u16; CHUNK_PRIMER_SIZE];
        let mut biomes = [0u8; BIOME_ARRAY_SIZE];

        for i in 0..BIOME_ARRAY_SIZE {
            biomes[i] = (i % 24) as u8; // Plains, Desert, etc.
        }

        // Place bedrock at y=0, stone at y=1..63, dirt at y=64..67, grass at y=68
        for x in 0..16 {
            for z in 0..16 {
                let col = (x << 12) | (z << 8);
                primer[col | 0] = 7; // bedrock
                for y in 1..=63 {
                    primer[col | y] = 1; // stone
                }
                for y in 64..=67 {
                    primer[col | y] = 3; // dirt
                }
                primer[col | 68] = 2; // grass
            }
        }

        let chunk = NativeChunk::from_primer(0, 10, -5, &primer, &biomes, 1001);
        assert_eq!(chunk.cx, 10);
        assert_eq!(chunk.cz, -5);
        assert_eq!(chunk.generation_id, 1001);

        // Active sections should be sections 0, 1, 2, 3, 4 (covering y=0..79)
        // Mask: bits 0, 1, 2, 3, 4 -> 0b0001_1111 = 31
        assert_eq!(chunk.primary_bit_mask, 0x1F);

        // Section 0 should have 4096 blocks (y=0..15)
        assert_eq!(chunk.sections[0].as_ref().unwrap().non_air_count, 4096);
        // Section 4 should have 16 * 16 * 5 = 1280 blocks (y=64..68)
        assert_eq!(chunk.sections[4].as_ref().unwrap().non_air_count, 1280);

        // Roundtrip back to primer
        let mut reconstructed = [0u16; CHUNK_PRIMER_SIZE];
        chunk.to_primer(&mut reconstructed);

        // Verify 100% bit-exact match
        assert_eq!(primer, reconstructed);
    }

    #[test]
    fn test_zero_copy_consumers() {
        let mut primer = [0u16; CHUNK_PRIMER_SIZE];
        let biomes = [4u8; BIOME_ARRAY_SIZE];

        for x in 0..16 {
            for z in 0..16 {
                primer[(x << 12) | (z << 8) | 10] = 1; // 1 layer of stone in sec 0
            }
        }

        let mut chunk = NativeChunk::from_primer(0, 0, 0, &primer, &biomes, 42);

        // Consumer 1: Packet payload
        let mut packet_buf = [0u8; 32768];
        let mut pkt_offset = 0;
        let pkt_res = chunk.encode_packet_payload(true, true, &mut packet_buf, &mut pkt_offset);
        assert!(pkt_res.is_ok());
        assert!(pkt_offset > 0);

        // Consumer 2A: Occupancy summary
        let (mask, total_blocks) = chunk.occupancy_summary();
        assert_eq!(mask, 1);
        assert_eq!(total_blocks, 256);

        // Consumer 2B: Persistence staging
        let mut stage_buf = [0u8; 32768];
        let mut stage_offset = 0;
        let stg_res = chunk.stage_persistence(&mut stage_buf, &mut stage_offset);
        assert!(stg_res.is_ok());
        assert_eq!(stage_buf[0], 1); // 1 section
        assert_eq!(stage_buf[1], 0); // Y=0
    }

    #[test]
    fn test_versioned_snapshot_model() {
        let mut primer = [0u16; CHUNK_PRIMER_SIZE];
        let biomes = [0u8; BIOME_ARRAY_SIZE];
        for x in 0..16 {
            for z in 0..16 {
                primer[(x << 12) | (z << 8) | 5] = 1;
            }
        }

        let mut chunk = NativeChunk::from_primer(0, 1, 2, &primer, &biomes, 7);

        // from_primer initializes mutation_generation=1, snapshot_generation=0
        assert_eq!(chunk.mutation_generation(), 1);
        assert_eq!(chunk.snapshot_generation(), 0);
        assert_eq!(chunk.lifecycle, ChunkLifecycle::ActiveNative);

        // Consumer takes a snapshot and reads — consistent
        let token = chunk.begin_snapshot();
        assert_eq!(token, 1);
        assert!(chunk.end_snapshot(token));

        // Mutation after snapshot: old token now stale, lifecycle Dirty
        chunk.mark_mutation();
        assert_eq!(chunk.mutation_generation(), 2);
        assert_eq!(chunk.lifecycle, ChunkLifecycle::Dirty);
        assert!(!chunk.end_snapshot(token), "old snapshot token must be stale");

        // New snapshot sees the new generation
        let token2 = chunk.begin_snapshot();
        assert_eq!(token2, 2);
        assert!(chunk.end_snapshot(token2));

        // All three consumers validate consistency on the happy path
        let mut pkt = [0u8; 32768];
        let mut off = 0;
        assert!(chunk.encode_packet_payload(true, true, &mut pkt, &mut off).is_ok());
        assert!(chunk.stage_persistence(&mut pkt, &mut off).is_ok());
        let _ = chunk.occupancy_summary();

        // mark_mutation is idempotent-safe on non-ActiveNative lifecycles
        chunk.set_lifecycle(ChunkLifecycle::Invalidated);
        chunk.mark_mutation();
        assert_eq!(chunk.lifecycle, ChunkLifecycle::Invalidated);
        assert_eq!(chunk.mutation_generation(), 3);
    }

    #[test]
    fn test_section_refresh_model() {
        let mut primer = [0u16; CHUNK_PRIMER_SIZE];
        let biomes = [0u8; BIOME_ARRAY_SIZE];
        for x in 0..16 {
            for z in 0..16 {
                primer[(x << 12) | (z << 8) | 5] = 1;
            }
        }
        let mut chunk = NativeChunk::from_primer(0, 0, 0, &primer, &biomes, 9);
        assert_eq!(chunk.dirty_mask(), 0);
        assert_eq!(chunk.lifecycle, ChunkLifecycle::ActiveNative);
        assert_eq!(chunk.primary_bit_mask, 1); // only section 0

        // Population-style burst: 200 mutations across 2 sections collapse into a mask
        for _ in 0..100 {
            chunk.mark_section_mutation(0);
            chunk.mark_section_mutation(1);
        }
        assert_eq!(chunk.dirty_mask(), 0b11);
        assert_eq!(chunk.lifecycle, ChunkLifecycle::Dirty);

        // Refresh section 1: new data arrives from Java (u16[4096] layer-major)
        let mut new_states = [0u16; 4096];
        for i in 0..2048 { new_states[i] = 3; }
        let bl = [7u8; 2048];
        let sl = [15u8; 2048];
        chunk.refresh_section(1, &new_states, Some(&bl), Some(&sl));
        assert_eq!(chunk.dirty_mask(), 0b01, "section 1 bit cleared");
        assert_eq!(chunk.lifecycle, ChunkLifecycle::Dirty, "section 0 still dirty");
        assert!(chunk.sections[1].is_some());
        assert_eq!(chunk.sections[1].as_ref().unwrap().non_air_count, 2048);
        assert_eq!(chunk.sections[1].as_ref().unwrap().block_light[0], 7);
        assert_eq!(chunk.primary_bit_mask & (1 << 1), 1 << 1, "mask gained section 1");

        // Refresh section 0 with all-air: section deactivates in mask
        let air = [0u16; 4096];
        chunk.refresh_section(0, &air, None, None);
        assert_eq!(chunk.dirty_mask(), 0);
        assert!(chunk.sections[0].is_none(), "all-air section released");
        assert_eq!(chunk.primary_bit_mask, 1 << 1);
        assert_eq!(chunk.lifecycle, ChunkLifecycle::ActiveNative, "fully refreshed");

        // Refreshed section encodes cleanly (palette rebuilt after replace)
        let mut pkt = [0u8; 32768];
        let mut off = 0;
        assert!(chunk.encode_packet_payload(true, true, &mut pkt, &mut off).is_ok());
        assert!(off > 0);
    }

    #[test]
    fn test_dimension_scoped_keys_no_aliasing() {
        let registry = ChunkRegistry::new();
        let mut primer = [0u16; CHUNK_PRIMER_SIZE];
        let biomes = [0u8; BIOME_ARRAY_SIZE];
        for x in 0..16 {
            for z in 0..16 {
                primer[(x << 12) | (z << 8) | 3] = 1;
            }
        }

        // Same (cx, cz) in two dimensions must be two independent chunks
        let a = registry.insert(NativeChunk::from_primer(0, 4, 4, &primer, &biomes, 1));
        let b = registry.insert(NativeChunk::from_primer(-1, 4, 4, &primer, &biomes, 2));
        assert_ne!(a.key, b.key, "dim must be part of the key");
        assert_eq!(registry.count(), 2);

        // Unloading dim -1 must not touch dim 0
        assert!(registry.remove(b.key).is_some());
        assert!(registry.get(&a).is_some(), "dim 0 chunk must survive dim -1 unload");
        assert_eq!(registry.count(), 1);

        // Generation handles stay per-dimension-correct
        let a2 = registry.get(&a).unwrap();
        assert_eq!(a2.read().unwrap().dim, 0);
    }

    #[test]
    fn test_registry_lifecycle_and_invalidation() {
        let registry = ChunkRegistry::new();
        let gen1 = registry.next_generation_id();

        let primer = [0u16; CHUNK_PRIMER_SIZE];
        let biomes = [0u8; BIOME_ARRAY_SIZE];
        let chunk = NativeChunk::from_primer(0, 3, 7, &primer, &biomes, gen1);

        let handle = registry.insert(chunk);
        assert_eq!(handle.key.cx, 3);
        assert_eq!(handle.key.cz, 7);
        assert_eq!(registry.count(), 1);

        // Retrieve valid chunk
        let retrieved = registry.get(&handle);
        assert!(retrieved.is_some());

        // Invalidate chunk
        assert!(registry.invalidate(handle.key));
        // Stale query rejected due to Invalidated lifecycle
        assert!(registry.get(&handle).is_none());

        // Unload chunk
        let removed = registry.remove(handle.key);
        assert!(removed.is_some());
        assert_eq!(registry.count(), 0);
    }

    #[test]
    fn test_refresh_then_mutation_then_refresh_cycle() {
        let mut primer = [0u16; CHUNK_PRIMER_SIZE];
        let biomes = [0u8; BIOME_ARRAY_SIZE];
        for x in 0..16 {
            for z in 0..16 {
                primer[(x << 12) | (z << 8) | 4] = 1;
            }
        }
        let mut chunk = NativeChunk::from_primer(0, 8, 8, &primer, &biomes, 5);
        let gen0 = chunk.mutation_generation();

        // Mutation -> refresh -> ANOTHER mutation -> refresh again:
        // generations keep rising, dirty bits stay coherent, encode stays valid.
        chunk.mark_section_mutation(2);
        let s1 = [3u16; 4096];
        chunk.refresh_section(2, &s1, None, None);
        assert_eq!(chunk.dirty_mask(), 0);
        assert!(chunk.mutation_generation() > gen0);

        chunk.mark_section_mutation(2);
        chunk.mark_section_mutation(3);
        assert_eq!(chunk.dirty_mask(), 0b1100);
        let s2 = [7u16; 4096];
        let s3 = [9u16; 4096];
        chunk.refresh_section(2, &s2, None, None);
        assert_eq!(chunk.dirty_mask(), 0b1000, "section 3 must still be dirty");
        chunk.refresh_section(3, &s3, None, None);
        assert_eq!(chunk.dirty_mask(), 0);

        // Data reflects the LAST refresh per section
        assert_eq!(chunk.sections[2].as_ref().unwrap().get_block_by_index(0), 7);
        assert_eq!(chunk.sections[3].as_ref().unwrap().get_block_by_index(0), 9);

        let mut pkt = [0u8; 32768];
        let mut off = 0;
        assert!(chunk.encode_packet_payload(true, true, &mut pkt, &mut off).is_ok());
    }

    #[test]
    fn test_light_only_refresh() {
        let mut primer = [0u16; CHUNK_PRIMER_SIZE];
        let biomes = [0u8; BIOME_ARRAY_SIZE];
        for x in 0..16 {
            for z in 0..16 {
                primer[(x << 12) | (z << 8) | 2] = 1;
            }
        }
        let mut chunk = NativeChunk::from_primer(0, 6, 6, &primer, &biomes, 11);

        // Java-side light engine ran: same states, new light values.
        let same_states = {
            let mut s = [0u16; 4096];
            for i in 0..4096 { s[i] = chunk.sections[0].as_ref().unwrap().get_block_by_index(i); }
            s
        };
        let new_bl = [5u8; 2048];
        let new_sl = [9u8; 2048];
        chunk.mark_section_mutation(0); // light change rides the section dirty bit
        chunk.refresh_section(0, &same_states, Some(&new_bl), Some(&new_sl));

        assert_eq!(chunk.sections[0].as_ref().unwrap().non_air_count, 256,
            "states untouched by light-only refresh");
        assert_eq!(chunk.sections[0].as_ref().unwrap().block_light[0], 5);
        assert_eq!(chunk.sections[0].as_ref().unwrap().sky_light[2047], 9);
        assert_eq!(chunk.dirty_mask(), 0);

        // Light bytes appear verbatim in the encoded packet.
        let mut pkt = [0u8; 32768];
        let mut off = 0;
        assert!(chunk.encode_packet_payload(true, true, &mut pkt, &mut off).is_ok());
        let pkt = &pkt[..off];
        assert!(pkt.windows(2048).any(|w| w[0] == 5 && w.iter().all(|&b| b == 5)), "block light payload present");
        assert!(pkt.windows(2048).any(|w| w[0] == 9 && w.iter().all(|&b| b == 9)), "sky light payload present");
    }
}
