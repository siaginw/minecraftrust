//! M4.1 Task 10 — memory model of the generalized NativeChunk state.
//!
//! Prints exact layout sizes and derives per-chunk / per-loaded-radius totals
//! for the canonical-u16 engine representation, with and without cached wire
//! palettes, against the vanilla Java reference layout.

use native_chunk::{NativeChunk, NativeSection, CHUNK_PRIMER_SIZE, BIOME_ARRAY_SIZE};

fn main() {
    println!("=== M4.1 MEMORY MODEL (generalized NativeSection) ===\n");

    let sec_size = std::mem::size_of::<NativeSection>();
    let chunk_size = std::mem::size_of::<NativeChunk>();
    println!("size_of::<NativeSection>()      = {} B (align {})", sec_size, std::mem::align_of::<NativeSection>());
    println!("  states u16[4096]              = {} B", 4096 * 2);
    println!("  block_light + sky_light       = {} B", 2 * 2048);
    println!("  palette_cache (Option ptr)    = {} B", std::mem::size_of::<Option<&u8>>());
    println!("  counters/flags/pad            = {} B", 6 + 2 + 4); // non_air+flags+y+pad est
    println!("size_of::<NativeChunk>()        = {} B", chunk_size);
    println!("  sections sparse [Option<Box>;16] = {} B", 16 * 8);
    println!("  biomes                        = {} B", BIOME_ARRAY_SIZE);
    println!("  heightMap u16[256]            = {} B", 256 * 2);
    println!("  registry-value fields         = rest\n");

    // Live-derived: build typical chunks and inspect
    let mut primer = [0u16; CHUNK_PRIMER_SIZE];
    for x in 0..16 {
        for z in 0..16 {
            let col = (x << 12) | (z << 8);
            primer[col] = 7;
            for y in 1..=63 { primer[col | y] = 1; }
            for y in 64..=67 { primer[col | y] = 3; }
            primer[col | 68] = 2;
        }
    }
    let biomes = [0u8; BIOME_ARRAY_SIZE];
    let chunk = NativeChunk::from_primer(0, 0, 0, &primer, &biomes, 1);
    let active = chunk.primary_bit_mask.count_ones();
    println!("typical overworld chunk: {} active sections, mask={:#06x}", active, chunk.primary_bit_mask);

    // Per-section resident cost (heap): Box<NativeSection> allocation = size + allocator slack (est 16B windows)
    let box_overhead = 16usize;
    let per_section_base = sec_size + box_overhead;

    // Wire palette cache costs by mode (derived, matches build_local_palette):
    //   words = ceil(4096*bits/64) u64s; palette = Vec<u32> of N entries (cap est N+1) + Vec headers est 48B
    let modes: &[(&str, u32, usize)] = &[
        ("linear 4-bit (<=16 states)", 4, 16),
        ("hashmap 5-bit (<=32)", 5, 32),
        ("hashmap 6-bit (<=64)", 6, 64),
        ("hashmap 8-bit (<=256)", 8, 256),
        ("global 13-bit (>256)", 13, 0),
    ];
    println!("\nper-section resident cost (engine base {} B + palette cache):", per_section_base);
    for (name, bits, n) in modes {
        let bits_u = *bits as usize;
        let words_bytes = ((4096usize * bits_u + 63) / 64) * 8;
        let palette_vec = if *n == 0 { 0 } else { (n + 1) * 4 + 48 };
        let cache = words_bytes + palette_vec;
        println!("  {:<28} +{:5} B cache -> {:6} B/section", name, cache, per_section_base + cache);
    }

    let typical_sections = 5usize;
    let per_chunk_base = chunk_size + typical_sections * per_section_base;
    println!("\nper-chunk ({} active sections, no palette cache): {} B = {:.1} KB", typical_sections, per_chunk_base, per_chunk_base as f64 / 1024.0);
    let per_chunk_cached4 = chunk_size + typical_sections * (per_section_base + 2048 + 17 * 4 + 48);
    println!("per-chunk (typical 4-bit palettes cached):        {} B = {:.1} KB", per_chunk_cached4, per_chunk_cached4 as f64 / 1024.0);

    for radius in [10usize, 20, 32] {
        let n = (2 * radius + 1) * (2 * radius + 1);
        println!("loaded radius {} ({} chunks): {:.1} MB base / {:.1} MB cached",
            radius, n, n as f64 * per_chunk_base as f64 / 1048576.0, n as f64 * per_chunk_cached4 as f64 / 1048576.0);
    }

    // Smoke-test observation: 2,938 chunks retained post-run on Target A
    let smoke = 2938usize;
    println!("\nTarget A smoke run retained {} chunks -> {:.1} MB base / {:.1} MB cached",
        smoke, smoke as f64 * per_chunk_base as f64 / 1048576.0, smoke as f64 * per_chunk_cached4 as f64 / 1048576.0);

    // Java reference (computed layout, vanilla 1.12.2):
    // ExtendedBlockStorage = BlockStateContainer(BitArray long[] + palette refs) + 2 NibbleArray(byte[2048]+hdr) + counters
    //   BitArray 4-bit: 256 longs = 2048 B; palette linear: IBlockState[cap16] refs 64 B + hdr 16
    //   NibbleArray: 2048 + 16 hdr each, x2 = 4128
    //   object headers/fields ~= 48
    //   JVM allocator granularity/loss ~ 10-15%
    let java_sec_4bit = 2048 + 64 + 16 + 4128 + 48;
    println!("\nJava reference (computed, vanilla 4-bit section): ~{} B = {:.1} KB", java_sec_4bit, java_sec_4bit as f64 / 1024.0);
    println!("  native u16 canonical trades +{:.1} KB/section over packed BitArray for O(1) access + zero unpack;",
        (per_section_base as f64 - java_sec_4bit as f64) / 1024.0);
    println!("  sparse Option<Box> means 67% of a 5/16-section column is a null pointer (8 B).");
    println!("  u16 cap 65,535 states: inherent 1.12.2 ChunkPrimer char[] limit; observed modpack registries stay well below.");
}
