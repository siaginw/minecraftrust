"""Source-bound V2 writer-plan admission/generation controls; no game JVM."""
from __future__ import annotations
import argparse,copy,hashlib,json,os,re,subprocess,sys,uuid
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'tools/testing'))
from hardening_guard import inspect
def sha(p):return hashlib.sha256(Path(p).read_bytes()).hexdigest()
def write(p,v):p.write_text(json.dumps(v,indent=2)+'\n',encoding='utf-8')
def sources():
 p=[ROOT/'tools/live-capture/generate_live_writer_plan.py',ROOT/'tools/testing/hardening_guard.py',ROOT/'tools/live-capture/live-shadow-profile.json',ROOT/'tools/live-capture/required-live-writer-hooks.json',Path(__file__)]
 p.extend(ROOT/'tools/bridge/src/com/rustcraft/coremod'/n for n in ['LiveWriterPlan.java','LiveHookSupport.java','CanonicalClassIdentityV2.java'])
 p.extend((ROOT/'tools/writer-plan-v2-tests/src').rglob('*.java'))
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
  run('compile-controls',[javac,'-source','8','-target','8','-Xlint:all','-Werror','-cp',asm,'-d',classes,core/'CanonicalClassIdentityV2.java',core/'LiveHookSupport.java',core/'LiveWriterPlan.java',test/'WriterPlanIdentityV2Test.java'])
  result=run('admission-controls',[java,'-cp',str(classes)+os.pathsep+str(asm),'com.rustcraft.coremod.WriterPlanIdentityV2Test',out/'fixtures'])
  assert result.stdout.strip()==b'PASS WriterPlanIdentityV2Test assertions=26'
  identity=json.loads((out/'fixtures/fixture-identity.json').read_text());name='example.PlanFixture'
  m={'required_hooks':[{'id':'X','class':name,'method':'value','descriptor':'()I','hook_type':'WRITE_BEGIN','fingerprint':{'kind':'DECLARATION'}}]}
  manifest=out/'manifest.json';write(manifest,m)
  p={'schema_version':2,'kind':'RUSTCRAFT_V2_WRITER_PLAN_RECIPE','identity_mode':'CANONICAL_ID_V2','all_required_observed':True,'required_hooks_manifest_sha256':sha(manifest),'required_hooks':[{'id':'X','status':'OBSERVED'}],'expected_class_identities':{name:identity},'forge_build':'14.23.5.2860','qualification':{'profile':'SYNTHETIC_RECIPE_NOT_QUALIFIED','minecraft_server_jar_sha256':'0'*64}}
  generated=generate('valid-v2',p,m);v2=out/'v2-classes';v2.mkdir()
  run('compile-generated-v2',[javac,'-source','8','-target','8','-Xlint:all','-Werror','-cp',asm,'-d',v2,core/'CanonicalClassIdentityV2.java',core/'LiveHookSupport.java',generated,test/'GeneratedPlanV2Smoke.java'])
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
   mm=copy.deepcopy(m);mm['required_hooks'][0]['fingerprint']={'kind':kind};tmp=out/(label+'-manifest.json');write(tmp,mm);pp=copy.deepcopy(p);pp['required_hooks_manifest_sha256']=sha(tmp);generate(label,pp,mm,False)
  legacy=json.loads((ROOT/'tools/live-capture/live-shadow-profile.json').read_text());lm=json.loads((ROOT/'tools/live-capture/required-live-writer-hooks.json').read_text())
  # Preserve exact committed manifest bytes; pretty reserialization may change its hash.
  temp=out/'legacy-manifest.json';write(temp,lm);legacy['required_hooks_manifest_sha256']=sha(temp)
  old=generate('historical-raw',legacy,lm)
  assert old.read_bytes()==(core/'LiveWriterPlan.java').read_bytes(),'generator output drift'
  legacy['identity_mode']='CANONICAL';generate('implicit-v1-rejected',legacy,lm,False);generate('explicit-v1-reproduction',legacy,lm,True,('--legacy-v1-reproduction',))
  custom=generate('named-plan',p,m,True,('--class-name','NamedPlan'));named=custom.with_name('NamedPlan.java');named.write_bytes(custom.read_bytes());(out/'named-classes').mkdir();run('named-plan-compiles',[javac,'-source','8','-target','8','-d',out/'named-classes',named])
  r.update(status='PASS',java_assertions=26,negative_generator_controls=len(r['negative_controls']),generated_v2_compile_and_admission=True,default_raw_regeneration_byte_identical=True)
 except Exception as e:r['error']=type(e).__name__+': '+str(e)
 finally:
  r['sources_after']=sources();r['guard_after']=inspect(ROOT)
  if r['sources_before']!=r['sources_after'] or r['guard_after']['status']!='PASS' or any(sha(p)!=h for p,h in r['tools'].items()):r.update(status='FAIL',error='source/tool/isolation drift')
  write(out/'receipt.json',r)
 print(json.dumps({'status':r['status'],'receipt':str(out/'receipt.json'),'sha256':sha(out/'receipt.json'),'error':r.get('error')}))
 return int(r['status']!='PASS')
if __name__=='__main__':raise SystemExit(main())
