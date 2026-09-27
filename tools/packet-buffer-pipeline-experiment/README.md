# H14 bounded packet/buffer experiment

This isolated workspace implements a synthetic four-connection packet pipeline.
It has no production caller, Minecraft gameplay implementation, authentication,
JNI integration, or authority. Root dependencies, MCK6 and compression defaults
remain unchanged. Run `python tools/packet-buffer-pipeline-experiment/run.py`
for correctness; add `--measure` only during an agreed CPU measurement window.
The runner refuses any checkout except the isolated hardening checkout.

## Pipeline and bounds

Immutable `Arc<Body>` objects are reused only for an exact snapshot identity,
state generation, protocol and recipient rule. Two of four recipients share each
rule; the other rule changes payload bytes and cannot reuse that body. The
snapshot is synthetic and trusted: this is not H6/JVM coherent capture. The
cache retains up to 128 bodies and 512 KiB of payload, including external readers
after cache clear. It returns backpressure rather than evicting readers.

Two real workers use the existing compression crate at its unchanged vanilla
level 6, flate2 1.1.10 with zlib-rs 0.6.8. Each independently framed packet resets
the compressor. A reservation covers worst-case compressed output plus framing
until final send, cancellation retirement or terminal discard. At most 16 such
reservations and 1 MiB exist across queued work, running work, completed results,
order restoration, encrypted output and canceled order holes. Even a canceled
completion behind a delayed predecessor keeps its reservation until that hole
retires. The completion channel is logically bounded by these retained credits.
Closing a connection retains credits for running workers through completion.
A separate admission check caps controller tracking entries even if a caller
abandons a detached completion handle. Such misuse remains fail-closed until
teardown; it cannot create a gap skip or unbounded ticket tracking.

Connection IDs contain a private hub origin, slot and checked generation;
tickets additionally contain an assigned sequence and nonce. Foreign completions
are returned intact and cannot remove local work. Reconnect cannot accept old
generation results. Compression errors close the connection. Submission failure
does not consume a sequence. Cancellation is accepted only while a ticket is in
the controller's running/completed-but-unaccepted map; it is not rollback after
encryption or sending.

The serialized controller restores order before advancing a continuous
AES-128-CFB8 state. It encrypts header and payload separately in place, then
passes two `IoSlice`s to `TcpStream::write_vectored`. A cursor advances only by
the accepted prefix. WouldBlock preserves both cursor and cipher state; fatal
errors close the connection. No unsent bytes are replayed on a new session.
Partial sending cannot be made wire-atomic: a peer can receive a prefix before a
fatal error, so terminal closure is the failure boundary.

Private listeners bind only `127.0.0.1:0`. The ordinary four-connection receiver
has an 8-second overall deadline and 5-second read timeout; application sends
have a 6-second deadline. Send/receive buffer sizes are requested as 1024/4096
bytes; these are requests, not measured kernel capacity. Windows accepted
sockets explicitly return to blocking mode before read timeouts are used.
Per-connection half-close occurs when its queue drains. The experiment waits for
all peer EOFs and exact reassembly; kernel acceptance alone is not completion.
Separate controls force real socket pressure and a reset after a received prefix.
An offered 17-byte send quantum exercises cursor boundaries but is not evidence
that the OS itself performed short writes. OS short returns and WouldBlock are
reported separately from scripted writer controls.

## Independent reference and faults

Seven body lengths/threshold cases cover disabled compression, 255/256/257-byte
threshold boundaries, compressible and arithmetic-pattern data, and 64-KiB
bodies. The body starts with synthetic packet ID 0x7e, recipient rule, big-endian
seed and state generation, two reserved zero bytes, then fixture data. This is
not a qualified Minecraft packet ID or real gameplay payload. Fixed public keys
and IVs are fixture material only. One of 28 submissions is canceled, leaving
27 delivered packets on four connections.

Java 8 independently constructs bodies and framing, uses JDK Deflater/Inflater,
checks exact decompressed bodies and framing constraints, and runs JCE
AES/CFB8/NoPadding across the continuous connection stream. It both independently
encrypts validated native plaintext frames and decrypts actual received wire in
17-byte update chunks. Different valid deflate encodings are explicitly recorded
with first offsets, not classified as a failure or suppressed. Cipher and zlib
tamper controls must fail with first-divergence markers. Each measured stream
must match the hashes of these independently qualified correctness streams.

Rust controls cover recipient/generation separation, live-reader body budget,
forced out-of-order real workers, foreign identities, delayed cancellation,
canceled-hole credit retention, reconnect, scripted short/WouldBlock/fatal sends,
real reset and real paused-reader pressure, compression failure and admission
failure. Worker-fault gates time out; runtime processes additionally run inside
the existing H9 Windows Job helper (512 MiB commit, one process, bounded output,
20–30-second timeout). Build/test subprocesses have timeouts and file-backed
logs but are not Job-memory-limited. Allocator failure, worker panic recovery,
arbitrary sequence exhaustion and adversarial authenticated Minecraft traffic
are not qualified by this prototype.

## Measurement interpretation

The wall timer begins after snapshots, listeners, connections, worker creation
and the receiver-ready barrier setup; it includes body generation/admission,
compression, order restoration, encryption, socket transfer and peer EOF joins.
The scripted slow receiver sleeps 80 ms inside that interval. Timing samples
disable plaintext oracle capture; correctness runs enable it. Evidence-file
writes occur afterward. Five measured fresh processes per sharing/slow condition
follow one discarded warmup; condition order reverses on alternating repetitions.
These instrumented shared-host samples are not a production throughput estimate.

Rust allocator accounting begins before hub/socket setup and ends after worker
and hub teardown, so its scope is deliberately wider than the wall timer. It
counts calls and requested layout sizes, not allocator usable size, RSS or peak
live ownership. Deallocation is charged to the stage that frees, not the stage
that allocated. Reallocation is recorded as old-free/new-allocation; any internal
copy it performs remains unknown. TLS stages are other, retention, compression,
ordering, encryption, send, receive and oracle. Snapshot source allocations and
post-meter evidence writes are outside this allocation interval. Callback atomics
add overhead; no low-overhead claim is made.

| Boundary | Explicit accounting | Unknown / separate work |
| --- | --- | --- |
| Retention | Identity-rule payload copies; transformed/header bytes generated; Arc hits | Arc/Vec/metadata movement is not a payload memcpy claim |
| Compression | Input/output bytes, zero-initialized output capacity, passthrough copies | Backend internal copies and workspace writes |
| Framing | Generated header bytes and copied inner VarInt header bytes | Compiler/register/stack movement |
| Ordering | Retained credits and allocator requests; payload ownership moves | Metadata costs are not counted as payload bytes |
| Encryption | Bytes transformed in place and elapsed time | AES/CFB8 internal moves and platform dispatch |
| Send | Bytes accepted, calls, short returns and WouldBlock; no concat buffer | Standard-library, socket-provider and kernel copies |
| Receive | Scratch-to-Vec bytes copied, reads and received wire size | Kernel-to-user copy implementation |

Payload budgets exclude metadata, source snapshots, crypto/compression contexts,
diagnostic copies and kernel queues. The overall Job limit is a separate bound,
not evidence that those exclusions consume zero memory. Summed worker compression
time is not wall critical-path time. Encryption timer includes order-promotion
loop work and skipped entries. Full-wire success is validated independently.

The bytes 1.12.1 comparison measures 100,000 clone/subrange accesses against
`Arc<Vec<u8>>` at 64/4096/65536 bytes. Ownership transfer preserves the input
payload pointer in both preparations. `Bytes::slice` creates an owned view;
the Arc case borrows a subrange while its cloned owner remains alive. This is a
specific access-pattern comparison, not identical APIs, a full pipeline port or
proof of zero-copy networking. Preparation is excluded from clone-loop timing.
The pipeline remains on standard-library Arc unless stronger evidence warrants
the additional abstraction.

## Provenance and evidence

`provenance.json` pins dependency versions, upstream commits and licenses;
`third-party/` retains package notices. New experiment dependencies are isolated
and do not enter the root lockfile. The runner hashes source/guards/local
dependency source, real tool executables, Java runtime jars/DLL, registry archive
checksums and every archived dependency file before and after. It verifies root
compression archive pins match and records dependency features, raw subprocess
logs, Java class hashes, binary hashes and wire hashes. This does not make an
untrusted machine or toolchain trustworthy. Historical failed development logs
are preserved and hash-referenced; final success never rewrites them. One
pre-existing compression warning is captured with its accepted-HEAD source.

The report at `docs/research/packet-buffer-pipeline.md` supplies observed results
and the bounded adoption decision. Production integration would still need
capability-bound capture, full version/recipient identity, encryption/key
lifecycle review, configured network resource bounds and real representative
workload qualification.
