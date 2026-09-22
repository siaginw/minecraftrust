package com.rustcraft.coremod;

import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * M3 collision-feasibility measurement: replaces the BODY of
 * World.func_191504_a with a static call to CollisionProbe.scan, an exact
 * bytecode-verified replica with aggregate counters. Gated by the system
 * property minecraftrust.collision_probe (default OFF; measurement-only
 * build, never for production).
 *
 * The replaced method keeps its exact descriptor, so the vanilla caller
 * func_184144_a (and any mod caller) is untouched; every state read and
 * collision callback still routes through the LIVE runtime (NEID/FoamFix/
 * mod transforms included).
 */
public class WorldCollisionProbeTransformer implements IClassTransformer {

    public static volatile int transformCount = 0;
    public static volatile String lastStatus = "NOT_ATTEMPTED";

    private static final boolean ENABLED =
            Boolean.getBoolean("minecraftrust.collision_probe");

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (!ENABLED || !"net.minecraft.world.World".equals(transformedName) || basicClass == null) {
            return basicClass;
        }
        try {
            ClassReader cr = new ClassReader(basicClass);
            ClassNode cn = new ClassNode();
            cr.accept(cn, 0);
            boolean patched = false;
            for (MethodNode mn : cn.methods) {
                if (!"func_191504_a".equals(mn.name)) continue;
                InsnList body = new InsnList();
                body.add(new VarInsnNode(Opcodes.ALOAD, 0)); // world
                body.add(new VarInsnNode(Opcodes.ALOAD, 1)); // entity
                body.add(new VarInsnNode(Opcodes.ALOAD, 2)); // aabb
                body.add(new VarInsnNode(Opcodes.ILOAD, 3)); // returnOnFirst
                body.add(new VarInsnNode(Opcodes.ALOAD, 4)); // out list
                body.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                        "com/rustcraft/bridge/CollisionProbe", "scan",
                        "(Lnet/minecraft/world/World;Lnet/minecraft/entity/Entity;"
                                + "Lnet/minecraft/util/math/AxisAlignedBB;Z"
                                + "Ljava/util/List;)Z", false));
                body.add(new org.objectweb.asm.tree.InsnNode(Opcodes.IRETURN));
                mn.instructions.clear();
                mn.tryCatchBlocks.clear();
                mn.localVariables = null; // stale LVT causes duplicated-attribute errors
                mn.instructions.add(body);
                patched = true;
            }
            if (patched) {
                ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
                cn.accept(cw);
                transformCount++;
                lastStatus = "PROBE_INSTALLED";
                return cw.toByteArray();
            }
            lastStatus = "SCAN_METHOD_NOT_FOUND";
            return basicClass;
        } catch (Throwable t) {
            lastStatus = "PROBE_TRANSFORM_ERROR: " + t.getMessage();
            return basicClass; // safe fallback: unmodified bytecode
        }
    }
}
