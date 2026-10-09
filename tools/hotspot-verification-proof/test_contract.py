from copy import deepcopy
import json
from pathlib import Path
import sys
import unittest
from contract import validate_row,proposal_pair,frame_types


class Controls(unittest.TestCase):
    rows=None
    fixtures=None
    known=None

    def check(self,row,status="VERIFIED_BY_PINNED_LINK_TRIGGER"):
        original=self.rows["valid-object"]
        validate_row(row,original["session"],original["challenge"],"valid-object",status,original["expected_sha256"],frame_types(self.fixtures["valid-object"]["dump"]))

    def test_bound_positive(self):self.check(self.rows["valid-object"])

    def test_wrong_session_challenge_schema_hash(self):
        for field in ("session","challenge","schema","actual_sha256","expected_sha256","mode"):
            row=deepcopy(self.rows["valid-object"]);row[field]="substituted"
            with self.subTest(field=field),self.assertRaises(ValueError):self.check(row)

    def test_define_without_trigger_is_not_verification(self):
        for field in ("trigger_started","trigger_returned"):
            row=deepcopy(self.rows["valid-object"]);row[field]=False
            with self.assertRaises(ValueError):self.check(row)

    def test_effective_flags_boolean_types_and_initializer(self):
        for field in ("verify_local","verify_remote","target_initialized_after","production_authority","defining_loader_is_exact"):
            row=deepcopy(self.rows["valid-object"]);row[field]=not row[field]
            with self.subTest(field=field),self.assertRaises(ValueError):self.check(row)
        row=deepcopy(self.rows["valid-object"]);row["defined"]=1
        with self.assertRaises(ValueError):self.check(row)

    def test_extra_or_missing_schema(self):
        row=deepcopy(self.rows["valid-object"]);row["authority_override"]=True
        with self.assertRaises(ValueError):self.check(row)
        del row["authority_override"];del row["verify_local"]
        with self.assertRaises(ValueError):self.check(row)

    def test_allowed_fixture_relations(self):
        for before,after in (("valid-top","valid-refined"),("valid-object","valid-refined"),("valid-object","valid-max")):
            self.assertEqual("FIXTURE_RELATION_ONLY",proposal_pair(self.fixtures[before],self.fixtures[after],self.known)["status"])

    def test_verified_missing_type_still_unqualified(self):
        row=self.rows["null-missing-type"]
        self.assertEqual("VERIFIED_BY_PINNED_LINK_TRIGGER",row["status"])
        self.assertIn("missing/Unknown",frame_types(self.fixtures["null-missing-type"]["dump"]))
        self.assertNotIn("missing.Unknown",row["verification_loader_requests"])
        self.assertIn("missing.Unknown",row["loader_requests"])
        self.assertFalse(row["frame_type_binding_complete"])
        with self.assertRaises(ValueError):proposal_pair(self.fixtures["null-object"],self.fixtures["null-missing-type"],self.known)

    def test_code_and_metadata_never_waived(self):
        for field in (0,1,2,3,4,5,6,7,8,9,11,12,13,15,16,17,18,19):
            row=deepcopy(self.fixtures["valid-refined"]);row["dump"][16][0][field]=["mutation"]
            with self.subTest(field=field),self.assertRaises(ValueError):proposal_pair(self.fixtures["valid-object"],row,self.known)


if __name__=="__main__":
    root=Path(sys.argv[1]);Controls.rows=json.loads((root/"proof-rows.json").read_text());Controls.fixtures=json.loads((root/"parser/classes.json").read_text())
    Controls.known={b[0] for row in Controls.rows.values() for b in row.get("frame_type_bindings",[]) if b[1].startswith("BOUND_")}
    result=unittest.TextTestRunner(stream=sys.stdout,verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(Controls))
    raise SystemExit(not result.wasSuccessful())
