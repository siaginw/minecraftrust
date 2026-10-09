package com.rustcraft.bridge;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;

/**
 * Java side of the Rust block-light kernel batch surface (crates/ffi
 * light_batch.rs — the exports bind this exact class name):
 *
 *   create(outsideLight) -> handle (>0) or 0
 *   addBatch(handle, coordsAddr, valuesAddr, n, kind) -> n added or <0
 *       coords: n * 3 packed little-endian i32 (x,y,z interleaved) in a
 *               direct buffer; values: n bytes
 *       kind:   0 = initial light, 1 = opacity, 2 = emission, 3 = notify
 *   propagate(handle) -> changed cells or <0 (-2 already propagated,
 *       -5 panic, -6 bad handle)
 *   readResult(handle, x, y, z) -> 0..15 or <0
 *   close(handle) -> idempotent
 *
 * One propagation per batch (propagate-once): removal pass drains to
 * completion, then addition — the proven two-queue kernel semantics.
 */
public final class LightBatchCtx {

    public static final boolean LOADED = loadNative();

    private static boolean loadNative() {
        try {
            System.loadLibrary("rustcraft_ffi");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static native long create(int outsideLight);
    private static native long addBatch(long h, long coordsAddr,
                                        long valuesAddr, int n, int kind);
    private static native long propagate(long h);
    private static native int readResult(long h, int x, int y, int z);
    private static native int closeRaw(long h);

    private long handle;
    /** LITTLE_ENDIAN: the Rust side reads packed native-endian i32s
     *  (x86_64) — Java's default big-endian putInt byte-swaps every
     *  coordinate, sending all writes to garbage cells (diag11 evidence). */
    private final ByteBuffer coords =
            ByteBuffer.allocateDirect(3 * 4)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN);
    private final ByteBuffer values = ByteBuffer.allocateDirect(1);
    /** growable staging for bulk adds (job-scale batches) */
    private ByteBuffer bulkCoords;
    private ByteBuffer bulkValues;

    private LightBatchCtx(int outsideLight) {
        this.handle = create(outsideLight);
    }

    /** Open a batch context, or null when natives are unavailable or the
     *  kernel refused creation. */
    public static LightBatchCtx open(int outsideLight) {
        if (!LOADED) return null;
        try {
            LightBatchCtx c = new LightBatchCtx(outsideLight);
            return c.handle > 0 ? c : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private long handle() {
        long h = handle;
        if (h <= 0) throw new IllegalStateException("closed batch ctx");
        return h;
    }

    /** Single-cell add of one kind (0 initial / 1 opacity / 2 emission /
     *  3 notify). Throws on kernel rejection — silent adds once corrupted a
     *  whole session's parity evidence. */
    public boolean add(int x, int y, int z, int v, int kind) {
        coords.putInt(0, x);
        coords.putInt(4, y);
        coords.putInt(8, z);
        values.put(0, (byte) v);
        long n = addBatch(handle(), address(coords), address(values), 1, kind);
        if (n < 0) {
            throw new IllegalStateException("addBatch failed n=" + n
                    + " kind=" + kind);
        }
        return true;
    }

    /** Bulk add: one JNI crossing for the whole batch. coordsXyz is
     *  interleaved x,y,z ints; values one byte per cell; kind as in add().
     *  Throws on kernel rejection. */
    public boolean addBulk(int[] coordsXyz, byte[] vals, int n, int kind) {
        int coordBytes = n * 3 * 4;
        if (bulkCoords == null || bulkCoords.capacity() < coordBytes) {
            bulkCoords = ByteBuffer
                    .allocateDirect(Math.max(256, coordBytes * 2))
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN);
            bulkValues = ByteBuffer.allocateDirect(Math.max(256, n * 2));
        }
        for (int i = 0; i < n; i++) {
            bulkCoords.putInt(i * 12 + 0, coordsXyz[i * 3 + 0]);
            bulkCoords.putInt(i * 12 + 4, coordsXyz[i * 3 + 1]);
            bulkCoords.putInt(i * 12 + 8, coordsXyz[i * 3 + 2]);
            bulkValues.put(i, vals[i]);
        }
        long r = addBatch(handle(), address(bulkCoords), address(bulkValues),
                n, kind);
        if (r < 0) {
            throw new IllegalStateException("bulk addBatch failed n=" + r
                    + " kind=" + kind);
        }
        return true;
    }

    /** Run the frontier once. Returns changed-cell count or negative. */
    public long propagate() {
        return propagate(handle());
    }

    /** Resulting light 0..15 or negative. */
    public int readResult(int x, int y, int z) {
        return readResult(handle(), x, y, z);
    }

    public void close() {
        long h = handle;
        handle = 0;
        if (h > 0) {
            try {
                closeRaw(h);
            } catch (Throwable ignore) {
                // idempotent on the Rust side; nothing to recover
            }
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
