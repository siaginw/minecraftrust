# Network CoreMod Hazards & ASM Patches (P0-5)

## Scope
Analysis of common Minecraft 1.12.2 coremods and ASM transformers that target network classes, and their implications for Rust runtime integration.

## Major CoreMod Network Targets

| CoreMod / Patch | Target Class | Target Method / Field | Transformation Nature | Compatibility Impact for Rust Runtime |
| --- | --- | --- | --- | --- |
| **RandomPatches** | `NettyVarint21FrameDecoder` | `decode()` | Alters the 21-bit / 3-byte VarInt frame size limit (raising it to 8 MiB) to prevent large modded packet kicks. | If Rust replaces `NettyVarint21FrameDecoder`, RandomPatches' bytecode transformer will fail to find its injection point or its configuration will be ignored, leading to client kicks on oversized payloads. |
| **RandomPatches** | `NetHandlerLoginServer` | `update()` | Alters `connectionTimer >= 600` login timeout constant (configurable up to 30000 ms) for large modpacks with long client freeze times. | Replacing `NetHandlerLoginServer` in Rust would bypass the user-configured timeout unless explicitly forwarded or emulated. |
| **VanillaFix / BetterFps** | `NetworkManager` | `sendPacket()`, `dispatchPacket()` | Patches exception handling around packet dispatching to catch NPEs or broken mod packets and prevent hard server crashes. | A native Rust network loop must provide equivalent resilience against malformed or unhandled packet states. |
| **SpongeForge** | `NetworkManager` | `channelRead0()`, pipeline setup | Injects custom Sponge `ChannelHandler`s to track cause-tracking context across network boundaries. | High hazard: any architecture that removes `ChannelPipeline` will completely break SpongeForge compatibility. |
| **FoamFix** | `PacketBuffer` | `readCompoundTag()`, `writeCompoundTag()` | Replaces vanilla NBT reader with memory-optimized NBT pools to reduce allocation churn during chunk/sync packets. | Redundant if NBT serialization moves to Rust (seam NET-7 / NBT-7), but ASM patch will still attempt to apply if the Java `PacketBuffer` class is loaded. |
| **LagGoggles / Profilers** | `NetHandlerPlayServer` | `process*()` | Injects entry/exit timing probes into every packet handler method to report per-packet TPS cost. | If packet handling is moved off the Java thread or bypassed entirely, in-game profiling tools will report 0 ms or crash due to missing stack traces. |

## Compatibility Invariants for CoreMod Coexistence
1. **`PacketBuffer` Bytecode Must Exist**: Coremods expect `net.minecraft.network.PacketBuffer` to be present on the JVM classpath and to extend `io.netty.buffer.ByteBuf`. Stripping it or replacing it with an incompatible interface breaks the LaunchWrapper classloader.
2. **`NetHandlerPlayServer` Signatures Must Remain Unaltered**: Mod transformers frequently inject into `processPlayer`, `processUseEntity`, and `processCustomPayload`. These methods must continue to be invoked with the standard vanilla signatures on the `Server thread`.
3. **Pipeline Handlers Must Retain Expected Names**: Coremods that call `pipeline.addBefore("decoder", ...)` or `pipeline.get("packet_handler")` require those exact handler names to exist in the active `ChannelPipeline`.
