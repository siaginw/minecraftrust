#!/usr/bin/env python3
"""Compare CANONICAL_ID_V2's actual ASM dump with an independent Rust parser.

No expected profile hash can stand in for a fresh parser observation. This is a
tooling cross-check; it cannot qualify a running JVM or authorize native state.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys


def encoded(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def ordered(values):
    return sorted(values, key=encoded)


def java_json(value):
    """Independent serializer matching V2's explicitly documented ASCII JSON."""
    if isinstance(value, str):
        out = ['"']
        for char in value:
            if char in ('"', "\\"):
                out.append("\\" + char)
            elif ord(char) < 32 or ord(char) > 126:
                units = char.encode("utf-16-be", errors="surrogatepass")
                out.extend("\\u" + units[i:i + 2].hex() for i in range(0, len(units), 2))
            else:
                out.append(char)
        return "".join(out) + '"'
    if isinstance(value, list):
        return "[" + ",".join(java_json(v) for v in value) + "]"
    if value is None:
        return "null"
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, int):
        return str(value)
    raise ValueError(f"unsupported V2 JSON value {type(value)}")


def constant(value):
    if value is None:
        return None
    tag, *rest = value
    if tag == "float_bits":
        return ["float", *rest]
    if tag == "double_bits":
        return ["double", *rest]
    if tag == "annotation":
        return [tag, [rest[0], [[n, constant(v)] for n, v in rest[1]]]]
    if tag == "array":
        return [tag, [constant(v) for v in rest[0]]]
    return value


def annotation(desc, values):
    return [desc, [[name, constant(value)] for name, value in values]]


def annotations(values):
    return ordered([[visible, annotation(desc, pairs)] for visible, desc, pairs in values])


def type_path(path):
    result = []
    i = 0
    while path and i < len(path):
        c = path[i]
        if c in "[.*":
            result.append([{"[": 0, ".": 1, "*": 2}[c], 0])
            i += 1
        else:
            end = path.index(";", i)
            result.append([3, int(path[i:end])])
            i = end + 1
    return result


def type_annotations(values, offset=None, local=False):
    result = []
    for value in values:
        visible, ref, path, desc, pairs, *ranges = value
        tag = (ref >> 24) & 255
        if tag in (0x00, 0x01, 0x16):
            target = [tag, (ref >> 16) & 255]
        elif tag in (0x10, 0x17, 0x42):
            target = [tag, (ref >> 8) & 65535]
        elif tag in (0x11, 0x12):
            target = [tag, (ref >> 16) & 255, (ref >> 8) & 255]
        elif tag in (0x13, 0x14, 0x15):
            target = [tag]
        elif tag in (0x40, 0x41) and local:
            start, end, index = ranges
            target = [tag, [list(x) for x in zip(start, end, index)]]
        elif tag in range(0x43, 0x47) and offset is not None:
            target = [tag, offset]
        elif tag in range(0x47, 0x4C) and offset is not None:
            target = [tag, offset, ref & 255]
        else:
            raise ValueError(f"unsupported ASM type annotation target {tag:#x}")
        result.append([visible, target, type_path(path), annotation(desc, pairs)])
    return ordered(result)


def frame_value(value):
    if value[0] in ("verification_tag", "object"):
        return value[1]
    if value[0] == "uninitialized":
        return value
    raise ValueError(f"unknown frame value {value!r}")


def argument_count(descriptor):
    if not descriptor.startswith("("):
        raise ValueError("invalid method descriptor")
    pos, count = 1, 0
    while descriptor[pos] != ")":
        while descriptor[pos] == "[":
            pos += 1
        if descriptor[pos] == "L":
            pos = descriptor.index(";", pos) + 1
        elif descriptor[pos] in "BCDFIJSZ":
            pos += 1
        else:
            raise ValueError("invalid method parameter descriptor")
        count += 1
    return count


def parameter_annotations(descriptor, sides, raw_counts):
    """Invert ASM 5's documented-in-bytecode synthetic parameter padding.

    ClassReader.readParameterAnnotations emits an invisible Synthetic marker
    for each leading omitted parameter on *each* present visibility attribute.
    Exact marker counts are checked before removing these visitor-only records.
    Real Synthetic annotations after that prefix remain in the facts.
    """
    arity = argument_count(descriptor)
    work = [[list(entry) for entry in side] if side is not None else [[] for _ in range(arity)] for side in sides]
    if any(len(side) != arity for side in work):
        raise ValueError("ASM parameter annotation array length differs from descriptor")
    shifts = []
    for count in raw_counts:
        if count is not None and not 0 <= count <= arity:
            raise ValueError("raw parameter annotation count exceeds descriptor arity")
        shifts.append(0 if count is None else arity - count)
    marker = [True, "Ljava/lang/Synthetic;", []]  # dump's side entry visibility tag is always true
    for index in range(arity):
        inserted = sum(count is not None and index < shift for count, shift in zip(raw_counts, shifts))
        if work[1][index][:inserted] != [marker] * inserted:
            raise ValueError("ASM synthetic parameter marker prefix disagrees with raw counts")
        del work[1][index][:inserted]
    result = []
    for visible, side, raw_count, shift in zip((True, False), work, raw_counts, shifts):
        for index, entry in enumerate(side):
            if entry and (raw_count is None or index < shift):
                raise ValueError("unexplained ASM annotation outside raw parameter range")
            for _, desc, pairs in entry:
                result.append([visible, index - shift, annotation(desc, pairs)])
    return ordered(result)


def project(dump):
    if not isinstance(dump, list) or len(dump) != 19 or dump[0] != "CANONICAL_ID_V2":
        raise ValueError("unexpected V2 dump schema")
    fields = []
    for owner, name, desc, signature, access, value, anns, types in dump[15]:
        if owner != dump[3]:
            raise ValueError("field owner disagrees with class name")
        fields.append(dict(name=name, descriptor=desc, signature=signature, access=access,
                           constant=constant(value), annotations=annotations(anns),
                           type_annotations=type_annotations(types)))
    methods = []
    for m in dump[16]:
        if len(m) != 20:
            raise ValueError("unexpected V2 method schema")
        name, desc, signature, access, exceptions, parameters, anns, types, param_anns, default, max_stack, max_locals, insns, handlers, frames, lines, locals_, local_anns, raw_parameter_annotation_counts, method_parameter_count = m
        if method_parameter_count == 0 and parameters is None:
            parameters = []
        if method_parameter_count != (None if parameters is None else len(parameters)):
            raise ValueError("ASM MethodParameters raw count/visitation disagreement")
        param_facts = parameter_annotations(desc, param_anns, raw_parameter_annotation_counts)
        code = None
        if not access & (0x100 | 0x400):
            instructions = []
            code_types = type_annotations(local_anns, local=True)
            for offset, (op, args, insn_types) in enumerate(insns):
                if op == 18:
                    args = [constant(args[0])]
                elif op == 186:
                    args = [args[0], args[1], constant(args[2]), [constant(a) for a in args[3]]]
                elif op == 171:
                    keys, default_label, labels = args
                    args = [default_label, [list(x) for x in zip(keys, labels)]]
                instructions.append([op, *args])
                code_types.extend(type_annotations(insn_types, offset=offset))
            for h in handlers:
                code_types.extend(type_annotations(h[4]))
            code = dict(max_stack=max_stack, max_locals=max_locals,
                        instructions=instructions, handlers=[h[:4] for h in handlers],
                        frames=[[f[0], [frame_value(v) for v in f[2]], [frame_value(v) for v in f[3]]] for f in frames],
                        lines=ordered([[pc, line] for line, pc in lines]),
                        locals=ordered([[start, end, n, d, index] for n, d, sig, start, end, index in locals_]),
                        local_types=ordered([[start, end, n, sig, index] for n, d, sig, start, end, index in locals_ if sig is not None]),
                        type_annotations=ordered(code_types))
        methods.append(dict(name=name, descriptor=desc, signature=signature, access=access,
                            exceptions=exceptions, parameters=parameters,
                            method_parameter_count=method_parameter_count,
                            parameter_annotation_counts=raw_parameter_annotation_counts,
                            parameter_annotations=param_facts, annotation_default=constant(default),
                            annotations=annotations(anns), type_annotations=type_annotations(types), code=code))
    return dict(schema="RUSTCRAFT_CLASS_FACTS_V1", version=dump[1], access=dump[2], name=dump[3],
                signature=dump[4], super=dump[5], interfaces=dump[6], source=dump[7], source_debug=dump[8],
                enclosing=dump[9:12] if dump[9] is not None else None,
                annotations=annotations(dump[12]), type_annotations=type_annotations(dump[13]),
                inner_classes=dump[14], fields=ordered(fields), methods=ordered(methods))


def differences(a, b, prefix="", limit=30):
    out = []
    if type(a) is not type(b):
        return [dict(path=prefix, rust=a, asm=b)]
    if isinstance(a, dict):
        for key in sorted(a.keys() | b.keys()):
            if key not in a or key not in b:
                out.append(dict(path=f"{prefix}/{key}", rust=a.get(key), asm=b.get(key)))
            else:
                out.extend(differences(a[key], b[key], f"{prefix}/{key}", limit))
            if len(out) >= limit:
                break
    elif isinstance(a, list):
        if len(a) != len(b):
            out.append(dict(path=prefix + "/length", rust=len(a), asm=len(b)))
        for i, (x, y) in enumerate(zip(a, b)):
            out.extend(differences(x, y, f"{prefix}/{i}", limit))
            if len(out) >= limit:
                break
    elif a != b:
        out.append(dict(path=prefix, rust=a, asm=b))
    return out[:limit]


def run(command):
    proc = subprocess.run(command, capture_output=True, text=True, encoding="utf-8", errors="strict", timeout=120)
    return proc.returncode, proc.stdout, proc.stderr


def decode_lines(text, expected):
    lines = text.splitlines()
    if len(lines) != expected:
        raise ValueError(f"expected {expected} output rows, got {len(lines)}")
    return [json.loads(line) for line in lines]


def observe(args, files):
    rust_code, rust_out, rust_err = run([args.rust_exe, *map(str, files)])
    if rust_code not in (0, 1):
        raise RuntimeError(f"Rust process failed: {rust_code}: {rust_err}")
    rust = decode_lines(rust_out, len(files))
    # Rejections are data; an unexpected crash or schema failure remains a harness error.
    if rust_code != int(any(x["status"] == "REJECTED" for x in rust)):
        raise ValueError("Rust status/exit code disagreement")
    java_cmd = [args.java, "-cp", args.classpath, "com.rustcraft.coremod.CanonicalClassIdentityV2"]
    dump_code, dump_out, dump_err = run([*java_cmd, "--dump", *map(str, files)])
    if dump_code:
        if len(files) > 1:
            # Java intentionally emits nothing unless all inputs succeed. Isolate
            # failing inputs; never attribute one rejected class to every row.
            return [row for f in files for row in observe(args, [f])]
        rejected = dump_code == 1 and "com.rustcraft.coremod.CanonicalClassIdentityV2$IdentityFailure:" in dump_err and not dump_out.strip()
        return [dict(path=str(files[0]), rust=rust[0], asm_status="REJECTED" if rejected else "PROCESS_FAILURE", asm_error=dump_err, asm_exit=dump_code)]
    dumps = decode_lines(dump_out, len(files))
    receipt_code, receipt_out, receipt_err = run([*java_cmd, *map(str, files)])
    if receipt_code:
        raise RuntimeError(f"Java receipt failed after dump success: {receipt_err}")
    receipts = decode_lines(receipt_out, len(files))
    result = []
    for path, r, dump, receipt in zip(files, rust, dumps, receipts):
        if len(receipt) != 5 or receipt[0] != "CANONICAL_ID_V2":
            raise ValueError("unexpected V2 receipt")
        result.append(dict(path=str(path), rust=r, asm_status="PARSED", asm_dump=dump, asm_receipt=receipt))
    return result


def compare(row, detail_dir, index):
    rust, asm_status = row["rust"], row["asm_status"]
    result = dict(path=row["path"], rust_status=rust["status"], asm_status=asm_status)
    if rust["status"] != "PARSED" or asm_status != "PARSED":
        result["status"] = "BOTH_REJECTED" if rust["status"] == "REJECTED" and asm_status == "REJECTED" else "PARSER_DISAGREEMENT"
        result["rust_error"] = rust.get("error")
        result["asm_error"] = row.get("asm_error")
        return result
    facts = dict(rust["facts"])
    declaration_order = facts.pop("declaration_order")
    projected = project(row["asm_dump"])
    diff = differences(facts, projected)
    order = ["CANONICAL_ID_V2_DECLARATION_ORDER"]
    order.extend(["field", *x] for x in declaration_order[0])
    order.extend(["method", *x] for x in declaration_order[1])
    # Java V2 JSON escapes all non-ASCII/control characters, including newline as
    # \u000a, whereas json.dumps uses \n. Reuse no Java hashing or parser code.
    order_json = java_json(order)
    order_hash = hashlib.sha256(order_json.encode("utf-8")).hexdigest()
    if order_hash != row["asm_receipt"][3]:
        diff.append(dict(path="/declaration_order_sha256", rust=order_hash, asm=row["asm_receipt"][3]))
    actual_raw = hashlib.sha256(Path(row["path"]).read_bytes()).hexdigest()
    if actual_raw != rust["raw_sha256"] or actual_raw != row["asm_receipt"][4]:
        diff.append(dict(path="/raw_sha256", current=actual_raw, rust=rust["raw_sha256"], asm=row["asm_receipt"][4]))
    result.update(status="AGREEMENT" if not diff else "PARSER_DISAGREEMENT", raw_sha256=actual_raw,
                  semantic_sha256=row["asm_receipt"][2], facts_sha256=rust["facts_sha256"],
                  semantic_facts_sha256=hashlib.sha256(encoded(facts).encode("utf-8")).hexdigest(),
                  declaration_order_sha256=order_hash, attribute_inventory=rust["attribute_inventory"])
    if diff:
        detail = detail_dir / f"{index:05d}.json"
        detail.write_text(json.dumps(dict(observation=row, projected_asm=projected, differences=diff), indent=2, ensure_ascii=True) + "\n", encoding="utf-8")
        result.update(differences=diff, disagreement_detail=str(detail))
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java", required=True)
    parser.add_argument("--classpath", required=True)
    parser.add_argument("--rust-exe", required=True)
    parser.add_argument("--root", action="append", required=True, help="NAME=directory of .class files; recursively read")
    parser.add_argument("--controls", type=Path, help="V2 generated controls.tsv; its parent must be supplied as a root")
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    tool_paths = [Path(args.rust_exe), Path(args.java), Path(__file__), Path(__file__).parent / "Cargo.lock", Path(__file__).parent / "Cargo.toml", Path(__file__).parent / "src/main.rs"]
    if args.controls:
        tool_paths.append(args.controls)
    for entry in args.classpath.split(os.pathsep):
        path = Path(entry)
        if path.is_dir():
            tool_paths.extend(path.glob("com/rustcraft/coremod/CanonicalClassIdentityV2*.class"))
        else:
            tool_paths.append(path)
    def tool_hashes():
        return {str(p.resolve(strict=True)): hashlib.sha256(p.read_bytes()).hexdigest() for p in tool_paths}
    tools_before = tool_hashes()
    roots = []
    for spec in args.root:
        name, path = spec.split("=", 1)
        if any(name == n for n, _ in roots):
            raise ValueError(f"duplicate corpus name {name}")
        roots.append((name, Path(path).resolve(strict=True)))
    def input_inventory():
        return {name: {str(p.relative_to(root)): hashlib.sha256(p.read_bytes()).hexdigest()
                       for p in sorted(root.rglob("*.class"))} for name, root in roots}
    inputs_before = input_inventory()
    args.out.parent.mkdir(parents=True, exist_ok=True)
    details = args.out.parent / (args.out.stem + "-disagreements")
    details.mkdir(exist_ok=True)
    rows = []
    counts = {}
    for name, root in roots:
        files = sorted(root.rglob("*.class"))
        if not files:
            raise ValueError(f"empty required corpus {name}: {root}")
        counts[name] = len(files)
        for start in range(0, len(files), 16):
            for observation in observe(args, files[start:start + 16]):
                row = compare(observation, details, len(rows))
                row.update(corpus=name, relative_path=str(Path(row["path"]).relative_to(root)))
                rows.append(row)
        print(f"{name}: {len(files)} observed", flush=True)
    controls = []
    if args.controls:
        base = next((r for r in rows if Path(r["path"]).resolve() == (args.controls.parent / "baseline.class").resolve()), None)
        if base is None or base["status"] != "AGREEMENT":
            raise ValueError("mutation baseline must have fresh parser agreement")
        by_path = {str(Path(r["path"]).resolve()): r for r in rows}
        for line in args.controls.read_text(encoding="utf-8").splitlines():
            columns = line.split("\t")
            if len(columns) not in (3, 4):
                raise ValueError("controls.tsv must have 3 or 4 columns")
            name, expected, expected_hash = columns[:3]
            baseline_name = columns[3] if len(columns) == 4 else "baseline"
            control_base = by_path.get(str((args.controls.parent / (baseline_name + ".class")).resolve()))
            if control_base is None or control_base["status"] != "AGREEMENT":
                raise ValueError(f"control {name} missing agreed baseline {baseline_name}")
            row = by_path.get(str((args.controls.parent / (name + ".class")).resolve()))
            if row is None:
                raise ValueError(f"control {name} missing fresh observation")
            if expected == "REJECTED":
                passed = row["status"] == "BOTH_REJECTED"
            else:
                passed = row["status"] == "AGREEMENT" and row.get("semantic_sha256") == expected_hash
                if expected == "CHANGED":
                    passed &= row.get("semantic_facts_sha256") != control_base["semantic_facts_sha256"]
                elif expected == "STABLE":
                    # Declaration order has a separate protected digest; the
                    # sorted member projection must otherwise remain equivalent.
                    passed &= row.get("semantic_sha256") == control_base["semantic_sha256"]
                    passed &= row.get("semantic_facts_sha256") == control_base["semantic_facts_sha256"]
                else:
                    raise ValueError(f"unknown control expectation {expected}")
            controls.append(dict(name=name, baseline=baseline_name, expected=expected, result="PASS" if passed else "FAIL"))
    rejected_controls = {str((args.controls.parent / (c["name"] + ".class")).resolve()) for c in controls if c["expected"] == "REJECTED"} if args.controls else set()
    success = all(r["status"] == "AGREEMENT" or (r["status"] == "BOTH_REJECTED" and str(Path(r["path"]).resolve()) in rejected_controls) for r in rows)
    success &= all(c["result"] == "PASS" for c in controls)
    tools_after = tool_hashes()
    success &= tools_before == tools_after
    inputs_after = input_inventory()
    success &= inputs_before == inputs_after
    receipt = dict(schema="RUSTCRAFT_INDEPENDENT_CLASSFILE_CROSSCHECK_V1", status="PASS" if success else "FAIL",
                   observed_at=datetime.now(timezone.utc).isoformat(), parser="cafebabe@0.9.0",
                   authority_eligible=False, evidence_scope="FRESH_TOOLING_OBSERVATION_OF_INPUT_CLASS_BYTES_NOT_LIVE_RUNTIME_QUALIFICATION",
                   comparison="ASM CANONICAL_ID_V2 dump projected to independently parsed symbolic class facts",
                   exclusions=["unused constant-pool entries and unused bootstrap methods (V2 identity retains these)",
                               "JVM verifier/dataflow validity, execution behavior and runtime writer/lifecycle closure",
                               "post-Java-8 classes and unknown attributes reject; unsupported strings reject"],
                   tool_artifacts_before=tools_before, tool_artifacts_after=tools_after,
                   tool_artifacts_unchanged=tools_before == tools_after,
                   input_inventory_sha256_before=hashlib.sha256(encoded(inputs_before).encode("utf-8")).hexdigest(),
                   input_inventory_sha256_after=hashlib.sha256(encoded(inputs_after).encode("utf-8")).hexdigest(),
                   input_inventory_unchanged=inputs_before == inputs_after,
                   command=sys.argv, corpora=counts, agreement=sum(r["status"] == "AGREEMENT" for r in rows),
                   expected_rejections=sum(r["status"] == "BOTH_REJECTED" for r in rows),
                   disagreements=sum(r["status"] == "PARSER_DISAGREEMENT" for r in rows), controls=controls, files=rows)
    args.out.write_text(json.dumps(receipt, indent=2, ensure_ascii=True) + "\n", encoding="utf-8")
    print(f"{receipt['status']}: {receipt['agreement']} agreements; {receipt['expected_rejections']} paired rejections; {receipt['disagreements']} disagreements; receipt {args.out}")
    return 0 if success else 1


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (ValueError, KeyError, OSError, RuntimeError, subprocess.SubprocessError) as failure:
        print(f"CROSSCHECK_HARNESS_ERROR: {failure}", file=sys.stderr)
        sys.exit(2)
