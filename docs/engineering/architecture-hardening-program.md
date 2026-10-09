# Architecture hardening implementation program

Baseline: `c4b868db2c9e4b03f745bf93c6ebf4fd8a8519e7`.
Execution checkout: `D:/minecraftrust-astra-hardening`, branch
`astra/architecture-hardening`. The original `D:/minecraftrust` checkout is
read-only; its paused Revelation files and evidence must remain untouched.

The complete authorized requirements are preserved in
`architecture-hardening-request.md`. Machine progress is tracked in
`../../machine/architecture-hardening/program.json`. A pending requirement is
not satisfied by a design note, a passing unrelated test, or an expected receipt.
Every milestone needs implementation, scoped validation, evidence, and an
independent commit. No push is authorized.

## Permanent gates

- `M4NativeStatePayload.tryEncode` remains fail-closed.
- `productionAuthorityEligible()` remains false.
- MCK6 framing/compression semantics and defaults remain unchanged.
- Retained-state, runtime, scheduler and transfer experiments are not production authority.
- No Revelation live shadow until BOTH profiles are freshly requalified under V2.
- Paused V1 shadow attempts are debugging history, never successful live evidence.
- No builds, execution, staging, edits, cleanup or campaigns in the original tree.

## Validation discipline

For every milestone record exact commands, input/source/tool identities, exit
status, test inventory, exclusions and evidence scope. Use `cargo fmt`, appropriate
Clippy and Rust tests, applicable Java/tooling compilation, and `git diff --check`.
Qualification/bridge/state changes require immediate applicable Clean Forge and
Revelation regression checks. Missing artifacts mean INCOMPLETE. Old successful
receipts never substitute for fresh required observations.

Performance integrations report kernel and complete-operation costs separately,
including CPU, allocations, RSS, tail latency and semantic parity where measurable.
Experimental or unavailable metrics remain explicitly unmeasured. Preserve failed
experiments and audit contradictions; do not invent favorable numbers.

## Audit additions carried into implementation

- V2 semantic hashing must bind every operand, handler, flag, annotation and
  observable metadata. Preserve a separate declaration-order commitment when
  member-order normalization is not proven irrelevant to an admitted profile.
- Unknown classfile attributes and unsupported versions fail closed.
- Parser agreement is structural evidence, not JVM bytecode verification or live qualification.
- Backing-array ownership needs origin/identity closure; an active ticket alone
  must not become a generic writer certificate.
- Fresh profile observations and certificate dependencies cannot be replaced by
  expectations embedded in the same manifest.
- Rust/JVM reentry must never alias a live unrestricted Rust mutable borrow.
- Fallback after divergent state is not safe: transfer requires quiescence and
  one writer, with generation advancement and cache invalidation.
- Preserve Java arithmetic, precision, RNG consumption and callback order;
  matching discrete terrain blocks does not establish floating-point equivalence.
- Measure entire NBT consumption and packet pipelines, retained buffers and
  snapshot memory; never extrapolate parser/kernel ratios to whole-server claims.
- Native worker budgets must account for JVM GC, Netty, I/O and compression.
- Finite slot generations require session identity and exhaustion handling.
- Region files retain one metadata writer and explicit crash/durability semantics.
- Evidence and compatibility infrastructure remain after diagnostic scaffolding exits.

## Progress interpretation

NOW recommendations require validated integration. SOON items require serious
bounded implementations/prototypes. LATER items require isolated evidence and an
integration gate. RESEARCH_ONLY items require meaningful experiments and an
explicit go/no-go. The program remains active until all requirements are proven;
finishing an early milestone does not redefine the objective.
