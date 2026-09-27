package com.rustcraft.coremod;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The static half of session-bound admission: what MAY be accepted, known before
 * any JVM starts.
 *
 * <p>This type exists to break a circularity. The generated writer plan used to
 * embed a concrete {@link SessionBoundIdentityCertificate}, but that certificate
 * binds a process id, a transformation session id, a loader object identity, a
 * session UUID and a pre-writer byte hash. None of those exist before launch and
 * none of them survive into another process, so the only way to satisfy such a
 * plan was to launch once to mint the certificate and launch again to use it --
 * and the second launch is a different process with a different
 * {@code MixinMerged.sessionId}, so the imported certificate describes a JVM that
 * is not the one asking.</p>
 *
 * <p>The split this enforces:</p>
 *
 * <p><b>STATIC POLICY SAYS WHAT MAY BE ACCEPTED. THE RUNNING JVM RECORDS WHAT
 * ACTUALLY WAS ACCEPTED.</b></p>
 *
 * <p>A policy names only cross-launch facts: the session-INVARIANT identity (the
 * masked rendering, stable across sessions by construction), the exact structural
 * provenance contract, and the runtime/manifest/recipe/loader constraints. It
 * never names one launch's session. The transforming JVM then admits its actual
 * pre-writer buffer against this policy and issues a certificate
 * in-process, which is evidence of an admission that happened rather than
 * permission imported from elsewhere.</p>
 *
 * <p>The prohibitions are enforced, not merely documented: a document carrying a
 * concrete process id, session UUID, pre-writer hash, loader object identity or
 * acquisition evidence hash is refused at parse time, whatever it is called. A
 * static artifact that can hold a per-launch fact can always be made to hold
 * one, and the schema is the only place that can say no.</p>
 */
public final class SessionBoundAdmissionPolicy {

    public static final String SCHEMA = "RUSTCRAFT_SESSION_BOUND_ADMISSION_POLICY";
    public static final int SCHEMA_VERSION = 1;
    public static final String PROVENANCE = "session-bound-admission-policy/v1";

    private static final String SHA256 = "[0-9a-f]{64}";
    /** A concrete per-launch UUID. Its mere presence in a policy is the bug. */
    private static final Pattern CONCRETE_UUID = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    public static final String ANNOTATION_DESCRIPTOR =
            "Lorg/spongepowered/asm/mixin/transformer/meta/MixinMerged;";
    public static final String ANNOTATION_ELEMENT = "sessionId";
    /** The only session value a policy may express: the shape a UUID must have. */
    public static final String UUID_SHAPE =
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    public static final String SCOPE_ANY = "ANY";
    private static final Set<String> LOADER_SCOPES =
            new HashSet<String>(Arrays.asList(SCOPE_ANY, "LAUNCHWRAPPER", "APPLICATION", "PLATFORM"));

    /** Canonical key order. The policy hash is the hash of this exact rendering. */
    private static final String[] KEYS = {
        "schema", "schema_version", "provenance", "identity_mode", "class_name",
        "expected_session_invariant_sha256", "expected_declaration_order_sha256",
        "expected_annotation_descriptor", "expected_annotation_element",
        "expected_masked_locations", "expected_masked_occurrence_count",
        "expected_distinct_masked_uuid_count", "expected_session_uuid_shape",
        "expected_loader_class", "expected_loader_scope", "runtime_profile",
        "runtime_manifest_sha256", "writer_plan_sha256", "recipe_sha256",
        "required_hook_ids",
    };

    /**
     * Fields that would reintroduce the circularity. Kept as an explicit deny
     * list in addition to the key allowlist so that the reason survives any
     * future relaxation of the allowlist: a policy that names one launch's
     * process, session, loader or acquisition evidence is the defect itself.
     */
    private static final Set<String> FORBIDDEN_KEYS = new HashSet<String>(Arrays.asList(
            "process_id", "transformation_session_id", "session_uuid", "expected_session_uuid",
            "defining_loader_identity", "loader_identity", "pre_writer_raw_sha256", "raw_sha256",
            "exact_semantic_sha256", "acquisition_evidence_sha256", "acquisition_evidence_id",
            "certificate", "certificate_sha256", "session_certificates"));

    public final String className;
    public final String expectedSessionInvariantSha256;
    public final String expectedDeclarationOrderSha256;
    public final String expectedAnnotationDescriptor;
    public final String expectedAnnotationElement;
    public final List<String> expectedMaskedLocations;
    public final int expectedMaskedOccurrenceCount;
    public final int expectedDistinctMaskedUuidCount;
    public final String expectedSessionUuidShape;
    public final String expectedLoaderClass;
    public final String expectedLoaderScope;
    public final String runtimeProfile;
    public final String runtimeManifestSha256;
    public final String writerPlanSha256;
    public final String recipeSha256;
    public final List<String> requiredHookIds;

    /**
     * Fields whose presence depends on what the plan publishes. They are the
     * bindings to a plan revision, not statements about the class.
     */
    private static final java.util.Set<String> CONDITIONAL_KEYS =
            java.util.Collections.unmodifiableSet(new java.util.HashSet<String>(
                    java.util.Arrays.asList("runtime_manifest_sha256", "writer_plan_sha256",
                            "recipe_sha256")));

    private SessionBoundAdmissionPolicy(Map<String, Object> fields) {
        className = text(fields, "class_name", null);
        expectedSessionInvariantSha256 = text(fields, "expected_session_invariant_sha256", SHA256);
        expectedDeclarationOrderSha256 = text(fields, "expected_declaration_order_sha256", SHA256);
        expectedAnnotationDescriptor = text(fields, "expected_annotation_descriptor", null);
        expectedAnnotationElement = text(fields, "expected_annotation_element", null);
        expectedMaskedLocations = textList(fields, "expected_masked_locations");
        expectedMaskedOccurrenceCount = count(fields, "expected_masked_occurrence_count");
        expectedDistinctMaskedUuidCount = count(fields, "expected_distinct_masked_uuid_count");
        expectedSessionUuidShape = text(fields, "expected_session_uuid_shape", null);
        expectedLoaderClass = text(fields, "expected_loader_class", null);
        expectedLoaderScope = text(fields, "expected_loader_scope", null);
        runtimeProfile = text(fields, "runtime_profile", null);
        runtimeManifestSha256 = optionalText(fields, "runtime_manifest_sha256", SHA256);
        writerPlanSha256 = optionalText(fields, "writer_plan_sha256", SHA256);
        recipeSha256 = optionalText(fields, "recipe_sha256", SHA256);
        requiredHookIds = textList(fields, "required_hook_ids");
    }

    // ------------------------------------------------------------------- parse

    /**
     * Strict parse. A policy that carries a concrete per-launch fact is refused
     * as REFUSE, not INCOMPLETE: the document is not missing evidence, it is
     * claiming authority it cannot have.
     */
    public static SessionBoundAdmissionPolicy parse(String json) {
        if (json == null)
            throw new Refusal(Refusal.Kind.INCOMPLETE, "NO_POLICY", "no session admission policy present");
        Map<String, Object> fields;
        try {
            fields = Json.object(json);
        } catch (RuntimeException malformed) {
            throw new Refusal(Refusal.Kind.REFUSE, "MALFORMED_POLICY",
                    "policy is not readable JSON: " + malformed.getMessage());
        }
        for (String key : fields.keySet()) {
            if (FORBIDDEN_KEYS.contains(key))
                throw new Refusal(Refusal.Kind.REFUSE, "POLICY_CARRIES_RUNTIME_FACT",
                        "a static policy must not carry " + key
                                + ": that is a fact about one running process");
            if (CONCRETE_UUID.matcher(key).matches())
                throw new Refusal(Refusal.Kind.REFUSE, "POLICY_CARRIES_SESSION_UUID",
                        "policy field name " + key + " is a concrete session UUID");
            if (!known(key))
                throw new Refusal(Refusal.Kind.REFUSE, "UNKNOWN_POLICY_FIELD",
                        "unknown policy field " + key);
            rejectConcreteUuid(fields.get(key), key);
        }
        for (String key : KEYS) {
            if (key.equals("schema") || key.equals("schema_version") || key.equals("provenance")) continue;
            if (CONDITIONAL_KEYS.contains(key)) continue;
            if (!fields.containsKey(key))
                throw new Refusal(Refusal.Kind.INCOMPLETE, "INCOMPLETE_POLICY",
                        "policy is missing " + key);
        }
        if (!SCHEMA.equals(fields.get("schema")))
            throw new Refusal(Refusal.Kind.REFUSE, "FOREIGN_POLICY_SCHEMA", "unexpected policy schema");
        if (!Integer.valueOf(SCHEMA_VERSION).equals(fields.get("schema_version")))
            throw new Refusal(Refusal.Kind.REFUSE, "UNSUPPORTED_POLICY_VERSION",
                    "unsupported policy version");
        if (!PROVENANCE.equals(fields.get("provenance")))
            throw new Refusal(Refusal.Kind.REFUSE, "FOREIGN_POLICY_PROVENANCE",
                    "unexpected policy provenance");
        if (!CanonicalClassIdentityV2.SCHEMA_SESSION_BOUND.equals(fields.get("identity_mode")))
            throw new Refusal(Refusal.Kind.REFUSE, "POLICY_IDENTITY_MODE",
                    "a policy authorizes session-bound admission and nothing else");
        SessionBoundAdmissionPolicy policy = new SessionBoundAdmissionPolicy(fields);
        if (!ANNOTATION_DESCRIPTOR.equals(policy.expectedAnnotationDescriptor))
            throw new Refusal(Refusal.Kind.REFUSE, "FOREIGN_ANNOTATION_DESCRIPTOR",
                    "unexpected qualified annotation descriptor");
        if (!ANNOTATION_ELEMENT.equals(policy.expectedAnnotationElement))
            throw new Refusal(Refusal.Kind.REFUSE, "FOREIGN_ANNOTATION_ELEMENT",
                    "unexpected qualified annotation element");
        if (!UUID_SHAPE.equals(policy.expectedSessionUuidShape))
            throw new Refusal(Refusal.Kind.REFUSE, "FOREIGN_UUID_SHAPE",
                    "session UUID shape must be the canonical lowercase pattern");
        if (!LOADER_SCOPES.contains(policy.expectedLoaderScope))
            throw new Refusal(Refusal.Kind.REFUSE, "UNKNOWN_LOADER_SCOPE",
                    "unknown loader scope " + policy.expectedLoaderScope);
        if (policy.expectedDistinctMaskedUuidCount != 1)
            throw new Refusal(Refusal.Kind.REFUSE, "POLICY_DISTINCT_UUIDS",
                    "a policy expects exactly one distinct session UUID, found "
                            + policy.expectedDistinctMaskedUuidCount);
        if (policy.expectedMaskedOccurrenceCount < policy.expectedDistinctMaskedUuidCount)
            throw new Refusal(Refusal.Kind.REFUSE, "IMPOSSIBLE_OCCURRENCE_COUNT",
                    "expected occurrence count below distinct count");
        for (String location : policy.expectedMaskedLocations) {
            if (location.indexOf(ANNOTATION_DESCRIPTOR) < 0 || location.indexOf(ANNOTATION_ELEMENT) < 0)
                throw new Refusal(Refusal.Kind.REFUSE, "UNQUALIFIED_MASKED_LOCATION",
                        "masked location does not name the qualified annotation: " + location);
        }
        if (policy.expectedSessionInvariantSha256.equals(policy.expectedDeclarationOrderSha256))
            throw new Refusal(Refusal.Kind.REFUSE, "POLICY_PROJECTS_NOTHING",
                    "session invariant equals the declaration-order identity, so the policy "
                            + "projects away nothing and would admit a process-bound value");
        return policy;
    }

    public String policySha256() {
        return sha256(toJson().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The session-invariant identity of the bytes this policy governs, computed
     * here rather than trusted. Used by the controls to show that two launches
     * with different session UUIDs land on the same invariant -- the property
     * that makes a session-INVARIANT policy the right static artifact at all.
     */
    public String sessionInvariantSha256Of(byte[] preWriterBytes) {
        try {
            return CanonicalClassIdentityV2.identifySessionBound(preWriterBytes).sessionInvariantSha256;
        } catch (CanonicalClassIdentityV2.IdentityFailure malformed) {
            throw new Refusal(Refusal.Kind.INCOMPLETE, "NO_SESSION_PROJECTION",
                    "no session projection for these bytes: " + malformed.getMessage());
        }
    }

    /** Canonical JSON rendering, byte-identical to the offline implementation. */
    public String toJson() {
        StringBuilder text = new StringBuilder();
        text.append('{');
        boolean first = true;
        for (int i = 0; i < KEYS.length; i++) {
            String key = KEYS[i];
            if (CONDITIONAL_KEYS.contains(key) && field(key) == null) continue;
            if (!first) text.append(',');
            first = false;
            appendKey(text, key);
            if (key.equals("expected_masked_locations") || key.equals("required_hook_ids")) {
                List<String> values = key.equals("expected_masked_locations")
                        ? expectedMaskedLocations : requiredHookIds;
                text.append('[');
                for (int j = 0; j < values.size(); j++) {
                    if (j > 0) text.append(',');
                    appendString(text, values.get(j));
                }
                text.append(']');
            } else if (key.equals("expected_masked_occurrence_count")) {
                text.append(expectedMaskedOccurrenceCount);
            } else if (key.equals("expected_distinct_masked_uuid_count")) {
                text.append(expectedDistinctMaskedUuidCount);
            } else if (key.equals("schema")) {
                appendString(text, SCHEMA);
            } else if (key.equals("schema_version")) {
                text.append(SCHEMA_VERSION);
            } else if (key.equals("provenance")) {
                appendString(text, PROVENANCE);
            } else if (key.equals("identity_mode")) {
                appendString(text, CanonicalClassIdentityV2.SCHEMA_SESSION_BOUND);
            } else {
                appendString(text, field(key));
            }
        }
        text.append('}');
        return text.toString();
    }

    // --------------------------------------------------------------- admission

    /**
     * One pre-writer buffer, observed in the transforming JVM, offered for
     * admission. Everything here is a fact about the bytes this process is
     * actually holding right now.
     */
    public static final class Candidate {
        public final String className;
        public final String sessionInvariantSha256;
        public final String declarationOrderSha256;
        public final String exactSemanticSha256;
        public final List<String> maskedLocations;
        public final int maskedOccurrenceCount;
        public final int distinctMaskedUuidCount;
        public final String loaderClass;
        public final String runtimeProfile;
        public final String recipeSha256;
        public final String manifestSha256;

        public Candidate(String className, CanonicalClassIdentityV2.Result identity,
                String loaderClass, String runtimeProfile, String recipeSha256,
                String manifestSha256) {
            if (identity == null || identity.sessionInvariantSha256 == null)
                throw new Refusal(Refusal.Kind.INCOMPLETE, "NO_SESSION_PROJECTION",
                        "no session projection was established for " + className);
            this.className = className;
            this.sessionInvariantSha256 = identity.sessionInvariantSha256;
            this.declarationOrderSha256 = identity.declarationOrderSha256;
            this.exactSemanticSha256 = identity.semanticSha256;
            this.maskedLocations = Collections.unmodifiableList(
                    new ArrayList<String>(identity.maskedLocations));
            this.maskedOccurrenceCount = identity.maskedOccurrenceCount;
            this.distinctMaskedUuidCount = identity.maskedValues.size();
            this.loaderClass = loaderClass;
            this.runtimeProfile = runtimeProfile;
            this.recipeSha256 = recipeSha256;
            this.manifestSha256 = manifestSha256;
        }
    }

    /**
     * Admits one observed pre-writer buffer against this policy.
     *
     * <p>Note what is absent: there is no process id, no transformation session
     * id and no expected session UUID to compare against, because a static policy
     * cannot hold any of them and a previous launch's values would be false here.
     * A different session UUID on this launch is therefore not a mismatch -- the
     * session-invariant identity is what must match, and that is exactly the
     * claim the policy is able to make.</p>
     *
     * @return the reasons the candidate was refused; empty means admitted.
     */
    public List<String> refuses(Candidate candidate) {
        List<String> refusals = new ArrayList<String>();
        if (candidate.distinctMaskedUuidCount != 1)
            refusals.add("DISTINCT_SESSION_UUIDS: observed " + candidate.distinctMaskedUuidCount
                    + " distinct qualified session UUIDs, policy expects 1");
        if (!candidate.sessionInvariantSha256.equals(expectedSessionInvariantSha256))
            refusals.add("INVARIANT_MISMATCH: observed " + candidate.sessionInvariantSha256
                    + ", policy expects " + expectedSessionInvariantSha256);
        if (!candidate.declarationOrderSha256.equals(expectedDeclarationOrderSha256))
            refusals.add("DECLARATION_ORDER_MISMATCH: observed " + candidate.declarationOrderSha256
                    + ", policy expects " + expectedDeclarationOrderSha256);
        List<String> observed = new ArrayList<String>(candidate.maskedLocations);
        Collections.sort(observed);
        List<String> expected = new ArrayList<String>(expectedMaskedLocations);
        Collections.sort(expected);
        if (!observed.equals(expected))
            refusals.add("MASKED_LOCATION_MISMATCH: observed " + observed + ", policy expects " + expected);
        if (candidate.maskedOccurrenceCount != expectedMaskedOccurrenceCount)
            refusals.add("MASKED_OCCURRENCE_MISMATCH: observed " + candidate.maskedOccurrenceCount
                    + ", policy expects " + expectedMaskedOccurrenceCount);
        if (candidate.loaderClass == null || !candidate.loaderClass.equals(expectedLoaderClass))
            refusals.add("LOADER_CLASS_MISMATCH: observed " + candidate.loaderClass
                    + ", policy expects " + expectedLoaderClass);
        if (candidate.runtimeProfile == null || !candidate.runtimeProfile.equals(runtimeProfile))
            refusals.add("RUNTIME_PROFILE_MISMATCH: observed " + candidate.runtimeProfile
                    + ", policy expects " + runtimeProfile);
        refusals.addAll(bindingRefusal("RECIPE", candidate.recipeSha256, recipeSha256));
        refusals.addAll(bindingRefusal("MANIFEST", candidate.manifestSha256, runtimeManifestSha256));
        if (!className.equals(candidate.className))
            refusals.add("CLASS_MISMATCH: observed " + candidate.className
                    + ", policy expects " + className);
        return Collections.unmodifiableList(refusals);
    }

    /**
     * Compares a plan-published binding with what the policy claims for it.
     * A plan that publishes no binding cannot be satisfied by a policy that
     * invents one, and a plan that publishes a binding is not satisfied by a
     * policy that omits it.
     */
    private static List<String> bindingRefusal(String what, String observed, String claimed) {
        if (observed == null || claimed == null)
            return observed == null && claimed == null
                    ? Collections.<String>emptyList()
                    : Collections.singletonList(what + "_BINDING_MISMATCH: plan publishes "
                            + observed + ", policy claims " + claimed);
        return observed.equals(claimed) ? Collections.<String>emptyList()
                : Collections.singletonList(what + "_BINDING_MISMATCH: observed " + observed
                        + ", policy expects " + claimed);
    }

    // -------------------------------------------------------------- internals

    public static final class Refusal extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        public enum Kind { INCOMPLETE, REFUSE }

        public final Kind kind;
        public final String reason;

        public Refusal(Kind kind, String reason, String detail) {
            super("SESSION_BOUND_POLICY_" + kind + " [" + reason + "]: " + detail);
            this.kind = kind;
            this.reason = reason;
        }
    }

    private static void rejectConcreteUuid(Object value, String key) {
        if (value instanceof String && CONCRETE_UUID.matcher((String) value).matches())
            throw new Refusal(Refusal.Kind.REFUSE, "POLICY_CARRIES_SESSION_UUID",
                    "policy field " + key + " is a concrete session UUID; a policy may state "
                            + "only the session-invariant identity and the shape a UUID must have");
        if (value instanceof List) {
            for (Object element : (List<?>) value) {
                if (element instanceof String && CONCRETE_UUID.matcher((String) element).matches())
                    throw new Refusal(Refusal.Kind.REFUSE, "POLICY_CARRIES_SESSION_UUID",
                            "policy field " + key + " contains a concrete session UUID");
            }
        }
    }

    private static boolean known(String key) {
        for (String candidate : KEYS) if (candidate.equals(key)) return true;
        return false;
    }

    private String field(String key) {
        if ("class_name".equals(key)) return className;
        if ("expected_session_invariant_sha256".equals(key)) return expectedSessionInvariantSha256;
        if ("expected_declaration_order_sha256".equals(key)) return expectedDeclarationOrderSha256;
        if ("expected_annotation_descriptor".equals(key)) return expectedAnnotationDescriptor;
        if ("expected_annotation_element".equals(key)) return expectedAnnotationElement;
        if ("expected_session_uuid_shape".equals(key)) return expectedSessionUuidShape;
        if ("expected_loader_class".equals(key)) return expectedLoaderClass;
        if ("expected_loader_scope".equals(key)) return expectedLoaderScope;
        if ("runtime_profile".equals(key)) return runtimeProfile;
        if ("runtime_manifest_sha256".equals(key)) return runtimeManifestSha256;
        if ("writer_plan_sha256".equals(key)) return writerPlanSha256;
        if ("recipe_sha256".equals(key)) return recipeSha256;
        throw new IllegalStateException("unmapped policy field " + key);
    }

    /**
     * A field that MAY be absent: a plan that publishes no recipe binding cannot
     * be named by a policy. A field that is present is still held to its shape,
     * so "optional" never becomes "unchecked".
     */
    private static String optionalText(Map<String, Object> fields, String key, String pattern) {
        if (!fields.containsKey(key)) return null;
        return text(fields, key, pattern);
    }

    private static String text(Map<String, Object> fields, String key, String pattern) {
        Object value = fields.get(key);
        if (!(value instanceof String) || ((String) value).length() == 0)
            throw new Refusal(Refusal.Kind.REFUSE, "INVALID_POLICY_FIELD",
                    key + " must be a non-empty string");
        String text = (String) value;
        if (pattern != null && !text.matches(pattern))
            throw new Refusal(Refusal.Kind.REFUSE, "INVALID_POLICY_FIELD",
                    key + " is malformed: " + text);
        return text;
    }

    private static int count(Map<String, Object> fields, String key) {
        Object value = fields.get(key);
        if (!(value instanceof Integer) || ((Integer) value).intValue() < 1)
            throw new Refusal(Refusal.Kind.REFUSE, "INVALID_POLICY_FIELD",
                    key + " must be a positive integer");
        return ((Integer) value).intValue();
    }

    @SuppressWarnings("unchecked")
    private static List<String> textList(Map<String, Object> fields, String key) {
        Object value = fields.get(key);
        if (!(value instanceof List) || ((List<?>) value).isEmpty())
            throw new Refusal(Refusal.Kind.REFUSE, "INVALID_POLICY_FIELD",
                    key + " must be a non-empty string array");
        List<String> result = new ArrayList<String>();
        for (Object element : (List<Object>) value) {
            if (!(element instanceof String) || ((String) element).length() == 0)
                throw new Refusal(Refusal.Kind.REFUSE, "INVALID_POLICY_FIELD",
                        key + " must contain strings");
            result.add((String) element);
        }
        List<String> sorted = new ArrayList<String>(result);
        Collections.sort(sorted);
        if (!sorted.equals(result))
            throw new Refusal(Refusal.Kind.REFUSE, "UNORDERED_POLICY_LIST", key + " must be sorted");
        if (key.equals("required_hook_ids") && new HashSet<String>(result).size() != result.size())
            throw new Refusal(Refusal.Kind.REFUSE, "DUPLICATE_POLICY_LIST",
                    key + " must be unique");
        return Collections.unmodifiableList(result);
    }

    public static String sha256(byte[] bytes) {
        try {
            StringBuilder result = new StringBuilder();
            for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) {
                result.append(String.format("%02x", value & 255));
            }
            return result.toString();
        } catch (Exception failure) {
            throw new Refusal(Refusal.Kind.REFUSE, "DIGEST_UNAVAILABLE", "sha256 unavailable: " + failure);
        }
    }

    private static void appendKey(StringBuilder text, String key) {
        appendString(text, key);
        text.append(':');
    }

    private static void appendString(StringBuilder text, String value) {
        text.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\') text.append('\\').append(c);
            else if (c < 0x20) text.append(String.format("\\u%04x", (int) c));
            else text.append(c);
        }
        text.append('"');
    }

    /** Minimal strict JSON reader, shared with the certificate's own parser. */
    private static final class Json {
        private final String text;
        private int at;

        private Json(String text) { this.text = text; }

        static Map<String, Object> object(String text) {
            Json json = new Json(text);
            json.skipSpace();
            Map<String, Object> result = json.readObject();
            json.skipSpace();
            if (json.at != text.length())
                throw new IllegalArgumentException("trailing content at offset " + json.at);
            return result;
        }

        private Map<String, Object> readObject() {
            expect('{');
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            skipSpace();
            if (peek() == '}') { at++; return result; }
            while (true) {
                skipSpace();
                String key = readString();
                if (result.containsKey(key))
                    throw new IllegalArgumentException("duplicate key " + key);
                skipSpace();
                expect(':');
                skipSpace();
                char c = peek();
                if (c == '"') result.put(key, readString());
                else if (c == '[') result.put(key, readArray());
                else if (c == '-' || (c >= '0' && c <= '9')) result.put(key, Integer.valueOf(readNumber()));
                else throw new IllegalArgumentException("unsupported value for key " + key);
                skipSpace();
                char next = next();
                if (next == '}') return result;
                if (next != ',') throw new IllegalArgumentException("expected , or } at offset " + (at - 1));
            }
        }

        private List<Object> readArray() {
            expect('[');
            List<Object> result = new ArrayList<Object>();
            skipSpace();
            if (peek() == ']') { at++; return result; }
            while (true) {
                skipSpace();
                result.add(readString());
                skipSpace();
                char next = next();
                if (next == ']') return result;
                if (next != ',') throw new IllegalArgumentException("expected , or ] at offset " + (at - 1));
            }
        }

        private int readNumber() {
            int start = at;
            if (peek() == '-') at++;
            while (at < text.length() && Character.isDigit(text.charAt(at))) at++;
            try {
                return Integer.parseInt(text.substring(start, at));
            } catch (NumberFormatException invalid) {
                throw new IllegalArgumentException("malformed number at offset " + start);
            }
        }

        private String readString() {
            expect('"');
            StringBuilder value = new StringBuilder();
            while (true) {
                char c = next();
                if (c == '"') return value.toString();
                if (c == '\\') {
                    char escaped = next();
                    if (escaped == 'u') {
                        value.append((char) Integer.parseInt(text.substring(at, at + 4), 16));
                        at += 4;
                    } else if (escaped == '"' || escaped == '\\' || escaped == '/') value.append(escaped);
                    else throw new IllegalArgumentException("bad escape at offset " + (at - 1));
                } else value.append(c);
            }
        }

        private void expect(char expected) {
            if (next() != expected)
                throw new IllegalArgumentException("expected " + expected + " at offset " + (at - 1));
        }

        private char peek() {
            if (at >= text.length()) throw new IllegalArgumentException("unexpected end of policy");
            return text.charAt(at);
        }

        private char next() {
            char c = peek();
            at++;
            return c;
        }

        private void skipSpace() {
            while (at < text.length() && Character.isWhitespace(text.charAt(at))) at++;
        }
    }
}
