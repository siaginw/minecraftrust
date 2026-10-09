"""Bounded observability collectors and actual offline Java8/Forge adapter; no server."""
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
    paths = set()
    for directory in ("tools/observability", "crates/native-observability"):
        for path in (ROOT / directory).rglob("*"):
            if path.is_file() and "target" not in path.parts and "__pycache__" not in path.parts:
                paths.add(path)
    paths.update((ROOT / "crates/metrics/src").rglob("*.rs"))
    paths.update((ROOT / "tools/forge-capture/src/com/rustcraft/offline").rglob("*.java"))
    paths.update(ROOT / p for p in forge.LIVE_TRANSFORMER_SOURCES)
    paths.update(ROOT / p for p in ("Cargo.toml", "Cargo.lock", "crates/metrics/Cargo.toml",
        "tools/testing/forge_runtime.py", "tools/forge-capture/runtime-pins.json", "tools/forge-capture/log4j2.xml"))
    doc = ROOT / "docs/architecture/observability.md"
    if doc.exists():
        paths.add(doc)
    return [forge.identity(p) for p in sorted(paths)]


def rows(data):
    return [json.loads(line) for line in data.decode("utf-8").splitlines() if line]


def interval_contains(interval, value):
    return isinstance(interval, list) and len(interval) == 2 and interval[0] <= value <= interval[1]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--java-home", type=Path, default=Path("D:/rustcraft-toolchains/temurin8/jdk8u504-b01"))
    parser.add_argument("--runtime-manifest", type=Path, default=ROOT / "target/architecture-hardening/forge-runtime.json")
    parser.add_argument("--forks", type=int, default=1)
    parser.add_argument("--samples", type=int, default=0)
    args = parser.parse_args()
    if not 1 <= args.forks <= 5 or not 0 <= args.samples <= 20:
        parser.error("forks 1..5, samples 0..20")
    output = args.output.resolve()
    if not output.is_relative_to(ROOT / "target"):
        parser.error("output must be a fresh path within isolated checkout target")
    output.mkdir(parents=True, exist_ok=False)
    receipt = {"schema": "H15_OBSERVABILITY_RUN_V1", "status": "FAIL", "production_authority": False,
               "live_runtime_coverage": "INCOMPLETE_OFFLINE_ADAPTERS_ONLY", "commands": [], "forks": []}
    receipt["host"] = {"platform":platform.platform(), "cpu":os.environ.get("PROCESSOR_IDENTIFIER"), "logical_cpu_count":os.cpu_count(), "exclusive_host":False}
    env = dict(os.environ)
    excluded = set(forge.ENV_EXCLUDED) | {k for k in env if k.upper().startswith(("RUST", "CARGO"))}
    receipt["sanitized_environment_keys"] = [k for k in sorted(excluded) if env.pop(k, None) is not None]
    env["CARGO_TARGET_DIR"] = str(output / "native-build")
    env["JAVA_HOME"] = str(args.java_home)

    def command(label, argv, cwd=ROOT, deadline=240, expected_success=True):
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
        if (code == 0) != expected_success or code == "TIMEOUT":
            raise RuntimeError(label + " unexpected return: " + str(code))
        return data

    try:
        receipt["runtime_manifest"] = forge.identity(args.runtime_manifest)
        before = sources()
        receipt["sources_before"] = before
        provenance = json.loads((ROOT / "tools/observability/PROVENANCE.json").read_text())
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
        manifest = ROOT / "crates/native-observability/Cargo.toml"
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
        command("native-release", [cargo, "build", "--manifest-path", manifest, "--locked", "--offline", "--release", "--examples"])
        native_probe = output / "native-build/release/examples/probe.exe"
        importer = output / "native-build/release/examples/import_stage_batch.exe"
        receipt["native_executables"] = [forge.identity(native_probe), forge.identity(importer)]
        native_rows = rows(command("native-probe", [native_probe] + (["--correctness-only"] if args.samples == 0 else [])))
        coverage = native_rows[0]
        if coverage["kind"] != "coverage" or coverage["recording_alloc_bytes"] != 0 or coverage["recording_alloc_events"] != 0 or coverage["trace_kept"] != 256 or coverage["trace_dropped"] != 5994 or coverage["native_allocator_live_bytes"] is not None:
            raise RuntimeError("native bounded recording contract")
        timings = native_rows[1:]
        if len(timings) != (0 if args.samples == 0 else 40) or any(row["kind"] != "timing" or row["iterations"] != 100000 for row in timings):
            raise RuntimeError("native timing output count")
        if timings and (len({r["state"] for r in timings}) != 1 or {(r["sample"],r["mode"]) for r in timings} != {(a,b) for a in range(10) for b in range(4)}):
            raise RuntimeError("native mode output semantics")
        receipt["native"] = {"coverage":coverage,"timings":timings}
        malformed = {"schema":b"wrong\n", "stage":b"RUSTCRAFT_STAGE_BATCH_V1\nS\t18\t1\n", "negative":b"RUSTCRAFT_STAGE_BATCH_V1\nS\t0\t-2\n", "fields":b"RUSTCRAFT_STAGE_BATCH_V1\nS\t0\n", "overflow":b"RUSTCRAFT_STAGE_BATCH_V1\nD\t0\t18446744073709551616\n", "capacity":b"x"*1048577, "event-capacity":b"RUSTCRAFT_STAGE_BATCH_V1\n"+b"S\t0\t1\n"*4115}
        malformed.update({"duplicate-loss":b"RUSTCRAFT_STAGE_BATCH_V1\nD\t0\t1\nD\t0\t1\n", "sample-after-loss":b"RUSTCRAFT_STAGE_BATCH_V1\nD\t0\t1\nS\t0\t1\n", "sample-capacity":b"RUSTCRAFT_STAGE_BATCH_V1\n"+b"S\t0\t1\n"*4097})
        receipt["import_negative_controls"] = []
        for name, data in malformed.items():
            path = output / ("invalid-"+name+".tsv")
            path.write_bytes(data)
            response = command("invalid-"+name, [importer,path], expected_success=False)
            if b'"stage":' in response:
                raise RuntimeError("invalid batch exposed successful partial rows")
            receipt["import_negative_controls"].append({"name":name,"input":forge.identity(path)})
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
            os.pathsep.join(map(str, [srg,boot]+classpath)), "-d", classes] + sorted((ROOT / "tools/observability/library").rglob("*.java")) + sorted((ROOT / "tools/observability/tests").rglob("*.java")))
        telemetry_agent = output / "telemetry-observer.jar"
        with zipfile.ZipFile(telemetry_agent,"x",zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\nPremain-Class: com.rustcraft.telemetry.agent.DefinitionObserver\n\n")
            for path in sorted((classes / "com/rustcraft/telemetry").rglob("*.class")):
                archive.write(path,path.relative_to(classes).as_posix())
        receipt["telemetry_observer"] = forge.identity(telemetry_agent)
        deterministic = output / "deterministic.json"
        command("deterministic-java", [java,"-javaagent:"+str(telemetry_agent),"-Dobservability.fixtureClasses="+str(classes),"-cp",os.pathsep.join(map(str,[classes,boot]+classpath)),"com.rustcraft.observability.ObservabilityChecks",deterministic])
        receipt["deterministic"] = json.loads(deterministic.read_text())
        if receipt["deterministic"]["assertions"] != 65 or receipt["deterministic"]["status"] != "PASS" or receipt["deterministic"]["rejected_definition_attempts"] != 4:
            raise RuntimeError("deterministic Java control count")
        jfr = output / "java8-probe.jfr"
        command("java8-jfr", [java,"-XX:StartFlightRecording=duration=1s,filename="+str(jfr)+",dumponexit=true","-version"])
        if not jfr.is_file() or jfr.stat().st_size < 100 or jfr.read_bytes()[:4] != b"FLR\0":
            raise RuntimeError("JFR runtime support probe incomplete")
        receipt["java8_jfr"] = {"recording":forge.identity(jfr),"scope":"startup-only local Java8 feature probe; not a server profile"}
        receipt["compiled_classes"] = {"bootstrap": forge.class_hashes(boot), "probe": forge.class_hashes(classes)}
        receipt["compile_only_srg"] = forge.identity(srg)
        for index in range(args.forks):
            fork = output / ("fork-" + str(index))
            game, dump, oracle = fork / "game", fork / "transformed", fork / "oracle"
            for directory in (game / "config", dump, oracle):
                directory.mkdir(parents=True)
            (game / "config/forge.cfg").write_bytes(b"general {\n B:disableVersionCheck=true\n}\n")
            qualification = fork / "qualification.json"
            command("fork-" + str(index), [java, "-Xms128m", "-Xmx512m", "-javaagent:"+str(observer), "-javaagent:"+str(telemetry_agent),
                "-Dlog4j.configurationFile="+(ROOT/"tools/forge-capture/log4j2.xml").as_uri(),
                "-Dlog4j2.formatMsgNoLookups=true", "-Drustcraft.dumpDir="+str(dump),
                "-Drustcraft.qualificationResult="+str(qualification),
                "-Drustcraft.oracleMain=com.rustcraft.observability.OfflineEntry", "-Drustcraft.oracleOutput="+str(oracle),
                "-Dobservability.fixtureClasses="+str(classes), "-Dobservability.samples="+str(args.samples),
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
            if result["schema"] != "H15_OFFLINE_OBSERVABILITY_V1" or result["production_authority"] is not False or result["status"] != "PASS" or result["deterministic"]["assertions"] != 65 or result["actual_callback_values_compared"] != 6800 or result["definition_observer_rejected_attempts"] != 4 or result["deterministic"]["rejected_definition_attempts"] != 4 or result["definition_observer_errors"] != 0 or result["definition_observer_dropped"] != 0 or len(result["overhead"]) != ((args.samples+3)*6 if args.samples else 0):
                raise RuntimeError("incomplete offline output")
            callback = result["actual_callback_snapshot"]["rows"]
            if len(callback) != 1 or callback[0]["completed_known"] != 16 or callback[0]["unknown_inclusive"] != 0 or callback[0]["raw_sha256"] != provenance["actual_java_oracle"]["noise_definitions_sha256"]["net.minecraft.world.gen.NoiseGeneratorOctaves"] or callback[0]["optimization_authority"] is not False or callback[0]["measurement_binding_current"] is not True:
                raise RuntimeError("actual callback definition/count binding")
            for sample in range(-3,args.samples) if args.samples else ():
                group = [r for r in result["overhead"] if r["sample"] == sample]
                if len(group) != 6 or {r["mode"] for r in group} != set(range(6)) or len({r["result_sum"] for r in group if r["mode"]<3}) != 1 or len({r["result_sum"] for r in group if r["mode"]>=3}) != 1:
                    raise RuntimeError("overhead original body outputs differ")
            imports = {}
            for label in ("actual-callback-stages", "synthetic-loss-stages", "synthetic-known-ticks"):
                batch = oracle / (label+".tsv")
                imported = rows(command("import-"+str(index)+"-"+label,[importer,batch]))
                if len(imported) != 18 or [r["stage"] for r in imported] != list(range(18)):
                    raise RuntimeError("stage import inventory")
                imports[label] = {"input":forge.identity(batch),"rows":imported}
            actual = imports["actual-callback-stages"]["rows"]
            if actual[16]["known"] != 16 or actual[16]["unknown"] != 0 or any(r["known"] != 0 for r in actual if r["stage"] != 16):
                raise RuntimeError("actual callback stage scope")
            lost = imports["synthetic-loss-stages"]["rows"][0]
            if lost["known"] != 4 or lost["unknown"] != 4 or any(lost[k] is not None for k in ("p50_ns","p95_ns","p99_ns")):
                raise RuntimeError("lost ticks fabricated complete quantiles")
            known = imports["synthetic-known-ticks"]["rows"][0]
            if known["known"] != 100 or known["unknown"] != 0 or not all(interval_contains(known[k],v) for k,v in (("p50_ns",50000000),("p95_ns",95000000),("p99_ns",99000000))):
                raise RuntimeError("native HDR differs from independent sorted Java ticks")
            if any((game/name).exists() for name in ("world","server.properties","eula.txt")):
                raise RuntimeError("unexpected server artifact")
            receipt["forks"].append({"qualification":forge.identity(qualification), "result":result,
                "result_identity":forge.identity(oracle/"result.json"), "stage_imports":imports})
        samples = [r for f in receipt["forks"] for r in f["result"]["overhead"] if r["sample"] >= 0]
        receipt["summary"] = {str(mode): {"sample_count":len(group),"median_ns_per_callback_operation":statistics.median(r["wall_ns"]/r["iterations"] for r in group),"min_ns":min(r["wall_ns"]/r["iterations"] for r in group),"max_ns":max(r["wall_ns"]/r["iterations"] for r in group),"thread_allocated_bytes":[r["thread_allocated_bytes"] for r in group]}
            for mode in range(6) if (group := [r for r in samples if r["mode"]==mode])}
        receipt["sources_after"] = sources()
        if receipt["sources_after"] != before or [forge.identity(Path(v["path"])) for v in receipt["native_executables"]] != receipt["native_executables"] or forge.validate_artifacts(server,pins) != artifacts or forge.identity(args.runtime_manifest) != receipt["runtime_manifest"]:
            raise RuntimeError("input/source/tool drift")
        if [forge.identity(Path(v["path"])) for v in receipt["tools"]] != receipt["tools"]:
            raise RuntimeError("tool drift")
        for package in registry:
            if forge.identity(Path(package["archive"]["path"])) != package["archive"]:
                raise RuntimeError("dependency archive drift")
            for name, expected in package["source_files"].items():
                if forge.sha256(Path(package["source_dir"])/name)!=expected:
                    raise RuntimeError("dependency source drift after execution")
        if receipt["compiled_classes"] != {"bootstrap":forge.class_hashes(boot),"probe":forge.class_hashes(classes)} or forge.identity(observer) != receipt["observer"] or forge.identity(telemetry_agent) != receipt["telemetry_observer"] or forge.identity(srg) != receipt["compile_only_srg"]:
            raise RuntimeError("compiled input drift")
        receipt["status"] = "PASS"
    except Exception as exc:
        receipt["failure"] = type(exc).__name__ + ": " + str(exc)
        raise
    finally:
        forge.json_write(output / "receipt.json",receipt)
        print(json.dumps({"status":receipt["status"],"receipt":str(output / "receipt.json"),"failure":receipt.get("failure")}))


if __name__ == "__main__":
    main()
