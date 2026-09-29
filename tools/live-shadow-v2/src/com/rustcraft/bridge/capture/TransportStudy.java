package com.rustcraft.bridge.capture;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Representation study for the versioned snapshot transport (RCSNAP02):
 * measures RAW_U16 (4096 x u16 logical ids per section, the schema-1 body
 * shape today) against LOCAL_U16_PALETTE (per-section u16 logical palette +
 * packed indices) on REAL captured chunks.
 *
 * Real chunks: the qualified Clean Forge oracle events (transport + javaPacket
 * base64), whose logical state ids are decoded from the recorded Java wire
 * with a strict local reader. Revelation's own chunks arrive with the
 * cross-language fixture; the study reports both when present.
 */
public final class TransportStudy {

    public static void main(String[] args) throws Exception {
        Path events = Paths.get(args[0]);
        Path out = Paths.get(args[1]);
        Files.createDirectories(out.getParent() != null ? out.getParent() : out);
        Map<String, Object> document = json(events);
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        long rawBytes = 0, paletteBytes = 0, cellsTotal = 0;
        long rawNanos = 0, paletteNanos = 0;
        int chunks = 0, sections = 0;
        List<Integer> paletteCardinalities = new ArrayList<Integer>();
        for (Object candidate : (List<?>) document.get("events")) {
            Map<?, ?> event = (Map<?, ?>) candidate;
            if (!"ACCEPTED_PENDING_INDEPENDENT_COMPARISON".equals(event.get("status"))) continue;
            byte[] javaWire = Base64.getDecoder().decode((String) event.get("javaPacket"));
            List<int[]> sectionStates = decodeSections(javaWire);
            if (sectionStates == null) throw new IllegalStateException("undecodable fixture " + event.get("name"));
            chunks++;
            for (int[] states : sectionStates) {
                sections++;
                cellsTotal += 4096;
                long t0 = System.nanoTime();
                byte[] raw = rawU16(states);
                long t1 = System.nanoTime();
                byte[] pal = localPalette(states);
                long t2 = System.nanoTime();
                rawBytes += raw.length;
                paletteBytes += pal.length;
                rawNanos += t1 - t0;
                paletteNanos += t2 - t1;
                paletteCardinalities.add(cardinality(states));
            }
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("event", String.valueOf(event.get("name")));
            rows.add(row);
        }
        Map<String, Object> result = new TreeMap<String, Object>();
        result.put("schema", "RUSTCRAFT_TRANSPORT_STUDY_V1");
        result.put("source", String.valueOf(events));
        result.put("chunks", chunks);
        result.put("sections", sections);
        result.put("logical_cells", cellsTotal);
        result.put("raw_u16_bytes_total", rawBytes);
        result.put("raw_u16_bytes_per_section", sections == 0 ? 0 : rawBytes / sections);
        result.put("local_u16_palette_bytes_total", paletteBytes);
        result.put("local_u16_palette_bytes_per_section", sections == 0 ? 0 : paletteBytes / sections);
        result.put("palette_ratio_of_raw", rawBytes == 0 ? 0 : round(paletteBytes * 100.0 / rawBytes));
        result.put("raw_u16_pack_nanos_total", rawNanos);
        result.put("local_palette_pack_nanos_total", paletteNanos);
        result.put("palette_cardinality_min", paletteCardinalities.stream().min(Integer::compare).orElse(0));
        result.put("palette_cardinality_max", paletteCardinalities.stream().max(Integer::compare).orElse(0));
        result.put("palette_cardinality_mean_x100", paletteCardinalities.isEmpty() ? 0
                : paletteCardinalities.stream().mapToInt(Integer::intValue).sum() * 100 / paletteCardinalities.size());
        Files.write(out, json(result).getBytes(StandardCharsets.UTF_8));
        System.out.println(json(result));
    }

    static long round(double v) { return Math.round(v); }

    // ---- RAW_U16: 4096 u16 logical ids, big-endian ----------------------

    static byte[] rawU16(int[] states) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(8192);
        DataOutputStream out = new DataOutputStream(bytes);
        for (int state : states) {
            if (state < 0 || state > 0xFFFF) throw new IllegalStateException("state exceeds u16");
            out.writeShort(state);
        }
        return bytes.toByteArray();
    }

    // ---- LOCAL_U16_PALETTE: palette of logical ids + packed indices -----

    static byte[] localPalette(int[] states) throws IOException {
        // Deterministic construction: first-appearance order over the 4096
        // cells (independent of any Java palette history by construction).
        int[] palette = new int[4096];
        int cardinality = 0;
        int[] index = new int[4096];
        java.util.HashMap<Integer, Integer> seen = new java.util.HashMap<Integer, Integer>();
        for (int i = 0; i < 4096; i++) {
            Integer existing = seen.get(states[i]);
            if (existing == null) {
                if (states[i] > 0xFFFF) throw new IllegalStateException("palette entry exceeds u16");
                existing = cardinality;
                seen.put(states[i], existing);
                palette[cardinality++] = states[i];
            }
            index[i] = existing;
        }
        int bits = 1;
        while ((1 << bits) < cardinality) bits++;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(8192);
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeShort(cardinality);
        for (int i = 0; i < cardinality; i++) out.writeShort(palette[i]);
        out.writeByte(bits);
        int words = (4096 * bits + 63) / 64;
        long[] packed = new long[words];
        for (int cell = 0; cell < 4096; cell++) {
            long position = (long) cell * bits;
            int word = (int) (position / 64), shift = (int) (position % 64);
            packed[word] |= (long) index[cell] << shift;
            if (shift + bits > 64) packed[word + 1] |= ((long) index[cell]) >> (64 - shift);
        }
        for (long word : packed) out.writeLong(word);
        return bytes.toByteArray();
    }

    static int cardinality(int[] states) {
        java.util.HashSet<Integer> distinct = new java.util.HashSet<Integer>();
        for (int state : states) distinct.add(state);
        return distinct.size();
    }

    // ---- strict Java-wire section reader (same contract as the tests) ---

    static List<int[]> decodeSections(byte[] wire) {
        List<int[]> withSky = decodeSections(wire, true);
        if (withSky != null) return withSky;
        return decodeSections(wire, false);
    }

    static List<int[]> decodeSections(byte[] wire, boolean skylight) {
        try {
            Wire.Reader r = new Wire.Reader(wire);
            r.take(4 + 4 + 1); // x, z, fullChunk
            boolean full = (wire[8] & 0xFF) != 0;
            long mask = r.varint();
            long size = r.varint();
            byte[] body = r.take((int) size);
            if (r.varint() != 0) return null; // tile entities: out of study scope
            int expected = Long.bitCount(mask);
            // The oracle world has skylight; verify by size: sections*(bits+lights) + biomes
            List<int[]> out = new ArrayList<int[]>();
            Wire.Reader b = new Wire.Reader(body);
            while (out.size() < expected) {
                int bits = b.take(1)[0] & 0xFF;
                if (bits < 4 || bits > 16) return null;
                long count = b.varint();
                int[] palette = new int[0];
                if (bits <= 8) {
                    palette = new int[(int) count];
                    for (int i = 0; i < count; i++) palette[i] = (int) b.varint();
                } else if (count != 0) {
                    return null;
                }
                long wordCount = b.varint();
                if (wordCount != (4096L * bits + 63) / 64) return null;
                byte[] packed = b.take((int) (wordCount * 8));
                long[] words = new long[(int) wordCount];
                for (int w = 0; w < wordCount; w++)
                    for (int j = 0; j < 8; j++)
                        words[w] = (words[w] << 8) | (packed[w * 8 + j] & 0xFFL);
                int[] states = new int[4096];
                for (int cell = 0; cell < 4096; cell++) {
                    long position = (long) cell * bits;
                    int word = (int) (position / 64), shift = (int) (position % 64);
                    long value = words[word] >>> shift;
                    if (shift + bits > 64) value |= words[word + 1] << (64 - shift);
                    value &= (1L << bits) - 1;
                    states[cell] = bits <= 8 ? palette[(int) value] : (int) value;
                }
                b.take(2048);        // block light
                if (skylight) b.take(2048); // sky light only when present
                out.add(states);
            }
            if (b.remaining() != (full ? 256 : 0)) return null;
            return out;
        } catch (RuntimeException truncated) {
            return null;
        }
    }

    static final class Wire {
        static final class Reader {
            private final byte[] data;
            private int at;
            Reader(byte[] data) { this.data = data; }
            int remaining() { return data.length - at; }
            byte[] take(int size) {
                if (size < 0 || size > data.length - at) throw new IllegalStateException("truncated");
                byte[] out = new byte[size];
                System.arraycopy(data, at, out, 0, size);
                at += size;
                return out;
            }
            long varint() {
                long value = 0;
                for (int i = 0; i < 5; i++) {
                    int b = data[at++] & 0xFF;
                    value |= (long) (b & 0x7F) << (7 * i);
                    if (b < 0x80) return value;
                }
                throw new IllegalStateException("varint");
            }
        }
    }

    // ---- tiny JSON (single-object + arrays of flat objects) -------------

    static Map<String, Object> json(Path p) throws IOException {
        byte[] raw = Files.readAllBytes(p);
        return (Map<String, Object>) new JsonParser(new String(raw, StandardCharsets.UTF_8)).parse();
    }

    static String json(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(e.getKey()).append("\":");
            Object v = e.getValue();
            if (v instanceof Number || v instanceof Boolean) sb.append(v);
            else if (v instanceof List) {
                sb.append('[');
                boolean f = true;
                for (Object item : (List<?>) v) {
                    if (!f) sb.append(',');
                    f = false;
                    sb.append(item instanceof Map ? json((Map<String, Object>) item) : String.valueOf(item));
                }
                sb.append(']');
            } else {
                sb.append('"').append(String.valueOf(v).replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
            }
        }
        return sb.append('}').toString();
    }

    static final class JsonParser {
        private final String text;
        private int at;
        JsonParser(String text) { this.text = text; }
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
                skip(); at++;
                String key = string();
                skip(); at++;
                map.put(key, parse());
                skip();
                if (text.charAt(at) == ',') { at++; continue; }
                at++; return map;
            }
        }
        private List<Object> array() {
            List<Object> list = new ArrayList<Object>();
            at++; skip();
            if (text.charAt(at) == ']') { at++; return list; }
            while (true) {
                list.add(parse());
                skip();
                if (text.charAt(at) == ',') { at++; continue; }
                at++; return list;
            }
        }
        private String string() {
            StringBuilder sb = new StringBuilder();
            while (text.charAt(at) != '"') {
                char c = text.charAt(at);
                if (c == '\\') {
                    at++;
                    char e = text.charAt(at);
                    if (e == 'u') { sb.append((char) Integer.parseInt(text.substring(at + 1, at + 5), 16)); at += 4; }
                    else sb.append(e);
                } else sb.append(c);
                at++;
            }
            at++;
            return sb.toString();
        }
        private void skip() { while (at < text.length() && Character.isWhitespace(text.charAt(at))) at++; }
    }

    private TransportStudy() { }
}
