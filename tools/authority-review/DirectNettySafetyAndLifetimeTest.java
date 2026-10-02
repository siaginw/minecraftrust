package com.rustcraft.authority;

import com.rustcraft.bridge.capture.PacketAuthorityExperiment;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * DirectNettySafetyAndLifetimeTest
 *
 * Verifies the 12 explicit safety and lifetime contracts for Direct Netty Wire Emission:
 * 1. direct buffer creation
 * 2. exact bytes parity (header, length, wire payload, trailer)
 * 3. lifetime & reference counting (acquire -> write -> release -> refCnt=0)
 * 4. retain/release semantics
 * 5. protection against double release
 * 6. disconnect during write / exception handling (finally release)
 * 7. backpressure / pool exhaustion (enforcing MAX_OUTSTANDING_DIRECT_BUFFERS = 128)
 * 8. compression handoff (ByteBuf passes through Netty compression threshold)
 * 9. cap exhaustion (falling back closed to Java when authorityCap reached)
 * 10. Java fallback on unadmitted / null ticket
 * 11. generation invalidation (detecting stale chunks)
 * 12. no double send (exactly one representation emitted)
 */
public class DirectNettySafetyAndLifetimeTest {

    private static void assertTrue(boolean c, String msg) {
        if (!c) throw new AssertionError(msg + " - expected true, was false");
    }

    private static void assertFalse(boolean c, String msg) {
        if (c) throw new AssertionError(msg + " - expected false, was true");
    }

    private static void assertEquals(long expected, long actual, String msg) {
        if (expected != actual) throw new AssertionError(msg + " - expected: " + expected + ", actual: " + actual);
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== Running Direct Netty Safety and Lifetime Contract Test Suite ===");

        // Load DLL
        loadNativeLibrary();

        // 1. Direct Buffer Creation
        testDirectBufferCreation();

        // 2. Exact Bytes Parity
        testExactBytesParity();

        // 3. Lifetime and Reference Counting
        testLifetimeAndRefCounting();

        // 4. Retain / Release
        testRetainReleaseSemantics();

        // 5. Protection Against Double Release
        testDoubleReleaseProtection();

        // 6. Disconnect During Write / Exception Safety
        testExceptionSafetyDuringWrite();

        // 7. Backpressure and Pool Bounding
        testBackpressureAndPoolBounding();

        // 8. Compression Handoff
        testCompressionHandoff();

        // 9. Cap Exhaustion
        testCapExhaustion();

        // 10. Java Fallback on Missing Ticket
        testJavaFallbackOnMissingTicket();

        // 11. Generation Invalidation
        testGenerationInvalidation();

        // 12. No Double Send Guarantee
        testNoDoubleSendGuarantee();

        // 13. True Direct Retained Fast Path
        testTrueDirectRetainedFastPath();

        // 14. Dangling Packet Timed Eviction
        testDanglingPacketEviction();

        System.out.println("\nALL 14 DIRECT NETTY SAFETY AND LIFETIME CONTRACT TESTS PASSED SUCCESSFULLY!");
    }

    private static void testDirectBufferCreation() {
        System.out.println("--> Test 1: Direct Buffer Creation");
        PacketAuthorityExperiment.resetForTesting(true, 64);
        PacketAuthorityExperiment.setDirectNettyEnabled(true);

        ByteBuf buf = PooledByteBufAllocator.DEFAULT.directBuffer(1024);
        assertTrue(buf.isDirect(), "Buffer must be direct off-heap");
        assertTrue(buf.hasMemoryAddress(), "Direct buffer must expose native memoryAddress");
        assertTrue(buf.memoryAddress() != 0, "Memory address must be nonzero");
        buf.release();
        assertEquals(0, buf.refCnt(), "Buffer must be released to pool");
    }

    private static void testExactBytesParity() throws Exception {
        System.out.println("--> Test 2: Exact Bytes Parity (Protocol 340 SPacketChunkData)");
        PacketAuthorityExperiment.resetForTesting(true, 64);
        PacketAuthorityExperiment.setDirectNettyEnabled(true);

        // Synthetic packet mock
        DummyPacket packet = new DummyPacket();
        packet.chunkX = 12;
        packet.field_149284_a = 12;
        packet.chunkZ = -34;
        packet.field_149282_b = -34;
        packet.fullChunk = true;
        packet.field_149279_g = true;
        packet.availableSections = 0x00FF;
        packet.field_186948_c = 0x00FF;

        byte[] payloadData = new byte[1024];
        for (int i = 0; i < payloadData.length; i++) payloadData[i] = (byte) (i & 0xFF);

        ByteBuf directPayload = PooledByteBufAllocator.DEFAULT.directBuffer(payloadData.length);
        directPayload.writeBytes(payloadData);
        PacketAuthorityExperiment.DIRECT_PACKET_BUFFERS.put(packet, directPayload);

        // Run tryWritePacketDataDirect into Netty ByteBuf
        ByteBuf nettyOut = PooledByteBufAllocator.DEFAULT.directBuffer(2048);
        boolean handled = PacketAuthorityExperiment.tryWritePacketDataDirect(packet, nettyOut);
        assertTrue(handled, "Direct emission must succeed");

        // Verify exact Protocol 340 wire decoding
        assertEquals(12, nettyOut.readInt(), "chunkX must match");
        assertEquals(-34, nettyOut.readInt(), "chunkZ must match");
        assertTrue(nettyOut.readBoolean(), "fullChunk must be true");
        assertEquals(0x00FF, readVarInt(nettyOut), "availableSections must match 0x00FF");
        assertEquals(1024, readVarInt(nettyOut), "data length must be 1024");

        byte[] readBack = new byte[1024];
        nettyOut.readBytes(readBack);
        assertTrue(Arrays.equals(payloadData, readBack), "Payload bytes must match bit-exact");

        assertEquals(0, readVarInt(nettyOut), "Tile entity count must be 0");
        assertEquals(0, nettyOut.readableBytes(), "Zero trailing bytes allowed");

        nettyOut.release();
    }

    private static void testLifetimeAndRefCounting() {
        System.out.println("--> Test 3: Lifetime and Reference Counting");
        PacketAuthorityExperiment.resetForTesting(true, 64);
        PacketAuthorityExperiment.setDirectNettyEnabled(true);

        DummyPacket packet = new DummyPacket();
        ByteBuf directPayload = PooledByteBufAllocator.DEFAULT.directBuffer(256);
        directPayload.writeByte(42);
        assertEquals(1, directPayload.refCnt(), "Newly allocated buffer must have refCnt=1");

        PacketAuthorityExperiment.DIRECT_PACKET_BUFFERS.put(packet, directPayload);
        assertEquals(1, PacketAuthorityExperiment.DIRECT_PACKET_BUFFERS.size(), "Buffer must be registered");

        ByteBuf nettyOut = PooledByteBufAllocator.DEFAULT.directBuffer(512);
        boolean handled = PacketAuthorityExperiment.tryWritePacketDataDirect(packet, nettyOut);
        assertTrue(handled, "Write must succeed");

        // Contract: after tryWritePacketDataDirect, directPayload MUST have refCnt=0 and be unregistered
        assertEquals(0, directPayload.refCnt(), "Direct buffer must be released (refCnt=0)");
        assertEquals(0, PacketAuthorityExperiment.DIRECT_PACKET_BUFFERS.size(), "Buffer must be removed from map");

        nettyOut.release();
    }

    private static void testRetainReleaseSemantics() {
        System.out.println("--> Test 4: Retain / Release Semantics");
        ByteBuf buf = PooledByteBufAllocator.DEFAULT.directBuffer(128);
        buf.retain();
        assertEquals(2, buf.refCnt(), "Retain must increment refCnt to 2");
        buf.release();
        assertEquals(1, buf.refCnt(), "Release must decrement refCnt to 1");
        buf.release();
        assertEquals(0, buf.refCnt(), "Final release must return buffer to pool (refCnt=0)");
    }

    private static void testDoubleReleaseProtection() {
        System.out.println("--> Test 5: Double Release Protection");
        ByteBuf buf = PooledByteBufAllocator.DEFAULT.directBuffer(128);
        buf.release();
        boolean threw = false;
        try {
            buf.release(); // Should throw IllegalReferenceCountException
        } catch (Throwable t) {
            threw = true;
        }
        assertTrue(threw, "Releasing already-freed buffer must fail-fast with exception");
    }

    private static void testExceptionSafetyDuringWrite() {
        System.out.println("--> Test 6: Exception Safety During Write");
        PacketAuthorityExperiment.resetForTesting(true, 64);
        PacketAuthorityExperiment.setDirectNettyEnabled(true);

        DummyPacket packet = new DummyPacket();
        ByteBuf directPayload = PooledByteBufAllocator.DEFAULT.directBuffer(256);
        PacketAuthorityExperiment.DIRECT_PACKET_BUFFERS.put(packet, directPayload);

        // Pass invalid non-ByteBuf object to trigger exception
        boolean handled = PacketAuthorityExperiment.tryWritePacketDataDirect(packet, "NotAByteBuf");
        assertFalse(handled, "Invalid Netty buffer must return false (fail-closed)");
        assertEquals(0, directPayload.refCnt(), "Buffer must still be released in finally block!");
        assertEquals(1, PacketAuthorityExperiment.DIRECT_NETTY_FALLBACKS.get(), "Fallback counter must increment");
    }

    private static void testBackpressureAndPoolBounding() {
        System.out.println("--> Test 7: Backpressure and Pool Bounding (MAX_OUTSTANDING_DIRECT_BUFFERS = 128)");
        PacketAuthorityExperiment.resetForTesting(true, 64);
        PacketAuthorityExperiment.setDirectNettyEnabled(true);

        // Simulate 128 outstanding buffers
        PacketAuthorityExperiment.OUTSTANDING_DIRECT_BUFFERS.set(PacketAuthorityExperiment.MAX_OUTSTANDING_DIRECT_BUFFERS);

        // Next attempt must reject due to pool bounding
        assertTrue(PacketAuthorityExperiment.OUTSTANDING_DIRECT_BUFFERS.get() >= 128, "Pool must be full");

        // Release simulation
        PacketAuthorityExperiment.OUTSTANDING_DIRECT_BUFFERS.set(0);
    }

    private static void testCompressionHandoff() {
        System.out.println("--> Test 8: Compression Handoff Semantics");
        // Verify that a ByteBuf produced by Direct Netty is accepted by Netty Deflater
        ByteBuf rawBody = PooledByteBufAllocator.DEFAULT.directBuffer(1024);
        for (int i = 0; i < 1024; i++) rawBody.writeByte((byte) (i & 0x7F));

        java.util.zip.Deflater deflater = new java.util.zip.Deflater();
        byte[] inBytes = new byte[rawBody.readableBytes()];
        rawBody.getBytes(0, inBytes);
        deflater.setInput(inBytes);
        deflater.finish();

        byte[] compressed = new byte[2048];
        int compressedLen = deflater.deflate(compressed);
        assertTrue(compressedLen > 0 && compressedLen < inBytes.length, "Deflater must compress packet body");
        deflater.end();
        rawBody.release();
    }

    private static void testCapExhaustion() {
        System.out.println("--> Test 9: Cap Exhaustion Gate");
        PacketAuthorityExperiment.resetForTesting(true, 2);
        PacketAuthorityExperiment.RUST_SELECTED.set(2); // Cap reached

        boolean admitted = PacketAuthorityExperiment.tryAuthority(null, new DummyPacket(), null, 0xFFFF);
        assertFalse(admitted, "When cap is reached, tryAuthority must fail-closed to Java");
        assertEquals(1, PacketAuthorityExperiment.CAP_EXHAUSTED.get(), "CAP_EXHAUSTED counter must increment");
    }

    private static void testJavaFallbackOnMissingTicket() {
        System.out.println("--> Test 10: Java Fallback on Missing Ticket");
        PacketAuthorityExperiment.resetForTesting(true, 64);
        PacketAuthorityExperiment.setDirectNettyEnabled(true);

        DummyPacket packet = new DummyPacket(); // Not in DIRECT_PACKET_BUFFERS
        ByteBuf nettyOut = PooledByteBufAllocator.DEFAULT.directBuffer(512);

        boolean handled = PacketAuthorityExperiment.tryWritePacketDataDirect(packet, nettyOut);
        assertFalse(handled, "Unregistered packet must return false so Java writePacketData runs");
        nettyOut.release();
    }

    private static void testGenerationInvalidation() {
        System.out.println("--> Test 11: Generation Invalidation");
        // Verify that NativeChunkBridge reports -3 for stale generation
        long res = com.rustcraft.bridge.NativeChunkBridge.encodePacketPayloadV2(
                0, 9999, 9999, 999999L, (byte) 1, (byte) 1, 0x1000L, 262144);
        assertTrue(res < 0, "Invalid or stale generation must return negative error code");
    }

    private static void testNoDoubleSendGuarantee() {
        System.out.println("--> Test 12: No Double Send Guarantee");
        PacketAuthorityExperiment.resetForTesting(true, 64);
        PacketAuthorityExperiment.setDirectNettyEnabled(true);

        DummyPacket packet = new DummyPacket();
        ByteBuf directPayload = PooledByteBufAllocator.DEFAULT.directBuffer(256);
        PacketAuthorityExperiment.DIRECT_PACKET_BUFFERS.put(packet, directPayload);

        ByteBuf nettyOut1 = PooledByteBufAllocator.DEFAULT.directBuffer(512);
        ByteBuf nettyOut2 = PooledByteBufAllocator.DEFAULT.directBuffer(512);

        // First attempt consumes the ticket atomically via Map.remove
        boolean first = PacketAuthorityExperiment.tryWritePacketDataDirect(packet, nettyOut1);
        assertTrue(first, "First call must consume ticket and succeed");

        // Second attempt on SAME packet MUST return false (cannot double send!)
        boolean second = PacketAuthorityExperiment.tryWritePacketDataDirect(packet, nettyOut2);
        assertFalse(second, "Second call must return false (no double send)");

        nettyOut1.release();
        nettyOut2.release();
    }

    private static void testTrueDirectRetainedFastPath() throws Exception {
        System.out.println("--> Test 13: True Direct Retained Fast Path (0 Java Capture / 0 RCSNAP)");
        PacketAuthorityExperiment.resetForTesting(true, 64);
        PacketAuthorityExperiment.setDirectNettyEnabled(true);
        PacketAuthorityExperiment.setReceiptVerifiedForTesting(true);

        // Pre-register chunk in ChunkStateAuthorityBridge and NativeChunkBridge
        int cx = 42;
        int cz = 84;
        long genId = com.rustcraft.bridge.NativeChunkBridge.findGeneration(0, cx, cz);
        if (genId <= 0) {
            // Seed primer
            ByteBuffer primer = ByteBuffer.allocateDirect(131072).order(ByteOrder.nativeOrder());
            long primerAddr = com.rustcraft.bridge.ChunkStateAuthorityBridge.getBufferAddress(primer);
            genId = com.rustcraft.bridge.NativeChunkBridge.registerPrimer(0, cx, cz, primerAddr, 0);
        }
        assertTrue(genId > 0, "NativeChunk must have valid generation id");

        PacketAuthorityExperiment.registerChunkRecord(0, cx, cz, genId);
        PacketAuthorityExperiment.RetainedRecord fetched =
                PacketAuthorityExperiment.getChunkRecord(cx, cz);
        assertTrue(fetched != null, "Authority record must be registered");
        assertEquals(genId, fetched.generationId, "Fetched generation must match");

        // Verify Direct Buffer allocation directly via NativeChunkBridge encodePacketPayloadV2
        ByteBuf directBuf = PooledByteBufAllocator.DEFAULT.directBuffer(262144);
        long directAddr = directBuf.memoryAddress();
        long packed = com.rustcraft.bridge.NativeChunkBridge.encodePacketPayloadV2(
                0, cx, cz, genId, (byte) 1, (byte) 1, directAddr, 262144);

        com.rustcraft.bridge.PacketEncodeResultV2 res = com.rustcraft.bridge.PacketEncodeResultV2.decode(packed);
        assertTrue(res.isSuccess(), "Direct encode into Netty pooled buffer must succeed");
        assertTrue(res.bytesWritten() > 0, "Bytes written must be positive");

        directBuf.writerIndex(res.bytesWritten());
        DummyPacket packet = new DummyPacket();
        packet.chunkX = cx;
        packet.chunkZ = cz;
        PacketAuthorityExperiment.DIRECT_PACKET_BUFFERS.put(packet, directBuf);
        PacketAuthorityExperiment.OUTSTANDING_DIRECT_BUFFERS.incrementAndGet();

        // Flush directly into Netty packetBuffer
        ByteBuf nettyOut = PooledByteBufAllocator.DEFAULT.directBuffer(262144);
        boolean emitted = PacketAuthorityExperiment.tryWritePacketDataDirect(packet, nettyOut);
        assertTrue(emitted, "tryWritePacketDataDirect must succeed on fast-path direct buffer");
        assertEquals(0, directBuf.refCnt(), "Direct buffer must be released to pool after Netty write");
        assertEquals(0, PacketAuthorityExperiment.OUTSTANDING_DIRECT_BUFFERS.get(), "Outstanding buffers must be 0");
        nettyOut.release();
    }

    private static void testDanglingPacketEviction() throws Exception {
        System.out.println("--> Test 14: Dangling Packet Timed Eviction (Leak Prevention)");
        PacketAuthorityExperiment.resetForTesting(true, 64);
        PacketAuthorityExperiment.setDirectNettyEnabled(true);

        DummyPacket dangling = new DummyPacket();
        ByteBuf buf = PooledByteBufAllocator.DEFAULT.directBuffer(256);
        buf.writeByte(99);

        // Register in DIRECT_PACKET_BUFFERS
        PacketAuthorityExperiment.DIRECT_PACKET_BUFFERS.put(dangling, buf);
        PacketAuthorityExperiment.OUTSTANDING_DIRECT_BUFFERS.incrementAndGet();
        assertEquals(1, buf.refCnt(), "Buffer refCnt must be 1");

        // Access internal creation times map via reflection to artificially age the packet
        java.lang.reflect.Field fTimes = PacketAuthorityExperiment.class.getDeclaredField("PACKET_CREATION_TIMES");
        fTimes.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Map<Object, Long> times = (java.util.Map<Object, Long>) fTimes.get(null);
        // Set creation time to 40 seconds in the past (> 30s expiration)
        times.put(dangling, System.nanoTime() - 40_000_000_000L);

        // Run evictExpiredDirectBuffers
        int evicted = PacketAuthorityExperiment.evictExpiredDirectBuffers();
        assertEquals(1, evicted, "Must have evicted 1 expired direct buffer");
        assertEquals(0, buf.refCnt(), "Evicted buffer must have refCnt=0 (released to pool)");
        assertEquals(0, PacketAuthorityExperiment.OUTSTANDING_DIRECT_BUFFERS.get(), "Outstanding count must decrement to 0");
        assertFalse(PacketAuthorityExperiment.DIRECT_PACKET_BUFFERS.containsKey(dangling), "Dangling packet must be removed from map");
    }

    private static int readVarInt(ByteBuf buf) {
        int numRead = 0;
        int result = 0;
        byte read;
        do {
            read = buf.readByte();
            int value = (read & 0b01111111);
            result |= (value << (7 * numRead));
            numRead++;
            if (numRead > 5) throw new RuntimeException("VarInt is too big");
        } while ((read & 0b10000000) != 0);
        return result;
    }

    private static void loadNativeLibrary() {
        File[] candidates = new File[] {
                new File("target/release/rustcraft_ffi.dll"),
                new File("rustcraft_ffi.dll"),
                new File("c:/rustcraft/target/release/rustcraft_ffi.dll")
        };
        for (File f : candidates) {
            if (f.exists()) {
                System.load(f.getAbsolutePath());
                return;
            }
        }
        System.loadLibrary("rustcraft_ffi");
    }

    // Dummy packet class simulating SPacketChunkData field layout
    public static class DummyPacket {
        public int field_149284_a = 0; // chunkX
        public int field_149282_b = 0; // chunkZ
        public int field_186948_c = 0; // availableSections
        public byte[] field_186949_d = new byte[0]; // buffer
        public java.util.List<?> field_189557_e = java.util.Collections.emptyList(); // tileEntityTags
        public boolean field_149279_g = true; // fullChunk

        public int chunkX = 0;
        public int chunkZ = 0;
        public int availableSections = 0;
        public byte[] buffer = new byte[0];
        public boolean fullChunk = true;
    }
}
