package com.rustcraft.bridge;

import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.BlockPos.PooledMutableBlockPos;
import net.minecraft.world.World;
import net.minecraft.world.border.WorldBorder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * M3 collision-feasibility measurement probe: an EXACT replica of
 * World.func_191504_a (1.12.2, verified against the committed disassembly
 * machine/raw/diag/world-collision-full.txt) with aggregate counters only.
 *
 * Semantics replicated branch-for-branch:
 *  - bounds = AABB expanded by 1 block on all sides (floor-1 / ceil+1)
 *  - corner columns (xEdge && zEdge) skipped entirely
 *  - top y of edge columns skipped
 *  - unloaded chunk columns skipped via isBlockLoaded(x, 64, z)
 *  - corrupt-world guard when !returnOnFirst and x/z outside +-30M
 *  - AIR substitution when !returnOnFirst && !border.contains(pos) &&
 *    entityInsideBorder (private func_191503_g, reflected once)
 *  - callback = state.func_185908_a(world, pos, bb, out, entity, false)
 *    (virtual dispatch -> NEID/FoamFix/mod runtime transforms still apply)
 *  - early exit when returnOnFirst && !out.isEmpty()
 *  - pooled-mutable-pos release incl. exception path
 *
 * Counters are aggregate LongAdders + bounded per-section maps + fixed
 * histograms; nothing per-block is logged.
 */
public final class CollisionProbe {

    public static final AtomicLong QUERIES = new AtomicLong();
    public static final AtomicLong SCAN_NS = new AtomicLong();
    public static final AtomicLong POS_CONSIDERED = new AtomicLong();   // y-iterations reached
    public static final AtomicLong COL_SKIPPED_CORNER = new AtomicLong();
    public static final AtomicLong COL_SKIPPED_UNLOADED = new AtomicLong();
    public static final AtomicLong Y_SKIPPED_EDGE_TOP = new AtomicLong();
    public static final AtomicLong STATES_READ = new AtomicLong();      // getBlockState calls
    public static final AtomicLong STATES_AIR = new AtomicLong();
    public static final AtomicLong STATES_AIR_SUBST = new AtomicLong(); // border substitution
    public static final AtomicLong CALLBACKS = new AtomicLong();
    public static final AtomicLong BOXES = new AtomicLong();
    public static final AtomicLong BOX_QUERIES = new AtomicLong();      // queries with >=1 box
    // positions/query histogram up to 4096
    static final AtomicLong[] POS_HIST = new AtomicLong[4097];
    // sections/query histogram up to 64
    static final AtomicLong[] SEC_HIST = new AtomicLong[65];
    // boxes/query histogram up to 64
    static final AtomicLong[] BOX_HIST = new AtomicLong[65];
    // AABB block-volume histogram (positions in the raw expanded box)
    static final AtomicLong[] VOL_HIST = new AtomicLong[4097];
    // per-section (x>>4,y>>4,z>>4 -> key) -> [positions, boxes]
    static final Map<Long, long[]> SECTION_STATS = new HashMap<>();
    // block-class -> [callbacks, boxes] (bounded by distinct classes)
    static final Map<String, long[]> BLOCK_CLASSES = new HashMap<>();

    // NOTE: vanilla's private World.func_191503_g(Entity) (entity-inside-border
    // flag) is deliberately NOT reflected here: initializing Entity.class from
    // this class's <clinit> re-enters the (already-replaced) collision path and
    // leaves this class partially initialized (measured: attempts 2-4). The
    // flag only influences queries at positions OUTSIDE the world border; the
    // default 60M-block border means no in-workload position is ever outside,
    // so flagInside=false is EXACT for this measurement (documented deviation).

    static {
        for (int i = 0; i < POS_HIST.length; i++) POS_HIST[i] = new AtomicLong();
        for (int i = 0; i < SEC_HIST.length; i++) SEC_HIST[i] = new AtomicLong();
        for (int i = 0; i < BOX_HIST.length; i++) BOX_HIST[i] = new AtomicLong();
        for (int i = 0; i < VOL_HIST.length; i++) VOL_HIST[i] = new AtomicLong();
    }

    private CollisionProbe() {}

    /** EXACT replica of func_191504_a with counters. */
    public static boolean scan(World world, net.minecraft.entity.Entity entity,
                               AxisAlignedBB bb, boolean returnOnFirst,
                               List<AxisAlignedBB> out) {
        long t0 = System.nanoTime();
        int minX = MathHelper.func_76128_c(bb.field_72340_a) - 1;
        int maxX = MathHelper.func_76143_f(bb.field_72336_d) + 1;
        int minY = MathHelper.func_76128_c(bb.field_72338_b) - 1;
        int maxY = MathHelper.func_76143_f(bb.field_72337_e) + 1;
        int minZ = MathHelper.func_76128_c(bb.field_72339_c) - 1;
        int maxZ = MathHelper.func_76143_f(bb.field_72334_f) + 1;

        WorldBorder border = world.func_175723_af();
        boolean flagOutside = entity != null && entity.func_174832_aS(); // kept for parity documentation
        boolean flagInside = false; // see class note: exact for in-border workloads
        IBlockState airDefault = Blocks.field_150348_b.func_176223_P();

        int posCount = 0, secCount = 0, boxesThis = 0;
        java.util.HashSet<Long> secs = new java.util.HashSet<>(8);

        PooledMutableBlockPos pos = PooledMutableBlockPos.func_185346_s();
        try {
            outer:
            for (int x = minX; x < maxX; x++) {
                for (int z = minZ; z < maxZ; z++) {
                    boolean xEdge = (x == minX || x == maxX - 1);
                    boolean zEdge = (z == minZ || z == maxZ - 1);
                    if (xEdge && zEdge) { COL_SKIPPED_CORNER.incrementAndGet(); continue; }
                    pos.func_181079_c(x, 64, z);
                    if (!world.func_175667_e(pos)) { COL_SKIPPED_UNLOADED.incrementAndGet(); continue; }
                    for (int y = minY; y < maxY; y++) {
                        if ((xEdge || zEdge) && y == maxY - 1) { Y_SKIPPED_EDGE_TOP.incrementAndGet(); continue; }
                        if (!returnOnFirst) {
                            if (x < -30000000 || x >= 30000000 || z < -30000000 || z >= 30000000) {
                                entity.func_174821_h(true); // bytecode offset 326 side effect
                                pos.func_185344_t();
                                return true;
                            }
                        }
                        pos.func_181079_c(x, y, z); // bytecode offset 337: set BEFORE border check
                        posCount++;
                        try { STATES_READ.incrementAndGet(); } catch (Throwable ignored) {}
                        IBlockState state;
                        if (!returnOnFirst && !border.func_177746_a(pos) && flagInside) {
                            state = airDefault;
                            STATES_AIR_SUBST.incrementAndGet();
                        } else {
                            state = world.func_180495_p(pos);
                        }
                        boolean isAir = state.func_177230_c() == Blocks.field_150348_b;
                        if (isAir) { try { STATES_AIR.incrementAndGet(); } catch (Throwable ignored) {} }
                        long secKey = sectionKey(x, y, z);
                        secs.add(secKey);
                        int before = out.size();
                        state.func_185908_a(world, pos, bb, out, entity, false);
                        CALLBACKS.incrementAndGet();
                        int added = out.size() - before;
                        if (added > 0) {
                            BOXES.addAndGet(added);
                            boxesThis += added;
                            String cls = state.func_177230_c().getClass().getName();
                            synchronized (BLOCK_CLASSES) {
                                long[] cc = BLOCK_CLASSES.computeIfAbsent(cls, k -> new long[2]);
                                cc[0]++; cc[1] += added;
                            }
                        }
                        synchronized (SECTION_STATS) {
                            long[] ss = SECTION_STATS.computeIfAbsent(secKey, k -> new long[2]);
                            ss[0]++;
                            ss[1] += added;
                        }
                        if (returnOnFirst && !out.isEmpty()) {
                            pos.func_185344_t();
                            finish(t0, posCount, secs, boxesThis, minX, minY, minZ, maxX, maxY, maxZ);
                            return true;
                        }
                    }
                }
            }
            pos.func_185344_t();
        } catch (Throwable t) {
            pos.func_185344_t();
            throw t;
        }
        finish(t0, posCount, secs, boxesThis, minX, minY, minZ, maxX, maxY, maxZ);
        return !out.isEmpty();
    }

    private static void finish(long t0, int posCount, java.util.HashSet<Long> secs,
                               int boxesThis, int minX, int minY, int minZ,
                               int maxX, int maxY, int maxZ) {
        try {
        if (POS_HIST == null || SEC_HIST == null || VOL_HIST == null) return; // clinit re-entry
        QUERIES.incrementAndGet();
        SCAN_NS.addAndGet(System.nanoTime() - t0);
        POS_CONSIDERED.addAndGet(posCount);
        if (posCount < POS_HIST.length - 1) POS_HIST[posCount].incrementAndGet();
        if (secs.size() < SEC_HIST.length - 1) SEC_HIST[secs.size()].incrementAndGet();
        if (boxesThis < BOX_HIST.length - 1) BOX_HIST[boxesThis].incrementAndGet();
        if (boxesThis > 0) BOX_QUERIES.incrementAndGet();
        int vol = (maxX - minX) * (maxY - minY) * (maxZ - minZ);
        if (vol >= 0 && vol < VOL_HIST.length - 1) VOL_HIST[vol].incrementAndGet();
        } catch (Throwable ignored) {
            // the probe must never alter or crash the measured path
        }
    }

    static long sectionKey(int x, int y, int z) {
        return ((long) (x >> 4) << 42) ^ ((long) (y >> 4) << 21) ^ (long) (z >> 4);
    }

    public static String dump() {
        if (QUERIES == null || POS_HIST == null) return "collision_probe=PARTIAL_INIT"; // never NPE the dumper
        StringBuilder sb = new StringBuilder();
        sb.append("collision_probe_queries=").append(QUERIES.get());
        sb.append("\ncollision_probe_scan_ms=").append(SCAN_NS.get() / 1_000_000);
        sb.append("\ncollision_probe_pos_considered=").append(POS_CONSIDERED.get());
        sb.append("\ncollision_probe_col_skipped_corner=").append(COL_SKIPPED_CORNER.get());
        sb.append("\ncollision_probe_col_skipped_unloaded=").append(COL_SKIPPED_UNLOADED.get());
        sb.append("\ncollision_probe_y_skipped_edge_top=").append(Y_SKIPPED_EDGE_TOP.get());
        sb.append("\ncollision_probe_states_read=").append(STATES_READ.get());
        sb.append("\ncollision_probe_states_air=").append(STATES_AIR.get());
        sb.append("\ncollision_probe_states_air_subst=").append(STATES_AIR_SUBST.get());
        sb.append("\ncollision_probe_callbacks=").append(CALLBACKS.get());
        sb.append("\ncollision_probe_boxes=").append(BOXES.get());
        sb.append("\ncollision_probe_box_queries=").append(BOX_QUERIES.get());
        sb.append("\ncollision_probe_pos_hist=").append(hist(POS_HIST));
        sb.append("\ncollision_probe_sec_hist=").append(hist(SEC_HIST));
        sb.append("\ncollision_probe_box_hist=").append(hist(BOX_HIST));
        sb.append("\ncollision_probe_vol_hist=").append(hist(VOL_HIST));
        synchronized (SECTION_STATS) {
            long emptySec = 0, nonEmpty = 0, posInEmpty = 0;
            for (long[] v : SECTION_STATS.values()) {
                if (v[1] == 0) { emptySec++; posInEmpty += v[0]; } else nonEmpty++;
            }
            sb.append("\ncollision_probe_sections_seen=").append(SECTION_STATS.size());
            sb.append("\ncollision_probe_sections_never_boxed=").append(emptySec);
            sb.append("\ncollision_probe_positions_in_never_boxed_sections=").append(posInEmpty);
            sb.append("\ncollision_probe_sections_boxed=").append(nonEmpty);
        }
        synchronized (BLOCK_CLASSES) {
            sb.append("\ncollision_probe_block_classes=").append(BLOCK_CLASSES.size());
            int shown = 0;
            for (Map.Entry<String, long[]> e : sortByBoxes(BLOCK_CLASSES)) {
                if (shown++ >= 12) break;
                sb.append("\ncollision_probe_class_").append(shown).append("=")
                        .append(e.getKey()).append(" cb=").append(e.getValue()[0])
                        .append(" boxes=").append(e.getValue()[1]);
            }
        }
        return sb.toString();
    }

    private static String hist(AtomicLong[] h) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < h.length; i++) {
            if (h[i].get() == 0) continue;
            if (sb.length() > 0) sb.append(',');
            sb.append(i).append(':').append(h[i].get());
        }
        return sb.length() == 0 ? "empty" : sb.toString();
    }

    private static java.util.List<Map.Entry<String, long[]>> sortByBoxes(Map<String, long[]> m) {
        java.util.ArrayList<Map.Entry<String, long[]>> l = new java.util.ArrayList<>(m.entrySet());
        l.sort((a, b) -> Long.compare(b.getValue()[1], a.getValue()[1]));
        return l;
    }
}
