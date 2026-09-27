"""Pinned offline storage comparison; exact reference checks precede timings."""
from __future__ import annotations
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import random
import secrets
import statistics
import subprocess
import sys
import time
import tomllib
import uuid
from collect_licenses import source_inventory
from reference import Reference,benchmark_expected

HERE=Path(__file__).resolve().parent
ROOT=HERE.parents[1]
sys.path.insert(0,str(ROOT/"tools/testing"))
from hardening_guard import inspect
BACKENDS=("soa","hecs","bevy","shipyard")
PHASES=("initialize","spawn_with_cold_payloads","hot_iteration","logical_lookup","extension_add_remove_churn","despawn_respawn_with_cold_payloads","native_hot_scan_materialization","restore_logical_order_with_copy","spatial_index_build_with_copy","spatial_queries","collision_broadphase","complete_pure_tick_pipeline")
def sha(path):return hashlib.sha256(Path(path).read_bytes()).hexdigest()
def require(condition,message):
    if not condition:raise RuntimeError(message)
def unique(pairs):
    result={}
    for k,v in pairs:
        require(k not in result,"duplicate JSON field");result[k]=v
    return result
def load(text):return json.loads(text,object_pairs_hook=unique,parse_constant=lambda _:(_ for _ in ()).throw(ValueError("nonfinite JSON")))
def canonical(value):return json.dumps(value,sort_keys=True,separators=(",",":"))
def environment():
    exact={"JAVA_TOOL_OPTIONS","_JAVA_OPTIONS","JDK_JAVA_OPTIONS","JDK_JAVAC_OPTIONS","CLASSPATH","JAVA_OPTS","CLIPPY_ARGS","CLIPPY_CONF_DIR","CL","_CL_","LINK","_LINK_","CFLAGS","CXXFLAGS","CPPFLAGS","LDFLAGS"}
    blocked=sorted(k for k in os.environ if k.upper() in exact or k.upper().startswith("CARGO_") and k.upper()!="CARGO_HOME" or k.upper().startswith("RUST") and k.upper()!="RUSTUP_HOME")
    result={k:v for k,v in os.environ.items() if k not in blocked};result["RUST_BACKTRACE"]="0"
    return result,dict(removed_names=blocked,blocked_exact=sorted(exact),blocked_prefixes=["CARGO_ except pinned CARGO_HOME","RUST except RUSTUP_HOME"],explicit={"RUST_BACKTRACE":"0"},remaining="inherited OS toolchain environment; values not recorded")

def process(command,folder,name,env,expected_exit=0):
    argv=list(map(str,command));timeout=120;caps={"stdout":8<<20,"stderr":2<<20};started=time.monotonic();child=subprocess.Popen(argv,cwd=HERE,env=env,stdin=subprocess.DEVNULL,stdout=subprocess.PIPE,stderr=subprocess.PIPE,bufsize=0,shell=False)
    streams={k:getattr(child,k) for k in caps};active=set(streams);buffers={k:bytearray() for k in streams};error=None;exit_time=None
    try:
        for stream in streams.values():os.set_blocking(stream.fileno(),False)
        while active:
            progress=False
            for key in tuple(active):
                try:data=os.read(streams[key].fileno(),4096)
                except BlockingIOError:continue
                if not data:active.remove(key);continue
                progress=True;remaining=caps[key]-len(buffers[key]);buffers[key].extend(data[:remaining])
                if len(data)>remaining:error=key+" byte budget";break
            now=time.monotonic()
            if error:break
            if now-started>timeout:error="lifetime budget";break
            if child.poll() is not None:
                if exit_time is None:exit_time=now
                if active and now-exit_time>1:error="inherited pipe remained open";break
            if not progress:time.sleep(.002)
    finally:
        if error and child.poll() is None:child.kill()
        try:child.wait(timeout=max(.1,timeout-(time.monotonic()-started)))
        except subprocess.TimeoutExpired:error="child did not exit";child.kill();child.wait(timeout=3)
        for stream in streams.values():stream.close()
    for key,data in buffers.items():(folder/(name+"."+key+".txt")).write_bytes(data)
    record=dict(argv=argv,exit=child.returncode,resource_error=error,elapsed_seconds=time.monotonic()-started,byte_budgets=caps,stdout_sha256=sha(folder/(name+".stdout.txt")),stderr_sha256=sha(folder/(name+".stderr.txt")))
    (folder/(name+".process.json")).write_text(json.dumps(record,indent=2)+"\n",encoding="utf-8")
    require(error is None and child.returncode==expected_exit,"process failed: "+name)
    return buffers["stdout"].decode("utf-8"),buffers["stderr"].decode("utf-8")

def dependencies(cache,archives):
    inventory=load((HERE/"third-party/inventory.json").read_text());require(inventory["cargo_lock_sha256"]==sha(HERE/"Cargo.lock"),"license lock drift")
    packages=[p for p in tomllib.loads((HERE/"Cargo.lock").read_text())["package"] if "source" in p]
    require(len(packages)==inventory["registry_package_count"]==104,"dependency count drift")
    for p,row in zip(packages,inventory["packages"]):
        key=p["name"]+"-"+p["version"];archive=archives/(key+".crate")
        require((p["name"],p["version"],p["checksum"])==(row["name"],row["version"],row["archive_sha256"]),"dependency identity drift")
        require(sha(archive)==p["checksum"],"archive checksum drift")
        digest,count=source_inventory(cache/key,archive,key)
        require(digest==row["source_inventory_sha256"] and count==row["source_file_count"],"dependency source drift")
        for notice in row["licenses"]:require(sha(HERE/notice["path"])==notice["sha256"],"license text drift")
    return inventory

def fixture_commands():
    rng=random.Random(0x11_225D);slots=list(range(24));rng.shuffle(slots)
    commands=["SPAWN %d %d"%(s,s//2) for s in slots]
    commands += ["SPAWN 0 9","PROBE 10 0 1","EXT 9 0 1 44","EXT 9 1 1 99","VELOCITY 9 0 1 9223372036854775807 2 -3","TICK","TICK","PROBE 9 0 1","LIFE 9 2 1 0","TICK","LIFE 9 2 1 1","EXT 9 0 1 -1","QUERY -100 -100 -100 100 100 100","PAIRS"]
    for slot in range(0,24,3):commands += ["DESPAWN 9 %d 1"%slot,"SPAWN %d 5"%slot,"PROBE 9 %d 1"%slot,"EXT 9 %d 1 8"%slot]
    commands += ["LIFE 9 0 1 2","LIFE 9 0 2 2","TICK","PAIRS","QUERY 0 0 0 9000 9000 9000"]
    model=Reference()
    for command in commands:model.apply(command)
    for _ in range(64):
        slot=rng.randrange(24);generation=model.rows[slot]["key"][2]
        command=rng.choice(["TICK","PAIRS","EXT 9 %d %d %d"%(slot,generation,rng.choice([-1,0,123])),"VELOCITY 9 %d %d %d %d %d"%(slot,generation,rng.randrange(-8,9),rng.randrange(-8,9),rng.randrange(-8,9)),"PROBE 9 %d %d"%(slot,generation),"LIFE 9 %d %d %d"%(slot,generation,rng.randrange(2))])
        commands.append(command);model.apply(command)
    return commands

def compare_trace(text,commands,session,challenge):
    rows=[load(line) for line in text.splitlines()];require(len(rows)==len(commands)+1,"boundary count")
    reference=Reference()
    for step,(command,actual) in enumerate(zip(["INITIAL",*commands],rows)):
        expected=dict(schema="ENTITY_STORAGE_BOUNDARY_V1",session=session,challenge=challenge,step=step,**reference.apply(command),production_authority=False)
        if canonical(actual)!=canonical(expected):return dict(step=step,command=command,expected=expected,actual=actual)
    return None

def campaign(a,folder):
    started=time.monotonic();guard_before=inspect(ROOT);require(guard_before["status"]=="PASS","isolation before")
    env,policy=environment();cargo=a.cargo.resolve(strict=True);rustc=a.rustc.resolve(strict=True)
    inventory=dependencies(a.cache,a.crate_cache)
    sources={str(p):sha(p) for p in [HERE/"Cargo.toml",HERE/"Cargo.lock",*HERE.glob("*.py"),*HERE.glob("src/*.rs"),ROOT/"tools/testing/hardening_guard.py",ROOT/"machine/architecture-hardening/isolation.json",*[p for p in (HERE/"third-party").rglob("*") if p.is_file()]]}
    tools={str(p):sha(p) for p in (cargo,rustc,Path(sys.executable))};checks=[]
    def run(command,name,exit=0):
        out,err=process(command,folder,name,env,exit);checks.append(name);return out,err
    sysroot,_=run([rustc,"--print","sysroot"],"rust-sysroot");bin_path=Path(sysroot.strip())/"bin";suffix=".exe" if os.name=="nt" else ""
    for name in ("cargo","cargo-clippy","clippy-driver","rustc","rustdoc","rustfmt"):
        p=(bin_path/(name+suffix)).resolve(strict=True);tools[str(p)]=sha(p)
    cargo_home=a.cache.resolve().parents[2];require(a.crate_cache.resolve().parents[2]==cargo_home,"cache homes differ")
    env.update(RUSTC=str(bin_path/("rustc"+suffix)),CARGO_HOME=str(cargo_home),PATH=str(bin_path)+os.pathsep+env.get("PATH",env.get("Path","")))
    policy["explicit"].update(RUSTC=env["RUSTC"],CARGO_HOME=env["CARGO_HOME"],PATH_PREPEND=str(bin_path))
    candidates={cargo_home/"config",cargo_home/"config.toml"}
    for parent in (HERE,*HERE.parents):candidates.update((parent/".cargo/config",parent/".cargo/config.toml",parent/"rust-toolchain",parent/"rust-toolchain.toml"))
    for p in candidates:
        if p.is_file():sources[str(p)]=sha(p)
    run([rustc,"-vV"],"rust-version");run([cargo,"-vV"],"cargo-version")
    raw,_=run([cargo,"metadata","--locked","--offline","--format-version","1"],"cargo-metadata");metadata=load(raw)
    actual={(p["name"],p["version"],str(Path(p["manifest_path"]).resolve())) for p in metadata["packages"] if p["source"]}
    expected={(p["name"],p["version"],str((a.cache/(p["name"]+"-"+p["version"])/"Cargo.toml").resolve())) for p in inventory["packages"]}
    require(actual==expected,"Cargo source-cache binding")
    tree,_=run([cargo,"tree","--locked","--offline","--edges","normal,build","--prefix","none"],"selected-cargo-tree")
    selected=sorted(set(line.strip().removesuffix(" (*)") for line in tree.splitlines()))
    target=folder/"cargo-target"
    run([cargo,"fmt","--","--check"],"rust-fmt");run([cargo,"test","--locked","--offline","--target-dir",target],"rust-tests")
    run([cargo,"clippy","--locked","--offline","--target-dir",target,"--","-D","warnings"],"rust-clippy")
    run([cargo,"build","--release","--locked","--offline","--target-dir",target],"rust-release")
    run([sys.executable,"-B","-m","unittest","test_reference","-v"],"reference-tests")
    binary=target/"release"/("entity-storage-experiment"+suffix);artifact=sha(binary)
    commands=fixture_commands();trace=folder/"commands.txt";trace.write_text("\n".join(commands)+"\n",encoding="utf-8");trace_hash=sha(trace);controls=[];preserved=None
    for backend in BACKENDS:
        session=uuid.uuid4().hex;challenge=secrets.token_hex(32);out,err=run([binary,"trace",backend,session,challenge,trace],"trace-"+backend);require(not err,"unexpected trace stderr")
        mismatch=compare_trace(out,commands,session,challenge);require(mismatch is None,"reference mismatch: "+backend);controls.append(dict(name=backend+"-reference",boundaries=len(commands)+1,status="PASS"))
        if preserved is None:preserved=(out,session,challenge)
    for fault in ("missing","identity","value","order"):
        session=uuid.uuid4().hex;challenge=secrets.token_hex(32);out,err=run([binary,"trace","hecs",session,challenge,trace,fault],"fault-"+fault);require(not err,"unexpected fault stderr");mismatch=compare_trace(out,commands,session,challenge);require(mismatch is not None,"injected fault missed")
        expected_step=1 if fault in ("missing","identity") else commands.index("TICK")+1;require(mismatch["step"]==expected_step,"wrong first divergence")
        p=folder/("fault-"+fault+".divergence.json");p.write_text(json.dumps(mismatch,indent=2)+"\n",encoding="utf-8");controls.append(dict(name=fault,first_divergence_step=mismatch["step"],receipt=str(p),sha256=sha(p),status="EXPECTED_DIVERGENCE"))
    out,session,challenge=preserved
    require(compare_trace(out,commands,uuid.uuid4().hex,challenge)["step"]==0,"cross-session replay accepted")
    require(compare_trace(out,commands,session,secrets.token_hex(32))["step"]==0,"wrong challenge accepted")
    for variant in (out.splitlines()[:-1],out.splitlines()+[out.splitlines()[-1]]):
        try:compare_trace("\n".join(variant),commands,session,challenge)
        except RuntimeError:pass
        else:raise RuntimeError("missing/extra boundary accepted")
    controls.extend(dict(name=name,status="EXPECTED_REJECTION") for name in ("cross-session","wrong-challenge","missing-boundary","extra-boundary"))
    expected_by_size={n:benchmark_expected(n) for n in a.sizes};samples=[]
    # Round-robin rotation reduces fixed backend ordering bias; samples remain
    # separate processes. No concurrent benchmark subprocesses or worker pools.
    for n in a.sizes:
        for repetition in range(a.samples):
            order=BACKENDS[repetition%4:]+BACKENDS[:repetition%4]
            for backend in order:
                session=uuid.uuid4().hex;challenge=secrets.token_hex(32);name="bench-%d-%d-%s"%(n,repetition,backend)
                out,err=run([binary,"bench",backend,session,challenge,n],name);require(not err,"unexpected sample stderr");row=load(out)
                require(row["schema"]=="ENTITY_STORAGE_SAMPLE_V1" and row["session"]==session and row["challenge"]==challenge and row["backend"]==backend and row["entities"]==n and row["production_authority"] is False,"sample binding")
                require([p["name"] for p in row["phases"]]==list(PHASES),"phase inventory")
                require(all(type(p["ns"]) is int and p["ns"]>=0 and type(p["operations"]) is int and p["operations"]>0 for p in row["phases"]),"phase measurement types")
                require(all(row[k]==v for k,v in expected_by_size[n].items()),"independent benchmark checksum mismatch: "+name)
                require(row["memory_is_rss"] is False and row["allocator_instrumentation_enabled"] is True,"memory interpretation")
                samples.append(dict(name=name,repetition=repetition,raw_stdout=str(folder/(name+".stdout.txt")),raw_sha256=sha(folder/(name+".stdout.txt")),result=row))
    summaries=[]
    for n in a.sizes:
        for backend in BACKENDS:
            group=[x["result"] for x in samples if x["result"]["entities"]==n and x["result"]["backend"]==backend]
            summary=dict(entities=n,backend=backend,samples=len(group),phases={})
            for phase in PHASES:
                values=[next(p["ns"] for p in r["phases"] if p["name"]==phase) for r in group]
                summary["phases"][phase]=dict(median_ns=statistics.median(values),min_ns=min(values),max_ns=max(values),samples_ns=values)
            for field in ("populated_live_bytes_delta","retained_live_bytes_delta","residual_live_bytes_delta"):summary[field]=[r[field] for r in group]
            summaries.append(summary)
    require(sha(binary)==artifact and sha(trace)==trace_hash,"artifact/trace drift")
    require(all(sha(path)==value for group in (sources,tools) for path,value in group.items()),"source/tool drift")
    require(inventory==dependencies(a.cache,a.crate_cache),"dependency drift");guard_after=inspect(ROOT);require(guard_after["status"]=="PASS","isolation after")
    receipt=dict(schema="ENTITY_STORAGE_CAMPAIGN_V1",status="PASS",scope="BOUNDED_SYNTHETIC_ENTITY_STORAGE_ONLY",production_authority=False,sources=sources,tools=tools,environment_policy=policy,host=dict(platform=platform.platform(),processor=platform.processor(),logical_cpus=os.cpu_count()),isolation_before=guard_before,isolation_after=guard_after,dependency_inventory_sha256=sha(HERE/"third-party/inventory.json"),selected_dependency_tree=selected,artifact=dict(path=str(binary),sha256=artifact),trace=dict(path=str(trace),sha256=trace_hash,commands=len(commands)),controls=controls,samples=samples,summaries=summaries,reference_benchmark_values={str(k):v for k,v in expected_by_size.items()},checks=checks,all_inputs_unchanged=True,elapsed_seconds=time.monotonic()-started,limitations=["Not Minecraft/Forge entity behavior or a scheduler benchmark","Integer synthetic fields; no Java references or NBT parsing","Allocator requested/live bytes are not RSS; timing includes instrumentation","Current point-lookup API strategy may not be optimal for each ECS","No production authority or live value demonstrated"])
    path=folder/"campaign.json";path.write_text(json.dumps(receipt,indent=2)+"\n",encoding="utf-8");print(json.dumps(dict(status="PASS",receipt=str(path),sha256=sha(path),samples=len(samples))))

def main():
    p=argparse.ArgumentParser(description=__doc__)
    for name in ("cargo","rustc","cache","crate-cache","output"):p.add_argument("--"+name,type=Path,required=True)
    p.add_argument("--samples",type=int,default=5);p.add_argument("--sizes",type=lambda x:[int(v) for v in x.split(",")],default=[1000,10000]);a=p.parse_args()
    if not a.output.resolve().is_relative_to((ROOT/"target").resolve()):p.error("output must be under isolated target/")
    if not 1<=a.samples<=9 or not 1<=len(a.sizes)<=3 or any(not 256<=n<=20000 for n in a.sizes):p.error("bounded samples/sizes required")
    folder=a.output.resolve()/uuid.uuid4().hex;folder.mkdir(parents=True,exist_ok=False)
    try:campaign(a,folder)
    except Exception as error:
        try:guard=inspect(ROOT)
        except Exception as exc:guard=dict(status="ERROR",detail=str(exc))
        path=folder/"campaign.json";path.write_text(json.dumps(dict(schema="ENTITY_STORAGE_CAMPAIGN_V1",status="FAIL",production_authority=False,error=type(error).__name__+": "+str(error),isolation_after=guard,retained_process_records={str(p):sha(p) for p in folder.glob("*.process.json")}),indent=2)+"\n",encoding="utf-8");print(json.dumps(dict(status="FAIL",receipt=str(path),sha256=sha(path))));return 1
    return 0
if __name__=="__main__":raise SystemExit(main())
