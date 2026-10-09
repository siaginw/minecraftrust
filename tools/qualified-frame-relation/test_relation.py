"""Model/adversarial controls. Constructed witnesses are NOT actual JVM evidence."""
from copy import deepcopy
import hashlib
from pathlib import Path
import sys
import unittest

from common import strict,digest
from frames import relation,frame_inventory
from structural import prove,request,unchanged_plan
from witness import evaluate,assignable,phase_graph


def h(text):return hashlib.sha256(("MODEL_ONLY:"+text).encode()).hexdigest()


def fixture_hooks():
    return [dict(id="W01",hook_type="WRITE_BEGIN",method="set",descriptor="(I)V",**{"class":"fixture.Writer"},fingerprint={"kind":"DECLARATION"}),
            dict(id="W36",hook_type="PRIVATE_BUILD_BEGIN",method="<init>",descriptor="()V",**{"class":"fixture.Writer"},fingerprint={"kind":"DECLARATION"})]


def model_evidence(req):
    """Fake in-memory object graphs solely to adversarially test the validator.

    This helper deliberately never writes an acquisition receipt or witness file.
    It cannot be used by the CLI to qualify a real class corpus.
    """
    witness=dict(schema="QUALIFIED_FRAME_WITNESS_V1",session=req["session"],challenge=req["challenge"],request_sha256=digest(req),
                 collector_sources_sha256=h("collector"),vm_inventory_sha256=h("vm"),phases={},loader_pairs=[],scope={"model":"OFFLINE_BYTECODE_EXECUTION_V1"})
    targets={r["name"]:r for r in req["classes"]}
    for phase in ("pre","post"):
        boot,custom=phase+":bootstrap",phase+":fixture"
        loaders=[dict(id=ident,parent=parent,implementation_sha256=h(label+"impl"),configuration_sha256=h(label+"config"),policy_sha256=h(label+"policy")) for ident,parent,label in ((boot,None,"bootstrap"),(custom,boot,"fixture"))]
        nodes={}
        def add(name):
            if name in nodes:return nodes[name]["id"]
            ident=phase+":class:"+name
            kind="array" if name.startswith("[") else "primitive" if name in "BCDFIJSZV" and len(name)==1 else "class"
            loader=custom if name in targets else boot
            interface=name in ("java/lang/Cloneable","java/io/Serializable")
            node=dict(id=ident,name=name,kind=kind,is_interface=interface,loader=loader,raw_sha256=(targets[name][phase+"_sha256"] if name in targets else h(name)) if kind=="class" else None,
                      artifact_sha256=h("fixture-bundle" if name in targets else "rt.jar") if kind=="class" else None,super=None,interfaces=[],component=None)
            nodes[name]=node
            if kind=="array":
                component=name[1:]
                if component.startswith("L"):component=component[1:-1]
                node["component"]=add(component);node["loader"]=nodes[component]["loader"]
                node["super"]=add("java/lang/Object");node["interfaces"]=[add("java/lang/Cloneable"),add("java/io/Serializable")]
            elif kind=="class" and name!="java/lang/Object" and not interface:node["super"]=add("java/lang/Object")
            return ident
        resolutions=[]
        for name in req["types"]:
            missing=name=="missing/Unknown"
            resolutions.append(dict(type=name,class_id=None if missing else add(name),error="java.lang.ClassNotFoundException" if missing else None))
        data=dict(loaders=loaders,classes=list(nodes.values()),resolutions=resolutions,
                  verification=[dict(name=name,class_id=add(name),raw_sha256=row[phase+"_sha256"],status="VERIFIED",trigger="PINNED_HOTSPOT_GET_DECLARED_METHODS_V1",verify_local=True,verify_remote=True,initialized=False) for name,row in targets.items()],assignability=[])
        graph=phase_graph(phase,data,req,[])
        for query in req["assignability"]:
            a,b=(graph["resolutions"][query[k]] for k in ("source","target"))
            data["assignability"].append(dict(**query,value=None if a is None or b is None else assignable(graph,a,b)))
        witness["phases"][phase]=data
    witness["loader_pairs"]=[dict(pre="pre:"+x,post="post:"+x) for x in ("bootstrap","fixture")]
    policies={}
    for name in ("observers","loader_effects"):
        policy={field:h(name+field) for field in ("policy_sha256","inventory_sha256","evidence_sha256")}
        witness["scope"][name]=dict(status="CLOSED",**policy);policies[name]=policy
    receipt=dict(schema="QUALIFIED_FRAME_COLLECTOR_RECEIPT_V1",session=req["session"],challenge=req["challenge"],request_sha256=digest(req),witness_sha256=digest(witness),
                 collector_sources_sha256=h("collector"),vm_inventory_sha256=h("vm"),exit_code=0,stderr_sha256=hashlib.sha256(b"").hexdigest(),fresh_processes=True,inputs_before_sha256=h("inputs"),inputs_after_sha256=h("inputs"))
    trust=dict(schema="QUALIFIED_FRAME_TRUST_V1",session=req["session"],challenge=req["challenge"],request_sha256=digest(req),witness_sha256=digest(witness),collector_receipt_sha256=digest(receipt),
               collector_sources_sha256=h("collector"),vm_inventory_sha256=h("vm"),observer_policy=policies["observers"],loader_effect_policy=policies["loader_effects"])
    return witness,trust,receipt


def repin(witness,trust,receipt):
    # Model controls deliberately bypass byte authentication to exercise deeper
    # graph rules. Real trust must never be derived from a witness's own claims.
    receipt["witness_sha256"]=trust["witness_sha256"]=digest(witness);trust["collector_receipt_sha256"]=digest(receipt)


class Controls(unittest.TestCase):
    fixture=None;clean=None

    def setUp(self):
        self.structure=prove({"fixture/Writer":self.fixture["pre"]},{"fixture/Writer":self.fixture["valid"]},fixture_hooks())
        self.req=request(self.structure,"1"*32,"2"*64,{key:h(key) for key in ("profile_sha256","manifest_sha256","observation_sha256","plan_sha256","validator_sources_sha256")})
        self.w,self.t,self.r=model_evidence(self.req)

    def run_model(self,repin_first=False):
        if repin_first:repin(self.w,self.t,self.r)
        return evaluate(self.req,digest(self.req),self.structure,self.w,digest(self.w),self.t,self.r,digest(self.r))

    def test_model_positive_is_explicitly_scoped(self):
        row=self.run_model();self.assertEqual("PASS",row["status"]);self.assertFalse(row["production_authority"]);self.assertFalse(row["live_writer_closure"])

    def test_no_witness_no_pass(self):self.assertEqual("INCOMPLETE",evaluate(self.req,digest(self.req),self.structure)["status"])

    def test_wrong_root_and_boolean_shapes_fail_structurally(self):
        for malformed in ([],None,"witness",42):
            if malformed is None:continue  # absent evidence has its own INCOMPLETE contract
            self.assertEqual("FAIL",evaluate(self.req,digest(self.req),self.structure,malformed,digest(malformed),self.t,self.r,digest(self.r))["status"])
        self.w["phases"]["post"]["verification"][0]["verify_local"]=1
        self.assertEqual("FAIL",self.run_model(True)["status"])

    def test_structure_and_request_cannot_disagree(self):
        self.req["types"].remove("java/lang/Throwable")
        self.assertEqual("FAIL",self.run_model(True)["status"])

    def test_forged_bytes_cannot_update_trust(self):
        self.w["phases"]["post"]["verification"][0]["initialized"]=True
        self.assertEqual("FAIL",self.run_model()["status"])

    def test_stale_foreign_session_challenge_request(self):
        for key in ("session","challenge","request_sha256"):
            with self.subTest(key=key):
                old=self.w[key];self.w[key]="0"*len(old);self.assertEqual("FAIL",self.run_model(True)["status"]);self.w[key]=old

    def test_vm_source_input_or_process_drift(self):
        for key,value in (("collector_sources_sha256",h("foreign")),("vm_inventory_sha256",h("foreign")),("exit_code",1),("fresh_processes",False),("inputs_after_sha256",h("changed")),("stderr_sha256",h("warning"))):
            with self.subTest(key=key):
                old=self.r[key];self.r[key]=value;self.assertEqual("FAIL",self.run_model(True)["status"]);self.r[key]=old

    def test_wrong_flags_trigger_bytes_and_initializer(self):
        row=self.w["phases"]["post"]["verification"][0]
        for key,value in (("verify_local",False),("verify_remote",False),("trigger","Class.forName(false)"),("raw_sha256",h("wrong")),("initialized",True),("status","REJECTED")):
            with self.subTest(key=key):
                old=row[key];row[key]=value;self.assertEqual("FAIL",self.run_model(True)["status"]);row[key]=old

    def test_missing_verification_is_incomplete(self):
        self.w["phases"]["pre"]["verification"][0]["status"]="UNAVAILABLE"
        self.assertEqual("INCOMPLETE",self.run_model(True)["status"])

    def test_foreign_exact_class_object_rejected(self):
        self.w["phases"]["pre"]["verification"][0]["class_id"]="pre:class:java/lang/Object"
        self.assertEqual("FAIL",self.run_model(True)["status"])

    def test_loader_parent_implementation_configuration_drift(self):
        row=self.w["phases"]["post"]["loaders"][1]
        for key,value in (("parent",row["id"]),("implementation_sha256",h("other")),("configuration_sha256",h("other")),("policy_sha256",h("other"))):
            with self.subTest(key=key):
                old=row[key];row[key]=value;self.assertEqual("FAIL",self.run_model(True)["status"]);row[key]=old

    def test_graph_correspondence_is_not_caller_boolean(self):
        self.w["loader_pairs"][0]["equivalent"]=True
        self.assertEqual("FAIL",self.run_model(True)["status"])

    def test_dependency_bytes_artifact_and_super_drift(self):
        row=next(x for x in self.w["phases"]["post"]["classes"] if x["name"]=="java/lang/Throwable")
        for key,value in (("raw_sha256",h("changed")),("artifact_sha256",h("foreign")),("super",row["id"])):
            with self.subTest(key=key):
                old=row[key];row[key]=value;self.assertEqual("FAIL",self.run_model(True)["status"]);row[key]=old

    def test_omitted_extra_duplicate_resolution_rejected(self):
        rows=self.w["phases"]["post"]["resolutions"]
        old=deepcopy(rows);rows.pop();self.assertEqual("FAIL",self.run_model(True)["status"])
        rows[:]=old+[deepcopy(old[0])];self.assertEqual("FAIL",self.run_model(True)["status"])

    def test_no_scope_closure_from_verification(self):
        for key in ("observer_policy","loader_effect_policy"):
            old=self.t[key];self.t[key]=None;self.assertEqual("INCOMPLETE",self.run_model(True)["status"]);self.t[key]=old
        self.w["scope"]["observers"]["evidence_sha256"]=h("forged")
        self.assertEqual("FAIL",self.run_model(True)["status"])

    def test_actual_null_missing_type_counterexample_remains_incomplete(self):
        self.structure["records"][0]["types"].append("missing/Unknown")
        self.req["types"]=sorted(self.req["types"]+["missing/Unknown"])
        self.w,self.t,self.r=model_evidence(self.req)
        row=self.run_model();self.assertEqual("INCOMPLETE",row["status"])
        self.assertTrue(any(b["kind"]=="UNRESOLVED_FRAME_OR_TARGET_TYPE" for b in row["blockers"]))

    def test_reference_queries_independently_recomputed(self):
        self.add_queries(["java/lang/String"],[("java/lang/String","java/lang/Object"),("java/lang/Object","java/lang/String")])
        self.assertEqual("PASS",self.run_model()["status"])
        self.w["phases"]["post"]["assignability"][0]["value"]=not self.w["phases"]["post"]["assignability"][0]["value"]
        self.assertEqual("FAIL",self.run_model(True)["status"])

    def add_queries(self,types,pairs):
        row=self.structure["records"][0];row["types"]=sorted(set(row["types"]+types));row["questions"]=[dict(source=a,target=b) for a,b in sorted(pairs)]
        self.req=request(self.structure,self.req["session"],self.req["challenge"],self.req["bindings"])
        self.w,self.t,self.r=model_evidence(self.req)

    def test_arrays_primitive_rank_component_and_interfaces(self):
        self.add_queries(["[I","[J","[Ljava/lang/String;","[Ljava/lang/Object;","[[Ljava/lang/String;"],[("[Ljava/lang/String;","[Ljava/lang/Object;"),("[Ljava/lang/Object;","[Ljava/lang/String;")])
        self.assertEqual("PASS",self.run_model()["status"])
        node=next(x for x in self.w["phases"]["post"]["classes"] if x["name"]=="[I")
        node["component"]="post:class:J"
        self.assertEqual("FAIL",self.run_model(True)["status"])

    def test_incomparable_reference_relation_blocks(self):
        self.add_queries(["java/lang/String"],[("java/lang/String","java/lang/Throwable"),("java/lang/Throwable","java/lang/String")])
        self.assertEqual("INCOMPLETE",self.run_model()["status"])

    def test_original_actual_classfile_mutations_still_blocked(self):
        for name in self.fixture:
            if name in ("pre","valid"):continue
            with self.subTest(name=name):
                row=prove({"fixture/Writer":self.fixture["pre"]},{"fixture/Writer":self.fixture[name]},fixture_hooks())
                self.assertIn(row["status"],("FAIL","INCOMPLETE"))

    def test_clean_without_witness_never_qualifies(self):
        self.assertNotEqual("FAIL",self.clean["status"])
        req=request(self.clean,"3"*32,"4"*64,self.req["bindings"])
        self.assertEqual("INCOMPLETE",evaluate(req,digest(req),self.clean)["status"])


def method(locals,stack=None,name="m",instructions=None,maxlocals=4):
    result=[None]*20;result[0]=name;result[1]="()V";result[3]=8;result[10]=4;result[11]=maxlocals
    result[12]=instructions or [[0,[],[]],[0,[],[]],[177,[],[]]];result[13]=[]
    result[14]=[[2,-1,locals,stack or []]]
    return result


class TypedControls(unittest.TestCase):
    def compare(self,a,b):return relation(a,b,unchanged_plan(a))

    def test_primitive_top_refinements(self):
        for tag in (1,2,3,4,5):
            row=self.compare(method([]),method([["verification_tag",tag]]));self.assertFalse(row["blockers"])
    def test_live_primitive_change_blocks(self):
        self.assertTrue(self.compare(method([["verification_tag",1]]),method([["verification_tag",2]]))["blockers"])
    def test_category_two_split_blocks(self):
        self.assertTrue(self.compare(method([["verification_tag",4]]),method([["verification_tag",0],["verification_tag",1]]))["blockers"])
    def test_stack_top_relaxation_forbidden(self):
        self.assertTrue(self.compare(method([],[["verification_tag",0]]),method([],[["verification_tag",1]]))["blockers"])
    def test_new_uses_instruction_map_not_boundary(self):
        a=method([["uninitialized",0]],instructions=[[187,["java/lang/Object"],[]],[0,[],[]],[177,[],[]]])
        b=method([["uninitialized",1]],instructions=[[0,[],[]],[187,["java/lang/Object"],[]],[177,[],[]]])
        plan=unchanged_plan(a);plan["original_at"][0]=1
        self.assertFalse(relation(a,b,plan)["blockers"])
        b[14][0][2][0][1]=0
        with self.assertRaises(ValueError):relation(a,b,plan)
    def test_different_new_allocation_cannot_alias(self):
        instructions=[[187,["java/lang/Object"],[]],[187,["java/lang/Object"],[]],[177,[],[]]]
        self.assertTrue(self.compare(method([["uninitialized",0]],instructions=instructions),method([["uninitialized",1]],instructions=instructions))["blockers"])
    def test_uninitialized_this_preserved(self):
        a=method([["verification_tag",6]],name="<init>");self.assertFalse(self.compare(a,deepcopy(a))["blockers"])
        b=deepcopy(a);b[14][0][2]=[["object","fixture/Writer"]];self.assertTrue(self.compare(a,b)["blockers"])
    def test_invalid_tag_and_wide_capacity(self):
        for raw,maximum in (([["verification_tag",7]],4),([["verification_tag",6]],4),([["verification_tag",4]],1)):
            with self.assertRaises(ValueError):frame_inventory(method(raw,maxlocals=maximum))
    def test_extra_frame_boundary_blocks(self):
        a=method([]);b=deepcopy(a);b[14].insert(0,[1,-1,[],[]]);self.assertTrue(self.compare(a,b)["blockers"])
    def test_expanded_frame_budget_checked_before_allocation(self):
        row=method([],maxlocals=65535);row[14]=[[i,-1,[],[]] for i in range(9)]
        with self.assertRaisesRegex(ValueError,"slot budget"):frame_inventory(row)


if __name__=="__main__":
    root=Path(sys.argv[1]);Controls.fixture=strict((root/"fixtures/classes.json").read_bytes());Controls.clean=strict((root/"clean/structure.json").read_bytes())
    suite=unittest.TestSuite([unittest.defaultTestLoader.loadTestsFromTestCase(c) for c in (Controls,TypedControls)])
    result=unittest.TextTestRunner(stream=sys.stdout,verbosity=2).run(suite)
    raise SystemExit(not result.wasSuccessful())
