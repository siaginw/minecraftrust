import json
import unittest
from reference import compare,expected,load,SCENARIOS

class ReferenceControls(unittest.TestCase):
    def fixture(self,scenario="ordered"):
        return list(expected("a"*32,"b"*64,scenario,17))
    def text(self,rows):return "\n".join(json.dumps(row) for row in rows)
    def test_golden_ordered_first_commit(self):
        row=self.fixture()[1]
        self.assertEqual(row["effects"],[[0,17,35],[1,18,37],[2,19,39],[3,20,41]])
        self.assertEqual(row["values"][4:],list(range(21,49)))
    def test_conflict_golden(self):
        rows=self.fixture("conflict")
        self.assertEqual([r["outcome"] for r in rows[1:9]],["Committed"]*4+["StaleInput"]*4)
        self.assertEqual(rows[4]["values"],rows[8]["values"])
    def test_error_and_lifecycle_golden_counts(self):
        for scenario,reason,count in [("cancel","Cancelled",4),("deadline","Deadline",32),("revoke","Revoked",32),("unload","Unloaded",32),("external","StaleInput",4),("budget","CpuBudget",4),("arithmetic","Arithmetic",4)]:
            self.assertEqual(sum(r["outcome"]==reason for r in self.fixture(scenario)),count)
    def test_first_divergence_even_after_reconvergence(self):
        rows=self.fixture();rows[1]["values"][0]+=1
        self.assertEqual(compare(self.text(rows),"a"*32,"b"*64,"ordered",17)["boundary"],1)
    def test_identity_order_missing_extra_and_types(self):
        rows=self.fixture();text=self.text(rows)
        self.assertEqual(compare(text,"c"*32,"b"*64,"ordered",17)["boundary"],0)
        self.assertEqual(compare(text,"a"*32,"c"*64,"ordered",17)["boundary"],0)
        rows[1],rows[2]=rows[2],rows[1];self.assertEqual(compare(self.text(rows),"a"*32,"b"*64,"ordered",17)["boundary"],1)
        for data in (rows[:-1],rows+[rows[-1]]):
            with self.assertRaises(ValueError):compare(self.text(data),"a"*32,"b"*64,"ordered",17)
        rows=self.fixture();rows[0]["production_authority"]=0
        self.assertIsNotNone(compare(self.text(rows),"a"*32,"b"*64,"ordered",17))
    def test_json_duplicate_and_nonfinite_rejected(self):
        for text in ('{"a":1,"a":2}','{"x":NaN}'):
            with self.assertRaises(ValueError):load(text)

if __name__=="__main__":unittest.main()
