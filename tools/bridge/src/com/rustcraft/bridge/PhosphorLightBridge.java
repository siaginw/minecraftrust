package com.rustcraft.bridge;

/**
 * LIVE block-light propagation bridge (RUST_BLOCK_LIGHT_PROPAGATION_AUTHORITY).
 *
 * Marshals one propagation JOB (one Phosphor
 * processLightUpdatesForType(BLOCK) drain) into the Rust LightBatchCtx JNI
 * surface — as a FACADE: no natives declared here (JNI binds the exports to
 * LightBatchCtx; private declarations on this class never resolve — the
 * bug that made every job silently fail closed). Four staged bulk buffers
 * (initial / opacity / emission / notify) flush in one JNI crossing each;
 * coords are staged in plain arrays and byte-order-corrected inside
 * LightBatchCtx (LITTLE_ENDIAN). Native failures throw and callers fail
 * closed to Java/Phosphor.
 */
public final class PhosphorLightBridge {

    public static final boolean LOADED = LightBatchCtx.LOADED;

    private final LightBatchCtx ctx;
    /** Job bounds: inclusive min, exclusive max. */
    public final int minX, minY, minZ, maxX, maxY, maxZ;
    public final int cells;
    private final int[] coordsI, coordsO, coordsE, coordsN;
    private final byte[] valuesI, valuesO, valuesE, valuesN;
    private int usedI, usedO, usedE, usedN;

    private PhosphorLightBridge(int minX, int minY, int minZ,
                                int maxX, int maxY, int maxZ) {
        LightBatchCtx c = LightBatchCtx.open(0); // outside world = dark
        if (c == null) {
            throw new IllegalStateException("LightBatchCtx unavailable");
        }
        this.ctx = c;
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = maxZ;
        this.cells = (maxX - minX) * (maxY - minY) * (maxZ - minZ);
        this.coordsI = new int[cells * 3];
        this.coordsO = new int[cells * 3];
        this.coordsE = new int[cells * 3];
        this.coordsN = new int[cells * 3];
        this.valuesI = new byte[cells];
        this.valuesO = new byte[cells];
        this.valuesE = new byte[cells];
        this.valuesN = new byte[cells];
    }

    /** Null on any failure (caller fail-closes to Java/Phosphor). */
    public static PhosphorLightBridge open(int minX, int minY, int minZ,
                                           int maxX, int maxY, int maxZ) {
        if (!LOADED) return null;
        try {
            return new PhosphorLightBridge(minX, minY, minZ,
                    maxX, maxY, maxZ);
        } catch (Throwable t) {
            return null;
        }
    }

    public boolean addInitial(int x, int y, int z, int light) {
        if (usedI >= cells) return false;
        stage(coordsI, valuesI, usedI, x, y, z, light);
        usedI++;
        return true;
    }

    public boolean addOpacity(int x, int y, int z, int opacity) {
        if (usedO >= cells) return false;
        stage(coordsO, valuesO, usedO, x, y, z, opacity);
        usedO++;
        return true;
    }

    public boolean addEmission(int x, int y, int z, int emission) {
        if (usedE >= cells) return false;
        stage(coordsE, valuesE, usedE, x, y, z, emission);
        usedE++;
        return true;
    }

    public boolean addNotify(int x, int y, int z) {
        if (usedN >= cells) return false;
        stage(coordsN, valuesN, usedN, x, y, z, 0);
        usedN++;
        return true;
    }

    private int stage(int[] coords, byte[] vals, int i,
                      int x, int y, int z, int value) {
        if (i >= cells) return -1;
        coords[i * 3 + 0] = x;
        coords[i * 3 + 1] = y;
        coords[i * 3 + 2] = z;
        vals[i] = (byte) value;
        return i;
    }

    public int flushInitial() { return flush(coordsI, valuesI, usedI, 0); }
    public int flushOpacity() { return flush(coordsO, valuesO, usedO, 1); }
    public int flushEmission() { return flush(coordsE, valuesE, usedE, 2); }
    public int flushNotify() { return flush(coordsN, valuesN, usedN, 3); }

    private int flush(int[] coords, byte[] vals, int n, int kind) {
        if (n == 0) return 0;
        ctx.addBulk(coords, vals, n, kind);
        return n;
    }

    public long propagate() {
        return ctx.propagate();
    }

    public int readResult(int x, int y, int z) {
        return ctx.readResult(x, y, z);
    }

    public void close() {
        ctx.close();
    }
}
