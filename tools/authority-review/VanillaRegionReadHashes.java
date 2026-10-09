import java.io.*;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/**
 * Vanilla-side shadow-read comparator: reads every chunk of every region in a
 * world region dir through the REAL vanilla RegionFile/RegionFileCache
 * (reflection over the notch/SRG class), canonicalizes each decompressed
 * chunk payload with MessageDigest SHA-256, and writes a JSON map
 * "region-stem:x,z" -> hash. Compare with the Rust readall output for the
 * byte-exact shadow-read campaign.
 *
 * Note: the vanilla RegionFile hands out a DataInputStream over the DEFLATED
 * sector stream; we drain it fully — the drained bytes ARE the decompressed
 * payload Java's NBT parser would consume.
 */
public class VanillaRegionReadHashes {

    public static void main(String[] args) throws Exception {
        String regionDir = args[0];
        String outFile = args[1];

        Class<?> rf = Class.forName("net.minecraft.world.chunk.storage.RegionFile");
        Constructor<?> rfCtor = rf.getConstructor(File.class);
        Method createInputStream = rf.getDeclaredMethod("func_76704_a", int.class, int.class);
        createInputStream.setAccessible(true);
        Method hasChunkMethod = rf.getMethod("func_76709_c", int.class, int.class);

        File dir = new File(regionDir);
        File[] files = dir.listFiles((d, n) -> n.endsWith(".mca"));
        Arrays.sort(files, Comparator.comparing(File::getName));

        TreeMap<String, String> hashes = new TreeMap<>();
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        int problems = 0;
        byte[] buf = new byte[65536];
        for (File f : files) {
            String name = f.getName();
            String stem = name.substring(0, name.length() - 4);
            int dot1 = name.indexOf('.');
            int dot2 = name.indexOf('.', dot1 + 1);
            int rx = Integer.parseInt(name.substring(dot1 + 1, dot2));
            int rz = Integer.parseInt(name.substring(dot2 + 1, name.lastIndexOf('.')));
            Object region = rfCtor.newInstance(f);
            for (int lz = 0; lz < 32; lz++) {
                for (int lx = 0; lx < 32; lx++) {
                    try {
                        Boolean hasChunk = (Boolean) hasChunkMethod.invoke(region, lx, lz);
                        if (hasChunk == null || !hasChunk) continue;
                        DataInputStream in =
                                (DataInputStream) createInputStream.invoke(region, lx, lz);
                        md.reset();
                        int read;
                        long total = 0;
                        while ((read = in.read(buf)) > 0) {
                            md.update(buf, 0, read);
                            total += read;
                        }
                        in.close();
                        hashes.put(stem + ":" + lx + "," + lz,
                                hex(md.digest()) + ":" + total);
                    } catch (Exception e) {
                        problems++;
                        System.err.println("PROBLEM " + stem + ":" + lx + "," + lz + " " + e);
                    }
                }
            }
        }
        StringBuilder sb = new StringBuilder("{\n");
        List<String> keys = new ArrayList<>(hashes.keySet());
        for (int i = 0; i < keys.size(); i++) {
            sb.append('"').append(keys.get(i)).append("\": \"")
              .append(hashes.get(keys.get(i))).append('"');
            if (i + 1 < keys.size()) sb.append(',');
            sb.append('\n');
        }
        sb.append("}\n");
        Files.write(Paths.get(outFile), sb.toString().getBytes("UTF-8"));
        System.out.println("VANILLA_READALL hashes=" + hashes.size() + " problems=" + problems);
    }

    private static String hex(byte[] d) {
        StringBuilder sb = new StringBuilder(d.length * 2);
        for (byte b : d) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
