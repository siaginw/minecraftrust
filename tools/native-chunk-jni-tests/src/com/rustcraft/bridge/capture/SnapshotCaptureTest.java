package com.rustcraft.bridge.capture;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import com.rustcraft.bridge.capture.CaptureContract.*;

/** Deterministic Java 8 synthetic capture contract tests; no Minecraft jars. */
public final class SnapshotCaptureTest {
    private static int checks, groups;

    public static void main(String[] args) throws Exception {
        maskAndOwnership();
        mutationAtEveryPhase();
        everyDomainAndRepresentation();
        capabilityMatrix();
        threadAndLease();
        sourceFailuresAndBusyLease();
        tileEntityBoundary();
        transport();
        System.out.println("PASS SnapshotCaptureTest groups=" + groups + " checks=" + checks);
    }

    private static SyntheticCaptureSource source() {
        SyntheticCaptureSource source = new SyntheticCaptureSource();
        source.sections[0] = new SyntheticCaptureSource.MutableSection().fill(1);
        return source;
    }

    private static void maskAndOwnership() {
        for (int mask : new int[] {0, 1, 0x1F, 0x8000, 0x8421, 0xFFFF}) {
            SyntheticCaptureSource source = source();
            for (int y = 0; y < 16; y++) source.sections[y] = (mask & (1 << y)) == 0 ? null
                    : new SyntheticCaptureSource.MutableSection().fill(y + 1);
            Result result = SnapshotCapture.capture(source, source.ownerContext());
            check(result.accepted(), "mask accepted");
            check(!result.productionAuthorityEligible(), "offline result cannot enable production");
            check(result.snapshot().acceptedMask == mask, "single-event mask");
            for (int y = 0; y < 16; y++) check((result.snapshot().section(y) != null) == ((mask & (1 << y)) != 0), "owned mask correspondence");
        }
        SyntheticCaptureSource source = source();
        OwnedPacketSnapshot snapshot = SnapshotCapture.capture(source, source.ownerContext()).snapshot();
        source.sections[0].states[0] = 2;
        source.sections[0].blockLight[0] = 3;
        source.sections[0].skyLight[0] = 4;
        source.biomes[0] = 5;
        check(snapshot.section(0).logicalStates()[0] == 1, "input state ownership");
        check(snapshot.section(0).blockLight()[0] == 0, "input block light ownership");
        check(snapshot.section(0).skyLight()[0] == 0, "input sky light ownership");
        check(snapshot.biomes()[0] == 0, "input biome ownership");
        snapshot.section(0).logicalStates()[0] = 8;
        snapshot.section(0).blockLight()[0] = 8;
        snapshot.section(0).skyLight()[0] = 8;
        snapshot.biomes()[0] = 8;
        check(snapshot.section(0).logicalStates()[0] == 1 && snapshot.biomes()[0] == 0
                && snapshot.section(0).blockLight()[0] == 0 && snapshot.section(0).skyLight()[0] == 0, "output getters copy");
        source.sections[0].fill(0);
        check(SnapshotCapture.capture(source, source.ownerContext()).snapshot().acceptedMask == 0, "nonempty to empty full");
        source.fullChunk = false;
        check(SnapshotCapture.capture(source, source.ownerContext()).snapshot().acceptedMask == 1, "partial present-empty selected");
        source.sections[0].fill(2);
        check(SnapshotCapture.capture(source, source.ownerContext()).snapshot().acceptedMask == 1, "empty to nonempty");
        source.requestedFilter = 0;
        check(SnapshotCapture.capture(source, source.ownerContext()).snapshot().acceptedMask == 0, "filter excludes resident section");
        groups++;
    }

    private static void mutationAtEveryPhase() {
        for (final Phase phase : Phase.values()) {
            if (phase == Phase.POST_TILE_ENTITY_VALIDATION) continue;
            final SyntheticCaptureSource source = source();
            source.on(phase, new Runnable() { public void run() { source.sections[0].states[0] = 2; } });
            reason(SnapshotCapture.capture(source, source.ownerContext()), Reason.FALLBACK_CAPTURE_CHANGED);
        }
        groups++;
    }

    private static void everyDomainAndRepresentation() {
        for (int kind = 0; kind < 17; kind++) {
            final int mutation = kind;
            final SyntheticCaptureSource source = source();
            source.on(Phase.CAPTURE_END, new Runnable() {
                public void run() {
                    switch (mutation) {
                        case 0: source.sections[0] = null; break;
                        case 1: source.sections[0].identity = new Object(); break;
                        case 2: source.sections[0].states = source.sections[0].states.clone(); break;
                        case 3: source.sections[0].blockLight[0]++; break;
                        case 4: source.sections[0].skyLight[0]++; break;
                        case 5: source.biomes[0]++; break;
                        case 6: source.sections[0].blockRefCount--; break;
                        case 7: source.sections[0].empty = true; break;
                        case 8: source.generation++; break;
                        case 9: source.incarnation++; break;
                        case 10: source.chunkIdentity = new Object(); break;
                        case 11: source.requestedFilter = 0; break;
                        case 12: source.storageIdentity = new Object(); break;
                        case 13: source.mutationEpoch++; break;
                        case 14: source.fullChunk = false; break;
                        case 15: source.skylight = false; break;
                        case 16: source.biomes = source.biomes.clone(); break;
                        default: throw new AssertionError();
                    }
                }
            });
            Result result = SnapshotCapture.capture(source, source.ownerContext());
            check(!result.accepted(), "domain mutation rejects " + kind);
        }
        for (long state : new long[] {-1, 65536, 76916, 0xFFFFFFFFL, 0x100000000L}) {
            SyntheticCaptureSource source = source();
            source.sections[0].states[3] = state;
            reason(SnapshotCapture.capture(source, source.ownerContext()), Reason.FALLBACK_EXTENDED_ID);
        }
        SyntheticCaptureSource neid = source();
        neid.storageModel = StorageModel.NEID_HIGH_BYTES;
        neid.sections[0].extendedHigh = new byte[4096];
        OwnedPacketSnapshot neidSnapshot = SnapshotCapture.capture(neid, neid.ownerContext()).snapshot();
        neid.sections[0].extendedHigh[0] = 1;
        check(neidSnapshot.section(0).extendedHigh()[0] == 0, "extended plane owned");
        reason(SnapshotCapture.capture(neid, neid.ownerContext()), Reason.FALLBACK_EXTENDED_ID);
        neid.storageModel = StorageModel.JEID_INT;
        reason(SnapshotCapture.capture(neid, neid.ownerContext()), Reason.FALLBACK_UNSUPPORTED_STORAGE);
        neid.storageModel = StorageModel.UNKNOWN;
        reason(SnapshotCapture.capture(neid, neid.ownerContext()), Reason.FALLBACK_UNSUPPORTED_STORAGE);
        SyntheticCaptureSource stale = source();
        Context old = stale.ownerContext();
        stale.incarnation++;
        reason(SnapshotCapture.capture(stale, old), Reason.FALLBACK_CHUNK_REPLACED);
        SyntheticCaptureSource malformed = source();
        malformed.sections[0].blockLight = new byte[1];
        reason(SnapshotCapture.capture(malformed, malformed.ownerContext()), Reason.FALLBACK_INVALID_INPUT);
        groups++;
    }

    private static Context context(SyntheticCaptureSource source, MapBuilder map, ParticipationLease lease, boolean complete) {
        return new Context(Thread.currentThread(), source.incarnation, source.generation,
                "synthetic-inventory", "test", complete, map.writers, lease);
    }

    private static final class MapBuilder {
        final EnumMap<Domain, WriterClass> writers = new EnumMap<Domain, WriterClass>(Domain.class);
        MapBuilder() { for (Domain domain : Domain.values()) writers.put(domain, WriterClass.OWNER_THREAD_ONLY); }
    }

    private static void capabilityMatrix() {
        for (Domain domain : Domain.values()) {
            for (WriterClass writer : new WriterClass[] {WriterClass.UNKNOWN, WriterClass.ASYNC_UNCOORDINATED,
                    WriterClass.DIRECT_BUT_OBSERVABLE, WriterClass.WRITER_PARTICIPATING}) {
                SyntheticCaptureSource source = source();
                MapBuilder map = new MapBuilder();
                map.writers.put(domain, writer);
                Reason expected = writer == WriterClass.UNKNOWN ? Reason.FALLBACK_UNKNOWN_WRITER
                        : writer == WriterClass.ASYNC_UNCOORDINATED ? Reason.FALLBACK_ASYNC_WRITER
                        : writer == WriterClass.DIRECT_BUT_OBSERVABLE ? Reason.FALLBACK_OBSERVATION_ONLY
                        : Reason.FALLBACK_MISSING_PARTICIPATION;
                Result result = SnapshotCapture.capture(source, context(source, map, null, true));
                reason(result, expected);
                check(result.domain() == domain, "explicit rejected domain");
            }
        }
        SyntheticCaptureSource source = source();
        reason(SnapshotCapture.capture(source, context(source, new MapBuilder(), null, false)), Reason.FALLBACK_UNKNOWN_WRITER);
        final AtomicBoolean reached = new AtomicBoolean();
        source.on(Phase.LOGICAL_STATES, new Runnable() { public void run() { reached.set(true); } });
        MapBuilder unknown = new MapBuilder();
        unknown.writers.put(Domain.BLOCK_STATES, WriterClass.UNKNOWN);
        reason(SnapshotCapture.capture(source, context(source, unknown, null, true)), Reason.FALLBACK_UNKNOWN_WRITER);
        check(!reached.get(), "unknown ABA-capable writer rejected before observation");
        groups++;
    }

    private static void threadAndLease() throws Exception {
        final SyntheticCaptureSource source = source();
        final Context owner = source.ownerContext();
        final Result[] offThread = new Result[1];
        Thread impostor = new Thread(new Runnable() { public void run() { offThread[0] = SnapshotCapture.capture(source, owner); } }, Thread.currentThread().getName());
        impostor.start(); impostor.join(5000);
        check(!impostor.isAlive(), "offthread completes");
        reason(offThread[0], Reason.FALLBACK_OFF_THREAD);
        final ParticipationLease lease = new ParticipationLease(EnumSet.allOf(Domain.class));
        final CountDownLatch attempted = new CountDownLatch(1);
        final AtomicBoolean wrote = new AtomicBoolean();
        final Thread[] writer = new Thread[1];
        source.on(Phase.LOGICAL_STATES, new Runnable() {
            public void run() {
                writer[0] = new Thread(new Runnable() {
                    public void run() {
                        attempted.countDown();
                        lease.withWriter(new Runnable() { public void run() { source.sections[0].states[0] = 2; wrote.set(true); } });
                    }
                });
                writer[0].start();
                try { check(attempted.await(5, TimeUnit.SECONDS), "writer started"); }
                catch (InterruptedException error) { throw new AssertionError(error); }
                check(!wrote.get(), "participating writer excluded during capture");
            }
        });
        MapBuilder map = new MapBuilder();
        for (Domain domain : Domain.values()) map.writers.put(domain, WriterClass.WRITER_PARTICIPATING);
        Result result = SnapshotCapture.capture(source, context(source, map, lease, true));
        check(result.accepted(), "lease capture accepted");
        writer[0].join(5000);
        check(!writer[0].isAlive() && wrote.get(), "writer proceeds after lease release");
        check(result.snapshot().section(0).logicalStates()[0] == 1, "accepted owned state survives later writer");
        groups++;
    }

    private static void tileEntityBoundary() {
        final SyntheticCaptureSource source = source();
        final AtomicBoolean called = new AtomicBoolean();
        reason(SnapshotCapture.capture(source, source.ownerContext(), new Runnable() { public void run() { called.set(true); } }), Reason.FALLBACK_UNKNOWN_WRITER);
        check(!called.get(), "unqualified callback not invoked");
        Result result = SnapshotCapture.capture(source, teContext(source), new Runnable() { public void run() { } });
        check(result.accepted() && result.snapshot().tileEntityRevalidated, "unchanged TE seam accepted");
        reason(SnapshotCapture.capture(source, teContext(source), new Runnable() {
            public void run() { source.biomes[1] = 4; }
        }), Reason.FALLBACK_TE_MUTATION);
        final SyntheticCaptureSource aba = source();
        reason(SnapshotCapture.capture(aba, teContext(aba), new Runnable() {
            public void run() { aba.sections[0].states[0] = 2; aba.sections[0].states[0] = 1; aba.mutationEpoch += 2; }
        }), Reason.FALLBACK_TE_MUTATION);
        groups++;
    }

    private static void sourceFailuresAndBusyLease() throws Exception {
        final SyntheticCaptureSource source = source();
        final ParticipationLease lease = new ParticipationLease(EnumSet.allOf(Domain.class));
        final CountDownLatch locked = new CountDownLatch(1), release = new CountDownLatch(1);
        Thread writer = new Thread(new Runnable() {
            public void run() {
                lease.withWriter(new Runnable() {
                    public void run() {
                        locked.countDown();
                        try {
                            if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("bounded lease test release");
                        } catch (InterruptedException error) { throw new AssertionError(error); }
                    }
                });
            }
        });
        writer.start();
        try {
            check(locked.await(5, TimeUnit.SECONDS), "writer holds lease");
            MapBuilder map = new MapBuilder();
            map.writers.put(Domain.BLOCK_STATES, WriterClass.WRITER_PARTICIPATING);
            reason(SnapshotCapture.capture(source, context(source, map, lease, true)), Reason.FALLBACK_MISSING_PARTICIPATION);
        } finally {
            release.countDown();
            writer.join(5000);
        }
        check(!writer.isAlive(), "busy lease test releases writer");
        source.on(Phase.LOGICAL_STATES, new Runnable() { public void run() { throw new IllegalStateException("synthetic extraction failure"); } });
        reason(SnapshotCapture.capture(source, source.ownerContext()), Reason.FALLBACK_SOURCE_EXCEPTION);
        source.on(Phase.LOGICAL_STATES, null);
        check(SnapshotCapture.capture(source, source.ownerContext()).accepted(), "retry after source failure");
        CaptureSource throwingScope = new CaptureSource() {
            public View readView() { return source.readView(); }
            public void atPhase(Phase phase) { }
            public boolean syntheticOfflineScope() { throw new IllegalStateException("synthetic scope failure"); }
        };
        reason(SnapshotCapture.capture(throwingScope, source.ownerContext()), Reason.FALLBACK_SOURCE_EXCEPTION);
        CaptureSource unsupported = new CaptureSource() {
            public View readView() { throw new AssertionError("must reject before read"); }
            public void atPhase(Phase phase) { throw new AssertionError("must reject before phases"); }
            public boolean syntheticOfflineScope() { return false; }
        };
        reason(SnapshotCapture.capture(unsupported, source.ownerContext()), Reason.FALLBACK_UNSUPPORTED_SCOPE);
        groups++;
    }

    private static Context teContext(SyntheticCaptureSource source) {
        return new Context(Thread.currentThread(), source.incarnation, source.generation,
                "synthetic-qualified-te-inventory", "synthetic-te-only", true,
                new MapBuilder().writers, null, TileEntityPolicy.PARTICIPATING_MUTATION_EPOCH);
    }

    private static void transport() {
        SyntheticCaptureSource source = source();
        source.incarnation = 9;
        source.generation = 11;
        OwnedPacketSnapshot snapshot = SnapshotCapture.capture(source, source.ownerContext()).snapshot();
        byte[] first = snapshot.toTransportBytes();
        check(first.length == 128 + 2 + 4 + 4096 * 4 + 4096 + 256, "transport exact size");
        check(first[0] == 'R' && first[7] == '1' && first[9] == 1, "transport magic/version");
        first[0] = 0;
        check(snapshot.toTransportBytes()[0] == 'R', "transport output owned");
        check(snapshot.incarnation == 9 && snapshot.generation == 11, "incarnation independent of generation");
        groups++;
    }

    private static void reason(Result result, Reason reason) {
        check(result != null && result.reason() == reason, "reason expected=" + reason + " actual=" + (result == null ? null : result.reason()));
        check(!result.accepted() && !result.productionAuthorityEligible(), "rejection exposes no authority");
        try { result.snapshot(); throw new AssertionError("Rejected snapshot accessible"); }
        catch (IllegalStateException expected) { checks++; }
    }

    private static void check(boolean condition, String label) {
        checks++;
        if (!condition) throw new AssertionError(label);
    }
}
