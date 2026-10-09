"""Controls for the canonical manifest identity.

`canonical_manifest_identity` is what the static recipe binds the runtime
manifest to. Two properties must hold at the same time, and they pull in
opposite directions, which is why both are tested rather than one:

  * PATH INDEPENDENCE -- the same qualification at two output locations, or
    with the runtime relocated, yields the same identity. Without this, the
    static contract hash would change for reasons that have nothing to do with
    what was authorized.
  * SENSITIVITY -- anything that genuinely changes what is being qualified
    (an artifact byte, the hook inventory, the schema) moves the identity.
    Without this, the correction would have quietly weakened artifact binding.

The manifests here are real ones captured from actual qualification runs, not
hand-built minimal objects, so the controls exercise the fields the engine
really sees.
"""
from __future__ import annotations

import copy
import json
from pathlib import Path
import shutil
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "tools" / "testing"))
from manifest_identity import canonical_manifest_identity  # noqa: E402

RUNS = Path(__file__).resolve().parents[2] / "target" / "architecture-hardening"


def _load(name: str) -> dict:
    path = RUNS / name / "manifest.json"
    if not path.is_file():
        raise unittest.SkipTest(f"no captured manifest at {name}")
    return json.loads(path.read_text(encoding="utf-8"))


class ManifestIdentityControls(unittest.TestCase):

    def setUp(self):
        try:
            self.manifest = _load("qualify-rev-locA")
        except unittest.SkipTest:
            self.skipTest("no captured Revelation manifest available")

    def test_output_location_does_not_move_the_identity(self):
        """The two real runs differ ONLY in their output location.

        Their raw manifests differ (the collector and validator command lines
        embed the output path, and the pins hash those documents), so a control
        that compares the raw documents would prove nothing. The identity is
        what must agree.
        """
        other = copy.deepcopy(self.manifest)
        # The renamed output directory must contain the documents the manifest
        # references, exactly as a real second run would: its config files exist
        # at its own location, with the same semantic content.
        relocated = []
        for entry in [other["collector"], *other.get("validators", {}).values()]:
            argv = entry["command"]
            for index, argument in enumerate(argv):
                if isinstance(argument, str) and "qualify-rev-locA" in argument:
                    moved = Path(argument.replace("qualify-rev-locA",
                                                  "a-completely-different-output-name"))
                    original = Path(argument)
                    if original.is_file() and not moved.is_file():
                        moved.parent.mkdir(parents=True, exist_ok=True)
                        shutil.copyfile(original, moved)
                    argv[index] = str(moved)
                    relocated.append((original, moved))
        try:
            self.assertEqual(canonical_manifest_identity(self.manifest),
                             canonical_manifest_identity(other),
                             "an output-location difference must not move the static identity")
        finally:
            for original, moved in relocated:
                if moved.is_file():
                    moved.unlink()
                for parent in moved.parents:
                    if parent == RUNS:
                        break
                    try:
                        parent.rmdir()
                    except OSError:
                        break

    def test_runtime_root_relocation_does_not_move_the_identity(self):
        """Same artifact contents under a different root is the same runtime.

        The inventory binds every artifact by RELATIVE path and content hash,
        so the root itself is pure location and must not enter the identity.
        """
        other = copy.deepcopy(self.manifest)
        other["runtime_root"] = "X:\\relocated\\runtime\\root\\somewhere\\else"
        self.assertEqual(canonical_manifest_identity(self.manifest),
                         canonical_manifest_identity(other),
                         "runtime-root relocation with identical artifacts is the same runtime")

    def test_an_artifact_byte_change_moves_the_identity(self):
        """Over-broad normalization would be the opposite failure.

        If a runtime artifact changed and the identity did not, the static
        contract would authorize bytes it never described.
        """
        other = copy.deepcopy(self.manifest)
        files = other["inventories"]["artifacts"]["files"]
        changed = next(k for k, v in files.items() if v)
        files[changed] = "f" * 64
        self.assertNotEqual(canonical_manifest_identity(self.manifest),
                            canonical_manifest_identity(other),
                            "a runtime artifact byte change must move the identity")

    def test_a_schema_change_moves_the_identity(self):
        other = copy.deepcopy(self.manifest)
        other["schema"] = "RUSTCRAFT_RUNTIME_MANIFEST_V3"
        self.assertNotEqual(canonical_manifest_identity(self.manifest),
                            canonical_manifest_identity(other),
                            "a manifest schema change must move the identity")

    def test_a_toolchain_change_moves_the_identity(self):
        """Toolchain identity is by executable CONTENT, and it is intentional.

        Bound by the executable's own SHA-256 rather than its location, so two
        identical toolchains at different paths are the same tool while a
        different one is not.
        """
        other = copy.deepcopy(self.manifest)
        other["identity_tool"]["java_sha256"] = "e" * 64
        self.assertNotEqual(canonical_manifest_identity(self.manifest),
                            canonical_manifest_identity(other),
                            "a toolchain content change must move the identity")

    def test_a_toolchain_relocation_alone_does_not_move_the_identity(self):
        other = copy.deepcopy(self.manifest)
        other["identity_tool"]["java"] = "X:\\relocated\\jdk8\\bin\\java.exe"
        self.assertEqual(canonical_manifest_identity(self.manifest),
                         canonical_manifest_identity(other),
                         "moving an identical toolchain is not a toolchain change")

    def test_identity_is_stable_across_serialisation_round_trips(self):
        """The engine reads the manifest from disk; the driver computed the
        identity from the in-memory dict. Both must agree, or the same run
        would carry two different manifest identities."""
        raw = json.dumps(self.manifest, indent=2, ensure_ascii=False)
        round_tripped = json.loads(raw)
        self.assertEqual(canonical_manifest_identity(self.manifest),
                         canonical_manifest_identity(round_tripped),
                         "identity must survive a JSON round trip")


if __name__ == "__main__":
    unittest.main()
