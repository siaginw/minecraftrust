"""Sensitivity controls for the ASM binary ABI comparison.

A comparison tool that has only ever returned "equivalent" proves nothing: it
may be reading the wrong bytes, joining on the wrong key, or quietly skipping
every symbol. These controls make each failure mode observable by constructing
an ASM jar that is deliberately broken in one specific way and asserting that
the classifier names that exact break.

The mutant is a real jar, built by replacing one entry of a real pinned ASM jar
with a real compiled classfile. Nothing here is mocked: the classifier sees
exactly what it would see if a runtime actually shipped a changed ASM.

Set RUSTCRAFT_TEST_ASM to run; the tests skip when it is absent.
"""
from __future__ import annotations

import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
import zipfile

from tools.testing.asm_abi import (IDENTICAL, MISSING, REAL_DIFFERENCE, SIGNATURE_ONLY,
                                   compare, members, resolution_key)

JAVA = os.environ.get("RUSTCRAFT_TEST_JAVA") or os.environ.get("JAVA8_HOME")
BASELINE = os.environ.get("RUSTCRAFT_TEST_ASM")
#: RustCraft's own classfiles, compiled against the baseline jar. Their constant
#: pools are the reference set the classifier is measured on, so a run without
#: them has nothing to classify and is skipped rather than faked.
REFERENCE = Path(os.environ.get("RUSTCRAFT_TEST_ABI_CLASSES", "target/abi/clean"))

#: Deliberately broken replacement for `org.objectweb.asm.tree.ClassNode`.
#:
#: `version` widens from int to long, which keeps the name and is a real ABI
#: break. `access` is gone, which is a missing member. `attrs` keeps its
#: descriptor but loses its generic argument, which is only a signature
#: difference. `name` is untouched, so a classifier that simply shouts
#: "broken" at everything is caught as well. The class deliberately does not
#: extend ClassVisitor, so the inherited members RustCraft references become
#: MISSING as well -- resolution must follow the superclass chain, and a jar
#: with no chain must not paper over that.
MUTANT = """package org.objectweb.asm.tree;

public class ClassNode {
    public long version;
    public java.lang.String name;
    public java.util.List attrs;
    public java.util.List methods;
}
"""


def javap() -> str | None:
    home = JAVA
    if home and Path(home).is_dir():
        candidate = Path(home) / "bin" / ("javap.exe" if os.name == "nt" else "javap")
        if candidate.is_file():
            return str(candidate)
    return shutil.which("javap")


def javac() -> str | None:
    home = JAVA
    if home and Path(home).is_dir():
        candidate = Path(home) / "bin" / ("javac.exe" if os.name == "nt" else "javac")
        if candidate.is_file():
            return str(candidate)
    return shutil.which("javac")


def build_mutant(root: Path, jar: Path) -> Path:
    """A copy of the pinned jar with exactly one entry replaced."""
    source = root / "org" / "objectweb" / "asm" / "tree" / "ClassNode.java"
    source.parent.mkdir(parents=True, exist_ok=True)
    source.write_text(MUTANT, encoding="utf-8")
    classes = root / "classes"
    # javac -d does not create the output root, and a missing directory is
    # reported as a compiler error rather than as a setup mistake.
    classes.mkdir(parents=True, exist_ok=True)
    compiled = subprocess.run([javac(), "-nowarn", "-d", str(classes), str(source)],
                              capture_output=True, text=True)
    if compiled.returncode != 0:
        # A silent CalledProcessError here reads as "the classifier is broken".
        raise AssertionError("the mutant did not compile:\n" + compiled.stdout + compiled.stderr)
    replacement = classes / "org" / "objectweb" / "asm" / "tree" / "ClassNode.class"
    if not replacement.is_file():
        raise AssertionError("the mutant did not compile to a classfile")
    mutant = root / "asm-mutant.jar"
    with zipfile.ZipFile(jar) as source_jar, \
            zipfile.ZipFile(mutant, "w", zipfile.ZIP_DEFLATED) as target:
        for entry in source_jar.infolist():
            if entry.filename == "org/objectweb/asm/tree/ClassNode.class":
                target.writestr(entry.filename, replacement.read_bytes())
            else:
                target.writestr(entry, source_jar.read(entry.filename))
    return mutant


class ResolutionKeyTests(unittest.TestCase):
    def test_return_type_is_not_part_of_the_key(self):
        """A return type that changed has to stay findable, or the single most
        important break in the whole comparison becomes invisible."""
        self.assertEqual(resolution_key("getType", "()I"), resolution_key("getType", "()Ljava/lang/String;"))

    def test_parameters_separate_overloads(self):
        self.assertNotEqual(resolution_key("visit", "(I)V"), resolution_key("visit", "(II)V"))

    def test_fields_key_on_their_kind_not_their_type(self):
        self.assertEqual(resolution_key("version", "I"), resolution_key("version", "J"))


class MemberLookupTests(unittest.TestCase):
    @unittest.skipUnless(BASELINE and javap(), "needs RUSTCRAFT_TEST_ASM and javap")
    def test_inherited_members_resolve(self):
        """A reference names only the subclass, so a lookup that stopped at
        declared members would call every inherited method absent."""
        found = members(javap(), Path(BASELINE), "org/objectweb/asm/tree/FieldInsnNode")
        self.assertIsNotNone(found)
        # getOpcode is declared on AbstractInsnNode, not on FieldInsnNode, and
        # the key carries parameters only -- the return type is compared, not
        # used to find the member.
        self.assertIn("getOpcode()", found)

    @unittest.skipUnless(BASELINE and javap(), "needs RUSTCRAFT_TEST_ASM and javap")
    def test_a_type_absent_from_the_jar_is_reported_as_absent(self):
        self.assertIsNone(members(javap(), Path(BASELINE), "org/objectweb/asm/NotARealType"))


class MutationDetectionTests(unittest.TestCase):
    """The verdict is only worth anything if a real break changes it."""

    def setUp(self):
        if not (BASELINE and javap() and javac()):
            self.skipTest("needs RUSTCRAFT_TEST_ASM, javac and javap")
        if not REFERENCE.is_dir() or not any(REFERENCE.rglob("*.class")):
            self.skipTest("needs RUSTCRAFT_TEST_ABI_CLASSES to point at the compiled bridge classes")
        self.root = Path(tempfile.mkdtemp(prefix="asm-abi-mutant-"))
        self.addCleanup(shutil.rmtree, self.root, True)
        self.mutant = build_mutant(self.root, Path(BASELINE))

    def classification(self, report, owner, name, descriptor):
        for symbol in report["symbols"]:
            if (symbol["owner"], symbol["name"], symbol["descriptor"]) == (owner, name, descriptor):
                return symbol["classification"], symbol["detail"]
        self.fail(f"{owner}.{name}{descriptor} was not classified at all")

    def test_a_widened_field_is_a_real_abi_difference(self):
        report = compare(REFERENCE, Path(BASELINE), self.mutant, javap())
        kind, detail = self.classification(report, "org/objectweb/asm/tree/ClassNode",
                                           "version", "I")
        self.assertEqual(kind, REAL_DIFFERENCE, detail)
        self.assertIn("J", detail)

    def test_a_removed_field_is_missing(self):
        report = compare(REFERENCE, Path(BASELINE), self.mutant, javap())
        kind, detail = self.classification(report, "org/objectweb/asm/tree/ClassNode",
                                           "access", "I")
        self.assertEqual(kind, MISSING, detail)

    def test_an_erased_generic_field_is_only_a_signature_difference(self):
        """The whole reason this comparison is not a stop: erasure makes the
        raw List and the List<Foo> the same member to the linker."""
        report = compare(REFERENCE, Path(BASELINE), self.mutant, javap())
        kind, detail = self.classification(report, "org/objectweb/asm/tree/ClassNode",
                                           "methods", "Ljava/util/List;")
        self.assertIn(kind, (SIGNATURE_ONLY, IDENTICAL), detail)

    def test_an_untouched_member_still_reads_as_identical(self):
        report = compare(REFERENCE, Path(BASELINE), self.mutant, javap())
        kind, _ = self.classification(report, "org/objectweb/asm/tree/ClassNode",
                                     "name", "Ljava/lang/String;")
        self.assertEqual(kind, IDENTICAL)

    def test_a_broken_jar_must_flip_the_verdict(self):
        report = compare(REFERENCE, Path(BASELINE), self.mutant, javap())
        self.assertEqual(report["verdict"], "REQUIRES_ULTRA_REVIEW_ASM_ABI")
        self.assertTrue(report["blocking"])


if __name__ == "__main__":
    unittest.main()
