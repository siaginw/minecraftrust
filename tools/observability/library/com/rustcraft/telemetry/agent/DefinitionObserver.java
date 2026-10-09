package com.rustcraft.telemetry.agent;
import java.lang.instrument.*;
import java.lang.ref.WeakReference;
import java.security.ProtectionDomain;
import java.util.*;
import com.rustcraft.coremod.CanonicalClassIdentityV2;
/** Passive final-definition collector. No transformation, matching-name authority or retransform. */
public final class DefinitionObserver implements ClassFileTransformer  {
    private static final int CAPACITY=64, MAX_CLASS_BYTES=2*1024*1024;
    private static final Definition[] definitions=new Definition[CAPACITY];
    private static long nextGeneration=1;
    private static long dropped;
    private static long errors;
    private static long rejectedDefinitionAttempts;
    private static boolean admissionClosed;
    public static final class Definition  {
        private final WeakReference<ClassLoader> loader;
        private final String name;
        private final byte[] bytes;
        private final long generation;
        private volatile boolean current;
        private Definition(ClassLoader l,String n,byte[] b,long g) {
            loader=new WeakReference<ClassLoader>(l);
            name=n;
            bytes=b==null?null:b.clone();
            generation=g;
            current=b!=null;
        }
        public long generation() {
            return generation;
        }
        public CanonicalClassIdentityV2.Result identity() {
            synchronized(definitions) {
                if(!current)throw new IllegalStateException("invalidated definition");
                return CanonicalClassIdentityV2.identify(bytes);
            }
        }
        public boolean matches(Class<?> type) {
            return current && type.getClassLoader()==loader.get() && type.getName().equals(name);
        }
    }
    public static void premain(String args,Instrumentation instrumentation) {
        instrumentation.addTransformer(new DefinitionObserver(),false);
    }
    public byte[] transform(ClassLoader loader,String name,Class<?> old,ProtectionDomain domain,byte[] bytes) {
        try  {
            if(name==null||loader==null)return null;
            // Collection filter only. Admission later requires actual Class/loader plus whole-class V2.
            if(!name.startsWith("com/rustcraft/observability/fixtures/")&&!name.equals("net/minecraft/world/gen/NoiseGeneratorOctaves"))return null;
            synchronized(definitions) {
                int free=-1;
                boolean seen=false;
                String binaryName=name.replace('/','.');
                for(int i=0;i<CAPACITY;i++) {
                    Definition d=definitions[i];
                    if(d!=null&&d.loader.get()==null) {
                        definitions[i]=null;
                        d=null;
                    }
                    if(d==null) {
                        if(free<0)free=i;
                    }
                    else if(d.loader.get()==loader&&d.name.equals(binaryName)) {
                        // An attempt is not a successful definition. Retain a tombstone until
                        // loader collection, so rejection by the JVM cannot resurrect this binding.
                        d.current=false;
                        seen=true;
                    }
                }
                if(old!=null||seen) {
                    if(rejectedDefinitionAttempts!=Long.MAX_VALUE)rejectedDefinitionAttempts++;
                    if(!seen) {
                        if(free>=0)definitions[free]=new Definition(loader,binaryName,null,0);
                        else admissionClosed=true; // No room to remember this rejected identity.
                    }
                    return null;
                }
                if(admissionClosed||bytes==null||bytes.length>MAX_CLASS_BYTES||free<0||nextGeneration==Long.MAX_VALUE) {
                    if(dropped!=Long.MAX_VALUE)dropped++;
                    // A dropped first attempt must never become an admitted duplicate later.
                    if(free>=0)definitions[free]=new Definition(loader,binaryName,null,0);
                    else admissionClosed=true;
                    return null;
                }
                definitions[free]=new Definition(loader,binaryName,bytes,nextGeneration++);
            }
        }
        catch(Throwable unavailable)  {
            synchronized(definitions) {
                if(errors!=Long.MAX_VALUE)errors++;
                admissionClosed=true;
                for(Definition d:definitions)if(d!=null)d.current=false;
            }
        }
        return null;
    }
    public static long rejectedDefinitionAttempts() {
        synchronized(definitions) {
            return rejectedDefinitionAttempts;
        }
    }
    public static long errors() {
        synchronized(definitions) {
            return errors;
        }
    }
    public static Definition observed(Class<?> type) {
        synchronized(definitions) {
            for(Definition d:definitions)if(d!=null&&d.matches(type))return d;
            return null;
        }
    }
    public static long dropped() {
        synchronized(definitions) {
            return dropped;
        }
    }
}
