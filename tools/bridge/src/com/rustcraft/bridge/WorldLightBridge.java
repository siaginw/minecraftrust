package com.rustcraft.bridge;

/**
 * Per-cell live shadow bridge for vanilla World.checkLightFor(BLOCK, pos).
 * Thin façade over LightBatchCtx (the class the Rust exports actually
 * bind — do not declare natives here; they would never resolve).
 *
 * One injected checkLightFor(BLOCK, pos) HEAD call:
 *   stage neighbor lights + emission/opacity at pos (Java reads, 10 values)
 *   flush a 7-cell batch into the Rust kernel
 *   kernel evaluates the vanilla level rule for pos
 * vanilla computes its own value; the tail hook compares.
 */
public final class WorldLightBridge {

    public static final boolean LOADED = LightBatchCtx.LOADED;

    private final LightBatchCtx ctx;

    private WorldLightBridge(LightBatchCtx ctx) {
        this.ctx = ctx;
    }

    public static WorldLightBridge open() {
        if (!LOADED) return null;
        LightBatchCtx c = LightBatchCtx.open(0);
        return c == null ? null : new WorldLightBridge(c);
    }

    /**
     * Evaluate the vanilla level rule at (x,y,z) through the Rust kernel:
     * pos light = max(emission, max over 6 neighbors of (light - attenuation)).
     * neighborLights: [x-1,x+1,y-1,y+1,z-1,z+1].
     */
    public int evaluateCell(int x, int y, int z, int emission, int opacity,
                            int[] neighborLights) {
        ctx.add(x, y, z, 0, 0);                       // initial light = 0
        ctx.add(x, y, z, emission, 2);                // emission at pos
        ctx.add(x, y, z, opacity, 1);                 // opacity at pos
        for (int i = 0; i < 6; i++) {
            int dx = (i == 0) ? -1 : (i == 1) ? 1 : 0;
            int dy = (i == 2) ? -1 : (i == 3) ? 1 : 0;
            int dz = (i == 4) ? -1 : (i == 5) ? 1 : 0;
            ctx.add(x + dx, y + dy, z + dz, neighborLights[i], 0);
        }
        ctx.add(x, y, z, 0, 3); // notify: seed the two-queue frontier at pos
        long changed = ctx.propagate();
        if (changed < 0) return -1;
        return ctx.readResult(x, y, z);
    }

    public void close() {
        ctx.close();
    }
}
