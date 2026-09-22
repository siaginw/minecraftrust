#!/usr/bin/env python3
"""Minimal Source RCON client (M1I perf driver: op the deterministic bots).

Usage: python tools/m1i_rcon.py <host> <port> <password> <command...>
"""
import socket, struct, sys


def _pkt(rid, ptype, body):
    data = struct.pack("<ii", rid, ptype) + body.encode("utf-8") + b"\x00\x00"
    return struct.pack("<i", len(data)) + data


def main():
    host, port, pw = sys.argv[1], int(sys.argv[2]), sys.argv[3]
    cmd = " ".join(sys.argv[4:])
    s = socket.create_connection((host, port), timeout=5)
    s.sendall(_pkt(1, 3, pw))  # auth
    resp = s.recv(64)
    if len(resp) < 14 or struct.unpack("<i", resp[8:12])[0] == -1:
        print("AUTH FAILED", file=sys.stderr)
        sys.exit(1)
    s.sendall(_pkt(2, 2, cmd))
    import time
    time.sleep(0.3)
    out = b""
    s.settimeout(1.0)
    try:
        while True:
            b = s.recv(4096)
            if not b:
                break
            out += b
    except socket.timeout:
        pass
    print(out[12:].split(b"\x00")[0].decode("utf-8", "replace"))
    s.close()


if __name__ == "__main__":
    main()
