#!/usr/bin/env python3
"""
Check biomes and heightmap across all 9,492 chunks.
HeightMap records the exact surface elevation of generated terrain.
"""
import os, glob
from compare_worlds import load_region_chunks, extract_chunk_block_state

dir_j = "machine/targetA/world-worldj"
dir_r = "machine/targetA/world-worldr"

regions_j = glob.glob(os.path.join(dir_j, "region", "r.*.*.mca"))
regions_r = glob.glob(os.path.join(dir_r, "region", "r.*.*.mca"))
reg_names = sorted(set(os.path.basename(p) for p in regions_j).intersection(os.path.basename(p) for p in regions_r))

total_chunks = 0
biomes_match = 0
heightmap_match = 0
heightmap_diff_sum = 0
heightmap_diff_max = 0

for rname in reg_names:
    cj = load_region_chunks(os.path.join(dir_j, "region", rname))
    cr = load_region_chunks(os.path.join(dir_r, "region", rname))
    common_coords = sorted(set(cj.keys()).intersection(cr.keys()))
    
    for coord in common_coords:
        total_chunks += 1
        dj = extract_chunk_block_state(cj[coord])
        dr = extract_chunk_block_state(cr[coord])
        if dj is None or dr is None: continue
        
        if dj["biomes"] == dr["biomes"]:
            biomes_match += 1
            
        hm_j = dj["height_map"]
        hm_r = dr["height_map"]
        if hm_j == hm_r:
            heightmap_match += 1
        else:
            diffs = [abs(a - b) for a, b in zip(hm_j, hm_r) if a != b]
            if diffs:
                heightmap_diff_sum += sum(diffs)
                heightmap_diff_max = max(heightmap_diff_max, max(diffs))

print(f"Total Chunks:           {total_chunks}")
print(f"Biomes Identical:       {biomes_match} / {total_chunks} ({biomes_match/total_chunks*100:.2f}%)")
print(f"HeightMap Identical:    {heightmap_match} / {total_chunks} ({heightmap_match/total_chunks*100:.2f}%)")
print(f"HeightMap Diff Max:     {heightmap_diff_max}")
