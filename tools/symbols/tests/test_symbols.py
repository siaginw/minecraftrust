"""Unit tests for the bytecode symbol index.

Tests run entirely on synthetic fixtures (hand-assembled class files, tiny
mapping files, in-memory SQLite) plus, when the machine has them, one real
World.class smoke parse. No Minecraft assets are required.
"""

import json
import os
import struct
import sys
import tempfile
import unittest
import zipfile

_HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(_HERE, ".."))

from symbol_index.builder import Builder, rename_descriptor  # noqa: E402
from symbol_index.classfile import (ClassFileError, decode_mutf8,  # noqa: E402
                                    parse_class)
from symbol_index.format import pretty_desc  # noqa: E402
from symbol_index.mappings import (Mappings, parse_joined_srg,  # noqa: E402
                                   parse_mcp_csv)
from symbol_index.query import Queries  # noqa: E402


# ----------------------------------------------------------------------
# synthetic class-file assembly

class CP:
    def __init__(self):
        self.entries = [None]  # index 0 unused
        self.cache = {}

    def _add(self, pack):
        self.entries.append(pack)
        return len(self.entries) - 1

    def utf8(self, s):
        if ("u", s) not in self.cache:
            b = s.encode("utf-8")
            self.cache[("u", s)] = self._add(
                struct.pack(">BH", 1, len(b)) + b)
        return self.cache[("u", s)]

    def clazz(self, name):
        if ("c", name) not in self.cache:
            self.cache[("c", name)] = self._add(
                struct.pack(">BH", 7, self.utf8(name)))
        return self.cache[("c", name)]

    def nat(self, name, desc):
        if ("n", name, desc) not in self.cache:
            self.cache[("n", name, desc)] = self._add(
                struct.pack(">BHH", 12, self.utf8(name), self.utf8(desc)))
        return self.cache[("n", name, desc)]

    def member(self, tag, owner, name, desc):
        if ("m", tag, owner, name, desc) not in self.cache:
            self.cache[("m", tag, owner, name, desc)] = self._add(
                struct.pack(">BHH", tag, self.clazz(owner),
                            self.nat(name, desc)))
        return self.cache[("m", tag, owner, name, desc)]

    def string(self, s):
        if ("s", s) not in self.cache:
            self.cache[("s", s)] = self._add(
                struct.pack(">BH", 8, self.utf8(s)))
        return self.cache[("s", s)]

    def integer(self, v):
        if ("i", v) not in self.cache:
            self.cache[("i", v)] = self._add(struct.pack(">Bi", 3, v))
        return self.cache[("i", v)]

    def ldc(self, idx):
        return bytes([0x12, idx]) if idx < 256 else b"\x13" + \
            struct.pack(">H", idx)

    def bytes(self):
        entries = [b""] + self.entries[1:]
        return struct.pack(">H", len(entries)) + b"".join(entries)


METHODREF, FIELDREF = 10, 9


def assemble_class(this, super_name="java/lang/Object", interfaces=(),
                   fields=(), methods=(), class_annotations=None):
    """Deterministic minimal class writer.

    class_annotations: list of (type_descriptor, {elem: (tag, payload)})
    rendered as RuntimeInvisibleAnnotations (tag 'c' class, 's' string,
    'I' int) — the @Mixin fixture shape.

    fields:  list of (access:int, name, desc)
    methods: list of dicts {access, name, desc, body}
      body None = abstract; list of instruction tuples:
        ("call", owner, name, desc, itf=False)
        ("scall", owner, name, desc)
        ("get", owner, name, desc)
        ("put", owner, name, desc)
        ("str", value)
        ("int", value)
      a bare "return" is appended automatically.
    """
    cp = CP()
    this_idx = cp.clazz(this)
    sup_idx = cp.clazz(super_name)
    ifc_idxs = [cp.clazz(i) for i in interfaces]

    field_data = []
    for access, name, desc in fields:
        field_data.append(struct.pack(">HHHH", access, cp.utf8(name),
                                      cp.utf8(desc), 0))

    method_data = []
    for m in methods:
        access, name, desc = m["access"], m["name"], m["desc"]
        body = m.get("body")
        if body is None:
            method_data.append(struct.pack(">HHHH", access, cp.utf8(name),
                                           cp.utf8(desc), 0))
            continue
        code = bytearray()
        for insn in body:
            if insn[0] == "call":
                idx = cp.member(METHODREF, insn[1], insn[2], insn[3])
                code += b"\xb6" + struct.pack(">H", idx)
            elif insn[0] == "scall":
                idx = cp.member(METHODREF, insn[1], insn[2], insn[3])
                code += b"\xb8" + struct.pack(">H", idx)
            elif insn[0] == "get":
                idx = cp.member(FIELDREF, insn[1], insn[2], insn[3])
                code += b"\xb2" + struct.pack(">H", idx)
            elif insn[0] == "put":
                idx = cp.member(FIELDREF, insn[1], insn[2], insn[3])
                code += b"\x03" + b"\xb3" + struct.pack(">H", idx)
            elif insn[0] == "str":
                code += cp.ldc(cp.string(insn[1])) + b"\x57"
            elif insn[0] == "int":
                code += cp.ldc(cp.integer(insn[1])) + b"\x57"
        code += b"\xb1"  # return
        code_attr = (struct.pack(">HHI", 1, 1, len(code))
                     + bytes(code)
                     + struct.pack(">HH", 0, 0))
        utf8_code = cp.utf8("Code")
        attrs = struct.pack(">HI", utf8_code, len(code_attr)) + code_attr
        method_data.append(struct.pack(">HHHH", access, cp.utf8(name),
                                       cp.utf8(desc), 1) + attrs)

    this_utf8 = cp.utf8("SourceFile")
    src_attr = struct.pack(">H", cp.utf8(this + ".java"))
    class_attrs = struct.pack(">HI", this_utf8, len(src_attr)) + src_attr
    for anno in class_annotations or ():
        # anno = (type_descriptor, {elem_name: ("c"|"s"|"I", payload)})
        type_idx = cp.utf8(anno[0])
        pairs = bytearray()
        pairs += struct.pack(">H", len(anno[1]))
        for elem, (tag, payload) in anno[1].items():
            pairs += struct.pack(">H", cp.utf8(elem))
            if tag == "c":
                pairs += b"c" + struct.pack(">H", cp.utf8(payload))
            elif tag == "s":
                pairs += b"s" + struct.pack(">H", cp.utf8(payload))
            elif tag == "I":
                pairs += b"I" + struct.pack(">H", cp.integer(payload))
        riva_body = struct.pack(">H", 1) + type_idx.to_bytes(2, "big") \
            + bytes(pairs)
        riva_name = cp.utf8("RuntimeInvisibleAnnotations")
        class_attrs += struct.pack(">HI", riva_name,
                                   len(riva_body)) + riva_body

    out = bytearray()
    out += struct.pack(">IHH", 0xCAFEBABE, 0, 52)
    out += cp.bytes()
    out += struct.pack(">HHH", 0x0021, this_idx, sup_idx)
    out += struct.pack(">H", len(ifc_idxs))
    for i in ifc_idxs:
        out += struct.pack(">H", i)
    out += struct.pack(">H", len(field_data)) + b"".join(field_data)
    out += struct.pack(">H", len(method_data)) + b"".join(method_data)
    out += struct.pack(">H", 1 + len(class_annotations or ()))         + class_attrs
    return bytes(out)


ACC_PUBLIC = 0x0001
ACC_STATIC = 0x0008
ACC_PUBLIC_STATIC = ACC_PUBLIC | ACC_STATIC


class TempIndex:
    """Builds a temp index from synthetic jars."""

    def __init__(self, jars, layers=None):
        self.dir = tempfile.TemporaryDirectory()
        self.db_path = os.path.join(self.dir.name, "test.sqlite")
        from symbol_index.mappings import Mappings
        mappings = Mappings()
        artifacts = []
        for i, (name, entries) in enumerate(jars):
            jar_path = os.path.join(self.dir.name, name + ".jar")
            with zipfile.ZipFile(jar_path, "w") as zf:
                for cls_name, data in entries:
                    zf.writestr(cls_name.replace(".", "/") + ".class", data)
            layer = (layers[i] if layers else "MOD_JAR")
            artifacts.append({
                "layer": layer, "name": name, "path": jar_path, "kind": "jar",
                "member_namespace": "raw", "entry_filter": "all",
                "anchor": i == 0,
            })
        b = Builder(self.db_path, mappings, progress=lambda s: None)
        b.build({"mc_version": "test", "mappings": [],
                 "artifacts": artifacts})
        import sqlite3
        self._builder_con = b.con
        self.con = sqlite3.connect(self.db_path)
        self.q = Queries(self.con)

    def close(self):
        self.con.close()
        self._builder_con.close()
        self.dir.cleanup()


# ----------------------------------------------------------------------
# parser tests

class TestMutf8(unittest.TestCase):
    def test_plain(self):
        self.assertEqual(decode_mutf8(b"hello"), "hello")

    def test_nul_special(self):
        self.assertEqual(decode_mutf8(b"a\xc0\x80b"), "a\x00b")

    def test_surrogate_pair(self):
        # U+10437 encoded as CESU-8 surrogate pair
        self.assertEqual(decode_mutf8(b"\xed\xa0\x81\xed\xb0\xb7"),
                         "\U00010437")

    def test_two_byte(self):
        self.assertEqual(decode_mutf8(b"\xc3\xa9"), "é")


class TestParser(unittest.TestCase):
    def test_facts(self):
        data = assemble_class("com/example/Foo", methods=[
            {"access": ACC_PUBLIC_STATIC, "name": "go", "desc": "()V",
             "body": [("scall", "com/example/Bar", "run", "()V"),
                      ("get", "java/lang/System", "out",
                       "Ljava/io/PrintStream;"),
                      ("str", "hello"),
                      ("int", 4096)]},
        ])
        cf = parse_class(data)
        self.assertEqual(cf.this_class, "com/example/Foo")
        self.assertEqual(cf.major, 52)
        self.assertEqual(len(cf.methods), 1)
        m = cf.methods[0]
        self.assertEqual(m.name, "go")
        self.assertEqual([v for _o, v in m.strings], ["hello"])
        self.assertIn(("int", "4096"), [(k, v) for _o, k, v in m.nums])
        self.assertIn(("com/example/Bar", "run", "()V"),
                      [tuple(i[2:5]) for i in m.invocations])
        self.assertIn(("java/lang/System", "out"),
                      [(f[2], f[3]) for f in m.field_ops])
        self.assertIsNotNone(m.code_sha256)

    def test_bad_magic(self):
        with self.assertRaises(ClassFileError):
            parse_class(b"\x00\x01\x02\x03" + b"\x00" * 8)

    def test_truncated(self):
        data = assemble_class("A", methods=[{"access": 1, "name": "a",
                                             "desc": "()V", "body": []}])
        with self.assertRaises(ClassFileError):
            parse_class(data[:len(data) // 2])

    def test_real_world_class_if_present(self):
        p = ("C:/rustcraft/target/authority-smoke/sc-bisect-obs/server/"
             "transformed/net/minecraft/world/World.class")
        if not os.path.exists(p):
            self.skipTest("machine-local transformed dump not present")
        cf = parse_class(open(p, "rb").read())
        names = {m.name for m in cf.methods}
        self.assertIn("func_180500_c", names)  # checkLightFor (SRG runtime)
        for m in cf.methods:
            if m.name == "func_180500_c":
                self.assertEqual(m.descriptor,
                                 "(Lnet/minecraft/world/EnumSkyBlock;"
                                 "Lnet/minecraft/util/math/BlockPos;)Z")


class TestDescriptors(unittest.TestCase):
    def test_pretty(self):
        self.assertEqual(
            pretty_desc("(Lnet/minecraft/world/EnumSkyBlock;"
                        "Lnet/minecraft/util/math/BlockPos;)Z"),
            "(EnumSkyBlock, BlockPos) -> boolean")
        self.assertEqual(pretty_desc("()V"), "() -> void")
        self.assertEqual(pretty_desc("()[I"), "() -> int[]")

    def test_rename(self):
        self.assertEqual(
            rename_descriptor("(Lana;Let;)Z",
                              {"ana": "net/minecraft/world/EnumSkyBlock",
                               "et": "net/minecraft/util/math/BlockPos"}),
            "(Lnet/minecraft/world/EnumSkyBlock;"
            "Lnet/minecraft/util/math/BlockPos;)Z")
        self.assertEqual(rename_descriptor("()V", {"a": "b"}), "()V")


# ----------------------------------------------------------------------
# mapping tests

class TestMappings(unittest.TestCase):
    def _write(self, tmp, name, content):
        p = os.path.join(tmp, name)
        with open(p, "w", encoding="utf-8") as fh:
            fh.write(content)
        return p

    def test_srg_and_csv(self):
        with tempfile.TemporaryDirectory() as tmp:
            srg = self._write(tmp, "joined.srg", "\n".join([
                "PK: . net/minecraft/src",
                "CL: amu net/minecraft/world/World",
                "CL: ana net/minecraft/world/EnumSkyBlock",
                "FD: ana/b net/minecraft/world/EnumSkyBlock/BLOCK",
                "MD: amu/c (Lana;Let;)Z net/minecraft/world/World/"
                "func_180500_c (Lnet/minecraft/world/EnumSkyBlock;"
                "Lnet/minecraft/util/math/BlockPos;)Z",
                "MD: amu/a (Let;Lana;)I net/minecraft/world/World/"
                "func_175638_a (Lnet/minecraft/util/math/BlockPos;"
                "Lnet/minecraft/world/EnumSkyBlock;)I",
                ""]))
            methods = self._write(
                tmp, "methods.csv",
                "searge,name,side,desc\n"
                "func_180500_c,checkLightFor,2,\n"
                "func_175638_a,getRawLight,2,\n")
            fields = self._write(tmp, "fields.csv",
                                 "searge,name,side,desc\n"
                                 "field_XXXX_b,BLOCK,2,\n")
            m = parse_joined_srg(srg)
            m = parse_mcp_csv(methods, fields, m)
            self.assertEqual(m.class_to_srg["amu"],
                             "net/minecraft/world/World")
            # notch name 'c' is overloaded across descs: descriptor keys it
            self.assertEqual(
                m.method_srg[("amu", "c",
                              "(Lana;Let;)Z")],
                ("net/minecraft/world/World", "func_180500_c"))
            self.assertEqual(m.method_mcp["func_180500_c"], "checkLightFor")
            self.assertEqual(
                m.resolve_mcp_method("net/minecraft/world/World",
                                     "checkLightFor",
                                     "(Lnet/minecraft/world/EnumSkyBlock;"
                                     "Lnet/minecraft/util/math/BlockPos;)Z"),
                "func_180500_c")
            self.assertEqual(
                m.method_notch[("net/minecraft/world/World",
                                "func_180500_c")],
                ("amu", "c", "(Lana;Let;)Z"))


# ----------------------------------------------------------------------
# end-to-end query tests on synthetic jars

FOO = assemble_class("com/example/Foo", super_name="com/example/Base",
                     methods=[
                         {"access": ACC_PUBLIC, "name": "run", "desc": "()V",
                          "body": [("scall", "com/example/Util", "help",
                                    "()V"),
                                   ("get", "com/example/Base", "STATE",
                                    "I"),
                                   ("str", "hello")]},
                     ])
FOO_SUB = assemble_class("com/example/Sub", super_name="com/example/Foo",
                         methods=[
                             {"access": ACC_PUBLIC, "name": "run",
                              "desc": "()V", "body": []},
                         ])
UTIL = assemble_class("com/example/Util", methods=[
    {"access": ACC_PUBLIC_STATIC, "name": "help", "desc": "()V",
     "body": [("put", "com/example/Base", "STATE", "I"),
              ("int", 8192)]},
    {"access": ACC_PUBLIC_STATIC, "name": "named", "desc": "()V",
     "body": [("str", "unique-marker")]},
])
BASE = assemble_class("com/example/Base", fields=[
    (ACC_PUBLIC_STATIC, "STATE", "I"),
], methods=[
    {"access": ACC_PUBLIC, "name": "run", "desc": "()V", "body": []},
])


class TestEndToEnd(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.idx = TempIndex([
            ("base", [("com.example.Base", BASE),
                      ("com.example.Util", UTIL)]),
            ("app", [("com.example.Foo", FOO),
                     ("com.example.Sub", FOO_SUB)]),
        ], layers=["MOD_JAR", "MOD_JAR2"])

    @classmethod
    def tearDownClass(cls):
        cls.idx.close()

    def test_method_identity_and_layers(self):
        matches, amb = self.idx.q.resolve_method("com.example.Util.help")
        self.assertEqual(len(matches), 1)
        card = matches[0]
        self.assertEqual(card["descriptor"], "()V")
        self.assertEqual(len(card["layers"]), 1)
        self.assertEqual(card["layers"][0]["artifact"], "base")

    def test_callers_and_callees(self):
        matches, _ = self.idx.q.resolve_method("com.example.Util.help")
        ident = matches[0]["identity_key"]
        callers = self.idx.q.callers(ident)
        self.assertEqual([c["caller_class"] for c in callers],
                         ["com.example.Foo"])
        callees = self.idx.q.callees(ident)
        # help() has no outgoing edges; check via Foo.run instead
        m2, _ = self.idx.q.resolve_method("com.example.Foo.run")
        callees = self.idx.q.callees(m2[0]["identity_key"])
        targets = {(c["callee_class"], c["name"]) for c in callees}
        self.assertIn(("com.example.Util", "help"), targets)

    def test_field_access_graph(self):
        cards = self.idx.q.resolve_field("com.example.Base.STATE")
        self.assertEqual(len(cards), 1)
        c = cards[0]
        self.assertEqual(c["readers_count"], 1)
        self.assertEqual(c["writers_count"], 1)
        readers = self.idx.q.field_readers(c["identity_key"], 1)
        self.assertEqual(readers[0]["caller_class"], "com.example.Foo")
        writers = self.idx.q.field_readers(c["identity_key"], 0)
        self.assertEqual(writers[0]["caller_class"], "com.example.Util")

    def test_strings_and_constants(self):
        hits = self.idx.q.strings("hello")
        self.assertTrue(any(h["class"] == "com.example.Foo" for h in hits))
        hits = self.idx.q.strings("unique-marker")
        self.assertTrue(any(h["class"] == "com.example.Util" for h in hits))
        consts = self.idx.q.constants("4096")
        self.assertEqual(consts, [])
        consts = self.idx.q.constants("8192")
        self.assertTrue(any(c["class"] == "com.example.Util"
                            for c in consts))

    def test_overrides(self):
        matches, _ = self.idx.q.resolve_method("com.example.Base.run")
        overs = self.idx.q.find_overrides(matches[0]["identity_key"])
        classes = {o["class"] for o in overs}
        self.assertIn("com.example.Foo", classes)
        self.assertIn("com.example.Sub", classes)

    def test_refs_and_class_card(self):
        refs = self.idx.q.refs_to_class("com.example.Util")
        self.assertTrue(any(r["caller_class"] == "com.example.Foo"
                            for r in refs))
        card = self.idx.q.class_card("com/example/Foo")
        self.assertEqual(card["super"], "com.example.Base")
        self.assertEqual(card["subclasses_count"], 1)

    def test_search(self):
        hits = self.idx.q.search(["named"])
        self.assertTrue(hits)
        self.assertTrue(any("named" in (h.get("mcp"), h.get("srg"),
                                        h.get("name"))
                            for h in hits))

    def test_ambiguity_reported(self):
        # 'run' exists in Base, Foo and Sub -> ambiguous without class hint
        matches, amb = self.idx.q.resolve_method("run")
        self.assertEqual(matches, [])
        self.assertTrue(amb)


class TestAccessFlagsFormat(unittest.TestCase):
    def test_pretty_access(self):
        from symbol_index.format import pretty_access
        self.assertEqual(pretty_access(0x0001), "public")
        self.assertIn("static", pretty_access(0x0009))
        self.assertEqual(pretty_access(0), "package-private")


class TestIncrementalCache(unittest.TestCase):
    def test_incremental_reuse_and_refresh(self):
        import sqlite3
        import tempfile
        import zipfile
        from symbol_index.builder import Builder
        from symbol_index.mappings import Mappings

        d = tempfile.mkdtemp(prefix="sym-incr-")
        self.addCleanup(__import__("shutil").rmtree, d, True)
        jar1 = os.path.join(d, "one.jar")
        jar2 = os.path.join(d, "two.jar")
        jar3 = os.path.join(d, "three.jar")
        for path, entries in (
                (jar1, [("com/example/Foo.class", FOO),
                        ("com/example/Base.class", BASE),
                        ("com/example/Util.class", UTIL)]),
                (jar2, [("com/example/Sub.class", FOO_SUB)])):
            with zipfile.ZipFile(path, "w") as zf:
                for name, data in entries:
                    zf.writestr(name, data)

        db = os.path.join(d, "idx.sqlite")
        mappings = Mappings()

        def artifact(path, layer="MOD_JAR"):
            return {"layer": layer, "name": os.path.basename(path),
                    "path": path, "kind": "jar",
                    "member_namespace": "raw",
                    "entry_filter": "all", "anchor": layer == "MOD_JAR"}

        b = Builder(db, mappings, progress=lambda s: None)
        b.build({"mc_version": "t", "mappings": [],
                 "artifacts": [artifact(jar1), artifact(jar2)]})
        b.con.close()
        con = sqlite3.connect(db)
        id_before = dict(con.execute(
            "SELECT name, id FROM artifacts"))
        n_methods = con.execute("SELECT COUNT(*) FROM methods").fetchone()[0]
        con.close()

        # jar1 unchanged, jar2 replaced with genuinely different class
        # bytes (zip entry names don't matter — the parser reads the
        # internal class name), jar3 added
        with zipfile.ZipFile(jar2, "w") as zf:
            zf.writestr("com/example/Foo.class", FOO)
        with zipfile.ZipFile(jar3, "w") as zf:
            zf.writestr("com/example/Util.class", UTIL)

        b2 = Builder(db, mappings, progress=lambda s: None)
        stats = b2.build({"mc_version": "t", "mappings": [],
                          "artifacts": [artifact(jar1), artifact(jar2),
                                        artifact(jar3)]},
                         incremental=True)
        con = sqlite3.connect(db)
        id_after = dict(con.execute("SELECT name, id FROM artifacts"))
        self.assertEqual(id_after["one.jar"], id_before["one.jar"],
                         "unchanged artifact must keep its id")
        # jar1's rows must be the SAME rows (copied), verified by the
        # class file hashes surviving; jar2 re-parsed to the new class set
        classes_two = {r[0] for r in con.execute(
            "SELECT c.canonical_name FROM classes c JOIN artifacts a ON "
            "a.id=c.artifact_id WHERE a.name='two.jar'")}
        self.assertEqual(classes_two, {"com/example/Foo"})
        classes_three = {r[0] for r in con.execute(
            "SELECT c.canonical_name FROM classes c JOIN artifacts a ON "
            "a.id=c.artifact_id WHERE a.name='three.jar'")}
        self.assertEqual(classes_three, {"com/example/Util"})
        self.assertEqual(
            con.execute("SELECT COUNT(*) FROM methods").fetchone()[0],
            n_methods + 2)  # Extra adds Util's 2 methods again
        con.close()
        cached = [s for s in stats if s.get("cached")]
        self.assertEqual(len(cached), 1)
        self.assertEqual(cached[0]["name"], "one.jar")


class TestProvenanceGuard(unittest.TestCase):
    def test_forge_version_mismatch_warns(self):
        import sqlite3
        import tempfile
        import zipfile
        from symbol_index.builder import Builder
        from symbol_index.mappings import Mappings
        from symbol_index.query import Queries

        d = tempfile.mkdtemp(prefix="sym-prov-")
        self.addCleanup(__import__("shutil").rmtree, d, True)
        jars = []
        for name in ("a.jar", "b.jar"):
            p = os.path.join(d, name)
            with zipfile.ZipFile(p, "w") as zf:
                zf.writestr("com/example/Foo.class", FOO)
            jars.append(p)
        db = os.path.join(d, "idx.sqlite")
        b = Builder(db, Mappings(), progress=lambda s: None)
        b.build({"mc_version": "1.12.2", "mappings": [], "artifacts": [
            {"layer": "FORGE_MOD", "name": "a", "path": jars[0],
             "kind": "jar", "member_namespace": "raw",
             "entry_filter": "all", "anchor": True,
             "forge_version": "14.23.5.2846"},
            {"layer": "LIVE_TRANSFORMED", "name": "b", "path": jars[1],
             "kind": "jar", "member_namespace": "raw",
             "entry_filter": "all", "anchor": False,
             "forge_version": "14.23.5.2860"},
        ]})
        b.con.close()
        con = sqlite3.connect(db)
        q = Queries(con)
        _changed, _added, prov = q._pair_diff("FORGE_MOD",
                                              "LIVE_TRANSFORMED")
        self.assertEqual(len(prov["warnings"]), 1)
        self.assertIn("14.23.5.2860", prov["warnings"][0])
        # vanilla baseline layers carry no forge_version -> no warning
        prov2 = q.provenance_check("FORGE_MOD", "FORGE_MOD")
        self.assertEqual(prov2["warnings"], [])


class TestMixinCapture(unittest.TestCase):
    MIXIN_ANNO = ("Lorg/spongepowered/asm/mixin/Mixin;",
                  {"value": ("c", "Lcom/example/Base;"),
                   "priority": ("I", 1066)})

    def test_parser_captures_mixin_annotation(self):
        data = assemble_class(
            "com/example/MixinBase", super_name="java/lang/Object",
            methods=[{"access": ACC_PUBLIC, "name": "go", "desc": "()V",
                      "body": []}],
            class_annotations=[self.MIXIN_ANNO])
        cf = parse_class(data)
        self.assertEqual(len(cf.mixin), 1)
        self.assertIn("org/spongepowered/asm/mixin/Mixin",
                      cf.mixin[0]["type"])
        # single-class @Mixin → scalar 'c' value; arrays → list
        self.assertEqual(cf.mixin[0]["values"]["value"],
                         "com/example/Base")
        self.assertEqual(cf.mixin[0]["values"]["priority"], 1066)

    def test_end_to_end_mixin_query(self):
        import sqlite3
        import tempfile
        import zipfile
        from symbol_index.builder import Builder
        from symbol_index.mappings import Mappings
        from symbol_index.query import Queries

        mixin_cls = assemble_class(
            "com/example/MixinWorld", super_name="java/lang/Object",
            methods=[{"access": ACC_PUBLIC, "name": "go", "desc": "()V",
                      "body": []}],
            class_annotations=[self.MIXIN_ANNO])
        target_cls = assemble_class(
            "com/example/Base", methods=[
                {"access": ACC_PUBLIC, "name": "run", "desc": "()V",
                 "body": []}])
        d = tempfile.mkdtemp(prefix="sym-mixin-")
        self.addCleanup(__import__("shutil").rmtree, d, True)
        jar = os.path.join(d, "mod.jar")
        with zipfile.ZipFile(jar, "w") as zf:
            zf.writestr("com/example/MixinWorld.class", mixin_cls)
            zf.writestr("com/example/Base.class", target_cls)
            zf.writestr("mixins.example.json",
                        '{"package": "com.example", "mixins": '
                        '["MixinWorld"], "refmap": "x"}')
        db = os.path.join(d, "idx.sqlite")
        b = Builder(db, Mappings(), progress=lambda s: None)
        b.build({"mc_version": "t", "mappings": [], "artifacts": [
            {"layer": "MOD_JAR", "name": "mod", "path": jar, "kind": "jar",
             "member_namespace": "raw", "entry_filter": "all",
             "anchor": True}]})
        b.con.close()
        con = sqlite3.connect(db)
        res = Queries(con).mixins_into("com.example.Base")
        self.assertEqual(len(res["mixins"]), 1)
        m = res["mixins"][0]
        self.assertEqual(m["mixin_class"], "com.example.MixinWorld")
        self.assertEqual(m["target_raw"], "com/example/Base")
        self.assertEqual(m["priority"], 1066)
        self.assertTrue(m["mixin_class_indexed"])
        self.assertEqual(len(res["configs"]), 1)
        self.assertEqual(res["configs"][0]["package"], "com.example")
        self.assertEqual(res["configs"][0]["mixin_count"], 1)
        con.close()


class TestBodyReader(unittest.TestCase):
    def test_ordered_stream_and_render(self):
        import sqlite3
        import tempfile
        import zipfile
        from symbol_index.builder import Builder
        from symbol_index.body import BodyReader, render_body
        from symbol_index.mappings import Mappings

        callee = assemble_class("com/example/Util", methods=[
            {"access": ACC_PUBLIC_STATIC, "name": "help", "desc": "()V",
             "body": []}])
        caller = assemble_class("com/example/Foo", methods=[
            {"access": ACC_PUBLIC, "name": "run", "desc": "()V",
             "body": [("scall", "com/example/Util", "help", "()V"),
                      ("str", "marker")]},
        ])
        d = tempfile.mkdtemp(prefix="sym-body-")
        self.addCleanup(__import__("shutil").rmtree, d, True)
        jar = os.path.join(d, "app.jar")
        with zipfile.ZipFile(jar, "w") as zf:
            zf.writestr("com/example/Foo.class", caller)
            zf.writestr("com/example/Util.class", callee)
        db = os.path.join(d, "idx.sqlite")
        b = Builder(db, Mappings(), progress=lambda s: None)
        b.build({"mc_version": "t", "mappings": [], "artifacts": [
            {"layer": "MOD_JAR", "name": "app", "path": jar, "kind": "jar",
             "member_namespace": "raw", "entry_filter": "all",
             "anchor": True}]})
        con = sqlite3.connect(db)
        reader = BodyReader(con)
        row = con.execute(
            "SELECT m.name, m.descriptor, c.internal_name, m.artifact_id "
            "FROM methods m JOIN classes c ON c.id=m.class_id "
            "WHERE m.name='run'").fetchone()
        cf = parse_class(reader.load_bytes(row[3], row[2]))
        m = next(x for x in cf.methods if x.name == "run")
        events = reader.ordered_stream(m)
        kinds = [k for _o, k, _p in events]
        self.assertEqual(kinds, ["invoke", "string"])
        lines = render_body(reader, m, raw=True)
        self.assertIn("invokestatic", lines[0])
        self.assertIn("com/example/Util.help", lines[0])
        self.assertIn("'marker'", lines[1])
        con.close()


if __name__ == "__main__":
    unittest.main()
