# Retained native world-generation fusion experiment (H10)

Status: bounded, non-authorizing prototype. This experiment measures **actual pinned Java noise plus a newly specified synthetic operation**, not Minecraft or Forge chunk generation. The final machine receipt is `machine/architecture-hardening/h10-worldgen-fusion-final-01.json`; its raw per-fork samples, command returns and source/tool hashes are authoritative. No production core, FFI, callback hooks, defaults, storage, gates or root dependency manifests are changed.

## What executes

The Java lane calls the actual `NoiseGeneratorOctaves` and `NoiseGeneratorImproved` definitions loaded by the pinned Forge 14.23.5.2860 LaunchClassLoader, after bounded offline FML qualification. A passive JVM definition observer captures their exact final bytes. An SRG-remapped jar exists only to compile the harness and is deliberately absent from the runtime classpath. No server, world, EULA or normal game loop starts. Runtime inputs and transformed classes stay under `target`.

The Rust lanes call the existing `crates/worldgen-noise::Octaves` unchanged. That source describes a vanilla port, so its historical authorship/licensing is not newly certified here. The existing `FieldParity.java` and `TerrainParity.java` contain shared/translated reference assembly and are excluded from the oracle. No decompiled terrain assembly was translated for this experiment. `PROVENANCE.json` records exact runtime noise pins and the isolated JNI dependency (`jni=0.22.4`, MIT OR Apache-2.0); the runner verifies every registry archive and extracted dependency source file against the lockfile before and after execution.

The independently specified operation is:

1. Construct two retained noise generators in order from one Java-compatible seeded RNG, with four and three octaves.
2. Generate fields A and B over a 16 × H × 16 volume, with H ∈ {1,17,32}. A uses scales (0.125,0.25,0.125); B uses (0.0625,0.125,0.0625). Coordinate order is x,z,y, with y innermost.
3. For each cell, evaluate, in the written binary64 order, `first=A*0.125`, `second=B*0.0625`, `combined=first+second`, `biased=combined+double(floatShift)`, `density=biased-double(y)*0.25`.
4. Classify density > 0 as synthetic runtime ID 70000; otherwise y < 8 as 0xF0000001; otherwise 0. These are **unbound synthetic u32 IDs**, not a claim that the Clean registry contains these states. Java `int[]` transports the exact bits, including the high bit.
5. Run the explicit Java completion callback, publish the synthetic Java array only after success, and traverse the final array for a consumed hash.

Java composition is `strictfp`; Rust uses ordinary ordered operations without `mul_add`, fast math or narrowing. The actual Java noise class retains its original FP flags, and only observed raw-bit equality on the pinned JVM is claimed. [Java 8 FP-strict semantics](https://docs.oracle.com/javase/specs/jls/se8/html/jls-15.html#jls-15.4) motivates this boundary.

## Lanes and accounting

| Lane | Actual work per admitted pure cluster | JNI calls | Explicit copied payload |
|---|---|---:|---:|
| Java | Actual runtime noise, synthetic Java density/classification, callbacks/publication/hash | 0 | 0 JNI bytes |
| Separated Rust | Existing noise kernels → two Java double arrays → Rust composition → Java state array | 2 | 16N bytes get + 20N bytes set |
| Fused Rust | Existing noise kernels + synthetic composition, intermediate arrays kept within Rust → Java state array | 1 | 4N bytes set |

For H=32, N=8192: separated copies 294,912 bytes per operation; fused copies 32,768 bytes. These are exact counts of explicit JNI array-region payload transfers, derived from checked call lengths, not measured DRAM traffic. They exclude JVM hidden copies, metadata, collector movement and allocator overhead. The region-call durations are measured inside JNI and include JNI API work; total Java wall time includes orchestration, allocations, callbacks, JNI, result materialization, publication and the consumed final hash. [JNI array-region specification](https://docs.oracle.com/javase/8/docs/technotes/guides/jni/spec/functions.html) defines the transfer operations.

The native vector payload ledger records 40N requested element bytes per separated operation and 24N per fused operation. It is **not allocation count, allocator capacity, peak RSS or all allocated bytes**. Java payload allocation is 20N bytes in the Java and separated paths, 4N in the fused path, excluding array headers, callback objects and other VM data. No zero-copy, allocation-free or general JNI latency claim follows.

Generators are retained across timed operations in all lanes; construction and process/FML startup are outside the steady-state interval and recorded as setup commands, not folded into a cold-generation claim. Fused native results remain only diagnostic scratch, with at most 16 sessions and 8192 cells each. They confer no ownership/capability and do not integrate with H6 retained chunks. Old scratch is replaced only after successful array transfer and is discarded if a later callback throws. Native handles are bounded monotonic tokens and never reused; there are no raw pointers or direct buffer lifetimes.

## Observable boundaries and failures

Synthetic callbacks run before and after each cluster and consume Java RNG in a recorded order. A middle observer or mutator is a fence: direct fused admission refuses it; the orchestrator selects the separated path, exposes both fields and honors mutations. A callback exception preserves the previously published Java result and discards diagnostic retained native output; callback RNG consumption is not rolled back. This is an executable **synthetic callback contract**, not proof that all real Forge callbacks were inventoried or instrumented.

Forge exposes terrain replacement and generator hooks, as shown in [TerrainGen](https://github.com/MinecraftForge/MinecraftForge/blob/1.12.x/src/main/java/net/minecraftforge/event/terraingen/TerrainGen.java) and the [overworld generator patch](https://github.com/MinecraftForge/MinecraftForge/blob/1.12.x/patches/minecraft/net/minecraft/world/gen/ChunkGeneratorOverworld.java.patch). An actual admitted fusion cluster must stop at each qualified observable boundary, bind generator implementations and RNG ordering, and refuse unknown replacements. These online bindings are absent here.

Controls cover wrong epoch, missing/stale/destroyed handles, non-reused handles, 16-session capacity, unsupported/negative height, null/short arrays, aliasing noise output arrays, nonfinite bias/fields, retry after rejection, callback order/RNG/mutation, exception publication rollback and native scratch discard. JNI runs with `-Xcheck:jni`. Null inputs surface Java exceptions, never a successful result. The process timeout kills the launched foreground child; Java heap is bounded to 512 MiB. This is not an adversarial process-memory sandbox or concurrent lifecycle qualification.

The H5 version adapter test builds an explicit 70,001-entry synthetic semantic registry, maps dense ID 70000 to wire ID 3 and a separate persistence identity, and verifies wrong-epoch and unmapped ID 0xF0000001 rejection. This proves the boundary refuses implicit casts; **the measured pipeline itself has no live registry/version mapping**. Its synthetic output cannot be sent or persisted as Minecraft state.

## Evidence and measurement policy

The correctness corpus covers five seeds (including signed extremes), zero/negative/near-world-border coordinates, and the three heights. Each run compares A, B and density plus separated A/B at raw binary64 bit precision: 960,000 values across 45 cases. Both state arrays are compared exactly. A deliberate double→float→double control records the first changed bit pattern and counts changed values whose classification is unchanged. Any unexpected floating/state mismatch retains `FIRST-DIVERGENCE.json` and full paired raw binary arrays before failure; it is never normalized away.

The final campaign uses five fresh JVMs, 40 warmup operations per lane per fork, then six cyclically ordered sample groups of 30 operations per lane. Raw samples retain total duration, all exact copy ledgers, compute/region timer spans and consumed output hashes. A sample group must have equal output hashes across all three lanes. Cross-process startup and JIT conditions are visible; this is one Windows host, not an exclusive-machine or broad-hardware claim. The correctness-only exploratory receipt is retained separately.

Reproduction from the isolated checkout:

```powershell
C:/Python314/python.exe tools/worldgen-fusion-experiment/run.py --output target/architecture-hardening/h10-final-01 --forks 5 --rounds 6
```

The runner requires a fresh output directory, pins all runtime jars, Java8 tool binaries, actual Rust tools, local source dependencies and compiled class outputs, checks drift after execution, rejects incomplete result schemas and preserves every command's return code/output length/hash. Scoped formatting and strict clippy apply to this experiment; existing warnings in unchanged worldgen-noise remain visible.

Claim audit:

| Claim | Classification |
|---|---|
| Raw floating/state/callback parity for the recorded corpus | MEASURED |
| Per-lane bounded-operation durations and JNI-region durations | MEASURED |
| Explicit array payload bytes from executed lengths/crossing counters | DERIVED_FROM_MEASURED |
| Terrain composition and synthetic callbacks/registry | SYNTHETIC |
| Speedup of complete Minecraft/Forge chunk generation | NOT_MEASURED; BLOCKED |
| Production benefit or real mod callback coverage | Not established; no projection is offered |

Complete chunk-generation effect cannot be reported from this corpus. It omits biome generation, real terrain interpolation/surface replacement, caves, structures, population/decorators, mod handlers, chunk integration, lighting, scheduled work and persistence. The next experiment must capture an admitted actual complete generation operation, retain all observable outputs/callbacks/RNG and end-to-end timing, then substitute only proven pure clusters. Until that evidence and the ownership/certificate bindings exist, native production authority remains disabled.
