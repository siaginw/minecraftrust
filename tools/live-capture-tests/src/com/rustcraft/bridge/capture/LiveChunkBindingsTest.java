package com.rustcraft.bridge.capture;

import com.rustcraft.bridge.capture.LiveProtocolTestSupport.FakeChunk;
import com.rustcraft.bridge.capture.LiveProtocolTestSupport.FakeNbtRoot;
import com.rustcraft.bridge.capture.LiveProtocolTestSupport.FakeWorld;
import com.rustcraft.bridge.capture.LiveProtocolTestSupport.Session;
import com.rustcraft.bridge.capture.PrivateBuildTickets.Ticket;
import com.rustcraft.bridge.capture.LiveChunkBindings.Binding;
import com.rustcraft.bridge.capture.LiveChunkBindings.BindingIdentity;
import com.rustcraft.bridge.capture.LiveChunkBindings.BindingState;
import com.rustcraft.bridge.capture.LiveChunkBindings.FailureReason;
import com.rustcraft.bridge.capture.LiveChunkBindings.Outcome;

/**
 * Deterministic foundation tests for {@link LiveChunkBindings}: the exact
 * identity registry, publication transactions, READY finalization, retirement
 * and incarnation rules, attachment validation, and resource bounds. All
 * scheduling is deterministic; no sleeps.
 */
public final class LiveChunkBindingsTest {

    static int pass = 0, fail = 0;

    public static void main(String[] args) {
        try {
            caseOwnerGenerationLifecycleToReady();
            caseOuterPublicationSuccessRequiredBeforeReady();
            caseNestedPublicationSingleOuterTransaction();
            caseDuplicatePublicationRejected();
            caseRecordMapPutValidation();
            caseSameCoordinateReplacementRejected();
            caseRecordMapRemoveExactIdentity();
            caseUnloadRetiresBinding();
            caseSameObjectNewIncarnationNewLifecycle();
            caseForeignAliasRevokesBothBindings();
            caseUnknownValueRevokesReceivingBinding();
            caseSealedNotAdoptedRejected();
            caseOwnerPendingTombstonesAtScopeExit();
            caseBindingResourceLimit();
            caseCaptureFallbackReasons();
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

    /** Owner generation: provisional PUBLISHING binding, owner-pending attachment, exact put, READY. */
    static void caseOwnerGenerationLifecycleToReady() {
        Session session = new Session("bind-lifecycle");
        try {
            FakeWorld world = session.world;
            FakeChunk chunk = new FakeChunk(201);
            final byte[] freshBacking = new byte[2];
            final Object freshPlane = new Object();
            session.owner.run(() -> {
                LiveWriterGate.WriteToken token = session.gate.beginWrite(null, null, "generate");
                try {
                    Outcome begun = session.bindings.beginPublication(world, chunk);
                    check("lifecycle: untracked chunk gets a provisional PUBLISHING binding",
                            begun.ok() && begun.binding().state() == BindingState.PUBLISHING,
                            String.valueOf(begun.reason()));
                    Binding binding = begun.binding();
                    check("lifecycle: owner-generated source qualification",
                            binding.sourceQualification() == LiveChunkBindings.SourceQualification.OWNER_GENERATED,
                            String.valueOf(binding.sourceQualification()));
                    Outcome pending = session.bindings.registerOwnerPending(freshPlane, freshBacking);
                    check("lifecycle: fresh owner allocation registered OWNER_PENDING",
                            pending.ok(), String.valueOf(pending.reason()));
                    Outcome attached = session.bindings.beforeAttach(chunk, chunk, freshPlane);
                    check("lifecycle: transaction-pending value attached and bound to the binding",
                            attached.ok(), String.valueOf(attached.reason()));
                    Outcome put = session.bindings.recordMapPut(world, 40, 50, chunk);
                    check("lifecycle: exact map put accepted", put.ok(), String.valueOf(put.reason()));
                    Outcome finished = session.bindings.finishPublication(begun.publication(), null);
                    check("lifecycle: outermost normal completion finalizes READY",
                            finished.ok() && binding.state() == BindingState.READY,
                            String.valueOf(binding.state()));
                    BindingIdentity identity = binding.identityRecord();
                    check("lifecycle: identity binds session/world/chunk/incarnation/generation",
                            identity.sessionId == session.gate.sessionId() && identity.worldId > 0
                                    && identity.chunkId > 0 && identity.incarnation > 0
                                    && identity.ownedEncodeGeneration > 0 && !binding.isRevoked(),
                            identity.toString());
                } finally {
                    session.gate.endWrite(token, null);
                }
                return null;
            });
            LiveWriterGate.CaptureAttempt attempt = LiveProtocolTestSupport.ownerCapture(
                    session, world, chunk, true);
            check("lifecycle: READY unrevoked binding admits and commits capture",
                    attempt.admitted() && attempt.outcome() == LiveWriterGate.CaptureOutcome.SEALED_COMMITTED,
                    String.valueOf(attempt.outcome()));
        } finally {
            session.close();
        }
    }

    /** Failure paths: a failed finish and a missing finish both keep the binding away from READY. */
    static void caseOuterPublicationSuccessRequiredBeforeReady() {
        Session session = new Session("bind-failure");
        try {
            FakeWorld world = session.world;
            FakeChunk failed = new FakeChunk(203);
            session.ownerWrite("publish-failed", () -> {
                Outcome begun = session.bindings.beginPublication(world, failed);
                Outcome put = session.bindings.recordMapPut(world, 1, 1, failed);
                Outcome finished = session.bindings.finishPublication(begun.publication(),
                        new RuntimeException("population failure"));
                check("failure: failed publication reports PUBLICATION_FAILED",
                        !finished.ok() && finished.reason() == FailureReason.PUBLICATION_FAILED,
                        String.valueOf(finished.reason()));
                check("failure: binding goes INELIGIBLE, never READY",
                        begun.binding().state() == BindingState.INELIGIBLE
                                && begun.binding().revocationToken().isRevoked(),
                        String.valueOf(begun.binding().state()));
                return null;
            });
            LiveWriterGate.CaptureAttempt rejected = LiveProtocolTestSupport.ownerCapture(
                    session, world, failed, false);
            check("failure: capture falls back NOT_READY for the failed incarnation",
                    !rejected.admitted() && rejected.fallbackReason() == LiveWriterGate.FallbackReason.NOT_READY,
                    String.valueOf(rejected.fallbackReason()));

            FakeChunk neverFinished = new FakeChunk(204);
            session.ownerWrite("publish-unfinished", () -> {
                Outcome begun = session.bindings.beginPublication(world, neverFinished);
                check("unfinished: binding stays PUBLISHING without completion",
                        begun.ok() && begun.binding().state() == BindingState.PUBLISHING, null);
                return null; // the transaction is never finished: a hook-coverage gap
            });
            check("unfinished: the leaked transaction keeps the binding PUBLISHING",
                    session.bindings.bindingFor(neverFinished) != null
                            && session.bindings.bindingFor(neverFinished).state() == BindingState.PUBLISHING,
                    null);
            LiveWriterGate.CaptureAttempt stillPending = LiveProtocolTestSupport.ownerCapture(
                    session, world, neverFinished, false);
            check("unfinished: capture falls back PUBLICATION_ACTIVE while a transaction leaks",
                    !stillPending.admitted()
                            && stillPending.fallbackReason() == LiveWriterGate.FallbackReason.PUBLICATION_ACTIVE,
                    String.valueOf(stillPending.fallbackReason()));
        } finally {
            session.close();
        }
    }

    /** Cross-chunk nesting forms one outer transaction: no intermediate READY publication. */
    static void caseNestedPublicationSingleOuterTransaction() {
        Session session = new Session("bind-nested");
        try {
            FakeWorld world = session.world;
            FakeChunk outer = new FakeChunk(205);
            FakeChunk inner = new FakeChunk(206);
            session.ownerWrite("nested", () -> {
                Outcome begunOuter = session.bindings.beginPublication(world, outer);
                Outcome begunInner = session.bindings.beginPublication(world, inner);
                check("nested: inner transaction opened inside the outer one", begunInner.ok(), null);
                check("nested: both bindings PUBLISHING while nested",
                        begunOuter.binding().state() == BindingState.PUBLISHING
                                && begunInner.binding().state() == BindingState.PUBLISHING, null);
                session.bindings.recordMapPut(world, 2, 0, inner);
                Outcome finishedInner = session.bindings.finishPublication(begunInner.publication(), null);
                check("nested: inner finish accepted but NOT finalized READY inside the outer transaction",
                        finishedInner.ok() && begunInner.binding().state() == BindingState.PUBLISHING,
                        String.valueOf(begunInner.binding().state()));
                session.bindings.recordMapPut(world, 2, 1, outer);
                Outcome finishedOuter = session.bindings.finishPublication(begunOuter.publication(), null);
                check("nested: outermost normal completion finalizes both bindings READY",
                        finishedOuter.ok()
                                && begunOuter.binding().state() == BindingState.READY
                                && begunInner.binding().state() == BindingState.READY,
                        begunOuter.binding().state() + "/" + begunInner.binding().state());
                return null;
            });
        } finally {
            session.close();
        }
    }

    /** A READY binding is never re-published. */
    static void caseDuplicatePublicationRejected() {
        Session session = new Session("bind-duplicate");
        try {
            FakeWorld world = session.world;
            FakeChunk chunk = new FakeChunk(207);
            LiveWriterGateTest.ownerGenerateAndPublish(session, world, chunk, 3, 3);
            Outcome duplicate = session.ownerWrite("duplicate",
                    () -> session.bindings.beginPublication(world, chunk));
            check("duplicate: publishing a READY binding rejected DUPLICATE_PUBLICATION",
                    !duplicate.ok() && duplicate.reason() == FailureReason.DUPLICATE_PUBLICATION,
                    String.valueOf(duplicate.reason()));
            check("duplicate: binding still READY (no state damage)",
                    session.bindings.bindingFor(chunk).state() == BindingState.READY, null);
        } finally {
            session.close();
        }
    }

    /** recordMapPut requires the exact binding in PUBLISHING state for the exact World. */
    static void caseRecordMapPutValidation() {
        Session session = new Session("bind-put");
        try {
            FakeWorld world = session.world;
            FakeWorld otherWorld = new FakeWorld(88);
            FakeChunk unknown = new FakeChunk(208);
            Outcome unbound = session.ownerWrite("put-unbound",
                    () -> session.bindings.recordMapPut(world, 5, 5, unknown));
            check("put: object without binding rejected NO_BINDING",
                    !unbound.ok() && unbound.reason() == FailureReason.NO_BINDING,
                    String.valueOf(unbound.reason()));

            FakeChunk chunk = new FakeChunk(209);
            session.ownerWrite("begin", () -> session.bindings.beginPublication(world, chunk));
            Outcome worldMismatch = session.ownerWrite("put-world",
                    () -> session.bindings.recordMapPut(otherWorld, 5, 5, chunk));
            check("put: wrong World object rejected WORLD_MISMATCH",
                    !worldMismatch.ok() && worldMismatch.reason() == FailureReason.WORLD_MISMATCH,
                    String.valueOf(worldMismatch.reason()));
        } finally {
            session.close();
        }
    }

    /** Same-coordinate replacement must retire the old binding first; eligibility is never inherited. */
    static void caseSameCoordinateReplacementRejected() {
        Session session = new Session("bind-replacement");
        try {
            FakeWorld world = session.world;
            FakeChunk oldChunk = new FakeChunk(210);
            LiveWriterGateTest.ownerGenerateAndPublish(session, world, oldChunk, 60, 70);
            Binding oldBinding = session.bindings.bindingFor(oldChunk);
            FakeChunk replacement = new FakeChunk(211);

            Outcome occupied = session.ownerWrite("put-over-live", () -> {
                Outcome begun = session.bindings.beginPublication(world, replacement);
                if (!begun.ok()) throw new AssertionError("beginPublication failed: " + begun.reason());
                return session.bindings.recordMapPut(world, 60, 70, replacement);
            });
            check("replacement: put over a live coordinate binding rejected COORDINATE_OCCUPIED",
                    !occupied.ok() && occupied.reason() == FailureReason.COORDINATE_OCCUPIED,
                    String.valueOf(occupied.reason()));
            check("replacement: old binding still holds the coordinates (never silently replaced)",
                    session.bindings.bindingFor(oldChunk) == oldBinding
                            && oldBinding.state() == BindingState.READY, null);

            Outcome afterRetire = session.ownerWrite("put-after-retire", () -> {
                Outcome retired = session.bindings.retire(world, oldChunk);
                if (!retired.ok()) throw new AssertionError("retire failed: " + retired.reason());
                return session.bindings.recordMapPut(world, 60, 70, replacement);
            });
            check("replacement: after retiring the old binding the put is accepted",
                    afterRetire.ok(), String.valueOf(afterRetire.reason()));
        } finally {
            session.close();
        }
    }

    /** Map removal only ever removes the exact old binding's entry, never a later replacement's. */
    static void caseRecordMapRemoveExactIdentity() {
        Session session = new Session("bind-remove");
        try {
            FakeWorld world = session.world;
            FakeChunk oldChunk = new FakeChunk(212);
            LiveWriterGateTest.ownerGenerateAndPublish(session, world, oldChunk, 80, 90);
            FakeChunk replacement = new FakeChunk(213);
            session.ownerWrite("replace", () -> {
                session.bindings.retire(world, oldChunk);
                Outcome begun = session.bindings.beginPublication(world, replacement);
                session.bindings.recordMapPut(world, 80, 90, replacement);
                session.bindings.finishPublication(begun.publication(), null);
                return null;
            });
            Outcome wrongRemoval = session.ownerWrite("remove-wrong",
                    () -> session.bindings.recordMapRemove(world, 80, 90, oldChunk));
            check("remove: retired predecessor's removal is a no-op that never touches the replacement",
                    wrongRemoval.ok(), String.valueOf(wrongRemoval.reason()));
            check("remove: the replacement still holds the coordinates",
                    session.bindings.bindingFor(replacement) != null
                            && session.bindings.bindingFor(replacement).state() == BindingState.READY, null);
            Outcome foreignCoords = session.ownerWrite("remove-foreign",
                    () -> session.bindings.recordMapRemove(world, 99, 99, replacement));
            check("remove: a live chunk removing coordinates it does not hold rejected REMOVAL_IDENTITY_MISMATCH",
                    !foreignCoords.ok() && foreignCoords.reason() == FailureReason.REMOVAL_IDENTITY_MISMATCH,
                    String.valueOf(foreignCoords.reason()));
            Outcome exactRemoval = session.ownerWrite("remove-exact",
                    () -> session.bindings.recordMapRemove(world, 80, 90, replacement));
            check("remove: the exact current binding removes its own coordinate entry",
                    exactRemoval.ok(), String.valueOf(exactRemoval.reason()));
        } finally {
            session.close();
        }
    }

    /** Unload retires the binding: token revoked, maps cleared, capture refused, retirement irreversible. */
    static void caseUnloadRetiresBinding() {
        Session session = new Session("bind-unload");
        try {
            FakeWorld world = session.world;
            FakeChunk chunk = new FakeChunk(214);
            LiveWriterGateTest.ownerGenerateAndPublish(session, world, chunk, 7, 8);
            Binding binding = session.bindings.bindingFor(chunk);
            BindingIdentity identityBefore = binding.identityRecord();
            Outcome retired = session.ownerWrite("unload", () -> session.bindings.retire(world, chunk));
            check("unload: retire accepted",
                    retired.ok() && binding.state() == BindingState.RETIRED && binding.isRevoked(),
                    binding.state() + "/revoked=" + binding.isRevoked());
            check("unload: chunk object no longer bound",
                    session.bindings.bindingFor(chunk) == null, null);
            LiveWriterGate.CaptureAttempt attempt = LiveProtocolTestSupport.ownerCapture(
                    session, world, chunk, false);
            check("unload: capture falls back NO_BINDING after retirement",
                    !attempt.admitted() && attempt.fallbackReason() == LiveWriterGate.FallbackReason.NO_BINDING,
                    String.valueOf(attempt.fallbackReason()));
            Outcome again = session.ownerWrite("unload-again", () -> session.bindings.retire(world, chunk));
            check("unload: second retire rejected NO_BINDING (irreversible, no resurrection)",
                    !again.ok() && again.reason() == FailureReason.NO_BINDING, String.valueOf(again.reason()));
            check("unload: identity record unchanged and token stays revoked",
                    binding.identityRecord().incarnation == identityBefore.incarnation
                            && binding.revocationToken().isRevoked(), null);
        } finally {
            session.close();
        }
    }

    /** Same-object reuse gets a fresh incarnation/generation and never inherits old eligibility. */
    static void caseSameObjectNewIncarnationNewLifecycle() {
        Session session = new Session("bind-reincarnate");
        try {
            FakeWorld world = session.world;
            FakeChunk chunk = new FakeChunk(215);
            final byte[] backing = new byte[2];
            // First lifecycle: IO build/adopt/publish.
            PrivateBuildTickets.Ticket first = LiveProtocolTestSupport.workerBuildAndSeal(
                    session, world, session.provider, 301L, 9, 9, chunk, backing, false);
            Binding firstBinding = LiveProtocolTestSupport.ownerPublishTicket(
                    session, world, 9, 9, chunk, first);
            final BindingIdentity firstIdentity = firstBinding.identityRecord();
            check("reincarnate: first lifecycle READY",
                    firstBinding.state() == BindingState.READY, null);

            session.ownerWrite("unload", () -> session.bindings.retire(world, chunk));

            // Dormant rebind of the SAME object: fresh publication transaction, fresh lifecycle.
            Binding secondBinding = session.ownerWrite("dormant-rebind", () -> {
                Outcome begun = session.bindings.beginPublication(world, chunk);
                if (!begun.ok()) throw new AssertionError("beginPublication failed: " + begun.reason());
                Outcome reattached = session.bindings.beforeAttach(chunk, chunk, backing);
                check("reincarnate: qualified dormant rebind re-adopts the same graph's component",
                        reattached.ok(), String.valueOf(reattached.reason()));
                session.bindings.recordMapPut(world, 9, 9, chunk);
                session.bindings.finishPublication(begun.publication(), null);
                return begun.binding();
            });
            BindingIdentity secondIdentity = secondBinding.identityRecord();
            check("reincarnate: same object, new incarnation and generation",
                    secondBinding.state() == BindingState.READY
                            && secondIdentity.incarnation > firstIdentity.incarnation
                            && secondIdentity.ownedEncodeGeneration > firstIdentity.ownedEncodeGeneration
                            && secondIdentity.chunkId != firstIdentity.chunkId,
                    "inc " + firstIdentity.incarnation + "->" + secondIdentity.incarnation);
            check("reincarnate: old binding stays RETIRED with its revoked token",
                    firstBinding.state() == BindingState.RETIRED && firstBinding.isRevoked(), null);
            check("reincarnate: registry maps the object to the NEW binding only",
                    session.bindings.bindingFor(chunk) == secondBinding, null);
            LiveWriterGate.CaptureAttempt attempt = LiveProtocolTestSupport.ownerCapture(
                    session, world, chunk, true);
            check("reincarnate: capture uses the new lifecycle and commits",
                    attempt.admitted() && attempt.outcome() == LiveWriterGate.CaptureOutcome.SEALED_COMMITTED,
                    String.valueOf(attempt.outcome()));
        } finally {
            session.close();
        }
    }

    /** A foreign alias revokes both known bindings before the original Java store. */
    static void caseForeignAliasRevokesBothBindings() {
        Session session = new Session("bind-alias");
        try {
            FakeWorld world = session.world;
            FakeChunk chunkA = new FakeChunk(216);
            final Object ownedPlaneA = new Object();
            session.owner.run(() -> {
                LiveWriterGate.WriteToken token = session.gate.beginWrite(null, null, "generate-a");
                try {
                    Outcome begun = session.bindings.beginPublication(world, chunkA);
                    session.bindings.registerOwnerPending(ownedPlaneA, null);
                    session.bindings.beforeAttach(chunkA, chunkA, ownedPlaneA);
                    session.bindings.recordMapPut(world, 1, 20, chunkA);
                    session.bindings.finishPublication(begun.publication(), null);
                } finally {
                    session.gate.endWrite(token, null);
                }
                return null;
            });
            Binding bindingA = session.bindings.bindingFor(chunkA);
            check("alias: setup published chunk A READY with its owned component",
                    bindingA.state() == BindingState.READY, null);

            FakeChunk chunkB = new FakeChunk(217);
            session.ownerWrite("publish-b", () -> {
                Outcome begun = session.bindings.beginPublication(world, chunkB);
                Outcome attached = session.bindings.beforeAttach(chunkB, chunkB, ownedPlaneA);
                check("alias: foreign alias rejected FOREIGN_ALIAS",
                        !attached.ok() && attached.reason() == FailureReason.FOREIGN_ALIAS,
                        String.valueOf(attached.reason()));
                session.bindings.finishPublication(begun.publication(),
                        new RuntimeException("rollback after rejected attach"));
                return null;
            });
            check("alias: both known bindings revoked and INELIGIBLE",
                    bindingA.state() == BindingState.INELIGIBLE && bindingA.isRevoked()
                            && session.bindings.bindingFor(chunkB).isRevoked(),
                    "A=" + bindingA.state());
        } finally {
            session.close();
        }
    }

    /** An attachment value of unknown provenance never becomes owned; the receiving binding is revoked. */
    static void caseUnknownValueRevokesReceivingBinding() {
        Session session = new Session("bind-unknown-value");
        try {
            FakeWorld world = session.world;
            FakeChunk chunk = new FakeChunk(218);
            session.ownerWrite("publish", () -> {
                Outcome begun = session.bindings.beginPublication(world, chunk);
                Outcome attached = session.bindings.beforeAttach(chunk, chunk, new Object());
                check("unknown-value: unregistered value rejected UNKNOWN_VALUE",
                        !attached.ok() && attached.reason() == FailureReason.UNKNOWN_VALUE,
                        String.valueOf(attached.reason()));
                check("unknown-value: receiving binding revoked before the Java store",
                        begun.binding().state() == BindingState.INELIGIBLE
                                && begun.binding().isRevoked(), null);
                session.bindings.finishPublication(begun.publication(), null);
                check("unknown-value: the revoked binding never reaches READY",
                        begun.binding().state() == BindingState.INELIGIBLE,
                        String.valueOf(begun.binding().state()));
                return null;
            });
        } finally {
            session.close();
        }
    }

    /** SEALED_IO components attach only through explicit publication adoption. */
    static void caseSealedNotAdoptedRejected() {
        Session session = new Session("bind-sealed-direct");
        try {
            FakeWorld world = session.world;
            final Object sealedComponent = new Object();
            PrivateBuildTickets.Ticket ticket = LiveProtocolTestSupport.inWorker("builder", () -> {
                Ticket t = session.tickets.beginIo(session.provider, 401L, world, 3, 21);
                session.tickets.recordDiskRoot(t, new FakeNbtRoot(401),
                        PrivateBuildTickets.CURRENT_DATA_VERSION);
                session.tickets.registerNew(t, sealedComponent, null);
                session.tickets.sealSuccess(t);
                return t;
            });
            check("sealed-direct: component is effectively SEALED_IO under the sealed ticket",
                    session.tickets.componentRecordFor(sealedComponent).isEffectivelySealedIo(), null);
            FakeChunk chunk = new FakeChunk(219);
            session.ownerWrite("publish", () -> {
                Outcome begun = session.bindings.beginPublication(world, chunk);
                Outcome attached = session.bindings.beforeAttach(chunk, chunk, sealedComponent);
                check("sealed-direct: direct attach of a sealed-but-unadopted component rejected",
                        !attached.ok() && attached.reason() == FailureReason.SEALED_NOT_ADOPTED,
                        String.valueOf(attached.reason()));
                check("sealed-direct: receiving binding revoked",
                        begun.binding().state() == BindingState.INELIGIBLE, null);
                session.bindings.finishPublication(begun.publication(), null);
                return null;
            });
            check("sealed-direct: the ticket itself remains unconsumed for the real adoption flow",
                    !ticket.isConsumed(), null);
        } finally {
            session.close();
        }
    }

    /** Unattached OWNER_PENDING allocations become non-private tombstones; they are never reusable. */
    static void caseOwnerPendingTombstonesAtScopeExit() {
        Session session = new Session("bind-pending-sweep");
        try {
            FakeWorld world = session.world;
            FakeChunk chunk = new FakeChunk(220);
            final Object orphan = new Object();
            session.ownerWrite("leak-pending", () -> {
                Outcome begun = session.bindings.beginPublication(world, chunk);
                session.bindings.registerOwnerPending(orphan, null); // never attached
                session.bindings.finishPublication(begun.publication(), null);
                return null;
            });
            Outcome reRegister = session.ownerWrite("reuse-orphan",
                    () -> session.bindings.registerOwnerPending(orphan, null));
            check("sweep: re-registering a tombstoned orphan rejected REGISTRATION_REJECTED",
                    !reRegister.ok() && reRegister.reason() == FailureReason.REGISTRATION_REJECTED,
                    String.valueOf(reRegister.reason()));
            FakeChunk other = new FakeChunk(221);
            session.ownerWrite("attach-orphan", () -> {
                Outcome begun = session.bindings.beginPublication(world, other);
                Outcome attached = session.bindings.beforeAttach(other, other, orphan);
                check("sweep: tombstoned orphan attach rejected SHARED_COMPONENT and revokes the receiver",
                        !attached.ok() && attached.reason() == FailureReason.SHARED_COMPONENT
                                && begun.binding().state() == BindingState.INELIGIBLE,
                        String.valueOf(attached.reason()));
                session.bindings.finishPublication(begun.publication(), null);
                return null;
            });
        } finally {
            session.close();
        }
    }

    /** Strong retention bound: the 4096th binding is refused and the session disqualifies RESOURCE_LIMIT. */
    static void caseBindingResourceLimit() {
        Session session = new Session("bind-limit");
        try {
            FakeWorld world = session.world;
            final int admitted = session.ownerWrite("fill", () -> {
                LiveWriterGate.WriteToken token = session.gate.beginWrite(null, null, "fill");
                int count = 0;
                try {
                    for (int i = 0; i < LiveChunkBindings.MAX_BINDINGS; i++) {
                        Outcome begun = session.bindings.beginPublication(world, new FakeChunk(1000 + i));
                        if (!begun.ok()) break;
                        Outcome finished = session.bindings.finishPublication(begun.publication(), null);
                        if (!finished.ok()) break;
                        count++;
                    }
                } finally {
                    session.gate.endWrite(token, null);
                }
                return count;
            });
            check("limit: exactly the retention bound was admitted",
                    admitted == LiveChunkBindings.MAX_BINDINGS, "admitted=" + admitted);
            Outcome overflow = session.ownerWrite("overflow",
                    () -> session.bindings.beginPublication(world, new FakeChunk(999_999)));
            check("limit: the next binding refuses with RESOURCE_LIMIT",
                    !overflow.ok() && overflow.reason() == FailureReason.RESOURCE_LIMIT,
                    String.valueOf(overflow.reason()));
            check("limit: resource exhaustion disqualifies the session",
                    session.gate.isTerminalDisqualified()
                            && session.gate.disqualificationReason()
                                    == LiveWriterGate.DisqualificationReason.RESOURCE_LIMIT,
                    String.valueOf(session.gate.disqualificationReason()));
        } finally {
            session.close();
        }
    }

    /** Exact capture fallback vocabulary for the lifecycle rejections. */
    static void caseCaptureFallbackReasons() {
        Session session = new Session("bind-capture-reasons");
        try {
            FakeWorld world = session.world;
            FakeWorld otherWorld = new FakeWorld(99);
            FakeChunk adoptedOnly = new FakeChunk(222);
            PrivateBuildTickets.Ticket ticket = LiveProtocolTestSupport.workerBuildAndSeal(
                    session, world, session.provider, 501L, 2, 22, adoptedOnly, new byte[2], false);
            session.ownerWrite("adopt-only", () -> {
                session.tickets.acquireCompletion(ticket);
                Outcome admitted = session.bindings.admitIoTicket(ticket, world, adoptedOnly);
                if (!admitted.ok()) throw new AssertionError("admit failed: " + admitted.reason());
                return null;
            });
            LiveWriterGate.CaptureAttempt notReady = LiveProtocolTestSupport.ownerCapture(
                    session, world, adoptedOnly, false);
            check("reasons: PRIVATE (not yet published) binding -> NOT_READY",
                    !notReady.admitted() && notReady.fallbackReason() == LiveWriterGate.FallbackReason.NOT_READY,
                    String.valueOf(notReady.fallbackReason()));

            FakeChunk ready = new FakeChunk(223);
            LiveWriterGateTest.ownerGenerateAndPublish(session, world, ready, 4, 23);
            LiveWriterGate.CaptureAttempt providerChanged = LiveProtocolTestSupport.ownerCapture(
                    session, otherWorld, ready, false);
            check("reasons: different World object -> PROVIDER_IDENTITY_CHANGED (identity is never coordinates)",
                    !providerChanged.admitted()
                            && providerChanged.fallbackReason()
                                    == LiveWriterGate.FallbackReason.PROVIDER_IDENTITY_CHANGED,
                    String.valueOf(providerChanged.fallbackReason()));
        } finally {
            session.close();
        }
    }
}
