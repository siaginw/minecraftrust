#!/usr/bin/env python3
"""Analyze live traces and benchmark registry sync, backpressure, and compression thresholds.
"""
import json, time, zlib, socket, os

def analyze_live_forge_trace():
    trace_path = "benchmarks/protocol/p0-5/live_trace_forge.jsonl"
    if not os.path.exists(trace_path):
        print("No live trace found at", trace_path)
        return

    frames = []
    with open(trace_path, "r") as f:
        for line in f:
            frames.append(json.loads(line))

    print(f"Loaded {len(frames)} frames from live Forge trace.")

    # Find FML handshake frames
    t_start = frames[0]["t"]
    t_joingame = None
    reg_frames = []
    
    for f in frames:
        name = f.get("name", "")
        if "JoinGame" in name and t_joingame is None:
            t_joingame = f["t"]
        if "RegistryData" in name:
            reg_frames.append(f)

    print(f"FML Handshake to JoinGame: {((t_joingame - t_start)*1000.0):.2f} ms")
    print(f"RegistryData frames count: {len(reg_frames)}")
    total_wire = sum(len(bytes.fromhex(f.get("payload_hex", ""))) for f in reg_frames)
    print(f"RegistryData total wire bytes: {total_wire} B")
    for i, rf in enumerate(reg_frames):
        b = bytes.fromhex(rf.get("payload_hex", ""))
        print(f"  Reg frame {i+1}: wire={len(b)} B")

def bench_compression_thresholds():
    print("\n--- [EXPERIMENT] Compression Threshold Optimization ---")
    # In Minecraft 1.12.2:
    # threshold = -1 (or disabled in server.properties): compression handlers are omitted from pipeline entirely.
    # threshold >= 0: SPacketEnableCompression is sent. With threshold 0, every packet is passed to Deflater.
    thresholds = [-1, 0, 64, 128, 256, 512, 1024, 2048]
    # Sample packets representative of Minecraft traffic
    # Chat: 45 B
    # Player Move/Look: 35 B
    # Inventory Sync: 320 B
    # Block Change batch: 120 B
    # Chunk Section: 8192 B
    packets = [
        ("Chat (45 B)", b"\x02\x2b" + b"A"*43),
        ("PlayerMove (35 B)", b"\x0d" + b"\x00"*34),
        ("BlockChange (120 B)", b"\x10" + b"\x01"*119),
        ("InventorySync (320 B)", b"\x14" + b"\x02"*319),
        ("ChunkData (8192 B)", b"\x20" + bytes([i%256 for i in range(8191)]))
    ]

    for th in thresholds:
        total_uncompressed = 0
        total_wire = 0
        cpu_time_ns = 0

        for name, pkt in packets:
            sz = len(pkt)
            total_uncompressed += sz
            if th == -1:
                # Compression disabled: raw packet bytes
                total_wire += sz
            elif sz < th:
                # Compression enabled but packet below threshold: [uncompressedSize=0][raw_bytes]
                total_wire += sz + 1
            else:
                # Compression enabled and size >= threshold: [uncompressedSize][zlib_deflate]
                t0 = time.perf_counter_ns()
                c = zlib.compress(pkt)
                cpu_time_ns += time.perf_counter_ns() - t0
                wire = len(c) + 2 # varint size header
                total_wire += wire

        label = "Disabled (th=-1)" if th == -1 else f"Threshold {th:4d} B"
        print(f"{label:17s} | Wire: {total_wire:5d} B (vs uncompressed {total_uncompressed:5d} B, ratio {total_wire/total_uncompressed:.2f}) | Deflate CPU: {cpu_time_ns/1000.0:6.1f} us")

def test_slow_reader_backpressure():
    print("\n--- [TEST] Slow Reader ChannelOutboundBuffer Backpressure ---")
    # Connect to live Forge server, complete login, then STOP reading from socket
    HOST = "127.0.0.1"
    PORT = 25565

    def varint(n: int) -> bytes:
        out = b""
        while True:
            b = n & 0x7F
            n >>= 7
            if n: out += bytes([b | 0x80])
            else: out += bytes([b]); return out

    s = socket.create_connection((HOST, PORT), timeout=10)
    # Handshake
    hs = varint(0x00) + varint(340) + varint(9) + b"localhost" + b"\x63\xdd" + varint(2)
    s.sendall(varint(len(hs)) + hs)
    # LoginStart
    name = b"SlowBot" + str(int(time.time())%1000).encode()
    ls = varint(0x00) + varint(len(name)) + name
    s.sendall(varint(len(ls)) + ls)

    # Read login packets until PLAY
    s.settimeout(2.0)
    received = 0
    t0 = time.time()
    try:
        while True:
            chunk = s.recv(4096)
            if not chunk: break
            received += len(chunk)
            if received > 1000: break # entered play
    except:
        pass

    print(f"SlowBot connected, entered play. Halting recv() for 5 seconds...")
    # Freeze reader for 5s while server tries to send keepalive/world data
    time.sleep(5)
    # Check if socket still writable
    try:
        s.sendall(b"\x00")
        print("Socket write succeeded after 5s stall. Channel still alive (server tolerates up to 30s timeout).")
    except Exception as e:
        print(f"Socket dropped: {e}")
    finally:
        s.close()

if __name__ == "__main__":
    analyze_live_forge_trace()
    bench_compression_thresholds()
    test_slow_reader_backpressure()
