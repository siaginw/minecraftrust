package com.rustcraft.bridge.capture;

import com.rustcraft.bridge.capture.CaptureContract.Phase;
import com.rustcraft.bridge.capture.CaptureContract.StorageModel;

/**
 * Offline extraction seam, not a Forge adapter. Views expose live arrays only
 * inside capture; accepted snapshots never retain these views or their tokens.
 */
public interface CaptureSource {
    View readView();
    void atPhase(Phase phase);
    boolean syntheticOfflineScope();
    default CaptureContract.Scope captureScope() {
        return syntheticOfflineScope() ? CaptureContract.Scope.SYNTHETIC_OFFLINE : CaptureContract.Scope.UNSUPPORTED;
    }

    final class Section {
        public final Object identity;
        public final long[] logicalStates;
        public final byte[] blockLight;
        public final byte[] skyLight;
        public final byte[] extendedHigh;
        public final boolean empty;
        public final int blockRefCount;

        public Section(Object identity, long[] logicalStates, byte[] blockLight,
                       byte[] skyLight, byte[] extendedHigh, boolean empty, int blockRefCount) {
            this.identity = identity;
            this.logicalStates = logicalStates;
            this.blockLight = blockLight;
            this.skyLight = skyLight;
            this.extendedHigh = extendedHigh;
            this.empty = empty;
            this.blockRefCount = blockRefCount;
        }
    }

    final class View {
        public final Object chunkIdentity;
        public final Object storageIdentity;
        public final int dimension, chunkX, chunkZ, requestedFilter;
        public final long incarnation, generation, mutationEpoch;
        public final boolean fullChunk, skylight;
        public final StorageModel storageModel;
        public final int globalPaletteBits;
        /** Source registry cardinality: TELEMETRY ONLY. The transport's V2
         *  representation depends on the logical state ids, not on this. */
        public final long globalRegistrySize;
        public final String provenance;
        public final byte[] biomes;
        private final Section[] sections;

        public View(Object chunkIdentity, Object storageIdentity, int dimension,
                    int chunkX, int chunkZ, long incarnation, long generation,
                    long mutationEpoch, int requestedFilter, boolean fullChunk,
                    boolean skylight, StorageModel storageModel, int globalPaletteBits,
                    String provenance, Section[] sections, byte[] biomes) {
            this(chunkIdentity, storageIdentity, dimension, chunkX, chunkZ,
                    incarnation, generation, mutationEpoch, requestedFilter,
                    fullChunk, skylight, storageModel, globalPaletteBits, 0L,
                    provenance, sections, biomes);
        }

        public View(Object chunkIdentity, Object storageIdentity, int dimension,
                    int chunkX, int chunkZ, long incarnation, long generation,
                    long mutationEpoch, int requestedFilter, boolean fullChunk,
                    boolean skylight, StorageModel storageModel, int globalPaletteBits,
                    long globalRegistrySize, String provenance, Section[] sections,
                    byte[] biomes) {
            this.chunkIdentity = chunkIdentity;
            this.storageIdentity = storageIdentity;
            this.dimension = dimension;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.incarnation = incarnation;
            this.generation = generation;
            this.mutationEpoch = mutationEpoch;
            this.requestedFilter = requestedFilter;
            this.fullChunk = fullChunk;
            this.skylight = skylight;
            this.storageModel = storageModel;
            this.globalPaletteBits = globalPaletteBits;
            this.globalRegistrySize = globalRegistrySize;
            this.provenance = provenance;
            this.sections = sections == null ? null : sections.clone();
            this.biomes = biomes;
        }

        public Section section(int y) { return sections[y]; }
        public int sectionSlots() { return sections == null ? -1 : sections.length; }
    }
}
