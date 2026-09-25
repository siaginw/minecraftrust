package com.rustcraft.bridge.capture;

import com.rustcraft.bridge.capture.LiveProtocolTestSupport.FakeChunk;
import com.rustcraft.bridge.capture.LiveProtocolTestSupport.FakeWorld;
import com.rustcraft.bridge.capture.LiveProtocolTestSupport.Session;

/**
 * Deterministic foundation tests for {@link LiveWriterGate}: writer
 * lifecycle, capture admission, reentrant invalidation, cleanup on all paths,
 * and terminal before-write violations. All scheduling is deterministic
 * (single owner thread plus bounded-join worker threads); no sleeps.
 */
public final class LiveWriterGateTest {

    static int pass = 0, fail = 0;

    public static void main(String[] args) throws Exception {
        try {
            caseDisabledGateIsNoop();
            caseValidUncontendedWriter();
            caseWriterContentionNestedScopes();
            caseCaptureAdmissionWithNoWriter();
            caseCaptureAdmissionWhileWriterActive();
            caseCaptureAdmissionNestedCaptureFallback();
            caseCaptureAdmissionOffThreadFallback();
            caseReentrantMutationInvalidatesCaptureBeforeMutation();
            caseReentrantAbaRestoredBytesStillInvalidated();
            caseWriterBehaviorWhileCaptureActive();
            caseWriterCleanupOnException();
            caseOffOwnerPublishedWriteFailsClosed();
            caseUnknownWriterWithoutParticipationFailsClosed();
            caseEpochExhaustionDisqualifies();
            caseLeakedPublicationDepthBlocksCapture();
            caseShutdownQuiesceTurnsNoop();
        } catch (Throwable uncaught) {
            fail++;
            System.out.println("UNCAUGHT: " + uncaught);
        } finally {
            System.out.println("RESULT: " + pass + " pass, " + fail + " fail");
        }
        System.exit(fail == 0 ? 0 : 1);
    }

    static void check(String name, boolean ok, String detail) {
        if (ok) {
            pass++;
            System.out.println(name + " -> PASS" + (detail == null ? "" : " (" + detail + ")"));
        } else {
            fail++;
            System.out.println(name + " -> FAIL " + detail);
        }
    }

    /** Negative control: with the diagnostic never enabled, everything is NOOP and capture falls back. */
    static void caseDisabledGateIsNoop() {
        LiveWriterGate gate = new LiveWriterGate(Thread.currentThread());
        LiveWriterGate.WriteToken outer = gate.beginWrite(null, null, "disabled-outer");
        LiveWriterGate.WriteToken inner = gate.beginWrite(null, null, "disabled-inner");
        check("disabled: writer scopes are NOOP",
                outer.kind() == LiveWriterGate.WriteToken.Kind.NOOP
                        && inner.kind() == LiveWriterGate.WriteToken.Kind.NOOP,
                outer.kind() + "/" + inner.kind());
        gate.endWrite(inner, null);
        gate.endWrite(outer, null);
        LiveWriterGate.CaptureAttempt attempt = gate.tryBeginCapture(null, null);
        check("disabled: capture falls back DISABLED without admitting",
                !attempt.admitted() && attempt.fallbackReason() == LiveWriterGate.FallbackReason.DISABLED,
                String.valueOf(attempt.fallbackReason()));
        check("disabled: epoch never advanced and nothing was admitted",
                gate.epoch() == 1L && gate.captureAttemptsAdmitted() == 0 && !gate.isEnabled(),
                "epoch=" + gate.epoch());
    }

    /** Valid uncontended writer: owner scope takes the gate, advances the epoch once, and cleans up. */
    static void caseValidUncontendedWriter() {
        Session session = new Session("uncontended");
        try {
            long epochBefore = session.owner.run(session.gate::epoch);
            session.ownerWrite("plain-owner-write", () -> {
                check("uncontended: gate held by owner inside scope",
                        session.gate.epoch() >= epochBefore && session.gate.writerDepth() == 1, null);
                return null;
            });
            check("uncontended: cleanup on normal return (depth 0)",
                    session.gate.writerDepth() == 0, "depth=" + session.gate.writerDepth());
            check("uncontended: outermost entry advanced epoch exactly once",
                    session.gate.epoch() == epochBefore + 1,
                    "epoch " + epochBefore + " -> " + session.gate.epoch());
            session.owner.run(() -> {
                session.gate.shutdownForSessionEnd();
                return null;
            });
            check("uncontended: fully quiesced shutdown accepted", true, null);
        } finally {
            session.close();
        }
    }

    /** Writer contention on the owner: nested scopes nest; the epoch advances once per outermost entry. */
    static void caseWriterContentionNestedScopes() {
        Session session = new Session("contention");
        try {
            session.owner.run(() -> {
                long epoch0 = session.gate.epoch();
                LiveWriterGate.WriteToken outer = session.gate.beginWrite(null, null, "outer");
                LiveWriterGate.WriteToken inner = session.gate.beginWrite(null, null, "inner");
                check("contention: nested scopes both HELD",
                        outer.kind() == LiveWriterGate.WriteToken.Kind.HELD
                                && inner.kind() == LiveWriterGate.WriteToken.Kind.HELD, null);
                check("contention: writer depth is two while nested",
                        session.gate.writerDepth() == 2, "depth=" + session.gate.writerDepth());
                session.gate.endWrite(inner, null);
                session.gate.endWrite(outer, null);
                check("contention: depth returns to zero and epoch advanced once",
                        session.gate.writerDepth() == 0 && session.gate.epoch() == epoch0 + 1,
                        "depth=" + session.gate.writerDepth() + " epoch=" + session.gate.epoch());
                return null;
            });
        } finally {
            session.close();
        }
    }

    /** Capture admission with no writer active: one tryLock admits; commit seals under the held gate. */
    static void caseCaptureAdmissionWithNoWriter() {
        Session session = new Session("capture-ok");
        try {
            FakeWorld world = session.world;
            FakeChunk chunk = new FakeChunk(7);
            ownerGenerateAndPublish(session, world, chunk, 3, 4);
            long seen0 = session.gate.captureAttemptsSeen();
            LiveWriterGate.CaptureAttempt attempt = LiveProtocolTestSupport.ownerCapture(
                    session, world, chunk, true);
            check("capture-ok: admitted with minted event id and entry epoch",
                    attempt.admitted() && attempt.eventId() > 0 && attempt.entryEpoch() > 0,
                    "event=" + attempt.eventId() + " epoch0=" + attempt.entryEpoch());
            check("capture-ok: commit sealed SEALED_COMMITTED",
                    attempt.outcome() == LiveWriterGate.CaptureOutcome.SEALED_COMMITTED,
                    String.valueOf(attempt.outcome()));
            check("capture-ok: counters seen/admitted/sealed",
                    session.gate.captureAttemptsSeen() == seen0 + 1
                            && session.gate.captureAttemptsAdmitted() >= 1
                            && session.gate.captureAttemptsSealed() >= 1, null);
        } finally {
            session.close();
        }
    }

    /** Capture admission while a writer is active: immediate WRITER_ACTIVE fallback, no lock acquisition. */
    static void caseCaptureAdmissionWhileWriterActive() {
        Session session = new Session("capture-busy-writer");
        try {
            FakeWorld world = session.world;
            FakeChunk chunk = new FakeChunk(11);
            session.ownerWrite("writer-active", () -> {
                LiveWriterGate.CaptureAttempt attempt = session.gate.tryBeginCapture(world, chunk);
                check("writer-active: immediate WRITER_ACTIVE fallback",
                        !attempt.admitted()
                                && attempt.fallbackReason() == LiveWriterGate.FallbackReason.WRITER_ACTIVE,
                        String.valueOf(attempt.fallbackReason()));
                check("writer-active: no admission counter movement",
                        session.gate.captureAttemptsAdmitted() == 0, null);
                return null;
            });
        } finally {
            session.close();
        }
    }

    /** A second capture attempt while one attempt is active rejects as NESTED_CAPTURE. */
    static void caseCaptureAdmissionNestedCaptureFallback() {
        Session session = new Session("capture-nested");
        try {
            FakeWorld world = session.world;
            FakeChunk chunk = new FakeChunk(13);
            ownerGenerateAndPublish(session, world, chunk, 5, 6);
            session.owner.run(() -> {
                LiveWriterGate.CaptureAttempt attempt = session.gate.tryBeginCapture(world, chunk);
                if (!attempt.admitted()) throw new AssertionError("setup capture not admitted");
                LiveWriterGate.CaptureAttempt nested = session.gate.tryBeginCapture(world, chunk);
                check("nested: second attempt rejects NESTED_CAPTURE without waiting",
                        !nested.admitted()
                                && nested.fallbackReason() == LiveWriterGate.FallbackReason.NESTED_CAPTURE,
                        String.valueOf(nested.fallbackReason()));
                session.gate.endCapture(attempt, null);
                return null;
            });
        } finally {
            session.close();
        }
    }

    /** Off-thread capture attempts are an ordinary immediate fallback, never a violation. */
    static void caseCaptureAdmissionOffThreadFallback() {
        Session session = new Session("capture-offthread");
        try {
            FakeWorld world = session.world;
            FakeChunk chunk = new FakeChunk(17);
            ownerGenerateAndPublish(session, world, chunk, 7, 8);
            LiveProtocolTestSupport.inWorker("capture-attempter", () -> {
                LiveWriterGate.CaptureAttempt attempt = session.gate.tryBeginCapture(world, chunk);
                check("offthread: immediate OFF_THREAD fallback",
                        !attempt.admitted()
                                && attempt.fallbackReason() == LiveWriterGate.FallbackReason.OFF_THREAD,
                        String.valueOf(attempt.fallbackReason()));
                return null;
            });
            check("offthread: no terminal disqualification for a rejected attempt",
                    !session.gate.isTerminalDisqualified(), null);
        } finally {
            session.close();
        }
    }

    /**
     * Reentrant owner mutation during an active capture invalidates the attempt
     * and advances the epoch BEFORE the mutation runs.
     */
    static void caseReentrantMutationInvalidatesCaptureBeforeMutation() {
        Session session = new Session("reentrant");
        try {
            FakeWorld world = session.world;
            FakeChunk chunk = new FakeChunk(19);
            ownerGenerateAndPublish(session, world, chunk, 9, 10);
            byte[] mutated = new byte[4];
            session.owner.run(() -> {
                LiveWriterGate.CaptureAttempt attempt = session.gate.tryBeginCapture(world, chunk);
                if (!attempt.admitted()) throw new AssertionError("capture not admitted");
                long epochAtCapture = attempt.entryEpoch();
                LiveWriterGate.WriteToken token =
                        session.gate.beginWrite(chunk, new Object[] { mutated }, "reentrant-mutation");
                // The invalidation must already be observable BEFORE any mutation.
                check("reentrant: attempt invalidated before the mutation store",
                        attempt.invalidated()
                                && attempt.invalidationReason() == LiveWriterGate.InvalidationReason.REENTRANT_WRITE,
                        String.valueOf(attempt.invalidationReason()));
                check("reentrant: epoch advanced before the mutation store",
                        session.gate.epoch() == epochAtCapture + 1,
                        "epoch=" + session.gate.epoch());
                mutated[0] = 1; // the actual (now diagnostic-irrelevant) mutation
                session.gate.endWrite(token, null);
                boolean committed = session.gate.endCapture(attempt, null);
                check("reentrant: commit refuses the invalidated attempt",
                        !committed && attempt.outcome() == LiveWriterGate.CaptureOutcome.REENTRANT_WRITE,
                        String.valueOf(attempt.outcome()));
                LiveWriterGate.CaptureAttempt next = session.gate.tryBeginCapture(world, chunk);
                check("reentrant: gate released after discard (next capture admits cleanly)",
                        next.admitted(), String.valueOf(next.fallbackReason()));
                session.gate.endCapture(next, null);
                return null;
            });
        } finally {
            session.close();
        }
    }

    /** Reentrant ABA: restoring the mutated bytes afterwards does not resurrect the attempt. */
    static void caseReentrantAbaRestoredBytesStillInvalidated() {
        Session session = new Session("reentrant-aba");
        try {
            FakeWorld world = session.world;
            FakeChunk chunk = new FakeChunk(23);
            ownerGenerateAndPublish(session, world, chunk, 11, 12);
            byte[] mutated = new byte[4];
            session.owner.run(() -> {
                LiveWriterGate.CaptureAttempt attempt = session.gate.tryBeginCapture(world, chunk);
                if (!attempt.admitted()) throw new AssertionError("capture not admitted");
                LiveWriterGate.WriteToken token =
                        session.gate.beginWrite(chunk, new Object[] { mutated }, "aba-mutation");
                mutated[0] = 9;
                mutated[0] = 0; // bytes restored — the invalidation still stands
                session.gate.endWrite(token, null);
                boolean committed = session.gate.endCapture(attempt, null);
                check("aba: restored bytes do not resurrect the invalidated attempt",
                        !committed && attempt.invalidated(),
                        "committed=" + committed);
                return null;
            });
        } finally {
            session.close();
        }
    }

    /** Writer behavior while a capture is active, end to end: discard, then a clean next capture. */
    static void caseWriterBehaviorWhileCaptureActive() {
        Session session = new Session("capture-vs-writer");
        try {
            FakeWorld world = session.world;
            FakeChunk chunk = new FakeChunk(29);
            ownerGenerateAndPublish(session, world, chunk, 13, 14);
            session.owner.run(() -> {
                LiveWriterGate.CaptureAttempt first = session.gate.tryBeginCapture(world, chunk);
                if (!first.admitted()) throw new AssertionError("first capture not admitted");
                LiveWriterGate.WriteToken token = session.gate.beginWrite(null, null, "during-capture");
                session.gate.endWrite(token, null);
                boolean committed = session.gate.endCapture(first, null);
                check("capture-vs-writer: first attempt discarded (REENTRANT_WRITE)",
                        !committed && first.outcome() == LiveWriterGate.CaptureOutcome.REENTRANT_WRITE,
                        String.valueOf(first.outcome()));
                LiveWriterGate.CaptureAttempt second = session.gate.tryBeginCapture(world, chunk);
                check("capture-vs-writer: next capture admits cleanly after cleanup",
                        second.admitted(), String.valueOf(second.fallbackReason()));
                session.gate.endCapture(second, null);
                return null;
            });
        } finally {
            session.close();
        }
    }

    /** Writer cleanup on the exception path: unlock exactly once, depth zero, terminal WRITE_EXCEPTION. */
    static void caseWriterCleanupOnException() {
        Session session = new Session("writer-exception");
        try {
            session.owner.run(() -> {
                LiveWriterGate.WriteToken token = session.gate.beginWrite(null, null, "throws");
                session.gate.endWrite(token, new RuntimeException("owner write failure"));
                check("writer-exception: depth zero after Throwable exit",
                        session.gate.writerDepth() == 0, "depth=" + session.gate.writerDepth());
                check("writer-exception: terminal WRITE_EXCEPTION disqualification",
                        session.gate.isTerminalDisqualified()
                                && session.gate.disqualificationReason()
                                        == LiveWriterGate.DisqualificationReason.WRITE_EXCEPTION,
                        String.valueOf(session.gate.disqualificationReason()));
                LiveWriterGate.CaptureAttempt attempt =
                        session.gate.tryBeginCapture(session.world, new FakeChunk(31));
                check("writer-exception: capture now falls back RUNTIME_DISQUALIFIED (no deadlock, no leak)",
                        !attempt.admitted()
                                && attempt.fallbackReason() == LiveWriterGate.FallbackReason.RUNTIME_DISQUALIFIED,
                        String.valueOf(attempt.fallbackReason()));
                return null;
            });
        } finally {
            session.close();
        }
    }

    /**
     * Off-owner published writes fail closed: the violation throws BEFORE any
     * mutation, without ever waiting on the capture gate.
     */
    static void caseOffOwnerPublishedWriteFailsClosed() {
        Session session = new Session("off-owner");
        try {
            FakeWorld world = session.world;
            FakeChunk chunk = new FakeChunk(37);
            ownerGenerateAndPublish(session, world, chunk, 15, 16);
            byte[] published = new byte[4];
            LiveProtocolTestSupport.inWorker("rogue-writer", () -> {
                try {
                    session.gate.beginWrite(chunk, new Object[] { published }, "rogue-write");
                    check("off-owner: write admitted (MUST NOT HAPPEN)", false, "no exception");
                } catch (LiveWriterGate.ProtocolViolationException violation) {
                    check("off-owner: terminal OFF_OWNER_PUBLISHED_WRITE thrown before mutation",
                            violation.reason() == LiveWriterGate.DisqualificationReason.OFF_OWNER_PUBLISHED_WRITE,
                            String.valueOf(violation.reason()));
                }
                check("off-owner: target bytes unchanged (violation fired before the write)",
                        published[0] == 0 && published[1] == 0 && published[2] == 0 && published[3] == 0, null);
                return null;
            });
            check("off-owner: session terminally disqualified, still enforcing (no reset)",
                    session.gate.isTerminalDisqualified()
                            && session.gate.disqualificationReason()
                                    == LiveWriterGate.DisqualificationReason.OFF_OWNER_PUBLISHED_WRITE,
                    String.valueOf(session.gate.disqualificationReason()));
        } finally {
            session.close();
        }
    }

    /** Unknown writers without participation get the same fail-closed treatment (no best-effort bypass). */
    static void caseUnknownWriterWithoutParticipationFailsClosed() {
        Session session = new Session("unknown-writer");
        try {
            byte[] unknownTarget = new byte[2];
            LiveProtocolTestSupport.inWorker("unknown-mod", () -> {
                try {
                    session.gate.beginWrite(new Object(), new Object[] { unknownTarget }, "raw-array-write");
                    check("unknown-writer: write admitted (MUST NOT HAPPEN)", false, "no exception");
                } catch (LiveWriterGate.ProtocolViolationException violation) {
                    check("unknown-writer: unparticipating raw-array write rejected OFF_OWNER_PUBLISHED_WRITE",
                            violation.reason() == LiveWriterGate.DisqualificationReason.OFF_OWNER_PUBLISHED_WRITE,
                            String.valueOf(violation.reason()));
                }
                check("unknown-writer: no byte written before rejection",
                        unknownTarget[0] == 0 && unknownTarget[1] == 0, null);
                return null;
            });
            check("unknown-writer: session disqualified; unsupported profiles stay Java-only by default",
                    session.gate.isTerminalDisqualified(), null);
        } finally {
            session.close();
        }
    }

    /** Every counter overflow disqualifies; the epoch never wraps. */
    static void caseEpochExhaustionDisqualifies() throws Exception {
        Session session = new Session("epoch-exhaustion");
        try {
            java.lang.reflect.Field epochField = LiveWriterGate.class.getDeclaredField("epoch");
            epochField.setAccessible(true);
            session.owner.run(() -> {
                epochField.setLong(session.gate, Long.MAX_VALUE);
                LiveWriterGate.WriteToken token = session.gate.beginWrite(null, null, "exhausted");
                session.gate.endWrite(token, null);
                check("epoch-exhaustion: outermost entry disqualifies COUNTER_EXHAUSTED",
                        session.gate.isTerminalDisqualified()
                                && session.gate.disqualificationReason()
                                        == LiveWriterGate.DisqualificationReason.COUNTER_EXHAUSTED,
                        String.valueOf(session.gate.disqualificationReason()));
                check("epoch-exhaustion: epoch did not wrap",
                        session.gate.epoch() == Long.MAX_VALUE, "epoch=" + session.gate.epoch());
                return null;
            });
        } finally {
            session.close();
        }
    }

    /** A publication transaction that outlives its writer scope keeps capture at PUBLICATION_ACTIVE. */
    static void caseLeakedPublicationDepthBlocksCapture() {
        Session session = new Session("leaked-publication");
        try {
            FakeWorld world = session.world;
            FakeChunk chunk = new FakeChunk(41);
            session.ownerWrite("leak", () -> {
                LiveChunkBindings.Outcome begun = session.bindings.beginPublication(world, chunk);
                if (!begun.ok()) throw new AssertionError("beginPublication failed: " + begun.reason());
                return null; // deliberately never finished: hook coverage gap
            });
            LiveWriterGate.CaptureAttempt attempt = LiveProtocolTestSupport.ownerCapture(
                    session, world, chunk, false);
            check("leaked-publication: capture falls back PUBLICATION_ACTIVE, never admits",
                    !attempt.admitted()
                            && attempt.fallbackReason() == LiveWriterGate.FallbackReason.PUBLICATION_ACTIVE,
                    String.valueOf(attempt.fallbackReason()));
        } finally {
            session.close();
        }
    }

    /** After fully quiesced shutdown the gate is NOOP and capture is DISABLED. */
    static void caseShutdownQuiesceTurnsNoop() {
        Session session = new Session("shutdown");
        try {
            FakeWorld world = session.world;
            FakeChunk chunk = new FakeChunk(43);
            ownerGenerateAndPublish(session, world, chunk, 17, 18);
            session.owner.run(() -> {
                session.gate.shutdownForSessionEnd();
                return null;
            });
            session.owner.run(() -> {
                LiveWriterGate.WriteToken token = session.gate.beginWrite(null, null, "after-shutdown");
                check("shutdown: writer is NOOP after quiesced shutdown",
                        token.kind() == LiveWriterGate.WriteToken.Kind.NOOP, String.valueOf(token.kind()));
                session.gate.endWrite(token, null);
                LiveWriterGate.CaptureAttempt attempt = session.gate.tryBeginCapture(world, chunk);
                check("shutdown: capture DISABLED after quiesced shutdown",
                        !attempt.admitted() && attempt.fallbackReason() == LiveWriterGate.FallbackReason.DISABLED,
                        String.valueOf(attempt.fallbackReason()));
                return null;
            });
        } finally {
            session.close();
        }
    }

    /** Owner-generation setup: one whole owner operation publishes an untracked chunk to READY. */
    static void ownerGenerateAndPublish(Session session, FakeWorld world, FakeChunk chunk, int x, int z) {
        session.ownerWrite("generate", () -> {
            LiveChunkBindings.Outcome begun = session.bindings.beginPublication(world, chunk);
            if (!begun.ok()) throw new AssertionError("beginPublication failed: " + begun.reason());
            LiveChunkBindings.Outcome put = session.bindings.recordMapPut(world, x, z, chunk);
            if (!put.ok()) throw new AssertionError("recordMapPut failed: " + put.reason());
            LiveChunkBindings.Outcome finished = session.bindings.finishPublication(begun.publication(), null);
            if (!finished.ok()) throw new AssertionError("finishPublication failed: " + finished.reason());
            if (begun.binding().state() != LiveChunkBindings.BindingState.READY) {
                throw new AssertionError("binding not READY after outermost normal completion");
            }
            return null;
        });
    }
}
