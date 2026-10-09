# RustCraft V2 Live-Shadow Architecture

Status: **design / readiness review — approved for implementation, not started.**

This document is written to be sufficient for another agent to implement the V2
live-shadow campaign without inventing missing semantics. It names the existing
classes that carry each responsibility, the exact new artifacts, and the
criteria that were fixed BEFORE any campaign runs.

Nothing here has been executed. No client has been connected, no authority
enabled, no Rust-authored byte transmitted.

---

## 0. What live shadow proves, and what it does not

Offline qualification proved: transformer compatibility, writer placement,
session admission, class identity, final defined bytes, frame validity, hook
survival, and same-process chain correctness — for a *transformation*.

Live shadow answers a different question:

> During a real running server under real workload, does Rust compute the same
> observable result as Java for one shadowed capability, given a coherent
> snapshot of the same authoritative input — while Java remains the only
> authority and nothing Rust computes reaches a player?

It is a runtime-parity experiment, not a performance benchmark and not an
authority grant. A perfect campaign closes at `LIVE_SHADOW_CLOSED` and nothing
beyond.

### Non-goals, enforced structurally

The Rust result is computed from a *sealed, owned copy* of the input. It is
written into a native output buffer owned by the consumer thread and discarded
after comparison (`LiveShadowCampaignConsumer.compareAndRecord` — "the Rust
result is recorded and discarded. It never reaches any wire path"). The
existing fail-closed production entry `M4NativeStatePayload.tryEncode → null`
is untouched by the shadow lane by design (`LiveWriterGate` javadoc: "Production
native packet authority remains fail-closed in M4NativeStatePayload.tryEncode,
which this class never touches"). `CaptureContract.productionAuthorityEligible()`
is `false`. MCK6 unchanged. No shadow component may call any of these, and no
shadow component may be called from them.

---

## 1. The first V2 shadow capability

**Chosen: the full-chunk `SPacketChunkData` packet encode** — the Issue #1
capability, already the subject of the whole writer/capture architecture.

Why this one, against the required criteria:

| Criterion | Assessment |
|---|---|
| Correctness value | It is the chunk-serialization seam — the single operation the entire native-chunk engine would eventually own. |
| Rust maturity | Complete and shipped: `NativeChunk::encode_owned_packet_payload`, `packet_snapshot.rs` parser, `OwnedSnapshotBridge.encodeOwnedV1` JNI, `PacketEncodeResultV2` decoder. No new Rust math. |
| Coherent capture | Already solved and proven in production Java: writer gate → `tryBeginCapture` (one `tryLock`, never waited) → `CaptureDraft` clone under the held gate → `validateForSeal` begin==end equality. This is the architecture Issue #1 built. |
| Deterministic comparison | Byte equality + mask equality + length equality on a sealed input. No timing, no ordering ambiguity in the comparison itself. |
| Event pairing reliability | The S02 hook brackets the `SPacketChunkData` constructor — one constructor execution is one event, with a gate-minted `eventId` minted under the gate. |
| Modpack relevance | Chunk serialization is universal; Revelation exercises modded block states, modded generators, private I/O. |
| Evidence quality | The V1 consumer's per-event JSONL + first-mismatch artifact set is the strongest evidence format in the repo. |
| Authority risk | Minimal: the pipeline already terminates in "record and discard". |
| Complexity | Lowest of any candidate — this is a scope widening of a proven pipeline, not new machinery. |

Runner-up considered: compression/frame shadow (MCK6 surface). Rejected as the
*first* capability because it is a byte-level transform with no coherence
problem to solve — it would validate infrastructure, not the hard correctness
question. It remains a good second capability.

### What V2 must widen (and what it must not)

The V1 pipeline is qualified only for `MINECRAFT_1_12_FLAT` /
`ChunkGeneratorFlat` (`LiveCaptureScope` constructor rejects any other generator
family), Clean Forge, dimension 0. Revelation needs three scope widenings, each
a qualification change, none a transport change:

1. **Generator family.** Revelation uses normal overworld generation, not flat.
   The capture mechanics do not depend on the generator; the generator gate is
   a qualification guard. V2 adds a `MINECRAFT_1_12_OVERWORLD` family bound to
   the concrete generator class observed by discovery, admitted only after
   offline qualification of that runtime.
2. **Registry width.** Measured on the real Revelation runtime:
   35,711 block states, max id 77,663, `registry_max_id_exceeds_u16: true`.
   The current scope-level `REGISTRY_EXCEEDS_U16` check would reject every
   capture. V2 moves the check from the registry to the chunk: a registry that
   exceeds u16 is *permitted*, and a capture whose states all fit u16 is
   admitted; any state above 65535 rejects that capture as `EXTENDED_ID`.
   **The u16 transport is not widened.** 84.1% of Revelation states fit u16
   (measured from `server-registry-state-ids.tsv`), so the comparable fraction
   is real, and the exclusion is counted and reported, never counted as parity.
3. **Dimension/generator set.** Only dimensions whose provider/generator class
   the discovery phase has qualified. No blanket multi-dimension support.

Kept exactly as-is: TileEntity-bearing chunks are excluded (`TE_PRESENT` at
begin and at commit); scope-3 accepts only the vanilla storage model and only
full-chunk packets whose requested mask is exactly `0xffff`
(`packet_snapshot.rs`); partial/incremental packets are out of scope for the
first campaign.

---

## 2. The shadow event

One event = one full-chunk `SPacketChunkData` construction admitted under the
writer gate.

```
SPacketChunkData.<init>(Chunk, int)          [the S02 qualified hook site]
   │
   ├─ constructor entry → LivePacketCapture.begin
   │     gate.tryBeginCapture            exactly one tryLock, never waits
   │     binding = resolveForCapture     READY, unrevoked, source-qualified
   │     reject if TE_PRESENT / EXTENDED_ID / UNSUPPORTED_*
   │     CaptureDraft.extract            clone states/light/biomes under gate
   │
   ├─ constructor body                    JAVA AUTHORITATIVE — untouched
   │
   └─ before return → LivePacketCapture.commit
         draft.validateForSeal            re-read end view; every plane equal
         draft.seal(packet, eventId)      SealedLiveCapture — one way
         gate.endCapture(null)            commit checks under held gate
         LiveComparisonQueue.offer        bounded, nonblocking, may drop
              │
              ▼  (consumer thread, gate-free)
         LiveShadowCampaignConsumer.compareAndRecord
             transport = sealed.toTransportBytes()     RCSNAP01, owned
             rust = OwnedSnapshotBridge.encodeOwnedV1  shadow compute
             compare mask / length / bytes
             record; discard rust buffer
```

**Event existence:** the constructor executing on the canonical owner thread
with a READY binding, admitted by `tryBeginCapture`. A constructor that is
rejected at begin never becomes an event; it is a counted rejection with a
reason.

**Java operation:** the vanilla chunk-packet serialization the constructor
performs — the authoritative bytes that go to the client.

**Rust computation:** `OwnedSnapshotBridge.encodeOwnedV1(transport)` — a
stateless encode of the owned snapshot into a private `NativeChunk`, producing
the packed `PacketEncodeResultV2` (mask + byte count) and the output byte body.

**Authoritative result:** the Java packet payload and mask, cloned at seal.
**Shadow result:** the Rust mask and byte body.

**Comparison legal when:** the seal succeeded (begin==end equality held, epoch
unchanged, no invalidation), the binding was still READY at commit, and the
packed Rust result is a success (`PacketEncodeResultV2` bit 62 set). Compare
`rustMask == javaMask == sealedMask`, then `rustLen == javaLen`, then
`Arrays.equals(rustBytes, javaPayload)`.

**Event discarded when:** any of the rejection reasons at begin (TE, extended
ID, unsupported registry/world, no binding, off-thread, capture busy, writer
active…), any abort (Throwable in the constructor body — the original exception
propagates unchanged and the draft is dropped), any disqualification at commit
(epoch changed, invalidation, plane mismatch), or a queue drop. Each is counted;
none is a pass.

---

## 3. Event identity — correspondence is provable, never inferred by timing

The event id is minted **under the gate** by `LiveWriterGate.tryBeginCapture`
(line ~482). It is carried by:

- the `CaptureAttempt` and the sealed capture (`gateEventId`),
- the RCSNAP01 transport header (`eventId` field),
- the comparison record,
- the first-mismatch artifact.

Identity plane (all present today in `LiveChunkBindings` / RCSNAP01):

| Field | Source | Guarantees |
|---|---|---|
| `eventId` | gate, under lock | unique per attempt within the session; monotone |
| `sessionId` | campaign session | old-session results never enter a new session |
| `worldId` | per-World object identity | wrong-world pairing impossible |
| `chunkObjectId` | exact `Chunk` object | not coordinates |
| `incarnation` | minted per admitted chunk incarnation | reload ⇒ new incarnation; stale pairing impossible |
| `ownedEncodeGeneration` | minted once per incarnation | encode staleness detectable |
| `epoch0/epoch1` | gate epoch at begin and commit | must be equal, else `EPOCH_CHANGED` |
| `x`, `z`, `fullChunk`, `mask` | descriptive only | **never authorize** anything |

The three classic mispairings are structurally excluded:

- *Java N vs Rust N+1* — impossible: both come from one sealed capture; the Rust
  call takes that capture's transport bytes, which carry that `eventId`.
- *old incarnation vs new* — impossible: the binding is resolved by exact
  `Chunk` object and retired irreversibly on unload; a reload mints a new
  incarnation (`admitIoTicket`: "same-object reload requires a retired
  predecessor and always mints a fresh incarnation").
- *pre-unload vs post-reload* — impossible: `retireBeforeUnload` revokes before
  the unload transition; a revoked binding cannot resolve for capture.

Duplicate event id ⇒ campaign stop (`same event ID reused` in §10).

---

## 4. Coherence — "what prevents Java mutating the snapshot mid-flight?"

The writer gate. The answer in full, reusing the existing contract:

- The gate is one `ReentrantLock` per session owned by the canonical server
  thread. `tryBeginCapture` takes it with **one** `tryLock`; if any writer scope
  or publication is open, capture is refused (`CAPTURE_BUSY`/`WRITER_ACTIVE`),
  never waited on.
- `CaptureDraft.extract` clones every plane (states, block light, sky light,
  biomes) **while the gate is held**, so no owner-thread mutation can interleave.
- `validateForSeal` **re-reads the end view and requires exact equality of every
  identity plane and every copied plane**. Any difference means the writer
  protocol was bypassed by an unknown writer ⇒ disqualification, not comparison.
- The epoch is captured at begin and re-checked at commit; a change ⇒ `EPOCH_CHANGED`.
- Off-owner writes are refused **before** the mutation (`ProtocolViolationException`
  from `beginWrite`) — an I/O worker is never made to wait behind the gate.
- The sealed capture contains **no live references**: no `Chunk`, `World`, `EBS`,
  palette or collection — only owned arrays and cloned payload
  (`SealedLiveCapture` javadoc). After sealing, Java may mutate freely; the
  compared bytes cannot change.

So the snapshot Rust sees is a function of one gate-frozen instant, and its
integrity is *proven* by the begin==end equality, not assumed.

**What survives from the hardened ownership architecture into V2 — all of it:**
canonical-owner gate, writer brackets, private-write bypass with exact
receiver-and-backing closure (TLS never suffices), private I/O build tickets
with `FRESH_DISK_CURRENT` source status and sticky taint, owner adoption
(`admitIoTicket`), off-owner disqualification, reentrant-write invalidation,
lifecycle retirement, TE exclusion. Nothing in the map is historical-only; V2
adds no new coherence mechanism, it reuses this one under a wider scope.

---

## 5. Zero-blocking / bounded overhead

All inherited, and already correct:

- Capture admission: one `tryLock`, never awaited; refusal is a counted drop.
- Queue: `LiveComparisonQueue` is bounded (default 8 envelopes / 8 MiB;
  campaign raises to 1024 / 64 MiB via system properties). `offer` is
  nonblocking; a full queue drops the work and counts it — "gameplay is
  unaffected".
- Consumer: a separate daemon thread polling `drain()` every 100 ms; drain is
  gate-free because envelopes are fully owned.
- Rust compute: synchronous on the consumer thread, never on the server thread.
- Retained bytes: bounded by the queue's aggregate byte budget. Sealed captures
  beyond the budget are dropped, not queued.
- Mismatch artifacts: one full event dump (three bounded binaries + metadata),
  then the campaign stops. Not per-event.

New V2 budget items to *measure* in the smoke campaign, not guess: capture CPU
per event, Rust encode CPU, comparison CPU, queue latency p50/p95, drop rate,
heap delta. §14.

**Backpressure rule:** authoritative gameplay is never delayed to preserve
shadow coverage. If the queue is full, evidence is lost and counted — that is
the intended behaviour.

---

## 6. Outcome taxonomy

Fixed now, before any campaign:

| Outcome | Meaning | Counts toward parity? |
|---|---|---|
| `COMPARE_PASS` | seal valid, both sides computed, mask+length+bytes all equal | **yes** |
| `COMPARE_MISMATCH` | seal valid, both sides validly computed, they differ | no — campaign stop |
| `EXCLUDED` | a known unsupported semantic condition (TE present, extended ID, non-vanilla storage, non-full mask, unsupported world/generator) | no |
| `DROPPED` | backpressure: the bounded queue refused the work | no |
| `DISQUALIFIED` | the coherence/lifecycle contract broke (epoch changed, plane mismatch, off-owner write, protocol violation) | no — campaign stop |
| `INCOMPLETE` | the event never reached comparison for an infra reason (capture rejected at begin, abort on Throwable) | no |
| `INFRA_FAILURE` | collector/client/harness malfunction (JNI error, consumer crash, client died) | no — campaign stop |

**Denominator rule:** the parity denominator is `COMPARE_PASS +
COMPARE_MISMATCH` only. Every other outcome is reported separately with
per-reason counters. A campaign with 10,000 exclusions and 50 comparisons
reports "50 compared", never "10,050 events, 100% pass".

Closure additionally bounds the *rates*: drop rate, exclusion rate, and the
per-reason rejection counts must each sit under a threshold fixed in §12, so a
campaign cannot pass by excluding everything interesting.

---

## 7. Mismatch handling — first divergence

Default: **the campaign stops on the first `COMPARE_MISMATCH`** and on the first
`DISQUALIFIED`. The existing artifact set is the required shape:

- `sealed-transport.bin` — the exact RCSNAP01 input Rust parsed
- `java-body.bin` — the authoritative payload
- `rust-body.bin` — the shadow output
- `meta.json` — event identity (eventId, session, world, chunk object id,
  incarnation, generation, epoch pair), coordinates, mask/full flags, ioAdopted,
  thread/owner provenance, gate state, all counters
- `live-shadow-STOP` — the stop-flag file the consumer raises

Plus one new bounded first-divergence field, ordered cheapest-first so the
report localizes without dumping:

1. `maskEqual` / `lenEqual` / `byteEqual`
2. first differing section index (from the mask comparison)
3. first differing byte offset within the first differing section
4. first differing state id and its decoded registry key (u16 → alias map is
   already in `LiveCaptureScope.RuntimeBinding`)
5. only then the full binaries (already bounded by `MAX_SNAPSHOT_BYTES` 256 KiB)

No gigabyte dumps. One mismatch artifact per campaign, because the campaign
stops.

A bounded diagnostic continuation mode exists in the design (a system property
that permits up to N additional mismatches to be *recorded but not compared*)
for triage; it is OFF by default and a campaign run in that mode cannot close.

---

## 8. Historical V1 harness — reuse / port / rewrite / discard

Both files read in full from `archive/revelation-v1-shadow`.

### `live_client_session.py` (170 lines) — **PORT, with two corrections**

Useful and correct: the Conn/varint framing reused from `protocol-live-probe.py`;
LOGIN→PLAY state machine with SetCompression and LoginSuccess handling;
JoinGame/KeepAlive/PlayerPosLook handling with Teleport Confirm; Client Settings;
a slow movement loop (8 m/s, under the vanilla quick-move threshold) that makes
chunks stream, unload and reload naturally; per-pid histogram; JSON summary.
This is real protocol against a real server, not fake traffic.

Corrections and verifications, all checked against the project's own canonical
packet table (`docs/protocol-340/packet-registry-wire.md`):

1. **KeepAlive id: correct as written; the comment is wrong.** V1 sends
   serverbound Keep Alive as `0x0B`, which the canonical table confirms is
   `CPacketKeepAlive`. The inline comment says "reply serverbound 0x0C", and
   `0x0C` is actually `CPacketPlayer`. The constant is right; a reader porting
   from the comment instead of the constant would introduce a real bug. The
   port keeps `0x0B` and deletes the misleading comment. (The other ids check
   out: Teleport Confirm `0x00`, Client Settings `0x04`, Custom Payload
   `0x09`, Player Position `0x0D`.)
2. **Empty client ModList — the one real defect.** The FML|HS ClientHello
   sends a zero-length mod list. Clean Forge accepted it; a 219-mod Revelation
   server will run `NetworkCheckHandler` checks that very likely reject an
   empty list. The port must advertise the server's own pinned mod list.

Also useful: the `handle_fml` skeleton (ServerHello→ClientHello, ModList→Ack,
RegistryData→Ack-on-final) is the right shape and needs the mod-list fix plus
the server-list echo.

### `run_rev_shadow.py` (230 lines) — **PORT the structure, REWRITE the wiring**

Useful: phase structure (control with diagnostic OFF proving default-off, then
bounded shadow phases); golden-world freeze + per-phase disposable copy;
`-Dfml.queryResult=confirm`; `-Drustcraft.liveShadow*` property set; spawn
parsing from the log; `wait_compared` against the receipt with a STOP-file
short-circuit; clean `stop` via stdin.

Rewrite: it predates V2 entirely — it launches the old campaign jar with no
session admission, no static-recipe binding, no path-independent identity, no
long-path workspace, and it reuses V1 jars that no longer exist. The V2 runner
is a new module that composes the existing `qualify_runtime` admission with the
campaign loop.

**Discard:** nothing. Both files stay in the archive untouched.

---

## 9. Client / FML session architecture

Options evaluated:

| | A. headless protocol client | B. controlled real modded client | C. server-local synthetic player |
|---|---|---|---|
| Protocol correctness | Must implement FML|HS + mod channels | Perfect | PlayerEntity injection is not a protocol client |
| Mod list | Must advertise the real 219-mod list (V1 sent 0) | Native | n/a |
| NetworkCheckHandler | Risk: per-mod server checks may still reject | Passes | bypassed, weakens evidence |
| Automation | High (Python, no client install) | Medium (needs a pinned client dir, window automation or `-XstartOnFirstThread` headless check) | High |
| Reproducibility | High — fully scripted | Medium — client state, options.txt, mods dir must be pinned | High but artificial |
| Drives movement/chunk load | Yes (position packets) | Yes | Yes but no network evidence |
| Reconnect | Trivial | Slower | n/a |
| Resource | Tiny | Heavy (full client JVM + 4 GB) | Tiny |

**Recommendation: A, as the primary, with a measured go/no-go gate before any
campaign depends on it.**

Rationale: it is the only option that is simultaneously scriptable,
reproducible, cheap to reconnect, and produces real network-driven chunk
traffic. Its one real risk — mod-list/handshake rejection by 219 mods — is
exactly the kind of thing a cheap Phase-0 probe resolves in minutes: boot the
pinned server, run the ported client with the full mod list, and observe join.
Phase C (below) exists to retire this risk before any campaign code is built on
it.

Fallback if A fails: B — a pinned, offline-mode, real Forge 1.12.2 client with
the Revelation mod set, driven by scripted input. Documented as the fallback,
not designed in detail here, because if A fails the failure mode itself (which
mod rejects, which channel stalls) dictates the B design.

**The old empty-ModList prototype is explicitly not assumed valid.**

---

## 10. Campaign stop conditions

Immediate stop, fail closed, on any of:

1. first `COMPARE_MISMATCH` (unless the bounded diagnostic flag is set)
2. first gate disqualification of any reason
3. admission failure at live launch (any offline-qualified class fails to
   re-admit; see §11)
4. transformation-chain or final-witness disagreement at live launch
5. unknown writer (plane mismatch at `validateForSeal`)
6. lifecycle/epoch contradiction
7. `eventId` reuse
8. any attempt by shadow code to touch authoritative state — enforced by test
   (§16), not by hope
9. any Rust-authored byte observed on the wire (asserted each phase)
10. queue/memory runaway (byte budget exceeded repeatedly; heap exhaustion)
11. world fixture hash drift after a phase (contamination)
12. client protocol drift (login rejected, FML|HS stall)
13. `INFRA_FAILURE` in the consumer (JNI error, drain crash)

A stopped campaign writes its receipt with verdict `STOPPED:<reason>` and
preserves everything. It never silently retries.

---

## 11. Live runtime admission — OFFLINE_QUALIFIED is not a skip

At live launch the server performs the same V2 admission path as the
qualifying run:

- the campaign jar is built from the **same generated plan** the offline
  qualification used (same `RECIPE_BINDING_SHA256`, same policies);
- exact classes must satisfy exact identity; session-bound classes must admit
  under their static policies; fresh certificates are issued by the live
  process; the transformation chain renders; the frame witness verifies.

The runner asserts, before opening the client connection, that the live
receipt's admission facts match the offline qualification's — same class set,
same session-bound set, same downstream inventory, plan hash equal. Any
difference stops admission (stop condition 3/4).

The runtime pins are bound by the **path-independent manifest identity**
(`canonical_manifest_identity`), so the campaign's identity does not move with
its output directory. Absolute paths appear only in provenance.

---

## 12. Capability gate and closure criteria

Capability states (explicit, not a boolean):

```
UNAVAILABLE → OFFLINE_QUALIFIED → LIVE_SHADOW_ELIGIBLE → LIVE_SHADOW_ACTIVE
                                    → LIVE_SHADOW_CLOSED → (AUTHORITY_ELIGIBLE
                                    → AUTHORITY_APPROVED — out of scope, forever
                                    in this document's scope)
```

The first campaign may reach `LIVE_SHADOW_CLOSED` and no further.
`AUTHORITY_*` states are listed only to name what is NOT automatic.

**Closure criteria — fixed before running.** Numbers chosen from the V1
evidence (2372 comparisons in a bounded Clean Forge session) and the
architecture's bounded queue, not arbitrarily large:

| Criterion | Minimum |
|---|---|
| `COMPARE_PASS` | ≥ 2,000 |
| `COMPARE_MISMATCH` (unexplained) | 0 |
| Disqualifications | 0 |
| Coherence violations | 0 |
| Drop rate (`DROPPED / offered`) | ≤ 10% |
| Exclusion rate (`EXCLUDED / admitted`) | ≤ 60% — and reported per reason |
| Compared events covering I/O-origin (`ioAdopted`) chunks | ≥ 200 |
| Distinct chunk incarnations compared | ≥ 300 |
| Distinct unload→reload cycles observed | ≥ 20 |
| Distinct session/client phases | ≥ 2 (initial join + reconnect) |
| Workload phases completed | all of §13 minimum set |
| Native-authored bytes on wire | 0 |
| Production authority state | OFF, asserted per phase |

The 2372/2372 V1 result is context for the order of magnitude only. It is
classified HISTORICAL V1 EVIDENCE and is not V2 closure.

---

## 13. Workload phases

Minimum closure workload (all required):

1. **control** — server booted with diagnostic OFF; assert zero captures, zero
   native output, default-off behaviour.
2. **join** — client connects, spawn chunk load, first ≥ 50 full-chunk packets.
3. **traverse** — sustained movement (the ported 8 m/s loop) through
   already-generated terrain; view-distance-driven load/unload churn.
4. **reload** — return traverse over unloaded-then-reloaded chunks; exercises
   the private-I/O path and fresh incarnations.
5. **reconnect** — disconnect, rejoin; new client session, same server session.

Optional extended (not required for closure; run only if time permits and
reported separately): fresh-chunk generation at the world border; a second
dimension (only if discovery qualified that dimension); a server save-all cycle.

The workload does not include: block placement/breaking by the client (partial
packets are out of scope), TE-heavy structures (excluded), or combat.

---

## 14. Evidence schema, retention, observability, performance

### Campaign receipt (`live-shadow-receipt.json`, extended)

```
schema: RUSTCRAFT_V2_LIVE_SHADOW_CAMPAIGN_V1
static_recipe_sha256, runtime_manifest_identity, writer_plan_sha256,
policy_sha256[], dll_sha256, campaign_jar_sha256,
server_launch_identity (session, process),
client_identity (mode, mod_list_hash, protocol),
world_fixture_identity (source hash, copy hash, run id)

events: { offered, admitted, sealed, compared, pass, mismatch,
          excluded, dropped, disqualified, incomplete, infra_failure }
per_reason: { TE_PRESENT, EXTENDED_ID, CAPTURE_BUSY, ..., QUEUE_FULL, ... }
phases: { name → { duration_s, compared, pass, drop_rate, exclusion_rate } }
incarnations_compared, io_adopted_compared, reload_cycles_observed
first_divergence: <present only if a mismatch occurred>
production_authority: false
native_bytes_on_wire: 0
verdict: CLOSED | STOPPED:<reason> | INCOMPLETE:<reason>
```

Raw JSONL per compared event is retained (it is the campaign's primary
evidence, and each row is small). Full binaries are retained only for:
mismatches, the first and last event of each phase, and a sampled 1-in-200
passing events (for spot audit). Storage ceiling: the queue byte budget plus
the artifact set; nothing unbounded.

### Observability counters (never one "shadow_failed" bucket)

Capture refused per reason (28 existing `RejectionReason` values), gate
disqualification per reason (7 existing values), writer-depth/publication-depth
rejections, queue offers/drops/bytes, JNI call count and error count, Rust
encode success/failure per `SnapshotRejection` variant, comparison
mask/len/byte mismatch counters, native-authored-wire-bytes (asserted 0),
phase boundaries, client session events (join, keepalives, teleports,
disconnect).

### Performance (recorded, never claimed as production)

Java tick impact (ms/tick sampled per phase), capture CPU per event, Rust
encode CPU per event, comparison CPU, queue latency p50/p95, heap delta, events
per second, drop rate. **No production performance claim may be made from
shadow mode** — it runs both the Java and the Rust work.

---

## 15. Threading / happens-before

```
canonical owner thread                     consumer thread
────────────────────────                   ─────────────────
writer scope / gate lock
   │ (happens-before: lock)
tryBeginCapture ── mint eventId
   │ extract (under lock)
   │ constructor body (Java authoritative)
   │ validateForSeal (under lock)
   │ seal  ──────────────────────────────▶ offer (nonblocking)
   │ endCapture (unlock)                     │ drain (gate-free)
   │ continue gameplay                       │ toTransportBytes (owned)
                                            │ JNI encodeOwnedV1
                                            │ compare
                                            │ record
                                            │ free native buffer
                                            ▼ retirement
```

Buffer ownership: the sealed capture and its arrays are owned by the queue then
by the consumer; `javaPayload()` clones on every read; the native input/output
buffers are allocated and freed by the consumer thread only.

Edge cases, all handled by existing identity (§3): chunk unloads before Rust
completes → the sealed copy is still valid (no live references) and compares
correctly against its own generation; chunk reloads into a new incarnation → new
incarnation, no cross-pairing; session ends → `sessionId` mismatch discards;
server shuts down → drain-to-empty with a bounded grace, then receipt; stale
queue item → compares against its own sealed input, which cannot change;
duplicate event → stop condition 7.

---

## 16. Test matrix (controls written BEFORE campaign implementation)

Each is a negative control proving the guard bites; run in the offline harness,
not by launching campaigns:

| Control | Expected |
|---|---|
| event-id mismatch between Java and Rust records | mismatch detected, event DISQUALIFIED |
| wrong generation in transport | Rust rejects (`CaptureChanged`) |
| wrong incarnation in transport | Rust rejects (`ChunkReplaced`) |
| stale Rust result (older generation offered) | rejected before comparison |
| duplicate eventId | campaign stop |
| queue overflow | drop counted, server thread never blocked (asserted by timing) |
| unknown writer (plane mutated between begin/end) | `validateForSeal` fails, disqualification |
| capture invalidation (reentrant write during capture) | `REENTRANT_WRITE`, capture refused |
| Rust output mutation attempt (flip a byte in the output buffer) | comparison detects; and a test that the shadow path has no write access to any Java object |
| injected Java/Rust mismatch | first-divergence report contains the right first differing section/offset/state |
| excluded chunk (TE or extended ID) | counted EXCLUDED, never compared |
| I/O-origin chunk | `ioAdopted` recorded from the ticket system, not inferred |
| unload during compute | comparison still valid (owned copy), new incarnation on reload |
| reconnect/session rollover | old-session records rejected |
| receipt corruption (edit a counter) | receipt hash mismatch detected |
| authority stays OFF under every failure mode | `productionAuthorityEligible()==false`, `tryEncode()==null`, native-wire-bytes==0 asserted after each |

---

## 17. Implementation phases

Each phase: inputs → outputs → tests → stop condition → commit.

**Phase A — campaign schema + capability state machine.**
In: this document. Out: `ShadowCapabilityState`, campaign receipt schema types,
outcome taxonomy enum. Tests: taxonomy/denominator unit controls; receipt
round-trip; state machine transition controls (never skips to authority).
Stop: if any state transition cannot be made explicit.
Commit: `feat(live-shadow-v2): campaign schema and capability state machine`.

**Phase B — scope widening (generator family + registry-to-state u16).**
In: §1.3. Out: `LiveCaptureScope` accepts a qualified overworld generator
family; `REGISTRY_EXCEEDS_U16` becomes per-state `EXTENDED_ID` at capture;
Rust unchanged. Tests: the existing offline capture suites extended — a
registry exceeding u16 with all-u16 chunk admits; a single high-id state
rejects that chunk only; u16 transport still rejected above 65535 on the Rust
side (already tested in `packet_snapshot.rs`).
Stop: if widening requires touching `packet_snapshot.rs` u16 semantics — that
would be a transport widening and is out of scope.
Commit: `feat(live-shadow-v2): qualify overworld generators and per-state u16 scope`.

**Phase C — client harness port.**
In: §8, §9. Out: `tools/live-shadow-v2/client.py` (the corrected port:
KeepAlive 0x0C, full mod-list advertisement, spawn/teleport handling, movement
loop, reconnect); a connectivity probe mode. Tests: against Clean Forge first
(V1-proven surface), then a Phase-0 Revelation join probe.
Stop: if the Revelation join is rejected and the rejecting mod cannot be
satisfied headlessly → escalate; fallback is option B and a new design note.
Commit: `feat(live-shadow-v2): port and correct the protocol client harness`.

**Phase D — campaign runner.**
In: the existing `run_live_shadow.py` structure, the V2 admission path, the
short-workspace + path-independent identity. Out: `tools/live-shadow-v2/run.py`
driving phases, workload, receipts, stop conditions; golden-world fixture
management (source hash → per-run disposable copy → post-phase hash → discard).
Tests: dry-run receipt generation; fixture contamination control; stop-condition
wiring (each condition provably stops).
Commit: `feat(live-shadow-v2): generic campaign runner`.

**Phase E — comparator hardening.**
In: §7. Out: first-divergence fields (section, offset, state id + registry key)
in `meta.json`; bounded diagnostic flag. Tests: §16 mismatch-localization rows.
Commit: `feat(live-shadow-v2): bounded first-divergence reporting`.

**Phase F — Clean Forge infrastructure smoke.**
Run: control + join + short traverse on Clean Forge (V1-proven surface) using
the V2 runner end to end. Purpose: validate the runner, receipt, stop wiring,
client, and performance instrumentation where correctness is already known.
Not a parity campaign; verdict `SMOKE_OK`.
Stop: any §10 condition.
Commit: `test(live-shadow-v2): clean forge infrastructure smoke` (receipt only).

**Phase G — Revelation bounded campaign.**
Run: §13 minimum workload on the pinned Revelation runtime, after live
admission asserts equality with the offline qualification. Verdict by §12.
Stop: any §10 condition; also stop if the exclusion rate at the end of the
join phase projects above the 60% closure bound, and report the measured rate
rather than grinding.
Commit: `test(live-shadow-v2): revelation bounded campaign` (receipt only).

Clean-Forge-first is a deliberate sequencing choice, not a qualification
requirement: the smoke validates the *campaign machinery* on the surface where
V1 already proved capture correctness, so a smoke failure unambiguously indicts
the new harness rather than the widened scope.

---

## 18. Module plan (no files moved in this task)

```
tools/live-shadow-v2/
  __init__.py
  capability.py      # ShadowCapabilityState + transitions
  taxonomy.py        # outcome enum + denominator rules
  receipt.py         # campaign receipt build/validate/hash
  client.py          # corrected protocol client (Phase C)
  fixture.py         # golden world freeze / per-run copy / hash
  workload.py        # phase definitions + drivers
  run.py             # campaign runner (admission → phases → receipt)
```

All active code lives here. Nothing is added to `tools/live-capture/` (that
directory holds the offline qualification tooling and the two historical V1
runners, which stay where they are). The Java side widens existing classes
(`LiveCaptureScope`, `CaptureDraft`) rather than adding parallel ones, because
the whole point is that the V1 coherence machinery is the V2 machinery.

---

## 19. Authority-safety proof obligation

For every phase, and as a stop condition:

- `M4NativeStatePayload.tryEncode` returns `null` (unchanged source, asserted)
- `CaptureContract.productionAuthorityEligible()` returns `false`
- MCK6 `FrameAuthorityHandler.MODE` remains `OFF_EXPERIMENTAL`/disabled
- the count of Rust-authored bytes observed on any client connection is 0
- no shadow class appears in any production wire path (asserted by the §16
  "no write access" control)

These are the same invariants the offline qualification already asserts; the
campaign re-asserts them per phase because a live server is exactly where drift
would be costly.

---

## 20. Unresolved items (honest)

1. **Revelation client join is unproven.** The single highest-risk unknown.
   Retired cheaply by Phase C's Phase-0 probe; fallback documented.
2. **Comparable fraction on Revelation is 84.1% of states by id, but the
   per-chunk comparable fraction is unknown** — a chunk is excluded if it
   contains even one high-id state. Modded build-heavy chunks will exclude more
   often. Phase G measures it and the closure bound (60%) gates on it.
3. **Mod-channel traffic after FML|HS.** Some mods open SimpleChannels and
   expect responses; the headless client must tolerate (ignore) them. Detected
   in Phase C; if any mod disconnects on silence, the client needs channel
   stubs or the fallback applies.

None of these is an architecture blocker; all three are measurement gates that
the phase plan retires in order of cost.
