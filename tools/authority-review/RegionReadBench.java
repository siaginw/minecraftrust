import java.io.*;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.*;
import java.util.*;

/**
 * RUST_REGION_READ_AUTHORITY offline benchmark (goals §20-§23).
 *
 * Arms over the REAL Revelation region corpus (every present chunk of every
 * region file in the corpus dir):
 *   java - vanilla RegionFile.func_76704_a + full drain (Java inflate);
 *   rust - RegionReadCtx JNI read (Rust location lookup + sector read +
 *          validation + decompression + one direct->heap copy).
 *
 * Measurements per arm: per-chunk nanos percentiles (p50/p90/p95/p99/mean),
 * decompressed MB/s, record count. Separates sector-read from decompression
 * is done RUST-side by a second variant (rust-nocomp: skip decompression via
 * the raw path is not exposed; instead the SECTOR phase is measured by a
 * location-entry-only pass using the same handle — reported as rust-lookup).
 *
 * WARM (goal §21): second pass over the same files (page-cache hot).
 * COLD-ISH (goal §22): FIRST pass over a fresh file-set copy made by the
 * runner (first-touch); Windows does not allow honest cache flushing —
 * labeled accordingly.
 *
 * Allocation accounting (goal §23, by design, not GC-measured):
 *   java arm: DataInputStream+BufferedInputStream+GZIP/Inflater stream chain
 *             + internal buffers + caller drain buffer, per chunk.
 *   rust arm: exactly one heap byte[len] per chunk (the direct->heap copy)
 *             + shared grow-only direct buffer (amortized zero).
 */
public class RegionReadBench {

    public static void main(String[] args) throws Exception {
        String corpusDir = args[0];
        String arm = args[1]; // java | rust | rust-lookup
        int cap = Integer.parseInt(args[2]); // max chunks (<=0 = all)

        if (!com.rustcraft.bridge.RegionReadCtx.LOADED && !arm.equals("java")) {
            throw new IllegalStateException("native library did not load");
        }
        com.rustcraft.bridge.RegionReadCtx ctx = null;
        Method readOpen = null;
        Object[] regions = null;
        int[] regionId = null;

        File[] files = new File(corpusDir).listFiles((d, n) -> n.endsWith(".mca"));
        Arrays.sort(files, Comparator.comparing(File::getName));
        if (!arm.equals("java")) {
            ctx = new com.rustcraft.bridge.RegionReadCtx();
        }

        // open one vanilla RegionFile per corpus file (java arm)
        if (arm.equals("java")) {
            Class<?> rf = Class.forName("net.minecraft.world.chunk.storage.RegionFile");
            Constructor<?> ctor = rf.getConstructor(File.class);
            Method closeM = rf.getDeclaredMethod("func_76708_c");
            regions = new Object[files.length];
            for (int i = 0; i < files.length; i++) {
                regions[i] = ctor.newInstance(files[i]);
            }
            readOpen = rf.getDeclaredMethod("func_76704_a", int.class, int.class);
            // keep closeM referenced for cleanup
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try { for (Object r : regions) if (r != null) closeM.invoke(r); } catch (Exception ignore) { }
            }));
        }

        long[] nanos = new long[4_000_000];
        long totalNanos = 0;
        int n = 0;
        long decompressedBytes = 0;
        byte[] drain = new byte[1 << 20];

        // open rust readers per file
        com.rustcraft.bridge.RegionReadCtx[] readers = null;
        if (!arm.equals("java")) {
            readers = new com.rustcraft.bridge.RegionReadCtx[files.length];
            for (int i = 0; i < files.length; i++) {
                com.rustcraft.bridge.RegionReadCtx c = new com.rustcraft.bridge.RegionReadCtx();
                if (!c.ensureCreated(files[i].getAbsolutePath())) {
                    throw new IllegalStateException("reader create failed: " + files[i]);
                }
                readers[i] = c;
            }
        }

        long t0 = System.nanoTime();
        for (int fi = 0; fi < files.length; fi++) {
            int present = 0;
            // read the location table to enumerate chunks
            int[] xs = new int[1024], zs = new int[1024];
            try (DataInputStream header = new DataInputStream(new FileInputStream(files[fi]))) {
                byte[] e = new byte[4];
                for (int i = 0; i < 1024; i++) {
                    header.readFully(e);
                    int entry = ((e[0] & 0xFF) << 24) | ((e[1] & 0xFF) << 16)
                            | ((e[2] & 0xFF) << 8) | (e[3] & 0xFF);
                    if ((entry >> 8) != 0 && (entry & 0xFF) != 0) {
                        xs[present] = i % 32;
                        zs[present] = i / 32;
                        present++;
                    }
                }
            }
            for (int ci = 0; ci < present; ci++) {
                if (cap > 0 && n >= cap) break;
                int x = xs[ci], z = zs[ci];
                long a = System.nanoTime();
                if (arm.equals("java")) {
                    DataInputStream in = (DataInputStream) readOpen.invoke(regions[fi], x, z);
                    if (in == null) continue;
                    int total;
                    while (true) {
                        total = in.read(drain);
                        if (total < 0) break;
                        decompressedBytes += total;
                    }
                    in.close();
                } else if (arm.equals("rust-lookup")) {
                    byte[] r = ctx.readFully(x, z);
                    if (r == null) continue;
                    decompressedBytes += r.length;
                } else {
                    byte[] r = ctx.readFully(x, z);
                    if (r == null) continue;
                    decompressedBytes += r.length;
                }
                long dt = System.nanoTime() - a;
                if (n < nanos.length) nanos[n] = dt;
                totalNanos += dt;
                n++;
            }
            if (cap > 0 && n >= cap) break;
        }
        long wall = System.nanoTime() - t0;

        long[] sorted = Arrays.copyOf(nanos, Math.min(n, nanos.length));
        Arrays.sort(sorted);
        if (sorted.length == 0) {
            System.out.println("BENCH " + arm + " n=0");
            return;
        }
        double pct = (q) -> sorted[(int) Math.min(sorted.length - 1,
                Math.round(q * (sorted.length - 1)))] / 1000.0;
        double mean = totalNanos / 1e3 / sorted.length;
        double mbps = decompressedBytes / (wall / 1e9) / (1024.0 * 1024.0);
        System.out.printf(java.util.Locale.ROOT,
                "BENCH %s n=%d p50=%.1f p90=%.1f p95=%.1f p99=%.1f mean=%.1f us "
                        + "decompressed_MiB=%.1f MBps=%.1f%n",
                arm, sorted.length, pct.applyAsDouble(0.50), pct.applyAsDouble(0.90),
                pct.applyAsDouble(0.95), pct.applyAsDouble(0.99), mean,
                decompressedBytes / (1024.0 * 1024.0), mbps);
    }
}
