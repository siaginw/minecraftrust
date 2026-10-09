"""Source discovery and resolution for the symbol index.

The default configuration enumerates the assets that actually exist in this
environment (Gradle caches + RustCraft target dirs) with candidate paths.
`resolve_sources` picks the first existing candidate per entry, hashes it,
and reports anything missing. Machine-specific paths stay out of the engine;
the committed sources.json documents them, and every build records the exact
resolved paths + SHA-256s into the database's meta table.
"""

import hashlib
import json
import os

GRADLE_MC = "C:/Users/Siagi/.gradle/caches/minecraft"
GRADLE_FG = "C:/Users/Siagi/.gradle/caches/forge_gradle"
CURSEFORGE = "C:/Users/Siagi/curseforge/minecraft/Install"
TARGET = "C:/rustcraft/target"

DEFAULT_SOURCES = {
    "mc_version": "1.12.2",
    "mappings": [
        {
            "name": "MCP joined.srg (notch -> SRG)",
            "version": "mcp-1.12.2 (ForgeGradle classic cache)",
            "format": "joined_srg",
            "required": True,
            "paths": [
                GRADLE_MC + "/de/oceanlabs/mcp/mcp/1.12.2/joined.srg",
            ],
        },
        {
            "name": "MCP snapshot 20171003 methods.csv (SRG -> MCP)",
            "version": "snapshot_20171003",
            "format": "mcp_methods_csv",
            "required": True,
            "paths": [
                GRADLE_MC + "/de/oceanlabs/mcp/mcp_snapshot/20171003/methods.csv",
            ],
        },
        {
            "name": "MCP snapshot 20171003 fields.csv (SRG -> MCP)",
            "version": "snapshot_20171003",
            "format": "mcp_fields_csv",
            "required": True,
            "paths": [
                GRADLE_MC + "/de/oceanlabs/mcp/mcp_snapshot/20171003/fields.csv",
            ],
        },
    ],
    "artifacts": [
        {
            "layer": "VANILLA_NOTCH",
            "name": "Minecraft 1.12.2 dedicated server (notch)",
            "member_namespace": "notch",
            "entry_filter": "mc",
            "anchor": True,
            "required": True,
            "note": "Server jar as distributed; bundled libraries are "
                    "excluded by the mc entry filter (mapping universe).",
            "paths": [
                GRADLE_FG + "/mcp_repo/de/oceanlabs/mcp/mcp_config/1.12.2/"
                "joined/downloadServer/server.jar",
                "C:/rustcraft/target/authority-smoke/runtimeA/"
                "minecraft_server.1.12.2.jar",
            ],
        },
        {
            "layer": "VANILLA_CLIENT_NOTCH",
            "name": "Minecraft 1.12.2 client (notch)",
            "member_namespace": "notch",
            "entry_filter": "all",
            "required": False,
            "note": "Client-only classes absent from the server jar; also "
                    "makes FORGE_PATCHED (merged jar) fully comparable.",
            "paths": [
                CURSEFORGE + "/versions/1.12.2/1.12.2.jar",
            ],
        },
        {
            "layer": "FORGE_PATCHED",
            "name": "Forge 14.23.5.2847 binpatched merged jar (notch)",
            "member_namespace": "notch",
            "entry_filter": "all",
            "forge_version": "14.23.5.2847",
            "required": False,
            "note": "Vanilla bytes + Forge binary patches. Per-method "
                    "code hashes compare against VANILLA_NOTCH to expose "
                    "what Forge changed.",
            "paths": [
                GRADLE_MC + "/net/minecraftforge/forge/"
                "1.12.2-14.23.5.2847/snapshot/20171003/"
                "forge-1.12.2-14.23.5.2847-binpatched.jar",
            ],
        },
        {
            "layer": "FORGE_PATCHED_MCP",
            "name": "Forge 14.23.5.2864 recompiled, MCP snapshot 20171003 "
                    "naming",
            "member_namespace": "mcp",
            "entry_filter": "all",
            "forge_version": "14.23.5.2864",
            "required": False,
            "note": "Human-readable MCP names. Recompiled from patched "
                    "sources: code hashes are NOT comparable to runtime "
                    "jars; use FORGE_PATCHED for byte-level diffing.",
            "paths": [
                GRADLE_FG + "/minecraft_user_repo/net/minecraftforge/forge/"
                "1.12.2-14.23.5.2864_mapped_snapshot_20171003-1.12/"
                "forge-1.12.2-14.23.5.2864_mapped_snapshot_20171003-1.12.jar",
            ],
        },
        {
            "layer": "FORGE_MOD",
            "name": "Forge universal 14.23.5.2846",
            "member_namespace": "srg",
            "entry_filter": "all",
            "forge_version": "14.23.5.2846",
            "required": False,
            "note": "The Forge mod itself (net.minecraftforge, "
                    "net.minecraftforge.fml).",
            "paths": [
                TARGET + "/authority-smoke/runtimeC/"
                "forge-1.12.2-14.23.5.2846-universal.jar",
                GRADLE_MC + "/modules-2-placeholder-universal.jar",
            ],
        },
        {
            "layer": "LIVE_TRANSFORMED",
            "name": "Runtime-transformed class dump (sc-bisect-obs campaign)",
            "member_namespace": "srg",
            "entry_filter": "all",
            "kind": "dir",
            "forge_version": "14.23.5.2860",
            "campaign_id": "sc-bisect-obs",
            "required": False,
            "note": "Classes as the live LaunchClassLoader actually defined "
                    "them (SRG names, post-transform bytecode). Runtime was "
                    "Forge 14.23.5.2860 with the Target C mod set, so live "
                    "bytes = vanilla + Forge-2860 runtime-applied binary "
                    "patches + mod coremods. Partial coverage: only classes "
                    "dumped by that campaign. Machine-local, under target/.",
            "paths": [
                TARGET + "/authority-smoke/sc-bisect-obs/server/transformed",
            ],
        },
        {
            "layer": "MOD_JAR",
            "name": "phosphor-forge-mc1.12.2-0.2.7-universal.jar",
            "member_namespace": "srg",
            "entry_filter": "all",
            "required": False,
            "note": "The mod currently targeted by the light-authority work "
                    "(Target C pack copy).",
            "paths": [
                TARGET + "/authority-review/collision-profile-C/server/mods/"
                "phosphor-forge-mc1.12.2-0.2.7-universal.jar",
            ],
        },
        {
            "layer": "MOD_JAR",
            "name": "journeymap-1.12.2-5.5.5.jar",
            "member_namespace": "srg",
            "entry_filter": "all",
            "required": False,
            "note": "Second Target C mod as a worked example.",
            "paths": [
                TARGET + "/authority-review/collision-profile-C/server/mods/"
                "journeymap-1.12.2-5.5.5.jar",
            ],
        },
        {
            "layer": "MOD_JAR",
            "name": "rustcraft campaign coremod classes",
            "member_namespace": "srg",
            "entry_filter": "all",
            "kind": "dir",
            "required": False,
            "note": "RustCraft's own transformer/hook/bridge classes "
                    "(target/campaign-coremod-build).",
            "paths": [
                TARGET + "/campaign-coremod-build",
            ],
        },
    ],
}


def _sha256_of(path, kind):
    h = hashlib.sha256()
    if kind == "jar":
        with open(path, "rb") as fh:
            for chunk in iter(lambda: fh.read(1 << 20), b""):
                h.update(chunk)
        return h.hexdigest()
    # deterministic directory hash: sorted rel paths + per-file hashes
    entries = []
    for root, _dirs, names in os.walk(path):
        for name in names:
            if name.endswith(".class"):
                full = os.path.join(root, name)
                rel = os.path.relpath(full, path).replace("\\", "/")
                fh2 = hashlib.sha256()
                with open(full, "rb") as fh:
                    for chunk in iter(lambda: fh.read(1 << 20), b""):
                        fh2.update(chunk)
                entries.append((rel, fh2.hexdigest()))
    entries.sort()
    for rel, digest in entries:
        h.update(rel.encode("utf-8"))
        h.update(digest.encode("ascii"))
    return h.hexdigest()


def resolve_sources(config=None, progress=None):
    """Return (resolved, skipped). Resolved entries gain `path`, `sha256` and
    `kind`; missing optional entries move to `skipped` with a reason."""
    progress = progress or (lambda msg: print(msg))
    cfg = config or DEFAULT_SOURCES
    resolved = {"mc_version": cfg.get("mc_version"), "mappings": [],
                "artifacts": []}
    skipped = []
    for entry in cfg.get("mappings", []):
        found = None
        for cand in entry["paths"]:
            if os.path.isfile(cand):
                found = cand
                break
        if found is None:
            if entry.get("required"):
                raise SystemExit("required mapping missing: %s (tried %s)"
                                 % (entry["name"], entry["paths"]))
            skipped.append({"name": entry["name"],
                            "reason": "no candidate path exists"})
            continue
        out = dict(entry)
        out["path"] = found
        del out["paths"]
        out["sha256"] = _sha256_of(found, "jar")
        resolved["mappings"].append(out)
    for entry in cfg.get("artifacts", []):
        if entry.get("mods_dir"):
            # goal-doc §2: index a whole mod directory as one artifact per
            # jar (recursive; every jar becomes its own MOD_JAR artifact so
            # per-artifact SHA caching can skip unchanged mods).
            mods_root = entry["mods_dir"]
            if not os.path.isdir(mods_root):
                if entry.get("required"):
                    raise SystemExit("required mods_dir missing: %s"
                                     % mods_root)
                skipped.append({"name": entry["name"],
                                "reason": "mods_dir does not exist"})
                continue
            jars = []
            for dirpath, _dirs, names in os.walk(mods_root):
                for n in sorted(names):
                    if n.lower().endswith(".jar"):
                        jars.append(os.path.join(dirpath, n))
            jars.sort()
            for jar in jars:
                out = {k: v for k, v in entry.items()
                       if k not in ("mods_dir", "name", "required", "note")}
                out["name"] = os.path.basename(jar)
                out["path"] = jar
                out["kind"] = "jar"
                progress("resolved %-22s %s" % (entry["layer"], jar))
                resolved["artifacts"].append(out)
            if not jars:
                skipped.append({"name": entry["name"],
                                "reason": "mods_dir contains no jars"})
            continue
        found, kind = None, entry.get("kind")
        for cand in entry["paths"]:
            if kind is None:
                if os.path.isfile(cand):
                    found, kind = cand, "jar"
                    break
                if os.path.isdir(cand):
                    found, kind = cand, "dir"
                    break
            elif kind == "jar" and os.path.isfile(cand):
                found = cand
                break
            elif kind == "dir" and os.path.isdir(cand):
                found = cand
                break
        if found is None:
            if entry.get("required"):
                raise SystemExit("required artifact missing: %s (tried %s)"
                                 % (entry["name"], entry["paths"]))
            skipped.append({"name": entry["name"],
                            "reason": "no candidate path exists"})
            continue
        out = dict(entry)
        out["path"] = found
        out["kind"] = kind
        del out["paths"]
        progress("resolved %-22s %s" % (entry["layer"], found))
        resolved["artifacts"].append(out)
    return resolved, skipped


def load_sources_file(path):
    with open(path, "r", encoding="utf-8") as fh:
        return json.load(fh)
