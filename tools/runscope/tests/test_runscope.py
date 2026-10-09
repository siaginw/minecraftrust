"""Unit tests for runscope (bridge lint + run report/compare).

All fixtures are synthetic (temp dirs); no Minecraft assets or real run
directories are required.
"""

import json
import time
import os
import shutil
import sys
import tempfile
import unittest

_HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(_HERE, ".."))

import bridge_lint            # noqa: E402
import run_report             # noqa: E402


class LintFixture(unittest.TestCase):
    """Builds a tiny synthetic repo tree for lint checks."""

    def setUp(self):
        self.root = tempfile.mkdtemp(prefix="runscope-lint-")
        self.java = os.path.join(self.root, "tools", "bridge", "src",
                                 "com", "rustcraft", "bridge")
        self.rust = os.path.join(self.root, "crates", "ffi", "src")
        os.makedirs(self.java)
        os.makedirs(self.rust)

    def tearDown(self):
        shutil.rmtree(self.root, ignore_errors=True)

    def write_java(self, name, body, package="com.rustcraft.bridge"):
        path = os.path.join(self.java, name)
        with open(path, "w", encoding="utf-8") as fh:
            fh.write("package %s;\n%s" % (package, body))
        return path

    def write_rust(self, body):
        path = os.path.join(self.rust, "lib.rs")
        with open(path, "w", encoding="utf-8") as fh:
            fh.write(body)
        return path

    def lint(self):
        return bridge_lint.run_lint(self.root)

    def test_clean_pair(self):
        self.write_java("GoodBridge.java",
                        "public final class GoodBridge {\n"
                        "  private static native long create(int x);\n"
                        "}\n")
        self.write_rust(
            '#[no_mangle]\npub extern "system" fn '
            'Java_com_rustcraft_bridge_GoodBridge_create(\n')
        findings = [f for f in self.lint() if f.severity != "info"]
        self.assertEqual(findings, [])

    def test_missing_export_fatal(self):
        self.write_java("BadCtx.java",
                        "public final class BadCtx {\n"
                        "  private static native int closeRaw(long h);\n"
                        "}\n")
        findings = [f for f in self.lint() if f.check == "jni-pairing"
                    and f.severity == "fatal"]
        self.assertEqual(len(findings), 1)
        self.assertIn("closeRaw", findings[0].message)

    def test_comment_does_not_create_native(self):
        self.write_java("DocBridge.java",
                        "/** native neverDeclared(long x); */\n"
                        "public final class DocBridge {\n"
                        "}\n")
        findings = [f for f in self.lint() if f.check == "jni-pairing"
                    and f.severity == "fatal"]
        self.assertEqual(findings, [])

    def test_underscore_mangling_accepted(self):
        self.write_java("ManCtx.java",
                        "public final class ManCtx {\n"
                        "  private static native int free_raw(long h);\n"
                        "}\n")
        self.write_rust(
            '#[no_mangle]\npub extern "system" fn '
            'Java_com_rustcraft_bridge_ManCtx_free_1raw(\n')
        findings = [f for f in self.lint() if f.check == "jni-pairing"
                    and f.severity == "fatal"]
        self.assertEqual(findings, [])

    def test_bytebuffer_without_order_flagged(self):
        self.write_java("BufHook.java",
                        "import java.nio.ByteBuffer;\n"
                        "public final class BufHook {\n"
                        "  void go() {\n"
                        "    ByteBuffer buf = ByteBuffer.allocateDirect(64);\n"
                        "    try {\n"
                        "      buf.putInt(0, 5);\n"
                        "    } catch (Throwable t) { }\n"
                        "  }\n"
                        "}\n")
        findings = [f for f in self.lint()
                    if f.check == "bytebuffer-endianness"]
        self.assertEqual(len(findings), 1)

    def test_bytebuffer_with_order_clean(self):
        self.write_java("BufOkHook.java",
                        "import java.nio.ByteBuffer;\n"
                        "public final class BufOkHook {\n"
                        "  void go() {\n"
                        "    ByteBuffer buf = ByteBuffer.allocateDirect(64)\n"
                        "        .order(java.nio.ByteOrder.LITTLE_ENDIAN);\n"
                        "    buf.putInt(0, 5);\n"
                        "  }\n"
                        "}\n")
        findings = [f for f in self.lint()
                    if f.check == "bytebuffer-endianness"]
        self.assertEqual(findings, [])

    def test_byte_only_buffer_clean(self):
        self.write_java("BytesHook.java",
                        "import java.nio.ByteBuffer;\n"
                        "public final class BytesHook {\n"
                        "  void go() {\n"
                        "    ByteBuffer buf = ByteBuffer.allocateDirect(64);\n"
                        "    buf.put((byte) 1);\n"
                        "  }\n"
                        "}\n")
        findings = [f for f in self.lint()
                    if f.check == "bytebuffer-endianness"]
        self.assertEqual(findings, [])

    def test_cross_loader_forname(self):
        self.write_java("NameHook.java",
                        "public final class NameHook {\n"
                        "  void go() throws Exception {\n"
                        '    Class c1 = Class.forName("net.minecraft.world.World");\n'
                        '    Class c2 = Class.forName("com.example.app.Foo");\n'
                        "  }\n"
                        "}\n")
        findings = [f for f in self.lint()
                    if f.check == "cross-loader-forname"]
        self.assertEqual(len(findings), 1)
        self.assertIn("net.minecraft.world.World", findings[0].message)

    def test_offline_harness_exempt(self):
        self.write_java("M4ThingHarness.java",
                        "public final class M4ThingHarness {\n"
                        "  void go() throws Exception {\n"
                        '    Class c = Class.forName("net.minecraft.world.World");\n'
                        "  }\n"
                        "}\n")
        findings = [f for f in self.lint()
                    if f.check == "cross-loader-forname"]
        self.assertEqual(findings, [])

    def test_ordinal_dispatch_flagged_sentinel_ok(self):
        self.write_java("OrdHook.java",
                        "public final class OrdHook {\n"
                        "  void go(Object sky) {\n"
                        "    int ordinal = 1;\n"
                        "    if (ordinal < 0) return;\n"      # sentinel: ok
                        "    if (ordinal == 0) throw new AssertionError();\n"
                        "  }\n"
                        "}\n")
        findings = [f for f in self.lint() if f.check == "ordinal-gate"]
        self.assertEqual(len(findings), 1)
        self.assertIn("== 0", findings[0].message)


def _write(path, content):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    if isinstance(content, (dict, list)):
        content = json.dumps(content, indent=1)
    mode = "wb" if isinstance(content, bytes) else "w"
    with open(path, mode, **({} if mode == "wb"
                             else {"encoding": "utf-8", "newline": "\n"})) \
            as fh:
        fh.write(content)


class ReportFixture(unittest.TestCase):
    """Synthetic run directories for report/compare."""

    def setUp(self):
        self.root = tempfile.mkdtemp(prefix="runscope-run-")
        self.run_a = os.path.join(self.root, "run-a")
        self.run_b = os.path.join(self.root, "run-b")

    def tearDown(self):
        shutil.rmtree(self.root, ignore_errors=True)

    def make_run(self, run_dir, verdict, cells, jar_bytes):
        _write(os.path.join(run_dir, "receipt.json"), {
            "verdict": verdict, "exit_reason": "RECEIPT_COMPLETE",
            "test_tier": "dev", "target": "A", "git_sha": "abc1234",
            "wall_time_s": 100.0,
            "evidence_targets": {"cells": 1000},
            "evidence_observed": {"cells": cells, "mismatches": 0},
        })
        _write(os.path.join(run_dir, "light-mutations.json"), {
            "passed": verdict == "PASS",
            "failedSteps": [],
            "final": {"worldLightCells": cells},
            "steps": [{"step": "torch_place", "cellDelta": 40, "ok": True,
                       "settleS": 1.2}],
        })
        _write(os.path.join(run_dir, "server", "rustcraft-campaign.jar"),
               jar_bytes)
        _write(os.path.join(run_dir, "server", "mods", "phosphor.jar"), "p")
        _write(os.path.join(run_dir, "server", "mods", "1.12.2",
                            "phosphor.jar"), "p")
        _write(os.path.join(run_dir, "server.log"), "\n".join([
            "[10:00:00] [main/INFO] [LaunchWrapper]: Loading tweak class "
            "name com.rustcraft.coremod.LiveSessionAdmissionTweaker",
            "[10:01:00] [Server thread/INFO]: Done (12.3s)! For help, "
            "type help",
            "[10:02:00] [Server thread/INFO]: RWPA0[/127.0.0.1:5]"
            " logged in with entity id 5 at (10.5, 100.0, 10.5)",
            "[10:03:00] [Server thread/WARN]: Can't keep up! Did the "
            "system time change, or is the server overloaded? Running "
            "2500ms or 50 ticks behind",
            "[10:04:00] [Server thread/INFO]: java.lang.NullPointerException: "
            "boom",
            "[10:05:00] [rustcraft-light-periodic/INFO] [STDOUT]: "
            "[Tweaker:1]: worldLight.shadow cells=%d mismatches=0 "
            "errors=0 transformer=WORLD_CHECK_LIGHT_HOOKED returns=2" % cells,
            "[10:06:00] [Server thread/INFO]: RWPA0 lost connection: "
            "disconnect",
            "",
        ]))

    def test_report_structure(self):
        self.make_run(self.run_a, "PASS", 500, b"new-jar")
        _write(os.path.join(self.root, "target",
                            "rustcraft-campaign-A.jar"), b"new-jar")
        r = run_report.build_report(self.run_a, self.root)
        self.assertEqual(r["receipt"]["verdict"], "PASS")
        self.assertEqual(r["staged_jars"][0]["status"], "MATCH")
        self.assertEqual(len(r["mods_duplicates"]), 1)
        self.assertEqual(r["log"]["lag"]["count"], 1)
        self.assertEqual(r["log"]["lag"]["max_ms"], 2500)
        self.assertIn("java.lang.NullPointerException",
                      r["log"]["exceptions"])
        kinds = [e[1] for e in r["log"]["events"]]
        self.assertIn("boot-done", kinds)
        self.assertIn("login", kinds)
        self.assertEqual(r["log"]["counters"]["cells"], 500)
        self.assertEqual(r["mutations"]["steps"][0]["delta"], 40)

    def test_stale_jar_detected(self):
        self.make_run(self.run_a, "FAIL", 0, b"old-jar")
        _write(os.path.join(self.root, "target",
                            "rustcraft-campaign-A.jar"), b"new-jar")
        r = run_report.build_report(self.run_a, self.root)
        self.assertEqual(r["staged_jars"][0]["status"], "STALE")

    def test_compare_deltas(self):
        self.make_run(self.run_a, "FAIL", 100, b"j1")
        self.make_run(self.run_b, "PASS", 900, b"j2")
        c = run_report.compare_reports(self.run_a, self.run_b, self.root)
        d = c["counter_deltas"]["cells"]
        self.assertEqual(d["delta"], 800)
        self.assertEqual(c["verdicts"]["a"], "FAIL")
        self.assertEqual(c["verdicts"]["b"], "PASS")

    def test_missing_files_tolerated(self):
        os.makedirs(self.run_a, exist_ok=True)
        r = run_report.build_report(self.run_a, self.root)
        self.assertIsNone(r["receipt"]["verdict"])
        self.assertFalse(r["log"]["log_present"])


class TestPatchTool(unittest.TestCase):
    def setUp(self):
        self.d = tempfile.mkdtemp(prefix="runscope-patch-")
        self.java = os.path.join(self.d, "Hook.java")
        with open(self.java, "w", encoding="utf-8", newline="\n") as fh:
            fh.write("// the old code used DEPTH.set here (prose)\n"
                     "public final class Hook {\n"
                     "  void go() { int x = 1; }\n"
                     "}\n")

    def tearDown(self):
        shutil.rmtree(self.d, ignore_errors=True)

    def test_apply_with_postconditions(self):
        import patch_tool
        ok, _ = patch_tool.run_patch(
            self.java, replacements=["int x = 1;===int x = DEPTH.set(0);"],
            must_contain=[r"DEPTH\s*\.\s*set"],
            must_not_contain=[r"skyBlockOrdinal"])
        self.assertTrue(ok)
        self.assertIn("DEPTH.set(0)", open(self.java, encoding="utf-8").read())

    def test_comment_prose_cannot_satisfy_checks(self):
        import patch_tool
        ok, _ = patch_tool.run_patch(self.java, check_only=True,
                                     must_contain=[r"DEPTH\s*\.\s*set"])
        self.assertFalse(ok)

    def test_nonunique_replacement_aborts_untouched(self):
        import patch_tool
        multi = os.path.join(self.d, "Multi.java")
        with open(multi, "w", encoding="utf-8", newline="\n") as fh:
            fh.write("a b a b\n")
        ok, _ = patch_tool.run_patch(multi, replacements=["a===Z"])
        self.assertFalse(ok)
        self.assertEqual(open(multi, encoding="utf-8").read(), "a b a b\n")

    def test_check_mode_detects_surviving_invariant(self):
        import patch_tool
        ok, _ = patch_tool.run_patch(
            self.java, replacements=["int x = 1;===int x = DEPTH.set(0);"])
        self.assertTrue(ok)
        ok2, _ = patch_tool.run_patch(self.java, check_only=True,
                                      must_contain=[r"DEPTH\s*\.\s*set"])
        self.assertTrue(ok2)

    def test_missing_file(self):
        import patch_tool
        ok, _ = patch_tool.run_patch(os.path.join(self.d, "nope.java"),
                                     check_only=True)
        self.assertFalse(ok)


class TestWaitfor(unittest.TestCase):
    def setUp(self):
        self.d = tempfile.mkdtemp(prefix="runscope-wait-")
        self.log = os.path.join(self.d, "server.log")

    def tearDown(self):
        shutil.rmtree(self.d, ignore_errors=True)

    def _append(self, text):
        with open(self.log, "a", encoding="utf-8", newline="\n") as fh:
            fh.write(text)

    def test_pattern_found_immediately_with_from_start(self):
        import waitfor
        self._append("boot ok\nLIGHT_HOOK_PLACED status=2\n")
        ok, s = waitfor.wait_for(self.log, pattern="LIGHT_HOOK_PLACED",
                                 from_start=True, deadline_s=2)
        self.assertTrue(ok)
        self.assertEqual(s["match_count"], 1)

    def test_pattern_count_threshold(self):
        import waitfor
        self._append("compared=1\ncompared=2\n")
        ok, s = waitfor.wait_for(self.log, pattern="compared=", count=3,
                                 from_start=True, deadline_s=1)
        self.assertFalse(ok)  # only 2 matches, needed 3
        self.assertEqual(s["match_count"], 2)

    def test_settle_on_counter(self):
        import waitfor
        self._append("worldLight.shadow cells=10 mismatches=0\n")
        ok, s = waitfor.wait_for(self.log, settle_key="cells",
                                 stable_for=0.5, from_start=True,
                                 deadline_s=5, interval_s=0.1)
        self.assertTrue(ok)
        self.assertEqual(s["settle_value"], 10)

    def test_tail_new_ignores_existing(self):
        import waitfor
        self._append("LIGHT_HOOK_PLACED already here\n")
        ok, s = waitfor.wait_for(self.log, pattern="LIGHT_HOOK_PLACED",
                                 from_start=False, deadline_s=1)
        self.assertFalse(ok)
        self.assertEqual(s["match_count"], 0)

    def test_missing_file_times_out_with_code_hint(self):
        import waitfor
        ok, s = waitfor.wait_for(os.path.join(self.d, "nope.log"),
                                 pattern="x", deadline_s=1)
        self.assertFalse(ok)
        self.assertFalse(s["file_appeared"])

    def test_live_append_triggers(self):
        import threading
        import waitfor

        def later():
            time.sleep(0.5)
            self._append("Done (12.3s)! For help, type \"help\"\n")
        threading.Thread(target=later, daemon=True).start()
        ok, s = waitfor.wait_for(self.log, pattern=r"Done \(\d",
                                 from_start=False, deadline_s=5,
                                 interval_s=0.1)
        self.assertTrue(ok)


class TestBootDiff(unittest.TestCase):
    def setUp(self):
        self.d = tempfile.mkdtemp(prefix="runscope-boot-")

    def tearDown(self):
        shutil.rmtree(self.d, ignore_errors=True)

    def _log(self, name, lines):
        p = os.path.join(self.d, name)
        with open(p, "w", encoding="utf-8", newline="\n") as fh:
            fh.write("\n".join(lines) + "\n")
        return p

    def test_first_divergence_and_categories(self):
        import boot_diff
        a = self._log("a.log", [
            "[10:00:00] [main/INFO] [LaunchWrapper]: Loading tweak class "
            "name com.rustcraft.coremod.LiveSessionAdmissionTweaker",
            "[10:00:01] [main/INFO] [LaunchWrapper]: Loading tweak class "
            "name net.minecraftforge.fml.common.launcher.FMLInjectionAndSortingTweaker",
            "[10:00:02] [main/INFO] [STDERR]: [java.lang.Throwable$WrappedPrintStream:println:-1]: "
            "net.minecraftforge.fml.relauncher.CoreModManager not signed",
            "[10:00:10] [Server thread/INFO]: Done (12.3s)!",
        ])
        b = self._log("b.log", [
            "[10:00:00] [main/INFO] [LaunchWrapper]: Loading tweak class "
            "name com.rustcraft.coremod.LiveSessionAdmissionTweaker",
            "[10:00:01] [main/INFO] [LaunchWrapper]: Loading tweak class "
            "name org.spongepowered.asm.launch.MixinTweaker",
            "[10:00:02] [main/INFO] [mixin/INFO]: Mixin config "
            "phosphor.mixins.json registered",
            "[10:00:10] [Server thread/INFO]: Done (12.9s)!",
        ])
        d = boot_diff.boot_diff(a, b)
        self.assertEqual(d["category_counts"]["tweaker"]["a"], 2)
        self.assertEqual(d["category_counts"]["tweaker"]["b"], 2)
        self.assertEqual(d["category_counts"]["mixin"]["a"], 0)
        self.assertEqual(d["category_counts"]["mixin"]["b"], 1)
        self.assertFalse(d["tweakers"]["identical"])
        self.assertIsNotNone(d["first_divergence"])
        self.assertIn("MixinTweaker", d["first_divergence"]["b_lines"][0])

    def test_identical_logs_no_divergence(self):
        import boot_diff
        lines = ["[10:00:00] [main/INFO]: Done (12.3s)!"]
        a = self._log("a.log", lines)
        b = self._log("b.log", ["[11:00:00] [main/INFO]: Done (12.3s)!"])
        d = boot_diff.boot_diff(a, b)
        self.assertIsNone(d["first_divergence"])


class TestExpectAndJustification(unittest.TestCase):
    def setUp(self):
        self.d = tempfile.mkdtemp(prefix="runscope-expect-")
        self.java = os.path.join(self.d, "Hook.java")
        with open(self.java, "w", encoding="utf-8", newline="\n") as fh:
            fh.write("public final class Hook {\n"
                     "  void go() { int x = Math.max(0, depth - 1); }\n"
                     "}\n")
        self.exp_file = os.path.join(self.d, "expectations.json")

    def tearDown(self):
        shutil.rmtree(self.d, ignore_errors=True)

    def _write_exps(self, exps):
        import json
        with open(self.exp_file, "w", encoding="utf-8") as fh:
            json.dump(exps, fh)

    def test_expect_pass_and_fail(self):
        import expect
        self._write_exps([
            {"id": "ok-inv", "file": self.java,
             "must_contain": ["Math\\.max\\(0, depth - 1\\)"],
             "must_not_contain": [], "incident": "test"},
            {"id": "bad-inv", "file": self.java,
             "must_contain": ["DEPTH.set"], "must_not_contain": [],
             "incident": "test"},
        ])
        ok_all, results = expect.run_expectations(self.d,
                                                  path=self.exp_file)
        self.assertFalse(ok_all)
        self.assertTrue(results[0]["ok"])
        self.assertFalse(results[1]["ok"])
        self.assertIn("incident:  test",
                      expect.render(results, ok_all))

    def test_prose_does_not_satisfy_expectation(self):
        import expect
        prose = os.path.join(self.d, "Doc.java")
        with open(prose, "w", encoding="utf-8", newline="\n") as fh:
            fh.write("// Math.max(0, depth - 1) mentioned in a comment\n"
                     "public class Doc {}\n")
        self._write_exps([
            {"id": "prose", "file": prose,
             "must_contain": ["Math\\.max\\(0, depth - 1\\)"],
             "must_not_contain": []},
        ])
        ok_all, results = expect.run_expectations(self.d,
                                                  path=self.exp_file)
        self.assertFalse(ok_all)

    def test_lint_justification_suppresses(self):
        import bridge_lint
        justified = os.path.join(self.d, "NameHook.java")
        with open(justified, "w", encoding="utf-8", newline="\n") as fh:
            fh.write(
                "public final class NameHook {\n"
                "  void go() throws Exception {\n"
                "    // RUNSCOPE-JUSTIFIED: resolves in Launch space\n"
                '    Class c1 = Class.forName("net.minecraft.world.World");\n'
                "    int pad = 0;\n"
                "    pad++;\n    pad++;\n    pad++;\n    pad++;\n    pad++;\n"
                "    pad++;\n    pad++;\n    pad++;\n    pad++;\n    pad++;\n"
                '    Class c2 = Class.forName("net.minecraft.world.World");\n'
                "  }\n"
                "}\n")
        findings = bridge_lint.check_cross_loader_forname([self.d])
        findings = bridge_lint._apply_justifications(findings)
        self.assertEqual(len(findings), 2)
        justified_flags = [f.justified for f in findings]
        self.assertIn(True, justified_flags)
        self.assertIn(False, justified_flags)


class TestPreflight(unittest.TestCase):
    def test_clean_and_bound_port(self):
        import preflight
        blockers, warnings, info = preflight.preflight(port=0,
                                                       min_free_gb=0.001)
        # port 0 is never connectable-free in the connect_ex sense...
        # use a real ephemeral bind instead
        import socket
        s = socket.socket()
        s.bind(("127.0.0.1", 0))
        free_port = s.getsockname()[1]
        s.close()
        blockers, warnings, info = preflight.preflight(
            port=free_port, min_free_gb=0.001)
        self.assertEqual(blockers, [])
        self.assertIn("free_gb", info)
        # now occupy the port -> blocker
        s = socket.socket()
        s.bind(("127.0.0.1", free_port))
        s.listen(1)
        try:
            blockers, _w, _i = preflight.preflight(port=free_port,
                                                   min_free_gb=0.001)
            self.assertTrue(any("port" in b for b in blockers))
        finally:
            s.close()

    def test_absurd_disk_requirement_blocks(self):
        import preflight
        blockers, _w, _i = preflight.preflight(port=0, min_free_gb=10 ** 9)
        self.assertTrue(any("GB free" in b for b in blockers))


class TestDecodeSolve(unittest.TestCase):
    def test_recovers_real_phosphor_layout(self):
        import decode_solve

        def enc(x, y, z):
            return (y << 52) | ((x + (1 << 25)) << 26) | (z + (1 << 25))
        pairs = [
            {"x": 192, "y": 70, "z": 285, "packed": enc(192, 70, 285)},
            {"x": 196, "y": 64, "z": 283, "packed": enc(196, 64, 283)},
            {"x": 5, "y": 6, "z": -7, "packed": enc(5, 6, -7)},
            {"x": -3, "y": 200, "z": 11, "packed": enc(-3, 200, 11)},
            {"x": 20000000, "y": 128, "z": -20000000,
             "packed": enc(20000000, 128, -20000000)},
        ]
        chosen, missing, _per = decode_solve.solve(pairs)
        self.assertEqual(chosen["x"]["shift"], 26)
        self.assertEqual(chosen["x"]["width"], 26)
        self.assertEqual(chosen["x"]["bias"], 1 << 25)
        self.assertEqual(chosen["z"]["shift"], 0)
        self.assertEqual(chosen["z"]["bias"], 1 << 25)
        self.assertEqual(chosen["y"]["shift"], 52)
        self.assertEqual(missing, [])
        # decoded values round-trip
        h = chosen["x"]
        self.assertEqual(decode_solve._unpack(pairs[0]["packed"],
                                              h["shift"], h["width"],
                                              h["bias"], h["signed"]), 192)

    def test_two_lane_overlap_rejected(self):
        import decode_solve
        # z claims ALL bits -> x must pick a disjoint layout
        def enc(x, z):
            return (x << 8) | z
        pairs = [{"x": 1, "z": 2, "packed": enc(1, 2)},
                 {"x": 300, "z": 5, "packed": enc(300, 5)}]
        chosen, _missing, _per = decode_solve.solve(pairs, lanes=("x", "z"))
        self.assertFalse(decode_solve._overlaps(chosen["x"], chosen["z"]))




if __name__ == "__main__":
    unittest.main()
