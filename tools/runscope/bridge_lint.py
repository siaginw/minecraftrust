"""Static consistency lint for the RustCraft JNI bridge + live hooks.

Catches, BEFORE a 5-8 minute campaign boot, the bug classes that actually
cost boots during the block-light authority work (diag4..13, dev4..15):

1. jni-pairing            Java `native` declarations must have an exactly
                          named Rust export (`Java_<package>_<class>_<m>`),
                          and vice versa. A missing pair is an
                          UnsatisfiedLinkError at call time — or worse, a
                          silently swallowed one (LightBatchCtx/Region*
                          Ctx `closeRaw` vs Rust `..._close`: every kernel
                          context leaked while `catch (Throwable ignore)`
                          hid it). JNI underscore mangling is checked.
2. bytebuffer-endianness  A ByteBuffer read/written with typed accessors
                          (putInt/getLong/...) must be explicitly
                          `.order(...)`-ed within its declaring method.
                          Java buffers are BIG-endian by default; the Rust
                          kernel writes/reads native little-endian — the
                          mismatch byte-swapped every coordinate once
                          (diag10/11: 43k bogus mismatches) and silently
                          swaps every snapshot read (RegionWriteCtx
                          floors()/stats() read "8 LE u64s" as BE).
3. cross-loader-forname   Class.forName with literal net.minecraft /
                          me.jellysquid / notch names in LIVE runtime
                          classes — runtime classes keep SRG names and live
                          on the LaunchClassLoader, so these binds fail or
                          hit the wrong loader (276,066 hook errors in
                          diag6). Derive surfaces from live objects.
                          Offline harnesses are exempt.
4. ordinal-gate           Ordinal EQUALITY dispatch on EnumSkyBlock in hook
                          files (SKY=0, BLOCK=1 — the gate was written
                          inverted twice; `< 0` validity sentinels are fine
                          and not flagged).

Read-only. Stdlib only. Sources are comment/string-stripped before
matching, so prose like "this class name" cannot false-positive.

Exit code: 1 if any finding at or above --min-severity is fatal, else 0.
"""

import os
import re
import sys

SEVERITIES = ("fatal", "warning", "info")

# A `RUNSCOPE-JUSTIFIED: <reason>` comment within LOOKBACK lines above a
# finding suppresses it as a justified exception (the finding is still
# reported as `justified` so the trail stays visible). Fatal findings
# (missing JNI exports) are NOT suppressible — a missing export cannot be
# justified away, only fixed.
JUSTIFIED_MARKER = "RUNSCOPE-JUSTIFIED:"
JUSTIFIED_LOOKBACK = 8

# retro fix 4: Tracker/Observer classes are live seam participants too —
# ChunkMutationTracker's static-init Class.forName("...Chunk") (system
# classloader, silently null) was invisible to the forName rule because
# "Tracker" didn't match (zsa12).
_LIVE_NAME_RE = re.compile(
    r"(Hook|Bridge|Transformer|Tweaker|Ctx|CoreMod|LiveSupport"
    r"|Tracker|Observer)\.java$")
_OFFLINE_NAME_RE = re.compile(
    r"(^M[0-9A-Z]|Test|SelfTest|Benchmark|Harness|Probe|Gate|Fixture|"
    r"Driver|Oracle|Draft|Experiment)")

_NATIVE_RE = re.compile(
    r"\b(?:public|protected|private)?\s*(?:static\s+)?native\s+"
    r"[\w\[\]<>.]+?\s+(\w+)\s*\(")
_CLASS_RE = re.compile(
    r"\b(?:public\s+|final\s+|abstract\s+)+class\s+(\w+)"
    r"|\bclass\s+(\w+)(?:\s+extends|\s+implements|\s*\{)")
_PACKAGE_RE = re.compile(r"^\s*package\s+([\w.]+)\s*;", re.M)
_EXPORT_RE = re.compile(r"\b(Java_[A-Za-z0-9_]+)\b")
_ALLOC_RE = re.compile(r"ByteBuffer\s*\.\s*(?:allocateDirect|allocate)\s*\(")
_ORDER_RE = re.compile(r"\.\s*order\s*\(")
_FORNAME_RE = re.compile(r"Class\s*\.\s*forName\s*\(\s*\"([^\"]+)\"")
_FORNAME_CODE_RE = re.compile(r"Class\s*\.\s*forName\s*\(")
_MC_NAME_RE = re.compile(
    r"^(?:net\.minecraft\.|me\.jellysquid\.|org\.spongepowered\.)"
    r"|^[a-z]{1,3}(?:\$[0-9]+)?(?:\.[a-z]{1,3})+$")
_ORDINAL_DISPATCH_RE = re.compile(
    r"\b(?:ordinal|\w*[Oo]rdinal)\s*(?:\(\s*\))?\s*(==|!=)\s*(\d+)\b")


def strip_comments_only(src):
    """Blank comments but KEEP string/char literals - the right text for
    seam invariants, whose anchors are often string-constant markers
    (lastTransformStatus = "CHECK_LIGHT_AUTHORITY_HOOKED")."""
    out = list(src)
    i, n = 0, len(src)
    NORMAL, LINE_COMMENT, BLOCK_COMMENT = range(3)
    state = NORMAL
    while i < n:
        c = src[i]
        nxt = src[i + 1] if i + 1 < n else ""
        if state == NORMAL:
            if c == "/" and nxt == "/":
                state = LINE_COMMENT
                out[i] = out[i + 1] = " "
                i += 2
                continue
            if c == "/" and nxt == "*":
                state = BLOCK_COMMENT
                out[i] = out[i + 1] = " "
                i += 2
                continue
            if c == '"':
                skipto = src.find('"', i + 1)
                if skipto < 0:
                    skipto = n - 1
                i = skipto + 1
                continue
            if c == "'":
                skipto = src.find("'", i + 1)
                if skipto < 0:
                    skipto = n - 1
                i = skipto + 1
                continue
            i += 1
        elif state == LINE_COMMENT:
            if c == chr(10):
                state = NORMAL
            else:
                out[i] = " "
            i += 1
        else:
            if c == "*" and nxt == "/":
                out[i] = out[i + 1] = " "
                state = NORMAL
                i += 2
                continue
            if c != chr(10):
                out[i] = " "
            i += 1
    return "".join(out)


def strip_java(src):
    """Blank out comments and string/char literals, preserving offsets and
    line structure so regex hits keep their line numbers."""
    out = list(src)
    i, n = 0, len(src)
    NORMAL, LINE_COMMENT, BLOCK_COMMENT, STRING, CHAR = range(5)
    state = NORMAL
    while i < n:
        c = src[i]
        nxt = src[i + 1] if i + 1 < n else ""
        if state == NORMAL:
            if c == "/" and nxt == "/":
                state = LINE_COMMENT
                out[i] = out[i + 1] = " "
                i += 2
                continue
            if c == "/" and nxt == "*":
                state = BLOCK_COMMENT
                out[i] = out[i + 1] = " "
                i += 2
                continue
            if c == '"':
                state = STRING
                out[i] = " "
                i += 1
                continue
            if c == "'":
                state = CHAR
                out[i] = " "
                i += 1
                continue
            i += 1
        elif state == LINE_COMMENT:
            if c == "\n":
                state = NORMAL
            else:
                out[i] = " "
            i += 1
        elif state == BLOCK_COMMENT:
            if c == "*" and nxt == "/":
                out[i] = out[i + 1] = " "
                state = NORMAL
                i += 2
                continue
            if c != "\n":
                out[i] = " "
            i += 1
        else:  # STRING or CHAR
            if c == "\\":
                out[i] = " "
                if i + 1 < n:
                    out[i + 1] = " "
                i += 2
                continue
            if (state == STRING and c == '"') or (state == CHAR
                                                  and c == "'"):
                state = NORMAL
            out[i] = " "
            i += 1
    return "".join(out)


class Finding:
    __slots__ = ("check", "severity", "file", "line", "message",
                 "justification")

    def __init__(self, check, severity, file, line, message,
                 justification=None):
        self.check = check
        self.severity = severity
        self.file = file
        self.line = line
        self.message = message
        self.justification = justification

    @property
    def justified(self):
        return self.justification is not None

    def as_dict(self, root):
        return {"check": self.check, "severity": self.severity,
                "file": _rel(self.file, root), "line": self.line,
                "message": self.message,
                "justified": self.justified,
                "justification": self.justification}


def _find_justification(raw_lines, lineno):
    """Look up to JUSTIFIED_LOOKBACK lines above lineno (1-based) for a
    RUNSCOPE-JUSTIFIED comment; return its reason text or None."""
    start = max(0, lineno - 1 - JUSTIFIED_LOOKBACK)
    reason = None
    for i in range(start, lineno - 1):
        idx = raw_lines[i].find(JUSTIFIED_MARKER)
        if idx >= 0:
            reason = raw_lines[i][idx + len(JUSTIFIED_MARKER):].strip()
            # collect the continuation of the // comment block (up to 3)
            j = i + 1
            while (j < lineno - 1 and len(reason) < 300
                   and raw_lines[j].strip().startswith("//")):
                reason += " " + raw_lines[j].strip().lstrip("/").strip()
                j += 1
            break
    return reason


def _rel(path, root):
    try:
        return os.path.relpath(path, root)
    except ValueError:
        return path


def _mangled(package, cls, method):
    base = "Java_%s_%s_%s" % (package.replace(".", "_"), cls, method)
    cands = {base}
    if "_" in method:  # JNI mangling: '_' -> '_1'
        cands.add("Java_%s_%s_%s" % (package.replace(".", "_"), cls,
                                     method.replace("_", "_1")))
    return cands


def _iter_java(roots):
    for root in roots:
        for dirpath, _dirs, names in os.walk(root):
            for n in sorted(names):
                if n.endswith(".java"):
                    yield os.path.join(dirpath, n)


def _iter_rust(roots):
    for root in roots:
        for dirpath, _dirs, names in os.walk(root):
            for n in sorted(names):
                if n.endswith(".rs"):
                    yield os.path.join(dirpath, n)


def _java_units(java_roots):
    """Yield (path, stripped_src, package, class, is_offline)."""
    for path in _iter_java(java_roots):
        with open(path, "r", encoding="utf-8", errors="replace") as fh:
            raw = fh.read()
        src = strip_java(raw)
        pkg = _PACKAGE_RE.search(src)
        package = pkg.group(1) if pkg else ""
        cls = None
        for m in _CLASS_RE.finditer(src):
            cls = m.group(1) or m.group(2)
        if cls is None:
            cls = os.path.basename(path)[:-5]
        offline = bool(_OFFLINE_NAME_RE.search(os.path.basename(path)))
        yield path, src, package, cls, offline


def check_jni_pairing(java_roots, rust_roots):
    findings = []
    exports = set()
    for path in _iter_rust(rust_roots):
        with open(path, "r", encoding="utf-8", errors="replace") as fh:
            for i, line in enumerate(fh, 1):
                for m in _EXPORT_RE.finditer(line):
                    exports.add(m.group(1))
    used = set()
    for path, src, package, cls, offline in _java_units(java_roots):
        severity = "warning" if offline else "fatal"
        for m in _NATIVE_RE.finditer(src):
            name = m.group(1)
            line = src.count("\n", 0, m.start()) + 1
            cands = _mangled(package, cls, name)
            used |= cands
            if not (cands & exports):
                findings.append(Finding(
                    "jni-pairing", severity, path, line,
                    "native '%s.%s.%s' has no Rust export (%s) — "
                    "UnsatisfiedLinkError at call time; swallowed catch "
                    "blocks turn that into a silent leak/failure"
                    % (package, cls, name, sorted(cands)[0])))
    for path in _iter_rust(rust_roots):
        with open(path, "r", encoding="utf-8", errors="replace") as fh:
            for i, line in enumerate(fh, 1):
                for m in _EXPORT_RE.finditer(line):
                    if m.group(1) not in used:
                        findings.append(Finding(
                            "jni-pairing", "info", path, i,
                            "Rust export %s has no matching Java native — "
                            "dead surface or the Java side renamed a method "
                            "(the WorldLightBridge/PhosphorLightBridge trap)"
                            % m.group(1)))
    return findings


def check_bytebuffer_order(java_roots):
    """Typed access on a direct ByteBuffer requires .order(...) in the same
    method. Variable use is scoped by method indentation."""
    findings = []
    for path, src, _pkg, _cls, offline in _java_units(java_roots):
        if offline:
            continue
        lines = src.split("\n")
        for i, line in enumerate(lines):
            if not _ALLOC_RE.search(line):
                continue
            eq = line.find("=")
            var = None
            if eq > 0:
                parts = line[:eq].strip().split()
                if parts:
                    var = parts[-1]
            if var is None:
                continue
            indent = len(line) - len(line.lstrip())
            if _ORDER_RE.search("\n".join(lines[i:i + 4])):
                continue
            use_re = re.compile(
                r"\b%s\s*\.\s*(?:put|get)(?:Int|Long|Short|Double|Float|"
                r"Char)\s*\(" % re.escape(var))
            member_re = re.compile(
                r"^\s*(?:public|private|protected|static)\s")
            scoped_use = False
            for j in range(i + 1, min(i + 400, len(lines))):
                other = lines[j]
                stripped = other.strip()
                if not stripped:
                    continue
                o_indent = len(other) - len(other.lstrip())
                # next member/method declaration at or above the alloc's
                # indent ends the enclosing method body; block openers and
                # closers (`try {`, `} catch ... {`, `}`) do not
                if (o_indent <= indent and member_re.match(other)):
                    break
                if use_re.search(other):
                    scoped_use = True
                    break
            if scoped_use:
                findings.append(Finding(
                    "bytebuffer-endianness", "warning", path, i + 1,
                    "ByteBuffer '%s' gets typed put/get but no .order(...) "
                    "in its method — Java defaults to BIG_ENDIAN, the Rust "
                    "kernel is native LITTLE_ENDIAN (diag10/11: byte-"
                    "swapped coordinates; RegionWriteCtx floors()/stats() "
                    "read LE u64s as BE)" % var))
    return findings


def check_cross_loader_forname(java_roots):
    findings = []
    for path, src, _pkg, _cls, offline in _java_units(java_roots):
        base = os.path.basename(path)
        if offline or not _LIVE_NAME_RE.search(base):
            continue
        raw = open(path, "r", encoding="utf-8", errors="replace").read()
        stripped_lines = src.split("\n")
        for m in _FORNAME_RE.finditer(raw):
            lineno = raw.count("\n", 0, m.start())
            # the string argument lives in a literal (blanked in the
            # stripped source); require the stripped line to still be a
            # forName call so commented-out code cannot fire
            if not _FORNAME_CODE_RE.search(stripped_lines[lineno]):
                continue
            name = m.group(1)
            if _MC_NAME_RE.match(name):
                findings.append(Finding(
                    "cross-loader-forname", "warning", path, lineno + 1,
                    "Class.forName(%r) in a live runtime class — runtime "
                    "classes keep SRG names on the LaunchClassLoader; the "
                    "system/launch split produced 276k hook errors (diag6)."
                    " Derive the reflection surface from live objects."
                    % name))
    return findings


def check_ordinal_gate(java_roots):
    findings = []
    for path, src, _pkg, _cls, offline in _java_units(java_roots):
        base = os.path.basename(path)
        if offline or not ("Hook" in base or "Light" in base
                           or "Transformer" in base):
            continue
        for m in _ORDINAL_DISPATCH_RE.finditer(src):
            op, val = m.group(1), m.group(2)
            if val not in ("0", "1"):  # <0 sentinels are validity checks
                continue
            findings.append(Finding(
                "ordinal-gate", "warning", path,
                src.count("\n", 0, m.start()) + 1,
                "ordinal %s %s dispatch — EnumSkyBlock SKY=0/BLOCK=1 was "
                "keyed inverted twice; gate on the enum constant NAME "
                "(ordinal may log, never decide)" % (op, val)))
    return findings


_CATCH_RE = re.compile(
    r"catch\s*\(\s*([\w.$]+\s+(\w+))\s*\)\s*\{")
_RECORD_RE = re.compile(
    r"record\w*|LAST_ERROR|ERRORS\.incrementAndGet|SURFACE_ERROR|log\.")
_RETURN_LITERAL_RE = re.compile(
    r"return\s+(?:new\s+long\[\]\s*\{\s*0\s*,\s*0\s*,\s*0\s*\}"
    r"|new\s+int\[\]\s*\{\s*0+(?:\s*,\s*0+)*\s*\}"
    r"|-1|0|0L|false|null)\s*;")


_CW_RE = re.compile(
    r"new\s+(?:[\w.]+\.)?ClassWriter\s*\(\s*([^)]{0,120}?)\)")
_CW_FRAME_OVERRIDE_RE = re.compile(r"getCommonSuperClass")


_RUST_CONST_RE = {
    "TABLE_CELL_BYTES": re.compile(
        r"TABLE_CELL_BYTES\s*:\s*usize\s*=\s*(\d+)"),
    "CELL_BYTES": re.compile(
        r"CELL_BYTES\s*:\s*usize\s*=\s*(\d+)"),
    "HEADER_U32": re.compile(
        r"HEADER_U32\s*:\s*usize\s*=\s*(\d+)"),
}
_JAVA_TABLE_STRIDE_RE = re.compile(
    r"allocateDirect\s*\(\s*MAX_STATES\s*\*\s*(\d+)\s*\)")
_JAVA_SID_STRIDE_RE = re.compile(
    r"sid\s*\*\s*(\d+)")
_RUST_SID_STRIDE_RE = re.compile(
    r"sid as usize \* (\d+)|t \+ sid \* (\d+)")


def check_seam_format(java_roots, rust_roots):
    """Cross-language staged-format drift: the light-authority job format
    is written by Java (LightAuthorityHook) and read by Rust
    (light_authority.rs) with only prose between them. The dev-ON-14/16
    incident (opacity 255 colliding with the 0xFF unknown sentinel ->
    every opaque-terrain job rejected) forced a 2-byte -> 3-byte table
    migration ON BOTH SIDES; a half-landed migration is silent
    corruption. This check parses the format constants from both sides
    and flags disagreement."""
    findings = []
    rust = {}
    for path in _iter_rust(rust_roots):
        if not path.endswith("light_authority.rs"):
            continue
        with open(path, "r", encoding="utf-8", errors="replace") as fh:
            rsrc = fh.read()
        for name, rx in _RUST_CONST_RE.items():
            m = rx.search(rsrc)
            if m:
                rust[name] = int(m.group(1))
                findings.append(Finding(
                    "seam-format", "info", path,
                    rsrc.count(chr(10), 0, m.start()) + 1,
                    "rust %s = %s" % (name, m.group(1))))
        sid_rx = _RUST_SID_STRIDE_RE.search(rsrc)
        if sid_rx:
            rust["SID_STRIDE"] = int(sid_rx.group(1)
                                     or sid_rx.group(2))
    if not rust:
        return findings  # no rust format file; nothing to cross-check
    java = {}
    for path, src, _pkg, _cls, offline in _java_units(java_roots):
        if os.path.basename(path) != "LightAuthorityHook.java":
            continue
        m = _JAVA_TABLE_STRIDE_RE.search(src)
        if m:
            java["TABLE_STRIDE"] = int(m.group(1))
        sids = set(_JAVA_SID_STRIDE_RE.findall(src))
        if len(sids) == 1:
            java["SID_STRIDE"] = int(next(iter(sids)))
    if "TABLE_CELL_BYTES" in rust and "TABLE_STRIDE" in java:
        if rust["TABLE_CELL_BYTES"] != java["TABLE_STRIDE"]:
            findings.append(Finding(
                "seam-format", "fatal", path, 1,
                "STAGED-FORMAT DRIFT: Rust TABLE_CELL_BYTES=%s but Java "
                "table stride=%s - the light table is written with one "
                "cell size and read with another (silent corruption / "
                "mass unknown-state rejections; the dev-ON-16 sentinel "
                "collision fix must land on BOTH sides)"
                % (rust["TABLE_CELL_BYTES"], java["TABLE_STRIDE"])))
    elif "TABLE_CELL_BYTES" in rust and "TABLE_STRIDE" not in java:
        findings.append(Finding(
            "seam-format", "info", java_roots[0], 1,
            "rust TABLE_CELL_BYTES=%s found but no Java table stride "
            "anchor (allocateDirect(MAX_STATES * N)) - if the Java side "
            "changed its table encoding, verify manually" %
            rust["TABLE_CELL_BYTES"]))
    return findings


def check_transformer_frames(java_roots):
    """A transformer that injects BRANCH TARGETS must regenerate stack map
    frames: COMPUTE_MAXS-only writers produced the VerifyError class
    (dev-ON-3; the fix pattern is ResolvingClassWriter with COMPUTE_FRAMES
    plus a LaunchClassLoader-aware getCommonSuperClass). COMPUTE_MAXS is
    legitimate for branch-free counter injections — justifiable."""
    findings = []
    for path, src, _pkg, _cls, offline in _java_units(java_roots):
        base = os.path.basename(path)
        if offline or not ("Transformer" in base or "CoreMod" in base):
            continue
        for m in _CW_RE.finditer(src):
            flags = m.group(1)
            if "COMPUTE_FRAMES" in flags:
                continue
            # getCommonSuperClass override nearby does not excuse missing
            # frame computation; still flag (frames, not supers, is the
            # VerifyError vector)
            line = src.count("\n", 0, m.start()) + 1
            findings.append(Finding(
                "transformer-frames", "warning", path, line,
                "ClassWriter without COMPUTE_FRAMES - if this transformer "
                "injects branch targets (if/return-redirect), Java 8 "
                "classfiles need regenerated stack map frames or the "
                "class VerifyErrors at load time (dev-ON-3); branch-free "
                "counter injections may justify this"))
    return findings


def check_sentinel_degradation(java_roots):
    """catch-and-return-a-plausible-literal without recording — the
    dev17-20 shape: getLong failure -> return (0,0,0) -> every position
    decoded to the origin and seven boots chased the symptom instead of
    the swallow. Flag any catch block whose body returns a literal
    constant and never records (recordError/LAST_ERROR/ERRORS/log)."""
    findings = []
    for path, src, _pkg, _cls, offline in _java_units(java_roots):
        base = os.path.basename(path)
        if offline or not _LIVE_NAME_RE.search(base):
            continue
        lines = src.split("\n")
        for m in _CATCH_RE.finditer(src):
            # brace-match the catch body (stripped source: safe)
            start = m.end() - 1
            depth = 0
            i = start
            while i < len(src):
                if src[i] == "{":
                    depth += 1
                elif src[i] == "}":
                    depth -= 1
                    if depth == 0:
                        break
                i += 1
            body = src[start:i + 1]
            if not _RETURN_LITERAL_RE.search(body):
                continue
            if _RECORD_RE.search(body):
                continue
            line = src.count("\n", 0, m.start()) + 1
            findings.append(Finding(
                "sentinel-degradation", "warning", path, line,
                "catch block swallows and returns a literal sentinel "
                "(0/-1/false/null/(0,0,0)) without recording the error - "
                "a reflection failure then looks like real data (dev17-20: "
                "return new long[]{0,0,0} decoded every position to the "
                "origin for seven boots); call a record function in every "
                "catch path"))
    return findings


def check_reflection_accessor(java_roots):
    """Flag Field.getLong applied to a field obtained by name — the dev17/18
    trap: Phosphor's static masks are INT fields; getLong threw per call,
    the swallowed catch returned (0,0,0) for every decoded position, and
    every job was rejected as far-from-anchor. Static type of the field is
    unknowable here, so this is a verify-the-accessor hint (justifiable)."""
    findings = []
    decl_re = re.compile(
        r"(\w+)\s*=\s*(?:\(java\.lang\.reflect\.Field\s*\)|\(Field\s*\))?"
        r"\w+\.getDeclaredField\(")
    for path, src, _pkg, _cls, offline in _java_units(java_roots):
        if offline:
            continue
        lines = src.split("\n")
        field_vars = {}
        for i, line in enumerate(lines):
            m = decl_re.search(line)
            if m:
                field_vars[m.group(1)] = i + 1
        if not field_vars:
            continue
        for i, line in enumerate(lines):
            for var, decl_line in field_vars.items():
                if re.search(r"\b%s\s*\.\s*getLong\s*\(" % re.escape(var),
                             line):
                    findings.append(Finding(
                        "reflection-accessor", "warning", path, i + 1,
                        "Field '%s' (fetched by name at line %d) is read "
                        "via getLong — verify the declared type is long; "
                        "a getLong-on-int throws per call and swallowed "
                        "catches silently default every read (dev17/18: "
                        "all positions decoded to (0,0,0))"
                        % (var, decl_line)))
                    break
    return findings


DEFAULT_JAVA_ROOTS = ["tools/bridge/src", "tools/spawn-interop/src",
                      "tools/worldgen-interop/src"]
DEFAULT_RUST_ROOTS = ["crates/ffi/src"]


def run_lint(repo_root, java_roots=None, rust_roots=None):
    def absp(paths):
        return [p if os.path.isabs(p) else os.path.join(repo_root, p)
                for p in paths]

    jroots = absp(java_roots or DEFAULT_JAVA_ROOTS)
    rroots = absp(rust_roots or DEFAULT_RUST_ROOTS)
    findings = []
    findings += check_jni_pairing(jroots, rroots)
    findings += check_bytebuffer_order(jroots)
    findings += check_cross_loader_forname(jroots)
    findings += check_ordinal_gate(jroots)
    findings += check_reflection_accessor(jroots)
    findings += check_sentinel_degradation(jroots)
    findings += check_transformer_frames(jroots)
    findings += check_seam_format(jroots, rroots)
    findings = _apply_justifications(findings)
    order = {"fatal": 0, "warning": 1, "info": 2}
    findings.sort(key=lambda f: (order[f.severity], _rel(f.file, repo_root),
                                 f.line))
    return findings


def _apply_justifications(findings):
    """Non-fatal findings may be suppressed by a RUNSCOPE-JUSTIFIED comment
    near the finding line; the justification text rides on the finding so
    the trail stays visible in output."""
    by_file = {}
    for f in findings:
        if f.severity != "fatal":
            by_file.setdefault(f.file, []).append(f)
    for path, group in by_file.items():
        try:
            with open(path, "r", encoding="utf-8", errors="replace") as fh:
                raw_lines = fh.read().split("\n")
        except OSError:
            continue
        for f in group:
            reason = _find_justification(raw_lines, f.line)
            if reason:
                f.justification = reason or "justified (no reason text)"
    return findings
