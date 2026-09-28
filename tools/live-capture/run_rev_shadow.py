#!/usr/bin/env python3
"""FIRST bounded Revelation 3.4.0 LIVE SHADOW campaign runner.

SHADOW ONLY: Java packets remain the only transmitted chunk bytes; the Rust
replay result is recorded and discarded. The complete pinned Revelation
runtime boots from an isolated working copy (with the real dedicated-server
world, so disk loads exercise IO adoption); a vanilla-protocol probe client
(the accepted Clean Forge harness — Forge 2846 accepts non-FML connections)
joins and moves to stream real chunk packets.

Phases: control (diagnostic OFF, default-OFF live proof), shadow-a
(>=100 compared admitted events), shadow-b (>=2000 compared admitted events
or bounded duration). Any mismatch writes live-shadow-STOP and ends the run.
"""
from __future__ import annotations

import json
import os
import re
import shutil
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
RT = Path("D:/rustcraft-runtime-targets/revelation-3.4.0/server")
JAVA = Path("D:/rustcraft-toolchains/temurin8/jdk8u504-b01/bin/java.exe")
CAMPAIGN = ROOT / "target/rev-shadow"
REV_JAR = ROOT / "target/rev-campaign/rustcraft-rev-coremod.jar"
SRG = ROOT / "target/live-transformer-build/srg/minecraft_server.1.12.2.srg.jar"
DLL = ROOT / "target/release/rustcraft_ffi.dll"
QUAL_SERVER = ROOT / "target/rev-server-qual/server"
PROBE = ROOT / "tools/live-capture/live_client_session.py"


def sha256(path: Path) -> str:
    import hashlib
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def prepare(phase: str) -> Path:
    server_dir = CAMPAIGN / ("server-" + phase)
    if server_dir.exists():
        shutil.rmtree(server_dir)
    server_dir.mkdir(parents=True)
    for item in ("libraries", "mods", "config"):
        shutil.copytree(RT / item, server_dir / item)
    for name in ("minecraft_server.1.12.2.jar", "forge-1.12.2-14.23.5.2846-universal.jar"):
        shutil.copyfile(RT / name, server_dir / name)
    world = QUAL_SERVER / "world"
    if world.is_dir():
        print("copying persisted Revelation world for IO adoption...")
        shutil.copytree(world, server_dir / world.name)
    (server_dir / "eula.txt").write_text("eula=true\n")
    props = "\n".join([
        "online-mode=false", "max-tick-time=-1", "view-distance=8",
        "spawn-protection=0", "snooper-enabled=false", "enable-rcon=false",
        "motd=rev-live-shadow", "server-port=25575",
    ]) + "\n"
    (server_dir / "server.properties").write_text(props)
    return server_dir


def launch(server_dir: Path, diagnostic: bool, phase: str, log_path: Path,
           dll: Path = DLL) -> subprocess.Popen:
    args = [str(JAVA), "-Xmx6G",
            "-Dfml.queryResult=confirm",
            "-Drustcraft.dumpDir=" + str(server_dir / "transformed"),
            "-Drustcraft.srgJar=" + str(SRG)]
    if diagnostic:
        args += ["-Drustcraft.liveWriterDiagnostic=true",
                 "-Drustcraft.liveShadowOut=" + str(server_dir / "live-shadow-events.jsonl"),
                 "-Drustcraft.liveShadowDll=" + str(dll),
                 "-Drustcraft.liveShadowQueueCapacity=4096",
                 "-Drustcraft.liveShadowQueueMaxBytes=" + str(256 * 1024 * 1024),
                 "-Drustcraft.ioTrace=" + str(server_dir / "live-io-trace.jsonl")]
    cp = os.pathsep.join([str(REV_JAR)]
                         + [str(p) for p in sorted((server_dir / "libraries").rglob("*.jar"))]
                         + [str(server_dir / "forge-1.12.2-14.23.5.2846-universal.jar"),
                            str(server_dir / "minecraft_server.1.12.2.jar")])
    args += ["-cp", cp, "net.minecraftforge.fml.relauncher.ServerLaunchWrapper", "nogui"]
    log = open(log_path, "wb")
    env = dict(os.environ)
    return subprocess.Popen(args, cwd=str(server_dir), stdout=log,
                            stderr=subprocess.STDOUT, stdin=subprocess.PIPE, env=env), log


def wait_for_done(log_path: Path, timeout_s: int = 600) -> bool:
    deadline = time.time() + timeout_s
    pattern = re.compile(r"Done \([\d.]+s\)")
    while time.time() < deadline:
        try:
            text = Path(log_path).read_text(encoding="utf-8", errors="replace")
        except OSError:
            time.sleep(1)
            continue
        if pattern.search(text):
            return True
        if "Exception in thread" in text and "Done" not in text:
            pass
        time.sleep(1)
    return False


def stop(process: subprocess.Popen) -> None:
    try:
        process.stdin.write(b"stop\n")
        process.stdin.flush()
    except (OSError, ValueError):
        pass
    try:
        process.wait(timeout=240)
    except subprocess.TimeoutExpired:
        process.kill()


def run_client(server_dir: Path, phase: str, duration: int, port: int,
               spawn=None) -> dict:
    sink = server_dir / ("client-%s.jsonl" % phase)
    out = server_dir / ("client-%s.json" % phase)
    argv = [sys.executable, "-B", str(PROBE), str(port), str(duration), "move",
            str(sink), str(out)]
    if spawn:
        argv += ["%f,%f,%f" % (spawn["x"], spawn["y"], spawn["z"])]
    result = subprocess.run(argv, capture_output=True, text=True, timeout=duration + 120)
    if result.returncode != 0 and not out.exists():
        return {"joined": False, "errors": [result.stderr[-300:]]}
    return json.loads(out.read_text(encoding="utf-8"))


def parse_spawn(log_path: Path) -> dict | None:
    try:
        m = re.search(r"logged in with entity id \d+ at \((-?[\d.]+), (-?[\d.]+), (-?[\d.]+)\)",
                      Path(log_path).read_text(encoding="utf-8", errors="replace"))
        if m:
            return {"x": float(m.group(1)), "y": float(m.group(2)), "z": float(m.group(3))}
    except OSError:
        pass
    return None


def wait_compared(server_dir: Path, target: int, deadline_s: int) -> dict:
    receipt = server_dir / "live-shadow-receipt.json"
    stop_file = server_dir / "live-shadow-STOP"
    deadline = time.time() + deadline_s
    last = {}
    while time.time() < deadline:
        if stop_file.exists():
            last["stoppedBy"] = "MISMATCH_STOP_FILE"
            break
        if receipt.exists():
            try:
                last = json.loads(receipt.read_text(encoding="utf-8"))
                if last.get("compared", 0) >= target:
                    last["targetReached"] = True
                    break
            except (OSError, ValueError):
                pass
        time.sleep(2)
    return last


def main() -> int:
    phases = sys.argv[1:] or ["control", "shadow-a", "shadow-b"]
    campaign = {"phases": {}}
    port = 25575  # avoid colliding with any lingering default-port server
    for phase in phases:
        diagnostic = phase != "control"
        server_dir = prepare(phase)
        log_path = server_dir / "server.log"
        process, log = launch(server_dir, diagnostic, phase, log_path)
        info = {"diagnostic": diagnostic, "dir": str(server_dir)}
        if not wait_for_done(log_path):
            info["serverUp"] = False
            stop(process)
            log.close()
            campaign["phases"][phase] = info
            (CAMPAIGN / "campaign-receipt.json").write_text(json.dumps(campaign, indent=1))
            print(json.dumps({"status": "INCOMPLETE", "phase": phase,
                              "reason": "never reached Done"}))
            return 2
        info["serverUp"] = True
        time.sleep(10)  # Forge accepts logins a few ticks after the Done line
        if phase == "control":
            client = run_client(server_dir, "control", 90, port)
            info["client"] = client
            stop(process)
            log.close()
            events = (server_dir / "live-shadow-events.jsonl").exists()
            receipt = (server_dir / "live-shadow-receipt.json").exists()
            clean = (not events) and (not receipt) and client.get("joined")
            info["defaultOffClean"] = clean
            campaign["phases"][phase] = info
            (CAMPAIGN / "campaign-receipt.json").write_text(json.dumps(campaign, indent=1))
            if not clean:
                print(json.dumps({"status": "FAIL", "phase": "control",
                                  "reason": "diagnostic artifacts in default-OFF boot"}))
                return 1
            print("control: default-OFF clean")
            continue
        spawn = parse_spawn(log_path)
        target = 100 if phase == "shadow-a" else 2000
        duration = 420 if phase == "shadow-a" else 1200
        client = run_client(server_dir, phase, duration, port, spawn)
        info["client"] = client
        state = wait_compared(server_dir, target, 300)
        info["finalCounters"] = state
        stop(process)
        log.close()
        receipt_path = server_dir / "live-shadow-receipt.json"
        if receipt_path.exists():
            info["receipt"] = json.loads(receipt_path.read_text(encoding="utf-8"))
        campaign["phases"][phase] = info
        (CAMPAIGN / "campaign-receipt.json").write_text(json.dumps(campaign, indent=1))
        rec = info.get("receipt", {})
        mism = rec.get("mismatchSeen") or state.get("stoppedBy") == "MISMATCH_STOP_FILE"
        if mism:
            print(json.dumps({"status": "FAIL", "phase": phase, "reason": "mismatch"}))
            return 1
        print("%s: compared=%s ioAdoptedCompared=%s drops=%s gateDisq=%s" % (
            phase, rec.get("compared"), rec.get("ioAdoptedCompared"),
            rec.get("queueDropped"), rec.get("gateDisqualified")))
    (CAMPAIGN / "campaign-receipt.json").write_text(json.dumps(campaign, indent=1))
    print("campaign receipt: " + str(CAMPAIGN / "campaign-receipt.json"))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
