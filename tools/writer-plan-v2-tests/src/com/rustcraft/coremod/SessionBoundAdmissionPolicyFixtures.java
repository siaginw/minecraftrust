package com.rustcraft.coremod;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Re-renders a policy with one expectation changed, for the negative controls.
 *
 * <p>This exists so a control can say "refuse because the policy expected a
 * different invariant" rather than only "refused". A refusal is only evidence if
 * the rule that fired is the rule under test, and constructing a doctored policy
 * by string surgery on the canonical rendering is what makes the mutation land
 * in exactly one field.</p>
 */
final class SessionBoundAdmissionPolicyFixtures {

    /** Renders `policy` with `key` set to `value`, in the canonical key order. */
    static String render(SessionBoundAdmissionPolicy policy, String key, Object value) {
        Map<String, Object> fields = fields(policy);
        if (key != null) {
            if (!fields.containsKey(key)) throw new IllegalArgumentException("no such policy field " + key);
            Object existing = fields.get(key);
            if (existing instanceof Integer && !(value instanceof Integer))
                throw new IllegalArgumentException(key + " is a count, not a string");
            if (existing instanceof List && !(value instanceof String[]))
                throw new IllegalArgumentException(key + " is a list");
            fields.put(key, value instanceof String[] ? Arrays.asList((String[]) value) : value);
        }
        return canonical(fields);
    }

    /**
     * A valid static policy for an observed class, carrying nothing that only
     * exists once a JVM is running: no process, no session UUID, no byte hash.
     */
    static String policyFor(CanonicalClassIdentityV2.Result exact,
            CanonicalClassIdentityV2.Result session, String binaryName, String loaderClass,
            String... hookIds) {
        Map<String, Object> fields = new LinkedHashMap<String, Object>();
        fields.put("schema", SessionBoundAdmissionPolicy.SCHEMA);
        fields.put("schema_version", Integer.valueOf(SessionBoundAdmissionPolicy.SCHEMA_VERSION));
        fields.put("provenance", SessionBoundAdmissionPolicy.PROVENANCE);
        fields.put("identity_mode", CanonicalClassIdentityV2.SCHEMA_SESSION_BOUND);
        fields.put("class_name", binaryName);
        fields.put("expected_session_invariant_sha256", session.sessionInvariantSha256);
        fields.put("expected_declaration_order_sha256", exact.declarationOrderSha256);
        fields.put("expected_annotation_descriptor", CanonicalClassIdentityV2.MIXIN_MERGED_DESC);
        fields.put("expected_annotation_element", CanonicalClassIdentityV2.SESSION_ELEMENT);
        fields.put("expected_masked_locations", new ArrayList<String>(session.maskedLocations));
        fields.put("expected_masked_occurrence_count", Integer.valueOf(session.maskedOccurrenceCount));
        fields.put("expected_distinct_masked_uuid_count", Integer.valueOf(1));
        fields.put("expected_session_uuid_shape", SessionBoundAdmissionPolicy.UUID_SHAPE);
        fields.put("expected_loader_class", loaderClass);
        fields.put("expected_loader_scope", SessionBoundAdmissionPolicy.SCOPE_ANY);
        fields.put("runtime_profile", LiveHookSupport.runtimeProfile());
        fields.put("required_hook_ids",
                new ArrayList<String>(Arrays.asList(hookIds)));
        return canonical(fields);
    }

    private static Map<String, Object> fields(SessionBoundAdmissionPolicy policy) {
        Map<String, Object> fields = new LinkedHashMap<String, Object>();
        fields.put("schema", SessionBoundAdmissionPolicy.SCHEMA);
        fields.put("schema_version", Integer.valueOf(SessionBoundAdmissionPolicy.SCHEMA_VERSION));
        fields.put("provenance", SessionBoundAdmissionPolicy.PROVENANCE);
        fields.put("identity_mode", CanonicalClassIdentityV2.SCHEMA_SESSION_BOUND);
        fields.put("class_name", policy.className);
        fields.put("expected_session_invariant_sha256", policy.expectedSessionInvariantSha256);
        fields.put("expected_declaration_order_sha256", policy.expectedDeclarationOrderSha256);
        fields.put("expected_annotation_descriptor", policy.expectedAnnotationDescriptor);
        fields.put("expected_annotation_element", policy.expectedAnnotationElement);
        fields.put("expected_masked_locations", policy.expectedMaskedLocations);
        fields.put("expected_masked_occurrence_count", Integer.valueOf(policy.expectedMaskedOccurrenceCount));
        fields.put("expected_distinct_masked_uuid_count", Integer.valueOf(policy.expectedDistinctMaskedUuidCount));
        fields.put("expected_session_uuid_shape", policy.expectedSessionUuidShape);
        fields.put("expected_loader_class", policy.expectedLoaderClass);
        fields.put("expected_loader_scope", policy.expectedLoaderScope);
        fields.put("runtime_profile", policy.runtimeProfile);
        fields.put("runtime_manifest_sha256", policy.runtimeManifestSha256);
        fields.put("writer_plan_sha256", policy.writerPlanSha256);
        fields.put("recipe_sha256", policy.recipeSha256);
        fields.put("required_hook_ids", policy.requiredHookIds);
        return fields;
    }

    private static String canonical(Map<String, Object> fields) {
        List<String> parts = new ArrayList<String>();
        for (Map.Entry<String, Object> entry : fields.entrySet()) {
            StringBuilder text = new StringBuilder();
            text.append(quote(entry.getKey())).append(':');
            Object value = entry.getValue();
            if (value instanceof List) {
                text.append('[');
                boolean first = true;
                for (Object element : (List<?>) value) {
                    if (!first) text.append(',');
                    text.append(quote(String.valueOf(element)));
                    first = false;
                }
                text.append(']');
            } else if (value instanceof Integer) {
                text.append(value.toString());
            } else {
                text.append(quote(String.valueOf(value)));
            }
            parts.add(text.toString());
        }
        return "{" + String.join(",", parts) + "}";
    }

    private static String quote(String value) {
        StringBuilder text = new StringBuilder();
        text.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\') text.append('\\').append(c);
            else if (c < 0x20) text.append(String.format("\\u%04x", (int) c));
            else text.append(c);
        }
        return text.append('"').toString();
    }

    private SessionBoundAdmissionPolicyFixtures() { }
}
