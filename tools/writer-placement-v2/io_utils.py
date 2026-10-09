"""Strict bounded I/O for offline evidence. No shell, fallback or inherited Java flags."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import threading
import time


def sha(path):
    h = hashlib.sha256()
    with Path(path).open("rb") as handle:
        while block := handle.read(65536): h.update(block)
    return h.hexdigest()


def limited(path, maximum):
    with Path(path).open("rb") as handle: raw = handle.read(maximum + 1)
    if len(raw) > maximum: raise ValueError("input byte budget exceeded")
    return raw


def strict(text):
    def pairs(items):
        out = {}
        for k, v in items:
            if k in out:
                raise ValueError("duplicate JSON key")
            out[k] = v
        return out
    return json.loads(text, object_pairs_hook=pairs,
                      parse_constant=lambda _: (_ for _ in ()).throw(ValueError("nonfinite JSON")))


def write(path, value):
    Path(path).write_text(json.dumps(value, ensure_ascii=True, indent=2, allow_nan=False) + "\n", encoding="utf-8")


def process(argv, output, label, timeout=60, limit=32 * 1024 * 1024):
    env = dict(os.environ)
    for key in ("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS", "JDK_JAVAC_OPTIONS", "CLASSPATH"):
        env.pop(key, None)
    started = time.monotonic()
    p = subprocess.Popen([str(x) for x in argv], stdin=subprocess.DEVNULL,
                         stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=env, shell=False)
    buffers, errors = [bytearray(), bytearray()], []

    def read(pipe, index):
        try:
            while block := pipe.read(65536):
                left = limit - len(buffers[index])
                buffers[index].extend(block[:max(0, left)])
                if len(block) > left:
                    errors.append("output byte limit")
                    p.kill()
                    break
        finally:
            pipe.close()
    readers = [threading.Thread(target=read, args=(pipe, i), daemon=True)
               for i, pipe in enumerate((p.stdout, p.stderr))]
    for t in readers:
        t.start()
    try:
        p.wait(timeout=timeout)
    except subprocess.TimeoutExpired:
        errors.append("timeout")
        p.kill()
        p.wait(timeout=5)
    for t in readers:
        t.join(timeout=5)
        if t.is_alive():
            errors.append("reader termination failure")
    for index, suffix in enumerate(("stdout", "stderr")):
        (output / (label + "." + suffix)).write_bytes(buffers[index])
    record = dict(argv=[str(x) for x in argv], exit=p.returncode, errors=errors,
                  elapsed_seconds=time.monotonic() - started,
                  stdout_sha256=hashlib.sha256(buffers[0]).hexdigest(),
                  stderr_sha256=hashlib.sha256(buffers[1]).hexdigest())
    write(output / (label + ".process.json"), record)
    if errors or p.returncode or buffers[1]:
        raise ValueError("subprocess failed: " + label + " (retained raw output)")
    return bytes(buffers[0]).decode("utf-8", errors="strict")
