package com.rustcraft.bridge;

import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.concurrent.DefaultEventExecutorGroup;
import io.netty.util.concurrent.EventExecutor;

/**
 * M-CK5 Checkpoint B — independent offline DIFFERENTIAL harness proving the
 * FrameShadowObserver does not change Java behavior, plus lifecycle/quiescence
 * and fault-injection gates.
 *
 * Every differential case runs the SAME inputs through:
 *   Pipeline A: actual installed Java framing pipeline, observer ABSENT/OFF.
 *   Pipeline B: same installed pipeline + observer installed and SHADOW-enabled.
 * and requires identical Java output bytes, ordering, promise outcomes, and
 * input-buffer release (refCnt), plus each observer comparison recovering the
 * original input independently.
 *
 * All fault injection is package-private/property-immune. Ownership/lifecycle
 * tests use a REAL single-thread executor (EmbeddedEventLoop's permissive
 * membership is never used as evidence).
 */
public class MCK5ObserverGates {

    static int pass = 0, fail = 0;

    public static void main(String[] args) throws Exception {
        Class.forName("net.minecraft.init.Bootstrap").getMethod("func_151354_b").invoke(null);
        M4PacketParityHarness.initReflectionPublic();

        offMakesZeroNativeCalls();
        compressionStatesAndDifferential();
        transitionsAndOrdering();
        multiChannelIndependence();
        bufferForms();
        frameAnomalyDetector();
        limitsAndSkips();
        ctxFailureAndCorruption();
        pairingCorruption();
        javaEncodeFailureUnpaired();
        retainedAWhileB();
        observerLifecycleQuiescence();
        handlerQuiescenceRejection();
        liveInstallPositionsAndFailClosed();
        droppedFramePairingHardening();

        System.out.println("RESULT: " + pass + " pass, " + fail + " fail");
        System.exit(fail == 0 ? 0 : 1);
    }

    /** Regression for the MCK5LR live defect class: on Forge, the FML|MP
     *  multipart HEADER write was captured but its frame never reached the
     *  frame observer (dropped/bypassed between capture and the prepender),
     *  and the NEXT message's frame was then absorbed by the stale pending ->
     *  false mismatch. Deterministic reproduction: a handler placed tail-most
     *  swallows the FIRST message's frame entirely. */
    static void droppedFramePairingHardening() throws Exception {
        EmbeddedChannel ch = javaPipeline(256);
        ch.pipeline().addLast("mck5-swallow", new io.netty.channel.ChannelOutboundHandlerAdapter() {
            boolean swallowed = false;
            @Override
            public void write(io.netty.channel.ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
                if (!swallowed) { swallowed = true; return; } // frame VANISHES (drop)
                ctx.write(msg, promise);
            }
        });
        FrameShadowObserver obs = FrameShadowObserver.install(ch.pipeline(), onCfg());
        byte[] first = new byte[2000], second = new byte[3000];
        new Random(95).nextBytes(first);
        new Random(96).nextBytes(second);
        writeTracked(ch, heap(first.clone()));   // captured; frame dropped mid-pipeline
        Object[] r2 = writeTracked(ch, heap(second.clone()));  // must retire stale pending and MATCH
        boolean secondOk = r2[0].equals(Boolean.TRUE) && r2[1] instanceof byte[]
                && Arrays.equals(MCK4Validation.decodeFrameSafe((byte[]) r2[1], true), second);
        boolean counters = obs.UNPAIRED_CAPTURE.get() == 1 && obs.MISMATCHED.get() == 0
                && obs.COMPLETED.get() == 1 && obs.MATCHED.get() == 1;
        check("pairing-hardening: dropped mid-pipeline frame -> stale pending RETIRED as unpaired; next message MATCHES (no mis-pairing)",
                secondOk && counters, "secondOk=" + secondOk + " unpaired=" + obs.UNPAIRED_CAPTURE.get()
                        + " mismatch=" + obs.MISMATCHED.get() + " completed=" + obs.COMPLETED.get());
        obs.uninstall(); ch.close();
        // v2 hole (reproduced live at every=2): the message AFTER the dropped
        // frame is SAMPLED OUT — its capture must still retire the stale pending
        // so the SUBSEQUENT selected message's frame is never absorbed by it.
        EmbeddedChannel ch2 = javaPipeline(256);
        ch2.pipeline().addLast("mck5-swallow2", new io.netty.channel.ChannelOutboundHandlerAdapter() {
            int seen = 0;
            @Override
            public void write(io.netty.channel.ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
                if (++seen == 2) { return; } // drop the SECOND write = the first SELECTED message (every=2 selects even seqs)
                ctx.write(msg, promise);
            }
        });
        FrameShadowObserver.Config cfg2 = onCfg();
        cfg2.sampleEvery = 2; // seq1 skipped, seq2 SELECTED (frame dropped), seq3 skipped (must retire), seq4 SELECTED
        FrameShadowObserver obs2 = FrameShadowObserver.install(ch2.pipeline(), cfg2);
        byte[][] bodies = { new byte[500], new byte[2000], new byte[700], new byte[3000] };
        Arrays.fill(bodies[0], (byte) 3);
        new Random(98).nextBytes(bodies[1]);
        Arrays.fill(bodies[2], (byte) 5);
        new Random(99).nextBytes(bodies[3]);
        writeTracked(ch2, heap(bodies[0].clone())); // seq1: skipped, frame flows
        writeTracked(ch2, heap(bodies[1].clone())); // seq2: SELECTED, frame DROPPED -> stale pending
        writeTracked(ch2, heap(bodies[2].clone())); // seq3: SKIPPED — must still retire the stale pending
        Object[] r4 = writeTracked(ch2, heap(bodies[3].clone())); // seq4: SELECTED; frame must pair with ITSELF
        boolean fourthOk = r4[0].equals(Boolean.TRUE) && r4[1] instanceof byte[]
                && Arrays.equals(MCK4Validation.decodeFrameSafe((byte[]) r4[1], true), bodies[3]);
        boolean counters2 = obs2.UNPAIRED_CAPTURE.get() == 1 && obs2.MISMATCHED.get() == 0
                && obs2.COMPLETED.get() == 1 && obs2.MATCHED.get() == 1;
        check("pairing-hardening-v2: sampled-out message after a dropped frame ALSO retires the stale pending; next SELECTED message matches",
                fourthOk && counters2, "fourthOk=" + fourthOk + " unpaired=" + obs2.UNPAIRED_CAPTURE.get()
                        + " mismatch=" + obs2.MISMATCHED.get() + " completed=" + obs2.COMPLETED.get());
        obs2.uninstall(); ch2.close();
    }

    static void check(String n, boolean ok, String d) {
        if (ok) { pass++; System.out.println(n + " -> PASS" + (d == null ? "" : " (" + d + ")")); }
        else { fail++; System.out.println(n + " -> FAIL " + d); }
    }

    // ---- pipeline builders ----
    static EmbeddedChannel javaPipeline(int threshold) throws Exception {
        EmbeddedChannel ch = new EmbeddedChannel();
        Object prep = Class.forName("net.minecraft.network.NettyVarint21FrameEncoder").getDeclaredConstructor().newInstance();
        ch.pipeline().addLast("prep", (ChannelHandler) prep);
        if (threshold >= 0) {
            Object comp = Class.forName("net.minecraft.network.NettyCompressionEncoder").getConstructor(int.class).newInstance(threshold);
            ch.pipeline().addLast("compress", (ChannelHandler) comp);
        }
        return ch;
    }

    static FrameShadowObserver.Config onCfg() {
        FrameShadowObserver.Config c = new FrameShadowObserver.Config();
        c.enabled = true;
        return c;
    }

    static boolean awaitFuture(ChannelFuture f, long ms) throws InterruptedException {
        CountDownLatch d = new CountDownLatch(1);
        f.addListener(v -> d.countDown());
        return d.await(ms, TimeUnit.MILLISECONDS);
    }

    static byte[] drain(EmbeddedChannel ch) {
        ByteBuf out = ch.readOutbound();
        if (out == null) return null;
        byte[] f = new byte[out.readableBytes()];
        out.readBytes(f);
        out.release();
        return f;
    }

    /** Write one body via the canonical EmbeddedChannel API: writeOutbound
     *  completes inline and RETHROWS a stored pipeline exception (encode
     *  failures surface through fireExceptionCaught, not the promise).
     *  Returns {success, frameBytes-or-cause, inputRefCntAfter}. */
    static Object[] writeTracked(EmbeddedChannel ch, ByteBuf input) {
        try {
            ch.writeOutbound(input);
            byte[] frame = drain(ch);
            return new Object[] { Boolean.TRUE, frame, Integer.valueOf(input.refCnt()) };
        } catch (Throwable t) {
            return new Object[] { Boolean.FALSE, t, Integer.valueOf(input.refCnt()) };
        }
    }

    static ByteBuf heap(byte[] b) { return Unpooled.wrappedBuffer(b); }

    // ---- case: OFF makes zero native observation calls ----
    static void offMakesZeroNativeCalls() throws Exception {
        long ops0 = OutboundFrameCtx.FRAME_OPS.get();
        byte[] body = new byte[3000];
        new Random(9).nextBytes(body);
        EmbeddedChannel a = javaPipeline(256);
        EmbeddedChannel b = javaPipeline(256);
        FrameShadowObserver obs = FrameShadowObserver.install(b.pipeline(), new FrameShadowObserver.Config()); // enabled=false
        Object[] ra = writeTracked(a, heap(body)), rb = writeTracked(b, heap(body));
        boolean identical = ra[0].equals(rb[0]) && Arrays.equals((byte[]) ra[1], (byte[]) rb[1]);
        boolean zeroCounters = obs.OBSERVED.get() == 0 && obs.SELECTED.get() == 0 && obs.COMPLETED.get() == 0
                && obs.CTX_CREATED.get() == 0 && obs.RETAINED_OBSERVATIONS.get() == 0;
        long opsDelta = OutboundFrameCtx.FRAME_OPS.get() - ops0;
        check("off: observer OFF -> identical Java output; ZERO observation/native calls (FRAME_OPS delta=0)",
                identical && zeroCounters && opsDelta == 0, "opsDelta=" + opsDelta);
        obs.uninstall(); a.close(); b.close();
    }

    // ---- differential across compression states ----
    static void compressionStatesAndDifferential() throws Exception {
        byte[] real = M53FixtureBuilder.realChunkPacketBody(800, 80); // REAL fully serialized packet
        byte[][] below = { new byte[255], new byte[64], new byte[200] };
        Arrays.fill(below[0], (byte) 3); Arrays.fill(below[1], (byte) 7); Arrays.fill(below[2], (byte) 7);
        byte[] at = new byte[256]; // exactly at threshold -> compressed (vanilla strict <)
        new Random(21).nextBytes(at);
        byte[] above = new byte[5000];
        new Random(22).nextBytes(above);
        boolean all = true;
        StringBuilder detail = new StringBuilder();
        // enabled @256: real, below(3 sizes), at-threshold, above-threshold
        all &= diff("enabled@256 real-31k", 256, new byte[][] { real }, null, detail);
        all &= diff("enabled@255 below(64,200,255)", 256, below, null, detail);
        all &= diff("enabled@256 at-threshold=256-compressed", 256, new byte[][] { at }, null, detail);
        all &= diff("enabled@256 above", 256, new byte[][] { above }, null, detail);
        // disabled: prep-only pipeline (actual disabled contract)
        all &= diff("disabled real-31k", -1, new byte[][] { real }, null, detail);
        all &= diff("disabled above-5k", -1, new byte[][] { above }, null, detail);
        check("differential: compression states (real/below/at/above, enabled+disabled) — Java bytes+promises identical; observer matched all",
                all, detail.toString());
    }

    /** Runs both pipelines over bodies; returns pass. cfg optional customizer. */
    interface ObsCustomizer { void customize(FrameShadowObserver obs); }
    static boolean diff(String name, int threshold, byte[][] bodies, ObsCustomizer cust, StringBuilder detail) throws Exception {
        EmbeddedChannel a = javaPipeline(threshold);
        EmbeddedChannel b = javaPipeline(threshold);
        FrameShadowObserver.Config cfg = onCfg();
        FrameShadowObserver obs = FrameShadowObserver.install(b.pipeline(), cfg);
        if (cust != null) cust.customize(obs);
        boolean ok = true;
        for (byte[] body : bodies) {
            Object[] ra = writeTracked(a, heap(body.clone()));
            Object[] rb = writeTracked(b, heap(body.clone()));
            boolean promises = ra[0].equals(rb[0]);
            boolean bytes = Arrays.equals((byte[]) ra[1], (byte[]) rb[1]);
            boolean refcnt = ra[2].equals(rb[2]) && ((Integer) rb[2]) == 0; // input released exactly once, same as A
            ok &= promises && bytes && refcnt;
        }
        long matchedExpected = 0;
        for (int i = 0; i < bodies.length; i++) matchedExpected++; // sampleEvery=1: every body observed
        boolean counters = obs.OBSERVED.get() == bodies.length && obs.SELECTED.get() == bodies.length
                && obs.COMPLETED.get() == matchedExpected && obs.MATCHED.get() == matchedExpected
                && obs.MISMATCHED.get() == 0 && obs.UNPAIRED_FRAME.get() == 0 && obs.UNPAIRED_CAPTURE.get() == 0;
        if (!ok || !counters) detail.append(name).append("[ok=").append(ok).append(" counters=").append(counters)
                .append(" obs=").append(obs.OBSERVED.get()).append(" sel=").append(obs.SELECTED.get())
                .append(" comp=").append(obs.COMPLETED.get()).append(" match=").append(obs.MATCHED.get()).append("] ");
        obs.uninstall(); a.close(); b.close();
        return ok && counters;
    }

    // ---- ordered writes around compression enable/update/disable ----
    static void transitionsAndOrdering() throws Exception {
        EmbeddedChannel a = javaPipeline(256), b = javaPipeline(256);
        FrameShadowObserver obs = FrameShadowObserver.install(b.pipeline(), onCfg());
        byte[] small = new byte[300], large = new byte[5000];
        Arrays.fill(small, (byte) 1); Arrays.fill(large, (byte) 2);
        Object[][] results = new Object[5][];
        byte[][] outs = new byte[5][];
        byte[][] outsA = new byte[5][];
        for (int i = 0; i < 5; i++) {
            byte[] body = (i % 2 == 0) ? large : small;
            Object[] ra = writeTracked(a, heap(body.clone()));
            Object[] rb = writeTracked(b, heap(body.clone()));
            results[i] = rb; outs[i] = (byte[]) rb[1]; outsA[i] = (byte[]) ra[1];
            if (i == 1) { setThreshold(a, 64); setThreshold(b, 64); }   // update (300 now compressed)
            if (i == 2) { setThreshold(a, 4096); setThreshold(b, 4096); } // update (5000 now compressed@4096? 5000>=4096 yes)
            if (i == 3) { removeCompress(a); removeCompress(b); }        // disable: actual contract (handler removed)
        }
        boolean promisesOk = true, bytesOk = true;
        for (int i = 0; i < 5; i++) {
            promisesOk &= ((Boolean) results[i][0]).booleanValue();
            bytesOk &= Arrays.equals(outsA[i], outs[i]);
        }
        boolean framingStates = MCK4Validation.decodeFrameSafe(outs[0], true) != null // threshold256: large compressed
                && MCK4Validation.decodeFrameSafe(outs[1], true) != null;             // small now @64: compressed
        boolean counters = obs.COMPLETED.get() == 5 && obs.MATCHED.get() == 5 && obs.MISMATCHED.get() == 0;
        check("transitions: writes around threshold update/disable — Java outputs identical to no-observer pipeline; all 5 observations matched",
                promisesOk && bytesOk && framingStates && counters,
                "completed=" + obs.COMPLETED.get() + " matched=" + obs.MATCHED.get());
        obs.uninstall(); a.close(); b.close();
    }

    static void setThreshold(EmbeddedChannel ch, int t) throws Exception {
        Object comp = ch.pipeline().get("compress");
        comp.getClass().getMethod("func_179299_a", int.class).invoke(comp, t);
    }

    static void removeCompress(EmbeddedChannel ch) {
        ch.pipeline().remove("compress");
    }

    // ---- multiple channels with distinct sequences and states ----
    static void multiChannelIndependence() throws Exception {
        EmbeddedChannel b1 = javaPipeline(256), b2 = javaPipeline(-1);
        FrameShadowObserver o1 = FrameShadowObserver.install(b1.pipeline(), onCfg());
        FrameShadowObserver o2 = FrameShadowObserver.install(b2.pipeline(), onCfg());
        byte[] body = new byte[4000];
        new Random(31).nextBytes(body);
        writeTracked(b1, heap(body.clone()));
        writeTracked(b2, heap(body.clone()));
        writeTracked(b1, heap(body.clone()));
        boolean ok = o1.COMPLETED.get() == 2 && o1.MATCHED.get() == 2
                && o2.COMPLETED.get() == 1 && o2.MATCHED.get() == 1
                && o1.CTX_CREATED.get() >= 1 && o2.CTX_CREATED.get() >= 1
                && o1.disabledReason == null && o2.disabledReason == null;
        check("multi-channel: distinct observers/sequences/states (256 vs disabled), all matched independently",
                ok, "o1=" + o1.COMPLETED.get() + "/" + o1.MATCHED.get() + " o2=" + o2.COMPLETED.get() + "/" + o2.MATCHED.get());
        o1.uninstall(); o2.uninstall(); b1.close(); b2.close();
    }

    // ---- heap/direct/nonzero-index/slice/composite inputs ----
    static void bufferForms() throws Exception {
        byte[] body = new byte[3000];
        new Random(41).nextBytes(body);
        EmbeddedChannel a = javaPipeline(256), b = javaPipeline(256);
        FrameShadowObserver obs = FrameShadowObserver.install(b.pipeline(), onCfg());
        // heap
        Object[] r1 = writeTracked(a, heap(body.clone()));
        ByteBuf heapB = Unpooled.wrappedBuffer(body.clone());
        Object[] s1 = writeTracked(b, heapB);
        // direct
        ByteBuf directA = Unpooled.directBuffer(body.length).writeBytes(body);
        Object[] r2 = writeTracked(a, directA);
        ByteBuf directB = Unpooled.directBuffer(body.length).writeBytes(body);
        Object[] s2 = writeTracked(b, directB);
        // nonzero readerIndex + slice
        ByteBuf wrapped = Unpooled.wrappedBuffer(body);
        wrapped.readerIndex(200);
        ByteBuf sl = wrapped.slice();
        byte[] expect = Arrays.copyOfRange(body, 200, body.length);
        ByteBuf wrapped2 = Unpooled.wrappedBuffer(body);
        wrapped2.readerIndex(200);
        Object[] s3 = writeTracked(b, wrapped2.slice());
        Object[] r3 = writeTracked(a, sl);
        // composite
        ByteBuf compA = Unpooled.compositeBuffer()
                .addComponent(true, Unpooled.wrappedBuffer(Arrays.copyOfRange(body, 0, 1000)))
                .addComponent(true, Unpooled.wrappedBuffer(Arrays.copyOfRange(body, 1000, body.length)));
        ByteBuf compB = Unpooled.compositeBuffer()
                .addComponent(true, Unpooled.wrappedBuffer(Arrays.copyOfRange(body, 0, 1000)))
                .addComponent(true, Unpooled.wrappedBuffer(Arrays.copyOfRange(body, 1000, body.length)));
        Object[] r4 = writeTracked(a, compA);
        Object[] s4 = writeTracked(b, compB);
        boolean formsOk = Arrays.equals((byte[]) r1[1], (byte[]) s1[1])
                && Arrays.equals((byte[]) r2[1], (byte[]) s2[1])
                && Arrays.equals((byte[]) r3[1], (byte[]) s3[1])
                && Arrays.equals((byte[]) r4[1], (byte[]) s4[1]);
        boolean decoded = MCK4Validation.decodeFrameSafe((byte[]) s3[1], true) != null
                && Arrays.equals(MCK4Validation.decodeFrameSafe((byte[]) s3[1], true), expect);
        boolean counters = obs.COMPLETED.get() == 4 && obs.MATCHED.get() == 4;
        check("buffer-forms: heap/direct/nonzero-index-slice/composite — Java outputs identical; slice observes only readable region; all matched",
                formsOk && decoded && counters, "completed=" + obs.COMPLETED.get() + " matched=" + obs.MATCHED.get());
        obs.uninstall(); a.close(); b.close();
    }

    // ---- frame anomaly detector (1:1 contract violation -> skip/disable, never MATCH) ----
    static void frameAnomalyDetector() throws Exception {
        FrameShadowObserver.Decoded dBad = FrameShadowObserver.structurallyDecode(concatTwoFrames(), true);
        check("anomaly: concatenated-frames buffer structurally DECODES as NOT-exactly-one-frame (consumedExactly=false)",
                dBad != null && !dBad.consumedExactly, null);
        byte[] one = javaFrameRef(new byte[300], 256);
        FrameShadowObserver.Decoded dGood = FrameShadowObserver.structurallyDecode(one, true);
        check("anomaly: single frame decodes consumedExactly=true", dGood != null && dGood.consumedExactly, null);
        byte[] disabled = javaFrameRef(new byte[300], null);
        FrameShadowObserver.Decoded dDis = FrameShadowObserver.structurallyDecode(disabled, false);
        check("anomaly: disabled framing (no dataLen) decodes consumedExactly=true", dDis != null && dDis.consumedExactly, null);
    }

    static byte[] concatTwoFrames() throws Exception {
        byte[] f1 = javaFrameRef(new byte[300], 256), f2 = javaFrameRef(new byte[400], 256);
        byte[] cat = new byte[f1.length + f2.length];
        System.arraycopy(f1, 0, cat, 0, f1.length);
        System.arraycopy(f2, 0, cat, f1.length, f2.length);
        return cat;
    }

    static byte[] javaFrameRef(byte[] body, Integer threshold) throws Exception {
        return M53FrameBench.javaFrame(body, threshold);
    }

    // ---- limits: Java continues; skips counted ----
    static void limitsAndSkips() throws Exception {
        byte[] normal = new byte[500];
        Arrays.fill(normal, (byte) 7);
        byte[] oversize = new byte[3000]; // > maxSampleBytes below
        Arrays.fill(oversize, (byte) 6);
        // (1) maxComparisons=1 + oversize: msg1 completes; msg2 limit-skipped; msg3 oversize-skipped
        EmbeddedChannel a = javaPipeline(256), b = javaPipeline(256);
        FrameShadowObserver.Config cfg = onCfg();
        cfg.maxSampleBytes = 2000;
        cfg.maxComparisons = 1;
        FrameShadowObserver obs = FrameShadowObserver.install(b.pipeline(), cfg);
        int bFrames = 0;
        for (byte[] body : new byte[][] { normal, normal, oversize }) {
            writeTracked(a, heap(body.clone()));
            Object[] r = writeTracked(b, heap(body.clone()));
            if (((Boolean) r[0]).booleanValue() && r[1] instanceof byte[]) bFrames++;
        }
        boolean c1 = bFrames == 3 && obs.OBSERVED.get() == 3 && obs.SELECTED.get() == 1
                && obs.COMPLETED.get() == 1 && obs.SKIPPED_LIMIT.get() == 1 && obs.SKIPPED_OVERSIZE.get() == 1;
        check("limits: maxComparisons/oversample — Java produced ALL 3 frames; exactly 1 comparison; skips counted per reason",
                c1, "obs=" + obs.OBSERVED.get() + " sel=" + obs.SELECTED.get() + " comp=" + obs.COMPLETED.get()
                        + " limit=" + obs.SKIPPED_LIMIT.get() + " over=" + obs.SKIPPED_OVERSIZE.get());
        obs.uninstall(); a.close(); b.close();
        // (2) deterministic sampling 1:2: evens selected, odds flow untouched
        EmbeddedChannel a2 = javaPipeline(256), b2 = javaPipeline(256);
        FrameShadowObserver.Config cfg2 = onCfg();
        cfg2.sampleEvery = 2;
        FrameShadowObserver obs2 = FrameShadowObserver.install(b2.pipeline(), cfg2);
        int pairs = 0, bFrames2 = 0;
        for (int i = 0; i < 6; i++) {
            writeTracked(a2, heap(normal.clone()));
            Object[] r = writeTracked(b2, heap(normal.clone()));
            if (((Boolean) r[0]).booleanValue()) { bFrames2++; pairs++; }
        }
        boolean c2 = bFrames2 == 6 && obs2.OBSERVED.get() == 6 && obs2.SELECTED.get() == 3
                && obs2.COMPLETED.get() == 3 && obs2.MATCHED.get() == 3 && obs2.UNPAIRED_FRAME.get() == 3;
        check("limits: sampling 1:2 — all 6 Java frames produced; 3 selected+matched; 3 unpaired frames counted (sampling skip)",
                c2, "sel=" + obs2.SELECTED.get() + " comp=" + obs2.COMPLETED.get() + " unpairedFrames=" + obs2.UNPAIRED_FRAME.get());
        obs2.uninstall(); a2.close(); b2.close();
    }

    // ---- missing native ctx + corruption containment ----
    static void ctxFailureAndCorruption() throws Exception {
        // (1) ctx creation failure: Java continues, observer disables, zero completed
        EmbeddedChannel b1 = javaPipeline(256);
        FrameShadowObserver o1 = FrameShadowObserver.install(b1.pipeline(), onCfg());
        o1.testSimulateCtxCreateFail = true;
        byte[] body = new byte[1000];
        Arrays.fill(body, (byte) 8);
        Object[] r1 = writeTracked(b1, heap(body.clone()));
        boolean case1 = r1[0].equals(Boolean.TRUE) && MCK4Validation.decodeFrameSafe((byte[]) r1[1], true) != null
                && o1.COMPLETED.get() == 0 && o1.OBSERVER_ERRORS.get() == 1
                && "native frame context unavailable".equals(o1.disabledReason)
                && o1.CTX_FREED.get() == 0;
        check("ctx-failure: observer disables with reason; Java frame still produced and valid", case1,
                "reason=" + o1.disabledReason);
        o1.uninstall(); b1.close();
        // (2) native result corruption: mismatch detected, observer disabled, Java output unchanged
        EmbeddedChannel a2 = javaPipeline(256), b2 = javaPipeline(256);
        FrameShadowObserver o2 = FrameShadowObserver.install(b2.pipeline(), onCfg());
        Object[] ra = writeTracked(a2, heap(body.clone()));
        o2.testCorruptRustFrame = true;
        Object[] rb = writeTracked(b2, heap(body.clone()));
        boolean case2 = rb[0].equals(Boolean.TRUE)
                && Arrays.equals((byte[]) ra[1], (byte[]) rb[1])
                && o2.MISMATCHED.get() == 1 && o2.MATCHED.get() == 0 && o2.disabledReason != null
                && !b2.close().isCancelled(); // channel still functional
        check("corruption: rust-frame corruption -> MISMATCH counted, observer disabled, Java bytes identical to no-observer run",
                case2, "mismatch=" + o2.MISMATCHED.get() + " reason=" + o2.disabledReason);
        o2.uninstall(); a2.close(); b2.close();
        // (3) same-length sample corruption: comparison must fail
        EmbeddedChannel a3 = javaPipeline(256), b3 = javaPipeline(256);
        FrameShadowObserver o3 = FrameShadowObserver.install(b3.pipeline(), onCfg());
        Object[] ra3 = writeTracked(a3, heap(body.clone()));
        o3.testCorruptSample = true;
        Object[] rb3 = writeTracked(b3, heap(body.clone()));
        boolean case3 = Arrays.equals((byte[]) ra3[1], (byte[]) rb3[1]) && o3.MISMATCHED.get() == 1
                && "java frame did not recover the captured body".equals(o3.disabledReason);
        check("corruption: same-length captured-body change -> comparison FAILS (no false match), Java bytes identical",
                case3, "mismatch=" + o3.MISMATCHED.get() + " reason=" + o3.disabledReason);
        o3.uninstall(); a3.close(); b3.close();
    }

    // ---- pairing corruption: detected, never false MATCH ----
    static void pairingCorruption() throws Exception {
        EmbeddedChannel a = javaPipeline(256), b = javaPipeline(256);
        FrameShadowObserver obs = FrameShadowObserver.install(b.pipeline(), onCfg());
        byte[] body1 = new byte[1000], body2 = new byte[1400]; // distinct SAME-SCHEMA bodies
        Arrays.fill(body1, (byte) 9);
        Arrays.fill(body2, (byte) 10);
        writeTracked(a, heap(body1.clone()));
        writeTracked(b, heap(body1.clone()));               // sample 1 matches; becomes lastOriginal
        obs.testPairingCorrupt = true;
        Object[] rb = writeTracked(b, heap(body2.clone())); // Java frame of body2 paired with body1's sample
        writeTracked(a, heap(body2.clone()));
        // the real pending of body2 is retired by the promise listener (unpaired capture);
        // the corrupted pairing is detected as a MISMATCH — never a MATCH
        boolean ok = rb[0].equals(Boolean.TRUE) && rb[1] instanceof byte[]
                && obs.MATCHED.get() == 1                   // only the clean first sample
                && obs.MISMATCHED.get() == 1
                && obs.UNPAIRED_CAPTURE.get() == 1
                && obs.RETAINED_OBSERVATIONS.get() == 0
                && obs.disabledReason != null;
        check("pairing-corruption: stale-sample pairing detected as MISMATCH (never a MATCH); real sample retired unpaired; Java frame produced",
                ok, "matched=" + obs.MATCHED.get() + " mismatch=" + obs.MISMATCHED.get()
                        + " unpaired=" + obs.UNPAIRED_CAPTURE.get() + " reason=" + obs.disabledReason);
        obs.uninstall(); a.close(); b.close();
    }

    // ---- failed Java encoding: capture retired, unpaired counted, failure identical ----
    static void javaEncodeFailureUnpaired() throws Exception {
        int max = 0x1F_FF_FF;
        byte[] overlimit = new byte[max + 65_536]; // INCOMPRESSIBLE: actual compressed size overflows the prepender
        new Random(77).nextBytes(overlimit);
        EmbeddedChannel a = javaPipeline(256), b = javaPipeline(256);
        FrameShadowObserver.Config cfg = onCfg();
        cfg.maxSampleBytes = max + 256 * 1024; // sample IS selected; the failure comes later (prepender overflow)
        FrameShadowObserver obs = FrameShadowObserver.install(b.pipeline(), cfg);
        Object[] ra = writeTracked(a, heap(overlimit));
        Object[] rb = writeTracked(b, heap(overlimit));
        boolean failedBoth = !((Boolean) ra[0]).booleanValue() && !((Boolean) rb[0]).booleanValue();
        boolean sameCause = ra[1] instanceof Throwable && rb[1] instanceof Throwable
                && ra[1].getClass().equals(rb[1].getClass())
                && String.valueOf(((Throwable) ra[1]).getMessage()).equals(String.valueOf(((Throwable) rb[1]).getMessage()));
        boolean retired = obs.UNPAIRED_CAPTURE.get() == 1 && obs.COMPLETED.get() == 0
                && obs.RETAINED_OBSERVATIONS.get() == 0 && obs.RETAINED_BYTES.get() == 0;
        check("java-encode-failure: BOTH pipelines fail identically ('unable to fit'); capture retired unpaired; observer data released",
                failedBoth && sameCause && retired,
                "cause=" + (ra[1] instanceof Throwable ? ((Throwable) ra[1]).getMessage() : "none")
                        + " unpaired=" + obs.UNPAIRED_CAPTURE.get());
        obs.uninstall(); a.close(); b.close();
    }

    // ---- retain frame A, produce B, reread + decode ORIGINAL A ----
    static void retainedAWhileB() throws Exception {
        EmbeddedChannel ch = javaPipeline(256);
        FrameShadowObserver obs = FrameShadowObserver.install(ch.pipeline(), onCfg());
        byte[] a = new byte[2000], b = new byte[3000];
        new Random(51).nextBytes(a);
        new Random(52).nextBytes(b);
        writeNoDrain(ch, a.clone());
        ByteBuf fa = ch.readOutbound();
        byte[] faSaved = new byte[fa.readableBytes()];
        fa.getBytes(fa.readerIndex(), faSaved);
        int ri = fa.readerIndex();
        writeNoDrain(ch, b.clone());
        byte[] faRe = new byte[faSaved.length];
        fa.getBytes(ri, faRe);
        boolean unchanged = Arrays.equals(faSaved, faRe)
                && Arrays.equals(MCK4Validation.decodeFrameSafe(faSaved, true), a);
        check("retained-A: observer processing B leaves A's original retained frame bytes unchanged; A still decodes",
                unchanged && obs.MATCHED.get() == 2, "matched=" + obs.MATCHED.get());
        fa.release();
        obs.uninstall(); ch.close();
    }

    static void writeNoDrain(EmbeddedChannel ch, byte[] body) {
        ch.writeOutbound(heap(body));
    }

    // ---- observer lifecycle: uninstall frees ctx (quiescence), executor rejection with op in flight ----
    static void observerLifecycleQuiescence() throws Exception {
        DefaultEventExecutorGroup group = new DefaultEventExecutorGroup(1);
        try {
            EventExecutor exec = group.next();
            // (a) normal uninstall: pending none, ctx freed exactly once
            EmbeddedChannel ch = javaPipeline(256);
            FrameShadowObserver obs = FrameShadowObserver.install(ch.pipeline(), onCfg());
            byte[] body = new byte[800];
            Arrays.fill(body, (byte) 4);
            writeTracked(ch, heap(body.clone()));
            obs.captureOwner(exec); // marshal frees onto the real executor
            obs.uninstall();
            exec.submit(() -> {}).get(5, TimeUnit.SECONDS);
            check("observer-lifecycle: uninstall frees native ctx exactly once (marshaled to real executor)",
                    obs.CTX_CREATED.get() == 1 && obs.CTX_FREED.get() == 1, "created=" + obs.CTX_CREATED.get() + " freed=" + obs.CTX_FREED.get());
            ch.close();
            // (b) rejection while native op IN FLIGHT: blocked comparison on a writer thread,
            // executor hard-terminated, uninstall -> owner.execute REJECTED -> free DEFERRED
            // -> the completing op frees on the write thread.
            EmbeddedChannel ch2 = javaPipeline(256);
            FrameShadowObserver obs2 = FrameShadowObserver.install(ch2.pipeline(), onCfg());
            CountDownLatch blockOn = new CountDownLatch(1), entered = new CountDownLatch(1);
            obs2.testNativeEntryLatch = blockOn;
            obs2.testNativeEntered = entered;
            Thread writer = new Thread(() -> {
                try { writeTracked(ch2, heap(body.clone())); } catch (Throwable ignore) { }
            });
            writer.start();
            entered.await(5, TimeUnit.SECONDS); // writer is INSIDE the native-op region (inFlight=1)
            // HARD shutdown: graceful shutdown keeps ACCEPTING tasks while draining
            // (state ST_SHUTTING_DOWN, isShutdown()==false) — rejection requires
            // ST_SHUTDOWN, which SingleThreadEventExecutor.shutdown() sets immediately
            // while the worker may still be mid-task.
            exec.shutdown();
            boolean rejectedNow = false;
            try { exec.submit(() -> {}); } catch (Throwable t) { rejectedNow = true; }
            obs2.captureOwner(exec);
            obs2.uninstall(); // freeNative: owner REJECTS -> inFlight>0 -> DEFER (never beneath the running op)
            boolean deferredNotFreed = obs2.CTX_FREED.get() == 0;
            blockOn.countDown();
            writer.join(5000);
            check("observer-quiescence: executor REJECTION with native op in flight -> free deferred to op completion; exactly-once; no crash",
                    deferredNotFreed && obs2.CTX_FREED.get() == 1 && rejectedNow && !writer.isAlive(),
                    "rejectedImmediately=" + rejectedNow + " freedAfterCompletion=" + obs2.CTX_FREED.get());
            try { ch2.close(); } catch (Throwable teardown) { /* Netty teardown on a hard-terminated executor; assertions already complete */ }
        } finally {
            group.shutdownGracefully();
        }
    }

    // ---- LIVE install path: production-shaped names, fail-closed verification, close cleanup ----
    static void liveInstallPositionsAndFailClosed() throws Exception {
        // production-shaped pipeline: [prepender, compress, encoder] at the real names
        // (the "encoder" stand-in serializes nothing — writes at that position already
        // carry the serialized body, which is exactly what capture must see)
        io.netty.channel.ChannelHandler noOpEncoder = new io.netty.channel.ChannelOutboundHandlerAdapter();
        EmbeddedChannel ch = new EmbeddedChannel();
        Object prep = Class.forName("net.minecraft.network.NettyVarint21FrameEncoder").getDeclaredConstructor().newInstance();
        ch.pipeline().addLast("prepender", (ChannelHandler) prep);
        Object comp = Class.forName("net.minecraft.network.NettyCompressionEncoder").getConstructor(int.class).newInstance(256);
        ch.pipeline().addLast("compress", (ChannelHandler) comp);
        ch.pipeline().addLast("encoder", noOpEncoder);
        long ok0 = FrameShadowObserver.INSTALL_OK.get(), skip0 = FrameShadowObserver.INSTALL_SKIPPED.get();
        FrameShadowObserver obs = FrameShadowObserver.liveInstall(ch.pipeline(), onCfg());
        byte[] body = new byte[3000];
        new Random(91).nextBytes(body);
        writeNoDrain(ch, body.clone());
        byte[] f = drain(ch);
        boolean installed = obs != null && FrameShadowObserver.INSTALL_OK.get() == ok0 + 1
                && f != null && Arrays.equals(MCK4Validation.decodeFrameSafe(f, true), body)
                && obs.COMPLETED.get() == 1 && obs.MATCHED.get() == 1;
        // double install skipped
        FrameShadowObserver again = FrameShadowObserver.liveInstall(ch.pipeline(), onCfg());
        boolean noDouble = again == null && FrameShadowObserver.INSTALL_SKIPPED.get() == skip0 + 1;
        // close-based cleanup: handlerRemoved retires + frees exactly once (LIVE lifecycle)
        ch.close().awaitUninterruptibly();
        boolean cleaned = obs.CTX_CREATED.get() == 1 && obs.CTX_FREED.get() == 1
                && obs.RETAINED_OBSERVATIONS.get() == 0 && obs.RETAINED_BYTES.get() == 0;
        check("live-install: production-shaped [prepender,compress,encoder] -> verified positions; observation matches; double-install skipped; close frees exactly-once with zero retained",
                installed && noDouble && cleaned,
                "installed=" + installed + " skipReason=" + FrameShadowObserver.LAST_SKIP_REASON
                        + " ctx=" + obs.CTX_CREATED.get() + "/" + obs.CTX_FREED.get());
        // fail-closed negatives: missing prepender / wrong order -> NOTHING installed
        EmbeddedChannel bad1 = new EmbeddedChannel();
        Object prep2 = Class.forName("net.minecraft.network.NettyVarint21FrameEncoder").getDeclaredConstructor().newInstance();
        bad1.pipeline().addLast("prependerX", (ChannelHandler) prep2); // wrong name -> missing "prepender"
        Object comp2 = Class.forName("net.minecraft.network.NettyCompressionEncoder").getConstructor(int.class).newInstance(256);
        bad1.pipeline().addLast("compress", (ChannelHandler) comp2);
        bad1.pipeline().addLast("encoder", new io.netty.channel.ChannelOutboundHandlerAdapter());
        long skip1 = FrameShadowObserver.INSTALL_SKIPPED.get();
        boolean fail1 = FrameShadowObserver.liveInstall(bad1.pipeline(), onCfg()) == null
                && FrameShadowObserver.INSTALL_SKIPPED.get() == skip1 + 1
                && bad1.pipeline().get("mck5-capture") == null
                && String.valueOf(FrameShadowObserver.LAST_SKIP_REASON).contains("missing-handler");
        EmbeddedChannel bad2 = new EmbeddedChannel(); // wrong ORDER: fresh handler instances (not @Sharable)
        bad2.pipeline().addLast("prepender", (ChannelHandler) Class.forName("net.minecraft.network.NettyVarint21FrameEncoder").getDeclaredConstructor().newInstance());
        bad2.pipeline().addLast("encoder", new io.netty.channel.ChannelOutboundHandlerAdapter());
        bad2.pipeline().addLast("compress", (ChannelHandler) Class.forName("net.minecraft.network.NettyCompressionEncoder").getConstructor(int.class).newInstance(256));
        long skip2 = FrameShadowObserver.INSTALL_SKIPPED.get();
        boolean fail2 = FrameShadowObserver.liveInstall(bad2.pipeline(), onCfg()) == null
                && FrameShadowObserver.INSTALL_SKIPPED.get() == skip2 + 1
                && bad2.pipeline().get("mck5-capture") == null
                && String.valueOf(FrameShadowObserver.LAST_SKIP_REASON).contains("order-verification-failed");
        check("live-install-failclosed: missing-prepender and wrong-order pipelines install NOTHING (counted, reason recorded, Java untouched)",
                fail1 && fail2, "fail1=" + fail1 + " fail2=" + fail2 + " last=" + FrameShadowObserver.LAST_SKIP_REASON);
        bad1.close(); bad2.close();
    }

    // ---- RustFrameHandler quiescence/rejection on a real executor ----
    static void handlerQuiescenceRejection() throws Exception {
        DefaultEventExecutorGroup group = new DefaultEventExecutorGroup(1);
        try {
            EventExecutor exec = group.next();
            RustFrameHandler h = new RustFrameHandler();
            h.setThreshold(256);
            EmbeddedChannel ch = new EmbeddedChannel();
            ch.pipeline().addLast(group, "rustframe", h);
            exec.submit(() -> {}).get(5, TimeUnit.SECONDS); // handlerAdded done
            CountDownLatch blockOn = new CountDownLatch(1), entered = new CountDownLatch(1);
            h.testEncodeBlockOn = blockOn;
            h.testEncodeEntered = entered;
            CountDownLatch done = new CountDownLatch(1);
            AtomicLong freesWhenBlocked = new AtomicLong(-1);
            ChannelFuture[] w = new ChannelFuture[1];
            Thread writer = new Thread(() -> {
                try {
                    w[0] = ch.writeAndFlush(Unpooled.wrappedBuffer(new byte[4000]));
                    done.countDown();
                } catch (Throwable ignore) { }
            });
            writer.start();
            boolean enteredOk = entered.await(5, TimeUnit.SECONDS); // encode dispatched and blocked INSIDE the counted region
            // HARD shutdown (see observer test): ST_SHUTDOWN rejects immediately
            // while the worker is still mid-encode.
            exec.shutdown();
            boolean executorRejects = false;
            try { exec.submit(() -> {}); } catch (Throwable t) { executorRejects = true; }
            // Direct cleanup invocation: with a terminated executor, a pipeline close op
            // would be rejected by Netty BEFORE reaching the handler — so the test calls
            // the handler's close hook directly, exercising OUR cleanupNative rejection
            // branch (owner.execute -> RejectedExecutionException -> inFlight>0 -> defer).
            java.util.concurrent.atomic.AtomicBoolean closeRan = new java.util.concurrent.atomic.AtomicBoolean(false);
            Thread closer = new Thread(() -> {
                try {
                    h.close(ch.pipeline().lastContext(), ch.newPromise());
                } catch (Throwable ignore) { } finally { closeRan.set(true); }
            });
            closer.start();
            closer.join(5000);
            boolean notFreedWhileInFlight = h.FREES.get() == 0 && closeRan.get();
            blockOn.countDown();                    // op completes -> its finally frees on the executor thread
            done.await(5, TimeUnit.SECONDS);
            awaitFuture(w[0], 5000);
            writer.join(5000);
            check("handler-quiescence: executor rejection with encode IN FLIGHT -> free deferred until op completion (never beneath it); exactly-once",
                    enteredOk && notFreedWhileInFlight && h.FREES.get() == 1 && executorRejects,
                    "entered=" + enteredOk + " freesWhileBlocked=" + (notFreedWhileInFlight ? 0 : 1) + " freesAfter=" + h.FREES.get() + " executorRejects=" + executorRejects);
            try { ch.close(); } catch (Throwable teardown) { /* Netty teardown on a hard-terminated executor; assertions already complete */ }
        } finally {
            group.shutdownGracefully();
        }
    }
}
