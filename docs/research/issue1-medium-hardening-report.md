# ISSUE1 MEDIUM HARDENING REPORT

Date: 2026-09-24. Bounded detached Clean Forge hardening is validated.
Production `M4NativeStatePayload.tryEncode` still unconditionally returns null.
Issue #1 remains open. No native packet transmission, live SHADOW run, modpack
run, push, rebase, or accepted-history rewrite occurred.

## A. Committed Ultra-stage SHAs

The accepted working tree matched its recorded source hashes. The accepted
41-case Forge suite passed before staging in run
`20260924T231213Z-forge-08e15637`. Staging exposed one trailing blank line in
`QualifyRuntime.java`; only that EOF whitespace was removed, its exact change
was recorded, and the entire accepted Forge lane passed again in
`20260924T231337Z-forge-85b224ae` before these local commits:

| SHA | Commit |
| --- | --- |
| `6db9624bf52a4b5768641b60babfbc5ecfbb20a9` | feat(issue1): add clean Forge owned snapshot oracle |
| `f3f2857fcfd6f3842d87cef9c941857e0b3e8884` | test(issue1): add Forge capture and adversarial regressions |
| `735a2dab9ecaba6acbc7b88cf182f9125a312aa3` | test(issue1): add focused mutation and Forge runner support |

The accepted architecture and earlier history are preserved. Commit and
whitespace receipts are under the local Medium evidence directory below.

## B. Branch and HEAD

Existing branch: `issue1-jni-v2`.
HEAD: `735a2dab9ecaba6acbc7b88cf182f9125a312aa3`.
No new branch was necessary. The Medium additions are uncommitted; the explicit
commit authorization applied to preserving the accepted Ultra stage.

## C. Noncanonical-input regressions

Six new real Forge controls reject malformed state properties, noncanonical
state identity, an unsupported EBS subclass, incompatible declared registry
width, invalid palette width and an invalid request filter. Existing thread,
lifecycle, storage, writer and TE controls remain required.

A constructible missing-property state exposed a real pre-setter hole: Forge
registry lookup can throw before the adapter records admission failure. The
adapter now catches that bounded RuntimeException, records explicit invalid
input and prevents later capture. The negative test uses ordinary construction,
not invented JVM state. See the [publication contract](issue1-medium-publication-contract.md)
for the exact input and rejection semantics.

## D. Partially failed capture regressions

Ten new controls cover temporary rewrite, disposable in-place rewrite and
eight extraction stages: metadata, logical conversion, logical clone, block
light, sky light, biomes, end validation and wide-ID decision. The throwing
rewrite List fails after exactly 17 completed real setters. Logical conversion
fails after 17 copied IDs. Clone-stage controls fail after a clone completes;
they do not claim to interrupt arraycopy.

All 16 new rejection records assert absent published snapshot/Java bytes,
denied transport access and zero failure-path JNI calls. Private graph witnesses
prove unchanged identities/content for all except the intentionally modified
disposable in-place graph, which is poisoned and cannot be captured. No existing
registered or live world is modified. Eight transient controls retry to a
complete Java pair and successful JNI result; two poisoned rewrite sources
continue rejecting. These retries are not additional parity fixtures.

## E. Snapshot publication invariant

Method-local extraction scratch becomes an immutable owned snapshot only at
the final successful `SnapshotCapture.captureHeld` return, after end/TE checks.
`CaptureContract.Result` enforces ELIGIBLE iff its snapshot exists. Rejected
snapshot access throws. The real oracle's Pair constructor separately requires
either the complete snapshot/Java-packet/tick-count triple or an all-null
rejection. Its guarded transport accessor blocks rejected JNI dispatch.

Rust's validated owned input and single-operation byte-count/mask result remain
unchanged. No new ABI or live coherence architecture was introduced.

## F. New real fixtures

The inventory is **21 accepted + 36 rejected = 57 distinct outcomes**. All 21
accepted events pass independent semantic comparison and native CLI replay;
all 21 also happen to match Java bytes exactly. Accepted inputs remain labeled
`REAL_CLEAN_FORGE_ORACLE_ACCEPTED`; rejection artifacts use
`REAL_CLEAN_FORGE_EXPECTED_REJECTION` and their own strict schema.

Sixteen new rejection records carry stage/progress, publication, zero-JNI,
source-witness and retry proof. The older 20 retain their existing semantics
and null new proof. Some older controls reject an encode of a valid input;
they are not relabeled as pre-capture failures. Rejection artifacts contain no
successful V2 value, payload or transport. Canonical hashes and all referenced
runtime/source artifacts remain mandatory. Generated artifacts stay ignored.

## G. Property-test results

Added three generated properties and two deterministic tests. The owned target
now passes **12 tests**, including eight generated properties at 64 cases each.
New properties exercise incomplete transport prefixes/trailing input, late
width decisions and lifecycle replacement without relabeling immutable history.
Deterministic tests cover each copy boundary and local/global ID width edges.
Invalid inputs cannot enter encode through the public consumer chain or change
its output sentinel; complete retries succeed. No unmutated counterexample was
found, so no minimized failing input was invented or persisted.

The property lane also passes the existing two native contract properties and
two FFI V2 properties: 16 target tests total, of which 12 are generated
properties. These tests overlap the public suite and are not additional unique
tests. Transport properties do not prove real-world writer participation.

## H. Mutation-test results

Focused cargo-mutants 27.1.0: **14 tested, 14 caught, 0 missed, 0 unbuildable,
0 timeout**. Scope was the lifecycle/width/completion/reader-bound predicates
newly exercised in `packet_snapshot.rs`; every caught mutant failed at least
one new test. No whole-function or whole-workspace campaign ran.

Separate project Java fault audit: **8 tested, 8 assertion-caught, 0 survivors
or unclassified failures**. Two standalone faults cover Result publication and
access; six qualified real Forge faults cover Pair publication/access,
noncanonical identity, malformed-property poisoning, registry width and rewrite
poisoning. Every Forge fault qualified its runtime before the expected harness
assertion. Removing canonical admission still rejected later with a different
reason; that catch proves pre-setter rejection specificity, not that the mutant
published bytes. Three other admission/poison removals did produce an eligible
Pair where rejection was required.

Two preliminary Java audit attempts encountered infrastructure issues (replay
executable default and Java 8 Windows path length); neither counts as a caught
Forge fault. The final short-path audit passed its standalone and full 57-case
Forge baselines and verified unchanged original sources. Mutation results are
bounded test evidence, not proof of live coherence.

## I. Forge runner result

`tools/run-rustcraft-tests.ps1 forge`: **PASS**, run
`20260924T232303Z-forge-f251f5ad`. Fresh offline qualification/oracle JVMs,
original pinned MC 1.12.2 / Forge 2860 artifacts, final transformed classes,
registry and source identities were checked. Independent replay passed all
21 accepted and 36 rejection outcomes. No server was started. Other lanes
still require no Forge artifacts.

## J. All regression totals

| Suite | Passing result; overlaps called out |
| --- | --- |
| `cargo test --locked -p native-chunk` | 46: original 10 unit + 14 structural + 8 snapshot + 2 native properties + 12 owned tests |
| `cargo test --workspace --lib --locked` | 78 library tests; includes the native 10 and FFI units |
| `cargo test --locked -p protocol` | 10 integration tests |
| FFI V2 integration / selected units | 9 integration; 8 selected units already included in workspace |
| Explicit structural / snapshot reruns | 14 / 8, already included in native-chunk |
| Property lane | 2 native + 2 FFI + 12 owned tests, already covered above |
| Python runner / runtime / Forge replay | 27 / 10 / 21 distinct tests |
| Python independent decoder / available evidence checker | 37 / 5 distinct tests |
| Synthetic fixtures | 16 fixtures + 8 rejection scenarios; not real Forge evidence |
| Java V2 decoder | 218 checks |
| Java retained JNI | 6 groups / 568,392 checks |
| Java capture | 9 groups / 454 checks |
| Java owned-snapshot JNI | 4 groups / 139 checks |
| Real Forge | 21 accepted comparisons/replays + 36 rejection outcomes |
| Focused audits | 14 Rust mutants + 8 Java guard faults, separately counted |
| `cargo build --release --locked -p ffi` | PASS; existing unrelated compiler warnings remain |
| Whitespace | `git diff --check` and new-file checks PASS |

All six final lanes passed: public, property, fixture, decoder, java-jni, forge.
Repeated lane executions are not new tests. Missing historical evidence,
unavailable NBT binaries, world-data/socket/live tests and modpack compatibility
remain the runner's explicit exclusions. No historical artifact was fabricated
or removed from the evidence registry to obtain a pass.

Final receipts under `target/rustcraft-tests/runs/`:

| Lane | Run |
| --- | --- |
| forge | `20260924T232303Z-forge-f251f5ad` |
| public | `20260924T232425Z-public-c6f47a2f` |
| property | `20260924T232437Z-property-8d314dfe` |
| fixture | `20260924T232440Z-fixture-4a3a3ae8` |
| decoder | `20260924T232443Z-decoder-cc20a4ad` |
| java-jni | `20260924T232444Z-java-jni-a282b40e` |

Each contains `results.json`, exact argument arrays and process logs. Local
final reverification matched all six receipts to the current 249-file source
snapshot, reopened all 57 fixture schemas/hashes/references and 16 new proofs,
and successfully rehashed all 1,768 mapped artifact entries.

Medium receipts live at `D:/rustcraft-bootstrap/issue1-medium-20260924T231213Z`:
accepted-tree/commit receipts, `rust-publication-mutants/summary.json`, and
`final-reverification.json`. Java fault evidence is at
`D:/rc-medium-audit-3/receipt.json`. These local paths are evidence from this
machine, not portable dependencies or tracked binary fixtures.

## K. Files changed in the Medium stage

| Area | Paths relative to repository root |
| --- | --- |
| Rust tests | `crates/native-chunk/tests/owned_snapshot_properties.rs` |
| Java capture contract | `tools/bridge/src/com/rustcraft/bridge/capture/CaptureContract.java`; `SnapshotCapture.java` in the same directory |
| Real Forge oracle | `tools/forge-capture/src/com/rustcraft/oracle/OwnedForgeCapture.java`; `CleanForgeCaptureOracle.java` in the same directory |
| Java regression | `tools/native-chunk-jni-tests/src/com/rustcraft/bridge/capture/SnapshotCaptureTest.java` |
| Replay/audit | `tools/testing/forge_fixture_replay.py`; `test_forge_fixture_replay.py`; new `audit_medium_capture_faults.py` in the same directory |
| Schemas | `docs/schemas/chunk-packet-fixture-v1.schema.json`; new `chunk-capture-rejection-v1.schema.json` |
| Documentation | `tools/testing/README.md`; `docs/research/issue1-real-forge-fixtures.md`; new `issue1-medium-publication-contract.md`, `issue1-live-shadow-gate-plan.md`, `issue1-medium-hardening-report.md` in `docs/research/` |

Sixteen changed/new files. Rust production code, JNI ABI, production gate,
frame-engine authority, MCK6, compression, defaults and historical evidence
registry are unchanged from accepted HEAD.

## L. Live-SHADOW integration plan

The [plan](issue1-live-shadow-gate-plan.md) identifies the actual ASM constructor
entry and completion hooks, canonical capture thread, incarnation/event
association, writer participation, biome/light exposure, empty-TE restriction,
fallback counters and stop conditions. The future flow is live eligibility ->
owned capture -> one V2 result -> independent comparison, with Java alone
authoritative. The plan is not an executable campaign; no live work ran.

## M. Live writer findings

The actual async ChunkIOProvider-to-owner publication edge remains unproven on
the audited tick completion path. Direct section/container/light/biome arrays
escape through current APIs, and biome lookup can lazily write. Existing
mutation return hooks update bookkeeping after writes; the refresh lock does
not exclude every writer. Legacy comparator rereads/deferred work and a
length-only encode plus separate mask cannot establish the new owned contract.
Nonempty TE callbacks remain unqualified. These are precise proof gaps, not a
claim of a newly reproduced async biome/light defect.

## N. Exact remaining blocker

Prove safe provider-to-canonical-owner publication and complete writer exclusion
for a narrowly admitted live Chunk, binding that exact object/incarnation/event
through actual Java packet construction and diagnostic publication. The offline
runtime excludes RustCraftCoreMod; the hooked runtime also needs its own exact
transformed-class/listener qualification. Detached fixture equality supplies
neither missing proof.

## O. SAFE_FOR_MEDIUM or REQUIRES_ULTRA

Completed bounded offline hardening: **SAFE_FOR_MEDIUM**, validated and safe to
commit within that scope. The next live ownership/publication work:
**REQUIRES_ULTRA**. No new concurrency protocol was designed in Medium.
Production remains fail-closed. Historical classification remains
`ROOT_CAUSE_CLASS_HARDENED` / `HISTORICAL_EXACT_WRITER_UNRESOLVED`.

## P. Next recommended task

An Ultra evidence/architecture review of provider publication and complete live
writer ownership for one bounded clean-runtime path, followed by a separately
reviewable integration proposal. Do not begin live SHADOW until that proof and
hooked-runtime qualification exist. Revelation, SevTech, nonempty TE callbacks,
native authority, historical exact-writer closure and performance campaigns
remain outside this stage.
