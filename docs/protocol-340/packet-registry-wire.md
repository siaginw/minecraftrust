# PROTOCOL-340 PACKET REGISTRY (wire layouts)

Augments `machine/protocol-340-packets.yaml` (ID/class/direction registry, 124
packets, generated from runtime reflection over `EnumConnectionState`). This
doc adds per-packet **wire field order** sourced from unpacked 1.12.2 sources
under `third_party_reference/minecraft/src/net/minecraft/network/`.

The ID↔class tables below were **generated programmatically from
`EnumConnectionState.java` registration order** (not hand-typed). Wire layouts
are given for classes whose `writePacketData`/`readPacketData` were audited
(see `docs/protocol-340/packet-wire-layouts.md` for the full per-field specs).

Version: 1.12.2 / protocol 340 / Forge 14.23.5.2860.

## Shared wire primitives (1.12.2 specifics — READ FIRST)

Full detail in `docs/protocol-340/datatypes-oracle.md`. Highlights:

- **VarInt**: 7-bit base-128 LE, MSB(0x80)=continue, max 5 bytes; negatives→5 bytes.
- **VarLong**: same, max 10 bytes.
- **Int/Long/Short/Float/Double**: big-endian (Netty default order).
- **Boolean**: 1 byte.
- **String**: **VarInt** UTF-8 byte-length + bytes. Write cap 32767 bytes
  (hard literal). Read pre-checks `len > max*4`, post-checks `str.length() > max`.
- **Position (BlockPos)**: **one BE long**, pack `(x&0x3FFFFFF)<<38|(y&0xFFF)<<26|(z&0x3FFFFFF)`, decode sign-extends.
- **UUID**: **two BE longs** (MSB, LSB). NOT 16 LE bytes (later versions).
- **NBT**: TAG_Compound stream; null→single `0x00`; read capped by `NBTSizeTracker(2097152)`.
- **ItemStack (Slot)**: `short id`(<0=empty, nothing more); else `short id, byte count, short metadata`, NBT only if `isDamageable()||getShareTag()`. **short id, NOT varint.**
- **ByteArray**: `VarInt len` + bytes.
- **Enum**: `VarInt ordinal()`.
- **Entity metadata list**: loop `id`=**1 unsigned byte**, terminator **0xFF** (NOT 0x7F), `typeId`=VarInt, value per serializer.

## HANDSHAKING / STATUS / LOGIN

**HANDSHAKING SB 0x00 C00Handshake**: `VarInt protocolVersion, String ip(max255), Short port, VarInt nextState(1=status,2=login)`.

**STATUS** CB 0x00 `String json` · CB 0x01 `Long time` · SB 0x00 (empty) · SB 0x01 `Long time`.

**LOGIN** CB 0x00 `TextComponent` · CB 0x01 `String serverId(max20), byte[] key, byte[] token` · CB 0x02 `String uuid(max36), String username(max16)` · CB 0x03 `VarInt threshold` · SB 0x00 `String name(max16)` · SB 0x01 `byte[] key, byte[] token`.

## PLAY — CLIENTBOUND (exact)

| ID | Class | Wire layout |
|---|---|---|
| 0x00 | SPacketSpawnObject | VarInt eid, UUID, Byte type, Double xyz, Byte pitch, Byte yaw, Int data, Short vel3 |
| 0x01 | SPacketSpawnExperienceOrb | VarInt eid, Double xyz, Short value |
| 0x02 | SPacketSpawnGlobalEntity | VarInt eid, Byte type, Double xyz |
| 0x03 | SPacketSpawnMob | VarInt eid, UUID, VarInt type, Double xyz, Byte yaw, Byte pitch, Byte headPitch, Short vel3 |
| 0x04 | SPacketSpawnPainting | VarInt eid, UUID, String title(max13), BlockPos, Byte facing |
| 0x05 | SPacketSpawnPlayer | VarInt eid, UUID, Double xyz, Byte yaw, Byte pitch, datawatcher |
| 0x06 | SPacketAnimation | VarInt eid, Byte animation |
| 0x07 | SPacketStatistics | VarInt count × (String name, VarInt value) |
| 0x08 | SPacketBlockBreakAnim | VarInt eid, BlockPos, Byte progress |
| 0x09 | SPacketUpdateTileEntity | BlockPos, Byte type, NBT |
| 0x0A | SPacketBlockAction | BlockPos, Byte instrument, Byte pitch, VarInt(blockId&4095) |
| 0x0B | SPacketBlockChange | BlockPos, VarInt blockStateId |
| 0x0C | SPacketUpdateBossInfo | UUID, VarInt op, switch payload |
| 0x0D | SPacketServerDifficulty | Byte difficulty, Boolean locked(empty=false in write) |
| 0x0E | SPacketTabComplete | VarInt count × String |
| 0x0F | SPacketChat | TextComponent, Byte type |
| 0x10 | SPacketMultiBlockChange | Int chunkX, Int chunkZ, VarInt count × (Short packedOffset, VarInt blockStateId) |
| 0x11 | SPacketConfirmTransaction | Byte windowId, Short action, Boolean accepted |
| 0x12 | SPacketCloseWindow | Byte windowId |
| 0x13 | SPacketOpenWindow | Byte windowId, String type(max32), TextComponent title, Byte slotCount, if EntityHorse: Int eid |
| 0x14 | SPacketWindowItems | Byte windowId, Short count × ItemStack |
| 0x15 | SPacketWindowProperty | Byte windowId, Short prop, Short value |
| 0x16 | SPacketSetSlot | Byte windowId, Short slot, ItemStack |
| 0x17 | SPacketCooldown | VarInt itemId, VarInt ticks |
| 0x18 | SPacketCustomPayload | String channel(max20), byte[] data (0..1048576) |
| 0x19 | SPacketCustomSound | String name(max256), VarInt category, Int xyz, Float volume, Float pitch |
| 0x1A | SPacketDisconnect | TextComponent |
| 0x1B | SPacketEntityStatus | Int eid, Byte status |
| 0x1C | SPacketExplosion | Float xyz, Float radius, Int count × (Byte x,Byte y,Byte z), Float motion3 |
| 0x1D | SPacketUnloadChunk | Int x, Int z |
| 0x1E | SPacketChangeGameState | Byte reason, Float value |
| 0x1F | SPacketKeepAlive | Long id |
| 0x20 | SPacketChunkData | Int x, Int z, Boolean fullChunk, VarInt availableSections, VarInt dataLen, byte[] data, VarInt teCount × NBT — **x/z are Int, not VarInt**; see chunk deep-dive |
| 0x21 | SPacketEffect | Int soundId, BlockPos, Int data, Boolean globalErr |
| 0x22 | SPacketParticles | Int id, Boolean longDistance, Float xyz, Float off3, Float speed, Int count, per-arg VarInt |
| 0x23 | SPacketJoinGame | Int eid, Byte gamemode(hardcore bit3), Int dimension, Byte difficulty, Byte maxPlayers, String worldType(max16), Boolean reducedDebug |
| 0x24 | SPacketMaps | (map data) |
| 0x25 | SPacketEntity (base) | VarInt eid only |
| 0x26 | S15PacketEntityRelMove (SPacketEntity.RelMove) | VarInt eid, Short dx, Short dy, Short dz, Boolean onGround |
| 0x27 | S17PacketEntityLookMove (SPacketEntity.LookMove) | VarInt eid, Short dx,dy,dz, Byte yaw, Byte pitch, Boolean onGround |
| 0x28 | S16PacketEntityLook (SPacketEntity.Look) | VarInt eid, Byte yaw, Byte pitch, Boolean onGround |
| 0x29 | SPacketMoveVehicle | Double xyz, Float yaw, Float pitch |
| 0x2A | SPacketSignEditorOpen | BlockPos |
| 0x2B | SPacketPlaceGhostRecipe | Int windowId |
| 0x2C | SPacketPlayerAbilities | Byte flags, Float flySpeed, Float walkSpeed |
| 0x2D | SPacketCombatEvent | VarInt eventType, switch payload |
| 0x2E | SPacketPlayerListItem | VarInt action, VarInt count, per-entry switch |
| 0x2F | SPacketPlayerPosLook | Double xyz, Float yaw, Float pitch, Byte flags, VarInt teleportId |
| 0x30 | SPacketUseBed | Int eid, BlockPos |
| 0x31 | SPacketRecipeBook | Boolean craftingOpen, Boolean filteringCraftable, Boolean smeltingOpen, Boolean filteringFurnace |
| 0x32 | SPacketDestroyEntities | VarInt count × VarInt eid |
| 0x33 | SPacketRemoveEntityEffect | VarInt eid, Byte effectId |
| 0x34 | SPacketResourcePackSend | String url(max32767), String hash(max40) |
| 0x35 | SPacketRespawn | Int dimension, Byte difficulty, Byte gamemode, String worldType(max16) |
| 0x36 | SPacketEntityHeadLook | VarInt eid, Byte yaw |
| 0x37 | SPacketSelectAdvancementsTab | String tabId |
| 0x38 | SPacketWorldBorder | (action switch) |
| 0x39 | SPacketCamera | VarInt eid |
| 0x3A | SPacketHeldItemChange | Byte slot |
| 0x3B | SPacketDisplayObjective | Byte position, String objective(max16) |
| 0x3C | SPacketEntityMetadata | VarInt eid, datawatcher (0xFF term) |
| 0x3D | SPacketEntityAttach | Int eid, Int vehicleId, Boolean leash |
| 0x3E | SPacketEntityVelocity | VarInt eid, Short vel3 |
| 0x3F | SPacketEntityEquipment | VarInt eid, VarInt slot, ItemStack |
| 0x40 | SPacketSetExperience | Float expBar, VarInt level, VarInt totalExp |
| 0x41 | SPacketUpdateHealth | Float health, VarInt food, Float saturation |
| 0x42 | SPacketScoreboardObjective | String name(max16), Byte action, TextComponent displayName |
| 0x43 | SPacketSetPassengers | VarInt eid, VarInt count × VarInt passenger |
| 0x44 | SPacketTeams | (team switch) |
| 0x45 | SPacketUpdateScore | String scoreName(max40), Byte action, String objective(max16), VarInt value |
| 0x46 | SPacketSpawnPosition | BlockPos |
| 0x47 | SPacketTimeUpdate | Long time, Long worldTime |
| 0x48 | SPacketTitle | VarInt action, switch payload |
| 0x49 | SPacketSoundEffect | VarInt soundId, VarInt category, Int xyz, Float volume, Float pitch |
| 0x4A | SPacketPlayerListHeaderFooter | TextComponent header, TextComponent footer |
| 0x4B | SPacketCollectItem | VarInt collectedId, VarInt collectorId, VarInt pickupCount |
| 0x4C | SPacketEntityTeleport | VarInt eid, Double xyz, Byte yaw, Byte pitch, Boolean onGround |
| 0x4D | SPacketAdvancementInfo | (advancement tree) |
| 0x4E | SPacketEntityProperties | VarInt eid, Int count × (String name, Double value, VarInt modifierCount × (UUID, Double amount, Byte op)) |

## PLAY — SERVERBOUND (exact)

| ID | Class | Wire layout |
|---|---|---|
| 0x00 | CPacketConfirmTeleport | VarInt teleportId |
| 0x01 | CPacketTabComplete | String message(max32767), Boolean hasTargetBlock(hardcoded false), Boolean hasTargetPos, if true BlockPos |
| 0x02 | CPacketChatMessage | String message(max256) |
| 0x03 | CPacketClientStatus | VarInt status |
| 0x04 | CPacketClientSettings | String lang(max16), Byte viewDist, VarInt chatVisibility, Boolean colors, Byte modelParts, VarInt mainHand |
| 0x05 | CPacketConfirmTransaction | Byte windowId, Short uid, Boolean accepted |
| 0x06 | CPacketEnchantItem | Byte windowId, Byte button |
| 0x07 | CPacketClickWindow | Byte windowId, Short slot, Byte button, Short action, VarInt mode, ItemStack carried, VarInt count × ItemStack |
| 0x08 | CPacketCloseWindow | Byte windowId |
| 0x09 | CPacketCustomPayload | String channel(max20), byte[] data (0..32767) |
| 0x0A | CPacketUseEntity | VarInt eid, VarInt action, switch (INTERACT: VarInt hand; INTERACT_AT: Float xyz + VarInt hand; ATTACK: none) |
| 0x0B | CPacketKeepAlive | Long key |
| 0x0C | CPacketPlayer (base) | Boolean onGround |
| 0x0D | CPacketPlayer.Position | Double xyz, Boolean onGround |
| 0x0E | CPacketPlayer.PositionRotation | Double xyz, Float yaw, Float pitch, Boolean onGround |
| 0x0F | CPacketPlayer.Rotation | Float yaw, Float pitch, Boolean onGround |
| 0x10 | CPacketVehicleMove | Double xyz, Float yaw, Float pitch |
| 0x11 | CPacketSteerBoat | Boolean left, Boolean right |
| 0x12 | CPacketPlaceRecipe | (recipe book) |
| 0x13 | CPacketPlayerAbilities | Byte flags, Float flySpeed, Float walkSpeed |
| 0x14 | CPacketPlayerDigging | VarInt action, BlockPos, Byte facing |
| 0x15 | CPacketEntityAction | VarInt eid, VarInt action, VarInt auxData |
| 0x16 | CPacketInput | Float strafe, Float forward, Boolean jump, Boolean sneak |
| 0x17 | CPacketRecipeInfo | (recipe book) |
| 0x18 | CPacketResourcePackStatus | String hash(max40), VarInt status |
| 0x19 | CPacketSeenAdvancements | (advancement) |
| 0x1A | CPacketHeldItemChange | Short slotId |
| 0x1B | CPacketCreativeInventoryAction | Short slotId, ItemStack |
| 0x1C | CPacketUpdateSign | BlockPos, String line1..4 |
| 0x1D | CPacketAnimation | VarInt hand |
| 0x1E | CPacketSpectate | UUID |
| 0x1F | CPacketPlayerTryUseItemOnBlock | BlockPos, Byte facing, VarInt hand, Float X,Y,Z |
| 0x20 | CPacketPlayerTryUseItem | VarInt hand |

## Packet ID cross-check

CB 0x00–0x4E (79 packets) and SB 0x00–0x20 (33 packets) generated directly from
`EnumConnectionState.java` registration order — authoritative for 1.12.2.
`machine/protocol-340-packets.yaml` (124 total incl. handshake/status/login)
is the parallel generated registry; keep both in sync via the generator, never
hand-edit IDs.

## Known 340-vs-modern discrepancies (summary; full list in knowledge-report §L)

- SPacketChunkData chunkX/chunkZ = **Int (BE)**, not VarInt.
- Entity metadata terminator = **0xFF byte**, not 0x7F VarInt.
- UUID = **2×long BE**, not 16-byte LE.
- ItemStack id = **short BE**, not VarInt.
- String length prefix = **VarInt** in 1.12.2 (not short; that's pre-1.7 / NBT-internal strings).
- No heightmaps in SPacketChunkData (added 1.15).
- Entity rel-move/re-look are separate packet IDs 0x26/0x27/0x28 (no 1.8-style bit-flag byte on 0x25).
