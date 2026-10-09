import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/** Independent Java 8 fixture check. JDK primitives, not Minecraft/Forge capture. */
public final class KeepAliveOracle {
    public static void main(String[] args) throws Exception {
        int checks = 0;
        for (String line : Files.readAllLines(Paths.get(args[0]), StandardCharsets.UTF_8)) {
            if (line.startsWith("#") || line.isEmpty()) continue;
            String[] fields = line.split("\\|");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeByte(fields[1].equals("toClient") ? 0x1f : 0x0b);
            long token = Long.parseLong(fields[2]);
            if (fields[0].equals("1.12.1")) {
                int value = Math.toIntExact(token);
                while ((value & ~0x7f) != 0) { out.writeByte(value | 0x80); value >>>= 7; }
                out.writeByte(value);
            } else if (fields[0].equals("1.12.2")) {
                out.writeLong(token);
            } else { throw new AssertionError("unknown version"); }
            StringBuilder hex = new StringBuilder();
            for (byte value : bytes.toByteArray()) hex.append(String.format("%02x", value & 255));
            if (!hex.toString().equals(fields[3])) throw new AssertionError(line + " actual=" + hex);
            checks++;
        }
        if (checks != 18) throw new AssertionError("fixture count " + checks);
        System.out.println("PASS KeepAliveOracle checks=" + checks);
    }
}
