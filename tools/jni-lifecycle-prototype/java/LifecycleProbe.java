import java.lang.ref.WeakReference;
import java.nio.file.*;
import java.util.*;

public final class LifecycleProbe {
    static native int ping();
    static native int localRefs(Object value,int rounds);
    static native int callback(Object value,int mode);
    static native int worker(Object value,boolean fail);
    static native boolean sameClass(Object value,Class<?> expected);
    static native int retain(Object value);
    static native int release();

    public static final class Callback {
        final RuntimeException problem=new IllegalStateException("exact fixture throwable");
        public int value() { return 73; }
        public int fail() { throw problem; }
    }
    private static final class Isolated extends ClassLoader {
        final byte[] bytes;
        Isolated(byte[] bytes) { super(null);this.bytes=bytes; }
        Class<?> payload() { return defineClass("LoaderPayload",bytes,0,bytes.length); }
    }
    private interface Action { void run(); }
    private static final List<String> passed=new ArrayList<String>();
    private static void check(String name,boolean success) {
        if(!success)throw new AssertionError(name);passed.add(name);
        System.out.println("{\"event\":\"CHECK\",\"name\":\""+name+"\"}");
    }
    private static Throwable thrown(String name,Action action) {
        Throwable observed=null;
        try {action.run();} catch(Throwable error) {observed=error;}
        check(name,observed!=null);return observed;
    }
    private static WeakReference<Object> retainTemporary() {
        Object value=new Object();check("retain-first",retain(value)==1);
        return new WeakReference<Object>(value);
    }
    private static String collectionObservation(WeakReference<Object> reference) throws Exception {
        for(int i=0;i<40;i++) {
            if(reference.get()==null)return "COLLECTED";
            byte[][] pressure=new byte[4][];
            for(int j=0;j<pressure.length;j++)pressure[j]=new byte[128*1024];
            System.gc();Thread.sleep(10);
        }
        return reference.get()==null?"COLLECTED":"INCONCLUSIVE_NOT_COLLECTED";
    }
    public static void main(String[] args) throws Exception {
        if((args.length!=4 && args.length!=5) || !args[2].matches("[0-9a-f]{32}") || !args[3].matches("[0-9a-f]{64}"))throw new IllegalArgumentException("library, loader bytes, session, challenge required");
        System.out.println("{\"event\":\"BEFORE_PATHS\"}");
        System.load(Paths.get(args[0]).toAbsolutePath().toString());
        System.out.println("{\"event\":\"LIBRARY_LOADED\"}");
        final Callback callback=new Callback();
        if(args.length==5) {
            String mode=args[4];
            if(mode.equals("load-only"))check("loaded",true);
            else if(mode.equals("ping"))check("ping",ping()==42);
            else if(mode.equals("local"))check("local",localRefs(callback,10000)==80000);
            else if(mode.equals("callback"))check("callback",callback(callback,0)==73);
            else if(mode.equals("direct"))check("direct",thrown("direct-thrown",new Action(){public void run(){callback(callback,1);}})==callback.problem);
            else if(mode.equals("worker"))check("worker",worker(callback,false)==73);
            else if(mode.equals("worker-fail"))check("worker-fail",thrown("worker-thrown",new Action(){public void run(){worker(callback,true);}})==callback.problem);
            else if(mode.equals("badsig"))thrown("badsig",new Action(){public void run(){callback(callback,3);}});
            else if(mode.equals("null"))thrown("null",new Action(){public void run(){callback(null,0);}});
            else if(mode.equals("panic"))thrown("panic",new Action(){public void run(){callback(callback,2);}});
            else if(mode.equals("class"))check("class",sameClass(callback,Callback.class)&&!sameClass(callback,Object.class));
            else if(mode.equals("global"))check("global",retain(callback)==1&&release()==1&&release()==0);
            else throw new IllegalArgumentException("unknown isolated mode");
            System.out.println("{\"schema\":\"JNI_ISOLATED_PROBE_V1\",\"session\":\""+args[2]+"\",\"challenge\":\""+args[3]+"\",\"mode\":\""+mode+"\",\"status\":\"PASS\"}");return;
        }
        check("ping",ping()==42);
        check("local-frame-zero",localRefs(callback,0)==0);
        check("local-frame-80000-refs-identity",localRefs(callback,10000)==80000);
        check("local-frame-negative-bound",localRefs(callback,-1)==-1);
        check("local-frame-upper-bound",localRefs(callback,10001)==-1);
        check("callback-success",callback(callback,0)==73);
        Throwable direct=thrown("direct-exception-observed",new Action(){public void run(){callback(callback,1);}});
        check("direct-exact-throwable-identity",direct==callback.problem);
        check("direct-exception-retry",callback(callback,0)==73);
        for(int i=0;i<32;i++)check("worker-success-"+i,worker(callback,false)==73);
        Throwable threaded=thrown("worker-exception-observed",new Action(){public void run(){worker(callback,true);}});
        check("worker-exact-throwable-identity",threaded==callback.problem);
        check("worker-exception-retry",worker(callback,false)==73);
        Throwable signature=thrown("bad-signature-rejected",new Action(){public void run(){callback(callback,3);}});
        check("bad-signature-is-method-error",signature instanceof NoSuchMethodError || signature.toString().contains("Method not found"));
        check("bad-signature-retry",callback(callback,0)==73);
        Throwable nullError=thrown("null-callback-rejected",new Action(){public void run(){callback(null,0);}});
        check("null-callback-not-control-sentinel",nullError instanceof RuntimeException || nullError instanceof NullPointerException);
        check("null-retry",callback(callback,0)==73);
        Throwable panic=thrown("panic-contained",new Action(){public void run(){callback(callback,2);}});
        check("panic-runtime-exception",panic instanceof RuntimeException);
        check("panic-retry",callback(callback,0)==73);
        check("class-exact",sameClass(callback,Callback.class));
        check("class-wrong-supertype",!sameClass(callback,Object.class));
        byte[] bytes=Files.readAllBytes(Paths.get(args[1]));
        Class<?> a=new Isolated(bytes).payload(),b=new Isolated(bytes).payload();
        Object instance=a.newInstance();
        check("same-name-distinct-loader-setup",a.getName().equals(b.getName())&&a!=b);
        check("same-name-own-class",sameClass(instance,a));
        check("same-name-other-loader-rejected",!sameClass(instance,b));
        check("release-empty",release()==0);
        WeakReference<Object> held=retainTemporary();
        for(int i=0;i<4;i++){System.gc();Thread.sleep(10);}
        check("global-reference-prevents-collection",held.get()!=null);
        check("global-reference-bounded-single-slot",retain(new Object())==-1);
        check("release-held",release()==1);
        check("release-idempotent",release()==0);
        String gc=collectionObservation(held);
        check("retain-release-retry",retain(new Object())==1&&release()==1);
        check("final-ping",ping()==42);
        StringBuilder names=new StringBuilder("[");
        for(String name:passed){if(names.length()>1)names.append(',');names.append('"').append(name).append('"');}names.append(']');
        System.out.println("{\"schema\":\"JNI_LIFECYCLE_PROBE_V1\",\"session\":\""+args[2]+"\",\"challenge\":\""+args[3]+"\",\"status\":\"PASS\",\"production_authority\":false,\"checks\":"+names+",\"gc_after_release\":\""+gc+"\",\"gc_proves_leak_freedom\":false}");
    }
}
