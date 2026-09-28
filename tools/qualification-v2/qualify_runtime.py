"""Generic two-launch offline qualification for a modded Forge runtime.

    python -B tools/qualification-v2/qualify_runtime.py --runtime-root <server dir> ...

This is the architecture, not a script written for one modpack:

    DISCOVERY LAUNCHES  ->  stable facts only
        ->  static mixed recipe + admission policies
        ->  generated writer plan
    QUALIFYING LAUNCH  ->  its OWN certificates, acquisition, chain, witness
        ->  QualificationEngine decides

The separation is the point and it is enforced, not merely documented.
Discovery runs with the live writers OFF, so it cannot issue a certificate; the
policies are built only from facts that survive a relaunch; and the policy
schema itself refuses any document carrying a process id, a session UUID, a
pre-writer hash, a loader object identity or an acquisition hash. Discovery
runs TWICE, because a single measurement is an observation and a value that
held across two independent launches is a fact. A class whose exact identity
moved, or whose invariant did not, is not pinned on that basis.

The qualifying launch authorizes itself. Nothing it needs is carried over.

A runtime whose classes are all exact takes the same path with an empty
session-bound set. Nothing here names a pack, a mod, or a runtime.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import subprocess
import sys
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
for extra in ("tools/testing", "tools/qualification-v2"):
    sys.path.insert(0, str(ROOT / extra))
import session_bound_policy as policy_schema  # noqa: E402
import writer_sites  # noqa: E402
import transformer_verify  # noqa: E402
from text_digest import normalized_sha256  # noqa: E402
from manifest_identity import canonical_manifest_identity  # noqa: E402
from rev_mixed_recipe import derive_split  # noqa: E402
import qualification_engine  # noqa: E402
from qualification_certificate import Maturity  # noqa: E402

CHAIN_NL = chr(10)
SESSION_BOUND = "CANONICAL_ID_V2_SESSION_BOUND"
EXACT = "CANONICAL_ID_V2"


def sha(path: Path) -> str:
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def dotted(name: str) -> str:
    return name.replace("/", ".")


def run(label, argv, cwd, log, expect_zero=True):
    result = subprocess.run([str(a) for a in argv], cwd=str(cwd), capture_output=True, text=True)
    log.parent.mkdir(parents=True, exist_ok=True)
    log.write_text(result.stdout + CHAIN_NL + result.stderr, encoding="utf-8")
    if (result.returncode == 0) != expect_zero:
        raise SystemExit("%s failed (exit %d); see %s" % (label, result.returncode, log))
    return result


def classpath_entry(path: Path) -> dict:
    """Pin a classpath entry. A jar is pinned whole; a directory is pinned
    file-by-file, which is what makes a compiled-class change visible."""
    if path.is_file():
        return {"path": str(path), "files": {"": sha(path)}}
    return {"path": str(path),
            "files": {q.relative_to(path).as_posix(): sha(q)
                      for q in sorted(path.rglob("*")) if q.is_file()}}


def command(script: Path, args, extra=()):
    """A pinned command for the engine to run, with everything it reads."""
    return {"command": [str(a) for a in [sys.executable, "-B", script] + list(args)],
            "pins": {str(p): sha(p) for p in [Path(sys.executable), script, *extra]},
            # A bare environment, and a bounded run: a collector that can hang
            # forever is a collector whose silence cannot be told from a pass.
            "environment": {},
            "timeout_seconds": 1800}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--runtime-root", type=Path, required=True)
    parser.add_argument("--java-home", type=Path, required=True)
    parser.add_argument("--srg-jar", type=Path, required=True)
    parser.add_argument("--asm", type=Path, required=True)
    parser.add_argument("--identity-classes", type=Path, required=True)
    parser.add_argument("--forge-build", required=True)
    parser.add_argument("--forge-jar", type=Path, required=True)
    parser.add_argument("--vanilla-jar", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--frame-request", type=Path, required=True)
    parser.add_argument("--scope-profile", type=Path, required=True,
                        help="the runtime's declared scope, used by the placement validator")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--runtime-profile", required=True)
    parser.add_argument("--bash", default=shutil.which("bash") or "bash",
                        help="interpreter used for the shared campaign builder; on hosts "
                             "where a bare `bash` is a different one (WSL, say) the "
                             "Git/MSYS bash has to be named explicitly")
    parser.add_argument("--id", default=None)
    args = parser.parse_args()

    bash = args.bash
    if sys.platform == "win32" and bash.startswith("/"):
        # A Git-Bash style path means nothing to a native CreateProcess; the
        # driver is a Windows process and has to spawn a Windows one.
        for prefix in ("/c/", "/mnt/c/"):
            if bash.startswith(prefix):
                bash = "C:\\" + bash[len(prefix):].replace("/", "\\")
                break
    # Two locations, with different jobs.
    #
    # `requested` is where the caller asked for the result and may be long,
    # contain spaces, or both. `work` is a SHORT internal workspace where every
    # JVM-touching step actually runs.
    #
    # The split exists because Java 8 cannot address paths beyond the Windows
    # MAX_PATH limit, so class dumps and compiler output silently vanish for a
    # caller who picked a long output directory. Running in a short workspace
    # and materialising the receipts afterwards means the caller never has to
    # know that limit exists.
    #
    # The workspace token is deliberately NOT part of any canonical identity:
    # it is machine-local execution provenance. A random suffix prevents
    # collisions between concurrent runs and guarantees a stale workspace from
    # an earlier attempt cannot contaminate this one; it is removed up front
    # regardless, so a failed run's evidence is inspectable until the next run
    # of the same output begins.
    requested = args.output.resolve()
    if requested.exists() and any(requested.iterdir()):
        raise SystemExit("qualification output must be fresh: " + str(requested))
    short_repo = ROOT.resolve()
    work = short_repo / "target" / "q" / (
        hashlib.sha256(str(requested).encode("utf-8")).hexdigest()[:12]
        + "-" + uuid.uuid4().hex[:8])
    if work.exists():
        shutil.rmtree(work)
    work.mkdir(parents=True)
    out = work
    requested.mkdir(parents=True, exist_ok=True)
    logs = out / "logs"
    runtime_root = args.runtime_root.resolve()
    java = args.java_home.resolve() / "bin" / "java.exe"
    asm = args.asm.resolve()
    manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
    manifest_sha = normalized_sha256(args.manifest)
    # Every path handed to a pinned subprocess is resolved HERE. The engine runs
    # the collector and the placement validator with cwd set to the run
    # directory, so a relative path written into a config is resolved against
    # that instead of against this repository, and the failure surfaces as a
    # missing file rather than as a wrong path.
    for name in ("manifest", "scope_profile", "srg_jar", "forge_jar", "vanilla_jar",
                 "identity_classes", "asm", "frame_request", "java_home"):
        setattr(args, name, getattr(args, name).resolve())
    identity_classpath = [args.identity_classes.resolve(), asm]
    hooks_by_class: dict[str, list[str]] = {}
    for hook in manifest["required_hooks"]:
        hooks_by_class.setdefault(hook["class"], []).append(hook["id"])
    for ids in hooks_by_class.values():
        ids.sort()

    # ---------------------------------------------------------------- A ----
    # Discovery, with the live writers OFF so it cannot issue a certificate.
    probe = ROOT / "tools/live-capture/run_rev_probe.py"
    identities_tool = ROOT / "tools/qualification-v2/rev_session_identities.py"
    # The writers must be compiled for the probe to link, and the plan in force
    # for that runtime is generated only later; the committed plan is the right
    # one here because with the writers off nothing consults it.
    campaign_source = out / "campaign-src"
    campaign_source.mkdir()
    campaign_jar = campaign_source / "campaign.jar"
    run("build-campaign", [bash, ROOT / "tools/live-capture/build_campaign_coremod.sh",
                           args.srg_jar, campaign_jar, runtime_root, asm, args.forge_jar],
        ROOT, logs / "campaign.log")

    discovered = []
    for index in (1, 2):
        destination = out / ("discovery-%d" % index)
        run("discovery-%d" % index,
            [sys.executable, "-B", probe, "--out", destination, "--campaign-jar", campaign_jar],
            ROOT, logs / ("discovery-%d.log" % index))
        run("identities-%d" % index,
            [sys.executable, "-B", identities_tool, "--dump", destination / "transformed",
             "--identity-classes", args.identity_classes, "--asm", asm,
             "--required", args.manifest, "--out", destination / "identities.json"],
            ROOT, logs / ("identities-%d.log" % index))
        discovered.append(json.loads((destination / "identities.json").read_text(encoding="utf-8")))

    session_bound, exact_only = derive_split(discovered[0], discovered[1])
    observed = discovered[0]["classes"]

    # ------------------------------------------------- static profile ------
    identities_block = {}
    for name in sorted(observed):
        record = observed[name]
        if name in set(session_bound):
            identities_block[dotted(name)] = {
                "schema": SESSION_BOUND, "class_name": name,
                "declaration_order_sha256": record["declaration_order_sha256"],
                "session_invariant_sha256": record["session_invariant_sha256"]}
        else:
            identities_block[dotted(name)] = {
                "schema": EXACT, "class_name": name,
                "raw_sha256": record["raw_sha256"],
                "semantic_sha256": record["semantic_sha256"],
                "declaration_order_sha256": record["declaration_order_sha256"]}

    # The engine binds every session certificate to the hash of the manifest IT
    # loads, so that manifest is built first and its digest is what the policies
    # name. Nothing in it depends on the policies: it pins the runtime
    # inventory, the collector command and the validators, all of whose paths are
    # known before the launch runs.
    launch_dir = out / "qualifying-launch"
    capture_config_path = out / "collector-config.json"
    # PATHS only. Everything the collector needs that depends on the launch is
    # derived there, because the manifest pins this file's digest and the
    # policies name the manifest's -- so nothing produced BY the launch may be
    # written into it, or the binding would be a promise about bytes that did
    # not exist yet.
    capture_config_path.write_text(json.dumps({
        "launch_dir": str(launch_dir),
        "plan": str(out / "LiveWriterPlan.java"),
        "hooks_manifest": str(args.manifest),
        "identity_tool": {"java": str(java),
                          "classpath": [p.as_posix() for p in identity_classpath]},
        "vanilla_jar": str(args.vanilla_jar),
        "runtime_profile": args.runtime_profile,
        "target": args.runtime_root.as_posix(),
    }, indent=2) + CHAIN_NL, encoding="utf-8")
    placement_config_path = out / "placement-config.json"
    placement_config_path.write_text(json.dumps({
        # The validator reads the committed hook MANIFEST, which is the same
        # document the plan is generated from; it was being handed the
        # generated Java and failing to parse it.
        "java_home": str(args.java_home), "plan": str(args.manifest),
        "profile": str(args.scope_profile),
        "pre_dump": str(launch_dir / "observation" / "pre"),
        "post_dump": str(launch_dir / "observation" / "classes"),
    }, indent=2) + CHAIN_NL, encoding="utf-8")
    manifest_out = out / "manifest.json"
    roots = {"artifacts": [args.forge_jar.name, args.vanilla_jar.name, "libraries"],
             "mods": ["mods"], "config": ["config"]}
    inventory = {}
    for section, names in roots.items():
        files = {}
        for name in names:
            entry = runtime_root / name
            paths = [entry] if entry.is_file() else sorted(entry.rglob("*"))
            for found in paths:
                if found.is_file():
                    files[found.relative_to(runtime_root).as_posix()] = sha(found)
        inventory[section] = {"roots": names, "files": files}

    early_manifest = {
        "schema": "RUSTCRAFT_RUNTIME_MANIFEST_V2",
        "runtime_root": str(runtime_root),
        "inventories": inventory,
        "collector": command(ROOT / "tools/qualification-v2/discovery_collector.py",
                             ["--config", str(capture_config_path)],
                             extra=(capture_config_path,)),
        "identity_tool": {"java": str(java), "java_sha256": sha(java),
                          "classpath": [classpath_entry(p) for p in identity_classpath],
                          "timeout_seconds": 900},
        "validators": {"placement": command(ROOT / "tools/qualification-v2/placement_validator.py",
                                            ["--config", str(placement_config_path)],
                                            extra=(placement_config_path,))},
    }
    manifest_out.write_text(json.dumps(early_manifest, indent=2) + CHAIN_NL, encoding="utf-8")
    # The static contract is bound to the manifest's CANONICAL identity, not its
    # raw file hash. The manifest is execution provenance: it names the runtime
    # root, the toolchain and the workspace by absolute path so its
    # subprocesses can run, and those paths move with every run. The canonical
    # identity projects them away and keeps the artifact inventory, toolchain
    # content and schema -- so the same qualification at two output locations
    # yields the same static recipe, plan bytes and policy identities.
    runtime_manifest_sha = canonical_manifest_identity(early_manifest)

    recipe = {
        "schema_version": 2, "kind": "RUSTCRAFT_V2_WRITER_PLAN_RECIPE",
        "identity_mode": SESSION_BOUND if session_bound else EXACT,
        "all_required_observed": True,
        "required_hooks_manifest_sha256": manifest_sha,
        "required_hooks": [{"id": hook["id"], "status": "OBSERVED"}
                            for hook in manifest["required_hooks"]],
        "expected_class_identities": identities_block,
        "forge_build": args.forge_build,
        "qualification": {"profile": args.runtime_profile,
                          "minecraft_server_jar_sha256": sha(args.vanilla_jar),
                          "runtime_manifest_sha256": runtime_manifest_sha},
    }
    if session_bound:
        recipe["session_bound_classes"] = sorted(dotted(n) for n in session_bound)
    binding = policy_schema.recipe_binding_sha256(recipe)
    if session_bound:
        policies = {}
        for name in sorted(session_bound):
            record = observed[name]
            policies[dotted(name)] = policy_schema.build(
                class_name=dotted(name),
                expected_session_invariant_sha256=record["session_invariant_sha256"],
                expected_declaration_order_sha256=record["declaration_order_sha256"],
                expected_masked_locations=record["masked_locations"],
                expected_masked_occurrence_count=record["masked_occurrences"],
                runtime_profile=args.runtime_profile,
                runtime_manifest_sha256=runtime_manifest_sha,
                writer_plan_sha256=binding, recipe_sha256=binding,
                required_hook_ids=hooks_by_class[dotted(name)],
                expected_loader_class="net.minecraft.launchwrapper.LaunchClassLoader",
                expected_loader_scope="LAUNCHWRAPPER")
        recipe["session_admission_policies"] = policies
    recipe["recipe_binding_sha256"] = binding
    recipe_path = out / "recipe.json"
    recipe_path.write_text(json.dumps(recipe, indent=2) + CHAIN_NL, encoding="utf-8")

    generator = ROOT / "tools/live-capture/generate_live_writer_plan.py"
    plan_path = out / "LiveWriterPlan.java"
    run("generate-plan", [sys.executable, "-B", generator, "--profile", recipe_path,
                          "--manifest", args.manifest, "--out", plan_path],
        ROOT, logs / "plan.log")
    repeat_plan = out / "LiveWriterPlan.repeat.java"
    run("regenerate-plan", [sys.executable, "-B", generator, "--profile", recipe_path,
                            "--manifest", args.manifest, "--out", repeat_plan],
        ROOT, logs / "plan-repeat.log")
    if plan_path.read_bytes() != repeat_plan.read_bytes():
        raise SystemExit("the generated plan is not deterministic")
    plan_text = plan_path.read_text(encoding="utf-8")
    for forbidden in ("process_id", "transformation_session_id", "\"defining_loader_identity\""):
        if forbidden in plan_text:
            raise SystemExit("the static plan carries a process-specific fact: " + forbidden)

    # The writers, rebuilt against the plan just generated.
    qualified_jar = out / "qualified-campaign.jar"
    run("build-qualified-campaign", [bash, ROOT / "tools/live-capture/build_campaign_coremod.sh",
                                     args.srg_jar, qualified_jar, runtime_root, asm,
                                     args.forge_jar, plan_path],
        ROOT, logs / "qualified-campaign.log")

    # ---------------------------------------------------------------- B ----
    launch_dir = out / "qualifying-launch"
    run("qualifying-launch",
        [sys.executable, "-B", ROOT / "tools/qualification-v2/rev_session_launch.py",
         "--campaign-jar", qualified_jar, "--srg-jar", args.srg_jar,
         "--frame-request", args.frame_request, "--out", launch_dir,
         "--profile", args.runtime_profile],
        ROOT, logs / "qualifying-launch.log")
    receipt = json.loads((launch_dir / "qualification.json").read_text(encoding="utf-8"))
    java_runtime_version = receipt.get("java_runtime_version", "")
    if receipt.get("transformation_chain_status") != "RENDERED":
        raise SystemExit("the qualifying launch did not render a chain: "
                         + str(receipt.get("transformation_chain_status")))
    session_ids = json.loads((launch_dir / "launch.json").read_text(encoding="utf-8"))

    # ------------------------------------------------- engine inputs ------
    # hook_plan reads the committed hook manifest, the same input the plan is
    # generated from; the two are cross-checked by the writer-plan lane.
    hook_plan = transformer_verify.hook_plan(args.manifest)
    hooked = sorted({hook["class"].replace(".", "/") for hook in hook_plan})
    required = sorted(set(hooked) & set(observed))

    # The engine reparses these itself and compares. They are stated here from
    # the qualifying launch's OWN files, recomputed with the pinned identity
    # tool -- never copied from discovery, which is a different set of bytes in
    # a different process and is not allowed to authorize anything.
    identity_tool = [str(java), "-cp", os.pathsep.join(p.as_posix() for p in identity_classpath),
                     "com.rustcraft.coremod.CanonicalClassIdentityV2"]
    def identities_for(folder: Path, names):
        result = {}
        for name in names:
            classfile = folder / (name + ".class")
            if not classfile.is_file():
                raise SystemExit("the qualifying launch produced no bytes for " + name)
            lines = run("identity-" + folder.name + "-" + name.replace("/", "."),
                        identity_tool + [str(classfile)], ROOT, logs / "identity.log").stdout.splitlines()
            receipt_line = json.loads(lines[0])
            result[name] = {"semantic_sha256": receipt_line[2],
                            "declaration_order_sha256": receipt_line[3]}
        return result

    launch_pre = launch_dir / "observation" / "pre"
    launch_post = launch_dir / "observation" / "classes"
    # Per-site contracts from the qualifying launch's own pre-writer facts. The
    # writer matrix says what the writers were asked to place; the engine then
    # recounts every one of those calls in the transformed class itself.
    pre_dumps = {}
    for name in required:
        classfile = launch_pre / (name + ".class")
        lines = run("pre-dump-" + name.replace("/", "."),
                    [str(java), "-cp", os.pathsep.join(p.as_posix() for p in identity_classpath),
                     "com.rustcraft.coremod.CanonicalClassIdentityV2", "--dump", str(classfile)],
                    ROOT, logs / "pre-dump.log").stdout.splitlines()
        pre_dumps[name] = json.loads(lines[0])
    sites = []
    for hook in hook_plan:
        if hook["hook_type"] == "DIAGNOSTIC_ONLY":
            continue
        internal = hook["class"].replace(".", "/")
        facts = writer_sites.method_facts(pre_dumps[internal], hook["method"], hook["descriptor"])
        sites.append({"id": hook["id"], "class": internal, "method": hook["method"],
                      "descriptor": hook["descriptor"],
                      "required_calls": writer_sites.expected_calls(hook, facts)})

    profile_id = args.id or args.runtime_profile
    engine_profile_classes = identities_for(launch_post, required)
    engine_profile_pre = identities_for(launch_pre, required)

    engine_profile = {
        "schema": "RUSTCRAFT_QUALIFICATION_PROFILE_V2",
        "id": profile_id,
        "identity_mode": SESSION_BOUND if session_bound else EXACT,
        # Stated by the driver from the pinned runtime, not read back from the
        # launch: the engine is being told what it is qualifying.
        "runtime_identity": {"registry_identity_sha256": sha(args.vanilla_jar),
                             "java_runtime_version": java_runtime_version,
                             "qualification_profile": args.runtime_profile,
                             "target": args.runtime_root.as_posix()},
        "transformer_chain": receipt["transformers"],
        "coremods": receipt.get("registered_coremod_plugins", []),
        "classes": engine_profile_classes, "pre_classes": engine_profile_pre,
        "writer_sites": sites,
        "negative_controls": [{"id": "raw-and-canonical-both-move", "expected_outcome": "CHANGED"}],
        "frame_evidence": {"required_classes": required},
        # The live extraction surface this qualification is about. These are the
        # game's own class names, identical in any 1.12.2 Forge runtime, so naming
        # them is the contract the engine validates a scope against, not an
        # assumption about any particular pack.
        "scope": {"schema": "LIVE_CAPTURE_SCOPE_V1",
                  "operation": "chunk_packet_shadow_capture",
                  "profile_id": profile_id, "dimension": 0,
                  "provider_class": "net.minecraft.world.chunk.ChunkProviderServer",
                  "world_class": "net.minecraft.world.World",
                  "chunk_class": "net.minecraft.world.chunk.Chunk",
                  "section_class": "net.minecraft.world.chunk.storage.ExtendedBlockStorage",
                  "container_class": "net.minecraft.world.chunk.BlockStateContainer",
                  "nibble_class": "net.minecraft.world.chunk.NibbleArray",
                  "packet_class": "net.minecraft.network.play.server.SPacketChunkData",
                  "registry_class": "net.minecraft.util.BitArray",
                  "generator_class": "net.minecraft.world.gen.ChunkProviderServer",
                  "storage_family": "VANILLA_U16", "registry_epoch": 1,
                  "state_width_bits": 16, "generator_family": "VANILLA_FLAT_ONLY",
                  "skylight": True},
        "production_authority": False,
    }
    if session_bound:
        # The engine binds the block to a hash of the profile MINUS the block,
        # so the profile is hashed first and the block is added afterwards.
        # Nothing circular: the block is not part of what it binds.
        #
        # Two digests are in play and they are not the same thing. This one
        # covers the engine profile: the class set, the manifest and the hook
        # inventory this run was declared against. The recipe binding inside each
        # policy covers the generated plan, and the transforming JVM enforced
        # that one when it admitted these bytes. Both are recorded so the
        # distinction is visible rather than implied.
        # The block binds the same digest the policies and the generated plan
        # carry: the canonical hash of the static recipe, computed once. The
        # profile carries that recipe verbatim so the engine can recompute it.
        profile_binding = policy_schema.recipe_binding_sha256(recipe)
        engine_profile["static_recipe"] = recipe
        engine_profile["session_bound"] = {
            "schema": "RUSTCRAFT_SESSION_BOUND_PROFILE_V1",
            "schema_version": 1,
            "recipe_sha256": profile_binding,
            "process_id": session_ids["process_id"],
            "transformation_session_id": session_ids["transformation_session_id"],
            # Internal names: the engine keys the profile's class inventory by
            # them, and each policy still names the class in binary form.
            "classes": sorted(session_bound),
            "admission_policies": {name: recipe["session_admission_policies"][dotted(name)]
                                   for name in sorted(session_bound)},
        }
    profile_out = out / "profile.json"
    profile_out.write_text(json.dumps(engine_profile, indent=2) + CHAIN_NL, encoding="utf-8")

    # Reproducibility receipt. The first group is the canonical static
    # contract and must be identical across path-only changes; the second is
    # machine-local execution provenance and is expected to differ. Recording
    # both in one place is what makes a future reproducibility bug obvious
    # instead of mysterious.
    (out / "reproducibility.json").write_text(json.dumps({
        "schema": "RUSTCRAFT_REPRODUCIBILITY_RECEIPT_V1",
        "canonical": {
            "static_recipe_sha256": binding,
            "writer_plan_sha256": sha(plan_path),
            "runtime_manifest_identity": runtime_manifest_sha,
        },
        "execution_provenance": {
            "requested_output_path": str(requested),
            "internal_workspace_path": str(work),
            "runtime_root": str(args.runtime_root),
            "worktree_root": str(ROOT),
            "java_path": str(args.java_home / "bin" / "java.exe"),
        },
    }, indent=2) + CHAIN_NL, encoding="utf-8")

    (out / "discovery.json").write_text(json.dumps({
        "schema": "RUSTCRAFT_DISCOVERY_V1",
        "discovery_launches": 2,
        "session_bound_classes": sorted(dotted(n) for n in session_bound),
        "exact_only_classes": sorted(dotted(n) for n in exact_only),
        "recipe_sha256": binding,
        "runtime_manifest_sha256": sha(args.manifest),
        "plan_sha256": sha(plan_path),
        "note": "nothing process-specific crosses from discovery into the qualifying launch",
    }, indent=2) + CHAIN_NL, encoding="utf-8")

    engine = qualification_engine.QualificationEngine(manifest_out, profile_out, out / "runs")
    certificate = engine.run(Maturity.OFFLINE_QUALIFIED)

    # Materialise the human-facing evidence at the REQUESTED output. Everything
    # copied here is a receipt or a static artifact; the heavy class dumps stay
    # in the workspace, where the JVM put them.
    # Materialise the human-facing evidence at the REQUESTED output. This is
    # best-effort by design: a caller who picked a path so long that even one
    # more path component exceeds the Windows limit has already made it
    # impossible to write anything under that directory, and the qualification
    # verdict must not be lost because of it. The workspace is the authoritative
    # location for the canonical evidence either way, and every failure below is
    # reported with the measured length rather than swallowed.
    keep = ["recipe.json", "LiveWriterPlan.java", "LiveWriterPlan.repeat.java",
            "discovery.json", "reproducibility.json", "profile.json", "manifest.json",
            "collector-config.json", "placement-config.json", "logs"]
    unwritable = []
    for name in keep:
        source = out / name
        if not source.exists():
            continue
        target = requested / name
        try:
            if source.is_dir():
                shutil.copytree(source, target, dirs_exist_ok=True)
            else:
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(source, target)
        except OSError as failure:
            unwritable.append({"entry": name,
                               "attempted_path": str(target),
                               "measured_length": len(str(target)),
                               "error": failure.strerror or str(failure)})
    for run_dir in sorted((out / "runs").glob("*")):
        target = requested / "runs" / run_dir.name
        for name in ("certificate.json", "observation.json", "process-log.json"):
            if not (run_dir / name).is_file():
                continue
            destination = target / name
            try:
                destination.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(run_dir / name, destination)
            except OSError as failure:
                unwritable.append({"entry": "runs/" + run_dir.name + "/" + name,
                                   "attempted_path": str(destination),
                                   "measured_length": len(str(destination)),
                                   "error": failure.strerror or str(failure)})
    if unwritable:
        (out / "MATERIALISATION_FAILURES.json").write_text(
            json.dumps({"schema": "RUSTCRAFT_MATERIALISATION_DIAGNOSTIC_V1",
                        "requested_output": str(requested),
                        "measured_requested_length": len(str(requested)),
                        "windows_max_path": 260,
                        "explanation": "the requested output directory is close enough to the "
                                       "Windows MAX_PATH limit that no child path can be created; "
                                       "the qualification itself ran and its canonical evidence is "
                                       "in the internal workspace recorded below",
                        "recommended_remediation": "use a shorter output directory, or read the "
                                                   "evidence from internal_workspace_path",
                        "entries": unwritable}, indent=2) + CHAIN_NL, encoding="utf-8")

    print(json.dumps({
        "status": certificate["status"], "maturity": certificate["maturity"],
        "production_authority": False,
        "recipe_sha256": binding,
        "writer_plan_sha256": sha(plan_path),
        "runtime_manifest_identity": runtime_manifest_sha,
        # The workspace is where the canonical evidence lives. A caller whose
        # requested path is too long for even one more component will not have
        # the copies, so naming the requested location here would point at
        # files that do not exist.
        "profile": str(profile_out),
        "manifest": str(manifest_out),
        "certificate": str(engine.output / "certificate.json"),
        "requested_output": str(requested),
        "internal_workspace": str(work),
        "receipts_materialised": not unwritable,
    }, indent=2))
    return {"PASS": 0, "FAIL": 1, "INCOMPLETE": 2}[certificate["status"]]


if __name__ == "__main__":
    sys.exit(main())
