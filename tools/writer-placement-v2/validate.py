"""Pinned QualificationEngine adapter for independent offline placement proof.

The plan supplies required sites and precise source BCIs, not expected post bytes.
All identities and parser facts are recomputed from the requested class files.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import sys

from capture import ROOT, capture, tool_inventory
from io_utils import limited, sha, strict, write
from validator import Failure, need, verify

MAX_CLASS = 8 * 1024 * 1024
MAX_TOTAL = 64 * 1024 * 1024


def inside_target(path):
    path = Path(path).resolve()
    need(path.is_relative_to(ROOT / "target"), "output must remain under isolated checkout target")
    return path


def load_plan(path):
    raw = limited(path, 2 * 1024 * 1024)
    plan = strict(raw)
    need(isinstance(plan, dict) and isinstance(plan.get("required_hooks"), list), "plan hook schema")
    hooks = plan["required_hooks"]
    need(0 < len(hooks) <= 256 and len({h["id"] for h in hooks}) == len(hooks), "plan hook bound/duplicates")
    for h in hooks:
        need(set(("id","class","method","descriptor","hook_type","fingerprint")) <= h.keys(), "missing hook proof specification")
        need(re.fullmatch(r"[A-Za-z_$][A-Za-z0-9_$]*(?:[./][A-Za-z_$][A-Za-z0-9_$]*)*", h["class"]) is not None, "invalid class name")
    return hooks


def copy_inputs(paths, out):
    out.mkdir(parents=True, exist_ok=False)
    inventory, copied, total = {}, [], 0
    need(0 < len(paths) <= 256, "class count bound")
    need(len({str(Path(p).resolve()) for p in paths}) == len(paths), "aliased class inputs")
    for index, value in enumerate(paths):
        path = Path(value)
        need(path.is_absolute() and path.is_file(), "class path must be absolute existing file")
        with path.open("rb") as handle:
            raw = handle.read(MAX_CLASS + 1)
        total += len(raw)
        need(0 < len(raw) <= MAX_CLASS and total <= MAX_TOTAL, "class byte budget")
        pin = hashlib.sha256(raw).hexdigest()
        need(sha(path) == pin, "input changed during immutable copy")
        dest = out / (f"{index:04}.bin")
        dest.write_bytes(raw)
        inventory[str(path)] = pin
        copied.append(dest)
    return inventory, copied


def ensure_inventory(inventory):
    need(all(Path(p).is_file() and sha(p) == pin for p,pin in inventory.items()), "input/tool/source changed during proof")


def source_inventory():
    return {str(p):sha(p) for p in sorted(Path(__file__).parent.rglob("*")) if p.is_file() and "__pycache__" not in p.parts}


def audit_files(pre_paths, post_paths, hooks, folder, java, classpath, rust_parser):
    tools, sources = tool_inventory(java, classpath, rust_parser), source_inventory()
    pre_inventory, pre_copy = copy_inputs(pre_paths, folder / "pre-inputs")
    post_inventory, post_copy = copy_inputs(post_paths, folder / "post-inputs")
    pre = capture(pre_copy, folder / "pre", java, classpath, rust_parser)
    post = capture(post_copy, folder / "post", java, classpath, rust_parser)
    report = verify(pre, post, hooks)
    ensure_inventory({**tools, **sources, **pre_inventory, **post_inventory})
    report.update(tool_hashes=tools, validator_source_hashes=sources,
                  pre_input_hashes=pre_inventory, post_input_hashes=post_inventory,
                  independent_parser_agreements=len(pre)+len(post),
                  environment_policy="JAVA_TOOL_OPTIONS/_JAVA_OPTIONS/JDK_JAVA_OPTIONS/CLASSPATH removed; explicit Java and classpath; no transformer or game execution")
    write(folder / "audit.json", report)
    return report, pre, post


def request_schema(request, hooks):
    keys = {"schema","session","challenge","acquisition_request_sha256","observation_sha256","profile_sha256","manifest_sha256","classes","pre_classes","required_sites"}
    need(isinstance(request, dict) and set(request) == keys, "placement request schema")
    need(request["schema"] == "RUSTCRAFT_PLACEMENT_REQUEST_V2", "placement request version")
    for name,length in (("session",32),("challenge",64),("acquisition_request_sha256",64),("observation_sha256",64),("profile_sha256",64),("manifest_sha256",64)):
        need(isinstance(request[name],str) and re.fullmatch("[0-9a-f]{%d}"%length,request[name]), "invalid request binding "+name)
    expected = {(h["id"],h["class"].replace(".","/"),h["method"],h["descriptor"]) for h in hooks}
    sites = request["required_sites"]
    need(isinstance(sites,list) and len(sites)==len(expected), "required site inventory count")
    actual = set()
    for site in sites:
        need(isinstance(site,dict) and set(site)=={"id","class","method","descriptor","required_calls"}, "site schema")
        actual.add((site["id"],site["class"],site["method"],site["descriptor"]))
    need(actual==expected,"required sites differ from pinned proof plan")
    names = {h[1] for h in expected}
    for section in ("pre_classes","classes"):
        need(isinstance(request[section],dict) and set(request[section])==names,"request class inventory")
        for name,row in request[section].items():
            need(isinstance(row,dict) and set(row)=={"raw_sha256","semantic_sha256","declaration_order_sha256","methods","file"},"request class fact schema")
            for key in ("raw_sha256","semantic_sha256","declaration_order_sha256"):
                need(isinstance(row[key],str) and re.fullmatch("[0-9a-f]{64}",row[key]),"bad class identity pin")
            need(isinstance(row["file"],str) and Path(row["file"]).is_absolute(),"class path must be absolute")


def compare_request(request, pre, post):
    for section,observed in (("pre_classes",pre),("classes",post)):
        for name,row in request[section].items():
            actual = observed[name]
            need(actual["receipt"][2:]==[row["semantic_sha256"],row["declaration_order_sha256"],row["raw_sha256"]],"requested identity differs from exact class bytes")
            need(actual["dump"][16]==row["methods"],"requested method facts differ from actual bytes")
    for site in request["required_sites"]:
        m = next(m for m in post[site["class"]]["dump"][16] if m[:2]==[site["method"],site["descriptor"]])
        need(isinstance(site["required_calls"],list) and site["required_calls"],"required calls missing")
        for c in site["required_calls"]:
            need(isinstance(c,dict) and set(c)=={"opcode","owner","name","descriptor","count"},"call schema")
            need(type(c["count"]) is int and c["count"]>0,"call count type")
            count=sum(n[0]==c["opcode"] and n[1][:3]==[c["owner"],c["name"],c["descriptor"]] for n in m[12])
            need(count==c["count"],"required call inventory differs from actual bytecode")


def main():
    p=argparse.ArgumentParser(description=__doc__)
    for name in ("request","out","plan","java","classpath","rust-parser"):
        p.add_argument("--"+name,required=True)
    a=p.parse_args()
    output=inside_target(a.out)
    need(not output.exists(),"witness output must be fresh")
    folder=output.parent/(output.stem+"-audit")
    folder.mkdir(parents=True,exist_ok=False)
    request_path=Path(a.request).resolve()
    request_hash=sha(request_path)
    plan_hash=sha(a.plan)
    try:
        request=strict(limited(request_path,32*1024*1024))
    except (ValueError,OSError) as error:
        write(folder/"audit.json",dict(status="FAIL",error=str(error),request_sha256=request_hash,production_authority=False))
        raise
    report={"status":"FAIL","error":"request not validated","production_authority":False}
    try:
        hooks=load_plan(a.plan)
        request_schema(request,hooks)
        pre_paths=[row["file"] for _,row in sorted(request["pre_classes"].items())]
        post_paths=[row["file"] for _,row in sorted(request["classes"].items())]
        report,pre,post=audit_files(pre_paths,post_paths,hooks,folder,a.java,a.classpath,a.rust_parser)
        compare_request(request,pre,post)
        need(sha(request_path)==request_hash and sha(a.plan)==plan_hash,"request/plan changed")
    except (ValueError,KeyError,IndexError,TypeError,OSError,StopIteration) as error:
        report.update(status="FAIL",error=str(error))
    write(folder/"audit.json",report)
    # A valid request receives a structured non-PASS witness even if parsing or
    # proof failed; the engine then emits FAIL/INCOMPLETE, never silent success.
    need(isinstance(request,dict) and all(k in request for k in ("session","challenge","observation_sha256","required_sites")),"malformed request cannot bind witness")
    state=report["status"]
    site_fail=any(s["status"]=="FAIL" for c in report.get("records",[]) for s in c.get("sites",[]))
    checks={name:dict(status=("PASS" if state=="INCOMPLETE" and name=="anchor_order" and not site_fail else state),
                      measurements=dict(audit_file=str(folder/"audit.json"),audit_sha256=sha(folder/"audit.json"),
                                        independent_parser_agreements=report.get("independent_parser_agreements",0),
                                        required_hook_count=report.get("required_hook_count",0)))
            for name in ("anchor_order","exception_paths","undeclared_edits","pre_post_relation")}
    witness=dict(schema="RUSTCRAFT_PLACEMENT_WITNESS_V2",session=request["session"],challenge=request["challenge"],
                 request_sha256=request_hash,observation_sha256=request["observation_sha256"],
                 covered_sites=[s["id"] for s in request["required_sites"]],checks=checks)
    write(output,witness)
    print(json.dumps(dict(schema="RUSTCRAFT_VALIDATOR_ACK_V2",session=request["session"],challenge=request["challenge"],output_sha256=sha(output))))
    return 0


if __name__=="__main__":
    try:
        raise SystemExit(main())
    except (ValueError,KeyError,IndexError,TypeError,OSError) as error:
        print("CONTROLLED_ERROR: "+str(error),file=sys.stderr)
        raise SystemExit(2)
