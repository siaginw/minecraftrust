# Research: Feasibility of Native-Backed Java Façades

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
- Simulated JNI Call Crossing Penalty: **18.50 ns/op**

### Verdict
Reading off-heap native memory directly from Java via `Unsafe` is **faster or equivalent** to Java array reads due to eliminating JVM array bounds checking. The danger is not JVM memory read performance; the danger is bytecode incompatibility with mods expecting standard Java fields.
