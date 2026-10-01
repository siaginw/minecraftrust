package com.rustcraft.bridge;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * RustCraft SEMANTIC ENGINE OWNERSHIP BRIDGE.
 *
 * Makes Rust NativeChunk the authoritative source of truth for admitted
 * Chunk.getBlockState and Chunk.setBlockState calls, while maintaining 100%
 * behavioral compatibility with Minecraft 1.12.2 / Forge.
 *
 * Core principles:
 * - NO DOUBLE-WRITE SEMANTIC STATE: Rust decides mutations; Java receives results.
 * - NO HOT JNI TAX ON READS: getBlockState reads native memory directly via Unsafe.
 * - PRESERVE FORGE CALLBACK ORDER: Forge lifecycle callbacks occur in reference order.
 * - BOUNDED EXPERIMENT: Fail-closed to Java if unadmitted or cap reached.
 */
public final class ChunkStateAuthorityBridge {

    public static final String PROPERTY_EXPERIMENT = "rustcraft.chunkStateAuthorityExperiment";
    public static final String PROPERTY_CAP = "rustcraft.chunkStateAuthorityCap";
    public static final String PROPERTY_MIRROR = "rustcraft.chunkStateMirrorMode";

    public static final boolean PRODUCTION_AUTHORITY = false;

    private static volatile boolean experimentEnabled = Boolean.getBoolean(PROPERTY_EXPERIMENT);
    private static volatile long authorityCap = Long.getLong(PROPERTY_CAP, 1000L).longValue();
    private static volatile MirrorMode mirrorMode = parseMirrorMode(System.getProperty(PROPERTY_MIRROR, "none"));

    public static final AtomicLong TOTAL_OPERATIONS = new AtomicLong();

    public enum AuthoritativeMode {
        JAVA_AUTHORITATIVE,
        RUST_MIRRORED,
        RUST_AUTHORITATIVE,
        DEMOTED,
        RESYNC_REQUIRED
    }

    public enum MirrorMode {
        NONE,
        EAGER,
        LAZY
    }

    private static MirrorMode parseMirrorMode(String str) {
        if ("eager".equalsIgnoreCase(str)) return MirrorMode.EAGER;
        if ("lazy".equalsIgnoreCase(str)) return MirrorMode.LAZY;
        return MirrorMode.NONE;
    }

    public static final class ChunkAuthorityRecord {
        public final int dim;
        public final int cx;
        public final int cz;
        public volatile long generationId;
        public volatile AuthoritativeMode mode;
        public final long[] sectionPointers = new long[16];
        public volatile boolean dirty;

        public ChunkAuthorityRecord(int dim, int cx, int cz, long generationId, AuthoritativeMode mode) {
            this.dim = dim;
            this.cx = cx;
            this.cz = cz;
            this.generationId = generationId;
            this.mode = mode;
        }
    }

    private static final Map<String, ChunkAuthorityRecord> RECORDS = new ConcurrentHashMap<>();

    // Reflection handles into Chunk
    private static Field chunkPrecipitationHeightMap;
    private static Field chunkHeightMap;
    private static Field chunkWorld;
    private static Field chunkStorageArrays;
    private static Field chunkLoaded;
    private static Method chunkRelightBlock;
    private static Method chunkCreateNewTileEntity;
    private static volatile boolean reflectionInitialized = false;

    public static final class MutationResult {
        public final boolean handled;
        public final IBlockState oldState;

        public static final MutationResult UNHANDLED = new MutationResult(false, null);
        public static final MutationResult NO_OP = new MutationResult(true, null);

        private MutationResult(boolean handled, IBlockState oldState) {
            this.handled = handled;
            this.oldState = oldState;
        }

        public static MutationResult handled(IBlockState oldState) {
            return new MutationResult(true, oldState);
        }
    }

    private ChunkStateAuthorityBridge() {}

    public static boolean isEnabled() {
        return experimentEnabled;
    }

    public static void setEnabled(boolean enabled) {
        experimentEnabled = enabled;
    }

    public static long getCap() {
        return authorityCap;
    }

    public static void setCap(long cap) {
        authorityCap = cap;
    }

    public static MirrorMode getMirrorMode() {
        return mirrorMode;
    }

    public static void setMirrorMode(MirrorMode mode) {
        mirrorMode = mode;
    }

    private static String chunkKey(int dim, int cx, int cz) {
        return dim + ":" + cx + ":" + cz;
    }

    public static long getBufferAddress(ByteBuffer buf) {
        try {
            Field f = Buffer.class.getDeclaredField("address");
            f.setAccessible(true);
            return f.getLong(buf);
        } catch (Throwable ignore) {}
        try {
            Method m = buf.getClass().getMethod("address");
            m.setAccessible(true);
            return ((Number) m.invoke(buf)).longValue();
        } catch (Throwable t) {
            return 0;
        }
    }

    private static int getDimension(World world) {
        if (world == null || world.field_73011_w == null) return -999;
        try {
            return world.field_73011_w.func_186058_p().func_186068_a();
        } catch (Throwable t) {
            try {
                Method m = world.field_73011_w.getClass().getMethod("getDimension");
                return (Integer) m.invoke(world.field_73011_w);
            } catch (Throwable t2) {
                return -999;
            }
        }
    }

    private static boolean hasTileEntity(Block block, IBlockState state) {
        if (block == null) return false;
        try {
            Method m = block.getClass().getMethod("hasTileEntity", IBlockState.class);
            return (Boolean) m.invoke(block, state);
        } catch (Throwable t) {
            return block.func_149716_u();
        }
    }

    private static boolean shouldRefresh(TileEntity te, World world, BlockPos pos, IBlockState oldState, IBlockState newState) {
        if (te == null) return false;
        try {
            Method m = te.getClass().getMethod("shouldRefresh", World.class, BlockPos.class, IBlockState.class, IBlockState.class);
            return (Boolean) m.invoke(te, world, pos, oldState, newState);
        } catch (Throwable t) {
            return oldState.func_177230_c() != newState.func_177230_c();
        }
    }

    private static boolean isCapturingSnapshots(World world) {
        try {
            Field f = world.getClass().getField("captureBlockSnapshots");
            return f.getBoolean(world);
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean hasSkyLight(World world) {
        if (world == null || world.field_73011_w == null) return false;
        try {
            return world.field_73011_w.func_177495_o();
        } catch (Throwable t) {
            try {
                Method m = world.field_73011_w.getClass().getMethod("hasSkyLight");
                return (Boolean) m.invoke(world.field_73011_w);
            } catch (Throwable t2) {
                return false;
            }
        }
    }

    private static boolean isChunkLoaded(Chunk chunk) {
        if (chunk == null) return false;
        try {
            ensureReflection();
            if (chunkLoaded != null) {
                return chunkLoaded.getBoolean(chunk);
            }
            return true; // Fallback if field inaccessible
        } catch (Throwable t) {
            return true;
        }
    }

    private static void ensureReflection() {
        if (reflectionInitialized) return;
        synchronized (ChunkStateAuthorityBridge.class) {
            if (reflectionInitialized) return;
            try {
                // Precipitation heightmap: field_76638_b
                try {
                    chunkPrecipitationHeightMap = Chunk.class.getDeclaredField("field_76638_b");
                } catch (NoSuchFieldException e) {
                    chunkPrecipitationHeightMap = Chunk.class.getDeclaredField("precipitationHeightMap");
                }
                chunkPrecipitationHeightMap.setAccessible(true);

                // Heightmap: field_76634_f
                try {
                    chunkHeightMap = Chunk.class.getDeclaredField("field_76634_f");
                } catch (NoSuchFieldException e) {
                    chunkHeightMap = Chunk.class.getDeclaredField("heightMap");
                }
                chunkHeightMap.setAccessible(true);

                // World: field_76637_e
                try {
                    chunkWorld = Chunk.class.getDeclaredField("field_76637_e");
                } catch (NoSuchFieldException e) {
                    chunkWorld = Chunk.class.getDeclaredField("world");
                }
                chunkWorld.setAccessible(true);

                // Storage arrays: field_76652_q
                try {
                    chunkStorageArrays = Chunk.class.getDeclaredField("field_76652_q");
                } catch (NoSuchFieldException e) {
                    chunkStorageArrays = Chunk.class.getDeclaredField("storageArrays");
                }
                chunkStorageArrays.setAccessible(true);

                // Loaded flag: field_76636_d / loaded
                try {
                    chunkLoaded = Chunk.class.getDeclaredField("field_76636_d");
                } catch (NoSuchFieldException e) {
                    try {
                        chunkLoaded = Chunk.class.getDeclaredField("loaded");
                    } catch (NoSuchFieldException e2) {
                        chunkLoaded = null;
                    }
                }
                if (chunkLoaded != null) {
                    chunkLoaded.setAccessible(true);
                }

                // relightBlock: func_76595_e(int, int, int)
                try {
                    chunkRelightBlock = Chunk.class.getDeclaredMethod("func_76595_e", int.class, int.class, int.class);
                } catch (NoSuchMethodException e) {
                    chunkRelightBlock = Chunk.class.getDeclaredMethod("relightBlock", int.class, int.class, int.class);
                }
                chunkRelightBlock.setAccessible(true);

                // createNewTileEntity: func_177432_g(BlockPos)
                try {
                    chunkCreateNewTileEntity = Chunk.class.getDeclaredMethod("func_177432_g", BlockPos.class);
                } catch (NoSuchMethodException e) {
                    chunkCreateNewTileEntity = Chunk.class.getDeclaredMethod("createNewTileEntity", BlockPos.class);
                }
                chunkCreateNewTileEntity.setAccessible(true);

            } catch (Throwable t) {
                System.err.println("[RustCraft] Failed to initialize Chunk reflection handles: " + t);
            }
            reflectionInitialized = true;
        }
    }

    /**
     * Registers or updates a chunk for Rust semantic authority.
     */
    public static ChunkAuthorityRecord registerChunkAuthority(int dim, int cx, int cz, long genId) {
        if (genId <= 0) return null;
        String key = chunkKey(dim, cx, cz);
        ChunkAuthorityRecord record = new ChunkAuthorityRecord(dim, cx, cz, genId,
                mirrorMode == MirrorMode.EAGER ? AuthoritativeMode.RUST_MIRRORED : AuthoritativeMode.RUST_AUTHORITATIVE);

        // Fetch section pointers from native memory
        ByteBuffer buf = ByteBuffer.allocateDirect(128).order(ByteOrder.nativeOrder());
        long bufAddr = getBufferAddress(buf);
        if (bufAddr != 0) {
            int ok = NativeChunkBridge.getSectionPointers(dim, cx, cz, bufAddr);
            if (ok > 0) {
                for (int s = 0; s < 16; s++) {
                    record.sectionPointers[s] = buf.getLong(s * 8);
                }
            }
        }
        RECORDS.put(key, record);
        return record;
    }

    public static ChunkAuthorityRecord getRecord(int dim, int cx, int cz) {
        return RECORDS.get(chunkKey(dim, cx, cz));
    }

    /**
     * Unregisters chunk from Rust semantic authority, clearing record and zeroing pointer table.
     * Prevents any subsequent Use-After-Free or stale generation reads.
     */
    public static void unregisterChunkAuthority(int dim, int cx, int cz) {
        String key = chunkKey(dim, cx, cz);
        ChunkAuthorityRecord record = RECORDS.remove(key);
        if (record != null) {
            record.mode = AuthoritativeMode.DEMOTED;
            record.generationId = 0;
            for (int i = 0; i < 16; i++) {
                record.sectionPointers[i] = 0;
            }
        }
    }

    public static void demoteChunk(int dim, int cx, int cz, String reason) {
        String key = chunkKey(dim, cx, cz);
        ChunkAuthorityRecord r = RECORDS.get(key);
        if (r != null) {
            r.mode = AuthoritativeMode.DEMOTED;
            NativeChunkBridge.DEMOTIONS.incrementAndGet();
            System.err.println("[RustCraft-Authority] Chunk (" + dim + "," + cx + "," + cz + ") demoted to Java: " + reason);
        }
    }

    public static boolean isAuthorityActive(Chunk chunk) {
        if (!experimentEnabled) return false;
        if (chunk == null) return false;
        try {
            ensureReflection();
            World w = (World) chunkWorld.get(chunk);
            if (w == null || getDimension(w) != 0) return false; // Overworld scope only

            ChunkAuthorityRecord record = RECORDS.get(chunkKey(0, chunk.field_76635_g, chunk.field_76647_h));
            return record != null && (record.mode == AuthoritativeMode.RUST_AUTHORITATIVE || record.mode == AuthoritativeMode.RUST_MIRRORED);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Authoritative getBlockState: zero-JNI fast path.
     * Returns IBlockState if handled by Rust, or null to fall back to Java.
     */
    public static IBlockState getBlockState(Chunk chunk, int x, int y, int z) {
        if (!experimentEnabled) return null;
        if (y < 0 || y >= 256) {
            return StateRegistryLookup.getAirState();
        }

        try {
            ensureReflection();
            World w = (World) chunkWorld.get(chunk);
            if (w == null || getDimension(w) != 0) return null;
            int dim = 0;
            if (!isChunkLoaded(chunk)) {
                NativeChunkBridge.JAVA_READS.incrementAndGet();
                return null;
            }
            ChunkAuthorityRecord record = RECORDS.get(chunkKey(dim, chunk.field_76635_g, chunk.field_76647_h));
            if (record == null || record.generationId <= 0 || (record.mode != AuthoritativeMode.RUST_AUTHORITATIVE && record.mode != AuthoritativeMode.RUST_MIRRORED)) {
                NativeChunkBridge.JAVA_READS.incrementAndGet();
                return null;
            }

            long currentOps = TOTAL_OPERATIONS.incrementAndGet();
            if (currentOps > authorityCap) {
                NativeChunkBridge.JAVA_READS.incrementAndGet();
                return null; // Bounded experiment cap reached
            }

            long secPtr = record.sectionPointers[y >> 4];
            NativeChunkBridge.RUST_READS.incrementAndGet();
            if (secPtr == 0) {
                return StateRegistryLookup.getAirState();
            }

            return StateRegistryLookup.getBlockStateDirect(secPtr, x, y, z);
        } catch (Throwable t) {
            NativeChunkBridge.JAVA_READS.incrementAndGet();
            return null;
        }
    }

    /**
     * Authoritative setBlockState: commits to Rust first, then dispatches Forge side effects.
     */
    public static MutationResult trySetBlockState(Chunk chunk, BlockPos pos, IBlockState newState) {
        if (!experimentEnabled) return MutationResult.UNHANDLED;
        if (pos == null || newState == null) return MutationResult.UNHANDLED;

        try {
            ensureReflection();
            World world = (World) chunkWorld.get(chunk);
            if (world == null || getDimension(world) != 0) {
                NativeChunkBridge.JAVA_WRITES.incrementAndGet();
                return MutationResult.UNHANDLED;
            }

            int dim = 0;
            int cx = chunk.field_76635_g;
            int cz = chunk.field_76647_h;
            if (!isChunkLoaded(chunk)) {
                NativeChunkBridge.JAVA_WRITES.incrementAndGet();
                return MutationResult.UNHANDLED;
            }
            ChunkAuthorityRecord record = RECORDS.get(chunkKey(dim, cx, cz));
            if (record == null || record.generationId <= 0 || (record.mode != AuthoritativeMode.RUST_AUTHORITATIVE && record.mode != AuthoritativeMode.RUST_MIRRORED)) {
                NativeChunkBridge.JAVA_WRITES.incrementAndGet();
                return MutationResult.UNHANDLED;
            }

            long currentOps = TOTAL_OPERATIONS.incrementAndGet();
            if (currentOps > authorityCap) {
                NativeChunkBridge.JAVA_WRITES.incrementAndGet();
                return MutationResult.UNHANDLED;
            }

            int x = pos.func_177958_n() & 15;
            int y = pos.func_177956_o();
            int z = pos.func_177952_p() & 15;

            if (y < 0 || y >= 256) {
                return MutationResult.NO_OP;
            }

            // Gate: TileEntity check (narrow scope excludes positions with TileEntities)
            TileEntity existingTe = chunk.func_177424_a(pos, Chunk.EnumCreateEntityType.CHECK);
            Block newBlock = newState.func_177230_c();
            if (existingTe != null || hasTileEntity(newBlock, newState)) {
                NativeChunkBridge.TE_FALLBACK.incrementAndGet();
                NativeChunkBridge.FALLBACKS.incrementAndGet();
                NativeChunkBridge.JAVA_WRITES.incrementAndGet();
                return MutationResult.UNHANDLED;
            }

            int newStateId = StateRegistryLookup.getId(newState);
            if (newStateId < 0 || newStateId > 65535) {
                NativeChunkBridge.HIGH_STATE_FALLBACK.incrementAndGet();
                NativeChunkBridge.FALLBACKS.incrementAndGet();
                NativeChunkBridge.JAVA_WRITES.incrementAndGet();
                return MutationResult.UNHANDLED;
            }

            // 1. Mutate Rust ChunkState FIRST (Authoritative commit)
            long packed = NativeChunkBridge.setBlockState(dim, cx, cz, x, y, z, newStateId);
            int status = (byte) (packed & 0xFF);

            if (status == 1) {
                // NO_OP: State is already equal to newState
                return MutationResult.NO_OP;
            }

            if (status < 0) {
                // Rust failed to mutate (e.g. chunk missing / out of bounds)
                if (status == -2) {
                    NativeChunkBridge.STALE_HANDLE_FALLBACK.incrementAndGet();
                } else {
                    NativeChunkBridge.ERROR_FALLBACK.incrementAndGet();
                }
                NativeChunkBridge.FALLBACKS.incrementAndGet();
                NativeChunkBridge.JAVA_WRITES.incrementAndGet();
                return MutationResult.UNHANDLED;
            }

            // Successfully committed in Rust!
            NativeChunkBridge.RUST_WRITES.incrementAndGet();
            boolean sectionCreated = (packed & (1L << 8)) != 0;
            boolean sectionBecameEmpty = (packed & (1L << 9)) != 0;
            int oldStateId = (int) ((packed >>> 16) & 0xFFFFL);
            IBlockState oldState = StateRegistryLookup.getState(oldStateId);
            Block oldBlock = oldState.func_177230_c();

            int secY = y >> 4;
            if (sectionCreated) {
                NativeChunkBridge.SECTION_CREATIONS.incrementAndGet();
                record.sectionPointers[secY] = NativeChunkBridge.getSectionPointer(dim, cx, cz, secY);
            }
            if (sectionBecameEmpty) {
                NativeChunkBridge.SECTION_EMPTIED.incrementAndGet();
            }
            record.dirty = true;

            // 2. Optional downstream compatibility mirror (if eager mode requested)
            if (mirrorMode == MirrorMode.EAGER) {
                try {
                    ExtendedBlockStorage[] storages = (ExtendedBlockStorage[]) chunkStorageArrays.get(chunk);
                    if (storages != null) {
                        ExtendedBlockStorage storage = storages[secY];
                        if (storage == null && sectionCreated) {
                            storage = new ExtendedBlockStorage(secY << 4, hasSkyLight(world));
                            storages[secY] = storage;
                        }
                        if (storage != null) {
                            storage.func_177484_a(x, y & 15, z, newState);
                        }
                    }
                } catch (Throwable ignore) {}
            }

            // 3. Dispatch Minecraft / Forge Lifecycle Side Effects in Exact Order
            int k = (z << 4) | x;
            int[] precipMap = (int[]) chunkPrecipitationHeightMap.get(chunk);
            if (precipMap != null && y >= precipMap[k] - 1) {
                precipMap[k] = -999;
            }

            // 3a. Break block callback
            if (!world.field_72995_K && oldBlock != newBlock) {
                oldBlock.func_180663_b(world, pos, oldState);
            }

            // 3b. Tile entity refresh check
            if (existingTe != null && shouldRefresh(existingTe, world, pos, oldState, newState)) {
                world.func_175713_t(pos);
            }

            // 3c. Relight block & column heightmap update
            chunkRelightBlock.invoke(chunk, x, y, z);

            // 3d. Light checks
            world.func_180500_c(EnumSkyBlock.SKY, pos);
            world.func_180500_c(EnumSkyBlock.BLOCK, pos);

            // 3e. OnBlockAdded callback
            if (!world.field_72995_K && oldBlock != newBlock && (!isCapturingSnapshots(world) || hasTileEntity(newBlock, newState))) {
                newBlock.func_176213_c(world, pos, newState);
            }

            // 3f. Mark chunk dirty
            chunk.func_76630_e();

            return MutationResult.handled(oldState);

        } catch (Throwable t) {
            System.err.println("[RustCraft-Authority] setBlockState exception, failing closed to Java: " + t);
            NativeChunkBridge.ERROR_FALLBACK.incrementAndGet();
            NativeChunkBridge.FALLBACKS.incrementAndGet();
            NativeChunkBridge.JAVA_WRITES.incrementAndGet();
            return MutationResult.UNHANDLED;
        }
    }
}
