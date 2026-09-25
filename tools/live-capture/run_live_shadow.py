#!/usr/bin/env python3
"""FIRST bounded Clean Forge live SHADOW campaign runner.

Phases: control (diagnostic OFF, live default-OFF proof), A (diagnostic ON,
>=100 compared accepted events), B (>=2000 compared events or bounded time).
SHADOW ONLY: Java packets remain the only transmitted chunk bytes; the Rust
replay result is recorded and discarded. Any comparison mismatch stops the
campaign with a full event artifact.

Prerequisites are verified before launch; wrong prerequisites yield
NOT_RUN / INCOMPLETE / ARTIFACT_MISMATCH — never a false PASS.
"""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
import uuid

ROOT = Path(__file__).resolve().parents[2]
RT = Path("D:/rustcraft-runtime-targets/clean-forge-2860/server")
JAVA_HOME = Path("D:/rustcraft-toolchains/temurin8/jdk8u504-b01")
MILESTONE_COMMIT = "536b210"
CAMPAIGN_ROOT = ROOT / "target" / "live-shadow-campaign"


def sha256(path: Path) -> str:
    import hashlib
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def preflight() -> dict:
    problems = []
    head = subprocess.run(["git", "rev-parse", "HEAD"], capture_output=True, text=True, cwd=ROOT).stdout.strip()
    ancestry = subprocess.run(["git", "merge-base", "--is-ancestor", MILESTONE_COMMIT, head],
                              capture_output=True, text=True, cwd=ROOT)
    if ancestry.returncode != 0:
        problems.append("capture-admission milestone %s is not an ancestor of HEAD %s" % (MILESTONE_COMMIT, head))
    dll = ROOT / "target/release/rustcraft_ffi.dll"
    if not dll.is_file():
        problems.append("release FFI missing: " + str(dll))
    srg = ROOT / "target/live-transformer-build/srg/minecraft_server.1.12.2.srg.jar"
    if not srg.is_file():
        problems.append("SRG study jar missing: " + str(srg))
    # production authority must remain fail-closed in the tree we are running
    payload = (ROOT / "tools/bridge/src/com/rustcraft/bridge/M4NativeStatePayload.java").read_text(encoding="utf-8")
    if "FALLBACK_CAPTURE_UNSAFE.incrementAndGet();" not in payload or "return null;" not in payload:
        problems.append("M4NativeStatePayload.tryEncode no longer fail-closed")
    contract = (ROOT / "tools/bridge/src/com/rustcraft/bridge/capture/CaptureContract.java").read_text(encoding="utf-8")
    if "public boolean productionAuthorityEligible() { return false; }" not in contract:
        problems.append("productionAuthorityEligible() is no longer hard-coded false")
    # artifact identity: exact pinned hashes via the accepted validator
    sys.path.insert(0, str(ROOT / "tools/testing"))
    import forge_runtime
    pins = json.loads((ROOT / "tools/forge-capture/runtime-pins.json").read_text(encoding="utf-8"))
    try:
        artifacts = forge_runtime.validate_artifacts(RT, pins)
    except forge_runtime.Incomplete as error:
        problems.append("ARTIFACT_MISMATCH: %s (%s)" % (error.reason, error.detail))
        artifacts = []
    return {"problems": problems, "head": head, "artifacts": artifacts,
            "dll": str(dll), "srg": str(srg)}


def prepare_server_dir(phase: str, coremod_jar: Path) -> Path:
    server_dir = CAMPAIGN_ROOT / ("server-" + phase)
    if server_dir.exists():
        shutil.rmtree(server_dir)
    (server_dir / "mods").mkdir(parents=True)
    shutil.copyfile(coremod_jar, server_dir / "mods" / "rustcraft-live-coremod.jar")
    (server_dir / "eula.txt").write_text("eula=true\n", encoding="utf-8")
    props = "\n".join([
        "online-mode=false", "level-type=FLAT", "level-seed=4", "generate-structures=false",
        "view-distance=6", "max-players=2", "motd=issue1-live-shadow", "spawn-protection=0",
        "snooper-enabled=false", "difficulty=0", "allow-nether=false", "enable-rcon=false",
        "spawn-monsters=false", "spawn-animals=false", "spawn-npcs=false",
    ]) + "\n"
    (server_dir / "server.properties").write_text(props, encoding="utf-8")
    return server_dir


def launch_server(server_dir: Path, dll: Path, observer_jar: Path, diagnostic: bool,
                  phase: str, log_path: Path) -> subprocess.Popen:
    java = JAVA_HOME / "bin" / "java.exe"
    args = [str(java), "-Xmx2G",
            "-javaagent:" + str(observer_jar.resolve()),
            "-Drustcraft.dumpDir=" + str(server_dir / "transformed"),
            "-Drustcraft.srgJar=" + str(CAMPAIGN_ROOT / "srg-minecraft.jar")]
    if diagnostic:
        args += ["-Drustcraft.liveWriterDiagnostic=true",
                 "-Drustcraft.liveShadowOut=" + str(server_dir / "live-shadow-events.jsonl"),
                 "-Drustcraft.liveShadowDll=" + str(dll),
                 "-Drustcraft.liveShadowQueueCapacity=1024",
                 "-Drustcraft.liveShadowQueueMaxBytes=" + str(64 * 1024 * 1024)]
    # classpath: pinned qualified jars + the campaign coremod (absolute paths).
    cp = os.pathsep.join([
        str(RT / "libraries/org/ow2/asm/asm-debug-all/5.2/asm-debug-all-5.2.jar"),
        str(RT / "libraries/net/minecraft/launchwrapper/1.12/launchwrapper-1.12.jar"),
        str(RT / "libraries/net/sf/jopt-simple/jopt-simple/5.0.3/jopt-simple-5.0.3.jar"),
        str(RT / "libraries/java3d/vecmath/1.5.2/vecmath-1.5.2.jar"),
        str(RT / "libraries/net/sf/trove4j/trove4j/3.0.3/trove4j-3.0.3.jar"),
        str(RT / "libraries/com/typesafe/akka/akka-actor_2.11/2.3.3/akka-actor_2.11-2.3.3.jar"),
        str(RT / "libraries/com/typesafe/config/1.2.1/config-1.2.1.jar"),
        str(RT / "libraries/org/scala-lang/scala-library/2.11.1/scala-library.jar"),
        str(RT / "libraries/org/scala-lang/scala-actors-migration_2.11/1.1.0/scala-actors-migration-1.1.0.jar"),
        str(RT / "libraries/org/scala-lang/scala-compiler/2.11.1/scala-compiler.jar"),
        str(RT / "libraries/org/scala-lang/plugins/scala-continuations-library_2.11/1.0.2_mc/scala-continuations-library-1.0.2.jar"),
        str(RT / "libraries/org/scala-lang/plugins/scala-continuations-plugin_2.11.1/1.0.2_mc/scala-continuations-plugin-1.0.2.jar"),
        str(RT / "libraries/org/scala-lang/scala-parser-combinators/1.0.4/scala-parser-combinators-1.0.4.jar"),
        str(RT / "libraries/org/scala-lang/scala-reflect/2.11.1/scala-reflect.jar"),
        str(RT / "libraries/org/scala-lang/scala-swing/2.11.1/scala-swing-2.11.1.jar"),
        str(RT / "libraries/org/scala-lang/scala-xml/1.0.2/scala-xml.jar"),
        str(RT / "libraries/lzma/lzma/0.0.1/lzma-0.0.1.jar"),
        str(RT / "libraries/org/apache/logging/log4j/log4j-api/2.15.0/log4j-api-2.15.0.jar"),
        str(RT / "libraries/org/apache/logging/log4j/log4j-core/2.15.0/log4j-core-2.15.0.jar"),
        str(RT / "libraries/org/apache/maven/maven-artifact/3.5.3/maven-artifact-3.5.3.jar"),
        str(RT / "libraries/net/minecraft/launchwrapper/1.12/launchwrapper-1.12.jar"),
        str(CAMPAIGN_ROOT / "rustcraft-live-coremod.jar"),
        str(RT / "minecraft_server.1.12.2.jar"),
        str(RT / "forge-1.12.2-14.23.5.2860.jar"),
    ])
    args += ["-cp", cp, "net.minecraftforge.fml.relauncher.ServerLaunchWrapper", "nogui"]
    log = open(log_path, "wb")
    env = dict(os.environ)
    process = subprocess.Popen(args, cwd=str(server_dir), stdout=log, stderr=subprocess.STDOUT,
                               stdin=subprocess.PIPE, env=env)
    return process


def wait_for_done(log_path: Path, timeout_s: int = 180) -> bool:
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
        time.sleep(1)
    return False


def stop_server(process: subprocess.Popen):
    try:
        process.stdin.write(b"stop\n")
        process.stdin.flush()
    except (OSError, ValueError):
        pass
    try:
        process.wait(timeout=90)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait(timeout=30)


def run_client(port: int, duration: int, move: bool, server_dir: Path, phase: str,
               spawn=None) -> dict:
    sink = server_dir / ("client-%s.jsonl" % phase)
    out = server_dir / ("client-%s.json" % phase)
    script = ROOT / "tools/live-capture/live_client_session.py"
    argv = [sys.executable, "-B", str(script), str(port), str(duration),
            "move" if move else "hold", str(sink), str(out)]
    if spawn:
        argv += ["%f,%f,%f" % (spawn["x"], spawn["y"], spawn["z"])]
    result = subprocess.run(argv, capture_output=True, text=True, timeout=duration + 120)
    if result.returncode != 0 and not out.is_file():
        return {"joined": False, "errors": [result.stderr[-400:]]}
    return json.loads(out.read_text(encoding="utf-8"))


def wait_for_compared(receipt_path: Path, target: int, deadline_s: int, server_dir: Path) -> dict:
    deadline = time.time() + deadline_s
    stop_file = server_dir / "live-shadow-STOP"
    last = {}
    while time.time() < deadline:
        if stop_file.is_file():
            last["stoppedBy"] = "MISMATCH_STOP_FILE"
            return last
        receipt = server_dir / "live-shadow-receipt.json"
        if receipt.is_file():
            try:
                last = json.loads(receipt.read_text(encoding="utf-8"))
                if last.get("compared", 0) >= target:
                    return last
            except (OSError, ValueError):
                pass
        time.sleep(2)
    last["stoppedBy"] = "deadline"
    return last


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--phase", choices=["control", "a", "b", "all"], default="all")
    parser.add_argument("--output", type=Path, default=None)
    parser.add_argument("--phase-a-target", type=int, default=100)
    parser.add_argument("--phase-b-target", type=int, default=2000)
    parser.add_argument("--phase-b-max-s", type=int, default=600)
    parser.add_argument("--port", type=int, default=25565)
    args = parser.parse_args()
    if args.output is None:
        args.output = CAMPAIGN_ROOT / ("run-" + uuid.uuid4().hex[:8])
    args.output = args.output.resolve()

    print(json.dumps(preflight_result := preflight(), indent=2))
    if preflight_result["problems"]:
        print(json.dumps({"status": "INCOMPLETE", "problems": preflight_result["problems"]}))
        return 2
    (args.output).mkdir(parents=True, exist_ok=False)
    coremod_jar = CAMPAIGN_ROOT / "rustcraft-live-coremod.jar"
    srg_dst = CAMPAIGN_ROOT / "srg-minecraft.jar"
    if not srg_dst.is_file():
        shutil.copyfile(preflight_result["srg"], srg_dst)
    if not coremod_jar.is_file():
        result = subprocess.run(["bash", str(ROOT / "tools/live-capture/build_campaign_coremod.sh"),
                                 preflight_result["srg"], str(coremod_jar)], capture_output=True, text=True)
        if result.returncode != 0:
            print(result.stdout[-800:], result.stderr[-800:])
            print(json.dumps({"status": "INCOMPLETE", "reason": "coremod build failed"}))
            return 2
    # passive observer for live transformed-class hashes
    observer_jar = args.output / "observer.jar"
    sys.path.insert(0, str(ROOT / "tools/testing"))
    import forge_runtime
    boot_classes = ROOT / "target/rustcraft-tests/forge-cache/bootstrap"
    agent_classes = None
    for candidate in sorted(boot_classes.glob("*/classes")):
        if (candidate / "com/rustcraft/offline/agent/ObservationAgent.class").is_file():
            agent_classes = candidate
            break
    if agent_classes is None:
        print(json.dumps({"status": "INCOMPLETE", "reason": "observer classes missing (run live-capture lane once)"}))
        return 2
    observer_jar.parent.mkdir(parents=True, exist_ok=True)
    forge_runtime.make_observer_jar(agent_classes, observer_jar)

    phases = {"control": False, "a": True, "b": True}
    selected = list(phases.items()) if args.phase == "all" else [(args.phase, phases[args.phase])]
    campaign = {"phases": {}, "head": preflight_result["head"], "artifacts": preflight_result["artifacts"]}

    for phase, diagnostic in selected:
        server_dir = prepare_server_dir(phase, coremod_jar)
        log_path = server_dir / "server.log"
        process = launch_server(server_dir, Path(preflight_result["dll"]), observer_jar,
                                diagnostic, phase, log_path)
        phase_info = {"diagnostic": diagnostic, "serverDir": str(server_dir), "log": str(log_path)}
        if not wait_for_done(log_path):
            phase_info["serverUp"] = False
            stop_server(process)
            campaign["phases"][phase] = phase_info
            print(json.dumps({"status": "INCOMPLETE", "phase": phase, "reason": "server never reached Done"}))
            (args.output / "campaign-receipt.json").write_text(json.dumps(campaign, indent=2))
            return 2
        phase_info["serverUp"] = True
        duration = 25 if phase == "control" else (150 if phase == "a" else args.phase_b_max_s // 2)
        move = phase != "control"
        spawn = None
        m = re.search(r"logged in with entity id \d+ at \((-?[\d.]+), (-?[\d.]+), (-?[\d.]+)\)",
                      Path(log_path).read_text(encoding="utf-8", errors="replace"))
        if m:
            spawn = {"x": float(m.group(1)), "y": float(m.group(2)), "z": float(m.group(3))}
        if phase == "b":
            # two bounded sessions: also exercises a fresh client reconnect
            s1 = run_client(args.port, duration, move, server_dir, phase + "-s1", spawn)
            s2 = run_client(args.port, duration, move, server_dir, phase + "-s2", spawn)
            phase_info["client"] = {"sessions": [s1, s2]}
            phase_info["chunkPacketsTotal"] = (s1.get("chunkPackets", 0)
                                                + s2.get("chunkPackets", 0))
            if s1.get("errors"):
                phase_info["clientSession1Errors"] = s1["errors"]
        else:
            summary = run_client(args.port, duration, move, server_dir, phase, spawn)
            phase_info["client"] = summary
        # give the consumer a moment to flush, then stop the server (receipt hook runs)
        target = {"control": 0, "a": args.phase_a_target, "b": args.phase_b_target}[phase]
        if diagnostic:
            deadline = max(60, duration * 2 + 90) if phase == "b" else max(60, duration + 90)
            state = wait_for_compared(server_dir, target, deadline, server_dir)
            phase_info["finalCounters"] = state
        stop_server(process)
        # artifacts produced by the shutdown hook land after process exit
        receipt = server_dir / "live-shadow-receipt.json"
        if receipt.is_file():
            phase_info["receipt"] = json.loads(receipt.read_text(encoding="utf-8"))
        events = server_dir / "live-shadow-events.jsonl"
        phase_info["eventLines"] = sum(1 for _ in events.open(encoding="utf-8")) if events.is_file() else 0
        campaign["phases"][phase] = phase_info
        (args.output / "campaign-receipt.json").write_text(json.dumps(campaign, indent=2))
        if phase == "control":
            # default-OFF proof: no consumer output of any kind
            clean = (not receipt.is_file()) and phase_info["eventLines"] == 0
            campaign["phases"][phase]["defaultOffClean"] = clean
            if not clean:
                print(json.dumps({"status": "FAIL", "phase": "control",
                                  "reason": "diagnostic artifacts appeared in default-OFF boot"}))
                (args.output / "campaign-receipt.json").write_text(json.dumps(campaign, indent=2))
                return 1
        if phase in ("a", "b"):
            mism = phase_info.get("finalCounters", {}).get("mismatchSeen", False) or \
                phase_info.get("receipt", {}).get("mismatchSeen", False)
            if mism:
                print(json.dumps({"status": "FAIL", "phase": phase, "reason": "comparison mismatch"}))
                (args.output / "campaign-receipt.json").write_text(json.dumps(campaign, indent=2))
                return 1

    (args.output / "campaign-receipt.json").write_text(json.dumps(campaign, indent=2))
    print("campaign receipt: " + str(args.output / "campaign-receipt.json"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
