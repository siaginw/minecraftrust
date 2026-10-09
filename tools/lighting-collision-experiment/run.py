"""H13: fresh offline actual Java primitive oracle, isolated Rust JNI, bounded jobs."""
from __future__ import annotations
import argparse
from datetime import datetime, timezone
import hashlib
import json
import lzma
import os
from pathlib import Path
import platform
import shutil
import statistics
import subprocess
import sys
import tarfile
import time
import tomllib
import uuid
import zipfile

HERE=Path(__file__).resolve().parent
ROOT=HERE.parents[1]
sys.dont_write_bytecode=True
sys.path.insert(0,str(ROOT/'tools/testing'))
import forge_runtime as forge
from hardening_guard import inspect
sys.path.insert(0,str(ROOT/'tools/nbt-region-experiment'))
from job import launch, LaunchFailure

def sha(p):
    with Path(p).open('rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()

def sources():
    files=[p for p in HERE.rglob('*') if p.is_file() and not {'target','__pycache__'}.intersection(p.relative_to(HERE).parts)]
    files+=list((ROOT/'tools/forge-capture/src/com/rustcraft/offline').rglob('*.java'))
    files+=[ROOT/p for p in forge.LIVE_TRANSFORMER_SOURCES]
    files+=[ROOT/p for p in ('Cargo.toml','Cargo.lock','tools/testing/forge_runtime.py','tools/testing/hardening_guard.py','machine/architecture-hardening/isolation.json','tools/forge-capture/runtime-pins.json','tools/forge-capture/log4j2.xml','tools/nbt-region-experiment/job.py','tools/nbt-region-experiment/test_job.py','tools/bridge/src/com/rustcraft/bridge/M4NativeStatePayload.java','tools/bridge/src/com/rustcraft/bridge/capture/CaptureContract.java')]
    return {p.relative_to(ROOT).as_posix():sha(p) for p in sorted(set(files))}

def dependencies(meta):
    pins={(p['name'],p['version']):p.get('checksum') for p in tomllib.loads((HERE/'Cargo.lock').read_text())['package']}
    records={}
    for p in meta['packages']:
        if p['source'] is None:continue
        base=Path(p['manifest_path']).parent
        archive=base.parents[2]/'cache'/base.parent.name/(base.name+'.crate')
        if sha(archive)!=pins[(p['name'],p['version'])]:raise RuntimeError('archive mismatch')
        files={}
        with tarfile.open(archive,'r:gz') as tar:
            for member in tar:
                if not member.isfile():continue
                prefix=base.name+'/'
                if not member.name.startswith(prefix):raise RuntimeError('archive root')
                name=member.name[len(prefix):]
                if Path(name).is_absolute() or '..' in Path(name).parts:raise RuntimeError('archive path')
                digest=hashlib.sha256(tar.extractfile(member).read()).hexdigest()
                if sha(base/name)!=digest:raise RuntimeError('dependency drift '+name)
                files[name]=digest
        actual={p.relative_to(base).as_posix() for p in base.rglob('*') if p.is_file() and p.name not in ('.cargo-ok','.cargo-checksum.json')}
        if actual!=set(files):raise RuntimeError('dependency file inventory')
        records[base.name]={'archive_sha256':sha(archive),'files':files,'license':p['license']}
    return records

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--forks',type=int,default=1)
    parser.add_argument('--rounds',type=int,default=0)
    parser.add_argument('--explore',action='store_true')
    args=parser.parse_args()
    if ROOT.resolve()!=Path(r'D:\minecraftrust-astra-hardening').resolve():parser.error('isolated tree only')
    if not 1<=args.forks<=3 or not 0<=args.rounds<=6:parser.error('forks1..3 rounds0..6')
    output=ROOT/'target/architecture-hardening'/('h13-light-collision-'+uuid.uuid4().hex[:12]);output.mkdir(parents=True)
    receipt={'schema_version':1,'kind':'H13_LIGHTING_COLLISION','status':'INCOMPLETE','production_authority':False,'started_utc':datetime.now(timezone.utc).isoformat(),'commands':[],'forks':[], 'exploratory':args.explore,
        'host':{'platform':platform.platform(),'cpu':os.environ.get('PROCESSOR_IDENTIFIER'),'logical_processors':os.cpu_count(),'exclusive_host':False},
        'limits':{'java_heap_mib':512,'runtime_job_commit_mib':1024,'active_processes':1,'runtime_seconds':180,'stream_output_bytes':1048576,'shapes':4096,'queries':128,'grid_side':32}}
    env=dict(os.environ)
    for key in list(env):
        if key.upper().startswith(('RUST','CARGO')) or key in forge.ENV_EXCLUDED:env.pop(key)
    def save(): (output/'receipt.json').write_text(json.dumps(receipt,indent=2)+'\n')
    def run(label,argv,limited=False,timeout=120,cwd=ROOT):
        argv=list(map(str,argv));out=output/(label+'.stdout.log');err=output/(label+'.stderr.log');begin=time.monotonic();timed=False;peak=None;error=None
        if limited:
            try:code,stdout,stderr,timed,peak=launch(argv,cwd=cwd,env=env,memory_mib=1024,timeout=timeout)
            except LaunchFailure as failure:code,stdout,stderr,timed,peak,error=failure.code,failure.stdout,failure.stderr,failure.timed_out,failure.peak,str(failure)
            out.write_bytes(stdout);err.write_bytes(stderr)
        else:
            with out.open('wb') as a,err.open('wb') as b:
                child=subprocess.Popen(argv,cwd=cwd,env=env,stdin=subprocess.DEVNULL,stdout=a,stderr=b)
                try:code=child.wait(timeout)
                except subprocess.TimeoutExpired:
                    timed=True;subprocess.run(['taskkill','/PID',str(child.pid),'/T','/F'],stdout=b,stderr=b,timeout=10)
                    if child.poll() is None:child.kill()
                    code=child.wait(10)
        record={'label':label,'argv':argv,'cwd':str(cwd),'exit_code':code,'timed_out':timed,'job_limited':limited,'peak_process_commit_bytes':peak,'seconds':time.monotonic()-begin,'stdout':str(out.relative_to(ROOT)),'stdout_sha256':sha(out),'stderr':str(err.relative_to(ROOT)),'stderr_sha256':sha(err)}
        if error:record['error']=error
        receipt['commands'].append(record);save();print(json.dumps(record),flush=True)
        if code!=0 or timed or error:raise RuntimeError(label+' failed')
        return out.read_text(encoding='utf-8',errors='replace')
    try:
        receipt['guard_before']=inspect()
        if receipt['guard_before']['status']!='PASS':raise RuntimeError('isolation guard')
        shim=Path(r'C:\Users\Admin\.cargo\bin\cargo.exe');rustup=shim.with_name('rustup.exe');toolchain=Path(run('resolve-rustc',[rustup,'which','rustc']).strip()).parent;cargo=toolchain/'cargo.exe'
        java_home=Path(r'D:\rustcraft-toolchains\temurin8\jdk8u504-b01');java=java_home/'bin/java.exe';javac=java_home/'bin/javac.exe'
        env['PATH']=str(toolchain)+os.pathsep+env.get('PATH','');env['RUSTC']=str(toolchain/'rustc.exe');env['RUSTDOC']=str(toolchain/'rustdoc.exe')
        tools=[shim,rustup,Path(sys.executable),Path(shutil.which('git')),java,javac,java_home/'jre/bin/server/jvm.dll',java_home/'jre/lib/rt.jar',java_home/'lib/tools.jar']+[toolchain/p for p in ('cargo.exe','rustc.exe','rustdoc.exe','rustfmt.exe','cargo-fmt.exe','cargo-clippy.exe','clippy-driver.exe')]
        receipt['tools_before']={str(p):sha(p) for p in tools};receipt['sources_before']=sources();receipt['preserved_failures']={str(p.relative_to(ROOT)):sha(p) for p in (ROOT/'target/architecture-hardening/h13-light-collision-1fd0d2b78b49').glob('*.log')};receipt['preserved_failure_receipt']=forge.identity(ROOT/'target/architecture-hardening/h13-light-collision-1fd0d2b78b49/receipt.json')
        manifest=HERE/'Cargo.toml';base=['--manifest-path',manifest,'--locked','--offline','--target-dir',output/'cargo']
        meta=json.loads(run('metadata',[cargo,'metadata','--manifest-path',manifest,'--locked','--offline','--format-version','1']));receipt['dependencies_before']=dependencies(meta)
        run('rustc-version',[toolchain/'rustc.exe','-Vv']);run('java-version',[java,'-Xms16m','-Xmx128m','-version'],True)
        run('format',[cargo,'fmt','--manifest-path',manifest,'--check']);run('clippy',[cargo,'clippy',*base,'--all-targets','--no-deps','--','-D','warnings'])
        raw=run('rust-tests',[cargo,'test',*base]);
        if '7 passed; 0 failed' not in raw:raise RuntimeError('Rust test inventory')
        run('job-controls',[sys.executable,ROOT/'tools/nbt-region-experiment/test_job.py'])
        run('release-build',[cargo,'build',*base,'--release'])
        dll=output/'cargo/release/lighting_collision_experiment.dll';receipt['dll_before']=sha(dll)
        pins=json.loads((ROOT/'tools/forge-capture/runtime-pins.json').read_text());server=Path(json.loads((ROOT/'target/architecture-hardening/forge-runtime.json').read_text(encoding='utf-8-sig'))['server_root'])
        artifacts=forge.validate_artifacts(server,pins);receipt['runtime_artifacts_before']=artifacts;classpath=[Path(v['path']) for v in artifacts]
        boot=output/'bootstrap';boot.mkdir();boot_sources=list((ROOT/'tools/forge-capture/src/com/rustcraft/offline').rglob('*.java'))+[ROOT/p for p in forge.LIVE_TRANSFORMER_SOURCES]
        run('bootstrap-javac',[javac,'-J-Xms32m','-J-Xmx256m','-source','8','-target','8','-encoding','UTF-8','-cp',os.pathsep.join(map(str,classpath)),'-d',boot,*boot_sources],True)
        observer=output/'observer.jar';forge.make_observer_jar(boot,observer)
        fj=next(Path(v['path']) for v in artifacts if v['path'].endswith('forge-1.12.2-14.23.5.2860.jar'))
        with zipfile.ZipFile(fj) as jar:mapping=lzma.decompress(jar.read('deobfuscation_data-1.12.2.lzma'),format=lzma.FORMAT_ALONE)
        mapping_path=output/'mapping.srg';mapping_path.write_bytes(mapping);srg=output/'compile-only-srg.jar';vanilla=next(v['path'] for v in artifacts if v['path'].endswith('minecraft_server.1.12.2.jar'))
        run('compile-support',[java,'-Xms32m','-Xmx512m','-cp',os.pathsep.join(map(str,[boot]+classpath)),'com.rustcraft.offline.oracle.SrgJarBuilder',vanilla,mapping_path,srg],True)
        classes=output/'classes';classes.mkdir();run('probe-javac',[javac,'-J-Xms32m','-J-Xmx256m','-source','8','-target','8','-Xlint:all','-Werror','-cp',os.pathsep.join(map(str,[srg]+classpath)),'-d',classes,*sorted((HERE/'java').rglob('*.java'))],True)
        receipt['compiled_before']={str(p):sha(p) for p in list(boot.rglob('*.class'))+list(classes.rglob('*.class'))+[observer,srg,mapping_path]}
        provenance=json.loads((HERE/'provenance.json').read_text())
        for index in range(args.forks):
            fork=output/('fork-'+str(index));game=fork/'game';dump=fork/'transformed';oracle=fork/'oracle'
            for p in (game/'config',dump,oracle):p.mkdir(parents=True)
            (game/'config/forge.cfg').write_bytes(b'general {\n B:disableVersionCheck=true\n}\n');qualification=fork/'qualification.json'
            run('fork-'+str(index),[java,'-Xms128m','-Xmx512m','-Xcheck:jni','-javaagent:'+str(observer),'-Dlog4j.configurationFile='+(ROOT/'tools/forge-capture/log4j2.xml').as_uri(),'-Dlog4j2.formatMsgNoLookups=true','-Drustcraft.dumpDir='+str(dump),'-Drustcraft.qualificationResult='+str(qualification),'-Drustcraft.oracleMain=com.rustcraft.h13.Probe','-Drustcraft.oracleOutput='+str(oracle),'-Drustcraft.nativeDll='+str(dll),'-Drustcraft.h13Rounds='+str(args.rounds),'-cp',os.pathsep.join(map(str,[classes,boot]+classpath)),'net.minecraft.launchwrapper.Launch','--tweakClass','com.rustcraft.offline.bootstrap.OfflineTweaker','--gameDir',game],True,180,fork)
            q=json.loads(qualification.read_text());forge.validate_qualification(q,pins)
            for name,digest in q['transformed_classes'].items():
                if sha(dump/(name.replace('.','/')+'.class'))!=digest:raise RuntimeError('definition dump drift')
            for name,digest in provenance['runtime_definitions'].items():
                if q['transformed_classes'].get(name)!=digest or q['transformed_class_loaders'].get(name)!='net.minecraft.launchwrapper.LaunchClassLoader':raise RuntimeError('bound definition drift '+name)
            if not args.explore and not provenance['runtime_definitions']:raise RuntimeError('runtime pins required')
            result=json.loads((oracle/'results.json').read_text())
            if result['status']!='PASS_BOUNDED_PRIMITIVE_ONLY' or result['collision_cases']!=25 or result['light_update_cases']!=30 or result['controls']!=45 or len(result['fixtures'])!=55 or len(result['samples'])!=args.rounds*21:raise RuntimeError('result inventory')
            for fixture in result['fixtures']:
                path=oracle/fixture['path']
                if not path.resolve().is_relative_to(oracle.resolve()) or path.stat().st_size!=fixture['bytes'] or sha(path)!=fixture['sha256']:raise RuntimeError('fixture mismatch')
            negative=oracle/'negative-control/FIRST-DIVERGENCE.json'
            control=json.loads(negative.read_text())
            if control['label']!='intentional-signed-zero-control' or control['first_index']!=1 or control['expected_bits']!='8000000000000000' or control['actual_bits']!='0':raise RuntimeError('divergence control')
            canonical_raw=run('canonical-'+str(index),[java,'-Xms32m','-Xmx256m','-cp',os.pathsep.join(map(str,[boot]+classpath)),'com.rustcraft.coremod.CanonicalClassIdentityV2',*[dump/(name.replace('.','/')+'.class') for name in provenance['runtime_definitions']]],True)
            canonical=[json.loads(line) for line in canonical_raw.splitlines() if line.startswith('[')]
            if len(canonical)!=len(provenance['runtime_definitions']) or any(row[0]!='CANONICAL_ID_V2' or row[4]!=provenance['runtime_definitions'].get(row[1].replace('/','.')) for row in canonical):raise RuntimeError('canonical definition inventory')
            groups={}
            for sample in result['samples']:
                key=(sample['kind'],sample.get('shapes'),sample.get('clustered'),sample['repeat']);groups.setdefault(key,[]).append(sample)
            for rows in groups.values():
                if len(rows)!=3 or {r['lane'] for r in rows}!={0,1,2} or len({r['output_hash'] for r in rows})!=1:raise RuntimeError('measured result disagreement')
            if any((game/p).exists() for p in ('world','eula.txt','server.properties')):raise RuntimeError('server artifact')
            receipt['forks'].append({'qualification':forge.identity(qualification),'result':result,'result_identity':forge.identity(oracle/'results.json'),'definitions':q['transformed_classes'],'loaders':q['transformed_class_loaders'],'canonical_definitions':canonical,'negative_control':forge.identity(negative),'negative_bytes':forge.identity(oracle/'negative-control/FIRST-DIVERGENCE.bin')});save()
        receipt['status']='PASS_EXPLORATORY_ONLY' if args.explore else 'PASS_BOUNDED_PRIMITIVE_ONLY'
    except Exception as error:receipt['status']='FAIL';receipt['error']=type(error).__name__+': '+str(error)
    finally:
        try:
            receipt['sources_after']=sources();receipt['tools_after']={p:sha(p) for p in receipt.get('tools_before',{})}
            if 'meta' in locals():receipt['dependencies_after']=dependencies(meta)
            for key in ('sources','tools','dependencies'):
                if receipt.get(key+'_before')!=receipt.get(key+'_after'):raise RuntimeError(key+' drift')
            if 'dll_before' in receipt and sha(dll)!=receipt['dll_before']:raise RuntimeError('DLL drift')
            if 'compiled_before' in receipt and {p:sha(p) for p in receipt['compiled_before']}!=receipt['compiled_before']:raise RuntimeError('compiled drift')
            if 'artifacts' in locals() and forge.validate_artifacts(server,pins)!=artifacts:raise RuntimeError('runtime drift')
            receipt['guard_after']=inspect()
            if receipt['guard_after']['status']!='PASS':raise RuntimeError('guard drift')
        except Exception as error:receipt['status']='FAIL';receipt['finalization_error']=str(error)
        receipt['finished_utc']=datetime.now(timezone.utc).isoformat();save();print(json.dumps({'status':receipt['status'],'receipt':str(output/'receipt.json'),'sha256':sha(output/'receipt.json')}),flush=True)
    return 0 if receipt['status'].startswith('PASS') else 1

if __name__=='__main__':raise SystemExit(main())
