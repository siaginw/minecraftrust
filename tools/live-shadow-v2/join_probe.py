"""The bounded V2 headless join probe.

This is NOT a shadow campaign. It connects one headless client to one real
server, drives the protocol 340 login and the FML|HS handshake, holds the
connection for a bounded stability interval, KeepAlives, and disconnects
cleanly. No chunk packet it receives is compared to anything, no Rust code is
called, and the probe has no code path capable of putting bytes on the wire
other than its own protocol replies.

Success is defined before the run (see PROBE_SUCCESS) and evaluated against
observed facts, never against "the socket stayed open".
"""
from __future__ import annotations

import json
import time

from fml_handshake import FML_CHANNEL, FmlHandshake, HandshakeRejected
from protocol340 import (CB_CHUNK_DATA, CB_DISCONNECT_LOGIN, CB_DISCONNECT_PLAY,
                         CB_JOIN_GAME, CB_KEEP_ALIVE, CB_PLUGIN_MESSAGE,
                         CB_PLAYER_POS_LOOK, Frame, SB_CLIENT_SETTINGS,
                         SB_CONFIRM_TELEPORT, SB_KEEP_ALIVE, SB_PLUGIN_MESSAGE,
                         fml_marker, handshake_packet, login_start_packet,
                         read_varint, varint, write_string)

#: Every condition that must hold for PASS, so the verdict is checkable
#: against the receipt rather than a judgement call made after the fact.
PROBE_SUCCESS = (
    "tcp_connected",
    "login_completed",
    "fml_handshake_complete",
    "play_reached",
    "join_game_observed",
    "keepalive_exchanged",
    "stability_held",
    "disconnect_clean",
)


class ProbeFailure(RuntimeError):
    pass


def run_probe(host: str, port: int, username: str, *, expect_forge: bool,
              connect_timeout_s: float = 15.0, login_timeout_s: float = 60.0,
              stability_s: float = 20.0, max_packets: int = 4096,
              max_bytes: int = 64 << 20) -> dict:
    """One bounded join attempt. Returns the receipt dict; never retries.

    `expect_forge` selects whether the login carries the FML marker and the
    handshake is required -- a vanilla-only server must be probed in vanilla
    mode, and its success must not be counted as an FML success.
    """
    receipt: dict = {
        "schema": "RUSTCRAFT_V2_JOIN_PROBE_V1",
        "client_implementation": "headless-protocol340-v2",
        "protocol_version": 340,
        "expect_forge": expect_forge,
        "username": username,
        "bound": {"connect_timeout_s": connect_timeout_s,
                  "login_timeout_s": login_timeout_s,
                  "stability_s": stability_s,
                  "max_packets": max_packets, "max_bytes": max_bytes},
        "observed": {},
        "fml": None,
        "channels": {"registered": [], "unknown": []},
        "classification": {},
        "times": {},
        "verdict": "FAIL",
        "checks": {},
    }
    observed = receipt["observed"]
    observed.update({"tcp_connected": False, "login_completed": False,
                     "fml_handshake_complete": False, "play_reached": False,
                     "join_game_observed": False, "keepalive_exchanged": False,
                     "stability_held": False, "disconnect_clean": False})
    handshake = FmlHandshake()
    frame: Frame | None = None
    registered: set[str] = set()

    try:
        # ---- connect -------------------------------------------------------
        t0 = time.time()
        frame = Frame(host, port, connect_timeout_s)
        observed["tcp_connected"] = True
        receipt["times"]["connect_s"] = round(time.time() - t0, 3)

        # ---- handshake + login --------------------------------------------
        frame.send(0x00, handshake_packet(fml_marker(host) if expect_forge else host,
                                          port, 2))
        frame.state = "LOGIN"
        frame.send(0x00, login_start_packet(username))

        t1 = time.time()
        while time.time() - t1 < login_timeout_s:
            if frame.packets_in >= max_packets or frame.bytes_in >= max_bytes:
                raise ProbeFailure("probe bound reached during login")
            try:
                packet_id, body = frame.read_frame()
            except TimeoutError:
                raise ProbeFailure("login timed out waiting for a packet")
            if frame.state == "LOGIN":
                if packet_id == 0x03:  # SetCompression
                    frame.threshold = read_varint(body)[0]
                    continue
                if packet_id == 0x02:  # LoginSuccess
                    frame.state = "PLAY"
                    observed["login_completed"] = True
                    receipt["times"]["login_s"] = round(time.time() - t1, 3)
                    continue
                if packet_id == CB_DISCONNECT_LOGIN:
                    reason, _ = _read_chat(body)
                    raise ProbeFailure("login disconnect: " + reason)
                continue

            # ---- PLAY -----------------------------------------------------
            if packet_id == CB_JOIN_GAME:
                observed["join_game_observed"] = True
                continue
            if packet_id == CB_KEEP_ALIVE:
                key = body[:8]
                frame.send(SB_KEEP_ALIVE, key)
                observed["keepalive_exchanged"] = True
                observed["last_keepalive_epoch"] = time.time()
                continue
            if packet_id == CB_PLAYER_POS_LOOK:
                # Confirm the teleport so the server considers us present.
                teleport_id, _ = _read_poslook(body)
                frame.send(SB_CONFIRM_TELEPORT, varint(teleport_id))
                frame.send(SB_CLIENT_SETTINGS,
                           write_string("en_US") + bytes([8]) + varint(0) + b"\x00\xff" + varint(1))
                continue
            if packet_id == CB_PLUGIN_MESSAGE:
                channel, offset = _read_channel(body)
                payload = body[offset:]
                if channel == FML_CHANNEL:
                    reply = handshake.on_server_message(payload)
                    if reply is not None:
                        frame.send(SB_PLUGIN_MESSAGE,
                                   write_string(FML_CHANNEL) + reply)
                    if handshake.complete:
                        observed["fml_handshake_complete"] = True
                elif channel.startswith("FML|"):
                    # Forge control channels: registered/reserved traffic.
                    registered.add(channel)
                else:
                    registered.add(channel)
                    receipt["channels"]["unknown"].append(channel)
                continue
            if packet_id == CB_CHUNK_DATA:
                observed["chunk_packets"] = observed.get("chunk_packets", 0) + 1
                # Deliberately NOT parsed and NOT compared: receiving a chunk
                # packet is a connectivity fact, not a shadow event.
                continue
            if packet_id == CB_DISCONNECT_PLAY:
                reason, _ = _read_chat(body)
                raise ProbeFailure("play disconnect: " + reason)

            # First PLAY packet of any kind proves the state transition.
            observed["play_reached"] = True

            if (observed["join_game_observed"] and observed["login_completed"]
                    and (observed["fml_handshake_complete"] or not expect_forge)):
                break

        if not observed["login_completed"]:
            raise ProbeFailure("login phase ended without LoginSuccess")
        if expect_forge and not observed["fml_handshake_complete"]:
            raise ProbeFailure("FML|HS handshake did not complete")
        if not observed["join_game_observed"]:
            raise ProbeFailure("reached PLAY without JoinGame")

        # ---- bounded stability -------------------------------------------
        observed["play_reached"] = True
        t2 = time.time()
        last_activity = time.time()
        while time.time() - t2 < stability_s:
            if frame.packets_in >= max_packets or frame.bytes_in >= max_bytes:
                raise ProbeFailure("probe bound reached during stability hold")
            try:
                packet_id, body = frame.read_frame()
            except TimeoutError:
                continue
            last_activity = time.time()
            observed["play_reached"] = True
            if packet_id == CB_KEEP_ALIVE:
                frame.send(SB_KEEP_ALIVE, body[:8])
                observed["keepalive_exchanged"] = True
            elif packet_id == CB_DISCONNECT_PLAY:
                reason, _ = _read_chat(body)
                raise ProbeFailure("disconnect during stability: " + reason)
        observed["stability_held"] = True
        receipt["times"]["stability_held_s"] = round(time.time() - t2, 3)

        # ---- intentional disconnect --------------------------------------
        frame.sock.shutdown(2)
        observed["disconnect_clean"] = True

    except HandshakeRejected as rejected:
        handshake.reject_reason = rejected.reason
        receipt["failure"] = str(rejected)
    except ProbeFailure as failure:
        receipt["failure"] = str(failure)
    except (ConnectionError, OSError, TimeoutError, ValueError) as error:
        receipt["failure"] = "transport: %s" % error
    finally:
        if frame is not None:
            receipt["packets_in"] = frame.packets_in
            receipt["packets_out"] = frame.packets_out
            receipt["bytes_in"] = frame.bytes_in
            frame.close()

    receipt["fml"] = handshake.summary()
    receipt["channels"]["registered"] = sorted(registered)
    receipt["channels"]["unknown"] = sorted(set(receipt["channels"]["unknown"]))
    # Unknown mod channels are classified, not guessed at. SAFE_NOOP means we
    # ignored them and the server did not object within the stability window;
    # BLOCKING_UNKNOWN would mean the server demanded a semantic reply.
    receipt["classification"]["unknown_channels"] = (
        "SAFE_NOOP" if observed.get("stability_held") and not receipt.get("failure")
        else "BLOCKING_UNKNOWN" if receipt["channels"]["unknown"] else "NONE")
    receipt["observed"] = observed
    receipt["checks"] = {name: observed.get(name, False) for name in PROBE_SUCCESS}
    receipt["verdict"] = "PASS" if all(receipt["checks"].values()) else "FAIL"
    return receipt


# ---- small parsers ---------------------------------------------------------

def _read_chat(body: bytes) -> tuple[str, int]:
    from protocol340 import read_string
    return read_string(body, 0)


def _read_channel(body: bytes) -> tuple[str, int]:
    from protocol340 import read_string
    return read_string(body, 0)


def _read_poslook(body: bytes) -> tuple[int, int]:
    """x f64, y f64, z f64, yaw f32, pitch f32, flags u8, teleportId VarInt."""
    import struct
    offset = 8 + 8 + 8 + 4 + 4 + 1
    return read_varint(body, offset)


if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("host")
    parser.add_argument("port", type=int)
    parser.add_argument("--username", default="RustCraftProbe")
    parser.add_argument("--forge", action="store_true",
                        help="send the FML marker and require the FML|HS handshake")
    parser.add_argument("--stability-s", type=float, default=20.0)
    parser.add_argument("--out", default=None)
    args = parser.parse_args()
    result = run_probe(args.host, args.port, args.username,
                       expect_forge=args.forge, stability_s=args.stability_s)
    text = json.dumps(result, indent=2, sort_keys=True)
    if args.out:
        with open(args.out, "w", encoding="utf-8") as handle:
            handle.write(text + "\n")
    print(text)
