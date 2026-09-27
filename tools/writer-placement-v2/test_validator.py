"""Bounded controls over independently generated, doubly parsed classfiles."""
from copy import deepcopy
import json
from pathlib import Path
import sys
import unittest

from classfile import offsets, method_offsets
from io_utils import strict
from validate import request_schema, inside_target
from validator import Failure, ins, relocation, verify, verify_method


def fixture_hooks():
    return [dict(id="W01",hook_type="WRITE_BEGIN",method="set",descriptor="(I)V",
                 **{"class":"fixture.Writer"},fingerprint={"kind":"DECLARATION"}),
            dict(id="W36",hook_type="PRIVATE_BUILD_BEGIN",method="<init>",descriptor="()V",
                 **{"class":"fixture.Writer"},fingerprint={"kind":"DECLARATION"})]


class Controls(unittest.TestCase):
    fixtures = None
    clean = None

    def audit(self,post="valid",hooks=None,pre=None):
        return verify({"fixture/Writer":pre or self.fixtures["pre"]},
                      {"fixture/Writer":self.fixtures[post]},hooks or fixture_hooks())

    def test_independent_java_positive(self):
        self.assertEqual("PASS",self.audit()["status"])

    def test_actual_classfile_mutations_all_block(self):
        self.assertEqual(29,len(self.fixtures))
        for name in self.fixtures:
            if name in ("pre","valid"):continue
            with self.subTest(name=name):
                self.assertEqual("INCOMPLETE" if name=="frame-local" else "FAIL",self.audit(name)["status"])

    def test_missing_and_extra_inventory(self):
        for pre,post in (({},{}),({"fixture/Writer":self.fixtures["pre"]},{})):
            with self.assertRaises(Failure):verify(pre,post,fixture_hooks())

    def test_duplicate_and_missing_hook(self):
        hooks=fixture_hooks()
        with self.assertRaises(Failure):self.audit(hooks=hooks+[hooks[0]])
        self.assertEqual("FAIL",self.audit(hooks=hooks[:1])["status"])

    def test_unknown_shape_and_fingerprint(self):
        hooks=fixture_hooks();hooks[0]["hook_type"]="UNKNOWN"
        self.assertEqual("FAIL",self.audit(hooks=hooks)["status"])
        hooks=fixture_hooks();hooks[0]["fingerprint"]={"kind":"UNKNOWN"}
        self.assertEqual("FAIL",self.audit(hooks=hooks)["status"])

    def test_wrong_descriptor_in_plan(self):
        hooks=fixture_hooks();hooks[0]["descriptor"]="(J)V"
        self.assertEqual("FAIL",self.audit(hooks=hooks)["status"])

    def test_source_drift_cannot_be_hidden_by_post(self):
        before=deepcopy(self.fixtures["pre"])
        next(m for m in before["dump"][16] if m[0]=="set")[12][1][1][0]=0
        self.assertEqual("FAIL",self.audit(pre=before)["status"])

    def test_frame_token_omission_never_passes(self):
        row=deepcopy(self.fixtures["valid"])
        next(m for m in row["dump"][16] if m[0]=="set")[14][0][2].pop()
        result=verify({"fixture/Writer":self.fixtures["pre"]},{"fixture/Writer":row},fixture_hooks())
        self.assertNotEqual("PASS",result["status"])

    def test_original_annotations_and_debug_preserved(self):
        for field in (2,4,5,6,7,8,9,15,16,17,18,19):
            row=deepcopy(self.fixtures["valid"])
            m=next(m for m in row["dump"][16] if m[0]=="set")
            m[field]=["undeclared"]
            with self.subTest(field=field):
                self.assertEqual("FAIL",verify({"fixture/Writer":self.fixtures["pre"]},{"fixture/Writer":row},fixture_hooks())["status"])

    def test_pool_bootstrap_and_duplicate_changes(self):
        for kind in ("drop","duplicate","bootstrap"):
            row=deepcopy(self.fixtures["valid"])
            if kind=="drop":row["dump"][17].remove(self.fixtures["pre"]["dump"][17][0])
            elif kind=="duplicate":row["dump"][17].append(next(x for x in row["dump"][17] if "writerBegin" in x))
            else:row["dump"][18].append("undeclared")
            self.assertEqual("FAIL",verify({"fixture/Writer":self.fixtures["pre"]},{"fixture/Writer":row},fixture_hooks())["status"])

    def test_complete_switch_targets_relocate(self):
        mapping={0:3,1:7,2:10,3:14}
        self.assertEqual(ins(170,2,4,14,[3,7,10]),relocation(ins(170,2,4,3,[0,1,2]),mapping))
        self.assertEqual(ins(171,[7,12],14,[3,10]),relocation(ins(171,[7,12],3,[0,2]),mapping))

    def test_offsets_are_actual_variable_width_bcis(self):
        self.assertEqual([0,3,7,10],offsets(bytes([19,0,1,196,21,1,0,132,1,255,177])))
        for code in (bytes([19]),bytes([196,0]),bytes([202]),bytes([170,0,0,0])):
            with self.assertRaises(ValueError):offsets(code)

    def test_raw_scanner_rejects_bad_class(self):
        for raw in (b"",b"xxxx",b"\xca\xfe\xba\xbe"+bytes(6)):
            with self.assertRaises((ValueError,KeyError)):method_offsets(raw)

    def test_strict_json_and_output_isolation(self):
        for text in ('{"a":1,"a":2}','[NaN]','[Infinity]'):
            with self.assertRaises(ValueError):strict(text)
        with self.assertRaises(Failure):inside_target(Path("D:/minecraftrust/target/forbidden.json"))

    def test_request_rejects_empty_or_wrong_types(self):
        for request in (None,[],{},"wrong",42):
            with self.assertRaises(Failure):request_schema(request,fixture_hooks())

    def test_all_clean_required_hooks_missing_call_blocked(self):
        pre,post,hooks=self.clean
        self.assertEqual(66,len(hooks))
        for hook in hooks:
            name=hook["class"].replace(".","/")
            before=next(m for m in pre[name]["dump"][16] if m[:2]==[hook["method"],hook["descriptor"]])
            after=deepcopy(next(m for m in post[name]["dump"][16] if m[:2]==before[:2]))
            index=next(i for i,n in enumerate(after[12]) if n[0]==184 and n[1][0]=="com/rustcraft/bridge/capture/LiveWriterHooks")
            after[12].pop(index)
            with self.subTest(id=hook["id"]):
                with self.assertRaises(Failure):verify_method(name,pre[name]["dump"][5],before,after,hook,pre[name]["offsets"][before[0]+before[1]])

    def test_nested_handler_order_must_be_exact(self):
        pre,post,hooks=self.clean
        hook=next(h for h in hooks if h["id"]=="W56");name=hook["class"].replace(".","/")
        before=next(m for m in pre[name]["dump"][16] if m[:2]==[hook["method"],hook["descriptor"]])
        after=deepcopy(next(m for m in post[name]["dump"][16] if m[:2]==before[:2]))
        after[13][-2:]=list(reversed(after[13][-2:]))
        with self.assertRaises(Failure):verify_method(name,pre[name]["dump"][5],before,after,hook,pre[name]["offsets"][before[0]+before[1]])

    def test_actual_clean_unproved_frames_remain_incomplete(self):
        pre,post,hooks=self.clean
        result=verify(pre,post,hooks)
        self.assertEqual("INCOMPLETE",result["status"])
        self.assertTrue(any(c["unhooked_method_differences"] for c in result["records"]))


if __name__=="__main__":
    directory=Path(sys.argv[1])
    Controls.fixtures=strict((directory/"fixture-dumps/classes.json").read_bytes())
    Controls.clean=(strict((directory/"clean/pre/classes.json").read_bytes()),strict((directory/"clean/post/classes.json").read_bytes()),strict((directory/"hooks.json").read_bytes()))
    result=unittest.TextTestRunner(stream=sys.stdout,verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(Controls))
    raise SystemExit(not result.wasSuccessful())
