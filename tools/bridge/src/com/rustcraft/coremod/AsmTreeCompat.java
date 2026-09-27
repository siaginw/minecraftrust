package com.rustcraft.coremod;

import java.util.Collections;
import java.util.List;

import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InnerClassNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.ParameterNode;
import org.objectweb.asm.tree.TryCatchBlockNode;

/**
 * Typed views over ASM's tree API that compile against both pinned ASM builds.
 *
 * <p>Clean Forge pins {@code asm-debug-all-5.2} and FTB Revelation pins
 * {@code asm-all-5.2}. Both ship the identical set of classes with the identical
 * JVM descriptors -- that was measured, not assumed, by comparing every symbol
 * RustCraft links against, and the verdict is {@code RUNTIME_ABI_EQUIVALENT}.
 * They differ in exactly one respect: {@code asm-all} is a generic-erasure
 * build whose {@code Signature} attributes are stripped, so {@code ClassNode.methods}
 * is declared {@code List} there and {@code List<MethodNode>} in the other.
 *
 * <p>That is a source-compatibility difference and nothing else. Erasure is not
 * part of the runtime ABI: the field is {@code java.util.List} in both jars, and
 * the list contains {@code MethodNode} elements in both. So the fix is to stop
 * asking the compiler to apply a type argument it cannot see, and to assert the
 * element type once, here, instead of at every use.
 *
 * <p>Deliberately NOT used, because each would break the property that makes
 * the qualification trustworthy:
 * <ul>
 *   <li>reflection, which would make the real field type a runtime discovery
 *       instead of something the compiler and the ABI comparison both see;</li>
 *   <li>a second ASM on the classpath, which would mean the harness no longer
 *       compiles against the ASM its runtime is pinned to;</li>
 *   <li>{@code (Object)} loops, which would erase the element type from the
 *       reader as well as from the compiler and let a wrong list through
 *       silently.</li>
 * </ul>
 *
 * <p>The casts below insert a {@code checkcast} when the compiler emits them and
 * collapse to a no-op when it does not. If a caller ever iterates a list that
 * does not hold the declared type, it gets a {@code ClassCastException} at the
 * use, not a silently wrong identity.
 */
public final class AsmTreeCompat {

    private AsmTreeCompat() {
    }

    /** The declared fields of a class, in declaration order. */
    public static List<FieldNode> fields(ClassNode node) {
        return view(node.fields);
    }

    /** The declared methods of a class, in declaration order. */
    public static List<MethodNode> methods(ClassNode node) {
        return view(node.methods);
    }

    /** The inner-class entries of a class, in declaration order. */
    public static List<InnerClassNode> innerClasses(ClassNode node) {
        return view(node.innerClasses);
    }

    /** The exception handlers of a method, in declaration order. */
    public static List<TryCatchBlockNode> tryCatchBlocks(MethodNode node) {
        return view(node.tryCatchBlocks);
    }

    /** The local-variable table of a method, or an empty list when it has none. */
    public static List<LocalVariableNode> localVariables(MethodNode node) {
        return view(node.localVariables);
    }

    /** The parameter table of a method, or an empty list when it has none. */
    public static List<ParameterNode> parameters(MethodNode node) {
        return view(node.parameters);
    }

    /** The instruction list of a method. Not generic in either build. */
    public static AbstractInsnNode[] instructions(MethodNode node) {
        return node.instructions.toArray();
    }

    @SuppressWarnings("unchecked")
    private static <T> List<T> view(List<?> raw) {
        return raw == null ? Collections.<T>emptyList() : (List<T>) raw;
    }
}
