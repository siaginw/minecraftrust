"""Display helpers: descriptor pretty-printing, access flags, method rows."""

_ACCESS = [(0x0001, "public"), (0x0002, "private"), (0x0004, "protected"),
           (0x0008, "static"), (0x0010, "final"), (0x0020, "super"),
           (0x0200, "interface"), (0x0400, "abstract"), (0x0800, "synthetic"),
           (0x1000, "annotation"), (0x2000, "enum"), (0x0040, "volatile"),
           (0x0080, "transient"), (0x0040, "bridge"), (0x0080, "varargs"),
           (0x0100, "native")]


def pretty_access(flags):
    names = [n for bit, n in _ACCESS if flags & bit]
    # dedupe volatile/bridge (0x40) and transient/varargs (0x80) collisions
    seen = []
    for n in names:
        if n not in seen:
            seen.append(n)
    return " ".join(seen) if seen else "package-private"


def parse_method_descriptor(desc):
    """'(Lfoo/Bar;I)V' -> (['foo/Bar', 'I'], 'V') or None if malformed."""
    if not desc or not desc.startswith("("):
        return None
    types = []
    i = 1
    while i < len(desc) and desc[i] != ")":
        start = i
        while i < len(desc) and desc[i] == "[":
            i += 1
        if i < len(desc) and desc[i] == "L":
            i = desc.find(";", i)
            if i < 0:
                return None
        i += 1
        types.append(desc[start:i])
    if i >= len(desc):
        return None
    return types, desc[i + 1:]


def simple_name(internal):
    return internal.rsplit("/", 1)[-1]


_PRIMITIVES = {"V": "void", "Z": "boolean", "B": "byte", "S": "short",
               "C": "char", "I": "int", "J": "long", "F": "float",
               "D": "double"}


def pretty_type(t):
    dims = 0
    while t.startswith("["):
        dims += 1
        t = t[1:]
    if t.startswith("L") and t.endswith(";"):
        t = t[1:-1]
    if t in _PRIMITIVES:
        base = _PRIMITIVES[t]
    else:
        base = simple_name(t)
    return base + "[]" * dims


def pretty_desc(desc):
    """Full pretty print: '(EnumSkyBlock, BlockPos) -> boolean'."""
    parsed = parse_method_descriptor(desc)
    if parsed is None:
        return desc
    params, ret = parsed
    pt = ", ".join(pretty_type(p) for p in params)
    rt = pretty_type(ret)
    return "(%s) -> %s" % (pt, rt)
