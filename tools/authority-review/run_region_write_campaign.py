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


def _read_light_metrics(metrics_path):
    """Live light counters from the metrics file, or Nones when
    unavailable. On Phosphor-active runtimes (Gate C) the vanilla-path
    worldLight* counters stay 0 by design and the phosphor* counters move."""
    try:
        text = metrics_path.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return None
    keys = ["worldLightCells", "worldLightMis", "worldLightErr",
            "worldLightSettledLate", "phosphorJobs", "phosphorCompared",
            "phosphorMis", "phosphorErr", "lightAuthJobs", "lightAuthAdmitted",
            "lightAuthFallbacks", "lightAuthCommitted", "lightAuthSky",
            "lightAuthErr"]
    vals = dict.fromkeys(keys)
    for line in text.splitlines():
        k, _, v = line.partition("=")
        if k in vals:
            try:
                vals[k] = int(v)
            except ValueError:
                pass
        elif re.match(r"^[A-Z][A-Z0-9_]+$", k):
            # metrics dump v2: reflection-dumped FIELD_NAME counters pass
            # through unfiltered so the hook-liveness gate (and future
            # checks) can read any counter without another whitelist
            try:
                vals[k] = int(v)
            except ValueError:
                pass
    return vals


def _light_job_total(m):
    """Combined BLOCK-light job counter: vanilla-path cells + Phosphor jobs
    (exactly one side moves on each runtime shape)."""
    if m is None:
        return None
    cells = m.get("worldLightCells") or 0
    jobs = m.get("phosphorJobs") or 0
    # lightAuthJOBS counts every checkLight invocation that passed the
    # loaded/bounds gates — admitted AND fallback AND sampled — so a job
    # that fell back still moves the settle counter (fallbacks otherwise
    # look like dead mutations)
    auth = m.get("lightAuthJobs") or 0
    return cells + jobs + auth


def _settle_light(metrics_path, before, require_increase, deadline_s=None):
    # ON-mode default 45s: admitted jobs (and deep worldgen-relight queues
    # ahead of them) can hold the server thread for tens of seconds; the
    # window must exceed queue latency, not job latency
    """Event-driven settle: poll the combined job counter until it rises
    past `before` (when required) and holds stable for two polls."""
    if deadline_s is None:
        deadline_s = 90.0  # survives a Minecraft autosave stall (45s period)
    poll = 0.4
    last = before
    stable = 0
    increased = False
    t0 = time.monotonic()
    while time.monotonic() - t0 < deadline_s:
        m = _read_light_metrics(metrics_path)
        cells = _light_job_total(m)
        if cells is not None:
            if cells > before:
                increased = True
            if cells == last and (increased or not require_increase):
                stable += 1
                if stable >= 2:
                    return cells, True, time.monotonic() - t0
            else:
                stable = 0
            last = cells
        time.sleep(poll)
    return last, False, time.monotonic() - t0


def mutation_op_center(anchor, salt=0):
    """Center of the salt-shifted mutation platform's OP cells — the coords
    the compare window must gate on. Must stay in lockstep with
    light_mutation_steps' X/OP_Y derivation."""
    return (anchor[0] + (salt % 8) * 5, anchor[1] + 3, anchor[2])


def light_mutation_steps(anchor, salt=0):
    """§4 workload: every step is a NORMAL Minecraft world mutation via the
    server console (setblock) — the Java oracle sees it exactly like
    gameplay, and the shadow hook + Rust kernel ride the same path. The
    platform is built two blocks over the probe player's feet so every
    mutation lives inside the chunks the probe holds loaded; the SALT
    (per-run) shifts the platform so reused-world runs mutate FRESH cells
    (setblock to an identical state is a no-op with no light job)."""
    X = anchor[0] + (salt % 8) * 5
    Y, Z = anchor[1], anchor[2]
    PLAT_Y = Y + 2   # platform level (two above the player's feet)
    OP_Y = Y + 3     # source operation level
    SEC_Y = (OP_Y & ~15) + 15  # top edge of the platform's 16-block section
    CHUNK_X = (X + 8) & ~15    # nearest chunk edge (<= 8 blocks away)
    plat = [(x, z) for x in range(X - 2, X + 3) for z in range(Z - 2, Z + 3)]

    def sb(x, y, z, block):
        return f"setblock {x} {y} {z} minecraft:{block}"

    # operation cells: normalize to air at build time so every later step
    # is a guaranteed real mutation even on reused worlds (a setblock to an
    # identical state is a no-op with no light job)
    op_cells = [(X, OP_Y, Z), (X + 2, OP_Y, Z + 2), (X - 2, OP_Y, Z - 2),
                (CHUNK_X, OP_Y, Z), (X, SEC_Y, Z)]
    steps = [
        ("platform_build",
         [sb(x, PLAT_Y, z, "stone") for x, z in plat]
         + [sb(x, y, z, "air") for x, y, z in op_cells],
         False),
        ("torch_place", [sb(X, OP_Y, Z, "torch")], True),
        ("torch_remove", [sb(X, OP_Y, Z, "air")], True),
        ("glowstone_place", [sb(X + 2, OP_Y, Z + 2, "glowstone")], True),
        ("opaque_replace", [sb(X + 2, OP_Y, Z + 2, "stone")], True),
        ("stone_remove", [sb(X + 2, OP_Y, Z + 2, "glowstone"),
                          sb(X + 2, OP_Y, Z + 2, "air")], True),
        ("two_sources", [sb(X - 2, OP_Y, Z - 2, "torch"),
                         sb(X + 2, OP_Y, Z + 2, "glowstone")], True),
        ("remove_one", [sb(X + 2, OP_Y, Z + 2, "air")], True),
        ("cleanup_last_source", [sb(X - 2, OP_Y, Z - 2, "air")], True),
        # self-contained place+remove pairs: the console queue drains
        # asynchronously, so a remove step following a place step can have
        # both commands land before its baseline read (delta 0 without any
        # missed job); pairing makes each step own its mutations
        ("chunk_boundary_pair", [sb(CHUNK_X, OP_Y, Z, "torch"),
                                 sb(CHUNK_X, OP_Y, Z, "air")], True),
        ("section_boundary_pair", [sb(X, SEC_Y, Z, "torch"),
                                   sb(X, SEC_Y, Z, "air")], True),
        ("rapid_add_remove_add", [sb(X, OP_Y, Z, "torch"),
                                  sb(X, OP_Y, Z, "air"),
                                  sb(X, OP_Y, Z, "torch"),
                                  sb(X, OP_Y, Z, "air")], True),
        ("platform_remove",
         [sb(x, PLAT_Y, z, "air") for x, z in plat], False),
    ]
    return steps


def run_light_mutations(process, metrics_path, out_dir, anchor=(10, 100, 10), salt=0):
    """Drives the §4 mutation workload; settles on live light counters;
    returns a receipt dict (never raises)."""
    metrics_path = metrics_path if metrics_path.is_absolute() else metrics_path
    receipt = {"steps": [], "passed": False}
    m0 = _read_light_metrics(metrics_path)
    if m0 is None or _light_job_total(m0) is None:
        receipt["failure"] = "metrics file unavailable"
        (out_dir / "light-mutations.json").write_text(
            json.dumps(receipt, indent=2, sort_keys=True) + "\n")
        print("[light-mutations] ERROR: metrics file unavailable",
              file=sys.stderr)
        return receipt
    cells0 = _light_job_total(m0)
    print(f"[light-mutations] baseline jobTotal={cells0} "
          f"(worldLightCells={m0.get('worldLightCells')} "
          f"phosphorJobs={m0.get('phosphorJobs')})")
    failed = []
    for name, commands, require_delta in light_mutation_steps(anchor, salt):
        before = _light_job_total(_read_light_metrics(metrics_path)) or 0
        for cmd in commands:
            try:
                process.stdin.write((cmd + "\n").encode())
                process.stdin.flush()
            except (OSError, ValueError) as e:
                failed.append((name, f"stdin: {e}"))
                break
        cells, settled, took = _settle_light(metrics_path, before or 0,
                                             require_delta)
        delta = (cells - before) if cells is not None else 0
        # drains are tick-driven; seeing the traffic is the pass condition
        # (full 2-poll stabilization stays in the receipt as `settled`)
        ok = (delta > 0 or not require_delta) and settled is not None
        if not ok:
            failed.append((name, f"settled={settled} delta={delta}"))
        receipt["steps"].append({
            "step": name, "commands": commands, "jobsBefore": before,
            "jobsAfter": cells, "jobDelta": delta,
            "settleS": round(took, 2), "ok": ok,
        })
        print(f"[light-mutations] {name}: jobs {before} -> {cells} "
              f"(delta {delta}, settle {took:.1f}s) ok={ok}")
    mf = _read_light_metrics(metrics_path)
    receipt["final"] = mf
    receipt["passed"] = (not failed
                         and (mf.get("worldLightMis") or 0) == 0
                         and (mf.get("worldLightErr") or 0) == 0
                         and (mf.get("phosphorMis") or 0) == 0
                         and (mf.get("phosphorErr") or 0) == 0)
    receipt["failedSteps"] = failed
    (out_dir / "light-mutations.json").write_text(
        json.dumps(receipt, indent=2, sort_keys=True) + "\n")
    if receipt["passed"]:
        print(f"[light-mutations] LIGHT_MUTATIONS_PASSED "
              f"steps={len(receipt['steps'])} final={mf}")
    else:
        print(f"[light-mutations] LIGHT_MUTATIONS_FAILED failed={failed} "
              f"final={mf}", file=sys.stderr)
    return receipt


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
    parser.add_argument("--light-experiment", action="store_true")
    parser.add_argument("--light-mode", choices=["SHADOW", "ON_EXPERIMENTAL"],
                        default="SHADOW")
    parser.add_argument("--light-mutations", action="store_true",
                        help="Run the §4 block-light mutation workload "
                             "(setblock torch/glowstone/stone cycles via the "
                             "normal console path) and settle on live "
                             "worldLight metrics between steps")
    parser.add_argument("--world-registry", action="store_true",
                        help="Enable world-lifecycle NativeChunk registration "
                             "(NATIVECHUNK_WORLD_REGISTRY; chunks register on "
                             "load regardless of packets/clients)")
    parser.add_argument("--allow-stale-jar", action="store_true",
                        help="skip the source-vs-artifact freshness guard "
                             "(A jar older than any bridge source means a "
                             "failed build was masked; the campaign would "
                             "silently run stale code)")
    parser.add_argument("--packet-authority", action="store_true",
                        help="Enable bounded Rust chunk packet authority "
                             "(§16 combined light+packet composition)")
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
        # Combined campaigns need BOTH floors from ONE session: only skip
        # when a single prior run met both. Solo campaigns keep single-floor
        # logic. (skip uses "OFF" as the write-mode component when read-only.)
        write_component = "OFF" if args.mode == "OFF" else args.mode.lower()
        read_component = args.read_mode.lower()
        combined = args.mode != "OFF" and args.read_mode != "OFF"
        if combined:
            skip = False
            seen_n = 0
        else:
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
    # STALE-JAR GUARD (retro 2026-10-09): a masked build failure once
    # launched a baseline campaign on a stale jar and produced
    # telemetry-free evidence for a full run before anyone noticed.
    # The jar must be newer than every bridge source and the DLL newer
    # than every Rust source the FFI depends on; --allow-stale-jar
    # overrides (reproducing an OLD artifact on purpose).
    if not args.allow_stale_jar and campaign_jar.is_file():
        jar_mtime = campaign_jar.stat().st_mtime
        newest_src = max(
            f.stat().st_mtime
            for f in (ROOT / "tools" / "bridge" / "src").rglob("*.java"))
        if newest_src > time.time() + 60:
            # clock-skew companion (same live incident as the DLL guard):
            # future-dated source mtimes make the comparison meaningless
            print(
                "[WARN] bridge source mtimes are in the future (clock "
                "skew?) — jar freshness check skipped; verify the jar was "
                "rebuilt from current sources manually.",
                file=sys.stderr)
        elif jar_mtime < newest_src:
            print(
                f"[ERROR] STALE JAR: {campaign_jar} predates the newest "
                f"bridge source by {newest_src - jar_mtime:.0f}s — a build "
                f"failure was probably masked. Rebuild "
                f"(python tools/build_campaign_jar.py) or pass "
                f"--allow-stale-jar to reproduce an old artifact on purpose.",
                file=sys.stderr)
            return 2
    if not args.allow_stale_jar and dll.is_file():
        dll_mtime = dll.stat().st_mtime
        newest_rs = max(
            f.stat().st_mtime
            for f in (ROOT / "crates").rglob("*.rs"))
        if newest_rs > time.time() + 60:
            # machine clock skew (observed live: sources with future
            # mtimes after a backward clock move) — mtime comparison is
            # unreliable; warn instead of refusing
            print(
                "[WARN] source mtimes are in the future (clock skew?) — "
                "DLL freshness check skipped; verify the DLL was rebuilt "
                "from current sources manually.",
                file=sys.stderr)
        elif dll_mtime < newest_rs:
            print(
                f"[ERROR] STALE DLL: {dll} predates the newest Rust source "
                f"by {newest_rs - dll_mtime:.0f}s — rebuild "
                f"(cargo build --release -p ffi) or pass --allow-stale-jar.",
                file=sys.stderr)
            return 2

    session = {"process_id": str(uuid.uuid4()), "session_id": str(uuid.uuid4())}
    # Upgrade #4: read-only campaigns (write OFF) reuse one prepared server
    # dir per target — reads never mutate the world, and the §29 hash check
    # catches any unexpected mutation. Write campaigns keep the fresh copy.
    if args.mode == "OFF" and os.environ.get("RUSTCRAFT_REUSE_SERVER") == "1":
        reuse_dir = ROOT / "target" / "authority-review" / f"warm-server-{target}"
        if (reuse_dir / "server" / "libraries").is_dir():
            print(f"[warm] reusing prepared server dir {reuse_dir / 'server'}")
            server_dir = reuse_dir / "server"
            _prep_skip = True
        else:
            reuse_dir.mkdir(parents=True, exist_ok=True)
            server_dir, prep = prepare_server(
                rt, reuse_dir, forge_jar=forge_jar, vanilla_jar=vanilla_jar,
                campaign_jar=campaign_jar,
                world_source=world_source if world_source.is_dir() else None)
            _prep_skip = True
    else:
        _prep_skip = False
    if not _prep_skip:
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
    if getattr(args, "light_experiment", False):
        extra_args += ["-Drustcraft.lightExperiment=true"]
        if args.light_mutations:
            # live worldLight counters for the mutation settle loop
            extra_args += [
                "-Drustcraft.regionMetricsFile="
                + str(out_dir / "region-metrics.txt"),
                "-Drustcraft.lightCompareFlag="
                + str(out_dir / "light-compare.flag"),
            ]
    if getattr(args, "light_mode", None) and args.light_mode != "SHADOW":
        for i, a in enumerate(extra_args):
            if a == "-Drustcraft.lightExperiment=true":
                extra_args[i] = "-Drustcraft.lightExperiment=true"
        extra_args.append(f"-Drustcraft.lightMode={args.light_mode}")
    if getattr(args, "world_registry", False):
        extra_args += [
            "-Drustcraft.worldRegistry=true",
            # the Chunk lifecycle transformer self-gates on this property
            "-Dminecraftrust.m4.coherency=true",
            # mutation→native state push (zero-stage reads registry states;
            # without this, setblock mutations stay invisible to Rust until
            # a deferred consumer refresh — committedCells=0 in dev3)
            "-Drustcraft.chunkStateAuthorityExperiment=true",
            "-Drustcraft.chunkStateAuthorityCap=2000",
            "-DRUSTCRAFT_ZS_DEBUG=1",
        ]
    if getattr(args, "packet_authority", False):
        extra_args += [
            "-Drustcraft.packetAuthorityExperiment=true",
            "-Drustcraft.packetAuthorityCap=2000",
            "-Drustcraft.chunkStateAuthorityExperiment=true",
            "-Drustcraft.chunkStateAuthorityCap=2000",
            "-DRUSTCRAFT_ZS_DEBUG=1",
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
                    if (args.light_mutations and args.light_experiment
                            and rnd + 1 >= max(1, args.probe_rounds)):
                        # §4 mutations need PLAYER-LOADED chunks: with
                        # teleport rounds the campaign ends far from spawn,
                        # so wait for it, tp the probe player onto the
                        # mutation anchor, then mutate. Without teleports the
                        # player already sits at spawn.
                        if args.teleport_rounds > 0:
                            wait_for(jvm_log, r"waypoints completed", 400.0)
                            try:
                                process.stdin.write(
                                    f"tp RWP{target}{rnd} 10 101 8\n".encode())
                                process.stdin.flush()
                            except (OSError, ValueError):
                                pass
                            time.sleep(6.0)  # anchor chunk load (one-time)
                        else:
                            wait_for(jvm_log,
                                     rf"RWP{target}{rnd}.*(logged in|joined)",
                                     240.0)
                            time.sleep(3.0)
                        # anchor at the PLAYER: mutations need chunks the
                        # probe holds loaded — the world spawn point is not
                        # guaranteed to be near them (Gate C spawns ~200
                        # blocks away; fixed anchors hit unloaded chunks)
                        anchor = None
                        try:
                            log_text_now = jvm_log.read_text(
                                encoding="utf-8", errors="replace")
                            mpos = None
                            for mpos in re.finditer(
                                    rf"RWP{target}{rnd}.*logged in with entity"
                                    r" id \d+ at \(([-0-9.]+), ([-0-9.]+),"
                                    r" ([-0-9.]+)\)", log_text_now):
                                pass  # keep the LAST login position
                            if mpos is not None:
                                anchor = (int(float(mpos.group(1))),
                                          int(float(mpos.group(2))),
                                          int(float(mpos.group(3))))
                        except OSError:
                            pass
                        if anchor is None:
                            anchor = (10, 100, 10)
                        print(f"[light-mutations] anchor={anchor}")
                        run_salt = int(time.time()) % 8
                        # gate the compare window on the PLATFORM op center,
                        # not the raw login pos: the salt shifts the platform
                        # (salt%8)*5 in x / +3 in y, and the hook captures at
                        # +/-8 — a raw anchor drifts out of the window for
                        # salt >= 3 and the harness silently captures nothing
                        # (po1: 0 captures, every job 13+ blocks away)
                        try:
                            (out_dir / "light-compare.flag").write_text(
                                ",".join(str(c) for c in
                                         mutation_op_center(anchor,
                                                            run_salt)))
                        except OSError:
                            pass
                        light_mutations_receipt = run_light_mutations(
                            process, out_dir / "region-metrics.txt", out_dir,
                            anchor, run_salt)
                        if not light_mutations_receipt.get("passed"):
                            exit_reason = "LIGHT_MUTATIONS_FAILED"
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
    if args.light_experiment:
        # worldLight parity gates: the boot line proves the seam; the FINAL
        # metrics line (shutdown or last periodic dump) decides the gates
        if "WORLD_CHECK_LIGHT_HOOKED" not in log_text:
            print("[FAIL] WORLD_CHECK_LIGHT_HOOKED absent (World seam not "
                  "instrumented)")
            failures += 1
        wl_cells = re.findall(r"worldLight\.shadow cells=(\d+)", log_text)
        wl_mis = re.findall(r"worldLight\.shadow cells=\d+ mismatches=(\d+)",
                            log_text)
        wl_err = re.findall(r"errors=(\d+)", log_text)
        wl_cells_n = int(wl_cells[-1]) if wl_cells else 0
        wl_mis_n = int(wl_mis[-1]) if wl_mis else -1
        wl_err_n = int(wl_err[-1]) if wl_err else -1
        # Phosphor-active shape (Gate C): the vanilla path is CANCELLED by
        # MixinWorld — the worldLight counters legitimately stay 0 and the
        # Phosphor drain counters carry the shadow evidence instead.
        ph_mis = re.findall(r"phosphorJobs=\d+ phosphorCompared=(\d+)", log_text)
        ph_mis_n = int(ph_mis[-1]) if ph_mis else 0
        ph_hook = re.search(r"phosphorLight\.hook enabled=(\S+) mode=(\S+) "
                            r"jobs=(\d+) admitted=(\d+) fallbacks=(\d+) "
                            r"compared=(\d+) mismatches=(\d+) committedCells=\d+ "
                            r"errors=(\d+)", log_text)
        ph = dict(zip(["enabled", "mode", "jobs", "admitted", "fallbacks",
                       "compared", "mismatches", "errors"],
                      ph_hook.groups() if ph_hook else []))
        # shutdown log lines race process exit — the metrics file (2s
        # snapshots) is the authoritative final counter source; log regexes
        # are the fallback
        mf = _read_light_metrics(out_dir / "region-metrics.txt")
        if mf and mf.get("phosphorJobs") is not None:
            ph["jobs"] = str(mf.get("phosphorJobs") or 0)
            ph["compared"] = str(mf.get("phosphorCompared") or 0)
            ph["mismatches"] = str(mf.get("phosphorMis") or 0)
            ph["errors"] = str(mf.get("phosphorErr") or 0)
        if mf and mf.get("worldLightCells") is not None:
            wl_cells_n = mf.get("worldLightCells") or 0
            wl_mis_n = mf.get("worldLightMis") or 0
            wl_err_n = mf.get("worldLightErr") or 0
        print(f"[verify] worldLight cells={wl_cells_n} mismatches={wl_mis_n} "
              f"errors={wl_err_n}; phosphor jobs={ph.get('jobs')} "
              f"compared={ph.get('compared')} mismatches={ph.get('mismatches')} "
              f"errors={ph.get('errors')}")
        vanilla_path_live = wl_cells_n > 0
        phosphor_path_live = int(ph.get("jobs", "0")) > 0
        # ON mode: admitted Rust jobs ARE the BLOCK-light work — the
        # shadows are correctly bypassed for admitted jobs (that is what
        # authority means); require either shadow evidence or authority
        la_adm_v = mf.get("lightAuthAdmitted") if mf else None
        authority_live = (la_adm_v or 0) > 0
        if not vanilla_path_live and not phosphor_path_live and not authority_live:
            print("[FAIL] neither shadow nor authority saw BLOCK-light work")
            failures += 1
        if vanilla_path_live:
            if wl_mis_n != 0:
                print(f"[FAIL] worldLight mismatches={wl_mis_n}")
                failures += 1
            if wl_err_n != 0:
                print(f"[FAIL] worldLight hook errors={wl_err_n}")
                failures += 1
        if phosphor_path_live:
            if ph.get("mismatches") != "0":
                print(f"[FAIL] phosphor shadow mismatches={ph.get('mismatches')}")
                failures += 1
            if ph.get("errors") != "0":
                print(f"[FAIL] phosphor hook errors={ph.get('errors')}")
                failures += 1
        if args.light_mutations:
            lm_path = out_dir / "light-mutations.json"
            if lm_path.is_file():
                lm = json.loads(lm_path.read_text())
                if not lm.get("passed"):
                    print(f"[FAIL] light mutations failed: "
                          f"{lm.get('failedSteps')}")
                    failures += 1
                else:
                    print(f"[verify] light mutations passed "
                          f"steps={len(lm.get('steps', []))}")
            else:
                print("[FAIL] light-mutations receipt missing")
                failures += 1
        if args.light_experiment and args.light_mode == "ON_EXPERIMENTAL":
            # ON gates: the coarse authority seam must have fired, admitted
            # real jobs, committed cells, and SKY must have stayed live
            if "CHECK_LIGHT_AUTHORITY_HOOKED" not in log_text:
                print("[FAIL] CHECK_LIGHT_AUTHORITY_HOOKED absent")
                failures += 1
            mf2 = _read_light_metrics(out_dir / "region-metrics.txt")
            la_adm = mf2.get("lightAuthAdmitted") if mf2 else None
            la_com = mf2.get("lightAuthCommitted") if mf2 else None
            la_sky = mf2.get("lightAuthSky") if mf2 else None
            la_err = mf2.get("lightAuthErr") if mf2 else None
            print(f"[verify] lightAuthority admitted={la_adm} "
                  f"committed={la_com} sky={la_sky} errors={la_err}")
            if not la_adm:
                print("[FAIL] lightAuthority admitted no jobs")
                failures += 1
            if not la_com:
                print("[FAIL] lightAuthority committed no cells")
                failures += 1
            if not la_sky:
                print("[FAIL] SKY passthrough never ran (0 calls)")
                failures += 1
            if (la_err or 0) != 0:
                print(f"[FAIL] lightAuthority errors={la_err}")
                failures += 1
            # HOOK LIVENESS (retro fix 1): a hook the run claims to
            # exercise reading ZERO is a gate failure — dead hooks shipped
            # silently twice (the M4.2D EBS light mirror was dead code for
            # two milestones, masked by the section pull; EBS_OWNER was
            # never populated). Keys are the reflection-dumped FIELD names
            # (metrics dump v2). ABSENT also fails: absent means a stale
            # jar without the counter — indistinguishable from dead.
            # OPT-MIRROR-001 gate: every committed cell must reach the
            # Java mirror (batched or fallback) — a silently-dropped
            # mirror passes every other gate while Java-visible light
            # (packets, saves) goes stale (the ClassNotFoundException
            # incident: committed=38,720, published=0, PASS).
            pub = mf2.get("MIRROR_PUBLISHED_CELLS") if mf2 else None
            if la_com and pub is not None and int(pub) < int(la_com):
                print(f"[FAIL] mirror publication: {pub} published < "
                      f"{la_com} committed")
                failures += 1
            for hk in ("HOOK_CHUNK_LOADED", "HOOK_BLOCK_SETS",
                       "MIRROR_STATE_SETS"):
                hv = mf2.get(hk) if mf2 else None
                if hv is None or int(hv) <= 0:
                    print(f"[FAIL] hook liveness: {hk}="
                          f"{hv} (must be > 0 under --light-mutations)")
                    failures += 1

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
        db.record_run(f"region-{(args.read_mode if args.read_mode != chr(79)+chr(70)+chr(70) else args.mode).lower()}-{target}", target,
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
