#!/usr/bin/env python3
"""RUST_REGION_WRITE_AUTHORITY in-server campaign (SHADOW mode).

Boots a real Gate A / Gate C server with the region-write experiment
(-Drustcraft.regionWriteExperiment=true -Drustcraft.regionWriteMode=SHADOW),
connects headless probe clients (chunk loads via the in-server teleport
campaign), forces world saves with `save-all flush`, stops the server
(graceful save), then verifies:
  - the transformer hooked the RegionFile seam (transformCount=1),
  - the hook metrics show Rust shadow writes (rustOk > 0),
  - EVERY chunk saved in-session has a byte-identical payload in the Rust
    mirror (compare_region_shadow.py, --min gate floor).

Usage:
  run_region_write_campaign.py --target A [--probe-rounds 2]
      [--save-flushes 3] [--min-shadow 1000] [--port N]
"""
from __future__ import annotations

import argparse
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


def default_port(target: str) -> int:
    return 25590 if target == "A" else 25591


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--target", choices=["A", "C"], required=True)
    parser.add_argument("--port", type=int, default=None)
    parser.add_argument("--probe-rounds", type=int, default=2)
    parser.add_argument("--save-flushes", type=int, default=3)
    parser.add_argument("--min-shadow", type=int, default=None,
                        help="Minimum payload comparisons for a PASS "
                             "(default: 1000 for A, 5000 for C)")
    parser.add_argument("--boot-timeout-s", type=int, default=1800)
    parser.add_argument("--stability-s", type=float, default=0.0,
                        help="Probe connection hold time (0 = auto: 560s when "
                             "teleports on, else 30s)")
    parser.add_argument("--teleport-rounds", type=int, default=4,
                        help="In-server teleport campaign rounds (chunk loads "
                             "-> unload saves; 0 disables)")
    parser.add_argument("--output", type=Path, default=None)
    args = parser.parse_args()

    port = args.port or default_port(args.target)
    min_shadow = args.min_shadow or (1000 if args.target == "A" else 5000)
    target = args.target
    if args.output:
        out_dir = args.output
    else:
        tag = time.strftime("%Y%m%d-%H%M%S")
        out_dir = ROOT / "target" / "authority-review" / f"region-write-campaign-{target}-{tag}"

    if out_dir.exists():
        shutil.rmtree(out_dir)
    out_dir.mkdir(parents=True)

    dll = ROOT / "target" / "release" / "rustcraft_ffi.dll"
    if not dll.is_file():
        print(f"[ERROR] DLL missing: {dll}", file=sys.stderr)
        return 1

    if target == "A":
        rt = ROOT / "target" / "authority-smoke" / "runtimeA"
        forge_jar = "forge-1.12.2-14.23.5.2860.jar"
        vanilla_jar = "minecraft_server.1.12.2.jar"
        campaign_jar = ROOT / "target" / "rustcraft-campaign-A.jar"
        world_source = rt / "world"
        mod_versions = None
        profile_id = "FORGE_2860_SERVER_TRANSFORMED_FML_INITIALIZED_OFFLINE_V1"
        settle = 10.0
    else:
        rt = ROOT / "target" / "authority-smoke" / "runtimeC"
        forge_jar = "forge-1.12.2-14.23.5.2846-universal.jar"
        vanilla_jar = "minecraft_server.1.12.2.jar"
        campaign_jar = ROOT / "target" / "rustcraft-campaign-C.jar"
        world_source = rt / "world"
        mod_versions = ROOT / "target" / "revelation-mod-versions.json"
        profile_id = "FORGE_2846_FTB_REVELATION_3_4_0_SERVER_TRANSFORMED_OFFLINE_V1"
        settle = 40.0
    if not campaign_jar.is_file():
        print(f"[ERROR] campaign jar missing: {campaign_jar}", file=sys.stderr)
        return 1

    session = {"process_id": str(uuid.uuid4()), "session_id": str(uuid.uuid4())}
    server_dir, prep = prepare_server(
        rt, out_dir, forge_jar=forge_jar, vanilla_jar=vanilla_jar,
        campaign_jar=campaign_jar, world_source=world_source if world_source.is_dir() else None)
    shutil.copyfile(dll, server_dir / "rustcraft_ffi.dll")

    mirror_root = out_dir / "region-mirror"
    mirror_root.mkdir()

    props = (server_dir / "server.properties").read_text(encoding="utf-8")
    (server_dir / "server.properties").write_text(
        props.replace("server-port=25599", f"server-port={port}") + "\nlevel-type=DEFAULT\n",
        encoding="utf-8")

    jvm_log = out_dir / "server.log"
    extra_args = [
        "-Dfml.queryResult=confirm",
        "-Drustcraft.liveWriterDiagnostic=true",
        "-Drustcraft.session.processId=" + session["process_id"],
        "-Drustcraft.session.transformationSessionId=" + session["session_id"],
        "-Drustcraft.srgJar=" + str(SRG_JAR),
        "-Drustcraft.observationDir=" + str(server_dir / "observation"),
        "-Drustcraft.liveShadowScope=OVERWORLD_PER_CHUNK",
        "-Drustcraft.liveShadowDll=" + str(server_dir / "rustcraft_ffi.dll"),
        "-Drustcraft.liveShadowJournal=" + str(out_dir / "shadow-journal.jsonl"),
        "-Drustcraft.regionWriteExperiment=true",
        "-Drustcraft.regionWriteMode=SHADOW",
        "-Drustcraft.regionWriteMirror=" + str(mirror_root),
        "-Drustcraft.profile=" + profile_id,
    ]
    if args.teleport_rounds > 0:
        extra_args += [
            "-Drustcraft.closureCampaignTeleport=true",
            f"-Drustcraft.closureCampaignTeleportRounds={args.teleport_rounds}",
        ]

    classpath = [str(server_dir / "rustcraft-campaign.jar"),
                 str(server_dir / forge_jar),
                 str(server_dir / vanilla_jar)]
    classpath += [str(p) for p in sorted((server_dir / "libraries").rglob("*.jar"))]
    argv = [str(JAVA), "-Xmx6G",
            "-javaagent:" + str(server_dir / "rustcraft-campaign.jar"),
            ] + extra_args + [
        "-cp", os.pathsep.join(classpath),
        "net.minecraft.launchwrapper.Launch",
        "--tweakClass", "com.rustcraft.coremod.LiveSessionAdmissionTweaker",
        "--gameDir", str(server_dir),
    ]
    (out_dir / "launch.json").write_text(json.dumps({"argv": argv}, indent=2) + "\n")

    print(f"=== RUST_REGION_WRITE campaign, Gate {target}, port {port}, out {out_dir} ===")
    log_handle = jvm_log.open("wb")
    process = subprocess.Popen(argv, cwd=str(server_dir), stdout=log_handle,
                               stderr=subprocess.STDOUT, stdin=subprocess.PIPE)
    ok = False
    try:
        if not wait_for(jvm_log, r"Done \([0-9.]+s\)", args.boot_timeout_s, process=process):
            print("[ERROR] server never reached Done", file=sys.stderr)
            return 1
        print("[boot] server Done")
        time.sleep(settle)

        # client mod inventory for the probe handshake
        client_mods = [("minecraft", "1.12.2"), ("FML", "8.0.99.99"),
                       ("forge", "14.23.5.2860" if target == "A" else "14.23.5.2846"),
                       ("mcp", "9.42")]
        if target == "C" and mod_versions and mod_versions.is_file():
            log_text = jvm_log.read_text(encoding="utf-8", errors="replace")
            inv = re.search(r"missing mods \[([^\]]+)\]", log_text)
            doc = json.loads(mod_versions.read_text(encoding="utf-8"))
            derived = doc.get("versions", doc)
            if inv is not None:
                client_mods = [(m.strip(), derived.get(m.strip(), "1.0"))
                               for m in inv.group(1).split(",")]
            else:
                client_mods = [(k, v) for k, v in derived.items()]

        probe_ok = True
        for rnd in range(max(1, args.probe_rounds)):
            print(f"[probe] round {rnd + 1}/{args.probe_rounds}")
            stability = args.stability_s or (
                560.0 if args.teleport_rounds > 0 else 30.0)
            receipt = run_probe("127.0.0.1", port, f"RWP{target}{rnd}",
                                expect_forge=True, client_mods=client_mods,
                                connect_timeout_s=20.0, login_timeout_s=120.0,
                                stability_s=stability)
            verdict = receipt.get("verdict")
            print(f"[probe] round {rnd + 1}: verdict={verdict} "
                  f"observed={json.dumps(receipt.get('observed'))}")
            if verdict != "PASS":
                probe_ok = False
        if not probe_ok:
            print("[ERROR] probe rounds failed", file=sys.stderr)
            return 1

        # force world saves while chunks are loaded -> real region writes
        for i in range(max(1, args.save_flushes)):
            print(f"[save] save-all flush {i + 1}/{args.save_flushes}")
            try:
                process.stdin.write(b"save-all flush\n")
                process.stdin.flush()
            except (OSError, ValueError):
                pass
            time.sleep(20.0)
    finally:
        print("[stop] stopping server (graceful world save)")
        stop(process, timeout_s=300)
        log_handle.close()

    log_text = jvm_log.read_text(encoding="utf-8", errors="replace")
    m = re.search(r"\[RustCraft-RegionWrite\] transformer status=(\d+) (\S+)", log_text)
    transform_count = int(m.group(1)) if m else -1
    hm = re.search(r"regionWrite\.hook enabled=(\S+) mode=(\S+) cap=(\S+) entryCalls=(\d+) "
                   r"rustAdmitted=(\d+) rustOk=(\d+) rustFailed=(\d+) vanillaFallbacks=(\d+) "
                   r"exitNotes=(\d+) errors=(\d+) ticketSeedMax=(\d+)", log_text)
    metrics = dict(zip(
        ["enabled", "mode", "cap", "entryCalls", "rustAdmitted", "rustOk",
         "rustFailed", "vanillaFallbacks", "exitNotes", "errors", "ticketSeedMax"],
        hm.groups() if hm else ["?"] * 11))
    print(f"[verify] transformer status count={transform_count} metrics={metrics}")

    failures = 0
    seam_seen = "seam class seen" in log_text
    status_m = re.search(
        r"seam class seen: name=(\S+) transformedName=(\S+) bytes=(\d+)", log_text)
    print(f"[verify] seamClassSeen={seam_seen} "
          + (f"seam={status_m.groups()}" if status_m else ""))
    if transform_count != 1:
        print(f"[FAIL] transformer did not hook RegionFile (count={transform_count})")
        failures += 1
    if metrics["errors"] != "0":
        print(f"[FAIL] hook errors={metrics['errors']}")
        failures += 1
    rust_ok = int(metrics["rustOk"]) if metrics["rustOk"].isdigit() else 0
    if rust_ok == 0:
        print("[FAIL] no Rust shadow writes recorded")
        failures += 1

    world_region = server_dir / "world" / "region"
    comparator = ROOT / "tools" / "authority-review" / "compare_region_shadow.py"
    print(f"[verify] comparing real {world_region} vs mirror {mirror_root} "
          f"(min {min_shadow})")
    cmp_run = subprocess.run([sys.executable, str(comparator), str(world_region),
                              str(mirror_root), "--min", str(min_shadow)],
                             capture_output=True, text=True)
    print(cmp_run.stdout.strip())
    if cmp_run.returncode != 0:
        failures += 1

    (out_dir / "region-write-campaign.json").write_text(json.dumps({
        "target": target, "port": port, "transform_count": transform_count,
        "hook_metrics": metrics, "rust_ok": rust_ok,
        "comparator_rc": cmp_run.returncode,
        "comparator_stdout": cmp_run.stdout.strip()[-4000:],
    }, indent=2, sort_keys=True) + "\n")

    if failures:
        print(f"REGION_WRITE_CAMPAIGN_FAILED Gate {target} ({failures} failures)")
        return 1
    print(f"REGION_WRITE_CAMPAIGN_PASSED Gate {target} "
          f"(rustOk={rust_ok}, comparisons>={min_shadow})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
