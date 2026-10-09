#!/usr/bin/env python3
"""Scans the Revelation mod jars for references to the chunk-state writer
surfaces. First-pass static inventory: records which jars reference which
sensitive vanilla/Forge classes (constant-pool UTF8 strings), and which
coremod-declaring jars carry transformation targets among those classes.
Output: target/rev-probe/mod-reference-scan.json
"""
from __future__ import annotations

import json
import zipfile
from pathlib import Path

RT = Path("D:/rustcraft-runtime-targets/revelation-3.4.0/server")
OUT = Path("target/rev-probe/mod-reference-scan.json")

TARGETS = {
    "chunk_package": b"net/minecraft/world/chunk/",
    "chunk_class": b"net/minecraft/world/chunk/Chunk",
    "extended_block_storage": b"net/minecraft/world/chunk/storage/ExtendedBlockStorage",
    "block_state_container": b"net/minecraft/world/chunk/BlockStateContainer",
    "nibble_array": b"net/minecraft/world/chunk/NibbleArray",
    "bit_array": b"net/minecraft/util/BitArray",
    "anvil_chunk_loader": b"net/minecraft/world/chunk/storage/AnvilChunkLoader",
    "chunk_provider_server": b"net/minecraft/world/gen/ChunkProviderServer",
    "chunk_io": b"net/minecraftforge/common/chunkio/",
    "spacket_chunk_data": b"net/minecraft/network/play/server/SPacketChunkData",
    "world_set_block": b"net/minecraft/world/World",
    "biome_provider": b"net/minecraft/world/biome/Biome",
    "world_server": b"net/minecraft/world/WorldServer",
    "tile_entity": b"net/minecraft/tileentity/TileEntity",
    "light_engine": b"net/minecraft/world/lighting/",
    "enum_sky_block": b"net/minecraft/world/EnumSkyBlock",
    "object_int_identity_map": b"net/minecraft/util/ObjectIntIdentityMap",
    "block": b"net/minecraft/block/Block",
    "game_registry": b"net/minecraftforge/registries/GameRegistry",
    "forge_registry": b"net/minecraftforge/registries/ForgeRegistries",
    "unsafe": b"sun/misc/Unsafe",
    "reflection": b"java/lang/reflect/Method",
}


def scan() -> int:
    pins = json.loads(Path("tools/live-capture/revelation-runtime-pins.json").read_text())
    result = {}
    for key, meta in sorted(pins["mods"].items()):
        path = RT / key
        refs = set()
        try:
            with zipfile.ZipFile(path) as archive:
                for name in archive.namelist():
                    if not name.endswith(".class"):
                        continue
                    data = archive.read(name)
                    for label, needle in TARGETS.items():
                        if needle in data:
                            refs.add(label)
        except (zipfile.BadZipFile, OSError) as error:
            result[key] = {"error": str(error)}
            continue
        entry = {"sha256": meta["sha256"], "references": sorted(refs)}
        if meta.get("coremod"):
            entry["coremod"] = meta["coremod"]
        if meta.get("access_transformers"):
            entry["access_transformers"] = meta["access_transformers"]
        result[key] = entry
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(result, indent=1))
    # summary of the load-bearing surfaces
    for label in TARGETS:
        holders = [k for k, v in result.items()
                   if isinstance(v, dict) and label in v.get("references", [])]
        print("%-28s %d jars" % (label, len(holders)))
    print()
    print("jars referencing chunk-state storage (EBS/BSC/Nibble/BitArray):")
    storage = {"extended_block_storage", "block_state_container", "nibble_array", "bit_array"}
    for k, v in sorted(result.items()):
        if isinstance(v, dict) and storage & set(v.get("references", [])):
            print("  ", k, sorted(set(v["references"]) & storage))
    return 0


if __name__ == "__main__":
    raise SystemExit(scan())
