package com.rustcraft.bridge;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicLong;

/**
 * LIVE region-write authority handle (RUST_REGION_WRITE_AUTHORITY).
 *
 * EXPERIMENTAL: inert unless the owning hook (RustRegionWriteHook) is enabled
 * with -Drustcraft.regionWriteExperiment; PRODUCTION_AUTHORITY stays false.
 *
 * Ownership: one RegionWriteCtx per transformed RegionFile instance, created
 * lazily on the first func_76706_a invocation (that method is synchronized on
 * the RegionFile, so creation is race-free) and freed exactly once from the
 * injected RegionFile.close hook via an AtomicLong CAS — after free the field
 * reads 0 and every later call short-circuits to a Java fallback.
 *
 * Buffering: caller-owned direct ByteBuffers; addresses computed in Java and
 * passed as longs. No GetPrimitiveArrayCritical anywhere.
 *
 * Status codes (Rust mirror): 0 success; negatives are negated engine codes:
 * -1 STALE_GENERATION, -2 INVALID_RECORD, -3 IO_ERROR, -4 CAPACITY_ERROR,
 * -5 NOT_ELIGIBLE, -6 BAD_HANDLE.
 */
public final class RegionWriteCtx {

    public static final boolean LOADED = loadNative();

    private static boolean loadNative() {
        try { System.loadLibrary("rustcraft_ffi"); return true; }
        catch (Throwable t) { return false; }
    }

    static {}

    private static native long create(long pathAddr, int pathLen);
    private static native long write(long h, int x, int z, long payloadAddr, int payloadLen, long generation);
    private static native int noteExternal(long h, int x, int z, int entry, long generation);
    private static native int closeRaw(long h);
    private static native int statsSnapshot(long h, long outAddr, int outCap);
    private static native int generationsSnapshot(long h, long outAddr, int outCap);
    private static native int usedSnapshot(long h, long outAddr, int outCap);

    public static final int ST_SUCCESS = 0;
    public static final int ST_STALE_GENERATION = -1;
    public static final int ST_INVALID_RECORD = -2;
    public static final int ST_IO_ERROR = -3;
    public static final int ST_CAPACITY_ERROR = -4;
    public static final int ST_NOT_ELIGIBLE = -5;
    public static final int ST_BAD_HANDLE = -6;

    /** handle: 0 = none/free. CAS on transition to 0 prevents double-free. */
    private final AtomicLong handle = new AtomicLong(0);
    /** Retained direct memory holding the UTF-8 path (lifetime of the ctx). */
    private ByteBuffer pathBuf;

    public boolean isLive() { return handle.get() > 0; }

    /** Idempotent. Called from the RegionFile's synchronized write seam. */
    public boolean ensureCreated(String path) {
        if (handle.get() > 0) return true;
        if (!LOADED) return false;
        try {
            byte[] utf8 = path.getBytes("UTF-8");
            if (pathBuf == null || pathBuf.capacity() < utf8.length) {
                pathBuf = ByteBuffer.allocateDirect(Math.max(64, utf8.length));
            }
            pathBuf.clear();
            pathBuf.put(utf8).flip();
            long h = create(address(pathBuf), pathBuf.remaining());
            if (h > 0) {
                handle.compareAndSet(0, h);
                return handle.get() > 0;
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Admitted write. Returns the new location entry (>0; sector = entry>>>8,
     * sectorCount = entry & 0xFF) or a negative status. The payload buffer is
     * caller-owned direct memory holding the OPAQUE_FINAL_REGION_PAYLOAD (the
     * raw DEFLATE stream; Rust adds the length prefix and type byte exactly as
     * vanilla's func_76706_a does internally).
     */
    public long write(int x, int z, ByteBuffer payload, int len, long generation) {
        long h = handle.get();
        if (h <= 0) return ST_BAD_HANDLE;
        try {
            return write(h, x, z, address(payload), len, generation);
        } catch (Throwable t) {
            return ST_IO_ERROR;
        }
    }

    /** Vanilla fallback notice: Java completed its own write and holds entry. */
    public int noteExternal(int x, int z, int entry, long generation) {
        long h = handle.get();
        if (h <= 0) return ST_BAD_HANDLE;
        try {
            return noteExternal(h, x, z, entry, generation);
        } catch (Throwable t) {
            return ST_IO_ERROR;
        }
    }

    /** Committed generation floors (1024 entries) or null on any failure. */
    public long[] floors() {
        long h = handle.get();
        if (h <= 0) return null;
        ByteBuffer buf = ByteBuffer.allocateDirect(1024 * 8)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN); // Rust writes LE u64s
        try {
            int n = generationsSnapshot(h, address(buf), 1024 * 8);
            if (n != 1024) return null;
            long[] out = new long[1024];
            for (int i = 0; i < 1024; i++) out[i] = buf.getLong(i * 8);
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Sector used-map (0/1 per sector) or null on any failure. */
    public byte[] usedMap() {
        long h = handle.get();
        if (h <= 0) return null;
        try {
            int probe = usedSnapshot(h, 1, 0);
            if (probe <= 0) return null;
            int n = probe;
            ByteBuffer buf = ByteBuffer.allocateDirect(n);
            int got = usedSnapshot(h, address(buf), n);
            if (got != n) return null;
            byte[] out = new byte[n];
            buf.get(out);
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    /** WriteStats snapshot (8 LE u64s) or null on any failure. */
    public long[] stats() {
        long h = handle.get();
        if (h <= 0) return null;
        ByteBuffer buf = ByteBuffer.allocateDirect(8 * 8)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN); // Rust writes LE u64s
        try {
            int n = statsSnapshot(h, address(buf), 8 * 8);
            if (n != 8) return null;
            long[] out = new long[8];
            for (int i = 0; i < 8; i++) out[i] = buf.getLong(i * 8);
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Idempotent. Called from the injected RegionFile.close hook. */
    public void free() {
        long h = handle.getAndSet(0);
        if (h > 0) {
            try { closeRaw(h); } catch (Throwable ignore) { }
        }
    }

    private static volatile Method ADDRESS_M;
    static long address(ByteBuffer b) {
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
}
