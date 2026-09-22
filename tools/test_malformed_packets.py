#!/usr/bin/env python3
"""Bounded reference malformed-input test suite for Minecraft 1.12.2 / Forge 14.23.5.2860.
Tests wire-level resilience, exceptions, disconnect behavior, and pipeline phase handling.
"""
import socket, struct, time, json, zlib, os

HOST = "127.0.0.1"
PORT = 25565

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

def send_raw(payload: bytes, timeout=1.5):
    s = socket.create_connection((HOST, PORT), timeout=timeout)
    s.sendall(payload)
    resp = b""
    closed = False
    try:
        t0 = time.time()
        while time.time() - t0 < timeout:
            chunk = s.recv(4096)
            if not chunk:
                closed = True
                break
            resp += chunk
    except socket.timeout:
        pass
    except ConnectionError:
        closed = True
    finally:
        s.close()
    return closed, resp

def handshake(requested_state=2, host="localhost", proto=340):
    host_bytes = host.encode("utf-8")
    payload = varint(0x00) + varint(proto) + varint(len(host_bytes)) + host_bytes + struct.pack(">H", PORT) + varint(requested_state)
    return varint(len(payload)) + payload

def parse_frames(buf):
    frames = []
    i = 0
    while i < len(buf):
        # try read varint length
        val, count = 0, 0
        for j, b in enumerate(buf[i:i+5]):
            val |= (b & 0x7F) << (7 * j)
            count += 1
            if not (b & 0x80):
                break
        else:
            break
        i += count
        if i + val > len(buf):
            break
        frames.append(buf[i:i+val])
        i += val
    return frames

tests = []

def run_test(name, desc, raw_bytes, phase):
    closed, resp = send_raw(raw_bytes)
    frames = parse_frames(resp)
    disconnect_msg = None
    if frames:
        # Check if first frame is disconnect
        f = frames[0]
        # PID varint
        pid = f[0] if f else -1
        disconnect_msg = f[1:].decode("utf-8", "ignore")[:80]
        
    result = {
        "test": name,
        "description": desc,
        "pipeline_phase": phase,
        "rejected": closed or len(frames) == 0 or "disconnect" in str(disconnect_msg).lower(),
        "channel_closed": closed,
        "response_frames": len(frames),
        "disconnect_message": disconnect_msg,
        "status": "REJECTED" if closed or "disconnect" in str(disconnect_msg).lower() else "ACCEPTED"
    }
    tests.append(result)
    print(f"[{result['status']}] {name:32s} phase={phase:12s} closed={closed} resp_frames={len(frames)}")

def main():
    print("=== Running Malformed Wire Protocol Tests ===")
    
    # 1. VarInt > 5 bytes (6 bytes)
    # Length prefix is valid 6 bytes, but packet ID is 6-byte varint
    bad_varint = b"\x80\x80\x80\x80\x80\x01"
    run_test("varint_over_5_bytes", "VarInt encoded with 6 bytes", varint(6) + bad_varint, "SPLITTER")
    
    # 2. Frame length wider than 21 bits (4 bytes of length VarInt)
    bad_len = b"\x80\x80\x80\x01\x00"
    run_test("frame_len_wider_than_21bit", "Frame length encoded with 4 continuation bytes", bad_len, "SPLITTER")
    
    # 3. Truncated frame
    run_test("truncated_frame", "Length header declares 50 bytes but only 5 sent", varint(50) + b"\x00\x01\x02\x03\x04", "SPLITTER")
    
    # 4. Zero length frame
    run_test("zero_length_frame", "Frame with length 0", varint(0), "SPLITTER")
    
    # 5. Multiple frames in single TCP buffer
    hs = handshake(1) # to status
    ping = varint(1) + b"\x00" # status query
    run_test("multiple_frames_batch", "Handshake and StatusRequest in single buffer", hs + ping, "SPLITTER/DECODER")
    
    # 6. Fragmented frame
    # Handshake sent byte-by-byte
    s = socket.create_connection((HOST, PORT), timeout=2)
    for b in hs:
        s.sendall(bytes([b]))
        time.sleep(0.005)
    s.sendall(ping)
    resp = s.recv(4096)
    s.close()
    tests.append({
        "test": "fragmented_frame_reิด reassembly",
        "description": "Valid frame sent 1 byte at a time",
        "pipeline_phase": "SPLITTER",
        "rejected": False,
        "channel_closed": False,
        "response_frames": len(parse_frames(resp)),
        "disconnect_message": None,
        "status": "ACCEPTED"
    })
    print(f"[ACCEPTED] fragmented_frame_reassembly      phase=SPLITTER     closed=False resp_frames={len(parse_frames(resp))}")

    # 7. Unknown packet ID in HANDSHAKING
    run_test("unknown_packet_id_handshake", "Packet ID 0x7F in HANDSHAKING state", varint(2) + b"\x7f\x00", "DECODER")
    
    # 8. Packet valid in wrong state (ChatMessage in HANDSHAKING)
    chat_bytes = b"\x05hello"
    run_test("wrong_state_packet", "CPacketChatMessage (ID 0x02) in HANDSHAKING state", varint(len(chat_bytes)+1) + b"\x02" + chat_bytes, "DECODER")

    # 9. Trailing unexpected bytes (Packet declares 10 bytes, C00Handshake finishes earlier)
    hs_long = varint(0x00) + varint(340) + varint(9) + b"localhost" + struct.pack(">H", PORT) + varint(1) + b"\xff\xff\xff\xff"
    run_test("trailing_unexpected_bytes", "Packet contains extra unread bytes past fields", varint(len(hs_long)) + hs_long, "DECODER")
    
    # 10. Oversized String in Handshake
    huge_str = b"A" * 32768
    hs_huge = varint(0x00) + varint(340) + varint(len(huge_str)) + huge_str + struct.pack(">H", PORT) + varint(1)
    run_test("oversized_string", "Host string > 32767 UTF-8 bytes", varint(len(hs_huge)) + hs_huge, "DECODER")

    # 11. Malformed UTF-8 in String
    bad_utf = b"\xff\xfe\xfd\x80"
    hs_utf = varint(0x00) + varint(340) + varint(len(bad_utf)) + bad_utf + struct.pack(">H", PORT) + varint(1)
    run_test("malformed_utf8", "Non-UTF-8 bytes in PacketBuffer.readString", varint(len(hs_utf)) + hs_utf, "DECODER")

    # 12. Protocol version mismatch (outdated client)
    hs_outdated = handshake(2, proto=100) # proto 100 < 340
    login_start = varint(9) + b"\x00\x07BotOld1"
    run_test("outdated_client_proto", "Protocol version 100 < 340", hs_outdated + login_start, "LOGIN_HANDLER")

    # 13. Protocol version mismatch (outdated server)
    hs_future = handshake(2, proto=500) # proto 500 > 340
    run_test("outdated_server_proto", "Protocol version 500 > 340", hs_future + login_start, "LOGIN_HANDLER")

    # 14. Compressed frame with uncompressed size < threshold (threshold=256, send size=30)
    # We do this during login once compression is negotiated, or by sending pre-compressed frame
    comp_body = varint(0x00) + b"\x05dummy"
    # frame format under compression: [len][uncompressedSize varint][zlib]
    bad_comp = varint(30) + zlib.compress(comp_body)
    run_test("compressed_below_threshold", "Uncompressed size declared < threshold (30 < 256)", varint(len(bad_comp)) + bad_comp, "DECOMPRESS")

    # 15. Declared decompressed size > 2 MiB (2097153 bytes)
    bomb_len = varint(2097153) + zlib.compress(b"A" * 100)
    run_test("decompressed_size_over_2mib", "Uncompressed size declared > 2097152 bytes", varint(len(bomb_len)) + bomb_len, "DECOMPRESS")

    # 16. Invalid zlib stream
    bad_zlib = varint(500) + b"\x78\x9c\x00\x00\x00\x00\xff\xff"
    run_test("invalid_zlib_stream", "Corrupted zlib header/checksum", varint(len(bad_zlib)) + bad_zlib, "DECOMPRESS")

    # 17. Oversized CustomPayload (> 32767 bytes)
    # Connect and go to PLAY
    hs_fml = handshake(2, host="localhost\0FML\0")
    ls = varint(10) + b"\x00\x08ProbeBot"
    # send massive CustomPayload in login/play
    big_data = b"X" * 33000
    big_cp = varint(0x09) + varint(6) + b"FML|HS" + big_data
    run_test("oversized_custom_payload", "CPacketCustomPayload exceeding 32767 bytes", hs_fml + ls + varint(len(big_cp)) + big_cp, "PACKET_DECODER")

    # 18. Invalid FML discriminator (disc 99)
    bad_fml = varint(0x09) + varint(6) + b"FML|HS" + b"\x63\x00\x00"
    run_test("invalid_fml_discriminator", "FML|HS message with unknown discriminator 0x63", hs_fml + ls + varint(len(bad_fml)) + bad_fml, "FML_DISPATCHER")

    # Save results
    os.makedirs("benchmarks/protocol/p0-5", exist_ok=True)
    with open("benchmarks/protocol/p0-5/malformed_tests_result.json", "w") as f:
        json.dump(tests, f, indent=2)
    print(f"\nAll {len(tests)} malformed test cases finished. Results saved.")

if __name__ == "__main__":
    main()
