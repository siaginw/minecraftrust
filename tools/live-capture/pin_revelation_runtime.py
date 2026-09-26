#!/usr/bin/env python3
"""Pins the restored FTB Revelation 3.4.0 server runtime into a machine-readable
manifest (tools/live-capture/revelation-runtime-pins.json).

Records path/size/sha256 for the vanilla base jar, the Forge universal jar, all
launch libraries, and every mod jar; parses each mod jar for transformation-
relevant markers (FMLCorePlugin, FMLCorePluginContainsFMLMod, MixinConfigs,
TweakClass, access transformers, signed-jar markers). Clean Forge pins are NOT
touched; the vanilla base jar is additionally cross-checked against the
accepted Clean Forge vanilla pin.
"""
from __future__ import annotations

import hashlib
import json
import zipfile
from pathlib import Path

RT = Path("D:/rustcraft-runtime-targets/revelation-3.4.0/server")
OUT = Path("tools/live-capture/revelation-runtime-pins.json")
CLEAN_FORGE_VANILLA_PIN = "fe1f9274e6dad9191bf6e6e8e36ee6ebc737f373603df0946aafcded0d53167e"


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def jar_markers(path: Path) -> dict:
    """Transformation-relevant markers inside one jar."""
    markers = {"coremod": None, "contains_fml_mod": None, "mixin_configs": None,
               "tweaker": None, "access_transformers": [], "signed": False}
    try:
        with zipfile.ZipFile(path) as archive:
            names = archive.namelist()
            try:
                text = archive.read("META-INF/MANIFEST.MF").decode("utf-8", "replace")
                for line in text.splitlines():
                    for key in ("FMLCorePlugin", "FMLCorePluginContainsFMLMod",
                                "MixinConfigs", "TweakClass"):
                        if line.startswith(key + ":") and markers[camel(key)] is None:
                            markers[camel(key)] = line.split(":", 1)[1].strip()
            except KeyError:
                pass
            markers["access_transformers"] = [
                n for n in names if n.startswith("META-INF/") and n.endswith("_at.cfg")]
            markers["signed"] = any(
                n.startswith("META-INF/") and n.endswith((".SF", ".RSA", ".DSA"))
                for n in names)
    except (zipfile.BadZipFile, OSError) as error:
        markers["error"] = str(error)
    return markers


def camel(key: str) -> str:
    return {"FMLCorePlugin": "coremod",
            "FMLCorePluginContainsFMLMod": "contains_fml_mod",
            "MixinConfigs": "mixin_configs",
            "TweakClass": "tweaker"}[key]


def main() -> int:
    manifest = {
        "schema_version": 1,
        "profile": "FORGE_2846_FTB_REVELATION_3_4_0_SERVER_OFFLINE_V1",
        "server_root": str(RT),
        "artifacts": {},
        "libraries": {},
        "mods": {},
    }
    core = {
        "minecraft_server.1.12.2.jar": {
            "role": "vanilla_base", "transformation_input": True,
            "expected_sha256_clean_forge_pin": CLEAN_FORGE_VANILLA_PIN},
        "forge-1.12.2-14.23.5.2846-universal.jar": {
            "role": "forge_universal", "transformation_input": True},
    }
    for name, info in core.items():
        path = RT / name
        info.update({"path": str(path), "bytes": path.stat().st_size,
                     "sha256": sha256_file(path)})
        manifest["artifacts"][name] = info
    manifest["artifacts"]["minecraft_server.1.12.2.jar"]["matches_clean_forge_vanilla_pin"] = \
        manifest["artifacts"]["minecraft_server.1.12.2.jar"]["sha256"] == CLEAN_FORGE_VANILLA_PIN

    for path in sorted((RT / "libraries").rglob("*.jar")):
        key = path.relative_to(RT).as_posix()
        manifest["libraries"][key] = {"bytes": path.stat().st_size,
                                      "sha256": sha256_file(path)}

    for path in sorted((RT / "mods").rglob("*.jar")):
        key = path.relative_to(RT).as_posix()
        entry = {"bytes": path.stat().st_size, "sha256": sha256_file(path)}
        entry.update(jar_markers(path))
        manifest["mods"][key] = entry

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(manifest, indent=1) + "\n")

    mods = manifest["mods"]
    print("libraries:", len(manifest["libraries"]), "| mods:", len(mods))
    print("coremod-declaring jars:", sum(1 for v in mods.values() if v["coremod"]))
    print("contains-fml-mod jars:", sum(1 for v in mods.values() if v["contains_fml_mod"]))
    print("mixin-config jars:", sum(1 for v in mods.values() if v["mixin_configs"]))
    print("tweaker jars:", sum(1 for v in mods.values() if v["tweaker"]))
    print("AT jars:", sum(1 for v in mods.values() if v["access_transformers"]))
    print("vanilla matches clean-forge pin:",
          manifest["artifacts"]["minecraft_server.1.12.2.jar"]["matches_clean_forge_vanilla_pin"])
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
