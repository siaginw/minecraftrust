"""Isolated, bounded Windows offline storage campaign with independent facts."""
from __future__ import annotations
import argparse,hashlib,json,os,platform,secrets,statistics,subprocess,sys,time,tomllib,uuid
from pathlib import Path
from collect_licenses import source_inventory
from reference import generate,expected,validate,load,PHASES

HERE=Path(__file__).resolve().parent
ROOT=HERE.parents[1]
sys.path.insert(0,str(ROOT/"tools/testing"))
from hardening_guard import inspect
sys.path.insert(0,str(ROOT/"tools/nbt-region-experiment"))
from job import launch,LaunchFailure

def sha(path):return hashlib.sha256(Path(path).read_bytes()).hexdigest()
def require(condition,message):
    if not condition:raise RuntimeError(message)
def write(path,value):path.write_text(json.dumps(value,indent=2)+"\n",encoding="utf-8")
def sources():
    files=[p for p in HERE.rglob("*") if p.is_file() and "__pycache__" not in p.parts]
    files.extend(ROOT/p for p in ("tools/testing/hardening_guard.py","tools/nbt-region-experiment/job.py","machine/architecture-hardening/isolation.json","docs/research/offline-storage-experiment.md"))
    return {str(p):sha(p) for p in files}
def environment():
    exact={"JAVA_TOOL_OPTIONS","_JAVA_OPTIONS","JDK_JAVA_OPTIONS","JDK_JAVAC_OPTIONS","CLASSPATH","JAVA_OPTS","CLIPPY_ARGS","CLIPPY_CONF_DIR","CL","_CL_","LINK","_LINK_","CFLAGS","CXXFLAGS","CPPFLAGS","LDFLAGS"}
    blocked=sorted(k for k in os.environ if k.upper() in exact or k.upper().startswith("CARGO_") and k.upper()!="CARGO_HOME" or k.upper().startswith("RUST") and k.upper()!="RUSTUP_HOME")
    env={k:v for k,v in os.environ.items() if k not in blocked};env["RUST_BACKTRACE"]="0"
    return env,dict(removed_names=blocked,explicit={"RUST_BACKTRACE":"0"},remaining="OS compiler/linker environment inherited; not fully hermetic")

def dependencies(cache,archives):
    inventory=load((HERE/"third-party/inventory.json").read_text());require(inventory["cargo_lock_sha256"]==sha(HERE/"Cargo.lock"),"license lock drift")
    packages=[p for p in tomllib.loads((HERE/"Cargo.lock").read_text())["package"] if "source" in p];require(len(packages)==inventory["registry_package_count"]==12,"dependency count")
    for p,row in zip(packages,inventory["packages"]):
        key=p["name"]+"-"+p["version"];archive=archives/(key+".crate")
        require((p["name"],p["version"],p["checksum"])==(row["name"],row["version"],row["archive_sha256"]),"dependency identity")
        require(sha(archive)==p["checksum"],"archive drift");digest,count=source_inventory(cache/key,archive,key)
        require((digest,count)==(row["source_inventory_sha256"],row["source_file_count"]),"source cache drift")
        for license in row["licenses"]:require(sha(HERE/license["path"])==license["sha256"],"license drift")
    return inventory

class Processes:
    def __init__(self,folder,env,records):self.folder=folder;self.env=env;self.records=records;self.bytes=0
    def run(self,name,argv,*,limited=False,expect=0):
        argv=list(map(str,argv));started=time.monotonic();error=None;peak=None;timeout=False;code=None;out=b"";err=b""
        try:
            if limited:code,out,err,timeout,peak=launch(argv,cwd=HERE,env=self.env,memory_mib=512,timeout=30)
            else:
                child=subprocess.Popen(argv,cwd=HERE,env=self.env,stdin=subprocess.DEVNULL,stdout=subprocess.PIPE,stderr=subprocess.PIPE,shell=False,bufsize=0)
                buffers={"stdout":bytearray(),"stderr":bytearray()};streams={k:getattr(child,k) for k in buffers};active=set(streams);exit_time=None
                try:
                    for stream in streams.values():os.set_blocking(stream.fileno(),False)
                    while active or child.poll() is None:
                        progress=False
                        for key in tuple(active):
                            try:data=os.read(streams[key].fileno(),4096)
                            except BlockingIOError:continue
                            if not data:active.remove(key);continue
                            progress=True;room=(2<<20)-len(buffers[key]);buffers[key].extend(data[:room])
                            if len(data)>room:raise RuntimeError("compiler output cap")
                        if time.monotonic()-started>120:raise RuntimeError("compiler lifetime cap")
                        if child.poll() is not None:
                            if exit_time is None:exit_time=time.monotonic()
                            if active and time.monotonic()-exit_time>1:raise RuntimeError("inherited compiler pipe")
                        if not progress:time.sleep(.002)
                finally:
                    if child.poll() is None:child.kill()
                    child.wait(timeout=5)
                    out=bytes(buffers["stdout"]);err=bytes(buffers["stderr"]);code=child.returncode
                    for stream in streams.values():stream.close()
        except LaunchFailure as failure:
            code,out,err,timeout,peak=failure.code,failure.stdout,failure.stderr,failure.timed_out,failure.peak;error=str(failure)
        except Exception as failure:error=type(failure).__name__+": "+str(failure)
        remaining=max(0,(32<<20)-self.bytes)
        if len(out)+len(err)>remaining:
            error=(error+"; " if error else "")+"campaign raw-output byte budget; retained prefix truncated"
            out=out[:remaining];err=err[:max(0,remaining-len(out))]
        self.bytes+=len(out)+len(err)
        (self.folder/(name+".stdout.txt")).write_bytes(out);(self.folder/(name+".stderr.txt")).write_bytes(err)
        record=dict(name=name,argv=argv,exit=code,expected_exit=expect,resource_error=error,timed_out=timeout,elapsed_seconds=time.monotonic()-started,windows_job_peak_process_committed_bytes=peak,job_memory_limit_mib=512 if limited else None,stdout_sha256=sha(self.folder/(name+".stdout.txt")),stderr_sha256=sha(self.folder/(name+".stderr.txt")))
        self.records.append(record);write(self.folder/(name+".process.json"),record)
        require(error is None and not timeout and code==expect,"process failed: "+name)
        return out.decode("utf-8"),err.decode("utf-8")

def campaign(args,folder,receipt):
    guard=inspect(ROOT);receipt["guard_before"]=guard;require(guard["status"]=="PASS","isolation before")
    env,policy=environment();receipt["environment_policy"]=policy;records=[];receipt["processes"]=records;process=Processes(folder,env,records)
    before=sources();receipt["source_hashes"]=before;inventory=dependencies(args.cache,args.crate_cache);receipt["dependency_inventory_sha256"]=sha(HERE/"third-party/inventory.json")
    cargo=args.cargo.resolve(strict=True);rustc=args.rustc.resolve(strict=True);tools={str(p):sha(p) for p in (cargo,rustc,Path(sys.executable))}
    sysroot,_=process.run("sysroot",[rustc,"--print","sysroot"]);sysroot=Path(sysroot.strip());bin_path=sysroot/"bin";suffix=".exe";version,_=process.run("rust-version",[rustc,"-vV"]);host=next(s.removeprefix("host: ") for s in version.splitlines() if s.startswith("host: "))
    for p in [*bin_path.iterdir(),*(sysroot/"lib/rustlib"/host/"lib").rglob("*")]:
        if p.is_file():tools[str(p)]=sha(p)
    cargo_home=args.cache.resolve().parents[2];require(args.crate_cache.resolve().parents[2]==cargo_home,"cache homes")
    env.update(RUSTC=str(bin_path/"rustc.exe"),CARGO_HOME=str(cargo_home),PATH=str(bin_path)+os.pathsep+env.get("PATH",env.get("Path","")),OFFLINE_STORAGE_TEST_DIR=str(folder/"unit-files"))
    policy["explicit"].update(RUSTC=env["RUSTC"],CARGO_HOME=env["CARGO_HOME"],PATH_PREPEND=str(bin_path),OFFLINE_STORAGE_TEST_DIR=env["OFFLINE_STORAGE_TEST_DIR"]);receipt["tools"]=tools
    configpaths={cargo_home/"config",cargo_home/"config.toml"}
    for parent in (HERE,*HERE.parents):configpaths.update((parent/".cargo/config",parent/".cargo/config.toml",parent/"rust-toolchain",parent/"rust-toolchain.toml"))
    configs={str(p):sha(p) if p.is_file() else None for p in configpaths};receipt["inherited_configs"]=configs
    raw,_=process.run("metadata",[cargo,"metadata","--locked","--offline","--format-version","1"]);metadata=load(raw);require(Path(metadata["workspace_root"]).resolve()==HERE,"workspace isolation")
    actual={(p["name"],p["version"],str(Path(p["manifest_path"]).resolve())) for p in metadata["packages"] if p["source"]};wanted={(p["name"],p["version"],str((args.cache/(p["name"]+"-"+p["version"])/"Cargo.toml").resolve())) for p in inventory["packages"]};require(actual==wanted,"dependency source substitution")
    target=folder/"cargo-target";process.run("fmt",[cargo,"fmt","--","--check"])
    out,_=process.run("rust-tests",[cargo,"test","--locked","--offline","--target-dir",target]);require("12 passed; 0 failed" in out and "2 passed; 0 failed" in out,"Rust control counts")
    process.run("clippy",[cargo,"clippy","--locked","--offline","--all-targets","--target-dir",target,"--","-D","warnings"])
    process.run("release",[cargo,"build","--locked","--offline","--release","--target-dir",target]);_,err=process.run("reference-tests",[sys.executable,"-B","-m","unittest","test_reference","-v"]);require("Ran 5 tests" in err and err.rstrip().endswith("OK"),"Python controls")
    binary=target/"release/offline-storage-experiment.exe";artifact=sha(binary);receipt["artifact"]={"path":str(binary),"sha256":artifact};inputs={};samples=[];receipt["samples"]=samples;controls=[];receipt["controls"]=controls
    for count in (8192,65536):
        data=generate(count);source=folder/f"source-{count}.bin";source.write_bytes(data);inputs[str(source)]=sha(source);reference=expected(data)
        for repetition in range(args.samples):
            modes=("buffered","mmap") if repetition%2==0 else ("mmap","buffered")
            for mode in modes:
                name=f"sample-{count}-{repetition}-{mode}";destination=folder/name;session=uuid.uuid4().hex;challenge=secrets.token_hex(32)
                out,err=process.run(name,[binary,mode,source,destination,session,challenge,7],limited=True);require(not err,"unexpected sample stderr");row=validate(out,reference,mode,session,challenge);row["repetition"]=repetition;row["process_name"]=name;samples.append(row)
                require(sum(p.stat().st_size for p in folder.rglob("*") if p.is_file())<2<<30,"campaign retained file budget")
    small=folder/"control-source.bin";data=generate(128);small.write_bytes(data);inputs[str(small)]=sha(small);reference=expected(data);session=uuid.uuid4().hex;challenge=secrets.token_hex(32);crashfolder=folder/"crash-control"
    out,err=process.run("crash-uncommitted",[binary,"crash-uncommitted",small,crashfolder,session,challenge,7],limited=True,expect=71);require(not err and load(out)==dict(schema="OFFLINE_STORAGE_CRASH_CONTROL_V1",session=session,challenge=challenge,stage="active_uncommitted_transaction"),"crash stage proof")
    session=uuid.uuid4().hex;challenge=secrets.token_hex(32);out,err=process.run("recover",[binary,"recover",small,crashfolder,session,challenge,7],limited=True);require(not err,"recovery stderr");validate(out,reference,"recover",session,challenge);controls.append(dict(name="process-exit-with-uncommitted-transaction",status="PASS",scope="explicit exit(71) with no Rust destructors, then fresh process reopen; not power failure or random crash point"))
    duplicate=bytearray(data);duplicate[96:104]=duplicate[64:72]
    for name,content,epoch in (("bad-magic",b"BADMAGIC"+data[8:],7),("truncated",data[:-1],7),("trailing",data+b"x",7),("duplicate-key",duplicate,7),("wrong-source-epoch",data,8)):
        source=folder/(name+".bin");source.write_bytes(content);inputs[str(source)]=sha(source)
        out,err=process.run(name,[binary,"mmap",source,folder/(name+"-output"),uuid.uuid4().hex,secrets.token_hex(32),epoch],limited=True,expect=2);require(not out and err.startswith("CONTROLLED_ERROR:"),"negative control output");controls.append(dict(name=name,status="EXPECTED_REJECTION"))
    receipt["inputs"]=inputs;require(all(sha(Path(p))==h for p,h in inputs.items()),"source input drift")
    require(sources()==before,"campaign source drift");require(all(sha(Path(p))==h for p,h in tools.items()),"tool drift");require(all((sha(Path(p)) if Path(p).is_file() else None)==h for p,h in configs.items()),"config drift");require(sha(binary)==artifact,"artifact drift");dependencies(args.cache,args.crate_cache)
    summary={}
    for count in (8192,65536):
        for mode in ("buffered","mmap"):
            selected=[s for s in samples if s["records"]==count and s["mode"]==mode]
            summary[f"{count}-{mode}"]={phase:dict(median=statistics.median(s["timings_ns"][phase] for s in selected),minimum=min(s["timings_ns"][phase] for s in selected),maximum=max(s["timings_ns"][phase] for s in selected)) for phase in PHASES}
    receipt.update(status="PASS",summary_ns=summary,rust_tests=12,compile_fail_tests=2,python_tests=5,semantic_samples=len(samples),snapshot_data_limit_bytes=64+131072*32,sidecar_file_limit_bytes=64<<20,retained_file_bytes=sum(p.stat().st_size for p in folder.rglob("*") if p.is_file()),measurement_scope="generated fixtures; warm page cache after copy/hash; creation and verification included; no Minecraft or server performance claim")

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument("--cargo",type=Path,default=Path(r"C:\Users\Admin\.cargo\bin\cargo.exe"));p.add_argument("--rustc",type=Path,default=Path(r"C:\Users\Admin\.cargo\bin\rustc.exe"));p.add_argument("--cache",type=Path,default=Path(r"C:\Users\Admin\.cargo\registry\src\index.crates.io-1949cf8c6b5b557f"));p.add_argument("--crate-cache",type=Path,default=Path(r"C:\Users\Admin\.cargo\registry\cache\index.crates.io-1949cf8c6b5b557f"));p.add_argument("--samples",type=int,choices=range(1,6),default=5);p.add_argument("--output",type=Path);a=p.parse_args()
    base=(ROOT/"target/offline-storage-experiment").resolve();folder=(a.output or base/uuid.uuid4().hex).resolve()
    if os.name!="nt" or not folder.is_relative_to(base) or folder==base:p.error("Windows only; new output must be under isolated target/offline-storage-experiment")
    folder.mkdir(parents=True,exist_ok=False);started=time.monotonic();receipt=dict(schema="OFFLINE_STORAGE_CAMPAIGN_V1",status="FAIL",production_authority=False,output=str(folder),host=platform.platform(),cpu=os.environ.get("PROCESSOR_IDENTIFIER"),samples_per_method_and_size=a.samples,measurement_concurrency="serial subprocesses; other team benchmark windows coordinated outside harness")
    try:campaign(a,folder,receipt)
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
