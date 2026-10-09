# Chunk Capabilities & Custom Mod Data Compatibility

## 1. Subsystem Overview
Forge 1.12.2 provides the Capability system (`net.minecraftforge.common.capabilities`) to allow mods to attach arbitrary data and behavior to game objects (`ItemStack`, `Entity`, `TileEntity`, `World`, and `Chunk`) without subclassing or bytecode surgery.

## 2. Chunk Capability Lifecycle & Event Hooks
When a `Chunk` is instantiated:
1. Forge invokes `MinecraftForge.EVENT_BUS.post(new AttachCapabilitiesEvent<Chunk>(Chunk.class, chunk))`.
2. Any mod can attach capability providers (`ICapabilityProvider` or `ICapabilitySerializable`).
3. Examples of chunk capabilities in major 1.12.2 mods:
   - **Flux Networks / Energy Grids:** Attaching regional power grid graphs directly to chunks.
   - **Mekanism / Pollution Plus:** Attaching regional radiation or atmospheric pollution levels to chunk columns.
   - **Thaumcraft 6:** Attaching local chunk Vis and Flux aura values.

## 3. Serialization Contract (`ForgeCaps`)
In `AnvilChunkLoader.java.patch`:
- When writing a chunk to disk:
  ```java
  if (chunk.capabilities != null) {
      try {
          compound.setTag("ForgeCaps", chunk.capabilities.serializeNBT());
      } catch (Exception e) {
          FMLLog.log.error("A capability provider has thrown an exception trying to write to NBT...", e);
      }
  }
  ```
- When reading a chunk from disk:
  ```java
  if (compound.hasKey("ForgeCaps")) {
      chunk.capabilities.deserializeNBT(compound.getCompoundTag("ForgeCaps"));
  }
  ```

## 4. Preservation Invariants for Rust Migration
1. **Opaque Tag Roundtripping:**
   A future Rust chunk persistence engine does not need to understand what `"thaumcraft:aura"` or `"enderio:grid"` contains. It **must**, however, preserve unknown compounds and lists byte-for-byte and structure-for-structure.
2. **Event Dispatch Synchronization:**
   `AttachCapabilitiesEvent<Chunk>` must fire on the main `Server thread` immediately upon chunk instantiation, before any block modifications or capability queries occur.
3. **Fault Isolation:**
   If a single mod capability fails to serialize, Forge catches the exception and logs an error, allowing the rest of the chunk and other capabilities to persist rather than aborting the entire save.
