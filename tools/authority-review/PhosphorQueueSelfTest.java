import me.jellysquid.mods.phosphor.mod.collections.PooledLongQueue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * Offline probe of the PhosphorLightHook queue-capture path against the
 * REAL PooledLongQueue: replicate the hook's reflective heuristic (no-arg
 * method returning long[] or *Iterator*), consume via hasNext/next, and
 * report what the hook would see. No server boot needed.
 */
public class PhosphorQueueSelfTest {

    public static void main(String[] args) throws Exception {
        Class<?> pool = Class.forName(
                "me.jellysquid.mods.phosphor.mod.collections.PooledLongQueue$Pool");
        Constructor<PooledLongQueue> ctor =
                PooledLongQueue.class.getDeclaredConstructor(pool);
        ctor.setAccessible(true);
        java.lang.reflect.Constructor<?> poolCtor = pool.getDeclaredConstructor();
        poolCtor.setAccessible(true);
        PooledLongQueue q = ctor.newInstance(poolCtor.newInstance());
        long p1 = pack(10, 64, 12);
        long p2 = pack(-3, 70, 200);
        q.add(p1);
        q.add(p2);
        System.out.println("size=" + q.size() + " isEmpty=" + q.isEmpty());

        // the hook's heuristic
        Method copy = null;
        for (Method m : PooledLongQueue.class.getDeclaredMethods()) {
            if (m.getParameterCount() == 0
                    && (m.getReturnType() == long[].class
                        || m.getReturnType().getName().contains("Iterator"))) {
                copy = m;
                break;
            }
        }
        System.out.println("heuristic picked: "
                + (copy == null ? "NONE" : copy.getName() + " -> "
                        + copy.getReturnType()));
        if (copy == null) {
            System.out.println("PHOSPHOR_QUEUE_SELFTEST_FAILED");
            return;
        }
        Object result = copy.invoke(q);
        int n = 0;
        if (result instanceof long[]) {
            for (long v : (long[]) result) {
                n++;
                System.out.println("  entry: " + unpack(v));
            }
        } else {
            Method hasNext = result.getClass().getMethod("hasNext");
            Method next = result.getClass().getMethod("next");
            System.out.println("iterator type: " + result.getClass().getName());
            while ((Boolean) hasNext.invoke(result)) {
                long v = (Long) next.invoke(result);
                n++;
                System.out.println("  entry: " + unpack(v));
            }
        }
        System.out.println("captured=" + n);
        System.out.println(n == 2 ? "PHOSPHOR_QUEUE_SELFTEST_PASSED"
                : "PHOSPHOR_QUEUE_SELFTEST_FAILED");
    }

    // The queue stores longs opaquely — the capture path under test is
    // add -> iterator round-trip, so any invertible packing works.
    private static final long LX = 0xFFFFFFFFC0000000L, LY = 0x3FFFFFF0L, LZ = 0xFFFFC00000000000L;

    private static long pack(int x, int y, int z) {
        return (((long) x << 38) & LX) | (((long) y << 4) & LY)
                | (((long) z << 10) & LZ);
    }

    private static String unpack(long v) {
        return "x=" + ((v & LX) >>> 38) + " y=" + ((v & LY) >>> 4)
                + " z=" + ((v & LZ) >>> 10);
    }
}
