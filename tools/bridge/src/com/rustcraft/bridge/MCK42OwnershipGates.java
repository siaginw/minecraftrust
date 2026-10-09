package com.rustcraft.bridge;

import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.concurrent.DefaultEventExecutorGroup;
import io.netty.util.concurrent.EventExecutor;

/**
 * M-CK4.2 §1 — REAL executor ownership gates for RustFrameHandler.
 *
 * Netty 4.1.9.Final (bundled in minecraft_server.1.12.2.srg.jar) evidence:
 * EmbeddedEventLoop.inEventLoop()/inEventLoop(Thread) return true
 * UNCONDITIONALLY (iconst_1) — an EmbeddedChannel's own loop cannot back an
 * ownership claim. These gates therefore bind the handler to a REAL
 * single-thread executor via pipeline.addLast(EventExecutorGroup, ...), which
 * (DefaultChannelPipeline.addLast disassembly) creates the context with
 * childExecutor(group) == group.next() and marshals handlerAdded through
 * ctx.executor().execute(...). Pipeline writes use the same
 * executor().inEventLoop() gate (AbstractChannelHandlerContext.invokeWrite), so
 * encodes, queued updates, and the marshaled free all run on that one thread,
 * FIFO by the SingleThreadEventExecutor task-queue contract.
 *
 * No fake/permissive executor is used to bypass the contract; the only
 * EmbeddedChannel permissiveness appears as the CONTROL arm of gate 2,
 * documenting why embedded membership alone is not evidence.
 */
public class MCK42OwnershipGates {

    static int pass = 0, fail = 0;

    public static void main(String[] args) throws Exception {
        DefaultEventExecutorGroup group = new DefaultEventExecutorGroup(1);
        try {
            gate1_configThreadA_attachEncodeOnB(group);
            gate2_wrongThreadCannotClaimOwnership(group);
            gate3_orderedWriteUpdateWrite(group);
            gate4_offOwnerRejectedAndQueued(group);
            gate5_closeWithQueuedWork(group);
            gate6_independentChannels(group);
            gate7_creationFailure(group);
        } finally {
            group.shutdownGracefully(0, 2, TimeUnit.SECONDS);
        }
        System.out.println("RESULT: " + pass + " pass, " + fail + " fail");
        System.exit(fail == 0 ? 0 : 1);
    }

    static void check(String n, boolean ok, String d) {
        if (ok) { pass++; System.out.println(n + " -> PASS" + (d == null ? "" : " (" + d + ")")); }
        else { fail++; System.out.println(n + " -> FAIL " + d); }
    }

    static byte[] body(int n, byte fill) { byte[] b = new byte[n]; Arrays.fill(b, fill); return b; }

    /** Attach h to a fresh EmbeddedChannel bound to the group's executor; awaits handlerAdded. */
    static EmbeddedChannel attach(DefaultEventExecutorGroup group, RustFrameHandler h) throws Exception {
        EmbeddedChannel ch = new EmbeddedChannel(); // registered; own loop permissive
        ch.pipeline().addLast(group, "rustframe", h); // context executor = group.next(); handlerAdded marshaled
        group.next().submit(() -> {}).get(5, TimeUnit.SECONDS); // FIFO: runs after handlerAdded
        return ch;
    }

    static EventExecutor exec(DefaultEventExecutorGroup group) { return group.next(); }

    static Thread execThread(EventExecutor exec) throws Exception {
        return exec.submit(Thread::currentThread).get(5, TimeUnit.SECONDS);
    }

    /** Await a Netty future WITHOUT future.await(): EmbeddedChannel promises run
     *  their deadlock check against the permissive EmbeddedEventLoop (inEventLoop
     *  == true for every thread), which would spuriously reject an await from the
     *  marshaling thread. Listener + countdown is the thread-neutral wait. */
    static boolean awaitFuture(io.netty.channel.ChannelFuture f, long ms) throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        f.addListener(v -> done.countDown());
        return done.await(ms, TimeUnit.MILLISECONDS);
    }

    /** Write from the calling thread (marshaled to the owning executor), await, drain frame bytes. */
    static byte[] writeAwait(EmbeddedChannel ch, byte[] body) throws Exception {
        if (!awaitFuture(ch.writeAndFlush(Unpooled.wrappedBuffer(body)), 5000)) return null;
        ByteBuf out = ch.readOutbound();
        if (out == null) return null;
        byte[] f = new byte[out.readableBytes()];
        out.readBytes(f);
        out.release();
        return f;
    }

    static void closeAwait(EmbeddedChannel ch) throws Exception {
        awaitFuture(ch.close(), 5000);
    }

    // ---- gate 1: construct/configure on thread A, attach and encode on executor B ----
    static void gate1_configThreadA_attachEncodeOnB(DefaultEventExecutorGroup group) throws Exception {
        RustFrameHandler h = new RustFrameHandler();
        h.setThreshold(256); // pre-attachment configuration on thread A (main) — binds no ownership
        EmbeddedChannel ch = attach(group, h);
        EventExecutor ex = exec(group);
        check("g1: owner bound to the ATTACHED executor (not the configuring thread)",
                h.ownerExecutor() == ex && h.ownerExecutor() != null, null);
        byte[] b = body(5000, (byte) 5);
        byte[] f = writeAwait(ch, b);
        Thread et = execThread(ex);
        boolean decoded = f != null && Arrays.equals(MCK4Validation.decodeFrameSafe(f, true), b);
        check("g1: configure on A, encode runs on real executor B, frame decodes compressed @256",
                decoded && h.lastEncodeThread == et && Thread.currentThread() != et,
                "encodeThread=" + h.lastEncodeThread + " execThread=" + et);
        closeAwait(ch);
    }

    // ---- gate 2: first post-attachment call from the WRONG thread cannot claim ownership ----
    static void gate2_wrongThreadCannotClaimOwnership(DefaultEventExecutorGroup group) throws Exception {
        RustFrameHandler h = new RustFrameHandler();
        h.setThreshold(256);
        EmbeddedChannel ch = attach(group, h);
        EventExecutor ex = exec(group);
        final AtomicBoolean rejected = new AtomicBoolean(false);
        try { h.setThreshold(512); } catch (IllegalStateException ise) { rejected.set(true); }
        check("g2: wrong-thread synchronous setThreshold REJECTED (first post-attach call from wrong thread)",
                rejected.get() && h.ownerExecutor() == ex && h.threshold() == 256, null);
        // negative-CONTROL pair: the same call on an EmbeddedChannel-bound handler (permissive
        // EmbeddedEventLoop.inEventLoop() == true) SUCCEEDS — proving this gate discriminates
        // real membership and that embedded channels alone are not ownership evidence.
        RustFrameHandler ph = new RustFrameHandler();
        EmbeddedChannel pch = new EmbeddedChannel();
        pch.pipeline().addLast("rustframe", ph);
        boolean permissiveAccepted = false;
        try { ph.setThreshold(512); permissiveAccepted = true; } catch (Throwable t) { }
        check("g2-control: embedded-bound handler accepts the same call (permissive loop) — gate discriminates",
                permissiveAccepted, null);
        closeAwait(pch);
        byte[] b = body(300, (byte) 7);
        byte[] f = writeAwait(ch, b);
        check("g2: ownership NOT stolen by the rejected call; encode still ordered and correct",
                f != null && Arrays.equals(MCK4Validation.decodeFrameSafe(f, true), b), null);
        closeAwait(ch);
    }

    // ---- gate 3: ordered write/update/write produces the expected framing states ----
    static void gate3_orderedWriteUpdateWrite(DefaultEventExecutorGroup group) throws Exception {
        RustFrameHandler h = new RustFrameHandler();
        h.setThreshold(256); // token 256 (pre-attach config)
        EmbeddedChannel ch = attach(group, h);
        byte[] b1 = body(5000, (byte) 3), b2 = body(50, (byte) 4), b3 = body(5000, (byte) 6);
        byte[] f1 = writeAwait(ch, b1);                       // threshold 256: compressed
        h.updateThresholdAsync(64).get(5, TimeUnit.SECONDS);  // queued update, ack awaited
        byte[] f2 = writeAwait(ch, b2);                       // 50 < 64: PASSTHROUGH proves the update ran first
        boolean f2Passthrough = false;
        if (f2 != null) {
            long outer = MCK4Validation.readVarInt(f2);
            int used = MCK4Validation.varIntSize((int) outer);
            byte[] inner = Arrays.copyOfRange(f2, used, f2.length);
            f2Passthrough = used + (int) outer == f2.length && MCK4Validation.readVarInt(inner) == 0;
        }
        h.updateThresholdAsync(-1).get(5, TimeUnit.SECONDS);  // disabled
        byte[] f3 = writeAwait(ch, b3);                       // threshold -1: disabled framing (no dataLen)
        boolean ok = f1 != null && Arrays.equals(MCK4Validation.decodeFrameSafe(f1, true), b1)
                && f2Passthrough && Arrays.equals(MCK4Validation.decodeFrameSafe(f2, true), b2)
                && f3 != null && Arrays.equals(MCK4Validation.decodeFrameSafe(f3, false), b3);
        Integer[] tokens = h.ORDERED_UPDATE_TOKENS.toArray(new Integer[0]);
        check("g3: write/update/write ORDER — f1 compressed, f2 passthrough(dataLen=0), f3 disabled(no dataLen), exact tokens 256,64,-1",
                ok && Arrays.equals(tokens, new Integer[] { 256, 64, -1 }),
                "tokens=" + Arrays.toString(tokens));
        closeAwait(ch);
    }

    // ---- gate 4: off-owner update rejected synchronously, queued form documented + acked ----
    static void gate4_offOwnerRejectedAndQueued(DefaultEventExecutorGroup group) throws Exception {
        RustFrameHandler h = new RustFrameHandler();
        EmbeddedChannel ch = attach(group, h); // no pre-attach config: tokens start empty
        final AtomicBoolean rejected = new AtomicBoolean(false);
        Thread wrong = new Thread(() -> {
            try { h.setThreshold(1024); } catch (IllegalStateException ise) { rejected.set(true); }
        });
        wrong.start();
        wrong.join(5000);
        // queued form called from the SAME wrong thread: explicit, acknowledged, applied on the owner
        @SuppressWarnings("unchecked")
        final CompletableFuture<Void>[] box = new CompletableFuture[1];
        Thread queuedCaller = new Thread(() -> box[0] = h.updateThresholdAsync(999));
        queuedCaller.start();
        queuedCaller.join(5000);
        CompletableFuture<Void> ack = box[0];
        boolean acked = ack != null && ack.get(5, TimeUnit.SECONDS) == null;
        Thread et = execThread(exec(group));
        boolean tokensOk = Arrays.equals(h.ORDERED_UPDATE_TOKENS.toArray(new Integer[0]), new Integer[] { 999 });
        check("g4: off-owner sync REJECTED; queued update ACKed, applied ON owner thread, exact token sequence [999]",
                rejected.get() && acked && tokensOk && h.lastUpdateThread == et && h.threshold() == 999,
                "lastUpdateThread=" + h.lastUpdateThread + " execThread=" + et);
        // negative control: token order breaks on any reorder — exact-sequence equality fails on swap
        check("g4-neg: token sequence comparison detects reordering",
                !Arrays.equals(new Integer[] { 999, 1 }, new Integer[] { 1, 999 }), null);
        closeAwait(ch);
    }

    // ---- gate 5: close/removal while work is queued cannot free a context still in use ----
    static void gate5_closeWithQueuedWork(DefaultEventExecutorGroup group) throws Exception {
        EventExecutor ex = exec(group);
        // (a) close while an encode is QUEUED behind a blocked executor:
        // exec order becomes [hold, write-task, marshaled-free] — the free can never
        // overtake the queued write on the single-thread FIFO executor.
        RustFrameHandler h = new RustFrameHandler();
        h.setThreshold(256);
        EmbeddedChannel ch = attach(group, h);
        CountDownLatch hold = new CountDownLatch(1);
        ex.submit(() -> { try { hold.await(10, TimeUnit.SECONDS); } catch (InterruptedException ie) { } }); // NOT awaited
        ChannelFuture w = ch.writeAndFlush(Unpooled.wrappedBuffer(body(5000, (byte) 9))); // queued behind hold
        closeAwait(ch); // deregistration runs HERE on the permissive loop; free marshaled to exec
        hold.countDown(); // exec drains: hold -> write-task -> free
        boolean completed = awaitFuture(w, 5000);
        ex.submit(() -> {}).get(5, TimeUnit.SECONDS); // after free
        check("g5a: close with queued write — write completes cleanly, free runs EXACTLY ONCE, ctx dead, no premature free",
                completed && h.FREES.get() == 1 && (h.ctx == null || !h.ctx.isLive()),
                "writeSuccess=" + w.isSuccess() + (w.cause() == null ? "" : " cause=" + w.cause().getClass().getSimpleName())
                        + " frees=" + h.FREES.get());
        // (b) removal AFTER work completes — BOTH removal modes free exactly once:
        //     (i) explicit pipeline.remove (the NetworkManager replacement path),
        //     (ii) channel close (channelInactive path — 4.1.9 close alone never
        //          calls handlerRemoved for foreign-executor-bound handlers; probe-verified)
        RustFrameHandler h2 = new RustFrameHandler();
        h2.setThreshold(256);
        EmbeddedChannel ch2 = attach(group, h2);
        byte[] b = body(4000, (byte) 2);
        byte[] f = writeAwait(ch2, b);
        boolean produced = f != null && Arrays.equals(MCK4Validation.decodeFrameSafe(f, true), b);
        ch2.pipeline().remove("rustframe");
        ex.submit(() -> {}).get(5, TimeUnit.SECONDS);
        boolean removeFreed = produced && h2.FREES.get() == 1 && (h2.ctx == null || !h2.ctx.isLive());
        RustFrameHandler h3 = new RustFrameHandler();
        h3.setThreshold(256);
        EmbeddedChannel ch3 = attach(group, h3);
        byte[] f3b = writeAwait(ch3, b);
        boolean produced3 = f3b != null && Arrays.equals(MCK4Validation.decodeFrameSafe(f3b, true), b);
        closeAwait(ch3);
        ex.submit(() -> {}).get(5, TimeUnit.SECONDS);
        boolean closeFreed = produced3 && h3.FREES.get() == 1 && (h3.ctx == null || !h3.ctx.isLive());
        check("g5b: removal after completed work — frame produced, free EXACTLY ONCE via BOTH explicit remove and channel close",
                removeFreed && closeFreed, "removeFrees=" + h2.FREES.get() + " closeFrees=" + h3.FREES.get());
        // (c) post-removal native use rejected: direct ctx frame after free returns null
        boolean closedRejected = h2.ctx == null || h2.ctx.frame(body(100, (byte) 1), 256) == null;
        check("g5c: encode/native use after removal returns null (closed code)", closedRejected, null);
    }

    // ---- gate 6: independent channels remain independent ----
    static void gate6_independentChannels(DefaultEventExecutorGroup group) throws Exception {
        RustFrameHandler h1 = new RustFrameHandler();
        h1.setThreshold(256);
        EmbeddedChannel ch1 = attach(group, h1);
        RustFrameHandler h2 = new RustFrameHandler();
        h2.setThreshold(-1); // disabled
        EmbeddedChannel ch2 = attach(group, h2);
        byte[] b = body(3000, (byte) 8);
        byte[] f1 = writeAwait(ch1, b); // compressed framing
        byte[] f2 = writeAwait(ch2, b); // disabled framing
        boolean indep = f1 != null && f2 != null
                && Arrays.equals(MCK4Validation.decodeFrameSafe(f1, true), b)
                && Arrays.equals(MCK4Validation.decodeFrameSafe(f2, false), b)
                && h1.ctx != null && h2.ctx != null && h1.ctx.isLive() && h2.ctx.isLive()
                && h1.ctx != h2.ctx
                && h1.threshold() != h2.threshold();
        check("g6: same group, two channels — different thresholds, different live native ctx, both decode",
                indep, null);
        closeAwait(ch1);
        closeAwait(ch2);
        exec(group).submit(() -> {}).get(5, TimeUnit.SECONDS);
        check("g6: both freed independently (1 free each)", h1.FREES.get() == 1 && h2.FREES.get() == 1, null);
    }

    // ---- gate 7: native-context creation failure handled cleanly ----
    static void gate7_creationFailure(DefaultEventExecutorGroup group) throws Exception {
        RustFrameHandler h = new RustFrameHandler();
        h.testSimulateCreateFailure = true; // offline seam (package-private, property-immune)
        EmbeddedChannel ch = attach(group, h);
        ChannelFuture w = ch.writeAndFlush(Unpooled.wrappedBuffer(body(100, (byte) 1)));
        awaitFuture(w, 5000);
        String msg = w.isSuccess() ? "no-exception" : String.valueOf(w.cause().getMessage());
        check("g7: creation failure -> deterministic encode failure (clean, no NPE)",
                !w.isSuccess() && msg.contains("creation failed at attach"), "msg=" + msg);
        closeAwait(ch);
        exec(group).submit(() -> {}).get(5, TimeUnit.SECONDS);
        check("g7: removal after creation failure still frees exactly once (nothing leaked)",
                h.FREES.get() == 1, "frees=" + h.FREES.get());
    }
}
