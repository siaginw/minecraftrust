# Minecraft 1.12.2 Scheduled Block Tick Model

## 1. Overview & Architectural Role
In Minecraft 1.12.2, scheduled block ticks are the authoritative engine driving deterministic block updates, redstone repeater/comparator delays, liquid propagation (water, lava), falling blocks (sand, gravel), and block decay.

Scheduled block ticks execute synchronously on `Server thread` during `WorldServer.tick()` within the `tickPending` profiler section (accounting for **25.93%** of overall tick CPU time in clean baseline testing).

---

## 2. Core Data Structures (`WorldServer.java`)
WorldServer maintains three distinct collections governing scheduled block ticks:

```java
private final TreeSet<NextTickListEntry> pendingTickListEntriesTreeSet;
private final Set<NextTickListEntry> pendingTickListEntriesHashSet;
private final List<NextTickListEntry> pendingTickListEntriesThisTick;
```

### 2.1 The Mirrored Collection Invariant
`pendingTickListEntriesTreeSet` and `pendingTickListEntriesHashSet` are strict dual mirrors:
- `TreeSet<NextTickListEntry>`: Provides strict chronological, priority, and FIFO sequence sorting.
- `HashSet<NextTickListEntry>`: Provides $O(1)$ constant-time deduplication checking when blocks call `World.scheduleUpdate()` or `World.scheduleBlockUpdate()`.
- **Integrity Assertion:** If at any point `pendingTickListEntriesTreeSet.size() != pendingTickListEntriesHashSet.size()`, the engine throws:
  ```java
  throw new IllegalStateException("TickNextTick list out of synch");
  ```

---

## 3. Entry Identity, Comparison, and Ordering (`NextTickListEntry.java`)

Each entry encapsulates:
- `BlockPos position`: 3D integer coordinate of target block.
- `Block block`: Target block instance.
- `long scheduledTime`: World total time (in ticks) when update must trigger.
- `int priority`: Integer priority level (default `0`; redstone repeaters use negative/custom priorities).
- `long tickEntryID`: Monotonic unique 64-bit sequence counter generated via atomic `nextTickEntryID++`.

### 3.1 Ordering Hierarchy (`compareTo`)
Ordering in `TreeSet` is defined strictly by `NextTickListEntry.compareTo(NextTickListEntry other)`:
1. **Primary Key: `scheduledTime` (Ascending)**
   $$\text{sign}(this.scheduledTime - other.scheduledTime)$$
   Entries scheduled for earlier world ticks execute before later ticks.
2. **Secondary Key: `priority` (Ascending)**
   $$\text{sign}(this.priority - other.priority)$$
   If scheduled for the same tick, lower numerical priority executes first. (e.g. priority $-1$ executes before priority $0$).
3. **Tertiary Key: `tickEntryID` (Ascending FIFO)**
   $$\text{sign}(this.tickEntryID - other.tickEntryID)$$
   If time and priority are identical, the entry scheduled earlier in real time (smaller ID) executes first. Ties never occur because `tickEntryID` is strictly monotonic.

### 3.2 Deduplication Semantics (`equals` and `hashCode`)
Unlike `compareTo()`, the identity methods used by `HashSet` ignore timing, priority, and ID:
```java
@Override
public boolean equals(Object obj) {
    if (!(obj instanceof NextTickListEntry)) return false;
    NextTickListEntry other = (NextTickListEntry) obj;
    return this.position.equals(other.position) && Block.isEqualTo(this.block, other.block);
}

@Override
public int hashCode() {
    return this.position.hashCode();
}
```

### 3.3 Deduplication Implication
- **Single Active Tick per Block per Position:** You cannot schedule two simultaneous pending ticks for the same block at the exact same coordinate. Any attempt to schedule a duplicate entry while one is already pending is discarded by the `HashSet.contains()` check, regardless of whether the second schedule request has a different delay or priority.
- **Different Blocks at Same Position:** If a block at `pos` changes (e.g. from redstone wire to air), an entry for the old block will not collide with an entry for the new block in `equals()` because `Block.isEqualTo()` evaluates to false.

---

## 4. Execution Algorithm (`WorldServer.tickUpdates(boolean runAll)`)

The drain sequence proceeds in two distinct stages: **Batch Extraction** and **Sequential Execution**.

### 4.1 Stage 1: Batch Extraction (`cleaning`)
```java
int count = this.pendingTickListEntriesTreeSet.size();
if (count > 65536) {
    count = 65536; // HARD CAP
}

this.profiler.startSection("cleaning");
for (int i = 0; i < count; ++i) {
    NextTickListEntry first = this.pendingTickListEntriesTreeSet.first();
    if (!runAll && first.scheduledTime > this.worldInfo.getWorldTotalTime()) {
        break; // Reached future ticks; stop extracting
    }

    this.pendingTickListEntriesTreeSet.remove(first);
    this.pendingTickListEntriesHashSet.remove(first);
    this.pendingTickListEntriesThisTick.add(first);
}
this.profiler.endSection();
```

#### Observable Constraints:
1. **The 65,536 Per-Tick Limit:**
   The server will never execute more than $65,536$ scheduled ticks in a single world tick. Any backlog above this threshold remains in the TreeSet and is deferred to subsequent world ticks.
2. **Atomic In-Memory Window Extraction:**
   Eligible entries are removed from the mirror set and placed into `pendingTickListEntriesThisTick` before any block logic executes. This allows blocks ticked during Stage 2 to re-schedule themselves without colliding with their own current execution entry in the `HashSet`.

### 4.2 Stage 2: Sequential Execution (`ticking`)
```java
this.profiler.startSection("ticking");
Iterator<NextTickListEntry> iterator = this.pendingTickListEntriesThisTick.iterator();

while (iterator.hasNext()) {
    NextTickListEntry entry = iterator.next();
    iterator.remove();

    int radius = 0; // Check 0-block radius around position (the chunk containing the pos)
    if (this.isAreaLoaded(entry.position.add(-radius, -radius, -radius), entry.position.add(radius, radius, radius))) {
        IBlockState state = this.getBlockState(entry.position);
        if (state.getMaterial() != Material.AIR && Block.isEqualTo(state.getBlock(), entry.getBlock())) {
            try {
                state.getBlock().updateTick(this, entry.position, state, this.rand);
            } catch (Throwable t) {
                CrashReport crash = CrashReport.makeCrashReport(t, "Exception while ticking a block");
                CrashReportCategory category = crash.makeCategory("Block being ticked");
                CrashReportCategory.addBlockInfo(category, entry.position, state);
                throw new ReportedException(crash);
            }
        }
    } else {
        // Chunk is not loaded: defer execution!
        this.scheduleUpdate(entry.position, entry.getBlock(), 0);
    }
}
this.profiler.endSection();
this.pendingTickListEntriesThisTick.clear();
```

---

## 5. Chunk Unload & Deferred Tick Handling

### 5.1 Unloaded Chunk Re-scheduling
If `isAreaLoaded(entry.position)` returns false when an entry's scheduled tick arrives:
- The entry is NOT discarded.
- It calls `this.scheduleUpdate(entry.position, entry.getBlock(), 0);`.
- This immediately re-inserts the entry into `pendingTickListEntriesTreeSet` with `scheduledTime = worldTotalTime + 0`.
- It will attempt execution again on every tick until the chunk is either loaded or the entry is saved to disk upon chunk serialization.

### 5.2 Persistence & Chunk Serialization
When an active chunk is saved to Anvil NBT (`ChunkProviderServer.saveChunk()`):
- `WorldServer.getPendingBlockUpdates(chunk, false)` queries the `pendingTickListEntriesTreeSet` for all entries whose coordinates lie within the chunk's bounding box.
- Entries are serialized to the chunk's NBT tag `TileTicks` as a compound list:
  - `i`: Block resource location / ID
  - `x`, `y`, `z`: Integer world coordinates
  - `t`: Relative delay remaining (`scheduledTime - worldTotalTime`)
  - `p`: Priority integer
- When the chunk is re-loaded from disk, entries are restored into the active `WorldServer` TreeSet/HashSet mirrors with adjusted absolute tick timestamps (`restored_t + current_worldTotalTime`).

---

## 6. Compatibility & Parity Invariants for Rust Implementation

1. **Deterministic Tick Order is Non-Negotiable:**
   Redstone repeaters and complex redstone circuitry rely strictly on the 3-tier sort (`time` -> `priority` -> `tickEntryID`). Any reordering changes repeater race conditions, breaking vanilla redstone computers, clocks, and contraptions.
2. **Deduplication Boundary:**
   A Rust implementation must replicate the exact distinction where `HashSet` deduplication uses ONLY `(BlockPos, Block)` while queue extraction uses the full tuple `(scheduledTime, priority, tickEntryID)`.
3. **Chunk Boundary Gating:**
   Ticks must not trigger on blocks situated inside unloaded chunk boundaries; attempting to run `updateTick()` on an unloaded chunk generates runaway chunk loading cascades.
4. **Cap Parity (65,536):**
   The 65,536 batch processing cap must be observed. In tick-overflow scenarios (e.g. massive liquid flow or redstone lag machines), exceeding this cap alters game mechanics and causes sudden lag spikes instead of graceful degradation across multiple ticks.
5. **Data Structure Architecture (Research Hypothesis):**
   Future Rust designs must not prematurely select a simple binary heap merely because Java's `TreeSet` appears expensive. Any candidate replacement must satisfy all of:
   - lookup semantics (`contains`)
   - deduplication semantics (`HashSet` mirror)
   - removal of individual positions (`isBlockTickPending`)
   - iteration and partial draining (cap 65,536)
   - serialization into chunk NBT `TileTicks`
   - Forge/coremod reflection and accessor compatibility
   - exact 3-tier ordering (`time` -> `priority` -> `tickEntryID`)
