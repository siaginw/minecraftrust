package com.rustcraft.bridge;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * RUST_BLOCK_LIGHT_PROPAGATION_AUTHORITY — ONE JNI crossing per BLOCK-light
 * job (goal §5). Java stages the flat job descriptor (state id + current
 * light per cell, job box ±15 + 2 halo rings); Rust runs the frozen kernel
 * and Java commits the diffs. No natives declared here beyond this class —
 * the Rust exports bind THIS exact name (runscope jni-pairing).
 *
 * Buffer layouts are documented in crates/ffi/src/light_authority.rs.
 * ALL direct buffers are LITTLE_ENDIAN (the diag10/11 byte-swap rule).
 */
public final class LightAuthorityBridge {

    public static final boolean LOADED = LightBatchCtx.LOADED; // same DLL

    private static native int runJob(long inputAddr, int inputLen,
                                     long tableAddr, int tableLen,
                                     long outAddr, int outLen);

    private static native void lastOob(long addr);

    private static native int runJobNative(long dirAddr, int dirLen,
            long statesAddr, int statesLen, long lightAddr, int lightLen,
            long tableAddr, int tableLen, int ox, int oy, int oz,
            int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
            long outAddr, int outLen, long maxVisited);

    private static native void lastUnknownSid(long addr);

    private static native void lastNativeUnknownSid(long addr);

    /** sid the NATIVE prescan/reads rejected last (u32::MAX if none). */
    public static int lastNativeUnknownSid() {
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocateDirect(4)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        lastNativeUnknownSid(address(b));
        return b.getInt(0);
    }

    /** NativeChunk fast path: ONE JNI crossing, frontier-driven over the
     *  section snapshots. Throws on kernel rejection. Returns changed-cell
     *  count; the diff lands in `out` (i32 x,y,z,v records). */
    public static int runJobNative(java.nio.ByteBuffer dir,
            java.nio.ByteBuffer states, java.nio.ByteBuffer light,
            java.nio.ByteBuffer table, int ox, int oy, int oz,
            int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
            java.nio.ByteBuffer out, long maxVisited) {
        // Rust expects ELEMENT counts: dir in i32s, states in u16s,
        // light/table/out in bytes/i32s as laid out
        long rc = runJobNative(address(dir), dir.remaining() / 4,
                address(states), states.remaining() / 2,
                address(light), light.remaining(),
                address(table), table.remaining(),
                ox, oy, oz, minX, minY, minZ, maxX, maxY, maxZ,
                address(out), out.remaining() / 4, maxVisited);
        if (rc > Integer.MAX_VALUE || rc < Integer.MIN_VALUE) {
            throw new IllegalStateException("runJobNative overflow " + rc);
        }
        return (int) rc;
    }

    /** State id rejected by the native prescan on the most recent
     *  UNKNOWN_STATE job (u32::MAX as int if none recorded). */
    public static int lastUnknownSidNative() {
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocateDirect(4)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        lastUnknownSid(address(b));
        return b.getInt(0);
    }

    private static native int runZeroStage(int dim, int x, int y, int z,
            long tableAddr, int tableLen, long outAddr, int outLen,
            long maxVisited, int maxTouchedChunks, int commit);

    /** ZERO-STAGING (goal §2/§3): ONE JNI call with a minimal descriptor
     *  (dim, x, y, z, caps + the session light table) — NO section
     *  snapshot buffers. Rust resolves the world-lifecycle NativeChunk
     *  registry directly, traverses NativeSections, commits block light
     *  natively, and returns the diff (x,y,z,v i32 records) for the Java
     *  mirror. Returns committed-cell count (>=0) or ZS_ERR_* (<0). */
    public static int runZeroStage(int dim, int x, int y, int z,
            java.nio.ByteBuffer table, java.nio.ByteBuffer out,
            long maxVisited, int maxTouchedChunks, boolean commit) {
        long rc = runZeroStage(dim, x, y, z, address(table),
                table.remaining(), address(out), out.remaining() / 4,
                maxVisited, maxTouchedChunks, commit ? 1 : 0);
        if (rc > Integer.MAX_VALUE || rc < Integer.MIN_VALUE) {
            throw new IllegalStateException("runZeroStage overflow " + rc);
        }
        return (int) rc;
    }

    private LightAuthorityBridge() { }

    /** Run one staged job; returns changed-cell count (>=0) or negative
     *  JOB_ERR_* (see light_authority.rs). Throws on kernel rejection —
     *  the caller fail-closes to Java. */
    public static int runJob(ByteBuffer input, ByteBuffer table,
                             ByteBuffer out) {
        long rc = runJob(address(input), input.remaining(),
                address(table), table.remaining(),
                address(out), out.remaining());
        if (rc > Integer.MAX_VALUE || rc < Integer.MIN_VALUE) {
            throw new IllegalStateException("runJob overflow " + rc);
        }
        return (int) rc;
    }

    /** First out-of-bounds cell of the most recent OOB job as
     *  {x, y, z} (or Integer.MIN_VALUEs if none recorded on this thread). */
    public static int[] lastOob() {
        ByteBuffer b = ByteBuffer.allocateDirect(12)
                .order(ByteOrder.LITTLE_ENDIAN);
        lastOob(address(b));
        return new int[]{b.getInt(0), b.getInt(4), b.getInt(8)};
    }

    private static volatile Method ADDRESS_M;

    static long address(ByteBuffer b) {
        try {
            Method m = ADDRESS_M;
            if (m == null) {
                m = b.getClass().getMethod("address");
                m.setAccessible(true);
                ADDRESS_M = m;
            }
            return (Long) m.invoke(b);
        } catch (Throwable t) {
            throw new IllegalStateException("direct-buffer address unavailable", t);
        }
    }
}
