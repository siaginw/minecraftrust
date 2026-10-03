import java.io.*;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.*;
import java.util.*;

/**
 * RUST_REGION_WRITE_AUTHORITY write bench: Java vanilla seam vs Rust engine,
 * fed the SAME real deflate records extracted from a live region file.
 *
 * Input manifest (built by the runner): a directory of
 *   rec_<i>.deflate  - the raw DEFLATE stream exactly as vanilla's
 *                      ChunkBuffer.close handed it to func_76706_a
 *   manifest.txt     - "<i> <chunkX> <chunkZ>" per line
 *
 * Arms (chosen by args[1]):
 *   java - real SRG RegionFile instance on <out>/r.bench.java.mca; the timed
 *          call is the real synchronized func_76706_a(x, z, deflate, len).
 *   rust - RegionWriteCtx JNI handle on <out>/r.bench.rust.mca; the timed
 *          call is ctx.write(x, z, directPayload, len, ticket).
 *
 * Prints per-call nanos sorted percentile lines:
 *   BENCH <arm> n=<N> p50=<us> p90=<us> p99=<us> max=<us> total_ms=<ms>
 */
public class RegionWriteBench {

    public static void main(String[] args) throws Exception {
        String manifestDir = args[0];
        String arm = args[1];
        String outDir = args[2];
        int count = Integer.parseInt(args[3]);
        new File(outDir).mkdirs();

        List<int[]> meta = new ArrayList<>();
        for (String line : Files.readAllLines(Paths.get(manifestDir, "manifest.txt"))) {
            if (line.isEmpty()) continue;
            String[] p = line.trim().split("\\s+");
            meta.add(new int[]{Integer.parseInt(p[0]), Integer.parseInt(p[1]),
                    Integer.parseInt(p[2])});
        }
        if (count > 0 && count < meta.size()) meta = meta.subList(0, count);
        System.out.println("[bench] arm=" + arm + " records=" + meta.size());

        long[] nanos = new long[meta.size()];
        long t0 = System.nanoTime();

        if ("java".equals(arm)) {
            Class<?> rf = Class.forName("net.minecraft.world.chunk.storage.RegionFile");
            File outFile = new File(outDir, "r.bench.java.mca");
            outFile.delete();
            Constructor<?> ctor = rf.getConstructor(File.class);
            Method writeSeam = rf.getDeclaredMethod("func_76706_a",
                    int.class, int.class, byte[].class, int.class);
            writeSeam.setAccessible(true);
            Method closeM = rf.getDeclaredMethod("func_76708_c");
            Object region = ctor.newInstance(outFile);
            int i = 0;
            for (int[] m : meta) {
                byte[] payload = Files.readAllBytes(
                        Paths.get(manifestDir, "rec_" + m[0] + ".deflate"));
                int slot = i++ % 1024;
                int x = slot % 32, z = slot / 32;
                long a = System.nanoTime();
                writeSeam.invoke(region, x, z, payload, payload.length);
                nanos[i - 1] = System.nanoTime() - a;
            }
            closeM.invoke(region);
        } else if ("rust".equals(arm)) {
            if (!com.rustcraft.bridge.RegionWriteCtx.LOADED) {
                throw new IllegalStateException("native library did not load");
            }
            File outFile = new File(outDir, "r.bench.rust.mca");
            outFile.delete();
            com.rustcraft.bridge.RegionWriteCtx ctx = new com.rustcraft.bridge.RegionWriteCtx();
            if (!ctx.ensureCreated(outFile.getAbsolutePath())) {
                throw new IllegalStateException("engine create failed");
            }
            long[] floors = ctx.floors();
            java.nio.ByteBuffer scratch = java.nio.ByteBuffer.allocateDirect(1 << 20);
            int i = 0;
            for (int[] m : meta) {
                byte[] payload = Files.readAllBytes(
                        Paths.get(manifestDir, "rec_" + m[0] + ".deflate"));
                if (scratch.capacity() < payload.length) {
                    scratch = java.nio.ByteBuffer.allocateDirect(payload.length);
                }
                scratch.clear();
                scratch.put(payload).flip();
                int slot = i % 1024;
                int x = slot % 32, z = slot / 32;
                long ticket = (floors != null ? floors[slot] : 0) + i / 1024 + 1;
                long a = System.nanoTime();
                long entry = ctx.write(x, z, scratch, payload.length, ticket);
                nanos[i] = System.nanoTime() - a;
                if (entry <= 0) throw new IllegalStateException(
                        "rust write " + i + " failed: " + entry);
                i++;
            }
            ctx.free();
        } else {
            throw new IllegalStateException("unknown arm " + arm);
        }

        long total = System.nanoTime() - t0;
        long[] sorted = nanos.clone();
        Arrays.sort(sorted);
        java.util.function.DoubleUnaryOperator p = (q) ->
                sorted[(int) Math.min(sorted.length - 1,
                        Math.round(q * (sorted.length - 1)))] / 1000.0;
        System.out.printf(java.util.Locale.ROOT,
                "BENCH %s n=%d p50=%.1f p90=%.1f p99=%.1f max=%.1f total_ms=%.1f%n",
                arm, sorted.length, p.applyAsDouble(0.50), p.applyAsDouble(0.90),
                p.applyAsDouble(0.99), p.applyAsDouble(1.0), total / 1e6);
    }
}
