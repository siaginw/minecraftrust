package com.rustcraft.bridge;

import java.lang.reflect.Field;

/**
 * M4.2E — offline deferred-comparison suite through the ACTUAL entries:
 * M4PacketCompare.onPacketBuilt (what the transformer injects), the bounded
 * queue, M4Coherency sync, and processDeferred (what the flusher runs).
 *
 * Cases: deferred match; stale temporal snapshot (mutated between capture and
 * sync -> SKIP, never compared); capacity skip; expiry skip; semantic-only
 * (valid palette-history divergence counted separately, never a mismatch).
 */
public class M4DeferredTest {

    static int pass = 0, fail = 0;
    static java.lang.reflect.Constructor<?> PKT_CT;
    static Field F_PAYLOAD, F_MASK;

    public static void main(String[] args) throws Exception {
        System.setProperty("minecraftrust.m4.packet_compare", "SHADOW");
        Class.forName("net.minecraft.init.Bootstrap").getMethod("func_151354_b").invoke(null);
        M4PacketParityHarness.initReflectionPublic();
        PKT_CT = M4PacketParityHarness.packetCtor();
        F_PAYLOAD = M4PacketParityHarness.pktPayloadField();
        F_MASK = M4PacketParityHarness.pktMaskField();

        caseMatch();
        caseStale();
        caseCapacity();
        caseExpiry();
        caseSemanticOnly();

        System.out.println("RESULT: " + pass + " pass, " + fail + " fail");
        System.exit(fail == 0 ? 0 : 1);
    }

    static void awaitSynced(Object chunk, long ms) throws Exception {
        long deadline = System.currentTimeMillis() + ms;
        while (!M4Coherency.isFullySynced(chunk)) {
            if (System.currentTimeMillis() > deadline) throw new IllegalStateException("sync timeout");
            Thread.sleep(10);
        }
    }

    static void check(String name, boolean ok, String d) {
        if (ok) { pass++; System.out.println(name + " -> PASS" + (d == null ? "" : " (" + d + ")")); }
        else { fail++; System.out.println(name + " -> FAIL " + d); }
    }

    static Object[] registered(int cx, int cz) throws Exception {
        Object[] r = M4PacketParityHarness.baseChunkAndPrimer(cx, cz);
        Object chunk = r[0];
        long gen = NativeChunkBridge.register(0, cx, cz, addr((java.nio.ByteBuffer) r[1]), 0L);
        if (gen <= 0) throw new IllegalStateException("register failed");
        return new Object[] { chunk, gen };
    }

    static Object packet(Object chunk) throws Exception {
        return PKT_CT.newInstance(chunk, 65535);
    }

    static void caseMatch() throws Exception {
        Object[] r = registered(90, 1);
        Object chunk = r[0];
        M4PacketCompare.onPacketBuilt(packet(chunk), chunk, 65535);   // unsynced -> deferred
        check("defer: enqueued", M4PacketCompare.DEFERRED_ENQUEUED.get() == 1
                && M4PacketCompare.UNSYNCED_SKIPPED.get() == 1, "enq=" + M4PacketCompare.DEFERRED_ENQUEUED.get());
        awaitSynced(chunk, 5000);                                      // dedicated sync worker (event-driven)
        M4PacketCompare.processDeferred(16);
        check("defer: completed exact-byte match", M4PacketCompare.DEFERRED_COMPLETED.get() == 1
                && M4PacketCompare.GATE_A_EXACT.get() == 1
                && M4PacketCompare.COMPARE_DEFERRED.get() == 1, "done=" + M4PacketCompare.DEFERRED_COMPLETED.get()
                + " exact=" + M4PacketCompare.GATE_A_EXACT.get());
        check("defer: zero mismatches", M4PacketCompare.MISMATCHES.get() == 0, null);
        NativeChunkBridge.unload(0, 90, 1);
    }

    static void caseStale() throws Exception {
        resetCounters();
        Object[] r = registered(91, 2);
        Object chunk = r[0];
        M4PacketCompare.onPacketBuilt(packet(chunk), chunk, 65535);   // capture at version V
        // Java state mutates BETWEEN capture and sync (real path + hook)
        Object pos = Class.forName("net.minecraft.util.math.BlockPos")
                .getConstructor(int.class, int.class, int.class).newInstance(5, 70, 5);
        chunk.getClass().getMethod("func_177436_a", Class.forName("net.minecraft.util.math.BlockPos"),
                Class.forName("net.minecraft.block.state.IBlockState"))
                .invoke(chunk, pos, M4PacketParityHarness.registryById(M4PacketParityHarness.testGid(2)));
        ChunkMutationTracker.onBlockSet(chunk, pos);
        awaitSynced(chunk, 5000);                                      // sync runs — at version V+1
        M4PacketCompare.processDeferred(16);
        check("defer-stale: SKIPPED, never compared", M4PacketCompare.DEFERRED_STALE.get() == 1
                && M4PacketCompare.DEFERRED_COMPLETED.get() == 0
                && M4PacketCompare.COMPARE_DEFERRED.get() == 0, "stale=" + M4PacketCompare.DEFERRED_STALE.get());
        NativeChunkBridge.unload(0, 91, 2);
    }

    static void caseCapacity() throws Exception {
        resetCounters();
        M4PacketCompare.setLimitsForTest(1, 32L * 1024 * 1024, 131072, 15000); // one record max
        Object[] r1 = registered(92, 3);
        Object[] r2 = registered(93, 4);
        M4PacketCompare.onPacketBuilt(packet(r1[0]), r1[0], 65535);   // fills the queue
        M4PacketCompare.onPacketBuilt(packet(r2[0]), r2[0], 65535);   // over capacity
        check("defer-capacity: second capture skipped with reason",
                M4PacketCompare.DEFERRED_CAPACITY_SKIPPED.get() == 1
                && M4PacketCompare.DEFERRED_ENQUEUED.get() == 1, "cap=" + M4PacketCompare.DEFERRED_CAPACITY_SKIPPED.get());
        M4PacketCompare.setLimitsForTest(1024, 32L * 1024 * 1024, 131072, 15000);
        awaitSynced(r1[0], 5000);
        M4PacketCompare.processDeferred(16);                           // drain r1's record
        NativeChunkBridge.unload(0, 92, 3);
        NativeChunkBridge.unload(0, 93, 4);
    }

    static void caseExpiry() throws Exception {
        resetCounters();
        M4PacketCompare.setLimitsForTest(1024, 32L * 1024 * 1024, 131072, 1); // 1ms expiry
        Object[] r = registered(94, 5);
        M4PacketCompare.onPacketBuilt(packet(r[0]), r[0], 65535);
        Thread.sleep(50);                                              // record expires before processing
        M4Coherency.flushOnce();
        M4PacketCompare.processDeferred(16);
        check("defer-expiry: expired skip, never compared", M4PacketCompare.DEFERRED_EXPIRED.get() == 1
                && M4PacketCompare.DEFERRED_COMPLETED.get() == 0, "exp=" + M4PacketCompare.DEFERRED_EXPIRED.get());
        M4PacketCompare.setLimitsForTest(1024, 32L * 1024 * 1024, 131072, 15000);
        NativeChunkBridge.unload(0, 94, 5);
    }

    static void caseSemanticOnly() throws Exception {
        resetCounters();
        Object[] r = registered(95, 6);
        Object chunk = r[0];
        // Churn BEFORE capture: Java palette retains stale entries; the minimal
        // native palette cannot be byte-equal, but decoded states must match.
        java.lang.reflect.Method setBlock = chunk.getClass().getMethod("func_177436_a",
                Class.forName("net.minecraft.util.math.BlockPos"), Class.forName("net.minecraft.block.state.IBlockState"));
        for (int i = 0; i < 300; i++) {
            Object pos = Class.forName("net.minecraft.util.math.BlockPos")
                    .getConstructor(int.class, int.class, int.class)
                    .newInstance((i * 7) & 15, 64 + (i & 3), (i * 13) & 15);
            setBlock.invoke(chunk, pos, M4PacketParityHarness.registryById(M4PacketParityHarness.testGid(i % 50)));
            setBlock.invoke(chunk, pos, M4PacketParityHarness.registryById(M4PacketParityHarness.testGid((i + 7) % 50)));
            ChunkMutationTracker.onBlockSet(chunk, pos);
        }
        M4PacketCompare.onPacketBuilt(packet(chunk), chunk, 65535);   // capture AFTER churn
        awaitSynced(chunk, 5000);
        M4PacketCompare.processDeferred(16);
        check("defer-semantic: not byte-exact but decoded-equal",
                M4PacketCompare.MATCHES_SEMANTIC.get() == 1
                && M4PacketCompare.DEFERRED_COMPLETED.get() == 1
                && M4PacketCompare.MISMATCHES.get() == 0,
                "sem=" + M4PacketCompare.MATCHES_SEMANTIC.get() + " exact=" + M4PacketCompare.GATE_A_EXACT.get());
        NativeChunkBridge.unload(0, 95, 6);
    }

    static void resetCounters() {
        M4PacketCompare.DEFERRED_ENQUEUED.set(0);
        M4PacketCompare.DEFERRED_COMPLETED.set(0);
        M4PacketCompare.DEFERRED_STALE.set(0);
        M4PacketCompare.DEFERRED_EXPIRED.set(0);
        M4PacketCompare.DEFERRED_CAPACITY_SKIPPED.set(0);
        M4PacketCompare.UNSYNCED_SKIPPED.set(0);
        M4PacketCompare.GATE_A_EXACT.set(0);
        M4PacketCompare.GATE_B_EQUAL.set(0);
        M4PacketCompare.COMPARE_DEFERRED.set(0);
        M4PacketCompare.COMPARE_IMMEDIATE.set(0);
        M4PacketCompare.MATCHES_SEMANTIC.set(0);
        M4PacketCompare.MISMATCHES.set(0);
        M4PacketCompare.COMPARED.set(0);
    }

    static long addr(java.nio.ByteBuffer b) throws Exception {
        Field f = java.nio.Buffer.class.getDeclaredField("address");
        f.setAccessible(true);
        return f.getLong(b);
    }
}
