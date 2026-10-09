#!/usr/bin/env python3
"""Canonicalize the session-invariant qualification profile (§25).

The real-launch runner currently completes session-invariant expectations
from the offline evidence at run time. Now that the semantics are proven,
this tool produces the CANONICAL artifact: a profile whose session-bound
rows carry the masked session-invariant identity alongside the derivation
launch's exact hashes, derived once with the pinned identity tool from the
qualified offline evidence. The real launch then consumes the profile
UNCHANGED; the runner's runtime completion retires.

Historical artifacts are never rewritten in place: the tool writes a new
profile next to the source (profile-canonical.json) and the engine accepts
rows with the completed field.

Also extends the canonical artifact with the extra-transformer identity
record (§26: observed lifecycle additions bound with class, origin jar,
artifact SHA-256, loader identity, position) and the coremod artifact
binding (§27: plugin class + artifact basename + artifact SHA-256).
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def sha(path: Path) -> str:
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def session_invariants(static_contract: Path, identity: dict) -> dict:
    profile = json.loads((static_contract / "profile.json").read_text(encoding="utf-8"))
    session_classes = set(profile.get("session_bound", {}).get("classes", []))
    derived = {}
    if not session_classes or profile.get("identity_mode") != "CANONICAL_ID_V2_SESSION_BOUND":
        return derived
    offline = static_contract / "qualifying-launch" / "observation"
    for section, directory in (("classes", "classes"), ("pre_classes", "pre")):
        rows = profile.get(section) or {}
        targets = [n for n in rows if n in session_classes]
        if not targets:
            continue
        files = [str(offline / directory / (n + ".class")) for n in targets]
        cmd = [identity["java"], "-cp", os.pathsep.join(identity["classpath"]),
               "com.rustcraft.coremod.CanonicalClassIdentityV2", "--session-bound", *files]
        env = dict(os.environ)
        env["MSYS2_ARG_CONV_EXCL"] = "*"
        done = subprocess.run(cmd, capture_output=True, text=True, env=env, timeout=600)
        receipts = done.stdout.strip().splitlines()
        if done.returncode or len(receipts) != len(targets):
            raise SystemExit("invariant derivation failed for " + section
                             + ": " + done.stderr[:300])
        for name, receipt in zip(targets, receipts):
            row = json.loads(receipt)
            derived[(section, name)] = row[5]
    return derived


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--static-contract", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True,
                        help="output directory for the canonical artifact set")
    args = parser.parse_args()

    contract = args.static_contract.resolve()
    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=True)
    profile = json.loads((contract / "profile.json").read_text(encoding="utf-8"))
    identity = json.loads((contract / "collector-config.json")
                          .read_text(encoding="utf-8"))["identity_tool"]

    invariants = session_invariants(contract, identity)
    for (section, name), invariant in invariants.items():
        profile[section][name]["session_invariant_sha256"] = invariant

    # §26: the extra-transformer identity record. The Revelation measured
    # lifecycle addition is ModAPITransformer; its origin jar and hash are
    # bound from the runtime the profile pins.
    runtime_root = Path(json.loads((contract / "manifest.json")
                                   .read_text(encoding="utf-8"))["runtime_root"])
    additions = []
    forge_jars = sorted(runtime_root.glob("forge-*.jar")) or [runtime_root / "libraries/net/minecraftforge/forge/1.12.2-14.23.5.2846/forge-1.12.2-14.23.5.2846-universal.jar"]
    forge_jar = next((j for j in forge_jars if j.is_file()), None)
    if forge_jar is not None:
        additions.append({
            "transformer_class": "net.minecraftforge.fml.common.asm.transformers.ModAPITransformer",
            "origin_artifact": forge_jar.name,
            "artifact_sha256": sha(forge_jar),
            "loader_identity": "net.minecraft.launchwrapper.LaunchClassLoader",
            "position": "before-live-writers",
            "measured_from": "REAL_FML_TRANSFORM_CAPTURE launches",
        })
    profile["observed_transformer_additions"] = additions

    # §27: coremod artifact binding. The inventory comes from the launch's
    # recorded coremod plugins; the artifact hash binds the jar basename to
    # content, making the disposable launch directory's path irrelevant.
    qualification = contract / "qualifying-launch" / "qualification.json"
    coremods = []
    if qualification.is_file():
        receipt = json.loads(qualification.read_text(encoding="utf-8"))
        for plugin in receipt.get("registered_coremod_plugins", []):
            location = plugin.get("location", "")
            basename = location.replace("\\", "/").rsplit("/", 1)[-1]
            coremods.append({
                "class": plugin.get("class"),
                "artifact_basename": basename,
                "artifact_sha256": sha(runtime_root / "mods" / basename)
                if (runtime_root / "mods" / basename).is_file() else None,
            })
    profile["coremod_bindings"] = coremods

    (out / "profile.json").write_text(json.dumps(profile, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({
        "canonical_profile": str(out / "profile.json"),
        "session_invariant_rows": len(invariants),
        "transformer_additions": len(additions),
        "coremod_bindings": len(coremods),
    }))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
