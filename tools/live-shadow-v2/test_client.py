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
