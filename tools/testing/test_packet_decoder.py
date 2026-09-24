#!/usr/bin/env python3
"""Independent decoder/fixture regressions; no Forge or encoder dependency."""
import copy
import unittest

from fixture_replay import (CORPUS, FixtureError, binary, canonical, check_schema,
                            fixture_hash, parse_json, run_corpus, snapshot_bytes,
                            validate_fixture)
from generate_synthetic_fixtures import record, reference_section, section, varint
from packet_decoder import DecodeError, Reader, decode_packet, unpack_cells


class DecoderTests(unittest.TestCase):
    def encoded(self, kind="one", sky=False, full=False):
        source = section(0, kind, sky)
        data = reference_section(source, sky) + (b"\x42" * 256 if full else b"")
        return data, source

    def test_zero_mask_empty_payload(self):
        result = decode_packet(b"", 0, False, False, 14)
        self.assertEqual((result.sections, result.consumed), ((), 0))

    def test_zero_mask_full_biomes(self):
        result = decode_packet(bytes(range(256)), 0, True, True, 14)
        self.assertEqual(result.biomes, bytes(range(256)))

    def test_all_local_widths_and_cross_word_cells(self):
        for bits in range(4, 9):
            palette = list(range(1 << bits))
            values = [index % len(palette) for index in range(4096)]
            packed = sum(value << (index * bits) for index, value in enumerate(values))
            words = [((packed >> pos) & ((1 << 64) - 1)).to_bytes(8, "big") for pos in range(0, bits * 4096, 64)]
            data = bytes([bits]) + varint(len(palette)) + b"".join(varint(value) for value in palette) + varint(len(words)) + b"".join(words) + bytes(2048)
            actual = decode_packet(data, 1, False, False, 14)
            self.assertEqual(actual.sections[0].states, tuple(values))
            self.assertEqual(actual.consumed, len(data))

    def test_global_widths_preserve_all_bits(self):
        for bits in range(9, 17):
            values = [(index * 313) & ((1 << bits) - 1) for index in range(4096)]
            packed = sum(value << (index * bits) for index, value in enumerate(values))
            words = [((packed >> pos) & ((1 << 64) - 1)).to_bytes(8, "big") for pos in range(0, bits * 4096, 64)]
            data = bytes([bits, 0]) + varint(len(words)) + b"".join(words) + bytes(2048)
            self.assertEqual(decode_packet(data, 1, False, False, bits).sections[0].states, tuple(values))

    def test_local_palette_ids_are_not_narrowed(self):
        data = bytes([4, 1]) + varint(0xFFFFFFFF) + varint(256) + bytes(4096)
        self.assertEqual(decode_packet(data, 1, False, False, 14).sections[0].states, (0xFFFFFFFF,) * 4096)

    def test_sparse_section_order_and_light_tails(self):
        data = b"".join(reference_section(section(y, sky=True), True) for y in (0, 7, 15)) + bytes(range(256))
        result = decode_packet(data, 0x8081, True, True, 14)
        self.assertEqual([value.y for value in result.sections], [0, 7, 15])
        self.assertEqual(result.sections[2].states, (16,) * 4096)
        self.assertEqual(result.sections[0].block_light, bytes([17]) * 2048)
        self.assertEqual(result.sections[0].sky_light, bytes([238]) * 2048)

    def test_truncation_at_every_boundary_rejected(self):
        data, _ = self.encoded("local5", sky=True, full=True)
        for end in (0, 1, 2, 18, 21, 30, 500, 2581, 4096, len(data) - 257, len(data) - 256, len(data) - 1):
            with self.subTest(end=end), self.assertRaises(DecodeError):
                decode_packet(data[:end], 1, True, True, 14)

    def test_trailing_byte_rejected(self):
        data, _ = self.encoded()
        with self.assertRaisesRegex(DecodeError, "trailing"):
            decode_packet(data + b"\x00", 1, False, False, 14)

    def test_unadvertised_complete_section_rejected(self):
        data, _ = self.encoded()
        with self.assertRaisesRegex(DecodeError, "trailing"):
            decode_packet(data * 2, 1, False, False, 14)

    def test_advertised_missing_section_rejected(self):
        data, _ = self.encoded()
        with self.assertRaises(DecodeError):
            decode_packet(data, 0x3F, False, False, 14)

    def test_bad_local_palette_counts(self):
        for count in (0, 17, 0xFFFFFFFF):
            with self.subTest(count=count), self.assertRaises(DecodeError):
                decode_packet(bytes([4]) + varint(count), 1, False, False, 14)

    def test_palette_index_out_of_bounds(self):
        data = bytes([4, 1, 0]) + varint(256) + bytes([0xFF]) * 2048 + bytes(2048)
        with self.assertRaisesRegex(DecodeError, "palette index"):
            decode_packet(data, 1, False, False, 14)

    def test_duplicate_local_palette_rejected(self):
        with self.assertRaisesRegex(DecodeError, "duplicate"):
            decode_packet(bytes([4, 2, 0, 0]), 1, False, False, 14)

    def test_global_width_and_palette_count_checked(self):
        for prefix in (bytes([14, 1]), bytes([13, 0])):
            with self.assertRaisesRegex(DecodeError, "global palette"):
                decode_packet(prefix, 1, False, False, 14)

    def test_exact_word_count_checked(self):
        for count in (0, 255, 257, 0xFFFFFFFF):
            with self.assertRaisesRegex(DecodeError, "word count"):
                decode_packet(bytes([4, 1, 0]) + varint(count), 1, False, False, 14)

    def test_invalid_widths_rejected(self):
        for bits in (0, 1, 3, 17, 255):
            with self.assertRaises(DecodeError):
                decode_packet(bytes([bits, 0]), 1, False, False, 14)

    def test_varint_overflow_overlong_noncanonical_rejected(self):
        for data in (b"\x80" * 6, b"\xff\xff\xff\xff\x10", b"\x80\x00", b"\x81\x00", b"\x80"):
            with self.subTest(data=data), self.assertRaises(DecodeError):
                Reader(data).varint()

    def test_flags_and_mask_types_are_strict(self):
        for mask, full, sky, bits in ((True, False, False, 14), (-1, False, False, 14), (65536, False, False, 14), (0, 1, False, 14), (0, False, 0, 14), (0, False, False, 8)):
            with self.assertRaises(DecodeError):
                decode_packet(b"", mask, full, sky, bits)

    def test_packed_word_count_checked_without_wire_reader(self):
        with self.assertRaisesRegex(DecodeError, "word count"):
            unpack_cells([0] * 255, 4, [0])


class FixtureTests(unittest.TestCase):
    def fixture(self, name="one-section"):
        return parse_json((CORPUS / (name + ".fixture.json")).read_bytes())

    def refreshed(self, fixture):
        fixture["hashes"]["fixtureSha256"] = fixture_hash(fixture)
        return fixture

    def test_complete_corpus_schema_hashes_and_semantics(self):
        result = run_corpus()
        self.assertEqual(result["fixtureCount"], 16)
        self.assertEqual(result["sequenceCount"], 3)
        self.assertEqual(result["status"], "INCOMPLETE")
        self.assertEqual(result["nativeReplayCount"], 0)

    def test_transport_preserves_selected_mask_and_states32(self):
        raw = snapshot_bytes(self.fixture(), {"firstStateId": 65536})
        self.assertEqual(raw[:8], b"RCSNAP01")
        self.assertEqual(int.from_bytes(raw[38:40], "big"), 1)
        self.assertEqual(int.from_bytes(raw[134:138], "big"), 65536)

    def test_duplicate_keys_and_bad_json_numbers_rejected(self):
        for data in (b'{"x":1,"x":2}', b"-0", b"1.0", b"1e1", b"NaN", b"Infinity", b'"\\ud800"', b"\xef\xbb\xbf{}"):
            with self.subTest(data=data), self.assertRaises(FixtureError):
                parse_json(data)

    def test_canonical_control_characters_use_long_escapes(self):
        self.assertEqual(canonical({"x": "\n\t\r\b\f\x00/\\\""}), b'{"x":"\\u000a\\u0009\\u000d\\u0008\\u000c\\u0000/\\\\\\\""}')

    def test_unknown_properties_and_boolean_integer_rejected(self):
        for name, value in (("extra", 1), ("emittedMask", True)):
            fixture = self.fixture()
            fixture[name] = value
            with self.assertRaises(FixtureError):
                validate_fixture(self.refreshed(fixture))

    def test_fixture_digest_tamper_rejected(self):
        fixture = self.fixture()
        fixture["eventId"] += ".tampered"
        with self.assertRaisesRegex(FixtureError, "fixture digest"):
            validate_fixture(fixture)

    def test_binary_count_and_digest_tamper_rejected(self):
        for field, value in (("byteCount", 1), ("sha256", "0" * 64)):
            fixture = self.fixture()
            fixture["payload"][field] = value
            with self.assertRaises(FixtureError):
                validate_fixture(self.refreshed(fixture))

    def test_noncanonical_base64_pad_bits_rejected(self):
        data = record(b"a")
        data["data"] = "YR=="
        with self.assertRaisesRegex(FixtureError, "noncanonical"):
            binary(data)

    def test_section_index_and_refcount_mismatch_rejected(self):
        for field, value in (("y", 1), ("nonAirCount", 0)):
            fixture = self.fixture()
            fixture["sections"][0][field] = value
            with self.assertRaises(FixtureError):
                validate_fixture(self.refreshed(fixture))

    def test_mask_coupling_and_presence_checked(self):
        for field, value in (("emittedMask", 2), ("requestedMask", 0), ("sections", [None] * 16)):
            fixture = self.fixture()
            fixture[field] = value
            with self.assertRaises(FixtureError):
                validate_fixture(self.refreshed(fixture))

    def test_payload_must_match_owned_logical_cells(self):
        fixture = self.fixture()
        fixture["sections"][0]["logicalStates"][0] = 2
        with self.assertRaisesRegex(FixtureError, "logical-state mismatch"):
            validate_fixture(self.refreshed(fixture))

    def test_light_and_biome_semantics_checked(self):
        for name, change in (("direct-light-before", "light"), ("biome-before", "biome")):
            fixture = self.fixture(name)
            if change == "light":
                fixture["sections"][0]["blockLight"] = record(bytes(2048))
            else:
                fixture["biomes"] = record(bytes(256))
            with self.assertRaises(FixtureError):
                validate_fixture(self.refreshed(fixture))

    def test_u64_identifiers_do_not_wrap(self):
        fixture = self.fixture()
        fixture["chunk"]["generationId"] = str(1 << 64)
        with self.assertRaisesRegex(FixtureError, "u64"):
            validate_fixture(self.refreshed(fixture))

    def test_unknown_registry_state_rejected_before_replay(self):
        fixture = self.fixture()
        fixture["sections"][0]["logicalStates"][0] = 65536
        with self.assertRaisesRegex(FixtureError, "registry"):
            validate_fixture(self.refreshed(fixture))

    def test_artifact_hash_checked(self):
        fixture = self.fixture()
        fixture["provenance"]["independentDecoder"]["sha256"] = "0" * 64
        with self.assertRaisesRegex(FixtureError, "artifact digest"):
            validate_fixture(self.refreshed(fixture))

    def test_source_palette_is_cross_checked(self):
        fixture = self.fixture()
        fixture["sections"][0]["sourcePalette"] = {"mode": "LOCAL_LINEAR", "bitsPerEntry": 4,
            "entries": [0, 1], "packedWords": ["1111111111111111"] * 256}
        validate_fixture(self.refreshed(fixture))
        fixture["sections"][0]["sourcePalette"]["packedWords"][0] = "0000000000000000"
        with self.assertRaisesRegex(FixtureError, "source palette"):
            validate_fixture(self.refreshed(fixture))

    def test_java_reference_decoded_independently(self):
        fixture = self.fixture()
        fixture["javaReference"] = {key: copy.deepcopy(fixture[key]) for key in
            ("emittedMask", "payload", "bytesWritten", "decodedSectionCount", "consumedByteCount")}
        validate_fixture(self.refreshed(fixture))
        fixture["javaReference"]["emittedMask"] = 0
        with self.assertRaisesRegex(FixtureError, "Java reference"):
            validate_fixture(self.refreshed(fixture))

    def test_unknown_schema_keyword_fails_closed(self):
        with self.assertRaisesRegex(FixtureError, "unsupported schema"):
            check_schema({}, {"newKeyword": True})


if __name__ == "__main__":
    unittest.main()
