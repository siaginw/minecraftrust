#!/usr/bin/env python3
"""Live SHADOW campaign client session.

Extends the existing pinned protocol client (tools/protocol-live-probe.py —
same Conn framing, same FML handshake) with what a bounded live session needs:
KeepAlive replies, Teleport Confirm, Client Settings, and a slow movement loop
so chunks stream, unload, and reload naturally. Records every ChunkData packet
received as session evidence. No new client architecture; no fake traffic: this
is the real Minecraft protocol against the real server.
"""
import importlib.util
import json
import os
import socket
import struct
import sys
import time

_PROBE = os.path.join(os.path.dirname(__file__), "..", "protocol-live-probe.py")
_spec = importlib.util.spec_from_file_location("protocol_live_probe", _PROBE)
probe = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(probe)

# Serverbound PLAY ids for protocol 340 (1.12.2)
SB_TELEPORT_CONFIRM = 0x00
SB_CLIENT_SETTINGS = 0x04
SB_KEEP_ALIVE = 0x0B          # 1.12.2 serverbound Keep Alive
SB_PLAYER_POSITION = 0x0D     # 1.12.2 serverbound Player Position
SB_CUSTOM_PAYLOAD = 0x09      # 1.12.2 serverbound Custom Payload


def varint_wrap(n):
    return probe.varint(n)


def handle_fml(c, chan, body, summary):
    """Minimal FML|HS client: accept the server mod list, ack the phases, and
    ride out the registry stream. Keeps the vanilla-protocol probe admissible
    on modded Forge servers that require the FML marker."""
    if chan != b"FML|HS" or not body:
        return False
    disc = body[0]
    if disc == 0:  # ServerHello -> ClientHello + empty ModList
        c.send(SB_CUSTOM_PAYLOAD, s("FML|HS") + b"\x01\x02")
        c.send(SB_CUSTOM_PAYLOAD, s("FML|HS") + b"\x02" + varint_wrap(0))
        summary["fml"] = "hello-acked"
        return True
    if disc == 2:  # server ModList -> Ack(2)
        c.send(SB_CUSTOM_PAYLOAD, s("FML|HS") + b"?\x02")
        summary["fml"] = "modlist-acked"
        return True
    if disc == 3:  # RegistryData stream -> Ack(3) on the final packet
        has_more = body[1] if len(body) > 1 else 0
        if not has_more:
            c.send(SB_CUSTOM_PAYLOAD, s("FML|HS") + b"\xff\x03")
            summary["fml"] = "registry-acked"
        return True
    if disc == 0xFF:
        summary["fml"] = "server-ack-" + str(body[1] if len(body) > 1 else -1)
        return True
    return False


def run_session(port: int, duration_s: int, move: bool, sink_path: str,
                out_summary: str, player_name: str = "ProbeBot", spawn_pos=None,
                direction: int = 1) -> dict:
    summary = {"joined": False, "chunkPackets": 0, "keepAlives": 0,
               "teleports": 0, "positionsSent": 0, "errors": [], "frames": 0}
    c = probe.Conn(probe.HOST, port)
    c.send(0x00, varint_wrap(probe.PROTO) + probe.s("localhost\0FML\0") + struct.pack(">H", port) + varint_wrap(2))
    c.state = "LOGIN"
    fl = probe.fl
    with open(sink_path, "w") as sink:
        c.send(0x00, probe.s(player_name))  # LoginStart
        fl(c, 0x00, b"", "LoginStart", "C", sink)
        deadline = time.time() + duration_s
        pos = dict(spawn_pos) if spawn_pos else None
        last_move = 0.0
        settings_sent = False
        while time.time() < deadline:
            try:
                pid, p = c.read_frame()
            except (ConnectionError, socket.timeout) as closed:
                summary["errors"].append("closed: %s" % closed)
                break
            summary["frames"] += 1
            if c.state == "PLAY" and pid == 0x18:  # CustomPayload: FML|HS ride
                chan, off = probe.read_str(p)
                if handle_fml(c, chan, p[off:], summary):
                    summary["fmlHandled"] = summary.get("fmlHandled", 0) + 1
                    continue
            if c.state == "LOGIN":
                if pid == 0x03:
                    c.compressed = True
                    c.threshold = int.from_bytes(p, "big")
                    continue
                if pid == 0x02:
                    c.state = "PLAY"
                    fl(c, pid, p, "LoginSuccess", "S", sink)
                    continue
                if pid == 0x00:
                    reason, _ = probe.read_str(p)
                    summary["errors"].append("login disconnect: " + reason.decode("utf-8", "replace"))
                    break
                continue
            # PLAY state
            if pid == 0x23:  # JoinGame
                summary["joined"] = True
                fl(c, pid, p, "JoinGame", "S", sink)
                continue
            if pid == 0x20:  # ChunkData
                summary["chunkPackets"] += 1
                continue
            if pid == 0x1F:  # KeepAlive (clientbound) -> reply serverbound 0x0C
                summary["keepAlives"] += 1
                c.send(SB_KEEP_ALIVE, p)
                continue
            summary.setdefault("pidHistogram", {})
            summary["pidHistogram"][str(pid)] = summary["pidHistogram"].get(str(pid), 0) + 1
            if pid in (0x26, 0x2F):  # PlayerPosLook candidates (protocol 340)
                summary["posLookId"] = hex(pid)
                summary["teleports"] += 1
                # payload: x(8) y(8) z(8) yaw(4) pitch(4) flags(1) teleportId(varint)
                off = 8 + 8 + 8 + 4 + 4 + 1
                val, _rest = c._try_varint(p[off:])
                if val is not None:
                    c.send(SB_TELEPORT_CONFIRM, varint_wrap(val))
                    summary["positionsSent"] += 1
                pos = {"x": struct.unpack(">d", p[0:8])[0],
                       "y": struct.unpack(">d", p[8:16])[0],
                       "z": struct.unpack(">d", p[16:24])[0]}
                if not settings_sent:
                    c.send(SB_CLIENT_SETTINGS,
                           probe.s("en_US") + bytes([6]) + varint_wrap(0) + b"\x00\xff" + varint_wrap(1))
                    settings_sent = True
                last_move = time.time()
                fl(c, pid, p[:8], "PlayerPosLook(confirmed)", "S", sink)
                continue
            # movement loop: walk along X so chunk borders stream naturally
            if move and pos is not None and time.time() - last_move >= 1.0:
                pos["x"] += 8.0 * direction  # 8 m/s stays under the vanilla 100-block-squared quick-move check
                body = struct.pack(">ddd", pos["x"], pos["y"], pos["z"]) + b"\x00"
                c.send(SB_PLAYER_POSITION, body)
                summary["positionsSent"] += 1
                last_move = time.time()
        try:
            c.sock.close()
        except OSError:
            pass
    os.makedirs(os.path.dirname(out_summary) or ".", exist_ok=True)
    with open(out_summary, "w") as out:
        json.dump(summary, out, indent=2)
    return summary


if __name__ == "__main__":
    port = int(sys.argv[1])
    duration = int(sys.argv[2])
    mode = sys.argv[3] if len(sys.argv) > 3 else "hold"
    move = mode in ("move", "walkback")
    direction = -1 if mode == "walkback" else 1
    sink = sys.argv[4] if len(sys.argv) > 4 else "client-session.jsonl"
    out = sys.argv[5] if len(sys.argv) > 5 else "client-session.json"
    spawn = None
    if len(sys.argv) > 6:
        parts = [float(v) for v in sys.argv[6].split(",")]
        spawn = {"x": parts[0], "y": parts[1], "z": parts[2]}
    print(json.dumps(run_session(port, duration, move, sink, out, spawn_pos=spawn,
                                 direction=direction)))
# MARKER_TEST
