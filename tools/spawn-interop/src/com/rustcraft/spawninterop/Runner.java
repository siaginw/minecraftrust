package com.rustcraft.spawninterop;

import com.rustcraft.spawninterop.SpawnModel.JavaIndex;
import com.rustcraft.spawninterop.SpawnModel.RefLinear;
import com.rustcraft.spawninterop.SpawnModel.RustIndex;
import com.rustcraft.spawninterop.SpawnModel.Start;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.math.ChunkPos;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.PrintStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * M3.0 offline prototype runner: real-world extraction, three-way parity,
 * effectiveness (candidate counts / skip factor), and a fair benchmark of
 * REFERENCE_LINEAR vs JAVA_INDEX vs RUST_INDEX (JNI included).
 */
public final class Runner {

    static PrintStream out;
    static long mismatchesTotal = 0;

    public static void main(String[] args) throws Exception {
        out = new PrintStream(Files.newOutputStream(
                Paths.get(args.length > 0 ? args[0] : "machine/raw/M30-prototype.txt")), false);
        Runtime.getRuntime().addShutdownHook(new Thread(out::flush));

        out.println("== M3.0 spawn-index prototype ==");
        List<SpawnModel> sets = new ArrayList<>();
        sets.add(SpawnModel.synthetic("syn-empty", "temple", 0, 1));
        sets.add(SpawnModel.synthetic("syn-1", "temple", 1, 2));
        sets.add(SpawnModel.synthetic("syn-16", "temple", 16, 3));
        sets.add(SpawnModel.synthetic("syn-256", "temple", 256, 4));
        sets.add(SpawnModel.synthetic("syn-4096", "temple", 4096, 5));
        sets.add(SpawnModel.synthetic("syn-32768-dense", "dense", 32768, 6));
        sets.add(SpawnModel.synthetic("syn-2048-huge", "huge", 2048, 7));
        sets.add(SpawnModel.synthetic("syn-4096-negative", "negative", 4096, 8));

        try {
            SpawnModel a = extractWorld("real-targetA", "machine/targetA/server/world/region", 4096);
            if (a != null) sets.add(a); else out.println("real-targetA: no structures found");
        } catch (Exception e) { out.println("targetA extraction failed: " + e); }
        try {
            SpawnModel c = extractWorld("real-targetC", "machine/targetC/server/world/region", 4096);
            if (c != null) sets.add(c); else out.println("real-targetC: chunk-NBT starts absent (bot corridor)");
        } catch (Exception e) { out.println("targetC extraction failed: " + e); }
        for (String feat : new String[]{"Temple", "Village", "Mineshaft", "Monument", "Stronghold"}) {
            try {
                SpawnModel fm = extractFeatureDat("machine/targetC/server", feat);
                if (fm != null) sets.add(fm);
            } catch (Exception e) { out.println(feat + ".dat extraction failed: " + e); }
        }

        for (SpawnModel m : sets) runDataset(m);

        out.println("\nTOTAL_PARITY_MISMATCHES=" + mismatchesTotal);
        out.println(mismatchesTotal == 0 ? "PARITY: ALL THREE IMPLEMENTATIONS IDENTICAL" : "PARITY: FAILED");
        out.flush();
        if (mismatchesTotal > 0) System.exit(3);
    }

    static void runDataset(SpawnModel m) {
        out.println("\n---- dataset " + m.name + ": " + m.starts.size() + " starts ----");
        RefLinear ref = new RefLinear(m);
        JavaIndex jix = new JavaIndex(m);
        RustIndex rix = new RustIndex(m);

        // ---- parity + effectiveness corpus (untimed) ----
        int nq = m.starts.size() == 0 ? 2000 : (m.starts.size() < 32 ? 10000 : 25000);
        Random qr = new Random(0xC0FFEE + m.name.hashCode());
        long linExam = 0, jExam = 0, rExam = 0, hits = 0;
        long[] linHist = new long[256], jHist = new long[256], rHist = new long[256];
        long rExamPrev = 0;
        for (int q = 0; q < nq; q++) {
            int x, y, z;
            if (!m.starts.isEmpty() && qr.nextInt(10) < 6) {
                Start s = m.starts.get(qr.nextInt(m.starts.size()));
                x = s.minX + qr.nextInt(Math.max(1, s.maxX - s.minX + 1));
                z = s.minZ + qr.nextInt(Math.max(1, s.maxZ - s.minZ + 1));
            } else {
                x = qr.nextInt(4_000_000) - 2_000_000;
                z = qr.nextInt(4_000_000) - 2_000_000;
            }
            y = qr.nextInt(256);
            int a = ref.query(x, y, z);
            int b = jix.query(x, y, z);
            int c = rix.query(x, y, z);
            long rExamNow = rix.stats()[1];
            int rDelta = (int) (rExamNow - rExamPrev);
            rExamPrev = rExamNow;
            if (a != b || a != c) {
                mismatchesTotal++;
                if (mismatchesTotal <= 5)
                    out.println("MISMATCH q=" + q + " xyz=" + x + "," + y + "," + z
                            + " lin=" + a + " java=" + b + " rust=" + c);
            }
            linExam += ref.examinedLast;
            jExam += jix.examinedLast;
            rExam += rDelta;
            hist(linHist, ref.examinedLast);
            hist(jHist, jix.examinedLast);
            hist(rHist, rDelta);
            if (a >= 0) hits++;
        }
        out.printf("parity corpus: %d queries, hit rate %.1f%%%n", nq, 100.0 * hits / nq);
        out.printf("candidates/query linear: p50=%d p95=%d p99=%d max=%d avg=%.1f%n",
                pct(linHist, .50), pct(linHist, .95), pct(linHist, .99), histMax(linHist), (double) linExam / nq);
        out.printf("candidates/query java:   p50=%d p95=%d p99=%d max=%d avg=%.2f%n",
                pct(jHist, .50), pct(jHist, .95), pct(jHist, .99), histMax(jHist), (double) jExam / nq);
        out.printf("candidates/query rust:   p50=%d p95=%d p99=%d max=%d avg=%.2f%n",
                pct(rHist, .50), pct(rHist, .95), pct(rHist, .99), histMax(rHist), (double) rExam / nq);
        out.printf("SKIP FACTOR (linear avg / rust avg) = %.2fx%n", (double) linExam / Math.max(1, rExam));
        long[] rs = rix.stats();
        out.printf("rust regions=%d approx_mem=%d bytes; java regions=%d%n", rs[4], rs[5], jix.regionCount());

        // ---- build cost ----
        long t0 = System.nanoTime();
        JavaIndex j2 = new JavaIndex(m);
        long javaBuildNs = System.nanoTime() - t0;
        t0 = System.nanoTime();
        RustIndex r2 = new RustIndex(m);
        long rustBuildNs = System.nanoTime() - t0;
        r2.free();

        // ---- benchmark: identical corpus, warmed, per-query nanos sampled ----
        int benchN = m.starts.size() == 0 ? 20000 : 100_000;
        int[] qs = new int[benchN * 3];
        Random br = new Random(99);
        for (int i = 0; i < benchN; i++) {
            if (!m.starts.isEmpty() && br.nextInt(10) < 6) {
                Start s = m.starts.get(br.nextInt(m.starts.size()));
                qs[i * 3] = s.minX + br.nextInt(Math.max(1, s.maxX - s.minX + 1));
                qs[i * 3 + 2] = s.minZ + br.nextInt(Math.max(1, s.maxZ - s.minZ + 1));
            } else {
                qs[i * 3] = br.nextInt(4_000_000) - 2_000_000;
                qs[i * 3 + 2] = br.nextInt(4_000_000) - 2_000_000;
            }
            qs[i * 3 + 1] = br.nextInt(256);
        }
        bench("linear", (x, y, z) -> ref.query(x, y, z), qs, benchN);
        bench("java_ix", (x, y, z) -> jix.query(x, y, z), qs, benchN);
        bench("rust_ix", (x, y, z) -> rix.query(x, y, z), qs, benchN);
        out.printf("build: java=%d us; rust(incl %d JNI inserts)=%d us%n",
                javaBuildNs / 1000, m.starts.size(), rustBuildNs / 1000);
        rix.free();
    }

    interface Q { int q(int x, int y, int z); }

    static void bench(String name, Q impl, int[] qs, int n) {
        // warmup
        for (int i = 0; i < Math.min(30000, n); i++)
            impl.q(qs[i * 3], qs[i * 3 + 1], qs[i * 3 + 2]);
        long[] t = new long[n];
        for (int i = 0; i < n; i++) {
            long s = System.nanoTime();
            impl.q(qs[i * 3], qs[i * 3 + 1], qs[i * 3 + 2]);
            t[i] = System.nanoTime() - s;
        }
        long[] sorted = t.clone();
        Arrays.sort(sorted);
        double med = sorted[n / 2], p95 = sorted[(int) (n * 0.95)], p99 = sorted[(int) (n * 0.99)];
        out.printf("  bench %-8s median=%.0f ns p95=%.0f ns p99=%.0f ns qps=%.0f%n",
                name, med, p95, p99, med > 0 ? 1e9 / med : 0);
    }

    static void hist(long[] h, long v) { h[(int) Math.min(h.length - 1, Math.max(0, v))]++; }

    static long pct(long[] h, double p) {
        long tot = 0;
        for (long v : h) tot += v;
        long need = (long) (tot * p), acc = 0;
        for (int i = 0; i < h.length; i++) { acc += h[i]; if (acc >= need) return i; }
        return h.length - 1;
    }

    static long histMax(long[] h) {
        for (int i = h.length - 1; i >= 0; i--) if (h[i] > 0) return i;
        return 0;
    }

    // ---------------- real-world .mca extraction ----------------

    static SpawnModel extractWorld(String name, String dir, int cap) throws Exception {
        Path base = Paths.get(dir);
        if (!Files.isDirectory(base)) return null;
        List<Path> files = new ArrayList<>();
        try (java.util.stream.Stream<Path> s = Files.list(base)) {
            s.filter(p -> p.getFileName().toString().endsWith(".mca")).sorted().forEach(files::add);
        }
        List<String> prov = new ArrayList<>();
        for (Path p : files) prov.add(p.getFileName() + " sha256=" + sha256(p));
        SpawnModel m = new SpawnModel(name);
        for (Path p : files) {
            extractRegion(p, m, cap);
            if (m.starts.size() >= cap) break;
        }
        out.println("real dataset " + name + " files: " + prov + " badChunks=" + badChunks);
        if (m.starts.isEmpty()) return null;
        // vanilla-map semantics: same chunk key replaces (mixed features in
        // one chunk keep the last one — model-level consistency documented)
        Long2ObjectOpenHashMap<Start> byKey = new Long2ObjectOpenHashMap<>(1024);
        for (Start s : m.starts) byKey.put(s.chunkKey, s);
        List<Start> uniq = new ArrayList<>();
        for (Start s : byKey.values()) uniq.add(s);
        m.starts.clear();
        m.starts.addAll(uniq);
        m.assignRanksFromMap();
        return m;
    }

    static long badChunks = 0;

    /** Extract one feature's REAL structure map from world/data/<Feature>.dat
     *  (MapGenStructureData: GZIP NBT, data->Features->chunkKey->start). */
    static SpawnModel extractFeatureDat(String worldDir, String feature) throws Exception {
        Path f = Paths.get(worldDir, "world", "data", feature + ".dat");
        if (!Files.isRegularFile(f)) return null;
        NBTTagCompound root = CompressedStreamTools.func_74796_a(
                new BufferedInputStream(Files.newInputStream(f)));
        NBTTagCompound data = root.func_74775_l("data");
        if (data == null) return null;
        NBTTagCompound features = data.func_74775_l("Features");
        if (features == null) return null;
        SpawnModel m = new SpawnModel("real-" + feature);
        for (String key : features.func_150296_c()) {
            NBTTagCompound st = features.func_74775_l(key);
            if (st == null) continue;
            int[] bb = st.func_74759_k("BB");
            if (bb == null || bb.length != 6) continue;
            // runtime isValid() is constant-true (verified by disassembly);
            // absent Valid tag => valid
            boolean valid = st.func_150296_c().contains("Valid") ? st.func_74767_n("Valid") : true;
            List<int[]> comps = new ArrayList<>();
            NBTTagList children = st.func_150295_c("Children", 10);
            if (children != null) {
                for (int ci = 0; ci < children.func_74745_c(); ci++) {
                    int[] cbb = children.func_150305_b(ci).func_74759_k("BB");
                    if (cbb != null && cbb.length == 6) comps.add(cbb);
                }
            }
            if (comps.isEmpty()) comps.add(bb);
            // key format "[cx,cz]"
            long ck;
            String k2 = key.replace("[", "").replace("]", "");
            String[] parts = k2.split(",");
            if (parts.length == 2) {
                try {
                    ck = ChunkPos.func_77272_a(Integer.parseInt(parts[0].trim()),
                            Integer.parseInt(parts[1].trim()));
                } catch (NumberFormatException e) { continue; }
            } else {
                try { ck = Long.parseLong(key); } catch (NumberFormatException e) { continue; }
            }
            m.starts.add(new Start(-1, valid, bb, comps.toArray(new int[0][]), ck));
        }
        if (m.starts.isEmpty()) return null;
        m.assignRanksFromMap();
        out.println("real feature " + feature + ": " + m.starts.size() + " starts from "
                + f + " sha256=" + sha256(f));
        return m;
    }

    static void extractRegion(Path file, SpawnModel m, int cap) throws Exception {
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            byte[] hdr = new byte[8192];
            raf.readFully(hdr);
            for (int i = 0; i < 1024; i++) {
                int off = ((hdr[i * 4] & 0xFF) << 16) | ((hdr[i * 4 + 1] & 0xFF) << 8) | (hdr[i * 4 + 2] & 0xFF);
                if (off == 0) continue;
                raf.seek(off * 4096L);
                int len = raf.readInt();
                int type = raf.readByte();
                if (len <= 1) continue;
                byte[] data = new byte[len - 1];
                raf.readFully(data);
                NBTTagCompound root = null;
                try {
                    java.io.ByteArrayInputStream bais = new java.io.ByteArrayInputStream(data);
                    if (type == 2) root = CompressedStreamTools.func_74794_a(
                            new DataInputStream(new BufferedInputStream(new java.util.zip.InflaterInputStream(bais))));
                    else if (type == 1) root = CompressedStreamTools.func_74794_a(
                            new DataInputStream(new BufferedInputStream(new java.util.zip.GZIPInputStream(bais))));
                    else root = CompressedStreamTools.func_74794_a(new DataInputStream(bais));
                } catch (Exception ex) {
                    badChunks++;
                    continue;
                }
                NBTTagCompound level = root.func_74775_l("Level");
                if (level == null) continue;
                NBTTagCompound structures = level.func_74775_l("Structures");
                if (structures == null) continue;
                NBTTagCompound starts = structures.func_74775_l("Starts");
                if (starts == null) continue;
                int cx = level.func_74762_e("xPos"), cz = level.func_74762_e("zPos");
                for (String key : starts.func_150296_c()) {
                    NBTTagCompound st = starts.func_74775_l(key);
                    if (st == null) continue;
                    int[] bb = st.func_74759_k("BB");
                    if (bb == null || bb.length != 6) continue;
                    boolean valid = st.func_74767_n("Valid");
                    List<int[]> comps = new ArrayList<>();
                    NBTTagList children = st.func_150295_c("Children", 10);
                    if (children != null) {
                        for (int ci = 0; ci < children.func_74745_c(); ci++) {
                            int[] cbb = children.func_150305_b(ci).func_74759_k("BB");
                            if (cbb != null && cbb.length == 6) comps.add(cbb);
                        }
                    }
                    if (comps.isEmpty()) comps.add(bb);
                    m.starts.add(new Start(-1, valid, bb, comps.toArray(new int[0][]),
                            ChunkPos.func_77272_a(cx, cz)));
                    if (m.starts.size() >= cap) return;
                }
            }
        } catch (EOFException ignored) { }
    }

    static String sha256(Path p) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        try (java.io.InputStream in = Files.newInputStream(p)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format("%02x", b));
        return sb.toString().substring(0, 16);
    }
}
