"""Qualified Forge 2860 server transformations without launching a server.

Only pinned official jars are admitted. Compiled output is cached; both qualification
and oracle always execute in fresh JVMs with fresh empty game directories. Runtime
class dumps and Minecraft-derived fixture output remain local, never source assets.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import uuid
import zipfile


PROFILE = "FORGE_2860_SERVER_TRANSFORMED_FML_INITIALIZED_OFFLINE_V1"
TARGET = "minecraft-1.12.2-forge-14.23.5.2860-clean-server"
ENV_EXCLUDED = ("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS", "CLASSPATH")


class Incomplete(Exception):
    def __init__(self, reason: str, detail: str):
        self.reason, self.detail = reason, detail
        super().__init__(detail)


def sha256(path: Path) -> str:
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def json_write(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")


def identity(path: Path) -> dict:
    path = path.resolve()
    if not path.is_file():
        raise Incomplete("MISSING_ARTIFACT", str(path))
    return {"path": str(path), "sha256": sha256(path), "bytes": path.stat().st_size}


def validate_artifacts(server: Path, pins: dict) -> list[dict]:
    if pins.get("schema_version") != 1 or pins.get("target") != TARGET:
        raise Incomplete("ARTIFACT_MISMATCH", "Wrong runtime pin schema or target")
    server = server.resolve()
    result = []
    for artifact in pins["artifacts"]:
        path = (server / artifact["path"]).resolve()
        if not path.is_relative_to(server):
            raise Incomplete("ARTIFACT_MISMATCH", "Artifact path escapes server root")
        actual = identity(path)
        if actual["sha256"] != artifact["sha256"]:
            raise Incomplete("ARTIFACT_MISMATCH", str(path))
        result.append(actual)
    # Record and verify the exact runtime SRG mapping and Forge binary patch set.
    with zipfile.ZipFile(result[0]["path"]) as jar:
        for name, digest in pins["forge_embedded_sha256"].items():
            if hashlib.sha256(jar.read(name)).hexdigest() != digest:
                raise Incomplete("ARTIFACT_MISMATCH", "Embedded Forge resource " + name)
    return result


def validate_qualification(data: dict, pins: dict) -> None:
    if data.get("profile") != PROFILE or data.get("production_authority") is not False:
        raise Incomplete("ARTIFACT_MISMATCH", "Wrong transformation qualification profile")
    if data.get("java_runtime_version") != "1.8.0_504-b01":
        raise Incomplete("ARTIFACT_MISMATCH", "JVM is not qualified Temurin 8u504-b01")
    for key in ("attach_capabilities_listener_count", "chunk_load_listener_count"):
        if data.get(key) != 0:
            raise Incomplete("ARTIFACT_MISMATCH", "Unqualified event listener inventory: " + key)
    if data.get("mod_lifecycle_executed") is not True or data.get("farmland_water_ticket_map_empty") is not True:
        raise Incomplete("ARTIFACT_MISMATCH", "FML lifecycle or detached-world callback boundary unqualified")
    expected_unload = ["net.minecraftforge.fml.common.eventhandler.EventPriority:NORMAL",
        "net.minecraftforge.fml.common.eventhandler.ASMEventHandler:ASM: net.minecraftforge.common.ForgeInternalHandler@IDENTITY onChunkUnload(Lnet/minecraftforge/event/world/ChunkEvent$Unload;)V"]
    if data.get("chunk_unload_listeners") != expected_unload:
        raise Incomplete("ARTIFACT_MISMATCH", "Unqualified chunk unload callback")
    if data.get("registry_identity_sha256") != pins["registry_identity_sha256"]:
        raise Incomplete("ARTIFACT_MISMATCH", "Registry identity changed")
    if data.get("transformers") != pins["qualified_transformers"] or data.get("loaded_mods") != pins["loaded_mods"]:
        raise Incomplete("ARTIFACT_MISMATCH", "Forge transformer or mod inventory changed")
    if data.get("ordered_loaded_mods") != pins["ordered_loaded_mods"]:
        raise Incomplete("ARTIFACT_MISMATCH", "Active mod order changed")
    if [item.get("class") for item in data.get("registered_coremod_plugins", [])] != pins["coremod_plugin_classes"]:
        raise Incomplete("ARTIFACT_MISMATCH", "Registered coremod plugins changed")
    if data.get("observer") != "PASSIVE_JVM_CLASS_DEFINITION_OBSERVER_NO_RETRANSFORMATION":
        raise Incomplete("ARTIFACT_MISMATCH", "Final-definition observation missing")
    for name, digest in pins["required_transformed_sha256"].items():
        if data.get("transformed_classes", {}).get(name) != digest:
            raise Incomplete("ARTIFACT_MISMATCH", "Transformed class changed: " + name)


LIVE_TRANSFORMER_EXPECTED = [
    "com.rustcraft.coremod.LiveChunkOwnershipTransformer",
    "com.rustcraft.coremod.LiveChunkPublicationTransformer",
    "com.rustcraft.coremod.SPacketChunkDataTransformer",
]


def validate_live_transformer_qualification(data: dict, pins: dict) -> None:
    """Diagnostic (post-hook) JVM validation: identical to the baseline except the
    transformer inventory carries the three live-writer transformers and the
    required-transformed-hash pins are replaced by post-hook expectations (the
    inserted hooks change the guarded classes by construction)."""
    if data.get("profile") != PROFILE or data.get("production_authority") is not False:
        raise Incomplete("ARTIFACT_MISMATCH", "Wrong transformation qualification profile")
    if data.get("java_runtime_version") != "1.8.0_504-b01":
        raise Incomplete("ARTIFACT_MISMATCH", "JVM is not qualified Temurin 8u504-b01")
    for key in ("attach_capabilities_listener_count", "chunk_load_listener_count"):
        if data.get(key) != 0:
            raise Incomplete("ARTIFACT_MISMATCH", "Unqualified event listener inventory: " + key)
    if data.get("mod_lifecycle_executed") is not True or data.get("farmland_water_ticket_map_empty") is not True:
        raise Incomplete("ARTIFACT_MISMATCH", "FML lifecycle or detached-world callback boundary unqualified")
    expected_unload = ["net.minecraftforge.fml.common.eventhandler.EventPriority:NORMAL",
        "net.minecraftforge.fml.common.eventhandler.ASMEventHandler:ASM: net.minecraftforge.common.ForgeInternalHandler@IDENTITY onChunkUnload(Lnet/minecraftforge/event/world/ChunkEvent$Unload;)V"]
    if data.get("chunk_unload_listeners") != expected_unload:
        raise Incomplete("ARTIFACT_MISMATCH", "Unqualified chunk unload callback")
    if data.get("registry_identity_sha256") != pins["registry_identity_sha256"]:
        raise Incomplete("ARTIFACT_MISMATCH", "Registry identity changed")
    # The live-writer transformers are appended immediately after the qualified base
    # chain and BEFORE FML's ModAPITransformer (which FML adds later, during mod
    # loading) — they must run last over the SRG-deobfuscated vanilla bytes.
    base = pins["qualified_transformers"]
    expected = base[:len(base) - 1] + LIVE_TRANSFORMER_EXPECTED + base[len(base) - 1:]
    if data.get("transformers") != expected:
        raise Incomplete("ARTIFACT_MISMATCH", "Live-writer transformer inventory changed")
    if data.get("loaded_mods") != pins["loaded_mods"]:
        raise Incomplete("ARTIFACT_MISMATCH", "Mod inventory changed")
    if data.get("ordered_loaded_mods") != pins["ordered_loaded_mods"]:
        raise Incomplete("ARTIFACT_MISMATCH", "Active mod order changed")
    if [item.get("class") for item in data.get("registered_coremod_plugins", [])] != pins["coremod_plugin_classes"]:
        raise Incomplete("ARTIFACT_MISMATCH", "Registered coremod plugins changed")
    if data.get("observer") != "PASSIVE_JVM_CLASS_DEFINITION_OBSERVER_NO_RETRANSFORMATION":
        raise Incomplete("ARTIFACT_MISMATCH", "Final-definition observation missing")


def class_hashes(classes: Path) -> dict[str, str]:
    return {path.relative_to(classes).as_posix(): sha256(path)
            for path in sorted(classes.rglob("*.class"))}


def cache_valid(directory: Path, expected_key: str) -> bool:
    try:
        manifest = json.loads((directory / "cache.json").read_text(encoding="utf-8"))
        hashes = class_hashes(directory / "classes")
        return (manifest["key"] == expected_key and bool(hashes)
                and hashes == manifest["classes"])
    except (OSError, ValueError, KeyError):
        return False


def run_command(argv: list[str], cwd: Path, log: Path, env: dict) -> dict:
    log.parent.mkdir(parents=True, exist_ok=True)
    with log.open("wb") as out:
        result = subprocess.run(argv, cwd=cwd, env=env, stdout=out,
                                stderr=subprocess.STDOUT, timeout=180)
    receipt = {"argv": argv, "cwd": str(cwd), "exit_code": result.returncode,
               "log": str(log), "log_sha256": sha256(log)}
    if result.returncode:
        raise RuntimeError("Command failed: " + str(log))
    return receipt


def make_observer_jar(classes: Path, destination: Path) -> dict:
    manifest = ("Manifest-Version: 1.0\nPremain-Class: com.rustcraft.offline.agent.ObservationAgent\n"
                "Can-Retransform-Classes: false\nCan-Redefine-Classes: false\n\n")
    with zipfile.ZipFile(destination, "x", compression=zipfile.ZIP_STORED) as jar:
        files = {"META-INF/MANIFEST.MF": manifest.encode()}
        for path in sorted((classes / "com/rustcraft/offline/agent").glob("*.class")):
            files[path.relative_to(classes).as_posix()] = path.read_bytes()
        if len(files) < 2:
            raise RuntimeError("Passive definition observer was not compiled")
        for name, data in files.items():
            entry = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            jar.writestr(entry, data)
    return identity(destination)


def compile_cached(root: Path, cache: Path, sources: list[Path], classpath: list[Path],
                   qualification: dict, javac: Path, env: dict, output: Path, name: str) -> tuple[Path, dict]:
    before = [identity(path) for path in sources]
    classpath_identity = [({"classes": class_hashes(p)} if p.is_dir() else identity(p)) for p in classpath]
    stable_qualification = dict(qualification)
    if "observer_jar" in stable_qualification:
        stable_qualification["observer_jar"] = {key: value for key, value in stable_qualification["observer_jar"].items() if key != "path"}
    key_input = {"sources": before, "classpath": classpath_identity,
                 "qualification": stable_qualification, "javac_options": ["-proc:none", "-encoding", "UTF-8", "-source", "8", "-target", "8"]}
    key = hashlib.sha256(json.dumps(key_input, sort_keys=True).encode()).hexdigest()
    directory = cache / name / key
    hit = cache_valid(directory, key)
    if not hit:
        if directory.exists():  # Preserve corrupt/stale output as evidence; never trust or erase it.
            directory = cache / name / (key + "-rebuild-" + uuid.uuid4().hex)
        classes = directory / "classes"
        classes.mkdir(parents=True)
        args = [str(javac), "-proc:none", "-encoding", "UTF-8", "-source", "8", "-target", "8",
                "-cp", os.pathsep.join(map(str, classpath)), "-d", str(classes)] + list(map(str, sources))
        command = run_command(args, root, output / (name + "-javac.log"), env)
        if before != [identity(path) for path in sources]:
            raise RuntimeError("Harness sources changed during compilation")
        json_write(directory / "cache.json", {"key": key, "classes": class_hashes(classes), "command": command})
    if not cache_valid(directory, key):
        raise RuntimeError("Compiled harness cache verification failed")
    return directory / "classes", {"key": key, "cache_hit": hit, "directory": str(directory),
                                   "source_identity": before, "classes": class_hashes(directory / "classes")}


LIVE_TRANSFORMER_SOURCES = [
    "tools/bridge/src/com/rustcraft/bridge/capture/CaptureContract.java",
    "tools/bridge/src/com/rustcraft/bridge/capture/CaptureSource.java",
    "tools/bridge/src/com/rustcraft/bridge/capture/OwnedPacketSnapshot.java",
    "tools/bridge/src/com/rustcraft/bridge/capture/SnapshotCapture.java",
    "tools/bridge/src/com/rustcraft/bridge/capture/SyntheticCaptureSource.java",
    "tools/bridge/src/com/rustcraft/bridge/capture/OwnedSnapshotBridge.java",
    "tools/bridge/src/com/rustcraft/bridge/capture/LiveWriterGate.java",
    "tools/bridge/src/com/rustcraft/bridge/capture/LiveWriterHooks.java",
    "tools/bridge/src/com/rustcraft/bridge/capture/LiveForgeCaptureSource.java",
    "tools/bridge/src/com/rustcraft/bridge/capture/LivePacketCapture.java",
    "tools/bridge/src/com/rustcraft/bridge/capture/CaptureDraft.java",
    "tools/bridge/src/com/rustcraft/bridge/capture/SealedLiveCapture.java",
    "tools/bridge/src/com/rustcraft/bridge/capture/LiveComparisonQueue.java",
    "tools/bridge/src/com/rustcraft/bridge/PacketEncodeResultV2.java",
    "tools/bridge/src/com/rustcraft/bridge/capture/PrivateBuildTickets.java",
    "tools/bridge/src/com/rustcraft/bridge/capture/LiveChunkBindings.java",
    "tools/bridge/src/com/rustcraft/coremod/LiveWriterPlan.java",
    "tools/bridge/src/com/rustcraft/coremod/LiveHookSupport.java",
    "tools/bridge/src/com/rustcraft/coremod/LiveChunkOwnershipTransformer.java",
    "tools/bridge/src/com/rustcraft/coremod/LiveChunkPublicationTransformer.java",
]

LIVE_ORACLE_SOURCES = [
    "tools/forge-capture/src/com/rustcraft/livetransformer/LiveTransformerVerification.java",
    "tools/bridge/src/com/rustcraft/bridge/capture/CaptureContract.java",
    "tools/bridge/src/com/rustcraft/bridge/capture/CaptureSource.java",
    "tools/bridge/src/com/rustcraft/bridge/capture/OwnedPacketSnapshot.java",
    "tools/bridge/src/com/rustcraft/bridge/capture/SnapshotCapture.java",
    "tools/bridge/src/com/rustcraft/bridge/capture/SyntheticCaptureSource.java",
    "tools/bridge/src/com/rustcraft/bridge/capture/OwnedSnapshotBridge.java",
    "tools/bridge/src/com/rustcraft/coremod/LiveWriterPlan.java",
    "tools/bridge/src/com/rustcraft/coremod/LiveHookSupport.java",
    "tools/bridge/src/com/rustcraft/coremod/LiveChunkOwnershipTransformer.java",
    "tools/bridge/src/com/rustcraft/coremod/LiveChunkPublicationTransformer.java",
    "tools/bridge/src/com/rustcraft/coremod/SPacketChunkDataTransformer.java",
    "tools/bridge/src/com/rustcraft/bridge/capture/LiveWriterGate.java",
    "tools/bridge/src/com/rustcraft/bridge/capture/PrivateBuildTickets.java",
    "tools/bridge/src/com/rustcraft/bridge/capture/LiveChunkBindings.java",
    "tools/bridge/src/com/rustcraft/bridge/capture/LiveWriterHooks.java",
    "tools/bridge/src/com/rustcraft/bridge/capture/LiveForgeCaptureSource.java",
    "tools/bridge/src/com/rustcraft/bridge/capture/LivePacketCapture.java",
    "tools/bridge/src/com/rustcraft/bridge/capture/CaptureDraft.java",
    "tools/bridge/src/com/rustcraft/bridge/capture/SealedLiveCapture.java",
    "tools/bridge/src/com/rustcraft/bridge/capture/LiveComparisonQueue.java",
    "tools/bridge/src/com/rustcraft/bridge/PacketEncodeResultV2.java",
    "tools/bridge/src/com/rustcraft/bridge/NativeChunkPacket.java",
    "tools/bridge/src/com/rustcraft/bridge/NativeChunkBridge.java",
    "tools/bridge/src/com/rustcraft/bridge/ChunkMutationTracker.java",
    "tools/bridge/src/com/rustcraft/bridge/M4Coherency.java",
    "tools/bridge/src/com/rustcraft/bridge/M4PacketCompare.java",
    "tools/bridge/src/com/rustcraft/bridge/M4NativeStatePayload.java",
    "tools/bridge/src/com/rustcraft/bridge/M4PacketParityHarness.java",
    "tools/bridge/src/com/rustcraft/bridge/M4ValidatorBoundary.java",
    "tools/bridge/src/com/rustcraft/bridge/M4LifecycleCases.java",
    "tools/bridge/src/com/rustcraft/bridge/M4DeferredTest.java",
    "tools/bridge/src/com/rustcraft/bridge/M4ExtractorParity.java",
    "tools/bridge/src/com/rustcraft/bridge/M4PacketPerfStudy.java",
    "tools/bridge/src/com/rustcraft/bridge/PacketEncodeResultV2.java",
    "tools/bridge/src/com/rustcraft/bridge/WorldgenShadow.java",
    "tools/bridge/src/com/rustcraft/bridge/OutboundFrameCtx.java",
    "tools/bridge/src/com/rustcraft/bridge/M5ResendDriver.java",
    "tools/bridge/src/com/rustcraft/coremod/ChunkMutationTransformer.java",
]





def execute(root: Path, output: Path, java_home: Path, dll: Path, manifest: Path,
            qualification_only: bool = False, live_transformers: bool = False) -> dict:
    output = output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    receipt = {"schema_version": 1, "target": TARGET, "status": "FAIL",
               "production_authority": False, "output": str(output)}
    try:
        if not manifest.is_file():
            raise Incomplete("MISSING_ARTIFACT", str(manifest))
        config = json.loads(manifest.read_text(encoding="utf-8-sig"))
        pins_path = root / "tools/forge-capture/runtime-pins.json"
        pins = json.loads(pins_path.read_text(encoding="utf-8"))
        artifacts = validate_artifacts(Path(config["server_root"]), pins)
        java, javac = java_home / "bin/java.exe", java_home / "bin/javac.exe"
        qualified_inputs = {
            "artifacts": artifacts, "pins": identity(pins_path), "dll": identity(dll),
            "java": identity(java), "javac": identity(javac),
            "rt_jar": identity(java_home / "jre/lib/rt.jar"), "tools_jar": identity(java_home / "lib/tools.jar"),
            "jvm_dll": identity(java_home / "jre/bin/server/jvm.dll"),
            "helper": identity(Path(__file__)),
            "logging": identity(root / "tools/forge-capture/log4j2.xml"),
        }
        receipt["qualified_inputs"] = qualified_inputs
        env = dict(os.environ)
        receipt["sanitized_environment_keys"] = [name for name in ENV_EXCLUDED if env.pop(name, None) is not None]
        env["JAVA_HOME"] = str(java_home)
        classpath = [Path(value["path"]) for value in artifacts]
        cache = root / "target/rustcraft-tests/forge-cache"
        boot_sources = sorted((root / "tools/forge-capture/src/com/rustcraft/offline").rglob("*.java"))
        # Live-writer transformer foundation: compiled into every run (inert unless
        # -Drustcraft.liveWriterDiagnostic=true) so the bootstrap harness can register
        # them and the verification oracle can link against the protocol facade.
        boot_sources += [root / source for source in LIVE_TRANSFORMER_SOURCES]
        # The verification oracle needs SRG symbols: live-compile only (excluded here).
        boot_classes, boot_receipt = compile_cached(root, cache, boot_sources, classpath,
                qualified_inputs, javac, env, output, "bootstrap")
        receipt["bootstrap_compile"] = boot_receipt
        observer_jar = output / "observer.jar"
        qualified_inputs["observer_jar"] = make_observer_jar(boot_classes, observer_jar)

        def launch(label: str, classes: list[Path], oracle_main: str | None = None,
                   live_transformers: bool = False, pre_hook_dump: Path | None = None) -> tuple[dict, Path]:
            run_dir = output / label
            run_dir.mkdir()
            game = run_dir / "game"
            game.mkdir()  # New empty path: no mods, EULA, server.properties or world.
            (game / "config").mkdir()
            (game / "config/forge.cfg").write_bytes(b"general {\n B:disableVersionCheck=true\n}\n")
            dump = run_dir / "transformed"
            dump.mkdir()
            result_path = run_dir / "qualification.json"
            args = [str(java), "-javaagent:" + str(observer_jar),
                    "-Dlog4j.configurationFile=" + (root / "tools/forge-capture/log4j2.xml").as_uri(),
                    "-Dlog4j2.formatMsgNoLookups=true", "-Drustcraft.dumpDir=" + str(dump),
                    "-Drustcraft.qualificationResult=" + str(result_path)]
            if oracle_main is not None:
                oracle_output = output / "oracle"
                oracle_output.mkdir(exist_ok=True)
                args += ["-Drustcraft.oracleMain=" + oracle_main,
                         "-Drustcraft.oracleOutput=" + str(oracle_output),
                         "-Drustcraft.nativeDll=" + str(dll.resolve())]
            if live_transformers:
                args += ["-Drustcraft.liveWriterDiagnostic=true",
                         "-Drustcraft.preHookDump=" + str(pre_hook_dump),
                         "-Drustcraft.srgJar=" + str(output / "live-transformer" / "srg-minecraft.jar"),
                         "-Drustcraft.verificationResult=" + str(run_dir / "live-transformer-verification.json")]
            args += ["-cp", os.pathsep.join(map(str, classes + classpath)),
                     "net.minecraft.launchwrapper.Launch", "--tweakClass",
                     "com.rustcraft.offline.bootstrap.OfflineTweaker", "--gameDir", str(game)]
            command = run_command(args, run_dir, run_dir / "jvm.log", env)
            data = json.loads(result_path.read_text(encoding="utf-8"))
            if live_transformers:
                validate_live_transformer_qualification(data, pins)
            else:
                validate_qualification(data, pins)
            for name, digest in data["transformed_classes"].items():
                if sha256(dump / (name.replace(".", "/") + ".class")) != digest:
                    raise RuntimeError("Transformed dump hash mismatch: " + name)
            if (game / "eula.txt").exists() or (game / "server.properties").exists() or (game / "world").exists():
                raise RuntimeError("Unexpected server artifact created in bounded offline run")
            return {"command": command, "manifest": str(result_path), "manifest_sha256": sha256(result_path),
                    "transformed_dir": str(dump), "registry_identity_sha256": data["registry_identity_sha256"],
                    "config_identity": [identity(path) for path in sorted((game / "config").rglob("*")) if path.is_file()],
                    "required_class_count": len(data["required_class_locations"]),
                    "transformed_class_count": len(data["transformed_classes"])}, dump

        prepare, dump = launch("qualification", [boot_classes])
        receipt["qualification"] = prepare
        if not qualification_only:
            oracle_sources = [path for path in sorted((root / "tools/forge-capture/src").rglob("*.java"))
                    if path.name != "LiveTransformerVerification.java"]  # live-transformer lane only
            oracle_sources += sorted((root / "tools/bridge/src/com/rustcraft/bridge/capture").glob("*.java"))
            oracle_sources += [root / "tools/bridge/src/com/rustcraft/bridge/PacketEncodeResultV2.java"]
            compile_identity = dict(qualified_inputs, transformed_classes=class_hashes(dump))
            oracle_classes, oracle_receipt = compile_cached(root, cache, oracle_sources,
                    [dump] + classpath, compile_identity, javac, env, output, "oracle")
            receipt["oracle_compile"] = oracle_receipt
            receipt["oracle_execution"], _ = launch("oracle-jvm", [oracle_classes],
                    "com.rustcraft.oracle.CleanForgeCaptureOracle")
            receipt["oracle_output"] = str(output / "oracle")
        if live_transformers:
            # Materialize the SRG study jar (offline compile support; the pinned
            # vanilla jar is notch-named and cannot compile the coremod sources).
            srg_jar = output / "live-transformer" / "srg-minecraft.jar"
            srg_jar.parent.mkdir(parents=True, exist_ok=True)
            import lzma as srg_lzma
            with zipfile.ZipFile(next(item["path"] for item in receipt["qualified_inputs"]["artifacts"]
                    if item["path"].endswith("forge-1.12.2-14.23.5.2860.jar"))) as forge_zip:
                mapping_text = srg_lzma.decompress(
                    forge_zip.read("deobfuscation_data-1.12.2.lzma"), format=srg_lzma.FORMAT_ALONE)
            mapping_path = srg_jar.parent / "srg-mapping.txt"
            mapping_path.write_bytes(mapping_text)
            srg_command = run_command([str(java), "-cp", os.pathsep.join(
                    [str(boot_classes)] + [str(item["path"]) for item in receipt["qualified_inputs"]["artifacts"]]),
                    "com.rustcraft.offline.oracle.SrgJarBuilder",
                    str(next(item["path"] for item in receipt["qualified_inputs"]["artifacts"]
                            if item["path"].endswith("minecraft_server.1.12.2.jar"))),
                    str(mapping_path), str(srg_jar)], output, output / "srg-jar.log", env)
            receipt["srg_jar"] = identity(srg_jar)
            live_sources = [root / source for source in LIVE_ORACLE_SOURCES]
            compile_identity = dict(qualified_inputs, transformed_classes=class_hashes(dump),
                    srg_jar_sha256=sha256(srg_jar))
            live_classes, live_receipt = compile_cached(root, cache, live_sources,
                    [srg_jar, dump] + classpath, compile_identity, javac, env, output, "live-transformer")
            receipt["live_compile"] = live_receipt
            diagnostic, diag_dump = launch("live-transformer-jvm", [boot_classes, live_classes],
                    "com.rustcraft.livetransformer.LiveTransformerVerification",
                    live_transformers=True, pre_hook_dump=dump)
            receipt["live_transformer"] = diagnostic
            receipt["post_hook_transformed_dir"] = str(diag_dump)
            receipt["post_hook_class_hashes"] = json.loads(
                    Path(diagnostic["manifest"]).read_text(encoding="utf-8"))["transformed_classes"]
        # Recheck exact bytes after all subprocesses, including DLL and source cache artifacts.
        if validate_artifacts(Path(config["server_root"]), pins) != artifacts:
            raise RuntimeError("Runtime artifacts changed during execution")
        for value in qualified_inputs.values():
            if isinstance(value, dict) and "path" in value and identity(Path(value["path"])) != value:
                raise RuntimeError("Qualified input changed during execution: " + value["path"])
        for compile_receipt in [receipt["bootstrap_compile"]] + ([receipt["oracle_compile"]] if not qualification_only else []):
            if [identity(Path(item["path"])) for item in compile_receipt["source_identity"]] != compile_receipt["source_identity"]:
                raise RuntimeError("Harness source changed during execution")
            if not cache_valid(Path(compile_receipt["directory"]), compile_receipt["key"]):
                raise RuntimeError("Compiled harness changed during execution")
        receipt["status"] = "PASS"
        receipt["scope"] = "TRANSFORMED_RUNTIME_QUALIFICATION_ONLY" if qualification_only else "OFFLINE_ORACLE_JVM_COMPLETED_REQUIRES_SEMANTIC_REPLAY"
    except Incomplete as error:
        receipt.update(status="INCOMPLETE", reason=error.reason, detail=error.detail)
    except (OSError, ValueError, KeyError, RuntimeError, subprocess.TimeoutExpired) as error:
        receipt.update(status="FAIL", reason=type(error).__name__, detail=str(error))
    json_write(output / "forge-runtime-result.json", receipt)
    return receipt


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--java-home", type=Path, required=True)
    parser.add_argument("--dll", type=Path, required=True)
    parser.add_argument("--manifest", type=Path)
    parser.add_argument("--qualification-only", action="store_true")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    result = execute(root, args.output, args.java_home, args.dll,
                     args.manifest or root / ".rustcraft-local/forge-runtime.json", args.qualification_only)
    print(json.dumps({key: result.get(key) for key in ("status", "reason", "detail", "output", "oracle_output")}))
    return {"PASS": 0, "INCOMPLETE": 2}.get(result["status"], 1)


if __name__ == "__main__":
    raise SystemExit(main())
