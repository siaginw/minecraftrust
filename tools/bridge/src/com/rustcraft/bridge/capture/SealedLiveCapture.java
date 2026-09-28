package com.rustcraft.bridge.capture;

import java.util.Arrays;

/**
 * One SEALED immutable live-SHADOW capture: the scope-3 transport owner plus the
 * cloned Java packet reference payload. Every byte needed downstream is owned
 * here; there are no live Chunk/World/EBS/palette/collection references and no
 * mutable Java backing array. Sealing is one-way (the constructor is the only
 * writer and CaptureDraft is the only caller); a sealed capture is never
 * reopened, resealed, or reused for another packet event.
 *
 * <p>Safe to process after the constructor and the capture gate are gone: the
 * comparison consumer reads owned values only and can never call back into
 * live chunk state.</p>
 */
public final class SealedLiveCapture {

    private final long gateEventId;
    private final LiveChunkBindings.BindingIdentity identity;
    private final OwnedPacketSnapshot owned;
    private final byte[] javaPayload;
    private final int javaMask;
    private final boolean javaFullChunk;
    private final int packetX;
    private final int packetZ;
    /** Phase-D telemetry: seal time, so queue wait is measurable downstream. */
    private final long sealedAtNanos;

    SealedLiveCapture(long gateEventId, LiveChunkBindings.BindingIdentity identity,
                      OwnedPacketSnapshot owned, byte[] javaPayload, int javaMask,
                      boolean javaFullChunk, int packetX, int packetZ) {
        this.gateEventId = gateEventId;
        this.identity = identity;
        this.owned = owned;
        this.javaPayload = javaPayload.clone();
        this.javaMask = javaMask;
        this.javaFullChunk = javaFullChunk;
        this.packetX = packetX;
        this.packetZ = packetZ;
        this.sealedAtNanos = System.nanoTime();
    }

    /** Gate-minted packet/capture event identity for this one event. */
    public long gateEventId() { return gateEventId; }

    /** Seal timestamp (nanos); the consumer's queue-wait measurement reads it. */
    public long sealedAtNanos() { return sealedAtNanos; }

    public LiveChunkBindings.BindingIdentity identity() { return identity; }

    public int javaMask() { return javaMask; }

    public boolean javaFullChunk() { return javaFullChunk; }

    public int packetX() { return packetX; }

    public int packetZ() { return packetZ; }

    public int javaPayloadLength() { return javaPayload.length; }

    /** The requested section filter captured with this event. */
    public int requestedFilter() { return owned.requestedFilter; }

    /** The sealed capture's own accepted section mask. */
    public int sealedMask() { return owned.acceptedMask; }

    /** Chunk coordinates as recorded in the owned snapshot (descriptive metadata). */
    public int chunkX() { return owned.chunkX; }

    public int chunkZ() { return owned.chunkZ; }

    /** Writer-protocol epoch pair captured with the event (begin == end on seal). */
    public long captureEpochStart() { return owned.captureStartGuard; }

    public long captureEpochEnd() { return owned.captureEndGuard; }

    /** Cloned on every read: the sealed bytes are never shared mutable state. */
    public byte[] javaPayload() { return javaPayload.clone(); }

    /** Owned RCSNAP01 scope-3 transport (big-endian, fully self-contained). */
    public byte[] toTransportBytes() { return owned.toTransportBytes(); }

    /** True when this capture's chunk was adopted through the live IO-ticket path. */
    public boolean ioAdopted() { return LiveWriterHooks.isIoAdoptedChunkId(identity.chunkId); }

    /** Diagnostic equality receipt for comparison records (owned values only). */
    public boolean javaPayloadEquals(byte[] candidate) {
        return Arrays.equals(javaPayload, candidate);
    }
}
