#!/usr/bin/env python3
"""Bounded live SHADOW smoke (Phase D sections 20/21). NOT a campaign.

Boots a disposable copy of a pinned runtime with the Phase-D campaign jar and
the release Rust DLL, joins headless exactly once, and lets the in-server
shadow pipeline run: live Java capture -> sealed snapshot -> Rust shadow
encode -> identity-paired comparison -> taxonomy journal. The Java packet
continues to the client unchanged; the Rust result is recorded and discarded.

Bounds: rustcraft.liveShadowMaxEvents caps comparisons; the queue bound stays
nonblocking; one probe, one receipt. Every outcome -- including every drop
and exclusion -- is counted with a reason, and no threshold is chased here.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
import subprocess
import sys
import time
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(Path(__file__).resolve().parent))

from run_join_probe import JAVA, launch, prepare_server, sha, stop, wait_for  # noqa: E402
from join_probe import run_probe  # noqa: E402
import taxonomy  # noqa: E402

PORT = 25598


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--runtime-root", type=Path, required=True)
    parser.add_argument("--forge-jar", required=True)
    parser.add_argument("--vanilla-jar", required=True)
    parser.add_argument("--campaign-jar", type=Path, required=True)
    parser.add_argument("--srg-jar", type=Path, default=None)
    parser.add_argument("--dll", type=Path, required=True)
    parser.add_argument("--mod-versions", type=Path, default=None)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--username", default="ShadowSmoke")
    parser.add_argument("--max-events", type=int, default=32)
    parser.add_argument("--queue-capacity", type=int, default=16)
    parser.add_argument("--queue-max-bytes", type=int, default=64 * 1024 * 1024)
    parser.add_argument("--post-done-settle-s", type=float, default=30.0)
    parser.add_argument("--stability-s", type=float, default=20.0)
    parser.add_argument("--boot-timeout-s", type=int, default=1800)
    parser.add_argument("--admitted", action="store_true",
                        help="launch through the V2 session-bound admission tweaker "
                             "(the qualified session-bound plan must be admitted before "
                             "any shadow capture can exist)")
    parser.add_argument("--static-contract", type=Path, default=None)
    args = parser.parse_args()

    out = args.output.resolve()
    if out.exists():
        shutil.rmtree(out)
    out.mkdir(parents=True)

    port_held = None
    for line in subprocess.run(["netstat", "-ano"], capture_output=True, text=True).stdout.splitlines():
        if ":%d" % PORT in line and "LISTENING" in line:
            port_held = line.split()[-1]
    if port_held:
        print(json.dumps({"status": "FAIL", "reason": "PORT_ALREADY_HELD",
                          "holder_pid": port_held}))
        return 1

    session = {"process_id": str(uuid.uuid4()), "session_id": str(uuid.uuid4())}
    (out / "launch-session.json").write_text(json.dumps(session, indent=2) + "\n")

    server, prepare_info = prepare_server(
        args.runtime_root, out, forge_jar=args.forge_jar, vanilla_jar=args.vanilla_jar,
        campaign_jar=args.campaign_jar.resolve(), world_source=None)
    if args.admitted and args.srg_jar is not None:
        args.srg_jar = args.srg_jar.resolve()
    # The JVM resolves -D paths against ITS working directory (the disposable
    # server copy); every artifact reference must be absolute.
    if args.srg_jar is not None:
        args.srg_jar = args.srg_jar.resolve()
    args.dll = args.dll.resolve()
    # FML discovers coremods by the FMLCorePlugin manifest in the MODS
    # directory, not the classpath: the campaign jar must live in mods/ for
    # the writers (and the shadow consumer) to load at all.
    if not args.admitted:
        # The coremod shape loads via mods/; the admitted shape loads through
        # the tweaker only (a second registration would duplicate writers).
        (server / "mods").mkdir(exist_ok=True)
        shutil.copyfile(args.campaign_jar, server / "mods" / "rustcraft-live-coremod.jar")
    # The shadow pipeline needs the DLL inside the disposable server copy.
    shutil.copyfile(args.dll, server / "rustcraft_ffi.dll")
    # run_join_probe's server.properties pins port 25599; the smoke runs on its
    # own port so it can never collide with a join probe.
    properties = (server / "server.properties").read_text()
    # The Phase-D scope admits the VANILLA overworld generator; the pack's
    # forge.cfg may default the world type to a modded generator. This world
    # is ours and disposable: state the vanilla default explicitly.
    (server / "server.properties").write_text(
        properties.replace("server-port=25599", "server-port=%d" % PORT)
        + ("level-type=DEFAULT" + chr(10) if args.admitted else ""))

    smoke_out = out / "live-shadow-events.jsonl"
    extra = [
        "-Drustcraft.liveShadowOut=" + str(smoke_out),
        "-Drustcraft.liveShadowDll=" + str(server / "rustcraft_ffi.dll"),
        "-Drustcraft.liveShadowJournal=" + str(out / "shadow-journal.jsonl"),
        "-Drustcraft.liveShadowScope=OVERWORLD_PER_CHUNK",
        "-Drustcraft.liveShadowMaxEvents=%d" % args.max_events,
        "-Drustcraft.liveShadowQueueCapacity=%d" % args.queue_capacity,
        "-Drustcraft.liveShadowQueueMaxBytes=%d" % args.queue_max_bytes,
        "-Drustcraft.observationDir=" + str(server / "observation"),
    ]
    if args.admitted:
        extra = ["-Drustcraft.session.processId=" + session["process_id"],
                 "-Drustcraft.session.transformationSessionId=" + session["session_id"],
                 "-Drustcraft.srgJar=" + str(args.srg_jar)] + extra
    if args.admitted:
        # The V2 session-bound admission launch shape: the tweaker (not the
        # coremod) bootstraps the session environment, entry observer and
        # writers, and the runtime states its own profile claim. The writers
        # must be ADMITTED for the session-bound plan before any shadow
        # capture can exist.
        import json as _json
        extra += ["-Drustcraft.profile=" + _json.loads(
            args.static_contract.joinpath("profile.json").read_text(encoding="utf-8"))["id"]
            if args.static_contract else "FORGE_2846_FTB_REVELATION_3_4_0_SERVER_TRANSFORMED_OFFLINE_V1"]

    jvm_log = out / "server.log"
    if args.admitted:
        import os as _os
        campaign = (server / "rustcraft-campaign.jar").resolve()
        classpath = [str(campaign), str(server / args.forge_jar), str(server / args.vanilla_jar)]
        classpath += [str(q) for q in sorted((server / "libraries").rglob("*.jar"))]
        argv = [str(JAVA), "-Xmx6G", "-javaagent:" + str(campaign),
                "-Dfml.queryResult=confirm",
                "-Drustcraft.liveWriterDiagnostic=true"] + extra + [
            "-cp", _os.pathsep.join(classpath),
            "net.minecraft.launchwrapper.Launch", "--tweakClass",
            "com.rustcraft.coremod.LiveSessionAdmissionTweaker", "--gameDir", str(server)]
        process = subprocess.Popen(argv, cwd=str(server), stdout=jvm_log.open("wb"),
                                   stderr=subprocess.STDOUT, stdin=subprocess.PIPE)
    else:
        process = launch(server, jvm_log, srg_jar=args.srg_jar, session=session,
                         forge_jar_name=args.forge_jar, vanilla_jar_name=args.vanilla_jar,
                         extra_java_args=extra)

    receipt: dict = {
        "schema": "RUSTCRAFT_V2_PHASE_D_LIVE_SHADOW_SMOKE_V1",
        "runtime_root": str(args.runtime_root),
        "campaign_jar_sha256": sha(args.campaign_jar),
        "dll_sha256": sha(args.dll),
        "session": session,
        "bounds": {"max_events": args.max_events,
                   "queue_capacity": args.queue_capacity,
                   "queue_max_bytes": args.queue_max_bytes,
                   "stability_s": args.stability_s,
                   "post_done_settle_s": args.post_done_settle_s},
        "production_authority": False,
    }

    booted = wait_for(jvm_log, r"Done \([0-9.]+s\)", args.boot_timeout_s)
    receipt["server_booted"] = booted
    if not booted:
        stop(process, timeout_s=120)
        receipt["verdict"] = "FAIL"
        receipt["failure"] = "server did not reach Done"
        (out / "smoke-receipt.json").write_text(json.dumps(receipt, indent=2, sort_keys=True) + "\n")
        return 1

    if args.post_done_settle_s > 0:
        time.sleep(args.post_done_settle_s)

    client_mods: list[tuple[str, str]] = []
    if args.mod_versions:
        log_text = jvm_log.read_text(encoding="utf-8", errors="replace")
        inventory = re.search(r"missing mods \[([^\]]+)\]", log_text)
        document = json.loads(args.mod_versions.read_text(encoding="utf-8"))
        derived = document.get("versions", document)
        builtins = {"minecraft": "1.12.2", "FML": "8.0.99.99",
                    "forge": args.forge_jar.split("-")[-1].replace(".jar", ""),
                    "mcp": "9.42"}
        if inventory is not None:
            unversioned = []
            for name in inventory.group(1).split(","):
                modid = name.strip()
                version = derived.get(modid, builtins.get(modid))
                if version is None:
                    unversioned.append(modid)
                    continue
                client_mods.append((modid, version))
            if unversioned:
                stop(process, timeout_s=120)
                receipt["verdict"] = "FAIL"
                receipt["failure"] = "unversioned modids: " + ", ".join(unversioned)
                (out / "smoke-receipt.json").write_text(
                    json.dumps(receipt, indent=2, sort_keys=True) + "\n")
                return 1

    probe = run_probe("127.0.0.1", PORT, args.username, expect_forge=True,
                      stability_s=args.stability_s, client_mods=client_mods)
    (out / "probe.json").write_text(json.dumps(probe, indent=2, sort_keys=True) + "\n")

    stop(process)
    time.sleep(3)

    # ---- collect the shadow pipeline's own records ------------------------
    taxonomy_counts = {o.value: 0 for o in taxonomy.ALL}
    exclusions: dict[str, int] = {}
    drops: dict[str, int] = {}
    contract_sha = None
    if (out / "shadow-journal.jsonl").exists():
        for line in (out / "shadow-journal.jsonl").read_text(
                encoding="utf-8", errors="replace").splitlines():
            line = line.strip()
            if not line.startswith("{"):
                continue
            try:
                record = json.loads(line)
            except ValueError:
                continue
            outcome = record.get("outcome")
            if outcome in taxonomy_counts:
                taxonomy_counts[outcome] += 1
            if outcome == "EXCLUDED":
                exclusions[record.get("reason", "?")] = \
                    exclusions.get(record.get("reason", "?"), 0) + 1
            if outcome == "DROPPED":
                drops[record.get("reason", "?")] = \
                    drops.get(record.get("reason", "?"), 0) + 1
            if record.get("reason") == "journal-bound":
                contract_sha = json.loads(record.get("detail", "{}")).get("sha256")

    denominator = taxonomy_counts["COMPARE_PASS"] + taxonomy_counts["COMPARE_MISMATCH"]
    validation_counts = dict(taxonomy_counts)
    validation_counts["observed"] = sum(taxonomy_counts.values())
    try:
        taxonomy.validate_counts(validation_counts)
        taxonomy_valid = True
    except Exception as failure:
        taxonomy_valid = False
        receipt["taxonomy_validation_error"] = str(failure)

    consumer_receipt = None
    if (out / "live-shadow-receipt.json").exists():
        try:
            consumer_receipt = json.loads(
                (out / "live-shadow-receipt.json").read_text(encoding="utf-8"))
        except ValueError:
            consumer_receipt = None

    receipt["probe"] = {k: probe.get(k) for k in
                        ("verdict", "checks", "failure", "packets_in", "bytes_in")}
    receipt["channels"] = probe.get("channels")
    receipt["taxonomy"] = taxonomy_counts
    receipt["parity_denominator"] = denominator
    receipt["parity_rate"] = (taxonomy_counts["COMPARE_PASS"] / denominator
                              if denominator else None)
    receipt["exclusion_reasons"] = exclusions
    receipt["drop_reasons"] = drops
    receipt["taxonomy_validated"] = taxonomy_valid
    receipt["contract_sha256"] = contract_sha
    if consumer_receipt is not None:
        receipt["consumer_receipt"] = consumer_receipt
    # The channel classification keeps its Phase-C window semantics verbatim.
    receipt["claim_limits"] = {
        "safe_noop": "no semantic reply was required during the observed "
                     "join/stability window; it does not prove the channel "
                     "never matters during gameplay",
        "client_inventory": "the headless client's derived inventory is test "
                            "qualification infrastructure, not a production "
                            "adapter design",
        "timings": "component measurements only; no end-to-end performance claim",
    }
    receipt["production_authority"] = False
    receipt["verdict"] = "PASS" if (
        probe.get("verdict") == "PASS"
        and taxonomy_valid
        and taxonomy_counts["COMPARE_MISMATCH"] == 0
        and taxonomy_counts["INFRA_FAILURE"] == 0
        and denominator > 0
        and not consumer_receipt_get(consumer_receipt, "mismatchSeen")
    ) else "FAIL"
    (out / "smoke-receipt.json").write_text(json.dumps(receipt, indent=2, sort_keys=True) + "\n")
    print(json.dumps({
        "verdict": receipt["verdict"],
        "probe_verdict": probe.get("verdict"),
        "taxonomy": taxonomy_counts,
        "parity_denominator": denominator,
        "exclusions": exclusions,
        "drops": drops,
        "failure": probe.get("failure"),
    }, indent=2))
    return 0 if receipt["verdict"] == "PASS" else 1


def consumer_receipt_get(consumer_receipt, key):
    if consumer_receipt is None:
        return False
    return bool(consumer_receipt.get(key))


if __name__ == "__main__":
    raise SystemExit(main())
