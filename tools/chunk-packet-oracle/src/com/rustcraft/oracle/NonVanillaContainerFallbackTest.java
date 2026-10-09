package com.rustcraft.oracle;

import com.rustcraft.bridge.NativeChunkPacket;
import net.minecraft.init.Bootstrap;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.server.SPacketChunkData;
import net.minecraft.world.chunk.BlockStateContainer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;

/**
 * M1-final targeted fixture (operator directive 2026-09-19): exercise the
 * non-vanilla BlockStateContainer representation that no campaign ever hit
 * (all four targets ran vanilla-class containers; FoamFix splices methods,
 * it does not swap the class — see docs/migrations/M1-final-target-coverage.md §5).
 *
 * Verifies, in ON_EXPERIMENTAL (production path):
 *   1. eligibility REJECTS a non-vanilla container class (populatePacket=false);
 *   2. the original Java path produces the correct packet through it;
 *   3. counters record the actual fallback reason
 *      (m1_native_fallback_extractor + m1_transformer_conflicts);
 *   4. no native work happens: staging bytes, payload-predict and JNI
 *      counters unchanged => no staging write, no predictOutputLen call,
 *      no GetPrimitiveArrayCritical region, no pointer acquired or retained.
 *
 * Verifies, in SHADOW mode (no isEligible gate on that path by design):
 *   5. the staging-time "Deduplicated" class-name detector fires
 *      (M1_FOAMFIX_ADAPTER_PACKETS increments) — proving that counter is
 *      reachable ONLY from the SHADOW path and therefore structurally
 *      unreachable in ON mode (which rejects the container class first);
 *   6. the reflection-based shadow extraction byte-matches the Java
 *      reference for a field-compatible container subclass.
 *
 * Exit 0 = all pass; nonzero = failure with diagnostic.
 */
public final class NonVanillaContainerFallbackTest {

    /** Non-vanilla container representation: subclass, byte-compatible data,
     *  class name deliberately containing "Deduplicated" to trip the SHADOW-path
     *  detector exactly like a FoamFix dedup container would. */
    public static final class DeduplicatedTestContainer extends BlockStateContainer {
    }

    public static void main(String[] args) throws Exception {
        Bootstrap.func_151354_b();

        // ---- fixture: full chunk, 3 populated + 13 empty sections (det family 4) ----
        M14RParityHarness.Fixture fx = M14RParityHarness.buildDeterministic(4);
        final Chunk chunk = M14RParityHarness.createMockChunk(fx);
        final int mask = fx.mask;
        ExtendedBlockStorage[] storage = chunk.func_76587_i();

        // Reference bytes with pure vanilla containers (mode OFF).
        NativeChunkPacket.setRuntimeMode("OFF");
        byte[] refVanilla = serialize(new SPacketChunkData(chunk, mask));

        // ---- swap section 0's container for the non-vanilla subclass ----
        // Copy the INTERNALS (BitArray storage + palette) so the subclass holds
        // the exact same representation — isolating ONLY the class identity,
        // which is the variable isEligible() tests.
        BlockStateContainer orig = storage[0].func_186049_g();
        if (orig == null || orig.getClass() != BlockStateContainer.class)
            throw new IllegalStateException("fixture section 0 not vanilla-class");
        DeduplicatedTestContainer sub = new DeduplicatedTestContainer();
        int copied = 0;
        for (Field vf : BlockStateContainer.class.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(vf.getModifiers())) continue;
            vf.setAccessible(true);
            vf.set(sub, vf.get(orig));
            copied++;
        }
        if (copied == 0) throw new IllegalStateException("no instance fields found to copy");
        // fidelity check: every cell must read back identically
        Method get = BlockStateContainer.class.getDeclaredMethod("func_186015_a", int.class);
        get.setAccessible(true);
        for (int i = 0; i < 4096; i++) {
            if (get.invoke(sub, i) != get.invoke(orig, i))
                throw new IllegalStateException("container copy diverged at cell " + i);
        }
        Field contField = null;
        for (Field f : ExtendedBlockStorage.class.getDeclaredFields()) {
            if (f.getType() == BlockStateContainer.class) { contField = f; break; }
        }
        if (contField == null) throw new IllegalStateException("EBS container field not found");
        contField.setAccessible(true);
        contField.set(storage[0], sub);
        if (storage[0].func_186049_g() != sub)
            throw new IllegalStateException("container swap failed");

        // (2) Java path correctness THROUGH the non-vanilla container.
        byte[] refNonVanilla = serialize(new SPacketChunkData(chunk, mask));
        report("java-path bytes identical through subclass container",
                Arrays.equals(refVanilla, refNonVanilla));

        // ---- (1)+(3)+(4) ON_EXPERIMENTAL rejection with counter accounting ----
        NativeChunkPacket.setRuntimeMode("ON_EXPERIMENTAL");
        NativeChunkPacket.setHandoffMode("A");
        long cExtr0 = get("M1_NATIVE_FALLBACK_EXTRACTOR");
        long cConf0 = get("M1_TRANSFORMER_CONFLICTS");
        long cStaged0 = get("M1_BYTES_STAGED");
        long cStageNs0 = get("M1_STAGE_NS");
        long cPredict0 = get("M1_PAYLOAD_PREDICT_NS");
        long cJni0 = get("M1_JNI_RUST_NS");
        long cNative0 = get("M1_NATIVE_PACKETS_TRANSMITTED");

        SPacketChunkData p = new SPacketChunkData(chunk, 0); // cheap shell; hook target
        boolean accepted = NativeChunkPacket.populatePacket(p, chunk, mask);

        report("ON mode rejects non-vanilla container (populatePacket=false)", !accepted);
        report("fallback reason recorded: fallback_extractor +1",
                get("M1_NATIVE_FALLBACK_EXTRACTOR") == cExtr0 + 1);
        report("fallback reason recorded: transformer_conflicts +1",
                get("M1_TRANSFORMER_CONFLICTS") == cConf0 + 1);
        report("no staging performed (bytes_staged unchanged)",
                get("M1_BYTES_STAGED") == cStaged0);
        report("no staging time recorded (stage_ns unchanged)",
                get("M1_STAGE_NS") == cStageNs0);
        report("no size prediction / no JNI (predict + jni_rust unchanged)",
                get("M1_PAYLOAD_PREDICT_NS") == cPredict0 && get("M1_JNI_RUST_NS") == cJni0);
        report("no native packet transmitted",
                get("M1_NATIVE_PACKETS_TRANSMITTED") == cNative0);

        // End-to-end fallback packet correctness: the Java body that runs after
        // the hook returns false must reproduce the reference exactly.
        SPacketChunkData fb = new SPacketChunkData(chunk, mask); // ctor body continues
        report("fallback (ctor body) packet byte-identical to reference",
                Arrays.equals(refVanilla, serialize(fb)));

        // ---- (5)+(6) SHADOW path: detector reachability + extraction parity ----
        long cAdapt0 = get("M1_FOAMFIX_ADAPTER_PACKETS");
        long cShadows0 = get("M1_SHADOW_PACKETS");
        long cMismatch0 = get("M1_SHADOW_MISMATCHES");
        NativeChunkPacket.setRuntimeMode("SHADOW");
        boolean shadowHandled = NativeChunkPacket.populatePacket(new SPacketChunkData(), chunk, mask);
        report("SHADOW never claims the packet (Java authoritative)", !shadowHandled);
        report("SHADOW-path Deduplicated detector fired (adapter counter +1)",
                get("M1_FOAMFIX_ADAPTER_PACKETS") == cAdapt0 + 1);
        report("SHADOW extraction byte-matched Java reference for subclass container",
                get("M1_SHADOW_PACKETS") == cShadows0 + 1
                        && get("M1_SHADOW_MISMATCHES") == cMismatch0);

        NativeChunkPacket.setRuntimeMode("OFF");

        System.out.println("=====================================================");
        System.out.println("  NonVanillaContainerFallbackTest: "
                + (failures == 0 ? "ALL CHECKS PASS" : failures + " FAILURE(S)"));
        System.out.println("=====================================================");
        System.exit(failures == 0 ? 0 : 3);
    }

    static long get(String name) throws Exception {
        Field f = NativeChunkPacket.class.getDeclaredField(name);
        f.setAccessible(true);
        Object v = f.get(null);
        return ((java.util.concurrent.atomic.AtomicLong) v).get();
    }

    static int failures;

    static void report(String what, boolean ok) {
        System.out.println((ok ? "  [PASS] " : "  [FAIL] ") + what);
        if (!ok) failures++;
    }

    static byte[] serialize(SPacketChunkData packet) throws Exception {
        ByteBuf buf = Unpooled.buffer(262144);
        try {
            PacketBuffer pb = new PacketBuffer(buf);
            packet.func_148840_b(pb);
            byte[] out = new byte[buf.readableBytes()];
            buf.readBytes(out);
            return out;
        } finally {
            buf.release();
        }
    }
}
