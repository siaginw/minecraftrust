# Network NBT Wire Protocol & Packet Integration

## 1. PacketBuffer Wire Format
Network NBT serialization is managed by `net.minecraft.network.PacketBuffer`:

```java
public PacketBuffer writeCompoundTag(@Nullable NBTTagCompound compound) {
    if (compound == null) {
        this.writeByte(0); // Single null byte (TAG_End) indicates absence
    } else {
        try {
            CompressedStreamTools.write(compound, new ByteBufOutputStream(this));
        } catch (IOException e) {
            throw new EncoderException(e);
        }
    }
    return this;
}
```

```java
@Nullable
public NBTTagCompound readCompoundTag() {
    int readerIndex = this.readerIndex();
    byte b0 = this.readByte();
    if (b0 == 0) {
        return null;
    } else {
        this.readerIndex(readerIndex);
        try {
            return CompressedStreamTools.read(new ByteBufInputStream(this), new NBTSizeTracker(2097152L));
        } catch (IOException e) {
            throw new EncoderException(e);
        }
    }
}
```

---

## 2. Invariants & Allocation Limits

1. **Null Encoding:** An absent or null compound is encoded as a single byte `0x00` (`TAG_End`).
2. **Present Encoding:** A present compound begins with `0x0A` (`TAG_Compound`), followed by the 2-byte empty root name `0x00 0x00`, followed by payload tags, terminated by `0x00`.
3. **2 MiB Allocation Clamp:**
   - Network parsing strictly enforces `new NBTSizeTracker(2097152L)` (2,097,152 bytes = 2 MiB).
   - If a client or corrupted packet attempts to allocate $> 2\text{ MiB}$ of NBT objects, `NBTSizeTracker` throws `RuntimeException`, aborting packet decoding and closing the Netty channel.
4. **Netty Transport Compression:**
   - NBT inside `PacketBuffer` is **uncompressed raw binary wire bytes**.
   - Netty applies packet-level compression via `NettyCompressionEncoder` when total packet length exceeds `network-compression-threshold` (default: 256 bytes in `server.properties`).

---

## 3. Packets Transmitting NBT Payloads

| Packet Class | Direction | Purpose | Typical Size |
| :--- | :--- | :--- | :--- |
| **`SPacketCustomPayload`** | S $\rightarrow$ C | Forge mod networking (`SimpleNetworkWrapper`) | $50\text{ B}\text{--}32\text{ KB}$ |
| **`CPacketCustomPayload`** | C $\rightarrow$ S | Client mod input / channel payloads | $50\text{ B}\text{--}32\text{ KB}$ |
| **`SPacketUpdateTileEntity`**| S $\rightarrow$ C | Client TileEntity sync | $100\text{ B}\text{--}2\text{ KB}$ |
| **`SPacketChunkData`** | S $\rightarrow$ C | Trailing TileEntity NBT list in chunk packet | $1\text{ KB}\text{--}16\text{ KB}$ |
| **`SPacketSetSlot`** | S $\rightarrow$ C | Inventory slot update (ItemStack NBT) | $50\text{ B}\text{--}2\text{ KB}$ |
| **`SPacketWindowItems`** | S $\rightarrow$ C | Full container open sync | $500\text{ B}\text{--}8\text{ KB}$ |
| **`CPacketCreativeInventoryAction`**| C $\rightarrow$ S | Creative mode item spawning (Exploit vector!) | $50\text{ B}\text{--}64\text{ KB}$ |
| **`SPacketEntityMetadata`** | S $\rightarrow$ C | Item frames, dropped items, armor stands | $100\text{ B}\text{--}1\text{ KB}$ |
