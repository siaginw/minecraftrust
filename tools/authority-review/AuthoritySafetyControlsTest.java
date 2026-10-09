package com.rustcraft.authority;

import com.rustcraft.bridge.capture.PacketAuthorityExperiment;

import java.io.File;
import java.io.FileWriter;
import java.nio.file.Files;

public class AuthoritySafetyControlsTest {

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

    public static void main(String[] args) throws Exception {
        System.out.println("=== Running RustCraft Authority Safety Controls Test Suite ===");

        // Test 1: Default Authority OFF
        testDefaultOff();

        // Test 2: Closure Receipt Verification (Valid & Invalid Cases)
        testReceiptVerification();

        // Test 3: Cap Enforcement
        testCapEnforcement();

        // Test 4: Scope Lockdown (Filter, Dimension, TE, High-State)
        testScopeGates();

        // Test 5: Session Admission Check
        testSessionAdmissionGate();

        // Test 6: Production Authority Invariant
        testProductionAuthorityInvariant();

        // Test 7: Receipt Emission
        testReceiptEmission();

        System.out.println("ALL AUTHORITY SAFETY CONTROL TESTS PASSED SUCCESSFULLY!");
    }

    private static void testDefaultOff() {
        System.out.println("--> Test 1: Default Authority OFF");
        PacketAuthorityExperiment.resetForTesting(false, 64);
        assertFalse(PacketAuthorityExperiment.enabled(), "Default authority must be false");
        boolean handled = PacketAuthorityExperiment.tryAuthority(null, null, null, 0xFFFF);
        assertFalse(handled, "When disabled, tryAuthority must return false immediately");
        assertEquals(0, PacketAuthorityExperiment.RUST_SELECTED.get(), "Rust selected must be 0");
    }

    private static void testReceiptVerification() throws Exception {
        System.out.println("--> Test 2: Closure Receipt Requirement");
        PacketAuthorityExperiment.resetForTesting(true, 64);

        // Case A: Missing receipt
        File tempReceipt = File.createTempFile("closure-test-", ".json");
        tempReceipt.delete();
        System.setProperty(PacketAuthorityExperiment.PROPERTY_RECEIPT, tempReceipt.getAbsolutePath());
        assertFalse(PacketAuthorityExperiment.verifyClosureReceipt(), "Missing receipt must fail verification");

        // Case B: Tampered receipt (closure_verdict is NOT CLOSED)
        PacketAuthorityExperiment.resetForTesting(true, 64);
        try (FileWriter fw = new FileWriter(tempReceipt)) {
            fw.write("{\n" +
                    "  \"closure_verdict\": \"FAILED\",\n" +
                    "  \"closure_state\": \"LIVE_SHADOW_CLOSED\",\n" +
                    "  \"production_authority\": false\n" +
                    "}");
        }
        assertFalse(PacketAuthorityExperiment.verifyClosureReceipt(), "Non-CLOSED verdict must fail verification");

        // Case C: Tampered receipt (production_authority is TRUE)
        PacketAuthorityExperiment.resetForTesting(true, 64);
        try (FileWriter fw = new FileWriter(tempReceipt)) {
            fw.write("{\n" +
                    "  \"closure_verdict\": \"CLOSED\",\n" +
                    "  \"closure_state\": \"LIVE_SHADOW_CLOSED\",\n" +
                    "  \"production_authority\": true\n" +
                    "}");
        }
        assertFalse(PacketAuthorityExperiment.verifyClosureReceipt(), "production_authority=true must fail verification");

        // Case D: Valid receipt
        PacketAuthorityExperiment.resetForTesting(true, 64);
        try (FileWriter fw = new FileWriter(tempReceipt)) {
            fw.write("{\n" +
                    "  \"closure_verdict\": \"CLOSED\",\n" +
                    "  \"closure_state\": \"LIVE_SHADOW_CLOSED\",\n" +
                    "  \"production_authority\": false\n" +
                    "}");
        }
        assertTrue(PacketAuthorityExperiment.verifyClosureReceipt(), "Valid receipt must pass verification");
        tempReceipt.delete();
    }

    private static void testCapEnforcement() {
        System.out.println("--> Test 3: Cap Enforcement");
        PacketAuthorityExperiment.resetForTesting(true, 3);
        PacketAuthorityExperiment.setReceiptVerifiedForTesting(true);

        assertEquals(3, PacketAuthorityExperiment.cap(), "Cap must be 3");

        // Simulate reaching cap
        PacketAuthorityExperiment.RUST_SELECTED.set(3);
        boolean handled = PacketAuthorityExperiment.tryAuthority(null, null, null, 0xFFFF);
        assertFalse(handled, "Exhausted cap must return false");
        assertEquals(1, PacketAuthorityExperiment.CAP_EXHAUSTED.get(), "Cap exhausted counter must be 1");
        assertEquals(1, PacketAuthorityExperiment.JAVA_SELECTED.get(), "Java fallback must be selected");
    }

    private static void testScopeGates() {
        System.out.println("--> Test 4: Scope Lockdown (Filter)");
        PacketAuthorityExperiment.resetForTesting(true, 64);
        PacketAuthorityExperiment.setReceiptVerifiedForTesting(true);

        // Filter not 0xFFFF
        boolean handled = PacketAuthorityExperiment.tryAuthority(null, null, null, 0x00FF);
        assertFalse(handled, "Partial filter must be rejected");
        assertEquals(1, PacketAuthorityExperiment.EXCLUDED_FILTER.get(), "Filter excluded count must be 1");
        assertEquals(1, PacketAuthorityExperiment.JAVA_SELECTED.get(), "Java selected must be 1");
    }

    private static void testSessionAdmissionGate() {
        System.out.println("--> Test 5: Session Admission Gate");
        PacketAuthorityExperiment.resetForTesting(true, 64);
        PacketAuthorityExperiment.setReceiptVerifiedForTesting(true);

        // Without active session and token, tryAuthority must reject safely to Java
        boolean handled = PacketAuthorityExperiment.tryAuthority(null, null, null, 0xFFFF);
        assertFalse(handled, "Unadmitted session must fail closed to Java");
        assertTrue(PacketAuthorityExperiment.JAVA_SELECTED.get() > 0, "Java must be selected");
    }

    private static void testProductionAuthorityInvariant() {
        System.out.println("--> Test 6: Production Authority Invariant");
        assertFalse(PacketAuthorityExperiment.PRODUCTION_AUTHORITY, "PRODUCTION_AUTHORITY must be strictly false");
    }

    private static void testReceiptEmission() throws Exception {
        System.out.println("--> Test 7: Receipt Emission");
        PacketAuthorityExperiment.resetForTesting(true, 64);
        PacketAuthorityExperiment.setReceiptVerifiedForTesting(true);
        PacketAuthorityExperiment.RUST_SELECTED.set(5);
        PacketAuthorityExperiment.JAVA_SELECTED.set(10);
        PacketAuthorityExperiment.CAP_EXHAUSTED.set(2);
        PacketAuthorityExperiment.EXCLUDED_TE.set(3);

        File receiptFile = File.createTempFile("auth-exp-receipt-", ".json");
        PacketAuthorityExperiment.writeReceipt(receiptFile.toPath());
        assertTrue(receiptFile.exists(), "Receipt file must be created");

        String json = new String(Files.readAllBytes(receiptFile.toPath()));
        assertTrue(json.contains("\"production_authority\":false") || json.contains("\"production_authority\": false"), "Receipt must contain production_authority: false");
        assertTrue(json.contains("\"rust_selected\":5") || json.contains("\"rust_selected\": 5"), "Receipt must contain rust_selected: 5");
        assertTrue(json.contains("\"java_selected\":10") || json.contains("\"java_selected\": 10"), "Receipt must contain java_selected: 10");
        assertTrue(json.contains("\"cap_exhausted\":2") || json.contains("\"cap_exhausted\": 2"), "Receipt must contain cap_exhausted: 2");
        assertTrue(json.contains("\"excluded_te\":3") || json.contains("\"excluded_te\": 3"), "Receipt must contain excluded_te: 3");
        receiptFile.delete();
    }
}
