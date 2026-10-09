package com.rustcraft.coremod;

import com.rustcraft.qualification.CalleeIsolation;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.MethodInsnNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Behavioural controls for the ISOLATED_CALLEE exception contract.
 *
 * <p>CalleeIsolation proves the STRUCTURE: that a wrapper really contains a
 * failing observation. This proves the BEHAVIOUR: that a wrapper of the
 * production shape, compiled by the real compiler, actually returns normally
 * when its observation throws, and that the application exceptions either side
 * of it still propagate.</p>
 *
 * <p>The fixtures are real Java (see fixtures/callee/example/Wrappers.java)
 * rather than hand-emitted bytecode, because the production wrappers are real
 * Java and the claim under test is about their shape. Every observation in the
 * fixture throws, so containment is a question with a definite answer rather
 * than one that happens to depend on an observation misbehaving.</p>
 */
public final class IsolatedCalleeControls {

    private static int checks = 0;
    /** Internal name, as it appears in a call instruction. */
    private static final String OWNER = "example/Wrappers";
    /** Binary name, as a classloader wants it. */
    private static final String BINARY = "example.Wrappers";
    private static final String DESC = "()V";

    public static void main(String[] args) throws Exception {
        Path classes = Paths.get(args[0]);
        byte[] wrappers = Files.readAllBytes(classes.resolve("example/Wrappers.class"));
        Loader loader = new Loader(classes);

        // ---- the contract, proven structurally ---------------------------
        contained(wrappers, "safeObservation",
                "a Throwable catch around the observation is a containment contract");
        contained(wrappers, "safeOther",
                "a wrapper over a different observation is judged on what it contains");

        // ---- every way of not having one is refused ----------------------
        refused(wrappers, "safeNarrow", "catching Exception is not a Throwable contract");
        refused(wrappers, "safeBare", "no catch is not a containment contract");
        refused(wrappers, "safeRethrow", "rethrowing is a lifecycle contract, not containment");
        refused(wrappers, "safeRangeMissesCall", "a catch that misses the call is not containment");
        refused(wrappers, "safeRecursive", "a wrapper that calls itself is not containment");
        refused(wrappers, "applicationOperation", "a non-wrapper method is not a contract");

        // ---- the contract, proven behaviourally --------------------------
        loader.loadClass(BINARY).getMethod("safeObservation", new Class<?>[0]).invoke(null);
        check(true, "a contained observation failure returns normally to the caller");

        // ---- an application failure still escapes, either side ------------
        for (String method : new String[] {
                "applicationFailureThenWrapper", "wrapperThenApplicationFailure"}) {
            try {
                loader.loadClass(BINARY).getMethod(method, new Class<?>[0]).invoke(null);
                check(false, method + " returned normally; an application failure was swallowed");
            } catch (java.lang.reflect.InvocationTargetException escaped) {
                Throwable cause = escaped.getCause();
                check(cause != null && "application failure".equals(cause.getMessage()),
                        method + " propagates the original Throwable unchanged");
            }
        }

        // ---- a wrapper that does not contain lets the failure out ---------
        try {
            loader.loadClass(BINARY).getMethod("safeBare", new Class<?>[0]).invoke(null);
            check(false, "an uncontained observation returned normally -> containment is not real");
        } catch (java.lang.reflect.InvocationTargetException escaped) {
            check(escaped.getCause() instanceof IllegalStateException,
                    "without a catch the observation failure escapes, as it must");
        }

        // ---- the evidence names the call it actually holds ----------------
        String other = evidence(wrappers, "safeOther");
        check(other != null && other.contains("otherObservation"),
                "the evidence names the observation the wrapper really contains");

        System.out.println("PASS IsolatedCalleeControls assertions=" + checks
                + "; containment observed at runtime, application failures untouched");
    }

    private static String evidence(byte[] classBytes, String wrapperName) {
        return CalleeIsolation.verify(classBytes,
                new MethodInsnNode(Opcodes.INVOKESTATIC, OWNER, wrapperName, DESC, false));
    }

    private static void contained(byte[] classBytes, String name, String what) {
        String found = evidence(classBytes, name);
        check(found != null && found.startsWith(CalleeIsolation.CONTRACT),
                what + (found == null ? " -> refused: " + CalleeIsolation.lastRefusal() : ""));
    }

    private static void refused(byte[] classBytes, String name, String what) {
        String found = evidence(classBytes, name);
        check(found == null, what + (found == null ? "" : " -> unexpectedly accepted: " + found));
    }

    /** Loads the fixture so the calls are real invocations, not simulations. */
    private static final class Loader extends ClassLoader {
        private final Path root;
        Loader(Path root) { super(IsolatedCalleeControls.class.getClassLoader()); this.root = root; }
        @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
            Path file = root.resolve(name.replace('.', '/') + ".class");
            try {
                byte[] bytes = Files.readAllBytes(file);
                return defineClass(name, bytes, 0, bytes.length);
            } catch (java.io.IOException unreadable) {
                throw new ClassNotFoundException(name, unreadable);
            }
        }
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
        checks++;
    }

    private IsolatedCalleeControls() { }
}
