#!/usr/bin/env python3
"""
Recalculate P0-9 Candidate Evaluation Rubric with Corrected Empirical Timings.
Weights remain identical:
- 25% Measured ServerThread / Tail Value
- 20% Forge / Mod Compatibility Safety
- 15% Reversibility and Fallback
- 10% FFI Granularity
- 10% Parity / Testability
- 10% Architectural Learning Value
- 5% Allocation Reduction
- 5% Implementation Complexity
"""

import yaml

# Candidate data with corrected empirical values
candidates = {
    "M1-C": {
        "name": "Native SPacketChunkData Vanilla Payload",
        "server_thread_ms": 0.06, # 0.04 to 0.08 ms steady state (was misattributed 0.72 ms)
        "tail_latency_ms": 0.45,  # 0.40 to 0.50 ms login/worldgen burst (was misattributed 3.5-5.8 ms)
        "raw_scores": {
            "value": 5.8,         # Downgraded from 9.2 due to small net delta (~4.3 µs/chunk net after input prep)
            "safety": 10.0,       # Ephemeral network wire data, zero world corruption risk
            "reversibility": 10.0,# Instantaneous per-packet fallback to Java constructor
            "ffi_granularity": 8.5,# Coarse 1 call/chunk, but requires input staging buffer or primitive array handles
            "parity_testability": 10.0, # Exact byte-for-byte differential oracle
            "learning_value": 9.5,# Proves JNI direct buffer, Netty ByteBuf lifecycle, shadow differential testing
            "alloc_reduction": 7.0,# Eliminates byte[] allocation inside SPacketChunkData (~15-25 MB/s)
            "simplicity": 8.5     # Narrow scope (vanilla section payload only, TEs remain in Java)
        },
        "notes": "Still leading candidate due to unparalleled safety (10/10), reversibility (10/10), and testability (10/10). Real net saving is ~4.3 µs/full chunk."
    },
    "M1-A": {
        "name": "SAVE-A Vanilla Chunk Snapshot Extractor",
        "server_thread_ms": 0.00, # 0.0 ms in steady state
        "tail_latency_ms": 11.70, # 11.7 ms on 900-tick autosave spike
        "raw_scores": {
            "value": 8.5,         # High autosave spike relief (11.7 ms every 45s)
            "safety": 6.5,        # Downgraded: touches persistent world storage (.mca files); corruption risk
            "reversibility": 8.0, # Can fall back to Java writeChunkToNBT
            "ffi_granularity": 9.0,# 1 call per chunk during save
            "parity_testability": 8.0,# NBT tag tree parity comparison
            "learning_value": 8.5,# Off-heap snapshot memory design
            "alloc_reduction": 7.5,# Reduces NBT compound churn during save
            "simplicity": 7.0     # Must safely read BlockStateContainer and construct NBT tags
        },
        "notes": "High tail value on autosave ticks, but carries persistence risk for an M1 first migration."
    },
    "M1-B": {
        "name": "SAVE-F/G Native NBT & Deflate Encoder",
        "server_thread_ms": 0.00, # 0.0 ms (executes on File IO Thread)
        "tail_latency_ms": 0.00,  # 0.0 ms on ServerThread (File IO queue throughput only)
        "raw_scores": {
            "value": 4.0,         # Downgraded: Section 0 proved this runs 100% off-thread on File IO Thread
            "safety": 8.0,        # Pure encoder, but affects save file integrity
            "reversibility": 8.5, # Fallback to CompressedStreamTools
            "ffi_granularity": 8.0,# 1 call per chunk
            "parity_testability": 9.5,# NBT binary stream roundtrip
            "learning_value": 7.5,# Native libdeflate / NBT encoder
            "alloc_reduction": 8.0,# Removes large byte[] buffers on File IO Thread
            "simplicity": 8.0     # Well-defined codec
        },
        "notes": "Pure background I/O optimization; does not reduce ServerThread compute MSPT."
    },
    "M1-D": {
        "name": "Collision / AABB Acceleration",
        "server_thread_ms": 1.40, # 1.2 to 1.8 ms
        "tail_latency_ms": 2.10,  # 2.1 ms
        "raw_scores": {
            "value": 7.8,         # Substantial steady state compute
            "safety": 5.0,        # Mod blocks override getCollisionBoundingBox; high risk of desync/fall-through
            "reversibility": 7.5, # Fallback to World.getCollisionBoxes
            "ffi_granularity": 4.5,# Poor: chatty reverse JNI callbacks for non-vanilla blocks
            "parity_testability": 6.0,# Floating point / bounding box list order differences
            "learning_value": 7.0,# Spatial acceleration structures
            "alloc_reduction": 8.0,# Eliminates AxisAlignedBB churn
            "simplicity": 4.0     # Complex voxel traversal and reverse callbacks
        },
        "notes": "High compute, but unacceptable FFI chattiness and mod block compatibility risk."
    },
    "M1-F": {
        "name": "Vanilla Worldgen Noise Helpers",
        "server_thread_ms": 0.00, # 0.0 ms in base workloads
        "tail_latency_ms": 6.50,  # Worldgen exploration only
        "raw_scores": {
            "value": 6.0,         # Zero benefit in base workloads; modgen dominates exploration (49.4%)
            "safety": 7.0,        # Worldgen terrain differences alter chunk seeds
            "reversibility": 8.5, # Fallback to Java noise generators
            "ffi_granularity": 9.0,# Coarse per-chunk noise generation
            "parity_testability": 7.5,# Floating-point parity with Java Perlin noise
            "learning_value": 7.0,# Math / SIMD noise acceleration
            "alloc_reduction": 4.0,# Modest allocation reduction
            "simplicity": 6.5     # Octave noise math port
        },
        "notes": "Benefits only exploration; mod generators dominate worldgen compute."
    },
    "M1-G": {
        "name": "Engine Allocation Reduction",
        "server_thread_ms": 0.90, # 0.8 to 1.2 ms
        "tail_latency_ms": 1.50,  # GC pause reduction
        "raw_scores": {
            "value": 6.5,         # Modest steady-state benefit via GC reduction
            "safety": 5.5,        # Invasive changes across core engine call sites
            "reversibility": 7.0, # In-JVM refactor, hard to toggle dynamically
            "ffi_granularity": 3.0,# Not an FFI seam; purely in-JVM Java work
            "parity_testability": 7.0,# Behavioral testing
            "learning_value": 4.0,# Teaches zero native FFI / migration patterns
            "alloc_reduction": 9.0,# Directly targets BlockPos / Vec3d churn
            "simplicity": 4.5     # Touches thousands of lines in World / Chunk
        },
        "notes": "In-JVM refactoring task, not a Rust migration seam."
    },
    "M1-E": {
        "name": "Lighting Propagation Engine",
        "server_thread_ms": 2.20, # 2.0 to 3.0 ms
        "tail_latency_ms": 5.00,  # 5.0 ms
        "raw_scores": {
            "value": 8.0,         # Substantial lighting compute
            "safety": 3.0,        # Catastrophic conflict with Phosphor Mixins; severe corruption risk
            "reversibility": 5.0, # Complex state rollback
            "ffi_granularity": 6.0,# Boundary updates across chunk borders
            "parity_testability": 5.0,# Phosphor vs Vanilla lighting divergence
            "learning_value": 8.0,# Complex spatial mutation algorithms
            "alloc_reduction": 5.0,# Modest allocation reduction
            "simplicity": 3.0     # Highly complex BFS lighting propagation
        },
        "notes": "Fatal conflict with Phosphor mixin used in modpacks."
    }
}

weights = {
    "value": 0.25,
    "safety": 0.20,
    "reversibility": 0.15,
    "ffi_granularity": 0.10,
    "parity_testability": 0.10,
    "learning_value": 0.10,
    "alloc_reduction": 0.05,
    "simplicity": 0.05
}

results = []
for cid, data in candidates.items():
    raw = data["raw_scores"]
    weighted = sum(raw[dim] * weights[dim] for dim in weights)
    results.append({
        "id": cid,
        "name": data["name"],
        "weighted_score": round(weighted, 2),
        "raw_scores": raw,
        "server_thread_ms": data["server_thread_ms"],
        "tail_latency_ms": data["tail_latency_ms"],
        "notes": data["notes"]
    })

results.sort(key=lambda x: x["weighted_score"], reverse=True)

print(f"{'Rank':<4} {'ID':<6} {'Weighted':<10} {'Server(ms)':<12} {'Tail(ms)':<10} {'Safety':<8} {'Revers':<8} {'Candidate Name'}")
print("-" * 90)
for rank, r in enumerate(results, 1):
    raw = r["raw_scores"]
    print(f"{rank:<4} {r['id']:<6} {r['weighted_score']:<10.2f} {r['server_thread_ms']:<12.2f} {r['tail_latency_ms']:<10.2f} {raw['safety']:<8.1f} {raw['reversibility']:<8.1f} {r['name']}")

with open("machine/p0-9-candidate-selection.yaml", "w") as f:
    yaml.dump({"candidates": results, "weights": weights}, f, default_flow_style=False, sort_keys=False)
print("\n-> Updated machine/p0-9-candidate-selection.yaml")
