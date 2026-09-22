package com.hermes.instrumentation;

public final class TickProfiler {
    public static final TickProfiler INSTANCE = new TickProfiler();

    private long tickStartTimeNanos;
    private long lastServerTickTimeNanos;
    private long worldTickTimeNanos;
    private long tileEntityTickTimeNanos;
    private long entityTickTimeNanos;

    private TickProfiler() {}

    public void startServerTick() {
        this.tickStartTimeNanos = System.nanoTime();
    }

    public void endServerTick() {
        this.lastServerTickTimeNanos = System.nanoTime() - this.tickStartTimeNanos;
    }

    public void recordWorldTick(long nanos) {
        this.worldTickTimeNanos = nanos;
    }

    public void recordTileEntityTick(long nanos) {
        this.tileEntityTickTimeNanos = nanos;
    }

    public void recordEntityTick(long nanos) {
        this.entityTickTimeNanos = nanos;
    }

    public long getLastServerTickTimeNanos() {
        return lastServerTickTimeNanos;
    }

    public long getWorldTickTimeNanos() {
        return worldTickTimeNanos;
    }

    public long getTileEntityTickTimeNanos() {
        return tileEntityTickTimeNanos;
    }

    public long getEntityTickTimeNanos() {
        return entityTickTimeNanos;
    }
}
