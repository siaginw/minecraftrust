//! JNI bridge for M4 NativeChunk state, lifecycle, and zero-copy consumers.

use metrics::GLOBAL_FFI_METRICS;
use native_chunk::{
    ChunkHandle, ChunkKey, ChunkRegistry, NativeChunk, BIOME_ARRAY_SIZE, CHUNK_PRIMER_SIZE,
};
use std::ffi::c_void;
use std::panic::catch_unwind;
use std::sync::OnceLock;

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
    let mut call = GLOBAL_FFI_METRICS.begin_call(metrics::Operation::ChunkRegisterPrimer);
    call.bytes = metrics::ByteMeasurements::NO_BULK;
    let outcome = catch_unwind(std::panic::AssertUnwindSafe(|| {
        if primer_addr == 0 {
            return 0i64;
        }
        let primer = &*(primer_addr as *const [u16; CHUNK_PRIMER_SIZE]);
        let biomes: &[u8; BIOME_ARRAY_SIZE] = if biome_addr != 0 {
            &*(biome_addr as *const [u8; BIOME_ARRAY_SIZE])
        } else {
            &[0u8; BIOME_ARRAY_SIZE]
        };

        call.bytes.input_bytes = Some(
            (CHUNK_PRIMER_SIZE * 2 + if biome_addr != 0 { BIOME_ARRAY_SIZE } else { 0 }) as u64,
        );
        call.bytes.borrowed_bytes = call.bytes.input_bytes;
        call.bytes.copied_bytes = None; // Native section construction owns its copies.
        let reg = get_registry();
        let gen_id = reg.next_generation_id();
        let chunk = NativeChunk::from_primer(dim, cx, cz, primer, biomes, gen_id);
        let handle = reg.insert(chunk);
        handle.generation_id as i64
    }));
    let panicked = outcome.is_err();
    let code = outcome.unwrap_or(0);
    call.fallback_reason = if panicked {
        metrics::FallbackReason::Panic
    } else {
        if code > 0 {
            metrics::FallbackReason::None
        } else {
            metrics::FallbackReason::InvalidArgument
        }
    };
    if panicked {
        call.bytes.output_bytes = Some(0);
        call.bytes.copied_bytes = None;
    }
    code
}

/// Seeds persistent NativeChunk state from RCSNAP01 or RCSNAP02 transport bytes.
///
/// Returns generation_id (>0) on success, or negative error code on failure.
/// If a chunk already exists at those coordinates, it is replaced and a new generation_id is assigned.
#[no_mangle]
pub unsafe extern "system" fn Java_com_rustcraft_bridge_NativeChunkBridge_seedFromTransport(
    _env: *mut c_void,
    _clazz: *mut c_void,
    transport_addr: i64,
    transport_len: i32,
) -> i64 {
    let mut call = GLOBAL_FFI_METRICS.begin_call(metrics::Operation::ChunkSeedFromTransport);
    call.bytes = metrics::ByteMeasurements::NO_BULK;
    let outcome = catch_unwind(std::panic::AssertUnwindSafe(|| {
        if transport_addr == 0
            || transport_len <= 0
            || transport_len as usize > native_chunk::packet_snapshot::MAX_SNAPSHOT_BYTES
        {
            return -1i64;
        }
        let bytes = std::slice::from_raw_parts(transport_addr as *const u8, transport_len as usize);
        call.bytes.input_bytes = Some(bytes.len() as u64);
        call.bytes.borrowed_bytes = call.bytes.input_bytes;

        let reg = get_registry();
        let gen_id = reg.next_generation_id();
        let mut chunk = match NativeChunk::from_transport(bytes) {
            Ok(c) => c,
            Err(_) => return -2i64,
        };
        chunk.generation_id = gen_id;
        let handle = reg.insert(chunk);
        handle.generation_id as i64
    }));
    let panicked = outcome.is_err();
    let code = outcome.unwrap_or(-7);
    call.fallback_reason = if panicked {
        metrics::FallbackReason::Panic
    } else if code > 0 {
        metrics::FallbackReason::None
    } else {
        metrics::FallbackReason::InvalidArgument
    };
    if panicked {
        call.bytes.output_bytes = Some(0);
        call.bytes.copied_bytes = None;
    }
    code
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
    let mut call = GLOBAL_FFI_METRICS.begin_call(metrics::Operation::ChunkMaterializePrimer);
    call.bytes = metrics::ByteMeasurements::NO_BULK;
    let outcome = catch_unwind(std::panic::AssertUnwindSafe(|| {
        if primer_out_addr == 0 {
            return -1;
        }
        let primer = &mut *(primer_out_addr as *mut [u16; CHUNK_PRIMER_SIZE]);
        call.bytes.borrowed_bytes = Some((CHUNK_PRIMER_SIZE * 2) as u64);
        let reg = get_registry();
        let handle = ChunkHandle {
            key: ChunkKey::new(dim, cx, cz),
            generation_id: generation_id as u64,
        };

        if let Some(chunk_arc) = reg.get(&handle) {
            let chunk = chunk_arc.read().unwrap();
            call.bytes.copied_bytes = None;
            chunk.to_primer(primer);
            call.bytes.output_bytes = Some((CHUNK_PRIMER_SIZE * 2) as u64);
            0
        } else {
            -2 // Not found or invalidated
        }
    }));
    let panicked = outcome.is_err();
    let code = outcome.unwrap_or(-99);
    call.fallback_reason = if panicked {
        metrics::FallbackReason::Panic
    } else {
        match code {
            -99 => metrics::FallbackReason::Panic,
            -1 => metrics::FallbackReason::InvalidArgument,
            0.. => metrics::FallbackReason::None,
            _ => metrics::FallbackReason::MissingState,
        }
    };
    if panicked {
        call.bytes.output_bytes = Some(0);
        call.bytes.copied_bytes = None;
    }
    code
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
    let mut call = GLOBAL_FFI_METRICS.begin_call(metrics::Operation::ChunkGetPrimaryBitMask);
    call.bytes = metrics::ByteMeasurements::NO_BULK;
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
    })
    .unwrap_or(-99)
}

/// First Zero-Copy Consumer: Directly encodes Protocol 340 SPacketChunkData payload from NativeChunk.
///
/// Eliminates Java ExtendedBlockStorage extraction, reflection, and staging buffers.
/// Returns bytes written (zero for an empty non-full payload), or a negative error code.
///
/// Legacy length-only ABI: this does not expose PacketEncodeResult.emitted_mask.
/// A separate mask query is NOT metadata for this encode result. A versioned
/// combined-result JNI contract and caller migration are required before native
/// packet authority can be reconsidered; the production CAPTURE_UNSAFE gate stays
/// closed. See docs/research/issue1-structural-mask-contract.md.
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
    let mut call = GLOBAL_FFI_METRICS.begin_call(metrics::Operation::ChunkEncodePacketPayload);
    call.bytes = metrics::ByteMeasurements::NO_BULK;
    let outcome = catch_unwind(std::panic::AssertUnwindSafe(|| {
        if output_buf_address == 0 || output_buf_capacity <= 0 {
            return -1;
        }
        let out = std::slice::from_raw_parts_mut(
            output_buf_address as *mut u8,
            output_buf_capacity as usize,
        );
        call.bytes.borrowed_bytes = Some(out.len() as u64);
        let reg = get_registry();
        let handle = ChunkHandle {
            key: ChunkKey::new(dim, cx, cz),
            generation_id: generation_id as u64,
        };

        if let Some(chunk_arc) = reg.get(&handle) {
            let mut chunk = chunk_arc.write().unwrap();
            call.bytes.copied_bytes = None;
            let mut offset = 0usize;
            match chunk.encode_packet_payload(skylight != 0, full_chunk != 0, out, &mut offset) {
                Ok(result) => {
                    call.bytes.output_bytes = Some(result.bytes_written as u64);
                    result.bytes_written as i32
                }
                Err(_) => -3, // Overflow or encode error
            }
        } else {
            -2 // Stale or missing
        }
    }));
    let panicked = outcome.is_err();
    let code = outcome.unwrap_or(-99);
    call.fallback_reason = if panicked {
        metrics::FallbackReason::Panic
    } else {
        match code {
            0.. => metrics::FallbackReason::None,
            -1 => metrics::FallbackReason::InvalidArgument,
            -2 => metrics::FallbackReason::MissingState,
            _ => metrics::FallbackReason::BackendError,
        }
    };
    if panicked {
        call.bytes.output_bytes = Some(0);
        call.bytes.copied_bytes = None;
    }
    code
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
    let mut call = GLOBAL_FFI_METRICS.begin_call(metrics::Operation::ChunkGetOccupancySummary);
    call.bytes = metrics::ByteMeasurements::NO_BULK;
    let outcome = catch_unwind(std::panic::AssertUnwindSafe(|| {
        let reg = get_registry();
        let handle = ChunkHandle {
            key: ChunkKey::new(dim, cx, cz),
            generation_id: generation_id as u64,
        };
        if let Some(chunk_arc) = reg.get(&handle) {
            let mut chunk = chunk_arc.write().unwrap();
            let (mask, total_blocks) = chunk.occupancy_summary();
            if out_count_addr != 0 {
                call.bytes.borrowed_bytes = Some(4);
                *(out_count_addr as *mut i32) = total_blocks as i32;
                call.bytes.output_bytes = Some(4);
            }
            mask as i32
        } else {
            -1
        }
    }));
    let panicked = outcome.is_err();
    let code = outcome.unwrap_or(-99);
    call.fallback_reason = if panicked {
        metrics::FallbackReason::Panic
    } else {
        if code >= 0 {
            metrics::FallbackReason::None
        } else {
            metrics::FallbackReason::MissingState
        }
    };
    if panicked {
        call.bytes.output_bytes = Some(0);
        call.bytes.copied_bytes = None;
    }
    code
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
    let mut call = GLOBAL_FFI_METRICS.begin_call(metrics::Operation::ChunkStagePersistence);
    call.bytes = metrics::ByteMeasurements::NO_BULK;
    let outcome = catch_unwind(std::panic::AssertUnwindSafe(|| {
        if output_buf_address == 0 || output_buf_capacity <= 0 {
            return -1;
        }
        let out = std::slice::from_raw_parts_mut(
            output_buf_address as *mut u8,
            output_buf_capacity as usize,
        );
        call.bytes.borrowed_bytes = Some(out.len() as u64);
        let reg = get_registry();
        let handle = ChunkHandle {
            key: ChunkKey::new(dim, cx, cz),
            generation_id: generation_id as u64,
        };

        if let Some(chunk_arc) = reg.get(&handle) {
            let mut chunk = chunk_arc.write().unwrap();
            call.bytes.copied_bytes = None;
            let mut offset = 0usize;
            match chunk.stage_persistence(out, &mut offset) {
                Ok(len) => {
                    call.bytes.output_bytes = Some(len as u64);
                    len as i32
                }
                Err(_) => -3,
            }
        } else {
            -2
        }
    }));
    let panicked = outcome.is_err();
    let code = outcome.unwrap_or(-99);
    call.fallback_reason = if panicked {
        metrics::FallbackReason::Panic
    } else {
        match code {
            0.. => metrics::FallbackReason::None,
            -1 => metrics::FallbackReason::InvalidArgument,
            -2 => metrics::FallbackReason::MissingState,
            _ => metrics::FallbackReason::BackendError,
        }
    };
    if panicked {
        call.bytes.output_bytes = Some(0);
        call.bytes.copied_bytes = None;
    }
    code
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
    let mut call = GLOBAL_FFI_METRICS.begin_call(metrics::Operation::ChunkInvalidate);
    call.bytes = metrics::ByteMeasurements::NO_BULK;
    catch_unwind(|| {
        let reg = get_registry();
        if reg.invalidate(ChunkKey::new(dim, cx, cz)) {
            1
        } else {
            0
        }
    })
    .unwrap_or(0)
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
    let mut call = GLOBAL_FFI_METRICS.begin_call(metrics::Operation::ChunkUnload);
    call.bytes = metrics::ByteMeasurements::NO_BULK;
    catch_unwind(|| {
        let reg = get_registry();
        if reg.remove(ChunkKey::new(dim, cx, cz)).is_some() {
            1
        } else {
            0
        }
    })
    .unwrap_or(0)
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
    let mut call = GLOBAL_FFI_METRICS.begin_call(metrics::Operation::ChunkMarkMutation);
    call.bytes = metrics::ByteMeasurements::NO_BULK;
    catch_unwind(|| {
        let reg = get_registry();
        if reg.mark_mutation(ChunkKey::new(dim, cx, cz)) {
            1
        } else {
            0
        }
    })
    .unwrap_or(0)
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
    let mut call = GLOBAL_FFI_METRICS.begin_call(metrics::Operation::ChunkMarkSectionMutation);
    call.bytes = metrics::ByteMeasurements::NO_BULK;
    catch_unwind(|| {
        let reg = get_registry();
        match reg.mark_section_mutation(ChunkKey::new(dim, cx, cz), section_y) {
            Some(mask) => mask as i32,
            None => -1,
        }
    })
    .unwrap_or(-99)
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
    let mut call = GLOBAL_FFI_METRICS.begin_call(metrics::Operation::ChunkRefreshSection);
    call.bytes = metrics::ByteMeasurements::NO_BULK;
    let outcome = catch_unwind(std::panic::AssertUnwindSafe(|| {
        if states_addr == 0 {
            return -2;
        }
        let states = &*(states_addr as *const [u16; 4096]);
        let block_light: Option<&[u8; 2048]> = if block_light_addr != 0 {
            Some(&*(block_light_addr as *const [u8; 2048]))
        } else {
            None
        };
        let sky_light: Option<&[u8; 2048]> = if sky_light_addr != 0 {
            Some(&*(sky_light_addr as *const [u8; 2048]))
        } else {
            None
        };
        call.bytes.input_bytes = Some(
            (4096 * 2
                + if block_light.is_some() { 2048 } else { 0 }
                + if sky_light.is_some() { 2048 } else { 0 }) as u64,
        );
        call.bytes.borrowed_bytes = call.bytes.input_bytes;
        call.bytes.copied_bytes = None;
        let reg = get_registry();
        match reg.refresh_section(
            ChunkKey::new(dim, cx, cz),
            section_y,
            states,
            block_light,
            sky_light,
        ) {
            Some(mask) => mask as i32,
            None => -1,
        }
    }));
    let panicked = outcome.is_err();
    let code = outcome.unwrap_or(-99);
    call.fallback_reason = if panicked {
        metrics::FallbackReason::Panic
    } else {
        match code {
            0.. => metrics::FallbackReason::None,
            -2 => metrics::FallbackReason::InvalidArgument,
            _ => metrics::FallbackReason::MissingState,
        }
    };
    if panicked {
        call.bytes.output_bytes = Some(0);
        call.bytes.copied_bytes = None;
    }
    code
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
    let mut call = GLOBAL_FFI_METRICS.begin_call(metrics::Operation::ChunkSetGlobalPaletteBits);
    call.bytes = metrics::ByteMeasurements::NO_BULK;
    catch_unwind(|| {
        native_chunk::section::set_global_palette_bits(bits);
        native_chunk::section::global_palette_bits() as i32
    })
    .unwrap_or(-99)
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
    let mut call = GLOBAL_FFI_METRICS.begin_call(metrics::Operation::ChunkGetDirtySections);
    call.bytes = metrics::ByteMeasurements::NO_BULK;
    catch_unwind(|| {
        let reg = get_registry();
        match reg.dirty_mask(ChunkKey::new(dim, cx, cz)) {
            Some(mask) => mask as i32,
            None => -1,
        }
    })
    .unwrap_or(-99)
}

/// Returns current count of registered chunks in native memory.
#[no_mangle]
pub unsafe extern "system" fn Java_com_rustcraft_bridge_NativeChunkBridge_getRegisteredCount(
    _env: *mut c_void,
    _clazz: *mut c_void,
) -> i32 {
    let mut call = GLOBAL_FFI_METRICS.begin_call(metrics::Operation::ChunkGetRegisteredCount);
    call.bytes = metrics::ByteMeasurements::NO_BULK;
    catch_unwind(|| get_registry().count() as i32).unwrap_or(0)
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
    let mut call = GLOBAL_FFI_METRICS.begin_call(metrics::Operation::ChunkFindGeneration);
    call.bytes = metrics::ByteMeasurements::NO_BULK;
    catch_unwind(|| {
        let reg = get_registry();
        reg.find_generation(ChunkKey::new(dim, cx, cz)) as i64
    })
    .unwrap_or(0)
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
    let mut call = GLOBAL_FFI_METRICS.begin_call(metrics::Operation::ChunkGetRegistryStats);
    call.bytes = metrics::ByteMeasurements::NO_BULK;
    let outcome = catch_unwind(std::panic::AssertUnwindSafe(|| {
        if out_addr == 0 {
            return -1;
        }
        let out = std::slice::from_raw_parts_mut(out_addr as *mut i64, 8);
        call.bytes.borrowed_bytes = Some(8 * 8);
        let reg = get_registry();
        let (chunks, sections, bytes) = reg.retention();
        out[0] = chunks as i64;
        out[1] = sections as i64;
        out[2] = bytes as i64;
        out[3] = native_chunk::registry::STATS_SECTIONS_ALLOCATED
            .load(std::sync::atomic::Ordering::Relaxed) as i64;
        out[4] = native_chunk::registry::STATS_SECTIONS_RELEASED
            .load(std::sync::atomic::Ordering::Relaxed) as i64;
        out[5] = native_chunk::registry::STATS_CHUNKS_EVICTED
            .load(std::sync::atomic::Ordering::Relaxed) as i64;
        out[6] = 0;
        out[7] = 0;
        call.bytes.output_bytes = Some(8 * 8);
        1
    }));
    let panicked = outcome.is_err();
    let code = outcome.unwrap_or(-99);
    call.fallback_reason = if panicked {
        metrics::FallbackReason::Panic
    } else {
        match code {
            -99 => metrics::FallbackReason::Panic,
            -1 => metrics::FallbackReason::InvalidArgument,
            0.. => metrics::FallbackReason::None,
            _ => metrics::FallbackReason::MissingState,
        }
    };
    if panicked {
        call.bytes.output_bytes = Some(0);
        call.bytes.copied_bytes = None;
    }
    code
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
    let mut call = GLOBAL_FFI_METRICS.begin_call(metrics::Operation::ChunkSetBiomes);
    call.bytes = metrics::ByteMeasurements::NO_BULK;
    let outcome = catch_unwind(std::panic::AssertUnwindSafe(|| {
        if biome_addr == 0 {
            return -1;
        }
        let biomes = &*(biome_addr as *const [u8; 256]);
        call.bytes.input_bytes = Some(256);
        call.bytes.borrowed_bytes = Some(256);
        let reg = get_registry();
        let key = ChunkKey::new(dim, cx, cz);
        let map = reg.chunks_map().read().unwrap();
        if let Some(arc) = map.get(&key) {
            let mut chunk = arc.write().unwrap();
            chunk.biomes.copy_from_slice(biomes);
            call.bytes.copied_bytes = Some(256);
            chunk.mark_mutation();
            1
        } else {
            0
        }
    }));
    let panicked = outcome.is_err();
    let code = outcome.unwrap_or(-99);
    call.fallback_reason = if panicked {
        metrics::FallbackReason::Panic
    } else {
        match code {
            1.. => metrics::FallbackReason::None,
            0 => metrics::FallbackReason::MissingState,
            _ => metrics::FallbackReason::InvalidArgument,
        }
    };
    if panicked {
        call.bytes.output_bytes = Some(0);
        call.bytes.copied_bytes = None;
    }
    code
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
    let mut call = GLOBAL_FFI_METRICS.begin_call(metrics::Operation::ChunkGetGenerationInfo);
    call.bytes = metrics::ByteMeasurements::NO_BULK;
    let outcome = catch_unwind(std::panic::AssertUnwindSafe(|| {
        if out_addr == 0 {
            return -1;
        }
        let out = std::slice::from_raw_parts_mut(out_addr as *mut i64, 4);
        call.bytes.borrowed_bytes = Some(4 * 8);
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
                call.bytes.output_bytes = Some(4 * 8);
                1
            }
            None => {
                call.bytes.output_bytes = Some(4 * 8);
                out[0] = 0;
                out[1] = 0;
                out[2] = 0;
                out[3] = 0;
                0
            }
        }
    }));
    let panicked = outcome.is_err();
    let code = outcome.unwrap_or(-99);
    call.fallback_reason = if panicked {
        metrics::FallbackReason::Panic
    } else {
        match code {
            1.. => metrics::FallbackReason::None,
            0 => metrics::FallbackReason::MissingState,
            _ => metrics::FallbackReason::InvalidArgument,
        }
    };
    if panicked {
        call.bytes.output_bytes = Some(0);
        call.bytes.copied_bytes = None;
    }
    code
}

/// In-JVM lifecycle cleanup (M4.2D): drains the registry with release
/// accounting. Returns chunks removed; getRegistryStats reflects it after.
#[no_mangle]
pub unsafe extern "system" fn Java_com_rustcraft_bridge_NativeChunkBridge_registryClear(
    _env: *mut c_void,
    _clazz: *mut c_void,
) -> i32 {
    let mut call = GLOBAL_FFI_METRICS.begin_call(metrics::Operation::ChunkRegistryClear);
    call.bytes = metrics::ByteMeasurements::NO_BULK;
    catch_unwind(|| {
        let reg = get_registry();
        let n = reg.count() as i32;
        reg.clear();
        n
    })
    .unwrap_or(-99)
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
    let mut call = GLOBAL_FFI_METRICS.begin_call(metrics::Operation::ChunkGetBiomes);
    call.bytes = metrics::ByteMeasurements::NO_BULK;
    let outcome = catch_unwind(std::panic::AssertUnwindSafe(|| {
        if out_addr == 0 {
            return -1;
        }
        let out = std::slice::from_raw_parts_mut(out_addr as *mut u8, 256);
        call.bytes.borrowed_bytes = Some(256);
        let reg = get_registry();
        let key = ChunkKey::new(dim, cx, cz);
        let map = reg.chunks_map().read().unwrap();
        match map.get(&key) {
            Some(arc) => {
                let chunk = arc.read().unwrap();
                out.copy_from_slice(&chunk.biomes);
                call.bytes.copied_bytes = Some(256);
                call.bytes.output_bytes = Some(256);
                1
            }
            None => 0,
        }
    }));
    let panicked = outcome.is_err();
    let code = outcome.unwrap_or(-99);
    call.fallback_reason = if panicked {
        metrics::FallbackReason::Panic
    } else {
        match code {
            1.. => metrics::FallbackReason::None,
            0 => metrics::FallbackReason::MissingState,
            _ => metrics::FallbackReason::InvalidArgument,
        }
    };
    if panicked {
        call.bytes.output_bytes = Some(0);
        call.bytes.copied_bytes = None;
    }
    code
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
    let mut call = GLOBAL_FFI_METRICS.begin_call(metrics::Operation::ChunkGetSectionLight);
    call.bytes = metrics::ByteMeasurements::NO_BULK;
    let outcome = catch_unwind(std::panic::AssertUnwindSafe(|| {
        if out_addr == 0 {
            return -1;
        }
        let out = std::slice::from_raw_parts_mut(out_addr as *mut u8, 4096);
        call.bytes.borrowed_bytes = Some(4096);
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
                        call.bytes.copied_bytes = Some(4096);
                        call.bytes.output_bytes = Some(4096);
                        1
                    }
                    None => 0,
                }
            }
            None => 0,
        }
    }));
    let panicked = outcome.is_err();
    let code = outcome.unwrap_or(-99);
    call.fallback_reason = if panicked {
        metrics::FallbackReason::Panic
    } else {
        match code {
            1.. => metrics::FallbackReason::None,
            0 => metrics::FallbackReason::MissingState,
            _ => metrics::FallbackReason::InvalidArgument,
        }
    };
    if panicked {
        call.bytes.output_bytes = Some(0);
        call.bytes.copied_bytes = None;
    }
    code
}

/// Authoritative setBlockState (M5 RustChunkState ownership).
///
/// Mutates block state at (x, y, z) in the registered native chunk.
/// Returns packed i64:
/// - bits 0..7: status as i8 (0 = success/changed, 1 = no-op/same state, -1 = out of bounds, -2 = not registered)
/// - bit 8: section_created (1 or 0)
/// - bit 9: section_became_empty (1 or 0)
/// - bits 16..31: old_state (u16)
/// - bits 32..47: new_state (u16)
/// - bits 48..63: non_air_count (u16)
#[no_mangle]
pub unsafe extern "system" fn Java_com_rustcraft_bridge_NativeChunkBridge_setBlockState(
    _env: *mut c_void,
    _clazz: *mut c_void,
    dim: i32,
    cx: i32,
    cz: i32,
    x: i32,
    y: i32,
    z: i32,
    new_state: i32,
) -> i64 {
    let mut call = GLOBAL_FFI_METRICS.begin_call(metrics::Operation::ChunkSetBlockState);
    call.bytes = metrics::ByteMeasurements::NO_BULK;
    let outcome = catch_unwind(std::panic::AssertUnwindSafe(|| {
        if x < 0
            || x >= 16
            || y < 0
            || y >= 256
            || z < 0
            || z >= 16
            || new_state < 0
            || new_state > 65535
        {
            return -1i8 as i64;
        }
        let reg = get_registry();
        let key = ChunkKey::new(dim, cx, cz);
        match reg.set_block_state(key, x as usize, y as usize, z as usize, new_state as u16) {
            Some(res) => {
                let flags = (if res.section_created { 1u64 << 8 } else { 0 })
                    | (if res.section_became_empty {
                        1u64 << 9
                    } else {
                        0
                    });
                let packed = (res.status as u8 as u64)
                    | flags
                    | ((res.old_state as u64) << 16)
                    | ((res.new_state as u64) << 32)
                    | ((res.non_air_count as u64) << 48);
                packed as i64
            }
            None => -2i8 as i64,
        }
    }));
    let panicked = outcome.is_err();
    let code = outcome.unwrap_or(-99i64);
    call.fallback_reason = if panicked {
        metrics::FallbackReason::Panic
    } else {
        let status = (code & 0xFF) as u8 as i8;
        match status {
            0 | 1 => metrics::FallbackReason::None,
            -1 => metrics::FallbackReason::InvalidArgument,
            _ => metrics::FallbackReason::MissingState,
        }
    };
    if panicked {
        call.bytes.output_bytes = Some(0);
        call.bytes.copied_bytes = None;
    }
    code
}

/// Direct getBlockState query (fallback / verification path).
/// Returns canonical global block state ID (0..65535), or negative error.
#[no_mangle]
pub unsafe extern "system" fn Java_com_rustcraft_bridge_NativeChunkBridge_getBlockState(
    _env: *mut c_void,
    _clazz: *mut c_void,
    dim: i32,
    cx: i32,
    cz: i32,
    x: i32,
    y: i32,
    z: i32,
) -> i32 {
    let mut call = GLOBAL_FFI_METRICS.begin_call(metrics::Operation::ChunkGetBlockState);
    call.bytes = metrics::ByteMeasurements::NO_BULK;
    let outcome = catch_unwind(std::panic::AssertUnwindSafe(|| {
        if x < 0 || x >= 16 || y < 0 || y >= 256 || z < 0 || z >= 16 {
            return -1;
        }
        let reg = get_registry();
        let key = ChunkKey::new(dim, cx, cz);
        match reg.get_block_state(key, x as usize, y as usize, z as usize) {
            Some(state) => state as i32,
            None => -2,
        }
    }));
    let panicked = outcome.is_err();
    let code = outcome.unwrap_or(-99);
    call.fallback_reason = if panicked {
        metrics::FallbackReason::Panic
    } else {
        match code {
            0.. => metrics::FallbackReason::None,
            -1 => metrics::FallbackReason::InvalidArgument,
            _ => metrics::FallbackReason::MissingState,
        }
    };
    if panicked {
        call.bytes.output_bytes = Some(0);
        call.bytes.copied_bytes = None;
    }
    code
}

/// Zero-JNI setup: populates array of 16 pointers to the resident sections' states arrays.
/// out_ptrs_addr: address of a 16-element long array (128 bytes).
/// Returns 1 on success, 0 if chunk not registered.
#[no_mangle]
pub unsafe extern "system" fn Java_com_rustcraft_bridge_NativeChunkBridge_getSectionPointers(
    _env: *mut c_void,
    _clazz: *mut c_void,
    dim: i32,
    cx: i32,
    cz: i32,
    out_ptrs_addr: i64,
) -> i32 {
    let mut call = GLOBAL_FFI_METRICS.begin_call(metrics::Operation::ChunkGetSectionPointers);
    call.bytes = metrics::ByteMeasurements::NO_BULK;
    let outcome = catch_unwind(std::panic::AssertUnwindSafe(|| {
        if out_ptrs_addr == 0 {
            return -1;
        }
        let out = std::slice::from_raw_parts_mut(out_ptrs_addr as *mut usize, 16);
        let reg = get_registry();
        let key = ChunkKey::new(dim, cx, cz);
        let mut ptrs = [0usize; 16];
        if reg.get_section_state_pointers(key, &mut ptrs) {
            out.copy_from_slice(&ptrs);
            1
        } else {
            0
        }
    }));
    let panicked = outcome.is_err();
    let code = outcome.unwrap_or(-99);
    call.fallback_reason = if panicked {
        metrics::FallbackReason::Panic
    } else {
        match code {
            1 => metrics::FallbackReason::None,
            0 => metrics::FallbackReason::MissingState,
            _ => metrics::FallbackReason::InvalidArgument,
        }
    };
    if panicked {
        call.bytes.output_bytes = Some(0);
        call.bytes.copied_bytes = None;
    }
    code
}

/// Returns raw pointer to section Y's states array (0 if absent / not registered).
#[no_mangle]
pub unsafe extern "system" fn Java_com_rustcraft_bridge_NativeChunkBridge_getSectionPointer(
    _env: *mut c_void,
    _clazz: *mut c_void,
    dim: i32,
    cx: i32,
    cz: i32,
    section_y: i32,
) -> i64 {
    let mut call = GLOBAL_FFI_METRICS.begin_call(metrics::Operation::ChunkGetSectionPointer);
    call.bytes = metrics::ByteMeasurements::NO_BULK;
    let outcome = catch_unwind(std::panic::AssertUnwindSafe(|| {
        if section_y < 0 || section_y >= 16 {
            return 0i64;
        }
        let reg = get_registry();
        let key = ChunkKey::new(dim, cx, cz);
        reg.get_section_state_pointer(key, section_y as usize) as i64
    }));
    let panicked = outcome.is_err();
    let code = outcome.unwrap_or(0i64);
    call.fallback_reason = if panicked {
        metrics::FallbackReason::Panic
    } else {
        metrics::FallbackReason::None
    };
    if panicked {
        call.bytes.output_bytes = Some(0);
        call.bytes.copied_bytes = None;
    }
    code
}

/// Zero-JNI setup: populates arrays of 16 block light and 16 sky light pointers.
/// out_bl_ptrs_addr: address of 16-element long array (128 bytes) for block light.
/// out_sl_ptrs_addr: address of 16-element long array (128 bytes) for sky light.
/// Returns 1 on success, 0 if chunk not registered.
#[no_mangle]
pub unsafe extern "system" fn Java_com_rustcraft_bridge_NativeChunkBridge_getSectionLightPointers(
    _env: *mut c_void,
    _clazz: *mut c_void,
    dim: i32,
    cx: i32,
    cz: i32,
    out_bl_ptrs_addr: i64,
    out_sl_ptrs_addr: i64,
) -> i32 {
    let mut call = GLOBAL_FFI_METRICS.begin_call(metrics::Operation::ChunkGetSectionLightPointers);
    call.bytes = metrics::ByteMeasurements::NO_BULK;
    let outcome = catch_unwind(std::panic::AssertUnwindSafe(|| {
        if out_bl_ptrs_addr == 0 || out_sl_ptrs_addr == 0 {
            return -1;
        }
        let out_bl = std::slice::from_raw_parts_mut(out_bl_ptrs_addr as *mut usize, 16);
        let out_sl = std::slice::from_raw_parts_mut(out_sl_ptrs_addr as *mut usize, 16);
        let reg = get_registry();
        let key = ChunkKey::new(dim, cx, cz);
        let mut bl_ptrs = [0usize; 16];
        let mut sl_ptrs = [0usize; 16];
        if reg.get_section_light_pointers(key, &mut bl_ptrs, &mut sl_ptrs) {
            out_bl.copy_from_slice(&bl_ptrs);
            out_sl.copy_from_slice(&sl_ptrs);
            1
        } else {
            0
        }
    }));
    let panicked = outcome.is_err();
    let code = outcome.unwrap_or(-99);
    call.fallback_reason = if panicked {
        metrics::FallbackReason::Panic
    } else {
        match code {
            1 => metrics::FallbackReason::None,
            0 => metrics::FallbackReason::MissingState,
            _ => metrics::FallbackReason::InvalidArgument,
        }
    };
    if panicked {
        call.bytes.output_bytes = Some(0);
        call.bytes.copied_bytes = None;
    }
    code
}

/// Returns raw pointer to section Y's light array (is_skylight: 0 for block light, 1 for sky light).
/// Returns 0 if absent / not registered.
#[no_mangle]
pub unsafe extern "system" fn Java_com_rustcraft_bridge_NativeChunkBridge_getSectionLightPointer(
    _env: *mut c_void,
    _clazz: *mut c_void,
    dim: i32,
    cx: i32,
    cz: i32,
    section_y: i32,
    is_skylight: i32,
) -> i64 {
    let mut call = GLOBAL_FFI_METRICS.begin_call(metrics::Operation::ChunkGetSectionLightPointer);
    call.bytes = metrics::ByteMeasurements::NO_BULK;
    let outcome = catch_unwind(std::panic::AssertUnwindSafe(|| {
        if section_y < 0 || section_y >= 16 {
            return 0i64;
        }
        let reg = get_registry();
        let key = ChunkKey::new(dim, cx, cz);
        if is_skylight != 0 {
            reg.get_section_sky_light_pointer(key, section_y as usize) as i64
        } else {
            reg.get_section_block_light_pointer(key, section_y as usize) as i64
        }
    }));
    let panicked = outcome.is_err();
    let code = outcome.unwrap_or(0i64);
    call.fallback_reason = if panicked {
        metrics::FallbackReason::Panic
    } else {
        metrics::FallbackReason::None
    };
    if panicked {
        call.bytes.output_bytes = Some(0);
        call.bytes.copied_bytes = None;
    }
    code
}

// ====================================================================
// M-CK3: Rust outbound frame engine (offline; immutable packet bodies)
// ====================================================================

/// Creates a reusable outbound frame context (threshold supplied per call).
#[no_mangle]
pub unsafe extern "system" fn Java_com_rustcraft_bridge_OutboundFrameCtx_frameCreate(
    _env: *mut c_void,
    _clazz: *mut c_void,
) -> i64 {
    catch_unwind(|| match compression::frame::OutboundFrameContext::new() {
        Ok(ctx) => Box::into_raw(Box::new(ctx)) as i64,
        Err(_) => 0,
    })
    .unwrap_or(0)
}

/// Frees a frame context (idempotent via caller CAS pattern; 0 is never valid).
#[no_mangle]
pub unsafe extern "system" fn Java_com_rustcraft_bridge_OutboundFrameCtx_frameFree(
    _env: *mut c_void,
    _clazz: *mut c_void,
    handle: i64,
) -> i32 {
    catch_unwind(|| {
        if handle == 0 {
            return 0;
        }
        drop(Box::from_raw(
            handle as *mut compression::frame::OutboundFrameContext,
        ));
        1
    })
    .unwrap_or(0)
}

/// Encodes one complete frame: threshold decision + optional compression +
/// compression framing + outer length framing, all in Rust.
/// Returns frame length (>0) or negative FrameError code (-1 capacity: the
/// SAFE RETRY BOUND is written to retryBoundAddr; -2 backend; -3 too large;
/// -4 invalid). Partial output is never a completed frame.
#[no_mangle]
pub unsafe extern "system" fn Java_com_rustcraft_bridge_OutboundFrameCtx_frameEncode(
    _env: *mut c_void,
    _clazz: *mut c_void,
    handle: i64,
    in_addr: i64,
    in_len: i32,
    out_addr: i64,
    out_cap: i32,
    threshold: i32,
    retry_bound_addr: i64,
) -> i32 {
    catch_unwind(|| {
        if handle == 0 || in_addr == 0 || out_addr == 0 || in_len <= 0 || out_cap <= 0 {
            return -4;
        }
        let ctx = &mut *(handle as *mut compression::frame::OutboundFrameContext);
        let body = std::slice::from_raw_parts(in_addr as *const u8, in_len as usize);
        let out = std::slice::from_raw_parts_mut(out_addr as *mut u8, out_cap as usize);
        match ctx.encode(body, threshold, out) {
            Ok(n) => n as i32,
            Err(e) => {
                if retry_bound_addr != 0 {
                    *(retry_bound_addr as *mut i32) = e.needed() as i32;
                }
                e.as_code()
            }
        }
    })
    .unwrap_or(-2)
}
