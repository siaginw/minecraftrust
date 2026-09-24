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
        byte[] transport=pair.snapshot.toTransportBytes(); Native result=encode(transport,262144);
        if(!result.metadata.isSuccess())throw new AssertionError(name+": "+result.metadata.failure());
        Map<String,Object> row=new LinkedHashMap<String,Object>();row.put("name",name);row.put("status","ACCEPTED_PENDING_INDEPENDENT_COMPARISON");
        row.put("transport",Base64.getEncoder().encodeToString(transport));row.put("javaPacket",Base64.getEncoder().encodeToString(pair.javaPacket));
        row.put("tickRefCounts",pair.tickRefCounts);
        row.put("nativePayload",Base64.getEncoder().encodeToString(result.payload));row.put("v2Result",Long.toString(result.raw));events.add(row);
    }
    private static void rejected(String name,OwnedForgeCapture graph,long incarnation,long generation)throws Exception {
        OwnedForgeCapture.Pair pair=graph.capture(incarnation,generation);
        if(pair.rejection==null || pair.snapshot!=null || pair.javaPacket!=null)throw new AssertionError(name+" silently accepted");
        events.add(rejection(name,pair.rejection));
    }
    private static Map<String,Object> rejection(String name,String reason){Map<String,Object> row=new LinkedHashMap<String,Object>();row.put("name",name);row.put("status","EXPLICIT_SAFE_REJECTION");row.put("reason",reason);return row;}
    private static void nativeReject(String name,byte[] input,long error){nativeReject(name,input,error,262144);}
    private static void nativeReject(String name,byte[] input,long error,int capacity){Native result=encode(input,capacity);if(result.raw!=error || result.payload!=null)throw new AssertionError(name+" error="+result.raw);events.add(rejection(name,"V2_"+error));}
    private static final class Native{long raw;PacketEncodeResultV2 metadata;byte[] payload;}
    private static Native encode(byte[] input,int capacity){long src=U.allocateMemory(input.length),dst=U.allocateMemory(capacity);try{
        for(int i=0;i<input.length;i++)U.putByte(src+i,input[i]); Native r=new Native();r.raw=OwnedSnapshotBridge.encodeOwnedV1(src,input.length,dst,capacity);r.metadata=PacketEncodeResultV2.decode(r.raw);
        if(r.metadata.isSuccess()){if(r.metadata.bytesWritten()>capacity)throw new AssertionError("native output overrun");r.payload=new byte[r.metadata.bytesWritten()];for(int i=0;i<r.payload.length;i++)r.payload[i]=U.getByte(dst+i);}return r;
    }finally{U.freeMemory(src);U.freeMemory(dst);}}
    private static Unsafe unsafe(){try{Field f=Unsafe.class.getDeclaredField("theUnsafe");f.setAccessible(true);return(Unsafe)f.get(null);}catch(Exception e){throw new ExceptionInInitializerError(e);}}
    private static byte[] json(Object value){return(new GsonBuilder().disableHtmlEscaping().create().toJson(value)+"\n").getBytes(StandardCharsets.UTF_8);}
    private static String sha256(byte[] bytes)throws Exception{StringBuilder s=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))s.append(String.format("%02x",b&255));return s.toString();}
}
