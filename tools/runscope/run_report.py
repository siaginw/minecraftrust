"""Campaign-run evidence digester for RustCraft authority runs.

One command replaces the per-run grep gymnastics (server.log greps, JSON
snippets, jar size comparisons) that surrounded every dev iteration of the
block-light work:

    python tools/runscope/rustcraft_runscope.py report  target/authority-review/light-mut-C-dev14
    python tools/runscope/rustcraft_runscope.py compare target/authority-review/light-mut-C-dev13 \
                                                        target/authority-review/light-mut-C-dev14

What it surfaces (each maps to a boot-wasting trap that actually happened):

- receipt verdict / exit_reason / failure_reasons / evidence targets vs
  observed (per-run receipt.json)
- STAGED-JAR PROVENANCE: sha256 of the campaign jar inside the run's server
  dir vs the current target/rustcraft-campaign-<target>.jar — the runner
  stages exact paths, and stale-jar staging silently invalidated two debug
  cycles (light-cell-diag4, dev2) before being caught by manual size diffs.
- duplicate mod jars in the staged mods/ tree (the "two phosphor jars?"
  hypothesis cost a boot to disprove)
- timeline reconstruction from server.log: boot Done, probe logins/logouts,
  teleports, setblock rejections — the probe-disconnect-then-mutate bug
  (light-shadow-A-full1) is a 3-line read here instead of log archaeology
- exception histogram with first/last line numbers (NPE storms like the
  276k-error diag6 / 6.7M-error dev5 sessions)
- last RustCraft light-counter lines (worldLight.shadow / phosphorLight.hook)
  parsed into a key=value table
- mutation receipt (light-mutations.json) step table with per-step deltas

Read-only over target/; never touches the run directories.
"""

import hashlib
import json
import os
import re
import sys


def _read_json(path):
    try:
        with open(path, "r", encoding="utf-8", errors="replace") as fh:
            return json.load(fh)
    except (OSError, ValueError):
        return None


_METRICS_KV_RE = re.compile(r"([\w.]+)=(-?[0-9.]+)")


def parse_metrics_file(path):
    """Run-dir metrics snapshot (region-metrics.txt): the tweaker rewrites
    it with the complete counter set, so unlike server.log it survives the
    process-kill race — the authoritative counter source when present
    (commit 585a425 lesson)."""
    counters = {}
    if not os.path.isfile(path):
        return None
    with open(path, "r", encoding="utf-8", errors="replace") as fh:
        for line in fh:
            for k, v in _METRICS_KV_RE.findall(line):
                try:
                    counters[k] = float(v) if "." in v else int(v)
                except ValueError:
                    pass
    return counters or None


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


# ----------------------------------------------------------------------
# server.log scanning

_TS_RE = re.compile(r"^\[(\d\d:\d\d:\d\d)\]")
_DONE_RE = re.compile(r"Done \(([0-9.]+)s\)!") 
_LOGIN_RE = re.compile(r"(\w+)\[.*?\] logged in with entity id \d+ at "
                       r"\(([-0-9.]+), ([-0-9.]+), ([-0-9.]+)\)")
_LOGOUT_RE = re.compile(r"(\w+) lost connection|\w+ left the game")
_TELEPORT_RE = re.compile(r"Teleported (\w+) to \(([-0-9.]+), ([-0-9.]+), "
                          r"([-0-9.]+)\)")
_LAG_RE = re.compile(r"Can't keep up! .*?Running (\d+)ms or \d+ ticks behind")
_SETBLOCK_FAIL_RE = re.compile(
    r"Cannot place block outside of the world|Could not set the block")
_EXC_RE = re.compile(r"([\w.$]+(?:Exception|Error))")
_NOISE_RE = re.compile(r"log4j|CLASS_NOT_FOUND|ServerGuiConsole|"
                       r"\[bootstrap\]|GLFW error")
_TWEAK_RE = re.compile(r"Loading tweak class name (\S+)")
_LIGHT_PREFIXES = ("worldLight.shadow", "phosphorLight.hook",
                   "lightAuthority", "WORLD_CHECK_LIGHT_HOOKED",
                   "LIGHT_HOOK_PLACED", "CHECK_LIGHT_AUTHORITY_HOOKED",
                   "transformer status=")
_WINDOW_OPEN_RE = re.compile(r"compare window OPEN|compare window")
_JOB_SKIP_RE = re.compile(r"\[RustCraft-Light\] job skip: (\S+)")
_KV_RE = re.compile(r"(\w+)=(-?[0-9.]+)")
_TIMELINE_COUNTER_CAP = 60


def scan_server_log(run_dir, max_timeline=40):
    log_path = os.path.join(run_dir, "server.log")
    events = []
    exceptions = {}
    lag = {"count": 0, "max_ms": 0}
    setblock_fails = 0
    counters = {}
    tweaks = []
    last_light_lines = {}
    counter_progress = []
    if not os.path.exists(log_path):
        return {"events": [], "exceptions": {}, "lag": lag,
                "setblock_fails": 0, "counters": {}, "tweaks": [],
                "last_light_lines": {}, "counter_progress": [],
                "log_present": False}
    with open(log_path, "r", encoding="utf-8", errors="replace") as fh:
        for lineno, line in enumerate(fh, 1):
            ts = _TS_RE.match(line)
            ts = ts.group(1) if ts else None
            m = _DONE_RE.search(line)
            if m and not any(e[1] == "boot-done" for e in events):
                events.append((ts, "boot-done", "Done in %ss" % m.group(1)))
                continue
            m = _LOGIN_RE.search(line)
            if m:
                events.append((ts, "login", "%s at (%s, %s, %s)"
                               % (m.group(1), m.group(2), m.group(3),
                                  m.group(4))))
                continue
            m = _LOGOUT_RE.search(line)
            if m:
                who = m.group(1) or "?"
                if events and events[-1][1] == "logout" \
                        and events[-1][0] == ts:
                    continue  # lost-connection + left-the-game pair
                events.append((ts, "logout", who))
                continue
            m = _TELEPORT_RE.search(line)
            if m:
                events.append((ts, "teleport", "%s -> (%s, %s, %s)"
                               % (m.group(1), m.group(2), m.group(3),
                                  m.group(4))))
                continue
            m = _LAG_RE.search(line)
            if m:
                lag["count"] += 1
                lag["max_ms"] = max(lag["max_ms"], int(m.group(1)))
                continue
            if _SETBLOCK_FAIL_RE.search(line):
                setblock_fails += 1
                if not any(e[1] == "setblock-fail" for e in events):
                    events.append((ts, "setblock-fail", line.strip()[:110]))
                continue
            if "log4j" not in line:
                m = _EXC_RE.search(line)
                if m:
                    sig = m.group(1)
                    ent = exceptions.setdefault(
                        sig, {"count": 0, "first_line": lineno,
                              "last_line": lineno, "first_ts": ts,
                              "sample": line.strip()[:130]})
                    ent["count"] += 1
                    ent["last_line"] = lineno
            m = _WINDOW_OPEN_RE.search(line)
            if m and not any(e[1] == "window-open" for e in events):
                events.append((ts, "window-open", line.strip()[:110]))
                continue
            m = _JOB_SKIP_RE.search(line)
            if m and not any(e[1] == "job-skip" for e in events):
                events.append((ts, "job-skip", "first skip reason: %s"
                               % m.group(1)))
                continue
            m = _TWEAK_RE.search(line)
            if m and m.group(1) not in tweaks:
                tweaks.append(m.group(1))
            for prefix in _LIGHT_PREFIXES:
                idx = line.find(prefix)
                if idx >= 0:
                    payload = line[idx:].strip()
                    last_light_lines[prefix] = {"ts": ts,
                                                "line": payload[:240]}
                    prev = dict(counter_progress[-1]["counters"]) \
                        if counter_progress else {}
                    for k, v in _KV_RE.findall(payload):
                        try:
                            counters[k] = float(v) if "." in v else int(v)
                        except ValueError:
                            pass
                    counter_progress.append({
                        "ts": ts, "counters": dict(counters),
                        "changed": {k: (prev.get(k), v)
                                    for k, v in counters.items()
                                    if prev.get(k) != v}})
    return {"events": events[:max_timeline], "exceptions": exceptions,
            "lag": lag, "setblock_fails": setblock_fails,
            "counters": counters, "tweaks": tweaks,
            "last_light_lines": last_light_lines,
            "counter_progress": counter_progress[-_TIMELINE_COUNTER_CAP:],
            "log_present": True}


# ----------------------------------------------------------------------
# staged artifact provenance

def transform_diag_tails(run_dir, per_file=6):
    """Per-candidate transformer diagnostics (the §2 file-based stack
    capture pattern: worldlight-transform-diag.log and siblings). These
    name the exact candidate/method decisions a boot made - the difference
    between 'transformer never fired' and 'fired on the wrong shape'."""
    server_dir = os.path.join(run_dir, "server")
    if not os.path.isdir(server_dir):
        return []
    out = []
    for name in sorted(os.listdir(server_dir)):
        if not (name.endswith(".log") and "diag" in name.lower()):
            continue
        path = os.path.join(server_dir, name)
        try:
            with open(path, "r", encoding="utf-8", errors="replace") as fh:
                lines = fh.readlines()
        except OSError:
            continue
        out.append({"file": name, "lines": len(lines),
                    "tail": [l.rstrip()[:170] for l in lines[-per_file:]]})
    return out


def staged_jar_provenance(run_dir, repo_root, target=None):
    out = []
    server_dir = os.path.join(run_dir, "server")
    if not os.path.isdir(server_dir):
        return out
    for name in sorted(os.listdir(server_dir)):
        if not (name.startswith("rustcraft-campaign") and
                name.endswith(".jar")):
            continue
        run_jar = os.path.join(server_dir, name)
        run_sha = sha256_file(run_jar)
        # the canonical build output for this run's target letter
        t = target or ""
        candidates = [
            os.path.join(repo_root, "target",
                         "rustcraft-campaign-%s.jar" % t),
            os.path.join(repo_root, "target", "rustcraft-campaign.jar"),
        ]
        status, cur_path, cur_sha = "?", None, None
        for cand in candidates:
            if os.path.isfile(cand):
                cur_path, cur_sha = cand, sha256_file(cand)
                status = "MATCH" if cur_sha == run_sha else "STALE"
                break
        if cur_path is None:
            status = "NO-CURRENT-BUILD"
        out.append({"jar": name, "run_sha256": run_sha,
                    "run_size": os.path.getsize(run_jar),
                    "current": _rel(cur_path, repo_root)
                    if cur_path else None,
                    "current_sha256": cur_sha, "status": status})
    return out


def mods_duplicates(run_dir):
    mods_dir = os.path.join(run_dir, "server", "mods")
    if not os.path.isdir(mods_dir):
        return []
    seen = {}
    for dirpath, _dirs, names in os.walk(mods_dir):
        for n in names:
            if n.lower().endswith(".jar"):
                seen.setdefault(n.lower(), []).append(
                    _rel(os.path.join(dirpath, n), run_dir))
    return [{"jar": k, "copies": v} for k, v in sorted(seen.items())
            if len(v) > 1]


def _rel(path, root):
    try:
        return os.path.relpath(path, root)
    except ValueError:
        return path


# ----------------------------------------------------------------------

def bakeoff_analysis(run_dir):
    """OPT-SYNC-006 retro (2026-10-09): guarded paired bake-off table from
    the BAKE0_/BAKE1_ counters. Guards: (a) per-arm section denominators
    are checked for ZERO — an unwired counter once made us/s rows divide
    by 1 silently; (b) the boot-anomaly check replicates the campaign
    runner's PAIR-ANOMALY gate (REGISTER > 800ms in paired shape, or
    stage-phase ratio > 3x — ABSENT is excluded: the first arm's
    markSectionAbsent does the real native transition, the second is an
    idempotent no-op, so its ~10x first-arm skew exists in EVERY clean
    boot). Returns None when the run carries no BAKE counters."""
    metrics = parse_metrics_file(os.path.join(run_dir, "region-metrics.txt"))
    if not metrics or not any(k.startswith("BAKE0_") for k in metrics):
        return None
    phases = ("SEC_ALLOC_NS", "PRECHECK_NS", "EXTRACT_NS", "DV_NS",
              "STAGE_NS", "JNI_NS", "VALIDATE_NS", "ABSENT_NS")
    arms = []
    for arm in ("0", "1"):
        t = {k[6:]: v for k, v in metrics.items()
             if k.startswith("BAKE%s_" % arm)}
        n = int(t.get("SECTIONS", 0))
        arms.append({
            "arm": arm,
            "sections": n,
            "sections_zero_denominator": n == 0,  # per-us rows invalid
            "sec_total_ms": int(t.get("SEC_TOTAL_NS", 0)) / 1e6,
            "alloc_mb": int(t.get("ALLOC_BYTES", 0)) / 1e6,
            "phases_ms": {p: int(t.get(p, 0)) / 1e6 for p in phases},
            "phases_us_per_section": (None if n == 0 else
                {p: int(t.get(p, 0)) / 1000.0 / n for p in phases}),
        })
    c0t, c1t = arms[0]["sec_total_ms"], arms[1]["sec_total_ms"]
    denom = c0t if c0t > 0 else 1.0
    reg_ms = int(metrics.get("REGISTER_NANOS", 0)) / 1e6
    s0 = int(metrics.get("BAKE0_STAGE_NS", 0))
    s1 = int(metrics.get("BAKE1_STAGE_NS", 0))
    stage_ratio = None
    lo, hi = min(s0, s1), max(s0, s1)
    if lo > 300_000:
        stage_ratio = round(hi / lo, 2)
    timing_suspect = reg_ms > 800.0 or (stage_ratio is not None
                                        and stage_ratio > 3.0)
    return {
        "arms": arms,
        "reduction_pct": round((c0t - c1t) / denom * 100, 1),
        "speedup_x": round(c0t / c1t, 3) if c1t > 0 else None,
        "register_ms": round(reg_ms, 1),
        "stage_ratio": stage_ratio,
        "timing_suspect": timing_suspect,
        "note": ("reduction=(arm0-arm1)/arm0; speedup=arm0/arm1 — distinct "
                 "statistics, do not interchange. arm order: see the "
                 "[sync-pair] boot line. ABSENT first-arm skew is a "
                 "permanent order artifact, not an anomaly signal"),
    }


def build_report(run_dir, repo_root):
    receipt = _read_json(os.path.join(run_dir, "receipt.json"))
    mutations = _read_json(os.path.join(run_dir, "light-mutations.json"))
    probes = _read_json(os.path.join(run_dir, "probe-receipts.json"))
    log_scan = scan_server_log(run_dir)
    metrics = parse_metrics_file(os.path.join(run_dir, "region-metrics.txt"))
    target = (receipt or {}).get("target")
    return {
        "metrics_file": {"path": "region-metrics.txt",
                         "counters": metrics} if metrics else None,
        "run_dir": _rel(run_dir, repo_root) if os.path.isdir(run_dir)
        else run_dir,
        "receipt": {
            "verdict": (receipt or {}).get("verdict"),
            "exit_reason": (receipt or {}).get("exit_reason"),
            "failure_reasons": (receipt or {}).get("failure_reasons"),
            "test_tier": (receipt or {}).get("test_tier"),
            "target": target,
            "git_sha": (receipt or {}).get("git_sha"),
            "wall_time_s": (receipt or {}).get("wall_time_s"),
            "targets": (receipt or {}).get("evidence_targets"),
            "observed": (receipt or {}).get("evidence_observed"),
        },
        "mutations": None if mutations is None else {
            "passed": mutations.get("passed"),
            "failed_steps": mutations.get("failedSteps"),
            "final": mutations.get("final"),
            "steps": [
                {"step": s.get("step"),
                 "delta": s.get("cellDelta", s.get("jobDelta")),
                 "ok": s.get("ok"),
                 "settled_s": s.get("settleS")}
                for s in mutations.get("steps", [])],
        },
        "probes": probes,
        "bakeoff": bakeoff_analysis(run_dir),
        "staged_jars": staged_jar_provenance(run_dir, repo_root, target),
        "mods_duplicates": mods_duplicates(run_dir),
        "transform_diag": transform_diag_tails(run_dir),
        "log": log_scan,
    }


def compare_reports(a_dir, b_dir, repo_root):
    ra = build_report(a_dir, repo_root)
    rb = build_report(b_dir, repo_root)

    def counters(r):
        out = {}
        mf = (r.get("metrics_file") or {}).get("counters") or {}
        out.update(mf)
        out.update(r["receipt"]["observed"] or {})
        out.update(r["mutations"]["final"] or {}) \
            if r["mutations"] else None
        return out

    ca, cb = counters(ra), counters(rb)
    deltas = {}
    for k in sorted(set(ca) | set(cb)):
        va, vb = ca.get(k), cb.get(k)
        if isinstance(va, (int, float)) and isinstance(vb, (int, float)):
            deltas[k] = {"a": va, "b": vb, "delta": vb - va}
        elif va != vb:
            deltas[k] = {"a": va, "b": vb}
    return {
        "a": ra["run_dir"], "b": rb["run_dir"],
        "verdicts": {"a": ra["receipt"]["verdict"],
                     "b": rb["receipt"]["verdict"],
                     "exit": {"a": ra["receipt"]["exit_reason"],
                              "b": rb["receipt"]["exit_reason"]}},
        "counter_deltas": deltas,
        "staged_jars": {"a": ra["staged_jars"], "b": rb["staged_jars"]},
        "log": {
            "lag": {"a": ra["log"]["lag"], "b": rb["log"]["lag"]},
            "setblock_fails": {"a": ra["log"]["setblock_fails"],
                               "b": rb["log"]["setblock_fails"]},
            "exceptions_top": {
                "a": sorted(ra["log"]["exceptions"].items(),
                            key=lambda kv: -kv[1]["count"])[:5],
                "b": sorted(rb["log"]["exceptions"].items(),
                            key=lambda kv: -kv[1]["count"])[:5]},
        },
    }


def _load_step_events(run_dir):
    """#6: per-step timestamps the moment the runner records them. Any of
    ts/t/time/time_s on a light-mutations step becomes a timeline event."""
    mut = _read_json(os.path.join(run_dir, "light-mutations.json"))
    if not mut:
        return []
    rows = []
    for st in mut.get("steps", []):
        ts = st.get("ts") or st.get("t") or st.get("time")             or st.get("time_s")
        if ts is None:
            continue
        rows.append((str(ts), 0, "step",
                     "%s jobs %s->%s (delta %s) ok=%s"
                     % (st.get("step"), st.get("jobsBefore"),
                        st.get("jobsAfter"),
                        st.get("jobDelta", st.get("cellDelta")),
                        st.get("ok"))))
    return rows


def _load_decision_log(run_dir):
    """#1 report side: seam-decisions.jsonl written by the hooks
    (spec in docs/engineering/RUNSCOPE.md). One JSON object per line;
    consumed leniently: ts + any of decision/reason + extras."""
    path = os.path.join(run_dir, "server", "seam-decisions.jsonl")
    if not os.path.isfile(path):
        path = os.path.join(run_dir, "seam-decisions.jsonl")
        if not os.path.isfile(path):
            return [], 0
    rows = []
    total = 0
    with open(path, "r", encoding="utf-8", errors="replace") as fh:
        for line in fh:
            line = line.strip()
            if not line:
                continue
            total += 1
            try:
                d = json.loads(line)
            except ValueError:
                continue
            ts = str(d.get("ts") or d.get("time") or "")
            reason = d.get("reason") or d.get("decision") or "?"
            hook = d.get("hook", "")
            extras = {k: v for k, v in d.items()
                      if k not in ("ts", "time", "decision", "reason",
                                   "hook") and not isinstance(v, (dict,
                                                                  list))}
            rows.append((ts, 0, "seam",
                         "%s %s %s" % (hook, reason,
                                       json.dumps(extras)[:110])[:170]))
    return rows, total


def build_timeline(run_dir):
    """Interleaved chronological view of everything timestamped in the
    run: boot/probe/window/skip events, the light-counter progression,
    per-step mutation events (when the runner records timestamps), and
    the seam decision log when the hooks write one."""
    log = scan_server_log(run_dir, max_timeline=200)
    rows = []
    for ts, kind, detail in log["events"]:
        rows.append((ts or "", 0, kind, detail))
    for cp in log.get("counter_progress", []):
        changed = ", ".join(
            "%s:%s->%s" % (k, va, vb)
            for k, (va, vb) in sorted(cp["changed"].items())
            if not str(k).startswith("_"))
        if not changed:
            continue
        rows.append((cp["ts"] or "", 1, "counters", changed[:160]))
    rows += _load_step_events(run_dir)
    decisions, _n = _load_decision_log(run_dir)
    rows += decisions
    rows.sort(key=lambda r: (r[0], r[1]))
    return rows


def render_timeline(rows):
    out = ["=" * 78,
           "TIMELINE (events + light-counter changes, chronological; "
           "per-step cause-effect needs the runner's stdout, which is not "
           "a saved artifact)"]
    for ts, _prio, kind, detail in rows:
        out.append("  %s %-12s %s" % (ts or "??:??:??", kind, detail))
    out.append("=" * 78)
    return "\n".join(out)


# ----------------------------------------------------------------------
# rendering

def render_report(r):
    out = []
    ap = out.append
    rec = r["receipt"]
    ap("=" * 78)
    ap("RUN      %s" % r["run_dir"])
    ap("VERDICT  %-6s exit=%s tier=%s target=%s wall=%ss git=%s" % (
        rec["verdict"], rec["exit_reason"], rec["test_tier"],
        rec["target"], rec["wall_time_s"],
        (rec["git_sha"] or "")[:10]))
    if rec["failure_reasons"]:
        ap("FAILURES %s" % rec["failure_reasons"])
    for j in r["staged_jars"]:
        ap("JAR      %-28s %s  sha=%s%s" % (
            j["jar"], j["status"], j["run_sha256"][:16],
            ("  (current build: %s)" % j["current"]) if j["status"] ==
            "STALE" else ""))
        if j["status"] == "STALE":
            ap("         current sha=%s  <- server ran the OLD jar"
               % (j["current_sha256"] or "")[:16])
    if r["mods_duplicates"]:
        ap("MOD-DUPES %s" % ", ".join(d["jar"] for d in r["mods_duplicates"]))
    bo = r.get("bakeoff")
    if bo:
        ap("BAKEOFF  arm0 vs arm1 (paired in-vivo; order alternates per "
           "section; work counters count arm0)")
        for a in bo["arms"]:
            warn = ("  [ZERO-DENOMINATOR: SECTIONS=0 — us/section rows "
                    "INVALID (unwired counter class)") \
                if a["sections_zero_denominator"] else ""
            ap("  arm%s   sections=%-6d total=%8.1fms alloc=%7.1fMB%s" % (
                a["arm"], a["sections"], a["sec_total_ms"],
                a["alloc_mb"], warn))
            ph = a["phases_ms"]
            ap("         phases_ms: " + " ".join(
                "%s=%.1f" % (p[:-3].lower(), ph[p]) for p in sorted(ph)))
        ap("  reduction=%.1f%%  speedup=%.3fx  REGISTER=%.0fms  "
           "stage_ratio=%s" % (
               bo["reduction_pct"], bo["speedup_x"], bo["register_ms"],
               bo["stage_ratio"]))
        if bo["timing_suspect"]:
            ap("  [PAIR-ANOMALY] arm TIMING suspect (slow-boot signature; "
               "do NOT trust arm deltas — rerun swapped; correctness "
               "counters remain valid)")
    for td in r.get("transform_diag") or []:
        ap("TRANSFORM-DIAG %s (%d lines, tail):" % (td["file"], td["lines"]))
        for l in td["tail"]:
            ap("    %s" % l)
    probes = r.get("probes")
    if probes:
        ap("PROBES   (%d rounds; transport-vs-world split: bytes_in=0 with "
           "a TCP connect = server accepted and never wrote - world side "
           "may still be healthy)" % len(probes))
        for pr in probes:
            if not isinstance(pr, dict):
                continue
            times = pr.get("times") or {}
            checks = pr.get("checks") or {}
            failed_checks = [k for k, v in checks.items()
                             if v is False] if isinstance(checks, dict)                 else []
            failure = pr.get("failure") or pr.get("failure_reason") or ""
            ap("  %-8s %-10s bytes_in=%-9s packets_in=%-8s times=%s" % (
                pr.get("verdict", "?"), (pr.get("username") or "?")[:10],
                pr.get("bytes_in"), pr.get("packets_in"),
                {k: v for k, v in times.items()
                 if isinstance(v, (int, float))}))
            if failed_checks:
                ap("         failed checks: %s" % failed_checks)
            if failure:
                ap("         failure: %s" % str(failure)[:110])
            cls = (pr.get("classification")
                   if isinstance(pr.get("classification"), dict) else None)
            if cls:
                ap("         classification: %s" % str(cls)[:110])
    mf = (r.get("metrics_file") or {}).get("counters") or {}
    log_counters = r["log"]["counters"]
    if mf or log_counters:
        final, source = {}, {}
        for k in set(mf) | set(log_counters):
            mv, lv = mf.get(k), log_counters.get(k)
            if isinstance(mv, (int, float)) and                     isinstance(lv, (int, float)):
                final[k] = max(mv, lv)
                source[k] = "log" if (lv or 0) > (mv or 0) else "metrics"
            elif mv is not None:
                final[k], source[k] = mv, "metrics"
            else:
                final[k], source[k] = lv, "log"
        ap("COUNTERS-FINAL (per-counter max of both sources; monotonic - "
           "source labeled; a log-wins row means the kill race was won "
           "and the metrics file was up to one dump-interval stale):")
        interesting = [k for k in sorted(final)
                       if not k.startswith(("regionRead", "regionWrite"))]
        for k in interesting[:24]:
            mark = "" if source[k] == "metrics" else                 "   <-- log wins (metrics snapshot was stale)"
            ap("  %-28s %12s  [%s]%s" % (k, final[k], source[k], mark))
        r["counters_final"] = {"values": final, "source": source}
    mut = r["mutations"]
    if mut:
        ap("MUTATIONS passed=%s failed=%s" % (mut["passed"],
                                              mut["failed_steps"]))
        for s in mut["steps"]:
            ap("  %-28s delta=%-6s ok=%s settle=%s" % (
                s["step"], s["delta"], s["ok"], s["settled_s"]))
        if mut["final"]:
            ap("  final: %s" % mut["final"])
    log = r["log"]
    if log["log_present"]:
        ap("LOG      lag_events=%d max_behind=%sms setblock_fails=%d "
           "exceptions=%d" % (
               log["lag"]["count"], log["lag"]["max_ms"],
               log["setblock_fails"], len(log["exceptions"])))
        top = sorted(log["exceptions"].items(),
                     key=lambda kv: -kv[1]["count"])[:6]
        for sig, ent in top:
            ap("  EXC  %-46s x%-8d first=L%d last=L%d" % (
                sig[:46], ent["count"], ent["first_line"],
                ent["last_line"]))
        if log["events"]:
            ap("TIMELINE")
            for ts, kind, detail in log["events"]:
                ap("  %s %-13s %s" % (ts or "??:??:??", kind, detail[:90]))
        if log["last_light_lines"]:
            ap("LIGHT COUNTERS (last lines)")
            for prefix, ent in sorted(log["last_light_lines"].items()):
                ap("  [%s] %s" % (ent["ts"] or "?", ent["line"][:140]))
        if log["counters"]:
            ap("COUNTERS %s" % log["counters"])
        if log["tweaks"]:
            ap("TWEAKERS %s" % " ".join(t.split(".")[-1]
                                        for t in log["tweaks"]))
    else:
        ap("LOG      server.log not present")
    ap("=" * 78)
    return "\n".join(out)


def render_compare(c):
    out = []
    ap = out.append
    ap("=" * 78)
    ap("COMPARE   A=%s (%s/%s)" % (c["a"], c["verdicts"]["a"],
                                   c["verdicts"]["exit"]["a"]))
    ap("          B=%s (%s/%s)" % (c["b"], c["verdicts"]["b"],
                                   c["verdicts"]["exit"]["b"]))
    ap("COUNTER DELTAS (B - A)")
    for k, d in c["counter_deltas"].items():
        if "delta" in d:
            ap("  %-28s %10s -> %10s  (%+d)" % (k, d["a"], d["b"],
                                                d["delta"]))
        else:
            ap("  %-28s %r -> %r" % (k, d["a"], d["b"]))
    for side in ("a", "b"):
        for j in c["staged_jars"][side]:
            ap("  JAR[%s] %s %s" % (side, j["status"], j["jar"]))
    lag = c["log"]["lag"]
    ap("LAG       A: %s   B: %s" % (lag["a"], lag["b"]))
    ap("SETBLOCK-FAILS  A: %d   B: %d" % (c["log"]["setblock_fails"]["a"],
                                          c["log"]["setblock_fails"]["b"]))
    for side in ("a", "b"):
        top = c["log"]["exceptions_top"][side]
        if top:
            ap("EXC[%s]  %s" % (side, ", ".join(
                "%s x%d" % (sig.split(".")[-1], ent["count"])
                for sig, ent in top[:4])))
    ap("=" * 78)
    return "\n".join(out)


def main_report(run_dir, repo_root, as_json=False):
    r = build_report(run_dir, repo_root)
    if as_json:
        print(json.dumps(r, indent=1, default=str))
    else:
        print(render_report(r))


def main_compare(a, b, repo_root, as_json=False):
    c = compare_reports(a, b, repo_root)
    if as_json:
        print(json.dumps(c, indent=1, default=str))
    else:
        print(render_compare(c))
