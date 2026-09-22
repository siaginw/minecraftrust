#!/usr/bin/env python3
"""
Generate machine/migration-M1.yaml and machine/M1-acceptance-gates.yaml
with empirically corrected baselines, GC-safe INPUT-A FFI contract,
Netty ByteBuf lifecycle, and realistic performance gates.
"""

import yaml

MIGRATION_M1 = {
    "migration_id": "M1",
    "candidate_id": "M1-C",
    "name": "NATIVE SPacketChunkData VANILLA PAYLOAD (Direct Staging Buffer Section Encoding)",
    "status": "APPROVED_DESIGN_ONLY",
    "phase": "P0-9",
    "authoritative_subsystem": "chunk_packet_encoding",
    "target_ownership": {
        "initial": "JAVA_OWNED",
        "validation_phase": "SHADOW_TESTED",
        "production_target": "JAVA_OWNED_RUST_ACCELERATED",
        "long_term_future": "RUST_OWNED (Deferred to later project phases)"
    },
    "feature_switch": {
        "property": "minecraftrust.native_chunk_packet",
        "modes": ["OFF", "SHADOW", "ON"],
        "default": "OFF"
    },
    "scope": {
        "in_scope": [
            "Java population of thread-local off-heap staging buffer (INPUT-A schema: bitmask, flags, palette, storage words, lights, biomes)",
            "Native reading of staging buffer and direct write of Protocol 340 SPacketChunkData wire payload",
            "Writing packed section payload directly into Netty PooledByteBufAllocator direct buffer",
            "Deterministic byte-for-byte shadow comparison against Java reference output",
            "Automatic fallback to Java reference constructor on any error or unsupported case"
        ],
        "out_of_scope": [
            "Replacing Netty transport, channels, or EventLoops",
            "Replacing Java Chunk, ExtendedBlockStorage, or World ownership",
            "Bypassing TileEntity.getUpdateTag() or mod TileEntity packet synchronization",
            "Modifying Forge ChunkWatchEvent.Watch or player tracking dispatch",
            "Replacing deflate compression on Netty IO threads"
        ]
    },
    "ffi_contract": {
        "abi_version": 2,
        "entry_point": "Java_com_rustcraft_bridge_NativeChunkPacket_encodeSections",
        "signature": "(JJI)I",
        "parameters": [
            {"name": "env", "type": "JNIEnv*", "desc": "JNI environment pointer"},
            {"name": "clazz", "type": "jclass", "desc": "Static class handle"},
            {"name": "staging_buf_address", "type": "jlong", "desc": "Direct memory address of thread-local off-heap staging buffer (INPUT-A)"},
            {"name": "staging_buf_len", "type": "jint", "desc": "Valid bytes in staging buffer"},
            {"name": "output_buf_address", "type": "jlong", "desc": "Direct memory address of Netty pooled DirectByteBuf"},
            {"name": "output_capacity", "type": "jint", "desc": "Capacity of output buffer in bytes (>= 262144)"}
        ],
        "return_value": {
            "type": "jint",
            "semantics": ">0: Bytes written into direct buffer; <0: Error code triggering instant Java fallback"
        },
        "alignment_and_endianness": {
            "byte_order": "Big-Endian (Standard Minecraft network wire order)",
            "alignment": "8-byte word alignment for long array copies"
        },
        "error_codes": {
            "0": "SUCCESS",
            "-1": "NULL_BUFFER_POINTER",
            "-2": "OUTPUT_BUFFER_OVERFLOW",
            "-3": "INVALID_SECTION_BITMASK",
            "-4": "UNSUPPORTED_PALETTE_WIDTH",
            "-5": "RUST_PANIC_CAUGHT"
        }
    },
    "memory_flow": {
        "step_1": "ServerThread identifies dirty chunk sections to send",
        "step_2": "ServerThread populates thread-local direct staging buffer (5.24 µs) [JAVA_HEAP_COPY to off-heap]",
        "step_3": "ServerThread calls JNI with staging_buf_address and Netty output_buf_address [ZERO_COPY: borrow addresses]",
        "step_4": "Rust encodes wire VarInts, palette, BitArray, lights, and biomes into Netty DirectByteBuf (2.00 µs) [NATIVE_COPY]",
        "step_5": "Java updates Netty writerIndex(bytesWritten) and wraps in SPacketChunkData [WRAPPER_ONLY]",
        "step_6": "Netty pipeline deflates on IO worker thread, writes socket, and releases direct buffer [ZERO_COPY]"
    },
    "netty_bytebuf_lifecycle": {
        "allocator": "PooledByteBufAllocator.DEFAULT.directBuffer(capacity)",
        "initial_ref_count": 1,
        "cleanup_on_success": "ReferenceCountUtil.release() invoked by Netty channel pipeline post-write",
        "cleanup_on_error": "Immediate directBuf.release() in catch block before Java fallback execution"
    },
    "threading_model": {
        "execution_context": "Synchronous on ServerThread during packet object instantiation",
        "server_thread_duration": "MEASURED post-M1.4-R: 9.19 µs total native (T_Stage 6.59 + T_JniWin 2.09) vs Java custom-encoder ref 7.25 µs — native SLOWER, gate FAILED (machine/raw/M14R-apples-bench.log)",
        "reference_java_duration": "MEASURED: vanilla ctor+serialize ~72 µs 0xFFFF (machine/raw/shadow_full.log); custom encoder 7.25 µs",
        "net_saving_per_chunk": "NEGATIVE (-1.94 µs vs custom encoder ref) as of 2026-09-18 honest run; earlier 4.36 µs claim INVALIDATED (synthetic decomposition)"
    },
    "panic_containment": {
        "policy": "catch_unwind on all native extern \"C\" entry points. Rust must NEVER unwind into JVM.",
        "on_panic": "Catch unwind, log diagnostic error with chunk coordinates, increment metric native_panics, return -5, fall back to Java."
    },
    "fallback_design": {
        "trigger": "Any return code < 0, null direct buffer, or unexpected CoreMod hook",
        "action": "Release directBuf, execute standard Java path: new SPacketChunkData(chunk, primaryBitMask)",
        "diagnostics": "Emit WARN log with reason and increment fallback_counter"
    },
    "shadow_mode_design": {
        "active_when": "minecraftrust.native_chunk_packet=SHADOW",
        "execution": [
            "1. Java constructs standard SPacketChunkData reference payload into ref_buf",
            "2. Rust computes native payload into native_buf",
            "3. JVM executes explicit loop: (ref_len == native_len) && (ref_bytes[i] == native_bytes[i])",
            "4. If mismatch: log full structured diagnostic dump with chunk coords, increment shadow_mismatch_counter",
            "5. Discard native_buf, transmit ref_buf (Java remains 100% authoritative)"
        ]
    }
}

M1_ACCEPTANCE_GATES = {
    "gates": {
        "correctness": [
            {"id": "GATE-CORRECT-1", "name": "Exact Wire Parity", "rule": "Native payload bytes must match Java reference output byte-for-byte across 100,000 continuous chunk transmissions without a single divergence in SHADOW mode."},
            {"id": "GATE-CORRECT-2", "name": "TileEntity Sync Preservation", "rule": "Mod TileEntity NBT update tags (EnderIO conduits, AE2 cables, chests) must serialize identically through Java getUpdateTag() without bypass."},
            {"id": "GATE-CORRECT-3", "name": "Dynamic Palette Width Parity", "rule": "Correctly handles dynamic global and indirect palettes from 4 to 16 bits without bit truncation."},
            {"id": "GATE-CORRECT-4", "name": "Dimension Skylight Variants", "rule": "Correctly omits skylight nibble array in Nether, End, and non-skylight modded dimensions."}
        ],
        "property_fuzz": [
            {"id": "GATE-FUZZ-1", "name": "Empty Chunk Sections", "rule": "Correctly serializes chunks with all-air sections, single non-empty sections, and completely saturated sections."},
            {"id": "GATE-FUZZ-2", "name": "Negative & High Coordinates", "rule": "Fuzz testing across chunk coordinates X/Z in [-30,000,000..+30,000,000] under fixed PRNG seeds."},
            {"id": "GATE-FUZZ-3", "name": "Buffer Overflow Guard", "rule": "Under-sized direct buffer must cleanly return error code -2 without memory corruption or segmentation fault."},
            {"id": "GATE-FUZZ-4", "name": "Max Legal Forge State ID", "rule": "Correctly serializes blockstate IDs up to 65,535 (Block 4095, meta 15)."}
        ],
        "performance": [
            {"id": "GATE-PERF-MICRO", "name": "End-to-End Microbenchmark Gate", "rule": "Total native path (staging + JNI + Rust + output wrap) must execute in <=8.5 µs per 16-section chunk AND beat the Java reference measured in the same run (equal-scope, see docs/engineering/m1-benchmark-scopes.md).", "reference": {"provenance_id": "M1_MICRO_16_SECTION_CURRENT", "raw_artifact": "machine/raw/M14R-canonical-scopes.csv", "summary": "machine/raw/M14R-canonical-scopes-summary.txt", "measured": "SCOPE-A-N p50 3.20 µs vs SCOPE-B Java ctor p50 6.20 µs (pooled); per-fixture ratios in summary"}},
            {"id": "GATE-PERF-SERVER", "name": "ServerThread Compute Gate", "rule": "Measurable reduction of at least 0.04 ms ServerThread compute MSPT in Target C steady state and >=0.35 ms during exploration/login bursts (100 chunks/tick)."},
            {"id": "GATE-PERF-ALLOC", "name": "Allocation Reduction Gate", "rule": "Eliminates at least 15.0 MB/s of ephemeral byte[] churn by using pooled off-heap Netty buffers."},
            {"id": "GATE-PERF-REGRESS", "name": "Zero Regression Gate", "rule": "Zero measurable regression in TPS, wall cadence, or GC pause time across Target A, B, C, and D."}
        ],
        "compatibility": [
            {"id": "GATE-COMPAT-A", "name": "Target A Clean Forge", "rule": "100% pass on clean Forge 14.23.5.2860."},
            {"id": "GATE-COMPAT-B", "name": "Target B Minimal Corpus", "rule": "100% pass with Thermal Expansion, AE2, Tinkers, Iron Chests, JourneyMap."},
            {"id": "GATE-COMPAT-C", "name": "Target C FTB Revelation", "rule": "Zero crashes or client desyncs over 1-hour active server session with 18 CoreMods enabled."},
            {"id": "GATE-COMPAT-D", "name": "Target D SevTech Ages", "rule": "Empirical shadow-mode verification with FoamFix and Phosphor Mixins enabled."}
        ],
        "rejection_triggers": [
            "Any unhandled Rust panic winding through JNI",
            "Any persistent packet byte divergence in shadow mode",
            "Any client kick or 'Internal Exception: io.netty.handler.codec.DecoderException' observed",
            "Performance gain falling below benchmark noise floor (<0.02 ms delta)",
            "Any memory leak in off-heap Netty ByteBuf handles"
        ]
    }
}

def main():
    with open("machine/migration-M1.yaml", "w", encoding="utf-8") as f:
        yaml.dump(MIGRATION_M1, f, default_flow_style=False, sort_keys=False)
    print("-> Written machine/migration-M1.yaml")

    with open("machine/M1-acceptance-gates.yaml", "w", encoding="utf-8") as f:
        yaml.dump(M1_ACCEPTANCE_GATES, f, default_flow_style=False, sort_keys=False)
    print("-> Written machine/M1-acceptance-gates.yaml")

if __name__ == "__main__":
    main()
