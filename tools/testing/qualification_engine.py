#!/usr/bin/env python3
"""Fresh, manifest-driven qualification. No certificate grants production authority.

Collectors are pinned trusted tools, not remote attestation. They must actually
perform the declared capture/validation; a nonce alone cannot prove that a JVM
loaded bytes. The engine independently checks bytes, inventories and method facts.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import sys
import uuid

try:
    from tools.testing.qualification_certificate import Evidence, EvidenceStatus as S, Maturity as M, digest_json, evaluate
    from tools.testing.manifest_identity import canonical_manifest_identity
    from tools.testing.session_bound_certificate import (
        CertificateError, recipe_binding_sha256, validate as validate_certificate)
    from tools.testing.session_bound_policy import (
        PolicyError, policy_sha256, validate as validate_policy)
    from tools.testing.frame_relation_evidence import (
        FrameEvidenceError, describe as describe_frames, validate as validate_frames)
except ModuleNotFoundError:
    from qualification_certificate import Evidence, EvidenceStatus as S, Maturity as M, digest_json, evaluate
    from manifest_identity import canonical_manifest_identity
    from session_bound_certificate import (
        CertificateError, recipe_binding_sha256, validate as validate_certificate)
    from session_bound_policy import (
        PolicyError, policy_sha256, validate as validate_policy)
    from frame_relation_evidence import (
        FrameEvidenceError, describe as describe_frames, validate as validate_frames)


#: CANONICAL_ID_V2_SESSION_BOUND is a real identity mode, not a V1 fallback. In
#: that mode the profile's declared class identities stay the EXACT (unmasked)
#: V2 identity of the observed bytes, so the engine can still recompute them
#: independently. What authorizes value-level normalization is the session
#: evidence block -- never the class structure.
IDENTITY_MODES = ("RAW_SHA256", "CANONICAL_ID_V2", "CANONICAL_ID_V2_SESSION_BOUND")

SESSION_BOUND_PROFILE_SCHEMA = "RUSTCRAFT_SESSION_BOUND_PROFILE_V1"
SESSION_BOUND_PROFILE_VERSION = 1

#: The same-process transformation chain. It exists because the session
#: certificate is an ADMISSION certificate: it authorizes the PRE_WRITER buffer
#: and says nothing about what any transformer did afterwards. Naming the stages
#: explicitly -- rather than calling the last writer "the writer" -- is what
#: keeps the chain honest when something other than RustCraft runs last.
CHAIN_SCHEMA = "RUSTCRAFT_TRANSFORMATION_CHAIN_V1"
CHAIN_VERSION = 1
STAGE_PRE = "PRE_WRITER"
STAGE_RUSTCRAFT = "RUSTCRAFT_POST_WRITER"
STAGE_DOWNSTREAM = "DOWNSTREAM_TRANSFORMER"
STAGE_FINAL = "FINAL_DEFINED"

#: Per-hook and per-exception-path shape. `required_calls` is only meaningful
#: where the count was declared, so a later stage that merely observes survival
#: may omit it.
CHAIN_HOOK_KEYS = ("id", "class", "method", "descriptor", "observed_calls")
CHAIN_PATH_KEYS = ("id", "class", "method", "descriptor", "handler")

#: The only transformers whose output may be labelled RUSTCRAFT_POST_WRITER.
RUSTCRAFT_TRANSFORMERS = (
    "com.rustcraft.coremod.LiveChunkOwnershipTransformer",
)


class Invalid(ValueError):
    pass


class Missing(ValueError):
    pass


def sha(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def load_hashed(path: Path):
    """Bind parsed data to the very same bytes, not a later filesystem read."""
    if not path.is_file():
        raise Missing(f"missing file: {path}")
    try:
        raw = path.read_bytes()
        return parse_json(raw.decode("utf-8")), hashlib.sha256(raw).hexdigest()
    except (UnicodeError, json.JSONDecodeError) as error:
        raise Invalid(f"malformed JSON: {path}: {error}") from error


def parse_json(text):
    def pairs(items):
        result = {}
        for key, value in items:
            if key in result:
                raise Invalid("duplicate JSON key: " + key)
            result[key] = value
        return result
    def constant(value):
        raise Invalid("nonfinite JSON value: " + value)
    try:
        return json.loads(text, object_pairs_hook=pairs, parse_constant=constant)
    except json.JSONDecodeError as error:
        raise Invalid("malformed JSON") from error


def keys(value, required, optional=()):
    if not isinstance(value, dict) or not set(required) <= value.keys() or value.keys() - set(required) - set(optional):
        raise Invalid(f"object schema mismatch; required={list(required)} allowed={list(required) + list(optional)}")


def digest(value):
    if not isinstance(value, str) or len(value) != 64 or any(c not in "0123456789abcdef" for c in value):
        raise Invalid("expected lowercase SHA-256")
    return value


def relative(root: Path, name: str, *, exists=True) -> Path:
    if not isinstance(name, str) or not name or "\\" in name:
        raise Invalid("relative paths must be nonempty portable '/' paths")
    candidate = Path(name)
    if candidate.is_absolute() or any(p in ("..", ".") for p in candidate.parts) or ":" in name:
        raise Invalid("absolute/traversing path in relative manifest entry")
    path = root / candidate
    resolved = path.resolve()
    if not resolved.is_relative_to(root.resolve()):
        raise Invalid("path escapes declared root")
    if exists and not path.exists():
        raise Missing(f"missing path: {name}")
    return resolved


def absolute(name):
    path = Path(name)
    if not path.is_absolute():
        raise Invalid("tool/runtime paths must be explicitly absolute")
    return path.resolve()


def strict_lines(text: str, count: int):
    lines = text.splitlines()
    if len(lines) != count:
        if not lines:
            raise Missing("subprocess produced no observations")
        raise Invalid(f"subprocess output count {len(lines)} != {count}")
    try:
        return [parse_json(line) for line in lines]
    except json.JSONDecodeError as error:
        raise Invalid("malformed subprocess JSON line") from error


class QualificationEngine:
    """One engine for every profile; mandatory stage requirements are code-owned."""

    def __init__(self, manifest: Path, profile: Path, output_root: Path):
        self.manifest_path, self.profile_path = Path(manifest).resolve(), Path(profile).resolve()
        self.session = uuid.uuid4().hex
        self.challenge = secrets.token_hex(32)
        self.output = Path(output_root).resolve() / self.session
        self.output.mkdir(parents=True, exist_ok=False)
        self.records = []
        self.logs = []
        self.profile = {}
        self.manifest = {}
        self.initial_tools = {}
        self.initial_inventory = {}
        self.initial_inputs = {}

    def node(self, name, kind, dependencies, function):
        try:
            value = function()
            record = Evidence(name, kind, S.PASS, digest_json(value), tuple(dependencies))
        except Missing as error:
            value = None
            record = Evidence(name, kind, S.INCOMPLETE, None, tuple(dependencies), str(error))
        except (ValueError, KeyError, TypeError, IndexError, AttributeError, OSError, subprocess.SubprocessError) as error:
            value = None
            record = Evidence(name, kind, S.FAIL, None, tuple(dependencies), str(error))
        self.records.append(record)
        return value

    def definitions(self):
        self.manifest, manifest_hash = load_hashed(self.manifest_path)
        self.profile, profile_hash = load_hashed(self.profile_path)
        m, p = self.manifest, self.profile
        keys(m, ("schema", "runtime_root", "inventories", "collector", "identity_tool"), ("validators",))
        keys(p, ("schema", "id", "identity_mode", "runtime_identity", "transformer_chain", "coremods", "classes", "writer_sites", "negative_controls", "scope", "production_authority"), ("pre_classes", "session_bound", "frame_evidence", "static_recipe"))
        if m["schema"] != "RUSTCRAFT_RUNTIME_MANIFEST_V2" or p["schema"] != "RUSTCRAFT_QUALIFICATION_PROFILE_V2":
            raise Invalid("unsupported manifest/profile schema")
        if p["identity_mode"] not in IDENTITY_MODES or p["production_authority"] is not False:
            raise Invalid("V1/unknown identity or production authority is forbidden")
        if not isinstance(p["id"], str) or not p["id"] or not isinstance(p["runtime_identity"], dict) or not p["runtime_identity"]:
            raise Invalid("profile/runtime identity must be nonempty")
        scope = p["scope"]
        class_keys = ("provider_class", "world_class", "chunk_class", "section_class", "container_class", "nibble_class", "packet_class", "registry_class", "generator_class")
        keys(scope, ("schema", "operation", "profile_id", "dimension", *class_keys, "storage_family", "registry_epoch", "state_width_bits", "generator_family", "skylight"))
        if scope["schema"] != "LIVE_CAPTURE_SCOPE_V1" or scope["operation"] != "chunk_packet_shadow_capture" or scope["profile_id"] != p["id"]:
            raise Invalid("unsupported/unbound extractor scope")
        if type(scope["dimension"]) is not int or not -(1 << 31) <= scope["dimension"] < 1 << 31 or type(scope["registry_epoch"]) is not int or not 0 < scope["registry_epoch"] < 1 << 63 or type(scope["state_width_bits"]) is not int or not 9 <= scope["state_width_bits"] <= 16 or type(scope["skylight"]) is not bool:
            raise Invalid("invalid dimension/epoch/state-width/skylight scope")
        if scope["storage_family"] != "VANILLA_U16" or any(not isinstance(scope[k], str) or not scope[k] for k in (*class_keys, "generator_family")):
            raise Invalid("exact supported storage/class/generator scope required")
        for name in ("transformer_chain", "coremods", "writer_sites", "negative_controls"):
            if not isinstance(p[name], list):
                raise Invalid(f"{name} must be an ordered list")
        if not p["classes"] or not isinstance(p["classes"], dict):
            raise Invalid("nonempty required class inventory required")
        for name, identity in [*p["classes"].items(), *p.get("pre_classes", {}).items()]:
            if not isinstance(name, str) or "." in name or name.startswith("/") or ".." in name:
                raise Invalid("class names must be JVM internal names")
            required = ("raw_sha256",) if p["identity_mode"] == "RAW_SHA256" else ("semantic_sha256", "declaration_order_sha256")
            # In session-bound mode the declared identity is still the EXACT V2
            # identity of the pre-writer bytes. That is what makes the engine's
            # independent recomputation meaningful: it recomputes the exact
            # identity and the certificate must agree with it.
            keys(identity, required)
            for item in identity.values():
                digest(item)
        if not p["writer_sites"] or not p["negative_controls"]:
            raise Missing("writer sites and required negative controls must be defined")
        ids = set()
        for site in p["writer_sites"]:
            keys(site, ("id", "class", "method", "descriptor", "required_calls"))
            if not site["id"] or site["id"] in ids or site["class"] not in p["classes"]:
                raise Invalid("duplicate writer id or unbound writer class")
            ids.add(site["id"])
            if not isinstance(site["required_calls"], list) or not site["required_calls"]:
                raise Missing("writer site requires concrete hook-call checks")
            for call in site["required_calls"]:
                keys(call, ("opcode", "owner", "name", "descriptor", "count"))
                if call["opcode"] not in (182, 183, 184, 185) or type(call["count"]) is not int or call["count"] < 1:
                    raise Invalid("invalid hook call contract")
        ids = [c["id"] for c in p["negative_controls"]]
        if len(set(ids)) != len(ids):
            raise Invalid("duplicate negative control id")
        for control in p["negative_controls"]:
            keys(control, ("id", "expected_outcome"))
            if control["expected_outcome"] not in ("REJECTED", "CHANGED", "STABLE"):
                raise Invalid("unknown negative control expectation")
        self.initial_inputs = {str(self.manifest_path): manifest_hash, str(self.profile_path): profile_hash}
        self.session_binding = self.declared_session_binding(p)
        self.frame_requirement = self.declared_frame_requirement(p)
        return self.initial_inputs

    def declared_session_binding(self, profile):
        """The session evidence a profile declares, before any observation.

        Missing evidence is a Missing (INCOMPLETE), never an Invalid: the goal
        is that absent evidence leaves the profile unpromoted, not that the run
        is scored as a discovered defect. A *present* but unreadable block is a
        FAIL, because that is a real disagreement.
        """
        block = profile.get("session_bound")
        if profile["identity_mode"] != "CANONICAL_ID_V2_SESSION_BOUND":
            if block is not None:
                raise Invalid("a session evidence block is meaningless in an exact identity mode")
            return None
        if block is None:
            raise Missing("a session-bound profile must declare its session evidence block")
        keys(block, ("schema", "schema_version", "recipe_sha256", "process_id",
                     "transformation_session_id", "classes", "admission_policies"))
        if block["schema"] != SESSION_BOUND_PROFILE_SCHEMA or block["schema_version"] != SESSION_BOUND_PROFILE_VERSION:
            raise Invalid("unsupported session evidence block schema/version")
        digest(block["recipe_sha256"])
        names = block["classes"]
        if (not isinstance(names, list) or not names
                or not all(isinstance(name, str) and name and "." not in name and not name.startswith("/") for name in names)
                or len(set(names)) != len(names)):
            raise Invalid("session evidence must name a distinct, ordered class inventory")
        if set(names) - set(profile["classes"]):
            raise Invalid("session evidence names classes the profile does not require")
        if "certificates" in block:
            # A certificate binds a process, a transformation session, a loader
            # object and an exact pre-writer byte hash. A profile is written
            # before the run it describes, so it cannot hold one, and a profile
            # that does is asking the engine to accept a certificate imported
            # from some earlier JVM. The policy it may hold says what MAY be
            # accepted; the transforming JVM records what WAS accepted.
            raise Invalid("a qualification profile may not carry session certificates; "
                          "it carries admission policies and the engine reads the "
                          "certificates the run itself issued")
        if not isinstance(block["admission_policies"], dict) or set(block["admission_policies"]) != set(names):
            raise Invalid("session evidence must carry exactly one admission policy per declared class")
        for name in names:
            try:
                validate_policy(block["admission_policies"][name])
            except PolicyError as error:
                raise Invalid(f"admission policy for {name} is unusable: {error}") from error
            if block["admission_policies"][name]["class_name"].replace(".", "/") != name:
                raise Invalid(f"admission policy names a different class: {name}")
        # The static qualification contract, and only that.
        #
        # This used to hash the profile minus the session block. That document
        # carries `classes`, `pre_classes` and `writer_sites`, which for a
        # session-bound class contain the qualifying launch's own bytes -- so the
        # digest could not exist until after the run the static policy had to
        # authorize, and no ordering of the driver could satisfy it. A
        # pre-launch authorization contract may not hash future runtime
        # evidence.
        #
        # The profile therefore carries an explicit `static_recipe`: the one
        # canonical document describing what was authorized BEFORE the run. The
        # engine recomputes the binding from it rather than trusting the value
        # written into the plan, the policies and this block, and then checks
        # that the contract still describes the profile it is attached to.
        contract = profile.get("static_recipe")
        if contract is None:
            raise Missing("a session-bound profile must declare its static qualification contract")
        if not isinstance(contract, dict):
            raise Invalid("the static qualification contract must be an object")
        expected = recipe_binding_sha256(contract)
        if block["recipe_sha256"] != expected:
            raise Invalid("session evidence recipe binding does not cover the static contract")
        if contract.get("identity_mode") != profile["identity_mode"]:
            raise Invalid("the static contract and the profile disagree about identity mode")
        contract_classes = {n.replace(".", "/") for n in contract.get("session_bound_classes", [])}
        if contract_classes != set(block["classes"]):
            raise Invalid("the static contract's session-bound classes differ from the block's")
        # A subset, not an equality: the contract declares every hook including
        # the diagnostic-only ones that place nothing and are therefore not
        # writer sites. What has to hold is that no writer site is unbacked by
        # the contract -- a placement the static contract never described is a
        # placement nothing authorized.
        contract_hooks = {hook["id"] for hook in contract.get("required_hooks", [])}
        unbacked = {site["id"] for site in profile.get("writer_sites", [])} - contract_hooks
        if unbacked:
            raise Invalid("the profile places hooks the static contract does not declare: "
                          + ", ".join(sorted(unbacked)))
        # The contract keys its identity inventory by binary name, the block by
        # internal name; the class is the same one either way.
        identities = {n.replace(".", "/") for n in contract.get("expected_class_identities", {})}
        for name in block["classes"]:
            if name not in identities:
                raise Invalid("the static contract does not bind the class " + name)
        return block

    def declared_frame_requirement(self, profile):
        """Which classes must be frame-proven, and against what evidence source.

        A profile may only declare that frame evidence is optional by saying so
        explicitly, and the recorded certificate carries that claim forward so a
        later reader cannot mistake an unproven class for a proven one.
        """
        block = profile.get("frame_evidence")
        if block is None:
            if profile["identity_mode"] != "CANONICAL_ID_V2_SESSION_BOUND":
                return None
            raise Missing("a session-bound profile must declare its frame evidence requirement")
        keys(block, ("required_classes",))
        names = block["required_classes"]
        if not isinstance(names, list) or not names or not all(isinstance(name, str) and name for name in names):
            raise Invalid("frame evidence must name the classes it requires proven")
        normalized = {name.replace(".", "/") for name in names}
        return {"required_classes": sorted(normalized),
                "unproven_classes": sorted(set(self.profile["classes"]) - normalized)}

    def inventory(self):
        root = absolute(self.manifest["runtime_root"])
        groups = self.manifest["inventories"]
        keys(groups, ("artifacts", "mods", "config"))
        result = {}
        for group, spec in groups.items():
            keys(spec, ("roots", "files"))
            if not isinstance(spec["roots"], list) or not spec["roots"] or not isinstance(spec["files"], dict):
                raise Invalid("inventory needs roots and exact file map")
            observed = {}
            for name in spec["roots"]:
                path = relative(root, name)
                paths = [path] if path.is_file() else sorted(path.rglob("*"))
                for file in paths:
                    if file.is_file():
                        if not file.resolve().is_relative_to(root):
                            raise Invalid("inventory symlink escapes runtime")
                        key = file.relative_to(root).as_posix()
                        if key in observed:
                            raise Invalid("overlapping inventory roots")
                        observed[key] = sha(file)
            for name, expected in spec["files"].items():
                relative(root, name, exists=False)
                digest(expected)
            extra = set(observed) - spec["files"].keys()
            if extra:
                raise Invalid(f"unobserved/unpinned {group} artifacts: {sorted(extra)}")
            missing = spec["files"].keys() - observed.keys()
            if missing:
                raise Missing(f"missing {group} artifacts: {sorted(missing)}")
            changed = [name for name in observed if observed[name] != spec["files"][name]]
            if changed:
                raise Invalid(f"{group} artifact hash drift: {changed}")
            if group == "artifacts" and not observed:
                raise Missing("no runtime artifacts observed")
            result[group] = observed
        return result

    def command_pins(self, collector):
        keys(collector, ("command", "pins", "environment", "timeout_seconds"))
        command = collector["command"]
        if not isinstance(command, list) or not command or not all(isinstance(x, str) for x in command):
            raise Invalid("collector command must be argv strings")
        exe = absolute(command[0])
        result = {}
        for name, pin in collector["pins"].items():
            path = absolute(name)
            digest(pin)
            if not path.is_file():
                raise Missing(f"missing pinned collector input: {path}")
            result[str(path)] = sha(path)
            if result[str(path)] != pin:
                raise Invalid(f"collector executable/input drift: {path}")
        if str(exe) not in result:
            raise Invalid("collector executable must be pinned")
        for arg in command[1:]:
            if Path(arg).is_absolute() and Path(arg).is_file() and str(Path(arg).resolve()) not in result:
                raise Invalid("collector file argument is not pinned")
        if type(collector["timeout_seconds"]) not in (int, float) or not 0 < collector["timeout_seconds"] <= 3600:
            raise Invalid("invalid bounded subprocess timeout")
        if not isinstance(collector["environment"], dict) or not all(isinstance(k, str) and isinstance(v, str) for k, v in collector["environment"].items()):
            raise Invalid("collector environment must contain string pairs")
        return result

    def tools(self):
        result = self.command_pins(self.manifest["collector"])
        validators = self.manifest.get("validators", {})
        keys(validators, (), ("placement",))
        for validator in validators.values():
            result.update(self.command_pins(validator))
        identity = self.manifest["identity_tool"]
        keys(identity, ("java", "java_sha256", "classpath", "timeout_seconds"))
        java = absolute(identity["java"])
        digest(identity["java_sha256"])
        if not java.is_file():
            raise Missing("missing Java executable")
        result[str(java)] = sha(java)
        if result[str(java)] != identity["java_sha256"]:
            raise Invalid("Java executable drift")
        if not identity["classpath"]:
            raise Missing("empty identity classpath")
        for entry in identity["classpath"]:
            keys(entry, ("path", "files"))
            path = absolute(entry["path"])
            if not path.exists():
                raise Missing("missing identity classpath entry")
            if path.is_file():
                actual = {"": sha(path)}
            else:
                actual = {p.relative_to(path).as_posix(): sha(p) for p in sorted(path.rglob("*")) if p.is_file()}
            if not actual or actual != entry["files"]:
                raise Invalid("identity classpath inventory drift")
            for name, value in actual.items():
                result[str(path / name) if name else str(path)] = value
        if type(identity["timeout_seconds"]) not in (int, float) or not 0 < identity["timeout_seconds"] <= 3600:
            raise Invalid("invalid bounded subprocess timeout")
        return result

    def process(self, command, timeout, label, environment=None):
        env = {key: os.environ[key] for key in ("SystemRoot", "WINDIR", "TEMP", "TMP") if key in os.environ}
        env.update(environment or {})
        try:
            process = subprocess.run(command, shell=False, cwd=self.output, env=env, capture_output=True, text=True, encoding="utf-8", errors="strict", timeout=timeout)
        except (subprocess.SubprocessError, OSError, UnicodeError) as error:
            self.logs.append({"label": label, "command": command, "error": str(error)})
            raise Invalid(f"{label} subprocess failure: {error}") from error
        self.logs.append({"label": label, "command": command, "returncode": process.returncode, "stdout": process.stdout, "stderr": process.stderr})
        if process.returncode:
            raise Invalid(f"{label} subprocess nonzero exit {process.returncode}; stderr captured")
        if process.stderr.strip():
            raise Invalid(f"{label} subprocess emitted unexpected stderr; stderr captured")
        return process.stdout

    def acquire(self):
        request = {"schema": "RUSTCRAFT_QUALIFICATION_REQUEST_V2", "session": self.session, "challenge": self.challenge,
                   "profile_id": self.profile["id"], "profile_sha256": self.initial_inputs[str(self.profile_path)],
                   "manifest_sha256": self.initial_inputs[str(self.manifest_path)], "tool_sha256": digest_json(self.initial_tools),
                   "inventory_sha256": digest_json(self.initial_inventory), "runtime_root": self.manifest["runtime_root"],
                   "required_classes": sorted(self.profile["classes"]), "scope": self.profile["scope"]}
        request_path = self.output / "request.json"
        request_path.write_text(json.dumps(request, indent=2) + "\n", encoding="utf-8")
        self.request_sha256 = sha(request_path)
        out = self.output / "observation.json"
        c = self.manifest["collector"]
        text = self.process([*c["command"], "--request", str(request_path), "--out", str(out)], c["timeout_seconds"], "collector", c["environment"])
        ack, = strict_lines(text, 1)
        keys(ack, ("schema", "session", "challenge", "output_sha256"))
        if ack["schema"] != "RUSTCRAFT_COLLECTOR_ACK_V2" or ack["session"] != self.session or ack["challenge"] != self.challenge:
            raise Invalid("stale/cross-session collector acknowledgement")
        observed, observed_hash = load_hashed(out)
        if ack["output_sha256"] != observed_hash:
            raise Invalid("collector acknowledgement/output hash drift")
        # The frame witness and the session acquisition/certificate records are
        # optional only in the sense that a profile which does not require them
        # may omit them. When present they are never ignored: the frame node
        # and the session node both read them, and the session node refuses to
        # let session evidence travel alongside an exact identity mode.
        keys(observed, ("schema", "session", "challenge", "request_sha256", "capture_kind", "runtime_identity", "transformer_chain", "coremods", "classes"),
             ("pre_classes", "writer_matrix", "negative_controls", "live", "frame_relation_witness", "session_acquisition", "session_certificates", "transformation_chain"))
        if observed["schema"] != "RUSTCRAFT_FRESH_OBSERVATION_V2" or observed["session"] != self.session or observed["challenge"] != self.challenge or observed["request_sha256"] != self.request_sha256:
            raise Invalid("stale/cross-session/request-substituted observation")
        if observed["capture_kind"] != "OFFLINE_TRANSFORM_CAPTURE":
            raise Invalid("this engine adapter only accepts explicit offline transformed capture")
        if sha(request_path) != self.request_sha256:
            raise Invalid("collector changed its input request")
        self.observation_sha256 = observed_hash
        return observed

    def runtime(self, observed):
        for name in ("runtime_identity", "transformer_chain", "coremods"):
            if observed[name] != self.profile[name]:
                raise Invalid(f"fresh {name} differs from profile")
        return {name: observed[name] for name in ("runtime_identity", "transformer_chain", "coremods")}

    def classes(self, observed, section="classes"):
        if not self.profile.get(section) or not observed.get(section):
            raise Missing(f"missing expected/fresh {section}")
        if not isinstance(observed[section], list):
            raise Invalid("classes must be an observed list")
        paths = {}
        for row in observed[section]:
            keys(row, ("name", "file", "raw_sha256"))
            if row["name"] in paths or row["name"] not in self.profile[section]:
                raise Invalid("duplicate/unexpected observed class")
            path = relative(self.output, row["file"])
            if path.suffix != ".class" or not path.is_file():
                raise Invalid("class observation must name a fresh class file")
            if sha(path) != digest(row["raw_sha256"]):
                raise Invalid("claimed class hash differs from actual bytes")
            paths[row["name"]] = path
        if set(paths) != self.profile[section].keys():
            raise Missing("required transformed class observation missing")
        reported_files = {relative(self.output, row["file"]) for key in ("classes", "pre_classes") for row in observed.get(key, [])}
        if set(self.output.rglob("*.class")) != reported_files:
            raise Invalid("unreported or aliased class files in observation directory")
        identity = self.manifest["identity_tool"]
        cmd = [identity["java"], "-cp", os.pathsep.join(x["path"] for x in identity["classpath"]), "com.rustcraft.coremod.CanonicalClassIdentityV2"]
        result = {}
        names = sorted(paths)
        for start in range(0, len(names), 16):
            batch = names[start:start + 16]
            args = [str(paths[n]) for n in batch]
            receipts = strict_lines(self.process(cmd + args, identity["timeout_seconds"], "identity-receipts"), len(batch))
            dump_text = self.process(cmd + ["--dump"] + args, identity["timeout_seconds"], "identity-method-facts")
            dumps = strict_lines(dump_text, len(batch))
            for name, receipt, dump, line in zip(batch, receipts, dumps, dump_text.splitlines()):
                if not isinstance(receipt, list) or len(receipt) != 5 or receipt[:2] != ["CANONICAL_ID_V2", name]:
                    raise Invalid(f"identity receipt schema/name mismatch for {name}: {receipt!r}")
                for value in receipt[2:]:
                    digest(value)
                if not isinstance(dump, list) or len(dump) != 19 or dump[0] != "CANONICAL_ID_V2" or dump[3] != name:
                    raise Invalid("identity dump schema/name mismatch")
                raw = sha(paths[name])
                if receipt[4] != raw or receipt[2] != hashlib.sha256(line.encode("utf-8")).hexdigest():
                    raise Invalid("identity output not bound to observed bytes/dump")
                actual = dict(raw_sha256=raw, semantic_sha256=receipt[2], declaration_order_sha256=receipt[3])
                if any(actual[k] != v for k, v in self.profile[section][name].items()):
                    raise Invalid(f"{self.profile['identity_mode']} class identity drift: {name}")
                for method in dump[16]:
                    if not isinstance(method, list) or len(method) != 20:
                        raise Invalid("unsupported V2 method fact schema")
                result[name] = {**actual, "methods": dump[16], "file": str(paths[name])}
        self.observed_class_paths = getattr(self, "observed_class_paths", {})
        self.observed_class_paths.update({row["file"]: row["raw_sha256"] for row in result.values()})
        return result

    def writers(self, observed, classes):
        matrix = observed.get("writer_matrix")
        if matrix is None:
            raise Missing("missing fresh writer matrix")
        keys(matrix, ("scope", "sites"))
        if matrix["scope"] != "OFFLINE_HOOK_CALL_PRESENCE":
            raise Invalid("offline matrix cannot claim live writer closure")
        sites = matrix["sites"]
        if not isinstance(sites, list) or len(sites) != len(self.profile["writer_sites"]):
            raise Missing("incomplete writer matrix")
        rows = {}
        for row in sites:
            keys(row, ("id", "class", "method", "descriptor", "raw_sha256", "status"))
            if row["id"] in rows:
                raise Invalid("duplicate writer observation")
            rows[row["id"]] = row
        for site in self.profile["writer_sites"]:
            row = rows.get(site["id"])
            if row is None:
                raise Missing("missing writer site")
            if any(row[k] != site[k] for k in ("class", "method", "descriptor")) or row["status"] != "PASS":
                raise Invalid("writer site changed/failed")
            klass = classes[site["class"]]
            if row["raw_sha256"] != klass["raw_sha256"]:
                raise Invalid("writer matrix is not bound to observed class bytes")
            methods = [m for m in klass["methods"] if m[:2] == [site["method"], site["descriptor"]]]
            if len(methods) != 1:
                raise Invalid("writer target descriptor absent from parsed class")
            for call in site["required_calls"]:
                count = sum(n[0] == call["opcode"] and n[1][:3] == [call["owner"], call["name"], call["descriptor"]] for n in methods[0][12])
                if count != call["count"]:
                    raise Invalid("actual bytecode hook call count differs from contract")
        return matrix

    def placement(self, observed, classes, pre_classes):
        definition = self.manifest.get("validators", {}).get("placement")
        if definition is None:
            raise Missing("separate pinned placement validator missing")
        if pre_classes is None or classes is None:
            raise Missing("fresh pre/post class identities required for placement validation")
        if pre_classes.keys() != classes.keys():
            raise Invalid("pre/post transformed class sets differ")
        folder = self.output / "placement"
        folder.mkdir(exist_ok=False)
        request_path, output = folder / "request.json", folder / "witness.json"
        request = {"schema": "RUSTCRAFT_PLACEMENT_REQUEST_V2", "session": self.session, "challenge": self.challenge,
                   "acquisition_request_sha256": self.request_sha256, "observation_sha256": self.observation_sha256,
                   "profile_sha256": self.initial_inputs[str(self.profile_path)], "manifest_sha256": self.initial_inputs[str(self.manifest_path)],
                   "classes": classes, "pre_classes": pre_classes, "required_sites": self.profile["writer_sites"]}
        request_path.write_text(json.dumps(request, indent=2) + "\n", encoding="utf-8")
        request_hash = sha(request_path)
        text = self.process([*definition["command"], "--request", str(request_path), "--out", str(output)], definition["timeout_seconds"], "placement-validator", definition["environment"])
        ack, = strict_lines(text, 1)
        keys(ack, ("schema", "session", "challenge", "output_sha256"))
        if ack["schema"] != "RUSTCRAFT_VALIDATOR_ACK_V2" or ack["session"] != self.session or ack["challenge"] != self.challenge:
            raise Invalid("stale placement acknowledgement")
        witness, witness_hash = load_hashed(output)
        keys(witness, ("schema", "session", "challenge", "request_sha256", "observation_sha256", "covered_sites", "checks"))
        if witness["schema"] != "RUSTCRAFT_PLACEMENT_WITNESS_V2" or witness["session"] != self.session or witness["challenge"] != self.challenge or witness["request_sha256"] != request_hash or witness["observation_sha256"] != self.observation_sha256 or ack["output_sha256"] != witness_hash or sha(request_path) != request_hash:
            raise Invalid("placement witness binding drift")
        if sorted(witness["covered_sites"]) != sorted(x["id"] for x in self.profile["writer_sites"]):
            raise Missing("placement witness does not cover exact required sites")
        keys(witness["checks"], ("anchor_order", "exception_paths", "undeclared_edits", "pre_post_relation"))
        for name, check in witness["checks"].items():
            keys(check, ("status", "measurements"))
            if check["status"] == "INCOMPLETE":
                raise Missing(f"placement {name} incomplete")
            if check["status"] != "PASS" or not isinstance(check["measurements"], dict) or not check["measurements"]:
                raise Invalid(f"placement {name} failed or lacks measurements")
        self.observed_class_paths[str(output)] = witness_hash
        return witness

    def controls(self, observed):
        rows = observed.get("negative_controls")
        if rows is None:
            raise Missing("missing fresh negative controls")
        if not isinstance(rows, list):
            raise Invalid("negative controls must be a list")
        by_id = {}
        for row in rows:
            keys(row, ("id", "actual_outcome", "evidence_file", "evidence_sha256"))
            if row["id"] in by_id:
                raise Invalid("duplicate negative control")
            path = relative(self.output, row["evidence_file"])
            witness, witness_hash = load_hashed(path)
            if witness_hash != digest(row["evidence_sha256"]):
                raise Invalid("negative control evidence hash drift")
            keys(witness, ("schema", "session", "challenge", "request_sha256", "id", "outcome", "measurements"))
            if witness["schema"] != "RUSTCRAFT_NEGATIVE_CONTROL_V2" or witness["session"] != self.session or witness["challenge"] != self.challenge or witness["request_sha256"] != self.request_sha256:
                raise Invalid("stale/cross-session negative control evidence")
            if witness["id"] != row["id"] or witness["outcome"] != row["actual_outcome"] or not isinstance(witness["measurements"], dict) or not witness["measurements"]:
                raise Invalid("control receipt lacks concrete observations")
            by_id[row["id"]] = row
            self.observed_class_paths = getattr(self, "observed_class_paths", {})
            self.observed_class_paths[str(path)] = witness_hash
        expected = {x["id"]: x["expected_outcome"] for x in self.profile["negative_controls"]}
        if by_id.keys() - expected.keys():
            raise Invalid("unexpected negative control observations")
        if expected.keys() - by_id.keys():
            raise Missing("required negative controls missing")
        if any(by_id[k]["actual_outcome"] != v for k, v in expected.items()):
            raise Invalid("negative control failed")
        return rows

    def live(self, observed):
        # A pinned offline collector's JSON is not proof that a live campaign
        # occurred. A separate live adapter/validator remains a mandatory gate.
        if observed.get("live") is not None:
            raise Invalid("offline collector may not promote self-reported live evidence")
        raise Missing("live execution writer/lifecycle validator not supplied; offline evidence cannot establish closure")

    def frame_evidence(self, observed, classes):
        """Blocker #1 as an engine gate: a frame proof, or INCOMPLETE.

        The witness is produced inside the real launch by the same process that
        transformed the classes, because the obfuscated server jar leaves no
        other honest oracle. A missing witness leaves the stage unpromoted; a
        present but wrong-scoped or unbound one is a FAIL.
        """
        if classes is None:
            raise Missing("fresh class identity is required before frame evidence can be bound")
        witness = observed.get("frame_relation_witness")
        if witness is None:
            raise Missing("no frame-relation witness was captured; whole-class frame proof is unavailable")
        if isinstance(witness, str):
            # The real witness is carried verbatim out of the launched JVM.
            try:
                witness = parse_json(witness)
            except (Invalid, json.JSONDecodeError) as error:
                raise Invalid(f"malformed frame-relation witness: {error}") from error
        required = self.frame_requirement["required_classes"] if self.frame_requirement else sorted(classes)
        try:
            validate_frames(witness, required)
        except FrameEvidenceError as error:
            raise Invalid(f"frame-relation evidence rejected: {error}") from error
        return {"witness": witness, "provenance": describe_frames(witness),
                "required_classes": required,
                "unproven_classes": self.frame_requirement["unproven_classes"] if self.frame_requirement
                else sorted({name.replace(".", "/") for name in classes} - set(required))}

    def session_evidence(self, observed, classes, pre_classes):
        """Blocker #2 as an engine gate: session evidence, or INCOMPLETE.

        In an exact identity mode this node records that no session evidence is
        required and none is claimed -- normalization is not in play, so there is
        nothing to certify. In session-bound mode the certificates must agree
        with the exact identities the engine recomputed from the observed bytes,
        and must be bound to a same-process acquisition record. Structure alone
        never authorizes masking: if the block is absent the stage stays
        INCOMPLETE, and it can never fall back to exact-mode silence.

        The certificate is an ADMISSION certificate: every identity field in it
        describes the exact buffer the writer was handed and is about to mutate.
        So it is checked against `pre_classes` -- the pre-writer bytes this engine
        recomputed itself from the same capture -- and never against `classes`,
        which holds the bytes the writer produced. Checking it against the
        post-writer class would ask one field to describe two different class
        states, and would in effect demand that the pre-write gate accept bytes
        that did not exist when it ran. The post-writer and finally-defined bytes
        are not left unproven: the transformation_chain node proves them
        separately and binds them to this same acquisition and certificate.
        """
        block = self.session_binding
        if block is None:
            if observed.get("session_acquisition") is not None or observed.get("session_certificates") is not None:
                raise Invalid("an exact identity profile may not carry session evidence")
            return {"identity_mode": self.profile["identity_mode"],
                    "session_bound": False,
                    "note": "exact identity mode: no value-level normalization is authorized or claimed"}
        if classes is None or pre_classes is None:
            raise Missing("fresh pre-writer and post-writer class identity are required before session certificates can be checked")
        acquisition = observed.get("session_acquisition")
        if acquisition is None:
            raise Missing("no same-process acquisition evidence accompanies the session certificates")
        # Two identities, deliberately distinct:
        #   raw file hash  -- drift/tamper detection for the manifest document
        #                     itself (the `unchanged` node)
        #   canonical id   -- what the static recipe and the runtime-issued
        #                     certificates are bound to, which must be
        #                     location-independent because the manifest embeds
        #                     machine-local command lines as execution
        #                     provenance
        manifest_hash = canonical_manifest_identity(self.manifest)
        self.canonical_manifest_id = manifest_hash
        acquisition_hash = digest_json(acquisition)
        rows = {}
        if not isinstance(acquisition, list):
            raise Invalid("session acquisition evidence must be a list of per-class records")
        for row in acquisition:
            keys(row, ("binary_name", "pre_writer_raw_sha256", "post_writer_raw_sha256",
                       "defining_loader_identity", "hook_placement", "definition_succeeded",
                       "session_invariant_sha256"))
            name = row["binary_name"].replace(".", "/")
            if name in rows or name not in block["classes"]:
                raise Invalid("unexpected or duplicate session acquisition record: " + name)
            digest(row["pre_writer_raw_sha256"])
            digest(row["post_writer_raw_sha256"])
            if row["pre_writer_raw_sha256"] == row["post_writer_raw_sha256"]:
                # The writers must have actually changed something; a record
                # where pre equals post is not an acquisition, it is a no-op.
                raise Invalid("session acquisition record shows no writer effect: " + name)
            if not isinstance(row["defining_loader_identity"], str) or not row["defining_loader_identity"]:
                raise Invalid("session acquisition record has no defining loader identity: " + name)
            if row["hook_placement"] not in ("PLACED", "REFUSED"):
                raise Invalid("unknown hook placement outcome for " + name)
            if row["definition_succeeded"] is not True:
                raise Invalid("no successful class definition was observed: " + name)
            if row["session_invariant_sha256"] is not None:
                digest(row["session_invariant_sha256"])
            rows[name] = row
        if set(rows) != set(block["classes"]):
            raise Missing("session acquisition evidence does not cover every certified class")
        # The certificates are the run's own, not the profile's. Absent evidence
        # is INCOMPLETE (unpromoted); present-but-wrong is FAIL, and a certificate
        # carried over from another launch necessarily is, because it names a
        # different process, session, loader and acquisition evidence hash.
        certificates = observed.get("session_certificates")
        if certificates is None or certificates == {}:
            # A run that issued no certificate at all has not disproven anything;
            # it has simply not proven the admission, so the profile stays
            # unpromoted rather than scored as a discovered defect.
            raise Missing("no runtime-issued session certificates accompany this capture; "
                          "the engine will not synthesize one")
        if not isinstance(certificates, dict):
            raise Invalid("runtime-issued session certificates must be keyed by class name")
        if set(certificates) != set(block["classes"]):
            raise Invalid("runtime-issued session certificates do not cover exactly the declared classes")
        issued = []
        for name in block["classes"]:
            document = certificates[name]
            try:
                validate_certificate(document)
            except CertificateError as error:
                raise Invalid(f"session certificate for {name} is unusable: {error}") from error
            if document["class_name"].replace(".", "/") != name:
                raise Invalid(f"session certificate names a different class: {name}")
            if document["process_id"] != block["process_id"] or document["transformation_session_id"] != block["transformation_session_id"]:
                raise Invalid("session certificate is bound to a different process/transformation session: " + name)
            if document["defining_loader_identity"] != rows[name]["defining_loader_identity"]:
                raise Invalid("session certificate and acquisition disagree on the defining loader: " + name)
            if document.get("runtime_manifest_sha256") != manifest_hash:
                # A manifest binding may be absent only when the issuing plan
                # published none; the engine always has one, so absence here is a
                # certificate that declines to say which manifest it ran under.
                raise Invalid("session certificate is not bound to this manifest: " + name)
            if document.get("acquisition_evidence_sha256") not in (None, acquisition_hash):
                # A live certificate is minted during the class transformation and
                # cannot yet name the acquisition record that will describe the
                # writer run. When it does name one, that one must be THIS run's.
                raise Invalid("session certificate is not bound to this acquisition evidence: " + name)
            if document["pre_writer_raw_sha256"] != rows[name]["pre_writer_raw_sha256"]:
                raise Invalid("session certificate pre-writer identity differs from the acquisition: " + name)
            if document["session_invariant_sha256"] != rows[name]["session_invariant_sha256"]:
                raise Invalid("session certificate invariant differs from the acquisition: " + name)
            # The certificate authorizes the PRE-writer class, so the engine
            # recomputes that class's exact identity itself and compares here.
            admitted = pre_classes.get(name)
            if admitted is None:
                raise Missing("session certificate names a class whose pre-writer identity was not recomputed: " + name)
            if (document["exact_semantic_sha256"] != admitted["semantic_sha256"]
                    or document["exact_declaration_order_sha256"] != admitted["declaration_order_sha256"]):
                # The whole point: the certificate claims an exact identity for
                # the bytes the writer is about to mutate, and the engine
                # recomputed those bytes itself. A post-writer identity here
                # would be a certificate that admits a class it never saw.
                raise Invalid("session certificate admission identity differs from the recomputed PRE-writer V2 identity: " + name)
            produced = classes.get(name)
            if produced is None:
                raise Missing("session certificate names a class whose post-writer identity was not recomputed: " + name)
            if produced["semantic_sha256"] == admitted["semantic_sha256"]:
                raise Invalid("post-writer identity equals the admitted pre-writer identity; the writers proved nothing: " + name)
            # The certificate is evidence of an admission, so it has to name the
            # policy that granted it, and the mask provenance the policy
            # authorized has to be the provenance actually observed. Without
            # this a certificate could be minted against a policy that allowed a
            # different annotation, a different number of sites, or a second
            # session UUID, and still describe this class correctly.
            policy = block["admission_policies"][name]
            if policy["runtime_manifest_sha256"] != manifest_hash:
                raise Invalid("admission policy is not bound to this manifest: " + name)
            if policy["recipe_sha256"] != block["recipe_sha256"]:
                raise Invalid("admission policy is not bound to this profile's recipe: " + name)
            if policy["runtime_profile"] != self.profile["id"]:
                # The policy authorizes admission for one qualified profile. A
                # policy carried across to a different one would be authorizing
                # bytes it was never qualified against.
                raise Invalid("admission policy is not bound to this qualified profile: " + name)
            if document.get("policy_sha256") != policy_sha256(policy):
                raise Invalid("session certificate does not record the admission policy that authorized it: " + name)
            if document["session_invariant_sha256"] != policy["expected_session_invariant_sha256"]:
                raise Invalid("session certificate invariant is not the one the policy authorized: " + name)
            if sorted(document["masked_annotation_locations"]) != sorted(policy["expected_masked_locations"]):
                raise Invalid("session certificate masks different annotation sites than the policy authorized: " + name)
            if document["masked_occurrence_count"] != policy["expected_masked_occurrence_count"]:
                raise Invalid("session certificate masks a different number of occurrences than the policy authorized: " + name)
            if document["distinct_masked_uuid_count"] != policy["expected_distinct_masked_uuid_count"]:
                raise Invalid("session certificate observed a different number of distinct session UUIDs than the policy authorized: " + name)
            if not re.fullmatch(policy["expected_session_uuid_shape"], document["expected_session_uuid"] or ""):
                raise Invalid("session certificate does not record a session UUID of the authorized shape: " + name)
            issued.append(name)
        return {"identity_mode": self.profile["identity_mode"], "session_bound": True,
                "process_id": block["process_id"], "transformation_session_id": block["transformation_session_id"],
                "recipe_sha256": block["recipe_sha256"], "acquisition_evidence_sha256": acquisition_hash,
                "certifies_stage": "PRE_WRITER",
                "certified_classes": sorted(issued)}


    def transformation_chain(self, observed, classes, pre_classes):
        """The PRE -> RUSTCRAFT_POST -> ... -> FINAL chain, or INCOMPLETE.

        The session certificate is an admission certificate: it authorizes the
        pre-writer buffer. It says nothing about what the writer then produced.
        This node is where the rest of the lifecycle is proven, and it is proven
        as a chain rather than as a second identity so that no stage can be
        swapped for another: every adjacent pair must satisfy
        `previous.output_sha256 == next.input_sha256`, and every stage must carry
        the same process id, transformation session id and defining loader the
        certificate was bound to.

        RustCraft is NOT assumed to be the last transformer. If the witness
        reports downstream transformers after the live writers, a FINAL_DEFINED
        stage observing the bytes the loader actually received is mandatory; its
        absence is INCOMPLETE, not a pass. The FINAL stage is compared against
        the engine's own recomputed post-writer identity for the final
        downstream-free case, and against the witness's own observed final bytes
        otherwise -- never assumed equal to the pre-writer admission.
        """
        block = self.session_binding
        if block is None:
            if observed.get("transformation_chain") is not None:
                raise Invalid("an exact identity profile may not carry a session transformation chain")
            return {"required": False,
                    "note": "exact identity mode: no session-bound chain is authorized or claimed"}
        if classes is None or pre_classes is None:
            raise Missing("fresh pre-writer and post-writer identity are required before a chain can be checked")
        chain = observed.get("transformation_chain")
        if chain is None:
            raise Missing("no same-process transformation chain accompanies the session certificates")
        if not isinstance(chain, dict):
            raise Invalid("the transformation chain must be a single per-session document")
        keys(chain, ("schema", "schema_version", "process_id", "transformation_session_id",
                     "defining_loader_identity", "acquisition_evidence_sha256",
                     "downstream_transformers_after_live_writers", "classes"))
        if chain["schema"] != CHAIN_SCHEMA or chain["schema_version"] != CHAIN_VERSION:
            raise Invalid("unsupported transformation chain schema/version")
        if chain["process_id"] != block["process_id"] or chain["transformation_session_id"] != block["transformation_session_id"]:
            raise Invalid("the transformation chain belongs to a different process/transformation session")
        if chain["acquisition_evidence_sha256"] != digest_json(observed["session_acquisition"]):
            raise Invalid("the transformation chain is not bound to this acquisition evidence")
        if not isinstance(chain["classes"], list):
            raise Invalid("the transformation chain must list per-class chains")
        if set(row.get("binary_name", "").replace(".", "/") for row in chain["classes"]) != set(block["classes"]):
            raise Invalid("the transformation chain does not cover exactly the certified classes")
        downstream = chain["downstream_transformers_after_live_writers"]
        if not isinstance(downstream, list) or any(not isinstance(name, str) or not name for name in downstream):
            raise Invalid("downstream_transformers_after_live_writers must be a transformer name list")
        # The PRE_WRITER stage is checked against the certificate THIS run issued,
        # taken from the same observation the chain came from. Reading it from
        # the profile instead would let a written-down plan vouch for bytes no
        # JVM in this run ever admitted.
        issued = observed.get("session_certificates")
        if issued is None or issued == {}:
            # The chain's PRE_WRITER stage stands on the admission the run
            # actually performed. With no admission to stand on, the chain is
            # simply unproven -- the same verdict the session node reaches, not a
            # stricter one, because nothing has been disproven.
            raise Missing("the chain has no runtime-issued admission certificate to bind its PRE_WRITER stage to")
        definitions = self.final_definitions(observed)
        proven = []
        for row in chain["classes"]:
            proven.append(self.chain_row(row, block, classes, bool(downstream), chain, definitions, issued))
        return {"required": True, "chain_schema": CHAIN_SCHEMA,
                "process_id": block["process_id"],
                "transformation_session_id": block["transformation_session_id"],
                "acquisition_evidence_sha256": chain["acquisition_evidence_sha256"],
                "downstream_transformers_after_live_writers": downstream,
                "classes": proven}

    def final_definitions(self, observed):
        """The JVM-defined bytes the real launch observed, keyed by binary name.

        A chain that only quotes itself proves nothing: anyone can write a
        plausible four-stage story. The FINAL_DEFINED stage is therefore bound
        to the frame witness, which is produced by the loader inside the launch
        and cannot be authored by the same document that writes the chain.
        """
        witness = observed.get("frame_relation_witness")
        if witness is None:
            raise Missing("the final chain stage cannot be bound without the launch's frame witness")
        if isinstance(witness, str):
            try:
                witness = parse_json(witness)
            except (Invalid, json.JSONDecodeError) as error:
                raise Invalid(f"malformed frame-relation witness: {error}") from error
        definitions = {}
        for phase in witness.get("phases", []):
            if phase.get("phase") != "post":
                continue
            for row in phase.get("verification", []):
                definitions[row["name"].replace(".", "/")] = row
        if not definitions:
            raise Missing("the frame witness observed no post-phase class definitions")
        return definitions

    def chain_row(self, row, block, classes, has_downstream, chain, definitions, issued):
        name = row.get("binary_name", "").replace(".", "/")
        keys(row, ("binary_name", "process_id", "transformation_session_id",
                   "defining_loader_identity", "stages"))
        if name not in issued:
            raise Invalid("chain row names a class this run issued no admission certificate for: " + name)
        if row["process_id"] != block["process_id"] or row["transformation_session_id"] != block["transformation_session_id"]:
            raise Invalid("chain row belongs to a different process/transformation session: " + name)
        if row["defining_loader_identity"] != chain["defining_loader_identity"]:
            raise Invalid("chain row names a different defining loader: " + name)
        stages = row["stages"]
        if not isinstance(stages, list) or len(stages) < 2:
            raise Invalid("a chain needs at least a PRE_WRITER and a final stage: " + name)
        ordered = [stage.get("stage") for stage in stages]
        if ordered[0] != STAGE_PRE or ordered[-1] != STAGE_FINAL:
            raise Invalid("a chain must start at PRE_WRITER and end at FINAL_DEFINED: " + name)
        if len(set(ordered)) != len(ordered):
            raise Invalid("a chain may not repeat a stage: " + name)
        certificate = issued[name]
        if ordered.count(STAGE_RUSTCRAFT) != 1:
            raise Invalid("a chain must contain exactly one RUSTCRAFT_POST stage: " + name)
        if ordered[1] != STAGE_RUSTCRAFT:
            raise Invalid("RUSTCRAFT_POST must directly follow PRE_WRITER: " + name)
        if has_downstream and ordered[2:-1] != [STAGE_DOWNSTREAM]:
            # RustCraft is not assumed to be the last transformer. A chain that
            # discloses a downstream transformer and then stops at the writers
            # is asserting that the writer output is what the loader defined --
            # which the disclosure itself contradicts.
            raise Invalid("a disclosed downstream transformer must appear as its own stage: " + name)
        if not has_downstream and len(ordered) != 3:
            raise Invalid("no downstream transformer ran, so the chain must be PRE, RUSTCRAFT_POST, FINAL: " + name)
        previous = None
        rustcraft_output = None
        for index, stage in enumerate(stages):
            keys(stage, ("stage", "ordinal", "process_id", "transformation_session_id",
                         "defining_loader_identity", "class_name", "transformer",
                         "input_raw_sha256", "output_raw_sha256",
                         "exact_semantic_sha256", "exact_declaration_order_sha256",
                         "session_invariant_sha256", "acquisition_evidence_id",
                         "rustcraft_hooks", "exception_paths"))
            if stage["process_id"] != block["process_id"] or stage["transformation_session_id"] != block["transformation_session_id"]:
                raise Invalid("chain stage belongs to a different process/transformation session: " + name)
            if stage["defining_loader_identity"] != chain["defining_loader_identity"]:
                raise Invalid("chain stage names a different defining loader: " + name)
            if stage["class_name"].replace(".", "/") != name:
                raise Invalid("chain stage names a different class: " + name)
            if stage["ordinal"] != index:
                raise Invalid("chain stages must be a dense ascending ordinal sequence: " + name)
            digest(stage["input_raw_sha256"])
            digest(stage["output_raw_sha256"])
            # A transformation stage that changed nothing did not transform
            # anything. FINAL_DEFINED is not a transformation stage: it is the
            # observation of a definition event, and when the writers are the
            # last transformers the loader defines exactly the buffer they
            # returned. It is exempted from the no-op rule ONLY under the
            # witnessed conditions in the FINAL branch below; every other stage,
            # and every FINAL that actually altered bytes, is still refused.
            if stage["stage"] != STAGE_FINAL and stage["input_raw_sha256"] == stage["output_raw_sha256"]:
                raise Invalid("chain stage is a no-op: " + stage["stage"] + " for " + name)
            if previous is not None and stage["input_raw_sha256"] != previous["output_raw_sha256"]:
                # This is the whole point of a chain: no stage may be swapped
                # for another, and no gap may be papered over.
                raise Invalid("broken adjacent transformation hash link before " + stage["stage"] + ": " + name)
            previous = stage
            if stage["stage"] == STAGE_PRE:
                if stage["transformer"] != "none" and stage["transformer"] != "":
                    raise Invalid("the PRE_WRITER stage must name no transformer: " + name)
                if stage["output_raw_sha256"] != certificate["pre_writer_raw_sha256"]:
                    raise Invalid("PRE_WRITER output is not the bytes the certificate admits: " + name)
                if stage["exact_semantic_sha256"] != certificate["exact_semantic_sha256"] \
                        or stage["exact_declaration_order_sha256"] != certificate["exact_declaration_order_sha256"]:
                    raise Invalid("PRE_WRITER identity is not the certified admission identity: " + name)
                if stage["session_invariant_sha256"] != certificate["session_invariant_sha256"]:
                    raise Invalid("PRE_WRITER session invariant is not the certified invariant: " + name)
            elif stage["stage"] == STAGE_RUSTCRAFT:
                if stage["transformer"] not in RUSTCRAFT_TRANSFORMERS:
                    raise Invalid("RUSTCRAFT_POST names a transformer that is not a live writer: " + name)
                produced = classes.get(name)
                if produced is None:
                    raise Missing("post-writer identity was not recomputed: " + name)
                if stage["output_raw_sha256"] != produced["raw_sha256"]:
                    raise Invalid("RUSTCRAFT_POST output is not the post-writer class the engine observed: " + name)
                if stage["exact_semantic_sha256"] != produced["semantic_sha256"] \
                        or stage["exact_declaration_order_sha256"] != produced["declaration_order_sha256"]:
                    # Independently recomputed: the engine parsed the bytes
                    # itself and did not take the chain's word for them.
                    raise Invalid("RUSTCRAFT_POST identity is not the engine's recomputed post-writer identity: " + name)
                self.check_rustcraft_effect(stage, name)
                rustcraft_output = stage["output_raw_sha256"]
            elif stage["stage"] == STAGE_DOWNSTREAM:
                if not chain["downstream_transformers_after_live_writers"]:
                    raise Invalid("a DOWNSTREAM stage was recorded with no downstream transformer: " + name)
                if stage["transformer"] not in chain["downstream_transformers_after_live_writers"]:
                    raise Invalid("a downstream stage names a transformer that is not in the reported chain: " + name)
                self.check_hook_survival(stage, name, "downstream")
            elif stage["stage"] == STAGE_FINAL:
                if stage["transformer"] != "class-loader-definition":
                    raise Invalid("the FINAL_DEFINED stage must be the loader definition: " + name)
                self.check_hook_survival(stage, name, "final")
                # Independently observed: the launch's own witness, not the chain.
                defined = definitions.get(name)
                if defined is None:
                    raise Missing("no post-phase class definition was observed for " + name)
                if stage["output_raw_sha256"] != defined["observed_raw_sha256"]:
                    raise Invalid("the FINAL_DEFINED bytes are not the bytes the launch observed: " + name)
                if stage["defining_loader_identity"] != defined["defining_loader"]:
                    raise Invalid("the FINAL_DEFINED stage was not defined by the reported loader: " + name)
                if defined.get("rustcraft_post_writer_sha256") != rustcraft_output:
                    raise Invalid("the launch's witness does not confirm the RUSTCRAFT_POST output: " + name)
                if defined.get("status") != "VERIFIED":
                    # The witness has to have actually verified these bytes, not
                    # merely recorded them. A witness that observed a class and
                    # could not establish anything about it is an observation,
                    # not a definition event, and the difference has to decide
                    # the verdict.
                    raise Missing("the launch's witness did not verify this class definition: " + name)
                if stage["input_raw_sha256"] != stage["output_raw_sha256"]:
                    # FINAL_DEFINED altered the bytes. When a downstream
                    # transformer is disclosed, that is what a DOWNSTREAM stage
                    # is for and the chain must name it. When none is, nothing
                    # in this chain can have caused the change, so the extra edge
                    # is unexplained rather than merely unusual.
                    if not has_downstream:
                        raise Invalid("FINAL_DEFINED changed the bytes with no downstream transformer "
                                      "to account for it: " + name)
                else:
                    # A confirmation stage. Every condition that makes it
                    # meaningful is already enforced above -- terminal position,
                    # adjacency to the previous stage, this process, this
                    # session, this class, this loader, hook and exception-path
                    # survival, and an independently verified observation of
                    # exactly these bytes. The one thing left to insist on is
                    # that no downstream transformer is being concealed: if one
                    # is disclosed, the bytes must actually differ, or the chain
                    # is hiding a stage rather than recording one.
                    if has_downstream:
                        raise Invalid("FINAL_DEFINED confirms the last stage's bytes while a downstream "
                                      "transformer is disclosed: the chain is not accounting for it: " + name)
            else:
                raise Invalid("unknown chain stage: " + str(stage["stage"]))
        return {"binary_name": name, "stages": [s["stage"] for s in stages],
                "final_raw_sha256": stages[-1]["output_raw_sha256"]}

    def check_rustcraft_effect(self, stage, name):
        """The RustCraft POST stage must show the hooks, the counts, the
        exception paths, and the absence of undeclared edits."""
        if not isinstance(stage["rustcraft_hooks"], list) or not stage["rustcraft_hooks"]:
            raise Invalid("RUSTCRAFT_POST declares no hooks: " + name)
        if not isinstance(stage["exception_paths"], list) or not stage["exception_paths"]:
            raise Invalid("RUSTCRAFT_POST declares no exception paths: " + name)
        for hook in stage["rustcraft_hooks"]:
            keys(hook, (*CHAIN_HOOK_KEYS, "required_calls"))
            if hook["class"].replace(".", "/") != name:
                raise Invalid("a declared hook belongs to a different class: " + name)
            if not isinstance(hook["required_calls"], int) or not isinstance(hook["observed_calls"], int):
                raise Invalid("hook call counts must be integers: " + name)
            if hook["required_calls"] != hook["observed_calls"]:
                raise Invalid("declared hook call count differs from the observed count: " + name + " " + hook["id"])
        for path in stage["exception_paths"]:
            keys(path, CHAIN_PATH_KEYS)
            if path["class"].replace(".", "/") != name:
                raise Invalid("a declared exception path belongs to a different class: " + name)
            if not isinstance(path["handler"], str) or not path["handler"]:
                raise Invalid("an exception path declares no handler: " + name)

    def check_hook_survival(self, stage, name, where):
        """Whatever ran after RustCraft must have left the hooks and the
        exception coverage intact. A downstream transformer that strips them
        would make the instrumented class unobservable at runtime, and a
        certificate that authorized a transformation nobody can see is not
        evidence of anything."""
        if stage["rustcraft_hooks"] is None and stage["exception_paths"] is None:
            raise Missing(f"{where} stage does not report hook survival for {name}")
        if not isinstance(stage["rustcraft_hooks"], list) or not stage["rustcraft_hooks"]:
            raise Invalid(f"{where} stage reports no RustCraft hooks: " + name)
        for hook in stage["rustcraft_hooks"]:
            keys(hook, CHAIN_HOOK_KEYS, ("required_calls",))
            if hook["class"].replace(".", "/") != name:
                raise Invalid(f"a surviving hook belongs to a different class: " + name)
            if not isinstance(hook["observed_calls"], int):
                raise Invalid("hook call counts must be integers: " + name)
            if hook["observed_calls"] < 1:
                raise Invalid(f"a required hook is missing at the {where} stage: " + name + " " + hook["id"])
        if not isinstance(stage["exception_paths"], list) or not stage["exception_paths"]:
            raise Invalid(f"{where} stage reports no exception coverage: " + name)
        for path in stage["exception_paths"]:
            keys(path, CHAIN_PATH_KEYS)
            if path["class"].replace(".", "/") != name:
                raise Invalid(f"a surviving exception path belongs to a different class: " + name)
            if not isinstance(path["handler"], str) or not path["handler"]:
                raise Invalid(f"an exception path lost its handler at the {where} stage: " + name + " " + path["id"])

    def unchanged(self):
        if self.initial_inputs != {p: sha(Path(p)) for p in self.initial_inputs}:
            raise Invalid("profile/manifest changed during qualification")
        if self.initial_tools != self.tools() or self.initial_inventory != self.inventory():
            raise Invalid("tool/runtime inventory changed during qualification")
        if hasattr(self, "observation_sha256") and sha(self.output / "observation.json") != self.observation_sha256:
            raise Invalid("observation changed after collection")
        if hasattr(self, "observed_class_paths") and any(sha(Path(p)) != v for p, v in self.observed_class_paths.items()):
            raise Invalid("transformed class changed after parsing")
        return {"inputs": self.initial_inputs, "tools": self.initial_tools, "inventory": self.initial_inventory}

    def run(self, requested=M.OFFLINE_QUALIFIED):
        definitions = self.node("definitions", "profile_manifest", (), self.definitions)
        if definitions is not None:
            self.initial_inventory = self.node("inventory", "exact_runtime_mod_config_inventory", ("definitions",), self.inventory)
            self.initial_tools = self.node("tools", "pinned_executable_inputs", ("definitions",), self.tools)
            if self.initial_inventory is not None and self.initial_tools is not None:
                # Forward reference is deliberate: final drift invalidates every
                # observation descendant, not merely the top-level stage result.
                observed = self.node("acquisition", "fresh_offline_observation", ("inventory", "tools", "unchanged"), self.acquire)
                if observed is not None:
                    self.node("runtime", "runtime_loader_coremod_identity", ("acquisition",), lambda: self.runtime(observed))
                    classes = self.node("classes", "recomputed_class_identity", ("runtime", "tools"), lambda: self.classes(observed))
                    pre_classes = self.node("pre_classes", "recomputed_pre_transform_identity", ("runtime", "tools"), lambda: self.classes(observed, "pre_classes"))
                    if classes is not None:
                        self.node("writers", "offline_hook_call_presence_only", ("classes",), lambda: self.writers(observed, classes))
                    self.node("placement", "independent_offline_placement_validation", ("writers", "classes", "pre_classes", "tools"), lambda: self.placement(observed, classes, pre_classes))
                    self.node("controls", "negative_control_witnesses", ("acquisition", "classes"), lambda: self.controls(observed))
                    # Both blocker gates sit above recomputed class identity: a
                    # frame proof and a session proof are only meaningful once
                    # the bytes they describe have been independently identified.
                    self.node("frame_evidence", "real_launch_frame_relation_and_hierarchy_proof", ("acquisition", "classes"), lambda: self.frame_evidence(observed, classes))
                    # The certificate authorizes PRE; the chain proves what the
                    # writers then produced and what the loader finally defined.
                    # Neither substitutes for the other.
                    self.node("session_evidence", "session_bound_identity_evidence", ("acquisition", "classes", "pre_classes"), lambda: self.session_evidence(observed, classes, pre_classes))
                    self.node("transformation_chain", "same_process_transformation_chain", ("session_evidence", "classes", "pre_classes"), lambda: self.transformation_chain(observed, classes, pre_classes))
                    self.node("live_closure", "live_writer_lifecycle_closure", ("writers", "controls"), lambda: self.live(observed))
                self.node("unchanged", "end_of_run_drift_check", ("inventory", "tools"), self.unchanged)
        log_path = self.output / "process-log.json"
        log_path.write_text(json.dumps(self.logs, indent=2) + "\n", encoding="utf-8")
        # Malformed top-level profile types already produced a FAIL node. Safe
        # reporting must not dereference that invalid value or erase its failure.
        reporting = self.profile if isinstance(self.profile, dict) else {}
        context = {"profile_id": reporting.get("id") or "UNRESOLVED_PROFILE", "observation_session": self.session,
                   "identity_schema": reporting.get("identity_mode") if reporting.get("identity_mode") in IDENTITY_MODES else "CANONICAL_ID_V2",
                   "challenge": self.challenge, "scope": reporting.get("scope"), "capture_kind": "OFFLINE_TRANSFORM_CAPTURE",
                   "output_directory": str(self.output), "requested_profile_identity": reporting.get("identity_mode"), "process_log_sha256": sha(log_path)}
        # Frame and session evidence gate OFFLINE_QUALIFIED, not OBSERVED: an
        # OBSERVED profile is a record of what was seen, and saying so without
        # them is honest. Any claim of qualification needs both, and a missing
        # one lands on INCOMPLETE rather than a vacuous PASS.
        requirements = {M.OBSERVED: ("definitions", "inventory", "tools", "acquisition", "runtime", "classes", "unchanged"),
                        M.OFFLINE_QUALIFIED: ("pre_classes", "writers", "placement", "controls", "frame_evidence", "session_evidence", "transformation_chain"),
                        M.LIVE_QUALIFIED: ("live_closure",),
                        M.SHADOW_VALIDATED: ("live_shadow",), M.PERFORMANCE_QUALIFIED: ("complete_performance",),
                        M.AUTHORITY_AUTHORIZED: ("explicit_authority_approval",)}
        certificate = evaluate(self.records, requirements, context=context, requested=requested)
        (self.output / "certificate.json").write_text(json.dumps(certificate, indent=2) + "\n", encoding="utf-8")
        return certificate


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--profile", type=Path, required=True)
    parser.add_argument("--output-root", type=Path, required=True)
    parser.add_argument("--requested", choices=[x.value for x in M], default=M.OFFLINE_QUALIFIED.value)
    args = parser.parse_args()
    engine = QualificationEngine(args.manifest, args.profile, args.output_root)
    cert = engine.run(M(args.requested))
    print(json.dumps({"status": cert["status"], "maturity": cert["maturity"], "production_authority": False, "certificate": str(engine.output / "certificate.json")}))
    return {"PASS": 0, "FAIL": 1, "INCOMPLETE": 2}[cert["status"]]


if __name__ == "__main__":
    sys.exit(main())
