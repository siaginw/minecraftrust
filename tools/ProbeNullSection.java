import net.minecraft.init.Bootstrap;
import net.minecraft.network.play.server.SPacketChunkData;
import net.minecraft.world.World;
import net.minecraft.world.WorldProvider;
import net.minecraft.world.WorldProviderSurface;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import java.lang.reflect.Field;

public class ProbeNullSection {
    static Field maskField;
    static {
        try { maskField = SPacketChunkData.class.getDeclaredField("field_186948_c"); maskField.setAccessible(true); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    static void probe(String label, Chunk c, int mask) {
        try {
            SPacketChunkData p = new SPacketChunkData(c, mask);
            System.out.println(label + ": NO THROW, effMask=0x" + Integer.toHexString(maskField.getInt(p)));
        } catch (Throwable t) {
            System.out.println(label + ": THREW " + t.getClass().getName() + " at " + t.getStackTrace()[0]);
        }
    }

    public static void main(String[] a) throws Exception {
        Bootstrap.func_151354_b();

        Chunk c = new Chunk(null, 0, 0);
        injectDummyWorld(c);
        int nulls = 0, sentinels = 0, real = 0;
        for (ExtendedBlockStorage e : c.func_76587_i()) { if (e == null) nulls++; else if (e == Chunk.field_186036_a) sentinels++; else real++; }
        System.out.println("fresh chunk (dummy world): null=" + nulls + " sentinel=" + sentinels + " real=" + real);

        Chunk c2 = new Chunk(null, 0, 0);
        injectDummyWorld(c2);
        c2.func_76587_i()[0] = new ExtendedBlockStorage(0, true);
        c2.func_76587_i()[1] = null;
        probe("null-bit partial(0x0003)", c2, 0x0003);

        Chunk c3 = new Chunk(null, 0, 0);
        injectDummyWorld(c3);
        c3.func_76587_i()[0] = new ExtendedBlockStorage(0, true);
        c3.func_76587_i()[1] = null;
        probe("null full(0xFFFF)", c3, 0xFFFF);

        Chunk c4 = new Chunk(null, 0, 0);
        injectDummyWorld(c4);
        c4.func_76587_i()[0] = Chunk.field_186036_a;
        c4.func_76587_i()[1] = new ExtendedBlockStorage(16, true);
        probe("sentinel+real partial(0x0003)", c4, 0x0003);

        Chunk c5 = new Chunk(null, 0, 0);
        injectDummyWorld(c5);
        c5.func_76587_i()[0] = new ExtendedBlockStorage(0, true);
        probe("no-null partial(0x0001)", c5, 0x0001);
    }

    static void injectDummyWorld(Chunk chunk) throws Exception {
        Field uf = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        uf.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) uf.get(null);
        World world = (World) unsafe.allocateInstance(WorldServer.class);
        WorldProviderSurface provider = new WorldProviderSurface();
        Field isRemote = World.class.getDeclaredField("field_72995_K");
        isRemote.setAccessible(true); isRemote.setBoolean(world, false);
        Field provF = World.class.getDeclaredField("field_73011_w");
        provF.setAccessible(true); provF.set(world, provider);
        Field pw = WorldProvider.class.getDeclaredField("field_76579_a");
        pw.setAccessible(true); pw.set(provider, world);
        Field cf = Chunk.class.getDeclaredField("field_76637_e");
        cf.setAccessible(true); cf.set(chunk, world);
    }
}
