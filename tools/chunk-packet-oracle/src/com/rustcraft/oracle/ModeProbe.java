
package com.rustcraft.oracle;

import com.rustcraft.bridge.NativeChunkPacket;
import java.lang.reflect.*;

public class ModeProbe {
    public static void main(String[] args) throws Exception {
        NativeChunkPacket.setRuntimeMode("ON_EXPERIMENTAL");
        System.out.println("direct mode: " + NativeChunkPacket.getRuntimeMode());
        System.out.println("nativeLoaded: " + NativeChunkPacket.isNativeLoaded());

        // same trick as harness: load transformed class in child loader
        Class<?> brid = NativeChunkPacket.class;
        System.out.println("bridge classloader: " + brid.getClassLoader());
        System.out.println("probe classloader : " + ModeProbe.class.getClassLoader());
    }
}
