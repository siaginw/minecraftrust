package com.rustcraft.bridge.capture;

import com.rustcraft.bridge.capture.LiveProtocolTestSupport.FakeChunk;
import com.rustcraft.bridge.capture.LiveProtocolTestSupport.FakeWorld;
import com.rustcraft.bridge.capture.LiveProtocolTestSupport.Session;
import com.rustcraft.bridge.capture.LiveChunkBindings.Binding;
import com.rustcraft.bridge.capture.LiveChunkBindings.BindingIdentity;
import com.rustcraft.bridge.capture.LiveChunkBindings.BindingState;
import com.rustcraft.bridge.capture.LiveWriterGate.CaptureAttempt;
import com.rustcraft.bridge.capture.LiveWriterGate.CaptureOutcome;
import com.rustcraft.bridge.capture.LiveWriterGate.WriteToken;

/**
 * Deterministic end-to-end foundation test for the writer protocol: private
 * I/O build on a worker, release/acquire transfer, owner publication to READY,
 * capture admission/commit, reentrant invalidation, retirement, and stale-job
 * revocation. Also proves deterministic repeatability of the whole cycle.
 */
public final class LiveWriterProtocolEndToEndTest {

    static int pass = 0, fail = 0;

    public static void main(String[] args) {
        try {
            caseFullIoHandoffAndCaptureCycle();
            caseStaleJobTokenAfterRetirement();
            caseDeterministicRepeatability();
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

    /** Model of one queued comparison job: immutable ids plus an opaque revocation token — no live references. */
    static final class ComparisonJob {
        final BindingIdentity identity;
        final LiveChunkBindings.RevocationToken token;

        ComparisonJob(Binding binding) {
            this.identity = binding.identityRecord();
            this.token = binding.revocationToken();
        }

        boolean isStale() { return token.isRevoked(); }
    }

    /** Full cycle: worker private build -> seal -> owner acquire/admit/publish -> capture commit. */
    static void caseFullIoHandoffAndCaptureCycle() {
        Session session = new Session("e2e");
        try {
            FakeWorld world = session.world;
            FakeChunk chunk = new FakeChunk(301);
            byte[] backing = new byte[4];
            PrivateBuildTickets.Ticket ticket = LiveProtocolTestSupport.workerBuildAndSeal(
                    session, world, session.provider, 11L, 12, 13, chunk, backing, false);
            Binding binding = LiveProtocolTestSupport.ownerPublishTicket(
                    session, world, 12, 13, chunk, ticket);
            check("e2e: adopted incarnation published READY with exact identity",
                    binding.state() == BindingState.READY && !binding.isRevoked()
                            && binding.identityRecord().sessionId == session.gate.sessionId(),
                    null);
            check("e2e: private build data transferred to the published graph",
                    backing[0] == 42, "backing[0]=" + backing[0]);

            final long seenBefore = session.gate.captureAttemptsSeen();
            CaptureAttempt attempt = LiveProtocolTestSupport.ownerCapture(session, world, chunk, true);
            check("e2e: capture admitted and sealed",
                    attempt.admitted() && attempt.outcome() == CaptureOutcome.SEALED_COMMITTED,
                    String.valueOf(attempt.outcome()));
            check("e2e: event identity minted once per attempt, entry epoch recorded",
                    attempt.eventId() > 0 && attempt.entryEpoch() == session.gate.epoch(),
                    "event=" + attempt.eventId());
            check("e2e: one seen/admitted/sealed movement",
                    session.gate.captureAttemptsSeen() == seenBefore + 1, null);

            // Reentrant mutation during a live attempt invalidates before mutation and discards.
            final String[] discardOutcome = new String[1];
            session.owner.run(() -> {
                CaptureAttempt live = session.gate.tryBeginCapture(world, chunk);
                if (!live.admitted()) throw new AssertionError("live attempt not admitted");
                WriteToken token = session.gate.beginWrite(chunk, new Object[] { backing }, "light-update");
                boolean invalidatedBeforeMutation = live.invalidated();
                backing[1] = 7; // the protected mutation the capture must not own
                session.gate.endWrite(token, null);
                boolean committed = session.gate.endCapture(live, null);
                discardOutcome[0] = (invalidatedBeforeMutation && !committed
                        && live.outcome() == CaptureOutcome.REENTRANT_WRITE)
                        ? "INVALIDATED_BEFORE_MUTATION" : "BROKEN";
                return null;
            });
            check("e2e: reentrant owner mutation invalidated the attempt before the mutation",
                    "INVALIDATED_BEFORE_MUTATION".equals(discardOutcome[0]), discardOutcome[0]);

            // After invalidation the Java graph is intact and the next capture is clean.
            CaptureAttempt retry = LiveProtocolTestSupport.ownerCapture(session, world, chunk, true);
            check("e2e: next capture after the mutation window commits cleanly",
                    retry.admitted() && retry.outcome() == CaptureOutcome.SEALED_COMMITTED,
                    String.valueOf(retry.outcome()));
        } finally {
            session.close();
        }
    }

    /** Queued jobs carry only token + immutable ids; retirement stales them and never transfers eligibility. */
    static void caseStaleJobTokenAfterRetirement() {
        Session session = new Session("e2e-stale-job");
        try {
            FakeWorld world = session.world;
            FakeChunk chunk = new FakeChunk(303);
            PrivateBuildTickets.Ticket ticket = LiveProtocolTestSupport.workerBuildAndSeal(
                    session, world, session.provider, 21L, 40, 41, chunk, new byte[2], false);
            Binding binding = LiveProtocolTestSupport.ownerPublishTicket(
                    session, world, 40, 41, chunk, ticket);
            ComparisonJob job = new ComparisonJob(binding);
            check("stale-job: queued job carries immutable ids and an unrevoked token",
                    !job.isStale() && job.identity.sessionId == session.gate.sessionId(), null);

            session.ownerWrite("unload", () -> session.bindings.retire(world, chunk));
            check("stale-job: retirement revokes the queued job's token (gate-free consumer view)",
                    job.isStale(), null);

            // Replacement at the same coordinates is a new lifecycle with new identity.
            FakeChunk replacement = new FakeChunk(304);
            Binding replacementBinding = session.ownerWrite("replace", () -> {
                LiveChunkBindings.Outcome begun = session.bindings.beginPublication(world, replacement);
                if (!begun.ok()) throw new AssertionError("beginPublication failed: " + begun.reason());
                session.bindings.recordMapPut(world, 40, 41, replacement);
                session.bindings.finishPublication(begun.publication(), null);
                return begun.binding();
            });
            BindingIdentity newIdentity = replacementBinding.identityRecord();
            check("stale-job: replacement binding has a different incarnation/chunk identity",
                    newIdentity.incarnation > job.identity.incarnation
                            && newIdentity.chunkId != job.identity.chunkId,
                    "inc " + job.identity.incarnation + "->" + newIdentity.incarnation);
            check("stale-job: the stale job can never authorize the replacement at the same coordinates",
                    job.isStale() && job.identity.ownedEncodeGeneration != newIdentity.ownedEncodeGeneration,
                    null);
            CaptureAttempt replacementCapture = LiveProtocolTestSupport.ownerCapture(
                    session, world, replacement, true);
            check("stale-job: only the fresh incarnation captures",
                    replacementCapture.admitted()
                            && replacementCapture.outcome() == CaptureOutcome.SEALED_COMMITTED,
                    String.valueOf(replacementCapture.outcome()));
        } finally {
            session.close();
        }
    }

    /** The whole canonical cycle repeats with identical outcomes on fresh sessions. */
    static void caseDeterministicRepeatability() {
        String first = canonicalCycleResult(1);
        String second = canonicalCycleResult(2);
        String third = canonicalCycleResult(3);
        check("repeatability: three independent sessions produce identical canonical outcomes",
                first.equals(second) && second.equals(third),
                first + " vs " + second + " vs " + third);
        check("repeatability: canonical outcome contains every expected protocol marker",
                first.contains("ACQUIRE=SUCCESS")
                        && first.contains("ADMIT=PRIVATE_FRESH_DISK")
                        && first.contains("PUBLISH=READY")
                        && first.contains("CAPTURE=SEALED_COMMITTED")
                        && first.contains("REENTRANT=INVALIDATED_BEFORE_MUTATION")
                        && first.contains("WRITER_ACTIVE_FALLBACK=true")
                        && first.contains("RETIRED=NO_BINDING")
                        && first.contains("REPLACEMENT=READY"),
                first);
    }

    /** One canonical protocol cycle; returns a deterministic summary of every outcome. */
    static String canonicalCycleResult(int run) {
        Session session = new Session("repeatability-" + run);
        try {
            FakeWorld world = session.world;
            FakeChunk chunk = new FakeChunk(700);
            byte[] backing = new byte[2];
            PrivateBuildTickets.Ticket ticket = LiveProtocolTestSupport.workerBuildAndSeal(
                    session, world, session.provider, 1000L, 1, 2, chunk, backing, false);
            final PrivateBuildTickets.Ticket acquired = ticket;
            String acquire = session.ownerWrite("acquire", () -> {
                String outcome = session.tickets.acquireCompletion(acquired).name();
                // Publish the consumed ticket in the same whole owner operation.
                LiveChunkBindings.Outcome admitted = session.bindings.admitIoTicket(acquired, world, chunk);
                if (!admitted.ok()) throw new AssertionError("admit failed: " + admitted.reason());
                LiveChunkBindings.Outcome begun = session.bindings.beginPublication(world, chunk);
                if (!begun.ok()) throw new AssertionError("beginPublication failed: " + begun.reason());
                session.bindings.recordMapPut(world, 1, 2, chunk);
                session.bindings.finishPublication(begun.publication(), null);
                return outcome;
            });
            Binding binding = session.bindings.bindingFor(chunk);
            String publishState = binding.state().name();
            String source = binding.sourceQualification().name();

            CaptureAttempt capture = LiveProtocolTestSupport.ownerCapture(session, world, chunk, true);
            String captureOutcome = capture.outcome().name();

            final String[] reentrant = new String[1];
            final String[] writerActive = new String[1];
            session.owner.run(() -> {
                CaptureAttempt live = session.gate.tryBeginCapture(world, chunk);
                if (!live.admitted()) throw new AssertionError("capture not admitted");
                WriteToken token = session.gate.beginWrite(chunk, null, "mutation");
                reentrant[0] = live.invalidated() ? "INVALIDATED_BEFORE_MUTATION" : "BROKEN";
                session.gate.endWrite(token, null);
                session.gate.endCapture(live, null);
                // Writer-active probe: a capture attempt inside an active writer scope
                // must fall back immediately, without waiting on the held gate.
                WriteToken probe = session.gate.beginWrite(chunk, null, "probe");
                CaptureAttempt busy = session.gate.tryBeginCapture(world, chunk);
                writerActive[0] = String.valueOf(!busy.admitted()
                        && busy.fallbackReason() == LiveWriterGate.FallbackReason.WRITER_ACTIVE);
                session.gate.endWrite(probe, null);
                return null;
            });

            session.ownerWrite("unload", () -> session.bindings.retire(world, chunk));
            CaptureAttempt afterUnload = session.owner.run(
                    () -> session.gate.tryBeginCapture(world, chunk));
            String retired = afterUnload.fallbackReason().name();

            FakeChunk replacement = new FakeChunk(701);
            String replacementState = session.ownerWrite("replace", () -> {
                LiveChunkBindings.Outcome begun = session.bindings.beginPublication(world, replacement);
                if (!begun.ok()) throw new AssertionError("beginPublication failed: " + begun.reason());
                session.bindings.recordMapPut(world, 1, 2, replacement);
                session.bindings.finishPublication(begun.publication(), null);
                return begun.binding().state().name();
            });

            return "ACQUIRE=" + acquire
                    + "|ADMIT=" + ("FRESH_DISK_CURRENT".equals(source) ? "PRIVATE_FRESH_DISK" : source)
                    + "|PUBLISH=" + publishState
                    + "|CAPTURE=" + captureOutcome
                    + "|REENTRANT=" + reentrant[0]
                    + "|WRITER_ACTIVE_FALLBACK=" + writerActive[0]
                    + "|RETIRED=" + retired
                    + "|REPLACEMENT=" + replacementState;
        } finally {
            session.close();
        }
    }
}
