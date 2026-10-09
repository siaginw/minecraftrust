"""Reproduce the isolated offline scheduler contract; no performance qualification."""
from __future__ import annotations
import argparse
import hashlib
import json
import os
from pathlib import Path
import secrets
import subprocess
import sys
import time
import uuid
from reference import compare,load,SCENARIOS

HERE=Path(__file__).resolve().parent
ROOT=HERE.parents[1]
sys.path.insert(0,str(ROOT/"tools/testing"))
from hardening_guard import inspect

def sha(path):return hashlib.sha256(Path(path).read_bytes()).hexdigest()
def require(condition,message):
    if not condition:raise RuntimeError(message)
def write(path,value):path.write_text(json.dumps(value,indent=2)+"\n",encoding="utf-8")

def environment():
    exact={"JAVA_TOOL_OPTIONS","_JAVA_OPTIONS","JDK_JAVA_OPTIONS","JDK_JAVAC_OPTIONS","CLASSPATH","JAVA_OPTS","CLIPPY_ARGS","CLIPPY_CONF_DIR","CL","_CL_","LINK","_LINK_","CFLAGS","CXXFLAGS","CPPFLAGS","LDFLAGS"}
    blocked=sorted(k for k in os.environ if k.upper() in exact or k.upper().startswith("CARGO_") and k.upper()!="CARGO_HOME" or k.upper().startswith("RUST") and k.upper()!="RUSTUP_HOME")
    env={k:v for k,v in os.environ.items() if k not in blocked};env["RUST_BACKTRACE"]="0"
    return env,dict(removed_names=blocked,blocked_exact=sorted(exact),blocked_prefixes=["CARGO_ except recorded CARGO_HOME","RUST except recorded RUSTUP_HOME"],explicit={"RUST_BACKTRACE":"0"},remaining="inherited operating system compiler/linker environment; not a hermetic compiler proof")

class Campaign:
    def __init__(self,folder,env):self.folder=folder;self.env=env;self.checks=[];self.remaining=16<<20
    def process(self,argv,name,exit=0):
        argv=list(map(str,argv));caps={"stdout":1<<20,"stderr":512<<10};started=time.monotonic();timeout=120
        child=subprocess.Popen(argv,cwd=HERE,env=self.env,stdin=subprocess.DEVNULL,stdout=subprocess.PIPE,stderr=subprocess.PIPE,bufsize=0,shell=False)
        streams={k:getattr(child,k) for k in caps};buffers={k:bytearray() for k in caps};active=set(streams);error=None;exit_time=None
        try:
            for stream in streams.values():os.set_blocking(stream.fileno(),False)
            while active:
                progress=False
                for key in tuple(active):
                    try:data=os.read(streams[key].fileno(),4096)
                    except BlockingIOError:continue
                    if not data:active.remove(key);continue
                    progress=True;room=min(caps[key]-len(buffers[key]),self.remaining);buffers[key].extend(data[:room]);self.remaining-=min(room,len(data))
                    if len(data)>room:error=key+" or campaign byte budget";break
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
        for key,data in buffers.items():(self.folder/(name+"."+key+".txt")).write_bytes(data)
        record=dict(argv=argv,exit=child.returncode,resource_error=error,elapsed_seconds=time.monotonic()-started,byte_budgets=caps,stdout_sha256=sha(self.folder/(name+".stdout.txt")),stderr_sha256=sha(self.folder/(name+".stderr.txt")))
        write(self.folder/(name+".process.json"),record);self.checks.append(record)
        require(error is None and child.returncode==exit,"process failed: "+name)
        return buffers["stdout"].decode("utf-8"),buffers["stderr"].decode("utf-8")

def sources():
    files=[HERE/"Cargo.toml",HERE/"Cargo.lock",HERE/"mechanisms-research.json",*HERE.glob("*.py"),*HERE.glob("src/*.rs"),*HERE.glob("examples/*.rs"),ROOT/"tools/testing/hardening_guard.py",ROOT/"machine/architecture-hardening/isolation.json",ROOT/"docs/architecture/semantic-scheduler.md"]
    return {str(p):sha(p) for p in files}

def run(args,folder,receipt):
    before=inspect(ROOT);receipt["guard_before"]=before;require(before["status"]=="PASS","isolation before")
    env,policy=environment();receipt["environment_policy"]=policy;campaign=Campaign(folder,env);receipt["processes"]=campaign.checks
    bound_sources=sources();receipt["sources"]=bound_sources
    cargo=args.cargo.resolve(strict=True);rustc=args.rustc.resolve(strict=True);tools={str(p):sha(p) for p in (cargo,rustc,Path(sys.executable))}
    sysroot,_=campaign.process([rustc,"--print","sysroot"],"rust-sysroot");sysroot=Path(sysroot.strip());suffix=".exe" if os.name=="nt" else "";bin_path=sysroot/"bin"
    for p in bin_path.iterdir():
        if p.is_file():tools[str(p)]=sha(p)
    version,_=campaign.process([rustc,"-vV"],"rust-version")
    host=next(line.removeprefix("host: ") for line in version.splitlines() if line.startswith("host: "))
    for p in (sysroot/"lib/rustlib"/host/"lib").rglob("*"):
        if p.is_file():tools[str(p)]=sha(p)
    env.update(RUSTC=str(bin_path/("rustc"+suffix)),PATH=str(bin_path)+os.pathsep+env.get("PATH",env.get("Path","")))
    policy["explicit"].update(RUSTC=env["RUSTC"],PATH_PREPEND=str(bin_path),CARGO_HOME=env.get("CARGO_HOME",str(Path.home()/".cargo")),RUSTUP_HOME=env.get("RUSTUP_HOME",str(Path.home()/".rustup")))
    receipt["tools"]=tools
    candidates={Path(policy["explicit"]["CARGO_HOME"])/"config",Path(policy["explicit"]["CARGO_HOME"])/"config.toml"}
    for parent in (HERE,*HERE.parents):candidates.update((parent/".cargo/config",parent/".cargo/config.toml",parent/"rust-toolchain",parent/"rust-toolchain.toml"))
    configs={str(p):sha(p) if p.is_file() else None for p in candidates};receipt["inherited_build_config"]=configs
    campaign.process([cargo,"-vV"],"cargo-version")
    metadata,_=campaign.process([cargo,"metadata","--locked","--offline","--format-version","1"],"metadata")
    packages=load(metadata)["packages"];require(len(packages)==1 and packages[0]["source"] is None and not packages[0]["dependencies"],"standalone dependency boundary changed")
    receipt["external_dependencies"]=0;target=folder/"cargo-target"
    campaign.process([cargo,"fmt","--","--check"],"fmt")
    tests,_=campaign.process([cargo,"test","--locked","--offline","--target-dir",target],"rust-tests")
    require("28 passed; 0 failed" in tests and "4 passed; 0 failed" in tests,"expected Rust and compile-fail control counts")
    campaign.process([cargo,"clippy","--locked","--offline","--all-targets","--target-dir",target,"--","-D","warnings"],"clippy")
    campaign.process([cargo,"build","--locked","--offline","--release","--example","boundaries","--target-dir",target],"release")
    _,python_tests=campaign.process([sys.executable,"-B","-m","unittest","test_reference","-v"],"reference-tests")
    require("Ran 6 tests" in python_tests and python_tests.rstrip().endswith("OK"),"reference controls")
    binary=target/"release/examples"/("boundaries"+suffix);artifact=sha(binary);receipt["artifact"]={"path":str(binary),"sha256":artifact}
    controls=[];receipt["controls"]=controls;preserved=None
    for seed in (0,17,(1<<64)-1):
        for scenario in SCENARIOS:
            session=uuid.uuid4().hex;challenge=secrets.token_hex(32);name=f"trace-{scenario}-{seed}"
            out,err=campaign.process([binary,session,challenge,scenario,seed],name);require(not err,"unexpected example stderr")
            mismatch=compare(out,session,challenge,scenario,seed)
            if mismatch is not None:write(folder/(name+".first-divergence.json"),mismatch)
            require(mismatch is None,"first divergence: "+name)
            controls.append(dict(name=name,boundaries=33,decisions=32,status="PASS",session=session,challenge=challenge))
            if preserved is None:preserved=(out,session,challenge,scenario,seed)
    out,session,challenge,scenario,seed=preserved
    controls.extend(dict(name=name,status="EXPECTED_REJECTION",first_divergence=compare(out,s,c,scenario,seed)["boundary"]) for name,s,c in (("cross-session","f"*32,challenge),("wrong-challenge",session,"f"*64)))
    rows=out.splitlines()
    for name,altered in (("missing-row",rows[:-1]),("extra-row",rows+[rows[-1]])):
        try:compare("\n".join(altered),session,challenge,scenario,seed)
        except ValueError:controls.append(dict(name=name,status="EXPECTED_REJECTION"))
        else:raise RuntimeError("boundary count accepted")
    for name in ("wrong-value","reordered"):
        altered=list(rows)
        if name=="wrong-value":row=load(altered[1]);row["values"][0]+=1;altered[1]=json.dumps(row)
        else:altered[1],altered[2]=altered[2],altered[1]
        mismatch=compare("\n".join(altered),session,challenge,scenario,seed);require(mismatch and mismatch["boundary"]==1,"wrong first divergence")
        write(folder/(name+".first-divergence.json"),mismatch);controls.append(dict(name=name,status="EXPECTED_DIVERGENCE",first_divergence=1))
    for name,arguments in (("bad-session",["bad",challenge,scenario,seed]),("bad-scenario",[session,challenge,"unknown",seed]),("bad-seed",[session,challenge,scenario,"18446744073709551616"])):
        out,err=campaign.process([binary,*arguments],name,exit=2);require(not out and err,"malformed request must fail before first row");controls.append(dict(name=name,status="EXPECTED_REJECTION"))
    require(sources()==bound_sources,"source inventory drift")
    require(all(sha(Path(p))==digest for p,digest in tools.items()),"tool drift")
    require(all((sha(Path(p)) if Path(p).is_file() else None)==digest for p,digest in configs.items()),"build config drift")
    require(sha(binary)==artifact,"artifact drift")
    receipt.update(rust_unit_tests=28,rust_compile_fail_tests=4,python_tests=6,independent_boundary_comparisons=27*33,ordered_decisions=27*32,performance_claim="none; elapsed times are workflow observations only",production_authority=False,status="PASS")

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cargo",type=Path,default=Path(r"C:\Users\Admin\.cargo\bin\cargo.exe"))
    parser.add_argument("--rustc",type=Path,default=Path(r"C:\Users\Admin\.cargo\bin\rustc.exe"))
    parser.add_argument("--output",type=Path)
    args=parser.parse_args();base=(ROOT/"target/semantic-scheduler-prototype").resolve();folder=(args.output or base/uuid.uuid4().hex).resolve()
    if not folder.is_relative_to(base) or folder==base:parser.error("output must be a new child below isolated target/semantic-scheduler-prototype")
    folder.mkdir(parents=True,exist_ok=False);started=time.monotonic();receipt=dict(schema="SEMANTIC_SCHEDULER_CAMPAIGN_V1",status="FAIL",output=str(folder),production_authority=False)
    try:run(args,folder,receipt)
    except Exception as error:receipt.update(status="FAIL",error=type(error).__name__+": "+str(error))
    finally:
        try:
            receipt["guard_after"]=inspect(ROOT)
            if receipt["guard_after"]["status"]!="PASS":receipt.update(status="FAIL",error="isolation after")
        except Exception as error:receipt.update(status="FAIL",guard_after_error=str(error))
        receipt["elapsed_seconds"]=time.monotonic()-started;write(folder/"campaign.json",receipt)
    print(json.dumps(dict(status=receipt["status"],receipt=str(folder/"campaign.json"),sha256=sha(folder/"campaign.json"),error=receipt.get("error"))))
    return int(receipt["status"]!="PASS")

if __name__=="__main__":raise SystemExit(main())
