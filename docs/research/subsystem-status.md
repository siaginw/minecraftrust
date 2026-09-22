# Subsystem Status (operator-mandated 2026-09-20; mirrors AGENTS.md)

| subsystem | status | basis |
|---|---|---|
| M1 SPacketChunkData | REOPEN_AFTER_NATIVE_STATE | proven correct but whole-server value unresolved; Java staging limits standalone value (M1I/M1J); revisit when Rust owns chunk/section data |
| M2-C native compression | READY_OPTIONAL (default OFF) | full ladder complete (M2C/M2CC/M2CE/M2CER/M2CED); matched 3v3 perf 2.26×, −39.9% worker CPU, +1% wire bytes |
| M2-PERSIST | ARCHITECTURAL_REOPEN_CANDIDATE | save encode already off-thread; mod-contract bound; reassess later as native storage infrastructure |
| M3 spawn-query index | REOPEN_AFTER_NATIVE_STATE | algorithm validated (114.9× skip) but java_ix≈rust_ix; useful when Rust owns structure/worldgen metadata (spatial-index utility retained) |
| M3 collision broadphase | REOPEN_AFTER_NATIVE_STATE | 46.2% ceiling capturable by Java counters; JNI boundary ≈ post-counter residual; revisit if Rust owns section/block metadata |
| M3 worldgen density | SHADOW_ACTIVE (Target A validated) | M3W4 bit-exact (240-chunk corpus) & 5.6% faster n=1; Target A live SHADOW validated (625 fields, 515,625 doubles, 0 mismatches, 0 leaks); active frontier |
