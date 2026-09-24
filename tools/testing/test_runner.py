#!/usr/bin/env python3
"""Test stale-build prevention and lane boundaries without external artifacts."""
import copy
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import run_tests as runner


class CacheIdentityTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source = self.file("Source.java", "class Source {}")
        self.second_source = self.file("Other.java", "class Other {}")
        self.jar = self.file("one.jar", "first jar")
        self.second_jar = self.file("two.jar", "second jar")
        self.dll = self.file("native.dll", "library one")
        self.inputs = dict(sources=[self.source, self.second_source], toolchain={"javac": {"version": "javac 1.8.0_504", "sha256": "compiler one"}},
                           classpath=[self.jar, self.second_jar], dll=self.dll, compiler_options=["-source", "8"],
                           launch_options=["-ea"], invocations=[{"main": "Source", "args": []}])
        self.baseline = self.key()

    def file(self, relative, content):
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
        return path

    def key(self, **changed):
        return runner.java_cache_identity(**dict(self.inputs, **changed))[0]

    def test_identical_inputs_have_stable_identity(self):
        self.assertEqual(self.baseline, self.key())

    def test_source_content_invalidates(self):
        self.source.write_text("class Source { int changed; }", encoding="utf-8")
        self.assertNotEqual(self.baseline, self.key())

    def test_source_order_invalidates(self):
        self.assertNotEqual(self.baseline, self.key(sources=list(reversed(self.inputs["sources"]))))

    def test_javac_version_and_binary_identity_each_invalidate(self):
        for field, value in (("version", "javac 1.8.0_505"), ("sha256", "compiler two")):
            with self.subTest(field=field):
                toolchain = copy.deepcopy(self.inputs["toolchain"])
                toolchain["javac"][field] = value
                self.assertNotEqual(self.baseline, self.key(toolchain=toolchain))

    def test_classpath_content_invalidates(self):
        self.jar.write_text("changed jar", encoding="utf-8")
        self.assertNotEqual(self.baseline, self.key())

    def test_classpath_order_invalidates(self):
        self.assertNotEqual(self.baseline, self.key(classpath=[self.second_jar, self.jar]))

    def test_classpath_directory_content_and_names_invalidate(self):
        entry = self.file("classes/A.class", "bytecode")
        first = self.key(classpath=[entry.parent])
        entry.write_text("new bytecode", encoding="utf-8")
        second = self.key(classpath=[entry.parent])
        self.assertNotEqual(first, second)
        entry.rename(entry.parent / "B.class")
        self.assertNotEqual(second, self.key(classpath=[entry.parent]))

    def test_dll_content_invalidates(self):
        self.dll.write_text("library two", encoding="utf-8")
        self.assertNotEqual(self.baseline, self.key())

    def test_compiler_launch_and_main_arguments_each_invalidate(self):
        changes = [{"compiler_options": ["-source", "8", "-g"]}, {"launch_options": ["-ea", "-Xmx128m"]},
                   {"invocations": [{"main": "Source", "args": ["other fixture"]}]}]
        for change in changes:
            with self.subTest(change=change):
                self.assertNotEqual(self.baseline, self.key(**change))

    def test_inherited_java_options_invalidate(self):
        first = self.key(environment={"JAVA_TOOL_OPTIONS": "-Xmx128m"})
        second = self.key(environment={"JAVA_TOOL_OPTIONS": "-Xmx256m"})
        self.assertNotEqual(first, second)
        self.assertNotEqual(self.baseline, self.key(environment={"_JAVA_OPTIONS": "-ea"}))

    def test_dll_change_during_compile_never_publishes_cache(self):
        with patch.object(runner, "OUTPUT_ROOT", self.root / "outputs"), patch.object(runner, "source_snapshot", return_value=[]):
            session = runner.Runner(SimpleNamespace(lane="java-jni"))
            session.java_toolchain = lambda: {"javac": {"path": "javac"}, "java": {"path": "java"}}

            def invoke(name, argv, cwd=None, **kwargs):
                self.dll.write_bytes(b"changed while javac was running")
                return session.record(name, "PASS")

            session.command = invoke
            session.java("fixture", [self.source], [("Source", [])], dll=self.dll)
            self.assertEqual("JAVA_INPUT_CHANGED_DURING_RUN", session.results[-1]["reason"])
            self.assertFalse(list((self.root / "outputs/java-cache").glob("*.json")))

    def test_dll_change_during_jvm_run_invalidates_result(self):
        with patch.object(runner, "OUTPUT_ROOT", self.root / "outputs"), patch.object(runner, "source_snapshot", return_value=[]):
            session = runner.Runner(SimpleNamespace(lane="java-jni"))
            session.java_toolchain = lambda: {"javac": {"path": "javac"}, "java": {"path": "java"}}

            def invoke(name, argv, cwd=None, **kwargs):
                if name.endswith("-javac"):
                    (Path(argv[argv.index("-d") + 1]) / "Source.class").write_bytes(b"compiled")
                else:
                    self.dll.write_bytes(b"changed while JVM was running")
                return session.record(name, "PASS")

            session.command = invoke
            session.java("fixture", [self.source], [("Source", [])], dll=self.dll)
            self.assertEqual("FAIL", session.results[-1]["status"])
            self.assertEqual("JAVA_INPUT_CHANGED_DURING_RUN", session.results[-1]["reason"])

    def test_cache_hit_still_launches_fresh_jvm_and_changed_dll_rebuilds(self):
        with patch.object(runner, "OUTPUT_ROOT", self.root / "outputs"), patch.object(runner, "source_snapshot", return_value=[]):
            session = runner.Runner(SimpleNamespace(lane="java-jni"))
            session.java_toolchain = lambda: {"javac": {"path": "javac"}, "java": {"path": "java"}}
            commands = []

            def invoke(name, argv, cwd=None, **kwargs):
                commands.append((name, argv))
                if name.endswith("-javac"):
                    (Path(argv[argv.index("-d") + 1]) / "Source.class").write_bytes(b"compiled")
                return session.record(name, "PASS")

            session.command = invoke
            for _ in range(2):
                session.java("fixture", [self.source], [("Source", [str(self.dll)])], dll=self.dll)
            self.assertEqual(1, sum(name.endswith("-javac") for name, _ in commands))
            self.assertEqual(2, sum(name == "fixture-1" for name, _ in commands))
            self.assertTrue(session.results[-2]["cache_hit"])
            self.dll.write_bytes(b"different DLL")
            session.java("fixture", [self.source], [("Source", [str(self.dll)])], dll=self.dll)
            self.assertEqual(2, sum(name.endswith("-javac") for name, _ in commands))
            self.assertEqual(3, sum(name == "fixture-1" for name, _ in commands))

    def test_corrupt_cached_classes_are_recompiled(self):
        with patch.object(runner, "OUTPUT_ROOT", self.root / "outputs"), patch.object(runner, "source_snapshot", return_value=[]):
            session = runner.Runner(SimpleNamespace(lane="java-jni"))
            session.java_toolchain = lambda: {"javac": {"path": "javac"}, "java": {"path": "java"}}
            compiled = []

            def invoke(name, argv, cwd=None, **kwargs):
                if name.endswith("-javac"):
                    output = Path(argv[argv.index("-d") + 1]) / "Source.class"
                    output.write_bytes(b"compiled")
                    compiled.append(output)
                return session.record(name, "PASS")

            session.command = invoke
            session.java("fixture", [self.source], [("Source", [])])
            compiled[0].write_bytes(b"corrupt")
            session.java("fixture", [self.source], [("Source", [])])
            self.assertEqual(2, len(compiled))


class LaneTests(unittest.TestCase):
    def test_missing_checker_fixture_is_not_a_successful_skipped_unittest(self):
        with tempfile.TemporaryDirectory() as temporary, patch.object(runner, "OUTPUT_ROOT", Path(temporary) / "output"), patch.object(runner, "ROOT", Path(temporary)), patch.object(runner, "source_snapshot", return_value=[]):
            session = runner.Runner(SimpleNamespace(lane="public"))
            commands = []

            def invoke(name, argv, **kwargs):
                commands.append(name)
                return session.record(name, "PASS")

            session.command = invoke
            session.java = lambda *args, **kwargs: session.record("java-v2", "PASS")
            session.run()
            check = next(result for result in session.results if result["id"] == "evidence-checker-regressions")
            self.assertEqual("NOT_RUN", check["status"])
            self.assertEqual("MISSING_PREREQUISITE", check["reason"])
            self.assertNotIn("evidence-checker-regressions", commands)

    def test_public_contains_direct_commands_and_both_integration_targets(self):
        commands = {tuple(item["argv"]) for item in runner.lane_inventory("public") if "argv" in item}
        for _, command in runner.RUST_PUBLIC:
            self.assertIn(tuple(command), commands)
        self.assertNotIn(("cargo", "test", "--workspace", "--locked"), commands)

    def test_benchmark_does_not_enter_correctness_inventory(self):
        for lane in ("public", "java-jni", "fixture", "forge"):
            self.assertNotIn("ForgeBenchmarks", json.dumps(runner.lane_inventory(lane)))
        self.assertIn("ForgeBenchmarks", json.dumps(runner.lane_inventory("benchmark")))

    def test_legacy_oracle_failure_and_missing_rows_are_not_pass(self):
        good = "TEST 1 [example]: PASS | details\nTEST 2 [second]: PASS\n"
        self.assertTrue(runner.oracle_rows_pass(good, 2))
        self.assertFalse(runner.oracle_rows_pass(good.replace("PASS", "FAIL", 1), 2))
        self.assertFalse(runner.oracle_rows_pass(good, 3))
        self.assertFalse(runner.oracle_rows_pass(good + "TEST 2 [second]: PASS\n", 2))

    def test_missing_artifacts_make_lane_incomplete(self):
        with tempfile.TemporaryDirectory() as temporary, patch.object(runner, "OUTPUT_ROOT", Path(temporary)), patch.object(runner, "source_snapshot", return_value=[]):
            for lane in ("forge", "modpack", "benchmark"):
                with self.subTest(lane=lane):
                    session = runner.Runner(SimpleNamespace(lane=lane, forge_classpath_manifest=None, modpack_artifact_manifest=None))
                    session.run()
                    self.assertEqual("NOT_RUN", session.results[0]["status"])
                    self.assertEqual("MISSING_EXTERNAL_ARTIFACT", session.results[0]["reason"])
                    self.assertEqual(2, session.finish())


if __name__ == "__main__":
    unittest.main(verbosity=2)
