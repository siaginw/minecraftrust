package com.rustcraft.qualification;

import com.rustcraft.coremod.CanonicalClassIdentityV2;
import com.rustcraft.coremod.SessionBoundIdentityCertificate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Same-process acquisition contract for session-bound class identity.
 *
 * <p>Authority must be tied to a derivation that happened inside ONE
 * transformation process/session:</p>
 *
 * <pre>
 *   exact observed pre-writer bytes
 *        -&gt; same loader, same process/session, same transformer chain
 *        -&gt; RustCraft instrumentation
 *        -&gt; exact post-writer bytes
 * </pre>
 *
 * <p>Cross-process byte comparison can validate that a session invariant
 * projection is stable, but it can never stand in for this chain: process
 * generated metadata differs by construction between processes, so equal bytes
 * across processes prove nothing about what actually happened to a class.</p>
 *
 * <p>Every class definition attempt is recorded, successful or not, in its own
 * record. Two successful definitions of the same binary name are never merged:
 * each carries its own pre-writer bytes, its own identities and its own
 * ordinal. A certificate can only be issued from a complete, successful,
 * self-consistent record.</p>
 */
public final class SameProcessAcquisition {

    public static final String SCHEMA = "RUSTCRAFT_SAME_PROCESS_ACQUISITION_V1";

    public static final class Incomplete extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        public Incomplete(String detail) { super("SAME_PROCESS_ACQUISITION_INCOMPLETE: " + detail); }
    }

    /** How the instrumentation step for one definition ended. */
    public static final class HookPlacement {
        public static final String NOT_PLANNED = "NOT_PLANNED";
        public static final String PLACED = "PLACED";
        public static final String REFUSED = "REFUSED";

        private HookPlacement() { }
    }

    /**
     * One class-definition attempt. Created when a transformer is handed a
     * buffer, completed when the loader returns a Class or the attempt fails.
     * A record that never completed can never produce a certificate.
     */
    public static final class Definition {
        public final int ordinal;
        public final String binaryName;
        public final String processId;
        public final String transformationSessionId;
        public final String definingLoaderIdentity;
        public final byte[] preWriterBytes;
        public final CanonicalClassIdentityV2.Result exact;
        public final CanonicalClassIdentityV2.Result session;
        public String postWriterRawSha256;
        private byte[] postWriterBytes;
        public String hookPlacement = HookPlacement.NOT_PLANNED;
        public boolean definitionSucceeded;
        public String definedClassIdentity;
        public String failure;

        Definition(int ordinal, String binaryName, String processId, String transformationSessionId,
                String definingLoaderIdentity, byte[] preWriterBytes) {
            this.ordinal = ordinal;
            this.binaryName = binaryName;
            this.processId = processId;
            this.transformationSessionId = transformationSessionId;
            this.definingLoaderIdentity = definingLoaderIdentity;
            this.preWriterBytes = preWriterBytes;
            this.exact = CanonicalClassIdentityV2.identify(preWriterBytes);
            CanonicalClassIdentityV2.Result projection = null;
            try {
                projection = CanonicalClassIdentityV2.identifySessionBound(preWriterBytes);
            } catch (CanonicalClassIdentityV2.IdentityFailure noProvenance) {
                // A class with no qualified session provenance is still an exact
                // identity record; it simply cannot be certified session-bound.
                projection = null;
            }
            this.session = projection;
        }

        /** Records the exact buffer the instrumentation step produced. */
        public Definition instrumentedWith(byte[] postWriterBytes, String placement) {
            if (definitionSucceeded || failure != null)
                throw new Incomplete("definition " + ordinal + " is already closed");
            if (postWriterBytes == null)
                throw new Incomplete("definition " + ordinal + " has no post-writer buffer");
            this.postWriterBytes = postWriterBytes.clone();
            this.postWriterRawSha256 = sha256(this.postWriterBytes);
            this.hookPlacement = placement;
            return this;
        }

        /** The exact buffer this process handed the loader, or null if none. */
        public byte[] postWriterBuffer() {
            return postWriterBytes == null ? null : postWriterBytes.clone();
        }

        /** Records that the loader returned a Class for this attempt. */
        public Definition defined(Class<?> returned, String loaderIdentity) {
            if (failure != null)
                throw new Incomplete("definition " + ordinal + " already failed");
            if (returned == null)
                throw new Incomplete("definition " + ordinal + " has no returned Class");
            this.definitionSucceeded = true;
            this.definedClassIdentity = returned.getName() + "@"
                    + Integer.toHexString(System.identityHashCode(returned));
            if (loaderIdentity != null && !loaderIdentity.equals(this.definingLoaderIdentity))
                throw new Incomplete("definition " + ordinal
                        + " was defined by a different loader than it was transformed for");
            return this;
        }

        /** Records a failed definition attempt. Failed attempts are kept, not dropped. */
        public Definition failed(String reason) {
            if (definitionSucceeded)
                throw new Incomplete("definition " + ordinal + " already succeeded");
            this.failure = reason;
            return this;
        }

        /** True when this record could back a session-bound certificate. */
        public boolean certifiable() {
            return definitionSucceeded && failure == null && postWriterRawSha256 != null
                    && session != null && !session.maskedValues.isEmpty();
        }

        public String describe() {
            StringBuilder text = new StringBuilder();
            text.append(SCHEMA).append(' ').append(binaryName)
                    .append(" attempt=").append(ordinal)
                    .append(" succeeded=").append(definitionSucceeded);
            text.append("\n  process=").append(processId)
                    .append(" session=").append(transformationSessionId)
                    .append(" loader=").append(definingLoaderIdentity);
            text.append("\n  pre_raw_sha256=").append(exact.rawSha256)
                    .append("\n  post_raw_sha256=").append(postWriterRawSha256);
            text.append("\n  exact_semantic_sha256=").append(exact.semanticSha256)
                    .append("\n  exact_declaration_order_sha256=").append(exact.declarationOrderSha256);
            text.append("\n  session_invariant_sha256=")
                    .append(session == null ? "ABSENT" : session.sessionInvariantSha256);
            if (session != null) {
                text.append("\n  session_uuid=").append(session.maskedValues)
                        .append(" distinct=").append(session.maskedValues.size())
                        .append(" occurrences=").append(session.maskedOccurrenceCount);
                for (String location : session.maskedLocations) text.append("\n  masked_location=").append(location);
            }
            text.append("\n  hook_placement=").append(hookPlacement)
                    .append("\n  defined_class=").append(definedClassIdentity)
                    .append("\n  failure=").append(failure);
            return text.toString();
        }
    }

    private final String processId;
    private final String transformationSessionId;
    private final List<Definition> definitions =
            Collections.synchronizedList(new ArrayList<Definition>());
    private final AtomicInteger ordinals = new AtomicInteger();

    public SameProcessAcquisition(String processId, String transformationSessionId) {
        if (processId == null || processId.length() == 0)
            throw new Incomplete("no process identity bound");
        if (transformationSessionId == null || transformationSessionId.length() == 0)
            throw new Incomplete("no transformation session identity bound");
        this.processId = processId;
        this.transformationSessionId = transformationSessionId;
    }

    public String processId() { return processId; }
    public String transformationSessionId() { return transformationSessionId; }

    private static volatile SameProcessAcquisition bound;

    /**
     * The one recorder for this transformation process/session. The live
     * transformers open their definition attempts here so the pre-writer bytes
     * they were actually handed, and the buffer they actually returned, are
     * recorded against the loader and session that produced them.
     */
    public static SameProcessAcquisition bound() { return bound; }

    public static void bind(SameProcessAcquisition acquisition) { bound = acquisition; }

    /**
     * Opens a definition attempt. `preWriterBytes` must be the exact buffer this
     * transformer was handed, not a re-read of the same class from elsewhere.
     */
    public Definition begin(String binaryName, ClassLoader definingLoader, byte[] preWriterBytes) {
        if (binaryName == null || binaryName.length() == 0)
            throw new Incomplete("no binary class name");
        if (preWriterBytes == null)
            throw new Incomplete("no pre-writer bytes");
        return new Definition(ordinals.incrementAndGet(), binaryName, processId,
                transformationSessionId, identityOf(definingLoader), preWriterBytes);
    }

    public void record(Definition definition) {
        definitions.add(definition);
    }

    /** All attempts in this session, in order, successful or not. */
    public List<Definition> definitions() {
        synchronized (definitions) {
            return new ArrayList<Definition>(definitions);
        }
    }

    /**
     * All successful definitions of one binary name. More than one means the
     * class was defined twice: the caller must qualify which record applies
     * rather than letting them be conflated.
     */
    public List<Definition> successfulDefinitions(String binaryName) {
        List<Definition> result = new ArrayList<Definition>();
        for (Definition definition : definitions()) {
            if (definition.binaryName.equals(binaryName) && definition.definitionSucceeded) {
                result.add(definition);
            }
        }
        return result;
    }

    /**
     * Issues a session-bound certificate from one successful same-process
     * record. Anything incomplete, inconsistent or ambiguous refuses.
     */
    public SessionBoundIdentityCertificate certify(Definition definition, String recipeSha256,
            String runtimeManifestSha256, String policySha256, String acquisitionEvidenceSha256) {
        if (definition == null)
            throw new Incomplete("no acquisition record supplied");
        if (!definitions.contains(definition))
            throw new Incomplete("record " + definition.ordinal + " does not belong to this session");
        if (definition.failure != null)
            throw new Incomplete("record " + definition.ordinal + " failed: " + definition.failure);
        if (!definition.definitionSucceeded)
            throw new Incomplete("record " + definition.ordinal + " never returned a Class");
        if (definition.postWriterRawSha256 == null)
            throw new Incomplete("record " + definition.ordinal + " has no post-writer buffer");
        if (HookPlacement.REFUSED.equals(definition.hookPlacement))
            throw new Incomplete("record " + definition.ordinal + " refused hook placement");
        if (definition.session == null)
            throw new Incomplete("record " + definition.ordinal
                    + " has no qualified session provenance to certify");
        if (successfulDefinitions(definition.binaryName).size() != 1)
            throw new Incomplete("binary name " + definition.binaryName
                    + " has more than one successful definition in this session;"
                    + " refusing to conflate them");
        if (!definition.processId.equals(processId)
                || !definition.transformationSessionId.equals(transformationSessionId))
            throw new Incomplete("record " + definition.ordinal + " belongs to another session");
        if (policySha256 == null)
            throw new Incomplete("no admission policy supplied: a certificate may not record "
                    + "an acquisition without naming what authorized it");
        return SessionBoundIdentityCertificate.issue(processId, transformationSessionId,
                definition.definingLoaderIdentity, definition.preWriterBytes, definition.session,
                recipeSha256, runtimeManifestSha256, policySha256, acquisitionEvidenceSha256);
    }

    /** A loader identity that does not conflate two distinct loaders. */
    /** Delegates to the single gate-side rendering; see LiveHookSupport.loaderIdentity. */
    public static String identityOf(ClassLoader loader) {
        return com.rustcraft.coremod.LiveHookSupport.loaderIdentity(loader);
    }

    public static String sha256(byte[] bytes) {
        return SessionBoundIdentityCertificate.sha256(bytes);
    }

    private SameProcessAcquisition() { throw new AssertionError(); }
}
