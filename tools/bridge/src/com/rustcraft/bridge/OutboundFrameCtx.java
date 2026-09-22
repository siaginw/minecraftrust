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
    public static volatile int LAST_ERR;
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
    /** Per-context retry-result direct buffer (i32 native-order). Per-context
     *  under the single-owner contract — no thread-idiosyncratic init, no
     *  disconnected int[] side channel. Cleared before EVERY native call. */
    private ByteBuffer retryResult = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder());

    /** Explicit one-retry limit state. */
    private int retriesThisCall;

    /** M-CK4.1 injection seam (offline tests ONLY): when > 0, the first
     *  frameEncode of the next frame() call is invoked with exactly this
     *  output capacity (forcing a real ERR_CAPACITY through the real JNI
     *  path). Cannot be activated by any runtime property — package-private
     *  field, set only by test code in this package. */
    int testForceFirstCapacity = -1;

    /** M-CK4.2 N-shot injection seam (offline tests ONLY): when > 0, the next
     *  testForceCapacityTimes frameEncode calls (INCLUDING the retry inside
     *  the same frame() call) are invoked with output capacity
     *  min(cap, testForceCapacityValue). Used to exercise the SECOND capacity
     *  failure: the one-retry ceiling must terminate the call with null
     *  instead of looping. Same rules as testForceFirstCapacity —
     *  package-private, property-immune, real JNI path. */
    int testForceCapacityTimes = 0;
    int testForceCapacityValue = 64;

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

            retriesThisCall = 0;
            while (true) {
                int bound = maxOutputLen(body.length) + 16;
                int cap = Math.max(outBuf == null ? 0 : outBuf.capacity(), bound);
                int actualOutCap;                   // capacity actually handed to native
                if (testForceFirstCapacity > 0) {
                    // injection: real insufficient capacity through the real JNI
                    // call regardless of the (larger) retained buffer size
                    actualOutCap = Math.min(cap, testForceFirstCapacity);
                    testForceFirstCapacity = -1;    // one-shot
                } else if (testForceCapacityTimes > 0) {
                    // N-shot injection: repeat the real insufficient-capacity
                    // failure across the retry so the one-retry ceiling is
                    // exercised by a SECOND real failure, not by inspection
                    actualOutCap = Math.min(cap, testForceCapacityValue);
                    testForceCapacityTimes--;
                } else {
                    actualOutCap = cap;
                }
                if (outBuf == null || outBuf.capacity() < cap) {
                    outBuf = ByteBuffer.allocateDirect(cap).order(ByteOrder.nativeOrder());
                    GROW_EVENTS.incrementAndGet();
                }
                retryResult.clear();                // cleared before EVERY native op
                retryResult.putInt(0, 0);
                FRAME_OPS.incrementAndGet();
                int n = frameEncode(h, address(inBuf), body.length, address(outBuf), actualOutCap,
                        threshold, address(retryResult));
                if (n > 0) {
                    LAST_ERR = 0;
                    RETAINED_APPROX = (inBuf == null ? 0 : inBuf.capacity()) + outBuf.capacity();
                    byte[] res = new byte[n];
                    outBuf.position(0);
                    outBuf.get(res, 0, n);
                    return res;
                }
                LAST_ERR = n;
                if (n == ERR_CAPACITY) {
                    if (retriesThisCall >= 1) return null;   // EXACTLY one retry, then terminate
                    retriesThisCall++;
                    int needed = retryResult.getInt(0);       // the actual native-written i32, native order
                    LAST_RETRY_NEEDED = needed;               // observable even when guards reject
                    // Reject invalid bounds and resource-policy violations. POLICY
                    // (16 MiB) is a resource ceiling, DISTINCT from the 3-byte
                    // wire-format limit enforced in Rust. needed must also exceed
                    // the capacity that just failed, else the native bound is
                    // nonsensical.
                    if (needed <= 0 || needed <= actualOutCap) return null;
                    if (needed > policyLimit) return null;
                    RETRY_EVENTS.incrementAndGet();
                    if (needed > outBuf.capacity()) {
                        outBuf = ByteBuffer.allocateDirect(needed).order(ByteOrder.nativeOrder());
                        GROW_EVENTS.incrementAndGet();
                    } // else: the retained buffer already suffices — retry at full capacity
                    continue;
                }
                return null; // backend/too-large/invalid/closed: no partial output
            }
        } catch (Throwable t) {
            return null;
        }
    }

    /** Test observability: the needed value read from the native-written i32
     *  on the last capacity retry (-1 = none). */
    public volatile int LAST_RETRY_NEEDED = -1;

    /** Resource-policy ceiling for retry allocation (distinct from wire limits).
     *  Instance field: offline tests may LOWER it to exercise the guard via a
     *  real JNI capacity error; production default 16 MiB. */
    int policyLimit = 16 << 20;

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
