"""Verified text-patch runner — replaces raw `python - <<EOF s.replace()`
heredocs, whose silent drift cost five campaign boots (dev8-12: one scripted
patch restored an old jobEnd, permanently disabling capture).

Two modes:

1. APPLY — perform replacements with occurrence verification and atomic
   write. Every `--replace OLD===NEW` must find OLD exactly once (or the
   stated count with --replace-all for >=1); on any failure the file is
   left untouched and nothing is written.

       python tools/runscope/rustcraft_runscope.py patch \
           --file tools/bridge/src/com/rustcraft/bridge/Hook.java \
           --replace "old expression===new expression" \
           --must-contain "DEPTH\\.set" \
           --must-not-contain "skyBlockOrdinal"

2. CHECK — postcondition-only drift detector (no writes). Run after later
   scripted edits to prove earlier invariants survived:

       ... patch --file Hook.java --check \
           --must-contain "Math\\.max\\(0, depth - 1\\)"

For .java files the must-contain / must-not-contain checks run against
comment-and-string-stripped source (bridge_lint.strip_java) so prose can
neither satisfy nor trip them; replacements always operate on raw text.

Exit codes: 0 all checks passed; 1 a check failed or a replacement count
did not match (file untouched in apply mode).
"""

import os
import re
import sys


def _strip_for_checks(path, text):
    if path.endswith(".java"):
        import bridge_lint
        return bridge_lint.strip_java(text)
    return text


def _parse_replacement(spec):
    if "===" not in spec:
        raise SystemExit("--replace expects OLD===NEW (got %r)" % spec)
    old, new = spec.split("===", 1)
    if not old:
        raise SystemExit("--replace OLD must be non-empty")
    return old, new


def run_patch(file_path, replacements=(), replace_all=(),
              must_contain=(), must_not_contain=(), check_only=False):
    """Returns (ok, messages). Never writes unless every replacement
    verifies."""
    messages = []
    if not os.path.isfile(file_path):
        return False, ["file not found: %s" % file_path]
    with open(file_path, "r", encoding="utf-8", newline="") as fh:
        original = fh.read()

    text = original
    ok = True
    applied = []
    for spec in replacements:
        old, new = _parse_replacement(spec)
        n = text.count(old)
        if n != 1:
            ok = False
            messages.append(
                "REPLACE-FAIL %r occurs %d times (expected exactly 1) — "
                "file left untouched" % (old[:80], n))
            continue
        text = text.replace(old, new, 1)
        applied.append((old, new))
    for spec in replace_all:
        old, new = _parse_replacement(spec)
        n = text.count(old)
        if n == 0:
            ok = False
            messages.append(
                "REPLACE-ALL-FAIL %r occurs 0 times — file left untouched"
                % old[:80])
            continue
        text = text.replace(old, new)
        applied.append((old, new + "  [x%d]" % n))
    if not ok:
        return False, messages

    # postconditions on the final content
    check_text = _strip_for_checks(file_path, text)
    for pattern in must_contain:
        if not re.search(pattern, check_text):
            ok = False
            messages.append("MUST-CONTAIN-FAIL /%s/ not found in %s"
                            % (pattern, os.path.basename(file_path)))
        else:
            messages.append("must-contain OK    /%s/" % pattern)
    for pattern in must_not_contain:
        if re.search(pattern, check_text):
            ok = False
            messages.append("MUST-NOT-CONTAIN-FAIL /%s/ present in %s"
                            % (pattern, os.path.basename(file_path)))
        else:
            messages.append("must-not-contain OK /%s/" % pattern)

    if check_only:
        if ok:
            messages.insert(0, "CHECK-OK   %s" % file_path)
        return ok, messages

    if not ok:
        return False, messages
    if text == original and applied:
        messages.append("note: replacements produced identical content")
    # atomic write
    tmp = file_path + ".patch-tmp"
    with open(tmp, "w", encoding="utf-8", newline="") as fh:
        fh.write(text)
    os.replace(tmp, file_path)
    messages.insert(0, "PATCHED    %s  (%d replacement(s))"
                    % (file_path, len(applied)))
    return ok, messages
