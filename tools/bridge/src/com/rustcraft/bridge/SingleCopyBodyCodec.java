package com.rustcraft.bridge;

import io.netty.buffer.ByteBuf;

/**
 * Protocol-340 SPacketChunkData body parser used ONLY by the shadow capture
 * to decide decode equivalence: two byte-different bodies are equivalent when
 * they deliver the same world state to the client — same coordinates/flags/
 * mask, and for every masked section the same 4096 cell states (each side
 * decoded through its own palette), the same block/sky light bytes, and (for
 * full chunks) the same biome bytes.
 *
 * This exists because vanilla's BlockStatePaletteHashMap never shrinks: after
 * block churn a Java section keeps stale palette entries (and their wire
 * bytes) forever, while the native encoder emits a minimal palette. Both are
 * valid encodings; byte equality is stricter than client-visible equality.
 *
 * Section wire format (per section, matching vanilla BlockStateContainer
 * write + light arrays):
 *   u8 bitsPerBlock
 *   VarInt paletteLen (0 for the global palette)
 *   paletteLen x VarInt global state ids
 *   VarInt dataLen (longs)
 *   dataLen x i64 big-endian
 *   2048 B block light
 *   2048 B sky light (when the body was built with skylight)
 * followed (full chunk) by 256 biome bytes and a VarInt tile-entity count.
 */
public final class SingleCopyBodyCodec {

    /** One parsed section: cells resolved to global state ids + light bytes. */
    public static final class ParsedSection {
        public final int[] cells;        // 4096 global state ids
        public final byte[] blockLight;  // 2048
        public final byte[] skyLight;    // 2048 or null

        ParsedSection(int[] cells, byte[] blockLight, byte[] skyLight) {
            this.cells = cells;
            this.blockLight = blockLight;
            this.skyLight = skyLight;
        }
    }

    public static final class ParsedBody {
        public int packetId = -1;
        public int chunkX;
        public int chunkZ;
        public boolean fullChunk;
        public int mask;
        public final ParsedSection[] sections = new ParsedSection[16];
        public byte[] biomes; // full chunk only
        public long tileEntityCount = -1;
    }

    private SingleCopyBodyCodec() { }

    /**
     * Parse a complete pre-compression body. Returns null on ANY structural
     * surprise (a malformed body is never decode-equivalent to anything).
     * Sky light presence is self-validating: sections must consume exactly
     * their wire bytes for one of the two conventions, and the trailer must
     * land exactly at the buffer end.
     */
    public static ParsedBody parse(ByteBuf body) {
        int originalReader = body.readerIndex();
        try {
            int reader = body.readerIndex();
            ParsedBody out = new ParsedBody();
            out.packetId = readVarInt(body);
            out.chunkX = body.readInt();
            out.chunkZ = body.readInt();
            out.fullChunk = body.readBoolean();
            out.mask = readVarInt(body);
            long declaredLen = readVarInt(body);
            int payloadStart = body.readerIndex();

            // Resolve the skylight convention by parsing with it on, then off;
            // exactly one convention can consume the declared payload exactly.
            ParsedBody attempt = parsePayload(body, reader, payloadStart, (int) declaredLen, out, true);
            if (attempt == null) {
                body.readerIndex(payloadStart);
                attempt = parsePayload(body, reader, payloadStart, (int) declaredLen, out, false);
            }
            if (attempt == null) {
                return null;
            }
            return attempt;
        } catch (Throwable malformed) {
            return null;
        } finally {
            body.readerIndex(originalReader); // never disturb the live body
        }
    }

    private static ParsedBody parsePayload(ByteBuf body, int bodyStart, int payloadStart,
                                           int declaredLen, ParsedBody out, boolean sky) {
        try {
            int cursor = payloadStart;
            for (int s = 0; s < 16; s++) {
                if ((out.mask & (1 << s)) == 0) {
                    continue;
                }
                body.readerIndex(cursor);
                int bits = body.readUnsignedByte();
                if (bits < 4 || bits > 16) {
                    return null;
                }
                int paletteLen = readVarInt(body);
                if (bits >= 9) {
                    if (paletteLen != 0) {
                        return null; // global palette writes an empty list
                    }
                } else if (paletteLen <= 0 || paletteLen > (1 << bits)) {
                    return null;
                }
                int[] palette = new int[bits >= 9 ? 0 : paletteLen];
                for (int i = 0; i < palette.length; i++) {
                    palette[i] = readVarInt(body);
                }
                int words = readVarInt(body);
                if (words <= 0 || words > 4096) {
                    return null;
                }
                long[] data = new long[words];
                for (int w = 0; w < words; w++) {
                    data[w] = body.readLong();
                }
                byte[] blockLight = new byte[2048];
                body.readBytes(blockLight);
                byte[] skyLight = null;
                if (sky) {
                    skyLight = new byte[2048];
                    body.readBytes(skyLight);
                }
                cursor = body.readerIndex();

                int cellsPerLong = 64 / bits;
                int cellMask = (1 << bits) - 1;
                int[] cells = new int[4096];
                for (int i = 0; i < 4096; i++) {
                    long word = data[i / cellsPerLong];
                    int shift = (i % cellsPerLong) * bits;
                    int index = (int) ((word >>> shift) & cellMask);
                    cells[i] = bits >= 9 ? index
                            : (index < palette.length ? palette[index] : -1);
                }
                out.sections[s] = new ParsedSection(cells, blockLight, skyLight);
            }
            if (out.fullChunk) {
                body.readerIndex(cursor);
                out.biomes = new byte[256];
                body.readBytes(out.biomes);
                cursor = body.readerIndex();
            }
            // The declared payload must end exactly here; the TE trailer
            // follows and must be the last thing in the body.
            if (cursor != payloadStart + declaredLen
                    || payloadStart + declaredLen > body.writerIndex()) {
                return null; // skylight convention mismatch or framing error
            }
            body.readerIndex(cursor);
            out.tileEntityCount = readVarInt(body);
            if (out.tileEntityCount != 0) {
                return null; // admitted bodies carry no tile entities
            }
            if (body.readerIndex() != body.writerIndex()) {
                return null; // trailing garbage
            }
            return out;
        } catch (Throwable malformed) {
            return null;
        }
    }

    /**
     * Decode equivalence: same header semantics and, per masked section,
     * identical cell states and light bytes (biomes for full chunks).
     */
    public static boolean decodeEquivalent(ParsedBody a, ParsedBody b) {
        if (a == null || b == null) {
            return false;
        }
        if (a.packetId != b.packetId || a.chunkX != b.chunkX || a.chunkZ != b.chunkZ
                || a.fullChunk != b.fullChunk || a.mask != b.mask) {
            return false;
        }
        for (int s = 0; s < 16; s++) {
            if ((a.mask & (1 << s)) == 0) {
                continue;
            }
            ParsedSection sa = a.sections[s];
            ParsedSection sb = b.sections[s];
            if (sa == null || sb == null) {
                return false;
            }
            if (!java.util.Arrays.equals(sa.cells, sb.cells)) {
                return false;
            }
            if (!java.util.Arrays.equals(sa.blockLight, sb.blockLight)) {
                return false;
            }
            if (!java.util.Arrays.equals(sa.skyLight, sb.skyLight)) {
                return false;
            }
        }
        if (a.fullChunk) {
            if (!java.util.Arrays.equals(a.biomes, b.biomes)) {
                return false;
            }
        }
        return true;
    }

    private static int readVarInt(ByteBuf buf) {
        int value = 0;
        int shift = 0;
        for (int i = 0; i < 5; i++) {
            byte b = buf.readByte();
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return value;
            }
            shift += 7;
        }
        throw new IllegalStateException("varint too long");
    }
}
