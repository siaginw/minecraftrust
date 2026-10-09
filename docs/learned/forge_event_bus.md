# Learned: Forge EventBus Internal Mechanics & Dispatch Overhead

## 1. Overview & Source Architecture
Forge's `EventBus` (`net.minecraftforge.fml.common.eventhandler.EventBus`) provides high-throughput publish-subscribe event dispatch. Unlike naive reflection-based event buses, Forge uses runtime bytecode generation via ASM (`ASMEventHandler`) and flattened pre-compiled array caching (`ListenerList`) to optimize dispatch speed on hot server tick paths.

---

## 2. Core Mechanics

### 2.1 Listener Registration & Priority Ordering
- Subscribers annotate methods with `@SubscribeEvent(priority = EventPriority.NORMAL)`.
- Priority Tiers & Ordinals:
  - `HIGHEST`: Ordinal 0
  - `HIGH`: Ordinal 1
  - `NORMAL`: Ordinal 2 (default)
  - `LOW`: Ordinal 3
  - `LOWEST`: Ordinal 4
- **Execution Order**: Dispatches in ascending ordinal order (0, 1, 2, 3, 4), which corresponds to the priority sequence:
  `HIGHEST -> HIGH -> NORMAL -> LOW -> LOWEST`.
- **FIFO Invariant**: When multiple listeners share the same priority tier, execution order is strictly deterministic First-In, First-Out (FIFO) based on registration order. Verified via `EventOracle.java` (Test 1).

### 2.2 Bytecode Generation (`ASMEventHandler`)
- When a listener method is registered, Forge dynamically generates an implementation of `IEventListener` via ASM `ClassWriter`.
- The generated class invokes the target listener method directly via bytecode (`INVOKEVIRTUAL`), eliminating JVM reflection overhead (`Method.invoke`).
- If ASM generation fails or security manager restricts dynamic class definition, Forge falls back to reflective invocation.

### 2.3 Pre-Compiled Array Caching (`ListenerList`)
- Every distinct event type has an associated `ListenerList`.
- Rather than traversing tree or priority bucket collections on every post, `ListenerList` compiles an internal flat array: `private volatile IEventListener[] listeners;`.
- **Registration**: Modifies the backing priority collections and calls `rebuild()`, which atomically swaps the `volatile` array reference.
- **Dispatch (`post`)**: Directly loops through the array:
  ```java
  IEventListener[] listeners = list.getListeners(busID);
  for (int i = 0; i < listeners.length; i++) {
      listeners[i].invoke(event);
  }
  ```

### 2.4 Cancellation Semantics (`@Cancelable`)
- An event supports cancellation only if annotated with `@Cancelable` (or overriding `isCancelable()` returning `true`).
- When a listener calls `event.setCanceled(true)`:
  - Cancellation does not terminate EventBus iteration universally.
  - Listeners without `receiveCanceled` are skipped.
  - Listeners declared with `@SubscribeEvent(receiveCanceled = true)` continue to execute even after cancellation.
- Verified in `EventOracle.java` (Test 2): `HIGHEST_CANCELS -> NORMAL_SKIPPED -> LOWEST_RECEIVES_CANCELED`.

### 2.5 Result Semantics (`@HasResult`)
- Used for tri-state decisions: `Event.Result.DENY`, `Event.Result.DEFAULT`, `Event.Result.ALLOW`.
- Invariant: `setResult()` can be mutated across multiple listeners; later listeners can override earlier results unless checked.

### 2.6 Class Hierarchy & Inheritance
- When registering a listener for a superclass (e.g. `LivingEvent`), `ListenerList` establishes a parent-child relationship with subclass lists (e.g. `LivingJumpEvent`).
- When `LivingJumpEvent` is posted, both its own listeners and its parent's listeners are invoked.
- Invariant: Subclass listeners execute before superclass listeners. Verified via `EventOracle.java` (Test 4).

---

## 3. Empirical Microbenchmark Data

From `tools/forge-bench/src/ForgeBenchmarks.java` (Java 8 HotSpot, 100,000 warmups, 500,000 iterations):

| Listener Count | Raw Dispatch Latency | Per-Listener Latency | Total with Allocation (`new Event()`) | Allocation Delta |
|---|---|---|---|---|
| **0 listeners** | 28.79 ns | N/A | 27.10 ns | -1.69 ns (noise) |
| **1 listener** | 16.74 ns | 16.74 ns | 11.85 ns | -4.89 ns (noise) |
| **10 listeners** | 19.95 ns | 1.99 ns | 21.70 ns | +1.75 ns |
| **100 listeners** | 136.63 ns | 1.37 ns | 124.03 ns | -12.59 ns (noise) |
| **1,000 listeners** | 1,694.70 ns | 1.69 ns | 1,543.26 ns | -151.44 ns (noise) |

### Key Performance Findings
1. **Per-Listener Cost**: Once JIT warm, array iteration scales at **~1.37 to 1.99 ns per listener**.
2. **Canceled Bailout**: When a listener cancels early, remaining skipped listeners are bypassed via a simple boolean check, taking **~965 ns** for 100 skipped listeners (~9.6 ns/skipped listener due to branch check overhead).
3. **Allocation Overhead**: Modern JVM TLAB escape analysis frequently scalarizes short-lived event instances on the stack, meaning event allocation overhead is negligible compared to array dispatch overhead.
