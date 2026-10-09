package com.rustcraft.interop;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Shared fixture list (moved from InteropMain for the M2C offline regression). */
public final class InteropMainFixtures {
    private InteropMainFixtures() {}

    public static List<Object[]> fixtures() throws Exception { // NOPMD
        List<Object[]> fx = new ArrayList<>();
        fx.add(new Object[]{"empty", new byte[0]});
        fx.add(new Object[]{"single", new byte[]{7}});
        Random rng = new Random(42);
        fx.add(new Object[]{"adjacent-255", filler(rng, 255)});
        fx.add(new Object[]{"adjacent-256", filler(rng, 256)});
        fx.add(new Object[]{"adjacent-257", filler(rng, 257)});
        byte[] z = new byte[64 * 1024]; fx.add(new Object[]{"zeros-64k", z});
        byte[] r = new byte[64 * 1024]; rng.nextBytes(r); fx.add(new Object[]{"random-64k-incompressible", r});
        byte[] semi = new byte[256 * 1024];
        for (int i = 0; i < semi.length; i++) semi[i] = (byte) (i % 97);
        fx.add(new Object[]{"semi-256k", semi});
        byte[] big = new byte[1 << 20]; fx.add(new Object[]{"zeros-1M", big});
        // legal packet payloads: serialized vanilla SPacketChunkData from the
        // committed oracle corpus builders (real chunk content; package-private
        // Fixture accessed as Object)
        Object f;
        f = com.rustcraft.oracle.M14RParityHarness.buildDeterministic(1);
        fx.add(new Object[]{"chunk-det1", serializeChunk(f)});
        f = com.rustcraft.oracle.M14RParityHarness.buildDeterministic(4);
        fx.add(new Object[]{"chunk-det4", serializeChunk(f)});
        f = com.rustcraft.oracle.M14RParityHarness.buildDeterministic(7);
        fx.add(new Object[]{"chunk-det7", serializeChunk(f)});
        return fx;
    }

    static byte[] filler(Random rng, int n) {
        byte[] b = new byte[n];
        rng.nextBytes(b);
        return b;
    }

    static byte[] serializeChunk(Object fixture) throws Exception {
        com.rustcraft.oracle.M14RParityHarness.Fixture fx =
                (com.rustcraft.oracle.M14RParityHarness.Fixture) fixture;
        net.minecraft.world.chunk.Chunk ch = com.rustcraft.oracle.M14RParityHarness.createMockChunk(fx);
        com.rustcraft.bridge.NativeChunkPacket.setRuntimeMode("OFF");
        net.minecraft.network.play.server.SPacketChunkData p =
                new net.minecraft.network.play.server.SPacketChunkData(ch, fx.mask);
        return com.rustcraft.oracle.M14RParityHarness.serialize(p);
    }
}
