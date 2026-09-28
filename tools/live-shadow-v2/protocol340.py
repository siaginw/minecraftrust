"""Protocol 340 framing and packet types for the V2 headless client.

Bounded: reads are size-capped, writes are varint-correct, and nothing here
knows about any mod, pack or runtime. Packet ids are the project's own
canonical table (docs/protocol-340/packet-registry-wire.md), not lore.
"""
from __future__ import annotations

import socket
import struct

PROTOCOL_VERSION = 340

# Serverbound (client -> server), protocol 340.
SB_CONFIRM_TELEPORT = 0x00
SB_CLIENT_SETTINGS = 0x04
SB_PLUGIN_MESSAGE = 0x09
SB_KEEP_ALIVE = 0x0B
SB_PLAYER = 0x0C
SB_PLAYER_POSITION = 0x0D

# Clientbound, protocol 340 -- only what the join probe classifies.
CB_JOIN_GAME = 0x23
CB_CHUNK_DATA = 0x20
CB_KEEP_ALIVE = 0x1F
CB_DISCONNECT_LOGIN = 0x00
CB_DISCONNECT_PLAY = 0x1A
CB_PLUGIN_MESSAGE = 0x18
CB_PLAYER_POS_LOOK = 0x2F
CB_TIME_UPDATE = 0x44
CB_HELD_ITEM_CHANGE = 0x15
CB_ENTITY_STATUS = 0x1B
CB_STATISTICS = 0x05
CB_SPANNER_POSLOOK = 0x2F

MAX_PACKET_BYTES = 1 << 20  # refuse absurd frames rather than buffering them


def varint(value: int) -> bytes:
    if value < 0:
        raise ValueError("varint must be non-negative")
    out = bytearray()
    while True:
        byte = value & 0x7F
        value >>= 7
        if value:
            out.append(byte | 0x80)
        else:
            out.append(byte)
            return bytes(out)


def read_varint(data: bytes, offset: int = 0) -> tuple[int, int]:
    value = 0
    for index in range(5):
        if offset + index >= len(data):
            raise ValueError("truncated varint")
        byte = data[offset + index]
        value |= (byte & 0x7F) << (7 * index)
        if not byte & 0x80:
            return value, offset + index + 1
    raise ValueError("varint longer than 5 bytes")


def write_string(text: str) -> bytes:
    encoded = text.encode("utf-8")
    if len(encoded) > 32767:
        raise ValueError("string exceeds protocol limit")
    return varint(len(encoded)) + encoded


def read_string(data: bytes, offset: int = 0) -> tuple[str, int]:
    length, offset = read_varint(data, offset)
    if length > 32767:
        raise ValueError("string length exceeds protocol limit")
    return data[offset:offset + length].decode("utf-8", "replace"), offset + length


class Frame:
    """One connection: handshake framing with strict bounds.

    Read side is pull-based with a timeout; there is no background thread, so
    the probe's wall clock is the only clock and every wait is bounded by the
    socket timeout the caller sets.
    """

    def __init__(self, host: str, port: int, timeout_s: float):
        self.host, self.port = host, port
        self.sock = socket.create_connection((host, port), timeout=timeout_s)
        self.sock.settimeout(timeout_s)
        self.state = "HANDSHAKE"
        self.threshold = -1
        self.packets_in = 0
        self.bytes_in = 0
        self.packets_out = 0
        #: Bounded wire trace, when the caller wants one. Each entry is
        #: (direction, packet id, hex body); the probe writes it to a file so a
        #: handshake disagreement can be diagnosed from bytes rather than
        #: guessed at from a stack trace on the other side.
        self.trace: list[tuple[str, int, str]] | None = None

    # ---- write ------------------------------------------------------------

    def send(self, packet_id: int, body: bytes = b"") -> None:
        if self.trace is not None:
            self.trace.append(("OUT", packet_id, body.hex()))
        self._write_frame(varint(packet_id) + body)

    def _write_frame(self, payload: bytes) -> None:
        if self.threshold >= 0:
            import zlib
            # Once compression is enabled every frame carries a data-length
            # varint: 0 when the body is sent uncompressed (below threshold),
            # the compressed size otherwise. Omitting it on the uncompressed
            # path makes the server parse our body as that varint -- which is
            # an IndexOutOfBoundsException on its side and a disconnect.
            if len(payload) < self.threshold:
                body = varint(len(payload)) + varint(0) + payload
            else:
                compressor = zlib.compressobj(1)
                compressed = compressor.compress(payload) + compressor.flush()
                body = varint(len(payload)) + varint(len(compressed)) + compressed
        else:
            body = varint(len(payload)) + payload
        self.sock.sendall(body)
        self.packets_out += 1

    # ---- read -------------------------------------------------------------

    def read_frame(self) -> tuple[int, bytes]:
        length, _ = self._read_varint_stream()
        if length < 0 or length > MAX_PACKET_BYTES:
            raise ValueError("frame length out of bounds: %d" % length)
        data = self._read_exact(length)
        if self.threshold >= 0:
            expected, offset = read_varint(data)
            if expected == 0:
                # Uncompressed frame: the packet-length prefix counts the
                # data-length varint, so `offset` bytes are overhead and the
                # payload is the remainder -- NOT the whole frame.
                payload = data[offset:]
            else:
                import zlib
                payload = zlib.decompress(data[offset:])
                if len(payload) != expected:
                    raise ValueError("decompressed length mismatch")
        else:
            payload = data
        if not payload:
            raise ValueError("empty packet")
        packet_id, offset = read_varint(payload)
        self.packets_in += 1
        self.bytes_in += length
        if self.trace is not None:
            self.trace.append(("IN", packet_id, payload[offset:].hex()))
        return packet_id, payload[offset:]

    def _read_exact(self, count: int) -> bytes:
        out = bytearray()
        while len(out) < count:
            chunk = self.sock.recv(min(65536, count - len(out)))
            if not chunk:
                raise ConnectionError("connection closed mid-frame")
            out.extend(chunk)
        return bytes(out)

    def _read_varint_stream(self) -> tuple[int, int]:
        value = 0
        for index in range(5):
            byte = self._read_exact(1)[0]
            value |= (byte & 0x7F) << (7 * index)
            if not byte & 0x80:
                return value, index + 1
        raise ValueError("stream varint longer than 5 bytes")

    def close(self) -> None:
        try:
            self.sock.close()
        except OSError:
            pass


def handshake_packet(host: str, port: int, next_state: int) -> bytes:
    """The serverbound Handshake (0x00) body: protocol, host, port, state."""
    return varint(PROTOCOL_VERSION) + write_string(host) + struct.pack(">H", port) + varint(next_state)


def login_start_packet(username: str) -> bytes:
    return write_string(username)


def fml_marker(hostname: str) -> str:
    """The hostname a Forge client sends: trailing NUL + FML + NUL.

    Without the marker a Forge server treats the connection as vanilla and
    will not run the FML|HS handshake at all, so the probe would prove
    nothing about mod compatibility.
    """
    return hostname + "\0FML\0"
