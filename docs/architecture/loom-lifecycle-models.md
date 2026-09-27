# Bounded lifecycle concurrency models (H7)

`tools/loom-lifecycle-models` is an isolated test-only workspace pinned to
Loom 0.7.2. It models selected concurrency obligations of the H4/H6 architecture.
It does **not** compile H4/H6 with Loom synchronization, prove those implementations
thread-safe, implement a JVM handoff, start a server, or enable native authority.
Existing H4/H6 APIs are serialized by exclusive Rust access. The models show
which boundaries must remain indivisible if concurrent adapters are introduced.

## Models and source correspondence

| Model | Invariant and source boundary | Deliberately broken control |
| --- | --- | --- |
| `handoff_admission_drain` | H6 `begin_write`, `begin_native_handoff`, `finish_write`, `stage_after_quiescence`: check/admit must linearize with freezing admission; an already issued writer may drain after ownership generation changes | Split eligibility check from admission; second control publishes native ownership without draining the writer |
| `owner_and_candidate_publication` | H6 `publish_handoff`; H4 `model_release_java` / `model_claim_native`: owner and the staged candidate become observable together | Publish the new owner before publishing the candidate |
| `worker_revocation` | H4 `prepare`/`commit` and H6 `publish_work`: a revoked capability cannot authorize a new result | Validate before releasing the lock, then use that decision after revocation |
| `worker_cancellation` | H6 `cancel_work` / `publish_work`: cancellation wins if it consumes the pending job first; a completed valid artifact need not disappear after a late cancellation | Split pending-job validation from final consumption/publication |
| `cancellation_checks_origin` | H6 origin-bound `WorkId` / `cancel_work`: a foreign store's colliding nonce cannot consume a local pending job | Ignore the store identity and cancel by nonce alone |
| `worker_state_generation` | H6 `set_state` / `publish_work`: a completed old-state result cannot populate the new generation's cache | Split validation from commit across a state mutation |
| `worker_registry_epoch` | H6 `invalidate_registry`, view tags and `publish_work`: old registry interpretation cannot be adopted as current | Split validation from commit across an epoch change |
| `worker_ownership_generation` | H6 handoff generation and H4 ownership checks at commit: old-owner work cannot commit after transfer | Split validation from commit across ownership change |
| `async_io_unload_adoption` | H6 `destroy`, `life_index`, `publish_work` analog: a completion for the old slot incarnation cannot be adopted after unload/reload | Split lifecycle/pending validation from adoption |
| `slot_reuse_generation` | H6 `life_index` / `destroy`: old slot number plus old generation must not alias a reloaded allocation | Ignore the generation during lookup |
| `exhausted_generation_retires` | H6 checked generation advancement, slot burning and H4 checked owner generations | Wrap a reduced counter back to an old live key |
| `ready_queue_publication` | Prospective READY/single-slot queue obligation: observing readiness must also observe initialized payload | Relax both publication and observation ordering |
| `immutable_snapshot_reclamation` | H6 `NativeView`, `Arc<SnapshotData>` and destruction: removing a slot must not reclaim a reader's retained immutable snapshot | Mark backing storage reclaimed at unload despite a live reader |

H4 source correspondence is `crates/native-capability/src/lib.rs`; H6 correspondence
is `crates/native-state-vnext/src/store.rs`. The runner hashes those source files
and their contract context so subsequent drift is visible. There is no automated
refinement proof between these small models and the real implementations.

The READY model uses one producer, one consumer, one atomic payload and one
release/acquire readiness flag. H6 currently has no such queue, READY flag or IO
adapter. The async IO model abstracts only the adoption decision for an already
completed immutable result; no filesystem, backend, completion queue or IO API
is tested. These are future integration obligations, not implemented features.

Each stale-result model checks final admission and publication in one modeled
critical section. Revocation/mutation/epoch/ownership/reload invalidations clear
the derived artifact. Cancellation affects only a still-pending job. In the
negative controls the commit-time assertion independently checks the stale
decision; a control fails before publishing invalid state.

The generation-exhaustion model reduces the maximum to two so exhaustion is
reachable. It establishes the modeled retire-versus-wrap property, not a proof
over all u64 values. Real counter exhaustion remains covered by source checks
and deterministic H4/H6 tests. Store/session/world identity, multiple simultaneous
jobs, dependency graphs, all light/biome generation combinations and allocator
behavior are not exhaustively enumerated here.
The cancellation-origin model covers two distinct stores with the same local
job nonce; it does not establish the entire handle/certificate identity closure.

The reclamation control uses a safe logical reclamation sentinel. It never
executes a real use-after-free. The valid case uses Loom's `Arc`, verifies a
retained reader can finish after slot removal, and checks exactly one final drop.
The handoff model has one Java writer and one transfer. Reverse materialization,
failure rollback and abandoned leases still rely on H6's deterministic tests;
there is no claim to have modeled every lifecycle transition concurrently.

## Bounds, negative controls and evidence

Each model has at most three threads including the main thread, a preemption
bound of three and 256 branches per execution. All modeled synchronization uses
Loom types. An ordinary atomic counter outside the model records execution
counts only and cannot influence admission, scheduling or invariants.

The builder explicitly disables permutation/time caps and checkpoints so an
ambient `LOOM_*` configuration cannot silently shorten a successful exploration.
Each test process has a 30-second deadline; exceeding it fails the run. Build and
clippy commands have 120-second deadlines. Process termination is an external
test/build bound, not a proof of driver/kernel cancellation or a wall-time bound
for every possible child spawned by an external compiler. No long polling loops,
random schedules, giant world state, raw pointers or unsafe Rust are used.

There are 13 positive models and 14 separately invoked negative controls. The
runner requires a completed exploration count greater than one for each positive.
Each negative must fail with its specified invariant marker, exit 101 and exactly
one failed test. A timeout, compilation error, branch-budget panic, unrelated
panic or unexpected success cannot count as successful fault detection. Ordinary
`cargo test` intentionally ignores the broken controls; use the runner for the
complete evidence campaign.

The bounded result covers the schedules Loom explored within these constraints.
Loom itself documents incomplete coverage of some relaxed-memory reorderings;
passing does not imply all real Rust, CPU, JVM, native callback or IO executions
are correct. Fairness, starvation and liveness are not established. The runner
hashes model/reference sources, selected actual tool binaries, fetched dependency
files and the compiled test executable before/after; raw output logs are retained
and hashed. This is not a complete system-DLL, MSVC SDK or environment closure.

## Dependency and reproduction

Primary documentation: [Loom 0.7.2](https://docs.rs/loom/0.7.2/loom/),
[Builder bounds](https://docs.rs/loom/0.7.2/loom/model/struct.Builder.html), and
[upstream MIT license](https://github.com/tokio-rs/loom/blob/v0.7.2/LICENSE).
`provenance.json` records the release commit, exact transitive versions, declared
licenses and registry archive checksums. `third-party/LOOM-LICENSE.txt` preserves
the upstream notice. No upstream model implementation was copied, no runtime
dependency was added to the root workspace, and no dependency source is vendored.

From the isolated checkout, fetch the pinned isolated dependencies once, then
run the offline validation campaign:

```powershell
C:\Users\Admin\.cargo\bin\cargo.exe fetch --locked --manifest-path tools/loom-lifecycle-models/Cargo.toml
python -B tools/loom-lifecycle-models/run.py
```

Machine evidence is recorded under `machine/architecture-hardening/h7-loom.json`
with retained logs/receipts. Live writer closure, quiescence, Java alias drainage,
publication barriers and qualified IO adoption remain required before runtime
integration. Production gates, defaults, MCK6 and compression stay unchanged.
