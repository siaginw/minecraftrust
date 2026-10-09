package com.rustcraft.oracle;

import com.rustcraft.bridge.NativeChunkPacket;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.init.Bootstrap;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.server.SPacketChunkData;
import net.minecraft.world.chunk.Chunk;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * M1-final tail-latency study (operator directive 2026-09-19).
 *
 * Motivation: M14R2-fullcorpus-deep.csv showed pooled eligible-class p99
 * ~5.3% WORSE for native despite -29.9% p50. Single JVM, fixed pass order
 * (java-then-native). This study asks: is the p99 difference reproducible,
 * unresolved, or absent — across fresh JVM processes with BALANCED pass
 * order (half java-first, half native-first), and with held-out fixtures
 * around the 8192-byte eligibility boundary.
 *
 * Scopes per fixture (same-run, equal-scope):
 *   SCOPE-C      JAVA    ctor(mask)+serialize                    (mode OFF)
 *   SCOPE-C-N-HA NATIVE  ctor(0)+populatePacket+serialize        (gate forced 0, as M14R2)
 *   SCOPE-SELECT SELECT  PRODUCTION selection path with the REAL 8192 gate:
 *                       ctor(0)+populatePacket; if declined -> ctor(mask);
 *                       serialize. Records the routing decision, so the
 *                       complete cost of deciding a small packet stays Java
 *                       (staging + predictOutputLen + Java ctor) is measured.
 *
 * Usage: TailLatencyStudy <out.csv> <java-first|native-first>
 * Driver: tools/tail-latency-study.sh (4 fresh JVMs, alternating order).
 */
public final class TailLatencyStudy {

    static final int WARM = 500, SAMPLES = 2000;

    public static void main(String[] args) throws Exception {
        Bootstrap.func_151354_b();
        String out = args.length > 0 ? args[0] : "machine/raw/M1F-tail.csv";
        boolean javaFirst = !(args.length > 1 && "native-first".equals(args[1]));
        long sink = 0;
        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(Paths.get(out)))) {
            pw.println("scope,impl,fixture,mask_hex,sections,full_chunk,trial,elapsed_ns,payload_bytes,decision");
            for (Object[] fe : fixtures()) {
                @SuppressWarnings("unchecked")
                M14RParityHarness.Fixture fx = (M14RParityHarness.Fixture) fe[0];
                String name = (String) fe[1];
                sink += bench(pw, fx, name, javaFirst);
            }
        }
        System.err.println("[TailLatencyStudy] order=" + (javaFirst ? "java-first" : "native-first")
                + " sink=" + sink + " -> " + out);
    }

    /** 24 original corpus fixtures + 8 held-out boundary fixtures. */
    static List<Object[]> fixtures() {
        List<Object[]> l = new ArrayList<>();
        for (int i = 0; i < 8; i++)
            l.add(new Object[]{M14RParityHarness.buildDeterministic(i), "det" + i});
        for (int want = 1; want <= 8; want *= 2) {
            Random rng = new Random(9000 + want);
            for (int k = 0; k < 4; k++)
                l.add(new Object[]{M14RParityHarness.buildFuzz(rng, k), "sweep" + want + "_" + k});
        }
        // held-out boundary fixtures: k populated 4-bit sections, seeds never
        // used by the corpus. payload ~ k*2.1KB + header -> 4.3k/6.4k/8.5k/10.6k
        for (int k = 1; k <= 5; k++) {
            l.add(new Object[]{boundaryFixture(k, 4700 + k), "bnd_k" + k + "a"});
            l.add(new Object[]{boundaryFixture(k, 4800 + k), "bnd_k" + k + "b"});
        }
        return l;
    }

    static M14RParityHarness.Fixture boundaryFixture(int k, int seed) {
        M14RParityHarness.Fixture fx = new M14RParityHarness.Fixture();
        fx.seed = seed;
        fx.mask = (1 << k) - 1; // low k sections requested; k < 16 => not full
        fx.sectionState = new int[16];
        for (int s = 0; s < k; s++) fx.sectionState[s] = 2;
        for (int s = k; s < 16; s++) fx.sectionState[s] = 0; // sentinel
        fx.fullChunk = false;
        fx.withTE = false;
        fx.profile = "boundary k=" + k;
        return fx;
    }

    static long bench(PrintWriter pw, M14RParityHarness.Fixture fx, String name, boolean javaFirst)
            throws Exception {
        Chunk ch = M14RParityHarness.createMockChunk(fx);
        int mask = fx.mask;
        long sink = 0;
        // Untimed reference payload (built once, before any pass, order-independent)
        NativeChunkPacket.setRuntimeMode("OFF");
        SPacketChunkData refP = new SPacketChunkData(ch, mask);
        ByteBuf rb = Unpooled.buffer(262144);
        PacketBuffer rpb = new PacketBuffer(rb);
        refP.func_148840_b(rpb);
        Holder.refLen = rb.readableBytes();
        Holder.ref = new byte[Holder.refLen];
        rb.getBytes(0, Holder.ref);
        rb.release();
        if (javaFirst) {
            sink += javaPass(pw, ch, mask, fx, name);
            sink += nativePass(pw, ch, mask, fx, name);
        } else {
            sink += nativePass(pw, ch, mask, fx, name);
            sink += javaPass(pw, ch, mask, fx, name);
        }
        sink += selectPass(pw, ch, mask, fx, name);
        return sink;
    }

    static long javaPass(PrintWriter pw, Chunk ch, int mask, M14RParityHarness.Fixture fx, String name)
            throws Exception {
        NativeChunkPacket.setRuntimeMode("OFF");
        ByteBuf buf = Unpooled.buffer(262144);
        PacketBuffer pb = new PacketBuffer(buf);
        long sink = 0;
        try {
            for (int i = 0; i < WARM; i++) {
                SPacketChunkData p = new SPacketChunkData(ch, mask);
                buf.clear(); pb = new PacketBuffer(buf);
                p.func_148840_b(pb);
                sink += buf.readableBytes();
            }
            for (int t = 0; t < SAMPLES; t++) {
                long t0 = System.nanoTime();
                SPacketChunkData p = new SPacketChunkData(ch, mask);
                buf.clear(); pb = new PacketBuffer(buf);
                p.func_148840_b(pb);
                long el = System.nanoTime() - t0;
                int len = buf.readableBytes();
                pw.println("SCOPE-C,JAVA," + name + ",0x" + Integer.toHexString(mask) + ","
                        + Integer.bitCount(mask) + "," + fx.fullChunk + "," + t + "," + el + "," + len + ",");
                sink += len;
            }
        } finally { buf.release(); }
        return sink;
    }

    static long nativePass(PrintWriter pw, Chunk ch, int mask, M14RParityHarness.Fixture fx, String name)
            throws Exception {
        NativeChunkPacket.setRuntimeMode("ON_EXPERIMENTAL");
        NativeChunkPacket.setHandoffMode("A");
        int gateSave = NativeChunkPacket.M1_NATIVE_MIN_WIRE_BYTES;
        NativeChunkPacket.setMinWireBytes(0); // measure all; rule applied offline/SELECT
        ByteBuf buf = Unpooled.buffer(262144);
        PacketBuffer pb = new PacketBuffer(buf);
        long sink = 0;
        try {
            for (int i = 0; i < WARM; i++) {
                SPacketChunkData p = new SPacketChunkData(ch, 0);
                NativeChunkPacket.populatePacket(p, ch, mask);
                buf.clear(); pb = new PacketBuffer(buf);
                p.func_148840_b(pb);
                sink += buf.readableBytes();
            }
            for (int t = 0; t < SAMPLES; t++) {
                long t0 = System.nanoTime();
                SPacketChunkData p = new SPacketChunkData(ch, 0);
                boolean ok = NativeChunkPacket.populatePacket(p, ch, mask);
                buf.clear(); pb = new PacketBuffer(buf);
                p.func_148840_b(pb);
                long el = System.nanoTime() - t0;
                int len = buf.readableBytes();
                if (!ok) throw new AssertionError(name + ": populatePacket=false (forced native)");
                if (t % 200 == 0) {
                    byte[] nb = new byte[len];
                    buf.getBytes(0, nb);
                    if (len != Holder.refLen || !Arrays.equals(Holder.ref, nb))
                        throw new AssertionError(name + ": payload mismatch len " + len + " vs " + Holder.refLen);
                }
                pw.println("SCOPE-C-N-HA,NATIVE," + name + ",0x" + Integer.toHexString(mask) + ","
                        + Integer.bitCount(mask) + "," + fx.fullChunk + "," + t + "," + el + "," + len + ",");
                sink += len;
            }
        } finally {
            buf.release();
            NativeChunkPacket.setMinWireBytes(gateSave);
            NativeChunkPacket.setRuntimeMode("OFF");
        }
        return sink;
    }

    /** Production-realism: the REAL 8192 gate routes each packet; measures the
     * complete decision path including the staging+predict cost paid by
     * ineligible packets before they fall back to the Java constructor. */
    static long selectPass(PrintWriter pw, Chunk ch, int mask, M14RParityHarness.Fixture fx, String name)
            throws Exception {
        NativeChunkPacket.setRuntimeMode("ON_EXPERIMENTAL");
        NativeChunkPacket.setHandoffMode("A");
        ByteBuf buf = Unpooled.buffer(262144);
        PacketBuffer pb = new PacketBuffer(buf);
        long sink = 0;
        try {
            for (int i = 0; i < WARM; i++) {
                SPacketChunkData p = new SPacketChunkData(ch, 0);
                boolean ok = NativeChunkPacket.populatePacket(p, ch, mask);
                if (!ok) p = new SPacketChunkData(ch, mask);
                buf.clear(); pb = new PacketBuffer(buf);
                p.func_148840_b(pb);
                sink += buf.readableBytes();
            }
            for (int t = 0; t < SAMPLES; t++) {
                long t0 = System.nanoTime();
                SPacketChunkData p = new SPacketChunkData(ch, 0);
                boolean ok = NativeChunkPacket.populatePacket(p, ch, mask);
                if (!ok) p = new SPacketChunkData(ch, mask);
                buf.clear(); pb = new PacketBuffer(buf);
                p.func_148840_b(pb);
                long el = System.nanoTime() - t0;
                int len = buf.readableBytes();
                if (t % 200 == 0) {
                    byte[] nb = new byte[len];
                    buf.getBytes(0, nb);
                    if (len != Holder.refLen || !Arrays.equals(Holder.ref, nb))
                        throw new AssertionError(name + ": SELECT payload mismatch len " + len + " vs " + Holder.refLen);
                }
                pw.println("SCOPE-SELECT,SELECT," + name + ",0x" + Integer.toHexString(mask) + ","
                        + Integer.bitCount(mask) + "," + fx.fullChunk + "," + t + "," + el + "," + len + ","
                        + (ok ? "native" : "java_fallback"));
                sink += len;
            }
        } finally {
            buf.release();
            NativeChunkPacket.setRuntimeMode("OFF");
        }
        return sink;
    }

    /** cross-pass reference holder (java pass runs first or after native pass
     *  depending on order; SELECT always runs after both so the reference exists). */
    static class Holder {
        static byte[] ref;
        static int refLen;
    }
}
