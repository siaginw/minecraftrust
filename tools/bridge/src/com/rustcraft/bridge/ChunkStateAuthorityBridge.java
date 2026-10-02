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
        public final long[] blockLightPointers = new long[16];
        public final long[] skyLightPointers = new long[16];
        public volatile long biomesPointer;
        public volatile long heightmapPointer;
        public volatile boolean dirty;

        public ChunkAuthorityRecord(int dim, int cx, int cz, long generationId, AuthoritativeMode mode) {
            this.dim = dim;
            this.cx = cx;
            this.cz = cz;
            this.generationId = generationId;
            this.mode = mode;
        }
    }

    private static final Map<Long, ChunkAuthorityRecord> RECORDS = new ConcurrentHashMap<>();

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

    // Telemetry Counters
    public static final AtomicLong CHUNK_API_RUST_READ = new AtomicLong();
    public static final AtomicLong CHUNK_API_RUST_WRITE = new AtomicLong();
    public static final AtomicLong SECTION_API_RUST_READ = new AtomicLong();
    public static final AtomicLong SECTION_API_RUST_WRITE = new AtomicLong();
    public static final AtomicLong LIGHT_API_RUST_READ = new AtomicLong();
    public static final AtomicLong LIGHT_API_RUST_WRITE = new AtomicLong();
    public static final AtomicLong CONTAINER_MATERIALIZATIONS = new AtomicLong();
    public static final AtomicLong CONTAINER_CACHE_HITS = new AtomicLong();
    public static final AtomicLong REFLECTION_READS = new AtomicLong();
    public static final AtomicLong REFLECTION_WRITES = new AtomicLong();
    public static final AtomicLong BYPASS_DETECTED = new AtomicLong();
    public static final AtomicLong FOREIGN_THREAD_READS = new AtomicLong();
    public static final AtomicLong SERVER_THREAD_READS = new AtomicLong();

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

    private static long chunkKey(int dim, int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
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
        long key = chunkKey(dim, cx, cz);
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

        // Fetch light pointers from native memory (block light + sky light)
        ByteBuffer blBuf = ByteBuffer.allocateDirect(128).order(ByteOrder.nativeOrder());
        ByteBuffer slBuf = ByteBuffer.allocateDirect(128).order(ByteOrder.nativeOrder());
        long blAddr = getBufferAddress(blBuf);
        long slAddr = getBufferAddress(slBuf);
        if (blAddr != 0 && slAddr != 0) {
            int okLight = NativeChunkBridge.getSectionLightPointers(dim, cx, cz, blAddr, slAddr);
            if (okLight > 0) {
                for (int s = 0; s < 16; s++) {
                    record.blockLightPointers[s] = blBuf.getLong(s * 8);
                    record.skyLightPointers[s] = slBuf.getLong(s * 8);
                }
            }
        }

        // Fetch biome and heightmap pointers from native memory
        record.biomesPointer = NativeChunkBridge.getBiomesPointer(dim, cx, cz);
        record.heightmapPointer = NativeChunkBridge.getHeightmapPointer(dim, cx, cz);

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
        long key = chunkKey(dim, cx, cz);
        unbindChunkStorages(dim, cx, cz);
        ChunkAuthorityRecord record = RECORDS.remove(key);
        if (record != null) {
            record.mode = AuthoritativeMode.DEMOTED;
            record.generationId = 0;
            for (int i = 0; i < 16; i++) {
                record.sectionPointers[i] = 0;
                record.blockLightPointers[i] = 0;
                record.skyLightPointers[i] = 0;
            }
            record.biomesPointer = 0;
            record.heightmapPointer = 0;
        }
    }

    public static void demoteChunk(int dim, int cx, int cz, String reason) {
        long key = chunkKey(dim, cx, cz);
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

            String threadName = Thread.currentThread().getName();
            if (threadName.startsWith("Server thread")) {
                SERVER_THREAD_READS.incrementAndGet();
            } else {
                FOREIGN_THREAD_READS.incrementAndGet();
            }

            long secPtr = record.sectionPointers[y >> 4];
            NativeChunkBridge.RUST_READS.incrementAndGet();
            CHUNK_API_RUST_READ.incrementAndGet();
            if (secPtr == 0) {
                return StateRegistryLookup.getAirState();
            }

            return StateRegistryLookup.getBlockStateDirect(secPtr, x, y, z);
        } catch (Throwable t) {
            NativeChunkBridge.JAVA_READS.incrementAndGet();
            return null;
        }
    }

    private static final Map<ExtendedBlockStorage, SectionAuthorityBinding> SECTION_BINDINGS = new ConcurrentHashMap<>();
    private static final Map<Long, ExtendedBlockStorage[]> CHUNK_STORAGES = new ConcurrentHashMap<>();

    public static final class SectionAuthorityBinding {
        public final ChunkAuthorityRecord chunkRecord;
        public final int secY;
        public SectionAuthorityBinding(ChunkAuthorityRecord chunkRecord, int secY) {
            this.chunkRecord = chunkRecord;
            this.secY = secY;
        }
    }

    public static void bindStorage(ExtendedBlockStorage storage, ChunkAuthorityRecord record, int secY) {
        if (storage != null && record != null) {
            SECTION_BINDINGS.put(storage, new SectionAuthorityBinding(record, secY));
        }
    }

    public static void unbindStorage(ExtendedBlockStorage storage) {
        if (storage != null) {
            SECTION_BINDINGS.remove(storage);
        }
    }

    public static void bindChunkStorages(Chunk chunk, ChunkAuthorityRecord record) {
        if (chunk == null || record == null) return;
        try {
            ensureReflection();
            if (chunkStorageArrays != null) {
                ExtendedBlockStorage[] storages = (ExtendedBlockStorage[]) chunkStorageArrays.get(chunk);
                if (storages != null) {
                    CHUNK_STORAGES.put(chunkKey(record.dim, record.cx, record.cz), storages);
                    for (int y = 0; y < storages.length; y++) {
                        if (storages[y] != null) {
                            bindStorage(storages[y], record, y);
                        }
                    }
                }
            }
        } catch (Throwable t) {
            // Non-fatal: section-level access will fall back to container
        }
    }

    public static void unbindChunkStorages(int dim, int cx, int cz) {
        ExtendedBlockStorage[] storages = CHUNK_STORAGES.remove(chunkKey(dim, cx, cz));
        if (storages != null) {
            for (ExtendedBlockStorage storage : storages) {
                if (storage != null) {
                    unbindStorage(storage);
                }
            }
        }
    }

    public static void onStorageArraysReplaced(Chunk chunk, ExtendedBlockStorage[] newStorages) {
        if (chunk == null) return;
        try {
            int dim = 0; // Overworld scope
            long key = chunkKey(dim, chunk.field_76635_g, chunk.field_76647_h);
            ChunkAuthorityRecord record = RECORDS.get(key);
            if (record != null) {
                unbindChunkStorages(dim, chunk.field_76635_g, chunk.field_76647_h);
                if (newStorages != null) {
                    CHUNK_STORAGES.put(key, newStorages);
                    for (int y = 0; y < newStorages.length; y++) {
                        if (newStorages[y] != null) {
                            bindStorage(newStorages[y], record, y);
                        }
                    }
                }
            }
        } catch (Throwable t) {
            // Non-fatal
        }
    }

    /**
     * Section-level getBlockState entry hook for ExtendedBlockStorage.get / func_177485_a.
     * Backed directly by Rust native memory via zero-JNI direct read.
     */
    public static IBlockState getSectionBlockState(ExtendedBlockStorage storage, int x, int y, int z) {
        if (!experimentEnabled || storage == null) return null;
        try {
            SectionAuthorityBinding binding = SECTION_BINDINGS.get(storage);
            if (binding == null) {
                return null; // Unbound storage: fallback to Java BlockStateContainer
            }
            ChunkAuthorityRecord record = binding.chunkRecord;
            if (record == null || record.generationId <= 0 || (record.mode != AuthoritativeMode.RUST_AUTHORITATIVE && record.mode != AuthoritativeMode.RUST_MIRRORED)) {
                return null;
            }

            long currentOps = TOTAL_OPERATIONS.incrementAndGet();
            if (currentOps > authorityCap) {
                return null;
            }

            long secPtr = record.sectionPointers[binding.secY];
            SECTION_API_RUST_READ.incrementAndGet();
            NativeChunkBridge.RUST_READS.incrementAndGet();
            if (secPtr == 0) {
                return StateRegistryLookup.getAirState();
            }

            return StateRegistryLookup.getBlockStateDirect(secPtr, x, y, z);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Section-level setBlockState entry hook for ExtendedBlockStorage.set / func_177484_a.
     * Intercepts bypass writes and commits directly to Rust authority.
     */
    public static boolean trySetSectionBlockState(ExtendedBlockStorage storage, int x, int y, int z, IBlockState newState) {
        if (!experimentEnabled || storage == null || newState == null) return false;
        try {
            SectionAuthorityBinding binding = SECTION_BINDINGS.get(storage);
            if (binding == null) {
                return false;
            }
            ChunkAuthorityRecord record = binding.chunkRecord;
            if (record == null || record.generationId <= 0 || (record.mode != AuthoritativeMode.RUST_AUTHORITATIVE && record.mode != AuthoritativeMode.RUST_MIRRORED)) {
                return false;
            }

            long currentOps = TOTAL_OPERATIONS.incrementAndGet();
            if (currentOps > authorityCap) {
                return false;
            }

            int newStateId = StateRegistryLookup.getId(newState);
            if (newStateId < 0 || newStateId > 65535) {
                NativeChunkBridge.HIGH_STATE_FALLBACK.incrementAndGet();
                NativeChunkBridge.FALLBACKS.incrementAndGet();
                return false;
            }

            int worldY = (binding.secY << 4) | (y & 15);
            long packed = NativeChunkBridge.setBlockState(record.dim, record.cx, record.cz, x & 15, worldY, z & 15, newStateId);
            int status = (byte) (packed & 0xFF);
            if (status == 1) {
                return true; // NO_OP
            }
            if (status < 0) {
                return false;
            }

            SECTION_API_RUST_WRITE.incrementAndGet();
            NativeChunkBridge.RUST_WRITES.incrementAndGet();
            boolean sectionCreated = (packed & (1L << 8)) != 0;
            if (sectionCreated) {
                NativeChunkBridge.SECTION_CREATIONS.incrementAndGet();
                record.sectionPointers[binding.secY] = NativeChunkBridge.getSectionPointer(record.dim, record.cx, record.cz, binding.secY);
                record.blockLightPointers[binding.secY] = NativeChunkBridge.getSectionLightPointer(record.dim, record.cx, record.cz, binding.secY, 0);
                record.skyLightPointers[binding.secY] = NativeChunkBridge.getSectionLightPointer(record.dim, record.cx, record.cz, binding.secY, 1);
            }
            record.dirty = true;
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Authoritative Block Light read: reads directly from native [AtomicU8; 2048] memory.
     * Returns 0..15 if handled, or -1 to fall back to Java.
     */
    public static int getBlockLight(ExtendedBlockStorage storage, int x, int y, int z) {
        if (!experimentEnabled || storage == null) return -1;
        try {
            SectionAuthorityBinding binding = SECTION_BINDINGS.get(storage);
            if (binding == null) return -1;
            ChunkAuthorityRecord record = binding.chunkRecord;
            if (record == null || record.generationId <= 0 || (record.mode != AuthoritativeMode.RUST_AUTHORITATIVE && record.mode != AuthoritativeMode.RUST_MIRRORED)) {
                return -1;
            }
            long currentOps = TOTAL_OPERATIONS.incrementAndGet();
            if (currentOps > authorityCap) return -1;

            long blPtr = record.blockLightPointers[binding.secY];
            LIGHT_API_RUST_READ.incrementAndGet();
            if (blPtr == 0) return 0; // Absent section has 0 block light

            return StateRegistryLookup.readLightNibble(blPtr, x, y, z);
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * Authoritative Block Light write: writes directly to native [AtomicU8; 2048] memory via atomic CAS.
     * Returns true if handled, false to fall back to Java.
     */
    public static boolean setBlockLight(ExtendedBlockStorage storage, int x, int y, int z, int val) {
        if (!experimentEnabled || storage == null) return false;
        try {
            SectionAuthorityBinding binding = SECTION_BINDINGS.get(storage);
            if (binding == null) return false;
            ChunkAuthorityRecord record = binding.chunkRecord;
            if (record == null || record.generationId <= 0 || (record.mode != AuthoritativeMode.RUST_AUTHORITATIVE && record.mode != AuthoritativeMode.RUST_MIRRORED)) {
                return false;
            }
            long currentOps = TOTAL_OPERATIONS.incrementAndGet();
            if (currentOps > authorityCap) return false;

            long blPtr = record.blockLightPointers[binding.secY];
            if (blPtr == 0) return false; // Storage not yet backed by native section

            LIGHT_API_RUST_WRITE.incrementAndGet();
            StateRegistryLookup.writeLightNibble(blPtr, x, y, z, val);
            record.dirty = true;
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Authoritative Sky Light read: reads directly from native [AtomicU8; 2048] memory.
     * Returns 0..15 if handled, or -1 to fall back to Java.
     */
    public static int getSkyLight(ExtendedBlockStorage storage, int x, int y, int z) {
        if (!experimentEnabled || storage == null) return -1;
        try {
            SectionAuthorityBinding binding = SECTION_BINDINGS.get(storage);
            if (binding == null) return -1;
            ChunkAuthorityRecord record = binding.chunkRecord;
            if (record == null || record.generationId <= 0 || (record.mode != AuthoritativeMode.RUST_AUTHORITATIVE && record.mode != AuthoritativeMode.RUST_MIRRORED)) {
                return -1;
            }
            long currentOps = TOTAL_OPERATIONS.incrementAndGet();
            if (currentOps > authorityCap) return -1;

            long slPtr = record.skyLightPointers[binding.secY];
            LIGHT_API_RUST_READ.incrementAndGet();
            if (slPtr == 0) return 15; // Absent section has 15 sky light by default in overworld

            return StateRegistryLookup.readLightNibble(slPtr, x, y, z);
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * Authoritative Sky Light write: writes directly to native [AtomicU8; 2048] memory via atomic CAS.
     * Returns true if handled, false to fall back to Java.
     */
    public static boolean setSkyLight(ExtendedBlockStorage storage, int x, int y, int z, int val) {
        if (!experimentEnabled || storage == null) return false;
        try {
            SectionAuthorityBinding binding = SECTION_BINDINGS.get(storage);
            if (binding == null) return false;
            ChunkAuthorityRecord record = binding.chunkRecord;
            if (record == null || record.generationId <= 0 || (record.mode != AuthoritativeMode.RUST_AUTHORITATIVE && record.mode != AuthoritativeMode.RUST_MIRRORED)) {
                return false;
            }
            long currentOps = TOTAL_OPERATIONS.incrementAndGet();
            if (currentOps > authorityCap) return false;

            long slPtr = record.skyLightPointers[binding.secY];
            if (slPtr == 0) return false;

            LIGHT_API_RUST_WRITE.incrementAndGet();
            StateRegistryLookup.writeLightNibble(slPtr, x, y, z, val);
            record.dirty = true;
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Authoritative Biome read: reads directly from native [u8; 256] biomes memory via Unsafe.
     * Returns 0..255 if handled, or -1 to fall back to Java.
     */
    public static int getBiome(Chunk chunk, int x, int z) {
        if (!experimentEnabled || chunk == null) return -1;
        try {
            int dim = 0; // Overworld
            ChunkAuthorityRecord record = RECORDS.get(chunkKey(dim, chunk.field_76635_g, chunk.field_76647_h));
            if (record == null || record.generationId <= 0 || (record.mode != AuthoritativeMode.RUST_AUTHORITATIVE && record.mode != AuthoritativeMode.RUST_MIRRORED)) {
                return -1;
            }
            long currentOps = TOTAL_OPERATIONS.incrementAndGet();
            if (currentOps > authorityCap) return -1;

            long bioPtr = record.biomesPointer;
            if (bioPtr == 0) return -1;

            int idx = ((z & 15) << 4) | (x & 15);
            return StateRegistryLookup.getUnsafe().getByte(bioPtr + idx) & 0xFF;
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * Authoritative Biome write: writes directly to native [u8; 256] biomes memory.
     * Returns true if handled, false to fall back to Java.
     */
    public static boolean setBiome(Chunk chunk, int x, int z, int biomeId) {
        if (!experimentEnabled || chunk == null) return false;
        try {
            int dim = 0;
            ChunkAuthorityRecord record = RECORDS.get(chunkKey(dim, chunk.field_76635_g, chunk.field_76647_h));
            if (record == null || record.generationId <= 0 || (record.mode != AuthoritativeMode.RUST_AUTHORITATIVE && record.mode != AuthoritativeMode.RUST_MIRRORED)) {
                return false;
            }
            long currentOps = TOTAL_OPERATIONS.incrementAndGet();
            if (currentOps > authorityCap) return false;

            long bioPtr = record.biomesPointer;
            if (bioPtr == 0) return false;

            int idx = ((z & 15) << 4) | (x & 15);
            StateRegistryLookup.getUnsafe().putByte(bioPtr + idx, (byte) (biomeId & 0xFF));
            record.dirty = true;
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Authoritative Heightmap read: reads directly from native [u16; 256] heightmap memory via Unsafe.
     * Returns 0..256 if handled, or -1 to fall back to Java.
     */
    public static int getHeight(Chunk chunk, int x, int z) {
        if (!experimentEnabled || chunk == null) return -1;
        try {
            int dim = 0;
            ChunkAuthorityRecord record = RECORDS.get(chunkKey(dim, chunk.field_76635_g, chunk.field_76647_h));
            if (record == null || record.generationId <= 0 || (record.mode != AuthoritativeMode.RUST_AUTHORITATIVE && record.mode != AuthoritativeMode.RUST_MIRRORED)) {
                return -1;
            }
            long currentOps = TOTAL_OPERATIONS.incrementAndGet();
            if (currentOps > authorityCap) return -1;

            long hmPtr = record.heightmapPointer;
            if (hmPtr == 0) return -1;

            int idx = ((z & 15) << 4) | (x & 15);
            return StateRegistryLookup.getUnsafe().getShort(hmPtr + ((long) idx << 1)) & 0xFFFF;
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * Synchronizes full biome array from Java setBiomeArray into native chunk memory.
     */
    public static void onBiomeArraySet(Chunk chunk, byte[] biomes) {
        if (!experimentEnabled || chunk == null || biomes == null) return;
        try {
            int dim = 0;
            ChunkAuthorityRecord record = RECORDS.get(chunkKey(dim, chunk.field_76635_g, chunk.field_76647_h));
            if (record != null && record.biomesPointer != 0 && biomes.length == 256) {
                for (int i = 0; i < 256; i++) {
                    StateRegistryLookup.getUnsafe().putByte(record.biomesPointer + i, biomes[i]);
                }
                record.dirty = true;
            }
        } catch (Throwable ignore) {}
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
            CHUNK_API_RUST_WRITE.incrementAndGet();
            boolean sectionCreated = (packed & (1L << 8)) != 0;
            boolean sectionBecameEmpty = (packed & (1L << 9)) != 0;
            int oldStateId = (int) ((packed >>> 16) & 0xFFFFL);
            IBlockState oldState = StateRegistryLookup.getState(oldStateId);
            Block oldBlock = oldState.func_177230_c();

            int secY = y >> 4;
            if (sectionCreated) {
                NativeChunkBridge.SECTION_CREATIONS.incrementAndGet();
                record.sectionPointers[secY] = NativeChunkBridge.getSectionPointer(dim, cx, cz, secY);
                record.blockLightPointers[secY] = NativeChunkBridge.getSectionLightPointer(dim, cx, cz, secY, 0);
                record.skyLightPointers[secY] = NativeChunkBridge.getSectionLightPointer(dim, cx, cz, secY, 1);
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
                            bindStorage(storage, record, secY);
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
