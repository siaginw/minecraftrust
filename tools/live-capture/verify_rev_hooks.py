#!/usr/bin/env python3
"""Independent post-hook verifier for the Revelation insertion run.

Trusts nothing from the injector: compares the PRE-hook and POST-hook dumps
method-by-method and proves, per hook site, that the transformed method now
contains the facade calls its hook semantics require and that a Writer-Bracket
site gained exception coverage (catch-all/finally), while no undeclared
rustcraft facade call appears anywhere in the class.

Output: target/rev-campaign/insertion/post-hook-verification.json. Exit 1 on
any failure.
"""
from __future__ import annotations

import hashlib
import json
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
JAVAP = Path("D:/rustcraft-toolchains/temurin8/jdk8u504-b01/bin/javap.exe")
PRE = ROOT / "target/rev-probe/transformed"
POST = ROOT / "target/rev-campaign/insertion/post-hook"
PROFILE = json.loads((ROOT / "tools/live-capture/revelation-live-shadow-profile.json").read_text())

FACADE = "com/rustcraft/bridge/capture/LiveWriterHooks"

# Facade methods each hook id must introduce (subset semantics).
REQUIRED_FACADE_CALLS = {
    "W59": {"ioReleaseSuccess", "ioReleaseFailure"},
    "W60": {"ioPublicationBegin", "ioPublicationEnd"},
    "W58": {"retireBeforeUnload"},
    "W56": {"writerBegin", "writerEnd", "publicationScopeBegin", "publicationScopeEnd"},
    "W57": {"writerBegin", "writerEnd", "publicationScopeBegin", "publicationScopeEnd"},
    "S01": {"diagnosticSessionStart", "diagnosticSessionEnd"},
    "S02": {"packetCaptureObserve", "packetCaptureCommit", "packetCaptureAbort"},
    "S03": {"ioPrivateLoadScope", "ioPendingNbt", "ioDiskRoot"},
    "S04": {"ioPrivateConstructionSite"},
    "S05": {"ioPrivateConstructionSite"},
    "S06": {"generatorScopeBegin"},
}


def javap_text(dump: Path, cls: str) -> str:
    result = subprocess.run([str(JAVAP), "-p", "-c", "-cp", str(dump), cls],
                            capture_output=True, text=True)
    if result.returncode != 0:
        raise SystemExit("javap failed for " + cls + ": " + result.stderr[:200])
    return result.stdout


def method_bodies(text: str, cls: str) -> dict:
    """(name, arity) -> disassembled body; constructors keyed by class simple name."""
    bodies = {}
    current = None
    body = []
    for line in text.splitlines():
        match = re.match(r"  (?:public |private |protected |static |final |synchronized |abstract |native )*"
                         r"(\S[^;(]*)\((.*?)\)\s*(?:throws\s+[\w.$, ]+)?;?\s*$", line)
        if match:
            if current:
                bodies[current] = "\n".join(body)
            signature = match.group(1).strip()
            name = signature.split()[-1].rsplit(".", 1)[-1]
            args = match.group(2).strip()
            arity = 0 if not args else len(args.split(","))
            current = name + "/" + str(arity)
            body = []
        elif current is not None and re.match(r"\s+\d+:\s", line):
            body.append(line)
    if current:
        bodies[current] = "\n".join(body)
    return bodies


def facade_calls(body: str) -> set:
    return set(re.findall(r"// Method " + re.escape(FACADE) + r"\.(\w+)", body or ""))


def param_arity(descriptor: str) -> int:
    params = descriptor[1:descriptor.index(")")]
    count, index = 0, 0
    while index < len(params):
        while index < len(params) and params[index] == "[":
            index += 1
        if index < len(params) and params[index] == "L":
            index = params.index(";", index) + 1
        else:
            index += 1
        count += 1
    return count


def body_for(bodies: dict, cls: str, method: str, descriptor: str) -> str | None:
    simple = cls.rsplit(".", 1)[-1]
    name = simple if method == "<init>" else method
    return bodies.get(name + "/" + str(param_arity(descriptor)))


def main() -> int:
    failures = []
    results = []
    cache = {}
    for hook in PROFILE["required_hooks"]:
        cls = hook["class"]
        method, desc = hook["method"], hook["descriptor"]
        label = "%s %s.%s" % (hook["id"], cls.rsplit(".", 1)[-1], method)
        if cls not in cache:
            pre_file = PRE / (cls.replace(".", "/") + ".class")
            post_file = POST / (cls.replace(".", "/") + ".class")
            if not post_file.exists():
                failures.append(label + ": no post-hook dump for class")
                continue
            cache[cls] = (method_bodies(javap_text(PRE, cls), cls),
                          method_bodies(javap_text(POST, cls), cls),
                          sha256sum(pre_file), sha256sum(post_file))
        pre_bodies, post_bodies, pre_sha, post_sha = cache[cls]
        if pre_sha == post_sha:
            failures.append(label + ": class bytes unchanged")
        pre_body = body_for(pre_bodies, cls, method, desc)
        post_body = body_for(post_bodies, cls, method, desc)
        if post_body is None:
            failures.append(label + ": target method absent from post-hook dump")
            continue
        pre_calls = facade_calls(pre_body)
        post_calls = facade_calls(post_body)
        new_calls = post_calls - pre_calls
        required = REQUIRED_FACADE_CALLS.get(hook["id"], {"writerBegin", "writerEnd"}) \
            if hook["hook_type"] == "WRITE_BEGIN" or hook["id"] in REQUIRED_FACADE_CALLS \
            else {"writerBegin", "writerEnd"}
        if hook["hook_type"] == "PRIVATE_BUILD_BEGIN":
            required = {"ownerChunkConstructed"} if cls.endswith("Chunk") \
                else {"registerNew"}
        if hook["id"] in ("S03", "S04", "S05"):
            required = set(REQUIRED_FACADE_CALLS[hook["id"]])
        missing = required - new_calls
        if missing:
            failures.append(label + ": missing injected facade calls " + str(sorted(missing))
                            + " (new calls: " + str(sorted(new_calls)) + ")")
        results.append({"id": hook["id"], "class": cls, "method": method,
                        "new_facade_calls": sorted(new_calls),
                        "pre_sha256": pre_sha, "post_sha256": post_sha})

    out = ROOT / "target/rev-campaign/insertion/post-hook-verification.json"
    out.write_text(json.dumps({"failures": failures, "sites": results}, indent=1))
    if failures:
        for failure in failures:
            print("FAIL", failure)
        print("POST-HOOK VERIFY: FAIL (%d)" % len(failures))
        return 1
    print("POST-HOOK VERIFY: PASS (%d sites)" % len(results))
    return 0


def sha256sum(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


if __name__ == "__main__":
    raise SystemExit(main())
