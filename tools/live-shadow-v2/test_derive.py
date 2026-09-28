"""Controls for derive_mod_versions.py.

The annotation-attribute parser and the FML resolution order are the two
load-bearing pieces; both are pinned here against synthetic classfiles and
fake jars so a change in either is caught before any server boot.
"""
from __future__ import annotations

import json
import struct
import sys
import unittest
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from derive_mod_versions import (  # noqa: E402
    annotation_elements, fml_annotation_cache, mcmod_info,
    metadata_containers, version_properties,
)

MOD = "Lnet/minecraftforge/fml/common/Mod;"


class ClassfileBuilder:
    """Minimal classfiles with one class attribute: the annotations body."""

    def __init__(self):
        self._strings: list[str] = []
        self._index: dict[str, int] = {}

    def utf8(self, text: str) -> int:
        if text not in self._index:
            self._index[text] = len(self._strings) + 1
            self._strings.append(text)
        return self._index[text]

    def annotation(self, type_desc: str, pairs) -> bytes:
        """One annotation: pairs of (element, kind, value), kind s/Z."""
        body = struct.pack(">H", self.utf8(type_desc)) + struct.pack(">H", len(pairs))
        for element, kind, value in pairs:
            body += struct.pack(">H", self.utf8(element))
            if kind == "s":
                body += b"s" + struct.pack(">H", self.utf8(value))
            elif kind == "Z":
                body += b"Z" + struct.pack(">H", value)
            elif kind == "J":
                body += b"J" + struct.pack(">q", value)
            else:
                raise ValueError(kind)
        return body

    def build(self, annotations: list[bytes], fields: int = 0) -> bytes:
        # Register every referenced string BEFORE serializing the pool.
        attr_name = self.utf8("RuntimeVisibleAnnotations")
        super_name = self.utf8("java/lang/Object")
        count = struct.pack(">H", len(annotations))
        attr_body = count + b"".join(annotations)
        pool = bytearray()
        for text in self._strings:
            raw = text.encode()
            pool += bytes([1]) + struct.pack(">H", len(raw)) + raw
        out = bytearray()
        out += bytes([0xCA, 0xFE, 0xBA, 0xBE]) + struct.pack(">HH", 0, 52)
        out += struct.pack(">H", len(self._strings) + 1)
        out += pool
        out += struct.pack(">HHH", 0x21, attr_name, super_name)
        out += struct.pack(">H", 0)  # interfaces
        out += struct.pack(">H", fields)  # fields (none carry attributes)
        out += struct.pack(">H", 0)  # methods
        out += struct.pack(">H", 1)
        out += struct.pack(">H", attr_name) + struct.pack(">I", len(attr_body)) + attr_body
        return bytes(out)


class AnnotationParseTests(unittest.TestCase):
    def test_reads_modid_and_version_elements(self):
        b = ClassfileBuilder()
        data = b.build([b.annotation(MOD, [("modid", "s", "wand"),
                                           ("version", "s", "0.11.1")])])
        self.assertEqual(annotation_elements(data),
                         {"modid": "wand", "version": "0.11.1"})

    def test_boolean_use_metadata_reported_as_string(self):
        b = ClassfileBuilder()
        data = b.build([b.annotation(MOD, [("modid", "s", "wand"),
                                           ("useMetadata", "Z", 1)])])
        self.assertEqual(annotation_elements(data)["useMetadata"], "true")

    def test_absent_version_element_yields_no_key(self):
        b = ClassfileBuilder()
        data = b.build([b.annotation(MOD, [("modid", "s", "wand")])])
        self.assertEqual(annotation_elements(data), {"modid": "wand"})

    def test_skips_a_leading_non_mod_annotation(self):
        # A foreign annotation with a long element (the 8-byte case that once
        # misaligned the walk) BEFORE the @Mod annotation: the parser must
        # skip it exactly and still find @Mod.
        b = ClassfileBuilder()
        other = b.annotation("Lcom/ExampleOther;", [("longval", "J", 7)])
        mod = b.annotation(MOD, [("modid", "s", "wand")])
        data = b.build([other, mod])
        self.assertEqual(annotation_elements(data), {"modid": "wand"})

    def test_not_a_classfile_is_rejected(self):
        self.assertIsNone(annotation_elements(b"\x00\x01\x02\x03" + b"x" * 32))


class JarSourceTests(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(__file__).resolve().parent / "derive-test-jar"
        self.tmp.mkdir(exist_ok=True)

    def tearDown(self):
        for jar in self.tmp.glob("*.jar"):
            jar.unlink()
        self.tmp.rmdir()

    def _jar(self, name: str, entries: dict) -> Path:
        path = self.tmp / name
        with zipfile.ZipFile(path, "w") as archive:
            for entry, data in entries.items():
                archive.writestr(entry, data)
        return path

    def test_mcmod_info_survives_control_characters(self):
        jar = self._jar("raw.jar", {"mcmod.info":
                                    '[{"modid":"chisel","version":"MC1.12.2-0.2.1.35",'
                                    '"description":"line one\n\nline two"}]'})
        self.assertEqual(mcmod_info(jar), {"chisel": "MC1.12.2-0.2.1.35"})

    def test_version_properties_keyed_by_dot_version(self):
        jar = self._jar("props.jar", {"version.properties":
                                      "# comment\nother.version=9.9\nwand.version=0.11.1\n"})
        self.assertEqual(version_properties(jar),
                         {"other.version": "9.9", "wand.version": "0.11.1"})

    def test_annotation_cache_is_jar_scoped(self):
        # ProjectRed ships one shared cache in the base jar naming every
        # @Mod of the family; only the classes actually IN the jar may be
        # honored from it. The foreign entries are inert to FML and must be
        # inert here, or the sub-mods resolve against the wrong jar.
        cache = {
            "mrtjp/projectred/ProjectRedCore": {"annotations": [
                {"name": "Lnet/minecraftforge/fml/common/Mod;",
                 "values": {"modid": {"value": "projectred-core"},
                            "version": {"value": None}}}]},
            "mrtjp/projectred/ProjectRedExpansion": {"annotations": [
                {"name": "Lnet/minecraftforge/fml/common/Mod;",
                 "values": {"modid": {"value": "projectred-expansion"},
                            "version": {"value": None}}}]},
        }
        jar = self._jar("base.jar", {
            "mrtjp/projectred/ProjectRedCore.class": b"\x00" * 8,
            "META-INF/fml_cache_annotation.json": json.dumps(cache),
        })
        found = fml_annotation_cache(jar)
        self.assertIn("projectred-core", found)
        self.assertNotIn("projectred-expansion", found)

    def test_container_metadata_version_precedes_the_field_name(self):
        # javac order: LDC value, then PUTFIELD name. The container pattern
        # takes the version-shaped string adjacent to "version" -- here the
        # "7.7.4" BEFORE it, matching FoamFixCoreContainer's real pool.
        pool = ["ModMetadata.java", "<init>", "foamfixcore", "modId",
                "FoamFixCore", "name", "7.7.4", "version", "description"]
        body = bytes([0xCA, 0xFE, 0xBA, 0xBE]) + struct.pack(">HH", 0, 52)
        body += struct.pack(">H", len(pool) + 1)
        for text in pool:
            raw = text.encode()
            body += bytes([1]) + struct.pack(">H", len(raw)) + raw
        body += struct.pack(">HHH", 0, 0, 0)
        body += struct.pack(">H", 0) + struct.pack(">H", 0) + struct.pack(">H", 0)
        body += struct.pack(">H", 0)  # no class attributes
        jar = self._jar("container.jar", {"pl/Container.class": body})
        self.assertEqual(metadata_containers(jar), {"foamfixcore": "7.7.4"})


if __name__ == "__main__":
    unittest.main()
