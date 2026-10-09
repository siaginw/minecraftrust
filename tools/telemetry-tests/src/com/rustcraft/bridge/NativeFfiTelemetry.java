package com.rustcraft.bridge;

/** Offline diagnostic declarations for the fixed schema V2 native snapshot. */
final class NativeFfiTelemetry {
    static native int schemaVersion();
    static native int readOperationV2(int operation, long output, int capacity);
}
