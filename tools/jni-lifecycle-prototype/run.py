"""Java8 -Xcheck:jni lifecycle evidence; isolated locked dependencies only."""
from __future__ import annotations
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import sys
import time
import tomllib
import uuid
from collect_licenses import source_inventory

HERE=Path(__file__).resolve().parent
ROOT=HERE.parents[1]
sys.path.insert(0,str(ROOT/"tools/testing"))
from hardening_guard import inspect as inspect_isolation

def sha(p):return hashlib.sha256(Path(p).read_bytes()).hexdigest()
def require(ok,reason):
    if not ok:raise RuntimeError(reason)

def controlled_environment():
    explicit={"JAVA_TOOL_OPTIONS","_JAVA_OPTIONS","JDK_JAVA_OPTIONS","JDK_JAVAC_OPTIONS","CLASSPATH","JAVA_OPTS","CLIPPY_ARGS","CLIPPY_CONF_DIR","CL","_CL_","LINK","_LINK_","CFLAGS","CXXFLAGS","CPPFLAGS","LDFLAGS"}
    removed=sorted(k for k in os.environ if k.upper() in explicit or (k.upper().startswith("CARGO_") and k.upper()!="CARGO_HOME") or (k.upper().startswith("RUST") and k.upper()!="RUSTUP_HOME"))
    environment={k:v for k,v in os.environ.items() if k not in removed}
    environment["RUST_BACKTRACE"]="0"
    policy=dict(schema="JNI_PROTOTYPE_ENVIRONMENT_V1",removed_variable_names=removed,blocked_exact=sorted(explicit),blocked_prefixes=["CARGO_ except pinned CARGO_HOME","RUST except RUSTUP_HOME"],explicit_values={"RUST_BACKTRACE":"0"},remaining_environment="Inherited OS/toolchain environment; no secret environment values retained")
    return environment,policy

def process(command,folder,name,expected_exit=0,environment=None):
    """Bound both streams, including malformed JNI diagnostics and compiler output."""
    argv=list(map(str,command));cap=2<<20;timeout=120;started=time.monotonic()
    child=subprocess.Popen(argv,cwd=HERE,stdin=subprocess.DEVNULL,stdout=subprocess.PIPE,stderr=subprocess.PIPE,bufsize=0,shell=False,env=environment)
    streams={name:getattr(child,name) for name in ("stdout","stderr")};active=set(streams);buffers={name:bytearray() for name in streams};error=None;exit_time=None
    try:
        for stream in streams.values():os.set_blocking(stream.fileno(),False)
        while active:
            progressed=False
            for stream in tuple(active):
                try:data=os.read(streams[stream].fileno(),4096)
                except BlockingIOError:continue
                if not data:active.remove(stream);continue
                progressed=True;remaining=cap-len(buffers[stream]);buffers[stream].extend(data[:remaining])
                if len(data)>remaining:error=stream+" byte limit exceeded";break
            now=time.monotonic()
            if error:break
            if now-started>timeout:error="process lifetime exceeded";break
            if child.poll() is not None:
                if exit_time is None:exit_time=now
                if active and now-exit_time>1:error="inherited pipes remained open";break
            if not progressed:time.sleep(.005)
    finally:
        if error is not None and child.poll() is None:child.kill()
        try:child.wait(timeout=max(.1,timeout-(time.monotonic()-started)))
        except subprocess.TimeoutExpired:
            error="process did not exit after closing streams";child.kill();child.wait(timeout=3)
        for stream in streams.values():stream.close()
    for stream,data in buffers.items():(folder/(name+"."+stream+".txt")).write_bytes(data)
    record=dict(name=name,argv=argv,exit=child.returncode,elapsed_seconds=time.monotonic()-started,output_byte_limit_per_stream=cap,resource_error=error,stdout_sha256=sha(folder/(name+".stdout.txt")),stderr_sha256=sha(folder/(name+".stderr.txt")))
    (folder/(name+".process.json")).write_text(json.dumps(record,indent=2)+"\n",encoding="utf-8")
    require(error is None and child.returncode==expected_exit,"process failed; retained "+str(folder/(name+".process.json")))
    return record,buffers["stdout"].decode("utf-8"),buffers["stderr"].decode("utf-8")

def inspect_output(text):
    records=[];diagnostics=[]
    for index,line in enumerate(text.splitlines(),1):
        if line.startswith('{'):records.append(json.loads(line,object_pairs_hook=unique_object,parse_constant=lambda _: (_ for _ in ()).throw(ValueError("nonfinite JSON"))))
        else:diagnostics.append(dict(line=index,text=line))
    return records,diagnostics

def unique_object(pairs):
    result={}
    for key,value in pairs:
        require(key not in result,"duplicate JSON field")
        result[key]=value
    return result

def verify_dependencies(cache,crate_cache):
    inventory=json.loads((HERE/"third-party/inventory.json").read_text())
    require(inventory["cargo_lock_sha256"]==sha(HERE/"Cargo.lock"),"license inventory lock mismatch")
    packages=[p for p in tomllib.loads((HERE/"Cargo.lock").read_text())["package"] if "source" in p]
    require(len(packages)==inventory["registry_package_count"]==25,"unexpected locked dependency count")
    observed=[]
    for package,row in zip(packages,inventory["packages"]):
        key=package["name"]+"-"+package["version"];source=cache/key;archive=crate_cache/(key+".crate")
        require((package["name"],package["version"],package["checksum"])==(row["name"],row["version"],row["archive_sha256"]),"license inventory identity mismatch")
        require(sha(archive)==package["checksum"],"cached crate archive drift")
        actual,count=source_inventory(source,archive,key)
        require(actual==row["source_inventory_sha256"] and count==row["source_file_count"],"cached dependency source drift")
        for license in row["licenses"]:require(sha(HERE/license["path"])==license["sha256"],"license text drift")
        observed.append(dict(name=key,archive_sha256=sha(archive),source_inventory_sha256=actual,source_files=count,license_expression=row["license_expression"]))
    return observed

def campaign(a,folder):
    start=time.monotonic();environment,environment_policy=controlled_environment()
    guard_before=inspect_isolation(ROOT)
    (folder/"isolation-before.json").write_text(json.dumps(guard_before,indent=2)+"\n",encoding="utf-8")
    require(guard_before["status"]=="PASS","isolation guard failed before campaign")
    suffix=".exe" if os.name=="nt" else ""
    java=(a.java_home/"bin"/("java"+suffix)).resolve(strict=True);javac=(a.java_home/"bin"/("javac"+suffix)).resolve(strict=True);cargo=a.cargo.resolve(strict=True);rustc=a.rustc.resolve(strict=True)
    source_paths=[HERE/"Cargo.toml",HERE/"Cargo.lock",ROOT/"tools/testing/hardening_guard.py",ROOT/"machine/architecture-hardening/isolation.json",*HERE.glob("*.py"),*HERE.glob("src/*.rs"),*HERE.glob("java/*.java"),*HERE.glob("compile-fail/*.rs"),*[x for x in (HERE/"third-party").rglob("*") if x.is_file()]]
    sources={str(x):sha(x) for x in source_paths};tools={str(x):sha(x) for x in (java,javac,cargo,rustc,Path(sys.executable))}
    runtime={str(x):sha(x) for root in (a.java_home/"jre/bin",a.java_home/"jre/lib") for x in root.rglob("*") if x.is_file()}
    runtime[str(a.java_home/"lib/tools.jar")]=sha(a.java_home/"lib/tools.jar")
    dependencies=verify_dependencies(a.cache,a.crate_cache);checks=[]
    def run(command,name,exit=0):
        item,out,err=process(command,folder,name,exit,environment);checks.append(item);return out,err
    sysroot,_=run([rustc,"--print","sysroot"],"rust-sysroot")
    rust_bin=Path(sysroot.strip())/"bin"
    for name in ("cargo","cargo-clippy","clippy-driver","rustc","rustfmt","rustdoc"):
        path=(rust_bin/(name+suffix)).resolve(strict=True);tools[str(path)]=sha(path)
    cargo_home=a.cache.resolve().parents[2]
    require(a.crate_cache.resolve().parents[2]==cargo_home,"source/archive cache homes differ")
    environment.update(RUSTC=str(rust_bin/("rustc"+suffix)),CARGO_HOME=str(cargo_home),PATH=str(rust_bin)+os.pathsep+environment.get("PATH",environment.get("Path","")))
    environment_policy["explicit_values"].update(RUSTC=environment["RUSTC"],CARGO_HOME=environment["CARGO_HOME"],PATH_PREPEND=str(rust_bin))
    # Cargo configuration is retained and hashed, not silently rewritten. Metadata
    # below verifies that the build actually resolves to the inspected source cache.
    config_candidates={cargo_home/"config",cargo_home/"config.toml"}
    for parent in (HERE,*HERE.parents):
        config_candidates.update((parent/".cargo/config",parent/".cargo/config.toml",parent/"rust-toolchain",parent/"rust-toolchain.toml"))
    for path in config_candidates:
        if path.is_file():sources[str(path)]=sha(path)
    run([java,"-version"],"java-version");run([cargo,"-vV"],"cargo-version");run([rustc,"-vV"],"rustc-version")
    metadata,_=run([cargo,"metadata","--locked","--offline","--format-version","1"],"cargo-metadata")
    packages=json.loads(metadata)["packages"]
    actual={(p["name"],p["version"],str(Path(p["manifest_path"]).resolve())) for p in packages if p["source"] is not None}
    expected={(p["name"],p["version"],str((a.cache/(p["name"]+"-"+p["version"])/"Cargo.toml").resolve())) for p in tomllib.loads((HERE/"Cargo.lock").read_text())["package"] if "source" in p}
    require(actual==expected,"Cargo resolved dependencies outside verified cache")
    target=folder/"cargo-target";classes=folder/"classes";classes.mkdir()
    run([cargo,"fmt","--","--check"],"rust-fmt")
    run([cargo,"test","--locked","--offline","--target-dir",target],"rust-tests")
    run([cargo,"clippy","--locked","--offline","--target-dir",target,"--","-D","warnings"],"rust-clippy")
    run([cargo,"build","--release","--locked","--offline","--target-dir",target],"rust-release")
    run([javac,"-encoding","UTF-8","-d",classes,*sorted((HERE/"java").glob("*.java"))],"java-build")
    library=target/"release"/("jni_lifecycle_prototype.dll" if os.name=="nt" else ("libjni_lifecycle_prototype.dylib" if sys.platform=="darwin" else "libjni_lifecycle_prototype.so"))
    rlibs=list((target/"release/deps").glob("libjni-*.rlib"));require(len(rlibs)==1,"ambiguous jni compile-control artifact")
    compile_args=[rustc,"--edition","2021","--crate-type","lib","--extern","jni="+str(rlibs[0]),"-L","dependency="+str(target/"release/deps"),"--out-dir",folder]
    run([*compile_args,"--crate-name","valid_local",HERE/"compile-fail/valid_local.rs"],"valid-local-compile")
    _,diagnostic=run([*compile_args,"--crate-name","escape_local",HERE/"compile-fail/escape_local.rs"],"escape-local-compile",1)
    require("lifetime may not live long enough" in diagnostic and "escape_local.rs" in diagnostic,"compile failure not the expected lifetime rejection")
    artifacts={str(x):sha(x) for x in [library,rlibs[0],*classes.rglob("*.class"),*folder.glob("libvalid_local.rlib")]}
    base=[java,"-Xcheck:jni","-Xmx64m","-cp",classes];baselines={};java_rows=[]
    for count in (0,2,4,5):
        out,err=run([*base,"BootstrapOnly",*(["baseline"]*count)],"bootstrap-"+str(count))
        records,warnings=inspect_output(out);require(records==[{"schema":"JVM_BOOTSTRAP_CONTROL_V1","native_library_loaded":False}] and not err,"malformed bootstrap baseline")
        baselines[str(count)]=dict(diagnostics=warnings)
    out,err=run([*base,"PathOnly",library,classes/"LoaderPayload.class"],"nio-without-prototype")
    nio_records,nio_diagnostics=inspect_output(out)
    require([r.get("event") or r.get("schema") for r in nio_records]==["BEFORE_PATHS","JVM_NIO_CONTROL_V1"] and not err,"invalid NIO baseline")
    require([r["text"] for r in nio_diagnostics]==[r["text"] for r in baselines["2"]["diagnostics"]],"NIO added unexpected JNI diagnostics")
    for mode in ("load-only","ping","local","callback","direct","worker","worker-fail","badsig","null","panic","class","global","full"):
        session=uuid.uuid4().hex;challenge=secrets.token_hex(32)
        command=[*base,"LifecycleProbe",library,classes/"LoaderPayload.class",session,challenge]
        if mode!="full":command.append(mode)
        out,err=run(command,"probe-"+mode);rows,warnings=inspect_output(out)
        expected=baselines["4" if mode=="full" else "5"]["diagnostics"]
        require([r["text"] for r in warnings]==[r["text"] for r in expected],"incremental JNI warning: "+mode)
        first_event=next((i for i,line in enumerate(out.splitlines(),1) if line.startswith('{')),None)
        require(first_event is not None and all(row["line"]<first_event for row in warnings),"JNI warning after Java fixture entry: "+mode)
        if mode in ("full","panic"):
            pattern=r"\s*thread '<unnamed>'(?: \([0-9]+\))? panicked at [^\r\n]+:\d+:\d+:\s*controlled JNI lifecycle panic\s*note: run with `RUST_BACKTRACE=1` environment variable to display a backtrace\s*"
            require(re.fullmatch(pattern,err) is not None,"unexpected panic/native stderr: "+mode)
        else:require(not err,"unexpected native stderr: "+mode)
        final=rows[-1];require(final.get("status")=="PASS" and final.get("session")==session and final.get("challenge")==challenge,"probe binding/result mismatch")
        require(rows[0]=={"event":"BEFORE_PATHS"} and rows[1]=={"event":"LIBRARY_LOADED"},"probe phase schema")
        require(all(isinstance(row,dict) and set(row)=={"event","name"} and row["event"]=="CHECK" and isinstance(row["name"],str) for row in rows[2:-1]),"unexpected probe record")
        if mode=="full":
            names=[row["name"] for row in rows[2:-1] if row.get("event")=="CHECK"]
            require(set(final)=={"schema","session","challenge","status","production_authority","checks","gc_after_release","gc_proves_leak_freedom"},"full result schema")
            require(final["schema"]=="JNI_LIFECYCLE_PROBE_V1" and len(names)==len(set(names))==66 and names==final["checks"] and final["production_authority"] is False,"full assertion receipt mismatch")
            require(final["gc_after_release"] in ("COLLECTED","INCONCLUSIVE_NOT_COLLECTED") and final["gc_proves_leak_freedom"] is False,"invalid GC claim")
        else:require(set(final)=={"schema","session","challenge","mode","status"} and final.get("schema")=="JNI_ISOLATED_PROBE_V1" and final.get("mode")==mode,"isolated mode mismatch")
        java_rows.append(dict(mode=mode,result=final,jni_diagnostics=warnings,incremental_warnings=[],native_stderr_kind="EXPECTED_CAUGHT_PANIC" if err else "EMPTY"))
    unchanged=all(sha(Path(name))==value for group in (sources,tools,runtime,artifacts) for name,value in group.items());require(unchanged,"source/tool/runtime/artifact drift")
    require(dependencies==verify_dependencies(a.cache,a.crate_cache),"dependency source/archive drift")
    guard_after=inspect_isolation(ROOT)
    (folder/"isolation-after.json").write_text(json.dumps(guard_after,indent=2)+"\n",encoding="utf-8")
    require(guard_after["status"]=="PASS","isolation guard failed after campaign")
    receipt=dict(schema="JNI_LIFECYCLE_CAMPAIGN_V1",status="PASS_WITH_JVM_BASELINE_WARNINGS",decision="PROTOTYPE",production_authority=False,sources=sources,tools=tools,jdk_runtime_files=runtime,artifacts=artifacts,dependencies=dependencies,environment_policy=environment_policy,isolation_before=guard_before,isolation_after=guard_after,checks=checks,bootstrap_baselines=baselines,nio_control=dict(records=nio_records,diagnostics=nio_diagnostics),java_probes=java_rows,full_fixture_assertions=66,compile_fail_lifetime_control="EXPECTED_REJECTION_WITH_VALID_SCALAR_CONTROL",all_inputs_unchanged=unchanged,elapsed_seconds=time.monotonic()-start,limitations=["Not an absolute clean -Xcheck:jni run: selected JVM startup warnings retained","GC collection observation does not prove leak freedom","No JNI performance baseline or speed claim","No production bridge replacement or runtime qualification"])
    path=folder/"campaign.json";path.write_text(json.dumps(receipt,indent=2)+"\n",encoding="utf-8")
    print(json.dumps(dict(status=receipt["status"],receipt=str(path),sha256=sha(path))))

def main():
    p=argparse.ArgumentParser(description=__doc__)
    for name in ("java-home","cargo","rustc","cache","crate-cache","output"):p.add_argument("--"+name,type=Path,required=True)
    a=p.parse_args();output=a.output.resolve()
    if not output.is_relative_to((ROOT/"target").resolve()):p.error("output must be under this isolated checkout's target/")
    folder=output/uuid.uuid4().hex;folder.mkdir(parents=True,exist_ok=False)
    try:campaign(a,folder)
    except Exception as error:
        try:guard=inspect_isolation(ROOT)
        except Exception as guard_error:guard=dict(status="ERROR",reason=str(guard_error))
        _,policy=controlled_environment()
        failure=dict(schema="JNI_LIFECYCLE_CAMPAIGN_V1",status="FAIL",decision="PROTOTYPE",production_authority=False,error_type=type(error).__name__,error=str(error),environment_policy=policy,isolation_after=guard,retained_process_records={str(path):sha(path) for path in folder.glob("*.process.json")},scope="Validation failed; no lifecycle qualification or adoption claim")
        path=folder/"campaign.json";path.write_text(json.dumps(failure,indent=2)+"\n",encoding="utf-8")
        print(json.dumps(dict(status="FAIL",receipt=str(path),sha256=sha(path))))
        return 1
    return 0

if __name__=="__main__":raise SystemExit(main())
