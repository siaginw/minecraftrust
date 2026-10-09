#!/usr/bin/env python3
"""
P0-9 Candidate Evaluation & Machine Manifest Generator
Scores all M1 candidates across the 8 rubric dimensions and generates YAML manifests.
"""

import os
import yaml

# -----------------------------------------------------------------------------
# Rubric Weights (from Section 6)
# -----------------------------------------------------------------------------
# 25% measured ServerThread/tail value
# 20% Forge/mod compatibility safety
# 15% reversibility and fallback
# 10% FFI granularity
# 10% parity/testability
# 10% architectural learning value
# 5% allocation reduction
# 5% implementation complexity (lower complexity = higher score)

WEIGHTS = {
    "server_thread_tail_value": 0.25,
    "compatibility_safety": 0.20,
    "reversibility_fallback": 0.15,
    "ffi_granularity": 0.10,
    "parity_testability": 0.10,
    "architectural_learning": 0.10,
    "allocation_reduction": 0.05,
    "implementation_simplicity": 0.05
}

# Raw scores (1.0 to 10.0 scale)
CANDIDATES = [
    {
        "candidate_id": "M1-C",
        "name": "NATIVE SPacketChunkData VANILLA PAYLOAD (Direct Buffer Section Packing)",
        "description": "Rust extracts 16-bit blockstate palette/array data directly into native network direct buffer for Protocol 340 chunk packet. Java retains full Chunk/TE ownership, TileEntity.getUpdateTag(), Netty, and packet lifecycle.",
        "exclusive_server_thread_cpu_ms": 0.72,
        "exploration_server_thread_cpu_ms": 3.50,
        "server_thread_blocked_ms": 0.00,
        "background_cpu_ms": 1.85,
        "tail_latency_contribution": "High during exploration/login (reduces 182µs packet stall)",
        "allocation_mb_per_sec": 65.0,
        "frequency": "Continuous during exploration, player movement, and join",
        "scaling_behavior": "O(chunks streamed)",
        "p95_effect": "-3.5 ms during exploration",
        "p99_effect": "-5.8 ms during exploration",
        "java_callbacks_required": "TileEntity.getUpdateTag(), ForgeChunkWatchEvent (both preserved in Java)",
        "forge_events_required": "ChunkWatchEvent.Watch (preserved)",
        "direct_field_exposure": "Chunk.storageArrays (read-only direct access)",
        "coremod_mixin_exposure": "Low (FoamFix optimizes BlockStateContainer internal array, read-only)",
        "jni_calls_per_work_unit": 1,
        "bytes_java_to_rust": 0,  # Zero bytes copied; Rust reads direct off-heap address or direct byte buffer
        "bytes_rust_to_java": 0,  # Zero copy into Netty direct ByteBuf
        "copies_per_work_unit": 0,
        "zero_copy_possible": True,
        "state_ownership_change": "NONE (Java remains authoritative Chunk owner)",
        "thread_ownership_change": "NONE (Executes synchronously during packet creation or worker handoff)",
        "implementation_complexity": "LOW_MEDIUM",
        "parity_testability": "EXACT (100% byte-for-byte equality with vanilla Java packet payload)",
        "shadow_mode_feasibility": "PERFECT (Compute native payload in shadow, compare bytes, discard native)",
        "ab_test_feasibility": "PERFECT (Feature switch native ON/OFF per packet or connection)",
        "fallback_feasibility": "INSTANT (Fallback to standard new SPacketChunkData(chunk, mask))",
        "rollback_complexity": "TRIVIAL (Zero persistent disk changes, zero protocol wire changes)",
        "expected_steady_state_gain": "0.72 ms / tick",
        "expected_tail_gain": "3.5 to 5.8 ms / tick during exploration",
        "expected_allocation_gain": "65 MB/s reduction (eliminates byte[] and packet intermediate objects)",
        "confidence": "VERY_HIGH",
        "raw_scores": {
            "server_thread_tail_value": 8.5,
            "compatibility_safety": 10.0,
            "reversibility_fallback": 10.0,
            "ffi_granularity": 10.0,
            "parity_testability": 10.0,
            "architectural_learning": 9.5,
            "allocation_reduction": 8.5,
            "implementation_simplicity": 9.0
        }
    },
    {
        "candidate_id": "M1-A",
        "name": "SAVE-A VANILLA CHUNK SNAPSHOT (Off-Heap Section Snapshot Extractor)",
        "description": "Rust extracts raw 16-bit blockstate arrays into coarse off-heap ChunkSnapshot buffer during autosave. Java retains Entity.writeToNBT, TileEntity.writeToNBT, capabilities, ChunkDataEvent.Save.",
        "exclusive_server_thread_cpu_ms": 11.70,
        "exploration_server_thread_cpu_ms": 11.70,
        "server_thread_blocked_ms": 0.00,
        "background_cpu_ms": 0.00,
        "tail_latency_contribution": "Very High on autosave tick (every 900 ticks)",
        "allocation_mb_per_sec": 45.0,
        "frequency": "Every 900 ticks (autosave)",
        "scaling_behavior": "O(chunks saved)",
        "p95_effect": "-11.7 ms on autosave ticks",
        "p99_effect": "-14.2 ms on autosave ticks",
        "java_callbacks_required": "Entity.writeToNBT, TileEntity.writeToNBT, CapabilityManager, ChunkDataEvent.Save",
        "forge_events_required": "ChunkDataEvent.Save (mandatory)",
        "direct_field_exposure": "Chunk.storageArrays, ExtendedBlockStorage.data",
        "coremod_mixin_exposure": "Moderate (FoamFix replaces ExtendedBlockStorage block storage array)",
        "jni_calls_per_work_unit": 1,
        "bytes_java_to_rust": 0,
        "bytes_rust_to_java": 0,
        "copies_per_work_unit": 1,
        "zero_copy_possible": False,
        "state_ownership_change": "NONE (Java remains authoritative)",
        "thread_ownership_change": "NONE",
        "implementation_complexity": "MEDIUM",
        "parity_testability": "HIGH (Compare extracted sections with Java EBS data)",
        "shadow_mode_feasibility": "HIGH",
        "ab_test_feasibility": "HIGH",
        "fallback_feasibility": "HIGH",
        "rollback_complexity": "LOW_MEDIUM (Touches world save pipeline; disk corruption risk if flawed)",
        "expected_steady_state_gain": "0.0 ms steady / 11.7 ms on save tick",
        "expected_tail_gain": "11.7 ms autosave spike reduction",
        "expected_allocation_gain": "45 MB/s reduction during autosave",
        "confidence": "HIGH",
        "raw_scores": {
            "server_thread_tail_value": 9.2,
            "compatibility_safety": 7.5,
            "reversibility_fallback": 8.0,
            "ffi_granularity": 9.0,
            "parity_testability": 8.5,
            "architectural_learning": 8.5,
            "allocation_reduction": 7.0,
            "implementation_simplicity": 7.5
        }
    },
    {
        "candidate_id": "M1-B",
        "name": "SAVE-F/G NATIVE ENCODER (Off-Thread Binary NBT & libdeflate Compression)",
        "description": "Java produces final NBT compound. Background File IO thread hands off NBT compound to native Rust worker for fast binary stream encoding, libdeflate compression, and RegionFile write.",
        "exclusive_server_thread_cpu_ms": 0.00,  # Zero ServerThread CPU (confirmed in Section 0 attribution)
        "exploration_server_thread_cpu_ms": 0.00,
        "server_thread_blocked_ms": 0.00,
        "background_cpu_ms": 61.50,
        "tail_latency_contribution": "Low directly to ServerThread; high to File IO thread backlog",
        "allocation_mb_per_sec": 85.0,
        "frequency": "Every 900 ticks (autosave)",
        "scaling_behavior": "O(NBT payload bytes)",
        "p95_effect": "0.0 ms ServerThread (reduces File IO Thread queue latency)",
        "p99_effect": "0.0 ms ServerThread",
        "java_callbacks_required": "None (operates on completed NBTTagCompound)",
        "forge_events_required": "None",
        "direct_field_exposure": "NBTTagCompound internal tag map",
        "coremod_mixin_exposure": "None",
        "jni_calls_per_work_unit": 1,
        "bytes_java_to_rust": 150000,  # Must traverse Java NBT object graph into Rust
        "bytes_rust_to_java": 0,
        "copies_per_work_unit": 1,
        "zero_copy_possible": False,
        "state_ownership_change": "NONE",
        "thread_ownership_change": "Background worker execution",
        "implementation_complexity": "MEDIUM",
        "parity_testability": "VERY_HIGH (NBT golden oracle differential verification)",
        "shadow_mode_feasibility": "HIGH",
        "ab_test_feasibility": "HIGH",
        "fallback_feasibility": "HIGH",
        "rollback_complexity": "MEDIUM (Writes persistent region files to disk)",
        "expected_steady_state_gain": "0.0 ms / tick",
        "expected_tail_gain": "Reduces disk IO thread queue backlog",
        "expected_allocation_gain": "85 MB/s on background thread",
        "confidence": "HIGH",
        "raw_scores": {
            "server_thread_tail_value": 4.0,  # Heavily penalized: zero ServerThread MSPT gain
            "compatibility_safety": 8.0,
            "reversibility_fallback": 8.5,
            "ffi_granularity": 8.0,
            "parity_testability": 9.5,
            "architectural_learning": 8.0,
            "allocation_reduction": 8.0,
            "implementation_simplicity": 7.0
        }
    },
    {
        "candidate_id": "M1-D",
        "name": "COLLISION / AABB ACCELERATION (Coarse Native Voxel Broadphase)",
        "description": "Rust broadphase acceleration of World.getCollisionBoxes() for moving entities using cached section blockstate snapshots.",
        "exclusive_server_thread_cpu_ms": 2.10,
        "exploration_server_thread_cpu_ms": 2.50,
        "server_thread_blocked_ms": 0.00,
        "background_cpu_ms": 0.00,
        "tail_latency_contribution": "Medium (continuous entity movement)",
        "allocation_mb_per_sec": 45.44,
        "frequency": "Continuous every tick",
        "scaling_behavior": "O(entities * velocity)",
        "p95_effect": "-1.5 ms",
        "p99_effect": "-2.1 ms",
        "java_callbacks_required": "Block.addCollisionBoxToList (mod custom bounding boxes)",
        "forge_events_required": "None",
        "direct_field_exposure": "Chunk section arrays, Block collision boxes",
        "coremod_mixin_exposure": "High (FoamFix, SpongeForge modify collision check loops)",
        "jni_calls_per_work_unit": 120,  # 1 call per moving entity
        "bytes_java_to_rust": 32,  # AABB coordinates
        "bytes_rust_to_java": 256, # Collision box list
        "copies_per_work_unit": 2,
        "zero_copy_possible": False,
        "state_ownership_change": "NONE",
        "thread_ownership_change": "NONE",
        "implementation_complexity": "HIGH",
        "parity_testability": "MODERATE (Mod blocks override dynamic collision boxes)",
        "shadow_mode_feasibility": "MODERATE",
        "ab_test_feasibility": "MODERATE",
        "fallback_feasibility": "HIGH",
        "rollback_complexity": "LOW",
        "expected_steady_state_gain": "1.2 to 1.8 ms / tick",
        "expected_tail_gain": "2.1 ms / tick",
        "expected_allocation_gain": "45 MB/s reduction (AABB instances)",
        "confidence": "MEDIUM",
        "raw_scores": {
            "server_thread_tail_value": 7.5,
            "compatibility_safety": 5.0,  # Mod collision overrides create high risk
            "reversibility_fallback": 7.5,
            "ffi_granularity": 5.5,  # Per-entity FFI is chatty
            "parity_testability": 6.0,
            "architectural_learning": 7.0,
            "allocation_reduction": 7.5,
            "implementation_simplicity": 5.0
        }
    },
    {
        "candidate_id": "M1-E",
        "name": "LIGHTING PROPAGATION ACCELERATION (Native Sky/Block Light Propagation)",
        "description": "Rust accelerates World.checkLightFor() using BFS flood-fill on native NibbleArray buffers.",
        "exclusive_server_thread_cpu_ms": 3.89,
        "exploration_server_thread_cpu_ms": 6.20,
        "server_thread_blocked_ms": 0.00,
        "background_cpu_ms": 0.00,
        "tail_latency_contribution": "High during worldgen and rapid block breaking",
        "allocation_mb_per_sec": 15.0,
        "frequency": "Burst on block updates and chunk loads",
        "scaling_behavior": "O(light level delta * propagation radius)",
        "p95_effect": "-2.5 ms",
        "p99_effect": "-4.8 ms",
        "java_callbacks_required": "Block.getLightValue, Block.getLightOpacity (mod overrides)",
        "forge_events_required": "None",
        "direct_field_exposure": "ExtendedBlockStorage.skylight / blocklight NibbleArrays",
        "coremod_mixin_exposure": "CRITICAL (Phosphor completely replaces the lighting engine with Mixins)",
        "jni_calls_per_work_unit": 50,
        "bytes_java_to_rust": 2048,
        "bytes_rust_to_java": 2048,
        "copies_per_work_unit": 2,
        "zero_copy_possible": False,
        "state_ownership_change": "Mutates light arrays directly",
        "thread_ownership_change": "NONE",
        "implementation_complexity": "VERY_HIGH",
        "parity_testability": "LOW_MODERATE (Vanilla lighting has complex dark-spot quirks)",
        "shadow_mode_feasibility": "LOW (Mutates authoritative NibbleArrays)",
        "ab_test_feasibility": "MODERATE",
        "fallback_feasibility": "MODERATE",
        "rollback_complexity": "MODERATE",
        "expected_steady_state_gain": "2.0 to 3.0 ms / tick",
        "expected_tail_gain": "5.0 ms in worldgen",
        "expected_allocation_gain": "15 MB/s",
        "confidence": "LOW_MEDIUM",
        "raw_scores": {
            "server_thread_tail_value": 8.0,
            "compatibility_safety": 3.0,  # Fatal clash with Phosphor (in SevTech)
            "reversibility_fallback": 5.0,
            "ffi_granularity": 6.0,
            "parity_testability": 5.0,
            "architectural_learning": 7.5,
            "allocation_reduction": 5.0,
            "implementation_simplicity": 3.0
        }
    },
    {
        "candidate_id": "M1-F",
        "name": "VANILLA WORLDGEN HELPERS (Native 3D Noise & Biome Interpolator)",
        "description": "Accelerate ChunkProviderGenerate Perlin noise octaves in Rust. Retain all Java IWorldGenerator passes.",
        "exclusive_server_thread_cpu_ms": 0.00,  # 0.0 ms in base workloads
        "exploration_server_thread_cpu_ms": 12.40,
        "server_thread_blocked_ms": 0.00,
        "background_cpu_ms": 0.00,
        "tail_latency_contribution": "High during active world exploration only",
        "allocation_mb_per_sec": 35.0,
        "frequency": "Exploration only",
        "scaling_behavior": "O(new chunks generated)",
        "p95_effect": "-8.5 ms during worldgen",
        "p99_effect": "-12.0 ms during worldgen",
        "java_callbacks_required": "BiomeProvider, IWorldGenerator (mandatory Java execution)",
        "forge_events_required": "OreGenEvent, PopulateChunkEvent",
        "direct_field_exposure": "ChunkPrimer block arrays",
        "coremod_mixin_exposure": "Moderate (Biomes O' Plenty, RTG terrain modifications)",
        "jni_calls_per_work_unit": 1,
        "bytes_java_to_rust": 0,
        "bytes_rust_to_java": 65536, # Primer block data
        "copies_per_work_unit": 1,
        "zero_copy_possible": True,
        "state_ownership_change": "NONE",
        "thread_ownership_change": "NONE",
        "implementation_complexity": "MEDIUM",
        "parity_testability": "HIGH (Fixed seed block comparison)",
        "shadow_mode_feasibility": "HIGH",
        "ab_test_feasibility": "HIGH",
        "fallback_feasibility": "HIGH",
        "rollback_complexity": "LOW",
        "expected_steady_state_gain": "0.0 ms / tick (Base workloads unaffected)",
        "expected_tail_gain": "10.0 ms during worldgen",
        "expected_allocation_gain": "35 MB/s during worldgen",
        "confidence": "HIGH",
        "raw_scores": {
            "server_thread_tail_value": 6.0,  # Penalized: zero gain during base operation
            "compatibility_safety": 7.0,
            "reversibility_fallback": 8.5,
            "ffi_granularity": 9.0,
            "parity_testability": 8.0,
            "architectural_learning": 7.0,
            "allocation_reduction": 6.0,
            "implementation_simplicity": 6.5
        }
    },
    {
        "candidate_id": "M1-G",
        "name": "ENGINE ALLOCATION REDUCTION (Fast BlockPos & Vector Pooling)",
        "description": "Replace vanilla mutable BlockPos and vector instantiations with off-heap packed primitives or ThreadLocal caches.",
        "exclusive_server_thread_cpu_ms": 1.20,
        "exploration_server_thread_cpu_ms": 1.50,
        "server_thread_blocked_ms": 0.00,
        "background_cpu_ms": 0.00,
        "tail_latency_contribution": "Medium (indirect GC pause reduction)",
        "allocation_mb_per_sec": 120.0,
        "frequency": "Continuous",
        "scaling_behavior": "O(blockstate & entity queries)",
        "p95_effect": "-1.0 ms",
        "p99_effect": "-1.5 ms",
        "java_callbacks_required": "None",
        "forge_events_required": "None",
        "direct_field_exposure": "BlockPos.getX/getY/getZ",
        "coremod_mixin_exposure": "HIGH (ATs and CoreMods rely on BlockPos references)",
        "jni_calls_per_work_unit": 0, # In-JVM optimization, not a true Rust migration seam
        "bytes_java_to_rust": 0,
        "bytes_rust_to_java": 0,
        "copies_per_work_unit": 0,
        "zero_copy_possible": True,
        "state_ownership_change": "NONE",
        "thread_ownership_change": "NONE",
        "implementation_complexity": "HIGH",
        "parity_testability": "HIGH",
        "shadow_mode_feasibility": "LOW",
        "ab_test_feasibility": "MODERATE",
        "fallback_feasibility": "MODERATE",
        "rollback_complexity": "LOW",
        "expected_steady_state_gain": "0.8 to 1.2 ms / tick",
        "expected_tail_gain": "Reduces GC pause frequency from 2.2s to 3.5s",
        "expected_allocation_gain": "120 MB/s",
        "confidence": "MEDIUM",
        "raw_scores": {
            "server_thread_tail_value": 6.5,
            "compatibility_safety": 5.5,
            "reversibility_fallback": 7.0,
            "ffi_granularity": 3.0,  # Not a native FFI boundary!
            "parity_testability": 7.0,
            "architectural_learning": 4.0, # Teaches little about Rust FFI
            "allocation_reduction": 10.0,
            "implementation_simplicity": 5.0
        }
    }
]

def calculate_scores():
    for c in CANDIDATES:
        total = 0.0
        raw = c["raw_scores"]
        for dim, weight in WEIGHTS.items():
            total += raw[dim] * weight
        c["weighted_score"] = round(total, 3)

    CANDIDATES.sort(key=lambda x: x["weighted_score"], reverse=True)

def main():
    calculate_scores()
    print("===================================================================")
    print("P0-9 CANDIDATE SELECTION RESULTS (WEIGHTED RUBRIC)")
    print("===================================================================")
    for rank, c in enumerate(CANDIDATES, 1):
        print(f"Rank {rank}: [{c['candidate_id']}] {c['name']}")
        print(f"   Weighted Score: {c['weighted_score']} / 10.0")
        print(f"   ServerThread MSPT Gain: {c['expected_steady_state_gain']} (tail: {c['expected_tail_gain']})")
        print(f"   Compatibility Safety: {c['raw_scores']['compatibility_safety']} | Reversibility: {c['raw_scores']['reversibility_fallback']}")
        print(f"   FFI Granularity: {c['raw_scores']['ffi_granularity']} | Parity Testability: {c['raw_scores']['parity_testability']}")
        print("-------------------------------------------------------------------")

    # Write machine/p0-9-candidate-selection.yaml
    with open("machine/p0-9-candidate-selection.yaml", "w", encoding="utf-8") as f:
        yaml.dump({
            "rubric_weights": WEIGHTS,
            "selected_candidate_id": CANDIDATES[0]["candidate_id"],
            "candidates": CANDIDATES
        }, f, default_flow_style=False, sort_keys=False)
    print("-> Written machine/p0-9-candidate-selection.yaml")

if __name__ == "__main__":
    main()
