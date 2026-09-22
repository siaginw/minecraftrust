package com.rustcraft.test;

import com.rustcraft.coremod.WorldgenShadowTransformer;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

public class TestTransform {
    public static void main(String[] args) throws Exception {
        JarFile jf = new JarFile("third_party_reference/minecraft/minecraft_server.1.12.2.srg.jar");
        ZipEntry ze = jf.getEntry("net/minecraft/world/gen/ChunkGeneratorOverworld.class");
        InputStream is = jf.getInputStream(ze);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int r;
        while ((r = is.read(buf)) != -1) baos.write(buf, 0, r);
        byte[] original = baos.toByteArray();
        System.out.println("Original bytes: " + original.length);

        String[] modes = {"OFF", "OFF_MEASURE", "SHADOW", "ON_EXPERIMENTAL"};
        for (String mode : modes) {
            System.setProperty("minecraftrust.worldgen", mode);
            WorldgenShadowTransformer xform = new WorldgenShadowTransformer();
            byte[] transformed = xform.transform(
                    "net.minecraft.world.gen.ChunkGeneratorOverworld",
                    "net.minecraft.world.gen.ChunkGeneratorOverworld",
                    original);
            System.out.println("Mode " + mode + " -> status: " + WorldgenShadowTransformer.lastStatus
                    + " bytes: " + transformed.length);

            ClassLoader cl = new ClassLoader(TestTransform.class.getClassLoader()) {
                public Class<?> define(String name, byte[] b) {
                    return defineClass(name, b, 0, b.length);
                }
            };
            try {
                Method m = cl.getClass().getMethod("define", String.class, byte[].class);
                Class<?> c = (Class<?>) m.invoke(cl, "net.minecraft.world.gen.ChunkGeneratorOverworld", transformed);
                System.out.println("  Class loaded & verified: " + c.getName());
                // Assert pre-population oracle hook present in PATCHED modes
                // (OFF returns unmodified class by design).
                if (!"OFF".equals(mode)) {
                    String str = new String(transformed, "ISO-8859-1");
                    boolean hasPrePop = str.indexOf("prePopOracle") != -1;
                    if (!hasPrePop) {
                        System.err.println("  ERROR mode " + mode + ": prePopOracle hook missing");
                        System.exit(1);
                    }
                    System.out.println("  prePopOracle hook present: " + hasPrePop);
                }
            } catch (Throwable t) {
                System.err.println("  Verification failed for mode " + mode + ": " + t);
                System.exit(1);
            }
        }
        System.out.println("ALL TRANSFORM MODES VERIFIED CLEANLY.");
    }
}
