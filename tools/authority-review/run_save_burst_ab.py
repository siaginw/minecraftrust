#!/usr/bin/env python3
"""Goal §27: live save-burst A/B — Java-only vs bounded Rust region authority.

Two back-to-back Gate C sessions with IDENTICAL workloads (probe join, N
teleport legs via the in-server campaign controller, K `save-all flush`
bursts, graceful stop). The only difference: -Drustcraft.regionWriteMode
(ON_EXPERIMENTAL vs absent = Java-only).

Measured per session:
  - per-flush save-completion latency (stdin command -> "Saved the world")
  - total save time across bursts
  - process CPU time delta across the save windows (psutil, server-wide
    proxy; region writes run on the server thread in BOTH arms)
  - GC totals from the JDK8 GC log (-XX:+PrintGCDateStamps ... gc.log parse)
  - disk bytes: world/region directory growth across the session

Prints SAVE_BURST_AB java=... rust=... summary lines; exit 0 always (the
numbers are the deliverable, not a pass/fail gate) unless a session fails
to boot.
"""
from __future__ import annotations

import json
import os
import re
import shutil
import subprocess
import sys
import time
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools" / "live-shadow-v2"))
sys.path.insert(0, str(ROOT / "tools" / "authority-review"))

from run_join_probe import JAVA, prepare_server, stop, wait_for  # noqa: E402
from join_probe import run_probe  # noqa: E402

SRG_JAR = Path(r"D:\minecraftrust\third_party_reference\minecraft\minecraft_server.1.12.2.srg.jar")
RT = ROOT / "target" / "authority-smoke" / "runtimeC"
FORGE_JAR = "forge-1.12.2-14.23.5.2846-universal.jar"
VANILLA_JAR = "minecraft_server.1.12.2.jar"
PROFILE_ID = "FORGE_2846_FTB_REVELATION_3_4_0_SERVER_TRANSFORMED_OFFLINE_V1"


def run_session(tag: str, out_dir: Path, port: int, rust_on: bool,
                legs: int, flushes: int) -> dict | None:
    campaign_jar = ROOT / "target" / "rustcraft-campaign-C.jar"
    dll = ROOT / "target" / "release" / "rustcraft_ffi.dll"
    session = {"process_id": str(uuid.uuid4()), "session_id": str(uuid.uuid4())}
    server_dir, _ = prepare_server(RT, out_dir, forge_jar=FORGE_JAR,
                                   vanilla_jar=VANILLA_JAR,
                                   campaign_jar=campaign_jar,
                                   world_source=RT / "world")
    shutil.copyfile(dll, server_dir / "rustcraft_ffi.dll")
    mirror = out_dir / "mirror"
    mirror.mkdir()
    props = (server_dir / "server.properties").read_text(encoding="utf-8")
    (server_dir / "server.properties").write_text(
        props.replace("server-port=25599", f"server-port={port}") + "\nlevel-type=DEFAULT\n",
        encoding="utf-8")

    extra = [
        "-Dfml.queryResult=confirm",
        "-Drustcraft.liveWriterDiagnostic=true",
        "-Drustcraft.session.processId=" + session["process_id"],
        "-Drustcraft.session.transformationSessionId=" + session["session_id"],
        "-Drustcraft.srgJar=" + str(SRG_JAR),
        "-Drustcraft.observationDir=" + str(server_dir / "observation"),
        "-Drustcraft.liveShadowScope=OVERWORLD_PER_CHUNK",
        "-Drustcraft.liveShadowDll=" + str(server_dir / "rustcraft_ffi.dll"),
        "-Drustcraft.liveShadowJournal=" + str(out_dir / "shadow-journal.jsonl"),
        "-Drustcraft.profile=" + PROFILE_ID,
        "-Drustcraft.closureCampaignTeleport=true",
        f"-Drustcraft.closureCampaignTeleportRounds={max(1, legs // 16)}",
        "-XX:+PrintGCDetails", "-XX:+PrintGCDateStamps",
        "-Xloggc:" + str(out_dir / "gc.log"),
    ]
    if rust_on:
        extra += ["-Drustcraft.regionWriteExperiment=true",
                  "-Drustcraft.regionWriteMode=ON_EXPERIMENTAL",
                  "-Drustcraft.regionWriteMirror=" + str(mirror)]

    classpath = [str(server_dir / "rustcraft-campaign.jar"),
                 str(server_dir / FORGE_JAR), str(server_dir / VANILLA_JAR)]
    classpath += [str(p) for p in sorted((server_dir / "libraries").rglob("*.jar"))]
    argv = [str(JAVA), "-Xmx6G",
            "-javaagent:" + str(server_dir / "rustcraft-campaign.jar"),
            ] + extra + [
        "-cp", os.pathsep.join(classpath),
        "net.minecraft.launchwrapper.Launch",
        "--tweakClass", "com.rustcraft.coremod.LiveSessionAdmissionTweaker",
        "--gameDir", str(server_dir),
    ]
    log = out_dir / "server.log"
    handle = log.open("wb")
    region_dir = server_dir / "world" / "region"
    proc = subprocess.Popen(argv, cwd=str(server_dir), stdout=handle,
                            stderr=subprocess.STDOUT, stdin=subprocess.PIPE)
    result: dict = {"tag": tag, "rust_on": rust_on, "port": port}
    try:
        if not wait_for(log, r"Done \([0-9.]+s\)", 1800, process=proc):
            print(f"[{tag}] boot failed", file=sys.stderr)
            return None
        time.sleep(20.0)
        # derive the full client mod inventory from the server's own log —
        # Revelation rejects FML joins that do not present all 219 mods
        log_text_early = log.read_text(encoding="utf-8", errors="replace")
        client_mods = [("minecraft", "1.12.2"), ("FML", "8.0.99.99"),
                       ("forge", "14.23.5.2846"), ("mcp", "9.42")]
        mod_versions = ROOT / "target" / "revelation-mod-versions.json"
        inv = re.search(r"missing mods \[([^\]]+)\]", log_text_early)
        if inv is not None and mod_versions.is_file():
            doc = json.loads(mod_versions.read_text(encoding="utf-8"))
            derived = doc.get("versions", doc)
            builtins = {"minecraft": "1.12.2", "FML": "8.0.99.99",
                        "forge": "14.23.5.2846", "mcp": "9.42"}
            client_mods = [(m.strip(), derived.get(m.strip(),
                            builtins.get(m.strip(), "1.0")))
                           for m in inv.group(1).split(",")]
        receipt = run_probe("127.0.0.1", port, f"SB{'R' if rust_on else 'J'}",
                            expect_forge=True, client_mods=client_mods,
                            connect_timeout_s=30.0, login_timeout_s=180.0,
                            stability_s=60.0 + legs * 8.0)
        result["probe_verdict"] = receipt.get("verdict")
        result["chunk_packets"] = receipt.get("observed", {}).get("chunk_packets", 0)

        region_before = sum(f.stat().st_size for f in region_dir.glob("*.mca")) \
            if region_dir.is_dir() else 0
        # save bursts: measure each flush round-trip
        flush_ms = []
        cpu_before = _process_cpu(proc.pid)
        for i in range(max(1, flushes)):
            t0 = time.time()
            proc.stdin.write(b"save-all flush\n")
            proc.stdin.flush()
            ok = wait_for(log, r"Saved the world", 300)
            flush_ms.append(round((time.time() - t0) * 1000, 1))
            if not ok:
                break
            time.sleep(3.0)
        cpu_after = _process_cpu(proc.pid)
        result["flush_ms"] = flush_ms
        result["save_total_ms"] = round(sum(flush_ms), 1)
        result["cpu_s_delta"] = round((cpu_after - cpu_before), 2) \
            if cpu_before and cpu_after else None
        region_after = sum(f.stat().st_size for f in region_dir.glob("*.mca")) \
            if region_dir.is_dir() else 0
        result["disk_bytes_written"] = max(0, region_after - region_before)
    finally:
        stop(proc, timeout_s=300)
        handle.close()

    text = log.read_text(encoding="utf-8", errors="replace")
    m = re.search(r"regionWrite\.hook enabled=(\S+) mode=(\S+).*", text)
    if m:
        result["hook"] = m.group(0)[:400]
    result["gc"] = _parse_gc(out_dir / "gc.log")
    keep_up = len(re.findall(r"Can't keep up", text))
    result["cant_keep_up"] = keep_up
    (out_dir / "save-burst.json").write_text(json.dumps(result, indent=2) + "\n")
    return result


def _process_cpu(pid: int) -> float | None:
    try:
        import psutil
        return psutil.Process(pid).cpu_times().user + \
            psutil.Process(pid).cpu_times().system
    except Exception:
        return None


def _parse_gc(log: Path) -> dict:
    if not log.is_file():
        return {}
    text = log.read_text(encoding="utf-8", errors="replace")
    total_gc = sum(float(x) for x in re.findall(r"([0-9.]+) secs\]", text))
    full = len(re.findall(r"\[Full GC", text))
    young = len(re.findall(r"\[(?:Pause Young|PSYoungGen|ParNew)", text))
    return {"gc_total_secs": round(total_gc, 2), "full_gc": full,
            "young_gc": young}


def main() -> int:
    tag = time.strftime("%Y%m%d-%H%M%S")
    base = ROOT / "target" / "authority-review" / f"save-burst-ab-{tag}"
    base.mkdir(parents=True)
    legs = int(sys.argv[1]) if len(sys.argv) > 1 else 96
    flushes = int(sys.argv[2]) if len(sys.argv) > 2 else 4

    rust_only = "--rust-only" in sys.argv
    j = None
    if not rust_only:
        j = run_session("java-only", base / "java", 25593, False, legs, flushes)
        if j is None:
            print("SAVE_BURST_AB java session failed")
            return 1
    r = run_session("rust-authority", base / "rust", 25593, True, legs, flushes)
    if r is None:
        print("SAVE_BURST_AB rust session failed")
        return 1
    if rust_only:
        print("RUST  " + json.dumps(r, sort_keys=True))
        print(f"SAVE_BURST_AB rust_save_total_ms={r['save_total_ms']} "
              f"rust_flush={r['flush_ms']} rust_disk={r['disk_bytes_written']}")
        return 0
    print("JAVA  " + json.dumps(j, sort_keys=True))
    print("RUST  " + json.dumps(r, sort_keys=True))
    print(f"SAVE_BURST_AB java_save_total_ms={j['save_total_ms']} "
          f"rust_save_total_ms={r['save_total_ms']} "
          f"java_flush={j['flush_ms']} rust_flush={r['flush_ms']} "
          f"java_disk={j['disk_bytes_written']} rust_disk={r['disk_bytes_written']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
