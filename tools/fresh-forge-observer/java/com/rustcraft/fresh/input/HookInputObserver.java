package com.rustcraft.fresh.input;

import com.rustcraft.fresh.agent.DefinitionObserver;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.launchwrapper.IClassTransformer;

/** Diagnostic capture at the exact input boundary; never changes or admits bytes. */
public final class HookInputObserver implements IClassTransformer {
    private static final Set<String> REQUIRED = new HashSet<String>();
    private static long total;
    private static int count;
    static {
        try {
            List<String> names = Files.readAllLines(Paths.get(System.getProperty("rustcraft.fresh.required")), StandardCharsets.UTF_8);
            for (String name : names) REQUIRED.add(name.replace('/', '.'));
        } catch (Exception failure) { throw new ExceptionInInitializerError(failure); }
    }
    public synchronized byte[] transform(String name, String transformedName, byte[] bytes) {
        if (bytes == null || !REQUIRED.contains(transformedName)) return bytes;
        try {
            if (count >= 4096 || bytes.length > 16 * 1024 * 1024 || total + bytes.length > 256 * 1024 * 1024)
                throw new IllegalStateException("hook input capture bound");
            Path directory = Paths.get(System.getProperty("rustcraft.fresh.hookInputDir"));
            Files.createDirectories(directory);
            String file = String.format(java.util.Locale.ROOT, "%04d.bin", count++);
            Files.write(directory.resolve(file), bytes, StandardOpenOption.CREATE_NEW);
            String row = transformedName.replace('.', '/') + "\t" + file + "\t" + DefinitionObserver.sha256(bytes) + "\n";
            Files.write(directory.resolve("inputs.tsv"), row.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            total += bytes.length;
        } catch (Exception failure) { throw new IllegalStateException("hook input observation failed", failure); }
        return bytes;
    }
}
