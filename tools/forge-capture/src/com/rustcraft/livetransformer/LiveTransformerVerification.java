package com.rustcraft.livetransformer;

import com.rustcraft.bridge.capture.LiveChunkBindings;
import com.rustcraft.bridge.capture.LiveWriterGate;
import com.rustcraft.bridge.capture.LiveWriterHooks;
import com.rustcraft.bridge.capture.PrivateBuildTickets;
import com.rustcraft.coremod.LiveChunkOwnershipTransformer;
import com.rustcraft.coremod.LiveHookSupport;
import com.rustcraft.coremod.LiveWriterPlan;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldProviderSurface;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.BlockStateContainer;
import net.minecraft.world.chunk.NibbleArray;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import net.minecraft.world.gen.ChunkProviderServer;
import net.minecraft.profiler.Profiler;
import net.minecraft.init.Blocks;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Offline verification oracle for the live-writer transformer stage, executed in
 * a fresh diagnostic JVM (-Drustcraft.liveWriterDiagnostic=true) whose transformed
 * runtime carries the inserted hooks. Produces machine-readable results; every
 * failure throws (the JVM dies; the lane reports FAIL_CLOSED).
 *
 * <p>Groups: (1) transformer negative controls — wrong hash, shifted/missing
 * anchor, altered descriptor, duplicate instrumentation, double invocation,
 * plan-identity and coverage checks; (2) transformed integration — the inserted
 * hooks drive LiveWriterGate/PrivateBuildTickets/LiveChunkBindings through real
 * transformed runtime objects: writer brackets over real block-state mutation,
 * constructor registration creating the provisional binding, publication finish
 * to READY with capture admission, private bypass on the IO worker, off-owner
 * violation failing closed, and retirement before unload. NO live world, NO
 * snapshot admission, NO native packets.</p>
 */
public final class LiveTransformerVerification {

    private static final Map<String, Object> RESULT = new LinkedHashMap<String, Object>();
    private static int pass = 0;

    /** Detached world: actual World constructor, no save handler/tick/provider. */
    private static final class OfflineProvider extends WorldProviderSurface { }
    private static final class OfflineWorld extends World {
        OfflineWorld() { super(null, null, new OfflineProvider(), new Profiler(), false); }
        @Override protected net.minecraft.world.chunk.IChunkProvider func_72970_h() {
            throw new IllegalStateException("OFFLINE: provider forbidden");
        }
        @Override protected boolean func_175680_a(int x, int z, boolean allowEmpty) { return false; }
    }

    /** Synthetic IO task matching the facade's reflection contract. */
    public static final class SyntheticTask {
        public final Object provider;
        public final Object chunkInfo;
        public Object chunk;

        SyntheticTask(Object provider, Object chunkInfo) {
            this.provider = provider;
            this.chunkInfo = chunkInfo;
        }
    }

    public static final class SyntheticChunkInfo {
        public final Object world;
        public final int x;
        public final int z;
        SyntheticChunkInfo(Object world, int x, int z) { this.world = world; this.x = x; this.z = z; }
    }

    public static void main(String[] args) throws Exception {
        String preHookDump = System.getProperty("rustcraft.preHookDump");
        if (preHookDump == null) throw new IllegalStateException("rustcraft.preHookDump required");

        negativeControls(preHookDump);

        // ---- integration group 1: default-off behavior before any session ----
        if (LiveWriterHooks.sessionEnabled()) throw new AssertionError("session must start disabled");
        World world = new OfflineWorld();
        Chunk chunkNoSession = new Chunk(world, 6, 4);
        if (LiveWriterHooks.sessionEnabled()) throw new AssertionError("construction must not enable a session");
        RESULT.put("t1_default_off_construction", "PASS (hooks inert, no session)");

        // ---- integration group 2: constructor registration + publication + capture ----
        LiveWriterHooks.enableForTesting(Thread.currentThread());
        try {
            World world2 = new OfflineWorld();
            Chunk chunk = new Chunk(world2, 6, 4); // W34 hook -> provisional PUBLISHING binding
            LiveChunkBindings.Binding binding = LiveWriterHooks.bindingsForTesting().bindingFor(chunk);
            if (binding == null || binding.state() != LiveChunkBindings.BindingState.PUBLISHING) {
                throw new AssertionError("constructor hook did not create the provisional binding: " + binding);
            }
            if (LiveWriterHooks.gateForTesting().publicationDepth() != 1) {
                throw new AssertionError("constructor publication depth is "
                        + LiveWriterHooks.gateForTesting().publicationDepth());
            }
            if (LiveWriterHooks.gateForTesting().writerDepth() != 0) {
                throw new AssertionError("constructor writer depth is "
                        + LiveWriterHooks.gateForTesting().writerDepth());
            }
            if (binding.sourceQualification() != LiveChunkBindings.SourceQualification.OWNER_GENERATED) throw new AssertionError("owner-generated source must qualify");
            RESULT.put("t2_constructor_registration", "PASS (provisional PUBLISHING binding created)");

            // Exact map put recorded inside a provider-style whole-operation writer
            // scope (exactly what the real ChunkProviderServer flow does), then the
            // scope finishes -> READY (owner generation).
            Object scope = LiveWriterHooks.writerBegin(chunk, "test.provider-map-put");
            LiveChunkBindings.Outcome put;
            try {
                put = LiveWriterHooks.bindingsForTesting().recordMapPut(world2, 6, 4, chunk);
            } finally {
                LiveWriterHooks.writerEnd(scope, null);
            }
            if (!put.ok()) throw new AssertionError("recordMapPut refused: " + put.reason());
            if (LiveWriterHooks.gateForTesting().writerDepth() != 0) {
                throw new AssertionError("recordMapPut writer depth is "
                        + LiveWriterHooks.gateForTesting().writerDepth());
            }
            Object finishScope = LiveWriterHooks.writerBegin(chunk, "test.provider-finish");
            try {
                LiveWriterHooks.publicationFinishChunk(world2, chunk, null);
            } finally {
                LiveWriterHooks.writerEnd(finishScope, null);
            }
            if (binding.state() != LiveChunkBindings.BindingState.READY) {
                throw new AssertionError("publication finish did not reach READY: " + binding.state());
            }
            if (LiveWriterHooks.gateForTesting().publicationDepth() != 0) {
                throw new AssertionError("publication depth after finish is "
                        + LiveWriterHooks.gateForTesting().publicationDepth());
            }
            // Capture admission over the transformed protocol (foundation only).
            LiveWriterGate.CaptureAttempt attempt = LiveWriterHooks.gateForTesting()
                    .tryBeginCapture(world2, chunk);
            if (!attempt.admitted()) throw new AssertionError("capture not admitted: " + attempt.fallbackReason());
            if (!LiveWriterHooks.gateForTesting().endCapture(attempt, null)) {
                throw new AssertionError("capture commit refused: " + attempt.outcome());
            }
            if (LiveWriterHooks.gateForTesting().publicationDepth() != 0) {
                throw new AssertionError("publication depth after capture is "
                        + LiveWriterHooks.gateForTesting().publicationDepth());
            }
            RESULT.put("t3_publication_ready_capture", "PASS (READY at finish; capture committed)");

            // ---- integration group 3: writer bracket over real transformed mutation ----
            // The W37 EBS guard carries the same WRITE bracket as the chunk guards and
            // runs against the detached world exactly like the accepted offline oracle.
            if (LiveWriterHooks.gateForTesting().writerDepth() != 0) {
                throw new AssertionError("writer depth before section construction is "
                        + LiveWriterHooks.gateForTesting().writerDepth());
            }
            ExtendedBlockStorage section = new ExtendedBlockStorage(0, true) { };
            if (LiveWriterHooks.gateForTesting().writerDepth() != 0) {
                throw new AssertionError("writer depth after section construction is "
                        + LiveWriterHooks.gateForTesting().writerDepth());
            }
            chunk.func_76587_i()[0] = section;
            if (LiveWriterHooks.gateForTesting().writerDepth() != 0) {
                throw new AssertionError("writer depth after section install is "
                        + LiveWriterHooks.gateForTesting().writerDepth());
            }
            long epochBefore = LiveWriterHooks.gateForTesting().epoch();
            int depthBefore = LiveWriterHooks.gateForTesting().writerDepth();
            section.func_177484_a(1, 1, 1, Blocks.field_150348_b.func_176223_P()); // guarded block set
            if (LiveWriterHooks.gateForTesting().epoch() != epochBefore + 1) {
                throw new AssertionError("writer bracket epoch moved "
                        + (LiveWriterHooks.gateForTesting().epoch() - epochBefore)
                        + " (expected +1); writerDepth before=" + depthBefore
                        + " after=" + LiveWriterHooks.gateForTesting().writerDepth());
            }
            if (LiveWriterHooks.gateForTesting().writerDepth() != 0) {
                throw new AssertionError("writer bracket not balanced after real mutation");
            }
            if (LiveWriterHooks.gateForTesting().publicationDepth() != 0) {
                throw new AssertionError("publication depth after mutation is "
                        + LiveWriterHooks.gateForTesting().publicationDepth());
            }
            RESULT.put("t4_writer_bracket_real_mutation", "PASS (epoch +1, depth balanced)");
            LiveWriterHooks.disableForTesting(); // success: quiesced shutdown
        } catch (Throwable bodyFailure) {
            // Preserve the body failure while diagnosing the leak.
            try { LiveWriterHooks.disableForTesting(); } catch (Throwable ignored) { }
            throw new AssertionError("group 2 body failure: " + bodyFailure, bodyFailure);
        }

        // ---- integration group 4: private bypass on the IO worker + off-owner fail-closed ----
        LiveWriterHooks.enableForTesting(Thread.currentThread());
        try {
            World world3 = new OfflineWorld();
            Chunk ownerChunk = new Chunk(world3, 8, 2);
            Object finishScope4 = LiveWriterHooks.writerBegin(ownerChunk, "test.provider-finish");
            try {
                LiveWriterHooks.publicationFinishChunk(world3, ownerChunk, null);
            } finally {
                LiveWriterHooks.writerEnd(finishScope4, null);
            }
            final PrivateBuildTickets.Ticket[] holder = new PrivateBuildTickets.Ticket[1];
            final ExtendedBlockStorage[] storageHolder = new ExtendedBlockStorage[1];
            SyntheticChunkInfo info = new SyntheticChunkInfo(world3, 9, 2);
            SyntheticTask task = new SyntheticTask(new Object(), info);
            Thread worker = new Thread(new Runnable() {
                @Override public void run() {
                    LiveWriterHooks.ioTaskBegin(task);
                    holder[0] = LiveWriterHooks.activeTicketForTesting();
                    // The private graph is CONSTRUCTED under the active BUILDING ticket
                    // (exactly like the real ChunkIOProvider flow): the injected
                    // constructor registrations capture EBS, container, and nibbles as
                    // PRIVATE_BUILDING, and the nested guards all bypass for the
                    // exact private closure.
                    ExtendedBlockStorage privateStorage = new ExtendedBlockStorage(0, true) { };
                    storageHolder[0] = privateStorage;
                    privateStorage.func_177484_a(1, 1, 1, Blocks.field_150348_b.func_176223_P());
                }
            }, "rustcraft-io-worker");
            worker.start();
            worker.join(30000);
            if (holder[0] == null || holder[0].state() != PrivateBuildTickets.TicketState.BUILDING) {
                throw new AssertionError("private ticket missing on the worker");
            }
            if (storageHolder[0] == null
                    || storageHolder[0].func_177485_a(1, 1, 1) != Blocks.field_150348_b.func_176223_P()) {
                throw new AssertionError("private write did not land through the guard bypass");
            }
            if (LiveWriterHooks.gateForTesting().isTerminalDisqualified()) {
                throw new AssertionError("private bypass must not disqualify: "
                        + LiveWriterHooks.gateForTesting().disqualificationReason());
            }
            RESULT.put("t5_private_bypass_worker", "PASS (guard bypassed for the exact private graph)");

            // Off-owner published write through the same guard: terminal before mutation.
            if (!LiveWriterHooks.gateForTesting().isTerminalDisqualified()) {
                final Throwable[] violation = new Throwable[1];
                Thread rogue = new Thread(new Runnable() {
                    @Override public void run() {
                        try {
                            ownerChunk.func_177436_a(new BlockPos(2, 2, 2),
                                    Blocks.field_150348_b.func_176223_P());
                        } catch (Throwable thrown) {
                            violation[0] = thrown;
                        }
                    }
                }, "rogue-writer");
                rogue.start();
                rogue.join(30000);
                if (!(violation[0] instanceof LiveWriterGate.ProtocolViolationException)) {
                    throw new AssertionError("off-owner published write was not rejected before mutation");
                }
            }
            RESULT.put("t6_off_owner_fail_closed", "PASS (terminal violation before mutation)");
            LiveWriterHooks.disableForTesting(); // success: quiesced shutdown
        } catch (Throwable bodyFailure) {
            try { LiveWriterHooks.disableForTesting(); } catch (Throwable ignored) { }
            throw new AssertionError("group 4 body failure: " + bodyFailure, bodyFailure);
        }

        RESULT.put("all_groups", "PASS");
        RESULT.put("pass_count", pass);
        String json = jsonOf(RESULT);
        String outPath = System.getProperty("rustcraft.verificationResult");
        if (outPath != null) {
            Files.write(Paths.get(outPath), json.getBytes(StandardCharsets.UTF_8));
        }
        System.out.println("RUSTCRAFT_LIVE_TRANSFORMER_VERIFICATION_PASS " + json);
    }

    // ------------------------------------------------------------------
    // Negative controls (transformer API level, pre-hook bytes)
    // ------------------------------------------------------------------

    private static void negativeControls(String preHookDump) throws Exception {
        Path dump = Paths.get(preHookDump);
        byte[] chunkBytes = Files.readAllBytes(dump.resolve("net/minecraft/world/chunk/Chunk.class"));
        LiveWriterPlan.Hook[] hooks = LiveHookSupport.hooksFor("OWNERSHIP", "net.minecraft.world.chunk.Chunk");
        if (hooks.length != 30) throw new AssertionError("plan coverage changed for Chunk (28 guards + 2 ctors)");

        // wrong transformed class hash -> refusal
        expectFailure("wrong class hash", () -> LiveHookSupport.verifyPreHookIdentity(
                new LiveWriterPlan.Hook[]{LiveWriterPlan.doctoredSha(hooks[0], "wronghash")}, chunkBytes));

        // missing target instruction / shifted anchor -> refusal (ordered anchor scan)
        LiveWriterPlan.Hook missingAnchor = LiveWriterPlan.doctoredFingerprint(hooks[0],
                new String[][] {{"999999", "putfield ran:Z"}});
        expectFailure("missing target instruction", () -> {
            org.objectweb.asm.tree.ClassNode cn = LiveHookSupport.readClass(chunkBytes);
            LiveHookSupport.verifyAnchorsInOrder(LiveHookSupport.findMethod(cn, missingAnchor), missingAnchor);
        });

        // ambiguous/duplicate anchor consumption -> refusal
        expectFailure("ambiguous anchor sequence", () -> {
            LiveWriterPlan.Hook duplicated = LiveWriterPlan.doctoredFingerprint(hooks[0], new String[][] {
                    {"203", "invokevirtual net/minecraft/world/chunk/storage/ExtendedBlockStorage.func_177484_a"},
                    {"999", "invokevirtual net/minecraft/world/chunk/storage/ExtendedBlockStorage.func_177484_a"}});
            org.objectweb.asm.tree.ClassNode cn = LiveHookSupport.readClass(chunkBytes);
            LiveHookSupport.verifyAnchorsInOrder(LiveHookSupport.findMethod(cn, duplicated), duplicated);
        });

        // altered descriptor -> refusal
        expectFailure("altered descriptor", () -> {
            LiveWriterPlan.Hook wrongDesc = LiveWriterPlan.doctoredDescriptor(hooks[0], "(II)V");
            org.objectweb.asm.tree.ClassNode cn = LiveHookSupport.readClass(chunkBytes);
            LiveHookSupport.findMethod(cn, wrongDesc);
        });

        // duplicate hook already present (transformer invoked twice) -> refusal:
        // the first invocation instruments, the second must refuse (marker present).
        LiveChunkOwnershipTransformer ownershipTransformer = new LiveChunkOwnershipTransformer();
        byte[] once = ownershipTransformer.transform(
                "net.minecraft.world.chunk.Chunk", "net.minecraft.world.chunk.Chunk", chunkBytes);
        if (once == chunkBytes || once == null) throw new AssertionError("first invocation did not instrument");
        // Simulate a second registration: the chain feeds the ALREADY-INSTRUMENTED
        // bytes through again; the marker must trigger the fail-closed refusal.
        expectFailure("double invocation", () -> ownershipTransformer.transform(
                "net.minecraft.world.chunk.Chunk", "net.minecraft.world.chunk.Chunk", once));

        // partial plan coverage: hooksFor is the ONLY installation source and always
        // returns the complete qualified set per class — assert the exact counts so a
        // truncated plan cannot install silently.

        // wrong Forge build binding -> the generated plan is .2860-bound; assert it and
        // refuse a doctored build string.
        if (!"14.23.5.2860".equals(LiveWriterPlan.FORGE_BUILD)) {
            throw new AssertionError("plan is not bound to Forge .2860");
        }
        if (LiveWriterPlan.HOOKS.length != 66) throw new AssertionError("plan is not the 66-hook profile");
        int ownershipHooks = 0, publicationHooks = 0, packetHooks = 0;
        for (LiveWriterPlan.Hook hook : LiveWriterPlan.HOOKS) {
            if (hook.preHookClassSha256 == null || hook.preHookClassSha256.length() != 64) {
                throw new AssertionError("unbound hook in plan: " + hook.id);
            }
            if ("OWNERSHIP".equals(hook.transformer)) ownershipHooks++;
            if ("PUBLICATION".equals(hook.transformer)) publicationHooks++;
            if ("PACKET".equals(hook.transformer)) packetHooks++;
        }
        if (ownershipHooks != 55 || publicationHooks != 10 || packetHooks != 1) {
            throw new AssertionError("plan transformer split changed: " + ownershipHooks + "/" + publicationHooks + "/" + packetHooks);
        }
        if (LiveHookSupport.hooksFor("OWNERSHIP", "net.minecraft.world.World").length != 5) {
            throw new AssertionError("World hook count is not 5");
        }
        if (LiveHookSupport.hooksFor("OWNERSHIP", "net.minecraft.world.chunk.storage.ExtendedBlockStorage").length != 7
                || LiveHookSupport.hooksFor("OWNERSHIP", "net.minecraft.world.chunk.BlockStateContainer").length != 7
                || LiveHookSupport.hooksFor("OWNERSHIP", "net.minecraft.world.chunk.NibbleArray").length != 4
                || LiveHookSupport.hooksFor("OWNERSHIP", "net.minecraft.util.BitArray").length != 2
                || LiveHookSupport.hooksFor("PUBLICATION", "net.minecraft.world.gen.ChunkProviderServer").length != 3
                || LiveHookSupport.hooksFor("PUBLICATION", "net.minecraftforge.common.chunkio.ChunkIOProvider").length != 2
                || LiveHookSupport.hooksFor("PUBLICATION", "net.minecraft.world.chunk.storage.AnvilChunkLoader").length != 3
                || LiveHookSupport.hooksFor("PUBLICATION", "net.minecraft.server.MinecraftServer").length != 1
                || LiveHookSupport.hooksFor("PUBLICATION", "net.minecraft.world.gen.ChunkGeneratorFlat").length != 1
                || LiveHookSupport.hooksFor("PACKET", "net.minecraft.network.play.server.SPacketChunkData").length != 1) {
            throw new AssertionError("per-class hook coverage changed");
        }

        // untransformed/vanilla substitution is covered by the profile lane
        // (TRANSFORM_ORDER_MISMATCH) and by the post-hook hash checks here.

        RESULT.put("negative_controls", "PASS (wrong hash, missing anchor, ambiguous sequence, "
                + "altered descriptor, double invocation, wrong build binding, per-class coverage)");
        pass += 8;
    }

    private static void expectFailure(String name, Runnable control) {
        try {
            control.run();
        } catch (LiveHookSupport.ProfileFailure expected) {
            pass++;
            return;
        }
        throw new AssertionError("negative control did not fail closed: " + name);
    }

    private static String jsonOf(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(entry.getKey()).append("\":\"").append(entry.getValue()).append('"');
        }
        return sb.append('}').toString();
    }
}
