# Explicit diagnostic extractor scope (H2.2)

`LiveForgeCaptureSource` now consumes an immutable `LiveCaptureScope.RuntimeBinding`.
It does not infer a Clean Forge profile from a receiver's class ancestry. A call
without an explicit runtime binding fails closed. This is diagnostic extraction
hardening, not a V2 qualification certificate or native packet authority grant.

## Declared policy and runtime observations

The policy uses `LIVE_CAPTURE_SCOPE_V1`, operation
`chunk_packet_shadow_capture`: profile identity, exact provider/world/chunk/
section/container/nibble/packet/registry/generator classes, dimension, storage
family, registry epoch, state width, generator family, and skylight. The generic
qualification profile omits the certificate digest to avoid a digest cycle;
the eventual runtime adapter must attach its issued certificate digest. The
Java materialized scope has a separate certificate identity field.

The runtime binding resolves classes through an explicitly supplied trusted
loader and compares `Class` identity, including loader identity. It checks the
chunk's actual world identity, the provider's dimension and skylight fields,
and the exact chunk-provider/generator layout. Reflection specifies the declaring
owner and full field/method descriptor. Hidden fields, dispatch to overriding
methods, unsupported storage implementations and unresolved state IDs refuse
extraction. Supported vanilla palette/packed-storage layouts are explicit in
the `VANILLA_U16` adapter; a future storage family requires another qualified
adapter, not superclass acceptance.

The binding records the concrete registry object, its identity-map entries and
ID-to-state list, and derives the global state width from the observed registry
size. Capture reads compare registry contents at both ends, including same-size
changes. A changed object, epoch, alias or decoded state permanently revokes that
binding. ID resolution uses the captured identity map and exact decode identity,
avoiding a virtual registry ID getter that could invoke mod block metadata.

The registry epoch here is a declared binding generation, **not an instrumented
registry mutation counter**. Two observed comparisons cannot prove there was no
transient mutation-and-restoration or asynchronous writer. Writer closure,
registry lifecycle instrumentation and certificate-to-session binding remain
requirements for a later live qualification. Scope data alone provides none of
those proofs. Existing session/world/chunk/incarnation/owned-generation IDs are
included with the selected profile and scope in capture provenance.

## Explicit legacy compatibility policy

`LegacyCaptureScopes.cleanSurfaceFlat` recognizes only
`FORGE_2860_SERVER_TRANSFORMED_FML_INITIALIZED_OFFLINE_V1`. Its policy admits exact
`WorldServer`, exact `WorldProviderSurface`, dimension zero, skylight, and exact
`ChunkGeneratorFlat`. Existing Clean campaign configuration uses `level-type=FLAT`
in `tools/live-capture/run_live_shadow.py`. Unknown and Revelation plan IDs get no
inferred extractor. Its certificate marker explicitly says
`HISTORICAL_DIAGNOSTIC_NOT_V2_NOT_AUTHORIZING`.

Detached transformer tests use a separately named policy with their exact fake
world/provider types and an explicitly absent generator. Those fake provider
subclasses do not widen the historical live policy. The old positive oracle's
anonymous `ExtendedBlockStorage` had an empty body with no overrides; its positive
case now uses exact `ExtendedBlockStorage`, preserving real transformed writer
brackets. An anonymous section subclass is an explicit rejection control.

The campaign's dimension probe now calls the installed policy factory. A missing
binding reports an unclassified result, and a factory refusal without a reason
is not presented as independently proven dimension exclusion.

## Verification and diagnostic cost

`ExtractorScopeVerification` runs against fresh transformed Clean Forge classes
in a detached JVM, with 33 assertions: eight positive checks and 25 rejections
that assert the exact refusal reason. They include provider/section/container/
chunk subclasses, a same-name provider from another class loader, a different
world object, dimension, skylight, storage family, state width, registry class,
unregistered state, registry epoch and same-size content drift, sticky
revocation, generator family/class/absence, hidden fields, and wrong or
overridden method descriptors. An exact live-layout positive uses uninitialized
object shells solely to test field/class checks; it starts no world, generator
or server.

The detached full packet regression still seals a real transformed Java packet,
replays it through the existing native diagnostic bridge and compares it with a
plain Java rebuild. The final focused run is
`target/architecture-hardening/h2-extractor-final-06`: all groups pass, with mask
1 and identical 4,358-byte Java/native payloads. Its structured result records all
33 scope outcomes. The prior failed runs remain in `h2-extractor-01` through `03`:
the first two caught a wrong registry-class assumption; the third caught the
previously accepted anonymous positive fixture. Runs `04` and `05` were exploratory
passing runs before the final controls and result schema.

Registry comparison is intentionally conservative and costs a full alias-map and
decode-list traversal at each boundary. The focused final run recorded 9,569
entries compared and 839,000 ns across one successful scan plus two immediate
epoch/revocation refusals. This single cold diagnostic observation is not a
benchmark or performance claim. No live campaign was run for H2.2.

The complete existing Clean regression replay is recorded separately under
`target/architecture-hardening/h2-clean-regressions-final/receipt.json`, including
input/source hashes before and after, process returns, transformed runtime checks,
packet capture, post-hook verification and the five foundation suites.

`M4NativeStatePayload.tryEncode` remains production fail-closed and
`CaptureContract.productionAuthorityEligible()` remains false. The three pinned
foundation implementations and committed legacy profile JSON are unchanged.
