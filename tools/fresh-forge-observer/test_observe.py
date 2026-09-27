"""Malformed/freshness/loader controls, independent of launching either runtime."""
import copy,hashlib,json,tempfile,unittest
from unittest.mock import patch
from pathlib import Path
from observe import ROOT,inventory,local,validate_manifest,validate_observation,sha,load_hashed,hook_inputs

class Contracts(unittest.TestCase):
    def setUp(self):
        (ROOT/'target/fresh-forge-observer-tests').mkdir(exist_ok=True)
        self.temp=tempfile.TemporaryDirectory(dir=ROOT/'target/fresh-forge-observer-tests')
        self.root=Path(self.temp.name);self.addCleanup(self.temp.cleanup)
        self.request={'session':'session','challenge':'challenge'}
        self.loader={'chain':[{'class':'fixture.Loader','process_local_id':1}],'bootstrap_terminus':True}
        path=self.root/'loader-1/example/Fixture.class';path.parent.mkdir(parents=True);path.write_bytes(b'actual fixture bytes')
        row={'name':'example/Fixture','file':'loader-1/example/Fixture.class','bytes':path.stat().st_size,'raw_sha256':hashlib.sha256(path.read_bytes()).hexdigest(),'loader':copy.deepcopy(self.loader),'code_source':'file:fixture.jar'}
        tool={'class':'fixture.Transformer','loader':copy.deepcopy(self.loader),'code_source':'file:fixture.jar'}
        self.value={'schema':'FRESH_FORGE_DEFINITION_OBSERVATION_V1',**self.request,'request_sha256':'requesthash','java_version':'test','java_runtime_version':'test','forge':{},'transformers_before':[tool],'transformers_after':[copy.deepcopy(tool)],'coremods':[copy.deepcopy(tool)],'definitions':[copy.deepcopy(row)],'all_loader_definitions':[row],'mod_lifecycle_executed':False,'server_main_called':False,'production_authority':False,'scope':'FRESH_OFFLINE_DEFINITIONS_ONLY_NO_WRITER_OR_LIFECYCLE_CLOSURE'}
    def check(self,value):return validate_observation(value,self.request,['example/Fixture'],self.root,'requesthash')
    def test_actual_bytes_and_loader_selection(self):
        self.assertEqual(self.check(self.value),self.value['definitions'])
        other=copy.deepcopy(self.value['all_loader_definitions'][0]);other['loader']['chain'][0]['process_local_id']=2;other['file']='loader-2/example/Fixture.class'
        dest=self.root/other['file'];dest.parent.mkdir(parents=True);dest.write_bytes((self.root/self.value['definitions'][0]['file']).read_bytes())
        self.value['all_loader_definitions'].append(other)
        self.assertEqual(self.check(self.value),self.value['definitions'])
    def test_changed_file_refused(self):
        (self.root/self.value['definitions'][0]['file']).write_bytes(b'changed fixture')
        with self.assertRaises(ValueError):self.check(self.value)
    def test_extra_file_refused(self):
        (self.root/'extra.class').write_bytes(b'extra')
        with self.assertRaises(ValueError):self.check(self.value)
    def test_malformed_and_stale_observations(self):
        variants={}
        def add(name,edit):
            value=copy.deepcopy(self.value);edit(value);variants[name]=value
        add('extra-field',lambda v:v.update(qualified=True))
        add('wrong-schema',lambda v:v.update(schema='OLD'))
        add('stale-session',lambda v:v.update(session='old'))
        add('foreign-challenge',lambda v:v.update(challenge='old'))
        add('request-swap',lambda v:v.update(request_sha256='other'))
        add('authority',lambda v:v.update(production_authority=True))
        add('lifecycle',lambda v:v.update(mod_lifecycle_executed=True))
        add('server',lambda v:v.update(server_main_called=True))
        add('scope',lambda v:v.update(scope='QUALIFIED'))
        add('chain-drift',lambda v:v.update(transformers_after=[]))
        add('missing',lambda v:v.update(definitions=[]))
        add('duplicate-selected',lambda v:v['definitions'].append(copy.deepcopy(v['definitions'][0])))
        add('unknown-selected',lambda v:v['definitions'][0].update(name='example/Foreign'))
        add('duplicate-observation',lambda v:v['all_loader_definitions'].append(copy.deepcopy(v['all_loader_definitions'][0])))
        add('hash-lie',lambda v:v['all_loader_definitions'][0].update(raw_sha256='0'*64))
        add('size-lie',lambda v:v['all_loader_definitions'][0].update(bytes=1))
        add('path-loader-mismatch',lambda v:v['all_loader_definitions'][0]['loader']['chain'][0].update(process_local_id=2))
        add('selected-loader-swap',lambda v:v['definitions'][0]['loader']['chain'][0].update(process_local_id=2))
        add('loader-id-reuse',lambda v:v['coremods'][0]['loader']['chain'][0].update(**{'class':'foreign.Loader'}))
        add('loader-parent-drift',lambda v:v['coremods'][0]['loader']['chain'].append({'class':'fixture.Parent','process_local_id':2}))
        add('loader-cycle',lambda v:v['all_loader_definitions'][0]['loader']['chain'].append(copy.deepcopy(v['all_loader_definitions'][0]['loader']['chain'][0])))
        for name,value in variants.items():
            with self.subTest(name=name),self.assertRaises(ValueError):self.check(value)
    def test_inventory_detects_addition_mutation_removal(self):
        (self.root/'mods').mkdir();p=self.root/'mods/test.jar';p.write_bytes(b'first')
        initial=inventory(self.root,['mods'],[])
        p.write_bytes(b'other');self.assertNotEqual(initial,inventory(self.root,['mods'],[]))
        p.write_bytes(b'first');(self.root/'mods/extra.jar').write_bytes(b'new');self.assertNotEqual(initial,inventory(self.root,['mods'],[]))
        p.unlink();self.assertNotEqual(initial,inventory(self.root,['mods'],[]))
    def test_paths_reject_escape(self):
        for name in ['../outside','C:/outside','dir\\file','/absolute','']:
            with self.subTest(name=name),self.assertRaises(ValueError):local(self.root,name)
    def diagnostic_manifest(self):
        for name in ['forge.jar','vanilla.jar','asm.jar','launch.jar','diagnostic.jar','study.jar']:(self.root/name).write_bytes(name.encode())
        recipe=self.root/'recipe.json';recipe.write_text(json.dumps({'kind':'RUSTCRAFT_V2_WRITER_PLAN_RECIPE','identity_mode':'CANONICAL_ID_V2','all_required_observed':True}))
        return {'schema':'FRESH_FORGE_OBSERVER_MANIFEST_V1','id':'fixture','runtime_root':str(self.root),'java_home':str(self.root),'roots':[],'files':['forge.jar','vanilla.jar','asm.jar','launch.jar'],'inventory':inventory(self.root,[],['forge.jar','vanilla.jar','asm.jar','launch.jar']),'forge':'forge.jar','vanilla':'vanilla.jar','asm':'asm.jar','launchwrapper':'launch.jar','required_classes':['example/Fixture'],'tools':{},'identity_asm':str(self.root/'asm.jar'),'diagnostic':{'mode':'OFFLINE_V2_HOOK_DEFINITIONS_ONLY','jar':str(self.root/'diagnostic.jar'),'jar_sha256':sha(self.root/'diagnostic.jar'),'srg_jar':str(self.root/'study.jar'),'srg_jar_sha256':sha(self.root/'study.jar'),'recipe':str(recipe),'recipe_sha256':sha(recipe)}}
    def test_diagnostic_requires_exact_mode_hashes_and_v2(self):
        value=self.diagnostic_manifest()
        with patch('observe.tool_pins',return_value={}):
            self.assertEqual(validate_manifest(value),(self.root,self.root))
            for key,bad in [('mode','LIVE'),('jar_sha256','0'*64),('srg_jar_sha256','0'*64),('recipe_sha256','0'*64),('unknown',True)]:
                changed=copy.deepcopy(value);changed['diagnostic'][key]=bad
                with self.subTest(key=key),self.assertRaises(ValueError):validate_manifest(changed)
            recipe=self.root/'recipe.json';recipe.write_text(json.dumps({'kind':'RUSTCRAFT_V2_WRITER_PLAN_RECIPE','identity_mode':'CANONICAL','all_required_observed':True}))
            value['diagnostic']['recipe_sha256']=sha(recipe)
            with self.assertRaises(ValueError):validate_manifest(value)
    def test_diagnostic_cannot_execute_original_or_unpinned_path(self):
        value=self.diagnostic_manifest()
        with patch('observe.tool_pins',return_value={}):
            for path in [str(ROOT/'Cargo.toml'),r'D:\minecraftrust\target\legacy.jar','relative.jar']:
                changed=copy.deepcopy(value);changed['diagnostic']['jar']=path
                with self.subTest(path=path),self.assertRaises(ValueError):validate_manifest(changed)

    def test_parsed_json_and_hash_share_one_read(self):
        first=b'{"value":1}';second=b'{"value":2}'
        with patch.object(Path,'read_bytes',side_effect=[first,second]) as read:
            value,pin=load_hashed(self.root/'swap.json')
        self.assertEqual(read.call_count,1);self.assertEqual(value,{'value':1})
        self.assertEqual(pin,hashlib.sha256(first).hexdigest())

    def test_hook_input_attempt_inventory_rejects_tampering(self):
        root=self.root/'inputs';root.mkdir();data=b'attempt';(root/'0000.bin').write_bytes(data)
        row='example/Fixture\t0000.bin\t'+hashlib.sha256(data).hexdigest()+'\n'
        index=root/'inputs.tsv';index.write_text(row,encoding='utf-8')
        self.assertEqual(hook_inputs(root,['example/Fixture'])['scope'],'ATTEMPT_BYTES_ONLY_NOT_DEFINED_CLASS')
        for changed in [row.replace('0000.bin','../escape.bin'),row.replace('example/Fixture','example/Foreign'),row+row,row.replace(hashlib.sha256(data).hexdigest(),'0'*64)]:
            index.write_text(changed,encoding='utf-8')
            with self.assertRaises(ValueError):hook_inputs(root,['example/Fixture'])
        index.write_text(row,encoding='utf-8');(root/'extra.bin').write_bytes(b'extra')
        with self.assertRaises(ValueError):hook_inputs(root,['example/Fixture'])

if __name__=='__main__':unittest.main()
