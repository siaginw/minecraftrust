# Hermes Agent Operating Rules

## Prime Directive

Study first. Measure second. Change third.

## Rule 1 — Never Guess Minecraft Behavior

If behavior is uncertain:
1. inspect source
2. reproduce it
3. add a test
4. document it
5. only then implement

## Rule 2 — Performance Regressions Trigger Investigation, Not Blind Rollback

When a change causes a regression:

1. preserve benchmark output
2. profile the new version
3. determine root cause
4. classify the regression
5. propose one or more fixes
6. implement the best candidate
7. rerun correctness tests
8. rerun benchmarks
9. keep the change only if correctness remains acceptable and performance meets thresholds
10. if abandoned, record why

Possible regression classes:
- excessive JNI crossings
- copies
- allocations
- locking
- cache locality
- serialization
- scheduler contention
- false sharing
- Java object churn
- thread wakeups
- I/O behavior
- changed algorithmic complexity

## Rule 3 — No Fine-Grained JNI

Forbidden by default:
- JNI call per block read
- JNI call per ItemStack property
- JNI call per entity query result
- JNI call per TileEntity tick

Prefer:
- `ChunkSnapshot`
- `RegionSnapshot`
- `PacketBatch`
- `SaveBatch`
- `MutationBatch`
- direct buffers
- long-lived handles

Any exception requires:
- benchmark
- written justification
- architecture review note

## Rule 4 — Update Ownership Metadata

Whenever ownership changes, update:
- ownership manifest
- subsystem status
- architecture docs
- relevant tests
- migration notes

## Rule 5 — Every Optimization Needs a Baseline

Never claim faster without:
- before benchmark
- after benchmark
- same workload
- same machine/runtime configuration where possible

## Rule 6 — Correctness Before Benchmark Victory

A faster result that changes observable behavior is not a successful optimization.

## Rule 7 — Preserve Learning

Every meaningful discovery goes in `docs/learned/`.

Do not leave essential architectural knowledge only in chat or commit messages.

## Rule 8 — Prefer Reversible Migration Steps

Build seams that allow A/B testing between:
- Java path
- Rust path

until the Rust implementation is proven.

## Rule 9 — Do Not Create Permanent Legacy Around a Temporary Bridge

FFI is migration infrastructure.

If a Rust subsystem becomes authoritative, remove obsolete Java mirrors and adapters when safe.
