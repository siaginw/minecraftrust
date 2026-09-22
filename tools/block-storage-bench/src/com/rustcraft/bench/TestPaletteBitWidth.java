package com.rustcraft.bench;

import net.minecraft.util.math.MathHelper;

public class TestPaletteBitWidth {
    public static void main(String[] args) {
        System.out.println("=== Global Palette Bit Width & Max State-ID Verification ===");
        
        int[] registrySizes = {
            1000, 4096, 8192, 8193, 16384, 16385, 32768, 32769, 65535, 65536
        };
        
        for (int size : registrySizes) {
            int bits = MathHelper.func_151241_e(size);
            int longsPerSection = (int)Math.ceil((4096.0 * bits) / 64.0);
            System.out.printf("Registry States: %5d | Global Palette Bits: %2d | Section Longs: %3d | Section Buffer: %4d B\n",
                    size, bits, longsPerSection, longsPerSection * 8);
        }

        // Max Block ID and Meta verification
        int maxBlockId = 4095; // GameData.MAX_BLOCK_ID
        int maxMeta = 15;      // 4-bit metadata
        int maxStateIdVanilla = maxBlockId + (maxMeta << 12);
        int maxStateIdForge = (maxBlockId << 4) | maxMeta;

        System.out.println("\nMax Block ID: " + maxBlockId + " (12 bits: 0x" + Integer.toHexString(maxBlockId) + ")");
        System.out.println("Max Meta: " + maxMeta + " (4 bits: 0x" + Integer.toHexString(maxMeta) + ")");
        System.out.printf("Vanilla formula: blockId + (meta << 12) = 0x%04X (%d) [Fits u16: %b]\n",
                maxStateIdVanilla, maxStateIdVanilla, maxStateIdVanilla <= 65535);
        System.out.printf("Forge formula:   (blockId << 4) | meta  = 0x%04X (%d) [Fits u16: %b]\n",
                maxStateIdForge, maxStateIdForge, maxStateIdForge <= 65535);
    }
}
