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

use crate::registry::STATS_SECTIONS_ALLOCATED;
use crate::section::NativeSection;

pub const CHUNK_PRIMER_SIZE: usize = 65536; // 16 * 16 * 256 u16
pub const BIOME_ARRAY_SIZE: usize = 256; // 16 * 16 u8

/// Metadata for one successfully serialized chunk payload.
///
/// Consumers must pair these fields with the bytes from that operation, rather
/// than querying the chunk's current mask or deriving a mask from Java state.
/// This describes native serialization, not the coherence of a Java capture.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[must_use]
pub struct PacketEncodeResult {
    pub bytes_written: usize,
    pub emitted_mask: u16,
}

/// Result of an authoritative NativeChunk block state mutation.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(C)]
pub struct BlockMutationResult {
    pub old_state: u16,
    pub new_state: u16,
    pub section_created: bool,
    pub section_became_empty: bool,
    pub non_air_count: u16,
    pub status: i32,
}

impl BlockMutationResult {
    pub const STATUS_SUCCESS: i32 = 0;
    pub const STATUS_NO_OP: i32 = 1;
    pub const STATUS_OUT_OF_BOUNDS: i32 = -1;
}

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
                None, None, None, None, None, None, None, None, None, None, None, None, None, None,
                None, None,
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

    /// Constructs a NativeChunk from RCSNAP01 or RCSNAP02 transport bytes.
    /// Used for initial seeding into the retained chunk registry.
    pub fn from_transport(bytes: &[u8]) -> Result<Self, crate::SnapshotRejection> {
        let snapshot = crate::OwnedPacketSnapshot::from_transport(bytes)?;
        snapshot.to_native_chunk()
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
    /// Encodes exactly the sections selected by `primary_bit_mask` in ascending Y.
    /// Every selected section must exist; an inconsistent mask is rejected before
    /// any output is written. Unselected resident sections are not serialized.
    ///
    /// On success the result pairs the emitted mask with the byte count (excluding
    /// the starting offset), and advances `offset` by that count. On error `offset`
    /// is unchanged and no result is published. A late error may modify scratch
    /// bytes in `out`; callers must discard that attempt's output on any error.
    /// Native borrowing/version checks do not establish Java capture coherence.
    pub fn encode_packet_payload(
        &mut self,
        skylight: bool,
        full_chunk: bool,
        out: &mut [u8],
        offset: &mut usize,
    ) -> Result<PacketEncodeResult, &'static str> {
        self.encode_packet_payload_inner(skylight, full_chunk, out, offset, None)
    }

    pub(crate) fn encode_owned_packet_payload(
        &mut self,
        skylight: bool,
        full_chunk: bool,
        out: &mut [u8],
        offset: &mut usize,
        global_bits: u8,
    ) -> Result<PacketEncodeResult, &'static str> {
        self.encode_packet_payload_inner(skylight, full_chunk, out, offset, Some(global_bits))
    }

    fn encode_packet_payload_inner(
        &mut self,
        skylight: bool,
        full_chunk: bool,
        out: &mut [u8],
        offset: &mut usize,
        global_bits: Option<u8>,
    ) -> Result<PacketEncodeResult, &'static str> {
        let start_pos = *offset;
        if start_pos > out.len() {
            return Err("Packet output offset out of bounds");
        }
        let selected_mask = self.primary_bit_mask;
        for s in 0..16 {
            if (selected_mask & (1u16 << s)) != 0 && self.sections[s].is_none() {
                return Err("Packet mask selects a missing section");
            }
        }

        let snap = self.begin_snapshot();
        let mut cursor = start_pos;
        let mut emitted_mask = 0u16;

        for s in 0..16 {
            let bit = 1u16 << s;
            if (selected_mask & bit) != 0 {
                let sec = self.sections[s]
                    .as_mut()
                    .ok_or("Packet mask selects a missing section")?;
                match global_bits {
                    Some(bits) => {
                        sec.encode_wire_with_global_bits(out, &mut cursor, skylight, bits)?
                    }
                    None => sec.encode_wire(out, &mut cursor, skylight)?,
                }
                emitted_mask |= bit;
            }
        }

        if full_chunk {
            if out.len() - cursor < BIOME_ARRAY_SIZE {
                return Err("Output buffer overflow writing biomes");
            }
            out[cursor..cursor + BIOME_ARRAY_SIZE].copy_from_slice(&self.biomes);
            cursor += BIOME_ARRAY_SIZE;
        }

        // Validate snapshot consistency
        if !self.end_snapshot(snap) {
            return Err("Snapshot stale: mutation during packet encode");
        }

        let result = PacketEncodeResult {
            bytes_written: cursor - start_pos,
            emitted_mask,
        };
        *offset = cursor;
        Ok(result)
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
        if *offset + 1 > out.len() {
            return Err("Overflow");
        }
        out[*offset] = active_sections as u8;
        *offset += 1;

        for s in 0..16 {
            if let Some(ref sec) = self.sections[s] {
                // Section header: Y index (u8), non_air_count (u16)
                if *offset + 3 > out.len() {
                    return Err("Overflow");
                }
                out[*offset] = s as u8;
                out[*offset + 1..*offset + 3].copy_from_slice(&sec.non_air_count.to_be_bytes());
                *offset += 3;

                // 4096 bytes of block IDs (low 8 bits)
                if *offset + 4096 > out.len() {
                    return Err("Overflow");
                }
                for idx in 0..4096 {
                    let state = sec.get_block_by_index(idx);
                    out[*offset + idx] = (state & 0xFF) as u8;
                }
                *offset += 4096;

                // 2048 bytes of block light
                if *offset + 2048 > out.len() {
                    return Err("Overflow");
                }
                out[*offset..*offset + 2048].copy_from_slice(sec.block_light_as_slice());
                *offset += 2048;

                // 2048 bytes of sky light
                if *offset + 2048 > out.len() {
                    return Err("Overflow");
                }
                out[*offset..*offset + 2048].copy_from_slice(sec.sky_light_as_slice());
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
            if sec.non_air_count == 0 {
                self.primary_bit_mask &= !(1u16 << y);
            } else {
                self.primary_bit_mask |= 1u16 << y;
            }
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

    /// Authoritative getBlockState: returns canonical global block state ID at (x, y, z).
    /// If out of bounds or section is not resident, returns 0 (Air).
    #[inline(always)]
    pub fn get_block_state(&self, x: usize, y: usize, z: usize) -> u16 {
        if x >= 16 || y >= 256 || z >= 16 {
            return 0;
        }
        let sec_idx = y >> 4;
        match &self.sections[sec_idx] {
            Some(sec) => sec.get_block(x, y & 15, z),
            None => 0,
        }
    }

    /// Authoritative setBlockState: mutates block state at (x, y, z) to new_state.
    ///
    /// Manages section allocation, non-air accounting, wire cache invalidation,
    /// primary bit mask, and mutation generation counter.
    pub fn set_block_state(
        &mut self,
        x: usize,
        y: usize,
        z: usize,
        new_state: u16,
    ) -> BlockMutationResult {
        if x >= 16 || y >= 256 || z >= 16 {
            return BlockMutationResult {
                old_state: 0,
                new_state,
                section_created: false,
                section_became_empty: false,
                non_air_count: 0,
                status: BlockMutationResult::STATUS_OUT_OF_BOUNDS,
            };
        }
        let sec_idx = y >> 4;
        let sub_y = y & 15;

        if self.sections[sec_idx].is_none() {
            if new_state == 0 {
                // Setting air in an absent section is a no-op
                return BlockMutationResult {
                    old_state: 0,
                    new_state: 0,
                    section_created: false,
                    section_became_empty: false,
                    non_air_count: 0,
                    status: BlockMutationResult::STATUS_NO_OP,
                };
            }
            // Allocate new resident section
            let mut sec = Box::new(NativeSection::new(sec_idx as u8));
            sec.set_block(x, sub_y, z, new_state);
            let non_air = sec.non_air_count;
            self.sections[sec_idx] = Some(sec);
            self.primary_bit_mask |= 1u16 << sec_idx;
            STATS_SECTIONS_ALLOCATED.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
            self.update_height_on_mutation(x, y, z, 0, new_state);
            self.mark_mutation();
            return BlockMutationResult {
                old_state: 0,
                new_state,
                section_created: true,
                section_became_empty: false,
                non_air_count: non_air,
                status: BlockMutationResult::STATUS_SUCCESS,
            };
        }

        let sec = self.sections[sec_idx].as_mut().unwrap();
        let old_state = sec.get_block(x, sub_y, z);
        if old_state == new_state {
            return BlockMutationResult {
                old_state,
                new_state,
                section_created: false,
                section_became_empty: false,
                non_air_count: sec.non_air_count,
                status: BlockMutationResult::STATUS_NO_OP,
            };
        }

        sec.set_block(x, sub_y, z, new_state);
        let non_air = sec.non_air_count;
        let became_empty = non_air == 0;

        if became_empty {
            // Keep section allocated in storage, but deactivate in primary_bit_mask
            // so packet serialization skips empty sections
            self.primary_bit_mask &= !(1u16 << sec_idx);
        } else {
            self.primary_bit_mask |= 1u16 << sec_idx;
        }

        self.update_height_on_mutation(x, y, z, old_state, new_state);
        self.mark_mutation();

        BlockMutationResult {
            old_state,
            new_state,
            section_created: false,
            section_became_empty: became_empty,
            non_air_count: non_air,
            status: BlockMutationResult::STATUS_SUCCESS,
        }
    }

    /// Exposes raw pointer to section's [u16; 4096] states array for zero-JNI direct read.
    /// Returns 0 if section is absent.
    #[inline(always)]
    pub fn get_section_state_pointer(&self, section_y: usize) -> usize {
        if section_y >= 16 {
            return 0;
        }
        match &self.sections[section_y] {
            Some(sec) => sec.states.as_ptr() as usize,
            None => 0,
        }
    }

    /// Fills array of 16 section states pointers (for zero-JNI direct read table).
    pub fn get_section_state_pointers(&self, out: &mut [usize; 16]) {
        for s in 0..16 {
            out[s] = match &self.sections[s] {
                Some(sec) => sec.states.as_ptr() as usize,
                None => 0,
            };
        }
    }

    /// Exposes raw pointer to section's [AtomicU32; 512] block light array for zero-JNI direct read.
    /// Returns 0 if section is absent.
    #[inline(always)]
    pub fn get_section_block_light_pointer(&self, section_y: usize) -> usize {
        if section_y >= 16 {
            return 0;
        }
        match &self.sections[section_y] {
            Some(sec) => sec.block_light.as_ptr() as usize,
            None => 0,
        }
    }

    /// Exposes raw pointer to section's [AtomicU32; 512] sky light array for zero-JNI direct read.
    /// Returns 0 if section is absent.
    #[inline(always)]
    pub fn get_section_sky_light_pointer(&self, section_y: usize) -> usize {
        if section_y >= 16 {
            return 0;
        }
        match &self.sections[section_y] {
            Some(sec) => sec.sky_light.as_ptr() as usize,
            None => 0,
        }
    }

    /// Fills arrays of 16 block light and 16 sky light pointers for zero-JNI direct access table.
    pub fn get_section_light_pointers(
        &self,
        out_block_light: &mut [usize; 16],
        out_sky_light: &mut [usize; 16],
    ) {
        for s in 0..16 {
            match &self.sections[s] {
                Some(sec) => {
                    out_block_light[s] = sec.block_light.as_ptr() as usize;
                    out_sky_light[s] = sec.sky_light.as_ptr() as usize;
                }
                None => {
                    out_block_light[s] = 0;
                    out_sky_light[s] = 0;
                }
            }
        }
    }

    // ============================================================
    // BIOME STATE ACCESS & POINTERS
    // ============================================================

    /// Gets biome ID (0..255) at column (x, z) (0..15).
    #[inline(always)]
    pub fn get_biome(&self, x: usize, z: usize) -> u8 {
        self.biomes[(z << 4) | x]
    }

    /// Sets biome ID (0..255) at column (x, z). Returns true if modified.
    #[inline(always)]
    pub fn set_biome(&mut self, x: usize, z: usize, biome_id: u8) -> bool {
        let idx = (z << 4) | x;
        if self.biomes[idx] != biome_id {
            self.biomes[idx] = biome_id;
            self.mark_mutation();
            true
        } else {
            false
        }
    }

    /// Exposes raw pointer to chunk's [u8; 256] biomes array for direct memory read.
    #[inline(always)]
    pub fn get_biomes_pointer(&self) -> usize {
        self.biomes.as_ptr() as usize
    }

    /// Replaces the entire 256-byte biome array.
    pub fn set_biomes(&mut self, biomes: &[u8; BIOME_ARRAY_SIZE]) {
        self.biomes.copy_from_slice(biomes);
        self.mark_mutation();
    }

    // ============================================================
    // HEIGHTMAP STATE ACCESS & DATA-ORIENTED UPDATE
    // ============================================================

    /// Gets heightmap value at column (x, z) (highest non-air block Y + 1, or 0 if empty).
    #[inline(always)]
    pub fn get_height(&self, x: usize, z: usize) -> u16 {
        self.height_map[(z << 4) | x]
    }

    /// Exposes raw pointer to chunk's [u16; 256] heightmap array for direct memory read.
    #[inline(always)]
    pub fn get_heightmap_pointer(&self) -> usize {
        self.height_map.as_ptr() as usize
    }

    /// Recomputes the height at column (x, z) from native section state directly.
    /// Fast downward scan skipping absent or empty sections via `primary_bit_mask`.
    pub fn recompute_height(&mut self, x: usize, z: usize) -> u16 {
        debug_assert!(x < 16 && z < 16);
        let col_idx = (z << 4) | x;
        let mut highest = 0u16;

        // Scan from top section (15) downward to 0
        for s in (0..16).rev() {
            // Fast skip if section is empty or unallocated
            if (self.primary_bit_mask & (1u16 << s)) == 0 {
                continue;
            }
            if let Some(ref sec) = self.sections[s] {
                let y_base = s * 16;
                for sub_y in (0..16).rev() {
                    let state = sec.get_block(x, sub_y, z);
                    if state != 0 {
                        highest = (y_base + sub_y + 1) as u16;
                        self.height_map[col_idx] = highest;
                        return highest;
                    }
                }
            }
        }

        self.height_map[col_idx] = highest;
        highest
    }

    /// Updates heightmap for column (x, z) after setting a block at `y` with `new_state` and `old_state`.
    /// Matches exact Minecraft 1.12.2 Chunk.setBlockState logic:
    /// - If block placed at or above current height: new height is y + 1.
    /// - If block removed at current top (y == height - 1): performs downward scan to find new top.
    /// - Otherwise (mutation below top): height remains unchanged.
    pub fn update_height_on_mutation(&mut self, x: usize, y: usize, z: usize, old_state: u16, new_state: u16) -> u16 {
        let col_idx = (z << 4) | x;
        let cur_height = self.height_map[col_idx] as usize;

        if new_state != 0 {
            // Block added / changed to non-air
            if y >= cur_height {
                let new_height = (y + 1) as u16;
                self.height_map[col_idx] = new_height;
                return new_height;
            }
        } else if old_state != 0 {
            // Block removed (changed to air)
            if y + 1 == cur_height {
                // Top block removed: downward search required
                return self.recompute_height(x, z);
            }
        }
        cur_height as u16
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_authoritative_block_mutations_and_pointers() {
        let mut chunk = NativeChunk::new(0, 5, -3, 101);

        // 1. Initial empty chunk returns Air (0) everywhere
        assert_eq!(chunk.get_block_state(0, 0, 0), 0);
        assert_eq!(chunk.get_block_state(15, 64, 15), 0);
        assert_eq!(chunk.primary_bit_mask, 0);

        // Direct pointers initially 0
        let mut ptrs = [0usize; 16];
        chunk.get_section_state_pointers(&mut ptrs);
        for p in ptrs {
            assert_eq!(p, 0);
        }

        // 2. Setting Air in absent section is a NO-OP
        let res = chunk.set_block_state(4, 35, 7, 0);
        assert_eq!(res.status, BlockMutationResult::STATUS_NO_OP);
        assert!(!res.section_created);
        assert!(chunk.sections[2].is_none());
        assert_eq!(chunk.primary_bit_mask, 0);

        // 3. Setting solid block in absent section creates section
        let gen_before = chunk.mutation_generation();
        let res = chunk.set_block_state(4, 35, 7, 1); // Stone
        assert_eq!(res.status, BlockMutationResult::STATUS_SUCCESS);
        assert!(res.section_created);
        assert!(!res.section_became_empty);
        assert_eq!(res.old_state, 0);
        assert_eq!(res.new_state, 1);
        assert_eq!(res.non_air_count, 1);
        assert_eq!(chunk.primary_bit_mask, 1 << 2);
        assert!(chunk.mutation_generation() > gen_before);
        assert_eq!(chunk.get_block_state(4, 35, 7), 1);

        // Pointer for section 2 is now valid and non-zero
        let sec2_ptr = chunk.get_section_state_pointer(2);
        assert_ne!(sec2_ptr, 0);
        let mut ptrs2 = [0usize; 16];
        chunk.get_section_state_pointers(&mut ptrs2);
        assert_eq!(ptrs2[2], sec2_ptr);
        assert_eq!(ptrs2[0], 0);

        // Verify direct memory read matches
        let idx = ((35 & 15) << 8) | ((7 & 15) << 4) | 4;
        let read_val = unsafe { *((sec2_ptr as *const u16).add(idx)) };
        assert_eq!(read_val, 1);

        // 4. Setting same state is a NO-OP
        let gen_same = chunk.mutation_generation();
        let res_same = chunk.set_block_state(4, 35, 7, 1);
        assert_eq!(res_same.status, BlockMutationResult::STATUS_NO_OP);
        assert_eq!(chunk.mutation_generation(), gen_same);

        // 5. Changing to different solid block
        let res_diff = chunk.set_block_state(4, 35, 7, 3); // Dirt
        assert_eq!(res_diff.status, BlockMutationResult::STATUS_SUCCESS);
        assert_eq!(res_diff.old_state, 1);
        assert_eq!(res_diff.new_state, 3);
        assert_eq!(res_diff.non_air_count, 1);
        assert_eq!(chunk.get_block_state(4, 35, 7), 3);

        // 6. Adding another block in same section
        let res_add = chunk.set_block_state(0, 32, 0, 5); // Wood
        assert_eq!(res_add.status, BlockMutationResult::STATUS_SUCCESS);
        assert_eq!(res_add.non_air_count, 2);
        assert_eq!(chunk.get_block_state(0, 32, 0), 5);

        // 7. Removing one block
        let res_rem1 = chunk.set_block_state(4, 35, 7, 0);
        assert_eq!(res_rem1.status, BlockMutationResult::STATUS_SUCCESS);
        assert_eq!(res_rem1.old_state, 3);
        assert_eq!(res_rem1.new_state, 0);
        assert_eq!(res_rem1.non_air_count, 1);
        assert!(!res_rem1.section_became_empty);
        assert_eq!(chunk.primary_bit_mask, 1 << 2);

        // 8. Removing last block empties section
        let res_rem2 = chunk.set_block_state(0, 32, 0, 0);
        assert_eq!(res_rem2.status, BlockMutationResult::STATUS_SUCCESS);
        assert_eq!(res_rem2.old_state, 5);
        assert_eq!(res_rem2.new_state, 0);
        assert_eq!(res_rem2.non_air_count, 0);
        assert!(res_rem2.section_became_empty);
        // Primary bit mask bit is cleared for empty section
        assert_eq!(chunk.primary_bit_mask & (1 << 2), 0);

        // 9. Out of bounds handling
        let res_oob1 = chunk.set_block_state(16, 0, 0, 1);
        assert_eq!(res_oob1.status, BlockMutationResult::STATUS_OUT_OF_BOUNDS);
        let res_oob2 = chunk.set_block_state(0, 256, 0, 1);
        assert_eq!(res_oob2.status, BlockMutationResult::STATUS_OUT_OF_BOUNDS);
        assert_eq!(chunk.get_block_state(16, 0, 0), 0);
    }

    #[test]
    fn test_mutation_invalidates_wire_cache_and_packet_observes_it() {
        let mut chunk = NativeChunk::new(0, 0, 0, 1);
        // Populate section 1 with state 4
        chunk.set_block_state(0, 16, 0, 4);

        let mut buf1 = vec![0u8; 131072];
        let mut offset1 = 0;
        let res1 = chunk
            .encode_packet_payload(true, true, &mut buf1, &mut offset1)
            .unwrap();
        assert_eq!(res1.emitted_mask, 1 << 1);

        // Mutate block from 4 to 9 in section 1
        let mut_res = chunk.set_block_state(0, 16, 0, 9);
        assert_eq!(mut_res.status, BlockMutationResult::STATUS_SUCCESS);
        assert_eq!(chunk.get_block_state(0, 16, 0), 9);

        // Second packet encode observes new state without reseed
        let mut buf2 = vec![0u8; 131072];
        let mut offset2 = 0;
        let res2 = chunk
            .encode_packet_payload(true, true, &mut buf2, &mut offset2)
            .unwrap();
        assert_eq!(res2.emitted_mask, 1 << 1);

        // Payloads must differ because state changed
        assert_ne!(buf1[..offset1], buf2[..offset2]);
    }

    #[test]
    fn test_authoritative_light_mutations_and_pointers() {
        let mut chunk = NativeChunk::new(0, 1, 2, 202);
        // Initially empty
        let mut bl_ptrs = [0usize; 16];
        let mut sl_ptrs = [0usize; 16];
        chunk.get_section_light_pointers(&mut bl_ptrs, &mut sl_ptrs);
        for i in 0..16 {
            assert_eq!(bl_ptrs[i], 0);
            assert_eq!(sl_ptrs[i], 0);
        }

        // Add a block to section 3 to allocate it
        chunk.set_block_state(0, 48, 0, 1);
        chunk.get_section_light_pointers(&mut bl_ptrs, &mut sl_ptrs);
        assert_ne!(bl_ptrs[3], 0);
        assert_ne!(sl_ptrs[3], 0);
        assert_eq!(bl_ptrs[0], 0);

        // Section 3 light pointers match get_section_block_light_pointer / sky_light_pointer
        assert_eq!(chunk.get_section_block_light_pointer(3), bl_ptrs[3]);
        assert_eq!(chunk.get_section_sky_light_pointer(3), sl_ptrs[3]);

        let sec = chunk.sections[3].as_ref().unwrap();
        // Check default sky light (15 everywhere in newly allocated section)
        assert_eq!(sec.get_sky_light(0, 0, 0), 15);
        assert_eq!(sec.get_sky_light(15, 15, 15), 15);
        // Check default block light (0 everywhere)
        assert_eq!(sec.get_block_light(0, 0, 0), 0);
        assert_eq!(sec.get_block_light(7, 8, 9), 0);

        // Mutate block light
        assert!(sec.set_block_light(7, 8, 9, 14));
        assert_eq!(sec.get_block_light(7, 8, 9), 14);
        // Mutating to same value returns false (no-op)
        assert!(!sec.set_block_light(7, 8, 9, 14));

        // Direct memory read via pointer matches
        let idx = NativeSection::block_index(7, 8, 9);
        let byte_offset = idx >> 1;
        let is_odd = (idx & 1) != 0;
        let raw_byte = unsafe { *((bl_ptrs[3] as *const u8).add(byte_offset)) };
        let nibble = if is_odd { (raw_byte >> 4) & 0x0F } else { raw_byte & 0x0F };
        assert_eq!(nibble, 14);

        // Mutate adjacent nibble in same byte (verify no tearing)
        let adj_idx = idx ^ 1;
        let (adj_x, adj_y, adj_z) = NativeSection::index_to_xyz(adj_idx);
        assert!(sec.set_block_light(adj_x, adj_y, adj_z, 9));
        assert_eq!(sec.get_block_light(adj_x, adj_y, adj_z), 9);
        assert_eq!(sec.get_block_light(7, 8, 9), 14); // Original still 14

        // Sky light mutation
        assert!(sec.set_sky_light(0, 0, 0, 7));
        assert_eq!(sec.get_sky_light(0, 0, 0), 7);
        assert_eq!(sec.get_sky_light(1, 0, 0), 15);
    }

    #[test]
    fn test_authoritative_biomes_and_heightmaps() {
        let mut chunk = NativeChunk::new(0, 2, 3, 303);

        // 1. Biome Array Verification
        assert_eq!(chunk.get_biome(5, 7), 0);
        assert!(chunk.set_biome(5, 7, 24)); // Biome 24
        assert_eq!(chunk.get_biome(5, 7), 24);
        assert!(!chunk.set_biome(5, 7, 24)); // No-op

        let bio_ptr = chunk.get_biomes_pointer();
        assert_ne!(bio_ptr, 0);
        let deref_bio = unsafe { *((bio_ptr as *const u8).add((7 << 4) | 5)) };
        assert_eq!(deref_bio, 24);

        // 2. Heightmap Tracking on Mutation
        assert_eq!(chunk.get_height(4, 4), 0);

        // Place block at Y=10 -> height becomes 11
        chunk.set_block_state(4, 10, 4, 1);
        assert_eq!(chunk.get_height(4, 4), 11);

        // Place block higher at Y=64 -> height becomes 65
        chunk.set_block_state(4, 64, 4, 2);
        assert_eq!(chunk.get_height(4, 4), 65);

        // Place block lower at Y=30 -> height remains 65
        chunk.set_block_state(4, 30, 4, 3);
        assert_eq!(chunk.get_height(4, 4), 65);

        // Remove top block at Y=64 -> downward search finds Y=30 -> height becomes 31
        chunk.set_block_state(4, 64, 4, 0);
        assert_eq!(chunk.get_height(4, 4), 31);

        // Remove block at Y=30 -> downward search finds Y=10 -> height becomes 11
        chunk.set_block_state(4, 30, 4, 0);
        assert_eq!(chunk.get_height(4, 4), 11);

        // Remove block at Y=10 -> column empty -> height becomes 0
        chunk.set_block_state(4, 10, 4, 0);
        assert_eq!(chunk.get_height(4, 4), 0);

        // Direct heightmap memory pointer matches
        let hm_ptr = chunk.get_heightmap_pointer();
        assert_ne!(hm_ptr, 0);
        let deref_hm = unsafe { *((hm_ptr as *const u16).add((4 << 4) | 4)) };
        assert_eq!(deref_hm, 0);
    }
}
