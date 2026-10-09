#!/usr/bin/env python3
"""RustCraft tooling MCP server — stdio transport, stdlib only.

Exposes the runscope + symbols toolset as MCP tools with structured
arguments (no shell escaping — the heredoc-mangling bug class dies here)
and JSON outputs. Implements the Model Context Protocol stdio transport:
newline-delimited JSON-RPC 2.0 (initialize / tools/list / tools/call).

Register (project .mcp.json convention):
    {"mcpServers": {"rustcraft-tools": {
        "command": "python",
        "args": ["tools/mcp/rustcraft_mcp_server.py"]}}}

All tools are read-only over the repo/run dirs except runscope_patch
(atomic, verified text edits) — same contract as the CLI.
"""

import io
import json
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(HERE))
PY = sys.executable

# ----------------------------------------------------------------------
# tool catalog: name -> (description, schema, argv builder)
# argv builders receive the arguments dict and return the full command list.


def _runscope(*args):
    return [PY, os.path.join(REPO, "tools", "runscope",
                             "rustcraft_runscope.py")] + list(args)


def _symbols(*args):
    return [PY, os.path.join(REPO, "tools", "symbols",
                             "rustcraft_symbols.py")] + list(args)


TOOLS = {
    "runscope_lint": {
        "description": "Static bridge/hook consistency lint: JNI native/"
                       "export pairing, ByteBuffer endianness, cross-loader "
                       "Class.forName, EnumSkyBlock ordinal gates, "
                       "sentinel-degradation catches, transformer frames, "
                       "staged-format drift. Run BEFORE every campaign "
                       "boot; fatal findings block.",
        "schema": {"type": "object", "properties": {
            "min_severity": {"type": "string",
                             "enum": ["fatal", "warning", "info"]}},
            "additionalProperties": False},
        "argv": lambda a: _runscope("--json", "lint")
        + (["--min-severity", a["min_severity"]] if a.get("min_severity")
           else []),
    },
    "runscope_expect": {
        "description": "Durable seam invariants (expectations.json): "
                       "per-file must-contain/must-not-contain regression "
                       "guards, each citing its incident. Run with lint "
                       "before every boot.",
        "schema": {"type": "object", "properties": {
            "only": {"type": "string",
                     "description": "run one expectation id"}},
            "additionalProperties": False},
        "argv": lambda a: _runscope("--json", "expect")
        + (["--only", a["only"]] if a.get("only") else []),
    },
    "runscope_preflight": {
        "description": "Environment gate before a boot: heavy build "
                       "processes, campaign port bound, disk headroom, "
                       "STALE campaign jar, STALE native lib.",
        "schema": {"type": "object", "properties": {
            "port": {"type": "integer"},
            "target": {"type": "string",
                       "description": "campaign target letter A/C for "
                                      "staged-jar freshness"},
            "min_free_gb": {"type": "number"}},
            "additionalProperties": False},
        "argv": lambda a: _runscope("--json", "preflight")
        + (["--port", str(a["port"])] if a.get("port") else [])
        + (["--target", a["target"]] if a.get("target") else [])
        + (["--min-free-gb", str(a["min_free_gb"])]
           if a.get("min_free_gb") else []),
    },
    "runscope_report": {
        "description": "One-page evidence digest of a campaign run dir: "
                       "verdict, COUNTERS-FINAL (max of log/metrics, "
                       "source-labeled), PROBES transport-vs-world split, "
                       "mutation steps, timeline, exception histogram, "
                       "transform-diag tails, STALE-jar detection.",
        "schema": {"type": "object", "properties": {
            "run_dir": {"type": "string"},
            "timeline": {"type": "boolean",
                         "description": "interleaved chronological view"}},
            "required": ["run_dir"], "additionalProperties": False},
        "argv": lambda a: _runscope("--json", "report", a["run_dir"])
        + (["--timeline"] if a.get("timeline") else []),
    },
    "runscope_compare": {
        "description": "Delta two campaign run directories: counter deltas "
                       "(metrics-file based), staged-jar status, lag, "
                       "exception signatures.",
        "schema": {"type": "object", "properties": {
            "run_a": {"type": "string"}, "run_b": {"type": "string"}},
            "required": ["run_a", "run_b"],
            "additionalProperties": False},
        "argv": lambda a: _runscope("compare", a["run_a"], a["run_b"],
                                    "--json"),
    },
    "runscope_bootdiff": {
        "description": "First lifecycle divergence between two server "
                       "boots (normalized log diff: tweaker sequences, "
                       "category counts, first divergence with context).",
        "schema": {"type": "object", "properties": {
            "log_a": {"type": "string"}, "log_b": {"type": "string"}},
            "required": ["log_a", "log_b"], "additionalProperties": False},
        "argv": lambda a: _runscope("bootdiff", a["log_a"], a["log_b"],
                                    "--json"),
    },
    "runscope_waitfor": {
        "description": "Event-driven wait: returns as soon as a pattern "
                       "matches (count = event floor) and/or a counter "
                       "settles. Deadline is a CEILING, not a hold. "
                       "Replaces sleep-then-grep.",
        "schema": {"type": "object", "properties": {
            "file": {"type": "string"},
            "pattern": {"type": "string"},
            "count": {"type": "integer"},
            "settle": {"type": "string",
                       "description": "key=value counter to watch for "
                                      "stability"},
            "stable_for": {"type": "number"},
            "from_start": {"type": "boolean"},
            "deadline": {"type": "number"}},
            "required": ["file"], "additionalProperties": False},
        "argv": lambda a: _runscope("--json", "waitfor", "--file",
                                    a["file"])
        + (["--pattern", a["pattern"]] if a.get("pattern") else [])
        + (["--count", str(a["count"])] if a.get("count") else [])
        + (["--settle", a["settle"]] if a.get("settle") else [])
        + (["--stable-for", str(a["stable_for"])]
           if a.get("stable_for") else [])
        + (["--from-start"] if a.get("from_start") else [])
        + (["--deadline", str(a["deadline"])] if a.get("deadline") else []),
    },
    "runscope_patch": {
        "description": "Verified text patch (atomic, unique-occurrence "
                       "enforcement, comment-stripped postconditions). "
                       "ALWAYS use this instead of raw s.replace heredocs. "
                       "check=true runs postconditions only (drift "
                       "detector).",
        "schema": {"type": "object", "properties": {
            "file": {"type": "string"},
            "replace": {"type": "array", "items": {"type": "string"},
                        "description": "OLD===NEW strings, first/only "
                                       "occurrence"},
            "must_contain": {"type": "array", "items": {"type": "string"}},
            "must_not_contain": {"type": "array",
                                 "items": {"type": "string"}},
            "check": {"type": "boolean"}},
            "required": ["file"], "additionalProperties": False},
        "argv": lambda a: _runscope("patch", "--file", a["file"])
        + sum([["--replace", r] for r in a.get("replace", [])], [])
        + sum([["--must-contain", r] for r in a.get("must_contain", [])], [])
        + sum([["--must-not-contain", r]
               for r in a.get("must_not_contain", [])], [])
        + (["--check"] if a.get("check") else []),
    },
    "runscope_decode_solve": {
        "description": "Derive a packed-value lane layout (shift/width/"
                       "bias/signedness) from >=2 captured input->packed "
                       "pairs; emits decoders + regression vectors.",
        "schema": {"type": "object", "properties": {
            "pairs_json": {"type": "string",
                           "description": "path to pairs JSON file"},
            "name": {"type": "string"}},
            "required": ["pairs_json"], "additionalProperties": False},
        "argv": lambda a: _runscope("--json", "decode-solve", "--pairs",
                                    a["pairs_json"], "--json")
        + (["--name", a["name"]] if a.get("name") else []),
    },
    "runscope_verify_transformer": {
        "description": "Offline transformer verification: apply a real "
                       "IClassTransformer to real jar bytes and run ASM "
                       "dataflow verification. Catches the VerifyError "
                       "class pre-boot.",
        "schema": {"type": "object", "properties": {
            "classes_dir": {"type": "string"},
            "jar": {"type": "string"},
            "target_class": {"type": "string"},
            "transformer": {"type": "string"},
            "prop": {"type": "array", "items": {"type": "string"},
                     "description": "-Dkey=value JVM properties"},
            "libs_dir": {"type": "string"}},
            "required": ["classes_dir", "jar", "target_class",
                         "transformer"],
            "additionalProperties": False},
        "argv": lambda a: _runscope(
            "verify-transformer", "--json", "--classes-dir",
            a["classes_dir"], "--jar", a["jar"], "--target-class",
            a["target_class"], "--transformer", a["transformer"])
        + sum([["--prop", p] for p in a.get("prop", [])], [])
        + (["--libs-dir", a["libs_dir"]] if a.get("libs_dir") else []),
    },
    "symbols_query": {
        "description": "MC 1.12.2/Forge bytecode + mapping navigator. "
                       "commands: method (identity card, all namespaces), "
                       "map (MCP/SRG/notch cross-name), callers/callees, "
                       "field/field-readers/field-writers/fields, class, "
                       "refs, strings, constant, overrides, forge-changes, "
                       "live-diff, signals (override evidence + "
                       "classification), mixins, body (ordered bytecode "
                       "inventory; use before javap), reflect (reflection "
                       "contract of a field - USE BEFORE WRITING "
                       "REFLECTION), search, status, provenance, sql.",
        "schema": {"type": "object", "properties": {
            "command": {"type": "string"},
            "ident": {"type": "string"},
            "layer": {"type": "string"},
            "limit": {"type": "integer"},
            "extra": {"type": "array", "items": {"type": "string"},
                      "description": "extra CLI flags verbatim, e.g. "
                                     "[\"--calls-only\"], "
                                     "[\"--strict-provenance\"], "
                                     "[\"--members\"]"}},
            "required": ["command"], "additionalProperties": False},
        "argv": lambda a: _symbols("--json", a["command"])
        + ([a["ident"]] if a.get("ident") else [])
        + (["--layer", a["layer"]] if a.get("layer") else [])
        + (["--limit", str(a["limit"])] if a.get("limit") else [])
        + list(a.get("extra") or []),
    },
}

# ----------------------------------------------------------------------
# MCP protocol (stdio: newline-delimited JSON-RPC 2.0)


def _result(req_id, payload):
    return json.dumps({"jsonrpc": "2.0", "id": req_id, "result": payload})


def _tool_defs():
    return [{"name": n, "description": t["description"],
             "inputSchema": t["schema"]} for n, t in TOOLS.items()]


def _call_tool(name, args):
    if name not in TOOLS:
        return {"content": [{"type": "text",
                             "text": "unknown tool %s" % name}],
                "isError": True}
    cmd = TOOLS[name]["argv"](args or {})
    try:
        r = subprocess.run(cmd, capture_output=True, text=True,
                           timeout=600, cwd=REPO)
        text = (r.stdout or r.stderr).strip()
        # parse the CLI's JSON when it emits pure JSON; else pass through
        try:
            parsed = json.loads(text)
            return {"content": [{"type": "text",
                                 "text": json.dumps(parsed, indent=1)}],
                    "isError": r.returncode not in (0, 2, 3)}
        except ValueError:
            return {"content": [{"type": "text", "text": text[:20000]}],
                    "isError": r.returncode != 0}
    except subprocess.TimeoutExpired:
        return {"content": [{"type": "text", "text": "tool timed out"}],
                "isError": True}


def main():
    outf = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8")
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            req = json.loads(line)
        except ValueError:
            continue
        method = req.get("method", "")
        rid = req.get("id")
        if method == "initialize":
            outf.write(_result(rid, {
                "protocolVersion": "2024-11-05",
                "capabilities": {"tools": {}},
                "serverInfo": {"name": "rustcraft-tools",
                               "version": "1.0.0"},
            }) + "\n")
        elif method == "tools/list":
            outf.write(_result(rid, {"tools": _tool_defs()}) + "\n")
        elif method == "tools/call":
            params = req.get("params") or {}
            outf.write(_result(rid, _call_tool(params.get("name"),
                                               params.get("args"))) + "\n")
        elif method == "ping":
            outf.write(_result(rid, {}) + "\n")
        # notifications (notifications/initialized etc.): no response
        outf.flush()


if __name__ == "__main__":
    main()
