# Initial Repository Skeleton

```text
project/
|
+-- README.md
+-- Cargo.toml
+-- rust-toolchain.toml
|
+-- crates/
|   +-- core-types/
|   +-- buffers/
|   +-- transport/
|   +-- compression/
|   +-- nbt/
|   +-- region-io/
|   +-- ffi/
|   +-- contracts/
|   +-- metrics/
|   +-- test-support/
|
+-- java/
|   +-- bootstrap/
|   +-- bridge/
|   +-- instrumentation/
|   +-- forge-adapter/
|
+-- docs/
|   +-- architecture/
|   +-- learned/
|   +-- migrations/
|   +-- adr/
|   +-- benchmarks/
|   +-- compatibility/
|
+-- machine/
|   +-- ownership.yaml
|   +-- subsystem-status.yaml
|   +-- benchmark-gates.yaml
|
+-- tests/
|   +-- parity/
|   +-- integration/
|   +-- modpacks/
|   +-- golden-worlds/
|
+-- benchmarks/
|   +-- micro/
|   +-- component/
|   +-- full-server/
|
+-- tools/
|   +-- source-indexer/
|   +-- benchmark-runner/
|   +-- world-diff/
|   +-- trace-analyzer/
|
+-- third_party_reference/
    +-- README.md
```

## Crate Responsibilities

### `core-types`
Stable Rust IDs/types with no JNI knowledge.

### `buffers`
Shared buffer abstractions.

### `transport`
Network transport interface and implementations.

### `compression`
Compression/decompression.

### `nbt`
Minecraft NBT codec and test corpus.

### `region-io`
Anvil/region persistence.

### `ffi`
The **only** crate allowed to directly use JNI.

### `contracts`
Coarse work contracts:
- PacketBatch
- SaveBatch
- MutationBatch
- ChunkSnapshot
- RegionSnapshot

### `metrics`
Instrumentation.

### `test-support`
Cross-language test fixtures, deterministic data, fuzz helpers.

## Dependency Direction

```text
Minecraft/Forge adapter
        |
       FFI
        |
     contracts
        |
  Rust subsystems
        |
    core-types
```

Rust subsystem crates must not import Forge/JNI concerns.
