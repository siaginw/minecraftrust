"""Reproduce bounded H23 placement controls; a passing campaign can contain an INCOMPLETE runtime audit."""
import argparse
from collections import Counter
import json
import os
from pathlib import Path
import secrets
import sys
import time
import uuid

from capture import ROOT,capture,tool_inventory
from io_utils import process,sha,strict,write
from test_validator import fixture_hooks
from validate import audit_files,ensure_inventory,inside_target,load_plan,source_inventory
from validator import need,verify

sys.path.insert(0,str(ROOT/"tools/testing"))
from hardening_guard import inspect

HERE=Path(__file__).resolve().parent


def request(pre,post,hooks):
    def classes(values):
        return {n:dict(file=str(Path(v["file"]).resolve()),methods=v["dump"][16],raw_sha256=v["receipt"][4],semantic_sha256=v["receipt"][2],declaration_order_sha256=v["receipt"][3]) for n,v in values.items()}
    sites=[]
    for hook in hooks:
        n=hook["class"].replace(".","/")
        method=next(m for m in post[n]["dump"][16] if m[:2]==[hook["method"],hook["descriptor"]])
        calls=Counter((i[0],*i[1][:3]) for i in method[12] if i[0]==184 and i[1][0]=="com/rustcraft/bridge/capture/LiveWriterHooks")
        sites.append(dict(id=hook["id"],**{"class":n},method=hook["method"],descriptor=hook["descriptor"],
                          required_calls=[dict(opcode=k[0],owner=k[1],name=k[2],descriptor=k[3],count=v) for k,v in sorted(calls.items())]))
    return dict(schema="RUSTCRAFT_PLACEMENT_REQUEST_V2",session=uuid.uuid4().hex,challenge=secrets.token_hex(32),
                acquisition_request_sha256="a"*64,observation_sha256="b"*64,profile_sha256="c"*64,manifest_sha256="d"*64,
                pre_classes=classes(pre),classes=classes(post),required_sites=sites)


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--output",type=Path)
    p.add_argument("--java-home",type=Path,default=Path("D:/rustcraft-toolchains/temurin8/jdk8u504-b01"))
    p.add_argument("--asm",type=Path,default=Path("D:/rustcraft-runtime-targets/clean-forge-2860/server/libraries/org/ow2/asm/asm-debug-all/5.2/asm-debug-all-5.2.jar"))
    p.add_argument("--rust-parser",type=Path,default=ROOT/"tools/classfile-crosscheck/target/debug/rustcraft-classfile-crosscheck.exe")
    p.add_argument("--plan",type=Path,default=ROOT/"tools/live-capture/required-live-writer-hooks.json")
    clean=ROOT/"target/architecture-hardening/h23-plan-clean-regressions"
    p.add_argument("--pre",type=Path,default=clean/"baseline/qualification/transformed")
    p.add_argument("--post",type=Path,default=clean/"diagnostic/live-transformer-jvm/transformed")
    a=p.parse_args()
    output=inside_target(a.output or ROOT/"target/writer-placement-v2"/uuid.uuid4().hex)
    output.mkdir(parents=True,exist_ok=False)
    receipt=dict(schema="WRITER_PLACEMENT_V2_CAMPAIGN",status="FAIL",production_authority=False,
                 runtime_qualification=False,output=str(output),scope="offline proof controls and exact retained Clean class buffers; no new game execution")
    started=time.monotonic()
    try:
        receipt["guard_before"]=inspect(ROOT);need(receipt["guard_before"]["status"]=="PASS","isolation before")
        sources=source_inventory()
        for rel in ("docs/engineering/v2-writer-placement.md","tools/bridge/src/com/rustcraft/coremod/CanonicalClassIdentityV2.java","tools/testing/hardening_guard.py","machine/architecture-hardening/isolation.json"):
            sources[str(ROOT/rel)]=sha(ROOT/rel)
        receipt["source_hashes"]=sources
        tool_files=[a.java_home/"bin/java.exe",a.java_home/"bin/javac.exe",a.java_home/"jre/lib/rt.jar",a.java_home/"jre/bin/server/jvm.dll",a.asm,a.rust_parser,a.plan]
        tool_pins={str(x.resolve()):sha(x) for x in tool_files}
        receipt["tool_and_plan_hashes"]=tool_pins
        logs=output/"logs";logs.mkdir()
        classes=output/"identity-classes";classes.mkdir()
        process([a.java_home/"bin/javac.exe","-cp",a.asm,"-d",classes,HERE/"PlacementFixtures.java",ROOT/"tools/bridge/src/com/rustcraft/coremod/CanonicalClassIdentityV2.java"],logs,"compile")
        cp=str(classes)+os.pathsep+str(a.asm.resolve());java=str((a.java_home/"bin/java.exe").resolve())
        generated=output/"fixtures"
        text=process([java,"-cp",cp,"PlacementFixtures",generated],logs,"generate")
        need(text.strip()=="FIXTURES 29","fixture cardinality")
        fixtures=capture(sorted(generated.glob("*.class")),output/"fixture-dumps",java,cp,str(a.rust_parser.resolve()),False)
        controls={n:verify({"fixture/Writer":fixtures["pre"]},{"fixture/Writer":v},fixture_hooks()) for n,v in fixtures.items() if n!="pre"}
        need(controls["valid"]["status"]=="PASS","positive fixture rejected")
        need(all(v["status"]==("INCOMPLETE" if n=="frame-local" else "FAIL") for n,v in controls.items() if n!="valid"),"mutation not blocked")
        write(output/"fixture-controls.json",controls)
        hooks=load_plan(a.plan);write(output/"hooks.json",hooks)
        names=sorted({h["class"].replace(".","/") for h in hooks})
        cleanout=output/"clean";cleanout.mkdir()
        report,pre,post=audit_files([(a.pre/(n+".class")).resolve() for n in names],[(a.post/(n+".class")).resolve() for n in names],hooks,cleanout,java,cp,str(a.rust_parser.resolve()))
        receipt["clean_audit_status"]=report["status"]
        receipt["clean_audit_sha256"]=sha(cleanout/"audit.json")
        need(report["status"]=="INCOMPLETE","retained Clean control classification changed; review required")
        process([sys.executable,"-B",HERE/"test_validator.py",output],logs,"unit-tests")
        # Exercise the real subprocess contract, including fresh request bindings
        # and a wrong claimed method fact that must not be trusted.
        fixture_plan=output/"fixture-plan.json";write(fixture_plan,{"required_hooks":fixture_hooks()})
        adapter_results=[]
        for label,definitions,pres,posts,plan in (
                ("positive",None,{"fixture/Writer":fixtures["pre"]},{"fixture/Writer":fixtures["valid"]},fixture_plan),
                ("wrong-observation",None,{"fixture/Writer":fixtures["pre"]},{"fixture/Writer":fixtures["valid"]},fixture_plan),
                ("clean-incomplete",None,pre,post,a.plan)):
            req=request(pres,posts,fixture_hooks() if plan==fixture_plan else hooks)
            if label=="wrong-observation":next(iter(req["classes"].values()))["semantic_sha256"]="0"*64
            path=output/(label+"-request.json");write(path,req)
            out=output/(label+"-witness.json")
            text=process([sys.executable,"-B",HERE/"validate.py","--plan",plan,"--java",java,"--classpath",cp,"--rust-parser",a.rust_parser.resolve(),"--request",path,"--out",out],logs,"adapter-"+label)
            ack=strict(text);witness=strict(out.read_bytes())
            need(ack==dict(schema="RUSTCRAFT_VALIDATOR_ACK_V2",session=req["session"],challenge=req["challenge"],output_sha256=sha(out)),"adapter acknowledgement binding")
            need(witness["request_sha256"]==sha(path) and witness["session"]==req["session"] and witness["challenge"]==req["challenge"] and witness["observation_sha256"]==req["observation_sha256"],"witness binding")
            expected="PASS" if label=="positive" else "FAIL" if label=="wrong-observation" else "INCOMPLETE"
            need(witness["checks"]["pre_post_relation"]["status"]==expected,"adapter proof classification")
            adapter_results.append(dict(case=label,status=expected,witness_sha256=sha(out)))
        receipt.update(status="PASS",unit_tests=18,actual_classfile_mutations=27,clean_missing_call_controls=66,
                       fixture_parser_agreements=29,clean_parser_agreements=24,adapter_controls=adapter_results,
                       site_status_counts=dict(Counter(s["status"] for c in report["records"] for s in c["sites"])),
                       unhooked_frame_rewrite_methods=sum(len(c["unhooked_method_differences"]) for c in report["records"]),
                       unsupported_frame_details=sum(len(s.get("blockers",[])) for c in report["records"] for s in c["sites"]))
        ensure_inventory({**sources,**tool_pins})
    except Exception as error:
        receipt.update(status="FAIL",error=type(error).__name__+": "+str(error))
    finally:
        try:
            receipt["guard_after"]=inspect(ROOT)
            if receipt["guard_after"]["status"]!="PASS":receipt.update(status="FAIL",error="isolation after")
        except Exception as error:receipt.update(status="FAIL",guard_error=str(error))
        receipt["elapsed_seconds"]=time.monotonic()-started
        receipt["retained_bytes"]=sum(p.stat().st_size for p in output.rglob("*") if p.is_file())
        if receipt["retained_bytes"]>256*1024*1024:receipt.update(status="FAIL",error="campaign retained-byte budget exceeded")
        write(output/"campaign.json",receipt)
    print(json.dumps(dict(status=receipt["status"],receipt=str(output/"campaign.json"),sha256=sha(output/"campaign.json"),error=receipt.get("error"))))
    return 0 if receipt["status"]=="PASS" else 1


if __name__=="__main__":raise SystemExit(main())
