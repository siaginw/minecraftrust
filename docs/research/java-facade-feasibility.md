# Research: Feasibility of Native-Backed Java Façades

> H3 claim audit: the 18.50 ns JNI value below is **ASSUMED**, printed as a constant by `ForgeBenchmarks.java`; that benchmark performs no JNI call. The array/Unsafe timings describe its narrow Java loop, not a safe native ownership boundary. The toy subclass/reference tests do not establish arbitrary mod compatibility, and the table's PERFECT/100% labels are unsupported. See [claim audit](../engineering/performance-claim-audit.md).

## 1. Research Question
Can a Java object retain its standard Java API and class signature while delegating its underlying mutable state to off-heap native memory (e.g. a Rust-allocated buffer address `long nativeAddress`), and can such a façade survive the Forge CoreMod and reflection ecosystem?

---

## 2. Empirical Findings from `CoreModProbe.java`

Using `tools/coremod-probe/src/CoreModProbe.java` (Java 8 HotSpot + ASM 5.2):

| Test Scenario | Result | Measured Behavior | Compatibility Implication |
|---|---|---|---|
| **Off-heap state round-trip** | **PASS** | `Unsafe.getShort(addr + offset)` reads and writes off-heap memory cleanly | Correctness verified |
| **Reflection access to handle** | **PASS** | `getDeclaredField("nativeAddress")` correctly reads handle value | Clean reflection compatibility |
| **Subclassing & Polymorphism** | **PASS** | Virtual call table dispatch functions identically to standard Java classes | Mod inheritance preserved |
| **Reference Identity (`==`)** | **PASS** | `ref1 == ref2` evaluates to `true` when canonical instance is retained | Zero reference identity breaks |
| **ASM Method Interception** | **PASS** | CoreMod injecting method head bytecode into `setBlock()` executes cleanly | Method hooks preserved |
| **Direct Field Access (`GETFIELD`)** | **FATAL HAZARD** | Bytecode targeting original Java array fields fails with `NoSuchFieldError` if fields are deleted | Incompatible with direct field transformers |

---

## 3. The Direct Field Problem & Dual-Memory Strategies

### 3.1 The Problem
Many Tier 2 (AT) and Tier 3 (CoreMod) mods do not call getter methods like `storage.get(x, y, z)`. They execute direct `GETFIELD` instructions on `storage.data` or `storageArrays[i]`. If the Java object only holds a `long nativeAddress`, bytecode attempting to access the old array field will throw `NoSuchFieldError` or `NullPointerException`.

### 3.2 Strategy Comparison

| Architecture Pattern | Description | Memory Cost | In-JVM Latency | CoreMod / AT Compatibility | Recommended? |
|---|---|---|---|---|---|
| **A. Pure Native Façade** | Only `long nativeAddress`; all methods delegate via `Unsafe` or JNI | Minimal (16 bytes) | 1.36 ns | **LOW**: Fails all direct field accesses | NO |
| **B. Dual Shadow Storage** | Both Java array and native buffer kept in sync on write | 2x Memory (16 KiB/sec) | 3.84 ns + sync cost | **PERFECT**: 100% compatible | Only if needed for AT mods |
| **C. ASM Field Redirector** | Custom ClassTransformer rewrites external `GETFIELD` into getter methods | Minimal (16 bytes) | 1.36 ns + method call | **HIGH**: Transparent to mods | **SELECTED P1 CANDIDATE** |

---

## 4. Benchmark Performance Delta

From `ForgeBenchmarks.java`:
- Direct Java Array Field Read: **3.84 ns/op**
- Coarse Native Memory Handle Read (`Unsafe.getShort`): **1.36 ns/op**
- Assumed JNI Call Crossing Penalty: **18.50 ns/op** (printed constant; not measured)

### Verdict
This loop reported a lower latency for its Unsafe read. It does not isolate bounds checking as the cause or establish general equivalence, complete-path JNI cost, lifetime safety, or arbitrary mod compatibility. Direct-field compatibility and native object lifetime need separate proofs and measurements.
