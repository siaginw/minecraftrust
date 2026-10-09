package com.rustcraft.bridge.capture;

import java.util.Arrays;

/**
 * The temporary construction object for one live capture attempt. Exists only
 * between {@link LivePacketCapture#begin} and commit/seal, while the writer
 * protocol gate freezes participants: every array it holds is a private clone
 * taken under the gate, and the end view re-read at commit must equal the begin
 * view exactly (identity + every copied plane). Any divergence, missing field,
 * unsupported condition, or TE emergence rejects the capture — Java continues
 * normally and nothing partially populated can ever be sealed.
 */
final class CaptureDraft {

    private final LivePacketCapture.LiveCaptureSource source;
    private final LiveWriterGate.CaptureAttempt attempt;
    private final LiveChunkBindings.Binding binding;
    private final int filter;
    private final CaptureSource.View begin;
    private final int acceptedMask;

    private final long[][] states;
    private final byte[][] blockLight;
    private final byte[][] skyLight;
    private final byte[] biomes;
    private CaptureSource.View end;

    private CaptureDraft(LivePacketCapture.LiveCaptureSource source,
                         LiveWriterGate.CaptureAttempt attempt,
                         LiveChunkBindings.Binding binding, int filter,
                         CaptureSource.View begin, int acceptedMask,
                         long[][] states, byte[][] blockLight, byte[][] skyLight, byte[] biomes) {
        this.source = source;
        this.attempt = attempt;
        this.binding = binding;
        this.filter = filter;
        this.begin = begin;
        this.acceptedMask = acceptedMask;
        this.states = states;
        this.blockLight = blockLight;
        this.skyLight = skyLight;
        this.biomes = biomes;
    }

    /** Entry extraction under the held gate; selection mirrors the accepted rules. */
    static CaptureDraft extract(LivePacketCapture.LiveCaptureSource source,
                                LiveWriterGate.CaptureAttempt attempt,
                                LiveChunkBindings.Binding binding, int filter) {
        CaptureSource.View begin = source.readView();
        validateBegin(begin, binding, filter);
        int mask = select(begin);
        long[][] states = new long[16][];
        byte[][] block = new byte[16][], sky = new byte[16][];
        for (int y = 0; y < 16; y++) {
            if ((mask & (1 << y)) == 0) continue;
            CaptureSource.Section section = begin.section(y);
            // Fail-closed support boundaries, enforced at capture time: no NEID/JEID
            // representation, no nonzero extended high bits, and exact array shapes.
            if (section.extendedHigh != null) {
                for (byte value : section.extendedHigh) if (value != 0) {
                    throw LivePacketCapture.reject(LivePacketCapture.RejectionReason.EXTENDED_ID,
                            "nonzero extended high bits in section " + y);
                }
            }
            if (section.logicalStates == null || section.logicalStates.length != 4096
                    || section.blockLight == null || section.blockLight.length != 2048) {
                throw LivePacketCapture.reject(LivePacketCapture.RejectionReason.INVALID_INPUT,
                        "malformed section " + y);
            }
            if (begin.skylight && (section.skyLight == null || section.skyLight.length != 2048)) {
                throw LivePacketCapture.reject(LivePacketCapture.RejectionReason.INVALID_INPUT,
                        "malformed sky light in section " + y);
            }
            states[y] = section.logicalStates.clone();
            block[y] = section.blockLight.clone();
            if (begin.skylight) sky[y] = section.skyLight == null ? null : section.skyLight.clone();
        }
        // Phase D: the u16 decision routes through ShadowScopeGate -- the same
        // single place the rule lives everywhere else -- so the exclusion is
        // structured (HIGH_STATE_ID, first offending id) and counted in the
        // gate's telemetry. An excluded chunk falls back to Java-only; the
        // journal records the exclusion with full identity so it never
        // disappears silently.
        ShadowScopeGate.Result scope = ShadowScopeGate.evaluateCounted(project(states));
        if (!scope.eligible) {
            journalExcluded(attempt.eventId(), binding, scope.reason,
                    "firstOffendingStateId=" + scope.firstOffendingStateId
                            + " entriesInspected=" + scope.entriesInspected);
            if ("HIGH_STATE_ID".equals(scope.reason)) {
                throw LivePacketCapture.reject(LivePacketCapture.RejectionReason.EXTENDED_ID,
                        "state outside native u16 width (firstOffending="
                                + scope.firstOffendingStateId + ")");
            }
            throw LivePacketCapture.reject(LivePacketCapture.RejectionReason.INVALID_INPUT,
                    "scope coherence: " + scope.reason);
        }
        // Phase D admission restriction: a section whose DISTINCT state count
        // exceeds 256 serializes on the Java wire through the runtime global
        // palette, whose width the u16 transport cannot carry on wide
        // registries. Such chunks are excluded with a named reason, never
        // compared, never counted as parity. No transport widening occurs.
        for (int y = 0; y < 16; y++) {
            if (states[y] == null) continue;
            if (distinctCount(states[y]) > 256) {
                journalExcluded(attempt.eventId(), binding, "GLOBAL_PALETTE_SECTION",
                        "section y=" + y + " exceeds the 256-entry section-palette range");
                throw LivePacketCapture.reject(LivePacketCapture.RejectionReason.UNSUPPORTED_STORAGE,
                        "section " + y + " requires the runtime global palette (width beyond transport)");
            }
        }
        byte[] biomes = begin.biomes.clone();
        return new CaptureDraft(source, attempt, binding, filter, begin, mask,
                states, block, sky, biomes);
    }

    /** int[] projection of the selected sections for the Phase-B gate. */
    private static int[][] project(long[][] states) {
        int[][] projected = new int[16][];
        for (int y = 0; y < 16; y++) {
            if (states[y] == null) continue;
            int[] column = new int[states[y].length];
            for (int i = 0; i < column.length; i++) column[i] = (int) states[y][i];
            projected[y] = column;
        }
        return projected;
    }

    /** Distinct logical states in one section (sort-based, bounded 4096). */
    private static int distinctCount(long[] sectionStates) {
        long[] sorted = sectionStates.clone();
        java.util.Arrays.sort(sorted);
        int distinct = sorted.length == 0 ? 0 : 1;
        for (int i = 1; i < sorted.length; i++) if (sorted[i] != sorted[i - 1]) distinct++;
        return distinct;
    }

    /** Capture-side exclusion record; journal absent means historical behavior. */
    private static void journalExcluded(long eventId, LiveChunkBindings.Binding binding,
                                        String reason, String detail) {
        ShadowEventJournal journal = ShadowEventJournal.instance();
        if (journal == null) return;
        try {
            journal.recordOutcome(eventId, binding.sessionId(),
                    ShadowEventJournal.Outcome.EXCLUDED, reason, detail);
        } catch (Throwable evidenceFailure) {
            System.err.println("[RustCraft] phase-d journal exclusion record failed: "
                    + evidenceFailure);
        }
    }

    /**
     * Final validation under the still-held gate: end view equals begin view
     * everywhere the capture owns data and the TE map is still empty. The Java
     * packet shell is checked in {@link #seal}. Sealing happens exactly once.
     */
    void validateForSeal() {
        this.end = source.readView();
        validateEnd(this.end);
        if (!source.tileEntitiesEmpty()) {
            throw LivePacketCapture.reject(LivePacketCapture.RejectionReason.TE_PRESENT,
                    "TE map became nonempty during capture");
        }
    }

    /**
     * Creates an immutable OwnedPacketSnapshot for the Rust authority experiment
     * directly under the held gate without requiring a pre-built Java packet.
     */
    OwnedPacketSnapshot createOwnedSnapshot() {
        validateForSeal();
        OwnedPacketSnapshot.Section[] owned = new OwnedPacketSnapshot.Section[16];
        for (int y = 0; y < 16; y++) {
            if ((acceptedMask & (1 << y)) == 0) continue;
            CaptureSource.Section section = begin.section(y);
            owned[y] = new OwnedPacketSnapshot.Section(y, states[y], blockLight[y],
                    skyLight[y], null, section.empty, section.blockRefCount);
        }
        return new OwnedPacketSnapshot(begin, end, context(),
                owned, biomes, acceptedMask, true, CaptureContract.Scope.LIVE_SHADOW_OWNED_V1);
    }

    SealedLiveCapture seal(Object packet, long gateEventId) {
        LivePacketCapture.JavaPacketView java = source.javaPacket(packet);
        if (java == null || java.payload() == null || java.payload().length == 0) {
            throw LivePacketCapture.reject(LivePacketCapture.RejectionReason.PACKET_MISMATCH,
                    "Java packet payload is missing");
        }
        if (java.mask() != acceptedMask) {
            throw LivePacketCapture.reject(LivePacketCapture.RejectionReason.MASK_MISMATCH,
                    "java mask " + java.mask() + " != draft mask " + acceptedMask);
        }
        if (java.fullChunk() != begin.fullChunk || java.packetX() != begin.chunkX
                || java.packetZ() != begin.chunkZ) {
            throw LivePacketCapture.reject(LivePacketCapture.RejectionReason.PACKET_MISMATCH,
                    "packet shell does not match the captured chunk identity");
        }
        // Owned snapshot: every Section constructor clones; biomes cloned here.
        OwnedPacketSnapshot.Section[] owned = new OwnedPacketSnapshot.Section[16];
        for (int y = 0; y < 16; y++) {
            if ((acceptedMask & (1 << y)) == 0) continue;
            CaptureSource.Section section = begin.section(y);
            owned[y] = new OwnedPacketSnapshot.Section(y, states[y], blockLight[y],
                    skyLight[y], null, section.empty, section.blockRefCount);
        }
        // One coherent event: the sealed capture binds the owned snapshot, the Java
        // payload clone, and the exact identity; nothing else escapes the draft.
        OwnedPacketSnapshot snapshot = new OwnedPacketSnapshot(begin, end, context(),
                owned, biomes, acceptedMask, true, CaptureContract.Scope.LIVE_SHADOW_OWNED_V1);
        return new SealedLiveCapture(gateEventId, binding.identityRecord(), snapshot,
                java.payload().clone(), java.mask(), java.fullChunk(), java.packetX(), java.packetZ());
    }

    private CaptureContract.Context context() {
        java.util.EnumMap<CaptureContract.Domain, CaptureContract.WriterClass> writers =
                new java.util.EnumMap<CaptureContract.Domain, CaptureContract.WriterClass>(CaptureContract.Domain.class);
        // Every admitted domain participates through the gate in this profile.
        for (CaptureContract.Domain domain : CaptureContract.Domain.values()) {
            writers.put(domain, CaptureContract.WriterClass.WRITER_PARTICIPATING);
        }
        return new CaptureContract.Context(Thread.currentThread(), begin.incarnation,
                begin.generation, "FORGE_2860_SERVER_TRANSFORMED_FML_INITIALIZED_OFFLINE_V1",
                "LIVE_SHADOW_OWNED_V1", true, writers, null);
    }

    /** Package-private for the Phase-D offline identity controls. */
    static void validateBegin(CaptureSource.View begin, LiveChunkBindings.Binding binding, int filter) {
        if (begin == null || begin.sectionSlots() != 16 || (filter & ~0xFFFF) != 0
                || begin.biomes == null || begin.biomes.length != 256
                // The source width is the runtime registry's width (18 bits
                // measured on Revelation) -- a consistency fact, not the
                // eligibility rule: eligibility is per actual state id.
                || begin.globalPaletteBits < 9 || begin.globalPaletteBits > 20
                || begin.storageModel != CaptureContract.StorageModel.VANILLA_U16
                || begin.provenance == null || begin.provenance.isEmpty()) {
            throw LivePacketCapture.reject(LivePacketCapture.RejectionReason.INVALID_INPUT,
                    "malformed live capture metadata");
        }
        if (begin.incarnation != binding.incarnation()
                || begin.generation != binding.ownedEncodeGeneration()) {
            throw LivePacketCapture.reject(LivePacketCapture.RejectionReason.PROVIDER_IDENTITY_CHANGED,
                    "binding identity does not match the extracted view");
        }
    }

    private void validateEnd(CaptureSource.View end) {
        validateViews(this.begin, end);
    }

    /** Package-private for the Phase-D offline epoch/incarnation controls. */
    static void validateViews(CaptureSource.View begin, CaptureSource.View end) {
        if (end == null || end.sectionSlots() != 16) {
            throw LivePacketCapture.reject(LivePacketCapture.RejectionReason.INVALID_INPUT,
                    "malformed end view");
        }
        // Identity planes: any change under the held gate means the writer protocol
        // was bypassed (unknown writer) and the capture is invalid.
        if (end.incarnation != begin.incarnation || end.generation != begin.generation
                || end.mutationEpoch != begin.mutationEpoch || end.fullChunk != begin.fullChunk
                || end.skylight != begin.skylight || end.storageModel != begin.storageModel
                || end.globalPaletteBits != begin.globalPaletteBits
                || end.requestedFilter != begin.requestedFilter) {
            throw LivePacketCapture.reject(LivePacketCapture.RejectionReason.EPOCH_CHANGED,
                    "identity/epoch planes changed between begin and end");
        }
        if (!Arrays.equals(end.biomes, begin.biomes)) {
            throw LivePacketCapture.reject(LivePacketCapture.RejectionReason.EPOCH_CHANGED,
                    "biomes changed between begin and end");
        }
        for (int y = 0; y < 16; y++) {
            CaptureSource.Section before = begin.section(y);
            CaptureSource.Section after = end.section(y);
            if ((before == null) != (after == null)) {
                throw LivePacketCapture.reject(LivePacketCapture.RejectionReason.EPOCH_CHANGED,
                        "section presence changed at " + y);
            }
            if (before == null) continue;
            if (!Arrays.equals(before.logicalStates, after.logicalStates)
                    || !Arrays.equals(before.blockLight, after.blockLight)
                    || !Arrays.equals(before.skyLight, after.skyLight)
                    || before.empty != after.empty || before.blockRefCount != after.blockRefCount) {
                throw LivePacketCapture.reject(LivePacketCapture.RejectionReason.EPOCH_CHANGED,
                        "section " + y + " changed between begin and end");
            }
        }
    }

    /** Selection mirrors the accepted offline rule: filter bit AND present AND (!full | nonempty). */
    private static int select(CaptureSource.View view) {
        int mask = 0;
        for (int y = 0; y < 16; y++) {
            CaptureSource.Section section = view.section(y);
            if ((view.requestedFilter & (1 << y)) != 0 && section != null
                    && (!view.fullChunk || !section.empty)) {
                mask |= 1 << y;
            }
        }
        return mask;
    }
}
