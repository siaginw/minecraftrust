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
    public final CaptureContract.Scope scope;
    private static final AtomicLong EVENTS = new AtomicLong();
    public final int dimension, chunkX, chunkZ, requestedFilter, acceptedMask;
    public final long incarnation, generation, captureStartGuard, captureEndGuard;
    public final boolean fullChunk, skylight;
    public final StorageModel storageModel;
    public final int globalPaletteBits;
    /** Source registry cardinality: TELEMETRY ONLY (V2 transport). */
    public final long globalRegistrySize;
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
                        byte[] biomes, int acceptedMask, boolean tileEntityRevalidated, CaptureContract.Scope scope) {
        this.scope = scope;
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
        this.globalRegistrySize = begin.globalRegistrySize;
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
            // Exhaustive scope switch: synthetic=1, oracle=2, live-shadow=3.
            // Anything else is a programming error, never an oracle relabel.
            final byte scopeVersion;
            switch (scope) {
                case SYNTHETIC_OFFLINE:
                    scopeVersion = 1;
                    break;
                case REAL_CLEAN_FORGE_ORACLE:
                    scopeVersion = 2;
                    break;
                case LIVE_SHADOW_OWNED_V1:
                    scopeVersion = 3;
                    break;
                default:
                    throw new IllegalStateException("Unsupported capture scope: " + scope);
            }
            out.writeByte(scopeVersion);
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

    /**
     * Owned RCSNAP02 big-endian transport: the LOGICAL section
     * representation, decoupled from the runtime's global registry width.
     *
     * <p>V2 exists because a runtime registry may require more global bits
     * than the u16 logical domain (measured on Revelation: 157,010 states
     * -> 18 bits) while a chunk's ACTUAL logical state ids still fit u16.
     * Each non-empty section carries its own u16 logical palette plus packed
     * indices; {@link #globalPaletteBits} and {@link #globalRegistrySize}
     * ride along as SOURCE TELEMETRY and never gate representability. Every
     * palette value is a logical global block-state id, each <= 65535; ids
     * above that are excluded by the capture path BEFORE transport.</p>
     */
    public byte[] toTransportBytesV2() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeBytes("RCSNAP02");
            out.writeShort(2);
            out.writeByte((fullChunk ? 1 : 0) | (skylight ? 2 : 0));
            out.writeByte(storageModel == StorageModel.VANILLA_U16 ? 1 : 2);
            out.writeByte(globalPaletteBits);   // SOURCE TELEMETRY: no V2 gate
            final byte scopeVersion;
            switch (scope) {
                case SYNTHETIC_OFFLINE: scopeVersion = 1; break;
                case REAL_CLEAN_FORGE_ORACLE: scopeVersion = 2; break;
                case LIVE_SHADOW_OWNED_V1: scopeVersion = 3; break;
                default: throw new IllegalStateException("Unsupported capture scope: " + scope);
            }
            out.writeByte(scopeVersion);
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
            // Source registry telemetry: informational, never a gate.
            out.writeInt((int) globalRegistrySize);
            out.writeByte(globalPaletteBits);
            for (int y = 0; y < 16; y++) if ((acceptedMask & (1 << y)) != 0) {
                Section section = sections[y];
                if (section == null) throw new IllegalStateException("Accepted mask has no owned section");
                long[] logical = section.logicalStates;
                // Deterministic palette: first-appearance order over the 4096
                // cells -- independent of any Java palette history by
                // construction. Every entry is a u16 logical global id.
                int[] palette = new int[4096];
                int cardinality = 0;
                int[] index = new int[4096];
                java.util.HashMap<Long, Integer> seen = new java.util.HashMap<Long, Integer>();
                for (int i = 0; i < 4096; i++) {
                    long state = logical[i];
                    if (state < 0 || state > 0xFFFFL)
                        throw new IllegalStateException("logical state exceeds u16 domain");
                    Integer existing = seen.get(state);
                    if (existing == null) {
                        existing = cardinality;
                        seen.put(state, existing);
                        palette[cardinality++] = (int) state;
                    }
                    index[i] = existing;
                }
                int bits = 1;
                while ((1 << bits) < cardinality) bits++;
                out.writeByte(y);
                out.writeByte(0);
                out.writeShort(section.blockRefCount);
                out.writeShort(cardinality);
                for (int i = 0; i < cardinality; i++) out.writeShort(palette[i]);
                out.writeByte(bits);
                int words = (4096 * bits + 63) / 64;
                out.writeShort(words);
                long[] packed = new long[words];
                for (int cell = 0; cell < 4096; cell++) {
                    long position = (long) cell * bits;
                    int word = (int) (position / 64), shift = (int) (position % 64);
                    packed[word] |= ((long) index[cell]) << shift;
                    if (shift + bits > 64)
                        packed[word + 1] |= ((long) index[cell]) >>> (64 - shift);
                }
                for (long word : packed) out.writeLong(word);
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
