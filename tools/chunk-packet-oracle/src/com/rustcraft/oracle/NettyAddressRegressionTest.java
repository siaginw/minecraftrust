package com.rustcraft.oracle;

import com.rustcraft.bridge.NativeChunkPacket;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;

/**
 * M1.4-R regression test for the Netty pooled-buffer addressing bug.
 * See docs/engineering/netty-pooled-buffer-addressing.md.
 *
 * Allocates many pooled direct ByteBufs so that at least two share an arena
 * slab, asks Rust (via the fixed address helper) to fill each with a distinct
 * pattern, then asserts every buffer holds EXACTLY its own pattern.
 *
 * Also proves the OLD formula (internalNioBuffer(0,cap).address()) diverges
 * from ByteBuf.memoryAddress() for non-slab-base buffers, when that is the
 * case in the current layout.
 */
public final class NettyAddressRegressionTest {

    public static void main(String[] args) throws Exception {
        int n = 32;              // enough to force slab sharing in one arena
        int cap = 1 << 16;       // 64 KiB, same class as M1 payload buffers
        ByteBuf[] bufs = new ByteBuf[n];
        byte[] patterns = new byte[n];

        for (int i = 0; i < n; i++) {
            bufs[i] = PooledByteBufAllocator.DEFAULT.directBuffer(cap, cap);
            patterns[i] = (byte) (i + 1);
        }

        // Native fill each buffer with its own pattern through the FIXED helper.
        // fillPattern(long addr, long len, byte pattern) — test-only Rust export
        // bound on the bridge class (Java_com_rustcraft_bridge_NativeChunkPacket_fillPattern).
        for (int i = 0; i < n; i++) {
            NativeChunkPacket.fillPattern(NativeChunkPacket.getNettyBufferAddress(bufs[i]), cap, patterns[i]);
        }

        int crossWrites = 0;
        int corrupted = 0;
        byte[] tmp = new byte[cap];
        for (int i = 0; i < n; i++) {
            bufs[i].getBytes(0, tmp);
            for (int j = 0; j < cap; j++) {
                if (tmp[j] != patterns[i]) { corrupted++; break; }
            }
            // detect other buffers' patterns written into this one
            for (int k = 0; k < n; k++) {
                if (k == i) continue;
                // scan a sample; a full scan of 32x64KiB is cheap enough
                for (int j = 0; j < cap; j += 4096) {
                    if (tmp[j] == patterns[k]) { crossWrites++; break; }
                }
            }
        }

        int diverged = 0;
        for (int i = 0; i < n; i++) {
            if (!bufs[i].hasMemoryAddress()) continue;
            long good = bufs[i].memoryAddress();
            java.nio.ByteBuffer nb = bufs[i].internalNioBuffer(0, bufs[i].capacity());
            long bad = NativeChunkPacket.getDirectBufferAddress(nb);
            if (good != bad) diverged++;
        }

        for (int i = 0; i < n; i++) bufs[i].release();

        boolean pass = corrupted == 0 && crossWrites == 0;
        System.err.println("[NettyAddressRegressionTest] buffers=" + n
                + " corrupted=" + corrupted
                + " crossWrites=" + crossWrites
                + " oldFormulaDiverged=" + diverged + "/" + n);
        System.err.println(pass
                ? "[NettyAddressRegressionTest] RESULT: PASS — native writes affected ONLY the intended buffers"
                : "[NettyAddressRegressionTest] RESULT: FAIL — cross-buffer corruption detected");
        if (!pass) System.exit(1);
    }
}
