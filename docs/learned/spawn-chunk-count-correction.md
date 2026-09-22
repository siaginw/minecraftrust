# Correction Note: Initial Spawn Chunk Count (P0-1 Audit)

## 1. Issue Description
In P0-1 documentation (`boot_sequence.md`, `reference-boot-flow.md`, `boot_invariants.md`, `boot-sequence.yaml`), the initial spawn area loaded by `MinecraftServer.initialWorldChunkLoad()` was stated to be a "19x19 area / 361 chunks".

## 2. Root Cause & Origin
The "19x19 chunk area" was an unverified heuristic carried over from later Minecraft versions (1.14+ where spawn radius was modified) or colloquial modding lore conflating default `spawn-protection` or `view-distance` radii with chunk generation. It was accepted without direct arithmetic calculation against the decompiled 1.12.2 source.

## 3. Empirical Source Evidence & Explicit Arithmetic
In `net/minecraft/server/MinecraftServer.java:258-285` (`initialWorldChunkLoad`):
```java
int p_xxx = 625;
for (int x = -192; x <= 192 && this.isServerRunning(); x += 16) {
    for (int z = -192; z <= 192 && this.isServerRunning(); z += 16) {
        p_xxxx++;
        p_xxxxxx.func_72863_F().provideChunk(
            p_xxxxxxx.getX() + x >> 4,
            p_xxxxxxx.getZ() + z >> 4
        );
    }
}
```
Arithmetic:
- Loop bounds: `x = -192` to `x <= +192` inclusive with step `16`.
- Number of X steps: `(192 - (-192)) / 16 + 1 = 384 / 16 + 1 = 24 + 1 = 25` iterations.
- Number of Z steps: `(192 - (-192)) / 16 + 1 = 384 / 16 + 1 = 24 + 1 = 25` iterations.
- Total chunks requested: `25 * 25 = 625` chunks.
- The progress calculation explicitly uses this denominator: `p_xxxx * 100 / 625`.

## 4. Remediation
All documentation and machine artifacts (`boot_sequence.md`, `reference-boot-flow.md`, `boot_invariants.md`, `boot-sequence.yaml`) have been corrected to reflect `25x25 grid / 625 chunks`.
