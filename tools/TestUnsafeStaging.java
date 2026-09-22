import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import sun.misc.Unsafe;

public class TestUnsafeStaging {
    private static Unsafe unsafe;
    static {
        try {
            Field f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            unsafe = (Unsafe) f.get(null);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static void main(String[] args) throws Exception {
        int iters = 10000;
        long[] words = new long[1024];
        ByteBuffer buf = ByteBuffer.allocateDirect(262144);
        long addr = ((sun.nio.ch.DirectBuffer) buf).address();

        // Warmup
        for (int i = 0; i < 5000; i++) {
            buf.clear();
            for (int s = 0; s < 16; s++) {
                for (long w : words) buf.putLong(w);
            }
        }

        long t0 = System.nanoTime();
        for (int i = 0; i < iters; i++) {
            buf.clear();
            for (int s = 0; s < 16; s++) {
                for (long w : words) buf.putLong(w);
            }
        }
        long t1 = System.nanoTime();
        double loopUs = ((t1 - t0) / (double) iters) / 1000.0;
        System.out.printf("Loop putLong (16 sections): %6.2f µs\n", loopUs);

        // Warmup Unsafe
        for (int i = 0; i < 5000; i++) {
            long dst = addr;
            for (int s = 0; s < 16; s++) {
                unsafe.copyMemory(words, Unsafe.ARRAY_LONG_BASE_OFFSET, null, dst, 1024 * 8);
                dst += 1024 * 8;
            }
        }

        long tu0 = System.nanoTime();
        for (int i = 0; i < iters; i++) {
            long dst = addr;
            for (int s = 0; s < 16; s++) {
                unsafe.copyMemory(words, Unsafe.ARRAY_LONG_BASE_OFFSET, null, dst, 1024 * 8);
                dst += 1024 * 8;
            }
        }
        long tu1 = System.nanoTime();
        double unsafeUs = ((tu1 - tu0) / (double) iters) / 1000.0;
        System.out.printf("Unsafe copyMemory (16 sections): %6.2f µs\n", unsafeUs);
    }
}
