"""Malformed/freshness/loader controls, independent of launching either runtime."""
import copy,hashlib,tempfile,unittest
from pathlib import Path
from observe import ROOT,inventory,local,validate_observation

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

if __name__=='__main__':unittest.main()
