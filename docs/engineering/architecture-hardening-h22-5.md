# H22.5 — Remaining Ultra audit experiments

This user-authorized amendment is part of the full implementation goal, not an
optional appendix. It adds bounded experiments without making them production
critical paths or authorizing dependency installation indiscriminately.

## A. Differential event replay

Build a reusable deterministic migration oracle comparing REFERENCE_JAVA with
candidate Rust/native behavior from the same initial world/state, ordered
player/server commands, controllable RNG seeds and callback/event order.
Compare at each semantic boundary: mutations, entity state, scheduled ticks,
packets, NBT/save state and callbacks. Stop and preserve the first divergence;
final world equality alone is insufficient.

## B. State-diff execution

Use immutable qualified snapshots only for bounded side-effect-free behavior.
Produce explicit effects/mutation diffs and validate them before ordered commit.
Evaluate pure block behavior, worldgen, simulations, tests and future scheduler
stages. Return ADOPT, LIMITED_USE or REJECT. Exclude arbitrary Forge callbacks,
filesystem/network effects, RNG-visible behavior and external side effects.

## C. GPU/offline acceleration

Research and prototype large pregeneration batches, selected worldgen kernels,
bulk lighting or offline indexes only where setup/transfers amortize. Start with
integer/bounded deterministic kernels. Stop on dominant transfer cost, harmful
live latency, unproven floating-point parity or complexity exceeding measured CPU
benefit. GPU research is not a production critical-path requirement.

## D. Secondary ECS comparators

Beyond custom SoA versus hecs, run small useful bevy_ecs and Shipyard comparisons:
iteration, mutation, stable identity, ordering control, memory, scheduler coupling
and mod-extension storage. Maturity alone never authorizes production integration.

## E. JNI/classfile tooling

Evaluate jni-rs, jbindgen, ristretto_classfile and cafebabe for typed lifecycle
safety, signatures, independent transformed-class parsing and qualification
cross-checking. Do not replace proven bridges wholesale without demonstrated
performance or maintenance value.

## F. Concurrency/buffer mechanisms

Evaluate Rayon, parking_lot, slab and bytemuck where justified. These are
mechanisms, never definitions of scheduler ordering, ownership, identity or wire
semantics.

## G. Storage/mmap/sidecars

Consider bounded memmap2, redb, Fjall and RocksDB experiments for offline scanning,
indexes, metadata and stable read snapshots. MCA/NBT compatibility remains; a
replacement needs a future explicit architecture decision.

## H. Hot mod methods

Use permanent observability to identify actual hot callbacks. Investigate guarded
intrinsics, specialization, selective JIT/AOT and native implementations only for
stable qualified methods. Bind final transformed identity, loader, receiver and
subclass assumptions, effects, exceptions, reflection and capability dependencies.
A matching method name never authorizes optimization.

## I. External decision ledger

Every serious audit candidate needs ADOPT, PROTOTYPE, STUDY, BORROW_ALGORITHM or
AVOID plus reason, license, performance value, migration value and integration
risk. Cover at least:

Valence; Pumpkin; Hyperion; FerrumC; Temper; Folia; Starlight; Azalea; Ristretto;
rusty-jvm; Espresso; Crema; OpenJ9; MMTk; Cranelift; hecs; bevy_ecs; Shipyard;
SlotMap; Crossbeam; arc-swap; Rayon; parking_lot; Loom; pulp; wide; bytemuck;
bytes; Compio; Monoio; libdeflate; zlib-ng; ISA-L; fastnbt; simdnbt; memmap2;
redb; Fjall; mimalloc; jemalloc; rpmalloc; bumpalo; tracing; metrics;
HdrHistogram; Tracy; Samply; async-profiler.

Also retain decisions for other serious candidates from the governing audit,
including RocksDB and JNI/classfile tooling. A STUDY or AVOID decision must be
reasoned and sourced. A PROTOTYPE entry is not proof that its experiment ran.
Do not install all candidates.
