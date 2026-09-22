# Source Inventory & Reference Corpus

## 1. Minecraft 1.12.2 Server
- **NAME:** Minecraft Java Edition Dedicated Server
- **PURPOSE:** Reference execution oracle, simulation baseline, and protocol parity target
- **VERSION:** `1.12.2` (Release date: 2017-09-18)
- **SOURCE:** Official Mojang Piston launcher meta: `https://piston-data.mojang.com/v1/objects/886945bfb2b978778c3a0288fd7fab09d315b25f/server.jar`
- **COMMIT / HASH:** SHA1: `886945bfb2b978778c3a0288fd7fab09d315b25f` (Size: 30,222,121 bytes)
- **LICENSE:** Proprietary commercial software (Mojang Synergies AB / Microsoft)
- **LOCAL PATH:** `third_party_reference/minecraft/minecraft_server.1.12.2.jar` (Acquired via `third_party_reference/minecraft/fetch-server.sh`)
- **CODEGRAPH INDEX STATUS:** Pending decompile / mapping in P0-1
- **REDISTRIBUTABLE:** **NO**. Must remain untracked in git. Acquired locally via script.
- **NOTES:** Target for protocol 340 client connections and golden-world verification.

## 2. Minecraft Mappings (MCP)
- **NAME:** Mod Coder Pack (MCP) Mappings
- **PURPOSE:** Translating stable SRG identifiers to human-readable field/method names
- **VERSION:** Channel `stable`, Version `39-1.12` (Alternate: `snapshot_20171003`)
- **SOURCE:** `https://maven.minecraftforge.net/de/oceanlabs/mcp/mcp_stable/39-1.12/mcp_stable-39-1.12.zip`
- **COMMIT / HASH:** MD5: `378607` bytes archive
- **LICENSE:** Public Domain / MCP community license
- **LOCAL PATH:** `third_party_reference/mappings/export/` (`fields.csv`, `methods.csv`, `params.csv`)
- **CODEGRAPH INDEX STATUS:** Reference CSV data tables
- **REDISTRIBUTABLE:** **YES**. Permissively licensed community data.
- **NOTES:** Pinned for bidirectional SRG <-> MCP translation.

## 3. MinecraftForge Reference Source
- **NAME:** MinecraftForge & FML (Forge Mod Loader)
- **PURPOSE:** Modding framework, lifecycle management, event bus, capability API, and patch reference
- **VERSION:** `14.23.5.2864` (Tip of `1.12.x` release line; fully binary-compatible with reference `14.23.5.2860`)
- **SOURCE:** `https://github.com/MinecraftForge/MinecraftForge.git`
- **COMMIT / HASH:** Commit `3effde4f1fc9d14d6ed1dbf6bebc39c2b18780e1`
- **LICENSE:** LGPL-2.1
- **LOCAL PATH:** `third_party_reference/forge/src/`
- **CODEGRAPH INDEX STATUS:** **INDEXED** (880 files, 24,082 nodes, 48,501 edges)
- **REDISTRIBUTABLE:** **YES** (Open source under LGPL-2.1). Maintained as local clone / git submodule.
- **NOTES:** Contains `patches/minecraft/` unified diffs applied to vanilla classes.

## 4. ForgeGradle Build Tooling
- **NAME:** ForgeGradle
- **PURPOSE:** Workspace generation, deobfuscation, reobfuscation (`reobfJar`), runtime patcher
- **VERSION:** Generation `2.3-SNAPSHOT` / `2.3.4` (with historical FG 3+ userdev backport reference)
- **SOURCE:** `net.minecraftforge.gradle:ForgeGradle:2.3-SNAPSHOT` via `https://maven.minecraftforge.net/`
- **COMMIT / HASH:** Branch `FG_2.3` in `MinecraftForge/ForgeGradle.git`
- **LICENSE:** LGPL-2.1
- **LOCAL PATH:** `third_party_reference/forgegradle/`
- **CODEGRAPH INDEX STATUS:** Tooling documentation & build scripts
- **REDISTRIBUTABLE:** **YES** (Open source under LGPL-2.1).
- **NOTES:** Requires Java 8 and Gradle 4.9–4.10.3.

## 5. Java 8 Toolchain
- **NAME:** Eclipse Temurin OpenJDK with Hotspot 8
- **PURPOSE:** Compiling and executing reference 1.12.2 server, ForgeGradle, and MCP decompile tasks
- **VERSION:** `8.0.504.1` (build `1.8.0_504-b01`)
- **SOURCE:** Eclipse Adoptium (`EclipseAdoptium.Temurin.8.JDK` on winget)
- **COMMIT / HASH:** Release tag `jdk8u504-b01`
- **LICENSE:** GPLv2 with Classpath Exception
- **LOCAL PATH:** `C:\Program Files\Eclipse Adoptium\jdk-8.0.504.1-hotspot` (Invoked via `tools/java8/`)
- **CODEGRAPH INDEX STATUS:** External SDK / JDK toolchain
- **REDISTRIBUTABLE:** **YES** (Standard binary distribution).
- **NOTES:** Side-by-side install. Does not overwrite machine default Java 25.

## 6. Minecraft Protocol 340 Specification
- **NAME:** Minecraft Protocol 340 Specification
- **PURPOSE:** Wire protocol definition for Minecraft 1.12.2 client/server communication
- **VERSION:** Protocol `340`
- **SOURCE:** PrismarineJS / wiki.vg protocol documentation
- **COMMIT / HASH:** Local machine-readable specification `third_party_reference/protocol/protocol-340.json`
- **LICENSE:** Creative Commons Attribution-ShareAlike 3.0 / MIT
- **LOCAL PATH:** `third_party_reference/protocol/`
- **CODEGRAPH INDEX STATUS:** JSON reference data
- **REDISTRIBUTABLE:** **YES**. Public protocol documentation.
- **NOTES:** Documents handshake, encryption, compression, and Forge channel extensions (`FML|HS`).
