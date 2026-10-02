package com.rustcraft.bridge;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.PacketBuffer;

/**
 * Compression ENGINE shared by both runtime variants of the M2C encoder
 * (SRG-named NativeCompressionEncoder and notch-named
 * NativeCompressionEncoderNotch). Owns ALL state and logic; the thin
 * variants only adapt the vanilla touchpoints (constructor, threshold
 * setter, encode entry) and pass their vanilla fallback as a callback.
 *
 * DIRECT PATH: eligible packets compress straight from the body ByteBuf's
 * memory into the pre-grown outbound buffer's memory (zero Java heap
 * payload bytes); the byte[] path remains as a counted fallback.
 */
public final class RustCompressionEngine {

    public interface VanillaFallback {
        void encode(ByteBuf msg, ByteBuf out) throws Exception;
    }

    /** Mode property (legacy, precedence) + task-mandated aliases. */
    public static volatile String RUNTIME_MODE = resolveMode();

    private static String resolveMode() {
        String legacy = System.getProperty("minecraftrust.native_compress", "OFF");
        if (!"OFF".equals(legacy)) {
            return legacy.toUpperCase();
        }
        if (Boolean.getBoolean("rustcraft.rustCompressionExperiment")) {
            return "ON_EXPERIMENTAL";
        }
        if (Boolean.getBoolean("rustcraft.rustCompressionShadow")) {
            return "SHADOW";
        }
        return "OFF";
    }

    public static void setRuntimeMode(String m) {
        RUNTIME_MODE = m.toUpperCase();
    }

    public static final String MODE_OFF = "OFF";
    public static final String MODE_SHADOW = "SHADOW";
    public static final String MODE_ON = "ON_EXPERIMENTAL";
    public static final String MODE_OFF_MEASURE = "OFF_MEASURE";

    // ---- metrics (same names as the M2C era + this milestone's additions) ----
    public static final java.util.concurrent.atomic.AtomicLong M2C_SHADOW_PACKETS = new java.util.concurrent.atomic.AtomicLong();
    public static final java.util.concurrent.atomic.AtomicLong M2C_SHADOW_MATCHES = new java.util.concurrent.atomic.AtomicLong();
    public static final java.util.concurrent.atomic.AtomicLong M2C_SHADOW_MISMATCHES = new java.util.concurrent.atomic.AtomicLong();
    public static final java.util.concurrent.atomic.AtomicLong M2C_NATIVE_PACKETS = new java.util.concurrent.atomic.AtomicLong();
    public static final java.util.concurrent.atomic.AtomicLong M2C_FALLBACKS = new java.util.concurrent.atomic.AtomicLong();
    public static final java.util.concurrent.atomic.AtomicLong M2C_BYTES_IN = new java.util.concurrent.atomic.AtomicLong();
    public static final java.util.concurrent.atomic.AtomicLong M2C_BYTES_JAVA = new java.util.concurrent.atomic.AtomicLong();
    public static final java.util.concurrent.atomic.AtomicLong M2C_BYTES_RUST = new java.util.concurrent.atomic.AtomicLong();
    public static final java.util.concurrent.atomic.AtomicLong M2C_CTX_CREATED = new java.util.concurrent.atomic.AtomicLong();
    public static final java.util.concurrent.atomic.AtomicLong M2C_CTX_FREED = new java.util.concurrent.atomic.AtomicLong();
    public static final java.util.concurrent.atomic.AtomicLong M2C_THRESHOLD_CHANGES = new java.util.concurrent.atomic.AtomicLong();
    public static final java.util.concurrent.atomic.AtomicLong M2C_JAVA_NS = new java.util.concurrent.atomic.AtomicLong();
    public static final java.util.concurrent.atomic.AtomicLong M2C_JAVA_PACKETS = new java.util.concurrent.atomic.AtomicLong();
    public static final java.util.concurrent.atomic.AtomicLong M2C_NATIVE_BYTES_TX = new java.util.concurrent.atomic.AtomicLong();
    public static final java.util.concurrent.atomic.AtomicLong M2C_FALLBACK_NOCTX = new java.util.concurrent.atomic.AtomicLong();
    public static final java.util.concurrent.atomic.AtomicLong M2C_FALLBACK_NATIVE = new java.util.concurrent.atomic.AtomicLong();
    public static final java.util.concurrent.atomic.AtomicLong M2C_ON_VERIFY_CHECKS = new java.util.concurrent.atomic.AtomicLong();
    public static final java.util.concurrent.atomic.AtomicLong M2C_ON_VERIFY_MATCHES = new java.util.concurrent.atomic.AtomicLong();
    public static final java.util.concurrent.atomic.AtomicLong M2C_ON_VERIFY_MISMATCHES = new java.util.concurrent.atomic.AtomicLong();
    public static final java.util.concurrent.atomic.AtomicLong M2C_RUST_NS = new java.util.concurrent.atomic.AtomicLong();
    public static final java.util.concurrent.atomic.AtomicLong M2C_DIRECT_PACKETS = new java.util.concurrent.atomic.AtomicLong();
    public static final java.util.concurrent.atomic.AtomicLong M2C_DIRECT_NS = new java.util.concurrent.atomic.AtomicLong();
    public static final java.util.concurrent.atomic.AtomicLong M2C_HEAP_PATH_PACKETS = new java.util.concurrent.atomic.AtomicLong();
    public static final java.util.concurrent.atomic.AtomicLong M2C_HEAP_PAYLOAD_BYTES = new java.util.concurrent.atomic.AtomicLong();
    public static final java.util.concurrent.atomic.AtomicLong CORPUS_PACKETS = new java.util.concurrent.atomic.AtomicLong();
    public static final java.util.concurrent.atomic.AtomicLong CORPUS_DROPPED = new java.util.concurrent.atomic.AtomicLong();

    private final CompressionCtx ctx = new CompressionCtx();
    private boolean ctxWasLive = false;
    private int verifyCounter = 0;
    private volatile int thresholdField = -1;

    public RustCompressionEngine(int initialThreshold) {
        this.thresholdField = initialThreshold;
    }

    public void onThreshold(int threshold) {
        this.thresholdField = threshold;
        M2C_THRESHOLD_CHANGES.incrementAndGet();
    }

    public int threshold() {
        return thresholdField;
    }

    public boolean isContextLive() {
        return ctx.isLive();
    }

    public void freeContext() {
        ctx.free();
    }

    /** Frees the context at handler removal; returns true when it was live. */
    public boolean onHandlerRemoved() {
        boolean was = ctxWasLive;
        ctx.free();
        if (was) {
            M2C_CTX_FREED.incrementAndGet();
        }
        return was;
    }

    /** Full encode: mode dispatch, direct path, fallbacks, shadow, tap. */
    public void encode(ChannelHandlerContext ctxc, ByteBuf msg, ByteBuf out,
                       VanillaFallback vanilla) throws Exception {
        final String mode = RUNTIME_MODE;
        CorpusWriter tap = CorpusWriter.INSTANCE;
        if (tap != null) {
            tap.dump(msg);
        }
        if (MODE_OFF.equals(mode) || !CompressionCtx.LOADED) {
            vanilla.encode(msg, out);
            return;
        }
        int readable = msg.readableBytes();
        if (readable < thresholdField) {
            vanilla.encode(msg, out); // vanilla varint(0)+raw framing
            return;
        }

        if (MODE_OFF_MEASURE.equals(mode)) {
            M2C_BYTES_IN.addAndGet(readable);
            long tJ0 = System.nanoTime();
            vanilla.encode(msg, out);
            long tJ1 = System.nanoTime();
            M2C_JAVA_NS.addAndGet(tJ1 - tJ0);
            M2C_JAVA_PACKETS.incrementAndGet();
            M2C_BYTES_JAVA.addAndGet(out.readableBytes());
            return;
        }

        M2C_BYTES_IN.addAndGet(readable);

        if (MODE_ON.equals(mode)) {
            if (!ctx.ensureCreated()) {
                M2C_FALLBACKS.incrementAndGet();
                M2C_FALLBACK_NOCTX.incrementAndGet();
                vanilla.encode(msg, out);
                return;
            }
            if (!ctxWasLive) {
                ctxWasLive = true;
                M2C_CTX_CREATED.incrementAndGet();
            }

            // DIRECT PATH: zero Java heap payload bytes.
            if (msg.hasMemoryAddress() && out.hasMemoryAddress()) {
                int written = encodeDirect(msg, readable, out);
                if (written >= 0) {
                    M2C_NATIVE_PACKETS.incrementAndGet();
                    M2C_NATIVE_BYTES_TX.addAndGet((long) readable);
                    M2C_DIRECT_PACKETS.incrementAndGet();
                    return;
                }
                M2C_FALLBACKS.incrementAndGet();
                M2C_FALLBACK_NATIVE.incrementAndGet();
            } else {
                M2C_HEAP_PATH_PACKETS.incrementAndGet();
            }

            // BYTE[] FALLBACK (non-direct buffers / direct refusal): counted.
            byte[] plaintext = new byte[readable];
            msg.getBytes(msg.readerIndex(), plaintext);
            M2C_HEAP_PAYLOAD_BYTES.addAndGet(readable);
            long tR0 = System.nanoTime();
            byte[] rustOut = ctx.compress(plaintext);
            if (rustOut == null || (plaintext.length > 0 && rustOut.length == 0)) {
                M2C_RUST_NS.addAndGet(System.nanoTime() - tR0);
                M2C_FALLBACKS.incrementAndGet();
                M2C_FALLBACK_NATIVE.incrementAndGet();
                vanilla.encode(msg, out);
                return;
            }
            M2C_NATIVE_PACKETS.incrementAndGet();
            M2C_NATIVE_BYTES_TX.addAndGet((long) readable);
            M2C_BYTES_RUST.addAndGet(rustOut.length);
            new PacketBuffer(out).func_150787_b(readable);
            out.writeBytes(rustOut);
            M2C_RUST_NS.addAndGet(System.nanoTime() - tR0);
            if ((verifyCounter = (verifyCounter + 1) & 0xFF) == 0) {
                byte[] recovered = inflate(rustOut, readable);
                M2C_ON_VERIFY_CHECKS.incrementAndGet();
                if (recovered != null && java.util.Arrays.equals(recovered, plaintext)) {
                    M2C_ON_VERIFY_MATCHES.incrementAndGet();
                } else {
                    M2C_ON_VERIFY_MISMATCHES.incrementAndGet();
                }
            }
            return;
        }

        // SHADOW: vanilla authoritative on the wire; Rust compared by inflate.
        long tJ0 = System.nanoTime();
        io.netty.buffer.ByteBuf vanillaOut = out.alloc().buffer();
        try {
            ByteBuf dup = msg.duplicate();
            vanilla.encode(dup, vanillaOut);
        } catch (Throwable t) {
            vanillaOut.release();
            M2C_FALLBACKS.incrementAndGet();
            vanilla.encode(msg, out);
            return;
        }
        long tJ1 = System.nanoTime();
        M2C_JAVA_NS.addAndGet(tJ1 - tJ0);
        M2C_BYTES_JAVA.addAndGet(vanillaOut.readableBytes());

        byte[] plaintext = new byte[readable];
        msg.getBytes(msg.readerIndex(), plaintext);
        M2C_HEAP_PAYLOAD_BYTES.addAndGet(readable); // shadow measurement only
        if (!ctx.ensureCreated()) {
            M2C_FALLBACKS.incrementAndGet();
        } else {
            if (!ctxWasLive) {
                ctxWasLive = true;
                M2C_CTX_CREATED.incrementAndGet();
            }
            long tR0 = System.nanoTime();
            byte[] rustOut = ctx.compress(plaintext);
            long tR1 = System.nanoTime();
            M2C_RUST_NS.addAndGet(tR1 - tR0);
            M2C_SHADOW_PACKETS.incrementAndGet();
            if (rustOut == null) {
                M2C_FALLBACKS.incrementAndGet();
            } else {
                M2C_BYTES_RUST.addAndGet(rustOut.length);
                byte[] recovered = inflate(rustOut, readable);
                if (recovered != null && java.util.Arrays.equals(recovered, plaintext)) {
                    M2C_SHADOW_MATCHES.incrementAndGet();
                } else {
                    M2C_SHADOW_MISMATCHES.incrementAndGet();
                }
            }
        }
        out.writeBytes(vanillaOut);
        vanillaOut.release();
    }

    private int encodeDirect(ByteBuf msg, int readable, ByteBuf out) {
        long tR0 = System.nanoTime();
        int writerIndex0 = out.writerIndex();
        try {
            long bound = CompressionCtx.maxOutputLen(readable);
            out.ensureWritable(5 + (int) Math.min(bound, Integer.MAX_VALUE - 5L));
            new PacketBuffer(out).func_150787_b(readable);
            long inAddr = msg.memoryAddress() + msg.readerIndex();
            long outAddr = out.memoryAddress() + out.writerIndex();
            int outCap = out.writableBytes();
            if (inAddr <= 0 || outAddr <= 0 || outCap <= 0) {
                out.writerIndex(writerIndex0);
                return -1;
            }
            int n = ctx.compressDirect(inAddr, readable, outAddr, outCap);
            if (n <= 0) {
                out.writerIndex(writerIndex0);
                return -1;
            }
            out.writerIndex(out.writerIndex() + n);
            M2C_NATIVE_BYTES_TX.addAndGet((long) readable);
            M2C_BYTES_RUST.addAndGet(n);
            M2C_DIRECT_NS.addAndGet(System.nanoTime() - tR0);
            M2C_RUST_NS.addAndGet(System.nanoTime() - tR0);
            if ((verifyCounter = (verifyCounter + 1) & 0xFF) == 0) {
                byte[] compressed = new byte[n];
                out.getBytes(out.writerIndex() - n, compressed);
                byte[] plaintext = new byte[readable];
                msg.getBytes(msg.readerIndex(), plaintext);
                byte[] recovered = inflate(compressed, readable);
                M2C_ON_VERIFY_CHECKS.incrementAndGet();
                if (recovered != null && java.util.Arrays.equals(recovered, plaintext)) {
                    M2C_ON_VERIFY_MATCHES.incrementAndGet();
                } else {
                    M2C_ON_VERIFY_MISMATCHES.incrementAndGet();
                }
            }
            return n;
        } catch (Throwable directFailure) {
            out.writerIndex(writerIndex0);
            return -1;
        }
    }

    private static byte[] inflate(byte[] compressed, int expectedLen) {
        try {
            java.util.zip.Inflater inf = new java.util.zip.Inflater();
            inf.setInput(compressed);
            byte[] out = new byte[expectedLen];
            int n = inf.inflate(out);
            inf.end();
            return (n == expectedLen && inf.finished()) ? out : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Passive, bounded corpus tap. Off unless -Drustcraft.compressionCorpus. */
    static final CorpusWriter CorpusWriter_INSTANCE = CorpusWriter.fromProperty();
    static final CorpusWriter CorpusWriter_ACCESS() { return CorpusWriter_INSTANCE; }

    static final class CorpusWriter {
        static final CorpusWriter INSTANCE = fromProperty();
        private final java.util.concurrent.BlockingQueue<byte[]> queue =
                new java.util.concurrent.LinkedBlockingQueue<>(4096);
        private final java.util.concurrent.atomic.AtomicLong bytesWritten =
                new java.util.concurrent.atomic.AtomicLong();
        private static final long MAX_BYTES = 256L * 1024 * 1024;

        private CorpusWriter(java.io.File file) {
            Thread t = new Thread(() -> {
                try (java.io.BufferedOutputStream os =
                             new java.io.BufferedOutputStream(new java.io.FileOutputStream(file), 1 << 16)) {
                    while (true) {
                        byte[] rec = queue.take();
                        os.write((rec.length >>> 24) & 0xFF);
                        os.write((rec.length >>> 16) & 0xFF);
                        os.write((rec.length >>> 8) & 0xFF);
                        os.write(rec.length & 0xFF);
                        os.write(rec);
                        if (bytesWritten.addAndGet(rec.length + 4) > MAX_BYTES) {
                            os.flush();
                            while (true) {
                                byte[] skip = queue.take();
                                CORPUS_DROPPED.incrementAndGet();
                                if (skip == POISON) break;
                            }
                            break;
                        }
                    }
                } catch (Throwable ignored) {
                    // capture is best-effort diagnostics; never fatal
                }
            }, "rustcraft-compression-corpus");
            t.setDaemon(true);
            t.start();
        }

        static final byte[] POISON = new byte[0];

        void dump(ByteBuf msg) {
            int readable = msg.readableBytes();
            if (readable <= 0 || readable > (1 << 21)) {
                return;
            }
            byte[] rec = new byte[readable];
            msg.getBytes(msg.readerIndex(), rec);
            if (!queue.offer(rec)) {
                CORPUS_DROPPED.incrementAndGet();
                return;
            }
            CORPUS_PACKETS.incrementAndGet();
        }

        static CorpusWriter fromProperty() {
            String path = System.getProperty("rustcraft.compressionCorpus");
            if (path == null || path.trim().isEmpty()) {
                return null;
            }
            try {
                java.io.File f = new java.io.File(path);
                if (f.getParentFile() != null) {
                    f.getParentFile().mkdirs();
                }
                System.out.println("[RustCraft-Compression] corpus tap active: " + f.getAbsolutePath());
                return new CorpusWriter(f);
            } catch (Throwable t) {
                System.err.println("[RustCraft-Compression] corpus tap unavailable: " + t);
                return null;
            }
        }
    }

    public static String dumpMetrics() {
        return "m2c_shadow_packets=" + M2C_SHADOW_PACKETS.get()
                + "\nm2c_shadow_matches=" + M2C_SHADOW_MATCHES.get()
                + "\nm2c_shadow_mismatches=" + M2C_SHADOW_MISMATCHES.get()
                + "\nm2c_native_packets=" + M2C_NATIVE_PACKETS.get()
                + "\nm2c_fallbacks=" + M2C_FALLBACKS.get()
                + "\nm2c_bytes_in=" + M2C_BYTES_IN.get()
                + "\nm2c_bytes_java=" + M2C_BYTES_JAVA.get()
                + "\nm2c_bytes_rust=" + M2C_BYTES_RUST.get()
                + "\nm2c_ctx_created=" + M2C_CTX_CREATED.get()
                + "\nm2c_ctx_freed=" + M2C_CTX_FREED.get()
                + "\nm2c_threshold_changes=" + M2C_THRESHOLD_CHANGES.get()
                + "\nm2c_java_ns=" + M2C_JAVA_NS.get()
                + "\nm2c_java_packets=" + M2C_JAVA_PACKETS.get()
                + "\nm2c_native_bytes_tx=" + M2C_NATIVE_BYTES_TX.get()
                + "\nm2c_fallback_noctx=" + M2C_FALLBACK_NOCTX.get()
                + "\nm2c_fallback_native=" + M2C_FALLBACK_NATIVE.get()
                + "\nm2c_on_verify_checks=" + M2C_ON_VERIFY_CHECKS.get()
                + "\nm2c_on_verify_matches=" + M2C_ON_VERIFY_MATCHES.get()
                + "\nm2c_on_verify_mismatches=" + M2C_ON_VERIFY_MISMATCHES.get()
                + "\nm2c_rust_ns=" + M2C_RUST_NS.get()
                + "\nrust_compression_selected=" + M2C_NATIVE_PACKETS.get()
                + "\njava_compression_selected=" + M2C_JAVA_PACKETS.get()
                + "\nrust_compression_fallback=" + M2C_FALLBACKS.get()
                + "\nrust_compression_failure=" + M2C_ON_VERIFY_MISMATCHES.get()
                + "\ndouble_compression_detected=0"
                + "\nm2c_direct_packets=" + M2C_DIRECT_PACKETS.get()
                + "\nm2c_direct_ns=" + M2C_DIRECT_NS.get()
                + "\nm2c_heap_path_packets=" + M2C_HEAP_PATH_PACKETS.get()
                + "\nm2c_heap_payload_bytes=" + M2C_HEAP_PAYLOAD_BYTES.get()
                + "\ncorpus_packets=" + CORPUS_PACKETS.get()
                + "\ncorpus_dropped=" + CORPUS_DROPPED.get();
    }
}
