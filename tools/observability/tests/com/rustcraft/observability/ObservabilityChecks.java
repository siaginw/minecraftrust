package com.rustcraft.observability;
import com.rustcraft.telemetry.*;
import com.rustcraft.observability.fixtures.Callbacks;
import com.rustcraft.observability.fixtures.LifecycleVictims;
import com.rustcraft.telemetry.agent.DefinitionObserver;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
public final class ObservabilityChecks  {
    static int assertions;
    static void check(boolean condition,String message) {
        if(!condition)throw new AssertionError(message);
        assertions++;
    }
    static final class Clock implements RuntimeTelemetry.Clock  {
        long value;
        boolean fail;
        int reads;
        public long now() {
            reads++;
            if(fail)throw new AssertionError("controlled metrics clock failure");
            return value;
        }
    }
    @SuppressWarnings("unchecked") static Map<String,Object> row(Map<String,Object> snapshot,int slot) {
        return ((List<Map<String,Object>>)snapshot.get("rows")).get(slot);
    }
    static long number(Map<String,Object> map,String name) {
        return ((Number)map.get(name)).longValue();
    }
    static CallbackRegistry.Token bind(CallbackRegistry r,CallbackRegistry.Owner owner,Class<?> receiver)throws Exception {
        return r.bind(owner,Callbacks.class.getMethod("value",int.class),receiver);
    }
    static void definitionAttemptControl(final Object receiver,final boolean redefine)throws Throwable {
        final Class<?> type=receiver.getClass();
        final Method method=type.getMethod("value",int.class);
        final DefinitionObserver.Definition definition=DefinitionObserver.observed(type);
        check(definition!=null&&definition.matches(type),"initial actual class observation");
        final CallbackRegistry registry=new CallbackRegistry();
        CallbackRegistry.Owner owner=registry.registerOwner(new Object(),"lifecycle_control");
        final CallbackRegistry.Token token=registry.bind(owner,method,type);
        final Clock clock=new Clock();
        final RuntimeTelemetry telemetry=new RuntimeTelemetry(registry,true,1,2,clock);
        RuntimeTelemetry.Attachment attachment=telemetry.attach();
        final int expected=((Integer)method.invoke(receiver,4)).intValue();
        check(telemetry.call(token,receiver,new RuntimeTelemetry.Body<Integer>() {
            public Integer run()throws Throwable { clock.value=10; return (Integer)method.invoke(receiver,4); }
        })==expected&&number(row(telemetry.snapshot(),token.slot()),"completed_known")==1,"initial binding attributes actual callback");
        final RuntimeException original=new RuntimeException("lifecycle exception identity");
        Throwable seen=null;
        int returned=0;
        try {
            returned=telemetry.call(token,receiver,new RuntimeTelemetry.Body<Integer>() {
                public Integer run()throws Throwable {
                    // Invalid candidate bytes simulate an attempt the JVM cannot accept. old is
                    // the actual existing Class for redefine, null for a duplicate define attempt.
                    check(new DefinitionObserver().transform(type.getClassLoader(),type.getName().replace('.','/'),redefine?type:null,null,new byte[]{0,1,2})==null,"observer never transforms candidate");
                    clock.value=20;
                    if(redefine)throw original;
                    return (Integer)method.invoke(receiver,4);
                }
            });
        } catch(Throwable caught) { seen=caught; }
        check(redefine?seen==original:seen==null&&returned==expected,"definition attempt preserves original callback outcome");
        check(!definition.matches(type)&&DefinitionObserver.observed(type)==null,"attempt invalidates old binding without replacement");
        check(number(row(telemetry.snapshot(),token.slot()),"completed_known")==1&&number(row(telemetry.snapshot(),token.slot()),"unknown_inclusive")==1,"mid-callback attempt loses attribution visibly");
        check(telemetry.call(token,receiver,new RuntimeTelemetry.Body<Integer>() {
            public Integer run()throws Throwable { return (Integer)method.invoke(receiver,4); }
        })==expected&&number(row(telemetry.snapshot(),token.slot()),"completed_known")==1,"invalid binding still executes existing class without attribution");
        boolean refused=false;
        try { registry.bind(owner,method,type); } catch(IllegalArgumentException expectedFailure) { refused=true; }
        check(refused&&registry.size()==1,"actual existing class cannot rebind after attempt");
        final byte[] originalBytes=Files.readAllBytes(Paths.get(System.getProperty("observability.fixtureClasses"),type.getName().replace('.','/')+".class"));
        new DefinitionObserver().transform(type.getClassLoader(),type.getName().replace('.','/'),null,null,originalBytes);
        check(DefinitionObserver.observed(type)==null&&!definition.matches(type),"valid repeated attempt cannot resurrect tombstone");
        attachment.close();
        telemetry.close();
        registry.close();
    }
    public static void main(String[] args)throws Throwable {
        Map<String,Object> result=run();
        Path output=Paths.get(args[0]);
        Files.write(output,new com.google.gson.GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(result).getBytes(java.nio.charset.StandardCharsets.UTF_8),StandardOpenOption.CREATE_NEW);
        System.out.println("H15_JAVA_CHECKS_PASS assertions="+assertions);
    }
    public static Map<String,Object> run()throws Throwable {
        assertions=0;
        final Callbacks receiver=new Callbacks();
        final Method method=Callbacks.class.getMethod("value",int.class);
        CallbackRegistry registry=new CallbackRegistry();
        CallbackRegistry.Owner a=registry.registerOwner(new Object(),"fixture_a"),b=registry.registerOwner(new Object(),"fixture_b");
        final CallbackRegistry.Token outer=bind(registry,a,Callbacks.class),inner=bind(registry,b,Callbacks.class);
        final Clock clock=new Clock();
        final RuntimeTelemetry telemetry=new RuntimeTelemetry(registry,true,2,4,clock);
        RuntimeTelemetry.Attachment attachment=telemetry.attach();
        clock.value=10;
        int result=telemetry.call(outer,receiver,new RuntimeTelemetry.Body<Integer>() {
            public Integer run()throws Throwable {
                clock.value=20;int v=telemetry.call(inner,receiver,new RuntimeTelemetry.Body<Integer>() {
                    public Integer run() {
                        clock.value=50;return receiver.value(7);
                    }
                }
                );clock.value=100;return v;
            }
        }
        );
        check(result==22,"original direct return");
        Map<String,Object> initial=telemetry.snapshot();
        Map<String,Object> ar=row(initial,outer.slot()),br=row(initial,inner.slot());
        check(number(ar,"inclusive_ns")==90&&number(ar,"exclusive_ns")==60,"independent nested outer interval");
        check(number(br,"inclusive_ns")==30&&number(br,"exclusive_ns")==30,"independent nested child interval");
        // Recursion: outer duration30 + inner10, exclusive total20+10.
        clock.value=200;
        telemetry.call(outer,receiver,new RuntimeTelemetry.Body<Integer>() {
            public Integer run()throws Throwable {
                clock.value=210;telemetry.call(outer,receiver,new RuntimeTelemetry.Body<Integer>() {
                    public Integer run() {
                        clock.value=220;return 1;
                    }
                }
                );clock.value=230;return 2;
            }
        }
        );
        ar=row(telemetry.snapshot(),outer.slot());
        check(number(ar,"inclusive_ns")==130&&number(ar,"exclusive_ns")==90&&number(ar,"completed_known")==3,"recursive inclusive exclusive accounting");
        final RuntimeException original=new RuntimeException("original callback identity");
        clock.value=300;
        Throwable seen=null;
        try {
            telemetry.call(outer,receiver,new RuntimeTelemetry.Body<Integer>() {
                public Integer run() {
                    clock.value=340;throw original;
                }
            }
            );
        }
        catch(Throwable e) {
            seen=e;
        }
        check(seen==original,"exception object preserved");
        check(number(row(telemetry.snapshot(),outer.slot()),"callback_exceptions")==1,"exception counted");
        clock.fail=true;
        seen=null;
        try {
            telemetry.call(outer,receiver,new RuntimeTelemetry.Body<Integer>() {
                public Integer run() {
                    throw original;
                }
            }
            );
        }
        catch(Throwable e) {
            seen=e;
        }
        check(seen==original,"clock failure cannot replace callback exception");
        clock.fail=false;
        clock.value=400;
        telemetry.call(outer,receiver,new RuntimeTelemetry.Body<Integer>() {
            public Integer run() {
                clock.fail=true;return 4;
            }
        }
        );
        clock.fail=false;
        check(number(row(telemetry.snapshot(),outer.slot()),"unknown_inclusive")==1,"failed exit clock is unknown");
        clock.value=500;
        telemetry.call(outer,receiver,new RuntimeTelemetry.Body<Integer>() {
            public Integer run() {
                clock.value=490;return 5;
            }
        }
        );
        check(number(row(telemetry.snapshot(),outer.slot()),"unknown_inclusive")==2,"backward clock is unknown");
        Clock disabledClock=new Clock();
        disabledClock.fail=true;
        RuntimeTelemetry disabled=new RuntimeTelemetry(registry,false,1,1,disabledClock);
        RuntimeTelemetry.Attachment disabledAttachment=disabled.attach();
        check(disabled.call(outer,receiver,new RuntimeTelemetry.Body<Integer>() {
            public Integer run() {
                return 88;
            }
        }
        )==88,"disabled body result");
        check(disabledClock.reads==0,"disabled makes zero clock calls");
        disabledAttachment.close();
        disabled.close();
        // Invalid identity disables attribution; original behavior still executes.
        final Callbacks.Inherited inherited=new Callbacks.Inherited();
        check(telemetry.call(outer,inherited,new RuntimeTelemetry.Body<Integer>() {
            public Integer run() {
                return inherited.value(9);
            }
        }
        )==28,"unqualified subclass still calls original");
        CallbackRegistry.Token inheritedToken=bind(registry,a,Callbacks.Inherited.class);
        clock.value=600;
        check(telemetry.call(inheritedToken,inherited,new RuntimeTelemetry.Body<Integer>() {
            public Integer run() {
                clock.value=610;return inherited.value(2);
            }
        }
        )==7,"explicit exact inherited receiver");
        boolean refused=false;
        try {
            bind(registry,a,Callbacks.Override.class);
        }
        catch(IllegalArgumentException e) {
            refused=true;
        }
        check(refused,"override cannot bind parent method");
        check(((Integer)telemetry.invokeReflective(method,outer,receiver,3))==10,"reflection return preserved");
        Method failMethod=Callbacks.class.getMethod("fail",RuntimeException.class);
        CallbackRegistry.Token failToken=registry.bind(a,failMethod,Callbacks.class);
        seen=null;
        try {
            telemetry.invokeReflective(failMethod,failToken,receiver,original);
        }
        catch(Throwable e) {
            seen=e;
        }
        check(seen instanceof InvocationTargetException&&((InvocationTargetException)seen).getCause()==original,"reflection wrapping preserved");
        check(((Integer)telemetry.invokeReflective(method,failToken,receiver,4))==13,"mismatched telemetry token never replaces reflection target");
        CallbackRegistry foreign=new CallbackRegistry();
        CallbackRegistry.Owner foreignOwner=foreign.registerOwner(new Object(),"foreign");
        CallbackRegistry.Token foreignToken=bind(foreign,foreignOwner,Callbacks.class);
        check(telemetry.call(foreignToken,receiver,new RuntimeTelemetry.Body<Integer>() {
            public Integer run() {
                return 123;
            }
        }
        )==123,"foreign token refuses attribution only");
        // Same binary name and bytes, different defining loader is a distinct observed identity.
        final byte[] bytes=Files.readAllBytes(Paths.get(System.getProperty("observability.fixtureClasses"),"com/rustcraft/observability/fixtures/Callbacks.class"));
        ClassLoader isolated=new ClassLoader(null) {
            protected Class<?> findClass(String name)throws ClassNotFoundException {
                if(name.equals("com.rustcraft.observability.fixtures.Callbacks"))return defineClass(name,bytes,0,bytes.length);
                if(name.startsWith("com.rustcraft.telemetry."))return Class.forName(name,false,RuntimeTelemetry.class.getClassLoader());
                throw new ClassNotFoundException(name);
            }
        }
        ;
        Class<?> duplicate=Class.forName(Callbacks.class.getName(),true,isolated);
        final Object duplicateReceiver=duplicate.newInstance();
        final Method duplicateMethod=duplicate.getMethod("value",int.class);
        check(telemetry.call(outer,duplicateReceiver,new RuntimeTelemetry.Body<Object>() {
            public Object run()throws Throwable {
                return duplicateMethod.invoke(duplicateReceiver,5);
            }
        }
        ).equals(16),"different loader preserves behavior without old attribution");
        CallbackRegistry.Token duplicateToken=registry.bind(a,duplicateMethod,duplicate);
        check(!registry.identities().get(duplicateToken.slot()).get("class_and_loader_observation_token").equals(registry.identities().get(outer.slot()).get("class_and_loader_observation_token")),"opaque definition+loader identity differs");
        final CallbackRegistry.Dependency dependency=registry.dependency();
        final CallbackRegistry.Token revocable=registry.bind(a,method,Callbacks.class,dependency);
        clock.value=700;
        telemetry.call(revocable,receiver,new RuntimeTelemetry.Body<Integer>() {
            public Integer run() {
                dependency.revoke();clock.value=740;return 42;
            }
        }
        );
        check(number(row(telemetry.snapshot(),revocable.slot()),"unknown_inclusive")==1,"late dependency revocation invalidates attribution");
        check(telemetry.call(revocable,receiver,new RuntimeTelemetry.Body<Integer>() {
            public Integer run() {
                return 9;
            }
        }
        )==9,"revocation keeps original callback");
        // Depth overflow cannot silently become supposedly exact exclusive time.
        final Clock shallowClock=new Clock();
        final RuntimeTelemetry shallow=new RuntimeTelemetry(registry,true,1,1,shallowClock);
        RuntimeTelemetry.Attachment shallowAttachment=shallow.attach();
        shallow.call(outer,receiver,new RuntimeTelemetry.Body<Integer>() {
            public Integer run()throws Throwable {
                shallowClock.value=10;shallow.call(inner,receiver,new RuntimeTelemetry.Body<Integer>() {
                    public Integer run() {
                        shallowClock.value=20;return 1;
                    }
                }
                );shallowClock.value=30;return 2;
            }
        }
        );
        check(number(row(shallow.snapshot(),outer.slot()),"unknown_exclusive")==1,"depth overflow taints exclusive coverage");
        check(number(shallow.snapshot(),"depth_overflow_calls")==1,"overflow visible");
        shallowAttachment.close();
        shallow.close();
        attachment.close();
        check(number(telemetry.snapshot(),"attached_threads")==0,"detach releases thread slot");
        RuntimeTelemetry.Attachment fresh=telemetry.attach();
        fresh.close();
        check(number(telemetry.snapshot(),"attached_threads")==0,"reattach bounded lifecycle");
        boolean closeRefused=false;
        RuntimeTelemetry.Attachment stillAttached=telemetry.attach();
        try {
            telemetry.close();
        }
        catch(IllegalStateException e) {
            closeRefused=true;
        }
        check(closeRefused,"close requires detached workers");
        stillAttached.close();
        for(int i=0;i<1000;i++) {
            RuntimeTelemetry.Attachment cycle=telemetry.attach();
            cycle.close();
        }
        check(number(telemetry.snapshot(),"attached_threads")==0,"1000 attach detach cycles do not retain threads");
        check(MxBeanSampler.known(-1)==null&&MxBeanSampler.known(0).longValue()==0,"unknown MXBean negative distinct from zero");
        check(!registry.optimizationAuthority(),"metrics never confer authority");
        check(Boolean.FALSE.equals(registry.identities().get(revocable.slot()).get("measurement_binding_current")),"revoked dependency export is visibly invalid");
        CallbackRegistry.Token staticToken=registry.bind(a,Callbacks.class.getMethod("staticValue",int.class),null);
        check(telemetry.call(staticToken,null,new RuntimeTelemetry.Body<Integer>() {
            public Integer run() {
                return Callbacks.staticValue(2);
            }
        }
        )==7,"static callback exact binding");
        boolean unobserved=false;
        try {
            registry.bind(a,String.class.getMethod("length"),String.class);
        }
        catch(IllegalArgumentException expected) {
            unobserved=true;
        }
        check(unobserved,"unobserved definition rejected");
        final StageBuffer stage=new StageBuffer(2,true,clock);
        check(stage.record(StageBuffer.Stage.TICK,0)&&stage.record(StageBuffer.Stage.TICK,-1),"stage zero and unknown retained");
        check(!stage.record(StageBuffer.Stage.TICK,1)&&stage.dropped(StageBuffer.Stage.TICK)==1,"stage overflow explicit");
        Thread foreignThread=new Thread(new Runnable() {
            public void run() {
                stage.record(StageBuffer.Stage.TICK,2);
            }
        }
        );
        foreignThread.start();
        foreignThread.join();
        check(stage.dropped(StageBuffer.Stage.TICK)==2&&number(stage.coverage(),"foreign_thread_calls")==1,"foreign stage writer rejected and counted");
        check(!stage.record(StageBuffer.Stage.TICK,-2)&&stage.dropped(StageBuffer.Stage.TICK)==3,"invalid stage duration unknown loss");
        stage.resetAfterExport();
        clock.fail=true;
        seen=null;
        try {
            stage.measure(StageBuffer.Stage.TICK,new RuntimeTelemetry.Body<Integer>() {
                public Integer run() {
                    throw original;
                }
            }
            );
        }
        catch(Throwable expected) {
            seen=expected;
        }
        clock.fail=false;
        check(seen==original&&stage.retained()==1,"stage clock failure preserves callback exception");
        Clock never=new Clock();
        never.fail=true;
        StageBuffer noStage=new StageBuffer(1,false,never);
        check(noStage.measure(StageBuffer.Stage.TICK,new RuntimeTelemetry.Body<Integer>() {
            public Integer run() {
                return 18;
            }
        }
        )==18&&never.reads==0,"disabled stage has no clock reads");
        CallbackRegistry capped=new CallbackRegistry();
        CallbackRegistry.Owner co=capped.registerOwner(new Object(),"cap");
        for(int i=0;i<CallbackRegistry.MAX_METHODS;i++)bind(capped,co,Callbacks.class);
        boolean full=false;
        try {
            bind(capped,co,Callbacks.class);
        }
        catch(IllegalArgumentException expected) {
            full=true;
        }
        check(full&&capped.size()==64,"method registry capacity exact");
        capped.close();
        final boolean[] foreignResults=new boolean[3];
        final RuntimeTelemetry bounded=new RuntimeTelemetry(registry,true,1,2,clock);
        final RuntimeTelemetry.Attachment boundedAttachment=bounded.attach();
        Thread rejectedThread=new Thread(new Runnable() {
            public void run() {
                try {
                    foreignResults[0]=bounded.call(outer,receiver,new RuntimeTelemetry.Body<Integer>() {
                        public Integer run() {
                            return 99;
                        }
                    }
                    )==99;
                }
                catch(Throwable unexpected) {
                }
                try {
                    bounded.attach();
                }
                catch(IllegalStateException expected) {
                    foreignResults[1]=true;
                }
                try {
                    boundedAttachment.close();
                }
                catch(IllegalStateException expected) {
                    foreignResults[2]=true;
                }
            }
        }
        );
        rejectedThread.start();
        rejectedThread.join();
        check(foreignResults[0]&&number(bounded.snapshot(),"unattached_calls")==1,"unattached thread preserves body and exports gap");
        check(foreignResults[1],"worker capacity refuses second live thread");
        check(foreignResults[2]&&number(bounded.snapshot(),"attached_threads")==1,"wrong thread cannot detach owner");
        boundedAttachment.close();
        bounded.close();
        CallbackRegistry ownerCap=new CallbackRegistry();
        for(int i=0;i<16;i++)ownerCap.registerOwner(new Object(),"owner_"+i);
        boolean ownersFull=false;
        try {
            ownerCap.registerOwner(new Object(),"overflow");
        }
        catch(IllegalStateException expected) {
            ownersFull=true;
        }
        check(ownersFull,"owner registry fixed bound");
        ownerCap.close();
        final AssertionError originalError=new AssertionError("original Error object");
        seen=null;
        try {
            telemetry.call(outer,receiver,new RuntimeTelemetry.Body<Integer>() {
                public Integer run() {
                    throw originalError;
                }
            }
            );
        }
        catch(Throwable expected) {
            seen=expected;
        }
        check(seen==originalError,"Error identity preserved without catch conversion");
        long previousUnknown=number(row(telemetry.snapshot(),outer.slot()),"unknown_inclusive");
        final RuntimeTelemetry.Attachment interrupted=telemetry.attach();
        int detachedResult=telemetry.call(outer,receiver,new RuntimeTelemetry.Body<Integer>() { public Integer run() { interrupted.close();return 19; } });
        check(detachedResult==19&&number(row(telemetry.snapshot(),outer.slot()),"unknown_inclusive")==previousUnknown+1&&number(telemetry.snapshot(),"attached_threads")==0,"detach during callback records unknown and preserves body");
        definitionAttemptControl(new LifecycleVictims.Redefinition(),true);
        definitionAttemptControl(new LifecycleVictims.Duplicate(),false);
        check(DefinitionObserver.rejectedDefinitionAttempts()==4,"all four rejected definition attempts counted");
        Map<String,Object> exported=telemetry.snapshot();
        telemetry.close();
        registry.close();
        foreign.close();
        Map<String,Object> answer=new LinkedHashMap<String,Object>();
        answer.put("assertions",assertions);
        answer.put("rejected_definition_attempts",DefinitionObserver.rejectedDefinitionAttempts());
        answer.put("deterministic_clock_oracle","explicit independent intervals: A90/60 B30/30 recursive A40/30");
        answer.put("snapshot",exported);
        answer.put("mxbean",MxBeanSampler.sample());
        answer.put("status","PASS");
        return answer;
    }
}
