"""Reproduce H1 legacy Clean Forge regressions in this isolated checkout only.

These checks preserve the existing RAW/V1 compatibility baseline. They do NOT
issue V2 qualification or resume a live shadow campaign. All JVM work is offline.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import uuid

from hardening_guard import inspect


ROOT = Path(__file__).resolve().parents[2]


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def sources():
    paths = [Path(__file__), ROOT / ".gitattributes", ROOT / "tools/testing/hardening_guard.py"]
    for name in ("forge_runtime.py", "live_profile.py", "transformer_runtime.py",
                 "transformer_verify.py", "test_live_profile.py", "test_forge_runtime.py"):
        paths.append(ROOT / "tools/testing" / name)
    for base in ("tools/bridge/src", "tools/forge-capture/src", "tools/live-capture-tests/src"):
        paths.extend(sorted((ROOT / base).rglob("*.java")))
    return {path.relative_to(ROOT).as_posix(): digest(path) for path in sorted(set(paths))}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java-home", type=Path, required=True)
    parser.add_argument("--runtime-manifest", type=Path, required=True)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    output = (args.output or ROOT / "target/architecture-hardening" /
              ("h1-regressions-" + uuid.uuid4().hex[:10])).resolve()
    if not output.is_relative_to((ROOT / "target").resolve()):
        parser.error("all output must remain under isolated target/")
    output.mkdir(parents=True, exist_ok=False)
    before = sources()
    receipt = {"schema_version": 1, "kind": "H1_LEGACY_REGRESSIONS",
               "scope": "existing Clean Forge RAW/V1 baseline; not V2 requalification",
               "production_authority": False, "started_utc": datetime.now(timezone.utc).isoformat(),
               "source_hashes_before": before, "results": []}
    env = dict(os.environ)
    for key in ("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS", "CLASSPATH"):
        env.pop(key, None)
    java_home = args.java_home.resolve()
    java, javac = java_home / "bin/java.exe", java_home / "bin/javac.exe"
    dll = ROOT / "target/release/rustcraft_ffi.dll"
    manifest = args.runtime_manifest.resolve()
    receipt["inputs"] = [{"path": str(path), "sha256": digest(path)}
                         for path in (java, javac, dll, manifest)]

    def save():
        (output / "receipt.json").write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")

    def run(name, argv):
        argv = list(map(str, argv))
        start = time.monotonic()
        log = output / (name + ".log")
        with log.open("wb") as stream:
            code = subprocess.run(argv, cwd=ROOT, env=env, stdout=stream,
                                  stderr=subprocess.STDOUT).returncode
        row = {"name": name, "argv": argv, "exit_code": code,
               "seconds": time.monotonic() - start, "log": str(log.relative_to(ROOT)),
               "log_sha256": digest(log)}
        receipt["results"].append(row)
        save()
        print(json.dumps(row), flush=True)
        if code:
            raise RuntimeError(name + " failed; see " + str(log))
        return log

    try:
        receipt["isolation_before"] = inspect()
        if receipt["isolation_before"]["status"] != "PASS":
            raise RuntimeError("isolation/gate guard failed")
        run("tooling", [sys.executable, "-B", "-m", "unittest",
                        "tools.testing.test_live_profile", "tools.testing.test_forge_runtime", "-v"])
        shared = ["--java-home", java_home, "--dll", dll, "--manifest", manifest]
        run("live-profile", [sys.executable, "-B", "tools/testing/live_profile.py",
                             "--output", output / "baseline", *shared])
        run("live-transformer-and-capture", [sys.executable, "-B", "tools/testing/transformer_runtime.py",
                                            "--output", output / "diagnostic", *shared])
        run("post-hook-verify", [sys.executable, "-B", "tools/testing/transformer_verify.py",
                                 "--pre-hook", output / "baseline/qualification/transformed",
                                 "--post-hook", output / "diagnostic/live-transformer-jvm/transformed",
                                 "--java-home", java_home,
                                 "--output", output / "post-hook-verification.json"])
        capture = json.loads((output / "diagnostic/live-transformer-jvm/live-transformer-verification.json").read_text())
        required = ("t7_real_spacket_captured", "t8_native_replay", "t9_java_output_unchanged")
        if capture.get("all_groups") != "PASS" or not all(
                isinstance(capture.get(key), str) and
                re.fullmatch(r"PASS(?: \([^\r\n]*\))?", capture[key]) for key in required):
            raise RuntimeError("required real-packet capture groups absent or failing")
        receipt["capture_groups"] = {key: capture[key] for key in required}
        runtime = Path(json.loads(manifest.read_text(encoding="utf-8-sig"))["server_root"])
        cp = os.pathsep.join(map(str, [runtime / "forge-1.12.2-14.23.5.2860.jar",
            output / "diagnostic/live-transformer/srg-minecraft.jar",
            runtime / "libraries/org/ow2/asm/asm-debug-all/5.2/asm-debug-all-5.2.jar",
            runtime / "libraries/net/minecraft/launchwrapper/1.12/launchwrapper-1.12.jar"]))
        build = output / "foundation-classes"
        build.mkdir()
        java_sources = [ROOT / "tools/bridge/src/com/rustcraft/bridge/PacketEncodeResultV2.java"]
        java_sources += sorted((ROOT / "tools/bridge/src/com/rustcraft/bridge/capture").glob("*.java"))
        java_sources += sorted((ROOT / "tools/live-capture-tests/src/com/rustcraft/bridge/capture").glob("*.java"))
        run("foundation-compile", [javac, "-proc:none", "-encoding", "UTF-8", "-source", "8",
                                   "-target", "8", "-cp", cp, "-d", build, *java_sources])
        for suite in ("PrivateBuildTicketsTest", "LiveWriterGateTest", "LiveChunkBindingsTest",
                      "LiveWriterProtocolEndToEndTest", "LiveCaptureAdmissionTest"):
            log = run(suite, [java, "-cp", str(build) + os.pathsep + cp,
                              "com.rustcraft.bridge.capture." + suite])
            summaries = re.findall(r"^RESULT: ([0-9]+) pass, ([0-9]+) fail$",
                                   log.read_text(encoding="utf-8"), re.MULTILINE)
            if len(summaries) != 1 or int(summaries[0][0]) == 0 or int(summaries[0][1]) != 0:
                raise RuntimeError(suite + " missing valid nonempty passing result")
            receipt.setdefault("foundation_assertions", {})[suite] = int(summaries[0][0])
        receipt["status"] = "PASS"
    except Exception as error:
        receipt["status"] = "FAIL"
        receipt["error"] = type(error).__name__ + ": " + str(error)
    finally:
        receipt["source_hashes_after"] = sources()
        receipt["isolation_after"] = inspect()
        receipt["inputs_after"] = [{"path": str(path), "sha256": digest(path)}
                                   for path in (java, javac, dll, manifest)]
        receipt["finished_utc"] = datetime.now(timezone.utc).isoformat()
        if (before != receipt["source_hashes_after"] or
                receipt["inputs"] != receipt["inputs_after"] or
                receipt["isolation_after"]["status"] != "PASS"):
            receipt["status"] = "FAIL"
            receipt["source_or_isolation_drift"] = True
        save()
        print(json.dumps({"status": receipt["status"], "receipt": str(output / "receipt.json")}), flush=True)
    return int(receipt["status"] != "PASS")


if __name__ == "__main__":
    raise SystemExit(main())
