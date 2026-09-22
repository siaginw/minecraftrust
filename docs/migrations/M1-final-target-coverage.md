# M1-Final Target Coverage Record (evidence preservation, 2026-09-19)

Status: RECORD ONLY. No acceptance gate was modified to produce this document.
Every deviation from planned coverage is listed explicitly; substitutions are
**proposed for operator approval**, not applied.

## 1. Campaign matrix (all protocol-bot driven; see §3 for what that means)

| Target | Pack | Forge | Mods | FoamFix | Phosphor | SHADOW | ON_EXPERIMENTAL | Paranoid leak run | Evidence |
|--------|------|-------|------|---------|-----------|--------|-----------------|-------------------|----------|
| A | clean control | 14.23.5.2860 | 0 | – | – | 11259/11259 + 10773/10773 (r2) | 2430 + 2187 native, 0 fb | clean (incl. mid-stream disconnect canary, 1053 pkt) | ee1b646, c7e8b91 |
| B | minimal corpus | 14.23.5.2860 | 12 | – | – | 46305/46305, 0 mm/fb | 48510 native, 0 fb | 21168 native, 0 reports | preserved this date |
| C | FTB Revelation 3.4.0 | **14.23.5.2846** | 193 | 0.10.5 active | 0.2.7 active | 10143/10143, 0 mm/fb | 88200 native, 0 fb | 29106 native, 0 reports | preserved this date |
| D | SevTech Ages 3.2.3 | 14.23.5.2860 | 262 | 0.10.10 active | **NOT INSTALLED** | 11007/11007, 0 mm/fb | 88200 native, 0 fb | 28224 native, 0 reports | preserved this date |

All campaigns: HANDOFF-A (`minecraftrust.m1.handoff=A` default in every
`tools/target*-run.sh` / `*-paranoid.sh`; note the CODE default remains `0` —
scripts override, see §6), identical binaries
(`machine/target-binary-provenance.yaml`: coremod `c9120858…` class-identical
rebuild, dll `02c722de…` bit-identical rebuild), Temurin JDK 8.0.504.1.

## 2. Deviations from plan (explicit, not silently absorbed)

1. **Target C Forge .2846, not .2860.** `machine/modpack-targets.yaml`
   specifies .2860; the acquired FTB Revelation 3.4.0 official installer ships
   and boots .2846 (pack-official version, sha256 in
   `machine/targetC/pack-metadata.json`). Kept for pack fidelity. Empirical
   note: transformer fingerprint check passed and byte-parity held on .2846;
   no claim of bytecode identity between the two Forge builds is made.
2. **Target D has no Phosphor.** SevTech 3.2.3 as acquired (CurseForge
   manifest, `machine/targetD/manifest.json`) contains FoamFix 0.10.10 but no
   Phosphor jar; `GATE-COMPAT-D` ("FoamFix and Phosphor Mixins enabled") is
   therefore **NOT satisfied as written**. Proposal for operator decision:
   - (a) add Phosphor to Target D and rerun the D campaign as written; or
   - (b) accept Target C's Phosphor coverage (phosphor-lighting 0.2.7 active,
     shadow 10143 + native 88200) as the mixin-runtime validation and re-scope
     GATE-COMPAT-D to "FoamFix active; Phosphor covered by Target C".
   Neither has been applied.
   
   **AMENDMENT APPROVED BY OPERATOR 2026-09-19**: option (b) — Phosphor
   coverage is documented through Target C (Revelation + Forge .2846 +
   FoamFix + Phosphor); Target D coverage remains SevTech + Forge .2860 +
   FoamFix WITHOUT Phosphor. Phosphor was NOT added to SevTech. The original
   GATE-COMPAT-D text is preserved unchanged; the authoritative scope record
   is `machine/compatibility-scope.yaml`. Not tested and not claimed:
   SevTech+Phosphor, Revelation+.2860.
3. **Target B acquired and tested — no longer blocked.** Earlier project
   history records B as blocked on missing Thermal 1.12.2 assets; the
   installed server contains ThermalExpansion/Foundation, AE2, Iron Chests,
   JourneyMap (12 mods incl. dependencies vs the 5 planned anchors).
4. **Bot output paths.** B/C/D bot JSONL transcripts were written under
   `machine/targetA/` (`client-{b,c,d}-*.jsonl`) because the bot's OUT_DIR is
   fixed. Moved would break evidence pointers; left in place, registered as
   `raw_artifact_secondary`.

## 3. Protocol-bot validation ≠ modded-client gameplay

What the bot (tools/targeta_client.py) actually does per session: full
FML|HS handshake with a REAL ModList payload built from the target's
`mod-ids.json` (modid@version echo, ack state machine per
`machine/fml-handshake.yaml`), JoinGame, receipt and VarInt/zlib decode of the
chunk stream, KeepAlive/PlayerPosLook response, client settings + movement,
observation of BlockChange/chat. Sessions ~8 s, 25–200 per campaign.

What it does NOT do: run any actual client mod (no registry consumption, no
mod channel traffic beyond FML|HS, no gameplay actions, no rendering, no
world interaction by a real player, no hours-long sessions).

Therefore the campaigns prove: **server-side wire correctness of native chunk
payloads against real modded Forge runtimes (classloading, coremods, mixins,
FoamFix/Phosphor-present environments) and client-side syntactic decode.**
They do NOT prove modded gameplay compatibility. GATE-COMPAT-C's "1-hour
active server session" wording remains unmet by bot evidence alone.

## 4. Netty memory safety — retained attribution questions

Validated: paranoid-level leakDetection runs on all four targets over 21k–29k
native packets each with **zero M1-attributable** leak reports (no
`com.rustcraft` frame in any leak stack); Target A additionally tested a
mid-stream disconnect under PARANOID (0 leaks/panics/cross-buffer/use-after-
release; `machine/targetA/run-r2-paranoid2.log`). The Target C paranoid
campaign run contains ONE pack-side ElecCore leak event — see §10.

Retained, NOT declared cleared:
- The large ON_EXPERIMENTAL campaigns (88,200 packets each) ran at DEFAULT
  leak detection; "0 leaks" is claimed only for the paranoid subsets above.
- Netty's detector reports on GC of unreleased buffers; long-duration soak
  (hours, real load) was never performed.
- Final-session bot disconnects (e.g. WinError 10054) coincide with server
  shutdown and are not leak evidence in either direction.
- GATE-PERF-REGRESS (TPS/cadence/GC-pause regression across targets) is
  OPEN — never measured.

## 5. FoamFix adapter: zero-counter investigation → UNTESTED

Observation: `m1_foamfix_adapter_packets=0`, `m1_foamfix_native_packets=0`,
`m1_phosphor_native_packets=0`, and critically
`m1_native_fallback_extractor=0` / `m1_transformer_conflicts=0` on every C/D
campaign despite FoamFix loaded with `deduplicate=true`.

Investigation result (from server logs + code):
- FoamFix 0.10.5/0.10.10 **splices `BlockStateContainer` methods**
  (log: "Spliced in METHOD:
  net.minecraft.world.chunk.BlockStateContainer.func_186018_a") — it does NOT
  replace the container class on these packs.
- The bridge's eligibility check requires
  `bsc.getClass() == BlockStateContainer.class` (`NativeChunkPacket.isEligible`);
  this held for every encoded section, so the native path ran on
  FoamFix-spliced-but-vanilla-class containers and matched Java byte-for-byte
  (shadow parity). Being loaded is not what mattered; class identity was.
- The FoamFix-deduplicated-container code path (adapter counter branch) never
  executed in ANY campaign, and the safe-Java-fallback branch for
  non-vanilla containers was never exercised in production either.

Classification: **FoamFix adapter branch = UNTESTED.** Safe by construction
(non-vanilla container → vanilla Java constructor via
`isEligible` → false → fallback), with counters
(`m1_transformer_conflicts`, `m1_foamfix_adapter_packets`) providing runtime
observability if a container-swapping optimization ever routes packets to
Java. **Follow-up executed 2026-09-19 (operator directive 2):**
`tools/chunk-packet-oracle/.../NonVanillaContainerFallbackTest.java` builds a
real chunk, swaps one section's container for a byte-identical
`Deduplicated…`-named subclass (same BitArray+palette objects — only the
class identity differs), and verifies in ON_EXPERIMENTAL:
populatePacket=false (rejected), fallback reason counters incremented
(`m1_native_fallback_extractor`, `m1_transformer_conflicts`), Java path emits
byte-identical packets both directly and via the post-hook constructor body,
and NO native work occurs (staging bytes, predict and JNI counters all
unchanged — no pointer acquired or retained). 12/12 checks PASS
(`machine/raw/M1F-nonvanilla-fallback-test.txt`, provenance
M1F-NONVANILLA-FALLBACK).

Adapter truth (correcting the M1.3-era description): the current
implementation has NO `extractFoamFixSection()` adapter — that was removed
with the invalidated M1.3 evidence; M1.4 replaces it with the strict
class-identity gate. The `m1_foamfix_adapter_packets` counter is reachable
ONLY from the SHADOW staging path (no isEligible gate there) and is
structurally unreachable in ON mode, which rejects the container class
first. The SHADOW-side counter branch and reflection extraction were
exercised by the same fixture (counter +1, shadow byte-match vs Java
reference). The installed FoamFix versions (0.10.5/0.10.10) splice methods
and never produce a non-vanilla container class, so the adapter naming is
historical; coverage is NOT being invented for it.

## 6. Handoff defaults (documented divergence)

`NativeChunkPacket.HANDOFF_MODE` code default: `0` (HANDOFF-0 double-copy
baseline). Every experimental launch script
(`tools/targeta-run.sh`, `tools/target{b,c,d}-run.sh`,
`tools/target{b,c,d}-paranoid.sh`, `tools/targetc-paranoid-off.sh`) passes
`-Dminecraftrust.m1.handoff="${M1_HANDOFF:-A}"` → **scripts select HANDOFF-A
by default**; no campaign ran HANDOFF-0 except the committed HANDOFF-0
comparison benchmarks (5b0c954/c7e8b91 eras). Runtime mode default remains
OFF (`minecraftrust.native_chunk_packet=OFF`); Java world ownership is
unchanged (`machine/ownership.yaml`: `chunk_packet_encoding: JAVA_OWNED`).

## 7. Performance claim scope (see machine/M1.4-R2-performance-results.yaml)

Pooled eligible-class (24-fixture corpus, ≥8192 B): native p50 4.70 µs vs
Java 6.70 µs = **29.9% lower median packet-path time**. This is NOT a
whole-server speedup, NOT ≥25% on every eligible fixture (range 3.8–37.1%;
7/21 below 25%), and pooled p99 is ~5% WORSE for native (8/21 fixtures
regress at p99). The 8192-byte gate is validated only on this corpus.
GATE-PERF-SERVER / GATE-PERF-ALLOC / GATE-PERF-REGRESS: OPEN (unmeasured).

## 8. Locally-preserved artifacts (gitignored, hashed)

- Worlds + servers + downloads for Targets B/C/D:
  `machine/target{B,C,D}/fixture-manifest.txt` (sha256 inventories),
  `machine/target-binary-provenance.yaml` (tested binary hashes + linkage).
- `fml-hs-capture.txt` (593 MB of FML|HS handshake hex captured during the
  B/C/D campaigns): sha256
  `97a3c51bb3afe2cecf95c691fb71c84568697ae112684624da4c071552ee3ced`,
  regenerable with `tools/targeta_client.py`. WARNING: the capture code
  appends unboundedly — rotate or truncate this file before long campaigns.

## 9. Operator decisions requested

1. Target D Phosphor: option (a) rerun with Phosphor, or (b) re-scope
   GATE-COMPAT-D with C's coverage (§2.2).
2. Whether M1 acceptance closes with GATE-PERF-SERVER / GATE-PERF-ALLOC /
   GATE-PERF-REGRESS left explicitly OPEN as future work, or requires them.
3. Whether the FoamFix-adapter targeted fixture (§5) is scheduled before M1
   closure.

## 10. ElecCore WindowManager leak — reconciled with run-specific artifacts

Earlier observation: a Netty `ResourceLeakDetector reportTracedLeak SEVERE`
report with `elec332.core.inventory.window.WindowManager` stacks during the
Target C paranoid campaign. This reconciles it with the zero-report table:

- **Campaign run (native ON, paranoid)** `machine/targetC/server/run-c-paranoid.log`
  (sha256 8c0445df…): exactly 1 `reportTracedLeak` event; all 5 access
  records are ElecCore login-sync code (`WindowManager$1.onPlayerConnected →
  toBytes → ElecByteBufImpl` writing NBT into an unreleased buffer). Zero
  `com.rustcraft` frames anywhere in the leak stacks. Extract committed:
  `machine/raw/M1F-eleccore-leak-native-ON.txt`.
- **Baseline run (native OFF, paranoid, same pack/scripts)**
  `machine/targetC/server/run-c-leakbase2.log` (sha256 1b9bbb5e…): the SAME
  single ElecCore SEVERE event with identical stacks fires with M1 fully
  disabled. Extract committed:
  `machine/raw/M1F-eleccore-leak-native-OFF.txt`.
- B and D paranoid runs: zero leak events.

Conclusion per the criterion written into `tools/targetc-paranoid-off.sh`
("If ElecCore WindowManager leak still fires -> PACK_BASELINE_UNRELATED_TO_M1"):
**the ElecCore leak is a pack-side bug reproduced identically with M1 OFF —
PACK_BASELINE_UNRELATED_TO_M1, supported by baseline evidence, not assertion.**
Both observations are preserved: (a) the campaign run does contain 1
pack-side SEVERE leak; (b) zero leak reports in any run attribute to M1
paths. The earlier "0 leak reports" phrasing for Target C is corrected to
"0 M1-attributable leak reports; 1 pack-baseline ElecCore event".
Related-but-separate: FML DEBUG "world may have leaked" messages appear
across many runs independent of mode (FML's world-object tracker); Netty
`AdvancedLeakAwareByteBuf` frames in bot-disconnect IOException stacks are a
wrapper artifact of paranoid detection, not leak reports.

## 11. Tail-latency replication + selection-path cost (2026-09-19)

Full record: `machine/M1F-tail-results.yaml` (provenance M1F-TAIL-LATENCY;
raw CSVs `machine/raw/M1F-tail-j{1..4}.csv`; harness
`TailLatencyStudy.java`; driver `tools/tail-latency-study.sh`).

- The M14R2 single-JVM pooled p99 regression (native −5.3%) is **ABSENT in
  replication**: 4 fresh JVMs, balanced java-first/native-first order, all
  show native pooled eligible p99 BETTER (+7% to +38%) and p50 −28.6% to
  −36.1% (median −29.9%, matching the original figure).
- One **reproducible unresolved** fixture tail: det5 (16,419 B) native p99
  worse in all 4 JVMs (−67% to −137%); det1 (38,312 B) worse in 3/4. Not
  dismissed; not declared a gate-relevant regression (pooled tail improves).
- Held-out boundary fixtures (new seeds; gate unchanged): 4,645/5,157 B →
  routed to Java (k1b would have been ~+7% native — the gate is mildly
  conservative in a thin ~4.6–5.2 KB band); 8,248–23,666 B → native-better
  at p50 in all 4 JVMs (thinnest at 8,248 B: +5–20%).
- Selection-path cost measured with the REAL 8192 gate: ineligible packets
  pay **+0.4–1.3 µs p50** (staging + predictOutputLen before falling back to
  the Java constructor) — roughly doubling the tiny-packet path cost today;
  gate cost when routing native ≈ 0. Threshold and encoder UNCHANGED; a
  cheap pre-staging gate is recorded as future work only.
- These are offline packet-path microbenchmarks: no whole-server MSPT/TPS or
  GC inference is permitted from them.

## 12. M1G focused pass (2026-09-19): selection cost + tail diagnosis

Full record: `machine/M1G-results.yaml` (provenance M1G-*).

- **Frozen comparison untouched**: M1F/M14R2 raw artifacts byte-identical to
  their commits (an incidental driver overwrite was reverted from git; the
  driver is now OUT_PREFIX-parameterized). 8192-byte rule and all gates
  unchanged. Tail finding stands as stated: pooled p99 worsening did not
  replicate; det5 worsened in 4/4 JVMs and remains unresolved.
- **Pre-staging gate**: a provably-safe wire-size upper bound from
  O(1)-per-section metadata (bits field, word count, light presence, TE-map
  emptiness) now skips staging+predict for packets guaranteed below 8192.
  Routing-only; the exact predictOutputLen gate still governs everything
  else. 1-written-section selection penalty eliminated (+0.6-0.7 → ~+0.1 µs
  vs pure Java; bnd_k1b within noise). Offline-only multi-section-small class
  (det3) still takes the full path — documented, not hidden. Live A traffic
  produced no sub-8192 packets (pregate_skipped=0), so live skip coverage is
  zero; behavior validated offline (9277-fixture parity, 0 divergence) and
  live parity revalidated (972/972 SHADOW; 486 native ON, 0 fallbacks).
- **det5/det1**: tail localized to the HANDOFF-A heap byte[] allocation phase
  (worst trials alloc-dominated 11/20 and 16/20; alloc p99 4.3/9.3 µs vs p50
  0.8/1.2); JNI/critical-section p99 flat (≤1.1 µs — critical section
  exonerated); NOT GC (3-5 sub-ms GCs per 20k trials; corrected time-base
  correlation 1/25 for det5). Precise slow-path cause UNRESOLVED → retained
  as an open regression risk; no correction made (a fix would change the
  HANDOFF-A design); no fixture-name exclusions introduced.
- Binaries: DLL 02c722de unchanged; validation coremod jar 9343412e
  (campaign jars c9120858 untouched).

## 13. M1H final validation (2026-09-19, FINAL build 9343412e + dll 02c722de)

Full record: `machine/M1H-final-results.yaml` (provenance M1H-*).

- **SevTech (Target D), final build**: SHADOW 11,025/11,025 (0 divergence,
  0 fallback); ON_EXPERIMENTAL 4,410 native (0 fallback, 0 decode errors);
  PARANOID 2,646 native, 0 leak events. FoamFix 0.10.10 active; Phosphor NOT
  installed (per approved amendment); SpongePowered MIXIN 0.8 active.
  Operational notes: 1,151 accumulated bot playerdata files inflated boot
  via Prestige per-player loading (pruned, zero-progression, manifest
  regenerated); Target D uses server-port 25568; Netty binds the port before
  FML finishes, so bot readiness = the run log's "Done (" line.
- **Revelation OFF+PARANOID baseline, final build**: the identical ElecCore
  WindowManager SEVERE leak fires with M1 fully disabled (0 native packets) —
  PACK_BASELINE_UNRELATED_TO_M1 reconfirmed on the final build.
- **Target B**: exact planned anchors were already acquired (Thermal
  Expansion 5.5.7.1/Foundation 2.6.7.1/CoFH, AE2 rv6-stable-7, Tinkers
  2.12.0.135+Mantle, Iron Chests 7.0.67.844, JourneyMap) — NO corpus
  substitution required; final-build SHADOW 2,646/2,646.
- **Live totals across all campaigns**: 104,130 live shadow comparisons,
  0 divergences; 315,567 native ON_EXPERIMENTAL packets, 0 fallbacks,
  0 client decode errors; 0 M1-attributable leak reports.

## 14. M1I whole-server performance closure (2026-09-19/20)

Full record: `machine/M1I-perf-results.yaml` (provenance M1I-WHOLESERVER;
8 matched runs, 2/arm/pack, balanced order, MSPT histograms + GC telemetry,
phase-resolved idle/burst/streaming).

**Verdict: measured-but-unfavorable.** No whole-server compute-MSPT
improvement is resolvable on either pack (the offline ~30% packet-path win
does not move whole-server ticks at realistic packet rates). Allocation
direction conflicts between packs; the 15 MB/s elimination target is not
demonstrated. One open signal: Revelation streaming-phase p50 reads
ON-slower in both balanced pairs (5.1→6.2, 5.4→8.0 ms; arm ranges
non-overlapping at n=2) — flagged as an investigation trigger, not declared
a regression; candidates (staging cost on C's packet mix, Phosphor
interaction, residual variance) unresolved. GATE-PERF-SERVER/ALLOC: NOT MET.
GATE-PERF-REGRESS: not demonstrable clean → stays OPEN.

Method notes: teleport-exploration replaces synthetic walking (vanilla
movement validation rubber-bands synthetic clients — documented); streaming
runs on a pre-generated 600-block corridor per pack so worldgen does not
confound arms (fresh-worldgen A/B impossible on a shared world — gate
aspect remains unexercised); measurement config (RCON + allow-flight,
identical in both arms) documented; MSPT = ServerThread compute via vanilla
tick-time array (~50% tick subsample); SevTech bot playerdata pruned
(Prestige boot inflation) with manifests regenerated; measurement coremod
build `b497c4c3` (adds sampler only — packet path unchanged from `9343412e`
semantics; dll `02c722de` unchanged). Live small-packet routing observed
for the first time on SevTech ON runs (37 pre-gated, 42 Java-routed tiny
packets in i-d1).

M1 remains EXPERIMENTAL, default OFF; default-enable not supported by this
evidence.

## 15. M1J Revelation streaming regression investigation (2026-09-20)

Full record: `machine/M1J-regression-results.yaml` (provenance
M1J-REVELATION-REGRESSION; 10 fresh-JVM runs, 5/arm, strictly alternating,
same world/config/build/workload/instrumentation as M1I).

**Classification: NO_REGRESSION_RESOLVED.** The M1I non-overlap (OFF
5.1–5.4 vs ON 6.2–8.0 ms walk p50 at n=2/arm) disappears at n=5/arm: full
range overlap on every metric (p50 OFF 6.5–10.0 vs ON 5.9–10.2) and all
pairwise ON-better fractions land at 7–18 of 25 — none approaching the
extremes a genuine arm difference produces at this sample size. A
time-drift confound (runs 7–10 ran ~2× allocation/p50 of runs 1–6 in both
arms) was neutralized by the alternating order.

Cause verdicts (all measured, none speculated): staging measured at
~10–11 µs/packet on real Revelation data (whole native path 13.3–17.1
µs ≈ 0.6% of tick — consistent with whole-server unresolvability);
allocation arm medians 106 vs 109 MB/s (coin flip); GCLocker-initiated
young GCs identical across arms (9 vs 8 — Netty's own critical regions
produce them in vanilla too); ON streamed slightly LESS data (289 vs
305 MB) so workload mismatch would have biased ON favorable; no
arm-asymmetric JIT signature in the warmup halves; hook cost bounded
tiny (≤249 Java-routed packets/run).

**Recommendation: KEEP EXPERIMENTAL OFF** — no clear regression or
improvement established in the tested workload; M1 remains a validated,
dormant accelerator. Statistical caveats (final wording, 2026-09-20): the
25 cross-arm pairs are overlap-descriptive, not independent experiments;
the tick-share arithmetic is 59 pkt/s (measured) × 13.3–17.1 µs ÷ 20 tps =
39–50 µs/tick ≈ 0.4–0.5% of the measured 8.5–10.7 ms mean tick (the
earlier "0.6%" here overstated it slightly); the time-drift limitation
and the unresolved offline det5 finding both stand.
