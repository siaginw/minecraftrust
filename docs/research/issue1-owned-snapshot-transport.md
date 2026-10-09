# Owned snapshot transport and property qualification

This input is an additive **offline owned-snapshot** contract with separate
synthetic and qualified Clean Forge oracle provenance scopes. It is not a live
Forge adapter, a publication permit, or a replacement for the production gate.
See [capture contract](issue1-coherent-capture-contract.md) for the writer and
ServerThread requirements. No existing JNI method or result layout is replaced.

## RCSNAP01 version 1

All multibyte fields are big-endian. Fixed header length is 128 bytes.

| Offset | Type | Meaning |
| --- | --- | --- |
| 0 | 8 bytes | ASCII `RCSNAP01` |
| 8 | u16 | Version 1 |
| 10 | u8 | Full-chunk bit 0, skylight bit 1; other bits zero |
| 11 | u8 | Storage 1 vanilla u16, 2 resolved NEID with zero high plane; other models reject |
| 12 | u8 | This snapshot's global palette width, 9 through 16 |
| 13 | u8 | Scope 1 SYNTHETIC_OFFLINE or 2 REAL_CLEAN_FORGE_ORACLE; other scopes reject |
| 14 | u16 | Reserved zero |
| 16, 20, 24 | i32 each | Dimension, chunk X, chunk Z |
| 28 | u64 | Positive Java-long generation identity |
| 36, 38 | u16 each | Requested filter, accepted mask; accepted must be a subset |
| 40 | u64 | Positive Java-long capture event identity |
| 48, 56 | u64 each | Positive, equal owner/capture thread IDs |
| 64, 72 | u64 each | Equal, nonnegative Java-long mutation epochs |
| 80, 88 | u64 each | Equal, positive Java-long incarnation identities, independent of generation |
| 96 | 32 bytes | Opaque SHA-256 provenance identity; not authentication or synchronization proof |

After the header comes a u16 section count equal to `popcount(accepted_mask)`.
Each selected Y appears exactly once in ascending order: u8 Y, u8 reserved zero,
u16 non-air count, 4096 u32 logical IDs, 2048 block-light bytes, and 2048 sky-light
bytes if enabled. A full chunk has a 256-byte biome tail. No trailing bytes are
allowed. This model identifies logical ID zero as air; the Clean Forge adapter
qualifies the exact registry, constructor and refcount semantics separately in
the [offline proof](issue1-clean-forge-capture-proof.md).

IDs remain wide until validation. The present native implementation rejects IDs
above 65535; global palettes also reject values unrepresentable in their explicit
width. Selected all-air sections are valid for partial chunks and rejected for
full chunks. A resolved NEID zero-high transport does not authorize skipping the
Java high-plane checks. JEID remains unsupported.

Rust parses into private owned arrays, then materializes an unregistered
`NativeChunk` for one encode. It does not read retained cache state or alter
retained-section accounting. The per-operation palette width bypasses the legacy
global setting without changing that setting or its defaults. Both metadata
fields originate from the same strict serialization. Accepted snapshots can be
encoded again without observing later source mutations.

`OwnedSnapshotBridge.encodeOwnedV1` is additive JNI: owned input address/length,
output address/capacity, returning the existing V2 packed long. Numeric bounds
and overlap checks precede slice construction. The caller still must own valid
allocations, hold input stable and keep output exclusive. Any failure invalidates
all output scratch and has no usable byte-count/mask fields. Rich parser reasons
map to existing V2 failures: lifecycle -3, missing selected section -4, capacity
-5, other parser/encoder failures -6; numeric arguments -1 and unwinding panic
-7. The input scope byte is not an authenticated claim of coherent capture.

The `ffi` example `snapshot_replay` preflights admission for precise rejection
reasons, then calls the actual JNI export once for successful serialization. It
creates a new output file only after success and exposes exactly the returned
count/mask in JSON. It never overwrites existing fixture artifacts.

## Proptest qualification and reproducibility

Independently checked the exact upstream [1.11.0 tagged manifest](https://github.com/proptest-rs/proptest/blob/v1.11.0/proptest/Cargo.toml)
and the crates.io package downloaded by Cargo: version **1.11.0**, dual license
**MIT OR Apache-2.0**, declared **Rust 1.85** minimum. The earlier tooling survey's
moving-main Rust 1.86 observation does not override this pinned release's
manifest. This checkout uses Rust 1.98.1; no claim of testing on 1.85 is made.
The crates.io archive checksum recorded in `Cargo.lock` is
`4b45fcc2344c680f5025fe57779faef368840d0bd1f42f216291f0dc4ace4744`.
The installed normalized package manifest SHA-256 was
`5f4e8f0f10653f121aabdf4dd7f2712d06b7cc7a1fdcea98b18d5d731d33fa2a`.

Only `native-chunk` and `ffi` **dev-dependencies** use exact `=1.11.0`, with
default features disabled and only `std` enabled. Cargo's normal dependency tree
contains no Proptest dependency. No production dependency, compression backend,
process-global setting or runtime default changes are introduced by this
transport. The later focused cargo-mutants audit is separately documented.

Four properties use independent small models: arbitrary selected/present sparse
masks and capacities with retries; fill/clear/refresh/encode operation sequences;
V2 field round trips and corrupted reserved bits; canonical errors and successful
retries. The uniform-section scanner consumes the body to its end and checks
every state index and light byte without using the encoder's selection helpers.
The Python strict decoder covers general palette widths and arbitrary fixture
states separately. V2 Rust reference decoding is test-only; the actual Java
decoder is separately exercised in standalone tests.

Normal runs use 64 generated cases per property; `property -Stress` uses 2048.
Shrinking is bounded at 10,000 iterations. Before generation, exact `.case`
inputs are replayed from `tests/fixtures/issue1-properties` and ignored
`target/rustcraft-tests/proptest`. The dev-only harness persists minimized
numeric values and their property name, not only an RNG seed. Its format is
`ISSUE1_PROPERTY_V1`, property name, comma-separated unsigned integers on three
lines. Mask fields are selected,present,sky,full,capacity-mode; operation values
encode Y, action, flags and capacity mode as documented in the property test.
V2 cases store count,mask,reserved-bit or failure-code,mask,count. Reviewed cases
are promoted to the tracked directory and replay automatically.

During development Proptest minimized `[1,0,0,0,0]`: the existing local-palette
encoder conservatively reserves five bytes per palette ID, so an exact-sized
wire buffer may reject. This was a too-strong test expectation, not shortened
successful output. The concrete case is preserved as `conservative-capacity.case`;
the model now allows conservative rejection, forbids success below actual wire
size, and requires retry with sufficient capacity to succeed. No production
capacity behavior was changed to make the model green.
