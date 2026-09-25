package com.rustcraft.bridge.capture;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Deterministic fake-identity test support for the live writer-protocol
 * foundation tests. No Minecraft/Forge classes, no JNI, no sleeps: ordering is
 * established by thread joins, bounded future waits, and single-threaded owner
 * sequencing. All timeouts are bounded failure detectors for test bugs, never
 * race inference.
 */
final class LiveProtocolTestSupport {

    private LiveProtocolTestSupport() { }

    static final class FakeWorld {
        final int tag;

        FakeWorld(int tag) { this.tag = tag; }

        @Override public String toString() { return "FakeWorld#" + tag; }
    }

    static final class FakeChunk {
        final int tag;

        FakeChunk(int tag) { this.tag = tag; }

        @Override public String toString() { return "FakeChunk#" + tag; }
    }

    static final class FakeProvider {
        final int tag;

        FakeProvider(int tag) { this.tag = tag; }

        @Override public String toString() { return "FakeProvider#" + tag; }
    }

    static final class FakeNbtRoot {
        final int tag;

        FakeNbtRoot(int tag) { this.tag = tag; }

        @Override public String toString() { return "FakeNbtRoot#" + tag; }
    }

    /**
     * Single-threaded deterministic canonical-owner executor. Protocol
     * operations that must run on the owner thread are submitted and awaited;
     * executions are strictly sequential, so no interleaving is possible.
     */
    static final class OwnerThread {
        private final Thread thread;
        private final LinkedBlockingQueue<FutureTask<?>> queue = new LinkedBlockingQueue<FutureTask<?>>();

        OwnerThread(String name) {
            thread = new Thread(new Runnable() {
                @Override public void run() {
                    loop();
                }
            }, name);
            thread.setDaemon(true);
            thread.start();
        }

        Thread thread() { return thread; }

        private void loop() {
            while (true) {
                try {
                    FutureTask<?> task = queue.take();
                    task.run();
                } catch (InterruptedException exit) {
                    return; // orderly shutdown
                }
            }
        }

        /** Runs one action on the owner thread and awaits it with a bounded timeout. */
        <T> T run(Callable<T> action) {
            FutureTask<T> task = new FutureTask<T>(action);
            queue.add(task);
            try {
                return task.get(30_000L, TimeUnit.MILLISECONDS);
            } catch (ExecutionException failure) {
                Throwable cause = failure.getCause();
                if (cause instanceof RuntimeException) throw (RuntimeException) cause;
                if (cause instanceof Error) throw (Error) cause;
                throw new RuntimeException(cause);
            } catch (TimeoutException timeout) {
                throw new AssertionError("owner thread action timed out (deadlock?)", timeout);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("test interrupted", interrupted);
            }
        }

        void close() {
            thread.interrupt();
            try {
                thread.join(10_000L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Runs one action on a fresh worker thread; bounded join, unwrapped failures. */
    static <T> T inWorker(String name, Callable<T> action) {
        FutureTask<T> task = new FutureTask<T>(action);
        Thread worker = new Thread(task, name);
        worker.setDaemon(true);
        worker.start();
        try {
            return task.get(30_000L, TimeUnit.MILLISECONDS);
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new RuntimeException(cause);
        } catch (TimeoutException timeout) {
            throw new AssertionError("worker " + name + " timed out (deadlock?)", timeout);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("test interrupted", interrupted);
        }
    }

    /** One assembled diagnostic session: gate + tickets + bindings + deterministic owner. */
    static final class Session {
        final OwnerThread owner;
        final LiveWriterGate gate;
        final PrivateBuildTickets tickets;
        final LiveChunkBindings bindings;
        final FakeWorld world;
        final FakeProvider provider;

        Session(String name) {
            owner = new OwnerThread(name + "-owner");
            gate = new LiveWriterGate(owner.thread());
            tickets = new PrivateBuildTickets(gate);
            bindings = new LiveChunkBindings(gate, tickets);
            gate.attach(tickets, bindings);
            owner.run(new Callable<Void>() {
                @Override public Void call() {
                    gate.enable();
                    return null;
                }
            });
            world = new FakeWorld(1);
            provider = new FakeProvider(1);
        }

        /** Runs one whole writer operation on the owner; the operation sees the gate held. */
        <T> T ownerWrite(final String operation, final Callable<T> body) {
            return owner.run(new Callable<T>() {
                @Override public T call() throws Exception {
                    LiveWriterGate.WriteToken token = gate.beginWrite(null, null, operation);
                    try {
                        return body.call();
                    } finally {
                        gate.endWrite(token, null);
                    }
                }
            });
        }

        void close() {
            try {
                owner.run(new Callable<Void>() {
                    @Override public Void call() {
                        if (gate.isEnabled()) gate.shutdownForSessionEnd();
                        return null;
                    }
                });
            } catch (Throwable tolerated) {
                // Sessions left non-quiesced by misuse tests cannot shut down; close anyway.
            } finally {
                owner.close();
            }
        }
    }

    /**
     * Deterministic private I/O build of one chunk incarnation on a worker
     * thread: registers the chunk root and one backing array, performs one
     * admitted private-bypass write, and seals with the requested outcome.
     * Returns the ticket so the owner side can acquire/admit/publish it.
     */
    static PrivateBuildTickets.Ticket workerBuildAndSeal(final Session session, final FakeWorld world,
            final FakeProvider provider, final long loadId, final int x, final int z,
            final FakeChunk chunk, final byte[] backing, final boolean failBuild) {
        return inWorker("io-worker-" + loadId, new Callable<PrivateBuildTickets.Ticket>() {
            @Override public PrivateBuildTickets.Ticket call() {
                PrivateBuildTickets.Ticket ticket =
                        session.tickets.beginIo(provider, loadId, world, x, z);
                if (ticket == null) {
                    throw new AssertionError("beginIo failed: " + session.tickets.lastAdmissionFailure());
                }
                if (!session.tickets.recordDiskRoot(ticket, new FakeNbtRoot((int) loadId),
                        PrivateBuildTickets.CURRENT_DATA_VERSION)) {
                    throw new AssertionError("recordDiskRoot failed; source=" + ticket.sourceStatus());
                }
                if (!session.tickets.registerNew(ticket, chunk, null)) {
                    throw new AssertionError("registerNew(chunk) rejected");
                }
                if (!session.tickets.registerNew(ticket, backing, null)) {
                    throw new AssertionError("registerNew(backing) rejected");
                }
                LiveWriterGate.WriteToken write =
                        session.gate.beginWrite(chunk, new Object[] { backing }, "io-private-build");
                if (write.kind() != LiveWriterGate.WriteToken.Kind.PRIVATE) {
                    throw new AssertionError("expected exact private graph bypass, got " + write.kind());
                }
                backing[0] = 42; // the actual private mutation inside the admitted scope
                session.gate.endWrite(write, failBuild ? new RuntimeException("worker io failure") : null);
                boolean sealed = failBuild
                        ? session.tickets.sealFailure(ticket)
                        : session.tickets.sealSuccess(ticket);
                if (!sealed) throw new AssertionError("seal rejected");
                return ticket;
            }
        });
    }

    /**
     * Owner-side publication of one adopted IO ticket inside one whole writer
     * operation: acquire, admit (PRIVATE), begin publication (PUBLISHING),
     * exact map put, finish. Returns the resulting binding (READY on normal
     * outermost completion).
     */
    static LiveChunkBindings.Binding ownerPublishTicket(final Session session, final FakeWorld world,
            final int x, final int z, final FakeChunk chunk, final PrivateBuildTickets.Ticket ticket) {
        return session.ownerWrite("syncCallback", new Callable<LiveChunkBindings.Binding>() {
            @Override public LiveChunkBindings.Binding call() {
                PrivateBuildTickets.AcquireOutcome acquired = session.tickets.acquireCompletion(ticket);
                if (acquired != PrivateBuildTickets.AcquireOutcome.SUCCESS) {
                    throw new AssertionError("acquireCompletion returned " + acquired
                            + " (state=" + ticket.state() + " source=" + ticket.sourceStatus()
                            + " taint=" + ticket.taintReason() + " writeFailed=" + ticket.isWriteFailed() + ")");
                }
                LiveChunkBindings.Outcome admitted =
                        session.bindings.admitIoTicket(ticket, world, chunk);
                if (!admitted.ok()) throw new AssertionError("admitIoTicket failed: " + admitted.reason());
                LiveChunkBindings.Outcome begun =
                        session.bindings.beginPublication(world, chunk);
                if (!begun.ok()) throw new AssertionError("beginPublication failed: " + begun.reason());
                LiveChunkBindings.Outcome put = session.bindings.recordMapPut(world, x, z, chunk);
                if (!put.ok()) throw new AssertionError("recordMapPut failed: " + put.reason());
                LiveChunkBindings.Outcome finished =
                        session.bindings.finishPublication(begun.publication(), null);
                if (!finished.ok()) throw new AssertionError("finishPublication failed: " + finished.reason());
                return admitted.binding();
            }
        });
    }

    /**
     * One capture cycle on the owner. When {@code expectCommit} is true the
     * attempt must be admitted and must seal; when false a rejected attempt is
     * returned as-is (the caller asserts the fallback reason) and an admitted
     * attempt is ended without asserting the commit outcome.
     */
    static LiveWriterGate.CaptureAttempt ownerCapture(final Session session, final FakeWorld world,
            final FakeChunk chunk, final boolean expectCommit) {
        return session.owner.run(new Callable<LiveWriterGate.CaptureAttempt>() {
            @Override public LiveWriterGate.CaptureAttempt call() {
                LiveWriterGate.CaptureAttempt attempt = session.gate.tryBeginCapture(world, chunk);
                if (!attempt.admitted()) {
                    if (expectCommit) {
                        throw new AssertionError("capture not admitted: " + attempt.fallbackReason());
                    }
                    return attempt;
                }
                boolean sealed = session.gate.endCapture(attempt, null);
                if (sealed != expectCommit) {
                    throw new AssertionError("endCapture=" + sealed + " outcome=" + attempt.outcome());
                }
                return attempt;
            }
        });
    }
}
