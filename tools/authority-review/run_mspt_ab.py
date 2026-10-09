#!/usr/bin/env python3
"""True-MSPT A/B: boots the Gate A server twice with the BenchAgent javaagent
(hooks MinecraftServer.func_71217_p on the real bytecode) - once with the
single-copy boundary enabled, once with the authority disabled - and drives
IDENTICAL probe workloads. Reports compute-tick MSPT mean/p50/p95/p99 from
the agent's own output (never the 50ms wall period).
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

from run_join_probe import prepare_server, stop, wait_for  # noqa: E402
from join_probe import run_probe  # noqa: E402

JAVA = Path(r"C:\Program Files\Eclipse Adoptium\jdk-8.0.504.1-hotspot\bin\java.exe")
JAVAC = Path(r"C:\Program Files\Eclipse Adoptium\jdk-8.0.504.1-hotspot\bin\javac.exe")
JAR = Path(r"C:\Program Files\Eclipse Adoptium\jdk-8.0.504.1-hotspot\bin\jar.exe")
RT = ROOT / "target" / "authority-smoke" / "runtimeA"
SRG = "D:/minecraftrust/third_party_reference/minecraft/minecraft_server.1.12.2.srg.jar"


def build_agent(out: Path) -> Path:
    build = ROOT / "target" / "authority-review" / "mspt-agent"
    if build.exists():
        shutil.rmtree(build)
    build.mkdir(parents=True)
    src = ROOT / "tools" / "bench-agent" / "src" / "com" / "rustcraft" / "bench" / "BenchAgent.java"
    cp = os.pathsep.join([str(RT / "forge-1.12.2-14.23.5.2860.jar"),
                          Path(SRG).as_posix()])
    r = subprocess.run([str(JAVAC), "-encoding", "UTF-8", "-source", "8", "-target", "8",
                        "-nowarn", "-cp", cp, "-d", str(build), str(src)],
                       capture_output=True, text=True)
    if r.returncode != 0:
        print(r.stderr[-3000:])
        raise SystemExit(1)
    manifest = build / "manifest.mf"
    manifest.write_text("Manifest-Version: 1.0\nPremain-Class: "
                        "com.rustcraft.bench.BenchAgent\n", encoding="utf-8")
    agent = out / "bench-agent.jar"
    subprocess.run([str(JAR), "cfm", str(agent), str(manifest), "-C", str(build), "."],
                   capture_output=True, text=True)
    return agent


def run_phase(name: str, single_copy: bool, port: int, agent: Path,
              compress: bool = False) -> dict:
    out_dir = ROOT / "target" / "authority-smoke" / f"sc-mspt-{name}"
    if out_dir.exists():
        shutil.rmtree(out_dir)
    out_dir.mkdir(parents=True)
    campaign_jar = ROOT / "target" / "rustcraft-campaign-A.jar"
    dll = ROOT / "target" / "release" / "rustcraft_ffi.dll"
    closure = ROOT / "target" / "authority-review" / "closure-input-receipt.json"

    session = {"process_id": str(uuid.uuid4()), "session_id": str(uuid.uuid4())}
    server, _ = prepare_server(
        RT, out_dir,
        forge_jar="forge-1.12.2-14.23.5.2860.jar",
        vanilla_jar="minecraft_server.1.12.2.jar",
        campaign_jar=campaign_jar,
        world_source=RT / "world")
    shutil.copyfile(dll, server / "rustcraft_ffi.dll")
    # prepare_server pins its own PORT constant: re-pin to the requested one.
    props = (server / "server.properties").read_text(encoding="utf-8")
    props = "\n".join(
        line for line in props.splitlines() if not line.startswith("server-port="))
    (server / "server.properties").write_text(
        props + "\nserver-port=%d\n" % port, encoding="utf-8")

    cp = [str(server / "rustcraft-campaign.jar"),
          str(server / "forge-1.12.2-14.23.5.2860.jar"),
          str(server / "minecraft_server.1.12.2.jar")]
    cp += [str(p) for p in sorted((server / "libraries").rglob("*.jar"))]
    argv = [str(JAVA), "-Xmx6G",
            "-javaagent:" + str(server / "rustcraft-campaign.jar"),
            "-javaagent:" + str(agent.resolve()),
            "-Dbench.warmup=30", "-Dbench.duration=60",
            "-Dfml.queryResult=confirm",
            "-Drustcraft.liveWriterDiagnostic=true",
            "-Drustcraft.session.processId=" + session["process_id"],
            "-Drustcraft.session.transformationSessionId=" + session["session_id"],
            "-Drustcraft.srgJar=" + SRG,
            "-Drustcraft.liveShadowDll=" + str(server / "rustcraft_ffi.dll"),
            "-Drustcraft.packetAuthorityExperiment=" + ("true" if single_copy else "false"),
            "-Drustcraft.packetAuthorityCap=120",
            "-Drustcraft.packetAuthorityReceipt=" + str(closure),
            "-Drustcraft.packetAuthorityReceiptOut=" + str(out_dir / "receipt.json"),
            "-Drustcraft.singleCopy=" + ("true" if single_copy else "false"),
            "-Drustcraft.singleCopyShadow=false",
            "-Dminecraftrust.native_compress=" + ("ON_EXPERIMENTAL" if compress else "OFF"),
            "-Drustcraft.compressionNotchRuntime=true",
            "-Drustcraft.profile=FORGE_2860_SERVER_TRANSFORMED_FML_INITIALIZED_OFFLINE_V1",
            "-cp", os.pathsep.join(cp),
            "net.minecraft.launchwrapper.Launch",
            "--tweakClass", "com.rustcraft.coremod.LiveSessionAdmissionTweaker",
            "--gameDir", str(server)]
    log_h = (out_dir / "server.log").open("wb")
    proc = subprocess.Popen(argv, cwd=str(server), stdout=log_h,
                            stderr=subprocess.STDOUT, stdin=subprocess.PIPE)
    try:
        if not wait_for(out_dir / "server.log", r"Done \([0-9.]+s\)", 1200, process=proc):
            return {"phase": name, "booted": False}
        time.sleep(10)
        receipts = []
        for i in range(2):
            receipts.append(run_probe("127.0.0.1", port, f"Mspt{name}{i}",
                                      expect_forge=True,
                                      client_mods=[("minecraft", "1.12.2"),
                                                   ("FML", "8.0.99.99"),
                                                   ("forge", "14.23.5.2860"),
                                                   ("mcp", "9.42")],
                                      connect_timeout_s=30.0, login_timeout_s=180.0,
                                      stability_s=60.0))
        # hold ~35s more so the agent's 30s warmup + 60s measurement window
        # (anchored at the FIRST tick - well before the probes) elapses and
        # the stats line lands in the log.
        for _ in range(35):
            time.sleep(1)
            text = (out_dir / "server.log").read_text(encoding="utf-8", errors="replace")
            if "compute_mspt:" in text:
                break
        text = (out_dir / "server.log").read_text(encoding="utf-8", errors="replace")
        stats = []
        for m in re.finditer(r"compute_mspt:.*", text):
            stats.append(m.group(0))
        return {"phase": name, "booted": True,
                "probes": [r.get("verdict") for r in receipts],
                "chunk_packets": sum(r.get("observed", {}).get("chunk_packets", 0)
                                     for r in receipts),
                "mspt_lines": stats[-4:]}
    finally:
        stop(proc, timeout_s=120)
        log_h.close()


def main() -> int:
    import argparse
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=25584)
    args = parser.parse_args()

    out = ROOT / "target" / "authority-smoke" / "sc-mspt"
    out.mkdir(parents=True, exist_ok=True)
    agent = build_agent(out)

    # Compression MSPT A/B: both arms single-copy ON; the toggle is
    # native_compress OFF (Java Deflater) vs ON_EXPERIMENTAL (Rust).
    result = {
        "rust_compression": run_phase("rustcomp", True, args.port, agent, compress=True),
        "java_compression": run_phase("javacomp", True, args.port, agent, compress=False),
    }
    (out / "mspt-ab.json").write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
