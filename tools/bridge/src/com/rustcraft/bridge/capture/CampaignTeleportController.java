package com.rustcraft.bridge.capture;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Server-side campaign teleport controller for live-shadow workload coverage.
 *
 * <p>Explicitly test-only infrastructure, strictly default OFF. Enabled ONLY
 * when {@code -Drustcraft.closureCampaignTeleport=true}. Normal server runs
 * experience zero effect.</p>
 *
 * <p>Does NOT mutate chunk contents, snapshot capture, comparator behavior, or
 * production authority. It only schedules canonical player movements on the
 * server main thread via {@code EntityPlayerMP.setPositionAndUpdate}
 * (SRG {@code func_70634_a}) so the normal Minecraft chunk provider naturally
 * loads, transmits, unloads, and reloads chunks across distant coordinates.</p>
 */
public final class CampaignTeleportController {

    public static final class Waypoint {
        public final double x;
        public final double y;
        public final double z;

        public Waypoint(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        @Override
        public String toString() {
            return String.format("(%.1f, %.1f, %.1f)", x, y, z);
        }
    }

    public static boolean isEnabled() {
        return Boolean.getBoolean("rustcraft.closureCampaignTeleport")
                || (Boolean.getBoolean("rustcraft.closureCampaign")
                    && "true".equalsIgnoreCase(System.getProperty("rustcraft.closureCampaignTeleport")));
    }

    public static void maybeSchedule() {
        if (!isEnabled()) {
            return;
        }
        System.out.println("[teleport-controller] CampaignTeleportController ENABLED");
        Thread task = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    runController();
                } catch (Throwable t) {
                    System.err.println("[teleport-controller] Controller thread failed: " + t);
                    t.printStackTrace(System.err);
                }
            }
        }, "rustcraft-campaign-teleport");
        task.setDaemon(true);
        task.start();
    }

    static Class<?> runtimeClass(String name) throws ClassNotFoundException {
        try {
            Object loader = Class.forName("net.minecraft.launchwrapper.Launch")
                    .getField("classLoader").get(null);
            return Class.forName(name, false, (ClassLoader) loader);
        } catch (Throwable notLaunch) {
            return Class.forName(name);
        }
    }

    static Method findMethod(Class<?> clazz, String srgName, String mcpName, Class<?>... paramTypes) {
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            for (String name : new String[] { srgName, mcpName }) {
                try {
                    Method m = c.getDeclaredMethod(name, paramTypes);
                    m.setAccessible(true);
                    return m;
                } catch (NoSuchMethodException ignored) {}
            }
        }
        for (String name : new String[] { srgName, mcpName }) {
            try {
                Method m = clazz.getMethod(name, paramTypes);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {}
        }
        throw new NoSuchMethodError("Cannot find method " + srgName + " / " + mcpName + " on " + clazz);
    }

    static Field findField(Class<?> clazz, String srgName, String mcpName) {
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            for (String name : new String[] { srgName, mcpName }) {
                try {
                    Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    return f;
                } catch (NoSuchFieldException ignored) {}
            }
        }
        for (String name : new String[] { srgName, mcpName }) {
            try {
                Field f = clazz.getField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {}
        }
        throw new NoSuchFieldError("Cannot find field " + srgName + " / " + mcpName + " on " + clazz);
    }

    private static void runController() throws Exception {
        long bootDelayMs = Long.getLong("rustcraft.closureCampaignTeleportBootDelayMs", 60000L);
        System.out.println("[teleport-controller] Waiting for server boot (initial delay " + bootDelayMs + "ms)...");
        try {
            Thread.sleep(bootDelayMs);
        } catch (InterruptedException ie) {
            return;
        }
        System.out.println("[teleport-controller] Initial boot delay elapsed; looking for MinecraftServer...");
        Object server = null;
        for (int i = 0; i < 120; i++) {
            try {
                Object fmlHandler = runtimeClass("net.minecraftforge.fml.common.FMLCommonHandler")
                        .getMethod("instance").invoke(null);
                server = fmlHandler.getClass().getMethod("getMinecraftServerInstance").invoke(fmlHandler);
                if (server != null) {
                    Class<?> dimManager = runtimeClass("net.minecraftforge.common.DimensionManager");
                    Method getWorld = dimManager.getMethod("getWorld", int.class);
                    Object world = getWorld.invoke(null, 0);
                    if (world != null) {
                        break;
                    }
                }
            } catch (Throwable ignored) {}
            Thread.sleep(3000);
        }
        if (server == null) {
            System.err.println("[teleport-controller] Server never became available; aborting.");
            return;
        }

        List<Waypoint> waypoints = loadWaypoints();
        long intervalMs = Long.getLong("rustcraft.closureCampaignTeleportIntervalMs", 8000L);
        long initialDelayMs = Long.getLong("rustcraft.closureCampaignTeleportInitialDelayMs", 10000L);
        String statusPath = System.getProperty("rustcraft.closureCampaignTeleportStatus");

        System.out.println("[teleport-controller] Server ready. Waiting for player to connect...");
        Object player = null;
        for (int i = 0; i < 180; i++) {
            player = getFirstPlayer(server);
            if (player != null) {
                break;
            }
            Thread.sleep(2000);
        }
        if (player == null) {
            System.err.println("[teleport-controller] No player connected after wait; aborting.");
            return;
        }

        System.out.println("[teleport-controller] Player connected. Initial settle delay " + initialDelayMs + "ms...");
        Thread.sleep(initialDelayMs);

        // Ensure player capabilities: flying & invulnerable for test movement
        configurePlayerSafety(server);

        int totalLegs = waypoints.size();
        int legIndex = 0;
        for (Waypoint wp : waypoints) {
            legIndex++;
            System.out.println(String.format("[teleport-controller] [%d/%d] Moving player to %s...",
                    legIndex, totalLegs, wp));
            boolean ok = executeTeleport(server, wp);
            if (!ok) {
                System.err.println("[teleport-controller] Failed to move player at leg " + legIndex);
            }
            // Settle window for chunk load, SPacketChunkData send/compare, and prior chunk unload
            Thread.sleep(intervalMs);
        }

        System.out.println("[teleport-controller] All " + totalLegs + " waypoints completed!");
        if (statusPath != null && statusPath.length() > 0) {
            Path p = Paths.get(statusPath).toAbsolutePath();
            if (p.getParent() != null) {
                Files.createDirectories(p.getParent());
            }
            String statusJson = String.format("{\"status\":\"COMPLETE\",\"legs\":%d,\"timestamp\":%d}\n",
                    totalLegs, System.currentTimeMillis());
            Files.write(p, statusJson.getBytes(StandardCharsets.UTF_8));
            System.out.println("[teleport-controller] Status receipt written to " + p);
        }
    }

    private static Object getFirstPlayer(Object server) throws Exception {
        Class<?> mcsClass = server.getClass();
        while (mcsClass != null && !mcsClass.getName().equals("net.minecraft.server.MinecraftServer")) {
            mcsClass = mcsClass.getSuperclass();
        }
        if (mcsClass == null) return null;
        Method getPlayerList = findMethod(mcsClass, "func_184103_al", "getPlayerList");
        Object playerList = getPlayerList.invoke(server);
        if (playerList == null) return null;
        Method getPlayers = findMethod(playerList.getClass(), "func_181057_v", "getPlayers");
        @SuppressWarnings("unchecked")
        List<Object> list = (List<Object>) getPlayers.invoke(playerList);
        if (list == null || list.isEmpty()) return null;
        return list.get(0);
    }

    private static void configurePlayerSafety(final Object server) throws Exception {
        Class<?> mcsClass = server.getClass();
        while (mcsClass != null && !mcsClass.getName().equals("net.minecraft.server.MinecraftServer")) {
            mcsClass = mcsClass.getSuperclass();
        }
        Method addScheduledTask = findMethod(mcsClass, "func_152344_a", "addScheduledTask", Runnable.class);
        final Object lock = new Object();
        final boolean[] done = new boolean[1];

        addScheduledTask.invoke(server, new Runnable() {
            @Override
            public void run() {
                try {
                    Object player = getFirstPlayer(server);
                    if (player != null) {
                        Field capField = findField(player.getClass(), "field_71075_bZ", "capabilities");
                        Object caps = capField.get(player);
                        if (caps != null) {
                            Field allowFlying = findField(caps.getClass(), "field_75101_c", "allowFlying");
                            Field isFlying = findField(caps.getClass(), "field_75100_b", "isFlying");
                            Field disableDamage = findField(caps.getClass(), "field_75102_a", "disableDamage");
                            allowFlying.setBoolean(caps, true);
                            isFlying.setBoolean(caps, true);
                            disableDamage.setBoolean(caps, true);
                            try {
                                Method sendCaps = findMethod(player.getClass(), "func_71016_p", "sendPlayerAbilities");
                                sendCaps.invoke(player);
                            } catch (Throwable ignored) {}
                            System.out.println("[teleport-controller] Configured test player safety capabilities (flying/invulnerable)");
                        }
                    }
                } catch (Throwable t) {
                    System.err.println("[teleport-controller] Note: could not set player safety caps: " + t);
                } finally {
                    synchronized (lock) {
                        done[0] = true;
                        lock.notifyAll();
                    }
                }
            }
        });

        synchronized (lock) {
            while (!done[0]) {
                lock.wait(1000);
            }
        }
    }

    private static boolean executeTeleport(final Object server, final Waypoint target) throws Exception {
        Class<?> mcsClass = server.getClass();
        while (mcsClass != null && !mcsClass.getName().equals("net.minecraft.server.MinecraftServer")) {
            mcsClass = mcsClass.getSuperclass();
        }
        if (mcsClass == null) {
            throw new IllegalStateException("MinecraftServer class not found in hierarchy");
        }
        Method addScheduledTask = findMethod(mcsClass, "func_152344_a", "addScheduledTask", Runnable.class);
        final Class<?> finalMcsClass = mcsClass;
        final boolean[] success = new boolean[1];
        final Exception[] err = new Exception[1];
        final Object lock = new Object();
        final boolean[] done = new boolean[1];

        addScheduledTask.invoke(server, new Runnable() {
            @Override
            public void run() {
                try {
                    Object player = getFirstPlayer(server);
                    if (player == null) {
                        System.err.println("[teleport-controller] No player available for teleport on server thread!");
                        return;
                    }
                    Method setPos = findMethod(player.getClass(), "func_70634_a", "setPositionAndUpdate",
                            double.class, double.class, double.class);
                    setPos.invoke(player, target.x, target.y, target.z);

                    // Update PlayerChunkMap so server loads destination chunks and unloads origin chunks
                    try {
                        Method getPlayerList = findMethod(finalMcsClass, "func_184103_al", "getPlayerList");
                        Object playerList = getPlayerList.invoke(server);
                        if (playerList != null) {
                            Class<?> epClass = runtimeClass("net.minecraft.entity.player.EntityPlayerMP");
                            Method updatePlayer = findMethod(playerList.getClass(), "func_72358_d",
                                    "serverUpdateMovingPlayer", epClass);
                            updatePlayer.invoke(playerList, player);
                        }
                    } catch (Throwable t) {
                        System.err.println("[teleport-controller] Note: could not update moving player: " + t);
                    }
                    success[0] = true;
                } catch (Exception ex) {
                    err[0] = ex;
                } finally {
                    synchronized (lock) {
                        done[0] = true;
                        lock.notifyAll();
                    }
                }
            }
        });

        synchronized (lock) {
            while (!done[0]) {
                lock.wait(1000);
            }
        }
        if (err[0] != null) {
            throw err[0];
        }
        return success[0];
    }

    public static List<Waypoint> loadWaypoints() {
        List<Waypoint> list = new ArrayList<Waypoint>();
        String pointsProp = System.getProperty("rustcraft.closureCampaignTeleportPoints");
        if (pointsProp != null && pointsProp.trim().length() > 0) {
            for (String part : pointsProp.split(";")) {
                part = part.trim();
                if (part.isEmpty()) continue;
                String[] coords = part.split(",");
                if (coords.length >= 3) {
                    double x = Double.parseDouble(coords[0].trim());
                    double y = Double.parseDouble(coords[1].trim());
                    double z = Double.parseDouble(coords[2].trim());
                    list.add(new Waypoint(x, y, z));
                }
            }
            if (!list.isEmpty()) {
                System.out.println("[teleport-controller] Loaded " + list.size() + " waypoints from property");
                return list;
            }
        }

        // Default deterministic generator along the pre-generated world corridor:
        // Base coordinate is (1024.5, 80.0, 64.5) — outside spawn chunks, pure disk I/O
        long seed = Long.getLong("rustcraft.closureCampaignSeed", 20260929L);
        int rounds = Integer.getInteger("rustcraft.closureCampaignTeleportRounds", 3);
        double baseX = 1024.5;
        double baseY = 80.0;
        double baseZ = 64.5;

        list.add(new Waypoint(baseX, baseY, baseZ));
        for (int r = 0; r < rounds; r++) {
            for (int step = 0; step < 8; step++) {
                // Distant waypoint along pre-generated X corridor (distance >= 2048 to guarantee full chunk set divergence)
                double distance = 2048.0 + 1024.0 * ((step + (int)(seed % 7)) % 6);
                double targetX = baseX + distance;
                list.add(new Waypoint(targetX, baseY, baseZ));
                // Return to base: causes base chunks to genuinely unload while away,
                // then reload from disk under a fresh incarnation when returning!
                list.add(new Waypoint(baseX, baseY, baseZ));
            }
        }
        System.out.println("[teleport-controller] Generated " + list.size() + " deterministic corridor waypoints (rounds=" + rounds + ")");
        return list;
    }
}
