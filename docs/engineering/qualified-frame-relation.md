# Qualified finite frame relation

This separate offline stage keeps the committed conservative writer-placement gate unchanged. It proves a bounded relation between exact pre/post buffers and requires externally established whole-class verification, loader/hierarchy and observer-scope evidence. Missing evidence remains **INCOMPLETE**. It never enables native packet authority or establishes live writer/lifecycle closure.

## Proof boundaries

`structural.py` uses the committed `writer-placement-v2` insertion grammar and explicit original-instruction mapping. It independently compares every non-frame V2 method/class/field fact, declarations, ordered handlers, instruction operands/annotations, debug ranges, parameter facts, symbolic bootstrap entries and original constant-pool multiplicities. Only exact prescribed hook groups and their attributable constants may be added. Hooked maximum locals/stack remain exact to the independently specified layout and stack-height proof. Unhooked declared maximum stack may vary only above the independently computed height and within the classfile bound, with both complete classes subsequently verified.

`frames.py` supplies a finite typed grammar. TOP, INTEGER, FLOAT, DOUBLE, LONG, NULL, uninitialized-this, object/array references and allocation-bound uninitialized values remain distinct. Category-two values expand into a checked second slot. A changed primitive requires TOP in the old or new local slot; live INTEGER/FLOAT and LONG/DOUBLE substitutions are not admitted. TOP changes on operand stacks are not permitted. Two TOP locals may become one category-two value, but a wide value cannot be split. Uninitialized-this stays identical at mapped constructor frames. An uninitialized allocation must reference the same original NEW instruction through the **original-instruction map**, not the earlier insertion boundary. Unknown tags and non-NEW allocation targets fail.

Original frame positions must correspond exactly, with only prescribed appended handler frames added. Reference/reference rewrites require comparable actual resolved types; both directed assignability queries are collected. NULL/reference and TOP/reference changes still require explicit reference resolution. The added token/exception local slots have exact grammar constraints. Catch-all stacks are exactly Throwable. Original local slots in a new handler can carry a verifier-valid merge because the independently fixed handler body uses only added bookkeeping locals; uninitialized original locals remain unsupported there. Unsupported well-formed rewrites stay INCOMPLETE. This is deliberately less permissive than accepting any two verifier-valid classes.

The [JVMS StackMapTable rules](https://docs.oracle.com/javase/specs/jvms/se8/html/jvms-4.html#jvms-4.7.4) describe verification types; the [type-checking rules](https://docs.oracle.com/javase/specs/jvms/se8/html/jvms-4.html#jvms-4.10.1) supply the verifier role. This stage does not reimplement the complete JVM verifier. It requires actual whole-class verification using the separately reviewed pinned HotSpot link trigger. Identical original code and handler correspondence, the finite metadata relation, actual verification and a closed observation model are joint prerequisites; no one is a substitute for the others.

## Object identity, hierarchy and evidence provenance

The full wire contract is [schema.md](../../tools/qualified-frame-relation/schema.md). Requests enumerate every frame reference type from both buffers, including arrays and types the JVM verifier never resolves. Each type is explicitly resolved in both phases. The witness records actual Class object identities, defining loader identities, exact definition bytes/artifacts, superclass/interface edges and array components. Object identity registries must be collision-free; names and identityHashCode values alone are insufficient.

Loader correspondence is only a candidate mapping. The validator independently checks its bijection, parent edges, implementation/configuration/policy pins, then derives Class correspondence by mapped defining loader, name and kind. Hierarchy/interface order/component edges must match. Non-target definition bytes and all supplying artifact identities must be unchanged. Only exact target classes already covered by the proof may have changed definition bytes. Array rank, primitive/reference component, bootstrap Object/Cloneable/Serializable and component defining loader are checked. Assignability is independently recomputed from this graph and compared with actual `Class.isAssignableFrom` answers. Contradictions, foreign Class bindings and graph drift fail.

This cannot authenticate arbitrary caller-authored JSON. `evaluate.py` requires an external trust envelope pinning the fresh collector's request/session/challenge, exact witness and execution receipt, collector sources and VM inventory. The root qualification engine owns real acquisition and those trust decisions. The receipt must establish clean successful fresh subprocesses, unchanged inputs and the expected verification mechanism. Editing a witness and its purported trust envelope together is not authentication. The final result states this trust boundary explicitly.

Separate externally accepted policies must close downstream transformers/agents/class-byte observers and custom-loader effects from explicit resolution. These policies have exact inventory/evidence hashes. They cannot be inferred from successful verification or a loader-name allowlist. Until root's collector supplies those policies, the real runtime result remains INCOMPLETE even if every class verifies. Pre/post versions cannot coexist in one defining loader; matched separate loader graphs therefore require this explicit correspondence proof. Bytecode-observing agents can observe frame/max/pool changes, and user loader code can perform arbitrary effects. Those concerns are not normalized away.

## Actual fixture and negative controls

The campaign compiles a hand-authored fixture with exact writer/constructor instrumentation plus unhooked methods demonstrating TOP→INTEGER, two TOPs→LONG, Object→String, Object[]→String[] and an unchanged allocation-bound uninitialized value. It does not run a production transformer. Both full versions are verified in separate Java processes using closed loaders and actual collision-free identity maps. The fixture collector uses pinned `sun.misc.Unsafe.shouldBeInitialized` reflectively to observe that the target remains uninitialized before/after verification and after hierarchy inspection; it does not infer this from Class.forName(false). This is a test-only pinned HotSpot API, not a production dependency.

The reviewed [Unsafe native implementation](https://github.com/adoptium/jdk8u/blob/1358e1b273e29392787e6feb32732304befaf1da/hotspot/src/share/vm/prims/unsafe.cpp) reads the represented Klass's initialization predicate; the [InstanceKlass predicate](https://github.com/adoptium/jdk8u/blob/1358e1b273e29392787e6feb32732304befaf1da/hotspot/src/share/vm/oops/instanceKlass.hpp) distinguishes an initialized class. The experiment records true before and after for this ordinary target class, with no concurrent target execution. It makes no general claim for arrays, primitives, failed initialization or concurrently initializing classes. Exact upstream source/header/license bytes are retained under the isolated target with hashes in `primary-source-provenance.json`; the installed vendor source's `+` suffix still prevents a reproducible-build identity claim.

A second actual class pair puts null in an unused local and changes its frame type to `missing/Unknown`. Both complete classes can verify, but explicit same-loader resolution fails. The relation must remain INCOMPLETE. Raw output, loader requests, initialization observations, definition artifacts, all Class graphs and assignability answers are preserved. Fixture scope policy is restricted to this hand-authored class and closed bootstrap delegation; it cannot qualify Forge or LaunchClassLoader.

Model-only controls exercise forged hashes, stale/cross-session evidence, wrong VM/source/input pins, disabled flags, wrong trigger, failed verification, foreign Class identity, cyclic/different loader graphs, dependency byte/artifact drift, missing/duplicate resolutions, missing observer/loader closure, contradictory assignability, array component/rank rules and incomparable references. These model witnesses exist only inside tests and cannot qualify a corpus. Separate typed controls cover wide-slot splitting, unknown tags, wrong NEW targets, uninitialized-this changes, stack TOP changes and additional frame positions. The original 27 real placement classfile mutations are all still blocked.

The retained Clean pre/post buffers are independently reparsed and analyzed across all required classes/hooks. This is retained offline evidence, not fresh game qualification. Their machine report lists any finite-relation blockers and the precise reference-resolution/query obligations for a later actual collector. No synthetic Clean PASS witness is produced.

The first complete campaign observed 12 retained Clean classes and 66 hooks as structurally eligible, with no remaining finite grammar blocker. It generated 115 type requests and 10 directed assignability queries for each phase. The result remained INCOMPLETE because no externally pinned actual Clean verification/hierarchy/scope witness was supplied. The actual positive fixture had 10 corresponding hierarchy Class nodes, 12 resolved type requests across both phases, and eight directed query answers. The full campaign performs 60 parser agreements, four actual verification JVM processes and 39 Python tests (31 relation controls plus eight file-binding controls). An earlier exploratory receipt reported 64 parser agreements due to a counting typo; its raw observations are preserved and the final campaign corrects that count to 60.

Independent review exposed an input-binding defect in the initial evaluator: it parsed one file read but authorized it with a later path hash. Six deterministic swap-back controls reproduced unsafe model PASS results for prepared/request/structure/witness/trust/receipt artifacts; `target/qualified-frame-relation/binding-controls-before-fix` preserves them. The repaired reader produces parsed facts and SHA-256 from the same immutable bounded byte buffer. Prepared/request/structure expected pins are checked against that buffer; witness/receipt authentication uses that buffer's digest; end-of-stage drift checks compare paths against those original digests. The plan reader follows the same rule. Generated request, structural report and fixture witness/receipt pins likewise come from the exact serialized buffers written. The eight file-binding controls include the unchanged positive baseline, all six swap-back races, and a plan swap-back case. Earlier campaign PASS receipts are preserved as pre-fix history, not evidence of the repaired contract.

## Commands and artifacts

Run the bounded complete campaign from the isolated checkout:

```powershell
& 'C:\Python314\python.exe' -B tools/qualified-frame-relation/run.py
```

The generic two-stage API is:

```text
prepare.py --pre PRE_CLASS_ROOT --post POST_CLASS_ROOT --plan HOOK_PLAN
  --java JAVA_EXE --classpath V2_CLASSES_AND_ASM --rust-parser RUST_PARSER
  --profile-sha256 HASH --manifest-sha256 HASH --observation-sha256 HASH
  --output NEW_ISOLATED_DIRECTORY [--session HEX32 --challenge HEX64]

evaluate.py --prepared PREPARED_JSON --prepared-sha256 EXPECTED_HASH
  [--witness WITNESS --trust EXTERNAL_TRUST --collector-receipt ACTUAL_RECEIPT]
  --out NEW_ISOLATED_RESULT_JSON
```

Preparation always reports INCOMPLETE when structurally eligible: no actual witness is assumed. The prepared artifact binds copied exact bytes, both parser outputs, source/tool inventories, the request and structural report. Evaluation rechecks those pins and source inventory before consuming external evidence. The caller pins `prepared.json` itself. Fresh outputs stay under `target/qualified-frame-relation`. Source/tool/input drift, duplicate JSON keys, nonfinite JSON and unknown witness fields are rejected. Original-checkout isolation guards run before/after each stage.

Trusted subprocesses have bounded output and 60-second deadlines, no shell, explicit classpaths, and inherited Java option injection removed. Class copies have per-file/total limits; expanded frame slots are capped at 524,288 per method before allocation; query-count × Class-node-count is capped at 2,097,152 per phase; graph/query counts are bounded and campaign retention is capped. This is not an untrusted process sandbox. Actual game acquisition belongs to the root collector; this tool launches only small fixture/identity JVMs. No production source/default/gate or original worktree is modified.

Remaining work is independent review, actual Forge pre/post verification/hierarchy acquisition, accepted observer/loader-effect policies, engine integration and live writer/lifecycle evidence. Production authority remains false.
