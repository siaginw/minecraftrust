"""Deterministic swap-back races over model-only temporary evidence, never runtime grants."""
from copy import deepcopy
import io
import json
from pathlib import Path
import sys
import unittest
from unittest import mock
import uuid
from contextlib import redirect_stdout

import common
import evaluate as evaluator
from common import ROOT,digest,sha,sources,write
from structural import request
from test_relation import model_evidence,h
from test_relation import fixture_hooks


class BufferBindingControls(unittest.TestCase):
    output=None

    def fixture(self):
        folder=self.output/uuid.uuid4().hex;folder.mkdir()
        pins=sources()
        structure=dict(schema="QUALIFIED_FRAME_STRUCTURE_V1",status="STRUCTURALLY_ELIGIBLE",
                       records=[dict(name="fixture/Writer",pre_sha256=h("before"),post_sha256=h("after"),types=["fixture/Writer","java/lang/Object"],questions=[],methods=[dict(method=["model","()V"],blockers=[])])])
        req=request(structure,"1"*32,"2"*64,{k:digest(pins) if k=="validator_sources_sha256" else h(k) for k in ("profile_sha256","manifest_sha256","observation_sha256","plan_sha256","validator_sources_sha256")})
        witness,trust,receipt=model_evidence(req)
        # Model helper hashes canonical JSON. Real wire binding uses the exact
        # saved serialization, as an actual collector would establish it.
        write(folder/"request.json",req);write(folder/"structure.json",structure)
        for row in (witness,trust,receipt):row["request_sha256"]=sha(folder/"request.json")
        write(folder/"witness.json",witness)
        trust["witness_sha256"]=receipt["witness_sha256"]=sha(folder/"witness.json")
        write(folder/"collector-receipt.json",receipt);trust["collector_receipt_sha256"]=sha(folder/"collector-receipt.json")
        write(folder/"trust.json",trust)
        prepared=dict(schema="QUALIFIED_FRAME_PREPARED_V1",status="INCOMPLETE",source_hashes=pins,tool_hashes={},original_input_hashes={},
                      request_sha256=sha(folder/"request.json"),artifact_hashes={str(folder/name):sha(folder/name) for name in ("request.json","structure.json")})
        write(folder/"prepared.json",prepared)
        return folder

    def invoke(self,folder,swap=None,malicious=None):
        original_limited=common.limited;swaps=[]
        def swapped_read(path,maximum):
            path=Path(path)
            if swap is not None and path.resolve()==(folder/swap).resolve():
                saved=path.read_bytes();path.write_bytes(malicious)
                try:raw=original_limited(path,maximum)
                finally:path.write_bytes(saved)
                swaps.append(str(path));return raw
            return original_limited(path,maximum)
        argv=["evaluate.py","--prepared",str(folder/"prepared.json"),"--prepared-sha256",sha(folder/"prepared.json"),
              "--witness",str(folder/"witness.json"),"--trust",str(folder/"trust.json"),"--collector-receipt",str(folder/"collector-receipt.json"),"--out",str(folder/"result.json")]
        stream=io.StringIO()
        # Patching both locations permits the same regression to reproduce the
        # old imported-limited implementation and exercise the corrected helper.
        with mock.patch.object(sys,"argv",argv),mock.patch.object(common,"limited",side_effect=swapped_read),mock.patch.object(evaluator,"limited",side_effect=swapped_read,create=True),redirect_stdout(stream):
            code=evaluator.main()
        (folder/"command-output.txt").write_text(stream.getvalue(),encoding="utf-8")
        result=json.loads((folder/"result.json").read_text())
        if swap is not None:self.assertEqual([str(folder/swap)],swaps)
        return code,result

    def test_model_baseline_has_no_race(self):
        code,row=self.invoke(self.fixture());self.assertEqual((0,"PASS"),(code,row["status"]))

    def race(self,name,change_original,change_malicious=lambda row:None):
        folder=self.fixture();path=folder/name
        benign=json.loads(path.read_text());change_original(benign);write(path,benign)
        # Update all honest pins to the on-disk original, then give the parser a
        # different temporary buffer which disappears before later path hashes.
        prepared=json.loads((folder/"prepared.json").read_text())
        if name in ("request.json","structure.json"):
            prepared["artifact_hashes"][str(path)]=sha(path)
            if name=="request.json":prepared["request_sha256"]=sha(path)
            write(folder/"prepared.json",prepared)
        if name=="witness.json":
            trust=json.loads((folder/"trust.json").read_text());receipt=json.loads((folder/"collector-receipt.json").read_text())
            trust["witness_sha256"]=receipt["witness_sha256"]=sha(path);write(folder/"collector-receipt.json",receipt)
            trust["collector_receipt_sha256"]=sha(folder/"collector-receipt.json");write(folder/"trust.json",trust)
        if name=="collector-receipt.json":
            trust=json.loads((folder/"trust.json").read_text());trust["collector_receipt_sha256"]=sha(path);write(folder/"trust.json",trust)
        bad=deepcopy(benign);change_malicious(bad)
        raw=(json.dumps(bad,indent=2)+"\n").encode()
        code,row=self.invoke(folder,name,raw)
        self.assertEqual((2,"FAIL"),(code,row["status"]),str(folder))

    def test_prepared_hash_is_bound_to_parsed_bytes(self):
        self.race("prepared.json",lambda r:r.update(status="FAIL"),lambda r:r.update(status="INCOMPLETE"))

    def test_request_hash_is_bound_to_parsed_bytes(self):
        self.race("request.json",lambda r:None,lambda r:r["bindings"].update(profile_sha256=h("foreign-profile")))

    def test_structural_report_hash_is_bound_to_parsed_bytes(self):
        self.race("structure.json",lambda r:r["records"][0]["methods"][0]["blockers"].append(dict(kind="MODEL_BLOCKER")),lambda r:r["records"][0]["methods"][0]["blockers"].clear())

    def test_witness_hash_is_bound_to_parsed_bytes(self):
        self.race("witness.json",lambda r:r["scope"]["observers"].update(status="INCOMPLETE"),lambda r:r["scope"]["observers"].update(status="CLOSED"))

    def test_trust_drift_is_checked_against_parsed_bytes(self):
        policy_holder={}
        def original(row):policy_holder["policy"]=row["observer_policy"];row["observer_policy"]=None
        self.race("trust.json",original,lambda r:r.update(observer_policy=policy_holder["policy"]))

    def test_receipt_hash_is_bound_to_parsed_bytes(self):
        self.race("collector-receipt.json",lambda r:r.update(exit_code=1),lambda r:r.update(exit_code=0))

    def test_plan_pin_uses_exact_parsed_buffer_then_detects_swap_back(self):
        folder=self.fixture();path=folder/"plan.json"
        write(path,{"required_hooks":fixture_hooks()});original=path.read_bytes()
        changed=json.loads(original);changed["required_hooks"][0]["id"]="W99"
        raw=(json.dumps(changed)+"\n").encode();read=common.limited
        def swap(value,maximum):
            path.write_bytes(raw)
            try:return read(value,maximum)
            finally:path.write_bytes(original)
        with mock.patch.object(common,"limited",side_effect=swap):hooks,pin=common.read_plan_bound(path)
        self.assertEqual("W99",hooks[0]["id"]);self.assertNotEqual(sha(path),pin)
        with self.assertRaises(ValueError):common.ensure_inventory({str(path):pin})


if __name__=="__main__":
    output=Path(sys.argv[1]).resolve()
    if not output.is_relative_to(ROOT/"target/qualified-frame-relation"):raise ValueError("isolated control output only")
    output.mkdir(parents=True,exist_ok=False);BufferBindingControls.output=output
    result=unittest.TextTestRunner(stream=sys.stdout,verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(BufferBindingControls))
    raise SystemExit(not result.wasSuccessful())
