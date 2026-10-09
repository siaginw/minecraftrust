package com.rustcraft.coremod;

import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.util.List;

/**
 * M1.4 CoreMod Class-Transformer for SPacketChunkData.
 * Injects a non-invasive conditional hook into SPacketChunkData.<init>(Chunk, int)
 * while strictly preserving original bytecode, descriptors, and Java fallback logic.
 */
public class SPacketChunkDataTransformer implements IClassTransformer {

    private static final String TARGET_CLASS_DEOBF = "net.minecraft.network.play.server.SPacketChunkData";
    private static final String TARGET_CLASS_OBF = "ji";

    public static volatile int transformCount = 0;
    public static volatile String lastTransformStatus = "NOT_ATTEMPTED";

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        // Bootstrap gate: classes loaded before the launch target belong to
        // the phase the offline launch defined without our hooks.
        if (LiveWriterOrdering.deferClass(transformedName)) return basicClass;
        // The qualified topology places the writers AFTER the complete FML
        // chain (the offline contract); ensure it holds in a real launch too.
        // A rotation defers THIS invocation: the loader's current pass
        // reaches the writers again at the tail, with the fully transformed
        // bytes -- processing here would both read pre-foreign bytes and be
        // applied a second time on the tail revisit.
        if (LiveWriterOrdering.ensureWritersLast()) return basicClass;
        if (!TARGET_CLASS_DEOBF.equals(transformedName) && !TARGET_CLASS_OBF.equals(name)) {
            return basicClass;
        }

        if (basicClass == null) {
            lastTransformStatus = "NULL_BYTECODE";
            return null;
        }

        String binaryName = TARGET_CLASS_DEOBF.equals(transformedName) ? transformedName : name;
        com.rustcraft.qualification.SameProcessAcquisition.Definition attempt =
                LiveHookSupport.openAcquisition("PACKET", binaryName,
                        basicClass, LiveHookSupport.definingLoader(getClass().getClassLoader()));
        try {
            // The same PRE-hook gate every other live writer runs. It was absent
            // here, and the absence was invisible: the class was still
            // instrumented, so the only symptom was that a session-bound class
            // transformed by THIS writer could never produce an admission
            // certificate, and its chain row had nothing to stand on. Identity
            // admission is a property of the writer, not of which hook family
            // it injects, so it belongs in all three or none.
            LiveWriterPlan.Hook[] hooks =
                    LiveHookSupport.hooksFor("PACKET", binaryName);
            if (hooks.length > 0) {
                LiveHookSupport.verifyPreHookIdentity(hooks, basicClass,
                        LiveHookSupport.definingLoader(getClass().getClassLoader()));
            }
            ClassReader cr = new ClassReader(basicClass);
            ClassNode cn = new ClassNode();
            cr.accept(cn, 0);

            boolean transformed = false;
            for (MethodNode mn : (List<MethodNode>) cn.methods) {
                // Match constructor (Chunk, int) -> (Lnet/minecraft/world/chunk/Chunk;I)V
                if ("<init>".equals(mn.name) && mn.desc.contains("Lnet/minecraft/world/chunk/Chunk;I")) {
                    transformed = transformConstructor(cn, mn);
                } else if (("func_148840_b".equals(mn.name) || "writePacketData".equals(mn.name))
                        && mn.desc.contains("PacketBuffer")) {
                    transformWritePacketData(cn, mn);
                }
            }

            if (transformed) {
                ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
                cn.accept(cw);
                transformCount++;
                lastTransformStatus = "TRANSFORMED_SUCCESS";
                byte[] result = cw.toByteArray();
                LiveHookSupport.completeAcquisition(attempt, result,
                        com.rustcraft.qualification.SameProcessAcquisition.HookPlacement.PLACED);
                return result;
            } else {
                if (!"UNKNOWN_LAYOUT_FALLBACK".equals(lastTransformStatus)) {
                    lastTransformStatus = "CONSTRUCTOR_NOT_FOUND";
                }
                LiveHookSupport.completeAcquisition(attempt, null,
                        com.rustcraft.qualification.SameProcessAcquisition.HookPlacement.REFUSED);
                return basicClass;
            }
        } catch (Throwable t) {
            System.err.println("[RustCraft] Failed to transform SPacketChunkData: " + t.getMessage());
            lastTransformStatus = "TRANSFORM_ERROR: " + t.getMessage();
            LiveHookSupport.recordNonAdmission(transformedName, String.valueOf(t.getMessage()));
            // Pass through unhooked; see the ownership/publication writers.
            return basicClass; // Safe fallback: return unmodified bytecode
        }
    }

    private boolean transformConstructor(ClassNode cn, MethodNode mn) {
        // Fingerprint check: ensure standard instructions exist
        boolean hasCalculateSize = false;
        for (AbstractInsnNode insn : mn.instructions.toArray()) {
            if (insn instanceof MethodInsnNode) {
                MethodInsnNode minsn = (MethodInsnNode) insn;
                if (minsn.name.equals("func_189556_a") || minsn.name.equals("calculateChunkSize")) {
                    hasCalculateSize = true;
                    break;
                }
            }
        }

        if (!hasCalculateSize) {
            System.err.println("[RustCraft] Warning: SPacketChunkData constructor has unexpected bytecode layout. Using UNKNOWN_LAYOUT_FALLBACK.");
            lastTransformStatus = "UNKNOWN_LAYOUT_FALLBACK";
            return false;
        }

        // Issue #1 live-writer PACKET_CAPTURE diagnostic branch (S02). Gated on the
        // same default-OFF option as the ownership/publication transformers; it is
        // strictly OBSERVATIONAL: no admission, no SnapshotCapture call, no native
        // output, no skipping of the vanilla body, no alteration of transmitted bytes.
        if (LiveChunkOwnershipTransformer.enabled()) {
            injectLiveCaptureObservation(cn, mn);
        }

        // Inject hook at method entry right after super() call:
        // boolean handled = NativeChunkPacket.populatePacket(this, chunkIn, changedSectionFilter);
        // if (handled) return;
        // In the live-writer diagnostic profile the legacy M1 native-population hook
        // is replaced by the live observation branch below (the production M1 path
        // is unchanged: property OFF leaves this injection exactly as before).
        final boolean liveDiagnostic = LiveChunkOwnershipTransformer.enabled();

        InsnList hook = new InsnList();

        LabelNode continueOriginal = new LabelNode();

        // Load this, chunkIn (var 1), changedSectionFilter (var 2)
        // Descriptor uses Object/Object so it is identical in notch/SRG/MCP runtimes
        // and matches NativeChunkPacket.populatePacket(Object, Object, int).
        if (!liveDiagnostic) {
            hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
            hook.add(new VarInsnNode(Opcodes.ALOAD, 1));
            hook.add(new VarInsnNode(Opcodes.ILOAD, 2));
            hook.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "com/rustcraft/bridge/NativeChunkPacket",
                    "populatePacket",
                    "(Ljava/lang/Object;Ljava/lang/Object;I)Z",
                    false
            ));
            hook.add(new JumpInsnNode(Opcodes.IFEQ, continueOriginal));
            hook.add(new InsnNode(Opcodes.RETURN));
        }
        hook.add(continueOriginal);

        // Find insertion point after super() call (INVOKESPECIAL java/lang/Object.<init>)
        AbstractInsnNode targetNode = null;
        for (AbstractInsnNode insn : mn.instructions.toArray()) {
            if (insn.getOpcode() == Opcodes.INVOKESPECIAL) {
                MethodInsnNode minsn = (MethodInsnNode) insn;
                if ("java/lang/Object".equals(minsn.owner) && "<init>".equals(minsn.name)) {
                    targetNode = insn;
                    break;
                }
            }
        }

        if (targetNode != null) {
            mn.instructions.insert(targetNode, hook);
        } else {
            mn.instructions.insert(hook);
        }

        // M4.2B: live packet comparator — at EVERY constructor return, hand the
        // fully built Java packet to M4PacketCompare (no-op unless
        // -Dminecraftrust.m4.packet_compare=SHADOW; Java body ran untouched).
        if ("SHADOW".equalsIgnoreCase(System.getProperty("minecraftrust.m4.packet_compare", "OFF"))) {
            for (AbstractInsnNode insn : mn.instructions.toArray()) {
                if (insn.getOpcode() == Opcodes.RETURN) {
                    InsnList cmp = new InsnList();
                    cmp.add(new VarInsnNode(Opcodes.ALOAD, 0)); // packet
                    cmp.add(new VarInsnNode(Opcodes.ALOAD, 1)); // chunkIn
                    cmp.add(new VarInsnNode(Opcodes.ILOAD, 2)); // changedSectionFilter
                    cmp.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                            "com/rustcraft/bridge/M4PacketCompare", "onPacketBuilt",
                            "(Ljava/lang/Object;Ljava/lang/Object;I)V", false));
                    mn.instructions.insertBefore(insn, cmp);
                }
            }
        }
        return true;
    }

    /**
     * Issue #1 S02 observation branch: entry after Object.&lt;init&gt; (before the
     * first source read), commit before the normal return, catch-all abort that
     * rethrows the original Throwable. Inert plumbing only: the facade records
     * binding-state observations and counters; live admission does not exist in
     * this stage.
     */
    private void injectLiveCaptureObservation(ClassNode cn, MethodNode mn) {
        // Pre-verification against the qualified profile: the constructor's anchor
        // instructions must match the committed fingerprints, in order.
        LiveWriterPlan.Hook hook = null;
        for (LiveWriterPlan.Hook candidate : LiveWriterPlan.HOOKS) {
            if ("PACKET".equals(candidate.transformer) && "S02".equals(candidate.id)) {
                hook = candidate;
                break;
            }
        }
        if (hook == null) {
            throw new LiveHookSupport.ProfileFailure("S02 missing from the live writer plan");
        }
        List<AbstractInsnNode> anchors = LiveHookSupport.verifyAnchorsInOrder(mn, hook);

        int tokenLocal = mn.maxLocals;
        mn.maxLocals += 1;
        int exLocal = mn.maxLocals;
        mn.maxLocals += 1;

        // Token pre-init BEFORE the try range (never throws; keeps the handler's
        // merged token local a reference instead of TOP).
        InsnList preInit = new InsnList();
        preInit.add(new InsnNode(Opcodes.ACONST_NULL));
        preInit.add(new VarInsnNode(Opcodes.ASTORE, tokenLocal));
        mn.instructions.insertBefore(mn.instructions.getFirst(), preInit);

        // Entry (after the super call): observe(this, chunk, filter).
        InsnList observe = new InsnList();
        observe.add(new VarInsnNode(Opcodes.ALOAD, 0));
        observe.add(new VarInsnNode(Opcodes.ALOAD, 1));
        observe.add(new VarInsnNode(Opcodes.ILOAD, 2));
        observe.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                "com/rustcraft/bridge/capture/LiveWriterHooks", "packetCaptureObserve",
                "(Ljava/lang/Object;Ljava/lang/Object;I)Ljava/lang/Object;", false));
        observe.add(new VarInsnNode(Opcodes.ASTORE, tokenLocal));

        // Authority experiment branch: if enabled and successful, return immediately
        LabelNode continueJava = new LabelNode();
        observe.add(new VarInsnNode(Opcodes.ALOAD, tokenLocal));
        observe.add(new VarInsnNode(Opcodes.ALOAD, 0));
        observe.add(new VarInsnNode(Opcodes.ALOAD, 1));
        observe.add(new VarInsnNode(Opcodes.ILOAD, 2));
        observe.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                "com/rustcraft/bridge/capture/PacketAuthorityExperiment", "tryAuthority",
                "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;I)Z", false));
        observe.add(new JumpInsnNode(Opcodes.IFEQ, continueJava));
        observe.add(new InsnNode(Opcodes.RETURN));
        observe.add(continueJava);

        mn.instructions.insert(anchors.get(0) /* Object.<init> at BCI 1 */, observe);

        // Commit before the normal return (BCI 200).
        for (AbstractInsnNode insn : mn.instructions.toArray()) {
            if (insn.getOpcode() == Opcodes.RETURN) {
                InsnList commit = new InsnList();
                commit.add(new VarInsnNode(Opcodes.ALOAD, 0));
                commit.add(new VarInsnNode(Opcodes.ALOAD, 1));
                commit.add(new VarInsnNode(Opcodes.ILOAD, 2));
                commit.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                        "com/rustcraft/bridge/capture/LiveWriterHooks", "packetCaptureCommit",
                        "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;I)V", false));
                // token first: (token, packet, chunk, filter)
                InsnList full = new InsnList();
                full.add(new VarInsnNode(Opcodes.ALOAD, tokenLocal));
                full.add(commit);
                mn.instructions.insertBefore(insn, full);
            }
        }

        // Catch-all abort + rethrow (the packet constructor must fail exactly as before).
        org.objectweb.asm.tree.LabelNode tryStart = new org.objectweb.asm.tree.LabelNode();
        mn.instructions.insert(anchors.get(0), tryStart); // after Object.<init>, before everything else
        org.objectweb.asm.tree.LabelNode handler = new org.objectweb.asm.tree.LabelNode();
        InsnList handlerCode = new InsnList();
        handlerCode.add(new VarInsnNode(Opcodes.ASTORE, exLocal));
        handlerCode.add(new VarInsnNode(Opcodes.ALOAD, tokenLocal));
        handlerCode.add(new VarInsnNode(Opcodes.ALOAD, exLocal));
        handlerCode.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                "com/rustcraft/bridge/capture/LiveWriterHooks", "packetCaptureAbort",
                "(Ljava/lang/Object;Ljava/lang/Throwable;)V", false));
        handlerCode.add(new VarInsnNode(Opcodes.ALOAD, exLocal));
        handlerCode.add(new InsnNode(Opcodes.ATHROW));
        mn.instructions.add(handler);
        mn.instructions.add(handlerCode);
        mn.tryCatchBlocks.add(new org.objectweb.asm.tree.TryCatchBlockNode(
                tryStart, handler, handler, null));
        lastTransformStatus = "LIVE_OBSERVATION_INSTALLED";
    }

    /**
     * Injects direct Netty packet-buffer emission hook at the entry of SPacketChunkData.writePacketData.
     *
     * Injected bytecode:
     *   ALOAD 0  (this)
     *   ALOAD 1  (packetBuffer)
     *   INVOKESTATIC com/rustcraft/bridge/capture/PacketAuthorityExperiment.tryWritePacketDataDirect(Ljava/lang/Object;Ljava/lang/Object;)Z
     *   IFEQ continueOriginal
     *   RETURN
     *   continueOriginal:
     *   [original instructions run unchanged for Java fallback]
     */
    private boolean transformWritePacketData(ClassNode cn, MethodNode mn) {
        InsnList hook = new InsnList();
        LabelNode continueOriginal = new LabelNode();

        hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
        hook.add(new VarInsnNode(Opcodes.ALOAD, 1));
        hook.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "com/rustcraft/bridge/capture/PacketAuthorityExperiment",
                "tryWritePacketDataDirect",
                "(Ljava/lang/Object;Ljava/lang/Object;)Z",
                false
        ));
        hook.add(new JumpInsnNode(Opcodes.IFEQ, continueOriginal));
        hook.add(new InsnNode(Opcodes.RETURN));
        hook.add(continueOriginal);

        mn.instructions.insert(hook);
        return true;
    }
}
