# M1 Java/Rust FFI Contract: NativeChunkPacket Specification

## 1. JNI Function Signature & ABI

### 1.1 Native Export Declaration
```c
JNIEXPORT jint JNICALL Java_com_rustcraft_bridge_NativeChunkPacket_encodeSections(
    JNIEnv *env,
    jclass clazz,
    jlong staging_buf_address,
    jint staging_buf_len,
    jlong output_buf_address,
    jint output_buf_capacity
);
```

### 1.2 Parameter Specifications

| Parameter | Type | Constraints | Description |
| :--- | :--- | :--- | :--- |
| `env` | `JNIEnv*` | Non-null | Pointer to the active thread JNI environment. |
| `clazz` | `jclass` | Non-null | Static class handle for `NativeChunkPacket`. |
| `staging_buf_address` | `jlong` | Non-zero | Direct memory address of the GC-safe thread-local staging buffer (INPUT-A format). |
| `staging_buf_len` | `jint` | `staging_buf_len > 0` | Valid byte length of the staged chunk data. |
| `output_buf_address` | `jlong` | Non-zero | Direct memory address of the Netty `DirectByteBuf` memory slice. |
| `output_buf_capacity` | `jint` | `output_buf_capacity >= 262144` | Allocated capacity of the Netty output buffer in bytes (minimum 256 KB headroom). |

---

## 2. Memory Layouts & Data Representations

### 2.1 Staging Buffer Binary Schema (INPUT-A Layout)
Contiguous off-heap memory structure populated by Java on `ServerThread`:
```
+-----------------------------------------------------------------------------+
| STAGING BUFFER HEADER (4 Bytes)                                             |
+-----------------------------------------------------------------------------+
| primary_bit_mask: u16 (Bitmask of non-empty sections to serialize)          |
| flags: u8 (Bit 0 = fullChunk, Bit 1 = skyLightPresent, Bits 2..7 = reserved)|
| section_count: u8 (Number of serialized section blocks following)           |
+-----------------------------------------------------------------------------+
| REPEATED SECTION BLOCKS (section_count iterations)                          |
+-----------------------------------------------------------------------------+
| section_index: u8 (Y index: 0..15)                                          |
| bits_per_block: u8 (4 to 8, or 13..16 for global palette)                   |
| palette_count: u16 (Number of registered state IDs; 0 if global palette)    |
| palette_entries: u32[palette_count] (Numerical state IDs from BLOCK_STATE_IDS)|
| storage_words_count: u16 (Number of u64 entries in BitArray storage)        |
| storage_words: u64[storage_words_count] (Packed backing long array)         |
| block_light: [u8; 2048] (NibbleArray block light)                           |
| sky_light: [u8; 2048] (NibbleArray sky light; present only if flag Bit 1 = 1)|
+-----------------------------------------------------------------------------+
| OPTIONAL BIOME DATA (Present only if flag Bit 0 = 1)                        |
+-----------------------------------------------------------------------------+
| biomes: [u8; 256] (Biome IDs for the 16x16 chunk column)                    |
+-----------------------------------------------------------------------------+
```

### 2.2 Wire Output Layout (Protocol 340 SPacketChunkData Payload)
Rust writes directly into `output_buf_address` matching exact vanilla network layout:
```
For each section serialized:
  1. bits_per_block: u8
  2. palette_length: VarInt (omitted if bits_per_block >= 9)
  3. palette_entries: VarInt[]
  4. data_array_length: VarInt (storage_words_count)
  5. data_array: u64[] (Packed BitArray, Big-Endian)
  6. block_light: [u8; 2048]
  7. sky_light: [u8; 2048] (if skyLightPresent)

If fullChunk == true:
  8. biomes: [u8; 256]
```

---

## 3. Buffer Lifetimes & Ownership Rules
1. **Thread-Local Staging Buffer**: Allocated once per thread (`ByteBuffer.allocateDirect(131072)`) and reused. Zero GC allocation during staging.
2. **Output Netty Buffer**: Allocated from Netty's off-heap pooled allocator. Rust borrows the raw address; Java owns the reference count and releases upon channel write.
3. **Zero Heap Relocation**: Both input and output buffers reside entirely in off-heap memory. HotSpot GC never inspects or relocates them.
4. **Zero Safepoint Blocking**: No JNI primitive array critical sections are entered. HotSpot GC safepoints operate without delay.

---

## 4. Panic Containment & Error Codes

### Panic Policy
Rust wraps internal encoding logic in `std::panic::catch_unwind`. Unhandled panics return `-5`, log diagnostic chunk metadata, and trigger immediate Java fallback.

### Error Codes

| Return Code | Mnemonic | Description | Action on JVM |
| :--- | :--- | :--- | :--- |
| `> 0` | `SUCCESS` | Number of valid payload bytes written into direct buffer. | Update Netty writerIndex, transmit packet. |
| `-1` | `NULL_BUFFER_POINTER` | Direct buffer memory address is 0 or unmapped. | Metric `native_fallback`, trigger Java fallback. |
| `-2` | `OUTPUT_BUFFER_OVERFLOW` | Payload size exceeded allocated output buffer capacity. | Metric `native_fallback`, trigger Java fallback. |
| `-3` | `INVALID_SECTION_BITMASK` | Primary bitmask is out of range or malformed. | Metric `native_fallback`, trigger Java fallback. |
| `-4` | `UNSUPPORTED_PALETTE_WIDTH` | Palette width violates Protocol 340 rules. | Metric `native_fallback`, trigger Java fallback. |
| `-5` | `RUST_PANIC_CAUGHT` | An internal Rust assertion or panic was safely caught. | Log ERROR stack trace, trigger Java fallback. |
