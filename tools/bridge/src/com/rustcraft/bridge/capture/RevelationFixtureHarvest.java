package com.rustcraft.bridge.capture;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;

/**
 * Cross-language fixture harvest for the RCSNAP02 transport, from a REAL
 * Revelation chunk inside the admitted real FML launch.
 *
 * <p>Runs on the canonical server thread (scheduled task, the same pattern
 * as the dimension-exclusion evidence): loads chunk (0,0) of the Overworld
 * through its normal provider, extracts the 4096 LOGICAL state ids per
 * section directly from the sealed runtime storage via the same per-cell
 * reader the qualified extractor uses, derives the source registry facts
 * (cardinality + required bits -- 157,010 / 18 bits measured on this
 * runtime), and writes:</p>
 *
 * <ul>
 *   <li>fixture-rcsnap02.bin -- the V2 transport of an OwnedPacketSnapshot
 *       built from those real cells (deterministic first-appearance
 *       palette, u16 logical domain)</li>
 *   <li>fixture-cells.txt -- the exact 4096-cell logical states per
 *       non-empty section, plus light planes and biomes, as the independent
 *       cross-language comparison reference</li>
 *   <li>fixture-facts.json -- registry cardinality/width, section count,
 *       palette cardinalities (source width is TELEMETRY: the fixture is
 *       eligible exactly because every real state fits u16)</li>
 * </ul>
 *
 * <p>Diagnostic-only: reads the live chunk ONCE on the server thread and
 * never writes game state. No shadow comparison, no Rust call, no packet
 * transmission.</p>
 */
public final class RevelationFixtureHarvest {

    public static void maybeSchedule() {
        final String dir = System.getProperty("rustcraft.harvestFixture");
        System.err.println("[RustCraft] fixture harvest requested: " + dir);
        if (dir == null || dir.length() == 0) return;
        Thread task = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    // Loading runtime classes from a foreign thread DURING
                    // tweak processing destabilizes startup (measured crash):
                    // wait out the boot first, then poll. Readiness is the
                    // Overworld's existence, structural, not a named gate.
                    Thread.sleep(300_000); // boot + Done settle (~2.5 min measured)
                    Class<?> dimManager = runtimeClass("net.minecraftforge.common.DimensionManager");
                    java.lang.reflect.Method getWorld = dimManager.getMethod("getWorld", int.class);
                    for (int i = 0; i < 80; i++) {
                        Object world = getWorld.invoke(null, 0);
                        System.err.println("[RustCraft] fixture harvest poll " + i
                                + ": overworld=" + (world != null));
                        if (world != null) {
                            Thread.sleep(10_000); // let "Done" work settle
                            break;
                        }
                        Thread.sleep(15_000);
                    }
                } catch (InterruptedException interrupted) {
                    return;
                } catch (Throwable unavailable) {
                    System.err.println("[RustCraft] fixture harvest readiness poll failed: "
                            + unavailable);
                    return;
                }
                System.err.println("[RustCraft] fixture harvest beginning");
                harvest(dir);
                System.err.println("[RustCraft] fixture harvest thread done");
            }
        }, "rustcraft-fixture-harvest");
        task.setDaemon(true);
        task.start();
    }

    /** Runtime classes live in the LaunchClassLoader; a parent-loaded
     *  lookup yields a DIFFERENT class with empty static state. */
    static Class<?> runtimeClass(String name) throws ClassNotFoundException {
        try {
            Object loader = Class.forName("net.minecraft.launchwrapper.Launch")
                    .getField("classLoader").get(null);
            return Class.forName(name, false, (ClassLoader) loader);
        } catch (Throwable notLaunch) {
            return Class.forName(name);
        }
    }

    static void harvest(String dir) {
        try {
            Object fmlHandler = runtimeClass("net.minecraftforge.fml.common.FMLCommonHandler")
                    .getMethod("instance").invoke(null);
            Object server = fmlHandler.getClass().getMethod("getMinecraftServerInstance")
                    .invoke(fmlHandler);
            Class<?> mcsClass = server.getClass();
            while (mcsClass != null && !mcsClass.getName().equals("net.minecraft.server.MinecraftServer")) {
                mcsClass = mcsClass.getSuperclass();
            }
            if (mcsClass == null) throw new IllegalStateException("MinecraftServer class not found");
            @SuppressWarnings("unchecked")
            Future<Object> future = (Future<Object>) mcsClass
                    .getMethod("func_175586_a", Callable.class).invoke(server, new Callable<Object>() {
                        @Override public Object call() throws Exception {
                            return harvestOnServerThread();
                        }
                    });
            Object[] result = (Object[]) future.get();
            int sections = (Integer) result[0];
            byte[] transport = (byte[]) result[1];
            String cells = (String) result[2];
            String facts = (String) result[3];
            byte[] javaPayload = (byte[]) result[4];
            Path out = Paths.get(dir).toAbsolutePath();
            Files.createDirectories(out);
            Files.write(out.resolve("fixture-rcsnap02.bin"), transport);
            Files.write(out.resolve("fixture-cells.txt"),
                    cells.getBytes(StandardCharsets.UTF_8));
            Files.write(out.resolve("fixture-facts.json"),
                    facts.getBytes(StandardCharsets.UTF_8));
            Files.write(out.resolve("fixture-java-packet.bin"), javaPayload);
            System.out.println("[RustCraft] fixture harvest complete: sections=" + sections
                    + " bytes=" + transport.length + " -> " + out);
        } catch (Throwable failure) {
            System.err.println("[RustCraft] fixture harvest failed: " + failure);
        }
    }

    /** Runs on the canonical server thread; returns {sections, v2, cells, facts}. */
    static Object[] harvestOnServerThread() throws Exception {
        Object fmlHandler = runtimeClass("net.minecraftforge.fml.common.FMLCommonHandler")
                .getMethod("instance").invoke(null);
        Object server = fmlHandler.getClass().getMethod("getMinecraftServerInstance")
                .invoke(fmlHandler);
        // Overworld world server via DimensionManager.getWorld(0)
        Class<?> dimManager = runtimeClass("net.minecraftforge.common.DimensionManager");
        Object world = dimManager.getMethod("getWorld", int.class).invoke(null, 0);
        if (world == null) {
            dimManager.getMethod("initDimension", int.class).invoke(null, 0);
            world = dimManager.getMethod("getWorld", int.class).invoke(null, 0);
        }
        if (world == null) throw new IllegalStateException("Overworld is null");
        Object chunk = null;
        for (Method m : world.getClass().getMethods()) {
            if (m.getName().equals("func_72964_e") && m.getParameterCount() == 2
                    && m.getParameterTypes()[0] == int.class) {
                chunk = m.invoke(world, 0, 0);
                break;
            }
        }
        if (chunk == null) throw new IllegalStateException("chunk (0,0) unavailable");

        // The block-state registry: ids for the real states.
        Class<?> block = runtimeClass("net.minecraft.block.Block");
        Field registryField = block.getDeclaredField("field_176229_d");
        registryField.setAccessible(true);
        Object registry = registryField.get(null);
        Class<?> mapType = runtimeClass("net.minecraft.util.ObjectIntIdentityMap");
        Method getId = null;
        Method size = null;
        for (Method m : mapType.getDeclaredMethods()) {
            if (m.getName().equals("func_148747_b") && m.getParameterCount() == 1
                    && m.getParameterTypes()[0] == Object.class) { getId = m; }
            if (m.getName().equals("func_186804_a") && m.getParameterCount() == 0) { size = m; }
        }
        if (getId == null || size == null)
            throw new IllegalStateException("registry accessors unavailable on " + mapType.getName());
        getId.setAccessible(true);
        size.setAccessible(true);
        int registrySize = (Integer) size.invoke(registry);
        int width = Math.max(9, 32 - Integer.numberOfLeadingZeros(registrySize - 1));

        Object[] storages = (Object[]) chunk.getClass()
                .getMethod("func_76587_i").invoke(chunk);
        Class<?> sectionType = runtimeClass("net.minecraft.world.chunk.storage.ExtendedBlockStorage");
        Method read = null;
        for (Method m : sectionType.getDeclaredMethods()) {
            if (m.getName().equals("func_177485_a") && m.getParameterCount() == 3) { read = m; break; }
        }
        if (read == null) throw new IllegalStateException("per-cell reader unavailable");
        read.setAccessible(true);

        byte[] biomes = (byte[]) chunk.getClass().getMethod("func_76605_m").invoke(chunk);

        OwnedPacketSnapshot.Section[] owned = new OwnedPacketSnapshot.Section[16];
        StringBuilder cells = new StringBuilder();
        java.util.List<Integer> cardinalities = new java.util.ArrayList<Integer>();
        int sectionCount = 0;
        for (int y = 0; y < 16; y++) {
            Object storage = storages[y];
            if (storage == null) continue;
            long[] states = new long[4096];
            java.util.TreeSet<Long> distinct = new java.util.TreeSet<Long>();
            int nonAir = 0;
            for (int i = 0; i < 4096; i++) {
                Object state = read.invoke(storage, i & 15, i >>> 8, (i >>> 4) & 15);
                int id = (Integer) getId.invoke(registry, state);
                states[i] = id;
                distinct.add((long) id);
                if (id != 0) nonAir++;
            }
            boolean empty = nonAir == 0;
            if (!empty) {
                sectionCount++;
                cardinalities.add(distinct.size());
                cells.append("section ").append(y).append('\n');
                for (int i = 0; i < 4096; i++) {
                    cells.append(states[i]);
                    cells.append(i == 4095 ? '\n' : ',');
                }
            }
            Field blockLightField = storage.getClass()
                    .getDeclaredField("field_76679_g");
            blockLightField.setAccessible(true);
            Object blockLightNibble = blockLightField.get(storage);
            byte[] blockLight = (byte[]) blockLightNibble.getClass()
                    .getMethod("func_177481_a").invoke(blockLightNibble);
            Field skyField = storage.getClass()
                    .getDeclaredField("field_76685_h");
            skyField.setAccessible(true);
            Object skyNibble = skyField.get(storage);
            byte[] skyLight = skyNibble == null ? null : (byte[]) skyNibble.getClass()
                    .getMethod("func_177481_a").invoke(skyNibble);
            owned[y] = new OwnedPacketSnapshot.Section(y, states, blockLight, skyLight,
                    null, empty, nonAir);
        }

        // The AUTHORITATIVE Java packet for the same chunk state: the real
        // SPacketChunkData constructor on the real chunk, read back through
        // its own serialized buffer fields. Its mask defines which sections
        // the fixture carries, so the Rust encode and the Java packet bytes
        // describe the identical section set.
        Class<?> packetType = runtimeClass("net.minecraft.network.play.server.SPacketChunkData");
        Object packet = packetType.getConstructor(runtimeClass("net.minecraft.world.chunk.Chunk"),
                int.class).newInstance(chunk, 0xFFFF);
        Field payloadField = packetType.getDeclaredField("field_186949_d");
        payloadField.setAccessible(true);
        byte[] javaPayload = (byte[]) payloadField.get(packet);
        Field maskField = packetType.getDeclaredField("field_186948_c");
        maskField.setAccessible(true);
        int javaMask = (Integer) maskField.get(packet);
        for (int y = 0; y < 16; y++) {
            if ((javaMask & (1 << y)) == 0) owned[y] = null;
        }

        // View + Context for the OwnedPacketSnapshot: the registry facts are
        // the REAL runtime's; the epoch/incarnation guards are this harvest's
        // own stable read (single-threaded on the server thread).
        CaptureSource.Section[] viewSections = new CaptureSource.Section[16];
        for (int y = 0; y < 16; y++) {
            if (owned[y] == null) continue;
            viewSections[y] = new CaptureSource.Section(
                    storages[y], owned[y].logicalStates(), owned[y].blockLight(),
                    owned[y].skyLight(), null, owned[y].empty, owned[y].blockRefCount);
        }
        CaptureSource.View view = new CaptureSource.View(chunk, storages, 0, 0, 0,
                1L, 1L, 1L, javaMask, true, true,
                CaptureContract.StorageModel.VANILLA_U16, width, registrySize,
                "rcsnap02-cross-language-fixture/real-revelation-chunk",
                viewSections, biomes);
        java.util.EnumMap<CaptureContract.Domain, CaptureContract.WriterClass> writers =
                new java.util.EnumMap<CaptureContract.Domain, CaptureContract.WriterClass>(
                        CaptureContract.Domain.class);
        CaptureContract.Context context = new CaptureContract.Context(
                Thread.currentThread(), 1L, 1L, "RCSNAP02_FIXTURE_HARVEST",
                "RCSNAP02_FIXTURE_HARVEST", true, writers, null);
        OwnedPacketSnapshot snapshot = new OwnedPacketSnapshot(view, view, context,
                owned, biomes, javaMask, true, CaptureContract.Scope.SYNTHETIC_OFFLINE);
        byte[] v2 = snapshot.toTransportBytesV2();

        StringBuilder facts = new StringBuilder("{");
        facts.append("\"registry_size\":").append(registrySize);
        facts.append(",\"source_required_bits\":").append(width);
        facts.append(",\"sections\":").append(sectionCount);
        facts.append(",\"palette_cardinalities\":").append(cardinalities);
        facts.append(",\"max_state\":").append(maxState(owned));
        facts.append(",\"eligible_u16\":").append(maxState(owned) <= 0xFFFF);
        facts.append(",\"v2_bytes\":").append(v2.length);
        facts.append(",\"java_mask\":").append(javaMask);
        facts.append(",\"java_payload_len\":").append(javaPayload.length);
        facts.append("}");
        return new Object[] { sectionCount, v2, cells.toString(), facts.toString(), javaPayload };
    }

    static long maxState(OwnedPacketSnapshot.Section[] sections) {
        long max = 0;
        for (OwnedPacketSnapshot.Section section : sections) {
            if (section == null) continue;
            for (long state : section.logicalStates()) if (state > max) max = state;
        }
        return max;
    }

    private RevelationFixtureHarvest() { }
}
