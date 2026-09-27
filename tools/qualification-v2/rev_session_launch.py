#!/usr/bin/env python3
"""Real Revelation launch under a generated session-bound plan.

OFFLINE + LIFECYCLE-FREE, exactly like run_rev_probe.py: the full 219-mod
inventory registers its coremods and cascading tweakers, the transformer chain
is what is being measured, and no mod lifecycle is initialized.

The difference is that the live writers are actually registered and a session
environment is bound, so this is the run that can answer the questions the probe
cannot: whether a static policy built from cross-launch evidence admits the
bytes THIS launch holds, whether a certificate is issued in-process, and what
runs after the writers.

Two launches of this script are the §8 shape: each binds its own process and
session identity, and each must admit under the same policy without either
importing anything from the other.

    python -B tools/qualification-v2/rev_session_launch.py \
        --campaign-jar <jar built from the generated plan> \
        --out <dir> --profile <runtime profile the policy names>
"""
from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import time
import uuid
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
RT = Path("D:/rustcraft-runtime-targets/revelation-3.4.0/server")
JAVA = Path("D:/rustcraft-toolchains/temurin8/jdk8u504-b01/bin/java.exe")
JAVAC = Path("D:/rustcraft-toolchains/temurin8/jdk8u504-b01/bin/javac.exe")

SOURCES = [
    ROOT / "tools/forge-capture/src/com/rustcraft/offline/agent/ObservationAgent.java",
    ROOT / "tools/forge-capture/src/com/rustcraft/offline/bootstrap/RevOfflineTweaker.java",
    ROOT / "tools/forge-capture/src/com/rustcraft/offline/oracle/RevQualifyRuntime.java",
]


def compile_harness(out: Path, campaign: Path) -> Path:
    classes = out / "harness-classes"
    classes.mkdir(parents=True, exist_ok=True)
    # The campaign jar carries the writers and the session-support classes, so
    # the harness compiles against the same copy the launch will load from the
    # parent classpath. Compiling against a second build of the same classes
    # would type-check against something the launch never uses.
    cp = os.pathsep.join([
        str(campaign),
        str(RT / "forge-1.12.2-14.23.5.2846-universal.jar"),
        str(RT / "minecraft_server.1.12.2.jar"),
        str(RT / "libraries/org/ow2/asm/asm-all/5.2/asm-all-5.2.jar"),
        str(RT / "libraries/net/minecraft/launchwrapper/1.12/launchwrapper-1.12.jar"),
    ])
    result = subprocess.run([str(JAVAC), "-encoding", "UTF-8", "-source", "8", "-target",
                             "8", "-nowarn", "-cp", cp, "-d", str(classes)]
                            + [str(s) for s in SOURCES], capture_output=True, text=True)
    if result.returncode != 0:
        print(result.stdout[-3000:], result.stderr[-3000:])
        raise SystemExit("harness compile failed")
    jar = out / "harness.jar"
    with zipfile.ZipFile(jar, "w") as archive:
        for file in classes.rglob("*.class"):
            archive.write(file, file.relative_to(classes).as_posix())
        archive.writestr("META-INF/MANIFEST.MF",
                         "Manifest-Version: 1.0\r\n"
                         "Premain-Class: com.rustcraft.offline.agent.ObservationAgent\r\n")
    return jar


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--campaign-jar", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--profile", required=True,
                        help="the runtime profile the static policy names")
    parser.add_argument("--srg-jar", type=Path, required=True,
                        help="the SRG-named Minecraft study jar this runtime's own "
                             "vanilla jar and forge mapping produce; the writers need it "
                             "to compute stack-map frames, and it must be the one built "
                             "from THIS runtime")
    parser.add_argument("--timeout", type=int, default=1200)
    args = parser.parse_args()

    # The launch runs with cwd set to the pinned server directory, so every
    # classpath entry handed to it must already be absolute. A relative path
    # silently resolves against the runtime instead of this repository, and the
    # JVM then reports the missing class as a ClassNotFoundException from a
    # loader that was never even given the jar.
    out = args.out.resolve()
    campaign = args.campaign_jar.resolve()
    srg = args.srg_jar.resolve()
    if out.exists():
        shutil.rmtree(out)
    out.mkdir(parents=True)
    harness = compile_harness(out, campaign)

    # One process identity and one transformation-session identity per launch,
    # fresh every time. The point of the two-launch comparison is that neither is
    # knowable in advance, so neither may be carried between runs.
    process_id = str(uuid.uuid4())
    session_id = str(uuid.uuid4())

    dump = out / "transformed"
    dump.mkdir()
    result_path = out / "qualification.json"
    chain_path = out / "transformation-chain.json"
    # The engine reparses class identities itself, so the pre-writer and
    # post-writer buffers have to exist as files it can read.
    observation_dir = out / "observation"

    cp = os.pathsep.join([str(out), str(harness), str(campaign)]
                         + [str(p) for p in sorted((RT / "libraries").rglob("*.jar"))]
                         + [str(RT / "forge-1.12.2-14.23.5.2846-universal.jar"),
                            str(RT / "minecraft_server.1.12.2.jar")])
    launch = [
        str(JAVA), "-Xmx3G",
        "-javaagent:" + str(harness.resolve()),
        "-Drustcraft.dumpDir=" + str(dump),
        "-Drustcraft.definedDump=" + str(dump),
        "-Drustcraft.qualificationResult=" + str(result_path),
        "-Drustcraft.transformationChain=" + str(chain_path),
        "-Drustcraft.observationDir=" + str(observation_dir),
        # The three properties that turn a diagnostic boot into a session-bound
        # one. Without them the writers are registered but cannot authorize
        # anything, and every class load is INCOMPLETE by design.
        "-Drustcraft.liveWriterDiagnostic=true",
        "-Drustcraft.session.processId=" + process_id,
        "-Drustcraft.session.transformationSessionId=" + session_id,
        "-Drustcraft.profile=" + args.profile,
        # Frame computation resolves common supertypes against this jar. It must
        # be the runtime's own: a frame computed against another runtime's
        # hierarchy would be a frame this JVM never verified.
        "-Drustcraft.srgJar=" + str(srg),
        "-cp", cp, "net.minecraft.launchwrapper.Launch", "--tweakClass",
        "com.rustcraft.offline.bootstrap.RevOfflineTweaker", "--gameDir", str(RT),
    ]
    (out / "launch.json").write_text(json.dumps({
        "process_id": process_id, "transformation_session_id": session_id,
        "profile": args.profile, "argv": launch}, indent=2) + "\n", encoding="utf-8")

    print("launching Revelation with the live writers under a session-bound plan...")
    started = time.time()
    log = (out / "jvm.log").open("wb")
    process = subprocess.Popen(launch, cwd=str(RT), stdout=log, stderr=subprocess.STDOUT)
    try:
        code = process.wait(timeout=args.timeout)
    except subprocess.TimeoutExpired:
        process.kill()
        code = -1
    log.close()
    print("exit=%s after %.1fs" % (code, time.time() - started))

    if not result_path.exists():
        print("NO qualification receipt; tail of jvm.log:")
        print((out / "jvm.log").read_text(encoding="utf-8", errors="replace")[-4000:])
        return 1
    data = json.loads(result_path.read_text(encoding="utf-8"))
    print("transformed_classes:", len(data.get("transformed_classes", {})))
    print("transformers:", len(data.get("transformers", [])))
    print("session_environment_bound:", data.get("session_environment_bound"))
    print("required_missing:", data.get("required_class_missing"))
    return 0 if code == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
