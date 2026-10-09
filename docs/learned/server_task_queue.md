# Minecraft 1.12.2 / Forge Server Task Queue Architecture

## 1. Executive Summary
In Minecraft Java Edition 1.12.2 and Forge 14.23.5.2860, cross-thread work execution is unified through the `IThreadListener` interface and `MinecraftServer.futureTaskQueue`.

Because authoritative world state mutation (block changes, entity modification, inventory handling, and chunk registration) is strictly forbidden on background threads (such as Netty I/O threads, File I/O threads, and HTTP authentication workers), background processes must post tasks across thread boundaries into the main game loop.

This document details the exact queue structure, thread hand-off mechanisms, draining lifecycle, and mod-facing APIs.

---

## 2. Core Data Structure (`MinecraftServer.java`)

```java
protected final Queue<FutureTask<?>> futureTaskQueue = Queues.newArrayDeque();
```

### 2.1 Architectural Characteristics
- **Queue Implementation:** `java.util.ArrayDeque<FutureTask<?>>`.
- **Thread Safety Mechanism:** Coarse-grained intrinsic object monitor synchronization (`synchronized (this.futureTaskQueue)`).
- **Element Type:** `java.util.concurrent.FutureTask<?>` (specifically Google Guava's `ListenableFutureTask<V>`).
- **Capacity:** Unbounded dynamic array; grows dynamically as pending cross-thread tasks arrive.

---

## 3. Submission Mechanics (`callFromMainThread` / `addScheduledTask`)

`MinecraftServer` and `WorldServer` both implement `net.minecraft.util.IThreadListener`:

```java
@Override
public ListenableFuture<Object> addScheduledTask(Runnable runnable) {
    Validate.notNull(runnable);
    return this.callFromMainThread(Executors.callable(runnable));
}

public <V> ListenableFuture<V> callFromMainThread(Callable<V> callable) {
    Validate.notNull(callable);
    if (!this.isCallingFromMinecraftThread() && !this.isServerStopped()) {
        ListenableFutureTask<V> task = ListenableFutureTask.create(callable);
        synchronized (this.futureTaskQueue) {
            this.futureTaskQueue.add(task);
            return task;
        }
    } else {
        try {
            return Futures.immediateFuture(callable.call());
        } catch (Exception e) {
            return Futures.immediateFailedCheckedFuture(e);
        }
    }
}
```

### 3.1 Synchronous In-Thread Fast Path
If the caller is already executing on `Server thread` (`this.isCallingFromMinecraftThread() == true`):
- The callable is NOT queued.
- It executes immediately and synchronously in the caller's stack frame.
- A pre-completed `Futures.immediateFuture(...)` is returned.
- This prevents latency overhead and deadlock when code calls `addScheduledTask` without checking thread state.

### 3.2 Asynchronous Cross-Thread Queue Path
If the caller is executing on any other thread (e.g. `Netty Server IO #0`, `File IO Thread`, mod worker thread):
1. Wraps the `Callable` in a `ListenableFutureTask`.
2. Acquires `synchronized (this.futureTaskQueue)` lock.
3. Appends task to the end of the `ArrayDeque` ($O(1)$ amortized FIFO).
4. Returns the unresolved `ListenableFutureTask` to the caller.

---

## 4. Inbound Gameplay Packet Hand-Off (`PacketThreadUtil`)

The primary source of tasks entering `futureTaskQueue` is inbound Netty network packets.

```java
package net.minecraft.network;

import net.minecraft.util.IThreadListener;

public class PacketThreadUtil {
    public static <T extends INetHandler> void checkThreadAndEnqueue(final Packet<T> packet, final T handler, IThreadListener listener) throws ThreadQuickExitException {
        if (!listener.isCallingFromMinecraftThread()) {
            listener.addScheduledTask(new Runnable() {
                @Override
                public void run() {
                    packet.processPacket(handler);
                }
            });
            throw ThreadQuickExitException.INSTANCE;
        }
    }
}
```

### 4.1 Packet Processing Lifecycle
1. Client sends a packet over TCP (e.g. `CPacketPlayerDigging`, `CPacketPlayerTryUseItem`, `CPacketUseEntity`).
2. Netty I/O thread reads bytes from socket, decrypts, decompresses, frames, and decodes into a `Packet` POJO.
3. Netty pipeline calls `packet.processPacket(handler)`.
4. First line of `processPacket()` invokes `PacketThreadUtil.checkThreadAndEnqueue(this, handler, worldserver)`.
5. Since current thread is a Netty I/O worker, `listener.isCallingFromMinecraftThread()` returns `false`.
6. Enqueues a `Runnable` executing `packet.processPacket(handler)` onto `futureTaskQueue`.
7. Throws `ThreadQuickExitException.INSTANCE` (a shared singleton exception with suppressed stack trace) to instantly break out of the Netty channel pipeline.
8. The packet remains queued until the main server tick drains the queue.

---

## 5. Drain Mechanics & Timing (`MinecraftServer.updateTimeLightAndEntities`)

The task queue is drained at the beginning of `MinecraftServer.tick()` within the `jobs` profiler section:

```java
this.profiler.startSection("jobs");
synchronized (this.futureTaskQueue) {
    while (!this.futureTaskQueue.isEmpty()) {
        Util.runTask(this.futureTaskQueue.poll(), LOGGER);
    }
}
this.profiler.endSection();
```

### 5.1 Step-by-Step Drain Algorithm
1. Enters `jobs` profiler section.
2. Acquires `synchronized (this.futureTaskQueue)`.
3. In a loop:
   - Polls head of queue (`futureTaskQueue.poll()`).
   - Invokes `net.minecraft.util.Util.runTask(task, LOGGER)`.
4. Inside `Util.runTask`:
   ```java
   public static <V> V runTask(FutureTask<V> task, Logger logger) {
       try {
           task.run();
           return task.get();
       } catch (ExecutionException e) {
           logger.fatal("Error executing task", e);
       } catch (InterruptedException e) {
           logger.fatal("Error executing task", e);
       }
       return null;
   }
   ```
5. Releases monitor lock once queue is fully empty (`while (!this.futureTaskQueue.isEmpty())`).

### 5.2 Exception Isolation
- If a scheduled task throws an unhandled `Exception`, `FutureTask.run()` traps it internally.
- `task.get()` then re-throws it wrapped in `ExecutionException`.
- `Util.runTask` catches `ExecutionException`, logs `logger.fatal("Error executing task", e)`, and swallows the error.
- **Critical Invariant:** A task failure NEVER crashes the server tick loop. The queue continues draining subsequent tasks.

### 5.3 Recursive Scheduling Behavior
If task A executing on `Server thread` calls `this.addScheduledTask(taskB)`:
- Because the calling thread is `Server thread`, `isCallingFromMinecraftThread()` is `true`.
- Task B executes synchronously inside the execution of Task A.
- Task B is NOT re-enqueued.

If task A asynchronously spawns an off-thread worker that posts Task C back via `addScheduledTask`:
- Task C will acquire `synchronized (this.futureTaskQueue)` after Task A releases it, or will be processed in the next server tick's drain pass.

---

## 6. Modding & Forge Integration (`SimpleNetworkWrapper`)

Forge explicitly mandates `IThreadListener.addScheduledTask` for all mod packet handling:

```java
// Forge SimpleNetworkWrapper recommendation:
IThreadListener mainThread = (WorldServer) ctx.getServerHandler().player.world;
mainThread.addScheduledTask(new Runnable() {
    @Override
    public void run() {
        // Safe to mutate blocks, capabilities, inventories, entities
    }
});
```
- Modders registering network channels via `NetworkRegistry.INSTANCE.newSimpleChannel("CHANNEL")` must delegate packet handling through this queue.
- Attempting to access world or inventory state directly inside `IMessageHandler.onMessage()` without `addScheduledTask()` results in race conditions with the active server tick.

---

## 7. Migration Invariants for Rust Runtime Architecture

1. **Lock-Free Queue (Research Candidate):**
   Vanilla Java protects `futureTaskQueue` with an intrinsic monitor lock (`synchronized`). Because multiple Netty worker threads concurrently append tasks while a single `Server thread` drains them, this resembles a **Multi-Producer Single-Consumer (MPSC)** pattern.
   - In Rust, this represents an unvalidated research candidate: evaluate high-throughput concurrent MPSC channels (such as `crossbeam_channel` or `flume`) against Java queue semantics, verifying task cancellation, future completion, and memory overhead before adoption.
2. **Deterministic Drain Point:**
   The drain point occurs immediately after `FMLCommonHandler.onPreServerTick()` and `ChunkIOExecutor.tick()`, and strictly BEFORE any world or dimension begins ticking. Mod packets that initiate chunk loading, teleportation, or block clicks must be processed before the world simulates physical changes for that tick.
3. **Thread Identity Requirement:**
   Any FFI bridging layer that executes scheduled tasks must ensure `isCallingFromMinecraftThread()` evaluates to `true` during task execution, or mod code will fail assertion checks.
