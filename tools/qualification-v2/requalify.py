"""Build a V2 requalification manifest and profile, then run the engine.

    requalify.py --runtime <forge-runtime.json> --java-home <jdk8> --dll <dll>
                 --identity-classes <dir> --asm <jar> --output <dir>

The bootstrap capture and the engine's own collector run are two SEPARATE fresh
captures. The bootstrap exists only to learn the expected identities; the engine
run then re-observes from scratch and the engine compares. Reusing the bootstrap
bytes would make the comparison vacuous, which is the exact failure mode the
session-bound and frame contracts exist to prevent.

Clean Forge runs in exact CANONICAL_ID_V2 mode: it carries no MixinMerged
sessionId provenance, so no session certificate is produced and none is needed.
Revelation is a separate invocation that does carry one.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
from pathlib import Path
import subprocess
import sys
import uuid

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
sys.path.insert(0, str(Path(__file__).resolve().parent))
sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "tools/testing"))
import forge_capture_lib as capture_lib  # noqa: E402
import writer_sites  # noqa: E402
import transformer_verify  # noqa: E402
import qualification_engine  # noqa: E402
from tools.testing.qualification_certificate import Maturity  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
PROFILE = ROOT / "tools/live-capture/live-shadow-profile.json"
PLAN = ROOT / "tools/live-capture/required-live-writer-hooks.json"
# The deepest class file a real Clean Forge launch has actually been seen to
# dump. Used only to budget the output path; see the MAX_PATH guard in main.
WORST_OBSERVED_DUMP_CLASS = ("net/minecraftforge/fml/common/asm/transformers/"
                             "EventSubscriberTransformer$SubscribeEventPredicate.class")

# The extractor scope the engine binds the certificate to. These are the exact
# class names the live pipeline targets, not placeholders: a scope that names a
# class the campaign never touched would be a claim, not a binding.
SCOPE_CLASSES = {
    "provider_class": "net.minecraft.world.chunk.ChunkProviderServer",
    "world_class": "net.minecraft.world.World",
    "chunk_class": "net.minecraft.world.chunk.Chunk",
    "section_class": "net.minecraft.world.chunk.storage.ExtendedBlockStorage",
    "container_class": "net.minecraft.world.chunk.BlockStateContainer",
    "nibble_class": "net.minecraft.world.chunk.NibbleArray",
    "packet_class": "net.minecraft.network.play.server.SPacketChunkData",
    "registry_class": "net.minecraft.util.BitArray",
    "generator_class": "net.minecraft.world.gen.ChunkProviderServer",
}


def sha(path: Path) -> str:
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def classpath_entry(path: Path) -> dict:
    """Pin a classpath entry. A jar is pinned whole; a directory is pinned
    file-by-file, which is what makes a compiled-class change visible."""
    if path.is_file():
        return {"path": str(path), "files": {"": sha(path)}}
    return {"path": str(path),
            "files": {q.relative_to(path).as_posix(): sha(q) for q in sorted(path.rglob("*")) if q.is_file()}}


def command(script: Path, args: list[str], extra: tuple[Path, ...] = ()) -> dict:
    executable = Path(sys.executable).resolve()
    return {"command": [str(executable), str(script), *args],
            "pins": {str(p): sha(p) for p in (executable, script, *extra)},
            # The engine caps a bounded subprocess at one hour, and it will not
            # accept an unbounded one: a collector that can hang forever is a
            # collector whose silence is indistinguishable from a pass.
            "environment": {}, "timeout_seconds": 3600}


def writer_sites_from(pre_dumps: dict[str, list], hooks: list[dict]) -> list[dict]:
    """Per-site contracts derived from the pre-writer method facts only."""
    sites = []
    for hook in hooks:
        if hook["hook_type"] == "DIAGNOSTIC_ONLY":
            continue
        facts = writer_sites.method_facts(pre_dumps[hook["class"].replace(".", "/")],
                                          hook["method"], hook["descriptor"])
        calls = writer_sites.expected_calls(hook, facts)
        sites.append({"id": hook["id"], "class": hook["class"].replace(".", "/"),
                      "method": hook["method"], "descriptor": hook["descriptor"],
                      "required_calls": calls})
    return sites


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--runtime", type=Path, required=True)
    parser.add_argument("--java-home", type=Path, required=True)
    parser.add_argument("--dll", type=Path, required=True)
    parser.add_argument("--identity-classes", type=Path, required=True)
    parser.add_argument("--asm", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--id", default=None, help="profile id; defaults to the runtime target")
    args = parser.parse_args()

    # The engine requires every pinned path to be explicitly absolute: a
    # relative pin would be re-resolved against whatever cwd a later stage
    # happened to have, and the same pin would then name two different files.
    args.runtime = args.runtime.resolve(strict=True)
    args.dll = args.dll.resolve(strict=True)
    args.identity_classes = args.identity_classes.resolve(strict=True)
    args.asm = args.asm.resolve(strict=True)
    args.java_home = args.java_home.resolve(strict=True)
    args.output = args.output.resolve()
    args.output.mkdir(parents=True, exist_ok=True)
    java = args.java_home / "bin" / "java.exe"
    classpath = [args.identity_classes, args.asm]

    # The observer agent writes one .class file per defined class, under a
    # per-run directory whose name is the 32-hex session id. That is the
    # longest path this run will ever create, and on Windows a path past 260
    # characters fails inside the agent -- silently, because the agent returns
    # null and never modifies anything. The symptom is a capture that reports a
    # class as declared-but-absent minutes later, which reads like a race and
    # costs a whole run to diagnose. The reference below is the deepest class
    # the real launch has actually been seen to dump. Refuse to start instead.
    # The JVM-touching steps run in a SHORT internal workspace, not at the
    # requested output. Java 8 cannot address paths beyond the Windows MAX_PATH
    # limit and the failure inside the observer agent is silent -- the agent
    # returns null, the class is reported declared-but-absent minutes later,
    # and it reads like a race while costing a whole run. Staging here means a
    # caller who picked a long output directory never has to know the limit
    # exists; the receipts are materialised at the requested location when the
    # run is over, and the mapping is recorded in the receipt.
    #
    # The workspace token is machine-local provenance and deliberately never
    # enters any canonical identity: the static recipe binds artifact content
    # and semantics, not where a receipt happens to be written. The random
    # suffix prevents collisions between concurrent runs and guarantees a stale
    # workspace cannot contaminate this one; it is removed up front regardless,
    # so a failed run's evidence stays inspectable until the next attempt.
    requested = args.output
    # 12 hex characters of the requested-path hash. The path budget here is
    # tight -- the repo root, the deepest class-dump structure and the longest
    # class the launch actually dumps leave 18 characters -- so the token is
    # kept minimal and the random concurrency suffix is dropped. Cross-path
    # collisions are still impossible (different requested paths hash
    # differently) and a stale workspace is removed before the run starts.
    workspace = ROOT / "target" / "q" / hashlib.sha256(
        str(requested).encode("utf-8")).hexdigest()[:12]
    if workspace.exists():
        shutil.rmtree(workspace)
    workspace.mkdir(parents=True)
    args.output = workspace

    deepest = len(str(args.output / "engine-captures" / ("run-" + "0" * 32)
                       / "live-transformer-jvm" / "transformed")) + 1 + len(WORST_OBSERVED_DUMP_CLASS)
    if deepest >= 250:
        print(json.dumps({"status": "FAIL", "reason": "OUTPUT_PATH_TOO_LONG_FOR_CLASS_DUMP",
                          "longest_class_dump_path": deepest, "output": str(args.output),
                          "internal_workspace": str(workspace),
                          "hint": "internal workspace path is still too long; report this"}))
        return 1

    # ---- bootstrap capture: learn the expected identities ----
    bootstrap = capture_lib.capture(ROOT, args.output / "bootstrap", args.java_home, args.dll, args.runtime)
    if bootstrap.get("status") != "PASS":
        print(json.dumps({"status": "FAIL", "reason": "BOOTSTRAP_CAPTURE",
                          "detail": bootstrap.get("reason") or bootstrap.get("detail")}))
        return 1
    pre_dump, post_dump = capture_lib.locate_dumps(bootstrap)
    profile_source = json.loads(PROFILE.read_text(encoding="utf-8"))
    # The engine keys classes by JVM internal name; the writer profile keys them
    # by binary name. Convert once, here, so every downstream consumer -- profile
    # classes, writer sites, frame requirement, collector config -- speaks the
    # same name and the engine never has to guess which was meant.
    # The profile's expected_class_hashes is the live *scope*: it also names
    # classes the writer is only allowed to leave alone. The engine's class set
    # is the *transformed* set, so demanding an observation of an untouched
    # class would ask for a frame proof of a transformation that never
    # happened. Intersect with the plan and record what fell out, so the
    # narrowing is visible rather than silent.
    scope = {name.replace('.', '/') for name in profile_source["expected_class_hashes"]}
    hooks = transformer_verify.hook_plan(PLAN)
    hooked = {hook["class"].replace('.', '/') for hook in hooks}
    required = sorted(scope & hooked)
    if not set(hooked) <= scope:
        print(json.dumps({"status": "FAIL", "reason": "HOOK_CLASS_OUTSIDE_PROFILE_SCOPE",
                          "detail": sorted(hooked - scope)}))
        return 1
    untouched_context = sorted(scope - hooked)
    moved = set()
    for name in required:
        if capture_lib.sha(capture_lib.class_file(pre_dump, name)) != capture_lib.sha(capture_lib.class_file(post_dump, name)):
            moved.add(name)
    if moved != set(required):
        # Every planned class must actually have been rewritten. A planned class
        # whose bytes did not move would make the whole placement story hollow:
        # the plan would name hooks that the writer never applied.
        print(json.dumps({"status": "FAIL", "reason": "PLANNED_CLASS_NOT_REWRITTEN",
                          "detail": sorted(set(required) - moved)}))
        return 1
    pre_files = [capture_lib.class_file(pre_dump, name) for name in required]
    post_files = [capture_lib.class_file(post_dump, name) for name in required]

    pre_receipts = capture_lib.identity_lines(java, classpath, pre_files)
    post_receipts = capture_lib.identity_lines(java, classpath, post_files)
    pre_dumps = dict(zip(required, capture_lib.identity_dumps(java, classpath, pre_files)))

    classes = {name: {"semantic_sha256": receipt[2], "declaration_order_sha256": receipt[3]}
               for name, receipt in zip(required, post_receipts)}
    pre_classes = {name: {"semantic_sha256": receipt[2], "declaration_order_sha256": receipt[3]}
                   for name, receipt in zip(required, pre_receipts)}

    sites = writer_sites_from(pre_dumps, hooks)

    capture_config = {"java_home": str(args.java_home), "dll": str(args.dll),
                      "forge_manifest": str(args.runtime), "capture_root": str(args.output / "engine-captures"),
                      "identity_classpath": [str(p) for p in classpath],
                      "writer_sites": [{"id": s["id"], "class": s["class"], "method": s["method"],
                                        "descriptor": s["descriptor"]} for s in sites]}
    capture_config_path = args.output / "collector-config.json"
    capture_config_path.write_text(json.dumps(capture_config, indent=2) + "\n", encoding="utf-8")

    validator_config = {"java_home": str(args.java_home), "plan": str(PLAN), "profile": str(PROFILE),
                        "pre_dump": str(pre_dump), "post_dump": str(post_dump)}
    validator_config_path = args.output / "placement-config.json"
    validator_config_path.write_text(json.dumps(validator_config, indent=2) + "\n", encoding="utf-8")

    runtime_config = json.loads(args.runtime.read_text(encoding="utf-8"))
    server_root = Path(runtime_config["server_root"])
    pins = json.loads((ROOT / "tools/forge-capture/runtime-pins.json").read_text(encoding="utf-8"))
    # The inventory must cover the whole pinned runtime the launch actually
    # loads from -- the two server jars and every library -- because a file the
    # inventory does not name is a file whose drift the end-of-run check cannot
    # see. Roots and file map are walked the same way the engine walks them, so
    # an inventory that is not exhaustive fails here rather than at the engine.
    roots = {"artifacts": ["forge-1.12.2-14.23.5.2860.jar", "minecraft_server.1.12.2.jar", "libraries"],
             "mods": ["mods"], "config": ["config"]}
    inventory = {}
    for section, names in roots.items():
        files = {}
        for name in names:
            entry = server_root / name
            paths = [entry] if entry.is_file() else sorted(entry.rglob("*"))
            for found in paths:
                if found.is_file():
                    files[found.relative_to(server_root).as_posix()] = sha(found)
        inventory[section] = {"roots": names, "files": files}

    manifest = {
        "schema": "RUSTCRAFT_RUNTIME_MANIFEST_V2", "runtime_root": str(server_root),
        "inventories": inventory,
        "collector": command(HERE / "collector.py", ["--config", str(capture_config_path)],
                             extra=(capture_config_path,)),
        "identity_tool": {"java": str(java), "java_sha256": sha(java),
                          "classpath": [classpath_entry(p) for p in classpath],
                          "timeout_seconds": 900},
        "validators": {"placement": command(HERE / "placement_validator.py",
                                            ["--config", str(validator_config_path)],
                                            extra=(validator_config_path,))},
    }
    manifest_path = args.output / "manifest.json"
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")

    profile_id = args.id or bootstrap["target"]
    engine_profile = {
        "schema": "RUSTCRAFT_QUALIFICATION_PROFILE_V2",
        "id": profile_id,
        "identity_mode": "CANONICAL_ID_V2",
        "runtime_identity": capture_lib.runtime_identity(bootstrap),
        "transformer_chain": capture_lib.transformer_chain(bootstrap),
        "coremods": capture_lib.coremods(bootstrap),
        "classes": classes, "pre_classes": pre_classes,
        "writer_sites": sites,
        "negative_controls": [{"id": "raw-and-canonical-both-move", "expected_outcome": "CHANGED"},
                              {"id": "declaration-order-is-significant", "expected_outcome": "CHANGED"},
                              {"id": "session-bound-without-certificate", "expected_outcome": "REJECTED"}],
        "frame_evidence": {"required_classes": required},
        "scope": {"schema": "LIVE_CAPTURE_SCOPE_V1", "operation": "chunk_packet_shadow_capture",
                  "profile_id": profile_id, "dimension": 0, "storage_family": "VANILLA_U16",
                  "registry_epoch": 1, "state_width_bits": 16,
                  "generator_family": "VANILLA_FLAT_ONLY", "skylight": True, **SCOPE_CLASSES},
        "production_authority": False,
    }
    # The live scope names classes the writer must leave alone. They are part of
    # the requalification story -- "this profile covers these classes" -- but they
    # are not transformed classes, so the engine's profile schema has no place for
    # them. Recorded beside the profile rather than inside it: the narrowing from
    # scope to transformed set has to be auditable, not a quiet edit.
    (args.output / "context-classes.json").write_text(json.dumps(
        {"schema": "RUSTCRAFT_SCOPE_CONTEXT_V1", "scope_classes": sorted(scope),
         "transformed_classes": required, "untouched_context_classes": untouched_context,
         "untouched_verified_byte_identical_pre_post": True}, indent=2) + chr(10), encoding="utf-8")
    profile_path = args.output / "profile.json"
    profile_path.write_text(json.dumps(engine_profile, indent=2) + "\n", encoding="utf-8")

    engine = qualification_engine.QualificationEngine(manifest_path, profile_path, args.output / "runs")
    certificate = engine.run(Maturity.OFFLINE_QUALIFIED)

    # Materialise the human-facing evidence at the REQUESTED output. Best
    # effort by the same reasoning as the generic driver: a caller whose
    # requested path cannot hold one more component has still received a
    # verdict, and the workspace holds the canonical evidence either way.
    keep = ["profile.json", "manifest.json", "context-classes.json",
            "collector-config.json", "placement-config.json", "logs"]
    unwritable = []
    for name in keep:
        source = workspace / name
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
            unwritable.append({"entry": name, "attempted_path": str(target),
                               "measured_length": len(str(target)),
                               "error": failure.strerror or str(failure)})
    for run_dir in sorted((workspace / "runs").glob("*")):
        for name in ("certificate.json", "observation.json", "process-log.json"):
            source = run_dir / name
            if not source.is_file():
                continue
            destination = requested / "runs" / run_dir.name / name
            try:
                destination.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(source, destination)
            except OSError as failure:
                unwritable.append({"entry": "runs/" + run_dir.name + "/" + name,
                                   "attempted_path": str(destination),
                                   "measured_length": len(str(destination)),
                                   "error": failure.strerror or str(failure)})
    if unwritable:
        (workspace / "MATERIALISATION_FAILURES.json").write_text(json.dumps(
            {"schema": "RUSTCRAFT_MATERIALISATION_DIAGNOSTIC_V1",
             "requested_output": str(requested),
             "measured_requested_length": len(str(requested)),
             "windows_max_path": 260,
             "explanation": "the requested output directory is close enough to the Windows "
                            "MAX_PATH limit that no child path can be created; the "
                            "qualification ran and its canonical evidence is in the "
                            "internal workspace recorded below",
             "recommended_remediation": "use a shorter output directory, or read the "
                                        "evidence from internal_workspace_path",
             "entries": unwritable}, indent=2) + chr(10), encoding="utf-8")

    print(json.dumps({"status": certificate["status"], "maturity": certificate["maturity"],
                      "production_authority": False,
                      "profile": str(profile_path), "manifest": str(manifest_path),
                      "certificate": str(engine.output / "certificate.json"),
                      "bootstrap_receipt_sha256": sha(args.output / "bootstrap" / "forge-runtime-result.json"),
                      "requested_output": str(requested),
                      "internal_workspace": str(workspace),
                      "receipts_materialised": not unwritable}, indent=2))
    return {"PASS": 0, "FAIL": 1, "INCOMPLETE": 2}[certificate["status"]]


if __name__ == "__main__":
    sys.exit(main())
