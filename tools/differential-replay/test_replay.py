import copy
import dataclasses
import json
import os
from pathlib import Path
import sys
import tempfile
import time
import unittest
from unittest.mock import patch
from replay import Adapter, Limits, ReplayError, boundary, replay, strict_json, validate_trace

def trace(*commands, seed=1, order="AB"):
    rows=[]
    for command in commands:
        words=command.split();op=words[0]
        rows.append(dict(source="PLAYER" if op in ("SET","MOVE") else "SERVER",op=op,args=list(map(int,words[1:]))))
    return dict(schema="REPLAY_TOY_V1",seed=seed,entity=[0,20],blocks=[0]*16,callback_order=order,commands=rows)

class ProtocolControls(unittest.TestCase):
    def test_json_duplicate_nonfinite_rejected(self):
        for data in ('{"x":1,"x":2}','{"x":NaN}','{"x":Infinity}','invalid'):
            with self.assertRaises(ReplayError):strict_json(data)
    def test_trace_schema_and_ownership(self):
        for key,value in (("schema","V0"),("seed",True),("seed",2**32),("callback_order","AA"),("entity",[0,-1]),("blocks",[0]),("commands",[])):
            t=trace("SAVE");t[key]=value
            with self.assertRaises(ReplayError):validate_trace(t)
        t=trace("MOVE 1");t["commands"][0]["source"]="SERVER"
        with self.assertRaises(ReplayError):validate_trace(t)
    def test_command_bounds_and_unknown(self):
        for command in ("SET 16 0","SET 0 -1","TICK 0","RNG 257","SCHEDULE 0 0 1","DIV -1","MOVE 2147483648","UNKNOWN"):
            with self.assertRaises(ReplayError):validate_trace(trace(command))
    def test_binding_and_every_category_required(self):
        binding=dict(session="a",challenge="b",trace_sha256="c")
        row=dict(schema="REPLAY_BOUNDARY_V1",**binding,step=1,command="SAVE",outcome="OK",error=None,world_mutations=[],entity_state=[0,20],scheduled_ticks=[],packet_outputs=[],save_state="0a000000",callbacks=[],rng_state=1)
        boundary(row,binding,1,"SAVE")
        for key,value in (("schema","WRONG"),("session","stale"),("challenge","wrong"),("trace_sha256","different"),("step",True),("entity_state",[True,20]),("save_state","xx")):
            changed=copy.deepcopy(row);changed[key]=value
            with self.assertRaises(ReplayError):boundary(changed,binding,1,"SAVE")
        for key in row:
            changed=dict(row);del changed[key]
            with self.assertRaises(ReplayError):boundary(changed,binding,1,"SAVE")

ECHO = '''
import json,sys
h=sys.stdin.readline().strip().split(' ')
def row(step,command):
 return dict(schema='REPLAY_BOUNDARY_V1',session=h[2],challenge=h[3],trace_sha256=h[4],step=step,command=command,outcome='OK',error=None,world_mutations=[],entity_state=[int(h[6]),int(h[7])],scheduled_ticks=[],packet_outputs=[],save_state='0a000000',callbacks=[],rng_state=int(h[5]))
print(json.dumps(row(0,'INIT')),flush=True)
for step,line in enumerate(sys.stdin,1):print(json.dumps(row(step,line.strip())),flush=True)
'''

class ResourceControls(unittest.TestCase):
    def setUp(self):
        retained=os.environ.get("REPLAY_RESOURCE_TEST_OUTPUT")
        if retained:
            self.folder=Path(retained)/self._testMethodName
            self.folder.mkdir(parents=True,exist_ok=False)
        else:
            self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
            self.folder=Path(self.temp.name)
    def command(self,code):
        return [sys.executable,"-I","-u","-c",code]
    def assert_quiet(self,adapter,receipt):
        self.assertEqual(receipt["reader_threads_alive"],[])
        sizes=(adapter.stdout_path.stat().st_size,adapter.stderr_path.stat().st_size)
        time.sleep(.02)
        self.assertEqual(sizes,(adapter.stdout_path.stat().st_size,adapter.stderr_path.stat().st_size))
        self.assertLessEqual(sizes[0],adapter.limits.stdout_bytes)
        self.assertLessEqual(sizes[1],adapter.limits.stderr_bytes)
        (self.folder/"adapter-resource-receipt.json").write_text(json.dumps(receipt,indent=2)+"\n",encoding="utf-8")
    def flood(self,code,limits,substring):
        started=time.monotonic();adapter=Adapter(self.command(code),self.folder,"flood",limits)
        try:
            with self.assertRaises(ReplayError):adapter.receive()
        finally:receipt=adapter.close(abort=True)
        self.assertIn(substring,receipt["resource_error"])
        self.assertLess(time.monotonic()-started,4)
        self.assert_quiet(adapter,receipt)
        return receipt
    def test_zero_newline_stdout_has_total_byte_cap(self):
        limits=dataclasses.replace(Limits(),stdout_bytes=4096,line_bytes=8192,boundary_timeout=2,lifetime=3)
        receipt=self.flood("import os\nwhile True:os.write(1,b'x'*4096)",limits,"stdout total byte limit")
        self.assertEqual(receipt["saved_bytes"]["stdout"],4096)
        self.assertTrue(receipt["raw_prefix_truncated"]["stdout"])
    def test_zero_newline_stdout_has_line_cap(self):
        limits=dataclasses.replace(Limits(),stdout_bytes=8192,line_bytes=1024,boundary_timeout=2,lifetime=3)
        self.flood("import os\nwhile True:os.write(1,b'x'*4096)",limits,"stdout line byte limit")
    def test_stderr_flood_has_total_byte_cap(self):
        limits=dataclasses.replace(Limits(),stderr_bytes=4096,boundary_timeout=2,lifetime=3)
        receipt=self.flood("import os\nwhile True:os.write(2,b'x'*4096)",limits,"stderr total byte limit")
        self.assertEqual(receipt["saved_bytes"]["stderr"],4096)
        self.assertTrue(receipt["raw_prefix_truncated"]["stderr"])
    def test_queue_flood_never_blocks_reader(self):
        limits=dataclasses.replace(Limits(),queue_lines=2,boundary_timeout=2,lifetime=3)
        adapter=Adapter(self.command("import os\nwhile True:os.write(1,b'{}\\n'*2048)"),self.folder,"queue",limits)
        try:
            adapter.process.wait(timeout=2)  # Deliberately do not drain the bounded queue.
            with self.assertRaises(ReplayError):adapter.receive()
        finally:receipt=adapter.close(abort=True)
        self.assertIn("stdout queue capacity",receipt["resource_error"])
        self.assert_quiet(adapter,receipt)
    def test_lifetime_kills_silent_adapter(self):
        limits=dataclasses.replace(Limits(),boundary_timeout=2,lifetime=.5)
        self.flood("import time;time.sleep(30)",limits,"lifetime limit")
    def test_boundary_timeout_kills_silent_adapter(self):
        limits=dataclasses.replace(Limits(),boundary_timeout=.5,lifetime=2)
        self.flood("import time;time.sleep(30)",limits,"boundary timeout")
    def test_whole_run_boundary_budget(self):
        limits=dataclasses.replace(Limits(),boundary_bytes=1200,boundary_timeout=2,lifetime=3)
        command=self.command(ECHO)
        result=replay(trace(*(["SAVE"]*2000)),command,command,self.folder,limits=limits)
        self.assertEqual(result["status"],"DIVERGED")
        self.assertIn("whole-replay boundary byte budget",result["first_divergence"]["detail"])
        self.assertLess(result["commands_issued"],4)
        self.assertTrue(all(not p["reader_threads_alive"] for p in result["processes"]))
    def test_extra_output_cannot_pass(self):
        reference=self.command(ECHO)
        candidate=self.command(ECHO+"\nprint('{}',flush=True)")
        result=replay(trace("SAVE"),reference,candidate,self.folder)
        self.assertEqual(result["status"],"DIVERGED")
        self.assertEqual(result["first_divergence"]["category"],"ADAPTER_TERMINATION")
        self.assertEqual(result["processes"][1]["extra_stdout_lines"],1)
    def test_input_is_copied_before_any_adapter_runs(self):
        original=trace("SAVE");command=self.command(ECHO)
        def adapter(*args,**kwargs):
            original["seed"]=99
            original["commands"][0]["op"]="INVALID"
            return Adapter(*args,**kwargs)
        with patch("replay.Adapter",side_effect=adapter):
            result=replay(original,command,command,self.folder)
        self.assertEqual(result["status"],"PASS")
        saved=json.loads((Path(result["output"])/"trace.json").read_text())
        self.assertEqual(saved["seed"],1)
        self.assertEqual(saved["commands"][0]["op"],"SAVE")
    def test_limits_cannot_be_disabled_or_increased(self):
        for limits in (dataclasses.replace(Limits(),queue_lines=0),dataclasses.replace(Limits(),stdout_bytes=1<<40),dataclasses.replace(Limits(),lifetime=float('nan'))):
            with self.assertRaises(ReplayError):limits.validate()

if __name__=="__main__":unittest.main()
