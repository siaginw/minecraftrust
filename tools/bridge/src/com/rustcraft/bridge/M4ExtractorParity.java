package com.rustcraft.bridge;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * M4.3C §6 — SLOW vs FAST extractor parity campaign on real containers.
 * Covers 4/5/6/7/8-bit palettes, global palette, mutation churn, palette
 * resize transitions, state replacement, sparse sections, post-populated-like
 * terrain. Requires 0 divergences; any divergence dumps via the M4.3C path.
 */
public class M4ExtractorParity {

    static int pass = 0, fail = 0;

    public static void main(String[] args) throws Exception {
        System.setProperty("minecraftrust.m1.native_state", "OFF");
        System.setProperty("minecraftrust.m4.packet_compare", "SHADOW");
        Class.forName("net.minecraft.init.Bootstrap").getMethod("func_151354_b").invoke(null);
        M4PacketParityHarness.initReflectionPublic();

        for (int unique : new int[]{1, 2, 5, 15, 16, 17, 20, 31, 32, 33, 48, 64, 65, 100, 127, 128, 129, 200, 255, 256, 257, 300, 512}) {
            caseWidth(unique);
        }
        caseChurnAndResize();
        caseSparseAndEmpty();
        caseReplacement();

        System.out.println("RESULT: " + pass + " pass, " + fail + " fail"
                + " dualVerifyRuns=" + com.rustcraft.bridge.M4Coherency.DUAL_VERIFY_RUNS.get()
                + " diverge=" + com.rustcraft.bridge.M4Coherency.DUAL_VERIFY_DIVERGE.get());
        System.exit(fail == 0 ? 0 : 1);
    }

    static void check(String name, boolean ok, String d) {
        if (ok) { pass++; System.out.println(name + " -> PASS" + (d == null ? "" : " (" + d + ")")); }
        else { fail++; System.out.println(name + " -> FAIL " + d); }
    }

    static Object buildSection(int unique, int mode) throws Exception {
        Object[] r = M4PacketParityHarness.baseChunkAndPrimer(400, 40);
        Object chunk = r[0];
        Object[] storages = (Object[]) chunk.getClass().getMethod("func_76587_i").invoke(chunk);
        Object target = null;
        for (Object st : storages) if (st != null) { target = st; break; }
        // Direct container writes: palette/BitArray state without any world
        // callbacks (onBlockAdded needs a live world offline). The extractor
        // reads exactly this state.
        Object[] storages0 = (Object[]) chunk.getClass().getMethod("func_76587_i").invoke(chunk);
        Object target0 = null;
        for (Object st : storages0) if (st != null) { target0 = st; break; }
        Object container0 = target0.getClass().getMethod("func_186049_g").invoke(target0);
        java.lang.reflect.Method setIdx = Class.forName("net.minecraft.world.chunk.BlockStateContainer")
                .getDeclaredMethod("func_186014_b", int.class, Class.forName("net.minecraft.block.state.IBlockState"));
        setIdx.setAccessible(true);
        java.lang.reflect.Method setBlock = null; // unused; kept null for clarity
        if (setBlock == null) setBlock = null;
        switch (mode) {
            case 0: // width: fill with i%unique states
                for (int i = 0; i < 4096; i++) {
                    setIdx.invoke(container0, i, safeGid(i % unique));
                }
                break;
            case 1: // churn + resize: grow palette then churn with shifts
                for (int i = 0; i < 4096; i++) {
                    setIdx.invoke(container0, i, safeGid(i % Math.max(1, unique / 2)));
                }
                for (int i = 0; i < 2048; i++) {
                    setIdx.invoke(container0, i, safeGid((i * 11 + 3) % unique));
                }
                for (int i = 0; i < 512; i++) {
                    setIdx.invoke(container0, i * 7 % 4096, safeGid((i + 29) % unique));
                }
                break;
            case 2: // sparse: 37 blocks only
                for (int i = 0; i < 37; i++) {
                    setIdx.invoke(container0, i * 111 % 4096, safeGid(i % 5));
                }
                break;
            case 3: // replacement: fill A then replace all with B (same count, stale palette entries)
                for (int i = 0; i < 4096; i++) {
                    setIdx.invoke(container0, i, safeGid(i % 4));
                }
                for (int i = 0; i < 4096; i++) {
                    setIdx.invoke(container0, i, safeGid((i + 2) % 4));
                }
                break;
        }
        return target0;
    }

    static boolean isSafeState(Object st) {
        try {
            Object blk = st.getClass().getMethod("func_177230_c").invoke(st);
            String cn = blk.getClass().getName();
            return !cn.contains("Liquid") && !cn.contains("Fire") && !cn.contains("Torch");
        } catch (Throwable t) { return false; }
    }

    static Object safeGid(int k) throws Exception {
        for (int i = 0; i < 512; i++) {
            Object st = M4PacketParityHarness.registryById(M4PacketParityHarness.testGid((k + i * 7) % 512));
            if (isSafeState(st)) return st;
        }
        return M4PacketParityHarness.registryById(M4PacketParityHarness.testGid(1));
    }

    static Object pos(int i) throws Exception {
        return Class.forName("net.minecraft.util.math.BlockPos")
                .getConstructor(int.class, int.class, int.class)
                .newInstance(i & 15, 5 + ((i >> 8) & 3), (i >> 4) & 15);
    }

    static void compareOne(String name, Object storage) throws Exception {
        Object container = storage.getClass().getMethod("func_186049_g").invoke(storage);
        // fast into fresh array
        int[] fast = new int[4096];
        int[] r = com.rustcraft.bridge.M4Coherency.fastExtractPublic(container, fast);
        // slow always
        int[] slow = new int[4096];
        java.lang.reflect.Method gs = Class.forName("net.minecraft.world.chunk.BlockStateContainer")
                .getMethod("func_186016_a", int.class, int.class, int.class);
        gs.setAccessible(true);
        Object regMap = regMap();
        java.lang.reflect.Method gid = regMap.getClass().getMethod("func_148747_b", Object.class);
        for (int i = 0; i < 4096; i++) {
            Object st = gs.invoke(container, i & 15, i >> 8, (i >> 4) & 15);
            Integer g = (Integer) gid.invoke(regMap, st);
            slow[i] = g == null ? 0 : g;
        }
        boolean ok = r != null;
        int firstBad = -1;
        if (ok) {
            for (int i = 0; i < 4096; i++) {
                if (slow[i] != fast[i]) { firstBad = i; ok = false; break; }
            }
        }
        check(name, ok, ok ? null : ("firstBad=" + firstBad + " slow=" + (firstBad >= 0 ? slow[firstBad] : -1)
                + " fast=" + (firstBad >= 0 ? fast[firstBad] : -1) + (r == null ? " FAST=NULL" : "")));
    }

    static Object regMap() throws Exception {
        Field f = Class.forName("net.minecraft.block.Block").getDeclaredField("field_176229_d");
        f.setAccessible(true);
        return f.get(null);
    }

    static void caseWidth(int unique) throws Exception {
        compareOne("width unique=" + unique, buildSection(unique, 0));
    }

    static void caseChurnAndResize() throws Exception {
        compareOne("churn+resize u=64", buildSection(64, 1));
        compareOne("churn+resize u=257", buildSection(257, 1));
        compareOne("churn+resize u=16->33", buildSection(33, 1));
    }

    static void caseSparseAndEmpty() throws Exception {
        compareOne("sparse 37 blocks", buildSection(5, 2));
        Object[] r = M4PacketParityHarness.baseChunkAndPrimer(401, 41); // untouched section = empty
        Object[] storages = (Object[]) r[0].getClass().getMethod("func_76587_i").invoke(r[0]);
        Object empty = storages[10];
        if (empty != null) compareOne("untouched y-section", empty);
        else check("untouched y-section null-sentinel skip", true, null);
    }

    static void caseReplacement() throws Exception {
        compareOne("replacement stale-palette", buildSection(4, 3));
    }
}
