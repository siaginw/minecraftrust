package com.rustcraft.offline.oracle;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * One-off offline tooling: produces the SRG-named Minecraft server study jar from
 * the pinned vanilla jar and Forge's embedded deobfuscation mapping (already
 * decompressed to SRG text by the python harness). This is the same rename the
 * FML DeobfuscationTransformer applies at runtime, materialized so the coremod
 * sources compile offline against the pinned runtime identity. Deterministic
 * given the pinned inputs; not a runtime component.
 */
public final class SrgJarBuilder {

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException("usage: SrgJarBuilder <vanillaJar> <srgMappingText> <outJar>");
        }
        Path vanilla = Paths.get(args[0]);
        Path mappingText = Paths.get(args[1]);
        Path out = Paths.get(args[2]);

        Map<String, String> mapping = new HashMap<String, String>();
        for (String line : new String(Files.readAllBytes(mappingText), StandardCharsets.UTF_8).split("\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("CL: ")) {
                String[] parts = line.substring(4).split(" ");
                if (parts.length == 2) mapping.put(parts[0], parts[1]);
            } else if (line.startsWith("FD: ")) {
                String[] parts = line.substring(4).split(" ");
                if (parts.length == 2) {
                    String owner = parts[0].substring(0, parts[0].lastIndexOf('/'));
                    String name = parts[0].substring(parts[0].lastIndexOf('/') + 1);
                    mapping.put(owner + "." + name, parts[1].substring(parts[1].lastIndexOf('/') + 1));
                }
            } else if (line.startsWith("MD: ")) {
                String[] parts = line.substring(4).split(" ");
                if (parts.length == 4) {
                    String owner = parts[0].substring(0, parts[0].lastIndexOf('/'));
                    String name = parts[0].substring(parts[0].lastIndexOf('/') + 1);
                    String srgName = parts[2].substring(parts[2].lastIndexOf('/') + 1);
                    // ASM SimpleRemapper looks methods up as owner.name+descriptor
                    // (concatenated); keep the spaced SRG convention too.
                    mapping.put(owner + "." + name + parts[1], srgName);
                    mapping.put(owner + "." + name + " " + parts[1], srgName);
                }
            }
        }
        SimpleRemapper remapper = new SimpleRemapper(mapping);
        int classes = 0;
        try (ZipFile vanillaZip = new ZipFile(vanilla.toFile());
             ZipOutputStream outZip = new ZipOutputStream(Files.newOutputStream(out))) {
            Enumeration<? extends ZipEntry> entries = vanillaZip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!name.endsWith(".class")) continue;
                byte[] bytes = readAll(vanillaZip.getInputStream(entry));
                String outName = name;
                try {
                    ClassReader reader = new ClassReader(bytes);
                    String internalName = name.substring(0, name.length() - 6);
                    String mapped = mapping.get(internalName);
                    outName = (mapped == null ? internalName : mapped) + ".class";
                    ClassWriter writer = new ClassWriter(0);
                    reader.accept(new ClassRemapper(writer, remapper), 0);
                    bytes = writer.toByteArray();
                } catch (RuntimeException broken) {
                    System.out.println("SRG-JAR: unremappable entry skipped: " + name + " (" + broken + ")");
                }
                outZip.putNextEntry(new ZipEntry(outName));
                outZip.write(bytes);
                outZip.closeEntry();
                classes++;
            }
        }
        System.out.println("SRG-JAR: wrote " + classes + " classes to " + out
                + " (mapping entries " + mapping.size() + ")");
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] block = new byte[65536];
        int read;
        while ((read = in.read(block)) > 0) buffer.write(block, 0, read);
        in.close();
        return buffer.toByteArray();
    }
}
