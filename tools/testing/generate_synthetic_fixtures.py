#!/usr/bin/env python3
"""Explicit fixture maintenance command; never invoked by validation/replay.

The expected synthetic wire uses a Python big-integer bitstream, independent of
Rust packing/selection helpers. This is a synthetic reference, not Minecraft.
"""
import base64
import json
from pathlib import Path

from fixture_replay import CORPUS, ROOT, canonical, fixture_hash, sha256


def record(data):
    return {"encoding": "base64", "data": base64.b64encode(data).decode("ascii"),
            "byteCount": len(data), "sha256": sha256(data)}


def artifact(name):
    return {"name": name, "version": "1", "sha256": sha256((ROOT / name).read_bytes())}


def varint(value):
    output = bytearray()
    while value >= 128:
        output.append((value & 127) | 128)
        value >>= 7
    output.append(value)
    return bytes(output)


def reference_section(section, skylight, global_bits=14):
    states = section["logicalStates"]
    palette = [0] + list(dict.fromkeys(state for state in states if state != 0))
    local = len(palette) <= 256
    bits = max(4, (len(palette) - 1).bit_length()) if local else global_bits
    lookup = {state: index for index, state in enumerate(palette)}
    values = [lookup[state] for state in states] if local else states
    # One arbitrary-precision bitstring; deliberately not Rust's per-word packer.
    packed = sum(value << (index * bits) for index, value in enumerate(values))
    words = [(packed >> start) & ((1 << 64) - 1) for start in range(0, 4096 * bits, 64)]
    result = bytes([bits]) + varint(len(palette) if local else 0)
    if local:
        result += b"".join(varint(state) for state in palette)
    result += varint(len(words)) + b"".join(word.to_bytes(8, "big") for word in words)
    result += base64.b64decode(section["blockLight"]["data"])
    if skylight:
        result += base64.b64decode(section["skyLight"]["data"])
    return result


def section(y, kind="one", sky=False, light=17):
    if kind == "empty":
        states = [0] * 4096
    elif kind == "local5":
        states = [index % 17 for index in range(4096)]
    elif kind == "local8":
        states = [index % 129 for index in range(4096)]
    elif kind == "global14":
        states = [8192 + index % 300 for index in range(4096)]
    else:
        states = [y + 1] * 4096
    return {"y": y, "logicalStates": states, "nonAirCount": sum(state != 0 for state in states),
            "tickRefCount": 0, "sourcePalette": None, "blockLight": record(bytes([light]) * 2048),
            "skyLight": record(bytes([255 - light]) * 2048) if sky else None}


def make_fixture(name, sequence, selected, kinds, full=False, sky=False, light=17, biome=4):
    sections = [section(y, kinds[y], sky, light) if y in kinds else None for y in range(16)]
    payload = b"".join(reference_section(sections[y], sky) for y in range(16) if selected & (1 << y))
    if full:
        payload += bytes([biome]) * 256
    generator = artifact("tools/testing/generate_synthetic_fixtures.py")
    document = {
        "format": "rustcraft-chunk-packet-fixture", "schemaVersion": 1,
        "eventId": "synthetic.issue1." + name, "captureKind": "SYNTHETIC",
        "runtime": {"minecraftVersion": "1.12.2", "protocolVersion": 340, "forgeVersion": None,
                    "javaRuntime": None, "orderedClasspath": [], "orderedMods": [],
                    "orderedCoremods": [], "orderedConfigs": []},
        "registry": {"kind": "SYNTHETIC", "export": artifact("tests/fixtures/issue1-capture/registry.json"),
                     "entryCount": 16384, "globalPaletteBits": 14},
        "chunk": {"dimension": 0, "x": -3, "z": 7, "generationId": "1"},
        "requestedMask": 65535, "encoderSelectedMask": selected, "emittedMask": selected,
        "fullChunk": full, "skylight": sky, "sections": sections,
        "biomes": record(bytes([biome]) * 256) if full else None,
        "payload": record(payload), "bytesWritten": len(payload),
        "decodedSectionCount": selected.bit_count(), "consumedByteCount": len(payload),
        "javaReference": None,
        "provenance": {"sequenceId": str(sequence), "threadId": "1", "captureImplementation": generator,
                       "ownershipProtocol": artifact("tests/fixtures/issue1-capture/SYNTHETIC-PROTOCOL.txt"),
                       "ownershipEvidence": [], "sourceWriter": None, "encoderBuild": generator,
                       "independentDecoder": artifact("tools/testing/packet_decoder.py"),
                       "generator": {"implementation": generator, "seed": "340"}},
        "hashes": {"algorithm": "SHA-256", "canonicalization": "rustcraft-fixture-json-v1", "fixtureSha256": ""}}
    document["hashes"]["fixtureSha256"] = fixture_hash(document)
    return document


def generate():
    specifications = [
        ("mask-zero", 0, {}, {}),
        ("one-section", 1, {0: "one"}, {}),
        ("sparse-high-sky", 0x8000, {15: "one"}, {"sky": True, "full": True}),
        ("mask-001f", 0x1F, {y: "one" for y in range(5)}, {}),
        ("sparse-8421", 0x8421, {y: "one" for y in (0, 5, 10, 15)}, {"sky": True}),
        ("palette-local5", 1, {0: "local5"}, {}),
        ("palette-local8", 1, {0: "local8"}, {"sky": True}),
        ("palette-global14", 1, {0: "global14"}, {"full": True}),
        ("transition-empty", 0, {2: "empty"}, {"full": True}),
        ("transition-nonempty", 4, {2: "one"}, {"full": True}),
        ("transition-cleared", 0, {2: "empty"}, {"full": True}),
        ("partial-empty-selected", 4, {2: "empty"}, {}),
        ("direct-light-before", 1, {0: "one"}, {"sky": True, "light": 17}),
        ("direct-light-after", 1, {0: "one"}, {"sky": True, "light": 201}),
        ("biome-before", 0, {}, {"full": True, "biome": 4}),
        ("biome-after", 0, {}, {"full": True, "biome": 42}),
    ]
    fixtures = []
    for sequence, (name, selected, kinds, options) in enumerate(specifications, 1):
        fixture = make_fixture(name, sequence, selected, kinds, **options)
        filename = name + ".fixture.json"
        (CORPUS / filename).write_bytes(canonical(fixture) + b"\n")
        fixtures.append({"file": filename, "fixtureSha256": fixture["hashes"]["fixtureSha256"]})
    scenarios = [
        {"name": "historical-003f-missing-section", "fixture": "mask-001f.fixture.json", "changes": {"acceptedMask": 63}, "expected": "FALLBACK_MISSING_SECTION"},
        {"name": "stale-incarnation", "fixture": "one-section.fixture.json", "changes": {"incarnationEnd": 2}, "expected": "FALLBACK_CHUNK_REPLACED"},
        {"name": "extended-id", "fixture": "one-section.fixture.json", "changes": {"firstStateId": 65536}, "expected": "FALLBACK_EXTENDED_ID"},
        {"name": "jeid-storage", "fixture": "one-section.fixture.json", "changes": {"storage": 3}, "expected": "FALLBACK_UNSUPPORTED_STORAGE"},
        {"name": "changed-epoch", "fixture": "one-section.fixture.json", "changes": {"epochEnd": 1}, "expected": "FALLBACK_CAPTURE_CHANGED"},
        {"name": "off-thread", "fixture": "one-section.fixture.json", "changes": {"captureThread": 2}, "expected": "FALLBACK_OFF_THREAD"},
        {"name": "unknown-writer-scope", "fixture": "one-section.fixture.json", "changes": {"scope": 2}, "expected": "FALLBACK_UNKNOWN_WRITER"},
        {"name": "capacity", "fixture": "one-section.fixture.json", "changes": {"capacity": 1}, "expected": "FALLBACK_CAPACITY"},
    ]
    manifest = {"format": "rustcraft-synthetic-capture-corpus", "version": 1, "captureKind": "SYNTHETIC",
                "fixtures": fixtures, "rejections": scenarios,
                "sequences": [{"name": "empty-nonempty-empty", "fixtures": ["transition-empty.fixture.json", "transition-nonempty.fixture.json", "transition-cleared.fixture.json"], "masks": [0, 4, 0]},
                              {"name": "direct-light-change", "fixtures": ["direct-light-before.fixture.json", "direct-light-after.fixture.json"], "masks": [1, 1]},
                              {"name": "biome-change", "fixtures": ["biome-before.fixture.json", "biome-after.fixture.json"], "masks": [0, 0]}]}
    (CORPUS / "corpus.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    generate()
