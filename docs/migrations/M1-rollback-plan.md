# M1 Rollback & Failure Containment Plan

## 1. Failure Containment Philosophy
In accordance with Core Principle 11: *Performance regressions trigger root-cause analysis and attempted correction before rollback; failed designs must be preserved and documented*.

The system architecture enforces containment at three independent defensive levels:
1. **Level 1: Dynamic In-Process Fallback** (Micro-level, per-packet).
2. **Level 2: Runtime Feature Flag Disable** (Macro-level, per-server).
3. **Level 3: Full Codebase Rollback** (Repository-level).

---

## 2. Multi-Tier Rollback Levels

```
+-----------------------------------------------------------------------------+
|                                FAILURE OCCURS                               |
+-----------------------------------------------------------------------------+
                                       |
                   Is failure transient or single packet?
                                       |
              +------------------------+------------------------+
              | YES                                             | NO
              v                                                 v
+-----------------------------+               +-------------------------------+
|  LEVEL 1: IN-PROCESS FALLBACK|               | Persistent failure / Crash?   |
|  - Native returns code < 0  |               +-------------------------------+
|  - JNI catches panic        |                                 |
|  - Java constructor runs    |               +-----------------+-------------+
|  - 0 ms downtime, client OK |               | Configurable?   | Hard bug?   |
+-----------------------------+               v                 v
                               +-----------------------------+ +---------------+
                               | LEVEL 2: PROPERTY DISABLE   | | LEVEL 3: GIT  |
                               | - Set native_packet=OFF     | |   REVERT      |
                               | - Immediate Java execution  | | - Revert PR   |
                               | - Requires no binary patch  | | - Archive post|
                               +-----------------------------+ +---------------+
```

### Level 1: Dynamic In-Process Fallback (Automated)
- **Mechanism**: Every invocation of `NativeChunkPacket.encodeSections()` checks the returned `jint`.
- **Trigger**: Any return value $\le 0$ (error code or caught panic).
- **Execution**:
  ```java
  int bytesWritten = NativeChunkPacket.encodeSections(...);
  if (bytesWritten <= 0) {
      metrics.nativeFallbackCount.increment();
      // Instantly call authoritative reference implementation
      return extractChunkDataJava(chunk, primaryBitMask);
  }
  ```
- **Blast Radius**: Zero client disconnection; zero server crash.

### Level 2: Runtime Property Disable
- **Mechanism**: Dynamic configuration flag `-Dminecraftrust.native_chunk_packet=OFF` or runtime command `/rustcraft toggle native_chunk_packet off`.
- **Trigger**: Sustained fallback rate (>0.1% of packets) or shadow divergence detected.
- **Execution**: Completely bypasses JNI invocation; 100% of packets route through vanilla Java constructor.

### Level 3: Complete Code Rollback
- **Mechanism**: Git revert of the migration commit.
- **Trigger**: Memory leak in native allocator or unrecoverable JVM crash.
- **Verification**: Clean build passes all legacy regression tests (`cargo test`, `run_forge_oracles.py`).

---

## 3. Rollback Triggers & Thresholds

| Condition | Threshold | Action Level |
| :--- | :--- | :--- |
| **Caught Rust Panic** | Single occurrence | Level 1 Fallback + Log ERROR stack trace |
| **Panic Rate** | $> 5$ panics per 10,000 packets | Level 2 Property Disable |
| **Shadow Mode Byte Divergence** | Single mismatch | Level 2 Property Disable + Post-Mortem |
| **Client DecoderException** | Single occurrence | Level 2 Property Disable immediately |
| **Off-Heap Memory Leak** | $> 100\text{ MB}$ uncollected native memory | Level 2 Disable -> Level 3 Code Rollback |
| **Performance Regression** | Any measurable increase in compute MSPT | Level 2 Disable |

---

## 4. Post-Mortem & Knowledge Preservation
Whenever a rollback or fallback is triggered:
1. Capture state: Save JFR recording, thread dump, packet hex dump, and native log.
2. Record evidence in `docs/learned/failures/m1_failure_<timestamp>.md`.
3. Explain the root cause: memory alignment, palette edge case, or ASM interaction.
4. Update `machine/candidate-seam-scores.yaml` and regression catalog.
