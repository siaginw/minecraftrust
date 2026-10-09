# Rust Crates and Reference Projects

This is an evaluation list, not an instruction to permanently depend on everything listed here.

## Foundation

### `bytes`
Candidate for:
- byte buffers
- packet buffers
- zero/low-copy slices

### `tokio`
Candidate for:
- async networking
- timers
- background I/O orchestration

Important:
Hide Tokio behind project traits/interfaces where reasonable. Do not make the entire architecture inseparable from one runtime.

### `mio`
Study as:
- lower-level evented I/O option
- possible future transport backend

### `tracing`
Use from day one for:
- spans
- tick profiling hooks
- subsystem visibility
- structured diagnostics

### `criterion`
Use for:
- repeatable Rust microbenchmarks

### `jni`
Use only inside the dedicated bridge crate.

Rule:
No other Rust crate imports JNI directly.

### `serde`
Useful for:
- internal config
- manifests
- tooling

Do not assume serde is automatically the fastest path for Minecraft binary formats.

## Minecraft-Specific References

### Valence
Study:
- Rust Minecraft protocol architecture
- entity/world patterns
- packet handling
- server organization

Do not assume its version semantics match 1.12.2.

### Pumpkin
Study:
- modern Rust Minecraft server design
- multithreading
- world organization
- compatibility concepts

Use as architectural reference, not a drop-in 1.12.2 solution.

### FastNBT / related NBT crates
Study:
- binary layout
- performance strategies
- serde integration
- edge cases

Benchmark before adoption.

### Anvil / region file crates
Study:
- `.mca` format handling
- sector allocation
- compression boundaries
- corruption handling

## Java / Forge References

Study:
- Minecraft 1.12.2 server source
- Forge 14.23.5.x
- LaunchWrapper
- Forge event bus
- Forge networking
- capabilities
- registries
- access transformers
- ASM/coremods
- selected major 1.12.2 mods

## Dependency Rule

For every third-party dependency ask:
1. Do we need it at runtime?
2. Is it version-stable?
3. Is the API appropriate for a long-lived core?
4. Can we wrap it?
5. Can we replace it later?
6. Is its performance measured?
