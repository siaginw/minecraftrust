package com.rustcraft.fusion;

import com.google.gson.GsonBuilder;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import net.minecraft.world.gen.NoiseGeneratorOctaves;

/** New synthetic operation using actual runtime noise, never a Minecraft terrain oracle. */
public strictfp final class FusionProbe {
    static native long create(long seed, long epoch);
    static native int destroy(long id);
    static native int discard(long id);
    static native int noise(long id, int x, int z, int h, double[] a, double[] b);
    static native int terrain(int h, float shift, double[] a, double[] b, int[] out);
    static native int fused(long id, long epoch, int x, int z, int h, float shift, int observer, int[] out);
    static native int trace(long id, int x, int z, int h, float shift, double[] out);
    static native int metrics(long[] out, int reset);
    static volatile long sink;
    static Path output;
    static int controls;
    static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); controls++; }
    static void ok(int status) { if(status!=0) throw new IllegalStateException("native status="+status); }
    static final class Generators {
        final NoiseGeneratorOctaves a,b;
        Generators(long seed) { Random rng=new Random(seed);a=new NoiseGeneratorOctaves(rng,4);b=new NoiseGeneratorOctaves(rng,3); }
        double[][] fields(int x,int z,int h) {
            return new double[][]{a.func_76304_a(null,x,0,z,16,h,16,.125,.25,.125),b.func_76304_a(null,x,0,z,16,h,16,.0625,.125,.0625)};
        }
    }
    static double density(double a,double b,int y,float shift) {
        double first=a*.125; double second=b*.0625; double combined=first+second;
        double biased=combined+(double)shift; return biased-((double)y*.25);
    }
    static int[] compose(double[][] f,int h,float shift) {
        int[] result=new int[h*256];
        for(int i=0;i<result.length;i++) {int y=i%h;double d=density(f[0][i],f[1][i],y,shift);result[i]=d>0?70000:y<8?0xf0000001:0;}
        return result;
    }
    static final class Callbacks {
        final Random rng;
        final StringBuilder events=new StringBuilder();
        final int middle;
        final boolean failAfter;
        Callbacks(long seed,int middle,boolean failAfter) {rng=new Random(seed);this.middle=middle;this.failAfter=failAfter;}
        float before(float shift) {events.append('B').append(rng.nextInt(31)).append(';');return shift;}
        void middle(double[][] fields) {events.append('M').append(rng.nextInt(31)).append(';');if(middle==2)Arrays.fill(fields[0],1024.0);}
        void after(int[] states) {events.append('A').append(rng.nextInt(31)).append(';');if(failAfter)throw new IllegalStateException("controlled after callback");sink^=states[0];}
    }
    static int[] published;
    static int[] operation(int lane,Generators g,long id,int x,int z,int h,float shift,Callbacks cb) {
        shift=cb.before(shift);
        int[] result;
        try {
            // Any middle observer is an explicit fence. Fused admission cannot cross it.
            if(lane==2 && cb.middle==0) {result=new int[h*256];ok(fused(id,1,x,z,h,shift,0,result));}
            else {
                double[][] f;
                if(lane==0)f=g.fields(x,z,h);
                else {f=new double[][]{new double[h*256],new double[h*256]};ok(noise(id,x,z,h,f[0],f[1]));}
                if(cb.middle!=0)cb.middle(f);
                if(lane==0)result=compose(f,h,shift);
                else {result=new int[h*256];ok(terrain(h,shift,f[0],f[1],result));}
            }
            cb.after(result);
            // Synthetic Java publication only after every callback succeeds.
            published=result;
            return result;
        } catch(RuntimeException failure) {ok(discard(id));throw failure;}
    }
    static void writeJson(Path file,Object value) throws Exception {Files.write(file,new GsonBuilder().setPrettyPrinting().create().toJson(value).getBytes(StandardCharsets.UTF_8),StandardOpenOption.CREATE_NEW);}
    static void compare(double[] expected,double[] actual,String label) throws Exception {
        for(int i=0;i<expected.length;i++)if(Double.doubleToRawLongBits(expected[i])!=Double.doubleToRawLongBits(actual[i])) {
            Map<String,Object> first=new LinkedHashMap<>();first.put("label",label);first.put("index",i);first.put("expected_bits",Long.toHexString(Double.doubleToRawLongBits(expected[i])));first.put("actual_bits",Long.toHexString(Double.doubleToRawLongBits(actual[i])));
            Path json=output.resolve("FIRST-DIVERGENCE.json");if(!Files.exists(json)){writeJson(json,first);try(DataOutputStream stream=new DataOutputStream(Files.newOutputStream(output.resolve("FIRST-DIVERGENCE.bin"),StandardOpenOption.CREATE_NEW))){for(double v:expected)stream.writeLong(Double.doubleToRawLongBits(v));for(double v:actual)stream.writeLong(Double.doubleToRawLongBits(v));}}
            throw new AssertionError("raw floating divergence "+label+" at "+i);
        }
    }
    static void compareStates(int[] expected,int[] actual,String label) throws Exception {
        for(int i=0;i<expected.length;i++)if(expected[i]!=actual[i]) {
            Map<String,Object> first=new LinkedHashMap<>();first.put("label",label);first.put("index",i);first.put("expected_u32",Integer.toUnsignedLong(expected[i]));first.put("actual_u32",Integer.toUnsignedLong(actual[i]));
            if(!Files.exists(output.resolve("FIRST-DIVERGENCE.json"))){writeJson(output.resolve("FIRST-DIVERGENCE.json"),first);try(DataOutputStream stream=new DataOutputStream(Files.newOutputStream(output.resolve("FIRST-DIVERGENCE.bin"),StandardOpenOption.CREATE_NEW))){for(int v:expected)stream.writeInt(v);for(int v:actual)stream.writeInt(v);}}
            throw new AssertionError("state divergence "+label+" at "+i);
        }
        check(true,label);
    }
    static Map<String,Object> correctness() throws Exception {
        Map<String,Object> result=new LinkedHashMap<>();List<Map<String,Object>> cases=new ArrayList<>();
        int start=controls;long compared=0;
        long[] seeds={0,1,-1,Long.MIN_VALUE,0x123456789abcdefL};int[][] coords={{0,0},{-17,31},{29999984,-29999984}};
        for(long seed:seeds)for(int[] coord:coords)for(int h:new int[]{1,17,32}) {
            Generators g=new Generators(seed);long id=create(seed,1);check(id>0,"create");float shift=Float.intBitsToFloat(0x3eaaaaab);
            double[][] fields=g.fields(coord[0],coord[1],h);int n=h*256;double[] javaTrace=new double[3*n];System.arraycopy(fields[0],0,javaTrace,0,n);System.arraycopy(fields[1],0,javaTrace,n,n);for(int i=0;i<n;i++)javaTrace[2*n+i]=density(fields[0][i],fields[1][i],i%h,shift);
            double[] nativeTrace=new double[3*n];ok(trace(id,coord[0],coord[1],h,shift,nativeTrace));compare(javaTrace,nativeTrace,"seed="+seed+",x="+coord[0]+",z="+coord[1]+",h="+h);compared+=javaTrace.length;
            int[] expected=compose(fields,h,shift);int[] fusedStates=new int[n];ok(fused(id,1,coord[0],coord[1],h,shift,0,fusedStates));compareStates(expected,fusedStates,"fused state bits");
            double[] a=new double[n],b=new double[n];ok(noise(id,coord[0],coord[1],h,a,b));compare(fields[0],a,"separated-a");compare(fields[1],b,"separated-b");compared+=2L*n;
            int[] separated=new int[n];ok(terrain(h,shift,a,b,separated));compareStates(expected,separated,"separated state bits");
            Map<String,Object> c=new LinkedHashMap<>();c.put("seed",Long.toString(seed));c.put("x",coord[0]);c.put("z",coord[1]);c.put("height",h);c.put("raw_double_values_compared",5*n);c.put("state_hash",Arrays.hashCode(expected));cases.add(c);ok(destroy(id));
        }
        // Sensitivity control: narrowing double fields changes bits even when a terrain decision agrees.
        Generators g=new Generators(0);double[][] fields=g.fields(0,0,32);int changed=0,sameBlock=0;
        Map<String,Object> precision=new LinkedHashMap<>();
        for(int i=0;i<fields[0].length;i++){double raw=fields[0][i],narrow=(double)(float)raw;if(Double.doubleToRawLongBits(raw)!=Double.doubleToRawLongBits(narrow)){changed++;if(precision.isEmpty()){precision.put("index",i);precision.put("expected_bits",Long.toHexString(Double.doubleToRawLongBits(raw)));precision.put("narrowed_bits",Long.toHexString(Double.doubleToRawLongBits(narrow)));precision.put("classification","EXPECTED_NEGATIVE_CONTROL");}if((density(raw,fields[1][i],i%32,.3f)>0)==(density(narrow,fields[1][i],i%32,.3f)>0))sameBlock++;}}
        writeJson(output.resolve("PRECISION-NEGATIVE-CONTROL.json"),precision);
        check(changed>0 && sameBlock>0,"raw precision negative control");result.put("narrowing_changed_float_values",changed);result.put("narrowing_same_classification_despite_changed_float",sameBlock);
        long id=create(0,1);int[] out=new int[8192];Arrays.fill(out,123);int[] before=out.clone();
        check(fused(id,2,0,0,32,0,0,out)<0 && Arrays.equals(before,out),"epoch rejects without output");
        check(fused(id,1,0,0,32,0,1,out)<0 && Arrays.equals(before,out),"middle observer rejects without output");
        check(fused(id,1,0,0,-1,0,0,out)<0 && Arrays.equals(before,out),"negative height");
        check(fused(id,1,0,0,32,Float.NaN,0,out)<0 && Arrays.equals(before,out),"nonfinite bias");
        check(fused(id,1,0,0,32,0,0,new int[8191])<0,"capacity");
        boolean nullRejected=false;try{fused(id,1,0,0,32,0,0,null);}catch(RuntimeException expected){nullRejected=true;}check(nullRejected,"null array surfaces exception");
        double[] bad=new double[8192];bad[31]=Double.POSITIVE_INFINITY;check(terrain(32,0,bad,new double[8192],out)<0 && Arrays.equals(before,out),"nonfinite field");
        double[] alias=new double[8192];check(noise(id,0,0,32,alias,alias)<0,"alias field arrays");
        ok(fused(id,1,0,0,32,0,0,out));check(!Arrays.equals(before,out),"retry after failures");
        List<Map<String,Object>> callbackEvidence=new ArrayList<>();
        for(int middle:new int[]{0,1,2}){Callbacks a=new Callbacks(123,middle,false),b=new Callbacks(123,middle,false),c=new Callbacks(123,middle,false);int[] av=operation(0,g,id,3,-2,32,.3f,a),bv=operation(1,g,id,3,-2,32,.3f,b),cv=operation(2,g,id,3,-2,32,.3f,c);check(Arrays.equals(av,bv)&&Arrays.equals(av,cv),"callback mutation/fence parity");check(a.events.toString().equals(b.events.toString())&&a.events.toString().equals(c.events.toString()),"callback order/RNG parity");Map<String,Object> row=new LinkedHashMap<>();row.put("middle_mode",middle);row.put("java_events",a.events.toString());row.put("separated_events",b.events.toString());row.put("fused_or_fenced_events",c.events.toString());row.put("output_hash",Arrays.hashCode(av));callbackEvidence.add(row);}
        result.put("callback_evidence",callbackEvidence);
        for(int lane=0;lane<3;lane++){int[] old=published;Callbacks c=new Callbacks(123,0,true);boolean threw=false;try{operation(lane,g,id,0,0,32,.3f,c);}catch(IllegalStateException e){threw=e.getMessage().equals("controlled after callback");}check(threw&&published==old,"callback throw publication rollback");long[] stats=new long[7];ok(metrics(stats,0));check(stats[6]==0,"callback failure discards retained diagnostic result");}
        check(create(0,2)<0,"wrong registry session");ok(destroy(id));check(fused(id,1,0,0,32,0,0,out)<0,"destroyed handle");long newId=create(0,1);check(newId!=id,"no handle ABA");check(destroy(id)<0,"stale destroy");ok(destroy(newId));
        long[] handles=new long[16];for(int i=0;i<16;i++){handles[i]=create(i,1);check(handles[i]>0,"session capacity admitted");}check(create(16,1)<0,"session capacity bound");for(long handle:handles)ok(destroy(handle));
        // No unchecked conversion to a 1.12 u16 state. These are synthetic registry IDs.
        check(Integer.toUnsignedLong(0xf0000001)==4026531841L,"unsigned Java transport exact");
        result.put("cases",cases);result.put("case_count",cases.size());result.put("raw_double_values_compared",compared);result.put("assertions",controls-start);result.put("status","PASS");return result;
    }
    static long hash(int[] states) {long v=0xcbf29ce484222325L;for(int s:states)v=(v^(long)s)*0x100000001b3L;return v;}
    static List<Map<String,Object>> benchmark(int rounds) {
        List<Map<String,Object>> samples=new ArrayList<>();Generators g=new Generators(12345);long id=create(12345,1);
        for(int warm=0;warm<40;warm++)for(int lane=0;lane<3;lane++)sink^=hash(operation(lane,g,id,warm,-warm,32,.3f,new Callbacks(warm,0,false)));
        for(int sample=0;sample<rounds;sample++)for(int offset=0;offset<3;offset++){
            int lane=(sample+offset)%3;long[] counters=new long[7];ok(metrics(counters,1));int iterations=30;long hash=0;long start=System.nanoTime();
            for(int i=0;i<iterations;i++){int x=sample*17+i;hash^=hash(operation(lane,g,id,x,-x,32,.3f,new Callbacks(i,0,false)));}
            long elapsed=System.nanoTime()-start;sink^=hash;ok(metrics(counters,0));
            check(counters[0]==(lane==0?0:lane==1?2L*iterations:iterations),"exact JNI operation crossings");
            check(counters[1]==(lane==1?16L*8192*iterations:0),"exact extracted bytes");
            check(counters[2]==(lane==0?0:lane==1?20L*8192*iterations:4L*8192*iterations),"exact materialized bytes");
            Map<String,Object> row=new LinkedHashMap<>();row.put("sample",sample);row.put("lane",lane==0?"actual_java_bounded":lane==1?"existing_rust_separated":"existing_rust_fused");row.put("iterations",iterations);row.put("wall_ns",elapsed);row.put("jni_operation_calls",counters[0]);row.put("explicit_jni_get_bytes",counters[1]);row.put("explicit_jni_set_bytes",counters[2]);row.put("explicit_rust_vector_payload_bytes",counters[3]);row.put("rust_compute_ns",counters[4]);row.put("jni_regions_ns",counters[5]);row.put("retained_state_bytes",counters[6]);row.put("hash",Long.toHexString(hash));samples.add(row);
        }
        ok(destroy(id));return samples;
    }
    public static void main(String[] args) throws Exception {
        System.load(System.getProperty("rustcraft.nativeDll"));output=Paths.get(System.getProperty("rustcraft.oracleOutput"));Files.createDirectories(output);
        Map<String,Object> result=new LinkedHashMap<>();result.put("schema","H10_SYNTHETIC_WORLDGEN_FUSION_V1");result.put("production_authority",false);result.put("scope","actual Minecraft noise plus independently specified synthetic composition; NOT full chunk generation");result.put("java",System.getProperty("java.runtime.version"));result.put("noise_class_loader",NoiseGeneratorOctaves.class.getClassLoader().getClass().getName());
        result.put("correctness",correctness());int rounds=Integer.parseInt(System.getProperty("rustcraft.fusionRounds","0"));if(rounds<0||rounds>20)throw new IllegalArgumentException("rounds");result.put("benchmark",rounds==0?Collections.emptyList():benchmark(rounds));result.put("status","PASS");writeJson(output.resolve("result.json"),result);System.out.println("H10_FUSION_COMPLETE assertions="+controls);
    }
}
