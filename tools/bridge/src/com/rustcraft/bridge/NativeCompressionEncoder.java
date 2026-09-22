package com.rustcraft.bridge;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.NettyCompressionEncoder;
import net.minecraft.network.PacketBuffer;

import java.util.zip.Deflater;

/**
 * M2C native outbound compression encoder.
 *
 * COMPATIBILITY CONTRACT (verified by javap of NetworkManager.setCompressionThreshold):
 * - SUBCLASSES the vanilla NettyCompressionEncoder, because vanilla installs
 *   via pipeline.get("compress") + direct cast to NettyCompressionEncoder and
 *   calls func_179299_a(threshold) WITHOUT instanceof - subclassing keeps that
 *   cast valid and keeps runtime threshold updates working.
 * - Handler name "compress", placement before "encoder": unchanged (installed
 *   at the same seam by the coremod only when the slot is EMPTY or holds a
 *   vanilla-class handler; an unknown/mod handler => NO INSTALL, vanilla path).
 * - Framing identical to vanilla encode(): below-threshold packets are framed
 *   varint(0)+raw by the VANILLA path (we simply call super); at/above
 *   threshold vanilla writes varint(len) + JDK-deflate. This class overrides
 *   encode() to produce varint(len) + NATIVE deflate for eligible packets in
 *   ON_EXPERIMENTAL, and in SHADOW calls super for the authoritative output
 *   while independently compressing the same plaintext with Rust and
 *   validating inflate-equality (never transmits Rust bytes).
 *
 * Modes via property minecraftrust.native_compress (OFF default / SHADOW /
 * ON_EXPERIMENTAL). Any native failure at any point falls back to super.encode
 * (the vanilla JDK path) for that packet - a native error can never close the
 * connection because vanilla's own error surface is unchanged.
 *
 * EventLoop model: Netty guarantees MessageToByteEncoder.encode runs on the
 * channel's single EventLoop thread; the per-instance CompressionCtx is
 * therefore single-threaded by construction. handlerRemoved (covers
 * disconnect, channel close, and pipeline removal) frees the native context
 * exactly once.
 */
public class NativeCompressionEncoder extends NettyCompressionEncoder {

    public static volatile String RUNTIME_MODE =
            System.getProperty("minecraftrust.native_compress", "OFF");

    public static void setRuntimeMode(String m) {
        RUNTIME_MODE = m.toUpperCase();
    }

    public static final String MODE_OFF = "OFF";
    public static final String MODE_SHADOW = "SHADOW";
    public static final String MODE_ON = "ON_EXPERIMENTAL";
    // M2CP measurement mode: vanilla JDK compression is the wire authority
    // (pure super.encode, identical bytes), but eligible packets are
    // nanoTime-bracketed so the Java arm of the matched OFF/ON study gets
    // the same per-packet counters the ON arm has. No native context is
    // ever created; never used outside the bounded perf study.
    public static final String MODE_OFF_MEASURE = "OFF_MEASURE";

    // ---- metrics (periodic dump via RustCraftCoreMod) ----
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

    private final CompressionCtx ctx = new CompressionCtx();

    private static final java.lang.reflect.Field VANILLA_THRESHOLD;
    static {
        java.lang.reflect.Field f = null;
        try {
            f = NettyCompressionEncoder.class.getDeclaredField("field_179300_a");
            f.setAccessible(true);
        } catch (Throwable ignore) { /* SRG name only; tests verify */ }
        VANILLA_THRESHOLD = f;
    }

    public NativeCompressionEncoder(int threshold) {
        super(threshold);
        thresholdField = threshold;
    }

    @Override
    public void func_179299_a(int threshold) {
        thresholdField = threshold;
        M2C_THRESHOLD_CHANGES.incrementAndGet();
        super.func_179299_a(threshold);
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctxc) throws Exception {
        // disconnect / pipeline removal / channel close: free exactly once
        ctx.free();
        if (ctxWasLive) M2C_CTX_FREED.incrementAndGet();
        super.handlerRemoved(ctxc);
    }

    private boolean ctxWasLive = false;

    /** Threshold accessor mirroring the vanilla field semantics (read-back used by tests). */
    public int threshold() {
        // vanilla stores threshold in a private field; expose via encode behavior probing
        return thresholdField;
    }
    private int thresholdField = -1;
    public void setThresholdField(int t) { thresholdField = t; }

    @Override
    protected void encode(ChannelHandlerContext ctxc, ByteBuf msg, ByteBuf out) throws Exception {
        final String mode = RUNTIME_MODE;
        if (MODE_OFF.equals(mode) || !CompressionCtx.LOADED) {
            super.encode(ctxc, msg, out);
            return;
        }
        // Only the ELIGIBLE (>= threshold) compressed path is ours; vanilla
        // handles sub-threshold framing. Read the plaintext without consuming:
        // duplicate() shares content, own indices.
        int readable = msg.readableBytes();
        if (readable < currentThreshold()) {
            super.encode(ctxc, msg, out); // vanilla varint(0)+raw framing
            return;
        }

        // OFF_MEASURE: vanilla wire, timing only (M2CP matched-study Java arm).
        // Placed before the plaintext copy so the OFF arm pays no extra memcpy.
        if (MODE_OFF_MEASURE.equals(mode)) {
            M2C_BYTES_IN.addAndGet(readable);
            long tJ0 = System.nanoTime();
            super.encode(ctxc, msg, out);
            long tJ1 = System.nanoTime();
            M2C_JAVA_NS.addAndGet(tJ1 - tJ0);
            M2C_JAVA_PACKETS.incrementAndGet();
            M2C_BYTES_JAVA.addAndGet(out.readableBytes());
            return;
        }

        byte[] plaintext = new byte[readable];
        msg.getBytes(msg.readerIndex(), plaintext); // does not move indices
        M2C_BYTES_IN.addAndGet(readable);

        // ON_EXPERIMENTAL: NATIVE-FIRST (no dual compression per packet —
        // directive 5). Vanilla is invoked ONLY on fallback for this packet.
        if (MODE_ON.equals(mode)) {
            if (!this.ctx.ensureCreated()) {
                M2C_FALLBACKS.incrementAndGet();
                M2C_FALLBACK_NOCTX.incrementAndGet();
                super.encode(ctxc, msg, out);
                return;
            }
            if (!ctxWasLive) { ctxWasLive = true; M2C_CTX_CREATED.incrementAndGet(); }
            long tR0 = System.nanoTime();
            byte[] rustOut = this.ctx.compress(plaintext);
            if (rustOut == null || (plaintext.length > 0 && rustOut.length == 0)) {
                M2C_RUST_NS.addAndGet(System.nanoTime() - tR0);
                M2C_FALLBACKS.incrementAndGet();
                M2C_FALLBACK_NATIVE.incrementAndGet();
                super.encode(ctxc, msg, out);
                return;
            }
            M2C_NATIVE_PACKETS.incrementAndGet();
            M2C_NATIVE_BYTES_TX.addAndGet((long) plaintext.length);
            M2C_BYTES_RUST.addAndGet(rustOut.length);
            // timed span = complete per-packet native work (JNI compress +
            // framing writes), matching what OFF_MEASURE brackets around
            // super.encode; the 1-in-256 sampled verify stays OUTSIDE it.
            PacketBuffer pb = new PacketBuffer(out);
            pb.func_150787_b(plaintext.length); // varint(uncompressedSize) - vanilla framing
            out.writeBytes(rustOut);
            M2C_RUST_NS.addAndGet(System.nanoTime() - tR0);
            // sampled safety verification (1-in-256): inflate the transmitted
            // bytes and compare with the retained plaintext (directive 6)
            if ((verifyCounter = (verifyCounter + 1) & 0xFF) == 0) {
                byte[] recovered = inflate(rustOut, plaintext.length);
                M2C_ON_VERIFY_CHECKS.incrementAndGet();
                if (recovered != null && java.util.Arrays.equals(recovered, plaintext)) {
                    M2C_ON_VERIFY_MATCHES.incrementAndGet();
                } else {
                    M2C_ON_VERIFY_MISMATCHES.incrementAndGet();
                }
            }
            return;
        }

        // SHADOW: vanilla reference output first (the wire authority)
        long tJ0 = System.nanoTime();
        io.netty.buffer.ByteBuf vanillaOut = out.alloc().buffer();
        try {
            ByteBuf dup = msg.duplicate(); // own indices = untouched msg
            super.encode(ctxc, dup, vanillaOut);
        } catch (Throwable t) {
            vanillaOut.release();
            M2C_FALLBACKS.incrementAndGet();
            super.encode(ctxc, msg, out); // let vanilla throw identically if truly broken
            return;
        }
        long tJ1 = System.nanoTime();
        M2C_JAVA_NS.addAndGet(tJ1 - tJ0);
        M2C_BYTES_JAVA.addAndGet(vanillaOut.readableBytes());

        // independent native compression of the same plaintext
        if (!this.ctx.ensureCreated()) { M2C_FALLBACKS.incrementAndGet(); }
        else {
            if (!ctxWasLive) { ctxWasLive = true; M2C_CTX_CREATED.incrementAndGet(); }
            long tR0 = System.nanoTime();
            byte[] rustOut = this.ctx.compress(plaintext);
            long tR1 = System.nanoTime();
            M2C_RUST_NS.addAndGet(tR1 - tR0);
            M2C_SHADOW_PACKETS.incrementAndGet();
            if (rustOut == null) {
                M2C_FALLBACKS.incrementAndGet();
            } else {
                M2C_BYTES_RUST.addAndGet(rustOut.length);
                byte[] recovered = inflate(rustOut, plaintext.length);
                if (recovered != null && java.util.Arrays.equals(recovered, plaintext)) {
                    M2C_SHADOW_MATCHES.incrementAndGet();
                } else {
                    M2C_SHADOW_MISMATCHES.incrementAndGet();
                }
            }
        }
        // transmit VANILLA bytes (authoritative)
        out.writeBytes(vanillaOut);
        vanillaOut.release();
    }

    private int verifyCounter = 0;

    private int currentThreshold() {
        return thresholdField;
    }

    /** zlib inflate with known output size; null on any failure. */
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
                + "\nm2c_rust_ns=" + M2C_RUST_NS.get();
    }
}
