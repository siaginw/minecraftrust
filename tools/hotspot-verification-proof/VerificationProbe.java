import com.sun.management.HotSpotDiagnosticMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Pins exact bytes and loader object before the source-justified link trigger.
 * This deliberately does not instantiate Target or call any Target method.
 */
public final class VerificationProbe {
    static final String TARGET="proof.Target", SENTINEL="rustcraft.verification.target.initialized";
    static String hash(byte[] bytes)throws Exception {
        StringBuilder b=new StringBuilder();for(byte x:MessageDigest.getInstance("SHA-256").digest(bytes))b.append(String.format("%02x",x&255));return b.toString();
    }
    static final class ExactLoader extends ClassLoader {
        final byte[] bytes;
        final List<String> lookups=new ArrayList<String>();
        ExactLoader(byte[] bytes)throws Exception {super(null);this.bytes=bytes.clone();}
        protected synchronized Class<?> loadClass(String name,boolean resolve)throws ClassNotFoundException {
            lookups.add(name);
            Class<?> c=findLoadedClass(name);
            if(c==null) {
                if(name.equals(TARGET))c=defineClass(name,bytes,0,bytes.length);
                else if(name.startsWith("java."))c=super.loadClass(name,false);
                else throw new ClassNotFoundException("outside closed fixture hierarchy: "+name);
            }
            // Never mistake resolveClass for verification: this HotSpot's
            // JVM_ResolveClass is a no-op. The explicit later trigger is audited.
            if(resolve)resolveClass(c);
            return c;
        }
    }
    static void bound(Class<?> target,ExactLoader expected)throws Exception {
        if(!target.getName().equals(TARGET)||target.getClassLoader()!=expected)
            throw new SecurityException("exact loader-object binding refused");
    }
    static String q(String s) {
        StringBuilder out=new StringBuilder("\"");for(char c:s.toCharArray()){
            if(c=='"'||c=='\\')out.append('\\').append(c);
            else if(c<32||c>126)out.append(String.format("\\u%04x",(int)c));else out.append(c);
        }return out.append('"').toString();
    }
    static String json(Object x) {
        if(x==null)return "null";if(x instanceof String)return q((String)x);
        if(x instanceof Boolean||x instanceof Number)return x.toString();
        if(x instanceof List){StringBuilder s=new StringBuilder("[");for(Object v:(List<?>)x){if(s.length()>1)s.append(',');s.append(json(v));}return s.append(']').toString();}
        StringBuilder s=new StringBuilder("{");for(Map.Entry<?,?>e:((Map<?,?>)x).entrySet()){if(s.length()>1)s.append(',');s.append(q((String)e.getKey())).append(':').append(json(e.getValue()));}return s.append('}').toString();
    }
    public static void main(String[] args)throws Exception {
        if(args.length<5)throw new IllegalArgumentException("FILE EXPECTED_SHA SESSION CHALLENGE MODE [FRAME_TYPES...]");
        if(!args[1].matches("[0-9a-f]{64}")||!args[2].matches("[0-9a-f]{32}")||!args[3].matches("[0-9a-f]{64}"))throw new IllegalArgumentException("binding syntax");
        Map<String,Object> row=new LinkedHashMap<String,Object>();
        row.put("schema","HOTSPOT_VERIFICATION_PROBE_V1");row.put("session",args[2]);row.put("challenge",args[3]);row.put("mode",args[4]);
        row.put("production_authority",false);row.put("target_initialized_before",System.getProperty(SENTINEL)!=null);
        row.put("runtime_version",System.getProperty("java.runtime.version"));row.put("vm_version",System.getProperty("java.vm.version"));row.put("vm_vendor",System.getProperty("java.vm.vendor"));
        row.put("input_arguments",ManagementFactory.getRuntimeMXBean().getInputArguments());
        HotSpotDiagnosticMXBean bean=ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
        boolean local=Boolean.parseBoolean(bean.getVMOption("BytecodeVerificationLocal").getValue());
        boolean remote=Boolean.parseBoolean(bean.getVMOption("BytecodeVerificationRemote").getValue());
        row.put("verify_local",local);row.put("verify_remote",remote);
        row.put("security_manager",System.getSecurityManager()!=null);
        row.put("defined",false);row.put("trigger_started",false);row.put("trigger_returned",false);
        if(!local||!remote)row.put("status","REFUSED_VERIFY_FLAGS");
        else {
            Path path=Paths.get(args[0]);if(Files.size(path)>1048576)throw new IllegalArgumentException("fixture bound");
            byte[] bytes=Files.readAllBytes(path);String actual=hash(bytes);row.put("actual_sha256",actual);row.put("expected_sha256",args[1]);
            if(!actual.equals(args[1]))row.put("status","REFUSED_HASH");
            else {
                ExactLoader loader=new ExactLoader(bytes);
                Class<?> target=Class.forName(TARGET,false,loader);row.put("defined",true);
                row.put("defining_loader_is_exact",target.getClassLoader()==loader);
                if(args[4].equals("foreign-loader")) {
                    ExactLoader foreign=new ExactLoader(bytes);Class<?> other=Class.forName(TARGET,false,foreign);
                    row.put("same_binary_name",other.getName().equals(target.getName()));row.put("same_class_object",other==target);
                    try {bound(other,loader);throw new AssertionError("foreign loader accepted");}
                    catch(SecurityException expected){row.put("status","REFUSED_LOADER");}
                } else {
                    bound(target,loader);row.put("target_initialized_after_define",System.getProperty(SENTINEL)!=null);
                    row.put("trigger_started",true);
                    try {
                        java.lang.reflect.Method[] methods=target.getDeclaredMethods();
                        row.put("trigger_returned",true);row.put("declared_method_count",methods.length);
                        List<String> methodNames=new ArrayList<String>();row.put("method_names",methodNames);
                        for(java.lang.reflect.Method m:methods)methodNames.add(m.getName()+":"+m.getModifiers());
                        row.put("status","VERIFIED_BY_PINNED_LINK_TRIGGER");
                        // Keep verification's actual requests separate: it can
                        // accept a null value without resolving its frame type.
                        row.put("verification_loader_requests",new ArrayList<String>(loader.lookups));
                        List<Object> bindings=new ArrayList<Object>();boolean complete=true;
                        for(int i=5;i<args.length;i++) {
                            String internal=args[i];
                            if(!internal.matches("[A-Za-z_$][A-Za-z0-9_$/]*"))throw new IllegalArgumentException("fixture frame type syntax");
                            try {
                                Class<?> type=Class.forName(internal.replace('/','.'),false,loader);
                                String binding;
                                if(type==target && type.getClassLoader()==loader)binding="BOUND_EXACT_TARGET";
                                else if(type.getClassLoader()==null && type.getName().equals(internal.replace('/','.')))binding="BOUND_BOOTSTRAP";
                                else throw new SecurityException("frame type loader outside fixture hierarchy");
                                bindings.add(Arrays.asList(internal,binding,null));
                            } catch(ClassNotFoundException error) {
                                complete=false;bindings.add(Arrays.asList(internal,"UNRESOLVED",error.getClass().getName()));
                            }
                        }
                        row.put("frame_type_bindings",bindings);row.put("frame_type_binding_complete",complete);
                    } catch(VerifyError error) {
                        row.put("status","REJECTED_VERIFY_ERROR");row.put("error_class",error.getClass().getName());row.put("error_message",error.getMessage());
                    } catch(LinkageError error) {
                        row.put("status","REJECTED_LINKAGE_ERROR");row.put("error_class",error.getClass().getName());row.put("error_message",error.getMessage());
                    }
                }
                row.put("loader_requests",loader.lookups);
            }
        }
        row.put("target_initialized_after",System.getProperty(SENTINEL)!=null);
        if(Boolean.TRUE.equals(row.get("target_initialized_before"))||Boolean.TRUE.equals(row.get("target_initialized_after")))throw new AssertionError("Target class initializer executed");
        System.out.println(json(row));
    }
}
