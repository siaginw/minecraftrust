package com.rustcraft.oracle;

import com.google.gson.GsonBuilder;
import com.rustcraft.bridge.PacketEncodeResultV2;
import com.rustcraft.bridge.capture.OwnedSnapshotBridge;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.lang.reflect.Field;
import java.security.MessageDigest;
import sun.misc.Unsafe;

/** Runs only inside the pinned, transformed offline LaunchWrapper process. */
public final class CleanForgeCaptureOracle {
    private static final List<Map<String,Object>> events = new ArrayList<Map<String,Object>>();
    private static final List<IBlockState> registry = new ArrayList<IBlockState>();
    private static final List<IBlockState> nonAir = new ArrayList<IBlockState>();
    private static int globalBits;
    private static String registryIdentity;
    private static long nativeCalls;
    private static final Unsafe U = unsafe();

    public static void main(String[] args) throws Exception {
        Path output = Paths.get(System.getProperty("rustcraft.oracleOutput"));
        Files.createDirectories(output);
        System.load(System.getProperty("rustcraft.nativeDll"));
        OwnedForgeCapture.requireNoListeners();
        List<Map<String,Object>> export = new ArrayList<Map<String,Object>>();
        for (Object state : Block.field_176229_d) registry.add((IBlockState) state);
        Collections.sort(registry, (a,b) -> Integer.compare(Block.field_176229_d.func_148747_b(a), Block.field_176229_d.func_148747_b(b)));
        for (IBlockState state : registry) {
            int id = Block.field_176229_d.func_148747_b(state);
            Map<String,Object> row = new LinkedHashMap<String,Object>(); row.put("id", id); row.put("state", state.toString());
            export.add(row); if (id != 0) nonAir.add(state);
        }
        int mapSize = Block.field_176229_d.func_186804_a();
        globalBits = 32 - Integer.numberOfLeadingZeros(mapSize - 1);
        byte[] registryBytes = json(export);
        registryIdentity = sha256(registryBytes);
        Files.write(output.resolve("registry.json"), registryBytes, StandardOpenOption.CREATE_NEW);
        if (globalBits < 9 || globalBits > 16 || nonAir.size() < 300) throw new AssertionError("unsupported registry");

        accepted("zero-sections", graph(true));
        OwnedForgeCapture g = graph(true); g.fill(0, nonAir, 1); accepted("one-section", g);
        g = graph(true); g.fill(15, nonAir, 1); accepted("sparse-high", g);
        g = graph(true); for (int y=0;y<5;y++) g.fill(y, nonAir, 1); accepted("terrain-001f", g);
        g = graph(false); g.fill(0, nonAir, 1); accepted("sky-off", g);
        g = graph(true); accepted("empty-before", g); g.fill(2, nonAir, 1); accepted("empty-to-nonempty", g); g.clear(2); accepted("nonempty-to-empty", g);
        g = graph(true); g.fill(0, nonAir, 20); accepted("local-palette-expansion", g);
        g.rewrite(0, nonAir, 300); accepted("local-to-global", g);
        List<IBlockState> wide=new ArrayList<IBlockState>(nonAir.subList(0,299));wide.add(nonAir.get(nonAir.size()-1));
        if(Block.field_176229_d.func_148747_b(wide.get(299)) >= (1 << globalBits)) {
            g=graph(true);g.fill(0,wide,300);rejected("global-registry-width",g,g.incarnation(),g.generation());
        }
        g = graph(true); g.fill(0, nonAir, 1); g.lights(0,0); accepted("light-before", g);
        g.lights(85,0); accepted("block-light-change", g); g.lights(85,170); accepted("sky-light-change", g);
        g.biome(4); accepted("biome-change", g);
        g = graph(true); g.fill(0, nonAir, 1); accepted("storage-before", g); g.fill(0, nonAir, 2); accepted("storage-replacement-between-events", g);
        g = graph(true); g.fill(15, Collections.singletonList(Block.field_176229_d.func_148745_a(0)), 1); g.partial(32768); accepted("partial-present-empty", g);
        g=graph(true);g.fill(0,Collections.singletonList(Block.field_176229_d.func_148745_a(0)),1);accepted("present-empty-full",g);
        g.rewrite(0,nonAir,1);accepted("inplace-empty-to-nonempty",g);
        g.rewrite(0,Collections.singletonList(Block.field_176229_d.func_148745_a(0)),1);accepted("inplace-nonempty-to-empty",g);

        for (OwnedForgeCapture.Fault fault : OwnedForgeCapture.Fault.values()) if (fault != OwnedForgeCapture.Fault.NONE) {
            g = graph(true); g.fill(0, nonAir, 1); g.fault(fault); rejected("during-" + fault.name().toLowerCase(Locale.ROOT), g, g.incarnation(), g.generation());
        }
        g = graph(true); g.fill(0, nonAir, 1); rejected("stale-generation", g, g.incarnation(), g.generation()+1);
        long incarnation=g.incarnation(), generation=g.generation(); g.replace(); rejected("same-coordinate-replacement", g, incarnation, generation);
        g = graph(true); g.unload(); rejected("unloaded", g, g.incarnation(), g.generation());
        incarnation=g.incarnation(); generation=g.generation(); g.reload(); rejected("reload-stale-handle",g,incarnation,generation); accepted("reloaded-current",g);
        g = graph(true); g.addForbiddenTileEntity(); rejected("tile-entity-unqualified",g,g.incarnation(),g.generation());
        final OwnedForgeCapture off=graph(true); final OwnedForgeCapture.Pair[] offResult=new OwnedForgeCapture.Pair[1];
        Thread worker=new Thread(() -> { try { offResult[0]=off.capture(off.incarnation(),off.generation()); } catch(Exception e){ throw new RuntimeException(e); } }, "ServerThread");
        worker.start(); worker.join(); if(offResult[0]==null || offResult[0].rejection==null) throw new AssertionError("off-thread accepted");
        events.add(rejection("off-thread",offResult[0].rejection));
        // Selected-bit/missing-section and unsupported-ID native controls use
        // the exact real accepted transport, never invented successful metadata.
        g=graph(true);g.fill(0,nonAir,1); OwnedForgeCapture.Pair control=g.capture(g.incarnation(),g.generation());
        byte[] broken=control.snapshot.toTransportBytes(); broken[37]|=32;broken[39]|=32;
        nativeReject("native-missing-selected-section",broken,-4);
        broken=control.snapshot.toTransportBytes();broken[134]=0;broken[135]=1;broken[136]=0;broken[137]=0;
        nativeReject("native-extended-id",broken,-6);
        nativeReject("native-capacity",control.snapshot.toTransportBytes(),-5,1);
        mediumRejections();
        // Frozen registry identity must survive every graph and callback check.
        List<Map<String,Object>> endExport=new ArrayList<Map<String,Object>>();
        for(IBlockState state:registry){Map<String,Object> row=new LinkedHashMap<String,Object>();row.put("id",Block.field_176229_d.func_148747_b(state));row.put("state",state.toString());endExport.add(row);}
        if(!Arrays.equals(registryBytes,json(endExport)) || mapSize!=Block.field_176229_d.func_186804_a())throw new AssertionError("registry changed");
        Map<String,Object> result=new LinkedHashMap<String,Object>(); result.put("captureKind","REAL_CLEAN_FORGE_ORACLE");result.put("contractVersion","clean-forge-exclusive-owned-graph-v1");
        result.put("globalPaletteBits",globalBits);result.put("registryMapSize",mapSize);result.put("registrySha256",registryIdentity);
        result.put("ownerThread",Thread.currentThread().getId()); result.put("events",events);
        result.put("productionAuthorityEligible",false);result.put("serverStarted",false);
        Files.write(output.resolve("events.json"),json(result),StandardOpenOption.CREATE_NEW);
        System.out.println("REAL_CLEAN_FORGE_ORACLE_RAW events="+events.size());
    }
    private static OwnedForgeCapture graph(boolean sky)throws Exception{return new OwnedForgeCapture(sky,globalBits,"REAL_CLEAN_FORGE_ORACLE:"+registryIdentity);}
    private static void accepted(String name,OwnedForgeCapture graph)throws Exception {
        OwnedForgeCapture.Pair pair=graph.capture(graph.incarnation(),graph.generation());
        if(pair.rejection!=null)throw new AssertionError(name+": "+pair.rejection);
        byte[] transport=pair.transportForEncoding(); Native result=encode(transport,262144);
        if(!result.metadata.isSuccess())throw new AssertionError(name+": "+result.metadata.failure());
        Map<String,Object> row=new LinkedHashMap<String,Object>();row.put("name",name);row.put("status","ACCEPTED_PENDING_INDEPENDENT_COMPARISON");
        row.put("fixtureKind","REAL_CLEAN_FORGE_ORACLE_ACCEPTED");
        row.put("transport",Base64.getEncoder().encodeToString(transport));row.put("javaPacket",Base64.getEncoder().encodeToString(pair.javaPacket));
        row.put("tickRefCounts",pair.tickRefCounts);
        row.put("nativePayload",Base64.getEncoder().encodeToString(result.payload));row.put("v2Result",Long.toString(result.raw));events.add(row);
    }
    private static void rejected(String name,OwnedForgeCapture graph,long incarnation,long generation)throws Exception {
        OwnedForgeCapture.Pair pair=graph.capture(incarnation,generation);
        if(pair.rejection==null || pair.snapshot!=null || pair.javaPacket!=null)throw new AssertionError(name+" silently accepted");
        events.add(rejection(name,pair.rejection));
    }
    private static Map<String,Object> rejection(String name,String reason){Map<String,Object> row=new LinkedHashMap<String,Object>();row.put("name",name);row.put("status","EXPLICIT_SAFE_REJECTION");row.put("reason",reason);row.put("fixtureKind","REAL_CLEAN_FORGE_EXPECTED_REJECTION");return row;}

    private static void mediumRejections() throws Exception {
        OwnedForgeCapture g = graph(true); g.fill(0, nonAir, 1); g.witnessGraph();
        IBlockState unregistered = new net.minecraft.block.state.BlockStateContainer(nonAir.get(0).func_177230_c(),
                nonAir.get(0).func_177227_a().toArray(new net.minecraft.block.properties.IProperty<?>[0])).func_177621_b();
        int unknownId = Block.field_176229_d.func_148747_b(unregistered);
        if (unknownId >= 0 && Block.field_176229_d.func_148745_a(unknownId) == unregistered)
            throw new AssertionError("negative control unexpectedly registered");
        g.fill(1, Collections.singletonList(unregistered), 1);
        provedRejection("noncanonical-state", g, "FALLBACK_REGISTRY_GLOBAL_ID_WIDTH", "STATE_ADMISSION", true, "NOT_ATTEMPTED", 0);
        g=graph(true);g.fill(0,nonAir,1);g.witnessGraph();
        IBlockState missingProperties=new net.minecraft.block.state.BlockStateContainer(nonAir.get(0).func_177230_c()).func_177621_b();
        g.fill(1,Collections.singletonList(missingProperties),1);
        provedRejection("malformed-state-properties",g,"FALLBACK_INVALID_STATE_INPUT","STATE_PROPERTY_ADMISSION",true,"NOT_ATTEMPTED",0);

        g=graph(true); g.unsupportedSectionSubclass(); g.witnessGraph();
        provedRejection("unsupported-storage-subclass",g,"FALLBACK_SOURCE_EXCEPTION","STORAGE_ADMISSION",true,"NOT_ATTEMPTED",0);
        IBlockState high = null;
        for (IBlockState state : nonAir) if (Block.field_176229_d.func_148747_b(state) >= 512) { high=state; break; }
        if (high == null) throw new AssertionError("qualified registry lacks width control");
        g=new OwnedForgeCapture(true,9,"REAL_CLEAN_FORGE_ORACLE:"+registryIdentity); g.fill(0,nonAir,1); g.witnessGraph();
        g.fill(1,Collections.singletonList(high),1);
        provedRejection("incompatible-registry-width",g,"FALLBACK_REGISTRY_GLOBAL_ID_WIDTH","GLOBAL_WIDTH_ADMISSION",true,"NOT_ATTEMPTED",0);
        g=new OwnedForgeCapture(true,8,"REAL_CLEAN_FORGE_ORACLE:"+registryIdentity);g.fill(0,nonAir,1);g.witnessGraph();
        provedRejection("invalid-palette-width",g,"FALLBACK_INVALID_INPUT","METADATA_ADMISSION",true,"NOT_ATTEMPTED",4096);
        g=graph(true);g.fill(0,nonAir,1);g.partial(-1);g.witnessGraph();
        provedRejection("invalid-request-filter",g,"FALLBACK_INVALID_INPUT","FILTER_ADMISSION",true,"NOT_ATTEMPTED",4096);

        for (boolean inPlace : new boolean[] {false,true}) {
            g=graph(true);g.fill(0,nonAir,1);g.witnessGraph();
            List<IBlockState> throwing = new AbstractList<IBlockState>() {
                private int reads;
                public int size() { return 1; }
                public IBlockState get(int index) {
                    if (index != 0) throw new IndexOutOfBoundsException();
                    // One admission read, then 17 real setter operations.
                    if (++reads > 18) throw new IllegalStateException("bounded failing state source");
                    return nonAir.get(nonAir.size()-1);
                }
            };
            try {
                if (inPlace) g.rewrite(0,throwing,1); else g.fill(1,throwing,1);
                throw new AssertionError("partial rewrite unexpectedly completed");
            } catch (IllegalStateException expected) { }
            if (g.rewriteCellsWritten()!=17) throw new AssertionError("failure did not follow exact partial rewrite progress");
            provedRejection(inPlace ? "failed-inplace-rewrite" : "failed-scratch-rewrite",g,
                    "FALLBACK_MUTATION_INCOMPLETE",inPlace ? "INPLACE_REWRITE" : "TEMPORARY_SECTION_REWRITE",
                    !inPlace,"REJECTED_POISONED_SOURCE",17);
        }
        for (OwnedForgeCapture.ScratchFault fault : OwnedForgeCapture.ScratchFault.values()) {
            if (fault == OwnedForgeCapture.ScratchFault.NONE) continue;
            g=graph(true);g.fill(0,nonAir,1);g.witnessGraph();g.scratchFault(fault);
            String name="capture-failure-"+fault.name().toLowerCase(Locale.ROOT).replace('_','-');
            provedRejection(name,g,fault==OwnedForgeCapture.ScratchFault.WIDE_ID ? "FALLBACK_EXTENDED_ID" : "FALLBACK_SOURCE_EXCEPTION",
                    fault.name(),true,"ACCEPTED_AFTER_TRANSIENT_FAILURE",fault==OwnedForgeCapture.ScratchFault.LOGICAL_CONVERSION ? 17 : 4096);
        }
        // Constructor guards are exercised directly as package-local controls;
        // malformed result shapes never reach the transport accessor or JNI.
        try { new OwnedForgeCapture.Pair(null,null,null,null); throw new AssertionError("partial Pair eligible"); }
        catch (IllegalArgumentException expected) { }
        // The explicit non-null rejected packet shape must also be refused.
        try { new OwnedForgeCapture.Pair("rejected",null,new byte[0],null); throw new AssertionError("rejected Pair retained packet"); }
        catch (IllegalArgumentException expected) { }
    }

    private static void provedRejection(String name, OwnedForgeCapture graph, String expectedReason,
            String stage, boolean unchanged, String retryOutcome, int expectedCells) throws Exception {
        long beforeNative=nativeCalls;
        OwnedForgeCapture.Pair pair=graph.capture(graph.incarnation(),graph.generation());
        if (!expectedReason.equals(pair.rejection) || pair.snapshot!=null || pair.javaPacket!=null || pair.tickRefCounts!=null)
            throw new AssertionError(name+" published partial data: "+pair.rejection);
        boolean denied=false;
        try { encode(pair.transportForEncoding(),262144); }
        catch (IllegalStateException expected) { denied=true; }
        long callsDuringFailure=nativeCalls-beforeNative;
        if (!denied || callsDuringFailure!=0) throw new AssertionError(name+" rejected transport reached JNI");
        if (graph.witnessedGraphUnchanged()!=unchanged) throw new AssertionError(name+" unexpected owned graph modification");
        int cells=stage.endsWith("REWRITE") ? graph.rewriteCellsWritten() : graph.scratchCellsWritten();
        if (cells!=expectedCells) throw new AssertionError(name+" scratch progress "+cells+" != "+expectedCells);
        if (retryOutcome.equals("ACCEPTED_AFTER_TRANSIENT_FAILURE") && !graph.scratchFired())
            throw new AssertionError(name+" injection did not execute");
        Map<String,Object> proof=new LinkedHashMap<String,Object>();
        proof.put("stage",stage);proof.put("snapshotPublished",false);proof.put("javaPacketPublished",false);
        proof.put("nativeCallsDuringFailure",callsDuringFailure);proof.put("rejectedTransportDenied",denied);
        proof.put("ownedGraphUnchanged",unchanged);proof.put("existingWorldUntouched",true);
        proof.put("ownedGraphBeforeSha256",graph.witnessedBeforeSha256());proof.put("ownedGraphAfterSha256",graph.witnessedAfterSha256());
        proof.put("scratchCellsWritten",cells);proof.put("retryOutcome",retryOutcome);
        if (retryOutcome.equals("ACCEPTED_AFTER_TRANSIENT_FAILURE")) {
            graph.scratchFault(OwnedForgeCapture.ScratchFault.NONE);
            OwnedForgeCapture.Pair retry=graph.capture(graph.incarnation(),graph.generation());
            if (retry.rejection!=null || !encode(retry.transportForEncoding(),262144).metadata.isSuccess()
                    || !graph.witnessedGraphUnchanged()) throw new AssertionError(name+" complete retry failed");
        } else if (retryOutcome.equals("REJECTED_POISONED_SOURCE")) {
            graph.scratchFault(OwnedForgeCapture.ScratchFault.NONE);
            OwnedForgeCapture.Pair retry=graph.capture(graph.incarnation(),graph.generation());
            if (!expectedReason.equals(retry.rejection) || retry.snapshot!=null || retry.javaPacket!=null)
                throw new AssertionError(name+" poisoned source became eligible");
        }
        Map<String,Object> row=rejection(name,pair.rejection);row.put("proof",proof);events.add(row);
    }
    private static void nativeReject(String name,byte[] input,long error){nativeReject(name,input,error,262144);}
    private static void nativeReject(String name,byte[] input,long error,int capacity){Native result=encode(input,capacity);if(result.raw!=error || result.payload!=null)throw new AssertionError(name+" error="+result.raw);events.add(rejection(name,"V2_"+error));}
    private static final class Native{long raw;PacketEncodeResultV2 metadata;byte[] payload;}
    private static Native encode(byte[] input,int capacity){long src=U.allocateMemory(input.length),dst=U.allocateMemory(capacity);try{
        for(int i=0;i<input.length;i++)U.putByte(src+i,input[i]); Native r=new Native();nativeCalls++;r.raw=OwnedSnapshotBridge.encodeOwnedV1(src,input.length,dst,capacity);r.metadata=PacketEncodeResultV2.decode(r.raw);
        if(r.metadata.isSuccess()){if(r.metadata.bytesWritten()>capacity)throw new AssertionError("native output overrun");r.payload=new byte[r.metadata.bytesWritten()];for(int i=0;i<r.payload.length;i++)r.payload[i]=U.getByte(dst+i);}return r;
    }finally{U.freeMemory(src);U.freeMemory(dst);}}
    private static Unsafe unsafe(){try{Field f=Unsafe.class.getDeclaredField("theUnsafe");f.setAccessible(true);return(Unsafe)f.get(null);}catch(Exception e){throw new ExceptionInInitializerError(e);}}
    private static byte[] json(Object value){return(new GsonBuilder().disableHtmlEscaping().create().toJson(value)+"\n").getBytes(StandardCharsets.UTF_8);}
    private static String sha256(byte[] bytes)throws Exception{StringBuilder s=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))s.append(String.format("%02x",b&255));return s.toString();}
}
