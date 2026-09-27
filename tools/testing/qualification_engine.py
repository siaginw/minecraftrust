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
import secrets
import subprocess
import sys
import uuid

try:
    from tools.testing.qualification_certificate import Evidence, EvidenceStatus as S, Maturity as M, digest_json, evaluate
except ModuleNotFoundError:
    from qualification_certificate import Evidence, EvidenceStatus as S, Maturity as M, digest_json, evaluate


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


def load(path: Path):
    if not path.is_file():
        raise Missing(f"missing file: {path}")
    try:
        return parse_json(path.read_text(encoding="utf-8"))
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
        self.manifest, self.profile = load(self.manifest_path), load(self.profile_path)
        m, p = self.manifest, self.profile
        keys(m, ("schema", "runtime_root", "inventories", "collector", "identity_tool"), ("validators",))
        keys(p, ("schema", "id", "identity_mode", "runtime_identity", "transformer_chain", "coremods", "classes", "writer_sites", "negative_controls", "scope", "production_authority"), ("pre_classes",))
        if m["schema"] != "RUSTCRAFT_RUNTIME_MANIFEST_V2" or p["schema"] != "RUSTCRAFT_QUALIFICATION_PROFILE_V2":
            raise Invalid("unsupported manifest/profile schema")
        if p["identity_mode"] not in ("RAW_SHA256", "CANONICAL_ID_V2") or p["production_authority"] is not False:
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
        self.initial_inputs = {str(self.manifest_path): sha(self.manifest_path), str(self.profile_path): sha(self.profile_path)}
        return self.initial_inputs

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
        observed = load(out)
        if ack["output_sha256"] != sha(out):
            raise Invalid("collector acknowledgement/output hash drift")
        keys(observed, ("schema", "session", "challenge", "request_sha256", "capture_kind", "runtime_identity", "transformer_chain", "coremods", "classes"), ("pre_classes", "writer_matrix", "negative_controls", "live"))
        if observed["schema"] != "RUSTCRAFT_FRESH_OBSERVATION_V2" or observed["session"] != self.session or observed["challenge"] != self.challenge or observed["request_sha256"] != self.request_sha256:
            raise Invalid("stale/cross-session/request-substituted observation")
        if observed["capture_kind"] != "OFFLINE_TRANSFORM_CAPTURE":
            raise Invalid("this engine adapter only accepts explicit offline transformed capture")
        if sha(request_path) != self.request_sha256:
            raise Invalid("collector changed its input request")
        self.observation_sha256 = sha(out)
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
                    raise Invalid("identity receipt schema/name mismatch")
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
        witness = load(output)
        keys(witness, ("schema", "session", "challenge", "request_sha256", "observation_sha256", "covered_sites", "checks"))
        if witness["schema"] != "RUSTCRAFT_PLACEMENT_WITNESS_V2" or witness["session"] != self.session or witness["challenge"] != self.challenge or witness["request_sha256"] != request_hash or witness["observation_sha256"] != self.observation_sha256 or ack["output_sha256"] != sha(output) or sha(request_path) != request_hash:
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
        self.observed_class_paths[str(output)] = sha(output)
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
            if sha(path) != digest(row["evidence_sha256"]):
                raise Invalid("negative control evidence hash drift")
            witness = load(path)
            keys(witness, ("schema", "session", "challenge", "request_sha256", "id", "outcome", "measurements"))
            if witness["schema"] != "RUSTCRAFT_NEGATIVE_CONTROL_V2" or witness["session"] != self.session or witness["challenge"] != self.challenge or witness["request_sha256"] != self.request_sha256:
                raise Invalid("stale/cross-session negative control evidence")
            if witness["id"] != row["id"] or witness["outcome"] != row["actual_outcome"] or not isinstance(witness["measurements"], dict) or not witness["measurements"]:
                raise Invalid("control receipt lacks concrete observations")
            by_id[row["id"]] = row
            self.observed_class_paths = getattr(self, "observed_class_paths", {})
            self.observed_class_paths[str(path)] = sha(path)
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
                    self.node("live_closure", "live_writer_lifecycle_closure", ("writers", "controls"), lambda: self.live(observed))
                self.node("unchanged", "end_of_run_drift_check", ("inventory", "tools"), self.unchanged)
        log_path = self.output / "process-log.json"
        log_path.write_text(json.dumps(self.logs, indent=2) + "\n", encoding="utf-8")
        # Malformed top-level profile types already produced a FAIL node. Safe
        # reporting must not dereference that invalid value or erase its failure.
        reporting = self.profile if isinstance(self.profile, dict) else {}
        context = {"profile_id": reporting.get("id") or "UNRESOLVED_PROFILE", "observation_session": self.session,
                   "identity_schema": reporting.get("identity_mode") if reporting.get("identity_mode") in ("RAW_SHA256", "CANONICAL_ID_V2") else "CANONICAL_ID_V2",
                   "challenge": self.challenge, "scope": reporting.get("scope"), "capture_kind": "OFFLINE_TRANSFORM_CAPTURE",
                   "output_directory": str(self.output), "requested_profile_identity": reporting.get("identity_mode"), "process_log_sha256": sha(log_path)}
        requirements = {M.OBSERVED: ("definitions", "inventory", "tools", "acquisition", "runtime", "classes", "unchanged"),
                        M.OFFLINE_QUALIFIED: ("pre_classes", "writers", "placement", "controls"), M.LIVE_QUALIFIED: ("live_closure",),
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
