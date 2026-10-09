package com.rustcraft.interop;

/**
 * M3.0 prototype JNI surface — natives must live in THIS class so the
 * exported symbol names (Java_com_rustcraft_interop_SpawnIndexInterop_*)
 * match. Standalone prototype only.
 */
public final class SpawnIndexInterop {
    private SpawnIndexInterop() {}

    public static native long create();
    public static native void freeRaw(long h);
    public static native void insert(long h, int rank, boolean valid,
            int minX, int minY, int minZ, int maxX, int maxY, int maxZ, long compsAddr, int nCompInts);
    public static native void updateBox(long h, int rank,
            int minX, int minY, int minZ, int maxX, int maxY, int maxZ);
    public static native void remove(long h, int rank);
    public static native int query(long h, int x, int y, int z);
    public static native void stats(long h, long outAddr);
}
