# Serverbound PLAY Packet Inventory & Thread Classification (P0-5)

## Scope
Authoritative audit of all 33 serverbound packets in the PLAY protocol state (Protocol 340, Minecraft 1.12.2 / Forge 14.23.5.2860). Evidence: direct inspection of `net.minecraft.network.NetHandlerPlayServer`, `PacketThreadUtil.java`, and runtime packet registry reflection.

## Thread Classification Summary
- **31 / 33 packets (93.9%)**: `SERVER_THREAD` (enqueued to `MinecraftServer.futureTaskQueue` via `PacketThreadUtil.checkThreadAndEnqueue`).
- **2 / 33 packets (6.1%)**: `NETTY_IO` (executed directly on the Netty worker thread).
  - `CPacketKeepAlive` (ID `0x0B`)
  - `CPacketResourcePackStatus` (ID `0x1B`)

---

## Complete Packet Inventory Table

| ID | Class | Handler Method | `checkThreadAndEnqueue`? | Execution Thread | Mutated State | Invariant / Threading Notes |
|:---|:---|:---|:---:|:---:|:---|:---|
| `0x00` | `CPacketConfirmTeleport` | `processConfirmTeleport` | YES | `SERVER_THREAD` | Player position confirmation ID | Cancels teleport timeout |
| `0x01` | `CPacketTabComplete` | `processTabComplete` | YES | `SERVER_THREAD` | Sends `SPacketTabComplete` | Triggers command completion queries |
| `0x02` | `CPacketChatMessage` | `processChatMessage` | YES | `SERVER_THREAD` | Chat events / Command execution | Modifies world/entity if command |
| `0x03` | `CPacketClientStatus` | `processClientStatus` | YES | `SERVER_THREAD` | Respawn / stat requests | Triggers player respawn |
| `0x04` | `CPacketClientSettings` | `processClientSettings` | YES | `SERVER_THREAD` | Player render/hand/chat settings | Mutates `EntityPlayerMP.skinModelParts` |
| `0x05` | `CPacketConfirmTransaction`| `processConfirmTransaction` | YES | `SERVER_THREAD` | Container transaction ack | Syncs inventory window transactions |
| `0x06` | `CPacketEnchantItem` | `processEnchantItem` | YES | `SERVER_THREAD` | Container / enchantment | Mutates container inventory |
| `0x07` | `CPacketClickWindow` | `processClickWindow` | YES | `SERVER_THREAD` | Inventory window / ItemStack | Heavy inventory mutation |
| `0x08` | `CPacketCloseWindow` | `processCloseWindow` | YES | `SERVER_THREAD` | Open container | Closes active container, drops items |
| `0x09` | `CPacketCustomPayload` | `processCustomPayload` | YES | `SERVER_THREAD` | Mod-registered channel dispatch | Enqueues mod channel handler |
| `0x0A` | `CPacketUseEntity` | `processUseEntity` | YES | `SERVER_THREAD` | Entity interaction / attack | Attacks entity or opens entity GUI |
| `0x0B` | `CPacketKeepAlive` | `processKeepAlive` | **NO** | **`NETTY_IO`** | `player.ping`, `pendingKeepAlive` | **Runs directly on IO thread** (see deep analysis below) |
| `0x0C` | `CPacketPlayer` | `processPlayer` | YES | `SERVER_THREAD` | On-ground state | Enqueues movement validation |
| `0x0D` | `CPacketPlayer$Position` | `processPlayer` | YES | `SERVER_THREAD` | Player coordinates (X, Y, Z) | Full movement and collision checks |
| `0x0E` | `CPacketPlayer$PositionRotation` | `processPlayer` | YES | `SERVER_THREAD` | Pos (X, Y, Z) + Yaw/Pitch | Movement + look vector calculation |
| `0x0F` | `CPacketPlayer$Rotation` | `processPlayer` | YES | `SERVER_THREAD` | Yaw / Pitch | Updates look vector |
| `0x10` | `CPacketVehicleMove` | `processVehicleMove` | YES | `SERVER_THREAD` | Ridden entity position | Mutates vehicle entity position |
| `0x11` | `CPacketSteerBoat` | `processSteerBoat` | YES | `SERVER_THREAD` | Boat paddle status | Mutates boat entity |
| `0x12` | `CPacketPlaceRecipe` | `func_194308_a` | YES | `SERVER_THREAD` | Crafting matrix inventory | Auto-crafting recipe population |
| `0x13` | `CPacketPlayerAbilities` | `processPlayerAbilities` | YES | `SERVER_THREAD` | `capabilities.isFlying` | Flight state mutation |
| `0x14` | `CPacketPlayerDigging` | `processPlayerDigging` | YES | `SERVER_THREAD` | Block break / drop item / swap | World block break, drops entities |
| `0x15` | `CPacketEntityAction` | `processEntityAction` | YES | `SERVER_THREAD` | Sneak/sprint/jump/horse state | Entity state flags |
| `0x16` | `CPacketInput` | `processInput` | YES | `SERVER_THREAD` | Movement inputs (strafe/forward) | Steers ridden entities |
| `0x17` | `CPacketRecipeInfo` | `handleRecipeBookUpdate` | YES | `SERVER_THREAD` | Recipe book seen/filter state | Recipe book persistence |
| `0x18` | `CPacketResourcePackStatus` | `handleResourcePackStatus` | **NO** | **`NETTY_IO`** | None | Empty body on DedicatedServer (NO-OP) |
| `0x19` | `CPacketSeenAdvancements` | `handleSeenAdvancements` | YES | `SERVER_THREAD` | Advancement tab selection | Advancement progress manager |
| `0x1A` | `CPacketHeldItemChange` | `processHeldItemChange` | YES | `SERVER_THREAD` | `inventory.currentItem` | Changes active hotbar slot |
| `0x1B` | `CPacketCreativeInventoryAction` | `processCreativeInventoryAction` | YES | `SERVER_THREAD` | Creative item placement | Direct itemstack mutation |
| `0x1C` | `CPacketUpdateSign` | `processUpdateSign` | YES | `SERVER_THREAD` | TileEntitySign text | Mutates TileEntity lines |
| `0x1D` | `CPacketAnimation` | `handleAnimation` | YES | `SERVER_THREAD` | Hand swing animation | Broadcasts animation packet |
| `0x1E` | `CPacketSpectate` | `handleSpectate` | YES | `SERVER_THREAD` | Spectated entity target | Switches spectated camera entity |
| `0x1F` | `CPacketPlayerTryUseItemOnBlock` | `processPlayerTryUseItemOnBlock` | YES | `SERVER_THREAD` | Block placement / activation | World block state mutation |
| `0x20` | `CPacketPlayerTryUseItem` | `processPlayerTryUseItem` | YES | `SERVER_THREAD` | Item activation (eat/bow/throw) | Uses item, consumes stack |

---

## Deep Dive: CPacketKeepAlive Concurrency Semantics

### Source Inspection (`NetHandlerPlayServer.java` L1141-1149)
```java
public void processKeepAlive(CPacketKeepAlive p_processKeepAlive_1_) {
    if (this.field_194403_g && p_processKeepAlive_1_.getKey() == this.field_194404_h) {
        int i = (int)(this.currentTimeMillis() - this.field_194402_f);
        this.player.ping = (this.player.ping * 3 + i) / 4;
        this.field_194403_g = false;
    } else if (!this.player.getName().equals(this.server.getServerOwner())) {
        this.disconnect(new TextComponentTranslation("disconnect.timeout"));
    }
}
```

### Mutated Fields
1. `this.player.ping` (`EntityPlayerMP.ping`, an `int`).
2. `this.field_194403_g` (`NetHandlerPlayServer.pendingKeepAlive`, a `boolean`).

### Synchronization & Concurrency Hazard
- **Neither field has `volatile` or `synchronized` modifiers**.
- `this.field_194403_g` is set to `true` on the **Server thread** during `NetHandlerPlayServer.update()` (every 15 seconds / 300 ticks) when sending `SPacketKeepAlive`.
- `this.field_194403_g` is reset to `false` on the **Netty IO thread** when `CPacketKeepAlive` arrives.
- `this.player.ping` is written on the **Netty IO thread** and read on the **Server thread** during tab-list updates (`SPacketPlayerListItem`).
- **Conclusion**: This is an observable data race in vanilla Minecraft 1.12.2. While the 32-bit `int` and boolean writes are atomic on x86/x64 JVMs, visibility is not guaranteed by Java Memory Model fences. A Rust transport or reimplemented net handler must preserve this non-blocking off-thread behavior without assuming it applies to any other packet.
