package com.rustcraft.coremod;

import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodNode;

/**
 * Live-writer OWNERSHIP transformer (Issue #1 writer protocol; diagnostic only).
 *
 * <p>Instruments the qualified chunk-ownership hook sites — the 48 whole-operation
 * writer guards (World/Chunk/ExtendedBlockStorage/BlockStateContainer/NibbleArray/
 * BitArray mutation methods, WRITE_BEGIN bracketed by WRITE_END in a catch-all
 * finally) and the 7 constructor identity registrations (REGISTER_NEW_IDENTITY
 * after super, before escape) listed in {@link LiveWriterPlan}.</p>
 *
 * <p>DEFAULT OFF: while -Drustcraft.liveWriterDiagnostic is not true, transform()
 * returns the input bytes unchanged and no protocol participation exists. When
 * enabled, every source class is hash-verified against the qualified pre-hook
 * profile before instrumentation; any mismatch throws a ProfileFailure that
 * aborts the class load — the runtime is never partially instrumented.</p>
 *
 * <p>No JNI per mutation, no blocking waits, no native packets: the injected
 * calls delegate to the pure-Java {@link LiveWriterHooks} facade.</p>
 */
public class LiveChunkOwnershipTransformer implements IClassTransformer {

    public static final String PROPERTY = "rustcraft.liveWriterDiagnostic";
    public static volatile int transformCount = 0;
    public static volatile String lastStatus = "NOT_ATTEMPTED";

    public static boolean enabled() {
        return Boolean.getBoolean(PROPERTY);
    }

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (basicClass == null || !enabled()) return basicClass;
        LiveWriterPlan.Hook[] hooks = LiveHookSupport.hooksFor("OWNERSHIP", transformedName);
        if (hooks.length == 0) return basicClass;
        try {
            LiveHookSupport.verifyPreHookIdentity(hooks, basicClass);
            ClassNode cn = LiveHookSupport.readClass(basicClass);
            LiveHookSupport.refuseMarkerString(cn);
            for (LiveWriterPlan.Hook hook : hooks) {
                MethodNode mn = LiveHookSupport.findMethod(cn, hook);
                if ("WRITE_BEGIN".equals(hook.hookType)) {
                    LiveHookSupport.verifyAnchorsInOrder(mn, hook); // anchor integrity
                    LiveHookSupport.injectWriterBracket(mn, hook.operationId);
                } else if ("PRIVATE_BUILD_BEGIN".equals(hook.hookType)) {
                    injectConstructorRegistration(mn, hook);
                } else {
                    throw new LiveHookSupport.ProfileFailure(
                            "unsupported ownership hook type " + hook.hookType + " for " + hook.id);
                }
            }
            byte[] result = LiveHookSupport.writeClass(cn);
            transformCount++;
            lastStatus = "HOOKS_INSTALLED_" + hooks.length;
            return result;
        } catch (LiveHookSupport.ProfileFailure failure) {
            lastStatus = "PROFILE_FAILURE: " + failure.getMessage();
            throw failure; // fail closed: the runtime is not transformed
        } catch (Throwable failure) {
            lastStatus = "TRANSFORM_ERROR[" + transformedName + "]: " + failure;
            throw (LiveHookSupport.ProfileFailure) new LiveHookSupport.ProfileFailure(
                    "unexpected transform error for " + transformedName + ": " + failure).initCause(failure);
        }
    }

    private void injectConstructorRegistration(MethodNode mn, LiveWriterPlan.Hook hook) {
        // The component is always `this`; the backing arg differs per constructor kind.
        InsnList args = new InsnList();
        args.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 0));
        InsnList registration;
        if ("net.minecraft.world.chunk.Chunk".equals(hook.className)) {
            // Chunk.<init>(World, ...) — arg 1 is the owning World.
            args.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 1));
            registration = LiveHookSupport.call("ownerChunkConstructed",
                    "(Ljava/lang/Object;Ljava/lang/Object;)V", args);
        } else if ("net.minecraft.world.chunk.NibbleArray".equals(hook.className)
                && "([B)V".equals(hook.descriptor)) {
            // NibbleArray(byte[]) ALIASES its argument: prior provenance is retained.
            args.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 1));
            registration = LiveHookSupport.call("registerNew",
                    "(Ljava/lang/Object;Ljava/lang/Object;)V", args);
        } else {
            // EBS/BSC/BitArray/NibbleArray() own their fresh storage: null backing.
            args.add(new org.objectweb.asm.tree.InsnNode(Opcodes.ACONST_NULL));
            registration = LiveHookSupport.call("registerNew",
                    "(Ljava/lang/Object;Ljava/lang/Object;)V", args);
        }
        LiveHookSupport.insertAfterSuperCtor(mn, registration);
    }
}
