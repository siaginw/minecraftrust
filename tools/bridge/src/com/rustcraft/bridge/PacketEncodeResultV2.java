package com.rustcraft.bridge;

/**
 * Immutable, dependency-free Java 8 decoder for one native V2 encode result.
 *
 * Success is positive: bit 62 is set, bit 63 and bits 61..47 are zero,
 * bits 46..16 contain an unsigned 31-bit byte count, and bits 15..0 contain
 * the emitted section mask. A zero byte count requires a zero mask.
 * Failures are exactly -1 through -7. All other representations are malformed.
 * Java unsigned shifts prevent sign extension while extracting success fields.
 *
 * The count and mask describe the same native serialization. They say nothing
 * about coherent Java capture. A failed operation's output is invalid scratch;
 * this class deliberately provides no count or mask access on failure.
 */
public final class PacketEncodeResultV2 {
    private static final long SUCCESS_TAG = 1L << 62;
    private static final long RESERVED_BITS = 0x3FFF800000000000L;

    public enum Failure {
        INVALID_ARGUMENT(-1L),
        MISSING_HANDLE(-2L),
        STALE_GENERATION(-3L),
        MISSING_SELECTED_SECTION(-4L),
        OUTPUT_CAPACITY(-5L),
        ENCODE_FAILURE(-6L),
        PANIC(-7L);

        private final long code;

        Failure(long code) {
            this.code = code;
        }

        public long code() {
            return code;
        }
    }

    private final long packed;
    private final Failure failure;

    private PacketEncodeResultV2(long packed, Failure failure) {
        this.packed = packed;
        this.failure = failure;
    }

    /** Rejects malformed/reserved values rather than treating them as success. */
    public static PacketEncodeResultV2 decode(long packed) {
        if (packed < 0) {
            for (Failure failure : Failure.values()) {
                if (packed == failure.code()) return new PacketEncodeResultV2(packed, failure);
            }
            throw malformed(packed);
        }
        if ((packed & SUCCESS_TAG) == 0 || (packed & RESERVED_BITS) != 0) {
            throw malformed(packed);
        }
        int byteCount = (int) ((packed >>> 16) & 0x7FFFFFFFL);
        if (byteCount == 0 && (packed & 0xFFFFL) != 0) throw malformed(packed);
        return new PacketEncodeResultV2(packed, null);
    }

    private static IllegalArgumentException malformed(long packed) {
        return new IllegalArgumentException("Malformed packet encode V2 result: 0x"
                + Long.toHexString(packed));
    }

    public boolean isSuccess() {
        return failure == null;
    }

    public int bytesWritten() {
        requireSuccess();
        return (int) ((packed >>> 16) & 0x7FFFFFFFL);
    }

    public int emittedMask() {
        requireSuccess();
        return (int) (packed & 0xFFFFL);
    }

    public Failure failure() {
        if (failure == null) throw new IllegalStateException("Successful encode has no failure");
        return failure;
    }

    private void requireSuccess() {
        if (failure != null) throw new IllegalStateException("Encode failed: " + failure);
    }
}
