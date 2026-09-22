#!/usr/bin/env python3
"""
M3 Hardening Gate: Pre-Population Terrain Oracle Comparator.

Compares the pre-population oracle captures (written by the coremod at
shutdown) between the Java (OFF_MEASURE) and Rust (ON_EXPERIMENTAL) arms.

Each line: "chunk=<GLOBAL_CX,GLOBAL_CZ>:blocks=<crc>,biomes=<crc>"

This is the CLEAN base-terrain boundary: the hash is computed at return of
func_185977_a (setBlocksInChunk), i.e. AFTER the density field has been
consumed into the base terrain but BEFORE any structure, population or
decoration pass. Population/ores/trees/springs nondeterminism is therefore
completely excluded.

REQUIREMENT (directive 2): the exact same coordinate set must appear in both
arms. If one arm generated an additional/missing chunk, it is reported, NOT
silently intersected.

Usage: python tools/compare_prepop.py <java.txt> <rust.txt>
"""
import sys
import collections
import os


def parse(path):
    """Returns (dict chunk->hash, set chunks, int linecount)."""
    d = {}
    count = 0
    with open(path, encoding="utf-8", errors="replace") as f:
        for raw in f:
            line = raw.strip()
            if not line:
                continue
            # chunk=<cx,cz>:blocks=<crc>,biomes=<crc>
            if not line.startswith("chunk="):
                continue
            count += 1
            head, rest = line.split(":blocks=", 1)
            chunk = head[len("chunk="):]
            d[chunk] = line
    return d, count


def main():
    if len(sys.argv) != 4:
        print("usage: compare_prepop.py <java.txt> <rust.txt> <outfile>", file=sys.stderr)
        sys.exit(2)
    jpath, rpath, outpath = sys.argv[1], sys.argv[2], sys.argv[3]

    jd, jn = parse(jpath)
    rd, rn = parse(rpath)

    jset = set(jd)
    rset = set(rd)

    report = []
    report.append("================================================================")
    report.append("M3 PRE-POPULATION TERRAIN ORACLE COMPARISON")
    report.append("================================================================")
    report.append(f"Java arm (OFF_MEASURE):   {jn} chunk lines, {len(jset)} unique coords")
    report.append(f"Rust arm (ON_EXPERIMENTAL): {rn} chunk lines, {len(rset)} unique coords")
    report.append("")

    # 1. Coordinate-set reconciliation (NO silent intersection)
    only_j = sorted(jset - rset)
    only_r = sorted(rset - jset)
    both = sorted(jset & rset)
    report.append("--- Coordinate reconciliation ---")
    report.append(f"Chunks in BOTH arms: {len(both)}")
    report.append(f"Chunks ONLY in Java arm (extra/missing in Rust): {len(only_j)}")
    report.append(f"Chunks ONLY in Rust arm (extra/missing in Java): {len(only_r)}")
    for c in only_j[:20]:
        report.append(f"  only_java: {jd[c]}")
    for c in only_r[:20]:
        report.append(f"  only_rust: {rd[c]}")
    if len(only_j) > 20 or len(only_r) > 20:
        report.append(f"  ... ({len(only_j)+len(only_r)} total coordinate-only diffs; first 40 shown)")
    report.append("")

    # 2. Per-chunk hash comparison
    match, mismatch = 0, 0
    details = collections.Counter()
    for c in both:
        if jd[c] == rd[c]:
            match += 1
        else:
            mismatch += 1
            # attribute: blocks-only vs biomes-only vs both
            _, jrest = jd[c].split(":blocks=", 1)
            _, rrest = rd[c].split(":blocks=", 1)
            jblocks, jbiomes = jrest.split(",biomes=", 1)
            rblocks, rbiomes = rrest.split(",biomes=", 1)
            if jblocks != rblocks and jbiomes != rbiomes:
                details["blocks+biomes"] += 1
            elif jblocks != rblocks:
                details["blocks_only"] += 1
            else:
                details["biomes_only"] += 1

    report.append("--- Base-terrain hash parity ---")
    report.append(f"Chunks identical (JAVA_PRE_POP == RUST_PRE_POP): {match} / {len(both)}"
                  + (f" ({match/len(both)*100:.2f}%)" if both else ""))
    report.append(f"Chunks mismatched: {mismatch}")
    for k, v in details.items():
        report.append(f"  mismatch type {k}: {v}")
    report.append("")

    # First 30 mismatches with detail
    if mismatch:
        report.append("--- First mismatches ---")
        shown = 0
        for c in both:
            if jd[c] != rd[c]:
                report.append(f"  {c}")
                report.append(f"    JAVA: {jd[c]}")
                report.append(f"    RUST: {rd[c]}")
                shown += 1
                if shown >= 30:
                    break

    out = "\n".join(report) + "\n"
    print(out)
    with open(outpath, "w", encoding="utf-8") as f:
        f.write(out)

    # Exit code: 2 if coordinate-set mismatch, 1 if any hash mismatch, 0 clean
    if only_j or only_r:
        sys.exit(2)
    if mismatch:
        sys.exit(1)
    sys.exit(0)


if __name__ == "__main__":
    main()
