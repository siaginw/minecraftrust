# M2 Candidate Discovery (pre-implementation, 2026-09-20)

Status: DISCOVERY ONLY. M1 is PARKED (experimental, default OFF,
whole-server benefit not demonstrated; implementation and tests preserved;
unmet gates remain unmet). No new benchmark was run for this document —
it uses ONLY already-committed, valid evidence. Every P0-8-era pack-level
number is excluded (invalidated; the seven affected docs now carry
SYNTHETIC banners as of commit 8678ccc).

## Evidence base actually available

| Source | What it measures | Class |
|---|---|---|
| M1I/M1J MSPT histograms | Real Revelation/SevTech ServerThread compute ticks, phase-resolved (idle/burst/streaming), n=8+10 matched runs | MEASURED |
| M1I/M1J GC logs | Real-pack young-gen allocation rates, GC counts/pauses, GCLocker/safepoint events | MEASURED |
| M1 m1 counters | Per-packet native path costs on real data (staging 10-11 µs etc.) | MEASURED |
| P0-2/P0-3/P0-4 vanilla research | Vanilla-server tick pipeline, autosave thread topology, NBT codec microbenchmarks | MEASURED (vanilla reference, pre-modpack era; not invalidated) |
| P0-8/P0-9 pack attributions & seam scores | — | INVALIDATED/SYNTHETIC — not used |

**Known gap, stated plainly:** no method-level CPU or allocation profile
of a real modpack server exists anywhere in the repository. Tick
histograms can bound WHERE (which phase) time goes, not WHAT consumes it.
No ranking is invented from them.

## Candidate hypotheses (max three, with what is and is not established)

### H1. Streaming-phase ServerThread work bundle (chunk load + relight + entity/TE tick composite)
- Measured magnitude: idle→streaming adds **~7–9 ms/tick on Revelation**
  (1.0–1.5 → 8.5–10.7 ms mean) and **~11–16 ms/tick on SevTech**
  (1.2–1.5 → 12.1–17.4 ms) — the single largest measured ServerThread
  consumer on real packs (M1I/M1J).
- Frequency: continuous during exploration; the dominant real-workload phase.
- Engine-owned vs mod-owned: UNKNOWN — no attribution (this is the gap).
- Plausible removable work: unknown until attributed; chunk-load path
  (anvil decode + light propagation + block-state resolution) is
  engine-owned and coarse-grained (per-chunk), hence Rust-compatible in
  principle; mod hooks fire inside it.
- Compatibility/data-transfer: per-chunk coarse boundary (like M1), but
  writes touch live world state — materially riskier than M1's read-only
  packet path. Requires its own ownership ladder.

### H2. Young-generation allocation churn
- Measured magnitude: **98–174 MB/s sustained** (Revelation streaming;
  M1J runs 1–6 vs 7–10 drift band) and **149–700 MB/s** on SevTech
  streaming (M1I), driving 9–54 young GCs per 150 s phase with 130–970 ms
  cumulative pause per phase; GCLocker-initiated collections occur in
  vanilla too (measured in both arms).
- Frequency: continuous; every GC pause is deadline-relevant at 20 tps.
- Removable work: allocation-site elimination or pooling — sites NOT
  attributed on real packs (JFR allocation sampling missing).
- Engine/mod split: unknown; Revelation/SevTech churn is dominated by
  mod object graphs by volume, but engine paths (chunk packets, NBT,
  block updates) contribute measurably (M1 offline corpus sized the
  packet share at ~µs/packet).
- Compatibility: allocation reduction inside Java code paths needs no
  ownership change; Rust-side buffering (as M1 demonstrated) can remove
  intermediate garbage where a coarse boundary exists.

### H3. Autosave-time NBT serialization on the ServerThread (vanilla-measured, modpack-unverified)
- Measured magnitude: only on the vanilla reference (P0-2 thread-topology
  finding, P0-4 codec microbenchmarks): periodic autosave builds chunk
  NBT in memory on the ServerThread while disk IO proceeds asynchronously
  on the File IO Thread (project hard-won knowledge; never re-measured on
  a modpack). On Revelation/SevTech with large modded NBT trees this is
  plausibly significant and bursty — magnitude UNKNOWN on real packs.
- Frequency: every autosave interval.
- Engine-owned: yes (vanilla save pipeline; mods only attach extra tile
  data). Coarse per-chunk data boundary (P0-4 built and benchmarked a Rust
  NBT codec already).
- Compatibility risk: saves must remain bit-stable on disk (region-file
  consumers); M1-A "chunk snapshot extractor" was the qualitative P0-9
  runner-up (scores themselves synthetic — not cited).

## Missing evidence (single list)
1. Method-level CPU profile of Revelation and SevTech under the streaming
   workload (who consumes the 7–16 ms).
2. Allocation-site profile of the same runs (who allocates the 100–700 MB/s).
3. Autosave-burst tick-cost measurement on a real modpack.

## Proposed ONE bounded profiling capture (for approval; not executed)
A single **Revelation OFF-mode run (~12 min)** with OpenJFR (available on
Temurin 8u504, verified: `-XX:+PrintFlagsFinal` shows FlightRecorder):
boot → settle → the standard 150 s TP-hop streaming phase; JFR method
sampling at 10–20 ms + allocation sampling enabled from JVM start;
recording saved to `machine/raw/`. Analysis offline (jfr tool). No server
code changes, M1 untouched, OFF mode, one JVM, no campaign. This single
capture addresses missing-evidence items 1–3 simultaneously (autosave
fires within any 12-minute window). SevTech would follow only if the
Revelation attribution warrants it.

## Recommended next action
Approve the one bounded JFR capture above; rank M2 candidates only from
its output plus the existing measured base. No M2 implementation work is
authorized or started.
