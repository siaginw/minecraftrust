"""Independent runner checks. Full class coverage lives in V2 mutation corpus."""
import unittest
from crosscheck import constant, differences, frame_value, java_json, parameter_annotations, type_annotations


class CrosscheckTests(unittest.TestCase):
    def test_json_does_not_rewrite_literal_backslash_n(self):
        self.assertEqual(java_json(["\\n", "\n", "😀"]), '["\\\\n","\\u000a","\\ud83d\\ude00"]')

    def test_nan_payloads_remain_distinct(self):
        self.assertNotEqual(constant(["double_bits", "7ff8000000000001"]), constant(["double_bits", "7ff8000000000002"]))

    def test_field_and_return_annotation_target_remain_distinct(self):
        field = type_annotations([[True, 0x13000000, None, "LMarker;", []]])
        returned = type_annotations([[True, 0x14000000, None, "LMarker;", []]])
        self.assertNotEqual(field, returned)

    def test_code_annotation_target_uses_instruction_position(self):
        value = [True, 0x47000002, None, "LMarker;", []]
        self.assertEqual(type_annotations([value], offset=9)[0][1], [0x47, 9, 2])

    def test_uninitialized_frame_target_is_retained(self):
        self.assertEqual(frame_value(["uninitialized", 7]), ["uninitialized", 7])

    def test_missing_field_is_disagreement(self):
        self.assertTrue(differences({"a": []}, {}))

    def test_operand_changes_are_disagreements(self):
        self.assertEqual(differences([16, 5], [16, 6])[0]["path"], "/1")

    def test_exact_asm_synthetic_padding_is_inverted(self):
        marker = [True, "Ljava/lang/Synthetic;", []]
        self.assertEqual(parameter_annotations("(I)V", [None, [[marker]]], [0, None]), [])

    def test_real_synthetic_annotation_is_preserved(self):
        marker = [True, "Ljava/lang/Synthetic;", []]
        self.assertEqual(parameter_annotations("(I)V", [None, [[marker, marker]]], [0, 1]), [[False, 0, ["Ljava/lang/Synthetic;", []]]])

    def test_unexplained_padding_is_rejected(self):
        with self.assertRaises(ValueError):
            parameter_annotations("(I)V", [None, [[]]], [0, None])


if __name__ == "__main__":
    unittest.main()
