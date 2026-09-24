"""Strict real Clean-Forge offline event comparison and local fixture export.

LIVE_FORGE remains unsupported. Runtime receipts, transformed classes, source
inputs and binaries are verified; owned-input, Java packet and V2 bytes are
decoded independently. No proprietary binary is copied into the repository.
"""
import argparse
import base64
import copy
import json
from pathlib import Path
import re
import struct
import subprocess
import zipfile

from fixture_replay import (FixtureError, SCHEMA, binary, canonical, check_schema,
                            compare_decoded, fixture_hash, parse_json, sha256)
from packet_decoder import DecodeError, Reader, decode_packet, unpack_cells

ROOT = Path(__file__).resolve().parents[2]
PROFILE = "FORGE_2860_SERVER_TRANSFORMED_FML_INITIALIZED_OFFLINE_V1"
CONTRACT = "clean-forge-exclusive-owned-graph-v1"
ACCEPTED = {
    "zero-sections", "one-section", "sparse-high", "terrain-001f", "sky-off",
    "empty-before", "empty-to-nonempty", "nonempty-to-empty", "local-palette-expansion",
    "local-to-global", "light-before", "block-light-change", "sky-light-change", "biome-change",
    "storage-before", "storage-replacement-between-events", "partial-present-empty",
    "present-empty-full", "inplace-empty-to-nonempty", "inplace-nonempty-to-empty", "reloaded-current",
}
REJECTED = {
    **{name: "FALLBACK_CAPTURE_CHANGED" for name in
       ("during-states", "during-light", "during-sky", "during-biome", "during-storage", "during-remove")},
    **{name: "FALLBACK_CHUNK_REPLACED" for name in
       ("during-replace", "stale-generation", "same-coordinate-replacement", "unloaded", "reload-stale-handle")},
    "during-unload": "FALLBACK_SOURCE_EXCEPTION", "during-extended": "FALLBACK_EXTENDED_ID",
    "during-unknown": "FALLBACK_UNKNOWN_WRITER", "during-async": "FALLBACK_ASYNC_WRITER",
    "tile-entity-unqualified": "FALLBACK_TE_UNQUALIFIED", "off-thread": "FALLBACK_OFF_THREAD",
    "native-missing-selected-section": "V2_-4", "native-extended-id": "V2_-6", "native-capacity": "V2_-5",
}


class Incomplete(FixtureError):
    pass


def require(value, message):
    if not value:
        raise FixtureError(message)


def decode64(value):
    try:
        data = base64.b64decode(value, validate=True)
    except (TypeError, ValueError) as error:
        raise FixtureError("invalid base64") from error
    require(base64.b64encode(data).decode("ascii") == value, "noncanonical base64")
    return data


def record(data):
    return {"encoding": "base64", "data": base64.b64encode(data).decode("ascii"),
            "byteCount": len(data), "sha256": sha256(data)}


def transport(data):
    r = Reader(data)
    values = struct.unpack(">8sHBBBBHiiiQHHQQQQQQQ32s", r.take(128))
    (magic, version, flags, storage, bits, scope, reserved, dimension, x, z,
     generation, requested, mask, event, owner, thread, start, end, incarnation,
     incarnation_end, provenance) = values
    require(magic == b"RCSNAP01" and version == 1 and flags < 4 and reserved == 0, "transport header")
    require(scope == 2, "real fixture must carry offline Forge scope 2")
    require(storage == 1 and 9 <= bits <= 16, "unsupported real storage")
    require(all(0 < n <= (1 << 63) - 1 for n in (generation, event, owner, incarnation)), "positive identity")
    require(thread == owner and incarnation == incarnation_end, "thread/incarnation mismatch")
    require(0 <= start <= (1 << 63) - 1 and start == end, "capture guards mismatch")
    require(mask & ~requested == 0, "selected mask outside request")
    count = int.from_bytes(r.take(2), "big")
    require(count == mask.bit_count(), "transport section count")
    sections = [None] * 16
    full, sky = bool(flags & 1), bool(flags & 2)
    require(full == (requested == 65535), "Forge constructor full/requested mismatch")
    for y in range(16):
        if not mask & (1 << y):
            continue
        actual, zero, non_air = struct.unpack(">BBH", r.take(4))
        require(actual == y and zero == 0, "transport section order")
        states = list(struct.unpack(">4096I", r.take(4096 * 4)))
        require(all(value <= 65535 for value in states), "extended state cannot narrow")
        require(non_air == sum(value != 0 for value in states), "transport refcount")
        require(not full or non_air > 0, "empty section in full packet")
        sections[y] = {"y": y, "logicalStates": states, "nonAirCount": non_air,
                       "blockLight": record(r.take(2048)), "skyLight": record(r.take(2048)) if sky else None}
    biomes = record(r.take(256)) if full else None
    require(r.offset == len(data), "transport trailing bytes")
    return {"dimension": dimension, "x": x, "z": z, "generation": generation,
            "requested": requested, "mask": mask, "event": event, "owner": owner,
            "epoch": start, "incarnation": incarnation, "provenance": provenance.hex(),
            "full": full, "sky": sky, "bits": bits, "sections": sections, "biomes": biomes}


def java_packet(data):
    r = Reader(data)
    x, z = struct.unpack(">ii", r.take(8))
    flag = r.take(1)[0]
    require(flag in (0, 1), "Java fullChunk is not boolean")
    mask = r.varint()
    require(mask <= 65535, "Java mask outside u16")
    payload = r.take(r.varint())
    require(r.varint() == 0, "TE tags outside this qualified empty-TE contract")
    require(r.offset == len(data), "Java packet trailing bytes")
    return x, z, bool(flag), mask, payload


def decode_v2(value, payload):
    require(isinstance(value, str) and re.fullmatch(r"[1-9][0-9]*", value), "V2 decimal success required")
    raw = int(value)
    require(raw < 1 << 63 and raw & (1 << 62) and raw & ~0x40007FFFFFFFFFFF == 0, "malformed V2 bits")
    count, mask = (raw >> 16) & 0x7FFFFFFF, raw & 65535
    require(count == len(payload) and (count != 0 or mask == 0), "V2 byte count/mask mismatch")
    return count, mask


def palettes_from_java(payload, mask, full, sky):
    """Read actual Java palette history/words, never encoder helper state."""
    r = Reader(payload)
    palettes = {}
    for y in range(16):
        if not mask & (1 << y):
            continue
        bits = r.take(1)[0]
        entries = [r.varint() for _ in range(r.varint())]
        words = [r.take(8).hex() for _ in range(r.varint())]
        palettes[y] = {"mode": "GLOBAL" if bits > 8 else "LOCAL_LINEAR" if bits == 4 else "LOCAL_HASH",
                       "bitsPerEntry": bits, "entries": entries, "packedWords": words}
        r.take(2048 + (2048 if sky else 0))
    if full:
        r.take(256)
    require(r.offset == len(payload), "Java palette extraction consumption")
    return palettes


def compare_event(event, registry_ids):
    owned_bytes = decode64(event["transport"])
    owned = transport(owned_bytes)
    native = decode64(event["nativePayload"])
    java_body = decode64(event["javaPacket"])
    x, z, full, java_mask, reference = java_packet(java_body)
    count, native_mask = decode_v2(event["v2Result"], native)
    require((x, z, full, java_mask, native_mask) ==
            (owned["x"], owned["z"], owned["full"], owned["mask"], owned["mask"]), "same-event header/mask mismatch")
    ticks = event.get("tickRefCounts")
    if not isinstance(ticks, list) or len(ticks) != 16:
        raise Incomplete("MISSING_SAME_EVENT_TICK_REFCOUNTS")
    actual_java = decode_packet(reference, java_mask, full, owned["sky"], owned["bits"])
    actual_native = decode_packet(native, native_mask, full, owned["sky"], owned["bits"])
    palette = palettes_from_java(reference, java_mask, full, owned["sky"])
    for y, section in enumerate(owned["sections"]):
        if section is None:
            require(ticks[y] is None, "tick count for absent selected section")
            continue
        require(type(ticks[y]) is int and 0 <= ticks[y] <= 4096, "invalid actual tick refcount")
        require(set(section["logicalStates"]) <= registry_ids, "state absent from actual registry export")
        section.update(tickRefCount=ticks[y], sourcePalette=palette[y])
        entries = None if palette[y]["mode"] == "GLOBAL" else palette[y]["entries"]
        require(set(palette[y]["entries"]) <= registry_ids, "palette state absent from registry")
        require(unpack_cells([int(word, 16) for word in palette[y]["packedWords"]],
                             palette[y]["bitsPerEntry"], entries) == tuple(section["logicalStates"]), "Java source palette/state mismatch")
    fixture_shape = {"sections": owned["sections"], "biomes": owned["biomes"],
                     "fullChunk": full, "skylight": owned["sky"]}
    compare_decoded(fixture_shape, actual_java)
    compare_decoded(fixture_shape, actual_native)
    require(len(actual_java.sections) == len(actual_native.sections) == native_mask.bit_count(), "decoded section count mismatch")
    return owned, owned_bytes, native, reference, java_body, actual_native, actual_java


def validate_inventory(events):
    require(isinstance(events, list), "event list required")
    names = [event.get("name") for event in events]
    require(len(names) == len(set(names)), "duplicate event names")
    require(set(names) == ACCEPTED | set(REJECTED), "missing or unqualified event records")
    for event in events:
        if event["name"] in ACCEPTED:
            require(event.get("status") == "ACCEPTED_PENDING_INDEPENDENT_COMPARISON", "accepted event status mismatch")
        else:
            require(set(event) == {"name", "status", "reason"} and event["status"] == "EXPLICIT_SAFE_REJECTION"
                    and event["reason"] == REJECTED[event["name"]], "incorrect safe rejection or published failure data")


class Artifacts:
    def __init__(self):
        self.mapping = {}

    def add(self, name, path, expected=None, version="1", member=None):
        require(re.fullmatch(r"[A-Za-z0-9_][A-Za-z0-9_./-]*", name)
                and all(part not in ("", ".", "..") for part in name.split("/")), "unsafe logical artifact name")
        path = Path(path).resolve()
        if not path.is_file():
            raise Incomplete("MISSING_EXTERNAL_ARTIFACT: " + str(path))
        if member:
            try:
                with zipfile.ZipFile(path) as jar:
                    data = jar.read(member)
            except (KeyError, zipfile.BadZipFile) as error:
                raise Incomplete("MISSING_JAR_MEMBER: " + name) from error
        else:
            data = path.read_bytes()
        digest = sha256(data)
        if expected is not None and digest != expected:
            raise Incomplete("ARTIFACT_MISMATCH: " + name)
        item = {"path": str(path), "sha256": digest}
        if member:
            item["zipMember"] = member
        require(name not in self.mapping or self.mapping[name] == item, "artifact logical-name collision")
        self.mapping[name] = item
        return {"name": name, "version": version, "sha256": digest}

    def revalidate(self):
        for name, item in tuple(self.mapping.items()):
            self.add(name, item["path"], item["sha256"], member=item.get("zipMember"))

    def verify_references(self, value):
        if isinstance(value, dict):
            if set(value) == {"name", "version", "sha256"}:
                item = self.mapping.get(value["name"])
                require(item is not None and item["sha256"] == value["sha256"], "unresolved fixture artifact")
            for child in value.values():
                self.verify_references(child)
        elif isinstance(value, list):
            for child in value:
                self.verify_references(child)


def _load_runtime(root, receipt_path, output):
    if not receipt_path.is_file():
        raise Incomplete("MISSING_RUNTIME_RECEIPT")
    receipt = parse_json(receipt_path.read_bytes())
    if receipt.get("status") != "PASS" or "oracle_execution" not in receipt:
        raise Incomplete("MISSING_QUALIFIED_ORACLE_EXECUTION")
    for field in ("qualified_inputs", "oracle_execution", "bootstrap_compile", "oracle_compile", "oracle_output"):
        if field not in receipt:
            raise Incomplete("MISSING_RUNTIME_IDENTITY: " + field)
    inputs, executed = receipt["qualified_inputs"], receipt["oracle_execution"]
    for field in ("artifacts", "pins", "dll", "java", "javac", "rt_jar", "tools_jar", "jvm_dll", "helper", "logging", "observer_jar"):
        if field not in inputs:
            raise Incomplete("MISSING_QUALIFIED_INPUT: " + field)
    artifacts = Artifacts()
    runtime_receipt = artifacts.add("runtime/execution-receipt.json", receipt_path)
    qualification_ref = artifacts.add("runtime/qualification.json", executed["manifest"], executed["manifest_sha256"])
    qualification = parse_json(Path(executed["manifest"]).read_bytes())
    if qualification.get("profile") != PROFILE or qualification.get("mod_lifecycle_executed") is not True:
        raise Incomplete("FULL_FML_RUNTIME_NOT_QUALIFIED")
    for field in ("ordered_loaded_mods", "registered_coremod_plugins", "registry_decoded_ids_sha256",
                  "registry_logical_states_sha256", "registry_identity_sha256", "transformed_classes",
                  "required_class_locations", "transformers", "jvm_arguments"):
        if field not in qualification:
            raise Incomplete("MISSING_RUNTIME_IDENTITY: " + field)
    require(qualification.get("production_authority") is False and qualification.get("server_main_called") is False,
            "runtime exceeds offline authority boundary")
    require(qualification.get("observer") == "PASSIVE_JVM_CLASS_DEFINITION_OBSERVER_NO_RETRANSFORMATION",
            "final JVM-definition observer missing")
    for item in ("attach_capabilities_listener_count", "chunk_load_listener_count"):
        require(qualification.get(item) == 0, "unqualified runtime listener")
    refs = {}
    for name, value in inputs.items():
        if isinstance(value, dict) and "path" in value:
            refs[name] = artifacts.add("runtime/" + name, value["path"], value["sha256"])
    pins = parse_json(Path(inputs["pins"]["path"]).read_bytes())
    # Share the exact runtime identity qualification, not any encoder/decoder
    # logic. Packet semantics are independently checked below.
    import forge_runtime
    try:
        forge_runtime.validate_qualification(qualification, pins)
    except forge_runtime.Incomplete as error:
        raise Incomplete(error.reason + ": " + error.detail) from error
    require(pins["target"] == "minecraft-1.12.2-forge-14.23.5.2860-clean-server", "wrong pinned target")
    registry_refs = []
    for filename, field in (("registry-state-ids.tsv", "registry_decoded_ids_sha256"),
                            ("registry-logical-states.tsv", "registry_logical_states_sha256")):
        registry_refs.append(artifacts.add("runtime/" + filename,
                            Path(executed["manifest"]).parent / filename, qualification[field]))
    composite = "RUSTCRAFT_REGISTRY_V1\n" + qualification["registry_decoded_ids_sha256"] + "\n" + qualification["registry_logical_states_sha256"] + "\n"
    require(sha256(composite.encode("utf-8")) == qualification["registry_identity_sha256"], "full registry identity mismatch")
    classpath = []
    require(len(inputs["artifacts"]) == len(pins["artifacts"]), "incomplete ordered classpath")
    for index, item in enumerate(inputs["artifacts"]):
        require(item["sha256"] == pins["artifacts"][index]["sha256"], "classpath differs from pins")
        classpath.append(artifacts.add("classpath/" + str(index), item["path"], item["sha256"]))
    require(len(classpath) == len(pins["artifacts"]), "incomplete ordered classpath")
    for member, digest in pins["forge_embedded_sha256"].items():
        artifacts.add("forge-embedded/" + re.sub(r"[^A-Za-z0-9_./-]", "_", member),
                      inputs["artifacts"][0]["path"], digest, member=member)
    compile_refs = []
    for kind in ("bootstrap_compile", "oracle_compile"):
        compiled = receipt[kind]
        for index, source in enumerate(compiled["source_identity"]):
            artifacts.add("sources/" + kind + "/" + str(index), source["path"], source["sha256"])
        for name, digest in compiled["classes"].items():
            artifacts.add("classes/" + kind + "/" + name.replace("$", "_"),
                          Path(compiled["directory"]) / "classes" / name, digest)
        manifest = output / (kind + ".json")
        manifest.write_bytes(canonical(compiled) + b"\n")
        compile_refs.append(artifacts.add("harness/" + kind + ".json", manifest))
    transformed = qualification["transformed_classes"]
    for name, digest in transformed.items():
        artifacts.add("transformed/" + name.replace(".", "/").replace("$", "_") + ".class",
                      Path(executed["transformed_dir"]) / (name.replace(".", "/") + ".class"), digest)
    for name, digest in pins["required_transformed_sha256"].items():
        require(transformed.get(name) == digest, "pinned transformed class mismatch")
    packet_name = "net.minecraft.network.play.server.SPacketChunkData"
    location = qualification["required_class_locations"][packet_name]
    require("!/" in location, "original Java packet location absent")
    member = location.split("!/", 1)[1]
    minecraft = next((item for item in inputs["artifacts"] if Path(item["path"]).name == "minecraft_server.1.12.2.jar"), None)
    require(minecraft is not None, "Minecraft jar absent")
    original = artifacts.add("original/SPacketChunkData.class", minecraft["path"], member=member)
    source_writer = {"className": packet_name, "methodName": "func_148840_b", "descriptor": "(Lnet/minecraft/network/PacketBuffer;)V",
                     "originalClassSha256": original["sha256"], "transformedClassSha256": transformed[packet_name],
                     "orderedTransformers": [dict(qualification_ref, version=name) for name in qualification["transformers"]]}
    decoder = artifacts.add("validation/packet_decoder.py", root / "tools/testing/packet_decoder.py")
    ownership = artifacts.add("contract/OwnedForgeCapture.java", root / "tools/forge-capture/src/com/rustcraft/oracle/OwnedForgeCapture.java")
    if not executed.get("config_identity"):
        raise Incomplete("MISSING_EXECUTED_CONFIG_IDENTITIES")
    configs = [artifacts.add("configs/" + str(index), row["path"], row["sha256"])
               for index, row in enumerate(executed["config_identity"])]
    metadata = {"runtime": {"minecraftVersion": "1.12.2", "protocolVersion": 340, "forgeVersion": "14.23.5.2860",
                            "javaRuntime": qualification["java_runtime_version"] + " " + qualification["java_vm"],
                            "orderedClasspath": [compile_refs[1]] + classpath,
                            "orderedMods": [dict(qualification_ref, version=row["id"] + ":" + row["version"])
                                            for row in qualification["ordered_loaded_mods"]],
                            "orderedCoremods": [dict(qualification_ref, version=row["class"])
                                                for row in qualification["registered_coremod_plugins"]],
                            "orderedConfigs": [refs["logging"]] + configs},
                "captureImplementation": compile_refs[1], "ownershipProtocol": ownership,
                "ownershipEvidence": [runtime_receipt, qualification_ref] + compile_refs + registry_refs,
                "sourceWriter": source_writer, "encoderBuild": refs["dll"], "independentDecoder": decoder}
    return receipt, qualification, artifacts, metadata


def load_runtime(root, receipt_path, output):
    try:
        return _load_runtime(root, receipt_path, output)
    except KeyError as error:
        raise Incomplete("MISSING_RUNTIME_IDENTITY: " + str(error)) from error


def execute(root, runtime_receipt_path, output, native_replay=None):
    output = Path(output).resolve()
    output.mkdir(parents=True, exist_ok=False)
    report = {"status": "FAIL", "captureKind": "REAL_CLEAN_FORGE_ORACLE", "productionAuthorityEligible": False}
    try:
        receipt, qualification, artifacts, metadata = load_runtime(Path(root), Path(runtime_receipt_path), output)
        if native_replay is not None:
            artifacts.add("validation/snapshot_replay", native_replay)
        oracle = Path(receipt["oracle_output"])
        events_path, registry_path = oracle / "events.json", oracle / "registry.json"
        events_ref = artifacts.add("oracle/events.json", events_path)
        registry_ref = artifacts.add("oracle/registry.json", registry_path)
        raw = parse_json(events_path.read_bytes())
        require(raw["captureKind"] == "REAL_CLEAN_FORGE_ORACLE" and raw["contractVersion"] == CONTRACT,
                "wrong oracle capture contract")
        require(raw["productionAuthorityEligible"] is False and raw["serverStarted"] is False, "oracle authority boundary")
        validate_inventory(raw["events"])
        registry = parse_json(registry_path.read_bytes())
        require(isinstance(registry, list) and registry, "empty runtime registry")
        ids = [row["id"] for row in registry]
        require(all(type(i) is int and 0 <= i <= 65535 for i in ids) and ids == sorted(ids), "invalid registry IDs")
        require(all(set(row) == {"id", "state"} and isinstance(row["state"], str) for row in registry), "invalid registry export")
        require(next(row["state"] for row in registry if row["id"] == 0) == "minecraft:air", "unqualified air ID")
        canonical_ids = {row["id"]: row["state"] for row in registry}
        registry_identity = sha256("".join(str(i) + "\t" + state + "\n" for i, state in sorted(canonical_ids.items())).encode())
        require(registry_identity == qualification["registry_decoded_ids_sha256"], "qualification/oracle registry differs")
        require(len(set(ids)) == qualification["registry_state_count"] and max(ids) == qualification["registry_state_max_id"], "registry count/maximum mismatch")
        require(raw["registrySha256"] == registry_ref["sha256"], "raw registry digest mismatch")
        require(raw["registryMapSize"] == qualification["registry_identity_map_size"] and max(ids) < raw["registryMapSize"], "registry map size mismatch")
        require(raw["globalPaletteBits"] == (raw["registryMapSize"] - 1).bit_length(), "runtime registry width mismatch")
        cases, sequences, accepted = [], set(), 0
        for event in raw["events"]:
            name = event["name"]
            if name in REJECTED:
                cases.append(dict(event))
                continue
            owned, input_bytes, native, java, packet, decoded, java_decoded = compare_event(event, set(ids))
            require(owned["owner"] == raw["ownerThread"] and owned["bits"] == raw["globalPaletteBits"], "event runtime context differs")
            require(owned["event"] not in sequences, "duplicate capture sequence")
            sequences.add(owned["event"])
            input_path = output / (name + ".snapshot.bin")
            input_path.write_bytes(input_bytes)
            input_ref = artifacts.add("events/" + name + ".snapshot.bin", input_path)
            body_path = output / (name + ".java-packet.bin")
            body_path.write_bytes(packet)
            body_ref = artifacts.add("events/" + name + ".java-packet.bin", body_path)
            provenance = {key: copy.deepcopy(value) for key, value in metadata.items() if key != "runtime"}
            provenance.update(sequenceId=str(owned["event"]), threadId=str(owned["owner"]), generator=None)
            provenance["ownershipEvidence"] += [events_ref, input_ref, body_ref]
            fixture = {"format": "rustcraft-chunk-packet-fixture", "schemaVersion": 1,
                       "eventId": name + ":" + str(owned["event"]), "captureKind": "REAL_CLEAN_FORGE_ORACLE",
                       "runtime": copy.deepcopy(metadata["runtime"]), "registry": {"kind": "RUNTIME_EXPORT",
                           "export": registry_ref, "entryCount": len(set(ids)), "globalPaletteBits": owned["bits"]},
                       "chunk": {"dimension": owned["dimension"], "x": owned["x"], "z": owned["z"], "generationId": str(owned["generation"])},
                       "requestedMask": owned["requested"], "encoderSelectedMask": owned["mask"], "emittedMask": decoded.emitted_mask,
                       "fullChunk": owned["full"], "skylight": owned["sky"], "sections": owned["sections"], "biomes": owned["biomes"],
                       "payload": record(native), "bytesWritten": len(native), "decodedSectionCount": len(decoded.sections),
                       "consumedByteCount": decoded.consumed, "javaReference": {"emittedMask": java_decoded.emitted_mask,
                           "payload": record(java), "bytesWritten": len(java), "decodedSectionCount": len(java_decoded.sections),
                           "consumedByteCount": java_decoded.consumed}, "provenance": provenance,
                       "hashes": {"algorithm": "SHA-256", "canonicalization": "rustcraft-fixture-json-v1", "fixtureSha256": "0" * 64}}
            fixture["hashes"]["fixtureSha256"] = fixture_hash(fixture)
            check_schema(fixture, parse_json(SCHEMA.read_bytes()))
            path = output / (name + ".fixture.json")
            path.write_bytes(canonical(fixture) + b"\n")
            persisted = parse_json(path.read_bytes())
            require(fixture_hash(persisted) == persisted["hashes"]["fixtureSha256"], "persisted fixture hash mismatch")
            artifacts.verify_references(persisted)
            replay = False
            if native_replay is not None:
                wire_path = output / (name + ".replayed.bin")
                process = subprocess.run([str(native_replay), str(input_path), str(wire_path)],
                                         capture_output=True, text=True, timeout=30)
                require(process.returncode == 0, "native replay process failed")
                result = parse_json(process.stdout.encode())
                (output / (name + ".native-replay.json")).write_bytes(canonical(result) + b"\n")
                replay_bytes = wire_path.read_bytes() if wire_path.exists() else b""
                require(result.get("status") == "PASS", "native replay rejected accepted event")
                count, mask = decode_v2(result["v2_result"], replay_bytes)
                require((count, mask) == (result["bytes_written"], result["emitted_mask"]), "replay V2 mismatch")
                require(replay_bytes == native and mask == owned["mask"], "replayed owned event differs")
                compare_decoded(fixture, decode_packet(replay_bytes, mask, owned["full"], owned["sky"], owned["bits"]))
                replay = True
            accepted += 1
            cases.append({"name": name, "status": "ACCEPTED_AND_MATCHED", "fixture": path.name,
                          "fixtureSha256": fixture["hashes"]["fixtureSha256"], "sections": len(decoded.sections),
                          "javaBytes": len(java), "nativeBytes": len(native), "exactByteEquality": java == native,
                          "semanticEquality": True, "nativeReplay": replay, "incarnation": str(owned["incarnation"]),
                          "generation": str(owned["generation"]), "epoch": str(owned["epoch"]), "v2Result": event["v2Result"]})
        require(accepted > 0, "empty real fixture set")
        artifacts.revalidate()
        (output / "artifact-map.json").write_bytes(canonical(artifacts.mapping) + b"\n")
        report.update(status="PASS" if native_replay is not None else "INCOMPLETE", reason=None if native_replay else "NATIVE_REPLAY_NOT_RUN",
                      acceptedCount=accepted, rejectionCount=len(REJECTED), caseCount=len(cases), cases=cases,
                      runtimeReceiptSHA256=sha256(Path(runtime_receipt_path).read_bytes()),
                      nativeReplayCount=accepted if native_replay is not None else 0,
                      fixtureSchemaVersion=1, registryDecodedIdsSHA256=registry_identity,
                      registryIdentitySHA256=qualification["registry_identity_sha256"],
                      registryExportRows=len(ids), registryUniqueIds=len(set(ids)), registryMapSize=raw["registryMapSize"],
                      scope="FULL_FML_INITIALIZED_EXCLUSIVE_OWNED_OBJECTS_NO_SERVER_NO_LIVE_AUTHORITY")
    except Incomplete as error:
        report.update(status="INCOMPLETE", reason=str(error))
    except (FixtureError, DecodeError, OSError, ValueError, KeyError, StopIteration, subprocess.SubprocessError) as error:
        report.update(status="FAIL", reason=str(error))
    (output / "results.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    return report


def exit_code(status):
    return 0 if status == "PASS" else 2 if status == "INCOMPLETE" else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--runtime-receipt", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--native-replay", type=Path)
    args = parser.parse_args()
    result = execute(ROOT, args.runtime_receipt, args.output, args.native_replay)
    print(json.dumps({key: result.get(key) for key in ("status", "reason", "acceptedCount", "rejectionCount", "caseCount")}))
    return exit_code(result["status"])


if __name__ == "__main__":
    raise SystemExit(main())
