package com.rustcraft.coremod;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

/**
 * Complete, conservative Java 8 identity. The wire schema is canonical JSON
 * arrays, headed by CANONICAL_ID_V2. This is an identity, not a JVM verifier.
 * Unknown attributes, newer classfiles and incomplete label mappings fail
 * closed. Constant pool references become symbolic values; code positions
 * become instruction ordinals, including the end-of-code ordinal.
 *
 * Member declarations are sorted in the semantic digest only. Java does not
 * specify reflection enumeration order, but existing mods can observe it.
 * Therefore Result also exposes declarationOrderSha256: qualification MUST bind
 * it unless the profile explicitly qualifies enumeration-order independence.
 * A semantic digest alone is not an authority certificate.
 */
public final class CanonicalClassIdentityV2 {
    public static final String SCHEMA = "CANONICAL_ID_V2";

    private CanonicalClassIdentityV2() { }

    public static final class Result {
        public final String schema = SCHEMA;
        public final String className;
        public final String canonicalJson;
        public final String semanticSha256;
        public final String declarationOrderSha256;
        public final String rawSha256;
        /** Session-invariant projection: declared process-bound metadata masked. */
        public final String sessionInvariantSha256;
        /** Distinct qualified sessionId values the mask replaced (sorted). */
        public final List<String> maskedValues;
        /** Total canonical-projection occurrences the mask replaced. */
        public final int maskedOccurrenceCount;
        /** Exact annotation provenances that were masked. */
        public final List<String> maskedLocations;

        private Result(String name, String canonical, String order, byte[] original) {
            this(name, canonical, order, original, null, null, null, 0);
        }

        private Result(String name, String canonical, String order, byte[] original,
                String sessionInvariant, List<String> maskedValues, List<String> maskedLocations,
                int maskedOccurrenceCount) {
            className = name;
            canonicalJson = canonical;
            semanticSha256 = sha256(canonical.getBytes(StandardCharsets.UTF_8));
            declarationOrderSha256 = sha256(order.getBytes(StandardCharsets.UTF_8));
            rawSha256 = sha256(original);
            this.sessionInvariantSha256 = sessionInvariant;
            this.maskedValues = maskedValues == null
                    ? java.util.Collections.<String>emptyList()
                    : java.util.Collections.unmodifiableList(maskedValues);
            this.maskedLocations = maskedLocations == null
                    ? java.util.Collections.<String>emptyList()
                    : java.util.Collections.unmodifiableList(maskedLocations);
            this.maskedOccurrenceCount = maskedOccurrenceCount;
        }

        public String receiptJson() {
            return json(a(schema, className, semanticSha256, declarationOrderSha256, rawSha256));
        }

        /** Session-bound receipt: exact + invariant + full mask provenance. */
        public String sessionBoundReceiptJson() {
            return json(a(SCHEMA_SESSION_BOUND, className, semanticSha256,
                    declarationOrderSha256, rawSha256, sessionInvariantSha256,
                    maskedValues.size(), maskedValues, maskedOccurrenceCount, maskedLocations));
        }
    }

    public static final String SCHEMA_SESSION_BOUND = "CANONICAL_ID_V2_SESSION_BOUND";

    /**
     * Declared process-bound mask, structurally bound to the proven Mixin
     * merged-method provenance: an annotation whose descriptor is EXACTLY
     * MIXIN_MERGED_DESC, under its EXACT sessionId element, whose value is a
     * canonical RFC-4122-shaped UUID. Nothing else is exempted: unrelated
     * UUID constants, UUIDs under other annotations, UUIDs under other
     * MixinMerged elements and any other UUID-shaped bytes stay fully
     * significant. The corresponding symbolic-pool UTF8 value is normalized
     * only insofar as it represents that qualified annotation provenance
     * (pool-level value equality with a qualified sessionId; javac dedups
     * identical UTF8 constants into one pool entry). Masking is applied
     * BEFORE sorted rendering, so the projection is order-stable across
     * processes. The exact semantic identity stays untouched; callers must
     * separately prove within-process sessionId equality so the mask
     * describes the same transformation session on both sides, never assumed.
     */
    public static final String MIXIN_MERGED_DESC =
            "Lorg/spongepowered/asm/mixin/transformer/meta/MixinMerged;";
    public static final String SESSION_ELEMENT = "sessionId";
    public static final String SESSION_PLACEHOLDER = "PROCESS_BOUND_SESSION_ID";
    private static final java.util.regex.Pattern SESSION_UUID = java.util.regex.Pattern
        .compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    /** One qualified sessionId provenance: where it was found and its value. */
    public static final class SessionId {
        public final String location;   // "class" | "field:name+desc" | "method:name+desc"
        public final boolean visible;
        public final String descriptor; // exact annotation descriptor
        public final String element;    // exact element key
        public final String value;      // the UUID

        SessionId(String location, boolean visible, String descriptor, String element, String value) {
            this.location = location; this.visible = visible; this.descriptor = descriptor;
            this.element = element; this.value = value;
        }

        /**
         * A location is a SITE, and a site is identical on every launch. The
         * value it carried is the process-bound fact, so it stays in
         * {@link #maskedValues} and in the runtime-issued certificate. Folding it
         * into the location would make the expected location unpinnable by any
         * static policy, which is the whole reason the policy is split out.
         */
        public String render() {
            return location + " visible=" + visible + " " + descriptor + "#" + element;
        }

        /** The same site together with the value it carried. Per-launch evidence only. */
        public String renderWithValue() {
            return render() + "=" + value;
        }
    }

    public static final class IdentityFailure extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        IdentityFailure(String message) { super(SCHEMA + ": " + message); }
        IdentityFailure(String message, Throwable cause) { super(SCHEMA + ": " + message, cause); }
    }

    /** Exact V2 identity: no session mask. */
    public static Result identify(byte[] bytes) {
        if (bytes == null) throw new IdentityFailure("null classfile");
        try {
            ClassNode cn = new ClassNode();
            new ClassReader(bytes).accept(cn, ClassReader.EXPAND_FRAMES);
            String[] parts = renderCanonical(bytes, cn, null);
            return new Result(parts[2], parts[0], parts[1], bytes);
        } catch (IdentityFailure rejected) {
            throw rejected;
        } catch (RuntimeException malformed) {
            // ASM reports a truncated/foreign classfile as IllegalArgumentException.
            throw new IdentityFailure("malformed classfile", malformed);
        } catch (java.io.IOException failure) {
            throw new IdentityFailure("malformed classfile", failure);
        }
    }

    /**
     * Session-bound identity: structurally scan the provenances, then render
     * the canonical projection with the qualified sessionId values masked
     * before sorted rendering. The exact semantic identity is rendered
     * separately, unmasked.
     */
    public static Result identifySessionBound(byte[] bytes) {
        if (bytes == null) throw new IdentityFailure("null classfile");
        ClassNode cn = new ClassNode();
        final String[] invariantRef = new String[1];
        final int[] occurrenceRef = new int[1];
        final List<String> locationsRef = new ArrayList<String>();
        final List<String> valuesRef = new ArrayList<String>();
        final String[] exactCanonicalRef = new String[1];
        final String[] classNameRef = new String[1];
        final String[] declarationOrderRef = new String[1];
        try {
            new ClassReader(bytes).accept(cn, ClassReader.EXPAND_FRAMES);
            List<SessionId> sessions = new ArrayList<SessionId>();
            collect(sessions, "class", cn.name, cn.visibleAnnotations, cn.invisibleAnnotations);
            for (FieldNode f : AsmTreeCompat.fields(cn))
                collect(sessions, "field:" + f.name + "+" + f.desc, f.name,
                        f.visibleAnnotations, f.invisibleAnnotations);
            for (MethodNode m : AsmTreeCompat.methods(cn))
                collect(sessions, "method:" + m.name + "+" + m.desc, m.name,
                        m.visibleAnnotations, m.invisibleAnnotations);
            if (sessions.isEmpty())
                throw new IdentityFailure(cn.name + ": session-bound identity requires at least one "
                        + MIXIN_MERGED_DESC + "#" + SESSION_ELEMENT + " provenance");
            java.util.TreeSet<String> mask = new java.util.TreeSet<String>();
            for (SessionId sid : sessions) mask.add(sid.value);
            String[] exactParts = renderCanonical(bytes, cn, null);
            String[] maskedParts = renderCanonical(bytes, cn, mask);
            int occurrences = 0;
            for (String value : mask) {
                int index = 0, count = 0;
                while ((index = exactParts[0].indexOf(value, index)) >= 0) {
                    count++; index += value.length();
                }
                if (count == 0)
                    throw new IdentityFailure(cn.name + ": qualified sessionId " + value
                            + " absent from the exact canonical projection");
                occurrences += count;
            }
            exactCanonicalRef[0] = exactParts[0];
            classNameRef[0] = exactParts[2];
            declarationOrderRef[0] = exactParts[1];
            invariantRef[0] = sha256(maskedParts[0].getBytes(StandardCharsets.UTF_8));
            occurrenceRef[0] = occurrences;
            valuesRef.addAll(mask);
            for (SessionId sid : sessions) locationsRef.add(sid.render());
        } catch (IdentityFailure rejected) {
            throw rejected;
        } catch (RuntimeException malformed) {
            throw new IdentityFailure("malformed classfile", malformed);
        } catch (java.io.IOException failure) {
            throw new IdentityFailure("malformed classfile", failure);
        }
        // The mask sites are a SET: the order they were discovered in is an
        // artifact of how the class happens to be laid out, and both the
        // admission policy and the certificate require them sorted. Reported
        // unsorted, a real multi-site class produced a certificate neither
        // schema would accept -- while a single-site fixture always was,
        // which is why it only showed up against a real runtime.
        Collections.sort(locationsRef);
        return new Result(classNameRef[0], exactCanonicalRef[0], declarationOrderRef[0], bytes,
                invariantRef[0], valuesRef, locationsRef, occurrenceRef[0]);
    }

    /**
     * Full canonical rendering. sessionMask == null renders the exact
     * identity; otherwise every MixinMerged.sessionId annotation value and
     * the corresponding raw-pool UTF8 value are masked before sorted
     * rendering, so the projection is order-stable across processes.
     */
    private static String[] renderCanonical(byte[] bytes, ClassNode cn,
            java.util.Set<String> sessionMask) throws java.io.IOException {
        RawClass raw = new RawClass(bytes);
        raw.sessionMask = sessionMask;
        rejectAttributes(cn.attrs);
        List<Object> declarationOrder = a(SCHEMA + "_DECLARATION_ORDER");
        for (FieldNode f : AsmTreeCompat.fields(cn)) declarationOrder.add(a("field", f.name, f.desc));
        for (MethodNode m : AsmTreeCompat.methods(cn)) declarationOrder.add(a("method", m.name, m.desc));

        List<FieldNode> fields = new ArrayList<FieldNode>(AsmTreeCompat.fields(cn));
        Collections.sort(fields, new Comparator<FieldNode>() {
            public int compare(FieldNode x, FieldNode y) {
                int n = x.name.compareTo(y.name);
                return n == 0 ? x.desc.compareTo(y.desc) : n;
            }
        });
        List<Object> fs = new ArrayList<Object>();
        Set<String> fieldKeys = new HashSet<String>();
        for (FieldNode f : fields) {
            if (!fieldKeys.add(f.name + "\u0000" + f.desc)) throw new IdentityFailure("duplicate field");
            rejectAttributes(f.attrs);
            fs.add(a(cn.name, f.name, f.desc, f.signature, f.access, constant(f.value),
                    annotations(f.visibleAnnotations, f.invisibleAnnotations, sessionMask),
                    typeAnnotations(f.visibleTypeAnnotations, f.invisibleTypeAnnotations)));
        }
        List<MethodNode> methods = new ArrayList<MethodNode>(AsmTreeCompat.methods(cn));
        Collections.sort(methods, new Comparator<MethodNode>() {
            public int compare(MethodNode x, MethodNode y) {
                int n = x.name.compareTo(y.name);
                return n == 0 ? x.desc.compareTo(y.desc) : n;
            }
        });
        List<Object> ms = new ArrayList<Object>();
        Set<String> methodKeys = new HashSet<String>();
        for (MethodNode m : methods) {
            if (!methodKeys.add(m.name + "\u0000" + m.desc)) throw new IdentityFailure("duplicate method");
            ms.add(method(m, raw.parameterCounts.get(m.name + "\u0000" + m.desc), sessionMask));
        }
        List<Object> inner = new ArrayList<Object>();
        for (InnerClassNode i : AsmTreeCompat.innerClasses(cn)) inner.add(a(i.name, i.outerName, i.innerName, i.access));
        String canonical = json(a(SCHEMA, cn.version, cn.access, cn.name, cn.signature,
                cn.superName, cn.interfaces, cn.sourceFile, cn.sourceDebug,
                cn.outerClass, cn.outerMethod, cn.outerMethodDesc,
                annotations(cn.visibleAnnotations, cn.invisibleAnnotations, sessionMask),
                typeAnnotations(cn.visibleTypeAnnotations, cn.invisibleTypeAnnotations),
                inner, fs, ms, raw.symbolicPool(), raw.symbolicBootstraps()));
        return new String[] {canonical, json(declarationOrder), cn.name};
    }

    private static void collect(List<SessionId> out, String location, String owner,
            List<AnnotationNode> visible, List<AnnotationNode> invisible) {
        collectOne(out, location, owner, true, visible);
        collectOne(out, location, owner, false, invisible);
    }

    private static void collectOne(List<SessionId> out, String location, String owner,
            boolean visible, List<AnnotationNode> nodes) {
        if (nodes == null) return;
        for (AnnotationNode n : nodes) {
            if (!MIXIN_MERGED_DESC.equals(n.desc) || n.values == null) continue;
            for (int i = 0; i + 1 < n.values.size(); i += 2) {
                if (!SESSION_ELEMENT.equals(n.values.get(i))) continue;
                Object value = n.values.get(i + 1);
                if (!(value instanceof String)
                        || !SESSION_UUID.matcher((String) value).matches())
                    throw new IdentityFailure(owner + ": " + MIXIN_MERGED_DESC + "#"
                            + SESSION_ELEMENT + " is not a canonical UUID: " + value);
                out.add(new SessionId(location, visible, n.desc, SESSION_ELEMENT, (String) value));
            }
        }
    }

    private static Object method(MethodNode m, List<Object> rawParameterAnnotationCounts,
            java.util.Set<String> sessionMask) {
        rejectAttributes(m.attrs);
        IdentityHashMap<LabelNode, Integer> labels = new IdentityHashMap<LabelNode, Integer>();
        int count = 0;
        for (AbstractInsnNode n : m.instructions.toArray()) {
            if (n instanceof LabelNode) labels.put((LabelNode) n, count);
            else if (n.getOpcode() >= 0) count++;
        }
        List<Object> instructions = new ArrayList<Object>();
        List<Object> frames = new ArrayList<Object>();
        List<Object> lines = new ArrayList<Object>();
        int ordinal = 0;
        for (AbstractInsnNode n : m.instructions.toArray()) {
            if (n instanceof LabelNode) continue;
            if (n instanceof FrameNode) {
                FrameNode f = (FrameNode) n;
                frames.add(a(ordinal, f.type, frameValues(f.local, labels), frameValues(f.stack, labels)));
                continue;
            }
            if (n instanceof LineNumberNode) {
                LineNumberNode l = (LineNumberNode) n;
                lines.add(a(l.line, label(l.start, labels)));
                continue;
            }
            if (n.getOpcode() < 0) throw new IdentityFailure("unknown pseudo instruction");
            List<Object> operands = new ArrayList<Object>();
            if (n instanceof InsnNode) { /* no operands */ }
            else if (n instanceof IntInsnNode) operands.add(((IntInsnNode) n).operand);
            else if (n instanceof VarInsnNode) operands.add(((VarInsnNode) n).var);
            else if (n instanceof TypeInsnNode) operands.add(((TypeInsnNode) n).desc);
            else if (n instanceof FieldInsnNode) {
                FieldInsnNode f = (FieldInsnNode) n;
                operands.addAll(a(f.owner, f.name, f.desc));
            } else if (n instanceof MethodInsnNode) {
                MethodInsnNode c = (MethodInsnNode) n;
                operands.addAll(a(c.owner, c.name, c.desc, c.itf));
            } else if (n instanceof InvokeDynamicInsnNode) {
                InvokeDynamicInsnNode d = (InvokeDynamicInsnNode) n;
                List<Object> args = new ArrayList<Object>();
                for (Object argument : d.bsmArgs) args.add(constant(argument));
                operands.addAll(a(d.name, d.desc, constant(d.bsm), args));
            } else if (n instanceof JumpInsnNode) operands.add(label(((JumpInsnNode) n).label, labels));
            else if (n instanceof LdcInsnNode) operands.add(constant(((LdcInsnNode) n).cst));
            else if (n instanceof IincInsnNode) {
                IincInsnNode i = (IincInsnNode) n;
                operands.addAll(a(i.var, i.incr));
            } else if (n instanceof TableSwitchInsnNode) {
                TableSwitchInsnNode s = (TableSwitchInsnNode) n;
                operands.addAll(a(s.min, s.max, label(s.dflt, labels), labelList(s.labels, labels)));
            } else if (n instanceof LookupSwitchInsnNode) {
                LookupSwitchInsnNode s = (LookupSwitchInsnNode) n;
                operands.addAll(a(s.keys, label(s.dflt, labels), labelList(s.labels, labels)));
            } else if (n instanceof MultiANewArrayInsnNode) {
                MultiANewArrayInsnNode x = (MultiANewArrayInsnNode) n;
                operands.addAll(a(x.desc, x.dims));
            } else throw new IdentityFailure("unknown instruction node: " + n.getClass().getName());
            instructions.add(a(n.getOpcode(), operands,
                    typeAnnotations(n.visibleTypeAnnotations, n.invisibleTypeAnnotations)));
            ordinal++;
        }
        List<Object> handlers = new ArrayList<Object>();
        for (TryCatchBlockNode t : AsmTreeCompat.tryCatchBlocks(m)) {
            handlers.add(a(label(t.start, labels), label(t.end, labels), label(t.handler, labels), t.type,
                    typeAnnotations(t.visibleTypeAnnotations, t.invisibleTypeAnnotations)));
        }
        List<Object> locals = new ArrayList<Object>();
        for (LocalVariableNode l : AsmTreeCompat.localVariables(m))
            locals.add(a(l.name, l.desc, l.signature, label(l.start, labels), label(l.end, labels), l.index));
        List<Object> parameters = null;
        if (m.parameters != null) {
            parameters = new ArrayList<Object>();
            for (ParameterNode p : AsmTreeCompat.parameters(m)) parameters.add(a(p.name, p.access));
        }
        return a(m.name, m.desc, m.signature, m.access, m.exceptions, parameters,
                annotations(m.visibleAnnotations, m.invisibleAnnotations, sessionMask),
                typeAnnotations(m.visibleTypeAnnotations, m.invisibleTypeAnnotations),
                parameterAnnotations(m.visibleParameterAnnotations, m.invisibleParameterAnnotations),
                annotationValue(m.annotationDefault), m.maxStack, m.maxLocals, instructions,
                handlers, frames, lines, locals,
                localAnnotations(m.visibleLocalVariableAnnotations, m.invisibleLocalVariableAnnotations, labels),
                rawParameterAnnotationCounts.subList(0, 2), rawParameterAnnotationCounts.get(2));
    }

    private static List<Object> frameValues(List<Object> values, IdentityHashMap<LabelNode, Integer> labels) {
        List<Object> result = new ArrayList<Object>();
        if (values != null) for (Object v : values) {
            if (v instanceof LabelNode) result.add(a("uninitialized", label((LabelNode) v, labels)));
            else if (v instanceof Integer) result.add(a("verification_tag", v));
            else if (v instanceof String) result.add(a("object", v));
            else throw new IdentityFailure("unknown frame value");
        }
        return result;
    }

    private static int label(LabelNode l, IdentityHashMap<LabelNode, Integer> labels) {
        Integer ordinal = labels.get(l);
        if (ordinal == null) throw new IdentityFailure("unbound code label");
        return ordinal;
    }

    private static List<Object> labelList(List<LabelNode> ls, IdentityHashMap<LabelNode, Integer> labels) {
        List<Object> values = new ArrayList<Object>();
        for (LabelNode l : ls) values.add(label(l, labels));
        return values;
    }

    private static Object constant(Object value) {
        if (value == null) return null;
        if (value instanceof Integer) return a("int", value);
        if (value instanceof Long) return a("long", value.toString());
        if (value instanceof Float) return a("float_bits", hex(Float.floatToRawIntBits((Float) value), 8));
        if (value instanceof Double) return a("double_bits", hex(Double.doubleToRawLongBits((Double) value), 16));
        if (value instanceof String) return a("string", value);
        if (value instanceof Type) return a("type", ((Type) value).getDescriptor());
        if (value instanceof Handle) {
            Handle h = (Handle) value;
            return a("handle", h.getTag(), h.getOwner(), h.getName(), h.getDesc(), h.isInterface());
        }
        throw new IdentityFailure("unsupported constant type: " + value.getClass().getName());
    }

    private static Object annotationValue(Object value) {
        if (value == null) return null;
        if (value instanceof AnnotationNode) {
            AnnotationNode n = (AnnotationNode) value;
            return a("annotation", n.desc, annotationPairs(n.values, null, null));
        }
        if (value instanceof String[]) {
            String[] e = (String[]) value;
            if (e.length != 2) throw new IdentityFailure("bad enum annotation value");
            return a("enum", e[0], e[1]);
        }
        if (value instanceof List<?>) {
            List<Object> values = new ArrayList<Object>();
            for (Object v : (List<?>) value) values.add(annotationValue(v));
            return a("array", values);
        }
        if (value instanceof Byte) return a("byte", ((Byte) value).intValue());
        if (value instanceof Short) return a("short", ((Short) value).intValue());
        if (value instanceof Character) return a("char", (int) ((Character) value).charValue());
        if (value instanceof Boolean) return a("boolean", value);
        return constant(value);
    }

    private static Object annotationPairs(List<Object> pairs, String annotationDesc,
            java.util.Set<String> sessionMask) {
        List<Object> result = new ArrayList<Object>();
        if (pairs != null) {
            if ((pairs.size() & 1) != 0) throw new IdentityFailure("odd annotation values");
            for (int i = 0; i < pairs.size(); i += 2) {
                Object value = annotationValue(pairs.get(i + 1));
                if (sessionMask != null && MIXIN_MERGED_DESC.equals(annotationDesc)
                        && SESSION_ELEMENT.equals(pairs.get(i))
                        && value instanceof List<?>) {
                    List<?> entry = (List<?>) value;
                    if (entry.size() == 2 && "string".equals(entry.get(0))
                            && sessionMask.contains(entry.get(1)))
                        value = a("string", SESSION_PLACEHOLDER);
                }
                result.add(a(pairs.get(i), value));
            }
        }
        return result;
    }

    private static List<Object> annotations(List<AnnotationNode> visible,
            List<AnnotationNode> invisible, java.util.Set<String> sessionMask) {
        List<Object> values = new ArrayList<Object>();
        addAnnotations(values, visible, true, sessionMask);
        addAnnotations(values, invisible, false, sessionMask);
        return values;
    }

    private static void addAnnotations(List<Object> out, List<AnnotationNode> nodes, boolean visible,
            java.util.Set<String> sessionMask) {
        if (nodes != null) for (AnnotationNode n : nodes)
            out.add(a(visible, n.desc, annotationPairs(n.values, n.desc, sessionMask)));
    }

    private static Object typeAnnotations(List<TypeAnnotationNode> visible, List<TypeAnnotationNode> invisible) {
        List<Object> values = new ArrayList<Object>();
        addTypeAnnotations(values, visible, true);
        addTypeAnnotations(values, invisible, false);
        return values;
    }

    private static void addTypeAnnotations(List<Object> out, List<TypeAnnotationNode> nodes, boolean visible) {
        if (nodes != null) for (TypeAnnotationNode n : nodes)
            out.add(a(visible, n.typeRef, n.typePath == null ? null : n.typePath.toString(),
                    n.desc, annotationPairs(n.values, null, null)));
    }

    private static Object parameterAnnotations(List<AnnotationNode>[] visible, List<AnnotationNode>[] invisible) {
        return a(parameterAnnotationSide(visible), parameterAnnotationSide(invisible));
    }

    private static Object parameterAnnotationSide(List<AnnotationNode>[] side) {
        if (side == null) return null;
        List<Object> result = new ArrayList<Object>();
        for (List<AnnotationNode> nodes : side) {
            List<Object> entry = new ArrayList<Object>();
            addAnnotations(entry, nodes, true, null);
            result.add(entry);
        }
        return result;
    }

    private static Object localAnnotations(List<LocalVariableAnnotationNode> visible,
            List<LocalVariableAnnotationNode> invisible, IdentityHashMap<LabelNode, Integer> labels) {
        List<Object> values = new ArrayList<Object>();
        addLocalAnnotations(values, visible, true, labels);
        addLocalAnnotations(values, invisible, false, labels);
        return values;
    }

    private static void addLocalAnnotations(List<Object> out, List<LocalVariableAnnotationNode> nodes,
            boolean visible, IdentityHashMap<LabelNode, Integer> labels) {
        if (nodes != null) for (LocalVariableAnnotationNode n : nodes)
            out.add(a(visible, n.typeRef, n.typePath == null ? null : n.typePath.toString(),
                    n.desc, annotationPairs(n.values, null, null), labelList(n.start, labels),
                    labelList(n.end, labels), n.index));
    }

    private static void rejectAttributes(List<?> attrs) {
        if (attrs != null && !attrs.isEmpty()) throw new IdentityFailure("unhandled ASM attribute");
    }

    private static List<Object> a(Object... values) {
        return new ArrayList<Object>(Arrays.asList(values));
    }

    private static String hex(long value, int width) {
        String s = Long.toHexString(value);
        if (s.length() > width) s = s.substring(s.length() - width);
        StringBuilder b = new StringBuilder();
        while (b.length() + s.length() < width) b.append('0');
        return b.append(s).toString();
    }

    private static String json(Object value) {
        StringBuilder b = new StringBuilder();
        appendJson(b, value);
        return b.toString();
    }

    private static void appendJson(StringBuilder b, Object value) {
        if (value == null) { b.append("null"); return; }
        if (value instanceof String) {
            b.append('"');
            for (char c : ((String) value).toCharArray()) {
                if (c == '"' || c == '\\') b.append('\\').append(c);
                else if (c < 0x20 || c > 0x7e) b.append("\\u").append(hex(c, 4));
                else b.append(c);
            }
            b.append('"');
        } else if (value instanceof Integer || value instanceof Long || value instanceof Boolean) {
            b.append(value);
        } else if (value instanceof List<?>) {
            b.append('[');
            boolean first = true;
            for (Object v : (List<?>) value) {
                if (!first) b.append(',');
                first = false;
                appendJson(b, v);
            }
            b.append(']');
        } else throw new IdentityFailure("unhandled JSON type: " + value.getClass().getName());
    }

    private static String sha256(byte[] bytes) {
        try {
            StringBuilder result = new StringBuilder();
            for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes))
                result.append(hex(value & 255, 2));
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new IdentityFailure("SHA-256 unavailable", failure);
        }
    }

    /**
     * Raw structural preflight prevents ASM's unknown-attribute skipping from
     * creating a successful incomplete identity. It also retains unused pool
     * constants/bootstrap entries, resolved recursively without pool indices.
     */
    private static final class RawClass {
        final byte[] data;
        final int[] tags;
        final Object[] cp;
        final Object[] resolved;
        final boolean[] resolving;
        final List<int[]> bootstraps = new ArrayList<int[]>();
        final Map<String, List<Object>> parameterCounts = new HashMap<String, List<Object>>();
        List<Object> activeParameterCounts;
        List<String> localVariableKeys;
        List<String> localVariableTypeKeys;
        java.util.Set<String> sessionMask;
        int p;

        RawClass(byte[] bytes) throws IOException {
            data = bytes;
            if (u4() != 0xcafebabeL) throw new IdentityFailure("bad magic");
            int minor = u2(), major = u2();
            if (major < 45 || major > 52 || (minor != 0 && !(major == 45 && minor == 3)))
                throw new IdentityFailure("unsupported classfile version " + major + "." + minor);
            int size = u2();
            if (size == 0) throw new IdentityFailure("empty constant pool");
            tags = new int[size]; cp = new Object[size]; resolved = new Object[size]; resolving = new boolean[size];
            for (int i = 1; i < size; i++) {
                int tag = tags[i] = u1();
                switch (tag) {
                    case 1:
                        int length = u2();
                        check(length);
                        cp[i] = new DataInputStream(new ByteArrayInputStream(data, p - 2, length + 2)).readUTF();
                        p += length;
                        break;
                    case 3: cp[i] = (int) u4(); break;
                    case 4: cp[i] = hex(u4(), 8); break;
                    case 5: case 6:
                        long value = (u4() << 32) | u4();
                        cp[i] = tag == 5 ? Long.toString(value) : hex(value, 16);
                        if (++i >= size) throw new IdentityFailure("truncated two-slot constant");
                        break;
                    case 7: case 8: case 16: cp[i] = new int[] {u2()}; break;
                    case 9: case 10: case 11: case 12: case 18: cp[i] = new int[] {u2(), u2()}; break;
                    case 15: cp[i] = new int[] {u1(), u2()}; break;
                    default: throw new IdentityFailure("unsupported constant pool tag " + tag);
                }
            }
            u2(); require(u2(), 7); int superIndex = u2(); if (superIndex != 0) require(superIndex, 7);
            int interfaces = u2(); for (int i = 0; i < interfaces; i++) require(u2(), 7);
            members("field"); members("method"); attributes("class", data.length);
            if (p != data.length) throw new IdentityFailure("trailing classfile bytes");
            for (int i = 1; i < size; i++) if (tags[i] != 0) resolve(i);
        }

        void members(String kind) {
            int count = u2();
            for (int i = 0; i < count; i++) {
                u2(); int name = u2(), desc = u2(); require(name, 1); require(desc, 1);
                activeParameterCounts = "method".equals(kind) ? a(null, null, null) : null;
                attributes(kind, data.length);
                if (activeParameterCounts != null)
                    parameterCounts.put(cp[name] + "\u0000" + cp[desc], activeParameterCounts);
                activeParameterCounts = null;
            }
        }

        void attributes(String context, int enclosingEnd) {
            int count = u2();
            Set<String> seen = new HashSet<String>();
            for (int i = 0; i < count; i++) {
                int nameIndex = u2(); require(nameIndex, 1); String name = (String) cp[nameIndex];
                long length = u4();
                if (length > Integer.MAX_VALUE) throw new IdentityFailure("oversized attribute");
                check((int) length); int end = p + (int) length;
                if (end > enclosingEnd) throw new IdentityFailure("attribute exceeds enclosing body");
                if (!seen.add(name)) throw new IdentityFailure("duplicate " + context + " attribute " + name);
                if (!allowed(context, name)) throw new IdentityFailure("unsupported " + context + " attribute " + name);
                if ("Code".equals(name)) {
                    u2(); u2(); long codeLength = u4();
                    if (codeLength == 0 || codeLength > 65535) throw new IdentityFailure("invalid code length");
                    skip((int) codeLength);
                    int handlers = u2(); skip(handlers * 8);
                    localVariableKeys = new ArrayList<String>();
                    localVariableTypeKeys = new ArrayList<String>();
                    attributes("code", end);
                    for (String key : localVariableTypeKeys)
                        if (!localVariableKeys.contains(key))
                            throw new IdentityFailure("orphan LocalVariableTypeTable entry unsupported by ASM5 identity");
                    localVariableKeys = null; localVariableTypeKeys = null;
                    if (p != end) throw new IdentityFailure("Code attribute length mismatch");
                } else if ("BootstrapMethods".equals(name)) {
                    int n = u2();
                    for (int j = 0; j < n; j++) {
                        int handle = u2(); require(handle, 15); int args = u2();
                        int[] values = new int[args + 1]; values[0] = handle;
                        for (int k = 0; k < args; k++) values[k + 1] = u2();
                        bootstraps.add(values);
                    }
                    if (p != end) throw new IdentityFailure("BootstrapMethods length mismatch");
                } else knownAttribute(name, context, end);
                if (p != end) throw new IdentityFailure(name + " attribute length mismatch");
            }
        }

        void knownAttribute(String name, String context, int end) {
            if ("Synthetic".equals(name) || "Deprecated".equals(name)) return;
            if ("SourceDebugExtension".equals(name)) { p = end; return; }
            if ("SourceFile".equals(name) || "Signature".equals(name)) { require(u2(), 1); return; }
            if ("ConstantValue".equals(name)) {
                requireOneOf(u2(), 3, 4, 5, 6, 8); return;
            }
            if ("Exceptions".equals(name)) {
                int count = u2(); for (int i = 0; i < count; i++) require(u2(), 7); return;
            }
            if ("EnclosingMethod".equals(name)) {
                require(u2(), 7); optional(u2(), 12); return;
            }
            if ("InnerClasses".equals(name)) {
                int count = u2();
                for (int i = 0; i < count; i++) { require(u2(), 7); optional(u2(), 7); optional(u2(), 1); u2(); }
                return;
            }
            if ("MethodParameters".equals(name)) {
                int count = u1(); activeParameterCounts.set(2, count);
                for (int i = 0; i < count; i++) { optional(u2(), 1); u2(); } return;
            }
            if ("LineNumberTable".equals(name)) {
                int count = u2(); skip(count * 4); return;
            }
            if ("LocalVariableTable".equals(name) || "LocalVariableTypeTable".equals(name)) {
                int count = u2();
                List<String> keys = "LocalVariableTable".equals(name) ? localVariableKeys : localVariableTypeKeys;
                for (int i = 0; i < count; i++) {
                    int start = u2(), length = u2(), localName = u2(), type = u2(), slot = u2();
                    require(localName, 1); require(type, 1);
                    String key = start + ":" + length + ":" + slot + ":" + cp[localName];
                    if (keys.contains(key)) throw new IdentityFailure("duplicate local variable metadata entry");
                    keys.add(key);
                }
                return;
            }
            if ("StackMapTable".equals(name)) {
                int count = u2();
                for (int i = 0; i < count; i++) {
                    int frame = u1();
                    if (frame <= 63) { /* same frame */ }
                    else if (frame <= 127) verificationType();
                    else if (frame == 247) { u2(); verificationType(); }
                    else if (frame >= 248 && frame <= 251) u2();
                    else if (frame >= 252 && frame <= 254) {
                        u2(); for (int j = 0; j < frame - 251; j++) verificationType();
                    } else if (frame == 255) {
                        u2(); int locals = u2(); for (int j = 0; j < locals; j++) verificationType();
                        int stack = u2(); for (int j = 0; j < stack; j++) verificationType();
                    } else throw new IdentityFailure("reserved stack frame type");
                }
                return;
            }
            if ("AnnotationDefault".equals(name)) { annotationElement(0); return; }
            if (name.equals("RuntimeVisibleAnnotations") || name.equals("RuntimeInvisibleAnnotations")) {
                int count = u2(); for (int i = 0; i < count; i++) annotation(0); return;
            }
            if (name.equals("RuntimeVisibleParameterAnnotations") || name.equals("RuntimeInvisibleParameterAnnotations")) {
                int parameters = u1();
                if (activeParameterCounts == null) throw new IdentityFailure("parameter annotation without method");
                activeParameterCounts.set(name.equals("RuntimeVisibleParameterAnnotations") ? 0 : 1, parameters);
                for (int i = 0; i < parameters; i++) {
                    int count = u2(); for (int j = 0; j < count; j++) annotation(0);
                }
                return;
            }
            if (name.equals("RuntimeVisibleTypeAnnotations") || name.equals("RuntimeInvisibleTypeAnnotations")) {
                int count = u2();
                for (int i = 0; i < count; i++) {
                    int target = u1();
                    if ("class".equals(context) && target != 0 && target != 0x10 && target != 0x11
                            || "field".equals(context) && target != 0x13
                            || "method".equals(context) && target != 1 && target != 0x12 && (target < 0x14 || target > 0x17)
                            || "code".equals(context) && (target < 0x40 || target > 0x4b))
                        throw new IdentityFailure("type annotation target outside attribute context");
                    switch (target) {
                        case 0: case 1: case 0x16: u1(); break;
                        case 0x10: case 0x17: case 0x42: case 0x43: case 0x44: case 0x45: case 0x46: u2(); break;
                        case 0x11: case 0x12: u1(); u1(); break;
                        case 0x13: case 0x14: case 0x15: break;
                        case 0x40: case 0x41:
                            int ranges = u2(); skip(ranges * 6); break;
                        case 0x47: case 0x48: case 0x49: case 0x4a: case 0x4b: u2(); u1(); break;
                        default: throw new IdentityFailure("unsupported type annotation target");
                    }
                    int paths = u1();
                    for (int j = 0; j < paths; j++) {
                        int kind = u1(), argument = u1();
                        if (kind > 3 || kind != 3 && argument != 0) throw new IdentityFailure("invalid type annotation path");
                    }
                    annotation(0);
                }
                return;
            }
            throw new IdentityFailure("allowed attribute has no complete parser: " + name);
        }

        void verificationType() {
            int tag = u1();
            if (tag == 7) require(u2(), 7);
            else if (tag == 8) u2();
            else if (tag > 6) throw new IdentityFailure("unsupported verification type");
        }

        void annotation(int depth) {
            if (depth > 64) throw new IdentityFailure("annotation nesting exceeds supported bound");
            require(u2(), 1); int pairs = u2();
            for (int i = 0; i < pairs; i++) { require(u2(), 1); annotationElement(depth + 1); }
        }

        void annotationElement(int depth) {
            if (depth > 64) throw new IdentityFailure("annotation nesting exceeds supported bound");
            switch (u1()) {
                case 'B': case 'C': case 'I': case 'S': case 'Z': require(u2(), 3); break;
                case 'D': require(u2(), 6); break;
                case 'F': require(u2(), 4); break;
                case 'J': require(u2(), 5); break;
                case 's': case 'c': require(u2(), 1); break;
                case 'e': require(u2(), 1); require(u2(), 1); break;
                case '@': annotation(depth + 1); break;
                case '[':
                    int count = u2(); for (int i = 0; i < count; i++) annotationElement(depth + 1); break;
                default: throw new IdentityFailure("unsupported annotation element tag");
            }
        }

        void optional(int index, int tag) { if (index != 0) require(index, tag); }
        void requireOneOf(int index, int... accepted) {
            if (index > 0 && index < tags.length) for (int tag : accepted) if (tags[index] == tag) return;
            throw new IdentityFailure("constant pool type mismatch");
        }

        boolean allowed(String context, String name) {
            String list;
            if ("code".equals(context)) list = "|StackMapTable|LineNumberTable|LocalVariableTable|LocalVariableTypeTable|RuntimeVisibleTypeAnnotations|RuntimeInvisibleTypeAnnotations|";
            else {
                list = "|Signature|Synthetic|Deprecated|RuntimeVisibleAnnotations|RuntimeInvisibleAnnotations|RuntimeVisibleTypeAnnotations|RuntimeInvisibleTypeAnnotations|";
                if ("class".equals(context)) list += "SourceFile|SourceDebugExtension|InnerClasses|EnclosingMethod|BootstrapMethods|";
                if ("field".equals(context)) list += "ConstantValue|";
                if ("method".equals(context)) list += "Code|Exceptions|RuntimeVisibleParameterAnnotations|RuntimeInvisibleParameterAnnotations|AnnotationDefault|MethodParameters|";
            }
            return list.contains("|" + name + "|");
        }

        Object resolve(int index) {
            if (index <= 0 || index >= tags.length || tags[index] == 0) throw new IdentityFailure("invalid constant pool reference");
            if (resolved[index] != null) return resolved[index];
            if (resolving[index]) throw new IdentityFailure("cyclic constant pool/bootstrap reference");
            resolving[index] = true;
            int tag = tags[index];
            Object value;
            if (tag == 1) value = a("utf8", cp[index]);
            else if (tag == 3) value = a("int", cp[index]);
            else if (tag == 4) value = a("float_bits", cp[index]);
            else if (tag == 5) value = a("long", cp[index]);
            else if (tag == 6) value = a("double_bits", cp[index]);
            else {
                int[] refs = (int[]) cp[index];
                switch (tag) {
                    case 7: case 8: case 16:
                        require(refs[0], 1);
                        value = a(tag == 7 ? "class" : tag == 8 ? "string" : "method_type", cp[refs[0]]);
                        break;
                    case 12:
                        require(refs[0], 1); require(refs[1], 1);
                        value = a("name_type", cp[refs[0]], cp[refs[1]]); break;
                    case 9: case 10: case 11:
                        require(refs[0], 7); require(refs[1], 12);
                        value = a(tag == 9 ? "field" : tag == 10 ? "method" : "interface_method", resolve(refs[0]), resolve(refs[1])); break;
                    case 15:
                        if (refs[0] < 1 || refs[0] > 9) throw new IdentityFailure("invalid method handle kind");
                        if (refs[0] <= 4) require(refs[1], 9);
                        else if (refs[0] == 9) require(refs[1], 11);
                        else if (refs[0] == 5 || refs[0] == 8) require(refs[1], 10);
                        else if (refs[1] <= 0 || refs[1] >= tags.length || (tags[refs[1]] != 10 && tags[refs[1]] != 11))
                            throw new IdentityFailure("invalid method handle reference");
                        value = a("handle", refs[0], resolve(refs[1])); break;
                    case 18:
                        require(refs[1], 12); value = a("invokedynamic", bootstrap(refs[0]), resolve(refs[1])); break;
                    default: throw new IdentityFailure("unhandled constant");
                }
            }
            resolving[index] = false;
            return resolved[index] = value;
        }

        Object bootstrap(int index) {
            if (index < 0 || index >= bootstraps.size()) throw new IdentityFailure("invalid bootstrap reference");
            List<Object> result = new ArrayList<Object>();
            for (int ref : bootstraps.get(index)) result.add(resolve(ref));
            return result;
        }

        Object symbolicPool() {
            List<String> values = new ArrayList<String>();
            for (int i = 1; i < tags.length; i++) {
                if (tags[i] == 0) continue;
                Object resolved = resolve(i);
                if (sessionMask != null && resolved instanceof List<?>) {
                    List<?> entry = (List<?>) resolved;
                    if (entry.size() == 2 && "utf8".equals(entry.get(0))
                            && sessionMask.contains(entry.get(1))) {
                        values.add(json(a("utf8", SESSION_PLACEHOLDER)));
                        continue;
                    }
                }
                values.add(json(resolved));
            }
            Collections.sort(values);
            return values;
        }

        Object symbolicBootstraps() {
            List<String> values = new ArrayList<String>();
            for (int i = 0; i < bootstraps.size(); i++) values.add(json(bootstrap(i)));
            Collections.sort(values);
            return values;
        }

        void require(int index, int tag) {
            if (index <= 0 || index >= tags.length || tags[index] != tag)
                throw new IdentityFailure("constant pool type mismatch");
        }
        void check(int length) { if (length < 0 || p > data.length - length) throw new IdentityFailure("truncated classfile"); }
        int u1() { check(1); return data[p++] & 255; }
        int u2() { return (u1() << 8) | u1(); }
        long u4() { return ((long) u2() << 16) | u2(); }
        void skip(int n) { check(n); p += n; }
    }

    /**
     * Generic tooling CLI: all requested files must succeed or the process fails.
     *
     * <p>{@code --session-bound} emits the ten-field session-bound receipt
     * instead of the five-field exact one. It is what a collector issues an
     * admission certificate from, so it deliberately does NOT relax anything:
     * the exact identity it reports is still the UNMASKED canonical rendering,
     * and a class with no qualified {@code MixinMerged.sessionId} is refused
     * rather than masked into a vacuous invariant.</p>
     */
    public static void main(String[] args) throws Exception {
        boolean dump = false, sessionBound = false;
        int start = 0;
        while (start < args.length && args[start].startsWith("--")) {
            if ("--dump".equals(args[start])) dump = true;
            else if ("--session-bound".equals(args[start])) sessionBound = true;
            else throw new IllegalArgumentException("usage: CanonicalClassIdentityV2 [--dump] [--session-bound] classfile ...");
            start++;
        }
        if (args.length == start) throw new IllegalArgumentException("usage: CanonicalClassIdentityV2 [--dump] [--session-bound] classfile ...");
        List<Result> results = new ArrayList<Result>();
        for (int i = start; i < args.length; i++) {
            byte[] bytes = Files.readAllBytes(Paths.get(args[i]));
            results.add(sessionBound ? identifySessionBound(bytes) : identify(bytes));
        }
        for (Result result : results) {
            System.out.println(dump ? result.canonicalJson
                    : sessionBound ? result.sessionBoundReceiptJson() : result.receiptJson());
        }
    }
}
