"""Derive the mod (modid, version) inventory from the pinned runtime's jars.

The genuine client's ModList must name the versions the server's
NetworkCheckHandler requires, and those come from each mod's own declared
metadata, resolved the way FML resolves it. The resolution order is proven
from FMLModContainer.bindMetadata and MetadataCollection.getMetadataForId
bytecode, not approximated:

  1. The @Mod version element. Preferred carrier: FML's own build-time parse
     in META-INF/fml_cache_annotation.json (exactly what FML reads);
     otherwise the RuntimeVisibleAnnotations attribute parsed from the
     classfile itself.
  2. version.properties in the mod's jar, key "<modid>.version".
  3. The jar's mcmod.info entry whose modid STRICTLY equals the modid --
     getMetadataForId does not fall back to the first entry. A jar whose
     mcmod.info keys a different id yields no metadata version.
  4. FML's literal substitute "1.0".

Coremod dummy containers that construct ModMetadata in code (openmodscore,
foamfixcore) have no @Mod; their version comes from the ModMetadata field
assignments in the container class's constant pool.

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


def _u2(data: bytes, at: int) -> int:
    return struct.unpack(">H", data[at:at + 2])[0]


def _u4(data: bytes, at: int) -> int:
    return struct.unpack(">I", data[at:at + 4])[0]


def annotation_elements(data: bytes) -> dict | None:
    """@Mod element values from the class's RuntimeVisibleAnnotations.

    Parses the classfile structure (JVMS 4.7.16) rather than sniffing pool
    strings, so the version element is the annotation's own value -- not a
    guess about which pool string it might be.
    """
    if data[:4] != b"\xca\xfe\xba\xbe":
        return None
    count = _u2(data, 8)
    offset = 10
    utf8: dict[int, str] = {}
    for index in range(1, count):
        tag = data[offset]
        if tag == 1:
            length = _u2(data, offset + 1)
            utf8[index] = data[offset + 3:offset + 3 + length].decode("utf-8", "replace")
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
    at = offset + 6  # access_flags, this_class, super_class
    at += 2 + 2 * _u2(data, at)  # interfaces_count + interfaces
    at += 2  # fields_count
    for _ in range(_u2(data, at - 2)):
        at += 6  # access_flags, name_index, descriptor_index
        at += _attribute_table_size(data, at)
    at += 2  # methods_count
    for _ in range(_u2(data, at - 2)):
        at += 6
        at += _attribute_table_size(data, at)
    at += 2  # class attributes_count
    for _ in range(_u2(data, at - 2)):
        name = utf8.get(_u2(data, at), "")
        length = _u4(data, at + 2)
        body = at + 6
        if name == "RuntimeVisibleAnnotations":
            found = _read_mod_annotation(data, body, utf8)
            if found is not None:
                return found
        at = body + length
    return None


def _attribute_table_size(data: bytes, at: int) -> int:
    """Byte size of an attribute table whose count is at `at`."""
    size = 2
    for _ in range(_u2(data, at)):
        size += 6 + _u4(data, at + size + 2)
    return size


def _read_mod_annotation(data: bytes, at: int, utf8: dict[int, str]) -> dict | None:
    """The @Mod element/value pairs from a RuntimeVisibleAnnotations body."""
    annotations = _u2(data, at)
    at += 2
    for _ in range(annotations):
        if utf8.get(_u2(data, at)) != MOD_ANNOTATION:
            at = _skip_annotation(data, at)
            continue
        at += 2  # past type_index
        pairs = _u2(data, at)
        at += 2
        out: dict = {}
        for _ in range(pairs):
            element = utf8.get(_u2(data, at), "")
            at += 2
            tag = data[at:at + 1]
            at += 1
            if tag == b"s":
                out[element] = utf8.get(_u2(data, at), "")
                at += 2
            elif tag == b"Z":
                out[element] = "true" if _u2(data, at) else "false"
                at += 2
            else:
                return None  # array/annotation/class values: not @Mod's shape
        return out
    return None


def _skip_annotation(data: bytes, at: int) -> int:
    """Offset past one annotation structure (type already at `at`)."""
    at += 2  # type_index
    pairs = _u2(data, at)
    at += 2
    for _ in range(pairs):
        at += 2  # element_name_index
        at = _skip_element_value(data, at)
    return at


def _skip_element_value(data: bytes, at: int) -> int:
    tag = data[at:at + 1]
    at += 1
    if tag in (b"e", b"c"):
        return at + 4
    if tag == b"[":
        values = _u2(data, at)
        at += 2
        for _ in range(values):
            at = _skip_element_value(data, at)
        return at
    if tag == b"@":
        return _skip_annotation(data, at)
    if tag in (b"J", b"D"):
        return at + 8  # long/double constants carry 8 payload bytes
    return at + 2


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
    """modid -> @Mod version element (None when the element is absent)."""
    out = {}
    try:
        with zipfile.ZipFile(path) as archive:
            for name in archive.namelist():
                if not name.endswith(".class"):
                    continue
                data = archive.read(name)
                if MOD_ANNOTATION.encode() not in data:
                    continue
                try:
                    elements = annotation_elements(data)
                except (struct.error, IndexError):
                    # A walk that runs off the buffer means this classfile
                    # uses something the parser mis-measured; the pool
                    # heuristic below is the fallback, not a silent guess.
                    elements = None
                if elements is None:
                    # Attribute parse failed; fall back to the pool heuristic.
                    found = annotation_mod(pool_strings(data), name)
                    if found:
                        out.setdefault(found[0], found[1])
                    continue
                modid = elements.get("modid")
                if modid:
                    out.setdefault(modid, elements.get("version"))
    except (OSError, zipfile.BadZipFile):
        pass
    return out


def version_properties(path: Path) -> dict:
    """modid.version -> version from a jar's version.properties, if present.

    FML's second stop when the @Mod version element is missing
    (FMLModContainer.bindMetadata -> searchForVersionProperties).
    """
    out = {}
    try:
        with zipfile.ZipFile(path) as archive:
            if "version.properties" not in archive.namelist():
                return out
            for line in archive.read("version.properties").decode(
                    "utf-8", "replace").splitlines():
                if "=" in line and not line.startswith("#"):
                    key, value = line.split("=", 1)
                    out[key.strip()] = value.strip()
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
        props = version_properties(jar)
        for modid in set(cached) | set(scanned) | set(containers):
            entry = cached.get(modid)
            # FML's exact resolution order, proven from FMLModContainer
            # .bindMetadata and MetadataCollection.getMetadataForId bytecode:
            # the @Mod version element, then version.properties, then the
            # STRICT modid-matched mcmod.info entry, then the literal "1.0".
            element = ((entry or {}).get("version")) if entry is not None else scanned.get(modid, "")
            if element:
                record(modid, element, "fml-annotation-cache" if entry is not None
                       else "mod-annotation-element")
                continue
            if props.get(modid + ".version"):
                record(modid, props[modid + ".version"], "version.properties")
                continue
            if info.get(modid):
                record(modid, info[modid], "mcmod.info:modid-match")
                continue
            if modid in containers:
                record(modid, containers[modid], "modmetadata-container")
                continue
            # getMetadataForId is a strict modid match: a jar whose mcmod.info
            # keys a different id yields autogenerated metadata with an empty
            # version, and FML substitutes its own literal "1.0".
            record(modid, "1.0", "fml-substitute-1.0")
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
