package com.rustcraft.bridge;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicLong;

/**
 * M5.8 — TEST-ONLY explicit full-chunk resend driver.
 *
 * Verifies server-owner execution, picks a ready chunk near an existing
 * watching player, constructs the REAL SPacketChunkData through the ordinary
 * constructor (transformer hooks + comparator fire normally), and sends it via
 * the player's ordinary connection path. No fabricated payloads, no
 * view-distance toggling, no exploratory teleports.
 *
 * Counters: ACCEPTED (request accepted), EXECUTED (server task ran),
 * CTOR_ENTERED/RETURNED (via the comparator's entry/return counters), SENT
 * (sendPacket invoked). Repeated requests must demonstrably reach the
 * constructor repeatedly.
 *
 * Offline: only the construction chain is testable (no server context);
 * everything before addScheduledTask throws harmlessly and is counted.
 */
public final class M5ResendDriver {

    public static final AtomicLong ACCEPTED = new AtomicLong();
    public static final AtomicLong EXECUTED = new AtomicLong();
    public static final AtomicLong SENT = new AtomicLong();
    public static final AtomicLong REJECTED_NO_CONTEXT = new AtomicLong();
    public static volatile boolean CANARY_FIRED = false;

    private M5ResendDriver() {}

    /** Request one explicit full-chunk resend per watching player. */
    public static void requestResends(int howMany) {
        try {
            Object fml = Class.forName("net.minecraftforge.fml.common.FMLCommonHandler")
                    .getMethod("instance").invoke(null);
            Object server = fml.getClass().getMethod("getMinecraftServerInstance").invoke(fml);
            if (server == null) {
                REJECTED_NO_CONTEXT.incrementAndGet();
                return;
            }
            ACCEPTED.addAndGet(howMany);
            Method callLater = server.getClass().getMethod("func_152344_a", Runnable.class);
            for (int i = 0; i < howMany; i++) {
                callLater.invoke(server, (Runnable) () -> {
                    try {
                        EXECUTED.incrementAndGet();
                        Object world = server.getClass().getMethod("func_71218_a", int.class)
                                .invoke(server, 0);
                        java.util.List<?> players = (java.util.List<?>) world.getClass()
                                .getField("field_73010_i").get(world); // playerEntities (M5.8-R2 verified name)
                        if (players == null || players.isEmpty()) {
                            REJECTED_NO_CONTEXT.incrementAndGet();
                            return;
                        }
                        Object player = players.iterator().next();
                        int px = (int) Math.floor((double) player.getClass().getMethod("func_174813_aO")
                                .invoke(player).getClass().getMethod("func_177958_n").invoke(
                                        player.getClass().getMethod("func_174813_aO").invoke(player)));
                        int pz = (int) Math.floor((double) player.getClass().getMethod("func_174813_aO")
                                .invoke(player).getClass().getMethod("func_177602_b").invoke(
                                        player.getClass().getMethod("func_174813_aO").invoke(player)));
                        Object chunk = world.getClass()
                                .getMethod("func_72799_s", int.class, int.class, boolean.class)
                                .invoke(world, px >> 4, pz >> 4, true); // getChunk loaded-or-load
                        if (chunk == null) {
                            REJECTED_NO_CONTEXT.incrementAndGet();
                            return;
                        }
                        // ORDINARY construction + send
                        Object pkt = Class.forName("net.minecraft.network.play.server.SPacketChunkData")
                                .getConstructor(Class.forName("net.minecraft.world.chunk.Chunk"), int.class)
                                .newInstance(chunk, 65535);
                        Object conn = player.getClass().getMethod("func_147112_ai").invoke(player); // getConnection
                        conn.getClass().getMethod("func_147297_a",
                                Class.forName("net.minecraft.network.Packet")).invoke(conn, pkt);
                        SENT.incrementAndGet();
                    } catch (Throwable t) {
                        WorldgenShadow.recordCoherencyError("resendDriver: " + t, t);
                    }
                });
            }
        } catch (Throwable t) {
            REJECTED_NO_CONTEXT.incrementAndGet(); // offline / no FML
        }
    }

    /** Offline-verifiable chain: real ctor + hooks + comparator (no send). */
    public static int testConstructChain(Object chunk) {
        try {
            Object pkt = M4PacketParityHarness.packetCtor().newInstance(chunk, 65535);
            NativeChunkPacket.populatePacket(pkt, chunk, 65535);
            M4PacketCompare.onPacketBuilt(pkt, chunk, 65535);
            return 1;
        } catch (Throwable t) {
            return -1;
        }
    }
}
