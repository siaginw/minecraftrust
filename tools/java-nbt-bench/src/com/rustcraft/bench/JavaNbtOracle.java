package com.rustcraft.bench;

import java.io.*;
import java.util.*;
import net.minecraft.nbt.*;

public class JavaNbtOracle {
    public static void main(String[] args) {
        if (args.length == 0) {
            System.err.println("Usage: JavaNbtOracle <command> [args...]");
            System.err.println("Commands: roundtrip, inspect, mutf8-read, check-depth, test-tracker");
            System.exit(1);
        }

        String cmd = args[0];
        try {
            if ("roundtrip".equals(cmd)) {
                File in = new File(args[1]);
                File out = new File(args[2]);
                NBTTagCompound tag;
                try (DataInputStream dis = new DataInputStream(new BufferedInputStream(new FileInputStream(in)))) {
                    tag = CompressedStreamTools.func_152456_a(dis, NBTSizeTracker.field_152451_a);
                }
                try (DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(out)))) {
                    CompressedStreamTools.func_74800_a(tag, dos);
                }
                System.out.println("OK: roundtrip successful");
            } else if ("inspect".equals(cmd)) {
                File in = new File(args[1]);
                NBTTagCompound tag;
                try (DataInputStream dis = new DataInputStream(new BufferedInputStream(new FileInputStream(in)))) {
                    tag = CompressedStreamTools.func_152456_a(dis, NBTSizeTracker.field_152451_a);
                }
                System.out.println("TAG_COUNT=" + tag.func_150296_c().size());
                for (String key : tag.func_150296_c()) {
                    NBTBase child = tag.func_74781_a(key);
                    System.out.println("KEY=" + key + " TYPE=" + child.func_74732_a());
                }
            } else if ("mutf8-read".equals(cmd)) {
                File in = new File(args[1]);
                try (DataInputStream dis = new DataInputStream(new BufferedInputStream(new FileInputStream(in)))) {
                    String str = dis.readUTF();
                    System.out.println("LEN=" + str.length());
                    System.out.println("CHARS=" + escapeJava(str));
                }
            } else if ("check-depth".equals(cmd)) {
                int depth = Integer.parseInt(args[1]);
                NBTTagCompound root = new NBTTagCompound();
                NBTTagCompound curr = root;
                for (int i = 0; i < depth; i++) {
                    NBTTagCompound child = new NBTTagCompound();
                    curr.func_74782_a("nest", child);
                    curr = child;
                }
                // Try write
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                DataOutputStream dos = new DataOutputStream(baos);
                CompressedStreamTools.func_74800_a(root, dos);
                System.out.println("WRITE_OK size=" + baos.size());

                // Try read with default 512-depth check
                ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
                DataInputStream dis = new DataInputStream(bais);
                try {
                    NBTTagCompound readBack = CompressedStreamTools.func_152456_a(dis, NBTSizeTracker.field_152451_a);
                    System.out.println("READ_OK");
                } catch (Exception ex) {
                    System.out.println("READ_FAIL: " + ex.getMessage());
                }
            } else if ("test-tracker".equals(cmd)) {
                File in = new File(args[1]);
                long maxBytes = Long.parseLong(args[2]);
                try (DataInputStream dis = new DataInputStream(new BufferedInputStream(new FileInputStream(in)))) {
                    NBTSizeTracker tracker = new NBTSizeTracker(maxBytes);
                    CompressedStreamTools.func_152456_a(dis, tracker);
                    System.out.println("TRACKER_OK");
                } catch (Exception ex) {
                    System.out.println("TRACKER_FAIL: " + ex.getMessage());
                }
            } else {
                System.err.println("Unknown command: " + cmd);
                System.exit(1);
            }
        } catch (Exception e) {
            System.err.println("ERROR: " + e.getMessage());
            e.printStackTrace();
            System.exit(2);
        }
    }

    private static String escapeJava(String s) {
        StringBuilder b = new StringBuilder();
        int[] cps = s.codePoints().toArray();
        for (int cp : cps) {
            if (cp >= 32 && cp < 127) {
                b.append((char) cp);
            } else {
                b.append(String.format("\\u%04x", cp));
            }
        }
        return b.toString();
    }
}
