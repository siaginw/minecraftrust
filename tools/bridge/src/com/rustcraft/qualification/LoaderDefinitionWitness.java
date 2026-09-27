package com.rustcraft.qualification;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An INDEPENDENT record of what the runtime's loader actually defined.
 *
 * <p>The transformation chain is written by the process that did the
 * transforming, and a chain that is its own proof is a story. So the final stage
 * of that chain is deliberately not taken from the chain: this witness reports
 * the bytes a SEPARATE observation point recorded at definition time, together
 * with the loader that defined them, and the qualification engine compares the
 * two. When they disagree the engine fails the run, which is the entire reason
 * the witness exists.</p>
 *
 * <p>Two independent facts are reported per class, and they are joined here on
 * purpose rather than in the consumer:</p>
 * <ul>
 *   <li>{@code observed_raw_sha256} -- what the passive observer saw the loader
 *       hand to the VM. This is the definition.</li>
 *   <li>{@code rustcraft_post_writer_sha256} -- what the writers returned, taken
 *       from the same-process acquisition record.</li>
 * </ul>
 *
 * <p>When the two are equal the writer output IS the defined buffer, which is a
 * fact worth pinning down rather than assuming. When they differ, the difference
 * is the honest signature of a stage that ran after the writers, and the engine
 * decides what to do with it. What this class never does is paper over the gap
 * by substituting one value for the other -- an earlier version of the Clean
 * Forge witness did exactly that and made the "independent" check compare a
 * value with itself.</p>
 *
 * <p>Generic by construction: it takes the observation maps as arguments and
 * names no runtime, no loader type, no mod and no class.</p>
 */
public final class LoaderDefinitionWitness {

    public static final String SCHEMA = "RUSTCRAFT_LOADER_DEFINITION_WITNESS_V1";

    /**
     * Renders the witness document.
     *
     * @param observedHashes the passive observer's record: dotted class name to
     *        the SHA-256 of the exact buffer the loader defined
     * @param observedLoaders the same observer's record of which loader defined it
     * @param loaderIdentity this launch's defining loader, as one identity string
     * @param acquisition this session's acquisition recorder, or null when none
     *        is bound -- in which case the writer column is reported ABSENT
     *        rather than filled from the definition
     */
    public static String witness(Map<String, String> observedHashes,
            Map<String, String> observedLoaders,
            String loaderIdentity,
            SameProcessAcquisition acquisition) {
        Map<String, Object> document = new LinkedHashMap<String, Object>();
        document.put("schema", SCHEMA);
        document.put("scope", "INDEPENDENT_DEFINITION_OBSERVATION");
        document.put("independence",
                "observed_raw_sha256 comes from the passive definition observer, not from the "
                        + "transformation chain; the engine compares them");
        document.put("loader_identity", loaderIdentity);
        document.put("production_authority", Boolean.FALSE);
        document.put("acquisition_bound", Boolean.valueOf(acquisition != null));

        List<Object> rows = new ArrayList<Object>();
        int verified = 0;
        if (observedHashes != null) {
            for (String name : new java.util.TreeSet<String>(observedHashes.keySet())) {
                Map<String, Object> row = new LinkedHashMap<String, Object>();
                row.put("name", name);
                row.put("phase", "post");
                row.put("observed_raw_sha256", observedHashes.get(name));
                String loader = observedLoaders == null ? null : observedLoaders.get(name);
                row.put("defining_loader", loader == null ? loaderIdentity : loader);
                String post = postWriterSha256(acquisition, name);
                if (post == null) {
                    row.put("rustcraft_post_writer_sha256", null);
                    row.put("status", "OBSERVED_WITHOUT_WRITER_RECORD");
                } else {
                    row.put("rustcraft_post_writer_sha256", post);
                    boolean same = post.equals(observedHashes.get(name));
                    row.put("defined_bytes_equal_rustcraft_output", Boolean.valueOf(same));
                    row.put("status", same ? "VERIFIED" : "DEFINITION_DIFFERS_FROM_WRITER_OUTPUT");
                    if (same) verified++;
                }
                rows.add(row);
            }
        }
        document.put("classes", Integer.valueOf(rows.size()));
        document.put("verified", Integer.valueOf(verified));
        document.put("verification", rows);
        return render(document);
    }

    /**
     * The writer's own output for this class, from the same-process record.
     * Returns null rather than falling back to the observed bytes: a witness
     * column that silently fills itself with the value it is supposed to be
     * checked against is worse than an absent column.
     */
    private static String postWriterSha256(SameProcessAcquisition acquisition, String name) {
        if (acquisition == null) return null;
        String internal = name.replace('.', '/');
        SameProcessAcquisition.Definition last = null;
        for (SameProcessAcquisition.Definition definition : acquisition.definitions()) {
            if (!definition.binaryName.replace('.', '/').equals(internal)) continue;
            if (definition.postWriterRawSha256 == null) continue;
            if (last == null || definition.ordinal > last.ordinal) last = definition;
        }
        return last == null ? null : last.postWriterRawSha256;
    }

    /** Minimal canonical JSON: keys in insertion order, strings escaped. */
    private static String render(Object value) {
        StringBuilder out = new StringBuilder();
        write(out, value);
        return out.toString();
    }

    private static void write(StringBuilder out, Object value) {
        if (value == null) { out.append("null"); return; }
        if (value instanceof String) { quote(out, (String) value); return; }
        if (value instanceof Boolean || value instanceof Integer) { out.append(value); return; }
        if (value instanceof Map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (!first) out.append(',');
                first = false;
                quote(out, String.valueOf(entry.getKey()));
                out.append(':');
                write(out, entry.getValue());
            }
            out.append('}');
            return;
        }
        if (value instanceof List) {
            out.append('[');
            boolean first = true;
            for (Object each : (List<?>) value) {
                if (!first) out.append(',');
                first = false;
                write(out, each);
            }
            out.append(']');
            return;
        }
        quote(out, String.valueOf(value));
    }

    private static void quote(StringBuilder out, String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':  out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < 0x20) out.append(String.format("\\u%04x", Integer.valueOf(c)));
                    else out.append(c);
            }
        }
        out.append('"');
    }

    private LoaderDefinitionWitness() { throw new AssertionError(); }
}
