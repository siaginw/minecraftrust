#!/usr/bin/env python3
"""Bounded live join probe runner: ONE server, ONE headless attempt, one receipt.

This is a connectivity probe, not a shadow campaign. It boots a disposable
copy of a pinned runtime with the V2-qualified campaign jar (live admission
runs: the writers admit classes under the same generated plan and static
policies the offline qualification used), waits for Done, performs exactly one
bounded headless join, disconnects cleanly, stops the server, and records
everything.

It must NOT be pointed at a world that matters: the server copy is disposable
by construction, and the runner hashes the world before and after so any
unexpected mutation is visible rather than silent.

No shadow comparator is enabled. No Rust code is called. No chunk packet is
compared to anything.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(Path(__file__).resolve().parent))

from join_probe import run_probe  # noqa: E402

JAVA = Path("D:/rustcraft-toolchains/temurin8/jdk8u504-b01/bin/java.exe")
PORT = 25599


def sha(path: Path) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def tree_hash(root: Path) -> dict:
    """Per-file hashes of a world tree, so before/after can be diffed."""
    files = {}
    if not root.is_dir():
        return files
    for found in sorted(root.rglob("*")):
        if found.is_file():
            files[found.relative_to(root).as_posix()] = sha(found)
    return files


def prepare_server(runtime_root: Path, out: Path, *, forge_jar: str,
                   vanilla_jar: str, campaign_jar: Path, world_source: Path | None,
                   view_distance: int = 6) -> tuple[Path, dict]:
    server = out / "server"
    if server.exists():
        shutil.rmtree(server)
    server.mkdir(parents=True)
    copied = 0
    for item in ("libraries", "mods", "config"):
        source = runtime_root / item
        if source.is_dir():
            shutil.copytree(source, server / item)
            copied += sum(1 for _ in source.rglob("*") if _.is_file())
    shutil.copyfile(runtime_root / forge_jar, server / forge_jar)
    shutil.copyfile(runtime_root / vanilla_jar, server / vanilla_jar)
    shutil.copyfile(campaign_jar, server / "rustcraft-campaign.jar")
    (server / "eula.txt").write_text("eula=true\n")
    (server / "server.properties").write_text("\n".join([
        "online-mode=false", "max-tick-time=-1",
        "view-distance=%d" % view_distance, "spawn-protection=0",
        "snooper-enabled=false", "enable-rcon=false", "level-seed=",
        "motd=rustcraft-v2-join-probe", "server-port=%d" % PORT,
        "allow-flight=true",
    ]) + "\n")
    world_before = {}
    if world_source is not None and world_source.is_dir():
        shutil.copytree(world_source, server / "world")
        world_before = tree_hash(server / "world")
    return server, {"files_copied": copied, "world_files_before": len(world_before)}


def launch(server: Path, log: Path, *, srg_jar: Path | None, session: dict,
           forge_jar_name: str, vanilla_jar_name: str) -> subprocess.Popen:
    args = [str(JAVA), "-Xmx6G", "-Dfml.queryResult=confirm",
            "-Drustcraft.dumpDir=" + str(server / "transformed"),
            "-Drustcraft.definedDump=" + str(server / "transformed"),
            # Live V2 admission: the writers run under the same generated plan
            # and static policies the offline qualification used, with a fresh
            # process/session identity, so this launch admits itself.
            "-Drustcraft.liveWriterDiagnostic=true",
            "-Drustcraft.session.processId=" + session["process_id"],
            "-Drustcraft.session.transformationSessionId=" + session["session_id"],
            "-Drustcraft.observationDir=" + str(server / "observation"),
            # FML network debug: the handshake codec logs what it actually
            # received, which settles byte-framing questions from the server's
            # side instead of inferring them from a stack trace.
            "-Dfml.debugNetworkHandshake=true"]
    if srg_jar is not None:
        args += ["-Drustcraft.srgJar=" + str(srg_jar)]
    classpath = [str(server / "rustcraft-campaign.jar"),
                 str(server / forge_jar_name), str(server / vanilla_jar_name)]
    classpath += [str(p) for p in sorted((server / "libraries").rglob("*.jar"))]
    args += ["-cp", os.pathsep.join(classpath),
             "net.minecraftforge.fml.relauncher.ServerLaunchWrapper", "nogui"]
    handle = log.open("wb")
    return subprocess.Popen(args, cwd=str(server), stdout=handle,
                            stderr=subprocess.STDOUT, stdin=subprocess.PIPE)


def wait_for(log: Path, pattern: str, timeout_s: int) -> bool:
    compiled = re.compile(pattern)
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        try:
            if compiled.search(log.read_text(encoding="utf-8", errors="replace")):
                return True
        except OSError:
            pass
        time.sleep(2)
    return False


def stop(process: subprocess.Popen, timeout_s: int = 300) -> None:
    try:
        process.stdin.write(b"stop\n")
        process.stdin.flush()
    except (OSError, ValueError):
        pass
    try:
        process.wait(timeout=timeout_s)
    except subprocess.TimeoutExpired:
        process.kill()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--runtime-root", type=Path, required=True)
    parser.add_argument("--forge-jar", required=True)
    parser.add_argument("--vanilla-jar", required=True)
    parser.add_argument("--campaign-jar", type=Path, required=True)
    parser.add_argument("--srg-jar", type=Path, default=None)
    parser.add_argument("--world-source", type=Path, default=None)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--forge", action="store_true",
                        help="the runtime is a Forge/FML server: send the marker "
                             "and require the FML|HS handshake")
    parser.add_argument("--username", default="RustCraftProbe")
    parser.add_argument("--stability-s", type=float, default=20.0)
    parser.add_argument("--boot-timeout-s", type=int, default=1800)
    args = parser.parse_args()

    out = args.output.resolve()
    if out.exists():
        shutil.rmtree(out)
    out.mkdir(parents=True)
    logs = out / "logs"
    logs.mkdir()

    import uuid
    session = {"process_id": str(uuid.uuid4()), "session_id": str(uuid.uuid4())}
    (out / "launch-session.json").write_text(json.dumps(session, indent=2) + "\n")

    server, prepare_info = prepare_server(
        args.runtime_root, out, forge_jar=args.forge_jar, vanilla_jar=args.vanilla_jar,
        campaign_jar=args.campaign_jar, world_source=args.world_source)

    receipt: dict = {
        "schema": "RUSTCRAFT_V2_JOIN_PROBE_RUN_V1",
        "runtime_root": str(args.runtime_root),
        "campaign_jar_sha256": sha(args.campaign_jar),
        "prepare": {"files_copied": prepare_info["files_copied"]},
        "session": session,
        "production_authority": False,
    }

    world_before = tree_hash(server / "world") if (server / "world").is_dir() else {}

    jvm_log = out / "server.log"
    process = launch(server, jvm_log, srg_jar=args.srg_jar, session=session,
                     forge_jar_name=args.forge_jar, vanilla_jar_name=args.vanilla_jar)
    receipt["server_pid"] = process.pid

    booted = wait_for(jvm_log, r"Done \([0-9.]+s\)", args.boot_timeout_s)
    receipt["server_booted"] = booted
    if not booted:
        stop(process, timeout_s=120)
        receipt["verdict"] = "FAIL"
        receipt["failure"] = "server did not reach Done within %ds" % args.boot_timeout_s
        (out / "join-receipt.json").write_text(json.dumps(receipt, indent=2, sort_keys=True) + "\n")
        return 1

    # ONE bounded attempt. No retry loop: a materially different attempt is a
    # new run with its own receipt, decided by a human, not by this loop.
    probe = run_probe("127.0.0.1", PORT, args.username,
                      expect_forge=args.forge, stability_s=args.stability_s)
    (out / "probe.json").write_text(json.dumps(probe, indent=2, sort_keys=True) + "\n")

    stop(process)
    time.sleep(2)

    world_after = tree_hash(server / "world") if (server / "world").is_dir() else {}
    changed = sorted(k for k in world_after
                     if world_before.get(k) != world_after[k])
    created = sorted(set(world_after) - set(world_before))
    removed = sorted(set(world_before) - set(world_after))
    receipt["world"] = {
        "files_before": len(world_before), "files_after": len(world_after),
        "changed": changed[:200], "created": created[:200], "removed": removed[:200],
        "note": "a server that ran WILL write world metadata; the point is that "
                "it is recorded, and that it happened to a disposable copy",
    }

    receipt["probe"] = {k: probe[k] for k in ("verdict", "checks", "failure", "packets_in")}
    receipt["fml"] = probe.get("fml")
    receipt["channels"] = probe.get("channels")
    receipt["classification"] = probe.get("classification")
    receipt["verdict"] = "PASS" if probe["verdict"] == "PASS" else "FAIL"
    receipt["production_authority"] = False
    (out / "join-receipt.json").write_text(json.dumps(receipt, indent=2, sort_keys=True) + "\n")
    print(json.dumps({"verdict": receipt["verdict"],
                      "probe_checks": probe["checks"],
                      "fml": (probe.get("fml") or {}).get("final_state"),
                      "server_mods": (probe.get("fml") or {}).get("server_mod_count"),
                      "failure": probe.get("failure")}, indent=2))
    return 0 if receipt["verdict"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
