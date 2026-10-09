"""Fresh generic pre/post diagnostic definitions; no profile promotion or server."""
from __future__ import annotations
import argparse,contextlib,io,json,lzma,os,sys,time,uuid,zipfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'tools/fresh-forge-observer'))
import observe as observer
sys.path.insert(0,str(ROOT/'tools/testing'))
import forge_runtime

def sources():
    paths={p for folder in ['tools/forge-v2-acquisition','tools/fresh-forge-observer'] for p in (ROOT/folder).rglob('*') if p.is_file() and '__pycache__' not in p.parts}
    paths.update(ROOT/p for p in forge_runtime.LIVE_ORACLE_SOURCES)
    paths.update(ROOT/p for p in ['tools/live-capture/generate_live_writer_plan.py','tools/forge-capture/src/com/rustcraft/offline/oracle/SrgJarBuilder.java','tools/testing/forge_runtime.py'])
    return {str(p):observer.sha(p) for p in sorted(paths)}

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--runtime-manifest',type=Path,required=True)
    p.add_argument('--hooks',type=Path,required=True)
    p.add_argument('--output',type=Path,required=True)
    a=p.parse_args();out=a.output.resolve();observer.require(out.is_relative_to(ROOT/'target/forge-v2-acquisition') and out!=ROOT/'target/forge-v2-acquisition','isolated acquisition output required');out.mkdir(parents=True,exist_ok=False)
    receipt={'schema':'FORGE_V2_ACQUISITION_V1','status':'FAIL','production_authority':False,'profile_qualification':False,'commands':[],'definition_verification':'NOT_ATTEMPTED'}
    started=time.monotonic();before=sources();receipt['sources_before']=before
    manifest=a.runtime_manifest.resolve();hook_path=a.hooks.resolve()
    env={k:v for k,v in os.environ.items() if k.upper() not in {'JAVA_TOOL_OPTIONS','_JAVA_OPTIONS','JDK_JAVA_OPTIONS','JDK_JAVAC_OPTIONS','CLASSPATH','JAVA_OPTS'}}
    def run(label,args,memory=768,timeout=120):
        argv=list(map(str,args));record={'label':label,'argv':argv,'cwd':str(out),'memory_mib':memory,'timeout_seconds':timeout}
        try:code,stdout,stderr,timed,peak=observer.launch(argv,cwd=out,env=env,memory_mib=memory,timeout=timeout)
        except observer.LaunchFailure as error:code,stdout,stderr,timed,peak=error.code,error.stdout,error.stderr,error.timed_out,error.peak;record['resource_error']=str(error)
        for stream,data in [('stdout',stdout),('stderr',stderr)]:
            f=out/(label+'.'+stream+'.txt');f.write_bytes(data);record[stream+'_sha256']=observer.sha(f)
        record.update(exit_code=code,timed_out=timed,peak_process_committed_bytes=peak);receipt['commands'].append(record)
        observer.require(code==0 and not timed and 'resource_error' not in record,label+' failed')
        return stdout,stderr
    def observe(label,manifest_path):
        capture=ROOT/'target/fresh-forge-observer'/('v2-'+label+'-'+uuid.uuid4().hex)
        sink=io.StringIO()
        with contextlib.redirect_stdout(sink):code=observer.observe(argparse.Namespace(manifest=manifest_path,output=capture))
        (out/(label+'-collector.stdout.txt')).write_text(sink.getvalue(),encoding='utf-8')
        r=observer.load(capture/'receipt.json');receipt[label]={'path':str(capture),'receipt_sha256':observer.sha(capture/'receipt.json'),'status':r['status']}
        observer.require(code==0 and r['status']=='PASS',label+' observation failed')
        return capture
    try:
        m,manifest_hash=observer.load_hashed(manifest);runtime,java=observer.validate_manifest(m);observer.require('diagnostic' not in m,'baseline manifest cannot already enable diagnostics')
        hook_data,hook_hash=observer.load_hashed(hook_path)
        receipt['manifest_sha256']=manifest_hash;receipt['hooks_sha256']=hook_hash
        hooks=hook_data['required_hooks'];expected={h['class'].replace('.','/') for h in hooks}
        observer.require(expected<=set(m['required_classes']),'hook class missing from acquisition manifest')
        pre=observe('pre',manifest);identities=observer.load(pre/'v2-identities.json');observation=observer.load(pre/'observation.json')
        forge=observation['forge'];version='.'.join(str(forge[k]) for k in ['major','minor','rev','build'])
        recipe={'schema_version':2,'kind':'RUSTCRAFT_V2_WRITER_PLAN_RECIPE','identity_mode':'CANONICAL_ID_V2','all_required_observed':True,'required_hooks_manifest_sha256':receipt['hooks_sha256'],'required_hooks':[{'id':h['id'],'status':'OBSERVED'} for h in hooks],'expected_class_identities':{name.replace('/','.'):identities[name] for name in sorted(expected)},'forge_build':version,'qualification':{'profile':m['id']+'_FRESH_V2_RECIPE_ONLY','minecraft_server_jar_sha256':m['inventory'][m['vanilla']]['sha256']}}
        recipe_path=out/'recipe.json';observer.write(recipe_path,recipe);plan=out/'LiveWriterPlan.java'
        run('generate-plan',[sys.executable,'-B',ROOT/'tools/live-capture/generate_live_writer_plan.py','--profile',recipe_path,'--manifest',hook_path,'--out',plan])
        receipt['recipe_sha256']=observer.sha(recipe_path);receipt['plan_sha256']=observer.sha(plan)
        builder=out/'builder-classes';builder.mkdir()
        run('compile-srg-builder',[java/'bin/javac.exe','-J-Xmx256M','-proc:none','-source','8','-target','8','-encoding','UTF-8','-cp',m['identity_asm'],'-d',builder,ROOT/'tools/forge-capture/src/com/rustcraft/offline/oracle/SrgJarBuilder.java'])
        mapping=out/'mapping.srg'
        with zipfile.ZipFile(observer.local(runtime,m['forge'])) as jar:mapping.write_bytes(lzma.decompress(jar.read('deobfuscation_data-1.12.2.lzma'),format=lzma.FORMAT_ALONE))
        srg=out/'study-srg.jar'
        stdout,stderr=run('build-srg',[java/'bin/java.exe','-Xmx512M','-cp',str(builder)+os.pathsep+m['identity_asm'],'com.rustcraft.offline.oracle.SrgJarBuilder',observer.local(runtime,m['vanilla']),mapping,srg],1024)
        observer.require(b'unremappable' not in stdout and not stderr,'SRG builder incomplete')
        receipt['mapping_sha256']=observer.sha(mapping);receipt['srg_sha256']=observer.sha(srg)
        classes=out/'diagnostic-classes';classes.mkdir()
        source_files=sorted({ROOT/rel for rel in forge_runtime.LIVE_ORACLE_SOURCES if '/com/rustcraft/livetransformer/' not in rel and not rel.endswith('/LiveWriterPlan.java')}|{plan})
        libraries=[observer.local(runtime,n) for n in sorted(m['inventory']) if n.startswith('libraries/') and n.endswith('.jar')]
        # Qualified ASM is a compiler dependency; the real runtime retains its own ASM.
        cp=os.pathsep.join(map(str,[srg,m['identity_asm'],observer.local(runtime,m['forge']),*libraries]))
        run('compile-diagnostic',[java/'bin/javac.exe','-J-Xmx512M','-proc:none','-source','8','-target','8','-encoding','UTF-8','-cp',cp,'-d',classes,*source_files],1024)
        compiled={p.relative_to(classes).as_posix():observer.sha(p) for p in classes.rglob('*.class')};receipt['compiled_diagnostic']=compiled
        jar_path=out/'diagnostic-definitions.jar'
        with zipfile.ZipFile(jar_path,'x',zipfile.ZIP_STORED) as jar:
            # No FMLCorePlugin manifest: no live consumer, engine or server activation.
            for name in sorted(compiled):jar.write(classes/name,name)
        receipt['diagnostic_jar_sha256']=observer.sha(jar_path)
        post_manifest=out/'post-manifest.json';m['diagnostic']={'mode':'OFFLINE_V2_HOOK_DEFINITIONS_ONLY','jar':str(jar_path),'jar_sha256':observer.sha(jar_path),'srg_jar':str(srg),'srg_jar_sha256':observer.sha(srg),'recipe':str(recipe_path),'recipe_sha256':observer.sha(recipe_path)};observer.write(post_manifest,m)
        post=observe('post',post_manifest)
        post_ids=observer.load(post/'v2-identities.json');observer.require(set(post_ids)==set(identities),'pre/post selected inventory drift')
        receipt['class_changes']={name:{'pre_raw':identities[name]['raw_sha256'],'post_raw':post_ids[name]['raw_sha256'],'changed':identities[name]!=post_ids[name]} for name in sorted(identities)}
        observer.require(all(receipt['class_changes'][n]['changed'] for n in expected),'required target not transformed')
        observer.require(observer.sha(manifest)==receipt['manifest_sha256'] and observer.sha(hook_path)==receipt['hooks_sha256'],'request inputs drift')
        observer.require(compiled=={p.relative_to(classes).as_posix():observer.sha(p) for p in classes.rglob('*.class')},'diagnostic compile drift')
        observer.require(observer.sha(jar_path)==receipt['diagnostic_jar_sha256'] and observer.sha(plan)==receipt['plan_sha256'],'diagnostic artifact drift')
        observer.validate_manifest(m);observer.require(before==sources(),'source drift')
        receipt['status']='PASS_FRESH_PRE_POST_DEFINITIONS_ONLY'
    except Exception as error:receipt['error']=type(error).__name__+': '+str(error)
    finally:
        receipt['sources_after']=sources();receipt['elapsed_seconds']=time.monotonic()-started
        if before!=receipt['sources_after']:receipt.update(status='FAIL',error='source drift')
        observer.write(out/'receipt.json',receipt)
    print(json.dumps({'status':receipt['status'],'receipt':str(out/'receipt.json'),'error':receipt.get('error')}));return 0 if receipt['status'].startswith('PASS_') else 1

if __name__=='__main__':raise SystemExit(main())
