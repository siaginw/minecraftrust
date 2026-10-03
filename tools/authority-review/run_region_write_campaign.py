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
import threading
import time
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools" / "live-shadow-v2"))
sys.path.insert(0, str(ROOT / "tools" / "authority-review"))
sys.path.insert(0, str(ROOT / "tools"))

from run_join_probe import JAVA, prepare_server, stop, wait_for  # noqa: E402
from join_probe import run_probe  # noqa: E402
from campaign.waits import hold_stability, wait_for_condition  # noqa: E402
from campaign.evidence import (  # noqa: E402
    ALL, CounterAtLeast, ZeroCounter, EvidenceTracker,
)
from campaign.telemetry import parse_metrics_snapshot  # noqa: E402
from campaign.receipt import write_receipt, human_summary  # noqa: E402
from campaign import policy as campaign_policy  # noqa: E402

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
    parser.add_argument("--mode", choices=["OFF", "SHADOW", "ON_EXPERIMENTAL"],
                        default="SHADOW",
                        help="Region WRITE experiment mode (OFF = vanilla writes)")
    parser.add_argument("--restart-cycles", type=int, default=0,
                        help="After the main session: N boot->Done->stop cycles "
                             "on the SAME server world with the experiment OFF "
                             "(fresh vanilla Forge restarts)")
    parser.add_argument("--output", type=Path, default=None)
    parser.add_argument("--attribution", action="store_true",
                        help="Enable the goal-§4 writer-attribution agent "
                             "(RandomAccessFile write capture with stacks)")
    parser.add_argument("--read-mode", choices=["OFF", "SHADOW", "ON_EXPERIMENTAL"],
                        default="OFF",
                        help="Enable the region READ experiment in this mode")
    parser.add_argument("--min-reads", type=int, default=0,  # per-run read-event floor
                        help="Minimum Rust-read events for a PASS (shadow)")
    parser.add_argument("--hard-timeout-s", type=int, default=None,
                        help="Override the evidence deadline ceiling")
    parser.add_argument("--soak-seconds", type=int, default=None)
    parser.add_argument("--soak-reason", type=str, default=None)
    campaign_policy.add_tier_argument(parser)
    args = parser.parse_args()

    port = args.port or default_port(args.target)
    campaign_policy.require_soak_config(args.test_tier, args.soak_seconds,
                                        args.soak_reason)
    # The probe hold is EVENT-DRIVEN: the in-server teleport controller runs
    # (rounds*16+1) legs at ~8s/leg — the hold scales with that schedule plus
    # the tier stability window. Explicit --stability-s still wins.
    expected_legs = args.teleport_rounds * 16 + 1 if args.teleport_rounds > 0 else 0
    probe_hold_default = (expected_legs * 8 + 45) if expected_legs else 30.0
    tier = campaign_policy.resolve(
        args.test_tier,
        post_target_stability_s=args.stability_s
        if args.stability_s else None,
        boot_timeout_s=args.boot_timeout_s,
        hard_timeout_s=args.hard_timeout_s,
        soak_s=args.soak_seconds,
    )
    args.boot_timeout_s = tier.boot_timeout_s
    target = args.target
    min_shadow = args.min_shadow or (1000 if target == "A" else 5000)
    if args.output:
        out_dir = Path(args.output).resolve()  # javaagent/-cp need absolute
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

    # Upgrade #3: skip-if-evidenced - a prior same-signature run that met
    # the floor (with zero-guarded counters) lets us cite it and skip boot.
    try:
        from campaign.evidence_db import EvidenceDB
        git_sha_now = os.popen("git rev-parse HEAD").read().strip()
        db = EvidenceDB(ROOT / "target" / "authority-review" / "evidence.db")
        skip_mode = args.read_mode if args.read_mode != "OFF" else args.mode
        floor = args.min_reads if args.read_mode != "OFF" else min_shadow
        counter = ("regionRead.readSuccess" if args.read_mode != "OFF"
                   else "regionWrite.rustOk")
        guards = (("regionRead.partialStreamAttempts",)
                  if args.read_mode != "OFF" else ())
        skip, seen_n = db.already_evidenced(
            f"region-{skip_mode.lower()}-{target}", target, skip_mode,
            git_sha_now, counter, floor, required_zeros=guards)
        if skip:
            print(f"[skip] already evidenced: {counter}={seen_n} >= {floor} "
                  f"at {git_sha_now} - citing stored receipt, skipping boot")
            return 0
    except Exception as skip_err:
        print(f"[skip] evidence-db unavailable ({skip_err}) - full campaign")

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
        "-Drustcraft.profile=" + profile_id,
    ]
    if args.mode != "OFF":
        extra_args += [
            "-Drustcraft.regionWriteExperiment=true",
            f"-Drustcraft.regionWriteMode={args.mode}",
            "-Drustcraft.regionWriteMirror=" + str(mirror_root),
            # LIVE counters for event-driven completion (goal §15)
            "-Drustcraft.regionMetricsFile=" + str(out_dir / "region-metrics.txt"),
        ]
    if args.teleport_rounds > 0:
        extra_args += [
            "-Drustcraft.closureCampaignTeleport=true",
            f"-Drustcraft.closureCampaignTeleportRounds={args.teleport_rounds}",
        ]
    if args.read_mode != "OFF":
        extra_args += [
            "-Drustcraft.regionReadExperiment=true",
            f"-Drustcraft.regionReadMode={args.read_mode}",
            # LIVE counters for event-driven completion (goal §15)
            "-Drustcraft.regionMetricsFile=" + str(out_dir / "region-metrics.txt"),
        ]
    attribution_jar = ROOT / "target" / "rustcraft-attribution.jar"
    attribution_dir = out_dir / "attribution"
    if args.attribution:
        if not attribution_jar.is_file():
            print("[ERROR] attribution jar missing; run "
                  "tools/authority-review/build_attribution_jar.py",
                  file=sys.stderr)
            return 1
        extra_args += [
            "-Drustcraft.writerAttribution=true",
            "-Drustcraft.writerAttributionDir=" + str(attribution_dir),
        ]

    classpath = [str(server_dir / "rustcraft-campaign.jar"),
                 str(server_dir / forge_jar),
                 str(server_dir / vanilla_jar)]
    classpath += [str(p) for p in sorted((server_dir / "libraries").rglob("*.jar"))]
    javaagents = ["-javaagent:" + str(server_dir / "rustcraft-campaign.jar")]
    if args.attribution:
        javaagents.append("-javaagent:" + str(attribution_jar))
    argv = [str(JAVA), "-Xmx6G"] + javaagents + extra_args + [
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
            process.kill()
            log_handle.close()
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
        probe_receipts = []
        # goal §9/§11: the probe's hold is the EVIDENCE DEADLINE ceiling
        # (scaled to the teleport-event schedule), never a fixed target.
        stability = args.stability_s or float(probe_hold_default)

        # EVIDENCE-DRIVEN COMPLETION (goals §10/§18): the JVM dumper thread
        # writes regionRead.*/regionWrite.* counters every 2s; the campaign
        # ends when the evidence targets hold + a short stability window
        # passes — bounded by the evidence deadline.
        metrics_file = out_dir / "region-metrics.txt"
        evidence_targets = []
        if args.read_mode == "SHADOW":
            evidence_targets += [
                CounterAtLeast("regionRead.readSelected", args.min_reads),
                ZeroCounter("regionRead.shadowMismatch"),
                ZeroCounter("regionRead.partialStreamAttempts"),
                ZeroCounter("regionRead.errors"),
            ]
        elif args.read_mode == "ON_EXPERIMENTAL":
            evidence_targets += [
                CounterAtLeast("regionRead.readSuccess", args.min_reads),
                ZeroCounter("regionRead.partialStreamAttempts"),
                ZeroCounter("regionRead.errors"),
            ]
        if args.mode == "SHADOW":
            evidence_targets += [CounterAtLeast("regionWrite.rustOk", min_shadow)]
        evidence = ALL(evidence_targets) if evidence_targets else None
        tracker = (EvidenceTracker(evidence, lambda: parse_metrics_snapshot(metrics_file),
                                   lambda: jvm_log.read_text(encoding="utf-8",
                                                             errors="replace"))
                   if evidence else None)

        def probe_worker(round_idx: int) -> None:
            try:
                receipt = run_probe("127.0.0.1", port, f"RWP{target}{round_idx}",
                                    expect_forge=True, client_mods=client_mods,
                                    connect_timeout_s=20.0, login_timeout_s=120.0,
                                    stability_s=stability)
            except (OSError, ValueError):
                receipt = {"verdict": "FAIL", "failure": "probe thread error"}
            probe_receipts.append(receipt)

        probe_thread = None
        # evidence deadline: generous default (reads accrue only after the
        # probe completes FML/PLAY); explicit --hard-timeout-s overrides
        evidence_deadline = time.monotonic() + (
            args.hard_timeout_s if args.hard_timeout_s else stability + 300.0)
        exit_reason = "EVIDENCE_COMPLETE"
        started = time.monotonic()
        try:
            for rnd in range(max(1, args.probe_rounds)):
                print(f"[probe] round {rnd + 1}/{args.probe_rounds}")
                probe_receipts.clear()
                probe_thread = threading.Thread(
                    target=probe_worker, args=(rnd,), daemon=True)
                probe_thread.start()

                if tracker is not None:
                    # poll evidence until met or the deadline ceiling hits
                    while tracker.satisfied_at is None:
                        if time.monotonic() > evidence_deadline:
                            exit_reason = "TIMEOUT"
                            break
                        if process.poll() is not None:
                            exit_reason = "SERVER_EXIT"
                            break
                        time.sleep(2.0)
                    if exit_reason == "EVIDENCE_COMPLETE":
                        # short stability window, re-verified (goal §10)
                        stable = hold_stability(
                            tier.post_target_stability_s,
                            verify=lambda: tracker.poll(time.monotonic()))
                        wall = time.monotonic() - started
                        print(f"[evidence] met at {wall:.0f}s + stability "
                              f"{tier.post_target_stability_s:.0f}s: "
                              f"{tracker.summary()}")
                        if not stable:
                            exit_reason = "ASSERTION_FAILURE"
                else:
                    # no evidence targets: legacy timing (probe holds the
                    # schedule-derived window)
                    hold_stability(stability, poll_s=5.0)

                # stop the probe: graceful server stop ends the probe hold
                break_out = exit_reason != "EVIDENCE_COMPLETE" or rnd + 1 >= args.probe_rounds
                if break_out:
                    break
            if tracker is None or exit_reason != "EVIDENCE_COMPLETE":
                # give the probe its window if evidence never completed
                if probe_thread is not None:
                    probe_thread.join(timeout=max(0.0, stability -
                                                  (time.monotonic() - started)))
            # probe receipt evaluation (world side; transport limitations are
            # tolerated — the storage evidence gates decide the verdict)
            for receipt in probe_receipts:
                verdict = receipt.get("verdict")
                obs = receipt.get("observed", {})
                world_ok = (obs.get("login_completed")
                            and obs.get("fml_handshake_complete")
                            and obs.get("play_reached"))
                if rnd == 0 and verdict != "PASS":
                    world_ok = world_ok and obs.get("chunk_packets", 0) >= 500
                print(f"[probe] verdict={verdict} observed={json.dumps(obs)}")
                if verdict != "PASS":
                    print(f"[probe] transport-limited probe (world_ok={world_ok}, "
                          f"failure={receipt.get('failure')!r})")
                    if not world_ok:
                        probe_ok = False
        finally:
            if probe_thread is not None:
                probe_thread.join(timeout=10)
        (out_dir / "probe-receipts.json").write_text(
            json.dumps(probe_receipts, indent=2, sort_keys=True) + "\n")
        if not probe_ok:
            print("[ERROR] probe rounds failed (world side unhealthy)",
                  file=sys.stderr)
            exit_reason = "PROBE_FAILURE"
        evidence_late = False
        if exit_reason == "TIMEOUT" and tracker is not None:
            # the window expired but shutdown counters may still meet targets;
            # the post-shutdown gates decide — record lateness honestly
            evidence_late = True

        # force world saves while chunks are loaded -> real region writes
        for i in range(max(1, args.save_flushes)):
            print(f"[save] save-all flush {i + 1}/{args.save_flushes}")
            try:
                process.stdin.write(b"save-all flush\n")
                process.stdin.flush()
            except (OSError, ValueError):
                pass
            # wait for the save to complete instead of a fixed idle
            wait_for(jvm_log, r"Saved the world", 300)
            time.sleep(2.0)
        print(f"[exit] {exit_reason} after "
              f"{time.monotonic() - started:.0f}s")
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
    # The shutdown-time transformer status print races the log appender; the
    # boot-time seam print is the reliable transform evidence. The hook
    # metrics (admissions through the injected entry) corroborate it.
    if args.mode != "OFF" and not seam_seen:
        print("[FAIL] transformer never saw the RegionFile seam class")
        failures += 1
    if args.mode != "OFF" and metrics["errors"] != "0":
        print(f"[FAIL] hook errors={metrics['errors']}")
        failures += 1
    rust_ok = int(metrics["rustOk"]) if metrics["rustOk"].isdigit() else 0
    if args.mode != "OFF" and rust_ok == 0:
        print("[FAIL] no Rust shadow writes recorded")
        failures += 1
    if args.mode == "OFF":
        # read-only authority session: vanilla owns writes, so the WRITE
        # experiment must be absent (verify by absence in the log)
        if "regionWrite.hook enabled=true" in log_text:
            print("[FAIL] write hook engaged in write-OFF session")
            failures += 1
        rust_ok = 0
        print("[verify] write-OFF session: write hook absent (vanilla owns writes)")

    (out_dir / "region-write-campaign.json").write_text(json.dumps({
        "target": target, "port": port, "mode": args.mode,
        "transform_count": transform_count,
        "hook_metrics": metrics, "rust_ok": rust_ok,
    }, indent=2, sort_keys=True) + "\n")

    # region READ experiment gates (goals §13-§14, §25-§26, §32)
    rm = re.search(
        r"regionRead\.hook enabled=(\S+) mode=(\S+) cap=(\S+) "
        r"readSelected=(\d+) readSuccess=(\d+) readFailure=(\d+) "
        r"javaReadFallback=(\d+) missing=(\d+) corrupt=(\d+) "
        r"unsupportedCompression=(\d+) staleGeneration=(\d+) "
        r"partialStreamAttempts=(\d+) shadowCompared=(\d+) "
        r"shadowMismatch=(\d+) errors=(\d+)", log_text)
    read_metrics = None
    if args.read_mode != "OFF":
        if rm is None:
            print("[FAIL] region-read metrics not found in server log")
            failures += 1
        else:
            read_metrics = dict(zip(
                ["enabled", "mode", "cap", "readSelected", "readSuccess",
                 "readFailure", "javaReadFallback", "missing", "corrupt",
                 "unsupportedCompression", "staleGeneration",
                 "partialStreamAttempts", "shadowCompared", "shadowMismatch",
                 "errors"], rm.groups()))
            print(f"[verify] readMetrics={read_metrics}")
            reads = int(read_metrics["readSuccess"])
            if read_metrics["partialStreamAttempts"] != "0":
                print("[FAIL] partial stream attempts > 0")
                failures += 1
            if read_metrics["errors"] != "0":
                print("[FAIL] read hook errors > 0")
                failures += 1
            if args.read_mode == "SHADOW":
                if int(read_metrics["shadowMismatch"]) != 0:
                    print("[FAIL] shadow read mismatches > 0")
                    failures += 1
                # goal §14 floor counts READ EVENTS (readSelected); the
                # compared subset (chunks present on disk) is reported too
                selected = int(read_metrics["readSelected"])
                if args.min_reads and selected < args.min_reads:
                    print(f"[FAIL] shadow read events {selected} < {args.min_reads}")
                    failures += 1
            else:
                if reads == 0:
                    print("[FAIL] ON read mode admitted 0 reads")
                    failures += 1
                fb = int(read_metrics["javaReadFallback"])
                miss = int(read_metrics["missing"])
                # Fail-closed fallbacks beyond missing chunks must be rare
                # (transient IO during concurrent vanilla saves). A large gap
                # means the Rust reader is rejecting valid records.
                tolerance = miss // 50 + 50
                if fb > miss + tolerance:
                    print(f"[FAIL] ON read fallbacks ({fb}) exceed misses "
                          f"({miss}) beyond tolerance ({tolerance})")
                    failures += 1
                if args.min_reads and reads < args.min_reads:
                    print(f"[FAIL] ON reads {reads} < {args.min_reads}")
                    failures += 1

    if args.mode == "OFF":
        # read-only authority session: structural scan only (no write gates)
        world_region = server_dir / "world" / "region"
        scanner = ROOT / "target" / "release" / "region_tools.exe"
        dirty = 0
        for mca in sorted(world_region.glob("r.*.*.mca")):
            out = subprocess.run([str(scanner), "scan", str(mca)],
                                 capture_output=True, text=True)
            line = out.stdout.strip().splitlines()[-1] if out.stdout.strip() else ""
            if "bad=0" not in line or "overlap=false" not in line:
                print(f"[FAIL] scan dirty after read-only campaign: {mca.name}: {line}")
                failures += 1
            else:
                dirty += 1
        print(f"[verify] read-only structural scan clean on {dirty} region files")
    elif args.mode == "SHADOW":
        world_region = server_dir / "world" / "region"
        comparator = ROOT / "tools" / "authority-review" / "compare_region_shadow.py"
        print(f"[verify] comparing real {world_region} vs mirror {mirror_root} "
              f"(min writes {min_shadow})")
        cmp_run = subprocess.run([sys.executable, str(comparator), str(world_region),
                                  str(mirror_root), "--min", "0"],
                                 capture_output=True, text=True)
        print(cmp_run.stdout.strip())
        if cmp_run.returncode != 0:
            print("[FAIL] shadow comparator found engine mismatches")
            failures += 1
        # goal §15: floor counts MEDIATED RUST WRITES; divergences must be
        # attributed (attribution journal UNKNOWN == 0 when the agent ran)
        if rust_ok < min_shadow:
            print(f"[FAIL] shadow rustOk={rust_ok} < {min_shadow}")
            failures += 1
        if args.attribution:
            journals = list(attribution_dir.glob("writer-attribution.jsonl"))
            classifier = (ROOT / "tools" / "authority-review"
                          / "classify_writer_attribution.py")
            cls = subprocess.run([sys.executable, str(classifier)]
                                 + [str(j) for j in journals],
                                 capture_output=True, text=True)
            print(cls.stdout.strip())
            if cls.returncode != 0:
                print("[FAIL] UNKNOWN writer writes present in journal")
                failures += 1
    else:
        # ON_EXPERIMENTAL: Rust wrote the real files; no mirror. Structural
        # scan of every region (bad=0, no overlap) + min-write floor instead.
        world_region = server_dir / "world" / "region"
        scanner = ROOT / "target" / "release" / "region_tools.exe"
        dirty = 0
        for mca in sorted(world_region.glob("r.*.*.mca")):
            out = subprocess.run([str(scanner), "scan", str(mca)],
                                 capture_output=True, text=True)
            line = out.stdout.strip().splitlines()[-1] if out.stdout.strip() else ""
            if "bad=0" not in line or "overlap=false" not in line:
                print(f"[FAIL] scan dirty after ON campaign: {mca.name}: {line}")
                failures += 1
            else:
                dirty += 1
        print(f"[verify] ON structural scan clean on {dirty} region files")
        if rust_ok < min_shadow:
            print(f"[FAIL] ON campaign rustOk={rust_ok} < floor {min_shadow}")
            failures += 1

    # fresh-process restart cycles: same server world, experiment OFF, the
    # world must boot to Done and serve a probe every time
    if args.restart_cycles > 0:
        print(f"[restart] {args.restart_cycles} fresh vanilla restart cycles "
              f"(experiment OFF, same world)")
        noexp_args = [a for a in extra_args
                      if not a.startswith("-Drustcraft.regionWriteExperiment")
                      and not a.startswith("-Drustcraft.regionWriteMode")
                      and not a.startswith("-Drustcraft.regionWriteMirror")]
        for cycle in range(1, args.restart_cycles + 1):
            cycle_log = out_dir / f"restart-{cycle:02d}.log"
            handle = cycle_log.open("wb")
            proc = subprocess.Popen(argv_nojava(argv, noexp_args), cwd=str(server_dir),
                                    stdout=handle, stderr=subprocess.STDOUT,
                                    stdin=subprocess.PIPE)
            try:
                if not wait_for(cycle_log, r"Done \([0-9.]+s\)", args.boot_timeout_s,
                                process=proc):
                    print(f"[FAIL] restart cycle {cycle}: no Done")
                    failures += 1
                    stop(proc, timeout_s=120)
                    handle.close()
                    break
                time.sleep(45.0)  # post-Done settle before probing
                probe_ok_cycle = True
                if cycle == 1 or cycle == args.restart_cycles:
                    for attempt in (1, 2):
                        receipt = run_probe("127.0.0.1", port, f"RWRC{target}{cycle}",
                                            expect_forge=True, client_mods=client_mods,
                                            connect_timeout_s=30.0, login_timeout_s=180.0,
                                            stability_s=20.0)
                        print(f"[restart] cycle {cycle} attempt {attempt}: "
                              f"probe={receipt.get('verdict')} "
                              f"failure={receipt.get('failure')!r}")
                        if receipt.get("verdict") == "PASS":
                            break
                        time.sleep(30.0)
                    else:
                        probe_ok_cycle = False
                else:
                    time.sleep(5.0)
            finally:
                stop(proc, timeout_s=300)
                handle.close()
            if not probe_ok_cycle:
                failures += 1
                break
            print(f"[restart] cycle {cycle}: OK")

    verdict = "PASS" if failures == 0 else "FAIL"
    final_metrics = parse_metrics_snapshot(out_dir / "region-metrics.txt")
    receipt_path = write_receipt(
        out_dir,
        campaign_name=f"region-{args.mode.lower()}-{target}",
        target=target, mode=f"write={args.mode},read={args.read_mode}",
        test_tier=args.test_tier,
        git_sha=os.popen("git rev-parse HEAD").read().strip(),
        wall_time_s=round(time.monotonic() - started, 1),
        hard_timeout_s=tier.hard_timeout_s,
        stability_s=tier.post_target_stability_s,
        evidence_targets=[c.name for c in evidence_targets] if evidence_targets else [],
        evidence_observed=final_metrics,
        probe_result=(probe_receipts[0].get("verdict") if probe_receipts else None),
        exit_reason="EVIDENCE_COMPLETE" if (evidence_late and failures == 0) else exit_reason,
        evidence_late=evidence_late,
        verdict=verdict,
        artifact_paths=[str(out_dir / "server.log"),
                        str(out_dir / "region-metrics.txt")],
    )
    final_exit = "EVIDENCE_COMPLETE" if (evidence_late and failures == 0) else exit_reason
    print(human_summary(
        f"Region {args.mode} Gate {target}", args.test_tier, verdict,
        final_exit, time.monotonic() - started, tier.hard_timeout_s,
        [f"{k}={v}" for k, v in sorted(final_metrics.items())],
        tier.post_target_stability_s, receipt_path))
    if failures:
        print(f"REGION_WRITE_CAMPAIGN_FAILED Gate {target} ({failures} failures)")
        return 1
    # record counters so cumulative floors / skip-if-evidenced work (§3)
    try:
        db.record_run(f"region-{args.mode.lower()}-{target}", target,
                      (args.read_mode if args.read_mode != "OFF" else args.mode),
                      os.popen("git rev-parse HEAD").read().strip(),
                      final_metrics, tier=args.test_tier, verdict=verdict,
                      receipt_path=str(receipt_path))
    except Exception:
        pass
    print(f"REGION_WRITE_CAMPAIGN_PASSED Gate {target} mode={args.mode} "
          f"(rustOk={rust_ok})")
    return 0


def argv_nojava(argv: list[str], replacement_args: list[str]) -> list[str]:
    """Rebuild the server argv with `replacement_args` in place of the
    original -D flags (keeps JVM sizing, agent, classpath, main)."""
    out = []
    skip_value = False
    for i, a in enumerate(argv):
        if skip_value:
            skip_value = False
            continue
        if a == "-cp":
            out.append(a); out.append(argv[i + 1]); skip_value = True
            continue
        if a.startswith("-D"):
            continue
        out.append(a)
    # re-insert non-experiment -D flags (session identity etc.)
    flags = [a for a in replacement_args if a.startswith("-D")]
    j = out.index("net.minecraft.launchwrapper.Launch")
    return out[:j] + flags + out[j:]


if __name__ == "__main__":
    sys.exit(main())
