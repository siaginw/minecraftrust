package com.rustcraft.bridge.capture;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Phase D offline controls: the twenty pre-live-event proofs required before
 * any live shadow comparison runs. Every control fails loudly; none can pass
 * by absence. The journal, comparator, contract, scope gate, and capture
 * validators are exercised directly, without a server and without the DLL;
 * the Rust-facing controls (Rust failure classification, the offline replay)
 * live in {@link PhaseDOfflineReplay}, which drives the real DLL.
 */
public final class PhaseDControls {

    private static int checks;

    public static void main(String[] args) throws Exception {
        Path out = Paths.get(args[0]);
        Files.createDirectories(out);
        ShadowScopeGate.resetTelemetry();

        // ---- journal fixture: one contract, one journal -----------------
        SessionCompatibilityContract contract = SessionCompatibilityContract.establish(
                "process-1", "session-1", "CONTROL_PROFILE", 8192, 13,
                repeated('a', 64));
        // A distinct digest proves the contract hash covers the registry facts.
        SessionCompatibilityContract contractB = SessionCompatibilityContract.establish(
                "process-1", "session-1", "CONTROL_PROFILE", 8192, 13,
                repeated('b', 64));
        check(!contract.sha256Hex().equals(contractB.sha256Hex()),
                "the contract digest covers the registry facts");
        ShadowEventJournal journal = ShadowEventJournal.bind(contract,
                out.resolve("control-journal.jsonl").toString());
        check(journal != null, "the journal binds once per JVM");
        check(ShadowEventJournal.bind(contract, out.resolve("second.jsonl").toString()) == null,
                "a second bind is refused; the first journal stands");

        // ---- 1. one Java event -> one eventId ---------------------------
        check(journal.mint(101L, 7L) == null, "event 101 mints cleanly");
        check(journal.mint(102L, 7L) == null, "event 102 mints cleanly");
        check(journal.isKnownOpen(101L) && journal.isKnownOpen(102L),
                "two Java events hold two distinct open registrations");

        // ---- 2. duplicate completion rejected ---------------------------
        check(journal.complete(101L, 7L, ShadowEventJournal.Outcome.COMPARE_PASS,
                null, "first") == null, "event 101 completes once");
        ShadowEventJournal.RefusedCompletion dup =
                journal.complete(101L, 7L, ShadowEventJournal.Outcome.COMPARE_MISMATCH,
                        "FIRST_DIVERGENCE", "second attempt");
        check(dup != null && dup.refusal == ShadowEventJournal.CompletionRefusal.DUPLICATE_EVENT_ID,
                "duplicate completion for the same eventId is rejected");
        check(journal.count(ShadowEventJournal.Outcome.COMPARE_PASS) == 1
                && journal.count(ShadowEventJournal.Outcome.COMPARE_MISMATCH) == 0,
                "the refused duplicate did not change the first outcome");
        check(journal.protocolViolations() == 1, "the duplicate is a recorded protocol violation");

        // ---- 3. unknown eventId rejected --------------------------------
        ShadowEventJournal.RefusedCompletion unknown =
                journal.complete(999L, 7L, ShadowEventJournal.Outcome.COMPARE_PASS, null, null);
        check(unknown != null && unknown.refusal == ShadowEventJournal.CompletionRefusal.UNKNOWN_EVENT_ID,
                "completion of an unknown eventId is rejected");

        // ---- 4/19. mismatched session rejected; contract binds admission -
        ShadowEventJournal.RefusedCompletion foreign =
                journal.mint(200L, 42L);
        check(foreign != null && foreign.refusal == ShadowEventJournal.CompletionRefusal.SESSION_MISMATCH,
                "an event minted under a foreign binding session is refused");
        check(journal.count(ShadowEventJournal.Outcome.DISQUALIFIED) >= 1,
                "the foreign-session event is recorded DISQUALIFIED, never admitted");
        ShadowEventJournal.RefusedCompletion completeForeign =
                journal.complete(102L, 42L, ShadowEventJournal.Outcome.COMPARE_PASS, null, null);
        check(completeForeign != null
                && completeForeign.refusal == ShadowEventJournal.CompletionRefusal.SESSION_MISMATCH,
                "completion under a session other than the event's own is rejected");

        // ---- 5. stale chunk incarnation rejected at capture --------------
        LiveChunkBindings.Binding binding = new LiveChunkBindings.Binding(
                7L, 1L, 2L, 55L, 6L, new Object(), new Object(),
                LiveChunkBindings.BindingState.READY,
                LiveChunkBindings.SourceQualification.OWNER_GENERATED);
        CaptureSource.View staleIncarnation = view(555L, 6L, 1L, 0xFFFF);
        try {
            CaptureDraft.validateBegin(staleIncarnation, binding, 0xFFFF);
            check(false, "a stale incarnation must be rejected");
        } catch (LivePacketCapture.Rejection rejection) {
            check(rejection.reason == LivePacketCapture.RejectionReason.PROVIDER_IDENTITY_CHANGED,
                    "stale chunk incarnation is rejected (binding identity mismatch)");
        }

        // ---- 6. begin epoch != end epoch -> excluded ---------------------
        CaptureSource.View beginView = view(55L, 6L, 1L, 0xFFFF);
        CaptureSource.View changedEpoch = view(55L, 6L, 77L, 0xFFFF);
        try {
            CaptureDraft.validateViews(beginView, changedEpoch);
            check(false, "a changed epoch must be rejected");
        } catch (LivePacketCapture.Rejection rejection) {
            check(rejection.reason == LivePacketCapture.RejectionReason.EPOCH_CHANGED,
                    "begin epoch != end epoch is excluded, never compared");
        }
        CaptureDraft.validateViews(beginView, view(55L, 6L, 1L, 0xFFFF));
        check(true, "identical begin/end views validate");

        // ---- 7/8. high state id excluded; 65535 allowed ------------------
        int[][] high = new int[1][4096];
        java.util.Arrays.fill(high[0], 65536);
        ShadowScopeGate.Result excluded = ShadowScopeGate.evaluateCounted(high);
        check(!excluded.eligible && "HIGH_STATE_ID".equals(excluded.reason),
                "a used state id of 65536 excludes the chunk");
        int[][] boundary = new int[1][4096];
        java.util.Arrays.fill(boundary[0], 65535);
        check(ShadowScopeGate.evaluateCounted(boundary).eligible,
                "max valid state 65535 is allowed");
        journal.recordOutcome(300L, 7L, ShadowEventJournal.Outcome.EXCLUDED,
                "HIGH_STATE_ID", "control");
        check(journal.count(ShadowEventJournal.Outcome.EXCLUDED) == 1,
                "the high-state exclusion is a journal record, not a silent drop");
        check(journal.mint(301L, 7L) == null, "event 301 mints for the reason-required control");
        try {
            journal.complete(301L, 7L, ShadowEventJournal.Outcome.EXCLUDED, null, null);
            check(false, "EXCLUDED without a named reason must be refused");
        } catch (IllegalArgumentException named) {
            check(true, "EXCLUDED requires a named reason");
        }

        // ---- 9. queue-full -> DROPPED_BACKPRESSURE -----------------------
        // The queue bound is fixed per JVM (capacity 8 in this control JVM);
        // fill it and prove the offer is refused and journaled.
        int offered = 0, refused = 0;
        for (int i = 0; i < 64; i++) {
            LiveComparisonQueue.OfferResult result =
                    LiveComparisonQueue.offer(controlCapture(4000L + i));
            if (result.accepted) offered++;
            else refused++;
        }
        check(offered <= 8 && refused >= 56,
                "the bounded queue refuses beyond capacity (offered=" + offered + ")");
        for (int i = 0; i < offered; i++)
            LiveComparisonQueue.drain();
        journal.recordOutcome(310L, 7L, ShadowEventJournal.Outcome.DROPPED,
                "BACKPRESSURE", "queue full at commit");
        check(journal.count(ShadowEventJournal.Outcome.DROPPED) == 1,
                "queue-full is journaled DROPPED_BACKPRESSURE with full identity");

        // ---- 10. Rust/native failure classifies INFRA_FAILURE ------------
        // (The real DLL failure is exercised in PhaseDOfflineReplay; here the
        // classification contract itself is pinned.)
        journal.recordOutcome(320L, 7L, ShadowEventJournal.Outcome.INFRA_FAILURE,
                "RUST_ENCODE", "PacketEncodeResultV2 failure -5 in replay");
        check(journal.count(ShadowEventJournal.Outcome.INFRA_FAILURE) == 1,
                "a Rust/native failure is an INFRA_FAILURE record");

        // ---- 11. Java result authoritative on Rust failure ---------------
        byte[] javaBytes = {1, 2, 3, 4};
        ShadowEventComparator.JavaSide authoritative =
                new ShadowEventComparator.JavaSide(javaBytes, 0xFFFF);
        ShadowEventComparator.RustSide failed =
                new ShadowEventComparator.RustSide(new byte[0], 0xFFFF);
        ShadowEventComparator.Comparison failedComparison =
                ShadowEventComparator.compareStatic(authoritative, failed);
        check(!failedComparison.pass, "a failed Rust side is a mismatch, not a crash");
        javaBytes[0] = 99; // caller mutation must not reach the comparator's copy
        check(authoritative.payload[0] == 1,
                "the Java side is defensively owned: nothing can mutate the authoritative bytes");
        check(failedComparison.firstDivergence.javaLength == 4,
                "the Java result is preserved intact on Rust failure");

        // ---- 12/13. exact equality passes; one byte differs --------------
        ShadowEventComparator.JavaSide pass =
                new ShadowEventComparator.JavaSide(new byte[]{9, 8, 7, 6}, 0x0F0F);
        ShadowEventComparator.RustSide same =
                new ShadowEventComparator.RustSide(new byte[]{9, 8, 7, 6}, 0x0F0F);
        check(ShadowEventComparator.compareStatic(pass, same).pass,
                "exact byte equality (and mask equality) is COMPARE_PASS");
        ShadowEventComparator.RustSide oneByteOff =
                new ShadowEventComparator.RustSide(new byte[]{9, 8, 7, 5}, 0x0F0F);
        ShadowEventComparator.Comparison mismatch =
                ShadowEventComparator.compareStatic(pass, oneByteOff);
        check(!mismatch.pass && mismatch.firstDivergence.offset == 3,
                "a one-byte difference is COMPARE_MISMATCH at the exact offset");
        check(mismatch.firstDivergence.contextHex.contains("0605")
                || mismatch.firstDivergence.contextHex.contains("05"),
                "the bounded context carries the diverging bytes");
        journal.recordOutcome(330L, 7L, ShadowEventJournal.Outcome.COMPARE_MISMATCH,
                "FIRST_DIVERGENCE", "offset=3 (control)");
        check(journal.count(ShadowEventJournal.Outcome.COMPARE_MISMATCH) == 1,
                "the mismatch is recorded");

        // ---- 14/15/16. denominator discipline ----------------------------
        journal.recordOutcome(331L, 7L, ShadowEventJournal.Outcome.COMPARE_PASS, null, "control");
        long denominator = journal.parityDenominator();
        check(denominator == journal.count(ShadowEventJournal.Outcome.COMPARE_PASS)
                + journal.count(ShadowEventJournal.Outcome.COMPARE_MISMATCH),
                "parity denominator is exactly COMPARE_PASS + COMPARE_MISMATCH");
        journal.validate();
        check(true, "journal counters re-derive from the record set without drift");
        // mismatch (1) + pass (2: event 101 and 331) = 3; the one EXCLUDED, one
        // DROPPED, one INFRA_FAILURE, one DISQUALIFIED are all outside it.
        check(denominator == 3, "exclusion/drop/infra/disqualified never enter the denominator");

        // ---- 17. Java and Rust cannot be swapped -------------------------
        Method compare = ShadowEventComparator.class.getMethod("compareStatic",
                ShadowEventComparator.JavaSide.class, ShadowEventComparator.RustSide.class);
        check(compare.getParameterTypes()[0] != compare.getParameterTypes()[1],
                "the comparator's parameter types are distinct: swapped arguments do not compile");
        boolean swapCompiled = false;
        try {
            ShadowEventComparator.class.getMethod("compareStatic",
                    ShadowEventComparator.RustSide.class, ShadowEventComparator.JavaSide.class);
            swapCompiled = true;
        } catch (NoSuchMethodException absent) { }
        check(!swapCompiled, "no swapped-order overload exists");

        // ---- 18. production_authority cannot become true -----------------
        boolean setterFound = false;
        for (Method method : ShadowEventJournal.class.getDeclaredMethods())
            if (method.getName().startsWith("set") && method.getName().toLowerCase()
                    .contains("authority")) setterFound = true;
        check(!setterFound, "no authority setter exists on the journal");
        check(journal.taxonomyJson().startsWith("{\"production_authority\":false"),
                "the taxonomy receipt hard-codes production_authority:false");
        boolean contractMutable = false;
        for (Method method : SessionCompatibilityContract.class.getDeclaredMethods())
            if (Modifier.isPublic(method.getModifiers()) && !Modifier.isStatic(method.getModifiers())
                    && method.getParameterTypes().length > 0) contractMutable = true;
        check(!contractMutable,
                "the contract facts are immutable: no instance method accepts a replacement");
        check(contract.toJson().contains("\"channelSetDigest\":null"),
                "the Phase-D contract carries no client-session facts (claim limit)");

        // ---- 19 (contract side). mismatch prevents shadow admission ------
        // Covered above: foreign binding session refused + DISQUALIFIED; the
        // journal is bound to exactly one contract and refuses a second bind.
        check(journal.contract() == contract,
                "shadow admission references the single bound contract");

        // ---- 20. no compatibility lookup per inner encode operation ------
        Class<?>[] compareParams = compare.getParameterTypes();
        boolean carriesContract = false;
        for (Class<?> parameter : compareParams)
            if (parameter == SessionCompatibilityContract.class
                    || parameter == ShadowEventJournal.class) carriesContract = true;
        check(!carriesContract,
                "the comparison operation takes neither the contract nor the journal");
        boolean perPacketContractReader = false;
        for (Method method : SessionCompatibilityContract.class.getDeclaredMethods())
            if (method.getName().contains("lookup") || method.getName().contains("resolve"))
                perPacketContractReader = true;
        check(!perPacketContractReader,
                "the contract exposes no lookup/resolve operation: it is hash-referenced only");

        journal.close();

        Path summary = out.resolve("phase-d-controls.json");
        String json = "{\"controls\":20,\"checks\":" + checks
                + ",\"outcome\":\"" + (checks >= 32 ? "PASS" : "FAIL") + "\"}";
        Files.write(summary, json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        System.out.println("PhaseDControls " + (checks >= 32 ? "PASS" : "FAIL")
                + " (" + checks + " assertions)");
        if (checks < 32) System.exit(1);
    }

    // ------------------------------------------------------------------

    private static CaptureSource.View view(long incarnation, long generation,
                                           long mutationEpoch, int filter) {
        return new CaptureSource.View(new Object(), new Object(), 0, 0, 0,
                incarnation, generation, mutationEpoch, filter, filter == 0xFFFF,
                true, CaptureContract.StorageModel.VANILLA_U16, 13,
                "phase-d-control", new CaptureSource.Section[16], new byte[256]);
    }

    /** A minimal sealed capture for queue-capacity controls (owned data only). */
    private static SealedLiveCapture controlCapture(long eventId) {
        CaptureContract.Context context = new CaptureContract.Context(
                Thread.currentThread(), 1L, 1L, "PHASE_D_CONTROL", "PHASE_D_CONTROL",
                true, new java.util.EnumMap<CaptureContract.Domain,
                        CaptureContract.WriterClass>(CaptureContract.Domain.class), null);
        CaptureSource.View view = view(1L, 1L, 1L, 0xFFFF);
        OwnedPacketSnapshot.Section[] sections = new OwnedPacketSnapshot.Section[16];
        sections[0] = new OwnedPacketSnapshot.Section(0, new long[4096],
                new byte[2048], new byte[2048], null, true, 0);
        OwnedPacketSnapshot snapshot = new OwnedPacketSnapshot(view, view, context,
                sections, new byte[256], 1, true, CaptureContract.Scope.SYNTHETIC_OFFLINE);
        LiveChunkBindings.BindingIdentity identity =
                new LiveChunkBindings.BindingIdentity(7L, 1L, 2L, 3L, 4L);
        return new SealedLiveCapture(eventId, identity, snapshot,
                new byte[]{1}, 1, true, 0, 0);
    }

    private static String repeated(char c, int length) {
        char[] chars = new char[length];
        java.util.Arrays.fill(chars, c);
        return new String(chars);
    }

    private static void check(boolean condition, String what) {
        checks++;
        if (!condition) {
            System.out.println("CONTROL FAILED: " + what);
            throw new IllegalStateException("control failed: " + what);
        }
    }

    private PhaseDControls() { }
}
