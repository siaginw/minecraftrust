package com.rustcraft.bridge;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * M-CK4.1 §2/§3 — ownership/cleanup gates for RustFrameHandler: queued writes
 * around threshold changes, off-owner rejection, marshaled update order, and
 * close/removal behavior. Runs parts on non-owner threads.
 */
public class MCK41Gates {

    static int pass = 0, fail = 0;

    public static void main(String[] args) throws Exception {
        ownerContractAndOrdering();
        cleanupAndOffOwner();
        System.out.println("RESULT: " + pass + " pass, " + fail + " fail");
        System.exit(fail == 0 ? 0 : 1);
    }

    static void check(String n, boolean ok, String d) {
        if (ok) { pass++; System.out.println(n + " -> PASS" + (d == null ? "" : " (" + d + ")")); }
        else { fail++; System.out.println(n + " -> FAIL " + d); }
    }

    static void ownerContractAndOrdering() throws Exception {
        io.netty.channel.embedded.EmbeddedChannel ch = new io.netty.channel.embedded.EmbeddedChannel();
        RustFrameHandler h = new RustFrameHandler();
        // PRE-ATTACHMENT setup: initial threshold (no owner yet — allowed)
        h.setThreshold(256);
        ch.pipeline().addLast("rustframe", h);

        // On-owner threshold changes interleaved with writes: ORDER verified by
        // sequence tokens, not just counters
        h.setThreshold(1024);
        byte[] a = body(5000);
        ch.writeOutbound(io.netty.buffer.Unpooled.wrappedBuffer(a));
        h.setThreshold(64);
        byte[] b = body(300);
        ch.writeOutbound(io.netty.buffer.Unpooled.wrappedBuffer(b));
        h.setThreshold(-1);
        ch.writeOutbound(io.netty.buffer.Unpooled.wrappedBuffer(a));

        Integer[] tokens = h.ORDERED_UPDATE_TOKENS.toArray(new Integer[0]);
        boolean order = tokens.length >= 4
                && tokens[0] == 256 && tokens[1] == 1024 && tokens[2] == 64 && tokens[3] == -1;
        boolean frames = drainDecode(ch, a, true) && drainDecode(ch, b, true) && drainDecode(ch, a, false);
        check("owner: pre-attach setup + on-owner updates; token ORDER 256->1024->64->-1; interleaved frames decode",
                order && frames, "tokens=" + Arrays.toString(tokens));
        ch.close();
    }

    static byte[] body(int n) { byte[] b = new byte[n]; Arrays.fill(b, (byte) 5); return b; }

    static boolean drainDecode(io.netty.channel.embedded.EmbeddedChannel ch, byte[] expect, boolean compressed) {
        io.netty.buffer.ByteBuf out = (io.netty.buffer.ByteBuf) ch.readOutbound();
        if (out == null) return false;
        byte[] f = new byte[out.readableBytes()];
        out.readBytes(f);
        out.release();
        byte[] dec = MCK4Validation.decodeFrameSafe(f, compressed);
        return dec != null && Arrays.equals(dec, expect);
    }

    static void cleanupAndOffOwner() throws Exception {
        // Off-owner update REJECTED (no test executor)
        io.netty.channel.embedded.EmbeddedChannel ch = new io.netty.channel.embedded.EmbeddedChannel();
        RustFrameHandler h = new RustFrameHandler();
        ch.pipeline().addLast("rustframe", h);
        h.setThreshold(256); // owner capture on this thread
        final AtomicBoolean rejected = new AtomicBoolean(false);
        Thread t = new Thread(() -> {
            try { h.setThreshold(512); } catch (Throwable ex) { rejected.set(true); }
        });
        t.start(); t.join();
        check("owner: off-owner setThreshold REJECTED", rejected.get(), null);

        // Marshaled update via the DOCUMENTED order-preserving test executor
        java.util.Queue<Runnable> q = new java.util.concurrent.ConcurrentLinkedQueue<>();
        h.setOwnerExecutorForTestsPost(r -> q.add(r)); // test seam (documented marshal)
        final AtomicBoolean marshaled = new AtomicBoolean(false);
        Thread t2 = new Thread(() -> {
            try { h.setThreshold(999); marshaled.set(true); } catch (Throwable ex) { marshaled.set(false); }
        });
        t2.start(); t2.join();
        Runnable r = q.poll();
        if (r != null) r.run(); // owner thread drains the queue
        check("owner: off-owner update MARSHALED through documented executor; order token after drain",
                marshaled.get() && !q.isEmpty() == false
                        && containsToken(h, 999), null);
        ch.close();
        // cleanup: handlerRemoved frees native ctx (also closes on channel close)
        check("cleanup: native ctx freed after channel close", !h.ctx.isLive(), null);
        // use-after-close via adapter encode path -> exception (codec semantics)
        boolean closedRejected = false;
        try {
            io.netty.channel.embedded.EmbeddedChannel dead = new io.netty.channel.embedded.EmbeddedChannel();
            // writing through a closed handler's channel is Netty-rejected; direct encode:
            byte[] f = h.ctx.frame(body(100), 256);
            closedRejected = f == null;
        } catch (Throwable ex) { closedRejected = true; }
        check("cleanup: encode after close returns null", closedRejected, null);
    }

    static boolean containsToken(RustFrameHandler h, int v) {
        for (Integer t : h.ORDERED_UPDATE_TOKENS) if (t == v) return true;
        return false;
    }
}
