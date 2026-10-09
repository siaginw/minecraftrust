# Operation-specific capabilities: non-authorizing H4 model

`crates/native-capability` is a dependency-free, deterministic Rust model. It
does not call JNI, import certificates, install hooks, mutate Minecraft state,
change a production gate, or grant runtime authority. Its modeled transitions
are deliberately named `model_*`, and its strongest state is `AuthorityModel`.
Both `production_authority_enabled()` and every model commit's
`production_authority()` are always false.

Status: **H4 FOUNDATION**. The crate is a member of the Rust workspace, but has
no runtime execution integration. Workspace membership is not H23 live binding
or authority qualification.

## Identity and independent operation states

An immutable installed capability has the following identity. Its generation and
bound dependency handles are returned alongside the complete declared identity.

| Part | Representation and validation |
| --- | --- |
| Operation | Nonempty operation identifier; observation must match exactly |
| Ownership | Java, owned snapshot, or native exclusive **model** |
| Qualification | Profile ID/digest, certificate digest, writer and lifecycle evidence digests |
| Runtime context | Opaque session and world tokens plus incarnation epoch |
| World/storage scope | Exact dimension, provider identity, storage-family identifier |
| Registry/version | Concrete registry token, content digest, state width and epoch; version-adapter token, implementation digest and epoch |
| Receiver | Nonempty exact-type set and optional exact object token |
| State | Qualified state-set digest, revision epoch, explicit tile-entity restriction |
| Dependencies | Capability generation, minimum evidence state and explicit Java/native ownership requirement |
| Revocation | Certificate, writer/lifecycle evidence, session/incarnation, registry/version, resource, dependency and mutable-owner conditions |

An exact type contains an opaque loader token, actual runtime class token,
CANONICAL_ID_V2 semantic hash and declaration-order hash. A class name is
insufficient. The token constructors are explicitly **model input** constructors,
not evidence verifiers. The model checks consistency of supplied identities; it
does not establish that they describe actual objects.

States belong to each operation/context, not a modpack:

| Example operation/context | Modeled state |
| --- | --- |
| Framing / allocation F | AuthorityModel |
| Compression / allocation C | AuthorityModel |
| Chunk storage family A / dimension 0 | ShadowValidated |
| Chunk storage family B | Java |
| Tile-entity chunks | Java |
| Twilight Forest provider/dimension | Java |
| Lighting | Java |

The mixed-state test installs these simultaneously. Separate framing/compression
allocations can have separate model owners. Changing a label does not partition
the same allocation. `Eligible` does not pass a shadow-use check. `Java` produces
an explicit Java fallback. AuthorityModel still confers no real authority.

## Graph publication, generations and revocation

`install_batch` validates the whole batch before publication. Missing or duplicate
dependencies, insufficient dependency state, cross-incarnation dependencies,
self-cycles, longer cycles, missing mandatory revocation conditions and malformed
scope reject without publishing a partial graph. Forward references within one
batch are supported. Dependencies bind the selected capability generation, not
only its reusable string key.

Revocation invalidates every transitive dependent and preserves independent
branches. Reinstalling a revoked key increments its generation and does not
resurrect descendants. They require their own explicit replacement bound to the
new generation. Certificate/evidence/registry/version invalidations leave
persistent tombstones, including invalidation received before installation.
Reinstalling the same revoked evidence cannot clear those tombstones. Lifecycle
end prevents adding new allocations to the ended session/incarnation.

`prepare` returns a non-cloneable validation ticket. `commit` consumes it and
rechecks the complete dependency closure, exact observation and ownership state
and generation for every resource in that closure. Revocation between prepare
and commit, a changed observation, Java release-and-restore, and re-registering a
capability under the same key all reject. Quarantine changes are checked even
when the old lease epoch is retained for quiescence acknowledgement.

The model is serialized through `&mut ModelRegistry`; it does not supply a Java
memory barrier, lock protocol, current registry observation or concurrent object
lifetime proof. A real adapter must make prepare/recheck/publication one coherent
operation or otherwise establish the required runtime synchronization.

## One mutable owner per actual allocation

Ownership keys come from registry-issued resource handles. Registration binds a
canonical root-allocation token and session/world/incarnation, and deduplicates
the root allocation independently of operation, profile, provider or storage
labels. A second label cannot obtain an independent owner for the same root.
The same allocation cannot be rebound to another incarnation or session while
its old resource is live or still has a mutable owner.

Each new resource starts with Java as its sole modeled mutable owner. Native
claiming requires an explicit modeled Java release and an unowned allocation.
Another native claimant or Java restoration while native owns the allocation
refuses. A dependency may explicitly require Java ownership or the dependent
capability's native ownership; an `Any` ownership dependency conveys only
qualification dependency, never ownership permission.

Releasing Java revokes claims that require its retained ownership and their
descendants. Releasing a native lease ends that authority-model generation and
its dependents. Revoking a native holder quarantines the resource; it does not
automatically hand it back to Java. The old generation must explicitly
acknowledge modeled quiescence before the resource becomes unowned. Its late
completion cannot revoke a freshly installed generation with the same key.
Dropping or leaking a lease leaves ownership held rather than silently restoring
Java. Counter exhaustion returns an error without wrapping or partially
publishing/releasing state.

The canonical-root token is an explicit trusted-adapter obligation. The model
cannot infer that two caller-invented tokens denote overlapping physical memory.
The future issuer must canonicalize Java section/container/palette/array aliases
to the same root or prove an actual disjoint partition. A string selected by a
caller is never acceptable as partition evidence. H4 supplies no live issuer and
therefore makes no live alias-safety or handoff claim. H6 must implement actual
handoff/quiescence/lifetime rules before these checks can control memory owners.

## Exact H2 certificate-to-live binding gap

The current H2 engine issues only offline, non-authorizing certificates. Its
`observation_session` is an evidence-collection run, not an active gameplay
session. Its declared `registry_epoch` is not an instrumented mutation counter.
No H2 artifact currently proves a current Java loader, object incarnation, writer
closure, ownership handoff or lifecycle quiescence. This crate deliberately has
no JSON/digest-to-grant deserializer.

A future adapter must perform these steps before issuing any runtime handle:

1. Verify the full H2 certificate artifact with its canonical digest algorithm,
   exact schema, dependency graph and concrete observed artifacts. Verify the
   operation-specific requested maturity and all current revocation information;
   a bare certificate digest is insufficient.
2. Bind its profile/scope to a fresh runtime session. Resolve exact live `Class`
   objects and class loaders; bind both V2 hashes. Issue opaque tokens from actual
   object identity and preserve lifecycle tombstones. Do not reuse class names or
   evidence-collection IDs as live identities.
3. Bind actual world/incarnation, dimension/provider, storage implementation,
   concrete registry identity and instrumented registry/version epochs. Qualify
   receiver/state restrictions and the canonical alias-to-allocation mapping.
4. Attach current writer and lifecycle closure evidence and generation-bound
   dependency capabilities. Feed every relevant registry, class, world, object,
   certificate and ownership invalidation into the graph before later use.
5. Implement real Java/native quiescence and generation-fenced publication.
   Only a separately reviewed authority gate could authorize execution after
   all these obligations are met; H4 cannot change that gate.

The profile omits its own certificate digest to avoid recursive hashing; the
verified issued digest is attached only to the materialized identity. There is
no current runtime integration, no broader Clean/Revelation qualification and no
native packet authority.

## Verification and scope limits

Run the workspace member's focused checks:

```text
cargo test --locked --offline -p native-capability
cargo clippy --locked --offline -p native-capability --all-targets -- -D warnings
cargo fmt -p native-capability -- --check
```

`crates/native-capability/verify.py --cargo PATH --workspace-regressions` creates
a fresh source/tool-bound receipt under this checkout's `target/`, with focused
tests, strict crate lint/format checks, all workspace library tests and a release
FFI build. The receipt identifies H4 FOUNDATION and `runtime_integration: false`.
Strict lint/format claims cover this crate; pre-existing debt elsewhere is not
silently recategorized as passing. Original standalone evidence remains locally
in `target/architecture-hardening/h4-capabilities-final-01/`.

The controls cover mixed operation states; all context/type/epoch restrictions;
atomic malformed graphs and cycles; independent branches; persistent evidence
revocation; generation ABA; late revocation; changed observations; same-root
ownership conflicts; Java/native dependency ownership; lease quarantine,
leak/drop and reincarnation; foreign registry handles; and counter exhaustion.
They are deterministic model tests, not live Minecraft, Loom exploration,
performance measurements or a proof that all external invalidations are delivered.
The graph retains tombstones conservatively and uses straightforward graph scans;
bounded-memory runtime retirement and optimized indexing are separate work.
