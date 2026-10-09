#!/usr/bin/env python3
"""P0-5 synthetic protocol client for the reference Forge 1.12.2 server.

Scenarios: status / vanilla / forge. Records every frame to JSONL transcript.
Compression-aware: usz layer active only after SetCompression (LOGIN 0x03).
"""
import json, os, socket, struct, sys, time, zlib

HOST = "127.0.0.1"
PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 25565
OUT_DIR = os.path.join("benchmarks", "protocol", "p0-5")
PROTO = 340

def varint(n: int) -> bytes:
    out = b""
    while True:
        b = n & 0x7F
        n >>= 7
        if n:
            out += bytes([b | 0x80])
        else:
            out += bytes([b])
            return out

class Conn:
    def __init__(self, host, port):
        self.sock = socket.create_connection((host, port), timeout=30)
        self.buf = b""
        self.compressed = False          # after SetCompression
        self.state = "HANDSHAKE"         # HANDSHAKE/STATUS/LOGIN/PLAY
        self.threshold = -1

    # -- recv --
    def _recv(self):
        chunk = self.sock.recv(262144)
        if not chunk:
            raise ConnectionError("closed")
        self.buf += chunk

    @staticmethod
    def _try_varint(buf):
        val, count = 0, 0
        for i, b in enumerate(buf[:5]):
            val |= (b & 0x7F) << (7 * i)
            count += 1
            if not (b & 0x80):
                return val, buf[count:]
        return None, None

    def read_varint(self):
        while True:
            val, new = self._try_varint(self.buf)
            if new is not None:
                self.buf = new
                return val
            self._recv()

    def read_frame(self):
        length = self.read_varint()
        while len(self.buf) < length:
            self._recv()
        body, self.buf = self.buf[:length], self.buf[length:]
        if self.compressed:
            usz = self._try_varint(body)[0]
            data = body[len(varint(usz)):]
            if usz > 0:
                data = zlib.decompress(data)
        else:
            data = body
        pid = self._try_varint(data)[0]
        return pid, data[len(varint(pid)):]

    # -- send --
    def send(self, pid, payload):
        body = varint(pid) + payload
        if self.compressed and self.threshold >= 0 and len(body) >= self.threshold:
            comp = zlib.compress(body)
            body = varint(len(body)) + comp
        elif self.compressed:
            body = varint(0) + body
        self.sock.sendall(varint(len(body)) + body)

def s(x: str) -> bytes:
    b = x.encode("utf-8")
    return varint(len(b)) + b

def log(rec, sink):
    rec["t"] = round(time.time(), 3)
    sink.write(json.dumps(rec) + "\n"); sink.flush()
    d = "C->S" if rec["dir"] == "C" else "S->C"
    print(f"{d} {rec['name']} id=0x{rec['id']:02x} len={rec['len']}"
          + (f" {rec.get('payload_hex','')[:40]}" if rec.get("payload_hex") else ""))

def fl(c, pid, payload, name, dir_, sink, max_hex=64):
    log({"dir": dir_, "id": pid, "name": name, "len": len(payload),
         "payload_hex": payload[:max_hex].hex(), "state": c.state,
         "compressed": c.compressed}, sink)

def read_str(buf):
    r = Conn.__new__(Conn); r.buf = buf
    n = r.read_varint()
    ln = len(varint(n))
    return buf[ln:ln+n], ln+n

# ---------------- scenarios ----------------
def status(sink):
    c = Conn(HOST, PORT)
    c.send(0x00, varint(PROTO) + s("localhost") + struct.pack(">H", PORT) + varint(1))
    c.state = "STATUS"
    fl(c, 0x00, b"", "Handshake->STATUS", "C", sink)
    c.send(0x00, b""); fl(c, 0x00, b"", "StatusRequest", "C", sink)
    pid, p = c.read_frame(); fl(c, pid, p, "StatusResponse", "S", sink)
    c.send(0x01, struct.pack(">q", 12345)); fl(c, 0x01, b"", "Ping", "C", sink)
    pid, p = c.read_frame(); fl(c, pid, p, "Pong", "S", sink)
    c.sock.close()

LOGIN_NAME = {0x00: "Disconnect", 0x01: "EncryptionRequest", 0x02: "LoginSuccess",
              0x03: "SetCompression"}
PLAY_NAME = {0x18: "CustomPayload", 0x23: "JoinGame", 0x0B: "BlockChange",
             0x20: "ChunkData", 0x1F: "KeepAlive", 0x26: "PlayerPosLook",
             0x35: "DestroyEntities", 0x32: "CombatEvent", 0x4B: "TimeUpdate",
             0x2E: "EntityVelocity", 0x25: "EntityMetadata", 0x05: "NotifyClientId"}

def login(fml: bool, sink, max_frames=2000):
    c = Conn(HOST, PORT)
    host = "localhost" + ("\0FML\0" if fml else "")
    c.send(0x00, varint(PROTO) + s(host) + struct.pack(">H", PORT) + varint(2))
    c.state = "LOGIN"
    fl(c, 0x00, b"", "Handshake->LOGIN" + ("+FML" if fml else ""), "C", sink)
    c.send(0x00, s("ProbeBot")); fl(c, 0x00, b"", "LoginStart", "C", sink)
    frames = 0
    join_seen = False
    while frames < max_frames:
        try:
            pid, p = c.read_frame()
        except (ConnectionError, socket.timeout):
            print(f"[closed after {frames} frames]"); break
        frames += 1
        name = (LOGIN_NAME if c.state == "LOGIN" else PLAY_NAME).get(pid, f"0x{pid:02x}")
        if c.state == "LOGIN" and pid == 0x03:
            c.compressed = True
            c.threshold = int.from_bytes(p, "big")
            fl(c, pid, p, f"SetCompression(threshold={c.threshold})", "S", sink)
            continue
        if c.state == "LOGIN" and pid == 0x02:
            fl(c, pid, p, "LoginSuccess", "S", sink)
            c.state = "PLAY"
            continue
        if c.state == "LOGIN" and pid == 0x00:
            fl(c, pid, p, "Disconnect(login)", "S", sink)
            reason, _ = read_str(p)
            print("[login disconnect]", reason.decode("utf-8", "replace"))
            break
        if fml and pid == 0x18 and c.state == "PLAY":
            chan, off = read_str(p)
            body = p[off:]
            handled = handle_fml(c, chan, body, sink)
            fl(c, pid, body, f"CustomPayload({chan})" + ("*" if handled else ""), "S", sink, 16)
            continue
        fl(c, pid, p, name, "S", sink, 32)
        if pid == 0x23:
            join_seen = True
            print("[JoinGame received]")
    c.sock.close()
    return join_seen

def handle_fml(c, chan, body, sink):
    if chan != b"FML|HS" or not body:
        return False
    disc = body[0]
    if disc == 0:  # ServerHello(protocol=2)
        c.send(0x09, s("FML|HS") + b"\x01\x02"); fl(c, 0x09, b"", "FML ClientHello", "C", sink)
        c.send(0x09, s("FML|HS") + b"\x02\x00"); fl(c, 0x09, b"", "FML ModList(empty)", "C", sink)
        return True
    if disc == 2:  # server ModList -> parse count, ack phase 2
        r = Conn.__new__(Conn); r.buf = body[1:]
        cnt = r.read_varint()
        fl(c, 0x18, varint(cnt), f"FML server ModList(count={cnt})", "S", sink)
        c.send(0x09, s("FML|HS") + b"\xff\x02"); fl(c, 0x09, b"", "FML Ack(2)", "C", sink)
        return True
    if disc == 3:  # RegistryData
        has_more = body[1] if len(body) > 1 else 0
        nm, off = read_str(body[2:]) if len(body) > 2 else ("?", 0)
        fl(c, 0x18, body[:2], f"FML RegistryData({nm} more={has_more})", "S", sink, 4)
        if not has_more:
            c.send(0x09, s("FML|HS") + b"\xff\x03"); fl(c, 0x09, b"", "FML Ack(3)", "C", sink)
        return True
    if disc == 0xFF:
        phase = body[1] if len(body) > 1 else -1
        fl(c, 0x18, body[:2], f"FML server Ack({phase})", "S", sink)
        if phase == 3:
            c.send(0x09, s("FML|HS") + b"\xff\x03"); fl(c, 0x09, b"", "FML Ack(3)", "C", sink)
        return True
    return False

if __name__ == "__main__":
    os.makedirs(OUT_DIR, exist_ok=True)
    scenario = sys.argv[2] if len(sys.argv) > 2 else "status"
    out = os.path.join(OUT_DIR, f"live_trace_{scenario}.jsonl")
    with open(out, "w") as sink:
        if scenario == "status":
            status(sink)
        elif scenario == "vanilla":
            login(fml=False, sink=sink)
        elif scenario == "forge":
            login(fml=True, sink=sink)
    print("transcript:", out)
