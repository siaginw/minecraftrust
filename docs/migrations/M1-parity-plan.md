# M1 Parity Plan: SPacketChunkData Differential Verification

## 1. Parity Objective & Explicit Comparison Contract
A successful migration requires proving that the native implementation produces **strictly identical output** to the reference Java implementation.

Because `SPacketChunkData` is a network wire packet, correctness is binary:
$$\text{Native Section Payload Bytes} \equiv \text{Java Reference Section Payload Bytes}$$

### Explicit Comparison Rule (Replacing ByteBuffer.equals)
`ByteBuffer.equals()` is prone to position and limit state bugs. Parity comparison must be explicit:
```java
boolean match = (refLength == nativeLength);
int mismatchOffset = -1;
if (match) {
    for (int i = 0; i < refLength; i++) {
        if (refBytes[i] != nativeBytes[i]) {
            match = false;
            mismatchOffset = i;
            break;
        }
    }
}
```

---

## 2. Verification Phases

### Phase 1: Standalone Differential Oracle Harness (Milestone M1.2)
- Built as an automated differential oracle comparing Java reference output against Rust `NativeChunkEncoder::encode()`.
- Golden Corpus: 1,000 chunk snapshots dumped across Target A, B, C, D saves.
- Evaluates both section payload equality and full outer packet wire equality.

### Phase 2: Live In-Game Shadow Mode (Milestone M1.4)
Activated via `-Dminecraftrust.native_chunk_packet=SHADOW`:
1. Java builds reference section payload into `ref_buf`.
2. Rust encodes native section payload into `native_buf`.
3. JVM runs explicit byte-for-byte comparison loop.
4. On mismatch: Emits structured diagnostic log, increments `shadow_mismatch_counter`.
5. Discards `native_buf`, transmits `ref_buf` (Java remains 100% authoritative).
6. **Acceptance Requirement**: 100,000 continuous chunk packet transmissions in shadow mode without a single byte mismatch.

---

## 3. Diagnostic Divergence Logging Specification
On mismatch, emit structured diagnostic log with all required fields:
```
[NATIVE-CHUNKPACKET-SHADOW-MISMATCH]
  Coordinates: Chunk (X=14, Z=-22)
  Dimension: 0 (Overworld, skyLight=true)
  Section Mask: 0x003F | Section Count: 6
  Mismatch Offset: byte 1,482
  Expected Length: 14,240 | Native Length: 14,240
  Section Index: 2 (Y=32..47)
  Palette Mode: LINEAR (BitsPerBlock=5, PaletteSize=18)
  Expected Byte: 0x4A | Native Byte: 0x4B
  Deterministic PRNG Seed: 403938725
  Context Hex Dump:
    Expected: [... 0x12, 0x34, 0x56, 0x4A, 0x78, 0x9A ...]
    Native:   [... 0x12, 0x34, 0x56, 0x4B, 0x78, 0x9A ...]
```

---

## 4. Dimension & Lighting Test Matrix

| Dimension Type | Skylight Present? | Section Payload Composition |
| :--- | :--- | :--- |
| **Overworld (Dim 0)** | `true` | Palette + BitArray + BlockLight (2048 B) + SkyLight (2048 B) |
| **Nether (Dim -1)** | `false` | Palette + BitArray + BlockLight (2048 B) [SkyLight omitted] |
| **The End (Dim 1)** | `false` | Palette + BitArray + BlockLight (2048 B) [SkyLight omitted] |
| **Modded (Twilight Forest)** | `true` | Standard 4096-byte light payload per section |
| **Modded (Betweenlands)** | `false` | BlockLight only |

---

## 5. Palette Variant Coverage Matrix

1. **`PAL-EMPTY`**: Empty chunk / section (all air blocks).
2. **`PAL-SINGLE`**: Monolithic state (e.g. solid stone, 1 state ID).
3. **`PAL-LINEAR`**: Small linear palette ($1 \le \text{states} \le 16$, $\text{bits} \le 4$).
4. **`PAL-HASHMAP`**: Medium hash-map palette ($17 \le \text{states} \le 256$, $5 \le \text{bits} \le 8$).
5. **`PAL-RESIZE-EDGE`**: Exactly 16 states (transition boundary to bits=5).
6. **`PAL-GLOBAL`**: Saturated palette ($> 256$ states, direct global palette, bits $\ge 9$).
7. **`PAL-MAX-ID`**: Maximum legal Forge blockstate ID ($4095 \ll 4 \mid 15 = 65,535$).
8. **`PAL-FOAMFIX`**: FoamFix-optimized `BlockStateContainer` backing storage.
