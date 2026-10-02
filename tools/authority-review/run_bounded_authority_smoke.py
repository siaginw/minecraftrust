#!/usr/bin/env python3
"""Bounded Rust-Authority Experiment Smoke Runner for Gate A and Gate B.

Executes a small, explicit, fail-closed bounded Rust-authority experiment
against either Clean Forge 2860 (Gate A) or FTB Revelation 2846 (Gate B).
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
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools" / "live-shadow-v2"))

from run_join_probe import JAVA, prepare_server, sha, stop, wait_for  # noqa: E402
from join_probe import run_probe  # noqa: E402


def check_port(port: int) -> str | None:
    res = subprocess.run(["netstat", "-ano"], capture_output=True, text=True)
    for line in res.stdout.splitlines():
        if f":{port} " in line and "LISTENING" in line:
            parts = line.split()
            return parts[-1]
    return None


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--target", choices=["A", "C"], required=True,
                        help="Target runtime: A (Clean Forge 2860) or C (FTB Revelation 2846)")
    parser.add_argument("--cap", type=int, default=None,
                        help="Bounded event cap (default: 32 for A, 64 for C)")
    parser.add_argument("--port", type=int, default=None,
                        help="Server port (default: 25597 for A, 25596 for C)")
    parser.add_argument("--output", type=Path, default=None,
                        help="Output run directory")
    parser.add_argument("--stability-s", type=float, default=20.0,
                        help="Client stability hold window in seconds")
    parser.add_argument("--boot-timeout-s", type=int, default=1800,
                        help="Server boot timeout in seconds")
    parser.add_argument("--profile-jfr", action="store_true",
                        help="Enable Java Flight Recorder stack sampling during run")
    parser.add_argument("--profile-etw", action="store_true",
                        help="Enable Windows ETW/WPR CPU stack sampling during run")
    parser.add_argument("--disable-authority", action="store_true",
                        help="Disable Rust packet authority for baseline A/B measurement")
    parser.add_argument("--direct-netty", action="store_true",
                        help="Enable Direct Netty wire emission experiment")
    parser.add_argument("--direct-shadow", action="store_true",
                        help="Enable Direct Netty shadow verification mode")
    args = parser.parse_args()

    target = args.target
    cap = args.cap or (32 if target == "A" else 64)
    port = args.port or (25597 if target == "A" else 25596)
    out_dir = args.output or (ROOT / "target" / "authority-smoke" / f"target{target}")
    out_dir = out_dir.resolve()

    if out_dir.exists():
        shutil.rmtree(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    held_pid = check_port(port)
    if held_pid:
        print(f"[ERROR] Port {port} is already held by PID {held_pid}!", file=sys.stderr)
        return 1

    srg_jar = Path(r"D:\minecraftrust\third_party_reference\minecraft\minecraft_server.1.12.2.srg.jar")
    dll = ROOT / "target" / "release" / "rustcraft_ffi.dll"
    closure_receipt = ROOT / "target" / "authority-review" / "closure-input-receipt.json"

    if not closure_receipt.exists():
        print(f"[ERROR] Verified closure input receipt not found at {closure_receipt}!", file=sys.stderr)
        return 1
    if not dll.exists():
        print(f"[ERROR] Rust DLL not found at {dll}!", file=sys.stderr)
        return 1

    if target == "A":
        rt = Path(r"D:\minecraftrust\machine\targetA\server")
        forge_jar_name = "forge-1.12.2-14.23.5.2860.jar"
        vanilla_jar_name = "minecraft_server.1.12.2.jar"
        campaign_jar = ROOT / "target" / "rustcraft-campaign-A.jar"
        world_source = rt / "world"
        mod_versions_file = None
    else:
        rt = Path(r"D:\minecraftrust\machine\targetC\server")
        forge_jar_name = "forge-1.12.2-14.23.5.2846-universal.jar"
        vanilla_jar_name = "minecraft_server.1.12.2.jar"
        campaign_jar = ROOT / "target" / "rustcraft-campaign-C.jar"
        # Pre-generated world for Revelation
        world_source = rt / "world"
        mod_versions_file = ROOT / "target" / "revelation-mod-versions.json"
        if not mod_versions_file.exists():
            print(f"[ERROR] Revelation mod versions file not found at {mod_versions_file}!", file=sys.stderr)
            return 1

    if not campaign_jar.exists():
        print(f"[ERROR] Campaign jar not found at {campaign_jar}! Run tools/build_campaign_jar.py first.", file=sys.stderr)
        return 1

    print(f"=== Starting Gate {target} Bounded Rust-Authority Smoke ===")
    print(f"Target: {'Clean Forge 2860' if target == 'A' else 'FTB Revelation 2846'}")
    print(f"Authority Cap: {cap}")
    print(f"Port: {port}")
    print(f"Output Directory: {out_dir}")

    session = {
        "process_id": str(uuid.uuid4()),
        "session_id": str(uuid.uuid4())
    }
    (out_dir / "launch-session.json").write_text(json.dumps(session, indent=2) + "\n")

    server_dir, prep_info = prepare_server(
        rt, out_dir,
        forge_jar=forge_jar_name,
        vanilla_jar=vanilla_jar_name,
        campaign_jar=campaign_jar,
        world_source=world_source if world_source.is_dir() else None
    )

    # Clean out any stale coremod jars from previous stages (e.g. rustcraft-m1-coremod.jar)
    if (server_dir / "mods").is_dir():
        for old_jar in (server_dir / "mods").glob("*m1*.jar"):
            old_jar.unlink()
        for old_jar in (server_dir / "mods").glob("*coremod*.jar"):
            old_jar.unlink()

    # Place DLL inside server directory
    shutil.copyfile(dll, server_dir / "rustcraft_ffi.dll")

    # Update server.properties
    props_file = server_dir / "server.properties"
    props = props_file.read_text(encoding="utf-8")
    props_file.write_text(
        props.replace("server-port=25599", f"server-port={port}")
        + "\nlevel-type=DEFAULT\n"
    )

    exp_receipt_out = out_dir / "authority-experiment-receipt.json"
    jvm_log = out_dir / "server.log"

    extra_args = [
        "-Dfml.queryResult=confirm",
        "-Drustcraft.liveWriterDiagnostic=true",
        "-Drustcraft.session.processId=" + session["process_id"],
        "-Drustcraft.session.transformationSessionId=" + session["session_id"],
        "-Drustcraft.srgJar=" + str(srg_jar),
        "-Drustcraft.observationDir=" + str(server_dir / "observation"),
        "-Drustcraft.liveShadowScope=OVERWORLD_PER_CHUNK",
        "-Drustcraft.liveShadowDll=" + str(server_dir / "rustcraft_ffi.dll"),
        "-Drustcraft.liveShadowJournal=" + str(out_dir / "shadow-journal.jsonl"),
        "-Drustcraft.packetAuthorityExperiment=" + ("false" if args.disable_authority else "true"),
        f"-Drustcraft.packetAuthorityCap={cap}",
        "-Drustcraft.packetAuthorityReceipt=" + str(closure_receipt),
        "-Drustcraft.packetAuthorityReceiptOut=" + str(exp_receipt_out),
        "-Drustcraft.chunkStateAuthorityExperiment=true",
        "-Drustcraft.chunkStateAuthorityCap=1000",
        "-Drustcraft.directNettyExperiment=" + ("true" if args.direct_netty else "false"),
        "-Drustcraft.directNettyShadow=" + ("true" if args.direct_shadow else "false"),
    ]

    if target == "C":
        profile_id = "FORGE_2846_FTB_REVELATION_3_4_0_SERVER_TRANSFORMED_OFFLINE_V1"
    else:
        profile_id = "FORGE_2860_SERVER_TRANSFORMED_FML_INITIALIZED_OFFLINE_V1"
    extra_args.append("-Drustcraft.profile=" + profile_id)

    jfr_file = out_dir / "server-profile.jfr"
    if args.profile_jfr:
        extra_args.extend([
            "-XX:+UnlockCommercialFeatures",
            "-XX:+FlightRecorder",
            f"-XX:StartFlightRecording=settings=profile,filename={jfr_file},dumponexit=true"
        ])

    classpath = [str(server_dir / "rustcraft-campaign.jar"),
                 str(server_dir / forge_jar_name),
                 str(server_dir / vanilla_jar_name)]
    classpath += [str(p) for p in sorted((server_dir / "libraries").rglob("*.jar"))]

    argv = [
        str(JAVA), "-Xmx6G",
        "-javaagent:" + str(server_dir / "rustcraft-campaign.jar"),
    ] + extra_args + [
        "-cp", os.pathsep.join(classpath),
        "net.minecraft.launchwrapper.Launch",
        "--tweakClass", "com.rustcraft.coremod.LiveSessionAdmissionTweaker",
        "--gameDir", str(server_dir)
    ]

    if args.profile_etw:
        print("[etw] Starting Windows Performance Recorder (CPU sampling)...")
        subprocess.run(["wpr", "-start", "CPU", "-filemode"], check=False)

    (out_dir / "launch.json").write_text(json.dumps({"session": session, "argv": argv}, indent=2) + "\n")

    print(f"[launch] Booting server (PID will follow)...")
    log_handle = jvm_log.open("wb")
    process = subprocess.Popen(argv, cwd=str(server_dir), stdout=log_handle, stderr=subprocess.STDOUT, stdin=subprocess.PIPE)
    print(f"[launch] Server launched with PID {process.pid}")

    booted = False
    try:
        print(f"[boot] Waiting for server Done line (timeout {args.boot_timeout_s}s)...")
        booted = wait_for(jvm_log, r"Done \([0-9.]+s\)", args.boot_timeout_s, process=process)
        if not booted:
            print("[ERROR] Server failed to reach Done within timeout!", file=sys.stderr)
            return 1
        print("[boot] Server successfully reached Done!")

        # Post-done settle
        settle_s = 40.0 if target == "C" else 10.0
        print(f"[settle] Allowing {settle_s}s post-boot settle...")
        time.sleep(settle_s)

        # Derive client mod list
        client_mods = []
        if target == "C":
            log_text = jvm_log.read_text(encoding="utf-8", errors="replace")
            inventory = re.search(r"missing mods \[([^\]]+)\]", log_text)
            doc = json.loads(mod_versions_file.read_text(encoding="utf-8"))
            derived = doc.get("versions", doc)
            builtins = {
                "minecraft": "1.12.2",
                "FML": "8.0.99.99",
                "forge": "14.23.5.2846",
                "mcp": "9.42",
            }
            if inventory is not None:
                for name in inventory.group(1).split(","):
                    modid = name.strip()
                    v = derived.get(modid, builtins.get(modid, "1.0"))
                    client_mods.append((modid, v))
            else:
                for modid, v in derived.items():
                    client_mods.append((modid, v))
        else:
            client_mods = [
                ("minecraft", "1.12.2"),
                ("FML", "8.0.99.99"),
                ("forge", "14.23.5.2860"),
                ("mcp", "9.42"),
            ]

        print(f"[probe] Connecting headless probe client ({len(client_mods)} mods in handshake)...")
        probe_receipt = run_probe(
            "127.0.0.1", port, f"AuthProbe{target}",
            expect_forge=True,
            client_mods=client_mods,
            connect_timeout_s=20.0,
            login_timeout_s=120.0,
            stability_s=args.stability_s
        )

        probe_out = out_dir / "probe-receipt.json"
        probe_out.write_text(json.dumps(probe_receipt, indent=2, sort_keys=True) + "\n")
        print(f"[probe] Client probe finished: verdict={probe_receipt.get('verdict')}")
        print(f"[probe] Observed checks: {json.dumps(probe_receipt.get('observed'), indent=2)}")

        if probe_receipt.get("verdict") != "PASS":
            print(f"[ERROR] Client probe failed: {probe_receipt.get('failure')}", file=sys.stderr)
            return 1

    finally:
        print("[stop] Sending stop command to server...")
        stop(process, timeout_s=120)
        log_handle.close()
        print("[stop] Server stopped cleanly.")

        if args.profile_etw:
            etw_out = out_dir / "server-etw.etl"
            print(f"[etw] Stopping Windows Performance Recorder -> {etw_out}...")
            subprocess.run(["wpr", "-stop", str(etw_out)], check=False)

        if args.profile_jfr and jfr_file.exists():
            print(f"[jfr] JFR recording collected at {jfr_file} ({jfr_file.stat().st_size} bytes)")
            summary_txt = out_dir / "jfr-summary.txt"
            subprocess.run(["jfr", "summary", str(jfr_file)], stdout=summary_txt.open("wb"), check=False)

    # Inspect and verify authority experiment receipt
    print(f"[receipt] Inspecting authority experiment receipt at {exp_receipt_out}...")
    if not exp_receipt_out.exists():
        print(f"[ERROR] Authority experiment receipt was not created at {exp_receipt_out}!", file=sys.stderr)
        return 1

    auth_data = json.loads(exp_receipt_out.read_text(encoding="utf-8"))
    print(f"[receipt] Experiment Receipt Content:\n{json.dumps(auth_data, indent=2)}")

    # Safety assertions
    assert auth_data.get("production_authority") is False, "production_authority MUST be false"
    if args.disable_authority:
        assert auth_data.get("experiment_enabled") is False, "experiment_enabled must be false when disabled"
        print("[baseline] Baseline mode confirmed: Rust packet authority disabled.")
        return 0
    assert auth_data.get("experiment_enabled") is True, "experiment_enabled must be true"
    assert auth_data.get("receipt_verified") is True, "receipt_verified must be true"
    assert auth_data.get("lifecycle_state") == "BOUNDED_AUTHORITY_EXPERIMENT", "State must be BOUNDED_AUTHORITY_EXPERIMENT"

    counters = auth_data.get("counters", {})
    rust_selected = counters.get("rust_selected", 0)
    java_selected = counters.get("java_selected", 0)
    cap_exhausted = counters.get("cap_exhausted", 0)
    rust_encode_failure = counters.get("rust_encode_failure", 0)

    retained_rust_selected = counters.get("retained_rust_selected", 0)
    retained_seeded = counters.get("retained_seeded", 0)
    direct_netty_committed = counters.get("direct_netty_committed", 0)
    direct_netty_buffers_allocated = counters.get("direct_netty_buffers_allocated", 0)
    direct_netty_buffers_released = counters.get("direct_netty_buffers_released", 0)
    outstanding_direct_buffers = counters.get("outstanding_direct_buffers", 0)
    direct_netty_fallbacks = counters.get("direct_netty_fallbacks", 0)

    print(f"[metrics] rust_selected: {rust_selected}")
    print(f"[metrics] retained_rust_selected: {retained_rust_selected}")
    print(f"[metrics] retained_seeded: {retained_seeded}")
    print(f"[metrics] direct_netty_committed: {direct_netty_committed}")
    print(f"[metrics] direct_netty_buffers_allocated: {direct_netty_buffers_allocated}")
    print(f"[metrics] direct_netty_buffers_released: {direct_netty_buffers_released}")
    print(f"[metrics] outstanding_direct_buffers: {outstanding_direct_buffers}")
    print(f"[metrics] direct_netty_fallbacks: {direct_netty_fallbacks}")
    print(f"[metrics] java_selected: {java_selected}")
    print(f"[metrics] cap_exhausted: {cap_exhausted}")
    print(f"[metrics] rust_encode_failure: {rust_encode_failure}")

    assert rust_encode_failure == 0, f"rust_encode_failure must be 0, got {rust_encode_failure}"
    assert rust_selected > 0, f"rust_selected must be > 0 (Rust must have authored packets within bound), got {rust_selected}"
    assert rust_selected <= cap, f"rust_selected ({rust_selected}) must not exceed authority cap ({cap})"

    if args.direct_netty:
        assert direct_netty_committed > 0, f"direct_netty_committed must be > 0, got {direct_netty_committed}"
        assert outstanding_direct_buffers == 0, f"outstanding_direct_buffers must be 0 (no leaks), got {outstanding_direct_buffers}"
        assert direct_netty_fallbacks == 0, f"direct_netty_fallbacks must be 0, got {direct_netty_fallbacks}"
        print(f"[direct-netty] PASS: {direct_netty_committed} packets directly emitted into Netty with 0 leaks and 0 fallbacks!")

    if rust_selected == cap:
        print(f"[metrics] Reached authority cap ({cap})! Fallback to Java was engaged successfully.")

    smoke_summary = {
        "schema": "RUSTCRAFT_BOUNDED_AUTHORITY_SMOKE_RECEIPT_V1",
        "gate": f"Gate {target}",
        "target": "Clean Forge 2860" if target == "A" else "FTB Revelation 2846",
        "authority_cap": cap,
        "production_authority": False,
        "verdict": "PASS",
        "probe_verdict": probe_receipt.get("verdict"),
        "probe_observed": probe_receipt.get("observed"),
        "authority_receipt": auth_data,
        "timestamp": time.time(),
    }
    summary_path = ROOT / "target" / "authority-smoke" / f"target{target}-smoke-receipt.json"
    summary_path.write_text(json.dumps(smoke_summary, indent=2) + "\n")
    print(f"[SUCCESS] Gate {target} Bounded Authority Smoke PASSED! Receipt written to {summary_path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
