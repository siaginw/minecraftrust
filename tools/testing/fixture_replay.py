#!/usr/bin/env python3
"""Validate immutable synthetic fixtures and replay through an offline native CLI.

Uses only the standard library. The small schema evaluator implements the exact
keywords used by the accepted v1 schema and rejects unknown keywords.
"""
import argparse
import base64
import copy
import hashlib
import json
from pathlib import Path
import re
import struct
import subprocess
import sys
import tempfile

from packet_decoder import DecodeError, decode_packet, unpack_cells

ROOT = Path(__file__).resolve().parents[2]
CORPUS = ROOT / "tests/fixtures/issue1-capture"
SCHEMA = ROOT / "docs/schemas/chunk-packet-fixture-v1.schema.json"


class FixtureError(ValueError):
    pass


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def _pairs(pairs):
    obj = {}
    for key, value in pairs:
        if key in obj:
            raise FixtureError("duplicate JSON key")
        obj[key] = value
    return obj


def _integer(token):
    if token == "-0":
        raise FixtureError("negative zero")
    return int(token)


def _not_integer(token):
    raise FixtureError("noninteger JSON number")


def parse_json(data):
    if data.startswith(b"\xef\xbb\xbf"):
        raise FixtureError("JSON BOM")
    try:
        value = json.loads(data.decode("utf-8"), object_pairs_hook=_pairs,
                           parse_int=_integer, parse_float=_not_integer,
                           parse_constant=_not_integer)
        canonical(value)  # Also rejects lone surrogate code points everywhere.
        return value
    except (UnicodeError, json.JSONDecodeError) as exc:
        raise FixtureError("invalid UTF-8 JSON") from exc


def canonical(value):
    if value is None:
        return b"null"
    if type(value) is bool:
        return b"true" if value else b"false"
    if type(value) is int:
        return str(value).encode("ascii")
    if isinstance(value, str):
        out = '"'
        for ch in value:
            point = ord(ch)
            if 0xD800 <= point <= 0xDFFF:
                raise FixtureError("lone surrogate")
            out += ('\\"' if ch == '"' else '\\\\' if ch == '\\' else
                    "\\u%04x" % point if point < 32 else ch)
        return (out + '"').encode("utf-8")
    if isinstance(value, list):
        return b"[" + b",".join(canonical(item) for item in value) + b"]"
    if isinstance(value, dict):
        return b"{" + b",".join(canonical(key) + b":" + canonical(value[key])
                                  for key in sorted(value)) + b"}"
    raise FixtureError("unsupported canonical JSON value")


def fixture_hash(fixture):
    value = copy.deepcopy(fixture)
    del value["hashes"]["fixtureSha256"]
    return sha256(canonical(value))


def check_schema(value, schema, root=None, path="fixture"):
    root = root or schema
    keywords = {"$schema", "$defs", "title", "description", "$ref", "type", "const", "enum",
                "minimum", "maximum", "minLength", "maxLength", "pattern", "minItems", "maxItems",
                "items", "required", "properties", "additionalProperties", "allOf", "oneOf", "if", "then", "else"}
    if set(schema) - keywords:
        raise FixtureError("unsupported schema keywords")
    if "$ref" in schema:
        ref = schema["$ref"]
        if not ref.startswith("#/$defs/"):
            raise FixtureError("unsupported schema reference")
        check_schema(value, root["$defs"][ref[8:]], root, path)
    types = {"null": lambda x: x is None, "object": lambda x: isinstance(x, dict),
             "array": lambda x: isinstance(x, list), "string": lambda x: isinstance(x, str),
             "integer": lambda x: type(x) is int, "boolean": lambda x: type(x) is bool}
    if "type" in schema:
        names = schema["type"] if isinstance(schema["type"], list) else [schema["type"]]
        if not any(types[name](value) for name in names):
            raise FixtureError(path + ": incorrect type")
    if "const" in schema and (type(value) is not type(schema["const"]) or value != schema["const"]):
        raise FixtureError(path + ": incorrect constant")
    if "enum" in schema and value not in schema["enum"]:
        raise FixtureError(path + ": incorrect enum")
    if type(value) is int:
        if value < schema.get("minimum", value) or value > schema.get("maximum", value):
            raise FixtureError(path + ": integer out of range")
    if isinstance(value, str):
        if len(value) < schema.get("minLength", 0) or len(value) > schema.get("maxLength", len(value)):
            raise FixtureError(path + ": string length")
        if "pattern" in schema and re.fullmatch(schema["pattern"], value) is None:
            raise FixtureError(path + ": string pattern")
    if isinstance(value, list):
        if len(value) < schema.get("minItems", 0) or len(value) > schema.get("maxItems", len(value)):
            raise FixtureError(path + ": array length")
        if "items" in schema:
            for index, item in enumerate(value):
                check_schema(item, schema["items"], root, path + "[%d]" % index)
    if isinstance(value, dict):
        if set(schema.get("required", [])) - set(value):
            raise FixtureError(path + ": missing field")
        properties = schema.get("properties", {})
        if schema.get("additionalProperties") is False and set(value) - set(properties):
            raise FixtureError(path + ": unknown field")
        for key in set(value) & set(properties):
            check_schema(value[key], properties[key], root, path + "." + key)
    for part in schema.get("allOf", []):
        check_schema(value, part, root, path)
    if "oneOf" in schema:
        successes = 0
        for part in schema["oneOf"]:
            try:
                check_schema(value, part, root, path)
                successes += 1
            except FixtureError:
                pass
        if successes != 1:
            raise FixtureError(path + ": expected exactly one schema alternative")
    if "if" in schema:
        try:
            check_schema(value, schema["if"], root, path)
            matched = True
        except FixtureError:
            matched = False
        branch = "then" if matched else "else"
        if branch in schema:
            check_schema(value, schema[branch], root, path)


def binary(record, expected=None):
    try:
        data = base64.b64decode(record["data"], validate=True)
    except (ValueError, TypeError) as exc:
        raise FixtureError("invalid base64") from exc
    if base64.b64encode(data).decode("ascii") != record["data"]:
        raise FixtureError("noncanonical base64")
    if len(data) != record["byteCount"] or sha256(data) != record["sha256"]:
        raise FixtureError("binary count/digest mismatch")
    if expected is not None and len(data) != expected:
        raise FixtureError("binary length mismatch")
    return data


def verify_artifacts(value, root=ROOT):
    if isinstance(value, dict):
        if set(value) == {"name", "version", "sha256"}:
            name = value["name"]
            if any(part in ("", ".", "..") for part in name.split("/")):
                raise FixtureError("invalid artifact path")
            path = (root / name).resolve()
            if not path.is_relative_to(root.resolve()):
                raise FixtureError("artifact escapes root")
            if not path.is_file():
                raise FixtureError("MISSING_EXTERNAL_ARTIFACT: " + name)
            if sha256(path.read_bytes()) != value["sha256"]:
                raise FixtureError("artifact digest mismatch: " + name)
        for child in value.values():
            verify_artifacts(child, root)
    elif isinstance(value, list):
        for child in value:
            verify_artifacts(child, root)


def compare_decoded(fixture, packet):
    for actual in packet.sections:
        section = fixture["sections"][actual.y]
        if section is None or actual.states != tuple(section["logicalStates"]):
            raise FixtureError("decoded logical-state mismatch")
        if actual.block_light != binary(section["blockLight"], 2048):
            raise FixtureError("decoded block-light mismatch")
        expected_sky = binary(section["skyLight"], 2048) if fixture["skylight"] else None
        if actual.sky_light != expected_sky:
            raise FixtureError("decoded sky-light mismatch")
    expected_biomes = binary(fixture["biomes"], 256) if fixture["fullChunk"] else None
    if packet.biomes != expected_biomes:
        raise FixtureError("decoded biome mismatch")


def validate_fixture(fixture, resolve_artifacts=True):
    check_schema(fixture, parse_json(SCHEMA.read_bytes()))
    if fixture["captureKind"] != "SYNTHETIC":
        raise FixtureError("LIVE_FORGE ownership validation is not implemented")
    if fixture_hash(fixture) != fixture["hashes"]["fixtureSha256"]:
        raise FixtureError("fixture digest mismatch")
    for field in (fixture["chunk"]["generationId"], fixture["provenance"]["sequenceId"],
                  fixture["provenance"]["threadId"], fixture["provenance"]["generator"]["seed"]):
        if int(field) > 0xFFFFFFFFFFFFFFFF:
            raise FixtureError("identifier exceeds u64")
    if resolve_artifacts:
        verify_artifacts(fixture)
    registry = fixture["registry"]
    export = parse_json((ROOT / registry["export"]["name"]).read_bytes())
    if export != {"format": "rustcraft-synthetic-registry-range-v1", "firstStateId": 0,
                  "lastStateId": registry["entryCount"] - 1, "airStateIds": [0],
                  "globalPaletteBits": registry["globalPaletteBits"]}:
        raise FixtureError("unsupported synthetic registry export")
    selected = fixture["encoderSelectedMask"]
    if selected & ~fixture["requestedMask"] or fixture["emittedMask"] != selected:
        raise FixtureError("selection/emitted-mask mismatch")
    for y, section in enumerate(fixture["sections"]):
        if section is None:
            if selected & (1 << y):
                raise FixtureError("selected section is missing")
            continue
        if section["y"] != y:
            raise FixtureError("section index mismatch")
        states = section["logicalStates"]
        if any(state >= registry["entryCount"] for state in states):
            raise FixtureError("logical state absent from registry")
        if section["nonAirCount"] != sum(state != 0 for state in states):
            raise FixtureError("synthetic non-air count mismatch")
        binary(section["blockLight"], 2048)
        if fixture["skylight"]:
            binary(section["skyLight"], 2048)
        palette = section["sourcePalette"]
        if palette is not None:
            entries = None if palette["mode"] == "GLOBAL" else palette["entries"]
            if any(state >= registry["entryCount"] for state in palette["entries"]):
                raise FixtureError("source palette state absent from registry")
            if entries is not None and len(entries) > (1 << palette["bitsPerEntry"]):
                raise FixtureError("source palette too large")
            if tuple(states) != unpack_cells([int(word, 16) for word in palette["packedWords"]],
                                             palette["bitsPerEntry"], entries):
                raise FixtureError("source palette/logical-state mismatch")
    payload = binary(fixture["payload"])
    if fixture["bytesWritten"] != len(payload) or fixture["consumedByteCount"] != len(payload):
        raise FixtureError("payload count mismatch")
    packet = decode_packet(payload, selected, fixture["fullChunk"], fixture["skylight"], registry["globalPaletteBits"])
    if fixture["decodedSectionCount"] != len(packet.sections):
        raise FixtureError("decoded count mismatch")
    compare_decoded(fixture, packet)
    reference = fixture["javaReference"]
    if reference is not None:
        reference_payload = binary(reference["payload"])
        if reference["emittedMask"] != selected or reference["bytesWritten"] != len(reference_payload) or reference["consumedByteCount"] != len(reference_payload):
            raise FixtureError("Java reference mask/count mismatch")
        java_packet = decode_packet(reference_payload, selected, fixture["fullChunk"], fixture["skylight"], registry["globalPaletteBits"])
        if reference["decodedSectionCount"] != len(java_packet.sections):
            raise FixtureError("Java decoded count mismatch")
        compare_decoded(fixture, java_packet)
    return packet


def snapshot_bytes(fixture, changes=None):
    """Build the separate RCSNAP01 offline transport; never amend a fixture."""
    changes = changes or {}
    allowed = {"acceptedMask", "incarnationEnd", "firstStateId", "storage", "epochEnd",
               "captureThread", "scope", "capacity"}
    if set(changes) - allowed:
        raise FixtureError("unsupported scenario mutation")
    mask = changes.get("acceptedMask", fixture["encoderSelectedMask"])
    flags = int(fixture["fullChunk"]) | (int(fixture["skylight"]) << 1)
    chunk = fixture["chunk"]
    header = struct.pack(">8sHBBBBHiiiQHHQQQQQQQ32s", b"RCSNAP01", 1, flags,
                         changes.get("storage", 1), fixture["registry"]["globalPaletteBits"],
                         changes.get("scope", 1), 0, chunk["dimension"], chunk["x"], chunk["z"],
                         int(chunk["generationId"]), fixture["requestedMask"], mask,
                         int(fixture["provenance"]["sequenceId"]), 1, changes.get("captureThread", 1),
                         0, changes.get("epochEnd", 0), 1, changes.get("incarnationEnd", 1),
                         bytes.fromhex(fixture["provenance"]["ownershipProtocol"]["sha256"]))
    if len(header) != 128:
        raise FixtureError("internal transport header size mismatch")
    included = [(y, section) for y, section in enumerate(fixture["sections"])
                if mask & (1 << y) and section is not None]
    result = bytearray(header + struct.pack(">H", len(included)))
    for index, (y, section) in enumerate(included):
        states = list(section["logicalStates"])
        if index == 0 and "firstStateId" in changes:
            states[0] = changes["firstStateId"]
        result += struct.pack(">BBH", y, 0, section["nonAirCount"])
        result += struct.pack(">4096I", *states)
        result += binary(section["blockLight"], 2048)
        if fixture["skylight"]:
            result += binary(section["skyLight"], 2048)
    if fixture["fullChunk"]:
        result += binary(fixture["biomes"], 256)
    return bytes(result)


def native_replay(executable, fixture, changes=None, expected=None):
    with tempfile.TemporaryDirectory(prefix="rustcraft-synthetic-replay-") as directory:
        input_path = Path(directory) / "input.bin"
        output_path = Path(directory) / "output.bin"
        input_path.write_bytes(snapshot_bytes(fixture, changes))
        command = [str(executable), str(input_path), str(output_path)]
        if changes and "capacity" in changes:
            command.append(str(changes["capacity"]))
        process = subprocess.run(command, capture_output=True, text=True, timeout=30)
        if process.returncode != 0:
            raise FixtureError("native replay process failed: " + process.stderr[-2000:])
        try:
            result = parse_json(process.stdout.encode("utf-8"))
        except FixtureError as exc:
            raise FixtureError("native replay emitted invalid result") from exc
        if expected is not None:
            if result.get("status") != "REJECT" or result.get("reason") != expected or output_path.exists():
                raise FixtureError("incorrect rejection or published failure bytes: " + str(result))
            return {"status": "PASS", "expectedRejection": expected}
        if result.get("status") != "PASS" or not output_path.is_file():
            raise FixtureError("native replay did not succeed: " + str(result))
        payload = output_path.read_bytes()
        count, mask = result.get("bytes_written"), result.get("emitted_mask")
        if type(count) is not int or count != len(payload) or mask != fixture["emittedMask"]:
            raise FixtureError("native result mask/count mismatch")
        packed = int(result["v2_result"])
        if packed != ((1 << 62) | (count << 16) | mask):
            raise FixtureError("native V2 combined result mismatch")
        decoded = decode_packet(payload, mask, fixture["fullChunk"], fixture["skylight"], fixture["registry"]["globalPaletteBits"])
        compare_decoded(fixture, decoded)
        return {"status": "PASS", "bytesWritten": count, "emittedMask": mask,
                "decodedSections": len(decoded.sections), "consumedByteCount": decoded.consumed,
                "payloadSha256": sha256(payload), "v2Result": str(packed),
                "semanticEquality": True, "exactByteEquality": payload == binary(fixture["payload"])}


def run_corpus(executable=None):
    manifest = parse_json((CORPUS / "corpus.json").read_bytes())
    check_schema(manifest, parse_json((CORPUS / "corpus.schema.json").read_bytes()))
    fixtures, events, records = {}, set(), []
    for entry in manifest["fixtures"]:
        filename = entry["file"]
        if filename in fixtures:
            raise FixtureError("duplicate fixture filename")
        fixture = parse_json((CORPUS / filename).read_bytes())
        validate_fixture(fixture)
        if fixture["hashes"]["fixtureSha256"] != entry["fixtureSha256"]:
            raise FixtureError("corpus fixture hash mismatch")
        if fixture["eventId"] in events:
            raise FixtureError("duplicate event identity")
        events.add(fixture["eventId"])
        fixtures[filename] = fixture
        record = {"fixture": filename, "status": "PASS", "fixtureSha256": entry["fixtureSha256"]}
        if executable:
            record["nativeReplay"] = native_replay(executable, fixture)
        records.append(record)
    if not fixtures:
        raise FixtureError("INCOMPLETE: empty synthetic fixture corpus")
    rejections = []
    for scenario in manifest["rejections"]:
        if scenario["fixture"] not in fixtures:
            raise FixtureError("rejection references missing fixture")
        if executable:
            result = native_replay(executable, fixtures[scenario["fixture"]], scenario["changes"], scenario["expected"])
            rejections.append(dict(name=scenario["name"], **result))
    for sequence in manifest["sequences"]:
        if len(sequence["fixtures"]) != len(sequence["masks"]):
            raise FixtureError("sequence length mismatch")
        for filename, mask in zip(sequence["fixtures"], sequence["masks"]):
            if filename not in fixtures or fixtures[filename]["emittedMask"] != mask:
                raise FixtureError("sequence expected-mask mismatch")
        if sequence["name"] == "direct-light-change":
            before, after = (fixtures[name] for name in sequence["fixtures"])
            if before["sections"][0]["logicalStates"] != after["sections"][0]["logicalStates"] or before["sections"][0]["blockLight"] == after["sections"][0]["blockLight"]:
                raise FixtureError("light transition was not isolated")
        if sequence["name"] == "biome-change":
            before, after = (fixtures[name] for name in sequence["fixtures"])
            if before["sections"] != after["sections"] or before["biomes"] == after["biomes"]:
                raise FixtureError("biome transition was not isolated")
    return {"status": "PASS" if executable else "INCOMPLETE", "captureKind": "SYNTHETIC",
            "nativeExecutableSha256": sha256(Path(executable).read_bytes()) if executable else None,
            "fixtureCount": len(fixtures), "nativeReplayCount": len(fixtures) if executable else 0,
            "rejectionCount": len(rejections), "sequenceCount": len(manifest["sequences"]),
            "fixtures": records, "rejections": rejections,
            "exclusions": [] if executable else [{"reason": "NATIVE_REPLAY_NOT_RUN"}]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--native-replay", type=Path)
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    try:
        report = run_corpus(args.native_replay)
    except (FixtureError, DecodeError, OSError, subprocess.SubprocessError, ValueError) as exc:
        report = {"status": "FAIL", "reason": str(exc)}
    encoded = json.dumps(report, indent=2) + "\n"
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(encoded, encoding="utf-8")
    print(encoded, end="")
    return 0 if report["status"] == "PASS" else 2


if __name__ == "__main__":
    sys.exit(main())
