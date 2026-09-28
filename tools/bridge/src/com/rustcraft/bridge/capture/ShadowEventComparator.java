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

    // ------------------------------------------------------------------
    // Semantic comparison (the live comparison contract)
    // ------------------------------------------------------------------

    /**
     * Strict Protocol-340 section reader, ported line-for-line from the
     * independent Python decoder (tools/testing/packet_decoder.py): bounded
     * 4..8-bit local palettes, 9..16-bit global width, canonical varints,
     * exact word counts, no trailing bytes. Any deviation throws.
     */
    static final class WireReader {
        private final byte[] data;
        private int offset;
        WireReader(byte[] data) { this.data = data; }
        int offset() { return offset; }
        byte[] take(int size) {
            if (size < 0 || size > data.length - offset)
                throw new IllegalArgumentException("truncated payload");
            byte[] out = java.util.Arrays.copyOfRange(data, offset, offset + size);
            offset += size;
            return out;
        }
        long varint() {
            long value = 0;
            for (int index = 0; index < 5; index++) {
                if (offset >= data.length) throw new IllegalArgumentException("truncated varint");
                int unsigned = data[offset] & 0xFF;
                offset++;
                if (index == 4 && (unsigned & 0xF0) != 0)
                    throw new IllegalArgumentException("varint exceeds unsigned 32-bit");
                value |= (long) (unsigned & 0x7F) << (7 * index);
                if (unsigned < 0x80) {
                    if (index > 0 && value < (1L << (7 * index)))
                        throw new IllegalArgumentException("noncanonical varint");
                    return value;
                }
            }
            throw new IllegalArgumentException("unterminated varint");
        }
    }

    /** One decoded section: logical global-id cells plus light planes. */
    static final class DecodedSection {
        final int y, bits;
        final int[] palette;      // global ids; empty when the width is global
        final int[] states;       // 4096 logical global ids
        final byte[] blockLight, skyLight;
        DecodedSection(int y, int bits, int[] palette, int[] states,
                       byte[] blockLight, byte[] skyLight) {
            this.y = y; this.bits = bits; this.palette = palette; this.states = states;
            this.blockLight = blockLight; this.skyLight = skyLight;
        }
    }

    static DecodedSection[] decode(byte[] body, int mask, boolean fullChunk,
                                   boolean skylight, int globalBits) {
        WireReader reader = new WireReader(body);
        java.util.List<DecodedSection> sections = new java.util.ArrayList<DecodedSection>();
        for (int y = 0; y < 16; y++) {
            if ((mask & (1 << y)) == 0) continue;
            int bits = reader.take(1)[0] & 0xFF;
            if (bits < 4 || bits > 16) throw new IllegalArgumentException("unsupported wire width");
            long count = reader.varint();
            int[] palette = new int[0];
            if (bits <= 8) {
                if (count < 1 || count > (1L << bits))
                    throw new IllegalArgumentException("invalid local palette length");
                palette = new int[(int) count];
                java.util.HashSet<Integer> distinct = new java.util.HashSet<Integer>();
                for (int i = 0; i < count; i++) {
                    palette[i] = (int) reader.varint();
                    if (!distinct.add(palette[i]))
                        throw new IllegalArgumentException("duplicate local palette entry");
                }
            } else {
                if (bits != globalBits || count != 0)
                    throw new IllegalArgumentException("global palette width or length mismatch");
            }
            long wordCount = reader.varint();
            long expectedWords = (4096L * bits + 63) / 64;
            if (wordCount != expectedWords)
                throw new IllegalArgumentException("incorrect wire word count");
            byte[] packed = reader.take((int) (wordCount * 8));
            long[] words = new long[(int) wordCount];
            for (int w = 0; w < wordCount; w++)
                for (int b = 0; b < 8; b++)
                    words[w] = (words[w] << 8) | (packed[w * 8 + b] & 0xFFL);
            int[] states = new int[4096];
            for (int cell = 0; cell < 4096; cell++) {
                long position = (long) cell * bits;
                int word = (int) (position / 64), shift = (int) (position % 64);
                long value = words[word] >>> shift;
                if (shift + bits > 64) value |= words[word + 1] << (64 - shift);
                value &= (1L << bits) - 1;
                if (bits <= 8) {
                    if (value >= palette.length)
                        throw new IllegalArgumentException("palette index out of range");
                    states[cell] = palette[(int) value];
                } else {
                    states[cell] = (int) value;
                }
            }
            byte[] blockLight = reader.take(2048);
            byte[] skyLight = skylight ? reader.take(2048) : null;
            sections.add(new DecodedSection(y, bits, palette, states, blockLight, skyLight));
        }
        if (fullChunk) reader.take(256); // biomes (compared at packet level below)
        if (reader.offset() != body.length)
            throw new IllegalArgumentException("trailing payload bytes or unadvertised sections");
        return sections.toArray(new DecodedSection[0]);
    }

    /** Semantic comparison outcome: the Phase-D live comparison contract. */
    public static final class SemanticComparison {
        public final boolean maskEqual;
        /** Recorded observed fact, never the pass criterion on the live path. */
        public final boolean byteExact;
        public final boolean semanticEqual;
        /** Cell-level first divergence when semantic equality fails. */
        public final String firstSemanticDivergence;
        public final long compareNanos;
        SemanticComparison(boolean maskEqual, boolean byteExact, boolean semanticEqual,
                           String divergence, long nanos) {
            this.maskEqual = maskEqual; this.byteExact = byteExact;
            this.semanticEqual = semanticEqual; this.firstSemanticDivergence = divergence;
            this.compareNanos = nanos;
        }
    }

    /**
     * The live comparison: mask equality plus decoded logical equality --
     * per-section 4096 logical global ids, block light, sky light, biomes --
     * because the Java wire palette is STATEFUL (vanilla section palettes
     * retain entries from replaced blocks, so two byte streams can encode
     * identical logical sections with different palette cardinality/order;
     * proven on live smoke artifacts where Java carried 29 palette entries
     * against Rust's minimal 21 with every logical cell equal). Exact byte
     * equality is recorded per event as an observed fact.
     */
    public static SemanticComparison compareSemantic(JavaSide java, RustSide rust,
                                                     boolean fullChunk, boolean skylight,
                                                     int globalBits) {
        long start = System.nanoTime();
        boolean maskEqual = java.mask == rust.mask;
        boolean byteExact = maskEqual
                && java.payload.length == rust.payload.length
                && Arrays.equals(java.payload, rust.payload);
        try {
            DecodedSection[] javaSections = decode(java.payload, java.mask, fullChunk, skylight, globalBits);
            DecodedSection[] rustSections = decode(rust.payload, rust.mask, fullChunk, skylight, globalBits);
            if (javaSections.length != rustSections.length)
                return new SemanticComparison(maskEqual, byteExact, false,
                        "section count " + javaSections.length + " vs " + rustSections.length,
                        System.nanoTime() - start);
            for (int s = 0; s < javaSections.length; s++) {
                DecodedSection a = javaSections[s], b = rustSections[s];
                if (a.y != b.y)
                    return new SemanticComparison(maskEqual, byteExact, false,
                            "section order at " + s + ": y=" + a.y + " vs y=" + b.y,
                            System.nanoTime() - start);
                if (!Arrays.equals(a.states, b.states)) {
                    for (int cell = 0; cell < 4096; cell++)
                        if (a.states[cell] != b.states[cell])
                            return new SemanticComparison(maskEqual, byteExact, false,
                                    "section y=" + a.y + " cell=" + cell + " javaState="
                                            + a.states[cell] + " rustState=" + b.states[cell],
                                    System.nanoTime() - start);
                }
                if (!Arrays.equals(a.blockLight, b.blockLight))
                    return new SemanticComparison(maskEqual, byteExact, false,
                            "section y=" + a.y + " block light differs", System.nanoTime() - start);
                if (skylight && !Arrays.equals(a.skyLight, b.skyLight))
                    return new SemanticComparison(maskEqual, byteExact, false,
                            "section y=" + a.y + " sky light differs", System.nanoTime() - start);
            }
            if (fullChunk) {
                // Biomes trail the section stream (256 bytes) in both bodies.
                int javaBiomesAt = java.payload.length - 256;
                int rustBiomesAt = rust.payload.length - 256;
                if (javaBiomesAt < 0 || rustBiomesAt < 0)
                    return new SemanticComparison(maskEqual, byteExact, false,
                            "body too short for biomes", System.nanoTime() - start);
                for (int i = 0; i < 256; i++)
                    if (java.payload[javaBiomesAt + i] != rust.payload[rustBiomesAt + i])
                        return new SemanticComparison(maskEqual, byteExact, false,
                                "biome[" + i + "] differs", System.nanoTime() - start);
            }
            boolean semanticEqual = maskEqual;
            return new SemanticComparison(maskEqual, byteExact, semanticEqual, null,
                    System.nanoTime() - start);
        } catch (RuntimeException malformed) {
            return new SemanticComparison(maskEqual, byteExact, false,
                    "undecodable payload: " + malformed.getMessage(),
                    System.nanoTime() - start);
        }
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
