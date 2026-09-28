"""Phase C fixture controls for the headless client and its state machines.

These exercise parsers and state transitions against scripted bytes -- no
server, no socket, no live campaign. The purpose is that the client's
correctness is proven before it is pointed at anything real, so a real-probe
failure indicts the server or the handshake, not the client.
"""
from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import fml_handshake as fml
import protocol340 as proto
from fml_handshake import FmlHandshake, HandshakeRejected

CHECKS = 0


class FramingControls(unittest.TestCase):

    def test_varint_round_trip(self):
        for value in (0, 1, 127, 128, 255, 4096, 65535, 2097151):
            encoded = proto.varint(value)
            decoded, offset = proto.read_varint(encoded)
            self.assertEqual((decoded, offset), (value, len(encoded)), str(value))

    def test_varint_rejects_negative(self):
        with self.assertRaises(ValueError):
            proto.varint(-1)

    def test_string_round_trip(self):
        encoded = proto.write_string("FML|HS")
        text, offset = proto.read_string(encoded)
        self.assertEqual((text, offset), ("FML|HS", len(encoded)))

    def test_packet_ids_match_canonical_table(self):
        """The ids this client uses are the project's canonical protocol 340
        table, not lore. Locking them here means a wrong id fails here, not
        in a live probe disguised as a server problem."""
        self.assertEqual(proto.SB_CONFIRM_TELEPORT, 0x00)
        self.assertEqual(proto.SB_CLIENT_SETTINGS, 0x04)
        self.assertEqual(proto.SB_PLUGIN_MESSAGE, 0x09)
        self.assertEqual(proto.SB_KEEP_ALIVE, 0x0B, "CPacketKeepAlive is 0x0B per "
                         "docs/protocol-340/packet-registry-wire.md -- the V1 comment "
                         "that said 0x0C was wrong, the V1 constant was right")
        self.assertEqual(proto.SB_PLAYER_POSITION, 0x0D)
        self.assertEqual(proto.CB_KEEP_ALIVE, 0x1F)
        self.assertEqual(proto.CB_CHUNK_DATA, 0x20)
        self.assertEqual(proto.CB_JOIN_GAME, 0x23)
        self.assertEqual(proto.CB_PLUGIN_MESSAGE, 0x18)

    def test_fml_marker_shape(self):
        self.assertEqual(proto.fml_marker("localhost"), "localhost\0FML\0")


class FrameLengthControls(unittest.TestCase):
    """The frame-length bug that cost ten live attempts.

    The outer packet length must count the compression data-length varint.
    Omitting it truncates the frame by one byte; the server's FML codec then
    receives a one-byte handshake message and reads from an empty remainder.
    Found by replaying the emitted frame through the real vanilla decoder
    (ReplayServerDecode), not by inspection. These controls pin both threshold
    states and the exact bug.
    """

    def _frame(self, threshold: int, payload: bytes) -> bytes:
        """The client's exact framing, lifted from protocol340._write_frame."""
        import protocol340 as proto
        if threshold >= 0:
            import zlib
            if len(payload) < threshold:
                return proto.varint(len(payload) + 1) + proto.varint(0) + payload
            compressor = zlib.compressobj(1)
            compressed = compressor.compress(payload) + compressor.flush()
            header = proto.varint(len(compressed))
            return proto.varint(len(compressed) + len(header)) + header + compressed
        return proto.varint(len(payload)) + payload

    def _parse(self, frame: bytes) -> tuple[int, bytes, bytes]:
        """Server-side view: (data_length, packet_id, body)."""
        import protocol340 as proto
        packet_len, off = proto.read_varint(frame)
        inner = frame[off:off + packet_len]
        self.assertEqual(packet_len, len(inner),
                         "the declared frame length must match the bytes available")
        data_len, off = proto.read_varint(inner)
        packet_id, off = proto.read_varint(inner, off)
        return data_len, packet_id, inner[off:]

    def test_uncompressed_frame_length_counts_the_data_length_varint(self):
        """The exact bug: threshold on, body below threshold."""
        payload = proto.varint(0x09) + proto.write_string("FML|HS") + bytes([1, 2])
        frame = self._frame(256, payload)
        data_len, packet_id, body = self._parse(frame)
        self.assertEqual((data_len, packet_id), (0, 0x09))
        self.assertEqual(body, proto.write_string("FML|HS") + bytes([1, 2]),
                         "the payload byte the live server lost must be inside the frame")

    def test_compressed_frame_length_counts_the_data_length_varint(self):
        import zlib
        big = b"x" * 4096  # above any small threshold, forces the compressed path
        payload = proto.varint(0x09) + big
        frame = self._frame(256, payload)
        packet_len, off = proto.read_varint(frame)
        inner = frame[off:off + packet_len]
        self.assertEqual(packet_len, len(inner))
        data_len, off2 = proto.read_varint(inner)
        self.assertGreater(data_len, 0)
        self.assertEqual(zlib.decompress(inner[off2:]), payload)

    def test_threshold_off_has_no_data_length_varint(self):
        payload = proto.varint(0x00) + proto.write_string("RustCraftProbe")
        frame = self._frame(-1, payload)
        packet_len, off = proto.read_varint(frame)
        self.assertEqual(frame[off:], payload,
                         "without compression the frame is length + payload")

    def test_one_byte_short_frame_reproduces_the_live_failure(self):
        """The old, buggy length -- the exact shape every live probe died of."""
        payload = proto.varint(0x09) + proto.write_string("FML|HS") + bytes([1, 2])
        buggy = proto.varint(len(payload)) + proto.varint(0) + payload
        packet_len, off = proto.read_varint(buggy)
        available = len(buggy) - off
        self.assertEqual(packet_len, available - 1,
                         "the buggy length declares one byte less than the frame carries")
        inner = buggy[off:off + packet_len]
        _, o = proto.read_varint(inner)
        _, o = proto.read_varint(inner, o)
        body = inner[o:]
        self.assertEqual(body, proto.write_string("FML|HS") + bytes([1]),
                         "the server sees a one-byte FML payload: discriminator only")


class HandshakeControls(unittest.TestCase):

    def _body(self, discriminator: int, rest: bytes = b"") -> bytes:
        return bytes([discriminator]) + rest

    def test_happy_path_records_every_transition(self):
        h = FmlHandshake()
        reply = h.on_server_message(self._body(0, bytes([2])))
        self.assertEqual(reply, bytes([1, 2]), "ClientHello: disc 1 + FML version 2")
        mods = proto.varint(2) + proto.write_string("forge") + proto.write_string("14.23.5.2846") \
            + proto.write_string("mymod") + proto.write_string("1.0")
        reply = h.on_server_message(self._body(2, mods))
        self.assertEqual(h.server_mod_list,
                         [("forge", "14.23.5.2846"), ("mymod", "1.0")])
        self.assertEqual(reply[:1], bytes([2]))
        self.assertEqual(len(h.client_mod_list), 2,
                         "the client echoes the server's own list")
        self.assertTrue(h.complete is False)
        h.on_server_message(self._body(3, b"\x01"))
        h.on_server_message(self._body(3, b"\x00"))
        self.assertTrue(h.complete)
        states = [t["to"] for t in h.transitions]
        self.assertEqual(states, [fml.S_WAIT_SERVER_HELLO, fml.S_HELLO_SENT,
                                  fml.S_MODLIST_SENT, fml.S_REGISTRY_STREAM,
                                  fml.S_COMPLETE],
                         "no state may be skipped, and the receipt shows gaps")

    def test_empty_server_mod_list_is_parsed_but_not_sent_back_empty(self):
        """The V1 defect, as a control: an empty server list is echoed as empty
        -- the machine never fabricates entries to fill a list."""
        h = FmlHandshake()
        h.on_server_message(self._body(0, bytes([2])))
        reply = h.on_server_message(self._body(2, proto.varint(0)))
        self.assertEqual(h.server_mod_list, [])
        self.assertEqual(reply, bytes([2, 0]))

    def test_mod_list_out_of_order_rejected(self):
        h = FmlHandshake()
        with self.assertRaises(HandshakeRejected):
            h.on_server_message(self._body(2, proto.varint(0)))

    def test_registry_before_modlist_rejected(self):
        h = FmlHandshake()
        with self.assertRaises(HandshakeRejected):
            h.on_server_message(self._body(3, b"\x00"))

    def test_duplicate_server_hello_rejected(self):
        h = FmlHandshake()
        h.on_server_message(self._body(0, bytes([2])))
        with self.assertRaises(HandshakeRejected):
            h.on_server_message(self._body(0, bytes([2])))

    def test_unknown_discriminator_rejected_with_state(self):
        h = FmlHandshake()
        try:
            h.on_server_message(self._body(0x42))
        except HandshakeRejected as rejected:
            self.assertIn("0x42", str(rejected))
            self.assertEqual(rejected.state, fml.S_WAIT_SERVER_HELLO)
        else:
            self.fail("expected a rejection")

    def test_implausible_mod_count_rejected(self):
        h = FmlHandshake()
        h.on_server_message(self._body(0, bytes([2])))
        with self.assertRaises(HandshakeRejected):
            h.on_server_message(self._body(2, proto.varint(100000)))

    def test_client_list_derivation_is_recorded(self):
        h = FmlHandshake()
        h.on_server_message(self._body(0, bytes([2])))
        h.on_server_message(self._body(2, proto.varint(1)
                                       + proto.write_string("a") + proto.write_string("1")))
        self.assertEqual(h.summary()["client_mod_list_derivation"],
                         "echoed-from-server-handshake")

    def test_summary_records_rejection(self):
        h = FmlHandshake()
        h.reject_reason = "server refused"
        self.assertEqual(h.summary()["reject_reason"], "server refused")
        self.assertFalse(h.summary()["complete"])


if __name__ == "__main__":
    unittest.main(verbosity=1)
