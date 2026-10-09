#!/usr/bin/env python3
"""RustCraft tooling MCP server — PRIMARY entry (official MCP SDK).

Builds on mcp.server.fastmcp (the official SDK's FastMCP): spec-complete
protocol (version negotiation, JSON-RPC error codes, cancellation),
schemas generated from type hints. Falls back to the stdlib minimal
implementation (rustcraft_mcp_server.py) when the package is absent.

Register (.mcp.json):
    {"mcpServers": {"rustcraft-tools": {
        "command": "python",
        "args": ["tools/mcp/rustcraft_mcp.py"]}}}
"""

import json
import os
import subprocess
import sys
from typing import Optional

_HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, _HERE)

from rustcraft_mcp_server import _runscope, _symbols, REPO  # noqa: E402


def _payload(argv):
    """Run a CLI command; return its parsed JSON payload."""
    r = subprocess.run(argv, capture_output=True, text=True, timeout=600,
                       cwd=REPO)
    text = (r.stdout or r.stderr).strip()
    try:
        return json.loads(text)
    except ValueError:
        return {"raw": text[:20000], "exit": r.returncode}


def main():
    try:
        from mcp.server.fastmcp import FastMCP
    except ImportError:
        print("mcp package not installed - falling back to the stdlib "
              "minimal server (pip install mcp for the spec-complete "
              "implementation)", file=sys.stderr)
        rustcraft_mcp_server = sys.modules["rustcraft_mcp_server"]
        rustcraft_mcp_server.main()
        return

    mcp = FastMCP("rustcraft-tools")

    @mcp.tool()
    def runscope_lint(min_severity: str = "warning") -> dict:
        """Static bridge/hook consistency lint: JNI pairing, ByteBuffer
        endianness, cross-loader Class.forName, EnumSkyBlock ordinal
        gates, sentinel-degradation catches, transformer frames,
        staged-format drift. Run BEFORE every campaign boot; fatal
        findings block."""
        return _payload(_runscope("--json", "lint", "--min-severity",
                                  min_severity))

    @mcp.tool()
    def runscope_expect(only: str = "") -> dict:
        """Durable seam invariants (expectations.json): per-file
        regression guards, each citing its incident. Run with lint before
        every boot."""
        argv = _runscope("--json", "expect")
        if only:
            argv += ["--only", only]
        return _payload(argv)

    @mcp.tool()
    def runscope_preflight(port: int = 25565, target: str = "",
                           min_free_gb: float = 5.0) -> dict:
        """Environment gate before a boot: heavy build processes, campaign
        port bound, disk headroom, STALE campaign jar, STALE native lib.
        Content-aware (git-based) staleness."""
        argv = _runscope("--json", "preflight", "--port", str(port),
                         "--min-free-gb", str(min_free_gb))
        if target:
            argv += ["--target", target]
        return _payload(argv)

    @mcp.tool()
    def runscope_report(run_dir: str, timeline: bool = False) -> dict:
        """One-page evidence digest of a campaign run: verdict,
        COUNTERS-FINAL (per-counter max of log/metrics, source-labeled),
        PROBES transport-vs-world split, mutation steps, exception
        histogram, transform-diag tails, STALE-jar detection.
        timeline=True = interleaved chronological view (also picks up
        seam-decisions.jsonl and step timestamps)."""
        argv = _runscope("--json", "report", run_dir)
        if timeline:
            argv.append("--timeline")
        return _payload(argv)

    @mcp.tool()
    def runscope_compare(run_a: str, run_b: str) -> dict:
        """Delta two campaign run directories: counter deltas
        (metrics-file based), staged-jar status, lag, exception
        signatures."""
        return _payload(_runscope("--json", "compare", run_a, run_b))

    @mcp.tool()
    def runscope_bootdiff(log_a: str, log_b: str) -> dict:
        """First lifecycle divergence between two boots (normalized
        diff: tweaker sequences, category counts, first divergence)."""
        return _payload(_runscope("--json", "bootdiff", log_a, log_b))

    @mcp.tool()
    def runscope_waitfor(file: str, pattern: str = "", count: int = 0,
                         settle: str = "", stable_for: float = 0.0,
                         from_start: bool = False,
                         deadline: float = 300.0) -> dict:
        """Event-driven wait: returns the moment a pattern matches (count
        = event floor) and/or a counter settles. Deadline is a CEILING.
        Replaces sleep-then-grep."""
        argv = _runscope("--json", "waitfor", "--file", file,
                         "--deadline", str(deadline))
        if pattern:
            argv += ["--pattern", pattern]
        if count:
            argv += ["--count", str(count)]
        if settle:
            argv += ["--settle", settle]
        if stable_for:
            argv += ["--stable-for", str(stable_for)]
        if from_start:
            argv.append("--from-start")
        return _payload(argv)

    @mcp.tool()
    def runscope_patch(file: str, replace: Optional[list] = None,
                       must_contain: Optional[list] = None,
                       must_not_contain: Optional[list] = None,
                       check: bool = False) -> dict:
        """Verified atomic text patch: unique-occurrence enforcement +
        comment-stripped postconditions. ALWAYS use instead of raw
        s.replace heredocs (the dev8-12 drift class). check=true =
        postconditions only (drift detector)."""
        argv = _runscope("patch", "--file", file)
        for r in replace or []:
            argv += ["--replace", r]
        for r in must_contain or []:
            argv += ["--must-contain", r]
        for r in must_not_contain or []:
            argv += ["--must-not-contain", r]
        if check:
            argv.append("--check")
        return _payload(argv)

    @mcp.tool()
    def runscope_decode_solve(pairs_json: str, name: str = "packed") -> dict:
        """Derive a packed-value lane layout (shift/width/bias/signedness)
        from >=2 captured input->packed pairs; emits decoders + pinned
        regression vectors. Widths are upper bounds unless values span the
        lane."""
        return _payload(_runscope("--json", "decode-solve", "--pairs",
                                  pairs_json, "--name", name))

    @mcp.tool()
    def runscope_verify_transformer(classes_dir: str, jar: str,
                                    target_class: str, transformer: str,
                                    prop: Optional[list] = None,
                                    libs_dir: str = "") -> dict:
        """Offline transformer verification: apply the real
        IClassTransformer to real jar bytes + ASM dataflow verification.
        Catches the VerifyError class pre-boot. prop = -D JVM properties
        (e.g. rustcraft.lightMode=ON_EXPERIMENTAL)."""
        argv = _runscope("--json", "verify-transformer", "--classes-dir",
                         classes_dir, "--jar", jar, "--target-class",
                         target_class, "--transformer", transformer)
        for pr in prop or []:
            argv += ["--prop", pr]
        if libs_dir:
            argv += ["--libs-dir", libs_dir]
        return _payload(argv)

    @mcp.tool()
    def symbols_query(command: str, ident: str = "", layer: str = "",
                      limit: int = 0, extra: Optional[list] = None) -> dict:
        """MC 1.12.2/Forge bytecode + mapping navigator. commands: method,
        map (MCP/SRG/notch cross-name), callers, callees, field,
        field-readers, field-writers, fields, class, refs, strings,
        constant, overrides, forge-changes, live-diff, signals (override
        evidence + suggested classification), mixins, body (ordered
        bytecode inventory — use BEFORE javap), reflect (reflection
        contract of a field — USE BEFORE WRITING REFLECTION), search,
        status, provenance, sql."""
        argv = _symbols("--json", command)
        if ident:
            argv.append(ident)
        if layer:
            argv += ["--layer", layer]
        if limit:
            argv += ["--limit", str(limit)]
        argv += list(extra or [])
        return _payload(argv)

    mcp.run()


if __name__ == "__main__":
    main()
