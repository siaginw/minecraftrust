package com.rustcraft.bridge.capture;

import java.util.Arrays;
import com.rustcraft.bridge.capture.CaptureContract.*;

/** Bounded offline capture under an explicit, closed writer capability model. */
public final class SnapshotCapture {
    private SnapshotCapture() { }

    public static Result capture(CaptureSource source, Context context) {
        return capture(source, context, null);
    }

    /** The optional callback is a synthetic TE-effects seam, never a live TE adapter. */
    public static Result capture(CaptureSource source, Context context, Runnable syntheticTeEffects) {
        if (source == null || context == null) return reject(Reason.FALLBACK_INVALID_INPUT, null, "null input");
        if (Thread.currentThread() != context.canonicalServerThread)
            return reject(Reason.FALLBACK_OFF_THREAD, null, "canonical Thread identity differs");
        final Scope scope;
        try {
            scope = source.captureScope();
            if (scope != Scope.SYNTHETIC_OFFLINE && scope != Scope.REAL_CLEAN_FORGE_ORACLE)
                return reject(Reason.FALLBACK_UNSUPPORTED_SCOPE, null, "no qualified live adapter exists");
        } catch (RuntimeException failure) {
            return reject(Reason.FALLBACK_SOURCE_EXCEPTION, null, failure.getClass().getName());
        }
        if (!context.completeKnownWriterInventory)
            return reject(Reason.FALLBACK_UNKNOWN_WRITER, null, "writer inventory is incomplete");
        if (syntheticTeEffects != null && context.tileEntityPolicy == TileEntityPolicy.UNQUALIFIED)
            return reject(Reason.FALLBACK_UNKNOWN_WRITER, Domain.TILE_ENTITY_EFFECTS,
                    "callback needs qualified read-only behavior or complete participating mutation epochs");
        boolean participation = false;
        for (Domain domain : Domain.values()) {
            WriterClass writer = context.writerClass(domain);
            if (writer == WriterClass.UNKNOWN)
                return reject(Reason.FALLBACK_UNKNOWN_WRITER, domain, "unknown writer");
            if (writer == WriterClass.ASYNC_UNCOORDINATED)
                return reject(Reason.FALLBACK_ASYNC_WRITER, domain, "uncoordinated writer");
            if (writer == WriterClass.DIRECT_BUT_OBSERVABLE)
                return reject(Reason.FALLBACK_OBSERVATION_ONLY, domain, "observation is not exclusion");
            if (writer == WriterClass.WRITER_PARTICIPATING) {
                if (context.lease == null || !context.lease.covers(domain))
                    return reject(Reason.FALLBACK_MISSING_PARTICIPATION, domain, "domain has no shared lease");
                participation = true;
            }
        }
        if (participation && !context.lease.tryAcquire())
            return reject(Reason.FALLBACK_MISSING_PARTICIPATION, null, "shared lease is busy; capture did not wait");
        try {
            return captureHeld(source, context, syntheticTeEffects, scope);
        } catch (Rejected failure) {
            return reject(failure.reason, null, failure.getMessage());
        } catch (RuntimeException failure) {
            return reject(Reason.FALLBACK_SOURCE_EXCEPTION, null, failure.getClass().getName());
        } finally {
            if (participation) context.lease.release();
        }
    }

    private static Result captureHeld(CaptureSource source, Context context, Runnable te, Scope scope) {
        CaptureSource.View begin = source.readView();
        validateView(begin);
        if (begin.incarnation != context.expectedIncarnation || begin.generation != context.expectedGeneration)
            fail(Reason.FALLBACK_CHUNK_REPLACED, "initial incarnation/generation differs from request");
        // A complete initial copy is deliberate: equality must compare against
        // the beginning, not merely data first read after an intervening callback.
        AuditCopy baseline = new AuditCopy(begin);
        source.atPhase(Phase.CAPTURE_BEGIN);
        source.atPhase(Phase.IDENTITY);
        source.atPhase(Phase.SECTION_STRUCTURE);
        int mask = select(begin);
        source.atPhase(Phase.SELECTION);
        long[][] states = new long[16][];
        byte[][] block = new byte[16][], sky = new byte[16][], high = new byte[16][];
        for (int y = 0; y < 16; y++) if ((mask & (1 << y)) != 0) states[y] = begin.section(y).logicalStates.clone();
        source.atPhase(Phase.LOGICAL_STATES);
        for (int y = 0; y < 16; y++) if ((mask & (1 << y)) != 0) block[y] = begin.section(y).blockLight.clone();
        source.atPhase(Phase.BLOCK_LIGHT);
        for (int y = 0; y < 16; y++) if ((mask & (1 << y)) != 0 && begin.skylight) sky[y] = begin.section(y).skyLight.clone();
        source.atPhase(Phase.SKY_LIGHT);
        byte[] biomes = begin.biomes.clone();
        source.atPhase(Phase.BIOMES);
        for (int y = 0; y < 16; y++) if ((mask & (1 << y)) != 0 && begin.section(y).extendedHigh != null)
            high[y] = begin.section(y).extendedHigh.clone();
        source.atPhase(Phase.EXTENDED_IDS);
        OwnedPacketSnapshot.Section[] owned = new OwnedPacketSnapshot.Section[16];
        for (int y = 0; y < 16; y++) if ((mask & (1 << y)) != 0) {
            CaptureSource.Section section = begin.section(y);
            checkStates(states[y], section.blockRefCount, section.empty, high[y], begin.storageModel);
            if (!Arrays.equals(states[y], baseline.states[y]) || !Arrays.equals(block[y], baseline.block[y])
                    || (begin.skylight && !Arrays.equals(sky[y], baseline.sky[y]))
                    || !Arrays.equals(high[y], baseline.high[y]))
                fail(Reason.FALLBACK_CAPTURE_CHANGED, "copied section changed from begin: " + y);
            owned[y] = new OwnedPacketSnapshot.Section(y, states[y], block[y], sky[y], high[y],
                    section.empty, section.blockRefCount);
        }
        if (!Arrays.equals(biomes, baseline.biomes)) fail(Reason.FALLBACK_CAPTURE_CHANGED, "copied biomes changed");
        source.atPhase(Phase.CAPTURE_END);
        CaptureSource.View end = source.readView();
        baseline.validate(end);
        if (te != null) {
            te.run();
            source.atPhase(Phase.POST_TILE_ENTITY_VALIDATION);
            try {
                end = source.readView();
                baseline.validate(end);
            } catch (Rejected changed) {
                fail(Reason.FALLBACK_TE_MUTATION, changed.getMessage());
            }
        }
        return new Result(Reason.ELIGIBLE, null, "qualified offline owned snapshot: " + scope,
                new OwnedPacketSnapshot(begin, end, context, owned, biomes, mask, te != null, scope));
    }

    private static int select(CaptureSource.View view) {
        int mask = 0;
        for (int y = 0; y < 16; y++) {
            CaptureSource.Section section = view.section(y);
            if ((view.requestedFilter & (1 << y)) != 0 && section != null && (!view.fullChunk || !section.empty)) mask |= 1 << y;
        }
        return mask;
    }

    private static void validateView(CaptureSource.View view) {
        if (view == null || view.chunkIdentity == null || view.storageIdentity == null
                || view.sectionSlots() != 16 || (view.requestedFilter & ~0xFFFF) != 0
                || view.incarnation <= 0 || view.generation <= 0 || view.mutationEpoch < 0
                || view.provenance == null || view.provenance.length() == 0
                || view.biomes == null || view.biomes.length != 256
                || view.globalPaletteBits < 9 || view.globalPaletteBits > 16)
            fail(Reason.FALLBACK_INVALID_INPUT, "malformed capture metadata");
        if (view.storageModel == null || view.storageModel == StorageModel.UNKNOWN || view.storageModel == StorageModel.JEID_INT)
            fail(Reason.FALLBACK_UNSUPPORTED_STORAGE, "storage model has not been qualified");
        for (int y = 0; y < 16; y++) {
            CaptureSource.Section section = view.section(y);
            if (section == null) continue;
            if (section.identity == null || section.logicalStates == null || section.logicalStates.length != 4096
                    || section.blockLight == null || section.blockLight.length != 2048
                    || (view.skylight && (section.skyLight == null || section.skyLight.length != 2048))
                    || (section.skyLight != null && section.skyLight.length != 2048))
                fail(Reason.FALLBACK_INVALID_INPUT, "malformed section " + y);
            checkStates(section.logicalStates, section.blockRefCount, section.empty, section.extendedHigh, view.storageModel);
        }
    }

    private static void checkStates(long[] states, int refCount, boolean empty, byte[] high, StorageModel model) {
        if (model == StorageModel.NEID_HIGH_BYTES && (high == null || high.length != 4096))
            fail(Reason.FALLBACK_UNSUPPORTED_STORAGE, "NEID high-byte plane missing");
        if (high != null && high.length != 4096) fail(Reason.FALLBACK_INVALID_INPUT, "invalid extended plane");
        if (high != null) for (byte value : high) if (value != 0)
            fail(Reason.FALLBACK_EXTENDED_ID, "nonzero extended high bits");
        int actual = 0;
        for (long state : states) {
            if (state < 0 || state > 65535) fail(Reason.FALLBACK_EXTENDED_ID, "state outside native u16 width");
            if (state != 0) actual++;
        }
        if (refCount != actual || empty != (actual == 0))
            fail(Reason.FALLBACK_CAPTURE_CHANGED, "refcount/empty metadata disagrees with logical states");
    }

    private static final class AuditCopy {
        final CaptureSource.View begin;
        final long[][] states = new long[16][];
        final byte[][] block = new byte[16][], sky = new byte[16][], high = new byte[16][];
        final byte[] biomes;

        AuditCopy(CaptureSource.View begin) {
            this.begin = begin;
            this.biomes = begin.biomes.clone();
            for (int y = 0; y < 16; y++) if (begin.section(y) != null) {
                CaptureSource.Section section = begin.section(y);
                states[y] = section.logicalStates.clone();
                block[y] = section.blockLight.clone();
                sky[y] = section.skyLight == null ? null : section.skyLight.clone();
                high[y] = section.extendedHigh == null ? null : section.extendedHigh.clone();
            }
        }

        void validate(CaptureSource.View end) {
            if (end == null || end.chunkIdentity != begin.chunkIdentity || end.incarnation != begin.incarnation
                    || end.generation != begin.generation)
                fail(Reason.FALLBACK_CHUNK_REPLACED, "chunk identity/incarnation/generation changed");
            validateView(end);
            if (end.storageIdentity != begin.storageIdentity || end.storageModel != begin.storageModel
                    || end.globalPaletteBits != begin.globalPaletteBits || end.mutationEpoch != begin.mutationEpoch
                    || end.dimension != begin.dimension || end.chunkX != begin.chunkX || end.chunkZ != begin.chunkZ
                    || end.requestedFilter != begin.requestedFilter || end.fullChunk != begin.fullChunk
                    || end.skylight != begin.skylight || !end.provenance.equals(begin.provenance)
                    || end.biomes != begin.biomes || !Arrays.equals(biomes, end.biomes))
                fail(Reason.FALLBACK_CAPTURE_CHANGED, "capture metadata or biome data changed");
            for (int y = 0; y < 16; y++) {
                CaptureSource.Section a = begin.section(y), b = end.section(y);
                if (a == null && b == null) continue;
                if (a == null || b == null) fail(Reason.FALLBACK_MISSING_SECTION, "section presence changed: " + y);
                if (a.identity != b.identity || a.logicalStates != b.logicalStates || a.blockLight != b.blockLight
                        || a.skyLight != b.skyLight || a.extendedHigh != b.extendedHigh
                        || a.empty != b.empty || a.blockRefCount != b.blockRefCount
                        || !Arrays.equals(states[y], b.logicalStates) || !Arrays.equals(block[y], b.blockLight)
                        || !Arrays.equals(sky[y], b.skyLight) || !Arrays.equals(high[y], b.extendedHigh))
                    fail(Reason.FALLBACK_CAPTURE_CHANGED, "section references or exact contents changed: " + y);
            }
        }
    }

    private static Result reject(Reason reason, Domain domain, String detail) { return new Result(reason, domain, detail, null); }
    private static void fail(Reason reason, String detail) { throw new Rejected(reason, detail); }
    private static final class Rejected extends RuntimeException {
        final Reason reason;
        Rejected(Reason reason, String detail) { super(detail); this.reason = reason; }
    }
}
