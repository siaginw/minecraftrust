"""Pure-Python JVM class-file parser (Java 8 / major version <= 52 focus).

Parses the binary class format directly (no javap, no regex). Extracts the
structural facts the symbol index needs: class metadata, fields, methods,
per-method bytecode facts (invocations, field accesses, type references,
string/numeric constants, exception handlers) and annotations.

Post-Java-8 constant-pool tags (17/19/20) parse leniently; attributes that
the index does not need are skipped by their declared length. Truncated or
malformed files raise ClassFileError.
"""

import hashlib
import struct

from . import opcodes as ops

_MAGIC = 0xCAFEBABE


class ClassFileError(Exception):
    pass


def decode_mutf8(b):
    """Decode JVM modified UTF-8. Fast path is strict UTF-8; the slow path
    handles the C0 80 NUL encoding and CESU-8 surrogate pairs."""
    try:
        return b.decode("utf-8")
    except UnicodeDecodeError:
        pass
    out = []
    i, n = 0, len(b)
    while i < n:
        c = b[i]
        if c < 0x80:
            out.append(chr(c))
            i += 1
        elif (c & 0xE0) == 0xC0 and i + 1 < n:
            out.append(chr(((c & 0x1F) << 6) | (b[i + 1] & 0x3F)))
            i += 2
        elif (c & 0xF0) == 0xE0 and i + 2 < n:
            out.append(chr(((c & 0x0F) << 12) | ((b[i + 1] & 0x3F) << 6)
                           | (b[i + 2] & 0x3F)))
            i += 3
        else:
            out.append("\ufffd")
            i += 1
    s = "".join(out)
    if any("\ud800" <= ch <= "\udfff" for ch in s):
        combined = []
        j = 0
        while j < len(s):
            ch = s[j]
            if ("\ud800" <= ch <= "\udbff" and j + 1 < len(s)
                    and "\udc00" <= s[j + 1] <= "\udfff"):
                combined.append(chr(0x10000 + ((ord(ch) - 0xD800) << 10)
                                    + (ord(s[j + 1]) - 0xDC00)))
                j += 2
            else:
                combined.append(ch)
                j += 1
        s = "".join(combined)
    return s


class _CP:
    """Constant pool. Raw Utf8 indexes are captured during the parse loop and
    resolved to strings right after it (Utf8 entries may appear anywhere in
    the pool). Index 0 is unused; long/double occupy two slots."""

    __slots__ = ("tags", "utf8", "classes", "nat", "refs", "consts",
                 "strings", "handles", "method_types", "indy")

    def __init__(self):
        self.tags = [0]
        self.utf8 = {}
        self.classes = {}       # idx -> utf8 idx (class name)
        self.nat = {}           # idx -> (name utf8 idx, desc utf8 idx)
        self.refs = {}          # idx -> (tag, class idx, nat idx)
        self.consts = {}        # idx -> ("int"/"float"/"long"/"double", value)
        self.strings = {}       # idx -> utf8 idx
        self.handles = {}       # idx -> (kind, ref idx)
        self.method_types = {}  # idx -> desc utf8 idx
        self.indy = {}          # idx -> (bsm idx, name utf8, desc utf8)

    def resolve(self):
        self.utf8 = {i: decode_mutf8(v) if isinstance(v, bytes) else v
                     for i, v in self.utf8.items()}
        self.classes = {i: self.utf8.get(u, "?") for i, u in self.classes.items()}
        self.nat = {i: (self.utf8.get(n, "?"), self.utf8.get(d, "?"))
                    for i, (n, d) in self.nat.items()}
        self.strings = {i: self.utf8.get(u, "") for i, u in self.strings.items()}
        self.method_types = {i: self.utf8.get(u, "?")
                             for i, u in self.method_types.items()}
        self.indy = {i: (bsm, self.utf8.get(n, "?"), self.utf8.get(d, "?"))
                     for i, (bsm, nat_idx) in self.indy.items()
                     for n, d in [self.nat.get(nat_idx, ("?", "?"))]}

    def class_at(self, idx):
        try:
            return self.classes[idx]
        except KeyError:
            raise ClassFileError("bad Class constant index %d" % idx)

    def ref_at(self, idx):
        """Resolve Fieldref/Methodref/InterfaceMethodref -> (tag, owner, name,
        descriptor)."""
        try:
            tag, cls_idx, nat_idx = self.refs[idx]
            owner = self.class_at(cls_idx)
            name, desc = self.nat[nat_idx]
            return tag, owner, name, desc
        except KeyError:
            raise ClassFileError("bad member-ref constant index %d" % idx)

    def handle_target(self, idx):
        kind, ref_idx = self.handles[idx]
        _, owner, name, desc = self.ref_at(ref_idx)
        return kind, owner, name, desc


def _skip_element_value(data, pos):
    tag = data[pos]
    pos += 1
    if tag in (66, 67, 68, 70, 73, 74, 83, 90, 115, 99):  # B C D F I J S Z s c
        return pos + 2
    if tag == 101:  # e enum
        return pos + 4
    if tag == 64:  # @ nested annotation
        return _read_annotation(data, pos)[1]
    if tag == 91:  # [ array
        n = struct.unpack_from(">H", data, pos)[0]
        pos += 2
        for _ in range(n):
            pos = _skip_element_value(data, pos)
        return pos
    raise ClassFileError("bad annotation element tag %r" % tag)


def _read_annotation(data, pos):
    """Returns (type_utf8_idx, new_pos); element values are skipped."""
    type_idx = struct.unpack_from(">H", data, pos)[0]
    pos += 2
    n_pairs = struct.unpack_from(">H", data, pos)[0]
    pos += 2
    for _ in range(n_pairs):
        pos += 2  # element name utf8 idx
        pos = _skip_element_value(data, pos)
    return type_idx, pos


def _parse_annotations(data, pos, target, cp, mixin_sink=None):
    """Parse an annotation table. `mixin_sink`, when provided, receives
    {"type": ..., "values": {...}} for every annotation whose type is a
    Mixin annotation — the only values we capture (element-value parsing
    for all annotations would measurably slow the 25k-class builds)."""
    n = struct.unpack_from(">H", data, pos)[0]
    pos += 2
    for _ in range(n):
        type_idx = struct.unpack_from(">H", data, pos)[0]
        anno_type = cp.utf8.get(type_idx, "?")
        n_pairs = struct.unpack_from(">H", data, pos + 2)[0]
        q = pos + 4
        want_values = (mixin_sink is not None
                       and "org/spongepowered/asm/mixin/Mixin" in anno_type)
        values = {} if want_values else None
        for _p in range(n_pairs):
            name_idx = struct.unpack_from(">H", data, q)[0]
            q += 2
            if want_values:
                val, q = _read_element_value(data, q, cp)
                values[cp.utf8.get(name_idx, "?")] = val
            else:
                q = _skip_element_value(data, q)
        target.append(anno_type)
        if want_values:
            mixin_sink.append({"type": anno_type, "values": values})
        pos = q
    return pos


def _read_element_value(data, pos, cp):
    """JVM element_value -> (python value, new_pos). Nested annotations
    return None (skipped)."""
    tag = data[pos]
    pos += 1
    if tag in b"BCIJSZ":
        idx = struct.unpack_from(">H", data, pos)[0]
        pos += 2
        return cp.consts.get(idx, (None, None))[1], pos
    if tag in b"JDF":
        idx = struct.unpack_from(">H", data, pos)[0]
        pos += 2
        return cp.consts.get(idx, (None, None))[1], pos
    if tag == ord("s"):
        idx = struct.unpack_from(">H", data, pos)[0]
        pos += 2
        return cp.utf8.get(idx, ""), pos
    if tag == ord("c"):
        idx = struct.unpack_from(">H", data, pos)[0]
        pos += 2
        desc = cp.utf8.get(idx, "?")
        if desc.startswith("L") and desc.endswith(";"):
            desc = desc[1:-1]
        return desc, pos
    if tag == ord("e"):
        t_idx, c_idx = struct.unpack_from(">HH", data, pos)
        pos += 4
        return ("%s.%s" % (cp.utf8.get(t_idx, "?"),
                           cp.utf8.get(c_idx, "?")), pos)
    if tag == ord("@"):
        _t, pos = _read_annotation(data, pos)
        return None, pos
    if tag == ord("["):
        n = struct.unpack_from(">H", data, pos)[0]
        pos += 2
        out = []
        for _i in range(n):
            val, pos = _read_element_value(data, pos, cp)
            out.append(val)
        return out, pos
    raise ClassFileError("bad annotation element tag %r" % tag)


class FieldDecl:
    __slots__ = ("name", "descriptor", "access", "signature", "annotations",
                 "constant")

    def __init__(self, name, descriptor, access):
        self.name = name
        self.descriptor = descriptor
        self.access = access
        self.signature = None
        self.annotations = []
        self.constant = None  # for static finals: (kind, value-as-str)


class MethodDecl:
    __slots__ = ("name", "descriptor", "access", "signature", "exceptions",
                 "annotations", "max_stack", "max_locals", "code_len",
                 "code_sha256", "invocations", "field_ops", "type_refs",
                 "strings", "nums", "handlers")

    def __init__(self, name, descriptor, access):
        self.name = name
        self.descriptor = descriptor
        self.access = access
        self.signature = None
        self.exceptions = []
        self.annotations = []
        self.max_stack = None
        self.max_locals = None
        self.code_len = 0
        self.code_sha256 = None
        self.invocations = []   # (offset, opname, owner|None, name, desc, itf)
        self.field_ops = []     # (offset, opname, owner, name, desc, get, static)
        self.type_refs = []     # (offset, opname, class_internal_or_desc)
        self.strings = []       # (offset, value)
        self.nums = []          # (offset, kind, value-as-str)
        self.handlers = []      # (start, end, handler, catch_class|None)


class ClassFile:
    __slots__ = ("minor", "major", "access", "this_class", "super_class",
                 "interfaces", "source_file", "signature", "fields",
                 "methods", "annotations", "inner_classes", "bootstrap_count",
                 "sha256", "mixin")


def _record_ldc(cp, idx, start, m):
    tag = cp.tags[idx] if idx < len(cp.tags) else 0
    if tag == ops.CONSTANT_String:
        m.strings.append((start, cp.strings.get(idx, "")))
    elif tag == ops.CONSTANT_Integer:
        m.nums.append((start, "int", str(cp.consts[idx][1])))
    elif tag == ops.CONSTANT_Float:
        m.nums.append((start, "float", repr(cp.consts[idx][1])))
    elif tag == ops.CONSTANT_Class:
        m.type_refs.append((start, "ldc_class", cp.class_at(idx)))
    elif tag == ops.CONSTANT_MethodType:
        m.type_refs.append((start, "ldc_methodtype",
                            cp.method_types.get(idx, "?")))
    elif tag == ops.CONSTANT_MethodHandle:
        try:
            _, owner, name, desc = cp.handle_target(idx)
            m.invocations.append((start, "ldc_methodhandle", owner, name,
                                  desc, False))
        except ClassFileError:
            pass


def _walk_code(cp, m, code):
    """Decode one method's bytecode, filling its fact lists. Offsets are
    relative to the start of the code array."""
    invocations = m.invocations
    field_ops = m.field_ops
    type_refs = m.type_refs
    strings = m.strings
    nums = m.nums
    unpack_b = struct.unpack_from
    n = len(code)
    pos = 0
    while pos < n:
        start = pos
        op = code[pos]
        pos += 1
        try:
            mnem, kind, semantic = ops.OPCODES[op]
        except KeyError:
            raise ClassFileError("unknown opcode 0x%02x at offset %d"
                                 % (op, start))
        if kind == "":
            continue
        if kind == "b":
            val = unpack_b(">b", code, pos)[0]
            pos += 1
            if semantic == "num":
                nums.append((start, "int", str(val)))
        elif kind == "s":
            val = unpack_b(">h", code, pos)[0]
            pos += 2
            if semantic == "num":
                nums.append((start, "int", str(val)))
        elif kind == "cp1":
            _record_ldc(cp, code[pos], start, m)
            pos += 1
        elif kind == "cp2":
            idx = unpack_b(">H", code, pos)[0]
            pos += 2
            if semantic == "ldc":
                _record_ldc(cp, idx, start, m)
            elif semantic == "ldc2":
                kindv, val = cp.consts.get(idx, ("?", "?"))
                nums.append((start, kindv, str(val)))
            elif semantic == "field":
                _, owner, name, desc = cp.ref_at(idx)
                field_ops.append((start, mnem, owner, name, desc,
                                  mnem.startswith("get"),
                                  mnem.endswith("static")))
            elif semantic == "invoke":
                tag, owner, name, desc = cp.ref_at(idx)
                invocations.append((start, mnem, owner, name, desc,
                                    tag == ops.CONSTANT_InterfaceMethodref))
            elif semantic == "type":
                type_refs.append((start, mnem, cp.class_at(idx)))
        elif kind in ("local", "iinc"):
            pos += 2 if kind == "iinc" else 1
        elif kind == "br2":
            pos += 2
        elif kind == "br4":
            pos += 4
        elif kind == "newarray":
            pos += 1
        elif kind == "multianewarray":
            idx = unpack_b(">H", code, pos)[0]
            pos += 3
            type_refs.append((start, mnem, cp.class_at(idx)))
        elif kind == "invokeinterface":
            idx = unpack_b(">H", code, pos)[0]
            pos += 4  # u2 index + u1 count + u1 zero
            tag, owner, name, desc = cp.ref_at(idx)
            invocations.append((start, mnem, owner, name, desc, True))
        elif kind == "invokedynamic":
            idx = unpack_b(">H", code, pos)[0]
            pos += 4
            entry = cp.indy.get(idx)
            if entry is not None:
                invocations.append((start, mnem, None, entry[1], entry[2],
                                    False))
        elif kind == "tableswitch":
            pos += (4 - (pos % 4)) % 4
            pos += 4  # default offset
            low, high = unpack_b(">ii", code, pos)
            pos += 8 + 4 * (high - low + 1)  # low, high, match offsets
        elif kind == "lookupswitch":
            pos += (4 - (pos % 4)) % 4
            pos += 4  # default offset
            n_pairs = unpack_b(">i", code, pos)[0]
            pos += 4 + 8 * n_pairs  # npairs, (match, offset) pairs
        elif kind == "wide":
            if code[pos] == 0x84:  # iinc: u2 index + s2 const (past sub-byte)
                pos += 5
            else:  # load/store/ret: u2 local index
                pos += 3
        else:
            raise ClassFileError("unhandled operand kind %r" % kind)


def _read_member(cp, data, pos, is_method):
    if pos + 6 > len(data):
        raise ClassFileError("truncated member header")
    access, name_idx, desc_idx = struct.unpack_from(">HHH", data, pos)
    pos += 6
    if is_method:
        decl = MethodDecl(cp.utf8.get(name_idx, "?"),
                          cp.utf8.get(desc_idx, "?"), access)
    else:
        decl = FieldDecl(cp.utf8.get(name_idx, "?"),
                         cp.utf8.get(desc_idx, "?"), access)
    attr_count = struct.unpack_from(">H", data, pos)[0]
    pos += 2
    for _ in range(attr_count):
        if pos + 6 > len(data):
            raise ClassFileError("truncated attribute header")
        name_idx, length = struct.unpack_from(">HI", data, pos)
        pos += 6
        if pos + length > len(data):
            raise ClassFileError("truncated attribute body")
        end = pos + length
        name = cp.utf8.get(name_idx, "?")
        if is_method and name == "Code":
            m = decl
            m.max_stack, m.max_locals, code_len = struct.unpack_from(
                ">HHI", data, pos)
            code = data[pos + 8:pos + 8 + code_len]
            m.code_len = code_len
            m.code_sha256 = hashlib.sha256(code).hexdigest()
            _walk_code(cp, m, code)
            q = pos + 8 + code_len
            ex_count = struct.unpack_from(">H", data, q)[0]
            q += 2
            for _ in range(ex_count):
                s, e, h, catch_idx = struct.unpack_from(">HHHH", data, q)
                q += 8
                catch = cp.class_at(catch_idx) if catch_idx else None
                m.handlers.append((s, e, h, catch))
                if catch is not None:
                    m.type_refs.append((h, "catch", catch))
            code_attr_count = struct.unpack_from(">H", data, q)[0]
            q += 2
            for _ in range(code_attr_count):
                an_idx, alen = struct.unpack_from(">HI", data, q)
                q += 6 + alen  # LineNumberTable / StackMapTable / ... skipped
        elif not is_method and name == "ConstantValue":
            cv_idx = struct.unpack_from(">H", data, pos)[0]
            if cv_idx in cp.strings:
                decl.constant = ("string", cp.strings[cv_idx])
            elif cv_idx in cp.consts:
                kindv, val = cp.consts[cv_idx]
                decl.constant = (kindv, str(val))
        elif name == "Signature":
            decl.signature = cp.utf8.get(
                struct.unpack_from(">H", data, pos)[0], None)
        elif name == "Exceptions":
            n = struct.unpack_from(">H", data, pos)[0]
            q = pos + 2
            decl.exceptions = [cp.utf8.get(u, "?") for u in
                               struct.unpack_from(">%dH" % n, data, q)] \
                if n else []
        elif name in (_ATTR_RVA, _ATTR_RIVA):
            _parse_annotations(data, pos, decl.annotations, cp)
        pos = end
    return decl, pos


# str, not bytes: attribute names are compared against decoded CP strings
# (a bytes-vs-str mismatch here silently skipped ALL annotation parsing)
_ATTR_RVA = "RuntimeVisibleAnnotations"
_ATTR_RIVA = "RuntimeInvisibleAnnotations"


def parse_class(data):
    """Parse one .class file into a ClassFile. Raises ClassFileError on any
    structural problem."""
    if len(data) < 10 or struct.unpack_from(">I", data, 0)[0] != _MAGIC:
        raise ClassFileError("not a class file (bad magic)")
    cf = ClassFile()
    cf.minor, cf.major = struct.unpack_from(">HH", data, 4)
    cf.sha256 = hashlib.sha256(data).hexdigest()

    cp = _CP()
    pos = 8
    cp_count = struct.unpack_from(">H", data, pos)[0]
    pos += 2
    idx = 1
    while idx < cp_count:
        if pos >= len(data):
            raise ClassFileError("truncated constant pool (index %d)" % idx)
        tag = data[pos]
        pos += 1
        cp.tags.append(tag)
        if tag == ops.CONSTANT_Utf8:
            length = struct.unpack_from(">H", data, pos)[0]
            pos += 2
            cp.utf8[idx] = data[pos:pos + length]  # decoded in resolve()
            pos += length
        elif tag in (ops.CONSTANT_Integer, ops.CONSTANT_Float):
            raw = data[pos:pos + 4]
            if tag == ops.CONSTANT_Integer:
                cp.consts[idx] = ("int", struct.unpack(">i", raw)[0])
            else:
                cp.consts[idx] = ("float", struct.unpack(">f", raw)[0])
            pos += 4
        elif tag in (ops.CONSTANT_Long, ops.CONSTANT_Double):
            raw = data[pos:pos + 8]
            if tag == ops.CONSTANT_Long:
                cp.consts[idx] = ("long", struct.unpack(">q", raw)[0])
            else:
                cp.consts[idx] = ("double", struct.unpack(">d", raw)[0])
            pos += 8
            cp.tags.append(0)  # phantom second slot
            idx += 1
        elif tag == ops.CONSTANT_Class:
            cp.classes[idx] = struct.unpack_from(">H", data, pos)[0]
            pos += 2
        elif tag == ops.CONSTANT_String:
            cp.strings[idx] = struct.unpack_from(">H", data, pos)[0]
            pos += 2
        elif tag in (ops.CONSTANT_Fieldref, ops.CONSTANT_Methodref,
                     ops.CONSTANT_InterfaceMethodref):
            cp.refs[idx] = (tag,) + struct.unpack_from(">HH", data, pos)
            pos += 4
        elif tag == ops.CONSTANT_NameAndType:
            cp.nat[idx] = struct.unpack_from(">HH", data, pos)
            pos += 4
        elif tag == ops.CONSTANT_MethodHandle:
            cp.handles[idx] = struct.unpack_from(">BH", data, pos)
            pos += 3
        elif tag == ops.CONSTANT_MethodType:
            cp.method_types[idx] = struct.unpack_from(">H", data, pos)[0]
            pos += 2
        elif tag in (ops.CONSTANT_Dynamic, ops.CONSTANT_InvokeDynamic):
            bsm, nat_idx = struct.unpack_from(">HH", data, pos)
            cp.indy[idx] = (bsm, nat_idx)
            pos += 4
        elif tag in (ops.CONSTANT_Module, ops.CONSTANT_Package):
            pos += 2
        else:
            raise ClassFileError("unknown constant-pool tag %d at index %d"
                                 % (tag, idx))
        idx += 1
    cp.resolve()

    cf.access, this_idx, super_idx = struct.unpack_from(">HHH", data, pos)
    pos += 6
    cf.this_class = cp.class_at(this_idx)
    cf.super_class = cp.class_at(super_idx) if super_idx else None
    ifc_count = struct.unpack_from(">H", data, pos)[0]
    pos += 2
    cf.interfaces = []
    for _ in range(ifc_count):
        cf.interfaces.append(cp.class_at(struct.unpack_from(">H", data, pos)[0]))
        pos += 2

    cf.fields = []
    f_count = struct.unpack_from(">H", data, pos)[0]
    pos += 2
    for _ in range(f_count):
        decl, pos = _read_member(cp, data, pos, is_method=False)
        cf.fields.append(decl)

    cf.methods = []
    m_count = struct.unpack_from(">H", data, pos)[0]
    pos += 2
    for _ in range(m_count):
        decl, pos = _read_member(cp, data, pos, is_method=True)
        if decl.code_len and decl.code_sha256 is None:
            raise ClassFileError("method %s.%s has no Code body decoded"
                                 % (cf.this_class, decl.name))
        cf.methods.append(decl)

    cf.annotations = []
    cf.mixin = []
    cf.inner_classes = []
    cf.source_file = None
    cf.signature = None
    cf.bootstrap_count = 0
    attr_count = struct.unpack_from(">H", data, pos)[0]
    pos += 2
    for _ in range(attr_count):
        if pos + 6 > len(data):
            raise ClassFileError("truncated attribute header")
        name_idx, length = struct.unpack_from(">HI", data, pos)
        pos += 6
        if pos + length > len(data):
            raise ClassFileError("truncated attribute body")
        end = pos + length
        name = cp.utf8.get(name_idx, "?")
        if name == "SourceFile":
            cf.source_file = cp.utf8.get(
                struct.unpack_from(">H", data, pos)[0], None)
        elif name == "Signature":
            cf.signature = cp.utf8.get(
                struct.unpack_from(">H", data, pos)[0], None)
        elif name == "InnerClasses":
            n = struct.unpack_from(">H", data, pos)[0]
            q = pos + 2
            for _ in range(n):
                inner, outer, nm, acc = struct.unpack_from(">HHHH", data, q)
                q += 8
                cf.inner_classes.append((cp.class_at(inner),
                                         cp.class_at(outer) if outer else None,
                                         cp.utf8.get(nm, "?"), acc))
        elif name in (_ATTR_RVA, _ATTR_RIVA):
            _parse_annotations(data, pos, cf.annotations, cp,
                               mixin_sink=cf.mixin)
        elif name == "BootstrapMethods":
            cf.bootstrap_count = struct.unpack_from(">H", data, pos)[0]
        pos = end
    return cf
