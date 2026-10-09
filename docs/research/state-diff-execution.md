# H22.5.B — Bounded state-diff execution

Overall decision: **LIMITED_USE** as an isolated execution pattern for explicitly
pure work. This prototype does not qualify Minecraft methods, Forge callbacks,
live snapshots or a production scheduler. No native production authority is
granted and no runtime integration is installed.

## Implemented contract

The standalone `tools/state-diff-experiment` crate defines a 16-cell u16 model.
`ModelWorld` has private state, a process-unique allocation ID, a state generation
and an independent local-admission epoch. A fresh world is unadmitted. Calling
`admit_offline_model` acknowledges only the fixed toy model's schema and pure
operation whitelist; it is deliberately not a capability grant or a verified
H2 runtime certificate. Every emitted receipt says `OFFLINE_MODEL_ONLY` and
`production_authority=false`.

`snapshot()` returns a private-field immutable `QualifiedSnapshot` only while
the model admission is active. It captures the entire 16-cell array, exact
allocation identity, generation and admission epoch. Value getters return
copies, so changing a returned array cannot change the snapshot or world.
Revocation prevents new snapshots and commits. Readmission advances the epoch,
so previously issued snapshots/diffs remain invalid.

`plan(&snapshot, ordinal, operation)` performs no world mutation. The complete
operation set is:

| Pure operation | Semantics |
| --- | --- |
| `Add {cell, delta}` | Read the cell; checked i32 addition followed by a checked u16 result; produce one `[cell,before,after]` effect. |
| `Copy {source,target}` | Read both cells, then emit a target write; copying to the same cell is an explicit no-op effect. |
| `Gradient {start,len,base,step}` | Produce 1–16 sequential cell effects using checked `base + offset*step`; any out-of-range result rejects the entire plan. |

All inputs are integers. The operation enum has no callback, filesystem,
network, clock, RNG, opaque function or foreign-method execution variant.
Campaign input generation uses a seeded Python RNG outside the execution model;
the executor itself has no RNG-visible behavior. It is not safe to infer that
real world generation or block behavior has these restrictions.

`Diff` carries the immutable provenance, ordinal, operation, complete read
preconditions and ordered before/after effects. Provenance and effect storage
are private; callers can inspect only immutable slices. No external diff
deserializer exists in this prototype.

## Validation and atomic ordered commit

`commit_ordered(&mut self, diffs)` requires exclusive Rust access to the model
owner. It validates the entire batch on a staged array before publication:

1. Active local admission; nonempty batch of at most 32 diffs; generation can
   advance without overflow.
2. Ordinals exactly `0..batch.len()` in the supplied order.
3. Exact allocation, original generation, admission epoch and complete snapshot
   contents match the batch's current base state.
4. At most 16 reads and 16 effects per diff. Recomputing the pure plan from the
   captured snapshot must produce exactly the supplied reads and ordered effects.
5. Each read precondition still holds in the staged state after prior diffs.
   Write/write and read-after-write conflicts therefore reject the whole batch.
6. Only after every diff validates, publish the staged array and increment the
   generation once. Return one receipt containing that generation, the complete
   final array and all effects in commit order.

A failed batch publishes neither cells nor generation and returns no successful
commit receipt. A late invalid second diff cannot leave the first applied.
Independent diffs from the same base snapshot may commit together in their
assigned order. A conflicting batch must be replanned against a fresh snapshot;
the implementation never guesses an ordering or silently resolves a conflict.
Even an explicit no-op successful batch advances the generation once.

The limits are 16 cells, 32 diffs, 16 reads/effects per diff and 512 emitted
effects per batch. Bounds are checked before indexing or effect publication.
Model IDs and generations use checked increment, not wraparound. Global
generation invalidation is conservative: a change anywhere invalidates older
single-operation plans, even when their read sets happen to be disjoint.
Validation deliberately recomputes the pure operation as a correctness check.
This duplicates work and demonstrates no acceleration benefit; a useful future
kernel would need a justified validation strategy and end-to-end measurements.

This is atomic publication under exclusive in-process ownership, not a lock-free
algorithm, durable transaction, crash-recovery mechanism or permission to race
Java writers. The real integration would still need coherent capture and
validated ownership, lifecycle and writer closure.

## Independent reference and first-divergence checks

`java/StateDiffReference.java` independently implements the three pure operations
as sequential transformations of a cloned working array. It does not call Rust,
share transition code, use Minecraft classes or claim Forge fidelity. The Rust
candidate executes snapshot → plan → validate → commit for each command.
Separate Rust tests exercise multi-diff transactions, which the Java sequential
reference does not independently qualify.

The driver compares initial state and **every** subsequent boundary: versioned
schema, fresh session/challenge/trace binding, outcome/error, generation, all
16 values and every ordered effect. It preserves the first mismatch and stops
issuing commands. An injected Rust receipt fault leaves the actual final cells
correct while corrupting an effect's result; comparison must stop at command 1,
demonstrating that state equality cannot substitute for effect equality.

The runner reuses the frozen differential-replay adapter read-only. Its bounded
queue, stdout/stderr prefixes, nonblocking pipes, timeouts and lifetime remain
enforced; the shared 4 MiB accepted-boundary budget is also applied here. Only
the latest pair is retained in memory. Source, Java/Cargo/Python tools, class
files, candidate binary and the exact shared transport source are pinned and
rechecked. Every campaign receives a fresh UUID output directory and saves its
input traces, boundary streams, errors, build/test logs and machine receipts.

## Validation and measured scope

Twenty Rust tests cover unadmitted snapshots, snapshot immutability, no mutation
during planning, valid ordered commits, out-of-order rejection, write conflicts,
read-after-write conflicts, stale generations, identical-but-distinct worlds,
revocation/readmission, a corrupted second diff, oversized effects/batches,
empty batches, gradient failure atomicity, gradient golden values, generation
exhaustion, forged snapshot contents, ordinal bounds and deterministic planning.

The Java/Rust campaign contains seven hand-checked traces and 16 seeded mixed
traces of 32 commands, plus one deliberate effect-only divergence. Golden
checks include descending gradients, copy/add results, ordered effects,
out-of-range gradient atomicity, invalid cells, empty gradients, overflow and
retry after rejection. It compares every boundary rather than just final state.
The Java reference covers pure sequential transitions; it does not independently
prove the Rust model's admission or multi-diff transaction implementation.

Final campaign: `target/state-diff-experiment/261a31c0203a4cd18216aa20c1d55f20/campaign.json`.
SHA-256: `8f332f843eb20f5092bfea52811532870c86f9b41b7bd55009b08c48f0812bd4`.
Result: **24/24 controls passed**, including the intended effect-only divergence;
**547 boundary pairs** were compared. The injected fault stopped at command 1
despite equal cell state. All 20 Rust tests passed, as did formatting, Clippy
with warnings denied, release build and Java8 compilation. Source/tool/build
artifact hashes, including the frozen replay transport, were unchanged.

The complete campaign elapsed **12.642 s**, including builds, process startup
and conservative pipe polling. This is reproducibility evidence, not evidence
of an execution speedup. There is no justified end-to-end Minecraft performance
measurement at this stage.

## Application decisions

| Application | Decision | What the evidence permits; missing conditions |
| --- | --- | --- |
| Pure block calculations | LIMITED_USE | Prototype the pattern for a separately qualified, bounded integer function with explicit reads/effects. Arbitrary block methods may call world/mod code and are outside this evidence. |
| World-generation kernels | LIMITED_USE | Explore deterministic side-effect-free kernels such as a supplied integer gradient. Real generators, mutable registries, neighborhood dependencies, floating point and RNG-visible behavior require separate qualification. |
| Selected simulations | LIMITED_USE | Suitable for further experiments with bounded pure integer state transitions. Entity AI, asynchronous world access and external effects are unqualified. |
| Testing and migration oracles | ADOPT | Use the isolated model's atomicity controls, ordered effects and independent per-boundary comparisons as executable regression examples. This does not promote tested toy methods to live runtime capabilities. |
| Production transactional scheduler | REJECT | This model lacks live ownership/capture gates, Java writer closure, concurrent lifetime guarantees, topology dependencies, retry policy, persistence and real-method qualification. Reuse of the validation pattern remains research only. |

## Required integration work

A future adapter must start with a packet-/operation-time coherent, immutable
snapshot whose exact runtime, transformed code, registry, receiver, world and
incarnation are covered by a current certificate and capability. Pure-method
admission needs an explicit effect model proving all reads, exceptions and
observable behavior are captured. The commit owner must validate generations,
topology and ownership under the appropriate serialization boundary and retain
Java fallback on rejection. Externally decoded diffs need strict validation and
provenance; private Rust fields alone do not authorize external input.

Arbitrary Forge callbacks, filesystem/network effects, RNG-visible behavior and
other external effects are excluded. No optimization or AOT assumption follows
from a matching method name. No production code, root Cargo dependencies,
runtime defaults, authority gate or original worktree was changed; no server
was launched and no external crate was installed.
