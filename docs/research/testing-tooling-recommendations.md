# Testing tooling recommendations

Research date: **2026-09-24**. These are recommendations based on this checkout and upstream primary sources. No dependency was installed or qualified against RustCraft during this research. Versions below are **candidate pins**, not approved installations or claims about the newest release.

## Recommendation

Prioritize a reproducible runner around the existing Java oracles, with cached compilation and fixture replay. Then add Proptest and a focused mutation audit, a small Java 8 test framework, and an independent protocol decoder. These should remain development dependencies. The expected efficiency comes from avoiding repeated compilation/server startup and reducing failures to useful reproducers; no speedup has been measured.

RustCraft already has JNI safety tests, deterministic fixtures, differential fuzzing, Forge/block-storage/protocol oracles, Netty EmbeddedChannel regressions, Java 8 launch wrappers, JFR scripts, and BenchAgent measurements. Reuse `tools/chunk-packet-oracle`, `tools/compression-interop`, `tools/protocol-oracle`, `tools/java8`, and the reference-independence audit. A replacement Minecraft server cannot establish the behavior of the actual Minecraft 1.12.2/Forge target.

## 1. Reproducible runner and immutable fixtures: strongest immediate benefit

Consolidate artifact discovery and classpaths into one runner instead of adding more hardcoded Java paths. Separate four lanes: public structural/JNI tests, deterministic fixture replay, exact-runtime differential generation, and live Forge/modpack campaigns. Missing external artifacts must produce `NOT_RUN / MISSING_EXTERNAL_ARTIFACT`, never a passing result.

Cache compiled harnesses by source content, compiler/runtime identity, ordered classpath hashes, mappings, JNI library hash, and launch options. Batch compatible fixtures into a JVM, but reset native handles/buffers and isolate tests with shared registries or mutable static state. Restart the Windows JVM when changing its loaded native DLL. Keep correctness runs separate from benchmark warmup.

A fixture must bind the following to **one serialization event**:

- Schema version; Java/Rust/bridge versions; Minecraft, Forge, mod/coremod and configuration hashes; mappings; transformed writer/class origin.
- Requested and Java-emitted masks, payload bytes/count, full-chunk and skylight flags, registry identity, owned section/palette/block/light/biome data.
- Capture point and ownership contract, world/dimension context, seed and operation sequence, and integrity hashes.

Capture under demonstrated thread ownership or synchronization. Copy mutable Java arrays into an immutable snapshot and establish that the reference writer serialized that same state. A timestamp, same-tick label, or payload hash alone does not prove coherence. Keep packet wrappers and chunk payloads distinct.

The strict decoder must consume exactly the returned byte count, observe exactly `popcount(emitted_mask)` complete sections, validate the permitted tail, and reject truncation/trailing data. Its mask selection, byte traversal, and expected-state model must not reuse encoder helpers. Shared selection logic can let the same bug pass both sides of a comparison. Record relationships between secondary oracles rather than count shared libraries as independent votes.

## 2. Proptest and cargo-mutants: strengthen the Rust contract

[Proptest](https://github.com/proptest-rs/proptest) is MIT OR Apache-2.0; the [examined manifest](https://github.com/proptest-rs/proptest/blob/a6f033cf83adfd55557b86e6065e6f4df054ec70/proptest/Cargo.toml) declares version 1.11.0 and Rust 1.86 minimum. Evaluate it as a dev-dependency after the dependency-free deterministic Issue #1 regressions.

Generate selected/present masks, sparse high bits, capacity boundaries, skylight/full-chunk modes, palette transitions, and fill/clear/refresh/encode/retry sequences. Compare against a simple state model and independent byte walker. Persist seeds **and explicit minimized fixtures**: [seed persistence](https://proptest-rs.github.io/proptest/proptest/failure-persistence.html) alone can reproduce different inputs after a generator changes. Use bounded PR case counts and larger varied-seed campaigns separately.

[cargo-mutants](https://github.com/sourcefrog/cargo-mutants), MIT, has [Windows in its CI matrix](https://github.com/sourcefrog/cargo-mutants/blob/fe82f1832778a591ab74248010fb40e699defafe/.github/workflows/tests.yml). Run a bounded audit of packet encode/refresh functions in its disposable copy. Mutations to missing-section rejection, mask accumulation, selection predicates, and capacity handling should be detected. Review surviving mutants; equivalent or unbuildable mutations are not automatically defects.

**Include integration tests in both baseline and mutation execution.** The packet contract regressions live under `crates/native-chunk/tests`; a command restricted to `--lib` would omit them. Start with the complete `cargo test --locked -p native-chunk` scope, then add explicitly selected integration targets as needed. Do not expand to unrelated broken workspace targets just to inflate mutation coverage. Mutation testing adds confidence, not speed, so keep it focused.

## 3. JUnit 5 and jqwik: a small Java 8 test layer

Candidate pair: **Jupiter 5.14.2 / Platform and Console 1.14.2**, plus **jqwik 1.9.3** API/engine. JUnit 5's [official guide](https://docs.junit.org/5.14.2/_exports/junit-user-guide-5.14.2.html) requires Java 8; [JUnit 6](https://junit.org/) requires Java 17. Do not confuse Jupiter and Platform version numbers or resolve the latest major into the Forge JVM.

jqwik 1.9.3 [targets Java 8](https://github.com/jqwik-team/jqwik/blob/1.9.3/dependencies.gradle) and its [guide](https://jqwik.net/docs/1.9.3/user-guide.html) requires Platform 1.13.1 or later. The proposed combination still needs a Java 8 discovery/run smoke test. Both projects use EPL-2.0: [JUnit license](https://github.com/junit-team/junit-framework/blob/r5.14.2/LICENSE.md), [jqwik license](https://github.com/jqwik-team/jqwik/blob/1.9.3/LICENSE.md).

Wrap existing cases for per-case reports, parameterized fixtures, tags, seeds, and shrinking. Reset native resources between shrinking attempts. Keep framework jars outside the production coremod and Forge classpath; an isolated ConsoleLauncher harness avoids requiring a ForgeGradle migration. QuickTheories is a Java 8, Apache-2.0 [alternative](https://github.com/quicktheories/QuickTheories), but upstream's last push observed was October 2020. Prefer one property framework, not both.

## 4. Prismarine: qualify an independent decoder, not another encoder

[node-minecraft-protocol](https://github.com/PrismarineJS/node-minecraft-protocol) is BSD-3-Clause; [minecraft-data](https://github.com/PrismarineJS/minecraft-data) and [prismarine-chunk](https://github.com/PrismarineJS/prismarine-chunk) are MIT. Their [1.12.2 version data](https://github.com/PrismarineJS/minecraft-data/blob/8ffb321c74cffe779acf5c447d08c473c4c291d7/data/pc/1.12.2/version.json) identifies protocol 340. Use an isolated Node lockfile and explicit `1.12.2`, with a qualified Node version; the examined protocol package requires Node 22+.

The [packet schema](https://github.com/PrismarineJS/minecraft-data/blob/8ffb321c74cffe779acf5c447d08c473c4c291d7/data/pc/1.12.2/protocol.json) treats chunk data as opaque bytes, so packet parse success alone cannot detect missing sections. A strict section reader is required.

The examined legacy [ChunkColumn implementation](https://github.com/PrismarineJS/prismarine-chunk/blob/ce60c5fcd09c6198e28de011eec3f8811b96923d/src/pc/1.9/ChunkColumn.js) exposes a cached mask while `dump()` separately skips null/empty sections. Its loader merges mask state and does not establish final cursor exhaustion. Do not copy that encode contract. Create a fresh decoder per fixture, explicitly track consumed bytes, and qualify rejection with deliberately malformed cases. Vanilla registry data cannot substitute for Forge's modded registry/global palette width.

The integration seam is a small offline harness accepting payload, exact result mask/count, skylight/full flags, and registry metadata, then returning decoded sections and consumption/rejection details. This provides another interpretation of bytes; it does not establish packet-time snapshot coherence.

## 5. JMH and existing profiling: measure the right scope

[JMH 1.37](https://github.com/openjdk/jmh/blob/1.37/pom.xml) targets Java 8 and uses GPLv2 with the Classpath Exception. Use it in a benchmark-only harness for Java serialization, native encoding with materialized input, and complete capture/staging/JNI/output costs. Validate outside timed iterations, consume results, use warmup and independent JVM forks, and report allocations and distributions. Kernel timings cannot substitute for full-server throughput or tail latency.

Reuse existing JFR/GC logs and BenchAgent first. [VisualVM](https://visualvm.github.io/download.html), GPLv2 + Classpath Exception, lists Windows/OpenJDK 8 support and is an optional analysis UI. [async-profiler](https://github.com/async-profiler/async-profiler), Apache-2.0, supports Linux/macOS; do not promise native Windows profiles. [spark](https://github.com/lucko/spark), GPLv3 with an MIT API, similarly distinguishes its native backend from Java sampling and needs a verified legacy Forge build.

## 6. Conditional speed and later campaigns

[nextest](https://github.com/nextest-rs/nextest), MIT OR Apache-2.0, offers process isolation, scheduling, timeouts, and reports. Its [Windows guidance](https://nexte.st/docs/installation/windows/) warns about process startup costs. Time equal test sets before choosing it; tiny tests may become slower. Preserve required cargo commands, test inventories, and first failures; avoid retries masking flaky correctness tests.

[Mineflayer](https://github.com/PrismarineJS/mineflayer), MIT, is useful later for deterministic movement, chunk loading, block actions, and reconnect workloads against actual servers. Its examined manifest requires Node 22+. The [Forge handshake plugin](https://github.com/PrismarineJS/node-minecraft-protocol-forge) does not implement arbitrary Java mods or their registries/custom channels. Qualify each pack and retain a real modpack client where necessary.

[cargo-fuzz](https://github.com/rust-fuzz/cargo-fuzz), MIT OR Apache-2.0 with libFuzzer's NCSA component, belongs in a separate pinned Linux/nightly lane initially. Upstream Windows support descriptions conflict; native Windows operation was not verified. Save minimized failures for stable-toolchain replay.

[jcstress 0.16](https://github.com/openjdk/jcstress/blob/0.16/README.md), GPLv2 + Classpath Exception, should wait until snapshot publication and ownership protocols are specified. Its build requires newer JDKs, while most tests can subsequently run on Java 8. Probabilistic Java results cannot prove Rust/JNI safety.

## Runtime qualification and scope

Use the exact intended Minecraft/Forge artifacts and hashes as the semantic oracle. Later official Forge 1.12.x [build files](https://github.com/MinecraftForge/MinecraftForge/blob/1.12.x/build.gradle) use ForgeGradle 3.0.197 and Java 8; Minecraft version alone does not select historical FG2.3. Forge/ForgeGradle use LGPL-2.1 with included notices; Minecraft is proprietary. Keep mapped game artifacts out of public source distribution under [Minecraft's terms](https://www.minecraft.net/en-us/eula).

Adopt in small follow-ups: runner/fixtures, Rust properties and mutation audit, Java test layer, qualified secondary decoder, then benchmarks and live workloads. Issue #1 remains open. `M4NativeStatePayload.tryEncode` remains production fail-closed; none of these recommendations enables native packet authority or resolves the historical exact writer.
