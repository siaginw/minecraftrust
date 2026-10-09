"""Fresh offline Java8/Forge noise oracle and bounded fusion experiment; no server."""
from __future__ import annotations
import argparse
import hashlib
import json
import lzma
import os
import platform
from pathlib import Path
import statistics
import subprocess
import sys
import tarfile
import time
import tomllib
import zipfile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools/testing"))
import forge_runtime as forge


def sources():
    paths = set((ROOT / "tools/worldgen-fusion-experiment").glob("*.py"))
    paths.update((ROOT / "tools/worldgen-fusion-experiment").glob("*.toml"))
    paths.update((ROOT / "tools/worldgen-fusion-experiment").glob("*.lock"))
    paths.update((ROOT / "tools/worldgen-fusion-experiment").glob("*.json"))
    for base, suffix in (("tools/worldgen-fusion-experiment/src", "*.rs"),
                         ("tools/worldgen-fusion-experiment/java", "*.java"),
                         ("tools/worldgen-fusion-experiment/tests", "*.rs"),
                         ("versions/mc_1_12_x", "*.rs"),
                         ("crates/worldgen-noise", "*.rs"), ("crates/rustcraft-core", "*.rs"),
                         ("tools/forge-capture/src/com/rustcraft/offline", "*.java")):
        paths.update((ROOT / base).rglob(suffix))
    paths.update(ROOT / p for p in forge.LIVE_TRANSFORMER_SOURCES)
    paths.update(ROOT / p for p in ("Cargo.toml", "Cargo.lock", "crates/worldgen-noise/Cargo.toml",
        "crates/rustcraft-core/Cargo.toml", "versions/mc_1_12_x/Cargo.toml", "tools/testing/forge_runtime.py",
        "tools/forge-capture/runtime-pins.json", "tools/forge-capture/log4j2.xml"))
    doc = ROOT / "docs/research/worldgen-fusion.md"
    if doc.exists():
        paths.add(doc)
    return [forge.identity(p) for p in sorted(paths)]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--java-home", type=Path, default=Path("D:/rustcraft-toolchains/temurin8/jdk8u504-b01"))
    parser.add_argument("--runtime-manifest", type=Path, default=ROOT / "target/architecture-hardening/forge-runtime.json")
    parser.add_argument("--forks", type=int, default=1)
    parser.add_argument("--rounds", type=int, default=0)
    args = parser.parse_args()
    if not 1 <= args.forks <= 5 or not 0 <= args.rounds <= 20:
        parser.error("forks 1..5, rounds 0..20")
    output = args.output.resolve()
    if not output.is_relative_to(ROOT / "target"):
        parser.error("output must be a fresh path within isolated checkout target")
    output.mkdir(parents=True, exist_ok=False)
    receipt = {"schema": "H10_WORLDGEN_FUSION_RUN_V1", "status": "FAIL", "production_authority": False,
               "full_forge_chunk_generation": "BLOCKED_NOT_MEASURED", "commands": [], "forks": []}
    receipt["host"] = {"platform":platform.platform(), "cpu":os.environ.get("PROCESSOR_IDENTIFIER"), "logical_cpu_count":os.cpu_count(), "exclusive_host":False}
    env = dict(os.environ)
    excluded = set(forge.ENV_EXCLUDED) | {k for k in env if k.upper().startswith(("RUST", "CARGO"))}
    receipt["sanitized_environment_keys"] = [k for k in sorted(excluded) if env.pop(k, None) is not None]
    env["CARGO_TARGET_DIR"] = str(output / "native-build")
    env["JAVA_HOME"] = str(args.java_home)

    def command(label, argv, cwd=ROOT, deadline=240):
        start = time.monotonic()
        record = {"label": label, "argv": [str(v) for v in argv], "cwd": str(cwd), "deadline_seconds": deadline}
        receipt["commands"].append(record)
        try:
            p = subprocess.run(record["argv"], cwd=cwd, env=env, stdout=subprocess.PIPE,
                               stderr=subprocess.STDOUT, timeout=deadline)
            data, code = p.stdout, p.returncode
        except subprocess.TimeoutExpired as exc:
            data, code = exc.stdout or b"", "TIMEOUT"
        log = output / (label + ".log")
        log.write_bytes(data)
        record.update(returncode=code, wall_seconds=time.monotonic()-start, output_bytes=len(data), log=forge.identity(log))
        if code != 0:
            raise RuntimeError(label + " failed: " + str(code))
        return data

    try:
        before = sources()
        receipt["sources_before"] = before
        provenance = json.loads((ROOT / "tools/worldgen-fusion-experiment/PROVENANCE.json").read_text())
        cargo = Path("C:/Users/Admin/.cargo/bin/cargo.exe")
        java, javac = args.java_home / "bin/java.exe", args.java_home / "bin/javac.exe"
        receipt["tools"] = [forge.identity(p) for p in (cargo, java, javac, Path(sys.executable),
            args.java_home / "jre/bin/server/jvm.dll", args.java_home / "jre/lib/rt.jar", args.java_home / "lib/tools.jar")]
        command("rust-toolchain", [cargo, "-Vv"])
        for name in ("cargo", "rustc", "rustfmt", "cargo-clippy", "clippy-driver"):
            resolved = Path(command("resolve-"+name, [cargo.with_name("rustup.exe"), "which", name]).decode().strip())
            receipt["tools"].append(forge.identity(resolved))
            if name == "rustc":
                command("rustc-version", [resolved, "-Vv"])
        command("java-version", [java, "-version"])
        manifest = ROOT / "tools/worldgen-fusion-experiment/Cargo.toml"
        metadata = json.loads(command("cargo-metadata", [cargo, "metadata", "--manifest-path", manifest, "--locked", "--offline", "--format-version", "1"]))
        lock = tomllib.loads(manifest.with_name("Cargo.lock").read_text())
        registry = []
        for package in metadata["packages"]:
            if not package["source"]:
                continue
            path = Path(package["manifest_path"])
            archive_path = path.parents[3] / "cache" / path.parents[1].name / (package["name"]+"-"+package["version"]+".crate")
            checksum = next(v["checksum"] for v in lock["package"] if v["name"]==package["name"] and v["version"]==package["version"])
            if forge.sha256(archive_path) != checksum:
                raise RuntimeError("registry archive checksum drift")
            files = {}
            with tarfile.open(archive_path, "r:gz") as archive:
                for member in archive.getmembers():
                    if not member.isfile():
                        continue
                    name = member.name.split("/", 1)[1]
                    expected = hashlib.sha256(archive.extractfile(member).read()).hexdigest()
                    if forge.sha256(path.parent / name) != expected:
                        raise RuntimeError("extracted dependency source drift: "+name)
                    files[name] = expected
            actual = {p.relative_to(path.parent).as_posix() for p in path.parent.rglob("*") if p.is_file() and p.name not in (".cargo-ok", ".cargo-checksum.json")}
            if actual != set(files):
                raise RuntimeError("dependency source inventory drift")
            registry.append({"name":package["name"], "version":package["version"], "license":package["license"], "archive":forge.identity(archive_path), "source_files":files, "source_dir":str(path.parent)})
        receipt["registry_packages"] = registry
        command("rust-tests", [cargo, "test", "--manifest-path", manifest, "--locked", "--offline"])
        command("scoped-fmt", [cargo, "fmt", "--manifest-path", manifest, "--check"])
        command("scoped-clippy", [cargo, "clippy", "--manifest-path", manifest, "--locked", "--offline", "--no-deps", "--all-targets", "--", "-D", "warnings"])
        command("native-release", [cargo, "build", "--manifest-path", manifest, "--locked", "--offline", "--release"])
        dll = output / "native-build/release/worldgen_fusion_experiment.dll"
        receipt["dll"] = forge.identity(dll)
        pins = json.loads((ROOT / "tools/forge-capture/runtime-pins.json").read_text())
        server = Path(json.loads(args.runtime_manifest.read_text(encoding="utf-8-sig"))["server_root"])
        artifacts = forge.validate_artifacts(server, pins)
        receipt["runtime_artifacts"] = artifacts
        classpath = [Path(v["path"]) for v in artifacts]
        boot_sources = sorted((ROOT / "tools/forge-capture/src/com/rustcraft/offline").rglob("*.java"))
        boot_sources += [ROOT / p for p in forge.LIVE_TRANSFORMER_SOURCES]
        boot = output / "bootstrap-classes"
        boot.mkdir()
        command("bootstrap-javac", [javac, "-source", "8", "-target", "8", "-encoding", "UTF-8", "-cp", os.pathsep.join(map(str, classpath)), "-d", boot] + boot_sources)
        observer = output / "observer.jar"
        receipt["observer"] = forge.make_observer_jar(boot, observer)
        forge_jar = next(Path(v["path"]) for v in artifacts if v["path"].endswith("forge-1.12.2-14.23.5.2860.jar"))
        with zipfile.ZipFile(forge_jar) as archive:
            mapping = lzma.decompress(archive.read("deobfuscation_data-1.12.2.lzma"), format=lzma.FORMAT_ALONE)
        mapping_path, srg = output / "srg-mapping.txt", output / "compile-only-srg.jar"
        mapping_path.write_bytes(mapping)
        vanilla = next(v["path"] for v in artifacts if v["path"].endswith("minecraft_server.1.12.2.jar"))
        command("srg-compile-support", [java, "-cp", os.pathsep.join(map(str, [boot]+classpath)),
            "com.rustcraft.offline.oracle.SrgJarBuilder", vanilla, mapping_path, srg])
        classes = output / "probe-classes"
        classes.mkdir()
        command("probe-javac", [javac, "-source", "8", "-target", "8", "-encoding", "UTF-8", "-cp",
            os.pathsep.join(map(str, [srg]+classpath)), "-d", classes] + sorted((ROOT / "tools/worldgen-fusion-experiment/java").rglob("*.java")))
        receipt["compiled_classes"] = {"bootstrap": forge.class_hashes(boot), "probe": forge.class_hashes(classes)}
        receipt["compile_only_srg"] = forge.identity(srg)
        for index in range(args.forks):
            fork = output / ("fork-" + str(index))
            game, dump, oracle = fork / "game", fork / "transformed", fork / "oracle"
            for directory in (game / "config", dump, oracle):
                directory.mkdir(parents=True)
            (game / "config/forge.cfg").write_bytes(b"general {\n B:disableVersionCheck=true\n}\n")
            qualification = fork / "qualification.json"
            command("fork-" + str(index), [java, "-Xms128m", "-Xmx512m", "-Xcheck:jni", "-javaagent:"+str(observer),
                "-Dlog4j.configurationFile="+(ROOT/"tools/forge-capture/log4j2.xml").as_uri(),
                "-Dlog4j2.formatMsgNoLookups=true", "-Drustcraft.dumpDir="+str(dump),
                "-Drustcraft.qualificationResult="+str(qualification),
                "-Drustcraft.oracleMain=com.rustcraft.fusion.FusionProbe", "-Drustcraft.oracleOutput="+str(oracle),
                "-Drustcraft.nativeDll="+str(dll), "-Drustcraft.fusionRounds="+str(args.rounds),
                "-cp", os.pathsep.join(map(str,[classes,boot]+classpath)), "net.minecraft.launchwrapper.Launch",
                "--tweakClass", "com.rustcraft.offline.bootstrap.OfflineTweaker", "--gameDir", game], cwd=fork)
            q = json.loads(qualification.read_text())
            forge.validate_qualification(q,pins)
            for name, expected in provenance["actual_java_oracle"]["noise_definitions_sha256"].items():
                if q["transformed_classes"].get(name) != expected or q["transformed_class_loaders"].get(name) != "net.minecraft.launchwrapper.LaunchClassLoader":
                    raise RuntimeError("actual noise definition/loader drift: "+name)
            for name,digest in q["transformed_classes"].items():
                if forge.sha256(dump / (name.replace(".","/")+".class")) != digest:
                    raise RuntimeError("dump drift")
            result = json.loads((oracle/"result.json").read_text())
            if result["schema"] != "H10_SYNTHETIC_WORLDGEN_FUSION_V1" or result["production_authority"] is not False or result["status"] != "PASS" or result["correctness"]["case_count"] != 45 or len(result["benchmark"]) != args.rounds*3:
                raise RuntimeError("incomplete output")
            if result["noise_class_loader"] != "net.minecraft.launchwrapper.LaunchClassLoader":
                raise RuntimeError("noise was not from actual runtime loader")
            if result["correctness"]["raw_double_values_compared"] != 960000 or result["correctness"]["assertions"] != 179:
                raise RuntimeError("correctness controls incomplete")
            for sample in range(args.rounds):
                group = [r for r in result["benchmark"] if r["sample"]==sample]
                if len(group)!=3 or len({r["lane"] for r in group})!=3 or len({r["hash"] for r in group})!=1:
                    raise RuntimeError("benchmark lane outputs differ")
            if any((game/name).exists() for name in ("world","server.properties","eula.txt")):
                raise RuntimeError("unexpected server artifact")
            receipt["forks"].append({"qualification": forge.identity(qualification), "result":result,
                "result_identity":forge.identity(oracle/"result.json"), "precision_negative_control":json.loads((oracle/"PRECISION-NEGATIVE-CONTROL.json").read_text()), "actual_noise_definitions":
                {k:v for k,v in q["transformed_classes"].items() if k.startswith("net.minecraft.world.gen.NoiseGenerator")}})
        samples = [r for f in receipt["forks"] for r in f["result"]["benchmark"]]
        receipt["summary"] = {lane: {"sample_count":len(rows), "median_ns_per_bounded_operation":statistics.median(r["wall_ns"]/r["iterations"] for r in rows),
            "min_ns_per_bounded_operation":min(r["wall_ns"]/r["iterations"] for r in rows), "max_ns_per_bounded_operation":max(r["wall_ns"]/r["iterations"] for r in rows)}
            for lane in sorted({r["lane"] for r in samples}) if (rows := [r for r in samples if r["lane"]==lane])}
        receipt["sources_after"] = sources()
        if receipt["sources_after"] != before or forge.identity(dll) != receipt["dll"] or forge.validate_artifacts(server,pins) != artifacts:
            raise RuntimeError("input/source/tool drift")
        if [forge.identity(Path(v["path"])) for v in receipt["tools"]] != receipt["tools"]:
            raise RuntimeError("tool drift")
        for package in registry:
            if forge.identity(Path(package["archive"]["path"])) != package["archive"]:
                raise RuntimeError("dependency archive drift")
            for name, expected in package["source_files"].items():
                if forge.sha256(Path(package["source_dir"])/name)!=expected:
                    raise RuntimeError("dependency source drift after execution")
        receipt["status"] = "PASS"
    except Exception as exc:
        receipt["failure"] = type(exc).__name__ + ": " + str(exc)
        raise
    finally:
        forge.json_write(output / "receipt.json",receipt)
        print(json.dumps({"status":receipt["status"],"receipt":str(output / "receipt.json"),"failure":receipt.get("failure")}))


if __name__ == "__main__":
    main()
