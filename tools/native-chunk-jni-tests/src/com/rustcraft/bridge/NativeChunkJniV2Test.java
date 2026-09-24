package com.rustcraft.bridge;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;
import sun.misc.Unsafe;

/**
 * Real-DLL Java 8 tests using owned synthetic buffers, with no Minecraft jars.
 * These test the JNI contract, not Forge integration or coherent Java capture.
 * Missing-section corruption and panic injection belong to Rust tests rather
 * than adding artificial fault injection exports to the production JNI ABI.
 */
public final class NativeChunkJniV2Test {
    private static final Unsafe MEMORY = unsafe();
    private static final int DIM = 991;
    private static final int CAPACITY = 262144;
    private static int nextX;
    private static int checks;

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected exact native DLL path");
        File dll = new File(args[0]).getCanonicalFile();
        if (!dll.isFile()) throw new IllegalArgumentException("Missing DLL: " + dll);
        System.load(dll.getPath());
        System.out.println("DLL=" + dll + " SHA256=" + sha256(Files.readAllBytes(dll.toPath())));
        check(NativeChunkBridge.isAvailable(), "bridge loaded selected library");
        check(NativeChunkBridge.getRegisteredCount() == 0, "isolated fresh JVM registry");
        emptyPayloads();
        sparsePayloadAndLegacyParity();
        allSectionsAndFlagCombinations();
        capacityFailuresAndRetry();
        invalidArguments();
        generationAndLifecycleFailures();
        check(NativeChunkBridge.getRegisteredCount() == 0, "all fixture handles unloaded");
        check(NativeChunkBridge.PACKET_ENCODED_CHUNKS.get() == 0,
                "V2 never updates legacy Java packet metrics");
        System.out.println("PASS NativeChunkJniV2Test groups=6 checks=" + checks);
    }

    private static void emptyPayloads() {
        try (Fixture fixture = new Fixture()) {
            PacketEncodeResultV2 empty = fixture.encode(false, false, CAPACITY);
            check(empty.isSuccess() && empty.bytesWritten() == 0 && empty.emittedMask() == 0,
                    "empty non-full success is distinct from failure");
            PacketEncodeResultV2 full = fixture.encode(true, true, CAPACITY);
            check(full.bytesWritten() == 256 && full.emittedMask() == 0, "biomes-only success");
            scan(fixture.bytes(full), full.emittedMask(), 0, true, true);
        }
    }

    private static void sparsePayloadAndLegacyParity() {
        try (Fixture fixture = new Fixture()) {
            fixture.populate(0x8421);
            PacketEncodeResultV2 first = fixture.encode(true, true, CAPACITY);
            byte[] firstBytes = fixture.bytes(first);
            scan(firstBytes, first.emittedMask(), 0x8421, true, true);
            // Separate deterministic parity run only; no independently queried
            // mask is ever used as metadata for an encode result.
            int legacyCount = NativeChunkBridge.encodePacketPayload(DIM, fixture.x, 0,
                    fixture.generation, (byte) 1, (byte) 1, fixture.output, CAPACITY);
            check(legacyCount == first.bytesWritten(), "legacy byte count preserved");
            check(Arrays.equals(firstBytes, fixture.bytes(legacyCount)), "legacy payload preserved");
            fixture.refresh(5, false);
            fixture.refresh(7, true);
            PacketEncodeResultV2 second = fixture.encode(true, true, CAPACITY);
            scan(fixture.bytes(second), second.emittedMask(), 0x8481, true, true);
            check(first.emittedMask() == 0x8421 && first.bytesWritten() == firstBytes.length,
                    "old result remains associated with its own bytes after refresh");
            scan(firstBytes, first.emittedMask(), 0x8421, true, true);
        }
    }

    private static void allSectionsAndFlagCombinations() {
        for (int mask : new int[] {0x001F, 0xFFFF}) {
            try (Fixture fixture = new Fixture()) {
                fixture.populate(mask);
                for (boolean sky : new boolean[] {false, true}) {
                    for (boolean full : new boolean[] {false, true}) {
                        PacketEncodeResultV2 result = fixture.encode(sky, full, CAPACITY);
                        scan(fixture.bytes(result), result.emittedMask(), mask, sky, full);
                    }
                }
            }
        }
    }

    private static void capacityFailuresAndRetry() {
        try (Fixture fixture = new Fixture()) {
            fixture.populate(0x001F);
            failure(fixture.encode(true, true, 1), PacketEncodeResultV2.Failure.OUTPUT_CAPACITY);
            PacketEncodeResultV2 valid = fixture.encode(true, true, CAPACITY);
            scan(fixture.bytes(valid), valid.emittedMask(), 0x001F, true, true);
            // All section bytes fit; the final biome tail does not. The output
            // is invalid scratch even though this late failure wrote section data.
            failure(fixture.encode(true, true, valid.bytesWritten() - 1),
                    PacketEncodeResultV2.Failure.OUTPUT_CAPACITY);
            PacketEncodeResultV2 retry = fixture.encode(true, true, CAPACITY);
            scan(fixture.bytes(retry), retry.emittedMask(), 0x001F, true, true);
        }
    }

    private static void invalidArguments() {
        try (Fixture fixture = new Fixture()) {
            failure(raw(fixture, fixture.generation, 0, CAPACITY, (byte) 0, (byte) 0),
                    PacketEncodeResultV2.Failure.INVALID_ARGUMENT);
            failure(fixture.encode(false, false, 0), PacketEncodeResultV2.Failure.INVALID_ARGUMENT);
            failure(fixture.encode(false, false, -1), PacketEncodeResultV2.Failure.INVALID_ARGUMENT);
            failure(raw(fixture, fixture.generation, fixture.output, CAPACITY, (byte) 2, (byte) 0),
                    PacketEncodeResultV2.Failure.INVALID_ARGUMENT);
            failure(raw(fixture, fixture.generation, fixture.output, CAPACITY, (byte) 0, (byte) 2),
                    PacketEncodeResultV2.Failure.INVALID_ARGUMENT);
            check(fixture.encode(false, false, CAPACITY).isSuccess(), "retry after invalid arguments");
        }
    }

    private static void generationAndLifecycleFailures() {
        try (Fixture fixture = new Fixture()) {
            failure(raw(fixture, 0, fixture.output, CAPACITY, (byte) 0, (byte) 0),
                    PacketEncodeResultV2.Failure.STALE_GENERATION);
            failure(raw(fixture, -1, fixture.output, CAPACITY, (byte) 0, (byte) 0),
                    PacketEncodeResultV2.Failure.STALE_GENERATION);
            failure(raw(fixture, fixture.generation + 1, fixture.output, CAPACITY, (byte) 0, (byte) 0),
                    PacketEncodeResultV2.Failure.STALE_GENERATION);
            check(fixture.encode(false, false, CAPACITY).isSuccess(), "retry with valid generation");
            check(NativeChunkBridge.invalidateChunk(DIM, fixture.x, 0) == 1, "invalidate fixture");
            failure(fixture.encode(false, false, CAPACITY), PacketEncodeResultV2.Failure.STALE_GENERATION);
            check(NativeChunkBridge.unloadChunk(DIM, fixture.x, 0) == 1, "unload invalid fixture");
            failure(fixture.encode(false, false, CAPACITY), PacketEncodeResultV2.Failure.MISSING_HANDLE);
            fixture.generation = NativeChunkBridge.registerPrimer(DIM, fixture.x, 0,
                    fixture.primer, fixture.biomes);
            check(fixture.generation > 0, "replacement registered");
            check(fixture.encode(false, false, CAPACITY).isSuccess(), "retry after replacement");
        }
    }

    private static PacketEncodeResultV2 raw(Fixture fixture, long generation,
            long output, int capacity, byte sky, byte full) {
        return PacketEncodeResultV2.decode(NativeChunkBridge.encodePacketPayloadV2(
                DIM, fixture.x, 0, generation, sky, full, output, capacity));
    }

    private static void failure(final PacketEncodeResultV2 result, PacketEncodeResultV2.Failure expected) {
        check(!result.isSuccess() && result.failure() == expected, "exact JNI failure " + expected);
        try {
            result.bytesWritten();
            throw new AssertionError("Failure exposed byte count");
        } catch (IllegalStateException expectedException) { checks++; }
        try {
            result.emittedMask();
            throw new AssertionError("Failure exposed mask");
        } catch (IllegalStateException expectedException) { checks++; }
    }

    /** Independent complete-payload scanner; mask never bounds section parsing. */
    private static void scan(byte[] payload, int emittedMask, int expectedMask, boolean sky, boolean full) {
        Reader reader = new Reader(payload);
        int sectionEnd = payload.length - (full ? 256 : 0);
        int scannedMask = 0;
        int sectionCount = 0;
        int previousY = -1;
        while (reader.position < sectionEnd) {
            check(reader.unsignedByte() == 4, "fixture four-bit palette");
            check(reader.varInt() == 2 && reader.varInt() == 0, "fixture palette includes air");
            int y = reader.varInt() - 1;
            check(y >= 0 && y < 16 && y > previousY, "fixture section identity and ascending order");
            previousY = y;
            scannedMask |= 1 << y;
            sectionCount++;
            check(reader.varInt() == 256, "exact packed long count");
            for (int word = 0; word < 256; word++) {
                for (int octet = 0; octet < 8; octet++) {
                    check(reader.unsignedByte() == 0x11, "all 4096 states are fixture palette index one");
                }
            }
            for (int index = 0; index < 2048; index++) {
                check(reader.unsignedByte() == y, "block light provenance");
            }
            if (sky) {
                for (int index = 0; index < 2048; index++) {
                    check(reader.unsignedByte() == 255 - y, "sky light provenance");
                }
            }
        }
        check(reader.position == sectionEnd, "section bytes consumed exactly");
        if (full) {
            for (int index = 0; index < 256; index++) {
                check(reader.unsignedByte() == 42, "biome provenance");
            }
        }
        check(reader.position == payload.length, "full payload consumed exactly");
        check(scannedMask == expectedMask && scannedMask == emittedMask, "mask belongs to serialized sections");
        check(sectionCount == Integer.bitCount(emittedMask), "mask count equals serialized section count");
    }

    private static final class Reader {
        final byte[] bytes;
        int position;
        Reader(byte[] bytes) { this.bytes = bytes; }
        int unsignedByte() {
            if (position == bytes.length) throw new AssertionError("Truncated section payload");
            return bytes[position++] & 255;
        }
        int varInt() {
            int value = 0;
            for (int shift = 0; shift < 35; shift += 7) {
                int octet = unsignedByte();
                value |= (octet & 127) << shift;
                if ((octet & 128) == 0) return value;
            }
            throw new AssertionError("Oversized VarInt");
        }
    }

    private static final class Fixture implements AutoCloseable {
        final int x = nextX++;
        final long primer = allocate(131072);
        final long biomes = allocate(256);
        final long states = allocate(8192);
        final long blockLight = allocate(2048);
        final long skyLight = allocate(2048);
        final long output = allocate(CAPACITY);
        long generation;

        Fixture() {
            MEMORY.setMemory(biomes, 256, (byte) 42);
            generation = NativeChunkBridge.registerPrimer(DIM, x, 0, primer, biomes);
            check(generation > 0, "fixture registered");
        }

        void populate(int mask) {
            for (int y = 0; y < 16; y++) if ((mask & (1 << y)) != 0) refresh(y, true);
        }

        void refresh(int y, boolean nonempty) {
            for (int index = 0; index < 4096; index++) {
                MEMORY.putShort(states + index * 2L, (short) (nonempty ? y + 1 : 0));
            }
            MEMORY.setMemory(blockLight, 2048, (byte) y);
            MEMORY.setMemory(skyLight, 2048, (byte) (255 - y));
            check(NativeChunkBridge.refreshSection(DIM, x, 0, (byte) y,
                    states, blockLight, skyLight) >= 0, "fixture section refreshed");
        }

        PacketEncodeResultV2 encode(boolean sky, boolean full, int capacity) {
            return NativeChunkBridge.encodePacketV2(DIM, x, 0, generation, sky, full, output, capacity);
        }

        byte[] bytes(PacketEncodeResultV2 result) { return bytes(result.bytesWritten()); }

        byte[] bytes(int count) {
            check(count >= 0 && count <= CAPACITY, "bounded byte count");
            byte[] copy = new byte[count];
            for (int index = 0; index < count; index++) copy[index] = MEMORY.getByte(output + index);
            return copy;
        }

        public void close() {
            NativeChunkBridge.unloadChunk(DIM, x, 0);
            for (long address : new long[] {primer, biomes, states, blockLight, skyLight, output}) {
                MEMORY.freeMemory(address);
            }
        }
    }

    private static long allocate(long size) {
        long address = MEMORY.allocateMemory(size);
        MEMORY.setMemory(address, size, (byte) 0);
        return address;
    }

    private static Unsafe unsafe() {
        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            return (Unsafe) field.get(null);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    private static String sha256(byte[] data) throws Exception {
        StringBuilder hex = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(data)) {
            hex.append(String.format("%02x", value & 255));
        }
        return hex.toString();
    }

    private static void check(boolean condition, String label) {
        checks++;
        if (!condition) throw new AssertionError(label);
    }
}
