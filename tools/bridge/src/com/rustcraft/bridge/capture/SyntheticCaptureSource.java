package com.rustcraft.bridge.capture;

import java.util.Arrays;
import java.util.EnumMap;
import com.rustcraft.bridge.capture.CaptureContract.*;

/** Deliberately mutable, closed-world SYNTHETIC test adapter. Not a live mod adapter. */
public final class SyntheticCaptureSource implements CaptureSource {
    public Object chunkIdentity = new Object(), storageIdentity = new Object();
    public int dimension, chunkX, chunkZ, requestedFilter = 0xFFFF, globalPaletteBits = 16;
    public long incarnation = 1, generation = 1, mutationEpoch;
    public boolean fullChunk = true, skylight = true;
    public StorageModel storageModel = StorageModel.VANILLA_U16;
    public String provenance = "SYNTHETIC:owned-capture-v1";
    public byte[] biomes = new byte[256];
    public final MutableSection[] sections = new MutableSection[16];
    private final EnumMap<Phase, Runnable> hooks = new EnumMap<Phase, Runnable>(Phase.class);

    public static final class MutableSection {
        public Object identity = new Object();
        public long[] states = new long[4096];
        public byte[] blockLight = new byte[2048], skyLight = new byte[2048], extendedHigh;
        public boolean empty = true;
        public int blockRefCount;

        public MutableSection fill(long state) {
            Arrays.fill(states, state);
            blockRefCount = state == 0 ? 0 : 4096;
            empty = state == 0;
            return this;
        }

        Section view() {
            return new Section(identity, states, blockLight, skyLight, extendedHigh, empty, blockRefCount);
        }
    }

    public void on(Phase phase, Runnable mutation) { hooks.put(phase, mutation); }
    public void atPhase(Phase phase) {
        Runnable hook = hooks.get(phase);
        if (hook != null) hook.run();
    }
    public boolean syntheticOfflineScope() { return true; }
    public View readView() {
        Section[] view = new Section[16];
        for (int y = 0; y < 16; y++) if (sections[y] != null) view[y] = sections[y].view();
        return new View(chunkIdentity, storageIdentity, dimension, chunkX, chunkZ,
                incarnation, generation, mutationEpoch, requestedFilter, fullChunk,
                skylight, storageModel, globalPaletteBits, provenance, view, biomes);
    }

    public Context ownerContext() {
        EnumMap<Domain, WriterClass> writers = new EnumMap<Domain, WriterClass>(Domain.class);
        for (Domain domain : Domain.values()) writers.put(domain, WriterClass.OWNER_THREAD_ONLY);
        return new Context(Thread.currentThread(), incarnation, generation,
                "synthetic-complete-inventory-v1", "standalone-test", true, writers, null);
    }
}
