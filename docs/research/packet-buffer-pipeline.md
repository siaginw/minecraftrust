# H14 packet/buffer pipeline experiment

Independent root review reran the complete frozen-source campaign at
`target/architecture-hardening/h14-packet-buffer-7fd10bf88555/receipt.json`, SHA-256
`5cd641526d1eb76e2c163fee502e4d2b03a676ac4807c27d057a95dcfe11d789`.
All 14 Rust tests, four Job controls, four Java configurations, two tamper
controls, 24 pipeline rows and 18 handle rows passed. Immediate-receiver medians
were 6.191 ms separate and 6.488 ms shared, reversing the first campaign's small
median difference within overlapping ranges. Delayed medians were 82.287 and
82.506 ms. Exact copy and semantic results held; no throughput gain is established.
Both raw campaigns and the earlier failed controls are preserved in the machine
evidence inventory. The result report itself is outside the runner's source
binding; the implementation and methodology README were frozen for both runs.

Decision: **PROTOTYPE / BORROW_ALGORITHM**, with no production adoption yet.
Keep immutable body sharing and bounded, connection-scoped order restoration as
design candidates. Keep standard-library `Arc` for this prototype; the tested
`bytes` use did not justify replacing it. Production compression, MCK6, JNI,
defaults and packet authority are unchanged. This synthetic offline experiment
does not close Issue #1 or qualify live snapshot coherency.

## Qualification and evidence

Final campaign: `target/architecture-hardening/h14-packet-buffer-e8e91da68b34/receipt.json`.
Status **PASS_BOUNDED_PROTOTYPE_ONLY**; SHA-256
`52bce21eb5485b9b9699caf46fbb0e10e588a5bffcccdd6916417f488dc8d70c`.
Binary SHA-256: `80378836ac6d36002f4f4380b604d79789e8032c3aceea51c91d6c8fba91caa9`.
Run command: `python tools/packet-buffer-pipeline-experiment/run.py --measure`.

The receipt binds 57 source/input files, 16 executable/runtime files and 16
registry archives containing 1,023 source files before and after execution.
Root compression pins match the isolated lockfile exactly. Java class and native
binary hashes remained unchanged. Source scope includes the isolated experiment,
actual local dependency sources, root manifests, isolation/gate files, and the
read-only H9 Job helper and its controls. A checked source/tool hash is evidence
of identity, not a trusted-machine attestation. The source-bound README describes
the timer boundaries, budgets, counter definitions and limitations in full.

Validation passed:

- 14 Rust contract tests, including real two-worker reorder, foreign identity,
  canceled-hole credit retention, completion abandonment, admission/retention
  bounds, reconnect, compression failure and socket failure cases.
- Scoped `cargo fmt --check`, strict `cargo clippy --all-targets --no-deps -- -D
  warnings`, isolated locked/offline release build and four H9 Job controls.
- Four actual loopback configurations, each independently Java-qualified:
  shared/fast, shared/80-ms delayed receiver, unshared/delayed receiver, and
  17-byte offered send quantum. Each delivered exactly 27 packets on four
  connections, totaling 128,282 encrypted bytes, after one canceled submission.
- Cipher-byte and zlib-checksum corruption both produced the expected
  first-divergence failures. All measured wire hashes matched the independently
  Java-qualified streams.
- 24 fresh-process pipeline runs: four discarded warmups and 20 measured runs.
  The handle comparison produced 18 rows: three discarded warmups and 15
  measured rows. These are separate counts, not extra correctness tests.

The unchanged dependency warning is `crates/compression/src/frame.rs:205`,
unused pattern variable `have`. `accepted-backend-warning-source.stdout.log`
records the accepted-HEAD source containing that site; its SHA-256 is
`d6f62c00e2a83f68c816ef6ff4121181df5949474c1c49d6af10b5e8932900ab`.
No warning was suppressed and no production dependency source was edited.

Java independently generated the synthetic bodies, checked native framing,
inflated compressed frames, and encrypted/decrypted the continuous actual socket
streams using JCE AES-CFB8. Eleven of 27 framed packets were byte-identical to
JDK Deflater output; 16 used different valid deflate bytes and decoded exactly.
Their first differing offsets are retained in Java logs. The AES wire comparison
uses independently validated native plaintext frames; it does **not** claim the
different JDK compressed streams produce the same ciphertext. Java CFB8 and
multipart operation semantics come from the [Java 8 Cipher documentation](https://docs.oracle.com/javase/8/docs/api/javax/crypto/Cipher.html);
compression reset/finish behavior is documented by [Deflater](https://docs.oracle.com/javase/8/docs/api/java/util/zip/Deflater.html).

## Bounded ownership and failure handling

Bodies are immutable `Arc<Body>` values, keyed by private snapshot identity,
state/lifecycle/registry generations, protocol and exact recipient rule. The
fixture has only one protocol and fixed lifecycle/registry generations; it is
not a complete modern-version or H6 dependency key. Separate recipient rules
produce different bytes. Cache clear cannot free or stop counting an external
reader. Body payload retention is bounded at 512 KiB/128 bodies.

Two workers retain a worst-case output reservation across work admission,
execution, result queuing, sequence reordering, encryption and final send. The
budget is 16 jobs and 1 MiB; canceled holes also keep credits until ordered
retirement. Controller tracking has a separate admission bound so dropping a
detached completion cannot grow ticket metadata indefinitely. Such abandonment
remains fail-closed until teardown. Compression failures terminate a connection.
Opaque origin/slot/generation IDs and private tickets prevent foreign or stale
completions/cancellation from consuming local work. Closing/unloading a fixture
connection and reconnecting cannot advance the new cipher with old output.

The controller encrypts only consecutive accepted frames, preserving a rolling
AES-CFB8 state. Header and payload stay separate through vectored writes; the
send cursor advances by the actual accepted prefix. Retry never re-encrypts.
A fatal write can follow an already received prefix, so it terminates the
connection rather than claiming transactional wire rollback.

The real paused-receiver pressure control observed one `WouldBlock`, zero OS
short writes, 2,097,248 reassembled bytes and peak reservation **984,180 bytes**.
It resumed the receiver only after observing pressure, then decrypted every
frame exactly. The real reset control received and checked a 32-byte prefix,
reset the peer, observed a socket error and released unsent reservations.
Scripted writers independently force short returns, WouldBlock and a fatal
error. All ordinary/timed loopback runs observed **zero OS short writes and zero
WouldBlock**. Their 17-byte offered quantum is not counted as OS partial-write
evidence. Requested socket buffer sizes are recorded in source; effective kernel
queue capacities were not measured or counted as zero.

One real boundedness defect was caught during development: canceled later
completions released their job credits while leaving unbounded sequence-hole
metadata behind a delayed predecessor. The new regression failed with
`canceled order hole released admission credit too early` (held 1, expected 2).
Retaining the hole's lease until ordered retirement fixed it. Original logs are
preserved under `target/architecture-hardening/h14-canceled-hole-before/` and
hash-referenced by the final receipt. Earlier accepted-socket mode, tiny-send
loop timeout and fixed-delay pressure failures are also retained. The latter
failed because it did not actually produce WouldBlock; it was not relabeled as
successful pressure coverage.

## Observed copies, allocation requests and retention

The following payload counters are exact for each fast measured fixture run;
they include compressed work for the eventually canceled packet where applicable.

| Application work, bytes | Separate bodies | Shared bodies |
| --- | ---: | ---: |
| Explicit retained identity-rule payload copies | 206,144 | 103,072 |
| Retained generated header/transformed payload | 206,592 | 103,296 |
| Retained body payload peak | 370,208 | 206,368 |
| Compression input / compressed output | 411,652 / 127,370 | 411,652 / 127,370 |
| Initialized compression output capacity | 413,704 | 413,704 |
| Explicit passthrough copies | 1,084 | 1,084 |
| Generated framing / copied inner header | 100 / 52 | 100 / 52 |
| In-place encrypted / socket accepted / peer reassembled | 128,282 each | 128,282 each |
| Explicit receiver scratch-to-Vec copies | 128,282 | 128,282 |
| Retained output reservation peak | 411,444 | 411,444 |

Sharing provided 14 body hits and halved body serialization work. Compression
still ran per recipient, and encryption was connection-specific; neither was
silently shared across incompatible streams. No body/header concatenation buffer
was introduced at send. This is not zero-copy networking: backend, crypto,
allocator and OS/kernel internal copies remain unknown.

Median Rust allocator traffic below covers setup through teardown, a wider
interval than the wall timer. Counts are allocation calls and requested layout
bytes, not live memory, usable sizes or RSS. Metadata and worker context
allocations are included in their active stage; deallocations are separately
recorded by freeing stage in the receipt.

| Stage | Separate bodies: calls / requested bytes | Shared bodies: calls / requested bytes |
| --- | ---: | ---: |
| Other/setup | 36 / 16,474 | 38 / 16,618 |
| Retention | 59 / 417,152 | 31 / 209,440 |
| Compression | 34 / 1,181,160 | 35 / 1,188,360 |
| Ordering | 4 / 3,232 | 4 / 3,232 |
| Encryption/output queue | 4 / 1,152 | 4 / 1,152 |
| Send | 0 / 0 | 0 / 0 |
| Receive | 4 / 524,288 | 4 / 524,288 |
| Oracle capture, disabled during timing | 0 / 0 | 0 / 0 |

Zero send-stage Rust allocator calls do not imply zero kernel/library allocation.
Source snapshot payload allocations precede this meter. The body/output budgets
exclude metadata, source snapshots, contexts, diagnostic copies and kernel
queues. Every native/Java runtime subprocess also had a separate 512 MiB Job
commit limit, one-process limit and bounded output/time. The largest measured
native process peak was 3,497,984 bytes of process commit; this does not include
kernel socket queues or qualify production memory use.

## Timing and bytes decision

Observed on Windows with an AMD Ryzen 7 7800X3D (8 cores/16 logical processors).
Other agents yielded benchmark work during the campaign, but host background
noise, scheduling, allocator instrumentation and cache state remain uncontrolled.
Each row is five fresh processes after one warmup; times are milliseconds.

| Retention / receiver | Minimum | Median | Maximum |
| --- | ---: | ---: | ---: |
| Separate / immediate | 5.911 | 6.352 | 6.530 |
| Shared / immediate | 5.530 | 6.131 | 6.438 |
| Separate / 80-ms delay | 82.122 | 82.285 | 82.569 |
| Shared / 80-ms delay | 82.053 | 82.230 | 82.480 |

Fast ranges overlap substantially. The lower shared median is **not sufficient
evidence of an end-to-end throughput improvement**. The slow condition is
dominated by its deliberate 80-ms receiver delay. Fast summed worker compression
medians were 2.722/2.732 ms (separate/shared); order-promotion/encryption medians
were 2.800/2.709 ms. These timers have different scopes and cannot be summed into
the critical path. The tiny-send correctness run required 7,558 sends rather
than 27 and took 65.980 ms, but it was a single diagnostic run with plaintext
capture, not a comparative performance result.

`bytes` 1.12.1 is an MIT-licensed shared byte-buffer abstraction, with documented
sharing and slicing in its [primary crate documentation](https://docs.rs/bytes/1.12.1/bytes/).
For 100,000 clone/subrange accesses, median microseconds were:

| Payload bytes | Arc owner + borrowed range | Bytes owned slice |
| --- | ---: | ---: |
| 64 | 253.4 | 590.0 |
| 4,096 | 252.0 | 594.4 |
| 65,536 | 253.4 | 578.2 |

Both constructions preserved the input payload pointer. The Arc loops made zero
allocation calls; Bytes loops made one, consistent with ownership promotion on
first clone for this construction. Preparation was outside timing; Bytes creates
an independently owned slice while the Arc case borrows inside a live owner, so
this is a specified access pattern rather than identical API costs. There is no
evidence here to justify switching this pipeline to Bytes. It remains a useful
candidate where independently owned subviews or ecosystem integration are needed.

## Remaining work and provenance

Next production-facing step would be an adapter that obtains a validated H6
immutable view under capability/lifecycle checks and binds full state, light,
biome, registry, protocol, dimension and recipient identity to the body. A
connection owner must control encryption, cancellation cutoff and ordered
completion throughout teardown. Qualification then needs representative real
packet sizes/recipient distributions, configured network backpressure and
bounded pending-output policies, key lifecycle review, independent real-version
wire fixtures and an uninstrumented baseline alongside copy diagnostics.

The current fixture does not model world unload independently from connection
closure, mixed compression-threshold renegotiation barriers, authentication,
long-running fairness, multiple hubs sharing a global memory budget, cross-job
compression deduplication, worker panic recovery or allocation exhaustion.
Repeated isolated-hub creation can consume multiple independent budgets; these
limits are local, not process-wide admission policy. H6/JVM and live Forge remain
unqualified. No production gate can be enabled from this evidence.

Exact versions, upstream source commits and license notices are in
`tools/packet-buffer-pipeline-experiment/provenance.json` and `third-party/`.
New isolated dependencies are bytes 1.12.1 (MIT), aes 0.9.3 and cfb8 0.9.1
(MIT OR Apache-2.0), and socket2 0.6.4 (MIT OR Apache-2.0). The existing backend
remains flate2 1.1.10 (MIT OR Apache-2.0), zlib-rs 0.6.8 (Zlib). No third-party
implementation was copied into the local Rust/Java files; dependency archives
and notices retain provenance. No root manifest, production code, shared ledger
or program manifest was changed by this stage. No commit or push was performed.
