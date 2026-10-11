package com.rustcraft.coremod;

import java.util.ArrayList;
import java.util.List;

/**
 * Places the three qualified RustCraft writers at the END of the real
 * LaunchClassLoader transformer chain — the qualified topology.
 *
 * <p>The offline qualification registers the writers "AFTER the complete FML
 * chain" by explicit contract (see RevQualifyRuntime), so their input is the
 * post-FML, post-Mixin buffer carrying the launch-scoped
 * MixinMerged#sessionId provenance the session-bound identity consumes. A
 * real FML launch registers foreign transformers continuously while
 * launchwrapper processes tweaks — classes load BETWEEN tweak invocations,
 * so a writer can be invoked long before the chain is complete. A one-shot
 * placement is therefore wrong: this guard re-checks the tail on EVERY
 * writer invocation (a linear scan of a small list) and re-places the
 * writers whenever a later foreign registration displaced them.</p>
 *
 * <p>Placement uses index-wise {@code set()} rewrites on the loader's own
 * field-backed list (the published getTransformers() view is unmodifiable):
 * the same list size, so no structural modification, so the loader's active
 * iterator is unaffected. The rotation is stable — foreign transformers keep
 * their relative registration order — so every class experiences the same
 * foreign sequence as the qualified topology, with the writers last.</p>
 *
 * <p>On rotation the guard returns TRUE and the calling writer DEFERS
 * (returns the bytes unchanged): the loader's current pass continues through
 * the remaining foreign transformers and then reaches the writers again at
 * the tail with the fully transformed bytes. Processing at the stale
 * position would both read pre-foreign bytes and be applied a second time on
 * the tail revisit.</p>
 *
 * <p>Generic by construction: no runtime name, no pack name, no identity-mode
 * branch. In a runtime whose transformers leave the writers last this is a
 * measured no-op. The measured order is recorded for the evidence flush —
 * the placement is a fact the receipt states, never one it assumes.</p>
 */
public final class LiveWriterOrdering {

    /** Exactly the transformer classes the tweaker/coremod registers. The
     *  single-copy pair joins the writer group: they register inside the
     *  writer block and the tail-topology check must treat them as ours, or
     *  the guard would rotate the chain mid-load and the Publication writer
     *  would see pre-patch bytes on the arm-target pass. */
    private static final String[] WRITER_CLASSES = {
            "com.rustcraft.coremod.SPacketChunkDataTransformer",
            "com.rustcraft.coremod.LiveChunkOwnershipTransformer",
            "com.rustcraft.coremod.LiveChunkPublicationTransformer",
            "com.rustcraft.coremod.NetworkManagerSingleCopyTransformer",
            "com.rustcraft.coremod.NettyPacketEncoderCounterTransformer",
    };

    /** M1-COMPOSE: RustCraft AUTHORITY transformers (light/registry/region/
     * compression). They are deliberately placed AFTER the writers in the
     * chain: the writers' qualified pre-hook pins bind to the FOREIGN-stage
     * bytes (the discovery probe registers no RustCraft transformers), so
     * the writers must transform before any owned pass injects into the
     * same classes. The authority passes are method-gated (no class-digest
     * admission) and their hook sites are disjoint from the writer sites —
     * where they share a method (W05/WorldLightTransformer on
     * func_180500_c; W18/ChunkMutationTransformer on func_76631_c) both
     * injections are preserved, only their in-method ordering flips. */
    private static final String[] AUTHORITY_CLASSES = {
            "com.rustcraft.coremod.NetworkManagerCompressionTransformer",
            "com.rustcraft.coremod.RegionFileAuthorityTransformer",
            "com.rustcraft.coremod.PhosphorLightTransformer",
            "com.rustcraft.coremod.WorldLightTransformer",
            "com.rustcraft.coremod.CheckLightAuthorityTransformer",
            "com.rustcraft.coremod.RegionFileReadTransformer",
            "com.rustcraft.coremod.ChunkMutationTransformer",
            "com.rustcraft.coremod.ChunkStateAuthorityTransformer",
            // M3-A: without this entry the guard classified the tick
            // authority as foreign and moved it into the foreign prefix
            // (on16 measured chain, position 3 — before deobf)
            "com.rustcraft.coremod.TickAuthorityTransformer",
    };

    private static volatile String measured = "NOT_YET_MEASURED";
    /** The launch target: the first class launchwrapper loads AFTER every
     *  tweak has registered. Classes before it are bootstrap classes the
     *  offline launch defined without our hooks; our writers must do the
     *  same. Resolved from Launch's own field, so no launch shape is
     *  hardcoded. */
    private static volatile String armTarget;
    private static volatile boolean armed;

    private LiveWriterOrdering() { }

    /** The tweaker states its own launch target before any class loads. */
    public static void armOn(String launchTarget) {
        if (launchTarget != null && launchTarget.length() > 0) armTarget = launchTarget;
    }

    /**
     * TRUE when this class must pass through UNCHANGED because it belongs to
     * the bootstrap phase (before the launch target, which is the first
     * class loaded after the transformer chain is complete). The launch
     * target itself ARMS the writers and is processed.
     */
    public static boolean deferClass(String transformedName) {
        if (armed || transformedName == null) return false;
        String target = armTarget;
        if (target == null) {
            // Neither the tweaker nor this launch stated a target. The
            // dedicated-server shapes this framework boots all launch
            // net.minecraft.server.MinecraftServer as the target class
            // (ServerLaunchWrapper and the admission tweaker's delegate
            // alike); arm on it rather than staying inert. A wrong guess
            // would surface as hook misplacement in the writer plan's
            // fingerprint checks, never as silent corruption.
            target = "net.minecraft.server.MinecraftServer";
            armTarget = target;
        }
        if (transformedName.equals(target)) {
            armed = true;
            return false;
        }
        return true;
    }

    /**
     * Ensures the writer instances occupy the tail of the chain. Called at
     * the top of each writer's {@code transform()}. Returns TRUE when this
     * invocation arrived at a stale position and the writer must DEFER;
     * false means the qualified topology holds — process normally.
     */
    public static boolean ensureWritersLast() {
        Object loader;
        try {
            loader = Class.forName("net.minecraft.launchwrapper.Launch")
                    .getField("classLoader").get(null);
        } catch (Throwable notForge) {
            measured = "UNAVAILABLE: no LaunchWrapper loader";
            return false;
        }
        if (loader == null) return false;
        try {
            synchronized (LiveWriterOrdering.class) {
                List<Object> live = transformers(loader);
                if (live == null) {
                    measured = "UNAVAILABLE: transformer list not reachable";
                    return false;
                }
                int liveSize = live.size();
                int ours = 0;
                for (Object transformer : live) if (isWriter(transformer)) ours++;
                if (ours == 0) {
                    measured = "UNAVAILABLE: no writer instances in the chain";
                    return false;
                }
                boolean already = ours == liveSize;
                if (!already) {
                    // M1-COMPOSE tiered topology [foreign][writers]
                    // [authorities]: stable partition, foreign first.
                    already = true;
                    int i = 0;
                    while (i < liveSize
                            && !isWriter(live.get(i)) && !isAuthority(live.get(i))) i++;
                    int wStart = i;
                    while (i < liveSize && isWriter(live.get(i))) i++;
                    int wEnd = i;
                    if (wEnd == wStart) already = false; // writers not contiguous
                    while (i < liveSize && isAuthority(live.get(i))) i++;
                    if (i != liveSize) already = false;   // misplaced entries after
                }
                if (already) return false;
                if (live.size() != liveSize)
                    return true; // foreign registration mid-guard: retry next call
                List<Object> order = new ArrayList<Object>(live);
                List<Object> foreign = new ArrayList<Object>();
                List<Object> writers = new ArrayList<Object>();
                List<Object> authorities = new ArrayList<Object>();
                for (Object t : order) {
                    if (isWriter(t)) writers.add(t);
                    else if (isAuthority(t)) authorities.add(t);
                    else foreign.add(t);
                }
                order = new ArrayList<Object>(foreign.size()
                        + writers.size() + authorities.size());
                order.addAll(foreign);
                order.addAll(writers);
                order.addAll(authorities);
                for (int i = 0; i < order.size(); i++)
                    if (live.get(i) != order.get(i)) live.set(i, order.get(i));
                StringBuilder names = new StringBuilder();
                for (int i = 0; i < live.size(); i++) {
                    if (i > 0) names.append(',');
                    names.append(live.get(i).getClass().getName());
                }
                measured = "MOVED_TO_TIERED " + names;
                return true; // stale position: defer, the tiered visit processes
            }
        } catch (Throwable failure) {
            measured = "UNAVAILABLE: " + failure;
            return false;
        }
    }

    /** The measured placement + resulting chain order for the evidence receipt. */
    public static String measuredOrder() {
        return measured;
    }

    static boolean isWriter(Object transformer) {
        return classifyWriter(transformer.getClass().getName());
    }

    /** name-level classification (package-visible for the offline
     * regression; the Object forms delegate here) */
    static boolean classifyWriter(String className) {
        for (String writer : WRITER_CLASSES) if (writer.equals(className)) return true;
        return false;
    }

    static boolean isAuthority(Object transformer) {
        return classifyAuthority(transformer.getClass().getName());
    }

    static boolean classifyAuthority(String className) {
        for (String authority : AUTHORITY_CLASSES) if (authority.equals(className)) return true;
        return false;
    }

    /**
     * The LIVE, mutable transformer list. The published getTransformers()
     * view is deliberately unmodifiable (set() throws
     * UnsupportedOperationException), so the loader's own field is the only
     * route that can actually place the writers. No live list, no placement.
     */
    @SuppressWarnings("unchecked")
    private static List<Object> transformers(Object loader) {
        try {
            java.lang.reflect.Field field = loader.getClass().getDeclaredField("transformers");
            field.setAccessible(true);
            return (List<Object>) field.get(loader);
        } catch (Throwable noField) {
            return null;
        }
    }
}
