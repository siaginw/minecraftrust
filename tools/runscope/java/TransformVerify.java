import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.util.CheckClassAdapter;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Offline transformer verification: apply one IClassTransformer to the
 * ORIGINAL bytes of one class from one jar, then run ASM's dataflow
 * verifier (CheckClassAdapter -> SimpleVerifier with frame merging) over
 * the result. Catches the VerifyError class BEFORE a campaign boot — the
 * injected-branch-without-frames trap (COMPUTE_MAXS writers), bad branch
 * targets, stack-shape errors.
 *
 * argv: <classes-dir> <jar> <target-class-internal> <transformer-fqcn>
 * Exit 0 = transformed + verified; 2 = transform ok but VERIFY FAILED;
 * 3 = transform produced no output / wrong shape; 4 = setup error.
 */
public class TransformVerify {
    public static void main(String[] args) throws Exception {
        // args: <classes-dir> <jar> <target-class> <transformer>
        //       [--set fqcn.FIELD=true]...
        String classesDir = null, jar = null, target = null,
                transformer = null, transformedName = null;
        java.util.List<String> sets = new java.util.ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            if ("--set".equals(args[i]) && i + 1 < args.length) {
                sets.add(args[++i]);
            } else if (classesDir == null) {
                classesDir = args[i];
            } else if (jar == null) {
                jar = args[i];
            } else if (target == null) {
                target = args[i];
            } else if (transformer == null) {
                transformer = args[i];
            } else if (transformedName == null) {
                transformedName = args[i]; // mapped name (retro fix 3)
            }
        }
        if (transformedName == null) {
            transformedName = target;
        }
        if (classesDir == null || jar == null || target == null
                || transformer == null) {
            System.err.println("usage: TransformVerify <classes-dir> <jar> "
                    + "<target-class-internal> <transformer-fqcn> "
                    + "[--set fqcn.FIELD=true]...");
            System.exit(4);
        }

        byte[] orig;
        try (JarFile jf = new JarFile(new File(jar))) {
            JarEntry e = jf.getJarEntry(target + ".class");
            if (e == null) {
                System.err.println("class not in jar: " + target);
                System.exit(4);
            }
            orig = read(jf.getInputStream(e));
        }

        URLClassLoaderWithDir loader =
                new URLClassLoaderWithDir(new File(classesDir));
        // --set before touching the transformer's dependencies: static
        // flags (LightAuthorityHook.ON) gate the transform, but Class.forName
        // would run the hook's <clinit> (natives + reflection probes) in a
        // plain JVM where they fail. Unsafe writes bypass <clinit>.
        sun.misc.Unsafe unsafe = getUnsafe();
        for (String spec : sets) {
            int eq = spec.lastIndexOf('=');
            int dot = spec.lastIndexOf('.', eq);
            String fqcn = spec.substring(0, dot);
            String field = spec.substring(dot + 1, eq);
            String value = spec.substring(eq + 1);
            Class<?> c = Class.forName(fqcn, false, loader);
            java.lang.reflect.Field f = c.getDeclaredField(field);
            Object base = unsafe.staticFieldBase(f);
            long offset = unsafe.staticFieldOffset(f);
            if (value.equals("true") || value.equals("false")) {
                unsafe.putBoolean(base, offset, Boolean.parseBoolean(value));
            } else if (field.toUpperCase().equals(field)) {
                unsafe.putInt(base, offset, Integer.parseInt(value));
            } else {
                unsafe.putObject(base, offset, value);
            }
            System.out.println("SET " + spec);
        }
        Class<?> t = Class.forName(transformer, true, loader);
        IClassTransformer tr =
                (IClassTransformer) t.getDeclaredConstructor().newInstance();
        // launchwrapper semantics: name = notch/internal, transformedName
        // = the MAPPED name transformers gate on. Feeding both = notch made
        // every mapped-name gate NOP (the EBS class-gate bug survived to
        // zsa9 because of exactly that).
        byte[] out = tr.transform(target, transformedName, orig);
        if (out == null || out == orig) {
            System.out.println("TRANSFORM-NOP (identity bytes returned - "
                    + "the class gate did not fire; nothing to verify)");
            System.exit(3);
        }
        System.out.println("TRANSFORMED " + out.length + " bytes (orig "
                + orig.length + ")");

        ClassReader cr = new ClassReader(out);
        StringWriter sw = new StringWriter();
        CheckClassAdapter.verify(cr, loader, false, new PrintWriter(sw));
        String text = sw.toString();
        // ASM prints "AnalyzerException" lines for every dataflow problem
        boolean failed = text.contains("AnalyzerException")
                || text.contains("VerificationError")
                || text.contains("Exception");
        System.out.println(failed ? "VERIFY-FAIL" : "VERIFY-OK");
        if (text.length() > 0) {
            System.out.println(text);
        }
        System.exit(failed ? 2 : 0);
    }

    private static sun.misc.Unsafe getUnsafe() throws Exception {
        java.lang.reflect.Field f =
                sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return (sun.misc.Unsafe) f.get(null);
    }

    private static byte[] read(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        in.close();
        return bos.toByteArray();
    }
}

class URLClassLoaderWithDir extends java.net.URLClassLoader {
    URLClassLoaderWithDir(File dir) throws Exception {
        // parent = the launcher's loader (asm + launchwrapper via -cp);
        // child = the compiled transformer classes
        super(new java.net.URL[]{dir.toURI().toURL()},
                TransformVerify.class.getClassLoader());
    }
}
