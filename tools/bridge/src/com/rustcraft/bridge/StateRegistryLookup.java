package com.rustcraft.bridge;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.ObjectIntIdentityMap;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.util.Iterator;

/**
 * Ultra-fast canonical IBlockState <-> GlobalStateId lookup table and direct native memory accessor.
 *
 * Provides:
 * - O(1) array lookup from global state ID (0..65535) to canonical Java IBlockState reference (~1 CPU instruction).
 * - Direct memory reading via sun.misc.Unsafe with ZERO JNI boundary crossings for getBlockState hot path.
 * - Dynamic registry synchronization on boot.
 */
public final class StateRegistryLookup {

    private static final int TABLE_SIZE = 65536;
    private static final IBlockState[] STATE_TABLE = new IBlockState[TABLE_SIZE];
    private static volatile IBlockState airState;
    private static volatile boolean initialized = false;

    public static final Unsafe UNSAFE;

    static {
        Unsafe u = null;
        try {
            Field f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            u = (Unsafe) f.get(null);
        } catch (Throwable t) {
            try {
                for (Field field : Unsafe.class.getDeclaredFields()) {
                    if (field.getType() == Unsafe.class) {
                        field.setAccessible(true);
                        u = (Unsafe) field.get(null);
                        break;
                    }
                }
            } catch (Throwable t2) {
                u = null;
            }
        }
        UNSAFE = u;
    }

    public static Unsafe getUnsafe() {
        return UNSAFE;
    }

    private StateRegistryLookup() {}

    /**
     * Ensures the lookup table is populated from the live Block.BLOCK_STATE_IDS registry.
     */
    @SuppressWarnings("unchecked")
    public static synchronized void ensureInitialized() {
        if (initialized) return;
        try {
            airState = Blocks.field_150350_a.func_176223_P();
        } catch (Throwable t) {
            // Offline fallback
            airState = null;
        }

        try {
            ObjectIntIdentityMap<IBlockState> registry = Block.field_176229_d;
            if (registry != null) {
                Iterator<IBlockState> it = registry.iterator();
                while (it.hasNext()) {
                    IBlockState state = it.next();
                    int id = registry.func_148747_b(state);
                    if (id >= 0 && id < TABLE_SIZE) {
                        STATE_TABLE[id] = state;
                    }
                }
            }
        } catch (Throwable t) {
            // When registry is not available on classpath
        }

        // Fill any null slots with Air state
        if (airState != null) {
            for (int i = 0; i < TABLE_SIZE; i++) {
                if (STATE_TABLE[i] == null) {
                    STATE_TABLE[i] = airState;
                }
            }
        }

        initialized = true;
    }

    /**
     * Fast O(1) canonical state lookup by global state ID.
     */
    public static IBlockState getState(int globalId) {
        if (!initialized) ensureInitialized();
        if (globalId >= 0 && globalId < TABLE_SIZE) {
            IBlockState s = STATE_TABLE[globalId];
            return s != null ? s : airState;
        }
        return airState;
    }

    /**
     * Looks up global state ID for a given IBlockState.
     */
    public static int getId(IBlockState state) {
        if (state == null) return 0;
        try {
            return Block.field_176229_d.func_148747_b(state);
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * Direct memory read: reads the full-width (32-bit) global block state ID
     * from the native section states array at (x, y, z).
     * Zero JNI overhead. Uses memory load fence to prevent load reordering across architectures.
     */
    public static int readStateId(long sectionPtr, int x, int y, int z) {
        int idx = ((y & 15) << 8) | ((z & 15) << 4) | (x & 15);
        if (UNSAFE != null) {
            UNSAFE.loadFence();
            // full-width u32 state cells: 4-byte stride, plain read
            return UNSAFE.getInt(sectionPtr + ((long) idx << 2));
        }
        return 0;
    }

    /**
     * Volatile direct memory read: full-width (32-bit) global state ID, acquire semantics.
     */
    public static int readStateIdVolatile(long sectionPtr, int x, int y, int z) {
        int idx = ((y & 15) << 8) | ((z & 15) << 4) | (x & 15);
        if (UNSAFE != null) {
            // volatile variant preserves the acquire ordering contract
            return UNSAFE.getIntVolatile(null, sectionPtr + ((long) idx << 2));
        }
        return 0;
    }

    /**
     * Direct memory read + lookup: returns canonical IBlockState from native section pointer.
     * Zero JNI overhead, zero Java object allocation.
     */
    public static IBlockState getBlockStateDirect(long sectionPtr, int x, int y, int z) {
        if (!initialized) ensureInitialized();
        int stateId = readStateId(sectionPtr, x, y, z);
        IBlockState state = STATE_TABLE[stateId];
        return state != null ? state : airState;
    }

    public static IBlockState getAirState() {
        if (!initialized) ensureInitialized();
        return airState;
    }

    /**
     * Section block index formula matching Minecraft: (y << 8) | (z << 4) | x.
     */
    public static int getSectionIndex(int x, int y, int z) {
        return ((y & 15) << 8) | ((z & 15) << 4) | (x & 15);
    }

    /**
     * Direct memory read of 4-bit light value (0..15) from native [AtomicU32; 512] light buffer.
     * Zero JNI overhead. Reads 32-bit atomic word directly, operating at exact same width as Rust AtomicU32.
     */
    public static int readLightNibble(long lightPtr, int x, int y, int z) {
        if (lightPtr == 0 || UNSAFE == null) return 0;
        int idx = getSectionIndex(x, y, z);
        int wordIdx = idx >> 3; // 8 nibbles per 32-bit word
        int shift = (idx & 7) << 2; // (idx % 8) * 4
        long wordAddr = lightPtr + ((long) wordIdx << 2);
        int word = UNSAFE.getIntVolatile(null, wordAddr);
        return (word >>> shift) & 0x0F;
    }

    /**
     * Direct memory write of 4-bit light value (0..15) into native [AtomicU32; 512] light buffer.
     * Uses atomic CAS loop via Unsafe.compareAndSwapInt matching Rust's AtomicU32.compare_exchange exactly.
     * Returns true if value was changed, false if already equal.
     */
    public static boolean writeLightNibble(long lightPtr, int x, int y, int z, int val) {
        if (lightPtr == 0 || UNSAFE == null) return false;
        int idx = getSectionIndex(x, y, z);
        int wordIdx = idx >> 3;
        int shift = (idx & 7) << 2;
        int mask = 0x0F << shift;
        int newBits = (val & 0x0F) << shift;
        long wordAddr = lightPtr + ((long) wordIdx << 2);

        while (true) {
            int currentWord = UNSAFE.getIntVolatile(null, wordAddr);
            int currentNibble = (currentWord >>> shift) & 0x0F;
            if (currentNibble == (val & 0x0F)) {
                return false; // No change
            }
            int updatedWord = (currentWord & ~mask) | newBits;
            if (UNSAFE.compareAndSwapInt(null, wordAddr, currentWord, updatedWord)) {
                return true;
            }
        }
    }
}
