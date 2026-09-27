"""Artifact-independent regressions for Forge qualification admission and cache integrity."""
import copy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

import forge_runtime as runtime


class ForgeRuntimeTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def artifacts(self):
        jar = self.root / "forge.jar"
        with zipfile.ZipFile(jar, "w") as out:
            out.writestr("mapping.lzma", b"exact mapping fixture")
        # A pins file names whatever runtime it was written against; the target is
        # data, not a constant this module owns.
        return {"schema_version": 1, "target": "synthetic-runtime",
                "artifacts": [{"path": "forge.jar", "sha256": runtime.sha256(jar)}],
                "forge_embedded_sha256": {"mapping.lzma": hashlib.sha256(b"exact mapping fixture").hexdigest()}}

    def qualified(self):
        pins = {"registry_identity_sha256": "r", "qualified_transformers": ["forge-transform"],
                "loaded_mods": {"forge": "2860"}, "ordered_loaded_mods": [{"id": "forge", "version": "2860"}],
                "coremod_plugin_classes": ["forge-plugin"], "required_transformed_sha256": {"Chunk": "c"},
                "qualification_profile": "FORGE_SYNTHETIC_TRANSFORMED_V1", "java_runtime_version": "1.8.0_504-b01",
                "observer": "PASSIVE_JVM_CLASS_DEFINITION_OBSERVER_NO_RETRANSFORMATION",
                "listener_counts": {"attach_capabilities_listener_count": 0, "chunk_load_listener_count": 0},
                "mod_lifecycle_executed": True, "farmland_water_ticket_map_empty": True,
                "chunk_unload_listeners": ["net.minecraftforge.fml.common.eventhandler.EventPriority:NORMAL",
                    "net.minecraftforge.fml.common.eventhandler.ASMEventHandler:ASM: net.minecraftforge.common.ForgeInternalHandler@IDENTITY onChunkUnload(Lnet/minecraftforge/event/world/ChunkEvent$Unload;)V"]}
        data = {"profile": pins["qualification_profile"], "production_authority": False,
                "java_runtime_version": pins["java_runtime_version"],
                "attach_capabilities_listener_count": 0, "chunk_load_listener_count": 0,
                "mod_lifecycle_executed": True, "farmland_water_ticket_map_empty": True,
                "chunk_unload_listeners": list(pins["chunk_unload_listeners"]),
                "registry_identity_sha256": "r", "transformers": ["forge-transform"], "loaded_mods": {"forge": "2860"},
                "ordered_loaded_mods": [{"id": "forge", "version": "2860"}],
                "registered_coremod_plugins": [{"class": "forge-plugin"}],
                "observer": pins["observer"],
                "transformed_classes": {"Chunk": "c"}}
        return data, pins

    def test_exact_artifact_and_embedded_mapping_accepted(self):
        self.assertEqual(len(runtime.validate_artifacts(self.root, self.artifacts())), 1)

    def test_wrong_artifact_is_incomplete_not_pass(self):
        pins = self.artifacts()
        (self.root / "forge.jar").write_bytes(b"wrong artifact")
        with self.assertRaises(runtime.Incomplete) as error:
            runtime.validate_artifacts(self.root, pins)
        self.assertEqual(error.exception.reason, "ARTIFACT_MISMATCH")

    def test_missing_artifact_is_incomplete(self):
        pins = self.artifacts()
        pins["artifacts"][0]["path"] = "missing.jar"
        with self.assertRaises(runtime.Incomplete) as error:
            runtime.validate_artifacts(self.root, pins)
        self.assertEqual(error.exception.reason, "MISSING_ARTIFACT")

    def test_mapping_identity_cannot_be_substituted(self):
        pins = self.artifacts()
        pins["forge_embedded_sha256"]["mapping.lzma"] = "0" * 64
        with self.assertRaises(runtime.Incomplete):
            runtime.validate_artifacts(self.root, pins)

    def test_artifact_path_cannot_escape_root(self):
        pins = self.artifacts()
        pins["artifacts"][0]["path"] = "../outside.jar"
        with self.assertRaises(runtime.Incomplete) as error:
            runtime.validate_artifacts(self.root, pins)
        self.assertEqual(error.exception.reason, "ARTIFACT_MISMATCH")

    def test_pins_must_still_name_the_runtime_they_were_written_for(self):
        # The target stopped being a hardcoded constant, not a requirement: a
        # pins file that names no runtime cannot be tied to one, so it refuses.
        for target in (None, "", 7, ["a"]):
            pins = self.artifacts()
            if target is None:
                del pins["target"]
            else:
                pins["target"] = target
            with self.subTest(target=target), self.assertRaises(runtime.Incomplete) as error:
                runtime.validate_artifacts(self.root, pins)
            self.assertEqual(error.exception.reason, "ARTIFACT_MISMATCH")
        for schema in (0, 2, "1"):
            pins = self.artifacts()
            pins["schema_version"] = schema
            with self.subTest(schema=schema), self.assertRaises(runtime.Incomplete):
                runtime.validate_artifacts(self.root, pins)

    def test_full_qualified_profile_accepted(self):
        runtime.validate_qualification(*self.qualified())

    def test_registry_only_or_missing_observer_cannot_be_promoted(self):
        for key, value in (("profile", "FORGE_2860_SERVER_TRANSFORMED_REGISTRY_ONLY_V1"),
                           ("mod_lifecycle_executed", False), ("observer", "terminal transformer")):
            data, pins = self.qualified()
            data[key] = value
            with self.subTest(key=key), self.assertRaises(runtime.Incomplete):
                runtime.validate_qualification(data, pins)

    def test_unqualified_writer_callback_and_registry_changes_reject(self):
        mutations = {"attach_capabilities_listener_count": 1, "chunk_load_listener_count": 1,
                     "farmland_water_ticket_map_empty": False, "chunk_unload_listeners": [],
                     "registry_identity_sha256": "changed", "transformed_classes": {"Chunk": "changed"},
                     "transformers": ["extra-transformer"], "ordered_loaded_mods": [],
                     "registered_coremod_plugins": [{"class": "extra-plugin"}]}
        for key, value in mutations.items():
            data, pins = self.qualified()
            data[key] = value
            with self.subTest(key=key), self.assertRaises(runtime.Incomplete):
                runtime.validate_qualification(data, pins)

    def test_modified_or_extra_cached_class_invalidates_cache(self):
        cache = self.root / "cache"
        (cache / "classes").mkdir(parents=True)
        target = cache / "classes/A.class"
        target.write_bytes(b"class bytes")
        runtime.json_write(cache / "cache.json", {"key": "k", "classes": runtime.class_hashes(cache / "classes")})
        self.assertTrue(runtime.cache_valid(cache, "k"))
        target.write_bytes(b"changed")
        self.assertFalse(runtime.cache_valid(cache, "k"))
        target.write_bytes(b"class bytes")
        (cache / "classes/Extra.class").write_bytes(b"extra")
        self.assertFalse(runtime.cache_valid(cache, "k"))

    def test_observer_jar_is_reproducible_and_declares_no_retransform(self):
        classes = self.root / "classes"
        target = classes / "com/rustcraft/offline/agent/ObservationAgent.class"
        target.parent.mkdir(parents=True)
        target.write_bytes(b"synthetic test class")
        one = runtime.make_observer_jar(classes, self.root / "one.jar")
        two = runtime.make_observer_jar(classes, self.root / "two.jar")
        self.assertEqual(one["sha256"], two["sha256"])
        with zipfile.ZipFile(one["path"]) as jar:
            self.assertIn(b"Can-Retransform-Classes: false", jar.read("META-INF/MANIFEST.MF"))


if __name__ == "__main__":
    unittest.main()
