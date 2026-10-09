#!/usr/bin/env python3
"""rustcraft-symbols — Minecraft 1.12.2 / Forge bytecode + mapping navigator.

Build once, query instantly:

    python tools/symbols/rustcraft_symbols.py build
    python tools/symbols/rustcraft_symbols.py method net.minecraft.world.World.checkLightFor
    python tools/symbols/rustcraft_symbols.py map World.checkLightFor
    python tools/symbols/rustcraft_symbols.py callers func_180500_c
    python tools/symbols/rustcraft_symbols.py field-readers net.minecraft.world.EnumSkyBlock.BLOCK

Database: target/symbol-index/rustcraft-symbols.sqlite (not committed).
Docs: docs/engineering/BYTECODE_SYMBOL_INDEX.md
"""

import argparse
import json
import os
import sqlite3
import sys

_HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, _HERE)

from symbol_index import schema                                  # noqa: E402
from symbol_index.format import pretty_access, pretty_desc       # noqa: E402

DEFAULT_DB = os.path.normpath(os.path.join(
    _HERE, "..", "..", "target", "symbol-index",
    "rustcraft-symbols.sqlite"))
SOURCES_EXAMPLE = os.path.join(_HERE, "sources.json")


def _sep():
    return "-" * 78


def _emit(data, as_json, text_fn):
    if as_json:
        print(json.dumps(data, indent=1, default=str))
    else:
        text_fn(data)


# ----------------------------------------------------------------------
# builders

def _load_cfg(path=None):
    from symbol_index.sources import DEFAULT_SOURCES, load_sources_file
    if path:
        return load_sources_file(path)
    if os.path.isfile(SOURCES_EXAMPLE):
        return load_sources_file(SOURCES_EXAMPLE)
    return DEFAULT_SOURCES


def cmd_build(args):
    from symbol_index.builder import Builder, load_mappings
    from symbol_index.sources import resolve_sources
    resolved, skipped = resolve_sources(_load_cfg(args.sources))
    for s in skipped:
        print("SKIP  %s: %s" % (s["name"], s["reason"]))
    m = load_mappings(resolved)
    os.makedirs(os.path.dirname(os.path.abspath(args.db)), exist_ok=True)
    if os.path.exists(args.db) and not args.append and not args.incremental:
        os.remove(args.db)
    b = Builder(args.db, m)
    only = set(args.layer) if args.layer else None
    b.build(resolved, only_layers=only, incremental=args.incremental)
    print("index: %s" % args.db)


def cmd_sources(args):
    from symbol_index.sources import resolve_sources
    resolved, skipped = resolve_sources(_load_cfg(args.sources))
    for m in resolved["mappings"]:
        print("MAPPING  %-46s %s" % (m["name"], m["path"]))
        print("         sha256=%s" % m["sha256"])
    for a in resolved["artifacts"]:
        print("ARTIFACT %-11s %-40s %s" % (a["layer"], a["name"], a["path"]))
    for s in skipped:
        print("SKIP     %-46s %s" % (s["name"], s["reason"]))


# ----------------------------------------------------------------------
# queries

def _print_method_card(card):
    n = card["name"]
    print(_sep())
    print("METHOD  %s.%s" % (card["canonical_class"],
                             n["mcp"] or n["srg"] or n["bytecode"]))
    print("  MCP      %s.%s" % (card["canonical_class"], n["mcp"] or "-"))
    print("  SRG      %s.%s" % (card["canonical_class"], n["srg"] or "-"))
    print("  NOTCH    %s.%s" % (card["canonical_class"], n["notch"] or "-")
          if n["notch"] else "  NOTCH    (class-name only; notch member "
          "identity lives in the mapping table)")
    print("  DESC     %s" % card["descriptor"])
    print("           %s" % pretty_desc(card["descriptor"]))
    print("  ACCESS   %s" % pretty_access(card["access"]))
    print("  LAYERS   %d artifact(s) define this identity:" % len(card["layers"]))
    for l in card["layers"]:
        h = l["code_sha256"]
        print("    %-22s %-42s code=%s" % (l["layer"], l["artifact"][:42],
                                           (h[:16] + "...") if h else "-"))
    print("  GRAPH    callers=%d callees=%d field_access_sites=%d" % (
        card["callers_count"], card["callees_count"],
        card["field_access_count"]))
    if card["overrides"]:
        print("  OVERRIDES FOUND IN SUBCLASSES (%d):" % len(card["overrides"]))
        for o in card["overrides"][:20]:
            print("    %-52s [%s] as %s" % (o["class"], o["layer"],
                                            o["bytecode_name"]))
    jar = card["layers"][0]["artifact"]
    print("  INSPECT  javap -p -c -classpath <layer jar> %s"
          % card["canonical_class"])
    print(_sep())


def cmd_method(args):
    from symbol_index.query import Queries
    con = sqlite3.connect("file:%s?mode=ro" % args.db.replace("\\", "/"),
                          uri=True)
    qq = Queries(con)
    matches, ambiguous = qq.resolve_method(args.ident, layer=args.layer)
    if ambiguous:
        print("AMBIGUOUS identifier — %d distinct identities; qualify with "
              "class and/or descriptor:" % len(ambiguous))
        for a in ambiguous[:30]:
            print("  %s.%s  %s  (layers: %s)" % (
                a["canonical_class"], a["member"],
                pretty_desc(a["descriptor"]), ",".join(a["layers"])[:60]))
        return 2
    if not matches:
        print("no method matches %r" % args.ident)
        return 1

    def text(cards):
        for c in cards:
            _print_method_card(c)

    _emit(matches, args.json, text)
    return 0


def cmd_map(args):
    from symbol_index.query import Queries
    con = sqlite3.connect("file:%s?mode=ro" % args.db.replace("\\", "/"),
                          uri=True)
    res = Queries(con).map_name(args.name)

    def text(r):
        if not (r["classes"] or r["methods"] or r["fields"]):
            print("no mapping found for %r" % args.name)
            return
        for c in r["classes"]:
            print(_sep())
            print("CLASS MAP")
            print("  MCP      %s" % c["MCP"])
            print("  SRG      %s" % c["SRG"])
            print("  NOTCH    %s" % (c["NOTCH"] or "-"))
            print("  LAYERS   %s" % ", ".join(c["layers"]))
        for m in r["methods"] + r["fields"]:
            print(_sep())
            print("%s MAP" % m["kind"].upper())
            print("  MCP      %s" % (m["MCP"] or "(unnamed in snapshot CSV)"))
            print("  SRG      %s" % m["SRG"])
            print("  NOTCH    %s" % (m["NOTCH"] or "-"))
            if m["descriptor"]:
                print("  DESC     %s" % m["descriptor"])
                print("           %s" % pretty_desc(m["descriptor"]))

    _emit(res, args.json, text)


def _print_edge_list(title, edges, who):
    print(_sep())
    print("%s (%d, direct bytecode references; virtual opcodes may hit "
          "runtime overrides)" % (title, len(edges)))
    for e in edges:
        cls = (e.get("caller_class") or e.get("callee_class")
               or e.get("class") or "?")
        member = e.get("srg") or e.get("name") or "?"
        mcp = e.get("mcp")
        label = "%s.%s" % (cls, mcp or member)
        print("  [%s] %-9s %-70s %s" % ((e.get("witness_layer")
                                         or e.get("layer") or "?")[:22],
                                        e.get("opcode", ""), label,
                                        e.get("descriptor", "")))


def cmd_callers(args):
    from symbol_index.query import Queries
    con = sqlite3.connect("file:%s?mode=ro" % args.db.replace("\\", "/"),
                          uri=True)
    qq = Queries(con)
    matches, ambiguous = qq.resolve_method(args.ident, layer=args.layer)
    if not matches:
        print("no method matches %r" % args.ident)
        return 1
    edges = qq.callers(matches[0]["identity_key"], depth=args.depth,
                       layer=args.layer, limit=args.limit)
    _emit(edges, args.json, lambda e: _print_edge_list(
        "CALLERS of %s (depth %d)" % (args.ident, args.depth), e, None))


def cmd_callees(args):
    from symbol_index.query import Queries
    con = sqlite3.connect("file:%s?mode=ro" % args.db.replace("\\", "/"),
                          uri=True)
    qq = Queries(con)
    matches, ambiguous = qq.resolve_method(args.ident, layer=args.layer)
    if not matches:
        print("no method matches %r" % args.ident)
        return 1
    edges = qq.callees(matches[0]["identity_key"], depth=args.depth,
                       layer=args.layer, limit=args.limit)
    _emit(edges, args.json, lambda e: _print_edge_list(
        "CALLEES of %s (depth %d)" % (args.ident, args.depth), e, None))


def cmd_fields(args):
    from symbol_index.query import Queries
    con = sqlite3.connect("file:%s?mode=ro" % args.db.replace("\\", "/"),
                          uri=True)
    cards = Queries(con).resolve_field(args.ident, layer=args.layer)
    _emit(cards, args.json, lambda cs: [(_print_field_card(c)) for c in cs])


def _print_field_card(c):
    print(_sep())
    n = c["name"]
    print("FIELD   %s.%s" % (c["canonical_class"], n["mcp"] or n["srg"]
                             or n["bytecode"]))
    print("  MCP      %s" % (n["mcp"] or "-"))
    print("  SRG      %s" % (n["srg"] or "-"))
    print("  NOTCH    %s" % (n["notch"] or "-"))
    print("  DESC     %s   ACCESS %s" % (c["descriptor"],
                                         pretty_access(c["access"])))
    if c["constant"]:
        print("  CONST    %s" % c["constant"])
    print("  LAYERS   %s" % ", ".join(c["layers"]))
    print("  ACCESS GRAPH  readers=%d writers=%d" % (c["readers_count"],
                                                     c["writers_count"]))


def _field_walk(args, is_get):
    from symbol_index.query import Queries
    con = sqlite3.connect("file:%s?mode=ro" % args.db.replace("\\", "/"),
                          uri=True)
    qq = Queries(con)
    cards = qq.resolve_field(args.ident, layer=args.layer)
    if not cards:
        print("no field matches %r" % args.ident)
        return 1
    edges = qq.field_readers(cards[0]["identity_key"], is_get,
                             layer=args.layer, limit=args.limit)
    title = "READERS of" if is_get else "WRITERS of"
    _emit(edges, args.json, lambda e: _print_edge_list(
        "%s %s" % (title, args.ident), e, None))
    return 0


def cmd_field_readers(args):
    return _field_walk(args, 1)


def cmd_field_writers(args):
    return _field_walk(args, 0)


def cmd_class(args):
    from symbol_index.query import Queries
    con = sqlite3.connect("file:%s?mode=ro" % args.db.replace("\\", "/"),
                          uri=True)
    qq = Queries(con)
    card = qq.class_card(args.ident, layer=args.layer)
    if card is None:
        print("no class matches %r" % args.ident)
        return 1

    def text(c):
        print(_sep())
        print("CLASS   %s" % c["canonical_name"])
        print("  NOTCH    %s" % (c["notch"] or "-"))
        print("  SUPER    %s" % (c["super"] or "-"))
        if c["interfaces"]:
            print("  IFACES   %s" % ", ".join(c["interfaces"]))
        print("  STATS    methods=%d (concrete %d) fields=%d subclasses=%d "
              "referenced_by=%d" % (c["method_count"], c["concrete_methods"],
                                    c["field_count"], c["subclasses_count"],
                                    c["referenced_by_classes"]))
        for l in c["layer_rows"]:
            print("    %-22s %-42s file=%s" % (
                l["layer"], (l["artifact"] or "")[:42],
                l["file_sha256"][:16] + "..."))

    _emit(card, args.json, text)
    if args.members:
        members = qq.class_members(args.ident, layer=args.layer)

        def text_m(m):
            print(_sep())
            print("METHODS")
            for r in m["methods"]:
                print("  %-34s %-58s [%s]" % (
                    (r["mcp"] or r["srg"] or r["bytecode_name"])[:34],
                    pretty_desc(r["descriptor"])[:58], r["layer"][:20]))
            print("FIELDS")
            for r in m["fields"]:
                const = ("=" + str(r["constant"])[:14])                     if r.get("constant") else ""
                print("  %-34s %-28s %-16s [%s]" % (
                    (r["mcp"] or r["srg"] or r["bytecode_name"])[:34],
                    r["descriptor"][:28], const, r["layer"][:20]))
        _emit(members, args.json, text_m)
    return 0


def cmd_refs(args):
    from symbol_index.query import Queries
    con = sqlite3.connect("file:%s?mode=ro" % args.db.replace("\\", "/"),
                          uri=True)
    rows = Queries(con).refs_to_class(args.ident, layer=args.layer,
                                      limit=args.limit)
    if rows is None:
        print("no class matches %r" % args.ident)
        return 1

    def text(r):
        print(_sep())
        print("CLASSES referencing %s (type instructions + call/field "
              "owners), %d" % (args.ident, len(r)))
        for x in r:
            print("  %4d  %s" % (x["count"], x["caller_class"]))
    _emit(rows, args.json, text)


def cmd_strings(args):
    from symbol_index.query import Queries
    con = sqlite3.connect("file:%s?mode=ro" % args.db.replace("\\", "/"),
                          uri=True)
    rows = Queries(con).strings(args.pattern, regex=args.regex,
                                layer=args.layer, limit=args.limit)
    _emit(rows, args.json, lambda r: (_print_edge_list(
        "STRING %r" % args.pattern, r, None)))


def cmd_class_fields(args):
    from symbol_index.query import Queries
    con = sqlite3.connect("file:%s?mode=ro" % args.db.replace("\\", "/"),
                          uri=True)
    members = Queries(con).class_members(args.ident, layer=args.layer,
                                         kind="fields")
    if members is None:
        print("no class matches %r" % args.ident)
        return 1
    def render_fields(fs):
        for f in fs:
            raw = f.get("constant")
            if raw:
                kind, _, val = raw.partition(":")
                const = "=%s" % val if kind in ("int", "long") \
                    else "=%r" % val
            else:
                const = ""
            print("  %-36s %-30s %-14s [%s]" % (
                (f["mcp"] or f["srg"] or f["bytecode_name"])[:36],
                f["descriptor"][:30], const[:14], f["layer"][:22]))
    _emit(members["fields"], args.json, render_fields)
    return 0


def cmd_constant(args):
    from symbol_index.query import Queries
    con = sqlite3.connect("file:%s?mode=ro" % args.db.replace("\\", "/"),
                          uri=True)
    rows = Queries(con).constants(args.value, layer=args.layer,
                                  limit=args.limit)
    _emit(rows, args.json, lambda r: (_print_edge_list(
        "CONSTANT %s" % args.value, r, None)))


def cmd_overrides(args):
    from symbol_index.query import Queries
    con = sqlite3.connect("file:%s?mode=ro" % args.db.replace("\\", "/"),
                          uri=True)
    qq = Queries(con)
    matches, _amb = qq.resolve_method(args.ident, layer=args.layer)
    if not matches:
        print("no method matches %r" % args.ident)
        return 1
    overs = qq.find_overrides(matches[0]["identity_key"], limit=args.limit)
    _emit(overs, args.json, lambda o: (_print_edge_list(
        "RUNTIME-OVERRIDE SITES of %s" % args.ident, [
            {"caller_class": x["class"], "srg": x["bytecode_name"],
             "opcode": "override", "descriptor": "",
             "witness_layer": x["layer"]} for x in o], None)))


def _print_provenance_warnings(prov):
    for w in prov["warnings"]:
        print("PROVENANCE WARNING: %s" % w)
    if prov["warnings"]:
        print("(use --strict-provenance to make this exit non-zero; see "
              "docs/engineering/BYTECODE_SYMBOL_INDEX.md)")


def cmd_forge_changes(args):
    from symbol_index.query import Queries
    con = sqlite3.connect("file:%s?mode=ro" % args.db.replace("\\", "/"),
                          uri=True)
    canon = Queries(con).canon_class(args.ident) if args.ident else None
    changed, client_only, prov = Queries(con).forge_changes(canon)

    def text(r):
        print(_sep())
        _print_provenance_warnings(r[2])
        print("FORGE-PATCHED METHODS (code hash differs VANILLA_NOTCH vs "
              "FORGE_PATCHED): %d" % len(r[0]))
        for c in r[0]:
            print("  %-46s %-34s %s" % (c["class"].split("/")[-1],
                                        (c["mcp"] or c["srg"]
                                         or c["name"])[:34],
                                        c["descriptor"][:40]))
        if r[1]:
            print("present only in FORGE_PATCHED (client-side or new): %d "
                  "(showing 20)" % len(r[1]))
            for c in r[1][:20]:
                print("  + %s.%s" % (c["class"], c["mcp"] or c["srg"]))
    _emit([changed, client_only, prov], args.json, text)
    if args.strict_provenance and prov["warnings"]:
        return 2
    return 0


def cmd_live_diff(args):
    from symbol_index.query import Queries
    con = sqlite3.connect("file:%s?mode=ro" % args.db.replace("\\", "/"),
                          uri=True)
    qq = Queries(con)
    canon = qq.canon_class(args.ident) if args.ident else None
    changed, injected, prov = qq.live_diff(canon, base_layer=args.base)

    def text(r):
        print(_sep())
        _print_provenance_warnings(r[2])
        print("LIVE-TRANSFORMED vs %s (runtime-defined bytecode that "
              "differs — vs VANILLA this includes Forge's runtime-applied "
              "binary patches; use --base FORGE_PATCHED to isolate "
              "coremod deltas): %d" % (args.base, len(r[0])))
        for c in r[0]:
            print("  %-46s %-34s %s" % (c["class"].split("/")[-1],
                                        (c["mcp"] or c["srg"]
                                         or c["name"])[:34],
                                        c["descriptor"][:40]))
        if r[1]:
            print("defined at runtime but absent from base: %d" % len(r[1]))
            for c in r[1][:20]:
                print("  + %s.%s" % (c["class"], c["mcp"] or c["srg"]
                                     or c["name"]))
    _emit([changed, injected, prov], args.json, text)
    if args.strict_provenance and prov["warnings"]:
        return 2
    return 0


def cmd_provenance(args):
    from symbol_index.query import Queries
    con = sqlite3.connect("file:%s?mode=ro" % args.db.replace("\\", "/"),
                          uri=True)
    layers = [r[0] for r in con.execute(
        "SELECT DISTINCT layer FROM artifacts ORDER BY layer")]

    def text(_):
        print(_sep())
        print("PROVENANCE — per-layer runtime identity (goal §31: never "
              "compare runtimes of different provenance silently)")
        for layer in layers:
            for a in Queries(con).layer_provenance(layer):
                print("  %-22s %-44s mc=%s forge=%s" % (
                    layer, a["name"][:44], a["mc_version"] or "-",
                    a["forge_version"] or "-"))
                if a["campaign_id"] or a["session_id"]:
                    print("  %-22s campaign=%s session=%s" % (
                        "", a["campaign_id"] or "-",
                        a["session_id"] or "-"))
                print("  %-22s sha256=%s" % ("", a["sha256"]))
                print("  %-22s %s" % ("", a["path"]))
    _emit(None, args.json, text)
    return 0


def cmd_signals(args):
    from symbol_index.query import Queries
    con = sqlite3.connect("file:%s?mode=ro" % args.db.replace("\\", "/"),
                          uri=True)
    qq = Queries(con)
    rows, ambiguous, base = qq.override_signals(args.ident,
                                                limit=args.limit)
    if ambiguous:
        print("AMBIGUOUS identifier — %d identities; qualify with class "
              "and/or descriptor:" % len(ambiguous))
        for a in ambiguous[:20]:
            print("  %s.%s  %s" % (a["canonical_class"], a["member"],
                                   pretty_desc(a["descriptor"])))
        return 2
    if not rows:
        if base is not None:
            print("method %s resolves, but no subclass overrides it in "
                  "the indexed universe (add mod jars to the index to see "
                  "mod overrides)" % args.ident)
            return 0
        print("no method matches %r (or no overrides)" % args.ident)
        return 1

    def text(r):
        print(_sep())
        print("OVERRIDE SIGNALS for %s (%s) — %d overrides; suggested "
              "classes are deterministic keyword rules over call/field "
              "evidence, NOT semantic understanding (goal §18: verify "
              "before admitting to authority)"
              % (args.ident, pretty_desc(base["descriptor"]), len(r)))
        tally = {}
        for row in r:
            tally[row["suggested"]] = tally.get(row["suggested"], 0) + 1
        print("SUMMARY %s" % tally)
        for row in r:
            print("  %-52s [%s] -> %s" % (row["class"][:52],
                                          row["witness_layer"][:16],
                                          row["suggested"]))
            for sig in row["signals"]:
                print("      %s" % sig[:110])
    _emit(rows, args.json, text)
    return 0


def cmd_body(args):
    from symbol_index.query import Queries
    from symbol_index.body import show_body
    con = sqlite3.connect("file:%s?mode=ro" % args.db.replace("\\", "/"),
                          uri=True)
    matches, ambiguous = Queries(con).resolve_method(args.ident,
                                                     layer=args.layer)
    if ambiguous:
        print("AMBIGUOUS identifier — %d identities; qualify with class "
              "and/or descriptor:" % len(ambiguous))
        for a in ambiguous[:20]:
            print("  %s.%s  %s" % (a["canonical_class"], a["member"],
                                   pretty_desc(a["descriptor"])))
        return 2
    if not matches:
        print("no method matches %r" % args.ident)
        return 1
    return show_body(con, matches, layer=args.layer, raw=args.raw,
                     max_insns=args.max, calls_only=args.calls_only)


def cmd_reflect(args):
    from symbol_index.query import Queries
    con = sqlite3.connect("file:%s?mode=ro" % args.db.replace("\\", "/"),
                          uri=True)
    res = Queries(con).reflect_member(args.ident, layer=args.layer)
    if res is None:
        print("no field matches %r" % args.ident)
        return 1

    def text(r):
        print(_sep())
        print("REFLECT %s" % r["member"])
        print("  DECLARED  %s" % (r["declared_type"] or r["descriptor"]))
        print("            (raw descriptor %s)" % r["descriptor"])
        h = r.get("hierarchy") or {}
        if h.get("supers"):
            print("  SUPERS    %s" % " <- ".join(h["supers"]))
        if h.get("indexed_subclasses"):
            print("  SUBCLASSES (indexed; Forge assigns subclass instances "
                  "into vanilla-declared fields):")
            for sub in h["indexed_subclasses"][:12]:
                print("    %s" % sub)
        if r["methods"]:
            print("  CALLABLE ON THE TYPE FAMILY (%d; canonical names):"
                  % len(r["methods"]))
            for m in r["methods"][:40]:
                print("    %-42s %-58s %s" % (
                    (m["mcp"] or m["srg"] or m["bytecode"])[:42],
                    m["descriptor"][:58], m["class"].split(".")[-1]))

    _emit(res, args.json, text)
    return 0


def cmd_mixins(args):
    from symbol_index.query import Queries
    con = sqlite3.connect("file:%s?mode=ro" % args.db.replace("\\", "/"),
                          uri=True)
    res = Queries(con).mixins_into(args.target, layer=args.layer,
                                   limit=args.limit)
    if res is None:
        print("no class matches %r" % args.target)
        return 1

    def text(r):
        print(_sep())
        print("MIXINS into %s — %d @Mixin classes (from indexed jars); "
              "the §10 question answered from the index"
              % (r["target"], len(r["mixins"])))
        for m in r["mixins"]:
            print("  %-58s [%s] kind=%s priority=%s indexed=%s"
                  % (m["mixin_class"][:58], m["layer"][:16], m["kind"],
                     m["priority"] if m["priority"] is not None else "-",
                     "yes" if m["mixin_class_indexed"] else "no"))
        if not r["mixins"] and not r["configs"]:
            print("  (no mixin wiring in the indexed universe — add mod "
                  "jars via a mods_dir spec)")
        if r["configs"]:
            print("REGISTERED CONFIGS (*.mixins.json resources)")
            for c in r["configs"]:
                print("  [%s] %-46s package=%s mixins=%d"
                      % (c["layer"][:14], c["config"][:46],
                         c["package"] or "-", c["mixin_count"] or 0))
    _emit(res, args.json, text)
    return 0


def cmd_search(args):
    from symbol_index.query import Queries
    con = sqlite3.connect("file:%s?mode=ro" % args.db.replace("\\", "/"),
                          uri=True)
    rows = Queries(con).search(args.terms, limit=args.limit)

    def text(r):
        print(_sep())
        print("SEARCH %s — deterministic token ranking, not semantic "
              "understanding (top %d)" % (args.terms, len(r)))
        for hit in r:
            if hit["kind"] == "method":
                print("  %4d  M  %-52s %-40s %s" % (
                    hit["score"], (hit["class"] + "." +
                                   (hit["mcp"] or hit["srg"] or ""))[:52],
                    hit["descriptor"][:40], hit["terms_hit"]))
            else:
                print("  %4d  S  %-52s %r" % (hit["score"],
                                              hit["class"], hit["value"]))
    _emit(rows, args.json, text)


def cmd_status(args):
    con = sqlite3.connect("file:%s?mode=ro" % args.db.replace("\\", "/"),
                          uri=True)

    def text(_):
        meta = dict(con.execute("SELECT key, value FROM meta"))
        print("db        %s" % args.db)
        print("schema    %s   built %s   tool %s" % (
            meta.get("schema_version"), meta.get("built_at"),
            meta.get("tool_version")))
        print("mc        %s" % meta.get("mc_version"))
        for r in con.execute("SELECT layer, name, class_count, method_count,"
                             " field_count, error_count, sha256, path,"
                             " mc_version, forge_version FROM artifacts"
                             " ORDER BY id"):
            print("artifact  %-22s %-44s cls=%-6d mth=%-7d fld=%-6d err=%d" % (
                r[0], r[1][:44], r[2], r[3], r[4], r[5]))
            print("          mc=%s forge=%s" % (r[8] or "-", r[9] or "-"))
            print("          sha256=%s" % r[6])
            print("          %s" % r[7])
        for r in con.execute("SELECT name, version, sha256, path FROM "
                             "mapping_sources ORDER BY id"):
            print("mapping   %-46s %s" % (r[0], r[1]))
            print("          sha256=%s" % r[2])
        print("counts    %s" % dict(
            (t, con.execute("SELECT COUNT(*) FROM %s" % t).fetchone()[0])
            for t in ("classes", "methods", "fields", "calls",
                      "field_accesses", "type_refs", "string_consts",
                      "num_consts", "hierarchy")))
    if not os.path.exists(args.db):
        print("no index at %s — run: python tools/symbols/"
              "rustcraft_symbols.py build" % args.db)
        return 1
    _emit(None, args.json, text)
    return 0


def cmd_sql(args):
    con = sqlite3.connect("file:%s?mode=ro" % args.db.replace("\\", "/"),
                          uri=True)
    con.execute("PRAGMA query_only=ON")
    cur = con.execute(args.query)
    rows = cur.fetchmany(args.limit)
    if args.json:
        print(json.dumps(rows, indent=1, default=str))
        return 0
    for row in rows:
        print("\t".join("" if v is None else str(v) for v in row))
    return 0


# ----------------------------------------------------------------------

def main(argv=None):
    ap = argparse.ArgumentParser(
        prog="rustcraft-symbols",
        description="Minecraft 1.12.2 / Forge bytecode + mapping navigator")
    ap.add_argument("--db", default=DEFAULT_DB,
                    help="index database (default: %(default)s)")
    ap.add_argument("--json", action="store_true", help="JSON output")
    sub = ap.add_subparsers(dest="cmd", required=True)

    p = sub.add_parser("build", help="build/rebuild the index")
    p.add_argument("--sources", help="sources JSON (default: built-in)")
    p.add_argument("--layer", action="append",
                   help="index only these layers (repeatable)")
    p.add_argument("--append", action="store_true",
                   help="add layers to an existing index instead of "
                        "rebuilding")
    p.add_argument("--incremental", action="store_true",
                   help="per-artifact SHA cache: reuse unchanged artifacts "
                        "from the previous index (full rebuild on mappings "
                        "or builder-version change)")
    p.set_defaults(fn=cmd_build)

    p = sub.add_parser("sources", help="show resolved sources + hashes")
    p.add_argument("--sources")
    p.set_defaults(fn=cmd_sources)

    p = sub.add_parser("method", help="exact method card")
    p.add_argument("ident")
    p.add_argument("--layer")
    p.set_defaults(fn=cmd_method)

    p = sub.add_parser("map", help="cross-name lookup (MCP/SRG/notch)")
    p.add_argument("name")
    p.set_defaults(fn=cmd_map)

    p = sub.add_parser("callers", help="direct bytecode callers")
    p.add_argument("ident")
    p.add_argument("--depth", type=int, default=1)
    p.add_argument("--layer")
    p.add_argument("--limit", type=int, default=200)
    p.set_defaults(fn=cmd_callers)

    p = sub.add_parser("callees", help="direct bytecode callees")
    p.add_argument("ident")
    p.add_argument("--depth", type=int, default=1)
    p.add_argument("--layer")
    p.add_argument("--limit", type=int, default=200)
    p.set_defaults(fn=cmd_callees)

    p = sub.add_parser("field", help="field card (all names, access graph)")
    p.add_argument("ident")
    p.add_argument("--layer")
    p.set_defaults(fn=cmd_fields)

    p = sub.add_parser("field-readers", help="methods reading a field")
    p.add_argument("ident")
    p.add_argument("--layer")
    p.add_argument("--limit", type=int, default=200)
    p.set_defaults(fn=cmd_field_readers)

    p = sub.add_parser("field-writers", help="methods writing a field")
    p.add_argument("ident")
    p.add_argument("--layer")
    p.add_argument("--limit", type=int, default=200)
    p.set_defaults(fn=cmd_field_writers)

    p = sub.add_parser("fields", help="list a class's fields")
    p.add_argument("ident")
    p.add_argument("--layer")
    p.set_defaults(fn=cmd_class_fields)

    p = sub.add_parser("class", help="class card")
    p.add_argument("ident")
    p.add_argument("--layer")
    p.add_argument("--members", action="store_true")
    p.set_defaults(fn=cmd_class)

    p = sub.add_parser("refs", help="classes referencing this class")
    p.add_argument("ident")
    p.add_argument("--layer")
    p.add_argument("--limit", type=int, default=300)
    p.set_defaults(fn=cmd_refs)

    p = sub.add_parser("strings", help="string constant search")
    p.add_argument("pattern")
    p.add_argument("--regex", action="store_true")
    p.add_argument("--layer")
    p.add_argument("--limit", type=int, default=100)
    p.set_defaults(fn=cmd_strings)

    p = sub.add_parser("constant", help="numeric/ConstantValue search")
    p.add_argument("value")
    p.add_argument("--layer")
    p.add_argument("--limit", type=int, default=100)
    p.set_defaults(fn=cmd_constant)

    p = sub.add_parser("overrides", help="subclass override sites")
    p.add_argument("ident")
    p.add_argument("--layer")
    p.add_argument("--limit", type=int, default=200)
    p.set_defaults(fn=cmd_overrides)

    p = sub.add_parser("forge-changes", help="methods Forge binary-patched")
    p.add_argument("ident", nargs="?")
    p.add_argument("--strict-provenance", action="store_true",
                   help="exit 2 when the compared layers have different "
                        "Forge-version provenance (goal-doc §31 rule)")
    p.set_defaults(fn=cmd_forge_changes)

    p = sub.add_parser("live-diff", help="runtime-defined vs shipped bytes")
    p.add_argument("ident", nargs="?")
    p.add_argument("--base", default="VANILLA_NOTCH",
                   help="comparison base layer (default VANILLA_NOTCH; use "
                        "FORGE_PATCHED to isolate coremod-only deltas)")
    p.add_argument("--strict-provenance", action="store_true",
                   help="exit 2 when the compared layers have different "
                        "Forge-version provenance (goal-doc §31 rule)")
    p.set_defaults(fn=cmd_live_diff)

    p = sub.add_parser("provenance",
                       help="per-layer runtime identity table (mc/forge "
                            "versions, hashes)")
    p.set_defaults(fn=cmd_provenance)

    p = sub.add_parser("signals",
                       help="per-override evidence + suggested "
                            "classification (STATIC/WORLD/TILEENTITY/...)")
    p.add_argument("ident")
    p.add_argument("--limit", type=int, default=200)
    p.set_defaults(fn=cmd_signals)

    p = sub.add_parser("body",
                       help="ordered method-body inventory from the "
                            "artifact bytes (the javap replacement)")
    p.add_argument("ident")
    p.add_argument("--layer",
                   help="which layer's bytes to read (default: "
                        "LIVE_TRANSFORMED > FORGE_PATCHED > VANILLA_NOTCH)")
    p.add_argument("--raw", action="store_true",
                   help="bytecode names as-is instead of canonical")
    p.add_argument("--calls-only", action="store_true",
                   help="deduped counted call inventory (the seam-finding "
                        "view) instead of the ordered stream")
    p.add_argument("--max", type=int, default=400)
    p.set_defaults(fn=cmd_body)

    p = sub.add_parser("mixins",
                       help="who mixins into this class (@Mixin targets "
                            "from the indexed jars)")
    p.add_argument("target")
    p.add_argument("--layer")
    p.add_argument("--limit", type=int, default=200)
    p.set_defaults(fn=cmd_mixins)

    p = sub.add_parser("reflect",
                       help="reflection contract of a field: declared type, "
                            "hierarchy, callable method inventory")
    p.add_argument("ident")
    p.add_argument("--layer")
    p.set_defaults(fn=cmd_reflect)

    p = sub.add_parser("search", help="ranked token search")
    p.add_argument("terms", nargs="+")
    p.add_argument("--limit", type=int, default=25)
    p.set_defaults(fn=cmd_search)

    p = sub.add_parser("status", help="index provenance + counts")
    p.set_defaults(fn=cmd_status)

    p = sub.add_parser("sql", help="raw read-only SQL escape hatch")
    p.add_argument("query")
    p.add_argument("--limit", type=int, default=200)
    p.set_defaults(fn=cmd_sql)

    args = ap.parse_args(argv)
    if getattr(args, "fn", None) is cmd_build:
        pass
    elif not os.path.exists(args.db) and args.cmd != "build" \
            and args.cmd != "sources":
        print("no index at %s — run: python tools/symbols/"
              "rustcraft_symbols.py build" % args.db)
        return 1
    return args.fn(args) or 0


if __name__ == "__main__":
    sys.exit(main())
