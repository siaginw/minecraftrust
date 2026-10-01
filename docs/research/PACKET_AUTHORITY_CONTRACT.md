# RustCraft SPacketChunkData Production-Authority Review & Contract

## 1. Executive Authority Decision

Following the successful completion and independent verification of the **FTB Revelation 3.4.0 V2 Live-Shadow Closure Campaign** (Minecraft 1.12.2, Protocol 340, Forge 14.23.5.2846, 219 mods; 4,905 comparisons, 0 mismatches, 0 dropped), this formal review establishes the strict boundary under which Rust-authored packet bytes may reach real Minecraft clients.

### Explicit Lifecycle State Machine

Automatic promotion to production authority is strictly forbidden by architectural governance. The subsystem progresses through explicit, non-skippable stages:

```
[ LIVE_SHADOW_CLOSED ]
          │
          ▼ (Formal Review Gate: receipt verification, scope lockdown, fail-closed contract)
[ AUTHORITY_REVIEWED ]
          │
          ▼ (Operator Flag: -Drustcraft.packetAuthorityExperiment=true, bounded cap)
[ BOUNDED_AUTHORITY_EXPERIMENT ]  <─── MAXIMUM PERMISSIBLE STATE FOR THIS MILESTONE
          │
          ▼ (Subsequent Milestone: retained ChunkState, zero-copy wire commit, full audit)
[ PRODUCTION_AUTHORITY ]          <─── STRICTLY UNREACHABLE (production_authority = FALSE)
```

- **Current State:** `BOUNDED_AUTHORITY_EXPERIMENT`
- **Production Authority:** `FALSE`
- **Java Fallback Invariant:** Intact, mandatory, never bypassed or removed.

---

## 2. Input Provenance & Closure Receipt Binding

Authority review is valid only when bound to verified machine-readable closure evidence:

| Artifact / Property | Value |
|:---|:---|
| **Closure Verdict** | `CLOSED` |
| **Closure Lifecycle State** | `LIVE_SHADOW_CLOSED` |
| **Commit SHA** | `dc99894664978b22be61e2b688eba974a815cc9b` |
| **Campaign Receipt SHA-256** | `915444fae9f56bf6f1837ff443c683b54ce025b3fa33f3869911e3bfa24c94b2` |
| **Canonical Profile SHA-256** | `744f95798bd24271fbe0e0d01f89a0a4a4c189e7b7635585b61d398c41a388d6` |
| **Campaign Coremod Jar SHA-256** | `4d474a05df53b82f9e77d89497ddc3cac3a556f630078e229052739d48ad817a` |
| **Semantic Denominator** | **4,905** ($\ge 2,000$ required) |
| **Unexplained Mismatches** | **0** ($= 0$ required) |
| **Drop Rate** | **0.00%** (0 / 5,380) |
| **Exclusion Rate** | **8.83%** (475 / 5,380) |
| **I/O-Origin Comparisons** | **4,885** ($\ge 200$ required) |
| **Distinct Incarnations** | **3,534** ($\ge 300$ required) |
| **Reload Cycles** | **297** ($\ge 20$ required) |
| **RCSNAP02 Transport Share** | **100.0%** (4,905 / 4,905) |

Input receipt path: `target/authority-review/closure-input-receipt.json`.

---

## 3. Scope Demarcation: Admitted vs. Excluded

Authority eligibility is strictly bounded to the proven scope of the closure campaign. It is **never** a blanket grant:

### Admitted Scope (Eligible for Bounded Rust Authority)
1. **Engine Version:** Minecraft 1.12.2, Protocol 340.
2. **Dimension:** Overworld only (`dimension == 0`, `WorldProviderSurface`).
3. **Packet Shape:** Full-chunk initialization (`changedSectionFilter == 0xFFFF` / `fullChunk == true`).
4. **Tile Entities:** Empty TileEntity map (`chunk.func_177434_r().isEmpty()`).
5. **State ID Width:** All sections have used logical state IDs $\le 65535$.
6. **Transport Schema:** RCSNAP02 deterministic section-local logical palettes.
7. **Session Governance:** V2 real-FML session-bound admission active and untainted.
8. **Cap Enforcement:** Cumulative `rustSelected < packetAuthorityCap`.

### Explicit Exclusions (Automatic Java Fallback)
1. **Dimensions:** Nether (`dim == -1`), End (`dim == 1`), any modded dimension (`dim != 0`) $\to$ `EXCLUDED_UNSUPPORTED_WORLD`.
2. **Partial Updates:** Sub-chunk delta packets (`changedSectionFilter != 0xFFFF`) $\to$ `EXCLUDED_UNSUPPORTED_FILTER`.
3. **Tile Entities:** Any chunk with $\ge 1$ TileEntity $\to$ `EXCLUDED_TE_PRESENT`.
4. **High-State Chunks:** Chunks where any block state ID $\ge 65536$ $\to$ `EXCLUDED_HIGH_STATE_ID`.
5. **Unadmitted Sessions:** Missing V2 certificate, wrong profile, or session mismatch $\to$ `FALLBACK_SESSION_UNADMITTED`.
6. **Cap Exhaustion:** Whenever `rustSelected >= packetAuthorityCap` $\to$ `JAVA_SELECTED_CAP_EXHAUSTED`.
7. **Native Errors:** Any Rust panic, buffer overflow, or encode failure $\to$ `FALLBACK_RUST_ENCODE_FAILURE`.

---

## 4. Fail-Closed Routing & Atomic Selection

For every `SPacketChunkData` instantiation:

```
                  SPacketChunkData.<init>(Chunk, int)
                                  │
                                  ▼
               Is packetAuthorityExperiment == true?
                        │               │
                       NO              YES
                        │               │
                        ▼               ▼
                   JAVA PACKET    Check Session Admission
                                        │
                                  ┌─────┴─────┐
                                FAIL         PASS
                                  │           │
                                  ▼           ▼
                             JAVA PACKET  Check Scope: Overworld,
                                          Full Chunk, TE Empty,
                                          Used States <= 65535?
                                              │
                                        ┌─────┴─────┐
                                      FAIL         PASS
                                        │           │
                                        ▼           ▼
                                   JAVA PACKET  Within Cap?
                                                (rustSelected < cap)
                                                    │
                                              ┌─────┴─────┐
                                            FAIL         PASS
                                              │           │
                                              ▼           ▼
                                         JAVA PACKET  Take Coherent Snapshot
                                                      & Invoke Native Encode
                                                            │
                                                      ┌─────┴─────┐
                                                    FAIL         PASS
                                                      │           │
                                                      ▼           ▼
                                                 JAVA PACKET  RUST PACKET
                                                 (Untouched   (Populate Shell,
                                                  Java Body)   Skip Java Body)
```

### Invariants:
1. **One Packet, One Representation:** Netty receives either pure Java serialization or verified Rust-encoded bytes.
2. **Fail to Java Before Commit:** Any error, exception, or rejection leaves packet fields untouched; control drops straight through to the original Java constructor body.
3. **No Double Send:** Exactly one representation reaches the wire. No double-packet emissions.
4. **No Speculative Transmission:** Rust bytes are committed only after the encoder returns a positive length within the allocated buffer.

---

## 5. Operator Controls & Bounded Experiment Parameters

The bounded authority experiment requires explicit operator opt-in via JVM system properties:

- `-Drustcraft.packetAuthorityExperiment=true` (Default: `false` — fail-closed)
- `-Drustcraft.packetAuthorityCap=64` (Default: `64`, range: `1` to `100`)

When `-Drustcraft.packetAuthorityExperiment` is absent or `false`, the subsystem behaves identically to existing production code: zero Rust bytes reach the socket.

---

## 6. Real-Time Telemetry & Accounting Counters

Every event is classified and counted monotonically:

- `authorityEligible`: Evaluated against admitted scope and passed all checks.
- `rustSelected`: Rust-authored payload committed to packet and wire.
- `javaSelected`: Java payload used for normal packet transmission.
- `javaFallback`: Rust encode attempted or evaluated but failed over to Java before commit.
- `rustEncodeFailure`: Native encoder returned error code or exception.
- `excludedHighState`: Excluded due to state ID $\ge 65536$.
- `excludedTE`: Excluded due to presence of TileEntities.
- `excludedFilter`: Excluded due to partial chunk filter.
- `excludedDimension`: Excluded due to non-Overworld dimension.
- `capExhausted`: Returned to Java because `rustSelected >= cap`.

Upon JVM termination or test completion, a structured receipt is emitted to:
`target/authority-experiment/authority-experiment-receipt.json`.
