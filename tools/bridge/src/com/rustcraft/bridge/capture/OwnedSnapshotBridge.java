package com.rustcraft.bridge.capture;

/**
 * Additive offline JNI seam for one owned RCSNAP01 input and one V2 result.
 * Caller owns both allocations through the call, must discard output on error,
 * and must use the returned emitted mask rather than rescan Java state.
 * This entry point has no production packet-shell call site.
 */
public final class OwnedSnapshotBridge {
    private OwnedSnapshotBridge() { }
    public static native long encodeOwnedV1(long inputAddress, int inputLength,
                                           long outputAddress, int outputCapacity);
}
