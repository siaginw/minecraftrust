"""The FML|HS handshake state machine for the V2 headless client.

Ported conceptually from the archived V1 prototype
(archive/revelation-v1-shadow:tools/live-capture/live_client_session.py),
with the two corrections the design review identified and one deliberate
architectural change:

  * packet ids verified against docs/protocol-340/packet-registry-wire.md
    rather than trusted from the V1 source -- the V1 KeepAlive CONSTANT was
    right (0x0B) but its comment was wrong, so a porter reading the comment
    would have introduced a real bug;
  * the client ModList is ECHOED FROM THE SERVER'S OWN LIST, not sent empty.
    V1 sent zero mods, which a 219-mod server's NetworkCheckHandler is
    expected to reject. Echoing the server's in-band list is not a fabricated
    inventory: it is the exact (modid, version) set the server itself just
    declared, recorded in the receipt as derived-from-server-handshake;
  * the handshake is an explicit state machine that records every transition,
    instead of a handler that reacts and updates a summary string. Success is
    a reached state, never "the socket stayed open".

FML|HS discriminator bytes (from the 1.12.2 Forge handshake): 0 ServerHello,
1 ClientHello, 2 ModList, 3 RegistryData, 0xFF server Ack. The server drives;
the client responds.
"""
from __future__ import annotations

from protocol340 import read_string, read_varint, varint, write_string

FML_CHANNEL = "FML|HS"
FML_PROTOCOL_VERSION = 2

#: The channel set every genuine Forge client registers on ServerHello, in the
#: order FMLHandshakeMessage.makeCustomChannelRegistration builds it: the three
#: built-ins first, then any registered channel names. A headless client with
#: no mod channels registers exactly the built-ins. NUL-joined, raw bytes, no
#: length prefix -- the payload IS the joined string, per the real encoder.
FML_BUILTIN_CHANNELS = ("FML|HS", "FML", "FML|MP")


def channel_registration(channels=()) -> bytes:
    """The REGISTER payload a genuine client sends: NUL-joined channel names."""
    names = list(FML_BUILTIN_CHANNELS) + [c for c in channels if c not in FML_BUILTIN_CHANNELS]
    return chr(0).join(names).encode("utf-8")

DISC_SERVER_HELLO = 0
DISC_CLIENT_HELLO = 1
DISC_MOD_LIST = 2
DISC_REGISTRY_DATA = 3
DISC_SERVER_ACK = 0xFF

# The state machine. Names are recorded verbatim in the join receipt.
S_WAIT_SERVER_HELLO = "WAIT_SERVER_HELLO"
S_HELLO_SENT = "HELLO_SENT"
S_MODLIST_SENT = "MODLIST_SENT"
S_REGISTRY_STREAM = "REGISTRY_STREAM"
S_COMPLETE = "HANDSHAKE_COMPLETE"

#: Ordered for the receipt; a skipped state is visible as a gap.
STATE_ORDER = (S_WAIT_SERVER_HELLO, S_HELLO_SENT, S_MODLIST_SENT,
               S_REGISTRY_STREAM, S_COMPLETE)


class HandshakeRejected(RuntimeError):
    """The server refused the handshake. Carries the server's own reason."""

    def __init__(self, reason: str, state: str):
        super().__init__("FML|HS rejected in %s: %s" % (state, reason))
        self.reason = reason
        self.state = state


class FmlHandshake:
    """Drives the client side of FML|HS, recording every transition.

    The caller feeds each received FML|HS payload to
    :meth:`on_server_message` and gets back the bytes to send (or None), so
    the state machine owns correctness while the connection layer owns IO.
    """

    def __init__(self):
        self.state = S_WAIT_SERVER_HELLO
        self.transitions: list[dict] = []
        self.server_mod_list: list[tuple[str, str]] = []
        self.client_mod_list: list[tuple[str, str]] = []
        self.registry_messages = 0
        self.reject_reason: str | None = None
        self._record(None, S_WAIT_SERVER_HELLO, "start")

    # ---- driver -----------------------------------------------------------

    def _record(self, frm, to, why: str) -> None:
        self.state = to
        self.transitions.append({"from": frm, "to": to, "why": why})

    def on_server_message(self, payload: bytes) -> bytes | None:
        """One server FML|HS message in; the reply payload out, or None."""
        if not payload:
            raise HandshakeRejected("empty FML|HS payload", self.state)
        discriminator = payload[0]
        body = payload[1:]

        if discriminator == DISC_SERVER_HELLO:
            if self.state != S_WAIT_SERVER_HELLO:
                raise HandshakeRejected(
                    "ServerHello while in %s" % self.state, self.state)
            version = body[0] if body else -1
            self._record(S_WAIT_SERVER_HELLO, S_HELLO_SENT,
                         "ServerHello(fml=%d) -> ClientHello + REGISTER" % version)
            # The genuine client sends TWO things on ServerHello, per
            # FMLHandshakeClientState$2: its ClientHello, then a channel
            # registration on the REGISTER channel. This machine returns the
            # FML|HS reply; the caller sends the registration from
            # channel_registration() -- it is a different channel, so it cannot
            # ride in this payload.
            return bytes([DISC_CLIENT_HELLO, FML_PROTOCOL_VERSION])

        if discriminator == DISC_MOD_LIST:
            if self.state != S_HELLO_SENT:
                raise HandshakeRejected(
                    "ModList while in %s" % self.state, self.state)
            self.server_mod_list = _parse_mod_list(body)
            # The client echoes the server's own declared list back. This is
            # the exact set the server just sent, not a guess and not a
            # fabrication: the receipt records the derivation.
            self.client_mod_list = list(self.server_mod_list)
            self._record(S_HELLO_SENT, S_MODLIST_SENT,
                         "ModList(%d mods) -> echo" % len(self.server_mod_list))
            return _render_mod_list(self.client_mod_list)

        if discriminator == DISC_REGISTRY_DATA:
            if self.state not in (S_MODLIST_SENT, S_REGISTRY_STREAM):
                raise HandshakeRejected(
                    "RegistryData while in %s" % self.state, self.state)
            self.registry_messages += 1
            has_more = body[0] if body else 0
            if self.state == S_MODLIST_SENT:
                self._record(S_MODLIST_SENT, S_REGISTRY_STREAM,
                             "RegistryData stream began")
            if not has_more:
                self._record(S_REGISTRY_STREAM, S_COMPLETE,
                             "final RegistryData acked")
            # Ack the registry phase.
            return bytes([DISC_SERVER_ACK, DISC_REGISTRY_DATA])

        if discriminator == DISC_SERVER_ACK:
            if self.state == S_REGISTRY_STREAM:
                self._record(S_REGISTRY_STREAM, S_COMPLETE, "server Ack")
            return None

        raise HandshakeRejected(
            "unexpected FML|HS discriminator 0x%02x" % discriminator, self.state)

    @property
    def complete(self) -> bool:
        return self.state == S_COMPLETE

    # ---- receipt ----------------------------------------------------------

    def summary(self) -> dict:
        return {
            "initial_state": S_WAIT_SERVER_HELLO,
            "final_state": self.state,
            "complete": self.complete,
            "transitions": self.transitions,
            "server_mod_count": len(self.server_mod_list),
            "client_mod_count": len(self.client_mod_list),
            "client_mod_list_derivation": "echoed-from-server-handshake",
            "registry_messages": self.registry_messages,
            "reject_reason": self.reject_reason,
        }


def render_client_mod_list(mods: list[tuple[str, str]]) -> bytes:
    """The client's ModList message: discriminator 2 + count + (id, version)*.

    The inventory must be DERIVED from the runtime's own evidence (the server
    logs its mod list during startup), never hardcoded per pack. The shape
    mirrors FMLHandshakeMessage$ModList.toBytes: discriminator byte 2, a VarInt
    count, then per mod a modid String and a version String.
    """
    out = bytearray([DISC_MOD_LIST])
    out += varint(len(mods))
    from protocol340 import write_string
    for modid, version in mods:
        out += write_string(modid)
        out += write_string(version)
    return bytes(out)


def _parse_mod_list(body: bytes) -> list[tuple[str, str]]:
    """count:VarInt then per mod (modid:String, version:String)."""
    count, offset = read_varint(body)
    if count > 4096:
        raise HandshakeRejected("implausible mod count %d" % count, "MOD_LIST")
    mods = []
    for _ in range(count):
        modid, offset = read_string(body, offset)
        version, offset = read_string(body, offset)
        mods.append((modid, version))
    return mods


def _render_mod_list(mods: list[tuple[str, str]]) -> bytes:
    out = bytearray([DISC_MOD_LIST])
    out += varint(len(mods))
    for modid, version in mods:
        out += write_string(modid)
        out += write_string(version)
    return bytes(out)
