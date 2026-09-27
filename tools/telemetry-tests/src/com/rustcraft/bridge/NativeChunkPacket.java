package com.rustcraft.bridge;

/** Test-only JNI declarations; this class is never packaged with the bridge. */
final class NativeChunkPacket {
    static native int encodeSections(long input, int length, long output, int capacity);
    static native int predictOutputLen(long input, int length);
    static native int encodeSectionsIntoArray(long input, int length, byte[] output);
}
