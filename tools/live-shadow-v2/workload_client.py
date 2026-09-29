"""Deterministic campaign workload client (bounded, protocol-valid only).

Drives the EXISTING headless client machinery along a recorded movement
plan: legs of position updates (with the teleport confirmations and keep-
alives the protocol requires), disconnects/reconnects between passes, and
a per-event trace. Every action is a generic Minecraft protocol action --
no pack-specific gameplay automation.
"""
from __future__ import annotations

import json
import socket
import struct
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from fml_handshake import (  # noqa: E402
    FmlHandshake, HandshakeRejected, channel_registration, render_client_mod_list)
from protocol340 import (  # noqa: E402
    Frame, fml_marker, handshake_packet, login_start_packet)

# Serverbound ids pinned from protocol340 (the clientbound plugin message
# is 0x18; using it serverbound desynchronises the server's frame decoder
# and every later byte is read as a packet id -- measured "Bad packet id").
from protocol340 import (  # noqa: E402
    SB_CLIENT_SETTINGS as SB_CLIENT_SETTINGS_ID,
    SB_CONFIRM_TELEPORT as SB_CONFIRM_TELEPORT_ID,
    SB_KEEP_ALIVE as SB_KEEP_ALIVE_ID,
    SB_PLAYER_POSITION as SB_PLAYER_POSITION_ID,
    SB_PLUGIN_MESSAGE)
SB_KEEP_ALIVE = SB_KEEP_ALIVE_ID
SB_CONFIRM_TELEPORT = SB_CONFIRM_TELEPORT_ID
SB_PLAYER_POSITION = SB_PLAYER_POSITION_ID
SB_CLIENT_SETTINGS = SB_CLIENT_SETTINGS_ID
SB_PLUGIN_MSG = SB_PLUGIN_MESSAGE
CB_KEEP_ALIVE = 0x1F
CB_JOIN_GAME = 0x23
CB_PLAYER_POS_LOOK = 0x2F
CB_PLUGIN_MESSAGE = 0x18
CB_DISCONNECT_PLAY = 0x1A


def varint(value: int) -> bytes:
    out = b""
    while value & ~0x7F:
        out += bytes([(value & 0x7F) | 0x80])
        value >>= 7
    return out + bytes([value])


def write_string(text: str) -> bytes:
    data = text.encode("utf-8")
    return varint(len(data)) + data


class WorkloadFailure(RuntimeError):
    pass


CB_DISCONNECT_LOGIN = 0x00


def _varint_from(body: bytes):
    value = 0
    at = 0
    for i in range(5):
        b = body[at]
        at += 1
        value |= (b & 0x7F) << (7 * i)
        if b < 0x80:
            break
    return value, at


def _channel_from(body: bytes):
    length, at = _varint_from(body)
    return body[at:at + length].decode("utf-8", "replace"), body[at + length:]


def _channel_body(channel: str, payload: bytes) -> bytes:
    return write_string(channel) + payload


def _plugin(frame, body, handshake, observed, registered, client_mods):
    """The join probe's proven plugin sequence, unchanged in semantics: on
    ServerHello the genuine client sends ClientHello, REGISTER, then its own
    ModList; every other server message gets exactly the state machine's
    reply."""
    try:
        channel, rest = _channel_from(body)
    except (IndexError, ValueError):
        return
    if channel == "FML|HS":
        if handshake.state == "WAIT_SERVER_HELLO":
            reply = handshake.on_server_message(rest)
            if reply is not None:
                frame.send(SB_PLUGIN_MSG, _channel_body("FML|HS", reply))
            frame.send(SB_PLUGIN_MSG, _channel_body(
                "REGISTER", channel_registration()))
            frame.send(SB_PLUGIN_MSG, _channel_body(
                "FML|HS", render_client_mod_list(list(client_mods or []))))
            return
        reply = handshake.on_server_message(rest)
        if reply is not None:
            frame.send(SB_PLUGIN_MSG, _channel_body("FML|HS", reply))
        if handshake.complete:
            observed["fml_handshake_complete"] = True
        return
    if channel in ("REGISTER", "UNREGISTER"):
        for name in rest.decode("utf-8", "replace").split(chr(0)):
            if name:
                registered.add(name)
        return
    registered.add(channel)


def _connect_and_join(host: str, port: int, username: str, client_mods):
    """One full login -> FML handshake -> PLAY; returns (frame, observed)."""
    frame = Frame(host, port, 30)
    handshake = FmlHandshake()
    observed = {"keepalives": 0, "teleports": 0, "chunk_packets": 0}
    registered = set()
    # The protocol handshake with the FML marker hostname precedes login --
    # skipping it makes the first frame a login_start with no handshake and
    # the server closes the connection (measured, campaign attempt 3).
    frame.send(0x00, handshake_packet(fml_marker(host), port, 2))
    frame.state = "LOGIN"
    frame.send(0x00, login_start_packet(username))
    deadline = time.time() + 180
    joined = False
    while time.time() < deadline and not joined:
        try:
            packet_id, body = frame.read_frame()
        except TimeoutError:
            raise WorkloadFailure("join timed out")
        except (ConnectionError, OSError) as error:
            raise WorkloadFailure("join transport: %s" % error)
        if frame.state == "LOGIN":
            if packet_id == 0x03:  # SetCompression: the threshold is a
                # VarInt; reading its first byte alone is wrong for values
                # >= 128 (vanilla default 256) and desynchronises every
                # subsequent frame (measured: server "Bad packet id").
                frame.threshold = _varint_from(body)[0] if body else 0
                continue
            if packet_id == 0x02:  # LoginSuccess
                frame.state = "PLAY"
                continue
            if packet_id == CB_DISCONNECT_LOGIN:
                raise WorkloadFailure("login disconnect")
            if packet_id == CB_PLUGIN_MESSAGE:
                _plugin(frame, body, handshake, observed, registered, client_mods)
            continue
        if packet_id == CB_JOIN_GAME:
            observed["join_game"] = True
            continue
        if packet_id == CB_KEEP_ALIVE:
            frame.send(SB_KEEP_ALIVE, body[:8])
            observed["keepalives"] += 1
            continue
        if packet_id == CB_PLAYER_POS_LOOK:
            teleport_id = _varint_from(body)[0]
            frame.send(SB_CONFIRM_TELEPORT, varint(teleport_id))
            observed["teleports"] += 1
            frame.send(SB_CLIENT_SETTINGS,
                       write_string("en_US") + bytes([8]) + varint(0)
                       + b"\x00" + bytes([0x7f]) + varint(1))
            if handshake.complete:
                joined = True
            continue
        if packet_id == CB_PLUGIN_MESSAGE:
            _plugin(frame, body, handshake, observed, registered, client_mods)
            continue
        if packet_id == CB_DISCONNECT_PLAY:
            raise WorkloadFailure("play disconnect during join")
    if not joined:
        raise WorkloadFailure("never joined")
    return frame, observed


def _drain(frame, seconds: float, observed):
    """Consume server output for a bounded window, answering keep-alives."""
    deadline = time.time() + seconds
    frame.sock.settimeout(1.0)
    while time.time() < deadline:
        try:
            packet_id, body = frame.read_frame()
        except TimeoutError:
            continue
        except (ConnectionError, OSError) as error:
            raise WorkloadFailure("drain transport: %s" % error)
        if packet_id == CB_KEEP_ALIVE:
            frame.send(SB_KEEP_ALIVE, body[:8])
            observed["keepalives"] += 1
        elif packet_id == CB_PLAYER_POS_LOOK:
            teleport_id = _varint_from(body)[0]
            frame.send(SB_CONFIRM_TELEPORT, varint(teleport_id))
            observed["teleports"] += 1
        elif packet_id == CB_DISCONNECT_PLAY:
            raise WorkloadFailure("disconnect during drain")
        else:
            observed["chunk_packets"] += 1


def _walk_leg(frame, x0, z0, x1, z1, step_blocks, observed):
    """Position updates along one leg at ~20/s (the protocol's own rate)."""
    dx, dz = x1 - x0, z1 - z0
    length = max(abs(dx), abs(dz))
    steps = max(1, length // step_blocks)
    y = 80.0
    for i in range(1, steps + 1):
        x = x0 + dx * i / steps
        z = z0 + dz * i / steps
        payload = struct.pack(">ddd?", x, y, z, True)
        frame.send(SB_PLAYER_POSITION, payload)
        time.sleep(0.05)
        _drain(frame, 0.05, observed)
    observed.setdefault("legs_walked", 0)
    observed["legs_walked"] += 1


def run_workload(host, port, username, *, client_mods, legs, step_blocks,
                 reconnects, settle_s, trace_path):
    """The deterministic campaign workload for ONE server session."""
    trace = {"username": username, "legs": [list(leg) for leg in legs],
             "reconnects": reconnects, "events": []}
    observed_total = {"keepalives": 0, "teleports": 0, "chunk_packets": 0,
                      "legs_walked": 0}
    passes = reconnects + 1
    for attempt in range(passes):
        frame = None
        try:
            frame, observed = _connect_and_join(host, port, "%s-%d" % (username, attempt),
                                                client_mods)
            trace["events"].append({"pass": attempt, "joined": True})
            _drain(frame, settle_s, observed)
            for (x0, z0, x1, z1) in legs:
                _walk_leg(frame, x0, z0, x1, z1, step_blocks, observed)
                _drain(frame, settle_s, observed)
            for key in observed_total:
                observed_total[key] += observed.get(key, 0)
        except WorkloadFailure as failure:
            trace["events"].append({"pass": attempt, "failure": str(failure)})
        finally:
            if frame is not None:
                try:
                    frame.sock.shutdown(2)
                except OSError:
                    pass
                frame.close()
        time.sleep(2)
    trace["observed"] = observed_total
    trace["verdict"] = "PASS" if observed_total.get("legs_walked", 0) >= len(legs) else "PARTIAL"
    if trace_path is not None:
        trace_path.write_text(json.dumps(trace, indent=2) + "\n")
    return trace
