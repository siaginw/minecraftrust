"""Bounded campaign: actual closed-loader witnesses, adversarial models, retained Clean blockers."""
import argparse
from collections import Counter
import json
import os
import re
from pathlib import Path
import shutil
import sys
import time
from types import SimpleNamespace
import uuid
import zipfile

from common import ROOT,HERE,sha,strict,write,read_json_bound,write_json_bound,process,digest,sources,output_directory,ensure_inventory,inspect,capture
from frames import need
from prepare import prepare
from structural import prove
from test_relation import fixture_hooks


def actual_fixture_evidence(folder,output,java,classpath,bundle,entry_post,rt,vm_hash,source_hash,policy,expected_request_pin):
    req,request_hash=read_json_bound(folder/"request.json",4*1024*1024,expected_request_pin)
    logs=output/"logs"
    types=folder/"types.txt";types.write_text("".join(n+"\n" for n in req["types"]),encoding="utf-8")
    questions=folder/"queries.tsv";questions.write_text("".join(q["source"]+"\t"+q["target"]+"\n" for q in req["assignability"]),encoding="utf-8")
    input_pins={str(p):sha(p) for p in (types,questions,bundle,policy,folder/"request.json")}
    witness=dict(schema="QUALIFIED_FRAME_WITNESS_V1",session=req["session"],challenge=req["challenge"],request_sha256=request_hash,
                 collector_sources_sha256=source_hash,vm_inventory_sha256=vm_hash,phases={},loader_pairs=[],scope={"model":"OFFLINE_BYTECODE_EXECUTION_V1"})
    phase_records={}
    for phase,entry in (("pre","pre.class"),("post",entry_post)):
        text=process([java,"-Xverify:all","-cp",classpath,"FixtureWitnessPhase",phase,req["session"],bundle,entry,req["classes"][0][phase+"_sha256"],types,questions,rt,vm_hash,policy],logs,folder.name+"-actual-"+phase)
        need(len(text.splitlines())==1,"phase output count")
        row=strict(text);need(set(row)=={"schema","phase","session","data","observed"} and row["schema"]=="CLOSED_FIXTURE_PHASE_V1" and row["phase"]==phase and row["session"]==req["session"],"phase schema/binding")
        measured=row["observed"]
        need(all(measured[k] is True for k in ("uninitialized_before","uninitialized_after","uninitialized_after_hierarchy")),"actual initialization state")
        need(measured["initialization_state_probe"]=="PINNED_SUN_MISC_UNSAFE_SHOULD_BE_INITIALIZED" and measured["input_arguments"]==["-Xverify:all"],"actual fixture VM policy")
        need(measured["runtime_version"]=="1.8.0_504-b01" and measured["vm_version"]=="25.504-b01","pinned fixture VM")
        need(measured["bundle_sha256"]==sha(bundle) and measured["rt_jar_sha256"]==sha(rt),"actual runtime/artifact bytes")
        need(all(name=="fixture.Writer" or name.startswith("java.") or name=="missing.Unknown" for name in measured["loader_requests"]),"fixture loader performed extra non-bootstrap definition/resolution")
        phase_records[phase]=row;witness["phases"][phase]=row["data"]
    observations_pin=write_json_bound(folder/"actual-phase-observations.json",phase_records)
    witness["loader_pairs"]=[dict(pre=req["session"]+":pre:"+name,post=req["session"]+":post:"+name) for name in ("bootstrap","fixture")]
    # These are bounded fixture policies established by this pinned collector's
    # closed loader implementation and actual command/identity observations.
    # They are never accepted as a policy for LaunchClassLoader or a real modpack.
    inventory=digest(input_pins)
    effect_evidence=dict(schema="CLOSED_FIXTURE_SCOPE_EVIDENCE_V1",scope="ONLY_HAND_AUTHORED_fixture.Writer",
                         policy_sha256=sha(policy),collector_sources_sha256=source_hash,vm_inventory_sha256=vm_hash,
                         actual_observations_sha256=observations_pin,input_inventory_sha256=inventory,
                         observer_assurance="no agents; closed fixture loader; no downstream transforms or bytecode observers; fixture target never initialized",
                         loader_assurance="exact bytes plus bootstrap-only delegation; explicit missing-type lookup retained; no target method/constructor invocation",
                         production_authority=False,actual_forge_integration=False)
    effect_pin=write_json_bound(folder/"fixture-scope-evidence.json",effect_evidence)
    accepted_policy=dict(policy_sha256=sha(policy),inventory_sha256=inventory,evidence_sha256=effect_pin)
    for name in ("observers","loader_effects"):witness["scope"][name]=dict(status="CLOSED",**accepted_policy)
    witness_pin=write_json_bound(folder/"witness.json",witness)
    ensure_inventory(input_pins)
    receipt=dict(schema="QUALIFIED_FRAME_COLLECTOR_RECEIPT_V1",session=req["session"],challenge=req["challenge"],request_sha256=request_hash,
                 witness_sha256=witness_pin,collector_sources_sha256=source_hash,vm_inventory_sha256=vm_hash,
                 exit_code=0,stderr_sha256="e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",fresh_processes=True,
                 inputs_before_sha256=inventory,inputs_after_sha256=digest({p:sha(p) for p in input_pins}))
    receipt_pin=write_json_bound(folder/"collector-receipt.json",receipt)
    trust=dict(schema="QUALIFIED_FRAME_TRUST_V1",session=req["session"],challenge=req["challenge"],request_sha256=request_hash,witness_sha256=witness_pin,
               collector_receipt_sha256=receipt_pin,collector_sources_sha256=source_hash,vm_inventory_sha256=vm_hash,
               observer_policy=accepted_policy,loader_effect_policy=accepted_policy)
    trust_pin=write_json_bound(folder/"trust.json",trust)
    ensure_inventory({str(folder/name):pin for name,pin in (("actual-phase-observations.json",observations_pin),("fixture-scope-evidence.json",effect_pin),("witness.json",witness_pin),("collector-receipt.json",receipt_pin),("trust.json",trust_pin))})
    return witness


def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument("--output",type=Path)
    p.add_argument("--java-home",type=Path,default=Path("D:/rustcraft-toolchains/temurin8/jdk8u504-b01"))
    p.add_argument("--asm",type=Path,default=Path("D:/rustcraft-runtime-targets/clean-forge-2860/server/libraries/org/ow2/asm/asm-debug-all/5.2/asm-debug-all-5.2.jar"))
    p.add_argument("--rust-parser",type=Path,default=ROOT/"tools/classfile-crosscheck/target/debug/rustcraft-classfile-crosscheck.exe")
    a=p.parse_args();output=output_directory(a.output or ROOT/"target/qualified-frame-relation"/uuid.uuid4().hex)
    receipt=dict(schema="QUALIFIED_FRAME_CAMPAIGN_V1",status="FAIL",production_authority=False,actual_forge_integration=False,
                 model_tests_cannot_qualify_runtime=True,output=str(output),placement_gate_changed=False)
    started=time.monotonic()
    try:
        receipt["guard_before"]=inspect(ROOT);need(receipt["guard_before"]["status"]=="PASS","isolation before")
        source_pins=sources();source_pins.update({str(ROOT/"tools/writer-placement-v2/PlacementFixtures.java"):sha(ROOT/"tools/writer-placement-v2/PlacementFixtures.java"),str(ROOT/"tools/bridge/src/com/rustcraft/coremod/CanonicalClassIdentityV2.java"):sha(ROOT/"tools/bridge/src/com/rustcraft/coremod/CanonicalClassIdentityV2.java")})
        tools=[a.java_home/p for p in ("bin/java.exe","bin/javac.exe","release","jre/lib/rt.jar","lib/tools.jar")]+list((a.java_home/"jre/bin").rglob("*.dll"))
        vm_pins={str(p):sha(p) for p in tools};tools += [a.asm,a.rust_parser,Path(sys.executable)]
        tool_pins={str(p):sha(p) for p in tools};receipt.update(source_hashes=source_pins,tool_hashes=tool_pins)
        # Pin the reviewed installed VM, not only a runtime version string.
        provenance_path=ROOT/"tools/hotspot-verification-proof/primary-source-provenance.json"
        provenance,_=read_json_bound(provenance_path,2*1024*1024,source_pins[str(provenance_path)])
        need(sha(a.java_home/"release")==provenance["local_release_sha256"],"reviewed VM source lead changed")
        new_provenance,_=read_json_bound(HERE/"primary-source-provenance.json",2*1024*1024,source_pins[str(HERE/"primary-source-provenance.json")])
        primary_pins={row["evidence_file"]:row["sha256"] for record in (provenance,new_provenance) for row in record["sources"]}
        ensure_inventory(primary_pins);receipt["primary_source_hashes"]=primary_pins
        logs=output/"logs";logs.mkdir();classes=output/"classes";classes.mkdir()
        java=a.java_home/"bin/java.exe";javac=a.java_home/"bin/javac.exe";cp=str(classes)+os.pathsep+str(a.asm.resolve())
        process([javac,"-cp",a.asm,"-d",classes,HERE/"FrameFixtures.java",HERE/"FixtureWitnessPhase.java",ROOT/"tools/writer-placement-v2/PlacementFixtures.java",ROOT/"tools/bridge/src/com/rustcraft/coremod/CanonicalClassIdentityV2.java"],logs,"compile")
        compiled={str(p):sha(p) for p in classes.rglob("*.class")};receipt["compiled_class_hashes"]=compiled
        generated=output/"legacy-fixtures";need(process([java,"-cp",cp,"PlacementFixtures",generated],logs,"legacy-generate").strip()=="FIXTURES 29","legacy fixture count")
        fixtures=capture(sorted(generated.glob("*.class")),output/"fixtures",str(java),cp,str(a.rust_parser),False)
        generated_frames=output/"frame-fixtures";need(process([java,"-cp",cp,"FrameFixtures",generated_frames],logs,"frame-generate").strip()=="FRAME_FIXTURES 3","frame fixture count")
        frame_rows=capture(sorted(generated_frames.glob("*.class")),output/"frame-parser",str(java),cp,str(a.rust_parser),False)
        bundle=output/"fixture-bundle.zip"
        with zipfile.ZipFile(bundle,"x",compression=zipfile.ZIP_STORED) as archive:
            for name in ("pre.class","post.class","missing-type.class"):archive.write(generated_frames/name,name)
        fixture_plan=output/"fixture-plan.json";write(fixture_plan,{"required_hooks":fixture_hooks()})
        policy=output/"fixture-policy.json";write(policy,dict(schema="CLOSED_FIXTURE_POLICY_V1",target="fixture/Writer",allowed_delegation="java.* bootstrap only",
            target_definition_artifact_sha256=sha(bundle),observers="no agents/downstream transformers/target execution",scope="OFFLINE_FIXTURE_ONLY",production_authority=False))
        roots={}
        for label,file in (("pre","pre.class"),("post","post.class"),("missing","missing-type.class")):
            root=output/(label+"-tree");dest=root/"fixture/Writer.class";dest.parent.mkdir(parents=True);shutil.copyfile(generated_frames/file,dest);roots[label]=root
        profiles=[];parser_agreements=len(fixtures)+len(frame_rows)
        for label,post in (("positive","post"),("missing-type","missing")):
            args=SimpleNamespace(output=output/label,pre=roots["pre"],post=roots[post],plan=fixture_plan,java=java,classpath=cp,rust_parser=a.rust_parser,
                                 session=None,challenge=None,profile_sha256=sha(policy),manifest_sha256=sha(bundle),observation_sha256=sha(output/"frame-parser/classes.json"))
            result=prepare(args);need(result["status"]=="INCOMPLETE" and result["structural_status"]=="STRUCTURALLY_ELIGIBLE","actual fixture structural proof: "+str(result.get("error")))
            parser_agreements += result["independent_parser_agreements"]
            folder=output/label
            actual_fixture_evidence(folder,output,java,str(classes),bundle,"post.class" if post=="post" else "missing-type.class",a.java_home/"jre/lib/rt.jar",digest(vm_pins),digest(source_pins),policy,result["request_sha256"])
            command=[sys.executable,"-B",HERE/"evaluate.py","--prepared",folder/"prepared.json","--prepared-sha256",sha(folder/"prepared.json"),"--witness",folder/"witness.json","--trust",folder/"trust.json","--collector-receipt",folder/"collector-receipt.json","--out",folder/"result.json"]
            observed=strict(process(command,logs,"actual-evaluate-"+label));expected="PASS" if label=="positive" else "INCOMPLETE"
            need(observed["status"]==expected,"actual fixture result classification")
            profiles.append(dict(case=label,status=observed["status"],receipt_sha256=sha(folder/"result.json")))
        retained=ROOT/"target/architecture-hardening/h23-plan-clean-regressions"
        dataset=output/"retained-clean-dataset.json";write(dataset,dict(schema="RETAINED_DATASET_ANALYSIS_PROFILE_V1",live_qualification=False,
            source_scope="already retained Clean pre/post buffers; no new game execution",pre=str(retained/"baseline/qualification/transformed"),post=str(retained/"diagnostic/live-transformer-jvm/transformed")))
        clean=prepare(SimpleNamespace(output=output/"clean",pre=retained/"baseline/qualification/transformed",post=retained/"diagnostic/live-transformer-jvm/transformed",
            plan=ROOT/"tools/live-capture/required-live-writer-hooks.json",java=java,classpath=cp,rust_parser=a.rust_parser,session=None,challenge=None,
            profile_sha256=sha(dataset),manifest_sha256=sha(dataset),observation_sha256=sha(ROOT/"machine/architecture-hardening/h23-placement-evidence/root-reviewed-01/clean/audit.json")))
        need(clean["status"]=="INCOMPLETE","Clean structural audit failed: "+str(clean.get("error")))
        process([sys.executable,"-B",HERE/"evaluate.py","--prepared",output/"clean/prepared.json","--prepared-sha256",sha(output/"clean/prepared.json"),"--out",output/"clean/result.json"],logs,"clean-no-witness")
        test_output=process([sys.executable,"-B",HERE/"test_relation.py",output],logs,"tests")
        test_count=re.search(r"Ran (\d+) tests in",test_output);need(test_count and int(test_count.group(1))==31,"unit test cardinality")
        binding_output=process([sys.executable,"-B",HERE/"test_file_binding.py",output/"binding-controls"],logs,"binding-tests")
        binding_count=re.search(r"Ran (\d+) tests in",binding_output);need(binding_count and int(binding_count.group(1))==8,"binding control cardinality")
        structure=strict((output/"clean/structure.json").read_bytes())
        receipt.update(status="PASS",actual_closed_fixture_results=profiles,actual_java_verification_processes=4,
                       independent_parser_agreements=parser_agreements+clean["independent_parser_agreements"],python_tests=int(test_count.group(1))+int(binding_count.group(1)),same_buffer_binding_tests=int(binding_count.group(1)),
                       clean_structural_status=structure["status"],clean_result="INCOMPLETE",
                       clean_finite_blocker_counts=dict(Counter(b["kind"] for r in structure["records"] for m in r["methods"] for b in m["blockers"])),
                       clean_required_types=len(strict((output/"clean/request.json").read_bytes())["types"]),
                       clean_assignability_queries_per_phase=len(strict((output/"clean/request.json").read_bytes())["assignability"]))
        ensure_inventory({**source_pins,**tool_pins,**compiled,**primary_pins})
    except (ValueError,KeyError,IndexError,TypeError,OSError) as error:receipt.update(status="FAIL",error=type(error).__name__+": "+str(error))
    finally:
        try:
            receipt["guard_after"]=inspect(ROOT);need(receipt["guard_after"]["status"]=="PASS","isolation after")
        except (ValueError,OSError) as error:receipt.update(status="FAIL",error=str(error))
        receipt["elapsed_seconds"]=time.monotonic()-started
        receipt["retained_bytes"]=sum(p.stat().st_size for p in output.rglob("*") if p.is_file())
        if receipt["retained_bytes"]>192*1024*1024:receipt.update(status="FAIL",error="campaign byte budget")
        write(output/"campaign.json",receipt)
    print(json.dumps(dict(status=receipt["status"],receipt=str(output/"campaign.json"),sha256=sha(output/"campaign.json"),error=receipt.get("error"))))
    return 0 if receipt["status"]=="PASS" else 1


if __name__=="__main__":raise SystemExit(main())
