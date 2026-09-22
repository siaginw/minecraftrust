package com.rustcraft.bridge;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicLong;

/**
 * M2C: per-instance native compressor handle with explicit lifecycle.
 *
 * Ownership: one CompressionCtx per NativeCompressionEncoder instance (per
 * Netty channel). No global lock - separate channels never serialize.
 *
 * Lifecycle: created lazily on first use on the channel's EventLoop; freed
 * exactly once via handlerRemoved (channelInactive/removal/disconnect) using
 * an AtomicLong CAS (0 = no ctx, >0 = live handle) which makes double-free
 * and use-after-free impossible: after free the field reads 0 and every
 * later call short-circuits to a Java fallback.
 *
 * Buffering: caller-owned direct ByteBuffers; addresses computed in Java
 * and passed as longs. No GetPrimitiveArrayCritical anywhere. Native holds
 * slices only for the duration of one call.
 */
public final class CompressionCtx {

    public static final boolean LOADED = loadNative();

    private static boolean loadNative() {
        try { System.loadLibrary("rustcraft_ffi"); return true; }
        catch (Throwable t) { return false; }
    }

    static {}

    private static native long create();
    private static native int compress(long h, long inAddr, int inLen, long outAddr, int outCap);
    private static native int freeRaw(long h);

    /** handle: 0 = none/free. CAS on transition to 0 prevents double-free. */
    private final AtomicLong handle = new AtomicLong(0);

    public boolean isLive() { return handle.get() > 0; }

    /** Idempotent. Must be called from the channel's EventLoop (removal hook). */
    public void free() {
        long h = handle.getAndSet(0);
        if (h > 0) freeRaw(h);
    }

    /** Safe upper bound on zlib expansion (mirrors the Rust constant). */
    public static long maxOutputLen(int n) {
        return n + (n / 8192 + 2) * 5L + 16;
    }

    private static volatile Method ADDRESS_M;
    private static long address(ByteBuffer b) {
        try {
            Method m = ADDRESS_M;
            if (m == null) {
                m = b.getClass().getMethod("address");
                m.setAccessible(true);
                ADDRESS_M = m;
            }
            return (Long) m.invoke(b);
        } catch (Throwable t) {
            throw new IllegalStateException("direct-buffer address unavailable", t);
        }
    }

    /**
     * Compress plaintext into a fresh heap byte[] (allocated by caller-size
     * direct out buffer, copied back). Returns null on ANY native failure
     * (caller falls back); never throws for native errors.
     */
    /**
     * M-CK2 OPTIMIZED path (Changes A+B): context-owned reusable direct scratch
     * (grown on demand, retained for the context lifetime) and bulk output copy.
     *
     * OWNERSHIP CONTRACT: a CompressionCtx is single-owner (one Netty channel /
     * event loop, matching the production NativeCompressionEncoder wiring and the
     * previously verified M2C lifecycle model). The scratch buffers are therefore
     * per-instance and unsynchronized BY DESIGN; independent contexts may run
     * concurrently with their own scratch. The result byte[] is freshly allocated
     * and owned solely by the caller — native writes complete before the bulk
     * copy, and no raw pointer survives the call (addresses are re-resolved from
     * the strongly-referenced ByteBuffers each call, so a growth realloc is safe).
     *
     * After free(): handle reads 0 (CAS) and compress returns null — documented,
     * tested. Retained direct memory is reclaimed by the JVM's direct-buffer
     * reclamation (physical reclamation is NOT claimed to be immediate).
     */
    private ByteBuffer scratchIn, scratchOut;
    public static final AtomicLong SCRATCH_ALLOC_EVENTS = new AtomicLong();
    public static final AtomicLong SCRATCH_GROW_EVENTS = new AtomicLong();
    public static volatile long SCRATCH_RETAINED_BYTES_APPROX;

    public byte[] compress(byte[] input) {
        long h = handle.get();
        if (h <= 0) return null;
        try {
            if (scratchIn == null || scratchIn.capacity() < Math.max(1, input.length)) {
                int cap = Math.max(1, input.length);
                scratchIn = ByteBuffer.allocateDirect(cap);
                if (scratchOut != null) SCRATCH_GROW_EVENTS.incrementAndGet();
                SCRATCH_ALLOC_EVENTS.incrementAndGet();
                trackRetained();
            }
            if (scratchOut == null || scratchOut.capacity() < (int) maxOutputLen(input.length)) {
                scratchOut = ByteBuffer.allocateDirect((int) maxOutputLen(input.length));
                if (scratchIn != null) SCRATCH_GROW_EVENTS.incrementAndGet();
                SCRATCH_ALLOC_EVENTS.incrementAndGet();
                trackRetained();
            }
            scratchIn.clear();
            if (input.length > 0) scratchIn.put(input).flip();
            long outAddr = address(scratchOut);
            int n = compress(h, address(scratchIn), input.length, outAddr, scratchOut.capacity());
            if (n < 0 || n > scratchOut.capacity()) return null; // incomplete write never becomes output
            byte[] res = new byte[n];
            scratchOut.position(0);
            scratchOut.get(res, 0, n); // bulk copy (Change B)
            return res;
        } catch (Throwable t) {
            return null; // Throwable-safe: fallback, never propagate native issues
        }
    }

    private void trackRetained() {
        SCRATCH_RETAINED_BYTES_APPROX =
                (scratchIn == null ? 0 : scratchIn.capacity()) + (scratchOut == null ? 0 : scratchOut.capacity());
    }

    /** Pre-change glue preserved verbatim for A/B benchmarking (baseline arm). */
    byte[] compressBaseline(byte[] input) {
        long h = handle.get();
        if (h <= 0) return null;
        try {
            ByteBuffer in = ByteBuffer.allocateDirect(Math.max(1, input.length));
            if (input.length > 0) in.put(input).flip();
            ByteBuffer out = ByteBuffer.allocateDirect((int) maxOutputLen(input.length));
            int n = compress(h, address(in), input.length, address(out), out.capacity());
            if (n < 0) return null;
            byte[] res = new byte[n];
            for (int i = 0; i < n; i++) res[i] = out.get(i);
            return res;
        } catch (Throwable t) {
            return null;
        }
    }

    /** For offline tests: raw error code path with capacity override. */
    public int compressCode(byte[] input, int outCapOverride) {
        long h = handle.get();
        if (h <= 0) return -4;
        try {
            ByteBuffer in = ByteBuffer.allocateDirect(Math.max(1, input.length));
            if (input.length > 0) in.put(input).flip();
            ByteBuffer out = ByteBuffer.allocateDirect(Math.max(1, outCapOverride));
            return compress(h, address(in), input.length, address(out), outCapOverride);
        } catch (Throwable t) {
            return -2;
        }
    }

    /** Create the native context (idempotent). Returns true if live. */
    public boolean ensureCreated() {
        if (handle.get() > 0) return true;
        long h = create();
        if (h > 0) {
            handle.compareAndSet(0, h);
            return true;
        }
        return false;
    }
}
