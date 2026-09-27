package com.rustcraft.telemetry;
import com.rustcraft.coremod.CanonicalClassIdentityV2;
import com.rustcraft.telemetry.agent.DefinitionObserver;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
/** Bounded measurement identity registry. Never produces an optimization grant. */
public final class CallbackRegistry implements AutoCloseable  {
    public static final int MAX_METHODS=64,MAX_OWNERS=16,MAX_DEPENDENCIES=8;
    private static final AtomicLong ORIGINS=new AtomicLong(1);
    private final long origin=next(ORIGINS);
    final Entry[] entries=new Entry[MAX_METHODS];
    private final Owner[] owners=new Owner[MAX_OWNERS];
    private int count,ownerCount;
    private volatile boolean closed;
    private static long next(AtomicLong counter) {
        for(;;) {
            long v=counter.get();
            if(v<=0||v==Long.MAX_VALUE)throw new IllegalStateException("identity exhausted");
            if(counter.compareAndSet(v,v+1))return v;
        }
    }
    public static final class Owner  {
        private final CallbackRegistry registry;
        private Object identity;
        public final String label;
        private Owner(CallbackRegistry r,Object i,String n) {
            registry=r;
            identity=i;
            label=n;
        }
    }
    public static final class Dependency  {
        private final CallbackRegistry registry;
        private volatile long generation=1;
        private volatile boolean current=true;
        private Dependency(CallbackRegistry r) {
            registry=r;
        }
        public void revoke() {
            current=false;
            if(generation!=Long.MAX_VALUE)generation++;
        }
    }
    public static final class Token  {
        private final CallbackRegistry registry;
        private final long origin;
        final int slot;
        private Token(CallbackRegistry r,int s) {
            registry=r;
            origin=r.origin;
            slot=s;
        }
        public int slot() {
            return slot;
        }
    }
    static final class Entry  {
        final Token token;
        final String ownerLabel,methodName,descriptor,semantic,order,raw,receiverName,dependencyCoverage;
        final long definitionGeneration;
        Method method;
        Class<?> receiver;
        DefinitionObserver.Definition definition;
        Dependency[] dependencies;
        long[] generations;
        volatile boolean active=true;
        Entry(Token token,Owner owner,Method m,Class<?> receiver,DefinitionObserver.Definition definition,Dependency[] dependencies) {
            this.token=token;
            this.ownerLabel=owner.label;
            method=m;
            this.receiver=receiver;
            receiverName=receiver==null?"STATIC_NO_RECEIVER":receiver.getName();
            dependencyCoverage=dependencies.length==0?"UNDECLARED":"DECLARED_LOCAL_REVOCABLE_TOKENS";
            this.definition=definition;
            this.definitionGeneration=definition.generation();
            methodName=m.getDeclaringClass().getName()+"."+m.getName();
            descriptor=org.objectweb.asm.Type.getMethodDescriptor(m);
            CanonicalClassIdentityV2.Result id=definition.identity();
            semantic=id.semanticSha256;
            order=id.declarationOrderSha256;
            raw=id.rawSha256;
            this.dependencies=dependencies.clone();
            generations=new long[dependencies.length];
            for(int i=0;i<dependencies.length;i++)generations[i]=dependencies[i].generation;
        }
        boolean current(Object object) {
            Method m=method;
            DefinitionObserver.Definition def=definition;
            Dependency[] deps=dependencies;
            long[] gens=generations;
            Class<?> exact=receiver;
            if(!active||m==null||def==null||deps==null||gens==null||!def.matches(m.getDeclaringClass()))return false;
            if(Modifier.isStatic(m.getModifiers())) {
                if(object!=null)return false;
            }
            else if(object==null||object.getClass()!=exact)return false;
            for(int i=0;i<deps.length;i++)if(!deps[i].current||deps[i].generation!=gens[i])return false;
            return active;
        }
        boolean bindingCurrent() {
            Method m=method;
            DefinitionObserver.Definition def=definition;
            Dependency[] deps=dependencies;
            long[] gens=generations;
            if(!active||m==null||def==null||deps==null||gens==null||!def.matches(m.getDeclaringClass()))return false;
            for(int i=0;i<deps.length;i++)if(!deps[i].current||deps[i].generation!=gens[i])return false;
            return active;
        }
        void clear() {
            active=false;
            method=null;
            receiver=null;
            definition=null;
            dependencies=null;
            generations=null;
        }
    }
    public synchronized Owner registerOwner(Object actualOwner,String label) {
        if(closed||actualOwner==null||label==null||label.length()>64||label.isEmpty())throw new IllegalArgumentException("owner");
        for(int i=0;i<ownerCount;i++)if(owners[i].identity==actualOwner)return owners[i];
        if(ownerCount==MAX_OWNERS)throw new IllegalStateException("owner capacity");
        Owner o=new Owner(this,actualOwner,label);
        owners[ownerCount++]=o;
        return o;
    }
    public synchronized Dependency dependency() {
        if(closed)throw new IllegalStateException("closed");
        return new Dependency(this);
    }
    public synchronized Token bind(Owner owner,Method method,Class<?> exactReceiver,Dependency... dependencies) {
        if(closed||owner.registry!=this||owner.identity==null||count==MAX_METHODS||dependencies.length>MAX_DEPENDENCIES)throw new IllegalArgumentException("registry/capacity");
        if(!Modifier.isPublic(method.getModifiers()))throw new IllegalArgumentException("public methods only");
        if(Modifier.isStatic(method.getModifiers())) {
            if(exactReceiver!=null)throw new IllegalArgumentException("static receiver");
        }
        else  {
            try {
                if(exactReceiver==null||!exactReceiver.getMethod(method.getName(),method.getParameterTypes()).equals(method))throw new IllegalArgumentException("receiver resolves different method");
            }
            catch(NoSuchMethodException e) {
                throw new IllegalArgumentException(e);
            }
        }
        for(Dependency d:dependencies)if(d==null||d.registry!=this||!d.current)throw new IllegalArgumentException("dependency");
        DefinitionObserver.Definition definition=DefinitionObserver.observed(method.getDeclaringClass());
        if(definition==null)throw new IllegalArgumentException("unobserved final definition");
        Token token=new Token(this,count);
        Entry entry=new Entry(token,owner,method,exactReceiver,definition,dependencies);
        entries[count]=entry;
        count++;
        return token;
    }
    boolean accepts(Token token,Object receiver) {
        if(token==null||token.registry!=this||token.origin!=origin||closed)return false;
        Entry e=entries[token.slot];
        return e!=null&&e.current(receiver);
    }
    boolean reflectionMatches(Token token,Method method) {
        return token!=null&&token.registry==this&&token.origin==origin&&!closed&&entries[token.slot]!=null&&method.equals(entries[token.slot].method);
    }
    public synchronized void revoke(Token token) {
        if(token==null||token.registry!=this||token.origin!=origin)throw new IllegalArgumentException("foreign token");
        entries[token.slot].clear();
    }
    public int size() {
        return count;
    }
    public boolean optimizationAuthority() {
        return false;
    }
    public List<Map<String,Object>> identities() {
        List<Map<String,Object>> result=new ArrayList<Map<String,Object>>();
        for(int i=0;i<count;i++) {
            Entry e=entries[i];
            Map<String,Object> row=new LinkedHashMap<String,Object>();
            row.put("slot",i);
            row.put("origin",origin);
            row.put("owner",e.ownerLabel);
            row.put("owner_coverage","DECLARED_ACTUAL_CONTAINER_IDENTITY_NOT_AUTHORITY");
            row.put("method",e.methodName);
            row.put("descriptor",e.descriptor);
            row.put("canonical_schema","CANONICAL_ID_V2");
            row.put("semantic_sha256",e.semantic);
            row.put("declaration_order_sha256",e.order);
            row.put("raw_sha256",e.raw);
            row.put("definition_generation",e.definitionGeneration);
            row.put("class_and_loader_observation_token",e.definitionGeneration);
            row.put("exact_receiver_class",e.receiverName);
            row.put("capability_dependency_coverage",e.dependencyCoverage);
            row.put("effects_coverage","UNQUALIFIED");
            row.put("registered_not_explicitly_revoked",e.active);
            row.put("measurement_binding_current",e.bindingCurrent());
            row.put("optimization_authority",false);
            result.add(row);
        }
        return result;
    }
    @Override public synchronized void close() {
        closed=true;
        for(int i=0;i<count;i++)entries[i].clear();
        for(int i=0;i<ownerCount;i++)owners[i].identity=null;
    }
    Method reflectiveMethod(Token token) {
        if(token==null||token.registry!=this||closed)throw new IllegalArgumentException("foreign/closed token");
        Method m=entries[token.slot].method;
        if(m==null)throw new IllegalArgumentException("revoked token");
        return m;
    }
}
