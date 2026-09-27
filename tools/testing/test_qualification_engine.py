"""Real-subprocess qualification controls; no game JVM is launched.

Set RUSTCRAFT_TEST_JAVA, RUSTCRAFT_TEST_V2_CLASSES, RUSTCRAFT_TEST_ASM and
RUSTCRAFT_TEST_CLASS_FIXTURE to run the real Java identity integration suite.
EngineFixture is shared by CLI controls; all materialized files stay in its root.

The frame-relation witness the collector emits here is SYNTHETIC. The real one
is produced inside a real Forge launch, which this file deliberately does not
start. What these controls exercise is the engine's refusal surface: each
negative mode breaks exactly one binding the frame contract relies on, and the
engine must refuse it rather than fall back to a weaker oracle. A real
`REAL_FORGE_LAUNCH_V1` witness is validated separately against the outputs of
an actual launch.
"""
from __future__ import annotations

import copy
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

from tools.testing import session_bound_certificate as certificate
from tools.testing import session_bound_policy as admission_policy
from tools.testing.qualification_engine import QualificationEngine, Invalid, Missing, parse_json, sha, strict_lines
from tools.testing.qualification_certificate import Maturity as M, digest_json


REPO_ROOT = Path(__file__).resolve().parents[2]


COLLECTOR = r'''
import argparse, copy, hashlib, json, pathlib, sys
p=argparse.ArgumentParser();p.add_argument('--config');p.add_argument('--request');p.add_argument('--out');a=p.parse_args()
c=json.loads(pathlib.Path(a.config).read_text()); request_path=pathlib.Path(a.request); r=json.loads(request_path.read_text()); out=pathlib.Path(a.out); folder=out.parent
def sha(path): return hashlib.sha256(pathlib.Path(path).read_bytes()).hexdigest()
mode=c['mode']
if mode=='nonzero': print('intentional stderr',file=sys.stderr);sys.exit(7)
if mode=='stderr': print('unexpected successful-process diagnostic',file=sys.stderr)
if mode=='empty': sys.exit(0)
if mode=='malformed-output': print('{broken');sys.exit(0)
raw=pathlib.Path(c['fixture']).read_bytes()
S=c.get('session')
classes=[];pre=[]
# The post-writer section is what the collector was handed; the pre-writer
# section is the same buffer before the writers ran. In exact mode they are the
# same file, which is the point: nothing transformed it.
for target,collection,source in [('classes',classes,c['fixture']),('pre',pre,c['pre_fixture'] if S else c['fixture'])]:
    data=pathlib.Path(source).read_bytes()
    if mode=='class-drift' and not S: data=data.replace(b'literal',b'literaX')
    path=folder/target/(('SessionFixture' if S else 'Fixture')+'.class');path.parent.mkdir();path.write_bytes(data)
    collection.append({'name':('example/SessionFixture' if S else 'example/Fixture'),'file':str(path.relative_to(folder)).replace('\\','/'),'raw_sha256':sha(path)})
control={'schema':'RUSTCRAFT_NEGATIVE_CONTROL_V2','session':r['session'],'challenge':r['challenge'],'request_sha256':sha(request_path),'id':'raw-mutation','outcome':'CHANGED','measurements':{'before':hashlib.sha256(raw).hexdigest(),'after':hashlib.sha256(bytes([raw[0]^1])+raw[1:]).hexdigest()}}
control_path=folder/'control.json';control_path.write_text(json.dumps(control))
site={'id':'site-1','class':('example/SessionFixture' if S else 'example/Fixture'),'method':'exercise','descriptor':'(I)V','status':'PASS','raw_sha256':classes[0]['raw_sha256']}
# A SYNTHETIC frame witness standing in for the real-launch one. The real witness
# is produced inside a real Forge process, which this harness deliberately does
# not start; what is under test here is the engine's refusal surface, so each
# negative mode breaks exactly one binding the frame contract relies on.
# In session mode the digests are the real measured ones for the two separately
# compiled fixture classfiles, and the class genuinely carries a masked
# MixinMerged.sessionId, so the admission certificate is a real certificate
# rather than a placeholder.
LOADER=(S['loader_class'] if S else 'synthetic.loader.Loader')
LOADER_ID=(S['loader'] if S else LOADER+'@0a0b0c[]')
def digest(seed): return hashlib.sha256(seed.encode()).hexdigest()
PRE=(S['pre_sha'] if S else digest('pre'));DEF=(S['defined_sha'] if S else digest('defined'));POST=(S['post_sha'] if S else digest('post'))
SEM=(S['pre_semantic'] if S else digest('s1'));ORD=(S['pre_order'] if S else digest('o1'))
NAME=('example/SessionFixture' if S else 'example/Fixture')
def phase(name):
    if name=='pre':
        return {'phase':'pre','loader':LOADER_ID,'verification':[{'name':NAME,'phase':'pre','status':'OBSERVED_NOT_DEFINED_IN_THIS_PROCESS','raw_sha256':PRE,'defined_by_transforming_loader':LOADER_ID,'semantic_sha256':SEM,'declaration_order_sha256':ORD,'jvm_verified':False,'reason':'the loader only ever defined the buffer the transformer returned; these pre-writer bytes were consumed, never defined'}],'resolutions':[{'type':'java/lang/Object','class_id':'java.lang.Object','defining_loader':None,'error':None}],'assignability':[{'source':'java/lang/Object','target':'java/lang/Object','value':True,'witness':'IS_ASSIGNABLE_FROM_ON_DEFINED_CLASSES'}],'hierarchy':[{'name':'java/lang/Object','defining_loader':LOADER_ID}],'required_types':1,'assignability_queries':1}
    return {'phase':'post','loader':LOADER_ID,'verification':[{'name':NAME,'phase':'post','status':'VERIFIED','raw_sha256':DEF,'observed_raw_sha256':DEF,'rustcraft_post_writer_sha256':POST,'defined_bytes_equal_rustcraft_output':False,'same_buffer_identity':'DEFINED_BYTES_INDEPENDENTLY_OBSERVED; A_DOWNSTREAM_TRANSFORMER_ALSO_RAN','defining_loader':LOADER_ID,'initialized':True,'initialized_before_observation':True,'initialized_after_observation':True,'verification_note':'recorded, not asserted','semantic_sha256':SEM,'declaration_order_sha256':ORD,'trigger':'REAL_LAUNCHCLASSLOADER_DECLARED_METHODS_V1','verify_local':False,'verify_remote':True}],'resolutions':[{'type':'java/lang/Object','class_id':'java.lang.Object','defining_loader':None,'error':None}],'assignability':[{'source':'java/lang/Object','target':'java/lang/Object','value':True,'witness':'IS_ASSIGNABLE_FROM_ON_DEFINED_CLASSES'}],'hierarchy':[{'name':'java/lang/Object','defining_loader':LOADER_ID}],'required_types':1,'assignability_queries':1}
witness={'schema':'QUALIFIED_FRAME_WITNESS_V1','scope':{'model':'REAL_FORGE_LAUNCH_V1','status':'CLOSED','observer_assurance':'synthetic stand-in','loader_assurance':'synthetic stand-in','no_static_oracle':True,'production_authority':False},'loader_identity':LOADER_ID,'loader_class':LOADER,'phase_summaries':[{'phase':'pre','whole_classes_verified':0,'required_types':1,'assignability_queries':1,'loader':LOADER_ID},{'phase':'post','whole_classes_verified':1,'required_types':1,'assignability_queries':1,'loader':LOADER_ID}],'acquisition':[{'ordinal':1,'binary_name':NAME,'pre_writer_raw_sha256':PRE,'post_writer_raw_sha256':POST,'exact_semantic_sha256':SEM,'hook_placement':'PLACED','definition_succeeded':True,'defined_class_identity':NAME+'@1','defining_loader_identity':LOADER_ID,'session_invariant_sha256':(S['invariant'] if S else None),'certifiable':bool(S)}],'chain_of_custody':[{'ordinal':1,'binary_name':NAME,'post_writer_raw_sha256':POST,'linked_to_next_stage':None,'is_final_stage':True,'definition_bound':True,'defined_class_identity':NAME+'@1','defining_loader_identity':LOADER_ID,'certifiable':bool(S)}],'downstream_transformers_after_live_writers':['synthetic.DownstreamTransformer'],'production_authority':False,'phases':[phase('pre'),phase('post')]}
if mode=='frame-static-oracle': witness['scope']['model']='STATIC_STUDY_JAR_V1'
if mode=='frame-pre-verified': witness['phases'][0]['verification'][0].update({'status':'VERIFIED','jvm_verified':True})
if mode=='frame-overstates-pre': witness['phase_summaries'][0]['whole_classes_verified']=1
if mode=='frame-unbound': witness['phases'][1]['verification'][0]['observed_raw_sha256']='0'*64
if mode=='frame-unresolved-type': witness['phases'][1]['resolutions'][0]['error']='java.lang.NoClassDefFoundError'
if mode=='frame-silent-drift': witness['phases'][1]['verification'][0]['defined_bytes_equal_rustcraft_output']=True
if mode=='frame-claims-authority': witness['production_authority']=True
if mode=='frame-unplanned-loader': witness['phases'][1]['verification'][0]['defining_loader']='other.Loader@9'
if mode=='frame-no-definition': witness['acquisition'][0].update({'definition_succeeded':False,'defined_class_identity':None})
if mode=='frame-two-definitions': witness['chain_of_custody'].append(dict(witness['chain_of_custody'][0],ordinal=2))
if mode=='frame-certifiable-without-invariant': witness['acquisition'][0]['certifiable']=True
o={'schema':'RUSTCRAFT_FRESH_OBSERVATION_V2','session':r['session'],'challenge':r['challenge'],'request_sha256':sha(request_path),'capture_kind':'OFFLINE_TRANSFORM_CAPTURE','runtime_identity':c['runtime_identity'],'transformer_chain':c['transformer_chain'],'coremods':c['coremods'],'classes':classes,'pre_classes':pre,'writer_matrix':{'scope':'OFFLINE_HOOK_CALL_PRESENCE','sites':[site]},'negative_controls':[{'id':'raw-mutation','actual_outcome':'CHANGED','evidence_file':'control.json','evidence_sha256':sha(control_path)}],'frame_relation_witness':json.dumps(witness)}
# The same-process transformation chain. Written here rather than in the
# profile because these are OBSERVED per-run facts about bytes that moved
# through this process; the profile only declares that a chain is required.
# The certificate in the profile authorizes stage 0's output and nothing else.
if S:
    o['session_acquisition']=[{'binary_name':NAME,'pre_writer_raw_sha256':PRE,'post_writer_raw_sha256':POST,'defining_loader_identity':LOADER_ID,'hook_placement':'PLACED','definition_succeeded':True,'session_invariant_sha256':S['invariant']}]
    HOOKS=[{'id':S['hook_id'],'class':NAME,'method':'exercise','descriptor':'(I)V','required_calls':2,'observed_calls':2}]
    PATHS=[{'id':'OWNERSHIP.exercise.finally','class':NAME,'method':'exercise','descriptor':'(I)V','handler':'java/lang/Throwable'}]
    # This collector stands in for the transforming JVM, so it does what that JVM
    # does: it reads the STATIC policy, checks the pre-writer facts it actually
    # observed against that policy, and only then mints the concrete certificate
    # for THIS process. It never receives a certificate. Modes prefixed
    # `engine-sees-` disable the runtime's own refusal so a broken policy can be
    # carried past admission and refused by the offline engine instead -- the two
    # layers have to refuse independently, or one of them is doing no work.
    if c.get('pythonpath'): sys.path[:0]=c['pythonpath']
    from tools.testing import session_bound_certificate as cert
    from tools.testing import session_bound_policy as sbpolicy
    prof=json.loads(pathlib.Path(c['profile']).read_text())
    block=prof['session_bound']
    policy=sbpolicy.validate(copy.deepcopy(block['admission_policies'][S['class_name']]))
    recipe=cert.recipe_binding_sha256({k:v for k,v in prof.items() if k!='session_bound'})
    manifest_sha=sha(c['manifest'])
    refusals=[]
    if policy['recipe_sha256']!=recipe: refusals.append('RECIPE_MISMATCH: policy is bound to another recipe')
    refusals+=sbpolicy.admits(policy,session_invariant_sha256=S['invariant'],declaration_order_sha256=S['pre_order'],masked_locations=S['masked_locations'],masked_occurrence_count=len(S['masked_locations']),distinct_masked_uuid_count=1,loader_class=LOADER,runtime_profile=S['runtime_profile'],recipe_sha256=recipe,manifest_sha256=manifest_sha)
    if sorted(h['id'] for h in HOOKS)!=policy['required_hook_ids']: refusals.append('HOOK_IDS_MISMATCH: this run places hooks the policy does not cover')
    if refusals:
        if mode.startswith('engine-sees-'):
            pass
        else:
            print('session-bound admission REFUSED: %r'%refusals,file=sys.stderr);sys.exit(4)
    def digest_json(value): return hashlib.sha256(json.dumps(value,sort_keys=True,separators=(',',':'),ensure_ascii=False).encode()).hexdigest()
    issued=dict(process_id=S['process_id'],transformation_session_id=S['session_id'],defining_loader_identity=LOADER_ID,
        identity=['CANONICAL_ID_V2_SESSION_BOUND',S['class_name'],S['pre_semantic'],S['pre_order'],PRE,S['invariant'],1,[S['session_uuid']],len(S['masked_locations']),S['masked_locations']],
        recipe_sha256=recipe,runtime_manifest_sha256=manifest_sha,policy_sha256=sbpolicy.policy_sha256(policy),
        acquisition_evidence_sha256=digest_json(o['session_acquisition']))
    if mode=='cert-foreign-process': issued['process_id']='9d3ccad1-a938-4a64-a7ca-c8c81bef1757'
    if mode=='cert-foreign-session': issued['transformation_session_id']='9d3ccad1-a938-4a64-a7ca-c8c81bef1757'
    if mode=='cert-foreign-manifest': issued['runtime_manifest_sha256']='b'*64
    if mode=='cert-foreign-acquisition': issued['acquisition_evidence_sha256']='c'*64
    if mode=='cert-foreign-loader': issued['defining_loader_identity']='other.Loader@1'
    if mode=='cert-foreign-policy': issued['policy_sha256']='d'*64
    if mode=='cert-post-identity': issued['identity']=list(issued['identity']);issued['identity'][2]=S['post_semantic'];issued['identity'][3]=S['post_order']
    if mode=='cert-missing': o['session_certificates']={}
    else:
        document=cert.from_identity(**issued)
        if mode=='cert-strips-policy-hash': document.pop('policy_sha256')
        o['session_certificates']={S['class_name']: document}
    def stage(name,ordinal,transformer,si,so,sem=None,order=None,invariant=None,hooks=None,paths=None):
        return {'stage':name,'ordinal':ordinal,'process_id':S['process_id'],'transformation_session_id':S['session_id'],'defining_loader_identity':LOADER_ID,'class_name':NAME,'transformer':transformer,'input_raw_sha256':si,'output_raw_sha256':so,'exact_semantic_sha256':sem,'exact_declaration_order_sha256':order,'session_invariant_sha256':invariant,'acquisition_evidence_id':S['evidence_id'],'rustcraft_hooks':hooks,'exception_paths':paths}
    stages=[stage('PRE_WRITER',0,'none',S['upstream_sha'],PRE,SEM,ORD,S['invariant']),
            stage('RUSTCRAFT_POST_WRITER',1,'com.rustcraft.coremod.LiveChunkOwnershipTransformer',PRE,POST,S['post_semantic'],S['post_order'],None,HOOKS,PATHS),
            stage('DOWNSTREAM_TRANSFORMER',2,S['downstream'],POST,S['downstream_sha'],None,None,None,HOOKS,PATHS),
            stage('FINAL_DEFINED',3,'class-loader-definition',S['downstream_sha'],DEF,None,None,None,HOOKS,PATHS)]
    o['transformation_chain']={'schema':'RUSTCRAFT_TRANSFORMATION_CHAIN_V1','schema_version':1,'process_id':S['process_id'],'transformation_session_id':S['session_id'],'defining_loader_identity':LOADER_ID,'acquisition_evidence_sha256':S['acquisition_sha256'],'downstream_transformers_after_live_writers':[S['downstream']],'classes':[{'binary_name':NAME,'process_id':S['process_id'],'transformation_session_id':S['session_id'],'defining_loader_identity':LOADER_ID,'stages':stages}]}
    if mode=='chain-missing': o.pop('transformation_chain')
    if mode=='chain-broken-edge': stages[3]['input_raw_sha256']='0'*64
    if mode=='chain-forged-final': stages[3]['output_raw_sha256']='1'*64
    if mode=='chain-no-downstream': del stages[2];[stages[i].__setitem__('ordinal',i) for i in range(len(stages))]
    if mode=='chain-missing-rustcraft-stage': stages[1]['stage']='OBSERVED_OTHER_WRITER'
    if mode=='chain-hooks-stripped-downstream': stages[2]['rustcraft_hooks']=[dict(HOOKS[0],observed_calls=0)]
    if mode=='chain-hooks-stripped-final': stages[3]['rustcraft_hooks']=[dict(HOOKS[0],observed_calls=0)]
    if mode=='chain-lying-post-identity': stages[1]['exact_semantic_sha256']=SEM
    if mode=='chain-foreign-session': stages[3]['transformation_session_id']='9d3ccad1-a938-4a64-a7ca-c8c81bef1757'
    if mode=='chain-foreign-loader': stages[2]['defining_loader_identity']='other.Loader@1'
if mode=='missing-frame': o.pop('frame_relation_witness')
if mode=='malformed-frame': o['frame_relation_witness']='{not json'
if mode=='session-without-profile': o['session_acquisition']=[{'binary_name':'example/Fixture','pre_writer_raw_sha256':PRE,'post_writer_raw_sha256':POST,'defining_loader_identity':LOADER_ID,'hook_placement':'PLACED','definition_succeeded':True,'session_invariant_sha256':None}]
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
# Driven by the profile's declared writer contract rather than a hardcoded call,
# so the same validator states the right thing in exact mode (no insertions, so
# the pre and post bytes must be identical) and in session-bound mode (declared
# insertions, so they must differ). Both readings are checkable; neither is a
# restatement of the other.
def fact(entry,site):
    m=[x for x in entry['methods'] if x[:2]==[site['method'],site['descriptor']]]
    return m[0] if len(m)==1 else None
def positions(m,call):return [i for i,n in enumerate(m[12]) if n[0]==call['opcode'] and n[1][:3]==[call['owner'],call['name'],call['descriptor']]]
site=r['required_sites'][0];klass=site['class']
pf=fact(r['classes'][klass],site);pm=fact(r['pre_classes'][klass],site)
pre_pos=[];post_pos=[];inserted={}
for call in site['required_calls']:
    a_=positions(pm,call);b_=positions(pf,call);pre_pos.append(a_);post_pos.append(b_);inserted[call['name']]=len(b_)-len(a_)
anchors=all(len(b_)==c['count'] and b_[:len(a_)]==a_ and all(q>a_[-1] for q in b_[len(a_):]) for c,a_,b_ in zip(site['required_calls'],pre_pos,post_pos))
covered=all(any(h[0]<=i<h[1] and h[2]!=i for h in pf[13]) for i in post_pos[0])
targets=set(s['class'] for s in r['required_sites'])
drift=[n for n in sorted(r['classes']) if n not in targets and sorted(r['classes'][n]['methods'])!=sorted(r['pre_classes'][n]['methods'])]
pre_bytes=pathlib.Path(r['pre_classes'][klass]['file']).read_bytes();post_bytes=pathlib.Path(r['classes'][klass]['file']).read_bytes()
same=pre_bytes==post_bytes;declared_insertions=sum(v for v in inserted.values() if v>0)
checks={'anchor_order':{'status':'PASS' if anchors else 'FAIL','measurements':{'required':[{k:c[k] for k in ('owner','name','descriptor','count')} for c in site['required_calls']],'pre_positions':pre_pos,'post_positions':post_pos,'inserted':inserted}},'exception_paths':{'status':'PASS' if covered and pf[13] else 'FAIL','measurements':{'handler_count':len(pf[13]),'fixture_callback_is_in_handler_range':covered}},'undeclared_edits':{'status':'PASS' if not drift else 'FAIL','measurements':{'undeclared_method_drift':drift,'methods_compared':[n for n in sorted(r['classes']) if n not in targets]}},'pre_post_relation':{'status':'PASS' if same==(declared_insertions==0) else 'FAIL','measurements':{'pre':sha(r['pre_classes'][klass]['file']),'post':sha(r['classes'][klass]['file']),'pre_equals_post':same,'declared_insertions':declared_insertions,'relation_expected':'equal' if declared_insertions==0 else 'edited'}}}
w={'schema':'RUSTCRAFT_PLACEMENT_WITNESS_V2','session':r['session'],'challenge':r['challenge'],'request_sha256':sha(src),'observation_sha256':r['observation_sha256'],'covered_sites':[s['id'] for s in r['required_sites']],'checks':checks};out.write_text(json.dumps(w));print(json.dumps({'schema':'RUSTCRAFT_VALIDATOR_ACK_V2','session':r['session'],'challenge':r['challenge'],'output_sha256':sha(out)}))
'''


def integration_tools():
    names = ("RUSTCRAFT_TEST_JAVA", "RUSTCRAFT_TEST_V2_CLASSES", "RUSTCRAFT_TEST_ASM", "RUSTCRAFT_TEST_CLASS_FIXTURE",
             "RUSTCRAFT_TEST_SESSION_PRE_FIXTURE", "RUSTCRAFT_TEST_SESSION_POST_FIXTURE")
    if any(not os.environ.get(name) for name in names):
        raise unittest.SkipTest("explicit Java/V2/ASM/fixture inputs not configured; real integration not established")
    paths = [Path(os.environ[n]).resolve(strict=True) for n in names]
    return paths


#: Identity of the synthetic transformation session. Fixed, not random: the
#: certificate binds these two UUIDs, and a run must be reproducible to be a
#: control rather than a sample.
PROCESS_ID = "11111111-1111-4111-8111-111111111111"
SESSION_ID = "22222222-2222-4222-8222-222222222222"
LOADER_CLASS = "synthetic.loader.Loader"
LOADER_ID = LOADER_CLASS + "@0a0b0c[]"
DOWNSTREAM = "synthetic.DownstreamTransformer"
SESSION_CLASS = "example/SessionFixture"
SESSION_HOOK_ID = "OWNERSHIP.exercise.WRITE_BEGIN"


def identity_tool(java, classes, asm, path, *flags):
    """Run the real identity oracle. Session-bound receipts come from the same
    binary the engine recomputes exact identities with, not from a second
    implementation that could drift from it."""
    process = subprocess.run([str(java), "-cp", os.pathsep.join((str(classes), str(asm))),
                              "com.rustcraft.coremod.CanonicalClassIdentityV2", *flags, str(path)],
                             capture_output=True, text=True, check=True)
    receipt, = strict_lines(process.stdout, 1)
    return receipt


class EngineFixture:
    def __init__(self, root, *, mode="good", identity_mode="CANONICAL_ID_V2", runtime_name="synthetic-alpha",
                 session_bound=False):
        self.root = Path(root).resolve()
        self.root.mkdir(parents=True, exist_ok=True)
        java, classes, asm, fixture, session_pre, session_post = integration_tools()
        self.session_bound = session_bound
        self.runtime = self.root / "runtime"
        self.runtime.mkdir()
        for name in ("mods", "config"):
            (self.runtime / name).mkdir()
        (self.runtime / "server.jar").write_bytes((runtime_name + " runtime").encode())
        (self.runtime / "input.class").write_bytes(session_post.read_bytes() if session_bound else fixture.read_bytes())
        (self.runtime / "pre.class").write_bytes(session_pre.read_bytes())
        (self.runtime / "post.class").write_bytes(session_post.read_bytes())
        (self.runtime / "mods" / "test.jar").write_bytes((runtime_name + " mod").encode())
        (self.runtime / "config" / "test.cfg").write_text("setting=" + runtime_name)
        self.collector = self.root / "collector.py"
        self.collector.write_text(COLLECTOR, encoding="utf-8")
        self.validator = self.root / "placement.py"
        self.validator.write_text(PLACEMENT, encoding="utf-8")
        self.config = self.root / "collector-config.json"
        self.config_data = dict(mode=mode, fixture=str(self.runtime / "input.class"), runtime_artifact=str(self.runtime / "server.jar"), runtime_identity={"implementation": runtime_name, "minecraft": "synthetic-1", "loader": "synthetic-2"}, transformer_chain=[runtime_name + ".First", runtime_name + ".Second"], coremods=[runtime_name + ".Coremod"])
        if session_bound:
            self.config_data["pre_fixture"] = str(self.runtime / "pre.class")
            self.config_data["session"] = self.session_facts(java, classes, asm, session_pre, session_post)
        self.config.write_text(json.dumps(self.config_data), encoding="utf-8")
        self.identity_tool = dict(java=str(java), java_sha256=sha(java), classpath=[dict(path=str(classes), files={p.relative_to(classes).as_posix(): sha(p) for p in classes.rglob("*") if p.is_file()}), dict(path=str(asm), files={"": sha(asm)})], timeout_seconds=30)
        cp = os.pathsep.join((str(classes), str(asm)))
        klass = SESSION_CLASS if session_bound else "example/Fixture"
        source = session_post if session_bound else fixture
        admitted_source = session_pre if session_bound else fixture
        receipt = identity_tool(java, classes, asm, source)
        expected = {"raw_sha256": receipt[4]} if identity_mode == "RAW_SHA256" else {"semantic_sha256": receipt[2], "declaration_order_sha256": receipt[3]}
        admitted_receipt = receipt if not session_bound else identity_tool(java, classes, asm, admitted_source)
        admitted = {"raw_sha256": admitted_receipt[4]} if identity_mode == "RAW_SHA256" else {"semantic_sha256": admitted_receipt[2], "declaration_order_sha256": admitted_receipt[3]}
        # The declared hook count is the POST count. In exact mode the writer
        # proved nothing, so it is 1; in session-bound mode the declared writer
        # inserted the second call, so it is 2.
        hook_count = 2 if session_bound else 1
        scope = dict(schema="LIVE_CAPTURE_SCOPE_V1", operation="chunk_packet_shadow_capture", profile_id=runtime_name, dimension=0, storage_family="VANILLA_U16", registry_epoch=1, state_width_bits=16, generator_family="SYNTHETIC_DIAGNOSTIC", skylight=True)
        for key in ("provider_class", "world_class", "chunk_class", "section_class", "container_class", "nibble_class", "packet_class", "registry_class", "generator_class"):
            scope[key] = "synthetic." + key
        self.profile = dict(schema="RUSTCRAFT_QUALIFICATION_PROFILE_V2", id=runtime_name, identity_mode=identity_mode, runtime_identity=self.config_data["runtime_identity"], transformer_chain=self.config_data["transformer_chain"], coremods=self.config_data["coremods"], classes={klass: expected}, pre_classes={klass: copy.deepcopy(admitted)}, writer_sites=[dict(id="site-1", **{"class": klass}, method="exercise", descriptor="(I)V", required_calls=[dict(opcode=184, owner="java/util/Objects", name="requireNonNull", descriptor="(Ljava/lang/Object;)Ljava/lang/Object;", count=hook_count)])], negative_controls=[dict(id="raw-mutation", expected_outcome="CHANGED")], scope=scope, production_authority=False)
        artifacts = ["server.jar", "input.class"] + (["pre.class", "post.class"] if session_bound else [])
        self.manifest = dict(schema="RUSTCRAFT_RUNTIME_MANIFEST_V2", runtime_root=str(self.runtime), inventories={"artifacts": {"roots": artifacts, "files": {name: sha(self.runtime / name) for name in artifacts}}, "mods": {"roots": ["mods"], "files": {"mods/test.jar": sha(self.runtime / "mods" / "test.jar")}}, "config": {"roots": ["config"], "files": {"config/test.cfg": sha(self.runtime / "config" / "test.cfg")}}}, collector=self.command(self.collector, ["--config", str(self.config)], [self.config]), identity_tool=self.identity_tool, validators={"placement": self.command(self.validator)})
        self.manifest_path, self.profile_path = self.root / "manifest.json", self.root / "profile.json"
        self.write()
        if session_bound:
            self.build_session_block()

    def session_facts(self, java, classes, asm, session_pre, session_post):
        """Measured facts about the two real fixture classfiles, plus the two
        buffers that only exist inside the synthetic launch. Those two are
        seeded rather than derived on purpose: they stand for bytes this
        offline harness cannot produce, and the engine's job is to require them
        to be linked, not to re-derive them."""
        exact_pre = identity_tool(java, classes, asm, session_pre)
        exact_post = identity_tool(java, classes, asm, session_post)
        bound = identity_tool(java, classes, asm, session_pre, "--session-bound")
        seeded = lambda seed: hashlib.sha256(seed.encode()).hexdigest()
        return {
            "loader_class": LOADER_CLASS, "loader": LOADER_ID, "downstream": DOWNSTREAM,
            "process_id": PROCESS_ID, "session_id": SESSION_ID, "evidence_id": "synthetic.acquisition.1",
            "pre_sha": exact_pre[4], "post_sha": exact_post[4],
            "pre_semantic": exact_pre[2], "pre_order": exact_pre[3],
            "post_semantic": exact_post[2], "post_order": exact_post[3],
            "invariant": bound[5],
            "session_uuid": bound[7][0], "masked_locations": bound[9],
            "upstream_sha": seeded("synthetic.upstream-buffer"),
            "downstream_sha": seeded("synthetic.downstream-output"),
            "defined_sha": seeded("synthetic.jvm-defined-bytes"),
            "acquisition_sha256": None,
        }

    def build_session_block(self):
        """Declare the STATIC admission policy and bind it to this exact profile.

        Nothing concrete is written here. The block names what MAY be admitted --
        the session-invariant identity, the masked sites and counts, the loader
        and runtime constraints, and the plan-revision bindings -- and it says
        nothing about any one launch. The certificate that will authorize actual
        bytes is issued by the run itself, in the collector, from the pre-writer
        buffer it is holding.

        The recipe hash is computed over the profile WITHOUT the block that
        carries it, which is what keeps the binding non-circular: a policy cannot
        make itself consistent by editing the very recipe it is judged against.
        """
        facts = self.config_data["session"]
        facts["class_name"] = SESSION_CLASS
        facts["runtime_profile"] = self.profile["id"]
        facts["hook_id"] = SESSION_HOOK_ID
        # The collector reads the two static documents it is judged against, the
        # same way a transforming JVM reads its writer plan, and can import the
        # same validators rather than a second implementation of them.
        self.config_data["pythonpath"] = [str(REPO_ROOT)]
        self.config_data["profile"] = str(self.profile_path)
        self.config_data["manifest"] = str(self.manifest_path)
        acquisition = [{"binary_name": SESSION_CLASS,
                        "pre_writer_raw_sha256": facts["pre_sha"],
                        "post_writer_raw_sha256": facts["post_sha"],
                        "defining_loader_identity": LOADER_ID,
                        "hook_placement": "PLACED",
                        "definition_succeeded": True,
                        "session_invariant_sha256": facts["invariant"]}]
        facts["acquisition_sha256"] = digest_json(acquisition)
        # The collector is pinned on this config file, so the hash it needs has
        # to be in the file before the run reads it.
        self.config.write_text(json.dumps(self.config_data), encoding="utf-8")
        # The manifest pins the collector's config by hash, so it has to be
        # re-pinned before the manifest hash the policy binds to is taken.
        self.manifest["collector"] = self.command(self.collector, ["--config", str(self.config)], [self.config])
        self.manifest["collector"]["environment"] = {"PYTHONPATH": str(REPO_ROOT)}
        self.manifest_path.write_text(json.dumps(self.manifest), encoding="utf-8")
        manifest_sha = sha(self.manifest_path)
        self.profile["frame_evidence"] = {"required_classes": [SESSION_CLASS]}
        self.profile["identity_mode"] = "CANONICAL_ID_V2_SESSION_BOUND"
        recipe = certificate.recipe_binding_sha256(
            {k: v for k, v in self.profile.items() if k != "session_bound"})
        policy = admission_policy.build(
            class_name=SESSION_CLASS.replace("/", "."),
            expected_session_invariant_sha256=facts["invariant"],
            expected_declaration_order_sha256=facts["pre_order"],
            expected_masked_locations=facts["masked_locations"],
            expected_masked_occurrence_count=len(facts["masked_locations"]),
            runtime_profile=self.profile["id"],
            runtime_manifest_sha256=manifest_sha,
            writer_plan_sha256=hashlib.sha256(b"synthetic writer plan").hexdigest(),
            recipe_sha256=recipe,
            required_hook_ids=[SESSION_HOOK_ID],
            expected_loader_class=LOADER_CLASS)
        self.profile["session_bound"] = {
            "schema": "RUSTCRAFT_SESSION_BOUND_PROFILE_V1", "schema_version": 1,
            "recipe_sha256": recipe, "process_id": PROCESS_ID,
            "transformation_session_id": SESSION_ID,
            "classes": [SESSION_CLASS], "admission_policies": {SESSION_CLASS: policy},
        }
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

    def test_frame_evidence_is_required_and_never_silently_weakened(self):
        # Absent frame evidence leaves the profile unpromoted, and OBSERVED is
        # still reachable because "observed" never claimed to be verified.
        absent = self.fixture(mode="missing-frame").run()
        self.assertEqual(absent["status"], "INCOMPLETE")
        self.assertEqual(absent["maturity"], "OBSERVED")
        nodes = {n["id"]: n for n in absent["evidence"]}
        self.assertEqual(nodes["frame_evidence"]["status"], "INCOMPLETE")
        # A witness that exists but is malformed, or that claims a weaker
        # oracle, is a FAIL -- not a downgrade to a study jar.
        for mode in ("malformed-frame", "frame-static-oracle", "frame-pre-verified",
                     "frame-overstates-pre", "frame-unbound", "frame-unresolved-type",
                     "frame-silent-drift", "frame-claims-authority", "frame-unplanned-loader",
                     "frame-no-definition", "frame-two-definitions",
                     "frame-certifiable-without-invariant"):
            with self.subTest(mode=mode):
                self.assertEqual(self.fixture(mode=mode).run()["status"], "FAIL")

    def test_frame_evidence_gates_offline_but_not_observed(self):
        # `requested` is the maturity being aimed at, not a ceiling, so a
        # complete run reports the highest maturity it actually proved. The
        # frame/session gap has to show up as a difference between the two
        # runs: with both present the run reaches OFFLINE_QUALIFIED, and with
        # the frame witness absent the same request stops at OBSERVED rather
        # than failing outright, because the observation itself is still sound.
        complete = self.fixture().run(M.OBSERVED)
        self.assertEqual((complete["status"], complete["maturity"]), ("PASS", "OFFLINE_QUALIFIED"))
        unframed = self.fixture(mode="missing-frame").run(M.OBSERVED)
        self.assertEqual((unframed["status"], unframed["maturity"]), ("PASS", "OBSERVED"))

    def test_exact_mode_never_carries_session_evidence(self):
        # Nothing authorizes masking in an exact-mode profile, so accepting a
        # session evidence block for one would be a back door.
        self.assertEqual(self.fixture(mode="session-without-profile").run()["status"], "FAIL")
        fixture = self.fixture()
        fixture.profile["session_bound"] = {"schema": "RUSTCRAFT_SESSION_BOUND_PROFILE_V1"}
        self.assertEqual(fixture.run()["status"], "FAIL")

    def session_fixture(self, **kwargs):
        return self.fixture(session_bound=True, runtime_name="synthetic-session", **kwargs)

    def test_session_bound_admission_and_chain_reach_pass(self):
        # The one permitted path: a real session-bound class, a certificate
        # issued from its PRE-writer bytes, and a four-stage chain whose every
        # adjacent edge is hash-continuous and whose FINAL stage is bound to
        # the launch's own frame witness.
        result = self.session_fixture().run()
        self.assertEqual((result["status"], result["maturity"]), ("PASS", "OFFLINE_QUALIFIED"))
        self.assertFalse(result["production_authority"])
        nodes = {n["id"]: n for n in result["evidence"]}
        self.assertEqual(nodes["session_evidence"]["status"], "PASS")
        self.assertEqual(nodes["transformation_chain"]["status"], "PASS")
        # The chain node binds to the launch's own frame witness, so both hashes
        # it records are digests of genuinely observed state, not of itself.
        self.assertEqual(len(nodes["transformation_chain"]["observed_sha256"]), 64)

    def test_absent_transformation_chain_is_incomplete_not_a_pass(self):
        # A session certificate on its own proves admission, not what the writer
        # then produced. Without the chain the profile stays unpromoted.
        result = self.session_fixture(mode="chain-missing").run()
        self.assertEqual(result["status"], "INCOMPLETE")
        self.assertEqual(result["maturity"], "OBSERVED")
        nodes = {n["id"]: n for n in result["evidence"]}
        self.assertEqual(nodes["session_evidence"]["status"], "PASS")
        self.assertEqual(nodes["transformation_chain"]["status"], "INCOMPLETE")

    def test_admission_certificate_must_describe_the_pre_writer_class(self):
        # THE Blocker A control. A certificate that carries the POST-writer
        # identity is a certificate admitting a class state the Java gate never
        # saw. Before the fix this is what the engine demanded, so it is the
        # control that would have failed on the old model.
        self.assertEqual(self.session_fixture(mode="cert-post-identity").run()["status"], "FAIL")

    def test_certificate_must_be_issued_in_this_transformation_session(self):
        # A certificate carried over from a previous launch names that launch's
        # process and transformation session, so it cannot describe this one.
        # This is the control that rules out the two-launch design: there is no
        # second launch in which such a certificate would be valid.
        self.assertEqual(self.session_fixture(mode="cert-foreign-session").run()["status"], "FAIL")

    def test_certificate_must_be_issued_in_this_process(self):
        # The other half of the same rule. A certificate from another process is
        # refused even when its session id happens to line up, because the
        # process id is the fact that names the JVM which did the admitting.
        self.assertEqual(self.session_fixture(mode="cert-foreign-process").run()["status"], "FAIL")

    def test_certificate_must_bind_this_manifest(self):
        self.assertEqual(self.session_fixture(mode="cert-foreign-manifest").run()["status"], "FAIL")

    def test_certificate_must_bind_this_acquisition_evidence(self):
        # A certificate issued against a different run's acquisition is not a
        # certificate for this run.
        self.assertEqual(self.session_fixture(mode="cert-foreign-acquisition").run()["status"], "FAIL")

    def test_certificate_must_bind_the_defining_loader(self):
        self.assertEqual(self.session_fixture(mode="cert-foreign-loader").run()["status"], "FAIL")

    def test_certificate_must_name_the_policy_that_admitted_it(self):
        # The certificate is evidence that a NAMED policy admitted these bytes.
        # One that names no policy, or names a different one, is not evidence of
        # anything: without the policy hash the mask provenance it reports is
        # unauthorized by construction.
        for mode in ("cert-strips-policy-hash", "cert-foreign-policy"):
            with self.subTest(mode=mode):
                self.assertEqual(self.session_fixture(mode=mode).run()["status"], "FAIL")

    def test_absent_runtime_certificate_is_incomplete_never_a_synthesis(self):
        # The engine must not be able to make a certificate for a run that did
        # not issue one. Absent evidence leaves the profile unpromoted.
        result = self.session_fixture(mode="cert-missing").run()
        self.assertEqual(result["status"], "INCOMPLETE")
        self.assertEqual(result["maturity"], "OBSERVED")
        nodes = {n["id"]: n for n in result["evidence"]}
        self.assertEqual(nodes["session_evidence"]["status"], "INCOMPLETE")

    def test_profile_may_not_carry_a_session_certificate(self):
        # The circularity this whole design removes: a written-down profile
        # cannot hold permission, only what may be accepted. A profile that
        # tries is refused outright, however well-formed its certificates are.
        fixture = self.session_fixture()
        fixture.profile["session_bound"]["certificates"] = {SESSION_CLASS: {}}
        self.assertEqual(fixture.run()["status"], "FAIL")

    def test_profile_may_not_be_issued_against_a_concrete_session(self):
        # A static policy that names a process, a session UUID or a pre-writer
        # hash is rejected by the schema, not merely frowned upon. Each of these
        # is exactly the field that made the old design circular.
        for key, value in (("process_id", PROCESS_ID), ("session_uuid", SESSION_ID),
                           ("expected_session_uuid", SESSION_ID), ("pre_writer_raw_sha256", "a" * 64),
                           ("defining_loader_identity", LOADER_ID)):
            with self.subTest(key=key):
                fixture = self.session_fixture()
                policy = fixture.profile["session_bound"]["admission_policies"][SESSION_CLASS]
                policy[key] = value
                self.assertEqual(fixture.run()["status"], "FAIL")

    def test_engine_refuses_a_policy_the_runtime_would_have_refused(self):
        # The runtime gate and the offline engine must refuse independently. Each
        # of these mutations is a policy that admits nothing it observed, which
        # the collector would refuse; the `engine-sees-` prefix carries it past
        # that gate so the ENGINE is the one under test here.
        for mode in ("engine-sees-invariance", "engine-sees-location", "engine-sees-occurrence",
                     "engine-sees-recipe", "engine-sees-manifest", "engine-sees-profile"):
            with self.subTest(mode=mode):
                fixture = self.session_fixture(mode=mode)
                self.mutate_policy(fixture, mode[len("engine-sees-"):])
                self.assertEqual(fixture.run()["status"], "FAIL")

    def test_runtime_refuses_a_policy_that_does_not_match_the_buffer(self):
        # And the same mutations refused by the RUN rather than the engine. This
        # is the fail-closed gate: nothing is instrumented, so the run produces
        # no certificate and the profile is never scored.
        for change in ("invariance", "location", "occurrence", "recipe", "manifest", "profile"):
            with self.subTest(change=change):
                fixture = self.session_fixture()
                self.mutate_policy(fixture, change)
                result = fixture.run()
                self.assertEqual(result["status"], "FAIL")
                nodes = {n["id"]: n for n in result["evidence"]}
                self.assertIn(nodes["session_evidence"]["status"], ("INVALIDATED", "FAIL", "INCOMPLETE"))

    @staticmethod
    def mutate_policy(fixture, change):
        """Break exactly one policy binding the admission decision rests on."""
        policy = fixture.profile["session_bound"]["admission_policies"][SESSION_CLASS]
        if change == "invariance":
            policy["expected_session_invariant_sha256"] = "1" * 64
        elif change == "location":
            policy["expected_masked_locations"] = sorted(
                policy["expected_masked_locations"] + [policy["expected_masked_locations"][0] + " extra"])
        elif change == "occurrence":
            policy["expected_masked_occurrence_count"] += 1
        elif change == "recipe":
            policy["recipe_sha256"] = "2" * 64
        elif change == "manifest":
            policy["runtime_manifest_sha256"] = "3" * 64
        elif change == "profile":
            policy["runtime_profile"] = "some-other-profile"
        else:
            raise AssertionError(change)
        # The policy is validated on read, so a mutated one must still be a
        # well-formed document; only the binding it makes is wrong.
        admission_policy.validate(policy)
        fixture.write()

    def test_every_transformation_chain_link_is_enforced(self):
        # Nine chain controls, one broken link each. All must FAIL: none of them
        # may be absorbed by a weaker reading of the chain.
        for mode in ("chain-broken-edge", "chain-forged-final", "chain-no-downstream",
                     "chain-missing-rustcraft-stage", "chain-hooks-stripped-downstream",
                     "chain-hooks-stripped-final", "chain-lying-post-identity",
                     "chain-foreign-session", "chain-foreign-loader"):
            with self.subTest(mode=mode):
                self.assertEqual(self.session_fixture(mode=mode).run()["status"], "FAIL")

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

    def test_definition_hash_binds_the_exact_bytes_parsed(self):
        fixture = self.fixture()
        engine = fixture.engine()
        original_text = fixture.profile_path.read_text(encoding="utf-8")
        changed = False

        def change_after_read(text):
            nonlocal changed
            value = parse_json(text)
            if text == original_text and not changed:
                changed = True
                # A deterministic concurrent-edit boundary: parsing consumed
                # the old bytes, but a later independent file hash sees new ones.
                fixture.profile_path.write_text(original_text + "\n", encoding="utf-8")
            return value

        with patch("tools.testing.qualification_engine.parse_json", change_after_read):
            cert = engine.run()
        self.assertTrue(changed)
        self.assertEqual(cert["status"], "FAIL")
        nodes = {node["id"]: node for node in cert["evidence"]}
        self.assertEqual(nodes["unchanged"]["status"], "FAIL")
        self.assertEqual(nodes["acquisition"]["status"], "INVALIDATED")

    def test_observation_and_witness_hashes_bind_parsed_bytes(self):
        targets = (
            ("RUSTCRAFT_FRESH_OBSERVATION_V2", "observation.json"),
            ("RUSTCRAFT_NEGATIVE_CONTROL_V2", "control.json"),
            ("RUSTCRAFT_PLACEMENT_WITNESS_V2", "placement/witness.json"),
        )
        for schema, relative_path in targets:
            with self.subTest(schema=schema):
                engine = self.fixture().engine()
                changed = False

                def change_after_read(text):
                    nonlocal changed
                    value = parse_json(text)
                    if isinstance(value, dict) and value.get("schema") == schema and not changed:
                        changed = True
                        (engine.output / relative_path).write_text(text + "\n", encoding="utf-8")
                    return value

                with patch("tools.testing.qualification_engine.parse_json", change_after_read):
                    cert = engine.run()
                self.assertTrue(changed)
                self.assertEqual(cert["status"], "FAIL")
                nodes = {node["id"]: node for node in cert["evidence"]}
                self.assertEqual(nodes["unchanged"]["status"], "FAIL")
                self.assertEqual(nodes["acquisition"]["status"], "INVALIDATED")

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
