#!/usr/bin/env python3
"""rustcraft-runscope — pre-boot consistency lint + post-run evidence digest.

Before a campaign boot (catches the bug classes that wasted boots during the
block-light authority work):

    python tools/runscope/rustcraft_runscope.py lint

After a campaign run (replaces manual grep/JSON archaeology over the run dir):

    python tools/runscope/rustcraft_runscope.py report  target/authority-review/<run>
    python tools/runscope/rustcraft_runscope.py compare <runA> <runB>

All commands support --json for machine consumption. Read-only over the
repository and run directories. Docs: docs/engineering/RUNSCOPE.md
"""

import argparse
import json
import os
import sys

_HERE = os.path.dirname(os.path.abspath(__file__))
_REPO_ROOT = os.path.dirname(os.path.dirname(_HERE))
sys.path.insert(0, _HERE)


def cmd_lint(args):
    import bridge_lint
    findings = bridge_lint.run_lint(_REPO_ROOT)
    order = {"fatal": 0, "warning": 1, "info": 2}
    threshold = order[args.min_severity]
    shown = [f for f in findings if order[f.severity] <= threshold]
    justified = [f for f in shown if f.justified]
    actionable = [f for f in shown if not f.justified]
    if args.json:
        print(json.dumps({"findings": [f.as_dict(_REPO_ROOT)
                                       for f in actionable],
                          "justified": [f.as_dict(_REPO_ROOT)
                                        for f in justified]}, indent=1))
        return 1 if any(f.severity == "fatal" for f in actionable) else 0
    if justified:
        print("justified %d (RUNSCOPE-JUSTIFIED markers; suppressed):"
              % len(justified))
        for f in justified:
            print("  %-22s %s:%d  %s" % (
                f.check, os.path.relpath(f.file, _REPO_ROOT), f.line,
                (f.justification or "")[:90]))
    if not actionable:
        print("runscope lint: clean (%d justified, no actionable findings "
              "at or above %s)" % (len(justified), args.min_severity))
        return 0
    counts = {}
    for f in actionable:
        counts[f.severity] = counts.get(f.severity, 0) + 1
    print("runscope lint: %d findings  %s" % (
        len(actionable),
        " ".join("%s=%d" % kv for kv in sorted(counts.items()))))
    for f in actionable:
        print("  %-22s %-7s %s:%d  %s" % (
            f.check, f.severity, os.path.relpath(f.file, _REPO_ROOT),
            f.line, f.message))
    print("fix or justify (RUNSCOPE-JUSTIFIED: comment) before burning a "
          "campaign boot; see docs/engineering/RUNSCOPE.md")
    return 1 if any(f.severity == "fatal" for f in actionable) else 0


def cmd_report(args):
    import run_report
    if args.timeline:
        rows = run_report.build_timeline(args.run_dir)
        if args.json:
            print(json.dumps([{"ts": t, "kind": k, "detail": d}
                              for t, _p, k, d in rows], indent=1))
        else:
            print(run_report.render_timeline(rows))
        return 0
    run_report.main_report(args.run_dir, _REPO_ROOT, as_json=args.json)
    return 0


def cmd_compare(args):
    import run_report
    run_report.main_compare(args.run_a, args.run_b, _REPO_ROOT,
                            as_json=args.json)
    return 0


def cmd_expect(args):
    import expect
    ok_all, results = expect.run_expectations(
        _REPO_ROOT, path=args.expectations,
        only_ids=set(args.only) if args.only else None)
    if args.json:
        print(json.dumps({"ok": ok_all, "results": results}, indent=1))
    else:
        print(expect.render(results, ok_all))
    return 0 if ok_all else 1


def cmd_waitfor(args):
    import waitfor
    ok, summary = waitfor.wait_for(
        args.file, pattern=args.pattern, count=args.count,
        settle_key=args.settle, stable_for=args.stable_for,
        from_start=args.from_start, deadline_s=args.deadline,
        interval_s=args.interval)
    if args.json:
        print(json.dumps(summary, indent=1))
    else:
        print(waitfor.render_summary(summary))
    if ok:
        return 0
    return 2 if not summary["file_appeared"] else 1


def cmd_preflight(args):
    import preflight
    blockers, warnings, info = preflight.preflight(
        port=args.port, min_free_gb=args.min_free_gb,
        repo_root=_REPO_ROOT, target=args.target)
    if args.json:
        print(json.dumps({"ok": not blockers, "blockers": blockers,
                          "warnings": warnings, "info": info}, indent=1))
        return 1 if blockers else 0
    if blockers:
        print("preflight: %d BLOCKER(S)" % len(blockers))
        for b in blockers:
            print("  BLOCK  %s" % b)
    else:
        print("preflight: clean (port %d free, %.1f GB free)"
              % (args.port, info["free_gb"]))
    for w in warnings:
        print("  warn   %s" % w)
    return 1 if blockers else 0


def cmd_decode_solve(args):
    import decode_solve
    return decode_solve.main(["--pairs", args.pairs, "--name", args.name])


def cmd_verify_transformer(args):
    import verify_transformer
    return verify_transformer.main(_REPO_ROOT, [
        "--classes-dir", args.classes_dir,
        "--jar", args.jar,
        "--target-class", args.target_class,
        "--transformer", args.transformer,
    ] + (["--libs-dir", args.libs_dir] if args.libs_dir else [])
      + (["--jdk", args.jdk] if args.jdk else [])
      + sum([["--prop", pr] for pr in (args.prop or ())], [])
      + (["--json"] if args.json else []))


def cmd_bootdiff(args):
    import boot_diff
    d = boot_diff.boot_diff(args.log_a, args.log_b)
    if args.json:
        print(json.dumps(d, indent=1))
    else:
        print(boot_diff.render_bootdiff(d))
    return 0


def cmd_patch(args):
    import patch_tool
    ok, messages = patch_tool.run_patch(
        args.file,
        replacements=args.replace or (),
        replace_all=args.replace_all or (),
        must_contain=args.must_contain or (),
        must_not_contain=args.must_not_contain or (),
        check_only=args.check)
    for m in messages:
        print(m)
    if args.json:
        print(json.dumps({"ok": ok, "messages": messages}, indent=1))
    return 0 if ok else 1


def main(argv=None):
    ap = argparse.ArgumentParser(prog="rustcraft-runscope")
    ap.add_argument("--json", action="store_true",
                    help="machine-readable output")
    sub = ap.add_subparsers(dest="cmd", required=True)

    p = sub.add_parser("lint", help="static bridge/hook consistency checks")
    p.add_argument("--min-severity", default="warning",
                   choices=["fatal", "warning", "info"])
    p.set_defaults(fn=cmd_lint)

    p = sub.add_parser("report", help="one-page evidence digest of a run")
    p.add_argument("run_dir")
    p.add_argument("--timeline", action="store_true",
                   help="interleaved chronological view (events + light "
                        "counter changes)")
    p.set_defaults(fn=cmd_report)

    p = sub.add_parser("compare", help="delta two run directories")
    p.add_argument("run_a")
    p.add_argument("run_b")
    p.set_defaults(fn=cmd_compare)

    p = sub.add_parser("expect",
                       help="durable seam invariants (expectations.json) "
                            "— regression suite for prior incident fixes")
    p.add_argument("--expectations",
                   default=os.path.join(_HERE, "expectations.json"))
    p.add_argument("--only", action="append",
                   help="run only these expectation ids")
    p.set_defaults(fn=cmd_expect)

    p = sub.add_parser("waitfor",
                       help="event-driven wait: return as soon as evidence "
                            "appears or a counter settles (deadline is a "
                            "ceiling)")
    p.add_argument("--file", required=True, help="log/metrics file to watch")
    p.add_argument("--pattern", help="fire when this regex matches "
                                     "(appended lines unless --from-start)")
    p.add_argument("--count", type=int, default=1,
                   help="matches needed before firing (event-count "
                        "evidence floor)")
    p.add_argument("--settle", metavar="KEY",
                   help="fire when key=value counter KEY stops changing")
    p.add_argument("--stable-for", type=float, default=10.0,
                   help="seconds KEY must stay unchanged (default 10)")
    p.add_argument("--from-start", action="store_true",
                   help="scan existing content instead of only new lines")
    p.add_argument("--deadline", type=float, default=300.0,
                   help="ceiling in seconds (default 300)")
    p.add_argument("--interval", type=float, default=0.5)
    p.set_defaults(fn=cmd_waitfor)

    p = sub.add_parser("preflight",
                       help="environment check before a boot: heavy builds "
                            "running, port free, disk headroom")
    p.add_argument("--port", type=int, default=25565)
    p.add_argument("--min-free-gb", type=float, default=5.0)
    p.add_argument("--target", help="campaign target letter (A/C): also "
                                    "check staged-jar freshness")
    p.set_defaults(fn=cmd_preflight)

    p = sub.add_parser("decode-solve",
                       help="derive lane layout (shift/width/bias/signed) "
                            "from captured input->packed pairs")
    p.add_argument("--pairs", required=True)
    p.add_argument("--name", default="packed")
    p.set_defaults(fn=cmd_decode_solve)

    p = sub.add_parser("verify-transformer",
                       help="offline transformer verification: apply to "
                            "real bytes + ASM dataflow verify (catches the "
                            "VerifyError class pre-boot)")
    p.add_argument("--classes-dir", required=True)
    p.add_argument("--jar", required=True)
    p.add_argument("--target-class", required=True)
    p.add_argument("--transformer", required=True)
    p.add_argument("--libs-dir")
    p.add_argument("--jdk")
    p.add_argument("--prop", action="append", metavar="key=value")
    p.set_defaults(fn=cmd_verify_transformer)

    p = sub.add_parser("bootdiff",
                       help="first lifecycle divergence between two "
                            "server boots (normalized log diff)")
    p.add_argument("log_a")
    p.add_argument("log_b")
    p.set_defaults(fn=cmd_bootdiff)

    p = sub.add_parser("patch",
                       help="verified text patch / drift check (replaces "
                            "raw s.replace heredocs)")
    p.add_argument("--file", required=True)
    p.add_argument("--replace", action="append", metavar="OLD===NEW",
                   help="replace first/only occurrence (must be unique)")
    p.add_argument("--replace-all", action="append", metavar="OLD===NEW",
                   help="replace every occurrence (must exist)")
    p.add_argument("--must-contain", action="append", metavar="REGEX",
                   help="postcondition: pattern must be present (comment/"
                        "string-stripped for .java)")
    p.add_argument("--must-not-contain", action="append", metavar="REGEX",
                   help="postcondition: pattern must be absent")
    p.add_argument("--check", action="store_true",
                   help="postconditions only, no writes (drift detector)")
    p.set_defaults(fn=cmd_patch)

    args = ap.parse_args(argv)
    # --json lives on the top parser; push it down for visibility
    return args.fn(args)


if __name__ == "__main__":
    sys.exit(main())
