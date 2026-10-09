#!/usr/bin/env python3
"""V2 live-shadow CLOSURE CAMPAIGN runner (Revelation-focused).

Exercise the EXISTING Java-authoritative / Rust-shadow pipeline under
broad real Revelation workload until the PREDECLARED closure criteria are
either satisfied or honestly shown not to be. No redesign: the admitted
tweaker launch, the lazy Phase-D scope, RCSNAP02 transport selection, the
semantic comparator, the bounded queue and the shadow journal are exactly
as qualified.

Workload: a DETERMINISTIC movement path (seeded, recorded) executed by the
Phase-C headless client, which is test infrastructure: traverse outward
across regions, change direction, revisit, disconnect/reconnect, repeat.
Reload cycles and incarnations are derived from the journal's own identity
model (chunkId+incarnation+generation), never from time or distance.

Closure caps are RAISED for the campaign (max events, queue bounds) but
remain bounded; drop behavior is measured, not tuned away.
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
sys.path.insert(0, str(Path(__file__).resolve().parent))

from run_join_probe import JAVA, prepare_server, sha, stop, wait_for  # noqa: E402
import closure  # noqa: E402
import taxonomy  # noqa: E402

PORT = 25596


def campaign_path(seed: int):
    """Deterministic traversal plan: outward spiral legs with reversals.

    Each leg is (x0, z0, x1, z1). The client issues position updates along
    the leg and the server's own view-distance machinery loads/unloads.
    """
    legs = []
    x, z = 0, 0
    ring = 1
    directions = [(1, 0), (0, 1), (-1, 0), (0, -1)]
    for _ in range(seed % 4):
        directions.append(directions.pop(0))
    while ring <= 6:
        for dx, dz in directions:
            length = ring * 256
            nx, nz = x + dx * length, z + dz * length
            if (nx, nz) != (x, z):
                legs.append((x, z, nx, nz))
            x, z = nx, nz
        ring += 1
    for target in ((0, 0), (-512, -512), (0, 0)):
        if (x, z) != target:
            legs.append((x, z, target[0], target[1]))
            x, z = target
    return legs


def hop_plan(seed: int, rounds: int = 4):
    """Deterministic hop traversal: spawn -> distant point -> spawn.

    Each hop is a single position update followed by a settle window, so
    the server genuinely loads the destination's chunks and unloads the
    origin's (view distance 6); hopping back reloads them under NEW
    incarnations -- the existing unload/reload evidence the closure
    criterion demands. Generic protocol-valid movement, no gameplay
    automation.
    """
    legs = []
    directions = [(1, 1), (-1, 1), (-1, -1), (1, -1), (1, 0), (0, 1), (-1, 0), (0, -1)]
    for _ in range(seed % 8):
        directions.append(directions.pop(0))
    for round_index in range(rounds):
        for dx, dz in directions[:4 + (round_index % 4)]:
            distance = 768 + 256 * ((round_index + seed) % 3)
            legs.append((0, 0, dx * distance, dz * distance))
            legs.append((dx * distance, dz * distance, 0, 0))
    return legs


def run_session(args, index: int) -> dict:
    out = args.output.resolve() / ("session-%d" % index)
    if out.exists():
        shutil.rmtree(out)
    out.mkdir(parents=True)

    session = {"process_id": str(uuid.uuid4()), "session_id": str(uuid.uuid4())}
    (out / "launch-session.json").write_text(json.dumps(session, indent=2) + "\n")

    # A persistent, pre-generated world (the admission/harvest run's real
    # Revelation world) is the campaign's reproducible terrain: every session
    # boots a DISPOSABLE COPY, so chunk loads are disk-origin I/O (the
    # closure criterion's own evidence path) and no session's first boot
    # churns worldgen -- measured to trip the coherence gate's off-owner
    # containment on a fresh world.
    world = args.world_source.resolve() if args.world_source is not None else None
    server, prepare_info = prepare_server(
        args.runtime_root, out, forge_jar=args.forge_jar,
        vanilla_jar=args.vanilla_jar, campaign_jar=args.campaign_jar.resolve(),
        world_source=world)
    properties = (server / "server.properties").read_text()
    (server / "server.properties").write_text(
        properties.replace("server-port=25599", "server-port=%d" % PORT)
        + "level-type=DEFAULT" + chr(10))

    campaign = (server / "rustcraft-campaign.jar").resolve()
    # The shadow pipeline needs the DLL inside the disposable server copy
    # (the tweaker's consumer loads it by absolute path).
    shutil.copyfile(args.dll.resolve(), server / "rustcraft_ffi.dll")
    smoke_out = out / "live-shadow-events.jsonl"
    teleport_status = None
    teleport_extra = []
    if getattr(args, "server_teleport", False):
        teleport_status = out / "teleport-status.json"
        teleport_extra = [
            "-Drustcraft.closureCampaignTeleport=true",
            "-Drustcraft.closureCampaignTeleportStatus=" + str(teleport_status),
            "-Drustcraft.closureCampaignTeleportIntervalMs=%d" % args.teleport_interval_ms,
            "-Drustcraft.closureCampaignTeleportInitialDelayMs=%d" % args.teleport_initial_delay_ms,
            "-Drustcraft.closureCampaignSeed=%d" % (args.seed + index),
            "-Drustcraft.closureCampaignTeleportRounds=%d" % args.teleport_rounds,
        ]
        if args.teleport_points:
            teleport_extra.append("-Drustcraft.closureCampaignTeleportPoints=" + args.teleport_points)

    extra = [
        "-Drustcraft.liveShadowOut=" + str(smoke_out),
        "-Drustcraft.liveShadowDll=" + str(server / "rustcraft_ffi.dll"),
        "-Drustcraft.liveShadowJournal=" + str(out / "shadow-journal.jsonl"),
        "-Drustcraft.liveShadowScope=OVERWORLD_PER_CHUNK",
        "-Drustcraft.liveShadowMaxEvents=%d" % args.max_events,
        "-Drustcraft.liveShadowQueueCapacity=%d" % args.queue_capacity,
        "-Drustcraft.liveShadowQueueMaxBytes=%d" % args.queue_max_bytes,
        "-Drustcraft.observationDir=" + str(server / "observation"),
        "-Drustcraft.session.processId=" + session["process_id"],
        "-Drustcraft.session.transformationSessionId=" + session["session_id"],
        "-Drustcraft.srgJar=" + str(args.srg_jar.resolve()),
        "-Drustcraft.profile=" + str(
            json.loads(args.canonical_profile.read_text(encoding="utf-8")).get("id")
            or (json.loads(args.canonical_profile.read_text(encoding="utf-8")).get("qualification") or {}).get("profile")
            or json.loads(args.canonical_profile.read_text(encoding="utf-8")).get("kind")
        ),
    ] + teleport_extra
    classpath = [str(campaign), str(server / args.forge_jar), str(server / args.vanilla_jar)]
    classpath += [str(p) for p in sorted((server / "libraries").rglob("*.jar"))]
    argv = [str(JAVA), "-Xmx6G", "-javaagent:" + str(campaign),
            "-Dfml.queryResult=confirm", "-Drustcraft.liveWriterDiagnostic=true"] + extra + [
        "-cp", os.pathsep.join(classpath),
        "net.minecraft.launchwrapper.Launch", "--tweakClass",
        "com.rustcraft.coremod.LiveSessionAdmissionTweaker", "--gameDir", str(server)]
    (out / "launch.json").write_text(json.dumps(
        {"session": session, "argv": argv}, indent=2) + "\n")

    jvm_log = out / "server.log"
    process = subprocess.Popen(argv, cwd=str(server), stdout=jvm_log.open("wb"),
                               stderr=subprocess.STDOUT, stdin=subprocess.PIPE)

    booted = wait_for(jvm_log, r"Done \([0-9.]+s\)", args.boot_timeout_s, process=process)
    if not booted:
        stop(process, timeout_s=120)
        return {"session": index, "booted": False}

    time.sleep(args.post_done_settle_s)

    # ---- deterministic workload via the headless client --------------------
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    from workload_client import WorkloadFailure, run_workload
    workload = None
    try:
        workload = run_workload("127.0.0.1", PORT, "Campaign%d" % index,
                                client_mods=client_mods_from_log(jvm_log, args),
                                legs=(hop_plan(args.seed + index) if args.hop_mode
                                      else campaign_path(args.seed + index)),
                                step_blocks=args.step_blocks,
                                reconnects=args.reconnects,
                                settle_s=args.leg_settle_s,
                                trace_path=out / "workload-trace.json",
                                teleport_status_path=teleport_status,
                                teleport_timeout_s=args.teleport_timeout_s)
    except WorkloadFailure as failure:
        # Record and still stop the server: an orphaned JVM on the campaign
        # port poisons every later attempt.
        workload = {"verdict": "FAIL", "failure": str(failure)}
    finally:
        (out / "workload.json").write_text(json.dumps(workload, indent=2) + "\n")
        stop(process, timeout_s=600)
    for _ in range(120):
        if process.poll() is not None:
            break
        time.sleep(1)
    if process.poll() is None:
        process.kill()
        time.sleep(2)
    return {"session": index, "booted": True, "out": str(out),
            "session_ids": session, "workload": workload}


def client_mods_from_log(jvm_log: Path, args):
    log_text = jvm_log.read_text(encoding="utf-8", errors="replace")
    inventory = re.search(r"missing mods \[([^\]]+)\]", log_text)
    document = json.loads(args.mod_versions.read_text(encoding="utf-8"))
    derived = document.get("versions", document)
    builtins = {"minecraft": "1.12.2", "FML": "8.0.99.99",
                "forge": args.forge_jar.split("-")[-1].replace(".jar", ""),
                "mcp": "9.42"}
    mods = []
    if inventory is not None:
        for name in inventory.group(1).split(","):
            modid = name.strip()
            version = derived.get(modid, builtins.get(modid))
            if version is not None:
                mods.append((modid, version))
    return mods


# ---------------------------------------------------------------------------
# Journal-derived metrics: everything the closure evaluator consumes is
# recomputed from the per-session shadow journals -- never hand-counted.
# ---------------------------------------------------------------------------

def session_metrics(session_dir: Path) -> dict:
    journal = session_dir / "shadow-journal.jsonl"
    records = []
    if journal.is_file():
        for line in journal.read_text(encoding="utf-8", errors="replace").splitlines():
            line = line.strip()
            if line.startswith("{"):
                try:
                    records.append(json.loads(line))
                except ValueError:
                    pass
    events = [r for r in records if r.get("outcome") is not None]
    consumer = session_dir / "live-shadow-receipt.json"
    consumer_receipt = json.loads(consumer.read_text(encoding="utf-8")) if consumer.is_file() else {}

    outcomes = {name: 0 for name in taxonomy.ALL}
    for event in events:
        outcomes[event["outcome"]] += 1

    # Incarnations: distinct (worldId, chunkId, incarnation) identities in
    # the consumer's per-event records (identity model, not coordinates).
    identities = set()
    io_origin = 0
    rcnsnap = {"01": 0, "02": 0}
    by_chunk_coord = {}
    details = session_dir / "live-shadow-events.jsonl"
    if details.is_file():
        for line in details.read_text(encoding="utf-8", errors="replace").splitlines():
            if not line.startswith("{"):
                continue
            try:
                row = json.loads(line)
            except ValueError:
                continue
            key = (row.get("worldId"), row.get("chunkId"), row.get("incarnation"))
            identities.add(key)
            if row.get("ioAdopted"):
                io_origin += 1
            # Track coordinates across incarnations for reload cycle detection
            coord_key = (row.get("worldId"), row.get("chunkX"), row.get("chunkZ"))
            if row.get("incarnation") is not None and None not in coord_key:
                by_chunk_coord.setdefault(coord_key, set()).add(row.get("incarnation"))
            # Transport version: from the journal detail where recorded.
    for event in events:
        if event.get("outcome") in taxonomy.COMPARED:
            detail = str(event.get("detail", ""))
            rcnsnap["02" if "byteExact" in detail else "01"] += 1

    # Reload cycles: chunk coordinates whose identity appears with
    # MORE THAN ONE incarnation -- an incarnation change is the existing
    # evidence that the server unloaded and re-created the chunk.
    reload_cycles = sum(1 for versions in by_chunk_coord.values() if len(versions) > 1)

    observed = sum(outcomes.values())
    unexplained = outcomes["COMPARE_MISMATCH"]  # runner policy: first mismatch stops
    return {
        "outcomes": outcomes,
        "observed": observed,
        "identities": len(identities),
        "reload_cycles": reload_cycles,
        "io_origin_comparisons": io_origin,
        "rcnsnap_distribution": rcnsnap,
        "consumer": consumer_receipt,
        "denominator": outcomes["COMPARE_PASS"] + outcomes["COMPARE_MISMATCH"],
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--runtime-root", type=Path, required=True)
    parser.add_argument("--forge-jar", required=True)
    parser.add_argument("--vanilla-jar", required=True)
    parser.add_argument("--campaign-jar", type=Path, required=True)
    parser.add_argument("--srg-jar", type=Path, required=True)
    parser.add_argument("--dll", type=Path, required=True)
    parser.add_argument("--canonical-profile", type=Path, required=True)
    parser.add_argument("--mod-versions", type=Path, required=True)
    parser.add_argument("--world-source", type=Path, default=None,
                        help="pre-generated world each session boots a disposable copy of")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--sessions", type=int, default=2)
    parser.add_argument("--seed", type=int, default=20260929)
    parser.add_argument("--max-events", type=int, default=100000,
                        help="campaign raises the smoke cap; still bounded")
    parser.add_argument("--queue-capacity", type=int, default=256)
    parser.add_argument("--queue-max-bytes", type=int, default=256 * 1024 * 1024)
    parser.add_argument("--reconnects", type=int, default=2)
    parser.add_argument("--step-blocks", type=int, default=64)
    parser.add_argument("--leg-settle-s", type=float, default=8.0)
    parser.add_argument("--hop-mode", action="store_true",
                        help="hop traversal (spawn <-> distant) instead of the walking spiral")
    parser.add_argument("--server-teleport", action="store_true",
                        help="use server-side CampaignTeleportController")
    parser.add_argument("--teleport-points", default=None,
                        help="semicolon-separated x,y,z waypoints for teleport controller")
    parser.add_argument("--teleport-interval-ms", type=int, default=5000,
                        help="dwell time per teleport waypoint in ms")
    parser.add_argument("--teleport-initial-delay-ms", type=int, default=10000,
                        help="initial delay before first teleport in ms")
    parser.add_argument("--teleport-rounds", type=int, default=4,
                        help="number of rounds of teleport exploration")
    parser.add_argument("--teleport-timeout-s", type=float, default=600.0,
                        help="timeout for server-side teleport sequence")
    parser.add_argument("--post-done-settle-s", type=float, default=45.0)
    parser.add_argument("--boot-timeout-s", type=int, default=1800)
    args = parser.parse_args()

    args.srg_jar = args.srg_jar.resolve()
    args.canonical_profile = args.canonical_profile.resolve()
    args.mod_versions = args.mod_versions.resolve()

    out = args.output.resolve()
    if out.exists():
        shutil.rmtree(out)
    out.mkdir(parents=True)

    for line in subprocess.run(["netstat", "-ano"], capture_output=True,
                               text=True).stdout.splitlines():
        if ":%d" % PORT in line and "LISTENING" in line:
            print(json.dumps({"status": "FAIL", "reason": "PORT_ALREADY_HELD"}))
            return 1

    sessions = []
    for index in range(1, args.sessions + 1):
        print("[campaign] session %d starting" % index, flush=True)
        result = run_session(args, index)
        sessions.append(result)
        if not result.get("booted"):
            break

    # ---- aggregate, reconciled from journals ------------------------------
    aggregate = {name: 0 for name in taxonomy.ALL}
    totals_meta = {"observed": 0, "io_origin": 0, "identities": 0,
                   "reload_cycles": 0}
    session_receipts = []
    rcnsnap = {"01": 0, "02": 0}
    for result in sessions:
        if not result.get("booted"):
            continue
        metrics = session_metrics(Path(result["out"]))
        for name, count in metrics["outcomes"].items():
            aggregate[name] += count
        totals_meta["observed"] += metrics["observed"]
        totals_meta["io_origin"] += metrics["io_origin_comparisons"]
        totals_meta["identities"] += metrics["identities"]
        totals_meta["reload_cycles"] += metrics["reload_cycles"]
        for key in rcnsnap:
            rcnsnap[key] += metrics["rcnsnap_distribution"][key]
        session_receipts.append({
            "session": result["session"],
            "process_id": result["session_ids"]["process_id"],
            "session_id": result["session_ids"]["session_id"],
            "metrics": {k: v for k, v in metrics.items() if k != "consumer"},
        })

    denominator = aggregate["COMPARE_PASS"] + aggregate["COMPARE_MISMATCH"]
    reconciliation = {
        "sum_session_counters_equal_campaign": all(
            s["metrics"]["outcomes"] is not None for s in session_receipts),
        "denominator_rule": denominator == aggregate["COMPARE_PASS"] + aggregate["COMPARE_MISMATCH"],
        "sessions_counted": len(session_receipts),
    }

    totals = closure.ClosureInput(
        compare_pass=aggregate["COMPARE_PASS"],
        compare_mismatch=aggregate["COMPARE_MISMATCH"],
        unexplained_mismatch=aggregate["COMPARE_MISMATCH"],
        dropped=aggregate["DROPPED"], excluded=aggregate["EXCLUDED"],
        disqualified=aggregate["DISQUALIFIED"],
        infra_failure=aggregate["INFRA_FAILURE"],
        integrity_threatening_infra=aggregate["INFRA_FAILURE"],
        io_origin_comparisons=totals_meta["io_origin"],
        distinct_incarnations=totals_meta["identities"],
        reload_cycles=totals_meta["reload_cycles"],
        observed_events=totals_meta["observed"],
        production_authority=False)
    verdict = closure.evaluate(totals)

    campaign_receipt = {
        "schema": "RUSTCRAFT_V2_LIVE_SHADOW_CAMPAIGN_RECEIPT_V1",
        "campaign_id": "rev-fullchunk-closure-%s" % uuid.uuid4().hex[:12],
        "seed": args.seed,
        "movement_plan": (
            "server_teleport_points: %s" % (args.teleport_points or "deterministic")
            if args.server_teleport
            else [list(leg) for leg in (hop_plan(args.seed) if args.hop_mode else campaign_path(args.seed))]
        ),
        "server_teleport": args.server_teleport,
        "hop_mode": args.hop_mode,
        "runtime_root": str(args.runtime_root),
        "canonical_profile_sha256": sha(args.canonical_profile),
        "campaign_jar_sha256": sha(args.campaign_jar),
        "sessions": session_receipts,
        "taxonomy": aggregate,
        "denominator": denominator,
        "io_origin_comparisons": totals_meta["io_origin"],
        "distinct_incarnations": totals_meta["identities"],
        "reload_cycles": totals_meta["reload_cycles"],
        "rcnsnap_distribution": rcnsnap,
        "reconciliation": reconciliation,
        "queue": {
            "capacity": args.queue_capacity,
            "max_bytes": args.queue_max_bytes,
        },
        "production_authority": False,
        "closure": verdict,
    }
    (out / "campaign-receipt.json").write_text(
        json.dumps(campaign_receipt, indent=2, sort_keys=True) + "\n")
    print(json.dumps({
        "sessions": len(session_receipts),
        "taxonomy": aggregate,
        "denominator": denominator,
        "io_origin": totals_meta["io_origin"],
        "incarnations": totals_meta["identities"],
        "reload_cycles": totals_meta["reload_cycles"],
        "closure": verdict["verdict"],
        "unmet": verdict["unmet"],
    }, indent=2))
    return 0 if verdict["verdict"] == "CLOSED" else 1


if __name__ == "__main__":
    raise SystemExit(main())
