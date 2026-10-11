# RustCraft Engine Rewrite — Roadmap (v2, 2026-10-10)

## Objective (operator directive, 2026-10-10)

Build a Minecraft 1.12.2 engine around Rust while preserving compatibility
with existing Forge/mod JARs through a defined compatibility runtime.

- Correctness and demonstrated native execution remain REQUIRED.
- Performance is measured and reported, but an early compatibility
  milestone does not need to beat clean Java before development continues.
- Historical benchmark findings and their limits are PRESERVED (see
  RUSTCRAFT_VS_JAVA_FULL_STACK_BENCHMARK.md + the FS-series receipts —
  they stand as measured, including that the composed stack was slower
  than clean Java at each recorded configuration).

## Architecture (unchanged by evidence)

Rust engine core + JVM mod island with patchable Java shells — forced by
coremod/mixin patching of vanilla bytecode (see
REA_SETUP_AND_RUSTCRAFT_REWRITE_ASSESSMENT.md). The rewrite proceeds by
OWNERSHIP MILESTONES: each flips one subsystem to Rust with engagement
proof, compatibility boundary documentation, and correctness receipts;
performance is reported per milestone but is not an early gate.

## Status corrections to inherited claims (2026-10-10)

1. **Light SHADOW retained Rust light computation** (shadow comparator:
   ~2k jobs / 30k shadow cells per run). M0/FS-004 refuted "light
   authority causes the residual MSPT gap" — it did NOT measure, and must
   not be cited as, light-machinery-off.
2. **No-capture-session results bypassed writer enforcement** and are
   DIAGNOSTIC/isolation results, not valid full-stack performance
   configurations (FS-003 scope limits stand).
3. **Netty's transformer exclusion does not establish zero compatibility
   exposure** for the surrounding packet path — the packet subsystem's
   compatibility boundary must be mapped on its own classes (M1 §3 work).
4. **REA capabilities are host- and workflow-verified only as stated in
   its setup receipt**: MCP registration verified (133 tools,
   binary_session OK); native Windows process capture NOT yet verified;
   Ghidra/Hopper/IDA engines not installed. REA has no JVM/jar tools —
   the symbol index remains the bytecode authority.

## Milestone ladder

- **M0** (FS-004): DONE — closed the light-ownership branch.
- **M1 — packet emission ownership: ENGAGED (2026-10-10)** in the
  supported (qualified-capture) composition: `--m1-packets
  --minimal-authorities`. m1-qual1 proves the full path — writer hooks
  admitted on Phosphor-mixin'd bytes, bindings formed, native state
  checked, Rust encoded + directly emitted 91/169 packets (2.9 MB on the
  wire), client received all 169; every fallback accounted. Root cause
  of the earlier non-engagement was NOT profile staleness: the pins are
  derived from a foreign-only discovery chain, so ANY authority
  transformer registered before the writers breaks identity BY DESIGN.
  Open follow-up: composed-config (authorities ON) engagement needs
  per-composition profile re-derivation or a foreign-stage identity
  boundary (architecture decision). Receipt:
  M1-PACKET-EMISSION-RECEIPT.json (boundary + classification inside).
- **M1 next**: the boundary classification lives in the receipt (Rust
  owns payload encoding + direct emission for the eligible slice; Java
  keeps framing/pipeline/TE/state serialization/game loop; NO new
  adapter needed).
- **M1-BOUNDARY** (with M1): pin modpack/Forge/mod identities; map the
  packet subsystem's class surface (refs, reflection, coremod targets,
  callbacks); classify Java-must-stay / delegable / adapter-needs.
- **M2 — region I/O end-to-end** (read+write authority-proven; retire
  remaining Java copy paths).
- **M3 — world tick/game-loop ownership behind patchable shells** (the
  exhaustive "which classes do the 73 jars' coremods touch" census from
  the symbol index comes FIRST).

## Standing gates (unchanged)

Digest/parity evidence, bounded authority, engagement witnesses (a
shadow-produced body is NOT a Rust-transmitted packet),
PRODUCTION_AUTHORITY=false until a dedicated milestone closes it.

## M1-COMPOSE (2026-10-10): COMPOSED ENGAGEMENT PROVEN — all subsystems + packet path in one configuration

Mechanism (goal-§2 option 2, safe ordering change): the transformer chain
is now tiered **[foreign][writers][authorities]**. The writers' qualified
pre-hook pins bind to the foreign stage (the discovery probe registers no
RustCraft transformers), so the writers transform BEFORE any owned
authority pass injects into the same classes. Implementation: writers
register first among our transformers; `LiveWriterOrdering` maintains the
three tiers, and its repair only ever moves late-appended foreign entries
back into the foreign prefix (writers/authorities shift later, never
before the live iterator — the first attempt's mid-pass rotation hazard
boot-failed and is the recorded negative). No new profile; no weakened
identity; the discovery/runtime chain asymmetry resolved by ORDER.

Proof (m1c-compose2): packet authority **92/169 packets Rust-authored
(2.98 MB transmitted, buffers 92/92 released)** + registry 646 chunks /
3,070 sections (0 mismatches, 0 divergences) + light 2,070 jobs / 30,619
cells (mirror==committed) + region-write 1,228 entries — zero NO_BINDING,
zero CHUNK_IDENTITY_MISMATCH, 169/169 packets delivered, 66/66 mutations
confirmed. Performance (reported, not gated): streaming 22.5/7.5 MSPT
mean/p50, 24 GB alloc (capture session on — FS-003 stands).

**Diagnosis superseded**: the M1-FIX "stale pre-hook profile" explanation
is corrected — the profile was never stale; the failure was a
discovery-vs-runtime TRANSFORMATION-CHAIN MISMATCH (discovery chain has
no RustCraft transformers; the composed runtime registered authorities
before the writers). **Boundary correction**: the nine Phosphor mixins
are what the current index records — NOT the entire compatibility
surface (73-jar census + unindexed coremods are additional). Canonical
identity note: CANONICAL_ID_V1 proves structural equality of the
foreign-stage bytecode modulo ordering — not raw-byte equality.
Receipt: `docs/research/M1-COMPOSE-RECEIPT.json`.

**Ladder: M1 COMPLETE (minimal + composed).**

- **M2 — region I/O end-to-end: PROVEN (2026-10-10)**. Native persistence
  AND fresh-process reload demonstrated: run A wrote EVERY region record
  through Rust (1178/1178, 0 fallbacks) in the composed configuration;
  fresh-process reload B1 loaded the Rust-written chunks natively
  (628/628, 0 failures, 67/67 workload confirms on the same world); reload
  B2 ran the VANILLA Java reader on the Rust-written files with a
  byte-for-byte shadow oracle: 630 compared, 0 mismatches. Boundary: Rust
  owns staging+compression+sector-alloc+file-write (write) and
  file-read+lookup+decompression (read); Java keeps NBT production/
  interpretation (the mod-visible contract). Receipt:
  M2-REGION-IO-RECEIPT.json. Performance reported not gated (stream
  16.5-20.8 mean MSPT in the composed+capture configuration).
- **Next: M3 — world tick/game-loop ownership behind patchable shells**
  (preceded by the exhaustive coremod-touch census from the now-expanded
  symbol index: 211 mod artifacts / 36 mixins indexed).

## M3-A (2026-10-10): SCHEDULED-TICK SCHEDULER AUTHORITY PROVEN

The first authoritative tick responsibility lives in Rust. The
WorldServer pending-block-tick scheduler (admission `func_175654_a`,
drain `func_72955_a`) is owned by
`crates/native-chunk/src/tick_scheduler.rs` + `TickSchedulerHook`:
Rust owns queue state, admission dedup, eligibility
(`scheduledTime <= totalWorldTime`), the per-call drain cap
(`min(queue, 65536)` — a COUNT cap, not a time horizon; bytecode
overturned memory), ordering (= `NextTickListEntry.compareTo`),
removal, and stale-drop; Java executes `Block.updateTick` and keeps
the TreeSet mirror for save-side reads. The vanilla tickUpdates loop
is displaced in ON mode — behaviorally proven by the on14 negative
(executor fail-closed froze falling sand; the vanilla loop did not
rescue it).

Proof (composed runtime, all M1/M2 gates live): **m3a-on17** — 205
enqueued / 204 drained / 201 executed / 0 errors, `m3a_water_flow` +
`m3a_sand_resting` confirmed through the Rust decision loop, 69/69
mutation cells, packets 92 committed, region 1182/1182, registry
634/0 missing, light 2276 admitted / 0 mis. **m3a-shadow6** (reference
path): 6,233 decision compares, **0 order / 0 count mismatches** —
the queue is rebuilt from the mirror each call and Rust's drain
decision equals the Java TreeSet's exactly.

Compat findings pinned: FoamFix's `FoamyBlockState` (declared-type
matching for reflective invokes must come from Block's own methods,
not the live state class); vanilla admission gates
(`isBlockLoaded(pos)`, `scheduledUpdatesAreImmediate` inline path,
AIR-material time=0/prio=0 quirk); unloaded→requeue(0) vs
stale→consume (the reverse of common memory); the loaded check is
2-arg `isAreaLoaded(pos, pos)`. Tier contract: the tick authority
sits in the authorities tier (measured chain position 40; on16's
foreign-prefix landing at position 3 was the classifier gap —
`TieredOrderingRegression` now pins every authority name).

Residuals (declared, not blocking): world-unload `tickClear` witness,
deliberate negative-boot injection, chunk-load-side adoption event,
PRODUCTION_AUTHORITY=false. Receipt:
`docs/research/M3A-TICK-AUTHORITY-RECEIPT.json` (incl. the honest
boot ledger: ~19 boots vs a 3-4 declared budget, every overage
attached to its diagnosed defect).
