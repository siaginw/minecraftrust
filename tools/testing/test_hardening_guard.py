import unittest

from tools.testing.hardening_guard import closed_body


class SourceGateControls(unittest.TestCase):
    def test_comments_and_spacing_do_not_change_closed_body(self):
        self.assertTrue(closed_body("public boolean gate() { /* gate */ return false; }",
                                    "public boolean gate()", "return false;"))

    def test_return_true_extra_statement_nested_code_and_comment_decoy_reject(self):
        for source in (
            "public boolean gate() { return true; }",
            "public boolean gate() { write(); return false; }",
            "public boolean gate() { if (flag) { return true; } return false; }",
            "// public boolean gate() { return false; }\npublic boolean gate() { return true; }",
        ):
            with self.subTest(source=source):
                self.assertFalse(closed_body(source, "public boolean gate()", "return false;"))


if __name__ == "__main__":
    unittest.main()
