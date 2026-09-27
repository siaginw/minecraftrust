# JNI lifecycle prototype

Isolated H22.5.E experiment using exactly `jni 0.22.4`, with default features
disabled. This is a standalone Cargo workspace. It has no RustCraft production
exports, runtime qualification, native packet authority, or performance claim.

Run from this directory with Python 3.12 or newer. The recorded Windows campaign
uses Python 3.14, Temurin Java 8u504, and explicit Rust tool paths:

```powershell
python -B run.py `
  --java-home D:\rustcraft-toolchains\temurin8\jdk8u504-b01 `
  --cargo C:\Users\Admin\.cargo\bin\cargo.exe `
  --rustc C:\Users\Admin\.cargo\bin\rustc.exe `
  --cache C:\Users\Admin\.cargo\registry\src\index.crates.io-1949cf8c6b5b557f `
  --crate-cache C:\Users\Admin\.cargo\registry\cache\index.crates.io-1949cf8c6b5b557f `
  --output D:\minecraftrust-astra-hardening\target\jni-lifecycle-prototype
```

The runner uses a fresh output directory, builds offline with the lockfile, runs
format/lint checks, compiles Java fixtures and both Rust lifetime controls, then
starts independent Java processes under `-Xcheck:jni`. Raw stdout/stderr and
commands are preserved. Each stream is limited to 2 MiB and each process to
120 seconds; inherited pipes get a one-second grace period after child exit.
Each fixture receives a fresh session and challenge. Source, tool, JDK runtime,
dependency source/archive, license, and built artifact hashes are checked again
at the end. Output is restricted to this isolated checkout's `target/` tree;
the original-checkout and production-gate guard runs before and after. Unexpected
failures retain a scoped FAIL campaign receipt and any existing process logs.

The runner removes inherited Java launcher/classpath options, Rust flags and
wrappers, Cargo overrides, Clippy overrides and compiler/linker flags. It records
the effective environment policy, pins `RUSTC` and `CARGO_HOME`, places the resolved
Rust toolchain first on PATH, and hashes the actual Rust tool executables as well
as the supplied shims. Existing Cargo configuration is hashed, and offline Cargo
metadata must resolve every dependency to the verified source cache.

The full fixture has 66 assertions. Twelve isolated modes separate library load,
JNI initialization, local frames, callbacks, exceptions, native worker attachment,
class identity and globals. Native threads assert detached status before and
after scoped attachment. A failed lifetime control is accepted only alongside a
successful scalar-return control and the expected compiler diagnostic.

The selected Java 8 launcher emits `-Xcheck:jni` warnings before the fixture runs.
The runner compares them with no-library Java controls having identical argument
counts, retains every warning, and rejects new diagnostics. Its successful status
is therefore `PASS_WITH_JVM_BASELINE_WARNINGS`, never an absolute clean-JNI claim.
The deliberately caught Rust panic has separately checked and retained stderr.

GC after releasing a global is observed for a bounded interval. Failure to observe
collection is reported as inconclusive. Observed collection is not proof of leak
freedom.

`collect_licenses.py` is a provenance collection utility, not part of ordinary
runs. It copies license/notice files from checksum-verified cached crate archives.
When a crate archive omits license text, it fetches the upstream license from the
exact commit recorded in that archive. `run.py` needs no network and verifies the
retained inventory and source bytes. See `third-party/inventory.json` and the
[evaluation report](../../docs/research/jni-classfile-tooling.md).
