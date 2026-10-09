"""Small shared artifact bindings; imports only frozen read-only capture utilities."""
import hashlib
import json
import re
from pathlib import Path
import sys

ROOT=Path(__file__).resolve().parents[2]
HERE=Path(__file__).resolve().parent
sys.path.insert(0,str(ROOT/"tools/writer-placement-v2"))
from io_utils import sha,strict,write,limited,process
from validate import inside_target,ensure_inventory,load_plan,copy_inputs
from capture import capture,tool_inventory
sys.path.insert(0,str(ROOT/"tools/testing"))
from hardening_guard import inspect


def digest(value):
    return hashlib.sha256(json.dumps(value,sort_keys=True,separators=(",",":"),ensure_ascii=True,allow_nan=False).encode()).hexdigest()


def read_json_bound(path, maximum, expected=None):
    """The immutable buffer supplies BOTH parsed facts and their byte identity."""
    raw=limited(path,maximum)
    pin=hashlib.sha256(raw).hexdigest()
    if expected is not None and pin!=expected:
        raise ValueError("parsed artifact byte pin mismatch: "+str(path))
    return strict(raw),pin


def write_json_bound(path,value):
    raw=(json.dumps(value,ensure_ascii=True,indent=2,allow_nan=False)+"\n").encode("utf-8")
    Path(path).write_bytes(raw)
    return hashlib.sha256(raw).hexdigest()


def read_plan_bound(path):
    """Same bounded plan schema as the frozen adapter, validated from one buffer."""
    plan,pin=read_json_bound(path,2*1024*1024)
    if not isinstance(plan,dict) or not isinstance(plan.get("required_hooks"),list):
        raise ValueError("plan hook schema")
    hooks=plan["required_hooks"]
    if not 0<len(hooks)<=256 or len({h["id"] for h in hooks})!=len(hooks):
        raise ValueError("plan hook bound/duplicates")
    for hook in hooks:
        if not {"id","class","method","descriptor","hook_type","fingerprint"}<=hook.keys():
            raise ValueError("missing hook proof specification")
        if re.fullmatch(r"[A-Za-z_$][A-Za-z0-9_$]*(?:[./][A-Za-z_$][A-Za-z0-9_$]*)*",hook["class"]) is None:
            raise ValueError("invalid class name")
    return hooks,pin


def sources():
    files=[p for p in HERE.rglob("*") if p.is_file() and "__pycache__" not in p.parts]
    files += [ROOT/"tools/writer-placement-v2"/name for name in ("classfile.py","io_utils.py","capture.py","validate.py","validator.py")]
    files += [ROOT/name for name in ("tools/classfile-crosscheck/crosscheck.py","tools/testing/hardening_guard.py",
              "machine/architecture-hardening/isolation.json","docs/engineering/qualified-frame-relation.md",
              "tools/hotspot-verification-proof/primary-source-provenance.json")]
    return {str(p):sha(p) for p in sorted(files)}


def output_directory(path):
    result=inside_target(path)
    if not result.is_relative_to(ROOT/"target/qualified-frame-relation"):
        raise ValueError("output must stay within isolated qualified-frame-relation target")
    result.mkdir(parents=True,exist_ok=False)
    return result
