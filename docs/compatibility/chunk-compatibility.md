# Chunk Subsystem Compatibility & Hazard Catalog

## 1. Hazard Catalog

### HAZARD-CHK-01: Cascading World Generation Lag
- **Severity:** CRITICAL
- **Mechanism:** During chunk population (`generator.populate` or mod `IWorldGenerator`), a feature placement algorithm accesses or places a block outside the current $2 \times 2$ chunk population window (i.e. $> X \cdot 16 + 23$ or $> Z \cdot 16 + 23$).
- **Impact:** Triggers synchronous `provideChunk()` for the neighbor, which immediately begins generating and populating that neighbor, causing exponential recursive chunk generation. A single player walking can generate hundreds of chunks in a single tick, causing server tick times to explode from 0.5 ms to >5,000 ms.
- **Migration Constraint:** Rust world generator and chunk management must enforce strict bounding checks and provide configurable cascade prevention (such as `fixVanillaCascading`).

### HAZARD-CHK-02: 255-Sector (1 MiB) Anvil Overflow Silent Data Loss
- **Severity:** CRITICAL
- **Mechanism:** Anvil format uses an 8-bit integer in the location table to represent sector count ($255 \times 4096 = 1,044,480$ bytes max).
- **Impact:** If a chunk in a heavy modpack contains thousands of tile entities, dense NBT item inventories, or complex capability data exceeding 1 MiB compressed, `RegionFile.write()` silently returns without writing. The server continues running, but the chunk is never saved. On restart, the chunk reverts or vanishes.
- **Migration Constraint:** Rust storage engine must detect sector overflow, emit high-visibility fatal warnings, and support non-destructive overflow fallback (such as external `.mcc` chunk overflow files used in modern Minecraft versions).

### HAZARD-CHK-03: Lack of fsync() and Crash Corruption
- **Severity:** HIGH
- **Mechanism:** Vanilla `RegionFile` uses `RandomAccessFile` and never invokes `fsync()`.
- **Impact:** System power outage or kernel crash causes uncommitted OS page cache blocks to vanish or write partially, corrupting Sector 0 (Location Table) or leaving truncated zlib streams that cause `ZipException` and force chunk regeneration on boot.
- **Migration Constraint:** Rust region engine should support configurable `fsync` cadences (e.g. fsync on autosave or flush) and atomic header updates via double-buffering or write-ahead logging.

### HAZARD-CHK-04: Concurrent Modification of Entities/TileEntities during Save
- **Severity:** HIGH
- **Mechanism:** Serializing chunk entities into NBT iterates `entityLists` and `chunkTileEntityMap`.
- **Impact:** If chunk NBT serialization is pushed to a background thread without acquiring a full lock or creating an immutable snapshot, concurrent entity movement or block placement throws `ConcurrentModificationException` and aborts the save.
- **Migration Constraint:** Rust migration must separate the snapshot phase (capturing coarse immutable chunk state on the server thread) from the encoding/compression/disk writing phase (executed on background threads).

### HAZARD-CHK-05: Mod ChunkDataEvent NBT Mutation
- **Severity:** HIGH
- **Mechanism:** Forge mods register handlers for `ChunkDataEvent.Save` and `ChunkDataEvent.Load`.
- **Impact:** Mods expect to receive the live `NBTTagCompound` to insert custom global keys into the chunk file (e.g. custom waypoints, dimensional rifts, chunk claiming metadata).
- **Migration Constraint:** Any hybrid Java/Rust bridge must pass the NBT compound through the Forge event bus before binary encoding, ensuring mod-injected tags are preserved.

### HAZARD-CHK-06: Asynchronous Event Bus Invocation Trap
- **Severity:** MEDIUM
- **Mechanism:** Offloading chunk loading to worker threads.
- **Impact:** If `ChunkEvent.Load` or `ChunkDataEvent.Load` were fired from a worker thread, thousands of mod event listeners would execute off the server thread, causing race conditions in mod singleton states.
- **Migration Constraint:** Events must strictly fire on the `Server thread` during the synchronous callback phase (`syncCallback()`).
