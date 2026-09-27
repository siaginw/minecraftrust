"""Real-subprocess qualification controls; no game JVM is launched.

Set RUSTCRAFT_TEST_JAVA, RUSTCRAFT_TEST_V2_CLASSES, RUSTCRAFT_TEST_ASM and
RUSTCRAFT_TEST_CLASS_FIXTURE to run the real Java identity integration suite.
EngineFixture is shared by CLI controls; all materialized files stay in its root.
"""
from __future__ import annotations

import copy
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

from tools.testing.qualification_engine import QualificationEngine, Invalid, Missing, parse_json, sha, strict_lines
from tools.testing.qualification_certificate import Maturity as M


COLLECTOR = r'''
import argparse, hashlib, json, pathlib, sys
p=argparse.ArgumentParser();p.add_argument('--config');p.add_argument('--request');p.add_argument('--out');a=p.parse_args()
c=json.loads(pathlib.Path(a.config).read_text()); request_path=pathlib.Path(a.request); r=json.loads(request_path.read_text()); out=pathlib.Path(a.out); folder=out.parent
def sha(path): return hashlib.sha256(pathlib.Path(path).read_bytes()).hexdigest()
mode=c['mode']
if mode=='nonzero': print('intentional stderr',file=sys.stderr);sys.exit(7)
if mode=='stderr': print('unexpected successful-process diagnostic',file=sys.stderr)
if mode=='empty': sys.exit(0)
if mode=='malformed-output': print('{broken');sys.exit(0)
raw=pathlib.Path(c['fixture']).read_bytes()
if mode=='class-drift': raw=raw.replace(b'literal',b'literaX')
classes=[];pre=[]
for target,collection in [('classes',classes),('pre',pre)]:
 path=folder/target/'Fixture.class';path.parent.mkdir();path.write_bytes(raw)
 collection.append({'name':'example/Fixture','file':str(path.relative_to(folder)).replace('\\','/'),'raw_sha256':sha(path)})
control={'schema':'RUSTCRAFT_NEGATIVE_CONTROL_V2','session':r['session'],'challenge':r['challenge'],'request_sha256':sha(request_path),'id':'raw-mutation','outcome':'CHANGED','measurements':{'before':hashlib.sha256(raw).hexdigest(),'after':hashlib.sha256(bytes([raw[0]^1])+raw[1:]).hexdigest()}}
control_path=folder/'control.json';control_path.write_text(json.dumps(control))
site={'id':'site-1','class':'example/Fixture','method':'exercise','descriptor':'(I)V','status':'PASS','raw_sha256':classes[0]['raw_sha256']}
o={'schema':'RUSTCRAFT_FRESH_OBSERVATION_V2','session':r['session'],'challenge':r['challenge'],'request_sha256':sha(request_path),'capture_kind':'OFFLINE_TRANSFORM_CAPTURE','runtime_identity':c['runtime_identity'],'transformer_chain':c['transformer_chain'],'coremods':c['coremods'],'classes':classes,'pre_classes':pre,'writer_matrix':{'scope':'OFFLINE_HOOK_CALL_PRESENCE','sites':[site]},'negative_controls':[{'id':'raw-mutation','actual_outcome':'CHANGED','evidence_file':'control.json','evidence_sha256':sha(control_path)}]}
if mode=='missing-class': o['classes']=[];(folder/'classes'/'Fixture.class').unlink()
if mode=='missing-pre': o.pop('pre_classes');(folder/'pre'/'Fixture.class').unlink()
if mode=='missing-controls': o.pop('negative_controls')
if mode=='failed-control': o['negative_controls'][0]['actual_outcome']='STABLE'
if mode=='missing-writer': o.pop('writer_matrix')
if mode=='wrong-descriptor': site['descriptor']='()V'
if mode=='stale': o['session']='older-session'
if mode=='wrong-challenge': o['challenge']='0'*64
if mode=='wrong-runtime': o['runtime_identity']={'unrelated':'runtime'}
if mode=='wrong-chain': o['transformer_chain']=list(reversed(o['transformer_chain']))
if mode=='wrong-coremods': o['coremods']=['unexpected.Coremod']
if mode=='claimed-hash': o['classes'][0]['raw_sha256']='0'*64
if mode=='live-claim': o['live']={'writer_closure':True,'lifecycle_closure':True}
if mode=='extra-class': (folder/'extra.class').write_bytes(raw)
if mode=='runtime-drift': pathlib.Path(c['runtime_artifact']).write_bytes(b'changed during collection')
if mode=='malformed-observation': out.write_text('{"schema":1,"schema":2}')
else: out.write_text(json.dumps(o))
ack={'schema':'RUSTCRAFT_COLLECTOR_ACK_V2','session':r['session'],'challenge':r['challenge'],'output_sha256':sha(out)}
print(json.dumps(ack))
if mode=='extra-output': print(json.dumps(ack))
'''


PLACEMENT = r'''
import argparse,hashlib,json,pathlib
p=argparse.ArgumentParser();p.add_argument('--request');p.add_argument('--out');a=p.parse_args();src=pathlib.Path(a.request);r=json.loads(src.read_text());out=pathlib.Path(a.out)
def sha(path):return hashlib.sha256(pathlib.Path(path).read_bytes()).hexdigest()
post=r['classes']['example/Fixture'];pre=r['pre_classes']['example/Fixture'];method=next(m for m in post['methods'] if m[:2]==['exercise','(I)V']);pre_method=next(m for m in pre['methods'] if m[:2]==['exercise','(I)V']);calls=[i for i,n in enumerate(method[12]) if n[0]==184 and n[1][:3]==['java/util/Objects','requireNonNull','(Ljava/lang/Object;)Ljava/lang/Object;']];pre_calls=[i for i,n in enumerate(pre_method[12]) if n[0]==184 and n[1][:3]==['java/util/Objects','requireNonNull','(Ljava/lang/Object;)Ljava/lang/Object;']]
same=pathlib.Path(post['file']).read_bytes()==pathlib.Path(pre['file']).read_bytes()
covered=all(any(h[0]<=i<h[1] and h[2]!=i for h in method[13]) for i in calls)
checks={'anchor_order':{'status':'PASS' if calls==pre_calls and len(calls)==1 else 'FAIL','measurements':{'call_positions':calls,'reference_positions':pre_calls}},'exception_paths':{'status':'PASS' if covered and method[13] else 'FAIL','measurements':{'handler_count':len(method[13]),'fixture_callback_is_in_handler_range':covered}},'undeclared_edits':{'status':'PASS' if same else 'FAIL','measurements':{'fixture_requires_byte_identical_pre_post':same}},'pre_post_relation':{'status':'PASS' if same else 'FAIL','measurements':{'pre':sha(pre['file']),'post':sha(post['file'])}}}
w={'schema':'RUSTCRAFT_PLACEMENT_WITNESS_V2','session':r['session'],'challenge':r['challenge'],'request_sha256':sha(src),'observation_sha256':r['observation_sha256'],'covered_sites':[s['id'] for s in r['required_sites']],'checks':checks};out.write_text(json.dumps(w));print(json.dumps({'schema':'RUSTCRAFT_VALIDATOR_ACK_V2','session':r['session'],'challenge':r['challenge'],'output_sha256':sha(out)}))
'''


def integration_tools():
    names = ("RUSTCRAFT_TEST_JAVA", "RUSTCRAFT_TEST_V2_CLASSES", "RUSTCRAFT_TEST_ASM", "RUSTCRAFT_TEST_CLASS_FIXTURE")
    if any(not os.environ.get(name) for name in names):
        raise unittest.SkipTest("explicit Java/V2/ASM/fixture inputs not configured; real integration not established")
    paths = [Path(os.environ[n]).resolve(strict=True) for n in names]
    return paths


class EngineFixture:
    def __init__(self, root, *, mode="good", identity_mode="CANONICAL_ID_V2", runtime_name="synthetic-alpha"):
        self.root = Path(root).resolve()
        self.root.mkdir(parents=True, exist_ok=True)
        java, classes, asm, fixture = integration_tools()
        self.runtime = self.root / "runtime"
        self.runtime.mkdir()
        for name in ("mods", "config"):
            (self.runtime / name).mkdir()
        (self.runtime / "server.jar").write_bytes((runtime_name + " runtime").encode())
        (self.runtime / "input.class").write_bytes(fixture.read_bytes())
        (self.runtime / "mods" / "test.jar").write_bytes((runtime_name + " mod").encode())
        (self.runtime / "config" / "test.cfg").write_text("setting=" + runtime_name)
        self.collector = self.root / "collector.py"
        self.collector.write_text(COLLECTOR, encoding="utf-8")
        self.validator = self.root / "placement.py"
        self.validator.write_text(PLACEMENT, encoding="utf-8")
        self.config = self.root / "collector-config.json"
        self.config_data = dict(mode=mode, fixture=str(self.runtime / "input.class"), runtime_artifact=str(self.runtime / "server.jar"), runtime_identity={"implementation": runtime_name, "minecraft": "synthetic-1", "loader": "synthetic-2"}, transformer_chain=[runtime_name + ".First", runtime_name + ".Second"], coremods=[runtime_name + ".Coremod"])
        self.config.write_text(json.dumps(self.config_data), encoding="utf-8")
        self.identity_tool = dict(java=str(java), java_sha256=sha(java), classpath=[dict(path=str(classes), files={p.relative_to(classes).as_posix(): sha(p) for p in classes.rglob("*") if p.is_file()}), dict(path=str(asm), files={"": sha(asm)})], timeout_seconds=30)
        cp = os.pathsep.join((str(classes), str(asm)))
        process = subprocess.run([str(java), "-cp", cp, "com.rustcraft.coremod.CanonicalClassIdentityV2", str(self.runtime / "input.class")], capture_output=True, text=True, check=True)
        receipt, = strict_lines(process.stdout, 1)
        expected = {"raw_sha256": receipt[4]} if identity_mode == "RAW_SHA256" else {"semantic_sha256": receipt[2], "declaration_order_sha256": receipt[3]}
        scope = dict(schema="LIVE_CAPTURE_SCOPE_V1", operation="chunk_packet_shadow_capture", profile_id=runtime_name, dimension=0, storage_family="VANILLA_U16", registry_epoch=1, state_width_bits=16, generator_family="SYNTHETIC_DIAGNOSTIC", skylight=True)
        for key in ("provider_class", "world_class", "chunk_class", "section_class", "container_class", "nibble_class", "packet_class", "registry_class", "generator_class"):
            scope[key] = "synthetic." + key
        self.profile = dict(schema="RUSTCRAFT_QUALIFICATION_PROFILE_V2", id=runtime_name, identity_mode=identity_mode, runtime_identity=self.config_data["runtime_identity"], transformer_chain=self.config_data["transformer_chain"], coremods=self.config_data["coremods"], classes={"example/Fixture": expected}, pre_classes={"example/Fixture": copy.deepcopy(expected)}, writer_sites=[dict(id="site-1", **{"class": "example/Fixture"}, method="exercise", descriptor="(I)V", required_calls=[dict(opcode=184, owner="java/util/Objects", name="requireNonNull", descriptor="(Ljava/lang/Object;)Ljava/lang/Object;", count=1)])], negative_controls=[dict(id="raw-mutation", expected_outcome="CHANGED")], scope=scope, production_authority=False)
        self.manifest = dict(schema="RUSTCRAFT_RUNTIME_MANIFEST_V2", runtime_root=str(self.runtime), inventories={"artifacts": {"roots": ["server.jar", "input.class"], "files": {name: sha(self.runtime / name) for name in ("server.jar", "input.class")}}, "mods": {"roots": ["mods"], "files": {"mods/test.jar": sha(self.runtime / "mods" / "test.jar")}}, "config": {"roots": ["config"], "files": {"config/test.cfg": sha(self.runtime / "config" / "test.cfg")}}}, collector=self.command(self.collector, ["--config", str(self.config)], [self.config]), identity_tool=self.identity_tool, validators={"placement": self.command(self.validator)})
        self.manifest_path, self.profile_path = self.root / "manifest.json", self.root / "profile.json"
        self.write()

    @staticmethod
    def command(script, args=(), extra=()):
        executable = Path(sys.executable).resolve()
        return dict(command=[str(executable), str(script), *args], pins={str(p): sha(p) for p in (executable, script, *extra)}, environment={}, timeout_seconds=30)

    def write(self):
        self.manifest_path.write_text(json.dumps(self.manifest), encoding="utf-8")
        self.profile_path.write_text(json.dumps(self.profile), encoding="utf-8")

    def engine(self):
        self.write()
        return QualificationEngine(self.manifest_path, self.profile_path, self.root / "runs")

    def run(self, requested=M.OFFLINE_QUALIFIED):
        return self.engine().run(requested)


class EngineIntegrationControls(unittest.TestCase):
    def setUp(self):
        integration_tools()
        parent = Path(__file__).resolve().parents[2] / "target" / "qualification-engine-tests"
        parent.mkdir(parents=True, exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(dir=parent)
        self.addCleanup(self.temp.cleanup)

    def fixture(self, **kwargs):
        return EngineFixture(Path(self.temp.name) / str(len(list(Path(self.temp.name).iterdir()))), **kwargs)

    def test_actual_java_v2_pipeline_and_unique_sessions(self):
        fixture = self.fixture()
        one, two = fixture.run(), fixture.run()
        self.assertEqual((one["status"], one["maturity"]), ("PASS", "OFFLINE_QUALIFIED"))
        self.assertNotEqual(one["context"]["observation_session"], two["context"]["observation_session"])
        self.assertFalse(one["production_authority"])

    def test_different_runtime_and_raw_mode_are_generic(self):
        for mode, name in (("RAW_SHA256", "synthetic-beta"), ("CANONICAL_ID_V2", "synthetic-gamma")):
            with self.subTest(mode=mode):
                result = self.fixture(identity_mode=mode, runtime_name=name).run()
                self.assertEqual(result["status"], "PASS")
                self.assertEqual(result["context"]["profile_id"], name)

    def test_missing_witnesses_are_incomplete(self):
        for mode in ("empty", "missing-class", "missing-pre", "missing-controls", "missing-writer"):
            with self.subTest(mode=mode):
                self.assertEqual(self.fixture(mode=mode).run()["status"], "INCOMPLETE")

    def test_subprocess_schema_session_runtime_controls_fail(self):
        for mode in ("nonzero", "stderr", "malformed-output", "malformed-observation", "extra-output", "stale", "wrong-challenge", "wrong-runtime", "wrong-chain", "wrong-coremods", "claimed-hash", "wrong-descriptor", "failed-control", "extra-class", "runtime-drift"):
            with self.subTest(mode=mode):
                self.assertEqual(self.fixture(mode=mode).run()["status"], "FAIL")

    def test_raw_drift_and_member_order_drift_fail(self):
        self.assertEqual(self.fixture(mode="class-drift", identity_mode="RAW_SHA256").run()["status"], "FAIL")
        fixture = self.fixture()
        fixture.profile["classes"]["example/Fixture"]["declaration_order_sha256"] = "0" * 64
        self.assertEqual(fixture.run()["status"], "FAIL")

    def test_legacy_ten_scenario_equivalents_use_fresh_observations(self):
        # Preserve the prior verifier's scenario coverage, while making actual
        # fresh observations and recomputing identities through the Java tool.
        cases = (
            ("00-positive", "good", "PASS"),
            ("01-runtime-artifact-pin", "good", "FAIL"),
            ("02-runtime-build-label", "good", "FAIL"),
            ("03-transformed-identity", "good", "FAIL"),
            ("04-missing-hook-target", "missing-writer", "INCOMPLETE"),
            ("05-target-descriptor", "wrong-descriptor", "FAIL"),
            ("06-unexpected-transformer", "good", "FAIL"),
            ("07-missing-coremod", "good", "FAIL"),
            ("08-unrecognized-coremod", "good", "FAIL"),
            ("09-mod-artifact-substitution", "good", "FAIL"),
        )
        for name, mode, status in cases:
            with self.subTest(scenario=name):
                fixture = self.fixture(mode=mode)
                if name.startswith("01"):
                    fixture.manifest["inventories"]["artifacts"]["files"]["server.jar"] = "0" * 64
                elif name.startswith("02"):
                    fixture.profile["runtime_identity"]["loader"] = "different-build"
                elif name.startswith("03"):
                    fixture.profile["classes"]["example/Fixture"]["semantic_sha256"] = "0" * 64
                elif name.startswith("06"):
                    fixture.profile["transformer_chain"].append("UnexpectedTransformer")
                elif name.startswith("07"):
                    fixture.profile["coremods"] = []
                elif name.startswith("08"):
                    fixture.profile["coremods"].append("UnrecognizedCoremod")
                elif name.startswith("09"):
                    (fixture.runtime / "mods" / "test.jar").write_bytes(b"substituted artifact")
                self.assertEqual(fixture.run()["status"], status)

    def test_v1_and_authority_request_cannot_qualify(self):
        fixture = self.fixture()
        fixture.profile["identity_mode"] = "CANONICAL_ID_V1"
        self.assertEqual(fixture.run()["status"], "FAIL")
        fixture = self.fixture()
        self.assertEqual(fixture.run(M.AUTHORITY_AUTHORIZED)["status"], "INCOMPLETE")

    def test_unobserved_extra_mod_and_config_drift_fail(self):
        fixture = self.fixture()
        (fixture.runtime / "mods" / "extra.jar").write_bytes(b"unobserved")
        self.assertEqual(fixture.run()["status"], "FAIL")
        fixture = self.fixture()
        (fixture.runtime / "config" / "test.cfg").write_text("changed")
        self.assertEqual(fixture.run()["status"], "FAIL")

    def test_missing_lifecycle_never_promotes_offline(self):
        result = self.fixture().run(M.LIVE_QUALIFIED)
        self.assertEqual(result["status"], "INCOMPLETE")
        self.assertEqual(result["maturity"], "OFFLINE_QUALIFIED")
        self.assertEqual(self.fixture(mode="live-claim").run(M.LIVE_QUALIFIED)["status"], "FAIL")

    def test_call_presence_cannot_replace_placement_witness(self):
        fixture = self.fixture()
        fixture.manifest.pop("validators")
        result = fixture.run()
        self.assertEqual(result["status"], "INCOMPLETE")
        self.assertEqual(result["maturity"], "OBSERVED")

    def test_tool_drift_and_missing_artifact_are_distinct(self):
        fixture = self.fixture()
        fixture.collector.write_text(COLLECTOR + "\n# drift\n")
        self.assertEqual(fixture.run()["status"], "FAIL")
        fixture = self.fixture()
        (fixture.runtime / "mods" / "test.jar").unlink()
        self.assertEqual(fixture.run()["status"], "INCOMPLETE")

    def test_late_runtime_drift_invalidates_observation_descendants(self):
        cert = self.fixture(mode="runtime-drift").run()
        nodes = {n["id"]: n for n in cert["evidence"]}
        self.assertEqual(nodes["unchanged"]["status"], "FAIL")
        for key in ("acquisition", "runtime", "classes", "pre_classes", "writers", "placement", "controls"):
            self.assertEqual(nodes[key]["status"], "INVALIDATED")

    def test_malformed_schema_returns_certificate(self):
        for key, value in (("writer_sites", [None]), ("negative_controls", [{}]), ("scope", {"unsupported": 1})):
            fixture = self.fixture()
            fixture.profile[key] = value
            self.assertEqual(fixture.run()["status"], "FAIL")

    def test_identity_empty_malformed_and_nonzero_are_structured(self):
        for mode in ("empty", "malformed", "failed"):
            fixture = self.fixture()
            engine = fixture.engine()
            original = engine.process
            def process(command, timeout, label, environment=None):
                if label == "identity-receipts":
                    if mode == "failed":
                        raise Invalid("identity subprocess nonzero")
                    return "" if mode == "empty" else "{}\n"
                return original(command, timeout, label, environment)
            with patch.object(engine, "process", process):
                self.assertEqual(engine.run()["status"], "INCOMPLETE" if mode == "empty" else "FAIL")


class EngineSchemaControls(unittest.TestCase):
    def test_duplicate_nonfinite_and_malformed_json_rejected(self):
        for text in ('{"x":1,"x":2}', '{"x":NaN}', 'broken'):
            with self.assertRaises(Invalid):
                parse_json(text)

    def test_empty_is_incomplete_extra_rows_fail(self):
        with self.assertRaises(Missing):
            strict_lines("", 1)
        with self.assertRaises(Invalid):
            strict_lines("{}\n{}\n", 1)


if __name__ == "__main__":
    unittest.main()
