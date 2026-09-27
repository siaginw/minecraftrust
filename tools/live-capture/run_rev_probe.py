#!/usr/bin/env python3
"""Runs the offline TRANSFORMATION qualification probe against the exact pinned
Revelation 3.4.0 server runtime.

OFFLINE + LIFECYCLE-FREE: launchwrapper + FML coremod/cascading-tweaker
injection run with the COMPLETE original mod inventory present (the launch
game directory IS the pinned server directory — nothing is copied, nothing is
excluded), but no mod lifecycle is initialized. Final transformed bytes of the
writer-matrix classes are determined by the transformer chain alone, so this
phase derives the transformed-writer profile without running or judging any
mod's lifecycle behavior. A mod that cannot survive a synthetic lifecycle is a
probe limitation for the later dedicated-server qualification phase, never a
profile exclusion.

Produces:
  target/rev-probe/qualification.json   transformation inventory receipt
  target/rev-probe/transformed/         final transformed class bytes
Side effect: FML log files appear in <server>/logs as on any legitimate server
boot; no pinned artifact (jars/libraries/mods) is written.
"""
from __future__ import annotations

import json
import os
import shutil
import subprocess
import time
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
RT = Path("D:/rustcraft-runtime-targets/revelation-3.4.0/server")
JAVA = Path("D:/rustcraft-toolchains/temurin8/jdk8u504-b01/bin/java.exe")
JAVAC = Path("D:/rustcraft-toolchains/temurin8/jdk8u504-b01/bin/javac.exe")
OUT = ROOT / "target/rev-probe"
# The writers and the session-support classes live in the campaign jar, built
# from these same sources against the Revelation pins. It is on the probe's
# classpath for the same reason RevOfflineTweaker excludes those packages from
# the launch loader: the writers must be loaded by the PARENT, or they are a
# different copy of the class the harness sees, and a session admission
# recorded in one copy is invisible to the other.
CAMPAIGN_JAR = ROOT / "target/architecture-hardening/rev-srg/rustcraft-rev-coremod.jar"


def compile_probe() -> Path:
    classes = OUT / "probe-classes"
    classes.mkdir(parents=True, exist_ok=True)
    sources = [
        ROOT / "tools/forge-capture/src/com/rustcraft/offline/agent/ObservationAgent.java",
        ROOT / "tools/forge-capture/src/com/rustcraft/offline/bootstrap/RevOfflineTweaker.java",
        ROOT / "tools/forge-capture/src/com/rustcraft/offline/oracle/RevQualifyRuntime.java",
    ]
    cp = os.pathsep.join([
        str(CAMPAIGN_JAR),
        str(RT / "forge-1.12.2-14.23.5.2846-universal.jar"),
        str(RT / "minecraft_server.1.12.2.jar"),
        str(RT / "libraries/org/ow2/asm/asm-all/5.2/asm-all-5.2.jar"),
        str(RT / "libraries/net/minecraft/launchwrapper/1.12/launchwrapper-1.12.jar"),
    ])
    result = subprocess.run([str(JAVAC), "-encoding", "UTF-8", "-source", "8", "-target",
                             "8", "-nowarn", "-cp", cp, "-d", str(classes)]
                            + [str(s) for s in sources], capture_output=True, text=True)
    if result.returncode != 0:
        print(result.stdout[-2000:], result.stderr[-2000:])
        raise SystemExit("probe compile failed")
    return classes


def make_observer_jar(classes: Path) -> Path:
    observer = OUT / "observer.jar"
    with zipfile.ZipFile(observer, "w") as archive:
        for file in classes.rglob("*.class"):
            archive.write(file, file.relative_to(classes).as_posix())
        archive.writestr("META-INF/MANIFEST.MF",
                         "Manifest-Version: 1.0\r\n"
                         "Premain-Class: com.rustcraft.offline.agent.ObservationAgent\r\n")
    return observer


def main() -> int:
    OUT.mkdir(parents=True, exist_ok=True)
    classes = compile_probe()
    observer = make_observer_jar(classes)
    dump = OUT / "transformed"
    if dump.exists():
        shutil.rmtree(dump)
    dump.mkdir()
    result_path = OUT / "qualification.json"
    cp = os.pathsep.join([str(classes), str(CAMPAIGN_JAR)]
                         + [str(p) for p in sorted((RT / "libraries").rglob("*.jar"))]
                         + [str(RT / "forge-1.12.2-14.23.5.2846-universal.jar"),
                            str(RT / "minecraft_server.1.12.2.jar")])
    args = [str(JAVA), "-Xmx3G",
            "-javaagent:" + str(observer),
            "-Drustcraft.dumpDir=" + str(dump),
            "-Drustcraft.qualificationResult=" + str(result_path),
            "-cp", cp, "net.minecraft.launchwrapper.Launch", "--tweakClass",
            "com.rustcraft.offline.bootstrap.RevOfflineTweaker", "--gameDir", str(RT)]
    print("launching Revelation transformation probe (exact pinned runtime)...")
    started = time.time()
    log = (OUT / "jvm.log").open("wb")
    process = subprocess.Popen(args, cwd=str(RT), stdout=log, stderr=subprocess.STDOUT)
    try:
        code = process.wait(timeout=1200)
    except subprocess.TimeoutExpired:
        process.kill()
        code = -1
    log.close()
    print("probe exit=%s after %.1fs" % (code, time.time() - started))
    if result_path.exists():
        data = json.loads(result_path.read_text(encoding="utf-8"))
        print("profile:", data.get("profile"))
        print("forge_major.minor:", data.get("forge_major"), data.get("forge_minor"),
              "mc:", data.get("forge_mccversion"))
        print("transformed_classes:", len(data.get("transformed_classes", {})))
        print("transformers:", len(data.get("transformers", [])))
        print("coremod_plugins:", len(data.get("registered_coremod_plugins", [])))
        print("required_missing:", data.get("required_class_missing"))
        return 0
    print("NO qualification receipt; tail of jvm.log:")
    print((OUT / "jvm.log").read_text(encoding="utf-8", errors="replace")[-3000:])
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
