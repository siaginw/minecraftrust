"""Session-bound identities for the real Revelation pre-writer classes.

Runs the real identity tool over the bytes the Revelation transformation probe
actually produced, once per class, and records BOTH outcomes: a class that
carries MixinMerged.sessionId provenance yields a session-invariant identity,
and a class that does not is refused by the tool.

A refusal here is evidence, not a failure. The session-bound set is the set of
classes that genuinely carry session provenance; everything else stays on exact
identity. Quietly treating a refusal as an error would either invent provenance
or hide the classes that need the exact path, and either would make the
admission policy describe a runtime that does not exist.

    python -B tools/qualification-v2/rev_session_identities.py \
        --dump target/rev-probe/transformed \
        --identity-classes target/engine-v2-classes \
        --asm D:/rustcraft-runtime-targets/revelation-3.4.0/server/libraries/org/ow2/asm/asm-all/5.2/asm-all-5.2.jar \
        --required tools/live-capture/required-live-writer-hooks.json \
        --out target/architecture-hardening/rev-session-identities.json
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess
from pathlib import Path

IDENTITY_MAIN = "com.rustcraft.coremod.CanonicalClassIdentityV2"
JAVA = Path("D:/rustcraft-toolchains/temurin8/jdk8u504-b01/bin/java.exe")


def required_classes(manifest: Path) -> list[str]:
    document = json.loads(manifest.read_text(encoding="utf-8"))
    return sorted({hook["class"] for hook in document["required_hooks"]})


def identify(java: Path, classpath: str, classfile: Path, session_bound: bool) -> dict:
    # The bare form is the RECEIPT layout -- [schema, class, semantic,
    # declaration_order, raw, ...]. `--dump` emits the full canonical projection
    # instead, whose first elements are not digests, so indexing one as the other
    # silently compares the wrong fields. Only the session-bound receipt has the
    # invariant appended, and it is read with its own layout below.
    flags = ["--session-bound"] if session_bound else []
    process = subprocess.run(
        [str(java), "-cp", classpath, IDENTITY_MAIN, *flags, str(classfile)],
        capture_output=True, text=True,
        env={k: v for k, v in os.environ.items()
             if k not in ("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS", "CLASSPATH")},
        timeout=300)
    if process.returncode != 0:
        return {"error": (process.stderr.strip() or process.stdout.strip()).splitlines()[-1]}
    return json.loads(process.stdout.strip().splitlines()[0])


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dump", type=Path, required=True,
                        help="the probe's transformed/ directory")
    parser.add_argument("--identity-classes", type=Path, required=True)
    parser.add_argument("--asm", type=Path, required=True)
    parser.add_argument("--required", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()

    classpath = os.pathsep.join([str(args.identity_classes.resolve()), str(args.asm.resolve())])
    classes = {}
    session_bound, exact_only = [], []
    for name in required_classes(args.required):
        classfile = args.dump / (name.replace('.', '/') + '.class')
        if not classfile.is_file():
            print("MISSING", name, classfile)
            return 1
        session = identify(JAVA, classpath, classfile, True)
        exact = identify(JAVA, classpath, classfile, False)
        record = {"class_name": name.replace('.', '/'),
                  "raw_sha256": exact[4] if "error" not in exact else None,
                  "semantic_sha256": exact[2] if "error" not in exact else None,
                  "declaration_order_sha256": exact[3] if "error" not in exact else None}
        if "error" in session:
            record["session_invariant_sha256"] = None
            record["session_provenance"] = False
            record["refusal"] = session["error"]
            exact_only.append(name)
        else:
            # The session-bound receipt is
            # [schema, class, semantic, declaration_order, raw, invariant,
            #  distinct_uuid_count, masked_values, occurrence_count, locations].
            record.update({
                "session_invariant_sha256": session[5],
                "session_provenance": True,
                "distinct_masked_uuid_count": session[6],
                "masked_values": session[7],
                "masked_occurrences": session[8],
                "masked_locations": session[9],
            })
            session_bound.append(name)
        classes[name.replace('.', '/')] = record

    document = {
        "schema": "RUSTCRAFT_REVELATION_SESSION_IDENTITIES_V1",
        "source_dump": str(args.dump),
        "identity_classpath": [str(args.identity_classes.resolve()), str(args.asm.resolve())],
        "session_bound_classes": sorted(session_bound),
        "exact_only_classes": sorted(exact_only),
        "classes": classes,
    }
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(document, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"session_bound": len(session_bound), "exact_only": len(exact_only),
                      "out": str(args.out)}))
    for name in sorted(exact_only):
        print("  no provenance:", name)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
