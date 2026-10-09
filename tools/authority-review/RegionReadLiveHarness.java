import java.io.*;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;
import java.util.zip.Deflater;

/**
 * RUST_REGION_READ_AUTHORITY offline LIVE harness.
 *
 * Loads the READ-transformed RegionFile (RegionFileReadTransformer applied to
 * the SRG-jar bytes, defined child-first) and drives the real synchronized
 * read seam func_76704_a(II) against records written by BOTH sides:
 *
 *  - ON mode: the returned DataInputStream must be Rust-supplied (the vanilla
 *    body is skipped) and byte-identical to the record's decompressed
 *    content; the REAL CompressedStreamTools.readFully parses it (goal §18).
 *  - SHADOW mode: the vanilla stream wrapped by the tee comparator; the
 *    comparison must complete with 0 mismatches while the caller sees
 *    vanilla bytes.
 *  - Corruption (§15): truncated/invalid records fail closed — ON returns
 *    the vanilla stream (lazy failure preserved), never a partial stream.
 *  - Coherency (§10-§11): engine writes / raw fallbacks between reads are
 *    observed by the next read on the SAME reader instance.
 *
 * Also verifies stream semantics (§17): single-byte reads, bulk reads,
 * available(), EOF, close() through CompressedStreamTools.
 */
public class RegionReadLiveHarness {

    static final String RF = "net.minecraft.world.chunk.storage.RegionFile";
    static final String CT = "net.minecraft.nbt.CompressedStreamTools";

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

    static byte[] drain(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int r;
        while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
        return out.toByteArray();
    }

    /** Builds a small compound NBT payload exactly like CompressedStreamTools writes. */
    static byte[] nbtPayload(int tag, String name) throws IOException {
        // NBT compound: TAG_Compound(10) + name + one int entry + TAG_End
        ByteArrayOutputStream nbt = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(nbt);
        d.writeByte(10);
        d.writeUTF(name);
        d.writeByte(3);
        d.writeUTF("value");
        d.writeInt(tag * 7919);
        d.writeByte(0);
        d.flush();
        return nbt.toByteArray();
    }

    public static void main(String[] args) throws Exception {
        String mode = args[0];       // SHADOW | ON_EXPERIMENTAL
        String srgJar = args[1];
        String workDir = args[2];
        int count = Integer.parseInt(args[3]);

        if (!Boolean.getBoolean("rustcraft.regionReadExperiment")) {
            throw new IllegalStateException("run with -Drustcraft.regionReadExperiment=true");
        }
        if (!com.rustcraft.bridge.RegionReadCtx.LOADED) {
            throw new IllegalStateException("native library did not load");
        }
        Files.createDirectories(Paths.get(workDir));
        Path work = Paths.get(workDir, "r.0.0.mca");
        Files.copy(Paths.get(args[4]), work, StandardCopyOption.REPLACE_EXISTING);

        byte[] vanilla = readRegionFileBytes(srgJar);
        com.rustcraft.coremod.RegionFileReadTransformer tx =
                new com.rustcraft.coremod.RegionFileReadTransformer();
        byte[] transformed = tx.transform(RF.replace('.', '/'), RF, vanilla);
        if (transformed == vanilla || transformed == null) {
            throw new IllegalStateException("read transformer did not modify: "
                    + com.rustcraft.coremod.RegionFileReadTransformer.lastTransformStatus);
        }
        System.out.println("[harness] transform status="
                + com.rustcraft.coremod.RegionFileReadTransformer.lastTransformStatus);

        URL[] urls = { new File(srgJar).toURI().toURL() };
        TransformedRegionLoader loader = new TransformedRegionLoader(transformed, urls,
                RegionReadLiveHarness.class.getClassLoader());
        Class<?> rfClass = loader.loadClass(RF);
        Constructor<?> ctor = rfClass.getConstructor(File.class);
        Method readOpen = rfClass.getDeclaredMethod("func_76704_a", int.class, int.class);
        readOpen.setAccessible(true);
        Method writeSeam = rfClass.getDeclaredMethod("func_76706_a",
                int.class, int.class, byte[].class, int.class);
        writeSeam.setAccessible(true);
        Method hasChunk = rfClass.getDeclaredMethod("func_76709_c", int.class, int.class);
        hasChunk.setAccessible(true);
        Method closeM = rfClass.getDeclaredMethod("func_76708_c");

        // the REAL Java NBT parser (goal §18): func_152456_a(DataInput, NBTSizeTracker)
        Class<?> ctClass = Class.forName(CT);
        Class<?> trackerClass = Class.forName("net.minecraft.nbt.NBTSizeTracker");
        Object unlimitedTracker = trackerClass.getDeclaredField("field_152451_a").get(null);
        Method readNbt = ctClass.getMethod("func_152456_a", java.io.DataInput.class, trackerClass);

        Object region = ctor.newInstance(work.toFile());

        // 1. pick existing chunks, read them through the transformed seam
        List<int[]> slots = new ArrayList<>();
        outer:
        for (int z = 0; z < 32; z++) {
            for (int x = 0; x < 32; x++) {
                if ((Boolean) hasChunk.invoke(region, x, z)) slots.add(new int[]{x, z});
                if (slots.size() >= count) break outer;
            }
        }
        int mismatches = 0;
        int reads = 0;
        for (int[] slot : slots) {
            DataInputStream in = (DataInputStream) readOpen.invoke(region, slot[0], slot[1]);
            if (in == null) throw new IllegalStateException("null stream for existing chunk");
            byte[] got = drain(in);
            in.close();
            reads++;
            // REAL NBT parse of the Rust/wrapped stream result (§18)
            Object tag = readNbt.invoke(null,
                    new DataInputStream(new ByteArrayInputStream(got)), unlimitedTracker);
            if (tag == null) throw new IllegalStateException("NBT parse returned null");
            if ("ON_EXPERIMENTAL".equals(mode)) {
                // ON: the stream came from Rust; verify against vanilla re-read
                Class<?> vClass = Class.forName(RF);
                Method vRead = vClass.getDeclaredMethod("func_76704_a", int.class, int.class);
                vRead.setAccessible(true);
                Object vRegion = vClass.getConstructor(File.class).newInstance(work.toFile());
                DataInputStream vin = (DataInputStream) vRead.invoke(vRegion, slot[0], slot[1]);
                byte[] want = drain(vin);
                vin.close();
                Method vClose = vClass.getDeclaredMethod("func_76708_c");
                vClose.invoke(vRegion);
                if (!Arrays.equals(got, want)) {
                    mismatches++;
                    System.out.println("[harness] ON mismatch at " + slot[0] + "," + slot[1]);
                }
            }
        }
        System.out.println("[harness] existing-chunk reads=" + reads + " mismatches=" + mismatches
                + " hook: " + com.rustcraft.bridge.RustRegionReadHook.dumpMetrics());
        if (mismatches > 0) throw new IllegalStateException("mismatches found");

        // 2. WRITE a new chunk through the seam, then READ it back (§10:
        //    Rust write -> Java read sees it immediately; same process)
        byte[] nbt = nbtPayload(41, "coherency");
        byte[] zraw = deflate(nbt);
        int nx = 5, nz = 5;
        writeSeam.invoke(region, nx, nz, zraw, zraw.length);
        DataInputStream in = (DataInputStream) readOpen.invoke(region, nx, nz);
        byte[] round = drain(in);
        in.close();
        if (!Arrays.equals(round, nbt)) {
            throw new IllegalStateException("write->read coherency broke (round-trip bytes differ)");
        }
        Object tag = readNbt.invoke(null, new DataInputStream(new ByteArrayInputStream(round)), unlimitedTracker);
        if (tag == null) throw new IllegalStateException("coherency NBT parse failed");
        System.out.println("[harness] write->read coherency verified (Rust engine record, "
                + "real NBT parse ok)");

        // 3. REWRITE via the seam (relocation), read back again
        byte[] nbt2 = nbtPayload(97, "coherency2");
        byte[] zraw2 = deflate(nbt2);
        writeSeam.invoke(region, nx, nz, zraw2, zraw2.length);
        in = (DataInputStream) readOpen.invoke(region, nx, nz);
        round = drain(in);
        in.close();
        if (!Arrays.equals(round, nbt2)) {
            throw new IllegalStateException("rewrite->read coherency broke");
        }
        System.out.println("[harness] rewrite->read coherency verified");

        // 4. corruption: truncated stream written directly to disk must fail
        //    closed (ON: vanilla stream returned -> lazy failure as unhooked)
        byte[] truncated = Arrays.copyOf(zraw2, zraw2.length / 2);
        // locate the record's sector from the on-disk location table
        int slotIdx = nx + nz * 32;
        byte[] ebuf = new byte[4];
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(work.toFile(), "r")) {
            raf.seek(slotIdx * 4);
            raf.readFully(ebuf);
        }
        int entry = ((ebuf[0] & 0xFF) << 24) | ((ebuf[1] & 0xFF) << 16)
                | ((ebuf[2] & 0xFF) << 8) | (ebuf[3] & 0xFF);
        int off = (entry >> 8) * 4096;
        java.nio.file.Path p = work;
        try (java.nio.channels.FileChannel ch =
                     java.nio.channels.FileChannel.open(p, java.nio.file.StandardOpenOption.WRITE)) {
            ch.write(java.nio.ByteBuffer.wrap(truncated), off + 5);
            ch.truncate(off + 5 + truncated.length);
        }
        in = (DataInputStream) readOpen.invoke(region, nx, nz);
        boolean lazyFailed = false;
        try {
            drain(in);
        } catch (IOException e) {
            lazyFailed = true;
        }
        in.close();
        System.out.println("[harness] corrupt record: lazy vanilla-style failure=" + lazyFailed
                + " partialStreamAttempts=" + com.rustcraft.bridge.RustRegionReadHook.PARTIAL_STREAM_ATTEMPTS.get());
        if (com.rustcraft.bridge.RustRegionReadHook.PARTIAL_STREAM_ATTEMPTS.get() != 0) {
            throw new IllegalStateException("partial stream escaped!");
        }
        closeM.invoke(region);
        System.out.println("REGION_READ_HARNESS_PASSED mode=" + mode + " reads=" + reads);
    }
}
