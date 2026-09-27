"""Isolated, bounded allocator experiment. Never selects a production allocator."""
from __future__ import annotations
import argparse, hashlib, importlib.util, itertools, json, math, os, platform, statistics, subprocess, sys, tarfile, time, tomllib
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
HERE=Path(__file__).resolve().parent
CARGO=Path("C:/Users/Admin/.cargo/bin/cargo.exe")
MANIFEST=HERE/"Cargo.toml"
spec=importlib.util.spec_from_file_location("h17_windows_job",ROOT/"tools/nbt-region-experiment/job.py")
job=importlib.util.module_from_spec(spec);spec.loader.exec_module(job)
import build_job
def sha(p):return hashlib.sha256(Path(p).read_bytes()).hexdigest()
def identity(p):
 p=Path(p);return {"path":str(p),"sha256":sha(p),"bytes":p.stat().st_size}
def source_inventory(local):
 paths={ROOT/"Cargo.toml",ROOT/"Cargo.lock",ROOT/"tools/nbt-region-experiment/job.py",ROOT/"tools/nbt-region-experiment/src/lossless.rs",ROOT/"docs/research/allocator-experiment.md"}
 for directory in [HERE]+local:
  for p in directory.rglob("*"):
   if p.is_file() and not any(part in ("target","__pycache__") for part in p.parts) and (p.suffix in (".rs",".py",".json",".md",".txt") or p.name in ("Cargo.toml","Cargo.lock",".gitignore")):
    paths.add(p)
 return [identity(p) for p in sorted(paths)]
def dependency_inventory(metadata,lock):
 result=[]
 for package in metadata["packages"]:
  if not package["source"]:continue
  manifest=Path(package["manifest_path"]);directory=manifest.parent
  archive=manifest.parents[3]/"cache"/manifest.parents[1].name/(package["name"]+"-"+package["version"]+".crate")
  checksum=next(p["checksum"] for p in lock["package"] if p["name"]==package["name"] and p["version"]==package["version"])
  if sha(archive)!=checksum:raise RuntimeError("dependency archive checksum")
  files={}
  with tarfile.open(archive,"r:gz") as stream:
   for item in stream.getmembers():
    if not item.isfile():continue
    name=item.name.split("/",1)[1];digest=hashlib.sha256(stream.extractfile(item).read()).hexdigest()
    if sha(directory/name)!=digest:raise RuntimeError("dependency extracted source drift: "+name)
    files[name]=digest
  actual={p.relative_to(directory).as_posix() for p in directory.rglob("*") if p.is_file() and p.name not in (".cargo-ok",".cargo-checksum.json")}
  if actual!=set(files):raise RuntimeError("dependency inventory drift")
  vcs=directory/".cargo_vcs_info.json"
  result.append({"name":package["name"],"version":package["version"],"license":package["license"],"repository":package["repository"],"archive":identity(archive),"source_dir":str(directory),"source_files":files,"vcs":json.loads(vcs.read_text()) if vcs.exists() else None})
 return result

def main():
 parser=argparse.ArgumentParser();parser.add_argument("--output",type=Path,required=True);parser.add_argument("--measure",action="store_true");parser.add_argument("--forks",type=int,default=3);parser.add_argument("--samples",type=int,default=6);parser.add_argument("--chunks",type=int,default=8)
 args=parser.parse_args()
 if not 1<=args.forks<=5 or not 1<=args.samples<=32 or not 1<=args.chunks<=32:parser.error("bounded arguments")
 output=args.output.resolve()
 if not output.is_relative_to(ROOT/"target"):parser.error("fresh output inside isolated target required")
 output.mkdir(parents=True,exist_ok=False)
 receipt={"schema":"H17_ALLOCATOR_CAMPAIGN_V1","status":"FAIL","production_allocator_changed":False,"commands":[],"processes":[],"measure":args.measure,"host":{"platform":platform.platform(),"cpu":os.environ.get("PROCESSOR_IDENTIFIER"),"logical_cpu_count":os.cpu_count(),"exclusive_host":False},"limits":{"native_process_memory_mib":256,"native_timeout_s":45,"active_processes":1,"each_output_stream_bytes":1048576},"measurement_caveats":["Authored bounded mixed-lifetime fixture schedule; not a captured server distribution","Allocation instrumentation enabled equally for every backend; overhead can perturb results","Requested Rust layout bytes are not usable size, virtual mapping, or backend internal peak","Private commit minus requested live is a process proxy, not pure allocator fragmentation","CPU FILETIME units are 100ns but actual accounting is coarser","Small sample nearest-rank tails are descriptive, not population tail estimates"]}
 env=dict(os.environ)
 excluded=[k for k in env if k.upper().startswith(("RUST","CARGO","MIMALLOC","RPMALLOC","MALLOC_","CC_","CFLAGS","CXXFLAGS","PKG_CONFIG","LIBRPMALLOC")) or k in ("CC","CXX","AR","CL","_CL_","LINK","LDFLAGS")]
 for k in excluded:env.pop(k,None)
 env["LIBRPMALLOC_NO_PKG_CONFIG"]="1";env["CARGO_BUILD_JOBS"]="2";receipt["removed_environment_keys"]=sorted(excluded);receipt["forced_environment"]={"LIBRPMALLOC_NO_PKG_CONFIG":"1","CARGO_BUILD_JOBS":"2"}
 def command(label,argv,build=None,expected=0):
  e=dict(env)
  if build:e["CARGO_TARGET_DIR"]=str(build)
  started=time.monotonic();record={"label":label,"argv":list(map(str,argv)),"cwd":str(ROOT)};receipt["commands"].append(record)
  try:code,out,err,timed,peak=build_job.launch(record["argv"],cwd=ROOT,env=e)
  except build_job.LaunchFailure as failure:code,out,err,timed,peak=failure.code,failure.stdout,failure.stderr,failure.timed_out,failure.peak;record["launch_failure"]=str(failure)
  log=output/(label+".log");log.write_bytes(out+err);record.update(returncode=code,timed_out=timed,peak_job_commit_bytes=peak,wall_seconds=time.monotonic()-started,output=identity(log))
  if code!=expected or timed or record.get("launch_failure"):raise RuntimeError(label+" unexpected return "+str(code))
  return out if label.startswith(("resolve-","metadata")) else out+err
 def native(label,argv,memory=256):
  start=time.monotonic();record={"label":label,"argv":list(map(str,argv)),"memory_mib":memory,"timeout_s":45};receipt["processes"].append(record)
  try:code,out,err,timed,peak=job.launch(record["argv"],cwd=ROOT,env=env,memory_mib=memory,timeout=45)
  except job.LaunchFailure as failure:code,out,err,timed,peak=failure.code,failure.stdout,failure.stderr,failure.timed_out,failure.peak;record["launch_failure"]=str(failure)
  op=output/(label+".stdout.log");ep=output/(label+".stderr.log");op.write_bytes(out);ep.write_bytes(err)
  record.update(returncode=code,timed_out=timed,wall_seconds=time.monotonic()-start,peak_job_commit_bytes=peak,stdout=identity(op),stderr=identity(ep))
  if code!=0 or timed or record.get("launch_failure"):raise RuntimeError(label+" failed")
  return out
 try:
  receipt["tools"]=[identity(CARGO),identity(Path(sys.executable))]
  for name in ("cargo","rustc","rustfmt","cargo-clippy","clippy-driver"):
   tool=Path(command("resolve-"+name,[CARGO.with_name("rustup.exe"),"which",name]).decode().strip());receipt["tools"].append(identity(tool))
   if name=="rustc":command("rustc-version",[tool,"-Vv"])
  compiler=Path("C:/Program Files/Microsoft Visual Studio/18/Community/VC/Tools/MSVC/14.51.36231/bin/Hostx64/x64/cl.exe")
  for name in ("cl.exe","c1.dll","c1xx.dll","c2.dll","link.exe"):
   p=compiler.with_name(name)
   if p.exists():receipt["tools"].append(identity(p))
  env["CC"]=str(compiler);env["CXX"]=str(compiler)
  sdk=Path("C:/Program Files (x86)/Windows Kits/10/Include/10.0.26100.0")
  includes=[compiler.parents[3]/"include",sdk/"ucrt",sdk/"shared",sdk/"um",sdk/"winrt"]
  if not all(p.is_dir() for p in includes):raise RuntimeError("pinned MSVC/SDK include installation unavailable")
  env["INCLUDE"]=os.pathsep.join(map(str,includes));receipt["forced_environment"].update({k:env[k] for k in ("CC","CXX","INCLUDE")})
  metadata=json.loads(command("metadata",[CARGO,"metadata","--manifest-path",MANIFEST,"--locked","--offline","--all-features","--format-version","1"]))
  local=[Path(p["manifest_path"]).parent for p in metadata["packages"] if not p["source"] and p["name"]!="allocator-experiment"]
  lock=tomllib.loads(MANIFEST.with_name("Cargo.lock").read_text());receipt["sources_before"]=source_inventory(local);receipt["dependencies"]=dependency_inventory(metadata,lock)
  control_log=command("build-job-controls",[sys.executable,HERE/"test_build_job.py"])
  if b"Ran 6 tests" not in control_log or b"\nOK" not in control_log:raise RuntimeError("build harness control count")
  command("fmt",[CARGO,"fmt","--manifest-path",MANIFEST,"--check"])
  executables={};features={"system":[],"mimalloc":["--features","mi"],"rpmalloc":["--features","rp"]};names={"system":"system","mimalloc":"mimalloc-0.1.52","rpmalloc":"rpmalloc-0.2.2"}
  for backend,flags in features.items():
   build=output/("build-"+backend)
   test_log=command("tests-"+backend,[CARGO,"test","--manifest-path",MANIFEST,"--locked","--offline",*flags,"--","--test-threads=1"],build)
   if b"test result: ok. 12 passed; 0 failed;" not in test_log or b"test result: ok. 2 passed; 0 failed;" not in test_log:raise RuntimeError("test count contract")
   command("clippy-"+backend,[CARGO,"clippy","--manifest-path",MANIFEST,"--locked","--offline","--no-deps","--all-targets",*flags,"--","-D","warnings"],build)
   command("release-"+backend,[CARGO,"build","--manifest-path",MANIFEST,"--locked","--offline","--release",*flags],build)
   executables[backend]=build/"release/allocator-experiment.exe"
  receipt["executables"]={k:identity(v) for k,v in executables.items()}
  reference=None;digest=None;receipt["correctness"]=[]
  for backend,exe in executables.items():
   path=output/("correctness-"+backend);native("correctness-"+backend,[exe,"--correctness",path])
   result=json.loads((path/"result.json").read_text())
   if result!={"schema":"H17_CORRECTNESS_V1","status":"PASS","backend":names[backend],"publications_per_mode":8,"modes":4,"digests":result.get("digests"),"independent_packet_cells":524288,"nbt_exact_preservation":True,"production_allocator_changed":False}:raise RuntimeError("correctness schema")
   if len(result["digests"])!=4 or len(set(result["digests"]))!=1:raise RuntimeError("correctness digest count")
   artifacts={p.name:sha(p) for p in sorted(path.glob("*.zlib"))}
   if len(artifacts)!=16:raise RuntimeError("artifact count")
   if reference is None:reference=artifacts;digest=result["digests"][0]
   if artifacts!=reference or result["digests"][0]!=digest:raise RuntimeError("FIRST_DIVERGENCE backend output")
   receipt["correctness"].append({"backend":backend,"result":result,"artifacts":artifacts})
   cap=json.loads(native("capacity-"+backend,[exe,"--capacity-control"],64))
   if cap!={"schema":"H17_CAPACITY_CONTROL_V1","backend":names[backend],"reservation_rejected":True,"original_preserved":True,"requested_bytes":536870912}:raise RuntimeError("capacity control")
  receipt["runs"]=[];lanes=list(itertools.product(features,("vec","arena"),("same","cross")))
  if args.measure:
   for fork in range(args.forks):
    for offset in range(len(lanes)):
     backend,scratch,delivery=lanes[(offset+fork*5)%len(lanes)];label=f"measure-{fork}-{backend}-{scratch}-{delivery}";path=output/(label+".json")
     native(label,[executables[backend],"--run",path,scratch,delivery,args.samples,args.chunks]);r=json.loads(path.read_text())
     if r["schema"]!="H17_ALLOCATOR_RUN_V1" or r["status"]!="PASS" or r["backend"]!=names[backend] or r["scratch"]!=scratch or r["delivery"]!=delivery or r["samples"]!=args.samples or r["chunks_per_batch"]!=args.chunks or r["production_allocator_changed"] is not False or len(r["rows"])!=args.samples+3:raise RuntimeError("run schema")
     if [row["sample"] for row in r["rows"]]!=list(range(-3,args.samples)):raise RuntimeError("sample sequence")
     for row in r["rows"]:
      if row["warmup"]!=(row["sample"]<0) or row["wall_ns"]<=0 or row["destructors"]!=args.chunks or row["cross_thread_final_frees"]!=(args.chunks if delivery=="cross" else 0) or row["pure_fragmentation_bytes"] is not None:raise RuntimeError("run sample contract")
      if row["alloc_before"]["requested_live_bytes"]+row["allocated_delta"]-row["freed_delta"]!=row["alloc_after"]["requested_live_bytes"]:raise RuntimeError("quiescent allocation counter conservation")
      if not row["warmup"] and row["allocated_delta"]!=row["freed_delta"]:raise RuntimeError("measured batch requested retention grows")
      if row["retained_after_producer_exit_payload_bytes"]<=0 or row["retained_requested_live_bytes"]<row["retained_after_producer_exit_payload_bytes"] or row["retained_working_set_bytes"] is None or row["retained_private_commit_bytes"] is None:raise RuntimeError("retained memory observation")
      if row["alloc_after"]["overflow"] or row["alloc_after"]["failures"]!=row["alloc_before"]["failures"] or row["arena_reserved_max"]>524288:raise RuntimeError("allocation bounds")
     r["startup_requested_retention_bytes"]=r["rows"][2]["alloc_after"]["requested_live_bytes"]-r["rows"][0]["alloc_before"]["requested_live_bytes"]
     r["startup_retention_attribution"]="UNKNOWN_NOT_ASSUMED_ALLOCATOR_FRAGMENTATION"
     receipt["runs"].append({"fork":fork,"backend":backend,"scratch":scratch,"delivery":delivery,"file":identity(path),"result":r})
   all_digests={row["digest"] for run in receipt["runs"] for row in run["result"]["rows"]}
   if len(all_digests)!=1:raise RuntimeError("FIRST_DIVERGENCE measured output")
  receipt["statistics"]=[]
  for backend,scratch,delivery in lanes:
   runs=[r for r in receipt["runs"] if (r["backend"],r["scratch"],r["delivery"])==(backend,scratch,delivery)];rows=[s for r in runs for s in r["result"]["rows"] if not s["warmup"]]
   if not rows:continue
   values=sorted(r["wall_ns"] for r in rows)
   receipt["statistics"].append({"backend":backend,"scratch":scratch,"delivery":delivery,"samples":len(rows),"batch_chunks":args.chunks,"median_wall_ns":statistics.median(values),"p95_observed_nearest_rank_ns":values[math.ceil(.95*len(values))-1],"p99_observed_nearest_rank_ns":values[math.ceil(.99*len(values))-1],"max_wall_ns":max(values),"median_allocated_bytes":statistics.median(r["allocated_delta"] for r in rows),"median_private_commit_bytes":statistics.median(r["private_commit_bytes"] for r in rows),"max_working_set_bytes":max(r["working_set_bytes"] for r in rows),"campaign_including_warmup_cpu_ns":sum(r["result"]["campaign_including_warmup_cpu_ns"] for r in runs)})
  receipt["sources_after"]=source_inventory(local)
  if receipt["sources_before"]!=receipt["sources_after"] or receipt["dependencies"]!=dependency_inventory(metadata,lock):raise RuntimeError("source drift")
  if any(identity(p["path"])!=p for p in receipt["tools"]) or any(identity(executables[k])!=v for k,v in receipt["executables"].items()):raise RuntimeError("tool/executable drift")
  receipt["status"]="PASS"
 except Exception as error:receipt["failure"]=repr(error)
 finally:
  (output/"receipt.json").write_text(json.dumps(receipt,indent=2)+"\n")
 print(json.dumps({"status":receipt["status"],"receipt":str(output/"receipt.json"),"failure":receipt.get("failure")}))
 return 0 if receipt["status"]=="PASS" else 1
if __name__=="__main__":raise SystemExit(main())
