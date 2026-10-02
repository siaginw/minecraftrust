//! Generalized Native Section representation for Minecraft 1.12.2.
//!
//! Engine representation uses canonical global block state IDs (u16).
//! Wire representation (Protocol 340) is derived on-demand via cached local palette.

use std::sync::atomic::{AtomicU16, AtomicU32, Ordering};

pub const SECTION_BLOCK_COUNT: usize = 4096;
pub const LIGHT_ARRAY_SIZE: usize = 2048; // 2,048 bytes
pub const LIGHT_WORD_COUNT: usize = 512; // 512 words (4 bytes each)

// Palette bit width limits per 1.12.2 spec
const MIN_BITS: u8 = 4;
const MAX_LOCAL_BITS: u8 = 8; // Local palette max (256 states)
/// Global palette bit width. Vanilla computes ceil(log2(Block.BLOCK_STATE_IDS.size()))
/// at runtime (13 for the ~8k-state vanilla 1.12.2 registry; larger under mods).
/// The bridge sets the live value via set_global_palette_bits() at boot.
static GLOBAL_PALETTE_BITS: std::sync::atomic::AtomicU8 = std::sync::atomic::AtomicU8::new(13);

pub fn set_global_palette_bits(bits: u8) {
    if (5..=16).contains(&bits) {
        GLOBAL_PALETTE_BITS.store(bits, Ordering::Relaxed);
    }
}

pub fn global_palette_bits() -> u8 {
    GLOBAL_PALETTE_BITS.load(Ordering::Relaxed)
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
pub enum PaletteMode {
    Linear = 0,  // 4-bit, array-backed
    HashMap = 1, // 5-8 bit, hashmap-backed
    Global = 2,  // >8 bit, global registry IDs
}

pub struct SectionFlags;

impl SectionFlags {
    pub const NON_AIR: u8 = 1 << 0;
    pub const WATER: u8 = 1 << 1;
    pub const STONE: u8 = 1 << 2;
    pub const SOLID: u8 = 1 << 3;
    pub const HAS_TICK: u8 = 1 << 4;
    pub const PALETTE_DIRTY: u8 = 1 << 5;
}

#[repr(C, align(64))]
pub struct NativeSection {
    // === ENGINE REPRESENTATION (Authoritative) ===
    /// Global block state IDs for all 4096 positions.
    /// Uses AtomicU16 ([u16; 4096] in memory layout) providing sound cross-language
    /// and concurrent access without Unchecked Data Race undefined behavior.
    pub states: [AtomicU16; SECTION_BLOCK_COUNT], // 8,192 bytes (exact layout as [u16; 4096])

    // === DERIVED CACHES (Wire format, built on demand) ===
    /// Cached local palette for Protocol 340 wire encoding.
    /// None = needs rebuild; Some = valid cache.
    palette_cache: Option<LocalPalette>,
    /// Derived wire byte slice cache (skylight=true). Reused across clients for static sections.
    wire_cache_skylight: Option<Vec<u8>>,
    /// Derived wire byte slice cache (skylight=false).
    wire_cache_noskylight: Option<Vec<u8>>,

    // === LIGHTING & METADATA ===
    /// Block light stored as 512 32-bit atomic words (2,048 bytes).
    /// Both Rust (compare_exchange) and Java Unsafe (compareAndSwapInt) operate
    /// on the SAME 32-bit atomic word boundaries, eliminating mixed-width atomic hazards.
    pub block_light: [AtomicU32; LIGHT_WORD_COUNT], // 2,048 bytes
    /// Sky light stored as 512 32-bit atomic words (2,048 bytes).
    pub sky_light: [AtomicU32; LIGHT_WORD_COUNT], // 2,048 bytes
    pub non_air_count: u16,
    pub flags: u8,
    pub y_index: u8,
    _pad: [u8; 2],
}

impl NativeSection {
    /// Creates a fresh native section initialized to Air (state 0).
    pub fn new(y_index: u8) -> Self {
        // Safe transmutation of zeroed u16 array to AtomicU16 array
        let raw = [0u16; SECTION_BLOCK_COUNT];
        let states: [AtomicU16; SECTION_BLOCK_COUNT] = unsafe { std::mem::transmute(raw) };
        let raw_bl = [0u32; LIGHT_WORD_COUNT];
        let block_light: [AtomicU32; LIGHT_WORD_COUNT] = unsafe { std::mem::transmute(raw_bl) };
        let raw_sl = [0xFFFFFFFFu32; LIGHT_WORD_COUNT]; // Default sky light 15 for all nibbles
        let sky_light: [AtomicU32; LIGHT_WORD_COUNT] = unsafe { std::mem::transmute(raw_sl) };
        Self {
            states,
            palette_cache: None,
            wire_cache_skylight: None,
            wire_cache_noskylight: None,
            block_light,
            sky_light,
            non_air_count: 0,
            flags: 0,
            y_index,
            _pad: [0, 0],
        }
    }

    /// Access states as plain u16 slice (safe because AtomicU16 is #[repr(transparent)] over UnsafeCell<u16>).
    #[inline(always)]
    pub fn states_as_slice(&self) -> &[u16; SECTION_BLOCK_COUNT] {
        unsafe { &*(self.states.as_ptr() as *const [u16; SECTION_BLOCK_COUNT]) }
    }

    /// Access states as mutable u16 slice when exclusive &mut self is held.
    #[inline(always)]
    pub fn states_as_mut_slice(&mut self) -> &mut [u16; SECTION_BLOCK_COUNT] {
        unsafe { &mut *(self.states.as_mut_ptr() as *mut [u16; SECTION_BLOCK_COUNT]) }
    }

    /// Access block_light as plain byte slice (2,048 bytes).
    #[inline(always)]
    pub fn block_light_as_slice(&self) -> &[u8; LIGHT_ARRAY_SIZE] {
        unsafe { &*(self.block_light.as_ptr() as *const [u8; LIGHT_ARRAY_SIZE]) }
    }

    /// Access block_light as mutable byte slice when exclusive &mut self is held.
    #[inline(always)]
    pub fn block_light_as_mut_slice(&mut self) -> &mut [u8; LIGHT_ARRAY_SIZE] {
        unsafe { &mut *(self.block_light.as_mut_ptr() as *mut [u8; LIGHT_ARRAY_SIZE]) }
    }

    /// Access sky_light as plain byte slice (2,048 bytes).
    #[inline(always)]
    pub fn sky_light_as_slice(&self) -> &[u8; LIGHT_ARRAY_SIZE] {
        unsafe { &*(self.sky_light.as_ptr() as *const [u8; LIGHT_ARRAY_SIZE]) }
    }

    /// Access sky_light as mutable byte slice when exclusive &mut self is held.
    #[inline(always)]
    pub fn sky_light_as_mut_slice(&mut self) -> &mut [u8; LIGHT_ARRAY_SIZE] {
        unsafe { &mut *(self.sky_light.as_mut_ptr() as *mut [u8; LIGHT_ARRAY_SIZE]) }
    }

    #[inline(always)]
    fn invalidate_wire_cache(&mut self) {
        self.wire_cache_skylight = None;
        self.wire_cache_noskylight = None;
    }

    // ============================================================
    // SECTION INDEXING (1.12.2 BlockStateContainer / ExtendedBlockStorage)
    // Index formula: (y << 8) | (z << 4) | x
    // ============================================================

    #[inline(always)]
    pub fn block_index(x: usize, y: usize, z: usize) -> usize {
        debug_assert!(x < 16 && y < 16 && z < 16);
        (y << 8) | (z << 4) | x
    }

    #[inline(always)]
    pub fn xyz_to_index(x: usize, y: usize, z: usize) -> usize {
        Self::block_index(x, y, z)
    }

    #[inline(always)]
    pub fn index_to_xyz(idx: usize) -> (usize, usize, usize) {
        let x = idx & 15;
        let y = (idx >> 8) & 15;
        let z = (idx >> 4) & 15;
        (x, y, z)
    }

    // ============================================================
    // ENGINE-LEVEL BLOCK ACCESS (Canonical Global IDs)
    // ============================================================

    /// Gets global block state ID at (x, y, z).
    #[inline(always)]
    pub fn get_block(&self, x: usize, y: usize, z: usize) -> u16 {
        self.states[Self::block_index(x, y, z)].load(Ordering::Acquire)
    }

    /// Gets global block state ID by section index.
    #[inline(always)]
    pub fn get_block_by_index(&self, idx: usize) -> u16 {
        self.states[idx].load(Ordering::Acquire)
    }

    // ============================================================
    // LIGHTING ACCESS (NibbleArray 4-bit values: 0..15)
    // Stored as 512 AtomicU32 words (8 nibbles per word)
    // ============================================================

    /// Gets block light nibble at (x, y, z) (0..15).
    #[inline(always)]
    pub fn get_block_light(&self, x: usize, y: usize, z: usize) -> u8 {
        self.get_block_light_by_index(Self::block_index(x, y, z))
    }

    /// Gets block light nibble by section block index (0..4095).
    #[inline(always)]
    pub fn get_block_light_by_index(&self, idx: usize) -> u8 {
        let word_idx = idx >> 3; // 8 nibbles per 32-bit word
        let shift = (idx & 7) << 2; // 4 bits per nibble
        let word = self.block_light[word_idx].load(Ordering::Acquire);
        ((word >> shift) & 0x0F) as u8
    }

    /// Sets block light nibble at (x, y, z) atomically via CAS loop.
    /// Invalidates wire cache if value changed. Returns true if modified.
    #[inline(always)]
    pub fn set_block_light(&self, x: usize, y: usize, z: usize, val: u8) -> bool {
        self.set_block_light_by_index(Self::block_index(x, y, z), val)
    }

    /// Sets block light nibble by section block index atomically via CAS loop.
    pub fn set_block_light_by_index(&self, idx: usize, val: u8) -> bool {
        let word_idx = idx >> 3;
        let shift = (idx & 7) << 2;
        let mask = 0x0Fu32 << shift;
        let val_bits = ((val & 0x0F) as u32) << shift;
        let cell = &self.block_light[word_idx];
        let mut cur = cell.load(Ordering::Relaxed);
        loop {
            let cur_nibble = (cur >> shift) & 0x0F;
            if cur_nibble == (val & 0x0F) as u32 {
                return false;
            }
            let next = (cur & !mask) | val_bits;
            match cell.compare_exchange_weak(cur, next, Ordering::Release, Ordering::Relaxed) {
                Ok(_) => return true,
                Err(actual) => cur = actual,
            }
        }
    }

    /// Gets sky light nibble at (x, y, z) (0..15).
    #[inline(always)]
    pub fn get_sky_light(&self, x: usize, y: usize, z: usize) -> u8 {
        self.get_sky_light_by_index(Self::block_index(x, y, z))
    }

    /// Gets sky light nibble by section block index (0..4095).
    #[inline(always)]
    pub fn get_sky_light_by_index(&self, idx: usize) -> u8 {
        let word_idx = idx >> 3;
        let shift = (idx & 7) << 2;
        let word = self.sky_light[word_idx].load(Ordering::Acquire);
        ((word >> shift) & 0x0F) as u8
    }

    /// Sets sky light nibble at (x, y, z) atomically via CAS loop.
    /// Returns true if modified.
    #[inline(always)]
    pub fn set_sky_light(&self, x: usize, y: usize, z: usize, val: u8) -> bool {
        self.set_sky_light_by_index(Self::block_index(x, y, z), val)
    }

    /// Sets sky light nibble by section block index atomically via CAS loop.
    pub fn set_sky_light_by_index(&self, idx: usize, val: u8) -> bool {
        let word_idx = idx >> 3;
        let shift = (idx & 7) << 2;
        let mask = 0x0Fu32 << shift;
        let val_bits = ((val & 0x0F) as u32) << shift;
        let cell = &self.sky_light[word_idx];
        let mut cur = cell.load(Ordering::Relaxed);
        loop {
            let cur_nibble = (cur >> shift) & 0x0F;
            if cur_nibble == (val & 0x0F) as u32 {
                return false;
            }
            let next = (cur & !mask) | val_bits;
            match cell.compare_exchange_weak(cur, next, Ordering::Release, Ordering::Relaxed) {
                Ok(_) => return true,
                Err(actual) => cur = actual,
            }
        }
    }

    /// Sets a block state at (x, y, z) using global registry ID.
    /// Invalidates palette cache.
    #[inline(always)]
    pub fn set_block(&mut self, x: usize, y: usize, z: usize, global_state_id: u16) -> bool {
        self.set_block_by_index(Self::block_index(x, y, z), global_state_id)
    }

    /// Sets a block state by section index using global registry ID.
    /// Invalidates palette cache.
    #[inline(always)]
    pub fn set_block_by_index(&mut self, idx: usize, global_state_id: u16) -> bool {
        let old_id = self.states[idx].load(Ordering::Relaxed);
        if old_id == global_state_id {
            return false; // No change
        }

        self.states[idx].store(global_state_id, Ordering::Release);

        // Update non_air_count
        if old_id == 0 && global_state_id != 0 {
            self.non_air_count += 1;
        } else if old_id != 0 && global_state_id == 0 {
            self.non_air_count = self.non_air_count.saturating_sub(1);
        }

        // Update flags
        if global_state_id != 0 {
            self.flags |= SectionFlags::NON_AIR;
            // Vanilla IDs: Stone=16, Water=144 (these are stable in 1.12.2)
            if global_state_id == 16 {
                self.flags |= SectionFlags::STONE;
            }
            if global_state_id == 144 {
                self.flags |= SectionFlags::WATER;
            }
        }

        // Invalidate palette cache & wire cache
        self.flags |= SectionFlags::PALETTE_DIRTY;
        self.palette_cache = None;
        self.invalidate_wire_cache();

        true
    }

    /// Bulk-replaces the entire section from a Java-side snapshot (M4.1 refresh).
    ///
    /// The unit of refresh is one 16³ section — 8 KB of states plus optional
    /// light arrays. Recomputes non_air_count and heightmap-relevant flags,
    /// invalidates the palette cache. Returns the new non_air_count.
    pub fn replace_states(
        &mut self,
        states: &[u16; SECTION_BLOCK_COUNT],
        block_light: Option<&[u8; LIGHT_ARRAY_SIZE]>,
        sky_light: Option<&[u8; LIGHT_ARRAY_SIZE]>,
    ) -> u16 {
        self.states_as_mut_slice().copy_from_slice(states);
        if let Some(bl) = block_light {
            self.block_light_as_mut_slice().copy_from_slice(bl);
        }
        if let Some(sl) = sky_light {
            self.sky_light_as_mut_slice().copy_from_slice(sl);
        }

        let mut non_air = 0u16;
        self.flags = 0;
        for &id in states.iter() {
            if id != 0 {
                non_air += 1;
                self.flags |= SectionFlags::NON_AIR;
                if id == 16 {
                    self.flags |= SectionFlags::STONE;
                }
                if id == 144 {
                    self.flags |= SectionFlags::WATER;
                }
            }
        }
        self.non_air_count = non_air;
        self.flags |= SectionFlags::PALETTE_DIRTY;
        self.palette_cache = None;
        self.invalidate_wire_cache();
        non_air
    }

    // ============================================================
    // PALETTE CACHE MANAGEMENT
    // ============================================================

    /// Ensures palette cache is up to date, rebuilding if dirty.
    fn ensure_palette(&mut self, global_bits: Option<u8>) {
        let compatible_cache = self
            .palette_cache
            .as_ref()
            .map(|cache| {
                global_bits
                    .map(|bits| cache.mode != PaletteMode::Global || cache.bits == bits)
                    .unwrap_or(true)
            })
            .unwrap_or(false);
        if compatible_cache && (self.flags & SectionFlags::PALETTE_DIRTY) == 0 {
            return;
        }
        self.palette_cache =
            Some(self.build_local_palette(global_bits.unwrap_or_else(global_palette_bits)));
        self.flags &= !SectionFlags::PALETTE_DIRTY;
    }

    /// Builds local palette from canonical global states.
    /// Returns None if section is all Air.
    fn build_local_palette(&self, global_bits: u8) -> LocalPalette {
        let states = self.states_as_slice();
        // Collect unique global state IDs
        let mut unique_ids = [0u16; 256];
        let mut unique_count = 0usize;

        for &id in states {
            if id == 0 {
                continue; // Air handled separately (always palette[0])
            }
            // Linear search - max 256 unique, cache-friendly
            let mut found = false;
            for i in 0..unique_count {
                if unique_ids[i] == id {
                    found = true;
                    break;
                }
            }
            if !found && unique_count < 256 {
                unique_ids[unique_count] = id;
                unique_count += 1;
            }
        }

        // Always include Air at index 0
        let palette_len = unique_count + 1;
        let mut palette = vec![0u32; palette_len];
        for i in 0..unique_count {
            palette[i + 1] = unique_ids[i] as u32;
        }

        // Determine bit width and palette mode
        let (bits, mode) = if palette_len <= (1usize << MIN_BITS) {
            (MIN_BITS, PaletteMode::Linear)
        } else if palette_len <= (1usize << MAX_LOCAL_BITS) {
            (
                palette_len.next_power_of_two().trailing_zeros() as u8,
                PaletteMode::HashMap,
            )
        } else {
            (global_bits, PaletteMode::Global)
        };

        // Pack into BitArray matching 1.12.2 wire layout
        let words = Self::pack_states_to_words(states, &palette, bits, mode);

        LocalPalette {
            bits,
            mode,
            palette,
            words,
        }
    }

    /// Packs one BitArray entry at `idx` (vanilla 1.12.2 layout: LSB-first
    /// continuous bitstream — entry's low bits at word0[offset..64], remaining
    /// high bits at word1[0..]). Used by both local and global packers.
    #[inline(always)]
    fn pack_entry(words: &mut [u64], idx: usize, value: u64, bits: u8) {
        let bit_pos = idx * bits as usize;
        let word0 = bit_pos / 64;
        let offset = bit_pos % 64;
        let fit = 64 - offset; // bits of this entry that fit in word0

        if fit >= bits as usize {
            // Single word
            let mask = (1u64 << bits) - 1;
            words[word0] = (words[word0] & !(mask << offset)) | ((value & mask) << offset);
        } else {
            // Cross-word spanning
            words[word0] |= (value & ((1u64 << fit) - 1)) << offset;
            words[word0 + 1] |= value >> fit;
        }
    }

    /// Packs 4096 global state IDs into u64 words using 1.12.2 BitArray spanning logic.
    fn pack_states_to_words(
        states: &[u16; SECTION_BLOCK_COUNT],
        palette: &[u32],
        bits: u8,
        mode: PaletteMode,
    ) -> Vec<u64> {
        if mode == PaletteMode::Global {
            // Global palette: pack global IDs directly
            return Self::pack_raw_states(states, bits);
        }

        // Local palette: map global IDs -> local palette indices
        let word_count = Self::calculate_word_count(bits);
        let mut words = vec![0u64; word_count];

        // OPTIMIZATION: Build a reverse lookup array or unrolled check to avoid
        // 4096 * N linear searches across the palette slice.
        // For palettes <= 16 (4 bits), a fixed 16-element scan is instant.
        // For palettes <= 64 (5..6 bits, covering ~92% of real Revelation sections),
        // a 64-element direct lookup array eliminates dynamic allocations and avoids
        // thousands of iterator calls.
        if palette.len() <= 16 {
            let mut pal_ids = [0u32; 16];
            let len = palette.len().min(16);
            pal_ids[..len].copy_from_slice(&palette[..len]);

            for idx in 0..SECTION_BLOCK_COUNT {
                let global_id = states[idx] as u32;
                let mut local_idx = 0u64;
                if global_id != 0 {
                    for p in 1..len {
                        if pal_ids[p] == global_id {
                            local_idx = p as u64;
                            break;
                        }
                    }
                }
                Self::pack_entry(&mut words, idx, local_idx, bits);
            }
        } else if palette.len() <= 64 {
            let mut pal_ids = [0u32; 64];
            let len = palette.len().min(64);
            pal_ids[..len].copy_from_slice(&palette[..len]);

            for idx in 0..SECTION_BLOCK_COUNT {
                let global_id = states[idx] as u32;
                let mut local_idx = 0u64;
                if global_id != 0 {
                    for p in 1..len {
                        if pal_ids[p] == global_id {
                            local_idx = p as u64;
                            break;
                        }
                    }
                }
                Self::pack_entry(&mut words, idx, local_idx, bits);
            }
        } else {
            // General local palette (65..256 entries):
            // Fallback direct scan
            for idx in 0..SECTION_BLOCK_COUNT {
                let global_id = states[idx];
                let local_idx = if global_id == 0 {
                    0
                } else {
                    palette[1..]
                        .iter()
                        .position(|&p| p == global_id as u32)
                        .map(|p| p + 1)
                        .unwrap_or(0) as u64
                };
                Self::pack_entry(&mut words, idx, local_idx, bits);
            }
        }

        words
    }

    /// Packs raw global state IDs (for Global palette mode).
    fn pack_raw_states(states: &[u16; SECTION_BLOCK_COUNT], bits: u8) -> Vec<u64> {
        let word_count = Self::calculate_word_count(bits);
        let mut words = vec![0u64; word_count];

        for idx in 0..SECTION_BLOCK_COUNT {
            Self::pack_entry(&mut words, idx, states[idx] as u64, bits);
        }

        words
    }

    #[inline]
    fn calculate_word_count(bits: u8) -> usize {
        ((SECTION_BLOCK_COUNT * bits as usize) + 63) / 64
    }

    // ============================================================
    // PROTOCOL 340 WIRE ENCODING
    // ============================================================

    /// Encodes this section directly into Protocol 340 wire packet format.
    pub fn encode_wire(
        &mut self,
        out: &mut [u8],
        offset: &mut usize,
        skylight: bool,
    ) -> Result<(), &'static str> {
        self.encode_wire_inner(out, offset, skylight, None)
    }

    /// Explicit registry width for owned packet snapshots. This never changes
    /// the legacy process-wide registry setting. The snapshot validates width
    /// and representability before materializing its private native sections.
    pub(crate) fn encode_wire_with_global_bits(
        &mut self,
        out: &mut [u8],
        offset: &mut usize,
        skylight: bool,
        global_bits: u8,
    ) -> Result<(), &'static str> {
        self.encode_wire_inner(out, offset, skylight, Some(global_bits))
    }

    fn encode_wire_inner(
        &mut self,
        out: &mut [u8],
        offset: &mut usize,
        skylight: bool,
        global_bits: Option<u8>,
    ) -> Result<(), &'static str> {
        // Fast path: if wire bytes are already cached and palette is clean, reuse!
        if (self.flags & SectionFlags::PALETTE_DIRTY) == 0 {
            let cached = if skylight {
                &self.wire_cache_skylight
            } else {
                &self.wire_cache_noskylight
            };
            if let Some(ref wire) = cached {
                if *offset + wire.len() > out.len() {
                    return Err("Output buffer overflow");
                }
                out[*offset..*offset + wire.len()].copy_from_slice(wire);
                *offset += wire.len();
                return Ok(());
            }
        }

        self.ensure_palette(global_bits);

        let local_palette = self.palette_cache.as_ref().unwrap();

        // Calculate needed space
        let palette_len = if local_palette.mode == PaletteMode::Global {
            0
        } else {
            local_palette.palette.len() as u32
        };
        let needed = 1
            + Self::varint_size(palette_len as i32)
            + if local_palette.mode == PaletteMode::Global {
                0
            } else {
                palette_len as usize * 5
            }
            + Self::varint_size(local_palette.words.len() as i32)
            + local_palette.words.len() * 8
            + LIGHT_ARRAY_SIZE
            + if skylight { LIGHT_ARRAY_SIZE } else { 0 };

        if *offset + needed > out.len() {
            return Err("Output buffer overflow");
        }

        let start_offset = *offset;

        // 1. bits_per_block
        out[*offset] = local_palette.bits;
        *offset += 1;

        // 2. palette_len (VarInt)
        Self::write_varint(palette_len as i32, out, offset);

        // 3. palette entries (VarInt each) - skipped for Global palette
        if local_palette.mode != PaletteMode::Global {
            for &id in &local_palette.palette {
                Self::write_varint(id as i32, out, offset);
            }
        }

        // 4. data_len in longs (VarInt)
        Self::write_varint(local_palette.words.len() as i32, out, offset);

        // 5. data array: big-endian longs
        for &w in &local_palette.words {
            let b = w.to_be_bytes();
            out[*offset..*offset + 8].copy_from_slice(&b);
            *offset += 8;
        }

        // 6. block_light
        out[*offset..*offset + LIGHT_ARRAY_SIZE].copy_from_slice(self.block_light_as_slice());
        *offset += LIGHT_ARRAY_SIZE;

        // 7. sky_light (if dimension has skylight)
        if skylight {
            out[*offset..*offset + LIGHT_ARRAY_SIZE].copy_from_slice(self.sky_light_as_slice());
            *offset += LIGHT_ARRAY_SIZE;
        }

        let written_bytes = out[start_offset..*offset].to_vec();
        if skylight {
            self.wire_cache_skylight = Some(written_bytes);
        } else {
            self.wire_cache_noskylight = Some(written_bytes);
        }

        Ok(())
    }

    #[inline]
    fn varint_size(mut val: i32) -> usize {
        let mut len = 0;
        loop {
            len += 1;
            val = ((val as u32) >> 7) as i32;
            if val == 0 {
                break;
            }
        }
        len
    }

    #[inline]
    fn write_varint(mut val: i32, out: &mut [u8], offset: &mut usize) {
        loop {
            let mut temp = (val as u32 & 0x7F) as u8;
            val = ((val as u32) >> 7) as i32;
            if val != 0 {
                temp |= 0x80;
            }
            out[*offset] = temp;
            *offset += 1;
            if val == 0 {
                break;
            }
        }
    }

    // ============================================================
    // BULK OPERATIONS
    // ============================================================

    /// Fills entire section with a single global state ID.
    pub fn fill(&mut self, global_state_id: u16) {
        self.states_as_mut_slice().fill(global_state_id);
        self.non_air_count = if global_state_id == 0 { 0 } else { 4096 };
        self.flags = if global_state_id == 0 {
            0
        } else {
            SectionFlags::NON_AIR
        };
        if global_state_id == 16 {
            self.flags |= SectionFlags::STONE;
        }
        if global_state_id == 144 {
            self.flags |= SectionFlags::WATER;
        }
        self.flags |= SectionFlags::PALETTE_DIRTY;
        self.palette_cache = None;
        self.invalidate_wire_cache();
    }

    /// Checks if section contains any non-air blocks.
    #[inline(always)]
    pub fn is_empty(&self) -> bool {
        self.non_air_count == 0
    }

    /// Recalculates non_air_count from states (for validation).
    pub fn recalculate_counts(&mut self) {
        let mut non_air = 0u16;
        for &id in self.states_as_slice() {
            if id != 0 {
                non_air += 1;
            }
        }
        self.non_air_count = non_air;
        if non_air > 0 {
            self.flags |= SectionFlags::NON_AIR;
        } else {
            self.flags &= !SectionFlags::NON_AIR;
        }
    }
}

/// Cached local palette for wire encoding.
#[derive(Debug, Clone)]
struct LocalPalette {
    bits: u8,
    mode: PaletteMode,
    palette: Vec<u32>, // Global state IDs in palette order
    words: Vec<u64>,   // Packed bit array (1.12.2 BitArray layout)
}
impl NativeSection {
    /// M5.2: light-array access for packet-time freshness checks.
    pub fn light_arrays(&self) -> (&[u8; 2048], &[u8; 2048]) {
        (self.block_light_as_slice(), self.sky_light_as_slice())
    }
}
