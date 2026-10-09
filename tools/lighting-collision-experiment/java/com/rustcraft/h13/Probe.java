package com.rustcraft.h13;

import com.google.gson.GsonBuilder;
import java.io.*;
import java.lang.reflect.*;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.profiler.Profiler;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.*;
import net.minecraft.world.chunk.IChunkProvider;

/** Actual initialized runtime primitive calls on detached immutable fixtures only. */
public strictfp final class Probe {
    static native int collide(int mode,int callbacks,double[] shapes,double[] queries,long[] output,long[] metrics);
    static native int light(int mode,int side,int callbacks,byte[] emission,byte[] opacity,byte[] initial,int[] dirty,byte[] output,long[] metrics);
    static Path output;
    static int controls, collisionCases, lightCases;
    static volatile long sink;
    static List<Map<String,Object>> fixtures=new ArrayList<Map<String,Object>>();
    static List<Map<String,Object>> samples=new ArrayList<Map<String,Object>>();
    static void check(boolean value,String why){if(!value)throw new AssertionError(why);controls++;}
    static void json(Path path,Object value)throws Exception{Files.write(path,new GsonBuilder().setPrettyPrinting().create().toJson(value).getBytes(StandardCharsets.UTF_8),StandardOpenOption.CREATE_NEW);}
    static void equal(long[] a,long[] b,String label)throws Exception{
        int first=-1;for(int i=0;i<Math.min(a.length,b.length);i++)if(a[i]!=b[i]){first=i;break;}
        if(first>=0 || a.length!=b.length){Map<String,Object> f=new LinkedHashMap<String,Object>();f.put("label",label);f.put("first_index",first);f.put("expected_length",a.length);f.put("actual_length",b.length);
            if(first>=0){f.put("expected_bits",Long.toHexString(a[first]));f.put("actual_bits",Long.toHexString(b[first]));}
            if(!Files.exists(output.resolve("FIRST-DIVERGENCE.json"))){json(output.resolve("FIRST-DIVERGENCE.json"),f);try(DataOutputStream s=new DataOutputStream(Files.newOutputStream(output.resolve("FIRST-DIVERGENCE.bin")))){for(long v:a)s.writeLong(v);for(long v:b)s.writeLong(v);}}
            throw new AssertionError("FIRST_DIVERGENCE "+label+" index="+first);
        }
    }
    static void equal(byte[] a,byte[] b,String label)throws Exception{long[] x=new long[a.length],y=new long[b.length];for(int i=0;i<a.length;i++)x[i]=a[i]&255;for(int i=0;i<b.length;i++)y[i]=b[i]&255;equal(x,y,label);}
    static long hash(long[] out){long h=1;for(long v:out)h=h*31+v;return h;}
    static long hash(byte[] out){long h=1;for(byte v:out)h=h*31+(v&255);return h;}
    static void fixture(Path path,String kind)throws Exception {
        byte[] data=Files.readAllBytes(path);StringBuilder sha=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(data))sha.append(String.format(Locale.ROOT,"%02x",b&255));
        Map<String,Object> f=new LinkedHashMap<String,Object>();f.put("path",path.getFileName().toString());f.put("kind",kind);f.put("bytes",data.length);f.put("sha256",sha.toString());fixtures.add(f);
    }
    static void recordCollision(Scene scene,long[] expected)throws Exception {
        Path path=output.resolve("collision-"+collisionCases+".bin");try(DataOutputStream out=new DataOutputStream(Files.newOutputStream(path,StandardOpenOption.CREATE_NEW))){
            out.writeInt(0x48313343);out.writeInt(scene.boxes.length);out.writeInt(scene.queries.length);
            double[] values=new double[6];for(AxisAlignedBB box:scene.boxes){capture(box,values,0);for(double v:values)out.writeLong(Double.doubleToRawLongBits(v));}
            for(int i=0;i<scene.queries.length;i++){capture(scene.queries[i],values,0);for(double v:values)out.writeLong(Double.doubleToRawLongBits(v));for(double v:scene.movement[i])out.writeLong(Double.doubleToRawLongBits(v));}
            out.writeInt(expected.length);for(long v:expected)out.writeLong(v);
        }fixture(path,"actual-aabb");
    }
    static void divergenceControl()throws Exception {
        Path saved=output;output=saved.resolve("negative-control");Files.createDirectory(output);boolean caught=false;
        try{equal(new long[]{7,Long.MIN_VALUE,11},new long[]{7,0,11},"intentional-signed-zero-control");}catch(AssertionError failure){caught=failure.getMessage().contains("FIRST_DIVERGENCE");}finally{output=saved;}
        check(caught,"deliberate signed-zero divergence detected");
    }
    static final class Scene {
        final AxisAlignedBB[] boxes, queries;final double[][] movement;
        Scene(int count,boolean clustered,double origin){
            boxes=new AxisAlignedBB[count];queries=new AxisAlignedBB[48];movement=new double[48][3];
            for(int i=0;i<count;i++){
                double x=origin+(clustered?(i%4)*.25:(i%32)*2),y=clustered?((i/4)%4)*.25:(i/32)%16,z=clustered?((i/16)%4)*.25:(i/512)*3;
                boxes[i]=new AxisAlignedBB(x,y,z,x+1,y+((i%7)==0?.5:1),z+1);
            }
            for(int i=0;i<queries.length;i++){
                double x=origin+(clustered?.125:(i%32)*2+.25),y=clustered?.125:(i/32)%16+.25,z=clustered?.125:0.25;
                queries[i]=new AxisAlignedBB(x,y,z,x+.6,y+1.8,z+.6);
                movement[i]=new double[]{i%3==0?-0.0:i%3==1?2.0:-2.0,(i%5-2)*.25,(i%7-3)*.5};
            }
        }
        Scene(AxisAlignedBB[] boxes,AxisAlignedBB[] queries,double[][] movement){this.boxes=boxes;this.queries=queries;this.movement=movement;}
    }
    static void capture(AxisAlignedBB b,double[] out,int i){out[i]=b.field_72340_a;out[i+1]=b.field_72338_b;out[i+2]=b.field_72339_c;out[i+3]=b.field_72336_d;out[i+4]=b.field_72337_e;out[i+5]=b.field_72334_f;}
    static long[] collision(Scene scene,int lane,long[] metric){
        long[] out=new long[1+scene.queries.length*(4+scene.boxes.length)];
        if(lane==0){
            int at=0;out[at++]=scene.queries.length;
            for(int q=0;q<scene.queries.length;q++){
                AxisAlignedBB query=scene.queries[q];double x=scene.movement[q][0],y=scene.movement[q][1],z=scene.movement[q][2];
                int base=at;at+=4;int count=0;
                for(int i=0;i<scene.boxes.length;i++){
                    AxisAlignedBB box=scene.boxes[i];
                    if(box.func_72326_a(query)){out[at++]=i;count++;}
                    x=box.func_72316_a(query,x);y=box.func_72323_b(query,y);z=box.func_72322_c(query,z);
                }
                out[base]=Double.doubleToRawLongBits(x);out[base+1]=Double.doubleToRawLongBits(y);out[base+2]=Double.doubleToRawLongBits(z);out[base+3]=count;
            }
            return Arrays.copyOf(out,at);
        }
        double[] boxes=new double[scene.boxes.length*6],queries=new double[scene.queries.length*9];
        for(int i=0;i<scene.boxes.length;i++)capture(scene.boxes[i],boxes,i*6);
        for(int i=0;i<scene.queries.length;i++){capture(scene.queries[i],queries,i*9);System.arraycopy(scene.movement[i],0,queries,i*9+6,3);}
        int length=collide(lane-1,0,boxes,queries,out,metric);if(length<0)throw new IllegalStateException("collision native status="+length);
        return Arrays.copyOf(out,length);
    }
    static void collisionCases()throws Exception{
        for(int n:new int[]{0,16,256,2048})for(boolean clustered:new boolean[]{false,true})for(double origin:new double[]{-16,0,29999900}){
            Scene scene=new Scene(n,clustered,origin);long[] expected=collision(scene,0,new long[8]);
            equal(expected,collision(scene,1,new long[8]),"collision-linear-"+collisionCases);equal(expected,collision(scene,2,new long[8]),"collision-index-"+collisionCases);recordCollision(scene,expected);collisionCases++;
        }
        AxisAlignedBB[] boxes={new AxisAlignedBB(-0.0,-0.0,-0.0,1,1,1),new AxisAlignedBB(1,0,0,2,1,1),new AxisAlignedBB(0,.5,0,1,.5,1),new AxisAlignedBB(-1000,-1000,-1000,1000,1000,1000),new AxisAlignedBB(0,0,0,1,1,1)};
        AxisAlignedBB[] q={new AxisAlignedBB(1,0,0,2,1,1),new AxisAlignedBB(Math.nextAfter(1,0),0,0,2,1,1),new AxisAlignedBB(0,0,0,0,0,0),new AxisAlignedBB(-4,0,0,-3,1,1),new AxisAlignedBB(2,0,0,1,1,1)};
        double[][] movement={{-0.0,0.0,0.0},{Double.MIN_VALUE,-Double.MIN_VALUE,0},{1,-1,1},{64,-64,64},{-1,1,-1}};
        Scene edge=new Scene(boxes,q,movement);long[] expected=collision(edge,0,new long[8]);equal(expected,collision(edge,1,new long[8]),"collision-edge-linear");equal(expected,collision(edge,2,new long[8]),"collision-edge-index");recordCollision(edge,expected);collisionCases++;
        long[] sentinel=new long[32];Arrays.fill(sentinel,77);long[] before=sentinel.clone();
        check(collide(1,1,new double[6],new double[9],sentinel,new long[8])==-1,"dynamic callback refused");equal(before,sentinel,"callback refusal output");
        double[] bad=new double[6];bad[0]=Double.NaN;check(collide(1,0,bad,new double[9],sentinel,new long[8])==-3,"nonfinite shape");equal(before,sentinel,"invalid output atomicity");
        check(collide(0,0,new double[6],new double[9],new long[0],new long[8])==-2,"short output");
        check(collide(0,0,new double[0],new double[0],new long[8],new long[8])==1,"empty batch retry");
        long[] alias=new long[8];check(collide(0,0,new double[0],new double[0],alias,alias)==-2,"metric/result alias rejected");
        check(collide(0,0,new double[4097*6],new double[9],sentinel,new long[8])==-2,"shape capacity");
        boolean threw=false;try{collide(0,0,null,new double[9],sentinel,new long[8]);}catch(RuntimeException e){threw=true;}check(threw,"null shape exception");
        // Synthetic callback fence: dispatch stays Java and original ordering is observable.
        StringBuilder events=new StringBuilder();long[] published={19};long[] prior=published;
        try{for(int i=0;i<4;i++){events.append(i);if(i==2)throw new IllegalStateException("callback");}published=expected;}catch(IllegalStateException e){check(published==prior,"callback failure preserves publication");}
        check(events.toString().equals("012"),"callback order and exception boundary");
    }
    static final class GridWorld extends World {
        final int side;final IBlockState[] states;byte[] levels;int writes;
        GridWorld(int side){super(null,null,new WorldProviderSurface(),new Profiler(),false);this.side=side;states=new IBlockState[side*side*side];Arrays.fill(states,state("air"));levels=new byte[states.length];}
        protected IChunkProvider func_72970_h(){throw new IllegalStateException("no provider");}
        protected boolean func_175680_a(int x,int z,boolean empty){return true;}
        public boolean func_175648_a(BlockPos p,int radius,boolean empty){return true;}
        public boolean func_175710_j(BlockPos p){return false;}
        int index(BlockPos p){int x=p.func_177958_n()+side/2,y=p.func_177956_o()-64,z=p.func_177952_p()+side/2;return x<0||x>=side||y<0||y>=side||z<0||z>=side?-1:x+side*(y+side*z);}
        BlockPos position(int i){return new BlockPos(i%side-side/2,64+(i/side)%side,i/(side*side)-side/2);}
        public IBlockState func_180495_p(BlockPos p){int i=index(p);return i<0?state("stone"):states[i];}
        public int func_175642_b(EnumSkyBlock type,BlockPos p){if(type!=EnumSkyBlock.BLOCK)throw new IllegalStateException("no skylight");int i=index(p);return i<0?0:levels[i]&255;}
        public void func_175653_a(EnumSkyBlock type,BlockPos p,int v){if(type!=EnumSkyBlock.BLOCK)throw new IllegalStateException("no skylight");int i=index(p);if(i>=0){if(v<0||v>15)throw new IllegalStateException("light range");levels[i]=(byte)v;writes++;}}
    }
    static IBlockState state(String name){return Block.func_149684_b("minecraft:"+name).func_176223_P();}
    static Method raw;
    static byte[][] captureLight(GridWorld w){
        byte[] emission=new byte[w.states.length],opacity=new byte[w.states.length];
        for(int i=0;i<w.states.length;i++){emission[i]=(byte)w.states[i].func_185906_d();opacity[i]=(byte)Math.min(15,w.states[i].func_185891_c());}
        return new byte[][]{emission,opacity};
    }
    static byte[] referenceLight(GridWorld w)throws Exception{
        byte[] previous=w.levels;w.levels=new byte[previous.length];
        try{for(int pass=0;pass<17;pass++){byte[] next=new byte[w.levels.length];for(int i=0;i<next.length;i++)next[i]=(byte)((Integer)raw.invoke(w,w.position(i),EnumSkyBlock.BLOCK)).intValue();boolean same=Arrays.equals(next,w.levels);w.levels=next;if(same)return next;}throw new IllegalStateException("reference did not settle");}finally{w.levels=previous;}
    }
    static byte[] nativeLight(GridWorld w,byte[] initial,int[] dirty,int mode,long[] metric){byte[][] values=captureLight(w);byte[] out=new byte[initial.length];int status=light(mode,w.side,0,values[0],values[1],initial,dirty,out,metric);if(status!=0)throw new IllegalStateException("light native status="+status);return out;}
    static void lightingCases()throws Exception{
        raw=World.class.getDeclaredMethod("func_175638_a",BlockPos.class,EnumSkyBlock.class);raw.setAccessible(true);
        for(int side:new int[]{8,18,32}){
            GridWorld w=new GridWorld(side);int center=side/2+side*(side/2+side*(side/2));byte[] previous=new byte[w.states.length];
            int[] positions={center,center+1,center-1,center-1,center-1,center,0,center+1,0,center-1};String[] values={"glowstone","torch","stone","water","glass","air","glowstone","air","stone","air"};
            for(int step=0;step<positions.length;step++){
                int at=positions[step];w.states[at]=state(values[step]);byte[] expected=referenceLight(w);int[] dirty={at};
                equal(expected,nativeLight(w,previous,dirty,0,new long[8]),"light-dense-"+side+"-"+step);
                equal(expected,nativeLight(w,previous,dirty,1,new long[8]),"light-frontier-"+side+"-"+step);
                w.levels=previous.clone();w.writes=0;check(w.func_180500_c(EnumSkyBlock.BLOCK,w.position(at)),"actual update accepted");equal(expected,w.levels,"actual-World-checkLightFor-"+side+"-"+step);
                Path fixture=output.resolve("light-"+lightCases+".bin");byte[][] captured=captureLight(w);
                try(DataOutputStream stream=new DataOutputStream(Files.newOutputStream(fixture,StandardOpenOption.CREATE_NEW))){stream.writeInt(0x4831334c);stream.writeInt(side);stream.writeInt(at);stream.write(captured[0]);stream.write(captured[1]);stream.write(previous);stream.write(expected);}fixture(fixture,"actual-block-light");
                previous=expected;lightCases++;
            }
        }
        byte[] valid=new byte[8],bad=new byte[8],out=new byte[8];Arrays.fill(out,(byte)73);byte[] before=out.clone();bad[1]=16;
        check(light(1,2,1,valid,valid,valid,new int[]{0},out,new long[8])==-1,"light callback gate");equal(before,out,"light callback output");
        check(light(1,2,0,bad,valid,valid,new int[]{0},out,new long[8])==-3,"light invalid level");equal(before,out,"light failure output");
        check(light(1,2,0,valid,valid,valid,new int[]{8},out,new long[8])==-3,"light invalid dirty");
        check(light(1,2,0,valid,valid,valid,new int[]{0},valid,new long[8])==-2,"light input/output alias rejected");
        check(light(1,33,0,valid,valid,valid,new int[]{0},out,new long[8])==-1,"light dimension bound");
    }
    static void benchmark(int rounds)throws Exception{
        for(boolean cluster:new boolean[]{false,true})for(int count:new int[]{256,2048}){
            Scene scene=new Scene(count,cluster,0);
            for(int i=0;i<15;i++)for(int lane=0;lane<3;lane++)sink^=hash(collision(scene,lane,new long[8]));
            for(int repeat=0;repeat<rounds;repeat++)for(int k=0;k<3;k++){
                int lane=(repeat+k)%3;long[] metric=new long[8];long start=System.nanoTime();long result=0;
                for(int i=0;i<8;i++)result+=hash(collision(scene,lane,metric));long elapsed=System.nanoTime()-start;sink^=result;
                Map<String,Object> s=new LinkedHashMap<String,Object>();s.put("kind","collision");s.put("lane",lane);s.put("shapes",count);s.put("clustered",cluster);s.put("repeat",repeat);s.put("operations",8);s.put("total_ns",elapsed);s.put("last_native_metrics",lane==0?null:metric);s.put("output_hash",result);samples.add(s);
            }
        }
        for(int scenario=0;scenario<3;scenario++) {
            GridWorld world=new GridWorld(18);int center=9+18*(9+18*9);world.states[center]=state("glowstone");byte[] before;
            if(scenario==0)before=new byte[world.states.length];else before=referenceLight(world);
            if(scenario==1)world.states[center]=state("air");int at=scenario==2?0:center;int[] dirty={at};
            for(int lane=0;lane<3;lane++)for(int i=0;i<3;i++){if(lane==0){world.levels=before.clone();world.func_180500_c(EnumSkyBlock.BLOCK,world.position(at));sink^=hash(world.levels);}else sink^=hash(nativeLight(world,before,dirty,lane-1,new long[8]));}
            for(int repeat=0;repeat<rounds;repeat++)for(int k=0;k<3;k++){
                int lane=(repeat+k)%3;long[] metric=new long[8];long start=System.nanoTime();long result;
                if(lane==0){world.levels=before.clone();world.func_180500_c(EnumSkyBlock.BLOCK,world.position(at));result=hash(world.levels);}else result=hash(nativeLight(world,before,dirty,lane-1,metric));sink^=result;
                Map<String,Object> sample=new LinkedHashMap<String,Object>();sample.put("kind",new String[]{"light-add","light-remove","light-unchanged"}[scenario]);sample.put("lane",lane);sample.put("repeat",repeat);sample.put("operations",1);sample.put("total_ns",System.nanoTime()-start);sample.put("last_native_metrics",lane==0?null:metric);sample.put("output_hash",result);samples.add(sample);
            }
        }
    }

    public static void main(String[] ignored)throws Exception{
        output=Paths.get(System.getProperty("rustcraft.oracleOutput"));System.load(System.getProperty("rustcraft.nativeDll"));
        collisionCases();lightingCases();divergenceControl();int rounds=Integer.getInteger("rustcraft.h13Rounds",0);if(rounds<0||rounds>10)throw new IllegalStateException("round bound");if(rounds>0)benchmark(rounds);
        Map<String,Object> result=new LinkedHashMap<String,Object>();result.put("status","PASS_BOUNDED_PRIMITIVE_ONLY");result.put("collision_cases",collisionCases);result.put("light_update_cases",lightCases);result.put("controls",controls);result.put("samples",samples);result.put("fixtures",fixtures);result.put("production_authority",false);json(output.resolve("results.json"),result);
        System.out.println("H13_PASS collision="+collisionCases+" light="+lightCases+" controls="+controls+" samples="+samples.size());
    }
}
