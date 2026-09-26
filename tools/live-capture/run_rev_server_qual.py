#!/usr/bin/env python3
"""Runs the bounded REAL Revelation dedicated-server qualification phase.

Uses an isolated working COPY of the exact pinned runtime (inputs verified
against revelation-runtime-pins.json before boot; the pinned dir is never
written). The complete original mod inventory boots — no exclusions, no
substitutions. The passive RevServerInventoryAgent dumps the lifecycle truth
(mods, registry, listeners, coremods) via a shutdown hook after a clean stop.

No capture, no live shadow, no client: this phase ends after the server
reaches Done and is stopped cleanly.
"""
from __future__ import annotations

import hashlib
import json
import os
import shutil
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
RT = Path("D:/rustcraft-runtime-targets/revelation-3.4.0/server")
JAVA = Path("D:/rustcraft-toolchains/temurin8/jdk8u504-b01/bin/java.exe")
JAVAC = Path("D:/rustcraft-toolchains/temurin8/jdk8u504-b01/bin/javac.exe")
OUT = ROOT / "target/rev-server-qual"
WORK = OUT / "server"


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def prepare_work_copy() -> None:
    pins = json.loads((ROOT / "tools/live-capture/revelation-runtime-pins.json").read_text())
    if WORK.exists():
        shutil.rmtree(WORK)
    WORK.mkdir(parents=True)
    print("copying pinned runtime (418MB)...")
    for item in ("libraries", "mods", "config", "changelogs", "scripts"):
        src = RT / item
        if src.is_dir():
            shutil.copytree(src, WORK / item)
    for name in ("minecraft_server.1.12.2.jar", "forge-1.12.2-14.23.5.2846-universal.jar"):
        shutil.copyfile(RT / name, WORK / name)
    # verify every pinned artifact hash in the working copy
    checked = 0
    for name, meta in pins["artifacts"].items():
        assert sha256_file(WORK / name) == meta["sha256"], name
        checked += 1
    for key, meta in pins["mods"].items():
        assert sha256_file(WORK / key) == meta["sha256"], key
        checked += 1
    for key, meta in pins["libraries"].items():
        assert sha256_file(WORK / key) == meta["sha256"], key
        checked += 1
    print("pinned artifacts verified in working copy:", checked)
    (WORK / "eula.txt").write_text("eula=true\n")
    props = "\n".join([
        "online-mode=false", "max-tick-time=-1", "view-distance=8",
        "spawn-protection=0", "snooper-enabled=false", "enable-rcon=false",
        "level-seed=", "motd=rev-qualification",
    ]) + "\n"
    (WORK / "server.properties").write_text(props)


def compile_agent() -> Path:
    classes = OUT / "agent-classes"
    classes.mkdir(parents=True, exist_ok=True)
    cp = os.pathsep.join([
        str(WORK / "forge-1.12.2-14.23.5.2846-universal.jar"),
        str(WORK / "minecraft_server.1.12.2.jar"),
        str(WORK / "libraries/org/ow2/asm/asm-all/5.2/asm-all-5.2.jar"),
        str(WORK / "libraries/net/minecraft/launchwrapper/1.12/launchwrapper-1.12.jar"),
    ])
    result = subprocess.run([str(JAVAC), "-encoding", "UTF-8", "-source", "8", "-target",
                             "8", "-nowarn", "-cp", cp, "-d", str(classes),
                             str(ROOT / "tools/forge-capture/src/com/rustcraft/offline/"
                                       "oracle/RevServerInventoryAgent.java")],
                            capture_output=True, text=True)
    if result.returncode != 0:
        print(result.stdout[-2000:], result.stderr[-2000:])
        raise SystemExit("agent compile failed")
    agent = OUT / "rev-inventory-agent.jar"
    import zipfile
    with zipfile.ZipFile(agent, "w") as archive:
        for file in classes.rglob("*.class"):
            archive.write(file, file.relative_to(classes).as_posix())
        archive.writestr("META-INF/MANIFEST.MF",
                         "Manifest-Version: 1.0\r\n"
                         "Premain-Class: com.rustcraft.offline.oracle.RevServerInventoryAgent\r\n"
                         "Can-Retransform-Classes: false\r\n")
    return agent


def main() -> int:
    OUT.mkdir(parents=True, exist_ok=True)
    prepare_work_copy()
    agent = compile_agent()
    cp = os.pathsep.join([
        str(agent_jar_on_cp(agent)),
        str(WORK / "libraries/org/ow2/asm/asm-all/5.2/asm-all-5.2.jar"),
        str(WORK / "libraries/net/minecraft/launchwrapper/1.12/launchwrapper-1.12.jar"),
        str(WORK / "libraries/net/sf/jopt-simple/jopt-simple/5.0.3/jopt-simple-5.0.3.jar"),
        str(WORK / "libraries/java3d/vecmath/1.5.2/vecmath-1.5.2.jar"),
        str(WORK / "libraries/net/sf/trove4j/trove4j/3.0.3/trove4j-3.0.3.jar"),
        str(WORK / "libraries/com/typesafe/akka/akka-actor_2.11/2.3.3/akka-actor_2.11-2.3.3.jar"),
        str(WORK / "libraries/com/typesafe/config/1.2.1/config-1.2.1.jar"),
        str(WORK / "libraries/org/scala-lang/scala-library/2.11.1/scala-library.jar"),
        str(WORK / "libraries/org/scala-lang/scala-actors-migration_2.11/1.1.0/scala-actors-migration_2.11-1.1.0.jar"),
        str(WORK / "libraries/org/scala-lang/scala-compiler/2.11.1/scala-compiler.jar"),
        str(WORK / "libraries/org/scala-lang/plugins/scala-continuations-library_2.11/1.0.2/scala-continuations-library-1.0.2.jar"),
        str(WORK / "libraries/org/scala-lang/plugins/scala-continuations-plugin_2.11.1/1.0.2_mc/scala-continuations-plugin-1.0.2.jar"),
        str(WORK / "libraries/org/scala-lang/scala-parser-combinators_2.11/1.0.1/scala-parser-combinators_2.11-1.0.1.jar"),
        str(WORK / "libraries/org/scala-lang/scala-reflect/2.11.1/scala-reflect-2.11.1.jar"),
        str(WORK / "libraries/org/scala-lang/scala-swing_2.11/1.0.1/scala-swing_2.11-1.0.1.jar"),
        str(WORK / "libraries/org/scala-lang/scala-xml_2.11/1.0.2/scala-xml_2.11-1.0.2.jar"),
        str(WORK / "libraries/lzma/lzma/0.0.1/lzma-0.0.1.jar"),
        str(WORK / "libraries/net/java/dev/jna/jna/4.4.0/jna-4.4.0.jar"),
        str(WORK / "libraries/org/jline/jline/3.5.1/jline-3.5.1.jar"),
        str(WORK / "libraries/org/apache/maven/maven-artifact/3.5.3/maven-artifact-3.5.3.jar"),
        str(WORK / "minecraft_server.1.12.2.jar"),
        str(WORK / "forge-1.12.2-14.23.5.2846-universal.jar"),
    ])
    args = [str(JAVA), "-Xmx6G", "-Dfml.queryResult=confirm",
            "-javaagent:" + str(agent),
            "-Drustcraft.revInventory=" + str(OUT / "server-inventory.json"),
            "-cp", cp, "net.minecraftforge.fml.relauncher.ServerLaunchWrapper", "nogui"]
    print("booting the REAL Revelation dedicated server (first worldgen; may take many minutes)...")
    started = time.time()
    log = (OUT / "server.log").open("wb")
    process = subprocess.Popen(args, cwd=str(WORK), stdout=log,
                               stderr=subprocess.STDOUT, stdin=subprocess.PIPE)
    done = False
    deadline = time.time() + 2400
    while time.time() < deadline:
        time.sleep(5)
        try:
            text = (OUT / "server.log").read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        if "Done (" in text:
            done = True
            print("server reached Done after %.1fs" % (time.time() - started))
            break
        if process.poll() is not None:
            print("server EXITED early (see server.log)")
            break
    if done:
        time.sleep(20)  # settle post-Done init
        try:
            process.stdin.write(b"stop\n")
            process.stdin.flush()
        except (OSError, ValueError):
            pass
        try:
            process.wait(timeout=300)
        except subprocess.TimeoutExpired:
            process.kill()
        print("server stopped cleanly after %.1fs total" % (time.time() - started))
    else:
        try:
            process.stdin.write(b"stop\n")
            process.stdin.flush()
        except (OSError, ValueError):
            pass
        try:
            process.wait(timeout=120)
        except subprocess.TimeoutExpired:
            process.kill()
    log.close()
    inventory = OUT / "server-inventory.json"
    if inventory.exists():
        data = json.loads(inventory.read_text(encoding="utf-8"))
        print("active_mod_count:", data.get("active_mod_count"))
        print("registry_state_count:", data.get("registry_state_count"),
              "max_id:", data.get("registry_state_max_id"),
              "exceeds_u16:", data.get("registry_max_id_exceeds_u16"))
        print("chunk_load_listeners:", len(data.get("chunk_load_listeners", [])),
              "unload:", len(data.get("chunk_unload_listeners", [])),
              "attach:", len(data.get("chunk_attach_capabilities_listeners", [])))
        return 0 if done else 1
    print("inventory MISSING — inspect server.log")
    return 1


def agent_jar_on_cp(agent: Path) -> Path:
    # the agent jar itself is fine on the classpath (its premain is via -javaagent)
    return agent


if __name__ == "__main__":
    raise SystemExit(main())
