# M2 Proposal: Native Outbound Packet Compression (M2-C)

Status: PROPOSAL ONLY — no implementation, no profiling campaign, no gate
changes. M1 parked/OFF; H2 parked. Grounded in existing valid research
(P0-5 network pipeline mapping, `machine/network-pipeline.yaml`), the M2J
capture's measured traffic, and the existing Rust skeleton
(`crates/compression` trait; `crates/nbt` codec precedent).

## The one bounded engine job

Replace the JDK-deflate implementation behind Netty's outbound
`compress` handler (`NettyCompressionEncoder`, threshold 256, per
`network-pipeline.yaml`) with a Rust zlib/zlib-ng encoder called at a
coarse per-packet boundary, behind a runtime property and shadow mode,
reusing M1's proven ladder (JAVA_OWNED → SHADOW_TESTED →
JAVA_OWNED_RUST_ACCELERATED).

## 1. What moves into Rust

The raw byte transform only: for each outbound packet whose framed body
exceeds the 256-byte threshold, compress the plaintext body bytes with a
native deflate encoder. Nothing else: framing, VarInt lengths, packet
identity, encryption, and socket IO all stay Java/Netty. Decompression
(inbound) stays Java in this bounded job (one direction only).

## 2. Java/mod callbacks that remain

Compression sits BELOW the mod surface: no Forge events, no mod-visible
API, no reflection, no coremod hook needed on any packet class (unlike
M1's ctor hook). The handler is swapped at pipeline-assembly time in
NetworkManager.setCompressionThreshold — a single vanilla seam. Mods that
replace Netty pipeline handlers (rare; network-coremod-hazards.md
surveyed them) are unaffected because the handler position/name/contract
is unchanged.

## 3. Coarse boundary and copying costs

Input: one heap ByteBuf's readable body (already materialized by the
framer) → pinned via GetPrimitiveArrayCritical exactly like M1 HANDOFF-A;
Rust deflate writes into a caller-sized output buffer; release; Netty
wraps the compressed bytes and re-frames. Copying cost: one
heap→native read pass + one native→heap write pass per compressed packet
— the same two-pass shape M1 measured as acceptable at ~30KB payloads
(chunk data dominates outbound bytes). Packets under threshold take the
existing path unchanged. The 2MiB bomb guard (P0-5) is preserved as a
hard output-size cap returning a Java fallback.

## 4. Architectural value (separate from performance)

- First Rust component INSIDE the live outbound pipeline owning a
  complete, self-contained transform — the next real rung of the
  ownership ladder after M1's read-only packet build.
- Establishes the byte-transform JNI pattern (buffer in → buffer out,
  bounded, critical-section-safe) reusable for NBT/region pipelines the
  roadmap already targets (crates/nbt, crates/region-io exist).
- Benefits ALL outbound packet types automatically; its value scales
  with player count and any future packet-path work, independent of
  chunk-packet volume.
- Rollback is a single property (M1 pattern), zero on-disk or wire-
  format commitments (zlib framing is unchanged; only the encoder
  implementation differs).

## 5. Smallest independent parity test + non-regression experiment

- Parity (offline, deterministic): oracle harness over the committed
  packet corpus (M1's 24 fixtures + fuzz) — for each payload ≥ threshold:
  `inflate(rust_compress(x)) == x` AND `inflate(java_compress(x)) == x`
  AND `len(rust_out)` within a stated band of `len(java_out)` (byte-
  identity is NOT required across zlib implementations; roundtrip
  identity is the invariant). Plus adversarial inputs: empty, threshold-
  adjacent, 2MiB guard, incompressible random, and decompression-bomb
  fixtures.
- Non-regression (live, bounded): the existing protocol bot already
  zlib-decompresses every inbound frame — run the M1H-style SHADOW/ON
  A/B: bot sessions with the native encoder active, 0 decode errors
  required, plus one paranoid-Netty run. Matched microbench (fresh JVMs,
  balanced order) measures encoder throughput/copy overhead vs JDK
  deflate before any claim is made.

## Measured opportunity (honest)

The M2J streaming workload pushed 251.3 MB / 150 s through this exact
path (bot decode, 0 errors), and `Netty Server IO` threads accounted for
1,848/12,474 (15%) of sampled allocation events — but their CPU share is
UNKNOWN from the JFR capture (under-sampled) and NO speedup is claimed.
zlib-ng's typical advantage over JDK deflate is a hypothesis to be
measured by the §5 microbench, not an assumption.

## Explicitly out of scope

Inbound decompression; encryption; any save/NBT work (H1 parked); any
Java-side patching (H2 parked); acceptance-gate changes (none without
operator approval).
