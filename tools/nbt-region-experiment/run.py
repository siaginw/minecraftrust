"""Fixture-only H9 complete pipeline comparison with exact preservation gates."""
from __future__ import annotations
import argparse
from collections import Counter
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import platform
import statistics
import struct
import subprocess
import sys
import time
import tarfile
import tomllib
import uuid
import zlib
from job import launch, LaunchFailure

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
sys.path.insert(0, str(ROOT / "tools/testing"))
from hardening_guard import inspect

def sha(path): return hashlib.sha256(Path(path).read_bytes()).hexdigest()
def unique(pairs):
    result = {}
    for key, value in pairs:
        if key in result: raise ValueError("duplicate JSON key")
        result[key] = value
    return result
def load(text): return json.loads(text, object_pairs_hook=unique, parse_constant=lambda _: (_ for _ in ()).throw(ValueError("nonfinite JSON")))
def require(condition, message):
    if not condition: raise RuntimeError(message)
def sources():
    paths = [p for p in HERE.rglob("*") if p.is_file() and "__pycache__" not in p.parts]
    paths += list((ROOT / "crates/nbt").rglob("*.rs")) + list((ROOT / "crates/core-types").rglob("*.rs"))
    paths += [ROOT / x for x in ("Cargo.toml", "Cargo.lock", "crates/nbt/Cargo.toml", "crates/core-types/Cargo.toml", "crates/compression/Cargo.toml", "crates/region-io/src/lib.rs", "tools/testing/hardening_guard.py")]
    doc = ROOT / "docs/research/nbt-region-pipeline.md"
    if doc.exists(): paths.append(doc)
    return {str(p.relative_to(ROOT)): sha(p) for p in sorted(set(paths))}

def region(raw):
    header = bytearray(8192)
    chunks = []
    offset = 2
    # Separate untouched second chunk tests all non-target header/payload bytes.
    for slot, content in ((0, raw), (1023, b"\x0a\0\0\0")):
        compressed = zlib.compress(content, level=6)
        record = struct.pack(">I", len(compressed) + 1) + b"\x02" + compressed
        sectors = (len(record) + 4095) // 4096
        header[slot*4:slot*4+4] = offset.to_bytes(3, "big") + bytes([sectors])
        header[4096+slot*4:4100+slot*4] = (1000+slot).to_bytes(4, "big")
        chunks.append(record.ljust(sectors*4096, b"\0"))
        offset += sectors
    return bytes(header) + b"".join(chunks)

def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--cargo", type=Path, required=True)
    p.add_argument("--java-home", type=Path, required=True)
    p.add_argument("--rustup-home", type=Path, required=True)
    p.add_argument("--output", type=Path)
    a = p.parse_args()
    output = (a.output or ROOT / "target/architecture-hardening" / ("h9-nbt-" + uuid.uuid4().hex[:12])).resolve()
    require(output.is_relative_to((ROOT / "target").resolve()), "output outside target")
    output.mkdir(parents=True, exist_ok=False)
    env = dict(os.environ)
    removed = [key for key in env if key.upper().startswith("RUST") or key.upper().startswith("CARGO_") and key.upper() != "CARGO_HOME" or key.upper() in ("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS", "CLASSPATH")]
    for key in removed: env.pop(key)
    env["RUSTUP_HOME"] = str(a.rustup_home.resolve())
    toolchain = "nightly-2026-09-24"
    cargo = a.cargo.resolve(strict=True)
    java = (a.java_home / "bin/java.exe").resolve(strict=True)
    javac = (a.java_home / "bin/javac.exe").resolve(strict=True)
    receipt = {"schema": "H9_NBT_REGION_EXPERIMENT_V1", "status": "RUNNING", "production_authority": False,
        "scope": "GENERATED_FIXTURE_ONLY_NO_LIVE_WORLD", "start_utc": datetime.now(timezone.utc).isoformat(),
        "source_before": sources(), "tool_hashes": {str(t): sha(t) for t in (cargo, java, javac, Path(sys.executable))},
        "toolchain": toolchain, "environment_removed_names": sorted(removed),
        "limits": {"parser_process_memory_mib": 512, "parser_timeout_seconds": 30, "raw_nbt_bytes": 2097152,
            "compressed_bytes":1048576,"region_bytes":16777216,"depth":64,"nodes":200000,"sequence":1000000},
        "compression": {"flate2":"1.1.10","zlib_rs":"0.6.8","level":6,"backend_proof":"resolved Cargo features plus pinned flate2 cfg source; no runtime backend API"},
        "host": {"platform":platform.platform(),"cpu_hint":os.environ.get("PROCESSOR_IDENTIFIER"),"exclusive":False},
        "processes": [], "cases": []}
    def save(): (output/"receipt.json").write_text(json.dumps(receipt, indent=2)+"\n", encoding="utf-8")
    def run(name, argv, limited=False, expect=0, memory=512, timeout=30):
        argv = list(map(str, argv)); started = time.monotonic()
        resource_error = None
        if limited:
            try: code, out, err, timed, peak = launch(argv, cwd=ROOT, env=env, memory_mib=memory, timeout=timeout)
            except LaunchFailure as failure:
                code, out, err, timed, peak = failure.code, failure.stdout, failure.stderr, failure.timed_out, failure.peak
                resource_error = {"reason":str(failure),"pid":failure.pid,"terminated":failure.terminated}
        else:
            result = subprocess.run(argv, cwd=ROOT, env=env, capture_output=True, timeout=240)
            code, out, err, timed = result.returncode, result.stdout, result.stderr, False
            peak=None
        (output/(name+".stdout")).write_bytes(out);(output/(name+".stderr")).write_bytes(err)
        record = {"name":name,"argv":argv,"returncode":code,"seconds":time.monotonic()-started,"timed_out":timed,
            "stdout_sha256":sha(output/(name+".stdout")),"stderr_sha256":sha(output/(name+".stderr")),"limited_job":limited,
            "stdout_bytes":len(out),"stderr_bytes":len(err),"job_peak_process_commit_bytes":peak,"resource_error":resource_error}
        receipt["processes"].append(record);save()
        require(resource_error is None, name+" resource failure (bounded partial output retained)")
        if expect is not None: require(code==expect, name+" unexpected return "+str(code))
        return out.decode("utf-8", errors="strict"), record
    try:
        receipt["isolation_before"] = inspect();require(receipt["isolation_before"]["status"]=="PASS","isolation")
        _, job_controls = run("job-cleanup-controls",[sys.executable,"-B","-m","unittest","discover","-s",HERE,"-p","test_job.py","-v"])
        job_log=(output/"job-cleanup-controls.stderr").read_text(encoding="utf-8")
        require("Ran 4 tests" in job_log and "OK" in job_log and "skipped" not in job_log,"Job cleanup controls incomplete")
        receipt["job_cleanup_controls"]={"tests":4,"status":"PASS","process":job_controls["name"]}
        rustup = cargo.with_name("rustup.exe")
        compiler = None
        for tool in ("cargo","rustc","rustfmt","cargo-clippy","clippy-driver"):
            path = Path(run("resolve-"+tool,[rustup,"which","--toolchain",toolchain,tool])[0].strip())
            receipt["tool_hashes"][str(path)] = sha(path)
            if tool=="rustc": compiler=path
        receipt["rustc_version"] = run("rustc-version",[compiler,"--version","--verbose"])[0]
        metadata = load(run("metadata",[cargo,"+"+toolchain,"metadata","--manifest-path",HERE/"Cargo.toml","--locked","--offline","--format-version","1"])[0])
        packages = {item["id"]:item for item in metadata["packages"]}
        node = next(n for n in metadata["resolve"]["nodes"] if packages[n["id"]]["name"]=="flate2")
        require("zlib-rs" in node["features"] and "any_c_zlib" not in node["features"],"wrong effective compression selection")
        receipt["compression"]["resolved_features"] = node["features"]
        registry = []
        for item in metadata["packages"]:
            manifest=Path(item["manifest_path"])
            if item["source"]:
                cache = manifest.parents[3]/"cache"/manifest.parents[1].name/(item["name"]+"-"+item["version"]+".crate")
                expected = next(x["checksum"] for x in tomllib.loads((HERE/"Cargo.lock").read_text())["package"] if x["name"]==item["name"] and x["version"]==item["version"])
                require(sha(cache)==expected,"crate archive checksum drift")
                files={}
                with tarfile.open(cache,"r:gz") as archive:
                    for member in archive.getmembers():
                        if not member.isfile(): continue
                        prefix=item["name"]+"-"+item["version"]+"/"
                        require(member.name.startswith(prefix) and ".." not in Path(member.name).parts,"archive path")
                        relative=member.name[len(prefix):]
                        expected_file=hashlib.sha256(archive.extractfile(member).read()).hexdigest()
                        files[relative]=sha(manifest.parent/relative)
                        require(files[relative]==expected_file,"extracted dependency source drift")
                observed={str(f.relative_to(manifest.parent)).replace("\\","/") for f in manifest.parent.rglob("*") if f.is_file() and f.name not in (".cargo-ok",".cargo-checksum.json")}
                require(set(files)==observed,"unexpected extracted dependency files")
                registry.append({"name":item["name"],"version":item["version"],"license":item["license"],"archive_sha256":sha(cache),"manifest_sha256":sha(manifest),
                    "verified_source_files":len(files),"source_tree_sha256":hashlib.sha256(json.dumps(files,sort_keys=True).encode()).hexdigest()})
        receipt["dependency_inventory"] = registry
        run("build",[cargo,"+"+toolchain,"build","--release","--locked","--offline","--manifest-path",HERE/"Cargo.toml","--target-dir",output/"build"])
        run("clippy",[cargo,"+"+toolchain,"clippy","--locked","--offline","--manifest-path",HERE/"Cargo.toml","--target-dir",output/"build","--all-targets","--","-D","warnings"])
        run("format",[cargo,"+"+toolchain,"fmt","--manifest-path",HERE/"Cargo.toml","--","--check"])
        exe=output/"build/release/nbt-region-experiment.exe";receipt["tool_hashes"][str(exe)]=sha(exe)
        classes=output/"java";classes.mkdir()
        run("javac",[javac,"-d",classes,HERE/"java/NbtFixtureOracle.java"])
        generated=output/"fixtures"
        oracle_stdout,_=run("java-oracle",[java,"-Xmx256m","-cp",classes,"NbtFixtureOracle","generate",generated])
        require(oracle_stdout.strip()=="JAVA8_RAW_NBT_ORACLE_V1 fixtures=54","oracle count")
        fixtures=[line.split("\t") for line in (generated/"manifest.tsv").read_text().splitlines()]
        require(len(fixtures)==54 and len({row[0] for row in fixtures})==54,"fixture manifest")
        fixture_hashes={str(f.relative_to(output)):sha(f) for f in generated.iterdir()}
        receipt["fixture_hashes_before"]=fixture_hashes
        _, memory_control=run("memory-control",[exe,"memory-limit-control"],limited=True,expect=None,memory=64)
        require(memory_control["returncode"]!=0 and not memory_control["timed_out"],"memory cap not enforced")
        _, timeout_control=run("timeout-control",[exe,"timeout-control"],limited=True,expect=None,timeout=.25)
        require(timeout_control["returncode"]!=0 and timeout_control["timed_out"],"deadline not enforced")
        region_dir=output/"regions";region_dir.mkdir()
        region_hashes={}
        for name, classification, utf_rejected in fixtures:
            path=region_dir/(name+".mca");path.write_bytes(region((generated/(name+".nbt")).read_bytes()))
            region_hashes[str(path.relative_to(output))]=sha(path)
            expected=generated/(name+".expected.nbt") if classification=="VALID" else generated/(name+".nbt")
            for parser in ("lossless","existing","fastnbt","simdnbt"):
                case_dir=output/(name+"--"+parser);case_dir.mkdir()
                value=load(run("case-"+name+"-"+parser,[exe,"process",parser,path,expected,case_dir,"5"],limited=True)[0])
                status=value["status"]
                require(status in ("EXACT_PASS","GUARD_REJECT","CANDIDATE_REJECT","PRESERVATION_LOSS"),"case status")
                if classification=="REJECT": require(status=="GUARD_REJECT","malformed input reached candidate")
                elif parser=="lossless": require(status=="EXACT_PASS","lossless baseline failure")
                else: require(status!="GUARD_REJECT","valid fixture rejected by shared guard")
                if status=="EXACT_PASS":
                    require(set(value)=={"status","parser","raw_bytes","nodes","data_version","max_neid","parse_100_ns","pipeline_samples","publication","stage_order"},"exact result schema")
                    require(value["parser"]==parser and value["publication"] is True and type(value["raw_bytes"])is int and value["raw_bytes"]==len((generated/(name+".nbt")).read_bytes()),"exact result identity")
                    require(len(value["pipeline_samples"])==5 and len(value["parse_100_ns"])==5,"timing count")
                    require(all(type(v)is int and v>0 for v in value["parse_100_ns"]),"parse timing schema")
                    for sample in value["pipeline_samples"]:
                        require(len(sample["stage_ns"])==6 and all(type(v)is int and v>=0 for v in sample["stage_ns"]) and sample["total_ns"]>0,"timing schema")
                    published_paths=sorted(case_dir.glob("*.mca"))
                    require([file.name for file in published_paths]==[f"ordered-{i}.mca" for i in range(5)],"missing or unexpected published region")
                    for published in published_paths:
                        data=published.read_bytes();offset=int.from_bytes(data[:3],"big")*4096;length=int.from_bytes(data[offset:offset+4],"big")
                        require(zlib.decompress(data[offset+5:offset+4+length])==expected.read_bytes(),"independent published-region check")
                if status=="PRESERVATION_LOSS":
                    require(set(value)=={"status","first_divergence","rejected_attempt_publication","earlier_exact_attempts","parse_100_ns"} and value["rejected_attempt_publication"] is False,"loss result schema")
                    divergence=case_dir/"FIRST-DIVERGENCE.actual.nbt";require(divergence.is_file(),"missing first divergence")
                    actual=divergence.read_bytes();wanted=expected.read_bytes()
                    diff=next((i for i,(a,b) in enumerate(zip(actual,wanted)) if a!=b),min(len(actual),len(wanted)) if len(actual)!=len(wanted) else None)
                    require(diff is not None and value["first_divergence"]["offset"]==diff,"first divergence offset")
                    require(value["first_divergence"]["actual_len"]==len(actual) and value["first_divergence"]["expected_len"]==len(wanted),"first divergence length")
                    value["first_divergence"]["actual_sha256"]=sha(divergence)
                    value["first_divergence"]["expected_sha256"]=sha(expected)
                if status in ("GUARD_REJECT","CANDIDATE_REJECT"):
                    require(set(value)=={"status","reason"} and isinstance(value["reason"],str) and value["reason"],"refusal result schema")
                if status!="EXACT_PASS":
                    original=(generated/(name+".nbt")).read_bytes()
                    for untouched in case_dir.glob("*.mca"):
                        data=untouched.read_bytes();offset=int.from_bytes(data[:3],"big")*4096;length=int.from_bytes(data[offset:offset+4],"big")
                        require(zlib.decompress(data[offset+5:offset+4+length]) in (original,expected.read_bytes()),"refused candidate published partial/corrupt result")
                receipt["cases"].append({"fixture":name,"oracle":classification,"java_readUTF_rejected_strings":int(utf_rejected),"parser":parser,"result":value})
                save()
        control_dir=output/"transaction-controls";control_dir.mkdir()
        control=load(run("transaction-controls",[exe,"controls",region_dir/"minimal.mca",control_dir],limited=True)[0])
        require(control["status"]=="PASS" and control["count"]==15,"transaction controls")
        receipt["transaction_controls"]=control
        receipt["fixture_hashes_after"]={str(f.relative_to(output)):sha(f) for f in generated.iterdir()}
        require(receipt["fixture_hashes_before"]==receipt["fixture_hashes_after"],"fixture drift")
        receipt["region_input_hashes_before"]=region_hashes
        receipt["region_input_hashes_after"]={str(f.relative_to(output)):sha(f) for f in region_dir.iterdir()}
        require(receipt["region_input_hashes_before"]==receipt["region_input_hashes_after"],"region input drift")
        receipt["summary"]={parser:dict(Counter(c["result"]["status"] for c in receipt["cases"] if c["parser"]==parser)) for parser in ("lossless","existing","fastnbt","simdnbt")}
        receipt["status"]="PASS"
    except Exception as error:
        receipt["status"]="FAIL";receipt["error"]=type(error).__name__+": "+str(error)
    finally:
        receipt["source_after"]=sources();receipt["isolation_after"]=inspect()
        if receipt["source_before"]!=receipt["source_after"] or receipt["isolation_after"]["status"]!="PASS" or any(sha(path)!=value for path,value in receipt["tool_hashes"].items()):
            receipt["status"]="FAIL";receipt["source_tool_or_isolation_drift"]=True
        receipt["finish_utc"]=datetime.now(timezone.utc).isoformat();save()
    print(json.dumps({"status":receipt["status"],"receipt":str(output/"receipt.json"),"error":receipt.get("error")}))
    return int(receipt["status"]!="PASS")

if __name__=="__main__": raise SystemExit(main())
