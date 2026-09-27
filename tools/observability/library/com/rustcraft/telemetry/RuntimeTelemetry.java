package com.rustcraft.telemetry;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
/** Reusable Java8 callback collector. Export/attach are cold; call entry/exit uses fixed arrays. */
public final class RuntimeTelemetry implements AutoCloseable  {
    public interface Clock  {
        long now();
    }
    public interface Body<T>  {
        T run() throws Throwable;
    }
    public static final Clock MONOTONIC=new Clock() {
        public long now() {
            return System.nanoTime();
        }
    }
    ;
    private static final AtomicLong FRAMES=new AtomicLong(1);
    private final CallbackRegistry registry;
    private final Clock clock;
    private final boolean enabled;
    private final int maxThreads,maxDepth;
    private final State[] states;
    private final ThreadLocal<State> local=new ThreadLocal<State>();
    private final long[][] completed=new long[CallbackRegistry.MAX_METHODS][6];
    private final AtomicLong errors=new AtomicLong(),unattached=new AtomicLong(),invalid=new AtomicLong(),depthOverflow=new AtomicLong();
    private volatile boolean closed;
    private volatile boolean diagnosticOverflow;
    private boolean completedOverflow;
    private int attached;
    private void increment(AtomicLong counter) {
        for(;;) {
            long v=counter.get();
            if(v==Long.MAX_VALUE) {
                diagnosticOverflow=true;
                return;
            }
            if(counter.compareAndSet(v,v+1))return;
        }
    }
    private static long nextFrame() {
        for(;;) {
            long v=FRAMES.get();
            if(v<=0||v==Long.MAX_VALUE)throw new IllegalStateException("frame identity exhausted");
            if(FRAMES.compareAndSet(v,v+1))return v;
        }
    }
    private static final class State  {
        final Thread thread=Thread.currentThread();
        final long[] frames,start,children;
        final CallbackRegistry.Token[] tokens;
        final Object[] receivers;
        final boolean[] exclusiveUnknown;
        final long[][] totals=new long[CallbackRegistry.MAX_METHODS][6];
        int depth;
        boolean active=true,overflow;
        State(int depth) {
            frames=new long[depth];
            start=new long[depth];
            children=new long[depth];
            tokens=new CallbackRegistry.Token[depth];
            receivers=new Object[depth];
            exclusiveUnknown=new boolean[depth];
        }
        void taintParents() {
            for(int i=0;i<depth;i++)exclusiveUnknown[i]=true;
        }
    }
    public final class Attachment implements AutoCloseable  {
        private State state;
        private Attachment(State s) {
            state=s;
        }
        public void close() {
            State s=state;
            if(s==null)return;
            if(Thread.currentThread()!=s.thread)throw new IllegalStateException("wrong detach thread");
            detach(s);
            state=null;
        }
    }
    public RuntimeTelemetry(CallbackRegistry registry,boolean enabled,int maxThreads,int maxDepth,Clock clock) {
        if(registry==null||clock==null||maxThreads<1||maxThreads>8||maxDepth<1||maxDepth>64)throw new IllegalArgumentException("bounds");
        this.registry=registry;
        this.enabled=enabled;
        this.maxThreads=maxThreads;
        this.maxDepth=maxDepth;
        this.clock=clock;
        states=new State[maxThreads];
    }
    public synchronized Attachment attach() {
        if(closed||local.get()!=null||attached==maxThreads)throw new IllegalStateException("thread capacity/lifecycle");
        State s=new State(maxDepth);
        for(int i=0;i<states.length;i++)if(states[i]==null) {
            states[i]=s;
            break;
        }
        attached++;
        local.set(s);
        return new Attachment(s);
    }
    private synchronized void detach(State s) {
        if(local.get()!=s||!s.active)throw new IllegalStateException("foreign attachment");
        s.active=false;
        for(int i=0;i<s.depth;i++) {
            s.totals[s.tokens[i].slot][3]=sum(s.totals[s.tokens[i].slot][3],1,s);
            s.tokens[i]=null;
            s.receivers[i]=null;
        }
        s.depth=0;
        for(int i=0;i<completed.length;i++)for(int j=0;j<6;j++)completed[i][j]=sum(completed[i][j],s.totals[i][j],s);
        completedOverflow|=s.overflow;
        for(int i=0;i<states.length;i++)if(states[i]==s)states[i]=null;
        attached--;
        local.remove();
    }
    private long enter(CallbackRegistry.Token token,Object receiver) {
        if(!enabled||closed)return 0;
        State s=local.get();
        if(s==null||!s.active) {
            increment(unattached);
            return 0;
        }
        try {
            if(!registry.accepts(token,receiver)) {
                s.taintParents();
                increment(invalid);
                return 0;
            }
            if(s.depth==maxDepth) {
                s.taintParents();
                increment(depthOverflow);
                return 0;
            }
            long time=clock.now();
            long serial=nextFrame();
            int d=s.depth++;
            s.frames[d]=serial;
            s.start[d]=time;
            s.children[d]=0;
            s.tokens[d]=token;
            s.receivers[d]=receiver;
            s.exclusiveUnknown[d]=false;
            return serial;
        }
        catch(Throwable failure) {
            s.taintParents();
            increment(errors);
            return 0;
        }
    }
    private void exit(long frame,boolean threw) {
        if(frame==0)return;
        State s=local.get();
        if(s==null||!s.active)return;
        long[] row=null;
        try {
            if(s.depth==0||s.frames[s.depth-1]!=frame) {
                s.taintParents();
                increment(errors);
                return;
            }
            int d=--s.depth;
            CallbackRegistry.Token token=s.tokens[d];
            Object receiver=s.receivers[d];
            s.tokens[d]=null;
            s.receivers[d]=null;
            row=s.totals[token.slot];
            if(threw)row[5]=sum(row[5],1,s);
            long elapsed=clock.now()-s.start[d];
            if(elapsed<0||!registry.accepts(token,receiver)) {
                row[3]=sum(row[3],1,s);
                s.taintParents();
                return;
            }
            row[0]=sum(row[0],1,s);
            row[1]=sum(row[1],elapsed,s);
            if(s.exclusiveUnknown[d]||s.children[d]>elapsed)row[4]=sum(row[4],1,s);
            else row[2]=sum(row[2],elapsed-s.children[d],s);
            if(d>0)s.children[d-1]=sum(s.children[d-1],elapsed,s);
        }
        catch(Throwable failure) {
            if(row!=null)row[3]=sum(row[3],1,s);
            s.taintParents();
            increment(errors);
        }
    }
    private static long sum(long a,long b,State state) {
        if(a<0||b<0||Long.MAX_VALUE-a<b) {
            state.overflow=true;
            return Long.MAX_VALUE;
        }
        return a+b;
    }
    /** Trusted adapter must call the method bound by token; invalid telemetry never suppresses body. */
    public <T>T call(CallbackRegistry.Token token,Object receiver,Body<T> body)throws Throwable {
        long frame=0;
        try {
            frame=enter(token,receiver);
        }
        catch(Throwable instrumentationFailure) {
            increment(errors);
        }
        boolean threw=true;
        try {
            T value=body.run();
            threw=false;
            return value;
        }
        finally {
            try {
                exit(frame,threw);
            }
            catch(Throwable instrumentationFailure) {
                increment(errors);
            }
        }
    }
    /** Reflection exception wrapping and access checks remain Method.invoke's original behavior. */
    public Object invokeReflective(final Method method,final CallbackRegistry.Token token,final Object receiver,final Object... args)throws Throwable {
        CallbackRegistry.Token admitted=registry.reflectionMatches(token,method)?token:null;
        return call(admitted,receiver,new Body<Object>() {
            public Object run()throws Throwable {
                return method.invoke(receiver,args);
            }
        }
        );
    }
    /** Quiescent export only; simultaneous active mutation would not be a coherent snapshot. */
    public synchronized Map<String,Object> snapshot() {
        Map<String,Object> result=new LinkedHashMap<String,Object>();
        List<Map<String,Object>> rows=registry.identities();
        for(Map<String,Object> row:rows) {
            int id=((Number)row.get("slot")).intValue();
            long[] values=completed[id].clone();
            boolean overflow=completedOverflow;
            for(State s:states)if(s!=null) {
                if(s.depth!=0)throw new IllegalStateException("export requires quiescence");
                overflow|=s.overflow;
                for(int j=0;j<6;j++) {
                    if(Long.MAX_VALUE-values[j]<s.totals[id][j]) {
                        overflow=true;
                        values[j]=Long.MAX_VALUE;
                    }
                    else values[j]+=s.totals[id][j];
                }
            }
            row.put("completed_known",values[0]);
            row.put("inclusive_ns",values[1]);
            row.put("exclusive_ns",values[2]);
            row.put("unknown_inclusive",values[3]);
            row.put("unknown_exclusive",values[4]);
            row.put("callback_exceptions",values[5]);
            row.put("counter_overflow",overflow);
        }
        result.put("schema","JAVA_CALLBACK_TELEMETRY_V1");
        result.put("enabled",enabled);
        result.put("rows",rows);
        result.put("metrics_errors",errors.get());
        result.put("diagnostic_counter_overflow",diagnosticOverflow);
        result.put("unattached_calls",unattached.get());
        result.put("invalid_identity_calls",invalid.get());
        result.put("depth_overflow_calls",depthOverflow.get());
        result.put("attached_threads",attached);
        result.put("optimization_authority",false);
        return result;
    }
    @Override public synchronized void close() {
        if(attached!=0)throw new IllegalStateException("detach all workers before close");
        closed=true;
    }
}
