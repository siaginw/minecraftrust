"""Minimal RCON client (Source RCON protocol) — drive a warm Minecraft
server without stdin pipes or probes. Works across runner restarts.

Used by the warm-server pattern: boot Gate A once, drive save-all/teleport/
evidence commands over RCON from any later process. See
docs/engineering/CAMPAIGN_TOOLING.md (prior art: github.com/topics/rcon).
"""
from __future__ import annotations

import socket
import struct

SERVERDATA_AUTH = 3
SERVERDATA_EXECCOMMAND = 2
SERVERDATA_AUTH_RESPONSE = 2


def _packet(req_id: int, ptype: int, payload: bytes) -> bytes:
    body = struct.pack("<ii", req_id, ptype) + payload + b"\x00\x00"
    return struct.pack("<i", len(body)) + body


def _read_packet(sock: socket.socket) -> tuple[int, int, bytes]:
    raw = sock.recv(4)
    if len(raw) < 4:
        raise ConnectionError("rcon: short length prefix")
    (length,) = struct.unpack("<i", raw)
    data = b""
    while len(data) < length:
        chunk = sock.recv(length - len(data))
        if not chunk:
            raise ConnectionError("rcon: connection closed")
        data += chunk
    req_id, ptype = struct.unpack("<ii", data[:8])
    return req_id, ptype, data[8:-2]


class RconClient:
    def __init__(self, host: str, port: int, password: str, timeout_s: float = 5.0):
        self.sock = socket.create_connection((host, port), timeout=timeout_s)
        self.sock.sendall(_packet(1, SERVERDATA_AUTH, password.encode()))
        # server replies with an empty AUTH_RESPONSE then the real one
        while True:
            rid, ptype, _ = _read_packet(self.sock)
            if ptype == SERVERDATA_AUTH_RESPONSE:
                if rid == -1:
                    raise PermissionError("rcon: auth failed")
                break

    def command(self, command: str) -> str:
        self.sock.sendall(_packet(2, SERVERDATA_EXECCOMMAND, command.encode()))
        _, _, payload = _read_packet(self.sock)
        return payload.decode("utf-8", "replace")

    def save_all_flush(self) -> str:
        return self.command("save-all flush")

    def close(self):
        try:
            self.sock.close()
        except OSError:
            pass

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        self.close()
