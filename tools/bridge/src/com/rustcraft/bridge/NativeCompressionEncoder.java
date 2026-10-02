package com.rustcraft.bridge;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.NettyCompressionEncoder;

/**
 * SRG-runtime variant of the M2C compression encoder (extends the SRG class
 * net.minecraft.network.NettyCompressionEncoder). The notch runtime uses
 * {@link NativeCompressionEncoderNotch} (extends gv) — BOTH are thin
 * adapters over {@link RustCompressionEngine}, which owns all state, modes,
 * the direct zero-heap path, fallbacks, shadow parity and the corpus tap.
 *
 * COMPATIBILITY CONTRACT: subclasses the vanilla encoder because vanilla
 * installs via pipeline.get("compress") + a direct cast, and calls the
 * threshold setter WITHOUT instanceof — subclassing keeps both working.
 * Modes: minecraftrust.native_compress (OFF default / SHADOW /
 * ON_EXPERIMENTAL / OFF_MEASURE) with -Drustcraft.rustCompressionExperiment
 * / rustCompressionShadow aliases. Any native failure falls back to the
 * vanilla JDK path for that packet.
 */
public class NativeCompressionEncoder extends NettyCompressionEncoder {

    public static volatile String RUNTIME_MODE = RustCompressionEngine.RUNTIME_MODE;

    public static void setRuntimeMode(String m) {
        RustCompressionEngine.setRuntimeMode(m);
        RUNTIME_MODE = m.toUpperCase();
    }

    public static final String MODE_OFF = RustCompressionEngine.MODE_OFF;
    public static final String MODE_SHADOW = RustCompressionEngine.MODE_SHADOW;
    public static final String MODE_ON = RustCompressionEngine.MODE_ON;
    public static final String MODE_OFF_MEASURE = RustCompressionEngine.MODE_OFF_MEASURE;

    public static final String CORPUS_PROPERTY = "rustcraft.compressionCorpus";

    // Static-counter aliases: the offline suite (and receipts) address the
    // telemetry through this class; the state lives in the engine.
    public static final java.util.concurrent.atomic.AtomicLong M2C_SHADOW_MATCHES = RustCompressionEngine.M2C_SHADOW_MATCHES;
    public static final java.util.concurrent.atomic.AtomicLong M2C_SHADOW_MISMATCHES = RustCompressionEngine.M2C_SHADOW_MISMATCHES;
    public static final java.util.concurrent.atomic.AtomicLong M2C_ON_VERIFY_MISMATCHES = RustCompressionEngine.M2C_ON_VERIFY_MISMATCHES;
    public static final java.util.concurrent.atomic.AtomicLong M2C_DIRECT_PACKETS = RustCompressionEngine.M2C_DIRECT_PACKETS;
    public static final java.util.concurrent.atomic.AtomicLong M2C_HEAP_PATH_PACKETS = RustCompressionEngine.M2C_HEAP_PATH_PACKETS;
    public static final java.util.concurrent.atomic.AtomicLong M2C_HEAP_PAYLOAD_BYTES = RustCompressionEngine.M2C_HEAP_PAYLOAD_BYTES;
    public static final java.util.concurrent.atomic.AtomicLong M2C_FALLBACKS = RustCompressionEngine.M2C_FALLBACKS;
    public static final java.util.concurrent.atomic.AtomicLong M2C_CTX_CREATED = RustCompressionEngine.M2C_CTX_CREATED;
    public static final java.util.concurrent.atomic.AtomicLong M2C_CTX_FREED = RustCompressionEngine.M2C_CTX_FREED;

    private final RustCompressionEngine engine;

    public NativeCompressionEncoder(int threshold) {
        super(threshold);
        this.engine = new RustCompressionEngine(threshold);
    }

    @Override
    public void func_179299_a(int threshold) {
        engine.onThreshold(threshold);
        super.func_179299_a(threshold);
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        engine.onHandlerRemoved();
        super.handlerRemoved(ctx);
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, ByteBuf msg, ByteBuf out) throws Exception {
        engine.encode(ctx, msg, out, (m, o) -> super.encode(ctx, m, o));
    }

    // test hooks
    public int thresholdForTest() {
        return engine.threshold();
    }

    public boolean isContextLiveForTest() {
        return engine.isContextLive();
    }

    public void freeContextForTest() {
        engine.freeContext();
    }
}
