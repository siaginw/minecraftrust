# Contributing to RustCraft

RustCraft runs on evidence. Every contribution — code, research, or documentation — inherits that rule. If you enjoy any of these, you will find real work here: high-performance serialization, native chunk state, concurrency under a single-writer discipline, JVM interop, classfile/bytecode compatibility, wire protocols, differential testing, and measurement infrastructure.

## The ground rules

1. **Evidence over assumption.** A claim ("faster", "compatible", "equivalent") needs a receipt: a benchmark, a comparison campaign, a qualification run. If it cannot be measured, it is stated as unmeasured or not stated.
2. **Parity before optimization.** A Rust component that is fast but not behaviorally equivalent to Java is a bug, not a feature. Optimization happens after semantic equality is proven, never instead of it.
3. **No performance claim without a benchmark** — and component benchmarks are labeled component benchmarks, never implied to be whole-server results.
4. **No proprietary game artifacts.** Do not commit Minecraft/Forge jars, mod jars, or copyrighted game assets. The tooling hash-pins externally obtained artifacts and refuses substitutes; keep it that way.
5. **No giant generated artifacts.** Evidence receipts are welcome; build outputs are not. If a file can be regenerated from a committed script, regenerate it rather than committing it.
6. **Java is authoritative until a recorded authority review says otherwise.** Do not enable Rust output paths, weaken fail-closed gates, or touch authority predicates (`M4NativeStatePayload.tryEncode`, `CaptureContract.productionAuthorityEligible()`).

## Useful contribution areas

- **Rust optimization** — palette compression, chunk state layout, serialization, SIMD, allocation discipline in `crates/`
- **Protocol correctness** — Protocol 340 encoders/decoders against the real wire (`crates/protocol`, `crates/chunk-punk`, `crates/native-chunk`)
- **Forge compatibility research** — studies of the 1.12.2 surface: classloaders, coremods, Mixins, registries, events (`docs/compatibility/`)
- **Minecraft 1.12.2 internals** — boot flow, chunk lifecycle, tick behavior; documented in `docs/architecture/`
- **Benchmarking** — scoped, reproducible measurement with published methodology (`docs/foundation/07_BENCHMARKING_AND_REGRESSION.md`)
- **Test fixtures** — synthetic chunks, wire captures, qualification controls
- **Documentation** — clarity is a feature; the public docs are part of the product
- **Cross-platform / build tooling** — Windows is the primary research platform today; Linux/macOS support is real work

## Workflow

1. Open an issue describing the change and the evidence you intend to produce. For parity work, describe how equivalence will be shown (fixtures, differential replay, shadow comparison).
2. Fork / branch from `main`.
3. Keep commits focused; use explicit paths when staging (never `git add .` here).
4. Point your PR at the evidence: test names, campaign receipts, benchmark outputs.
5. For anything touching qualification semantics or authority gates, expect extra scrutiny — that is by design, not obstruction.

## Research environment

The Rust workspace (`cargo build --locked --workspace` / `cargo test --locked --workspace`) builds and tests without any proprietary artifacts. Qualification and campaign work additionally requires the externally obtained, hash-pinned set described in [PROJECT_STATUS § Research environment](docs/PROJECT_STATUS.md#research-environment). You cannot break the rules accidentally — the tooling checks pins and refuses substitutes — but you should know they exist.

## Reporting problems

- Behavioral/parity discrepancy, compatibility break, or crash: open an issue with reproduction steps and (if applicable) campaign receipts.
- Safety or security concern: see [SECURITY.md](SECURITY.md) — do not open a public issue for it.
