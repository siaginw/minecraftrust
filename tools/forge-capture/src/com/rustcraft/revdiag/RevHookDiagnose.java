package com.rustcraft.revdiag;

import com.rustcraft.coremod.LiveChunkOwnershipTransformer;
import com.rustcraft.coremod.LiveChunkPublicationTransformer;
import com.rustcraft.coremod.SPacketChunkDataTransformer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Offline diagnostic: feeds one dumped pre-hook class through the qualified
 * transformer chain directly (no launchwrapper), so a ProfileFailure is
 * observed verbatim instead of being masked by the launcher.
 */
public final class RevHookDiagnose {
  public static void main(String[] args) throws Exception {
    String dumpDir = System.getProperty("rustcraft.revPreHookDump");
    String outDir = System.getProperty("rustcraft.revPostHookOut");
    Path dump = Paths.get(dumpDir);
    Path out = Paths.get(outDir);
    Files.createDirectories(out);
    for (String name : args) {
      String internal = name.replace('.', '/');
      Path file = dump.resolve(internal + ".class");
      byte[] bytes = Files.readAllBytes(file);
      byte[] current = bytes;
      String[] chain = {
          "com.rustcraft.coremod.LiveChunkOwnershipTransformer",
          "com.rustcraft.coremod.LiveChunkPublicationTransformer",
          "com.rustcraft.coremod.SPacketChunkDataTransformer"};
      try {
        for (String transformer : chain) {
          Object instance = Class.forName(transformer).getDeclaredConstructor().newInstance();
          current = (byte[]) Class.forName(transformer)
              .getMethod("transform", String.class, String.class, byte[].class)
              .invoke(instance, name, name, current);
          if (current == null) {
            current = bytes; // transformer declined (not in its plan slice)
          }
        }
        Path target = out.resolve(internal + ".class");
        Files.createDirectories(target.getParent());
        Files.write(target, current);
        System.out.println("HOOKED " + name + " -> " + current.length + " bytes");
      } catch (Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null) cause = cause.getCause();
        System.out.println("FAILED " + name + ": " + failure);
        System.out.println("  root cause: " + cause);
      }
    }
  }
}
