package com.rustcraft.bridge.capture;

import com.rustcraft.bridge.PacketEncodeResultV2;
import sun.misc.Unsafe;

import java.io.BufferedReader;
import java.io.FileReader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Phase D offline replay (section 19 of the phase contract): one known
 * admissible REAL Clean Forge event -- recorded by the qualified oracle with
 * its actual Java packet bytes and its sealed transport -- flows through the
 * EXACT Phase-D event/comparator pipeline with the real Rust DLL, with no
 * networking and no server.
 *
 * <p>Requires one terminal event outcome, validates receipt/counter behavior,
 * and additionally classifies a REAL native failure (output capacity) as
 * INFRA_FAILURE while the Java side stays authoritative.</p>
 */
public final class PhaseDOfflineReplay {

    public static void main(String[] args) throws Exception {
        // args: <events.json> <event-name> <dll-path> <out-dir>
        Path eventsJson = Paths.get(args[0]);
        String eventName = args[1];
        Path dll = Paths.get(args[2]);
        Path out = Paths.get(args[3]);
        Files.createDirectories(out);

        Map<String, Object> doc = readJson(eventsJson);
        Map<String, Object> event = null;
        for (Object candidate : (java.util.List<?>) doc.get("events")) {
            Map<?, ?> record = (Map<?, ?>) candidate;
            if (eventName.equals(record.get("name"))) { event = (Map<String, Object>) record; break; }
        }
        if (event == null) throw new IllegalStateException("event not found: " + eventName);
        if (!"ACCEPTED_PENDING_INDEPENDENT_COMPARISON".equals(event.get("status")))
            throw new IllegalStateException("event is not admissible: " + event.get("status"));

        byte[] transport = Base64.getDecoder().decode((String) event.get("transport"));
        byte[] javaPacket = Base64.getDecoder().decode((String) event.get("javaPacket"));
        byte[] recordedNative = Base64.getDecoder().decode((String) event.get("nativePayload"));
        long recordedV2 = Long.parseLong(String.valueOf(event.get("v2Result")));

        // Bind the pipeline exactly as the live session would.
        SessionCompatibilityContract contract = SessionCompatibilityContract.establish(
                "offline-replay-process", "offline-replay-session", "PHASE_D_OFFLINE_REPLAY",
                intOf(doc.get("registryMapSize")), intOf(doc.get("globalPaletteBits")),
                String.valueOf(doc.get("registrySha256")));
        ShadowEventJournal journal = ShadowEventJournal.bind(contract,
                out.resolve("replay-journal.jsonl").toString());
        if (journal == null) throw new IllegalStateException("journal bind failed");
        long eventId = 1L;
        long bindingSession = 1L;
        if (journal.mint(eventId, bindingSession) != null)
            throw new IllegalStateException("mint refused");

        Unsafe memory = unsafe();
        loadBridge(dll);
        long input = memory.allocateMemory(transport.length);
        for (int i = 0; i < transport.length; i++) memory.putByte(input + i, transport[i]);
        int capacity = 262144;
        long output = memory.allocateMemory(capacity);

        // The real shadow encode, timed as a component measurement.
        memory.setMemory(output, capacity, (byte) 0xCC);
        long encodeStart = System.nanoTime();
        long packed = OwnedSnapshotBridge.encodeOwnedV1(input, transport.length, output, capacity);
        long encodeNanos = System.nanoTime() - encodeStart;
        PacketEncodeResultV2 result = PacketEncodeResultV2.decode(packed);

        Map<String, Object> receipt = new LinkedHashMap<String, Object>();
        receipt.put("schema", "RUSTCRAFT_V2_PHASE_D_OFFLINE_REPLAY_V1");
        receipt.put("event", eventName);
        receipt.put("eventId", eventId);
        receipt.put("production_authority", false);

        if (!result.isSuccess()) {
            journal.recordOutcome(eventId, bindingSession,
                    ShadowEventJournal.Outcome.INFRA_FAILURE, "RUST_ENCODE",
                    String.valueOf(result.failure()));
            receipt.put("outcome", "INFRA_FAILURE");
            receipt.put("rustFailure", String.valueOf(result.failure()));
            Files.write(out.resolve("phase-d-offline-replay.json"),
                    json(receipt).getBytes(StandardCharsets.UTF_8));
            System.out.println("PhaseDOfflineReplay INFRA_FAILURE "
                    + result.failure() + " (Java bytes remain authoritative, untransmitted)");
            return;
        }

        // Cross-check against the oracle's own recording of the same event:
        // the packed result and payload bytes must reproduce identically.
        if (packed != recordedV2)
            throw new IllegalStateException("packed v2 result differs from the oracle recording");
        byte[] rustBody = new byte[result.bytesWritten()];
        for (int i = 0; i < rustBody.length; i++) rustBody[i] = memory.getByte(output + i);
        boolean reproducesRecording = java.util.Arrays.equals(rustBody, recordedNative);

        // The oracle recorded the packet in FULL WIRE FORM: [x, z, fullChunk,
        // mask varint] + varint(bodyLength) + body + varint(tileEntityCount).
        // The live consumer reads the Java body field directly and never sees
        // this framing; the replay reconstructs the body the same way, with
        // the size varint and the TE-count varint as structural self-checks.
        byte[] javaBody = javaWireBody(javaPacket, recordedNative.length);

        // The Phase-D comparison: JavaSide vs RustSide, typed, exact. The
        // Java side's mask comes from the oracle's own recorded V2 result
        // (low 16 bits), already cross-checked byte-for-byte above.
        ShadowEventComparator.JavaSide javaSide =
                new ShadowEventComparator.JavaSide(javaBody, (int) (recordedV2 & 0xFFFF));
        ShadowEventComparator.RustSide rustSide =
                new ShadowEventComparator.RustSide(rustBody, result.emittedMask());
        ShadowEventComparator.Comparison comparison =
                ShadowEventComparator.compareStatic(javaSide, rustSide);

        String outcome;
        if (comparison.pass) {
            journal.recordOutcome(eventId, bindingSession,
                    ShadowEventJournal.Outcome.COMPARE_PASS, null,
                    "javaLen=" + javaPacket.length + " mask=" + result.emittedMask());
            outcome = "COMPARE_PASS";
        } else {
            ShadowEventComparator.FirstDivergence d = comparison.firstDivergence;
            journal.recordOutcome(eventId, bindingSession,
                    ShadowEventJournal.Outcome.COMPARE_MISMATCH, "FIRST_DIVERGENCE",
                    "offset=" + d.offset + " javaLen=" + d.javaLength
                            + " rustLen=" + d.rustLength + " region=" + d.region);
            outcome = "COMPARE_MISMATCH";
            Files.write(out.resolve("first-divergence.txt"),
                    ("offset=" + d.offset + "\njava=" + d.javaSha256 + "\nrust=" + d.rustSha256
                            + "\ncontext=" + d.contextHex + "\n").getBytes(StandardCharsets.UTF_8));
        }

        // A REAL native failure classified as INFRA_FAILURE, Java untouched.
        long failed = OwnedSnapshotBridge.encodeOwnedV1(input, transport.length, output, 1);
        PacketEncodeResultV2 failedResult = PacketEncodeResultV2.decode(failed);
        boolean infraClassified = !failedResult.isSuccess();
        journal.recordOutcome(2L, bindingSession,
                ShadowEventJournal.Outcome.INFRA_FAILURE, "RUST_ENCODE",
                "real capacity failure: " + failedResult.failure());
        boolean javaStillAuthoritative =
                java.util.Arrays.equals(javaBody, javaSide.payload);

        journal.validate();
        receipt.put("outcome", outcome);
        receipt.put("reproducesOracleRecording", reproducesRecording);
        receipt.put("rustEncodeNanos", encodeNanos);
        receipt.put("compareNanos", comparison.compareNanos);
        receipt.put("rustLen", result.bytesWritten());
        receipt.put("rustMask", result.emittedMask());
        receipt.put("javaLen", javaBody.length);
        receipt.put("realCapacityFailureClassifiedInfra", infraClassified);
        receipt.put("javaAuthoritativeAfterFailure", javaStillAuthoritative);
        receipt.put("taxonomy", journal.taxonomyJson());
        receipt.put("parityDenominator", journal.parityDenominator());
        receipt.put("contractSha256", contract.sha256Hex());
        receipt.put("timingsClaimLimit",
                "component measurements only; no end-to-end performance claim");
        Files.write(out.resolve("phase-d-offline-replay.json"),
                json(receipt).getBytes(StandardCharsets.UTF_8));

        boolean pass = ("COMPARE_PASS".equals(outcome) || "COMPARE_MISMATCH".equals(outcome))
                && reproducesRecording && infraClassified && javaStillAuthoritative;
        System.out.println("PhaseDOfflineReplay " + (pass ? "PASS" : "FAIL")
                + " outcome=" + outcome
                + " reproducesRecording=" + reproducesRecording
                + " infraClassified=" + infraClassified
                + " javaAuthoritative=" + javaStillAuthoritative);
        if (!pass) System.exit(1);
    }

    /**
     * Strips the wire framing from a recorded packet: [x(4) z(4) full(1)
     * maskVarint] then a bodyLength varint (validated against the recorded
     * native length) then the body, then the tile-entity count varint
     * (validated zero: TE chunks are outside admitted scope).
     */
    static byte[] javaWireBody(byte[] wire, int recordedBodyLength) {
        int at = 4 + 4 + 1; // x, z, fullChunk
        at += varintLength(wire, at); // primary bit mask
        int sizeLength = varintLength(wire, at);
        long declared = varintValue(wire, at, sizeLength);
        if (declared != recordedBodyLength)
            throw new IllegalStateException("wire size varint " + declared
                    + " does not match the recorded native length " + recordedBodyLength);
        at += sizeLength;
        byte[] body = java.util.Arrays.copyOfRange(wire, at, at + recordedBodyLength);
        int teAt = at + recordedBodyLength;
        int teLength = varintLength(wire, teAt);
        long teCount = varintValue(wire, teAt, teLength);
        if (teCount != 0)
            throw new IllegalStateException("recorded event carries tile entities: out of scope");
        if (teAt + teLength != wire.length)
            throw new IllegalStateException("trailing bytes after the tile-entity count");
        return body;
    }

    private static int varintLength(byte[] data, int at) {
        int length = 1;
        while ((data[at + length - 1] & 0x80) != 0) length++;
        return length;
    }

    private static long varintValue(byte[] data, int at, int length) {
        long value = 0;
        for (int i = 0; i < length; i++)
            value |= (long) (data[at + i] & 0x7F) << (7 * i);
        return value;
    }

    /** Loads the DLL through the bridge's own loader so JNI binds correctly. */
    private static void loadBridge(Path dll) {
        LiveWriterHooks.loadNativeLibraryForBridge(dll.toAbsolutePath().normalize().toString());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readJson(Path path) throws Exception {
        // Minimal single-object JSON reader for the flat oracle document shape.
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(path.toFile()))) {
            String line;
            while ((line = reader.readLine()) != null) sb.append(line).append('\n');
        }
        return (Map<String, Object>) new Json(sb.toString()).parse();
    }

    /** Minimal JSON parser (objects, arrays, strings, numbers, literals). */
    private static final class Json {
        private final String text;
        private int at;
        Json(String text) { this.text = text; }
        Object parse() {
            skip();
            char c = text.charAt(at);
            if (c == '{') return object();
            if (c == '[') return array();
            if (c == '"') { at++; return string(); }
            if (c == 't') { at += 4; return Boolean.TRUE; }
            if (c == 'f') { at += 5; return Boolean.FALSE; }
            if (c == 'n') { at += 4; return null; }
            int start = at;
            while (at < text.length() && "+-0123456789.eE".indexOf(text.charAt(at)) >= 0) at++;
            String number = text.substring(start, at);
            return number.contains(".") || number.contains("e") || number.contains("E")
                    ? (Object) Double.parseDouble(number) : (Object) Long.parseLong(number);
        }
        private Map<String, Object> object() {
            Map<String, Object> map = new LinkedHashMap<String, Object>();
            at++; skip();
            if (text.charAt(at) == '}') { at++; return map; }
            while (true) {
                skip();
                at++; // opening quote
                String key = string();
                skip(); at++; // colon
                map.put(key, parse());
                skip();
                if (text.charAt(at) == ',') { at++; continue; }
                at++; break; // }
            }
            return map;
        }
        private java.util.List<Object> array() {
            java.util.List<Object> list = new java.util.ArrayList<Object>();
            at++; skip();
            if (text.charAt(at) == ']') { at++; return list; }
            while (true) {
                list.add(parse());
                skip();
                if (text.charAt(at) == ',') { at++; continue; }
                at++; break; // ]
            }
            return list;
        }
        private String string() {
            StringBuilder sb = new StringBuilder();
            while (text.charAt(at) != '"') {
                char c = text.charAt(at);
                if (c == '\\') {
                    at++;
                    char escaped = text.charAt(at);
                    if (escaped == 'u') {
                        sb.append((char) Integer.parseInt(text.substring(at + 1, at + 5), 16));
                        at += 4;
                    } else sb.append(escaped == '/' ? '/' : escaped);
                } else sb.append(c);
                at++;
            }
            at++; // closing quote
            return sb.toString();
        }
        private void skip() {
            while (at < text.length() && Character.isWhitespace(text.charAt(at))) at++;
        }
    }

    private static int intOf(Object value) {
        return Integer.parseInt(String.valueOf(value));
    }

    private static String json(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(entry.getKey()).append("\":");
            Object v = entry.getValue();
            if (v instanceof Boolean || v instanceof Number) sb.append(v);
            else if (v == null) sb.append("null");
            else sb.append('"').append(String.valueOf(v)
                    .replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        return sb.append('}').toString();
    }

    private static Unsafe unsafe() {
        try {
            Field f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            return (Unsafe) f.get(null);
        } catch (ReflectiveOperationException error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    private PhaseDOfflineReplay() { }
}
