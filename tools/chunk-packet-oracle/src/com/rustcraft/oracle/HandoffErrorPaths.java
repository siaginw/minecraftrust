package com.rustcraft.oracle;

import com.rustcraft.bridge.NativeChunkPacket;
import net.minecraft.init.Bootstrap;
import net.minecraft.network.play.server.SPacketChunkData;
import net.minecraft.world.chunk.Chunk;

/** M1.4-R2 §2: HANDOFF-A error-path safety — corrupt staging, forced panic,
 *  tiny payload (gate), oversized prediction. All must fall back cleanly, no
 *  JVM crash, no partial writes. */
public final class HandoffErrorPaths {

    public static void main(String[] args) throws Exception {
        Bootstrap.func_151354_b();
        M14RParityHarness.Fixture fx = M14RParityHarness.buildDeterministic(1); // large
        Chunk ch = M14RParityHarness.createMockChunk(fx);
        long sink = 0;

        NativeChunkPacket.setRuntimeMode("ON_EXPERIMENTAL");
        NativeChunkPacket.setHandoffMode("A");

        // 1. normal
        SPacketChunkData ok = new SPacketChunkData(ch, 0);
        boolean r1 = NativeChunkPacket.populatePacket(ok, ch, fx.mask);
        System.err.println("ERRPATH normal ok=" + r1);
        sink += r1 ? 1 : 0;

        // 2. tiny payload -> gate skip (Java fallback)
        M14RParityHarness.Fixture small = M14RParityHarness.buildDeterministic(2);
        Chunk chS = M14RParityHarness.createMockChunk(small);
        SPacketChunkData pS = new SPacketChunkData(chS, 0);
        boolean r2 = NativeChunkPacket.populatePacket(pS, chS, small.mask);
        System.err.println("ERRPATH small ok=" + r2 + " (expect false -> Java path)");
        sink += r2 ? 1 : 0;

        // 3. forced Rust panic inside encode (critical section active)
        SPacketChunkData pP = new SPacketChunkData(ch, 0);
        boolean r3;
        try {
            int panic_rc = NativeChunkPacket.testForcedPanic();
            r3 = panic_rc == 0;
            System.err.println("ERRPATH panic returned rc=" + panic_rc);
        } catch (Throwable t) {
            System.err.println("ERRPATH panic threw=" + t.getClass().getName());
            r3 = false;
        }
        sink += r3 ? 1 : 0;

        // 4. null/corrupt staging via predict on bad length (negative result code)
        int bad = NativeChunkPacket.predictOutputLen(0L, 100); // null address
        System.err.println("ERRPATH predict(null)=" + bad + " (expect negative)");
        sink += bad;

        // 5. server still alive: another normal packet
        SPacketChunkData ok2 = new SPacketChunkData(ch, 0);
        boolean r5 = NativeChunkPacket.populatePacket(ok2, ch, fx.mask);
        System.err.println("ERRPATH post-error ok=" + r5 + " (must be true)");
        if (!r5) System.exit(2);

        System.err.println("ERRPATH all done sink=" + sink);
    }
}
