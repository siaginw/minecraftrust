#!/usr/bin/env python3
"""Live-writer transformer diagnostic run: fresh pre-hook qualification JVM, then a
fresh diagnostic JVM with -Drustcraft.liveWriterDiagnostic=true carrying the
inserted hooks, the in-JVM negative controls, and the transformed integration
tests. Machine-readable receipt under <output>/forge-runtime-result.json."""
import argparse
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--java-home", type=Path, required=True)
    parser.add_argument("--dll", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, default=ROOT / ".rustcraft-local/forge-runtime.json")
    args = parser.parse_args()
    import forge_runtime
    result = forge_runtime.execute(ROOT, args.output.resolve(), args.java_home, args.dll,
                                   args.manifest, qualification_only=True, live_transformers=True)
    print(json.dumps({key: result.get(key) for key in ("status", "reason", "detail", "output",
                                                       "post_hook_transformed_dir")}))
    return {"PASS": 0, "INCOMPLETE": 2}.get(result["status"], 1)


if __name__ == "__main__":
    sys.exit(main())
