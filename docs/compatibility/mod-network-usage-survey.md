# Real Mod Network Usage Survey (P0-5)

## Scope
Survey of 20 representative 1.12.2 Forge mods and libraries covering tech, magic, storage, dimensions, content, and performance to determine how real mods interact with the network stack.

## Classification Levels
- **LEVEL 0**: `SimpleNetworkWrapper` only (highest-level Forge API; zero raw Netty exposure).
- **LEVEL 1**: Forge `CustomPayload` / `FMLProxyPacket` directly (registers channels via `NetworkRegistry`, manages packets manually).
- **LEVEL 2**: Raw `ByteBuf` assumptions (implements `IMessage` using Netty `ByteBuf` methods directly: slicing, endianness, retain/release).
- **LEVEL 3**: Raw `Channel` / `EventLoop` / pipeline interaction (obtains Netty channel or pipeline from Forge or NetworkManager).
- **LEVEL 4**: `NetworkManager` reflection (reads or mutates private fields like `channel`, `inboundQueue`, `netHandler`).
- **LEVEL 5**: ASM / CoreMod network modification (patches network classes at bytecode level via LaunchWrapper).

## Survey Table (20 Representative Projects)

| # | Mod / Library | Primary Role | Peak Level | Specific Netty / Network Touchpoints |
| --- | --- | --- | --- | --- |
| 1 | **Mekanism** | Tech / Automation | LEVEL 2 | Uses `SimpleNetworkWrapper`; `IMessage.toBytes`/`fromBytes` rely heavily on Netty `PacketBuffer`/`ByteBuf` utilities (`writeCompoundTag`, `writeEnumValue`, `readBytes`). No raw channel access. |
| 2 | **Applied Energistics 2** | Storage / Tech | LEVEL 2 | Custom network wrapper layer on top of `SimpleNetworkWrapper` and `FMLIndexedMessageToMessageCodec`. Direct `ByteBuf` operations for compact bit-packed grids. |
| 3 | **Ender IO** | Tech / Automation | LEVEL 2 | `SimpleNetworkWrapper`; standard `PacketBuffer`/`ByteBuf` scalar writes. Enqueues message handling to main thread explicitly via `Minecraft.getMinecraft().addScheduledTask` / server equivalent. |
| 4 | **Thermal Foundation / Expansion** (CoFH) | Tech / Foundation | LEVEL 2 | `CoFHCore` networking uses `SimpleNetworkWrapper` patterns with heavy `PacketBuffer` usage. |
| 5 | **Tinkers' Construct** (SlimeKnights / Mantle) | Content / Tools | LEVEL 2 | `Mantle` provides `AbstractPacket` hierarchy wrapped in Forge networking. Pure `ByteBuf` serialization. |
| 6 | **Botania** | Magic / Tech | LEVEL 2 | Pure `SimpleNetworkWrapper` + `ByteBuf` read/write. |
| 7 | **Thaumcraft 6** | Magic | LEVEL 2 | `SimpleNetworkWrapper` registered via `NetworkRegistry.newSimpleChannel`. Uses standard `ByteBuf` streams. |
| 8 | **Twilight Forest** | Dimension / Worldgen | LEVEL 2 | Standard `SimpleNetworkWrapper` with `ByteBuf`. |
| 9 | **Biomes O' Plenty** | Worldgen / Content | LEVEL 0 | Minimal networking; syncs biome/setting data via `SimpleNetworkWrapper`. |
| 10 | **Just Enough Items (JEI)** | Utility / Recipe UI | LEVEL 2 | `SimpleNetworkWrapper` (client-to-server cheat actions, recipe transfers). Relies on `PacketBuffer` for large recipe NBT syncs. |
| 11 | **Baubles** | Library / Equipment | LEVEL 2 | Single `SimpleNetworkWrapper` channel for syncing player inventory accessories via `ByteBuf`. |
| 12 | **CodeChickenLib** | Core Library | LEVEL 3 | Implements custom packet wrappers over `FMLProxyPacket`; interacts directly with `NetworkManager` for custom packet broadcasting; provides custom `PacketCustom` streaming over raw `ByteBuf`. |
| 13 | **Chisel & Bits** | Cosmetic / Content | LEVEL 2 | `SimpleNetworkWrapper` with large `ByteBuf` arrays (compressed block voxel bitmasks up to tens of kilobytes per packet). |
| 14 | **Immersive Engineering** | Tech / Multiblocks | LEVEL 2 | `SimpleNetworkWrapper` + `ByteBuf`. Multiblock state synchronization. |
| 15 | **Galacticraft** | Dimensions / Space | LEVEL 3 | Custom packet pipeline; uses `FMLEmbeddedChannel` directly; interacts with `FMLProxyPacket` for custom dimension payload routing. |
| 16 | **OpenComputers** | Tech / Programmable | LEVEL 2 | Extensive `SimpleNetworkWrapper` usage for binary computer state and screen buffer streaming (`ByteBuf`). |
| 17 | **Phosphor** (CaffeineMC) | Lighting / Engine | LEVEL 0 | Zero network involvement; purely lighting engine optimizations. |
| 18 | **FoamFix** (asiekierka) | Performance / Memory | LEVEL 2 | Optimizes `PacketBuffer` NBT handling and reduces duplicate tag allocations, but relies on vanilla `PacketBuffer` signatures. |
| 19 | **FastFurnace / FastWorkbench** | Performance | LEVEL 0 | No networking. |
| 20 | **Netty / Packet Coremods** (e.g. VanillaFix / SpongeForge / RandomPatches) | Engine / Fixes | LEVEL 5 | **RandomPatches**: patches `NettyVarint21FrameDecoder` and `PacketBuffer` via ASM to raise packet size limits and timeouts (e.g. login timeout). **SpongeForge**: heavily intercepts `NetworkManager` and injects custom ChannelHandlers for async tracking. |

## Level Distribution Summary (N = 20)
- **LEVEL 0** (Pure SimpleNetworkWrapper / No Network): 4 / 20 (20%)
- **LEVEL 1** (FMLProxyPacket without Netty coupling): 0 / 20 (0%)
- **LEVEL 2** (Direct Netty `ByteBuf` dependence in `IMessage`): 13 / 20 (65%)
- **LEVEL 3** (Raw `Channel` / `FMLEmbeddedChannel` / pipeline access): 2 / 20 (10%)
- **LEVEL 4** (NetworkManager reflection): 0 / 20 (0% in standalone mods, overlaps with Level 5)
- **LEVEL 5** (ASM / CoreMod transformations): 1 / 20 (5% — RandomPatches / utility coremods)

## Core Finding
- **80% of the surveyed sample (16 of 20 projects)** directly import and execute methods on `io.netty.buffer.ByteBuf` via `PacketBuffer` or `IMessage.toBytes(ByteBuf)`.
- **15% of the surveyed sample (3 of 20 projects)** interact with deeper Netty abstractions (`FMLEmbeddedChannel`, raw pipeline, or ASM bytecode transformations of Netty classes).
- **0%** of mod-authored gameplay packets in the surveyed sample are completely decoupled from Netty's buffer representations.

## Compatibility Conclusion
Netty's `ByteBuf` API is a **HARD DE FACTO ABI** in the surveyed sample of the Minecraft 1.12.2 / Forge ecosystem. Any migration strategy that eliminates Netty entirely from the Java layer would break the vast majority of the surveyed ecosystem unless an exhaustive, bug-compatible `io.netty.buffer.ByteBuf` emulation layer is provided. (Note: this finding applies to the surveyed sample of 20 representative mods and is not statistically extrapolated across the entire long-tail 1.12.2 mod corpus).
