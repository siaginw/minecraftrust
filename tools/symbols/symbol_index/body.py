"""On-demand ordered method body inventory — the javap replacement.

The recurring manual pattern in the light-authority work: `javap -c -p
-classpath <jar> amu > /tmp/amu.txt`, then awk/sed surgery to isolate one
method and see exactly which calls it makes in order (the opacity/emission
discovery, the Phosphor packing-code read). `symbols body` does that in one
command against the indexed artifacts, with canonical names:

    python tools/symbols/rustcraft_symbols.py body net.minecraft.world.World.func_180500_c
    python tools/symbols/rustcraft_symbols.py body net.minecraft.world.World.func_180500_c --layer FORGE_PATCHED --raw

- Resolves the method identity (all namespaces accepted, like `method`).
- Picks the layer row to read bytes from: --layer wins, else the first of
  LIVE_TRANSFORMED > FORGE_PATCHED > VANILLA_NOTCH > FORGE_PATCHED_MCP >
  anything (runtime-real bytes first).
- Re-parses just that one class file with the classfile parser and merges
  the per-fact lists by instruction offset into one ordered stream.
- Renders refs canonically (owner via classes.internal/notch_name; member
  via member_names) with `--raw` showing bytecode names as-is.

This reads bytes; it does not decompile. The output is the ordered call /
field / type / constant inventory — which is what every javap session in
the transcripts was actually after.
"""

import os
import re
import sqlite3
import zipfile

from .classfile import parse_class

_LAYER_PREFERENCE = ("LIVE_TRANSFORMED", "FORGE_PATCHED", "VANILLA_NOTCH",
                     "FORGE_PATCHED_MCP", "FORGE_MOD", "VANILLA_CLIENT_NOTCH",
                     "MOD_JAR")

_DESC_CLASS = re.compile(r"L([^;]+);")


class BodyReader:
    def __init__(self, con):
        self.con = con
        self._cls = {}       # internal/notch -> canonical
        self._member = {}    # (srg_class, srg_name) -> (mcp, notch, desc)
        self._member_by_notch = {}  # (notch_class, notch_name, desc) -> srg
        self._desc_canon = {}

    def _canon_class(self, internal):
        hit = self._cls.get(internal)
        if hit is None:
            row = self.con.execute(
                "SELECT canonical_name FROM classes WHERE internal_name=? "
                "OR notch_name=? LIMIT 1", (internal, internal)).fetchone()
            hit = row[0] if row else internal
            self._cls[internal] = hit
        return hit

    def _canon_desc(self, desc):
        hit = self._desc_canon.get(desc)
        if hit is None:
            hit = _DESC_CLASS.sub(
                lambda m: "L" + self._canon_class(m.group(1)) + ";", desc)
            self._desc_canon[desc] = hit
        return hit

    def _member_names(self, owner_internal, name, desc, kind="method"):
        """(display_name, canonical_desc) for a raw bytecode ref; name
        passthrough when unmapped. SRG-namespace names are unique per
        class; NOTCH single-letter names are heavily overloaded, so they
        must match on the canonical descriptor too."""
        owner = self._canon_class(owner_internal)
        cdesc = self._canon_desc(desc)
        key = (owner, name, cdesc, kind)
        hit = self._member.get(key)
        if hit is None:
            row = self.con.execute(
                "SELECT mcp_name, srg_name FROM member_names WHERE "
                "srg_class=? AND srg_name=? AND kind=? LIMIT 1",
                (owner, name, kind)).fetchone()
            if row is None and kind == "method":
                row = self.con.execute(
                    "SELECT mcp_name, srg_name FROM member_names WHERE "
                    "srg_class=? AND notch_name=? AND descriptor=? "
                    "LIMIT 1", (owner, name, cdesc)).fetchone()
            elif row is None:
                row = self.con.execute(
                    "SELECT mcp_name, srg_name FROM member_names WHERE "
                    "srg_class=? AND notch_name=? AND kind='field' "
                    "LIMIT 1", (owner, name)).fetchone()
            display = (row[0] or row[1]) if row else name
            hit = (display, cdesc)
            self._member[key] = hit
        return hit

    def load_bytes(self, artifact_id, class_internal):
        row = self.con.execute(
            "SELECT path, kind FROM artifacts WHERE id=?",
            (artifact_id,)).fetchone()
        if row is None:
            raise SystemExit("artifact %s not in index" % artifact_id)
        path, kind = row
        rel = class_internal + ".class"
        if kind == "jar":
            with zipfile.ZipFile(path) as zf:
                try:
                    return zf.read(rel)
                except KeyError:
                    # some dumps nest differently; search once
                    for n in zf.namelist():
                        if n.endswith("/" + rel) or n == rel:
                            return zf.read(n)
                    raise SystemExit("%s not found in %s" % (rel, path))
        full = os.path.join(path, *rel.split("/"))
        with open(full, "rb") as fh:
            return fh.read()

    def ordered_stream(self, m):
        """Merge the parser's per-fact lists into one offset-ordered list of
        (offset, kind, payload)."""
        events = []
        events += [(o, "invoke", (op, owner, name, desc, itf))
                   for o, op, owner, name, desc, itf in m.invocations]
        events += [(o, "field", (op, owner, name, desc, get, static))
                   for o, op, owner, name, desc, get, static in m.field_ops]
        events += [(o, "type", (op, cls)) for o, op, cls in m.type_refs]
        events += [(o, "string", v) for o, v in m.strings]
        events += [(o, "num", (k, v)) for o, k, v in m.nums]
        events.sort(key=lambda e: e[0])
        return events


def render_calls_only(reader, m, raw=False, max_insns=400):
    """Deduped counted call inventory - the seam-finding view (e.g.
    '6x World.getLightFor(EnumSkyBlock;BlockPos)I')."""
    counts = {}
    order = []
    for offset, kind, payload in reader.ordered_stream(m):
        if kind != "invoke":
            continue
        op, owner, name, desc, itf = payload
        if owner is None:
            key = ("invokedynamic", None, name, desc)
            disp = "%s%s" % (name, desc)
        else:
            if raw:
                odisp, ndisp, ddisp = owner, name, desc
            else:
                odisp = _dot(reader._canon_class(owner))
                ndisp, ddisp = reader._member_names(owner, name, desc,
                                                    kind="method")
            key = (op, odisp, ndisp, ddisp)
            disp = "%s.%s%s" % (odisp, ndisp, ddisp)
        if key not in counts:
            order.append((key, disp, op))
        counts[key] = counts.get(key, 0) + 1
    out = []
    for key, disp, op in sorted(order, key=lambda x: (-counts[x[0]],
                                                      x[1])):
        out.append("  %3dx  %-16s %s" % (counts[key], op, disp))
        if len(out) >= max_insns:
            break
    return out


def render_body(reader, m, raw=False, max_insns=400):
    out = []
    stream = reader.ordered_stream(m)

    def owner_display(owner):
        return owner if raw else _dot(reader._canon_class(owner))

    def member_display(owner_internal, name, kind="method", desc=""):
        if raw:
            return name
        display, _cd = reader._member_names(owner_internal, name, desc,
                                            kind=kind)
        return display

    for offset, kind, payload in stream:
        if len(out) >= max_insns:
            out.append("  ... (%d more)" % (len(stream) - max_insns))
            break
        if kind == "invoke":
            op, owner, name, desc, itf = payload
            if owner is None:
                out.append("  %6d  %-16s %s%s" % (offset, op, name, desc))
                continue
            disp = "%s.%s%s" % (
                owner_display(owner),
                member_display(owner, name, "method", desc),
                "" if raw else reader._canon_desc(desc))
            out.append("  %6d  %-16s %s" % (offset, op, disp))
        elif kind == "field":
            op, owner, name, desc, get, static = payload
            disp = "%s.%s" % (
                owner_display(owner),
                member_display(owner, name, "field", desc))
            out.append("  %6d  %-16s %s" % (offset, op, disp))
        elif kind == "type":
            op, cls = payload
            out.append("  %6d  %-16s %s" % (offset, op,
                                            cls if raw else _dot(cls)))
        elif kind == "string":
            out.append("  %6d  %-16s %r" % (offset, "ldc", payload))
        elif kind == "num":
            k, v = payload
            out.append("  %6d  %-16s %s (%s)" % (offset, "ldc2" if k in
                                                 ("long", "double")
                                                 else "ldc", v, k))
    return out


def _dot(internal):
    return internal.replace("/", ".")


def pick_row(rows, layer):
    """rows: resolve_method matches (list of cards)."""
    all_rows = [dict(lr, card=card) for card in rows
                for lr in card["layers"]]
    if layer:
        for r in all_rows:
            if r["layer"] == layer:
                return r
        raise SystemExit("layer %r not present for this method (have: %s)"
                         % (layer, sorted({r["layer"] for r in all_rows})))
    for pref in _LAYER_PREFERENCE:
        for r in all_rows:
            if r["layer"] == pref and r.get("code_len"):
                return r
    return all_rows[0]


def show_body(con, matches, layer=None, raw=False, max_insns=400,
              calls_only=False):
    """matches: resolve_method matches (non-empty). Picks the layer row,
    loads bytes, prints the ordered inventory. Returns exit code."""
    reader = BodyReader(con)
    row = pick_row(matches, layer)
    card = row["card"]
    data = reader.load_bytes(row["artifact_id"], row["class_internal"])
    cf = parse_class(data)
    # per-ROW bytecode name/descriptor: the card-level values come from the
    # first layer row (alphabetically first layer), which may be a different
    # namespace than the picked row (notch 'c' vs SRG func_180500_c)
    target = None
    for m in cf.methods:
        if m.name == row["name"] \
                and m.descriptor == row["descriptor_as_in_bytecode"]:
            target = m
            break
    if target is None:
        print("method %s %s not found in %s (name drift?)"
              % (row["name"], row["descriptor_as_in_bytecode"],
                 row["class_internal"]))
        return 1
    n = card["name"]
    print("=" * 78)
    print("BODY %s.%s   [%s / %s]" % (
        card["canonical_class"], n["mcp"] or n["srg"] or n["bytecode"],
        row["layer"], row["artifact"]))
    print("  bytecode: %s %s   code=%d bytes   sha=%s..."
          % (target.name, target.descriptor, target.code_len,
             (row["code_sha256"] or "")[:12]))
    if calls_only:
        print("  CALL INVENTORY (deduped, counted - the seam-finding view):")
        lines = render_calls_only(reader, target, raw=raw,
                                  max_insns=max_insns)
    else:
        print("  ordered inventory (canonical names; --raw for bytecode "
              "names):")
        lines = render_body(reader, target, raw=raw, max_insns=max_insns)
    for line in lines:
        print(line)
    print("=" * 78)
    return 0
