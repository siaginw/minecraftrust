import com.rustcraft.bridge.WorldLightHook;
import com.rustcraft.coremod.WorldLightTransformer;

import java.lang.reflect.Method;

/**
 * §5 regression self-test for the World.checkLightFor seam (offline, no
 * server): verifies canonical identities against the SRG-named server jar
 * and the BLOCK/SKY discrimination, which must key on the enum constant
 * NAME because EnumSkyBlock declares SKY first (ordinal 0 = SKY).
 *
 * Classpath: rustcraft-campaign.jar + minecraft_server.1.12.2.srg.jar
 * (+ launchwrapper/asm for the transformer supertypes).
 */
public class WorldLightSeamSelfTest {

    private static int checks = 0;

    private static void ok(boolean cond, String what) {
        checks++;
        if (!cond) {
            throw new AssertionError("FAIL: " + what);
        }
        System.out.println("  ok: " + what);
    }

    private enum Local { SKY, BLOCK }

    public static void main(String[] args) throws Exception {
        // -- transformer class matching (notch + deobf shapes) --
        ok(WorldLightTransformer.isTargetClass("amu", "amu"),
                "notch class amu matches (name and transformedName)");
        ok(WorldLightTransformer.isTargetClass(
                        "net.minecraft.world.World", "net.minecraft.world.World"),
                "deobf class net.minecraft.world.World matches");
        ok(!WorldLightTransformer.isTargetClass("amy", "amy"),
                "IBlockAccess notch amy does not match");

        // -- method matching: exact (name, descriptor) pairs only --
        ok(WorldLightTransformer.isTargetMethod("c", "(Lana;Let;)Z"),
                "notch checkLightFor amu.c (Lana;Let;)Z matches");
        ok(WorldLightTransformer.isTargetMethod("func_180500_c",
                        "(Lnet/minecraft/world/EnumSkyBlock;"
                                + "Lnet/minecraft/util/math/BlockPos;)Z"),
                "SRG func_180500_c + deobf desc matches");
        ok(WorldLightTransformer.isTargetMethod("checkLightFor",
                        "(Lnet/minecraft/world/EnumSkyBlock;"
                                + "Lnet/minecraft/util/math/BlockPos;)Z"),
                "MCP checkLightFor + deobf desc matches");
        ok(!WorldLightTransformer.isTargetMethod("c",
                        "(Lnet/minecraft/world/EnumSkyBlock;"
                                + "Lnet/minecraft/util/math/BlockPos;)Z"),
                "name c with deobf desc does NOT match (pair integrity)");
        ok(!WorldLightTransformer.isTargetMethod("func_180500_c", "(Lana;Let;)Z"),
                "SRG name with notch desc does NOT match (pair integrity)");
        ok(!WorldLightTransformer.isTargetMethod("b", "(Lana;Let;)Z"),
                "getLightFor (notch amu.b) does NOT match checkLightFor");

        // -- forbidden target guard: getRawLight --
        ok(WorldLightTransformer.isForbiddenTarget("func_175638_a", "(I)I"),
                "func_175638_a forbidden regardless of descriptor");
        ok(WorldLightTransformer.isForbiddenTarget("a", "(Let;Lana;)I"),
                "notch getRawLight amu.a (Let;Lana;)I forbidden");
        ok(!WorldLightTransformer.isForbiddenTarget("a", "(Lana;Let;I)V"),
                "setLightFor notch amu.a (Lana;Let;I)V is NOT forbidden");

        // -- §5 enum discrimination --
        ok(!WorldLightHook.isBlockType(Local.SKY), "local SKY enum rejected");
        ok(WorldLightHook.isBlockType(Local.BLOCK), "local BLOCK enum accepted");

        Class<?> esb = Class.forName("net.minecraft.world.EnumSkyBlock");
        Object[] consts = esb.getEnumConstants();
        ok(consts.length == 2
                        && "SKY".equals(((Enum) consts[0]).name())
                        && ((Enum) consts[0]).ordinal() == 0,
                "REAL EnumSkyBlock: SKY is constant 0 (ordinal 0)");
        ok("BLOCK".equals(((Enum) consts[1]).name())
                        && ((Enum) consts[1]).ordinal() == 1,
                "REAL EnumSkyBlock: BLOCK is constant 1 (ordinal 1 — the old"
                        + " ordinal!=0 gate was inverted)");
        ok(WorldLightHook.isBlockType(consts[1]), "real BLOCK accepted");
        ok(!WorldLightHook.isBlockType(consts[0]), "real SKY rejected");

        // -- every reflected seam member exists on the SRG jar with the
        //    exact signature the hook binds (candidate proof, offline) --
        Class<?> world = Class.forName("net.minecraft.world.World");
        Class<?> blockPos = Class.forName("net.minecraft.util.math.BlockPos");
        Class<?> iBlockState =
                Class.forName("net.minecraft.block.state.IBlockState");
        Class<?> iBlockAccess = Class.forName("net.minecraft.world.IBlockAccess");
        Class<?> block = Class.forName("net.minecraft.block.Block");

        m(world, "func_175642_b", esb, blockPos);           // getLightFor
        m(world, "func_180495_p", blockPos);                // getBlockState
        m(iBlockState, "func_177230_c");                    // getBlock
        // Forge world-aware opacity/emission live on the FORGE-PATCHED
        // runtime Block, not the vanilla SRG jar — verify on the notch-named
        // binpatched artifact (aow), where they keep literal names.
        Class<?> patchBlock = Class.forName("aow");
        forge3(patchBlock, "getLightValue");
        forge3(patchBlock, "getLightOpacity");
        m(blockPos, "func_177958_n");                       // getX
        m(blockPos, "func_177956_o");                       // getY
        m(blockPos, "func_177952_p");                       // getZ
        Method checkLightFor = m(world, "func_180500_c", esb, blockPos);
        ok(checkLightFor.getReturnType() == boolean.class,
                "func_180500_c returns boolean");
        // getRawLight is PRIVATE (javap: private int a(et, ana)) — declared
        // lookup required; the hook must never bind it (guard above)
        Method rawLight = world.getDeclaredMethod("func_175638_a", blockPos, esb);
        rawLight.setAccessible(true);
        checks++;
        System.out.println("  ok: seam member World.func_175638_a (2 params)"
                + " present (private)");
        ok(rawLight.getReturnType() == int.class,
                "func_175638_a (getRawLight) returns int — different seam");

        System.out.println("WORLD_LIGHT_SEAM_SELFTEST_PASSED checks=" + checks);
    }

    /** Forge-added world-aware Block methods keep literal names in every
     *  launch shape; verify presence + (IBlockState, IBlockAccess, BlockPos)
     *  int signature on the binpatched (notch-named) Block artifact. */
    private static void forge3(Class<?> patchBlock, String name) {
        for (Method mm : patchBlock.getMethods()) {
            if (name.equals(mm.getName()) && mm.getParameterCount() == 3
                    && mm.getReturnType() == int.class) {
                checks++;
                System.out.println("  ok: forge seam " + patchBlock.getName()
                        + "." + name + "(state,world,pos) present ("
                        + mm.getParameterTypes()[0].getSimpleName() + ","
                        + mm.getParameterTypes()[1].getSimpleName() + ","
                        + mm.getParameterTypes()[2].getSimpleName() + ")");
                return;
            }
        }
        throw new AssertionError("FAIL: forge 3-arg " + name
                + " missing on " + patchBlock.getName());
    }

    private static Method m(Class<?> owner, String name, Class<?>... params)
            throws Exception {
        Method mm = owner.getMethod(name, params);
        checks++;
        System.out.println("  ok: seam member " + owner.getSimpleName() + "."
                + name + " (" + params.length + " params) present");
        return mm;
    }
}
