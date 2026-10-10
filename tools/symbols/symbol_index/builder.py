"""Build the SQLite bytecode symbol index from configured sources.

Ingestion model
---------------
Every artifact (jar or class-directory) is parsed entry by entry with the
pure-Python classfile parser and inserted with its full provenance. Names are
canonicalized at build time according to the artifact's member namespace:

- notch artifacts (vanilla / binpatched jars): classes and members renamed via
  joined.srg; descriptors type-renamed notch -> SRG; bytecode is untouched
  (code-array hashes therefore compare across layers).
- srg artifacts (runtime dumps, Forge universal, mod jars): classes already
  canonical; func_/field_ names resolved to MCP via the snapshot CSVs.
- mcp artifacts (deobf recompiled jar): MCP member names reverse-resolved to
  SRG via the (class, mcp name, descriptor) triple.

Call/field edges store the raw reference (canonicalized owner/name/desc) and,
when the target is resolvable (same artifact first, then the anchor artifact),
the resolved canonical method/field row id. Identity across layers is the
identity_key (canonical class, canonical member name, canonical descriptor),
so "callers of X" unions every layer's witnesses without collapsing rows.
"""

import hashlib
import json
import os
import re
import sqlite3
import sys
import time
import zipfile

from . import schema
from .classfile import ClassFileError, parse_class
from .mappings import parse_joined_srg, parse_mcp_csv

_DESC_CLASS = re.compile(r"L([^;]+);")
_TOOL_VERSION = "1.1.0"


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def sha256_dir(path):
    h = hashlib.sha256()
    class_files = []
    for root, _dirs, names in os.walk(path):
        for name in names:
            if name.endswith(".class"):
                full = os.path.join(root, name)
                class_files.append((os.path.relpath(full, path), full))
    class_files.sort()
    for rel, full in class_files:
        h.update(rel.encode("utf-8"))
        h.update(sha256_file(full).encode("ascii"))
    return h.hexdigest(), len(class_files)


def rename_descriptor(desc, class_map):
    """Type-rename a descriptor with a notch->srg class map."""
    if not class_map or ";" not in desc or "L" not in desc:
        return desc
    return _DESC_CLASS.sub(
        lambda m: "L" + class_map.get(m.group(1), m.group(1)) + ";", desc)


_INSERTS = {
    "build_issues": ("artifact_id,entry,error", 3),
    "class_interfaces": ("class_id,iface", 2),
    "calls": ("caller_method_id,caller_class_id,artifact_id,opcode,"
              "owner_canonical,name,descriptor,itf,resolved_method_id", 9),
    "field_accesses": ("caller_method_id,caller_class_id,artifact_id,opcode,"
                       "owner_canonical,name,descriptor,is_get,is_static,"
                       "resolved_field_id", 10),
    "type_refs": ("method_id,caller_class_id,artifact_id,opcode,class_name", 5),
    "string_consts": ("method_id,caller_class_id,artifact_id,value", 4),
    "num_consts": ("method_id,caller_class_id,artifact_id,kind,value", 5),
    "member_names": ("kind,srg_class,srg_name,mcp_name,notch_class,"
                     "notch_name,descriptor", 7),
    "hierarchy": ("class_name,ancestor,depth", 3),
    "mixins": ("artifact_id,mixin_class,target_canonical,target_raw,"
               "kind,priority", 6),
    "mixin_configs": ("artifact_id,config,package,mixin_count", 4),
}


class _Batch:
    """Buffered multi-table inserter (explicit column lists; ids omitted)."""

    def __init__(self, con, size=4000):
        self.con = con
        self.size = size
        self.buf = {}

    def add(self, table, row):
        self.buf.setdefault(table, []).append(row)
        if len(self.buf[table]) >= self.size:
            self.flush(table)

    def flush(self, table=None):
        tables = [table] if table else list(self.buf)
        for t in tables:
            rows = self.buf.get(t)
            if not rows:
                continue
            cols, _n = _INSERTS[t]
            sql = "INSERT INTO %s (%s) VALUES (%s)" % (
                t, cols, ",".join(["?"] * len(rows[0])))
            self.con.executemany(sql, rows)
            self.buf[t] = []


class Builder:
    def __init__(self, db_path, mappings, progress=None):
        self.db_path = db_path
        self.map = mappings
        self.progress = progress or (lambda msg: print(msg, file=sys.stderr))
        self.con = sqlite3.connect(db_path)
        self.con.execute("PRAGMA journal_mode=OFF")
        self.con.execute("PRAGMA synchronous=OFF")
        self.batch = _Batch(self.con)
        # resolution state
        self.temp_methods = {}   # (canon_cls, canon_name, canon_desc) -> id
        self.temp_fields = {}
        self.anchor_methods = {}
        self.anchor_fields = {}
        self._desc_canon = {}    # notch-desc -> canonical-desc (per artifact)
        self._cls_canon = {}     # internal -> canonical (per artifact)

    # ------------------------------------------------------------------
    # canonicalization helpers (artifact namespace aware)

    def _canon_class(self, internal):
        hit = self._cls_canon.get(internal)
        if hit is None:
            hit = self.map.class_to_srg.get(internal, internal)
            self._cls_canon[internal] = hit
        return hit

    def _canon_desc(self, desc, notch_ns):
        if not notch_ns:
            return desc
        hit = self._desc_canon.get(desc)
        if hit is None:
            hit = rename_descriptor(desc, self.map.class_to_srg)
            self._desc_canon[desc] = hit
        return hit

    def _canon_method_decl(self, cls_internal, canon_cls, name, desc,
                           notch_ns, mcp_ns):
        """Returns (srg, mcp, notch, canonical_desc) for a declared method."""
        if notch_ns:
            entry = self.map.method_srg.get((cls_internal, name, desc))
            if entry is not None:
                s_cls, s_name = entry
                return (s_name, self.map.method_mcp.get(s_name), name,
                        self.map.method_desc[(s_cls, s_name)])
            return None, None, None, self._canon_desc(desc, True)
        if mcp_ns:
            srg = self.map.resolve_mcp_method(canon_cls, name, desc)
            if srg is not None:
                notch_e = self.map.method_notch.get((canon_cls, srg))
                return (srg, name, notch_e[1] if notch_e else None, desc)
            return None, name, None, desc
        # srg / raw namespace
        if name.startswith("func_"):
            notch_e = self.map.method_notch.get((canon_cls, name))
            return (name, self.map.method_mcp.get(name),
                    notch_e[1] if notch_e else None, desc)
        return None, None, None, desc

    def _canon_field_decl(self, cls_internal, canon_cls, name, desc,
                          notch_ns, mcp_ns):
        if notch_ns:
            entry = self.map.field_srg.get((cls_internal, name))
            if entry is not None:
                s_cls, s_name = entry
                return (s_name, self.map.field_mcp.get(s_name), name,
                        self._canon_desc(desc, True))
            return None, None, None, self._canon_desc(desc, True)
        if mcp_ns:
            srg = self.map.resolve_mcp_field(canon_cls, name)
            if srg is not None:
                notch_e = self.map.field_notch.get((canon_cls, srg))
                return (srg, name, notch_e[1] if notch_e else None, desc)
            return None, name, None, desc
        if name.startswith("field_"):
            notch_e = self.map.field_notch.get((canon_cls, name))
            return (name, self.map.field_mcp.get(name),
                    notch_e[1] if notch_e else None, desc)
        return None, None, None, desc

    def _canon_call_ref(self, owner_internal, name, desc, notch_ns, mcp_ns):
        owner = self._canon_class(owner_internal)
        if notch_ns:
            entry = self.map.method_srg.get((owner_internal, name, desc))
            if entry is not None:
                s_cls, s_name = entry
                return owner, s_name, self.map.method_desc[(s_cls, s_name)]
            return owner, name, self._canon_desc(desc, True)
        if mcp_ns:
            srg = self.map.resolve_mcp_method(owner, name, desc)
            return owner, srg or name, desc
        return owner, name, desc

    def _canon_field_ref(self, owner_internal, name, desc, notch_ns, mcp_ns):
        owner = self._canon_class(owner_internal)
        if notch_ns:
            entry = self.map.field_srg.get((owner_internal, name))
            if entry is not None:
                return owner, entry[1], self._canon_desc(desc, True)
            return owner, name, self._canon_desc(desc, True)
        if mcp_ns:
            srg = self.map.resolve_mcp_field(owner, name)
            return owner, srg or name, desc
        return owner, name, desc

    # ------------------------------------------------------------------

    def _mappings_digest(self, resolved_sources):
        h = hashlib.sha256()
        for src in sorted(resolved_sources.get("mappings", []),
                          key=lambda s: s["name"]):
            h.update(("%s|%s|%s|%s" % (src["name"], src.get("version", ""),
                                      src["format"], src["sha256"]))
                     .encode("utf-8"))
        return h.hexdigest()

    _COPY_TABLES = ("classes", "class_interfaces", "methods", "fields",
                    "calls", "field_accesses", "type_refs", "string_consts",
                    "num_consts", "build_issues", "mixins", "mixin_configs")

    def build(self, resolved_sources, only_layers=None, incremental=False):
        if incremental and not only_layers:
            return self._build_incremental(resolved_sources)
        con = self.con
        # append mode: the schema already exists — re-running the DDL
        # crashed on 'table meta already exists' (the M1-COMPOSE mods-set
        # expansion attempt); guard both executescript sites
        fresh = con.execute(
            "SELECT COUNT(*) FROM sqlite_master WHERE type='table' "
            "AND name='meta'").fetchone()[0] == 0
        if fresh:
            con.executescript(schema.DDL)
        now = time.strftime("%Y-%m-%dT%H:%M:%S")
        con.executemany(
            "INSERT OR REPLACE INTO meta VALUES (?,?)",
            [("schema_version", schema.SCHEMA_VERSION),
             ("tool_version", _TOOL_VERSION),
             ("built_at", now),
             ("mc_version", resolved_sources.get("mc_version", "?"))])
        mappings_digest = self._mappings_digest(resolved_sources)
        con.execute("INSERT OR REPLACE INTO meta VALUES ('mappings_digest', ?)",
                    (mappings_digest,))

        for src in resolved_sources.get("mappings", []):
            con.execute(
                "INSERT INTO mapping_sources(name,version,path,sha256,format)"
                " VALUES (?,?,?,?,?)",
                (src["name"], src.get("version", "?"), src["path"],
                 src["sha256"], src["format"]))

        artifact_stats = []
        for spec in resolved_sources["artifacts"]:
            layer = spec["layer"]
            if only_layers and layer not in only_layers:
                continue
            stats = self._ingest_artifact(spec)
            artifact_stats.append(stats)

        self._populate_member_names()
        self._build_hierarchy()
        self._resolve_inherited()
        self._record_sources_json(resolved_sources)
        con.commit()
        con.execute("ANALYZE")
        con.commit()
        return artifact_stats

    # ------------------------------------------------------------------
    # incremental build: per-artifact SHA cache (goal §2)

    @staticmethod
    def _spec_digest(spec):
        """Identity of an artifact SPEC (everything but the file bytes)."""
        h = hashlib.sha256()
        for k in ("layer", "name", "path", "kind", "member_namespace",
                  "entry_filter", "mc_version", "forge_version",
                  "campaign_id", "session_id"):
            h.update(("%s=%r;" % (k, spec.get(k))).encode("utf-8"))
        h.update(b"anchor=%d;" % (1 if spec.get("anchor") else 0))
        return h.hexdigest()

    def _mappings_digest(self, resolved_sources):
        h = hashlib.sha256()
        for src in sorted(resolved_sources.get("mappings", []),
                          key=lambda s: s["name"]):
            h.update(("%s|%s|%s|%s" % (src["name"], src.get("version", ""),
                                      src["format"], src["sha256"]))
                     .encode("utf-8"))
        return h.hexdigest()

    def _fresh_connect(self, path):
        if getattr(self, "con", None) is not None:
            try:
                self.con.close()
            except Exception:
                pass
        con = sqlite3.connect(path)
        con.execute("PRAGMA journal_mode=OFF")
        con.execute("PRAGMA synchronous=OFF")
        self.con = con
        self.batch = _Batch(con)

    def _select_cached(self, db_path, resolved_sources, mappings_digest):
        """Returns {path: old_artifact_id} for artifacts whose file bytes,
        spec and mappings are all unchanged; None when the previous index
        is incompatible (different builder version / mappings)."""
        old = sqlite3.connect(db_path)
        try:
            meta = dict(old.execute("SELECT key, value FROM meta"))
            if (meta.get("schema_version") != schema.SCHEMA_VERSION
                    or meta.get("tool_version") != _TOOL_VERSION):
                self.progress("[cache] previous index built by a different "
                              "version — full rebuild")
                return None
            if meta.get("mappings_digest") != mappings_digest:
                self.progress("[cache] mappings changed — full rebuild")
                return None
            old_by_key = {}
            for r in old.execute(
                    "SELECT id, layer, name, path, kind, sha256, "
                    "member_namespace, entry_filter, mc_version, "
                    "forge_version, anchor, campaign_id, session_id "
                    "FROM artifacts"):
                old_spec = {"layer": r[1], "name": r[2], "path": r[3],
                            "kind": r[4], "sha256": r[5],
                            "member_namespace": r[6],
                            "entry_filter": r[7], "mc_version": r[8],
                            "forge_version": r[9], "anchor": bool(r[10]),
                            "campaign_id": r[11], "session_id": r[12]}
                old_by_key[(r[3], r[5])] = (r[0], old_spec)
        finally:
            old.close()
        cached = {}
        for spec in resolved_sources["artifacts"]:
            path = spec["path"]
            kind = spec.get("kind")
            if kind is None:
                kind = "dir" if os.path.isdir(path) else "jar"
            artifact_sha = (sha256_dir(path)[0] if kind == "dir"
                            else sha256_file(path))
            hit = old_by_key.get((path, artifact_sha))
            if hit is None:
                continue
            old_id, old_spec = hit
            if self._spec_digest(old_spec) == self._spec_digest(spec):
                cached[path] = old_id
        return cached

    def _copy_cached(self, old_db_path, artifact_ids):
        con = self.con
        con.execute("ATTACH ? AS oldidx", (old_db_path,))
        try:
            id_list = ",".join(str(i) for i in sorted(artifact_ids))
            art_cols = ("id,layer,name,path,kind,sha256,member_namespace,"
                        "entry_filter,mc_version,forge_version,anchor,note,"
                        "campaign_id,session_id,class_count,method_count,"
                        "field_count,error_count,indexed_at")
            con.execute(
                "INSERT INTO artifacts (%s) SELECT %s FROM oldidx.artifacts"
                " WHERE id IN (%s)" % (art_cols, art_cols, id_list))
            cls_cols = ("id,artifact_id,internal_name,canonical_name,"
                        "notch_name,access,super_canonical,interfaces,"
                        "signature,source_file,cf_version,file_sha256,"
                        "bootstrap_count,annotations")
            con.execute(
                "INSERT INTO classes (%s) SELECT %s FROM oldidx.classes"
                " WHERE artifact_id IN (%s)" % (cls_cols, cls_cols, id_list))
            con.execute(
                "INSERT INTO class_interfaces (class_id,iface) SELECT "
                "class_id,iface FROM oldidx.class_interfaces WHERE class_id"
                " IN (SELECT id FROM oldidx.classes WHERE artifact_id IN"
                " (%s))" % id_list)
            for table in ("methods", "fields", "calls", "field_accesses",
                          "type_refs", "string_consts", "num_consts",
                          "build_issues"):
                cols = ",".join(
                    r[1] for r in con.execute(
                        "PRAGMA table_info(%s)" % table).fetchall())
                con.execute(
                    "INSERT INTO %s (%s) SELECT %s FROM oldidx.%s WHERE"
                    " artifact_id IN (%s)"
                    % (table, cols, cols, table, id_list))
            con.commit()
        finally:
            con.execute("DETACH oldidx")

    def _build_incremental(self, resolved_sources):
        """Rebuild into a temp DB, copying every cached artifact with row
        ids preserved and re-parsing only changed/new ones, then swap into
        place. The previous index stays intact until the swap succeeds."""
        db_path = os.path.abspath(self.db_path)
        mappings_digest = self._mappings_digest(resolved_sources)
        cached_by_path = {}
        if os.path.exists(db_path):
            cached_by_path = self._select_cached(
                db_path, resolved_sources, mappings_digest) or {}
        tmp_path = db_path + ".tmp-build"
        for p in (tmp_path, tmp_path + "-wal", tmp_path + "-shm",
                  tmp_path + "-journal"):
            if os.path.exists(p):
                os.remove(p)
        self._fresh_connect(tmp_path)
        try:
            con = self.con
            con.executescript(schema.DDL)
            now = time.strftime("%Y-%m-%dT%H:%M:%S")
            con.executemany(
                "INSERT INTO meta VALUES (?,?)",
                [("schema_version", schema.SCHEMA_VERSION),
                 ("tool_version", _TOOL_VERSION),
                 ("built_at", now),
                 ("mc_version", resolved_sources.get("mc_version", "?")),
                 ("mappings_digest", mappings_digest)])
            con.executemany(
                "INSERT INTO mapping_sources(name,version,path,sha256,"
                "format) VALUES (?,?,?,?,?)",
                [(s["name"], s.get("version", "?"), s["path"], s["sha256"],
                  s["format"]) for s in resolved_sources.get("mappings", [])])
            if cached_by_path:
                self._copy_cached(db_path,
                                  set(cached_by_path.values()))
                con.commit()
            self._load_anchor_index_from_db()

            artifact_stats = []
            n_cached = 0
            for spec in resolved_sources["artifacts"]:
                cached_id = cached_by_path.get(spec["path"])
                if cached_id is not None:
                    row = con.execute(
                        "SELECT layer, name, class_count, method_count, "
                        "field_count, error_count FROM artifacts WHERE "
                        "id=?", (cached_id,)).fetchone()
                    self.progress("[cache] %-22s %s (sha256+spec match — "
                                  "%s classes, %s methods copied)"
                                  % (row[0], row[1], row[2], row[3]))
                    artifact_stats.append({
                        "layer": row[0], "name": row[1],
                        "artifact_id": cached_id,
                        "classes": row[2], "methods": row[3],
                        "fields": row[4], "errors": row[5],
                        "seconds": 0.0, "cached": True})
                    n_cached += 1
                    continue
                stats = self._ingest_artifact(spec)
                stats["cached"] = False
                artifact_stats.append(stats)
            self.progress("[cache] %d/%d artifacts reused, %d re-parsed"
                          % (n_cached, len(artifact_stats),
                             len(artifact_stats) - n_cached))

            self._populate_member_names()
            self._build_hierarchy()
            self._resolve_inherited()
            self._record_sources_json(resolved_sources)
            con.commit()
            con.execute("ANALYZE")
            con.commit()
        except BaseException:
            try:
                self.con.close()
            except Exception:
                pass
            if os.path.exists(tmp_path):
                os.remove(tmp_path)
            self._fresh_connect(db_path)
            raise
        self.con.close()
        os.replace(tmp_path, db_path)
        self._fresh_connect(db_path)
        return artifact_stats

    def _load_anchor_index_from_db(self):
        row = self.con.execute(
            "SELECT id FROM artifacts WHERE anchor=1 ORDER BY id").fetchone()
        if row:
            self._load_anchor_index(row[0])

    def _resolve_inherited(self):
        """Third pass: javac emits the compile-time receiver type as the
        methodref owner, so references to inherited methods carry the
        subclass as owner. Walk each unresolved owner's ancestor chain and
        re-resolve against a global canonical member index."""
        direct_parents = {}
        for canon, sup, ifaces_json in self.con.execute(
                "SELECT canonical_name, super_canonical, interfaces FROM "
                "classes"):
            plist = direct_parents.setdefault(canon, set())
            if sup:
                plist.add(sup)
            for iface in json.loads(ifaces_json or "[]"):
                plist.add(iface)

        global_methods = {}
        q = ("SELECT m.id, m.srg_name, m.name, m.canonical_descriptor, "
             "a.anchor, c.canonical_name FROM methods m "
             "JOIN classes c ON c.id = m.class_id "
             "JOIN artifacts a ON a.id = m.artifact_id")
        for mid, srg, name, cdesc, anchor, canon in self.con.execute(q):
            for key in ((canon, srg or name, cdesc), (canon, name, cdesc)):
                prev = global_methods.get(key)
                if prev is None or (anchor and not prev[1]):
                    global_methods[key] = (mid, bool(anchor))
        global_fields = {}
        q = ("SELECT f.id, f.srg_name, f.name, f.canonical_descriptor, "
             "a.anchor, c.canonical_name FROM fields f "
             "JOIN classes c ON c.id = f.class_id "
             "JOIN artifacts a ON a.id = f.artifact_id")
        for fid, srg, name, cdesc, anchor, canon in self.con.execute(q):
            for key in ((canon, srg or name, cdesc), (canon, name, cdesc)):
                prev = global_fields.get(key)
                if prev is None or (anchor and not prev[1]):
                    global_fields[key] = (fid, bool(anchor))

        def ancestors_of(start):
            seen = set()
            stack = [start]
            while stack:
                node = stack.pop()
                for parent in direct_parents.get(node, ()):
                    if parent not in seen:
                        seen.add(parent)
                        stack.append(parent)
            return seen

        upd_calls, upd_fields = [], []
        q = ("SELECT k.id, k.owner_canonical, k.name, k.descriptor FROM "
             "calls k WHERE k.resolved_method_id IS NULL "
             "AND k.owner_canonical IS NOT NULL")
        for row_id, owner, name, desc in self.con.execute(q):
            for anc in ancestors_of(owner):
                hit = global_methods.get((anc, name, desc))
                if hit is not None:
                    upd_calls.append((hit[0], row_id))
                    break
        q = ("SELECT k.id, k.owner_canonical, k.name, k.descriptor FROM "
             "field_accesses k WHERE k.resolved_field_id IS NULL "
             "AND k.owner_canonical IS NOT NULL")
        for row_id, owner, name, desc in self.con.execute(q):
            for anc in ancestors_of(owner):
                hit = global_fields.get((anc, name, desc))
                if hit is not None:
                    upd_fields.append((hit[0], row_id))
                    break
        self.con.executemany(
            "UPDATE calls SET resolved_method_id=? WHERE id=?", upd_calls)
        self.con.executemany(
            "UPDATE field_accesses SET resolved_field_id=? WHERE id=?",
            upd_fields)
        self.con.commit()
        self.progress("[resolve] inherited-member pass: %d calls, %d field "
                      "edges resolved" % (len(upd_calls), len(upd_fields)))

    def _record_sources_json(self, resolved_sources):
        self.con.execute(
            "INSERT OR REPLACE INTO meta VALUES ('sources', ?)",
            (json.dumps(resolved_sources, indent=1),))

    # ------------------------------------------------------------------

    def _ingest_artifact(self, spec):
        path = spec["path"]
        kind = spec.get("kind")
        if kind is None:
            kind = "dir" if os.path.isdir(path) else "jar"
        if kind == "jar":
            digest = sha256_file(path)
            n_entries = None
        else:
            digest, n_entries = sha256_dir(path)

        con = self.con
        cur = con.execute(
            "INSERT INTO artifacts(layer,name,path,kind,sha256,"
            "member_namespace,entry_filter,mc_version,forge_version,anchor,"
            "note,campaign_id,session_id,indexed_at) "
            "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            (spec["layer"], spec["name"], path, kind, digest,
             spec.get("member_namespace", "raw"),
             spec.get("entry_filter", "all"),
             spec.get("mc_version"), spec.get("forge_version"),
             1 if spec.get("anchor") else 0, spec.get("note"),
             spec.get("campaign_id"), spec.get("session_id"),
             time.strftime("%Y-%m-%dT%H:%M:%S")))
        artifact_id = cur.lastrowid
        con.commit()

        ns = spec.get("member_namespace", "raw")
        notch_ns = ns == "notch"
        mcp_ns = ns == "mcp"
        entry_filter = spec.get("entry_filter", "all")
        self.temp_methods = {}
        self.temp_fields = {}
        self._cls_canon = {}
        self._desc_canon = {}
        # edges resolved in phase 2 (after the whole artifact is indexed) so
        # forward references within/across classes resolve
        self.pending_calls = []
        self.pending_fa = []

        n_classes = n_methods = n_fields = n_errors = 0
        t0 = time.time()
        class_iter = self._iter_class_entries(path, kind)
        for entry_name, data in class_iter:
            try:
                cf = parse_class(data)
            except ClassFileError as exc:
                n_errors += 1
                self.batch.add("build_issues", (artifact_id, entry_name,
                                                str(exc)))
                continue
            except Exception as exc:  # malformed beyond parser expectations
                n_errors += 1
                self.batch.add("build_issues", (artifact_id, entry_name,
                                                "crash: %r" % (exc,)))
                continue
            if entry_filter == "mc" and not self._is_mc_class(cf.this_class):
                continue
            try:
                counts = self._ingest_class(artifact_id, entry_name, cf,
                                            notch_ns, mcp_ns)
            except ClassFileError as exc:
                n_errors += 1
                self.batch.add("build_issues", (artifact_id, entry_name,
                                                str(exc)))
                continue
            n_classes += 1
            n_methods += counts[0]
            n_fields += counts[1]

        if kind == "jar":
            self._ingest_mixin_configs(artifact_id, path)
        self._resolve_pending()
        self.batch.flush()
        con.execute(
            "UPDATE artifacts SET class_count=?, method_count=?, "
            "field_count=?, error_count=? WHERE id=?",
            (n_classes, n_methods, n_fields, n_errors, artifact_id))
        con.commit()
        dt = time.time() - t0
        self.progress(
            "[%s] %s: %d classes, %d methods, %d fields, %d errors "
            "(%.1fs)" % (spec["layer"], spec["name"], n_classes, n_methods,
                         n_fields, n_errors, dt))
        if spec.get("anchor"):
            self._load_anchor_index(artifact_id)
        return {"layer": spec["layer"], "artifact_id": artifact_id,
                "classes": n_classes, "methods": n_methods,
                "fields": n_fields, "errors": n_errors, "seconds": dt}

    def _ingest_mixin_configs(self, artifact_id, jar_path):
        """Registered-mixin provenance: parse *.mixins.json resources
        (registered configs vs merely-present @Mixin classes)."""
        import zipfile
        with zipfile.ZipFile(jar_path) as zf:
            for name in zf.namelist():
                base = name.rsplit("/", 1)[-1].lower()
                # conventions: mixins.json or mixins.<modid>.json
                # (refmaps carry no mixin list and are skipped)
                if not (base == "mixins.json"
                        or (base.startswith("mixins.")
                            and base.endswith(".json")
                            and ".refmap." not in base)):
                    continue
                try:
                    data = json.loads(zf.read(name).decode("utf-8",
                                                           errors="replace"))
                    package = str(data.get("package", ""))
                    n = sum(len(data.get(k) or [])
                            for k in ("mixins", "server", "client"))
                    self.batch.add("mixin_configs",
                                   (artifact_id, name, package, n))
                except Exception as exc:
                    self.batch.add("build_issues",
                                   (artifact_id, name,
                                    "mixins.json parse: %r" % (exc,)))

    def _iter_class_entries(self, path, kind):
        if kind == "jar":
            with zipfile.ZipFile(path) as zf:
                for info in zf.infolist():
                    if info.filename.endswith(".class"):
                        yield info.filename, zf.read(info)
        else:
            for root, _dirs, names in os.walk(path):
                for name in sorted(names):
                    if name.endswith(".class"):
                        full = os.path.join(root, name)
                        rel = os.path.relpath(full, path).replace("\\", "/")
                        with open(full, "rb") as fh:
                            yield rel, fh.read()

    def _is_mc_class(self, internal):
        return (internal in self.map.class_to_srg
                or internal.startswith("net/minecraft/"))

    def _ingest_class(self, artifact_id, entry_name, cf, notch_ns, mcp_ns):
        cls_internal = cf.this_class
        canon = self._canon_class(cls_internal)
        notch_name = self.map.class_to_notch.get(canon)
        con = self.con
        cur = con.execute(
            "INSERT INTO classes(artifact_id,internal_name,canonical_name,"
            "notch_name,access,super_canonical,interfaces,signature,"
            "source_file,cf_version,file_sha256,bootstrap_count,annotations)"
            " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
            (artifact_id, cls_internal, canon, notch_name, cf.access,
             self._canon_class(cf.super_class) if cf.super_class else None,
             json.dumps([self._canon_class(i) for i in cf.interfaces]),
             cf.signature, cf.source_file, "%d.%d" % (cf.major, cf.minor),
             cf.sha256, cf.bootstrap_count,
             json.dumps(cf.annotations)))
        class_id = cur.lastrowid

        for iface in cf.interfaces:
            self.batch.add("class_interfaces",
                           (class_id, self._canon_class(iface)))

        for anno in cf.mixin:
            vals = anno["values"]
            targets = vals.get("value") or []
            if isinstance(targets, str):
                targets = [targets]
            str_targets = vals.get("targets") or []
            if isinstance(str_targets, str):
                str_targets = [str_targets]
            priority = vals.get("priority")
            for t in targets:
                internal = str(t).replace(".", "/")
                self.batch.add("mixins",
                               (artifact_id, canon,
                                self._canon_class(internal), str(t),
                                "class", priority))
            for t in str_targets:
                internal = str(t).replace(".", "/")
                self.batch.add("mixins",
                               (artifact_id, canon,
                                self._canon_class(internal), str(t),
                                "string", priority))

        n_methods = n_fields = 0
        for f in cf.fields:
            srg, mcp, notch, cdesc = self._canon_field_decl(
                cls_internal, canon, f.name, f.descriptor, notch_ns, mcp_ns)
            ident = "%s\x00%s" % (canon, srg or f.name)
            cur = con.execute(
                "INSERT INTO fields(class_id,artifact_id,name,srg_name,"
                "mcp_name,notch_name,descriptor,canonical_descriptor,"
                "identity_key,access,signature,constant,annotations)"
                " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                (class_id, artifact_id, f.name, srg, mcp, notch, f.descriptor,
                 cdesc, ident, f.access, f.signature,
                 ("%s:%s" % (f.constant[0], f.constant[1]))
                 if f.constant else None,
                 json.dumps(f.annotations)))
            self.temp_fields[(canon, srg or f.name, cdesc)] = cur.lastrowid
            self.temp_fields[(canon, f.name, cdesc)] = cur.lastrowid
            n_fields += 1

        for m in cf.methods:
            srg, mcp, notch, cdesc = self._canon_method_decl(
                cls_internal, canon, m.name, m.descriptor, notch_ns, mcp_ns)
            member = srg or m.name
            ident = "%s\x00%s\x00%s" % (canon, member, cdesc)
            cur = con.execute(
                "INSERT INTO methods(class_id,artifact_id,name,srg_name,"
                "mcp_name,notch_name,descriptor,canonical_descriptor,"
                "identity_key,access,signature,exceptions,code_len,max_stack,"
                "max_locals,code_sha256,annotations)"
                " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                (class_id, artifact_id, m.name, srg, mcp, notch, m.descriptor,
                 cdesc, ident, m.access, m.signature,
                 json.dumps(m.exceptions), m.code_len, m.max_stack,
                 m.max_locals, m.code_sha256, json.dumps(m.annotations)))
            method_id = cur.lastrowid
            self.temp_methods[(canon, member, cdesc)] = method_id
            self.temp_methods[(canon, m.name, cdesc)] = method_id
            n_methods += 1

            inv_seen = set()
            for offset, opname, owner, name, desc, itf in m.invocations:
                if owner is None:  # invokedynamic (lambda/indy site)
                    key = (opname, None, name, desc)
                    if key in inv_seen:
                        continue
                    inv_seen.add(key)
                    self.batch.add("calls", (method_id, class_id, artifact_id,
                                             opname, None, name, desc, 0,
                                             None))
                    continue
                owner_c, name_c, desc_c = self._canon_call_ref(
                    owner, name, desc, notch_ns, mcp_ns)
                key = (opname, owner_c, name_c, desc_c)
                if key in inv_seen:
                    continue
                inv_seen.add(key)
                self.pending_calls.append(
                    (method_id, class_id, artifact_id, opname, owner_c,
                     name_c, desc_c, 1 if itf else 0))

            fa_seen = set()
            for offset, opname, owner, name, desc, is_get, is_static \
                    in m.field_ops:
                owner_c, name_c, desc_c = self._canon_field_ref(
                    owner, name, desc, notch_ns, mcp_ns)
                key = (opname, owner_c, name_c, desc_c)
                if key in fa_seen:
                    continue
                fa_seen.add(key)
                self.pending_fa.append(
                    (method_id, class_id, artifact_id, opname, owner_c,
                     name_c, desc_c, 1 if is_get else 0,
                     1 if is_static else 0))

            tr_seen = set()
            for offset, opname, cls in m.type_refs:
                cls_c = (cls if opname == "ldc_methodtype"
                         else self._canon_class(cls))
                key = (opname, cls_c)
                if key in tr_seen:
                    continue
                tr_seen.add(key)
                self.batch.add("type_refs", (method_id, class_id, artifact_id,
                                             opname, cls_c))

            for offset, value in set(m.strings):
                self.batch.add("string_consts",
                               (method_id, class_id, artifact_id, value))
            for offset, kindv, val in set(m.nums):
                if kindv in ("?", ""):
                    continue
                self.batch.add("num_consts",
                               (method_id, class_id, artifact_id, kindv, val))
        return n_methods, n_fields

    # ------------------------------------------------------------------

    def _resolve_pending(self):
        """Phase 2 of artifact ingestion: resolve collected call/field edges
        against the completed per-artifact index, then the anchor index."""
        calls = []
        for (method_id, class_id, artifact_id, opname, owner_c, name_c,
             desc_c, itf) in self.pending_calls:
            rid = (self.temp_methods.get((owner_c, name_c, desc_c))
                   or self.anchor_methods.get((owner_c, name_c, desc_c)))
            calls.append((method_id, class_id, artifact_id, opname, owner_c,
                          name_c, desc_c, itf, rid))
        if calls:
            cols = _INSERTS["calls"][0]
            self.con.executemany(
                "INSERT INTO calls (%s) VALUES (%s)" % (
                    cols, ",".join(["?"] * 9)), calls)
        fas = []
        for (method_id, class_id, artifact_id, opname, owner_c, name_c,
             desc_c, is_get, is_static) in self.pending_fa:
            rid = (self.temp_fields.get((owner_c, name_c, desc_c))
                   or self.anchor_fields.get((owner_c, name_c, desc_c)))
            fas.append((method_id, class_id, artifact_id, opname, owner_c,
                        name_c, desc_c, is_get, is_static, rid))
        if fas:
            cols = _INSERTS["field_accesses"][0]
            self.con.executemany(
                "INSERT INTO field_accesses (%s) VALUES (%s)" % (
                    cols, ",".join(["?"] * 10)), fas)
        self.pending_calls = []
        self.pending_fa = []

    def _load_anchor_index(self, artifact_id):
        q = ("SELECT m.id, c.canonical_name, m.srg_name, m.name, "
             "m.canonical_descriptor FROM methods m "
             "JOIN classes c ON c.id = m.class_id WHERE m.artifact_id = ?")
        for mid, canon, srg, name, cdesc in self.con.execute(q, (artifact_id,)):
            self.anchor_methods[(canon, srg or name, cdesc)] = mid
            self.anchor_methods[(canon, name, cdesc)] = mid
        q = ("SELECT f.id, c.canonical_name, f.srg_name, f.name, "
             "f.canonical_descriptor FROM fields f "
             "JOIN classes c ON c.id = f.class_id WHERE f.artifact_id = ?")
        for fid, canon, srg, name, cdesc in self.con.execute(q, (artifact_id,)):
            self.anchor_fields[(canon, srg or name, cdesc)] = fid
            self.anchor_fields[(canon, name, cdesc)] = fid

    def _populate_member_names(self):
        m = self.map
        rows = []
        for (s_cls, s_name), (n_cls, n_name, _nd) in m.method_notch.items():
            rows.append(("method", s_cls, s_name, m.method_mcp.get(s_name),
                         n_cls, n_name, m.method_desc.get((s_cls, s_name))))
        for (s_cls, s_name), (n_cls, n_name) in m.field_notch.items():
            rows.append(("field", s_cls, s_name, m.field_mcp.get(s_name),
                         n_cls, n_name, None))
        self.con.executemany(
            "INSERT INTO member_names VALUES (?,?,?,?,?,?,?)", rows)
        self.con.commit()

    def _build_hierarchy(self):
        parents = {}
        q = ("SELECT canonical_name, super_canonical, interfaces FROM classes")
        for canon, sup, ifaces_json in self.con.execute(q):
            plist = parents.setdefault(canon, set())
            if sup:
                plist.add(sup)
            for iface in json.loads(ifaces_json or "[]"):
                plist.add(iface)
        full = {}
        rows = []
        for start in parents:
            if start in full:
                continue
            depth = {start: 0}
            stack = [start]
            while stack:
                node = stack.pop()
                d = depth[node]
                for parent in parents.get(node, ()):
                    if parent not in depth:
                        depth[parent] = d + 1
                        stack.append(parent)
            ancestors = set(depth) - {start}
            full[start] = ancestors
            for anc in ancestors:
                rows.append((start, anc, depth[anc]))
        self.con.executemany("INSERT INTO hierarchy VALUES (?,?,?)", rows)
        self.con.commit()


def open_db(db_path):
    con = sqlite3.connect(db_path)
    con.execute("PRAGMA query_only=OFF")
    return con


def load_mappings(resolved_sources):
    m = None
    for src in resolved_sources.get("mappings", []):
        fmt = src["format"]
        if fmt == "joined_srg":
            m = parse_joined_srg(src["path"], m)
        elif fmt == "mcp_methods_csv":
            m = parse_mcp_csv(src["path"], None, m)
        elif fmt == "mcp_fields_csv":
            m = parse_mcp_csv(None, src["path"], m)
        else:
            raise SystemExit("unknown mapping format %r" % fmt)
    if m is None:
        raise SystemExit("no joined.srg configured; member canonicalization "
                         "would be dishonest — refusing to build")
    return m
