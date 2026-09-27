# Bounded Java/Rust differential replay

This tool implements the independently written `REPLAY_TOY_V1` specification.
It does not implement Minecraft, qualify Forge callbacks, grant packet authority,
or establish production performance.

Run from this directory with existing tools:

```powershell
python -B run.py --java-home 'D:\rustcraft-toolchains\temurin8\jdk8u504-b01' --cargo 'C:\Users\Admin\.cargo\bin\cargo.exe' --output 'D:\minecraftrust-astra-hardening\target\differential-replay'
```

Every invocation uses a fresh output directory. Dependencies: Python standard
library, Java 8 standard library, Rust standard library. No added crates.
The standalone Cargo workspace and lockfile do not alter the main workspace.

See `../../docs/research/differential-event-replay.md` for the complete semantic
contract, measurements, first-divergence behavior, adapter interface and limits.
