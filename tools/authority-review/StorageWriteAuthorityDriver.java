import java.io.*;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.*;
import java.util.*;

/**
 * RUST_REGION_WRITE_AUTHORITY driver (offline, disposable world copy).
 *
 * For each selected chunk: the VANILLA path produces the authoritative
 * compressed chunk bytes (read the chunk through the real RegionFile, then
 * re-frame the exact same payload through the real vanilla writer semantics:
 * 4-byte BE length + type byte + zlib stream — produced here by the same
 * Deflater default settings the vanilla ChunkBuffer uses), and those exact
 * bytes are handed to the Rust region writer via the region_tools
 * `writechunk` contract (file written to <file>.rustwrite for the driver
 * script to commit). NO Rust NBT serialization happens — mod data is inside
 * the payload bytes and is preserved byte-exactly by construction.
 */
public class StorageWriteAuthorityDriver {

    public static void main(String[] args) throws Exception {
        String regionDir = args[0];
        String outDir = args[1];
        int count = Integer.parseInt(args[2]);
        new File(outDir).mkdirs();

        Class<?> rf = Class.forName("net.minecraft.world.chunk.storage.RegionFile");
        Constructor<?> rfCtor = rf.getConstructor(File.class);
        Method createIn = rf.getDeclaredMethod("func_76704_a", int.class, int.class);
        createIn.setAccessible(true);
        Method hasChunk = rf.getMethod("func_76709_c", int.class, int.class);

        File dir = new File(regionDir);
        File[] files = dir.listFiles((d, n) -> n.endsWith(".mca"));
        Arrays.sort(files, Comparator.comparing(File::getName));

        int written = 0;
        byte[] buf = new byte[65536];
        for (File f : files) {
            if (written >= count) break;
            String name = f.getName();
            String stem = name.substring(0, name.length() - 4);
            int dot1 = name.indexOf('.');
            int dot2 = name.indexOf('.', dot1 + 1);
            int rx = Integer.parseInt(name.substring(dot1 + 1, dot2));
            int rz = Integer.parseInt(name.substring(dot2 + 1, name.lastIndexOf('.')));
            Object region = rfCtor.newInstance(f);
            for (int lz = 0; lz < 32 && written < count; lz++) {
                for (int lx = 0; lx < 32 && written < count; lx++) {
                    Boolean has = (Boolean) hasChunk.invoke(region, lx, lz);
                    if (has == null || !has) continue;
                    // 1. drain the decompressed payload through the REAL vanilla reader
                    DataInputStream in =
                            (DataInputStream) createIn.invoke(region, lx, lz);
                    ByteArrayOutputStream payload = new ByteArrayOutputStream();
                    int r;
                    while ((r = in.read(buf)) > 0) payload.write(buf, 0, r);
                    in.close();
                    byte[] decompressed = payload.toByteArray();

                    // 2. vanilla writer semantics: Deflater default level, zlib stream
                    java.util.zip.Deflater def = new java.util.zip.Deflater();
                    def.setInput(decompressed);
                    def.finish();
                    ByteArrayOutputStream compressed = new ByteArrayOutputStream();
                    byte[] scratch = new byte[8192];
                    while (!def.finished()) {
                        int n = def.deflate(scratch);
                        compressed.write(scratch, 0, n);
                    }
                    def.reset();

                    // 3. vanilla frame: 4-byte BE length (payload+1) + type byte 2
                    byte[] framed = new byte[compressed.size() + 5];
                    int total = compressed.size() + 1;
                    framed[0] = (byte) ((total >>> 24) & 0xFF);
                    framed[1] = (byte) ((total >>> 16) & 0xFF);
                    framed[2] = (byte) ((total >>> 8) & 0xFF);
                    framed[3] = (byte) (total & 0xFF);
                    framed[4] = 2;
                    System.arraycopy(compressed.toByteArray(), 0, framed, 5, compressed.size());

                    // 4. hand the EXACT bytes to the Rust region writer contract
                    String outName = String.format("%s.%d,%d.rustwrite", stem, lx, lz);
                    Files.write(new File(outDir, outName).toPath(), framed);
                    written++;
                }
            }
        }
        System.out.println("DRIVER produced " + written + " vanilla-authoritative chunk records");
    }
}
