#!/usr/bin/env python3
"""FULL-STACK BENCHMARK driver (2026-10-09): one measured arm-run of the
Java-vs-RustCraft comparison. Boots ONE server (Arm A = clean
Java/Forge/Revelation with the measurement observer only; Arm B = the
current RustCraft full-stack composition), then runs three phases in-JVM:

  PHASE A  chunk streaming: fixed deterministic teleport route from the
           probe's verified login anchor (identical route both arms —
           the driver, not in-JVM tooling, owns the route)
  PHASE B  active-world mutations: the proven light_mutation_steps
           console workload (identical commands both arms; success is
           confirmed by command ECHO lines, not rust counters)
  PHASE C  save/unload: 3x save-all flush + graceful stop

Measurement (both arms, identical jar + cadence): the observer tweaker's
tick-identity MSPT sampler + CPU/GC/heap MXBeans -> observer-metrics.jsonl,
time-joined by this driver's phases.json. Arm B additionally records its
authority witnesses from region-metrics.txt (§6).

Usage:
  python run_fullstack_ab.py --arm java --port 25610 --output <dir>
  python run_fullstack_ab.py --arm rust --port 25611 --output <dir>
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import re
import shutil
import subprocess
import sys
import threading
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools" / "live-shadow-v2"))
sys.path.insert(0, str(ROOT / "tools" / "authority-review"))
sys.path.insert(0, str(ROOT / "tools"))

from run_join_probe import JAVA, wait_for, tree_hash  # noqa: E402
from join_probe import run_probe  # noqa: E402
from run_region_write_campaign import light_mutation_steps  # noqa: E402

RT = ROOT / "target" / "authority-smoke" / "runtimeC"
FORGE_JAR = "forge-1.12.2-14.23.5.2846-universal.jar"
VANILLA_JAR = "minecraft_server.1.12.2.jar"
SRG_JAR = Path(r"D:\minecraftrust\third_party_reference\minecraft"
               r"\minecraft_server.1.12.2.srg.jar")
OBSERVER_TWEAKER = "com.rustcraft.observer.MeasurementObserverTweaker"
FML_TWEAKER = "net.minecraftforge.fml.common.launcher.FMLServerTweaker"
RUSTCRAFT_TWEAKER = "com.rustcraft.coremod.LiveSessionAdmissionTweaker"

# deterministic route (§5: same recorded positions every run): rings of 8
# cardinal points at radii 48/96/144 blocks and back (48/96), from the
# verified login anchor — 48 waypoints, ~1.6s dwell
ROUTE_RADII = (48, 96, 144, 96, 48)
ROUTE_POINTS = 8
ROUTE_DWELL_S = 1.6
# set by --profile-jfr (module-level so run_arm sees it)
PROFILE_JFR = False
OBSERVER_PROPS = None
MINIMAL_AUTHORITIES = False
# OPT-FS-002 §9 diagnostic ablation: OFF = vanilla region writes (labeled
# attribution run — NOT an equivalent-configuration performance result)
REGION_WRITE_MODE = "ON_EXPERIMENTAL"
LIGHT_MODE = "ON_EXPERIMENTAL"
JVM_PROPS_EXTRA = []


def sha16(p: Path) -> str:
    return hashlib.sha256(p.read_bytes()).hexdigest()[:16]


def now_ms() -> int:
    return int(time.time() * 1000)


class Phases:
    def __init__(self, path: Path):
        self.path = path
        self.events = []

    def mark(self, phase: str, boundary: str, **extra):
        ev = {"phase": phase, "boundary": boundary, "t": now_ms()}
        ev.update(extra)
        self.events.append(ev)
        self.path.write_text(json.dumps(self.events, indent=1) + "\n",
                             encoding="utf-8")

    def note(self, **kv):
        self.events.append({"t": now_ms(), **kv})
        self.path.write_text(json.dumps(self.events, indent=1) + "\n",
                             encoding="utf-8")


def stage_server(out_dir: Path, arm: str, port: int, reuse_world_from=None):
    server = out_dir / "server"
    if server.exists():
        shutil.rmtree(server)
    server.mkdir(parents=True)
    copied = 0
    for item in ("libraries", "mods", "config"):
        src = RT / item
        if src.is_dir():
            shutil.copytree(src, server / item)
            copied += sum(1 for _ in src.rglob("*") if _.is_file())
    shutil.copyfile(RT / FORGE_JAR, server / FORGE_JAR)
    shutil.copyfile(RT / VANILLA_JAR, server / VANILLA_JAR)
    observer_jar = ROOT / "target" / "rustcraft-observer.jar"
    shutil.copyfile(observer_jar, server / "rustcraft-observer.jar")
    campaign_jar = None
    if arm == "rust":
        campaign_jar = ROOT / "target" / "rustcraft-campaign-C.jar"
        shutil.copyfile(campaign_jar, server / "rustcraft-campaign.jar")
        shutil.copyfile(ROOT / "target" / "release" / "rustcraft_ffi.dll",
                        server / "rustcraft_ffi.dll")
    (server / "eula.txt").write_text("eula=true\n")
    (server / "server.properties").write_text("\n".join([
        "online-mode=false", "max-tick-time=-1", "view-distance=6",
        "spawn-protection=0", "snooper-enabled=false", "enable-rcon=false",
        "level-seed=", "motd=rustcraft-fullstack-ab",
        f"server-port={port}", "allow-flight=true",
    ]) + "\n")
    world_hash = None
    world_src = reuse_world_from or (RT / "world")
    if world_src.is_dir():
        shutil.copytree(world_src, server / "world")
        world_hash = tree_hash(server / "world")
    return server, {"files_copied": copied, "world_hash": world_hash}


def build_argv(server: Path, out_dir: Path, arm: str, port: int,
               profile_jfr: Path = None, observer_props=None):
    cp = [str(server / "rustcraft-observer.jar"),
          str(server / FORGE_JAR), str(server / VANILLA_JAR)]
    cp += [str(p) for p in sorted((server / "libraries").rglob("*.jar"))]
    argv = [str(JAVA), "-Xmx6G"]
    if profile_jfr is not None:
        # OPT-FS-001 §5: diagnostic allocation/CPU profile (NOT a
        # performance-result run — overhead reported separately)
        argv += ["-XX:+FlightRecorder",
                 "-XX:StartFlightRecording=settings=profile,"
                 f"filename={profile_jfr},dumponexit=true"]
    tweakers = []
    if arm == "rust":
        argv += ["-javaagent:" + str(server / "rustcraft-campaign.jar")]
        if MINIMAL_AUTHORITIES:
            argv += ["-Dfml.queryResult=confirm",
                     "-Drustcraft.liveWriterDiagnostic=true",
                     f"-Drustcraft.session.processId=m1-{port}",
                     f"-Drustcraft.session.transformationSessionId=m1-{port}",
                     "-Drustcraft.srgJar=" + str(SRG_JAR),
                     "-Drustcraft.observationDir=" + str(server / "observation"),
                     "-Drustcraft.liveShadowScope=OVERWORLD_PER_CHUNK",
                     "-Drustcraft.liveShadowDll=" + str(server / "rustcraft_ffi.dll"),
                     "-Drustcraft.liveShadowJournal=" + str(out_dir / "shadow-journal.jsonl"),
                     "-Drustcraft.profile=FORGE_2846_FTB_REVELATION_3_4_0_SERVER_"
                     "TRANSFORMED_OFFLINE_V1",
                "-Drustcraft.packetAuthorityReceiptOut="
                + str(out_dir / "packet-authority-receipt.json")]
        else:
            argv += [
                "-Dfml.queryResult=confirm",
                "-Drustcraft.liveWriterDiagnostic=true",
                f"-Drustcraft.session.processId=fullstack-{arm}-{port}",
                f"-Drustcraft.session.transformationSessionId=fs-{arm}-{port}",
                "-Drustcraft.srgJar=" + str(SRG_JAR),
                "-Drustcraft.observationDir=" + str(server / "observation"),
                "-Drustcraft.liveShadowScope=OVERWORLD_PER_CHUNK",
                "-Drustcraft.liveShadowDll=" + str(server / "rustcraft_ffi.dll"),
                "-Drustcraft.liveShadowJournal=" + str(out_dir / "shadow-journal.jsonl"),
                "-Drustcraft.packetAuthorityReceiptOut="
                + str(out_dir / "packet-authority-receipt.json"),
                "-Drustcraft.profile=FORGE_2846_FTB_REVELATION_3_4_0_SERVER_"
                "TRANSFORMED_OFFLINE_V1",
                # audit-baseline full-stack composition (packet/compression
                # authorities deliberately OFF — labeled subset)
                "-Drustcraft.regionWriteExperiment=true",
                "-Drustcraft.regionWriteMode=" + REGION_WRITE_MODE,
                "-Drustcraft.regionWriteMirror=" + str(out_dir / "region-mirror"),
                "-Drustcraft.regionReadExperiment=true",
                "-Drustcraft.regionReadMode=ON_EXPERIMENTAL",
                "-Drustcraft.regionMetricsFile=" + str(out_dir / "region-metrics.txt"),
                "-Drustcraft.lightExperiment=true",
                "-Drustcraft.lightMode=" + LIGHT_MODE,
                "-Drustcraft.lightCompareFlag=" + str(out_dir / "light-compare.flag"),
                "-Drustcraft.worldRegistry=true",
                "-Dminecraftrust.m4.coherency=true",
                "-Drustcraft.chunkStateAuthorityExperiment=true",
                "-Drustcraft.chunkStateAuthorityCap=2000",
                "-DRUSTCRAFT_ZS_DEBUG=1",
            ]
        tweakers.append(RUSTCRAFT_TWEAKER)
    else:
        argv += ["-Dfml.queryResult=confirm"]
        tweakers.append(FML_TWEAKER)
    tweakers.append(OBSERVER_TWEAKER)
    # user -D props go LAST: JVM -D is last-wins, and these must be able
    # to override any mode property the main block set (the M2 oracle boot's
    # regionReadMode=SHADOW silently lost to ON_EXPERIMENTAL before this)
    for jp in (observer_props or []):
        argv.append("-D" + jp)
    argv += ["-cp", os.pathsep.join(cp), "net.minecraft.launchwrapper.Launch"]
    for t in tweakers:
        argv += ["--tweakClass", t]
    argv += ["--gameDir", str(server)]
    return argv


def send(process, line: str):
    try:
        process.stdin.write((line + "\n").encode())
        process.stdin.flush()
    except (OSError, ValueError):
        pass


def log_size(path: Path) -> int:
    try:
        return path.stat().st_size
    except OSError:
        return 0


def count_in_new(path: Path, start_off: int, pattern) -> int:
    n = 0
    with path.open("rb") as f:
        f.seek(start_off)
        for line in f:
            if re.search(pattern, line):
                n += 1
    return n


def wait_for_new(path: Path, start_off: int, pattern, timeout_s,
                 process=None) -> bool:
    """offset-scoped wait: only text appended AFTER start_off counts (the
    plain wait_for re-matches OLD lines — the 3x save loop matched the
    first flush's line instantly for flushes 2 and 3)."""
    import time as _t
    deadline = _t.time() + timeout_s
    while _t.time() < deadline:
        if process is not None and process.poll() is not None:
            return False
        if count_in_new(path, start_off, pattern) > 0:
            return True
        _t.sleep(0.5)
    return False


def parse_anchor(log: Path, username: str):
    txt = log.read_text(encoding="utf-8", errors="replace")
    mpos = None
    for mpos in re.finditer(
            rf"{re.escape(username)}.*logged in with entity id \d+ at "
            r"\(([-0-9.]+), ([-0-9.]+), ([-0-9.]+)\)", txt):
        pass
    if mpos is None:
        return None
    return (int(float(mpos.group(1))), int(float(mpos.group(2))),
            int(float(mpos.group(3))))


def route_waypoints(anchor):
    x0, y0, z0 = anchor
    pts = []
    for r in ROUTE_RADII:
        for k in range(ROUTE_POINTS):
            ang = 2 * math.pi * k / ROUTE_POINTS
            pts.append((x0 + int(r * math.cos(ang)),
                        y0 + 8, z0 + int(r * math.sin(ang))))
    pts.append((x0, y0, z0))
    return pts


def run_arm(arm: str, port: int, out_dir: Path, username: str,
            reuse_world_from=None) -> int:
    # the JVM runs with cwd=server: every path handed to it (-cp,
    # --gameDir, -D file props) must be absolute
    out_dir = out_dir.resolve()
    out_dir.mkdir(parents=True, exist_ok=True)
    (out_dir / "region-mirror").mkdir(exist_ok=True)
    server, stage_info = stage_server(out_dir, arm, port,
                                       reuse_world_from=reuse_world_from)
    jfr_file = out_dir / "server-profile.jfr"
    argv = build_argv(server, out_dir, arm, port,
                      profile_jfr=jfr_file if PROFILE_JFR else None,
                      observer_props=OBSERVER_PROPS)
    (out_dir / "launch.json").write_text(json.dumps(
        {"argv": argv, "arm": arm}, indent=2) + "\n", encoding="utf-8")
    (out_dir / "runner-argv.json").write_text(json.dumps(
        {"runner_argv": sys.argv, "cwd": os.getcwd(),
         "started_at": time.strftime("%Y-%m-%dT%H:%M:%S")}, indent=2)
        + "\n", encoding="utf-8")
    artifacts = {
        "observer_jar_sha16": sha16(ROOT / "target" / "rustcraft-observer.jar"),
        "world_hash_n": stage_info["world_hash"],
        "campaign_jar_sha16": sha16(ROOT / "target" / "rustcraft-campaign-C.jar")
        if arm == "rust" else None,
        "dll_sha16": sha16(ROOT / "target" / "release" / "rustcraft_ffi.dll")
        if arm == "rust" else None,
    }

    jvm_log = out_dir / "server.log"
    phases = Phases(out_dir / "phases.json")
    phases.mark("launch", "start", arm=arm)
    log_handle = jvm_log.open("wb")
    t_boot0 = time.monotonic()
    process = subprocess.Popen(argv, cwd=str(server), stdout=log_handle,
                               stderr=subprocess.STDOUT, stdin=subprocess.PIPE)
    receipt = {"arm": arm, "port": port, "username": username,
               "artifacts": artifacts, "verdict": "FAIL"}
    try:
        if not wait_for(jvm_log, r"Done \([0-9.]+s\)! For help",
                        1800.0, process=process):
            receipt["failure"] = "boot timeout"
            return finish(process, log_handle, out_dir, receipt, phases)
        phases.mark("boot", "end", boot_s=round(time.monotonic() - t_boot0, 1))
        # FML keeps rejecting players for a moment after Done ("Server is
        # still starting") — settle before the probe connects (campaign
        # precedent: post-Done settle)
        time.sleep(10.0)

        # --- probe join (external fake client; no RustCraft dependency) ---
        # stability_s is the CEILING the probe may stay: it must hold the
        # connection for the WHOLE run (player-driven chunk streaming +
        # player-loaded mutation chunks); it returns its receipt when the
        # server stops (disconnect) — collected in finish() after the join.
        # client mod inventory: the FML handshake must present the modpack
        # (same derivation as the campaign runner: log rejection list,
        # else the recorded versions JSON)
        client_mods = [("minecraft", "1.12.2"), ("FML", "8.0.99.99"),
                       ("forge", "14.23.5.2846"), ("mcp", "9.42")]
        mod_versions = ROOT / "target" / "revelation-mod-versions.json"
        if mod_versions.is_file():
            log_text_now = jvm_log.read_text(encoding="utf-8",
                                             errors="replace")
            inv = re.search(r"missing mods \[([^\]]+)\]", log_text_now)
            doc = json.loads(mod_versions.read_text(encoding="utf-8"))
            derived = doc.get("versions", doc)
            if inv is not None:
                client_mods = [(m.strip(), derived.get(m.strip(), "1.0"))
                               for m in inv.group(1).split(",")]
            else:
                client_mods = [(k, v) for k, v in derived.items()]
        probe_holder = [{}]
        def probe_worker():
            try:
                probe_holder[0] = run_probe(
                    "127.0.0.1", port, username, expect_forge=True,
                    client_mods=client_mods, connect_timeout_s=20.0,
                    login_timeout_s=240.0, stability_s=900.0)
            except (OSError, ValueError) as e:
                probe_holder[0] = {"verdict": "FAIL", "failure": str(e)}
        phases.mark("probe", "start")
        pt = threading.Thread(target=probe_worker, daemon=True)
        pt.start()
        if not wait_for(jvm_log,
                        rf"{re.escape(username)}.*(logged in|joined the game)",
                        240.0, process=process):
            receipt["failure"] = "probe never joined"
            return finish(process, log_handle, out_dir, receipt, phases, pt,
                          probe_holder)
        time.sleep(3.0)
        phases.mark("probe", "end")
        anchor = parse_anchor(jvm_log, username) or (10, 100, 10)
        phases.note(anchor=anchor)

        # --- PHASE A: deterministic route ---
        phases.mark("phase_a_streaming", "start", waypoints=len(route_waypoints(anchor)))
        t_a0 = time.monotonic()
        for (x, y, z) in route_waypoints(anchor):
            send(process, f"tp {username} {x} {y} {z}")
            time.sleep(ROUTE_DWELL_S)
        phases.mark("phase_a_streaming", "end",
                    duration_s=round(time.monotonic() - t_a0, 1))
        time.sleep(2.0)

        # --- PHASE B: mutations (identical commands both arms) ---
        # §5 confirmation with a POSITIVE channel: console command success
        # AND failure feedback are silent for setblock/testforblock (proven
        # in the smokes), but /say always logs. One armor stand is summoned
        # at the anchor; each step's per-cell FINAL intended state (last
        # command per distinct cell) is verified with
        #   execute @e[type=armor_stand] A detect <cell> <block> -1 say CONFIRM...
        # — the detect form runs the say ONLY when the block matches, so a
        # CONFIRM line is positive evidence of the intended transition, in
        # both arms, with identical commands.
        phases.mark("phase_b_mutations", "start")
        ax, ay, az = anchor
        send(process, f"summon armor_stand {ax} {ay + 10} {az}")
        time.sleep(1.0)
        t_b0 = time.monotonic()
        commands = 0
        step_recd = []
        for name, cmds, require_delta in light_mutation_steps(anchor, salt=0):
            final_state = {}
            for c in cmds:
                m = re.match(r"setblock (-?\d+) (-?\d+) (-?\d+) (\S+)", c)
                final_state[(m.group(1), m.group(2), m.group(3))] = m.group(4)
            off = log_size(jvm_log)
            for c in cmds:
                send(process, c)
                commands += 1
            time.sleep(1.0)
            for (x, y, z), block in sorted(final_state.items()):
                send(process,
                     f"execute @e[type=armor_stand] {ax} {ay} {az} "
                     f"detect {x} {y} {z} {block} -1 "
                     f"say CONFIRM {name} {x} {y} {z}")
            time.sleep(1.0)
            # the say sender is the armor stand: "[Armor Stand] CONFIRM ..."
            # Retro 2026-10-09: confirmations can land late against busy
            # ticks (fs1r/fs-prof1 recorded 42/67 — partial-work footnotes
            # for completed work); retry the UNCONFIRMED cells once with a
            # longer wait before recording, bounded and identical in both
            # arms
            def confirmed_cells_from(off):
                got = set()
                with jvm_log.open("rb") as f:
                    f.seek(off)
                    for line in f:
                        m = re.search(
                            rb"CONFIRM \S+ (-?\d+) (-?\d+) (-?\d+)", line)
                        if m:
                            got.add((m.group(1).decode(),
                                     m.group(2).decode(),
                                     m.group(3).decode()))
                return got
            off_confirm = off
            confirmed = confirmed_cells_from(off_confirm)
            if len(confirmed) < len(final_state):
                missing = [c for c in final_state if c not in confirmed]
                for (x, y, z) in missing:
                    send(process,
                         f"execute @e[type=armor_stand] {ax} {ay} {az} "
                         f"detect {x} {y} {z} {final_state[(x, y, z)]} -1 "
                         f"say CONFIRM {name} {x} {y} {z}")
                time.sleep(2.5)
                confirmed = confirmed_cells_from(off_confirm)
            confirmed = len(confirmed)
            step_recd.append({"step": name, "cmds": len(cmds),
                              "confirmed": confirmed,
                              "expected_cells": len(final_state),
                              "require_delta": require_delta})
            phases.note(step=name, confirmed=confirmed,
                        expected=len(final_state))
        # M3-A scheduled-tick scenario: water flow + falling sand both go
        # through WorldServer.updateBlockTick (func_175654_a) — the seam
        # the tick authority owns. Deterministic staging: a landing pad
        # one below the sand and a cleared cell below the water, so the
        # resting/flow positions are known regardless of prior steps.
        m3a_off = log_size(jvm_log)
        for (x, y, z, blk) in (
            (ax - 2, ay + 2, az - 2, "stone"),    # sand lands here (ay+3)
            (ax + 2, ay + 2, az + 2, "air"),      # water flows down here
        ):
            send(process, f"setblock {x} {y} {z} minecraft:{blk}")
            commands += 1
        time.sleep(0.5)
        for (x, y, z, blk) in (
            (ax + 2, ay + 3, az + 2, "flowing_water"),  # DYNAMIC liquid: a
            # setblock'ed static water source never self-flows in 1.12
            # (verified on the shadow4 saved world: id 9 inert above air,
            # vanilla executing); buckets place flowing_water
            (ax - 2, ay + 4, az - 2, "sand"),    # falls via tick
        ):
            send(process, f"setblock {x} {y} {z} minecraft:{blk}")
            commands += 1
        time.sleep(4.0)
        # water flow evidence: 1.12 saves all water as block id 9 with
        # LEVEL meta (0=source, 8=falling) — "flowing_water" (id 8) never
        # appears in state; the below cell after a real scheduled-tick flow
        # reads water meta 8 (verified on the shadow6 saved world)
        send(process,
             f"execute @e[type=armor_stand] {ax} {ay} {az} "
             f"detect {ax + 2} {ay + 2} {az + 2} minecraft:water 8 "
             f"say CONFIRM m3a_water_flow")
        send(process,
             f"execute @e[type=armor_stand] {ax} {ay} {az} "
             f"detect {ax - 2} {ay + 3} {az - 2} minecraft:sand -1 "
             f"say CONFIRM m3a_sand_resting")
        send(process,
             f"execute @e[type=armor_stand] {ax} {ay} {az} "
             f"detect {ax - 2} {ay + 4} {az - 2} minecraft:sand -1 "
             f"say CONFIRM m3a_sand_pending")
        time.sleep(2.0)
        m3a_sand = count_in_new(jvm_log, m3a_off, rb"CONFIRM ")
        step_recd.append({"step": "m3a_scheduled_ticks", "cmds": 4,
                          "confirmed": m3a_sand, "expected_cells": 2,
                          "require_delta": False})
        phases.note(step="m3a_scheduled_ticks", confirmed=m3a_sand)
        send(process, "kill @e[type=armor_stand]")
        total_confirmed = sum(s["confirmed"] for s in step_recd)
        total_expected = sum(s["expected_cells"] for s in step_recd)
        phases.mark("phase_b_mutations", "end",
                    duration_s=round(time.monotonic() - t_b0, 1),
                    commands=commands, confirmed=total_confirmed,
                    expected=total_expected)
        receipt["mutation_steps"] = step_recd
        receipt["confirmed_cells"] = total_confirmed
        receipt["expected_cells"] = total_expected
        time.sleep(2.0)

        # --- PHASE C: saves ---
        phases.mark("phase_c_save", "start")
        t_c0 = time.monotonic()
        for i in range(3):
            off = log_size(jvm_log)
            send(process, "save-all flush")
            wait_for_new(jvm_log, off, rb"Saved the world", 120.0,
                         process=process)
            phases.note(save_flush=i + 1)
        phases.mark("phase_c_save", "end",
                    duration_s=round(time.monotonic() - t_c0, 1))

        # probe evidence is computed in finish() AFTER the probe thread
        # joins (the thread is still holding mid-run; its observed fields
        # populate only at disconnect)

        receipt["commands"] = commands
        receipt["verdict"] = "MEASURED"
        return finish(process, log_handle, out_dir, receipt, phases, pt,
                      probe_holder)
    finally:
        try:
            if process.poll() is None:
                send(process, "stop")
                try:
                    process.wait(timeout=60)
                except subprocess.TimeoutExpired:
                    process.kill()
        finally:
            log_handle.close()


def finish(process, log_handle, out_dir, receipt, phases, probe_thread=None,
           probe_holder=None):
    phases.mark("run", "end")
    send(process, "stop")
    try:
        rc = process.wait(timeout=120)
    except subprocess.TimeoutExpired:
        process.kill()
        rc = process.poll()
    # the probe returns its receipt when the server stops (disconnect);
    # chunk-packet counts land in the run receipt, not before
    if probe_thread is not None:
        probe_thread.join(timeout=30)
    if probe_holder is not None:
        receipt["probe"] = probe_holder[0]
        # the long-hold probe treats the benchmark's own server stop as
        # "disconnect during stability" (join_probe design) — the RUN
        # verdict uses the join evidence fields, not the probe verdict
        obs = (probe_holder[0].get("observed") or {})
        receipt["probe_evidence"] = {
            "login_completed": bool(obs.get("login_completed")),
            "play_reached": bool(obs.get("play_reached")),
            "keepalive_exchanged": bool(obs.get("keepalive_exchanged")),
            "chunk_packets": obs.get("chunk_packets", 0),
            "note": "probe verdict FAIL on disconnect-during-hold is the "
                    "expected artifact of holding until the benchmark's "
                    "server stop; join evidence fields are authoritative "
                    "here",
        }
    phases.mark("process", "exit")
    try:
        process.stdin.close()
    except (OSError, ValueError, AttributeError):
        pass
    log_handle.close()
    receipt["exit_code"] = rc
    (out_dir / "fullstack-run.json").write_text(
        json.dumps(receipt, indent=1, sort_keys=True) + "\n", encoding="utf-8")
    print(f"[fullstack-{receipt['arm']}] verdict={receipt['verdict']} "
          f"exit={rc} -> {out_dir / 'fullstack-run.json'}")
    return 0 if receipt["verdict"] == "MEASURED" else 1


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--arm", choices=["java", "rust"], required=True)
    ap.add_argument("--port", type=int, required=True)
    ap.add_argument("--output", type=Path, required=True)
    ap.add_argument("--username", default=None)
    ap.add_argument("--reuse-world-from", type=Path, default=None,
                    help="M2 lifecycle: stage the world from a previous "
                         "run's server/world instead of the pristine "
                         "template — enables fresh-process reload on the "
                         "exact saved state")
    ap.add_argument("--m3-ticks", choices=["SHADOW", "ON"], default=None,
                    help="M3-A: scheduled-tick scheduler authority mode "
                         "(rustcraft.tickAuthorityMode)")
    ap.add_argument("--minimal-authorities", action="store_true",
                    help="Drop ALL authority properties (light/regionRW/"
                         "regionRead/worldRegistry/chunkState) — the "
                         "qualified-capture shape: the writer pre-hook "
                         "pins were derived from a discovery chain with "
                         "NO rustcraft transformers, so any authority "
                         "transformer registered before the writers "
                         "changes the bytes and fails identity by design")
    ap.add_argument("--m1-packets", action="store_true",
                    help="M1 (rewrite ladder): bounded Rust packet authority "
                         "+ True Direct Netty emission (directNettyExperiment "
                         "+ packetAuthorityExperiment, cap 2000, closure "
                         "receipt verified). Requires the capture session "
                         "(admission) — keep it ON in both comparison arms")
    ap.add_argument("--no-capture-session", action="store_true",
                    help="OPT-FS-003 isolation experiment: skip the "
                         "capture/observation session (rustcraft."
                         "noCaptureSession=true) while all authorities "
                         "keep running — labeled ablation, not a valid "
                         "full-stack performance config")
    ap.add_argument("--ablate-light", action="store_true",
                    help="OPT-FS-002/004 diagnostic: sets lightMode=SHADOW "
                         "— tests OWNERSHIP ONLY (Java owns light) while "
                         "the shadow comparator keeps shadow-computing "
                         "every job (~2k jobs/30k cells per run); NOT a "
                         "light-machinery-off switch (no such flag exists; "
                         "omitting lightExperiment entirely would also skip "
                         "light-transformer registration)")
    ap.add_argument("--ablate-region-write", action="store_true",
                    help="OPT-FS-002 diagnostic: vanilla region writes "
                         "(attribution ablation, labeled)")
    ap.add_argument("--jvm-prop", action="append", default=None,
                    help="Extra -D for the server JVM (repeatable; same "
                         "flag name as run_region_write_campaign)")
    ap.add_argument("--observer-prop", action="append", default=None,
                    help="Alias of --jvm-prop (kept for the OPT-FS-001 "
                         "invocations; e.g. "
                         "rustcraft.observer.allocSampler=true)")
    ap.add_argument("--profile-jfr", action="store_true",
                    help="OPT-FS-001 diagnostic: JFR profile recording "
                         "(allocation+CPU events; overhead reported; NOT "
                         "a performance-result run)")
    args = ap.parse_args()
    global PROFILE_JFR, OBSERVER_PROPS, REGION_WRITE_MODE
    global JVM_PROPS_EXTRA, LIGHT_MODE, MINIMAL_AUTHORITIES
    MINIMAL_AUTHORITIES = args.minimal_authorities
    if args.ablate_region_write:
        REGION_WRITE_MODE = "OFF"
    if args.ablate_light:
        LIGHT_MODE = "SHADOW"
    if args.no_capture_session:
        JVM_PROPS_EXTRA.append("rustcraft.noCaptureSession=true")
    if args.m3_ticks:
        JVM_PROPS_EXTRA.append("rustcraft.tickAuthorityMode=" + args.m3_ticks)
    if args.m1_packets:
        JVM_PROPS_EXTRA += [
            "rustcraft.packetAuthorityExperiment=true",
            "rustcraft.packetAuthorityCap=2000",
            "rustcraft.directNettyExperiment=true",
            "rustcraft.packetAuthorityReceipt="
            "C:/rustcraft/target/authority-review/closure-input-receipt.json",
        ]
    PROFILE_JFR = args.profile_jfr
    OBSERVER_PROPS = list(args.observer_prop or []) +         list(args.jvm_prop or []) + JVM_PROPS_EXTRA
    username = args.username or f"FS{args.arm.upper()[:2]}"
    return run_arm(args.arm, args.port, args.output, username,
                   reuse_world_from=args.reuse_world_from)


if __name__ == "__main__":
    sys.exit(main())
