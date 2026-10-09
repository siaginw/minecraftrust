#!/usr/bin/env python3
"""
Twin-World Generated-Chunk Semantic Parity Oracle.
Compares Anvil .mca region files block-by-block across two Minecraft worlds.
Uses Python stdlib (struct, zlib, glob, os, sys) with zero external dependencies.
"""
import io
import os
import sys
import glob
import zlib
import struct

def read_string(s):
    length = struct.unpack(">H", s.read(2))[0]
    return s.read(length).decode("utf-8", errors="replace")

def read_payload(tag_type, s):
    if tag_type == 1: return struct.unpack(">b", s.read(1))[0]
    elif tag_type == 2: return struct.unpack(">h", s.read(2))[0]
    elif tag_type == 3: return struct.unpack(">i", s.read(4))[0]
    elif tag_type == 4: return struct.unpack(">q", s.read(8))[0]
    elif tag_type == 5: return struct.unpack(">f", s.read(4))[0]
    elif tag_type == 6: return struct.unpack(">d", s.read(8))[0]
    elif tag_type == 7:
        length = struct.unpack(">i", s.read(4))[0]
        return s.read(length)
    elif tag_type == 8: return read_string(s)
    elif tag_type == 9:
        sub_type = struct.unpack(">b", s.read(1))[0]
        length = struct.unpack(">i", s.read(4))[0]
        return (sub_type, [read_payload(sub_type, s) for _ in range(length)])
    elif tag_type == 10:
        comp = {}
        while True:
            sub_type = struct.unpack(">b", s.read(1))[0]
            if sub_type == 0: break
            name = read_string(s)
            val = read_payload(sub_type, s)
            comp[name] = (sub_type, val)
        return comp
    elif tag_type == 11:
        length = struct.unpack(">i", s.read(4))[0]
        return list(struct.unpack(f">{length}i", s.read(4 * length)))
    elif tag_type == 12:
        length = struct.unpack(">i", s.read(4))[0]
        return list(struct.unpack(f">{length}q", s.read(8 * length)))
    else:
        raise ValueError(f"Unknown tag type {tag_type}")

def parse_nbt(stream):
    b = stream.read(1)
    if not b: return None
    root_type = struct.unpack(">b", b)[0]
    if root_type == 0: return None
    root_name = read_string(stream)
    payload = read_payload(root_type, stream)
    return (root_name, root_type, payload)

def load_region_chunks(mca_path):
    """Returns dict of (chunk_x, chunk_z) -> decompressed NBT bytes"""
    basename = os.path.basename(mca_path)
    parts = basename.split(".")
    if len(parts) < 4 or parts[0] != "r":
        return {}
    rx, rz = int(parts[1]), int(parts[2])
    chunks = {}
    with open(mca_path, "rb") as f:
        header = f.read(8192)
        if len(header) < 8192:
            return chunks
        for i in range(1024):
            offset = struct.unpack(">I", b"\x00" + header[i*4 : i*4+3])[0]
            sector_count = header[i*4+3]
            if offset > 0:
                cx = rx * 32 + (i % 32)
                cz = rz * 32 + (i // 32)
                f.seek(offset * 4096)
                plen = struct.unpack(">I", f.read(4))[0]
                ctype = f.read(1)[0]
                cdata = f.read(plen - 1)
                try:
                    decomp = zlib.decompress(cdata)
                    chunks[(cx, cz)] = decomp
                except Exception as e:
                    print(f"Error decompressing chunk ({cx}, {cz}) in {mca_path}: {e}")
    return chunks

def extract_chunk_block_state(nbt_bytes):
    """
    Extracts structured block state from chunk NBT:
    Returns dict: section_y -> {'Blocks': bytes(4096), 'Data': bytes(2048), 'Add': bytes}
    and 'Biomes': bytes(256), 'HeightMap': list of ints
    """
    tree = parse_nbt(io.BytesIO(nbt_bytes))
    if not tree or tree[1] != 10:
        return None
    root_comp = tree[2]
    level = root_comp.get("Level", (10, {}))[1]
    sections_list = level.get("Sections", (9, (10, [])))[1]
    
    sections = {}
    if isinstance(sections_list, tuple) and len(sections_list) == 2:
        sec_items = sections_list[1]
    else:
        sec_items = sections_list

    for sec_comp in sec_items:
        sy = sec_comp.get("Y", (1, 0))[1]
        blocks = sec_comp.get("Blocks", (7, b""))[1]
        data = sec_comp.get("Data", (7, b""))[1]
        add = sec_comp.get("Add", (7, b""))[1]
        sections[sy] = {
            "Blocks": blocks,
            "Data": data,
            "Add": add
        }
    
    biomes = level.get("Biomes", (7, b""))[1]
    height_map = level.get("HeightMap", (11, []))[1]
    return {
        "xPos": level.get("xPos", (3, 0))[1],
        "zPos": level.get("zPos", (3, 0))[1],
        "sections": sections,
        "biomes": biomes,
        "height_map": height_map
    }

def compare_worlds(dir_j, dir_r):
    print(f"Comparing World J: {dir_j}")
    print(f"     with World R: {dir_r}")
    
    regions_j = glob.glob(os.path.join(dir_j, "region", "r.*.*.mca"))
    regions_r = glob.glob(os.path.join(dir_r, "region", "r.*.*.mca"))
    
    reg_names_j = {os.path.basename(p): p for p in regions_j}
    reg_names_r = {os.path.basename(p): p for p in regions_r}
    
    common_regions = sorted(set(reg_names_j.keys()).intersection(reg_names_r.keys()))
    print(f"Found {len(common_regions)} common region file(s): {common_regions}")
    
    total_chunks_j = 0
    total_chunks_r = 0
    chunks_compared = 0
    chunks_identical = 0
    chunks_mismatched = 0
    
    total_blocks_compared = 0
    total_blocks_mismatched = 0
    
    first_mismatch = None
    
    for rname in common_regions:
        cj = load_region_chunks(reg_names_j[rname])
        cr = load_region_chunks(reg_names_r[rname])
        total_chunks_j += len(cj)
        total_chunks_r += len(cr)
        
        common_coords = sorted(set(cj.keys()).intersection(cr.keys()))
        print(f"Region {rname}: J has {len(cj)} chunks, R has {len(cr)} chunks. {len(common_coords)} in common.")
        
        for coord in common_coords:
            chunks_compared += 1
            data_j = extract_chunk_block_state(cj[coord])
            data_r = extract_chunk_block_state(cr[coord])
            
            if data_j is None or data_r is None:
                print(f"Error parsing NBT for chunk {coord}")
                chunks_mismatched += 1
                if first_mismatch is None:
                    first_mismatch = f"Chunk {coord}: NBT parse failure"
                continue
            
            sec_j = data_j["sections"]
            sec_r = data_r["sections"]
            
            all_sy = sorted(set(sec_j.keys()).union(sec_r.keys()))
            chunk_matches = True
            
            for sy in all_sy:
                if sy not in sec_j:
                    chunk_matches = False
                    if first_mismatch is None:
                        first_mismatch = f"Chunk {coord}: Section Y={sy} present in R but absent in J"
                    break
                if sy not in sec_r:
                    chunk_matches = False
                    if first_mismatch is None:
                        first_mismatch = f"Chunk {coord}: Section Y={sy} present in J but absent in R"
                    break
                
                bj = sec_j[sy]["Blocks"]
                br = sec_r[sy]["Blocks"]
                dj = sec_j[sy]["Data"]
                dr = sec_r[sy]["Data"]
                
                # Compare blocks (4096 bytes per 16x16x16 section)
                total_blocks_compared += len(bj)
                if bj != br:
                    chunk_matches = False
                    for bidx in range(len(bj)):
                        if bj[bidx] != br[bidx]:
                            total_blocks_mismatched += 1
                            if first_mismatch is None:
                                y_off = sy * 16 + (bidx // 256)
                                z_off = (bidx % 256) // 16
                                x_off = bidx % 16
                                gx = coord[0] * 16 + x_off
                                gz = coord[1] * 16 + z_off
                                gy = y_off
                                first_mismatch = (f"Chunk {coord}: Block mismatch at section Y={sy} bidx={bidx} "
                                                  f"rel=({x_off},{y_off},{z_off}) world=({gx},{gy},{gz}) "
                                                  f"J_block={bj[bidx]} R_block={br[bidx]}")
                
                if dj != dr:
                    chunk_matches = False
                    if first_mismatch is None:
                        first_mismatch = f"Chunk {coord}: Metadata Data mismatch at section Y={sy}"
            
            # Compare biomes
            if data_j["biomes"] != data_r["biomes"]:
                chunk_matches = False
                if first_mismatch is None:
                    first_mismatch = f"Chunk {coord}: Biomes mismatch"
            
            # Compare heightmap
            if data_j["height_map"] != data_r["height_map"]:
                chunk_matches = False
                if first_mismatch is None:
                    first_mismatch = f"Chunk {coord}: HeightMap mismatch"
            
            if chunk_matches:
                chunks_identical += 1
            else:
                chunks_mismatched += 1
    
    print("\n--- CHUNK SEMANTIC PARITY RESULTS ---")
    print(f"Total Chunks in J: {total_chunks_j}")
    print(f"Total Chunks in R: {total_chunks_r}")
    print(f"Chunks Compared:   {chunks_compared}")
    print(f"Chunks Identical:  {chunks_identical}")
    print(f"Chunks Mismatched: {chunks_mismatched}")
    print(f"Total Blocks Compared:   {total_blocks_compared}")
    print(f"Total Blocks Mismatched: {total_blocks_mismatched}")
    print(f"First Mismatch:    {first_mismatch or 'NONE (100% BIT-EXACT SEMANTIC IDENTITY)'}")
    
    return {
        "chunks_j": total_chunks_j,
        "chunks_r": total_chunks_r,
        "chunks_compared": chunks_compared,
        "chunks_identical": chunks_identical,
        "chunks_mismatched": chunks_mismatched,
        "blocks_compared": total_blocks_compared,
        "blocks_mismatched": total_blocks_mismatched,
        "first_mismatch": first_mismatch or "none"
    }

if __name__ == "__main__":
    if len(sys.argv) < 3:
        print("Usage: compare_worlds.py <path_to_world_j> <path_to_world_r>")
        sys.exit(1)
    res = compare_worlds(sys.argv[1], sys.argv[2])
    if res["chunks_mismatched"] > 0 or res["chunks_compared"] == 0:
        sys.exit(1)
    sys.exit(0)
