package com.rustcraft.offline.agent;

import java.lang.instrument.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Passive final-definition observer. Never changes bytes, retransforms, or redefines a class. */
public final class ObservationAgent implements ClassFileTransformer {
    private static final Map<String, String> HASHES = new TreeMap<>();
    private static final Map<String, String> LOADERS = new TreeMap<>();
    private static volatile String failure;

    public static void premain(String args, Instrumentation instrumentation) {
        instrumentation.addTransformer(new ObservationAgent(), false);
    }

    public byte[] transform(ClassLoader loader, String name, Class<?> redefined,
                            ProtectionDomain domain, byte[] bytes) {
        if (name == null || loader == null
                || !"net.minecraft.launchwrapper.LaunchClassLoader".equals(loader.getClass().getName())
                || !(name.startsWith("net/minecraft/") || name.startsWith("net/minecraftforge/"))) return null;
        try {
            String transformedName = name.replace('/', '.');
            // retro 2026-10-09: with -Drustcraft.dumpDir unset, Paths.get(null,
            // ...) on this JDK8 build coerces the null into a LITERAL "null"
            // directory (proven empirically) — every transformed class landed
            // under <cwd>/null/. When no dump root is configured, observe
            // hashes in memory only; file dumps require the property.
            String dumpDir = System.getProperty("rustcraft.dumpDir");
            Path out = dumpDir == null ? null
                    : Paths.get(dumpDir, name + ".class");
            String hash = sha256(bytes);
            synchronized (HASHES) {
                String previous = HASHES.get(transformedName);
                if (previous != null && !previous.equals(hash)) {
                    throw new IllegalStateException("Conflicting definition " + transformedName);
                }
                if (previous == null) {
                    if (out != null) {
                        Files.createDirectories(out.getParent());
                        Files.write(out, bytes, StandardOpenOption.CREATE_NEW);
                    }
                    HASHES.put(transformedName, hash);
                    LOADERS.put(transformedName, loader.getClass().getName());
                }
            }
        } catch (Exception error) { failure = error.toString(); }
        return null;
    }

    public static Map<String, String> hashes() {
        if (failure != null) throw new IllegalStateException("Definition observation failed: " + failure);
        synchronized (HASHES) { return new TreeMap<>(HASHES); }
    }

    public static Map<String, String> loaders() {
        synchronized (HASHES) { return new TreeMap<>(LOADERS); }
    }

    public static String sha256(byte[] bytes) throws Exception {
        StringBuilder result = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) {
            result.append(String.format(Locale.ROOT, "%02x", value & 255));
        }
        return result.toString();
    }
}
