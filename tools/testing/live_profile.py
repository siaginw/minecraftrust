"""Fail-closed live-writer profile verifier and qualification driver.

Required live-writer hooks (tools/live-capture/required-live-writer-hooks.json) are
checked against the actual transformed runtime produced by a fresh-JVM Forge 2860
offline qualification (forge_runtime.execute, qualification_only). Every required
descriptor must classify as exactly one of the vocabulary statuses; the profile may
arm a future live writer protocol only when ALL required hooks are QUALIFIED.

No wildcards, no partial best-effort profile, and no runtime hook is installed by
this module: it observes and verifies only. Wrong or missing runtime artifacts yield
UNSUPPORTED_RUNTIME / INCOMPLETE results, never PASS.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[2]
REQUIRED_HOOKS = ROOT / "tools/live-capture/required-live-writer-hooks.json"
COMMITTED_PROFILE = ROOT / "tools/live-capture/live-shadow-profile.json"
FORGE_RUNTIME = ROOT / ".rustcraft-local/forge-runtime.json"

STATUS_VOCABULARY = ("QUALIFIED", "MISSING_CLASS", "MISSING_METHOD", "DESCRIPTOR_MISMATCH",
                     "HASH_MISMATCH", "FINGERPRINT_MISMATCH", "AMBIGUOUS_MATCH",
                     "TRANSFORM_ORDER_MISMATCH", "UNSUPPORTED_RUNTIME")

# javap method declaration line, e.g. "  public boolean func_180501_a(net.minecraft..., int);"
DECLARATION = re.compile(r"^  (?:public |protected |private |static |final |abstract |synchronized |native )*"
                         r"[\w.$<>\[\], ]+?\s(\S+)\(([^()]*(?:\([^()]*\)[^()]*)*)\)"
                         r"(?:\s+throws\s+[\w.$, ]+)?;$")
DESCRIPTOR_LINE = re.compile(r"^    descriptor: (.+)$")
INSTRUCTION = re.compile(r"^ *(\d+): (\S+)(.*)$")


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def file_sha256(path: Path) -> str:
    with Path(path).open("rb") as stream:
        return sha256_bytes(stream.read())


def parse_javap(text: str, class_name: str = None) -> dict:
    """Parses `javap -p -s -c` output into {declaration_name: {"descriptors": [desc],
    "instructions": [(bci, opcode, rest)]}} (per declaration, overload-safe).
    Constructors print under the class simple name and are normalized to <init>."""
    methods = {}
    current = None
    simple = class_name.rsplit(".", 1)[-1] if class_name else None
    for line in text.splitlines():
        match = DECLARATION.match(line)
        if match:
            name = match.group(1)
            if simple and name.rsplit(".", 1)[-1] == simple:
                name = "<init>"
            current = (name, len(methods))
            methods[current] = {"name": name, "descriptor": None, "instructions": []}
            continue
        if current is None:
            continue
        descriptor = DESCRIPTOR_LINE.match(line)
        if descriptor:
            methods[current]["descriptor"] = descriptor.group(1)
            continue
        instruction = INSTRUCTION.match(line)
        if instruction:
            methods[current]["instructions"].append(
                (int(instruction.group(1)), instruction.group(2), instruction.group(3).strip()))
    return methods


def class_methods(parsed: dict) -> list:
    """Flattens parsed declarations to [{name, descriptor, instructions}].

    Accepts either a parse_javap map or a pre-flattened list (tests)."""
    values = parsed.values() if isinstance(parsed, dict) else parsed
    return [{"name": item["name"], "descriptor": item["descriptor"], "instructions": item["instructions"]}
            for item in values]


def run_javap(javap: Path, transformed_dir: Path, cls: str, with_code: bool) -> str:
    args = [str(javap), "-p", "-s"]
    if with_code:
        args.append("-c")
    args += ["-classpath", str(transformed_dir), cls]
    result = subprocess.run(args, capture_output=True, text=True)
    if result.returncode:
        raise RuntimeError("javap failed for %s: %s" % (cls, result.stderr.strip()))
    return result.stdout


def normalize_instruction(opcode: str, rest: str) -> tuple:
    rest = re.sub(r"#\d+", "#", rest)
    rest = re.sub(r"\s+", " ", rest).strip()
    return opcode, rest


def instruction_at(instructions: list, bci: int):
    for offset, opcode, rest in instructions:
        if offset == bci:
            return normalize_instruction(opcode, rest)
    return None


def fingerprint_matches(fingerprint: dict, method: dict, class_text: str) -> list:
    """Returns [{bci/marker, expected, observed, match}] without ever guessing."""
    results = []
    kind = fingerprint["kind"]
    if kind == "DECLARATION":
        results.append({"marker": "exact class identity + exact method descriptor",
                        "expected": "DECLARATION", "observed": "DECLARATION", "match": True})
        return results
    if kind == "CLASS_STRUCTURE":
        for marker in fingerprint["markers"]:
            if marker.startswith("declares "):
                token = marker[len("declares "):].split(" ")[0]
                results.append({"marker": marker, "expected": token,
                                "observed": token if token in class_text else "ABSENT",
                                "match": token in class_text})
            elif "calls" in marker:
                inside, _, callee = marker.split(" ")
                found = False
                for item in class_methods({"m": {"name": "", "descriptor": None,
                                                 "instructions": _instructions_of(class_text, inside)}}):
                    found = found or any(callee in rest for _, _, rest in item["instructions"])
                results.append({"marker": marker, "expected": callee,
                                "observed": callee if found else "ABSENT", "match": found})
            else:
                results.append({"marker": marker, "expected": marker, "observed": "UNKNOWN_MARKER",
                                "match": False})
        return results
    if kind == "BCI_ASSERTIONS":
        for assertion in fingerprint["assertions"]:
            expected = assertion["expect"]
            eopcode, _, erest = expected.partition(" ")
            observed = instruction_at(method["instructions"], int(assertion["bci"]))
            if observed is None:
                results.append({"bci": assertion["bci"], "expected": expected,
                                "observed": "ABSENT", "match": False})
                continue
            opcode, rest = observed
            match = opcode == eopcode and (not erest or erest in rest)
            results.append({"bci": assertion["bci"], "expected": expected,
                            "observed": (opcode + " " + rest).strip(), "match": match})
        return results
    results.append({"marker": "fingerprint kind", "expected": "known kind",
                    "observed": kind, "match": False})
    return results


def _instructions_of(class_text: str, method_name: str):
    """Best-effort extraction of one method's instruction tuples from raw javap -c text."""
    parsed = parse_javap(class_text)
    for item in parsed.values():
        if item["name"] == method_name:
            return item["instructions"]
    return []


def identity_failures(manifest: dict, receipt: dict, pins: dict, qualification: dict) -> list:
    """Exact runtime identity binding; any failure disqualifies the whole profile.
    `qualification` is the parsed fresh-JVM qualification manifest."""
    failures = []
    requirements = manifest["identity_requirements"]
    if receipt.get("status") != "PASS":
        failures.append("runtime qualification receipt status=%s reason=%s" %
                        (receipt.get("status"), receipt.get("reason", "unknown")))
        return failures
    if receipt.get("production_authority") is not False:
        failures.append("production authority flag is not false")
    artifacts = {Path(item["path"]).name: item["sha256"] for item in receipt["qualified_inputs"]["artifacts"]}
    if artifacts.get("minecraft_server.1.12.2.jar") != requirements["minecraft_server_jar_sha256"]:
        failures.append("minecraft server jar identity mismatch (wrong build or substituted artifact)")
    forge_name = [name for name in artifacts if name.startswith("forge-1.12.2-")]
    if not forge_name or not forge_name[0].endswith("-" + manifest["forge_build"] + ".jar"):
        failures.append("forge build is not %s: %s" % (manifest["forge_build"], forge_name))
    elif artifacts[forge_name[0]] != requirements["forge_jar_sha256"]:
        failures.append("forge jar identity mismatch")
    if qualification.get("profile") != requirements["qualification_profile"]:
        failures.append("qualification profile mismatch")
    if qualification.get("java_runtime_version") != requirements["java_runtime_version"]:
        failures.append("JVM is not the qualified runtime: %s" % qualification.get("java_runtime_version"))
    if qualification.get("transformers") != pins.get("qualified_transformers"):
        failures.append("Forge transformer inventory changed (transform-order identity broken)")
    if qualification.get("registry_identity_sha256") != pins.get("registry_identity_sha256"):
        failures.append("registry identity changed")
    committed_pins = manifest["identity_requirements"]["runtime_pins_sha256"]
    if receipt["qualified_inputs"]["pins"]["sha256"] != committed_pins:
        failures.append("runtime pins identity changed")
    for relative, expected in requirements["foundation_implementation"].items():
        actual = file_sha256(ROOT / relative)
        if actual != expected:
            failures.append("foundation implementation changed: %s" % relative)
    return failures


def hook_status(hook: dict, class_files: dict, expected_hashes: dict, vanilla_hashes: dict,
                parsed_classes: dict, committed_profile: dict) -> dict:
    """One terminal status per required descriptor; never a best-effort pass."""
    observed = {"class_sha256": None, "fingerprint_results": []}
    if hook["descriptor"] != hook["descriptor"].strip() or "*" in hook["descriptor"] or "?" in hook["descriptor"] \
            or hook["descriptor"] == "":
        return {"observed": observed, "status": "AMBIGUOUS_MATCH", "detail": "inexact/wildcard descriptor refused"}
    if class_files.get(hook["class"]) is None:
        return {"observed": observed, "status": "MISSING_CLASS", "detail": "no transformed definition observed"}
    class_sha = class_files[hook["class"]]
    observed["class_sha256"] = class_sha
    expected = expected_hashes.get(hook["class"])
    if expected is not None and class_sha != expected:
        if vanilla_hashes.get(hook["class"]) == class_sha:
            return {"observed": observed, "status": "TRANSFORM_ORDER_MISMATCH",
                        "detail": "observed bytes equal the untransformed vanilla class"}
        return {"observed": observed, "status": "HASH_MISMATCH", "detail": "expected %s" % expected}
    committed = (committed_profile or {}).get(hook["class"])
    if committed is not None and class_sha != committed:
        return {"observed": observed, "status": "HASH_MISMATCH", "detail": "committed profile expected %s" % committed}
    parsed = parsed_classes.get(hook["class"])
    if parsed is None:
        return {"observed": observed, "status": "MISSING_METHOD", "detail": "javap observation failed"}
    named = [item for item in class_methods(parsed) if item["name"] == hook["method"]]
    if not named:
        return {"observed": observed, "status": "MISSING_METHOD", "detail": "method absent from transformed class"}
    exact = [item for item in named if item["descriptor"] == hook["descriptor"]]
    if not exact:
        return {"observed": observed, "status": "DESCRIPTOR_MISMATCH",
                "detail": "declared: %s" % sorted({item["descriptor"] for item in named})}
    if len(exact) > 1:
        return {"observed": observed, "status": "AMBIGUOUS_MATCH", "detail": "duplicate candidate methods"}
    fingerprint_results = fingerprint_matches(hook["fingerprint"], exact[0], parsed_classes.get("_text", {}).get(hook["class"], ""))
    observed["fingerprint_results"] = fingerprint_results
    if not all(item["match"] for item in fingerprint_results):
        return {"observed": observed, "status": "FINGERPRINT_MISMATCH",
                "detail": "; ".join("%s expected %r observed %r" %
                                    (item.get("bci", item.get("marker")), item["expected"], item["observed"])
                                    for item in fingerprint_results if not item["match"])}
    return {"observed": observed, "status": "QUALIFIED", "detail": None}


def context_status(entry: dict, class_files: dict, parsed_classes: dict, expected_hashes: dict) -> dict:
    if class_files.get(entry["class"]) is None:
        return dict(entry, status="MISSING_CLASS")
    parsed = parsed_classes.get(entry["class"])
    if parsed is None:
        return dict(entry, status="MISSING_METHOD")
    named = [item for item in class_methods(parsed) if item["name"] == entry["method"]]
    if not named:
        return dict(entry, status="MISSING_METHOD")
    if not any(item["descriptor"] == entry["descriptor"] for item in named):
        return dict(entry, status="DESCRIPTOR_MISMATCH")
    expected = expected_hashes.get(entry["class"])
    if expected is not None and class_files[entry["class"]] != expected:
        return dict(entry, status="HASH_MISMATCH")
    return dict(entry, status="QUALIFIED")


def vanilla_class_hashes(server_jar: Path, classes: list) -> dict:
    """SHA-256 of the untransformed entries inside the pinned vanilla server jar."""
    result = {}
    with zipfile.ZipFile(server_jar) as jar:
        names = {info.filename for info in jar.infolist()}
        for cls in classes:
            entry = cls.replace(".", "/") + ".class"
            if entry in names:
                result[cls] = sha256_bytes(jar.read(entry))
    return result


def verify(manifest: dict, receipt: dict, pins: dict, transformed_dir: Path, javap: Path,
           committed_profile_path: Path = None) -> dict:
    """Full fail-closed verification; produces the machine-readable result document."""
    result = {"schema_version": 1, "kind": "LIVE_SHADOW_PROFILE_QUALIFICATION_V1",
              "target": manifest["target"], "forge_build": manifest["forge_build"],
              "required_hook_count": manifest["required_hook_count"]}
    committed = None
    if committed_profile_path is not None and Path(committed_profile_path).is_file():
        committed = json.loads(Path(committed_profile_path).read_text(encoding="utf-8"))
        committed_hashes = committed.get("expected_class_hashes", {})
    else:
        committed_hashes = {}
    qualification_manifest = json.loads(Path(receipt["qualification"]["manifest"]).read_text(encoding="utf-8"))
    identity_problems = identity_failures(manifest, receipt, pins, qualification_manifest)
    if identity_problems:
        result.update(status="UNSUPPORTED_RUNTIME", identity_failures=identity_problems,
                      all_required_qualified=False,
                      required_hooks=[dict(hook, status="UNSUPPORTED_RUNTIME", observed={})
                                      for hook in manifest["required_hooks"]])
        return result
    qualification = json.loads(Path(receipt["qualification"]["manifest"]).read_text(encoding="utf-8"))
    observed_hashes = qualification["transformed_classes"]
    server_jar = next(Path(item["path"]) for item in receipt["qualified_inputs"]["artifacts"]
                      if Path(item["path"]).name == "minecraft_server.1.12.2.jar")
    hook_classes = sorted({hook["class"] for hook in manifest["required_hooks"]})
    context_classes = sorted({item["class"] for item in manifest["context_definitions"]})
    class_files = {}
    for cls in hook_classes + context_classes:
        path = transformed_dir / (cls.replace(".", "/") + ".class")
        if path.is_file():
            class_files[cls] = file_sha256(path)
    expected_hashes = {}
    for cls in hook_classes + context_classes:
        if cls in observed_hashes:
            expected_hashes[cls] = observed_hashes[cls]
        elif cls in committed_hashes:
            expected_hashes[cls] = committed_hashes[cls]
    vanilla_hashes = vanilla_class_hashes(server_jar, hook_classes)
    parsed_classes, class_text = {}, {}
    for cls in hook_classes + context_classes:
        if cls in class_files:
            text = run_javap(javap, transformed_dir, cls, with_code=True)
            parsed_classes[cls] = parse_javap(text, cls)
            class_text[cls] = text
    parsed_classes["_text"] = class_text
    baseline = {cls: observed_hashes[cls] for cls in hook_classes if cls not in expected_hashes}
    records, failures = [], []
    for hook in manifest["required_hooks"]:
        outcome = hook_status(hook, class_files, expected_hashes, vanilla_hashes, parsed_classes, committed_hashes)
        record = {"id": hook["id"], "purpose": hook["purpose"], "class": hook["class"],
                  "method": hook["method"], "descriptor": hook["descriptor"],
                  "handoff_hook_mode": hook["handoff_hook_mode"], "hook_type": hook["hook_type"],
                  "bracket_end_hook_type": hook.get("bracket_end_hook_type"),
                  "affected_domains": hook["affected_domains"], "thread_context": hook["thread_context"],
                  "insertion_location": hook["insertion_location"], "fingerprint_kind": hook["fingerprint"]["kind"],
                  "status": outcome["status"], "observed": outcome["observed"]}
        if outcome.get("detail"):
            record["detail"] = outcome["detail"]
        records.append(record)
        if outcome["status"] != "QUALIFIED":
            failures.append({"id": hook["id"], "status": outcome["status"], "detail": outcome.get("detail", "")})
    context_records = [context_status(entry, class_files, parsed_classes, expected_hashes)
                       for entry in manifest["context_definitions"]]
    result.update(status="QUALIFIED" if not failures else "NOT_QUALIFIED",
                  all_required_qualified=not failures,
                  identity_failures=[],
                  qualification={"profile": qualification.get("profile"),
                                 "java_runtime_version": qualification.get("java_runtime_version"),
                                 "registry_identity_sha256": qualification.get("registry_identity_sha256"),
                                 "transformer_count": len(qualification.get("transformers", [])),
                                 "runtime_pins_sha256": receipt["qualified_inputs"]["pins"]["sha256"],
                                 "minecraft_server_jar_sha256": file_sha256(server_jar),
                                 "transformed_class_count": len(observed_hashes)},
                  expected_class_hashes={cls: observed_hashes[cls] for cls in hook_classes + context_classes if cls in observed_hashes},
                  baseline_established=baseline,
                  required_hooks=records,
                  nonqualified=failures,
                  context_definitions=context_records,
                  context_all_qualified=all(item["status"] == "QUALIFIED" for item in context_records))
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--java-home", type=Path, required=True)
    parser.add_argument("--dll", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, default=FORGE_RUNTIME)
    parser.add_argument("--required-hooks", type=Path, default=REQUIRED_HOOKS)
    parser.add_argument("--committed-profile", type=Path, default=COMMITTED_PROFILE)
    parser.add_argument("--write-profile", action="store_true",
                        help="write/update tools/live-capture/live-shadow-profile.json on QUALIFIED")
    args = parser.parse_args()
    manifest = json.loads(args.required_hooks.read_text(encoding="utf-8"))
    import forge_runtime
    output = args.output.resolve()
    if output.exists():
        print("output directory already exists: %s" % output)
        return 1
    receipt = forge_runtime.execute(ROOT, output, args.java_home, args.dll, args.manifest,
                                    qualification_only=True)
    if receipt.get("status") != "PASS":
        result = {"schema_version": 1, "kind": "LIVE_SHADOW_PROFILE_QUALIFICATION_V1",
                  "status": "UNSUPPORTED_RUNTIME" if receipt.get("reason") == "ARTIFACT_MISMATCH" else "INCOMPLETE",
                  "reason": receipt.get("reason"), "detail": receipt.get("detail"),
                  "all_required_qualified": False}
    else:
        pins = json.loads((ROOT / "tools/forge-capture/runtime-pins.json").read_text(encoding="utf-8"))
        javap = args.java_home / "bin/javap.exe"
        result = verify(manifest, receipt, pins, Path(receipt["qualification"]["transformed_dir"]),
                        javap, args.committed_profile)
        result["run_receipt"] = str(output / "forge-runtime-result.json")
    output.mkdir(parents=True, exist_ok=True)
    results_path = output / "live-profile-results.json"
    results_path.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print("live-profile status=%s all_required_qualified=%s nonqualified=%d" %
          (result["status"], result["all_required_qualified"], len(result.get("nonqualified", []))))
    if args.write_profile and result["status"] == "QUALIFIED":
        profile = {"schema_version": 1, "kind": "LIVE_SHADOW_WRITER_PROFILE_V1",
                   "target": manifest["target"], "forge_build": manifest["forge_build"],
                   "required_hooks_manifest_sha256": file_sha256(args.required_hooks),
                   "identity_requirements": manifest["identity_requirements"],
                   "expected_class_hashes": result["expected_class_hashes"],
                   "qualification": result["qualification"],
                   "required_hooks": result["required_hooks"],
                   "nonqualified": result["nonqualified"],
                   "context_definitions": result["context_definitions"],
                   "all_required_qualified": result["all_required_qualified"],
                   "arming_rule": manifest["arming_rule"],
                   "note": "Profile qualification evidence only. This file is NOT an arming switch and "
                           "does not enable any runtime behavior; production remains fail-closed."}
        args.committed_profile.write_text(json.dumps(profile, indent=2) + "\n", encoding="utf-8")
        print("committed profile: %s" % args.committed_profile)
    return 0 if result["all_required_qualified"] else 1


if __name__ == "__main__":
    sys.exit(main())
