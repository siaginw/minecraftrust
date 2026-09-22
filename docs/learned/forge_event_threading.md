# Learned: Forge EventBus Threading Model & Concurrency Hazards

## 1. Threading Architecture
The Forge 1.12.2 `EventBus` has an asymmetric concurrency architecture:
- **Dispatch (`post`)**: Multi-thread safe for reading pre-compiled listener arrays.
- **Registration (`register` / `unregister`)**: Synchronized via internal monitor locks.
- **Listener State**: Mod listeners are generally NOT thread-safe unless authored specifically for async events.

---

## 2. Event Dispatch Contexts

### 2.1 Server Main Thread Events (95%+ of all dispatches)
- **Examples**: `TickEvent.ServerTickEvent`, `TickEvent.WorldTickEvent`, `BlockEvent.BreakEvent`, `LivingEvent.LivingUpdateEvent`, `PlayerInteractEvent`.
- **Concurrency Contract**: Strictly single-threaded execution on Minecraft's `ServerThread`.
- **Mod Assumption**: Handlers assume full access to `World`, `TileEntity`, and `Entity` objects without taking locks.
- **Hazard**: If a native background thread fires a game event directly onto `MinecraftForge.EVENT_BUS`, mods will read and mutate world state off-thread, causing immediate race conditions or `ConcurrentModificationException`.

### 2.2 Netty IO Thread Events
- **Examples**: `FMLNetworkEvent.ServerCustomPacketEvent`, `NetworkCheckHandler`.
- **Concurrency Contract**: Dispatched on Netty worker threads (`Netty Epoll Server IO #X` or `Netty Server IO #X`).
- **Mod Assumption**: Well-behaved network handlers schedule tasks onto the main server thread via `IThreadListener.addScheduledTask()`. However, legacy or buggy mods may mutate game state directly on the Netty thread.

### 2.3 Async Chunk Generation Events
- **Examples**: `ChunkDataEvent.Load`, `PopulateChunkEvent`.
- **Concurrency Contract**: When async chunk generation mods (or Forge's async chunk loading) are enabled, chunk events may fire from background worker threads.

---

## 3. ListenerList Concurrency Semantics

### 3.1 Lock-Free Read Path
`ListenerList` exposes listener arrays through a volatile reference:
```java
private volatile IEventListener[] listeners;
```
When `post(Event e)` is called:
1. `list.getListeners(busID)` loads the current `volatile` reference into a local CPU register.
2. The dispatch loop iterates over this array without taking any lock.
3. Multiple threads can safely post events concurrently without lock contention on the listener list.

### 3.2 Array Rebuild Race Conditions
When a mod dynamically calls `register()` or `unregister()` during runtime:
1. The mutating thread locks `ListenerList.class` or the specific list instance.
2. A new `IEventListener[]` array is allocated, populated, and assigned to the `volatile` reference.
3. Concurrent posting threads running on other cores will either execute against the old array or the new array, both of which are immutable snapshots.
4. **Hazard**: Dynamically modifying listeners during tick loops causes transient allocations and GC pressure, though it is memory-safe.

---

## 4. Invariants for Native / Rust Integration
1. **Never Post Main-Thread Events from Native Background Threads**:
   - Any event originating from native code that touches world or player state must be queued and dispatched on the Java Server Thread.
2. **Event Modification Single-Ownership**:
   - Cancelable events mutate `isCanceled`. When posting an event, the event instance must belong to exactly one thread during its dispatch lifecycle.
