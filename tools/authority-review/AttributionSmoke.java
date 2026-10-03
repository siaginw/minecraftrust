import java.io.RandomAccessFile;

/** Offline smoke for the goal-§4 attribution agent. */
public class AttributionSmoke {
    public static void main(String[] a) throws Exception {
        String dir = a[0];
        // 1. a "vanilla RegionFile-like" write path (frame contains the class
        //    name the classifier looks for is emulated here: our frames do NOT
        //    contain RegionFile -> everything is UNKNOWN by construction; the
        //    in-server journal proves the real classification)
        try (RandomAccessFile raf = new RandomAccessFile(
                dir + "/_c__test_world_region_r.0.0.mca", "rw")) {
            raf.seek(8192);
            raf.write(new byte[64], 0, 64);
            raf.writeInt(0x0702);
            raf.seek(4096);
            raf.writeInt(7);
        }
        System.out.println(com.rustcraft.offline.agent.WriterAttribution.dump());
        // writeInt funnels through both hooked entries on JDK8, so each
        // writeInt captures twice: expect at least 4 events, all captured
        // with a caller frame, all UNKNOWN here (no RegionFile in scope)
        if (com.rustcraft.offline.agent.WriterAttribution.EVENTS.get() < 4) {
            throw new IllegalStateException("expected >=4 capture events");
        }
        System.out.println("ATTRIBUTION_SMOKE_PASSED");
    }
}
