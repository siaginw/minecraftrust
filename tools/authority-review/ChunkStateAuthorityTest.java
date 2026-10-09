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

        // 7. Light State Authority & Direct-Memory Nibble Verification
        testLightStateAuthority();

        // 8. Light State Differential Fuzzing (10,000 light operations)
        testLightDifferentialFuzzing(10000, 1337L);

        // 9. Biome & Heightmap Authority & Direct Pointers
        testBiomeAndHeightAuthority();

        // 10. Biome Differential Fuzzing (100,000 operations)
        testBiomeDifferentialFuzzing(100000, 9999L);

        // 11. Heightmap Differential Fuzzing (100,000 height-affecting block operations)
        testHeightDifferentialFuzzing(100000, 7777L);

        // 12. Performance Benchmarks
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

    private static void testLightStateAuthority() {
        System.out.println("--> [7/9] Testing Light State Authority & Direct-Memory Nibble Architecture...");

        int dim = 0;
        int cx = 14;
        int cz = 15;
        ByteBuffer pb = ByteBuffer.allocateDirect(65536 * 2).order(ByteOrder.nativeOrder());
        ByteBuffer bb = ByteBuffer.allocateDirect(256).order(ByteOrder.nativeOrder());
        long genId = NativeChunkBridge.register(dim, cx, cz,
                ChunkStateAuthorityBridge.getBufferAddress(pb),
                ChunkStateAuthorityBridge.getBufferAddress(bb));
        assertTrue(genId > 0, "Registration must succeed");

        // Populate a block in section 2 to allocate section 2
        NativeChunkBridge.setBlockState(dim, cx, cz, 4, 35, 7, 1);

        ByteBuffer blBuf = ByteBuffer.allocateDirect(128).order(ByteOrder.nativeOrder());
        ByteBuffer slBuf = ByteBuffer.allocateDirect(128).order(ByteOrder.nativeOrder());
        long blAddr = ChunkStateAuthorityBridge.getBufferAddress(blBuf);
        long slAddr = ChunkStateAuthorityBridge.getBufferAddress(slBuf);

        int ok = NativeChunkBridge.getSectionLightPointers(dim, cx, cz, blAddr, slAddr);
        assertEquals(1, ok, "getSectionLightPointers must return 1");

        long sec2BlPtr = blBuf.getLong(2 * 8);
        long sec2SlPtr = slBuf.getLong(2 * 8);
        long sec0BlPtr = blBuf.getLong(0 * 8);

        assertTrue(sec2BlPtr != 0, "Allocated section 2 block light pointer must be non-zero");
        assertTrue(sec2SlPtr != 0, "Allocated section 2 sky light pointer must be non-zero");
        assertEquals(0, sec0BlPtr, "Absent section 0 block light pointer must be 0");

        // 1. Initial State Parity: Block light = 0 everywhere, Sky light = 15 everywhere
        int initBl = StateRegistryLookup.readLightNibble(sec2BlPtr, 4, 3, 7);
        assertEquals(0, initBl, "Default block light in newly allocated section must be 0");

        int initSl = StateRegistryLookup.readLightNibble(sec2SlPtr, 4, 3, 7);
        assertEquals(15, initSl, "Default sky light in newly allocated section must be 15");

        // 2. Direct-Memory Atomic Writes
        boolean changedBl = StateRegistryLookup.writeLightNibble(sec2BlPtr, 4, 3, 7, 14);
        assertTrue(changedBl, "Writing new block light value 14 must return true");

        int readBl = StateRegistryLookup.readLightNibble(sec2BlPtr, 4, 3, 7);
        assertEquals(14, readBl, "Direct-memory read must match written block light 14");

        // 3. No-Op Write
        boolean noOpBl = StateRegistryLookup.writeLightNibble(sec2BlPtr, 4, 3, 7, 14);
        assertFalse(noOpBl, "Writing same block light value 14 must return false (no-op)");

        // 4. Non-Tearing Adjacent Nibble Verification (same byte, adjacent odd/even index)
        // (4, 3, 7) -> index = (3 << 8) | (7 << 4) | 4 = 768 + 112 + 4 = 884 (even index)
        // Adjacent odd index is 885 -> (5, 3, 7)
        int adjBefore = StateRegistryLookup.readLightNibble(sec2BlPtr, 5, 3, 7);
        assertEquals(0, adjBefore, "Adjacent nibble must initially be 0");

        boolean changedAdj = StateRegistryLookup.writeLightNibble(sec2BlPtr, 5, 3, 7, 9);
        assertTrue(changedAdj, "Writing adjacent odd nibble must return true");

        assertEquals(14, StateRegistryLookup.readLightNibble(sec2BlPtr, 4, 3, 7),
                "Even nibble must remain 14 after odd nibble write (no tearing)");
        assertEquals(9, StateRegistryLookup.readLightNibble(sec2BlPtr, 5, 3, 7),
                "Odd nibble must be 9");

        // 5. Sky Light Atomic Mutation
        boolean changedSl = StateRegistryLookup.writeLightNibble(sec2SlPtr, 4, 3, 7, 7);
        assertTrue(changedSl, "Writing sky light value 7 must return true");
        assertEquals(7, StateRegistryLookup.readLightNibble(sec2SlPtr, 4, 3, 7),
                "Sky light must read 7");
        assertEquals(15, StateRegistryLookup.readLightNibble(sec2SlPtr, 5, 3, 7),
                "Adjacent sky light nibble must remain default 15");

        System.out.println("    [PASS] Light state authority & direct-memory nibble architecture verified.");
    }

    private static void testLightDifferentialFuzzing(int count, long seed) {
        System.out.println("--> [8/9] Running Light State Differential Fuzzing (" + count + " operations)...");

        int dim = 0;
        int cx = 33;
        int cz = 44;
        ByteBuffer pb = ByteBuffer.allocateDirect(65536 * 2).order(ByteOrder.nativeOrder());
        ByteBuffer bb = ByteBuffer.allocateDirect(256).order(ByteOrder.nativeOrder());
        NativeChunkBridge.register(dim, cx, cz,
                ChunkStateAuthorityBridge.getBufferAddress(pb),
                ChunkStateAuthorityBridge.getBufferAddress(bb));

        // Allocate section 1 (Y=16..31)
        NativeChunkBridge.setBlockState(dim, cx, cz, 0, 16, 0, 1);

        ByteBuffer blBuf = ByteBuffer.allocateDirect(128).order(ByteOrder.nativeOrder());
        ByteBuffer slBuf = ByteBuffer.allocateDirect(128).order(ByteOrder.nativeOrder());
        NativeChunkBridge.getSectionLightPointers(dim, cx, cz,
                ChunkStateAuthorityBridge.getBufferAddress(blBuf),
                ChunkStateAuthorityBridge.getBufferAddress(slBuf));
        long blPtr = blBuf.getLong(1 * 8);
        long slPtr = slBuf.getLong(1 * 8);

        assertTrue(blPtr != 0, "Section 1 block light pointer must exist");
        assertTrue(slPtr != 0, "Section 1 sky light pointer must exist");

        // Reference Oracle: byte arrays simulating Minecraft NibbleArray
        byte[] refBlockLight = new byte[2048];
        byte[] refSkyLight = new byte[2048];
        Arrays.fill(refSkyLight, (byte) 0xFF); // Default 15

        Random rng = new Random(seed);
        int writes = 0;
        int reads = 0;

        for (int op = 0; op < count; op++) {
            int x = rng.nextInt(16);
            int y = rng.nextInt(16);
            int z = rng.nextInt(16);
            boolean isSky = rng.nextBoolean();
            long targetPtr = isSky ? slPtr : blPtr;
            byte[] refArray = isSky ? refSkyLight : refBlockLight;

            int idx = StateRegistryLookup.getSectionIndex(x, y, z);
            int byteOffset = idx >> 1;
            boolean isOdd = (idx & 1) != 0;

            if (rng.nextBoolean()) {
                // WRITE
                int val = rng.nextInt(16);
                boolean nativeChanged = StateRegistryLookup.writeLightNibble(targetPtr, x, y, z, val);

                // Update oracle
                int curByte = refArray[byteOffset] & 0xFF;
                int curNibble = isOdd ? (curByte >> 4) & 0x0F : curByte & 0x0F;
                boolean oracleChanged = curNibble != val;
                assertEquals(oracleChanged ? 1 : 0, nativeChanged ? 1 : 0,
                        "Change detection mismatch at (" + x + "," + y + "," + z + ")");

                int newByte = isOdd ? ((curByte & 0x0F) | (val << 4)) : ((curByte & 0xF0) | val);
                refArray[byteOffset] = (byte) newByte;
                writes++;
            } else {
                // READ
                int nativeVal = StateRegistryLookup.readLightNibble(targetPtr, x, y, z);
                int curByte = refArray[byteOffset] & 0xFF;
                int oracleVal = isOdd ? (curByte >> 4) & 0x0F : curByte & 0x0F;
                assertEquals(oracleVal, nativeVal,
                        "Light read mismatch at (" + x + "," + y + "," + z + "), isSky=" + isSky);
                reads++;
            }
        }

        // Full array parity check (all 4096 cells)
        for (int idx = 0; idx < 4096; idx++) {
            int x = idx & 15;
            int z = (idx >> 4) & 15;
            int y = (idx >> 8) & 15;
            int blNative = StateRegistryLookup.readLightNibble(blPtr, x, y, z);
            int byteOffset = idx >> 1;
            int blByte = refBlockLight[byteOffset] & 0xFF;
            int blOracle = (idx & 1) != 0 ? (blByte >> 4) & 0x0F : blByte & 0x0F;
            assertEquals(blOracle, blNative, "Post-fuzz block light mismatch at index " + idx);

            int slNative = StateRegistryLookup.readLightNibble(slPtr, x, y, z);
            int slByte = refSkyLight[byteOffset] & 0xFF;
            int slOracle = (idx & 1) != 0 ? (slByte >> 4) & 0x0F : slByte & 0x0F;
            assertEquals(slOracle, slNative, "Post-fuzz sky light mismatch at index " + idx);
        }

        System.out.println("    [PASS] Light Differential Fuzzing: " + count + " ops (" + writes + " writes, "
                + reads + " reads) with 0 MISMATCHES across all 4,096 cells!");
    }

    private static void testBiomeAndHeightAuthority() {
        System.out.println("--> [9/12] Testing Biome & Heightmap Authority & Direct Pointers...");

        int dim = 0;
        int cx = 77;
        int cz = 88;
        ByteBuffer pb = ByteBuffer.allocateDirect(65536 * 2).order(ByteOrder.nativeOrder());
        ByteBuffer bb = ByteBuffer.allocateDirect(256).order(ByteOrder.nativeOrder());
        long genId = NativeChunkBridge.register(dim, cx, cz,
                ChunkStateAuthorityBridge.getBufferAddress(pb),
                ChunkStateAuthorityBridge.getBufferAddress(bb));
        ChunkStateAuthorityBridge.ChunkAuthorityRecord rec =
                ChunkStateAuthorityBridge.registerChunkAuthority(dim, cx, cz, genId);

        assertTrue(rec.biomesPointer != 0, "biomesPointer must be non-null");
        assertTrue(rec.heightmapPointer != 0, "heightmapPointer must be non-null");

        // 1. Biome read/write via Unsafe direct memory
        int b1 = StateRegistryLookup.getUnsafe().getByte(rec.biomesPointer + ((7 << 4) | 5)) & 0xFF;
        assertEquals(0, b1, "Initial biome at (5,7) must be 0");

        // Set biome
        int okBio = NativeChunkBridge.setBiome(dim, cx, cz, 5, 7, 42);
        assertEquals(1, okBio, "setBiome must return 1 (modified)");
        int b2 = StateRegistryLookup.getUnsafe().getByte(rec.biomesPointer + ((7 << 4) | 5)) & 0xFF;
        assertEquals(42, b2, "Direct memory biome at (5,7) must read 42");

        // 2. Heightmap initial value
        int h1 = StateRegistryLookup.getUnsafe().getShort(rec.heightmapPointer + (((4 << 4) | 4) << 1)) & 0xFFFF;
        assertEquals(0, h1, "Initial height at (4,4) must be 0");

        // Place block at Y=20 -> height becomes 21
        NativeChunkBridge.setBlockState(dim, cx, cz, 4, 20, 4, 1);
        int h2 = StateRegistryLookup.getUnsafe().getShort(rec.heightmapPointer + (((4 << 4) | 4) << 1)) & 0xFFFF;
        assertEquals(21, h2, "Height after placing block at Y=20 must be 21");

        // Place block at Y=50 -> height becomes 51
        NativeChunkBridge.setBlockState(dim, cx, cz, 4, 50, 4, 2);
        int h3 = StateRegistryLookup.getUnsafe().getShort(rec.heightmapPointer + (((4 << 4) | 4) << 1)) & 0xFFFF;
        assertEquals(51, h3, "Height after placing block at Y=50 must be 51");

        // Remove top block at Y=50 -> downward scan finds Y=20 -> height becomes 21
        NativeChunkBridge.setBlockState(dim, cx, cz, 4, 50, 4, 0);
        int h4 = StateRegistryLookup.getUnsafe().getShort(rec.heightmapPointer + (((4 << 4) | 4) << 1)) & 0xFFFF;
        assertEquals(21, h4, "Height after removing top block must scan down to 21");

        System.out.println("    [PASS] Biome & Heightmap Authority & Direct Pointers verified.");
    }

    private static void testBiomeDifferentialFuzzing(int count, long seed) {
        System.out.println("--> [10/12] Running Biome Differential Fuzzing (" + count + " operations)...");

        int dim = 0;
        int cx = 78;
        int cz = 89;
        ByteBuffer pb = ByteBuffer.allocateDirect(65536 * 2).order(ByteOrder.nativeOrder());
        ByteBuffer bb = ByteBuffer.allocateDirect(256).order(ByteOrder.nativeOrder());
        long genId = NativeChunkBridge.register(dim, cx, cz,
                ChunkStateAuthorityBridge.getBufferAddress(pb),
                ChunkStateAuthorityBridge.getBufferAddress(bb));
        ChunkStateAuthorityBridge.ChunkAuthorityRecord rec =
                ChunkStateAuthorityBridge.registerChunkAuthority(dim, cx, cz, genId);

        byte[] refBiomes = new byte[256];
        Random rng = new Random(seed);
        int writes = 0;
        int reads = 0;

        for (int op = 0; op < count; op++) {
            int x = rng.nextInt(16);
            int z = rng.nextInt(16);
            int idx = (z << 4) | x;

            if (rng.nextBoolean()) {
                // WRITE
                int val = rng.nextInt(256);
                int changed = NativeChunkBridge.setBiome(dim, cx, cz, x, z, val);
                int refChanged = (refBiomes[idx] & 0xFF) != val ? 1 : 0;
                assertEquals(refChanged, changed, "setBiome return mismatch at (" + x + "," + z + ")");
                refBiomes[idx] = (byte) val;
                writes++;
            } else {
                // READ direct memory
                int nativeVal = StateRegistryLookup.getUnsafe().getByte(rec.biomesPointer + idx) & 0xFF;
                int refVal = refBiomes[idx] & 0xFF;
                assertEquals(refVal, nativeVal, "Biome read mismatch at (" + x + "," + z + ")");
                reads++;
            }
        }

        // Full array check
        for (int i = 0; i < 256; i++) {
            int nativeVal = StateRegistryLookup.getUnsafe().getByte(rec.biomesPointer + i) & 0xFF;
            int refVal = refBiomes[i] & 0xFF;
            assertEquals(refVal, nativeVal, "Post-fuzz biome mismatch at index " + i);
        }

        System.out.println("    [PASS] Biome Differential Fuzzing: " + count + " ops (" + writes + " writes, "
                + reads + " reads) with 0 MISMATCHES across all 256 entries!");
    }

    private static void testHeightDifferentialFuzzing(int count, long seed) {
        System.out.println("--> [11/12] Running Heightmap Differential Fuzzing (" + count + " operations)...");

        int dim = 0;
        int cx = 79;
        int cz = 90;
        ByteBuffer pb = ByteBuffer.allocateDirect(65536 * 2).order(ByteOrder.nativeOrder());
        ByteBuffer bb = ByteBuffer.allocateDirect(256).order(ByteOrder.nativeOrder());
        long genId = NativeChunkBridge.register(dim, cx, cz,
                ChunkStateAuthorityBridge.getBufferAddress(pb),
                ChunkStateAuthorityBridge.getBufferAddress(bb));
        ChunkStateAuthorityBridge.ChunkAuthorityRecord rec =
                ChunkStateAuthorityBridge.registerChunkAuthority(dim, cx, cz, genId);

        // Oracle: 16x16x256 block array and 256 height array
        short[] refBlocks = new short[16 * 16 * 256];
        int[] refHeight = new int[256];

        Random rng = new Random(seed);
        int mutations = 0;

        for (int op = 0; op < count; op++) {
            int x = rng.nextInt(16);
            int z = rng.nextInt(16);
            int colIdx = (z << 4) | x;

            // Pick Y skewed toward current height or random
            int curH = refHeight[colIdx];
            int y;
            int r = rng.nextInt(10);
            if (r < 3 && curH > 0) {
                y = curH - 1; // Hammer top removal
            } else if (r < 5) {
                y = Math.min(255, curH + rng.nextInt(10)); // Placement above
            } else {
                y = rng.nextInt(256); // Arbitrary Y
            }

            // Decide new state: 0 (air) or 1..5
            int newState = rng.nextInt(10) < 4 ? 0 : (rng.nextInt(5) + 1);
            int blockIdx = (colIdx << 8) | y;
            short oldState = refBlocks[blockIdx];

            if (oldState != newState) {
                // Update Oracle block
                refBlocks[blockIdx] = (short) newState;

                // Update Oracle heightmap (exact Minecraft reference logic)
                if (newState != 0) {
                    if (y >= curH) {
                        refHeight[colIdx] = y + 1;
                    }
                } else {
                    if (y + 1 == curH) {
                        // Scan down
                        int newTop = 0;
                        for (int scanY = y - 1; scanY >= 0; scanY--) {
                            if (refBlocks[(colIdx << 8) | scanY] != 0) {
                                newTop = scanY + 1;
                                break;
                            }
                        }
                        refHeight[colIdx] = newTop;
                    }
                }

                // Mutate NativeChunk
                NativeChunkBridge.setBlockState(dim, cx, cz, x, y, z, newState);
                mutations++;
            }

            // Verify height at (x, z)
            int nativeHeight = StateRegistryLookup.getUnsafe().getShort(rec.heightmapPointer + ((long) colIdx << 1)) & 0xFFFF;
            assertEquals(refHeight[colIdx], nativeHeight, "Height mismatch at op " + op + " (" + x + "," + z + ")");
        }

        // Full array check
        for (int col = 0; col < 256; col++) {
            int nativeHeight = StateRegistryLookup.getUnsafe().getShort(rec.heightmapPointer + ((long) col << 1)) & 0xFFFF;
            assertEquals(refHeight[col], nativeHeight, "Post-fuzz height mismatch at col " + col);
        }

        System.out.println("    [PASS] Heightmap Differential Fuzzing: " + count + " ops (" + mutations
                + " height-affecting mutations) with 0 MISMATCHES across all 256 columns!");
    }

    private static void runPerformanceBenchmarks() {
        System.out.println("--> [12/12] Running Performance Profiling (p50 / p95 / p99)...");

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
        int readSamples = 500000;
        int batchSize = 10000;
        int batches = readSamples / batchSize;
        double[] readBatchNs = new double[batches];

        // Warmup
        for (int i = 0; i < 50000; i++) {
            StateRegistryLookup.readStateId(sec0Ptr, i & 15, (i >> 4) & 15, (i >> 8) & 15);
        }

        long t0 = System.nanoTime();
        for (int b = 0; b < batches; b++) {
            long start = System.nanoTime();
            int sum = 0;
            for (int i = 0; i < batchSize; i++) {
                sum += StateRegistryLookup.readStateId(sec0Ptr, i & 15, (i >> 4) & 15, (i >> 8) & 15);
            }
            long end = System.nanoTime();
            readBatchNs[b] = (double) (end - start) / batchSize;
        }
        long t1 = System.nanoTime();

        Arrays.sort(readBatchNs);
        double rP50 = readBatchNs[(int) (batches * 0.50)];
        double rP95 = readBatchNs[(int) (batches * 0.95)];
        double rP99 = readBatchNs[(int) (batches * 0.99)];
        double readOpsSec = (readSamples / ((t1 - t0) / 1e9));
        double avgReadNs = ((t1 - t0) * 1.0) / readSamples;

        System.out.println("    [DIRECT READS] Throughput: " + String.format("%,.0f", readOpsSec) + " ops/sec"
                + " | avg: " + String.format("%.2f", avgReadNs) + " ns/op | p50: " + String.format("%.2f", rP50) + " ns/op"
                + " | p95: " + String.format("%.2f", rP95) + " ns/op | p99: " + String.format("%.2f", rP99) + " ns/op");

        // --- Benchmark 2: Authoritative setBlockState Writes ---
        int writeSamples = 50000;
        int writeBatchSize = 1000;
        int writeBatches = writeSamples / writeBatchSize;
        double[] writeBatchNs = new double[writeBatches];

        // Warmup
        for (int i = 0; i < 5000; i++) {
            NativeChunkBridge.setBlockState(dim, cx, cz, i & 15, (i >> 4) & 15, (i >> 8) & 15, (i & 0xFF) + 1);
        }

        t0 = System.nanoTime();
        for (int b = 0; b < writeBatches; b++) {
            long start = System.nanoTime();
            for (int i = 0; i < writeBatchSize; i++) {
                NativeChunkBridge.setBlockState(dim, cx, cz, i & 15, (i >> 4) & 15, (i >> 8) & 15, ((i + 1) & 0xFF) + 1);
            }
            long end = System.nanoTime();
            writeBatchNs[b] = (double) (end - start) / writeBatchSize;
        }
        t1 = System.nanoTime();

        Arrays.sort(writeBatchNs);
        double wP50 = writeBatchNs[(int) (writeBatches * 0.50)];
        double wP95 = writeBatchNs[(int) (writeBatches * 0.95)];
        double wP99 = writeBatchNs[(int) (writeBatches * 0.99)];
        double writeOpsSec = (writeSamples / ((t1 - t0) / 1e9));
        double avgWriteNs = ((t1 - t0) * 1.0) / writeSamples;

        System.out.println("    [AUTHORITATIVE WRITES] Throughput: " + String.format("%,.0f", writeOpsSec) + " ops/sec"
                + " | avg: " + String.format("%.2f", avgWriteNs) + " ns/op | p50: " + String.format("%.2f", wP50) + " ns/op"
                + " | p95: " + String.format("%.2f", wP95) + " ns/op | p99: " + String.format("%.2f", wP99) + " ns/op");
    }
}
