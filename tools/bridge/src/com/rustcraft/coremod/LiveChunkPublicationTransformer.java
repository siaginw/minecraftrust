package com.rustcraft.coremod;

import com.rustcraft.qualification.SameProcessAcquisition;
import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.List;

/**
 * Live-writer PUBLICATION transformer (Issue #1 writer protocol; diagnostic only).
 *
 * <p>Instruments the qualified owner-publication, private-I/O and lifecycle
 * sites from {@link LiveWriterPlan}. For the provider operations (W56/W57) two
 * brackets nest: the publication scope (inner) is injected first and the writer
 * bracket (outer) second, so on normal returns the inner scope ends before the
 * writer scope, and the exception-table order routes a body failure through the
 * inner end before the outer end — READY can only follow a successful outermost
 * completion, and no Throwable path can leak either scope.</p>
 *
 * <p>DEFAULT OFF via -Drustcraft.liveWriterDiagnostic; every source class is
 * hash-verified against the qualified pre-hook profile with loud fail-closed
 * refusal otherwise. No JNI, no blocking waits, no native packets.</p>
 */
public class LiveChunkPublicationTransformer implements IClassTransformer {

    public static volatile int transformCount = 0;
    public static volatile String lastStatus = "NOT_ATTEMPTED";

    public static boolean enabled() {
        return LiveChunkOwnershipTransformer.enabled();
    }

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        // The qualified topology places the writers AFTER the complete FML
        // chain (the offline contract); ensure it holds in a real launch too.
        LiveWriterOrdering.ensureWritersLast();
        if (basicClass == null || !enabled()) return basicClass;
        LiveWriterPlan.Hook[] hooks = LiveHookSupport.hooksFor("PUBLICATION", transformedName);
        if (hooks.length == 0) return basicClass;
        SameProcessAcquisition.Definition attempt =
                LiveHookSupport.openAcquisition("PUBLICATION", transformedName, basicClass,
                        LiveHookSupport.definingLoader(getClass().getClassLoader()));
        try {
            LiveHookSupport.verifyPreHookIdentity(hooks, basicClass, LiveHookSupport.definingLoader(getClass().getClassLoader()));
            ClassNode cn = LiveHookSupport.readClass(basicClass);
            LiveHookSupport.refuseMarkerString(cn);
            for (LiveWriterPlan.Hook hook : hooks) {
                instrument(hook, cn, LiveHookSupport.findMethod(cn, hook));
            }
            byte[] result = LiveHookSupport.writeClass(cn);
            transformCount++;
            lastStatus = "HOOKS_INSTALLED_" + hooks.length;
            LiveHookSupport.completeAcquisition(attempt, result,
                    SameProcessAcquisition.HookPlacement.PLACED);
            return result;
        } catch (LiveHookSupport.ProfileFailure failure) {
            lastStatus = "PROFILE_FAILURE: " + failure.getMessage();
            LiveHookSupport.completeAcquisition(attempt, null,
                    SameProcessAcquisition.HookPlacement.REFUSED);
            throw failure; // fail closed: the runtime is not transformed
        } catch (Throwable failure) {
            lastStatus = "TRANSFORM_ERROR[" + transformedName + "]: " + failure;
            throw (LiveHookSupport.ProfileFailure) new LiveHookSupport.ProfileFailure(
                    "unexpected transform error for " + transformedName + ": " + failure).initCause(failure);
        }
    }

    private void instrument(LiveWriterPlan.Hook hook, ClassNode cn, MethodNode mn) {
        String ownerInternal = hook.className.replace('.', '/');
        switch (hook.id) {
            case "W56":
            case "W57": {
                // Inner: owner publication scope (world from the provider field). Injected
                // FIRST so its exception handler precedes the writer handler; the writer
                // bracket (outer) is then inserted at the list head and runs first on entry.
                LiveHookSupport.injectScopeBracket(mn, "publicationScopeBegin",
                        "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;",
                        scopeBeginArgs(ownerInternal, hook.operationId + ".publication"),
                        "publicationScopeEnd");
                // Outer: the whole-operation writer bracket.
                LiveHookSupport.verifyAnchorsInOrder(mn, hook);
                LiveHookSupport.injectWriterBracket(mn, hook.operationId);
                break;
            }
            case "W58": {
                LiveHookSupport.injectWriterBracket(mn, hook.operationId);
                List<AbstractInsnNode> anchors = LiveHookSupport.verifyAnchorsInOrder(mn, hook);
                AbstractInsnNode onUnloadCall = anchors.get(0); // Chunk.func_76623_d (BCI 149)
                LiveHookSupport.requireNoLabelBefore(mn, onUnloadCall, hook.id + " retire");
                AbstractInsnNode receiverLoad = immediateReceiverLoad(mn, onUnloadCall, hook.id);
                InsnList retire = new InsnList();
                retire.add(LiveHookSupport.loadThisField(ownerInternal, "field_73251_h",
                        "Lnet/minecraft/world/WorldServer;"));
                retire.add(new VarInsnNode(Opcodes.ALOAD,
                        ((VarInsnNode) receiverLoad).var));
                retire.add(new org.objectweb.asm.tree.MethodInsnNode(Opcodes.INVOKESTATIC,
                        LiveHookSupport.HOOKS_CLASS, "retireBeforeUnload",
                        "(Ljava/lang/Object;Ljava/lang/Object;)V", false));
                mn.instructions.insertBefore(onUnloadCall, retire);
                break;
            }
            case "W59": {
                // Ticket creation at entry (before any loading work); release tickets
                // published BEFORE the verified success/failure `ran` putfields.
                List<AbstractInsnNode> anchors = LiveHookSupport.verifyAnchorsInOrder(mn, hook);
                AbstractInsnNode successRan = anchors.get(2);
                AbstractInsnNode failureRan = anchors.get(3);
                LiveHookSupport.requireNoLabelBefore(mn, successRan, hook.id + " success release");
                LiveHookSupport.requireNoLabelBefore(mn, failureRan, hook.id + " failure release");
                mn.instructions.insertBefore(mn.instructions.getFirst(), LiveHookSupport.call("ioTaskBegin",
                        "(Ljava/lang/Object;)V", LiveHookSupport.loadLocal(0)));
                mn.instructions.insertBefore(successRan, LiveHookSupport.call("ioReleaseSuccess",
                        "(Ljava/lang/Object;)V", LiveHookSupport.loadLocal(0)));
                mn.instructions.insertBefore(failureRan, LiveHookSupport.call("ioReleaseFailure",
                        "(Ljava/lang/Object;)V", LiveHookSupport.loadLocal(0)));
                break;
            }
            case "W60": {
                // Acquire-before-first-read publication scope around the whole body.
                LiveHookSupport.verifyAnchorsInOrder(mn, hook);
                InsnList beginArgs = new InsnList();
                beginArgs.add(new VarInsnNode(Opcodes.ALOAD, 0));
                LiveHookSupport.injectScopeBracket(mn, "ioPublicationBegin",
                        "(Ljava/lang/Object;)Ljava/lang/Object;", beginArgs, "ioPublicationEnd");
                break;
            }
            default:
                instrumentSupplemental(hook, mn);
            }
    }

    private InsnList scopeBeginArgs(String ownerInternal, String scopeOpId) {
        InsnList args = LiveHookSupport.loadThisField(ownerInternal, "field_73251_h",
                "Lnet/minecraft/world/WorldServer;");
        args.add(LiveHookSupport.loadString(scopeOpId));
        return args;
    }

    private void instrumentSupplemental(LiveWriterPlan.Hook hook, MethodNode mn) {
        switch (hook.id) {
            case "S01": {
                // Diagnostic session bootstrap/shutdown around the whole server run body.
                mn.instructions.insert(LiveHookSupport.call("diagnosticSessionStart", "()V",
                        new InsnList()));
                int exLocal = mn.maxLocals;
                mn.maxLocals += 1;
                org.objectweb.asm.tree.LabelNode tryStart = new org.objectweb.asm.tree.LabelNode();
                mn.instructions.insertBefore(mn.instructions.getFirst(), tryStart); // before everything
                for (AbstractInsnNode insn : mn.instructions.toArray()) {
                    if (isReturn(insn)) {
                        InsnList end = new InsnList();
                        end.add(new org.objectweb.asm.tree.InsnNode(Opcodes.ACONST_NULL));
                        end.add(new org.objectweb.asm.tree.MethodInsnNode(Opcodes.INVOKESTATIC,
                                LiveHookSupport.HOOKS_CLASS, "diagnosticSessionEnd",
                                "(Ljava/lang/Throwable;)V", false));
                        mn.instructions.insertBefore(insn, end);
                    }
                }
                org.objectweb.asm.tree.LabelNode handler = new org.objectweb.asm.tree.LabelNode();
                InsnList handlerCode = new InsnList();
                handlerCode.add(new VarInsnNode(Opcodes.ASTORE, exLocal));
                handlerCode.add(new VarInsnNode(Opcodes.ALOAD, exLocal));
                handlerCode.add(new org.objectweb.asm.tree.MethodInsnNode(Opcodes.INVOKESTATIC,
                        LiveHookSupport.HOOKS_CLASS, "diagnosticSessionEnd",
                        "(Ljava/lang/Throwable;)V", false));
                handlerCode.add(new VarInsnNode(Opcodes.ALOAD, exLocal));
                handlerCode.add(new org.objectweb.asm.tree.InsnNode(Opcodes.ATHROW));
                mn.instructions.add(handler);
                mn.instructions.add(handlerCode);
                mn.tryCatchBlocks.add(new org.objectweb.asm.tree.TryCatchBlockNode(
                        tryStart, handler, handler, null));
                break;
            }
            case "S03": {
                // Static (World,II): private-load scope marker + pending/disk provenance.
                List<AbstractInsnNode> anchors = LiveHookSupport.verifyAnchorsInOrder(mn, hook);
                // loadChunk__Async(World, int, int) is an INSTANCE method: this=0,
                // world=1, x=2, z=3.
                // One-shot observations, called through their qualified safe
                // wrappers. The callsite is the same single INVOKESTATIC at the
                // same anchor with the same arguments; only the callee differs,
                // and the callee is where containment lives. Nothing is added to
                // this method's exception table, so its control flow and its
                // operand stack are exactly what they were.
                InsnList scopeArgs = new InsnList();
                scopeArgs.add(new VarInsnNode(Opcodes.ALOAD, 1));
                scopeArgs.add(new VarInsnNode(Opcodes.ILOAD, 2));
                scopeArgs.add(new VarInsnNode(Opcodes.ILOAD, 3));
                mn.instructions.insertBefore(mn.instructions.getFirst(), LiveHookSupport.call("safeIoPrivateLoadScope",
                        "(Ljava/lang/Object;II)V", scopeArgs));
                InsnList pending = LiveHookSupport.dup();
                pending.add(new org.objectweb.asm.tree.MethodInsnNode(Opcodes.INVOKESTATIC,
                        LiveHookSupport.HOOKS_CLASS, "safeIoPendingNbt",
                        "(Ljava/lang/Object;)V", false));
                mn.instructions.insert(anchors.get(0), pending); // after pending Map.get (BCI 17)
                InsnList disk = LiveHookSupport.dup();
                disk.add(new org.objectweb.asm.tree.MethodInsnNode(Opcodes.INVOKESTATIC,
                        LiveHookSupport.HOOKS_CLASS, "safeIoDiskRoot",
                        "(Ljava/lang/Object;)V", false));
                mn.instructions.insert(anchors.get(2), disk);    // after compressed root read (BCI 59)
                break;
            }
            case "S04":
            case "S05": {
                // Local 0: the loader instance (instance methods) or the World (static).
                mn.instructions.insert(LiveHookSupport.call("safeIoPrivateConstructionSite",
                        "(Ljava/lang/Object;)V", LiveHookSupport.loadLocal(0)));
                break;
            }
            case "S06": {
                mn.instructions.insert(LiveHookSupport.call("safeGeneratorScopeBegin",
                        "(Ljava/lang/Object;)V", LiveHookSupport.loadLocal(0)));
                break;
            }
            default:
                throw new LiveHookSupport.ProfileFailure("unsupported publication hook " + hook.id);
        }
    }

    /** The retiree is the receiver local of the anchored func_76623_d call. */
    private AbstractInsnNode immediateReceiverLoad(MethodNode mn, AbstractInsnNode call, String hookId) {
        AbstractInsnNode previous = call.getPrevious();
        while (previous != null && (previous instanceof org.objectweb.asm.tree.FrameNode
                || previous instanceof org.objectweb.asm.tree.LineNumberNode
                || previous instanceof org.objectweb.asm.tree.LabelNode)) {
            previous = previous.getPrevious();
        }
        if (previous instanceof VarInsnNode && previous.getOpcode() == Opcodes.ALOAD) {
            return previous;
        }
        throw new LiveHookSupport.ProfileFailure(
                "cannot resolve the retiree receiver local for " + hookId);
    }

    private boolean isReturn(AbstractInsnNode insn) {
        int op = insn.getOpcode();
        return op == Opcodes.RETURN || op == Opcodes.ARETURN || op == Opcodes.IRETURN
                || op == Opcodes.LRETURN || op == Opcodes.DRETURN || op == Opcodes.FRETURN;
    }
}
