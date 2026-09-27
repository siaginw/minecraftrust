# Generic qualification engine V2 (H2)

`tools/testing/qualification_engine.py` is the common engine. The active
`profile_verify.py` CLI delegates to it. No runtime path, JDK path, Forge build,
modpack name or expected observation is embedded in the implementation.

```text
python tools/testing/profile_verify.py --manifest MANIFEST.json --profile PROFILE.json --output-root RUN_DIRECTORY --requested OFFLINE_QUALIFIED
```

Library API:

```python
engine = QualificationEngine(manifest_path, profile_path, output_root)
certificate = engine.run(Maturity.OFFLINE_QUALIFIED)
# engine.output is a newly created, unguessable session subdirectory.
```

Exit codes: `0` PASS for the requested maturity, `1` FAIL, `2` INCOMPLETE.
Every certificate sets `production_authority: false`. Authority remains blocked
even if all future lower-stage requirements are supplied. Missing evidence is
INCOMPLETE; changed artifacts, bad bindings, malformed schemas, subprocess
failures and mismatched observations are FAIL. Duplicate JSON keys and nonfinite
JSON values reject. Expected data never substitutes for an observation.

## Manifest contract

The top-level object has exactly these required keys:

```json
{
  "schema": "RUSTCRAFT_RUNTIME_MANIFEST_V2",
  "runtime_root": "ABSOLUTE_RUNTIME_DIRECTORY",
  "inventories": {
    "artifacts": {"roots": ["server.jar", "libraries"], "files": {"server.jar": "SHA256", "libraries/example.jar": "SHA256"}},
    "mods": {"roots": ["mods"], "files": {}},
    "config": {"roots": ["config"], "files": {}}
  },
  "collector": {
    "command": ["ABSOLUTE_EXECUTABLE", "ABSOLUTE_HELPER"],
    "pins": {"ABSOLUTE_EXECUTABLE": "SHA256", "ABSOLUTE_HELPER": "SHA256"},
    "environment": {},
    "timeout_seconds": 120
  },
  "identity_tool": {
    "java": "ABSOLUTE_JAVA_EXECUTABLE",
    "java_sha256": "SHA256",
    "classpath": [
      {"path": "ABSOLUTE_V2_CLASSES_DIRECTORY", "files": {"com/rustcraft/coremod/CanonicalClassIdentityV2.class": "SHA256", "OTHER_REQUIRED_CLASSES": "SHA256"}},
      {"path": "ABSOLUTE_ASM_JAR", "files": {"": "SHA256"}}
    ],
    "timeout_seconds": 120
  }
}
```

`validators` is the only optional top-level key. Its optional `placement` entry
has the same command/pins/environment/timeout shape as `collector`. A missing
placement validator prevents OFFLINE qualification.

The examples abbreviate hash values and class inventories and are not runnable
profiles. Real digests are exactly 64 lowercase hexadecimal characters. Runtime
paths in inventories are portable relative `/` paths; traversal and escaping
symlinks reject. Every file under each declared root must be pinned, including
extra mod/config files. Missing files are INCOMPLETE; extras and hash drift FAIL.
Artifact roots must contain at least one file. Empty mod/config directories are
allowed and remain protected against subsequently added files. Roots cannot
overlap within a group. The inventory scope is explicit: files outside declared
roots are not claimed to be audited.

Classpath directory inventories are exact, including helper/inner classes. A
JAR uses the empty relative-name key. Collector and validator executable and
file arguments must be pinned; their remaining indirect inputs must also be
listed in pins or the runtime inventory by the adapter author. The engine
cannot infer arbitrary subprocess filesystem reads. Tool executable hashes do
not by themselves cover the entire JDK/interpreter installation: required
runtime libraries belong in the pinned runtime/tool inventories as well.

Commands use argument arrays, `shell=False`, an explicit fresh session working
directory, checked return codes, captured stdout/stderr, and bounded timeouts.
Any nonempty stderr is an explicit failure, including a zero-exit subprocess;
the diagnostic bytes remain in the process log.
Only OS runtime/temp environment values plus explicitly configured environment
entries are inherited. Do not put secrets in qualification manifests or logs.

## Profile contract

Required keys are `schema`, `id`, `identity_mode`, `runtime_identity`,
`transformer_chain`, `coremods`, `classes`, `writer_sites`, `negative_controls`,
`scope`, `production_authority`. The only optional key is `pre_classes`.

- `schema`: `RUSTCRAFT_QUALIFICATION_PROFILE_V2`.
- `id`: nonempty profile identifier.
- `identity_mode`: `RAW_SHA256` or `CANONICAL_ID_V2`. V1 rejects.
- `runtime_identity`: nonempty expected runtime facts; exact fresh equality.
- `transformer_chain`, `coremods`: ordered arrays; exact fresh equality.
- `classes`, `pre_classes`: maps from JVM internal class names to identities.
  RAW entries contain only `raw_sha256`. V2 entries contain both
  `semantic_sha256` and `declaration_order_sha256`. Missing pre-transform
  identities/evidence prevents OFFLINE qualification.
- `production_authority`: exactly `false`.

Each writer site contains:

```json
{
  "id": "site-id",
  "class": "example/Target",
  "method": "methodName",
  "descriptor": "()V",
  "required_calls": [
    {"opcode": 184, "owner": "example/Hooks", "name": "enter", "descriptor": "()V", "count": 1}
  ]
}
```

IDs are unique. Targets must belong to the pinned class inventory. Method
existence/descriptor and exact hook-call counts are checked against freshly
parsed Java V2 instruction facts **in RAW mode too**. This establishes call
presence only. It does not establish balanced guards, exception coverage, anchor
ordering or a permitted pre/post transformation.

Each required negative control is `{ "id": "...", "expected_outcome":
"REJECTED|CHANGED|STABLE" }`. Nonempty writer and control requirements are
mandatory; profiles cannot redefine or omit the engine's stage requirements.

## Exact extractor scope

Profile `scope` follows `LIVE_CAPTURE_SCOPE_V1`, with fields:

```text
schema = LIVE_CAPTURE_SCOPE_V1
operation = chunk_packet_shadow_capture
profile_id = the enclosing profile id
dimension = signed 32-bit integer
provider_class, world_class, chunk_class, section_class, container_class,
nibble_class, packet_class, registry_class, generator_class = exact class names
storage_family = VANILLA_U16
registry_epoch = positive signed 64-bit integer
state_width_bits = 9 through 16
generator_family = explicit nonempty family
skylight = boolean
```

There is no `certificate_id` in the expected scope: that would introduce a
circular hash dependency. The Java adapter can attach the completed certificate
digest afterward. An offline certificate binds the **declared** scope; it does
not prove that live registry objects, IDs, dimensions or writers satisfy it.
Those are per-event/runtime checks in the extractor/live adapter. This version
admits only this extractor-scope schema; new operations require an explicit
schema validator rather than arbitrary nonempty metadata.

## Fresh acquisition protocol

The engine creates a new UUID directory and random challenge. It writes a
`RUSTCRAFT_QUALIFICATION_REQUEST_V2` request containing session/challenge,
profile/manifest/tool/inventory hashes, required classes, runtime root and scope.
It appends `--request ABSOLUTE_REQUEST --out ABSOLUTE_OBSERVATION` to the pinned
collector command. The output path did not exist before this session.

The collector must print exactly one JSON line:

```json
{"schema":"RUSTCRAFT_COLLECTOR_ACK_V2","session":"SESSION","challenge":"CHALLENGE","output_sha256":"SHA256"}
```

The observation object requires:

```text
schema = RUSTCRAFT_FRESH_OBSERVATION_V2
session, challenge = supplied fresh values
request_sha256 = hash of the actual request file
capture_kind = OFFLINE_TRANSFORM_CAPTURE
runtime_identity, transformer_chain, coremods = actual observed values
classes = [{name, file, raw_sha256}, ...]
```

Optional `pre_classes` uses the same rows. All class files must be under the new
session directory; no unreported `.class` file is allowed. Optional
`writer_matrix`, `negative_controls`, `live` are handled below. No embedded
historical receipt field is accepted by the schema.

The engine independently hashes every class and invokes the explicitly pinned
Java V2 tool for both its receipt and complete dump. It checks exact row counts,
class names, raw hashes, the dump's semantic hash and the applicable composite
expected identity. It keeps parsed method facts for independent descriptor/call
checks. The runtime, tools, definitions and observed witness/class files are
rechecked before issuing a certificate. End-of-run drift transitively invalidates
acquisition and all dependent evidence. Process logs are retained and hash-bound
in certificate context.

**Trust boundary:** a challenge establishes fresh execution/binding of a reviewed
pinned collector; it cannot prove that arbitrary collector code actually ran a
transformer or loaded a JVM. Wrapping old class dumps in a new nonce is not real
runtime requalification. The Clean Forge/Revelation adapters must perform fresh
capture from the pinned runtime and expose their actual provenance. This engine
does not claim cryptographic attestation or infer closed-world writer coverage.

## Writer, placement and negative-control witnesses

`writer_matrix` has exactly `scope: OFFLINE_HOOK_CALL_PRESENCE` and `sites`.
Each site has `id`, `class`, `method`, `descriptor`, `raw_sha256`, `status: PASS`.
The observed site set, byte binding and actual parsed calls are independently
checked. A missing matrix is INCOMPLETE.

The separate placement command receives a fresh
`RUSTCRAFT_PLACEMENT_REQUEST_V2` with acquisition/request/observation bindings,
actual pre/post class paths, identities/method facts and required sites. It
prints a `RUSTCRAFT_VALIDATOR_ACK_V2` envelope with session, challenge and output
hash, and writes:

```text
schema = RUSTCRAFT_PLACEMENT_WITNESS_V2
session, challenge, request_sha256, observation_sha256
covered_sites = exact required IDs
checks = {
  anchor_order: {status, measurements},
  exception_paths: {status, measurements},
  undeclared_edits: {status, measurements},
  pre_post_relation: {status, measurements}
}
```

Each measurement object must be nonempty. Any failing check fails; missing or
incomplete coverage prevents qualification. The validator is itself pinned and
reviewed: the engine cannot turn arbitrary textual claims about semantics into
proof. Runtime adapters need substantive validators for their actual injection
plans. The synthetic test validator checks a deliberately narrow known fixture
relation; it is not a validator for arbitrary Forge writers.

`negative_controls` is an array of `{id, actual_outcome, evidence_file,
evidence_sha256}`. Each freshly written witness has exactly `schema:
RUSTCRAFT_NEGATIVE_CONTROL_V2`, `session`, `challenge`, `request_sha256`, `id`,
`outcome`, `measurements`. Required IDs/outcomes, freshness and actual evidence
bytes are checked; missing controls are INCOMPLETE. Concrete test execution
belongs to the pinned collector/validator, not the expected profile.

## Maturity and remaining integration gates

The code-owned graph requires definitions, exact inventories, tools, fresh
acquisition, runtime/class identity and a clean final drift check for OBSERVED.
OFFLINE additionally requires fresh pre-transform identity, writer call checks,
separate placement validation and negative controls.

LIVE requires a separate live writer/lifecycle validator. This initial engine
explicitly **does not implement that backend**. It rejects an offline collector's
self-reported `live` claim and keeps LIVE incomplete otherwise. Shadow,
performance and authority likewise remain unfulfilled. No live maturity follows
from transformed hashes or the synthetic fixture suite. H23 must integrate fresh
Clean Forge/Revelation adapters and genuine live validation before claiming their
requalification. H24 remains gated by that later evidence.

## Tests

`tools.testing.test_qualification_engine.EngineFixture` is reusable by CLI tests.
It creates pinned synthetic runtime inventories, collector/placement processes
and two distinct profile families. It exercises the actual Java V2 parser, in
both RAW and V2 modes. It never starts Minecraft or a game JVM.

Configure explicit `RUSTCRAFT_TEST_JAVA`, `RUSTCRAFT_TEST_V2_CLASSES`,
`RUSTCRAFT_TEST_ASM`, `RUSTCRAFT_TEST_CLASS_FIXTURE` paths, then run:

```text
python -B -m unittest tools.testing.test_qualification_engine tools.testing.test_profile_verify tools.testing.test_qualification_certificate -v
```

Without those inputs the integration tests explicitly skip. A skipped suite is
not evidence of actual Java integration. Test failures and earlier exploratory
runs must not be reported as successful campaigns.
