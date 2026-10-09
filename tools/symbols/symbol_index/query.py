"""Query engine for the symbol index. Functions return plain dicts so the
CLI can render text or JSON.

Identity model (see builder.py): a method is (canonical_class, canonical
member name, canonical_descriptor) across layers; `methods.identity_key`
groups the layer rows. Name resolution accepts MCP, SRG, notch or bytecode
names; descriptors are always displayed.
"""

import json
import re

from .format import parse_method_descriptor, simple_name

SEP = "\x00"

_DESC_CLASS = re.compile(r"L([^;]+);")


def _dot(internal):
    return internal.replace("/", ".")


def _class_internal(con, artifact_id, canonical):
    row = con.execute(
        "SELECT internal_name FROM classes WHERE canonical_name=? AND "
        "artifact_id=? LIMIT 1", (canonical, artifact_id)).fetchone()
    return row[0] if row else canonical


def _slash(dotted):
    return dotted.replace(".", "/")


class Queries:
    def __init__(self, con):
        self.con = con

    def q(self, sql, args=()):
        return list(self.con.execute(sql, args))

    # ------------------------------------------------------------------
    # name resolution

    def canon_class(self, hint):
        """Resolve a class hint (dotted or slashed, SRG/MCP or notch, or a
        unique short name like 'World') to a canonical internal name."""
        if not hint:
            return None
        cand = _slash(hint)
        row = self.q("SELECT canonical_name FROM classes WHERE "
                     "canonical_name=? LIMIT 1", (cand,))
        if row:
            return cand
        row = self.q("SELECT canonical_name FROM classes WHERE notch_name=? "
                     "LIMIT 1", (cand,))
        if row:
            return row[0][0]
        if "/" not in cand:
            like = "%/" + cand
            rows = self.q("SELECT DISTINCT canonical_name FROM classes "
                          "WHERE canonical_name LIKE ? LIMIT 2", (like,))
            if len(rows) == 1:
                return rows[0][0]
        return None

    def resolve_method(self, ident, layer=None):
        """ident -> list of method rows (grouped across layers).

        Accepted forms:
          pkg.to.Class.member[descriptor]   (dots or slashes, inner $ ok)
          pkg.to.Class.member
          member                            (bare: SRG searge or MCP name)
        Returns (matches, ambiguous_list). Matches are dicts.
        """
        desc_hint = None
        if "(" in ident:
            ident, _, raw_desc = ident.partition("(")
            ident = ident.strip()  # 'amu.c (Lana;Let;)Z' spaced form
            raw_desc = "(" + raw_desc
            pretty = _canonicalize_pretty_desc(raw_desc)
            if pretty:
                # typed forms may use ANY namespace for the argument types
                # (amu.c(Lana;Let;)Z): map each L-type through canon_class
                pretty = _DESC_CLASS.sub(
                    lambda m: "L" + (self.canon_class(m.group(1))
                                     or m.group(1)) + ";", pretty)
                # return type optional: '(...)Z' exact, '(...)' prefix match
                # (a descriptor with a return type never ends in ')')
                desc_hint = (pretty, not pretty.endswith(")"))
        class_hint, member = None, ident
        if "." in ident or "/" in ident:
            norm = ident.replace("/", ".").strip()
            parts = [p.strip() for p in norm.rsplit(".", 1)]
            cand_class = self.canon_class(parts[0])
            if cand_class is not None:
                class_hint, member = cand_class, parts[1]
        elif not ident.startswith(("func_", "field_")):
            # bare non-SRG name could still be 'World' style short class;
            # treat as member first, caller falls back to class search
            pass

        conds = ["(m.srg_name=? OR m.mcp_name=? OR m.notch_name=? OR m.name=?)"]
        args = [member, member, member, member]
        if class_hint:
            conds.append("c.canonical_name=?")
            args.append(class_hint)
        if layer:
            conds.append("a.layer=?")
            args.append(layer)
        rows = self.q(
            "SELECT m.id, m.identity_key, c.canonical_name, m.name, "
            "m.srg_name, m.mcp_name, m.notch_name, m.descriptor, "
            "m.canonical_descriptor, m.access, m.code_len, m.code_sha256, "
            "a.layer, a.name, a.id FROM methods m "
            "JOIN classes c ON c.id=m.class_id "
            "JOIN artifacts a ON a.id=m.artifact_id WHERE "
            + " AND ".join(conds) + " ORDER BY c.canonical_name, a.layer",
            args)
        if desc_hint is not None:
            want, exact = desc_hint
            if exact:
                rows = [r for r in rows if r[8] == want]
            else:
                rows = [r for r in rows if r[8].startswith(want)]
        if not rows:
            return [], []
        # group rows by identity to detect ambiguity
        identities = {}
        for r in rows:
            identities.setdefault(r[1], []).append(r)
        if len(identities) > 1 and not (class_hint and desc_hint):
            amb = [{"canonical_class": _dot(k.split(SEP)[0]),
                    "member": k.split(SEP)[1],
                    "descriptor": k.split(SEP)[2],
                    "layers": [_dot(rr[2]) for rr in v]}
                   for k, v in sorted(identities.items())]
            return [], amb
        return [self._method_card(v) for v in identities.values()], []

    def _method_card(self, layer_rows):
        first = layer_rows[0]
        ident = first[1]
        callers = self.q(
            "SELECT COUNT(DISTINCT k.caller_method_id) FROM calls k "
            "WHERE k.resolved_method_id IN "
            "(SELECT id FROM methods WHERE identity_key=?)", (ident,))[0][0]
        callees = self.q(
            "SELECT COUNT(*) FROM calls k WHERE k.caller_method_id IN "
            "(SELECT id FROM methods WHERE identity_key=?) "
            "AND k.owner_canonical IS NOT NULL", (ident,))[0][0]
        fa = self.q(
            "SELECT COUNT(*), SUM(is_get) FROM field_accesses "
            "WHERE caller_method_id IN "
            "(SELECT id FROM methods WHERE identity_key=?)", (ident,))[0]
        overrides = self.find_overrides(ident, limit=50)
        layers = [{"layer": r[12], "artifact": r[13], "name": r[3],
                   "code_len": r[10], "code_sha256": r[11],
                   "artifact_id": r[14],
                   "descriptor_as_in_bytecode": r[7],
                   "class_internal": _class_internal(
                       self.con, r[14], r[2])} for r in layer_rows]
        return {
            "identity_key": ident,
            "canonical_class": _dot(first[2]),
            "name": {"bytecode": first[3], "srg": first[4], "mcp": first[5],
                     "notch": first[6]},
            "descriptor": first[8],
            "descriptor_as_in_bytecode": first[7],
            "access": first[9],
            "layers": layers,
            "callers_count": callers,
            "callees_count": callees,
            "field_access_count": fa[0] or 0,
            "field_read_count": fa[1] or 0,
            "overrides": overrides,
        }

    # ------------------------------------------------------------------

    def find_overrides(self, identity_key, limit=50):
        cls, member, desc = identity_key.split(SEP)
        rows = self.q(
            "SELECT DISTINCT c.canonical_name, a.layer, m.name, m.srg_name "
            "FROM hierarchy h "
            "JOIN classes c ON c.canonical_name = h.class_name "
            "JOIN methods m ON m.class_id = c.id "
            "JOIN artifacts a ON a.id = m.artifact_id "
            "WHERE h.ancestor = ? AND m.canonical_descriptor = ? "
            "AND (m.srg_name = ? OR (m.srg_name IS NULL AND m.name = ?)) "
            "AND c.canonical_name != ? "
            "ORDER BY c.canonical_name LIMIT ?",
            (cls, desc, member, member, cls, limit))
        return [{"class": _dot(r[0]), "layer": r[1], "bytecode_name": r[2],
                 "srg": r[3]} for r in rows]

    # ------------------------------------------------------------------
    # reflection contract (the shape-assumption class: BLOCK_STATE_IDS is
    # declared ObjectIntIdentityMap but holds a RegistryNamespaced)

    def _type_method_inventory(self, type_internal, limit=400):
        rows = self.q(
            "SELECT DISTINCT c.canonical_name, m.name, m.srg_name, "
            "m.mcp_name, m.canonical_descriptor, m.access FROM methods m "
            "JOIN classes c ON c.id = m.class_id "
            "WHERE c.canonical_name = ? ORDER BY m.name LIMIT ?",
            (type_internal, limit))
        return [{"class": _dot(r[0]), "bytecode": r[1], "srg": r[2],
                 "mcp": r[3], "descriptor": r[4]} for r in rows]

    def reflect_member(self, ident, layer=None):
        """Full reflection contract for a field: declared type (canonical),
        its hierarchy (supers + indexed subclasses - Forge assigns subclass
        instances into vanilla-declared fields), and the callable method
        inventory across that type family."""
        cards = self.resolve_field(ident, layer=layer)
        if not cards:
            return None
        card = cards[0]
        desc = card["descriptor"]
        # parse the declared type out of the descriptor
        t = desc
        while t.startswith("["):
            t = t[1:]
        declared = t[1:-1] if t.startswith("L") and t.endswith(";") else None
        out = {"member": ident, "descriptor": desc,
               "declared_type": _dot(declared) if declared else None,
               "hierarchy": {}, "methods": []}
        if not declared:
            return out
        supers = []
        cur = declared
        for _ in range(8):
            rows = self.q("SELECT super_canonical FROM classes WHERE "
                          "canonical_name=? LIMIT 1", (cur,))
            if not rows or not rows[0][0]:
                break
            supers.append(rows[0][0])
            cur = rows[0][0]
        subs = [r[0] for r in self.q(
            "SELECT DISTINCT class_name FROM hierarchy WHERE ancestor=? "
            "ORDER BY class_name LIMIT 40", (declared,))]
        out["hierarchy"] = {
            "supers": [_dot(x) for x in supers],
            "indexed_subclasses": [_dot(x) for x in subs],
            "note": "subclass closure covers indexed artifacts only; "
                    "Forge-side branches may be incomplete. The runtime "
                    "class of a field is provable via field-writers -> "
                    "body of the writer (what gets PUT here).",
        }
        family = [declared] + subs
        seen = set()
        methods = []
        for t_internal in family:
            for m in self._type_method_inventory(t_internal):
                key = (m["class"], m["bytecode"], m["descriptor"])
                if key not in seen:
                    seen.add(key)
                    methods.append(m)
        out["methods"] = methods
        return out

    # ------------------------------------------------------------------
    # mixins (goal §10: "who mixins into this class" from the index)

    def mixins_into(self, target, layer=None, limit=200):
        """All @Mixin classes targeting `target` (any namespace), with the
        jar provenance and whether the mixin class is itself indexed."""
        canon = self.canon_class(target)
        if canon is None:
            return None
        conds = ["m.target_canonical=?"]
        args = [canon]
        art_join = "JOIN artifacts a ON a.id = m.artifact_id"
        if layer:
            conds.append("a.layer=?")
            args.append(layer)
        rows = self.q(
            "SELECT m.mixin_class, m.kind, m.target_raw, m.priority, "
            "a.layer, a.name, "
            "EXISTS(SELECT 1 FROM classes c WHERE c.canonical_name = "
            "m.mixin_class) AS mixin_indexed "
            "FROM mixins m " + art_join + " WHERE " + " AND ".join(conds)
            + " ORDER BY a.layer, m.mixin_class LIMIT ?", args + [limit])
        configs = self.q(
            "SELECT DISTINCT a.layer, a.name, k.config, k.package, "
            "k.mixin_count FROM mixin_configs k "
            "JOIN artifacts a ON a.id = k.artifact_id ORDER BY a.layer")
        return {
            "target": _dot(canon),
            "mixins": [{"mixin_class": _dot(r[0]), "kind": r[1],
                        "target_raw": r[2], "priority": r[3],
                        "layer": r[4], "artifact": r[5],
                        "mixin_class_indexed": bool(r[6])} for r in rows],
            "configs": [{"layer": r[0], "artifact": r[1], "config": r[2],
                         "package": r[3], "mixin_count": r[4]}
                        for r in configs],
        }

    def method_ids_for_identity(self, identity_key):
        return [r[0] for r in self.q(
            "SELECT id FROM methods WHERE identity_key=?", (identity_key,))]

    # ------------------------------------------------------------------
    # per-method evidence signals (goal §17/§18: opacity/emission audit)

    # JDK packages that don't count as semantic dependency signals
    _JDK_PREFIX = ("java/", "javax/", "sun/", "com/sun/", "jdk/", "org/ow2/")

    _WORLD_CALL_RE = re.compile(
        r"(getLightValue|getLightOpacity|getLightFrom|getLight|getBlock|"
        r"getTileEntity|getStrongPower|isNormalCube|getBlockState|"
        r"getBiome|getFluid|hasCapability|getBlockMetadata)$")
    _WORLD_OWNERS = ("net/minecraft/world/World", "net/minecraft/world/"
                     "IBlockAccess", "net/minecraft/world/IBlockReader")

    def method_evidence(self, method_id):
        """Raw per-method evidence: deduped calls, field accesses, type
        refs, strings — the signals a human (or agent) classifies."""
        calls = self.q(
            "SELECT DISTINCT k.opcode, k.owner_canonical, k.name, "
            "k.descriptor FROM calls k WHERE k.caller_method_id=? AND "
            "k.owner_canonical IS NOT NULL ORDER BY 1,2,3", (method_id,))
        fields = self.q(
            "SELECT DISTINCT k.opcode, k.owner_canonical, k.name FROM "
            "field_accesses k WHERE k.caller_method_id=? AND "
            "k.owner_canonical IS NOT NULL ORDER BY 1,2,3", (method_id,))
        types = self.q(
            "SELECT DISTINCT t.class_name FROM type_refs t WHERE "
            "t.method_id=? ORDER BY 1", (method_id,))
        strings = self.q(
            "SELECT DISTINCT s.value FROM string_consts s WHERE "
            "s.method_id=? ORDER BY 1 LIMIT 20", (method_id,))
        return {
            "calls": [{"opcode": c[0], "owner": _dot(c[1]), "name": c[2]}
                      for c in calls],
            "field_accesses": [{"owner": _dot(f[1]), "name": f[2]}
                               for f in fields],
            "type_refs": [_dot(t[0]) for t in types],
            "strings": [s[0] for s in strings],
        }

    def classify_evidence(self, ev, own_class, member=None):
        """Deterministic keyword rules over evidence — signals with a
        suggested class, NOT semantic understanding (goal §18: verify
        before admitting to authority)."""
        world_calls = [c for c in ev["calls"]
                       if not (member is not None and c["name"] == member
                               and c["opcode"] == "invokespecial")
                       and (self._WORLD_CALL_RE.search(c["name"] or "")
                            or c["owner"].replace(".", "/")
                            in self._WORLD_OWNERS)]
        non_jdk_calls = [c for c in ev["calls"]
                         if not c["owner"].replace(".", "/")
                         .startswith(self._JDK_PREFIX)]
        tileentity = ("getTileEntity" in {c["name"] for c in ev["calls"]}
                      or any("TileEntity" in t for t in ev["type_refs"]))
        own_fields = [f for f in ev["field_accesses"]
                      if f["owner"].replace(".", "/") == own_class]
        signals = []
        if tileentity:
            signals.append("TILEENTITY: getTileEntity call or TileEntity "
                           "type reference")
        if world_calls:
            names = sorted({c["name"] for c in world_calls})[:6]
            signals.append("WORLD/NEIGHBOR: world-aware calls %s" % names)
        if non_jdk_calls and not world_calls:
            others = sorted({(c["owner"], c["name"])
                             for c in non_jdk_calls})[:4]
            signals.append("OTHER-CALLS: %s" % others)
        if not non_jdk_calls and not world_calls and not tileentity:
            signals.append("no non-JDK calls (own fields/constants only)")
        if tileentity:
            suggested = "TILEENTITY_DEPENDENT"
        elif world_calls:
            suggested = "WORLD_DEPENDENT"
        elif non_jdk_calls or any(
                f["owner"].replace(".", "/") != own_class
                for f in ev["field_accesses"]):
            suggested = "UNKNOWN"
        else:
            suggested = "STATIC"
        return {"signals": signals, "suggested": suggested}

    def override_signals(self, ident, limit=200):
        """For every subclass override of <ident>: evidence + suggested
        classification. Returns (rows, ambiguous, base_identity)."""
        matches, ambiguous = self.resolve_method(ident)
        if ambiguous or not matches:
            return [], ambiguous, None
        # the same method may exist as several identities (e.g. an
        # MCP-named row from the recompiled jar and the SRG-named row mods
        # actually override) — use the one with the most overrides
        best = None
        for match in matches:
            rows = self._override_rows(match["identity_key"], limit)
            if best is None or len(rows) > len(best[0]):
                best = (rows, match)
            if rows:
                break
        rows, base = best
        out = []
        member_name = base["identity_key"].split(SEP)[1]
        for canon, layer, name, srg, ident_key, mdesc in rows:
            # pick the most complete body for evidence (largest code_len)
            mid = self.q(
                "SELECT m.id FROM methods m JOIN artifacts a ON "
                "a.id=m.artifact_id WHERE m.identity_key=? AND m.code_len "
                "IS NOT NULL ORDER BY m.code_len DESC, m.id ASC LIMIT 1",
                (ident_key,))
            ev = self.method_evidence(mid[0][0]) if mid else {
                "calls": [], "field_accesses": [], "type_refs": [],
                "strings": []}
            verdict = self.classify_evidence(ev, canon, member=member_name)
            out.append({"class": _dot(canon), "witness_layer": layer,
                        "suggested": verdict["suggested"],
                        "signals": verdict["signals"],
                        "evidence": ev})
        return out, ambiguous, base

    def _override_rows(self, identity_key, limit):
        cls, member, desc = identity_key.split(SEP)
        return self.q(
            "SELECT DISTINCT c.canonical_name, a.layer, m.name, m.srg_name, "
            "m.identity_key, m.canonical_descriptor FROM hierarchy h "
            "JOIN classes c ON c.canonical_name = h.class_name "
            "JOIN methods m ON m.class_id = c.id "
            "JOIN artifacts a ON a.id = m.artifact_id "
            "WHERE h.ancestor = ? AND m.canonical_descriptor = ? "
            "AND (m.srg_name = ? OR (m.srg_name IS NULL AND m.name = ?)) "
            "AND c.canonical_name != ? "
            "ORDER BY c.canonical_name LIMIT ?",
            (cls, desc, member, member, cls, limit))

    # ------------------------------------------------------------------
    # call graph

    def callers(self, identity_key, depth=1, layer=None, limit=200):
        seen = {identity_key}
        frontier = [identity_key]
        out = []
        for _ in range(max(1, depth)):
            nxt = []
            for ident in frontier:
                rows = self.q(
                    "SELECT DISTINCT c.canonical_name, m.identity_key, "
                    "m.srg_name, m.mcp_name, m.name, m.descriptor, "
                    "k.opcode, a.layer, a.name FROM calls k "
                    "JOIN methods m ON m.id = k.caller_method_id "
                    "JOIN classes c ON c.id = m.class_id "
                    "JOIN artifacts a ON a.id = k.artifact_id "
                    "WHERE k.resolved_method_id IN "
                    "(SELECT id FROM methods WHERE identity_key=?) "
                    "ORDER BY c.canonical_name, a.layer", (ident,))
                for r in rows:
                    out.append({"caller_class": _dot(r[0]),
                                "caller_identity": r[1],
                                "srg": r[2], "mcp": r[3], "name": r[4],
                                "descriptor": r[5], "opcode": r[6],
                                "witness_layer": r[7], "artifact": r[8],
                                "depth": _ + 1})
                    if r[1] not in seen:
                        seen.add(r[1])
                        nxt.append(r[1])
            frontier = nxt
        if layer:
            out = [o for o in out if o["witness_layer"] == layer]
        return out[:limit]

    def callees(self, identity_key, depth=1, layer=None, limit=200):
        seen = {identity_key}
        frontier = [identity_key]
        out = []
        for _ in range(max(1, depth)):
            nxt = []
            for ident in frontier:
                rows = self.q(
                    "SELECT DISTINCT k.owner_canonical, k.name, k.descriptor,"
                    " k.opcode, k.itf, m2.identity_key, a.layer, a.name "
                    "FROM calls k "
                    "JOIN methods m ON m.id = k.caller_method_id "
                    "JOIN artifacts a ON a.id = k.artifact_id "
                    "LEFT JOIN methods m2 ON m2.id = k.resolved_method_id "
                    "WHERE m.identity_key = ? "
                    "ORDER BY k.owner_canonical, k.name", (ident,))
                for r in rows:
                    out.append({"callee_class": _dot(r[0]) if r[0] else None,
                                "name": r[1], "descriptor": r[2],
                                "opcode": r[3], "interface": r[4],
                                "resolved": r[5] is not None,
                                "witness_layer": r[6], "artifact": r[7],
                                "depth": _ + 1})
                    if r[5] and r[5] not in seen:
                        seen.add(r[5])
                        nxt.append(r[5])
            frontier = nxt
        if layer:
            out = [o for o in out if o["witness_layer"] == layer]
        return out[:limit]

    # ------------------------------------------------------------------
    # fields

    def resolve_field(self, ident, layer=None):
        desc_hint = None
        if ":" in ident and ident.count(":") == 1 and not ident.startswith(
                "L"):
            pass  # not used; descriptor hints come via full form below
        class_hint, member = None, ident
        if "." in ident or "/" in ident:
            norm = ident.replace("/", ".").strip()
            parts = [p.strip() for p in norm.rsplit(".", 1)]
            cand_class = self.canon_class(parts[0])
            if cand_class is not None:
                class_hint, member = cand_class, parts[1]
        conds = ["(f.srg_name=? OR f.mcp_name=? OR f.notch_name=? OR f.name=?)"]
        args = [member, member, member, member]
        if class_hint:
            conds.append("c.canonical_name=?")
            args.append(class_hint)
        if layer:
            conds.append("a.layer=?")
            args.append(layer)
        rows = self.q(
            "SELECT f.identity_key, c.canonical_name, f.name, f.srg_name, "
            "f.mcp_name, f.notch_name, f.descriptor, f.canonical_descriptor, "
            "f.access, f.constant, a.layer, a.name FROM fields f "
            "JOIN classes c ON c.id=f.class_id "
            "JOIN artifacts a ON a.id=f.artifact_id WHERE "
            + " AND ".join(conds) + " ORDER BY c.canonical_name, a.layer",
            args)
        groups = {}
        for r in rows:
            groups.setdefault(r[0], []).append(r)
        cards = []
        for ident_key, group in sorted(groups.items()):
            g = group[0]
            readers = self.q(
                "SELECT COUNT(DISTINCT k.caller_method_id) FROM "
                "field_accesses k WHERE k.resolved_field_id IN "
                "(SELECT id FROM fields WHERE identity_key=?) AND k.is_get=1",
                (ident_key,))[0][0]
            writers = self.q(
                "SELECT COUNT(DISTINCT k.caller_method_id) FROM "
                "field_accesses k WHERE k.resolved_field_id IN "
                "(SELECT id FROM fields WHERE identity_key=?) AND k.is_get=0",
                (ident_key,))[0][0]
            cards.append({
                "identity_key": ident_key,
                "canonical_class": _dot(g[1]),
                "name": {"bytecode": g[2], "srg": g[3], "mcp": g[4],
                         "notch": g[5]},
                "descriptor": g[7], "access": g[8], "constant": g[9],
                "layers": sorted({r[10] for r in group}),
                "artifacts": sorted({r[11] for r in group}),
                "readers_count": readers, "writers_count": writers,
            })
        return cards

    def field_readers(self, identity_key, is_get, layer=None, limit=200):
        rows = self.q(
            "SELECT DISTINCT c.canonical_name, m.identity_key, m.srg_name, "
            "m.mcp_name, m.name, k.opcode, a.layer, a.name FROM "
            "field_accesses k "
            "JOIN methods m ON m.id = k.caller_method_id "
            "JOIN classes c ON c.id = m.class_id "
            "JOIN artifacts a ON a.id = k.artifact_id "
            "WHERE k.resolved_field_id IN "
            "(SELECT id FROM fields WHERE identity_key=?) AND k.is_get=? "
            "ORDER BY c.canonical_name", (identity_key, is_get))
        out = [{"caller_class": _dot(r[0]), "caller_identity": r[1],
                "srg": r[2], "mcp": r[3], "name": r[4], "opcode": r[5],
                "witness_layer": r[6], "artifact": r[7]} for r in rows]
        if layer:
            out = [o for o in out if o["witness_layer"] == layer]
        return out[:limit]

    # ------------------------------------------------------------------
    # classes

    def class_card(self, ident, layer=None):
        canon = self.canon_class(ident)
        if canon is None:
            return None
        conds = "c.canonical_name=?"
        args = [canon]
        if layer:
            conds += " AND a.layer=?"
            args.append(layer)
        rows = self.q(
            "SELECT c.id, c.artifact_id, c.internal_name, c.canonical_name, "
            "c.notch_name, c.access, c.super_canonical, c.interfaces, "
            "c.cf_version, c.file_sha256, c.source_file, a.layer, a.name "
            "FROM classes c JOIN artifacts a ON a.id=c.artifact_id "
            "WHERE " + conds, args)
        if not rows:
            return None
        subclasses = self.q(
            "SELECT COUNT(*) FROM hierarchy WHERE ancestor=?", (canon,))[0][0]
        methods = self.q(
            "SELECT COUNT(*), SUM(code_len IS NOT NULL) FROM methods "
            "WHERE class_id IN (SELECT id FROM classes WHERE canonical_name=?)",
            (canon,))[0]
        fields = self.q(
            "SELECT COUNT(*) FROM fields WHERE class_id IN (SELECT id FROM "
            "classes WHERE canonical_name=?)", (canon,))[0]
        referenced_by = self.q(
            "SELECT COUNT(DISTINCT t.caller_class_id) FROM type_refs t "
            "WHERE t.class_name=?", (canon,))[0][0]
        return {
            "canonical_name": _dot(canon),
            "notch": _dot(rows[0][4]) if rows[0][4] else None,
            "access": rows[0][5],
            "super": _dot(rows[0][6]) if rows[0][6] else None,
            "interfaces": [_dot(x) for x in json.loads(rows[0][7] or "[]")],
            "cf_version": rows[0][8],
            "subclasses_count": subclasses,
            "method_count": methods[0], "concrete_methods": methods[1],
            "field_count": fields,
            "referenced_by_classes": referenced_by,
            "layer_rows": [{"layer": r[11], "artifact": r[12],
                            "file_sha256": r[9],
                            "internal_name": r[2]} for r in rows],
        }

    def class_members(self, ident, layer=None, kind="all"):
        canon = self.canon_class(ident)
        if canon is None:
            return None
        out = {"methods": [], "fields": []}
        out = {"methods": [], "fields": []}
        if kind in ("all", "methods"):
            sql = ("SELECT m.identity_key, m.name, m.srg_name, m.mcp_name, "
                   "m.notch_name, m.canonical_descriptor, m.access, "
                   "m.code_len, m.code_sha256, a.layer, a.name "
                   "FROM methods m JOIN artifacts a ON a.id=m.artifact_id "
                   "JOIN classes c ON c.id=m.class_id "
                   "WHERE c.canonical_name=?")
            sargs = [canon]
            if layer:
                sql += " AND a.layer=?"
                sargs.append(layer)
            sql += " ORDER BY m.name, a.layer"
            for r in self.q(sql, sargs):
                out["methods"].append({
                    "identity_key": r[0], "bytecode_name": r[1], "srg": r[2],
                    "mcp": r[3], "notch": r[4], "descriptor": r[5],
                    "access": r[6], "code_len": r[7], "code_sha256": r[8],
                    "layer": r[9], "artifact": r[10]})
        if kind in ("all", "fields"):
            sql = ("SELECT f.identity_key, f.name, f.srg_name, f.mcp_name, "
                   "f.notch_name, f.canonical_descriptor, f.access, "
                   "f.constant, a.layer FROM fields f "
                   "JOIN artifacts a ON a.id=f.artifact_id "
                   "JOIN classes c ON c.id=f.class_id "
                   "WHERE c.canonical_name=?")
            sargs = [canon]
            if layer:
                sql += " AND a.layer=?"
                sargs.append(layer)
            sql += " ORDER BY f.name, a.layer"
            for r in self.q(sql, sargs):
                out["fields"].append({
                    "identity_key": r[0], "bytecode_name": r[1], "srg": r[2],
                    "mcp": r[3], "notch": r[4], "descriptor": r[5],
                    "access": r[6], "constant": r[7], "layer": r[8]})
        return out

    def refs_to_class(self, ident, layer=None, limit=300):
        """Classes referencing a class via type instructions, call targets
        or field ownership (union, with the strongest witness per class)."""
        canon = self.canon_class(ident)
        if canon is None:
            return None
        rows = self.q(
            "SELECT caller_class, MAX(cnt) FROM ("
            "SELECT c.canonical_name AS caller_class, COUNT(*) AS cnt "
            "FROM type_refs t JOIN methods m ON m.id=t.method_id "
            "JOIN classes c ON c.id=m.class_id "
            "WHERE t.class_name=? GROUP BY 1 "
            "UNION ALL "
            "SELECT c.canonical_name, COUNT(*) "
            "FROM calls k JOIN methods m ON m.id=k.caller_method_id "
            "JOIN classes c ON c.id=m.class_id "
            "WHERE k.owner_canonical=? GROUP BY 1 "
            "UNION ALL "
            "SELECT c.canonical_name, COUNT(*) "
            "FROM field_accesses k JOIN methods m ON m.id=k.caller_method_id "
            "JOIN classes c ON c.id=m.class_id "
            "WHERE k.owner_canonical=? GROUP BY 1"
            ") GROUP BY caller_class ORDER BY caller_class LIMIT ?",
            (canon, canon, canon, limit))
        return [{"caller_class": _dot(r[0]), "count": r[1]} for r in rows]

    # ------------------------------------------------------------------
    # strings / constants

    def strings(self, pattern, regex=False, layer=None, limit=100):
        if regex:
            rx = re.compile(pattern)
            rows = self.q("SELECT DISTINCT value FROM string_consts")
            vals = [r[0] for r in rows if rx.search(r[0] or "")]
        else:
            rows = self.q(
                "SELECT DISTINCT value FROM string_consts WHERE value LIKE ? "
                "LIMIT ?", ("%" + pattern + "%", limit * 4))
            vals = [r[0] for r in rows]
        out = []
        for v in sorted(vals)[:limit * 2]:
            holders = self.q(
                "SELECT c.canonical_name, m.identity_key, m.srg_name, "
                "m.mcp_name, a.layer FROM string_consts s "
                "JOIN methods m ON m.id=s.method_id "
                "JOIN classes c ON c.id=m.class_id "
                "JOIN artifacts a ON a.id=s.artifact_id WHERE s.value=? "
                "ORDER BY c.canonical_name LIMIT 40", (v,))
            for h in holders:
                if layer and h[4] != layer:
                    continue
                out.append({"string": v, "class": _dot(h[0]),
                            "identity_key": h[1], "srg": h[2], "mcp": h[3],
                            "layer": h[4]})
            if len(out) >= limit:
                break
        return out

    def constants(self, value, layer=None, limit=100):
        out = []
        for kind in ("int", "long", "float", "double"):
            rows = self.q(
                "SELECT n.value, c.canonical_name, m.identity_key, m.srg_name,"
                " m.mcp_name, a.layer FROM num_consts n "
                "JOIN methods m ON m.id=n.method_id "
                "JOIN classes c ON c.id=m.class_id "
                "JOIN artifacts a ON a.id=n.artifact_id "
                "WHERE n.kind=? AND n.value=? ORDER BY c.canonical_name "
                "LIMIT ?", (kind, value, limit))
            for r in rows:
                if layer and r[5] != layer:
                    continue
                out.append({"kind": kind, "value": r[0],
                            "class": _dot(r[1]), "identity_key": r[2],
                            "srg": r[3], "mcp": r[4], "layer": r[5]})
        for r in self.q(
                "SELECT f.constant, c.canonical_name, f.identity_key, "
                "a.layer FROM fields f JOIN classes c ON c.id=f.class_id "
                "JOIN artifacts a ON a.id=f.artifact_id "
                "WHERE f.constant LIKE ? LIMIT ?", ("%:" + value, limit)):
            out.append({"kind": "ConstantValue", "value": r[0],
                        "class": _dot(r[1]), "identity_key": r[2],
                        "layer": r[3]})
        return out[:limit]

    # ------------------------------------------------------------------
    # cross-name mapping lookup

    def map_name(self, ident):
        ident = ident.replace("/", ".").strip()
        results = {"classes": [], "methods": [], "fields": []}
        # class?
        canon = self.canon_class(ident)
        if canon:
            rows = self.q("SELECT DISTINCT canonical_name, notch_name FROM "
                          "classes WHERE canonical_name=?", (canon,))
            notch = rows[0][1] if rows else None
            layers = [r[0] for r in self.q(
                "SELECT DISTINCT a.layer FROM classes c JOIN artifacts a ON "
                "a.id=c.artifact_id WHERE c.canonical_name=?", (canon,))]
            results["classes"].append({"MCP": _dot(canon),
                                       "SRG": _dot(canon),
                                       "NOTCH": _dot(notch) if notch else None,
                                       "layers": layers})
        # member with class prefix?
        class_hint, member = None, ident
        if "." in ident:
            parts = ident.rsplit(".", 1)
            cand = self.canon_class(parts[0])
            if cand:
                class_hint, member = cand, parts[1]
        elif not ident:
            return results
        name_cond = "(srg_name=? OR mcp_name=? OR notch_name=?)"
        args = [member, member, member]
        where = name_cond
        if class_hint:
            where = "(%s AND srg_class=?)" % name_cond
            args.append(class_hint)
        for r in self.q("SELECT kind, srg_class, srg_name, mcp_name, "
                        "notch_class, notch_name, descriptor FROM "
                        "member_names WHERE " + where, args):
            entry = {"kind": r[0],
                     "MCP": _dot(r[1]) + "." + r[3] if r[3] else None,
                     "SRG": _dot(r[1]) + "." + r[2],
                     "NOTCH": (_dot(r[4]) + "." + r[5]) if r[4] else None,
                     "descriptor": r[6]}
            results["methods" if r[0] == "method" else "fields"].append(entry)
        return results

    # ------------------------------------------------------------------
    # diffs

    def _pair_diff(self, base_layer, cmp_layer, class_canon=None, limit=500):
        cond, args = "", []
        if class_canon:
            cond = " AND c.canonical_name=?"
            args = [class_canon]
        rows = self.q(
            "SELECT m.identity_key, c.canonical_name, m.srg_name, m.mcp_name,"
            " m.name, m.canonical_descriptor, m.code_sha256 FROM methods m "
            "JOIN classes c ON c.id=m.class_id "
            "JOIN artifacts a ON a.id=m.artifact_id WHERE a.layer=? " + cond,
            (cmp_layer,) + tuple(args))
        cmp_map = {r[0]: r for r in rows}
        rows = self.q(
            "SELECT m.identity_key, c.canonical_name, m.srg_name, m.mcp_name,"
            " m.name, m.canonical_descriptor, m.code_sha256 FROM methods m "
            "JOIN classes c ON c.id=m.class_id "
            "JOIN artifacts a ON a.id=m.artifact_id WHERE a.layer=? " + cond,
            (base_layer,) + tuple(args))
        changed, added = [], []
        for r in rows:
            other = cmp_map.get(r[0])
            if other is None:
                continue
            if (r[6] or "") != (other[6] or ""):
                changed.append({"class": _dot(r[1]), "srg": r[2],
                                "mcp": r[3], "name": r[4],
                                "descriptor": r[5],
                                "base_sha": r[6], "cmp_sha": other[6]})
        if not class_canon:
            base_keys = {r[0] for r in rows}
            for k, other in cmp_map.items():
                if k not in base_keys:
                    added.append({"class": _dot(other[1]), "srg": other[2],
                                  "mcp": other[3], "name": other[4],
                                  "descriptor": other[5]})
        provenance = self.provenance_check(base_layer, cmp_layer)
        return (changed[:limit],
                sorted(added, key=lambda x: (x["class"],
                                             x["srg"] or x["name"] or ""))[:limit],
                provenance)

    def layer_provenance(self, layer):
        rows = self.q(
            "SELECT name, path, sha256, mc_version, forge_version, "
            "campaign_id, session_id FROM artifacts WHERE layer=? "
            "ORDER BY id", (layer,))
        return [{"name": r[0], "path": r[1], "sha256": r[2],
                 "mc_version": r[3], "forge_version": r[4],
                 "campaign_id": r[5], "session_id": r[6]} for r in rows]

    def provenance_check(self, base_layer, cmp_layer):
        """§31 guard: flag when two layers come from incompatible runtimes.

        Warnings fire when:
        - both layers carry a forge_version and the versions differ
          (e.g. LIVE_TRANSFORMED captured under Forge 2860 vs FORGE_PATCHED
          built from 2847) — vanilla baselines carry no forge_version and
          are exempt;
        - both layers carry campaign/session provenance and they differ
          (runtime dumps from different campaigns are different runtimes).
        """
        base = self.layer_provenance(base_layer)
        cmp_ = self.layer_provenance(cmp_layer)
        base_fv = sorted({a["forge_version"] for a in base
                          if a["forge_version"]})
        cmp_fv = sorted({a["forge_version"] for a in cmp_
                         if a["forge_version"]})
        base_mc = {a["mc_version"] for a in base}
        cmp_mc = {a["mc_version"] for a in cmp_}
        base_cs = sorted({(a["campaign_id"], a["session_id"]) for a in base
                          if a["campaign_id"] or a["session_id"]})
        cmp_cs = sorted({(a["campaign_id"], a["session_id"]) for a in cmp_
                         if a["campaign_id"] or a["session_id"]})
        warnings = []
        if base_fv and cmp_fv and set(base_fv) != set(cmp_fv):
            warnings.append(
                "forge_version mismatch: %s=%s vs %s=%s — runtime-shape "
                "differences may reflect the Forge version skew, not real "
                "patches; compare against a same-version baseline"
                % (base_layer, "+".join(base_fv), cmp_layer,
                   "+".join(cmp_fv)))
        if base_mc and cmp_mc and base_mc != cmp_mc:
            warnings.append("mc_version mismatch: %s=%s vs %s=%s"
                            % (base_layer, ",".join(sorted(base_mc)),
                               cmp_layer, ",".join(sorted(cmp_mc))))
        if base_cs and cmp_cs and base_cs != cmp_cs:
            warnings.append(
                "campaign/session provenance mismatch: %s from %s vs %s "
                "from %s — different capture sessions are different "
                "runtimes" % (base_layer, base_cs, cmp_layer, cmp_cs))
        return {"base": base, "cmp": cmp_, "warnings": warnings}

    def forge_changes(self, class_canon=None):
        return self._pair_diff("VANILLA_NOTCH", "FORGE_PATCHED", class_canon)

    def live_diff(self, class_canon=None, base_layer="VANILLA_NOTCH"):
        return self._pair_diff(base_layer, "LIVE_TRANSFORMED", class_canon)

    # ------------------------------------------------------------------
    # ranked search

    ALIASES = {
        "light": ["light", "EnumSkyBlock", "LightEngine", "getLight",
                  "checkLight", "relight", "sky", "blocklight"],
        "collision": ["collision", "collide", "BoundingBox", "addCollision",
                      "isNormalCube", "CollisionRaster"],
        "region": ["RegionFile", "chunk", ".mca", "ChunkLoader",
                   "RegionFileCache"],
        "tick": ["tick", "onUpdate", "update", "func_72847_b"],
        "chunk": ["chunk", "Chunk", "ChunkProvider"],
        "save": ["save", "write", "ChunkIo", "AnvilChunkLoader"],
    }

    def search(self, terms, limit=25):
        words = []
        for t in terms:
            words.extend(t.lower().split())
        terms = words
        expanded = set(terms)
        for t in terms:
            expanded.update(a.lower() for a in self.ALIASES.get(t, []))
        scored = {}

        def add(key, score, payload):
            prev = scored.get(key)
            if prev is None or prev[0] < score:
                scored[key] = (score, payload)

        for term in expanded:
            like = "%" + term + "%"
            for r in self.q(
                    "SELECT m.identity_key, c.canonical_name, m.srg_name, "
                    "m.mcp_name, m.name, m.canonical_descriptor "
                    "FROM methods m JOIN classes c ON c.id=m.class_id "
                    "WHERE m.mcp_name LIKE ? OR m.srg_name LIKE ? "
                    "OR m.name LIKE ? OR c.canonical_name LIKE ? LIMIT 4000",
                    (like,) * 4):
                cls, srg, mcp = r[1].lower(), (r[2] or "").lower(), \
                    (r[3] or "").lower()
                raw = (r[4] or "").lower()
                score = 0
                if mcp == term or srg == term:
                    score += 100
                elif mcp.startswith(term) or srg.startswith(term):
                    score += 60
                elif term in mcp or term in srg:
                    score += 30
                elif term in raw:
                    score += 20
                if term in cls:
                    score += 15
                if score:
                    add(r[0], score, {"kind": "method",
                                      "class": _dot(r[1]), "srg": r[2],
                                      "mcp": r[3], "name": r[4],
                                      "descriptor": r[5],
                                      "terms_hit": term})
            for r in self.q("SELECT DISTINCT value FROM string_consts "
                            "WHERE value LIKE ? LIMIT 500", (like,)):
                holders = self.q(
                    "SELECT DISTINCT c.canonical_name, m.identity_key FROM "
                    "string_consts s JOIN methods m ON m.id=s.method_id "
                    "JOIN classes c ON c.id=m.class_id WHERE s.value=? "
                    "LIMIT 5", (r[0],))
                for h in holders:
                    if term in (r[0] or "").lower():
                        add(h[1], 12, {"kind": "string", "value": r[0],
                                       "class": _dot(h[0]),
                                       "terms_hit": term})
        ranked = sorted(scored.items(),
                        key=lambda kv: (-kv[1][0], kv[1][1].get("class", ""),
                                        kv[1][1].get("srg") or ""))
        return [dict(score=s, **p)
                for _k, (s, p) in ranked[:limit]]


def _canonicalize_pretty_desc(raw):
    """'(...; ...)Z' with dotted, slashed, bare or L-prefixed type names ->
    internal descriptor. Handles L-prefixed segments without double-prefix
    (the Block.getLightOpacity 3-arg overload exercise)."""
    if "(" not in raw or len(raw) < 3:
        return None
    if not raw.endswith(")"):
        # return type present: cut at the last ')' (the hint then
        # prefix-matches overloads; the return itself is not compared)
        if ")" not in raw:
            return None
        raw = raw[:raw.rindex(")") + 1]
    inner = raw[1:raw.index(")")]
    ret = raw[raw.index(")") + 1:]
    parts = []
    for seg in inner.replace(",", ";").split(";"):
        seg = seg.strip().replace(".", "/")
        if not seg:
            continue
        # typed descriptor forms arrive L-prefixed ('Lana;' for notch
        # 'ana'); bare forms ('EnumSkyBlock', 'BlockPos') get prefixed and
        # are resolved by the canon_class mapping below
        if seg.startswith("["):
            pass
        elif seg.startswith("L") and not seg.endswith(";"):
            pass
        else:
            seg = "L" + seg
        if seg.startswith("L") and not seg.endswith(";"):
            seg += ";"
        parts.append(seg)
    out = "(" + "".join(parts) + ")" + ret.replace(".", "/")
    return out
