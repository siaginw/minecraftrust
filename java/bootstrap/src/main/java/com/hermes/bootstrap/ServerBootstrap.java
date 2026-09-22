package com.hermes.bootstrap;

import com.hermes.bridge.NativeBridge;
import com.hermes.instrumentation.TickProfiler;

public final class ServerBootstrap {
    public static void main(String[] args) {
        System.out.println("[Hermes] Initializing Minecraft 1.12.2 Rust Server Foundation...");
        boolean nativeLoaded = NativeBridge.isAvailable();
        System.out.println("[Hermes] Native runtime loaded: " + nativeLoaded);
        TickProfiler.INSTANCE.startServerTick();
        // Server init placeholder for P0
        TickProfiler.INSTANCE.endServerTick();
        System.out.println("[Hermes] Bootstrap test complete.");
    }
}
