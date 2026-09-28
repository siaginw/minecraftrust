package com.rustcraft.bridge.capture;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Minimal Phase-D session compatibility contract: the proven session facts
 * the shadow pipeline needs, established ONCE at diagnostic session start and
 * referenced by digest thereafter. This is architectural groundwork for the
 * eventual Rust-side connection-time compatibility adapter (control plane),
 * not a compatibility implementation: channel-set and negotiated mod-set
 * digests are deliberately absent because they are facts of a CLIENT session,
 * and the production adapter must evaluate a connecting client's actual
 * advertised inventory rather than anything synthesized server-side.
 *
 * <p>No per-packet or per-encode operation performs a compatibility lookup.
 * The comparator holds the contract's sha256 and the journal binds event
 * completion to the contract's sessionId; nothing else reads it on any hot
 * path.</p>
 */
public final class SessionCompatibilityContract {

    public static final String SCHEMA = "RUSTCRAFT_SESSION_COMPATIBILITY_CONTRACT_V1";
    /** Protocol 340 (Minecraft 1.12.2) — pinned, not negotiated in Phase D. */
    public static final int MINECRAFT_PROTOCOL = 340;
    /** FML|HS ServerHello protocol version observed in the qualified runtime. */
    public static final int FML_PROTOCOL = 2;

    public final String processId;
    public final String sessionId;
    public final String scopeProfileId;
    public final int registrySize;
    public final int stateWidthBits;
    public final String registryDigestSha256;
    public final long createdAtMillis;
    private final String sha256Hex;

    private SessionCompatibilityContract(String processId, String sessionId,
            String scopeProfileId, int registrySize, int stateWidthBits,
            String registryDigestSha256, long createdAtMillis) {
        this.processId = processId;
        this.sessionId = sessionId;
        this.scopeProfileId = scopeProfileId;
        this.registrySize = registrySize;
        this.stateWidthBits = stateWidthBits;
        this.registryDigestSha256 = registryDigestSha256;
        this.createdAtMillis = createdAtMillis;
        this.sha256Hex = digest(canonicalForm());
    }

    public static SessionCompatibilityContract establish(String processId, String sessionId,
            String scopeProfileId, int registrySize, int stateWidthBits,
            String registryDigestSha256) {
        if (processId == null || processId.isEmpty() || sessionId == null || sessionId.isEmpty()
                || scopeProfileId == null || scopeProfileId.isEmpty()
                || registrySize <= 0 || stateWidthBits < 9 || stateWidthBits > 16
                || registryDigestSha256 == null
                || !registryDigestSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "session compatibility contract facts are incomplete or malformed");
        }
        return new SessionCompatibilityContract(processId, sessionId, scopeProfileId,
                registrySize, stateWidthBits, registryDigestSha256,
                System.currentTimeMillis());
    }

    /** Digest of the canonical field tuple; stable across processes. */
    public String sha256Hex() { return sha256Hex; }

    private String canonicalForm() {
        StringBuilder sb = new StringBuilder();
        sb.append(SCHEMA).append('\u0001')
          .append(MINECRAFT_PROTOCOL).append('\u0001')
          .append(FML_PROTOCOL).append('\u0001')
          .append(processId).append('\u0001')
          .append(sessionId).append('\u0001')
          .append(scopeProfileId).append('\u0001')
          .append(registrySize).append('\u0001')
          .append(stateWidthBits).append('\u0001')
          .append(registryDigestSha256);
        return sb.toString();
    }

    public String toJson() {
        return "{\"schema\":\"" + SCHEMA + "\""
                + ",\"minecraftProtocol\":" + MINECRAFT_PROTOCOL
                + ",\"fmlProtocol\":" + FML_PROTOCOL
                + ",\"processId\":\"" + escape(processId) + "\""
                + ",\"sessionId\":\"" + escape(sessionId) + "\""
                + ",\"scopeProfileId\":\"" + escape(scopeProfileId) + "\""
                + ",\"registrySize\":" + registrySize
                + ",\"stateWidthBits\":" + stateWidthBits
                + ",\"registryDigestSha256\":\"" + registryDigestSha256 + "\""
                + ",\"createdAtMillis\":" + createdAtMillis
                + ",\"sha256\":\"" + sha256Hex + "\""
                + ",\"channelSetDigest\":null"
                + ",\"negotiatedModSetDigest\":null"
                + ",\"claimLimit\":\"facts of the shadow session only; the future adapter"
                + " must evaluate the connecting client's actual advertised inventory\"}";
    }

    static String digest(String canonical) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : hash) sb.append(Character.forDigit((b >> 4) & 0xF, 16))
                    .append(Character.forDigit(b & 0xF, 16));
            return sb.toString();
        } catch (NoSuchAlgorithmException absent) {
            throw new IllegalStateException("SHA-256 unavailable", absent);
        }
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @Override public String toString() { return "SessionCompatibilityContract[" + sha256Hex + "]"; }
}
