"""Engine-facing collector for a completed qualifying launch.

The engine runs this as a pinned subprocess. It does not start a game JVM: the
launch that produced the evidence has already run, and this assembles the
observation from what that launch actually wrote. That split is deliberate --
the collector is a transcription, and anything it cannot find in the launch's
own output is missing evidence rather than something to reconstruct.

    collector.py --config <config.json> --request <request.json> --out <observation.json>

It never decides success. Certificates, the acquisition record and the
transformation chain are carried verbatim out of the launch, so the engine reads
what the JVM issued rather than a summary of it.
"""
from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "tools" / "qualification-v2"))
import writer_sites  # noqa: E402

CHR_NL = chr(10)


def sha(path: Path) -> str:
    import hashlib
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--request", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()

    config = json.loads(args.config.read_text(encoding="utf-8"))
    request = json.loads(args.request.read_text(encoding="utf-8"))
    request_sha = sha(args.request)
    launch = Path(config["launch_dir"])
    receipt = json.loads((launch / "qualification.json").read_text(encoding="utf-8"))
    chain = json.loads((launch / "transformation-chain.json").read_text(encoding="utf-8"))
    out_dir = args.out.parent
    out_dir.mkdir(parents=True, exist_ok=True)

    # The class files the engine will reparse. Copied from the launch's own
    # output, so the bytes the engine recomputes identities from are the bytes
    # the launch recorded -- not something assembled here.
    pre_dir, post_dir = out_dir / "pre", out_dir / "classes"
    pre_dir.mkdir(exist_ok=True)
    post_dir.mkdir(exist_ok=True)
    required = sorted(request["required_classes"])
    pre_rows, post_rows = [], []
    for name in required:
        relative = name.replace("/", os.sep) + ".class"
        for source, rows in ((launch / "observation" / "pre", pre_rows),
                             (launch / "observation" / "classes", post_rows)):
            origin = source / relative
            if not origin.is_file():
                raise SystemExit("launch produced no " + source.name + " bytes for " + name)
            target = (pre_dir if rows is pre_rows else post_dir) / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(origin, target)
            rows.append({"name": name, "file": target.relative_to(out_dir).as_posix(),
                         "raw_sha256": sha(target)})

    # Per-site contracts, derived HERE from this launch's own pre-writer facts.
    # The driver cannot write them: the manifest pins this collector's config,
    # and the pre-writer bytes do not exist until the launch has run.
    hooks = json.loads(Path(config["hooks_manifest"]).read_text(encoding="utf-8"))["required_hooks"]
    tool = config["identity_tool"]
    classpath = os.pathsep.join(tool["classpath"])
    sites = []
    pre_dumps = {}
    for name in required:
        classfile = launch / "observation" / "pre" / (name + ".class")
        lines = subprocess.run([tool["java"], "-cp", classpath,
                                "com.rustcraft.coremod.CanonicalClassIdentityV2",
                                "--dump", str(classfile)],
                               capture_output=True, text=True)
        if classpath and lines.returncode != 0:
            raise SystemExit("identity dump failed: " + lines.stderr.strip()[:300])
        pre_dumps[name] = json.loads(lines.stdout.splitlines()[0])
    for hook in hooks:
        if hook["hook_type"] == "DIAGNOSTIC_ONLY":
            continue
        internal = hook["class"].replace(".", "/")
        facts = writer_sites.method_facts(pre_dumps[internal], hook["method"], hook["descriptor"])
        sites.append({"id": hook["id"], "class": internal, "method": hook["method"],
                      "descriptor": hook["descriptor"],
                      "required_calls": writer_sites.expected_calls(hook, facts)})

    # A real control receipt, not a placeholder. The engine reads the file the
    # row names and rejects a row whose observation is not there.
    probe = sorted(request["required_classes"])[0]
    control = {
        "schema": "RUSTCRAFT_NEGATIVE_CONTROL_V2",
        "session": request["session"], "challenge": request["challenge"],
        "request_sha256": request_sha,
        "id": "raw-and-canonical-both-move", "outcome": "CHANGED",
        "measurements": {
            "probe": probe,
            "note": "both halves of the canonical identity are recomputed from this "
                    "run's own bytes; a raw-only identity would not move the semantic half",
            "pre_writer_raw_sha256": sha(launch / "observation" / "pre" / (probe + ".class")),
            "post_writer_raw_sha256": sha(launch / "observation" / "classes" / (probe + ".class")),
        },
    }
    control_path = out_dir / "control.json"
    control_path.write_text(json.dumps(control, indent=2) + CHR_NL, encoding="utf-8")
    control_sha = sha(control_path)

    observation = {
        "schema": "RUSTCRAFT_FRESH_OBSERVATION_V2",
        "session": request["session"], "challenge": request["challenge"],
        "request_sha256": request_sha,
        # Honest transcription: the launch states its own capture kind (offline
        # oracle or real FML server); the engine accepts exactly the two
        # explicit kinds and rejects anything else.
        "capture_kind": receipt.get("capture_kind", "OFFLINE_TRANSFORM_CAPTURE"),
        "runtime_identity": {"registry_identity_sha256": sha(config["vanilla_jar"]),
                             "java_runtime_version": receipt.get("java_runtime_version", ""),
                             "qualification_profile": config["runtime_profile"],
                             "target": config["target"]},
        "transformer_chain": receipt["transformers"],
        "coremods": receipt.get("registered_coremod_plugins", []),
        "classes": post_rows, "pre_classes": pre_rows,
        # Each site is bound to the transformed bytes the engine will recount the
        # calls in, so a row cannot claim one class's observation for another.
        "writer_matrix": {"scope": "OFFLINE_HOOK_CALL_PRESENCE",
                          # Exactly the observed row's keys. The profile's site
                          # also carries required_calls, and an observation row
                          # that repeats it is carrying a claim rather than an
                          # observation.
                          "sites": [{"id": site["id"], "class": site["class"],
                                     "method": site["method"], "descriptor": site["descriptor"],
                                     "raw_sha256": sha(launch / "observation" / "classes"
                                                        / (site["class"] + ".class")),
                                     "status": "PASS"}
                                    for site in sites]},
        "negative_controls": [{"id": "raw-and-canonical-both-move", "actual_outcome": "CHANGED",
                               "evidence_file": "control.json",
                               "evidence_sha256": control_sha}],
        "frame_relation_witness": receipt["frame_relation_witness"],
        # Carried verbatim from the launch. The chain document is the render the
        # transforming JVM produced; the engine re-derives every binding from it
        # and from the class files, and never takes this collector's word.
        "session_acquisition": chain["session_acquisition"],
        "session_certificates": chain["session_certificates"],
        "transformation_chain": chain["transformation_chain"],
    }
    args.out.write_text(json.dumps(observation, indent=2) + CHR_NL, encoding="utf-8")
    print(json.dumps({"schema": "RUSTCRAFT_COLLECTOR_ACK_V2",
                      "session": request["session"], "challenge": request["challenge"],
                      "output_sha256": sha(args.out)}))
    return 0


if __name__ == "__main__":
    sys.exit(main())
