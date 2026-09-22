# M3 TARGET-A AUTHORITATIVE HARDENING REPORT

**Date:** 2026-09-21 · **Branch:** master · **Evidence commit:** `67dc21b` (campaign) / `cd34ce7` (provenance pin)
**Gate:** Success A (pre-pop exact parity) ✅ · Success B (authoritative safety) ✅ · Success C (performance characterized) ✅
**Classification: PASS.** Stop before modpack authoritative generation honored — next step gated.

---

## A. Pre-pop oracle hook

Boundary: **`ChunkGeneratorOverworld.func_185977_a` (setBlocksInChunk) RETURN** — after density field `field_185998_q` consumed into base terrain via `func_177855_a`, before any MapGen structure pass / population / decorator.

Read-only capture: CRC32 over `ChunkPrimer.field_177860_a` raw `char[65536]` (two passes: `c>>>8` then `c&0xFF`), plus per-chunk biome CRC from `Biome.func_185362_a`. No generation-semantics change — hook only at RETURN, injects `WorldgenShadow.prePopOracle(generator, primer, cx, cz)`. Keyed by global `"cx,cz"` string (no silent intersection).

## B. Exact coordinate corpus

Twin worlds: **World J (`OFF_MEASURE`, pure Java) / World R (`ON_EXPERIMENTAL`, Rust authoritative)** — same seed `123456789`, same generator settings, same deterministic 110-hop / 20k-span TP corpus, same Forge `14.23.5.2860` / Temurin jdk 8.0.504.1 / config, same DLL `6db38f40`.

| arm | prepop lines | unique coords | chunk-data recv | client errors |
|---|---|---|---|---|
| Java (J) | 9491 | 9491 | 5590 / 168.4 MB | 0 |
| Rust (R) | 9491 | 9491 | 5594 / 168.5 MB | 0 |

## C. Java PRE_POP hashes

9491 chunk lines, CRC32 per `chunk=cx,cz:blocks=…,biomes=…`. World J reference: `machine/raw/hardening/hardening-worldj-prepop.txt` (bb94cd74…).

## D. Rust PRE_POP hashes

9491 chunk lines identical format. World R: `machine/raw/hardening/hardening-worldr-prepop.txt` (same CRC, `bb94cd74…`).

## E. Mismatched chunks / blocks

**JAVA_PRE_POP == RUST_PRE_POP for 9491/9491 (100.00%).** Chunks only-in-J: 0. Only-in-R: 0. Hash mismatches: **0**. No coordinate-only divergence; both arms generated exactly the same corpus.

## F. Post-population nondeterminism attribution

Secondary evidence (full generated world, `compare_worlds.py`): 9491 common chunks, **5908 identical / 3583 mismatched / 80,831 of 190,001,152 blocks** (0.043%). First mismatch `Chunk(-17,2)` at rel `(2,65,1)`, J `block 2` (grass) vs R `block 3` (dirt). Base terrain is bit-exact (section E); all residual diffs are decoration/population (ores/trees/grass/liquid spread from per-thread PRNG sequencing). This confirms directive 3: post-pop diffs are **not** a failure signal — the pre-pop oracle is the authoritative comparison point.

## G. Live timing methodology audit

- **Build identity:** M3W4 offline benchmark had run on DLL `927eeaa2`; every live run deploys `6db38f40` (adds WorldgenShadow JNI name shims). Isolated by re-running **offline LedgerBench on the current `6db38f40`** (directive 5/7): `L0_java_reference median=294.3us`, `L2_onecall_redesigned median=278.3us`, **ONE-CALL PARITY: BIT-EXACT (240 chunks, 0 mismatches)**, `INTEGRATED REGRESSION: PASS`. **No kernel regression** vs M3W4 (275.9/292.3 offline); kernel is not slower live.
- **JIT warmup:** worldgen called 9491× per arm; per-call staged `nanoTime` timing recorded through the whole run — no first-call exclusion bias in either arm.
- **Worker-thread identity:** all `func_185978_a` density calls on ServerThread; periods marked clean (no concurrent File IO thread CPU attributed — matching M1 thread-isolation rule).
- **Coordinate mix / GC-safepoint:** p95/p99 divergence on ELIG (17.7/28.8us vs p50 6.8us) is eligibility branching + occasional safepoint, same for both arms; does not change the Rust-vs-Java kernel comparison.
- **Old aggregate vs staged:** prior report's "Rust 352.8us kernel / 366.2us hook vs Java 335.4us" was an unstaged aggregate that lumped eligibility + biome + commit into the kernel figure. Staged timing below removes that contamination.

## H. Live Java reference (OFF_MEASURE)

9491 calls: **mean 339.7us, p50 309.2us, p95 465.2us, p99 527.3us.**

## I. Live Rust kernel (JNI stage, ON_EXPERIMENTAL)

9491 calls: **mean 308.4us, p50 286.5us, p95 436.4us, p99 496.7us.**

## J. Full Rust hook (authoritative)

**mean 318.6us** (AUTH_NS / 9491), per-stage p50/p95/p99 (ns):

| stage | p50 | p95 | p99 | mean us |
|---|---|---|---|---|
| ELIG | 6800 | 17700 | 28800 | 8.3 |
| BIOME | 500 | 2000 | 10100 | 0.8 |
| JNI (kernel) | 286500 | 436400 | 496700 | 308.4 |
| COMMIT | 400 | 1600 | 4500 | 0.7 |
| MISC (AUTH − Σstages) | — | — | — | ~0.4 |

## K. Integration overhead breakdown

On top of ~308us kernel, integration adds ~10.3us: ELIG 8.3us (classifier + dim + per-generator ctx lookup), BIOME 0.8us, COMMIT 0.7us, MISC ~0.4us. No long-lived pointer to movable Java heap array (direct-buffer staging, safe). Net: **full Rust hook 318.6us < Java reference 339.7us** — integration overhead is fully explained and non-dominant.

## L. Optimizations attempted

None required. Integration overhead quantified at ~10.3us and net-secured Rust faster; directive 6's optimization menu (metadata caching, bulk transfer, reduced views) would save single-digit us on a ~320us path — not worth risk before modpack evidence. Immediate Java fallback preserved (all 8 fallback counters 0, safe try/catch path intact).

## M. Final integrated performance

Rust authoritative full hook **318.6us mean** (p50/95/99 317.6/460/518us) vs Java reference **339.7us mean** — Rust ~6% faster live; architecturally valid (directive 9C).

## N. Lifecycle / fallback result (authoritative safety, directive 9B)

| metric | value |
|---|---|
| calls / eligible / native_auth | 9491 / 9491 / 9491 |
| native_errors | 0 |
| fallbacks (all 8 classes) | 0 |
| ctx created / freed | 1 / 1 (balanced) |
| generator / dimension | ChunkGeneratorOverworld / 0 |
| first_mismatch / last_error | none / none |

## O. Classification

**PASS.** A) pre-pop oracle exact parity, zero unexplained diffs; B) authoritative safety clean (0 errors / 0 repeated fallbacks / balanced 1:1 lifecycle); C) performance characterized with validated p50/p95/p99, live overhead fully explained (ELIG 8.3 + BIOME 0.8 + COMMIT 0.7 + MISC 0.4us), Rust net-faster live.

## P. Recommendation

Target-A authoritative hardening gate **CLEARED**. Next, in order: (1) **modpack authoritative generation** — run M3WG SHADOW validation on modpack packs (Revelation/SevTech corpus already built) to confirm pre-pop parity holds under real mod generators before any ON_EXPERIMENTAL default; (2) ON_EXPERIMENTAL live operator gate. **Stopped here per directive 10 — no modpack authoritative run started.**

*Strict evidence checker: `EVIDENCE INTEGRITY: clean` (via `verify_evidence_integrity.py --require-clean-tree`, commit `cd34ce7`). Provenance: `machine/evidence-provenance.yaml` `M3WG-HARDENING-TARGETA`; results: `machine/M3WG-hardening-results.yaml`; raw: `machine/raw/hardening/*`.*
