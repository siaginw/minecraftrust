"""Manifest-driven fresh offline Forge definitions. Never launches a game server."""
from __future__ import annotations
import argparse,hashlib,json,os,re,secrets,shutil,sys,time,uuid,zipfile
from pathlib import Path
HERE=Path(__file__).resolve().parent
ROOT=HERE.parents[1]
sys.path.insert(0,str(ROOT/'tools/testing'))
from qualification_engine import parse_json
from hardening_guard import inspect
sys.path.insert(0,str(ROOT/'tools/nbt-region-experiment'))
from job import launch,LaunchFailure

def require(value,message):
    if not value:raise ValueError(message)

def sha(path):
    with Path(path).open('rb') as source:return hashlib.file_digest(source,'sha256').hexdigest()
def load(path):return parse_json(Path(path).read_bytes().decode('utf-8'))
def write(path,value):Path(path).write_text(json.dumps(value,indent=2)+'\n',encoding='utf-8')

def local(root,name):
    require(isinstance(name,str) and name and '\\' not in name and ':' not in name,'portable relative path required')
    p=Path(name);require(not p.is_absolute() and '..' not in p.parts,'relative path traversal')
    value=(root/p).resolve();require(value.is_relative_to(root.resolve()),'path escapes root');return value

def inventory(root,roots,files):
    found=set()
    for name in roots:
        p=local(root,name)
        if p.exists():
            require(p.is_dir(),'inventory root is not directory')
            for child in p.rglob('*'):
                require(not child.is_symlink() and child.resolve().is_relative_to(root.resolve()),'reparse/escaping input')
                if child.is_file():found.add(child)
    for name in files:
        p=local(root,name);require(p.is_file() and not p.is_symlink(),'missing or symbolic input');found.add(p)
    require(len(found)<=20000,'input file count bound')
    result={p.relative_to(root).as_posix():{'sha256':sha(p),'bytes':p.stat().st_size} for p in sorted(found)}
    require(sum(x['bytes'] for x in result.values())<=8<<30,'input size bound')
    return result

def sources():
    paths=[p for p in HERE.rglob('*') if p.is_file() and '__pycache__' not in p.parts]
    paths.extend(ROOT/p for p in ['tools/testing/qualification_engine.py','tools/testing/qualification_certificate.py','tools/testing/hardening_guard.py','tools/nbt-region-experiment/job.py','tools/bridge/src/com/rustcraft/coremod/CanonicalClassIdentityV2.java'])
    return {str(p):sha(p) for p in paths}

def tool_pins(java,identity_asm):
    # Pin runtime jars/native libraries/compiler support, not just launcher stubs.
    files=inventory(java,['bin','jre','lib'],['release'])
    return {**{str(local(java,p)):v['sha256'] for p,v in files.items()},str(identity_asm.resolve()):sha(identity_asm),str(Path(sys.executable).resolve()):sha(sys.executable)}

def validate_observation(observation,request,required,dump,request_hash):
    fields={'schema','session','challenge','request_sha256','java_version','java_runtime_version','forge','transformers_before','transformers_after','coremods','definitions','all_loader_definitions','mod_lifecycle_executed','server_main_called','production_authority','scope'}
    require(isinstance(observation,dict) and set(observation)==fields,'observation schema fields')
    require(observation['schema']=='FRESH_FORGE_DEFINITION_OBSERVATION_V1' and all(observation[k]==request[k] for k in ['session','challenge']) and observation['request_sha256']==request_hash,'observation request binding')
    require(all(observation[k] is False for k in ['production_authority','server_main_called','mod_lifecycle_executed']),'incorrect execution scope')
    require(observation['scope']=='FRESH_OFFLINE_DEFINITIONS_ONLY_NO_WRITER_OR_LIFECYCLE_CLOSURE','observation scope')
    require(observation['transformers_before'] and observation['transformers_before']==observation['transformers_after'],'transformer chain drift')
    rows=observation['definitions'];all_rows=observation['all_loader_definitions']
    require(isinstance(rows,list) and isinstance(all_rows,list) and len(rows)==len(required) and len(all_rows)<=4096,'definition count')
    require({r['name'] for r in rows}==set(required),'selected class inventory')
    loaders={}
    def check_loader(value):
        require(isinstance(value,dict) and set(value)=={'chain','bootstrap_terminus'} and value['bootstrap_terminus'] is True,'loader schema')
        chain=value['chain'];require(isinstance(chain,list) and 0<len(chain)<=32,'loader chain bound')
        ids=[]
        for index,part in enumerate(chain):
            require(set(part)=={'class','process_local_id'} and isinstance(part['class'],str) and part['class'] and type(part['process_local_id']) is int and part['process_local_id']>0,'loader item')
            key=part['process_local_id'];require(key not in ids,'cyclic loader chain');ids.append(key)
            fact=(part['class'],chain[index+1]['process_local_id'] if index+1<len(chain) else None)
            require(key not in loaders or loaders[key]==fact,'loader identity reused with another class/parent');loaders[key]=fact
        return ids[0]
    for row in all_rows:
        require(set(row)=={'name','file','raw_sha256','bytes','loader','code_source'} and row['name'] in required,'definition fields/name')
        ident=check_loader(row['loader'])
        require(row['file']==f"loader-{ident}/{row['name']}.class",'definition loader path')
        path=local(dump,row['file']);require(type(row['bytes']) is int and 0<row['bytes']<=16<<20 and path.stat().st_size==row['bytes'] and sha(path)==row['raw_sha256'],'definition size/hash')
    actual={p.relative_to(dump).as_posix():sha(p) for p in dump.rglob('*.class')}
    require(len(all_rows)==len(actual) and actual=={r['file']:r['raw_sha256'] for r in all_rows},'observed definition inventory')
    require(all(r in all_rows for r in rows),'selected class loader definition missing')
    for group in ['transformers_before','coremods']:
        require(isinstance(observation[group],list),'runtime inventory list')
        for row in observation[group]:
            require(set(row)=={'class','loader','code_source'} and isinstance(row['class'],str) and row['class'],'runtime inventory item')
            check_loader(row['loader'])
    return rows

def validate_manifest(m):
    require(set(m)=={'schema','id','runtime_root','java_home','roots','files','inventory','forge','vanilla','asm','launchwrapper','required_classes','tools','identity_asm'},'manifest fields')
    require(m['schema']=='FRESH_FORGE_OBSERVER_MANIFEST_V1' and re.fullmatch(r'[A-Za-z0-9_.-]+',m['id']),'manifest schema/id')
    root=Path(m['runtime_root']);java=Path(m['java_home'])
    require(root.is_absolute() and java.is_absolute(),'absolute runtime and Java paths required')
    require(not root.resolve().is_relative_to(Path(r'D:\minecraftrust').resolve()),'original worktree cannot be executable runtime input')
    require(all(isinstance(m[k],list) for k in ['roots','files','required_classes']),'manifest lists')
    require(0<len(m['required_classes'])<=256 and len(set(m['required_classes']))==len(m['required_classes']),'required class count/duplicates')
    require(all(isinstance(n,str) and re.fullmatch(r'[A-Za-z_$][A-Za-z0-9_$]*(/[A-Za-z_$][A-Za-z0-9_$]*)+',n) for n in m['required_classes']),'class names')
    for key in ['forge','vanilla','asm','launchwrapper']:
        require(m[key] in m['inventory'] and local(root,m[key]).is_file(),'missing runtime role '+key)
    require(all(isinstance(v,dict) and set(v)=={'sha256','bytes'} and type(v['bytes']) is int and v['bytes']>=0 and re.fullmatch('[0-9a-f]{64}',v['sha256']) for v in m['inventory'].values()),'inventory facts')
    identity_asm=Path(m['identity_asm']);require(identity_asm.is_absolute() and identity_asm.is_file(),'explicit independent identity ASM required')
    require(m['tools']==tool_pins(java,identity_asm),'Java/Python/parser tool inventory drift')
    require(inventory(root,m['roots'],m['files'])==m['inventory'],'runtime input drift')
    return root,java

def prepare(args):
    root=args.runtime_root.resolve();java=args.java_home.resolve()
    require(root.is_dir(),'runtime root absent')
    hooks=load(args.hooks)
    required=sorted({h['class'].replace('.','/') for h in hooks['required_hooks']}|set(args.extra_class))
    files=list(dict.fromkeys([args.forge,args.vanilla,*args.file]));roots=list(dict.fromkeys(args.roots))
    m={'schema':'FRESH_FORGE_OBSERVER_MANIFEST_V1','id':args.id,'runtime_root':str(root),'java_home':str(java),'roots':roots,'files':files,'inventory':inventory(root,roots,files),'forge':args.forge,'vanilla':args.vanilla,'asm':args.asm,'launchwrapper':args.launchwrapper,'required_classes':required,'tools':{str(java/'bin'/n):sha(java/'bin'/n) for n in ['java.exe','javac.exe']}}
    identity_asm=args.identity_asm.resolve();m['identity_asm']=str(identity_asm);m['tools']=tool_pins(java,identity_asm)
    validate_manifest(m);out=args.manifest.resolve();require(out.is_relative_to(ROOT/'target') and not out.exists(),'fresh manifest output under isolated target required')
    out.parent.mkdir(parents=True,exist_ok=True);write(out,m);print(json.dumps({'manifest':str(out),'sha256':sha(out),'files':len(m['inventory']),'classes':len(required)}))

def observe(args):
    manifest_path=args.manifest.resolve();raw=manifest_path.read_bytes();m=parse_json(raw.decode('utf-8'));root,java=validate_manifest(m)
    base=(ROOT/'target/fresh-forge-observer').resolve();out=(args.output or base/uuid.uuid4().hex).resolve()
    require(out.is_relative_to(base) and out!=base and not out.is_relative_to(root),'fresh observation output must be isolated from runtime inputs')
    out.mkdir(parents=True,exist_ok=False);started=time.monotonic()
    receipt={'schema':'FRESH_FORGE_OBSERVER_RUN_V1','status':'FAIL','production_authority':False,'manifest_sha256':hashlib.sha256(raw).hexdigest(),'commands':[],'scope':'OFFLINE_FINAL_DEFINITIONS_ONLY_NO_PROFILE_QUALIFICATION'}
    before=sources();receipt['sources_before']=before
    request={'schema':'FRESH_FORGE_OBSERVER_REQUEST_V1','session':uuid.uuid4().hex,'challenge':secrets.token_hex(32),'manifest_sha256':receipt['manifest_sha256'],'required_classes':m['required_classes']}
    write(out/'request.json',request)
    blocked={'JAVA_TOOL_OPTIONS','_JAVA_OPTIONS','JDK_JAVA_OPTIONS','JDK_JAVAC_OPTIONS','CLASSPATH','JAVA_OPTS'}
    env={k:v for k,v in os.environ.items() if k.upper() not in blocked};receipt['removed_environment_names']=sorted(k for k in os.environ if k.upper() in blocked)
    def run(label,argv,cwd,memory,timeout):
        argv=list(map(str,argv));record={'label':label,'argv':argv,'cwd':str(cwd),'memory_mib':memory,'timeout_seconds':timeout}
        try:code,stdout,stderr,timed,peak=launch(argv,cwd=cwd,env=env,memory_mib=memory,timeout=timeout)
        except LaunchFailure as error:
            code,stdout,stderr,timed,peak=error.code,error.stdout,error.stderr,error.timed_out,error.peak;record['resource_error']=str(error)
        (out/(label+'.stdout.txt')).write_bytes(stdout);(out/(label+'.stderr.txt')).write_bytes(stderr)
        record.update(exit_code=code,timed_out=timed,peak_process_committed_bytes=peak,stdout_sha256=sha(out/(label+'.stdout.txt')),stderr_sha256=sha(out/(label+'.stderr.txt')));receipt['commands'].append(record)
        require(code==0 and not timed and 'resource_error' not in record,label+' process failed')
        return stdout,stderr
    try:
        receipt['guard_before']=inspect(ROOT);require(receipt['guard_before']['status']=='PASS','isolation before')
        game=out/'game';game.mkdir()
        for name,row in m['inventory'].items():
            dest=local(game,name);dest.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(local(root,name),dest);require(sha(dest)==row['sha256'],'copy changed bytes')
        for name in m['roots']:local(game,name).mkdir(parents=True,exist_ok=True)
        require(inventory(game,m['roots'],m['files'])==m['inventory'],'private input copy inventory')
        required=out/'required.txt';required.write_text('\n'.join(m['required_classes'])+'\n',encoding='utf-8')
        classes=out/'classes';classes.mkdir();dump=out/'definitions';dump.mkdir()
        jars=[local(game,p) for p in sorted(m['inventory']) if p.startswith('libraries/') and p.endswith('.jar')]
        cp=os.pathsep.join(map(str,[*jars,local(game,m['forge']),local(game,m['vanilla'])]))
        src=sorted((HERE/'java').rglob('*.java'))
        # Official Forge manifests contain relative optional classpath entries.
        # The explicit copied/pinned classpath is authoritative for this tool.
        stdout,stderr=run('compile',[java/'bin/javac.exe','-J-Xmx384M','-proc:none','-encoding','UTF-8','-source','8','-target','8','-Xlint:all,-path','-Werror','-cp',cp,'-d',classes,*src],out,768,120)
        require(not stdout and not stderr,'unexpected compiler output')
        compiled={p.relative_to(classes).as_posix():sha(p) for p in sorted(classes.rglob('*.class'))};receipt['compiled_classes']=compiled
        identity_classes=out/'identity-classes';identity_classes.mkdir()
        stdout,stderr=run('compile-identity',[java/'bin/javac.exe','-J-Xmx256M','-proc:none','-encoding','UTF-8','-source','8','-target','8','-Xlint:all','-Werror','-cp',m['identity_asm'],'-d',identity_classes,ROOT/'tools/bridge/src/com/rustcraft/coremod/CanonicalClassIdentityV2.java'],out,768,60)
        require(not stdout and not stderr,'identity compiler output')
        identity_hashes={p.relative_to(identity_classes).as_posix():sha(p) for p in identity_classes.rglob('*.class')};receipt['compiled_identity']=identity_hashes
        observer=out/'observer.jar'
        with zipfile.ZipFile(observer,'w') as jar:
            jar.writestr('META-INF/MANIFEST.MF','Manifest-Version: 1.0\r\nPremain-Class: com.rustcraft.fresh.agent.DefinitionObserver\r\nCan-Retransform-Classes: false\r\nCan-Redefine-Classes: false\r\n\r\n')
            for p in sorted((classes/'com/rustcraft/fresh/agent').glob('*.class')):jar.write(p,p.relative_to(classes).as_posix())
        receipt['observer_sha256']=sha(observer)
        controls=out/'controls';controls.mkdir();control_classes=controls/'classes';control_classes.mkdir()
        stdout,stderr=run('compile-observer-controls',[java/'bin/javac.exe','-J-Xmx256M','-proc:none','-source','8','-target','8','-Xlint:all','-Werror','-cp',classes,'-d',control_classes,*sorted((HERE/'tests').rglob('*.java'))],out,768,60)
        require(not stdout and not stderr,'observer control compiler output')
        control_hashes={p.relative_to(control_classes).as_posix():sha(p) for p in control_classes.rglob('*.class')};receipt['compiled_controls']=control_hashes
        control_required=controls/'required.txt';control_required.write_text('example/Fixture\n',encoding='utf-8')
        for mode in ['valid','duplicate','redefined']:
            control_dump=controls/mode;control_dump.mkdir()
            stdout,stderr=run('observer-control-'+mode,[java/'bin/java.exe','-Xmx128M','-Xverify:all','-javaagent:'+str(observer),'-Drustcraft.fresh.required='+str(control_required),'-Drustcraft.fresh.dump='+str(control_dump),'-cp',str(control_classes)+os.pathsep+str(classes),'ObserverControl',control_classes/'example/Fixture.class',mode],out,384,30)
            require(not stderr and stdout.strip()==('PASS_OBSERVER_CONTROL '+mode).encode(),'observer control result')
        stdout,stderr=run('python-controls',[sys.executable,'-B','-m','unittest','discover','-s',HERE,'-p','test_observe.py'],ROOT,256,30)
        require(not stdout and b'Ran 6 tests' in stderr and stderr.rstrip().endswith(b'OK'),'Python controls failed')
        receipt['java_observer_controls']=3;receipt['python_tests']=6;receipt['malformed_observation_variants']=21
        props={'required':required,'dump':dump,'result':out/'observation.json','session':request['session'],'challenge':request['challenge'],'requestSha256':sha(out/'request.json')}
        argv=[java/'bin/java.exe','-Xmx1536M','-XX:+DisableAttachMechanism','-javaagent:'+str(observer),*[f'-Drustcraft.fresh.{k}={v}' for k,v in props.items()],'-cp',str(classes)+os.pathsep+cp,'net.minecraft.launchwrapper.Launch','--tweakClass','com.rustcraft.fresh.bootstrap.FreshTweaker','--gameDir',game]
        stdout,stderr=run('observe',argv,game,3072,180)
        require(stdout.count(b'RUSTCRAFT_FRESH_DEFINITIONS_COMPLETE')==1,'fresh completion marker')
        observation=load(out/'observation.json')
        rows=validate_observation(observation,request,m['required_classes'],dump,sha(out/'request.json'))
        identities={}
        for start in range(0,len(rows),16):
            batch=rows[start:start+16];data,err=run('identity-'+str(start),[java/'bin/java.exe','-Xmx384M','-cp',str(identity_classes)+os.pathsep+m['identity_asm'],'com.rustcraft.coremod.CanonicalClassIdentityV2',*[local(dump,r['file']) for r in batch]],out,768,45)
            require(not err,'identity stderr');lines=data.decode('utf-8').splitlines();require(len(lines)==len(batch),'identity output count')
            for row,line in zip(batch,lines):
                identity=parse_json(line);require(isinstance(identity,list) and len(identity)==5 and identity[:2]==['CANONICAL_ID_V2',row['name']] and identity[4]==row['raw_sha256'],'V2 identity raw/name binding')
                identities[row['name']]={'schema':identity[0],'class_name':identity[1],'semantic_sha256':identity[2],'declaration_order_sha256':identity[3],'raw_sha256':identity[4]}
        write(out/'v2-identities.json',identities)
        receipt.update(definition_count=len(rows),identities_sha256=sha(out/'v2-identities.json'),observation_sha256=sha(out/'observation.json'))
        after=inventory(game,m['roots'],m['files']);receipt['private_inventory_after']=after
        require(after==m['inventory'],'private runtime/config drift during observation; preserve, do not qualify')
        require(validate_manifest(m)==(root,java),'input drift')
        require(sha(manifest_path)==receipt['manifest_sha256'] and before==sources(),'manifest/source drift')
        require(compiled=={p.relative_to(classes).as_posix():sha(p) for p in classes.rglob('*.class')} and sha(observer)==receipt['observer_sha256'],'compiled tool drift')
        require(control_hashes=={p.relative_to(control_classes).as_posix():sha(p) for p in control_classes.rglob('*.class')},'compiled control drift')
        require(identity_hashes=={p.relative_to(identity_classes).as_posix():sha(p) for p in identity_classes.rglob('*.class')},'compiled identity drift')
        receipt['status']='PASS'
    except Exception as error:receipt['error']=type(error).__name__+': '+str(error)
    finally:
        receipt['sources_after']=sources();receipt['guard_after']=inspect(ROOT)
        if receipt['guard_after']['status']!='PASS' or receipt['sources_after']!=before:receipt.update(status='FAIL',error='isolation or source drift')
        receipt['elapsed_seconds']=time.monotonic()-started;write(out/'receipt.json',receipt)
    print(json.dumps({'status':receipt['status'],'receipt':str(out/'receipt.json'),'sha256':sha(out/'receipt.json'),'error':receipt.get('error')}))
    return int(receipt['status']!='PASS')

def main():
    p=argparse.ArgumentParser(description=__doc__);sub=p.add_subparsers(dest='mode',required=True)
    a=sub.add_parser('prepare');a.add_argument('--runtime-root',type=Path,required=True);a.add_argument('--java-home',type=Path,required=True);a.add_argument('--id',required=True)
    for name in ['forge','vanilla','asm','launchwrapper']:a.add_argument('--'+name,required=True)
    a.add_argument('--identity-asm',type=Path,required=True)
    a.add_argument('--roots',nargs='+',default=['libraries','mods','config','scripts']);a.add_argument('--file',action='append',default=[]);a.add_argument('--hooks',type=Path,required=True);a.add_argument('--extra-class',action='append',default=[])
    a.add_argument('--manifest',type=Path,required=True)
    a=sub.add_parser('observe');a.add_argument('--manifest',type=Path,required=True);a.add_argument('--output',type=Path)
    args=p.parse_args();return prepare(args) if args.mode=='prepare' else observe(args)
if __name__=='__main__':raise SystemExit(main())
