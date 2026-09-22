#!/usr/bin/env python3
"""M1.4-R / M1-final Evidence Integrity Checker.

Detects:
  1. known hardcoded benchmark outputs
  2. timing clamps
  3. final reports referencing missing raw data
  4. "real pack" claims without installed pack metadata
  5. missing/invalid/pending provenance entries      (strengthened 2026-09-19)
  6. generated results not committed
  7. dirty working tree during milestone finalization

Provenance strengthening (M1-final, 2026-09-19) — the registry was previously
YAML-invalid while this checker passed it because it only regex-scanned the
text. The checker now:
  - actually parses machine/evidence-provenance.yaml with a strict loader
    that REJECTS duplicate mapping keys and any parse error;
  - validates per-entry schema (required fields by evidence class, known
    classes only, unique ids, allowed keys);
  - verifies raw_artifact existence AND sha256 match for MEASURED-like
    entries (raw_artifact_sha256 is mandatory for them);
  - rejects pending/awaiting provenance on valid empirical entries;
  - rejects invalidated artifacts being cited as current evidence outside
    banner-marked files;
  - requires every active empirical artifact (machine/**/*-final-metrics.txt,
    machine/*-results.yaml without an invalidation banner) to be registered.

Usage:
  python tools/verify_evidence_integrity.py [--require-clean-tree]

Exit 0 = clean; non-zero = violations listed on stdout.

Regression tests: tools/tests/test_evidence_integrity.py (includes the
previously-passing broken-YAML case).
"""
import hashlib
import json
import os
import re
import subprocess
import sys

ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
MACHINE = os.path.join(ROOT, "machine")

try:
    import yaml
except ImportError:  # pragma: no cover
    yaml = None


# --------------------------------------------------------------------------
# Strict YAML loading (rejects duplicate keys); used for all registry parsing.
# --------------------------------------------------------------------------

class DuplicateKeyError(Exception):
    pass


class StrictLoader(yaml.SafeLoader if yaml else object):  # type: ignore[misc]
    pass


def _strict_construct_mapping(loader, node, deep=False):
    mapping = {}
    for key_node, value_node in node.value:
        key = loader.construct_object(key_node, deep=deep)
        if key in mapping:
            raise DuplicateKeyError("duplicate mapping key: %r" % (key,))
        mapping[key] = loader.construct_object(value_node, deep=deep)
    return mapping


if yaml:
    StrictLoader.add_constructor(
        yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG,
        _strict_construct_mapping,
    )


def load_yaml_strict(text):
    """Parse YAML rejecting duplicate keys. Returns (data, None) or (None, error)."""
    if yaml is None:
        return None, "PyYAML is not installed (pip install pyyaml)"
    try:
        return yaml.load(text, Loader=StrictLoader), None
    except DuplicateKeyError as e:
        return None, "duplicate key rejected: %s" % e
    except yaml.YAMLError as e:
        return None, "YAML parse error: %s" % str(e).replace("\n", " ")[:200]


# --------------------------------------------------------------------------
# Provenance registry validation
# --------------------------------------------------------------------------

VALID_CLASSES = {
    "MEASURED", "DERIVED_FROM_MEASURED", "PROJECTED", "SYNTHETIC", "ASSUMED",
    "INVALIDATED",
}
MEASURED_LIKE = {"MEASURED", "DERIVED_FROM_MEASURED"}
ALLOWED_ENTRY_KEYS = {
    "id", "class", "claim", "tool", "command", "git_commit", "raw_artifact",
    "raw_artifact_sha256", "raw_artifact_secondary", "timestamp", "machine",
    "environment", "environment_notes", "java", "rust_profile", "handoff",
    "binaries", "methodology", "derived_from", "sample_count", "scope",
    "status", "note", "notes", "row_count", "summary", "superseded_by",
    "reason", "artifact",
}
GIT_COMMIT_RX = re.compile(r"^[0-9a-f]{7,40}$")


def _sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def validate_provenance_text(text, root):
    """Validate provenance registry content. Returns list of violation strings."""
    out = []
    data, err = load_yaml_strict(text)
    if err or not isinstance(data, dict):
        return ["PROVENANCE_INVALID_YAML: %s" % (err or "top level is not a mapping")]

    entries = data.get("entries")
    invalidated = data.get("invalidated", [])
    if entries is None:
        out.append("PROVENANCE_SCHEMA: missing 'entries'")
        entries = []
    elif not isinstance(entries, list):
        out.append("PROVENANCE_SCHEMA: 'entries' must be a sequence")
        entries = []
    if not isinstance(invalidated, list):
        out.append("PROVENANCE_SCHEMA: 'invalidated' must be a sequence")
        invalidated = []

    seen_ids = {}
    registered_artifacts = set()

    def check_id(eid, where):
        if eid in seen_ids:
            out.append("PROVENANCE_DUP_ID: %s duplicates id in %s" % (eid, seen_ids[eid]))
        seen_ids[eid] = where

    for i, e in enumerate(entries):
        where = "entries[%d]" % i
        if not isinstance(e, dict):
            out.append("PROVENANCE_SCHEMA: %s is not a mapping" % where)
            continue
        eid = e.get("id")
        if not eid or not isinstance(eid, str):
            out.append("PROVENANCE_SCHEMA: %s missing string 'id'" % where)
            eid = "<missing:%d>" % i
        check_id(eid, where)
        unknown = set(e.keys()) - ALLOWED_ENTRY_KEYS
        if unknown:
            out.append("PROVENANCE_SCHEMA: %s(%s) unknown keys: %s" % (where, eid, sorted(unknown)))
        cls = e.get("class")
        if cls not in VALID_CLASSES:
            out.append("PROVENANCE_CLASS: %s(%s) invalid class %r" % (where, eid, cls))
            cls = None
        if not e.get("claim"):
            out.append("PROVENANCE_SCHEMA: %s(%s) missing 'claim'" % (where, eid))

        status = e.get("status")
        if cls in MEASURED_LIKE:
            if status not in (None, "valid"):
                out.append("PENDING_PROVENANCE: %s(%s) MEASURED-like entry status=%r "
                           "(must be 'valid'; supersede or complete it)" % (where, eid, status))
            gc = e.get("git_commit")
            if not gc or not isinstance(gc, str) or "pending" in gc.lower() \
                    or "awaiting" in gc.lower() or not GIT_COMMIT_RX.match(gc):
                out.append("PENDING_PROVENANCE: %s(%s) git_commit=%r is missing/pending/malformed"
                           % (where, eid, gc))
            art = e.get("raw_artifact")
            if not art:
                out.append("PROVENANCE_SCHEMA: %s(%s) MEASURED-like entry missing 'raw_artifact'"
                           % (where, eid))
            else:
                registered_artifacts.add(art)
                ap = os.path.join(root, art.replace("/", os.sep))
                if not os.path.isfile(ap):
                    out.append("RAW_DATA_MISSING: %s(%s) artifact not found: %s" % (where, eid, art))
                else:
                    h = e.get("raw_artifact_sha256")
                    if not h:
                        out.append("RAW_HASH_MISSING: %s(%s) MEASURED-like entry lacks "
                                   "raw_artifact_sha256" % (where, eid))
                    elif _sha256(ap) != h:
                        out.append("RAW_HASH_MISMATCH: %s(%s) sha256 of %s does not match registry"
                                   % (where, eid, art))
            if not isinstance(e.get("sample_count"), int) or e.get("sample_count", 0) <= 0:
                out.append("PROVENANCE_SCHEMA: %s(%s) missing positive 'sample_count'" % (where, eid))
            if not e.get("timestamp"):
                out.append("PROVENANCE_SCHEMA: %s(%s) missing 'timestamp'" % (where, eid))
        else:
            # non-empirical classes: artifact reference, if present, must exist
            art = e.get("raw_artifact")
            if art:
                registered_artifacts.add(art)
                if not os.path.isfile(os.path.join(root, art.replace("/", os.sep))):
                    out.append("RAW_DATA_MISSING: %s(%s) artifact not found: %s" % (where, eid, art))
        sec = e.get("raw_artifact_secondary")
        if sec:
            registered_artifacts.add(sec)
        summ = e.get("summary")
        if summ:
            registered_artifacts.add(summ)

    for i, e in enumerate(invalidated):
        where = "invalidated[%d]" % i
        if not isinstance(e, dict):
            out.append("PROVENANCE_SCHEMA: %s is not a mapping" % where)
            continue
        eid = e.get("id")
        if not eid:
            out.append("PROVENANCE_SCHEMA: %s missing 'id'" % where)
            eid = "<missing:%d>" % i
        check_id(eid, where)
        if not e.get("reason"):
            out.append("PROVENANCE_SCHEMA: %s(%s) invalidated entry missing 'reason' "
                       "(a MEASURED entry misfiled here loses its meaning)" % (where, eid))
        if e.get("class") in MEASURED_LIKE and e.get("status") == "valid":
            out.append("INVALIDATED_CONTRADICTION: %s(%s) marked invalidated but carries a valid "
                       "MEASURED classification" % (where, eid))
    return out


def audit_provenance():
    reg = os.path.join(MACHINE, "evidence-provenance.yaml")
    if not os.path.isfile(reg):
        return ["PROVENANCE_MISSING: machine/evidence-provenance.yaml does not exist"]
    with open(reg, encoding="utf-8") as f:
        text = f.read()
    violations = validate_provenance_text(text, ROOT)

    # invalidated artifacts must not be cited as current evidence elsewhere
    data, _ = load_yaml_strict(text)
    inv_artifacts = []
    if isinstance(data, dict):
        for e in data.get("invalidated", []) or []:
            if isinstance(e, dict) and e.get("artifact"):
                inv_artifacts.append(e["artifact"])
    if inv_artifacts:
        violations.extend(_scan_invalidated_citations(inv_artifacts))

    # every active empirical artifact must be registered
    violations.extend(_scan_unregistered_evidence(
        registered=_collect_registered(data)))
    return violations


def _collect_registered(data):
    reg = set()
    if not isinstance(data, dict):
        return reg
    for e in (data.get("entries") or []) + (data.get("invalidated") or []):
        if isinstance(e, dict):
            for k in ("raw_artifact", "raw_artifact_secondary", "summary", "artifact"):
                if e.get(k):
                    reg.add(e[k].replace("/", os.sep))
    return reg


def _has_banner(path, words=("SYNTHETIC", "INVALIDATED")):
    try:
        with open(path, encoding="utf-8", errors="replace") as f:
            head = "".join(f.readline() for _ in range(4))
    except OSError:
        return False
    return any(w in head for w in words)


def _scan_invalidated_citations(inv_artifacts):
    out = []
    exempt = {os.path.join("docs", "engineering", "evidence-invalidation-register.md"),
              "evidence-provenance.yaml"}
    for dirpath, dirs, files in os.walk(MACHINE):
        dirs[:] = [d for d in dirs if d not in ("raw", "server", "download")]
        for fn in files:
            if not fn.endswith((".yaml", ".md")):
                continue
            p = os.path.join(dirpath, fn)
            rel = os.path.relpath(p, ROOT)
            if rel.replace(os.sep, "/") in exempt or os.path.basename(rel) in \
                    (os.path.basename(a) for a in exempt) or _has_banner(p):
                continue
            try:
                content = open(p, encoding="utf-8", errors="replace").read()
            except OSError:
                continue
            for art in inv_artifacts:
                if art in content:
                    out.append("INVALIDATED_AS_CURRENT: %s cites invalidated artifact %s"
                               % (rel, art))
    return out


def _scan_unregistered_evidence(registered):
    out = []
    for dirpath, dirs, files in os.walk(MACHINE):
        dirs[:] = [d for d in dirs if d not in ("server", "download", "diag")]
        for fn in files:
            p = os.path.join(dirpath, fn)
            rel = os.path.relpath(p, ROOT)
            is_metrics = fn.endswith("-final-metrics.txt")
            is_results = fn.endswith("-results.yaml") or fn.endswith("-results.yml")
            if not (is_metrics or is_results):
                continue
            if _has_banner(p) and is_results:
                continue  # banner-marked legacy result files are exempt
            rel_norm = rel.replace(os.sep, "/")
            if rel_norm not in {r.replace(os.sep, "/") for r in registered}:
                out.append("ACTIVE_EVIDENCE_UNREGISTERED: %s is active empirical evidence but no "
                           "provenance entry references it" % rel)
    return out


# --------------------------------------------------------------------------
# Original audits (hardcoded outputs, pack claims, committed results, tree)
# --------------------------------------------------------------------------

TIMING_CLAMP_PATTERNS = [
    (re.compile(r"Math\.max\(\s*\d+\s*,\s*\w*[Nn]s\b"), "Math.max clamp on ns value"),
    (re.compile(r"Math\.min\(\s*\w*[Nn]s\w*\s*,\s*\d+\s*\)"), "Math.min clamp on ns value"),
    (re.compile(r"nJni\s*=\s*0?\.\d+"), "hardcoded nJni fraction"),
    (re.compile(r"^\s*(T_JNI|T_HANDOFF|JNI_NS|HANDOFF_NS)\s*[:=]\s*\d+", re.M), "fixed timing constant"),
    (re.compile(r"clamp\w*\(\s*\d+"), "explicit numeric clamp call"),
]
SYNTHETIC_PATTERNS = [
    (re.compile(r"SYNTHETIC_MSPT|mspt_baseline_table|synthetic_results\s*=\s*\{"), "synthetic result table"),
]


def audit_tools():
    out = []
    for dirpath, _dirs, files in os.walk(os.path.join(ROOT, "tools")):
        for fn in files:
            if not fn.endswith((".java", ".py")):
                continue
            p = os.path.join(dirpath, fn)
            rel = os.path.relpath(p, ROOT)
            if rel.replace(os.sep, "/") == "tools/verify_evidence_integrity.py":
                continue  # skip self: regex definitions are not violations
            try:
                with open(p, encoding="utf-8", errors="replace") as f:
                    for i, line in enumerate(f, 1):
                        for rx, label in TIMING_CLAMP_PATTERNS:
                            if rx.search(line):
                                out.append("TIMING_CLAMP: %s:%d: %s: %s"
                                           % (rel, i, label, line.strip()[:120]))
                        for rx, label in SYNTHETIC_PATTERNS:
                            if rx.search(line):
                                out.append("SYNTHETIC_RESULT: %s:%d: %s: %s"
                                           % (rel, i, label, line.strip()[:120]))
            except OSError as e:
                out.append("IO: %s: %s" % (rel, e))
    return out


def audit_pack_claims():
    out = []
    meta = os.path.join(MACHINE, "pack-installations.yaml")
    for dirpath, _dirs, files in os.walk(os.path.join(ROOT, "docs")):
        for fn in files:
            if not fn.endswith(".md"):
                continue
            p = os.path.join(dirpath, fn)
            rel = os.path.relpath(p, ROOT)
            with open(p, encoding="utf-8", errors="replace") as f:
                for i, line in enumerate(f, 1):
                    if "REAL_PACK_VALIDATED" in line and not os.path.isfile(meta):
                        out.append("PACK_CLAIM_NO_METADATA: %s:%d: REAL_PACK_VALIDATED claim but "
                                   "machine/pack-installations.yaml missing" % (rel, i))
    return out


def audit_generated_committed():
    out = []
    for sub in ("raw", "targetA", "targetB", "targetC", "targetD"):
        d = os.path.join(MACHINE, sub)
        if not os.path.isdir(d):
            continue
        for fn in os.listdir(d):
            p = os.path.join(d, fn)
            if not os.path.isfile(p):
                continue
            r = subprocess.run(["git", "ls-files", "--error-unmatch",
                                os.path.relpath(p, ROOT)],
                               cwd=ROOT, capture_output=True, text=True)
            if r.returncode != 0:
                out.append("RESULT_NOT_COMMITTED: %s exists on disk but is not committed"
                           % os.path.relpath(p, ROOT))
    return out


def audit_tree(require_clean):
    r = subprocess.run(["git", "status", "--porcelain"], cwd=ROOT,
                       capture_output=True, text=True)
    dirty = [l for l in r.stdout.splitlines() if l.strip()]
    if require_clean and dirty:
        return ["DIRTY_TREE: working tree has %d uncommitted paths during finalization"
                % len(dirty)]
    return []


def run_all(require_clean=False):
    violations = []
    violations += audit_tools()
    violations += audit_provenance()
    violations += audit_pack_claims()
    violations += audit_generated_committed()
    violations += audit_tree(require_clean)
    return violations


def main():
    require_clean = "--require-clean-tree" in sys.argv
    violations = run_all(require_clean)
    if violations:
        print(f"EVIDENCE INTEGRITY: {len(violations)} violation(s)")
        for x in violations:
            print("  " + x)
        sys.exit(1)
    print("EVIDENCE INTEGRITY: clean")


if __name__ == "__main__":
    main()
