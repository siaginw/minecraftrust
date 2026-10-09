# Chunk NBT Schema & Binary Storage Contract

## 1. Top-Level Compound Structure
When serialized by `AnvilChunkLoader.saveChunk()`, every chunk produces a root `NBTTagCompound` containing three primary keys:

```
Root (TAG_Compound)
├── "DataVersion": TAG_Int (1343 for Minecraft 1.12.2)
├── "ForgeDataVersion": TAG_Int (1 for Forge 1.12.2)
└── "Level": TAG_Compound
```

## 2. The "Level" Compound Schema

| Key | Type | Size / Dimensions | Description |
| :--- | :--- | :--- | :--- |
| `xPos` | `TAG_Int` | 4 bytes | Chunk X coordinate in chunk units. |
| `zPos` | `TAG_Int` | 4 bytes | Chunk Z coordinate in chunk units. |
| `LastUpdate` | `TAG_Long` | 8 bytes | World tick timestamp (`world.getTotalWorldTime()`) when chunk was saved. |
| `InhabitedTime` | `TAG_Long` | 8 bytes | Cumulative player ticks spent in this chunk (scales local regional difficulty). |
| `TerrainPopulated` | `TAG_Byte` | 1 byte | `1` if terrain features/ores placed; `0` if raw base terrain only. |
| `LightPopulated` | `TAG_Byte` | 1 byte | `1` if block/sky light recalculation has been performed; `0` otherwise. |
| `HeightMap` | `TAG_Int_Array` | 256 ints (1024 bytes) | Array of lowest Y coordinate where sky light is 0 for each $(X, Z)$ column. |
| `Biomes` | `TAG_Byte_Array` | 256 bytes | Array of biome IDs ($0 \dots 255$) indexed by $X + Z \cdot 16$. |
| `Sections` | `TAG_List` | List of `TAG_Compound` | List of non-empty $16 \times 16 \times 16$ voxel sub-chunks ($Y = 0 \dots 15$). |
| `Entities` | `TAG_List` | List of `TAG_Compound` | Active non-player entities residing within chunk boundary. |
| `TileEntities` | `TAG_List` | List of `TAG_Compound` | TileEntities (furnaces, chests, mod machines) in this chunk. |
| `TileTicks` | `TAG_List` | List of `TAG_Compound` | Scheduled block updates awaiting execution. |
| `ForgeCaps` | `TAG_Compound` | Variable (optional) | Serialized Forge capability data attached to this chunk. |

## 3. Sub-Chunk "Sections" Format ($16 \times 16 \times 16$ Voxels)
Empty sections (e.g. pure air above terrain) are omitted from the `Sections` list. Each non-empty section contains:

```
Section (TAG_Compound)
├── "Y": TAG_Byte (Sub-chunk vertical index: 0..15 corresponding to y=0..255)
├── "Blocks": TAG_Byte_Array (4096 bytes: lower 8 bits of block ID)
├── "Data": TAG_Byte_Array (2048 bytes: 4-bit block metadata nibbles)
├── "Add": TAG_Byte_Array (2048 bytes: optional upper 4 bits for block IDs > 255)
├── "BlockLight": TAG_Byte_Array (2048 bytes: 4-bit block emission light nibbles)
└── "SkyLight": TAG_Byte_Array (2048 bytes: 4-bit skylight attenuation nibbles)
```

### Voxel Data Bitpacking Math:
- Voxel local coordinate index ($X \in [0..15], Y \in [0..15], Z \in [0..15]$):
  $$\text{voxelIndex} = Y \cdot 256 + Z \cdot 16 + X$$
- Lower 8 bits of block ID: $\text{Blocks}[\text{voxelIndex}] \ \& \ \text{0xFF}$.
- Upper 4 bits of block ID (if `"Add"` tag present):
  $$\text{addNibble} = (\text{voxelIndex} \ \& \ 1 == 0) \ ? \ (\text{Add}[\text{voxelIndex} / 2] \ \& \ \text{0x0F}) : ((\text{Add}[\text{voxelIndex} / 2] \gg 4) \ \& \ \text{0x0F})$$
  $$\text{Full Block ID} = \text{blockLower} \ | \ (\text{addNibble} \ll 8)$$
- 4-bit Block Metadata:
  $$\text{meta} = (\text{voxelIndex} \ \& \ 1 == 0) \ ? \ (\text{Data}[\text{voxelIndex} / 2] \ \& \ \text{0x0F}) : ((\text{Data}[\text{voxelIndex} / 2] \gg 4) \ \& \ \text{0x0F})$$

## 4. TileTicks Compound Schema
Scheduled block updates serialize the exact state of `NextTickListEntry`:
```
TileTick (TAG_Compound)
├── "i": TAG_String (Block registry resource location, e.g. "minecraft:water")
├── "x": TAG_Int (Block world coordinate X)
├── "y": TAG_Int (Block world coordinate Y)
├── "z": TAG_Int (Block world coordinate Z)
├── "t": TAG_Int (Remaining scheduled ticks relative to LastUpdate: scheduledTime - totalWorldTime)
└── "p": TAG_Int (Execution priority, lower executes first)
```

## 5. Forge Capabilities ("ForgeCaps")
Added by Forge in `AnvilChunkLoader.java.patch`:
- If chunk implements `ICapabilitySerializable`, its provider serializes to a compound under `Level.ForgeCaps`.
- Maps capability domain names (e.g. `"enderio:conduit_network"`, `"mekanism:radiation"`) to mod-defined compounds or byte arrays.
- Preserved across chunk deserialization, modification, and re-saving.
