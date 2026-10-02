package com.rustcraft.bridge;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;

/**
 * Builds the COMPLETE pre-compression SPacketChunkData body for one packet as
 * a single immutable pooled direct Netty ByteBuf:
 *
 * <pre>
 *   VarInt  packetId            (EnumConnectionState PLAY/CLIENTBOUND lookup, never hardcoded)
 *   int BE   chunkX
 *   int BE   chunkZ
 *   boolean fullChunk
 *   VarInt  availableSections   (the mask of sections actually written)
 *   VarInt  payloadLength       (exact, from the native measure export)
 *   byte[]  NativeChunk payload (ONE native write straight into this buffer)
 *   VarInt  tileEntityCount = 0
 * </pre>
 *
 * This is byte-for-byte the buffer vanilla's NettyPacketEncoder would produce
 * for the same packet (verified by the shadow capture and offline parity
 * tests). Exactly one large payload memory movement happens here: the native
 * section wire cache is written directly at
 * {@code buf.memoryAddress() + writerIndex} by
 * {@code NativeChunkBridge.encodePacketPayloadV2}. No PacketBuffer.writeBytes,
 * no heap byte[], no intermediate buffer.
 *
 * The two-pass structure exists because the wire format places the payload
 * length VarInt BEFORE the payload: the exact length must be known before the
 * payload destination address can carry it contiguously. The measure export
 * scans the chunk without writing; any measure/encode divergence (e.g. a
 * native refresh between the two crossings) fails closed to the Java path.
 *
 * The returned buffer is frozen after {@link #build}: writerIndex never
 * advances again, contents never change. Consumers take retainedDuplicate()
 * views with independent indices, so one body can be serialized to several
 * connections (vanilla sends one changed-chunk SPacketChunkData object to
 * every watcher) without index races.
 */
public final class SingleCopyChunkBody {

    /** Matches the V2 scratch capacity the retained path has always used. */
    public static final int BUFFER_CAPACITY = 262144;

    /**
     * Worst-case header: VarInt id (1, ids < 128 in PLAY clientbound) +
     * 4 + 4 + 1 + VarInt mask (<=3 for 0xFFFF) + VarInt len (<=3 for < 2 MiB)
     * = 16. Payload is always encoded after a header this small or smaller.
     */
    public static final int MAX_HEADER_BYTES = 16;

    private SingleCopyChunkBody() { }

    /**
     * Build and freeze the complete body. Returns null on any failure
     * (resource pressure, measure failure, encode failure, measure/encode
     * divergence) — the caller falls back to the untouched Java path. The
     * returned ticket owns the body with refCnt 1.
     */
    public static SingleCopyPipeline.SingleCopyTicket build(
            Object packet,
            int dim, int cx, int cz, long generationId,
            boolean skylight, boolean fullChunk,
            int packetId) {
        if (!NativeChunkBridge.isAvailable()) {
            return null;
        }

        // Resource bounds (count + bytes) BEFORE allocation. Fallback is a
        // pressure fallback, not a cap exhaustion.
        if (!SingleCopyPipeline.tryReserveSlot()) {
            SingleCopyPipeline.telemetry().pressureFallback.incrementAndGet();
            return null;
        }

        ByteBuf buf = null;
        try {
            long packedMeasure = NativeChunkBridge.encodePacketPayloadV2Measure(
                    dim, cx, cz, generationId,
                    (byte) (skylight ? 1 : 0), (byte) (fullChunk ? 1 : 0));
            PacketEncodeResultV2 measure = PacketEncodeResultV2.decode(packedMeasure);
            if (!measure.isSuccess()) {
                SingleCopyPipeline.telemetry().measureFailures.incrementAndGet();
                SingleCopyPipeline.releaseSlot();
                return null;
            }
            int measuredLen = measure.bytesWritten();
            int measuredMask = measure.emittedMask();

            buf = PooledByteBufAllocator.DEFAULT.directBuffer(BUFFER_CAPACITY);
            SingleCopyPipeline.telemetry().buffersCreated.incrementAndGet();

            // ---- header (exact minimal VarInts, byte-identical to vanilla) --
            writeVarInt(buf, packetId);
            buf.writeInt(cx);
            buf.writeInt(cz);
            buf.writeBoolean(fullChunk);
            writeVarInt(buf, measuredMask);
            writeVarInt(buf, measuredLen);
            if (buf.writerIndex() > MAX_HEADER_BYTES) {
                // Header must never push the payload past the assumed maximum;
                // a violation would mean the VarInt assumptions above broke.
                buf.release();
                buf = null;
                SingleCopyPipeline.releaseSlot();
                SingleCopyPipeline.telemetry().measureFailures.incrementAndGet();
                return null;
            }

            // ---- payload: the ONE large payload memory movement ------------
            int payloadOffset = buf.writerIndex();
            long payloadAddress = buf.memoryAddress() + buf.writerIndex();
            int remaining = buf.capacity() - buf.writerIndex();
            long packed = NativeChunkBridge.encodePacketPayloadV2(
                    dim, cx, cz, generationId,
                    (byte) (skylight ? 1 : 0), (byte) (fullChunk ? 1 : 0),
                    payloadAddress, remaining);
            PacketEncodeResultV2 result = PacketEncodeResultV2.decode(packed);
            if (!result.isSuccess()) {
                SingleCopyPipeline.telemetry().encodeFailures.incrementAndGet();
                buf.release();
                buf = null;
                SingleCopyPipeline.releaseSlot();
                return null;
            }
            if (result.bytesWritten() != measuredLen || result.emittedMask() != measuredMask) {
                // Native state moved between measure and encode (native refresh
                // thread). Fail closed: discard the buffer, never ship a
                // mis-framed body.
                SingleCopyPipeline.telemetry().measureEncodeDivergences.incrementAndGet();
                buf.release();
                buf = null;
                SingleCopyPipeline.releaseSlot();
                return null;
            }
            buf.writerIndex(buf.writerIndex() + measuredLen);

            // ---- trailer: tile entity count 0 (TE chunks are never admitted)
            writeVarInt(buf, 0);

            SingleCopyPipeline.SingleCopyTicket ticket =
                    SingleCopyPipeline.registerTicket(packet, buf, packetId, cx, cz,
                            fullChunk, measuredMask, measuredLen, payloadOffset);
            if (ticket == null) {
                // Registration raced a shutdown/release; the buffer is already
                // released by the registry on that path.
                SingleCopyPipeline.releaseSlot();
                return null;
            }
            buf = null; // ownership moved to the ticket
            SingleCopyPipeline.telemetry().committed.incrementAndGet();
            SingleCopyPipeline.telemetry().bodyBytes.addAndGet(measuredLen);
            SingleCopyPipeline.noteOutstandingBytes(measuredLen);
            return ticket;
        } catch (Throwable t) {
            SingleCopyPipeline.telemetry().bodyBuildErrors.incrementAndGet();
            if (buf != null) {
                try { buf.release(); } catch (Throwable ignore) { }
            }
            SingleCopyPipeline.releaseSlot();
            return null;
        }
    }

    /** Minimal VarInt encoding, identical to vanilla PacketBuffer.func_150787_b. */
    public static void writeVarInt(ByteBuf buf, int value) {
        while ((value & -128) != 0) {
            buf.writeByte(value & 127 | 128);
            value >>>= 7;
        }
        buf.writeByte(value);
    }
}
