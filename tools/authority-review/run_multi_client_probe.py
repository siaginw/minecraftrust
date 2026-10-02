#!/usr/bin/env python3
"""Multi-client single-copy probe: boots a Clean Forge server with the
single-copy direct boundary enabled and joins 1, then 4, then 8 headless
probe clients in PARALLEL at world spawn, where they share the vanilla
changed-chunk broadcast (one SPacketChunkData object -> several channels).

Gate: every client must complete FML handshake + PLAY + KeepAlive +
stability + clean disconnect while receiving chunk packets; the server must
end with 0 outstanding single-copy bodies and bypass counts equal to
committed counts.
"""
from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools" / "live-shadow-v2"))

from run_join_probe import JAVA, prepare_server, stop, wait_for  # noqa: E402
from join_probe import run_probe  # noqa: E402

RT = ROOT / "target" / "authority-smoke" / "runtimeA"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=25595)
    parser.add_argument("--cap", type=int, default=4096)
    parser.add_argument("--client-counts", type=int, nargs="+", default=[1, 4, 8])
    parser.add_argument("--stability-s", type=float, default=30.0)
    parser.add_argument("--output", type=Path, default=ROOT / "target" / "authority-smoke" / "sc-multi-client")
    args = parser.parse_args()

    out_dir = args.output.resolve()
    if out_dir.exists():
        shutil.rmtree(out_dir)
    out_dir.mkdir(parents=True)

    campaign_jar = ROOT / "target" / "rustcraft-campaign-A.jar"
    dll = ROOT / "target" / "release" / "rustcraft_ffi.dll"
    closure_receipt = ROOT / "target" / "authority-review" / "closure-input-receipt.json"
    for dep in (campaign_jar, dll, closure_receipt, RT):
        if not dep.exists():
            print(f"[ERROR] missing prerequisite: {dep}")
            return 1

    session = {"process_id": str(uuid.uuid4()), "session_id": str(uuid.uuid4())}
    server_dir, _ = prepare_server(
        RT, out_dir,
        forge_jar="forge-1.12.2-14.23.5.2860.jar",
        vanilla_jar="minecraft_server.1.12.2.jar",
        campaign_jar=campaign_jar,
        world_source=RT / "world",
    )
    # max-players must cover 8 concurrent clients; prepare_server pins its
    # own PORT constant, so re-pin the port to the requested one.
    props = (server_dir / "server.properties").read_text(encoding="utf-8")
    props = "\n".join(
        line for line in props.splitlines() if not line.startswith("server-port="))
    (server_dir / "server.properties").write_text(
        props + "\nserver-port=%d\nmax-players=16\nlevel-type=FLAT\n" % args.port,
        encoding="utf-8")
    shutil.copyfile(dll, server_dir / "rustcraft_ffi.dll")

    exp_receipt = out_dir / "authority-experiment-receipt.json"
    jvm_log = out_dir / "server.log"
    extra = [
        "-Dfml.queryResult=confirm",
        "-Drustcraft.liveWriterDiagnostic=true",
        "-Drustcraft.session.processId=" + session["process_id"],
        "-Drustcraft.session.transformationSessionId=" + session["session_id"],
        "-Drustcraft.srgJar=D:\\minecraftrust\\third_party_reference\\minecraft\\minecraft_server.1.12.2.srg.jar",
        "-Drustcraft.observationDir=" + str(server_dir / "observation"),
        "-Drustcraft.liveShadowScope=OVERWORLD_PER_CHUNK",
        "-Drustcraft.liveShadowDll=" + str(server_dir / "rustcraft_ffi.dll"),
        "-Drustcraft.liveShadowJournal=" + str(out_dir / "shadow-journal.jsonl"),
        "-Drustcraft.packetAuthorityExperiment=true",
        f"-Drustcraft.packetAuthorityCap={args.cap}",
        "-Drustcraft.packetAuthorityReceipt=" + str(closure_receipt),
        "-Drustcraft.packetAuthorityReceiptOut=" + str(exp_receipt),
        "-Drustcraft.chunkStateAuthorityExperiment=true",
        "-Drustcraft.chunkStateAuthorityCap=1000",
        "-Drustcraft.directNettyExperiment=false",
        "-Drustcraft.directNettyShadow=false",
        "-Drustcraft.singleCopy=true",
        "-Drustcraft.singleCopyShadow=false",
        "-Drustcraft.profile=FORGE_2860_SERVER_TRANSFORMED_FML_INITIALIZED_OFFLINE_V1",
    ]

    classpath = [str(server_dir / "rustcraft-campaign.jar"),
                 str(server_dir / "forge-1.12.2-14.23.5.2860.jar"),
                 str(server_dir / "minecraft_server.1.12.2.jar")]
    classpath += [str(pth) for pth in sorted((server_dir / "libraries").rglob("*.jar"))]
    argv = [str(JAVA), "-Xmx6G", "-javaagent:" + str(server_dir / "rustcraft-campaign.jar")] \
        + extra + ["-cp", os.pathsep.join(classpath),
                   "net.minecraft.launchwrapper.Launch",
                   "--tweakClass", "com.rustcraft.coremod.LiveSessionAdmissionTweaker",
                   "--gameDir", str(server_dir)]
    (out_dir / "launch.json").write_text(json.dumps({"argv": argv}, indent=2) + "\n")

    log_handle = jvm_log.open("wb")
    process = subprocess.Popen(argv, cwd=str(server_dir), stdout=log_handle,
                               stderr=subprocess.STDOUT, stdin=subprocess.PIPE)
    print(f"[boot] server launching on port {args.port} (pid {process.pid})")
    try:
        if not wait_for(jvm_log, r"Done \([0-9.]+s\)", 1800, process=process):
            print("[ERROR] server never reached Done")
            return 1
        print("[boot] server Done; settling 15s")
        time.sleep(15)

        results = {}
        for count in args.client_counts:
            print(f"=== joining {count} clients in parallel ===")
            with ThreadPoolExecutor(max_workers=count) as pool:
                futures = [pool.submit(
                    run_probe, "127.0.0.1", args.port, f"Multi{count}C{i}",
                    expect_forge=True,
                    client_mods=[("minecraft", "1.12.2"), ("FML", "8.0.99.99"),
                                 ("forge", "14.23.5.2860"), ("mcp", "9.42")],
                    connect_timeout_s=60.0 + 20 * count,
                    login_timeout_s=240.0,
                    stability_s=args.stability_s)
                    for i in range(count)]
                round_receipts = [f.result() for f in futures]
            results[count] = [{
                "verdict": r.get("verdict"),
                "chunk_packets": r.get("observed", {}).get("chunk_packets", 0),
                "failure": r.get("failure"),
            } for r in round_receipts]
            (out_dir / f"probe-round-{count}.json").write_text(
                json.dumps(round_receipts, indent=2, sort_keys=True) + "\n")
            passed = sum(1 for r in results[count] if r["verdict"] == "PASS")
            total_chunks = sum(r["chunk_packets"] for r in results[count])
            print(f"=== {count}-client round: {passed}/{count} PASS, {total_chunks} chunk packets ===")

        (out_dir / "multi-client-summary.json").write_text(
            json.dumps(results, indent=2) + "\n")
        all_pass = all(r["verdict"] == "PASS" for rs in results.values() for r in rs)
        print(f"[multi-client] verdict: {'PASS' if all_pass else 'FAIL'}")
        return 0 if all_pass else 1
    finally:
        print("[stop] stopping server")
        stop(process, timeout_s=120)
        log_handle.close()
        if exp_receipt.exists():
            receipt = json.loads(exp_receipt.read_text(encoding="utf-8"))
            (out_dir / "authority-receipt-final.json").write_text(
                json.dumps(receipt, indent=2) + "\n")
            print("[receipt] single-copy telemetry: "
                  + str(receipt.get("single_copy_telemetry")))


if __name__ == "__main__":
    sys.exit(main())
