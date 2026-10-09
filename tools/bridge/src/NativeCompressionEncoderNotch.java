import com.rustcraft.bridge.RustCompressionEngine;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;

/**
 * NOTCH-runtime variant of the M2C compression encoder: extends the notch
 * class gv (1.12.2 NettyCompressionEncoder; Deflater scan verified) so the
 * vanilla cast in NetworkManager's setCompression site stays valid and
 * runtime threshold updates (gv.a(int)) dispatch here.
 *
 * Deliberately in the DEFAULT package: gv lives in the unnamed package and
 * the Java language forbids named-package classes from extending it. Nothing
 * imports this class by name — the NetworkManagerCompressionTransformer
 * rewrites the `new gv` construction site to this binary name
 * ("NativeCompressionEncoderNotch") on notch runtimes, and launchwrapper
 * loads it from the parent classpath (campaign jar on -cp).
 *
 * The engine (state, modes, direct zero-heap path, fallbacks, shadow parity,
 * corpus tap) lives in RustCompressionEngine; this class only adapts the
 * notch touchpoints: constructor gv(int), setter a(int), encoder
 * a(ctx,ByteBuf,ByteBuf).
 */
public class NativeCompressionEncoderNotch extends gv {

    private final RustCompressionEngine engine;

    public NativeCompressionEncoderNotch(int threshold) {
        super(threshold);
        this.engine = new RustCompressionEngine(threshold);
    }

    @Override
    public void a(int threshold) {
        engine.onThreshold(threshold);
        super.a(threshold);
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        engine.onHandlerRemoved();
        super.handlerRemoved(ctx);
    }

    @Override
    protected void a(ChannelHandlerContext ctx, ByteBuf msg, ByteBuf out) throws Exception {
        engine.encode(ctx, msg, out, (m, o) -> super.a(ctx, m, o));
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
