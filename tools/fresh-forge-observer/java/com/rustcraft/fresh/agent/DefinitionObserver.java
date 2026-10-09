package com.rustcraft.fresh.agent;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Passive definition observer for a bounded, explicit class inventory. */
public final class DefinitionObserver implements ClassFileTransformer {
    private static final Set<String> REQUIRED = new HashSet<String>();
    private static final Map<String, Map<String, Object>> ROWS = new TreeMap<String, Map<String, Object>>();
    private static final Map<String, ClassLoader> OWNERS = new TreeMap<String, ClassLoader>();
    private static final IdentityHashMap<ClassLoader, Integer> LOADERS = new IdentityHashMap<ClassLoader, Integer>();
    private static String failure;
    private static long bytesWritten;

    public static void premain(String arguments, Instrumentation instrumentation) throws Exception {
        for (String name : Files.readAllLines(Paths.get(System.getProperty("rustcraft.fresh.required")), StandardCharsets.UTF_8)) {
            if (!name.matches("[A-Za-z_$][A-Za-z0-9_$]*(/[A-Za-z_$][A-Za-z0-9_$]*)+") || !REQUIRED.add(name))
                throw new IllegalArgumentException("invalid or duplicate required class");
        }
        if (REQUIRED.isEmpty() || REQUIRED.size() > 256) throw new IllegalArgumentException("required class bound");
        instrumentation.addTransformer(new DefinitionObserver(), false);
    }

    public byte[] transform(ClassLoader loader, String name, Class<?> redefined, ProtectionDomain domain, byte[] bytes) {
        if (!REQUIRED.contains(name)) return null;
        synchronized (ROWS) {
            try {
                if (failure != null) return null;
                if (loader == null || redefined != null) throw new IllegalStateException("redefined/bootstrap target: " + name);
                Map<String, Object> loaderFact = loader(loader);
                String key = LOADERS.get(loader) + ":" + name;
                if (ROWS.containsKey(key) || ROWS.size() >= 4096) throw new IllegalStateException("duplicate/excess target in same loader: " + key);
                if (bytes.length == 0 || bytes.length > 16 * 1024 * 1024 || bytesWritten + bytes.length > 256 * 1024 * 1024)
                    throw new IllegalStateException("definition byte bound");
                String file = "loader-" + LOADERS.get(loader) + "/" + name + ".class";
                Path out = Paths.get(System.getProperty("rustcraft.fresh.dump"), file);
                Files.createDirectories(out.getParent());
                Files.write(out, bytes, StandardOpenOption.CREATE_NEW);
                Map<String, Object> row = new LinkedHashMap<String, Object>();
                row.put("name", name); row.put("file", file); row.put("raw_sha256", sha256(bytes)); row.put("bytes", bytes.length);
                row.put("loader", loaderFact);
                row.put("code_source", domain == null || domain.getCodeSource() == null || domain.getCodeSource().getLocation() == null ? null : domain.getCodeSource().getLocation().toExternalForm());
                ROWS.put(key, row); OWNERS.put(key, loader); bytesWritten += bytes.length;
            } catch (Throwable error) { failure = error.toString(); }
        }
        return null;
    }

    public static Map<String, Object> loader(ClassLoader loader) {
        synchronized (ROWS) {
            List<Map<String, Object>> chain = new ArrayList<Map<String, Object>>();
            Set<ClassLoader> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<ClassLoader, Boolean>());
            for (ClassLoader value = loader; value != null; value = value.getParent()) {
                if (!seen.add(value) || chain.size() >= 32) throw new IllegalStateException("loader chain bound");
                Integer id = LOADERS.get(value);
                if (id == null) { id = LOADERS.size() + 1; LOADERS.put(value, id); }
                Map<String, Object> item = new LinkedHashMap<String, Object>();
                item.put("process_local_id", id); item.put("class", value.getClass().getName()); chain.add(item);
            }
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("chain", chain); result.put("bootstrap_terminus", true); return result;
        }
    }

    public static List<Map<String, Object>> verifiedDefinitions(List<Class<?>> loaded) {
        synchronized (ROWS) {
            if (failure != null) throw new IllegalStateException("observer failed: " + failure);
            Set<String> names = new HashSet<String>();
            List<Map<String, Object>> selected = new ArrayList<Map<String, Object>>();
            for (Class<?> type : loaded) {
                String name = type.getName().replace('.', '/');
                String key = LOADERS.get(type.getClassLoader()) + ":" + name;
                if (!names.add(name) || !ROWS.containsKey(key) || OWNERS.get(key) != type.getClassLoader())
                    throw new IllegalStateException("missing/foreign actual loaded class: " + name);
                selected.add(ROWS.get(key));
            }
            if (!names.equals(REQUIRED)) throw new IllegalStateException("incomplete definition inventory");
            return selected;
        }
    }

    public static List<Map<String, Object>> allDefinitions() {
        synchronized (ROWS) {
            if (failure != null) throw new IllegalStateException(failure);
            return new ArrayList<Map<String, Object>>(ROWS.values());
        }
    }

    public static String sha256(byte[] bytes) throws Exception {
        char[] digits = "0123456789abcdef".toCharArray();
        StringBuilder result = new StringBuilder(64);
        for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) {
            result.append(digits[(value & 255) >>> 4]); result.append(digits[value & 15]);
        }
        return result.toString();
    }
}
