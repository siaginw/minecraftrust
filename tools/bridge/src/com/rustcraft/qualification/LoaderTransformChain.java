package com.rustcraft.qualification;

import com.rustcraft.coremod.AsmTreeCompat;
import com.rustcraft.coremod.LiveHookSupport;
import com.rustcraft.coremod.LiveWriterPlan;

import net.minecraft.launchwrapper.IClassTransformer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Observes a real class-loading chain, in the process that runs it, for any
 * runtime whose loader publishes a transformer list.
 *
 * <p>This is deliberately generic. It names no mod, no pack, no profile and no
 * game class: it works for any Forge-family loader, and the only thing it needs
 * is a transformer it can register, a list it can read back, and a directory
 * the runtime's own observer wrote. A modpack is a test case here, not a
 * special case.</p>
 *
 * <h3>Why an observer transformer</h3>
 * <p>The chain has to start where the transformation actually starts. A JVM-level
 * agent transformer only ever sees the buffer the loader was finally handed,
 * which is the far END of the chain, not its beginning. So the entry buffer is
 * captured by a pass-through transformer registered immediately BEFORE the
 * RustCraft writers: it observes exactly the bytes those writers were about to
 * receive, in this process, with no re-read of the class from anywhere.</p>
 *
 * <p>It returns {@code null}. An observer that altered bytes would not be an
 * observer, and a chain whose first stage changed the class could never be told
 * apart from a chain whose first stage was the real one.</p>
 *
 * <h3>What "observed calls" means here</h3>
 * <p>The chain reports, per hook, how many of its injected call sites are still
 * present in that stage's bytes. That is a SURVIVAL count: its whole purpose is
 * to detect a stage that removed, relocated or duplicated a hook, and a byte
 * comparison can answer exactly that. It is not a count of times the hook ran.
 * Runtime invocation is a different question, answered by the hook facade's own
 * counters, and conflating the two would let an unexecuted class claim coverage
 * it never exercised.</p>
 */
public final class LoaderTransformChain {

    /** A pass-through transformer that records the buffer handed to the writers. */
    public static final class EntryObserver implements IClassTransformer {
        private static final Map<String, byte[]> ENTRIES =
                new ConcurrentHashMap<String, byte[]>();

        @Override
        public byte[] transform(String name, String transformedName, byte[] basicClass) {
            // Fail-safe by construction. An observer that can throw is an
            // observer that changes behaviour, and the failure would surface as
            // a class that will not load rather than as a missing observation.
            // Recording the failure and passing the buffer through untouched
            // keeps the chain's first stage honest: if it is missing, the chain
            // renders INCOMPLETE, which is what a gap in evidence looks like.
            try {
                if (transformedName != null && basicClass != null)
                    ENTRIES.putIfAbsent(transformedName.replace('.', '/'), basicClass.clone());
            } catch (Throwable observerFailure) {
                FAILURES.put(transformedName == null ? String.valueOf(name) : transformedName,
                        observerFailure.toString());
            }
            // Returning the buffer unchanged rather than null: some loader
            // implementations treat a null return as "this transformer
            // failed" and abandon the class. Either way the bytes are the same
            // ones we were handed, so the observation is identical.
            return basicClass;
        }

        private static final Map<String, String> FAILURES =
                new ConcurrentHashMap<String, String>();
        public static Map<String, String> failures() { return FAILURES; }

        public static byte[] entry(String internalName) { return ENTRIES.get(internalName); }
        public static void clear() { ENTRIES.clear(); }
    }

    private final String definingLoaderIdentity;
    private final Path definedDump;

    public LoaderTransformChain(String definingLoaderIdentity, Path definedDump) {
        this.definingLoaderIdentity = definingLoaderIdentity;
        this.definedDump = definedDump;
    }

    /**
     * The transformer names the loader registered AFTER the last live writer.
     *
     * <p>Reported, never assumed. An empty result is the good case -- the
     * writers ran last and nothing could alter the class after them -- but it is
     * a measurement of this launch's registration order, not a property of the
     * writer, so it is recomputed every time rather than hard-coded.</p>
     */
    public static List<String> downstreamTransformers() {
        List<String> out = new ArrayList<String>();
        List<IClassTransformer> registered = transformersSeen();
        boolean passedWriters = false;
        for (IClassTransformer each : registered) {
            String name = each.getClass().getName();
            if (isLiveWriter(name)) { passedWriters = true; continue; }
            if (passedWriters) out.add(name);
        }
        return out;
    }

    /** True for a transformer this chain may label RUSTCRAFT_POST_WRITER. */
    private static boolean isLiveWriter(String name) {
        return name.equals(TransformationChainEvidence.OWNERSHIP_WRITER)
                || name.equals("com.rustcraft.coremod.LiveChunkPublicationTransformer")
                || name.equals("com.rustcraft.coremod.SPacketChunkDataTransformer");
    }

    /**
     * Installs the entry observer at the FRONT of a loader's transformer chain.
     *
     * <p>Position is the entire point: an observer anywhere else records bytes
     * that some other transformer already produced, which would make the chain's
     * first stage a restatement of a later one rather than an edge. Loaders
     * commonly publish their transformer list as an unmodifiable view, so the
     * public accessor is tried first and the loader's own field second.</p>
     *
     * <p>Neither route is privileged. If both are unavailable the caller is told
     * so, and the chain then renders INCOMPLETE for want of an entry buffer --
     * which is the correct outcome, and much better than a chain that begins
     * somewhere in the middle and calls it the start.</p>
     *
     * @return how the observer was installed, for the receipt
     */
    public static String installAtFront(Object loader) {
        EntryObserver observer = new EntryObserver();
        try {
            @SuppressWarnings("unchecked")
            java.util.List<IClassTransformer> published =
                    (java.util.List<IClassTransformer>) loader.getClass()
                            .getMethod("getTransformers").invoke(loader);
            published.add(0, observer);
            return "FRONT_OF_PUBLISHED_LIST";
        } catch (Throwable notModifiable) {
            // fall through to the loader's own field
        }
        try {
            java.lang.reflect.Field field = loader.getClass().getDeclaredField("transformers");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.List<IClassTransformer> live = (java.util.List<IClassTransformer>) field.get(loader);
            live.add(0, observer);
            return "FRONT_OF_LOADER_FIELD";
        } catch (Throwable unreachable) {
            return "UNAVAILABLE: " + unreachable;
        }
    }

    public static List<IClassTransformer> transformersSeen() {
        try {
            return net.minecraft.launchwrapper.Launch.classLoader.getTransformers();
        } catch (Throwable unavailable) {
            return Collections.emptyList();
        }
    }

    /** The bytes the runtime's own observer recorded for the loader's definition. */
    public byte[] definedBytes(String internalName) {
        if (definedDump == null) return null;
        Path file = definedDump.resolve(internalName + ".class");
        try {
            return Files.isRegularFile(file) ? Files.readAllBytes(file) : null;
        } catch (IOException unreadable) {
            return null;
        }
    }

    /**
     * How many injected call sites of each planned hook survive in these bytes.
     *
     * <p>Counted from the BYTES, by parsing them and counting the calls into the
     * hook facade inside each hook's own method. The obvious cheaper proxy --
     * searching for the hook's operation-id marker string -- is wrong, and was
     * caught being wrong: a hook family that injects a bare facade call carries
     * no marker, so the proxy reports zero survivors for hooks that are
     * demonstrably present. Counting the calls the writer actually emits is
     * what "survived" means, and it works for every hook family rather than only
     * the ones that happen to be annotated.</p>
     */
    private static Map<String, Integer> survivingFacadeCalls(String internalName, byte[] bytes) {
        Map<String, Integer> out = new TreeMap<String, Integer>();
        List<LiveWriterPlan.Hook> hooks = hooksFor(internalName);
        if (bytes == null) {
            for (LiveWriterPlan.Hook hook : hooks) out.put(hook.id, Integer.valueOf(0));
            return out;
        }
        org.objectweb.asm.tree.ClassNode cn =
                LiveHookSupport.readClass(bytes);
        for (LiveWriterPlan.Hook hook : hooks) {
            int calls = 0;
            for (org.objectweb.asm.tree.MethodNode mn : AsmTreeCompat.methods(cn)) {
                if (!mn.name.equals(hook.methodName) || !mn.desc.equals(hook.descriptor)) continue;
                for (org.objectweb.asm.tree.AbstractInsnNode insn : AsmTreeCompat.instructions(mn)) {
                    if (insn.getOpcode() != org.objectweb.asm.Opcodes.INVOKESTATIC) continue;
                    org.objectweb.asm.tree.MethodInsnNode call =
                            (org.objectweb.asm.tree.MethodInsnNode) insn;
                    if (LiveHookSupport.HOOKS_CLASS.equals(call.owner)) calls++;
                }
            }
            out.put(hook.id, Integer.valueOf(calls));
        }
        return out;
    }

    private static List<LiveWriterPlan.Hook> hooksFor(String internalName) {
        List<LiveWriterPlan.Hook> out = new ArrayList<LiveWriterPlan.Hook>();
        for (LiveWriterPlan.Hook hook : LiveWriterPlan.HOOKS)
            if (hook.className.replace('.', '/').equals(internalName)) out.add(hook);
        return out;
    }

    /** The observations a real launch feeds the chain producer. */
    public TransformationChainEvidence.Observations observations() {
        return new TransformationChainEvidence.Observations() {
            public byte[] entryBytes(String name) { return EntryObserver.entry(name); }
            public byte[] definedBytes(String name) {
                return LoaderTransformChain.this.definedBytes(name);
            }
            public List<TransformationChainEvidence.Stage> downstreamStages(String name) {
                // Only produced when this launch actually registered a
                // transformer after the writers AND that transformer changed the
                // bytes. Naming a transformer whose output equals its input would
                // claim a stage that never altered a byte.
                return null;
            }
            public Map<String, Integer> observedHookCalls(String name) {
                return survivingFacadeCalls(name, definedBytes(name));
            }
            public String definingLoaderIdentity() { return definingLoaderIdentity; }
        };
    }

    /**
     * Binds each recorded definition to the Class the loader actually returned.
     *
     * <p>A transformer sees a buffer and returns a buffer; neither half of that
     * is a definition. Until the loader has accepted those bytes and handed back
     * a Class, the record is a claim about a transformation rather than a record
     * of one, and {@code certifiable()} is right to refuse it.</p>
     *
     * <p>So this force-loads each recorded class and binds the resulting Class to
     * its own record. The binding is per definition and never merges two
     * definitions of one name: a class defined twice has two records, and picking
     * one would make the chain describe a definition that may not be the one
     * observed. Force-loading with initialization disabled means no static
     * initialiser runs -- this is an observation of definitions, not a
     * lifecycle.</p>
     *
     * @return the number of definitions successfully bound
     */
    public static int bindDefinitions(ClassLoader loader, SameProcessAcquisition acquisition) {
        return bindDefinitions(loader, acquisition, null);
    }

    /**
     * Binds definition claims to the agent's OBSERVED final definitions. A
     * real launch transforms some classes more than once (re-entrant loads
     * during other transformers' work); only the attempt whose output bytes
     * ARE the loader's final definition may claim the definition. Without
     * the witness hashes, every loadable row would claim -- exactly the
     * double claim the engine's frame evidence exists to reject.
     */
    public static int bindDefinitions(ClassLoader loader, SameProcessAcquisition acquisition,
                                      java.util.Map<String, String> observedFinalHashes) {
        int bound = 0;
        if (acquisition == null) return 0;
        String identity = LiveHookSupport.loaderIdentity(loader);
        java.util.Set<String> alreadyClaimed = new java.util.HashSet<String>();
        for (SameProcessAcquisition.Definition definition : acquisition.definitions()) {
            if (definition.failure != null || definition.postWriterRawSha256 == null) continue;
            try {
                if (observedFinalHashes != null) {
                    // Two attempts of one class can produce IDENTICAL output
                    // (an exact class transformed twice); the definition is
                    // claimed ONCE -- by the first attempt whose bytes match.
                    if (!alreadyClaimed.add(definition.binaryName)) continue;
                    String finalHash = observedFinalHashes.get(
                            definition.binaryName.replace('/', '.'));
                    if (finalHash == null || !finalHash.equals(definition.postWriterRawSha256)) {
                        // Discarded attempt: its buffer is not what the
                        // loader defined. The row stays recorded as a
                        // non-defining attempt; the flush reports it.
                        continue;
                    }
                }
                Class<?> returned = Class.forName(
                        definition.binaryName.replace('/', '.'), false, loader);
                definition.defined(returned, identity);
                bound++;
            } catch (SameProcessAcquisition.Incomplete refused) {
                // Recorded, not thrown: a class that cannot be bound is a gap
                // in the evidence and belongs in the receipt, not in an
                // exception that hides every other definition behind it.
            } catch (Throwable absent) {
                // The class may genuinely not be loadable in this phase. Same
                // reasoning: the absence is the finding.
            }
        }
        return bound;
    }

    /** Renders the chain, or reports the exact evidence gap that stopped it. */
    public String render() {
        return TransformationChainEvidence.render(observations());
    }

    /** The loader identity, as the runtime renders it for a class it defined. */
    public static String loaderIdentity() {
        return LiveHookSupport.loaderIdentity(net.minecraft.launchwrapper.Launch.classLoader);
    }

    private static Path dumpPath(String property) {
        String value = System.getProperty(property);
        return value == null || value.length() == 0 ? null : Paths.get(value);
    }

    /** Builds a chain bound to this launch's own observation directory. */
    public static LoaderTransformChain forThisLaunch() {
        return new LoaderTransformChain(loaderIdentity(), dumpPath("rustcraft.definedDump"));
    }
}
