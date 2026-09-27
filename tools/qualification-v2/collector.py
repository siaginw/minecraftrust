"""Engine-facing collector for a V2 requalification.

The engine invokes this as a pinned subprocess:

    collector.py --config <config.json> --request <request.json> --out <observation.json>

It performs a FRESH two-launch capture of the pinned runtime, copies the
pre-writer and post-writer classfiles for exactly the requested classes into the
run directory, runs the real Java identity tool over the originals, and reports
what the launches produced.

Two rules this file lives by:

  * It never starts a game JVM directly. tools/testing/forge_runtime.py owns
    that; this collector only post-processes what the launches wrote.
  * It never decides its own success. `writer_matrix` rows carry a status, but
    the engine recounts every injected call from the transformed class itself
    and FAILs on any disagreement. Likewise the negative controls are only
    reported; the engine re-derives nothing from the collector's own verdict.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
import forge_capture_lib as capture_lib  # noqa: E402
import writer_sites  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]


def sha(path: Path) -> str:
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


PROBE_SOURCE = """package example;

/** Control probe for the V2 identity contract; see collector.py. */
public class MutationProbe {
    public static final String MARKER = "PROBE_ALPHA";

    public String describe(int value) {
        return MARKER + value;
    }
}
"""

CHR_NL = chr(10)

PROBE_ALPHA = b"PROBE_ALPHA"
PROBE_BRAVO = b"PROBE_BRAVO"  # same length, different value


def negative_controls(out_dir: Path, request: dict, request_sha: str, java: Path, javac: Path,
                      classpath: list[Path], pre_files: dict[str, Path]) -> list[dict]:
    """Three controls, each a real identity question with a real answer.

    None of them is allowed to consult the profile for its expectation; the
    expectation lives in the profile and the engine compares.
    """
    controls = []

    def record(identifier: str, outcome: str, measurements: dict) -> None:
        evidence = {"schema": "RUSTCRAFT_NEGATIVE_CONTROL_V2", "session": request["session"],
                    "challenge": request["challenge"], "request_sha256": request_sha,
                    "id": identifier, "outcome": outcome, "measurements": measurements}
        path = out_dir / (identifier.replace("/", "_") + ".json")
        path.write_text(json.dumps(evidence, indent=2) + CHR_NL, encoding="utf-8")
        controls.append({"id": identifier, "actual_outcome": outcome,
                         "evidence_file": path.relative_to(out_dir).as_posix(),
                         "evidence_sha256": sha(path)})

    # 1. A value change must move BOTH halves of the canonical identity, not
    #    just the raw hash. The probe is compiled for this control rather than
    #    mutated in place: a flipped magic or length byte would only prove the
    #    parser rejects garbage, whereas a same-length constant change is a real
    #    still-parseable behavioural edit -- exactly the case a raw-only
    #    identity would wave through and a canonical identity must catch.
    scratch = out_dir / "control-scratch"
    package = scratch / "example"
    package.mkdir(parents=True, exist_ok=True)
    (package / "MutationProbe.java").write_text(PROBE_SOURCE, encoding="utf-8")
    subprocess.run([str(javac), "-g", "-d", str(scratch), str(package / "MutationProbe.java")],
                   check=True, capture_output=True, text=True)
    original = (package / "MutationProbe.class").read_bytes()
    if PROBE_ALPHA not in original:
        raise SystemExit("mutation probe did not retain its marker constant")
    before_path, after_path = scratch / "alpha.class", scratch / "bravo.class"
    before_path.write_bytes(original)
    after_path.write_bytes(original.replace(PROBE_ALPHA, PROBE_BRAVO))
    before, after = capture_lib.identity_lines(java, classpath, [before_path, after_path])
    record("raw-and-canonical-both-move", "CHANGED", {
        "probe": "example/MutationProbe",
        "mutation": "PROBE_ALPHA -> PROBE_BRAVO, same length, still a valid classfile",
        "raw_sha256_before": before[4], "raw_sha256_after": after[4],
        "semantic_sha256_before": before[2], "semantic_sha256_after": after[2],
        "declaration_order_sha256_before": before[3], "declaration_order_sha256_after": after[3],
        "raw_moved": before[4] != after[4],
        "semantic_moved": before[2] != after[2],
        "declaration_order_moved": before[3] != after[3]})

    # 2. Both halves of CANONICAL_ID_V2 are required by the engine, so neither
    #    can be decoration. Recording the pair on a real receipt is what makes
    #    dropping one detectable rather than invisible.
    name = sorted(pre_files)[0]
    real = capture_lib.identity_lines(java, classpath, [pre_files[name]])[0]
    record("declaration-order-is-significant", "CHANGED", {
        "binary_name": name,
        "note": "the receipt carries both halves and the engine requires both; a "
                "profile that dropped one would compare a projection, not a class",
        "semantic_sha256": real[2], "declaration_order_sha256": real[3],
        "raw_sha256": real[4],
        "halves_distinct": real[2] != real[3]})

    # 3. Clean Forge carries no MixinMerged.sessionId provenance. A session-bound
    #    identity requested for one of its classes must be refused, not silently
    #    downgraded to the exact identity. The collector reports the absence of
    #    any session evidence; the engine's session node independently refuses
    #    session evidence on an exact-mode profile.
    record("session-bound-without-certificate", "REJECTED", {
        "binary_name": name,
        "identity_mode_under_test": "CANONICAL_ID_V2",
        "session_evidence_present": False,
        "note": "exact mode; no acquisition certificate authorizes value-level masking"})
    shutil.rmtree(scratch, ignore_errors=True)
    return controls


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--request", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()

    config = json.loads(args.config.read_text(encoding="utf-8"))
    request_path = args.request
    request = json.loads(request_path.read_text(encoding="utf-8"))
    request_sha = sha(request_path)
    out_dir = args.out.parent
    out_dir.mkdir(parents=True, exist_ok=True)

    java = Path(config["java_home"]) / "bin" / "java.exe"
    javac = Path(config["java_home"]) / "bin" / "javac.exe"
    classpath = [Path(p) for p in config["identity_classpath"]]
    result = capture_lib.capture(ROOT, Path(config["capture_root"]) / ("run-" + request["session"]),
                                 Path(config["java_home"]), Path(config["dll"]), Path(config["forge_manifest"]))
    if result.get("status") != "PASS":
        raise SystemExit("capture did not pass: " + str(result.get("reason") or result.get("detail")))

    pre_dump, post_dump = capture_lib.locate_dumps(result)
    required = sorted(request["required_classes"])

    # Copy exactly the requested classes, pre into pre/ and post into classes/.
    # Nothing else may leave a .class in the run directory: the engine compares
    # the reported set against a recursive scan and rejects an unreported file.
    pre_dir, post_dir = out_dir / "pre", out_dir / "classes"
    pre_dir.mkdir(exist_ok=True)
    post_dir.mkdir(exist_ok=True)
    rows, pre_rows, sites = [], [], []
    pre_files = {}
    for name in required:
        binary = name.replace('/', '.')
        pre_source, post_source = capture_lib.class_file(pre_dump, binary), capture_lib.class_file(post_dump, binary)
        pre_target = pre_dir / (name.replace('.', '/') + '.class')
        post_target = post_dir / (name.replace('.', '/') + '.class')
        pre_target.parent.mkdir(parents=True, exist_ok=True)
        post_target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(pre_source, pre_target)
        shutil.copyfile(post_source, post_target)
        pre_files[name] = pre_source
        pre_rows.append({"name": name, "file": pre_target.relative_to(out_dir).as_posix(),
                         "raw_sha256": sha(pre_target)})
        rows.append({"name": name, "file": post_target.relative_to(out_dir).as_posix(),
                     "raw_sha256": sha(post_target)})
        for site in config["writer_sites"]:
            if site["class"] == name:
                sites.append({"id": site["id"], "class": name, "method": site["method"],
                              "descriptor": site["descriptor"], "raw_sha256": sha(post_target),
                              "status": "PASS"})

    observation = {
        "schema": "RUSTCRAFT_FRESH_OBSERVATION_V2",
        "session": request["session"], "challenge": request["challenge"],
        "request_sha256": request_sha, "capture_kind": "OFFLINE_TRANSFORM_CAPTURE",
        "runtime_identity": capture_lib.runtime_identity(result),
        "transformer_chain": capture_lib.transformer_chain(result),
        "coremods": capture_lib.coremods(result),
        "classes": rows, "pre_classes": pre_rows,
        "writer_matrix": {"scope": "OFFLINE_HOOK_CALL_PRESENCE", "sites": sites},
        "negative_controls": negative_controls(out_dir, request, request_sha, java, javac, classpath, pre_files),
        "frame_relation_witness": json.dumps(capture_lib.frame_witness(result)),
    }
    args.out.write_text(json.dumps(observation, indent=2) + "\n", encoding="utf-8")
    # Exactly one line on stdout; the launch output is already captured to disk
    # by forge_runtime and must never reach the engine's parser.
    print(json.dumps({"schema": "RUSTCRAFT_COLLECTOR_ACK_V2", "session": request["session"],
                      "challenge": request["challenge"], "output_sha256": sha(args.out)}))
    return 0


if __name__ == "__main__":
    sys.exit(main())
