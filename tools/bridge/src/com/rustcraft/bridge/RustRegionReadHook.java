package com.rustcraft.bridge;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicLong;

/**
 * LIVE region READ authority hook (RUST_REGION_READ_DECOMPRESSION_AUTHORITY).
 *
 * Called from bytecode injected into RegionFile.func_76704_a (notch
 * ayj.a(II)Ljava/io/DataInputStream;) — the synchronized read seam:
 *
 * <ul>
 *   <li>{@link #regionRead} at method ENTRY (mode ON_EXPERIMENTAL): returns a
 *       DataInputStream over Rust's fully-validated decompressed bytes, or
 *       null to run the vanilla body (missing chunk, any Rust failure —
 *       fail closed BEFORE any partial stream could exist);</li>
 *   <li>{@link #regionReadEnter} + {@link #shadowWrap} (mode SHADOW): Rust
 *       independently reads/decompresses the same chunk; the vanilla-
 *       authoritative stream is wrapped so that, as the caller consumes it,
 *       the decompressed bytes are compared against Rust's — comparison at
 *       real read timing with zero effect on the bytes the caller sees;</li>
 *   <li>{@link #regionFileClosing}: frees the native reader handle.</li>
 * </ul>
 *
 * FAIL-CLOSED RULE (goal §15/§32): ON mode admits a read ONLY when Rust
 * decompressed and validated the FULL record. Any failure (missing handled
 * as null, corrupt, unsupported compression, oversized, IO) falls back to
 * the untouched vanilla body — including lazy vanilla failures for corrupt
 * streams, exactly as unhooked. partial_stream_attempts = 0 by construction.
 *
 * NBT SEMANTICS STAY IN JAVA (goal §7): Rust hands over raw uncompressed
 * NBT bytes; CompressedStreamTools/DataFixer/Chunk construction/Forge
 * callbacks are untouched. No NBT parsing happens in Rust.
 */
public final class RustRegionReadHook {

    public static final boolean ENABLED =
            Boolean.getBoolean("rustcraft.regionReadExperiment");
    public static final String MODE =
            System.getProperty("rustcraft.regionReadMode", "SHADOW");
    public static final boolean SHADOW = !"ON_EXPERIMENTAL".equals(MODE);
    public static final int CAP =
            Integer.getInteger("rustcraft.regionReadCap", Integer.MAX_VALUE);

    // goal §32 counters
    public static final AtomicLong READ_SELECTED = new AtomicLong();
    public static final AtomicLong READ_SUCCESS = new AtomicLong();
    public static final AtomicLong READ_FAILURE = new AtomicLong();
    public static final AtomicLong JAVA_READ_FALLBACK = new AtomicLong();
    public static final AtomicLong READ_MISSING = new AtomicLong();
    public static final AtomicLong READ_CORRUPT = new AtomicLong();
    public static final AtomicLong READ_UNSUPPORTED_COMPRESSION = new AtomicLong();
    public static final AtomicLong READ_STALE_GENERATION = new AtomicLong();
    public static final AtomicLong PARTIAL_STREAM_ATTEMPTS = new AtomicLong();
    public static final AtomicLong SHADOW_COMPARED = new AtomicLong();
    public static final AtomicLong SHADOW_MISMATCH = new AtomicLong();
    public static final AtomicLong ERRORS = new AtomicLong();
    public static volatile String LAST_MISMATCH = "";

    /** Per-RegionFile reader state stored in the injected public field. */
    public static final class State {
        public final RegionReadCtx ctx = new RegionReadCtx();
        /** Java ticket: advances on every observed read for staleness stats. */
        public final AtomicLongArrayCompat tickets = new AtomicLongArrayCompat();
        public volatile boolean capped;
        public final String regionPath;
        State(String regionPath) { this.regionPath = regionPath; }
    }

    /** Minimal long-array with bounds-safe get/set (1024 slots). */
    public static final class AtomicLongArrayCompat {
        private final java.util.concurrent.atomic.AtomicLongArray arr =
                new java.util.concurrent.atomic.AtomicLongArray(1024);
        public void increment(int i) { arr.incrementAndGet(i & 1023); }
    }

    private RustRegionReadHook() { }

    // ------------------------------------------------------------------
    // injected call sites
    // ------------------------------------------------------------------

    /**
     * ON mode, func_76704_a entry. Returns a DataInputStream over the fully
     * validated decompressed record, or null to run the vanilla body.
     */
    public static DataInputStream regionRead(Object regionFile, int x, int z) {
        if (!ENABLED || SHADOW) return null;
        try {
            State st = (State) state(regionFile);
            if (st == null) return null;
            if (st.capped || READ_SELECTED.get() >= CAP) {
                st.capped = true;
                JAVA_READ_FALLBACK.incrementAndGet();
                return null;
            }
            READ_SELECTED.incrementAndGet();
            byte[] bytes = st.ctx.readFully(x & 31, z & 31);
            if (bytes != null) {
                READ_SUCCESS.incrementAndGet();
                // CompressedStreamTools cannot distinguish this stream from
                // the vanilla one for valid chunks (goal §17): same bytes,
                // DataInputStream, exact EOF semantics.
                return new DataInputStream(new ByteArrayInputStream(bytes));
            }
            // classify the failure for the counters, then fail closed
            READ_FAILURE.incrementAndGet();
            JAVA_READ_FALLBACK.incrementAndGet();
            classify(st.ctx.lastStatus);
            return null; // vanilla body runs (incl. its lazy corrupt handling)
        } catch (Throwable t) {
            ERRORS.incrementAndGet();
            return null;
        }
    }

    /**
     * SHADOW mode, func_76704_a entry: read the chunk through Rust NOW (same
     * file state, same timing as the vanilla body about to run) and stash
     * the Rust bytes; returns false — the vanilla body always runs and its
     * returned stream is wrapped by {@link #shadowWrap} for comparison.
     * NEVER modifies the caller-visible stream.
     */
    public static boolean regionReadEnter(Object regionFile, int x, int z) {
        if (!ENABLED || !SHADOW) return false;
        try {
            State st = (State) state(regionFile);
            if (st == null) return false;
            READ_SELECTED.incrementAndGet();
            byte[] bytes = st.ctx.readFully(x & 31, z & 31);
            if (bytes == null) {
                READ_FAILURE.incrementAndGet();
                classify(st.ctx.lastStatus);
                return false;
            }
            Shadow sh = new Shadow();
            sh.x = x & 31;
            sh.z = z & 31;
            sh.expected = bytes;
            sh.path = st.regionPath;
            LAST.set(sh);
            return false; // always run the vanilla body
        } catch (Throwable t) {
            ERRORS.incrementAndGet();
            return false;
        }
    }

    // thread-confined shadow state: the seam is synchronized per RegionFile
    // and the comparison completes before the caller's next read of a
    // different chunk, so a single slot suffices
    static final ThreadLocal<Shadow> LAST = new ThreadLocal<>();
    static final class Shadow {
        int x, z;
        byte[] expected;
        String path = "?";
        boolean compared;
    }

    private static void classify(int status) {
        if (status == RegionReadCtx.ST_MISSING) READ_MISSING.incrementAndGet();
        else if (status == RegionReadCtx.ST_CORRUPT_ENTRY) READ_CORRUPT.incrementAndGet();
        else if (status == RegionReadCtx.ST_UNSUPPORTED_COMPRESSION) READ_UNSUPPORTED_COMPRESSION.incrementAndGet();
    }

    /**
     * SHADOW mode: wrap the vanilla-authoritative stream so consumption
     * compares it byte-for-byte against Rust's decompressed bytes. Returns a
     * stream the caller cannot distinguish from the vanilla one for valid
     * chunks (same bytes, same read semantics).
     */
    public static DataInputStream shadowWrap(DataInputStream vanilla, Object regionFile, int x, int z) {
        if (!ENABLED || !SHADOW || vanilla == null) return vanilla;
        Shadow sh = LAST.get();
        if (sh == null || sh.compared || sh.x != (x & 31) || sh.z != (z & 31)) {
            return vanilla; // no Rust snapshot for this read: vanilla stands
        }
        sh.compared = true;
        return new DataInputStream(new ComparingStream(vanilla, sh.expected, x, z, sh.path));
    }

    /**
     * Tee-comparing stream (goal §9): every byte the caller reads is checked
     * against Rust's decompressed bytes; EOF marks the comparison point.
     * The caller always receives the VANILLA bytes; mismatches are recorded,
     * never corrected or suppressed.
     */
    static final class ComparingStream extends InputStream {
        private final DataInputStream vanilla;
        private final byte[] expected;
        private final int x, z;
        private final String path;
        private int pos;
        private boolean mismatched;

        ComparingStream(DataInputStream vanilla, byte[] expected, int x, int z, String path) {
            this.vanilla = vanilla;
            this.expected = expected;
            this.x = x;
            this.z = z;
            this.path = path;
        }

        private void check(int b, int index) {
            if (mismatched || index >= expected.length) {
                if (!mismatched && index >= expected.length) {
                    recordMismatch("rust-shorter", index, b & 0xFF);
                }
                return;
            }
            if ((b & 0xFF) != (expected[index] & 0xFF)) {
                recordMismatch("byte", index, b & 0xFF);
            }
        }

        private void recordMismatch(String kind, int index, int got) {
            mismatched = true;
            SHADOW_MISMATCH.incrementAndGet();
            int want = index < expected.length ? (expected[index] & 0xFF) : -1;
            LAST_MISMATCH = "kind=" + kind + " x=" + x + " z=" + z
                    + " divergence=" + index + " vanilla=" + got + " rust=" + want
                    + " rustLen=" + expected.length
                    + " file=" + path;
        }

        @Override public int read() throws IOException {
            int b = vanilla.read();
            if (b >= 0) {
                check(b, pos);
                pos++;
            } else {
                finish();
            }
            return b;
        }

        @Override public int read(byte[] b, int off, int len) throws IOException {
            int n = vanilla.read(b, off, len);
            if (n > 0) {
                for (int i = 0; i < n; i++) {
                    check(b[off + i], pos + i);
                }
                pos += n;
            } else if (n < 0) {
                finish();
            }
            return n;
        }

        private boolean finished;

        private void finish() {
            if (finished) return;
            finished = true;
            if (!mismatched && pos != expected.length) {
                recordMismatch("length", pos, -1);
            }
            if (!mismatched) {
                SHADOW_COMPARED.incrementAndGet();
            }
        }

        @Override public int available() throws IOException { return vanilla.available(); }
        @Override public void close() throws IOException {
            // drain any unread tail so the comparison completes even for
            // callers that stop reading early (CompressedStreamTools reads
            // to EOF for valid chunks; close() makes shadow bookkeeping total)
            try {
                byte[] sink = new byte[8192];
                while (true) {
                    int n = vanilla.read(sink);
                    if (n < 0) break;
                    for (int i = 0; i < n; i++) check(sink[i], pos + i);
                    pos += n;
                }
                finish();
            } catch (Throwable ignore) { }
            vanilla.close();
        }
    }

    /** func_76708_c (close): free the native reader exactly once. */
    public static void regionFileClosing(Object regionFile) {
        if (!ENABLED) return;
        try {
            Object st = peekState(regionFile);
            if (st != null) ((State) st).ctx.free();
        } catch (Throwable t) {
            ERRORS.incrementAndGet();
        }
    }

    // ------------------------------------------------------------------
    // per-instance state (injected rustcraft$rrState field)
    // ------------------------------------------------------------------

    private static volatile Field F_STATE;

    private static Field stateField(Class<?> c) {
        Field f = F_STATE;
        if (f == null) {
            try {
                f = c.getDeclaredField("rustcraft$rrState");
                f.setAccessible(true);
                F_STATE = f;
            } catch (Throwable t) {
                return null;
            }
        }
        return f;
    }

    private static Object peekState(Object regionFile) throws IllegalAccessException {
        Field f = stateField(regionFile.getClass());
        return f == null ? null : f.get(regionFile);
    }

    private static Object state(Object regionFile) throws IllegalAccessException {
        Field f = stateField(regionFile.getClass());
        if (f == null) return null;
        Object st = f.get(regionFile);
        if (st == null) {
            st = makeState(regionFile);
            if (st != null) f.set(regionFile, st);
        }
        return st;
    }

    static State makeState(Object regionFile) {
        if (!ENABLED) return null;
        try {
            java.io.File file = regionFilePath(regionFile);
            if (file == null) return null;
            State st = new State(file.getAbsolutePath());
            if (!st.ctx.ensureCreated(file.getAbsolutePath())) return null;
            return st;
        } catch (Throwable t) {
            ERRORS.incrementAndGet();
            return null;
        }
    }

    private static volatile Field F_FILE;

    static java.io.File regionFilePath(Object regionFile) {
        try {
            Field f = F_FILE;
            if (f == null) {
                for (String n : new String[]{"field_76718_b", "fileName"}) {
                    try {
                        Field cand = regionFile.getClass().getDeclaredField(n);
                        cand.setAccessible(true);
                        if (cand.getType() == java.io.File.class) {
                            f = cand;
                            F_FILE = f;
                            break;
                        }
                    } catch (NoSuchFieldException ignore) { }
                }
                if (f == null) return null;
            }
            return (java.io.File) f.get(regionFile);
        } catch (Throwable t) {
            return null;
        }
    }

    public static String dumpMetrics() {
        return "regionRead.hook enabled=" + ENABLED + " mode=" + MODE + " cap=" + CAP
                + " readSelected=" + READ_SELECTED.get()
                + " readSuccess=" + READ_SUCCESS.get()
                + " readFailure=" + READ_FAILURE.get()
                + " javaReadFallback=" + JAVA_READ_FALLBACK.get()
                + " missing=" + READ_MISSING.get()
                + " corrupt=" + READ_CORRUPT.get()
                + " unsupportedCompression=" + READ_UNSUPPORTED_COMPRESSION.get()
                + " staleGeneration=" + READ_STALE_GENERATION.get()
                + " partialStreamAttempts=" + PARTIAL_STREAM_ATTEMPTS.get()
                + " shadowCompared=" + SHADOW_COMPARED.get()
                + " shadowMismatch=" + SHADOW_MISMATCH.get()
                + " errors=" + ERRORS.get()
                + " directToHeapBytes=" + RegionReadCtx.COPY_DIRECT_TO_HEAP_BYTES.get()
                + " lastMismatch=" + LAST_MISMATCH;
    }
}
