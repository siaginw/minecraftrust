# Authorized implementation request

/goal Execute the RustCraft Ultra architecture/performance/ecosystem audit as a multi-milestone IMPLEMENTATION PROGRAM, while preserving the paused Revelation live-shadow work for later reuse.

Level: Ultra

This is no longer a read-only audit.

Implement the audit recommendations according to their recommended maturity:

NOW
→ implement fully, validate, integrate, commit

SOON
→ implement serious bounded foundations/prototypes;
  integrate only when evidence justifies it

LATER
→ produce isolated prototypes/qualification evidence;
  do not prematurely productionize

RESEARCH_ONLY
→ produce bounded experiments and explicit go/no-go decisions;
  do not place them on RustCraft's production critical path

DO NOT blindly add every researched crate.
DO NOT sacrifice Minecraft / Forge / mod semantics for benchmark numbers.
DO NOT enable production native chunk authority.
DO NOT push to any remote.
DO NOT rewrite accepted history.

==================================================
0. CRITICAL WORKTREE ISOLATION
==================================================

The original active repository is:

D:\minecraftrust

ZCode has STOPPED cleanly.

Its branch:

issue1-jni-v2

Its committed HEAD:

c4b868d
feat(issue1): validate revelation writer transformers offline

The original worktree contains valuable UNCOMMITTED Revelation live-shadow
work.

DO NOT MODIFY THE ORIGINAL WORKTREE.

Do NOT:

- checkout there
- reset there
- clean there
- stash there
- stage there
- commit there
- build there
- delete files there
- overwrite target evidence there
- resume its Revelation shadow campaign there

Create a completely separate worktree from the accepted committed baseline:

c4b868db2c9e4b03f745bf93c6ebf4fd8a8519e7

Suggested:

git worktree add -b astra/architecture-hardening D:\minecraftrust-astra-hardening c4b868d

Work ONLY in:

D:\minecraftrust-astra-hardening

If that path or branch already exists:

STOP and inspect safely.

Record:

- original worktree HEAD
- original worktree git status
- new worktree HEAD
- new branch
- new worktree path

The original ZCode tree must remain untouched.

==================================================
0A. PAUSED ZCODE REVELATION-SHADOW HANDOFF
==================================================

The original worktree currently contains two meaningful uncommitted items:

1. MODIFIED

tools/live-capture/live_client_session.py

Purpose:

- FML|HS client handshake support
- \0FML\0 handshake-host marker
- ClientHello
- empty ModList
- Ack(2)
- Ack(3)
- server Ack handling
- SB_CUSTOM_PAYLOAD = 0x09

State:

- Python syntax compiles
- byte literals normalized to ASCII escapes
- NOT YET proven by a successful live Revelation join

2. NEW / UNTRACKED

tools/live-capture/run_rev_shadow.py

Purpose:

- Revelation live-shadow runner
- control / shadow-a / shadow-b phases
- port 25575
- persisted-world handling
- polling stop conditions

State:

- server boots
- no valid live parity campaign completed
- no valid compared-event evidence exists

Untracked __pycache__ is disposable noise.

==================================================
0B. READ-ONLY ACCESS TO PAUSED ZCODE WORK IS AUTHORIZED
==================================================

You ARE authorized to inspect the paused ZCode files and preserved evidence
READ-ONLY.

Examples:

git -C D:\minecraftrust diff -- tools/live-capture/live_client_session.py

read:

D:\minecraftrust\tools\live-capture\run_rev_shadow.py

You may inspect preserved target evidence.

Do NOT edit or execute from the original tree.

If useful, copy files into an unrelated TEMP directory for analysis.

Do NOT copy them into the hardened branch yet.

Treat them as reference material until the hardened V2 qualification system
has requalified Clean Forge and Revelation.

==================================================
0C. PRESERVED ZCODE EVIDENCE
==================================================

Offline Revelation:

D:\minecraftrust\target\rev-probe\
  qualification.json
  hook-matrix.json
  anchor-validation.json
  mod-reference-scan.json
  canonical-hashes.txt
  transformed\

Transformer campaign:

D:\minecraftrust\target\rev-campaign\
  LiveWriterPlan.java
  rustcraft-rev-coremod.jar
  insertion\post-hook\
  insertion\post-hook-verification.json
  insertion\insertion-summary.json
  insertion\jvm.log
  diagnose\

Real Revelation server:

D:\minecraftrust\target\rev-server-qual\
  server.log
  server-inventory.json
  server-registry-state-ids.tsv
  server\

Paused Revelation shadow:

D:\minecraftrust\target\rev-shadow\
  campaign-receipt.json
  server-control\
  server-shadow-a\
  server-shadow-b\

Committed/profile data includes:

tools/live-capture/revelation-runtime-pins.json
tools/live-capture/revelation-profile.json
tools/live-capture/revelation-live-shadow-profile.json

IMPORTANT:

The paused Revelation shadow produced NO VALID compared-event/live-parity
results.

Do NOT treat any paused control/shadow receipts as a successful Revelation
live-shadow qualification.

==================================================
0D. KNOWN PAUSED-SHADOW FINDINGS
==================================================

Two harness findings are already known.

1. FORGE/FML CLIENT HANDSHAKE

The Revelation server rejects a vanilla protocol client:

"This server has mods that require FML/Forge to be installed on the client."

ZCode implemented a candidate FML|HS handshake in live_client_session.py.

It is NOT yet proven by a successful Revelation client join.

An empty client ModList may still be rejected by @NetworkCheckHandler mods.

When live shadow eventually resumes:

- prove FML join independently first
- if rejected, inspect the exact server rejection
- derive required id/version entries from the pinned runtime inventory
- never remove server mods or fake acceptance

2. PERSISTED WORLD RELIABILITY

target/rev-server-qual/server/world was produced during an earlier hard-kill
iteration and one copied world failed inconsistently.

Use only a CLEANLY STOPPED persisted Revelation world as a future qualification
source.

Never qualify from a mid-write world snapshot.

==================================================
0E. IMPORTANT CONSEQUENCE OF THE ULTRA AUDIT
==================================================

DO NOT resume Revelation live shadow before the qualification hardening below.

The current Revelation profile at c4b868d uses:

CANONICAL_ID_V1

The Ultra audit found that CANONICAL_ID_V1 omits behavior-relevant bytecode
information.

Therefore:

- the paused shadow harness work is useful
- its qualification identity is not strong enough for future authority evidence
- no V1 shadow result should be promoted as final qualification
- implement CANONICAL_ID_V2
- regenerate/requalify Clean Forge
- regenerate/requalify Revelation
- only then resume Revelation live shadow

==================================================
1. GOVERNING AUDIT
==================================================

The governing review is:

RUSTCRAFT ULTRA ARCHITECTURE + PERFORMANCE + ECOSYSTEM AUDIT

Audit baseline:

c4b868d

Read the entire audit before implementation.

Treat its recommendations as hypotheses to validate.

If implementation evidence contradicts the audit:

preserve the evidence
correct the recommendation
do not force the audit conclusion.

==================================================
2. PROJECT NORTH STAR
==================================================

RustCraft's goal is NOT merely:

"make Forge compatible with Rust."

The actual target is:

A SUBSTANTIALLY FASTER MINECRAFT SERVER ENGINE IN RUST

while preserving observable:

- Minecraft behavior
- Forge behavior
- mod behavior
- protocol behavior
- persistence behavior

Long-term Rust ownership should include:

- world state
- chunks
- block mutation
- derived caches
- ticking
- entities
- collision
- lighting
- worldgen
- networking
- packet generation
- persistence
- scheduling
- player tracking
- native data layouts

Java should progressively become:

a compatibility shell for existing mods

rather than the Minecraft server engine.

Potential very-long-term target:

Rust-hosted or Rust-implemented Java bytecode compatibility runtime

but ONLY if justified by evidence.

Targets eventually include:

Minecraft 1.12.1
Minecraft 1.12.2
modern Minecraft versions

Do NOT simply translate Mojang's Java object graph into equivalent Rust structs.

Exploit:

- retained native state
- data-oriented layout
- fewer representations
- fewer crossings
- fewer copies
- batching
- cache locality
- SIMD
- bounded parallelism
- subsystem fusion
- explicit ownership

==================================================
3. PERMANENT MIGRATION / PERFORMANCE RUBRIC
==================================================

Every major migration candidate follows:

REFERENCE_JAVA
→ RUST_PARITY
→ EXTERNAL_OPTIMIZATION_RESEARCH
→ RUST_OPTIMIZED
→ COMPLETE_BENCHMARK
→ ARCHITECTURE_VALUE
→ COMPATIBILITY_LADDER
→ KEEP / PARK
→ COMBINED_INTEGRATION

Separate:

PERFORMANCE VALUE

from:

JAVA-REMOVAL / MIGRATION VALUE

Do not park something solely because the first Rust parity implementation loses.

Do not retain a permanently slower implementation merely because future
optimization is theoretically possible.

==================================================
4. MILESTONE DISCIPLINE
==================================================

Execute as independently reviewable milestones.

After EACH milestone:

- applicable tests green
- cargo fmt
- clippy where applicable
- relevant Java/tooling compile
- git diff --check
- evidence receipt
- benchmark receipt when appropriate
- concise milestone report
- commit

DO NOT push.

Recommended order:

H1   Qualification identity V2
H2   Generic verifier / evidence certificates
H3   Telemetry/evidence correction
H4   Fine-grained capability architecture
H5   Version-neutral core architecture
H6   Retained native ownership/handoff
H7   Loom lifecycle models
H8   Section layout / packet cache prototypes
H9   NBT / region pipeline
H10  Worldgen fusion
H11  Entity storage experiment
H12  Semantic scheduler
H13  Lighting/collision research
H14  Packet/buffer pipeline
H15  Observability
H16  Representative benchmark suite
H17  Allocator experiments
H18  External project prototypes
H19  JVM/intrinsic research
H20  Compatibility-domain transfer
H21  Event/capability fast paths
H22  Retention/leak cleanup
H23  Clean Forge + Revelation V2 requalification
H24  Revelation shadow resumption

Continue automatically when green.

STOP for review only if:

- compatibility requires intentional behavior break
- a destructive migration requires user choice
- production authority must be enabled
- license/provenance blocks implementation
- accepted Clean Forge guarantees cannot be preserved
- custom JVM work would become critical-path
- architecture contradiction requires a strategic decision

==================================================
H1 — COMPLETE CANONICAL BYTECODE IDENTITY V2
==================================================

Highest priority.

The audit found CANONICAL_ID_V1 incomplete.

It can omit behavior-relevant information including:

- constants
- integer operands
- branch targets
- switch keys/targets
- exception-handler ranges
- exception-handler targets
- invocation descriptors
- some local indices
- type operands
- field owners
- method/field access flags
- synchronization
- volatility

A secure hash over incomplete input is still incomplete identity.

Implement:

CANONICAL_ID_V2

==================================================
H1.1 CLASS REPRESENTATION
==================================================

Include all behavior/reflection-relevant data.

CLASS:

- classfile version
- access flags
- class name
- superclass
- interfaces
- signatures where observable
- inner/nest metadata where relevant
- annotations
- bootstrap methods
- constants

FIELDS:

- owner
- name
- descriptor
- signature
- access flags
- constant value
- annotations

METHODS:

- name
- descriptor
- signature
- access flags
- declared exceptions
- annotations
- relevant parameter metadata

CODE:

- every opcode
- every operand
- local index
- constants by symbolic value
- field owner/name/descriptor
- method owner/name/descriptor/interface bit
- invokedynamic bootstrap method + args
- type operands
- MULTIANEWARRAY dimensions
- IINC operands
- branch targets
- switch keys
- switch targets
- try/catch start/end/handler/type
- any behavior-relevant frame/control metadata

Canonicalize ONLY:

- constant-pool numeric indices
- raw byte offsets
- label object identity
- member emission order when order is proven semantically irrelevant

Create deterministic stable block/label IDs.

Version the schema explicitly:

CANONICAL_ID_V2

Do not silently reuse V1 naming.

==================================================
H1.2 MUTATION NEGATIVE CONTROLS
==================================================

Create controls proving identity changes/rejects on:

- BIPUSH change
- SIPUSH change
- LDC constant change
- branch target change
- TABLESWITCH key/target change
- LOOKUPSWITCH target change
- try range change
- handler target change
- catch type change
- invocation descriptor change
- invocation owner change
- field owner change
- field descriptor change
- local index change
- type operand change
- method access flag change
- synchronized flag change
- field volatile flag change
- declared exception change
- superclass change
- interface change
- reflection-visible member change

Also prove identity remains stable across known irrelevant nondeterminism:

- constant-pool reordering
- member emission order
- label object allocation/order when CFG is identical

==================================================
H1.3 INDEPENDENT CLASSFILE CROSS-CHECK
==================================================

Do not trust only the ASM representation used by the injector.

Prototype one independent classfile parser:

preferred candidates:

ristretto_classfile
cafebabe

Tooling-only dependency is acceptable.

Cross-check:

Clean Forge classes
Revelation transformed classes
all V2 mutation controls

Any parser disagreement:

record
understand
do not normalize away blindly.

==================================================
H2 — MAKE PROFILE QUALIFICATION TRULY GENERIC
==================================================

Fix hardcoding.

NO hardcoded:

- Forge 2846
- Revelation runtime path
- local JDK path
- Revelation helper path
- machine-specific directories

Everything comes from:

CLI
environment
manifest
profile

Fresh observations are mandatory.

Expected profile data cannot substitute for missing current observations.

Missing observation:

INCOMPLETE

not PASS.

Every subprocess:

- validate return code
- capture stderr
- validate output count/schema
- reject malformed output

Fix RAW mode completely.

Create one generic:

QualificationEngine

consuming:

runtime manifest
profile
fresh transformed observations
writer matrix
lifecycle evidence
negative controls

Clean Forge and Revelation must use it.

==================================================
H2.1 EVIDENCE DEPENDENCY GRAPH / CERTIFICATES
==================================================

Implement machine-readable qualification certificates.

Evidence nodes may include:

artifact hashes
config hashes
runtime identity
loader/transformer chain
coremod inventory
complete transformed identity
hook placement
writer closure
lifecycle closure
storage/extractor family
registry epoch
negative controls
live shadow
performance qualification
explicit authority approval

Status levels:

OBSERVED
OFFLINE_QUALIFIED
LIVE_QUALIFIED
SHADOW_VALIDATED
PERFORMANCE_QUALIFIED
AUTHORITY_AUTHORIZED

Invalidation must propagate transitively.

No one boolean like:

profile_supported = true

may replace this maturity model.

==================================================
H2.2 EXTRACTOR SCOPE CORRECTION
==================================================

Audit:

LiveForgeCaptureSource
and related extraction/eligibility logic.

Reusable extractor code must NOT claim Clean Forge semantics internally.

Provider/dimension/storage support belongs in profile/capability data.

Explicitly qualify:

provider exact identity/family
dimension
storage family
registry epoch
state width
generator family

If the profile supports only:

dimension 0
WorldProviderSurface

enforce exactly that.

No accidental broad acceptance through superclass ancestry.

==================================================
H3 — FIX TELEMETRY
==================================================

Correct JNI/native transfer metrics.

Operation IDs/constants must not be recorded as transferred bytes.

Record separately:

operation_id
call_count
input_bytes
output_bytes
copied_bytes
borrowed_bytes
retained_bytes
allocation_bytes where known
elapsed_ns
fallback_reason

Version the metrics schema.

Mark historical transfer-volume claims invalid where based on the old
misinterpreted values.

Do not rewrite historical raw data.

==================================================
H3.1 PERFORMANCE CLAIM AUDIT
==================================================

Scan project reports/docs for active performance claims.

Classify:

MEASURED
DERIVED_FROM_MEASURED
PROJECTED
SYNTHETIC
ASSUMED
INVALIDATED

Explicitly review:

- façade JNI latency
- allocation-event counts vs allocation bytes
- "factory" workload naming
- missing M3W5 evidence
- compression-size summaries
- zero-ULP claims inferred only from generated block IDs

Narrow wording where evidence is weaker.

==================================================
H4 — FINE-GRAINED NATIVE CAPABILITY MODEL
==================================================

Do not model support as:

modpack = Rust yes/no

Capability identity should include:

operation
ownership mode
profile
session/world/incarnation
dimension/provider/storage family
registry/version epoch
receiver restrictions
state restrictions
writer evidence
lifecycle evidence
dependency capabilities
revocation conditions

Example valid mixed state:

framing                  AUTHORITY
compression              AUTHORITY
chunk-storage-family-A   SHADOW_VALIDATED
chunk-storage-family-B   JAVA
TE chunks                JAVA
dimension 0              eligible
Twilight Forest          JAVA
lighting                 JAVA or qualified separately

Ownership dependencies must prevent two mutable authorities.

==================================================
H5 — VERSION-NEUTRAL CORE ARCHITECTURE
==================================================

Do NOT broadly implement modern Minecraft yet.

But remove legacy assumptions from universal core interfaces.

Move behind adapters:

- sixteen vertical sections
- fixed height 256
- u16 section mask
- 2D u8 biome array
- legacy numeric state IDs
- wire BlockPos encoding
- process-global palette width

Introduce version-neutral concepts such as:

SectionCoord(i32)
WorldBounds
SemanticStateKey
DenseRuntimeStateId
WireStateId
RegistryEpoch
BiomeKey
VersionCapabilities

16×16×16 sections may remain a useful primitive.

Architectural skeleton:

rustcraft-core/
versions/
  mc_1_12_x/
    mc_1_12_1/
    mc_1_12_2/
protocol/
world_format/
loader/
mod_runtime/

Avoid dynamic abstraction cost in per-block inner loops.

Select specialized implementations per world/session/job where possible.

==================================================
H5.1 1.12.X FAMILY
==================================================

Add version metadata/adapters for:

Minecraft 1.12.1
protocol 338
DataVersion 1241

Minecraft 1.12.2
protocol 340
DataVersion 1343

Represent known protocol deltas, including keepalive payload differences.

This is architecture/fixtures only.

Do NOT claim complete 1.12.1 gameplay support.

==================================================
H6 — RETAINED NATIVE OWNERSHIP MODEL
==================================================

This is the major engine migration foundation.

Goal:

replace:

Java state
→ extract/copy
→ Rust kernel
→ result/copy
→ Java owner

with:

Rust-owned admitted state
→ Rust mutation
→ Rust derived caches
→ Rust packets
→ Rust persistence

while Java preserves compatibility identities where needed.

Define ownership states and transitions:

JAVA_OWNED
QUIESCING
TRANSFERRING
RUST_OWNED
MATERIALIZING_JAVA
REVOKED / RETIRED as appropriate

Define:

- single-writer invariant
- quiescence
- atomic handoff
- revocation
- generation
- lifecycle identity
- Java materialization
- failure rollback
- fallback
- stale-view rejection
- cache invalidation
- destruction
- capability dependencies

No production authority.

==================================================
H6.1 GENERATIONAL HANDLES
==================================================

Prototype stable handles using SlotMap or equivalent.

Identity must include enough context such as:

session
world
incarnation
generation
slot generation

Bare pointer or integer slot is not sufficient external identity.

==================================================
H6.2 NATIVECHUNK VNEXT
==================================================

Design version-neutral NativeChunk / NativeSection vNext.

Do not mirror Java fields.

Prototype representations:

UNIFORM
LOCAL_PALETTE
DENSE/HOT

Use hysteresis so sections do not thrash representation.

Separate:

semantic state key
dense runtime state ID
wire ID
persistence ID

Do NOT impose u16 as universal native state width.

Revelation already proves max state IDs can exceed u16.

==================================================
H6.3 IMMUTABLE GENERATION-TAGGED VIEWS
==================================================

Add views for:

packet generation
lighting workers
persistence
queries
worldgen dependencies

Every view carries:

lifecycle identity
ownership generation
registry epoch

Stale worker results cannot commit.

==================================================
H7 — LOOM MODEL CHECKING
==================================================

Add Loom as test-only infrastructure.

Model:

ownership publication
READY
unload/reload
slot reuse
revocation
async IO adoption
queue publication
cancellation
state transfer
stale result rejection

Do not model giant Minecraft state.

Create minimal state machines that exercise interleavings.

Live tests remain required.

==================================================
H8 — SECTION LAYOUT PROTOTYPES
==================================================

Benchmark:

current dense
uniform
small local palette
larger palette
dense hot-section representation

Measure:

read throughput
write throughput
palette transitions
packing
memory
packet generation
mutation invalidation

Prototype hybrid palette lookup:

small palette → linear
large palette → lookup structure

Preserve deterministic palette ordering.

==================================================
H8.1 GENERATION-KEYED PACKET CACHE
==================================================

Prototype immutable packet-body caching.

Cache keys should include required:

block/state generation
light generation
biome generation
lifecycle generation
registry epoch
protocol/version
dimension rules
recipient-dependent behavior

Measure:

one player
many players
high view distance
low mutation
high mutation

Measure retained memory and invalidation cost.

Study Valence/Hyperion ideas.

Do not copy incompatible code/licensing.

==================================================
H9 — NATIVE NBT / REGION PIPELINE
==================================================

Prototype full pipeline:

region read
→ validate
→ decompress
→ bounded NBT parse
→ native extraction
→ preserve opaque mod data
→ modify
→ version-correct NBT encode
→ compress
→ ordered region write

MCA/NBT stays the compatibility format.

Preserve:

unknown tags
array/list types
modified UTF-8 behavior
floating payloads
nested opaque data
DataVersion
NEID/mod fields

Prototype and benchmark:

fastnbt
simdnbt
existing/custom implementation

Measure COMPLETE pipeline.

Do not choose parser from parser-only benchmark.

Do not replace MCA with a database.

Sidecar indexes may be researched separately.

==================================================
H10 — WORLDGEN PIPELINE FUSION
==================================================

Prototype retained-native worldgen avoiding repeated Java/Rust materialization.

Fuse where semantics allow:

density
terrain
other pure stages

Keep Forge/mod observable callbacks explicit.

Benchmark:

Java full operation
current separated Rust kernels
fused Rust pipeline

Report complete chunk-generation effect.

==================================================
H11 — ENTITY STORAGE EXPERIMENT
==================================================

Prototype:

A. custom Rust SoA/hot-cold storage
B. hecs-based storage

Hot fields:

position
velocity
bounds
lifecycle
behavior type

Cold:

capabilities
NBT
Java references
mod extension state

Benchmark:

iteration
lookup
spawn/despawn
component churn
spatial queries
collision broad phase
memory
stable handles

Behavior order must not accidentally depend on ECS/archetype ordering.

Blocks are NOT ECS entities.

==================================================
H12 — SEMANTIC SCHEDULER
==================================================

Build a RustCraft-specific semantic scheduler.

Use standard worker primitives but custom semantic ordering.

Architecture:

bounded command intake
stable logical sequence numbers
qualified pure jobs
owned/immutable inputs
generation-tagged results
ordered effect commit
CPU/memory/time budgets

Early parallel domains:

compression
disk read/decompression
pure worldgen
immutable queries
packet preparation
cache generation

Do NOT parallelize arbitrary:

redstone
entity interactions
block entities
scheduled/random ticks
mod callbacks

without read/write dependency proofs.

No optimistic replay of arbitrary Java callbacks.

==================================================
H13 — LIGHTING / COLLISION PROTOTYPES
==================================================

Research:

Starlight
Folia ownership ideas
other current lighting/spatial designs

Lighting prototype:

packed queues
dirty frontiers
reduced neighbor reads
cache-local traversal

Collision:

native spatial broad phase
cached immutable shapes
exact double precision where required
dynamic mod callbacks preserved

No production integration without parity evidence.

==================================================
H14 — PACKET / BUFFER PIPELINE
==================================================

Keep MCK6 production semantics unchanged.

Prototype:

retained state
→ immutable packet body
→ shared body across equivalent recipients
→ bounded compression
→ connection order restoration
→ encryption
→ vectored send

Evaluate `bytes` or equivalent.

Measure every copy.

Do not call it zero-copy without measuring complete path.

==================================================
H15 — OBSERVABILITY PLATFORM
==================================================

Implement low-overhead permanent metrics.

Need:

MSPT p50/p95/p99
tick phase timings
per-mod inclusive/exclusive callback time
chunk pipeline timings
JNI count/bytes/time
network serialize/compress/send timings
Java heap/GC
native allocations
RSS
snapshot retention
lock wait/hold
queue depth/bytes
fallback/capability reason counts

Evaluate/use where justified:

metrics
HdrHistogram
tracing

Optional diagnostics:

Tracy
Samply / ETW
async-profiler where supported
JFR/JVM tooling appropriate to Java 8 runtime

Do not trace every block access.

==================================================
H16 — REPRESENTATIVE BENCHMARK MATRIX
==================================================

Build reproducible scenarios for:

Clean Forge
Revelation
SevTech

Workloads:

idle
join/reconnect
exploration
high view distance
pregeneration
entity-heavy
TE-heavy
redstone-heavy
automation/factory-heavy
multiplayer
many dimensions
cold reload
warm reload
network saturation

The old "factory" workload is NOT sufficient.

Create real mod-machine workloads.

Pin:

binary
runtime
mods
config
world
seed
actions
memory settings
CPU assumptions

Alternate run order.

Measure:

MSPT p50/p95/p99
CPU
RSS
Java heap
GC
native memory
allocations
throughput
queueing
native eligibility
semantic mismatch

TPS alone is insufficient.

==================================================
H17 — ALLOCATOR EXPERIMENTS
==================================================

Only after instrumentation is trustworthy.

Benchmark RustCraft workloads with:

system allocator
mimalloc
jemalloc
optional rpmalloc

Measure:

CPU
RSS
fragmentation
tail latency
cross-thread free behavior

Do not integrate based on allocator microbenchmarks.

Use bump allocation only for bounded scratch phases.

==================================================
H18 — EXTERNAL REPOSITORY / ALGORITHM EXPERIMENTS
==================================================

Use the audit ecosystem list.

Priority:

Valence
Pumpkin
Hyperion
Folia
Starlight
fastnbt
simdnbt
hecs
SlotMap
Crossbeam
arc-swap
Loom
pulp
wide
bytes
Compio
libdeflate
zlib-ng
ISA-L

For each adopted/prototyped item record:

repository
version/commit
license
dependency vs algorithm inspiration
exact subsystem
benchmark
semantic differences
integration complexity
decision:

ADOPT
PROTOTYPE
STUDY
BORROW_ALGORITHM
AVOID

Do not dependency-spam.

==================================================
H19 — JVM / JAVA MOD RUNTIME RESEARCH
==================================================

RESEARCH_ONLY.

Do NOT put custom JVM work on the engine critical path.

Study/prototype:

Ristretto classfile
Ristretto VM
rusty-jvm
rjvm
Espresso
Crema / Native Image runtime loading
OpenJ9
Cranelift
MMTk

Build a small semantic experiment only.

==================================================
H19.1 GUARDED MINECRAFT INTRINSIC
==================================================

Prototype:

mod bytecode calls a Minecraft method

runtime checks:

defining loader
final transformed method identity
descriptor
receiver/subclass condition
profile certificate
ownership capability

then either:

qualified → Rust intrinsic

or:

unqualified → normal Java behavior

Start with a coarse PURE READ-ONLY method.

No tiny JNI theater.

Measure real call-path cost.

==================================================
H19.2 JAVA OBJECT IDENTITY / NATIVE HANDLE PROTOTYPE
==================================================

Prototype canonical Java-facing identity backed by Rust handles.

Test:

==
System.identityHashCode
hashCode override
monitor identity
weak references
lifecycle invalidation
unload/reload
replacement
subclass fields
native handle generation

No permanent strong-wrapper leak.

Do not claim arbitrary subclass compatibility from trivial tests.

==================================================
H19.3 CUSTOM JVM GO / NO-GO
==================================================

Custom Java 8 runtime advances only if research demonstrates:

- adversarial semantic correctness
- loader/transformation compatibility
- reflection
- object/reference lifecycle
- required class library/native services
- strategic measurable advantage over embedded mature JVM

An explicit:

DO NOT BUILD CUSTOM JVM

result is valid.

==================================================
H20 — COMPATIBILITY DOMAIN TRANSFER PROTOTYPE
==================================================

Prototype one small state family that can move:

JAVA_OWNED
→ QUIESCING
→ TRANSFERRING
→ RUST_OWNED

and potentially:

RUST_OWNED
→ QUIESCING
→ MATERIALIZING_JAVA
→ JAVA_OWNED

Prove:

one writer
identity preservation
callbacks ordered/blocked safely
generation advancement
stale-view rejection
cache invalidation

Do not start with whole-world transfer.

==================================================
H21 — EVENT / CAPABILITY FAST PATHS
==================================================

Prototype:

zero-listener fast path
subscription indexing
capability lookup caching
dynamic registration invalidation

Investigate profile-guided callback specialization afterward.

Do NOT reorder callbacks.

Do NOT skip observable event creation if construction side effects exist.

Measure real Revelation/SevTech traffic.

==================================================
H22 — RETENTION / LEAK AUDIT
==================================================

Audit and bound:

closed frame handlers
ticket histories
binding histories
capture identities
diagnostic snapshots
profile observations
wrapper caches
retained packet bodies

Historical aggregate counters may persist.

Closed runtime objects may not accumulate forever.

Add long-duration lifecycle tests.

==================================================
H23 — REQUALIFY CLEAN FORGE AND REVELATION UNDER V2
==================================================

After the hardened qualification foundation is stable:

regenerate from fresh observations:

Clean Forge 2860

and:

Revelation 3.4.0 / Forge 2846

using:

CANONICAL_ID_V2
generic QualificationEngine
fresh evidence
evidence certificates
correct extractor scopes
fine-grained capability model

DO NOT trust previous CANONICAL_ID_V1 expected hashes.

Expected classifications:

CLEAN_FORGE_PROFILE_REQUALIFIED_V2

REVELATION_PROFILE_REQUALIFIED_V2

Run existing controls plus all new V2 semantic mutation controls.

Then run:

Clean Forge:
- live-profile
- live-transformer
- live-capture
- foundation tests

Revelation:
- generic profile verify
- negative controls
- anchor verify
- insertion verify
- post-hook verify
- default-OFF
- wrong-profile

No concurrent profile edits while validation runs.

==================================================
H24 — RESUME PAUSED REVELATION SHADOW
==================================================

Only after:

CLEAN_FORGE_PROFILE_REQUALIFIED_V2
AND
REVELATION_PROFILE_REQUALIFIED_V2

may Revelation live SHADOW resume.

At that point:

1. Review READ-ONLY the paused ZCode:
   - live_client_session.py diff
   - run_rev_shadow.py

2. Port useful IDEAS into the hardened branch.

3. Do NOT blindly copy the files wholesale.

Preserve useful concepts:

- FML|HS negotiation
- control / Phase A / Phase B
- polling
- stop conditions
- eligibility/rejection accounting
- persisted-world strategy

Correct known issues first:

- prove FML join separately
- include required ModList entries if network checks demand them
- default-OFF control requires genuine client join
- use cleanly stopped persisted world
- wait for every JVM to exit before next phase
- prohibit overlapping multi-GB servers
- bind campaign to CANONICAL_ID_V2 certificate

Prefer architecture:

GenericLiveShadowRunner
+
runtime manifest
+
profile/capability certificate
+
client adapter

NOT:

run_clean_shadow.py
run_rev_shadow.py
run_sevtech_shadow.py

Run Revelation shadow FROM SCRATCH under V2.

Paused V1 shadow attempts remain debugging history only.

Do not claim continuity of live evidence.

==================================================
H25 — DO NOT ENABLE PRODUCTION AUTHORITY
==================================================

Throughout this entire program:

M4NativeStatePayload.tryEncode remains fail-closed

productionAuthorityEligible() remains false

MCK6 production authority remains unchanged

No native world/chunk ownership flag becomes production-active

No Rust chunk packet reaches production wire

Retained-state experiments remain non-authoritative until a separate explicit
authority review.

==================================================
H26 — PERFORMANCE ACCEPTANCE RULE
==================================================

For every performance integration report:

kernel performance
complete operation performance
whole-path performance where applicable
CPU
allocation
RSS
tail latency
semantic parity

Reject claims such as:

"SIMD parser 3x → chunk load 3x"

or:

"terrain inner loop 1.5x → server 1.5x"

Complete boundaries matter.

==================================================
H27 — LICENSING / PROVENANCE
==================================================

Maintain a machine-readable external-reuse ledger.

Record:

project
version/commit
license
dependency/reuse/inspiration
notice obligations
provenance

GPL/LGPL project code:

do NOT copy into incompatible modules.

Use as research / independent reimplementation unless separately approved.

Do not mechanically translate decompiled Mojang code.

Flag uncertain cases for legal review.

==================================================
H28 — TEMPORARY SCAFFOLD EXIT PLAN
==================================================

Maintain:

mechanism
classification
exit condition

Include at minimum:

diagnostic live capture
Java/native packet comparison
profile-specific probes
duplicate Java/native state
JNI state getters
global writer gate
persistent identity history
closed handler retention
Java engine oracle paths

Classify:

TEMPORARY_AND_EXPECTED
GENERALIZE
REMOVE_WHEN_X
DANGEROUS_IF_PERMANENT

Do not remove scaffolding before replacement proves correctness.

==================================================
H29 — COMMIT POLICY
==================================================

Commit each independently green milestone.

Suggested examples:

feat(qualification): add complete canonical bytecode identity v2

feat(qualification): add generic evidence certificates

fix(metrics): record actual jni transfer bytes

feat(core): introduce version-neutral state identities

feat(native-state): add retained ownership state machine

test(concurrency): add loom ownership models

perf(section): benchmark adaptive section layouts

feat(storage): prototype native nbt region pipeline

perf(worldgen): prototype fused native worldgen

perf(entity): benchmark soa versus hecs

feat(scheduler): prototype ordered semantic scheduler

perf(packet): prototype generation-keyed packet caching

feat(observability): add cross-runtime telemetry

research(jvm): add guarded minecraft intrinsic prototype

No push.

Do not squash milestones.

==================================================
H30 — CONTINUOUS REGRESSION SAFETY
==================================================

After any milestone touching:

qualification
writer/lifecycle rules
state identity
bridge
packets
version semantics
retained state

run relevant Clean Forge and Revelation checks immediately.

Do not accumulate hidden regressions.

==================================================
FINAL PROGRAM DELIVERABLE
==================================================

Return:

# RUSTCRAFT ARCHITECTURE HARDENING + FULL-RUST MIGRATION IMPLEMENTATION REPORT

A. worktree isolation proof
B. baseline / branch / commit lineage
C. audit recommendations implemented
D. recommendations rejected or modified
E. CANONICAL_ID_V2 design
F. V2 semantic negative controls
G. independent parser cross-check
H. generic qualification architecture
I. evidence-certificate architecture
J. extractor/profile scope corrections
K. telemetry corrections
L. active evidence claim audit
M. fine-grained capability model
N. version-neutral architecture
O. 1.12.x adapter skeleton
P. retained native ownership model
Q. handle/lifecycle model
R. NativeChunk vNext design
S. immutable generation-tagged views
T. Loom model results
U. section layout benchmark
V. packet-cache benchmark
W. native NBT/region pipeline
X. worldgen fusion results
Y. entity SoA vs hecs result
Z. semantic scheduler result

AA. lighting/collision prototype results
AB. packet/buffer pipeline result
AC. observability implementation
AD. representative benchmark matrix
AE. allocator experiments
AF. external project prototype results
AG. dependency/license ledger
AH. JVM/runtime research
AI. guarded intrinsic prototype
AJ. Java identity/native-handle prototype
AK. custom JVM GO/NO-GO
AL. compatibility-domain transfer prototype
AM. event/capability fast paths
AN. retention/leak audit
AO. Clean Forge V2 requalification
AP. Revelation V2 requalification
AQ. Revelation V2 shadow result if H24 becomes eligible
AR. performance evidence summary
AS. Java-removal progress
AT. migration scaffolding remaining
AU. complete commit list
AV. git/diff state
AW. exact recommended next production/migration stage

==================================================
PRIMARY SUCCESS CONDITION
==================================================

Success does NOT mean:

"every idea was shoved into production."

Success means:

- every NOW recommendation implemented and validated
- every SOON recommendation has a serious implementation/prototype
- every LATER recommendation has bounded evidence and an integration gate
- every RESEARCH_ONLY recommendation has meaningful go/no-go evidence
- qualification is materially stronger than c4b868d
- Clean Forge is requalified under V2
- Revelation is requalified under V2
- native-core ownership architecture is substantially advanced
- version boundaries are real
- performance measurement is trustworthy
- RustCraft is measurably closer to being a high-performance Rust-owned engine
- Java remains only where compatibility still requires it
- no native production authority was accidentally enabled

==================================================
ARCHITECTURE ESCAPE HATCH
==================================================

If an audit recommendation proves wrong:

record:

AUDIT_RECOMMENDATION_REJECTED

with:

recommendation
evidence
alternative
impact

Do NOT force a recommendation because the audit said it.

If speed requires semantic incompatibility:

reject the optimization.

If production authority would need enabling:

STOP before enabling it.

If custom JVM work starts becoming required for ordinary engine migration:

STOP and keep it research-only.

Otherwise:

continue milestone by milestone until the architecture-hardening program is
complete.