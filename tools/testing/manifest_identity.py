"""Canonical identity for the runtime qualification manifest.

The manifest is a machine-local document: it names the runtime root, the Java
executable, the collector script and the config files by ABSOLUTE PATH, because
the subprocesses it launches need those paths to run. It is execution
provenance.

The static recipe, on the other hand, must identify WHAT is being qualified and
never WHERE its receipts happen to be written. The same runtime, the same
artifacts and the same hook inventory qualified at two different output
directories must produce the same static contract, the same policy identities
and the same generated plan bytes.

So the manifest carries two identities:

  raw file hash        drift/tamper detection for the document itself
  canonical identity   what the static recipe is bound to

The canonical identity is derived by replacing every path-bearing field with
the CONTENT identity of what it points at -- an executable by its own SHA-256,
an input file by its content hash -- and by DROPPING pure locations (the output
directory, the workspace, the runtime root) whose contents are already bound
elsewhere in the manifest inventory.

Location is not identity. Content and semantics are identity.

Path values are classified explicitly per field rather than by pattern
matching, because a blanket "strip anything that looks like a path" rule would
quietly remove facts that are semantic (an artifact content hash) or leave
facts that are not (a runtime root that happens to look like a version).
"""
from __future__ import annotations

import hashlib
import json
from pathlib import Path

IDENTITY_SCHEMA = "RUSTCRAFT_MANIFEST_IDENTITY_V1"


def _sha_file(path: Path) -> str | None:
    try:
        digest = hashlib.sha256()
        with open(path, "rb") as handle:
            for chunk in iter(lambda: handle.read(1 << 20), b""):
                digest.update(chunk)
        return digest.hexdigest()
    except OSError:
        return None


def _sha_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _content_identity(value):
    """Content identity for one config value.

    A path to an existing FILE becomes the hash of its bytes: two identical
    files at different locations are the same input. A path to a DIRECTORY
    becomes a directory token rather than a recursive walk -- the runtime's own
    artifact contents are already bound by the manifest inventory, and walking
    a 9 GB tree twice to prove the same thing is waste. Anything that is not a
    path is semantic and is kept verbatim.
    """
    if not isinstance(value, str) or len(value) < 2 or not value[:1].isalpha() or value[1:2] != ":":
        if not (isinstance(value, str) and value.startswith("/")):
            return value
    candidate = Path(value)
    if candidate.is_file():
        return {"sha256": _sha_file(candidate)}
    if candidate.is_dir():
        return {"role": "directory"}
    return value


def _canonical_command(command, cwd=None):
    """Semantic identity of one pinned command line.

    argv[0] (the interpreter) and the script are bound by their own content,
    so two byte-identical toolchains at different locations are the same tool.
    A `--config <path>` argument becomes the canonical identity of that config,
    which is itself computed by this module -- so a config that moved keeps its
    identity as long as its semantic inputs did.
    """
    if not isinstance(command, list) or not command:
        return {"error": "empty command"}
    executable, *rest = command
    parts = [{"sha256": _sha_file(Path(executable))}]
    # `pins` is deliberately excluded from identity: those are raw file hashes
    # of the documents as written, and a config document embeds absolute paths,
    # so its raw hash moves with the output directory. The command's semantic
    # identity above already binds the script content and the config's
    # canonical identity; the raw pins remain in the manifest as provenance.
    index = 0
    while index < len(rest):
        argument = rest[index]
        index += 1
        if isinstance(argument, str) and argument.startswith("-"):
            parts.append(argument)
            continue
        candidate = Path(argument)
        if candidate.suffix == ".py" and candidate.is_file():
            digest = _sha_file(candidate)
            if digest:
                parts.append({"role": "script", "sha256": digest})
                continue
        if candidate.is_file():
            # An input document: bound by its canonical identity, which for a
            # config is computed recursively and for anything else by content.
            identity = _canonical_config(argument) if candidate.suffix == ".json" \
                else {"sha256": _sha_file(candidate)}
            parts.append({"role": "document", "identity": identity})
            continue
        parts.append(argument)
    return {"argv": parts}


def _canonical_config(path: Path) -> dict:
    """Semantic identity of a pinned config document.

    Only fields that carry SEMANTIC identity are kept, with locations replaced
    by content hashes where the target is a file. Fields that are pure
    locations -- the launch directory, the workspace, the runtime root -- are
    dropped here: their contents are bound by the manifest inventory or by the
    recipe itself, and keeping them would reintroduce the location dependence
    this module exists to remove.
    """
    try:
        document = json.loads(Path(path).read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {"unavailable": True}
    out = {}
    for key in sorted(document):
        value = document[key]
        if key in ("launch_dir", "pre_dump", "post_dump", "plan", "manifest", "profile"):
            # Excluded from identity.
            #   launch_dir / pre_dump / post_dump are pure output and workspace
            # locations, recorded in the manifest's execution provenance.
            #   `plan` is downstream of this manifest: it is generated from the
            # recipe, which is bound to this manifest's identity, and it is
            # separately bound by the recipe's writer_plan_sha256.
            #   `manifest` is THE MANIFEST ITSELF: hashing it inside its own
            # identity is circular, and it changes as the fixture/driver wires
            # itself up. Its content is bound by the raw-file hash the engine
            # keeps for drift detection, and its semantic content by the
            # inventory that is already part of this projection.
            #   `profile` is a RUN PRODUCT, not an input: it carries the
            # policies, which carry this very identity. Hashing it made the
            # identity a function of itself -- the computed value differed from
            # the value read back one file-write later, for the same manifest,
            # which is why the policy never matched. Only inputs may enter an
            # input's identity.
            continue
        if key in ("plan", "hooks_manifest", "profile", "vanilla_jar"):
            identity = _content_identity(value)
            if isinstance(identity, dict) and "sha256" not in identity:
                identity = {"unresolved": True}
            out[key] = identity
            continue
        if key == "identity_tool" and isinstance(value, dict):
            java = value.get("java")
            out[key] = {
                "java_sha256": _sha_file(Path(java)) if java else None,
                "classpath": [_content_identity(entry) for entry in value.get("classpath", [])],
            }
            continue
        out[key] = _content_identity(value) if isinstance(value, str) else value
    return out


def canonical_manifest_identity(manifest: dict) -> str:
    """The canonical, location-independent identity of a runtime manifest.

    Computed from the manifest's semantic content: the runtime artifact
    inventory (relative paths and content hashes), the pinned tooling by its
    own content, and the schema version. Absolute locations do not enter.
    """
    out = {
        "schema": IDENTITY_SCHEMA,
        "manifest_schema": manifest.get("schema"),
        "inventories": manifest.get("inventories"),
        "tools": {},
    }
    collector = manifest.get("collector")
    if isinstance(collector, dict):
        out["tools"]["collector"] = {
            "command": _canonical_command(collector.get("command"))}
    validators = manifest.get("validators") or {}
    for name in sorted(validators):
        entry = validators[name]
        if isinstance(entry, dict):
            out["tools"][name] = {
                "command": _canonical_command(entry.get("command"))}
    identity_tool = manifest.get("identity_tool")
    if isinstance(identity_tool, dict):
        out["tools"]["identity_tool"] = {
            "java_sha256": identity_tool.get("java_sha256"),
            "classpath": [_content_identity(entry) if isinstance(entry, str) else entry
                          for entry in identity_tool.get("classpath", [])],
        }
    raw = json.dumps(out, sort_keys=True, separators=(",", ":"), ensure_ascii=True).encode("utf-8")
    return _sha_bytes(raw)
