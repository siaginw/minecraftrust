"""Historical Revelation V1 regression preservation, never V2 qualification.

Reads explicitly supplied preserved classfiles; compiles accepted-baseline and
current helpers into a fresh isolated output. The existing legacy verifier and
its ten controls execute unchanged except for explicit test path injection and
a strict subprocess adapter. Embedded class observations are emptied, so they
cannot substitute for the actual classfile corpus. This intentionally does not
repair or claim to generalize the legacy verifier; H2 owns that replacement.
"""
from __future__ import annotations

import argparse
import contextlib
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import uuid

ROOT = Path(__file__).resolve().parents[2]
BASELINE = "c4b868db2c9e4b03f745bf93c6ebf4fd8a8519e7"
CORE = "tools/bridge/src/com/rustcraft/coremod/"
HELPER = "tools/forge-capture/src/com/rustcraft/revdiag/RevCanonicalHash.java"


def sha(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def dump_inventory(dump: Path) -> dict[str, str]:
    files = sorted(dump.rglob("*.class"))
    if not files:
        raise ValueError("historical class corpus is empty")
    return {str(p.relative_to(dump)).replace("\\", "/"): sha(p) for p in files}


def parse_hashes(stdout: str, stderr: str, code: int, inventory: dict[str, str]) -> dict[str, str]:
    if code != 0 or stderr:
        raise ValueError(f"canonical helper failed: exit={code}, stderr={stderr!r}")
    observed = {}
    for line in stdout.splitlines():
        match = re.fullmatch(r"([^=\r\n]+)=([0-9a-f]{64})", line)
        if not match or match[1] in observed:
            raise ValueError("malformed or duplicate canonical helper output")
        observed[match[1]] = match[2]
    expected_names = {name[:-6].replace("/", ".") for name in inventory}
    if set(observed) != expected_names:
        raise ValueError("helper output class count/set does not match actual corpus")
    return observed


def command(argv: list[str], records: list[dict], **kwargs) -> subprocess.CompletedProcess:
    try:
        result = subprocess.run(argv, cwd=ROOT, capture_output=True, text=True, timeout=900, **kwargs)
    except subprocess.TimeoutExpired as error:
        records.append({"command": argv, "returnCode": None, "timedOut": True,
                        "stdout": str(error.stdout or ""), "stderr": str(error.stderr or "")})
        raise
    records.append({"command": argv, "returnCode": result.returncode,
                    "stdout": result.stdout, "stderr": result.stderr})
    return result


def require_success(result: subprocess.CompletedProcess) -> None:
    if result.returncode:
        raise ValueError(f"command failed: {result.args}: {result.returncode}: {result.stderr}")


def worker() -> int:
    """Test-only adapter: only the known legacy Java helper invocation is replaced."""
    parser = argparse.ArgumentParser()
    parser.add_argument("--profile", type=Path, required=True)
    args = parser.parse_args()
    config = json.loads(Path(os.environ["RUSTCRAFT_H1_LEGACY_CONFIG"]).read_text(encoding="utf-8"))
    work = Path(config["out"]) / "controls" / uuid.uuid4().hex
    work.mkdir(parents=True, exist_ok=False)
    document = json.loads(args.profile.read_text(encoding="utf-8"))
    (work / "profile.json").write_text(json.dumps(document, indent=2) + "\n", encoding="utf-8")
    records, helpers = [], []
    real_run = subprocess.run

    def strict_helper(argv, **kwargs):
        if (len(argv) != 5 or argv[1] != "-cp"
                or argv[3] != "com.rustcraft.revdiag.RevCanonicalHash"
                or Path(argv[4]).resolve() != Path(config["dump"])):
            raise ValueError("unexpected legacy helper subprocess")
        if dump_inventory(Path(config["dump"])) != config["corpus"]:
            raise ValueError("historical corpus changed during controls")
        if any(sha(Path(path)) != expected for path, expected in config["compiledClasses"].items()):
            raise ValueError("compiled helper drift before control")
        actual = [config["java"], "-cp", config["classpath"], argv[3], config["dump"]]
        result = real_run(actual, cwd=ROOT, capture_output=True, text=True, timeout=120)
        records.append({"requestedCommand": argv, "executedCommand": actual,
                        "returnCode": result.returncode, "stdout": result.stdout, "stderr": result.stderr})
        hashes = parse_hashes(result.stdout, result.stderr, result.returncode, config["corpus"])
        if any(sha(Path(path)) != expected for path, expected in config["compiledClasses"].items()):
            raise ValueError("compiled helper drift during control")
        if hashes != config["canonical"]:
            raise ValueError("current helper changed from pinned historical replay")
        helpers.append(hashes)
        return result

    stdout, stderr = io.StringIO(), io.StringIO()
    status, failure = None, None
    try:
        if document["probe_receipt"].get("transformed_classes") != {}:
            raise ValueError("embedded class fallback must be disabled for this replay")
        spec = importlib.util.spec_from_file_location("h1_legacy_profile_verify", ROOT / "tools/testing/profile_verify.py")
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        subprocess.run = strict_helper
        with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
            status = module.main()
        if len(helpers) != 1:
            raise ValueError("legacy verifier did not obtain exactly one checked actual corpus observation")
        if status not in (0, 1) or stderr.getvalue():
            raise ValueError("unexpected legacy verifier status/stderr")
        lines = stdout.getvalue().splitlines()
        if status == 0 and lines != ["PROFILE VERIFY: PASS"]:
            raise ValueError("malformed positive legacy verifier output")
        if status == 1 and (not lines or not re.fullmatch(r"PROFILE VERIFY: FAIL \([1-9][0-9]*\)", lines[-1])
                            or not all(line.startswith("FAIL ") for line in lines[:-1])):
            raise ValueError("malformed negative legacy verifier output")
    except Exception as error:
        status, failure = 2, repr(error)
    finally:
        subprocess.run = real_run
        receipt = {"schema": "H1_LEGACY_REVELATION_CONTROL_V1", "returnCode": status,
                   "failure": failure, "stdout": stdout.getvalue(), "stderr": stderr.getvalue(),
                   "profileSha256": sha(work / "profile.json"), "helperCommands": records,
                   "actualObservedClasses": len(config["corpus"]) if len(helpers) == 1 else None}
        (work / "receipt.json").write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
    print(stdout.getvalue(), end="")
    if stderr.getvalue():
        print(stderr.getvalue(), end="", file=sys.stderr)
    if failure:
        print(failure, file=sys.stderr)
    return status


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dump", type=Path, required=True)
    parser.add_argument("--runtime", type=Path, required=True)
    parser.add_argument("--java-home", type=Path, required=True)
    parser.add_argument("--asm", type=Path, required=True)
    parser.add_argument("--profile", type=Path, default=ROOT / "tools/live-capture/revelation-profile.json")
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    out, dump, runtime = args.out.resolve(), args.dump.resolve(), args.runtime.resolve()
    if ROOT not in out.parents or out.exists():
        parser.error("output must be a nonexistent directory in this isolated worktree")
    if not dump.is_dir() or not runtime.is_dir():
        parser.error("explicit historical corpus/runtime inputs must exist")
    out.mkdir(parents=True)
    records, baseline_sources, compiled_artifacts, failure = [], {}, {}, None
    java = args.java_home.resolve() / "bin/java.exe"
    javac = args.java_home.resolve() / "bin/javac.exe"
    sources = [CORE + "LiveHookSupport.java", CORE + "LiveWriterPlan.java", HELPER]
    current_sources = sources + [CORE + "CanonicalClassIdentityV2.java"]
    profile = json.loads(args.profile.read_text(encoding="utf-8"))
    inventory = dump_inventory(dump)
    inputs = [ROOT / p for p in current_sources] + [args.profile.resolve(), java, javac, args.asm.resolve(),
            Path(__file__).resolve(), ROOT / "tools/testing/test_profile_verify.py", ROOT / "tools/testing/profile_verify.py"]
    inputs += [runtime / p for p in profile["runtime_pins"]["artifacts"]]
    inputs += [runtime / p for p in profile["runtime_pins"]["mods"]]
    before = {str(p): sha(p) for p in inputs}
    canonical = {}
    try:
        version = command([str(java), "-version"], records); require_success(version)
        if not re.search(r'(?:java|openjdk) version "1\.8\.', version.stdout + version.stderr):
            raise ValueError("explicit Java 8 runtime required")
        for relative in sources:
            result = command(["git", "--no-optional-locks", "show", BASELINE + ":" + relative], records)
            require_success(result)
            target = out / "baseline-src" / Path(relative).name
            target.parent.mkdir(parents=True, exist_ok=True)
            # Git output decoded as text: normalize Windows transport newlines to
            # LF; source semantics are unchanged and the compiled bytes are hashed.
            target.write_text(result.stdout, encoding="utf-8", newline="\n")
            baseline_sources[relative] = {"blob": BASELINE + ":" + relative, "sha256": sha(target)}
        classpaths = {}
        for variant in ("baseline", "current"):
            classes = out / variant / "classes"; classes.mkdir(parents=True)
            selected = ([out / "baseline-src" / Path(p).name for p in sources] if variant == "baseline"
                        else [ROOT / p for p in current_sources])
            build = command([str(javac), "-proc:none", "-source", "8", "-target", "8", "-encoding", "UTF-8",
                             "-cp", str(args.asm.resolve()), "-d", str(classes), *map(str, selected)], records)
            require_success(build)
            compiled_artifacts[variant] = {str(p): sha(p) for p in sorted(classes.rglob("*.class"))}
            cp = os.pathsep.join((str(classes), str(args.asm.resolve())))
            classpaths[variant] = cp
            result = command([str(java), "-cp", cp, "com.rustcraft.revdiag.RevCanonicalHash", str(dump)], records)
            canonical[variant] = parse_hashes(result.stdout, result.stderr, result.returncode, inventory)
        if canonical["baseline"] != canonical["current"]:
            raise ValueError("H1 changed historical V1 identity behavior")
        expected_classes = profile["expected_transformed_classes"]
        if any(canonical["current"].get(name) != value for name, value in expected_classes.items()):
            raise ValueError("preserved actual corpus does not match committed historical canonical expectations")
        profile["transformed_dump_dir"] = str(dump)
        profile["runtime_pins"]["server_root"] = str(runtime)
        profile["probe_receipt"]["transformed_classes"] = {}
        profile_path = out / "historical-profile-no-class-fallback.json"
        profile_path.write_text(json.dumps(profile, indent=2) + "\n", encoding="utf-8")
        config = {"out": str(out), "dump": str(dump), "java": str(java),
                  "classpath": classpaths["current"], "corpus": inventory, "canonical": canonical["current"],
                  "compiledClasses": compiled_artifacts["current"]}
        config_path = out / "worker-config.json"
        config_path.write_text(json.dumps(config, indent=2) + "\n", encoding="utf-8")
        temp = out / "temp"; temp.mkdir()
        env = dict(os.environ)
        env.update({"PYTHONDONTWRITEBYTECODE": "1", "RUSTCRAFT_H1_LEGACY_WORKER": "1",
                    "RUSTCRAFT_H1_LEGACY_CONFIG": str(config_path),
                    "RUSTCRAFT_PROFILE_TEST_PROFILE": str(profile_path),
                    "RUSTCRAFT_PROFILE_TEST_VERIFIER": str(Path(__file__).resolve()),
                    "RUSTCRAFT_PROFILE_TEST_RUNTIME": str(runtime), "RUSTCRAFT_PROFILE_TEST_TEMP_ROOT": str(temp)})
        controls = command([sys.executable, "-B", "-m", "unittest", "tools.testing.test_profile_verify", "-v"], records, env=env)
        require_success(controls)
        if not re.search(r"Ran 10 tests in [0-9.]+s\s+OK\s*$", controls.stderr):
            raise ValueError("legacy unittest output does not prove all ten controls ran")
        receipts = [json.loads(p.read_text(encoding="utf-8")) for p in (out / "controls").glob("*/receipt.json")]
        statuses = [r["returnCode"] for r in receipts]
        if len(receipts) != 9 or statuses.count(0) != 1 or statuses.count(1) != 8:
            raise ValueError("expected one positive and eight verifier rejection executions")
        if any(r["failure"] or r["actualObservedClasses"] != len(inventory) for r in receipts):
            raise ValueError("legacy control did not use complete checked class observations")
    except Exception as error:
        failure = repr(error)
    after = {str(p): sha(p) if p.is_file() else None for p in inputs}
    after_corpus = dump_inventory(dump)
    if before != after or inventory != after_corpus:
        failure = "source/tool/runtime/historical corpus drift during regression"
    if any(not Path(path).is_file() or sha(Path(path)) != value
           for classes in compiled_artifacts.values() for path, value in classes.items()):
        failure = "compiled artifact drift during regression"
    receipt = {"schema": "H1_LEGACY_REVELATION_REGRESSION_V1", "status": "FAIL" if failure else "PASS",
               "failure": failure, "baseline": BASELINE, "baselineSources": baseline_sources,
               "inputHashesBefore": before, "inputHashesAfter": after, "corpus": inventory,
               "actualClasses": len(inventory), "historicalCanonical": canonical,
               "compiledArtifacts": compiled_artifacts,
               "commands": records, "controls": {"unittestCount": 10, "verifierExecutions": 9},
               "scope": "Historical preserved Revelation V1 corpus only. No fresh server, live shadow, or V2 qualification.",
               "knownLegacyLimitations": ["CANONICAL_ID_V1 is incomplete; reproduced only for regression",
                    "Legacy verifier hardcoded runtime/helper paths are bypassed only by explicit test adapter",
                    "Transformer/coremod metadata still comes from historical embedded receipt",
                    "Control09 checks one real pinned mod hash; it does not perform actual jar substitution",
                    "RAW mode and generic verifier limitations remain assigned to H2"],
               "productionAuthorityEnabled": False}
    (out / "receipt.json").write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"status": receipt["status"], "failure": failure, "classes": len(inventory), "receipt": str(out / "receipt.json")}))
    return 1 if failure else 0


if __name__ == "__main__":
    raise SystemExit(worker() if os.environ.get("RUSTCRAFT_H1_LEGACY_WORKER") == "1" else main())
