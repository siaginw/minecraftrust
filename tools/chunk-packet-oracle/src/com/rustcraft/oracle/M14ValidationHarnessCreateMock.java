
package com.rustcraft.oracle;

import java.util.Random;
import java.lang.reflect.Method;

public class M14ValidationHarnessCreateMock {
    public static Object createMockChunkPublic(int n, Random rng) throws Exception {
        Method m = M14ValidationHarness.class.getDeclaredMethod("createMockChunk", int.class, Random.class);
        m.setAccessible(true);
        return m.invoke(null, n, rng);
    }
}
