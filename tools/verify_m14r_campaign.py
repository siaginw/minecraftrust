#!/usr/bin/env python3
"""Verify M1.4-R parity campaign accounting for null-section fixtures."""
import json, re, sys

rows = []
with open("machine/raw/M14R-parity-campaign.jsonl") as f:
    header = f.readline()
    for line in f:
        line = line.strip()
        if not line:
            continue
        parts = line.split(",")
        rows.append(parts)

n = len(rows)
res = {}
null_bit_set = {"MATCH": 0, "MISMATCH": 0, "REF_THROW_ONLY": 0}
null_no_bit = {"MATCH": 0, "MISMATCH": 0, "REF_THROW_ONLY": 0}
no_null = {"MATCH": 0, "MISMATCH": 0, "REF_THROW_ONLY": 0}
examples = []
for p in rows:
    fid, kind, result = p[0], p[1], p[2]
    mask = int(p[5], 16) if p[5].startswith("0x") else int(p[5])
    profile = p[7]
    sects = profile.split("+")[0]
    res[result] = res.get(result, 0) + 1
    has_null_bit = False
    has_null = False
    for i, ch in enumerate(sects):
        if ch == "N":
            has_null = True
            if mask & (1 << i):
                has_null_bit = True
    if has_null_bit:
        null_bit_set[result] = null_bit_set.get(result, 0) + 1
        if len(examples) < 5 and result != "MATCH":
            examples.append(line := ",".join(p))
    elif has_null:
        null_no_bit[result] = null_no_bit.get(result, 0) + 1
    else:
        no_null[result] = no_null.get(result, 0) + 1

print("total rows:", n)
print("results:", res)
print("null section AND bit set :", null_bit_set)
print("null section, bit unset  :", null_no_bit)
print("no null sections         :", no_null)
print("non-MATCH examples:", examples)
assert n == 11000, f"expected 11000, got {n}"
print("ACCOUNTING OK")
