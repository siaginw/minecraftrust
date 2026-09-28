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
 * real FML launch registers coremod transformers in wrapper discovery order,
 * which measured BEFORE Phosphor's MixinTweaker on the first live attempt —
 * the writers then saw pre-Mixin bytes and failed closed.</p>
 *
 * <p>This class reorders ONLY instances of the three qualified writer classes
 * (exact class-name match — the same names the coremod registers), and does
 * it with index-wise {@code set()} rewrites: the same list size, so no
 * structural modification, so the loader's own for-each iteration (which is
 * invoking a writer right now) is unaffected. All class loading during
 * startup is confined to the main thread; launchwrapper finishes every
 * tweak's registration before the first game class loads, so by the time any
 * TARGET class reaches the writers the placement has long since settled.</p>
 *
 * <p>Generic by construction: no runtime name, no pack name, no identity-mode
 * branch. In a runtime whose transformers already leave the writers last
 * (Clean Forge) this is a measured no-op. The measured before/after order is
 * recorded for the evidence flush — the placement is a fact the receipt
 * states, never one it assumes.</p>
 */
public final class LiveWriterOrdering {

    /** Exactly the transformer classes LiveShadowCoreMod registers. */
    private static final String[] WRITER_CLASSES = {
            "com.rustcraft.coremod.SPacketChunkDataTransformer",
            "com.rustcraft.coremod.LiveChunkOwnershipTransformer",
            "com.rustcraft.coremod.LiveChunkPublicationTransformer",
    };

    private static volatile boolean settled;
    private static volatile String measured;

    private LiveWriterOrdering() { }

    /**
     * Ensures the writer instances occupy the tail of the chain. Called at
     * the top of each writer's {@code transform()} (idempotent, cheap once
     * settled). The loader is the defining LaunchClassLoader, resolved the
     * same way LiveHookSupport resolves it.
     */
    public static void ensureWritersLast() {
        Object loader = null;
        try {
            loader = Class.forName("net.minecraft.launchwrapper.Launch")
                    .getField("classLoader").get(null);
        } catch (Throwable notForge) {
            settled = true;
            measured = "UNAVAILABLE: no LaunchWrapper loader";
            return;
        }
        ensureWritersLast(loader);
    }

    private static void ensureWritersLast(Object loader) {
        if (settled || loader == null) return;
        try {
            List<Object> live = transformers(loader);
            if (live == null) {
                measured = "UNAVAILABLE: transformer list not reachable";
                settled = true;
                return;
            }
            synchronized (LiveWriterOrdering.class) {
                if (settled) return;
                int liveSize = live.size();
                List<Object> order = new ArrayList<Object>(live);
                List<Object> ours = new ArrayList<Object>();
                for (int i = order.size() - 1; i >= 0; i--)
                    if (isWriter(order.get(i))) ours.add(0, order.remove(i));
                if (ours.isEmpty()) {
                    measured = "UNAVAILABLE: no writer instances in the chain";
                    settled = true;
                    return;
                }
                if (live.size() != liveSize) {
                    // A foreign transformer registered while we composed the
                    // new order. Not settled: the next writer invocation
                    // retries against the fuller list.
                    return;
                }
                boolean already = liveSize == ours.size();
                if (!already) {
                    int tail = liveSize - ours.size();
                    for (int i = 0; i < ours.size(); i++)
                        if (live.get(tail + i) != ours.get(i)) already = false;
                }
                if (already) {
                    // The qualified topology is already in place.
                } else {
                    order.addAll(ours);
                    for (int i = 0; i < order.size(); i++)
                        if (live.get(i) != order.get(i)) live.set(i, order.get(i));
                }
                StringBuilder names = new StringBuilder();
                for (int i = 0; i < live.size(); i++) {
                    if (i > 0) names.append(',');
                    names.append(live.get(i).getClass().getName());
                }
                measured = (already ? "ALREADY_LAST " : "MOVED_TO_LAST ") + names;
                settled = true;
            }
        } catch (Throwable failure) {
            measured = "UNAVAILABLE: " + failure;
            settled = true;
        }
    }

    /** The measured placement + resulting chain order for the evidence receipt. */
    public static String measuredOrder() {
        return measured;
    }

    static boolean isWriter(Object transformer) {
        String actual = transformer.getClass().getName();
        for (String writer : WRITER_CLASSES) if (writer.equals(actual)) return true;
        return false;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> transformers(Object loader) {
        try {
            return (List<Object>) loader.getClass().getMethod("getTransformers").invoke(loader);
        } catch (Throwable notPublished) {
            // fall through to the loader's own field
        }
        try {
            java.lang.reflect.Field field = loader.getClass().getDeclaredField("transformers");
            field.setAccessible(true);
            return (List<Object>) field.get(loader);
        } catch (Throwable unreachable) {
            return null;
        }
    }
}
