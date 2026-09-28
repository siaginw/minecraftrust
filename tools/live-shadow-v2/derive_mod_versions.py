"""Derive the mod (modid, version) inventory from the pinned runtime's jars.

The genuine client's ModList must name the versions the server's
NetworkCheckHandler requires, and those come from each mod's own declared
metadata, resolved the way FML resolves it. Sources, in FML's own order of
authority:

  1. META-INF/fml_cache_annotation.json -- FML's own build-time parse of the
     @Mod annotations, present in most properly-built jars. This is exactly
     what FML reads, not our interpretation of it.
  2. The @Mod annotation's constant pool in class files, when no cache entry
     covers the mod.
  3. ModMetadata-construction pattern in coremod dummy container classes
     (modid, "modId", name, "name", ..., "version", V) -- the openmodscore /
     foamfixcore family, which has no @Mod at all.
  4. The owning jar's mcmod.info, matching modid when possible, else the
     first entry -- FML's own fallback for @Mod elements FML substitutes a
     placeholder for.

A literal "1.0" is FML's own substitute when nothing resolves (careerbees'
@Mod version element is literally "1.0" in FML's cache); a "1.0" that comes
from OUR failure to resolve is honestly reported as source "unresolved" so a
consumer can refuse to trust it.

    python -B tools/live-shadow-v2/derive_mod_versions.py \
        --mods <runtime>/mods [--forge-jar <forge>] [--out versions.json]
"""
from __future__ import annotations

import argparse
import json
import re
import struct
import zipfile
from pathlib import Path

RESERVED = {
    "modid", "version", "name", "acceptableRemoteVersions", "acceptableSaveVersions",
    "certificateFingerprint", "clientSideOnly", "dependencies", "guiFactory",
    "acceptedMinecraftVersions", "useMetadata", "canBeDeactivated", "modLanguage",
    "modLanguageAdapter", "instanceFactory", "value", "Lnet/minecraftforge/fml/common/Mod;",
}

_ID = re.compile(r"[a-z0-9_]{3,40}\Z")
# A version, or an unsubstituted gradle build token. Tokens are real: the
# server's NetworkCheckHandler compared against a literal "@VERSION@" for
# Storage Drawers Extras, because that is what the shipped @Mod carries.
_VER = re.compile(r"(?:[0-9][A-Za-z0-9.\-_+@]{0,40}|@[A-Z_]+@?)\Z")

MOD_ANNOTATION = "Lnet/minecraftforge/fml/common/Mod;"


def pool_strings(data: bytes):
    """Every UTF8 constant in a classfile's constant pool, in pool order."""
    if data[:4] != b"\xca\xfe\xba\xbe":
        return None
    count = struct.unpack(">H", data[8:10])[0]
    offset = 10
    out = []
    for _ in range(1, count):
        tag = data[offset]
        if tag == 1:
            length = struct.unpack(">H", data[offset + 1:offset + 3])[0]
            out.append(data[offset + 3:offset + 3 + length].decode("utf-8", "replace"))
            offset += 3 + length
        elif tag in (7, 8, 16, 19, 20):
            offset += 3
        elif tag == 15:
            offset += 4
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
            offset += 5
        elif tag in (5, 6):
            offset += 9
        else:
            return None
    return out


def fml_annotation_cache(path: Path) -> dict:
    """(modid -> {"version", "name"}) from FML's own annotation cache, if any."""
    out = {}
    try:
        with zipfile.ZipFile(path) as archive:
            if "META-INF/fml_cache_annotation.json" not in archive.namelist():
                return out
            cache = json.loads(archive.read("META-INF/fml_cache_annotation.json")
                               .decode("utf-8", "replace"))
    except (OSError, ValueError, zipfile.BadZipFile):
        return out
    for info in cache.values() if isinstance(cache, dict) else []:
        for annotation in info.get("annotations", []):
            if annotation.get("name") != MOD_ANNOTATION:
                continue
            values = annotation.get("values", {})
            modid = values.get("modid", {}).get("value")
            if not modid:
                continue
            entry = {"version": values.get("version", {}).get("value"),
                     "name": values.get("name", {}).get("value")}
            previous = out.get(modid.lower())
            # Prefer an entry that actually declares a version.
            if previous is None or (previous["version"] is None and entry["version"]):
                out[modid.lower()] = entry
    return out


def annotation_mod(strs, class_internal: str):
    """(modid, version) for a class whose pool carries @Mod, or None."""
    if strs is None or "modid" not in strs:
        return None
    # The modid is the pool string that matches the class's own name shape and
    # is not an annotation element name. Anchoring on the class name is what
    # makes this reliable across most jars; the cache covers the rest.
    anchor = class_internal.rsplit("/", 1)[-1][:-6].lower()
    candidates = [s for s in strs
                  if _ID.match(s) and s not in RESERVED
                  and (s.lower() in anchor or anchor in s.lower())]
    if not candidates:
        return None
    modid = candidates[0]
    versions = [s for s in strs if _VER.match(s) and s not in RESERVED]
    return modid, (versions[0] if versions else None)


def scan_jar(path: Path) -> dict:
    out = {}
    try:
        with zipfile.ZipFile(path) as archive:
            for name in archive.namelist():
                if not name.endswith(".class"):
                    continue
                data = archive.read(name)
                if MOD_ANNOTATION.encode() not in data:
                    continue
                found = annotation_mod(pool_strings(data), name)
                if found:
                    out.setdefault(found[0], found[1])
    except (OSError, zipfile.BadZipFile):
        pass
    return out


def metadata_containers(path: Path) -> dict:
    """modid -> version for coremod dummy containers that build ModMetadata.

    Those classes assign the fields by name: the pool carries the modid
    string adjacent to the literal field name "modId", and the version
    adjacent to the literal field name "version". openmodscore
    (OpenModsCore) and foamfixcore (FoamFixCoreContainer) come from here;
    neither jar has any @Mod for the id.
    """
    out = {}
    try:
        with zipfile.ZipFile(path) as archive:
            for name in archive.namelist():
                if not name.endswith(".class"):
                    continue
                strs = pool_strings(archive.read(name))
                if not strs or "modId" not in strs:
                    continue
                for index, value in enumerate(strs):
                    if value != "modId" or index == 0:
                        continue
                    modid = strs[index - 1]
                    if not _ID.match(modid):
                        continue
                    if "version" in strs:
                        at = strs.index("version")
                        # javac order is LDC value then PUTFIELD name, so the
                        # version constant normally PRECEDES "version"; check
                        # both sides to be safe.
                        near = strs[max(0, at - 3):at] + strs[at + 1:at + 4]
                        versions = [s for s in near if _VER.match(s)]
                        if versions:
                            out.setdefault(modid, versions[0])
    except (OSError, zipfile.BadZipFile):
        pass
    return out


def mcmod_info(path: Path) -> dict:
    """Ordered {modid: version} plus the jar's first entry, from mcmod.info."""
    out = {}
    try:
        with zipfile.ZipFile(path) as archive:
            if "mcmod.info" not in archive.namelist():
                return out
            text = archive.read("mcmod.info").decode("utf-8", "replace")
            # Several shipped mcmod.info files contain raw control characters
            # (unescaped newlines) inside JSON strings; Gson tolerates them
            # but json.loads does not. Token whitespace is irrelevant, so
            # strip every control character rather than losing the file.
            text = "".join(ch for ch in text if ch >= " ")
            document = json.loads(text)
            entries = document if isinstance(document, list) else document.get("modList", [])
            for entry in entries:
                modid, version = entry.get("modid"), entry.get("version")
                if modid and version:
                    out.setdefault(modid, version)
    except (OSError, ValueError, zipfile.BadZipFile):
        pass
    return out


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mods", type=Path, required=True)
    parser.add_argument("--forge-jar", type=Path, default=None)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()

    jars = sorted(args.mods.glob("*.jar"))
    if args.forge_jar is not None:
        jars.append(args.forge_jar)

    # Per-jar collection, so every modid can fall back to its OWN jar's
    # mcmod.info (projectred-compat's @Mod declares no version element; FML
    # then reads the metadata of the jar the @Mod class lives in).
    versions: dict = {}
    provenance: dict = {}

    def record(modid, version, source):
        modid = modid.lower()
        if version and modid not in versions:
            versions[modid] = version
            provenance[modid] = source

    for jar in jars:
        cached = fml_annotation_cache(jar)
        scanned = scan_jar(jar)
        containers = metadata_containers(jar)
        info = mcmod_info(jar)
        first_info = next(iter(info.items()), None)
        for modid in set(cached) | set(scanned) | set(containers):
            entry = cached.get(modid)
            if entry is not None and entry["version"]:
                record(modid, entry["version"], "fml-annotation-cache")
                continue
            if entry is not None and entry["version"] is None and modid in info:
                # @Mod with no version element: FML falls back to the jar's
                # own metadata (useMetadata / metadataFromJar).
                record(modid, info[modid], "mcmod.info:modid-match")
                continue
            if modid in scanned and scanned[modid]:
                record(modid, scanned[modid], "mod-annotation-pool")
                continue
            if modid in containers:
                record(modid, containers[modid], "modmetadata-container")
                continue
            if entry is not None and first_info is not None:
                # @Mod with no version element and no matching mcmod.info id:
                # FML's ModMetadata for the container comes from the jar's
                # metadata file, first entry.
                record(modid, first_info[1], "mcmod.info:first-entry")
                continue
            if modid in info:
                # The annotation scan anchored the modid but found no version
                # element; the jar's own metadata is FML's next stop.
                record(modid, info[modid], "mcmod.info:modid-match")
                continue
            if entry is not None and entry["version"] is None:
                record(modid, "1.0", "mod-annotation:fml-literal-1.0")
                continue
            if modid in scanned:
                record(modid, "1.0", "unresolved")
        # Standalone mcmod.info-only mods (no @Mod anywhere): rare, but do
        # not lose them.
        for modid in info:
            if modid not in versions:
                record(modid, info[modid], "mcmod.info:only-source")

    # The four FML built-ins are constants of the runtime, not jar metadata.
    for modid, version, source in (("minecraft", "1.12.2", "fml-builtin"),
                                   ("mcp", "9.42", "fml-builtin"),
                                   ("FML", "8.0.99.99", "fml-builtin")):
        if modid not in versions:
            versions[modid] = version
            provenance[modid] = source
    if args.forge_jar is not None and "forge" not in versions:
        versions["forge"] = args.forge_jar.stem.split("-")[-1]
        provenance["forge"] = "forge-jar-name"

    unresolved = sorted(m for m, s in provenance.items() if s == "unresolved")
    args.out.write_text(json.dumps(
        {"versions": dict(sorted(versions.items())),
         "provenance": dict(sorted(provenance.items())),
         "unresolved": unresolved},
        indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps({"mods_scanned": len(jars), "versions_derived": len(versions),
                      "unresolved": unresolved, "out": str(args.out)}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
