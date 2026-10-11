package com.rustcraft.bridge;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;

/**
 * M3-A: authoritative scheduled-tick scheduler ownership.
 *
 * OWNERSHIP CONTRACT (receipt M3-A):
 * - RUST OWNS the pending-tick queue state per dimension and the
 *   scheduling decisions: admission (dedup by (pos, block) — Rust's own
 *   queue decides), eligibility (scheduledTime <= totalWorldTime, or
 *   unbounded on runAllPending), the per-call drain cap (min(queue,
 *   65536)), ORDERING ((time, priority, seq) — the exact
 *   NextTickListEntry.compareTo), and removal. Java executes.
 * - JAVA EXECUTES: Block.updateTick fires all vanilla+mod block behavior;
 *   the write path (updateBlockTick) runs its VANILLA body unchanged so
 *   the Java TreeSet mirror (save-side reads: getPendingBlockUpdates ->
 *   chunk NBT TileTicks; mod reads) stays byte-identical — the M3-A
 *   persistence boundary. The hook feeds the same admission stream to
 *   Rust (enqueue); divergence heals via reconcile.
 * - DISPLACED JAVA PATH: WorldServer.tickUpdates' cleaning + ticking loop
 *   (the drain, its eligibility/order/cap decisions, and its callback
 *   loop) never executes in ON mode — the head hook performs the whole
 *   drain from Rust's authoritative batch and returns vanilla's semantic
 *   result via the LAST_HANDLED handshake.
 *
 * Vanilla semantics ground-truthed from notch bytecode of
 * oo.a(Let;Laow;II)V / oo.a(Z)Z (minecraft_server.1.12.2.jar):
 * - updateBlockTick admission: scheduledTime = totalWorldTime + delay
 *   (deterministic); gated on isBlockLoaded(pos); when
 *   scheduledUpdatesAreImmediate the vanilla inline path runs instead
 *   (we skip enqueue and heal via adopt); AIR-material blocks admit with
 *   time=0/prio=0 (setters skipped).
 * - tickUpdates drain: cap = min(treeSize, 65536) per call; eligibility
 *   scheduledTime <= totalWorldTime unless runAllPending; ALL eligible
 *   entries are collected first, then executed (so same-call re-schedules
 *   never re-execute); area-not-loaded -> scheduleUpdate(pos, block, 0)
 *   (REQUEUE); material==AIR or block-changed -> consume silently.
 *
 * SHADOW: isolated decision comparison — snapshot Java's TreeSet decision
 * order, ask Rust for its batch on the same inputs, compare bounded,
 * re-adopt so the vanilla queue is untouched, NEVER execute. Only the
 * vanilla body executes. No double effects, no double RNG.
 *
 * Fail-closed: any Throwable in the native path disables the authority
 * (vanilla tickUpdates resumes). Already-executed callbacks are never
 * re-run — the drain continues per-entry, no loop restart.
 */
public final class TickSchedulerHook {

    public static final String MODE =
            System.getProperty("rustcraft.tickAuthorityMode", "OFF");
    public static final boolean ENABLED = !"OFF".equals(MODE);
    public static final boolean SHADOW = "SHADOW".equals(MODE);
    /** negative-boot injection: fail (disable the authority) once this many
     * callbacks have executed — 0 = off. The M2 failEvery pattern applied
     * to the mid-execution boundary (goal §3: no restart, no repeats). */
    public static final int FAIL_AFTER_EXEC =
            Integer.getInteger("rustcraft.tickFailAfterExec", 0);
    public static volatile boolean DISABLED_BY_FAILURE = false;

    public static final AtomicLong TICKS_ENQUEUED = new AtomicLong();
    // M3-A entry counters: increment BEFORE any early return — the
    // observability lesson from M1-FIX (a zero-after-guards counter is
    // indistinguishable from a never-called hook; the M3-A silent-stop
    // incident made every reflection member's null path a counted,
    // named event)
    public static final AtomicLong ENTRY_UPDATE_TICK = new AtomicLong();
    public static final AtomicLong ENTRY_TICK_UPDATES = new AtomicLong();
    public static final AtomicLong REFLECT_FAIL = new AtomicLong();
    public static final AtomicLong DIM_FAIL = new AtomicLong();
    public static final AtomicLong TIME_FAIL = new AtomicLong();
    public static final AtomicLong TICKS_SKIP_RESTORE = new AtomicLong();
    public static final AtomicLong TICKS_SKIP_UNLOADED = new AtomicLong();
    public static final AtomicLong TICKS_DEDUP_SKIPPED = new AtomicLong();
    public static final AtomicLong TICKS_DRAINED = new AtomicLong();
    public static final AtomicLong TICKS_EXECUTED = new AtomicLong();
    public static final AtomicLong TICKS_RESCHEDULED = new AtomicLong();
    public static final AtomicLong TICKS_STALE_CONSUMED = new AtomicLong();
    public static final AtomicLong NATIVE_DRAINS = new AtomicLong();
    public static final AtomicLong SHADOW_COMPARES = new AtomicLong();
    public static final AtomicLong SHADOW_ORDER_MISMATCH = new AtomicLong();
    public static final AtomicLong SHADOW_COUNT_MISMATCH = new AtomicLong();
    public static final AtomicLong MIRROR_REMOVALS = new AtomicLong();
    public static final AtomicLong ADOPTED = new AtomicLong();
    public static final AtomicLong ERRORS = new AtomicLong();
    public static volatile String LAST_ERROR = "";

    // reflection: derived from the LIVE WorldServer object graph (the
    // repo cross-loader rule — no Class.forName on game classes; members
    // resolve through the caller's loader = the launch loader)
    private static Field F_TREE, F_SET, F_RAND, F_INFO, F_IMMEDIATE, F_PROVIDER;
    private static Method F_WTIME, F_TERRAIN;
    private static Field F_NTLE_POS, F_NTLE_BLOCK, F_NTLE_TIME, F_NTLE_PRIO;
    private static Method M_POS_X, M_POS_Y, M_POS_Z;
    private static Method M_AREA_LOADED, M_BLOCK_LOADED, M_GET_STATE, M_RESCHED;
    private static Method M_UPDATE_TICK, M_IS_EQUAL, M_DEF_STATE, M_MAT, M_STATE_BLOCK;
    private static Object MAT_AIR, WT_DEBUG;
    private static Class<?> BLOCK_C, POS_C;
    private static volatile boolean reflectReady;

    private static final int BATCH_CAP = 8192;   // direct-buffer entries
    private static final int DRAIN_CAP = 65536;  // vanilla per-call cap
    private static final ThreadLocal<ByteBuffer> BATCH_TL = ThreadLocal.withInitial(() ->
            ByteBuffer.allocateDirect(7 * 8 * BATCH_CAP).order(ByteOrder.LITTLE_ENDIAN));
    private static final ThreadLocal<ByteBuffer> ADOPT_TL = ThreadLocal.withInitial(() ->
            ByteBuffer.allocateDirect(7 * 8 * BATCH_CAP).order(ByteOrder.LITTLE_ENDIAN));

    private TickSchedulerHook() { }

    private static native long tickEnqueue(int dim, int x, int y, int z, int block,
                                           long time, int priority);
    private static native int tickDrain(int dim, long horizon, long outAddr, int capEntries);
    private static native int tickAdopt(int dim, long inAddr, int lenI64);
    private static native long tickPendingCount(int dim);
    private static native long tickClear(int dim);

    private static long addr(ByteBuffer b) {
        try {
            Method m = b.getClass().getMethod("address");
            m.setAccessible(true);
            return (Long) m.invoke(b);
        } catch (Throwable t) {
            ERRORS.incrementAndGet();
            LAST_ERROR = "addr: " + t; // RUNSCOPE-JUSTIFIED: recorded, and 0 is refused by the native side (-4)
            return 0;
        }
    }

    /** every silent-null path counts AND names itself — the M3-A
     * silent-stop class of bug must be boot-visible */
    private static void reflectFail(String member) {
        REFLECT_FAIL.incrementAndGet();
        LAST_ERROR = "reflect:" + member;
    }

    private static boolean initReflect(Object ws, Class<?> posC, Class<?> blockC) {
        if (reflectReady) return true;
        // the LIVE class may be a subclass (WorldServerMulti): declared
        // fields/methods live on WorldServer/World — walk per member
        Class<?> wsc = ws.getClass();
        F_TREE = fieldOnChain(wsc, "field_73065_O");
        if (F_TREE == null) { reflectFail("field_73065_O"); return false; }
        F_SET = fieldOnChain(wsc, "field_73064_N");
        if (F_SET == null) { reflectFail("field_73064_N"); return false; }
        F_RAND = fieldOnChain(wsc, "field_73012_v");
        if (F_RAND == null) { reflectFail("field_73012_v"); return false; }
        F_INFO = fieldOnChain(wsc, "field_72986_A");
        if (F_INFO == null) { reflectFail("field_72986_A"); return false; }
        F_IMMEDIATE = fieldOnChain(wsc, "field_72999_e"); // scheduledUpdatesAreImmediate
        if (F_IMMEDIATE == null) { reflectFail("field_72999_e"); return false; }
        F_PROVIDER = fieldOnChain(wsc, "field_73011_w");
        if (F_PROVIDER == null) { reflectFail("field_73011_w"); return false; }
        // accessible BEFORE first use — F_INFO.get() runs two lines down
        // (protected members: use-before-setAccessible is the on13
        // IllegalAccessException, boot-visible via the named counter)
        for (Field f : new Field[]{F_TREE, F_SET, F_RAND, F_INFO, F_IMMEDIATE, F_PROVIDER})
            f.setAccessible(true);
        // World class = nearest superclass named ...World on the chain
        Class<?> worldC = wsc;
        while (worldC != null && !worldC.getName().endsWith(".World")) {
            worldC = worldC.getSuperclass();
        }
        if (worldC == null) { reflectFail("worldC"); return false; }
        POS_C = posC;
        BLOCK_C = blockC;
        try {
            F_WTIME = F_INFO.get(ws).getClass().getMethod("func_82573_f"); // getWorldTotalTime
            F_TERRAIN = F_INFO.get(ws).getClass().getMethod("func_76067_t"); // getTerrainType
        } catch (Throwable t) {
            ERRORS.incrementAndGet();
            LAST_ERROR = "initReflect.worldinfo: " + t;
            return false;
        }
        try {
            M_POS_X = POS_C.getMethod("func_177958_n");
            M_POS_Y = POS_C.getMethod("func_177956_o");
            M_POS_Z = POS_C.getMethod("func_177952_p");
        } catch (Throwable t) {
            ERRORS.incrementAndGet();
            LAST_ERROR = "initReflect.pos: " + t;
            return false;
        }
        // vanilla's loaded check is isAreaLoaded(pos, pos) — TWO BlockPos
        // params (notch oo.a: offsets 209-232) — the one-arg resolution
        // was the M3-A silent-stop root cause
        M_AREA_LOADED = methodOnChain(wsc, "func_175707_a", POS_C, POS_C);
        if (M_AREA_LOADED == null) { reflectFail("func_175707_a(L;L)"); return false; }
        // admission gate: isBlockLoaded(pos) (updateBlockTick offset 123-128)
        M_BLOCK_LOADED = methodOnChain(wsc, "func_175667_e", POS_C);
        if (M_BLOCK_LOADED == null) { reflectFail("func_175667_e"); return false; }
        M_GET_STATE = methodOnChain(wsc, "func_180495_p", POS_C);
        if (M_GET_STATE == null) { reflectFail("func_180495_p"); return false; }
        M_RESCHED = methodOnChain(wsc, "func_175684_a", POS_C, BLOCK_C, int.class);
        if (M_RESCHED == null) { reflectFail("func_175684_a"); return false; }
        try {
            M_DEF_STATE = BLOCK_C.getMethod("func_176223_P"); // getDefaultState
        } catch (Throwable t) {
            ERRORS.incrementAndGet();
            LAST_ERROR = "initReflect.defstate: " + t;
            return false;
        }
        WORLD_C = worldC;
        for (Method m : new Method[]{M_POS_X, M_POS_Y, M_POS_Z, M_AREA_LOADED,
                M_BLOCK_LOADED, M_GET_STATE, M_RESCHED, M_DEF_STATE, F_WTIME, F_TERRAIN})
            m.setAccessible(true);
        reflectReady = true;
        return true;
    }

    /** material surface (getMaterial + Material.AIR) from any LIVE state
     * (a block's default state or a world state) — never Class.forName */
    private static boolean initMaterialSurface(Object state) {
        if (M_MAT != null && MAT_AIR != null) return true;
        try {
            M_MAT = state.getClass().getMethod("func_185904_a");
            M_MAT.setAccessible(true);
            Object anyMat = M_MAT.invoke(state);
            MAT_AIR = anyMat.getClass().getField("field_151579_a").get(null);
            return true;
        } catch (Throwable t) {
            ERRORS.incrementAndGet();
            LAST_ERROR = "initMaterial: " + t;
            return false;
        }
    }

    /** phase B: derive the state/block-side surface from a LIVE IBlockState */
    private static boolean initStateReflect(Object state) {
        if (!initMaterialSurface(state)) return false;
        if (M_UPDATE_TICK != null) return true;
        try {
            // updateTick's DECLARED parameter types come from Block itself —
            // the live state class may be a mod replacement (FoamFix's
            // FoamyBlockState) whose declared type is still IBlockState;
            // matching on the live class cannot resolve the method (on14:
            // NoSuchMethodException on every entry)
            for (Method m : BLOCK_C.getMethods()) {
                if ("func_180650_b".equals(m.getName()) && m.getParameterTypes().length == 4) {
                    M_UPDATE_TICK = m;
                    break;
                }
            }
            if (M_UPDATE_TICK == null) { reflectFail("func_180650_b"); return false; }
            // the IBlockState interface may sit anywhere in the hierarchy
            // (direct interface, superclass interface, or transitive)
            Class<?> stateIface = findIfaceWith(state.getClass(), "func_185904_a");
            if (stateIface == null) { reflectFail("IBlockState"); return false; }
            M_STATE_BLOCK = stateIface.getMethod("func_177230_c");
            M_IS_EQUAL = BLOCK_C.getMethod("func_149680_a", BLOCK_C, BLOCK_C);
            for (Method m : new Method[]{M_STATE_BLOCK, M_UPDATE_TICK, M_IS_EQUAL})
                m.setAccessible(true);
            return true;
        } catch (Throwable t) {
            ERRORS.incrementAndGet();
            LAST_ERROR = "initStateReflect: " + t;
            return false;
        }
    }

    /** first interface (walking superclasses and transitive interfaces)
     * declaring `method`; null if absent */
    private static Class<?> findIfaceWith(Class<?> c, String method) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            for (Class<?> i : k.getInterfaces()) {
                try { i.getMethod(method); return i; }
                catch (NoSuchMethodException ignore) { }
                Class<?> t = findIfaceWith(i, method);
                if (t != null) return t;
            }
        }
        return null;
    }
    private static Class<?> WORLD_C;

    // NTLE field accessors resolve lazily from the first live entry
    private static boolean initNtle(Object ws) {
        if (F_NTLE_POS != null) return true;
        try {
            Object any = ((TreeSet<?>) F_TREE.get(ws)).first();
            Class<?> c = any.getClass();
            F_NTLE_POS = c.getDeclaredField("field_180282_a");
            F_NTLE_BLOCK = c.getDeclaredField("field_151352_g");
            F_NTLE_TIME = c.getDeclaredField("field_77180_e"); // index: descriptor J (getLong is type-correct)
            F_NTLE_PRIO = c.getDeclaredField("field_82754_f");
            for (Field f : new Field[]{F_NTLE_POS, F_NTLE_BLOCK, F_NTLE_TIME, F_NTLE_PRIO})
                f.setAccessible(true);
            return true;
        } catch (Throwable t) {
            // empty queue (first()) OR real failure — both benign here
            // (nothing to adopt), but record so the distinction is visible
            LAST_ERROR = "initNtle: " + t;
            return false;
        }
    }

    /** init phase A from tickUpdates head (no pos/block objects): POS_C
     * from getBlockState's parameter, BLOCK_C from updateBlockTick's. */
    private static boolean initFromWorld(Object ws) {
        try {
            Method getState = null, updateTick = null;
            for (Method m : ws.getClass().getMethods()) {
                if ("func_180495_p".equals(m.getName()) && m.getParameterTypes().length == 1) {
                    getState = m;
                } else if ("func_175654_a".equals(m.getName())
                        && m.getParameterTypes().length == 4) {
                    updateTick = m;
                }
            }
            if (getState == null) { reflectFail("initFromWorld.getState"); return false; }
            if (updateTick == null) { reflectFail("initFromWorld.updateBlockTick"); return false; }
            return initReflect(ws,
                    getState.getParameterTypes()[0],
                    updateTick.getParameterTypes()[1]);
        } catch (Throwable t) {
            ERRORS.incrementAndGet();
            LAST_ERROR = "initFromWorld: " + t;
            return false;
        }
    }
    /** first declaring class up the chain that has this field (null if absent) */
    private static Field fieldOnChain(Class<?> c, String name) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try { return k.getDeclaredField(name); }
            catch (NoSuchFieldException ignore) { }
        }
        return null;
    }
    private static Method methodOnChain(Class<?> c, String name, Class<?>... params) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try { return k.getDeclaredMethod(name, params); }
            catch (NoSuchMethodException ignore) { }
        }
        return null;
    }

    private static long worldTime(Object ws) {
        try {
            return (Long) F_WTIME.invoke(F_INFO.get(ws));
        } catch (Throwable t) { return Long.MIN_VALUE; }
    }

    private static volatile boolean diagPrinted;
    private static volatile Object provDiag;
    private static int dimOf(Object ws) {
        // WorldServer is a WORLD, not a chunk — the tracker's dimOf expects
        // chunks. Resolve via the world's provider (field_73011_w) +
        // getDimension, the light hook's proven path.
        try {
            Object provider = F_PROVIDER.get(ws);
            provDiag = provider;
            if (provider == null) return Integer.MIN_VALUE;
            for (String n : new String[]{"getDimension", "func_186058_p"}) {
                try {
                    java.lang.reflect.Method m = provider.getClass().getMethod(n);
                    m.setAccessible(true);
                    Object r = m.invoke(provider);
                    if (r instanceof Integer) return (Integer) r;
                    if (r != null) { // DimensionType
                        java.lang.reflect.Method id = r.getClass().getMethod("func_186068_a");
                        id.setAccessible(true);
                        return (Integer) id.invoke(r);
                    }
                } catch (NoSuchMethodException ignore) {
                    // try next
                }
            }
            return Integer.MIN_VALUE;
        } catch (Throwable t) {
            return Integer.MIN_VALUE;
        }
    }

    /** vanilla's DEBUG_ALL_BLOCK_STATES guard: tickUpdates returns false
     * before draining in debug worlds (offsets 0-14). */
    private static boolean isDebugWorld(Object ws) {
        try {
            Object terrain = F_TERRAIN.invoke(F_INFO.get(ws));
            if (terrain == null) return false;
            if (WT_DEBUG == null) {
                WT_DEBUG = terrain.getClass().getField("field_180272_g").get(null);
            }
            return terrain == WT_DEBUG;
        } catch (Throwable t) {
            ERRORS.incrementAndGet();
            LAST_ERROR = "isDebugWorld: " + t;
            return false; // cannot prove debug -> not debug (fail-open on
                          // the guard is safe: the drain predicate still
                          // applies; vanilla worlds are never this type)
        }
    }

    /**
     * WorldServer.updateBlockTick head (func_175654_a): feed the admission
     * stream to Rust. ALWAYS returns false (false = fall-through = the
     * vanilla body runs and maintains the Java mirror; the transformer
     * returns early only on true). Rust's own dedup decision governs ITS
     * queue; divergence heals via reconcile.
     */
    public static boolean onUpdateBlockTick(Object ws, Object pos, Object block,
                                            int delay, int priority) {
        ENTRY_UPDATE_TICK.incrementAndGet();
        if (!ENABLED || DISABLED_BY_FAILURE) return false;
        try {
            if (!initReflect(ws, pos.getClass(), block.getClass())) return false;
            // vanilla scheduledUpdatesAreImmediate path: inline execution /
            // delay=1 / abort — we mirror none of it; skip enqueue and let
            // adoptMissing heal anything the vanilla body did admit
            if (F_IMMEDIATE.getBoolean(ws)) { TICKS_SKIP_RESTORE.incrementAndGet(); return false; }
            // vanilla admission gate: isBlockLoaded(pos) (no entry otherwise)
            if (!(Boolean) M_BLOCK_LOADED.invoke(ws, pos)) {
                TICKS_SKIP_UNLOADED.incrementAndGet();
                return false;
            }
            int dim = dimOf(ws);
            if (dim == Integer.MIN_VALUE) { DIM_FAIL.incrementAndGet(); return false; }
            long now = worldTime(ws);
            if (now == Long.MIN_VALUE) { TIME_FAIL.incrementAndGet(); return false; }
            // vanilla AIR-material quirk: entry admitted but the setters
            // are skipped (time=0, priority=0)
            Object defState = M_DEF_STATE.invoke(block);
            if (!initMaterialSurface(defState)) return false;
            boolean air = M_MAT.invoke(defState) == MAT_AIR;
            long time = air ? 0L : now + delay;
            int prio = air ? 0 : priority;
            int x = (Integer) M_POS_X.invoke(pos);
            int y = (Integer) M_POS_Y.invoke(pos);
            int z = (Integer) M_POS_Z.invoke(pos);
            if (!diagPrinted) {
                diagPrinted = true;
                System.out.println("[RustCraft-Tick] first admission: dim=" + dim
                        + " now=" + now + " pos=" + x + "," + y + "," + z
                        + " delay=" + delay + " prio=" + priority
                        + " provider=" + (provDiag == null ? "?" : provDiag.getClass().getName()));
            }
            int id = System.identityHashCode(block);
            rememberBlock(id, block);
            long seq = tickEnqueue(dim, x, y, z, id, time, prio);
            if (seq == -1) TICKS_DEDUP_SKIPPED.incrementAndGet();
            else if (seq >= 0) TICKS_ENQUEUED.incrementAndGet();
            else fail("tickEnqueue rc=" + seq);
            return false; // vanilla body maintains the Java mirror
        } catch (Throwable t) {
            ERRORS.incrementAndGet();
            LAST_ERROR = "onUpdateBlockTick: " + t;
            return false;
        }
    }

    public static volatile boolean LAST_HANDLED;

    /**
     * WorldServer.tickUpdates head (func_72955_a). SHADOW: decision
     * comparison only (vanilla executes). ON: the authoritative drain —
     * the vanilla cleaning+ticking loop is displaced; the return value is
     * vanilla's semantic result (treeSet.isEmpty()) when handled.
     */
    public static boolean onTickUpdates(Object ws, boolean runAllPending) {
        ENTRY_TICK_UPDATES.incrementAndGet();
        LAST_HANDLED = false;
        if (!ENABLED || DISABLED_BY_FAILURE) return false;
        try {
            if (!reflectReady) {
                if (!initFromWorld(ws)) return false;
            }
            if (isDebugWorld(ws)) return false; // vanilla early-return; let its body do it
            final int dim = dimOf(ws);
            if (dim == Integer.MIN_VALUE) return false;
            long now = worldTime(ws);
            if (now == Long.MIN_VALUE) return false;
            // vanilla asserts tree/set size equality ("TickNextTick list
            // out of synch"); ours is a mirror invariant — violation means
            // our removeMirrorEntry failed somewhere: fail closed
            TreeSet<?> tree = (TreeSet<?>) F_TREE.get(ws);
            java.util.Set<?> set = (java.util.Set<?>) F_SET.get(ws);
            if (tree.size() != set.size()) { fail("tree/set desync " + tree.size() + "/" + set.size()); return false; }
            if (SHADOW) {
                // queue := mirror EVERY call: a time-only drift (callback
                // re-scheduled an entry the vanilla body just drained — new
                // time in the tree, old time deduped in Rust) is invisible
                // to the count-equal adopt fast path (shadow5: 17 residual
                // count mismatches). The rebuild makes the compare's input
                // exactly the captured mirror state.
                rebuildFromTree(ws, dim);
                doShadowCompare(ws, dim, now, runAllPending);
                return false;
            }
            // ---- ON: the authoritative drain (vanilla cleaning+ticking) ----
            adoptMissing(ws, dim); // heal chunk-NBT-loaded/direct tree writes
            long horizon = runAllPending ? Long.MAX_VALUE - 1 : now;
            int cap = (int) Math.min(tickPendingCount(dim), (long) DRAIN_CAP);
            // collect FIRST (vanilla drains all eligible before executing
            // any — same-call re-schedules never re-execute)
            java.util.ArrayList<long[]> collected = new java.util.ArrayList<>();
            int total = 0;
            while (total < cap) {
                int want = Math.min(BATCH_CAP, cap - total);
                ByteBuffer buf = BATCH_TL.get();
                buf.clear();
                int n = tickDrain(dim, horizon, addr(buf), want);
                if (n < 0) { fail("tickDrain rc=" + n); return false; }
                NATIVE_DRAINS.incrementAndGet();
                if (n > 0) {
                    long[] rec = new long[n * 7];
                    buf.asLongBuffer().get(rec);
                    collected.add(rec);
                    total += n;
                }
                if (n < want) break; // queue exhausted at the horizon
            }
            TICKS_DRAINED.addAndGet(total);
            // then execute, in Rust's (== vanilla compareTo) order
            Object rand = F_RAND.get(ws);
            for (long[] rec : collected) {
                for (int i = 0; i < rec.length; i += 7) {
                    // negative-boot injection (rustcraft.tickFailAfterExec):
                    // fail MID-EXECUTION, before this entry's mirror removal,
                    // so the already-executed callbacks stay consumed (never
                    // re-run by the resuming vanilla loop) and the remaining
                    // entries stay intact in the tree for vanilla to run
                    // exactly once — the goal §3 continuation boundary
                    if (FAIL_AFTER_EXEC > 0
                            && TICKS_EXECUTED.get() >= FAIL_AFTER_EXEC) {
                        fail("injected: failAfterExec=" + FAIL_AFTER_EXEC
                                + " (executed=" + TICKS_EXECUTED.get() + ")");
                        return false;
                    }
                    int x = (int) rec[i + 3];
                    int y = (int) rec[i + 4];
                    int z = (int) rec[i + 5];
                    int blockId = (int) rec[i + 6];
                    removeMirrorEntry(ws, x, y, z, blockId);
                    Object pos = POS_C.getConstructor(int.class, int.class, int.class)
                            .newInstance(x, y, z);
                    // vanilla loaded check: isAreaLoaded(pos.add(0,0,0), pos.add(0,0,0))
                    if ((Boolean) M_AREA_LOADED.invoke(ws, pos, pos)) {
                        Object state = M_GET_STATE.invoke(ws, pos);
                        if (!initStateReflect(state)) {
                            M_RESCHED.invoke(ws, pos, M_STATE_BLOCK.invoke(state), 0);
                            TICKS_RESCHEDULED.incrementAndGet();
                            continue;
                        }
                        Object stateMat = M_MAT.invoke(state);
                        Object stateBlock = M_STATE_BLOCK.invoke(state);
                        Object schedBlock = blockById(blockId);
                        if (schedBlock == null) {
                            // identity miss (GC of the weak id space): fail
                            // closed for this entry -> re-schedule so work
                            // is not lost
                            M_RESCHED.invoke(ws, pos, stateBlock, 0);
                            TICKS_RESCHEDULED.incrementAndGet();
                            continue;
                        }
                        boolean matAir = stateMat == MAT_AIR;
                        boolean equal = (Boolean) M_IS_EQUAL.invoke(null, schedBlock, stateBlock);
                        if (!matAir && equal) {
                            M_UPDATE_TICK.invoke(schedBlock, ws, pos, state, rand);
                            TICKS_EXECUTED.incrementAndGet();
                        } else {
                            // vanilla: consumed silently (no else branch —
                            // offsets 256/274 jump straight to the loop join)
                            TICKS_STALE_CONSUMED.incrementAndGet();
                        }
                    } else {
                        // vanilla requeues unloaded entries (offsets 349-361)
                        Object schedBlock = blockById(blockId);
                        M_RESCHED.invoke(ws, pos,
                                schedBlock != null ? schedBlock : M_STATE_BLOCK.invoke(M_GET_STATE.invoke(ws, pos)),
                                0);
                        TICKS_RESCHEDULED.incrementAndGet();
                    }
                }
            }
            LAST_HANDLED = true;
            return ((TreeSet<?>) F_TREE.get(ws)).isEmpty(); // vanilla result
        } catch (Throwable t) {
            ERRORS.incrementAndGet();
            fail("onTickUpdates: " + t);
            return false;
        }
    }

    /** remove the matching (pos, block) entry from the Java mirror (the
     * same entry Rust decided to drain). Bounded scan; removal by identity. */
    private static void removeMirrorEntry(Object ws, int x, int y, int z, int blockId) {
        try {
            if (!initNtle(ws)) return;
            TreeSet<Object> tree = (TreeSet<Object>) F_TREE.get(ws);
            java.util.Set<Object> set = (java.util.Set<Object>) F_SET.get(ws);
            Object victim = null;
            for (Object e : tree) {
                Object p = F_NTLE_POS.get(e);
                if ((Integer) M_POS_X.invoke(p) != x) continue;
                if ((Integer) M_POS_Y.invoke(p) != y) continue;
                if ((Integer) M_POS_Z.invoke(p) != z) continue;
                Object b = F_NTLE_BLOCK.get(e);
                if (System.identityHashCode(b) != blockId) continue;
                victim = e;
                break;
            }
            if (victim != null) {
                tree.remove(victim);
                set.remove(victim);
                MIRROR_REMOVALS.incrementAndGet();
            }
        } catch (Throwable t) {
            ERRORS.incrementAndGet();
            LAST_ERROR = "removeMirror: " + t;
        }
    }

    /** heal drift: adopt Java-mirror entries Rust does not have (chunk-load
     * NBT writes that bypassed updateBlockTick, restore-mode admissions we
     * skipped, or any direct-set writes). */
    private static void adoptMissing(Object ws, int dim) {
        try {
            if (!initNtle(ws)) return;
            TreeSet<Object> tree = (TreeSet<Object>) F_TREE.get(ws);
            long rustN = tickPendingCount(dim);
            int javaN = tree.size();
            if (rustN >= javaN && !mappingSuspect) return; // fast path
            ByteBuffer ab = ADOPT_TL.get();
            ab.clear();
            int count = 0;
            for (Object e : tree) {
                if (ab.remaining() < 56) break;
                Object p = F_NTLE_POS.get(e);
                Object b = F_NTLE_BLOCK.get(e);
                rememberBlock(System.identityHashCode(b), b); // blockById must resolve adopted ids
                ab.putLong(-1); // re-sequence: fresh seq in tree order
                ab.putLong(F_NTLE_TIME.getLong(e));
                ab.putLong(F_NTLE_PRIO.getInt(e));
                ab.putLong((Integer) M_POS_X.invoke(p));
                ab.putLong((Integer) M_POS_Y.invoke(p));
                ab.putLong((Integer) M_POS_Z.invoke(p));
                ab.putLong(System.identityHashCode(b));
                count++;
            }
            if (count > 0) {
                // full adopt: adopt() dedups by (pos, block), so re-adopting
                // everything Rust already has is a no-op — drift heals and
                // duplicates stay impossible
                mappingSuspect = false;
                int adopted = tickAdopt(dim, addr(ab), count * 7);
                if (adopted > 0) ADOPTED.addAndGet(adopted);
            }
        } catch (Throwable t) {
            ERRORS.incrementAndGet();
            LAST_ERROR = "adoptMissing: " + t;
            mappingSuspect = true;
        }
    }
    private static volatile boolean mappingSuspect = true; // adopt on first drain

    private static void doShadowCompare(Object ws, int dim, long now, boolean runAll) {
        try {
            if (!initNtle(ws)) return;
            long horizon = runAll ? Long.MAX_VALUE - 1 : now;
            long rustN = tickPendingCount(dim);
            int cap = (int) Math.min(rustN, (long) DRAIN_CAP);
            // Rust decision: drain (same predicate/cap as ON), collecting
            // the records so the restore is exact
            StringBuilder rustOrder = new StringBuilder();
            java.util.ArrayList<long[]> drained = new java.util.ArrayList<>();
            int rn = 0;
            while (rn < cap) {
                int want = Math.min(BATCH_CAP, cap - rn);
                ByteBuffer buf = BATCH_TL.get();
                buf.clear();
                int n = tickDrain(dim, horizon, addr(buf), want);
                if (n < 0) { SHADOW_ORDER_MISMATCH.incrementAndGet(); return; }
                if (n > 0) {
                    long[] rec = new long[n * 7];
                    buf.asLongBuffer().get(rec); // relative read from
                                                 // position 0 (asLongBuffer
                                                 // inherits LE order)
                    drained.add(rec);
                    for (int i = 0; i < rec.length; i += 7) {
                        rustOrder.append((int) rec[i + 3]).append(',')
                                .append((int) rec[i + 4]).append(',')
                                .append((int) rec[i + 5]).append(',')
                                .append((int) rec[i + 6]).append(';');
                    }
                    rn += n;
                }
                if (n < want) break;
            }
            // Java decision: vanilla cleaning = first min(size, cap) entries,
            // breaking at the first scheduledTime > now (unless runAll)
            StringBuilder javaOrder = new StringBuilder();
            int jn = 0;
            for (Object e : (TreeSet<?>) F_TREE.get(ws)) {
                if (jn >= cap) break;
                if (!runAll && F_NTLE_TIME.getLong(e) > now) break;
                Object p = F_NTLE_POS.get(e);
                javaOrder.append(M_POS_X.invoke(p)).append(',')
                        .append(M_POS_Y.invoke(p)).append(',')
                        .append(M_POS_Z.invoke(p)).append(',')
                        .append(System.identityHashCode(F_NTLE_BLOCK.get(e)))
                        .append(';');
                jn++;
            }
            SHADOW_COMPARES.incrementAndGet();
            if (rn != jn) SHADOW_COUNT_MISMATCH.incrementAndGet();
            if (!rustOrder.toString().equals(javaOrder.toString()))
                SHADOW_ORDER_MISMATCH.incrementAndGet();
            // SHADOW rebuild: the vanilla body (which runs right after this
            // hook returns false) EXECUTES the tree and drains it — a
            // restore of the drained batch would leave Rust holding stale
            // entries the tree no longer has (shadow1..4: count mismatch
            // every subsequent call). Instead the queue is rebuilt FROM the
            // mirror, so it is exactly the tree at the end of every call
            // and each compare starts from a provably equal state.
            rebuildFromTree(ws, dim);
        } catch (Throwable t) {
            ERRORS.incrementAndGet();
            LAST_ERROR = "shadow: " + t;
        }
    }

    /** queue := exactly the Java mirror (clear + adopt-all). The SHADOW
     * decision engine's steady state; also the unconditional heal. */
    private static void rebuildFromTree(Object ws, int dim) {
        try {
            if (!initNtle(ws)) return;
            tickClear(dim);
            TreeSet<Object> tree = (TreeSet<Object>) F_TREE.get(ws);
            ByteBuffer ab = ADOPT_TL.get();
            int count = 0;
            for (Object e : tree) {
                if (ab.remaining() < 56) {
                    ab.flip();
                    tickAdopt(dim, addr(ab), count * 7);
                    ab.clear();
                    count = 0;
                }
                Object p = F_NTLE_POS.get(e);
                Object b = F_NTLE_BLOCK.get(e);
                rememberBlock(System.identityHashCode(b), b);
                ab.putLong(-1); // fresh seq in tree order
                ab.putLong(F_NTLE_TIME.getLong(e));
                ab.putLong(F_NTLE_PRIO.getInt(e));
                ab.putLong((Integer) M_POS_X.invoke(p));
                ab.putLong((Integer) M_POS_Y.invoke(p));
                ab.putLong((Integer) M_POS_Z.invoke(p));
                ab.putLong(System.identityHashCode(b));
                count++;
            }
            if (count > 0) {
                ab.flip();
                int adopted = tickAdopt(dim, addr(ab), count * 7);
                if (adopted > 0) ADOPTED.addAndGet(adopted);
            }
            mappingSuspect = false;
        } catch (Throwable t) {
            ERRORS.incrementAndGet();
            LAST_ERROR = "rebuildFromTree: " + t;
            mappingSuspect = true;
        }
    }

    // Integer-keyed by VALUE (identityHashCode) holding the block INSTANCE:
    // an IdentityHashMap here keys by box REFERENCE and get(new Integer(id))
    // misses every lookup — the on15 all-reschedule defect
    private static final java.util.Map<Integer, Object> BLOCK_BY_ID =
            new java.util.HashMap<>();
    private static void rememberBlock(int id, Object block) {
        synchronized (BLOCK_BY_ID) { BLOCK_BY_ID.put(id, block); }
    }
    private static Object blockById(int id) {
        synchronized (BLOCK_BY_ID) { return BLOCK_BY_ID.get(id); }
    }

    private static void fail(String why) {
        DISABLED_BY_FAILURE = true;
        LAST_ERROR = why;
    }
}
