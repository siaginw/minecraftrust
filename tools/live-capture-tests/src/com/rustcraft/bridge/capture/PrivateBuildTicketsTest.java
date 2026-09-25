package com.rustcraft.bridge.capture;

import com.rustcraft.bridge.capture.LiveProtocolTestSupport.FakeChunk;
import com.rustcraft.bridge.capture.LiveProtocolTestSupport.FakeNbtRoot;
import com.rustcraft.bridge.capture.LiveProtocolTestSupport.FakeProvider;
import com.rustcraft.bridge.capture.LiveProtocolTestSupport.FakeWorld;
import com.rustcraft.bridge.capture.LiveProtocolTestSupport.Session;
import com.rustcraft.bridge.capture.PrivateBuildTickets.AcquireOutcome;
import com.rustcraft.bridge.capture.PrivateBuildTickets.Ticket;
import com.rustcraft.bridge.capture.PrivateBuildTickets.TicketState;

/**
 * Deterministic foundation tests for {@link PrivateBuildTickets}: ticket
 * lifecycle, exact creator/TLS discipline, sticky provenance, sealing and
 * completion semantics, identity mismatches, and the fail-closed admission
 * rules. No sleeps; all cross-thread ordering is join/await based.
 */
public final class PrivateBuildTicketsTest {

    static int pass = 0, fail = 0;

    public static void main(String[] args) {
        try {
            caseTicketLifecycleAndCompletion();
            caseAcquireBeforeSealDoesNotWait();
            caseTicketCreatorMismatch();
            caseBeginIoMisuse();
            caseFailedBuildCannotPublish();
            caseWriteFailedForcesFailurePublication();
            caseSealedTicketCannotRegainPermission();
            caseTicketSessionMismatch();
            caseTicketIdentityMismatches();
            caseTicketBackingStorageMismatch();
            casePendingNbtSourceSticky();
            caseOldDataVersionSticky();
            caseContradictorySourceTaints();
            caseDoubleRegistrationTaints();
            casePrivateGraphEscapeThrows();
            caseLiveBackingThroughFreshWrapperFailsBeforeWrite();
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

    /** BUILDING -> private writes -> sealSuccess -> owner acquire SUCCESS -> replay rejected. */
    static void caseTicketLifecycleAndCompletion() {
        Session session = new Session("ticket-lifecycle");
        try {
            FakeWorld world = session.world;
            FakeChunk chunk = new FakeChunk(101);
            byte[] backing = new byte[4];
            Ticket ticket = LiveProtocolTestSupport.workerBuildAndSeal(
                    session, world, session.provider, 1L, 2, 3, chunk, backing, false);
            check("lifecycle: private build wrote through the bypass",
                    backing[0] == 42, "backing[0]=" + backing[0]);
            check("lifecycle: sealed success with qualified fresh-disk source",
                    ticket.state() == TicketState.SEALED_SUCCESS
                            && ticket.sourceStatus() == PrivateBuildTickets.SourceStatus.FRESH_DISK_CURRENT
                            && !ticket.isWriteFailed(),
                    ticket.state() + "/" + ticket.sourceStatus());
            AcquireOutcome acquired = session.ownerWrite("acquire",
                    () -> session.tickets.acquireCompletion(ticket));
            check("lifecycle: owner acquire returns SUCCESS once",
                    acquired == AcquireOutcome.SUCCESS, String.valueOf(acquired));
            AcquireOutcome replayed = session.ownerWrite("acquire-again",
                    () -> session.tickets.acquireCompletion(ticket));
            check("lifecycle: second acquire is REPLAYED (single-use)",
                    replayed == AcquireOutcome.REPLAYED, String.valueOf(replayed));
            // Admission is also single-use: after one successful admission, retirement and
            // replay of the same consumed ticket can never authorize publication again.
            LiveChunkBindings.Outcome admittedOnce = session.ownerWrite("admit",
                    () -> session.bindings.admitIoTicket(ticket, session.world, chunk));
            check("lifecycle: consumed SUCCESS ticket admits to a PRIVATE binding",
                    admittedOnce.ok()
                            && admittedOnce.binding().state() == LiveChunkBindings.BindingState.PRIVATE,
                    String.valueOf(admittedOnce.reason()));
            session.ownerWrite("unload", () -> session.bindings.retire(session.world, chunk));
            LiveChunkBindings.Outcome replayedAdmission = session.ownerWrite("re-admit",
                    () -> session.bindings.admitIoTicket(ticket, session.world, chunk));
            check("lifecycle: replayed admission of the consumed ticket refused TICKET_REPLAYED",
                    !replayedAdmission.ok()
                            && replayedAdmission.reason() == LiveChunkBindings.FailureReason.TICKET_REPLAYED,
                    String.valueOf(replayedAdmission.reason()));
        } finally {
            session.close();
        }
    }

    /** A ticket that has not sealed yet returns NOT_YET_SEALED; acquisition never waits. */
    static void caseAcquireBeforeSealDoesNotWait() {
        Session session = new Session("ticket-early-acquire");
        try {
            Ticket ticket = LiveProtocolTestSupport.inWorker("builder", () ->
                    session.tickets.beginIo(session.provider, 2L, session.world, 0, 0));
            long startNanos = System.nanoTime();
            AcquireOutcome outcome = session.ownerWrite("acquire",
                    () -> session.tickets.acquireCompletion(ticket));
            long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000L;
            check("early-acquire: NOT_YET_SEALED without waiting",
                    outcome == AcquireOutcome.NOT_YET_SEALED && elapsedMillis < 5_000L,
                    outcome + " after " + elapsedMillis + "ms");
            check("early-acquire: ticket stays BUILDING",
                    ticket.state() == TicketState.BUILDING, String.valueOf(ticket.state()));
        } finally {
            session.close();
        }
    }

    /** Only the exact creator thread holding the ticket TLS may record provenance or seal. */
    static void caseTicketCreatorMismatch() throws Exception {
        Session session = new Session("ticket-creator");
        try {
            final FakeWorld world = new FakeWorld(9);
            final java.util.concurrent.CountDownLatch ticketReady =
                    new java.util.concurrent.CountDownLatch(1);
            final java.util.concurrent.CountDownLatch impostorDone =
                    new java.util.concurrent.CountDownLatch(1);
            final Ticket[] holder = new Ticket[1];
            final boolean[] sealedByCreator = new boolean[1];
            Thread creator = new Thread(new Runnable() {
                @Override public void run() {
                    holder[0] = session.tickets.beginIo(session.provider, 3L, world, 1, 1);
                    ticketReady.countDown();
                    try {
                        impostorDone.await(30, java.util.concurrent.TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    sealedByCreator[0] = session.tickets.sealSuccess(holder[0]);
                }
            }, "creator");
            creator.setDaemon(true);
            creator.start();
            if (!ticketReady.await(30, java.util.concurrent.TimeUnit.SECONDS)) {
                throw new AssertionError("creator never produced the ticket");
            }
            // This main test thread is the impostor: a different thread without the creator TLS.
            check("creator: impostor recordDiskRoot rejected",
                    !session.tickets.recordDiskRoot(holder[0], new FakeNbtRoot(1),
                            PrivateBuildTickets.CURRENT_DATA_VERSION), null);
            check("creator: impostor sealSuccess rejected",
                    !session.tickets.sealSuccess(holder[0]), null);
            check("creator: impostor sealFailure rejected",
                    !session.tickets.sealFailure(holder[0]), null);
            check("creator: ticket untouched by impostor attempts",
                    holder[0].state() == TicketState.BUILDING
                            && holder[0].sourceStatus() == PrivateBuildTickets.SourceStatus.NONE,
                    holder[0].state() + "/" + holder[0].sourceStatus());
            impostorDone.countDown();
            creator.join(30_000L);
            check("creator: exact creator still seals after impostor rejections",
                    sealedByCreator[0] && holder[0].state() == TicketState.SEALED_SUCCESS,
                    String.valueOf(sealedByCreator[0]));
        } finally {
            session.close();
        }
    }

    /** beginIo discipline: one active ticket per thread, loadIds unique per session. */
    static void caseBeginIoMisuse() {
        Session session = new Session("ticket-misuse");
        try {
            Ticket first = LiveProtocolTestSupport.inWorker("busy", () -> {
                Ticket a = session.tickets.beginIo(session.provider, 4L, session.world, 0, 0);
                Ticket b = session.tickets.beginIo(session.provider, 5L, session.world, 0, 0);
                check("misuse: second beginIo on a busy worker thread rejected",
                        a != null && b == null
                                && session.tickets.lastAdmissionFailure()
                                        == PrivateBuildTickets.TicketFailure.THREAD_BUSY,
                        String.valueOf(session.tickets.lastAdmissionFailure()));
                return a;
            });
            Ticket duplicate = LiveProtocolTestSupport.inWorker("second", () ->
                    session.tickets.beginIo(session.provider, 4L, session.world, 0, 0));
            check("misuse: duplicate loadId rejected session-wide",
                    duplicate == null
                            && session.tickets.lastAdmissionFailure()
                                    == PrivateBuildTickets.TicketFailure.DUPLICATE_LOAD_ID,
                    String.valueOf(session.tickets.lastAdmissionFailure()));
            check("misuse: first ticket remains valid",
                    first != null && first.state() == TicketState.BUILDING, null);
        } finally {
            session.close();
        }
    }

    /** A failed build can never publish: admission refuses a SEALED_FAILURE ticket outright. */
    static void caseFailedBuildCannotPublish() {
        Session session = new Session("ticket-failed");
        try {
            FakeChunk chunk = new FakeChunk(103);
            byte[] backing = new byte[2];
            Ticket ticket = LiveProtocolTestSupport.workerBuildAndSeal(
                    session, session.world, session.provider, 6L, 8, 9, chunk, backing, true);
            check("failed: ticket sealed SEALED_FAILURE",
                    ticket.state() == TicketState.SEALED_FAILURE, String.valueOf(ticket.state()));
            LiveChunkBindings.Outcome admitted = session.ownerWrite("admit",
                    () -> session.bindings.admitIoTicket(ticket, session.world, chunk));
            check("failed: admission refuses the failed ticket",
                    !admitted.ok() && admitted.reason() == LiveChunkBindings.FailureReason.TICKET_FAILED,
                    String.valueOf(admitted.reason()));
            check("failed: no binding was created for the failed incarnation",
                    session.bindings.bindingFor(chunk) == null, null);
        } finally {
            session.close();
        }
    }

    /** Any private writer Throwable forces sealSuccess to publish SEALED_FAILURE (monotonic writeFailed). */
    static void caseWriteFailedForcesFailurePublication() {
        Session session = new Session("ticket-writefailed");
        try {
            FakeChunk chunk = new FakeChunk(107);
            byte[] backing = new byte[2];
            Ticket ticket = LiveProtocolTestSupport.inWorker("builder", () -> {
                Ticket t = session.tickets.beginIo(session.provider, 7L, session.world, 4, 4);
                session.tickets.recordDiskRoot(t, new FakeNbtRoot(7),
                        PrivateBuildTickets.CURRENT_DATA_VERSION);
                session.tickets.registerNew(t, chunk, null);
                session.tickets.registerNew(t, backing, null);
                LiveWriterGate.WriteToken write =
                        session.gate.beginWrite(chunk, new Object[] { backing }, "io-write");
                session.gate.endWrite(write, new RuntimeException("worker throwable")); // sets writeFailed
                check("writefailed: monotonic flag set by the private writer Throwable",
                        t.isWriteFailed(), null);
                boolean sealed = session.tickets.sealSuccess(t);
                check("writefailed: sealSuccess accepted but published FAILURE",
                        sealed && t.state() == TicketState.SEALED_FAILURE, String.valueOf(t.state()));
                return t;
            });
            AcquireOutcome outcome = session.ownerWrite("acquire",
                    () -> session.tickets.acquireCompletion(ticket));
            check("writefailed: acquired completion outcome is FAILURE, never success",
                    outcome == AcquireOutcome.FAILURE, String.valueOf(outcome));
        } finally {
            session.close();
        }
    }

    /** Sealing permanently revokes private-write permission; the same ticket cannot be re-sealed. */
    static void caseSealedTicketCannotRegainPermission() {
        Session session = new Session("ticket-sealed");
        try {
            FakeChunk chunk = new FakeChunk(109);
            byte[] backing = new byte[4];
            final Ticket ticket = LiveProtocolTestSupport.inWorker("creator", () -> {
                Ticket t = session.tickets.beginIo(session.provider, 8L, session.world, 6, 6);
                session.tickets.recordDiskRoot(t, new FakeNbtRoot(8),
                        PrivateBuildTickets.CURRENT_DATA_VERSION);
                session.tickets.registerNew(t, chunk, null);
                session.tickets.registerNew(t, backing, null);
                LiveWriterGate.WriteToken write =
                        session.gate.beginWrite(chunk, new Object[] { backing }, "io-private-build");
                backing[0] = 42;
                session.gate.endWrite(write, null);
                if (!session.tickets.sealSuccess(t)) throw new AssertionError("seal rejected");
                // Same thread, immediately after sealing: the TLS scope is gone, so the
                // formerly-private graph no longer admits this worker at all.
                try {
                    session.gate.beginWrite(chunk, new Object[] { backing }, "post-seal-write");
                    check("sealed: post-seal write admitted (MUST NOT HAPPEN)", false, "no exception");
                } catch (LiveWriterGate.ProtocolViolationException violation) {
                    check("sealed: post-seal write rejected before mutation (permission revoked by sealing)",
                            violation.reason()
                                    == LiveWriterGate.DisqualificationReason.OFF_OWNER_PUBLISHED_WRITE,
                            String.valueOf(violation.reason()));
                }
                return t;
            });
            check("sealed: sealed graph bytes unchanged by the rejected write",
                    backing[0] == 42, "backing[0]=" + backing[0]);
            boolean resealed = LiveProtocolTestSupport.inWorker("creator-again", () ->
                    session.tickets.sealSuccess(ticket));
            check("sealed: re-sealing the sealed ticket is rejected (one-way)",
                    !resealed, null);
        } finally {
            session.close();
        }
    }

    /** Tickets are bound to their issuing session; foreign tickets are refused everywhere. */
    static void caseTicketSessionMismatch() {
        Session sessionA = new Session("session-a");
        Session sessionB = new Session("session-b");
        try {
            FakeChunk chunkB = new FakeChunk(211);
            byte[] backingB = new byte[2];
            Ticket foreign = LiveProtocolTestSupport.workerBuildAndSeal(
                    sessionB, sessionB.world, sessionB.provider, 21L, 1, 2, chunkB, backingB, false);
            AcquireOutcome acquireOutcome = sessionA.ownerWrite("acquire",
                    () -> sessionA.tickets.acquireCompletion(foreign));
            check("session: foreign ticket acquire refused SESSION_MISMATCH",
                    acquireOutcome == AcquireOutcome.SESSION_MISMATCH, String.valueOf(acquireOutcome));
            sessionB.ownerWrite("acquire-b", () -> sessionB.tickets.acquireCompletion(foreign));
            LiveChunkBindings.Outcome admitted = sessionA.ownerWrite("admit",
                    () -> sessionA.bindings.admitIoTicket(foreign, sessionA.world, chunkB));
            check("session: foreign ticket admission refused SESSION_MISMATCH",
                    !admitted.ok() && admitted.reason() == LiveChunkBindings.FailureReason.SESSION_MISMATCH,
                    String.valueOf(admitted.reason()));
        } finally {
            sessionA.close();
            sessionB.close();
        }
    }

    /** Exact World and chunk identities and the incarnation rule are enforced at admission. */
    static void caseTicketIdentityMismatches() {
        Session session = new Session("ticket-identity");
        try {
            FakeWorld otherWorld = new FakeWorld(77);
            FakeChunk chunk = new FakeChunk(113);
            byte[] backing = new byte[2];
            Ticket ticket = LiveProtocolTestSupport.workerBuildAndSeal(
                    session, session.world, session.provider, 31L, 10, 10, chunk, backing, false);
            session.ownerWrite("acquire", () -> session.tickets.acquireCompletion(ticket));

            LiveChunkBindings.Outcome notAcquiredProbe = session.ownerWrite("probe",
                    () -> {
                        Ticket sealedOnly = LiveProtocolTestSupport.workerBuildAndSeal(
                                session, session.world, session.provider, 32L, 10, 11,
                                new FakeChunk(114), new byte[2], false);
                        return session.bindings.admitIoTicket(sealedOnly, session.world,
                                new FakeChunk(114));
                    });
            check("identity: unacquired sealed ticket refuses admission TICKET_NOT_ACQUIRED",
                    !notAcquiredProbe.ok()
                            && notAcquiredProbe.reason() == LiveChunkBindings.FailureReason.TICKET_NOT_ACQUIRED,
                    String.valueOf(notAcquiredProbe.reason()));

            LiveChunkBindings.Outcome worldMismatch = session.ownerWrite("admit-world",
                    () -> session.bindings.admitIoTicket(ticket, otherWorld, chunk));
            check("identity: wrong World object refuses admission WORLD_MISMATCH",
                    !worldMismatch.ok() && worldMismatch.reason() == LiveChunkBindings.FailureReason.WORLD_MISMATCH,
                    String.valueOf(worldMismatch.reason()));

            LiveChunkBindings.Outcome chunkMismatch = session.ownerWrite("admit-chunk",
                    () -> session.bindings.admitIoTicket(ticket, session.world, new FakeChunk(999)));
            check("identity: unregistered chunk object refuses admission CHUNK_IDENTITY_MISMATCH",
                    !chunkMismatch.ok()
                            && chunkMismatch.reason() == LiveChunkBindings.FailureReason.CHUNK_IDENTITY_MISMATCH,
                    String.valueOf(chunkMismatch.reason()));

            LiveChunkBindings.Outcome admitted = session.ownerWrite("admit",
                    () -> session.bindings.admitIoTicket(ticket, session.world, chunk));
            check("identity: exact identities admit to a PRIVATE binding",
                    admitted.ok()
                            && admitted.binding().state() == LiveChunkBindings.BindingState.PRIVATE,
                    String.valueOf(admitted.reason()));

            // A second consumed ticket for its own chunk object, admitted against the
            // FIRST chunk's live binding: the incarnation rule refuses it.
            FakeChunk otherChunk = new FakeChunk(115);
            Ticket second = LiveProtocolTestSupport.workerBuildAndSeal(
                    session, session.world, session.provider, 33L, 10, 12, otherChunk, new byte[2], false);
            session.ownerWrite("acquire-second", () -> session.tickets.acquireCompletion(second));
            LiveChunkBindings.Outcome incarnationMismatch = session.ownerWrite("admit-second",
                    () -> session.bindings.admitIoTicket(second, session.world, chunk));
            check("identity: admission over a live binding refuses INCARNATION_MISMATCH",
                    !incarnationMismatch.ok()
                            && incarnationMismatch.reason() == LiveChunkBindings.FailureReason.INCARNATION_MISMATCH,
                    String.valueOf(incarnationMismatch.reason()));
            check("identity: the live binding was untouched by the refused admission",
                    session.bindings.bindingFor(chunk).state() == LiveChunkBindings.BindingState.PRIVATE, null);
        } finally {
            session.close();
        }
    }

    /** Backing storage owned by anyone else taints the whole ticket graph permanently. */
    static void caseTicketBackingStorageMismatch() {
        Session session = new Session("ticket-backing");
        try {
            // Live-owned backing (adopted IO graph) must never be registered under a new ticket.
            FakeChunk publishedChunk = new FakeChunk(127);
            byte[] liveBacking = new byte[2];
            Ticket published = LiveProtocolTestSupport.workerBuildAndSeal(
                    session, session.world, session.provider, 41L, 12, 0, publishedChunk, liveBacking, false);
            LiveProtocolTestSupport.ownerPublishTicket(session, session.world, 12, 0, publishedChunk, published);

            FakeChunk taintedChunk = new FakeChunk(128);
            Ticket tainted = LiveProtocolTestSupport.inWorker("builder", () -> {
                Ticket t = session.tickets.beginIo(session.provider, 42L, session.world, 13, 0);
                session.tickets.recordDiskRoot(t, new FakeNbtRoot(42),
                        PrivateBuildTickets.CURRENT_DATA_VERSION);
                session.tickets.registerNew(t, taintedChunk, null);
                // Fresh wrapper registering LIVE owned backing: alias, sticky taint.
                session.tickets.registerNew(t, new Object(), liveBacking);
                session.tickets.sealSuccess(t);
                return t;
            });
            check("backing: aliased live backing tainted the ticket",
                    tainted.taintReason() == PrivateBuildTickets.TaintReason.ALIASED_BACKING,
                    String.valueOf(tainted.taintReason()));
            session.ownerWrite("acquire", () -> session.tickets.acquireCompletion(tainted));
            LiveChunkBindings.Outcome admitted = session.ownerWrite("admit",
                    () -> session.bindings.admitIoTicket(tainted, session.world, taintedChunk));
            check("backing: tainted graph refuses admission SOURCE_UNQUALIFIED",
                    !admitted.ok() && admitted.reason() == LiveChunkBindings.FailureReason.SOURCE_UNQUALIFIED,
                    String.valueOf(admitted.reason()));
            check("backing: live backing bytes unchanged by the failed admission flow",
                    liveBacking[0] == 42, "liveBacking[0]=" + liveBacking[0]);

            Ticket unknownBacking = LiveProtocolTestSupport.inWorker("builder2", () -> {
                Ticket t = session.tickets.beginIo(session.provider, 43L, session.world, 14, 0);
                session.tickets.recordDiskRoot(t, new FakeNbtRoot(43),
                        PrivateBuildTickets.CURRENT_DATA_VERSION);
                session.tickets.registerNew(t, new FakeChunk(129), null);
                session.tickets.registerNew(t, new Object(), new byte[4]); // never registered storage
                session.tickets.sealSuccess(t);
                return t;
            });
            check("backing: unregistered backing taints UNKNOWN_BACKING_PROVENANCE",
                    unknownBacking.taintReason()
                            == PrivateBuildTickets.TaintReason.UNKNOWN_BACKING_PROVENANCE,
                    String.valueOf(unknownBacking.taintReason()));
        } finally {
            session.close();
        }
    }

    /** Shared/pending NBT provenance is sticky: the load completes for Java but never becomes eligible. */
    static void casePendingNbtSourceSticky() {
        Session session = new Session("ticket-pending-nbt");
        try {
            FakeChunk chunk = new FakeChunk(131);
            byte[] backing = new byte[2];
            Ticket ticket = LiveProtocolTestSupport.inWorker("builder", () -> {
                Ticket t = session.tickets.beginIo(session.provider, 51L, session.world, 20, 1);
                session.tickets.recordSharedRoot(t, new FakeNbtRoot(51));
                session.tickets.registerNew(t, chunk, null);
                session.tickets.registerNew(t, backing, null);
                session.tickets.sealSuccess(t); // ordinary Java load continues normally
                return t;
            });
            check("pending-nbt: source recorded SHARED_PENDING_NBT",
                    ticket.sourceStatus() == PrivateBuildTickets.SourceStatus.SHARED_PENDING_NBT, null);
            session.ownerWrite("acquire", () -> session.tickets.acquireCompletion(ticket));
            LiveChunkBindings.Outcome admitted = session.ownerWrite("admit",
                    () -> session.bindings.admitIoTicket(ticket, session.world, chunk));
            check("pending-nbt: admission refuses SOURCE_UNQUALIFIED",
                    !admitted.ok() && admitted.reason() == LiveChunkBindings.FailureReason.SOURCE_UNQUALIFIED,
                    String.valueOf(admitted.reason()));
            check("pending-nbt: sticky-INELIGIBLE binding recorded, never READY",
                    session.bindings.bindingFor(chunk) != null
                            && session.bindings.bindingFor(chunk).state() == LiveChunkBindings.BindingState.INELIGIBLE
                            && session.bindings.bindingFor(chunk).sourceQualification()
                                    == LiveChunkBindings.SourceQualification.SHARED_PENDING_NBT,
                    null);
            LiveWriterGate.CaptureAttempt attempt = LiveProtocolTestSupport.ownerCapture(
                    session, session.world, chunk, false);
            check("pending-nbt: capture falls back NOT_READY for the ineligible incarnation",
                    !attempt.admitted() && attempt.fallbackReason() == LiveWriterGate.FallbackReason.NOT_READY,
                    String.valueOf(attempt.fallbackReason()));
        } finally {
            session.close();
        }
    }

    /** Only the current DataVersion qualifies fresh-disk provenance; anything else is sticky-ineligible. */
    static void caseOldDataVersionSticky() {
        Session session = new Session("ticket-oldversion");
        try {
            FakeChunk chunk = new FakeChunk(555);
            Ticket ticket = LiveProtocolTestSupport.inWorker("builder", () -> {
                Ticket t = session.tickets.beginIo(session.provider, 61L, session.world, 0, 2);
                boolean recorded = session.tickets.recordDiskRoot(t, new FakeNbtRoot(61), 1342);
                check("oldversion: non-current DataVersion rejected", !recorded, null);
                session.tickets.registerNew(t, chunk, null);
                session.tickets.registerNew(t, new byte[2], null);
                session.tickets.sealSuccess(t);
                return t;
            });
            check("oldversion: OLD_DATA_VERSION sticky on the ticket",
                    ticket.sourceStatus() == PrivateBuildTickets.SourceStatus.OLD_DATA_VERSION, null);
            session.ownerWrite("acquire", () -> session.tickets.acquireCompletion(ticket));
            LiveChunkBindings.Outcome admitted = session.ownerWrite("admit",
                    () -> session.bindings.admitIoTicket(ticket, session.world, chunk));
            check("oldversion: admission refuses SOURCE_UNQUALIFIED",
                    !admitted.ok() && admitted.reason() == LiveChunkBindings.FailureReason.SOURCE_UNQUALIFIED,
                    String.valueOf(admitted.reason()));
            check("oldversion: the old-data-version incarnation is recorded sticky-ineligible",
                    session.bindings.bindingFor(chunk) != null
                            && session.bindings.bindingFor(chunk).state() == LiveChunkBindings.BindingState.INELIGIBLE,
                    null);
        } finally {
            session.close();
        }
    }

    /** Contradictory provenance records taint the ticket. */
    static void caseContradictorySourceTaints() {
        Session session = new Session("ticket-contradiction");
        try {
            Ticket ticket = LiveProtocolTestSupport.inWorker("builder", () -> {
                Ticket t = session.tickets.beginIo(session.provider, 71L, session.world, 0, 3);
                session.tickets.recordSharedRoot(t, new FakeNbtRoot(71));
                boolean second = session.tickets.recordDiskRoot(t, new FakeNbtRoot(71),
                        PrivateBuildTickets.CURRENT_DATA_VERSION);
                check("contradiction: second provenance record rejected", !second, null);
                session.tickets.sealSuccess(t);
                return t;
            });
            check("contradiction: ticket permanently tainted",
                    ticket.taintReason() == PrivateBuildTickets.TaintReason.CONTRADICTORY_SOURCE,
                    String.valueOf(ticket.taintReason()));
            check("contradiction: no qualified source remains",
                    !ticket.isSourceQualified(), null);
        } finally {
            session.close();
        }
    }

    /** Existing registry identities are never overwritten as PRIVATE; relabeling attempts taint. */
    static void caseDoubleRegistrationTaints() {
        Session session = new Session("ticket-double-register");
        try {
            Ticket ticket = LiveProtocolTestSupport.inWorker("builder", () -> {
                Ticket t = session.tickets.beginIo(session.provider, 81L, session.world, 0, 4);
                session.tickets.recordDiskRoot(t, new FakeNbtRoot(81),
                        PrivateBuildTickets.CURRENT_DATA_VERSION);
                Object component = new Object();
                check("double-register: first registration accepted",
                        session.tickets.registerNew(t, component, null), null);
                check("double-register: second registration of the same identity rejected",
                        !session.tickets.registerNew(t, component, null), null);
                session.tickets.sealSuccess(t);
                return t;
            });
            check("double-register: relabeling attempt tainted the ticket",
                    ticket.taintReason() == PrivateBuildTickets.TaintReason.EXISTING_IDENTITY_RELABELED,
                    String.valueOf(ticket.taintReason()));
        } finally {
            session.close();
        }
    }

    /** A still-BUILDING private component reaching an owner attachment is a terminal escape. */
    static void casePrivateGraphEscapeThrows() {
        Session session = new Session("private-escape");
        try {
            final Object privateComponent = new Object();
            Ticket ticket = LiveProtocolTestSupport.inWorker("builder", () -> {
                Ticket t = session.tickets.beginIo(session.provider, 91L, session.world, 0, 5);
                session.tickets.registerNew(t, privateComponent, null);
                return t; // deliberately left BUILDING: the worker is mid-build
            });
            FakeChunk target = new FakeChunk(137);
            session.owner.run(() -> {
                LiveWriterGate.WriteToken token = session.gate.beginWrite(null, null, "publication");
                try {
                    LiveChunkBindings.Outcome begun = session.bindings.beginPublication(session.world, target);
                    if (!begun.ok()) throw new AssertionError("beginPublication failed: " + begun.reason());
                    try {
                        session.bindings.beforeAttach(target, target, privateComponent);
                        check("escape: owner attach of BUILDING private component admitted (MUST NOT HAPPEN)",
                                false, "no exception");
                    } catch (LiveWriterGate.ProtocolViolationException violation) {
                        check("escape: PRIVATE_GRAPH_ESCAPE thrown before the store",
                                violation.reason()
                                        == LiveWriterGate.DisqualificationReason.PRIVATE_GRAPH_ESCAPE,
                                String.valueOf(violation.reason()));
                    }
                    check("escape: session terminally disqualified",
                            session.gate.isTerminalDisqualified()
                                    && session.gate.disqualificationReason()
                                            == LiveWriterGate.DisqualificationReason.PRIVATE_GRAPH_ESCAPE,
                            String.valueOf(session.gate.disqualificationReason()));
                } finally {
                    session.gate.endWrite(token, null);
                }
                return null;
            });
            check("escape: worker ticket untouched and still BUILDING",
                    ticket.state() == TicketState.BUILDING, String.valueOf(ticket.state()));
        } finally {
            session.close();
        }
    }

    /** A private worker touching LIVE backing through a fresh wrapper fails before any byte write. */
    static void caseLiveBackingThroughFreshWrapperFailsBeforeWrite() {
        Session session = new Session("live-backing");
        try {
            FakeChunk publishedChunk = new FakeChunk(139);
            final byte[] liveBacking = new byte[4];
            Ticket published = LiveProtocolTestSupport.workerBuildAndSeal(
                    session, session.world, session.provider, 101L, 30, 1, publishedChunk, liveBacking, false);
            LiveProtocolTestSupport.ownerPublishTicket(session, session.world, 30, 1, publishedChunk, published);
            LiveChunkBindings.Binding binding = session.bindings.bindingFor(publishedChunk);
            check("live-backing: setup published the chunk READY",
                    binding != null && binding.state() == LiveChunkBindings.BindingState.READY, null);
            LiveProtocolTestSupport.inWorker("alias-worker", () -> {
                Ticket t = session.tickets.beginIo(session.provider, 102L, session.world, 31, 1);
                try {
                    // Fresh worker context, fresh (unregistered) receiver, LIVE owned backing target.
                    session.gate.beginWrite(new Object(), new Object[] { liveBacking }, "alias-write");
                    check("live-backing: alias write admitted (MUST NOT HAPPEN)", false, "no exception");
                } catch (LiveWriterGate.ProtocolViolationException violation) {
                    check("live-backing: worker alias write rejected PRIVATE_ALIAS_WRITE before mutation",
                            violation.reason()
                                    == LiveWriterGate.DisqualificationReason.PRIVATE_ALIAS_WRITE,
                            String.valueOf(violation.reason()));
                } finally {
                    session.tickets.sealFailure(t);
                }
                return null;
            });
            check("live-backing: LIVE backing bytes unchanged (42 from the private build only)",
                    liveBacking[0] == 42 && liveBacking[1] == 0 && liveBacking[2] == 0 && liveBacking[3] == 0,
                    "liveBacking=" + liveBacking[0] + "," + liveBacking[1] + "," + liveBacking[2]);
        } finally {
            session.close();
        }
    }
}
