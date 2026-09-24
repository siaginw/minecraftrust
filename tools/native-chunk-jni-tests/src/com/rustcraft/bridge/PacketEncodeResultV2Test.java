package com.rustcraft.bridge;

/** Standalone Java 8 contract tests; no native library or Minecraft dependency. */
public final class PacketEncodeResultV2Test {
    private static int checks;

    public static void main(String[] args) {
        success((1L << 62) | (12345L << 16) | 0x8421L, 12345, 0x8421);
        success(1L << 62, 0, 0);
        success((1L << 62) | (256L << 16), 256, 0);
        success((1L << 62) | (1048576L << 16) | 0xFFFFL, 1048576, 0xFFFF);
        success(0x40007FFFFFFFFFFFL, Integer.MAX_VALUE, 0xFFFF);
        for (int count : new int[] {1, 127, 128, 65535, 65536, Integer.MAX_VALUE}) {
            for (int mask : new int[] {0, 1, 0x1F, 0x3F, 0x8000, 0xFFFF}) {
                success((1L << 62) | ((long) count << 16) | mask, count, mask);
            }
        }
        for (PacketEncodeResultV2.Failure expected : PacketEncodeResultV2.Failure.values()) {
            final PacketEncodeResultV2 result = PacketEncodeResultV2.decode(expected.code());
            check(!result.isSuccess(), "error is not success");
            check(result.failure() == expected, "exact failure class");
            expect(IllegalStateException.class, new Runnable() {
                public void run() { result.bytesWritten(); }
            });
            expect(IllegalStateException.class, new Runnable() {
                public void run() { result.emittedMask(); }
            });
        }
        for (long value : new long[] {0, 1, 65535, Long.MAX_VALUE,
                Long.MIN_VALUE, -8, -99, (1L << 62) | 1,
                (1L << 62) | 0xFFFFL, 0x7FFFFFFFL << 16}) malformed(value);
        for (int bit = 47; bit <= 61; bit++) {
            malformed((1L << 62) | (1L << bit) | (256L << 16));
        }
        malformed((1L << 63) | (1L << 62) | (256L << 16));
        System.out.println("PASS PacketEncodeResultV2Test checks=" + checks);
    }

    private static void success(long packed, int count, int mask) {
        final PacketEncodeResultV2 result = PacketEncodeResultV2.decode(packed);
        check(result.isSuccess(), "success status");
        check(result.bytesWritten() == count, "exact byte count");
        check(result.emittedMask() == mask, "exact unsigned mask");
        expect(IllegalStateException.class, new Runnable() {
            public void run() { result.failure(); }
        });
    }

    private static void malformed(final long packed) {
        expect(IllegalArgumentException.class, new Runnable() {
            public void run() { PacketEncodeResultV2.decode(packed); }
        });
    }

    private static void expect(Class<? extends Throwable> type, Runnable action) {
        try {
            action.run();
        } catch (Throwable error) {
            check(type.isInstance(error), "exception class: " + error);
            return;
        }
        throw new AssertionError("Expected " + type.getName());
    }

    private static void check(boolean condition, String label) {
        checks++;
        if (!condition) throw new AssertionError(label);
    }
}
