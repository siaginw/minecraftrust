# Boundary and Ownership Model

## Why This Exists

The project will fail if Java and Rust both believe they own the same mutable state.

Every subsystem must have exactly one authoritative owner.

## Ownership States

Use one of:

- `JAVA_OWNED`
- `JAVA_OWNED_RUST_ACCELERATED`
- `SHADOW_TESTED`
- `RUST_OWNED_JAVA_VIEW`
- `RUST_OWNED`
- `RETIRED`

## Example Migration

### Step A

`NBT codec = JAVA_OWNED`

### Step B

`NBT codec = SHADOW_TESTED`

Java result and Rust result are produced and compared.

### Step C

`NBT codec = JAVA_OWNED_RUST_ACCELERATED`

Java API calls one coarse native codec function.

### Step D

`NBT codec = RUST_OWNED`

Java implementation is retained only as compatibility fallback/testing oracle.

## Boundary Contracts

All Java/Rust interfaces must define:
- input schema
- output schema
- ownership
- mutability
- lifetime
- thread affinity
- error behavior
- copy behavior
- performance budget
- version

## Preferred Contract Shapes

Examples:

```text
encode_nbt(ByteBuffer input) -> ByteBuffer
compress_packet_batch(PacketBatch) -> EncodedBatch
save_chunks(SaveBatch) -> SaveResult
query_entities(QueryBatch) -> QueryResultBatch
apply_mutations(RegionId, MutationBatch) -> ApplyResult
```

Avoid contracts like:

```text
get_block(x,y,z)
set_block(x,y,z,state)
get_entity_field(id, field)
```

when they cross FFI in hot loops.
