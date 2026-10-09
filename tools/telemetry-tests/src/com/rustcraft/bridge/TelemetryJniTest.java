package com.rustcraft.bridge;

import java.io.File;
import java.lang.reflect.Field;
import java.util.Arrays;
import sun.misc.Unsafe;

/** Real Java 8 JNI and heap critical-array calls; no Forge or simulated JNI. */
public final class TelemetryJniTest {
    private static final Unsafe MEMORY = unsafe();
    private static int checks;
    private static final int SNAPSHOT_BYTES = 312;

    private static Unsafe unsafe() {
        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            return (Unsafe) field.get(null);
        } catch (Exception error) { throw new AssertionError(error); }
    }
    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }
    private static long word(long address, int index) {
        long value = 0;
        for (int i = 0; i < 8; i++) value |= (MEMORY.getByte(address + index * 8L + i) & 255L) << (i * 8);
        return value;
    }
    private static long[] snapshot(int operation) {
        long address = MEMORY.allocateMemory(SNAPSHOT_BYTES + 16);
        try {
            MEMORY.setMemory(address, SNAPSHOT_BYTES + 16, (byte) 0xa5);
            check(NativeFfiTelemetry.readOperationV2(operation, address, SNAPSHOT_BYTES + 16) == SNAPSHOT_BYTES, "fixed snapshot length");
            long[] words = new long[39];
            for (int i = 0; i < words.length; i++) words[i] = word(address, i);
            check(words[0] == 2 && words[1] == operation, "version and operation identity");
            for (int i = SNAPSHOT_BYTES; i < SNAPSHOT_BYTES + 16; i++) check(MEMORY.getByte(address + i) == (byte) 0xa5, "snapshot tail guard");
            return words;
        } finally { MEMORY.freeMemory(address); }
    }
    private static void knownDelta(long[] before, long[] after, int field, long amount) {
        int base = 3 + field * 4;
        check(after[base] - before[base] == amount, "known byte sum field " + field);
        check(after[base + 1] - before[base + 1] == 1, "one known sample field " + field);
        check(after[base + 2] == before[base + 2], "no unknown sample field " + field);
        check(after[base + 3] == 0, "no overflow field " + field);
    }
    private static void unknownDelta(long[] before, long[] after, int field) {
        int base = 3 + field * 4;
        check(after[base + 1] == before[base + 1], "no fabricated known sample");
        check(after[base + 2] - before[base + 2] == 1, "unknown sample explicit");
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected exact DLL path");
        System.load(new File(args[0]).getCanonicalPath());
        check(NativeFfiTelemetry.schemaVersion() == 2, "schema version");
        long input = MEMORY.allocateMemory(263), output = MEMORY.allocateMemory(512);
        try {
            MEMORY.setMemory(input, 263, (byte) 0);
            MEMORY.putByte(input + 1, (byte) 1); // staging schema 1
            MEMORY.putByte(input + 4, (byte) 1); // full chunk, no sections
            for (int i = 0; i < 256; i++) MEMORY.putByte(input + 6 + i, (byte) i);
            MEMORY.setMemory(output, 512, (byte) 0xa5);
            long[] before = snapshot(1);
            check(NativeChunkPacket.encodeSections(input, 262, output, 512) == 256, "direct encode result");
            for (int i = 0; i < 256; i++) check(MEMORY.getByte(output + i) == (byte) i, "independent biome payload");
            for (int i = 256; i < 512; i++) check(MEMORY.getByte(output + i) == (byte) 0xa5, "direct tail guard");
            long[] after = snapshot(1);
            check(after[2] - before[2] == 1, "one completed direct call");
            knownDelta(before, after, 0, 262);
            knownDelta(before, after, 1, 256);
            knownDelta(before, after, 3, 774);
            knownDelta(before, after, 4, 0);
            knownDelta(before, after, 5, 0);
            unknownDelta(before, after, 2);
            check(after[32] - before[32] == 1, "native completion fallback bucket");
            check(Arrays.equals(after, snapshot(1)), "diagnostic reads do not count themselves");

            before = after;
            check(NativeChunkPacket.encodeSections(input, 263, output, 512) == -6, "trailing staging rejected");
            after = snapshot(1);
            knownDelta(before, after, 0, 263);
            knownDelta(before, after, 1, 0);
            check(after[35] - before[35] == 1, "corrupt input classified");
            before = after;
            check(NativeChunkPacket.encodeSections(input, 262, output, 255) == -2, "capacity rejected");
            after = snapshot(1);
            knownDelta(before, after, 0, 0);
            knownDelta(before, after, 1, 0);
            knownDelta(before, after, 3, 0);
            check(after[34] - before[34] == 1, "capacity classified");
            check(NativeChunkPacket.encodeSections(input, 262, output, 512) == 256, "retry succeeds");

            before = snapshot(3);
            check(NativeChunkPacket.predictOutputLen(input, 262) == 256, "prediction");
            after = snapshot(3);
            knownDelta(before, after, 0, 262);
            knownDelta(before, after, 1, 0); // scalar prediction is not serialized output
            byte[] heap = new byte[256];
            before = snapshot(2);
            check(NativeChunkPacket.encodeSectionsIntoArray(input, 262, heap) == 256, "critical-array encode");
            for (int i = 0; i < 256; i++) check(heap[i] == (byte) i, "heap payload");
            after = snapshot(2);
            knownDelta(before, after, 0, 262);
            knownDelta(before, after, 1, 256);
            knownDelta(before, after, 3, 518);
            knownDelta(before, after, 4, 0);
            unknownDelta(before, after, 2);
            unknownDelta(before, after, 5); // VM critical access does not prove allocation bytes
            byte[] wrong = new byte[255];
            Arrays.fill(wrong, (byte) 0xa5);
            before = after;
            check(NativeChunkPacket.encodeSectionsIntoArray(input, 262, wrong) == -2, "critical capacity error");
            after = snapshot(2);
            knownDelta(before, after, 1, 0);
            knownDelta(before, after, 3, 262);
            for (byte value : wrong) check(value == (byte) 0xa5, "failed heap guard");

            MEMORY.setMemory(output, 512, (byte) 0xa5);
            check(NativeFfiTelemetry.readOperationV2(1, output, 311) == -1, "diagnostic capacity rejected");
            check(NativeFfiTelemetry.readOperationV2(511, output, 512) == -1, "unregistered operation rejected");
            check(NativeFfiTelemetry.readOperationV2(1, 0, 512) == -1, "null diagnostic rejected");
            for (int i = 0; i < 512; i++) check(MEMORY.getByte(output + i) == (byte) 0xa5, "failed diagnostic has no writes");
            System.out.println("PASS TelemetryJniTest groups=5 checks=" + checks);
        } finally { MEMORY.freeMemory(input); MEMORY.freeMemory(output); }
    }
}
