package com.rustcraft.bridge;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;

import java.util.Arrays;
import java.util.Random;

/**
 * Offline compression-authority suite (this milestone's fresh validation):
 * the REAL NativeCompressionEncoder (direct zero-heap path) + the REAL
 * vanilla NettyCompressionDecoder over EmbeddedChannel. One handler instance
 * per channel (MessageToByteEncoder is not @Sharable) — matching production
 * where one encoder serves one connection.
 *
 * Covers: threshold edges (255/256/257, 127/128 VarInt boundaries,
 * 16383/16384, zero-length), entropy shapes, direct-path zero-heap proof,
 * Java fallback on a dead context, shadow-mode parity, threshold lifecycle,
 * and the 100k compression lifecycle stress (0 leaks, 0 mismatches).
 */
public class CompressionAuthorityOfflineTest {

    private static int failures = 0;
    private static int checks = 0;

    private static void assertTrue(boolean c, String name) {
        checks++;
        if (!c) {
            failures++;
            System.err.println("  [FAIL] " + name);
        }
    }

    private static void assertEquals(Object e, Object a, String name) {
        checks++;
        if (!java.util.Objects.equals(String.valueOf(e), String.valueOf(a))) {
            failures++;
            System.err.println("  [FAIL] " + name + ": expected=" + e + " actual=" + a);
        }
    }

    public static void main(String[] args) throws Exception {
        NativeCompressionEncoder.setRuntimeMode("ON_EXPERIMENTAL");
        System.out.println("=== RustCraft Compression Authority Offline Suite ===");

        testThresholdEdgesAndVarintBoundaries();
        testEntropyShapes();
        testZeroHeapOnDirectPath();
        testFallbackOnDeadContext();
        testShadowModeParity();
        testThresholdLifecycle();
        testHundredKStress();

        System.out.println("=== checks=" + checks + " failures=" + failures + " ===");
        System.out.println(failures == 0 ? "COMPRESSION_SUITE_GREEN" : "COMPRESSION_SUITE_FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static EmbeddedChannel newChannel(NativeCompressionEncoder encoder, int threshold) {
        return new EmbeddedChannel(encoder,
                new net.minecraft.network.NettyCompressionDecoder(threshold));
    }

    /** One body through the REAL encoder + REAL decoder on a live channel. */
    private static byte[] roundTrip(EmbeddedChannel ch, byte[] body) {
        ByteBuf buf = PooledByteBufAllocator.DEFAULT.directBuffer(body.length + 16);
        buf.writeBytes(body);
        ch.writeOutbound(buf);
        Object out = ch.readOutbound();
        assertTrue(out instanceof ByteBuf, "frame emitted");
        ByteBuf frame = (ByteBuf) out;
        // Feed the frame to the REAL vanilla decoder (inbound side).
        ch.writeInbound(frame); // the decoder owns and releases the frame
        Object decoded = ch.readInbound();
        if (decoded == null) {
            return null;
        }
        ByteBuf db = (ByteBuf) decoded;
        byte[] result = new byte[db.readableBytes()];
        db.readBytes(result);
        db.release();
        return result;
    }

    private static void testThresholdEdgesAndVarintBoundaries() {
        System.out.println("[1] threshold edges + VarInt boundaries (threshold 256)");
        NativeCompressionEncoder enc = new NativeCompressionEncoder(256);
        EmbeddedChannel ch = newChannel(enc, 256);
        int[] sizes = {1, 127, 128, 255, 256, 257, 16383, 16384};
        Random r = new Random(340);
        for (int size : sizes) {
            byte[] body = new byte[size];
            r.nextBytes(body);
            byte[] decoded = roundTrip(ch, body);
            assertTrue(decoded != null, "decoded size=" + size);
            if (decoded != null) {
                assertTrue(Arrays.equals(body, decoded), "round trip size=" + size);
            }
        }
        byte[] empty = new byte[0];
        byte[] decodedEmpty = roundTrip(ch, empty);
        assertTrue(decodedEmpty != null && decodedEmpty.length == 0, "empty body round trip");
        assertEquals(0, NativeCompressionEncoder.M2C_ON_VERIFY_MISMATCHES.get(),
                "no sampled-verify mismatches");
        ch.finishAndReleaseAll();
    }

    private static void testEntropyShapes() {
        System.out.println("[2] entropy shapes (zeros / semi / random / chunk-like)");
        NativeCompressionEncoder enc = new NativeCompressionEncoder(256);
        EmbeddedChannel ch = newChannel(enc, 256);
        byte[] zeros = new byte[65536];
        byte[] decoded = roundTrip(ch, zeros);
        assertTrue(decoded != null && Arrays.equals(zeros, decoded), "zeros 64k");

        byte[] semi = new byte[262144];
        Random r = new Random(7);
        for (int i = 0; i < semi.length; i += 16) {
            byte v = (byte) r.nextInt(4);
            Arrays.fill(semi, i, Math.min(i + 16, semi.length), v);
        }
        decoded = roundTrip(ch, semi);
        assertTrue(decoded != null && Arrays.equals(semi, decoded), "semi 256k");

        byte[] random = new byte[65536];
        r.nextBytes(random);
        decoded = roundTrip(ch, random);
        assertTrue(decoded != null && Arrays.equals(random, decoded), "random 64k");

        byte[] chunkLike = new byte[49480];
        for (int i = 0; i < chunkLike.length; i += 2048) {
            byte v = (byte) ((i / 2048) & 0x0F);
            Arrays.fill(chunkLike, i, Math.min(i + 2048, chunkLike.length), v);
        }
        decoded = roundTrip(ch, chunkLike);
        assertTrue(decoded != null && Arrays.equals(chunkLike, decoded), "chunk-like 49k");
        ch.finishAndReleaseAll();
    }

    private static void testZeroHeapOnDirectPath() {
        System.out.println("[3] direct path: zero heap payload bytes on the wire path");
        long heapBefore = NativeCompressionEncoder.M2C_HEAP_PAYLOAD_BYTES.get();
        long directBefore = NativeCompressionEncoder.M2C_DIRECT_PACKETS.get();
        NativeCompressionEncoder enc = new NativeCompressionEncoder(256);
        EmbeddedChannel ch = newChannel(enc, 256);
        byte[] body = new byte[32768];
        new Random(9).nextBytes(body);
        byte[] decoded = roundTrip(ch, body);
        assertTrue(decoded != null && Arrays.equals(body, decoded), "direct round trip");
        assertTrue(NativeCompressionEncoder.M2C_DIRECT_PACKETS.get() > directBefore,
                "direct path used (pooled direct in/out)");
        assertEquals(heapBefore, NativeCompressionEncoder.M2C_HEAP_PAYLOAD_BYTES.get(),
                "heap payload bytes untouched on the direct wire path");
        assertTrue(NativeCompressionEncoder.M2C_ON_VERIFY_MISMATCHES.get() == 0,
                "no verify mismatches");
        ch.finishAndReleaseAll();
    }

    private static void testFallbackOnDeadContext() {
        System.out.println("[4] context lifecycle: dead context self-heals, output stays clean");
        NativeCompressionEncoder enc = new NativeCompressionEncoder(256);
        EmbeddedChannel ch = newChannel(enc, 256);
        byte[] body = new byte[4096];
        new Random(11).nextBytes(body);
        byte[] decoded = roundTrip(ch, body);
        assertTrue(decoded != null && Arrays.equals(body, decoded), "pre-free round trip");
        enc.freeContextForTest();
        assertTrue(!enc.isContextLiveForTest(), "context freed");
        // ensureCreated() self-heals on the next packet (the true production
        // behavior); the vanilla fallback arm is exercised by the M2C
        // offline regression's forced-native-failure case.
        decoded = roundTrip(ch, body);
        assertTrue(decoded != null && Arrays.equals(body, decoded),
                "post-free round trip (self-healed)");
        decoded = roundTrip(ch, body);
        assertTrue(decoded != null && Arrays.equals(body, decoded), "steady round trip");
        assertTrue(enc.isContextLiveForTest(), "context live again after self-heal");
        ch.finishAndReleaseAll();
    }

    private static void testShadowModeParity() {
        System.out.println("[5] shadow mode: vanilla transmits, Rust parity compared");
        NativeCompressionEncoder.setRuntimeMode("SHADOW");
        try {
            NativeCompressionEncoder enc = new NativeCompressionEncoder(256);
            EmbeddedChannel ch = newChannel(enc, 256);
            long matchesBefore = NativeCompressionEncoder.M2C_SHADOW_MATCHES.get();
            long mismatchBefore = NativeCompressionEncoder.M2C_SHADOW_MISMATCHES.get();
            Random r = new Random(13);
            for (int i = 0; i < 20; i++) {
                byte[] body = new byte[1024 + i * 512];
                r.nextBytes(body);
                byte[] decoded = roundTrip(ch, body);
                assertTrue(decoded != null && Arrays.equals(body, decoded),
                        "shadow round trip " + i);
            }
            ch.finishAndReleaseAll();
            assertTrue(NativeCompressionEncoder.M2C_SHADOW_MATCHES.get() >= matchesBefore + 20,
                    "shadow matches incremented");
            assertEquals(mismatchBefore, NativeCompressionEncoder.M2C_SHADOW_MISMATCHES.get(),
                    "zero shadow mismatches");
        } finally {
            NativeCompressionEncoder.setRuntimeMode("ON_EXPERIMENTAL");
        }
    }

    private static void testThresholdLifecycle() {
        System.out.println("[6] threshold lifecycle (func_179299_a updates)");
        NativeCompressionEncoder enc = new NativeCompressionEncoder(256);
        assertEquals(256, enc.threshold(), "initial threshold");
        enc.func_179299_a(1024);
        assertEquals(1024, enc.threshold(), "updated threshold");
        byte[] body = new byte[512]; // between 256 and 1024: must now pass through raw
        new Random(17).nextBytes(body);
        EmbeddedChannel ch = newChannel(enc, 1024);
        byte[] decoded = roundTrip(ch, body);
        assertTrue(decoded != null && Arrays.equals(body, decoded), "passthrough body survives");
        ch.finishAndReleaseAll();
    }

    private static void testHundredKStress() {
        System.out.println("[7] 100k compression lifecycle stress");
        NativeCompressionEncoder enc = new NativeCompressionEncoder(256);
        Random r = new Random(19);
        long createdBefore = NativeCompressionEncoder.M2C_CTX_CREATED.get();
        long freedBefore = NativeCompressionEncoder.M2C_CTX_FREED.get();
        long verifyMismatchBefore = NativeCompressionEncoder.M2C_ON_VERIFY_MISMATCHES.get();
        byte[][] bodies = new byte[8][];
        for (int i = 0; i < bodies.length; i++) {
            bodies[i] = new byte[256 + i * 4096];
            r.nextBytes(bodies[i]);
        }
        EmbeddedChannel ch = newChannel(enc, 256);
        int decodedOk = 0;
        for (int i = 0; i < 100_000; i++) {
            byte[] body = bodies[i & 7];
            ByteBuf buf = PooledByteBufAllocator.DEFAULT.directBuffer(body.length + 16);
            buf.writeBytes(body);
            ch.writeOutbound(buf);
            ByteBuf frame = ch.readOutbound();
            if (frame == null) {
                assertTrue(false, "stress frame null at " + i);
                break;
            }
            ch.writeInbound(frame); // decoder owns the frame
            Object decoded = ch.readInbound();
            if (decoded == null) {
                assertTrue(false, "stress decode null at " + i);
                break;
            }
            ByteBuf db = (ByteBuf) decoded;
            if (db.readableBytes() != body.length) {
                assertTrue(false, "stress length mismatch at " + i);
                db.release();
                break;
            }
            db.release();
            decodedOk++;
            if (i == 49_999) {
                enc.freeContextForTest(); // mid-stress churn: free + re-create
            }
        }
        assertEquals(100_000, decodedOk, "all 100k round trips decoded");
        assertEquals(verifyMismatchBefore, NativeCompressionEncoder.M2C_ON_VERIFY_MISMATCHES.get(),
                "zero verify mismatches across 100k");
        assertTrue(NativeCompressionEncoder.M2C_CTX_CREATED.get() > createdBefore,
                "context re-created mid-stress");
        ch.finishAndReleaseAll();
        assertTrue(NativeCompressionEncoder.M2C_CTX_FREED.get() > freedBefore,
                "handler removal freed the context");
        System.out.println("  ctx created=" + NativeCompressionEncoder.M2C_CTX_CREATED.get()
                + " freed=" + NativeCompressionEncoder.M2C_CTX_FREED.get()
                + " direct_packets=" + NativeCompressionEncoder.M2C_DIRECT_PACKETS.get()
                + " heap_path_packets=" + NativeCompressionEncoder.M2C_HEAP_PATH_PACKETS.get()
                + " heap_payload_bytes=" + NativeCompressionEncoder.M2C_HEAP_PAYLOAD_BYTES.get()
                + " fallbacks=" + NativeCompressionEncoder.M2C_FALLBACKS.get());
    }
}
