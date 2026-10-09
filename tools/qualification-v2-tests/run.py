"""Compile/run V2 controls with an explicitly selected Java 8 and ASM 5.2.

All outputs stay under the selected worktree output directory. Original game
classfiles can be read later by the independent cross-check; this runner never
starts a game JVM or changes a qualification profile.
"""
import argparse
import collections
import datetime
import hashlib
import json
import pathlib
import re
import subprocess
import sys
import uuid


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def validate_fixtures(directory):
    rows = []
    for line in (directory / "controls.tsv").read_text(encoding="utf-8").splitlines():
        columns = line.split("\t")
        if len(columns) != 4 or not all(columns):
            raise ValueError("malformed control manifest row")
        name, status, expected, reference = columns
        if not re.fullmatch(r"[a-z0-9-]+", name) or not re.fullmatch(r"[a-z0-9-]+", reference):
            raise ValueError("invalid control fixture name")
        rows.append((name, status, expected, reference))
    if len({r[0] for r in rows}) != len(rows):
        raise ValueError("duplicate control manifest entry")
    counts = dict(collections.Counter(r[1] for r in rows))
    if counts != {"CHANGED": 63, "REJECTED": 6, "STABLE": 4}:
        raise ValueError(f"unexpected control coverage: {counts}")
    rejected = {r[0] for r in rows if r[1] == "REJECTED"}
    names = {r[0] for r in rows} | {r[3] for r in rows}
    identities, expected_files = {}, {"controls.tsv"}
    for name in names:
        classfile = directory / (name + ".class")
        expected_files.add(classfile.name)
        if not classfile.is_file():
            raise ValueError(f"missing class fixture: {name}")
        if name in rejected:
            continue
        dump = directory / (name + ".canonical.json")
        receipt = directory / (name + ".receipt.json")
        expected_files.update((dump.name, receipt.name))
        canonical = json.loads(dump.read_text(encoding="utf-8"))
        identity = json.loads(receipt.read_text(encoding="utf-8"))
        if not isinstance(canonical, list) or len(canonical) != 19 or canonical[0] != "CANONICAL_ID_V2":
            raise ValueError(f"invalid canonical schema: {name}")
        if (not isinstance(identity, list) or len(identity) != 5
                or identity[0] != "CANONICAL_ID_V2" or identity[1] != canonical[3]
                or not all(isinstance(h, str) and re.fullmatch(r"[0-9a-f]{64}", h) for h in identity[2:])
                or identity[2] != digest(dump) or identity[4] != digest(classfile)):
            raise ValueError(f"invalid or mismatched identity receipt: {name}")
        identities[name] = identity
    actual_files = {p.name for p in directory.iterdir()}
    if actual_files != expected_files:
        raise ValueError(f"fixture output set mismatch: {sorted(actual_files ^ expected_files)}")
    for name, status, expected, reference in rows:
        if status == "REJECTED":
            if expected != "-":
                raise ValueError("rejection encoded as successful digest")
            continue
        if identities[name][2] != expected:
            raise ValueError(f"control hash mismatch: {name}")
        equal = identities[name][2] == identities[reference][2]
        if equal != (status == "STABLE"):
            raise ValueError(f"control relationship mismatch: {name}")
    return {"counts": counts, "classfiles": len(names),
            "artifactHashes": {name: digest(directory / name) for name in sorted(expected_files)}}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--java-home", type=pathlib.Path, required=True)
    parser.add_argument("--asm", type=pathlib.Path, required=True)
    parser.add_argument("--out", type=pathlib.Path)
    args = parser.parse_args()
    repo = pathlib.Path(__file__).resolve().parents[2]
    run_id = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:12]
    out = (args.out or repo / "target/qualification-v2/runs" / run_id).resolve()
    if repo not in out.parents:
        parser.error("outputs must be within this isolated worktree")
    if out.exists():
        parser.error("output already exists; choose a fresh output path to preserve evidence")
    java = args.java_home / "bin" / ("java.exe" if sys.platform == "win32" else "java")
    javac = args.java_home / "bin" / ("javac.exe" if sys.platform == "win32" else "javac")
    for path in (java, javac, args.asm):
        if not path.is_file():
            parser.error(f"missing input: {path}")
    sources = [repo / "tools/bridge/src/com/rustcraft/coremod" / name for name in (
        "CanonicalClassIdentityV2.java", "LiveHookSupport.java", "LiveWriterPlan.java",
        # AsmTreeCompat is the only place a raw ASM tree list is cast, so it
        # has to be in this lane or the compile proves something else.
        "AsmTreeCompat.java",
        # LiveHookSupport resolves the acquisition record and, in session-bound
        # mode, the evidence certificate. Naming them here is what keeps this
        # lane a real compile of the identity contract instead of a compile of
        # whichever files happened to be listed.
        "SessionBoundIdentityCertificate.java",
        "SessionBoundAdmissionPolicy.java")]
    sources.append(repo / "tools/bridge/src/com/rustcraft/qualification/SameProcessAcquisition.java")
    controls = [repo / "tools/qualification-v2-tests/src/com/rustcraft/coremod" / name for name in (
        "CanonicalClassIdentityV2Test.java", "SessionBoundMaskControls.java",
        "SessionBoundCertificateControls.java", "SessionCrossSessionControls.java")]
    sources.extend(controls)
    classes = out / "classes"
    classes.mkdir(parents=True, exist_ok=False)
    input_paths = [*sources, java, javac, args.asm.resolve(), pathlib.Path(__file__).resolve()]
    before = {str(p): digest(p) for p in input_paths}
    runtime_classpath = str(args.asm) + (";" if sys.platform == "win32" else ":") + str(classes)
    commands = [
        [str(java), "-version"],
        [str(javac), "-version"],
        [str(javac), "-proc:none", "-source", "8", "-target", "8", "-Xlint:all", "-encoding", "UTF-8",
         "-cp", str(args.asm), "-d", str(classes), *map(str, sources)],
        [str(java), "-cp", runtime_classpath,
         "com.rustcraft.coremod.CanonicalClassIdentityV2Test", str(out / "fixtures")],
        # The mask and certificate controls used to be run by hand, which meant
        # nothing kept them green between runs. They are part of the same
        # contract as the 92 identity controls, so they run in the same lane and
        # a regression in either fails this receipt.
        [str(java), "-cp", runtime_classpath, "com.rustcraft.coremod.SessionBoundMaskControls"],
        [str(java), "-cp", runtime_classpath, "com.rustcraft.coremod.SessionBoundCertificateControls"],
        [str(java), "-cp", runtime_classpath, "com.rustcraft.coremod.SessionCrossSessionControls"],
    ]
    results = []
    failure, fixtures = None, None
    try:
        for index, command in enumerate(commands):
            result = subprocess.run(command, cwd=repo, capture_output=True, text=True, timeout=120)
            results.append({"command": command, "returnCode": result.returncode,
                            "stdout": result.stdout, "stderr": result.stderr})
            if result.returncode:
                raise ValueError(f"command {index} exited {result.returncode}")
            if {str(p): digest(p) for p in input_paths} != before:
                raise ValueError("source, harness or toolchain input changed during validation")
            if index == 0 and not re.search(r'(?:java|openjdk) version "1\.8\.', result.stdout + result.stderr):
                raise ValueError("Java runtime must be Java 8")
            if index == 1 and not re.search(r'javac 1\.8\.', result.stdout + result.stderr):
                raise ValueError("Java compiler must be Java 8")
        expected_line = f"CANONICAL_ID_V2 controls passed: 92; fixtures={out / 'fixtures'}"
        if results[3]["stdout"].splitlines() != [expected_line] or results[3]["stderr"]:
            raise ValueError("test output/assertion schema mismatch")
        # The mask and certificate controls report a pass/fail tally. The count
        # is pinned so a control silently disappearing is a failure, exactly as
        # a control turning red is.
        for index, suite, expected in ((4, "mask", "RESULT: 19 pass, 0 fail"),
                                       (5, "certificate", "RESULT: 38 pass, 0 fail"),
                                       (6, "cross-session", "SessionCrossSessionControls PASS (16 assertions)")):
            lines = results[index]["stdout"].splitlines()
            if results[index]["stderr"] or lines[-1:] != [expected] or not lines[:-1]:
                raise ValueError(f"{suite} controls output/assertion schema mismatch: {lines[-1:]}")
        fixtures = validate_fixtures(out / "fixtures")
    except subprocess.TimeoutExpired as error:
        results.append({"command": error.cmd, "returnCode": None, "timedOut": True,
                        "stdout": (error.stdout or b"").decode("utf-8", "replace") if isinstance(error.stdout, bytes) else error.stdout or "",
                        "stderr": (error.stderr or b"").decode("utf-8", "replace") if isinstance(error.stderr, bytes) else error.stderr or ""})
        failure = str(error)
    except (OSError, ValueError) as error:
        failure = str(error)
    after = {str(p): digest(p) if p.is_file() else None for p in input_paths}
    if after != before:
        failure = "source, harness or toolchain input changed during validation"
    receipt = {"schema": "CANONICAL_ID_V2_TEST_RECEIPT_V1",
               "status": "PASS" if failure is None else "FAIL", "failure": failure,
               "runId": run_id, "output": str(out),
               "assertions": ({"canonical_v2": 92, "session_bound_mask": 19,
                               "session_bound_certificate": 38,
                               "session_cross_session": 16} if failure is None else None),
               "inputHashesBefore": before, "inputHashesAfter": after,
               "sourceHashes": {str(p.relative_to(repo)): before[str(p)] for p in sources},
               "toolchain": {"java": before[str(java)], "javac": before[str(javac)], "asm": before[str(args.asm.resolve())]},
               "commands": results, "fixtures": fixtures,
               "scope": "Structural identity/admission controls; not bytecode execution or live profile qualification"}
    (out / "java-controls-receipt.json").write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
    if results:
        print(results[-1]["stdout"], end="")
    if failure:
        print(failure, file=sys.stderr)
        if results:
            print(results[-1]["stderr"], end="", file=sys.stderr)
    print(f"Receipt: {out / 'java-controls-receipt.json'}")
    return 0 if receipt["status"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
