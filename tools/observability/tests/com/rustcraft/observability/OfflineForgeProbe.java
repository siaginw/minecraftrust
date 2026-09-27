package com.rustcraft.observability;
import com.google.gson.GsonBuilder;
import com.rustcraft.telemetry.*;
import com.rustcraft.observability.fixtures.Callbacks;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import net.minecraft.launchwrapper.Launch;
import net.minecraft.world.gen.NoiseGeneratorOctaves;
import net.minecraftforge.fml.common.Loader;
public final class OfflineForgeProbe  {
    static volatile int input;
    static volatile long sink;
    static Long allocatedNow() {
        try {
            java.lang.management.ThreadMXBean base=java.lang.management.ManagementFactory.getThreadMXBean();
            if(!(base instanceof com.sun.management.ThreadMXBean))return null;
            com.sun.management.ThreadMXBean bean=(com.sun.management.ThreadMXBean)base;
            if(!bean.isThreadAllocatedMemorySupported()||!bean.isThreadAllocatedMemoryEnabled())return null;
            long n=bean.getThreadAllocatedBytes(Thread.currentThread().getId());
            return n<0?null:Long.valueOf(n);
        }
        catch(Throwable unavailable) {
            return null;
        }
    }
    static void check(boolean value,String description) {
        if(!value)throw new AssertionError(description);
    }
    static List<Map<String,Object>> benchmark(int samples)throws Throwable  {
        List<Map<String,Object>> rows=new ArrayList<Map<String,Object>>();
        final Callbacks receiver=new Callbacks();
        CallbackRegistry registry=new CallbackRegistry();
        CallbackRegistry.Owner owner=registry.registerOwner(new Object(),"synthetic_fixture");
        final CallbackRegistry.Token inner=registry.bind(owner,Callbacks.class.getMethod("value",int.class),Callbacks.class);
        final CallbackRegistry.Token outer=registry.bind(owner,Callbacks.class.getMethod("nested",RuntimeTelemetry.Body.class),Callbacks.class);
        final RuntimeTelemetry disabled=new RuntimeTelemetry(registry,false,1,8,RuntimeTelemetry.MONOTONIC),enabled=new RuntimeTelemetry(registry,true,1,8,RuntimeTelemetry.MONOTONIC);
        RuntimeTelemetry.Attachment da=disabled.attach(),ea=enabled.attach();
        final RuntimeTelemetry.Body<Integer> work=new RuntimeTelemetry.Body<Integer>() {
            public Integer run() {
                return receiver.value(input);
            }
        }
        ;
        final RuntimeTelemetry.Body<Integer> disabledInner=new RuntimeTelemetry.Body<Integer>() {
            public Integer run()throws Throwable {
                return disabled.call(inner,receiver,work);
            }
        }
        ;
        final RuntimeTelemetry.Body<Integer> enabledInner=new RuntimeTelemetry.Body<Integer>() {
            public Integer run()throws Throwable {
                return enabled.call(inner,receiver,work);
            }
        }
        ;
        final RuntimeTelemetry.Body<Integer> directNested=new RuntimeTelemetry.Body<Integer>() {
            public Integer run()throws Throwable {
                return receiver.nested(work);
            }
        }
        ;
        final RuntimeTelemetry.Body<Integer> disabledNested=new RuntimeTelemetry.Body<Integer>() {
            public Integer run()throws Throwable {
                return receiver.nested(disabledInner);
            }
        }
        ;
        final RuntimeTelemetry.Body<Integer> enabledNested=new RuntimeTelemetry.Body<Integer>() {
            public Integer run()throws Throwable {
                return receiver.nested(enabledInner);
            }
        }
        ;
        for(int sample=-3;sample<samples;sample++)for(int ordering=0;ordering<6;ordering++) {
            int mode=(ordering+sample+6)%6;
            int iterations=100000;
            long hash=0;
            Long allocationStart=allocatedNow();
            long start=System.nanoTime();
            for(int i=0;i<iterations;i++) {
                input=i%31;
                switch(mode) {
                    case 0:hash+=work.run();
                    break;
                    case 1:hash+=disabled.call(inner,receiver,work);
                    break;
                    case 2:hash+=enabled.call(inner,receiver,work);
                    break;
                    case 3:hash+=directNested.run();
                    break;
                    case 4:hash+=disabled.call(outer,receiver,disabledNested);
                    break;
                    case 5:hash+=enabled.call(outer,receiver,enabledNested);
                    break;
                    default:throw new AssertionError();
                }
            }
            long elapsed=System.nanoTime()-start;
            Long allocationEnd=allocatedNow();
            sink^=hash;
            {
                Map<String,Object> row=new LinkedHashMap<String,Object>();
                row.put("sample",sample);
                row.put("mode",mode);
                row.put("iterations",iterations);
                row.put("wall_ns",elapsed);
                row.put("result_sum",hash);
                row.put("thread_allocated_bytes",allocationStart==null||allocationEnd==null||allocationEnd<allocationStart?null:allocationEnd-allocationStart);
                row.put("allocation_scope","current Java thread including sampler overhead; no native allocations");
                rows.add(row);
            }
        }
        for(Object object:(List<?>)disabled.snapshot().get("rows"))check(((Number)((Map<?,?>)object).get("completed_known")).longValue()==0,"disabled has no recorded callbacks");
        List<?> enabledRows=(List<?>)enabled.snapshot().get("rows");
        check(((Number)((Map<?,?>)enabledRows.get(0)).get("completed_known")).longValue()==(samples+3)*200000L,"enabled inner callback count exact");
        check(((Number)((Map<?,?>)enabledRows.get(1)).get("completed_known")).longValue()==(samples+3)*100000L,"enabled outer callback count exact");
        da.close();
        ea.close();
        disabled.close();
        enabled.close();
        registry.close();
        return rows;
    }
    public static void main(String[] args)throws Throwable  {
        // Library and agent share their actual defining loader; fixture/runtime classes stay in LaunchClassLoader.
        Launch.classLoader.addClassLoaderExclusion("com.rustcraft.telemetry.");
        Path output=Paths.get(System.getProperty("rustcraft.oracleOutput"));
        Files.createDirectories(output);
        Map<String,Object> result=new LinkedHashMap<String,Object>();
        result.put("schema","H15_OFFLINE_OBSERVABILITY_V1");
        result.put("production_authority",false);
        result.put("deterministic",ObservabilityChecks.run());
        final NoiseGeneratorOctaves noise=new NoiseGeneratorOctaves(new Random(12345),4);
        CallbackRegistry registry=new CallbackRegistry();
        Object actualOwner=Loader.instance().getIndexedModList().get("minecraft");
        check(actualOwner!=null,"actual Minecraft mod container");
        CallbackRegistry.Owner owner=registry.registerOwner(actualOwner,"minecraft");
        Method method=NoiseGeneratorOctaves.class.getMethod("func_76304_a",double[].class,int.class,int.class,int.class,int.class,int.class,int.class,double.class,double.class,double.class);
        final CallbackRegistry.Token token=registry.bind(owner,method,NoiseGeneratorOctaves.class);
        final RuntimeTelemetry telemetry=new RuntimeTelemetry(registry,true,1,8,RuntimeTelemetry.MONOTONIC);
        RuntimeTelemetry.Attachment attachment=telemetry.attach();
        StageBuffer stages=new StageBuffer(64,true,RuntimeTelemetry.MONOTONIC);
        int compared=0;
        for(int coordinate=-8;coordinate<8;coordinate++) {
            final int x=coordinate;
            final RuntimeTelemetry.Body<double[]> action=new RuntimeTelemetry.Body<double[]>() {
                public double[] run() {
                    return noise.func_76304_a(null,x,0,-x,5,17,5,.125,.25,.125);
                }
            }
            ;
            double[] expected=action.run();
            double[] actual=stages.measure(StageBuffer.Stage.CALLBACK_INCLUSIVE,new RuntimeTelemetry.Body<double[]>() {
                public double[] run()throws Throwable {
                    return telemetry.call(token,noise,action);
                }
            }
            );
            check(expected.length==actual.length,"noise length");
            for(int i=0;i<expected.length;i++) {
                check(Double.doubleToRawLongBits(expected[i])==Double.doubleToRawLongBits(actual[i]),"metrics changed actual runtime output");
                compared++;
            }
        }
        result.put("actual_callback_values_compared",compared);
        result.put("actual_callback_snapshot",telemetry.snapshot());
        result.put("actual_stage_coverage",stages.coverage());
        result.put("actual_stage_boundary","whole observed noise callback wrapper; not chunk generation or MSPT");
        result.put("java_mxbean",MxBeanSampler.sample());
        stages.writeBatch(output.resolve("actual-callback-stages.tsv"));
        attachment.close();
        telemetry.close();
        registry.close();
        StageBuffer synthetic=new StageBuffer(4,true,RuntimeTelemetry.MONOTONIC);
        for(int i=1;i<=8;i++)synthetic.record(StageBuffer.Stage.TICK,i*10_000_000L);
        synthetic.writeBatch(output.resolve("synthetic-loss-stages.tsv"));
        result.put("synthetic_loss_coverage",synthetic.coverage());
        check(synthetic.retained()==4&&synthetic.dropped(StageBuffer.Stage.TICK)==4,"bounded staging loss visible");
        synthetic.resetAfterExport();
        check(synthetic.retained()==0&&synthetic.dropped(StageBuffer.Stage.TICK)==0,"explicit drain reset");
        StageBuffer known=new StageBuffer(100,true,RuntimeTelemetry.MONOTONIC);
        for(int i=1;i<=100;i++)known.record(StageBuffer.Stage.TICK,i*1_000_000L);
        known.writeBatch(output.resolve("synthetic-known-ticks.tsv"));
        int samples=Integer.parseInt(System.getProperty("observability.samples","0"));
        if(samples<0||samples>20)throw new IllegalArgumentException("samples");
        result.put("overhead",samples==0?Collections.emptyList():benchmark(samples));
        result.put("definition_observer_dropped",com.rustcraft.telemetry.agent.DefinitionObserver.dropped());
        result.put("definition_observer_rejected_attempts",com.rustcraft.telemetry.agent.DefinitionObserver.rejectedDefinitionAttempts());
        result.put("definition_observer_errors",com.rustcraft.telemetry.agent.DefinitionObserver.errors());
        result.put("status","PASS");
        Files.write(output.resolve("result.json"),new GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(result).getBytes(StandardCharsets.UTF_8),StandardOpenOption.CREATE_NEW);
        System.out.println("H15_OFFLINE_OBSERVABILITY_PASS values="+compared);
    }
}
