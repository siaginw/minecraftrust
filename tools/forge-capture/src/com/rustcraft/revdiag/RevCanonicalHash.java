package com.rustcraft.revdiag;

import com.rustcraft.coremod.LiveHookSupport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

/**
 * Prints name=canonicalSha256 lines for every class file under a dump
 * directory, using the exact canonical identity the transformers verify.
 */
public final class RevCanonicalHash {
  public static void main(String[] args) throws Exception {
    Path dump = Paths.get(args[0]);
    try (Stream<Path> files = Files.walk(dump)) {
      files.filter(f -> f.toString().endsWith(".class")).sorted().forEach(f -> {
        try {
          String internal = dump.relativize(f).toString().replace('\\', '/');
          String name = internal.substring(0, internal.length() - ".class".length())
              .replace('/', '.');
          byte[] bytes = Files.readAllBytes(f);
          System.out.println(name + "=" + LiveHookSupport.canonicalSha256(bytes));
        } catch (Exception failure) {
          System.out.println(f + "=ERROR " + failure);
        }
      });
    }
  }
}
