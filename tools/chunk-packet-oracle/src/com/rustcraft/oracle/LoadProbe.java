package com.rustcraft.oracle;

import net.minecraft.init.Bootstrap;

public class LoadProbe {
    public static void main(String[] args) throws Exception {
        Bootstrap.func_151354_b();
        ClassLoader app = LoadProbe.class.getClassLoader();
        java.lang.reflect.Method f = ClassLoader.class.getDeclaredMethod("findLoadedClass", String.class);
        f.setAccessible(true);
        System.out.println("after Bootstrap: " + f.invoke(app, "net.minecraft.network.play.server.SPacketChunkData"));
        // reference the class literal the way harness does
        Class<?> ref = net.minecraft.network.play.server.SPacketChunkData.class;
        System.out.println("after literal : " + ref + " loader=" + ref.getClassLoader());
    }
}
