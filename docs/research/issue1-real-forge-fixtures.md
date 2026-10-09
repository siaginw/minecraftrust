# Issue #1: real Clean Forge offline fixture replay

The `forge` lane produces schema-v1 `REAL_CLEAN_FORGE_ORACLE_ACCEPTED` fixtures from
actual Minecraft 1.12.2 / Forge 14.23.5.2860 classes after offline FML
initialization. The oracle exclusively owns its constructed object graph.
It starts no server, captures no running world's chunks, and grants no native
production packet authority. `LIVE_FORGE` remains unsupported by the fixture
importer. See the [capture proof](issue1-clean-forge-capture-proof.md) and
[writer audit](issue1-clean-forge-writer-audit.md) for the ownership boundary.

## Independent comparison

`tools/testing/forge_fixture_replay.py` requires the exact 57 named oracle
outcomes. An accepted row must contain the owned `RCSNAP01` input, actual
`SPacketChunkData.func_148840_b` packet body, native payload, packed JNI V2
result and same-event selected-section tick refcounts. A rejected row must
contain its name, explicit rejection status, expected reason and
`REAL_CLEAN_FORGE_EXPECTED_REJECTION` kind. The 16 Medium additions also require
strict publication, scratch-progress, retry and source-witness proof metadata.
Missing, duplicated or unqualified rows cannot pass. Rejections materialize as
separate `.rejection.json` records under the capture-rejection schema, with no
owned transport, successful V2 result, Java packet or native payload. The batch
receipt retains the generic `REAL_CLEAN_FORGE_ORACLE` kind because it contains
both accepted and rejected outcomes.

The replay code independently parses the owned input, including every u32
state before narrowing, section order, non-air counts, mask subset, lifecycle
identity and capture guards. It enforces the real constructor relationship
`fullChunk == (requestedMask == 0xffff)`. It then reads the Java packet's
coordinates, full flag, mask, payload length and empty tile-entity list with
exact byte consumption. The V2 result must carry a valid success tag, exact
payload byte count and the same mask. Failure codes cannot stand in for a
successful payload.

The unchanged standalone `packet_decoder.py` decodes both Java and native
section payloads. All 4,096 states per selected section, block light, optional
sky light, biomes, masks and section counts must match the owned input. The
fixture's source palette and packed words come from the actual Java wire
payload. A freshly built native CLI replays that same owned input and must
reproduce the captured native result. Semantic equality is the required
comparison; incidental byte equality is reported separately.

## Runtime and registry identity

Successful materialization requires the full FML profile
`FORGE_2860_SERVER_TRANSFORMED_FML_INITIALIZED_OFFLINE_V1`. Original classpath
artifacts, Forge mapping/binpatch members, passive final-definition observer,
JDK components, DLL, harness sources/classes, transformed classes, ordered
mods/coremods and actual generated configuration files are identified by
SHA-256. Files are rehashed after replay. Missing or changed identity inputs
produce `INCOMPLETE`, never a compatibility pass.

The registry is an actual export, not a dense integer range. This qualified
runtime exports 1,681 iterator rows and 1,656 distinct decoded IDs, while its
logical state identity map contains 5,485 entries. The latter determines the
13-bit global palette width. Duplicate decoded IDs retain the runtime's
canonical decoded state, and membership checks reject holes. Both canonical
decoded-ID and full logical-state alias exports are hash-bound:

```text
decoded IDs: 449059d5a2b512040e982fb4763849d62e4ae586c73da38b191af0c25585cc00
composite:   126bd48d66ba7533fd9ca5b3ce3203b53427075b44ca86d66ba401373eaf2007
```

The composite is SHA-256 of UTF-8 `RUSTCRAFT_REGISTRY_V1\n`, the decoded-ID
digest, a newline, the logical-state digest and a final newline.

## Reproduction and local artifacts

Run from the repository with the qualified external artifacts configured as
described in the [runner documentation](../../tools/testing/README.md):

```powershell
tools/run-rustcraft-tests.ps1 forge
```

The ignored run directory contains `clean-forge/forge-runtime-result.json`,
raw oracle events, registry exports, generated configurations and final
transformed class observations. `real-forge-fixtures/` contains schema-v1
JSON fixtures, exact owned inputs, Java packet bodies, native replay outputs,
per-event comparison results, `artifact-map.json` and `results.json`. Logical
artifact names in each fixture resolve through that map to hash-bound local
files or explicitly named original jar members; they are not repository
relative binary paths. Proprietary binaries and generated fixtures are not
committed.

To replay a completed runtime receipt into a new directory:

```powershell
python -B tools/testing/forge_fixture_replay.py --runtime-receipt <run>/clean-forge/forge-runtime-result.json --output <new-directory> --native-replay target/debug/examples/snapshot_replay.exe
```

Existing output directories are never overwritten. Exit 0 means PASS,
1 means a semantic failure, and 2 means incomplete evidence. Omitting native
CLI replay yields `INCOMPLETE` even if the raw payload comparison succeeds.
Twenty-one public unit tests cover truncation, invalid scope/guards, mismatched
headers, missing sections, extended IDs, V2 corruption, light/biome changes,
registry holes, missing refcounts/rows, artifact tampering, exit mapping,
rejection publication/progress proofs and accepted-kind runtime provenance.
Their constructed inputs are test controls, not real Forge evidence.

## Verified result and limits

The accepted Ultra baseline run `20260924T225842Z-forge-c5e54c1b` passed all 41 expected outcomes:
21 accepted events and 20 explicit safe rejections. All accepted events
matched semantically, all 21 native CLI replays passed, and all 21 happened
to match Java bytes exactly. A separate read verified every persisted fixture's
schema, canonical hash and artifact references, then rehashed all 1,765
referenced artifacts.

The Medium expansion preserves those 21 accepted inputs and 20 rejection
cases, and adds 16 distinct rejection records. It does not inflate successful
fixture counts with retries or negative tests. See the
[publication contract](issue1-medium-publication-contract.md) for precise fault
semantics and the [Medium report](issue1-medium-hardening-report.md) for current
receipts and totals. Historical generic accepted labels remain schema-readable;
new oracle runs must emit the explicit accepted/rejected kinds.

These fixtures qualify the bounded clean-runtime, exclusive-owned-object
oracle. They do not prove arbitrary mod writers participate in a capture
protocol, qualify nonempty tile-entity callback behavior, establish live
snapshot coherency, or identify the historical `java=63/native=31` writer.
Issue #1 remains open and production native packet authority remains
fail-closed.
