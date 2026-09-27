import com.rustcraft.fresh.agent.DefinitionObserver;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Actual definitions of the same binary name in distinct loader objects. */
public final class ObserverControl {
    static final class Owner extends ClassLoader {
        Class<?> define(byte[] bytes) { return defineClass("example.Fixture", bytes, 0, bytes.length); }
    }
    public static void main(String[] args) throws Exception {
        byte[] bytes = Files.readAllBytes(Paths.get(args[0]));
        Owner first = new Owner(); Owner second = new Owner();
        Class<?> a = first.define(bytes); Class<?> b = second.define(bytes);
        if (a == b || a.getClassLoader() == b.getClassLoader()) throw new AssertionError("distinct loaders");
        if (!Integer.valueOf(37).equals(a.getMethod("value").invoke(null)) || !Integer.valueOf(37).equals(b.getMethod("value").invoke(null)))
            throw new AssertionError("actual methods");
        List<Map<String, Object>> one = DefinitionObserver.verifiedDefinitions(Arrays.<Class<?>>asList(a));
        List<Map<String, Object>> two = DefinitionObserver.verifiedDefinitions(Arrays.<Class<?>>asList(b));
        if (one.get(0).get("file").equals(two.get(0).get("file")) || DefinitionObserver.allDefinitions().size() != 2)
            throw new AssertionError("loader identities conflated");
        if (!one.get(0).get("raw_sha256").equals(two.get(0).get("raw_sha256"))) throw new AssertionError("same byte identity");
        boolean missing = false;
        try { DefinitionObserver.verifiedDefinitions(Arrays.<Class<?>>asList(ObserverControl.class)); }
        catch (IllegalStateException expected) { missing = true; }
        if (!missing) throw new AssertionError("unobserved class accepted");
        if (args[1].equals("redefined")) new DefinitionObserver().transform(first, "example/Fixture", a, null, bytes);
        else if (args[1].equals("duplicate")) new DefinitionObserver().transform(first, "example/Fixture", null, null, bytes);
        else if (!args[1].equals("valid")) throw new IllegalArgumentException("mode");
        if (!args[1].equals("valid")) {
            boolean rejected = false;
            try { DefinitionObserver.verifiedDefinitions(Arrays.<Class<?>>asList(a)); }
            catch (IllegalStateException expected) { rejected = true; }
            if (!rejected) throw new AssertionError("sticky definition failure missing");
        }
        System.out.println("PASS_OBSERVER_CONTROL " + args[1]);
    }
}
