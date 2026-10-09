package com.rustcraft.oracle;

import com.rustcraft.bridge.NativeChunkPacket;
import net.minecraft.init.Bootstrap;
import net.minecraft.util.BitArray;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.Random;

/**
 * M1.4-R §5: exclusive-cost decomposition of T_STAGE (staging).
 * Times each sub-phase of populateStagingBuffer separately on a real 16-section
 * fixture; sum must reconcile with measured whole-T_STAGE.
 *
 * Usage: StageDecompositionBench
 */
public final class StageDecompositionBench {

    static final int ITER = 2000;
    static final int WARMUP = 500;

    // reflection handles resolved ONCE (same Field objects the bridge caches)
    static Field fBits, fPalette, fStorage;

    public static void main(String[] args) throws Exception {
        Bootstrap.func_151354_b();
        // Reuse the bridge's cached reflection metadata (same Field instances,
        // resolved by class not by guessed name).
        java.lang.reflect.Field[] bridgeFields = NativeChunkPacket.getCachedFieldsForProbe();
        fBits = bridgeFields[0];
        fPalette = bridgeFields[1];
        fStorage = bridgeFields[2];

        System.err.println("phase,mask_hex,iter_us_mean,iter_us_p50,reconcile_note");

        M14RParityHarness.Fixture fx = M14RParityHarness.buildDeterministic(1); // full 16 sections populated
        Chunk ch = M14RParityHarness.createMockChunk(fx);
        ExtendedBlockStorage[] sections = ch.func_76587_i();
        byte[] biomes = ch.func_76605_m();
        boolean full = true, sky = true;
        int mask = 0xFFFF;

        ByteBuffer staging = NativeChunkPacket.getStagingForProbe();

        // whole T_STAGE baseline
        double whole = bench("WHOLE_T_STAGE", () -> {
            staging.clear();
            return NativeChunkPacket.populateStagingBuffer(staging, sections, mask, full, sky, biomes);
        });

        // header + mask computation only (no sections written): emulate via mask 0 on empty array
        ExtendedBlockStorage[] empty = new ExtendedBlockStorage[16];
        double header = bench("HEADER_MASK_ONLY", () -> {
            staging.clear();
            return NativeChunkPacket.populateStagingBuffer(staging, empty, 0x0000, false, sky, null);
        });

        // eligibility loop only (vanillaWritesSection checks, no writes): reflectively read
        double eligibility = bench("ELIGIBILITY_LOOP", () -> {
            int eff = 0;
            for (int i = 0; i < 16; i++) {
                ExtendedBlockStorage ebs = sections[i];
                if (ebs != null && !ebs.func_76663_a() && ((mask >> i) & 1) != 0) eff |= 1 << i;
            }
            return eff;
        });

        // reflective field reads per section (bits/palette/storage)
        double reflect = bench("REFLECT_READS", () -> {
            int sink = 0;
            for (int i = 0; i < 16; i++) {
                Object bsc = sections[i] == null ? null : sections[i].func_186049_g();
                if (bsc == null) continue; // sentinel/null section — skip like bridge
                sink += fBits.getInt(bsc) + System.identityHashCode(fPalette.get(bsc)) + System.identityHashCode(fStorage.get(bsc));
            }
            return sink;
        });

        // BitArray long[] copy via staging.putLong loop (as bridge does)
        double longLoop = bench("BITARRAY_LONGLoop_PUT", () -> {
            staging.clear();
            int sink = 0;
            for (int i = 0; i < 16; i++) {
                if (sections[i] == null) continue;
                Object bsc = sections[i].func_186049_g();
                if (bsc == null) continue;
                BitArray storage = (BitArray) fStorage.get(bsc);
                long[] words = storage.func_188143_a();
                for (long w : words) staging.putLong(w);
                sink += words.length;
            }
            return sink;
        });

        // long[] copy via LongBuffer bulk put (candidate optimization, §7 preview)
        double longBulk = bench("BITARRAY_LONGBUFFER_BULK", () -> {
            staging.clear();
            int sink = 0;
            LongBuffer lb = staging.asLongBuffer();
            for (int i = 0; i < 16; i++) {
                if (sections[i] == null) continue;
                Object bsc = sections[i].func_186049_g();
                if (bsc == null) continue;
                long[] words = ((BitArray) fStorage.get(bsc)).func_188143_a();
                lb.put(words);
                sink += words.length;
                staging.position(staging.position() + words.length * 8);
            }
            return sink;
        });

        // lights copy (block light always; sky light if sky)
        double lights = bench("LIGHTS_NIBBLE_COPY", () -> {
            staging.clear();
            int sink = 0;
            for (int i = 0; i < 16; i++) {
                ExtendedBlockStorage ebs = sections[i];
                if (ebs == null) continue;
                staging.put(ebs.func_76661_k().func_177481_a());
                if (sky && ebs.func_76671_l() != null) staging.put(ebs.func_76671_l().func_177481_a());
                sink++;
            }
            return sink;
        });

        // biomes copy
        double biomesC = bench("BIOMES_COPY", () -> {
            staging.clear();
            staging.put(biomes);
            return biomes.length;
        });

        // §7: Unsafe.copyMemory long[] -> direct staging address
        sun.misc.Unsafe U = getUnsafe();
        long stagingBase = NativeChunkPacket.getDirectBufferAddress(staging);
        double unsafeCopy = bench("UNSAFE_COPYMEMORY", () -> {
            staging.clear();
            int sink = 0;
            long pos = stagingBase;
            for (int i = 0; i < 16; i++) {
                if (sections[i] == null) continue;
                Object bsc = sections[i].func_186049_g();
                if (bsc == null) continue;
                long[] words = ((BitArray) fStorage.get(bsc)).func_188143_a();
                U.copyMemory(words, sun.misc.Unsafe.ARRAY_LONG_BASE_OFFSET, null, pos, words.length * 8L);
                pos += words.length * 8L;
                sink += words.length;
            }
            staging.position((int) (pos - stagingBase));
            return sink;
        });

        System.err.println(String.format(
                "RECONCILE: header(%.3f) + eligibility(%.3f) + reflect(%.3f) + longLoop(%.3f) + lights(%.3f) + biomes(%.3f) = %.3f vs WHOLE %.3f us (residual %.3f = palette emit + ByteBuffer API + loop overhead)",
                header, eligibility, reflect, longLoop, lights, biomesC,
                header + eligibility + reflect + longLoop + lights + biomesC, whole,
                whole - (header + eligibility + reflect + longLoop + lights + biomesC)));
        System.err.println(String.format("OPTIMIZATION_PREVIEW: bulk LongBuffer put = %.3f us vs putLong loop = %.3f us vs Unsafe.copyMemory = %.3f us", longBulk, longLoop, unsafeCopy));
    }

    static sun.misc.Unsafe getUnsafe() throws Exception {
        Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return (sun.misc.Unsafe) f.get(null);
    }

    interface Thunk { Object run() throws Exception; }

    static double bench(String name, Thunk t) throws Exception {
        for (int i = 0; i < WARMUP; i++) t.run();
        long[] ns = new long[ITER];
        for (int i = 0; i < ITER; i++) {
            long t0 = System.nanoTime();
            Object r = t.run();
            long t1 = System.nanoTime();
            if (r instanceof Integer && (Integer) r == -12345) System.err.print("");
            ns[i] = t1 - t0;
        }
        java.util.Arrays.sort(ns);
        double p50 = ns[ITER / 2] / 1000.0;
        double mean = java.util.Arrays.stream(ns).average().orElse(0) / 1000.0;
        System.err.println(String.format("%s,0xFFFF,%.3f,%.3f,", name, mean, p50));
        return p50;
    }
}
