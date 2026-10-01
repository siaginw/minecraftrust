# FTB Revelation 3.4.0 V2 Live-Shadow Closure Report

## 1. Executive Summary & Closure Declaration

The V2 live-shadow closure campaign for **FTB Revelation 3.4.0** (Minecraft 1.12.2, Protocol 340, Forge 14.23.5.2846, 219 mods) has **PASSED all 7 predeclared closure criteria** across two fresh, independent, session-bound JVM server launches without a single unexplained mismatch or infrastructure defect.

**Closure Verdict:** `CLOSED`  
**State Achieved:** `LIVE_SHADOW_CLOSED`  
**Production Authority State:** `FALSE` (Fail-Closed, `tryEncode -> null`)  
**Recommendation:** **`READY_FOR_RUST_PACKET_AUTHORITY_REVIEW`**

Under strict project governance, **Rust production packet authority has NOT been enabled.** Closure constitutes formal empirical evidence that the cross-language snapshot capture, RCSNAP02 transport, native encoder, and differential comparator achieve full semantic equivalence under a live, heavily-modded server runtime. Granting production packet authority remains a separate, subsequent architectural milestone requiring formal review.

---

## 2. Predeclared Closure Criteria vs. Counted Evidence

| Closure Criterion | Predeclared Threshold | Campaign Result | Status |
|:---|:---:|:---:|:---:|
| **Semantic Comparison Denominator** | $\ge 2,000$ | **4,905** | **MET** (245% of threshold) |
| **COMPARE_MISMATCH (Unexplained)** | **0** | **0** | **MET** (Zero tolerance) |
| **Drop Rate (`DROPPED / observed`)** | $\le 10.0\%$ | **0.00%** (0 / 5,380) | **MET** (0 dropped) |
| **Exclusion Rate (`EXCLUDED / observed`)** | $\le 60.0\%$ | **8.83%** (475 / 5,380) | **MET** (Well within bound) |
| **I/O-Origin Comparisons (`ioAdopted`)** | $\ge 200$ | **4,885** | **MET** (2,442% of threshold) |
| **Distinct Chunk Incarnations** | $\ge 300$ | **4,546** | **MET** (1,515% of threshold) |
| **Distinct Unload $\to$ Reload Cycles** | $\ge 20$ | **491** | **MET** (2,455% of threshold) |
| **Disqualifications & Integrity Failures** | **0** | **0** | **MET** (Zero exceptions) |
| **Native-Authored Bytes on Wire** | **0** | **0** | **MET** (Java authoritative) |
| **Production Authority Flag** | `false` | `false` | **MET** (Fail-closed) |

---

## 3. Workload Coverage Solution: `CampaignTeleportController`

The prior campaign was bounded by player rubber-banding in the headless workload client, which restricted movements to spawn chunks where vanilla `WorldProviderSurface.canDropChunk` prevents unloads.

To solve this honestly without altering engine physics, serializers, or authority:
1. **Server-Side Test-Only Controller**: Implemented `CampaignTeleportController.java` (strictly default OFF, activated solely via `-Drustcraft.closureCampaignTeleport=true`).
2. **Canonical Server-Thread Scheduling**: Uses `MinecraftServer.addScheduledTask` (`func_152344_a`) to invoke `EntityPlayerMP.setPositionAndUpdate` (`func_70634_a`) and triggers `PlayerList.serverUpdateMovingPlayer` (`func_72358_d`) to advance the real `PlayerChunkMap`.
3. **Decoupled Boot Timing**: Configured a 60-second initial boot sleep to allow LaunchWrapper, Forge coremods, Mixins, and ASM transformers to complete initialization without foreign thread classloading interference.
4. **Pre-Generated World Corridor Navigation**: Traverses the pre-generated terrain strip ($Z \approx 64, X \in [1024, 35000]$) across regions `r.2.0.mca` through `r.70.0.mca`. Because the terrain is pre-generated on disk, chunk acquisitions are 100% genuine disk I/O (`ioAdopted`) without cascading worldgen lag.
5. **Deterministic Base Revisit Cycles**: The controller moves the player to distant waypoints ($\ge 2048$ blocks away), waits for view-distance unloads, and returns to the base waypoint $(1024.5, 80, 64.5)$, causing the base chunks to genuinely unload and reload from disk under new monotonic incarnations (`LiveChunkBindings.createBinding`).
6. **Protocol 340 Client Alignment**: Fixed `workload_client.py`'s `CB_PLAYER_POS_LOOK` parsing offset (reading VarInt `teleport_id` after the 33-byte position prefix) and implemented `SB_PLAYER_POSITION` acknowledgments alongside `SB_CONFIRM_TELEPORT`.

---

## 4. Multi-Session Evidence & Metrics Reconciliation

The campaign was conducted across two clean, disposable server copies. Each session independently performed V2 real-FML session-bound qualification, generated fresh session nonces, and emitted complete receipts.

### Session 1:
- **Process ID:** `e842350a-4dd9-4324-a919-1e0f24093881`
- **Transformation Session ID:** `564e0175-9802-46ae-882b-85066bc2f1f1`
- **Taxonomy:**
  - `COMPARE_PASS`: 2,412
  - `COMPARE_MISMATCH`: 0
  - `EXCLUDED`: 245
  - `DROPPED`: 0
  - `DISQUALIFIED`: 0
  - `INFRA_FAILURE`: 0
- **I/O-Origin Comparisons:** 2,406
- **Distinct Incarnations:** 2,211
- **Reload Cycles:** 237
- **RCSNAP Distribution:** RCSNAP01 = 0, RCSNAP02 = 2,412

### Session 2:
- **Process ID:** `2997e908-d7c7-4e5f-86c5-8983532889f7`
- **Transformation Session ID:** `af8c2eed-d1f3-4fe0-be78-1ce1383860db`
- **Taxonomy:**
  - `COMPARE_PASS`: 2,493
  - `COMPARE_MISMATCH`: 0
  - `EXCLUDED`: 230
  - `DROPPED`: 0
  - `DISQUALIFIED`: 0
  - `INFRA_FAILURE`: 0
- **I/O-Origin Comparisons:** 2,479
- **Distinct Incarnations:** 2,335
- **Reload Cycles:** 254
- **RCSNAP Distribution:** RCSNAP01 = 0, RCSNAP02 = 2,493

### Reconciliation Check:
- `sum_session_counters_equal_campaign`: **TRUE** ($2,412 + 2,493 = 4,905$)
- `denominator_rule`: **TRUE** ($4,905 = 4,905 + 0$)
- `sessions_counted`: **2**

---

## 5. Artifacts and Provenance

- **Campaign Receipt:** `target/closure-campaign/campaign-receipt.json`
- **Session 1 Receipts:** `target/closure-campaign/session-1/live-shadow-receipt.json`, `shadow-journal.jsonl`
- **Session 2 Receipts:** `target/closure-campaign/session-2/live-shadow-receipt.json`, `shadow-journal.jsonl`
- **Campaign Coremod:** `target/rustcraft-campaign.jar` (`SHA-256: 4d474a05df53b82f9e77d89497ddc3cac3a556f630078e229052739d48ad817a`)
- **Canonical Profile:** `tools/live-capture/revelation-live-shadow-profile.json` (`SHA-256: 744f95798bd24271fbe0e0d01f89a0a4a4c189e7b7635585b61d398c41a388d6`)

---

## 6. Authority Guard Status

Throughout all phases of development, testing, proof verification, and campaign execution:
- `M4NativeStatePayload.tryEncode(chunk, filter)` returned `null` unconditionally (`FALLBACK_CAPTURE_UNSAFE`).
- `CaptureContract.productionAuthorityEligible()` returned `false`.
- Zero Rust-authored packet bytes reached client sockets; Java remained 100% production authoritative.

With all criteria formally achieved, the subsystem status is updated to **`READY_FOR_RUST_PACKET_AUTHORITY_REVIEW`**.
