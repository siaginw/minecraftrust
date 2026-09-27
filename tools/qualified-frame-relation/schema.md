# Qualified frame relation wire contract V1

All JSON rejects duplicate keys, nonfinite numbers, unknown keys and wrong scalar types. Hashes are lowercase SHA-256, sessions 32 lowercase hex, challenges 64 lowercase hex. Arrays are ordered; identities are nonempty opaque strings unique within the witness. No identifier is inferred from a name or from `identityHashCode` alone. No production authority is conferred.

`request.json` is generated from fresh bounded immutable copies parsed by V2 plus the frozen independent Rust projection:

```text
schema: QUALIFIED_FRAME_REQUEST_V1
session, challenge
bindings: {profile_sha256, manifest_sha256, observation_sha256, plan_sha256,
           validator_sources_sha256}
classes: [{name, pre_sha256, post_sha256}] // exact sorted target inventory
types: [internal_name_or_array_descriptor] // exact sorted union, requested in BOTH phases
assignability: [{source, target}] // exact sorted unique directed questions, BOTH phases
```

The type union includes every object/array frame type in either buffer, all target classes, and `java/lang/Throwable` when handlers exist. References introduced by changed frames are included even when HotSpot never requests them. The finite relation requests both directions for each differing reference/reference pair. Array descriptors retain rank and primitive/reference component identity. Hierarchy closure is supplied as actual object graphs, not a name allowlist.

`witness.json` is supplied by an externally pinned, fresh actual collector. This tool does not implement a game collector:

```text
schema: QUALIFIED_FRAME_WITNESS_V1
session, challenge, request_sha256
collector_sources_sha256, vm_inventory_sha256
phases: {
  pre: {
    loaders: [{id, parent, implementation_sha256, configuration_sha256, policy_sha256}],
    classes: [{id, name, kind, is_interface, loader, raw_sha256, artifact_sha256,
               super, interfaces, component}],
    resolutions: [{type, class_id, error}],
    verification: [{name, class_id, raw_sha256, status, trigger,
                    verify_local, verify_remote, initialized}],
    assignability: [{source, target, value}]
  },
  post: {same fields}
}
loader_pairs: [{pre, post}]
scope: {
  model: OFFLINE_BYTECODE_EXECUTION_V1,
  observers: {status, policy_sha256, inventory_sha256, evidence_sha256},
  loader_effects: {status, policy_sha256, inventory_sha256, evidence_sha256}
}
```

Class `kind` is `class`, `array` or `primitive`. `name` is an internal class name, array descriptor or primitive descriptor. Class nodes have raw class bytes and supplying artifact hashes; arrays/primitives have null raw/artifact hashes. Every node has an actual defining `loader` ID. Bootstrap is represented by one explicit loader whose parent is null and whose implementation/configuration/policy hashes bind the pinned VM/bootstrap configuration. Arrays identify their immediate actual component Class; primitives have no super/interfaces/component. Classes have no component; arrays have Object super and exact Cloneable/Serializable interfaces. `super`/`interfaces`/`component` reference Class node IDs in the same phase, not strings naming types. All reachable hierarchy edges must be present. Resolution errors use `class_id:null` plus a nonempty error string and cause INCOMPLETE; successful resolutions use `error:null`.

Verification status is `VERIFIED`, `REJECTED` or `UNAVAILABLE`; trigger is `PINNED_HOTSPOT_GET_DECLARED_METHODS_V1` for this implementation. Effective flags must both be true. `initialized:false` is an observed collector condition, not inferred from Class.forName(false). Only VERIFIED can support PASS. REJECTED causes FAIL, UNAVAILABLE is INCOMPLETE. Queries bind requested source/target type strings to their resolved Class objects in that phase; `value` is actual `targetClass.isAssignableFrom(sourceClass)` or null when unavailable.

Loader pairs provide only a proposed mapping. The validator checks total bijection, parent edges and implementation/configuration/policy equality. It derives Class correspondence by the mapped defining loader and exact name/kind, then checks super/interfaces/component edges. Raw class bytes must match across phases except exact target classes already proven under the insertion/frame relation. Artifact identities always match. The collector must use collision-free object identity registries and preserve raw definition bytes; an unverified hash claimed by another source is insufficient provenance.

Scope statuses are `CLOSED` or `INCOMPLETE`. CLOSED is accepted only with the matching independently accepted policy/evidence pins in the external trust envelope below. Observer policy must account for downstream transformers, agents, class-byte inspection and frame/max/pool observability. Loader policy must account for explicit resolution's extra definitions and custom-loader effects. Missing policy closure stays INCOMPLETE. This validator checks provenance binding; it does not invent either scope proof from JVM verification.

`trust.json` is an **external trust input** from the acquisition/qualification engine, never emitted by this tool from witness claims:

```text
schema: QUALIFIED_FRAME_TRUST_V1
session, challenge, request_sha256, witness_sha256
collector_receipt_sha256, collector_sources_sha256, vm_inventory_sha256
observer_policy: {policy_sha256, inventory_sha256, evidence_sha256} | null
loader_effect_policy: {policy_sha256, inventory_sha256, evidence_sha256} | null
```

`collector-receipt.json` binds the actual checked subprocess execution:

```text
schema: QUALIFIED_FRAME_COLLECTOR_RECEIPT_V1
session, challenge, request_sha256, witness_sha256
collector_sources_sha256, vm_inventory_sha256
exit_code: 0
stderr_sha256: SHA256(empty)
fresh_processes: true
inputs_before_sha256, inputs_after_sha256 // must equal
```

The trust envelope pins the exact receipt bytes, witness bytes, collector sources and VM inventory; the receipt repeats request/session/byte bindings. A witness without this externally established trust boundary cannot PASS. Editing both a witness and its purported trust envelope is not authentication; the root engine must establish those pins from actual fresh acquisition. Synthetic contract tests are explicitly labelled schema tests and cannot promote a real corpus.

CLI is two-stage: `prepare.py` takes exact pre/post paths and plan, parses/copied inputs, writes request and structural report; `evaluate.py` recomputes the saved input/source bindings and validates supplied witness/trust/receipt, or returns INCOMPLETE if absent. The exact flags and output filenames are documented with the implementation. Root owns request→actual game acquisition. A stale session, wrong bytes, foreign identity, malformed graph, failed verification or contradictory assignability causes FAIL. Missing evidence or a well-formed but unsupported finite frame shape causes INCOMPLETE.
