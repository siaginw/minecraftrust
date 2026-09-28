package com.rustcraft.bridge.capture;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase-D event journal: the Phase-A taxonomy ledger AND the completion
 * registry for shadow events. One terminal outcome per eventId, forever.
 *
 * <p>Guarantees, each pinned by an offline control:</p>
 * <ul>
 *   <li>one Java packet event carries exactly one eventId; a duplicate
 *       completion for the same eventId is REJECTED (recorded as a protocol
 *       violation, never overwriting the first outcome)</li>
 *   <li>completion of an unknown eventId is REJECTED</li>
 *   <li>an event minted under a binding session other than the journal's
 *       pinned session is DISQUALIFIED (session identity failure) and can
 *       never be completed or compared</li>
 *   <li>EXCLUDED and DROPPED records REQUIRE a named reason; "not
 *       comparable" is not a reason</li>
 *   <li>the parity denominator is exactly COMPARE_PASS + COMPARE_MISMATCH;
 *       exclusions and drops never count as passes and never enter it</li>
 *   <li>{@code production_authority} is false, final, and this journal
 *       refuses any other value at serialization time</li>
 * </ul>
 *
 * <p>Session identity is two-level, mirroring the runtime: the journal binds
 * once to the JVM's {@link SessionCompatibilityContract} (process +
 * transformation session), and pins the writer-protocol binding session id
 * (the {@code LiveChunkBindings} session the gate mints event ids under) at
 * the first mint. Both must agree for an event to complete.</p>
 *
 * <p>Thread model: event minting happens on the capture (server) thread,
 * completion on the consumer thread; the registry is a concurrent map and
 * the JSONL writer is synchronized. Failures in this journal classify as
 * INFRA_FAILURE evidence and never change Java packet behavior.</p>
 */
public final class ShadowEventJournal {

    /** Phase-A taxonomy per-event outcomes (INCOMPLETE is campaign-level). */
    public enum Outcome {
        COMPARE_PASS, COMPARE_MISMATCH, EXCLUDED, DROPPED, DISQUALIFIED, INFRA_FAILURE
    }

    /** Why a completion attempt was refused; the first outcome stands. */
    public enum CompletionRefusal { DUPLICATE_EVENT_ID, UNKNOWN_EVENT_ID, SESSION_MISMATCH,
                                    NO_CONTRACT, JOURNAL_CLOSED }

    public static final class Record {
        public final long eventId;
        public final long bindingSessionId;
        public final Outcome outcome;
        /** Required for EXCLUDED/DROPPED; taxonomy vocabulary. */
        public final String reason;
        public final String detail;
        public final long atNanos;
        public final long atMillis;

        Record(long eventId, long bindingSessionId, Outcome outcome, String reason,
               String detail, long atNanos) {
            this.eventId = eventId;
            this.bindingSessionId = bindingSessionId;
            this.outcome = outcome;
            this.reason = reason;
            this.detail = detail;
            this.atNanos = atNanos;
            this.atMillis = System.currentTimeMillis();
        }
    }

    public static final class RefusedCompletion {
        public final CompletionRefusal refusal;
        public final long eventId;
        public final long bindingSessionId;
        RefusedCompletion(CompletionRefusal refusal, long eventId, long bindingSessionId) {
            this.refusal = refusal; this.eventId = eventId; this.bindingSessionId = bindingSessionId;
        }
    }

    private final Map<Long, Record> records = new ConcurrentHashMap<Long, Record>();
    private final Map<Outcome, long[]> counters = new LinkedHashMap<Outcome, long[]>();
    private final SessionCompatibilityContract contract;
    private final PrintWriter jsonl;
    private volatile boolean closed;
    /** Pinned at first mint: every event of this session shares it. */
    private volatile Long pinnedBindingSession;
    private long protocolViolations;

    private ShadowEventJournal(SessionCompatibilityContract contract, PrintWriter jsonl) {
        this.contract = contract;
        this.jsonl = jsonl;
        for (Outcome outcome : Outcome.values()) counters.put(outcome, new long[1]);
    }

    /** The single journal for this JVM's shadow session; null until bound. */
    private static volatile ShadowEventJournal instance;

    /**
     * Binds the journal to its session contract and JSONL sink. Once per JVM:
     * a second bind is refused (and reported), the first journal stands.
     */
    public static synchronized ShadowEventJournal bind(SessionCompatibilityContract contract,
                                                       String jsonlPath) {
        if (instance != null) return null;
        try {
            Path path = Paths.get(jsonlPath).toAbsolutePath();
            if (path.getParent() != null) Files.createDirectories(path.getParent());
            PrintWriter writer = new PrintWriter(
                    Files.newBufferedWriter(path, StandardCharsets.UTF_8), true);
            instance = new ShadowEventJournal(contract, writer);
            instance.emit(instance.metaRecord("journal-bound", contract.toJson()));
            return instance;
        } catch (IOException failure) {
            // A journal that cannot persist evidence must not run comparisons.
            return null;
        }
    }

    public static ShadowEventJournal instance() { return instance; }

    public SessionCompatibilityContract contract() { return contract; }

    // ------------------------------------------------------------------
    // Minting (capture thread) and completion (consumer thread)
    // ------------------------------------------------------------------

    /**
     * Registers the eventId as minted under this binding session. Returns
     * null on success, or the refusal. The binding session is pinned at the
     * first mint; a foreign session is DISQUALIFIED immediately and can
     * never be completed.
     */
    public RefusedCompletion mint(long eventId, long bindingSessionId) {
        if (closed) return refuse(CompletionRefusal.JOURNAL_CLOSED, eventId, bindingSessionId);
        Long pinned = pinnedBindingSession;
        if (pinned != null && pinned.longValue() != bindingSessionId) {
            record(new Record(eventId, bindingSessionId, Outcome.DISQUALIFIED, "SESSION_IDENTITY",
                    "binding session " + bindingSessionId + " is not this journal's session "
                            + pinned.longValue(), System.nanoTime()));
            return refuse(CompletionRefusal.SESSION_MISMATCH, eventId, bindingSessionId);
        }
        if (pinned == null) pinnedBindingSession = Long.valueOf(bindingSessionId);
        Record existing = records.putIfAbsent(eventId,
                new Record(eventId, bindingSessionId, null, "MINTED", null, System.nanoTime()));
        return existing != null
                ? refuse(CompletionRefusal.DUPLICATE_EVENT_ID, eventId, bindingSessionId) : null;
    }

    /** True when the eventId is a known, not-yet-terminal event. */
    public boolean isKnownOpen(long eventId) {
        Record record = records.get(eventId);
        return record != null && record.outcome == null;
    }

    /**
     * Records the ONE terminal outcome for an eventId. Refuses (and reports)
     * duplicates, unknown ids, and binding-session mismatches; the first
     * terminal outcome stands forever.
     */
    public synchronized RefusedCompletion complete(long eventId, long bindingSessionId,
                                                   Outcome outcome, String reason,
                                                   String detail) {
        if (closed) return refuse(CompletionRefusal.JOURNAL_CLOSED, eventId, bindingSessionId);
        Record record = records.get(eventId);
        if (record == null) {
            protocolViolations++;
            return refuse(CompletionRefusal.UNKNOWN_EVENT_ID, eventId, bindingSessionId);
        }
        if (record.outcome != null) {
            protocolViolations++;
            return refuse(CompletionRefusal.DUPLICATE_EVENT_ID, eventId, bindingSessionId);
        }
        if (record.bindingSessionId != bindingSessionId) {
            protocolViolations++;
            return refuse(CompletionRefusal.SESSION_MISMATCH, eventId, bindingSessionId);
        }
        if (outcome == Outcome.EXCLUDED && !isNamedReason(reason)) {
            throw new IllegalArgumentException("EXCLUDED requires a named reason");
        }
        if (outcome == Outcome.DROPPED && !isNamedReason(reason)) {
            throw new IllegalArgumentException("DROPPED requires a named reason");
        }
        record(new Record(eventId, record.bindingSessionId, outcome, reason, detail,
                System.nanoTime()));
        return null;
    }

    /** Records a non-comparison outcome, minting the event if needed. */
    public void recordOutcome(long eventId, long bindingSessionId, Outcome outcome,
                              String reason, String detail) {
        if (records.get(eventId) == null) {
            RefusedCompletion refusal = mint(eventId, bindingSessionId);
            if (refusal != null) return;
        }
        complete(eventId, bindingSessionId, outcome, reason, detail);
    }

    private static boolean isNamedReason(String reason) {
        return reason != null && !reason.isEmpty() && Character.isUpperCase(reason.charAt(0));
    }

    private RefusedCompletion refuse(CompletionRefusal refusal, long eventId, long bindingSessionId) {
        emit(metaRecord("completion-refused:" + refusal,
                "{\"eventId\":" + eventId + ",\"bindingSessionId\":" + bindingSessionId + "}"));
        return new RefusedCompletion(refusal, eventId, bindingSessionId);
    }

    private void record(Record record) {
        if (record.outcome != null) {
            long[] counter = counters.get(record.outcome);
            if (counter != null) counter[0]++;
        }
        records.put(record.eventId, record);
        emit(eventJson(record));
    }

    private Record metaRecord(String kind, String payloadJson) {
        return new Record(-1, pinnedBindingSession == null ? -1 : pinnedBindingSession.longValue(),
                null, kind, payloadJson, System.nanoTime());
    }

    private void emit(Record record) {
        emit(eventJson(record));
    }

    private void emit(String json) {
        if (jsonl == null) return;
        synchronized (this) {
            jsonl.println(json);
            jsonl.flush();
        }
    }

    private String eventJson(Record record) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"eventId\":").append(record.eventId);
        sb.append(",\"bindingSessionId\":").append(record.bindingSessionId);
        if (record.outcome != null) sb.append(",\"outcome\":\"").append(record.outcome).append('"');
        if (record.reason != null) sb.append(",\"reason\":\"").append(record.reason).append('"');
        if (record.detail != null) sb.append(",\"detail\":\"")
                .append(record.detail.replace("\\", "\\\\").replace("\"", "\\\"")
                        .replace("\n", "\\n")).append('"');
        sb.append(",\"atMillis\":").append(record.atMillis).append('}');
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Counts, denominator, validation
    // ------------------------------------------------------------------

    public synchronized long count(Outcome outcome) { return counters.get(outcome)[0]; }

    public synchronized Map<String, Long> counts() {
        Map<String, Long> snapshot = new LinkedHashMap<String, Long>();
        for (Outcome outcome : Outcome.values()) snapshot.put(outcome.name(), counters.get(outcome)[0]);
        return snapshot;
    }

    /** EXCLUDED and DROPPED never enter this; COMPARE_PASS + COMPARE_MISMATCH only. */
    public synchronized long parityDenominator() {
        return counters.get(Outcome.COMPARE_PASS)[0] + counters.get(Outcome.COMPARE_MISMATCH)[0];
    }

    public long protocolViolations() { return protocolViolations; }
    public long recordsSize() { return records.size(); }

    /**
     * Re-derives counters from the record set and refuses disagreement with
     * the maintained counters. Receipt validation calls this; a journal whose
     * counters drifted is an INFRA_FAILURE, not a rounding problem.
     */
    public synchronized void validate() {
        Map<Outcome, Long> derived = new LinkedHashMap<Outcome, Long>();
        for (Outcome outcome : Outcome.values()) derived.put(outcome, 0L);
        for (Record record : records.values())
            if (record.outcome != null) derived.put(record.outcome, derived.get(record.outcome) + 1);
        for (Outcome outcome : Outcome.values())
            if (derived.get(outcome).longValue() != counters.get(outcome)[0])
                throw new IllegalStateException("journal counter drift for " + outcome);
    }

    /** Terminal receipt fragment; production authority is refused, always. */
    public synchronized String taxonomyJson() {
        StringBuilder sb = new StringBuilder("{\"production_authority\":false");
        for (Outcome outcome : Outcome.values())
            sb.append(",\"").append(outcome.name()).append("\":").append(counters.get(outcome)[0]);
        sb.append(",\"parity_denominator\":").append(parityDenominator());
        sb.append(",\"protocol_violations\":").append(protocolViolations);
        if (contract != null) sb.append(",\"contract_sha256\":\"").append(contract.sha256Hex()).append('"');
        sb.append('}');
        return sb.toString();
    }

    /** Closes the JSONL; after this every mint/completion is refused. */
    public synchronized void close() {
        if (closed) return;
        closed = true;
        if (jsonl != null) jsonl.close();
    }

    static { // the taxonomy vocabulary is exactly the Phase-A per-event set
        if (Outcome.values().length != 6)
            throw new IllegalStateException("per-event taxonomy must stay the Phase-A set");
    }
}
