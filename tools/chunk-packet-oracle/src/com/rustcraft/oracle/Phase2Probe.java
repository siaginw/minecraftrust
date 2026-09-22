package com.rustcraft.oracle;

import com.rustcraft.bridge.NativeChunkPacket;
import com.rustcraft.coremod.SPacketChunkDataTransformer;
import net.minecraft.init.Bootstrap;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.util.Random;

public class Phase2Probe {
    public static void main(String[] args) throws Exception {
        Bootstrap.func_151354_b();

        String targetClassName = "net.minecraft.network.play.server.SPacketChunkData";
        String resourcePath = targetClassName.replace('.', '/') + ".class";
        InputStream in = Phase2Probe.class.getClassLoader().getResourceAsStream(resourcePath);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096]; int n;
        while ((n = in.read(buf)) != -1) baos.write(buf, 0, n);

        SPacketChunkDataTransformer t = new SPacketChunkDataTransformer();
        byte[] transformed = t.transform(targetClassName, targetClassName, baos.toByteArray());

        java.lang.reflect.Method defineClass = ClassLoader.class.getDeclaredMethod(
                "defineClass", String.class, byte[].class, int.class, int.class);
        defineClass.setAccessible(true);
        Class<?> cls = (Class<?>) defineClass.invoke(Phase2Probe.class.getClassLoader(),
                targetClassName, transformed, 0, transformed.length);

        NativeChunkPacket.setRuntimeMode("ON_EXPERIMENTAL");

        Constructor<?> ctor = cls.getDeclaredConstructor(
                Class.forName("net.minecraft.world.chunk.Chunk"), int.class);
        ctor.setAccessible(true);

        Random rng = new Random(555);
        Object chunk = M14ValidationHarnessCreateMock.createMockChunkPublic(3, rng);
        Object pkt = ctor.newInstance(chunk, 0xFFFF);
        System.out.println("packet: " + pkt.getClass().getName());
        System.out.println("--- metrics ---");
        System.out.println(NativeChunkPacket.dumpMetrics());
    }
}
