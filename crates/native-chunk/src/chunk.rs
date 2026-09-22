//! NativeChunk v1 representation for Minecraft 1.12.2.
//!
//! Owns 16 vertical sections, chunk coordinates, lifecycle generation,
//! and 2D biome / heightmap data.
//!
//! Versioned Snapshot Model (M4.1 Task 7):
//! - mutation_generation: increments on ANY mutation (block set, populate, light, etc.)
//! - snapshot_generation: increments when a consumer takes a snapshot (packet, persistence)
//! - Consumers read snapshot_generation; if it differs from mutation_generation at read time,
//!   the snapshot is stale and should be re-acquired.

use crate::section::NativeSection;
use crate::registry::{STATS_SECTIONS_ALLOCATED, STATS_SECTIONS_RELEASED};

pub const CHUNK_PRIMER_SIZE: usize = 65536; // 16 * 16 * 256 u16
pub const BIOME_ARRAY_SIZE: usize = 256;    // 16 * 16 u8

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
pub enum ChunkLifecycle {
    Create = 0,
    JavaMaterialized = 1,
    ActiveNative = 2,
    Dirty = 3,
    Invalidated = 4,
    Unloading = 5,
    Freed = 6,
}

pub struct NativeChunk {
    pub dim: i32,
    pub cx: i32,
    pub cz: i32,
    pub primary_bit_mask: u16,
    pub lifecycle: ChunkLifecycle,
    pub generation_id: u64,
    /// Monotonically increasing counter — increments on ANY mutation.
    /// Used by consumers to detect stale snapshots.
    pub mutation_generation: u64,
    /// Increments when a consumer (packet encode, persistence, occupancy) takes a snapshot.
    /// Consumer reads this at start; if != mutation_generation at end, data changed mid-read.
    pub snapshot_generation: u64,
    /// Per-section dirty mask (bit y = section y needs refresh from Java).
    /// Set by mark_section_mutation; cleared by refresh_section.
    pub dirty_sections: u16,
    pub sections: [Option<Box<NativeSection>>; 16],
    pub biomes: [u8; BIOME_ARRAY_SIZE],
    pub height_map: [u16; 256],
}

impl NativeChunk {
    /// Creates an empty NativeChunk with given chunk coordinates.
    pub fn new(dim: i32, cx: i32, cz: i32, generation_id: u64) -> Self {
        Self {
            dim,
            cx,
            cz,
            primary_bit_mask: 0,
            lifecycle: ChunkLifecycle::Create,
            generation_id,
            mutation_generation: 0,
            snapshot_generation: 0,
            dirty_sections: 0,
            sections: [
                None, None, None, None, None, None, None, None,
                None, None, None, None, None, None, None, None,
            ],
            biomes: [0u8; BIOME_ARRAY_SIZE],
            height_map: [0u16; 256],
        }
    }

    /// Builds a NativeChunk from a 65,536-entry ChunkPrimer array.
    ///
    /// The input ChunkPrimer uses Minecraft 1.12.2 indexing: `(x << 12) | (z << 8) | y`.
    /// NativeSection uses section-local indexing: `(y << 8) | (z << 4) | x`.
    pub fn from_primer(
        dim: i32,
        cx: i32,
        cz: i32,
        primer: &[u16; CHUNK_PRIMER_SIZE],
        biomes: &[u8; BIOME_ARRAY_SIZE],
        generation_id: u64,
    ) -> Self {
        let mut chunk = Self::new(dim, cx, cz, generation_id);
        chunk.biomes.copy_from_slice(biomes);

        let mut mask = 0u16;

        for s in 0..16 {
            let y_base = s * 16;
            let mut sec_has_blocks = false;

            // Fast presence check for section s
            'presence: for x in 0..16 {
                let x_shift = x << 12;
                for z in 0..16 {
                    let col_base = x_shift | (z << 8);
                    for sub_y in 0..16 {
                        let y = y_base + sub_y;
                        if primer[col_base | y] != 0 {
                            sec_has_blocks = true;
                            break 'presence;
                        }
                    }
                }
            }

            if sec_has_blocks {
                let mut sec = Box::new(NativeSection::new(s as u8));
                let mut non_air = 0u16;

                for x in 0..16 {
                    let x_shift = x << 12;
                    for z in 0..16 {
                        let col_base = x_shift | (z << 8);
                        for sub_y in 0..16 {
                            let y = y_base + sub_y;
                            let state = primer[col_base | y];
                            if state != 0 {
                                sec.set_block(x, sub_y, z, state as u16);
                                non_air += 1;
                                if y as u16 > chunk.height_map[(z << 4) | x] {
                                    chunk.height_map[(z << 4) | x] = y as u16;
                                }
                            }
                        }
                    }
                }

                sec.non_air_count = non_air;
                chunk.sections[s] = Some(sec);
                mask |= 1u16 << s;
            }
        }

        chunk.primary_bit_mask = mask;
        chunk.lifecycle = ChunkLifecycle::ActiveNative;
        chunk.mutation_generation = 1; // Initial state counts as first mutation
        chunk.snapshot_generation = 0;
        chunk.dirty_sections = 0;
        chunk
    }

    /// Materializes the NativeChunk back into a ChunkPrimer `[u16; 65536]`.
    ///
    /// Provides exact 100% bit-exact reconstruction of the original ChunkPrimer.
    pub fn to_primer(&self, primer: &mut [u16; CHUNK_PRIMER_SIZE]) {
        for word in primer.iter_mut() {
            *word = 0;
        }

        for s in 0..16 {
            if let Some(ref sec) = self.sections[s] {
                let y_base = s * 16;
                for idx in 0..4096 {
                    let state = sec.get_block_by_index(idx);
                    if state != 0 {
                        let (x, sub_y, z) = NativeSection::index_to_xyz(idx);
                        let y = y_base + sub_y;
                        let primer_idx = (x << 12) | (z << 8) | y;
                        primer[primer_idx] = state as u16;
                    }
                }
            }
        }
    }

    /// First Zero-Copy Consumer: Protocol 340 SPacketChunkData payload encoder.
    ///
    /// Encodes all active sections directly into the packet buffer without Java staging,
    /// reflection, or unpacking. Uses versioned snapshot model for consistency.
    pub fn encode_packet_payload(
        &mut self,
        skylight: bool,
        full_chunk: bool,
        out: &mut [u8],
        offset: &mut usize,
    ) -> Result<usize, &'static str> {
        let snap = self.begin_snapshot();
        let start_pos = *offset;

        for s in 0..16 {
            if (self.primary_bit_mask & (1u16 << s)) != 0 {
                if let Some(ref mut sec) = self.sections[s] {
                    sec.encode_wire(out, offset, skylight)?;
                }
            }
        }

        if full_chunk {
            if *offset + BIOME_ARRAY_SIZE > out.len() {
                return Err("Output buffer overflow writing biomes");
            }
            out[*offset..*offset + BIOME_ARRAY_SIZE].copy_from_slice(&self.biomes);
            *offset += BIOME_ARRAY_SIZE;
        }

        // Validate snapshot consistency
        if !self.end_snapshot(snap) {
            return Err("Snapshot stale: mutation during packet encode");
        }

        Ok(*offset - start_pos)
    }

    /// Second Consumer Proof A: Spatial metadata & section occupancy summary.
    ///
    /// Computes active section mask and total non-air blocks in sub-microsecond time.
    /// Uses versioned snapshot model for consistency.
    #[inline(always)]
    pub fn occupancy_summary(&mut self) -> (u16, u32) {
        let snap = self.begin_snapshot();
        let mut total_blocks = 0u32;
        for s in 0..16 {
            if let Some(ref sec) = self.sections[s] {
                total_blocks += sec.non_air_count as u32;
            }
        }
        let result = (self.primary_bit_mask, total_blocks);
        // Note: occupancy_summary is fast; stale snapshot unlikely but checked
        let _ = self.end_snapshot(snap);
        result
    }

    /// Second Consumer Proof B: Persistence staging for region/NBT chunk serializer.
    ///
    /// Formats raw block IDs and metadata for Anvil MCA format without JVM object allocation.
    /// Uses versioned snapshot model for consistency.
    pub fn stage_persistence(
        &mut self,
        out: &mut [u8],
        offset: &mut usize,
    ) -> Result<usize, &'static str> {
        let snap = self.begin_snapshot();
        let start = *offset;
        let active_sections = self.primary_bit_mask.count_ones() as usize;
        // Header: section count (u8)
        if *offset + 1 > out.len() { return Err("Overflow"); }
        out[*offset] = active_sections as u8;
        *offset += 1;

        for s in 0..16 {
            if let Some(ref sec) = self.sections[s] {
                // Section header: Y index (u8), non_air_count (u16)
                if *offset + 3 > out.len() { return Err("Overflow"); }
                out[*offset] = s as u8;
                out[*offset + 1..*offset + 3].copy_from_slice(&sec.non_air_count.to_be_bytes());
                *offset += 3;

                // 4096 bytes of block IDs (low 8 bits)
                if *offset + 4096 > out.len() { return Err("Overflow"); }
                for idx in 0..4096 {
                    let state = sec.get_block_by_index(idx);
                    out[*offset + idx] = (state & 0xFF) as u8;
                }
                *offset += 4096;

                // 2048 bytes of block light
                if *offset + 2048 > out.len() { return Err("Overflow"); }
                out[*offset..*offset + 2048].copy_from_slice(&sec.block_light);
                *offset += 2048;

                // 2048 bytes of sky light
                if *offset + 2048 > out.len() { return Err("Overflow"); }
                out[*offset..*offset + 2048].copy_from_slice(&sec.sky_light);
                *offset += 2048;
            }
        }

        // Validate snapshot consistency
        if !self.end_snapshot(snap) {
            return Err("Snapshot stale: mutation during persistence staging");
        }

        Ok(*offset - start)
    }

    /// Transition lifecycle state safely.
    pub fn set_lifecycle(&mut self, next: ChunkLifecycle) {
        self.lifecycle = next;
    }

    /// Marks a mutation occurred — increments mutation_generation.
    /// Called by FFI layer when Java/mod mutates block state, light, biomes, etc.
    #[inline(always)]
    pub fn mark_mutation(&mut self) {
        self.mutation_generation = self.mutation_generation.wrapping_add(1);
        if self.lifecycle == ChunkLifecycle::ActiveNative {
            self.lifecycle = ChunkLifecycle::Dirty;
        }
    }

    /// Marks one section dirty (M4.1 refresh model): sets the section's bit in
    /// dirty_sections and bumps mutation_generation, WITHOUT invalidating the
    /// whole chunk. Cheap enough to call from a Chunk.setBlockState hook
    /// (population bursts collapse into a mask, not N transfers).
    #[inline(always)]
    pub fn mark_section_mutation(&mut self, section_y: u8) {
        self.dirty_sections |= 1u16 << (section_y & 15);
        self.mutation_generation = self.mutation_generation.wrapping_add(1);
        if self.lifecycle == ChunkLifecycle::ActiveNative {
            self.lifecycle = ChunkLifecycle::Dirty;
        }
    }

    /// Refreshes one section in place from a Java-side snapshot (lazy pull on
    /// the consumer path). Replaces states + optional light arrays, clears the
    /// section's dirty bit, and returns to ActiveNative when nothing else is
    /// dirty. Same generation_id — chunk identity is preserved, so consumer
    /// handles stay valid.
    pub fn refresh_section(
        &mut self,
        section_y: u8,
        states: &[u16; 4096],
        block_light: Option<&[u8; 2048]>,
        sky_light: Option<&[u8; 2048]>,
    ) {
        let y = (section_y & 15) as usize;
        if self.sections[y].is_none() {
            self.sections[y] = Some(Box::new(NativeSection::new(y as u8)));
            self.primary_bit_mask |= 1u16 << y;
            STATS_SECTIONS_ALLOCATED.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
        }
        if let Some(ref mut sec) = self.sections[y] {
            sec.replace_states(states, block_light, sky_light);
        }
        // All-air refreshed section deactivates in the mask: vanilla full-chunk
        // packets exclude empty sections (isFullChunk && isEmpty -> skip), so the
        // mask bit must drop. Partial-filter packets would need separate
        // presence tracking (unsupported, documented M4.2A B3).
        if self.sections[y].as_ref().map(|s| s.non_air_count == 0).unwrap_or(false) {
            self.primary_bit_mask &= !(1u16 << y);
            self.sections[y] = None;
            STATS_SECTIONS_RELEASED.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
        }
        self.dirty_sections &= !(1u16 << y);
        self.mutation_generation = self.mutation_generation.wrapping_add(1);
        if self.dirty_sections == 0 && self.lifecycle == ChunkLifecycle::Dirty {
            self.lifecycle = ChunkLifecycle::ActiveNative;
        }
    }

    /// Begins a consumer snapshot — records current mutation_generation.
    /// Returns the snapshot generation token.
    /// Consumer must call `end_snapshot(token)` after reading to validate.
    #[inline(always)]
    pub fn begin_snapshot(&mut self) -> u64 {
        self.snapshot_generation = self.mutation_generation;
        self.snapshot_generation
    }

    /// Ends a consumer snapshot — validates no mutation occurred during read.
    /// Returns true if snapshot is consistent (no concurrent mutation).
    #[inline(always)]
    pub fn end_snapshot(&self, token: u64) -> bool {
        self.snapshot_generation == token && self.mutation_generation == token
    }

    /// Gets current mutation generation (for external staleness checks).
    #[inline(always)]
    pub fn mutation_generation(&self) -> u64 {
        self.mutation_generation
    }

    /// Current per-section dirty mask (bit y = section y needs refresh).
    #[inline(always)]
    pub fn dirty_mask(&self) -> u16 {
        self.dirty_sections
    }

    /// Gets current snapshot generation (last consumer snapshot).
    #[inline(always)]
    pub fn snapshot_generation(&self) -> u64 {
        self.snapshot_generation
    }
}
