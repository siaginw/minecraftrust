package com.rustcraft.bridge;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-cell live shadow for vanilla World.checkLightFor(BLOCK, pos)
 * (SRG func_180500_c, notch amu.c) — the BLOCK-light recompute rule.
 * func_175638_a is World.getRawLight — a different method; do not target it.
 *
 * Runtime naming truth (proven live in light-cell-diag5/6 + joined.srg):
 * loaded classes keep NOTCH names (amu, ana, et) while MEMBERS are
 * FML-deobfuscated to SRG (func_XXXXX); launchwrapper hands transformers
 * deobf transformedName but the LOADED class is notch. Reflection therefore
 * derives the whole surface from live objects (no Class.forName); Forge
 * world-aware Block.getLightValue/getLightOpacity keep literal names and
 * are exactly the calls checkLightFor/getRawLight make (javap-verified on
 * the Forge-2847 binpatched jar).
 *
 * BLOCK/SKY discrimination: EnumSkyBlock declares SKY first — ordinal 0 is
 * SKY, ordinal 1 is BLOCK (javap-verified). Discrimination therefore keys
 * on the enum constant NAME, never the ordinal.
 *
 * Injected code must NEVER throw into vanilla: every failure path counts
 * an error and degrades to "no comparison for this cell".
 *
 * Injected at the method HEAD: reads the 6 neighbor block-light values and
 * pos emission/opacity from the live world (Java reads), evaluates the same
 * rule through the Rust kernel (LightBatchCtx), and stages the expected
 * value; the injected RETURN comparison checks vanilla's own result against
 * it. Java stays authoritative; Rust is the independent evaluator.
 */
public final class WorldLightHook {

    public static final boolean ENABLED =
            Boolean.getBoolean("rustcraft.lightExperiment");

    public static final AtomicLong CELLS = new AtomicLong();
    public static final AtomicLong MISMATCHES = new AtomicLong();
    public static final AtomicLong ERRORS = new AtomicLong();
    public static final AtomicLong SKIPPED = new AtomicLong();
    /** Divergences where a tail-state kernel re-evaluation agrees with
     *  vanilla: checkLightFor's in-method queue drain re-wrote pos after
     *  the HEAD-rule value — an ordering artifact, not a rule divergence. */
    public static final AtomicLong SETTLED_LATE = new AtomicLong();
    public static volatile String LAST_MISMATCH = "";
    public static volatile String LAST_ERROR = "";
    /** Mismatch classification: index = clamp(javaNow,0,15)*16
     *  + clamp(rust,0,15) — read via dumpMetrics. */
    public static final int[] MISMATCH_CLASS = new int[256];
    /** Last 4 mismatch samples with full captured inputs. */
    public static final String[] MISMATCH_SAMPLES = new String[4];
    public static volatile int MISMATCH_SAMPLE_N;

    private static final ThreadLocal<int[]> NEIGHBORS = new ThreadLocal<>();
    private static final ThreadLocal<int[]> CONTEXT = new ThreadLocal<>();

    // resolved reflection surface (SRG runtime names, MCP dev names)
    private static volatile boolean RESOLVED = false;
    private static Method M_GET_LIGHT_FOR;   // World.getLightFor(EnumSkyBlock, BlockPos)I
    private static Method M_GET_X, M_GET_Y, M_GET_Z; // BlockPos/Vec3i getters
    private static Method M_GET_BLOCK_STATE; // World.getBlockState(BlockPos)IBlockState
    private static Method M_GET_BLOCK;       // IBlockState.getBlock()Block
    private static Method M_LIGHT_VALUE;     // Block.getLightValue(IBlockState, IBlockAccess, BlockPos)I
    private static Method M_LIGHT_OPACITY;   // Block.getLightOpacity(IBlockState, IBlockAccess, BlockPos)I

    private WorldLightHook() { }

    /** §5 discrimination: BLOCK only. EnumSkyBlock SKY is ordinal 0, BLOCK
     *  is ordinal 1 — key on the constant NAME, never the ordinal. */
    public static boolean isBlockType(Object skyBlock) {
        return skyBlock instanceof java.lang.Enum
                && "BLOCK".equals(((java.lang.Enum) skyBlock).name());
    }

    /** Injected at HEAD of checkLightFor(BLOCK, pos). Captures inputs and
     *  evaluates the Rust kernel for this cell. Never throws. */
    public static void cellStart(Object world, Object skyBlock, Object pos) {
        if (!ENABLED) return;
        if (!isBlockType(skyBlock)) return; // SKY stays Java (§5)
        try {
            ensureResolved(world, pos);
            Object blockType = enumBlockType(skyBlock);
            int x = (Integer) M_GET_X.invoke(pos);
            int y = (Integer) M_GET_Y.invoke(pos);
            int z = (Integer) M_GET_Z.invoke(pos);
            int[] nb = captureNeighbors(world, blockType, pos, x, y, z);
            Object pos0 = newPos(pos, x, y, z);
            Object state = M_GET_BLOCK_STATE.invoke(world, pos0);
            Object block = state == null ? null : M_GET_BLOCK.invoke(state);
            if (block == null) {
                throw new IllegalStateException("no block/state at " + x + "," + y + "," + z);
            }
            int emission = (Integer) M_LIGHT_VALUE.invoke(block, state, world, pos0);
            int opacity = (Integer) M_LIGHT_OPACITY.invoke(block, state, world, pos0);
            NEIGHBORS.set(nb);
            CONTEXT.set(new int[]{x, y, z, emission, opacity});
            CELLS.incrementAndGet();
        } catch (Throwable t) {
            fail(t);
        }
    }

    /** The 6 face-neighbor BLOCK lights via World.getLightFor — the same
     *  reads vanilla getRawLight makes. Null on read failure. */
    private static int[] captureNeighbors(Object world, Object blockType,
                                          Object pos, int x, int y, int z)
            throws Exception {
        int[] nb = new int[6];
        for (int i = 0; i < 6; i++) {
            int dx = (i == 0) ? -1 : (i == 1) ? 1 : 0;
            int dy = (i == 2) ? -1 : (i == 3) ? 1 : 0;
            int dz = (i == 4) ? -1 : (i == 5) ? 1 : 0;
            nb[i] = (Integer) M_GET_LIGHT_FOR.invoke(world, blockType,
                    newPos(pos, x + dx, y + dy, z + dz));
        }
        return nb;
    }

    /** Injected before the EARLY-EXIT return (area not loaded): vanilla did
     *  not recompute this cell — count and drop, never a parity failure. */
    public static void cellEndSkip(Object world, Object skyBlock, Object pos) {
        if (!ENABLED) return;
        if (NEIGHBORS.get() == null && CONTEXT.get() == null) return;
        NEIGHBORS.remove();
        CONTEXT.remove();
        SKIPPED.incrementAndGet();
    }

    /** Injected before RETURN: vanilla computed and set the new value;
     *  compare against the kernel evaluation. Never throws. */
    public static void cellEnd(Object world, Object skyBlock, Object pos) {
        if (!ENABLED) return;
        int[] nb = NEIGHBORS.get();
        int[] ctx = CONTEXT.get();
        if (nb == null || ctx == null) return;
        NEIGHBORS.remove();
        CONTEXT.remove();
        int x = ctx[0], y = ctx[1], z = ctx[2];
        try {
            ensureResolved(world, pos);
            Object blockType = enumBlockType(skyBlock);
            int javaNow = (Integer) M_GET_LIGHT_FOR.invoke(world, blockType,
                    newPos(pos, x, y, z));
            WorldLightBridge bridge = WorldLightBridge.open();
            if (bridge == null) {
                throw new IllegalStateException("rust bridge unavailable"
                        + " (LOADED=" + WorldLightBridge.LOADED + ")");
            }
            int rust;
            try {
                rust = bridge.evaluateCell(x, y, z, ctx[3], ctx[4], nb);
            } finally {
                bridge.close();
            }
            if (rust != javaNow) {
                // checkLightFor drains its propagation queue WITHIN one call;
                // pos may be re-written after the HEAD-rule value. Re-evaluate
                // from the post-drain neighbor state before calling it a
                // rule divergence.
                int[] nbTail = captureNeighbors(world, blockType, pos, x, y, z);
                if (nbTail != null) {
                    WorldLightBridge bridge2 = WorldLightBridge.open();
                    if (bridge2 != null) {
                        try {
                            int rustTail = bridge2.evaluateCell(
                                    x, y, z, ctx[3], ctx[4], nbTail);
                            if (rustTail == javaNow) {
                                SETTLED_LATE.incrementAndGet();
                                return;
                            }
                        } finally {
                            bridge2.close();
                        }
                    }
                }
                MISMATCHES.incrementAndGet();
                int j = Math.min(15, Math.max(0, javaNow));
                int r = Math.min(15, Math.max(0, rust));
                MISMATCH_CLASS[j * 16 + r]++;
                String sample = "x=" + x + " y=" + y + " z=" + z
                        + " java=" + javaNow + " rust=" + rust
                        + " em=" + ctx[3] + " op=" + ctx[4]
                        + " nb=" + java.util.Arrays.toString(nb)
                        + " nbTail=" + java.util.Arrays.toString(nbTail);
                MISMATCH_SAMPLES[(int) (MISMATCHES.get() % MISMATCH_SAMPLES.length)]
                        = sample;
                LAST_MISMATCH = sample;
            }
        } catch (Throwable t) {
            fail(t);
        }
    }

    private static void fail(Throwable t) {
        ERRORS.incrementAndGet();
        StringBuilder sb = new StringBuilder(String.valueOf(t));
        StackTraceElement[] st = t.getStackTrace();
        for (int i = 0; i < st.length && i < 3; i++) {
            sb.append(" @ ").append(st[i].getClassName())
                    .append('.').append(st[i].getMethodName())
                    .append(':').append(st[i].getLineNumber());
        }
        String msg = sb.toString();
        LAST_ERROR = msg.length() > 400 ? msg.substring(0, 400) : msg;
    }

    // -- reflection resolution --
    //
    // LIVE naming truth (proven in light-cell-diag5/6): loaded classes keep
    // NOTCH names (amu, ana, et) while MEMBERS are FML-deobfuscated to SRG
    // (func_XXXXX). Class.forName by deobf names therefore FAILS — the
    // entire surface is derived from the live objects instead: enum class
    // from the passed EnumSkyBlock instance, constructor from the passed
    // BlockPos instance, methods by candidate-name + param-count + return
    // type. No Class.forName anywhere; no param classes needed (Method.
    // invoke checks assignability against the real args' loader).

    private static Method find(Object owner, String[] names, int paramCount,
                               Class<?> expectReturn) {
        for (Method m : owner.getClass().getMethods()) {
            boolean nameHit = false;
            for (String n : names) {
                if (n.equals(m.getName())) {
                    nameHit = true;
                    break;
                }
            }
            boolean returnOk = (expectReturn == Object.class)
                    ? !m.getReturnType().equals(void.class)  // any non-void
                    : m.getReturnType().equals(expectReturn);
            if (nameHit && m.getParameterCount() == paramCount && returnOk) {
                m.setAccessible(true);
                return m;
            }
        }
        throw new IllegalStateException("seam method not found: "
                + java.util.Arrays.toString(names) + "/" + paramCount + "p/"
                + (expectReturn == null ? "?" : expectReturn.getSimpleName())
                + " on " + owner.getClass().getName());
    }

    private static void ensureResolved(Object world, Object pos) {
        if (RESOLVED) return;
        synchronized (WorldLightHook.class) {
            if (RESOLVED) return;
            M_GET_LIGHT_FOR = find(world,
                    new String[]{"getLightFor", "func_175642_b"}, 2, int.class);
            M_GET_X = find(pos, new String[]{"getX", "func_177958_n"}, 0, int.class);
            M_GET_Y = find(pos, new String[]{"getY", "func_177956_o"}, 0, int.class);
            M_GET_Z = find(pos, new String[]{"getZ", "func_177952_p"}, 0, int.class);
            M_GET_BLOCK_STATE = find(world,
                    new String[]{"getBlockState", "func_180495_p"}, 1,
                    Object.class /* any non-void return */);
            try {
                Object stateProbe = M_GET_BLOCK_STATE.invoke(world, pos);
                M_GET_BLOCK = find(stateProbe,
                        new String[]{"getBlock", "func_177230_c"}, 0,
                        Object.class);
                Class<?> blockClass = M_GET_BLOCK.getReturnType();
                M_LIGHT_VALUE = findOnClass(blockClass, "getLightValue", 3);
                M_LIGHT_OPACITY = findOnClass(blockClass, "getLightOpacity", 3);
            } catch (Exception e) {
                throw new IllegalStateException("state/block probe failed", e);
            }
            RESOLVED = true;
        }
    }

    /** Forge-added world-aware Block.getLightValue/getLightOpacity keep
     *  literal names; bind by name + 3 params + int return on the declared
     *  Block type. */
    private static Method findOnClass(Class<?> owner, String name, int paramCount) {
        for (Method m : owner.getMethods()) {
            if (name.equals(m.getName()) && m.getParameterCount() == paramCount
                    && m.getReturnType().equals(int.class)) {
                m.setAccessible(true);
                return m;
            }
        }
        throw new IllegalStateException("forge seam method not found: "
                + owner.getName() + "." + name + "/" + paramCount + "p");
    }

    /** New BlockPos via the passed pos's own class hierarchy — loader- and
     *  name-shape-agnostic. */
    private static Object newPos(Object templatePos, int x, int y, int z)
            throws Exception {
        for (Class<?> c = templatePos.getClass(); c != null; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Constructor<?> ctor =
                        c.getConstructor(int.class, int.class, int.class);
                return ctor.newInstance(x, y, z);
            } catch (NoSuchMethodException ignore) {
                // walk up (MutableBlockPos -> BlockPos)
            }
        }
        throw new IllegalStateException("no (int,int,int) ctor on "
                + templatePos.getClass().getName());
    }

    /** The EnumSkyBlock BLOCK constant, resolved from the PASSED instance's
     *  own enum class by NAME (never ordinal: SKY is declared first). */
    private static Object enumBlockType(Object passedSkyBlock) {
        Object[] constants = passedSkyBlock.getClass().getEnumConstants();
        for (Object c : constants) {
            if ("BLOCK".equals(((java.lang.Enum) c).name())) {
                return c;
            }
        }
        throw new IllegalStateException("no BLOCK constant on "
                + passedSkyBlock.getClass().getName());
    }

    public static String dumpMetrics() {
        StringBuilder sb = new StringBuilder();
        sb.append("worldLight.shadow cells=").append(CELLS.get())
                .append(" mismatches=").append(MISMATCHES.get())
                .append(" settledLate=").append(SETTLED_LATE.get())
                .append(" errors=").append(ERRORS.get())
                .append(" skippedEarlyExit=").append(SKIPPED.get())
                .append(" transformer=")
                .append(com.rustcraft.coremod.WorldLightTransformer
                        .lastTransformStatus)
                .append((LAST_ERROR.isEmpty() ? "" : " lastError=" + LAST_ERROR))
                .append(" lastMismatch=").append(LAST_MISMATCH);
        // top mismatch classes
        int[] mc = MISMATCH_CLASS;
        int printed = 0;
        for (int idx = 255; idx >= 0 && printed < 4; idx--) {
            if (mc[idx] > 0) {
                sb.append(" cls[java=").append(idx / 16)
                        .append(" rust=").append(idx % 16)
                        .append("]=").append(mc[idx]);
                printed++;
            }
        }
        for (int i = 0; i < MISMATCH_SAMPLES.length; i++) {
            String s = MISMATCH_SAMPLES[i];
            if (s != null) {
                sb.append(" sample").append(i).append("=[").append(s).append(']');
            }
        }
        return sb.toString();
    }
}
