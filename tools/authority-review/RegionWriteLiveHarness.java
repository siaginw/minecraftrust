import java.io.*;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;
import java.util.zip.Deflater;

/**
 * RUST_REGION_WRITE_AUTHORITY offline LIVE harness (no server, real JVM).
 *
 * Loads the TRANSFORMED RegionFile (RegionFileAuthorityTransformer applied to
 * the SRG-jar bytes, defined child-first) and drives the real synchronized
 * seam func_76706_a(II[BI)V with synthetic deflate payloads, so the full
 * chain runs end to end: injected entry hook -> RustRegionWriteHook ->
 * RegionWriteCtx JNI -> Rust LiveRegionFile engine -> file -> (ON mode)
 * in-session read coherence -> fresh vanilla RegionFile re-read.
 *
 * Modes:
 *   SHADOW          -Dprop set by runner; Rust writes the mirror, vanilla
 *                     writes the real file; harness compares both.
 *   ON_EXPERIMENTAL - Rust writes the real file and the vanilla body is
 *                     skipped; harness reads back IN-SESSION through the
 *                     transformed RegionFile (coherence) and after close()
 *                     through a FRESH VANILLA class (format proof).
 */
public class RegionWriteLiveHarness {

    static final String RF = "net.minecraft.world.chunk.storage.RegionFile";

    /** Child-first for the transformed RegionFile only. */
    static final class TransformedRegionLoader extends URLClassLoader {
        private final byte[] regionFileBytes;
        TransformedRegionLoader(byte[] rfBytes, URL[] urls, ClassLoader parent) {
            super(urls, parent);
            this.regionFileBytes = rfBytes;
        }
        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (RF.equals(name)) {
                synchronized (getClassLoadingLock(name)) {
                    Class<?> c = findLoadedClass(name);
                    if (c == null) {
                        c = defineClass(name, regionFileBytes, 0, regionFileBytes.length);
                    }
                    if (resolve) resolveClass(c);
                    return c;
                }
            }
            return super.loadClass(name, resolve);
        }
    }

    static byte[] readRegionFileBytes(String srgJar) throws IOException {
        try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(srgJar)) {
            java.util.zip.ZipEntry e = z.getEntry(RF.replace('.', '/') + ".class");
            if (e == null) throw new IOException("RegionFile.class not in srg jar");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            try (InputStream in = z.getInputStream(e)) {
                int r;
                while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
            }
            return out.toByteArray();
        }
    }

    static byte[] deflate(byte[] raw) throws IOException {
        Deflater def = new Deflater();
        try {
            def.setInput(raw);
            def.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] scratch = new byte[8192];
            while (!def.finished()) out.write(scratch, 0, def.deflate(scratch));
            return out.toByteArray();
        } finally {
            def.end();
        }
    }

    /** Drains func_76704_a's stream — it is ALREADY a decompressing
     *  DataInputStream (InflaterInputStream inside), so no second inflate. */
    static byte[] drain(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int r;
        while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
        return out.toByteArray();
    }

    /** Deterministic pseudo-random payload. */
    static byte[] payload(long seed, int len) {
        byte[] out = new byte[len];
        long x = seed * 6364136223846793005L + 1442695040888963407L;
        for (int i = 0; i < len; i++) {
            x ^= x << 13; x ^= x >>> 7; x ^= x << 17;
            out[i] = (byte) x;
        }
        return out;
    }

    public static void main(String[] args) throws Exception {
        String mode = args[0];                 // SHADOW | ON_EXPERIMENTAL
        String srgJar = args[1];
        String srcRegion = args[2];            // real .mca to copy
        String workDir = args[3];
        String mirrorDir = args[4];
        int count = Integer.parseInt(args[5]);

        if (!Boolean.getBoolean("rustcraft.regionWriteExperiment")) {
            throw new IllegalStateException("harness must run with -Drustcraft.regionWriteExperiment=true");
        }
        if (!com.rustcraft.bridge.RegionWriteCtx.LOADED) {
            throw new IllegalStateException("rustcraft_ffi native library did not load");
        }

        Files.createDirectories(Paths.get(workDir));
        Path work = Paths.get(workDir, "r.0.0.mca");
        Files.copy(Paths.get(srcRegion), work, StandardCopyOption.REPLACE_EXISTING);
        byte[] original = Files.readAllBytes(work);

        // 1. transform the SRG bytes (assert real modification happened)
        byte[] vanilla = readRegionFileBytes(srgJar);
        com.rustcraft.coremod.RegionFileAuthorityTransformer tx =
                new com.rustcraft.coremod.RegionFileAuthorityTransformer();
        byte[] transformed = tx.transform(RF.replace('.', '/'),
                RF, vanilla);
        if (transformed == vanilla || transformed == null) {
            throw new IllegalStateException("transformer did not modify RegionFile: "
                    + com.rustcraft.coremod.RegionFileAuthorityTransformer.lastTransformStatus);
        }
        System.out.println("[harness] transform status="
                + com.rustcraft.coremod.RegionFileAuthorityTransformer.lastTransformStatus
                + " sizeDelta=" + (transformed.length - vanilla.length));

        // 2. load transformed RegionFile child-first
        URL[] urls = { new File(srgJar).toURI().toURL() };
        TransformedRegionLoader loader = new TransformedRegionLoader(transformed, urls,
                RegionWriteLiveHarness.class.getClassLoader());
        Class<?> rfClass = loader.loadClass(RF);

        Constructor<?> ctor = rfClass.getConstructor(File.class);
        Method writeSeam = rfClass.getDeclaredMethod("func_76706_a",
                int.class, int.class, byte[].class, int.class);
        writeSeam.setAccessible(true);
        Method readOpen = rfClass.getDeclaredMethod("func_76704_a", int.class, int.class);
        readOpen.setAccessible(true);
        Method hasChunk = rfClass.getDeclaredMethod("func_76709_c", int.class, int.class);
        hasChunk.setAccessible(true);
        Method closeM = rfClass.getDeclaredMethod("func_76708_c");

        Object region = ctor.newInstance(work.toFile());

        // pick `count` existing chunk slots (deterministic: diagonal)
        List<int[]> slots = new ArrayList<>();
        outer:
        for (int z = 0; z < 32; z++) {
            for (int x = 0; x < 32; x++) {
                if ((Boolean) hasChunk.invoke(region, x, z)) slots.add(new int[]{x, z});
                if (slots.size() >= count) break outer;
            }
        }
        if (slots.size() < count) {
            throw new IllegalStateException("source region has only " + slots.size()
                    + " chunks, need " + count);
        }

        // 3. drive the real seam with synthetic deflate payloads
        Map<Long, byte[]> expected = new LinkedHashMap<>();
        int sizes[] = {100, 900, 4096, 20000, 60000};
        java.lang.reflect.Field offField = rfClass.getDeclaredField("field_76716_d");
        offField.setAccessible(true);
        for (int i = 0; i < count; i++) {
            int[] slot = slots.get(i);
            byte[] raw = payload(i + 1, sizes[i % sizes.length]);
            byte[] zraw = deflate(raw);
            expected.put(key(slot[0], slot[1]), raw);
            writeSeam.invoke(region, slot[0], slot[1], zraw, zraw.length);
            int index = slot[1] * 32 + slot[0];
            int[] offs = (int[]) offField.get(region);
            System.out.println("[harness] write i=" + i + " chunk=" + slot[0] + "," + slot[1]
                    + " index=" + index + " rawLen=" + raw.length + " offsetsArr=" + offs[index]);
        }
        System.out.println("[harness] wrote " + count + " chunks through the seam; hook: "
                + com.rustcraft.bridge.RustRegionWriteHook.dumpMetrics());

        if ("SHADOW".equals(mode)) {
            // 4a. real file must still be vanilla-written (per-chunk payload
            //     equals what we wrote); mirror file must hold the SAME payloads.
            Object vRegion = ctor.newInstance(work.toFile());
            for (Map.Entry<Long, byte[]> e : expected.entrySet()) {
                int[] slot = unpack(e.getKey());
                DataInputStream in = (DataInputStream) readOpen.invoke(vRegion, slot[0], slot[1]);
                byte[] got = drain(in);
                in.close();
                if (!Arrays.equals(got, e.getValue())) {
                    throw new IllegalStateException("SHADOW real-file mismatch at "
                            + slot[0] + "," + slot[1]);
                }
            }
            closeM.invoke(vRegion);

            String mirrorPath = com.rustcraft.bridge.RustRegionWriteHook.mirrorPath(
                    work.toFile().getAbsolutePath());
            File mirrorFile = new File(mirrorPath);
            if (!mirrorFile.isFile()) {
                throw new IllegalStateException("SHADOW mirror missing: " + mirrorPath);
            }
            Object mRegion = ctor.newInstance(mirrorFile);
            for (Map.Entry<Long, byte[]> e : expected.entrySet()) {
                int[] slot = unpack(e.getKey());
                DataInputStream in = (DataInputStream) readOpen.invoke(mRegion, slot[0], slot[1]);
                byte[] got = drain(in);
                in.close();
                if (!Arrays.equals(got, e.getValue())) {
                    throw new IllegalStateException("SHADOW mirror mismatch at "
                            + slot[0] + "," + slot[1]);
                }
            }
            closeM.invoke(mRegion);
            System.out.println("[harness] SHADOW verified: real(vanilla) + mirror(Rust) "
                    + "hold identical payloads for " + count + " chunks");
        } else if ("ON_EXPERIMENTAL".equals(mode)) {
            // 4b. IN-SESSION read through the TRANSFORMED region (its in-memory
            //     offsets were mirrored by the hook) must return our payloads.
            //     The engine stays LIVE through the rewrite phase below.
            int[] offsNow = (int[]) offField.get(region);
            for (Map.Entry<Long, byte[]> e : expected.entrySet()) {
                int[] slot = unpack(e.getKey());
                int idx = slot[1] * 32 + slot[0];
                DataInputStream in = (DataInputStream) readOpen.invoke(region, slot[0], slot[1]);
                if (in == null) {
                    throw new IllegalStateException("ON in-session NULL read at "
                            + slot[0] + "," + slot[1] + " offsetsArr=" + offsNow[idx]);
                }
                byte[] got = drain(in);
                in.close();
                if (!Arrays.equals(got, e.getValue())) {
                    throw new IllegalStateException("ON in-session mismatch at "
                            + slot[0] + "," + slot[1] + " gotLen=" + got.length
                            + " wantLen=" + e.getValue().length + " offsetsArr=" + offsNow[idx]);
                }
            }
            System.out.println("[harness] ON_EXPERIMENTAL in-session read verified ("
                    + count + " chunks; Rust-committed offsets coherent)");

            // 5. FRESH VANILLA re-read: plain parent-loader class on the same
            //    file — proves the on-disk format is exactly vanilla's.
            Class<?> vClass = Class.forName(RF); // parent loader: vanilla bytes
            Method vRead = vClass.getDeclaredMethod("func_76704_a", int.class, int.class);
            vRead.setAccessible(true);
            Method vClose = vClass.getDeclaredMethod("func_76708_c");
            Object vRegion = vClass.getConstructor(File.class).newInstance(work.toFile());
            for (Map.Entry<Long, byte[]> e : expected.entrySet()) {
                int[] slot = unpack(e.getKey());
                DataInputStream in = (DataInputStream) vRead.invoke(vRegion, slot[0], slot[1]);
                byte[] got = drain(in);
                in.close();
                if (!Arrays.equals(got, e.getValue())) {
                    throw new IllegalStateException("ON fresh-vanilla mismatch at "
                            + slot[0] + "," + slot[1]);
                }
            }
            vClose.invoke(vRegion);
            System.out.println("[harness] ON_EXPERIMENTAL fresh vanilla re-read verified");
        } else {
            throw new IllegalStateException("unknown mode " + mode);
        }

        // 6. rewrite a subset a second time (generation tickets + in-place or
        //    relocation paths), then re-verify everything still reads back.
        int rewrites = Math.min(3, count);
        for (int i = 0; i < rewrites; i++) {
            int[] slot = slots.get(i);
            byte[] raw = payload(1000 + i, 7000);
            byte[] zraw = deflate(raw);
            expected.put(key(slot[0], slot[1]), raw);
            writeSeam.invoke(region, slot[0], slot[1], zraw, zraw.length);
        }
        if ("SHADOW".equals(mode)) {
            // verify mirror file carries the rewrites
            String mirrorPath = com.rustcraft.bridge.RustRegionWriteHook.mirrorPath(
                    work.toFile().getAbsolutePath());
            Object mRegion = ctor.newInstance(new File(mirrorPath));
            for (int i = 0; i < rewrites; i++) {
                int[] slot = slots.get(i);
                DataInputStream in = (DataInputStream) readOpen.invoke(mRegion, slot[0], slot[1]);
                byte[] got = drain(in);
                in.close();
                if (!Arrays.equals(got, expected.get(key(slot[0], slot[1])))) {
                    throw new IllegalStateException("SHADOW rewrite mismatch at "
                            + slot[0] + "," + slot[1]);
                }
            }
            closeM.invoke(mRegion);
        } else {
            Class<?> vClass = Class.forName(RF);
            Method vRead = vClass.getDeclaredMethod("func_76704_a", int.class, int.class);
            vRead.setAccessible(true);
            Method vClose = vClass.getDeclaredMethod("func_76708_c");
            Object vRegion = vClass.getConstructor(File.class).newInstance(work.toFile());
            for (int i = 0; i < rewrites; i++) {
                int[] slot = slots.get(i);
                DataInputStream in = (DataInputStream) vRead.invoke(vRegion, slot[0], slot[1]);
                byte[] got = drain(in);
                in.close();
                if (!Arrays.equals(got, expected.get(key(slot[0], slot[1])))) {
                    throw new IllegalStateException("ON rewrite mismatch at "
                            + slot[0] + "," + slot[1]);
                }
            }
            vClose.invoke(vRegion);
        }
        closeM.invoke(region);

        System.out.println("[harness] rewrites verified; final hook state: "
                + com.rustcraft.bridge.RustRegionWriteHook.dumpMetrics());
        System.out.println("REGION_WRITE_HARNESS_PASSED mode=" + mode + " chunks=" + count);
    }

    static long key(int x, int z) { return ((long) z << 32) | (x & 0xFFFFFFFFL); }
    static int[] unpack(long k) { return new int[]{(int) k, (int) (k >>> 32)}; }
}
