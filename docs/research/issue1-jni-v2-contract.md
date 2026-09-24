# Issue #1: JNI V2 single-result contract

This stage carries the accepted Rust `PacketEncodeResult` through a new JNI
entry point to a dependency-free Java 8 decoder. It is public-checkout structural
validation, not Forge integration or coherent live capture. Production
`M4NativeStatePayload.tryEncode` remains fail-closed and Issue #1 remains open.
MCK6, compression, defaults and the historical evidence registry are unchanged.

The accepted parent commits are preserved: `5826648cfb5c6d046000b091e7201d207a7cba90`
(structural correction) and `baea1a1fa7069365d11b94395e27b5738387b191` (research).
Development continues locally on `issue1-jni-v2`; no push is authorized.

## Packed result specification

The method name versions the ABI: `NativeChunkBridge.encodePacketPayloadV2` takes
the same arguments as the historical method and returns a Java `long` / Rust
`i64`. The historical `encodePacketPayload` still returns `int` / `i32` and is
unchanged. No field of its old return value has been reinterpreted.

| Bits | Successful encoding |
| --- | --- |
| 63 | Zero: success is positive in Java and Rust. |
| 62 | One: explicit success tag. |
| 61..47 | Reserved; must all be zero. |
| 46..16 | Unsigned 31-bit `bytes_written`, 0 through 2,147,483,647. |
| 15..0 | Unsigned 16-bit `emitted_mask`, 0 through 65,535. |

Success is `0x4000000000000000 | ((long) bytes_written << 16) | emitted_mask`.
The maximum encoded success is `0x40007fffffffffff`, strictly below
`Long.MAX_VALUE`. A zero count requires mask zero. Count zero/mask zero is valid
for an empty non-full payload. Count greater than zero/mask zero can carry only
biomes for a full payload. The result carries metadata, not those flags or the
buffer itself; callers retain them with the same request and owned output.

The format's count maximum is a representation bound, not a claim that a chunk
encoder will emit a payload of that size. Positive `jint` output capacity bounds
actual output. Rust checks the result range before widening and shifting, with
an additional zero-count/mask consistency guard.

Failures are **exact signed values**, never success fields with an error flag:

| Value | Name | Meaning |
| --- | --- | --- |
| -1 | `INVALID_ARGUMENT` | Null/nonpositive or numerically overflowing output address, nonpositive capacity, or flag outside 0/1. |
| -2 | `MISSING_HANDLE` | No native chunk exists at the requested coordinates. |
| -3 | `STALE_GENERATION` | Nonpositive/mismatched generation, or native lifecycle invalidated, unloading or freed. |
| -4 | `MISSING_SELECTED_SECTION` | The encoder's selected mask contains an absent section. |
| -5 | `OUTPUT_CAPACITY` | Section output or final biome output does not fit. |
| -6 | `ENCODE_FAILURE` | Other encoder error, poisoned native lock, or unrepresentable result metadata. |
| -7 | `PANIC` | A Rust unwinding panic was contained by this entry point's result boundary. |

Zero, tagless positive values, successes with reserved bits set, zero-count
success with a nonzero mask, and negative values outside -1..-7 are malformed or
reserved. A Java decoder rejects these with `IllegalArgumentException`.

Java must check the sign/error case **before** extracting any fields: negative
two's-complement longs also have bit 62 set. For validated success, decode the
count using `(packed >>> 16) & 0x7fffffffL` and mask using `packed & 0xffffL`.
Only then narrow to Java `int`. This is a scalar JNI result, not a byte buffer
with an implicit endianness. Both sides test boundary encodings, including the
maximum count and mask.

## One operation and failure semantics

`crates/ffi/src/packet_encode_v2.rs` validates numeric arguments, takes the native
registry membership read lock and the selected chunk's write lock, then checks
the generation/lifecycle under those locks. It calls `encode_packet_payload`
exactly once on the accepted path and packs the returned count and mask.
Preflight rejection calls the encoder zero times. There is no second mask
lookup, Java scan, cached-mask substitution, length inference or internal retry.

Holding native membership through the call prevents native removal/replacement
during serialization. It does not synchronize Java/mod writers, freeze Java
arrays or guarantee that the handle is still publishable after return. Existing
dirty state is not a proof of capture freshness. V2 adds no packet authority.

On any error the output is invalid scratch: discard it, and publish neither a
count nor a mask. A later biome-capacity error can leave section bytes written;
that is still only error -5. The Java result helper prevents count/mask access on
a decoded failure. Malformed encodings also cannot produce a success object.
The helper does not change Java packet fields or fall back to a separate mask.

Valid retries after capacity, argument, missing-section or generation correction
are tested. The boundary has no sticky failure state. A poisoned/corrupt native
object remains ineligible; catching a panic is not permission to reuse arbitrary
partially mutated state. In-process recovery of a poisoned handle is not
implemented: existing legacy replacement/removal methods can themselves panic
on poisoned chunk locks and poison the registry. Such recovery requires a
known-valid fresh state/JVM, not simply re-registering the poisoned chunk. No
production test-panic export or magic pointer sentinel was added.

JNI still uses the project's raw-address buffer convention. The caller must
provide a live, sufficiently sized writable allocation with exclusive ownership
and retain it through output consumption. Numeric validation cannot establish
that an arbitrary nonzero address is mapped, writable or large enough. Tests
exercise null, negative and overflowing addresses without dereference, and valid
allocations with invalid capacities; they never fabricate a dereferenced pointer.
`catch_unwind` contains unwinding Rust panics, not access violations, allocator
termination or builds configured to abort on panic. The current release profile
uses unwinding; that build configuration is part of qualification.

The accepted Rust API currently returns string errors. This adapter maps its
known missing-section/capacity strings to distinct classes and all other errors
to -6. Unknown future errors cannot silently become a successful shorter result.

## Validation and scope

Six Rust unit tests cover result boundaries/ranges, canonical errors, invalid
metadata rejection, one boundary invocation per attempt, test-only panic
containment, error mapping and poisoned-lock rejection. Nine integration tests
exercise the actual V2 JNI export with owned native buffers, including complete
payload scanning, sparse/all masks, empty payloads, legacy byte parity, missing
sections, missing/stale/invalidated handles, early/late capacity errors, retries,
invalid arguments and metadata retained across later native mutation.

The standalone Java decoder test covers all error classes and malformed values.
The real Java/JNI harness loads the release DLL and exercises serialization,
failure/retry and lifecycle cleanup without Minecraft jars. Missing-section,
generic encoder failure and panic injection are native test seams, not claimed
Java fault-injection scenarios. The Java harness proves scalar JNI transport
and owned synthetic-buffer behavior; it does not prove Forge integration.

Required commands, including targeted tests, are:

```text
cargo test --locked -p native-chunk
cargo test --workspace --lib --locked
cargo test --locked -p protocol
cargo build --release --locked -p ffi
cargo test --locked -p ffi --lib packet_encode_v2
cargo test --locked -p ffi --test packet_encode_v2
cargo test --locked -p native-chunk --test packet_encode_contract
tools/run-rustcraft-tests.ps1 public
tools/run-rustcraft-tests.ps1 java-jni
git diff --check
```

Use the configured JDK 8u504 for the Java tests. Runner receipts identify exact
commands, toolchain, source/cache inputs and DLL content. Forge/modpack lanes
remain `NOT_RUN / MISSING_EXTERNAL_ARTIFACT` when their required artifacts are
absent. A skipped lane is not a passing comparison. No new testing dependencies
are needed for V2; the previously researched frameworks remain deferred.

| Tool | Adoption gate retained for follow-up work |
| --- | --- |
| Proptest | V2 and a snapshot model exist, and generated state sequences add useful coverage. |
| cargo-mutants | Core snapshot guards exist to audit. |
| JUnit / jqwik | The Forge Java oracle environment is restored and qualified. |
| JMH | Correctness and the complete packet pipeline are established. |
| Prismarine | Qualified strictly as a secondary decoder, including malformed-input rejection and complete consumption. |
| nextest | Equal-scope timing demonstrates a benefit on Windows. |

Only the runner and compilation-cache infrastructure are adopted now. No tool
in that table was installed or added to the dependency graph.

## Future packet shell and exact next stage

The required future relationship is:

```text
COHERENT OWNED JAVA SNAPSHOT
  -> one Rust encode V2
  -> one accepted {bytes_written, emitted_mask} with its output bytes
  -> Java SPacketChunkData shell uses emitted_mask as the wire mask
```

The shell must not call `computeEffectiveMask`, `getPrimaryBitMask`, re-walk Java
sections or substitute cached metadata after Rust serialization. Existing
legacy/shadow/fixture callers remain for historical validation, and their
limitations are enumerated in the [call-site audit](issue1-jni-v2-call-site-audit.md).
No existing packet shell was migrated or enabled in this stage.

The next stage is a reviewed capture/publication design at the actual Java
packet writer: inventory participating writers and enforceable ownership for
blocks, section presence/refcounts, palettes, light, biomes and lifecycle;
explicitly exclude or reject unknown writers. Define an immutable same-event
capture and show that the actual Java writer consumes that same state. Specify
full-width state validation before native narrowing, rejection/refresh safety,
buffer lifetime and the final publication eligibility check. The
[fixture schema](chunk-packet-fixture-v1.md) records these bindings; hashes alone
do not prove them. Do not implement live capture or enable the gate based on
these structural tests.

Classification remains `ROOT_CAUSE_CLASS_HARDENED` and
`HISTORICAL_EXACT_WRITER_UNRESOLVED`. V2 does not diagnose the historical 63/31
writer and does not close Issue #1.
