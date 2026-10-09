package com.rustcraft.interop;

/** M3-WORLDGEN-NOISE prototype JNI surface — one coarse call per field. */
public final class WNoiseInterop {
    private WNoiseInterop() {}

    public static native long create(long seed, int octaves);
    public static native void freeRaw(long h);
    public static native void gen3d(long h, int xOff, int yOff, int zOff,
            int xSize, int ySize, int zSize, double xScale, double yScale, double zScale,
            long outAddr, int outLen);
    public static native long batchCreate(long seed, int octaves);
    public static native void batchFreeRaw(long h);
    public static native void batchGen3d(long h, int n, long xOffsAddr, long zOffsAddr,
            int yOff, int xSize, int ySize, int zSize, double xScale, double yScale, double zScale, long outAddr);
    public static native long fieldCreate(float a, float b, float e, float f, float g,
            float h, float i, float j, float k, float l, float d, float c,
            float m, float n, float o, float p, byte amplified);
    public static native void fieldFreeRaw(long h);
    public static native void fieldAssemble(long h, long hAddr, long eAddr, long fAddr, long gAddr,
            long biomeAddr, long qAddr);
    public static native long singleCreate(long seed, int octaves);
    public static native void singleFreeRaw(long h);
    public static native void singleGen3d(long h, int xOff, int yOff, int zOff,
            int xSize, int ySize, int zSize, double xScale, double yScale, double zScale, long outAddr);
    public static native long initCreate(long seedDepth, long seedMain, long seedMin, long seedMax,
            float a, float b, float e, float f, float g, float h, float i, float j,
            float k, float l, float d, float c, float m, float n, float o, float p, byte amplified);
    public static native void initFreeRaw(long h);
    public static native void initFieldComplete(long h, int x4, int z4, long biomeAddr, long outAddr);
    public static native long initCreateFromState(int octDepth, int octMain, int octMin, int octMax,
            long permsI32Addr, long offsAddr,
            float a, float b, float e, float f, float g, float h, float i, float j,
            float k, float l, float d, float c, float m, float n, float o, float p);
    public static native void gen2d(long h, int xOff, int zOff, int xSize, int zSize,
            double xScale, double zScale, double dropped, long outAddr, int outLen);
    public static native void terrainSetBlocks(long densityAddr, int seaLevel, int stoneId, int waterId, long primerOutAddr);
    public static native void terrainSetBlocksOpt(long densityAddr, int seaLevel, int stoneId, int waterId, long primerOutAddr);
    public static native void terrainSetBlocksCritical(long densityAddr, int seaLevel, int stoneId, int waterId, char[] primerOutArray);
    public static native void terrainComplete(long h, int x4, int z4, long biomeAddr, int seaLevel, int stoneId, int waterId, long primerOutAddr);
}
