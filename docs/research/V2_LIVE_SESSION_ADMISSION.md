# V2 Live Session Admission — offline vs real FML transformation provenance

Status: ACTIVE (Phase-D Revelation blocker resolution, research record)
Baseline head at acceptance: `b8abc59`
Phase-D contract: `docs/research/V2_PHASE_D_EVENT_CONTRACT.md`

## 1. The question

Can a REAL Forge/FML server launch satisfy the SAME V2 session-bound
admission the offline qualification harness satisfies — issuing its own
fresh evidence, without downgrade, stamping, or certificate reuse?

## 2. Does real FML naturally produce MixinMerged#sessionId? YES.

The session-bound identity requires at least one annotation with descriptor
`Lorg/spongepowered/asm/mixin/transformer/meta/MixinMerged;` carrying a
`sessionId` element whose value is a canonical UUID
(`CanonicalClassIdentityV2.collectOne`). Stock SpongePowered Mixin writes
`mixin` and `priority` only — but the Revelation runtime's Phosphor jar
shades its own Mixin whose `MixinTargetContext.addMergedMethod` writes a
FIVE-element annotation array including `"sessionId"` taken from
`TargetClassContext.getSessionId()` (verified by disassembling
`phosphor-forge-mc1.12.2-0.2.7-universal.jar`).

The value is launch-scoped, not static: the two independent discovery
launches (`target/architecture-hardening/qualify-rev-final/discovery-{1,2}`)
carry DIFFERENT session UUIDs (`c41b7535-…` vs `0b1407bc-…`) over the same
six classes (`World`, `Chunk`, `ChunkProviderServer`, `AnvilChunkLoader`,
`ExtendedBlockStorage`, `SPacketChunkData` — all
`me.jellysquid.mods.phosphor.mixins.lighting.common.*` merges). A real
Revelation FML launch therefore NATURALLY produces fresh session-bound
provenance. Answer A holds; nothing needs to create it.

## 3. Why the real launch previously failed: transformer ORDER, not missing provenance

The offline oracle states its own contract in code: the three RustCraft
transformers are registered "AFTER the complete FML chain (they must consume
exactly the profile-pinned post-FML pre-hook definitions)" — writers LAST,
entry observer FIRST (`RevQualifyRuntime` registration block).

The failed real launch (`target/phase-d-smoke/revelation-1`) measured the
real topology instead: our coremod wrapper registered its transformers
BEFORE Phosphor's `MixinTweaker` transformer entered the chain, so the
writers' input for `net.minecraft.world.World` carried no MixinMerged
provenance, `verifySessionBoundIdentity` failed closed, and Mixin itself
then reported `@Mixin target … was not found` for the class our exception
had already broken. Launchwrapper registers all tweaks before any game
class loads, so the ORDER at first target-class load is the whole
difference.

This is not missing evidence and not a weaker reality: it is a different
transformer registration order, which is a real, controllable structural
property of our OWN transformers. The correction is to make the real launch
register the writers LAST — the same qualified topology, by reordering real
transformers, never by touching bytes. The evidence direction stays
REALITY → CERTIFICATE.

## 4. Bounded offline vs real topology comparison

| fact | offline qualification launch | real FML launch (revelation-1) |
|---|---|---|
| launch target | `RevQualifyRuntime` (no server loop) | `ServerLaunchWrapper` → real server |
| loader | LaunchClassLoader (FML chain complete) | same |
| Mixin | Phosphor's shaded Mixin, real sessionId | same (verified in discovery bytes) |
| writer position | LAST, by explicit registration | BEFORE Mixin (measured failure) |
| entry observer | FRONT, explicit install | absent (no one installed it) |
| session env | bound by oracle before writers | properties present, lazily bound |
| evidence flush | oracle `main()` at end | absent (no flush ran) |

The remaining deltas are all implementation gaps on our side, each with an
existing proven component: writer tail placement, entry-observer install,
session-environment binding, and the end-of-launch evidence flush.

## 5. ObservationAgent role

`tools/forge-capture/src/com/rustcraft/offline/agent/ObservationAgent.java`
is a passive javaagent: `premain` registers a ClassFileTransformer that
hashes every definition it observes and records the defining loader; it
"never changes bytes, retransforms, or redefines a class." It observes
FINAL definitions only — exactly what `LoaderDefinitionWitness` needs as
the INDEPENDENT witness (§13: the chain must not self-certify). It does not
participate in transformation, creates no provenance, and carries no
dependency on the offline tweaker. It is suitable for the real launch
unchanged: attach the same observer jar via `-javaagent:`.

## 6. Live admission design (what will be implemented)

1. **Writers last**: each RustCraft transformer, at its first invocation,
   places the three writer instances at the END of
   `LaunchClassLoader.transformers` using index-wise `set()` rewrites (no
   structural list modification — the loader's active iteration is
   untouched; all class loading during startup is single-threaded). This is
   generic (no pack branch), it reorders OUR OWN transformers only, and the
   measured resulting order is recorded as evidence. In Clean Forge this is
   a no-op (the writers already sit last; the passing Clean smoke proves
   the input bytes were already the qualified ones).
2. **Coremod bootstrap**: `LiveShadowCoreMod.injectData` binds the session
   environment from the launch properties (the same
   `SystemPropertySessionEnvironment` the offline oracle binds), installs
   the entry observer at the FRONT via the existing
   `LoaderTransformChain.installAtFront`, and — when
   `rustcraft.qualificationResult` is set — registers a shutdown hook that
   flushes the evidence.
3. **Shared evidence flush**: `RevQualifyRuntime`'s end-of-launch flush
   (acquisition rows, pre/classes byte files, entry-observer failures,
   frame relation witness, loader definition witness, transformation chain,
   measured transformer order, forge facts, `production_authority:false`)
   is extracted into one producer both the offline oracle and the real
   launch call. One code path, one schema.
4. **Same engine, explicit new capture kind**: the qualification collector
   gains a REAL_FML_SERVER launch shape (boot the real server with the
   campaign jar in mods/, observer agent, session and evidence properties;
   bounded headless join; clean stop; artifact verification). The engine's
   acquire gate accepts exactly two explicit kinds —
   `OFFLINE_TRANSFORM_CAPTURE` and `REAL_FML_TRANSFORM_CAPTURE` — with
   every downstream node unchanged: the real launch's runtime topology,
   class identities, certificates, chain and witnesses must satisfy the
   same checks against the same static profile.

Nothing in this design stamps a sessionId, reuses a certificate, downgrades
an identity mode, or adds a second validator.
