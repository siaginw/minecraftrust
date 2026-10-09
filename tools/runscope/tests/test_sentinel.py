"""Sentinel-degradation lint tests (separate file — the fixture Java
strings are escape-heavy and live cleanly on their own)."""

import os
import shutil
import sys
import tempfile
import unittest

_HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(_HERE, ".."))

import bridge_lint  # noqa: E402

JAVA_BAD = (
    "public final class SomeHook {\n"
    "  long[] decode(Object o) {\n"
    "    try {\n"
    "      return realDecode(o);\n"
    "    } catch (Throwable t) {\n"
    "      return new long[]{0, 0, 0};\n"
    "    }\n"
    "  }\n"
    "}\n")

JAVA_OK = (
    "public final class SomeHook {\n"
    "  long[] decode(Object o) {\n"
    "    try {\n"
    "      return realDecode(o);\n"
    "    } catch (Throwable t) {\n"
    "      recordError(t);\n"
    "      return new long[]{0, 0, 0};\n"
    "    }\n"
    "  }\n"
    "}\n")


class TestSentinelLint(unittest.TestCase):
    def test_swallowed_literal_flagged_then_clean_with_record(self):
        d = tempfile.mkdtemp(prefix="runscope-sentinel-")
        self.addCleanup(shutil.rmtree, d, True)
        live = os.path.join(d, "SomeHook.java")
        with open(live, "w", encoding="utf-8", newline="\n") as fh:
            fh.write(JAVA_BAD)
        findings = bridge_lint.check_sentinel_degradation([d])
        self.assertEqual(len(findings), 1)
        with open(live, "w", encoding="utf-8", newline="\n") as fh:
            fh.write(JAVA_OK)
        self.assertEqual(bridge_lint.check_sentinel_degradation([d]), [])

    def test_offline_harness_exempt(self):
        d = tempfile.mkdtemp(prefix="runscope-sentinel2-")
        self.addCleanup(shutil.rmtree, d, True)
        offline = os.path.join(d, "M4SomeHarness.java")
        with open(offline, "w", encoding="utf-8", newline="\n") as fh:
            fh.write(JAVA_BAD)
        self.assertEqual(bridge_lint.check_sentinel_degradation([d]), [])


if __name__ == "__main__":
    unittest.main()
