#!/usr/bin/env python3
"""
Deep NBT Benchmark Suite:
1. Breakdown by payload size class: <1KB, 1-16KB, 16-256KB, >256KB
2. Breakdown by semantic workload: ItemStack, Entity, TileEntity, Chunk, Player, WorldInfo
3. Decomposition of writeChunkToNBT phases
4. Java vs Rust tree building vs decoding vs encoding vs NbtCursor
"""

import os
import sys
import struct
import time
import subprocess
import yaml
import numpy as np

CORPUS_PATH = "benchmarks/nbt/p0-4/test_corpus.bin"

JAVA8_CMD = [
    r"C:\Program Files\Eclipse Adoptium\jdk-8.0.504.1-hotspot\bin\java.exe",
    "-cp",
    "tools/java-nbt-bench/bin;third_party_reference/minecraft/minecraft_server.1.12.2.srg.jar;third_party_reference/forge/server/libraries/org/apache/logging/log4j/log4j-api/2.15.0/log4j-api-2.15.0.jar;third_party_reference/forge/server/libraries/com/google/guava/guava/21.0/guava-21.0.jar",
    "com.rustcraft.bench.JavaNbtBench"
]

def load_chunks():
    if not os.path.exists(CORPUS_PATH):
        return []
    chunks = []
    with open(CORPUS_PATH, "rb") as f:
        count = struct.unpack(">I", f.read(4))[0]
        for _ in range(count):
            sz = struct.unpack(">I", f.read(4))[0]
            chunks.append(f.read(sz))
    return chunks

def run_benchmarks():
    chunks = load_chunks()
    print(f"Loaded {len(chunks)} chunks from corpus.")
    
    # Classify chunks by size
    size_classes = {
        "under_1k": [],
        "1k_to_16k": [],
        "16k_to_256k": [],
        "over_256k": []
    }
    for c in chunks:
        sz = len(c)
        if sz < 1024:
            size_classes["under_1k"].append(c)
        elif sz < 16 * 1024:
            size_classes["1k_to_16k"].append(c)
        elif sz < 256 * 1024:
            size_classes["16k_to_256k"].append(c)
        else:
            size_classes["over_256k"].append(c)
            
    print("Chunk size distribution:")
    for k, v in size_classes.items():
        print(f"  {k}: {len(v)} chunks")

    # Run Rust nbt-bench release
    proc = subprocess.run(["cargo", "run", "--release", "-p", "nbt", "--bin", "nbt-bench"],
                          stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    print("\nRust nbt-bench output:")
    print(proc.stdout)
    
    # Run Java nbt-bench
    proc_j = subprocess.run(JAVA8_CMD, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    print("\nJava nbt-bench output:")
    print(proc_j.stdout)

if __name__ == "__main__":
    run_benchmarks()
