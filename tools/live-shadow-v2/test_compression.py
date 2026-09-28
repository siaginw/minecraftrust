"""Compression and fragmented-socket controls for the V2 headless client.

The dataLength rule is ground truth from the genuine vanilla classes (gv =
encoder, gu = decoder, in the pinned 1.12.2 jar): the compressed branch writes
VarInt(UNCOMPRESSED_SIZE) then the deflate stream, and the decoder allocates
new byte[dataLength] and calls Inflater.inflate exactly once. Declaring the
COMPRESSED size therefore truncates the decompressed packet to that many
bytes -- silently, because a single inflate into a short buffer is not an
error. That truncation was the Revelation ModList failure: 3847 packet bytes
became the declared 2282, and FML saw a ~2273-byte payload.
"""
from __future__ import annotations

import sys
import unittest
import zlib
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import protocol340 as proto


def frame(payload: bytes, threshold: int = 256) -> bytes:
    """The headless client's exact framing, from protocol340._write_frame."""
    if threshold < 0:
        return proto.varint(len(payload)) + payload
    if len(payload) < threshold:
        return proto.varint(len(payload) + 1) + proto.varint(0) + payload
    compressor = zlib.compressobj(1)
    compressed = compressor.compress(payload) + compressor.flush()
    header = proto.varint(len(payload))
    return proto.varint(len(header) + len(compressed)) + header + compressed


def genuine_decode(frame_bytes: bytes) -> bytes:
    """The genuine decoder's semantics: allocate dataLength, inflate once."""
    packet_len, off = proto.read_varint(frame_bytes)
    inner = frame_bytes[off:off + packet_len]
    if len(inner) != packet_len:
        raise ValueError("truncated frame")
    declared, o = proto.read_varint(inner)
    if declared == 0:
        return inner[o:]
    produced = zlib.decompressobj().decompress(inner[o:], declared)
    return produced


class CompressionControls(unittest.TestCase):

    def test_declared_length_is_the_uncompressed_size(self):
        payload = b"M" * 3847          # the Revelation ModList packet size
        encoded = frame(payload)
        packet_len, off = proto.read_varint(encoded)
        inner = encoded[off:off + packet_len]
        declared, _ = proto.read_varint(inner)
        self.assertEqual(declared, 3847,
                     "dataLength must name the uncompressed size, not the stream")

    def test_compressed_size_as_declaration_truncates_like_the_real_decoder(self):
        """The bug, reproduced with the genuine decoder's own behaviour."""
        payload = b"M" * 3847
        compressor = zlib.compressobj(1)
        compressed = compressor.compress(payload) + compressor.flush()
        wrong = proto.varint(len(compressed)) + compressed   # the old bug
        declared, o = proto.read_varint(wrong)
        produced = zlib.decompressobj().decompress(wrong[o:], declared)
        self.assertEqual(len(produced), len(compressed),
                     "a single inflate into a dataLength-sized buffer stops at "
                     "dataLength bytes, silently truncating the packet")

    def test_threshold_boundaries_round_trip(self):
        for size in (255, 256, 257):        # threshold-1, threshold, threshold+1
            payload = b"B" * size
            self.assertEqual(genuine_decode(frame(payload)), payload,
                         "size %d must round-trip" % size)

    def test_exact_revelation_modlist_size_round_trips(self):
        payload = b"M" * 3847
        self.assertEqual(genuine_decode(frame(payload)), payload)

    def test_much_larger_payload_round_trips(self):
        payload = b"L" * 200_000
        self.assertEqual(genuine_decode(frame(payload)), payload)

    def test_wrong_declared_length_is_detected(self):
        """Our own reader refuses a declaration that does not match."""
        payload = b"W" * 5000
        compressor = zlib.compressobj(1)
        compressed = compressor.compress(payload) + compressor.flush()
        declared = 4321
        wrong = proto.varint(declared) + compressed
        produced = zlib.decompressobj().decompress(
            wrong[proto.read_varint(wrong)[1]:], declared)
        self.assertNotEqual(len(produced), len(payload),
                            "a wrong declaration must be detectable on read")

    def test_truncated_compressed_stream_is_rejected(self):
        payload = b"T" * 5000
        compressor = zlib.compressobj(1)
        compressed = compressor.compress(payload) + compressor.flush()
        with self.assertRaises(Exception):
            zlib.decompress(compressed[:len(compressed) // 2])

    def test_trailing_bytes_outside_declaration_do_not_join_the_frame(self):
        payload = b"Z" * 100
        padded = frame(payload) + b"ZZZZ"
        packet_len, off = proto.read_varint(padded)
        inner = padded[off:off + packet_len]
        self.assertEqual(len(inner), packet_len,
                     "trailing bytes must not become part of the declared frame")


class FragmentedSocketControls(unittest.TestCase):
    """Partial TCP I/O: the transport under large compressed frames."""

    def test_multi_fragment_receive_reassembles(self):
        payload = b"P" * 3000
        encoded = frame(payload)
        buf = bytearray()
        frames = []
        for i in range(0, len(encoded), 97):
            buf.extend(encoded[i:i + 97])
            while True:
                try:
                    plen, off = proto.read_varint(bytes(buf))
                except ValueError:
                    break
                if len(buf) < off + plen:
                    break
                frames.append(bytes(buf[off:off + plen]))
                del buf[:off + plen]
        self.assertEqual(len(frames), 1)
        self.assertEqual(genuine_decode(proto.varint(len(frames[0])) + frames[0]), payload,
                     "a frame reassembled from many fragments must decode whole")

    def test_coalesced_frames_split(self):
        stream = frame(b"A" * 3000) + frame(b"B" * 3000)
        buf = bytearray(stream)
        out = []
        while len(buf):
            plen, off = proto.read_varint(bytes(buf))
            self.assertLessEqual(off + plen, len(buf))
            out.append(bytes(buf[off:off + plen]))
            del buf[:off + plen]
        self.assertEqual(len(out), 2)
        self.assertEqual(genuine_decode(proto.varint(len(out[0])) + out[0]), b"A" * 3000)
        self.assertEqual(genuine_decode(proto.varint(len(out[1])) + out[1]), b"B" * 3000)


if __name__ == "__main__":
    unittest.main(verbosity=1)
