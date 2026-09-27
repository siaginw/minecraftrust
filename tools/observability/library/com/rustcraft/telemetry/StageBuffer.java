package com.rustcraft.telemetry;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
/** Thread-confined bounded samples. Foreign-thread calls are explicit losses, never extra writers. */
public final class StageBuffer  {
    public enum Stage  {
        TICK,TICK_WORLD,TICK_ENTITIES,TICK_BLOCK_ENTITIES,CHUNK_READ,CHUNK_DECODE,CHUNK_GENERATE,CHUNK_LIGHT,CHUNK_PUBLISH,JNI_NATIVE_BODY,JNI_WHOLE_CALL,NETWORK_SERIALIZE,NETWORK_COMPRESS,NETWORK_SEND,LOCK_WAIT,LOCK_HOLD,CALLBACK_INCLUSIVE,CALLBACK_EXCLUSIVE
    }
    private final Thread owner=Thread.currentThread();
    private final int[] stages;
    private final long[] durations;
    private final AtomicLongArray dropped=new AtomicLongArray(Stage.values().length);
    private final AtomicLong errors=new AtomicLong(),foreignCalls=new AtomicLong();
    private final AtomicBoolean overflow=new AtomicBoolean();
    private final RuntimeTelemetry.Clock clock;
    private final boolean enabled;
    private int used;
    public StageBuffer(int capacity,boolean enabled,RuntimeTelemetry.Clock clock)  {
        if(capacity<1||capacity>4096||clock==null)throw new IllegalArgumentException("capacity/clock");
        stages=new int[capacity];
        durations=new long[capacity];
        this.enabled=enabled;
        this.clock=clock;
    }
    private void increment(AtomicLong counter)  {
        for(;;) {
            long old=counter.get();
            if(old==Long.MAX_VALUE) {
                overflow.set(true);
                return;
            }
            if(counter.compareAndSet(old,old+1))return;
        }
    }
    private void drop(int id)  {
        for(;;) {
            long old=dropped.get(id);
            if(old==Long.MAX_VALUE) {
                overflow.set(true);
                return;
            }
            if(dropped.compareAndSet(id,old,old+1))return;
        }
    }
    /** durationNs==-1 is unknown; zero is a real measured zero. Never throws into the observed body. */
    public boolean record(Stage stage,long durationNs)  {
        if(!enabled)return false;
        try  {
            int id=stage.ordinal();
            if(owner!=Thread.currentThread()) {
                increment(foreignCalls);
                drop(id);
                return false;
            }
            if(durationNs< -1) {
                increment(errors);
                drop(id);
                return false;
            }
            if(used==stages.length) {
                drop(id);
                return false;
            }
            stages[used]=id;
            durations[used++]=durationNs;
            return true;
        }
        catch(Throwable invalid) {
            increment(errors);
            return false;
        }
    }
    public <T>T measure(Stage stage,RuntimeTelemetry.Body<T> body)throws Throwable  {
        if(!enabled)return body.run();
        if(owner!=Thread.currentThread()) {
            record(stage,-1);
            return body.run();
        }
        long started=0;
        boolean startKnown=false;
        try {
            started=clock.now();
            startKnown=true;
        }
        catch(Throwable unavailable) {
            increment(errors);
        }
        try {
            return body.run();
        }
        finally {
            long value=-1;
            try {
                long elapsed=clock.now()-started;
                if(startKnown&&elapsed>=0)value=elapsed;
            }
            catch(Throwable unavailable) {
                increment(errors);
            }
            record(stage,value);
        }
    }
    public int retained() {
        return used;
    }
    public long dropped(Stage stage) {
        return dropped.get(stage.ordinal());
    }
    private long[] losses() {
        long[] values=new long[dropped.length()];
        for(int i=0;i<values.length;i++)values[i]=dropped.get(i);
        return values;
    }
    /** Cold snapshots/exports/reset require external quiescence of all producers, including rejected ones. */
    public Map<String,Object> coverage() {
        Map<String,Object> map=new LinkedHashMap<String,Object>();
        map.put("schema","RUSTCRAFT_STAGE_BATCH_V1");
        map.put("retained",used);
        map.put("capacity",stages.length);
        map.put("dropped_by_stage",losses());
        map.put("instrumentation_errors",errors.get());
        map.put("foreign_thread_calls",foreignCalls.get());
        map.put("counter_overflow",overflow.get());
        map.put("quantiles_require_native_import",true);
        return map;
    }
    public void writeBatch(Path path)throws Exception  {
        if(owner!=Thread.currentThread())throw new IllegalStateException("owner");
        if(overflow.get())throw new IllegalStateException("counter overflow: complete batch unavailable");
        StringBuilder data=new StringBuilder("RUSTCRAFT_STAGE_BATCH_V1\n");
        for(int i=0;i<used;i++)data.append("S\t").append(stages[i]).append('\t').append(durations[i]).append('\n');
        for(int i=0;i<dropped.length();i++)if(dropped.get(i)!=0)data.append("D\t").append(i).append('\t').append(dropped.get(i)).append('\n');
        Files.write(path,data.toString().getBytes(StandardCharsets.US_ASCII),StandardOpenOption.CREATE_NEW);
    }
    public void resetAfterExport() {
        if(owner!=Thread.currentThread())throw new IllegalStateException("owner");
        used=0;
        for(int i=0;i<dropped.length();i++)dropped.set(i,0);
        errors.set(0);
        foreignCalls.set(0);
        overflow.set(false);
    }
}
