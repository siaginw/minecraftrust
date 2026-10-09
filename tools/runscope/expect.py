"""Durable seam invariants — a committed regression suite for the
bridge/hook files, derived from real incidents (each expectation cites the
run that motivated it).

Where `lint` checks generic bug SHAPES across all files, `expect` checks
specific invariants of specific files: "this fix must survive all future
scripted edits". This is the mechanical answer to the dev8-12 failure mode
(a scripted s.replace chain silently restored an old jobEnd; five boots
showed jobs=0 before anyone knew why).

Expectation file: tools/runscope/expectations.json

    [
      {"id": "phosphor-depth-decrement",
       "file": "tools/bridge/src/com/rustcraft/bridge/PhosphorLightHook.java",
       "must_contain": ["Math.max(0, depth - 1)"],
       "must_not_contain": [],
       "reason": "jobEnd must decrement DEPTH before the ordinal check ...",
       "incident": "dev8-12: depth never decremented -> jobs=0 forever"} ]

Rules mirror `patch` postconditions: for .java files the patterns run
against comment-and-string-stripped source (prose cannot satisfy or trip
them). Exit 1 when any expectation fails; failing expectations name the
incident, so the agent knows what regression it is looking at.

When an expectation legitimately becomes stale (a deliberate redesign),
UPDATE the expectation in the same commit — never delete it silently.
"""

import json
import os
import re
import sys

_HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_EXPECTATIONS = os.path.join(_HERE, "expectations.json")


def load_expectations(path=None):
    p = path or DEFAULT_EXPECTATIONS
    with open(p, "r", encoding="utf-8") as fh:
        data = json.load(fh)
    return data if isinstance(data, list) else data.get("expectations", [])


def check_expectation(repo_root, exp):
    """Returns (ok, messages)."""
    rel = exp["file"]
    path = rel if os.path.isabs(rel) else os.path.join(repo_root, rel)
    if not os.path.isfile(path):
        return False, ["file missing: %s" % rel]
    with open(path, "r", encoding="utf-8", errors="replace") as fh:
        raw = fh.read()
    if path.endswith(".java"):
        import bridge_lint
        # real code content: comments removed, string-constant MARKERS kept
        text = bridge_lint.strip_comments_only(raw)
    else:
        text = raw
    messages = []
    ok = True
    for pattern in exp.get("must_contain", []):
        if re.search(pattern, text):
            messages.append("  OK   must-contain    /%s/" % pattern)
        else:
            ok = False
            messages.append("  FAIL must-contain    /%s/" % pattern)
    for pattern in exp.get("must_not_contain", []):
        if re.search(pattern, text):
            ok = False
            messages.append("  FAIL must-not-contain /%s/" % pattern)
        else:
            messages.append("  OK   must-not-contain /%s/" % pattern)
    return ok, messages


def run_expectations(repo_root, path=None, only_ids=None):
    exps = load_expectations(path)
    if only_ids:
        exps = [e for e in exps if e.get("id") in only_ids]
    results = []
    ok_all = True
    for exp in exps:
        ok, messages = check_expectation(repo_root, exp)
        ok_all = ok_all and ok
        results.append({"id": exp.get("id"), "file": exp["file"],
                        "ok": ok, "messages": messages,
                        "incident": exp.get("incident"),
                        "reason": exp.get("reason")})
    return ok_all, results


def render(results, ok_all):
    out = []
    failed = [r for r in results if not r["ok"]]
    out.append("runscope expect: %d/%d invariants hold%s" % (
        len(results) - len(failed), len(results),
        "" if ok_all else "  <-- REGRESSION"))
    for r in results:
        marker = "PASS" if r["ok"] else "FAIL"
        out.append("%s  %-28s %s" % (marker, r["id"], r["file"]))
        for m in r["messages"]:
            out.append(m)
        if not r["ok"]:
            if r.get("reason"):
                out.append("  invariant: %s" % r["reason"])
            if r.get("incident"):
                out.append("  incident:  %s" % r["incident"])
    if failed:
        out.append("A failed expectation means a fix REGRESSED or a file "
                   "was legitimately redesigned: update the expectation in "
                   "the same commit (tools/runscope/expectations.json), "
                   "never silently.")
    return "\n".join(out)
