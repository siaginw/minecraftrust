package com.rustcraft.bridge;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicLong;

/**
 * M-CK3 — Java adapter for the Rust outbound frame engine.
 *
 * One synchronous native operation per frame (threshold decision + optional
 * compression + compression framing + outer length framing, all in Rust).
 * Caller-owned direct input/output buffers + reusable native context;
 * explicit lengths/capacities; success-only publication (partial output is
 * never a completed frame; capacity errors return the safe retry bound).
 *
 * OWNERSHIP: single-owner per context (one connection/event loop, mirroring
 * the production encoder-per-channel wiring); independent contexts may run
 * concurrently with their own scratch. The result byte[] is freshly
 * allocated and owned solely by the caller. Input direct buffer is written
 * once per call from the immutable body; addresses are re-resolved from the
 * strong ByteBuffer references each call. No GetPrimitiveArrayCritical.
 * After free(): handle reads 0 (CAS) and frame() returns the closed code.
 */
public final class OutboundFrameCtx {

    public static final int ERR_CAPACITY = -1, ERR_BACKEND = -2, ERR_TOO_LARGE = -3, ERR_INVALID = -4, ERR_CLOSED = -5;

    private final AtomicLong handle = new AtomicLong(0);
    // reusable caller-owned direct scratch (input mirror + output)
    private ByteBuffer inBuf, outBuf;
    public static final AtomicLong FRAME_OPS = new AtomicLong();
    public static final AtomicLong RETRY_EVENTS = new AtomicLong();
    public static final AtomicLong GROW_EVENTS = new AtomicLong();
    public static volatile long RETAINED_APPROX;

    private static native long frameCreate();
    private static native int frameFree(long h);
    private static native int frameEncode(long h, long inAddr, int inLen, long outAddr, int outCap,
                                          int threshold, long retryBoundAddr);

    static {
        try { System.loadLibrary("rustcraft_ffi"); } catch (Throwable t) {
            try { System.load(new java.io.File("rustcraft_ffi.dll").getAbsolutePath()); } catch (Throwable t2) { }
        }
    }

    public static OutboundFrameCtx create() {
        OutboundFrameCtx c = new OutboundFrameCtx();
        c.handle.set(frameCreate());
        return c;
    }

    public boolean isLive() { return handle.get() != 0; }

    /** Frees the native context (idempotent via CAS). */
    public void free() {
        long h = handle.getAndSet(0);
        if (h != 0) frameFree(h);
        inBuf = null; outBuf = null;
        RETAINED_APPROX = 0;
    }

    /**
     * Encodes one complete frame from the immutable body. Returns the frame
     * bytes (caller-owned) or null on any error (caller falls back).
     */
    public byte[] frame(byte[] body, int threshold) {
        long h = handle.get();
        if (h == 0) return null;
        if (body == null || body.length == 0) return null;
        try {
            if (inBuf == null || inBuf.capacity() < body.length) {
                inBuf = ByteBuffer.allocateDirect(Math.max(64, body.length)).order(ByteOrder.nativeOrder());
                if (outBuf != null) GROW_EVENTS.incrementAndGet();
            }
            inBuf.clear();
            inBuf.put(body).flip();

            int retry = 0;
            while (true) {
                int bound = maxOutputLen(body.length) + 16;
                int cap = Math.max(outBuf == null ? 0 : outBuf.capacity(), bound);
                if (outBuf == null || outBuf.capacity() < cap) {
                    outBuf = ByteBuffer.allocateDirect(cap).order(ByteOrder.nativeOrder());
                    GROW_EVENTS.incrementAndGet();
                }
                FRAME_OPS.incrementAndGet();
                int n = frameEncode(h, address(inBuf), body.length, address(outBuf), outBuf.capacity(),
                        threshold, RETRY_BB_TL.get() == null ? 0 : address(RETRY_BB_TL.get()));
                if (n > 0) {
                    RETAINED_APPROX = (inBuf == null ? 0 : inBuf.capacity()) + outBuf.capacity();
                    byte[] res = new byte[n];
                    outBuf.position(0);
                    outBuf.get(res, 0, n);
                    return res;
                }
                if (n == ERR_CAPACITY) {
                    RETRY_EVENTS.incrementAndGet();
                    int needed = RETRY_INT_TL.get()[0];
                    if (needed <= 0 || needed > (1 << 24)) return null; // refuse unbounded retry
                    outBuf = ByteBuffer.allocateDirect(needed).order(ByteOrder.nativeOrder());
                    GROW_EVENTS.incrementAndGet();
                    continue; // exactly one retry loop; a second capacity error returns via needed check
                }
                return null; // backend/too-large/invalid/closed: no partial output
            }
        } catch (Throwable t) {
            return null;
        }
    }

    private static final ThreadLocal<ByteBuffer> RETRY_BB_TL = new ThreadLocal<>();
    private static final ThreadLocal<int[]> RETRY_INT_TL = ThreadLocal.withInitial(() -> new int[1]);
    static { try { RETRY_BB_TL.set(ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())); } catch (Throwable ignore) { } }

    static int maxOutputLen(int n) { return n + (n / 8192 + 2) * 5 + 16; }

    private static volatile Method ADDRESS_M;
    private static long address(ByteBuffer b) throws Exception {
        Method m = ADDRESS_M;
        if (m == null) {
            m = b.getClass().getMethod("address");
            m.setAccessible(true);
            ADDRESS_M = m;
        }
        return (Long) m.invoke(b);
    }
}
