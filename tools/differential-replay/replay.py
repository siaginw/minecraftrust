"""Strict lockstep protocol adapter; no Minecraft qualification is implied."""
from __future__ import annotations
import hashlib
import json
import copy
from dataclasses import asdict, dataclass
import os
from pathlib import Path
import queue
import re
import secrets
import subprocess
import threading
import time
import uuid

FIELDS = ("outcome", "error", "world_mutations", "entity_state", "scheduled_ticks", "packet_outputs", "save_state", "callbacks", "rng_state")
BINDING = ("schema", "session", "challenge", "trace_sha256", "step", "command")

class ReplayError(ValueError):
    pass

@dataclass(frozen=True)
class Limits:
    line_bytes: int = 1 << 20
    stdout_bytes: int = 8 << 20
    stderr_bytes: int = 256 << 10
    queue_lines: int = 4
    boundary_bytes: int = 4 << 20
    boundary_timeout: float = 10
    lifetime: float = 30

    def validate(self):
        for name, value in asdict(self).items():
            maximum = getattr(Limits(), name)
            if type(value) not in (int,float) or not 0 < value <= maximum:
                raise ReplayError("invalid or increased resource limit: " + name)
            if name not in ("boundary_timeout","lifetime") and type(value) is not int:
                raise ReplayError("byte/queue limits must be integers")

def strict_json(text):
    def pairs(items):
        result = {}
        for k, v in items:
            if k in result: raise ReplayError("duplicate JSON key")
            result[k] = v
        return result
    def constant(_): raise ReplayError("nonfinite JSON")
    try: return json.loads(text, object_pairs_hook=pairs, parse_constant=constant)
    except (ValueError, TypeError, RecursionError) as error: raise ReplayError(str(error)) from error

def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":"), allow_nan=False).encode()).hexdigest()

def file_sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()

def integer(value, low, high):
    return type(value) is int and low <= value <= high

def validate_trace(trace):
    if not isinstance(trace, dict) or set(trace) != {"schema", "seed", "entity", "blocks", "callback_order", "commands"} or trace["schema"] != "REPLAY_TOY_V1":
        raise ReplayError("trace schema")
    if not integer(trace["seed"], 0, 2**32-1) or trace["callback_order"] not in ("AB", "BA"):
        raise ReplayError("seed/callback order")
    if not isinstance(trace["entity"], list) or len(trace["entity"]) != 2 or not integer(trace["entity"][0], -2**31, 2**31-1) or not integer(trace["entity"][1], 0, 2**31-1):
        raise ReplayError("initial entity")
    if not isinstance(trace["blocks"], list) or len(trace["blocks"]) != 16 or not all(integer(n, 0, 255) for n in trace["blocks"]):
        raise ReplayError("initial cells")
    if not isinstance(trace["commands"], list) or not 1 <= len(trace["commands"]) <= 2000:
        raise ReplayError("bounded nonempty command sequence required")
    specs = {"SET": ("PLAYER", ((0,15),(0,255))), "MOVE": ("PLAYER", ((-2**31,2**31-1),)), "SCHEDULE": ("SERVER", ((1,1000),(0,15),(-255,255))), "TICK": ("SERVER", ((1,100),)), "RNG": ("SERVER", ((1,256),)), "DIV": ("SERVER", ((0,2**31-1),)), "SAVE": ("SERVER", ())}
    lines = []
    for command in trace["commands"]:
        if not isinstance(command, dict) or set(command) != {"source", "op", "args"} or not isinstance(command["op"], str) or command["op"] not in specs:
            raise ReplayError("command schema")
        source, bounds = specs[command["op"]]
        args = command["args"]
        if command["source"] != source or not isinstance(args, list) or len(args) != len(bounds) or not all(integer(value, *bound) for value, bound in zip(args,bounds)):
            raise ReplayError("command ownership/range")
        lines.append(" ".join([command["op"], *map(str,args)]))
    return lines

def boundary(row, binding, step, command):
    if not isinstance(row, dict) or set(row) != set(FIELDS+BINDING): raise ReplayError("boundary schema fields")
    expected = dict(schema="REPLAY_BOUNDARY_V1", **binding, step=step, command=command)
    if any(type(row[k]) is not type(v) or row[k] != v for k,v in expected.items()): raise ReplayError("boundary schema/session/sequence/trace binding")
    if (row["outcome"],row["error"]) not in (("OK",None),("CONTROLLED_ERROR","DIVIDE_BY_ZERO")): raise ReplayError("outcome schema")
    for key, width in (("world_mutations",3),("scheduled_ticks",4)):
        if not isinstance(row[key],list) or any(not isinstance(v,list) or len(v)!=width or any(type(n) is not int for n in v) for v in row[key]): raise ReplayError("boundary numeric rows")
    if not isinstance(row["entity_state"],list) or len(row["entity_state"])!=2 or any(type(n) is not int for n in row["entity_state"]): raise ReplayError("entity schema")
    for key in ("packet_outputs", "callbacks"):
        if not isinstance(row[key],list) or any(type(v) is not str for v in row[key]): raise ReplayError("event list schema")
    if not integer(row["rng_state"],0,2**32-1) or not isinstance(row["save_state"],str): raise ReplayError("rng/save schema")
    if any(not re.fullmatch(r"(?:[0-9a-f]{2})+", v) for v in [row["save_state"],*row["packet_outputs"]]): raise ReplayError("noncanonical hex bytes")
    return row

def difference_path(left, right, path):
    if isinstance(left,list) and isinstance(right,list):
        for i,(a,b) in enumerate(zip(left,right)):
            if a!=b:return difference_path(a,b,path+[i])
        if len(left)!=len(right):return path+[min(len(left),len(right))]
    return path

class Adapter:
    def __init__(self, command, folder, name, limits=Limits()):
        limits.validate()
        self.limits = limits
        self.command = list(command)
        self.stderr_path = folder / (name + ".stderr.txt")
        self.stdout_path = folder / (name + ".stdout.jsonl")
        self.queue = queue.Queue(maxsize=limits.queue_lines)
        self.done = threading.Event()
        self.stop_readers = threading.Event()
        self.lock = threading.Lock()
        self.violation = None
        self.truncated = {"stdout":False,"stderr":False}
        self.saved = {"stdout":0,"stderr":0}
        self.last_line_bytes = 0
        self.process = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, cwd=folder, shell=False, bufsize=0)
        try:
            # Python >=3.12 supports nonblocking anonymous pipes on Windows;
            # all supported POSIX hosts do too. Fail instead of using a reader
            # that can remain blocked by an inherited pipe in another process.
            os.set_blocking(self.process.stdout.fileno(),False)
            os.set_blocking(self.process.stderr.fileno(),False)
        except (OSError,AttributeError) as error:
            self.process.kill();self.process.wait(timeout=3)
            self.process.stdin.close();self.process.stdout.close();self.process.stderr.close()
            raise ReplayError("nonblocking subprocess pipes required") from error
        self.readers = []
        for stream,path,cap in (("stdout",self.stdout_path,limits.stdout_bytes),("stderr",self.stderr_path,limits.stderr_bytes)):
            thread = threading.Thread(target=self._read, args=(stream,path,cap),daemon=True,name="replay-"+name+"-"+stream)
            self.readers.append(thread);thread.start()
        def lifetime():
            if not self.done.wait(limits.lifetime):self._fail("adapter lifetime limit exceeded")
        self.watchdog = threading.Thread(target=lifetime,daemon=True,name="replay-"+name+"-lifetime")
        self.watchdog.start()

    def _fail(self, reason):
        with self.lock:
            if self.violation is None:self.violation = reason
            if reason.startswith("stdout"):self.truncated["stdout"] = True
            elif reason.startswith("stderr"):self.truncated["stderr"] = True
            else:self.truncated.update(stdout=True,stderr=True)
        if self.process.poll() is None:
            try:self.process.kill()
            except OSError:pass

    def _enqueue(self, line):
        try:self.queue.put_nowait(line);return True
        except queue.Full:
            self._fail("stdout queue capacity exceeded")
            return False

    def _read(self, stream, path, cap):
        pipe = getattr(self.process,stream)
        pending = bytearray()
        try:
            with path.open("wb") as output:
                while not self.stop_readers.is_set():
                    try:chunk = os.read(pipe.fileno(),4096)
                    except BlockingIOError:
                        self.stop_readers.wait(.005)
                        continue
                    if not chunk:break
                    remaining = cap-self.saved[stream]
                    prefix = chunk[:remaining]
                    output.write(prefix);output.flush()
                    self.saved[stream] += len(prefix)
                    if len(chunk)>remaining:
                        self.truncated[stream] = True
                        self._fail(stream+" total byte limit exceeded")
                        break
                    if stream == "stdout":
                        pending.extend(chunk)
                        while b"\n" in pending:
                            end = pending.index(10)+1
                            if end>self.limits.line_bytes:
                                self._fail("stdout line byte limit exceeded");return
                            line = bytes(pending[:end]);del pending[:end]
                            if not self._enqueue(line):return
                        if len(pending)>self.limits.line_bytes:
                            self._fail("stdout line byte limit exceeded");return
                if stream=="stdout" and pending:
                    self._fail("stdout ended with an unterminated boundary")
        except (OSError,ValueError) as error:
            if not self.done.is_set():self._fail(stream+" reader error: "+str(error))
        finally:
            if stream=="stdout":
                try:self.queue.put_nowait(b"")
                except queue.Full:pass

    def send(self, line):
        if self.violation:raise ReplayError(self.violation)
        try: self.process.stdin.write((line+"\n").encode());self.process.stdin.flush()
        except (OSError, ValueError) as error: raise ReplayError("adapter input closed: " + str(error)) from error
    def receive(self, remaining_budget=None):
        if self.violation:raise ReplayError(self.violation)
        try: line=self.queue.get(timeout=self.limits.boundary_timeout)
        except queue.Empty as error:
            self._fail("adapter boundary timeout")
            raise ReplayError(self.violation) from error
        if self.violation:raise ReplayError(self.violation)
        if not line: raise ReplayError("adapter ended without boundary")
        if remaining_budget is not None and len(line)>remaining_budget:
            raise ReplayError("whole-replay boundary byte budget exceeded")
        try: text=line.decode("utf-8")
        except UnicodeError as error: raise ReplayError("boundary encoding") from error
        self.last_line_bytes = len(line)
        return strict_json(text)
    def close(self, abort=False):
        try: self.process.stdin.close()
        except OSError: pass
        if abort and self.process.poll() is None: self.process.kill()
        try: code=self.process.wait(timeout=3)
        except subprocess.TimeoutExpired:
            self._fail("adapter termination timeout");self.process.wait(timeout=3);code="TIMEOUT"
        self.done.set()
        self.watchdog.join(timeout=1)
        for thread in self.readers:thread.join(timeout=.5)
        if any(thread.is_alive() for thread in self.readers):
            self._fail("adapter inherited pipes remained open after termination")
            self.stop_readers.set()
            for thread in self.readers:thread.join(timeout=.5)
        self.process.stdout.close();self.process.stderr.close()
        alive=[thread.name for thread in self.readers if thread.is_alive()]
        if alive:self._fail("reader termination failed")
        extra_lines=0;extra_bytes=0
        while not self.queue.empty():
            line=self.queue.get_nowait()
            if line:extra_lines+=1;extra_bytes+=len(line)
        return {"command":self.command,"exit":code,"stderr_bytes":self.saved["stderr"],"extra_stdout_lines":extra_lines,"extra_stdout_bytes":extra_bytes,
                "saved_bytes":self.saved,"raw_prefix_truncated":self.truncated,"resource_error":self.violation,"reader_threads_alive":alive,"limits":asdict(self.limits),
                "stdout_sha256":file_sha(self.stdout_path),"stderr_sha256":file_sha(self.stderr_path)}

def replay(trace, reference, candidate, output_root, *, compare=True, limits=Limits()):
    """compare=False is a separate diagnostic run, never a parity verdict."""
    limits.validate()
    try:trace=copy.deepcopy(trace)
    except (ValueError,TypeError,RuntimeError,RecursionError) as error:raise ReplayError("cannot freeze trace") from error
    lines=validate_trace(trace)
    session=uuid.uuid4().hex;folder=Path(output_root).resolve()/session;folder.mkdir(parents=True,exist_ok=False)
    binding=dict(session=session,challenge=secrets.token_hex(32),trace_sha256=digest(trace))
    (folder/"trace.json").write_text(json.dumps(trace,indent=2)+"\n",encoding="utf-8")
    initial=" ".join(["INIT","REPLAY_TOY_V1",session,binding["challenge"],binding["trace_sha256"],str(trace["seed"]),*map(str,trace["entity"]),",".join(map(str,trace["blocks"])),trace["callback_order"]])
    (folder/"request.json").write_text(json.dumps(dict(binding=binding,initial=initial,reference=reference,candidate=candidate),indent=2)+"\n",encoding="utf-8")
    adapters=[];first=None;issued=0;last_reference=None;last_candidate=None;paired=0;row_bytes=0;start=time.perf_counter()
    try:
        for command in (reference,candidate):
            adapters.append(Adapter(command,folder,"reference" if not adapters else "candidate",limits))
        for adapter in adapters:adapter.send(initial)
        for step,command in enumerate(["INIT",*lines]):
            issued=step
            if step:
                for adapter in adapters:adapter.send(command)
            rows=[]
            for name,adapter in zip(("reference","candidate"),adapters):
                try:
                    row=adapter.receive(remaining_budget=limits.boundary_bytes-row_bytes)
                    row_bytes+=adapter.last_line_bytes
                    rows.append(boundary(row,binding,step,command))
                except ReplayError as error:
                    first=dict(step=step,command=command,category="ADAPTER_PROTOCOL",side=name,detail=str(error));break
            if first is not None:break
            last_reference,last_candidate=rows;paired+=1
            differences=[key for key in FIELDS if rows[0][key]!=rows[1][key]]
            if differences and compare:
                first=dict(step=step,command=command,category=differences[0],first_difference_path=difference_path(rows[0][differences[0]],rows[1][differences[0]],[differences[0]]),differing_categories=differences,reference=rows[0],candidate=rows[1]);break
    except (OSError,ReplayError) as error:
        first=dict(step=issued,category="ADAPTER_PROTOCOL",detail=str(error))
    finally:
        processes=[adapter.close(abort=first is not None) for adapter in adapters]
    if first is None:
        for i,p in enumerate(processes):
            if p["exit"]!=0 or p["stderr_bytes"] or p["extra_stdout_lines"] or p["resource_error"]:
                first=dict(step=issued,category="ADAPTER_TERMINATION",side=("reference","candidate")[i],detail=p);break
    result=dict(schema="DIFFERENTIAL_REPLAY_RECEIPT_V1",binding=binding,status="DIVERGED" if first else ("PASS" if compare else "DIAGNOSTIC_ONLY"),
                scope="REPLAY_TOY_V1_ONLY",production_authority=False,commands_issued=issued,commands_total=len(lines),
                compared_boundaries=paired if compare else 0,first_divergence=first,processes=processes,elapsed_seconds=time.perf_counter()-start,
                observed_boundary_bytes=row_bytes,limits=asdict(limits),final_reference=last_reference,final_candidate=last_candidate,output=str(folder))
    if first:(folder/"first-divergence.json").write_text(json.dumps(first,indent=2)+"\n",encoding="utf-8")
    (folder/"receipt.json").write_text(json.dumps(result,indent=2)+"\n",encoding="utf-8")
    return result
