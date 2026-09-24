"""Public unit controls for replay logic; constructed inputs are NOT real captures."""
import base64
import copy
from pathlib import Path
import struct
import tempfile
import unittest

from fixture_replay import FixtureError, CORPUS, SCHEMA, check_schema, fixture_hash, parse_json
from forge_fixture_replay import (ACCEPTED, REJECTED, HARDENING_REJECTED, ACCEPTED_KIND, REJECTED_KIND,
                                 REJECTION_SCHEMA, REJECTION_PROGRESS, Artifacts, Incomplete, rejection_fixture,
                                 compare_event, decode_v2, execute, exit_code, java_packet,
                                 transport, validate_inventory, validate_rejection_proof)
from generate_synthetic_fixtures import reference_section, section, varint
from packet_decoder import DecodeError


def b64(data):
    return base64.b64encode(data).decode()


def sample():
    source = section(0, sky=True)
    states = source["logicalStates"]
    # This unit-only source fixture uses id 1 and deterministic lights.
    from fixture_replay import binary
    body = reference_section(source, True) + bytes(range(256))
    header = struct.pack(">8sHBBBBHiiiQHHQQQQQQQ32s", b"RCSNAP01", 1, 3, 1, 14, 2, 0,
                         0, 6, 4, 57, 65535, 1, 3, 1, 1, 0, 0, 31, 31, bytes(32))
    owned = header + struct.pack(">HBBH", 1, 0, 0, sum(value != 0 for value in states))
    owned += struct.pack(">4096I", *states) + binary(source["blockLight"]) + binary(source["skyLight"]) + bytes(range(256))
    packet = struct.pack(">iiB", 6, 4, 1) + varint(1) + varint(len(body)) + body + b"\0"
    return {"name": "one-section", "status": "ACCEPTED_PENDING_INDEPENDENT_COMPARISON",
            "fixtureKind": ACCEPTED_KIND,
            "transport": b64(owned), "javaPacket": b64(packet), "nativePayload": b64(body),
            "v2Result": str((1 << 62) | (len(body) << 16) | 1), "tickRefCounts": [0] + [None] * 15}


def proof(name):
    stage, cells, retry = REJECTION_PROGRESS[name]
    return {"stage": stage, "snapshotPublished": False, "javaPacketPublished": False,
            "nativeCallsDuringFailure": 0, "rejectedTransportDenied": True,
            "ownedGraphUnchanged": name != "failed-inplace-rewrite", "existingWorldUntouched": True,
            "retryOutcome": retry, "scratchCellsWritten": cells,
            "ownedGraphBeforeSha256": "0" * 64,
            "ownedGraphAfterSha256": ("1" if name == "failed-inplace-rewrite" else "0") * 64}


def rejection(name):
    event = {"name": name, "status": "EXPLICIT_SAFE_REJECTION", "reason": REJECTED[name], "fixtureKind": REJECTED_KIND}
    if name in HARDENING_REJECTED:
        event["proof"] = proof(name)
    return event


class ForgeFixtureReplayTests(unittest.TestCase):
    def test_cli_distinguishes_semantic_failure_and_incomplete_evidence(self):
        self.assertEqual([exit_code(status) for status in ("PASS", "INCOMPLETE", "FAIL", "UNKNOWN")], [0, 2, 1, 1])
    def test_constructed_comparison_preserves_independent_ids_and_palette(self):
        owned, _, _, _, _, native, java = compare_event(sample(), {0, 1})
        self.assertEqual((owned["generation"], owned["incarnation"]), (57, 31))
        self.assertEqual(native.sections[0].states, java.sections[0].states)
        self.assertEqual(owned["sections"][0]["sourcePalette"]["bitsPerEntry"], 4)

    def test_transport_truncation_and_trailing_data_reject(self):
        data = base64.b64decode(sample()["transport"])
        for value in (data[:7], data[:127], data[:133], data[:-1], data + b"\0"):
            with self.assertRaises((FixtureError, DecodeError)):
                transport(value)

    def test_selected_missing_and_wide_id_reject(self):
        for offset, value in ((39, 3), (134, 1)):
            data = bytearray(base64.b64decode(sample()["transport"]))
            data[offset] = value
            with self.assertRaises(FixtureError):
                transport(bytes(data))

    def test_scope_lifecycle_epoch_and_owner_checks(self):
        for offset, value in ((13, 1), (95, 32), (79, 1), (63, 2)):
            data = bytearray(base64.b64decode(sample()["transport"]))
            data[offset] = value
            with self.assertRaises(FixtureError):
                transport(bytes(data))

    def test_forge_constructor_full_flag_exactly_matches_requested_mask(self):
        for offset, value in ((10, 2), (36, 127)):
            data = bytearray(base64.b64decode(sample()["transport"]))
            data[offset] = value
            with self.assertRaisesRegex(FixtureError, "full/requested"):
                transport(bytes(data))

    def test_java_packet_exact_consumption_and_no_te_tags(self):
        data = base64.b64decode(sample()["javaPacket"])
        for value in (data[:-1], data + b"\0", data[:-1] + b"\1"):
            with self.assertRaises((FixtureError, DecodeError)):
                java_packet(value)

    def test_java_header_cannot_describe_another_event(self):
        value = sample()
        packet = bytearray(base64.b64decode(value["javaPacket"]))
        packet[3] = 7
        value["javaPacket"] = b64(bytes(packet))
        with self.assertRaisesRegex(FixtureError, "header"):
            compare_event(value, {0, 1})

    def test_v2_malformed_failure_and_wrong_count_reject(self):
        for raw in ("-1", "0", "1", str((1 << 62) | 1), str((1 << 62) | (1 << 50)), "04611686018427387904"):
            with self.assertRaises(FixtureError):
                decode_v2(raw, b"")

    def test_native_light_and_biome_tampering_reject(self):
        for index in (-1, -300):
            value = sample()
            payload = bytearray(base64.b64decode(value["nativePayload"]))
            payload[index] ^= 1
            value["nativePayload"] = b64(bytes(payload))
            with self.assertRaises(FixtureError):
                compare_event(value, {0, 1})

    def test_registry_holes_are_not_treated_as_valid_range(self):
        with self.assertRaisesRegex(FixtureError, "registry"):
            compare_event(sample(), {0, 2, 65535})

    def test_missing_tick_refcount_never_fabricated(self):
        value = sample()
        del value["tickRefCounts"]
        with self.assertRaises(Incomplete):
            compare_event(value, {0, 1})

    def test_full_event_inventory_required_and_rejection_has_no_payload(self):
        events = [{"name": name, "status": "ACCEPTED_PENDING_INDEPENDENT_COMPARISON", "fixtureKind": ACCEPTED_KIND} for name in ACCEPTED]
        events += [rejection(name) for name in REJECTED]
        validate_inventory(events)
        for changed in (events[:-1], events + [events[0]], [dict(e, payload="unexpected") if e["name"] in REJECTED else e for e in events]):
            with self.assertRaises(FixtureError):
                validate_inventory(changed)

    def test_failed_capture_cannot_claim_publication_or_native_dispatch(self):
        for key, value in (("snapshotPublished", True), ("javaPacketPublished", True),
                           ("nativeCallsDuringFailure", 1), ("rejectedTransportDenied", False),
                           ("existingWorldUntouched", False)):
            value_proof = proof("capture-failure-logical-copy")
            value_proof[key] = value
            with self.subTest(key=key), self.assertRaises(FixtureError):
                validate_rejection_proof("capture-failure-logical-copy", value_proof)

    def test_source_witness_cannot_hide_mutation_or_fabricate_partial_rewrite(self):
        for name in ("failed-inplace-rewrite", "failed-scratch-rewrite", "capture-failure-biome-copy"):
            value = proof(name)
            value["ownedGraphAfterSha256"] = ("0" if name == "failed-inplace-rewrite" else "1") * 64
            with self.subTest(name=name), self.assertRaises(FixtureError):
                validate_rejection_proof(name, value)

    def test_rejection_evidence_is_not_a_packet_fixture(self):
        refs = [{"name": name, "version": "1", "sha256": "0" * 64} for name in ("runtime", "oracle", "source")]
        fixture = rejection_fixture(rejection("failed-scratch-rewrite"), refs)
        self.assertEqual(fixture["fixtureKind"], REJECTED_KIND)
        self.assertFalse(fixture["publishableResult"])
        self.assertEqual(fixture_hash(fixture), fixture["hashes"]["fixtureSha256"])
        schema = parse_json(REJECTION_SCHEMA.read_bytes())
        for key, value in (("payload", "forbidden"), ("publishableResult", True), ("fixtureKind", ACCEPTED_KIND)):
            broken = dict(fixture, **{key: value})
            with self.subTest(key=key), self.assertRaises(FixtureError):
                check_schema(broken, schema)
        changed = copy.deepcopy(fixture)
        changed["evidence"][0]["sha256"] = "1" * 64
        self.assertNotEqual(fixture_hash(changed), fixture["hashes"]["fixtureSha256"])

    def test_accepted_label_cannot_bypass_real_runtime_provenance(self):
        fixture = parse_json((CORPUS / "transition-nonempty.fixture.json").read_bytes())
        schema = parse_json(SCHEMA.read_bytes())
        check_schema(fixture, schema)
        fixture["captureKind"] = ACCEPTED_KIND
        with self.assertRaises(FixtureError):
            check_schema(fixture, schema)

    def test_hardening_proof_is_required_and_strict(self):
        for key in proof("capture-failure-wide-id"):
            value = proof("capture-failure-wide-id")
            del value[key]
            with self.subTest(key=key), self.assertRaises(FixtureError):
                validate_rejection_proof("capture-failure-wide-id", value)
        with self.assertRaises(FixtureError):
            validate_rejection_proof("capture-failure-wide-id", dict(proof("capture-failure-wide-id"), payload="forbidden"))

    def test_partial_progress_and_retry_are_not_inferred_from_rejection_alone(self):
        for name in HARDENING_REJECTED:
            for key, value in (("stage", "WRONG_STAGE"), ("scratchCellsWritten", 99), ("retryOutcome", "WRONG_OUTCOME")):
                changed = dict(proof(name), **{key: value})
                with self.subTest(name=name, key=key), self.assertRaises(FixtureError):
                    validate_rejection_proof(name, changed)

    def test_artifact_hash_tamper_and_path_traversal_reject(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "input"
            path.write_bytes(b"identity")
            artifacts = Artifacts()
            with self.assertRaises(Incomplete):
                artifacts.add("runtime/input", path, "0" * 64)
            with self.assertRaises(FixtureError):
                artifacts.add("../input", path)
            artifacts.add("runtime/input", path)
            path.write_bytes(b"changed after initial verification")
            with self.assertRaises(Incomplete):
                artifacts.revalidate()

    def test_missing_runtime_receipt_is_incomplete_never_pass(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            result = execute(root, root / "missing.json", root / "output")
            self.assertEqual(result["status"], "INCOMPLETE")


if __name__ == "__main__":
    unittest.main()
