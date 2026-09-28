package com.rustcraft.bridge.capture;

import java.security.MessageDigest;
import java.util.Arrays;

/**
 * Phase-D comparison core: exact-equality comparison of the Java-authoritative
 * packet payload against the Rust shadow encode of the same sealed event,
 * with bounded first-divergence evidence on mismatch.
 *
 * <p>The two sides are distinct TYPES ({@link JavaSide} / {@link RustSide}) so
 * they cannot be swapped accidentally — the compiler refuses it. Within the
 * admitted scope there are no intentionally-nondeterministic fields, so exact
 * byte equality is required and no masks exist. A mismatch preserves the
 * divergence offset, a bounded byte context, both sha256 digests, both masks
 * and both lengths; the logical region is reported only when it can be
 * located, and is never guessed.</p>
 */
public final class ShadowEventComparator {

    /** The Java packet's own payload — authoritative, never replaced. */
    public static final class JavaSide {
        public final byte[] payload;
        public final int mask;
        public JavaSide(byte[] payload, int mask) {
            this.payload = payload.clone();
            this.mask = mask;
        }
    }

    /** The Rust shadow encode — recorded, discarded, never transmitted. */
    public static final class RustSide {
        public final byte[] payload;
        public final int mask;
        public RustSide(byte[] payload, int mask) {
            this.payload = payload.clone();
            this.mask = mask;
        }
    }

    /** Bounded first-divergence evidence; never the whole packet in metadata. */
    public static final class FirstDivergence {
        public final int offset;                 // -1 when lengths differ before any byte differs
        public final int javaByte, rustByte;     // -1 when out of range on that side
        public final int javaLength, rustLength;
        public final int javaMask, rustMask;
        public final String javaSha256, rustSha256;
        public final String contextHex;          // ±16 bytes around the divergence, both sides
        public final String region;              // located where possible, never guessed

        FirstDivergence(int offset, int javaByte, int rustByte, int javaLength, int rustLength,
                        int javaMask, int rustMask, String javaSha256, String rustSha256,
                        String contextHex, String region) {
            this.offset = offset;
            this.javaByte = javaByte;
            this.rustByte = rustByte;
            this.javaLength = javaLength;
            this.rustLength = rustLength;
            this.javaMask = javaMask;
            this.rustMask = rustMask;
            this.javaSha256 = javaSha256;
            this.rustSha256 = rustSha256;
            this.contextHex = contextHex;
            this.region = region;
        }
    }

    public static final class Comparison {
        public final boolean pass;
        public final boolean maskEqual;
        public final boolean lengthEqual;
        public final boolean byteEqual;
        public final FirstDivergence firstDivergence; // null on pass
        public final long compareNanos;

        private Comparison(boolean pass, boolean maskEqual, boolean lengthEqual,
                           boolean byteEqual, FirstDivergence divergence, long nanos) {
            this.pass = pass;
            this.maskEqual = maskEqual;
            this.lengthEqual = lengthEqual;
            this.byteEqual = byteEqual;
            this.firstDivergence = divergence;
            this.compareNanos = nanos;
        }
    }

    public Comparison compare(JavaSide java, RustSide rust) {
        return compareStatic(java, rust);
    }

    /** The comparison entry; static so no instance can carry per-event state. */
    public static Comparison compareStatic(JavaSide java, RustSide rust) {
        long start = System.nanoTime();
        boolean maskEqual = java.mask == rust.mask;
        boolean lengthEqual = java.payload.length == rust.payload.length;
        int divergenceAt = -1;
        int limit = Math.min(java.payload.length, rust.payload.length);
        for (int i = 0; i < limit; i++) {
            if (java.payload[i] != rust.payload[i]) { divergenceAt = i; break; }
        }
        boolean byteEqual = divergenceAt < 0 && lengthEqual;
        boolean pass = maskEqual && byteEqual;
        long nanos = System.nanoTime() - start;
        if (pass) return new Comparison(true, true, true, true, null, nanos);
        int offset = maskEqual ? divergenceAt : 0;
        String region = maskEqual ? locateRegion(offset, java.payload)
                                  : "SECTION_MASK";
        return new Comparison(false, maskEqual, lengthEqual, byteEqual,
                new FirstDivergence(
                        offset,
                        offset >= 0 && offset < java.payload.length
                                ? (java.payload[offset] & 0xFF) : -1,
                        offset >= 0 && offset < rust.payload.length
                                ? (rust.payload[offset] & 0xFF) : -1,
                        java.payload.length, rust.payload.length,
                        java.mask, rust.mask,
                        sha256Hex(java.payload), sha256Hex(rust.payload),
                        contextHex(java.payload, rust.payload, offset),
                        region),
                nanos);
    }

    /**
     * Region localization is bounded to what the offset alone proves: offset
     * 0 is the packet head (mask/anchor region); any other offset is inside
     * the section payload stream, whose per-section boundaries would require
     * decoding we do not perform on the mismatch path. Never a guess.
     */
    private static String locateRegion(int offset, byte[] payload) {
        if (offset == 0) return "PAYLOAD_HEAD";
        return "PAYLOAD_BODY_NOT_FURTHER_LOCALIZED";
    }

    private static String contextHex(byte[] java, byte[] rust, int at) {
        if (at < 0) return "len-diff";
        int lo = Math.max(0, at - 16);
        int hi = Math.min(Math.max(java.length, rust.length), at + 17);
        StringBuilder sb = new StringBuilder();
        sb.append("java[");
        for (int i = lo; i < Math.min(hi, java.length); i++)
            sb.append(String.format("%02x", java[i]));
        sb.append("] rust[");
        for (int i = lo; i < Math.min(hi, rust.length); i++)
            sb.append(String.format("%02x", rust[i]));
        sb.append(']');
        return sb.toString();
    }

    static String sha256Hex(byte[] body) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(body);
            StringBuilder sb = new StringBuilder(64);
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception failure) {
            return "unavailable";
        }
    }

    private ShadowEventComparator() { }
}
