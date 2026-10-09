# H22.5.A — Deterministic differential event replay

Decision: **LIMITED_USE**. Adopt the first-divergence comparison pattern for
migration tests. The implemented prototype is a bounded oracle for its own
`REPLAY_TOY_V1` model; it supplies no Minecraft/Forge parity or runtime
qualification evidence. Production native authority remains disabled.

## Scope and independence

`tools/differential-replay/java/ReferenceReplay.java` and the standalone Rust
candidate implement the written rules below separately. Java uses a priority
queue and advances directly between due events. Rust uses a sorted vector and
advances one tick at a time. Java writes NBT through `DataOutputStream`; Rust
writes tagged big-endian bytes. Neither implementation uses copied Mojang code,
nor calls the other implementation or a shared implementation of the model.
The Python driver performs protocol checks and comparisons, not the state
transition algorithm. Independent numerical golden checks supplement agreement.

The model has 16 byte-valued cells, one entity with a signed 32-bit position and
nonnegative signed 32-bit health, a clock, scheduled updates, a monotonic schedule
serial, a 32-bit unsigned RNG state, and two inert callback observers. Each trace
contains an immutable initial state, explicit seed and callback order (`AB` or
`BA`), and 1–2000 ordered player/server commands. The initial clock and schedule
serial are zero and the queue is empty. No wall clock, external randomness,
filesystem action, network action or arbitrary foreign callback participates in
the state transition.

## Exact state transition contract

| Command | Source | Behavior |
| --- | --- | --- |
| `SET cell value` | PLAYER | Cell 0–15, value 0–255. Record `[cell,old,new]` even if unchanged; emit packet bytes `02 cell value`. |
| `MOVE delta` | PLAYER | Add signed i32 delta using two's-complement wrap. Emit `01` followed by the resulting i32 position in big-endian order. |
| `SCHEDULE delay cell delta` | SERVER | Delay 1–1000, cell 0–15, delta −255–255. Enqueue `[clock+delay,++serial,cell,delta]`; order by due time then insertion serial. |
| `TICK count` | SERVER | Advance 1–100 ticks. Apply every due update in queue order; wrap cell values modulo 256. Record each individual old/new mutation and each due callback. |
| `RNG bound` | SERVER | Bound 1–256. Update `rng=(rng*1664525+1013904223) mod 2^32`; set cell 0 to `rng mod bound`, record mutation, emit `03 value`. |
| `DIV denominator` | SERVER | Nonnegative i32 denominator. Positive values divide health with integer truncation. Zero produces `CONTROLLED_ERROR / DIVIDE_BY_ZERO`, with no mutation or callback. |
| `SAVE` | SERVER | Observe unchanged state; ordinary successful-command callbacks occur. |

Every successful command appends `A:OP,B:OP` or `B:OP,A:OP`. Every scheduled
mutation first appends the corresponding ordered `DUE` pair. Callbacks are
observations only. Out-of-spec input is rejected; malformed input is not a
successful controlled simulation error.

One semantic boundary exists at the immutable initial snapshot and after every
command. Batched `TICK` preserves **all** intra-command mutations and callbacks
in their observed order; it does not collapse them into final cell values. The
prototype does not instrument arbitrary Java instructions or claim that a toy
command boundary is sufficient for a future Minecraft adapter.

## Boundary data and save format

Every boundary contains all six required observable categories:

1. `world_mutations`: ordered `[cell,old,new]` rows for this command.
2. `entity_state`: current `[position,health]`.
3. `scheduled_ticks`: complete queue ordered by due time/insertion serial.
4. `packet_outputs`: ordered exact toy packet bytes as lowercase hex.
5. `save_state`: complete canonical uncompressed NBT bytes as lowercase hex.
6. `callbacks`: ordered observer/event strings.

`rng_state`, outcome and controlled-error identity are additionally compared.
The save is a real unnamed `TAG_Compound`, with named `TAG_Int` x and health;
`TAG_Long` time, rng (zero-extended u32) and serial; `TAG_Byte_Array` blocks;
`TAG_List<TAG_Long>` scheduled (flattened due/serial/cell/delta tuples); and
`TAG_End`. Named tags use the exact documented order and ASCII names. This is a
toy save schema, not a Minecraft world save schema. Toy packets likewise make no
claim about Minecraft wire compatibility.

The initial snapshot must agree before any command is issued. Per-command event
arrays and complete current state are compared together. Field priority for
reporting simultaneous mismatches is outcome, error, mutations, entity, queue,
packets, save, callbacks, RNG. The first differing list index is recorded; this
priority is deterministic reporting order, not a claim about causal precedence.

## Reusable adapter protocol and failure preservation

`replay.replay(trace, reference_argv, candidate_argv, output_root)` starts two
explicit commands with `shell=False`. Each gets the same `INIT` line and then
one identical command at a time. The driver issues the next command only after
the previous boundary has arrived from both adapters and passed comparison.
The caller's trace is privately deep-copied and validated once before either
adapter starts; later caller mutations cannot alter the saved trace, binding,
initial state or command sequence.

Each output is one UTF-8 JSON line with exactly the specified fields:
`schema=REPLAY_BOUNDARY_V1`, fresh random `session`, independent random
`challenge`, SHA-256 of the canonical immutable trace, command index and text,
all six categories, RNG state, outcome and error. Duplicate keys, nonfinite
JSON, omitted categories, wrong binding, wrong order, malformed bytes, output
over 1 MiB and a missing/timed-out boundary fail the adapter protocol. Index zero
is `INIT`. No expected-output fallback exists. Unexpected trailing stdout,
nonzero exit and stderr are retained and rejected at adapter termination.

The driver stops issuing commands at the first observed semantic or protocol
failure and terminates both adapters. It preserves the exact trace, bindings,
both observed stdout prefixes, stderr, exit status, the first mismatch (including
both valid boundary rows where available), first differing field/index, and
hashes in a fresh UUID directory. A protocol failure is `DIVERGED` with an
`ADAPTER_PROTOCOL` or `ADAPTER_TERMINATION` category; it is never reported as
semantic parity. The top-level campaign binds source, tool, executable and class
file hashes before/after execution and retains build/test logs.

The adapter has fixed upper bounds, which tests may lower but cannot disable or
increase: four queued lines, 1 MiB per stdout line, 8 MiB total recorded stdout,
256 KiB total recorded stderr, a 10-second boundary timeout, and a 30-second
adapter lifetime. Both streams are read in at most 4096-byte chunks into bounded
raw-prefix files. Overflow kills the adapter, preserves the permitted prefix and
records an explicit resource error and truncation flags. Stderr never writes
directly to an uncapped file. Queues use nonblocking insertion; overflow fails
instead of blocking a pipe reader.

An additional **4 MiB cumulative budget covers both adapters' accepted boundary
lines for the entire replay**, independent of the 2000-command bound. It is
checked before JSON parsing. Only the latest pair is retained in memory;
previous rows live in the capped raw-prefix files. Thus a long replay cannot
retain 2000 separate 1 MiB parsed rows. The two adapters together can preserve at
most 16.5 MiB of raw stdout/stderr, plus bounded trace, request, final-pair and
first-divergence receipts. Each reader uses a nonblocking pipe and explicit stop
event, with bounded joining and descriptor closure. This requires Python 3.12+
on Windows; unavailable nonblocking pipe support fails explicitly. It prevents
an inherited open pipe from indefinitely blocking a reader, but it is not a
general descendant-process sandbox.

The transport and comparator can support later qualified adapters. Such an
adapter needs a new explicit versioned trace/schema, a justified semantic
boundary policy, immutable qualified snapshot acquisition, deterministic RNG
and callback scheduling control, complete observed effect capture, and the
existing runtime certificate/lifecycle gates. The current `validate_trace`
deliberately accepts only this toy specification. Tool pins and nonce binding
are reproducibility mechanisms, not proof that arbitrary JVM code was executed.

## Controls and measured result

The campaign runs 30 positive Java/Rust comparisons: six numerical/ordering/error
golden cases and 24 seeded mixed traces of 32 commands. Seventeen negative
controls test a reconvergent entity fault, reordered/missing/extra callbacks,
missing mutation, missing scheduled update, wrong RNG, extra packet, corrupt
save, wrongly accepted controlled error, schema/session/sequence mismatch,
nonzero/empty/malformed adapter output, and altered initial state.

Every injected command fault is caught at command 1; the altered initial state
is caught at boundary 0 with no commands issued. Four Python protocol tests also
exercise trace bounds, command ownership, required categories, duplicate and
nonfinite JSON, stale challenge/trace hashes and invalid field types. Five Rust
unit tests cover independent RNG golden values, queue order/modulo wrap,
controlled errors, signed arithmetic wrap and invalid commands.
Ten additional Python controls exercise stdout without newlines, stdout line
limits, stderr floods, bounded queue overflow, silent-process lifetime and
boundary timeouts, whole-replay byte budgets, extra output, mutable caller
input and invalid/increased limits. Resource failures preserve capped raw
prefixes and explicit error/truncation receipts; all observed reader threads
terminated, and prefix files stopped growing after shutdown. The campaign keeps
41 resource-control artifact files and their hashes instead of deleting that
negative evidence after testing.

The reconvergent candidate adds one extra position unit after command 1 and
subtracts it after command 2. The comparator stops at command 1. A **separate**
fresh diagnostic replay runs to completion solely to establish that the final
entity, queue, RNG and complete NBT state become equal. Its status is
`DIAGNOSTIC_ONLY`, never PASS. This demonstrates why final world equality cannot
replace event-level comparison.

Frozen campaign: `target/differential-replay/13cc743898b84ad1950e399d31708c9f/campaign.json`.
SHA-256: `534eaf3d4dc8f9cdd462dbf8db2c52d664aad7bcd84d1626b014d495ed172854`.
Result: **47/47 controls PASS**, 836 paired boundary rows compared across
positive and negative runs; the separate reconvergence diagnostic is excluded
from that count. The 17 intended divergences were all detected. Source/tool/build
artifact inventories were unchanged. Rust unit tests (5), Python protocol and
resource tests (14), `cargo fmt --check`, `cargo clippy -- -D warnings`, release
build and Java8 compilation all passed. Full build plus campaign elapsed time was **21.577 s** on
this host. That includes process startup and builds; it is not runtime
throughput, speedup or production latency evidence.

## Limits and integration decision

This establishes the isolated replay mechanism, strict comparison, fault
detection and reproducibility for a small deterministic model. It does not
establish Minecraft fidelity, arbitrary Forge callback safety, multi-threaded
ordering, floating-point parity, live packet/save behavior or migration speedup.
No server was started and no production code, workspace dependency, default,
authority gate or original worktree was changed. No external crate was added.

Use this prototype as an executable pattern and regression oracle. Keep general
Forge callbacks, external effects, unsafe AOT assumptions and production
authorization outside its scope.

## Independent review rerun

The parent review reran the frozen build and complete campaign independently at
`target/differential-replay/7416393607984c89a810fe20abac8a61/campaign.json`
(SHA-256 `d1684295d752322f89a9e932f9ee8276399459a811e7ad10de01f58a860a8179`). All 47 cases,
836 paired boundaries, 5 Rust tests and 14 Python tests passed again.
The committed summary, unmodified campaign and command logs, and explicitly
aggregated case receipts are under `machine/architecture-hardening/h22-5-replay*`.
Raw capped streams and 41 resource-control artifacts remain in the hash-bound
local campaign. This rerun does not broaden the prototype qualification scope.
