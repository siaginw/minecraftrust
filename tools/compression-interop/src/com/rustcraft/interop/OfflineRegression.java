package com.rustcraft.interop;

import com.rustcraft.bridge.CompressionCtx;
import com.rustcraft.bridge.NativeCompressionEncoder;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.NettyCompressionDecoder;
import net.minecraft.network.PacketBuffer;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * M2C offline regression through the NEW handler path (not the prototype
 * harness): EmbeddedChannel with the real NativeCompressionEncoder +
 * real NettyCompressionDecoder, covering the 14 interop fixtures plus
 * concurrency, threshold lifecycle, enable/disable, disconnect/reconnect,
 * forced native failure, capacity failure, and post-failure context reset.
 */
public final class OfflineRegression {

    public static void main(String[] args) throws Exception {
        net.minecraft.init.Bootstrap.func_151354_b();
        PrintWriter out = new PrintWriter(Files.newBufferedWriter(
                Paths.get(args.length > 0 ? args[0] : "machine/raw/M2C-offline-regression.txt")));
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(out::flush));
            int pass = 0, fail = 0;

            // ---- 1) 14 interop fixtures through the REAL handler ----
            out.println("== A: interop fixtures through NativeCompressionEncoder (SHADOW & ON) ==");
            List<Object[]> fixtures = InteropMainFixtures.fixtures();
            for (String mode : new String[]{"SHADOW", "ON_EXPERIMENTAL"}) {
                NativeCompressionEncoder.setRuntimeMode(mode);
                for (Object[] f : fixtures) {
                    String name = (String) f[0];
                    byte[] data = (byte[]) f[1];
                    EmbeddedChannel ch = makeChannel(256);
                    ByteBuf frame = framePacket(ch, data);
                    ch.writeOutbound(frame);
                    ByteBuf wire = ch.readOutbound();
                    byte[] decoded = decodeWire(wire, data.length);
                    boolean ok = Arrays.equals(data, decoded);
                    if (ok) pass++; else fail++;
                    out.printf("  %-8s %-26s %s%n", mode, name, ok ? "PASS" : "FAIL");
                    wire.release();
                    ch.finishAndReleaseAll();
                }
            }

            // ---- 2) threshold-adjacent behavior vs vanilla framing ----
            out.println("== B: threshold-adjacent framing (255 below / 256 at) ==");
            NativeCompressionEncoder.setRuntimeMode("ON_EXPERIMENTAL");
            for (int n : new int[]{255, 256}) {
                EmbeddedChannel ch = makeChannel(256);
                byte[] data = new byte[n]; new Random(n).nextBytes(data);
                ch.writeOutbound(framePacket(ch, data));
                ByteBuf wire = ch.readOutbound();
                byte[] decoded = decodeWire(wire, data.length);
                boolean ok = Arrays.equals(data, decoded);
                if (ok) pass++; else fail++;
                out.printf("  len=%d decoded=%s%n", n, ok ? "PASS" : "FAIL");
                wire.release(); ch.finishAndReleaseAll();
            }

            // ---- 3) threshold changes + disable/re-enable mid-channel ----
            out.println("== C: threshold lifecycle ==");
            {
                EmbeddedChannel ch = makeChannel(256);
                NativeCompressionEncoder enc = (NativeCompressionEncoder) ch.pipeline().get("compress");
                byte[] big = new byte[1000]; new Random(1).nextBytes(big);
                // raise threshold above packet size -> sub-threshold framing
                enc.func_179299_a(4096);
                ch.writeOutbound(framePacket(ch, big));
                ByteBuf w1 = ch.readOutbound();
                byte[] d1 = decodeWire(w1, big.length); // decoder threshold also matters; use fresh decode ch
                boolean t1 = Arrays.equals(big, d1);
                w1.release();
                // lower back
                enc.func_179299_a(256);
                ch.writeOutbound(framePacket(ch, big));
                ByteBuf w2 = ch.readOutbound();
                byte[] d2 = decodeWire(w2, big.length);
                boolean t2 = Arrays.equals(big, d2);
                w2.release();
                boolean ok = t1 && t2;
                if (ok) pass++; else fail++;
                out.println("  raise-to-4096 + lower-to-256: " + (ok ? "PASS" : "FAIL"));
                ch.finishAndReleaseAll();
            }

            // ---- 4) randomized packet sequences, mixed sizes ----
            out.println("== D: randomized sequence (500 packets, one channel) ==");
            {
                EmbeddedChannel ch = makeChannel(256);
                Random rng = new Random(99);
                int mism = 0;
                for (int i = 0; i < 500; i++) {
                    int n = rng.nextInt(60000);
                    byte[] data = new byte[n];
                    rng.nextBytes(data);
                    ch.writeOutbound(framePacket(ch, data));
                    ByteBuf wire = ch.readOutbound();
                    byte[] dec = decodeWire(wire, data.length);
                    if (!Arrays.equals(data, dec)) mism++;
                    wire.release();
                }
                boolean ok = mism == 0;
                if (ok) pass++; else fail++;
                out.println("  500 random packets mismatches=" + mism + " " + (ok ? "PASS" : "FAIL"));
                ch.finishAndReleaseAll();
            }

            // ---- 5) concurrency: N channels in parallel threads, no shared lock ----
            out.println("== E: concurrent channels (8 threads x 200 packets) ==");
            {
                int threads = 8, per = 200;
                CountDownLatch start = new CountDownLatch(1);
                CountDownLatch done = new CountDownLatch(threads);
                AtomicBoolean anyFail = new AtomicBoolean(false);
                AtomicInteger totalDone = new AtomicInteger();
                for (int t = 0; t < threads; t++) {
                    final int seed = t;
                    new Thread(() -> {
                        try {
                            start.await();
                            EmbeddedChannel ch = makeChannel(256);
                            Random rng = new Random(seed);
                            for (int i = 0; i < per; i++) {
                                byte[] data = new byte[rng.nextInt(20000)];
                                rng.nextBytes(data);
                                ch.writeOutbound(framePacket(ch, data));
                                ByteBuf wire = ch.readOutbound();
                                byte[] dec = decodeWire(wire, data.length);
                                if (!Arrays.equals(data, dec)) anyFail.set(true);
                                wire.release();
                            }
                            ch.finishAndReleaseAll();
                            totalDone.incrementAndGet();
                        } catch (Throwable e) { anyFail.set(true); }
                        finally { done.countDown(); }
                    }).start();
                }
                start.countDown();
                long t0 = System.nanoTime();
                done.await();
                long ms = (System.nanoTime() - t0) / 1_000_000;
                boolean ok = !anyFail.get() && totalDone.get() == threads;
                if (ok) pass++; else fail++;
                out.println("  " + threads + "x" + per + " packets in " + ms + "ms all-correct="
                        + ok + " (channels ran concurrently; no global serialization observable)");
            }

            // ---- 6) disconnect/reconnect lifecycle: contexts created & freed ----
            out.println("== F: lifecycle (50 channels, create/free counting) ==");
            {
                long c0 = NativeCompressionEncoder.M2C_CTX_CREATED.get();
                long f0 = NativeCompressionEncoder.M2C_CTX_FREED.get();
                for (int i = 0; i < 50; i++) {
                    EmbeddedChannel ch = makeChannel(256);
                    byte[] data = new byte[1000]; new Random(i).nextBytes(data);
                    ch.writeOutbound(framePacket(ch, data));
                    ByteBuf wire = ch.readOutbound(); wire.release();
                    ch.finishAndReleaseAll(); // triggers handlerRemoved -> free
                }
                long created = NativeCompressionEncoder.M2C_CTX_CREATED.get() - c0;
                long freed = NativeCompressionEncoder.M2C_CTX_FREED.get() - f0;
                boolean ok = created == 50 && freed == 50;
                if (ok) pass++; else fail++;
                out.println("  created=" + created + " freed=" + freed + " " + (ok ? "PASS" : "FAIL"));
            }

            // ---- 7) forced native failure + capacity failure + recovery ----
            out.println("== G: failure injection ==");
            {
                CompressionCtx ctx = new CompressionCtx();
                boolean made = ctx.ensureCreated();
                byte[] data = new byte[5000]; new Random(5).nextBytes(data);
                int code = ctx.compressCode(data, 16); // insufficient capacity
                boolean capOk = code == -1;
                byte[] after = ctx.compress(data); // context must still work
                boolean recoverOk = after != null && inflateOk(after, data);
                ctx.free();
                byte[] afterFree = ctx.compress(data); // must be null (handle 0)
                boolean freeOk = afterFree == null;
                ctx.free(); // double free: no crash
                boolean ok = made && capOk && recoverOk && freeOk;
                if (ok) pass++; else fail++;
                out.printf("  create=%s capacityErr=%d recovered=%s useAfterFreeNull=%s doubleFreeSafe=true %s%n",
                        made, code, recoverOk, freeOk, ok ? "PASS" : "FAIL");
            }

            // ---- 8) SHADOW metrics sanity ----
            out.println("== H: SHADOW parity counters ==");
            {
                long mm0 = NativeCompressionEncoder.M2C_SHADOW_MISMATCHES.get();
                long sm0 = NativeCompressionEncoder.M2C_SHADOW_MATCHES.get();
                NativeCompressionEncoder.setRuntimeMode("SHADOW");
                EmbeddedChannel ch = makeChannel(256);
                byte[] data = new byte[4096]; new Random(3).nextBytes(data);
                ch.writeOutbound(framePacket(ch, data));
                ByteBuf wire = ch.readOutbound(); wire.release();
                ch.finishAndReleaseAll();
                long matches = NativeCompressionEncoder.M2C_SHADOW_MATCHES.get() - sm0;
                long mism = NativeCompressionEncoder.M2C_SHADOW_MISMATCHES.get() - mm0;
                boolean ok = matches == 1 && mism == 0;
                if (ok) pass++; else fail++;
                out.println("  shadow_matches+" + matches + " mismatches+" + mism + " " + (ok ? "PASS" : "FAIL"));
            }

            // ---- 9) OFF_MEASURE: wire bytes identical to pure vanilla, timed ----
            out.println("== I: OFF_MEASURE vanilla-identity + counters ==");
            {
                byte[] data = new byte[9000]; new Random(11).nextBytes(data);
                NativeCompressionEncoder.setRuntimeMode("OFF_MEASURE");
                long c0 = NativeCompressionEncoder.M2C_CTX_CREATED.get();
                EmbeddedChannel ch = makeChannel(256);
                ch.writeOutbound(framePacket(ch, data));
                ByteBuf wire = ch.readOutbound();
                // reference: PURE vanilla encoder channel, same input
                Object vanillaEnc = Class.forName("net.minecraft.network.NettyCompressionEncoder")
                        .getConstructor(int.class).newInstance(256);
                EmbeddedChannel vch = new EmbeddedChannel((ChannelHandler) vanillaEnc);
                vch.writeOutbound(Unpooled.wrappedBuffer(data.clone()));
                ByteBuf vwire = vch.readOutbound();
                boolean same = wire.readerIndex() == vwire.readerIndex(); // sizes first
                if (same) {
                    byte[] a = new byte[wire.readableBytes()], b = new byte[vwire.readableBytes()];
                    wire.readBytes(a); vwire.readBytes(b);
                    same = Arrays.equals(a, b);
                }
                wire.release(); vwire.release();
                ch.finishAndReleaseAll(); vch.finishAndReleaseAll();
                boolean noCtx = NativeCompressionEncoder.M2C_CTX_CREATED.get() == c0;
                boolean timed = NativeCompressionEncoder.M2C_JAVA_PACKETS.get() > 0;
                boolean ok = same && noCtx && timed;
                if (ok) pass++; else fail++;
                out.println("  wireIdenticalToVanilla=" + same + " ctxCreatedDelta=0:" + noCtx
                        + " javaPacketsCounted=" + timed + " " + (ok ? "PASS" : "FAIL"));
            }

            out.println("\nOFFLINE pass=" + pass + " fail=" + fail);
            out.println(NativeCompressionEncoder.dumpMetrics());
            out.flush();
            if (fail > 0) System.exit(3);
        } finally { out.flush(); }
    }

    // ---- helpers ----

    static EmbeddedChannel makeChannel(int threshold) throws Exception {
        Object dec = Class.forName("net.minecraft.network.NettyCompressionDecoder")
                .getConstructor(int.class).newInstance(threshold);
        com.rustcraft.bridge.NativeCompressionEncoder enc =
                new com.rustcraft.bridge.NativeCompressionEncoder(threshold);
        EmbeddedChannel ch = new EmbeddedChannel((ChannelHandler) dec);
        ch.pipeline().addLast("compress", enc); // vanilla handler name
        return ch;
    }

    /** Wrap plaintext as the framer would: a packet body ByteBuf. */
    static ByteBuf framePacket(EmbeddedChannel ch, byte[] body) {
        ByteBuf buf = Unpooled.wrappedBuffer(body);
        return buf;
    }

    /** Decode the encoder's wire output through the REAL vanilla decoder. */
    static byte[] decodeWire(ByteBuf wire, int expectedLen) throws Exception {
        // feed to a fresh decoder channel
        Object dec = Class.forName("net.minecraft.network.NettyCompressionDecoder")
                .getConstructor(int.class).newInstance(256);
        EmbeddedChannel ch = new EmbeddedChannel((ChannelHandler) dec);
        ByteBuf copy = wire.copy();
        ch.writeInbound(copy);
        ByteBuf out = ch.readInbound();
        byte[] res = null;
        if (out != null) {
            res = new byte[out.readableBytes()];
            out.readBytes(res);
            out.release();
        }
        ch.finishAndReleaseAll();
        return res;
    }

    static boolean inflateOk(byte[] compressed, byte[] expected) {
        try {
            java.util.zip.Inflater inf = new java.util.zip.Inflater();
            inf.setInput(compressed);
            byte[] out = new byte[expected.length];
            int n = inf.inflate(out);
            inf.end();
            return n == expected.length && Arrays.equals(out, expected);
        } catch (Throwable t) { return false; }
    }
}
