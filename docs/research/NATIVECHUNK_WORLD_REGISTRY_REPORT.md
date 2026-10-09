# NATIVECHUNK_WORLD_REGISTRY — world-lifecycle NativeChunk registration

Status: **NATIVECHUNK_WORLD_REGISTRY_PROVEN** (2026-10-06, main=6727f94).

## Why packet-scoped registration was insufficient

The NativeChunk registry's creators were `PacketAuthorityExperiment.seedFromTransport` (packet admission) and offline benchmarks — a chunk existed in Rust only after a client received it (~32 chunks observed). A loaded server chunk exists regardless of packets, clients, or visibility, so the Rust block-light authority could not look up arbitrary loaded chunks and had to snapshot them from Java per job (~4-7 ms/job, >99% of authority cost; see the A/B in RUST_BLOCK_LIGHT_PROPAGATION_AUTHORITY_REPORT §0f).

## Boundary (goal §1/§4/§5)

```
Chunk.onLoad        (SRG func_76631_c, notch axw/c) → register exactly once
Chunk.onChunkUnload (SRG func_76623_d, notch axw/d) → generation-invalidated unload
```

Registration = `registerPrimer` shell + the existing M4 first-touch full sync (states + block light + sky light + biomes) — one Java→Rust snapshot per CHUNK LOAD, amortized across all future mutations (§13). Coherence after registration is the proven M4 model: `setBlockState` → `ChunkMutationTracker.onBlockSet` → native update + dirty refresh; light writes → `onLightSet` → light-dirty → refresh before encode. Unload reclaims state; re-registering the same coordinates takes a NEW generation (§5/§6/§11). Packet authority now only LOOKS UP — it no longer creates semantic ownership (§37).

## The silent-shadowing bug (root cause, found by instrumented runs)

`ChunkMutationTransformer` never ran in the tweaker launch: it was listed only in the dead `RustCraftCoreMod.getASMTransformerClass`. After registering it from the tweaker, hooks STILL did not fire: the transformer's notch-name wildcard (`mn.name.length() <= 2`) let `func_76630_e` `()V` (generateSkylightMap) match EVERY short `()V` method — `onLoad` and `onChunkUnload` were silently patched as `onSkylightRegenerated`. Fixed with exact `(SRG, notch)` name+descriptor pairs from `joined.srg` (`isExact`); seven seams, no wildcards.

## Evidence (receipts in target/authority-review/)

- **§9 no-client proof** (worldreg-noclient8): server booted with zero clients — `m4_registered_chunks=625` (spawn area + worldgen), purely world-lifecycle-driven.
- **§10/§11 player + unload/reload** (worldreg-player1, DEV, campaign PASS): 625 registers / 336 unregisters / 289 resident (`m4_current_registered_count=289` = the live loaded set after teleport legs); revisits re-register under new generations; `worldRegistryMissing=0`.
- **Composition**: block-light authority live in the same session — 1,189 jobs admitted, 21,850 cells committed, 0 errors.

## Deferred to the zero-staging milestone

§16's full counter battery is wired (registers/unregisters/bootstrap/missing/stale); §17 stress and §13 registration p50/p95/p99 land with the zero-staging A/B. The registry now makes §19-§27 (zero-staging LightWorld over the registry) possible.
