package com.rustcraft.oracle;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.init.Bootstrap;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.BitArray;
import net.minecraft.world.chunk.BlockStateContainer;
import net.minecraft.world.chunk.IBlockStatePalette;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

import java.lang.reflect.Field;
import java.util.Arrays;

public class InspectJavaSectionBytes {
    public static void main(String[] args) throws Exception {
        Bootstrap.func_151354_b();

        System.out.println("=== Testing Java BlockStateContainer wire format ===");

        ExtendedBlockStorage ebs = new ExtendedBlockStorage(0, true);
        BlockStateContainer bsc = ebs.func_186049_g();

        Field bitsField = BlockStateContainer.class.getDeclaredField("field_186024_e");
        Field paletteField = BlockStateContainer.class.getDeclaredField("field_186022_c");
        Field regPaletteField = BlockStateContainer.class.getDeclaredField("field_186023_d");
        Field storageField = BlockStateContainer.class.getDeclaredField("field_186021_b");

        bitsField.setAccessible(true);
        paletteField.setAccessible(true);
        regPaletteField.setAccessible(true);
        storageField.setAccessible(true);

        // Test Global Palette
        bitsField.setInt(bsc, 13);
        IBlockStatePalette regPal = (IBlockStatePalette) regPaletteField.get(null);
        paletteField.set(bsc, regPal);
        storageField.set(bsc, new BitArray(13, 4096));

        ByteBuf buf = Unpooled.buffer();
        PacketBuffer pb = new PacketBuffer(buf);
        bsc.func_186009_b(pb);

        byte[] bytes = new byte[buf.readableBytes()];
        buf.readBytes(bytes);
        System.out.println("13-bit global palette section bytes length=" + bytes.length);
        System.out.print("First 20 bytes: ");
        for (int i = 0; i < Math.min(20, bytes.length); i++) {
            System.out.print(String.format("0x%02X ", bytes[i]));
        }
        System.out.println();
    }
}
