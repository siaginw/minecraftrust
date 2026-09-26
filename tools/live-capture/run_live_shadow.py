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
GOLDEN = CAMPAIGN_ROOT / "io-golden-world"


def git_state() -> dict:
    head = subprocess.run(["git", "rev-parse", "HEAD"], capture_output=True, text=True,
                          cwd=ROOT).stdout.strip()
    dirty = subprocess.run(["git", "status", "--porcelain"], capture_output=True, text=True,
                           cwd=ROOT).stdout.strip() != ""
    return {"head": head, "dirty": dirty}


def compute_build_id(coremod_jar: Path) -> dict:
    """Identity of exactly what is being booted; asserted in the server log."""
    hooks = ROOT / "tools/bridge/src/com/rustcraft/bridge/capture/LiveWriterHooks.java"
    state = git_state()
    return {"gitHead": state["head"], "gitDirty": state["dirty"],
            "hooksSha256": sha256(hooks), "coremodJarSha256": sha256(coremod_jar),
            "builtAt": time.strftime("%Y-%m-%dT%H:%M:%S")}


def freeze_golden_world(source_dir: Path) -> dict:
    """Freeze a known-good persisted world; the golden copy is never booted."""
    world = source_dir / "world"
    region = world / "region"
    if not region.is_dir():
        raise FileNotFoundError("no persisted region dir: " + str(region))
    if GOLDEN.exists():
        shutil.rmtree(GOLDEN)
    GOLDEN.mkdir(parents=True)
    shutil.copytree(world, GOLDEN / "world")
    files = {}
    for mca in sorted((GOLDEN / "world" / "region").glob("r.*.*.mca")):
        import struct
        with mca.open("rb") as handle:
            header = handle.read(8192)
        chunks = sum(1 for i in range(1024) if header[i * 4:i * 4 + 3] != b"\x00\x00\x00")
        files[mca.name] = {"sha256": sha256(mca), "bytes": mca.stat().st_size, "chunks": chunks}
    props = {}
    for line in (source_dir / "server.properties").read_text(encoding="utf-8").splitlines():
        if "=" in line and not line.startswith("#"):
            key, _, value = line.partition("=")
            props[key] = value
    manifest = {
        "frozenAt": time.strftime("%Y-%m-%dT%H:%M:%S"),
        "sourceDir": str(source_dir),
        "world": {"seed": props.get("level-seed"), "levelType": props.get("level-type"),
                  "viewDistance": props.get("view-distance")},
        "regions": files,
        "totalPersistedChunks": sum(item["chunks"] for item in files.values()),
        "cleanShutdownEvidence": "world frozen from a stopped campaign server dir",
        "smokeTargets": "spawn-area chunks (client moves from spawn; view-distance 6)",
    }
    (GOLDEN / "golden-manifest.json").write_text(json.dumps(manifest, indent=2))
    return manifest


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
    # io2 reuses io1's persisted world (the disk chunks ARE the test subject).
    if phase == "io2":
        server_dir = CAMPAIGN_ROOT / "server-io1"
        if not server_dir.exists():
            raise FileNotFoundError("io1 world not saved: " + str(server_dir))
    else:
        server_dir = CAMPAIGN_ROOT / ("server-" + phase)
        if server_dir.exists():
            shutil.rmtree(server_dir)
        (server_dir / "mods").mkdir(parents=True)
        shutil.copyfile(coremod_jar, server_dir / "mods" / "rustcraft-live-coremod.jar")
        (server_dir / "eula.txt").write_text("eula=true\n", encoding="utf-8")
    nether = "true" if phase == "dim" else "false"
    monsters = "true" if phase == "te" else "false"
    props = "\n".join([
        "online-mode=false", "level-type=FLAT", "level-seed=4", "generate-structures=false",
        "view-distance=6", "max-players=2", "motd=issue1-live-shadow", "spawn-protection=0",
        "snooper-enabled=false", "difficulty=0", "allow-nether=" + nether, "enable-rcon=false",
        "spawn-monsters=" + monsters, "spawn-animals=false", "spawn-npcs=false",
    ]) + "\n"
    (server_dir / "server.properties").write_text(props, encoding="utf-8")
    # (re)install the current coremod jar for every phase
    (server_dir / "mods").mkdir(parents=True, exist_ok=True)
    shutil.copyfile(coremod_jar, server_dir / "mods" / "rustcraft-live-coremod.jar")
    return server_dir


def launch_server(server_dir: Path, dll: Path, observer_jar: Path, diagnostic: bool,
                  phase: str, log_path: Path, build_id: str = None) -> subprocess.Popen:
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
                 "-Drustcraft.liveShadowQueueMaxBytes=" + str(64 * 1024 * 1024),
                 "-Drustcraft.ioTrace=" + str(server_dir / "live-io-trace.jsonl")]
    if build_id:
        args += ["-Drustcraft.buildId=" + build_id]
    if phase == "dim":
        args += ["-Drustcraft.liveShadowDimTest=true"]
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


def start_client(port: int, duration: int, move, server_dir: Path, phase: str,
                 spawn=None) -> subprocess.Popen:
    sink = server_dir / ("client-%s.jsonl" % phase)
    out = server_dir / ("client-%s.json" % phase)
    script = ROOT / "tools/live-capture/live_client_session.py"
    # move accepts True ("move" = walk +X), "walkback" (walk -X) or False (hold)
    mode = move if isinstance(move, str) else ("move" if move else "hold")
    argv = [sys.executable, "-B", str(script), str(port), str(duration),
            mode, str(sink), str(out)]
    if spawn:
        argv += ["%f,%f,%f" % (spawn["x"], spawn["y"], spawn["z"])]
    return subprocess.Popen(argv, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def run_client(port: int, duration: int, move, server_dir: Path, phase: str,
               spawn=None) -> dict:
    sink = server_dir / ("client-%s.jsonl" % phase)
    out = server_dir / ("client-%s.json" % phase)
    process = start_client(port, duration, move, server_dir, phase, spawn)
    try:
        process.wait(timeout=duration + 120)
    except subprocess.TimeoutExpired:
        process.kill()
        if not out.is_file():
            return {"joined": False, "errors": ["client timeout"]}
    if process.returncode != 0 and not out.is_file():
        return {"joined": False, "errors": ["client exited %s" % process.returncode]}
    return json.loads(out.read_text(encoding="utf-8"))


def _parse_spawn(log_path: Path):
    import re
    try:
        m = re.search(r"logged in with entity id \d+ at \((-?[\d.]+), (-?[\d.]+), (-?[\d.]+)\)",
                      Path(log_path).read_text(encoding="utf-8", errors="replace"))
        if m:
            return {"x": float(m.group(1)), "y": float(m.group(2)), "z": float(m.group(3))}
    except (OSError, ValueError):
        pass
    return {"x": 0.0, "y": 4.0, "z": 0.0}


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


def prepare_io_smoke_dir(coremod_jar: Path) -> Path:
    """Fresh COPY of the frozen golden persisted world; the golden never boots."""
    if not (GOLDEN / "world").is_dir():
        raise FileNotFoundError("golden world missing; run --phase freeze-golden first")
    server_dir = CAMPAIGN_ROOT / "server-io-smoke"
    if server_dir.exists():
        shutil.rmtree(server_dir)
    (server_dir / "mods").mkdir(parents=True)
    shutil.copyfile(coremod_jar, server_dir / "mods" / "rustcraft-live-coremod.jar")
    shutil.copytree(GOLDEN / "world", server_dir / "world")
    (server_dir / "eula.txt").write_text("eula=true\n", encoding="utf-8")
    props = "\n".join([
        "online-mode=false", "level-type=FLAT", "level-seed=4", "generate-structures=false",
        "view-distance=6", "max-players=2", "motd=issue1-io-smoke", "spawn-protection=0",
        "snooper-enabled=false", "difficulty=0", "allow-nether=false", "enable-rcon=false",
        "spawn-monsters=false", "spawn-animals=false", "spawn-npcs=false",
    ]) + "\n"
    (server_dir / "server.properties").write_text(props, encoding="utf-8")
    return server_dir


def poll_events(events_path: Path, state: dict) -> dict:
    """Incremental byte-offset tail of the consumer's event JSONL.

    Binary mode with manual accounting: CPython forbids tell() during
    text-mode iteration ("telling position disabled by next() call"), which
    silently starved the first smoke poll loop. A trailing partial line is
    re-read on the next poll.
    """
    try:
        with events_path.open("rb") as handle:
            handle.seek(state["offset"])
            consumed = 0
            last = b""
            for raw in handle:
                consumed += len(raw)
                last = raw
                try:
                    event = json.loads(raw.decode("utf-8", errors="replace"))
                except ValueError:
                    continue
                state["events"] += 1
                if event.get("ioAdopted") and event.get("byteEqual"):
                    state["green"] = event
                elif event.get("byteEqual") is False or event.get("mismatch"):
                    state["mismatchSeen"] = True
            if last and not last.endswith(b"\n"):
                consumed -= len(last)  # partial write; re-read on the next poll
            state["offset"] += consumed
    except (OSError, ValueError):
        pass
    return state


def io_smoke_phase(process: subprocess.Popen, server_dir: Path, log_path: Path,
                   build: dict, port: int, client_seconds: int) -> dict:
    """Fast single-ticket smoke: poll 1/s, stop at the first decisive event."""
    spawn = _parse_spawn(log_path)
    client_argv = [sys.executable, "-B", str(ROOT / "tools/live-capture/live_client_session.py"),
                   str(port), str(client_seconds), "move",
                   str(server_dir / "client-io-smoke.jsonl"), str(server_dir / "client-io-smoke.json"),
                   "%f,%f,%f" % (spawn["x"], spawn["y"], spawn["z"])]
    client = subprocess.Popen(client_argv, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    events_path = server_dir / "live-shadow-events.jsonl"
    state = {"offset": 0, "events": 0, "green": None, "mismatchSeen": False}
    deadline = time.time() + 120  # bounded smoke window AFTER server Done
    verdict, decisive = "TIMEOUT", None
    while time.time() < deadline:
        poll_events(events_path, state)
        try:
            log_text = Path(log_path).read_text(encoding="utf-8", errors="replace")
        except OSError:
            log_text = ""
        if process.poll() is not None:
            verdict, decisive = "FATAL", "server process exited early"
            break
        if "Done (" in log_text and \
                "BUILD_ID " + build["coremodJarSha256"][:16] not in log_text and \
                "BUILD_ID " + build["coremodJarSha256"] not in log_text:
            verdict, decisive = "FATAL", "stale jar: BUILD_ID banner missing from server log"
            break
        if state["green"] is not None:
            verdict, decisive = "GREEN", state["green"]
            break
        if state["mismatchSeen"]:
            verdict, decisive = "FATAL", "comparison mismatch event"
            break
        if "DISQUALIFICATION WATCHDOG" in log_text:
            verdict, decisive = "FATAL", "gate disqualification"
            break
        if "IO ADMISSION FAILED" in log_text:
            line = [l for l in log_text.splitlines() if "IO ADMISSION FAILED" in l][-1]
            if "source=FRESH_DISK_CURRENT" in line:
                # decisive: a disk-qualified ticket was refused — stop immediately
                verdict, decisive = "FAIL", line.split("[live-capture]")[-1].strip()
                break
        if client.poll() is not None and client.poll() == 0 and state["events"] == 0 \
                and (server_dir / "client-io-smoke.json").is_file():
            summary = json.loads((server_dir / "client-io-smoke.json").read_text(encoding="utf-8"))
            if summary.get("chunkPackets", 0) == 0:
                verdict, decisive = "FATAL", "client saw zero chunk packets"
                break
        time.sleep(1)
    try:
        client.wait(timeout=30)
    except subprocess.TimeoutExpired:
        client.kill()
    stop_server(process)
    return {"verdict": verdict, "decisive": decisive,
            "eventsSeen": state["events"], "spawn": spawn}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--phase", choices=["control", "a", "b", "io", "io-smoke",
                                            "freeze-golden", "te", "dim", "all"], default="all")
    parser.add_argument("--output", type=Path, default=None)
    parser.add_argument("--phase-a-target", type=int, default=100)
    parser.add_argument("--phase-b-target", type=int, default=2000)
    parser.add_argument("--phase-b-max-s", type=int, default=600)
    parser.add_argument("--port", type=int, default=25565)
    args = parser.parse_args()

    if args.phase == "freeze-golden":
        manifest = freeze_golden_world(CAMPAIGN_ROOT / "server-io1")
        print(json.dumps({"status": "FROZEN", "golden": str(GOLDEN),
                          "totalPersistedChunks": manifest["totalPersistedChunks"],
                          "regions": sorted(manifest["regions"])}))
        return 0

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

    phases = {"control": False, "a": True, "b": True, "io": True, "te": True, "dim": True}
    if args.phase == "all":
        selected = [("control", False), ("a", True), ("b", True)]
    elif args.phase == "io":
        selected = [("io1", True), ("io2", True)]
    else:
        selected = [(args.phase, phases.get(args.phase, True))]
    campaign = {"phases": {}, "head": preflight_result["head"], "artifacts": preflight_result["artifacts"]}

    for phase, diagnostic in selected:
        build = compute_build_id(coremod_jar)
        if phase == "io-smoke":
            server_dir = prepare_io_smoke_dir(coremod_jar)
        else:
            server_dir = prepare_server_dir(phase, coremod_jar)
        log_path = server_dir / "server.log"
        process = launch_server(server_dir, Path(preflight_result["dll"]), observer_jar,
                                diagnostic, phase, log_path,
                                build_id=build["coremodJarSha256"] if diagnostic else None)
        phase_info = {"diagnostic": diagnostic, "serverDir": str(server_dir), "log": str(log_path),
                      "build": build}
        if not wait_for_done(log_path):
            phase_info["serverUp"] = False
            stop_server(process)
            campaign["phases"][phase] = phase_info
            print(json.dumps({"status": "INCOMPLETE", "phase": phase, "reason": "server never reached Done"}))
            (args.output / "campaign-receipt.json").write_text(json.dumps(campaign, indent=2))
            return 2


        # -- edge-coverage phase handlers --
        if phase == "io-smoke":
            result = io_smoke_phase(process, server_dir, log_path, build, args.port, 90)
            phase_info["smoke"] = result
            trace = server_dir / "live-io-trace.jsonl"
            if trace.is_file():
                lines = trace.read_text(encoding="utf-8", errors="replace").splitlines()
                phase_info["traceLines"] = len(lines)
                phase_info["traceTail"] = lines[-40:]
            receipt = server_dir / "live-shadow-receipt.json"
            if receipt.is_file():
                phase_info["receipt"] = json.loads(receipt.read_text(encoding="utf-8"))
            campaign["phases"][phase] = phase_info
            (args.output / "campaign-receipt.json").write_text(json.dumps(campaign, indent=2))
            verdict = result["verdict"]
            if verdict == "GREEN":
                event = result["decisive"] or {}
                print("IO_SMOKE_GREEN")
                print("  chunk=" + str(event.get("chunkX")) + "," + str(event.get("chunkZ"))
                      + " javaMask=" + str(event.get("javaMask")) + " rustMask=" + str(event.get("rustMask"))
                      + " javaLen=" + str(event.get("javaLen")) + " rustLen=" + str(event.get("rustLen"))
                      + " byteEqual=" + str(event.get("byteEqual")))
                print("  receipt: ioAdmitted=" + str(phase_info.get("receipt", {}).get("ioAdmitted"))
                      + " ioAdoptedCompared=" + str(phase_info.get("receipt", {}).get("ioAdoptedCompared"))
                      + " queueDrops=" + str(phase_info.get("receipt", {}).get("queueDropped")))
                print("  build: jar=" + build["coremodJarSha256"][:16] + " dirty=" + str(build["gitDirty"]))
                return 0
            if verdict == "FAIL":
                print("IO_SMOKE_FAIL " + str(result["decisive"]))
                print("  trace: " + str(server_dir / "live-io-trace.jsonl"))
                return 2
            print("IO_SMOKE_" + verdict + " " + json.dumps(result, default=str)[:600])
            print("  trace: " + str(server_dir / "live-io-trace.jsonl"))
            return 3 if verdict == "TIMEOUT" else 1
        if phase == "io1":
            summary = run_client(args.port, 180, True, server_dir, "io1", _parse_spawn(log_path))
            phase_info["client"] = summary
            stop_server(process)
            phase_info["worldSaved"] = (server_dir / "world" / "region").is_dir()
            # snapshot BEFORE io2 reboots in this same dir and overwrites the receipt
            receipt = server_dir / "live-shadow-receipt.json"
            if receipt.is_file():
                phase_info["receipt"] = json.loads(receipt.read_text(encoding="utf-8"))
            rec = phase_info.get("receipt", {})
            checks = {
                "freshGenerationAdmitsNothing": rec.get("ioAdmitted", -1) == 0,
                "freshGenerationTicketsExist": rec.get("ioTicketCreated", 0) >= 1,
                "gateNotDisqualified": rec.get("gateDisqualified") is not True,
                "noMismatchSeen": rec.get("mismatchSeen") is not True,
            }
            phase_info["freshGenerationExclusion"] = checks
            campaign["phases"][phase] = phase_info
            (args.output / "campaign-receipt.json").write_text(json.dumps(campaign, indent=2))
            if not all(checks.values()):
                print(json.dumps({"status": "FAIL", "phase": phase,
                                  "failedChecks": [k for k, v in checks.items() if not v]}))
                (args.output / "campaign-receipt.json").write_text(json.dumps(campaign, indent=2))
                return 1
            continue
        if phase == "io2":
            summary = run_client(args.port, 240, True, server_dir, "io2", _parse_spawn(log_path))
            phase_info["client"] = summary
            state = wait_for_compared(server_dir, 1, 120, server_dir)
            phase_info["finalCounters"] = state
            stop_server(process)
            receipt = server_dir / "live-shadow-receipt.json"
            if receipt.is_file():
                phase_info["receipt"] = json.loads(receipt.read_text(encoding="utf-8"))
            trace = server_dir / "live-io-trace.jsonl"
            if trace.is_file():
                phase_info["traceLines"] = sum(1 for _ in trace.open(encoding="utf-8", errors="replace"))
            # qualification bar: adoption happened, comparisons are exact,
            # nothing was dropped, and the gate never disqualified.
            rec = phase_info.get("receipt", {})
            checks = {
                "ioAdmitted>=1": rec.get("ioAdmitted", 0) >= 1,
                "ioAdoptedCompared>=1": rec.get("ioAdoptedCompared", 0) >= 1,
                "zeroByteMismatch": rec.get("byteMismatch", 0) == 0,
                "zeroMaskMismatch": rec.get("maskMismatch", 0) == 0,
                "zeroLengthMismatch": rec.get("lengthMismatch", 0) == 0,
                "zeroQueueDrops": rec.get("queueDropped", 0) == 0,
                "gateNotDisqualified": rec.get("gateDisqualified") is not True,
                "noMismatchSeen": rec.get("mismatchSeen") is not True,
                "worldWasPersisted": (server_dir / "world" / "region").is_dir(),
            }
            phase_info["ioQualification"] = checks
            campaign["phases"][phase] = phase_info
            (args.output / "campaign-receipt.json").write_text(json.dumps(campaign, indent=2))
            if not all(checks.values()):
                print(json.dumps({"status": "FAIL", "phase": phase,
                                  "failedChecks": [k for k, v in checks.items() if not v]}))
                (args.output / "campaign-receipt.json").write_text(json.dumps(campaign, indent=2))
                return 1
            continue
        if phase == "te":
            # Join and HOLD; place the chest MID-HOLD while the target chunk is
            # loaded (vanilla setblock refuses positions whose chunk is absent —
            # the parse must come from the ACTUAL login line, not a default).
            hold = start_client(args.port, 45, False, server_dir, "te-join", None)
            spawn_pos, joined, queried, placed = None, False, False, False
            watch_from = 0
            place_deadline = time.time() + 120
            while hold.poll() is None and time.time() < place_deadline and not placed:
                time.sleep(2)
                try:
                    log_text = Path(log_path).read_text(encoding="utf-8", errors="replace")
                except OSError:
                    continue
                fresh = log_text[watch_from:]
                watch_from = len(log_text)
                if not joined:
                    if "logged in with entity" in fresh:
                        spawn_pos = _parse_spawn(log_path)
                        joined = True
                        time.sleep(8)  # let the spawn chunk burst settle
                        bx = int(spawn_pos["x"]) + 4
                        bz = int(spawn_pos["z"]) + 4
                        phase_info["setblockCoords"] = {"x": bx, "y": 4, "z": bz}
                        try:
                            process.stdin.write(("setblock %d 4 %d minecraft:chest\n"
                                                 % (bx, bz)).encode())
                            process.stdin.flush()
                        except (OSError, ValueError):
                            break
                    continue
                # joined: watch for the placement feedback
                if "Successfully changed" in fresh or "Changed the block" in fresh \
                        or "found the block" in fresh:
                    placed = True
                elif "outside of the world" in fresh or "Could not" in fresh or "Usage" in fresh:
                    phase_info["setblockFailure"] = [l for l in fresh.splitlines() if l.strip()][-1]
                    break
                elif not queried:
                    # no recognizable feedback yet: query the block directly
                    queried = True
                    try:
                        process.stdin.write(("testforblock %d 4 %d minecraft:chest\n"
                                             % (bx, bz)).encode())
                        process.stdin.flush()
                    except (OSError, ValueError):
                        break
            phase_info["chestPlaced"] = placed
            try:
                hold.wait(timeout=90)
            except subprocess.TimeoutExpired:
                hold.kill()
            if not placed or spawn_pos is None:
                phase_info["teQualification"] = {"chestPlaced": False}
                stop_server(process)
                campaign["phases"][phase] = phase_info
                (args.output / "campaign-receipt.json").write_text(json.dumps(campaign, indent=2))
                print(json.dumps({"status": "FAIL", "phase": phase, "reason": "chest not placed",
                                  "detail": phase_info.get("setblockFailure", "no feedback seen")}))
                return 1
            run_client(args.port, 60, True, server_dir, "te-away", spawn_pos)
            # walk BACK toward the chest: the rejoin spawns at the away endpoint
            # (persisted playerdata), so -X returns the chest chunk to view and
            # forces the disk reload whose packet must be TE-rejected.
            summary_back = run_client(args.port, 60, "walkback", server_dir, "te-back", spawn_pos)
            phase_info["clientWalkback"] = summary_back
            # give the consumer a moment to seal+compare the reload packet
            state = wait_for_compared(server_dir, 1, 60, server_dir)
            phase_info["finalCounters"] = state
            stop_server(process)
            receipt = server_dir / "live-shadow-receipt.json"
            if receipt.is_file():
                phase_info["receipt"] = json.loads(receipt.read_text(encoding="utf-8"))
            rec = phase_info.get("receipt", {})
            checks = {
                "teRejections>=1": rec.get("teRejections", 0) >= 1,
                "teEvidencePresent": bool(rec.get("lastTeRejection")),
                "zeroByteMismatch": rec.get("byteMismatch", 0) == 0,
                "zeroQueueDrops": rec.get("queueDropped", 0) == 0,
                "gateNotDisqualified": rec.get("gateDisqualified") is not True,
            }
            phase_info["teQualification"] = checks
            campaign["phases"][phase] = phase_info
            (args.output / "campaign-receipt.json").write_text(json.dumps(campaign, indent=2))
            if not all(checks.values()):
                print(json.dumps({"status": "FAIL", "phase": phase,
                                  "failedChecks": [k for k, v in checks.items() if not v]}))
                (args.output / "campaign-receipt.json").write_text(json.dumps(campaign, indent=2))
                return 1
            continue
        if phase == "dim":
            time.sleep(60)
            stop_server(process)
            exclusion = server_dir / "dimension-exclusion.json"
            evidence = None
            if exclusion.is_file():
                evidence = json.loads(exclusion.read_text(encoding="utf-8"))
            phase_info["dimensionExclusion"] = evidence
            receipt = server_dir / "live-shadow-receipt.json"
            if receipt.is_file():
                phase_info["receipt"] = json.loads(receipt.read_text(encoding="utf-8"))
            checks = {
                "evidenceFile": evidence is not None,
                "netherPresent": evidence is not None and evidence.get("dimension") == -1,
                "failedClosed": evidence is not None
                and "UNSUPPORTED_WORLD" in str(evidence.get("extraction", "")),
                "gateNotDisqualified": phase_info.get("receipt", {}).get("gateDisqualified") is not True,
            }
            phase_info["dimQualification"] = checks
            campaign["phases"][phase] = phase_info
            (args.output / "campaign-receipt.json").write_text(json.dumps(campaign, indent=2))
            if not all(checks.values()):
                print(json.dumps({"status": "FAIL", "phase": phase,
                                  "failedChecks": [k for k, v in checks.items() if not v]}))
                (args.output / "campaign-receipt.json").write_text(json.dumps(campaign, indent=2))
                return 1
            continue

        # -- original control/a/b flow --
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
