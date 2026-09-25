package com.rustcraft.bridge.capture;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded, nonblocking diagnostic queue for sealed live-SHADOW captures. The
 * ServerThread never waits for a consumer: a full queue (capacity or aggregate
 * owned-byte budget) drops the diagnostic work and counts the drop — gameplay
 * is unaffected. Consumers are diagnostic only and receive fully owned sealed
 * values; no consumer can mutate live world state through this queue.
 */
public final class LiveComparisonQueue {

    public enum DropReason { FULL, BYTES, INACTIVE }

    public static final class OfferResult {
        public final boolean accepted;
        public final DropReason reason;

        OfferResult(boolean accepted, DropReason reason) {
            this.accepted = accepted;
            this.reason = reason;
        }
    }

    private static final ConcurrentLinkedQueue<SealedLiveCapture> QUEUE =
            new ConcurrentLinkedQueue<SealedLiveCapture>();
    private static final AtomicInteger RESERVED = new AtomicInteger();
    private static final AtomicLong RESERVED_BYTES = new AtomicLong();

    public static final AtomicLong ENQUEUE_SUCCESSES = new AtomicLong();
    public static final AtomicLong QUEUE_FULL_DROPS = new AtomicLong();
    public static final AtomicLong BYTE_BUDGET_DROPS = new AtomicLong();
    public static final AtomicLong INACTIVE_DROPS = new AtomicLong();
    public static final AtomicLong CONSUMED = new AtomicLong();

    private LiveComparisonQueue() { }

    /**
     * Nonblocking bounded offer. Reservation happens before insertion and rolls
     * back on the (rare) failed poll-side invariant, so a consumer can never
     * observe a size above the bound.
     */
    static LiveComparisonQueue.OfferResult offer(SealedLiveCapture sealed) {
        int bytes = sealed.javaPayloadLength() + sealed.toTransportBytes().length;
        if (LiveWriterHooks.currentSessionInternal() == null) {
            INACTIVE_DROPS.incrementAndGet();
            return new LiveComparisonQueue.OfferResult(false, DropReason.INACTIVE);
        }
        if (RESERVED.get() >= LivePacketCapture.QUEUE_CAPACITY) {
            QUEUE_FULL_DROPS.incrementAndGet();
            return new LiveComparisonQueue.OfferResult(false, DropReason.FULL);
        }
        long reservedBytes = RESERVED_BYTES.addAndGet(bytes);
        if (reservedBytes > LivePacketCapture.QUEUE_MAX_BYTES) {
            RESERVED_BYTES.addAndGet(-bytes);
            BYTE_BUDGET_DROPS.incrementAndGet();
            return new LiveComparisonQueue.OfferResult(false, DropReason.BYTES);
        }
        QUEUE.add(sealed);
        RESERVED.incrementAndGet();
        ENQUEUE_SUCCESSES.incrementAndGet();
        return new LiveComparisonQueue.OfferResult(true, null);
    }

    /**
     * Diagnostic consumer: drains every currently queued sealed capture in FIFO
     * order. Each returned object is fully owned; consumption touches no live
     * Java state and needs no gate.
     */
    public static List<SealedLiveCapture> drain() {
        List<SealedLiveCapture> drained = new ArrayList<SealedLiveCapture>();
        Iterator<SealedLiveCapture> iterator = QUEUE.iterator();
        while (iterator.hasNext()) {
            SealedLiveCapture sealed = iterator.next();
            if (QUEUE.remove(sealed)) {
                RESERVED.decrementAndGet();
                CONSUMED.incrementAndGet();
                drained.add(sealed);
            }
        }
        return drained;
    }

    public static int queuedCount() {
        return RESERVED.get();
    }

    public static long queuedBytes() {
        return RESERVED_BYTES.get();
    }

    /** Session-end clear: only reachable on a fully quiesced diagnostic shutdown. */
    static void clearForSessionEnd() {
        QUEUE.clear();
        RESERVED.set(0);
        RESERVED_BYTES.set(0);
    }
}
