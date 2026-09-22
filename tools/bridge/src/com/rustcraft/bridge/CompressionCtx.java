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

    private static long address(ByteBuffer b) {
        try {
            Method m = b.getClass().getMethod("address");
            m.setAccessible(true);
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
    public byte[] compress(byte[] input) {
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
            return null; // Throwable-safe: fallback, never propagate native issues
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
