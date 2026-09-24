package com.rustcraft.oracle;

import com.rustcraft.bridge.capture.*;
import com.rustcraft.bridge.capture.CaptureContract.*;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.server.SPacketChunkData;
import net.minecraft.profiler.Profiler;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldProviderSurface;
import net.minecraft.world.chunk.*;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import io.netty.buffer.Unpooled;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Offline owned real-class oracle. Never wraps a published server Chunk.
 * Graphs are private, created here, never installed in a provider/world map,
 * and never returned. All graph operations are confined to the creating Thread.
 * Constructor capability listeners are required empty before allocation.
 */
final class OwnedForgeCapture implements CaptureSource {
    enum Fault { NONE, STATES, LIGHT, SKY, BIOME, STORAGE, REMOVE, REPLACE, UNLOAD, EXTENDED, UNKNOWN, ASYNC }
    private static final AtomicLong IDENTITIES = new AtomicLong();
    private final Thread owner = Thread.currentThread();
    private final OfflineWorld world;
    private Chunk chunk;
    private long incarnation = IDENTITIES.incrementAndGet(), generation = incarnation, epoch;
    private boolean active = true;
    private int filter = 65535;
    private final int globalBits;
    private final String provenance;
    private final Object[] identities = new Object[16], sections = new Object[16], containers = new Object[16], packed = new Object[16];
    private final long[][] logical = new long[16][];
    private Fault fault = Fault.NONE;
    private boolean fired;
    private String admissionFailure;

    private static final class OfflineProvider extends WorldProviderSurface {
        OfflineProvider(boolean sky) { field_191067_f = sky; }
    }
    /** Actual World constructor; no save handler, chunk provider, server or tick. */
    private static final class OfflineWorld extends World {
        OfflineWorld(boolean sky) { super(null, null, new OfflineProvider(sky), new Profiler(), false); }
        protected IChunkProvider func_72970_h() { throw new IllegalStateException("OFFLINE: provider forbidden"); }
        protected boolean func_175680_a(int x, int z, boolean allowEmpty) { return false; }
    }

    OwnedForgeCapture(boolean sky, int globalBits, String provenance) throws Exception {
        requireNoListeners();
        this.globalBits = globalBits;
        this.provenance = provenance;
        world = new OfflineWorld(sky);
        chunk = new Chunk(world, 6, 4);
        if (chunk.getCapabilities() != null) throw new IllegalStateException("Unexpected chunk capabilities");
        Arrays.fill(chunk.func_76605_m(), (byte) 1);
        requireNoListeners();
    }

    static Object field(Object instance, String name) {
        try {
            Class<?> type = instance.getClass();
            while (type != null) {
                try { Field f = type.getDeclaredField(name); f.setAccessible(true); return f.get(instance); }
                catch (NoSuchFieldException absent) { type = type.getSuperclass(); }
            }
            throw new NoSuchFieldException(name);
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
    }

    static void requireNoListeners() {
        try {
            com.rustcraft.offline.oracle.QualifyRuntime.assertNoAttachCapabilitiesListeners();
            com.rustcraft.offline.oracle.QualifyRuntime.assertQualifiedChunkLifecycleListeners();
        } catch (Exception failure) { throw new IllegalStateException("unqualified event dispatch", failure); }
    }

    private void owner() { if (Thread.currentThread() != owner) throw new IllegalStateException("OFFLINE owner violation"); }

    private boolean admitStates(List<IBlockState> states, int count) {
        if (count < 1 || count > states.size()) {
            admissionFailure = "FALLBACK_INVALID_STATE_INPUT"; return false;
        }
        // Validate before any actual container setter can discard an unknown
        // palette entry or narrow an ID. Applying the global bound even to local
        // inputs also protects existing global containers during in-place edits.
        for (int i = 0; i < count; i++) {
            IBlockState state = states.get(i);
            int id = Block.field_176229_d.func_148747_b(state);
            if (state == null || id < 0 || id > 65535
                    || Block.field_176229_d.func_148745_a(id) != state
                    || id >= (1 << globalBits)) {
                admissionFailure = "FALLBACK_REGISTRY_GLOBAL_ID_WIDTH"; return false;
            }
        }
        return admissionFailure == null;
    }

    private void writeStates(ExtendedBlockStorage section, List<IBlockState> states, int count) {
        try {
            for (int i = 0; i < 4096; i++)
                section.func_177484_a(i & 15, i >>> 8, (i >>> 4) & 15, states.get(i % count));
        } catch (RuntimeException failure) {
            // A partially applied rewrite must never become a later success.
            admissionFailure = "FALLBACK_MUTATION_INCOMPLETE";
            throw failure;
        }
    }

    void fill(int y, List<IBlockState> states, int count) {
        owner();
        if (!admitStates(states, count)) return;
        ExtendedBlockStorage s = new ExtendedBlockStorage(y << 4, world.field_73011_w.func_191066_m());
        writeStates(s, states, count);
        chunk.func_76587_i()[y] = s; epoch++;
    }
    void clear(int y) { owner(); chunk.func_76587_i()[y] = null; epoch++; }
    void rewrite(int y, List<IBlockState> states, int count) {
        owner();
        if (!admitStates(states, count)) return;
        ExtendedBlockStorage s=chunk.func_76587_i()[y];
        if(s==null)throw new IllegalStateException("rewrite requires present section");
        writeStates(s, states, count);
        epoch++;
    }
    void partial(int mask) { owner(); filter = mask; }
    void lights(int block, int sky) {
        owner();
        for (ExtendedBlockStorage s : chunk.func_76587_i()) if (s != null) {
            Arrays.fill(s.func_76661_k().func_177481_a(), (byte) block);
            if (world.field_73011_w.func_191066_m()) Arrays.fill(s.func_76671_l().func_177481_a(), (byte) sky);
        }
        epoch++;
    }
    void biome(int value) { owner(); Arrays.fill(chunk.func_76605_m(), (byte) value); epoch++; }
    void fault(Fault f) { owner(); fault = f; fired = false; }
    void unload() { owner(); requireNoListeners(); chunk.func_76623_d(); active = false; epoch++; }
    void reload() { owner(); requireNoListeners(); chunk.func_76631_c(); active = true; incarnation = IDENTITIES.incrementAndGet(); generation = incarnation; epoch++; }
    void replace() {
        owner(); requireNoListeners(); chunk = new Chunk(world, 6, 4);
        incarnation = IDENTITIES.incrementAndGet(); generation = incarnation; epoch++;
    }
    void addForbiddenTileEntity() {
        owner();
        // Exact actual transformed TE object; never call updateTag in this adapter.
        TileEntity te = new net.minecraft.tileentity.TileEntityChest();
        chunk.func_177434_r().put(new BlockPos(96, 0, 64), te);
    }

    private Context context(long expectedIncarnation, long expectedGeneration) {
        EnumMap<Domain, WriterClass> writers = new EnumMap<Domain, WriterClass>(Domain.class);
        for (Domain d : Domain.values()) writers.put(d, WriterClass.OWNER_THREAD_ONLY);
        if (fault == Fault.UNKNOWN) writers.put(Domain.BLOCK_STATES, WriterClass.UNKNOWN);
        if (fault == Fault.ASYNC) writers.put(Domain.BLOCK_LIGHT, WriterClass.ASYNC_UNCOORDINATED);
        return new Context(owner, expectedIncarnation, expectedGeneration,
                "clean-forge-exclusive-owned-graph-v1", "offline-owner-no-provider-no-TE", true, writers, null);
    }

    long incarnation() { return incarnation; }
    long generation() { return generation; }
    public boolean syntheticOfflineScope() { return false; }
    public Scope captureScope() { return Scope.REAL_CLEAN_FORGE_ORACLE; }

    public View readView() {
        owner();
        if (!active) throw new IllegalStateException("chunk lifecycle inactive");
        Section[] result = new Section[16];
        ExtendedBlockStorage[] slots = chunk.func_76587_i();
        for (int y = 0; y < 16; y++) {
            ExtendedBlockStorage s = slots[y];
            if (s == null) { sections[y] = containers[y] = packed[y] = identities[y] = null; logical[y] = null; continue; }
            if (s.getClass() != ExtendedBlockStorage.class || s.func_186049_g().getClass() != BlockStateContainer.class)
                throw new IllegalStateException("unsupported storage subclass");
            Object storage = s.func_186049_g();
            Object words = field(storage, "field_186021_b");
            if (sections[y] != s || containers[y] != storage || packed[y] != words) {
                sections[y] = s; containers[y] = storage; packed[y] = words;
                identities[y] = new Object(); logical[y] = new long[4096];
            }
            for (int i = 0; i < 4096; i++) {
                IBlockState state = s.func_177485_a(i & 15, i >>> 8, (i >>> 4) & 15);
                int id = Block.field_176229_d.func_148747_b(state);
                if (id < 0 || Block.field_176229_d.func_148745_a(id) != state)
                    throw new IllegalStateException("unresolved logical state");
                logical[y][i] = id; // Full int retained; no char/u16 staging.
            }
            if (fault == Fault.EXTENDED) logical[y][0] = 65536L; // negative control only
            int count = ((Integer) field(s, "field_76682_b")).intValue();
            result[y] = new Section(identities[y], logical[y], s.func_76661_k().func_177481_a(),
                    world.field_73011_w.func_191066_m() ? s.func_76671_l().func_177481_a() : null,
                    null, s.func_76663_a(), count);
        }
        return new View(chunk, slots, 0, chunk.field_76635_g, chunk.field_76647_h,
                incarnation, generation, epoch, filter, filter == 65535, world.field_73011_w.func_191066_m(),
                StorageModel.VANILLA_U16, globalBits, provenance, result, chunk.func_76605_m());
    }

    public void atPhase(Phase phase) {
        if (phase != Phase.BLOCK_LIGHT || fired) return;
        fired = true;
        ExtendedBlockStorage s = chunk.func_76587_i()[0];
        switch (fault) {
            case STATES: s.func_177484_a(0, 0, 0, Block.field_176229_d.func_148745_a(0)); epoch++; break;
            case LIGHT: s.func_76661_k().func_177481_a()[0] ^= 1; epoch++; break;
            case SKY: s.func_76671_l().func_177481_a()[0] ^= 1; epoch++; break;
            case BIOME: chunk.func_76605_m()[0] ^= 1; epoch++; break;
            case STORAGE: chunk.func_76587_i()[0] = new ExtendedBlockStorage(0, true); epoch++; break;
            case REMOVE: chunk.func_76587_i()[0] = null; epoch++; break;
            case REPLACE: replace(); break;
            case UNLOAD: unload(); break;
            default: break;
        }
    }

    /** Contains only immutable snapshots and byte copies; never the graph. */
    static final class Pair {
        final String rejection;
        final OwnedPacketSnapshot snapshot;
        final byte[] javaPacket;
        final Integer[] tickRefCounts;
        Pair(String rejection, OwnedPacketSnapshot snapshot, byte[] packet) {
            this(rejection, snapshot, packet, null);
        }
        Pair(String rejection, OwnedPacketSnapshot snapshot, byte[] packet, Integer[] tickRefCounts) {
            this.rejection = rejection; this.snapshot = snapshot; this.javaPacket = packet == null ? null : packet.clone();
            this.tickRefCounts = tickRefCounts == null ? null : tickRefCounts.clone();
        }
    }
    Pair capture(long expectedIncarnation, long expectedGeneration) throws Exception {
        if (Thread.currentThread() != owner) return new Pair("FALLBACK_OFF_THREAD", null, null);
        requireNoListeners();
        if (admissionFailure != null) return new Pair(admissionFailure, null, null);
        if (!active) return new Pair("FALLBACK_CHUNK_REPLACED", null, null);
        if (!chunk.func_177434_r().isEmpty()) return new Pair("FALLBACK_TE_UNQUALIFIED", null, null);
        Context context = context(expectedIncarnation, expectedGeneration);
        Result capture = SnapshotCapture.capture(this, context);
        if (!capture.accepted()) return new Pair(capture.reason().name(), null, null);
        OwnedPacketSnapshot owned = capture.snapshot();
        Integer[] tickRefCounts = new Integer[16];
        for (int y=0;y<16;y++) if ((owned.acceptedMask & (1 << y)) != 0)
            tickRefCounts[y] = (Integer)field(chunk.func_76587_i()[y], "field_76683_c");
        // No user callbacks, writers, publication or thread switch between
        // capture and this exact transformed constructor/PacketBuffer writer.
        SPacketChunkData packet = new SPacketChunkData(chunk, filter);
        PacketBuffer bytes = new PacketBuffer(Unpooled.buffer());
        byte[] wire;
        try { packet.func_148840_b(bytes); wire = new byte[bytes.readableBytes()]; bytes.getBytes(0, wire); }
        finally { bytes.release(); }
        Result end = SnapshotCapture.capture(this, context);
        if (!end.accepted() || !sameContents(owned, end.snapshot()))
            return new Pair("FALLBACK_JAVA_REFERENCE_CHANGED", null, null);
        for(int y=0;y<16;y++) if(tickRefCounts[y]!=null && !tickRefCounts[y].equals(field(chunk.func_76587_i()[y],"field_76683_c")))
            return new Pair("FALLBACK_JAVA_REFERENCE_CHANGED",null,null);
        requireNoListeners();
        return new Pair(null, owned, wire, tickRefCounts);
    }

    private static boolean sameContents(OwnedPacketSnapshot a, OwnedPacketSnapshot b) {
        // Event IDs differ for the second validation. All logical/identity
        // metadata other than the per-attempt event ID must remain identical.
        byte[] x = a.toTransportBytes(), y = b.toTransportBytes();
        Arrays.fill(x, 40, 48, (byte) 0); Arrays.fill(y, 40, 48, (byte) 0);
        return Arrays.equals(x, y);
    }
}
