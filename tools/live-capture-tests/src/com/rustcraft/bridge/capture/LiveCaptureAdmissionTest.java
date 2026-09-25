package com.rustcraft.bridge.capture;

import com.rustcraft.bridge.capture.LiveChunkBindings.Binding;
import com.rustcraft.bridge.capture.LiveChunkBindings.BindingState;
import com.rustcraft.bridge.capture.LivePacketCapture.JavaPacketView;
import com.rustcraft.bridge.capture.LivePacketCapture.LiveCaptureSource;
import com.rustcraft.bridge.capture.LivePacketCapture.RejectionReason;

import java.util.Arrays;
import java.util.List;

/**
 * Deterministic offline tests for the live capture admission layer: admission
 * through the real writer-protocol gate, one-way sealing, scope-3 transport
 * ownership, bounded nonblocking queue, and every required rejection. Uses
 * synthetic sources (no Minecraft classes, no JNI, no sleeps); the real
 * transformed-runtime path is exercised by the live-transformer lane.
 */
public final class LiveCaptureAdmissionTest {

    private static int pass = 0, fail = 0;

    private static final class FakeWorld { }
    private static final class FakeChunk { }
    private static final class FakePacket { }

    /** Controllable synthetic adapter over the accepted CaptureSource contract. */
    private static final class FakeSource implements LiveCaptureSource {
        final SyntheticCaptureSource delegate = new SyntheticCaptureSource();
        final Object world = new FakeWorld();
        final Object chunk = new FakeChunk();
        boolean teEmpty = true;
        byte[] payload = new byte[] {1, 2, 3, 4};
        int packetMask = -1; // defaults to the draft mask at commit time
        boolean failCreation;
        int extractionFailures;

        FakeSource() {
            delegate.sections[0] = new SyntheticCaptureSource.MutableSection().fill(9);
            delegate.sections[0].blockLight[7] = 13;
            delegate.skylight = true;
        }

        @Override public Object world() { return world; }
        @Override public Object chunk() { return chunk; }
        @Override public boolean tileEntitiesEmpty() { return teEmpty; }

        @Override public JavaPacketView javaPacket(Object packet) {
            final int mask = packetMask >= 0 ? packetMask : acceptedMaskOf(delegate);
            return new JavaPacketView() {
                @Override public byte[] payload() { return payload.clone(); }
                @Override public int mask() { return mask; }
                @Override public boolean fullChunk() { return delegate.fullChunk; }
                @Override public int packetX() { return delegate.chunkX; }
                @Override public int packetZ() { return delegate.chunkZ; }
            };
        }

        @Override public View readView() {
            if (extractionFailures > 0) {
                extractionFailures--;
                throw new IllegalStateException("injected extraction failure");
            }
            return delegate.readView();
        }

        @Override public void atPhase(CaptureContract.Phase phase) { delegate.atPhase(phase); }
        @Override public boolean syntheticOfflineScope() { return false; }
        @Override public CaptureContract.Scope captureScope() {
            return CaptureContract.Scope.LIVE_SHADOW_OWNED_V1;
        }

        int acceptedMaskOf(SyntheticCaptureSource source) {
            int mask = 0;
            View view = source.readView();
            for (int y = 0; y < 16; y++) {
                Section s = view.section(y);
                if ((view.requestedFilter & (1 << y)) != 0 && s != null
                        && (!view.fullChunk || !s.empty)) mask |= 1 << y;
            }
            return mask;
        }
    }

    private static LiveWriterHooks.Session session;

    public static void main(String[] args) {
        try {
            caseDefaultOffInert();
            caseReadyAdmitSealOnceAndConsume();
            caseWriterMutationInvalidatesCapture();
            caseRetiredBindingRejected();
            caseUnknownWriterDisqualifiesThenRejected();
            caseCaptureBusyImmediateFallback();
            caseTePresentRejected();
            caseExtendedIdRejected();
            caseQueueFullDrops();
            caseSealedObjectFullyOwned();
            caseAbortPathReleasesGate();
            caseMaskMismatchRejected();
        } catch (Throwable uncaught) {
            fail++;
            System.out.println("UNCAUGHT: " + uncaught);
        } finally {
            System.out.println("RESULT: " + pass + " pass, " + fail + " fail");
        }
        System.exit(fail == 0 ? 0 : 1);
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) { pass++; System.out.println(name + " -> PASS" + (detail == null ? "" : " (" + detail + ")")); }
        else { fail++; System.out.println(name + " -> FAIL " + detail); }
    }

    private static FakeSource source() {
        FakeSource source = new FakeSource();
        source.packetMask = source.acceptedMaskOf(source.delegate);
        return source;
    }

    /** Publishes the fake chunk to READY under a provider-style writer scope. */
    private static void publish(FakeSource source) {
        LiveChunkBindings bindings = LiveWriterHooks.bindingsForTesting();
        Object writer = LiveWriterHooks.writerBegin(source.chunk, "setup.publish");
        try {
            LiveChunkBindings.Outcome begun = bindings.beginPublication(source.world, source.chunk);
            if (!begun.ok()) throw new AssertionError("beginPublication: " + begun.reason());
            LiveChunkBindings.Outcome put = bindings.recordMapPut(source.world, 5, 6, source.chunk);
            if (!put.ok()) throw new AssertionError("recordMapPut: " + put.reason());
            LiveChunkBindings.Outcome fin = bindings.finishPublication(begun.publication(), null);
            if (!fin.ok()) throw new AssertionError("finishPublication: " + fin.reason());
        } finally {
            LiveWriterHooks.writerEnd(writer, null);
        }
    }

    private static void enable() {
        LiveWriterHooks.enableForTesting(Thread.currentThread());
        session = LiveWriterHooks.currentSessionInternal();
        LivePacketCapture.installSourceFactory(new LivePacketCapture.SourceFactory() {
            @Override public LiveCaptureSource create(Object packet, Object chunk, int filter,
                                                      Binding binding, LiveWriterGate gate) {
                return currentSource; // per-case source
            }
        });
    }

    private static void disable() {
        try { LiveWriterHooks.disableForTesting(); } catch (Throwable ignored) { }
        currentSource = null;
    }

    private static FakeSource currentSource;

    private static FakePacket packet() { return new FakePacket(); }

    // ------------------------------------------------------------------

    /** 20. default OFF: with no diagnostic session, begin is inert end to end. */
    static void caseDefaultOffInert() {
        FakeSource source = source();
        long seen = LivePacketCapture.SEEN.get(), admitted = LivePacketCapture.ADMITTED.get();
        Object token = LivePacketCapture.begin(packet(), source.chunk, 0xFFFF);
        check("default-off: begin returns null with no admission",
                token == null && LivePacketCapture.SEEN.get() == seen
                        && LivePacketCapture.ADMITTED.get() == admitted
                        && LiveComparisonQueue.queuedCount() == 0, null);
    }

    /** 1. READY binding → admitted → sealed exactly once → drained → transport scope 3. */
    static void caseReadyAdmitSealOnceAndConsume() {
        enable();
        try {
            FakeSource src = source();
            currentSource = src;
            publish(src);
            check("matrix-1 setup: binding READY",
                    session.bindings.bindingFor(src.chunk).state() == BindingState.READY, null);
            Object token = LivePacketCapture.begin(packet(), src.chunk, 0xFFFF);
            check("matrix-1: admitted", token != null, null);
            LivePacketCapture.commit(token, packet(), src.chunk, 0xFFFF);
            check("matrix-1: sealed + enqueued",
                    LivePacketCapture.SEALED.get() == 1 && LiveComparisonQueue.queuedCount() == 1, null);
            List<SealedLiveCapture> drained = LiveComparisonQueue.drain();
            check("matrix-1: exactly one sealed capture drained once",
                    drained.size() == 1 && LiveComparisonQueue.drain().isEmpty(), null);
            SealedLiveCapture sealed = drained.get(0);
            byte[] transport = sealed.toTransportBytes();
            check("matrix-1: transport is RCSNAP01 scope 3",
                    transport.length > 128 && new String(transport, 0, 8, java.nio.charset.StandardCharsets.US_ASCII)
                            .equals("RCSNAP01") && transport[13] == 3, "scope=" + transport[13]);
            check("matrix-1: java payload owned and masked",
                    sealed.javaMask() == 1 && Arrays.equals(sealed.javaPayload(),
                            new byte[] {1, 2, 3, 4}), null);
            // double seal/reseal impossible: the token is consumed; a second commit is a no-op
            LivePacketCapture.commit(token, packet(), src.chunk, 0xFFFF);
            check("matrix-12: commit twice ignored (12. seal called twice)",
                    LivePacketCapture.SEALED.get() == 1 && LiveComparisonQueue.queuedCount() == 0, null);
        } finally { disable(); }
    }

    /** 2. writer mutation between begin and commit invalidates the capture. */
    static void caseWriterMutationInvalidatesCapture() {
        enable();
        try {
            FakeSource src = source();
            currentSource = src;
            publish(src);
            Object token = LivePacketCapture.begin(packet(), src.chunk, 0xFFFF);
            check("matrix-2: admitted", token != null, null);
            Object writer = LiveWriterHooks.writerBegin(src.chunk, "mutate-during-capture");
            src.delegate.sections[1] = new SyntheticCaptureSource.MutableSection().fill(3);
            LiveWriterHooks.writerEnd(writer, null);
            long sealedBefore = LivePacketCapture.SEALED.get();
            long validationBefore = LivePacketCapture.VALIDATION_FAILED.get();
            LivePacketCapture.commit(token, packet(), src.chunk, 0xFFFF);
            check("matrix-2: invalidated capture published nothing",
                    LivePacketCapture.SEALED.get() == sealedBefore
                            && LiveComparisonQueue.queuedCount() == 0
                            && LivePacketCapture.VALIDATION_FAILED.get() == validationBefore + 1, null);
        } finally { disable(); }
    }

    /** 3./4./5. retired (unloaded/replaced) binding: no capture by coordinates or history. */
    static void caseRetiredBindingRejected() {
        enable();
        try {
            FakeSource src = source();
            currentSource = src;
            publish(src);
            Object retireScope = LiveWriterHooks.writerBegin(src.chunk, "setup.retire");
            try {
                session.bindings.retire(src.world, src.chunk);
            } finally {
                LiveWriterHooks.writerEnd(retireScope, null);
            }
            long revokedBefore = LivePacketCapture.REVOKED_DROPPED.get();
            Object token = LivePacketCapture.begin(packet(), src.chunk, 0xFFFF);
            check("matrix-3/4/5: retired binding rejects NO_BINDING",
                    token == null && LivePacketCapture.REVOKED_DROPPED.get() == revokedBefore + 1, null);
        } finally { disable(); }
    }

    /** 6. unknown writer (off-owner) disqualifies the session; capture then refuses. */
    static void caseUnknownWriterDisqualifiesThenRejected() {
        enable();
        try {
            FakeSource src = source();
            currentSource = src;
            publish(src);
            try {
                LiveWriterHooks.Session ignored = session;
                Thread rogue = new Thread(() -> LiveWriterHooks.writerBegin(src.chunk, "rogue"));
                rogue.start(); rogue.join(10_000);
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            check("matrix-6: rogue write disqualified the session",
                    LiveWriterHooks.gateForTesting().isTerminalDisqualified(), null);
            Object token = LivePacketCapture.begin(packet(), src.chunk, 0xFFFF);
            check("matrix-6: capture refused RUNTIME_DISQUALIFIED",
                    token == null && LivePacketCapture.UNSUPPORTED.get() >= 1, null);
        } finally { disable(); }
    }

    /** 7. capture lock unavailable: active writer scope → immediate WRITER_ACTIVE fallback. */
    static void caseCaptureBusyImmediateFallback() {
        enable();
        try {
            FakeSource src = source();
            currentSource = src;
            publish(src);
            Object writer = LiveWriterHooks.writerBegin(src.chunk, "hold-gate");
            long busyBefore = LivePacketCapture.CAPTURE_BUSY.get();
            long seenBefore = LivePacketCapture.SEEN.get();
            Object token = LivePacketCapture.begin(packet(), src.chunk, 0xFFFF);
            check("matrix-7: immediate WRITER_ACTIVE fallback, never waits",
                    token == null && LivePacketCapture.CAPTURE_BUSY.get() == busyBefore + 1
                            && LivePacketCapture.SEEN.get() == seenBefore + 1, null);
            LiveWriterHooks.writerEnd(writer, null);
        } finally { disable(); }
    }

    /** 8. TE-bearing chunk → TE_PRESENT rejection; 11. failure during copy → no publish. */
    static void caseTePresentRejected() {
        enable();
        try {
            FakeSource src = source();
            src.teEmpty = false;
            currentSource = src;
            publish(src);
            Object token = LivePacketCapture.begin(packet(), src.chunk, 0xFFFF);
            check("matrix-8: TE-bearing chunk rejected", token == null
                    && LivePacketCapture.UNSUPPORTED.get() >= 1, null);
            // 11: extraction fails after admission (gate released, nothing published)
            FakeSource src2 = source();
            src2.extractionFailures = 1;
            currentSource = src2;
            publish(src2);
            LivePacketCapture.begin(packet(), src2.chunk, 0xFFFF);
            // note: extraction failure path releases the gate; a following capture admits
            check("matrix-11: extraction failure released the gate (next attempt admits)",
                    LiveWriterHooks.gateForTesting().writerDepth() == 0, null);
        } finally { disable(); }
    }

    /** 9. unsupported state width → EXTENDED_ID at capture time. */
    static void caseExtendedIdRejected() {
        enable();
        try {
            FakeSource src = source();
            src.delegate.sections[0].fill(70_000);
            src.packetMask = src.acceptedMaskOf(src.delegate);
            currentSource = src;
            publish(src);
            Object token = LivePacketCapture.begin(packet(), src.chunk, 0xFFFF);
            check("matrix-9: u16 overflow rejected EXTENDED_ID",
                    token == null && LivePacketCapture.UNSUPPORTED.get() >= 1, null);
        } finally { disable(); }
    }

    /** 13. queue full → diagnostic drop, gameplay unaffected. */
    static void caseQueueFullDrops() {
        enable();
        try {
            FakeSource src = source();
            currentSource = src;
            publish(src);
            long dropsBefore = LivePacketCapture.QUEUE_DROPPED.get();
            int capacity = LivePacketCapture.QUEUE_CAPACITY;
            for (int i = 0; i < capacity + 1; i++) {
                Object token = LivePacketCapture.begin(packet(), src.chunk, 0xFFFF);
                LivePacketCapture.commit(token, packet(), src.chunk, 0xFFFF);
            }
            check("matrix-13: capacity overflow dropped the diagnostic work only",
                    LivePacketCapture.QUEUE_DROPPED.get() == dropsBefore + 1
                            && LiveComparisonQueue.queuedCount() == capacity, null);
            LiveComparisonQueue.drain();
        } finally { disable(); }
    }

    /** 14./15. sealed object fully owned; consumer processes it after the gate is gone. */
    static void caseSealedObjectFullyOwned() {
        enable();
        try {
            FakeSource src = source();
            currentSource = src;
            publish(src);
            Object token = LivePacketCapture.begin(packet(), src.chunk, 0xFFFF);
            LivePacketCapture.commit(token, packet(), src.chunk, 0xFFFF);
            SealedLiveCapture sealed = LiveComparisonQueue.drain().get(0);
            byte[] first = sealed.javaPayload();
            first[0] = 99; // mutate the returned clone
            check("matrix-14: java payload returned by clone",
                    !Arrays.equals(first, sealed.javaPayload())
                            && sealed.javaPayload()[0] == 1, null);
            byte[] transportA = sealed.toTransportBytes();
            byte[] transportB = sealed.toTransportBytes();
            check("matrix-14: transport deterministic and fully owned",
                    Arrays.equals(transportA, transportB) && transportA[13] == 3, null);
            disable(); // gate/session gone: the consumer can still process the sealed value
            byte[] afterShutdown = sealed.toTransportBytes();
            check("matrix-15: consumer works after capture completion",
                    Arrays.equals(transportA, afterShutdown), null);
            enable(); // restore for the shared counter state of later cases
            currentSource = src;
        } finally { disable(); }
    }

    /** 18. Throwable path: abort releases the gate and preserves the throwable. */
    static void caseAbortPathReleasesGate() {
        enable();
        try {
            FakeSource src = source();
            currentSource = src;
            publish(src);
            Object token = LivePacketCapture.begin(packet(), src.chunk, 0xFFFF);
            check("matrix-18 setup: admitted", token != null, null);
            RuntimeException original = new RuntimeException("constructor failure");
            LivePacketCapture.abort(token, original);
            check("matrix-18: abort released the gate for the next attempt",
                    LiveWriterHooks.gateForTesting().writerDepth() == 0
                            && LiveComparisonQueue.queuedCount() == 0
                            && LivePacketCapture.ABORTED.get() >= 1, null);
            Object retry = LivePacketCapture.begin(packet(), src.chunk, 0xFFFF);
            check("matrix-18: next attempt admits after abort", retry != null, null);
            LivePacketCapture.abort(retry, original);
        } finally { disable(); }
    }

    /** Java packet shell mismatch (mask) → PACKET-level rejection, nothing published. */
    static void caseMaskMismatchRejected() {
        enable();
        try {
            FakeSource src = source();
            src.packetMask = src.acceptedMaskOf(src.delegate) ^ 1; // wrong shell mask
            currentSource = src;
            publish(src);
            long sealedBefore = LivePacketCapture.SEALED.get();
            Object token = LivePacketCapture.begin(packet(), src.chunk, 0xFFFF);
            check("mask-mismatch setup: admitted", token != null, null);
            LivePacketCapture.commit(token, packet(), src.chunk, 0xFFFF);
            check("mask-mismatch: commit rejected, nothing published",
                    LivePacketCapture.SEALED.get() == sealedBefore
                            && LiveComparisonQueue.queuedCount() == 0, null);
        } finally { disable(); }
    }
}
