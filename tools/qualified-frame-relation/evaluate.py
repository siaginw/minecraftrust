"""Validate a separately acquired witness; missing actual provenance remains INCOMPLETE."""
import argparse
import json
from pathlib import Path
from common import ROOT,sha,read_json_bound,write_json_bound,sources,ensure_inventory,inside_target,inspect,digest
from frames import need
from witness import evaluate


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--prepared",type=Path,required=True);parser.add_argument("--prepared-sha256",required=True)
    for name in ("witness","trust","collector-receipt"):parser.add_argument("--"+name,type=Path)
    parser.add_argument("--out",type=Path,required=True)
    args=parser.parse_args()
    output=inside_target(args.out)
    need(output.is_relative_to(ROOT/"target/qualified-frame-relation") and not output.exists(),"fresh isolated result path")
    output.parent.mkdir(parents=True,exist_ok=True)
    report=dict(schema="QUALIFIED_FRAME_RESULT_V1",status="FAIL",production_authority=False)
    try:
        before=inspect(ROOT);need(before["status"]=="PASS","isolation before")
        prepared,prepared_pin=read_json_bound(args.prepared,8*1024*1024,args.prepared_sha256)
        need(prepared["schema"]=="QUALIFIED_FRAME_PREPARED_V1" and prepared["status"]!="FAIL","failed preparation")
        ensure_inventory({**prepared["source_hashes"],**prepared["tool_hashes"],**prepared["artifact_hashes"],**prepared["original_input_hashes"]})
        need(sources()==prepared["source_hashes"],"validator source inventory changed")
        folder=args.prepared.resolve().parent
        request,request_pin=read_json_bound(folder/"request.json",4*1024*1024,prepared["artifact_hashes"][str(folder/"request.json")])
        structure,structure_pin=read_json_bound(folder/"structure.json",64*1024*1024,prepared["artifact_hashes"][str(folder/"structure.json")])
        need(request_pin==prepared["request_sha256"],"prepared request identity mismatch")
        need(request["bindings"]["validator_sources_sha256"]==digest(prepared["source_hashes"]),"request source binding")
        evidence=[];evidence_hashes=[];pins={}
        for path in (args.witness,args.trust,args.collector_receipt):
            if path is None:evidence.append(None);evidence_hashes.append(None)
            else:
                value,pin=read_json_bound(path,32*1024*1024)
                evidence.append(value);evidence_hashes.append(pin);pins[str(path.resolve())]=pin
        witness,trust,receipt=evidence
        report=evaluate(request,request_pin,structure,witness,evidence_hashes[0],trust,receipt,evidence_hashes[2])
        ensure_inventory({**prepared["artifact_hashes"],**prepared["source_hashes"],**prepared["tool_hashes"],**prepared["original_input_hashes"],**pins})
        need(sha(args.prepared)==prepared_pin,"prepared drift")
        after=inspect(ROOT);need(after["status"]=="PASS","isolation after")
        report.update(guard_before=before,guard_after=after,prepared_sha256=prepared_pin,request_sha256=request_pin,structure_sha256=structure_pin,evidence_file_hashes=pins)
    except (ValueError,KeyError,IndexError,TypeError,OSError) as error:
        report.update(status="FAIL",error=type(error).__name__+": "+str(error))
    output_pin=write_json_bound(output,report)
    print(json.dumps(dict(status=report["status"],output=str(output),sha256=output_pin,error=report.get("error"))))
    return 2 if report["status"]=="FAIL" else 0


if __name__=="__main__":raise SystemExit(main())
