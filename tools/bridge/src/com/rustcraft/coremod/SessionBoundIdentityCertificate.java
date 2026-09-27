package com.rustcraft.coremod;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Evidence-bound authorization for {@link CanonicalClassIdentityV2#SCHEMA_SESSION_BOUND}
 * class identity.
 *
 * <p>The governing rule this type exists to enforce:</p>
 *
 * <p><b>The class STRUCTURE determines WHAT may be normalized. This certificate
 * determines WHETHER normalization is authorized in this specific
 * process/transformation session. Structure alone never authorizes masking.</b>
 * A class that merely contains a {@code MixinMerged.sessionId} element is not
 * session-bound-qualified: without a matching certificate the correct outcome is
 * {@link Refusal.Kind#INCOMPLETE}, never a silent fallback to session-bound
 * masking and never a downgrade to an exact-only or raw identity.</p>
 *
 * <p>Certificates are issued offline by the generic acquisition/qualification
 * tooling from a recorded same-process acquisition record, are embedded verbatim
 * in the generated writer plan, and are re-verified inside the transforming JVM
 * against freshly observed runtime state. Every field below is bound and
 * compared; a single mismatch refuses the class load.</p>
 */
public final class SessionBoundIdentityCertificate {

    public static final String SCHEMA = "RUSTCRAFT_SESSION_BOUND_IDENTITY_CERTIFICATE";
    public static final int SCHEMA_VERSION = 1;
    public static final String PROVENANCE = "session-bound-identity-certificate/v1";

    private static final String SHA256 = "[0-9a-f]{64}";
    private static final String UUID = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    /** Canonical key order. The certificate hash is the hash of this exact rendering. */
    private static final String[] KEYS = {
        "schema", "schema_version", "provenance",
        "process_id", "transformation_session_id", "defining_loader_identity", "class_name",
        "pre_writer_raw_sha256", "exact_semantic_sha256", "exact_declaration_order_sha256",
        "session_invariant_sha256", "expected_session_uuid",
        "masked_annotation_locations", "distinct_masked_uuid_count", "masked_occurrence_count",
        "recipe_sha256", "runtime_manifest_sha256", "acquisition_evidence_sha256",
    };

    public final String processId;
    public final String transformationSessionId;
    public final String definingLoaderIdentity;
    public final String className;
    public final String preWriterRawSha256;
    public final String exactSemanticSha256;
    public final String exactDeclarationOrderSha256;
    public final String sessionInvariantSha256;
    public final String expectedSessionUuid;
    /** Sorted, immutable, exact annotation provenances that were masked. */
    public final List<String> maskedAnnotationLocations;
    public final int distinctMaskedUuidCount;
    public final int maskedOccurrenceCount;
    public final String recipeSha256;
    public final String runtimeManifestSha256;
    public final String acquisitionEvidenceSha256;

    private SessionBoundIdentityCertificate(Map<String, Object> fields) {
        processId = text(fields, "process_id", UUID);
        transformationSessionId = text(fields, "transformation_session_id", UUID);
        definingLoaderIdentity = text(fields, "defining_loader_identity", null);
        className = text(fields, "class_name", null);
        preWriterRawSha256 = text(fields, "pre_writer_raw_sha256", SHA256);
        exactSemanticSha256 = text(fields, "exact_semantic_sha256", SHA256);
        exactDeclarationOrderSha256 = text(fields, "exact_declaration_order_sha256", SHA256);
        sessionInvariantSha256 = text(fields, "session_invariant_sha256", SHA256);
        expectedSessionUuid = text(fields, "expected_session_uuid", UUID);
        maskedAnnotationLocations = textList(fields, "masked_annotation_locations");
        distinctMaskedUuidCount = count(fields, "distinct_masked_uuid_count");
        maskedOccurrenceCount = count(fields, "masked_occurrence_count");
        recipeSha256 = text(fields, "recipe_sha256", SHA256);
        runtimeManifestSha256 = text(fields, "runtime_manifest_sha256", SHA256);
        acquisitionEvidenceSha256 = text(fields, "acquisition_evidence_sha256", SHA256);
    }

    // ---------------------------------------------------------------- issuance

    /**
     * Issues a certificate from an observed same-process acquisition record.
     * The structural projection is supplied by the identity engine; the session
     * UUID, loader identity, process/session identity and artifact hashes are
     * supplied by the acquisition tooling. Nothing here is inferred from class
     * contents alone.
     */
    public static SessionBoundIdentityCertificate issue(String processId,
            String transformationSessionId, String definingLoaderIdentity,
            byte[] preWriterBytes, CanonicalClassIdentityV2.Result identity,
            String recipeSha256, String runtimeManifestSha256,
            String acquisitionEvidenceSha256) {
        if (preWriterBytes == null) throw new Refusal(Refusal.Kind.REFUSE, "NO_PRE_WRITER_BYTES", "missing pre-writer bytes");
        if (identity == null || identity.sessionInvariantSha256 == null)
            throw new Refusal(Refusal.Kind.INCOMPLETE, "NO_SESSION_PROJECTION",
                    "a session certificate may only be issued from a session-bound projection");
        if (identity.maskedValues.isEmpty())
            throw new Refusal(Refusal.Kind.REFUSE, "NO_QUALIFIED_PROVENANCE",
                    "no qualified sessionId provenance was recorded");
        // Two different qualified session UUIDs in one class/session is never
        // certifiable. No exception is minted for it here.
        if (identity.maskedValues.size() != 1)
            throw new Refusal(Refusal.Kind.REFUSE, "MULTIPLE_SESSION_UUIDS",
                    "cannot certify " + identity.maskedValues.size()
                            + " distinct qualified session UUIDs: " + identity.maskedValues);
        Map<String, Object> fields = new LinkedHashMap<String, Object>();
        fields.put("process_id", processId);
        fields.put("transformation_session_id", transformationSessionId);
        fields.put("defining_loader_identity", definingLoaderIdentity);
        fields.put("class_name", identity.className);
        fields.put("pre_writer_raw_sha256", sha256(preWriterBytes));
        fields.put("exact_semantic_sha256", identity.semanticSha256);
        fields.put("exact_declaration_order_sha256", identity.declarationOrderSha256);
        fields.put("session_invariant_sha256", identity.sessionInvariantSha256);
        fields.put("expected_session_uuid", identity.maskedValues.get(0));
        fields.put("masked_annotation_locations", new ArrayList<String>(identity.maskedLocations));
        fields.put("distinct_masked_uuid_count", Integer.valueOf(identity.maskedValues.size()));
        fields.put("masked_occurrence_count", Integer.valueOf(identity.maskedOccurrenceCount));
        fields.put("recipe_sha256", recipeSha256);
        fields.put("runtime_manifest_sha256", runtimeManifestSha256);
        fields.put("acquisition_evidence_sha256", acquisitionEvidenceSha256);
        return new SessionBoundIdentityCertificate(fields);
    }

    // ------------------------------------------------------------ serialization

    /** Canonical JSON rendering; {@link #certificateSha256()} hashes these bytes. */
    public String toJson() {
        StringBuilder text = new StringBuilder();
        text.append('{');
        for (int i = 0; i < KEYS.length; i++) {
            if (i > 0) text.append(',');
            appendKey(text, KEYS[i]);
            if (KEYS[i].equals("masked_annotation_locations")) {
                text.append('[');
                for (int j = 0; j < maskedAnnotationLocations.size(); j++) {
                    if (j > 0) text.append(',');
                    appendString(text, maskedAnnotationLocations.get(j));
                }
                text.append(']');
            } else if (KEYS[i].equals("distinct_masked_uuid_count")) {
                text.append(distinctMaskedUuidCount);
            } else if (KEYS[i].equals("masked_occurrence_count")) {
                text.append(maskedOccurrenceCount);
            } else {
                if (KEYS[i].equals("schema")) appendString(text, SCHEMA);
                else if (KEYS[i].equals("schema_version")) text.append(SCHEMA_VERSION);
                else if (KEYS[i].equals("provenance")) appendString(text, PROVENANCE);
                else appendString(text, field(KEYS[i]));
            }
        }
        text.append('}');
        return text.toString();
    }

    /**
     * Strict parse. Unknown keys, missing keys, duplicate keys, wrong JSON types
     * and malformed values are all refused: a certificate that cannot be read
     * exactly is not a certificate.
     */
    public static SessionBoundIdentityCertificate parse(String json) {
        if (json == null) throw new Refusal(Refusal.Kind.INCOMPLETE, "NO_CERTIFICATE", "no session certificate present");
        Map<String, Object> fields;
        try {
            fields = Json.object(json);
        } catch (RuntimeException malformed) {
            throw new Refusal(Refusal.Kind.REFUSE, "MALFORMED_CERTIFICATE", "certificate is not readable JSON: " + malformed.getMessage());
        }
        for (String key : fields.keySet()) {
            if (!known(key)) throw new Refusal(Refusal.Kind.REFUSE, "UNKNOWN_CERTIFICATE_FIELD", "unknown certificate field " + key);
        }
        for (String key : KEYS) {
            if (key.startsWith("schema") || key.equals("provenance")) continue;
            if (!fields.containsKey(key))
                throw new Refusal(Refusal.Kind.INCOMPLETE, "INCOMPLETE_CERTIFICATE", "certificate is missing " + key);
        }
        if (!SCHEMA.equals(fields.get("schema")))
            throw new Refusal(Refusal.Kind.REFUSE, "FOREIGN_CERTIFICATE_SCHEMA", "unexpected certificate schema");
        if (!Integer.valueOf(SCHEMA_VERSION).equals(fields.get("schema_version")))
            throw new Refusal(Refusal.Kind.REFUSE, "UNSUPPORTED_CERTIFICATE_VERSION", "unsupported certificate version");
        if (!PROVENANCE.equals(fields.get("provenance")))
            throw new Refusal(Refusal.Kind.REFUSE, "FOREIGN_CERTIFICATE_PROVENANCE", "unexpected certificate provenance");
        SessionBoundIdentityCertificate certificate = new SessionBoundIdentityCertificate(fields);
        List<String> sorted = new ArrayList<String>(certificate.maskedAnnotationLocations);
        Collections.sort(sorted);
        if (!sorted.equals(certificate.maskedAnnotationLocations))
            throw new Refusal(Refusal.Kind.REFUSE, "UNORDERED_MASKED_LOCATIONS", "masked locations must be sorted");
        if (certificate.distinctMaskedUuidCount != 1)
            throw new Refusal(Refusal.Kind.REFUSE, "MULTIPLE_SESSION_UUIDS",
                    "a certificate must bind exactly one session UUID, found " + certificate.distinctMaskedUuidCount);
        if (certificate.maskedOccurrenceCount < certificate.distinctMaskedUuidCount)
            throw new Refusal(Refusal.Kind.REFUSE, "IMPOSSIBLE_OCCURRENCE_COUNT", "occurrence count below distinct count");
        return certificate;
    }

    public String certificateSha256() {
        return sha256(toJson().getBytes(StandardCharsets.UTF_8));
    }

    // ----------------------------------------------------------- verification

    /** Runtime state observed in the transforming JVM, for verification only. */
    public static final class Observation {
        public final String processId;
        public final String transformationSessionId;
        public final String definingLoaderIdentity;
        public final byte[] preWriterBytes;
        public final CanonicalClassIdentityV2.Result identity;

        public Observation(String processId, String transformationSessionId,
                String definingLoaderIdentity, byte[] preWriterBytes,
                CanonicalClassIdentityV2.Result identity) {
            this.processId = processId;
            this.transformationSessionId = transformationSessionId;
            this.definingLoaderIdentity = definingLoaderIdentity;
            this.preWriterBytes = preWriterBytes;
            this.identity = identity;
        }
    }

    /**
     * Authorizes session-bound identity for one class in one process/transformation
     * session. Every rule below refuses rather than downgrades; there is no
     * tolerance, no "close enough" match and no fallback identity mode.
     */
    public void authorize(Observation observation) {
        if (observation == null || observation.identity == null)
            throw new Refusal(Refusal.Kind.INCOMPLETE, "NO_OBSERVATION", "no runtime observation to verify");
        CanonicalClassIdentityV2.Result observed = observation.identity;
        if (observed.sessionInvariantSha256 == null)
            throw new Refusal(Refusal.Kind.REFUSE, "EXACT_IDENTITY_ONLY",
                    "observation carries no session-bound projection; exact identity cannot be masked after the fact");
        // Two different qualified session UUIDs in one qualified class/session.
        if (observed.maskedValues.size() != 1)
            throw new Refusal(Refusal.Kind.REFUSE, "MULTIPLE_SESSION_UUIDS",
                    "observed " + observed.maskedValues.size() + " distinct qualified session UUIDs: " + observed.maskedValues);
        if (observation.preWriterBytes == null)
            throw new Refusal(Refusal.Kind.INCOMPLETE, "NO_PRE_WRITER_BYTES", "no pre-writer bytes observed");
        if (!processId.equals(observation.processId))
            throw new Refusal(Refusal.Kind.REFUSE, "PROCESS_MISMATCH",
                    "certificate issued for process " + processId + ", observed " + observation.processId);
        if (!transformationSessionId.equals(observation.transformationSessionId))
            throw new Refusal(Refusal.Kind.REFUSE, "SESSION_MISMATCH",
                    "certificate issued for transformation session " + transformationSessionId
                            + ", observed " + observation.transformationSessionId);
        if (!definingLoaderIdentity.equals(observation.definingLoaderIdentity))
            throw new Refusal(Refusal.Kind.REFUSE, "LOADER_MISMATCH",
                    "certificate issued for defining loader " + definingLoaderIdentity
                            + ", observed " + observation.definingLoaderIdentity);
        if (!className.equals(observed.className))
            throw new Refusal(Refusal.Kind.REFUSE, "CLASS_MISMATCH",
                    "certificate bound to " + className + ", observed " + observed.className);
        String observedRaw = sha256(observation.preWriterBytes);
        if (!preWriterRawSha256.equals(observedRaw))
            throw new Refusal(Refusal.Kind.REFUSE, "PRE_WRITER_BYTES_MISMATCH",
                    "certificate bound to pre-writer bytes " + preWriterRawSha256 + ", observed " + observedRaw);
        if (!exactSemanticSha256.equals(observed.semanticSha256))
            throw new Refusal(Refusal.Kind.REFUSE, "EXACT_IDENTITY_MISMATCH",
                    "exact CANONICAL_ID_V2 semantic identity differs from the certificate");
        if (!exactDeclarationOrderSha256.equals(observed.declarationOrderSha256))
            throw new Refusal(Refusal.Kind.REFUSE, "EXACT_IDENTITY_MISMATCH",
                    "exact CANONICAL_ID_V2 declaration-order identity differs from the certificate");
        if (!sessionInvariantSha256.equals(observed.sessionInvariantSha256))
            throw new Refusal(Refusal.Kind.REFUSE, "INVARIANT_MISMATCH",
                    "session-bound invariant " + observed.sessionInvariantSha256
                            + " differs from certified " + sessionInvariantSha256);
        if (!expectedSessionUuid.equals(observed.maskedValues.get(0)))
            throw new Refusal(Refusal.Kind.REFUSE, "SESSION_UUID_MISMATCH",
                    "observed sessionId " + observed.maskedValues.get(0)
                            + " is not the certified session UUID " + expectedSessionUuid);
        if (!maskedAnnotationLocations.equals(observed.maskedLocations))
            throw new Refusal(Refusal.Kind.REFUSE, "MASKED_LOCATION_MISMATCH",
                    "certified masked locations " + maskedAnnotationLocations
                            + " differ from observed " + observed.maskedLocations);
        if (maskedOccurrenceCount != observed.maskedOccurrenceCount)
            throw new Refusal(Refusal.Kind.REFUSE, "MASKED_OCCURRENCE_MISMATCH",
                    "certified " + maskedOccurrenceCount + " masked occurrences, observed "
                            + observed.maskedOccurrenceCount);
        if (distinctMaskedUuidCount != observed.maskedValues.size())
            throw new Refusal(Refusal.Kind.REFUSE, "MASKED_DISTINCT_MISMATCH",
                    "certified " + distinctMaskedUuidCount + " distinct masked UUIDs, observed "
                            + observed.maskedValues.size());
    }

    // ---------------------------------------------------------------- internals

    public static final class Refusal extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        public enum Kind { INCOMPLETE, REFUSE }

        public final Kind kind;
        public final String reason;

        public Refusal(Kind kind, String reason, String detail) {
            super("SESSION_BOUND_" + kind + " [" + reason + "]: " + detail);
            this.kind = kind;
            this.reason = reason;
        }
    }

    private static boolean known(String key) {
        for (String candidate : KEYS) if (candidate.equals(key)) return true;
        return false;
    }

    private String field(String key) {
        if ("process_id".equals(key)) return processId;
        if ("transformation_session_id".equals(key)) return transformationSessionId;
        if ("defining_loader_identity".equals(key)) return definingLoaderIdentity;
        if ("class_name".equals(key)) return className;
        if ("pre_writer_raw_sha256".equals(key)) return preWriterRawSha256;
        if ("exact_semantic_sha256".equals(key)) return exactSemanticSha256;
        if ("exact_declaration_order_sha256".equals(key)) return exactDeclarationOrderSha256;
        if ("session_invariant_sha256".equals(key)) return sessionInvariantSha256;
        if ("expected_session_uuid".equals(key)) return expectedSessionUuid;
        if ("recipe_sha256".equals(key)) return recipeSha256;
        if ("runtime_manifest_sha256".equals(key)) return runtimeManifestSha256;
        if ("acquisition_evidence_sha256".equals(key)) return acquisitionEvidenceSha256;
        throw new IllegalStateException("unmapped certificate field " + key);
    }

    private static String text(Map<String, Object> fields, String key, String pattern) {
        Object value = fields.get(key);
        if (!(value instanceof String) || ((String) value).length() == 0)
            throw new Refusal(Refusal.Kind.REFUSE, "INVALID_CERTIFICATE_FIELD", key + " must be a non-empty string");
        String text = (String) value;
        if (pattern != null && !text.matches(pattern))
            throw new Refusal(Refusal.Kind.REFUSE, "INVALID_CERTIFICATE_FIELD", key + " is malformed: " + text);
        return text;
    }

    private static int count(Map<String, Object> fields, String key) {
        Object value = fields.get(key);
        if (!(value instanceof Integer) || ((Integer) value).intValue() < 1)
            throw new Refusal(Refusal.Kind.REFUSE, "INVALID_CERTIFICATE_FIELD", key + " must be a positive integer");
        return ((Integer) value).intValue();
    }

    @SuppressWarnings("unchecked")
    private static List<String> textList(Map<String, Object> fields, String key) {
        Object value = fields.get(key);
        if (!(value instanceof List) || ((List<?>) value).isEmpty())
            throw new Refusal(Refusal.Kind.REFUSE, "INVALID_CERTIFICATE_FIELD", key + " must be a non-empty string array");
        List<String> result = new ArrayList<String>();
        for (Object element : (List<Object>) value) {
            if (!(element instanceof String) || ((String) element).length() == 0)
                throw new Refusal(Refusal.Kind.REFUSE, "INVALID_CERTIFICATE_FIELD", key + " must contain strings");
            result.add((String) element);
        }
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

    /** Minimal strict JSON reader: objects of string/array/integer values only. */
    private static final class Json {
        private final String text;
        private int at;

        private Json(String text) { this.text = text; }

        static Map<String, Object> object(String text) {
            Json json = new Json(text);
            json.skipSpace();
            Map<String, Object> result = json.readObject();
            json.skipSpace();
            if (json.at != text.length()) throw new IllegalArgumentException("trailing content at offset " + json.at);
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
                if (result.containsKey(key)) throw new IllegalArgumentException("duplicate key " + key);
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
            if (next() != expected) throw new IllegalArgumentException("expected " + expected + " at offset " + (at - 1));
        }

        private char peek() {
            if (at >= text.length()) throw new IllegalArgumentException("unexpected end of certificate");
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
