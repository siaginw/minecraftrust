import java.io.InputStream;
import java.security.MessageDigest;

public class ProbeClassOrigin {
    public static void main(String[] args) throws Exception {
        Class<?> cls = Class.forName("com.rustcraft.bridge.NativeChunkPacket");
        System.out.println("CodeSource: " + (cls.getProtectionDomain().getCodeSource() != null
                ? cls.getProtectionDomain().getCodeSource().getLocation() : "null"));
        InputStream in = cls.getResourceAsStream("/com/rustcraft/bridge/NativeChunkPacket.class");
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] buf = new byte[8192];
        int n, total = 0;
        while ((n = in.read(buf)) > 0) { md.update(buf, 0, n); total += n; }
        in.close();
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format("%02x", b));
        System.out.println("loaded class bytes: " + total + " sha256=" + sb);
    }
}
