package com.rustcraft.bridge;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.CharBuffer;

/**
 * M4.1 Task 11 — Wire parity of the generalized NativeSection encoder against
 * the REAL vanilla 1.12.2 implementation.
 *
 * For each unique-state count, builds a live net.minecraft.world.chunk.BlockStateContainer,
 * fills it in index order, serializes via func_186009_b(PacketBuffer) (the exact
 * method SPacketChunkData uses), then registers the same states as a native
 * NativeChunk section and byte-compares the native Protocol 340 output prefix.
 *
 * Offline: Bootstrap-registered vanilla classes on the classpath; no server.
 */
public class M4WireParityHarness {

    private static java.lang.reflect.Field addressField;
    static {
        try {
            addressField = java.nio.Buffer.class.getDeclaredField("address");
            addressField.setAccessible(true);
        } catch (Throwable t) { addressField = null; }
    }
    private static long addr(ByteBuffer bb) {
        try { return addressField.getLong(bb); } catch (Throwable t) { return 0; }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== M4.1 WIRE PARITY: generalized native encoder vs real vanilla BlockStateContainer ===");
        if (!NativeChunkBridge.isAvailable()) {
            System.err.println("NativeChunkBridge not available"); System.exit(1); return;
        }

        // Vanilla bootstrap: registers blocks/states into Block.BLOCK_STATE_IDS
        Class<?> bootstrap = Class.forName("net.minecraft.init.Bootstrap");
        bootstrap.getMethod("func_151354_b").invoke(null);

        Class<?> blockCls = Class.forName("net.minecraft.block.Block");
        Object regmap = blockCls.getField("field_176229_d").get(null); // BLOCK_STATE_IDS
        java.lang.reflect.Method regGetId = regmap.getClass().getMethod("func_148747_b", Object.class);
        int regSize = (Integer) regmap.getClass().getMethod("func_186804_a").invoke(regmap);
        // MathHelper.log2 = ceil(log2(size)) = 32 - nlz(size-1) (pow-2 verified, M4.2A B1)
        int globalBits = regSize <= 1 ? 0 : 32 - Integer.numberOfLeadingZeros(regSize - 1);
        NativeChunkBridge.setGlobalPaletteBits((byte) globalBits);
        System.out.println("registry size=" + regSize + " -> global bits=" + globalBits
                + " (bridge live=" + NativeChunkBridge.LIVE_GLOBAL_PALETTE_BITS + ")");
        // True global state id = (blockId << 4) | meta; obtained via BLOCK_STATE_IDS.getId(state).

        Class<?> containerCls = Class.forName("net.minecraft.world.chunk.BlockStateContainer");
        Class<?> packetBufCls = Class.forName("net.minecraft.network.PacketBuffer");
        Class<?> unpooled = Class.forName("io.netty.buffer.Unpooled");
        java.lang.reflect.Method setMethod = containerCls.getMethod("func_186013_a", int.class, int.class, int.class,
                Class.forName("net.minecraft.block.state.IBlockState"));
        java.lang.reflect.Method writeMethod = containerCls.getMethod("func_186009_b", packetBufCls);

        // Direct buffers
        ByteBuffer primerBB = ByteBuffer.allocateDirect(65536 * 2).order(ByteOrder.nativeOrder());
        CharBuffer primerCB = primerBB.asCharBuffer();
        ByteBuffer outBB = ByteBuffer.allocateDirect(65536).order(ByteOrder.nativeOrder());
        long primerAddr = addr(primerBB), outAddr = addr(outBB);

        int[] cases = {1, 2, 3, 4, 8, 12, 15, 16, 17, 20, 31, 32, 33, 48, 64, 65, 100, 127, 128, 129,
                       200, 255, 256, 257, 300, 512};
        int pass = 0, fail = 0;
        int cx = 900;

        for (int unique : cases) {
            // Gather `unique` distinct non-air states with true registry ids
            java.util.List<Object> stateList = new java.util.ArrayList<>(unique);
            java.util.List<Integer> idList = new java.util.ArrayList<>(unique);
            @SuppressWarnings("unchecked")
            java.lang.Iterable<Object> registryIterable = (java.lang.Iterable<Object>) regmap;
            for (Object st : registryIterable) {
                int gid = (Integer) regGetId.invoke(regmap, st);
                if (gid != 0) { stateList.add(st); idList.add(gid); }
                if (stateList.size() == unique) break;
            }
            if (stateList.size() < unique) { System.out.println("case " + unique + ": registry exhausted"); break; }
            Object[] states = stateList.toArray();
            int[] ids = new int[unique];
            for (int i = 0; i < unique; i++) ids[i] = idList.get(i);

            // Fill pattern: section index i -> states[i % unique].
            // Vanilla container inserted in SECTION INDEX order (x fastest), matching
            // NativeSection::build_local_palette's first-appearance scan order.
            Object container = containerCls.getDeclaredConstructor().newInstance();
            primerCB.clear();
            for (int i = 0; i < 4096; i++) {
                int x = i & 15, z = (i >> 4) & 15, y = i >> 8;
                Object st = states[i % unique];
                setMethod.invoke(container, x, y, z, st);
                primerCB.put((x << 12) | (z << 8) | y, (char) ids[i % unique]);
            }

            Object byteBuf = unpooled.getMethod("buffer").invoke(null);
            Object pbuf = packetBufCls.getConstructor(Class.forName("io.netty.buffer.ByteBuf"))
                    .newInstance(byteBuf);
            writeMethod.invoke(container, pbuf);
            java.lang.reflect.Method readableBytes = byteBuf.getClass().getMethod("readableBytes");
            int vLen = (Integer) readableBytes.invoke(byteBuf);
            byte[] vanilla = new byte[vLen];
            // io.netty ByteBuf read into byte[]: use getBytes(0, byte[])
            byteBuf.getClass().getMethod("getBytes", int.class, byte[].class).invoke(byteBuf, 0, vanilla);

            // --- Native encode ---
            long genId = NativeChunkBridge.register(0, cx, 0, primerAddr, 0);
            if (genId <= 0) { System.out.println("case " + unique + ": register FAILED"); fail++; cx++; continue; }
            int nBytes = NativeChunkBridge.encodePacket(0, cx, 0, genId, true, false, outAddr, 65536);
            if (nBytes < vLen) { System.out.println("case " + unique + ": native too short " + nBytes + " < " + vLen); fail++; cx++; continue; }

            byte[] nativePrefix = new byte[vLen];
            outBB.clear(); outBB.get(nativePrefix, 0, vLen);

            boolean match = java.util.Arrays.equals(vanilla, nativePrefix);
            int diffAt = -1;
            if (!match) {
                for (int i = 0; i < vLen; i++) if (vanilla[i] != nativePrefix[i]) { diffAt = i; break; }
            }
            int bits = vanilla[0] & 0xFF;
            System.out.printf("unique=%3d vanilla_bits=%2d vLen=%5d native_total=%5d -> %s%s%n",
                    unique, bits, vLen, nBytes, match ? "MATCH" : "MISMATCH",
                    match ? "" : " (first diff @ byte " + diffAt + ")");
            if (match) pass++; else fail++;

            NativeChunkBridge.unload(0, cx, 0);
            cx++;
        }

        System.out.println("\nRESULT: " + pass + " pass, " + fail + " fail");
        System.exit(fail == 0 ? 0 : 1);
    }
}
