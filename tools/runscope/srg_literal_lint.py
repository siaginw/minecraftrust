#!/usr/bin/env python3
"""SRG-literal lint: every field_NNNNN_x / func_NNNNN_x literal in the
bridge sources must exist in the bytecode symbol index.

Motivated by the zsa13 incident: bridge code resolved Chunk.sections via
the literal field_76647_h — which is Chunk.z (notch axw.c). The symbol
index had the truth all along; nothing checked literals already sitting
in source. Also catches hallucinated ids at write time.

Exit 0 clean / 1 unknown ids found / 2 index unavailable (warn-only —
build machines without the index fall back to a warning).

Usage:
  python tools/runscope/srg_literal_lint.py            # lint bridge sources
  python tools/runscope/srg_literal_lint.py --json
Standalone-importable: srg_literal_lint(paths, db_path) -> findings.
"""

import argparse
import json
import re
import sqlite3
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_DB = ROOT / "target" / "symbol-index" / "rustcraft-symbols.sqlite"
DEFAULT_SOURCES = ROOT / "tools" / "bridge" / "src"

SRG_RE = re.compile(r"\b(?:field|func)_\d+_[a-z]\d*\b")
# Forge/FML-added members are SRG-named in the runtime but may be absent
# from the vanilla-srg index; they must be justified inline.
JUSTIFIED_MARK = "SRG-LINT-JUSTIFIED"


def find_srg_db(explicit=None):
    for cand in ([explicit] if explicit else []) + [DEFAULT_DB]:
        if cand and Path(cand).is_file():
            return Path(cand)
    return None


def lint(paths, db_path=None):
    """Returns (findings, db_used). finding: dict(file, line, id, kind,
    reason) — kind: unknown|justified-unknown."""
    db = find_srg_db(db_path)
    findings = []
    if db is None:
        return ([{"kind": "no-index",
                  "reason": f"symbol index not found at {DEFAULT_DB} "
                            f"(build it: python tools/symbols/"
                            f"rustcraft_symbols.py build); skipping"}], False)

    con = sqlite3.connect(str(db))
    known = {r[0] for r in con.execute("SELECT srg_name FROM member_names")}
    con.close()

    for path in paths:
        text = path.read_text(encoding="utf-8", errors="replace")
        for lineno, line in enumerate(text.splitlines(), 1):
            for ident in SRG_RE.findall(line):
                if ident in known:
                    continue
                kind = ("justified-unknown"
                        if JUSTIFIED_MARK in line else "unknown")
                findings.append({
                    "file": str(path.relative_to(ROOT)),
                    "line": lineno,
                    "id": ident,
                    "kind": kind,
                    "reason": f"{ident} not in symbol index"
                              + (" (justified inline)" if kind.startswith("just")
                                 else " — hallucinated or unmapped id; verify"
                                      " with: python tools/symbols/"
                                      "rustcraft_symbols.py map " + ident),
                })
    return findings, True


def java_sources(root=DEFAULT_SOURCES):
    return sorted(Path(root).rglob("*.java"))


def main():
    ap = argparse.ArgumentParser(prog="srg-literal-lint")
    ap.add_argument("--json", action="store_true")
    ap.add_argument("--db", default=None)
    args = ap.parse_args()

    findings, db_used = lint(java_sources(), args.db)
    unknown = [f for f in findings if f["kind"] == "unknown"]
    justified = [f for f in findings if f["kind"] == "justified-unknown"]

    if args.json:
        print(json.dumps({"db_used": db_used, "unknown": unknown,
                          "justified_unknown": justified}, indent=1))
    else:
        for f in findings:
            print(f"[{'FAIL' if f['kind'] == 'unknown' else 'INFO'}] "
                  f"{f['kind']}: {f.get('file')}:{f.get('line')} "
                  f"{f['reason']}")
        if db_used:
            print(f"[srg-lint] {len(unknown)} unknown, "
                  f"{len(justified)} justified-unknown "
                  f"({len(java_sources())} files scanned)")
    if not db_used:
        return 2
    return 1 if unknown else 0


if __name__ == "__main__":
    sys.exit(main())
