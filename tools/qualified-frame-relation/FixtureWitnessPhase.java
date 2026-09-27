import com.sun.management.HotSpotDiagnosticMXBean;
import java.io.*;
import java.lang.management.ManagementFactory;
import java.lang.reflect.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.ZipFile;

/** Actual object-identity/hierarchy/verification observations for ONE closed fixture.
 * This deliberately cannot collect a Minecraft or Forge runtime.
 */
public final class FixtureWitnessPhase {
    static final String NAME="fixture.Writer";
    static byte[] read(InputStream in)throws Exception {
        try {ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] block=new byte[8192];int n;
            while((n=in.read(block))!=-1){if(out.size()+n>4*1024*1024)throw new IOException("byte bound");out.write(block,0,n);}return out.toByteArray();
        }finally{in.close();}
    }
    static String sha(byte[] data)throws Exception {
        StringBuilder out=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(data))out.append(String.format("%02x",b&255));return out.toString();
    }
    static String fileHash(Path path)throws Exception {
        MessageDigest md=MessageDigest.getInstance("SHA-256");try(InputStream in=Files.newInputStream(path)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)md.update(b,0,n);}
        StringBuilder out=new StringBuilder();for(byte b:md.digest())out.append(String.format("%02x",b&255));return out.toString();
    }
    static Map<String,Object> map(Object... parts){Map<String,Object> out=new LinkedHashMap<String,Object>();for(int i=0;i<parts.length;i+=2)out.put((String)parts[i],parts[i+1]);return out;}
    static final class ExactLoader extends ClassLoader {
        final byte[] target;final List<String> requests=new ArrayList<String>();
        ExactLoader(byte[] bytes){super(null);target=bytes.clone();}
        protected synchronized Class<?> loadClass(String name,boolean resolve)throws ClassNotFoundException {
            requests.add(name);Class<?> c=findLoadedClass(name);
            if(c==null){if(name.equals(NAME))c=defineClass(name,target,0,target.length);else if(name.startsWith("java."))c=super.loadClass(name,false);else throw new ClassNotFoundException("closed fixture loader: "+name);}
            if(resolve)resolveClass(c);return c;
        }
    }
    static final class Graph {
        final IdentityHashMap<Class<?>,String> ids=new IdentityHashMap<Class<?>,String>();
        final ArrayDeque<Class<?>> pending=new ArrayDeque<Class<?>>();
        final List<Map<String,Object>> rows=new ArrayList<Map<String,Object>>();
        final String prefix,boot,custom,rtHash,bundleHash;final ExactLoader loader;final byte[] target;
        Graph(String prefix,ExactLoader loader,byte[] bytes,String rt,String bundle){this.prefix=prefix;boot=prefix+":bootstrap";custom=prefix+":fixture";this.loader=loader;target=bytes;rtHash=rt;bundleHash=bundle;}
        String id(Class<?> c){if(c==null)return null;String value=ids.get(c);if(value==null){value=prefix+":class:"+ids.size();ids.put(c,value);pending.add(c);}return value;}
        void finish()throws Exception {
            while(!pending.isEmpty()){
                Class<?> c=pending.remove();ClassLoader defining=c.getClassLoader();
                if(defining!=null&&defining!=loader)throw new SecurityException("foreign defining loader");
                boolean derived=c.isArray()||c.isPrimitive();String name=c.getName().replace('.','/');
                if(c.isPrimitive()){String[] names={"boolean","byte","char","double","float","int","long","short","void"};String[] desc={"Z","B","C","D","F","I","J","S","V"};for(int i=0;i<names.length;i++)if(name.equals(names[i]))name=desc[i];}
                List<String> interfaces=new ArrayList<String>();for(Class<?> x:c.getInterfaces())interfaces.add(id(x));
                String raw=null,artifact=null;
                if(!derived){
                    if(defining==loader){if(!c.getName().equals(NAME))throw new SecurityException("extra fixture definition");raw=sha(target);artifact=bundleHash;}
                    else {InputStream stream=c.getResourceAsStream("/"+name+".class");if(stream==null)throw new IOException("missing bootstrap definition resource");raw=sha(read(stream));artifact=rtHash;}
                }
                rows.add(map("id",id(c),"name",name,"kind",c.isArray()?"array":c.isPrimitive()?"primitive":"class","is_interface",c.isInterface(),
                    "loader",defining==loader?custom:boot,"raw_sha256",raw,"artifact_sha256",artifact,
                    "super",id(c.getSuperclass()),"interfaces",interfaces,"component",id(c.getComponentType())));
            }
        }
    }
    static String quote(String s){StringBuilder out=new StringBuilder("\"");for(char c:s.toCharArray()){if(c=='"'||c=='\\')out.append('\\').append(c);else if(c<32||c>126)out.append(String.format("\\u%04x",(int)c));else out.append(c);}return out.append('"').toString();}
    static String json(Object x){if(x==null)return "null";if(x instanceof String)return quote((String)x);if(x instanceof Boolean||x instanceof Number)return x.toString();if(x instanceof List){StringBuilder s=new StringBuilder("[");for(Object v:(List<?>)x){if(s.length()>1)s.append(',');s.append(json(v));}return s.append(']').toString();}StringBuilder s=new StringBuilder("{");for(Map.Entry<?,?>e:((Map<?,?>)x).entrySet()){if(s.length()>1)s.append(',');s.append(quote((String)e.getKey())).append(':').append(json(e.getValue()));}return s.append('}').toString();}
    public static void main(String[] args)throws Exception {
        if(args.length!=10)throw new IllegalArgumentException("phase session bundle entry expectedHash types queries rtJar vmInventoryHash policyFile");
        String phase=args[0],session=args[1],expected=args[4];
        if(!(phase.equals("pre")||phase.equals("post"))||!session.matches("[0-9a-f]{32}")||!expected.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("binding syntax");
        if(!ManagementFactory.getRuntimeMXBean().getInputArguments().equals(Arrays.asList("-Xverify:all"))||System.getSecurityManager()!=null)throw new SecurityException("fixture process policy");
        HotSpotDiagnosticMXBean bean=ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
        boolean local=Boolean.parseBoolean(bean.getVMOption("BytecodeVerificationLocal").getValue()),remote=Boolean.parseBoolean(bean.getVMOption("BytecodeVerificationRemote").getValue());
        if(!local||!remote)throw new SecurityException("verification flags");
        byte[] bytes;try(ZipFile bundle=new ZipFile(args[2])){bytes=read(bundle.getInputStream(bundle.getEntry(args[3])));}
        if(!sha(bytes).equals(expected))throw new SecurityException("exact class bytes");
        String bundleHash=fileHash(Paths.get(args[2])),rtHash=fileHash(Paths.get(args[7])),policy=fileHash(Paths.get(args[9]));
        ExactLoader loader=new ExactLoader(bytes);Class<?> target=Class.forName(NAME,false,loader);
        if(target.getClassLoader()!=loader)throw new SecurityException("wrong target loader");
        // This pinned HotSpot API observes actual initialization state. It is
        // accessed reflectively to avoid adding a production Unsafe dependency.
        Class<?> unsafeClass=Class.forName("sun.misc.Unsafe");Field singleton=unsafeClass.getDeclaredField("theUnsafe");singleton.setAccessible(true);
        Object unsafe=singleton.get(null);Method should=unsafeClass.getMethod("shouldBeInitialized",Class.class);
        boolean uninitializedBefore=(Boolean)should.invoke(unsafe,target);
        target.getDeclaredMethods();
        boolean uninitializedAfter=(Boolean)should.invoke(unsafe,target);
        if(!uninitializedBefore||!uninitializedAfter)throw new SecurityException("target initialized during verification");
        Graph graph=new Graph(session+":"+phase,loader,bytes,rtHash,bundleHash);String targetId=graph.id(target);
        List<Map<String,Object>> resolutions=new ArrayList<Map<String,Object>>(),queries=new ArrayList<Map<String,Object>>();
        Map<String,Class<?>> resolved=new LinkedHashMap<String,Class<?>>();
        for(String name:Files.readAllLines(Paths.get(args[5]),java.nio.charset.StandardCharsets.UTF_8)){
            try{Class<?> c=Class.forName(name.replace('/','.'),false,loader);resolved.put(name,c);resolutions.add(map("type",name,"class_id",graph.id(c),"error",null));}
            catch(ClassNotFoundException error){resolved.put(name,null);resolutions.add(map("type",name,"class_id",null,"error",error.getClass().getName()));}
        }
        for(String line:Files.readAllLines(Paths.get(args[6]),java.nio.charset.StandardCharsets.UTF_8)){
            String[] names=line.split("\t",-1);if(names.length!=2||!resolved.containsKey(names[0])||!resolved.containsKey(names[1]))throw new IllegalArgumentException("query inventory");
            Class<?> source=resolved.get(names[0]),destination=resolved.get(names[1]);queries.add(map("source",names[0],"target",names[1],"value",source==null||destination==null?null:destination.isAssignableFrom(source)));
        }
        graph.finish();
        if(!(Boolean)should.invoke(unsafe,target))throw new SecurityException("target initialized during hierarchy inspection");
        String impl=sha(read(FixtureWitnessPhase.class.getResourceAsStream("/FixtureWitnessPhase$ExactLoader.class")));
        List<Object> loaders=Arrays.<Object>asList(map("id",graph.boot,"parent",null,"implementation_sha256",args[8],"configuration_sha256",rtHash,"policy_sha256",policy),
            map("id",graph.custom,"parent",graph.boot,"implementation_sha256",impl,"configuration_sha256",policy,"policy_sha256",policy));
        Map<String,Object> verification=map("name","fixture/Writer","class_id",targetId,"raw_sha256",sha(bytes),"status","VERIFIED","trigger","PINNED_HOTSPOT_GET_DECLARED_METHODS_V1","verify_local",local,"verify_remote",remote,"initialized",false);
        Map<String,Object> data=map("loaders",loaders,"classes",graph.rows,"resolutions",resolutions,"verification",Arrays.asList(verification),"assignability",queries);
        System.out.println(json(map("schema","CLOSED_FIXTURE_PHASE_V1","phase",phase,"session",session,"data",data,
            "observed",map("input_arguments",ManagementFactory.getRuntimeMXBean().getInputArguments(),"loader_requests",loader.requests,
                "uninitialized_before",uninitializedBefore,"uninitialized_after",uninitializedAfter,"uninitialized_after_hierarchy",should.invoke(unsafe,target),
                "initialization_state_probe","PINNED_SUN_MISC_UNSAFE_SHOULD_BE_INITIALIZED", "runtime_version",System.getProperty("java.runtime.version"),
                "vm_version",System.getProperty("java.vm.version"),"bundle_sha256",bundleHash,"rt_jar_sha256",rtHash,"loader_implementation_sha256",impl))));
    }
}
