package com.rustcraft.bridge.capture;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/** Offline capability vocabulary. ELIGIBLE never enables production authority. */
public final class CaptureContract {
    private CaptureContract() { }

    public enum Domain {
        SECTION_REFERENCES, BLOCK_STATES, EMPTY_REFCOUNTS, BLOCK_LIGHT,
        SKY_LIGHT, BIOMES, EXTENDED_IDS, LIFECYCLE, TILE_ENTITY_EFFECTS
    }

    public enum WriterClass {
        OWNER_THREAD_ONLY, WRITER_PARTICIPATING, DIRECT_BUT_OBSERVABLE,
        ASYNC_UNCOORDINATED, UNKNOWN
    }

    public enum Reason {
        ELIGIBLE, FALLBACK_OFF_THREAD, FALLBACK_UNKNOWN_WRITER,
        FALLBACK_ASYNC_WRITER, FALLBACK_OBSERVATION_ONLY,
        FALLBACK_MISSING_PARTICIPATION, FALLBACK_UNSUPPORTED_SCOPE,
        FALLBACK_UNSUPPORTED_STORAGE, FALLBACK_EXTENDED_ID,
        FALLBACK_CHUNK_REPLACED, FALLBACK_CAPTURE_CHANGED,
        FALLBACK_MISSING_SECTION, FALLBACK_INVALID_INPUT,
        FALLBACK_TE_MUTATION, FALLBACK_SOURCE_EXCEPTION
    }

    public enum Phase {
        CAPTURE_BEGIN, IDENTITY, SECTION_STRUCTURE, SELECTION, LOGICAL_STATES,
        BLOCK_LIGHT, SKY_LIGHT, BIOMES, EXTENDED_IDS, CAPTURE_END,
        POST_TILE_ENTITY_VALIDATION
    }

    public enum StorageModel { VANILLA_U16, NEID_HIGH_BYTES, JEID_INT, UNKNOWN }
    /** Offline provenance scopes, never production publication permits. */
    public enum Scope { UNSUPPORTED, SYNTHETIC_OFFLINE, REAL_CLEAN_FORGE_ORACLE }
    public enum TileEntityPolicy { UNQUALIFIED, QUALIFIED_READ_ONLY, PARTICIPATING_MUTATION_EPOCH }

    /**
     * Every writer declared WRITER_PARTICIPATING must use this exact lease.
     * A lock does not coordinate unknown writers. The capture owns acquisition
     * and release; readers cannot claim participation from an epoch alone.
     */
    public static final class ParticipationLease {
        private final ReentrantLock lock = new ReentrantLock();
        private final EnumSet<Domain> domains;

        public ParticipationLease(EnumSet<Domain> domains) {
            this.domains = domains.clone();
        }

        public void withWriter(Runnable writer) {
            lock.lock();
            try { writer.run(); } finally { lock.unlock(); }
        }

        boolean covers(Domain domain) { return domains.contains(domain); }
        // Capture never waits indefinitely behind a participating writer.
        boolean tryAcquire() { return lock.tryLock(); }
        void release() { lock.unlock(); }
        boolean held() { return lock.isHeldByCurrentThread(); }
    }

    public static final class Context {
        public final Thread canonicalServerThread;
        public final long expectedIncarnation;
        public final long expectedGeneration;
        public final String inventoryId;
        public final String captureContext;
        public final boolean completeKnownWriterInventory;
        public final TileEntityPolicy tileEntityPolicy;
        private final Map<Domain, WriterClass> writers;
        final ParticipationLease lease;

        public Context(Thread canonicalServerThread, long expectedIncarnation,
                       long expectedGeneration, String inventoryId, String captureContext,
                       boolean completeKnownWriterInventory,
                       Map<Domain, WriterClass> writers, ParticipationLease lease) {
            this(canonicalServerThread, expectedIncarnation, expectedGeneration, inventoryId,
                    captureContext, completeKnownWriterInventory, writers, lease, TileEntityPolicy.UNQUALIFIED);
        }

        public Context(Thread canonicalServerThread, long expectedIncarnation,
                       long expectedGeneration, String inventoryId, String captureContext,
                       boolean completeKnownWriterInventory,
                       Map<Domain, WriterClass> writers, ParticipationLease lease,
                       TileEntityPolicy tileEntityPolicy) {
            if (canonicalServerThread == null || writers == null || inventoryId == null
                    || captureContext == null) throw new IllegalArgumentException("Missing context");
            this.canonicalServerThread = canonicalServerThread;
            this.expectedIncarnation = expectedIncarnation;
            this.expectedGeneration = expectedGeneration;
            this.inventoryId = inventoryId;
            this.captureContext = captureContext;
            this.completeKnownWriterInventory = completeKnownWriterInventory;
            this.tileEntityPolicy = tileEntityPolicy == null ? TileEntityPolicy.UNQUALIFIED : tileEntityPolicy;
            EnumMap<Domain, WriterClass> copy = new EnumMap<Domain, WriterClass>(Domain.class);
            copy.putAll(writers);
            this.writers = Collections.unmodifiableMap(copy);
            this.lease = lease;
        }

        public WriterClass writerClass(Domain domain) {
            WriterClass value = writers.get(domain);
            return value == null ? WriterClass.UNKNOWN : value;
        }

        public Map<Domain, WriterClass> writerClasses() { return writers; }
    }

    public static final class Result {
        private final Reason reason;
        private final Domain domain;
        private final String detail;
        private final OwnedPacketSnapshot snapshot;

        Result(Reason reason, Domain domain, String detail, OwnedPacketSnapshot snapshot) {
            // Publication is all-or-nothing: a rejected capture has no owned
            // input, and eligibility cannot describe an absent builder result.
            if (reason == null || (reason == Reason.ELIGIBLE) != (snapshot != null))
                throw new IllegalArgumentException("Capture result publication invariant");
            this.reason = reason;
            this.domain = domain;
            this.detail = detail;
            this.snapshot = snapshot;
        }

        public Reason reason() { return reason; }
        public Domain domain() { return domain; }
        public String detail() { return detail; }
        public boolean accepted() { return reason == Reason.ELIGIBLE; }
        /** This offline foundation never grants a production authority permit. */
        public boolean productionAuthorityEligible() { return false; }
        public OwnedPacketSnapshot snapshot() {
            if (!accepted()) throw new IllegalStateException("Capture rejected: " + reason);
            return snapshot;
        }
    }
}
