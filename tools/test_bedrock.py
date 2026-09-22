#!/usr/bin/env python3
"""
Verify bedrock at Y=0 across all 9,492 chunks.
At Y=0, setBlocksInChunk places solid BEDROCK (block id 7) in every single (x, z) coordinate.
"""
import os, glob
from compare_worlds import load_region_chunks, extract_chunk_block_state

dir_j = "machine/targetA/world-worldj"
dir_r = "machine/targetA/world-worldr"

regions_j = glob.glob(os.path.join(dir_j, "region", "r.*.*.mca"))
regions_r = glob.glob(os.path.join(dir_r, "region", "r.*.*.mca"))
reg_names = sorted(set(os.path.basename(p) for p in regions_j).intersection(os.path.basename(p) for p in regions_r))

total_y0_blocks = 0
bedrock_matches = 0
bedrock_mismatches = 0

for rname in reg_names:
    cj = load_region_chunks(os.path.join(dir_j, "region", rname))
    cr = load_region_chunks(os.path.join(dir_r, "region", rname))
    common_coords = sorted(set(cj.keys()).intersection(cr.keys()))
    
    for coord in common_coords:
        dj = extract_chunk_block_state(cj[coord])
        dr = extract_chunk_block_state(cr[coord])
        if dj is None or dr is None: continue
        sec_j = dj["sections"]
        sec_r = dr["sections"]
        if 0 not in sec_j or 0 not in sec_r: continue
        
        bj = sec_j[0]["Blocks"]
        br = sec_r[0]["Blocks"]
        # Y=0 is the first 256 bytes (bidx 0..255: y=0, z=bidx//16, x=bidx%16)
        for bidx in range(256):
            total_y0_blocks += 1
            if bj[bidx] == 7 and br[bidx] == 7:
                bedrock_matches += 1
            elif bj[bidx] != br[bidx]:
                bedrock_mismatches += 1

print(f"Total Y=0 Blocks Checked: {total_y0_blocks}")
print(f"Bedrock Matches:          {bedrock_matches}")
print(f"Bedrock Mismatches:       {bedrock_mismatches}")
