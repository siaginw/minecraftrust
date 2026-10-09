"""Machine-readable ASM binary ABI comparison between two pinned ASM jars.

    python -B tools/testing/asm_abi.py --classes <dir> --baseline-asm <jar> \\
        --candidate-asm <jar> --javap <javap.exe> --out <json>

Why this exists. The two runtimes pin different ASM 5.2 builds: Clean Forge
ships `asm-debug-all`, FTB Revelation ships `asm-all`. The sources compile
against the first and not the second, and the failure looks like a hundred
generic-erasure errors. Those errors are a COMPILE-TIME artifact of a build that
strips `Signature` attributes; whether the runtime API actually differs is a
separate question, and it has to be answered by measurement rather than by
assuming it either way. Guessing "it's fine" would be how a real ABI break ships;
guessing "it's broken" would abandon a working pin over a non-issue.

So each referenced symbol is classified:

``IDENTICAL_RUNTIME_ABI``
    Same JVM descriptor and same declaration in both jars.
``SIGNATURE_ONLY_DIFFERENCE``
    Same JVM descriptor, different declaration text -- the generic type
    arguments differ but erasure is identical. NOT a runtime incompatibility.
``REAL_ABI_DIFFERENCE``
    A JVM descriptor differs. At link time this is a NoSuchMethodError or
    NoSuchFieldError.
``MISSING``
    Present in the baseline, absent from the candidate.

The distinction that matters is between a JVM descriptor and a generic
`Signature` attribute. A generic mismatch alone is not runtime ABI
incompatibility, and treating it as one is the error this module exists to
avoid -- as is treating it as free.

JVM descriptors and generic signatures are read separately on purpose:
`javap -s` prints descriptors only, and the declaration line carries the
generic form. Comparing the two independently is what makes the classification
mechanical instead of a judgement call.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import struct
import subprocess
import sys
from pathlib import Path
from typing import Dict, List, Optional, Tuple

SCHEMA = "RUSTCRAFT_ASM_ABI_COMPARISON_V1"

IDENTICAL = "IDENTICAL_RUNTIME_ABI"
SIGNATURE_ONLY = "SIGNATURE_ONLY_DIFFERENCE"
REAL_DIFFERENCE = "REAL_ABI_DIFFERENCE"
MISSING = "MISSING"

#: The outcome that must stop the run. A signature-only difference is not in
#: this set, on purpose: erasure makes it invisible to the linker.
BLOCKING = (REAL_DIFFERENCE, MISSING)

#: ASM's own packages. Anything RustCraft links against outside these is
#: resolved elsewhere and is not this comparison's business.
ASM_PREFIX = "org/objectweb/asm/"

_CONSTANT_POOL_TAGS = {
    7: ("class", 1), 9: ("field", 2), 10: ("method", 2), 11: ("interface_method", 2),
    12: ("name_and_type", 2),
}
_FIXED = {3: 4, 4: 4, 5: 8, 6: 8, 8: 2, 16: 2, 19: 2, 20: 2}
#: Tags whose entries straddle two constant pool slots.
_WIDE = (5, 6)


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with Path(path).open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


class ClassFormatError(ValueError):
    pass


def constant_pool(data: bytes) -> List[Tuple[int, tuple]]:
    if data[:4] != b"\xca\xfe\xba\xbe":
        raise ClassFormatError("not a classfile")
    count = struct.unpack_from(">H", data, 8)[0]
    pool: List[Tuple[int, tuple]] = [(0, ())]
    offset = 10
    index = 1
    while index < count:
        tag = data[offset]
        offset += 1
        if tag == 1:
            length = struct.unpack_from(">H", data, offset)[0]
            # Constant pool strings are MODIFIED UTF-8, which is not valid
            # UTF-8. Decoded as latin-1 so every byte maps to exactly one
            # character: equality comparisons stay correct, and the only
            # strings compared here (owners, member names, descriptors) are
            # ASCII anyway. Human-readable text comes from javap, not here.
            pool.append((tag, (data[offset + 2:offset + 2 + length].decode("latin-1"),)))
            offset += 2 + length
        elif tag in _CONSTANT_POOL_TAGS:
            width = _CONSTANT_POOL_TAGS[tag][1]
            pool.append((tag, tuple(struct.unpack_from(">" + "H" * width, data, offset))))
            offset += 2 * width
        elif tag in _FIXED:
            pool.append((tag, (data[offset:offset + _FIXED[tag]],)))
            offset += _FIXED[tag]
        elif tag in (15,):
            pool.append((tag, (data[offset], struct.unpack_from(">H", data, offset + 1)[0])))
            offset += 3
        elif tag == 18:
            pool.append((tag, struct.unpack_from(">HH", data, offset)))
            offset += 4
        else:
            raise ClassFormatError(f"unsupported constant pool tag {tag} at index {index}")
        if tag in _WIDE:
            # Long and double take two constant pool slots; the second is a
            # hole no reference can point at.
            pool.append((0, ()))
            index += 2
        else:
            index += 1
    return pool


def scan(data: bytes) -> List[dict]:
    """Every ASM owner and member reference the classfile will actually link
    against. Read out of the constant pool rather than the source, because the
    constant pool is what the JVM resolves at link time."""
    pool = constant_pool(data)

    def utf8(index: int) -> str:
        tag, value = pool[index]
        if tag != 1:
            raise ClassFormatError(f"constant {index} is not a Utf8")
        return value[0]

    def class_name(index: int) -> str:
        tag, value = pool[index]
        if tag != 7:
            raise ClassFormatError(f"constant {index} is not a Class")
        return utf8(value[0])

    found: Dict[Tuple[str, str, str, str], dict] = {}
    for index, (tag, value) in enumerate(pool):
        if tag == 7:
            owner = class_name(index)
            if owner.startswith(ASM_PREFIX):
                found.setdefault((owner, "class", "", ""), {
                    "owner": owner, "kind": "class", "name": None, "descriptor": None})
        elif tag in (9, 10, 11):
            owner = class_name(value[0])
            if not owner.startswith(ASM_PREFIX):
                continue
            nat = pool[value[1]]
            name, descriptor = utf8(nat[1][0]), utf8(nat[1][1])
            kind = {9: "field", 10: "method", 11: "interface_method"}[tag]
            found[(owner, kind, name, descriptor)] = {
                "owner": owner, "kind": kind, "name": name, "descriptor": descriptor}
    return [found[key] for key in sorted(found)]


_DESCRIPTOR = re.compile(r"^\s+descriptor: (?P<descriptor>\S+)\s*$")
_METHOD_NAME = re.compile(r"([A-Za-z0-9_$]+)\s*\(")
_FIELD_NAME = re.compile(r"([A-Za-z0-9_$]+)\s*(?:\[\s*\])*\s*$")
_COMPILED = "Compiled from"
_EXTENDS = re.compile(r"\bextends\s+([A-Za-z0-9_.$]+)")
_IMPLEMENTS = re.compile(r"\bimplements\s+([^{]+)")


def resolution_key(name: str, descriptor: str) -> str:
    """The part of a member's descriptor that identifies it to a CALLER.

    Overloads are separated by their parameter list, so the parameter list is
    the key and the return type is compared separately. Keying on the whole
    descriptor would hide the most important difference there is: a return type
    that changed is a real ABI break, and a lookup that keyed on the return type
    could never find it.
    """
    if descriptor.startswith("("):
        return descriptor[:descriptor.index(")") + 1]
    return "@field"


def member_name(declaration: str) -> str:
    """The simple name a member links under, or None when the declaration is
    not a separately nameable member (a static initializer, a constructor)."""
    body = declaration.strip().rstrip(";").strip()
    if " throws " in body:
        body = body.split(" throws ", 1)[0].strip()
    if "(" in body:
        found = _METHOD_NAME.search(body)
    else:
        found = _FIELD_NAME.search(body)
    if not found:
        return None
    name = found.group(1)
    if name == declaration.strip().split()[0] and "(" not in body:
        return None
    return name


def members(javap: str, jar: Path, owner: str) -> Optional[Dict[str, dict]]:
    """Every member a symbolic reference to `owner` can resolve to, keyed by
    (name, resolution key).

    The key is the pair the constant pool carries minus the return type, because
    that is the pair a caller identifies an overload by. The full JVM
    descriptor and the declaration text are both kept beside it so a return-type
    change and a generic-signature change can be told apart -- the distinction
    the whole classification turns on.

    Resolution follows the JVM's own order: the class itself, then each
    superclass, then each superinterface. A reference to an inherited member
    names only the subclass, so a lookup that stopped at the declared members
    would report inherited methods and fields as absent, which is a lookup bug
    and not a finding. Returns None when the type itself will not load.
    """
    if (str(jar), owner) not in _CACHE:
        _CACHE[(str(jar), owner)] = _load(javap, jar, owner)
    entry = _CACHE[(str(jar), owner)]
    if entry is None:
        return None
    declared, supers, interfaces = entry
    merged: Dict[str, dict] = dict(declared)
    for parent in list(supers) + list(interfaces):
        inherited = members(javap, jar, parent)
        if inherited is None:
            continue
        for key, value in inherited.items():
            merged.setdefault(key, value)
    return merged


_CACHE: Dict[Tuple[str, str], Optional[tuple]] = {}


def _load(javap: str, jar: Path, owner: str) -> Optional[tuple]:
    process = subprocess.run([javap, "-p", "-s", "-cp", str(jar), owner.replace("/", ".")],
                             capture_output=True, text=True)
    if process.returncode != 0:
        return None
    declared: Dict[str, dict] = {}
    supers: List[str] = []
    interfaces: List[str] = []
    pending: Optional[str] = None
    serial = 0
    for line in process.stdout.splitlines():
        if line.startswith(_COMPILED) or not line.strip() or line.strip() in ("{", "}"):
            continue
        if not line.startswith(" ") and line.rstrip().endswith("{"):
            # The declaration line is the only place the hierarchy appears, and
            # it is the only line at column zero.
            header = line.rstrip()[:-1].strip()
            match = _EXTENDS.search(header)
            if match:
                supers.append(match.group(1).strip().replace(".", "/"))
            for implemented in _IMPLEMENTS.findall(header):
                interfaces.append(implemented.strip().replace(".", "/"))
            continue
        descriptor = _DESCRIPTOR.match(line)
        if descriptor is not None:
            if pending is not None:
                value = declared[pending]
                value["descriptor"] = descriptor.group("descriptor")
                # The provisional key was unique only so that two overloads of
                # the same name could both wait for their own descriptor line.
                declared[value["name"] + resolution_key(value["name"], value["descriptor"])] = value
                del declared[pending]
                pending = None
            continue
        if not line.startswith("  ") or line.startswith("    "):
            continue
        body = line.strip()
        if "static {}" in body:
            pending = None
            continue
        name = member_name(body)
        if name is None:
            pending = None
            continue
        serial += 1
        pending = f"pending#{serial}"
        declared[pending] = {"name": name, "descriptor": None, "declaration": body}
    return {key: value for key, value in declared.items() if not key.startswith("pending#")}, \
        supers, interfaces


def compare(classes: Path, baseline_jar: Path, candidate_jar: Path, javap: str) -> dict:
    references: List[dict] = []
    seen = set()
    for path in sorted(classes.rglob("*.class")):
        for row in scan(path.read_bytes()):
            key = (row["owner"], row["kind"], row["name"], row["descriptor"])
            if key in seen:
                continue
            seen.add(key)
            references.append(row)

    owners = sorted({row["owner"] for row in references})
    by_owner = {owner: (members(javap, baseline_jar, owner),
                        members(javap, candidate_jar, owner)) for owner in owners}

    results = []
    for row in references:
        if row["name"] == "<clinit>":
            # A class initializer is not a separately linkable symbol: javac
            # emits it or not according to the source, and nothing invokes it
            # by name.
            continue
        base, cand = by_owner[row["owner"]]
        entry = dict(row)
        if base is None or cand is None:
            # A whole type that will not load makes every reference to it a
            # link failure, whatever its members look like.
            entry["classification"] = MISSING
            entry["detail"] = ("owner type absent from the candidate jar" if base is not None else
                               "owner type absent from the baseline jar" if cand is not None else
                               f"owner type {row['owner']} absent from both jars")
            results.append(entry)
            continue
        if row["kind"] == "class":
            # A class reference links on existence and hierarchy only. Generic
            # differences inside the class are covered by its own members.
            entry["classification"] = IDENTICAL
            entry["detail"] = "type present in both jars"
            results.append(entry)
            continue
        # The join key is the name plus parameter list, exactly what a caller
        # uses to pick an overload, so a changed return type is still a match
        # and can be reported as the break it is. javap prints a constructor
        # under the type's simple name, so `<init>` is resolved to the name the
        # jar actually declares it under.
        simple = row["owner"].rsplit("/", 1)[-1].rsplit("$", 1)[-1]
        name = simple if row["name"] == "<init>" else row["name"]
        key = name + resolution_key(name, row["descriptor"])
        base_row, cand_row = base.get(key), cand.get(key)
        if base_row is None and cand_row is None:
            entry["classification"] = MISSING
            entry["detail"] = (f"neither jar declares {name} with descriptor "
                               f"{row['descriptor']} on {row['owner']} or any supertype")
            results.append(entry)
            continue
        if base_row is None or cand_row is None:
            entry["classification"] = MISSING
            entry["detail"] = ("member absent from the baseline jar" if base_row is None else
                               "member absent from the candidate jar; same-name members there: "
                               + (", ".join(sorted(k[1] for k in cand if k[0] == name)) or "none"))
            results.append(entry)
            continue
        if base_row["descriptor"] != cand_row["descriptor"]:
            # Same name, same parameters, different descriptor. The JVM will
            # resolve this at link time to the wrong member or to none.
            entry["classification"] = REAL_DIFFERENCE
            entry["detail"] = f"{base_row['descriptor']} (baseline) != {cand_row['descriptor']} (candidate)"
        elif base_row["declaration"] != cand_row["declaration"]:
            entry["classification"] = SIGNATURE_ONLY
            entry["detail"] = f"{cand_row['declaration']!r} vs {base_row['declaration']!r}"
        else:
            entry["classification"] = IDENTICAL
            entry["detail"] = base_row["declaration"]
        results.append(entry)

    counts = {name: sum(1 for r in results if r["classification"] == name)
              for name in (IDENTICAL, SIGNATURE_ONLY, REAL_DIFFERENCE, MISSING)}
    blocking = [r for r in results if r["classification"] in BLOCKING]
    return {
        "schema": SCHEMA,
        "baseline_asm": str(baseline_jar), "baseline_asm_sha256": sha256(baseline_jar),
        "candidate_asm": str(candidate_jar), "candidate_asm_sha256": sha256(candidate_jar),
        "classes_scanned": sorted({p.relative_to(classes).as_posix() for p in classes.rglob("*.class")}),
        "referenced_symbols": len(results),
        "counts": counts,
        "verdict": "REQUIRES_ULTRA_REVIEW_ASM_ABI" if blocking else "RUNTIME_ABI_EQUIVALENT",
        "blocking": blocking,
        "symbols": results,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--classes", type=Path, required=True,
                        help="RustCraft classes compiled against the baseline jar")
    parser.add_argument("--baseline-asm", type=Path, required=True)
    parser.add_argument("--candidate-asm", type=Path, required=True)
    parser.add_argument("--javap", default="javap")
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    for path in (args.baseline_asm, args.candidate_asm):
        if not path.is_file():
            sys.stderr.write(f"missing ASM jar: {path}\n")
            return 2
    report = compare(args.classes, args.baseline_asm, args.candidate_asm, args.javap)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"verdict": report["verdict"], "referenced_symbols": report["referenced_symbols"],
                      "counts": report["counts"]}, indent=2))
    return 1 if report["verdict"] != "RUNTIME_ABI_EQUIVALENT" else 0


if __name__ == "__main__":
    raise SystemExit(main())
