package com.rustcraft.bridge;

import java.util.Arrays;
import java.util.Random;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.embedded.EmbeddedChannel;

/**
 * M-CK4.2 §2 — the COMPLETE frame-length boundary matrix around the 3-byte
 * outer VarInt encoder limit (MAX_FRAME_BODY = 0x1FFFFF = 2,097,151).
 *
 * Distinct limit domains (kept separate on purpose):
 *  - ENCODER wire limit: the installed NettyVarint21FrameEncoder throws
 *    IllegalArgumentException("unable to fit <n> into 3") when the outer
 *    VarInt needs > 3 bytes (verified by disassembly); the Rust engine
 *    rejects with ERR_TOO_LARGE. THIS file tests that domain.
 *  - RESOURCE policy: the 16 MiB retry-allocation ceiling in
 *    OutboundFrameCtx.policyLimit (tested in MCK4Validation.retryInjectionGate).
 *  - RECEIVER limits: decoder-side maximum sizes — NOT exercised here
 *    (NOT_RUN: offline gates have no live receiver; documented, not silently
 *    assumed equivalent).
 *
 * Every case asserts BOTH the installed-Java outcome AND the Rust outcome.
 * Accepted frames must independently decode to the original body (and where
 * the framing is deterministic — disabled and passthrough — Java and Rust
 * bytes must be exactly equal). Rejections must be the expected size/contract
 * failure (Java: "unable to fit"; Rust: null with LAST_ERR == ERR_TOO_LARGE),
 * not arbitrary exceptions.
 */
public class MCK42FrameBoundaryGates {

    static final int MAX = 0x1F_FF_FF; // 2,097,151 — largest 3-byte VarInt payload bound
    static int pass = 0, fail = 0;

    public static void main(String[] args) throws Exception {
        disabledMatrix();
        passthroughMatrix();
        compressedAcceptOverLimit();
        compressedRejectActualOverflow();
        retryCeilingSecondFailure();
        System.out.println("RESULT: " + pass + " pass, " + fail + " fail");
        System.exit(fail == 0 ? 0 : 1);
    }

    static void check(String n, boolean ok, String d) {
        if (ok) { pass++; System.out.println(n + " -> PASS" + (d == null ? "" : " (" + d + ")")); }
        else { fail++; System.out.println(n + " -> FAIL " + d); }
    }

    /** Installed-Java pipeline result: frame bytes on success, or the rejection Throwable. */
    static Object javaFrameOrThrowable(byte[] body, Integer threshold) {
        EmbeddedChannel ch = new EmbeddedChannel();
        try {
            Object prep = Class.forName("net.minecraft.network.NettyVarint21FrameEncoder").getDeclaredConstructor().newInstance();
            ch.pipeline().addLast("prep", (ChannelHandler) prep);
            if (threshold != null) {
                Object comp = Class.forName("net.minecraft.network.NettyCompressionEncoder").getConstructor(int.class).newInstance(threshold.intValue());
                ch.pipeline().addLast("compress", (ChannelHandler) comp);
            }
            try {
                ch.writeOutbound(Unpooled.wrappedBuffer(body));
                ByteBuf out = ch.readOutbound();
                byte[] arr = new byte[out.readableBytes()];
                out.readBytes(arr);
                out.release();
                return arr;
            } catch (Throwable t) {
                return t;
            }
        } catch (Throwable t) {
            return t;
        } finally {
            ch.close();
        }
    }

    static boolean isSizeRejection(Object r) {
        return r instanceof Throwable && String.valueOf(((Throwable) r).getMessage()).contains("unable to fit");
    }

    // ---- A. compression DISABLED (installed contract: handlers absent, prepender only) ----
    static void disabledMatrix() {
        int[] lens = { MAX - 1, MAX, MAX + 1 };
        for (int len : lens) {
            byte[] body = new byte[len]; // content irrelevant: no compression in disabled mode
            Object j = javaFrameOrThrowable(body, null);
            OutboundFrameCtx ctx = OutboundFrameCtx.create();
            byte[] r = ctx.frame(body, -1);
            int lastErr = OutboundFrameCtx.LAST_ERR;
            ctx.free();
            boolean expectAccept = len <= MAX;
            if (expectAccept) {
                boolean ok = (j instanceof byte[]) && r != null
                        && Arrays.equals((byte[]) j, r) // deterministic framing: exact bytes
                        && Arrays.equals(MCK4Validation.decodeFrameSafe((byte[]) j, false), body)
                        && Arrays.equals(MCK4Validation.decodeFrameSafe(r, false), body);
                check("A-disabled len=" + len + " (MAX" + (len == MAX - 1 ? "-1" : len == MAX ? "" : "+1")
                        + "): BOTH accept; bytes EXACTLY equal; both decode", ok,
                        "j=" + (j instanceof byte[] ? ((byte[]) j).length : j) + " r=" + (r == null ? -1 : r.length));
            } else {
                boolean ok = isSizeRejection(j) && r == null && lastErr == OutboundFrameCtx.ERR_TOO_LARGE;
                check("A-disabled len=" + len + " (MAX+1): BOTH reject with the SIZE contract (java 'unable to fit', rust ERR_TOO_LARGE)",
                        ok, "java=" + (isSizeRejection(j) ? "unable-to-fit" : j) + " rust=" + (r == null ? "null/err " + lastErr : "ACCEPTED"));
            }
        }
    }

    // ---- B. compression ENABLED but passthrough (dataLen=0 included in the outer length) ----
    static void passthroughMatrix() {
        int threshold = Integer.MAX_VALUE; // every body below threshold -> writeVarInt(0) passthrough
        int[] lens = { MAX - 2, MAX - 1, MAX }; // bodyLen+1 = MAX-1, MAX, MAX+1
        for (int len : lens) {
            byte[] body = new byte[len];
            Arrays.fill(body, (byte) 3);
            Object j = javaFrameOrThrowable(body, threshold);
            OutboundFrameCtx ctx = OutboundFrameCtx.create();
            byte[] r = ctx.frame(body, threshold);
            int lastErr = OutboundFrameCtx.LAST_ERR;
            ctx.free();
            boolean expectAccept = (len + 1) <= MAX; // inner = 1 dataLen byte + bodyLen
            if (expectAccept) {
                boolean ok = (j instanceof byte[]) && r != null
                        && Arrays.equals((byte[]) j, r)
                        && Arrays.equals(MCK4Validation.decodeFrameSafe((byte[]) j, true), body)
                        && Arrays.equals(MCK4Validation.decodeFrameSafe(r, true), body);
                check("B-passthrough bodyLen=" + len + " (bodyLen+1=" + (len + 1) + " <= MAX): BOTH accept; bytes EXACTLY equal; both decode",
                        ok, "j=" + (j instanceof byte[] ? ((byte[]) j).length : j) + " r=" + (r == null ? -1 : r.length));
            } else {
                boolean ok = isSizeRejection(j) && r == null && lastErr == OutboundFrameCtx.ERR_TOO_LARGE;
                check("B-passthrough bodyLen=" + len + " (bodyLen+1=MAX+1): BOTH reject with the SIZE contract",
                        ok, "java=" + (isSizeRejection(j) ? "unable-to-fit" : j) + " rust=" + (r == null ? "null/err " + lastErr : "ACCEPTED"));
            }
        }
    }

    // ---- C1. compressed: highly compressible plaintext LARGER than MAX, framed output FITS ----
    static void compressedAcceptOverLimit() {
        byte[] body = new byte[MAX + 300_000]; // ~2.39 MiB of zeros
        OutboundFrameCtx ctx = OutboundFrameCtx.create();
        byte[] r = ctx.frame(body, 256);
        Object j = javaFrameOrThrowable(body, 256);
        ctx.free();
        boolean rustOk = false, javaOk = false;
        long rustOuter = -1;
        if (r != null) {
            byte[] dec = MCK4Validation.decodeFrameSafe(r, true);
            rustOk = dec != null && Arrays.equals(dec, body);
            rustOuter = MCK4Validation.readVarInt(r);
            rustOk = rustOk && rustOuter <= MAX
                    && MCK4Validation.varIntSize((int) rustOuter) + (int) rustOuter == r.length;
        }
        if (j instanceof byte[]) {
            byte[] dec = MCK4Validation.decodeFrameSafe((byte[]) j, true);
            javaOk = dec != null && Arrays.equals(dec, body);
        }
        check("C1-compressible ~2.39MiB over limit: BOTH accept; both decode to original; framed under MAX",
                rustOk && javaOk,
                "java=" + (j instanceof byte[] ? ((byte[]) j).length : j) + " rust=" + (r == null ? -1 : r.length)
                        + " rustOuter=" + rustOuter);
    }

    // ---- C2. compressed: deterministic incompressible input whose ACTUAL framed output EXCEEDS MAX ----
    static void compressedRejectActualOverflow() throws Exception {
        int len = MAX + 65_536; // ~2.16 MiB: zlib on PRNG bytes cannot shrink below MAX
        byte[] body = new byte[len];
        new Random(1234).nextBytes(body); // deterministic fixture (labeled SYNTHETIC)
        Object j = javaFrameOrThrowable(body, 256);
        boolean javaReject = isSizeRejection(j);
        OutboundFrameCtx ctx = OutboundFrameCtx.create();
        byte[] r = ctx.frame(body, 256);
        int lastErr = OutboundFrameCtx.LAST_ERR;
        boolean rustReject = r == null && lastErr == OutboundFrameCtx.ERR_TOO_LARGE;
        check("C2-incompressible ~2.16MiB actual overflow: BOTH reject with the SIZE contract (post-compression exact check)",
                javaReject && rustReject,
                "java=" + (javaReject ? "unable-to-fit(" + ((Throwable) j).getMessage() + ")" : j)
                        + " rust=" + (r == null ? "null/err " + lastErr : "ACCEPTED len " + r.length));
        // recovery on the SAME Rust context: a small valid frame must still work after the rejection
        byte[] small = new byte[100];
        Arrays.fill(small, (byte) 9);
        byte[] sf = ctx.frame(small, 256);
        boolean recovered = sf != null && Arrays.equals(MCK4Validation.decodeFrameSafe(sf, true), small);
        check("C2-recovery: small valid frame on the SAME Rust context after rejection", recovered, null);
        ctx.free();
        // recovery on the SAME installed-Java channel: the failed write must not poison the handlers
        EmbeddedChannel ch = new EmbeddedChannel();
        Object prep = Class.forName("net.minecraft.network.NettyVarint21FrameEncoder").getDeclaredConstructor().newInstance();
        ch.pipeline().addLast("prep", (ChannelHandler) prep);
        Object comp = Class.forName("net.minecraft.network.NettyCompressionEncoder").getConstructor(int.class).newInstance(256);
        ch.pipeline().addLast("compress", (ChannelHandler) comp);
        String first = "ok";
        try { ch.writeOutbound(Unpooled.wrappedBuffer(body)); } catch (Throwable t) { first = t.getMessage(); }
        ByteBuf mid = ch.readOutbound(); // partial output, if any: release, not a frame
        if (mid != null) mid.release();
        boolean jRecovered = false;
        try {
            ch.writeOutbound(Unpooled.wrappedBuffer(small));
            ByteBuf out = ch.readOutbound();
            byte[] jf = new byte[out.readableBytes()];
            out.readBytes(jf);
            out.release();
            jRecovered = Arrays.equals(MCK4Validation.decodeFrameSafe(jf, true), small);
        } catch (Throwable t) { jRecovered = false; }
        ch.close();
        check("C2-recovery-java: same installed-Java channel still encodes a small valid frame after the rejection",
                jRecovered, "firstWrite=" + first);
    }

    // ---- one-retry ceiling exercised by a SECOND real capacity failure ----
    static void retryCeilingSecondFailure() {
        OutboundFrameCtx ctx = OutboundFrameCtx.create();
        byte[] body = new byte[5000];
        new Random(11).nextBytes(body);
        long ops0 = OutboundFrameCtx.FRAME_OPS.get();
        long retries0 = OutboundFrameCtx.RETRY_EVENTS.get();
        ctx.testForceCapacityTimes = 2; // BOTH the first call and the retry report real ERR_CAPACITY
        ctx.testForceCapacityValue = 64;
        byte[] f = ctx.frame(body, 256);
        long ops = OutboundFrameCtx.FRAME_OPS.get() - ops0;
        long retries = OutboundFrameCtx.RETRY_EVENTS.get() - retries0;
        boolean terminated = f == null;                                       // second failure terminates
        boolean twoRealCalls = ops == 2;                                       // two REAL JNI calls happened
        boolean oneRetryEvent = retries == 1;                                  // exactly one retry was admitted
        boolean neededRead = ctx.LAST_RETRY_NEEDED > 64;                       // bound came from native
        // the context must remain usable after the terminated call
        ctx.testForceCapacityTimes = 0;
        byte[] small = new byte[100];
        Arrays.fill(small, (byte) 4);
        byte[] sf = ctx.frame(small, 256);
        boolean usable = sf != null && Arrays.equals(MCK4Validation.decodeFrameSafe(sf, true), small);
        check("retry-ceiling: SECOND real ERR_CAPACITY terminates null (ops=2, retryEvents=1, native bound read); ctx still usable",
                terminated && twoRealCalls && oneRetryEvent && neededRead && usable,
                "f=" + (f == null ? "null" : f.length) + " ops=" + ops + " retries=" + retries
                        + " needed=" + ctx.LAST_RETRY_NEEDED);
        ctx.free();
    }
}
