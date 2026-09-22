package com.rustcraft.bridge;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.CharBuffer;

/**
 * M4.2D §2/§4 — systematic offline validity + lifecycle cases through the
 * ACTUAL entrypoints (ChunkMutationTracker hook methods = what the ASM
 * transformer injects; M4Coherency.refreshChunkNow = the consumer gate;
 * real Chunk mutations + real SPacketChunkData as oracle).
 *
 * Required cases: initial sync with non-default lights/biomes; TWO later
 * light-only changes on the same chunk; biome-only change with no section
 * dirtiness; empty->nonempty->empty cycle; mutation-after-refresh negative
 * control; unload/reload with coordinate reuse + stale-handle rejection;
 * skylight regeneration; in-JVM registry cleanup with retention proof.
 */
public class M4LifecycleCases {

    static int pass = 0, fail = 0;
    static Method M_SET_BIOMES_ARR;
    static Method M_GEN_SKY;

    public static void main(String[] args) throws Exception {
        Class.forName("net.minecraft.init.Bootstrap").getMethod("func_151354_b").invoke(null);
        M4PacketParityHarness.initReflectionPublic();
        Class<?> chunkCls = Class.forName("net.minecraft.world.chunk.Chunk");
        M_SET_BIOMES_ARR = chunkCls.getMethod("func_76616_a", byte[].class);
        M_SET_BIOMES_ARR.setAccessible(true);
        M_GEN_SKY = chunkCls.getMethod("func_76630_e");
        M_GEN_SKY.setAccessible(true);

        caseLightTwice();
        caseBiomeOnly();
        caseEmptyCycle();
        caseMutationBeforeAccept();
        caseSkylightRegen();
        caseUnloadReuseStaleHandle();
        caseInJvmCleanup();

        System.out.println("\nRESULT: " + pass + " pass, " + fail + " fail");
        System.exit(fail == 0 ? 0 : 1);
    }

    static void check(String name, boolean ok, String detail) {
        if (ok) { pass++; System.out.println(name + " -> PASS" + (detail == null ? "" : " (" + detail + ")")); }
        else { fail++; System.out.println(name + " -> FAIL " + detail); }
    }

    static Object lastPrimer;

    static Object newChunk(int cx, int cz) throws Exception {
        Object[] r = M4PacketParityHarness.baseChunkAndPrimer(cx, cz);
        lastPrimer = r[1];
        return r[0];
    }

    static byte[] javaPacketOf(Object chunk) throws Exception {
        Object pkt = M4PacketParityHarness.packetCtor().newInstance(chunk, 65535);
        return (byte[]) M4PacketParityHarness.pktPayloadField().get(pkt);
    }

    static int javaMaskOf(Object chunk) throws Exception {
        Object pkt = M4PacketParityHarness.packetCtor().newInstance(chunk, 65535);
        return M4PacketParityHarness.pktMaskField().getInt(pkt);
    }

    static byte[] nativePacket(int cx, int cz, long gen) throws Exception {
        ByteBuffer out = ByteBuffer.allocateDirect(262144).order(ByteOrder.nativeOrder());
        int n = NativeChunkBridge.encodePacket(0, cx, cz, gen, true, true, addr(out), 262144);
        if (n <= 0) return null;
        byte[] b = new byte[n];
        out.clear(); out.get(b);
        return b;
    }

    /** Gate B compare via the mask-driven parser. */
    static String gateB(byte[] jp, int jmask, byte[] np, long gen, int cx, int cz) throws Exception {
        int nmask = NativeChunkBridge.getPrimaryBitMask(0, cx, cz, gen);
        if (nmask != jmask) return "mask java=" + jmask + " native=" + nmask;
        M4PacketParityHarness.ParsedJ J = M4PacketParityHarness.parseByMask(jp, jmask, true);
        M4PacketParityHarness.ParsedJ N = M4PacketParityHarness.parseByMask(np, nmask, true);
        if (J == null || N == null) return "parse failure J=" + (J == null) + " N=" + (N == null);
        return M4PacketParityHarness.compareParsed(J, N);
    }

    static long addr(ByteBuffer b) throws Exception {
        Field f = java.nio.Buffer.class.getDeclaredField("address");
        f.setAccessible(true);
        return f.getLong(b);
    }

    static void caseLightTwice() throws Exception {
        int cx = 70, cz = 1;
        Object chunk = newChunk(cx, cz);
        long gen = NativeChunkBridge.register(0, cx, cz, addr((ByteBuffer) lastPrimer), 0L);
        int synced = M4Coherency.refreshChunkNow(chunk);          // first-touch full sync
        check("light2: first-touch sync", synced == 16, "sections=" + synced);
        String r0 = gateB(javaPacketOf(chunk), javaMaskOf(chunk), nativePacket(cx, cz, gen), gen, cx, cz);
        check("light2: first compare equal", r0 == null, r0);

        Object sky = enumSky("BLOCK");
        Object pos = blockPos(3, 70, 5);
        Method setLight = chunk.getClass().getMethod("func_177431_a",
                Class.forName("net.minecraft.world.EnumSkyBlock"), Class.forName("net.minecraft.util.math.BlockPos"), int.class);
        setLight.invoke(chunk, sky, pos, 11);                    // light change #1 (real method)
        ChunkMutationTracker.onLightSet(chunk, pos);             // actual hook entry
        int s1 = M4Coherency.refreshChunkNow(chunk);
        String r1 = gateB(javaPacketOf(chunk), javaMaskOf(chunk), nativePacket(cx, cz, gen), gen, cx, cz);
        check("light2: after change#1 equal", r1 == null, "r=" + r1 + " refreshed=" + s1);

        Object pos2 = blockPos(9, 66, 2);
        setLight.invoke(chunk, sky, pos2, 7);                    // light change #2 SAME chunk
        ChunkMutationTracker.onLightSet(chunk, pos2);
        int s2 = M4Coherency.refreshChunkNow(chunk);
        String r2 = gateB(javaPacketOf(chunk), javaMaskOf(chunk), nativePacket(cx, cz, gen), gen, cx, cz);
        check("light2: after change#2 equal", r2 == null, "r=" + r2 + " refreshed=" + s2);
        NativeChunkBridge.unload(0, cx, cz);
    }

    static void caseBiomeOnly() throws Exception {
        int cx = 71, cz = 2;
        Object chunk = newChunk(cx, cz);
        long gen = NativeChunkBridge.register(0, cx, cz, addr((ByteBuffer) lastPrimer), 0L);
        M4Coherency.refreshChunkNow(chunk);                       // first-touch (biomes real)
        byte[] nb = new byte[256];
        for (int i = 0; i < 256; i++) nb[i] = (byte) (20 + (i % 7));
        M_SET_BIOMES_ARR.invoke(chunk, nb);                       // real biome-array write
        ChunkMutationTracker.onBiomeChanged(chunk);               // actual hook entry (biome flag)
        int ref = M4Coherency.refreshChunkNow(chunk);             // dirty-only: sections untouched
        String r = gateB(javaPacketOf(chunk), javaMaskOf(chunk), nativePacket(cx, cz, gen), gen, cx, cz);
        check("biome-only: equal after push, 0 section refreshes", r == null && ref == 0, "r=" + r + " refreshed=" + ref);
        NativeChunkBridge.unload(0, cx, cz);
    }

    static void caseEmptyCycle() throws Exception {
        int cx = 72, cz = 3;
        Object chunk = newChunk(cx, cz);
        long gen = NativeChunkBridge.register(0, cx, cz, addr((ByteBuffer) lastPrimer), 0L);
        M4Coherency.refreshChunkNow(chunk);
        Method setBlock = chunk.getClass().getMethod("func_177436_a",
                Class.forName("net.minecraft.util.math.BlockPos"), Class.forName("net.minecraft.block.state.IBlockState"));
        Object stone = M4PacketParityHarness.registryById(M4PacketParityHarness.testGid(1)); // a real state
        Object pos = blockPos(8, 200, 8);                          // empty -> nonempty (section 12)

        setBlock.invoke(chunk, pos, stone);
        ChunkMutationTracker.onBlockSet(chunk, pos);
        M4Coherency.refreshChunkNow(chunk);
        String r1 = gateB(javaPacketOf(chunk), javaMaskOf(chunk), nativePacket(cx, cz, gen), gen, cx, cz);
        check("cycle: empty->nonempty equal", r1 == null, r1);

        setBlock.invoke(chunk, pos, M4PacketParityHarness.airStateRef());
        ChunkMutationTracker.onBlockSet(chunk, pos);               // nonempty -> empty again
        M4Coherency.refreshChunkNow(chunk);
        String r2 = gateB(javaPacketOf(chunk), javaMaskOf(chunk), nativePacket(cx, cz, gen), gen, cx, cz);
        check("cycle: nonempty->empty equal (mask drops)", r2 == null, r2);
        NativeChunkBridge.unload(0, cx, cz);
    }

    static void caseMutationBeforeAccept() throws Exception {
        int cx = 73, cz = 4;
        Object chunk = newChunk(cx, cz);
        long gen = NativeChunkBridge.register(0, cx, cz, addr((ByteBuffer) lastPrimer), 0L);
        M4Coherency.refreshChunkNow(chunk);                        // accepted state
        Method setBlock = chunk.getClass().getMethod("func_177436_a",
                Class.forName("net.minecraft.util.math.BlockPos"), Class.forName("net.minecraft.block.state.IBlockState"));
        Object pos = blockPos(2, 70, 9);
        setBlock.invoke(chunk, pos, M4PacketParityHarness.registryById(M4PacketParityHarness.testGid(1)));
        ChunkMutationTracker.onBlockSet(chunk, pos);               // mutation AFTER refresh
        // NO refreshChunkNow: consumer reads the stale native snapshot — the
        // comparator must DETECT it (negative control: stale data is caught).
        String stale = gateB(javaPacketOf(chunk), javaMaskOf(chunk), nativePacket(cx, cz, gen), gen, cx, cz);
        check("mutation-before-accept: stale DETECTED", stale != null, "r=" + stale);
        M4Coherency.refreshChunkNow(chunk);                        // proper gate before accepting
        String fresh = gateB(javaPacketOf(chunk), javaMaskOf(chunk), nativePacket(cx, cz, gen), gen, cx, cz);
        check("mutation-before-accept: equal after gate", fresh == null, fresh);
        NativeChunkBridge.unload(0, cx, cz);
    }

    static void caseSkylightRegen() throws Exception {
        int cx = 74, cz = 5;
        Object chunk = newChunk(cx, cz);
        long gen = NativeChunkBridge.register(0, cx, cz, addr((ByteBuffer) lastPrimer), 0L);
        M4Coherency.refreshChunkNow(chunk);
        // Direct skylight-array rewrite (the unobservable path in production):
        // simulate by zeroing sky light through raw storage, then the hook.
        Object[] storages = (Object[]) chunk.getClass().getMethod("func_76587_i").invoke(chunk);
        Object nib = storages[2].getClass().getMethod("func_76671_l").invoke(storages[2]);
        byte[] arr = (byte[]) nib.getClass().getMethod("func_177481_a").invoke(nib);
        java.util.Arrays.fill(arr, (byte) 0);                       // sky light wiped (like a regen)
        ChunkMutationTracker.onSkylightRegenerated(chunk);          // actual hook entry
        M4Coherency.refreshChunkNow(chunk);
        String r = gateB(javaPacketOf(chunk), javaMaskOf(chunk), nativePacket(cx, cz, gen), gen, cx, cz);
        check("skylight-regen: equal after hook+refresh", r == null, r);
        NativeChunkBridge.unload(0, cx, cz);
    }

    static void caseUnloadReuseStaleHandle() throws Exception {
        int cx = 75, cz = 6;
        Object chunk = newChunk(cx, cz);
        long gen1 = NativeChunkBridge.register(0, cx, cz, addr((ByteBuffer) lastPrimer), 0L);
        check("reuse: registered gen1", gen1 > 0, null);
        NativeChunkBridge.unload(0, cx, cz);
        long gen2 = NativeChunkBridge.register(0, cx, cz, addr((ByteBuffer) lastPrimer), 0L);
        check("reuse: re-registered new gen", gen2 > 0 && gen2 != gen1, "g1=" + gen1 + " g2=" + gen2);
        long found = NativeChunkBridge.findGeneration(0, cx, cz);
        check("reuse: findGeneration returns current", found == gen2, "found=" + found);
        int stale = NativeChunkBridge.encodePacket(0, cx, cz, gen1, true, true,
                addr(ByteBuffer.allocateDirect(4096).order(ByteOrder.nativeOrder())), 4096);
        check("reuse: stale handle rejected", stale < 0, "encode(gen1)=" + stale);
        NativeChunkBridge.unload(0, cx, cz);
    }

    static void caseInJvmCleanup() throws Exception {
        // register two live chunks + one tracked entry, then in-JVM cleanup
        Object c1 = newChunk(76, 7);
        Object c2 = newChunk(77, 8);
        NativeChunkBridge.register(0, 76, 7, addr((ByteBuffer) lastPrimer), 0L);
        NativeChunkBridge.register(0, 77, 8, addr((ByteBuffer) lastPrimer), 0L);
        ChunkMutationTracker.onBlockSet(c1, blockPos(1, 64, 1));    // pending work exists
        long[] before = stats();
        int removed = M4Coherency.shutdownCleanup();                // stop work + drain + clear
        long[] after = stats();
        check("cleanup: removed entries", removed >= 2, "removed=" + removed);
        check("cleanup: retained 0 chunks/bytes", after[0] == 0 && after[2] == 0,
                "chunks=" + after[0] + " bytes=" + after[2]);
        check("cleanup: releases >= allocations", after[4] >= after[3],
                "alloc=" + after[3] + " released=" + after[4]);
        int again = M4Coherency.shutdownCleanup();                  // repeat is safe
        long[] after2 = stats();
        check("cleanup: repeat safe", again == 0 && after2[0] == 0, "again=" + again + " chunks=" + after2[0]);
        long staleGen = NativeChunkBridge.findGeneration(0, 76, 7);
        check("cleanup: stale handles rejected after clear", staleGen == 0, "gen=" + staleGen);
    }

    static long[] stats() throws Exception {
        ByteBuffer bb = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder());
        NativeChunkBridge.getRegistryStats(addr(bb));
        return new long[] { bb.getLong(0), bb.getLong(8), bb.getLong(16), bb.getLong(24), bb.getLong(32), bb.getLong(40) };
    }

    static Object blockPos(int x, int y, int z) throws Exception {
        return Class.forName("net.minecraft.util.math.BlockPos")
                .getConstructor(int.class, int.class, int.class).newInstance(x, y, z);
    }

    static Object enumSky(String name) throws Exception {
        Class<?> c = Class.forName("net.minecraft.world.EnumSkyBlock");
        return Enum.valueOf((Class<? extends Enum>) c.asSubclass(Enum.class), name);
    }
}
