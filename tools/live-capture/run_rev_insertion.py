#!/usr/bin/env python3
"""Runs the offline HOOK-INSERTION validation against the Revelation runtime:
launches the exact pinned runtime with the profile-generated Revelation
campaign jar, registers the three qualified transformers after the FML chain
(diagnostic ON, explicitly), and dumps the post-hook final definitions.

Produces target/rev-campaign/insertion/post-hook/ (final hooked bytes).
Verification is done by tools/live-capture/verify_rev_hooks.py (independent).
"""
from __future__ import annotations

import json
import os
import subprocess
import time
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
RT = Path("D:/rustcraft-runtime-targets/revelation-3.4.0/server")
JAVA = Path("D:/rustcraft-toolchains/temurin8/jdk8u504-b01/bin/java.exe")
JAVAC = Path("D:/rustcraft-toolchains/temurin8/jdk8u504-b01/bin/javac.exe")
OUT = ROOT / "target/rev-campaign"
SRG = ROOT / "target/live-transformer-build/srg/minecraft_server.1.12.2.srg.jar"
REV_JAR = OUT / "rustcraft-rev-coremod.jar"


def compile_probe() -> Path:
    classes = OUT / "probe-classes"
    classes.mkdir(parents=True, exist_ok=True)
    sources = [
        ROOT / "tools/forge-capture/src/com/rustcraft/offline/agent/ObservationAgent.java",
        ROOT / "tools/forge-capture/src/com/rustcraft/offline/bootstrap/RevOfflineTweaker.java",
        ROOT / "tools/forge-capture/src/com/rustcraft/offline/oracle/RevQualifyRuntime.java",
    ]
    cp = os.pathsep.join([str(REV_JAR), str(RT / "forge-1.12.2-14.23.5.2846-universal.jar"),
                          str(RT / "minecraft_server.1.12.2.jar"),
                          str(RT / "libraries/org/ow2/asm/asm-all/5.2/asm-all-5.2.jar"),
                          str(RT / "libraries/net/minecraft/launchwrapper/1.12/launchwrapper-1.12.jar")])
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
    classes = compile_probe()
    observer = make_observer_jar(classes)
    insertion = OUT / "insertion"
    insertion.mkdir(parents=True, exist_ok=True)
    dump = insertion / "post-hook"
    if dump.exists():
        import shutil
        shutil.rmtree(dump)
    dump.mkdir(parents=True)
    result_path = insertion / "insertion-receipt.json"
    cp = os.pathsep.join([str(classes), str(REV_JAR)]
                         + [str(p) for p in sorted((RT / "libraries").rglob("*.jar"))]
                         + [str(RT / "forge-1.12.2-14.23.5.2846-universal.jar"),
                            str(RT / "minecraft_server.1.12.2.jar")])
    args = [str(JAVA), "-Xmx3G",
            "-javaagent:" + str(observer),
            "-Drustcraft.dumpDir=" + str(dump),
            "-Drustcraft.qualificationResult=" + str(result_path),
            "-Drustcraft.liveWriterDiagnostic=true",
            "-Drustcraft.srgJar=" + str(SRG),
            "-Drustcraft.liveShadowOut=" + str(insertion / "events.jsonl"),
            "-Drustcraft.liveShadowDll=" + str(ROOT / "target/release/rustcraft_ffi.dll"),
            "-cp", cp, "net.minecraft.launchwrapper.Launch", "--tweakClass",
            "com.rustcraft.offline.bootstrap.RevOfflineTweaker", "--gameDir", str(RT)]
    print("launching Revelation hook-insertion validation...")
    started = time.time()
    log = (insertion / "jvm.log").open("wb")
    process = subprocess.Popen(args, cwd=str(RT), stdout=log, stderr=subprocess.STDOUT)
    try:
        code = process.wait(timeout=900)
    except subprocess.TimeoutExpired:
        process.kill()
        code = -1
    log.close()
    print("insertion run exit=%s after %.1fs" % (code, time.time() - started))
    hooked = len(list(dump.rglob("*.class")))
    print("post-hook classes dumped:", hooked)
    if result_path.exists():
        data = json.loads(result_path.read_text(encoding="utf-8"))
        print("transformed_classes:", len(data.get("transformed_classes", {})))
        print("required_missing:", data.get("required_class_missing"))
        (insertion / "insertion-summary.json").write_text(json.dumps({
            "exit_code": code, "post_hook_classes": hooked,
            "receipt": data}, indent=1))
        return 0 if hooked > 0 else 1
    print("NO receipt; jvm.log tail:")
    print((insertion / "jvm.log").read_text(encoding="utf-8", errors="replace")[-3000:])
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
