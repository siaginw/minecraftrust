package com.hermes.bridge;

import java.nio.ByteBuffer;

public final class NativeBridge {
    private static volatile boolean available = false;

    static {
        try {
            System.loadLibrary("ffi");
            available = true;
        } catch (UnsatisfiedLinkError e) {
            // Native library not yet compiled into library path during initial bootstrap
            available = false;
        }
    }

    public static boolean isAvailable() {
        return available;
    }

    public static native int ping();
    public static native long getFfiCalls();
    public static native void processPacketBatch(ByteBuffer input, ByteBuffer output);
}
