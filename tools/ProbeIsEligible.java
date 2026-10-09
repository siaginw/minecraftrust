import com.rustcraft.bridge.NativeChunkPacket;
import net.minecraft.init.Bootstrap;
import net.minecraft.world.World;
import net.minecraft.world.WorldProvider;
import net.minecraft.world.WorldProviderSurface;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

public class ProbeIsEligible {
    public static void main(String[] args) throws Exception {
        Bootstrap.func_151354_b();
        Chunk c = new Chunk(null, 0, 0);
        injectDummyWorld(c);
        c.func_76587_i()[0] = new ExtendedBlockStorage(0, true);
        c.func_76587_i()[1] = null;

        ExtendedBlockStorage[] arr = c.func_76587_i();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            ExtendedBlockStorage e = arr[i];
            sb.append(i).append('=').append(e == null ? "NULL" : (e == Chunk.field_186036_a ? "SENTINEL" : "real")).append(' ');
        }
        System.out.println("array head: " + sb);
        System.out.println("same array across calls: " + (arr == c.func_76587_i()));

        Method m = NativeChunkPacket.class.getDeclaredMethod("isEligible", Chunk.class, int.class);
        m.setAccessible(true);
        try {
            Object r = m.invoke(null, c, 0x0007);
            System.out.println("isEligible(0x0007) = " + r);
        } catch (java.lang.reflect.InvocationTargetException ite) {
            System.out.println("isEligible THREW cause=" + ite.getCause());
        }
    }

    static void injectDummyWorld(Chunk chunk) throws Exception {
        Field uf = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        uf.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) uf.get(null);
        World world = (World) unsafe.allocateInstance(WorldServer.class);
        WorldProviderSurface provider = new WorldProviderSurface();
        Field isRemote = World.class.getDeclaredField("field_72995_K");
        isRemote.setAccessible(true);
        isRemote.setBoolean(world, false);
        Field provF = World.class.getDeclaredField("field_73011_w");
        provF.setAccessible(true);
        provF.set(world, provider);
        Field pw = WorldProvider.class.getDeclaredField("field_76579_a");
        pw.setAccessible(true);
        pw.set(provider, world);
        Field cf = Chunk.class.getDeclaredField("field_76637_e");
        cf.setAccessible(true);
        cf.set(chunk, world);
    }
}
