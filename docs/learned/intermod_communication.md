# Learned: FML InterModComms (IMC) Messaging Subsystem

## 1. Overview & Purpose
FML's InterModComms (`net.minecraftforge.fml.common.event.FMLInterModComms`) provides a decoupled, structured messaging bus allowing Forge mods to communicate and coordinate features without creating direct compile-time code dependencies.

---

## 2. Architecture & Delivery Lifecycle

### 2.1 Message Structure (`IMCMessage`)
An `IMCMessage` consists of:
- `modId`: The recipient mod ID (string).
- `sender`: The sending mod ID (string).
- `key`: The message type/command identifier (string).
- Payload: Supports three distinct payload types:
  - `String`
  - `ItemStack`
  - `NBTTagCompound`
  - `ResourceLocation`
  - `Function<..., ...>` (code callback)

### 2.2 Dispatch & Consumption Stages
```
[PRE-INIT] -> [INIT: sendRuntimeMessage()] -> [POST-INIT: IMCEvent fired] -> [GAME LOOP]
```
1. **Sending (`FMLInterModComms.sendMessage()`)**:
   - Mods typically queue messages during `FMLInitializationEvent`.
   - Messages are buffered in internal multimaps keyed by recipient mod ID (`ListMultimap<String, IMCMessage>`).
2. **Receiving (`FMLInterModComms.IMCEvent`)**:
   - Dispatched to each mod during `FMLPostInitializationEvent`.
   - Mod iterates over received messages via `event.getMessages()`:
     ```java
     for (FMLInterModComms.IMCMessage msg : event.getMessages()) {
         if ("registerWailaProvider".equals(msg.key)) {
             // Register third-party tooltip provider
         }
     }
     ```
3. **Runtime Messages**:
   - Mods can send messages dynamically at runtime (`sendRuntimeMessage()`), which are processed during server ticks.

---

## 3. Common Ecosystem Uses
- **JEI (Just Enough Items)**: Used by hundreds of mods to register recipe categories, catalysts, and click-area coordinates.
- **TOP (The One Probe) / WAILA**: Used by tech mods to register HUD inspection providers.
- **Ender IO / Thermal Expansion**: Used to register custom alloying recipes, sag mill outputs, or induction smelter recipes via NBT messages.

---

## 4. Invariants for Native Runtime
- IMC messages often transfer live Java object instances (`ItemStack`, lambda functions).
- The IMC message buffer is entirely Java-owned and has negligible tick cost because it is active primarily during startup. It does not require native migration.
