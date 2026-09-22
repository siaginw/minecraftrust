//! JNI bridge for M4 NativeChunk state, lifecycle, and zero-copy consumers.

use std::ffi::c_void;
use std::panic::catch_unwind;
use std::sync::OnceLock;
use native_chunk::{
    NativeChunk, ChunkRegistry, ChunkHandle, ChunkKey,
    CHUNK_PRIMER_SIZE, BIOME_ARRAY_SIZE,
};
use metrics::GLOBAL_FFI_METRICS;

static GLOBAL_REGISTRY: OnceLock<ChunkRegistry> = OnceLock::new();

pub fn get_registry() -> &'static ChunkRegistry {
    GLOBAL_REGISTRY.get_or_init(ChunkRegistry::new)
}

/// Registers a newly generated ChunkPrimer into persistent NativeChunk state.
///
/// Returns generation_id (>0), or 0 on error.
#[no_mangle]
pub unsafe extern "system" fn Java_com_rustcraft_bridge_NativeChunkBridge_registerPrimer(
    _env: *mut c_void,
    _clazz: *mut c_void,
    dim: i32,
    cx: i32,
    cz: i32,
    primer_addr: i64,
    biome_addr: i64,
) -> i64 {
    GLOBAL_FFI_METRICS.record_call(300);
    catch_unwind(|| {
        if primer_addr == 0 { return 0i64; }
        let primer = &*(primer_addr as *const [u16; CHUNK_PRIMER_SIZE]);
        let biomes: &[u8; BIOME_ARRAY_SIZE] = if biome_addr != 0 {
            &*(biome_addr as *const [u8; BIOME_ARRAY_SIZE])
        } else {
            &[0u8; BIOME_ARRAY_SIZE]
        };

        let reg = get_registry();
        let gen_id = reg.next_generation_id();
        let chunk = NativeChunk::from_primer(dim, cx, cz, primer, biomes, gen_id);
        let handle = reg.insert(chunk);
        handle.generation_id as i64
    }).unwrap_or(0)
}

/// Reverse materializes a NativeChunk into a Java ChunkPrimer DirectBuffer.
///
/// Returns 0 on success, or error code <0.
#[no_mangle]
pub unsafe extern "system" fn Java_com_rustcraft_bridge_NativeChunkBridge_materializePrimer(
    _env: *mut c_void,
    _clazz: *mut c_void,
    dim: i32,
    cx: i32,
    cz: i32,
    generation_id: i64,
    primer_out_addr: i64,
) -> i32 {
    GLOBAL_FFI_METRICS.record_call(301);
    catch_unwind(|| {
        if primer_out_addr == 0 { return -1; }
        let primer = &mut *(primer_out_addr as *mut [u16; CHUNK_PRIMER_SIZE]);
        let reg = get_registry();
        let handle = ChunkHandle {
            key: ChunkKey::new(dim, cx, cz),
            generation_id: generation_id as u64,
        };

        if let Some(chunk_arc) = reg.get(&handle) {
            let chunk = chunk_arc.read().unwrap();
            chunk.to_primer(primer);
            0
        } else {
            -2 // Not found or invalidated
        }
    }).unwrap_or(-99)
}

/// Retrieves the primary bit mask (bitmask of populated 16-block sections).
#[no_mangle]
pub unsafe extern "system" fn Java_com_rustcraft_bridge_NativeChunkBridge_getPrimaryBitMask(
    _env: *mut c_void,
    _clazz: *mut c_void,
    dim: i32,
    cx: i32,
    cz: i32,
    generation_id: i64,
) -> i32 {
    GLOBAL_FFI_METRICS.record_call(302);
    catch_unwind(|| {
        let reg = get_registry();
        let handle = ChunkHandle {
            key: ChunkKey::new(dim, cx, cz),
            generation_id: generation_id as u64,
        };
        if let Some(chunk_arc) = reg.get(&handle) {
            let chunk = chunk_arc.read().unwrap();
            chunk.primary_bit_mask as i32
        } else {
            -1
        }
    }).unwrap_or(-99)
}

/// First Zero-Copy Consumer: Directly encodes Protocol 340 SPacketChunkData payload from NativeChunk.
///
/// Eliminates Java ExtendedBlockStorage extraction, reflection, and staging buffers.
/// Returns bytes written (>0) on success, or negative error code.
#[no_mangle]
pub unsafe extern "system" fn Java_com_rustcraft_bridge_NativeChunkBridge_encodePacketPayload(
    _env: *mut c_void,
    _clazz: *mut c_void,
    dim: i32,
    cx: i32,
    cz: i32,
    generation_id: i64,
    skylight: u8,
    full_chunk: u8,
    output_buf_address: i64,
    output_buf_capacity: i32,
) -> i32 {
    GLOBAL_FFI_METRICS.record_call(303);
    catch_unwind(|| {
        if output_buf_address == 0 || output_buf_capacity <= 0 { return -1; }
        let out = std::slice::from_raw_parts_mut(output_buf_address as *mut u8, output_buf_capacity as usize);
        let reg = get_registry();
        let handle = ChunkHandle {
            key: ChunkKey::new(dim, cx, cz),
            generation_id: generation_id as u64,
        };

        if let Some(chunk_arc) = reg.get(&handle) {
            let mut chunk = chunk_arc.write().unwrap();
            let mut offset = 0usize;
            match chunk.encode_packet_payload(skylight != 0, full_chunk != 0, out, &mut offset) {
                Ok(len) => len as i32,
                Err(_) => -3, // Overflow or encode error
            }
        } else {
            -2 // Stale or missing
        }
    }).unwrap_or(-99)
}

/// Second Consumer Proof A: Section occupancy summary & spatial block count.
///
/// Writes total non-air block count to out_count_addr, returns primary_bit_mask.
#[no_mangle]
pub unsafe extern "system" fn Java_com_rustcraft_bridge_NativeChunkBridge_getOccupancySummary(
    _env: *mut c_void,
    _clazz: *mut c_void,
    dim: i32,
    cx: i32,
    cz: i32,
    generation_id: i64,
    out_count_addr: i64,
) -> i32 {
    GLOBAL_FFI_METRICS.record_call(304);
    catch_unwind(|| {
        let reg = get_registry();
        let handle = ChunkHandle {
            key: ChunkKey::new(dim, cx, cz),
            generation_id: generation_id as u64,
        };
        if let Some(chunk_arc) = reg.get(&handle) {
            let mut chunk = chunk_arc.write().unwrap();
            let (mask, total_blocks) = chunk.occupancy_summary();
            if out_count_addr != 0 {
                *(out_count_addr as *mut i32) = total_blocks as i32;
            }
            mask as i32
        } else {
            -1
        }
    }).unwrap_or(-99)
}

/// Second Consumer Proof B: Stages raw chunk blocks for MCA / NBT persistence.
///
/// Returns bytes written on success, or negative error code.
#[no_mangle]
pub unsafe extern "system" fn Java_com_rustcraft_bridge_NativeChunkBridge_stagePersistence(
    _env: *mut c_void,
    _clazz: *mut c_void,
    dim: i32,
    cx: i32,
    cz: i32,
    generation_id: i64,
    output_buf_address: i64,
    output_buf_capacity: i32,
) -> i32 {
    GLOBAL_FFI_METRICS.record_call(305);
    catch_unwind(|| {
        if output_buf_address == 0 || output_buf_capacity <= 0 { return -1; }
        let out = std::slice::from_raw_parts_mut(output_buf_address as *mut u8, output_buf_capacity as usize);
        let reg = get_registry();
        let handle = ChunkHandle {
            key: ChunkKey::new(dim, cx, cz),
            generation_id: generation_id as u64,
        };

        if let Some(chunk_arc) = reg.get(&handle) {
            let mut chunk = chunk_arc.write().unwrap();
            let mut offset = 0usize;
            match chunk.stage_persistence(out, &mut offset) {
                Ok(len) => len as i32,
                Err(_) => -3,
            }
        } else {
            -2
        }
    }).unwrap_or(-99)
}

/// Marks a chunk as invalidated when Java or a mod mutates its blocks.
#[no_mangle]
pub unsafe extern "system" fn Java_com_rustcraft_bridge_NativeChunkBridge_invalidateChunk(
    _env: *mut c_void,
    _clazz: *mut c_void,
    dim: i32,
    cx: i32,
    cz: i32,
) -> i32 {
    GLOBAL_FFI_METRICS.record_call(306);
    catch_unwind(|| {
        let reg = get_registry();
        if reg.invalidate(ChunkKey::new(dim, cx, cz)) { 1 } else { 0 }
    }).unwrap_or(0)
}

/// Unloads a chunk from native memory when the chunk is unloaded by the server.
#[no_mangle]
pub unsafe extern "system" fn Java_com_rustcraft_bridge_NativeChunkBridge_unloadChunk(
    _env: *mut c_void,
    _clazz: *mut c_void,
    dim: i32,
    cx: i32,
    cz: i32,
) -> i32 {
    GLOBAL_FFI_METRICS.record_call(307);
    catch_unwind(|| {
        let reg = get_registry();
        if reg.remove(ChunkKey::new(dim, cx, cz)).is_some() { 1 } else { 0 }
    }).unwrap_or(0)
}

/// Marks native chunk as mutated (Java/mod changed block state, light, biomes, etc.).
/// Increments mutation_generation and transitions lifecycle to Dirty if ActiveNative.
#[no_mangle]
pub unsafe extern "system" fn Java_com_rustcraft_bridge_NativeChunkBridge_markMutation(
    _env: *mut c_void,
    _clazz: *mut c_void,
    dim: i32,
    cx: i32,
    cz: i32,
) -> i32 {
    GLOBAL_FFI_METRICS.record_call(309);
    catch_unwind(|| {
        let reg = get_registry();
        if reg.mark_mutation(ChunkKey::new(dim, cx, cz)) { 1 } else { 0 }
    }).unwrap_or(0)
}

/// Section-granular dirty mark (M4.1 refresh model). Sets bit (sectionY) in the
/// chunk's dirty mask, bumps mutation_generation. Cheap: safe to call from a
/// Chunk.setBlockState hook — population bursts collapse into a mask.
/// Returns the new dirty mask (>=0), or -1 if chunk not registered.
#[no_mangle]
pub unsafe extern "system" fn Java_com_rustcraft_bridge_NativeChunkBridge_markSectionMutation(
    _env: *mut c_void,
    _clazz: *mut c_void,
    dim: i32,
    cx: i32,
    cz: i32,
    section_y: u8,
) -> i32 {
    GLOBAL_FFI_METRICS.record_call(310);
    catch_unwind(|| {
        let reg = get_registry();
        match reg.mark_section_mutation(ChunkKey::new(dim, cx, cz), section_y) {
            Some(mask) => mask as i32,
            None => -1,
        }
    }).unwrap_or(-99)
}

/// Refreshes one section in place from Java-side snapshot buffers (12 KB:
/// 8 KB u16 states + optional 2 KB block light + 2 KB sky light).
/// Clears the section's dirty bit; returns remaining dirty mask, or -1.
#[no_mangle]
pub unsafe extern "system" fn Java_com_rustcraft_bridge_NativeChunkBridge_refreshSection(
    _env: *mut c_void,
    _clazz: *mut c_void,
    dim: i32,
    cx: i32,
    cz: i32,
    section_y: u8,
    states_addr: i64,
    block_light_addr: i64,
    sky_light_addr: i64,
) -> i32 {
    GLOBAL_FFI_METRICS.record_call(311);
    catch_unwind(|| {
        if states_addr == 0 { return -2; }
        let states = &*(states_addr as *const [u16; 4096]);
        let block_light: Option<&[u8; 2048]> = if block_light_addr != 0 {
            Some(&*(block_light_addr as *const [u8; 2048]))
        } else { None };
        let sky_light: Option<&[u8; 2048]> = if sky_light_addr != 0 {
            Some(&*(sky_light_addr as *const [u8; 2048]))
        } else { None };
        let reg = get_registry();
        match reg.refresh_section(ChunkKey::new(dim, cx, cz), section_y, states, block_light, sky_light) {
            Some(mask) => mask as i32,
            None => -1,
        }
    }).unwrap_or(-99)
}

/// Sets the global-palette bit width from the live block-state registry size
/// (vanilla: ceil(log2(Block.BLOCK_STATE_IDS.size())); mods grow the registry).
/// Must be called at boot before any section with >256 states is encoded.
#[no_mangle]
pub unsafe extern "system" fn Java_com_rustcraft_bridge_NativeChunkBridge_setGlobalPaletteBits(
    _env: *mut c_void,
    _clazz: *mut c_void,
    bits: u8,
) -> i32 {
    GLOBAL_FFI_METRICS.record_call(313);
    catch_unwind(|| {
        native_chunk::section::set_global_palette_bits(bits);
        native_chunk::section::global_palette_bits() as i32
    }).unwrap_or(-99)
}

/// Returns the chunk's current per-section dirty mask (bit y = section y stale),
/// or -1 if not registered. Consumers call this before encoding to decide
/// which sections to push via refreshSection.
#[no_mangle]
pub unsafe extern "system" fn Java_com_rustcraft_bridge_NativeChunkBridge_getDirtySections(
    _env: *mut c_void,
    _clazz: *mut c_void,
    dim: i32,
    cx: i32,
    cz: i32,
) -> i32 {
    GLOBAL_FFI_METRICS.record_call(312);
    catch_unwind(|| {
        let reg = get_registry();
        match reg.dirty_mask(ChunkKey::new(dim, cx, cz)) {
            Some(mask) => mask as i32,
            None => -1,
        }
    }).unwrap_or(-99)
}

/// Returns current count of registered chunks in native memory.
#[no_mangle]
pub unsafe extern "system" fn Java_com_rustcraft_bridge_NativeChunkBridge_getRegisteredCount(
    _env: *mut c_void,
    _clazz: *mut c_void,
) -> i32 {
    GLOBAL_FFI_METRICS.record_call(308);
    catch_unwind(|| {
        get_registry().count() as i32
    }).unwrap_or(0)
}

/// Current generation id for a live chunk at (dim, cx, cz), or 0 when absent
/// or invalidated. Consumer entry point for mutation-tracked refresh (M4.2A C2).
#[no_mangle]
pub unsafe extern "system" fn Java_com_rustcraft_bridge_NativeChunkBridge_findGeneration(
    _env: *mut c_void,
    _clazz: *mut c_void,
    dim: i32,
    cx: i32,
    cz: i32,
) -> i64 {
    GLOBAL_FFI_METRICS.record_call(314);
    catch_unwind(|| {
        let reg = get_registry();
        reg.find_generation(ChunkKey::new(dim, cx, cz)) as i64
    }).unwrap_or(0)
}

/// Writes 8 x i64 retention/allocation stats to out_addr:
/// [0] current chunks, [1] current sections, [2] retained bytes estimate,
/// [3] sections allocated (cumulative), [4] sections released (cumulative),
/// [5] chunks evicted (cumulative), [6] reserved, [7] reserved.
#[no_mangle]
pub unsafe extern "system" fn Java_com_rustcraft_bridge_NativeChunkBridge_getRegistryStats(
    _env: *mut c_void,
    _clazz: *mut c_void,
    out_addr: i64,
) -> i32 {
    GLOBAL_FFI_METRICS.record_call(315);
    catch_unwind(|| {
        if out_addr == 0 { return -1; }
        let out = std::slice::from_raw_parts_mut(out_addr as *mut i64, 8);
        let reg = get_registry();
        let (chunks, sections, bytes) = reg.retention();
        out[0] = chunks as i64;
        out[1] = sections as i64;
        out[2] = bytes as i64;
        out[3] = native_chunk::registry::STATS_SECTIONS_ALLOCATED.load(std::sync::atomic::Ordering::Relaxed) as i64;
        out[4] = native_chunk::registry::STATS_SECTIONS_RELEASED.load(std::sync::atomic::Ordering::Relaxed) as i64;
        out[5] = native_chunk::registry::STATS_CHUNKS_EVICTED.load(std::sync::atomic::Ordering::Relaxed) as i64;
        out[6] = 0;
        out[7] = 0;
        1
    }).unwrap_or(-99)
}

/// Replaces the chunk's 256-byte biome array (M4.2B: live registration happens
/// with zero biomes; the flush/comparator path pushes the real array before
/// any consumer read — biomes are separately tracked from sections).
#[no_mangle]
pub unsafe extern "system" fn Java_com_rustcraft_bridge_NativeChunkBridge_setBiomes(
    _env: *mut c_void,
    _clazz: *mut c_void,
    dim: i32,
    cx: i32,
    cz: i32,
    biome_addr: i64,
) -> i32 {
    GLOBAL_FFI_METRICS.record_call(316);
    catch_unwind(|| {
        if biome_addr == 0 { return -1; }
        let biomes = &*(biome_addr as *const [u8; 256]);
        let reg = get_registry();
        let key = ChunkKey::new(dim, cx, cz);
        let map = reg.chunks_map().read().unwrap();
        if let Some(arc) = map.get(&key) {
            let mut chunk = arc.write().unwrap();
            chunk.biomes.copy_from_slice(biomes);
            chunk.mark_mutation();
            1
        } else {
            0
        }
    }).unwrap_or(-99)
}

/// Writes 4 x i64 snapshot diagnostics for (dim,cx,cz):
/// [0] generation_id (0 if absent/invalidated), [1] mutation_generation,
/// [2] snapshot_generation, [3] dirty_sections mask.
/// M4.2C: captured in every mismatch dump.
#[no_mangle]
pub unsafe extern "system" fn Java_com_rustcraft_bridge_NativeChunkBridge_getGenerationInfo(
    _env: *mut c_void,
    _clazz: *mut c_void,
    dim: i32,
    cx: i32,
    cz: i32,
    out_addr: i64,
) -> i32 {
    GLOBAL_FFI_METRICS.record_call(317);
    catch_unwind(|| {
        if out_addr == 0 { return -1; }
        let out = std::slice::from_raw_parts_mut(out_addr as *mut i64, 4);
        let reg = get_registry();
        let key = ChunkKey::new(dim, cx, cz);
        let map = reg.chunks_map().read().unwrap();
        match map.get(&key) {
            Some(arc) => {
                let c = arc.read().unwrap();
                out[0] = c.generation_id as i64;
                out[1] = c.mutation_generation as i64;
                out[2] = c.snapshot_generation as i64;
                out[3] = c.dirty_sections as i64;
                1
            }
            None => { out[0] = 0; out[1] = 0; out[2] = 0; out[3] = 0; 0 }
        }
    }).unwrap_or(-99)
}

/// In-JVM lifecycle cleanup (M4.2D): drains the registry with release
/// accounting. Returns chunks removed; getRegistryStats reflects it after.
#[no_mangle]
pub unsafe extern "system" fn Java_com_rustcraft_bridge_NativeChunkBridge_registryClear(
    _env: *mut c_void,
    _clazz: *mut c_void,
) -> i32 {
    GLOBAL_FFI_METRICS.record_call(318);
    catch_unwind(|| {
        let reg = get_registry();
        let n = reg.count() as i32;
        reg.clear();
        n
    }).unwrap_or(-99)
}

/// Reads the chunk's current 256-byte biome array (M4.3C: packet-side freshness
/// check against direct array writes vanilla makes without any hook).
#[no_mangle]
pub unsafe extern "system" fn Java_com_rustcraft_bridge_NativeChunkBridge_getBiomes(
    _env: *mut c_void,
    _clazz: *mut c_void,
    dim: i32,
    cx: i32,
    cz: i32,
    out_addr: i64,
) -> i32 {
    GLOBAL_FFI_METRICS.record_call(319);
    catch_unwind(|| {
        if out_addr == 0 { return -1; }
        let out = std::slice::from_raw_parts_mut(out_addr as *mut u8, 256);
        let reg = get_registry();
        let key = ChunkKey::new(dim, cx, cz);
        let map = reg.chunks_map().read().unwrap();
        match map.get(&key) {
            Some(arc) => {
                let chunk = arc.read().unwrap();
                out.copy_from_slice(&chunk.biomes);
                1
            }
            None => 0,
        }
    }).unwrap_or(-99)
}

/// M5.2 light freshness: writes 4096 bytes (2048 block + 2048 sky) of section y
/// to out_addr. Returns 1 on success, 0 if chunk/section absent.
#[no_mangle]
pub unsafe extern "system" fn Java_com_rustcraft_bridge_NativeChunkBridge_getSectionLight(
    _env: *mut c_void,
    _clazz: *mut c_void,
    dim: i32,
    cx: i32,
    cz: i32,
    section_y: u8,
    out_addr: i64,
) -> i32 {
    GLOBAL_FFI_METRICS.record_call(320);
    catch_unwind(|| {
        if out_addr == 0 { return -1; }
        let out = std::slice::from_raw_parts_mut(out_addr as *mut u8, 4096);
        let reg = get_registry();
        let key = ChunkKey::new(dim, cx, cz);
        let map = reg.chunks_map().read().unwrap();
        match map.get(&key) {
            Some(arc) => {
                let chunk = arc.read().unwrap();
                match chunk.sections[(section_y & 15) as usize] {
                    Some(ref sec) => {
                        let (bl, sl) = sec.light_arrays();
                        out[..2048].copy_from_slice(bl);
                        out[2048..].copy_from_slice(sl);
                        1
                    }
                    None => 0,
                }
            }
            None => 0,
        }
    }).unwrap_or(-99)
}
