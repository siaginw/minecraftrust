"""Normalized boot-sequence differ — pinpoints the FIRST lifecycle
divergence between two server launches (the §11 hunt: normal Revelation vs
custom tweaker launch was done by hand three times with grep).

Both logs are reduced to boot-relevant lines (tweaker chain, coremod
discovery, signature warnings, mod-scan paths, mixin subsystem, FML phases,
RustCraft seam lines, boot completion), normalized by stripping timestamps
and thread/logger decoration, then aligned with difflib.

Output:
- per-category line counts for each side (e.g. mixin 306 vs 299)
- the tweaker sequences side by side
- the first aligned divergence with a few lines of context

Deterministic. Read-only.
"""

import difflib
import os
import re

# line reducers: (category, regex) — first match wins
_BOOT_FILTERS = [
    ("tweaker", re.compile(r"Loading tweak class name (\S+)|"
                           r"Tweak class name (\S+)")),
    ("coremod", re.compile(r"coremod|IFMLLoadingPlugin|FMLLoadingPlugin|"
                           r"candidate.*coremod", re.I)),
    ("cert", re.compile(r"not signed|certificate|corrupt|signature", re.I)),
    ("modscan", re.compile(r"Searching .* for (mods|coremods)|"
                           r"Scanning mod file|candidate (jar|mod)|"
                           r"identified \d+ mods|found \d+ mods")),
    ("mixin", re.compile(r"mixin", re.I)),
    ("fml", re.compile(r"Forge Mod Loader|Forge version|MinecraftForge|"
                       r"FML is|FML has found|mod reloader|mod list")),
    ("rustcraft", re.compile(r"RustCraft|worldLight|phosphorLight|"
                             r"LIGHT_HOOK|WORLD_CHECK")),
    ("done", re.compile(r"Done \(\d|Preparing level|Starting minecraft "
                        r"server|Loading properties")),
]

_LINE_PREFIX = re.compile(
    r"^\[\d\d:\d\d:\d\d\]\s+\[[^\]]+\]\s+\[[^\]]*\]:?\s?")
_TS_ONLY = re.compile(r"^\[\d\d:\d\d:\d\d\]\s*")


def _normalize(path):
    """-> (list of (category, normalized_message), unfiltered_count)"""
    entries = []
    total = 0
    if not os.path.isfile(path):
        return entries, 0
    with open(path, "r", encoding="utf-8", errors="replace") as fh:
        for line in fh:
            total += 1
            line = _LINE_PREFIX.sub("", line.rstrip("\n"))
            line = _TS_ONLY.sub("", line.rstrip("\n")).strip()
            if not line:
                continue
            for cat, rx in _BOOT_FILTERS:
                if rx.search(line):
                    entries.append((cat, line))
                    break
    return entries, total


def boot_diff(path_a, path_b, context=3):
    ea, ta = _normalize(path_a)
    eb, tb = _normalize(path_b)
    msgs_a = [m for _c, m in ea]
    msgs_b = [m for _c, m in eb]
    sm = difflib.SequenceMatcher(a=msgs_a, b=msgs_b, autojunk=False)
    first_div = None
    for op, i1, i2, j1, j2 in sm.get_opcodes():
        if op == "equal":
            continue
        first_div = {
            "opcode": op,
            "a_index": i1, "b_index": j1,
            "a_lines": msgs_a[i1:min(i2, i1 + context)],
            "b_lines": msgs_b[j1:min(j2, j1 + context)],
            "context_before": msgs_a[max(0, i1 - context):i1],
        }
        break
    cats = sorted({c for c, _m in ea} | {c for c, _m in eb})
    counts = {}
    for c in cats:
        counts[c] = {"a": sum(1 for x in ea if x[0] == c),
                     "b": sum(1 for x in eb if x[0] == c)}
    tweakers_a = [m.split()[-1] for c, m in ea if c == "tweaker"]
    tweakers_b = [m.split()[-1] for c, m in eb if c == "tweaker"]
    return {
        "a": path_a, "b": path_b,
        "a_total": ta, "b_total": tb,
        "a_boot_lines": len(msgs_a), "b_boot_lines": len(msgs_b),
        "category_counts": counts,
        "tweakers": {"a": tweakers_a, "b": tweakers_b,
                     "identical": tweakers_a == tweakers_b},
        "first_divergence": first_div,
    }


def render_bootdiff(d):
    out = []
    ap = out.append
    ap("=" * 78)
    ap("BOOTDIFF  A=%s (%s boot lines of %d total)"
       % (d["a"], d["a_boot_lines"], d["a_total"]))
    ap("          B=%s (%s boot lines of %d total)"
       % (d["b"], d["b_boot_lines"], d["b_total"]))
    ap("CATEGORY COUNTS  (A / B)")
    for c, n in d["category_counts"].items():
        flag = "   <-- differs" if n["a"] != n["b"] else ""
        ap("  %-10s %5d / %5d%s" % (c, n["a"], n["b"], flag))
    tw = d["tweakers"]
    ap("TWEAKER SEQUENCE %s" % ("identical" if tw["identical"]
                                else "DIFFERS"))
    for i in range(max(len(tw["a"]), len(tw["b"]))):
        a = tw["a"][i] if i < len(tw["a"]) else "-"
        b = tw["b"][i] if i < len(tw["b"]) else "-"
        mark = "" if a == b else "   <--"
        ap("  %-58s %s" % (a if a != b else a, mark))
        if a != b:
            ap("  %-58s" % b)
    fd = d["first_divergence"]
    if fd is None:
        ap("FIRST DIVERGENCE  none — reduced boot sequences are identical")
    else:
        ap("FIRST DIVERGENCE (%s at A#%d / B#%d):" % (
            fd["opcode"], fd["a_index"], fd["b_index"]))
        for line in fd["context_before"]:
            ap("  =  %s" % line[:110])
        for line in fd["a_lines"]:
            ap("  A  %s" % line[:110])
        for line in fd["b_lines"]:
            ap("  B  %s" % line[:110])
    ap("=" * 78)
    return "\n".join(out)
