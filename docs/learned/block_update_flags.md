# Learned: Block Update Flags Semantics

## 1. Flag Bitfield Definitions
In `World.setBlockState(BlockPos pos, IBlockState newState, int flags)`:
- **Bit 1 (`0x01`)**: Cause neighbor block updates.
  - Calls `World.notifyNeighborsRespectDebug(pos, oldBlock, true)`.
  - Dispatches `neighborChanged` to the 6 adjacent block positions (East, West, Up, Down, South, North).
  - Triggers redstone wire updates, observer triggers, and gravity block falls.
- **Bit 2 (`0x02`)**: Send block update packet to tracking clients.
  - Calls `World.notifyBlockUpdate(pos, oldState, newState, flags)`.
  - Queues an `SPacketBlockChange` packet to all players watching the chunk via `PlayerChunkMapEntry`.
- **Bit 4 (`0x04`)**: Prevent client rerender (used client-side).
- **Bit 8 (`0x08`)**: Force main thread rerender (used client-side).
- **Bit 16 (`0x10`)**: Prevent observer updates.
  - If bit 16 is NOT set: `(!this.isRemote && (flags & 16) == 0) -> updateObservingBlocksAt(pos, oldBlock)`.
  - Suppresses observer triggers during world generation or multi-block atomic placements.

## 2. Common Flag Combinations in Game Logic
- `flags = 3` (`1 | 2`): Standard gameplay placement (notify neighbors AND send client network packet).
- `flags = 2`: Visual update only (send client packet, do NOT trigger neighbor reactions). Used for chest opening, furnace lighting, command block metadata sync.
- `flags = 0` or `flags = 4`: Silent internal update. Used during world generation, chunk decoration, or bulk structure template placements to avoid cascading physics and network bloat.
