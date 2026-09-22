import com.rustcraft.bridge.NativeChunkPacket;
import net.minecraft.init.Bootstrap;
import net.minecraft.network.play.server.SPacketChunkData;
import net.minecraft.world.World;
import net.minecraft.world.WorldProvider;
import net.minecraft.world.WorldProviderSurface;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import java.lang.reflect.Field;

public class ProbeNativeNull {
    public static void main(String[] a) throws Exception {
        Bootstrap.func_151354_b();
        Chunk c = new Chunk(null, 0, 0);
        injectDummyWorld(c);
        c.func_76587_i()[0] = new ExtendedBlockStorage(0, true);
        c.func_76587_i()[1] = null; // bit 1 set in 0x0007
        NativeChunkPacket.setRuntimeMode("ON_EXPERIMENTAL");
        SPacketChunkData p = new SPacketChunkData();
        long t0 = NativeChunkPacket.M1_NATIVE_FALLBACKS.get();
        long t1 = NativeChunkPacket.M1_NATIVE_PACKETS_TRANSMITTED.get();
        boolean ok;
        try { ok = NativeChunkPacket.populatePacket(p, c, 0x0007); }
        catch (Throwable t) { ok = false; System.out.println("populatePacket THREW " + t + " at " + t.getStackTrace()[0]); }
        long t2 = NativeChunkPacket.M1_NATIVE_FALLBACKS.get();
        long t3 = NativeChunkPacket.M1_NATIVE_PACKETS_TRANSMITTED.get();
        System.out.println("populatePacket(null+bit-set, 0x0007) = " + ok
                + " fallbacksDelta=" + (t2 - t0) + " transmittedDelta=" + (t3 - t1));
        Field mf = SPacketChunkData.class.getDeclaredField("field_186948_c");
        mf.setAccessible(true);
        System.out.println("packet effMask=0x" + Integer.toHexString(mf.getInt(p)));
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
