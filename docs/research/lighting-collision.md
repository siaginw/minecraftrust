# H13 lighting and collision experiment

Independent root rerun passed at
`target/architecture-hardening/h13-light-collision-90e43dd5b31c/receipt.json`,
SHA-256 `c8a6344be129410139fc19a9509fb81d90f4d4caed25bb4fd2aaf2791f4fb5e0`.
It binds the completed 99-source inventory, including all dependency notices,
and repeats the seven Rust tests, four Job controls and three fresh Forge forks.
The root's 378 timing rows and grouped measurements are retained in
`machine/architecture-hardening/h13-light-collision.json`. The tables below
describe the earlier agent campaign; both campaigns support the same bounded
decision and neither establishes a whole-server performance improvement.

The bounded experiment passed exact comparisons with actual initialized Minecraft
1.12.2 / Forge 14.23.5.2860 Java definitions. Keep the native scalar collision
batch and changed-cell lighting frontier as isolated migration candidates. Park
the current rebuilt spatial index: it saves predicates in sparse scenes but
costs much more on clustered scenes. Production integration remains parked.

This is a primitive collision batch and a finite block-light grid. It does not
implement entity movement, a world lighting engine, skylight, arbitrary mod
callbacks, live capture authority, or Minecraft 1.12.1 support. No production
caller, gate, default, compression backend, MCK6 behavior or root dependency
changed. The original checkout was only inspected by the isolation guard.

## Reference Java and admitted domain

The harness starts fresh offline FML JVMs using the existing H10 runtime pattern.
The compile-only SRG support jar is excluded from the runtime classpath. A passive
observer records actual loaded definitions and `LaunchClassLoader` identity;
qualification validates the pinned runtime jars and 15 selected class hashes.
The V2 canonical verifier additionally records raw, semantic and declaration
order identities for those classes. Method names and descriptors are listed in
`tools/lighting-collision-experiment/provenance.json`; whole-class raw identities
bind their bodies, rather than pretending a method-name match is qualification.

Collision invokes actual `AxisAlignedBB.func_72326_a` and the three axis-offset
methods `func_72316_a`, `func_72323_b`, `func_72322_c`. Every lane returns ordered
intersecting shape indices and exact binary64 offset bits. Each axis operates on
the same fixed query box. This deliberately omits `Entity.move`'s sequential box
translation, stepping, sneaking, entity interactions, world border and callbacks.
Shapes come from already constructed immutable Java AABBs, including the Java
constructor's endpoint normalization. Rust does not substitute a physics library.

Lighting uses an explicitly detached `World` subclass, constructed normally with
the same offline pattern as owned capture. It has no provider or server loop.
It overrides grid block/light access, loaded-area answers and the no-skylight
answer. Outside the finite grid is opaque stone with zero block light. The oracle
invokes actual private `World.func_175638_a` to independently solve the fixed
point, and actual `World.func_180500_c` to apply each mutation to the prior grid.
All final grids agree with both Rust algorithms. The six admitted clean-runtime
states are air, stone, glowstone, torch, water and glass. Emission and effective
opacity are captured from those exact states.

This palette does not establish that arbitrary world-dependent emission or
opacity callbacks are safe to capture. Forge exposes such callbacks in its
[1.12.x World patch](https://github.com/MinecraftForge/MinecraftForge/blob/1.12.x/patches/minecraft/net/minecraft/world/World.java.patch).
The native API rejects an explicit callback flag; zero is only a harness
precondition, not a generated compatibility certificate. A synthetic Java control
retains callback order `0,1,2`, propagates the third callback's failure and keeps
the prior published result. No actual dynamic mod callback is moved to Rust.

## Rust parity, research and optimization

The scalar collision implementation visits shapes in original order, preserving
strict contact tests, double precision and scalar operation order. The optimized
variant builds conservative four-unit buckets once per batch, shares the captured
immutable shapes across its queries, then sorts candidate IDs into original
order before exact predicates. Oversized shapes remain in an always-considered
list. Excessively wide queries use the scalar path. Duplicate shapes retain
distinct original indices. The index is rebuilt inside every measured operation;
there is no unqualified persistent cache.

The lighting parity lane solves from zero with dense synchronous passes. The
optimized lane uses contiguous byte grids, packed `u32` cell indices, a queued
bitset and a dirty FIFO. It reads neighbors only when needed and enqueues them
only after a changed value. The previous initial grid and complete dirty set must
already be correct. Missing dirty mutations are not detected by this prototype.
Only final values are qualified; intermediate write order is outside this
callback-free domain.

| Primary source, reviewed pin | License and reuse | Decision and relevance |
| --- | --- | --- |
| [Starlight technical details](https://github.com/PaperMC/Starlight/blob/cca03d62da48e876ac79196bad16864e8a96bbeb/TECHNICAL_DETAILS.md), `cca03d62da48e876ac79196bad16864e8a96bbeb` | [LGPL-3.0-only](https://github.com/PaperMC/Starlight/blob/cca03d62da48e876ac79196bad16864e8a96bbeb/LICENSE); no code copied or dependency installed | BORROW_ALGORITHM: compact positions, dirty work and fewer redundant reads. The archived modern engine and its version-specific benchmarks do not qualify 1.12.2 behavior. |
| [Folia](https://github.com/PaperMC/Folia/tree/612d9bd8569fe1a6008a05325af3fad66ef1cef7), `612d9bd8569fe1a6008a05325af3fad66ef1cef7`, and [region ownership documentation](https://docs.papermc.io/folia/reference/region-logic/) | [GPL-3.0-only patches](https://github.com/PaperMC/Folia/blob/612d9bd8569fe1a6008a05325af3fad66ef1cef7/PATCHES-LICENSE); inherited components retain their licenses; no copied scheduler | STUDY: a state owner also needs its dependency neighborhood. This experiment does not implement region scheduling or cross-region publication. |
| [Parry AABB](https://github.com/dimforge/parry/blob/3383f51cbbe9af70565427e7a66c605e1557c1fc/src/bounding_volume/aabb.rs), `3383f51cbbe9af70565427e7a66c605e1557c1fc` | [Apache-2.0](https://github.com/dimforge/parry/blob/3383f51cbbe9af70565427e7a66c605e1557c1fc/LICENSE); no dependency | STUDY broad-phase designs. Its general AABB intersection includes equality at contact, unlike the tested Minecraft strict predicate; it is not a drop-in narrow phase. |
| [glam DVec3](https://github.com/bitshifter/glam-rs/tree/0090cbeca303d076d87a8034a75ede85177247bc), `0090cbeca303d076d87a8034a75ede85177247bc` | [MIT](https://github.com/bitshifter/glam-rs/blob/0090cbeca303d076d87a8034a75ede85177247bc/LICENSE-MIT) OR [Apache-2.0](https://github.com/bitshifter/glam-rs/blob/0090cbeca303d076d87a8034a75ede85177247bc/LICENSE-APACHE); no dependency | STUDY double-vector layout; no arithmetic reassociation, narrowing or custom floating-point physics. |
| [jni-rs 0.22.4](https://github.com/jni-rs/jni-rs/tree/5ae9458a4ec44c5318f37ddc7569c1d4ae8a69e7) | MIT OR Apache-2.0; isolated exact dependency with default features disabled | ADOPT for experiment transport only. The standalone lockfile and 25 registry package source inventories are bound; production JNI is untouched. |

The spatial buckets and worklist are independently implemented. Repository pins,
license URLs and retained dependency notices are in `provenance.json`. Minecraft
jars, class dumps, compile support and derived binary fixtures remain target-only;
the experiment does not relicense or redistribute those implementations.

## Correctness, rejection and bounds

The campaign ran seven Rust tests, four existing Windows Job lifecycle controls,
strict scoped formatting and Clippy, release DLL compilation and Java 8
`-Xlint:all -Werror` compilation. All passed. Each of three fresh JVMs passed:

- 25 collision scenes: counts 0/16/256/2048, sparse/clustered geometry, negative,
  zero and near-world-limit coordinates; exact contact, next-representable
  contact, signed zero, subnormal deltas, reversed constructor endpoints,
  degenerate/duplicate and oversized shapes.
- 30 lighting updates across sides 8, 18 and 32: source addition/removal,
  overlapping sources, stone/water/glass opacity changes and finite boundaries.
- 45 explicit control assertions, including the 30 accepted actual Java light
  updates. These are not 45 separate adverse fixtures. Other controls exercise
  callback rejection, malformed/nonfinite input, invalid light levels/dirty
  indices, capacity, aliasing, null JNI input, retry and callback failure order.
- 55 binary oracle fixtures (3,868,368 bytes per fork), with exact source inputs
  and expected bytes/bits, each length and SHA-256 verified.
- An intentional signed-zero mismatch that produces first-divergence index 1,
  expected `8000000000000000`, actual `0`, with JSON and binary evidence. Actual
  parity checks passed; this separate negative control proves detection works.

Collision admits at most 4096 shapes and 128 queries, finite coordinates within
±30,000,010 and movement magnitude at most 64. Small shapes contribute at most
64 index entries each. Temporary cell enumeration is bounded at 4096 entries
before the small-shape filter; that cost is included. Query expansion beyond
4096 cells falls back to scanning. Worst-case result element capacity is
`1 + 128 * (4 + 4096)` longs. Java and JNI validate capacity before result writes.

Lighting admits side 2..32, byte values 0..15, at most N dirty indices, N queued
cells and 128N processing steps. Exhaustion returns no successful result. The
queue's logical payload is at most 4N bytes and the queued marker uses
`8 * ceil(N/64)` bytes; allocator capacity and metadata are additional. Queue
peak was 981 for the measured addition and 2074 for removal (N=5832).

Each runtime process has a 512 MiB heap, 1 GiB Job commit limit, one-process limit,
180-second deadline and bounded captured output. No `world`, `eula.txt` or
`server.properties` may appear. Build/test subprocesses have deadlines and
file-backed logs, but are not adversarial memory sandboxes. Failures and partial
logs remain available. The first attempted runtime was rejected by the offline
configuration guard because Windows text output used CRLF; writing the required
configuration bytes fixed it. Its failed receipt is retained, not rewritten.

## Complete operation measurements

Three fresh JVM forks each ran six rotated sample groups after per-lane warmup:
378 samples total, 18 samples per condition/lane. Collision samples contain
eight complete 48-query batches; lighting samples contain one update. Values below
are **median [minimum, maximum] microseconds per complete operation**, derived
from raw command output, not throughput estimates. The shared Windows host was
not exclusive. Small sample counts, JIT and GC noise preclude whole-server claims.

| Collision scene, 48 queries | Actual Java | Rust scalar | Rust rebuilt buckets |
| --- | ---: | ---: | ---: |
| 256 sparse shapes | 98.594 [78.350,156.675] | 35.569 [29.475,71.938] | 58.056 [35.950,93.938] |
| 2048 sparse shapes | 820.700 [597.475,1013.650] | 318.369 [199.738,1072.450] | 294.281 [225.488,614.837] |
| 256 clustered shapes | 174.906 [109.425,378.550] | 113.056 [70.125,140.950] | 510.587 [308.738,659.987] |
| 2048 clustered shapes | 1165.387 [908.212,1625.850] | 686.550 [481.175,848.075] | 4230.012 [3392.425,4832.225] |

The index reduces sparse exact-shape visits from 12,288 to 496 and from 98,304
to 992, but build/deduplication costs consume the benefit. Clustered inputs retain
all exact visits and add substantial indexing overhead. The small apparent gain
on 2048 sparse shapes is noisy and not a reason to choose this index generally.
Native-only last-operation median compute was 25.5/210.0/69.45/576.75 us for the
scalar rows and 32.85/243.3/476.25/3767.35 us for the bucket rows. These are a
separate last-operation sample, not subtractable from a batch-average wall time.

| Block light, side 18 (5832 cells) | Actual Java update | Rust dense from zero | Rust dirty frontier |
| --- | ---: | ---: | ---: |
| Add source | 1301.450 [947.900,2097.200] | 1561.750 [1030.600,2325.900] | 215.100 [143.800,314.300] |
| Remove only source | 1473.350 [1175.900,2859.100] | 115.900 [91.000,239.600] | 287.000 [229.400,510.900] |
| Unchanged update | 6.900 [5.000,10.500] | 1264.600 [1018.100,2129.700] | 48.950 [29.400,62.900] |

Addition neighbor reads fall from 528,447 dense to 39,714 frontier. Removal is
different: recomputing the all-dark grid needs 33,048 reads while draining the
old lit frontier needs 63,648 and 8,737 value changes. This does not establish a
general removal strategy for multiple sources. The unchanged frontier does only
15 neighbor reads, but capturing the entire grid and crossing JNI dominates.
Native last-operation median compute for frontier add/remove/unchanged was
174.0/254.4/8.3 us. Dense and frontier deliberately do different algorithmic work
while targeting the same final callback-free state.

Java wall timing includes capture, array materialization, JNI transfers, native
validation, computation, index construction, output transfer, result trimming
and result hash. Java uses its retained actual objects. Collision output arrays
have the same worst-case capacity across all lanes. Lighting Java clones the
prior grid then calls the real update method; Rust captures emission/opacity
for the whole grid on every operation. Scene/FML construction and cold startup
are outside operation timers. Full fork process durations were
9.271/9.247/9.927 seconds; observed peak process commit was
605,499,392 / 596,951,040 / 607,440,896 bytes. Commit is not RSS or native live bytes.

## Copy and memory accounting

The measured input/output region payloads are exact. Collision input copies
`8 * (6S + 9Q)` bytes into Rust: 15,744 bytes at S=256 and 101,760 at S=2048,
Q=48. The Java capture additionally writes that many element bytes before JNI.
Sparse result payload is 2,696 bytes; clustered payload is 56,840 or 443,912 bytes.
Each native result copies that payload to Java plus 64 diagnostic metric bytes.
`Arrays.copyOf` then copies the used result payload in **every** collision lane.
Both Java worst-case output zeroing and native input/result storage cost time;
array headers, allocator sizes and hidden JVM copies are not measured.

Lighting input copies `3N + 4D` bytes and output copies N plus 64 metric bytes.
For N=5832 and D=1 that is 17,500 in and 5,896 out per native call. Java writes
2N emission/opacity element bytes during capture; the Java reference instead
clones N prior light bytes. Native signed/unsigned materialization and working
grids are explicit, but optimizer/library copy elision is not inferred. The
metric's `4N+4D` element-byte value is a storage ledger, **not** allocation count,
capacity, peak memory, or all bytes touched. BTree allocations, queue capacity,
GC moves, kernel/JVM internal copies and process RSS remain unknown. No zero-copy,
allocation-free or low-overhead telemetry claim is made.

## Architecture value, compatibility ladder and decision

| Candidate | Performance value in this campaign | Migration value | Decision |
| --- | --- | --- | --- |
| Exact scalar collision batch | Better complete-operation medians in all admitted scenes; noisy ranges retained | Establishes ordered raw-double primitive parity and owned immutable captures | KEEP as isolated candidate; no entity movement replacement |
| Rebuilt spatial buckets | Sparse predicate savings; cold build and clustered overhead fail the general case | Demonstrates conservative candidate generation and ordering constraints | PARK this implementation; do not install it on a production path |
| Dense block-light fixed point | Useful correctness oracle; poor on addition/unchanged, unusually cheap for all-dark removal | Provides independent finite-grid parity baseline | KEEP for validation, PARK as general runtime algorithm |
| Dirty packed frontier | Benefits changed add/remove in these grids; unchanged capture loses substantially | Useful path toward retained native light with complete dependency generations | KEEP for bounded follow-on; PARK production integration and unchanged JNI calls |

The next integration experiment must first establish H4/H6 ownership and versioned
state/light dependency identities, a certified immutable capture and complete
dirty tracking. It must preserve Java callbacks and exception order through an
explicit fallback boundary. Only then should it compare retained batches,
unchanged-update bypass, capture amortization, unload/cancellation and stale
publication against actual Java behavior. An arbitrary zero callback flag or
caller-supplied dirty list cannot authorize a native result. The immutable shape
reuse here lasts one batch only, so generation-keyed persistent shape retention
and dynamic mod behavior remain unimplemented.

Compatibility achieved: Rust deterministic fixtures → exact clean-runtime
primitive fixtures → three fresh initialized offline Forge JVMs. Still absent:
actual entity movement, full light/skylight and chunk-boundary semantics, runtime
callback qualification, Revelation/other transformers, 1.12.1 qualification,
live shadow and combined production integration. There is no production authority.

## Evidence and independent reproduction

Run from the isolated checkout, after coordinating an exclusive agent measurement
window (the host itself remains shared):

```powershell
python tools/lighting-collision-experiment/run.py --forks 3 --rounds 6
```

The runner resolves the absolute Rust toolchain, builds a fresh target directory,
and checks seven Rust tests, four Job controls, 25 collision/30 light cases,
45 assertions, 55 fixtures and 126 samples **per JVM**, then verifies hashes.
No production workspace regression is claimed by this isolated command.

Agent campaign receipt:
`target/architecture-hardening/h13-light-collision-fd88a35e4dcd/receipt.json`
SHA-256 `63843d6a1f9dece8bd3aa05412096c5d40b3133e99afed501ba087ec14624394`.
It binds 93 source inputs, 16 tools, all 25 registry package archives and complete
source-file inventories before/after, plus runtime artifacts, compiled classes,
observer, mappings, DLL, raw stdout/stderr, fixture digests, canonical identities,
loader names and isolation checks. Release experiment DLL SHA-256:
`10b9839850e72680e22068601f00b6ffdd9bbbb135ad833be3f91acc80ee0d70`.
The adjacent `derived-measurements.json`, SHA-256
`fde5f4f61715262a9623ed8b9218bcc8b9b8f67d28b746cc5c0165df4590195c`,
contains all unrounded grouped statistics and binds that receipt.

The retained first failed receipt is
`target/architecture-hardening/h13-light-collision-1fd0d2b78b49/receipt.json`,
SHA-256 `0c7db9e12e6b9501ab442ede5cd204cdcc42d649cbedec39a1b5f342042a069f`.
Preliminary correctness-only receipt `h13-light-collision-32f6f10b8a11` had 41
assertions; it is historical and does not supply the final campaign counts.

After the measured campaign, provenance review found three registry archives
(`jni`, `jni-macros`, `jni-sys-macros`) omit repository-root license files.
Six license texts were retained from each archive's exact VCS commit and their
URLs/hashes added to provenance. There are now 49 retained license/notice files
covering all 25 dependencies. No Rust, Java, harness timer or dependency version
changed. The measured receipt retains its earlier exact source inventory; it is
not silently relabeled as binding these subsequent notice additions. The root's
independent final rerun must bind the completed provenance inventory before
commit. This report is derived documentation outside the runner's source set.
