package com.rustcraft.bridge;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.zip.Inflater;

import com.rustcraft.bridge.capture.PacketAuthorityExperiment;

/**
 * Offline single-copy safety, lifetime, failure-injection and lifecycle suite
 * (Java 8, no Minecraft runtime required — vanilla wire classes come from the
 * SRG study jar on the classpath).
 *
 * Covers the single-copy contract:
 *  - complete pre-compression body parity vs vanilla PacketBuffer framing,
 *  - real-pipeline bypass: handler -> NettyPacketEncoder (bypassed) ->
 *    NettyCompressionEncoder -> NettyVarint21FrameEncoder through
 *    EmbeddedChannel with the REAL vanilla encoder classes,
 *  - shadow mode: the real encoder serializes a real SPacketChunkData shell
 *    and the capture compares its output byte-for-byte against the body,
 *  - per-write view ownership (multi-consumer broadcast safety),
 *  - deterministic disconnect release without the sweep (quiescence),
 *  - failure-injection matrix (bounds, staleness, closed channel, downstream
 *    failure, cancel-before-write, writePacketData guard),
 *  - generation unload/reload (same coords, new generation, zero old bytes),
 *  - retained fast path clean counters (no capture/transport machinery),
 *  - 100k-event lifecycle stress with the required event mix.
 */
public class SingleCopySafetyAndLifetimeTest {

    private static final int CAPACITY = 262144;

    private static int failures = 0;
    private static int checks = 0;

    private static void assertTrue(boolean condition, String name) {
        checks++;
        if (!condition) {
            failures++;
            System.err.println("  [FAIL] " + name);
        }
    }

    private static void assertEquals(Object expected, Object actual, String name) {
        checks++;
        if (!java.util.Objects.equals(String.valueOf(expected), String.valueOf(actual))) {
            failures++;
            System.err.println("  [FAIL] " + name + ": expected=" + expected + " actual=" + actual);
        }
    }

    public static void main(String[] args) throws Exception {
        loadNativeLibrary();

        System.out.println("=== RustCraft Single-Copy Safety and Lifetime Suite ===");
        SingleCopyPipeline.resetForTesting();
        SingleCopyPipeline.setDirectModeForTest(true);
        SingleCopyPipeline.setShadowModeForTest(false);

        testProtocol340PacketIdLookup();
        testBodyParityAgainstVanillaFraming();
        testBodyIsImmutableAfterRegistration();
        testDirectBypassThroughRealCompression();
        testJavaPacketPassthroughUntouched();
        testMultiConsumerBroadcastSafety();
        testDeterministicDisconnectReleaseWithoutSweep();
        testCancelBeforeWriteStillReleases();
        testDoubleReleaseProtection();
        testFailureMatrixBoundsPressure();
        testFailureMatrixStaleGeneration();
        testFailureMatrixDownstreamFailure();
        testFailureMatrixClosedChannelBeforeConsume();
        testWritePacketDataGuardTwoCopyServeAndRefusal();
        testGenerationUnloadReloadSameCoords();
        testRetainedFastPathCleanCounters();
        testShadowModeRealEncoderOutputCompare();
        testHundredKLifecycleStress();

        SingleCopyPipeline.releaseAllOutstanding();
        SingleCopyPipeline.setDirectModeForTest(false);
        System.out.println("=== checks=" + checks + " failures=" + failures + " ===");
        System.out.println(failures == 0 ? "SINGLE_COPY_SUITE_GREEN"
                : "SINGLE_COPY_SUITE_FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    // ------------------------------------------------------------------
    // 1. Protocol 340 packet id — looked up, never hardcoded
    // ------------------------------------------------------------------

    private static int resolvedChunkPacketId;

    private static void testProtocol340PacketIdLookup() throws Exception {
        System.out.println("[1] Protocol 340 SPacketChunkData id via EnumConnectionState");
        Object packet = new net.minecraft.network.play.server.SPacketChunkData();
        resolvedChunkPacketId = packetIdOf(packet);
        assertEquals(32, resolvedChunkPacketId, "PLAY/CLIENTBOUND SPacketChunkData id == 0x20");
    }

    private static int packetIdOf(Object pkt) throws Exception {
        Class<?> stateCls = Class.forName("net.minecraft.network.EnumConnectionState");
        Object play = stateCls.getField("PLAY").get(null);
        Object dir = Class.forName("net.minecraft.network.EnumPacketDirection")
                .getField("CLIENTBOUND").get(null);
        java.lang.reflect.Method m = stateCls.getMethod("func_179246_a",
                Class.forName("net.minecraft.network.EnumPacketDirection"),
                Class.forName("net.minecraft.network.Packet"));
        Object id = m.invoke(play, dir, pkt);
        assertTrue(id != null, "packet registered in PLAY/CLIENTBOUND");
        return (Integer) id;
    }

    // ------------------------------------------------------------------
    // 2. Body parity vs vanilla PacketBuffer framing
    // ------------------------------------------------------------------

    private static void testBodyParityAgainstVanillaFraming() throws Exception {
        System.out.println("[2] complete body parity vs vanilla PacketBuffer framing");
        int cx = 20001, cz = 20002;
        long genId = seedChunk(cx, cz, 8);
        assertTrue(genId > 0, "chunk seeded");

        long measured = NativeChunkBridge.encodePacketPayloadV2Measure(
                0, cx, cz, genId, (byte) 1, (byte) 1);
        PacketEncodeResultV2 m = PacketEncodeResultV2.decode(measured);
        assertTrue(m.isSuccess(), "measure success");

        // Vanilla framing with the REAL PacketBuffer methods (SRG names for
        // the Minecraft-specific VarInt pair; writeInt/writeBoolean/writeBytes
        // are ByteBuf overrides with stable library names) — the exact bytes
        // NettyPacketEncoder + writePacketData produce.
        net.minecraft.network.PacketBuffer pb = new net.minecraft.network.PacketBuffer(
                Unpooled.buffer(64));
        pb.func_150787_b(resolvedChunkPacketId); // writeVarInt
        pb.writeInt(cx);
        pb.writeInt(cz);
        pb.writeBoolean(true);
        pb.func_150787_b(m.emittedMask());
        pb.func_150787_b(m.bytesWritten());
        byte[] payload = new byte[m.bytesWritten()];
        ByteBuffer scratch = directScratch();
        long packed = NativeChunkBridge.encodePacketPayloadV2(0, cx, cz, genId,
                (byte) 1, (byte) 1, getAddress(scratch), CAPACITY);
        PacketEncodeResultV2 enc = PacketEncodeResultV2.decode(packed);
        assertTrue(enc.isSuccess(), "reference encode success");
        scratch.position(0);
        scratch.get(payload);
        pb.writeBytes(payload);
        pb.func_150787_b(0); // TE count

        try {
            Object fakePacket = newFakePacket(cx, cz);
            SingleCopyPipeline.SingleCopyTicket ticket = SingleCopyChunkBody.build(
                    fakePacket, 0, cx, cz, genId, true, true, resolvedChunkPacketId);
            assertTrue(ticket != null, "body built");
            ByteBuf body = ticket.body;

            ByteBuf reference = Unpooled.wrappedBuffer(pb.array(), 0, pb.writerIndex());
            assertEquals(reference.readableBytes(), body.readableBytes(), "body length parity");
            boolean match = true;
            for (int i = 0; i < reference.readableBytes() && match; i++) {
                if (reference.getByte(reference.readerIndex() + i)
                        != body.getByte(body.readerIndex() + i)) {
                    match = false;
                    System.err.println("  first divergence at " + i);
                }
            }
            assertTrue(match, "body bytes byte-identical to vanilla framing");

            net.minecraft.network.PacketBuffer verify = new net.minecraft.network.PacketBuffer(
                    body.retainedDuplicate());
            assertEquals(resolvedChunkPacketId, verify.func_150792_a(),
                    "leading VarInt is the packet id");
            verify.release();
            ticket.invalidate();
        } finally {
            NativeChunkBridge.unload(0, cx, cz);
        }
    }

    private static void testBodyIsImmutableAfterRegistration() throws Exception {
        System.out.println("[3] body frozen after registration");
        int cx = 20003, cz = 20004;
        long genId = seedChunk(cx, cz, 2);
        try {
            Object fakePacket = newFakePacket(cx, cz);
            SingleCopyPipeline.SingleCopyTicket ticket = SingleCopyChunkBody.build(
                    fakePacket, 0, cx, cz, genId, true, true, resolvedChunkPacketId);
            assertTrue(ticket != null, "body built");
            int w0 = ticket.body.writerIndex();
            int r0 = ticket.body.readerIndex();
            byte[] before = snapshot(ticket.body);

            // More native traffic for the same chunk must not touch the body.
            long packed = NativeChunkBridge.encodePacketPayloadV2(0, cx, cz, genId,
                    (byte) 1, (byte) 1, getAddress(directScratch()), CAPACITY);
            assertTrue(PacketEncodeResultV2.decode(packed).isSuccess(), "re-encode ok");

            assertTrue(Arrays.equals(before, snapshot(ticket.body)), "bytes unchanged");
            assertEquals(w0, ticket.body.writerIndex(), "writerIndex frozen");
            assertEquals(r0, ticket.body.readerIndex(), "readerIndex frozen");
            assertTrue(ticket.body.refCnt() >= 1, "body alive");
            ticket.invalidate();
            assertEquals(0, ticket.body.refCnt(), "released exactly once");
        } finally {
            NativeChunkBridge.unload(0, cx, cz);
        }
    }

    // ------------------------------------------------------------------
    // 4. Real-pipeline bypass through EmbeddedChannel + vanilla encoders
    // ------------------------------------------------------------------

    private static void testDirectBypassThroughRealCompression() throws Exception {
        System.out.println("[4] direct write bypasses NettyPacketEncoder through real compression chain");
        int cx = 20005, cz = 20006;
        long genId = seedChunk(cx, cz, 4);
        try {
            Object fakePacket = newFakePacket(cx, cz);
            SingleCopyPipeline.SingleCopyTicket ticket = SingleCopyChunkBody.build(
                    fakePacket, 0, cx, cz, genId, true, true, resolvedChunkPacketId);
            assertTrue(ticket != null, "body built");
            byte[] expected = snapshot(ticket.body);

            SingleCopyPipeline.RustCraftSingleCopyChunkHandler handler =
                    new SingleCopyPipeline.RustCraftSingleCopyChunkHandler();
            // EmbeddedChannel add order is head-side first; outbound
            // traversal is the REVERSE, so the handler goes LAST (closest to
            // tail) and the encoders sit between it and the head in wire order.
            EmbeddedChannel channel = new EmbeddedChannel(
                    new net.minecraft.network.NettyVarint21FrameEncoder(),
                    new net.minecraft.network.NettyCompressionEncoder(256),
                    new net.minecraft.network.NettyPacketEncoder(
                            net.minecraft.network.EnumPacketDirection.CLIENTBOUND),
                    handler);

            long encoderCallsBefore = SingleCopyPipeline.telemetry().packetEncoderInvocations.get();
            channel.writeOutbound(fakePacket);
            long encoderCallsAfter = SingleCopyPipeline.telemetry().packetEncoderInvocations.get();
            assertEquals(encoderCallsBefore, encoderCallsAfter,
                    "NettyPacketEncoder.encode NOT invoked for the direct packet");

            ByteBuf framed = channel.readOutbound();
            assertTrue(framed != null, "framed output exists");
            byte[] decompressed = decodeFrame(framed);
            assertTrue(Arrays.equals(expected, decompressed),
                    "compressed+framed wire bytes == frozen body");
            framed.release();

            // write-completion lifecycle: quiescence releases the body.
            SingleCopyPipeline.runQuiescenceCheck(ticket);
            assertEquals(0, ticket.body.refCnt(), "body released after single write quiescence");
            assertTrue(SingleCopyPipeline.ticketFor(fakePacket) == null, "ticket removed from registry");
            channel.finishAndReleaseAll();
        } finally {
            NativeChunkBridge.unload(0, cx, cz);
        }
    }

    private static void testJavaPacketPassthroughUntouched() {
        System.out.println("[5] ordinary Java message forwarded untouched");
        SingleCopyPipeline.RustCraftSingleCopyChunkHandler handler =
                new SingleCopyPipeline.RustCraftSingleCopyChunkHandler();
        EmbeddedChannel channel = new EmbeddedChannel(handler);
        Object plain = new Object(); // any non-admitted outbound message
        channel.writeOutbound(plain);
        Object out = channel.readOutbound();
        assertTrue(out == plain, "message passed through with identity");
        channel.finishAndReleaseAll();
    }

    // ------------------------------------------------------------------
    // 6. Multi-consumer broadcast: one packet, several connections
    // ------------------------------------------------------------------

    private static void testMultiConsumerBroadcastSafety() throws Exception {
        System.out.println("[6] one admitted packet serialized to two channels (broadcast)");
        int cx = 20007, cz = 20008;
        long genId = seedChunk(cx, cz, 3);
        try {
            Object fakePacket = newFakePacket(cx, cz);
            SingleCopyPipeline.SingleCopyTicket ticket = SingleCopyChunkBody.build(
                    fakePacket, 0, cx, cz, genId, true, true, resolvedChunkPacketId);
            assertTrue(ticket != null, "body built");
            byte[] expected = snapshot(ticket.body);

            EmbeddedChannel a = new EmbeddedChannel(new SingleCopyPipeline.RustCraftSingleCopyChunkHandler());
            EmbeddedChannel b = new EmbeddedChannel(new SingleCopyPipeline.RustCraftSingleCopyChunkHandler());
            a.writeOutbound(fakePacket);
            drainOutbound(a);
            b.writeOutbound(fakePacket);
            drainOutbound(b);

            assertEquals(2, ticket.totalWrites.get(), "two consumes of one immutable body");
            assertTrue(ticket.body.refCnt() >= 1, "body still alive until lifecycle release");
            // The multi-consumer ticket is NOT quiescence-released:
            SingleCopyPipeline.runQuiescenceCheck(ticket);
            assertTrue(ticket.body.refCnt() >= 1, "quiescence skipped for multi-consumer");
            // The defensive sweep covers it, exactly once.
            assertTrue(SingleCopyPipeline.releaseAllOutstanding() >= 1, "sweep released");
            assertEquals(0, ticket.body.refCnt(), "released once");
            a.finishAndReleaseAll();
            b.finishAndReleaseAll();
        } finally {
            NativeChunkBridge.unload(0, cx, cz);
        }
    }

    // ------------------------------------------------------------------
    // 7. Deterministic disconnect release (no sweep)
    // ------------------------------------------------------------------

    private static void testDeterministicDisconnectReleaseWithoutSweep() throws Exception {
        System.out.println("[7] closed connection: release via promise, not sweep");
        int cx = 20009, cz = 20010;
        long genId = seedChunk(cx, cz, 2);
        try {
            Object fakePacket = newFakePacket(cx, cz);
            SingleCopyPipeline.SingleCopyTicket ticket = SingleCopyChunkBody.build(
                    fakePacket, 0, cx, cz, genId, true, true, resolvedChunkPacketId);
            assertTrue(ticket != null, "body built");
            // Simulate "client closed, then server sends": the write runs the
            // pipeline, fails at the head, the view is released and the
            // promise completes (failure) — the real-Netty closed-socket path.
            EmbeddedChannel channel = new EmbeddedChannel(
                    new FailingHeadHandler(),
                    new SingleCopyPipeline.RustCraftSingleCopyChunkHandler());
            try {
                channel.writeOutbound(fakePacket);
            } catch (Throwable closedChannel) {
                // EmbeddedChannel rethrows the failed-write cause.
            }
            assertEquals(1, ticket.totalWrites.get(), "handler consumed the ticket before the head rejected");
            SingleCopyPipeline.runQuiescenceCheck(ticket);
            assertEquals(0, ticket.body.refCnt(), "body released via promise+quiescence");
            assertTrue(SingleCopyPipeline.ticketFor(fakePacket) == null, "registry clean");
            assertTrue(SingleCopyPipeline.telemetry().releasedViaQuiescence.get() >= 1,
                    "quiescence counter moved");
            channel.releaseOutbound();
        } finally {
            NativeChunkBridge.unload(0, cx, cz);
        }
    }

    private static void testCancelBeforeWriteStillReleases() throws Exception {
        System.out.println("[8] admitted but never written (cancelled send): sweep/release paths");
        int cx = 20011, cz = 20012;
        long genId = seedChunk(cx, cz, 1);
        try {
            Object fakePacket = newFakePacket(cx, cz);
            SingleCopyPipeline.SingleCopyTicket ticket = SingleCopyChunkBody.build(
                    fakePacket, 0, cx, cz, genId, true, true, resolvedChunkPacketId);
            assertTrue(ticket != null, "body built");
            assertEquals(1, ticket.body.refCnt(), "registry holds the only reference");
            // No write ever happens. releaseAllOutstanding models the defensive
            // sweep/shutdown; the body must release exactly once.
            assertTrue(SingleCopyPipeline.releaseAllOutstanding() >= 1, "released");
            assertEquals(0, ticket.body.refCnt(), "refCnt 0");
        } finally {
            NativeChunkBridge.unload(0, cx, cz);
        }
    }

    private static void testDoubleReleaseProtection() {
        System.out.println("[9] double release protection");
        ByteBuf body = PooledByteBufAllocator.DEFAULT.directBuffer(64);
        Object packet = new Object();
        SingleCopyPipeline.SingleCopyTicket ticket =
                SingleCopyPipeline.registerTicket(packet, body, 32, 1, 2, true, 0, 10, 8);
        assertTrue(ticket != null, "ticket registered");
        assertTrue(ticket.invalidate(), "first invalidate true");
        assertTrue(!ticket.invalidate(), "second invalidate false");
        assertEquals(0, body.refCnt(), "refCnt stays 0");
    }

    // ------------------------------------------------------------------
    // Failure-injection matrix
    // ------------------------------------------------------------------

    private static void testFailureMatrixBoundsPressure() {
        System.out.println("[10] matrix: pool pressure before allocation -> Java fallback");
        SingleCopyPipeline.resetForTesting();
        // Fill every slot; the next admission must fail closed WITHOUT
        // allocating (pressure fallback, not cap exhaustion).
        int filled = 0;
        while (SingleCopyPipeline.tryReserveSlot()) {
            filled++;
        }
        assertEquals(SingleCopyPipeline.MAX_OUTSTANDING_BODIES, filled, "hard bound count");
        long built = SingleCopyPipeline.telemetry().buffersCreated.get();
        long pressureBefore = SingleCopyPipeline.telemetry().pressureFallback.get();
        SingleCopyPipeline.SingleCopyTicket ticket = SingleCopyChunkBody.build(
                newFakePacket(20013, 20014), 0, 20013, 20014, 999999, true, true,
                resolvedChunkPacketId);
        assertTrue(ticket == null, "no body under pressure");
        assertEquals(built, SingleCopyPipeline.telemetry().buffersCreated.get(),
                "no buffer allocated");
        assertEquals(pressureBefore + 1, SingleCopyPipeline.telemetry().pressureFallback.get(),
                "pressure fallback counted");
        // Drain the synthetic reservations; accounting must return to zero.
        for (int i = 0; i < filled; i++) {
            SingleCopyPipeline.releaseSlot();
        }
        assertEquals(0, SingleCopyPipeline.outstandingBodyCount(), "accounting clean");
    }

    private static void testFailureMatrixStaleGeneration() throws Exception {
        System.out.println("[11] matrix: stale generation before encode -> fallback");
        SingleCopyPipeline.resetForTesting();
        int cx = 20015, cz = 20016;
        long genId = seedChunk(cx, cz, 2);
        NativeChunkBridge.unload(0, cx, cz); // generation is dead now
        long createdBefore = SingleCopyPipeline.telemetry().buffersCreated.get();
        SingleCopyPipeline.SingleCopyTicket ticket = SingleCopyChunkBody.build(
                newFakePacket(cx, cz), 0, cx, cz, genId, true, true, resolvedChunkPacketId);
        assertTrue(ticket == null, "stale generation rejected");
        assertEquals(createdBefore, SingleCopyPipeline.telemetry().buffersCreated.get(),
                "no buffer leaked on measure failure");
        assertEquals(0, SingleCopyPipeline.outstandingBodyCount(), "slot accounting clean");
    }

    private static void testFailureMatrixDownstreamFailure() throws Exception {
        System.out.println("[12] matrix: downstream write failure releases view + body");
        int cx = 20017, cz = 20018;
        long genId = seedChunk(cx, cz, 2);
        try {
            Object fakePacket = newFakePacket(cx, cz);
            SingleCopyPipeline.SingleCopyTicket ticket = SingleCopyChunkBody.build(
                    fakePacket, 0, cx, cz, genId, true, true, resolvedChunkPacketId);
            assertTrue(ticket != null, "body built");
            EmbeddedChannel channel = new EmbeddedChannel(
                    new FailingDownstreamHandler(),
                    new SingleCopyPipeline.RustCraftSingleCopyChunkHandler());
            boolean threw = false;
            try {
                channel.writeOutbound(fakePacket);
            } catch (Throwable expected) {
                threw = true;
            }
            assertTrue(threw, "downstream failure surfaced");
            SingleCopyPipeline.runQuiescenceCheck(ticket);
            assertEquals(0, ticket.body.refCnt(), "body released after failure");
            channel.finishAndReleaseAll();
        } finally {
            NativeChunkBridge.unload(0, cx, cz);
        }
    }

    /** Mimics a closed socket's head: the message is released and the write
     *  promise fails. Real Netty traverses the pipeline even on a closed
     *  channel (the head rejects); EmbeddedChannel refuses pre-traversal, so
     *  the tests model the closed-connection write with this handler. */
    private static final class FailingHeadHandler extends io.netty.channel.ChannelDuplexHandler {
        @Override
        public void write(io.netty.channel.ChannelHandlerContext ctx, Object msg,
                          io.netty.channel.ChannelPromise promise) throws Exception {
            ReferenceCountUtil.release(msg);
            promise.setFailure(new java.nio.channels.ClosedChannelException());
        }
    }

    /** Consumes (releases) the message like a codec would on error, then fails. */
    private static final class FailingDownstreamHandler extends io.netty.channel.ChannelDuplexHandler {
        @Override
        public void write(io.netty.channel.ChannelHandlerContext ctx, Object msg,
                          io.netty.channel.ChannelPromise promise) throws Exception {
            ReferenceCountUtil.release(msg);
            promise.setFailure(new java.io.IOException("injected compression failure"));
        }
    }

    private static void testFailureMatrixClosedChannelBeforeConsume() throws Exception {
        System.out.println("[13] matrix: channel closed before handler consumes");
        int cx = 20019, cz = 20020;
        long genId = seedChunk(cx, cz, 2);
        try {
            Object fakePacket = newFakePacket(cx, cz);
            SingleCopyPipeline.SingleCopyTicket ticket = SingleCopyChunkBody.build(
                    fakePacket, 0, cx, cz, genId, true, true, resolvedChunkPacketId);
            assertTrue(ticket != null, "body built");
            EmbeddedChannel channel = new EmbeddedChannel(
                    new FailingHeadHandler(),
                    new SingleCopyPipeline.RustCraftSingleCopyChunkHandler());
            try {
                channel.writeOutbound(fakePacket); // traversal runs; head rejects
            } catch (Throwable closedChannel) {
                // EmbeddedChannel rethrows the failed-write cause; fine.
            }
            SingleCopyPipeline.runQuiescenceCheck(ticket);
            assertEquals(0, ticket.body.refCnt(), "released via promise lifecycle");
            channel.releaseOutbound();
        } finally {
            NativeChunkBridge.unload(0, cx, cz);
        }
    }

    private static void testWritePacketDataGuardTwoCopyServeAndRefusal() throws Exception {
        System.out.println("[14] writePacketData guard: two-copy serve while alive, refusal when gone");
        PacketAuthorityExperiment.setSingleCopyModes(true, false);
        int cx = 20021, cz = 20022;
        long genId = seedChunk(cx, cz, 2);
        try {
            FakePacket fake = new FakePacket();
            fake.field_149284_a = cx;
            fake.field_149282_b = cz;
            fake.field_149279_g = true;
            Object fakePacket = fake;
            SingleCopyPipeline.SingleCopyTicket ticket = SingleCopyChunkBody.build(
                    fakePacket, 0, cx, cz, genId, true, true, resolvedChunkPacketId);
            assertTrue(ticket != null, "body built");
            fake.field_186948_c = ticket.mask; // admission would populate the shell
            PacketAuthorityExperiment.markAdmittedForTest(fakePacket);

            // Handler absent (install failure): the fallback serves correct
            // bytes through PacketBuffer.writeBytes (counted as payload copy).
            ByteBuf out = Unpooled.buffer(64);
            boolean served = PacketAuthorityExperiment.tryWritePacketDataDirect(fakePacket, out);
            assertTrue(served, "two-copy fallback served");
            byte[] bodyBytes = snapshot(ticket.body);
            byte[] servedBytes = new byte[out.readableBytes()];
            out.readBytes(servedBytes);
            // The writePacketData hook produces the body WITHOUT the packet-id
            // VarInt (vanilla's encoder writes it); compare after the id.
            net.minecraft.network.PacketBuffer idProbe = new net.minecraft.network.PacketBuffer(
                    Unpooled.wrappedBuffer(bodyBytes));
            int idSize = idProbe.func_150792_a() >= 0 ? idProbe.readerIndex() : -1;
            byte[] bodyWithoutId = Arrays.copyOfRange(bodyBytes, idSize, bodyBytes.length);
            if (!Arrays.equals(bodyWithoutId, servedBytes)) {
                System.err.println("  served=" + servedBytes.length + " bodyMinusId=" + bodyWithoutId.length);
                for (int i = 0; i < Math.min(24, Math.min(servedBytes.length, bodyWithoutId.length)); i++) {
                    System.err.println("  [" + i + "] served=" + Integer.toHexString(servedBytes[i] & 0xFF)
                            + " expected=" + Integer.toHexString(bodyWithoutId[i] & 0xFF));
                }
            }
            assertTrue(Arrays.equals(bodyWithoutId, servedBytes),
                    "fallback bytes == body bytes after the packet id");
            assertTrue(SingleCopyPipeline.telemetry().fallbackTwoCopyServes.get() >= 1,
                    "two-copy serve counted");
            assertTrue(SingleCopyPipeline.telemetry().packetbufferPayloadCopies.get() >= 1,
                    "payload copy counted on the fallback path");

            // Once the body is gone the guard must refuse loudly — never a
            // malformed empty-payload serialization.
            ticket.invalidate();
            boolean refused = false;
            try {
                PacketAuthorityExperiment.tryWritePacketDataDirect(fakePacket, Unpooled.buffer(16));
            } catch (IllegalStateException expected) {
                refused = true;
            }
            assertTrue(refused, "guard refuses released body");
        } finally {
            PacketAuthorityExperiment.setSingleCopyModes(false, false);
            NativeChunkBridge.unload(0, cx, cz);
        }
    }

    // ------------------------------------------------------------------
    // Generation unload/reload (same coordinates, new generation)
    // ------------------------------------------------------------------

    private static void testGenerationUnloadReloadSameCoords() throws Exception {
        System.out.println("[15] same-coords unload/reload: generation B, zero stale bytes");
        int cx = 20023, cz = 20024;
        int staleRefusals = 0;
        for (int cycle = 0; cycle < 50; cycle++) {
            long genA = seedChunk(cx, cz, 3, (byte) (0x10 + (cycle & 0xF)));
            assertTrue(genA > 0, "generation A registered (cycle " + cycle + ")");
            Object packetA = newFakePacket(cx, cz);
            SingleCopyPipeline.SingleCopyTicket ticketA = SingleCopyChunkBody.build(
                    packetA, 0, cx, cz, genA, true, true, resolvedChunkPacketId);
            assertTrue(ticketA != null, "body A built");
            byte[] bytesA = snapshot(ticketA.body);
            ticketA.invalidate();

            // Unload: canonical authority must immediately refuse the dead
            // generation (-3 when the entry is still mapped, -2 once the
            // unload has fully removed it; both are hard refusals).
            NativeChunkBridge.unload(0, cx, cz);
            long current = NativeChunkBridge.findGeneration(0, cx, cz);
            assertEquals(0, current, "findGeneration 0 after unload (cycle " + cycle + ")");
            long measured = NativeChunkBridge.encodePacketPayloadV2Measure(
                    0, cx, cz, genA, (byte) 1, (byte) 1);
            assertTrue(measured == -2 || measured == -3,
                    "stale generation A refused (cycle " + cycle + "): " + measured);
            staleRefusals++;

            // Reload the same coordinates: strictly newer generation, content B.
            long genB = seedChunk(cx, cz, 5, (byte) (0x40 + (cycle & 0xF)));
            assertTrue(genB > genA, "generation B is newer (cycle " + cycle + ")");
            // The old handle stays refused even though the entry exists again.
            long measuredOld = NativeChunkBridge.encodePacketPayloadV2Measure(
                    0, cx, cz, genA, (byte) 1, (byte) 1);
            assertEquals(-3, measuredOld,
                    "generation A refused against generation B entry (cycle " + cycle + ")");
            Object packetB = newFakePacket(cx, cz);
            SingleCopyPipeline.SingleCopyTicket ticketB = SingleCopyChunkBody.build(
                    packetB, 0, cx, cz, genB, true, true, resolvedChunkPacketId);
            assertTrue(ticketB != null, "body B built (cycle " + cycle + ")");
            byte[] bytesB = snapshot(ticketB.body);
            // Generation B carries the new content; zero stale bytes from A.
            assertTrue(!Arrays.equals(bytesA, bytesB),
                    "body B differs from body A (cycle " + cycle + ")");
            ticketB.invalidate();
        }
        assertEquals(50, staleRefusals, "every stale handle refused, no stale-handle loop");
        NativeChunkBridge.unload(0, cx, cz);
    }

    // ------------------------------------------------------------------
    // Retained fast path counters (no capture/transport machinery)
    // ------------------------------------------------------------------

    private static void testRetainedFastPathCleanCounters() throws Exception {
        System.out.println("[16] retained path freshness gate: fail-closed on unsynchronizable chunk");
        PacketAuthorityExperiment.resetForTesting(false, 64);
        PacketAuthorityExperiment.setEnabled(true);
        PacketAuthorityExperiment.setCap(64);
        PacketAuthorityExperiment.setReceiptVerifiedForTesting(true);
        PacketAuthorityExperiment.setSingleCopyModes(true, false);
        PacketAuthorityExperiment.setDirectNettyEnabled(false);
        PacketAuthorityExperiment.setDirectNettyShadow(false);
        com.rustcraft.bridge.capture.LiveWriterHooks.enableForTesting(Thread.currentThread());

        int cx = 20025, cz = 20026;
        long genId = seedChunk(cx, cz, 2);
        try {
            Object fakeChunk = new FakeChunk(cx, cz);
            // A REAL SPacketChunkData instance: the packet-id lookup and the
            // field population run against the actual vanilla class.
            Object fakePacket = new net.minecraft.network.play.server.SPacketChunkData();
            boolean admitted = PacketAuthorityExperiment.tryAuthority(
                    null, fakePacket, fakeChunk, 0xFFFF);
            // The retained admission runs a synchronous freshness sync first
            // (M4.2C race: registration light is a default until the first
            // full sync). An unsynchronizable chunk fails CLOSED to the Java
            // path - the offline FakeChunk cannot satisfy the real section
            // extraction, so the guard must refuse it.
            assertTrue(!admitted, "unsynchronizable chunk refused (fail closed)");
            assertTrue(PacketAuthorityExperiment.JAVA_FALLBACK.get() >= 1,
                    "refusal counted as Java fallback");
            assertTrue(PacketAuthorityExperiment.SINGLE_COPY_ADMITTED_COUNT.get() == 0,
                    "no single-copy admission without a fresh sync");
            // The authority path never populated the shell (the offline test
            // calls tryAuthority directly, so the vanilla constructor body is
            // not executed here - the field must still be the untouched
            // no-arg default, proving no Rust payload or heap copy was set).
            Object payloadField = packetField(fakePacket, "field_186949_d");
            // The real no-arg constructor leaves the payload field NULL; the
            // authority path (populatePacketFields) would have set a byte[]
            // (empty shell for direct mode). Still-null proves the authority
            // never touched the refused packet.
            assertTrue(payloadField == null,
                    "authority never populated the refused packet");
            NativeChunkBridge.unload(0, cx, cz);
        } finally {
            PacketAuthorityExperiment.setEnabled(false);
            PacketAuthorityExperiment.resetForTesting(false, 64);
            com.rustcraft.bridge.capture.LiveWriterHooks.disableForTesting();
        }
    }

    // ------------------------------------------------------------------
    // Shadow mode: real encoder output compared against the body
    // ------------------------------------------------------------------

    private static void testShadowModeRealEncoderOutputCompare() throws Exception {
        System.out.println("[17] shadow mode: real NettyPacketEncoder output == body");
        SingleCopyPipeline.resetForTesting();
        SingleCopyPipeline.setShadowModeForTest(true);
        try {
            int cx = 20027, cz = 20028;
            long genId = seedChunk(cx, cz, 4);
            // A REAL SPacketChunkData shell, reflect-populated with the Rust
            // payload so the real encoder serializes the same content.
            net.minecraft.network.play.server.SPacketChunkData packet =
                    new net.minecraft.network.play.server.SPacketChunkData();
            long measured = NativeChunkBridge.encodePacketPayloadV2Measure(
                    0, cx, cz, genId, (byte) 1, (byte) 1);
            PacketEncodeResultV2 m = PacketEncodeResultV2.decode(measured);
            assertTrue(m.isSuccess(), "measure success");
            ByteBuffer scratch = directScratch();
            long packed = NativeChunkBridge.encodePacketPayloadV2(0, cx, cz, genId,
                    (byte) 1, (byte) 1, getAddress(scratch), CAPACITY);
            PacketEncodeResultV2 enc = PacketEncodeResultV2.decode(packed);
            assertTrue(enc.isSuccess(), "encode success");
            byte[] payload = new byte[m.bytesWritten()];
            scratch.position(0);
            scratch.get(payload);
            setPacketField(packet, "field_149284_a", cx);
            setPacketField(packet, "field_149282_b", cz);
            setPacketField(packet, "field_149279_g", true);
            setPacketField(packet, "field_186948_c", m.emittedMask());
            setPacketField(packet, "field_186949_d", payload);
            setPacketField(packet, "field_189557_e", java.util.Collections.emptyList());

            // The single-copy body for the SAME packet object.
            SingleCopyPipeline.SingleCopyTicket ticket = SingleCopyChunkBody.build(
                    packet, 0, cx, cz, genId, true, true, resolvedChunkPacketId);
            assertTrue(ticket != null, "shadow body built");
            byte[] expected = snapshot(ticket.body);

            SingleCopyPipeline.RustCraftSingleCopyChunkHandler handler =
                    new SingleCopyPipeline.RustCraftSingleCopyChunkHandler();
            // Named anchors so the production lazy capture placement
            // (between "encoder" and "compress") runs exactly as in the live
            // pipeline.
            EmbeddedChannel channel = new EmbeddedChannel();
            channel.pipeline().addLast("prepender", new net.minecraft.network.NettyVarint21FrameEncoder());
            channel.pipeline().addLast("compress", new net.minecraft.network.NettyCompressionEncoder(256));
            channel.pipeline().addLast("encoder", new net.minecraft.network.NettyPacketEncoder(
                    net.minecraft.network.EnumPacketDirection.CLIENTBOUND));
            channel.pipeline().addLast("rustcraft_single_copy", handler);
            channel.attr(net.minecraft.network.NetworkManager.field_150739_c)
                    .set(net.minecraft.network.EnumConnectionState.PLAY);

            long matchesBefore = SingleCopyPipeline.telemetry().shadowMatches.get();
            long mismatchesBefore = SingleCopyPipeline.telemetry().shadowMismatches.get();
            channel.writeOutbound(packet);

            assertEquals(matchesBefore + 1, SingleCopyPipeline.telemetry().shadowMatches.get(),
                    "capture matched encoder output against the body");
            assertEquals(mismatchesBefore, SingleCopyPipeline.telemetry().shadowMismatches.get(),
                    "no shadow mismatches");

            ByteBuf framed = channel.readOutbound();
            byte[] wire = decodeFrame(framed);
            assertTrue(Arrays.equals(expected, wire),
                    "vanilla-encoded transmission == single-copy body");
            framed.release();

            // Ticket released on promise completion (shadow lifecycle).
            assertEquals(0, ticket.body.refCnt(), "shadow body released after compare");
            assertTrue(SingleCopyPipeline.ticketFor(packet) == null, "shadow ticket gone");
            channel.finishAndReleaseAll();
            NativeChunkBridge.unload(0, cx, cz);
        } finally {
            SingleCopyPipeline.setShadowModeForTest(false);
            SingleCopyPipeline.resetForTesting();
        }
    }

    private static void setPacketField(Object packet, String name, Object value) throws Exception {
        Field f = packet.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(packet, value);
    }

    // ------------------------------------------------------------------
    // 100k lifecycle stress with the required event mix
    // ------------------------------------------------------------------

    private static void testHundredKLifecycleStress() throws Exception {
        System.out.println("[18] 100k lifecycle stress (70/10/5/5/5/5 mix)");
        SingleCopyPipeline.resetForTesting();
        PacketAuthorityExperiment.resetForTesting(false, Long.MAX_VALUE);
        PacketAuthorityExperiment.setSingleCopyModes(true, false);
        PacketAuthorityExperiment.setEnabled(false); // drive the builder directly

        final int TOTAL = 100_000;
        final int CHUNKS = 8;
        int cxBase = 20100;
        int czBase = 20101;
        long[] gens = new long[CHUNKS];
        for (int i = 0; i < CHUNKS; i++) {
            gens[i] = seedChunk(cxBase + i, czBase, 2, (byte) i);
        }
        long createdBefore = SingleCopyPipeline.telemetry().buffersCreated.get();
        long releasedBefore = SingleCopyPipeline.telemetry().buffersReleased.get();

        Random random = new Random(340);
        EmbeddedChannel[] channels = new EmbeddedChannel[4];
        for (int i = 0; i < channels.length; i++) {
            channels[i] = new EmbeddedChannel(new SingleCopyPipeline.RustCraftSingleCopyChunkHandler());
        }
        int committed = 0, cancelled = 0, stale = 0, pressure = 0, disconnects = 0, downstream = 0;
        List<SingleCopyPipeline.SingleCopyTicket> openTickets = new ArrayList<>();
        int unexpected = 0;

        for (int i = 0; i < TOTAL; i++) {
            int roll = random.nextInt(100);
            int cx = cxBase + random.nextInt(CHUNKS);
            Object packet = newFakePacket(cx, czBase);
            if (roll < 70) {
                // committed: admit + write + settle
                SingleCopyPipeline.SingleCopyTicket t = SingleCopyChunkBody.build(
                        packet, 0, cx, czBase, gens[cx - cxBase], true, true, resolvedChunkPacketId);
                if (t == null) { unexpected++; continue; }
                EmbeddedChannel ch = channels[random.nextInt(channels.length)];
                if (!ch.isOpen()) {
                    // A prior disconnect event closed it; refresh so this
                    // committed event still exercises a real write.
                    ch.releaseOutbound();
                    ch = new EmbeddedChannel(new SingleCopyPipeline.RustCraftSingleCopyChunkHandler());
                    channels[random.nextInt(channels.length)] = ch;
                }
                ch.writeOutbound(packet);
                drainOutbound(ch);
                SingleCopyPipeline.runQuiescenceCheck(t);
                committed++;
            } else if (roll < 80) {
                // cancelled: admitted, never written, released (sweep model)
                SingleCopyPipeline.SingleCopyTicket t = SingleCopyChunkBody.build(
                        packet, 0, cx, czBase, gens[cx - cxBase], true, true, resolvedChunkPacketId);
                if (t == null) { unexpected++; continue; }
                openTickets.add(t);
                cancelled++;
            } else if (roll < 85) {
                // generation invalid: stale handle -> Java fallback
                SingleCopyPipeline.SingleCopyTicket t = SingleCopyChunkBody.build(
                        packet, 0, cx, czBase, gens[cx - cxBase] + 1_000_000, true, true,
                        resolvedChunkPacketId);
                if (t == null) { stale++; } else { unexpected++; t.invalidate(); }
            } else if (roll < 90) {
                // pool pressure: fill the bounds, expect fallback, drain
                int held = 0;
                while (SingleCopyPipeline.tryReserveSlot()) held++;
                if (held > 0) pressure++;
                for (int r = 0; r < held; r++) SingleCopyPipeline.releaseSlot();
            } else if (roll < 95) {
                // disconnect: write through a failing head (closed-socket model)
                SingleCopyPipeline.SingleCopyTicket t = SingleCopyChunkBody.build(
                        packet, 0, cx, czBase, gens[cx - cxBase], true, true, resolvedChunkPacketId);
                if (t == null) { unexpected++; continue; }
                EmbeddedChannel ch = new EmbeddedChannel(
                        new FailingHeadHandler(),
                        new SingleCopyPipeline.RustCraftSingleCopyChunkHandler());
                try {
                    ch.writeOutbound(packet);
                } catch (Throwable closedChannel) {
                    // expected: the write fails at the head
                }
                SingleCopyPipeline.runQuiescenceCheck(t);
                ch.finishAndReleaseAll();
                disconnects++;
            } else {
                // injected downstream failure
                SingleCopyPipeline.SingleCopyTicket t = SingleCopyChunkBody.build(
                        packet, 0, cx, czBase, gens[cx - cxBase], true, true, resolvedChunkPacketId);
                if (t == null) { unexpected++; continue; }
                EmbeddedChannel ch = new EmbeddedChannel(
                        new FailingDownstreamHandler(),
                        new SingleCopyPipeline.RustCraftSingleCopyChunkHandler());
                try {
                    ch.writeOutbound(packet);
                } catch (Throwable expected) {
                    downstream++;
                }
                SingleCopyPipeline.runQuiescenceCheck(t);
                ch.finishAndReleaseAll();
            }
            if (openTickets.size() > 64) {
                for (SingleCopyPipeline.SingleCopyTicket t : openTickets) {
                    assertTrue(t.invalidate(), "sweep release exactly once");
                }
                openTickets.clear();
            }
            if (i % 5000 == 4999) {
                // refresh closed channels so later events keep exercising writes
                for (int c = 0; c < channels.length; c++) {
                    if (!channels[c].isOpen()) {
                        channels[c].releaseOutbound();
                        channels[c] = new EmbeddedChannel(
                                new SingleCopyPipeline.RustCraftSingleCopyChunkHandler());
                    }
                }
            }
        }
        for (SingleCopyPipeline.SingleCopyTicket t : openTickets) {
            assertTrue(t.invalidate(), "final sweep release exactly once");
        }
        for (EmbeddedChannel ch : channels) {
            drainOutbound(ch);
            ch.finishAndReleaseAll();
        }

        System.out.println("  committed=" + committed + " cancelled=" + cancelled
                + " stale=" + stale + " pressure=" + pressure
                + " disconnects=" + disconnects + " downstream=" + downstream
                + " unexpected=" + unexpected);
        assertTrue(unexpected == 0, "no unexpected failures");
        assertTrue(committed >= 69_000 && committed <= 71_000, "committed ~70%");
        assertTrue(disconnects >= 4_000, "disconnect path exercised");
        assertEquals(0, SingleCopyPipeline.outstandingBodyCount(), "outstanding == 0");
        long created = SingleCopyPipeline.telemetry().buffersCreated.get() - createdBefore;
        long released = SingleCopyPipeline.telemetry().buffersReleased.get() - releasedBefore;
        assertEquals(created, released, "buffers_created == buffers_released");
        for (int i = 0; i < CHUNKS; i++) {
            NativeChunkBridge.unload(0, cxBase + i, czBase);
        }
        PacketAuthorityExperiment.resetForTesting(false, 64);
        SingleCopyPipeline.resetForTesting();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static ByteBuffer SCRATCH = ByteBuffer.allocateDirect(CAPACITY);

    private static ByteBuffer directScratch() {
        SCRATCH.clear();
        return SCRATCH;
    }

    private static long getAddress(ByteBuffer buf) {
        try {
            Field f = java.nio.Buffer.class.getDeclaredField("address");
            f.setAccessible(true);
            return f.getLong(buf);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Seeds a chunk with `sections` sections; section 0's sky-light array is
     *  filled with `signature` (when >= 0) for content identity. */
    private static long seedChunk(int cx, int cz, int sections) {
        return seedChunk(cx, cz, sections, (byte) -1);
    }

    private static long seedChunk(int cx, int cz, int sections, byte signature) {
        byte[] transport = buildTransport(cx, cz, sections, signature);
        ByteBuffer in = ByteBuffer.allocateDirect(transport.length).order(ByteOrder.nativeOrder());
        in.put(transport);
        long addr = getAddress(in);
        return NativeChunkBridge.seedFromTransport(addr, transport.length);
    }

    private static byte[] buildTransport(int cx, int cz, int sections, byte signature) {
        ByteBuffer buf = ByteBuffer.allocate(262144).order(ByteOrder.BIG_ENDIAN);
        buf.put("RCSNAP02".getBytes());
        buf.putShort((short) 2);
        buf.put((byte) 3); // full=1, skylight=1
        buf.put((byte) 1); // storage present
        buf.put((byte) 18); // bits
        buf.put((byte) 1); // scope
        buf.putShort((short) 0);
        buf.putInt(0); // dim
        buf.putInt(cx);
        buf.putInt(cz);
        buf.putLong(1L); // generation
        buf.putShort((short) 0xffff); // filter
        buf.putShort((short) ((1 << sections) - 1)); // mask
        for (int i = 0; i < 7; i++) buf.putLong(1L);
        buf.put(new byte[32]); // digest
        buf.putShort((short) sections);
        buf.putInt(157010); // registry size (18 bits)
        buf.put((byte) 18);
        for (int y = 0; y < sections; y++) {
            buf.put((byte) y);
            buf.put((byte) 0);
            buf.putShort((short) 4096);
            buf.putShort((short) 4); // palette len 4
            buf.putShort((short) 1).putShort((short) 3).putShort((short) 2).putShort((short) 4);
            buf.put((byte) 4); // bits per block
            buf.putShort((short) 256); // words
            for (int w = 0; w < 256; w++) buf.putLong(0x0123012301230123L);
            buf.put(new byte[2048]); // block light
            byte[] sky = new byte[2048];
            Arrays.fill(sky, y == 0 && signature >= 0 ? signature : (byte) (255 - y));
            buf.put(sky);
        }
        byte[] biomes = new byte[256];
        Arrays.fill(biomes, (byte) 4);
        buf.put(biomes);
        byte[] out = new byte[buf.position()];
        buf.flip();
        buf.get(out);
        return out;
    }

    private static byte[] snapshot(ByteBuf buf) {
        byte[] out = new byte[buf.readableBytes()];
        buf.getBytes(buf.readerIndex(), out);
        return out;
    }

    private static void drainOutbound(EmbeddedChannel channel) {
        Object out;
        while ((out = channel.readOutbound()) != null) {
            ReferenceCountUtil.release(out);
        }
    }

    private static byte[] decodeFrame(ByteBuf framed) throws Exception {
        // prepender: VarInt(compressedLen) + [VarInt(uncompressedLen) + deflate]
        int r = framed.readerIndex();
        long frameLen = readVarInt(framed);
        assertEquals(frameLen, framed.readableBytes(), "frame length prefix matches body");
        long uncompressedLen = readVarInt(framed);
        byte[] compressed = new byte[framed.readableBytes()];
        framed.readBytes(compressed);
        Inflater inflater = new Inflater();
        inflater.setInput(compressed);
        byte[] out = new byte[(int) uncompressedLen];
        int produced = inflater.inflate(out);
        assertTrue(inflater.finished(), "deflate stream complete");
        assertEquals(uncompressedLen, produced, "inflated length matches header");
        inflater.end();
        framed.readerIndex(r);
        return out;
    }

    private static long readVarInt(ByteBuf buf) {
        long value = 0;
        int shift = 0;
        for (int i = 0; i < 5; i++) {
            byte b = buf.readByte();
            value |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return value;
            }
            shift += 7;
        }
        throw new IllegalStateException("varint too long");
    }

    // ---- fake chunk / packet shaped for the experiment's reflection ----

    public static class FakeProvider {
        public int getDimension() { return 0; }
        public boolean hasSkyLight() { return true; }
        public boolean func_191066_m() { return true; }
    }

    public static class FakeWorld {
        public final FakeProvider field_73011_w = new FakeProvider();
    }

    public static class FakeChunk {
        public final int field_76635_g;
        public final int field_76647_h;
        public final FakeWorld field_76637_e = new FakeWorld();

        public FakeChunk(int x, int z) {
            this.field_76635_g = x;
            this.field_76647_h = z;
        }

        public java.util.Map<?, ?> func_177434_r() {
            return java.util.Collections.emptyMap();
        }
    }

    public static class FakePacket {
        public int field_149284_a;
        public int field_149282_b;
        public int field_186948_c;
        public byte[] field_186949_d = new byte[0];
        public java.util.List<?> field_189557_e = java.util.Collections.emptyList();
        public boolean field_149279_g = true;
    }

    private static Object newFakePacket(int cx, int cz) {
        FakePacket p = new FakePacket();
        p.field_149284_a = cx;
        p.field_149282_b = cz;
        return p;
    }

    private static Object packetField(Object packet, String name) throws Exception {
        Field f = packet.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(packet);
    }

    private static void loadNativeLibrary() {
        Path[] candidates = new Path[] {
                Paths.get("target/release/rustcraft_ffi.dll"),
                Paths.get("rustcraft_ffi.dll"),
                Paths.get("c:/rustcraft/target/release/rustcraft_ffi.dll"),
        };
        for (Path p : candidates) {
            if (Files.exists(p)) {
                System.load(p.toAbsolutePath().toString());
                System.out.println("[OK] loaded native library: " + p.toAbsolutePath());
                if (!NativeChunkBridge.isAvailable()) {
                    throw new IllegalStateException("bridge did not bind the native library");
                }
                return;
            }
        }
        throw new IllegalStateException("rustcraft_ffi.dll not found");
    }
}
