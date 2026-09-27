# Pinned HotSpot whole-class verification proof

This is a bounded prerequisite experiment. It does not relax the V2 writer-placement gate, issue a runtime qualification certificate, or grant native packet authority. `writer-placement-v2` still reports the observed frame rewrites as **INCOMPLETE**. No Forge/game server runs in this campaign.

## What the link trigger establishes

Loading is insufficient. The generated Java 8 class has a private, dormant `dormant(Z)I` method. A structurally legal variant returns a reference from an integer-returning method. `Class.forName(name, false, exactLoader)` defines it successfully under `-Xverify:all`; the subsequent `getDeclaredMethods()` raises `VerifyError` at that dormant method. A second negative has inconsistent stack-map local types. The class initializer writes a sentinel property, which must remain absent throughout all probes. No target constructor or method is invoked.

This is an implementation-specific trigger backed by the pinned vendor source, not an inference from reflection's public name. At [vendor commit 1358e1b273e29392787e6feb32732304befaf1da, jvm.cpp](https://github.com/adoptium/jdk8u/blob/1358e1b273e29392787e6feb32732304befaf1da/hotspot/src/share/vm/prims/jvm.cpp), `get_class_declared_methods_helper` calls `link_class` before creating method reflection objects. [InstanceKlass](https://github.com/adoptium/jdk8u/blob/1358e1b273e29392787e6feb32732304befaf1da/hotspot/src/share/vm/oops/instanceKlass.cpp) invokes verification while linking. [ClassVerifier](https://github.com/adoptium/jdk8u/blob/1358e1b273e29392787e6feb32732304befaf1da/hotspot/src/share/vm/classfile/verifier.cpp) checks every method except native, abstract, and VM-generated overpass methods, including private methods and initializer bytecode. Conversely, this source's `JVM_ResolveClass` implementation is a no-op: calling `resolveClass` is not a substitute for this evidence.

The installed release says Temurin `1.8.0_504-b01`, VM `25.504-b01`, and `SOURCE=1358e1b273e2+`. The plus suffix prevents claiming a reproducible source-to-binary identity. Each campaign therefore binds the actual Java/Javac executables, all JRE native DLLs, `rt.jar`, `tools.jar`, release metadata, ASM, independent Rust parser, generated classes, probe sources, and imported capture code. Effective `BytecodeVerificationLocal` and `BytecodeVerificationRemote` are read through the HotSpot diagnostic API. A fresh `-Xverify:none` control must refuse before target definition. Changing either VM or source requires review and controls again.

## Counterexample: verification is not hierarchy qualification

`null-missing-type` puts null in an unused local and names `missing/Unknown` in the stack map. Whole-class verification succeeds. The recorded loader requests before the subsequent explicit type audit contain no request for `missing.Unknown`. Explicitly resolving that exact frame type through the same closed loader then fails. The output retains both request sequences and marks type binding incomplete, even though the verification trigger succeeded.

This is explained by [VerificationType::is_reference_assignable_from](https://github.com/adoptium/jdk8u/blob/1358e1b273e29392787e6feb32732304befaf1da/hotspot/src/share/vm/classfile/verificationType.cpp): null assignability can succeed without resolving the destination reference type. Equal verifier types can also avoid a hierarchy lookup. The [JVMS verification rules](https://docs.oracle.com/javase/specs/jvms/se8/html/jvms-5.html#jvms-5.4.1) permit loading during verification; they do not make verification a complete inventory of resolved reference types.

Consequently, observing only the classes requested by the verifier cannot qualify all stack-map types. The probe explicitly resolves the independently parsed frame object types and checks the actual returned `Class` object's defining loader: the exact target object or a bootstrap-defined class. Its private loader contains exactly the target bytes and delegates only `java.*` to bootstrap. These are fixture restrictions, not an acceptable Forge type-name allowlist. The positive relation examples derive their available type facts from those fresh object-binding observations.

## Narrow relation demonstrated here

Eight actual class files are canonicalized by V2 and cross-checked by the frozen independent Rust parser. All are structurally parseable, including the verifier-invalid controls. Three pairs have identical original instructions, operands, control-flow targets, ordered exception handlers, fields, declaration order, and observable method/class metadata:

| Pair | Sole permitted verification difference |
| --- | --- |
| `valid-top` → `valid-refined` | Unused local changes from TOP to the actual String reference type |
| `valid-object` → `valid-refined` | Unused local changes from Object to String |
| `valid-object` → `valid-max` | Declared maximum stack increases, with identical instructions |

Both entire classes in every pair must pass the pinned link trigger. Frame positions, stack entries, local-vector lengths, max locals, and all non-frame method fields stay exact. Added symbolic constants are limited to the one class/name pair attributable to a demonstrated frame type; original constants cannot disappear. Raw and V2 identities remain different and are preserved in the receipt. The helper returns only `FIXTURE_RELATION_ONLY`, never a qualification result. It is intentionally too narrow for arbitrary recomputed Forge frames.

## Proposed future relation and feasibility

A separate versioned relation is feasible as an offline proof stage, but it is not implemented here for general transformed classes. It must consume the existing exact insertion proof; remove only independently proven intended hook instructions; preserve all remaining instructions and operands, original handler ordering/ranges/targets, original control-flow correspondence, annotations (including instruction/local/handler targets), debug metadata, declarations, and other observable attributes. It must reject unsupported shapes. Both pre/post full class bytes must actually verify under the same pinned verification rules and a qualified loader/hierarchy model. Replaying ASM transformation or trusting ASM frame recomputation cannot serve as the oracle.

For each frame position a finite declared relation must cover its mapped instruction boundary, locals and operand-stack types, TOP slots, category-two values, uninitialized-this, and uninitialized values bound to the exact original NEW instruction. Handler-entry frames and synthetic handler frames need their own rules. Primitive and uninitialized types cannot be generalized to reference types. Object/array changes need exact resolved `Class` objects and validated assignability relationships in the qualified hierarchy; name equality is insufficient. Max-stack changes need actual verifier acceptance and a stated resource limit. Max-local changes require attribution to exact added local slots, with original slot meaning preserved. Every added pool entry must have an attributable use; pool or metadata changes cannot be discarded wholesale.

All object and array types from both frame sets must be enumerated independently, including types the verifier does not request. Array component identity and loader provenance matter too. Resolve them without initialization, bind exact class bytes/source artifacts/defining loader objects and hierarchy edges, and record failed resolutions as blockers. The same VM cannot define two different versions of one class in the same defining loader. A pre/post comparison therefore needs matched, separately isolated loader graphs with independently established hierarchy equivalence, or actual transformation-time pre/post evidence plus a qualified reference-loader model. A pair of loaders with the same class names is not that proof.

Explicit resolution itself can invoke user loader code, transform more classes, or change loader caches. A real LaunchClassLoader adapter must account for the resulting definitions and side effects; it cannot hide extra loader activity as normalization. Loading without initialization avoids this fixture's class initializer, but it does not establish absence of arbitrary custom loader behavior. Bytecode-inspecting agents and later transformers can observe changed frames/maxima/pool entries, so the supported observation model and downstream transformer order also require qualification. Verification acceptance alone proves neither program equivalence nor Forge writer/lifecycle closure. If that hierarchy/loader/observer scope cannot be closed, the gate remains INCOMPLETE.

## Evidence, bounds, and rerun

Run from the isolated checkout:

```powershell
& 'C:\Python314\python.exe' -B tools/hotspot-verification-proof/run.py
```

Optional `--output` must name a new directory under `target/hotspot-verification-proof`; `--java-home`, `--asm`, and `--rust-parser` are explicit overrides and remain pinned in the receipt. The installed Java release/source lead is deliberately fixed for this proof. New VMs need an updated reviewed experiment, not a version-name substitution.

The campaign compiles original fixtures and the current V2 parser afresh, runs 11 separate Java probe processes, checks eight independent parser agreements and three restricted relation pairs, and runs eight Python contract tests. Contract controls reject changed session/challenge/schema/hash/mode, absent link-trigger evidence, disabled verification, forged boolean facts, class initialization, extra/missing fields, and every non-permitted method-field mutation. Exact-byte hash mismatch refuses before definition; same-named classes from another loader are refused by actual object identity before the trigger.

Every command, return code, duration, raw stdout/stderr and output hash is retained. Subprocesses have 60-second and 32-MiB-per-stream limits; the campaign has a 128-MiB retained-byte limit. These are bounded trusted-tool runs, not an adversarial process sandbox. Java option injection variables and inherited classpath are removed, explicit classpaths are used, and no agents are configured. Before/after original-checkout guards and input/source/tool drift checks must pass. Failures retain a FAIL campaign receipt; they are not silently rerun into success.

`primary-source-provenance.json` records eight exact upstream source/license URLs, hashes, lengths, cached evidence paths and the installed release hash. `collect_primary_sources.py` can refresh that provenance into a new isolated target directory, but refreshing it changes a reviewed source input and requires a new campaign. Consulted HotSpot source is GPL-2.0-only with the applicable OpenJDK Assembly Exception; complete upstream LICENSE and ASSEMBLY_EXCEPTION files are retained with their hashes. No HotSpot implementation code is copied into or compiled into the original probe. The [StackMapTable specification](https://docs.oracle.com/javase/specs/jvms/se8/html/jvms-4.html#jvms-4.7.4) supplies format context; the explicit vendor source and dynamic controls justify this particular trigger.

Production authority is always false. Actual Forge post-hook verification, complete loader/hierarchy qualification, a general finite frame relation, and live writer/lifecycle closure remain outstanding.
