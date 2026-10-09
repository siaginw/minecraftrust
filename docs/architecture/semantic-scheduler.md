# Semantic scheduler foundation — H12 / H22.5.F

Decision: **PROTOTYPE**. The standalone `crates/semantic-scheduler-prototype`
implements admission, owned snapshots, bounded pure execution, generation fencing
and ordered atomic commit. It has no production dependency, JNI entry point,
runtime certificate adapter or authority path. `production_authority_enabled()`
is always false. Its independent specification covers a small integer state
machine; it does not establish Minecraft or Forge tick parity.

## Implemented contract

The controller owns at most 4,096 versioned integer cells. The only worker
operation is a checked affine transform over 1–256 contiguous cells. It cannot
invoke a supplied closure, access controller state, call Java, observe gameplay
RNG, read files, or dispatch external effects. This intentionally closed operation
makes fault and ordering tests auditable before real job types are introduced.

An `OfflineQualification` is privately issued by this model and binds scheduler
object identity, session, world, incarnation, registry generation, and capability
generation. Numbers supplied to `Scheduler::new` are model inputs, not verified
runtime facts. A token from a different scheduler fails even when its numbers are
identical. A token does not deserialize from a certificate or hash.

Successful admission assigns a checked `u64` sequence and copies a versioned read
set into private immutable storage. Failed admission consumes no sequence. The
last value `u64::MAX` can be assigned once; future admission fails permanently
rather than wrapping. `submit` returns an opaque `TaskId` with private originating
scheduler identity and sequence. `dispatch` and `cancel` take a borrowed `TaskId`
and reject a foreign origin **before** reaping, lookup or mutation. A sequence is
inspectable for ordering and trace output only; numeric values and serialized
trace rows cannot authorize controls. Cloning a handle permits local cancellation
without duplicating a worker lease. A stale handle cannot control a later task,
and retaining it after scheduler destruction cannot bind it to a replacement
scheduler, even when both first sequences are zero.

Inputs cannot be modified through a ticket. A ticket and
its completion are move-only, and completion payloads have no public constructor
or mutation API. Every result carries the exact private task identity and its
scope and capability generation.

Workers may finish in any order. `accept` records completion without applying
state. `drain_one` considers only the oldest admitted task and returns exactly
one observable decision. A ready later task never bypasses a queued/running head.
Validation checks scope, exact result shape and operation count, the complete
read set, output order and values, and checked cell version increments. It builds
the entire effect list before any state mutation. The commit loop contains no
allocation, callback, external I/O or fallible arithmetic. A failed task produces
an empty effect list and leaves controller state unchanged. The prototype
recomputes the small arithmetic operation during validation; that validation
cost is real and is not evidence of a useful parallel speedup.

An external write through the model API increments the cell version even when
its value remains the same. Two tasks admitted against overlapping snapshots do
not automatically replay: after the first commits, the later conflicting task
gets `StaleInput`. A caller can explicitly resubmit a pure task with a fresh
snapshot. This is not authorization to replay arbitrary Java callbacks.
The model exposes skipped decisions as failures. A real game adapter must define
an ordered reference fallback or an explicit operation failure; it may not turn
these outcomes into silently omitted gameplay effects. Wall-time expiry can
vary with machine scheduling even when the logical input is the same.

## Cancellation, deadlines and lifecycle

Cancellation, revocation and unload create terminal records in the same sequence
stream. The first controller terminal event is sticky. A running result cannot
overwrite it. When that record reaches the head, it produces a skipped decision
and allows later records to progress. Expiry similarly produces an ordered
`Deadline` record. Dropping an issued ticket or unaccepted completion produces
`Abandoned` when the head is observed.

Crucially, ordered progress is separate from worker quiescence. If a task is
cancelled while a worker still owns its ticket, its payload reservation and
dispatched slot remain charged in the retired set until the ticket/completion
lease is dropped. A forgotten or stuck worker therefore creates bounded
backpressure rather than unlimited replacement tasks. Repeated cancelled heads
cannot bypass the outstanding-task limit. A scheduler drop requests cooperative
cancellation; it does not forcibly kill external worker threads.

Unload stops admission and invalidates pending jobs. Explicit offline reload
increments the incarnation before issuing new tokens; retained old workers stay
charged. Revocation increments the capability generation. Generation exhaustion
stops and permanently closes the scheduler; incarnation exhaustion keeps it
unloaded. Cell version exhaustion prevents the entire affected commit. The
logical clock only moves forward and deadline addition is checked.

The prototype uses both a controller monotonic logical clock and admission-time
`Instant` elapsed time. Workers check cancellation, deadlines and operation
budgets between arithmetic steps; commit checks expiry again. The wall-time check
is a cooperative guard, not preemption or a maximum scheduling latency. OS
suspension, allocator failure, a future non-cooperating native call and process
abort are outside that guarantee. `catch_unwind` captures an unwinding worker
panic in this build; it cannot capture aborts or a `panic=abort` build. Panics
outside the closed worker operation (for example OS thread creation failure)
are not converted into job outcomes by the demonstration executor.

`execute_scoped` uses a bounded `std::sync::mpsc::sync_channel`, a short mutex over
ticket intake, and 1–8 scoped standard threads. It joins before returning. Its
completion vector is bounded by the admitted tickets, and never defines commit
order. Because it waits for its closed tasks, it is a demonstration executor,
not a responsive live controller loop for arbitrary long-running work. An actual
adapter must retain controller access for cancellation and preserve lease-based
accounting if a separate worker pool is substituted.

## Resource and error policy

| Resource | Implemented limit |
|---|---|
| Controller state | 1–4,096 cells |
| Per task read/output/effect set | 1–256 cells |
| Admitted plus retired tasks | Configured 1–64 |
| Dispatched leases | Configured 1–8, including retired workers |
| Global task payload reservation | Configured at most 1 MiB |
| Per task logical CPU work | 1–512 checked arithmetic steps; success requires exactly twice cell count |
| Wall budget | Positive, at most 10 seconds; cooperative |
| Logical deadline | Positive ticks, checked addition |
| Captured campaign output | 1 MiB stdout / 512 KiB stderr per process; 16 MiB entire campaign |

Payload reservations cover input records, candidate output, validation effects
and a 1,024-byte per-task metadata allowance. They are conservative logical byte
accounting, **not allocator bytes or RSS**. Allocator headers, thread stacks,
fixed controller storage, copied `values()` responses and caller-retained
`Decision` histories are excluded. Scheduler-owned histories do not grow: each
decision transfers to the caller. A live adapter must separately bound its
recording/output consumers, decoded inputs, worker stacks and native library
scratch allocations. A caller can always retain arbitrarily many returned
values; the model does not claim to govern memory it no longer owns.
Incoming state and ticket vectors are rebuilt using their checked lengths so
arbitrary caller over-allocation is not retained in controller or intake storage.

Admission rejection precedence is unloaded → qualification → range → budget
format/deadline → per-task bytes → outstanding count → aggregate bytes → sequence
exhaustion. At commit, an existing terminal record wins; expiry is checked before
new worker errors; a completed worker error is preserved before read validation.
For successful worker results, malformed count/step shape is checked before
stale reads, then ordered output values and version exhaustion. Combined invalid
inputs are explicit controls. None of these cases permits a partial commit.

## Evidence and reproduction

The crate has **28 Rust unit tests**, **4 compile-fail API controls**, and **6
Python reference/harness controls**. Unit tests exercise all 24 completion
permutations for both disjoint and overlapping four-task workloads, comparing
every decision and resulting state with a separate serial Rust specification.
They also exercise actual scoped worker counts 1, 2 and 4; retained cancelled
leases; dropped tickets/results; foreign completions; malformed output length,
indices, values, ordering and work count; stale same-value writes; arithmetic
overflow; partial-work CPU exhaustion; panic; logical/wall deadlines; unload,
reload, revoke; all relevant counter exhaustion; and invalid-input precedence.
Control-identity tests cover foreign cancellation/dispatch with equal sequence
numbers, rejection with no local pending task, cloned local handles, stale local
handles, and retained handles after scheduler replacement. Compile-fail controls
also reject numeric cancellation and forged `TaskId` construction.

Root review identified that the first prototype returned a bare sequence to
control APIs. That allowed a caller to mix scheduler IDs accidentally. The
pre-fix reproduction is preserved under
`target/semantic-scheduler-prototype/root-control-identity-before`: both new
foreign-control regressions failed against the saved original source (exit 101),
while the existing foreign-completion check passed. The original campaign
`bb570f0a96284ce1913a28cc58159d69` is retained as superseded evidence. The final
origin-bound-handle campaign reruns the whole suite; the earlier PASS did not
cover this defect and is not acceptance evidence for the new contract.

The public API example emits each boundary for nine scenarios: ordinary jobs,
conflicts, cancellation, deadlines, revocation, unload/reload, external writes,
CPU budgets and arithmetic errors. The Python oracle is an independent
implementation using arbitrary-precision arithmetic with separate signed-64
multiplication and addition bounds. Three explicit seeds (`0`, `17`, `u64::MAX`)
give **891 boundary comparisons, including 864 ordered job decisions**. Every
row binds schema, fresh session/challenge, scenario and seed. Controls reject
cross-session/challenge rows, missing/extra rows, reordered results, malformed
CLI requests, and a transient wrong value that later reconverges. The first
divergence is retained rather than replaced by a final-state comparison.

Run from the isolated checkout:

```powershell
& 'C:\Python314\python.exe' -B crates/semantic-scheduler-prototype/run.py
```

The runner creates a unique output under `target/semantic-scheduler-prototype`,
checks the original-tree isolation and closed production gates before and after,
records source/config/tool hashes, verifies zero external dependencies through
locked offline Cargo metadata, runs formatting, Rust tests, all-target Clippy
with warnings denied, a release example build, and the Python checks. It clears
inherited Java and Rust build-option overrides, pins the active Rust executables,
and records remaining environment policy. Compiler binaries and host Rust library
files are hashed; this is not a fully hermetic linker/OS image. Sources, tool
files, configuration presence/content and executable are checked again before a
PASS receipt. Failure produces a scoped campaign record and bounded raw logs.
No throughput or whole-server performance claim follows from workflow timings.

## H22.5.F mechanism decisions

All external candidates below are **STUDY**, with no dependency added by this
prototype. `mechanisms-research.json` records the exact versions, crates.io
metadata URLs/response hashes, archive checksums, declared licenses and MSRV
fields. A null MSRV is absent registry metadata, not a compatibility promise.
No candidate archive or implementation is vendored here. Installation would
require its own complete locked dependency/license inventory and measurements.

| Candidate and pin | License | Possible value and migration boundary | Decision and integration risk |
|---|---|---|---|
| Rayon 1.12.0 | MIT OR Apache-2.0 | A persistent pool for CPU-heavy qualified pure jobs can amortize thread creation. Explicit thread count and per-job envelopes would preserve this API. | STUDY. Work stealing does not promise semantic order; pool shutdown does not preempt work. Avoid global pools or unchecked spawn-panic handling. [Primary API](https://docs.rs/rayon/1.12.0/rayon/struct.ThreadPoolBuilder.html). |
| parking_lot 0.12.5 | MIT OR Apache-2.0 | Candidate for measured intake-lock contention with short critical sections. | STUDY. Eventual lock fairness is not job order. Its mutex does not poison on panic, so invariant recovery must be designed explicitly. No contention result justifies replacing std here. [Pinned source](https://docs.rs/crate/parking_lot/0.12.5/source/src/mutex.rs). |
| slab 0.4.12 | MIT | Reusable storage may reduce metadata allocation at larger bounded occupancy. | STUDY. Keys are reused and cannot be task identity; keep checked sequence/generation and an admission cap. The current ≤64-entry bounded deque is simpler. [Primary API](https://docs.rs/slab/0.4.12/slab/). |
| crossbeam-channel 0.5.17 | MIT OR Apache-2.0 | Bounded multi-consumer work queues and selectable control messages may suit a persistent executor. | STUDY. Message capacity does not bound payload bytes, establish quiescence or define effect order. Keep the scheduler's separate reservations and leases. [Bounded channel API](https://docs.rs/crossbeam-channel/0.5.17/crossbeam_channel/fn.bounded.html). |
| arc-swap 1.9.2 | MIT OR Apache-2.0 | Read-mostly publication of one already coherent immutable snapshot. | STUDY. An atomic Arc pointer does not establish writer closure across Java/native state or make revoked data valid. Long-held snapshots can delay reclamation. [Primary documentation](https://docs.rs/arc-swap/1.9.2/arc_swap/). |
| bytemuck 1.25.2 | Zlib OR Apache-2.0 OR MIT | Potential H14 buffer conversion for types whose layout and valid bit patterns are proven. | STUDY; no H12 need. Casting does not define endian, Java layout, packet semantics or ownership. Prefer fallible casts and verified derive constraints; never cast arbitrary JVM objects. [Primary API](https://docs.rs/bytemuck/1.25.2/bytemuck/). |

## Integration gates and domain decisions

| Proposed domain | Current decision | Required evidence before an adapter |
|---|---|---|
| Compression / packet preparation / cache generation | PROTOTYPE architecture | Exact immutable input identity, byte-for-byte reference output, bounded native scratch/output, complete packet-time generations, cancellation cleanup and serialization-order proof |
| Disk read / decompression | STUDY separate I/O lane | Bounded buffers and in-flight reads, immutable file/version identity, IO error and completion ordering; no disk effects inside the pure worker contract |
| Pure worldgen / immutable queries | PROTOTYPE architecture | Qualified pure algorithm, owned snapshot, reproducible seed, exact numerical behavior, complete read footprint and ordered commit parity |
| Redstone, entity interactions, block entities, scheduled/random ticks, mod callbacks | AVOID parallelization at this stage | Proven read/write and observable-effect dependencies, event/exception/RNG order, loader/receiver/class qualification and live writer/lifecycle closure |

The H4 operation capability and H6 retained-state concepts are compatible design
inputs, not imported grants. A future adapter must bind the verified artifact and
certificate graph to actual runtime loader/object identities, operation scope,
registry and lifecycle generations, capture freshness and complete writers. It
must revoke on dependency drift, establish exclusive commit ownership, retain
worker lifetimes, and test every externally observable boundary against the
qualified Java path. No adapter exists here, no runtime profiles become newly
qualified, and Issue #1 live packet-time coherency remains open.
