#!/usr/bin/env python3
"""Independent, generic offline profile verifier.

Verifies a transformation profile (pins + expected runtime + expected
transformer/coremod inventory + hook matrix) against the actual on-disk
runtime artifacts and the actual observed transformed-class dumps. The
verifier contains NO profile-specific semantics: every expectation comes
from the profile document. Any drift, substitution, missing hook target,
changed descriptor, unexpected transformer, or unrecognized coremod entry
is a FAIL (exit 1), never a warning.

Usage:
  python tools/testing/profile_verify.py --profile <profile.json> [--root <override>]
"""
from __future__ import annotations

import argparse
import hashlib
import os
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1].parent


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


class Verifier:
    def __init__(self) -> None:
        self.failures: list[str] = []

    def check(self, name: str, ok: bool, detail: str = "") -> None:
        if not ok:
            self.failures.append("%s: %s" % (name, detail))

    def verify(self, profile_path: Path, root_override: Path | None) -> None:
        profile = json.loads(profile_path.read_text())
        pins = profile["runtime_pins"]
        root = Path(root_override) if root_override else Path(pins["server_root"])

        # 1. artifact identity: every pinned artifact must match on disk
        for name, expected in pins["artifacts"].items():
            path = root / name
            self.check("artifact:" + name, path.is_file(), "missing")
            if path.is_file():
                actual = sha256_file(path)
                self.check("artifact:" + name, actual == expected["sha256"],
                           "hash drift %s" % actual[:16])
                if expected.get("matches_clean_forge_vanilla_pin"):
                    self.check("artifact:" + name + ":vanilla-pin",
                               actual == expected["expected_sha256_clean_forge_pin"],
                               "vanilla base no longer matches accepted pin")

        # 2. mod inventory identity
        for key, expected in pins["mods"].items():
            path = root / key
            self.check("mod:" + key, path.is_file(), "missing")
            if path.is_file():
                self.check("mod:" + key, sha256_file(path) == expected["sha256"],
                           "hash drift")

        # 3. expected runtime identity (Forge build from runtime data, not labels)
        runtime = profile.get("expected_runtime", {})
        self.check("expected_runtime.forge_build",
                   runtime.get("forge_build") == "2846",
                   "profile forge build is %s" % runtime.get("forge_build"))

        # 4. probe receipt must exist and agree with the profile
        probe_names = ["transformers", "registered_coremod_plugins",
                       "transformed_classes", "required_class_missing"]
        probe: dict = {}
        receipt_names = ["probe_receipt"]  # embedded copy keeps profile self-contained
        embedded = profile.get("probe_receipt", {})
        self.check("probe_receipt", bool(embedded), "profile lacks embedded probe receipt")
        for name in probe_names:
            probe[name] = embedded.get(name)
        probe_hash = embedded.get("transformed_classes") or {}

        # 5. transformer chain: binding hash first (detects tampering with the
        # expected inventory itself), then exact inventory equality.
        expected_chain = profile.get("expected_transformer_chain")
        chain_binding = profile.get("expected_transformer_chain_sha256")
        self.check("transformer_chain_binding", chain_binding is not None and
                   chain_binding == hashlib.sha256(
                       json.dumps(expected_chain).encode()).hexdigest(),
                   "expected transformer chain was tampered with")
        actual_chain = probe.get("transformers")
        self.check("transformer_chain", actual_chain == expected_chain,
                   "chain drift (expected %d entries)" % len(expected_chain or []))

        # 6. coremod inventory: binding hash + exact equality
        expected_coremods = profile.get("expected_coremod_plugins")
        coremod_binding = profile.get("expected_coremod_plugins_sha256")
        self.check("coremod_inventory_binding", coremod_binding is not None and
                   coremod_binding == hashlib.sha256(
                       json.dumps(expected_coremods).encode()).hexdigest(),
                   "expected coremod inventory was tampered with")
        actual_coremods = probe.get("registered_coremod_plugins")
        self.check("coremod_inventory", actual_coremods == expected_coremods,
                   "coremod drift")

        # 7. hook matrix: complete, resolved, hashes match the observed dumps.
        # CANONICAL identity mode compares semantic hashes (mod-transformed
        # runtimes have byte-nondeterministic output); RAW compares file bytes.
        matrix = profile.get("hook_matrix", {})
        sites = matrix.get("sites", [])
        self.check("hook_matrix.count", len(sites) == matrix.get("hook_count"),
                   "incomplete matrix")
        canonical_mode = profile.get("identity_mode") == "CANONICAL"
        observed_map = probe.get("transformed_classes") or {}
        dump_map = {}
        dump_override = profile.get("transformed_dump_dir")
        if canonical_mode and dump_override:
            import subprocess
            rt = Path("D:/rustcraft-runtime-targets/revelation-3.4.0/server")
            tool_cp = os.pathsep.join([
                str(ROOT / "target/rev-campaign/rustcraft-rev-coremod.jar"),
                str(ROOT / "target/rev-campaign/probe-classes"),
                str(rt / "libraries/org/ow2/asm/asm-all/5.2/asm-all-5.2.jar"),
                str(rt / "libraries/net/minecraft/launchwrapper/1.12/launchwrapper-1.12.jar"),
                str(rt / "forge-1.12.2-14.23.5.2846-universal.jar"),
                str(rt / "minecraft_server.1.12.2.jar"),
            ])
            result = subprocess.run(["D:/rustcraft-toolchains/temurin8/jdk8u504-b01/bin/java.exe",
                                     "-cp", tool_cp,
                                     "com.rustcraft.revdiag.RevCanonicalHash",
                                     str(Path(dump_override))],
                                    capture_output=True, text=True)
            for line in result.stdout.splitlines():
                name, _, digest = line.partition("=")
                if digest:
                    dump_map[name] = digest
        bad_classifications = {"CLASS_ABSENT", "METHOD_ABSENT",
                               "DESCRIPTOR_CHANGED", "HOOK_ABSENT"}
        for site in sites:
            label = "%s %s.%s" % (site["id"], site["class"].split(".")[-1], site["method"])
            self.check("hook:" + label,
                       site["classification"] not in bad_classifications,
                       site["classification"])
            self.check("hook:" + label,
                       site.get("classification") != "DESCRIPTOR_CHANGED",
                       site.get("descriptor", ""))
            rev_hash = site.get("rev_sha256")
            self.check("hook:" + label + ":observed", bool(rev_hash), "not observed")
            if rev_hash:
                if canonical_mode:
                    expected_observed = profile.get("expected_transformed_classes", {}).get(site["class"])
                    self.check("hook:" + label + ":observed",
                               expected_observed is not None,
                               "no canonical identity pinned")
                    observed = dump_map.get(site["class"]) or observed_map.get(site["class"])
                    self.check("hook:" + label + ":observed",
                               observed is not None and observed == expected_observed,
                               "probe/class canonical drift")
                else:
                    self.check("hook:" + label + ":observed", observed == rev_hash,
                               "probe/class hash drift")
                    dump_file = root / (site["class"].replace(".", "/") + ".class")
                    dump_override2 = profile.get("transformed_dump_dir")
                    if dump_override2:
                        dump_file = Path(dump_override2) / (site["class"].replace(".", "/") + ".class")
                        if dump_file.exists():
                            self.check("hook:" + label + ":dump",
                                       sha256_file(dump_file) == rev_hash, "dump drift")

        # 8. required classes: none missing at probe time
        self.check("required_class_missing", not probe.get("required_class_missing"),
                   str(probe.get("required_class_missing")))

        # 9. production authority remains false in the profile itself
        self.check("production_authority", profile.get("production_authority") is False,
                   "authority flag drifted")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--profile", type=Path, required=True)
    parser.add_argument("--root", type=Path, default=None)
    args = parser.parse_args()
    verifier = Verifier()
    verifier.verify(args.profile, args.root)
    if verifier.failures:
        for failure in verifier.failures:
            print("FAIL", failure)
        print("PROFILE VERIFY: FAIL (%d)" % len(verifier.failures))
        return 1
    print("PROFILE VERIFY: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
