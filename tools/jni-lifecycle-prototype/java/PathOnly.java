import java.nio.file.*;
/** Isolates Java8 NIO initialization without loading any prototype library. */
public final class PathOnly {
    public static void main(String[] args) throws Exception {
        System.out.println("{\"event\":\"BEFORE_PATHS\"}");
        Path library=Paths.get(args[0]).toAbsolutePath();
        byte[] bytes=Files.readAllBytes(Paths.get(args[1]));
        System.out.println("{\"schema\":\"JVM_NIO_CONTROL_V1\",\"native_library_loaded\":false,\"input_bytes\":"+bytes.length+",\"absolute_path\":"+library.isAbsolute()+"}");
    }
}
