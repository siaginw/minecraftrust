# H13 lighting/collision experiment

This standalone crate and offline Java harness have no production callers.
They exercise a deliberately narrow domain against actual pinned 1.12.2/Forge
14.23.5.2860 definitions. They are not a world lighting engine, entity movement
implementation, mod compatibility layer or proof of 1.12.1 support.

Run `python tools/lighting-collision-experiment/run.py --explore` for a first
unqualified correctness discovery. Once exact runtime definition pins are
reviewed in `provenance.json`, omit `--explore`. Timed campaigns require a
coordinated measurement window, with `--forks 3 --rounds 6`. Every invocation
uses a fresh target directory and fresh offline initialized Forge JVM. The
compile-only SRG jar is excluded from the runtime classpath. A passive agent
records final runtime definitions and loader identity; jar/dump/tool hashes are
checked. No server, provider, tick loop, EULA, authentication or live modpack
campaign starts.

## Collision contract

The oracle calls actual `AxisAlignedBB.func_72326_a` and `func_72316_a`,
`func_72323_b`, `func_72322_c`. Each query returns the exact ordered intersecting
shape indices and raw binary64 bits of independently clipped X/Y/Z distances.
This is a batch of primitive calls on a fixed query box, **not** Entity.move's
ordered box translations, step-up, sneaking, world-border logic, callback
dispatch or entity interactions. Shapes are captured from already constructed
immutable Java AABBs; constructor normalization is not reimplemented in Rust.
Nonfinite values, unsupported coordinates/movement and malformed shapes fail.
Signed zero, touching faces, degenerate shapes, reversed constructor endpoints,
subnormal distances, negative coordinates, duplicate shapes and oversized shapes
are tested. No float narrowing, fused operations or alternate physics are used.

The parity lane linearly visits the captured shapes in original order. The
optimized lane builds a conservative 4-unit spatial bucket index once per batch
and retains its immutable shape vector across all queries in that batch. Swept
query bounds conservatively include all three independent axis offsets. Candidate
IDs are deduplicated and sorted by original position before exact predicates.
Shapes spanning more than 64 buckets stay in an always-considered list; query
coverage above 4096 buckets falls back to the full shape list. This avoids both
missed oversized shapes and unbounded spatial expansion. No index survives its
JNI call, so measurements include rebuilding it. There is no persistent-scene
handle, silent stale cache or H6 lifecycle integration.

Limits: 4096 shapes, 128 queries, finite box coordinates within ±30,000,010,
movement magnitude at most 64, index entries at most 64 per admitted small
shape. Result arrays are capacity-checked before output writes. Callbacks are
an explicit rejection flag in this isolated API; this flag is not a runtime
qualification certificate. A synthetic Java fallback control demonstrates
ordered callback dispatch and exception-before-publication. It does not
inventory or replace actual Forge/mod callbacks.

## Lighting contract

A detached `World` subclass uses the actual constructor pattern already used by
the offline owned-capture harness. It overrides only finite-grid block/light
access, loaded-area answers and a no-skylight answer. A provider request throws.
Positions outside the finite grid are opaque stone with block light zero. The
oracle invokes actual private `World.func_175638_a` through reflection to find
the fixed point and actual `World.func_180500_c` to test individual updates.
Runtime-derived bodies and class dumps remain under target; no decompiled
Minecraft implementation is copied into this repository.

The state palette is explicit clean-runtime air, stone, glowstone, torch, water
and glass, with captured emission and opacity clamped to the effective 0..15
range. The Rust parity algorithm independently solves the final fixed point
using dense synchronous relaxation from zero. The optimized algorithm starts
from the previous qualified fixture result and processes a dirty frontier with
packed u32 cell indices, a one-bit queued marker and contiguous byte grids.
New writes enqueue neighbors only when a cell changes. Opacity/source early
exits avoid unnecessary neighbor reads. Sources, removal, overlap, opacity
changes and finite boundaries are tested over sides 8,18,32. At most 32³ cells,
N queued entries and 128N processing steps are admitted; exhaustion returns an
error before a successful output. No negative or unknown light values are
accepted. Outside-grid dependencies are fixed fixture boundaries.

The incremental routine's initial grid and complete dirty list are a harness
precondition; this experiment does not certify external mutable state, detect
omitted mutations or implement a capture authority. Final light values are
compared; intermediate write ordering and callback observations are intentionally
outside the callback-free domain. This cannot qualify light listeners, custom
emission/opacity hooks, Phosphor, skylight, chunk boundaries with unknown
neighbors, dynamic unload, chunk-load effects or incremental state admission.
An actual integration would need H6 generations and complete dependency/dirty
tracking plus a qualified callback boundary. Merely setting the API's callbacks
flag to zero does not establish those facts.

## Timers, copies and unknowns

Java timing includes the complete bounded operation and result hash. Collision
Rust lanes include Java object-to-array capture, JNI array-region transfers,
native validation/materialization, index construction, queries, result transfer
and Java result trimming. The reference uses the retained actual Java boxes.
Lighting Rust lanes include emission/opacity capture, transfers, computation
and output/hash. The actual Java update clones its initial light grid and calls
the real engine update method. Setup/scene construction and FML/JIT startup are
outside steady-state operation timing and appear separately in command logs.

All modes receive the same recorded fixture state. Lane order rotates each
sample; raw output hashes must agree. Collision warms all three lanes and uses
eight operations per sample. Lighting has three warmup operations per lane,
then one update per sample. Shared-host/JIT/GC noise remains; raw min/median/max
distributions are evidence, not whole-server projections.

Eight native metric slots contain: exact-shape-tests or neighbor-reads;
bucket-visits or light-updates; index-entry-count or queue-peak; explicit input
region payload bytes; output result payload bytes; compute nanoseconds;
native elapsed nanoseconds before metric/result transfer; input/materialization
element-byte ledger. The last is **not allocation count, allocator capacity,
peak memory or every native allocation**. Another 64 bytes per successful call
are copied for the metrics array itself. Java capture and result-array copy
traffic are reported separately from JNI payloads. Compiler/library/JVM hidden
copies, allocator usable sizes, GC moves and process RSS are unknown. Job
observations report process commit peak, which is not RSS. Native compute timers
exclude input-region copies and output transfers; the Java operation timer
includes them. JNI exception/failure status never qualifies the output, even if
the separate diagnostic metric array was already written.

Registry/tool/source hashes and notices are bound before/after each campaign.
Runtime subprocesses use the existing H9 Job helper: 1 GiB process commit,
512 MiB Java heap, one process, 180-second deadline, bounded captured output.
Builds/tests have file-backed logs and deadlines but are not adversarial memory
sandboxes. No arbitrary mod code or live workload is executed. Failures retain
their logs and first-divergence arrays; they are not converted into successful
historical evidence. Results and the KEEP/PARK decision belong in
`docs/research/lighting-collision.md` after the final source-frozen campaign.

## Research boundaries

`provenance.json` pins Starlight, Folia, Parry and glam primary sources/licenses.
Starlight motivates compact queues and avoiding redundant neighbor reads, but its
modern lighting engine and benchmark claims do not qualify 1.12.2. Folia's
ownership invariants motivate keeping both state and its neighborhood under an
owner; no scheduler is imported. Parry's general AABB intersection includes
contact equality, whereas the tested Minecraft predicate is strict, so it is
not a drop-in narrow phase. glam's double vectors are a layout option, not a
license to reorder arithmetic. None of these implementations is copied or
installed. jni-rs 0.22.4 is the isolated checked transport dependency; its full
lockfile, registry-source identities and notices are retained.
