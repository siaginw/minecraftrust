package com.rustcraft.qualification;

import com.rustcraft.coremod.AsmTreeCompat;
import com.rustcraft.coremod.LiveHookSupport;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Proves, from a callee's OWN bytecode, that a one-shot observation it performs
 * is exception-contained.
 *
 * <p>The point is that a method NAME is not a contract. A wrapper called
 * {@code safeWhatever} whose catch-all has been deleted, or narrowed to
 * {@code Exception}, or moved so it no longer covers the observation, would
 * still be found by name and would still be an unsafe injection into live game
 * code. So the wrapper's class bytes are read from the same classloader that ran
 * the transformation, its catch range is located, and the following are all
 * required before any exception evidence is reported for a callsite:</p>
 *
 * <ol>
 *   <li>the wrapper exists and its descriptor is the one the caller used;</li>
 *   <li>it calls the expected underlying observation -- the same name without
 *       the {@code safe} prefix, in the hook facade;</li>
 *   <li>a catch-all (type {@code null}, so Throwable) range actually CONTAINS
 *       that underlying call, not merely exists somewhere in the method;</li>
 *   <li>the handler does not rethrow, because a rethrow is a lifecycle
 *       contract and this one is containment;</li>
 *   <li>the handler is reachable, i.e. it is preceded by a real jump;</li>
 *   <li>the wrapper does not call itself.</li>
 * </ol>
 *
 * <p>The reported evidence carries a digest of the wrapper's own body, so a
 * callsite's qualification is bound to the exact implementation that run used.
 * Change the wrapper afterwards and the digest no longer matches, which is what
 * stops a callsite staying qualified against an implementation nobody reviewed.</p>
 *
 * <p>Generic by construction: it names no mod, no pack, no profile and no game
 * class, and it is driven entirely by the call instruction found in the
 * transformed bytes.</p>
 */
public final class CalleeIsolation {

    /** How a caller's call is described once the callee is verified. */
    public static final String CONTRACT = "ISOLATED_CALLEE";

    private static final String SAFE_PREFIX = "safe";
    private static final Map<String, String> VERIFIED =
            new ConcurrentHashMap<String, String>();

    /**
     * Why the most recent verification was refused.
     *
     * <p>A verifier that returns null for six different reasons is unusable
     * during qualification: the receipt would say "no exception coverage" and
     * the actual defect would be somewhere else entirely.</p>
     */
    private static volatile String lastRefusal = "none";

    public static String lastRefusal() { return lastRefusal; }

    /**
     * Verifies the callee of an injected call.
     *
     * @return the contract string, or null when the callee is not a verified
     *         safe wrapper -- which the caller must treat as NO coverage
     */
    public static String verify(MethodInsnNode call) {
        byte[] bytes = classBytesOf(call.owner);
        if (bytes == null) {
            lastRefusal = "cannot read the callee class bytes for " + call.owner;
            return null;
        }
        String key = call.owner + "#" + call.name + call.desc;
        String cached = VERIFIED.get(key);
        if (cached != null) {
            lastRefusal = "verified";
            return cached;
        }
        String evidence = verify(bytes, call);
        if (evidence != null) VERIFIED.put(key, evidence);
        return evidence;
    }

    /**
     * The same verification against class bytes the caller already holds.
     *
     * <p>Exposed so a control can verify a wrapper it built itself, which is how
     * the behavioural half of this contract is tested: a synthetic wrapper of the
     * production shape, with an observation that deliberately throws. The
     * production observations never throw, and making one throw to test this
     * would be changing the product in order to observe it.</p>
     */
    public static String verify(byte[] classBytes, MethodInsnNode call) {
        lastRefusal = "not examined";
        if (call.name == null || call.name.length() == SAFE_PREFIX.length()
                || !call.name.startsWith(SAFE_PREFIX)) {
            lastRefusal = "callee is not a safe wrapper: " + call.name;
            return null;
        }
        ClassNode cn;
        try {
            cn = LiveHookSupport.readClass(classBytes);
        } catch (Throwable unreadable) {
            lastRefusal = "callee class bytes are unreadable: " + unreadable;
            return null;
        }
        MethodNode wrapper = null;
        for (MethodNode mn : AsmTreeCompat.methods(cn))
            if (mn.name.equals(call.name) && mn.desc.equals(call.desc)) { wrapper = mn; break; }
        if (wrapper == null) {
            lastRefusal = "no method " + call.name + call.desc + " in " + call.owner;
            return null;
        }
        String reason = analyse(wrapper, call.owner);
        lastRefusal = reason == null ? "verified" : reason;
        if (reason != null) return null;
        return CONTRACT + ":" + call.owner.replace('/', '.') + "#" + call.name
                + call.desc + ";underlying=" + observedUnderlying(wrapper, call.owner)
                + ";wrapper_body_sha256=" + bodyDigest(wrapper);
    }

    private static String analyse(MethodNode wrapper, String owner) {
        AbstractInsnNode[] body = AsmTreeCompat.instructions(wrapper);
        // The isolated observation is identified STRUCTURALLY: it is the one
        // call this wrapper makes into the hook facade that is not a call to
        // itself. Deriving the name from the wrapper's own method name cannot
        // work -- safeIoPendingNbt strips to "IoPendingNbt", which is not
        // ioPendingNbt -- and a name mapping stated inside the wrapper would be
        // a claim to be trusted rather than a call to be counted.
        int underlyingCall = -1;
        String underlyingName = null;
        for (int i = 0; i < body.length; i++) {
            if (body[i].getOpcode() != Opcodes.INVOKESTATIC) continue;
            MethodInsnNode inner = (MethodInsnNode) body[i];
            if (!owner.equals(inner.owner)) continue;
            if (inner.name.equals(wrapper.name))
                return "the wrapper calls itself";
            if (underlyingCall >= 0)
                return "the wrapper makes more than one observation call, so the contract is ambiguous";
            underlyingCall = i;
            underlyingName = inner.name;
        }
        if (underlyingCall < 0)
            return "the wrapper never calls the hook facade at all";
        if (takesThrowable(inner0(body, underlyingCall).desc))
            return "the isolated observation itself takes a Throwable; that is a scoped hook, not a one-shot";
        if (!underlyingName.startsWith(SAFE_PREFIX)) {
            // expected: the underlying is the raw facade method.
        }
        int covered = -1;
        for (TryCatchBlockNode block : AsmTreeCompat.tryCatchBlocks(wrapper)) {
            if (!isCatchAll(block)) continue;
            int from = -1, to = -1;
            for (int i = 0; i < body.length; i++) {
                if (body[i] == block.start) from = i;
                if (body[i] == block.end) { to = i; break; }
            }
            if (block.end == wrapper.instructions.getLast()) to = body.length;
            if (from < 0 || to < 0) continue;
            if (from <= underlyingCall && underlyingCall < to) { covered = to; break; }
        }
        if (covered < 0)
            return "no catch-all range covers the observation call";
        if (covered >= body.length) return "the handler is not reachable";
        int handlerAt = handlerIndex(wrapper, body);
        if (handlerAt < 0) return "the handler is not reachable";
        // Scan the HANDLER's own instructions only. Walking forward to the next
        // GOTO would run on into whatever method follows and find an ATHROW that
        // has nothing to do with this catch.
        for (int i = handlerAt; i < body.length && i < handlerEnd(wrapper, body, handlerAt); i++) {
            if (body[i].getOpcode() == Opcodes.ATHROW)
                return "the handler rethrows; this is a lifecycle contract, not containment";
        }
        return null;
    }

    /** The one non-self facade call this wrapper makes, by name. */
    private static String observedUnderlying(MethodNode wrapper, String owner) {
        for (AbstractInsnNode insn : AsmTreeCompat.instructions(wrapper)) {
            if (insn.getOpcode() != Opcodes.INVOKESTATIC) continue;
            MethodInsnNode call = (MethodInsnNode) insn;
            if (owner.equals(call.owner) && !call.name.equals(wrapper.name)) return call.name;
        }
        return "unknown";
    }

    private static MethodInsnNode inner0(AbstractInsnNode[] body, int index) {
        return (MethodInsnNode) body[index];
    }

    private static boolean takesThrowable(String descriptor) {
        return descriptor != null && descriptor.startsWith("(")
                && descriptor.contains("Ljava/lang/Throwable;");
    }

    /**
     * A catch-all for this contract.
     *
     * <p>javac compiles `catch (Throwable)` with an explicit type, not the null
     * type a synthetic catch-all carries, so requiring null would reject every
     * real wrapper written in Java. A Throwable handler is the widest thing a
     * compiler emits; catching Exception or narrower is a genuine difference,
     * because an Error would escape it, so that is refused.</p>
     */
    private static boolean isCatchAll(TryCatchBlockNode block) {
        return block.type == null || "java/lang/Throwable".equals(block.type);
    }

    /**
     * Where this handler's instructions end.
     *
     * <p>At the next BLOCK boundary, not the next label. javac emits a label
     * inside the handler for the normal path's jump target, and stopping there
     * cut the scan short before the rethrowing athrow -- which is the one
     * instruction this whole check exists to find. A handler that contains an
     * athrow is not a containment contract, and missing it would report an
     * unsafe wrapper as safe.</p>
     */
    private static int handlerEnd(MethodNode mn, AbstractInsnNode[] body, int handlerAt) {
        for (int i = handlerAt + 1; i < body.length; i++) {
            for (TryCatchBlockNode block : AsmTreeCompat.tryCatchBlocks(mn)) {
                if (body[i] == block.start || body[i] == block.handler) return i;
            }
        }
        return body.length;
    }

    private static int handlerIndex(MethodNode mn, AbstractInsnNode[] body) {
        for (TryCatchBlockNode block : AsmTreeCompat.tryCatchBlocks(mn)) {
            if (block.handler == null || !isCatchAll(block)) continue;
            for (int i = 0; i < body.length; i++) if (body[i] == block.handler) return i;
        }
        return -1;
    }

    /**
     * Where this handler's instructions end: at the next label that begins
     * another block, or at the end of the method. Everything in between is the
     * handler's own code.
     */

    /**
     * A digest of the wrapper's own body, so callsite evidence is bound to the
     * exact implementation that ran.
     *
     * <p>Opcode sequence plus the structure around it, not the constant pool: the
     * point is to notice that someone edited the containment, and adding an
     * unrelated constant should not invalidate a callsite's qualification.</p>
     */
    private static String bodyDigest(MethodNode mn) {
        StringBuilder text = new StringBuilder(mn.name).append(mn.desc).append('|');
        for (AbstractInsnNode insn : AsmTreeCompat.instructions(mn)) {
            int op = insn.getOpcode();
            text.append(op).append(';');
            if (insn instanceof MethodInsnNode) {
                MethodInsnNode call = (MethodInsnNode) insn;
                text.append(call.owner).append('.').append(call.name).append(call.desc).append(';');
            }
        }
        text.append("|tc:");
        for (TryCatchBlockNode block : AsmTreeCompat.tryCatchBlocks(mn))
            text.append(block.type == null ? "*" : block.type).append(',');
        return SameProcessAcquisition.sha256(
                text.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** The wrapper's class bytes, read from the loader that is running this. */
    private static byte[] classBytesOf(String internalOwner) {
        String resource = internalOwner + ".class";
        ClassLoader loader = CalleeIsolation.class.getClassLoader();
        InputStream in = loader == null
                ? ClassLoader.getSystemResourceAsStream(resource)
                : loader.getResourceAsStream(resource);
        if (in == null) return null;
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) > 0) out.write(chunk, 0, read);
            return out.toByteArray();
        } catch (java.io.IOException unreadable) {
            return null;
        } finally {
            try { in.close(); } catch (java.io.IOException ignored) { }
        }
    }

    /** Clears the verification cache. Only for controls that mutate a wrapper. */
    public static void forget() { VERIFIED.clear(); }

    private CalleeIsolation() { throw new AssertionError(); }
}
