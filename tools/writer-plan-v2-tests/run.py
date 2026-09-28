"""Source-bound V2 writer-plan admission/generation controls; no game JVM."""
from __future__ import annotations
import argparse,copy,hashlib,json,os,re,subprocess,sys,uuid
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'tools/testing'))
from hardening_guard import inspect
import session_bound_certificate as certificate_schema
import session_bound_policy as policy_schema
# The engine's own canonical digest, not a local copy: the binding this lane
# checks is the one the engine will recompute, so a second implementation of it
# would prove nothing about the real comparison.
from qualification_certificate import digest_json
from text_digest import normalized_sha256
def sha(p):return hashlib.sha256(Path(p).read_bytes()).hexdigest()
# A manifest pin must be the digest the generator computes, which normalizes line
# endings. Hashing these fixtures raw would make every pin a function of the
# platform that ran the control.
def msha(p):return normalized_sha256(p)
def write(p,v):p.write_text(json.dumps(v,indent=2)+'\n',encoding='utf-8')
def sources():
 p=[ROOT/'tools/live-capture/generate_live_writer_plan.py',ROOT/'tools/testing/hardening_guard.py',ROOT/'tools/testing/session_bound_certificate.py',ROOT/'tools/testing/text_digest.py',ROOT/'tools/live-capture/live-shadow-profile.json',ROOT/'tools/live-capture/required-live-writer-hooks.json',Path(__file__)]
 p.extend(ROOT/'tools/bridge/src/com/rustcraft/coremod'/n for n in ['LiveWriterPlan.java','LiveHookSupport.java','CanonicalClassIdentityV2.java','SessionBoundIdentityCertificate.java','SessionBoundAdmissionPolicy.java','AsmTreeCompat.java'])
 p.extend((ROOT/'tools/writer-plan-v2-tests/src').rglob('*.java'))
 p.append(ROOT/'tools/bridge/src/com/rustcraft/qualification/SameProcessAcquisition.java')
 return {str(x):sha(x) for x in p}
def main():
 a=argparse.ArgumentParser(description=__doc__);a.add_argument('--output',type=Path)
 a.add_argument('--java-home',type=Path,required=True);a.add_argument('--asm',type=Path,required=True);args=a.parse_args()
 base=(ROOT/'target/architecture-hardening').resolve();out=(args.output or base/('h23-plan-'+uuid.uuid4().hex[:12])).resolve()
 if out==base or not out.is_relative_to(base):a.error('fresh output must be under isolated target/architecture-hardening')
 out.mkdir(parents=True,exist_ok=False)
 j=args.java_home.resolve()/'bin';java=j/'java.exe';javac=j/'javac.exe'
 asm=args.asm.resolve()
 env={k:v for k,v in os.environ.items() if k not in ('JAVA_TOOL_OPTIONS','_JAVA_OPTIONS','JDK_JAVA_OPTIONS','CLASSPATH')}
 r={'schema':'H23_V2_WRITER_PLAN_ADMISSION_V1','status':'FAIL','scope':'Synthetic contract controls only; no fresh runtime qualification','production_authority':False,'sources_before':sources(),'tools':{str(p):sha(p) for p in [java,javac,asm,Path(sys.executable)]},'commands':[],'negative_controls':[]}
 def run(label,argv,success=True):
  result=subprocess.run(list(map(str,argv)),cwd=ROOT,env=env,stdin=subprocess.DEVNULL,capture_output=True,timeout=120)
  for stream in ('stdout','stderr'):(out/(label+'.'+stream+'.txt')).write_bytes(getattr(result,stream))
  r['commands'].append({'name':label,'argv':list(map(str,argv)),'exit_code':result.returncode,**{s+'_sha256':sha(out/(label+'.'+s+'.txt')) for s in ('stdout','stderr')}})
  if (result.returncode==0)!=success:raise RuntimeError(label+' unexpected exit '+str(result.returncode))
  return result
 gen=ROOT/'tools/live-capture/generate_live_writer_plan.py';core=ROOT/'tools/bridge/src/com/rustcraft/coremod'
 def generate(label,p,m,success=True,extra=()):
  folder=out/label;folder.mkdir();write(folder/'profile.json',p);write(folder/'manifest.json',m)
  generated=folder/'LiveWriterPlan.java';sentinel=b'PRESERVE_ON_REFUSAL';generated.write_bytes(sentinel)
  run(label,[sys.executable,'-B',gen,'--profile',folder/'profile.json','--manifest',folder/'manifest.json','--out',generated,*extra],success)
  if not success:
   if generated.read_bytes()!=sentinel:raise RuntimeError('refusal changed existing output')
   r['negative_controls'].append(label)
  return generated
 try:
  r['guard_before']=inspect(ROOT);assert r['guard_before']['status']=='PASS'
  classes=out/'classes';classes.mkdir();test=ROOT/'tools/writer-plan-v2-tests/src/com/rustcraft/coremod'
  # LiveHookSupport reaches the acquisition recorder, so every javac step that
  # compiles it must also compile SameProcessAcquisition; otherwise the step
  # fails to resolve com.rustcraft.qualification for reasons unrelated to the
  # contract it is meant to exercise.
  acq=ROOT/'tools/bridge/src/com/rustcraft/qualification/SameProcessAcquisition.java'
  run('compile-controls',[javac,'-source','8','-target','8','-Xlint:all','-Werror','-cp',asm,'-d',classes,core/'CanonicalClassIdentityV2.java',core/'SessionBoundIdentityCertificate.java',core/'SessionBoundAdmissionPolicy.java',core/'LiveHookSupport.java',core/'AsmTreeCompat.java',core/'LiveWriterPlan.java',acq,test/'WriterPlanIdentityV2Test.java',test/'SessionBoundFixture.java'])
  result=run('admission-controls',[java,'-cp',str(classes)+os.pathsep+str(asm),'com.rustcraft.coremod.WriterPlanIdentityV2Test',out/'fixtures'])
  assert result.stdout.strip()==b'PASS WriterPlanIdentityV2Test assertions=26'
  identity=json.loads((out/'fixtures/fixture-identity.json').read_text());name='example.PlanFixture'
  # The session-bound policy needs a session-INVARIANT identity, which only a
  # classfile carrying real MixinMerged.sessionId provenance can produce.
  run('session-fixture',[java,'-cp',str(classes)+os.pathsep+str(asm),'com.rustcraft.coremod.SessionBoundFixture',out/'fixtures'])
  sidentity=json.loads((out/'fixtures/session-fixture-identity.json').read_text())
  # The plan and the runtime address a class by its binary name; the identity
  # receipt uses the internal name. Keep both renderings explicit.
  sname=sidentity['class_name'].replace('/', '.');name=sname
  sexact={'schema':'CANONICAL_ID_V2_SESSION_BOUND','class_name':sidentity['class_name'],
   'declaration_order_sha256':sidentity['declaration_order_sha256'],
   'session_invariant_sha256':sidentity['session_invariant_sha256']}
  m={'required_hooks':[{'id':'X','class':name,'method':'value','descriptor':'()I','hook_type':'WRITE_BEGIN','fingerprint':{'kind':'DECLARATION'}}]}
  manifest=out/'manifest.json';write(manifest,m)
  p={'schema_version':2,'kind':'RUSTCRAFT_V2_WRITER_PLAN_RECIPE','identity_mode':'CANONICAL_ID_V2','all_required_observed':True,'required_hooks_manifest_sha256':msha(manifest),'required_hooks':[{'id':'X','status':'OBSERVED'}],'expected_class_identities':{name:identity},'forge_build':'14.23.5.2860','qualification':{'profile':'SYNTHETIC_RECIPE_NOT_QUALIFIED','minecraft_server_jar_sha256':'0'*64}}
  generated=generate('valid-v2',p,m);v2=out/'v2-classes';v2.mkdir()
  run('compile-generated-v2',[javac,'-source','8','-target','8','-Xlint:all','-Werror','-cp',asm,'-d',v2,core/'CanonicalClassIdentityV2.java',core/'SessionBoundIdentityCertificate.java',core/'SessionBoundAdmissionPolicy.java',core/'LiveHookSupport.java',core/'AsmTreeCompat.java',acq,generated,test/'GeneratedPlanV2Smoke.java'])
  run('generated-v2-smoke',[java,'-cp',str(v2)+os.pathsep+str(asm),'com.rustcraft.coremod.GeneratedPlanV2Smoke',out/'fixtures/fixture.class'])
  variants={}
  def change(label,fn):v=copy.deepcopy(p);fn(v);variants[label]=v
  change('missing-order',lambda v:v['expected_class_identities'][name].pop('declaration_order_sha256'))
  change('wrong-name',lambda v:v['expected_class_identities'][name].update(class_name='example/Other'))
  change('missing-raw',lambda v:v['expected_class_identities'][name].pop('raw_sha256'))
  change('bad-semantic',lambda v:v['expected_class_identities'][name].update(semantic_sha256='bad'))
  change('nested-v1',lambda v:v['expected_class_identities'][name].update(schema='CANONICAL_ID_V1'))
  change('unknown-mode',lambda v:v.update(identity_mode='CANONICAL_ID_V3'))
  change('downgrade-raw',lambda v:v.update(identity_mode='RAW'))
  change('missing-mode',lambda v:v.pop('identity_mode'))
  change('not-observed',lambda v:v.update(all_required_observed=False))
  change('claimed-qualified',lambda v:v.update(all_required_qualified=True))
  change('old-hashes',lambda v:v.update(expected_class_hashes={name:'0'*64}))
  change('missing-hook',lambda v:v.update(required_hooks=[]))
  change('duplicate-hook',lambda v:v['required_hooks'].append(dict(v['required_hooks'][0])))
  change('extra-hook',lambda v:v['required_hooks'].append({'id':'Y','status':'OBSERVED'}))
  change('bad-hook-status',lambda v:v['required_hooks'][0].update(status='QUALIFIED'))
  change('missing-class',lambda v:v.update(expected_class_identities={}))
  change('extra-class',lambda v:v['expected_class_identities'].update({'example.Other':dict(identity)}))
  change('wrong-manifest',lambda v:v.update(required_hooks_manifest_sha256='0'*64))
  change('comment-injection',lambda v:v['qualification'].update(profile='*/bad'))
  for label,v in variants.items():generate(label,v,m,False)
  generate('bad-class-name',p,m,False,('--class-name','bad-name'))
  for label,kind in [('unknown-fingerprint','UNKNOWN'),('missing-anchors','BCI_ASSERTIONS')]:
   mm=copy.deepcopy(m);mm['required_hooks'][0]['fingerprint']={'kind':kind};tmp=out/(label+'-manifest.json');write(tmp,mm);pp=copy.deepcopy(p);pp['required_hooks_manifest_sha256']=msha(tmp);generate(label,pp,mm,False)
  legacy=json.loads((ROOT/'tools/live-capture/live-shadow-profile.json').read_text());lm=json.loads((ROOT/'tools/live-capture/required-live-writer-hooks.json').read_text())
  # Preserve exact committed manifest bytes; pretty reserialization may change its hash.
  temp=out/'legacy-manifest.json';write(temp,lm);legacy['required_hooks_manifest_sha256']=msha(temp)
  old=generate('historical-raw',legacy,lm)
  assert old.read_bytes()==(core/'LiveWriterPlan.java').read_bytes(),'generator output drift'
  # The committed manifest and profile must carry the same digest on any checkout.
  # Under core.autocrlf=true the working-tree bytes were CRLF, so a raw-byte hash
  # produced a different pin per platform and the committed plan recorded a digest
  # no committed blob hashes to. This fails if the two ever drift apart again, and it
  # is the control that was missing when that drift was introduced.
  committed_manifest=ROOT/'tools/live-capture/required-live-writer-hooks.json'
  committed_profile=ROOT/'tools/live-capture/live-shadow-profile.json'
  # The Revelation profile carries the same pin and nothing generates a plan from
  # it, so nothing else would have noticed when it was left behind. Naming it
  # here is what keeps one profile from drifting silently out of the set.
  rev_profile=ROOT/'tools/live-capture/revelation-live-shadow-profile.json'
  r['manifest_pin']={'manifest_lf_sha256':msha(committed_manifest),
   'profile_pin':json.loads(committed_profile.read_text(encoding='utf-8'))['required_hooks_manifest_sha256'],
   'revelation_profile_pin':json.loads(rev_profile.read_text(encoding='utf-8'))['required_hooks_manifest_sha256'],
   'plan_pin':re.search(r'REQUIRED_HOOKS_MANIFEST_SHA256 = "([0-9a-f]{64})"',(core/'LiveWriterPlan.java').read_text(encoding='utf-8')).group(1)}
  assert len(set(r['manifest_pin'].values()))==1,'manifest pin drift between manifest, both profiles and plan'
  # A CRLF rendering of the same manifest must produce the same plan: if the digest
  # were taken over raw working-tree bytes this comparison would fail on Windows.
  crlf_manifest=out/'crlf-manifest.json'
  crlf_manifest.write_bytes((json.dumps(lm,indent=2)+'\n').replace('\n','\r\n').encode('utf-8'))
  crlf_profile=copy.deepcopy(legacy);crlf_profile['required_hooks_manifest_sha256']=msha(crlf_manifest)
  crlf_dir=out/'crlf';crlf_dir.mkdir();write(crlf_dir/'profile.json',crlf_profile);write(crlf_dir/'manifest.json',lm)
  crlf_plan=crlf_dir/'LiveWriterPlan.java'
  run('crlf-manifest-same-plan',[sys.executable,'-B',gen,'--profile',str(crlf_dir/'profile.json'),'--manifest',str(crlf_manifest),'--out',str(crlf_plan)])
  assert crlf_plan.read_bytes()==old.read_bytes(),'line endings changed the generated plan'
  legacy['identity_mode']='CANONICAL';generate('implicit-v1-rejected',legacy,lm,False);generate('explicit-v1-reproduction',legacy,lm,True,('--legacy-v1-reproduction',))
  custom=generate('named-plan',p,m,True,('--class-name','NamedPlan'));named=custom.with_name('NamedPlan.java');named.write_bytes(custom.read_bytes());(out/'named-classes').mkdir();run('named-plan-compiles',[javac,'-source','8','-target','8','-d',out/'named-classes',named])
  # ---- session-bound plan generation: structure alone never authorizes ----
  def session_policy(identity, **overrides):
   document=policy_schema.build(class_name=sname,
    expected_session_invariant_sha256=identity['session_invariant_sha256'],
    expected_declaration_order_sha256=identity['declaration_order_sha256'],
    expected_masked_locations=identity['masked_locations'],
    expected_masked_occurrence_count=identity['masked_occurrences'],
    runtime_profile=runtime_profile,runtime_manifest_sha256=RUNTIME_MANIFEST_SHA,
    writer_plan_sha256='0'*64,recipe_sha256='0'*64,required_hook_ids=['X'],
    expected_loader_class=loader_class)
   document.update(overrides)
   return document
  def session_recipe(document,manifest_sha=None,**overrides):
   recipe={'schema_version':2,'kind':'RUSTCRAFT_V2_WRITER_PLAN_RECIPE','identity_mode':'CANONICAL_ID_V2_SESSION_BOUND',
    'all_required_observed':True,'required_hooks_manifest_sha256':manifest_sha or msha(manifest),
    'required_hooks':[{'id':'X','status':'OBSERVED'}],'expected_class_identities':{sname:sexact},
    'forge_build':'14.23.5.2860','qualification':{'profile':'SYNTHETIC_RECIPE_NOT_QUALIFIED','minecraft_server_jar_sha256':'0'*64,'runtime_manifest_sha256':RUNTIME_MANIFEST_SHA},
    'session_bound_classes':[sname]}
   # The binding is computed over the recipe with the policy block removed, so
   # the policy can carry it without the binding being circular.
   binding=policy_schema.recipe_binding_sha256(recipe)
   if document is not None and document.get('recipe_sha256')=='0'*64:document['recipe_sha256']=binding
   recipe['session_admission_policies']={sname:document}
   recipe['recipe_binding_sha256']=binding
   recipe.update(overrides)
   return recipe
  env_lines=run('session-fixture-environment',[java,'-cp',str(classes)+os.pathsep+str(asm),'com.rustcraft.coremod.SessionBoundFixture','--environment']).stdout.decode().strip().splitlines()
  loader_class,runtime_profile=env_lines[0].strip(),env_lines[1].strip()
  RUNTIME_MANIFEST_SHA='d'*64
  good=session_policy(sidentity)
  session_plan=generate('session-bound-valid',session_recipe(good),m)
  text=session_plan.read_text(encoding='utf-8')
  embedded=policy_schema.render(good).replace(chr(92)+chr(92),chr(92)*4).replace(chr(34),chr(92)+chr(34))
  assert embedded in text,'admission policy not embedded in the generated plan'
  assert 'CANONICAL_ID_V2_SESSION_BOUND' in text,'session-bound identity mode not declared'
  assert json.loads(policy_schema.render(good))==good,'policy does not round-trip'
  # The whole point of the split: a static plan may not carry a concrete
  # certificate, because none of its fields exist before launch.
  concrete=certificate_schema.issue(process_id='1'*8+'-'+'1'*4+'-'+'4'*4+'-'+'8'*4+'-'+'1'*12,
   transformation_session_id='2'*8+'-'+'2'*4+'-'+'4'*4+'-'+'8'*4+'-'+'2'*12,
   defining_loader_identity='example.Loader@1',class_name=sidentity['class_name'],
   pre_writer_raw_sha256=sidentity['raw_sha256'],exact_semantic_sha256=sidentity['semantic_sha256'],
   exact_declaration_order_sha256=sexact['declaration_order_sha256'],
   session_invariant_sha256=sidentity['session_invariant_sha256'],
   expected_session_uuid='0b2dcd72-90c3-4182-b23c-ac0c2ab6c7a4',
   masked_annotation_locations=sidentity['masked_locations'],distinct_masked_uuid_count=1,
   masked_occurrence_count=sidentity['masked_occurrences'],recipe_sha256='0'*64,
   runtime_manifest_sha256='0'*64,policy_sha256=policy_schema.policy_sha256(good),
   acquisition_evidence_sha256='0'*64)
  generate('session-certificate-in-static-plan',session_recipe(good,session_certificates={sname:concrete}),m,False)
  def bad_policy(label,mutate):
   document=json.loads(json.dumps(good));mutate(document)
   generate(label,session_recipe(document),m,False)
  bad_policy('policy-missing-field',lambda d:d.pop('expected_session_uuid_shape'))
  bad_policy('policy-unknown-field',lambda d:d.update(unexpected_binding='x'))
  bad_policy('policy-foreign-schema',lambda d:d.update(schema='SOMETHING_ELSE'))
  bad_policy('policy-exact-mode',lambda d:d.update(identity_mode='CANONICAL_ID_V2'))
  bad_policy('policy-two-uuids',lambda d:d.update(expected_distinct_masked_uuid_count=2))
  bad_policy('policy-wrong-class',lambda d:d.update(class_name='example/Other'))
  bad_policy('policy-carries-process-id',lambda d:d.update(process_id='1'*8+'-'+'1'*4+'-'+'4'*4+'-'+'8'*4+'-'+'1'*12))
  bad_policy('policy-carries-session-uuid',lambda d:d.update(expected_session_uuid='0b2dcd72-90c3-4182-b23c-ac0c2ab6c7a4'))
  bad_policy('policy-carries-pre-writer-hash',lambda d:d.update(pre_writer_raw_sha256='1'*64))
  bad_policy('policy-carries-acquisition-hash',lambda d:d.update(acquisition_evidence_sha256='1'*64))
  bad_policy('policy-carries-loader-identity',lambda d:d.update(defining_loader_identity='example.Loader@1'))
  bad_policy('policy-wrong-order',lambda d:d.update(expected_declaration_order_sha256='1'*64))
  bad_policy('policy-wrong-recipe',lambda d:d.update(recipe_sha256='1'*64))
  bad_policy('policy-unsorted-locations',lambda d:d.update(expected_masked_locations=['zzz','aaa']))
  bad_policy('policy-projects-nothing',lambda d:d.update(expected_session_invariant_sha256=d['expected_declaration_order_sha256']))
  bad_policy('policy-foreign-annotation',lambda d:d.update(expected_annotation_descriptor='Lcom/example/Other;'))
  bad_policy('policy-foreign-uuid-shape',lambda d:d.update(expected_session_uuid_shape='anything'))
  generate('session-missing-policy',session_recipe(good,session_admission_policies={}),m,False)
  generate('session-missing-class-list',session_recipe(good,session_bound_classes=[]),m,False)
  generate('session-class-out-of-scope',session_recipe(good,session_bound_classes=['example.Other']),m,False)
  generate('session-policy-not-in-list',session_recipe(good,session_admission_policies={sname:good,'example.Other':good}),m,False)
  generate('session-missing-recipe-binding',session_recipe(good,recipe_binding_sha256=None),m,False)
  generate('session-wrong-recipe-binding',session_recipe(good,recipe_binding_sha256='1'*64),m,False)
  generate('session-downgrade-to-exact',session_recipe(good,identity_mode='CANONICAL_ID_V2'),m,False)
  generate('session-downgrade-to-raw',session_recipe(good,identity_mode='RAW'),m,False)
  exact_with_policy=copy.deepcopy(p);exact_with_policy['session_admission_policies']={sname:good}
  generate('policy-on-exact-mode',exact_with_policy,m,False)
  exact_with_list=copy.deepcopy(p);exact_with_list['session_bound_classes']=[sname]
  generate('class-list-on-exact-mode',exact_with_list,m,False)
  session_classes=out/'session-classes';session_classes.mkdir()
  policy_src=[core/n for n in ['CanonicalClassIdentityV2.java','SessionBoundIdentityCertificate.java','SessionBoundAdmissionPolicy.java','LiveHookSupport.java','AsmTreeCompat.java']]
  run('compile-generated-session-bound',[javac,'-source','8','-target','8','-Xlint:all','-Werror','-cp',asm,'-d',session_classes,*policy_src,acq,session_plan,test/'GeneratedPlanSessionBoundSmoke.java',test/'SessionBoundAdmissionPolicyFixtures.java',test/'SessionBoundFixture.java'])
  smoke=run('generated-session-bound-smoke',[java,'-cp',str(session_classes)+os.pathsep+str(asm),'com.rustcraft.coremod.GeneratedPlanSessionBoundSmoke'])
  assert smoke.stdout.strip().startswith(b'PASS generated session-bound plan admission'),smoke.stdout
  clean=out/'clean-forge-classes';clean.mkdir()
  run('compile-clean-forge-exact-regression',[javac,'-source','8','-target','8','-Xlint:all','-Werror','-cp',asm,'-d',clean,core/'CanonicalClassIdentityV2.java',core/'SessionBoundIdentityCertificate.java',core/'SessionBoundAdmissionPolicy.java',core/'LiveHookSupport.java',core/'AsmTreeCompat.java',core/'LiveWriterPlan.java',acq,test/'CleanForgeExactModeRegression.java'])
  clean_result=run('clean-forge-exact-regression',[java,'-cp',str(clean)+os.pathsep+str(asm),'com.rustcraft.coremod.CleanForgeExactModeRegression'])
  assert clean_result.stdout.strip().startswith(b'PASS Clean Forge exact mode'),clean_result.stdout
  acquisition=out/'acquisition-classes';acquisition.mkdir()
  run('compile-same-process-acquisition',[javac,'-source','8','-target','8','-Xlint:all','-Werror','-cp',asm,'-d',acquisition,core/'CanonicalClassIdentityV2.java',core/'SessionBoundIdentityCertificate.java',core/'SessionBoundAdmissionPolicy.java',core/'LiveHookSupport.java',core/'AsmTreeCompat.java',core/'LiveWriterPlan.java',acq,test/'SessionBoundAdmissionPolicyFixtures.java',test/'SameProcessAcquisitionControls.java'])
  acquisition_result=run('same-process-acquisition-controls',[java,'-cp',str(acquisition)+os.pathsep+str(asm),'com.rustcraft.coremod.SameProcessAcquisitionControls'])
  assert acquisition_result.stdout.strip().startswith(b'PASS SameProcessAcquisitionControls'),acquisition_result.stdout
  # ---- the same-process transformation chain, rendered by the real JVM -----
  # The chain control defines its class through a loader of its own, so the
  # policy must name THAT loader class: a policy naming any other loader would
  # refuse, which is the point, and would make this lane prove nothing.
  chain_loader='com.rustcraft.coremod.TransformationChainControls$Defining'
  chain_manifest=out/'chain-manifest.json'
  chain_m={'required_hooks':[{'id':'X','class':name,'method':'handler$zzf000',
   'descriptor':'()V','hook_type':'WRITE_BEGIN','fingerprint':{'kind':'DECLARATION'}}]}
  write(chain_manifest,chain_m)
  chain_policy=session_policy(sidentity,expected_loader_class=chain_loader)
  chain_plan=generate('chain-session-bound',session_recipe(chain_policy,msha(chain_manifest)),chain_m)
  chain=out/'chain-classes';chain.mkdir()
  chain_producer=ROOT/'tools/bridge/src/com/rustcraft/qualification/TransformationChainEvidence.java'
  chain_src=[core/n for n in ['CanonicalClassIdentityV2.java','AsmTreeCompat.java','SessionBoundIdentityCertificate.java','SessionBoundAdmissionPolicy.java','LiveHookSupport.java']]
  run('compile-transformation-chain',[javac,'-source','8','-target','8','-Xlint:all','-Werror','-cp',asm,'-d',chain,*chain_src,acq,chain_producer,ROOT/'tools/bridge/src/com/rustcraft/qualification/CalleeIsolation.java',chain_plan,test/'SessionBoundFixture.java',test/'TransformationChainControls.java'])
  chain_doc=out/'transformation-chain.json'
  chain_result=run('transformation-chain-controls',[java,'-cp',str(chain)+os.pathsep+str(asm),'com.rustcraft.coremod.TransformationChainControls',out/'fixtures/session-fixture.class',chain_doc])
  # Behavioural half of the ISOLATED_CALLEE contract: a wrapper of the production
  # shape whose observation deliberately throws. Structure is proved by
  # CalleeIsolation; this proves the containment actually happens at runtime and
  # that the caller's own failures either side of it still propagate.
  calib=out/'callee-controls';calib.mkdir()
  # The wrapper variants are REAL Java compiled by the real compiler, so the
  # control tests the shape production actually ships rather than one assembled
  # by hand.
  callee_fixtures=out/'callee-fixtures';callee_fixtures.mkdir()
  run('compile-callee-fixtures',[javac,'-g','-source','8','-target','8','-d',str(callee_fixtures),ROOT/'tools/writer-plan-v2-tests/fixtures/callee/example/Wrappers.java'])
  run('compile-isolated-callee-controls',[javac,'-source','8','-target','8','-Xlint:all','-Werror','-cp',asm,'-d',calib,*chain_src,acq,chain_plan,ROOT/'tools/bridge/src/com/rustcraft/qualification/CalleeIsolation.java',ROOT/'tools/bridge/src/com/rustcraft/qualification/LoaderDefinitionWitness.java',ROOT/'tools/bridge/src/com/rustcraft/qualification/TransformationChainEvidence.java',test/'IsolatedCalleeControls.java'])
  callee_result=run('isolated-callee-controls',[java,'-cp',str(calib)+os.pathsep+str(asm),'com.rustcraft.coremod.IsolatedCalleeControls',str(callee_fixtures)])
  assert callee_result.stdout.strip().startswith(b'PASS IsolatedCalleeControls'),callee_result.stdout
  assert chain_result.stdout.strip().startswith(b'PASS TransformationChainControls'),chain_result.stdout
  # The Java renderer and the engine must agree byte for byte on the document
  # the chain binds itself to, or that binding compares a hash against a
  # different document. Checked in Python rather than asserted in Java because
  # the engine's canonical digest is the one that counts.
  rendered=json.loads(chain_doc.read_text(encoding='utf-8'))
  assert rendered['transformation_chain']['acquisition_evidence_sha256']==digest_json(rendered["session_acquisition"]),'the chain is not bound to the acquisition it names'
  for name_,document in rendered['session_certificates'].items():certificate_schema.validate(document)
  r['chain']={'document':str(chain_doc),'document_sha256':sha(chain_doc),
   'acquisition_rows':len(rendered['session_acquisition']),
   'certificates':sorted(rendered['session_certificates']),
   'stages':[s['stage'] for s in rendered['transformation_chain']['classes'][0]['stages']]}
  r.update(status='PASS',java_assertions=26,negative_generator_controls=len(r['negative_controls']),generated_v2_compile_and_admission=True,default_raw_regeneration_byte_identical=True,clean_forge_exact_mode_regression=True,same_process_acquisition_contract=True,session_bound_plan_generated_and_admitted=True,static_policy_not_concrete_certificate=True,runtime_issued_certificate_in_process=True)
 except Exception as e:r['error']=type(e).__name__+': '+str(e)
 finally:
  r['sources_after']=sources();r['guard_after']=inspect(ROOT)
  if r['sources_before']!=r['sources_after'] or r['guard_after']['status']!='PASS' or any(sha(p)!=h for p,h in r['tools'].items()):r.update(status='FAIL',error='source/tool/isolation drift')
  write(out/'receipt.json',r)
 print(json.dumps({'status':r['status'],'receipt':str(out/'receipt.json'),'sha256':sha(out/'receipt.json'),'error':r.get('error')}))
 return int(r['status']!='PASS')
if __name__=='__main__':raise SystemExit(main())
