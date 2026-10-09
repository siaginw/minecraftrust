"""Actual Windows process controls for parser Job startup and capture failure."""
import ctypes as c
from ctypes import wintypes as w
import os
from pathlib import Path
import sys
import time
import unittest
from job import LaunchFailure, launch


def assert_exited(test, pid):
    kernel = c.WinDLL("kernel32", use_last_error=True)
    kernel.OpenProcess.argtypes = [w.DWORD, w.BOOL, w.DWORD]
    kernel.OpenProcess.restype = w.HANDLE
    kernel.WaitForSingleObject.argtypes = [w.HANDLE, w.DWORD]
    kernel.CloseHandle.argtypes = [w.HANDLE]
    handle = kernel.OpenProcess(0x100000, False, pid)
    if not handle:
        test.assertEqual(c.get_last_error(), 87)  # process no longer exists
        return
    try: test.assertEqual(kernel.WaitForSingleObject(handle, 0), 0)
    finally: kernel.CloseHandle(handle)


@unittest.skipUnless(os.name == "nt", "Windows Job contract")
class JobControls(unittest.TestCase):
    def command(self, source):
        return [sys.executable, "-I", "-c", source]

    def failure(self, source, marker, **kwargs):
        with self.assertRaises(LaunchFailure) as found:
            launch(self.command(source), cwd=Path(__file__).parent,
                   env=dict(os.environ), memory_mib=128, timeout=2, **kwargs)
        failure = found.exception
        self.assertIn(marker, str(failure))
        self.assertTrue(failure.terminated)
        self.assertIsNotNone(failure.pid)
        assert_exited(self, failure.pid)
        return failure

    def test_unassigned_suspended_child_is_terminated(self):
        self.failure("import time; time.sleep(60)", "assignment failure",
                     startup_fault="assignment-failure")

    def test_assigned_suspended_child_is_terminated(self):
        self.failure("import time; time.sleep(60)", "resume failure",
                     startup_fault="resume-failure")

    def test_output_budget_preserves_bounded_prefix_and_terminates(self):
        failure = self.failure("import os,time; os.write(2,b'prefix\\n'); os.write(1,b'x'*(2<<20)); time.sleep(60)", "output limit")
        self.assertEqual(failure.stdout, b"x"*(1<<20))
        self.assertIn(b"prefix", failure.stderr)

    def test_closed_pipes_do_not_bypass_deadline(self):
        began = time.monotonic()
        code, out, err, timed, peak = launch(self.command(
            "import os,time; os.close(1); os.close(2); time.sleep(60)"),
            cwd=Path(__file__).parent, env=dict(os.environ), memory_mib=128, timeout=.25)
        self.assertNotEqual(code, 0)
        self.assertTrue(timed)
        self.assertEqual((out, err), (b"", b""))
        self.assertLess(time.monotonic()-began, 5)
        self.assertGreater(peak, 0)


if __name__ == "__main__": unittest.main()
