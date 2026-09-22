# RustCraft (minecraftrust)

A progressive, behavior-compatible reimplementation and optimization of Minecraft Java Edition **1.12.2** engine work in **Rust**, retaining real Java/Forge integration where legacy mods require it.

- **Target:** Minecraft 1.12.2, Protocol 340, Forge 14.23.5.x (tested builds `14.23.5.2846` / `14.23.5.2860`)
- **Philosophy:** SAME GAME · BETTER IMPLEMENTATION · MAXIMUM PRACTICAL RUST ENGINE OWNERSHIP

**This is NOT:** a completed standalone Minecraft replacement; a modern-version server with automatic Forge compatibility; gameplay-changing shortcuts; a promise that every mod works.

> **Status: experimental research project.** All native paths default **OFF**; Java is authoritative everywhere with immediate, counted fallback. The live retained-snapshot path is **fail-closed** pending the capture-coherency contract — see [issue #1](https://github.com/siaginw/minecraftrust/issues/1).

---

## Architecture

```
+-------------------------------------------------------------------+
|  Java / Forge 1.12.2  (authoritative, mod-visible)                |
|  chunks - packets - events - TileEntities - Netty pipeline        |
|  worldgen hooks (coremod ASM) - mutation hooks (6 sites)          |
+-----------------------+-------------------------------------------+
                        |  coarse JNI boundaries (buffers + handles)
+-----------------------v-------------------------------------------+
|  Rust (rustcraft_ffi)                                             |
|  worldgen-noise kernels - Protocol-340 encoder                    |
|  zlib compression (M2-C) - NativeChunk/NativeSection snapshots    |
|  registry - lifecycle - generation handles                        |
+-----------------------+-------------------------------------------+
                        |  fallbacks + feature gates at every seam
   OFF -> OFF_MEASURE -> SHADOW (Java transmits, Rust verified)
       -> ON_EXPERIMENTAL (bounded, disposable worlds only;
          live snapshot authority currently FAIL-CLOSED: issue #1)
```

Ownership levels actually implemented: **native computation** (kernels, codecs — proven); **immutable/native snapshots** (NativeChunk registry — validated); **experimental retained-state coherency** (hooks + freshness domains — **blocked**, see issue #1); **authoritative loaded-world ownership** — *not implemented*.

## Milestones

| Area | Status | Evidence |
|---|---|---|
| Java↔Rust interop, contexts, panic isolation | IMPLEMENTED + SHADOW_TESTED | `crates/ffi`, `docs/research/` |
| M2-C native outbound compression | IMPLEMENTED · SHADOW_TESTED · EXPERIMENTALLY_TRANSMITTED (Target A; modpack SHADOW 146,259/0) | `machine/M2CC-modpack-shadow-results.yaml` |
| Deterministic worldgen density | SHADOW_TESTED bit-exact (clean Forge + Revelation + SevTech; 3.4M+ doubles, 0 mismatch) | `machine/M3WG-*-results.yaml` |
| Base terrain (Stone/Water/Air) | SHADOW_TESTED bit-exact | `docs/research/m3w5-chunkprimer-ownership-report.md` |
| NativeChunk / NativeSection snapshots | IMPLEMENTED · OFFLINE_VALIDATED | `docs/research/m4-native-chunk-state-foundation-report.md` |
| Generalized palettes (4–13 bit) | OFFLINE_VALIDATED 26/26 vs real vanilla `BlockStateContainer` | `docs/research/m4-1-report.md` |
| Native packet encoding (Protocol 340) | OFFLINE_VALIDATED · SHADOW_TESTED (1,572/1,572 live) · formerly EXPERIMENTAL, now FAIL-CLOSED | `machine/M43E-readiness-results.yaml` |
| Combined encode+compress wire | SHADOW + formerly bounded EXPERIMENTAL (Target A) | `machine/M53-results.yaml` |
| Live mutation coherency | SHADOW_TESTED · **BLOCKED** (issue #1; production entry fail-closed) | `machine/M58HOLD-results.yaml` |
| Test/evidence infrastructure | IMPLEMENTED (offline suites, integrity checker, provenance registry) | `tools/`, `machine/evidence-provenance.yaml` |
| External library evaluation | RESEARCH_ONLY | `docs/research/P*.md` |

## Performance (verified, scoped)

Windows x64 dev box, JDK 8 (Temurin 8.0.504), Rust release. Kernel/offline scope unless noted.

| Operation | Java ref | Rust | Scope / caveats | Artifact |
|---|---|---|---|---|
| Base-terrain placement (M3W5 kernel) | 23.95 µs | **15.69 µs (1.53×)** | offline component benchmark, bit-exact | `machine/M3W5-*` |
| Optimized worldgen noise (n=1) | 1× | **+5.6%** | offline 240-chunk, bit-exact | `machine/M3W4-n1-results.yaml` |
| Chunk-packet encode (31 KB, cached palette) | ~7.2 µs ctor | **~1.0 µs** | warm cache vs offline-corpus Java ctor; NOT a like-for-like claim vs full Java construction under load | `machine/M43-*-results.yaml` |
| Compression throughput (M2CP, Revelation) | 57.5 MB/s | **129.7 MB/s (~2.26×)** | same study: separate Netty-worker CPU reduction 39.9%; compressed size +1.5–2.4% | `machine/M2CP-perf-results.yaml` |
| Section refresh transfer | — | 12 KB in ~2.7 µs | offline, immutable input | `docs/research/m4-1-refresh-design.md` |

Not claimed: whole-server TPS multipliers, zero bugs, universal compatibility, or % completion. Combined-path optimization headroom is open (cached-encode vs rebuild cost, worker CPU vs ServerThread time are kept distinct in the cited reports).

## Current limitations

- Experimental; defaults OFF; Java fallback everywhere. Compatibility limited to tested configurations (clean Forge, FTB Revelation 3.4.0, SevTech: Ages 3.2.3, with pack-specific exclusions).
- **[Issue #1](https://github.com/siaginw/minecraftrust/issues/1):** live NativeChunk capture lacks cross-thread coherence guarantees (worldgen writes biomes/light off-thread); the production native-packet entry is fail-closed until resolved.
- Unresolved section-5 packet-mask event (`java=63 native=31`) — root cause unresolved.
- Unsupported scopes (Java-only, counted): partial-filter packets, TileEntity tag scope, JEID int-backed primers, NEID high-bit states, state ids > u16, unregistered-state sentinels.
- Not implemented natively: storage/Anvil ownership, lighting engine, collision, deeper world ownership.

## Building and testing

Requirements: Rust stable (MSVC), JDK 8, Python 3, Windows x64 (tested). Minecraft/Forge jars are **not bundled** (obtain separately; never committed).

```bash
# From a clean public checkout (verified):
cargo build --release -p ffi          # Rust core + FFI DLL
cargo test -p native-chunk            # standalone Rust unit tests (10 tests)

# Also verified in the research environment (requires Minecraft/Forge jars,
# built bridge, and locally-retained evidence artifacts):
python tools/verify_evidence_integrity.py   # checker relies on intentionally
#    local-only raw artifacts — NOT runnable green from the public checkout
bash tools/build-coremod.sh           # bridge/coremod jar (needs external jars)
bash tools/build-coremod.sh           # bridge/coremod jar (needs external jars)
# Java offline oracle suites (M4PacketParityHarness, M4ValidatorBoundary,
# M4LifecycleCases, M4AuthoritativeTest, ...) require the external jars and
# the built DLL; they are not runnable from the public checkout alone.
```

## Contributing

High-value areas: reproduction fixtures (especially modded-state corpora for issue #1), offline parity tests, profiling, Rust-native optimization of the packet→compression pipeline (immutable fixtures), compatibility analysis, documentation. Read the rubric below before proposing perf claims.

## Roadmap and methodology

Planned frontiers (plans, not features): Anvil persistence staging, lighting, wider-than-u16 native state, per-pack fill-model plugins, combined packet+compression authority after the capture contract lands.

Permanent rubric applied to every candidate:
```
REFERENCE_JAVA -> RUST_PARITY -> PROFILE / EXTERNAL RESEARCH
-> RUST_OPTIMIZED -> COMPLETE BENCHMARK -> PERFORMANCE + MIGRATION VALUE
-> COMPATIBILITY LADDER (offline -> SHADOW -> bounded ON -> modpack)
```
Modest speedups can still justify ownership; a parity loss alone never parks a candidate.

## License and history

Project-owned code by the project authors (commit history preserves authorship). Third-party proprietary material is intentionally absent; licensing of project code is not yet finalized (open decision). This public repository is a **sanitized snapshot** of a larger local research repository (original local checkpoint → public snapshot; per-milestone raw server logs and runtime captures remain local-only with hashes in `machine/evidence-provenance.yaml`).
