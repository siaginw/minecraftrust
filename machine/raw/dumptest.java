public class dumptest {
    public static void main(String[] a) {
        try { System.out.println("1:" + com.rustcraft.bridge.NativeChunkPacket.dumpMetrics().length()); } catch (Throwable t) { System.out.println("1 FAILED: " + t); }
        try { System.out.println("2:" + com.rustcraft.bridge.NativeCompressionEncoder.dumpMetrics().length()); } catch (Throwable t) { System.out.println("2 FAILED: " + t); }
        try { System.out.println("3:" + com.rustcraft.coremod.WorldCollisionProbeTransformer.transformCount); } catch (Throwable t) { System.out.println("3 FAILED: " + t); }
        try { System.out.println("4:" + com.rustcraft.bridge.CollisionProbe.dump().length()); } catch (Throwable t) { t.printStackTrace(); System.out.println("4 FAILED: " + t); }
    }
}
