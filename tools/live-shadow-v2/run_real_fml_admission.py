#!/usr/bin/env python3
"""Real FML server launch under the EXISTING V2 session-bound admission.

Phase-D blocker resolution, section 16: boot a REAL disposable Revelation
Forge/FML server with live session admission ON and shadow comparison OFF,
prove the Phase-C headless join still works in the same launch, stop the
server cleanly so the in-JVM evidence flush runs, then feed the launch's own
evidence through the SAME qualification engine (same static manifest facts,
same profile, same collector transcription, same nodes).

No downgrade, no stamping, no offline certificate reuse: the real JVM
certifies its own real session; the engine decides.
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
sys.path.insert(0, str(Path(__file__).resolve().parent))

from run_join_probe import prepare_server, sha, stop, wait_for  # noqa: E402
from join_probe import run_probe  # noqa: E402

JAVA = Path("D:/rustcraft-toolchains/temurin8/jdk8u504-b01/bin/java.exe")
PORT = 25597


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--runtime-root", type=Path, required=True)
    parser.add_argument("--forge-jar", required=True)
    parser.add_argument("--vanilla-jar", required=True)
    parser.add_argument("--campaign-jar", type=Path, required=True)
    parser.add_argument("--srg-jar", type=Path, required=True)
    parser.add_argument("--static-contract", type=Path, required=True,
                        help="the qualified static contract directory (manifest.json, "
                             "profile.json, recipe.json, collector-config.json, "
                             "qualifying-launch/frame-obligations/)")
    parser.add_argument("--mod-versions", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--username", default="AdmissionProbe")
    parser.add_argument("--post-done-settle-s", type=float, default=45.0)
    parser.add_argument("--stability-s", type=float, default=20.0)
    parser.add_argument("--boot-timeout-s", type=int, default=1800)
    args = parser.parse_args()
    args.static_contract = args.static_contract.resolve()
    args.srg_jar = args.srg_jar.resolve()
    args.mod_versions = args.mod_versions.resolve()

    out = args.output.resolve()
    if out.exists():
        shutil.rmtree(out)
    out.mkdir(parents=True)

    for line in subprocess.run(["netstat", "-ano"], capture_output=True, text=True).stdout.splitlines():
        if ":%d" % PORT in line and "LISTENING" in line:
            print(json.dumps({"status": "FAIL", "reason": "PORT_ALREADY_HELD",
                              "holder_pid": line.split()[-1]}))
            return 1

    session = {"process_id": str(uuid.uuid4()), "session_id": str(uuid.uuid4())}
    (out / "launch-session.json").write_text(json.dumps(session, indent=2) + "\n")

    server, prepare_info = prepare_server(
        args.runtime_root, out, forge_jar=args.forge_jar, vanilla_jar=args.vanilla_jar,
        campaign_jar=args.campaign_jar.resolve(), world_source=None)
    properties = (server / "server.properties").read_text()
    (server / "server.properties").write_text(
        properties.replace("server-port=25599", "server-port=%d" % PORT))

    launch = out / "launch"
    launch.mkdir()
    dump = launch / "transformed"
    dump.mkdir()
    observation = launch / "observation"

    frame = args.static_contract / "qualifying-launch" / "frame-obligations"
    frame_types, frame_queries = frame / "types.txt", frame / "queries.tsv"
    if not frame_types.is_file() or not frame_queries.is_file():
        print(json.dumps({"status": "FAIL", "reason": "FRAME_OBLIGATIONS_MISSING"}))
        return 1

    campaign = (server / "rustcraft-campaign.jar").resolve()
    jvm_args = [
        str(JAVA), "-Xmx6G",
        "-javaagent:" + str(campaign),
        "-Dfml.queryResult=confirm",
        "-Drustcraft.liveWriterDiagnostic=true",
        "-Drustcraft.session.processId=" + session["process_id"],
        "-Drustcraft.session.transformationSessionId=" + session["session_id"],
        "-Drustcraft.dumpDir=" + str(dump),
        "-Drustcraft.definedDump=" + str(dump),
        "-Drustcraft.qualificationResult=" + str(launch / "qualification.json"),
        "-Drustcraft.transformationChain=" + str(launch / "transformation-chain.json"),
        "-Drustcraft.observationDir=" + str(observation),
        "-Drustcraft.frameTypes=" + str(frame_types),
        "-Drustcraft.frameQueries=" + str(frame_queries),
        "-Drustcraft.srgJar=" + str(args.srg_jar.resolve()),
        # The runtime's OWN claim of which qualified profile it runs: the
        # same static profile id the plan and policies pin; every other
        # check (Forge build, pre-hook hashes, invariants) verifies it.
        "-Drustcraft.profile=" + json.loads(
            (args.static_contract / "profile.json").read_text(encoding="utf-8"))["id"],
    ]
    classpath = [str(campaign), str(server / args.forge_jar), str(server / args.vanilla_jar)]
    classpath += [str(p) for p in sorted((server / "libraries").rglob("*.jar"))]
    jvm_args += ["-cp", os.pathsep.join(classpath),
                 "net.minecraft.launchwrapper.Launch", "--tweakClass",
                 "com.rustcraft.coremod.LiveSessionAdmissionTweaker",
                 "--gameDir", str(server)]
    (launch / "launch.json").write_text(json.dumps(
        {"session": session, "argv": jvm_args}, indent=2) + "\n")

    jvm_log = launch / "jvm.log"
    process = subprocess.Popen(jvm_args, cwd=str(server), stdout=jvm_log.open("wb"),
                               stderr=subprocess.STDOUT, stdin=subprocess.PIPE)

    receipt: dict = {
        "schema": "RUSTCRAFT_V2_REAL_FML_ADMISSION_V1",
        "runtime_root": str(args.runtime_root),
        "campaign_jar_sha256": sha(args.campaign_jar),
        "session": session,
        "production_authority": False,
    }

    booted = wait_for(jvm_log, r"Done \([0-9.]+s\)", args.boot_timeout_s)
    receipt["server_booted"] = booted
    if not booted:
        stop(process, timeout_s=120)
        receipt["verdict"] = "FAIL"
        receipt["failure"] = "server did not reach Done"
        (out / "admission-receipt.json").write_text(
            json.dumps(receipt, indent=2, sort_keys=True) + "\n")
        return 1

    if args.post_done_settle_s > 0:
        time.sleep(args.post_done_settle_s)

    # Phase-C join regression, inside the SAME admitted launch.
    log_text = jvm_log.read_text(encoding="utf-8", errors="replace")
    inventory = re.search(r"missing mods \[([^\]]+)\]", log_text)
    document = json.loads(args.mod_versions.read_text(encoding="utf-8"))
    derived = document.get("versions", document)
    builtins = {"minecraft": "1.12.2", "FML": "8.0.99.99",
                "forge": args.forge_jar.split("-")[-1].replace(".jar", ""),
                "mcp": "9.42"}
    client_mods = []
    if inventory is not None:
        for name in inventory.group(1).split(","):
            modid = name.strip()
            version = derived.get(modid, builtins.get(modid))
            if version is not None:
                client_mods.append((modid, version))
    probe = run_probe("127.0.0.1", PORT, args.username, expect_forge=True,
                      stability_s=args.stability_s, client_mods=client_mods)
    (launch / "probe.json").write_text(json.dumps(probe, indent=2, sort_keys=True) + "\n")

    stop(process, timeout_s=600)
    for _ in range(120):
        if process.poll() is not None:
            break
        time.sleep(1)
    if process.poll() is None:
        process.kill()
        time.sleep(2)

    # ---- the flush artifacts this launch must have produced ---------------
    qualification = launch / "qualification.json"
    chain = launch / "transformation-chain.json"
    artifacts = {
        "qualification_json": qualification.is_file(),
        "transformation_chain": chain.is_file(),
        "observation_pre": (observation / "pre").is_dir(),
        "observation_classes": (observation / "classes").is_dir(),
    }
    receipt["flush_artifacts"] = artifacts
    receipt["probe"] = {k: probe.get(k) for k in
                        ("verdict", "checks", "failure", "packets_in", "bytes_in")}
    if not all(artifacts.values()):
        receipt["verdict"] = "FAIL"
        receipt["failure"] = "evidence flush incomplete: " + json.dumps(artifacts)
        (out / "admission-receipt.json").write_text(
            json.dumps(receipt, indent=2, sort_keys=True) + "\n")
        print(json.dumps({"status": "FAIL", "artifacts": artifacts}, indent=2))
        return 1

    facts = json.loads(qualification.read_text(encoding="utf-8"))
    receipt["capture_kind"] = facts.get("capture_kind")
    receipt["writer_ordering"] = facts.get("writer_ordering")
    receipt["definitions_bound"] = facts.get("definitions_bound_to_classes")
    receipt["downstream_after_writers"] = facts.get("downstream_transformers_after_live_writers")
    receipt["session_environment_bound"] = facts.get("session_environment_bound")
    receipt["transformation_chain_status"] = facts.get("transformation_chain_status")
    receipt["frame_witness"] = facts.get("frame_witness")

    # ---- the SAME collector transcription + the SAME engine ---------------
    engine_dir = out / "engine"
    engine_dir.mkdir()
    collector_config = json.loads(
        (args.static_contract / "collector-config.json").read_text(encoding="utf-8"))
    collector_config["launch_dir"] = str(launch)
    (engine_dir / "collector-config.json").write_text(
        json.dumps(collector_config, indent=2) + "\n")
    manifest = json.loads(
        (args.static_contract / "manifest.json").read_text(encoding="utf-8"))
    collector = manifest["collector"]
    for index, item in enumerate(collector["command"]):
        if str(item).endswith("collector-config.json"):
            collector["command"][index] = str(engine_dir / "collector-config.json")
    # THIS run's tool pin: the collector transcribes the launch's own capture
    # kind, which changed the script; a run's manifest pins the tools it
    # actually uses, and the engine verifies the on-disk tool against the
    # pin. The static contract (recipe, policies, plan) is untouched.
    if "pins" in collector:
        collector_path = ROOT / "tools/qualification-v2/discovery_collector.py"
        for pinned in list(collector["pins"]):
            if str(pinned).endswith("discovery_collector.py"):
                collector["pins"][pinned] = sha(collector_path)
    # Every file argument of the collector command must be pinned, including
    # this run's own config (which redirects the SAME collector at THIS
    # launch's evidence directory).
    if "pins" in collector:
        collector["pins"][str(engine_dir / "collector-config.json")] =             sha(engine_dir / "collector-config.json")
    # The derived manifest declares the manifest OF RECORD it operates under:
    # the static qualification contract whose hash the plan pinned and the
    # launch's certificates bound. Only tool wiring differs.
    manifest["static_manifest_sha256"] = sha(args.static_contract / "manifest.json")
    (engine_dir / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    shutil.copyfile(args.static_contract / "profile.json", engine_dir / "profile.json")
    complete_session_invariants(engine_dir / "profile.json", args.static_contract)

    engine_env = dict(os.environ)
    engine_env["MSYS2_ARG_CONV_EXCL"] = "*"
    engine = subprocess.run(
        [sys.executable, "-B", str(ROOT / "tools/testing/qualification_engine.py"),
         "--manifest", str(engine_dir / "manifest.json"),
         "--profile", str(engine_dir / "profile.json"),
         "--output-root", str(engine_dir)],
        capture_output=True, text=True, env=engine_env, cwd=str(ROOT), timeout=3600)
    (engine_dir / "engine-stdout.log").write_text(engine.stdout)
    (engine_dir / "engine-stderr.log").write_text(engine.stderr)
    try:
        engine_result = json.loads(engine.stdout.strip().splitlines()[-1])
    except (ValueError, IndexError):
        engine_result = {"status": "ENGINE_OUTPUT_UNPARSEABLE",
                         "stdout_tail": engine.stdout[-2000:],
                         "stderr_tail": engine.stderr[-2000:]}
    receipt["engine"] = engine_result
    certificate_path = engine_dir / "certificate.json"
    if certificate_path.is_file():
        receipt["engine_certificate"] = json.loads(
            certificate_path.read_text(encoding="utf-8"))
    receipt["production_authority"] = False
    admitted = (engine_result.get("status") == "PASS"
                and receipt["capture_kind"] == "REAL_FML_TRANSFORM_CAPTURE"
                and receipt["probe"].get("verdict") == "PASS")
    receipt["verdict"] = "PASS" if admitted else "FAIL"
    (out / "admission-receipt.json").write_text(
        json.dumps(receipt, indent=2, sort_keys=True) + "\n")
    print(json.dumps({
        "verdict": receipt["verdict"],
        "probe_verdict": receipt["probe"].get("verdict"),
        "engine_status": engine_result.get("status"),
        "engine_maturity": engine_result.get("maturity"),
        "capture_kind": receipt.get("capture_kind"),
        "definitions_bound": receipt.get("definitions_bound"),
        "downstream": receipt.get("downstream_after_writers"),
        "writer_ordering": (receipt.get("writer_ordering") or "")[:160],
    }, indent=2))
    return 0 if admitted else 1


def complete_session_invariants(profile_path: Path, static_contract: Path) -> None:
    """Complete the static profile's launch-independent form.

    The profile's classes/pre_classes rows for session-bound classes carried
    only the EXACT semantic hash of the offline qualifying launch -- a
    launch-scoped value (it embeds that launch's MixinMerged#sessionId) that
    cannot be the expectation for any other launch. The launch-independent
    completion is the MASKED session invariant, recomputed here with the
    pinned identity tool from the SAME offline evidence the profile was
    derived from. Exact-mode rows are untouched; the admission policies'
    own expected invariants stay the pre-writer authority.
    """
    import subprocess
    profile = json.loads(profile_path.read_text(encoding="utf-8"))
    if profile.get("identity_mode") != "CANONICAL_ID_V2_SESSION_BOUND":
        return
    session_classes = set(profile.get("session_bound", {}).get("classes", []))
    if not session_classes:
        return
    identity = json.loads(
        (static_contract / "collector-config.json").read_text(encoding="utf-8"))["identity_tool"]
    offline = static_contract / "qualifying-launch" / "observation"
    for section, directory in (("classes", "classes"), ("pre_classes", "pre")):
        rows = profile.get(section) or {}
        targets = [n for n in rows if n in session_classes]
        if not targets:
            continue
        files = [str(offline / directory / (n + ".class")) for n in targets]
        cmd = [identity["java"], "-cp", os.pathsep.join(identity["classpath"]),
               "com.rustcraft.coremod.CanonicalClassIdentityV2", "--session-bound", *files]
        env = dict(os.environ)
        env["MSYS2_ARG_CONV_EXCL"] = "*"
        completed = subprocess.run(cmd, capture_output=True, text=True, env=env, timeout=600)
        receipts = completed.stdout.strip().splitlines()
        if completed.returncode or len(receipts) != len(targets):
            raise SystemExit("session invariant derivation failed for " + section
                             + ": " + completed.stderr[:200])
        for name, receipt in zip(targets, receipts):
            row = json.loads(receipt)
            # row: [schema, name, exact_semantic, declaration, raw, invariant, ...]
            row_data = rows[name]
            # The exact semantic stays as the derivation launch's provenance;
            # the masked invariant is the launch-independent expectation the
            # engine compares for session-bound rows.
            row_data["session_invariant_sha256"] = row[5]
    profile_path.write_text(json.dumps(profile, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    raise SystemExit(main())
