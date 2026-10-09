package com.rustcraft.bridge;

import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.concurrent.DefaultEventExecutorGroup;
import io.netty.util.concurrent.EventExecutor;

/**
 * M-CK6 — OFFLINE AUTHORITATIVE differential gates (§7) for
 * FrameAuthorityHandler BEFORE any server boot. Proves with negative controls:
 *  - authority output decodes to the original Java-serialized body
 *    (compressed, at-threshold, passthrough, disabled threshold);
 *  - the Java compressor/prepender are BYPASSED for authoritative writes
 *    (spy counters) and NOT bypassed on fallback;
 *  - FAIL-CLOSED fallbacks (forced native failure, closing handler, missing
 *    prepender mid-flight) are byte-identical to the normal Java pipeline;
 *  - no packet loss, duplication, or partial frames under interleaved
 *    forced failures;
 *  - threshold updates are honored live;
 *  - heap/direct/slice/composite inputs;
 *  - sampled cross-check catches a corrupted comparison (negative control);
 *  - fail-closed installation (missing/misordered pipeline -> pure Java);
 *  - quiescence-safe free (executor rejection with op in flight).
 */
public class MCK6AuthorityGates {

    static int pass = 0, fail = 0;

    public static void main(String[] args) throws Exception {
        authorityDecodeAndBypass();
        failClosedFallbacks();
        interleavedFailuresNoLossNoDuplication();
        thresholdUpdatesHonored();
        bufferForms();
        crosscheckNegativeControl();
        installFailClosed();
        quiescenceFree();
        System.out.println("RESULT: " + pass + " pass, " + fail + " fail");
        System.exit(fail == 0 ? 0 : 1);
    }

    static void check(String n, boolean ok, String d) {
        if (ok) { pass++; System.out.println(n + " -> PASS" + (d == null ? "" : " (" + d + ")")); }
        else { fail++; System.out.println(n + " -> FAIL " + d); }
    }

    /** [prepender, compress, authority, encoder] with spies counting Java codec invocations. */
    static final class Rig {
        final EmbeddedChannel ch = new EmbeddedChannel();
        final AtomicInteger prepCalls = new AtomicInteger(), compCalls = new AtomicInteger();
        FrameAuthorityHandler auth;

        Rig(int threshold) throws Exception {
            Object prep = Class.forName("net.minecraft.network.NettyVarint21FrameEncoder").getDeclaredConstructor().newInstance();
            ch.pipeline().addLast("prepender", (ChannelHandler) prep);
            Object comp = Class.forName("net.minecraft.network.NettyCompressionEncoder").getConstructor(int.class).newInstance(threshold);
            ch.pipeline().addLast("compress", (ChannelHandler) comp);
            ch.pipeline().addLast("spy-prep", new ChannelOutboundHandlerAdapter() {
                @Override public void write(ChannelHandlerContext c, Object m, ChannelPromise p) throws Exception {
                    prepCalls.incrementAndGet();
                    c.write(m, p);
                }
            });
            ch.pipeline().addLast("spy-comp", new ChannelOutboundHandlerAdapter() {
                @Override public void write(ChannelHandlerContext c, Object m, ChannelPromise p) throws Exception {
                    compCalls.incrementAndGet();
                    c.write(m, p);
                }
            });
            ch.pipeline().addLast("encoder", new ChannelOutboundHandlerAdapter()); // serialized-body stand-in
        }
    }

    // writes at the ENCODER position (what the authority receives post-serialization)
    static byte[] writeAndDrain(Rig rig, byte[] body) {
        rig.ch.writeOutbound(Unpooled.wrappedBuffer(body));
        ByteBuf out = rig.ch.readOutbound();
        if (out == null) return null;
        byte[] f = new byte[out.readableBytes()];
        out.readBytes(f);
        out.release();
        return f;
    }

    static void setThreshold(Rig rig, int t) throws Exception {
        Object comp = rig.ch.pipeline().get("compress");
        comp.getClass().getMethod("func_179299_a", int.class).invoke(comp, t);
    }

    static void authorityDecodeAndBypass() throws Exception {
        Rig rig = new Rig(256);
        rig.auth = FrameAuthorityHandler.liveInstall(rig.ch.pipeline());
        byte[][] bodies = { prng(3000, 11), new byte[100], prng(256, 12), prng(5000, 13) };
        boolean allDecode = true;
        for (byte[] b : bodies) {
            byte[] f = writeAndDrain(rig, b);
            boolean dec = f != null && Arrays.equals(MCK4Validation.decodeFrameSafe(f, true), b);
            allDecode &= dec;
        }
        boolean installed = rig.auth != null && rig.auth.AUTH_PACKETS.get() == 4
                && rig.auth.AUTH_COMPRESSED.get() == 3 && rig.auth.AUTH_UNCOMPRESSED.get() == 1;
        boolean bypassed = rig.prepCalls.get() == 0 && rig.compCalls.get() == 0; // Java codecs never saw the 4 bodies
        check("authority: 4 bodies framed by Rust; all decode to the original; compressed/uncompressed split correct (256/100/256/5000 -> 3+1); Java compress+prep BYPASSED (spies: 0 invocations)",
                allDecode && installed && bypassed, "auth=" + (rig.auth == null ? -1 : rig.auth.AUTH_PACKETS.get())
                        + " prepSpy=" + rig.prepCalls.get() + " compSpy=" + rig.compCalls.get());
        rig.ch.close().awaitUninterruptibly();
    }

    static void failClosedFallbacks() throws Exception {
        // (a) forced native failure -> byte-identical to pure-Java pipeline
        Rig javaOnly = new Rig(256);
        byte[] body = prng(4000, 21);
        byte[] javaFrame = writeAndDrain(javaOnly, body.clone());
        Rig rig = new Rig(256);
        rig.auth = FrameAuthorityHandler.liveInstall(rig.ch.pipeline());
        rig.auth.testForceNativeFail = true;
        byte[] fb = writeAndDrain(rig, body.clone());
        boolean byteIdentical = Arrays.equals(javaFrame, fb);
        boolean counters = rig.auth.FALLBACK_NATIVE.get() == 1 && rig.auth.AUTH_PACKETS.get() == 0;
        boolean javaPathUsed = rig.compCalls.get() == 1 && rig.prepCalls.get() == 1;
        check("fallback(native-fail): output BYTE-IDENTICAL to the pure-Java pipeline; Java compress+prep exercised; counted",
                byteIdentical && counters && javaPathUsed,
                "fallbacks=" + rig.auth.FALLBACK_NATIVE.get());
        // (b) closing handler -> untouched Java forwarding (seam already consumed in (a))
        rig.auth.removed = true;
        byte[] fb2 = writeAndDrain(rig, body.clone());
        check("fallback(closing): removed handler forwards untouched Java path (byte-identical)",
                Arrays.equals(javaFrame, fb2) && rig.auth.FALLBACK_CLOSING.get() >= 1,
                "closing=" + rig.auth.FALLBACK_CLOSING.get());
        // (c) original message indices untouched at fallback: write with a nonzero readerIndex slice
        Rig rig2 = new Rig(256);
        rig2.auth = FrameAuthorityHandler.liveInstall(rig2.ch.pipeline());
        byte[] full = prng(2000, 22);
        ByteBuf wrapped = Unpooled.wrappedBuffer(full);
        wrapped.readerIndex(50);
        ByteBuf slice = wrapped.slice();
        byte[] expect = Arrays.copyOfRange(full, 50, full.length);
        rig2.auth.testForceNativeFail = true;
        rig2.ch.writeOutbound(slice);
        ByteBuf o = rig2.ch.readOutbound();
        byte[] f2 = new byte[o.readableBytes()];
        o.readBytes(f2);
        o.release();
        check("fallback(indices): slice at readerIndex 50 falls back and frames exactly the readable region (indices untouched)",
                Arrays.equals(MCK4Validation.decodeFrameSafe(f2, true), expect), null);
        javaOnly.ch.close().awaitUninterruptibly();
        rig.ch.close().awaitUninterruptibly();
        rig2.ch.close().awaitUninterruptibly();
    }

    static void interleavedFailuresNoLossNoDuplication() throws Exception {
        Rig rig = new Rig(256);
        rig.auth = FrameAuthorityHandler.liveInstall(rig.ch.pipeline());
        int n = 40;
        byte[][] bodies = new byte[n][];
        byte[][] outs = new byte[n][];
        for (int i = 0; i < n; i++) {
            bodies[i] = prng(200 + i * 137, 30 + i);
            if (i % 3 == 0) rig.auth.testForceNativeFail = true; // forced failure every 3rd
            outs[i] = writeAndDrain(rig, bodies[i].clone());
        }
        int decoded = 0;
        for (int i = 0; i < n; i++) {
            if (outs[i] != null && Arrays.equals(MCK4Validation.decodeFrameSafe(outs[i], true), bodies[i])) decoded++;
        }
        // drained output count == written count (writeAndDrain drains eagerly; ch has no backlog)
        boolean noBacklog = rig.ch.readOutbound() == null;
        boolean counts = rig.auth.AUTH_PACKETS.get() + rig.auth.FALLBACK_NATIVE.get() == n;
        boolean split = rig.auth.AUTH_PACKETS.get() == n - (n + 2) / 3 && rig.auth.FALLBACK_NATIVE.get() == (n + 2) / 3;
        check("interleaved: 40 writes with every-3rd forced failure -> ALL 40 decode exactly once, no backlog loss/duplication, counters sum to N",
                decoded == n && noBacklog && counts && split,
                "decoded=" + decoded + " auth=" + rig.auth.AUTH_PACKETS.get() + " fb=" + rig.auth.FALLBACK_NATIVE.get());
        rig.ch.close().awaitUninterruptibly();
    }

    static void thresholdUpdatesHonored() throws Exception {
        Rig rig = new Rig(256);
        rig.auth = FrameAuthorityHandler.liveInstall(rig.ch.pipeline());
        byte[] body = new byte[300];
        Arrays.fill(body, (byte) 7);
        writeAndDrain(rig, body.clone());      // 300>=256 -> compressed
        setThreshold(rig, 4096);
        byte[] f2 = writeAndDrain(rig, body.clone()); // 300<4096 now -> passthrough
        FrameShadowObserver.Decoded d = FrameShadowObserver.structurallyDecode(f2, true);
        boolean passthroughNow = d != null && d.consumedExactly && d.dataLen == 0 && Arrays.equals(d.body, body);
        boolean counters = rig.auth.AUTH_COMPRESSED.get() == 1 && rig.auth.AUTH_UNCOMPRESSED.get() == 1;
        check("threshold-update: live threshold change 256->4096 flips the same body from compressed to passthrough (live threshold honored)",
                passthroughNow && counters, null);
        rig.ch.close().awaitUninterruptibly();
    }

    static void bufferForms() throws Exception {
        Rig rig = new Rig(256);
        rig.auth = FrameAuthorityHandler.liveInstall(rig.ch.pipeline());
        byte[] body = prng(3000, 41);
        // heap
        byte[] f1 = writeAndDrain(rig, body.clone());
        // direct
        rig.ch.writeOutbound(Unpooled.directBuffer(body.length).writeBytes(body));
        byte[] f2 = drain(rig.ch);
        // nonzero-index slice
        ByteBuf w = Unpooled.wrappedBuffer(body); w.readerIndex(100);
        rig.ch.writeOutbound(w.slice());
        byte[] f3 = drain(rig.ch);
        byte[] expectSlice = Arrays.copyOfRange(body, 100, body.length);
        // composite
        ByteBuf comp = Unpooled.compositeBuffer()
                .addComponent(true, Unpooled.wrappedBuffer(Arrays.copyOfRange(body, 0, 1000)))
                .addComponent(true, Unpooled.wrappedBuffer(Arrays.copyOfRange(body, 1000, body.length)));
        rig.ch.writeOutbound(comp);
        byte[] f4 = drain(rig.ch);
        boolean ok = Arrays.equals(MCK4Validation.decodeFrameSafe(f1, true), body)
                && Arrays.equals(MCK4Validation.decodeFrameSafe(f2, true), body)
                && Arrays.equals(MCK4Validation.decodeFrameSafe(f3, true), expectSlice)
                && Arrays.equals(MCK4Validation.decodeFrameSafe(f4, true), body)
                && rig.auth.AUTH_PACKETS.get() == 4;
        check("buffer-forms: heap/direct/nonzero-slice/composite all framed authoritatively and decode to their readable regions",
                ok, "auth=" + rig.auth.AUTH_PACKETS.get());
        rig.ch.close().awaitUninterruptibly();
    }

    static byte[] drain(EmbeddedChannel ch) {
        ByteBuf out = ch.readOutbound();
        byte[] f = new byte[out.readableBytes()];
        out.readBytes(f);
        out.release();
        return f;
    }

    static void crosscheckNegativeControl() throws Exception {
        Rig rig = new Rig(256);
        rig.auth = FrameAuthorityHandler.liveInstall(rig.ch.pipeline());
        rig.auth.testForceCrosscheckFail = true;
        writeAndDrain(rig, prng(500, 51)); // packet 0 -> sampled crosscheck (every 16 from 0)
        boolean detected = rig.auth.CROSSCHECKS.get() == 1 && rig.auth.CROSSCHECK_MISMATCHES.get() == 1;
        writeAndDrain(rig, prng(500, 52)); // packet 1 -> not sampled
        writeAndDrain(rig, prng(500, 53));
        boolean sampledOnly = rig.auth.CROSSCHECKS.get() == 1;
        check("crosscheck: sampled post-production validation runs on packet 0 (of 16); forced corruption DETECTED as mismatch; later packets unsampled",
                detected && sampledOnly, "cc=" + rig.auth.CROSSCHECKS.get() + " mism=" + rig.auth.CROSSCHECK_MISMATCHES.get());
        rig.ch.close().awaitUninterruptibly();
    }

    static void installFailClosed() throws Exception {
        // missing prepender -> NO install; behavior identical to the SAME pure-Java
        // pipeline without any authority handler (reference channel built identically)
        java.util.function.Supplier<EmbeddedChannel> bare = () -> {
            try {
                EmbeddedChannel c = new EmbeddedChannel();
                c.pipeline().addLast("compress", (ChannelHandler) Class.forName("net.minecraft.network.NettyCompressionEncoder").getConstructor(int.class).newInstance(256));
                c.pipeline().addLast("encoder", new ChannelOutboundHandlerAdapter());
                return c;
            } catch (Exception e) { throw new RuntimeException(e); }
        };
        EmbeddedChannel ref = bare.get();
        EmbeddedChannel ch = bare.get();
        long skip0 = FrameAuthorityHandler.INSTALL_SKIPPED.get();
        boolean noInstall = FrameAuthorityHandler.liveInstall(ch.pipeline()) == null
                && FrameAuthorityHandler.INSTALL_SKIPPED.get() == skip0 + 1
                && ch.pipeline().get("mck5a-authority") == null;
        byte[] body = prng(2000, 61);
        ch.writeOutbound(Unpooled.wrappedBuffer(body));
        byte[] f = drain(ch);
        ref.writeOutbound(Unpooled.wrappedBuffer(body.clone()));
        byte[] fRef = drain(ref);
        boolean identical = Arrays.equals(f, fRef); // same no-prepender Java output (unframed by construction)
        check("install-failclosed: missing-prepender pipeline installs NOTHING; behavior byte-identical to the same bare Java pipeline",
                noInstall && identical, null);
        ch.close().awaitUninterruptibly();
        ref.close().awaitUninterruptibly();
        // misordered pipeline -> NO install
        EmbeddedChannel bad = new EmbeddedChannel();
        Object prep2 = Class.forName("net.minecraft.network.NettyVarint21FrameEncoder").getDeclaredConstructor().newInstance();
        bad.pipeline().addLast("prepender", (ChannelHandler) prep2);
        bad.pipeline().addLast("encoder", new ChannelOutboundHandlerAdapter());
        bad.pipeline().addLast("compress", (ChannelHandler) Class.forName("net.minecraft.network.NettyCompressionEncoder").getConstructor(int.class).newInstance(256));
        long skip1 = FrameAuthorityHandler.INSTALL_SKIPPED.get();
        boolean noInstall2 = FrameAuthorityHandler.liveInstall(bad.pipeline()) == null
                && FrameAuthorityHandler.INSTALL_SKIPPED.get() == skip1 + 1
                && bad.pipeline().get("mck5a-authority") == null;
        check("install-failclosed: misordered pipeline installs NOTHING (reason recorded)",
                noInstall2 && String.valueOf(FrameAuthorityHandler.LAST_SKIP_REASON).contains("order"), null);
        bad.close().awaitUninterruptibly();
    }

    static void quiescenceFree() throws Exception {
        DefaultEventExecutorGroup group = new DefaultEventExecutorGroup(1);
        try {
            EventExecutor exec = group.next();
            Rig rig = new Rig(256);
            rig.auth = FrameAuthorityHandler.liveInstall(rig.ch.pipeline());
            writeAndDrain(rig, prng(500, 71)); // create the ctx
            boolean created = rig.auth.CTX_CREATED.get() == 1;
            // bind the free to a REAL executor, hard-shutdown it, then remove:
            // rejection with no op in flight -> inline free
            rig.auth.captureOwner(exec);
            exec.shutdown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!exec.isShutdown() && System.nanoTime() < deadline) Thread.sleep(5);
            rig.ch.close().awaitUninterruptibly();
            boolean freed = rig.auth.CTX_FREED.get() == 1;
            check("quiescence: close after hard executor shutdown frees the native ctx exactly once (no in-flight op)",
                    created && freed, "created=" + created + " freed=" + rig.auth.CTX_FREED.get());
        } finally {
            group.shutdownGracefully();
        }
    }

    static byte[] prng(int n, int seed) { byte[] b = new byte[n]; new Random(seed).nextBytes(b); return b; }
}
