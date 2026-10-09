# V2 writer-plan admission prerequisite

The writer-plan generator and pre-hook admission now carry an explicit identity
schema. V2 admission compares the complete semantic digest and declaration-order
digest from one parse of the exact incoming class bytes. It rejects missing,
mixed, unknown or inconsistent contracts. The legacy boolean remains only for
historical V1 callers; V2 cannot silently fall back to that path.

The generator accepts an observed V2 **recipe**, not a qualification certificate.
It requires the exact required-hook and class inventory, explicit V2 identities,
the manifest digest, and observed hook statuses. It rejects old qualification
booleans, missing identities, malformed anchors and mode downgrade. Manifest
parsing and hashing use one captured byte buffer. Refusals preserve the existing
output. Output paths must remain in the generator's checkout. Historical V1
generation requires `--legacy-v1-reproduction`.

The installed default remains the existing RAW diagnostic plan. Regeneration
preserves its 66 hook entries, hashes, anchors and profile. No V2 runtime profile,
issuer or capture scope is installed. `M4NativeStatePayload.tryEncode` and the
production authority gate remain fail-closed.

Validation:

- 26 Java admission assertions include a V1-invisible operand mutation, changed
  declaration order, incorrect digests/class/schema, mixed contracts, and helper
  preservation of V2 metadata.
- 23 negative generator controls preserve a sentinel output. A generated V2 plan
  compiles and admits the independently constructed fixture. Default RAW
  regeneration is byte-identical; a custom generated class name compiles.
- All 92 canonical V2 assertions pass. Historical Revelation checks still match
  129 classes and reject 10 controls; this remains historical V1 evidence.
- Fresh Clean Forge checks pass: 25 tooling tests, 201 foundation assertions,
  transformer/capture verification and a 4,358-byte mask-1 diagnostic comparison.

The source-bound final admission receipt is
`machine/architecture-hardening/h23-plan-v2-evidence/reviewed/receipt.json`.
The initial harness failure (missing javac output directory), earlier passing
run, and all regression receipts are retained alongside it. The machine summary
records their hashes and scope. No Rust source changed in this milestone; the
existing H3 DLL was used by the Clean diagnostic regression.

Reproduce the admission checks from the isolated checkout with explicit tools:

```powershell
python -B tools/writer-plan-v2-tests/run.py --java-home <Java8-home> --asm <ASM-5.2-jar>
```

This is a prerequisite for H23, not completed requalification. Next, independently
prove exact V2 hook placement and bytecode preservation, then collect fresh Clean
Forge and Revelation observations with runtime, loader and transformer identities.
The generic qualification engine still needs real placement, writer/lifecycle
closure and runtime issuer adapters. Legacy post-hook checks are regression
evidence only; they do not replace those proofs. H24 Revelation shadow remains
blocked on fresh V2 requalification, and no historical live-parity result is
created by this change.
