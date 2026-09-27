"""Prepare fresh frame obligations from exact class buffers; does not collect game evidence."""
import argparse
import json
from pathlib import Path
import secrets
import time
import uuid

from common import ROOT,HERE,sha,write,read_json_bound,write_json_bound,read_plan_bound,digest,sources,output_directory,ensure_inventory,copy_inputs,capture,tool_inventory,inspect
from frames import need
from structural import prove,request
from witness import request_schema


def prepare(args):
    output=output_directory(args.output)
    result=dict(schema="QUALIFIED_FRAME_PREPARED_V1",status="FAIL",production_authority=False,output=str(output))
    started=time.monotonic()
    try:
        result["guard_before"]=inspect(ROOT);need(result["guard_before"]["status"]=="PASS","isolation before")
        source_pins=sources();tool_pins=tool_inventory(str(args.java),args.classpath,str(args.rust_parser))
        java_home=Path(args.java).resolve().parents[1]
        native_inputs=[java_home/"release",java_home/"jre/lib/rt.jar",*sorted((java_home/"jre/bin").rglob("*.dll"))]
        need(len(native_inputs)>2,"explicit supported Java8 runtime inventory missing")
        reviewed_path=ROOT/"tools/hotspot-verification-proof/primary-source-provenance.json"
        reviewed,_=read_json_bound(reviewed_path,2*1024*1024,source_pins[str(reviewed_path)])
        need(sha(java_home/"release")==reviewed["local_release_sha256"],"Java parser runtime source lead not reviewed")
        tool_pins.update({str(p):sha(p) for p in native_inputs})
        hooks,plan_pin=read_plan_bound(args.plan)
        names=sorted({h["class"].replace(".","/") for h in hooks})
        pre_pins,pre_copy=copy_inputs([(args.pre/(name+".class")).resolve() for name in names],output/"pre-inputs")
        post_pins,post_copy=copy_inputs([(args.post/(name+".class")).resolve() for name in names],output/"post-inputs")
        pre=capture(pre_copy,output/"pre",str(args.java),args.classpath,str(args.rust_parser))
        post=capture(post_copy,output/"post",str(args.java),args.classpath,str(args.rust_parser))
        structure=prove(pre,post,hooks)
        bound_outputs={str(output/"structure.json"):write_json_bound(output/"structure.json",structure)}
        result["structural_status"]=structure["status"]
        if structure["status"]!="FAIL":
            req=request(structure,args.session or uuid.uuid4().hex,args.challenge or secrets.token_hex(32),
                        dict(profile_sha256=args.profile_sha256,manifest_sha256=args.manifest_sha256,
                             observation_sha256=args.observation_sha256,plan_sha256=plan_pin,validator_sources_sha256=digest(source_pins)))
            request_schema(req)
            result["request_sha256"]=write_json_bound(output/"request.json",req)
            bound_outputs[str(output/"request.json")]=result["request_sha256"]
        result.update(status="FAIL" if structure["status"]=="FAIL" else "INCOMPLETE",
                      reason="exact relation failed" if structure["status"]=="FAIL" else "external actual verification/hierarchy/observer witness required",
                      source_hashes=source_pins,tool_hashes=tool_pins,plan_sha256=plan_pin,
                      original_input_hashes={**pre_pins,**post_pins},
                      artifact_hashes={**{str(p):sha(p) for p in output.rglob("*") if p.is_file()},**bound_outputs},
                      independent_parser_agreements=len(pre)+len(post),classes=len(names),hooks=len(hooks))
        ensure_inventory({**source_pins,**tool_pins,**pre_pins,**post_pins,**result["artifact_hashes"],str(args.plan.resolve()):plan_pin})
    except (ValueError,KeyError,IndexError,TypeError,OSError) as error:
        result.update(status="FAIL",error=type(error).__name__+": "+str(error))
    finally:
        try:
            result["guard_after"]=inspect(ROOT)
            need(result["guard_after"]["status"]=="PASS","isolation after")
        except (ValueError,OSError) as error:result.update(status="FAIL",error=str(error))
        result["elapsed_seconds"]=time.monotonic()-started
        result["retained_bytes"]=sum(p.stat().st_size for p in output.rglob("*") if p.is_file())
        if result["retained_bytes"]>128*1024*1024:result.update(status="FAIL",error="retained byte budget exceeded")
        write(output/"prepared.json",result)
    return result


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    for name in ("pre","post","plan","java","rust-parser","output"):parser.add_argument("--"+name,type=Path,required=True)
    for name in ("classpath","profile-sha256","manifest-sha256","observation-sha256"):parser.add_argument("--"+name,required=True)
    parser.add_argument("--session");parser.add_argument("--challenge")
    args=parser.parse_args();result=prepare(args)
    print(json.dumps(dict(status=result["status"],prepared=str(Path(result["output"])/"prepared.json"),sha256=sha(Path(result["output"])/"prepared.json"),error=result.get("error"))))
    return 2 if result["status"]=="FAIL" else 0


if __name__=="__main__":raise SystemExit(main())
