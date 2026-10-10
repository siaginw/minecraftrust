# RustCraft Engine Rewrite — Roadmap (v2, 2026-10-10)

## Objective (operator directive, 2026-10-10)

Build a Minecraft 1.12.2 engine around Rust while preserving compatibility
with existing Forge/mod JARs through a defined compatibility runtime.

- Correctness and demonstrated native execution remain REQUIRED.
- Performance is measured and reported, but an early compatibility
  milestone does not need to beat clean Java before development continues.
- Historical benchmark findings and their limits are PRESERVED (see
  RUSTCRAFT_VS_JAVA_FULL_STACK_BENCHMARK.md + the FS-series receipts —
  they stand as measured, including that the composed stack was slower
  than clean Java at each recorded configuration).

## Architecture (unchanged by evidence)

Rust engine core + JVM mod island with patchable Java shells — forced by
coremod/mixin patching of vanilla bytecode (see
REA_SETUP_AND_RUSTCRAFT_REWRITE_ASSESSMENT.md). The rewrite proceeds by
OWNERSHIP MILESTONES: each flips one subsystem to Rust with engagement
proof, compatibility boundary documentation, and correctness receipts;
performance is reported per milestone but is not an early gate.

## Status corrections to inherited claims (2026-10-10)

1. **Light SHADOW retained Rust light computation** (shadow comparator:
   ~2k jobs / 30k shadow cells per run). M0/FS-004 refuted "light
   authority causes the residual MSPT gap" — it did NOT measure, and must
   not be cited as, light-machinery-off.
2. **No-capture-session results bypassed writer enforcement** and are
   DIAGNOSTIC/isolation results, not valid full-stack performance
   configurations (FS-003 scope limits stand).
3. **Netty's transformer exclusion does not establish zero compatibility
   exposure** for the surrounding packet path — the packet subsystem's
   compatibility boundary must be mapped on its own classes (M1 §3 work).
4. **REA capabilities are host- and workflow-verified only as stated in
   its setup receipt**: MCP registration verified (133 tools,
   binary_session OK); native Windows process capture NOT yet verified;
   Ghidra/Hopper/IDA engines not installed. REA has no JVM/jar tools —
   the symbol index remains the bytecode authority.

## Milestone ladder

- **M0** (FS-004): DONE — closed the light-ownership branch.
- **M1 — packet emission ownership: ENGAGED (2026-10-10)** in the
  supported (qualified-capture) composition: `--m1-packets
  --minimal-authorities`. m1-qual1 proves the full path — writer hooks
  admitted on Phosphor-mixin'd bytes, bindings formed, native state
  checked, Rust encoded + directly emitted 91/169 packets (2.9 MB on the
  wire), client received all 169; every fallback accounted. Root cause
  of the earlier non-engagement was NOT profile staleness: the pins are
  derived from a foreign-only discovery chain, so ANY authority
  transformer registered before the writers breaks identity BY DESIGN.
  Open follow-up: composed-config (authorities ON) engagement needs
  per-composition profile re-derivation or a foreign-stage identity
  boundary (architecture decision). Receipt:
  M1-PACKET-EMISSION-RECEIPT.json (boundary + classification inside).
- **M1 next**: the boundary classification lives in the receipt (Rust
  owns payload encoding + direct emission for the eligible slice; Java
  keeps framing/pipeline/TE/state serialization/game loop; NO new
  adapter needed).
- **M1-BOUNDARY** (with M1): pin modpack/Forge/mod identities; map the
  packet subsystem's class surface (refs, reflection, coremod targets,
  callbacks); classify Java-must-stay / delegable / adapter-needs.
- **M2 — region I/O end-to-end** (read+write authority-proven; retire
  remaining Java copy paths).
- **M3 — world tick/game-loop ownership behind patchable shells** (the
  exhaustive "which classes do the 73 jars' coremods touch" census from
  the symbol index comes FIRST).

## Standing gates (unchanged)

Digest/parity evidence, bounded authority, engagement witnesses (a
shadow-produced body is NOT a Rust-transmitted packet),
PRODUCTION_AUTHORITY=false until a dedicated milestone closes it.
