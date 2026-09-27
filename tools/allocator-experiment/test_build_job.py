"""Deterministic bounded-process harness faults; no benchmark measurements."""
import ctypes as c
from ctypes import wintypes as w
import json,os,sys,unittest
from pathlib import Path
import build_job
ROOT=Path(__file__).resolve().parents[2]
class JobControls(unittest.TestCase):
 def run_child(self,source,**kw):
  return build_job.launch([sys.executable,"-u","-c",source],cwd=ROOT,env=dict(os.environ),memory_mib=256,**kw)
 def assert_gone(self,pid):
  kernel=c.WinDLL("kernel32",use_last_error=True);kernel.OpenProcess.argtypes=[w.DWORD,w.BOOL,w.DWORD];kernel.OpenProcess.restype=w.HANDLE;kernel.WaitForSingleObject.argtypes=[w.HANDLE,w.DWORD];kernel.WaitForSingleObject.restype=w.DWORD;kernel.CloseHandle.argtypes=[w.HANDLE]
  h=kernel.OpenProcess(0x100000,False,pid)
  if not h:self.assertEqual(c.get_last_error(),87);return
  try:self.assertEqual(kernel.WaitForSingleObject(h,0),0)
  finally:kernel.CloseHandle(h)
 def test_normal_child_tree(self):
  result=self.run_child("import subprocess,sys; subprocess.run([sys.executable,'-c','print(42)'],check=True)")
  self.assertEqual(result[0],0);self.assertEqual(result[1].strip(),b"42");self.assertFalse(result[3])
 def test_assignment_failure(self):
  with self.assertRaises(build_job.LaunchFailure) as seen:self.run_child("raise AssertionError('must stay suspended')",startup_fault="assignment-failure")
  self.assertTrue(seen.exception.terminated);self.assertEqual(seen.exception.stdout,b"");self.assert_gone(seen.exception.pid)
 def test_resume_failure(self):
  with self.assertRaises(build_job.LaunchFailure) as seen:self.run_child("raise AssertionError('must stay suspended')",startup_fault="resume-failure")
  self.assertTrue(seen.exception.terminated);self.assertEqual(seen.exception.stdout,b"");self.assert_gone(seen.exception.pid)
 def test_timeout_terminates_descendant(self):
  source="import subprocess,sys,time; p=subprocess.Popen([sys.executable,'-c','import time;time.sleep(30)']); print(p.pid,flush=True); time.sleep(30)"
  try:r=self.run_child(source,timeout=.5);out=r[1];self.assertTrue(r[3]);self.assertNotEqual(r[0],0)
  except build_job.LaunchFailure as failure:out=failure.stdout;self.assertTrue(failure.timed_out);self.assertTrue(failure.terminated)
  self.assert_gone(int(out.strip()))
 def test_output_budget_preserves_prefix(self):
  with self.assertRaises(build_job.LaunchFailure) as seen:self.run_child("import sys;sys.stdout.write('x'*10000);sys.stdout.flush()",output_limit=128)
  self.assertEqual(seen.exception.stdout,b"x"*128);self.assertTrue(seen.exception.terminated);self.assert_gone(seen.exception.pid)
 def test_detached_descendant_refuses_false_quiescence(self):
  source="import subprocess,sys; p=subprocess.Popen([sys.executable,'-c','import time;time.sleep(30)'],stdin=subprocess.DEVNULL,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL); print(p.pid,flush=True)"
  with self.assertRaises(build_job.LaunchFailure) as seen:self.run_child(source)
  self.assertIn("descendants alive",str(seen.exception));self.assertTrue(seen.exception.terminated);self.assert_gone(int(seen.exception.stdout.strip()))
if __name__=="__main__":unittest.main()
