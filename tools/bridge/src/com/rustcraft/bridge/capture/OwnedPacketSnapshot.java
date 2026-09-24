package com.rustcraft.bridge.capture;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.atomic.AtomicLong;
import com.rustcraft.bridge.capture.CaptureContract.Domain;
import com.rustcraft.bridge.capture.CaptureContract.StorageModel;
import com.rustcraft.bridge.capture.CaptureContract.WriterClass;

/** Owned immutable schema 1 input. No Java live chunk/section/array references. */
public final class OwnedPacketSnapshot {
    public static final int SCHEMA_VERSION = 1;
    public static final String SCOPE = "SYNTHETIC_OFFLINE";
    private static final AtomicLong EVENTS = new AtomicLong();
    public final int dimension, chunkX, chunkZ, requestedFilter, acceptedMask;
    public final long incarnation, generation, captureStartGuard, captureEndGuard;
    public final boolean fullChunk, skylight;
    public final StorageModel storageModel;
    public final int globalPaletteBits;
    public final long captureThreadId;
    public final long canonicalOwnerThreadId, eventId;
    public final String captureThreadName, captureContext, writerInventoryId, provenance;
    public final String integrityMethod;
    public final boolean tileEntityRevalidated;
    public final CaptureContract.TileEntityPolicy tileEntityPolicy;
    private final Section[] sections;
    private final byte[] biomes;
    private final Map<Domain, WriterClass> writers;

    public static final class Section {
        public final int y, blockRefCount;
        public final boolean empty;
        private final long[] logicalStates;
        private final byte[] blockLight, skyLight, extendedHigh;

        Section(int y, long[] logicalStates, byte[] blockLight, byte[] skyLight,
                byte[] extendedHigh, boolean empty, int blockRefCount) {
            this.y = y;
            this.logicalStates = logicalStates.clone();
            this.blockLight = blockLight.clone();
            this.skyLight = skyLight == null ? null : skyLight.clone();
            this.extendedHigh = extendedHigh == null ? null : extendedHigh.clone();
            this.empty = empty;
            this.blockRefCount = blockRefCount;
        }

        public long[] logicalStates() { return logicalStates.clone(); }
        public byte[] blockLight() { return blockLight.clone(); }
        public byte[] skyLight() { return skyLight == null ? null : skyLight.clone(); }
        public byte[] extendedHigh() { return extendedHigh == null ? null : extendedHigh.clone(); }
    }

    OwnedPacketSnapshot(CaptureSource.View begin, CaptureSource.View end,
                        CaptureContract.Context context, Section[] sections,
                        byte[] biomes, int acceptedMask, boolean tileEntityRevalidated) {
        this.dimension = begin.dimension;
        this.chunkX = begin.chunkX;
        this.chunkZ = begin.chunkZ;
        this.requestedFilter = begin.requestedFilter;
        this.acceptedMask = acceptedMask;
        this.incarnation = begin.incarnation;
        this.generation = begin.generation;
        this.captureStartGuard = begin.mutationEpoch;
        this.captureEndGuard = end.mutationEpoch;
        this.fullChunk = begin.fullChunk;
        this.skylight = begin.skylight;
        this.storageModel = begin.storageModel;
        this.globalPaletteBits = begin.globalPaletteBits;
        this.captureThreadId = Thread.currentThread().getId();
        this.canonicalOwnerThreadId = context.canonicalServerThread.getId();
        this.eventId = EVENTS.incrementAndGet();
        if (eventId <= 0) throw new IllegalStateException("Capture event identity exhausted");
        this.captureThreadName = Thread.currentThread().getName();
        this.captureContext = context.captureContext;
        this.writerInventoryId = context.inventoryId;
        this.provenance = begin.provenance;
        this.integrityMethod = "exact-array-equality-plus-qualified-writer-exclusion";
        this.tileEntityRevalidated = tileEntityRevalidated;
        this.tileEntityPolicy = context.tileEntityPolicy;
        this.sections = sections.clone();
        this.biomes = biomes == null ? null : biomes.clone();
        EnumMap<Domain, WriterClass> copy = new EnumMap<Domain, WriterClass>(Domain.class);
        copy.putAll(context.writerClasses());
        this.writers = Collections.unmodifiableMap(copy);
    }

    /** The Section object is itself immutable and every array accessor copies. */
    public Section section(int y) { return sections[y]; }
    public byte[] biomes() { return biomes == null ? null : biomes.clone(); }
    public Map<Domain, WriterClass> writerClasses() { return writers; }

    /**
     * Owned RCSNAP01 big-endian transport. Provenance digest is an identity
     * receipt only; capture acceptance already required exact equality and
     * qualified writer exclusion. This does not enable packet publication.
     */
    public byte[] toTransportBytes() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeBytes("RCSNAP01");
            out.writeShort(SCHEMA_VERSION);
            out.writeByte((fullChunk ? 1 : 0) | (skylight ? 2 : 0));
            out.writeByte(storageModel == StorageModel.VANILLA_U16 ? 1 : 2);
            out.writeByte(globalPaletteBits);
            out.writeByte(1); // SYNTHETIC_OFFLINE, never an authority permit
            out.writeShort(0);
            out.writeInt(dimension);
            out.writeInt(chunkX);
            out.writeInt(chunkZ);
            out.writeLong(generation);
            out.writeShort(requestedFilter);
            out.writeShort(acceptedMask);
            out.writeLong(eventId);
            out.writeLong(canonicalOwnerThreadId);
            out.writeLong(captureThreadId);
            out.writeLong(captureStartGuard);
            out.writeLong(captureEndGuard);
            out.writeLong(incarnation);
            out.writeLong(incarnation);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            // Length delimiters make different provenance tuples distinct even
            // when a declared string itself contains a newline or separator.
            ByteArrayOutputStream identityBytes = new ByteArrayOutputStream();
            DataOutputStream identity = new DataOutputStream(identityBytes);
            for (String field : new String[] {provenance, writerInventoryId, captureContext}) {
                byte[] utf8 = field.getBytes(StandardCharsets.UTF_8);
                identity.writeInt(utf8.length);
                identity.write(utf8);
            }
            out.write(digest.digest(identityBytes.toByteArray()));
            if (bytes.size() != 128) throw new AssertionError("Transport header length");
            out.writeShort(Integer.bitCount(acceptedMask));
            for (int y = 0; y < 16; y++) if ((acceptedMask & (1 << y)) != 0) {
                Section section = sections[y];
                if (section == null) throw new IllegalStateException("Accepted mask has no owned section");
                out.writeByte(y);
                out.writeByte(0);
                out.writeShort(section.blockRefCount);
                for (long state : section.logicalStates) {
                    if (state < 0 || state > 0xFFFFFFFFL) throw new IllegalStateException("State exceeds schema u32");
                    out.writeInt((int) state);
                }
                out.write(section.blockLight);
                if (skylight) out.write(section.skyLight);
            }
            if (fullChunk) out.write(biomes);
            out.flush();
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException("In-memory transport failed", impossible);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Java SHA-256 is unavailable", impossible);
        }
    }
}
