"""Obtain V2 dumps and independent Rust parser agreement from exact class bytes."""
import hashlib
import importlib.util
import os
from pathlib import Path

from classfile import method_offsets
from io_utils import process, sha, strict, write

ROOT = Path(__file__).resolve().parents[2]


def crosscheck_module():
    path = ROOT / "tools/classfile-crosscheck/crosscheck.py"
    spec = importlib.util.spec_from_file_location("frozen_h1_crosscheck", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def capture(paths, output, java, classpath, rust_parser, unique_names=True):
    output.mkdir(parents=True, exist_ok=False)
    checks = crosscheck_module()
    results = {}
    for start in range(0, len(paths), 8):
        batch = paths[start:start + 8]
        names = [str(p) for p in batch]
        prefix = f"batch-{start:04}"
        command = [java, "-cp", classpath, "com.rustcraft.coremod.CanonicalClassIdentityV2"]
        text = process(command + ["--dump", *names], output, prefix + "-dump")
        dumps = text.splitlines()
        receipts = process(command + names, output, prefix + "-identity").splitlines()
        rust = process([rust_parser, *names], output, prefix + "-rust").splitlines()
        if not len(dumps) == len(receipts) == len(rust) == len(batch):
            raise ValueError("parser result count mismatch")
        for i, (path, line, receipt, independent) in enumerate(zip(batch, dumps, receipts, rust)):
            dump, receipt, independent = strict(line), strict(receipt), strict(independent)
            if len(receipt) != 5 or receipt[:2] != ["CANONICAL_ID_V2", dump[3]]:
                raise ValueError("identity schema/name mismatch")
            if receipt[2] != hashlib.sha256(line.encode("utf-8")).hexdigest():
                raise ValueError("dump hash mismatch")
            row = dict(path=str(path), rust=independent, asm_status="PARSED", asm_dump=dump, asm_receipt=receipt)
            checked = checks.compare(row, output, start + i)
            if checked["status"] != "AGREEMENT":
                raise ValueError("independent parser disagreement")
            key = dump[3] if unique_names else path.stem
            if key in results:
                raise ValueError("duplicate class observation")
            offsets = method_offsets(path.read_bytes())
            if any(len(offsets[tuple(m[:2])]) != len(m[12]) for m in dump[16]):
                raise ValueError("raw BCI/canonical instruction cardinality disagreement")
            results[key] = dict(file=str(path), dump=dump, receipt=receipt, parser=checked,
                                   offsets={m[0] + m[1]: offsets[tuple(m[:2])] for m in dump[16]})
    write(output / "classes.json", results)
    return results


def tool_inventory(java, classpath, rust_parser):
    files = [Path(java), Path(rust_parser), ROOT / "tools/classfile-crosscheck/crosscheck.py"]
    for entry in classpath.split(os.pathsep):
        path = Path(entry)
        if not path.is_absolute() or not path.exists():
            raise ValueError("classpath must name existing absolute inputs")
        files += sorted(path.rglob("*.class")) if path.is_dir() else [path]
    if not files or any(not p.is_absolute() or not p.is_file() for p in files):
        raise ValueError("tool paths must be explicit existing absolute files")
    return {str(p): sha(p) for p in files}
