"""Shared capture helpers for the V2 requalification campaigns.

A requalification is two real Forge launches against one pinned runtime:

  * a qualification launch with no live writers, whose transformed dump is the
    PRE-writer byte source, and
  * a diagnostic launch with the live-writer transformers armed, whose dump is
    the POST-writer byte source and which additionally produces the
    REAL_FORGE_LAUNCH_V1 frame witness.

Both launches are produced by tools/testing/forge_runtime.py, which is the only
code allowed to start a game JVM. Everything here is post-processing of what
those launches wrote: locating the dumps, running the real Java identity tool
over the classfiles, and describing the result. Nothing here fabricates an
identity, and nothing here decides whether the engine accepts it.
"""
from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
IDENTITY_MAIN = "com.rustcraft.coremod.CanonicalClassIdentityV2"

# The injected facade. Every writer site must show calls to exactly these
# methods, and the engine recounts them itself from the transformed class, so a
# collector that miscounts cannot talk its way to a PASS.
HOOKS_INTERNAL = "com/rustcraft/bridge/capture/LiveWriterHooks"


def sha(path: Path) -> str:
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def digest_json(value) -> str:
    """Canonical JSON digest, byte-identical to the engine's own digest_json."""
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":")).encode("utf-8")).hexdigest()


def identity_tool(java: Path, classpath: list[Path], args: list[str], timeout: int = 120) -> list[str]:
    env = dict(os.environ)
    process = subprocess.run([str(java), "-cp", os.pathsep.join(str(p) for p in classpath), IDENTITY_MAIN, *args],
                             capture_output=True, text=True, env=env, timeout=timeout)
    if process.returncode != 0:
        raise RuntimeError("identity tool failed: " + (process.stderr.strip() or process.stdout.strip()))
    return [line for line in process.stdout.splitlines() if line.strip()]


def identity_lines(java: Path, classpath: list[Path], classfiles: list[Path], timeout: int = 300) -> list[list]:
    """Parsed receipts, one per classfile, in argument order."""
    return [json.loads(line) for line in
            identity_tool(java, classpath, [str(p) for p in classfiles], timeout)]


def identity_dumps(java: Path, classpath: list[Path], classfiles: list[Path], timeout: int = 300) -> list[list]:
    """Method/constant-pool facts, one per classfile, in argument order."""
    return [json.loads(line) for line in
            identity_tool(java, classpath, ["--dump", *[str(p) for p in classfiles]], timeout)]


def locate_dumps(result: dict) -> tuple[Path, Path]:
    """(pre-writer dump, post-writer dump) from a forge_runtime result."""
    pre = Path(result["qualification"]["transformed_dir"])
    post = Path(result["post_hook_transformed_dir"])
    if not pre.is_dir() or not post.is_dir():
        raise RuntimeError("capture did not produce both dumps")
    return pre, post


def class_file(dump: Path, binary_name: str) -> Path:
    path = dump / (binary_name.replace('.', '/') + '.class')
    if not path.is_file():
        raise RuntimeError("capture dump has no " + binary_name + ".class")
    return path


def frame_witness(result: dict) -> dict:
    """The REAL_FORGE_LAUNCH_V1 witness the live-transformer lane produced."""
    verification = result["live_transformer"]["command"]["argv"]
    for value in verification:
        if value.startswith("-Drustcraft.verificationResult="):
            document = json.loads(Path(value.split("=", 1)[1]).read_text(encoding="utf-8"))
            witness = document.get("frame_relation_witness")
            if witness is None:
                raise RuntimeError("live lane recorded no frame witness: " +
                                   str(document.get("frame_relation_witness_status")))
            return json.loads(witness) if isinstance(witness, str) else witness
    raise RuntimeError("live lane command names no verification result")


def live_manifest(result: dict) -> dict:
    """The live lane's own qualification manifest."""
    return json.loads(Path(result["live_transformer"]["manifest"]).read_text(encoding="utf-8"))


def runtime_identity(result: dict) -> dict:
    """The identity triple the engine requires the collector to re-report.

    Taken from the live lane's own qualification manifest, not from the profile,
    so a drifted runtime cannot be papered over by a stale expected value.
    """
    manifest = live_manifest(result)
    return {"registry_identity_sha256": manifest["registry_identity_sha256"],
            "java_runtime_version": manifest["java_runtime_version"],
            "qualification_profile": manifest["profile"],
            "target": result["target"]}


def transformer_chain(result: dict) -> list[str]:
    """The transformer chain in the order the real loader actually ran it."""
    return list(live_manifest(result)["transformers"])


def coremods(result: dict) -> list[str]:
    """Registered FML coremod plugin classes, sorted so the list is stable."""
    return sorted(entry["class"] for entry in live_manifest(result)["registered_coremod_plugins"])


def capture(root: Path, output: Path, java_home: Path, dll: Path, manifest: Path) -> dict:
    """Run both real launches. This is the only place a game JVM is started."""
    sys.path.insert(0, str(ROOT / "tools/testing"))
    import forge_runtime  # noqa: E402  (path set above, deliberately not at import time)
    return forge_runtime.execute(root, output, java_home, dll, manifest,
                                 qualification_only=True, live_transformers=True)
