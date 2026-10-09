package com.rustcraft.bridge.capture;

import java.io.File;
import java.lang.reflect.Field;
import java.util.Arrays;
import com.rustcraft.bridge.PacketEncodeResultV2;
import sun.misc.Unsafe;

/** Actual DLL tests for SYNTHETIC owned Java capture -> one native V2 result. */
public final class OwnedSnapshotJniTest {
    private static final Unsafe MEMORY = unsafe();
    private static final int CAPACITY = 262144;
    private static int groups, checks;

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected exact DLL path");
        File dll = new File(args[0]).getCanonicalFile();
        if (!dll.isFile()) throw new IllegalArgumentException("Missing DLL: " + dll);
        System.load(dll.getPath());
        masksAndFlags();
        capacityAndRetry();
        ownedStateSurvivesLiveMutation();
        transportRejections();
        System.out.println("PASS OwnedSnapshotJniTest groups=" + groups + " checks=" + checks);
    }

    private static SyntheticCaptureSource source(int mask, boolean full, boolean sky) {
        SyntheticCaptureSource source = new SyntheticCaptureSource();
        source.fullChunk = full;
        source.skylight = sky;
        source.incarnation = 31;
        source.generation = 57;
        source.requestedFilter = mask;
        for (int y = 0; y < 16; y++) if ((mask & (1 << y)) != 0) {
            source.sections[y] = new SyntheticCaptureSource.MutableSection().fill(y + 1);
            Arrays.fill(source.sections[y].blockLight, (byte) y);
            Arrays.fill(source.sections[y].skyLight, (byte) (255 - y));
        }
        Arrays.fill(source.biomes, (byte) 42);
        return source;
    }

    private static OwnedPacketSnapshot capture(SyntheticCaptureSource source) {
        CaptureContract.Result result = SnapshotCapture.capture(source, source.ownerContext());
        check(result.accepted(), "synthetic Java capture accepted");
        check(!result.productionAuthorityEligible(), "no production authority");
        return result.snapshot();
    }

    private static void masksAndFlags() {
        for (int mask : new int[] {0, 1, 0x8000, 0x8421, 0x1F, 0xFFFF}) {
            for (boolean full : new boolean[] {false, true}) {
                for (boolean sky : new boolean[] {false, true}) {
                    try (Buffers buffers = new Buffers(capture(source(mask, full, sky)).toTransportBytes())) {
                        PacketEncodeResultV2 result = buffers.encode(CAPACITY);
                        check(result.isSuccess(), "owned encode success");
                        check(result.emittedMask() == mask, "same operation exact mask");
                        int sectionBytes = 1 + 1 + 2 + 2 + 2048 + 2048 + (sky ? 2048 : 0);
                        check(result.bytesWritten() == Integer.bitCount(mask) * sectionBytes + (full ? 256 : 0), "uniform fixture byte count");
                    }
                }
            }
        }
        SyntheticCaptureSource empty = source(1, false, false);
        empty.sections[0].fill(0);
        try (Buffers buffers = new Buffers(capture(empty).toTransportBytes())) {
            check(buffers.encode(CAPACITY).emittedMask() == 1, "partial present-empty serialized");
        }
        groups++;
    }

    private static void capacityAndRetry() {
        try (Buffers buffers = new Buffers(capture(source(0x1F, true, true)).toTransportBytes())) {
            PacketEncodeResultV2 failure = buffers.encode(1);
            check(!failure.isSuccess() && failure.failure() == PacketEncodeResultV2.Failure.OUTPUT_CAPACITY, "capacity explicit failure");
            try { failure.bytesWritten(); throw new AssertionError("Failure exposed bytes"); }
            catch (IllegalStateException expected) { checks++; }
            PacketEncodeResultV2 success = buffers.encode(CAPACITY);
            check(success.isSuccess() && success.emittedMask() == 0x1F, "retry emits complete result");
        }
        groups++;
    }

    private static void ownedStateSurvivesLiveMutation() {
        SyntheticCaptureSource live = source(1, true, true);
        OwnedPacketSnapshot owned = capture(live);
        try (Buffers buffers = new Buffers(owned.toTransportBytes())) {
            PacketEncodeResultV2 first = buffers.encode(CAPACITY);
            byte[] before = buffers.bytes(first.bytesWritten());
            live.sections[0].fill(16);
            live.sections[0].blockLight[0] = 8;
            live.biomes[0] = 9;
            live.sections[5] = new SyntheticCaptureSource.MutableSection().fill(7);
            live.requestedFilter = 0x3F;
            PacketEncodeResultV2 after = buffers.encode(CAPACITY);
            check(after.emittedMask() == first.emittedMask() && after.bytesWritten() == first.bytesWritten(), "input independent of retained/live changes");
            check(Arrays.equals(before, buffers.bytes(after.bytesWritten())), "owned bytes stable after live mutation");
        }
        groups++;
    }

    private static void transportRejections() {
        byte[] valid = capture(source(1, true, true)).toTransportBytes();
        for (int kind = 0; kind < 5; kind++) {
            byte[] bad = valid.clone();
            if (kind == 0) bad[0] = 'X';
            if (kind == 1) bad[11] = 3; // unsupported JEID representation
            if (kind == 2) bad[39] = 3; // accepted mask claims absent section 1
            if (kind == 3) bad[134] = 1; // logical state > u16, no truncation
            if (kind == 4) bad = Arrays.copyOf(bad, bad.length - 1);
            try (Buffers buffers = new Buffers(bad)) {
                PacketEncodeResultV2 result = buffers.encode(CAPACITY);
                check(!result.isSuccess(), "malformed/unsupported transport rejects " + kind);
            }
        }
        groups++;
    }

    private static final class Buffers implements AutoCloseable {
        final long input, output;
        final int length;
        Buffers(byte[] ownedTransport) {
            length = ownedTransport.length;
            input = MEMORY.allocateMemory(length);
            output = MEMORY.allocateMemory(CAPACITY);
            for (int i = 0; i < length; i++) MEMORY.putByte(input + i, ownedTransport[i]);
            MEMORY.setMemory(output, CAPACITY, (byte) 0xCC);
        }
        PacketEncodeResultV2 encode(int capacity) {
            return PacketEncodeResultV2.decode(OwnedSnapshotBridge.encodeOwnedV1(input, length, output, capacity));
        }
        byte[] bytes(int count) {
            byte[] bytes = new byte[count];
            for (int i = 0; i < count; i++) bytes[i] = MEMORY.getByte(output + i);
            return bytes;
        }
        public void close() { MEMORY.freeMemory(output); MEMORY.freeMemory(input); }
    }

    private static Unsafe unsafe() {
        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            return (Unsafe) field.get(null);
        } catch (ReflectiveOperationException error) { throw new ExceptionInInitializerError(error); }
    }

    private static void check(boolean condition, String label) {
        checks++;
        if (!condition) throw new AssertionError(label);
    }
}
