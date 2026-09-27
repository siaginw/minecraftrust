"""Build and execute the bounded Java/Rust replay evidence campaign."""
from __future__ import annotations
import argparse
import json
import os
from pathlib import Path
import random
import subprocess
import sys
import time
import uuid
from replay import replay, file_sha
from test_replay import trace

HERE=Path(__file__).resolve().parent

def require(condition, message):
    if not condition: raise RuntimeError(message)

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java-home",type=Path,required=True)
    parser.add_argument("--cargo",type=Path,required=True)
    parser.add_argument("--output",type=Path,required=True)
    args=parser.parse_args()
    folder=args.output.resolve()/uuid.uuid4().hex;folder.mkdir(parents=True,exist_ok=False)
    suffix=".exe" if os.name=="nt" else ""
    java=(args.java_home/"bin"/("java"+suffix)).resolve(strict=True)
    javac=(args.java_home/"bin"/("javac"+suffix)).resolve(strict=True)
    cargo=args.cargo.resolve(strict=True)
    files=[HERE/"Cargo.toml",HERE/"Cargo.lock",*HERE.glob("*.py"),*HERE.glob("src/*.rs"),*HERE.glob("java/*.java")]
    sources={str(p):file_sha(p) for p in files}
    tools={str(p):file_sha(p) for p in (java,javac,cargo,Path(sys.executable))}
    commands=[];start=time.perf_counter()
    def run(command,name,environment=None):
        began=time.perf_counter()
        process=subprocess.run(list(map(str,command)),cwd=HERE,shell=False,capture_output=True,text=True,timeout=120,env=environment)
        (folder/(name+".stdout.txt")).write_text(process.stdout,encoding="utf-8")
        (folder/(name+".stderr.txt")).write_text(process.stderr,encoding="utf-8")
        commands.append(dict(name=name,argv=list(map(str,command)),exit=process.returncode,elapsed_seconds=time.perf_counter()-began,stdout_sha256=file_sha(folder/(name+".stdout.txt")),stderr_sha256=file_sha(folder/(name+".stderr.txt"))))
        if process.returncode:raise RuntimeError(name+" failed; preserved logs: "+str(folder))
    target=folder/"cargo-target";classes=folder/"classes";classes.mkdir()
    run([cargo,"fmt","--","--check"],"rust-fmt")
    run([cargo,"test","--locked","--offline","--target-dir",target],"rust-tests")
    run([cargo,"clippy","--locked","--offline","--target-dir",target,"--","-D","warnings"],"rust-clippy")
    run([cargo,"build","--release","--locked","--offline","--target-dir",target],"rust-release")
    run([javac,"-encoding","UTF-8","-d",classes,HERE/"java/ReferenceReplay.java"],"java-build")
    run([sys.executable,"-B","-m","unittest","test_replay","-v"],"protocol-tests",dict(os.environ,REPLAY_RESOURCE_TEST_OUTPUT=str(folder/"resource-controls")))
    reference=[str(java),"-cp",str(classes),"ReferenceReplay"]
    candidate=[str(target/"release"/("differential-replay-candidate"+suffix))]
    artifacts={str(p):file_sha(p) for p in [Path(candidate[0]),*classes.rglob("*.class")]}
    cases=[]
    def case(name,t,fault="none",expected="PASS",category=None,first_step=1):
        result=replay(t,reference,candidate+[fault],folder/"runs")
        ok=result["status"]==expected and (category is None or result["first_divergence"]["category"]==category)
        if expected=="DIVERGED":ok=ok and result["commands_issued"]==first_step and result["first_divergence"]["step"]==first_step
        cases.append(dict(name=name,passed=ok,expected=expected,observed=result["status"],receipt=result["output"]+"/receipt.json",receipt_sha256=file_sha(Path(result["output"])/"receipt.json")))
        if not ok:raise RuntimeError("control failed: "+name+" "+str(result))
        return result
    golden=case("integer-rng-golden",trace("RNG 256"))
    require(golden["final_reference"]["world_mutations"]==[[0,0,108]],"independent RNG mutation golden failed")
    require(golden["final_reference"]["rng_state"]==1015568748,"independent RNG state golden failed")
    error=case("controlled-error-and-retry",trace("DIV 0","DIV 2","SAVE"))
    require(error["final_reference"]["entity_state"]==[0,10],"controlled-error retry golden failed")
    schedule=case("same-time-schedule-order",trace("SCHEDULE 1 3 255","SCHEDULE 1 3 2","TICK 1"))
    require(schedule["final_reference"]["world_mutations"]==[[3,0,255],[3,255,1]],"scheduled order golden failed")
    overflow=trace("MOVE 1","SAVE");overflow["entity"][0]=2147483647
    require(case("signed-wrap-golden",overflow)["final_reference"]["entity_state"][0]==-2147483648,"signed wrap golden failed")
    both=[]
    for order in ("AB","BA"):
        both.append(case("callback-order-"+order,trace("SET 2 7",order=order))["final_reference"]["callbacks"])
    require(both[0]==list(reversed(both[1])),"callback ordering control failed")
    generator=random.Random(0x2215)
    for i in range(24):
        seq=[]
        for _ in range(32):
            seq.append(generator.choice(["MOVE "+str(generator.randint(-1000,1000)),"SET %d %d"%(generator.randrange(16),generator.randrange(256)),"SCHEDULE %d %d %d"%(generator.randint(1,8),generator.randrange(16),generator.randint(-255,255)),"TICK "+str(generator.randint(1,10)),"RNG "+str(generator.randint(1,256)),"DIV "+str(generator.randint(0,4)),"SAVE"]))
        case("seeded-mixed-"+str(i),trace(*seq,seed=generator.randrange(2**32),order="AB" if i%2 else "BA"))
    for fault,first,category in (("reconverge","MOVE 1","entity_state"),("callback-reorder","SET 1 3","callbacks"),("callback-missing","SET 1 3","callbacks"),("callback-extra","SET 1 3","callbacks"),("mutation-missing","SET 1 3","world_mutations"),("scheduled-missing","SCHEDULE 1 0 3","scheduled_ticks"),("rng","RNG 256","world_mutations"),("packet","SAVE","packet_outputs"),("save","SAVE","save_state"),("controlled-error","DIV 0","outcome")):
        case(fault,trace(first,"MOVE -1","SAVE"),fault,"DIVERGED",category)
    for fault in ("schema","session","sequence","nonzero","empty","malformed"):
        case("protocol-"+fault,trace("SAVE","SAVE"),fault,"DIVERGED","ADAPTER_PROTOCOL")
    case("initial-state-drift",trace("SAVE","SAVE"),"initial-state","DIVERGED","save_state",first_step=0)
    reconvergent=trace("MOVE 1","MOVE -1","SAVE")
    diagnostic=replay(reconvergent,reference,candidate+["reconverge"],folder/"diagnostic",compare=False)
    left,right=diagnostic["final_reference"],diagnostic["final_candidate"]
    final_equal=all(left[k]==right[k] for k in ("entity_state","scheduled_ticks","save_state","rng_state"))
    require(diagnostic["status"]=="DIAGNOSTIC_ONLY" and final_equal,"reconvergent final state diagnostic failed")
    unchanged=all(file_sha(Path(p))==value for group in (sources,tools,artifacts) for p,value in group.items())
    require(unchanged,"source/tool/artifact drift")
    resource_artifacts={p.relative_to(folder).as_posix():file_sha(p) for p in sorted((folder/"resource-controls").rglob("*")) if p.is_file()}
    receipt=dict(schema="DIFFERENTIAL_REPLAY_CAMPAIGN_V1",status="PASS",scope="REPLAY_TOY_V1_ONLY",production_authority=False,decision="LIMITED_USE",resource_control_artifacts=resource_artifacts,
                 sources=sources,tools=tools,artifacts=artifacts,commands=commands,cases=cases,case_count=len(cases),all_inputs_unchanged=unchanged,
                 independent_implementations="Java PriorityQueue/event-driven tick advancement; Rust sorted Vec/per-tick advancement; separate NBT writers",
                 reconvergent_diagnostic=dict(receipt=diagnostic["output"]+"/receipt.json",receipt_sha256=file_sha(Path(diagnostic["output"])/"receipt.json"),final_state_equal=final_equal,first_divergence_control="reconverge"),
                 elapsed_seconds=time.perf_counter()-start,limitations=["Not Minecraft/Forge semantics or live qualification","Single-threaded integer model; no floating point, IO or arbitrary callbacks","Callbacks are ordered inert observations, never foreign code execution","Elapsed times include process startup and are not production performance claims"])
    (folder/"campaign.json").write_text(json.dumps(receipt,indent=2)+"\n",encoding="utf-8")
    print(json.dumps(dict(status="PASS",cases=len(cases),receipt=str(folder/"campaign.json"),sha256=file_sha(folder/"campaign.json"))))

if __name__=="__main__":main()
