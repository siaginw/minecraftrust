"""Pre-boot environment check — catches the boot-burning environment
failures before they eat a 5-8 minute Revelation boot.

Incident driving this (light-mut-C-dev1): `cargo test --workspace` ran
concurrently with the Gate C boot; the server stalled 114 s right after
Done, the probe's login ticked out server-side (bytes_in: 0), and a full
pack boot was burned on an environment problem that looked like a code
problem.

Checks:
1. heavy builds running (cargo/rustc/javac/java-build processes) — these
   steal CPU exactly when a boot is CPU-bound;
2. campaign port free (default from server.properties conventions: the
   runner's --port, else 25565/25610 range) — a bound port produces the
   probe-connection black hole;
3. disk headroom on the run-dir volume (campaign dirs hold full server
   copies; a full disk kills a boot mid-save).

Exit 0 clean, 1 blockers found. Read-only. Windows-first (tasklist), with
a ps fallback.
"""

import os
import re
import shutil
import socket
import subprocess
import sys

_HEAVY_PROCS = ("cargo.exe", "rustc.exe", "javac.exe",
                "cargo", "rustc", "javac", "ld.lld", "link.exe")


def _running_processes():
    out = set()
    try:
        raw = subprocess.run(["tasklist", "/FO", "CSV", "/NH"],
                             capture_output=True, text=True, timeout=15)
        for line in raw.stdout.splitlines():
            m = re.match(r'"([^"]+)"', line.strip())
            if m:
                out.add(m.group(1).lower())
    except (OSError, subprocess.SubprocessError):
        try:
            raw = subprocess.run(["ps", "-eo", "comm="],
                                 capture_output=True, text=True, timeout=15)
            out = {ln.strip().lstrip("./").lower()
                   for ln in raw.stdout.splitlines() if ln.strip()}
        except (OSError, subprocess.SubprocessError):
            return None  # cannot determine process list on this machine
    return out


def _port_free(port, host="127.0.0.1"):
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.settimeout(1.0)
        return s.connect_ex((host, port)) != 0


_SOURCE_ROOTS = ("tools/bridge/src", "tools/spawn-interop/src",
                 "tools/worldgen-interop/src", "crates/ffi/src")


def _newest_content_change(repo_root, roots=None):
    """Newest time the SOURCE CONTENT actually changed (the false-positive
    fix: a validation pass bumps mtimes without changing content, so raw
    mtime over clean files lies). Content truth, in order of preference:

    - committed state: the last commit touching the source paths (their
      commit time is when content last changed on those files);
    - dirty files: uncommitted edits have no commit time, so their mtimes
      ARE the content truth (mtimes of clean files are ignored entirely).
    Returns (newest_epoch, description) or (0, reason) when undeterminable.
    """
    paths = list(roots or _SOURCE_ROOTS)
    try:
        dirty = subprocess.run(
            ["git", "status", "--porcelain", "--"] + paths,
            cwd=repo_root, capture_output=True, text=True, timeout=15,
        ).stdout.splitlines()
        dirty_mtimes = []
        for line in dirty:
            rel = line[3:].strip().strip('"')
            full = os.path.join(repo_root, rel)
            if os.path.isfile(full) and rel.endswith((".java", ".rs")):
                dirty_mtimes.append(os.path.getmtime(full))
        last_commit = subprocess.run(
            ["git", "log", "-1", "--format=%ct", "--"] + paths,
            cwd=repo_root, capture_output=True, text=True, timeout=15,
        ).stdout.strip()
        newest_commit = int(last_commit) if last_commit else 0
        newest_dirty = max(dirty_mtimes) if dirty_mtimes else 0.0
        if newest_dirty > newest_commit:
            import time
            return (newest_dirty,
                    "uncommitted edits (newest %s)" % time.strftime(
                        "%H:%M:%S", time.localtime(newest_dirty)))
        if newest_commit:
            import time
            return (newest_commit,
                    "last content commit %s" % time.strftime(
                        "%H:%M:%S", time.localtime(newest_commit)))
        return 0, "no git history for source paths"
    except (OSError, subprocess.SubprocessError):
        # git unavailable: fall back to raw mtimes (may false-positive;
        # said so out loud)
        newest = 0.0
        for rel_root in paths:
            root = os.path.join(repo_root, rel_root)
            for dirpath, _dirs, names in os.walk(root):
                for n in names:
                    if n.endswith((".java", ".rs")):
                        mt = os.path.getmtime(os.path.join(dirpath, n))
                        if mt > newest:
                            newest = mt
        return newest, "raw mtimes (git unavailable - may false-positive)"


def _stale_jar_check(repo_root, target):
    """The diag4/dev2 trap pre-boot: the runner stages
    target/rustcraft-campaign-<T>.jar exactly; if it predates the newest
    bridge/ffi source, the server will silently run old code."""
    if not target:
        return None
    jar = os.path.join(repo_root, "target",
                       "rustcraft-campaign-%s.jar" % target.upper())
    if not os.path.isfile(jar):
        return "campaign jar target/rustcraft-campaign-%s.jar does not "             "exist - build it with tools/build_campaign_jar.py --target %s "             "--output target/rustcraft-campaign-%s.jar" % (
                target.upper(), target.upper(), target.upper())
    newest_src, why = _newest_content_change(repo_root)
    if newest_src and os.path.getmtime(jar) < newest_src:
        import time
        return ("campaign jar target/rustcraft-campaign-%s.jar is STALE "
                "(built %s; %s) - rebuild before booting"
                % (target.upper(),
                   time.strftime("%H:%M:%S",
                                 time.localtime(os.path.getmtime(jar))),
                   why))
    return None


def _stale_dll_check(repo_root):
    """The dev-ON-11 trap: JNI exports added to crates/ffi but the native
    lib never rebuilt — the server runs a DLL without the new symbols and
    the failure surfaces as UnsatisfiedLinkError per job. Content-aware
    like the jar check."""
    dll = os.path.join(repo_root, "target", "release", "rustcraft_ffi.dll")
    ffi_roots = ["crates/ffi/src"]
    newest_src, why = _newest_content_change(repo_root, roots=ffi_roots)
    if not newest_src:
        return None
    if not os.path.isfile(dll):
        return ("native lib target/release/rustcraft_ffi.dll does not "
                "exist — build it (cargo build --release) before booting "
                "a campaign that loads natives")
    if os.path.getmtime(dll) < newest_src:
        import time
        return ("native lib target/release/rustcraft_ffi.dll is STALE "
                "(built %s; crates/ffi %s) — rebuild (cargo build "
                "--release) or every new JNI export is an "
                "UnsatisfiedLinkError at call time" % (
                    time.strftime("%H:%M:%S",
                                  time.localtime(os.path.getmtime(dll))),
                    why))
    return None


def preflight(port=25565, min_free_gb=5.0, repo_root=".", target=None):
    blockers = []
    warnings = []

    procs = _running_processes()
    if procs is None:
        warnings.append("process list unavailable; heavy-build check "
                        "skipped")
    else:
        heavy = sorted(p for p in procs if p in _HEAVY_PROCS)
        # the server's own java is fine; builds are not
        if heavy:
            blockers.append(
                "heavy build process(es) running: %s — a concurrent build "
                "stalled a Gate C boot 114s and burned it (light-mut-C-dev1)"
                % ", ".join(heavy))

    if not _port_free(port):
        blockers.append(
            "port %d is already bound — probe connections black-hole "
            "(the bytes_in:0 failure mode)" % port)

    stale = _stale_jar_check(repo_root, target)
    if stale:
        blockers.append(stale)
    stale_dll = _stale_dll_check(repo_root)
    if stale_dll:
        blockers.append(stale_dll)

    usage = shutil.disk_usage(os.path.abspath(repo_root))
    free_gb = usage.free / (1 << 30)
    if free_gb < min_free_gb:
        blockers.append("only %.1f GB free on the run volume (need >= %.1f "
                        "GB for a full server-copy run dir)"
                        % (free_gb, min_free_gb))
    elif free_gb < min_free_gb * 2:
        warnings.append("%.1f GB free — comfortable but watch it across "
                        "repeat runs" % free_gb)
    return blockers, warnings, {"free_gb": round(free_gb, 1),
                                "port": port,
                                "heavy_procs": sorted(
                                    p for p in (procs or set())
                                    if p in _HEAVY_PROCS)}


def main(repo_root, argv=None):
    import argparse
    import json
    ap = argparse.ArgumentParser(prog="runscope preflight")
    ap.add_argument("--port", type=int, default=25565,
                    help="campaign server port to check (default 25565)")
    ap.add_argument("--min-free-gb", type=float, default=5.0)
    ap.add_argument("--target",
                    help="campaign target letter (A/C): also verify "
                         "target/rustcraft-campaign-<T>.jar is newer than "
                         "the newest bridge/ffi source (the diag4/dev2 "
                         "stale-jar trap, pre-boot)")
    ap.add_argument("--json", action="store_true")
    ns = ap.parse_args(argv)
    blockers, warnings, info = preflight(port=ns.port,
                                         min_free_gb=ns.min_free_gb,
                                         repo_root=repo_root,
                                         target=ns.target)
    if ns.json:
        print(json.dumps({"ok": not blockers, "blockers": blockers,
                          "warnings": warnings, "info": info}, indent=1))
    else:
        if blockers:
            print("preflight: %d BLOCKER(S)" % len(blockers))
            for b in blockers:
                print("  BLOCK  %s" % b)
        else:
            print("preflight: clean (port %d free, %.1f GB free)"
                  % (ns.port, info["free_gb"]))
        for w in warnings:
            print("  warn   %s" % w)
    return 1 if blockers else 0


if __name__ == "__main__":
    sys.exit(main(os.path.dirname(os.path.dirname(
        os.path.dirname(os.path.abspath(__file__))))))
