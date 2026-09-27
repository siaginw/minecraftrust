# Bounded state-diff execution

An isolated, dependency-free Rust model: immutable locally admitted snapshot →
pure read/write diff → validation → ordered transactional publication.
Admission is explicitly `OFFLINE_MODEL_ONLY`, not runtime qualification.

```powershell
python -B run.py --java-home 'D:\rustcraft-toolchains\temurin8\jdk8u504-b01' --cargo 'C:\Users\Admin\.cargo\bin\cargo.exe' --output 'D:\minecraftrust-astra-hardening\target\state-diff-experiment'
```

The runner compiles the independent Java8 reference, tests/builds the standalone
Rust workspace, and compares every boundary. It imports the frozen sibling
`differential-replay/replay.py` only for bounded subprocess transport and receipt
helpers, and pins that exact source file. It never edits the replay prototype.

See `../../docs/research/state-diff-execution.md` for the full contract,
measurements, application decisions and integration gaps.
