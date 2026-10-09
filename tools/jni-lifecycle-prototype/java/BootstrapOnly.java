/** Same Java8 startup under -Xcheck:jni, without loading any prototype library. */
public final class BootstrapOnly {
    public static void main(String[] args) {
        System.out.println("{\"schema\":\"JVM_BOOTSTRAP_CONTROL_V1\",\"native_library_loaded\":false}");
    }
}
