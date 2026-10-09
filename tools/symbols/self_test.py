#!/usr/bin/env python3
"""Symbol-index self-test — the documented entry point (AGENTS.md, goal docs).

Two levels:

1. Unit suite (always): synthetic classfiles + mapping fixtures + query
   engine. No Minecraft assets required.
2. Live sanity (when the index DB exists): re-derives the canonical light
   identities from the real index and fails on drift.

Exit 0 only when both applicable levels pass.
"""

import json
import os
import sys
import unittest

_HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, _HERE)

from symbol_index.builder import open_db  # noqa: E402

DEFAULT_DB = os.path.normpath(os.path.join(
    _HERE, "..", "..", "target", "symbol-index",
    "rustcraft-symbols.sqlite"))

# (query, expected class, expected srg, expected canonical descriptor)
CANONICAL_IDENTITIES = [
    ("net.minecraft.world.World.checkLightFor",
     "net/minecraft/world/World", "func_180500_c",
     "(Lnet/minecraft/world/EnumSkyBlock;"
     "Lnet/minecraft/util/math/BlockPos;)Z"),
    ("net.minecraft.world.World.getRawLight",
     "net/minecraft/world/World", "func_175638_a",
     "(Lnet/minecraft/util/math/BlockPos;"
     "Lnet/minecraft/world/EnumSkyBlock;)I"),
]


def run_unit_suite():
    print("== unit suite (synthetic fixtures) ==")
    suite = unittest.defaultTestLoader.discover(
        os.path.join(_HERE, "tests"), top_level_dir=_HERE)
    runner = unittest.TextTestRunner(verbosity=1)
    result = runner.run(suite)
    return result.wasSuccessful()


def run_live_sanity(db_path):
    from symbol_index.query import Queries
    print("== live sanity (%s) ==" % db_path)
    con = open_db(db_path)
    try:
        q = Queries(con)
        ok = True
        for ident, want_cls, want_srg, want_desc in CANONICAL_IDENTITIES:
            matches, amb = q.resolve_method(ident)
            if amb:
                print("  FAIL %s -> %d ambiguous identities" % (ident,
                                                                len(amb)))
                ok = False
                continue
            if not matches:
                print("  FAIL %s -> no match (is the index built from the "
                      "current sources?)" % ident)
                ok = False
                continue
            card = matches[0]
            got_cls = card["canonical_class"].replace(".", "/")
            got_srg = card["name"]["srg"] or card["name"]["bytecode"]
            got_desc = card["descriptor"]
            if (got_cls, got_srg, got_desc) != (want_cls, want_srg,
                                                want_desc):
                print("  FAIL %s -> %s/%s %s (expected %s/%s %s)"
                      % (ident, got_cls, got_srg, got_desc, want_cls,
                         want_srg, want_desc))
                ok = False
            else:
                layers = ", ".join(sorted(l["layer"]
                                          for l in card["layers"]))
                print("  OK   %s = %s/%s  [%d layers: %s]"
                      % (ident, got_cls, got_srg, len(card["layers"]),
                         layers))
        return ok
    finally:
        con.close()


def main():
    unit_ok = run_unit_suite()
    live_ok = None
    if os.path.exists(DEFAULT_DB):
        live_ok = run_live_sanity(DEFAULT_DB)
    else:
        print("== live sanity skipped (no index at %s; run "
              "`python tools/symbols/rustcraft_symbols.py build`) =="
              % DEFAULT_DB)
    print("self_test: unit=%s live=%s" % (
        "PASS" if unit_ok else "FAIL",
        "PASS" if live_ok else ("FAIL" if live_ok is False else "skipped")))
    return 0 if unit_ok and live_ok is not False else 1


if __name__ == "__main__":
    sys.exit(main())
