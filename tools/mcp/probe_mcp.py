#!/usr/bin/env python3
"""Probe one MCP server registration the way a client would (retro
2026-10-10: the REA setup needed three ad-hoc probe iterations before the
async-read pattern was right — encode it once).

Spawns the server, performs the JSON-RPC initialize handshake, lists
tools, and optionally calls one tool. Prints identity + tool count +
sample names. Exit 0 only on a full handshake.

Usage:
  python tools/mcp/probe_mcp.py --command node --arg path/to/server.mjs \
      --arg mcp [--cwd .] [--call binary_session] [--timeout 10]

  # probe every entry in a .mcp.json:
  python tools/mcp/probe_mcp.py --config .mcp.json
"""
from __future__ import annotations

import argparse
import json
import subprocess
import sys
import threading
import time
from pathlib import Path


def probe(name: str, command: str, args: list, cwd: str | None,
          call: str | None, timeout: float) -> int:
    print(f"== probing [{name}] {command} {' '.join(args)}")
    try:
        p = subprocess.Popen([command] + args, stdin=subprocess.PIPE,
                             stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                             text=True, encoding="utf-8", cwd=cwd)
    except FileNotFoundError as e:
        print(f"  SPAWN FAIL: {e} (Windows note: npm installs .cmd "
              "shims — spawn the underlying .mjs/.js via node, or an "
              ".exe, not the shim name)")
        return 1
    msgs: list[dict] = []
    err_seen: list[str] = []

    def reader():
        for line in p.stdout:
            if line.strip():
                try:
                    msgs.append(json.loads(line))
                except ValueError:
                    pass

    def err_reader():
        for line in p.stderr:
            if len(err_seen) < 5:
                err_seen.append(line.rstrip())

    threading.Thread(target=reader, daemon=True).start()
    threading.Thread(target=err_reader, daemon=True).start()

    def send(obj):
        p.stdin.write(json.dumps(obj) + "\n")
        p.stdin.flush()

    ok = True
    try:
        send({"jsonrpc": "2.0", "id": 1, "method": "initialize",
              "params": {"protocolVersion": "2024-11-05",
                         "capabilities": {},
                         "clientInfo": {"name": "probe-mcp",
                                        "version": "0.1"}}})
        time.sleep(min(3.0, timeout / 3))
        send({"jsonrpc": "2.0", "method": "notifications/initialized"})
        send({"jsonrpc": "2.0", "id": 2, "method": "tools/list",
              "params": {}})
        deadline = time.time() + timeout
        while time.time() < deadline and not any(
                m.get("id") == 2 for m in msgs):
            time.sleep(0.3)
        init = next((m for m in msgs if m.get("id") == 1), None)
        tl = next((m for m in msgs if m.get("id") == 2), None)
        if init is None:
            print("  FAIL: no initialize response")
            ok = False
        else:
            info = init.get("result", {}).get("serverInfo", {})
            print(f"  server: {info.get('name')} {info.get('version')}")
        if tl is None:
            print("  FAIL: no tools/list response")
            ok = False
        else:
            tools = tl.get("result", {}).get("tools", [])
            names = [t.get("name", "?") for t in tools]
            print(f"  tools: {len(names)} (sample: {names[:6]})")
        if ok and call:
            send({"jsonrpc": "2.0", "id": 3, "method": "tools/call",
                  "params": {"name": call, "arguments": {}}})
            deadline = time.time() + timeout
            while time.time() < deadline and not any(
                    m.get("id") == 3 for m in msgs):
                time.sleep(0.3)
            tc = next((m for m in msgs if m.get("id") == 3), None)
            if tc is None or "error" in tc:
                print(f"  CALL {call}: FAIL "
                      f"({(tc or {}).get('error', 'no response')})")
                ok = False
            else:
                txt = (tc.get("result", {}).get("content", [{}])
                       or [{}])[0].get("text", "")[:120]
                print(f"  CALL {call}: ok ({len(txt)} chars: "
                      f"{txt[:60]!r}...)")
    finally:
        p.terminate()
        try:
            p.wait(timeout=5)
        except subprocess.TimeoutExpired:
            p.kill()
    if err_seen:
        print(f"  stderr: {' | '.join(err_seen[:2])}")
    print(f"  => {'PASS' if ok else 'FAIL'}")
    return 0 if ok else 1


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--config", type=Path, default=None,
                    help="probe every entry in this .mcp.json")
    ap.add_argument("--command")
    ap.add_argument("--arg", action="append", default=[],
                    help="server arg (repeatable)")
    ap.add_argument("--cwd", default=None)
    ap.add_argument("--call", default=None,
                    help="optional tools/call name to invoke with {}")
    ap.add_argument("--timeout", type=float, default=10.0)
    args = ap.parse_args()
    if args.config:
        cfg = json.loads(args.config.read_text(encoding="utf-8"))
        rc = 0
        for name, ent in cfg.get("mcpServers", {}).items():
            rc |= probe(name, ent.get("command"), ent.get("args", []),
                        ent.get("cwd"), args.call, args.timeout)
        return rc
    if not args.command:
        ap.error("--command or --config required")
    return probe("ad-hoc", args.command, args.arg, args.cwd,
                 args.call, args.timeout)


if __name__ == "__main__":
    sys.exit(main())
