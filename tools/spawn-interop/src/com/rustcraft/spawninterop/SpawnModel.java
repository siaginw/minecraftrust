package com.rustcraft.spawninterop;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.util.math.ChunkPos;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * M3.0 prototype dataset + the three implementations under test.
 *
 * EXACT vanilla query semantics (verified by disassembly of
 * MapGenStructure.func_175797_c):
 *   for start in map.values():               // fastutil iteration order
 *     if !start.isValid() continue;
 *     if !start.getBoundingBox().isInside(pos) continue;
 *     for comp in start.getComponents():
 *       if comp.getBoundingBox().isInside(pos) return start;   // FIRST match
 *   return null
 *
 * Ranks are assigned by iterating the SAME Long2ObjectOpenHashMap the
 * reference uses, so first-match ordering is identical across all three
 * implementations by construction.
 */
public final class SpawnModel {

    public static final class Start {
        public final int rank;
        public final boolean valid;
        public final int minX, minY, minZ, maxX, maxY, maxZ;
        public final int[][] comps; // each [6]: minx,maxx,miny,maxy,minz,maxz
        public final long chunkKey; // vanilla map key (informational)

        Start(int rank, boolean valid, int[] bb, int[][] comps, long chunkKey) {
            this.rank = rank;
            this.valid = valid;
            this.minX = bb[0]; this.maxX = bb[3];
            this.minY = bb[1]; this.maxY = bb[4];
            this.minZ = bb[2]; this.maxZ = bb[5];
            this.comps = comps;
            this.chunkKey = chunkKey;
        }

        boolean boxContains(int x, int y, int z) {
            return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
        }

        boolean componentContains(int x, int y, int z) {
            for (int[] c : comps) {
                if (x >= c[0] && x <= c[1] && y >= c[2] && y <= c[3] && z >= c[4] && z <= c[5]) return true;
            }
            return false;
        }

        int flatComponents() {
            int n = 0;
            for (int[] c : comps) n += c.length;
            return n;
        }
    }

    public final List<Start> starts = new ArrayList<>();
    public final String name;

    public SpawnModel(String name) { this.name = name; }

    /** Build the vanilla-shaped map and assign ranks in ITS iteration order. */
    public Long2ObjectOpenHashMap<Start> buildVanillaMap() {
        // first insertion pass mirrors how vanilla populates (keyed by chunk)
        Long2ObjectOpenHashMap<Start> byKey = new Long2ObjectOpenHashMap<>(1024);
        for (Start s : starts) {
            byKey.put(s.chunkKey, s);
        }
        return byKey;
    }

    /** Ranks must equal the map's values() iteration order. */
    public void assignRanksFromMap() {
        Long2ObjectOpenHashMap<Start> m = buildVanillaMap();
        int r = 0;
        for (Start s : m.values()) {
            setRank(s, r++);
        }
        starts.sort((a, b) -> Integer.compare(a.rank, b.rank));
    }

    private static void setRank(Start s, int r) {
        try {
            java.lang.reflect.Field f = Start.class.getDeclaredField("rank");
            f.setAccessible(true);
            f.setInt(s, r);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------------- A. REFERENCE_LINEAR (vanilla-exact) ----------------

    public static final class RefLinear {
        public long examinedLast = 0;
        private final Long2ObjectOpenHashMap<Start> map;

        public RefLinear(SpawnModel m) { this.map = m.buildVanillaMap(); }

        /** Exact replica of func_175797_c. Returns rank or -1. */
        public int query(int x, int y, int z) {
            examinedLast = 0;
            for (Start s : map.values()) {
                examinedLast++;
                if (!s.valid) continue;
                if (!s.boxContains(x, y, z)) continue;
                if (s.componentContains(x, y, z)) return s.rank;
            }
            return -1;
        }
    }

    // ---------------- B. JAVA_INDEX (same algorithm in Java) ----------------

    public static final class JavaIndex {
        public long examinedLast = 0;
        private static final int SHIFT = 9;
        private final java.util.HashMap<Long, java.util.ArrayList<Start>> regions = new java.util.HashMap<>();
        private final java.util.HashMap<Long, Start> byRank = new java.util.HashMap<>();

        public JavaIndex(SpawnModel m) {
            Long2ObjectOpenHashMap<Start> map = m.buildVanillaMap();
            for (Start s : map.values()) {
                byRank.put((long) s.rank, s);
                for (long k : regionKeys(s)) {
                    regions.computeIfAbsent(k, kk -> new ArrayList<>()).add(s);
                }
            }
            for (java.util.ArrayList<Start> b : regions.values()) b.sort((a, c) -> Integer.compare(a.rank, c.rank));
        }

        private static long pack(int rx, int rz) { return ((long) rx << 32) | (rz & 0xFFFFFFFFL); }

        private static List<Long> regionKeys(Start s) {
            List<Long> ks = new ArrayList<>(2);
            for (int rx = s.minX >> SHIFT; rx <= s.maxX >> SHIFT; rx++)
                for (int rz = s.minZ >> SHIFT; rz <= s.maxZ >> SHIFT; rz++)
                    ks.add(pack(rx, rz));
            return ks;
        }

        public int query(int x, int y, int z) {
            examinedLast = 0;
            java.util.ArrayList<Start> b = regions.get(pack(x >> SHIFT, z >> SHIFT));
            if (b == null) return -1;
            for (Start s : b) {
                examinedLast++;
                if (!s.valid) continue;
                if (!s.boxContains(x, y, z)) continue;
                if (s.componentContains(x, y, z)) return s.rank;
            }
            return -1;
        }

        public int regionCount() { return regions.size(); }
    }

    // ---------------- C. RUST_INDEX (JNI) ----------------

    public static final class RustIndex {
        private static boolean loaded = false;
        static synchronized void load() {
            if (!loaded) { System.loadLibrary("rustcraft_ffi"); loaded = true; }
        }

        private final long handle;

        public RustIndex(SpawnModel m) {
            load();
            handle = com.rustcraft.interop.SpawnIndexInterop.create();
            java.nio.IntBuffer scratch = java.nio.ByteBuffer.allocateDirect(6 * 4096 * 4).order(java.nio.ByteOrder.nativeOrder()).asIntBuffer();
            long addr = address(scratch);
            for (Start s : m.starts) {
                int i = 0;
                for (int[] c : s.comps) for (int v : c) scratch.put(i++, v);
                com.rustcraft.interop.SpawnIndexInterop.insert(handle, s.rank, s.valid,
                        s.minX, s.minY, s.minZ, s.maxX, s.maxY, s.maxZ, addr, s.flatComponents());
            }
        }

        @SuppressWarnings("unused")
        private static long address(java.nio.Buffer b) {
            try {
                java.lang.reflect.Method m = b.getClass().getMethod("address");
                m.setAccessible(true);
                return (Long) m.invoke(b);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }

        public void free() { com.rustcraft.interop.SpawnIndexInterop.freeRaw(handle); }
        public int query(int x, int y, int z) { return com.rustcraft.interop.SpawnIndexInterop.query(handle, x, y, z); }
        public long[] stats() {
            java.nio.ByteBuffer bb = java.nio.ByteBuffer.allocateDirect(8 * 6).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            long addr = address(bb);
            com.rustcraft.interop.SpawnIndexInterop.stats(handle, addr);
            long[] out = new long[6];
            for (int i = 0; i < 6; i++) out[i] = bb.getLong(i * 8);
            return out;
        }

    }

    // ---------------- datasets ----------------

    /** Deterministic synthetic dataset. style: "temple" small boxes sparse;
     *  "dense" overlapping; "huge" giant boxes; "negative" far-negative coords.
     *  Starts are deduped by vanilla chunk key (later replaces earlier, exactly
     *  like Long2ObjectMap.put) before ranks are assigned. */
    public static SpawnModel synthetic(String name, String style, int count, long seed) {
        SpawnModel raw = new SpawnModel(name);
        SpawnModel m = new SpawnModel(name);
        Random r = new Random(seed);
        for (int i = 0; i < count; i++) {
            int span = 16 + r.nextInt(48);
            int cx, cz;
            switch (style) {
                case "negative": cx = -60_000_000 + r.nextInt(4_000_000); cz = -60_000_000 + r.nextInt(4_000_000); break;
                case "dense":    cx = r.nextInt(20_000) - 10_000;        cz = r.nextInt(20_000) - 10_000; break;
                case "huge":     cx = r.nextInt(2_000_000) - 1_000_000;  cz = r.nextInt(2_000_000) - 1_000_000; span = 2_000 + r.nextInt(8_000); break;
                default:         cx = r.nextInt(2_000_000) - 1_000_000;  cz = r.nextInt(2_000_000) - 1_000_000; break;
            }
            int y0 = 40 + r.nextInt(60);
            int[] bb = {cx, y0, cz, cx + span, y0 + 20 + r.nextInt(30), cz + span};
            int nComp = 1 + r.nextInt(4);
            int[][] comps = new int[nComp][];
            for (int c = 0; c < nComp; c++) {
                int ox = r.nextInt(Math.max(1, span / 2)), oz = r.nextInt(Math.max(1, span / 2));
                int w = Math.max(1, span / 2 + r.nextInt(Math.max(1, span / 2)));
                comps[c] = new int[]{cx + ox, cx + ox + w, y0, y0 + 20, cz + oz, cz + oz + w};
            }
            // a few starts invalid (isValid=false path)
            boolean valid = !(style.equals("temple") && r.nextInt(50) == 0);
            Start s = new Start(-1, valid, bb, comps, ChunkPos.func_77272_a(cx >> 4, cz >> 4));
            raw.starts.add(s);
        }
        // vanilla-map semantics: same chunk key replaces
        Long2ObjectOpenHashMap<Start> byKey = new Long2ObjectOpenHashMap<>(1024);
        for (Start s : raw.starts) byKey.put(s.chunkKey, s);
        for (Start s : byKey.values()) m.starts.add(s);
        m.assignRanksFromMap();
        return m;
    }
}
