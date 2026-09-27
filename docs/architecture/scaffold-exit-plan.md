# Temporary scaffold exit plan (H28)

This register defines replacement gates. It authorizes no removal, production
authority, or change to MCK6, compression, defaults or accepted evidence. A
prototype passing its own tests is not an exit condition. Keep the existing
mechanism until its replacement proves the same observable contract on the
qualified runtime, including failure and lifecycle paths.

| Mechanism | Classification | Current purpose | Exit condition and retained replacement |
| --- | --- | --- | --- |
| Diagnostic live capture | TEMPORARY_AND_EXPECTED | `LivePacketCapture` and `SealedLiveCapture` capture packet-time state while writer admission is constrained. | After fresh V2 writer/lifecycle qualification and independent live shadow, stop continuous diagnostic capture where its evidence goal is met. Retain a bounded, opt-in reproduction path and first-divergence artifacts. Never replace a missing observation with a profile expectation. |
| Java/native packet comparison | REMOVE_WHEN_X | `LiveComparisonQueue` and `LiveShadowCampaignConsumer` compare candidate output while Java remains authoritative. | Remove mandatory per-packet double encoding only after operation-specific semantic parity, lifecycle closure, complete performance qualification and explicit authority approval. Keep offline replay, sampled diagnostics and a safe Java fallback wherever qualified native ownership cannot be maintained. |
| Profile-specific probes | GENERALIZE | `run_rev_probe.py`, `derive_rev_profile.py` and `live_profile.py` contain historical profile workflows; H2 supplies a generic engine foundation. | Both Clean Forge and Revelation must use fresh manifest-driven collectors and the same V2 verifier with real placement/writer/lifecycle witnesses. Retain runtime-specific declarative policies and historical receipts; eliminate duplicated procedural assumptions only after cross-profile negative controls pass. H2 alone does not satisfy this gate. |
| Duplicate Java/native state | DANGEROUS_IF_PERMANENT | `NativeChunkBridge` refresh/materialize operations and legacy native chunks retain a second representation under Java ownership. | For each qualified operation, prove single-writer ownership, quiescence, atomic handoff, registry binding, rollback/materialization, object identity, unloading and stale-result rejection. H6 is a serialized foundation only. Remove the redundant mutable representation only after those runtime proofs and a measured complete-operation benefit; retain immutable snapshots solely within explicit budgets. |
| JNI state getters | REMOVE_WHEN_X | `getPrimaryBitMask`, `getBiomes`, `getSectionLight` and generation queries support diagnostics and compatibility checks. | Replace success-path metadata reconstruction with the single encode result and qualified immutable view. Replace repeated getter crossings only when retained ownership supplies equivalent qualified data. Keep explicitly named diagnostics where useful; never pair bytes with a separately fetched successful mask. No ABI removal without caller inventory and compatibility tests. |
| Global writer gate | GENERALIZE | The session `LiveWriterGate` lock provides a conservative capture and writer ordering boundary. | Replace with finer ownership domains only after all writers, aliases, shared storage and cross-domain callbacks are covered, lock ordering and reentrancy are explicit, and negative/live tests prove equivalent capture coherence. H4/H6 models and H7 bounded models do not establish JVM happens-before. Preserve a conservative fallback for unqualified operations. |
| Persistent identity history | DANGEROUS_IF_PERMANENT | `PrivateBuildTickets.components` retains exact object identities; `LiveChunkBindings` tracks worlds/chunks and provenance so reused or shared objects cannot be relabeled private. H6 also retains registry epoch history. | Introduce bounded lifecycle accounting without forgetting identity/provenance while aliases or stale tickets survive. Prove safe retirement, anti-ABA generation rules, revocation and alias drainage; test long churn and session reset. A weak map, TTL or size-based deletion alone cannot justify trusting a formerly unknown object. Preserve fail-closed saturation until replacement proof. |
| Closed handler retention | DANGEROUS_IF_PERMANENT | `FrameAuthorityHandler.LIVE` and `FrameShadowLiveHook.LIVE` intentionally retain closed handlers for final diagnostic aggregation. | After in-flight native work and callbacks drain, transfer final counters into bounded immutable aggregates and release handler/object graphs. Verify close/removal ordering, rejected executor work, native-context free and exactly-once accounting with existing frame gates and long connection churn. This register does not edit frame authority or MCK6 semantics. |
| Java engine oracle paths | GENERALIZE | Java fixture/oracle harnesses and qualified runtime output remain independent references during migration. | Move mandatory duplicate production execution out of a qualified native hot path only after the complete rubric and explicit authority approval. Keep version/runtime-pinned Java oracles in tests, first-divergence replay and release qualification. Do not replace an independent oracle with a Rust-derived expected output. Java compatibility callbacks remain until individually qualified replacements exist. |

The machine register is `machine/architecture-hardening/scaffold-exit-plan.json`.
It records source anchors and dependent milestones; it is a plan, not a record
that cleanup or runtime replacement has occurred. H22 owns retention experiments,
H23 owns fresh runtime qualification, and H24 remains blocked until both target
runtimes are requalified under V2. Existing historical raw evidence is immutable.

For each eventual removal, record the old mechanism, replacement receipt,
measured copy/crossing/retention effect, negative-control result and rollback
path in that milestone. Leave the register open when any required proof is
missing. Diagnostic history can be archived as files; correctness identity
history cannot be discarded merely to reduce a memory counter.
