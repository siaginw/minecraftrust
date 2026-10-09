# NBT Security Semantics & Exploit Defense

## 1. Attack Vectors in Minecraft 1.12.2

Because NBT is parsed directly from untrusted network clients (via `CPacketCreativeInventoryAction`, `CPacketCustomPayload`, and book signing packets), it represents a critical denial-of-service and remote-crash attack surface.

```
+-----------------------------------------------------------------------------------+
|                           NBT VULNERABILITY TAXONOMY                              |
+--------------------------+------------------------------+-------------------------+
| Exploit Class            | Malicious Payload Mechanism  | Failure Mode            |
+--------------------------+------------------------------+-------------------------+
| 1. Call-Stack Exhaustion | Compound nested >1,000 deep  | JVM StackOverflowError  |
| 2. Heap Allocation Bomb  | Array length header = 2^31-1 | OutOfMemoryError / OOM  |
| 3. Zip Bomb Expansion    | Highly compressible sparse   | Disk / RAM exhaustion   |
| 4. MUTF-8 Surrogate Hang | Invalid 0xED surrogate byte  | Parser loop / crash     |
| 5. Creative NBT Crashers | Recursive entity passengers  | Server tick crash       |
+--------------------------+------------------------------+-------------------------+
```

---

## 2. Mojang & Forge Defense Mechanisms

### 1. The 512 Recursion Depth Clamp
- In vanilla 1.12.2, `NBTTagCompound.read()` and `NBTTagList.read()` receive a depth counter.
- If `depth > 512`, the parser throws:
  `RuntimeException("Tried to read NBT tag with too high complexity, depth > 512")`
- **Asymmetry Hazard:** In Java, `write()` has **no depth check**. A crafted in-memory tree $> 512$ writes successfully in Java but crashes on read.

### 2. The `NBTSizeTracker` Allocation Budget
- Network packets enforce `new NBTSizeTracker(2097152L)` (2 MiB).
- Every read operation accounts for allocated bits:
  - Header bytes: 16-32 bits
  - String length + content: $288 + 16 \times \text{len}$ bits
  - Compound start: 384 bits
  - Array elements: $8 \times \text{len}$ bits
- If cumulative allocated bits exceed the tracker limit, reading halts immediately.

---

## 3. Rust Codec Security Invariants

In `crates/nbt`, the following defensive invariants are strictly enforced:

1. **Dual Read & Write Bounded Depth ($\le 512$):**
   Both `NbtDecoder::decode` and `NbtEncoder::encode` reject recursion depths exceeding 512 with `NbtError::DepthExceeded(depth)`.
2. **Safe Array Allocation:**
   Before allocating `Vec<u8>`, `Vec<i32>`, or `Vec<i64>`, the decoder verifies that the declared array length does not exceed remaining buffer slice length. Declaring a 1 GB array inside a 100-byte packet immediately returns `NbtError::UnexpectedEof` with **zero heap allocation**.
3. **MUTF-8 Validation:**
   The MUTF-8 decoder validates surrogate pairs and null sequences before materializing Rust `String`, preventing malformed surrogate panics.
4. **Panic-Free Guarantee:**
   No malformed or malicious binary payload can cause a Rust panic or undefined behavior; all errors map cleanly to `Result<T, NbtError>`.
