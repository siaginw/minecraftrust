package com.rustcraft.bridge;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicLong;

/**
 * LIVE region READ authority handle (RUST_REGION_READ_DECOMPRESSION_AUTHORITY).
 *
 * One RegionReadCtx per transformed RegionFile instance, created lazily under
 * the instance monitor (func_76704_a is synchronized), freed exactly once via
 * the RegionFile close hook (AtomicLong CAS prevents double-free).
 *
 * read(x, z) returns:
 *   > 0  SUCCESS — the decompressed length; the direct buffer holds exactly
 *          that many bytes
 *   0    MISSING — vanilla returns null for this chunk
 *   < 0  negated status: -4 OUTPUT_TOO_SMALL, -2 CORRUPT_ENTRY,
 *        -3 UNSUPPORTED_COMPRESSION, -5 IO_ERROR — caller MUST fail closed
 *        to the vanilla body; no bytes were produced
 *
 * Buffering (goal §6): Rust decompresses into a grow-only DIRECT buffer; the
 * caller-visible copy direct→heap byte[] is EXPLICIT and measured. The
 * direct-stream experiment (goal §24) may remove that copy later; semantic
 * compatibility (DataInputStream over BAIS) is not sacrificed for zero-copy.
 */
public final class RegionReadCtx {

    public static final boolean LOADED = RegionWriteCtx.LOADED; // same DLL

    private static native long create(long pathAddr, int pathLen);
    private static native long read(long h, int x, int z, long outAddr, int outCap);
    private static native int closeRaw(long h);

    public static final int ST_MISSING = 0;
    public static final int ST_CORRUPT_ENTRY = -2;
    public static final int ST_UNSUPPORTED_COMPRESSION = -3;
    public static final int ST_OUTPUT_TOO_SMALL = -4;
    public static final int ST_IO_ERROR = -5;

    /** Copy accounting (goal §6): direct → heap bytes handed to Java. */
    public static final AtomicLong COPY_DIRECT_TO_HEAP_BYTES = new AtomicLong();
    public static final AtomicLong READ_CALLS = new AtomicLong();

    private final AtomicLong handle = new AtomicLong(0);
    private ByteBuffer pathBuf;
    private ByteBuffer outBuf;
    private byte[] heapCopy;
    /** Status of the most recent readFully on this ctx (thread-confined:
     *  the read seam is synchronized per RegionFile). 0 = success/missing. */
    public volatile int lastStatus = Integer.MIN_VALUE;

    public boolean isLive() { return handle.get() > 0; }

    /** Idempotent; called under the RegionFile instance monitor. */
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
     * Full read pipeline: Rust location lookup → sector read → validation →
     * decompression → explicit direct→heap copy. Returns the uncompressed
     * record bytes, or null on MISSING / any failure (caller fail-closes to
     * vanilla). Never throws; never returns a partial buffer.
     */
    public byte[] readFully(int x, int z) {
        long h = handle.get();
        if (h <= 0) return null;
        try {
            READ_CALLS.incrementAndGet();
            if (outBuf == null) {
                outBuf = ByteBuffer.allocateDirect(1024 * 1024);
            }
            lastStatus = ST_IO_ERROR;
            long n = read(h, x, z, address(outBuf), outBuf.capacity());
            if (n == ST_OUTPUT_TOO_SMALL) {
                // grow once to a generous size; the engine caps at 32 MiB and
                // fail-closes anything larger to vanilla
                outBuf = ByteBuffer.allocateDirect(32 * 1024 * 1024);
                n = read(h, x, z, address(outBuf), outBuf.capacity());
            }
            if (n == ST_MISSING) {
                lastStatus = ST_MISSING;
                return null;
            }
            if (n < 0) {
                lastStatus = (int) n; // fail-closed status for classification
                return null;
            }
            lastStatus = 0;
            byte[] heap = heapCopy != null && heapCopy.length >= n
                    ? heapCopy : new byte[(int) n];
            outBuf.position(0);
            outBuf.get(heap, 0, (int) n);
            heapCopy = heap;
            byte[] exact = new byte[(int) n];
            System.arraycopy(heap, 0, exact, 0, (int) n);
            COPY_DIRECT_TO_HEAP_BYTES.addAndGet(n);
            return exact;
        } catch (Throwable t) {
            lastStatus = ST_IO_ERROR;
            return null;
        }
    }

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
