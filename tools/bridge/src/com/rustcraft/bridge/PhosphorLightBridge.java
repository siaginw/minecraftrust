package com.rustcraft.bridge;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;

/**
 * LIVE block-light propagation bridge (RUST_BLOCK_LIGHT_PROPAGATION_AUTHORITY).
 *
 * Marshals one propagation JOB (one Phosphor
 * processLightUpdatesForType(BLOCK) drain) into the Rust LightBatchCtx JNI
 * surface. Three staged buffers (initial light / opacity / emission) plus a
 * notify buffer are bulk-flushed into Rust, which runs the proven frontier
 * kernel. Native failures surface via flush return values; callers fail
 * closed to Java/Phosphor.
 */
public final class PhosphorLightBridge {

    public static final boolean LOADED = RegionWriteCtx.LOADED; // same DLL

    private static native long create(int outsideLight);
    private static native long addBatch(long h, long coordsAddr, long valuesAddr,
                                        int n, int kind);
    private static native long propagate(long h);
    private static native int readResult(long h, int x, int y, int z);
    private static native int closeRaw(long h);

    private final long handle;
    /** Job bounds: inclusive min, exclusive max. */
    public final int minX, minY, minZ, maxX, maxY, maxZ;
    public final int cells;
    private final ByteBuffer coordsI, valuesI;
    private final ByteBuffer coordsO, valuesO;
    private final ByteBuffer coordsE, valuesE;
    private final ByteBuffer coordsN, valuesN;
    private int usedI, usedO, usedE, usedN;
    private final int cap;

    private PhosphorLightBridge(int minX, int minY, int minZ,
                                int maxX, int maxY, int maxZ) {
        this.handle = create(0); // outside world = dark (vanilla)
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = maxZ;
        this.cells = (maxX - minX) * (maxY - minY) * (maxZ - minZ);
        this.cap = cells;
        this.coordsI = ByteBuffer.allocateDirect(cells * 3 * 4);
        this.valuesI = ByteBuffer.allocateDirect(cells);
        this.coordsO = ByteBuffer.allocateDirect(cells * 3 * 4);
        this.valuesO = ByteBuffer.allocateDirect(cells);
        this.coordsE = ByteBuffer.allocateDirect(cells * 3 * 4);
        this.valuesE = ByteBuffer.allocateDirect(cells);
        this.coordsN = ByteBuffer.allocateDirect(cells * 3 * 4);
        this.valuesN = ByteBuffer.allocateDirect(cells);
    }

    /** Null on any failure (caller fail-closes to Java/Phosphor). */
    public static PhosphorLightBridge open(int minX, int minY, int minZ,
                                           int maxX, int maxY, int maxZ) {
        if (!LOADED) return null;
        try {
            PhosphorLightBridge b = new PhosphorLightBridge(minX, minY, minZ,
                                                            maxX, maxY, maxZ);
            return b.handle > 0 ? b : null;
        } catch (Throwable t) {
            return null;
        }
    }

    public boolean addInitial(int x, int y, int z, int light) {
        if (usedI >= cap) return false;
        put(coordsI, valuesI, usedI, x, y, z, light);
        usedI++;
        return true;
    }

    public boolean addOpacity(int x, int y, int z, int opacity) {
        if (usedO >= cap) return false;
        put(coordsO, valuesO, usedO, x, y, z, opacity);
        usedO++;
        return true;
    }

    public boolean addEmission(int x, int y, int z, int emission) {
        if (usedE >= cap) return false;
        put(coordsE, valuesE, usedE, x, y, z, emission);
        usedE++;
        return true;
    }

    public boolean addNotify(int x, int y, int z) {
        if (usedN >= cap) return false;
        put(coordsN, valuesN, usedN, x, y, z, 0);
        usedN++;
        return true;
    }

    private static void put(ByteBuffer c, ByteBuffer v, int i,
                            int x, int y, int z, int value) {
        c.putInt(i * 3 * 4 + 0, x);
        c.putInt(i * 3 * 4 + 4, y);
        c.putInt(i * 3 * 4 + 8, z);
        v.put(i, (byte) value);
    }

    public int flushInitial() { return flush(0, coordsI, valuesI, usedI); }
    public int flushOpacity() { return flush(1, coordsO, valuesO, usedO); }
    public int flushEmission() { return flush(2, coordsE, valuesE, usedE); }
    public int flushNotify() { return flush(3, coordsN, valuesN, usedN); }

    private int flush(int kind, ByteBuffer c, ByteBuffer v, int n) {
        if (n == 0) return 0;
        long added = addBatch(handle, address(c), address(v), n, kind);
        return (int) added;
    }

    public long propagate() {
        return propagate(handle);
    }

    public int readResult(int x, int y, int z) {
        return readResult(handle, x, y, z);
    }

    public void close() {
        try {
            closeRaw(handle);
        } catch (Throwable ignore) { }
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
