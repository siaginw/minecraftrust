# Reproducible bounded test lanes

Run from PowerShell with Rust/Cargo and Python 3.11+ on `PATH`, plus
`JAVA8_HOME` or `JAVA_HOME` selecting **JDK 8u504**. `-JavaHome` is an explicit
override. The runner verifies both `java -version` and `javac -version`.
It installs nothing and changes no repository settings or production gates.

```powershell
tools/run-rustcraft-tests.ps1 public
tools/run-rustcraft-tests.ps1 property
tools/run-rustcraft-tests.ps1 property -Stress
tools/run-rustcraft-tests.ps1 java-jni
tools/run-rustcraft-tests.ps1 fixture
tools/run-rustcraft-tests.ps1 decoder
tools/run-rustcraft-tests.ps1 forge
tools/run-rustcraft-tests.ps1 modpack
tools/run-rustcraft-tests.ps1 benchmark
tools/run-rustcraft-tests.ps1 public -Inventory
```

The PowerShell entry point invokes a Python standard-library implementation.
All logs, receipts, compiler output and harness output go below the already
ignored `target/rustcraft-tests/`. Cargo uses the repository's `target/`.
No build or evidence output is written into tracked source/evidence folders.
The runner downloads no Minecraft/Forge/modpack artifacts and installs no
research dependencies. Cargo may fetch existing locked dependencies on a cold
cache (`--locked` is not `--offline`). There is no server startup or live campaign.

| Lane | Inventory and boundaries |
| --- | --- |
| `public` | All workspace library tests; native-chunk unit/integration/property tests; protocol tests; release FFI build; explicit V2/structural/snapshot reruns; independent decoder regressions; all synthetic fixtures through the freshly built native CLI; runner regressions; five available evidence-checker regressions; four standalone Java decoder/capture/JNI mains. |
| `property` | `native-chunk --test property_contract`, `native-chunk --test owned_snapshot_properties` and `ffi --lib packet_encode_v2::properties`; at least one test must actually execute in each target. 64 generated cases per property normally; optional `-Stress` raises the budget to 2048. |
| `decoder` | The independent Python wire decoder, immutable fixture importer and schema/hash regressions. No native replay or Java/Forge parity claim. |
| `java-jni` | Release FFI build, standalone Java 8 V2 decoder, retained-handle JNI, owned capture model and owned-snapshot JNI tests. Each main uses a fresh JVM; no Minecraft/Forge classpath. |
| `fixture` | Explicitly **SYNTHETIC**: Rust structural/V2 regressions, independent decoder regressions and 16 immutable fixtures plus eight rejection scenarios through a freshly built native snapshot CLI. A passing lane covers only these synthetic inputs. Real Forge owned graphs use the separate `forge` lane. |
| `forge` | Pins the original clean 1.12.2 / Forge 2860 server artifacts, performs actual offline FML load/preinit/init, observes final JVM class definitions, runs the owned real-Chunk oracle, creates local `REAL_CLEAN_FORGE_ORACLE` fixtures, and independently compares Java/native semantics plus native CLI replay. Missing/wrong artifacts produce `INCOMPLETE`, never PASS. No server or live source capture starts. |
| `modpack` | **Artifact preflight only.** Missing manifest/files: `NOT_RUN / MISSING_EXTERNAL_ARTIFACT`. Present verified files: `NOT_RUN / NO_QUALIFIED_OFFLINE_REPLAY_HARNESS`. Hash presence never means compatibility passed; qualified replay is future work. |
| `benchmark` | Explicitly separate: preflights the Forge classpath then runs existing `ForgeBenchmarks`. No benchmarking occurs in correctness lanes. Process completion is not a speedup claim. |

`public -Inventory` prints the exact direct commands without executing them.
The nine Cargo commands are also listed in every public receipt and match the
direct validation inventory. Existing NBT binary targets reference missing
`src/bin/bench.rs` and `src/bin/oracle_cli.rs`; therefore the runner uses the
workspace **library** suite plus available integration targets rather than
claiming an all-target workspace pass. The historical live evidence registry
test, three checker tests requiring absent `machine/raw/M14R-cargo-test.txt`,
strict evidence audit, world-data tests and socket/live-server tests are
explicit exclusions. Missing historical evidence is neither repaired nor
relabeled. The selected checker regressions use the existing PyYAML
dependency; if unavailable they report `NOT_RUN`, never trigger installation.
The excluded `test_measured_requires_sha256` and `test_hash_mismatch_rejected`
reach `RAW_DATA_MISSING` before their intended assertions; the excluded
`test_valid_minimal_passes_schema` reads that absent file directly. This is an
artifact limitation, not a claim that those tests pass.

Every run records `results.json`, exact process argument arrays, logs, elapsed
times, source hashes before/after, explicit exclusions and cache decisions.
Source changes during a run invalidate its result. Exit **0** means every
inventoried required check passed, **1** means a failure, and **2** means
`INCOMPLETE` because a required step was `NOT_RUN`. Thus a missing-artifact
Forge/modpack lane is never a green result. The fixture lane can pass only its
explicit synthetic inventory; that status makes no claim about real Forge
events. Invoking the fixture importer without native replay returns INCOMPLETE.
Separate oracle row validation catches legacy Forge mains that print `FAIL`
but exit zero; it also rejects missing or duplicate assertion rows. These
preexisting oracles remain limited to their documented test semantics.

## Compilation cache

Only Java compilation is reused, never a PASS result. The cache key contains:

- Ordered Java source paths and SHA-256 content hashes.
- Canonical `javac`/`java` paths, executable hashes and full version output;
  `tools.jar` and `rt.jar` hashes bind the actual compiler/runtime.
- Ordered classpath paths and content hashes; directories include all relative
  file names and content, so order, replacement and rename changes invalidate.
- The selected JNI library hash for JNI harnesses.
- Compiler options, JVM launch options, main classes and argument arrays.
- Inherited `_JAVA_OPTIONS`, `JAVA_TOOL_OPTIONS`, `JDK_JAVA_OPTIONS` and
  `CLASSPATH`, including whether each variable is unset.

Cache entries are published only after successful compilation. Their generated
class files are rehashed before reuse; corrupt or incomplete entries rebuild
in a fresh directory. Every main always launches in a **fresh JVM**, including
cache hits and changed DLLs. The JNI test loads the exact selected release DLL.
Inputs are rehashed after compilation before publishing a cache entry and
before/after every JVM execution. A changed DLL or other changed identity
input invalidates the run; an old hash never labels that run as passing.
Tests exercise each identity input, damaged outputs and fresh JVM dispatch on
cache hits. Proptest is a pinned Rust dev dependency. The separate focused
[cargo-mutants audit](../../docs/research/issue1-focused-guard-audit.md) is an
isolated developer-tool audit, not a normal runner or production dependency.
Every runner Cargo command sets
`PROPTEST_CASES=64`, overriding an inherited value for reproducibility. Only
`property -Stress` sets 2048, and `-Stress` is rejected for other lanes. This is
a correctness case budget, not a benchmark warmup. A future minimized failure
must be preserved as an explicit deterministic regression alongside any seed.

See [synthetic fixture and decoder documentation](../../docs/research/issue1-synthetic-fixtures.md)
for immutable hashes, exact consumption, source independence and maintenance.

## External artifact preflight

The Clean Forge lane reads `.rustcraft-local/forge-runtime.json` by default,
or `-ForgeRuntimeManifest <local-json>`. That machine-local file contains paths
only; expected identities are the reviewed tracked pins in
`tools/forge-capture/runtime-pins.json`:

```json
{"schema_version": 1, "server_root": "D:/path/to/clean-forge-2860/server"}
```

Use the restored original Mojang server jar, installed Forge jar and exact
dependency tree. The runtime helper verifies all ordered artifact hashes,
embedded remapping/binpatch data, exact final transformed class hashes,
registry aliases, initialized built-in mods and applicable listeners. The
passive observation agent returns null for every transform and records the
bytes passed to JVM definition after Forge's transforms; it is separately
identified in receipts. No deobfuscated development jar is substituted.

Every invocation starts fresh qualification and oracle JVMs, even with cached
compilation. The native DLL hash participates in cache identity and is checked
before/after execution. Inherited JVM option variables are removed before
launch and their names recorded in the receipt. Missing files or
identity mismatch stop with exit 2 / INCOMPLETE and an explicit reason;
semantic defects fail with exit 1. Successful JVM exit alone is insufficient:
the independent replay must verify every required accepted/rejected case and
consume each packet exactly. Local receipts, fixture hashes, owned inputs and
payloads remain under the ignored run directory; proprietary binaries are not
committed. See the [runtime qualification](../../docs/research/issue1-clean-forge-capture-proof.md)
and [writer audit](../../docs/research/issue1-clean-forge-writer-audit.md).

The separate **benchmark** lane retains its older classpath-manifest interface,
`-ForgeClasspathManifest <local-json>`:

```json
{
  "schema_version": 1,
  "kind": "forge-classpath",
  "minecraft_version": "1.12.2",
  "forge_version": "14.23.5.2860",
  "classpath": [
    {"path": "minecraft_server.1.12.2.srg.jar", "sha256": "<actual 64 lowercase hex characters>"},
    {"path": "forge-1.12.2-14.23.5.2860.jar", "sha256": "<actual digest>"}
  ]
}
```

Include **every required dependency jar in its intended resolution order**.
Paths resolve relative to the manifest; absolute local paths are also accepted.
The two shown entries are illustrative, not a complete Forge classpath. Digest
mismatches fail preflight; missing files skip execution as missing artifacts.
Compilation/runtime failure with an incomplete or incompatible classpath is
reported as failure. Existing oracle sources mix obfuscated/SRG expectations;
classpath qualification is required before interpreting results as coverage.
The harness cwd is the run output directory so its relative `benchmarks/`
files stay outside tracked evidence.

`-ModpackArtifactManifest <local-json>` accepts the same version fields with
`kind: "modpack-artifacts"` and `artifacts: [{"path": ..., "sha256": ...}]`.
The modpack preflight accepts Forge 14.23.5.2846 (Revelation) or 14.23.5.2860
(SevTech); this does not broaden the Clean Forge oracle's qualified target.
Include the actual ordered mod/coremod/config identity inputs to preflight;
the receipt preserves the supplied manifest. This performs file/hash presence
checks only. It does not assert completeness, load a pack, replay coherent
captures or enable authority. Forge/modpack semantics need separately accepted
artifacts and a qualified replay harness.
