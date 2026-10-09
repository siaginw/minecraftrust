#!/usr/bin/env python3
"""
tools/generate-minecraft-source.py
Automated pipeline to remap and decompile Minecraft 1.12.2 server jar into third_party_reference/minecraft/src.
"""
import os
import sys
import zipfile
import subprocess
import csv
import re
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MC_REF = os.path.join(ROOT, "third_party_reference", "minecraft")
SERVER_JAR = os.path.join(MC_REF, "minecraft_server.1.12.2.jar")
SRG_JAR = os.path.join(MC_REF, "minecraft_server.1.12.2.srg.jar")
MC_SRG_JAR = os.path.join(MC_REF, "minecraft_mc_srg.jar")
SRC_DIR = os.path.join(MC_REF, "src")
TSRG_PATH = os.path.join(ROOT, "third_party_reference", "mappings", "mcp_config", "config", "joined.tsrg")
SPECIALSOURCE_JAR = os.path.join(ROOT, "tools", "specialsource.jar")
VINEFLOWER_JAR = os.path.join(ROOT, "tools", "vineflower.jar")
JAVA8_SH = os.path.join(ROOT, "tools", "java8", "java8.sh")
MAPPINGS_DIR = os.path.join(ROOT, "third_party_reference", "mappings", "export")

def main():
    print("[1/4] Remapping server jar with SpecialSource...")
    cmd = [JAVA8_SH, "-jar", SPECIALSOURCE_JAR, "-i", SERVER_JAR, "-m", TSRG_PATH, "-o", SRG_JAR]
    subprocess.run(cmd, check=True)

    print("[2/4] Filtering net/minecraft/** classes...")
    with zipfile.ZipFile(SRG_JAR, 'r') as zin, zipfile.ZipFile(MC_SRG_JAR, 'w', zipfile.ZIP_DEFLATED) as zout:
        for item in zin.infolist():
            if item.filename.startswith('net/minecraft/') and item.filename.endswith('.class'):
                zout.writestr(item, zin.read(item.filename))

    print("[3/4] Decompiling with Vineflower...")
    os.makedirs(SRC_DIR, exist_ok=True)
    subprocess.run(["java", "-jar", VINEFLOWER_JAR, "-log=WARN", "--threads=8", MC_SRG_JAR, SRC_DIR], check=True)

    print("[4/4] Remapping SRG identifiers to MCP names...")
    methods = {}
    fields = {}
    params = {}
    with open(os.path.join(MAPPINGS_DIR, "methods.csv"), 'r', encoding='utf-8') as f:
        for row in csv.DictReader(f):
            methods[row['searge']] = row['name']
    with open(os.path.join(MAPPINGS_DIR, "fields.csv"), 'r', encoding='utf-8') as f:
        for row in csv.DictReader(f):
            fields[row['searge']] = row['name']
    with open(os.path.join(MAPPINGS_DIR, "params.csv"), 'r', encoding='utf-8') as f:
        for row in csv.DictReader(f):
            params[row['param']] = row['name']

    all_maps = {**methods, **fields, **params}
    # Hardened regex supporting uppercase suffixes (_M, _F) and trailing underscores (_a_)
    pattern = re.compile(r'\b(func_\d+_[a-zA-Z0-9_]+|field_\d+_[a-zA-Z0-9_]+|p_\d+_\d+_[a-zA-Z0-9_]*)\b')

    for root, _, files in os.walk(SRC_DIR):
        for f in files:
            if f.endswith('.java'):
                p = os.path.join(root, f)
                with open(p, 'r', encoding='utf-8', errors='ignore') as fh:
                    c = fh.read()
                nc = pattern.sub(lambda m: all_maps.get(m.group(1), m.group(1)), c)
                nc = nc.replace('☃', 'p_')
                with open(p, 'w', encoding='utf-8') as fh:
                    fh.write(nc)

    print("Pipeline complete. Source ready in third_party_reference/minecraft/src.")

if __name__ == '__main__':
    main()
