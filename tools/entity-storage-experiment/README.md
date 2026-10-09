# Bounded entity storage comparison

Standalone H11/H22.5.D experiment: custom hot/cold SoA, hecs 0.11.1,
bevy_ecs 0.19.1 and Shipyard 0.11.5. No production dependency, entity engine,
blocks-as-entities, JNI bridge or scheduler integration is included.

Run with Python 3.12+ and the explicitly configured Rust tools:

```powershell
python -B run.py `
  --cargo C:\Users\Admin\.cargo\bin\cargo.exe `
  --rustc C:\Users\Admin\.cargo\bin\rustc.exe `
  --cache C:\Users\Admin\.cargo\registry\src\index.crates.io-1949cf8c6b5b557f `
  --crate-cache C:\Users\Admin\.cargo\registry\cache\index.crates.io-1949cf8c6b5b557f `
  --output D:\minecraftrust-astra-hardening\target\entity-storage-experiment
```

Default sizes are 1,000 and 10,000 entities, with five fresh processes for each
backend/size pair (40 samples). Backend order rotates between repetitions; no
benchmark subprocesses run concurrently. Each process is bounded to 120 seconds,
8 MiB stdout and 2 MiB stderr. Output stays under the isolated checkout's target;
the original-checkout and production-gate guard runs before and after. A failed
run preserves raw process logs and a scoped FAIL receipt.

The runner removes inherited Rust/Cargo/Clippy flags and wrappers, Java options,
and compiler/linker flag variables. It pins the resolved Rust tool binaries and
Cargo home, hashes existing Cargo configuration and verifies offline metadata
resolves dependencies to the inspected source cache. Source, tool, executable,
input and dependency hashes are rechecked at completion.

## Semantic contract

The external key is `(world, logical slot, u64 generation)`. A common registry
maps keys to private backend handles, rejects stale/cross-world keys and increments
generations on despawn. Generation exhaustion fails before deletion. SoA uses
dense component columns, sparse slot lookup and swap removal. ECS implementations
use equivalent typed components. Native iteration order never defines observable
event order: the synthetic contract orders events by external key.

Hot components are position, velocity, bounds, lifecycle and behavior type.
Cold payloads contain capability identifiers, opaque bytes representing NBT,
Java-reference tokens and variable mod-extension bytes. Tokens carry no actual
authority or Java-reference lifetime. Optional `Extension` component insertion
and removal exercise structural churn while cold payloads must survive exactly.
Only live (`lifecycle == 1`) entities move. Integer position addition wraps as
signed 64-bit arithmetic. Inclusive AABB comparisons use widened arithmetic.

`reference.py` is a plain dictionary/list implementation independent of the Rust
adapters. It checks all fields and events at every boundary in the 139-command
fixture. The fixture covers shuffled insertion, duplicate spawn, stale and
cross-world access, removal/reuse, extension churn, pause/resume, extreme integer
velocity, spatial queries and collision pairs. The reference uses brute-force
pairs for the fixture and independently checked grid buckets for larger benchmark
states; the Rust comparator uses an x-axis sweep index.

Lifecycle updates resolve the logical identity before validating the new value.
A stale identity with value 2 returns `STALE`; a current identity with value 2
returns `LIMIT`, without mutation. Root review found and preserved a divergence
in this combined-invalid-input case; both the Rust tests and trace now cover it.
Keys belong to one registry lifetime in this experiment. They do not contain
session/incarnation identity and must not be exported as production handles;
the H6 handle contract supplies that separate requirement.

Injected missing row, changed identity, changed value and reversed event order
must produce the exact first divergence. Cross-session/challenge replay and
missing/extra boundaries must be rejected. Rust tests also exercise private stale
backend handles separately from the external registry.

## Measurement interpretation

Each sample records initialization, spawn, hot iteration, checked logical lookup,
optional component churn, despawn/respawn, native hot-state extraction, logical
order restoration, spatial index build, queries, broadphase and a complete pure
tick pipeline. Ordering and spatial index costs are not hidden inside an
iteration-only result. The complete pipeline moves entities, extracts/sorts
logical state, calculates a state checksum, rebuilds the index and performs
broadphase plus four queries. It does not run arbitrary behavior callbacks.

`ns` is elapsed wall-clock nanoseconds for all listed operations in the phase.
Samples include global allocator counter overhead. The point-lookup paths use
checked per-component ECS access; these are concrete adapters, not assertions of
each crate's best possible point-lookup API. Bevy iteration query state is cached.
No built-in ECS scheduler is used and no parallel ECS feature is enabled.

Memory counters record allocation/reallocation calls, requested bytes, current
live requested bytes and peak extra live requested bytes through Rust's global
allocator. Reallocation counts the new request even if it occurs in place.
These values exclude allocator metadata, committed pages, stack memory and native
allocations outside that interface. They are **not RSS**. World-retained deltas
include a small live phase-record vector; post-drop residuals include that vector
and library-global metadata and do not prove a leak. All backend worlds use the
same synthetic cold payload sizes. A noncryptographic checksum provides benchmark
sanity checks only; qualification identities are outside this experiment.

## Dependency provenance

Only the three direct comparison libraries were selected. Their exact feature
sets are in Cargo.toml; default reflection, async executor and parallel features
were not enabled. Cargo.lock nevertheless retains 104 registry packages,
including optional and target-specific packages. The selected native dependency
tree is recorded separately in each receipt.

`third-party/inventory.json` binds all 104 packages to their registry checksums,
7,170 cached source files, VCS provenance and 208 retained notice/license files.
`unicode-ident`'s Unicode-3.0 AND clause and standalone Zlib licenses remain intact.
`r-efi` includes its full MIT option and copyright notice in AUTHORS, which is
retained with README; its original triple-license declaration is preserved.
Shipyard's proc-macro archive omits its license files, so those copies come from
the exact archived VCS commit. No mutable branch is used as license provenance.
`collect_licenses.py` collects this inventory; ordinary validation is offline.

See the [research report](../../docs/research/entity-storage-comparison.md) for
measured results, decisions and remaining integration gates.
