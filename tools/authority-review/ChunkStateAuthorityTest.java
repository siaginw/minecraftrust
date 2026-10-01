package com.rustcraft.authority;

import com.rustcraft.bridge.ChunkStateAuthorityBridge;
import com.rustcraft.bridge.NativeChunkBridge;
import com.rustcraft.bridge.StateRegistryLookup;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Random;

/**
 * RustCraft SEMANTIC ENGINE OWNERSHIP Verification & Differential Fuzzing Suite.
 *
 * Verifies:
 * 1. Safety controls: experiment flag, default OFF, production authority false, bounded cap.
 * 2. Rust authoritative block mutation semantics:
 *    - Air in absent section -> NO_OP, no section allocated
 *    - Solid in absent section -> section created, mask updated, non-air count = 1
 *    - Same state -> NO_OP
 *    - Solid to different solid -> state updated, old state returned
 *    - Emptying section -> sectionBecameEmpty = true, mask updated
 *    - Coordinate boundary handling
 * 3. Direct memory read (Zero-JNI) consistency with native state.
 * 4. Read-after-write coherence across multiple sections and coordinates.
 * 5. Mutation -> Packet end-to-end proof without reseed.
 * 6. Differential Fuzzing against reference oracle across 10,000+ operations.
 * 7. Microbenchmarking: p50/p95/p99 latency for reads and writes.
 */
public class ChunkStateAuthorityTest {

    private static void assertEquals(long expected, long actual, String msg) {
        if (expected != actual) {
            throw new AssertionError(msg + " - expected: " + expected + ", actual: " + actual);
        }
    }

    private static void assertTrue(boolean condition, String msg) {
        if (!condition) {
            throw new AssertionError(msg + " - expected true, was false");
        }
    }

    private static void assertFalse(boolean condition, String msg) {
        if (condition) {
            throw new AssertionError(msg + " - expected false, was true");
        }
    }

    private static void ensureDll() {
        if (!NativeChunkBridge.isAvailable()) {
            File f = new File("rustcraft_ffi.dll");
            if (f.exists()) {
                System.load(f.getAbsolutePath());
            } else {
                File rel = new File("target/release/rustcraft_ffi.dll");
                if (rel.exists()) {
                    System.load(rel.getAbsolutePath());
                } else {
                    System.loadLibrary("rustcraft_ffi");
                }
            }
        }
        assertTrue(NativeChunkBridge.isAvailable(), "Native DLL must be loaded for authority tests");
    }

    public static void main(String[] args) throws Exception {
        System.out.println("==================================================================");
        System.out.println("  RustCraft Semantic Engine Ownership Verification Suite");
        System.out.println("==================================================================");

        ensureDll();

        // 1. Safety Controls
        testSafetyControls();

        // 2. Authoritative Mutation Semantics
        testMutationSemantics();

        // 3. Direct Memory Zero-JNI Reads
        testDirectMemoryZeroJNI();

        // 4. Read-After-Write Consistency
        testReadAfterWriteCoherence();

        // 5. Mutation -> Packet End-to-End Proof
        testMutationToPacketEndToEnd();

        // 6. Differential Fuzzing Oracle (10,000 operations)
        testDifferentialFuzzing(10000, 42L);

        // 7. Performance Benchmarks
        runPerformanceBenchmarks();

        System.out.println("==================================================================");
        System.out.println("  ALL SEMANTIC ENGINE OWNERSHIP TESTS PASSED WITH 0 MISMATCHES!  ");
        System.out.println("==================================================================");
    }

    private static void testSafetyControls() {
        System.out.println("--> [1/7] Testing Safety Controls & Invariants...");

        // Production authority must be hardcoded false
        assertFalse(ChunkStateAuthorityBridge.PRODUCTION_AUTHORITY,
                "PRODUCTION_AUTHORITY must be strictly FALSE");

        // Default disabled
        ChunkStateAuthorityBridge.setEnabled(false);
        assertFalse(ChunkStateAuthorityBridge.isEnabled(),
                "Authority must be disabled when property is false");

        // Bounded cap
        ChunkStateAuthorityBridge.setCap(50);
        assertEquals(50, ChunkStateAuthorityBridge.getCap(), "Cap must match configured value");

        // Demotion
        ChunkStateAuthorityBridge.demoteChunk(0, 10, 20, "TestDemotion");
        ChunkStateAuthorityBridge.ChunkAuthorityRecord rec = ChunkStateAuthorityBridge.getRecord(0, 10, 20);
        // Not registered yet, record is null or demoted if created
        ChunkStateAuthorityBridge.registerChunkAuthority(0, 10, 20, 1L);
        ChunkStateAuthorityBridge.demoteChunk(0, 10, 20, "TestDemotion");
        assertEquals(ChunkStateAuthorityBridge.AuthoritativeMode.DEMOTED.ordinal(),
                ChunkStateAuthorityBridge.getRecord(0, 10, 20).mode.ordinal(),
                "Demoted chunk must have DEMOTED mode");

        System.out.println("    [PASS] Safety controls verified.");
    }

    private static void testMutationSemantics() {
        System.out.println("--> [2/7] Testing Authoritative Rust Block Mutation Semantics...");

        // Seed a fresh chunk at (0, 1, 2)
        int dim = 0;
        int cx = 1;
        int cz = 2;
        long primerBuf = ByteBuffer.allocateDirect(65536 * 2).order(ByteOrder.nativeOrder()).hashCode();
        ByteBuffer pb = ByteBuffer.allocateDirect(65536 * 2).order(ByteOrder.nativeOrder());
        ByteBuffer bb = ByteBuffer.allocateDirect(256).order(ByteOrder.nativeOrder());
        long pAddr = ChunkStateAuthorityBridge.getBufferAddress(pb);
        long bAddr = ChunkStateAuthorityBridge.getBufferAddress(bb);

        long genId = NativeChunkBridge.register(dim, cx, cz, pAddr, bAddr);
        assertTrue(genId > 0, "Chunk registration must succeed");

        // Query initial air state
        int state = NativeChunkBridge.getBlockState(dim, cx, cz, 4, 35, 7);
        assertEquals(0, state, "Initial state in empty section must be air (0)");

        // 1. Setting Air in absent section -> NO_OP (status 1)
        long res = NativeChunkBridge.setBlockState(dim, cx, cz, 4, 35, 7, 0);
        int status = (byte) (res & 0xFF);
        assertEquals(1, status, "Setting air in absent section must return NO_OP (1)");
        boolean secCreated = (res & (1L << 8)) != 0;
        assertFalse(secCreated, "Section must not be created for air write");

        // 2. Setting solid block (1) in absent section -> SUCCESS (status 0)
        res = NativeChunkBridge.setBlockState(dim, cx, cz, 4, 35, 7, 1);
        status = (byte) (res & 0xFF);
        assertEquals(0, status, "Setting solid in absent section must return SUCCESS (0)");
        secCreated = (res & (1L << 8)) != 0;
        assertTrue(secCreated, "Section must be created for non-air write");
        int oldState = (int) ((res >>> 16) & 0xFFFFL);
        int newState = (int) ((res >>> 32) & 0xFFFFL);
        int nonAir = (int) ((res >>> 48) & 0xFFFFL);
        assertEquals(0, oldState, "Old state must be 0");
        assertEquals(1, newState, "New state must be 1");
        assertEquals(1, nonAir, "Non-air count must be 1");

        // Query state: must be 1
        state = NativeChunkBridge.getBlockState(dim, cx, cz, 4, 35, 7);
        assertEquals(1, state, "getBlockState must return 1 after mutation");

        // 3. Setting same block (1) -> NO_OP (status 1)
        res = NativeChunkBridge.setBlockState(dim, cx, cz, 4, 35, 7, 1);
        status = (byte) (res & 0xFF);
        assertEquals(1, status, "Setting same state must return NO_OP (1)");

        // 4. Changing to different solid block (3) -> SUCCESS (status 0)
        res = NativeChunkBridge.setBlockState(dim, cx, cz, 4, 35, 7, 3);
        status = (byte) (res & 0xFF);
        assertEquals(0, status, "Changing solid state must return SUCCESS (0)");
        oldState = (int) ((res >>> 16) & 0xFFFFL);
        newState = (int) ((res >>> 32) & 0xFFFFL);
        nonAir = (int) ((res >>> 48) & 0xFFFFL);
        assertEquals(1, oldState, "Old state must be 1");
        assertEquals(3, newState, "New state must be 3");
        assertEquals(1, nonAir, "Non-air count must remain 1");

        // 5. Adding another block in same section at (0, 32, 0)
        res = NativeChunkBridge.setBlockState(dim, cx, cz, 0, 32, 0, 5);
        status = (byte) (res & 0xFF);
        assertEquals(0, status, "Adding block must return SUCCESS (0)");
        nonAir = (int) ((res >>> 48) & 0xFFFFL);
        assertEquals(2, nonAir, "Non-air count must become 2");

        // 6. Removing first block (set to 0) -> SUCCESS
        res = NativeChunkBridge.setBlockState(dim, cx, cz, 4, 35, 7, 0);
        status = (byte) (res & 0xFF);
        assertEquals(0, status, "Removing block must return SUCCESS (0)");
        boolean becameEmpty = (res & (1L << 9)) != 0;
        assertFalse(becameEmpty, "Section must not be empty yet");
        nonAir = (int) ((res >>> 48) & 0xFFFFL);
        assertEquals(1, nonAir, "Non-air count must become 1");

        // 7. Removing second block (set to 0) -> Section becomes empty!
        res = NativeChunkBridge.setBlockState(dim, cx, cz, 0, 32, 0, 0);
        status = (byte) (res & 0xFF);
        assertEquals(0, status, "Removing final block must return SUCCESS (0)");
        becameEmpty = (res & (1L << 9)) != 0;
        assertTrue(becameEmpty, "Section must become empty when all non-air blocks removed");
        nonAir = (int) ((res >>> 48) & 0xFFFFL);
        assertEquals(0, nonAir, "Non-air count must be 0");

        // 8. Bounds check: invalid coordinates return -1
        res = NativeChunkBridge.setBlockState(dim, cx, cz, 16, 0, 0, 1);
        status = (byte) (res & 0xFF);
        assertEquals(-1, status, "x=16 must be rejected as out of bounds (-1)");

        res = NativeChunkBridge.setBlockState(dim, cx, cz, 0, 256, 0, 1);
        status = (byte) (res & 0xFF);
        assertEquals(-1, status, "y=256 must be rejected as out of bounds (-1)");

        System.out.println("    [PASS] Authoritative mutation semantics verified.");
    }

    private static void testDirectMemoryZeroJNI() {
        System.out.println("--> [3/7] Testing Direct Memory (Zero-JNI) Read Architecture...");

        int dim = 0;
        int cx = 3;
        int cz = 4;
        ByteBuffer pb = ByteBuffer.allocateDirect(65536 * 2).order(ByteOrder.nativeOrder());
        ByteBuffer bb = ByteBuffer.allocateDirect(256).order(ByteOrder.nativeOrder());
        long genId = NativeChunkBridge.register(dim, cx, cz,
                ChunkStateAuthorityBridge.getBufferAddress(pb),
                ChunkStateAuthorityBridge.getBufferAddress(bb));
        assertTrue(genId > 0, "Registration must succeed");

        // Set up blocks in section 0 (Y=0..15) and section 5 (Y=80..95)
        NativeChunkBridge.setBlockState(dim, cx, cz, 2, 5, 3, 42);
        NativeChunkBridge.setBlockState(dim, cx, cz, 7, 85, 9, 128);

        // Fetch section pointers
        ByteBuffer ptrsBuf = ByteBuffer.allocateDirect(128).order(ByteOrder.nativeOrder());
        long ptrsAddr = ChunkStateAuthorityBridge.getBufferAddress(ptrsBuf);
        int ok = NativeChunkBridge.getSectionPointers(dim, cx, cz, ptrsAddr);
        assertEquals(1, ok, "getSectionPointers must return 1");

        long sec0Ptr = ptrsBuf.getLong(0 * 8);
        long sec5Ptr = ptrsBuf.getLong(5 * 8);
        long sec1Ptr = ptrsBuf.getLong(1 * 8);

        assertTrue(sec0Ptr != 0, "Section 0 pointer must be non-zero");
        assertTrue(sec5Ptr != 0, "Section 5 pointer must be non-zero");
        assertEquals(0, sec1Ptr, "Section 1 pointer must be 0 (absent section)");

        // Read directly from native memory via StateRegistryLookup (Unsafe)
        int val0 = StateRegistryLookup.readStateId(sec0Ptr, 2, 5, 3);
        assertEquals(42, val0, "Direct memory read at (2, 5, 3) must match written state 42");

        int val5 = StateRegistryLookup.readStateId(sec5Ptr, 7, 85, 9);
        assertEquals(128, val5, "Direct memory read at (7, 85, 9) must match written state 128");

        // Single section pointer query
        long directSec5 = NativeChunkBridge.getSectionPointer(dim, cx, cz, 5);
        assertEquals(sec5Ptr, directSec5, "getSectionPointer must match bulk query pointer");

        System.out.println("    [PASS] Direct memory Zero-JNI read verified.");
    }

    private static void testReadAfterWriteCoherence() {
        System.out.println("--> [4/7] Testing Read-After-Write Coherence Across Sections...");

        int dim = 0;
        int cx = 5;
        int cz = 6;
        ByteBuffer pb = ByteBuffer.allocateDirect(65536 * 2).order(ByteOrder.nativeOrder());
        ByteBuffer bb = ByteBuffer.allocateDirect(256).order(ByteOrder.nativeOrder());
        NativeChunkBridge.register(dim, cx, cz,
                ChunkStateAuthorityBridge.getBufferAddress(pb),
                ChunkStateAuthorityBridge.getBufferAddress(bb));

        // Write 100 blocks across sections 0..15
        int[] expected = new int[100];
        int[][] coords = new int[100][3];
        Random rnd = new Random(12345);

        for (int i = 0; i < 100; i++) {
            coords[i][0] = rnd.nextInt(16);
            coords[i][1] = rnd.nextInt(256);
            coords[i][2] = rnd.nextInt(16);
            expected[i] = 1 + rnd.nextInt(1000); // non-zero state

            long res = NativeChunkBridge.setBlockState(dim, cx, cz, coords[i][0], coords[i][1], coords[i][2], expected[i]);
            int status = (byte) (res & 0xFF);
            assertTrue(status == 0 || status == 1, "Write must succeed");

            // Immediate read-after-write
            int read = NativeChunkBridge.getBlockState(dim, cx, cz, coords[i][0], coords[i][1], coords[i][2]);
            assertEquals(expected[i], read, "Read-after-write must be immediately visible at step " + i);
        }

        System.out.println("    [PASS] Read-after-write coherence confirmed across 100 random positions.");
    }

    private static void testMutationToPacketEndToEnd() {
        System.out.println("--> [5/7] Testing Mutation -> Packet End-to-End Proof (No Reseed)...");

        int dim = 0;
        int cx = 7;
        int cz = 8;
        ByteBuffer pb = ByteBuffer.allocateDirect(65536 * 2).order(ByteOrder.nativeOrder());
        ByteBuffer bb = ByteBuffer.allocateDirect(256).order(ByteOrder.nativeOrder());
        long genId = NativeChunkBridge.register(dim, cx, cz,
                ChunkStateAuthorityBridge.getBufferAddress(pb),
                ChunkStateAuthorityBridge.getBufferAddress(bb));

        // 1. Initial write
        NativeChunkBridge.setBlockState(dim, cx, cz, 0, 16, 0, 10); // Section 1

        ByteBuffer out1 = ByteBuffer.allocateDirect(131072).order(ByteOrder.nativeOrder());
        long out1Addr = ChunkStateAuthorityBridge.getBufferAddress(out1);
        int bytes1 = NativeChunkBridge.encodePacket(dim, cx, cz, genId, true, true, out1Addr, 131072);
        assertTrue(bytes1 > 0, "Packet encode 1 must succeed");

        byte[] payload1 = new byte[bytes1];
        out1.get(payload1);

        // 2. Authoritative mutation: change state 10 to 999
        long mut = NativeChunkBridge.setBlockState(dim, cx, cz, 0, 16, 0, 999);
        assertEquals(0, (byte)(mut & 0xFF), "Mutation must succeed");

        // 3. Re-encode packet from SAME retained native chunk without reseed!
        ByteBuffer out2 = ByteBuffer.allocateDirect(131072).order(ByteOrder.nativeOrder());
        long out2Addr = ChunkStateAuthorityBridge.getBufferAddress(out2);
        int bytes2 = NativeChunkBridge.encodePacket(dim, cx, cz, genId, true, true, out2Addr, 131072);
        assertTrue(bytes2 > 0, "Packet encode 2 must succeed");

        byte[] payload2 = new byte[bytes2];
        out2.get(payload2);

        // 4. Payloads must differ because native state changed, yet generation handle is the same!
        assertFalse(Arrays.equals(payload1, payload2),
                "Packet payload 2 must reflect mutation without reseed or full section refresh");

        System.out.println("    [PASS] Mutation -> packet end-to-end proof successful ("
                + bytes1 + " bytes -> " + bytes2 + " bytes).");
    }

    private static void testDifferentialFuzzing(int numOperations, long seed) {
        System.out.println("--> [6/7] Running Differential Fuzzing Oracle (" + numOperations + " operations)...");

        // Reference model: 16 sections x 4096 ints
        int[][] reference = new int[16][4096];
        int dim = 0;
        int cx = 11;
        int cz = 12;

        ByteBuffer pb = ByteBuffer.allocateDirect(65536 * 2).order(ByteOrder.nativeOrder());
        ByteBuffer bb = ByteBuffer.allocateDirect(256).order(ByteOrder.nativeOrder());
        NativeChunkBridge.register(dim, cx, cz,
                ChunkStateAuthorityBridge.getBufferAddress(pb),
                ChunkStateAuthorityBridge.getBufferAddress(bb));

        Random rnd = new Random(seed);
        int mismatches = 0;
        int writeCount = 0;
        int readCount = 0;

        for (int op = 0; op < numOperations; op++) {
            boolean isWrite = rnd.nextBoolean();
            int x = rnd.nextInt(16);
            int y = rnd.nextInt(256);
            int z = rnd.nextInt(16);
            int secY = y >> 4;
            int idx = ((y & 15) << 8) | ((z & 15) << 4) | x;

            if (isWrite) {
                writeCount++;
                // 20% air, 30% low id, 30% mid id, 20% high id (up to 65535)
                int state;
                double r = rnd.nextDouble();
                if (r < 0.20) {
                    state = 0;
                } else if (r < 0.50) {
                    state = 1 + rnd.nextInt(20);
                } else if (r < 0.80) {
                    state = 100 + rnd.nextInt(500);
                } else {
                    state = 1000 + rnd.nextInt(64535);
                }

                int oldRef = reference[secY][idx];
                reference[secY][idx] = state;

                long res = NativeChunkBridge.setBlockState(dim, cx, cz, x, y, z, state);
                int status = (byte) (res & 0xFF);

                if (oldRef == state) {
                    assertEquals(1, status, "Expected NO_OP for same state at op " + op);
                } else {
                    assertEquals(0, status, "Expected SUCCESS for state change at op " + op);
                    int oldRet = (int) ((res >>> 16) & 0xFFFFL);
                    int newRet = (int) ((res >>> 32) & 0xFFFFL);
                    if (oldRet != oldRef || newRet != state) {
                        mismatches++;
                        System.err.println("MISMATCH at op " + op + ": oldRef=" + oldRef + " oldRet=" + oldRet
                                + " newRef=" + state + " newRet=" + newRet);
                        break;
                    }
                }
            } else {
                readCount++;
                int expected = reference[secY][idx];
                int actual = NativeChunkBridge.getBlockState(dim, cx, cz, x, y, z);
                if (expected != actual) {
                    mismatches++;
                    System.err.println("MISMATCH on read at op " + op + ": expected=" + expected + " actual=" + actual);
                    break;
                }
            }
        }

        assertEquals(0, mismatches, "Differential fuzzing must have 0 mismatches");
        System.out.println("    [PASS] Differential Fuzzing: " + numOperations + " ops ("
                + writeCount + " writes, " + readCount + " reads) with 0 MISMATCHES!");
    }

    private static void runPerformanceBenchmarks() {
        System.out.println("--> [7/7] Running Performance Profiling (p50 / p95 / p99)...");

        int dim = 0;
        int cx = 20;
        int cz = 21;
        ByteBuffer pb = ByteBuffer.allocateDirect(65536 * 2).order(ByteOrder.nativeOrder());
        ByteBuffer bb = ByteBuffer.allocateDirect(256).order(ByteOrder.nativeOrder());
        NativeChunkBridge.register(dim, cx, cz,
                ChunkStateAuthorityBridge.getBufferAddress(pb),
                ChunkStateAuthorityBridge.getBufferAddress(bb));

        // Populate chunk
        for (int y = 0; y < 64; y++) {
            NativeChunkBridge.setBlockState(dim, cx, cz, 5, y, 5, y + 1);
        }

        // Get section pointer for direct memory read
        long sec0Ptr = NativeChunkBridge.getSectionPointer(dim, cx, cz, 0);

        // --- Benchmark 1: Direct Memory (Zero-JNI) Reads ---
        int readSamples = 200000;
        long[] readLatencies = new long[readSamples];

        // Warmup
        for (int i = 0; i < 20000; i++) {
            StateRegistryLookup.readStateId(sec0Ptr, i & 15, (i >> 4) & 15, (i >> 8) & 15);
        }

        long t0 = System.nanoTime();
        for (int i = 0; i < readSamples; i++) {
            long start = System.nanoTime();
            int s = StateRegistryLookup.readStateId(sec0Ptr, i & 15, (i >> 4) & 15, (i >> 8) & 15);
            long end = System.nanoTime();
            readLatencies[i] = end - start;
        }
        long t1 = System.nanoTime();

        Arrays.sort(readLatencies);
        long rP50 = readLatencies[(int) (readSamples * 0.50)];
        long rP95 = readLatencies[(int) (readSamples * 0.95)];
        long rP99 = readLatencies[(int) (readSamples * 0.99)];
        double readOpsSec = (readSamples / ((t1 - t0) / 1e9));

        System.out.println("    [DIRECT READS] Throughput: " + String.format("%,.0f", readOpsSec) + " ops/sec"
                + " | p50: " + rP50 + " ns | p95: " + rP95 + " ns | p99: " + rP99 + " ns");

        // --- Benchmark 2: Authoritative setBlockState Writes ---
        int writeSamples = 50000;
        long[] writeLatencies = new long[writeSamples];

        // Warmup
        for (int i = 0; i < 5000; i++) {
            NativeChunkBridge.setBlockState(dim, cx, cz, i & 15, (i >> 4) & 15, (i >> 8) & 15, (i & 0xFF) + 1);
        }

        t0 = System.nanoTime();
        for (int i = 0; i < writeSamples; i++) {
            long start = System.nanoTime();
            long res = NativeChunkBridge.setBlockState(dim, cx, cz, i & 15, (i >> 4) & 15, (i >> 8) & 15, ((i + 1) & 0xFF) + 1);
            long end = System.nanoTime();
            writeLatencies[i] = end - start;
        }
        t1 = System.nanoTime();

        Arrays.sort(writeLatencies);
        long wP50 = writeLatencies[(int) (writeSamples * 0.50)];
        long wP95 = writeLatencies[(int) (writeSamples * 0.95)];
        long wP99 = writeLatencies[(int) (writeSamples * 0.99)];
        double writeOpsSec = (writeSamples / ((t1 - t0) / 1e9));

        System.out.println("    [AUTHORITATIVE WRITES] Throughput: " + String.format("%,.0f", writeOpsSec) + " ops/sec"
                + " | p50: " + wP50 + " ns | p95: " + wP95 + " ns | p99: " + wP99 + " ns");
    }
}
