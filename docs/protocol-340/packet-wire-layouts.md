# 1.12.2 PACKET WIRE LAYOUTS — full field specs

Per-field read/write order for protocol-340 packets, extracted from the
unpacked 1.12.2 sources. Packet IDs cross-referenced to
`docs/protocol-340/packet-registry-wire.md` (generated from EnumConnectionState).

Source root: `third_party_reference/minecraft/src/net/minecraft/network/`.

Notation: `VarInt`/`VarLong`/`String(n)`(max chars)/`Position`(one long)/
`UUID`(2×long)/`Slot`(ItemStack)/`NBT`/`Double`/`Float`/`Long`/`Byte`(u8 on
read where noted)/`Short`/`Int`/`Boolean`/`byte[]`.

## Shared formats

### ItemStack (Slot) — `network/PacketBuffer.java` (writeItemStack / readItemStack)
- `short id`; `id < 0` → **empty slot**, nothing more.
- else: `short id, byte count, short metadata` (all BE), then NBT **only if**
  `item.isDamageable() || item.getShareTag()`, else null(NBT).
- NBT-in-packet: `writeCompoundTag` — null → `writeByte(0)`; else TAG_Compound
  streaming (leading byte `0x0A` + short name-len + name + children + TAG_End(0)).

### BlockPos — `writeBlockPos` / `readBlockPos`
- **One BE long**: `toLong() = ((x & 0x3FFFFFF) << 38) | ((y & 0xFFF) << 26) | (z & 0x3FFFFFF)`.
- Decode sign-extends: `x=(v<<0)>>38, y=(v<<26)>>52, z=(v<<38)>>38`. X/Z 26 bits, Y 12 bits.

### String — `writeString` / `readString(int max)`
- **VarInt UTF-8 byte-length**, then UTF-8 bytes.
- Write cap: literal **32767 bytes** → EncoderException if exceeded.
- Read: `len > max*4` → reject; `len < 0` → reject; then `str.length() > max` → reject.
- All packets pass an explicit `max`.

### ByteArray — `writeByteArray` / `readByteArray`
- `VarInt len` + raw bytes. Read default cap = readableBytes.

### UUID — `writeUniqueId` / `readUniqueId`
- Two BE longs: MSB then LSB.

### Enum — `writeEnumValue`
- `VarInt ordinal()`.

### TextComponent — `writeTextComponent`
- `String` (JSON serialization). Max depends on caller.

### Entity datawatcher list — `network/datasync/EntityDataManager.java`
- Loop: `id` = **1 byte unsigned** (terminator `0xFF`), `typeId` = VarInt, then value.
- Serializer ids (`DataSerializers.java`): 0=BYTE,1=VARINT,2=FLOAT,
  3=STRING(32767),4=TEXT_COMPONENT,5=ITEM_STACK,6=BOOLEAN,7=ROTATIONS(3×float),
  8=BLOCK_POS(long),9=OPTIONAL_BLOCK_POS(bool+pos),10=FACING(varint),
  11=OPTIONAL_UNIQUE_ID(bool+2long),12=OPTIONAL_BLOCK_STATE(varint,0=absent),
  13=COMPOUND_TAG.

## Handshake / Status / Login

- **C00Handshake (HB SB 0x00)**: `VarInt protocolVersion, String ip(255), Short port, VarInt nextState(1 status/2 login)`.
- **SPacketServerInfo (Status CB 0x00)**: `String json(32767)`.
- **SPacketPong (Status CB 0x01)**: `Long time`.
- **CPacketPing (Status SB 0x01)**: `Long time`.
- **SPacketDisconnect (Login CB 0x00)**: `TextComponent reason`.
- **SPacketEncryptionRequest (Login CB 0x01)**: `String serverId(20), byte[] publicKey, byte[] verifyToken`.
- **SPacketLoginSuccess (Login CB 0x02)**: `String uuid(36), String username(16)`.
- **SPacketEnableCompression (Login CB 0x03)**: `VarInt threshold`.
- **CPacketLoginStart (Login SB 0x00)**: `String name(16)`.
- **CPacketEncryptionResponse (Login SB 0x01)**: `byte[] secretKeyEncrypted, byte[] verifyTokenEncrypted`.

## PLAY clientbound

**SPacketSpawnObject 0x00**: `VarInt entityId, UUID, Byte type, Double x, Double y, Double z, Byte pitch, Byte yaw, Int data, Short velX, Short velY, Short velZ`.

**SPacketSpawnExperienceOrb 0x01**: `VarInt entityId, Double x, Double y, Double z, Short value`.

**SPacketSpawnGlobalEntity 0x02**: `VarInt entityId, Byte type, Double x, Double y, Double z`.

**SPacketSpawnMob 0x03**: `VarInt entityId, UUID, VarInt type, Double x, Double y, Double z, Byte yaw, Byte pitch, Byte headPitch, Short velX, Short velY, Short velZ`, then datawatcher.

**SPacketSpawnPainting 0x04**: `VarInt entityId, UUID, String title(13), BlockPos, Byte facing`.

**SPacketSpawnPlayer 0x05**: `VarInt entityId, UUID, Double x, Double y, Double z, Byte yaw, Byte pitch`, then datawatcher.

**SPacketAnimation 0x06**: `VarInt entityId, Byte animation`.

**SPacketStatistics 0x07**: `VarInt count` × (`String name`, `VarInt value`).

**SPacketBlockBreakAnim 0x08**: `VarInt breakerId, BlockPos, Byte progress`.

**SPacketUpdateTileEntity 0x09**: `BlockPos, Byte type, NBT`.

**SPacketBlockAction 0x0A**: `BlockPos, Byte instrument, Byte pitch, VarInt (blockId & 4095)`.

**SPacketBlockChange 0x0B**: `BlockPos, VarInt blockStateId` (from `Block.BLOCK_STATE_IDS`).

**SPacketUpdateBossInfo 0x0C**: `UUID uniqueId, VarInt operation(ADD=0/REMOVE=1/UPDATE_PCT=2/UPDATE_NAME=3/UPDATE_STYLE=4/UPDATE_PROPERTIES=5)`; ADD: `TextComponent name, Float percent, VarInt color, VarInt overlay, Byte flags`; REMOVE: none; UPDATE_PCT: `Float percent`; UPDATE_NAME: `TextComponent name`; UPDATE_STYLE: `VarInt color, VarInt overlay`; UPDATE_PROPERTIES: `Byte flags`.

**SPacketServerDifficulty 0x0D**: `Byte difficulty`.

**SPacketTabComplete 0x0E**: `VarInt count` × `String suggestion`.

**SPacketChat 0x0F**: `TextComponent chatComponent, Byte type(0 chat/1 system/2 gameinfo)`.

**SPacketMultiBlockChange 0x10**: `Int chunkX, Int chunkZ, VarInt count` × (`Short packedOffset`(12b x | 8b y | 12b z from ChunkPos), `VarInt blockStateId`).

**SPacketConfirmTransaction 0x11**: `Byte windowId, Short action, Boolean accepted`.

**SPacketCloseWindow 0x12**: `Byte windowId`.

**SPacketOpenWindow 0x13**: `Byte windowId, String inventoryType(32), TextComponent windowTitle, Byte slotCount`; if `inventoryType == "EntityHorse"`: `Int entityId`.

**SPacketWindowItems 0x14**: `Byte windowId, Short count` × `Slot`.

**SPacketWindowProperty 0x15**: `Byte windowId, Short property, Short value`.

**SPacketSetSlot 0x16**: `Byte windowId, Short slot, Slot`.

**SPacketCooldown 0x17**: `VarInt itemId, VarInt ticks`.

**SPacketCustomPayload 0x18**: `String channel(20), byte[] data` (read guard 0..1048576 readableBytes).

**SPacketCustomSound 0x19**: `String name(256), VarInt category, Int x, Int y, Int z, Float volume, Float pitch`.

**SPacketDisconnect 0x1A**: `TextComponent reason`.

**SPacketEntityStatus 0x1B**: `Int entityId, Byte status`.

**SPacketExplosion 0x1C**: `Float x, Float y, Float z, Float radius, Int count` × (`Byte x, Byte y, Byte z`), `Float motionX, Float motionY, Float motionZ`.

**SPacketUnloadChunk 0x1D**: `Int x, Int z`.

**SPacketChangeGameState 0x1E**: `Byte reason, Float value`.

**SPacketKeepAlive 0x1F**: `Long id`.

**SPacketChunkData 0x20**: see `docs/protocol-340/chunk-format-deep-dive.md`. Header: `Int chunkX, Int chunkZ, Boolean fullChunk, VarInt availableSections, VarInt dataLen, byte[] data, VarInt tileEntityCount` × `NBT`.

**SPacketEffect 0x21**: `Int soundId, BlockPos, Int data, Boolean globalErr`.

**SPacketParticles 0x22**: `Int particleId, Boolean longDistance, Float x, Float y, Float z, Float offsetX, Float offsetY, Float offsetZ, Float particleSpeed, Int particleCount` × per-arg `VarInt`.

**SPacketJoinGame 0x23**: `Int entityId, Byte gamemode(bit3=hardcore), Int dimension, Byte difficulty(unsigned), Byte maxPlayers, String worldType(16), Boolean reducedDebugInfo`.

**SPacketMaps 0x24**: `VarInt mapId, Byte scale, Boolean tracking, Int iconCount` × (`Byte type, Byte x, Byte y, Byte rot`), `Short cols, Short rows` × `VarInt data`.

**SPacketEntity 0x25 (base)**: `VarInt entityId`.
**S15PacketEntityRelMove 0x26**: `VarInt entityId, Short dx, Short dy, Short dz, Boolean onGround`.
**S17PacketEntityLookMove 0x27**: `VarInt entityId, Short dx, Short dy, Short dz, Byte yaw, Byte pitch, Boolean onGround`.
**S16PacketEntityLook 0x28**: `VarInt entityId, Byte yaw, Byte pitch, Boolean onGround`.

**SPacketMoveVehicle 0x29**: `Double x, Double y, Double z, Float yaw, Float pitch`.

**SPacketSignEditorOpen 0x2A**: `BlockPos`.

**SPacketPlaceGhostRecipe 0x2B**: `Int windowId, Slot` × N.

**SPacketPlayerAbilities 0x2C**: `Byte flags(bit0 invulnerable,1 flying,2 allowFlying,3 creative), Float flySpeed, Float walkSpeed`.

**SPacketCombatEvent 0x2D**: `VarInt eventType(0 ENTER_COMBAT,1 END_COMBAT,2 ENTITY_DIED)`; END_COMBAT: `VarInt duration, Int entityId`.

**SPacketPlayerListItem 0x2E**: `VarInt action(0 ADD_PLAYER,1 UPDATE_GAME_MODE,2 UPDATE_LATENCY,3 UPDATE_DISPLAY_NAME,4 REMOVE_PLAYER), VarInt count`; per entry:
- ADD_PLAYER: `UUID, String name(16), VarInt propCount` × (`String name, String value, Boolean hasSig, if hasSig String signature`), `VarInt gamemode, VarInt ping, Boolean hasDisplayName, if true TextComponent`.
- UPDATE_GAME_MODE: `UUID, VarInt gamemode`.
- UPDATE_LATENCY: `UUID, VarInt ping`.
- UPDATE_DISPLAY_NAME: `UUID, Boolean hasDisplayName, if true TextComponent`.
- REMOVE_PLAYER: `UUID`.

**SPacketPlayerPosLook 0x2F**: `Double x, Double y, Double z, Float yaw, Float pitch, Byte flags(bit0=x,1=y,2=z,3=yaw_rot,4=pitch_rot), VarInt teleportId`.

**SPacketUseBed 0x30**: `Int entityId, BlockPos`.

**SPacketRecipeBook 0x31**: `Boolean craftingOpen, Boolean filteringCraftable, Boolean smeltingOpen, Boolean filteringFurnace`.

**SPacketDestroyEntities 0x32**: `VarInt count` × `VarInt entityId`.

**SPacketRemoveEntityEffect 0x33**: `VarInt entityId, Byte effectId`.

**SPacketResourcePackSend 0x34**: `String url(32767), String hash(40)`.

**SPacketRespawn 0x35**: `Int dimension, Byte difficulty, Byte gamemode, String worldType(16)`.

**SPacketEntityHeadLook 0x36**: `VarInt entityId, Byte yaw`.

**SPacketSelectAdvancementsTab 0x37**: `String tabId`(empty=main).

**SPacketWorldBorder 0x38**: `VarInt action`; SET_SIZE: `Double diameter`; LERP_SIZE: `Double oldD, Double newD, VarLong time`; SET_CENTER: `Double x, Double z`; INITIALIZE: `Double x, Double z, Double oldD, Double newD, VarLong time, VarInt portalTeleportBoundary, VarInt warningTime, VarInt warningBlocks`; SET_WARNING_TIME: `VarInt`; SET_WARNING_BLOCKS: `VarInt`.

**SPacketCamera 0x39**: `VarInt entityId`.

**SPacketHeldItemChange 0x3A**: `Byte slot`.

**SPacketDisplayObjective 0x3B**: `Byte position, String objective(16)`.

**SPacketEntityMetadata 0x3C**: `VarInt entityId`, then datawatcher.

**SPacketEntityAttach 0x3D**: `Int entityId, Int vehicleId, Boolean leash`.

**SPacketEntityVelocity 0x3E**: `VarInt entityID, Short motionX, Short motionY, Short motionZ`.

**SPacketEntityEquipment 0x3F**: `VarInt entityId, VarInt slot, Slot`.

**SPacketSetExperience 0x40**: `Float expBar, VarInt level, VarInt totalExp`.

**SPacketUpdateHealth 0x41**: `Float health, VarInt foodLevel, Float saturationLevel`.

**SPacketScoreboardObjective 0x42**: `String name(16), Byte action, TextComponent displayName`.

**SPacketSetPassengers 0x43**: `VarInt entityId, VarInt count` × `VarInt passengerId`.

**SPacketTeams 0x44**: (action switch over team name/members — complex).

**SPacketUpdateScore 0x45**: `String scoreName(40), Byte action(0 create/update,1 remove), String objective(16), VarInt value`(only if action=0).

**SPacketSpawnPosition 0x46**: `BlockPos`.

**SPacketTimeUpdate 0x47**: `Long totalWorldTime, Long worldTime`.

**SPacketTitle 0x48**: `VarInt action(0 TITLE,1 SUBTITLE,2 ACTIONBAR,3 TIMES,4 CLEAR,5 RESET)`; TITLE/SUBTITLE/ACTIONBAR: `TextComponent`; TIMES: `Int fadeIn, Int stay, Int fadeOut`.

**SPacketSoundEffect 0x49**: `VarInt soundId, VarInt category, Int x, Int y, Int z, Float volume, Float pitch`.

**SPacketPlayerListHeaderFooter 0x4A**: `TextComponent header, TextComponent footer`.

**SPacketCollectItem 0x4B**: `VarInt collectedId, VarInt collectorId, VarInt pickupCount`.

**SPacketEntityTeleport 0x4C**: `VarInt entityId, Double x, Double y, Double z, Byte yaw, Byte pitch, Boolean onGround`.

**SPacketAdvancementInfo 0x4D**: (advancement tree — complex).

**SPacketEntityProperties 0x4E**: `VarInt entityId, Int count` × (`String name, Double value, VarInt modifierCount` × (`UUID, Double amount, Byte operation`)).

## PLAY serverbound

**CPacketConfirmTeleport 0x00**: `VarInt teleportId`.

**CPacketTabComplete 0x01**: `String message(32767), Boolean hasTargetBlock, Boolean hasTargetPos, if true BlockPos`.

**CPacketChatMessage 0x02**: `String message(256)`.

**CPacketClientStatus 0x03**: `VarInt status(0 PERFORM_RESPAWN,1 REQUEST_STATS,2 OPEN_INVENTORY_ACHIEVEMENT)`.

**CPacketClientSettings 0x04**: `String lang(16), Byte view, VarInt chatVisibility, Boolean enableColors, Byte modelPartFlags, VarInt mainHand`.

**CPacketConfirmTransaction 0x05**: `Byte windowId, Short uid, Boolean accepted`.

**CPacketEnchantItem 0x06**: `Byte windowId, Byte button`.

**CPacketClickWindow 0x07**: `Byte windowId, Short slot, Byte button, Short action, VarInt mode, Slot carried, VarInt itemCount` × `Slot`.

**CPacketCloseWindow 0x08**: `Byte windowId`.

**CPacketCustomPayload 0x09**: `String channel(20), byte[] data` (read guard 0..32767).

**CPacketUseEntity 0x0A**: `VarInt entityId, VarInt action(0 INTERACT,1 ATTACK,2 INTERACT_AT)`; INTERACT: `VarInt hand`; INTERACT_AT: `Float x, Float y, Float z, VarInt hand`; ATTACK: none.

**CPacketKeepAlive 0x0B**: `Long key`.

**CPacketPlayer 0x0C (base)**: `Boolean onGround`.
**CPacketPlayer.Position 0x0D**: `Double x, Double y, Double z, Boolean onGround`.
**CPacketPlayer.PositionRotation 0x0E**: `Double x, Double y, Double z, Float yaw, Float pitch, Boolean onGround`.
**CPacketPlayer.Rotation 0x0F**: `Float yaw, Float pitch, Boolean onGround`.

**CPacketVehicleMove 0x10**: `Double x, Double y, Double z, Float yaw, Float pitch`.

**CPacketSteerBoat 0x11**: `Boolean left, Boolean right`.

**CPacketPlaceRecipe 0x12**: (recipe book select).

**CPacketPlayerAbilities 0x13**: `Byte flags, Float flySpeed, Float walkSpeed`.

**CPacketPlayerDigging 0x14**: `VarInt action(0 START_DIGGING,1 CANCELLED_DIGGING,2 FINISH_DIGGING,3 DROP_ITEM,4 DROP_ALL_ITEMS,5 RELEASE_USE_ITEM,6 SWAP_HELD_ITEMS), BlockPos, Byte facing`.

**CPacketEntityAction 0x15**: `VarInt entityID, VarInt action(0 START_SNEAKING,1 STOP_SNEAKING,2 STOP_SLEEPING,3 START_SPRINTING,4 STOP_SPRINTING,5 START_ELYTRA_FLYING,6 STOP_ELYTRA_FLYING,7 START_SWIMMING,8 STOP_SWIMMING), VarInt auxData`.

**CPacketInput 0x16**: `Float strafe, Float forward, Boolean jump, Boolean sneak`.

**CPacketRecipeInfo 0x17**: (recipe book).

**CPacketResourcePackStatus 0x18**: `String hash(40), VarInt status`.

**CPacketSeenAdvancements 0x19**: (advancement).

**CPacketHeldItemChange 0x1A**: `Short slotId`.

**CPacketCreativeInventoryAction 0x1B**: `Short slotId, Slot`.

**CPacketUpdateSign 0x1C**: `BlockPos, String line1..4`.

**CPacketAnimation 0x1D**: `VarInt hand(0 MAIN_HAND,1 OFF_HAND)`.

**CPacketSpectate 0x1E**: `UUID`.

**CPacketPlayerTryUseItemOnBlock 0x1F**: `BlockPos, Byte facing, VarInt hand, Float X, Float Y, Float Z`.

**CPacketPlayerTryUseItem 0x20**: `VarInt hand`.

## SRG / obfuscation notes

The local sources are MCP-deobf (human-readable names). The same classes are
registered by SRG names in distribution jars (e.g. `SPacketChunkData` ↔
`south`-style SRG); `third_party_reference/mappings/` (stable 39-1.12) gives
obf↔SRG↔MCP translation. Packet IDs are stable across obfuscation — they are
assigned by registration order in `EnumConnectionState`, not by class name, so
never infer an ID from a class name.
