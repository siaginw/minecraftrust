# Project Charter

## Mission

Create a new high-performance runtime for Minecraft 1.12.2 and Forge modpacks that preserves existing mod behavior while progressively replacing legacy server subsystems with Rust.

The server should eventually support large modpacks without requiring mod authors or pack maintainers to port their `.jar` files.

## Why 1.12.2

Minecraft 1.12.2 is a strong target because:
- the version is frozen
- protocol behavior is stable
- Forge 1.12.2 has a mature ecosystem
- many large modpacks exist
- difficult performance workloads are easy to reproduce
- server-side behavior can be studied indefinitely without chasing Mojang releases

## Definition of Success

The project is successful when it can demonstrate all of the following:

1. Existing Forge 1.12.2 clients can connect normally.
2. Existing large Forge modpacks load with minimal or no modification.
3. Compatibility is measured rather than claimed.
4. Every migrated subsystem has automated parity tests.
5. Every migrated subsystem has a benchmark before and after migration.
6. Rust ownership increases over time.
7. JNI/FFI overhead remains bounded and observable.
8. Performance improves materially on real modded workloads.
9. A large modpack can sustain 20 TPS under workloads that overwhelm a normal Forge server.

## Non-Goals Early in Development

Do not initially:
- rewrite every Minecraft class
- rewrite Forge
- port mods to Rust
- build a new plugin ecosystem
- optimize without profiling
- introduce region-parallel ticking before behavior is understood
- make permanent APIs out of temporary migration bridges
