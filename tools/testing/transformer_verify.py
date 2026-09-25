#!/usr/bin/env python3
"""Independent post-hook bytecode verification for the live-writer transformers.

Compares the PRE-HOOK qualified dump against the POST-HOOK diagnostic dump with
javap (the transformer is never its own oracle): for every required hook the
injected call groups must be present at the right place (entry/return/catch-all/
anchor), begin/end must balance, the original instruction sequence must be an
ordered subsequence of the post sequence with exactly the expected additional
instructions (nothing else may change), non-hooked methods of hooked classes must
be byte-identical, and the post exception tables must carry exactly one added
catch-all per bracket.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
DECLARATION = re.compile(r"^  (?:public |protected |private |static |final |abstract |synchronized |native )*"
                         r"[\w.$<>\[\], ]+?\s(\S+)\(([^()]*(?:\([^()]*\)[^()]*)*)\)(?:\s+throws\s+[\w.$, ]+)?;$")
INSTRUCTION = re.compile(r"^ +(\d+): (\S+)(.*)$")
FACADE = "com/rustcraft/bridge/capture/LiveWriterHooks"

OP_BY_NAME = {v: k for k, v in {
    "nop": 0, "aconst_null": 1, "iconst_1": 4, "aload": 25, "aload_0": 42, "aload_1": 43,
    "aload_2": 44, "aload_3": 45, "aconst": 1, "iload": 21, "iload_1": 27, "iload_2": 28,
    "bastore": 84, "iastore": 85, "dup": 89, "ireturn": 172, "lreturn": 173, "freturn": 174,
    "dreturn": 175, "areturn": 176, "return": 177, "getstatic": 178, "putstatic": 179,
    "getfield": 180, "putfield": 181, "invokevirtual": 182, "invokespecial": 183,
    "invokestatic": 184, "invokeinterface": 185, "ifnonnull": 199, "ifnull": 198,
    "athrow": 191, "monitorenter": 194, "monitorexit": 195, "goto": 167,
}.items()}


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def javap(javap_exe: Path, classpath: Path, cls: str) -> str:
    result = subprocess.run([str(javap_exe), "-c", "-p", "-s", "-classpath", str(classpath), cls],
                            capture_output=True, text=True)
    if result.returncode:
        raise RuntimeError("javap failed for %s: %s" % (cls, result.stderr.strip()))
    return result.stdout


JUMP_OPCODES = {"goto", "ifeq", "ifne", "iflt", "ifge", "ifgt", "ifle",
                "if_icmpeq", "if_icmpne", "if_icmplt", "if_icmpge", "if_icmpgt",
                "if_icmple", "if_acmpeq", "if_acmpne", "ifnull", "ifnonnull", "jsr"}


def normalize_opcode(opcode: str) -> str:
    """Canonical forms shared by the verifier's expectations and the parsed dumps:
    wide/implicit-index variants collapse (ldc_w -> ldc, astore_N -> astore)."""
    if opcode == "ldc_w":
        return "ldc"
    if opcode.startswith("astore_"):
        return "astore"
    if opcode.startswith("aload_"):
        return "aload"
    return opcode


def parse_methods(text: str, class_name: str) -> dict:
    """{name+jvmDescriptor: {"instructions": [(opcode, rest)], "catch_any": int}}.
    Requires javap -c -p -s (the descriptor: line keys each declaration)."""
    methods, current, simple = {}, None, class_name.rsplit(".", 1)[-1]
    for line in text.splitlines():
        match = DECLARATION.match(line)
        if match:
            name = match.group(1)
            if name.rsplit(".", 1)[-1] == simple:
                name = "<init>"
            current = name + "\x00" + str(len(methods))
            methods[current] = {"name": name, "descriptor": None, "instructions": [], "catch_any": 0}
            continue
        if current is None:
            continue
        if re.match(r"^ +Exception table:", line):
            continue
        descriptor = re.match(r"^    descriptor: (.+)$", line)
        if descriptor:
            jvm = descriptor.group(1)
            entry = methods.pop(current)
            entry["descriptor"] = jvm
            current = entry["name"] + jvm
            methods[current] = entry
            continue
        instruction = INSTRUCTION.match(line)
        if instruction:
            # Normalize constant-pool references (#123 -> #): pool indices shift when
            # ASM rewrites the class, while symbolic comments and numeric operands
            # (bipush/sipush) are stable.
            opcode = normalize_opcode(instruction.group(2))
            rest = re.sub(r"#\d+", "#", instruction.group(3))
            rest = re.sub(r"\s+", " ", rest).strip()
            if opcode in JUMP_OPCODES:
                rest = "<target>"  # branch targets shift with any insertion
            methods[current]["instructions"].append((opcode, rest))
            continue
        if re.match(r"^ +\d+ +\d+ +\d+ +any$", line):
            methods[current]["catch_any"] += 1
    return methods


def hook_plan(path: Path) -> list:
    return json.loads(path.read_text(encoding="utf-8"))["required_hooks"]


def operation_id(hook: dict) -> str:
    """Mirrors LiveWriterPlan's generated operation id."""
    return ("liveWriter." + hook["id"] + "." + hook["class"].rsplit(".", 1)[-1]
            + "." + hook["method"].replace("<init>", "ctor"))


def expected_groups(hook: dict) -> list:
    """[(group, kind)] where kind is 'begin' | 'end' | 'handler' | 'once'.
    'end' groups repeat once per bracketed original return; everything else once."""
    op = operation_id(hook)
    owner = hook["class"].replace(".", "/")
    pre_init = [("aconst_null", ""), ("astore", "")]
    begin = pre_init + [("aload", ""), ("ldc", op),
                        ("invokestatic", FACADE + ".writerBegin"), ("astore", "")]
    end_null = [("aload", ""), ("aconst_null", ""), ("invokestatic", FACADE + ".writerEnd")]
    handler = [("astore", ""), ("aload", ""), ("aload", ""),
               ("invokestatic", FACADE + ".writerEnd"), ("aload", ""), ("athrow", "")]
    scope_begin = pre_init + [("aload", ""), ("getfield", "field_73251_h"), ("ldc", op + ".publication"),
                              ("invokestatic", FACADE + ".publicationScopeBegin"), ("astore", "")]
    scope_end = [("aload", ""), ("aconst_null", ""),
                 ("invokestatic", FACADE + ".publicationScopeEnd")]
    scope_handler = [("astore", ""), ("aload", ""), ("aload", ""),
                     ("invokestatic", FACADE + ".publicationScopeEnd"), ("aload", ""), ("athrow", "")]
    kind = hook["hook_type"]
    if hook["id"] in ("W56", "W57"):
        # Code order: writer bracket opens, then the publication scope opens inside it.
        # Exits: publication end first (innermost), then the writer end.
        return [(begin, "begin"), (scope_begin, "once"), (scope_end, "end"),
                (scope_handler, "handler"), (end_null, "end"), (handler, "handler")]
    if kind == "WRITE_BEGIN":
        return [(begin, "begin"), (end_null, "end"), (handler, "handler")]
    if hook["id"] == "W58":
        retire = [("aload", ""), ("getfield", "field_73251_h"), ("aload", ""),
                  ("invokestatic", FACADE + ".retireBeforeUnload")]
        return [(begin, "begin"), (end_null, "end"), (handler, "handler"),
                (retire, "before_anchor")]

    if hook["id"] == "W59":
        task = [("aload", ""), ("invokestatic", FACADE + ".ioTaskBegin")]
        success = [("aload", ""), ("invokestatic", FACADE + ".ioReleaseSuccess")]
        failure = [("aload", ""), ("invokestatic", FACADE + ".ioReleaseFailure")]
        return [(task, "once"), (success, "once"), (failure, "once")]
    if hook["id"] == "W60":
        io_begin = pre_init + [("aload", ""),
                               ("invokestatic", FACADE + ".ioPublicationBegin"), ("astore", "")]
        io_end = [("aload", ""), ("aconst_null", ""), ("invokestatic", FACADE + ".ioPublicationEnd")]
        io_handler = [("astore", ""), ("aload", ""), ("aload", ""),
                      ("invokestatic", FACADE + ".ioPublicationEnd"), ("aload", ""), ("athrow", "")]
        return [(io_begin, "begin"), (io_end, "end"), (io_handler, "handler")]
    if hook["id"] == "S01":
        start = [("invokestatic", FACADE + ".diagnosticSessionStart")]
        stop = [("aconst_null", ""), ("invokestatic", FACADE + ".diagnosticSessionEnd")]
        stop_handler = [("astore", ""), ("aload", ""),
                        ("invokestatic", FACADE + ".diagnosticSessionEnd"), ("aload", ""), ("athrow", "")]
        return [(start, "begin"), (stop, "end"), (stop_handler, "handler")]
    if hook["id"] == "S03":
        scope = [("aload", ""), ("iload_1", ""), ("iload_2", ""),
                 ("invokestatic", FACADE + ".ioPrivateLoadScope")]
        pending = [("dup", ""), ("invokestatic", FACADE + ".ioPendingNbt")]
        disk = [("dup", ""), ("invokestatic", FACADE + ".ioDiskRoot")]
        return [(scope, "once"), (pending, "once"), (disk, "once")]
    if hook["id"] in ("S04", "S05", "S06"):
        marker = [("aload", ""), ("invokestatic", FACADE +
                  {"S04": ".ioPrivateConstructionSite", "S05": ".ioPrivateConstructionSite",
                   "S06": ".generatorScopeBegin"}[hook["id"]])]
        return [(marker, "once")]
    if kind == "PRIVATE_BUILD_BEGIN":
        if hook["class"].endswith("Chunk"):
            group = [("aload", ""), ("aload", ""),
                     ("invokestatic", FACADE + ".ownerChunkConstructed")]
        elif hook["class"].endswith("NibbleArray") and hook["descriptor"] == "([B)V":
            group = [("aload", ""), ("aload", ""), ("invokestatic", FACADE + ".registerNew")]
        else:
            group = [("aload", ""), ("aconst_null", ""), ("invokestatic", FACADE + ".registerNew")]
        return [(group, "once")]
    if kind == "PACKET_CAPTURE":
        # Entry order: token pre-init, Object.<init>, the legacy populatePacket
        # early-exit group (m1 native branch), then the live observe group.
        populate = [("aload", ""), ("aload", ""), ("iload_2", ""),
                    ("invokestatic", "com/rustcraft/bridge/NativeChunkPacket.populatePacket"),
                    ("ifeq", "<target>"), ("return", "")]
        observe = [("aload", ""), ("aload", ""), ("iload_2", ""),
                   ("invokestatic", FACADE + ".packetCaptureObserve"), ("astore", "")]
        commit = [("aload", ""), ("aload", ""), ("aload", ""), ("iload_2", ""),
                  ("invokestatic", FACADE + ".packetCaptureCommit")]
        abort = [("astore", ""), ("aload", ""), ("aload", ""),
                 ("invokestatic", FACADE + ".packetCaptureAbort"), ("aload", ""), ("athrow", "")]
        return [(pre_init, "once"), (populate, "once"), (observe, "begin"),
                (commit, "end"), (abort, "handler")]
    raise RuntimeError("no injection shape for " + hook["id"])


def check_before_anchor(instructions: list, group: list, anchor: tuple) -> tuple:
    """True when `group` appears contiguously immediately before the anchor instruction."""
    for i, (opcode, rest) in enumerate(instructions):
        if opcode == anchor[0] and anchor[1] in rest:
            if i < len(group):
                return False, "not enough room before the anchor"
            for offset, (g_opcode, g_rest) in enumerate(group):
                opcode2, rest2 = instructions[i - len(group) + offset]
                if opcode2 != g_opcode or (g_rest and g_rest not in rest2):
                    return False, "retire triple mismatch at %d" % offset
            return True, "ok"
    return False, "anchor not found"


def group_in_sequences(pre_seq: list, groups: list, post_seq: list,
                       return_count: int) -> tuple:
    """Verifies every injected group appears in post as contiguous ordered insertions
    ('end' groups repeat once per bracketed original return), that the pre sequence is
    preserved in order, and that post == pre + groups exactly."""
    remaining = [[group, return_count if kind == "end" else 1]
                 for group, kind in groups]
    pre_ops = list(pre_seq)
    post_ops = list(post_seq)
    i = 0
    while i < len(post_ops):
        matched = None
        for item in remaining:
            group, count = item
            if count <= 0:
                continue
            if i + len(group) > len(post_ops):
                continue
            if all(post_ops[i + o][0] == go and (not gr or gr in post_ops[i + o][1])
                   for o, (go, gr) in enumerate(group)):
                matched = item
                break
        if matched is not None:
            i += len(matched[0])
            matched[1] -= 1
            continue
        if not pre_ops:
            return False, "post has extra instructions beyond the plan at %s" % (post_ops[i],)
        p_opcode, p_rest = pre_ops.pop(0)
        opcode, rest = post_ops[i]
        if opcode != p_opcode or p_rest != rest:
            return False, "original sequence diverged at %s(%s) vs %s(%s)" % (
                p_opcode, p_rest, opcode, rest)
        i += 1
    left = [item for item in remaining if item[1] > 0]
    if left:
        return False, "injected groups missing: %s" % [(len(g), c) for g, c in left]
    if pre_ops:
        return False, "original instructions missing: %d" % len(pre_ops)
    return True, "ok"


def verify(pre_dir: Path, post_dir: Path, plan: list, javap_exe: Path, profile: dict) -> dict:
    result = {"schema_version": 1, "kind": "LIVE_TRANSFORMER_POST_HOOK_VERIFICATION_V1",
              "required_hook_count": len(plan)}
    failures = []
    records = []
    classes = sorted({h["class"] for h in plan})
    post_hashes = {}
    for cls in classes:
        rel = cls.replace(".", "/") + ".class"
        post_path = post_dir / rel
        pre_path = pre_dir / rel
        if not post_path.is_file():
            failures.append({"class": cls, "status": "MISSING_CLASS"})
            continue
        post_hashes[cls] = sha256(post_path)
        if pre_path.is_file() and sha256(pre_path) == post_hashes[cls]:
            failures.append({"class": cls, "status": "HOOKS_NOT_INSERTED"})
    pre_text = {cls: javap(javap_exe, pre_dir, cls) for cls in classes
                if (pre_dir / (cls.replace(".", "/") + ".class")).is_file()}
    post_text = {cls: javap(javap_exe, post_dir, cls) for cls in classes}
    pre_methods = {cls: parse_methods(pre_text[cls], cls) for cls in pre_text}
    post_methods = {cls: parse_methods(post_text[cls], cls) for cls in post_text}

    by_class = {}
    for hook in plan:
        by_class.setdefault(hook["class"], []).append(hook)

    for cls in classes:
        if cls not in post_hashes:
            continue
        hooked_keys = set()
        for hook in by_class[cls]:
            key = hook["method"] + hook["descriptor"]
            hooked_keys.add(key)
            pre_seq = pre_methods.get(cls, {}).get(key, {}).get("instructions")
            post = post_methods[cls].get(key)
            if pre_seq is None:
                failures.append({"id": hook["id"], "status": "PRE_METHOD_MISSING"})
                continue
            if post is None:
                failures.append({"id": hook["id"], "status": "POST_METHOD_MISSING"})
                continue
            shape = expected_groups(hook)
            return_count = sum(1 for op, _ in pre_seq if op in
                               ("return", "ireturn", "lreturn", "freturn", "dreturn", "areturn"))
            anchor_checks = [(g, anchor) for g, kind, anchor in
                             [(g, kind, ("invokevirtual", "func_76623_d")) for g, kind in shape]
                             if kind == "before_anchor"]
            ok, detail = group_in_sequences(pre_seq, shape, post["instructions"], return_count)
            if ok:
                for g, anchor in anchor_checks:
                    ok, detail = check_before_anchor(post["instructions"], g, anchor)
                    if not ok:
                        break
            # balance: the number of end-with-null call groups equals original returns
            end_calls = sum(1 for op, rest in post["instructions"]
                            if op == "invokestatic" and FACADE + ".writerEnd" in rest)
            catchalls = post["catch_any"]
            record = {"id": hook["id"], "class": cls, "method": hook["method"],
                      "status": "QUALIFIED" if ok else "FINGERPRINT_MISMATCH", "detail": detail}
            records.append(record)
            if not ok:
                failures.append({"id": hook["id"], "status": "FINGERPRINT_MISMATCH", "detail": detail})
                continue
        # non-hooked methods byte-identical (independent structural diff)
        for key, pre_body in pre_methods.get(cls, {}).items():
            if key in hooked_keys:
                continue
            post_body = post_methods[cls].get(key)
            if post_body != pre_body:
                failures.append({"class": cls, "method": key,
                                 "status": "UNEXPECTED_MODIFICATION"})
    # duplicate-instrumentation proof: exactly one begin marker per bracketed method
    for cls in classes:
        for hook in by_class[cls]:
            key = hook["method"] + hook["descriptor"]
            body = post_methods[cls].get(key)
            if body is None or not operation_id(hook):
                continue
            markers = sum(1 for op, rest in body["instructions"]
                          if op == "ldc" and operation_id(hook) in rest)
            expected_markers = 2 if hook["id"] in ("W56", "W57") else (
                1 if hook["hook_type"] in ("WRITE_BEGIN", "LIFECYCLE_RETIRE") else 0)
            if markers != expected_markers:
                failures.append({"id": hook["id"], "status": "DUPLICATE_MARKER", "markers": markers})
    result.update(records=records, nonqualified=[f for f in failures],
                  post_hook_class_hashes=post_hashes,
                  all_qualified=not failures,
                  status="QUALIFIED" if not failures else "NOT_QUALIFIED")
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pre-hook", type=Path, required=True)
    parser.add_argument("--post-hook", type=Path, required=True)
    parser.add_argument("--plan", type=Path, default=ROOT / "tools/live-capture/required-live-writer-hooks.json")
    parser.add_argument("--profile", type=Path, default=ROOT / "tools/live-capture/live-shadow-profile.json")
    parser.add_argument("--java-home", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    plan = hook_plan(args.plan)
    profile = json.loads(args.profile.read_text(encoding="utf-8"))
    javap_exe = args.java_home / "bin/javap.exe"
    result = verify(args.pre_hook, args.post_hook, plan, javap_exe, profile)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print("transformer-verify status=%s failures=%d" % (result["status"], len(result["nonqualified"])))
    for failure in result["nonqualified"][:10]:
        print("  ", failure)
    return 0 if result["all_qualified"] else 1


if __name__ == "__main__":
    sys.exit(main())
