# V2 Live Shadow — Phase D Event Contract

Status: ACTIVE (Phase D implementation contract)
Baseline head at acceptance: `92ad3e1`
Parent architecture: `docs/research/V2_LIVE_SHADOW_ARCHITECTURE.md`
Phase A taxonomy/capability/receipt: `tools/live-shadow-v2/{taxonomy,receipt,capability}.py`

Phase D implements and proves the FIRST real Java-vs-Rust shadow comparison
pipeline for full-chunk `SPacketChunkData` encode. It proves the measurement
system. It is NOT the closure campaign: no thresholds, no workload
generation, no production authority, no transmission of Rust bytes.

---

## 1. Pipeline

```
S02 (SPacketChunkData.<init> entry)
  ├─ LivePacketCapture.begin ── gate.tryBeginCapture (one tryLock)
  │     └─ CaptureDraft.extract under the held gate
  │           └─ ShadowScopeGate.evaluateCounted   ← per-chunk u16 decision
  │                 ineligible → journal EXCLUDED (reason) → Java-only
  ├─ Java constructor body runs UNCHANGED (authoritative)
  └─ commit: end-view re-read == begin view → seal → release gate →
        bounded nonblocking queue offer
              full/bytes → journal DROPPED_BACKPRESSURE → Java-only
consumer thread (no gate, no lock, owned data only)
  ├─ journal.begin(eventId, sessionId)   ← contract-bound completion registry
  ├─ OwnedSnapshotBridge.encodeOwnedV1   ← Rust shadow encode (timed)
  ├─ ShadowEventComparator.compare(JavaSide, RustSide)
  │     exact byte equality within admitted scope
  │     COMPARE_PASS | COMPARE_MISMATCH(+first divergence) | INFRA_FAILURE
  └─ JSONL per event + taxonomy receipt
```

Java output is authoritative for every event. The Rust result is recorded
and discarded. No Rust byte can reach a Netty buffer, a packet field, the
wire, the scheduler, or any world/chunk state: `SealedLiveCapture` owns
every byte the consumer touches, and the consumer has no reference to any
live object.

## 2. Event identity

One Java `SPacketChunkData` constructor execution = exactly one eventId,
minted at the capture boundary by `LiveWriterGate.tryBeginCapture`
(`CaptureAttempt.eventId()`), carried by:

- the `OwnedPacketSnapshot` (transport header `event_id`)
- the `SealedLiveCapture` (`gateEventId`)
- every journal record and comparison outcome for that event

All correlation is BY IDENTITY. Nothing is paired by timestamp, chunk
coordinates, thread, packet size, or FIFO assumption. The full identity
tuple on every record: `eventId`, `sessionId`, `worldId`, `chunkId`,
`incarnation`, `ownedEncodeGeneration`, `captureEpochStart`,
`captureEpochEnd` (begin == end at seal), `chunkX`, `chunkZ`, `mask`.

The completion registry (`ShadowEventJournal`) rejects: duplicate completion
of a terminal outcome for the same eventId; completion of an unknown
eventId; completion under a session that does not match the bound
`SessionCompatibilityContract`. A rejected completion is recorded as
evidence (protocol violation) and never overwrites the first outcome.

## 3. Coherence and snapshot contract

Reuses the proven Issue-1 machinery unchanged: acquire (one `tryLock`, never
waits) → clone minimal owned state under the gate → end-view equality at
commit → seal (immutable, one-way) → release → asynchronous Rust compute.
The gate is NEVER held during Rust encode or comparison; the consumer
operates exclusively on sealed owned bytes.

Coherence failures (epoch change, section change, TE emergence, binding
stale) reject the capture — they are EXCLUDED/DISQUALIFIED outcomes, never
compared incoherent states presented as mismatches.

## 4. Per-chunk u16 scope (Phase B reuse)

`ShadowScopeGate` is the single place the u16 rule lives. The live capture
path routes its decision through `ShadowScopeGate.evaluateCounted`: all used
state ids ≤ 65535 → eligible; any used id ≥ 65536 → `EXCLUDED_HIGH_STATE_ID`
(structured reason, first offending id, counted telemetry; the chunk falls
back to Java-only). The registry width no longer refuses the runtime under
the per-chunk policy (`LiveCaptureScope.perChunkStateIdLimit=true`):
Revelation's registry (max id 77,663, 35,711 states) is bound for identity
only.

Additional Phase-D admission restriction, structured and counted: a section
whose DISTINCT state count exceeds 256 would serialize on the Java wire
through the runtime global palette, whose width the u16 transport cannot
carry on such registries — such chunks are `EXCLUDED_GLOBAL_PALETTE_SECTION`
(Java-only), never compared, never counted as parity. No transport widening
occurs in Phase D.

## 5. Queue and backpressure

`LiveComparisonQueue` stays exactly as accepted: bounded capacity (default
8 envelopes), bounded aggregate bytes (8 MiB), nonblocking offer, drop
counted. A full queue yields `DROPPED_BACKPRESSURE` in the journal with the
event's full identity — the Java packet path never waits. The Phase-D smoke
adds a bounded event cap (`rustcraft.liveShadowMaxEvents`): once the cap is
reached, further sealed events are recorded `DROPPED_CAPPED` (identity +
reason preserved; not parity). A dropped event never disappears silently.

Telemetry records: capacity, high-water depth/bytes, enqueue successes,
drop counts by reason, enqueue latency, queue wait (compare time minus
seal time), worker count (one consumer thread).

## 6. Rust worker input contract

Rust receives ONLY: the immutable sealed owned transport (RCSNAP01, every
byte owned before serialization starts), the event identity embedded in it,
and the encode parameters in its header (mask, full-chunk, skylight, global
palette bits). The FFI boundary is the existing
`OwnedSnapshotBridge.encodeOwnedV1` — raw off-heap input/output pointers,
`PacketEncodeResultV2` packed return. Rust never reaches back into Java
Chunk/World/registry state during encode. A Rust/JNI failure classifies
`INFRA_FAILURE`; the Java packet result remains authoritative and the Java
application path is unaffected.

## 7. Comparison contract

`ShadowEventComparator` compares, within the admitted scope:
- mask equality (Java packet mask == sealed accepted mask == Rust emitted mask)
- SEMANTIC equality: both payloads decoded by the strict Protocol-340 reader
  (ported line-for-line from the independent Python decoder) and compared
  per-section — all 4096 logical global state ids, block light, sky light —
  plus biomes for full chunks
- exact byte equality is RECORDED per event as an observed fact
  (`byteExact`), never the live pass criterion

Why semantic: the Java wire palette is STATEFUL. Vanilla section palettes
retain entries from replaced blocks (world generation places and replaces;
`idFor` adds on miss and nothing removes), so the Java packet's palette can
carry entries the current logical cells never reference, while the Rust
owned encode builds a minimal palette from the sealed state. Proven on the
first live smoke's preserved artifacts (event 1, chunk -6/-8): Java palette
29 entries vs Rust 21, javaLen 31,628 vs rustLen 31,612, and the independent
decoder shows every logical cell, both light planes and biomes IDENTICAL.
Byte-different, semantically equal is the expected wire behavior of two
palette histories — not a Rust defect and not a pass-criterion failure.

`COMPARE_MISMATCH` requires a genuine logical divergence (cell-level first
divergence: section y, cell index, both global ids; or light/biome plane).
Undecodable payloads are INFRA_FAILURE, never parity. Inputs are typed
(`JavaSide` / `RustSide`) so the two results cannot be swapped accidentally.
A mismatch preserves bounded first-divergence evidence: eventId, identity
tuple, Java/Rust output sha256, lengths, masks, the cell-level divergence,
±16 bounded byte context and full bodies as artifacts (bounded smoke), and
the comparison timings. The logical region is reported where it can be
located and never guessed.

## 8. Taxonomy and counters

Phase-A taxonomy verbatim. Per-event outcomes: `COMPARE_PASS`,
`COMPARE_MISMATCH`, `EXCLUDED` (named scope reason required), `DROPPED`
(named reason required), `DISQUALIFIED`, `INFRA_FAILURE`. `INCOMPLETE` is a
campaign-level state, not a per-event outcome. Parity denominator is
exactly COMPARE_PASS + COMPARE_MISMATCH; exclusions and drops never count
as passes and never enter the denominator. `ShadowEventJournal.validate()`
re-derives counts and refuses disagreement; the taxonomy layer
(`tools/live-shadow-v2/taxonomy.py`) validates the smoke receipts again,
independently, in Python. `production_authority` is false, cannot be set
true, and the receipt refuses to serialize it any other way.

## 9. Exception semantics

Unchanged proven architecture: S02 scoped abort/rethrow on the capture
path; S03–S06 isolated callee wrappers; CalleeIsolation structural proof.
The Phase-D additions are observational only and fail toward evidence, not
toward behavior change: journal/comparator/contract failures classify
`INFRA_FAILURE` while the Java packet proceeds. The consumer's mismatch and
infra-failure paths stop FURTHER comparisons (bounded smoke semantics) but
never touch the Java packet path.

## 10. SessionCompatibilityContract (minimal Phase-D shape)

Created ONCE at diagnostic session establishment (server bootstrap), never
per packet:

```
schema                     RUSTCRAFT_SESSION_COMPATIBILITY_CONTRACT_V1
minecraftProtocol          340
fmlProtocol                2 (FML|HS ServerHello version)
processId                  rustcraft.session.processId
sessionId                  rustcraft.session.transformationSessionId
scopeProfileId             the admitted capture-scope profile
registrySize               block-state registry size at establishment
stateWidthBits             ceil_log2(registrySize), clamped 9..16
registryDigestSha256       content digest of the registry binding
sha256                     digest over the canonical field tuple
```

Channel-set and negotiated mod-set digests are deliberately ABSENT in the
Phase-D contract: they are facts of a client session, not of the shadow
session, and belong to the future connection-time adapter (§ claim limits).
The shadow path binds events to the contract by `sessionId`; a mismatch
prevents shadow admission (the event is disqualified, not compared). No
compatibility lookup occurs inside any per-packet or per-encode operation —
the comparator holds the contract hash.

## 11. Claim limits (preserved verbatim, machine-readable)

A channel classified SAFE_NOOP during the Phase-C join means "no semantic
reply was required during the observed join/stability window". It does NOT
prove "this channel never matters during gameplay". Phase-D smoke receipts
carry the channel classification with its original window qualifier and
never upgrade it.

The headless client's derived mod inventory is legitimate TEST-CLIENT
qualification infrastructure. The eventual real-player compatibility
adapter must evaluate the connecting client's ACTUAL advertised inventory
and Forge compatibility rules; the production adapter must not synthesize a
client inventory from the server. Phase-D receipts record the client
inventory derivation as test-infrastructure provenance.

Phase-D component timings (capture, enqueue, queue wait, Rust encode,
comparison) are COMPONENT measurements. No "Rust is X times faster" claim
is made or implied by this phase.

## 12. Stop conditions

Any of: Java packet output changes because shadow is enabled; Rust bytes
selected for transmission; packet ordering change; deadlock; writer gate
held during Rust encode; unbounded queue/thread growth; event identity
ambiguity; session/runtime identity mismatch; comparison of different chunk
incarnations; unexplained COMPARE_MISMATCH; changed Java exception
semantics; a disconnect attributable to the shadow path; offline
qualification regression; any production-authority change → STOP for Ultra
review with evidence preserved. Phase D never continues into the closure
campaign.
