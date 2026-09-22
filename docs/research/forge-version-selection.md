# Forge Version Selection & Baseline Analysis

## 1. Executive Summary
- **Official Recommended Build:** `14.23.5.2859` (Released 2021-12-11)
- **Official Latest Build:** `14.23.5.2864` (Released 2025-12-03)
- **Log4j CVE-2021-44228 Fixed Build:** `14.23.5.2860` (Released 2021-12-13)
- **Selected Reference Implementation Build:** **`14.23.5.2860`**
- **Overarching Compatibility Objective:** **`14.23.5.x`** (Builds 2847 through 2864)

## 2. Release Line History & Server-Relevant Changes

| Build | Release Date | Key Changes | Server / Runtime Impact |
|---|---|---|---|
| **14.23.5.2847** | 2019-09-03 | Baseline 1.12.2 release | Longstanding stable modpack baseline. |
| **14.23.5.2854** | 2020-04-12 | Fix universal jar classpath (#7546) | Critical for headless dedicated server launch. |
| **14.23.5.2856** | 2021-12-10 | Log4j patch (#8275) | Fixes CVE-2021-44228 in logging pipeline. |
| **14.23.5.2859** | 2021-12-11 | Installer 2.1+, maven URL updates | Pinned by Forge promo as "Recommended". |
| **14.23.5.2860** | 2021-12-13 | Exclude `log4j-slf4j18-impl` (#8293) | Fixes SLF4J classpath conflict during server boot. |
| **14.23.5.2864** | 2025-12-03 | Biome Dictionary fix (#10725), CI migration | Current "Latest" build; minor biome dict recursion fix. |

## 3. Major Modpack Ecosystem Analysis
An analysis of tier-1 Minecraft 1.12.2 modpacks reveals:
- **Enigmatica 2: Expert (E2E):** Pins `14.23.5.2859` or `14.23.5.2860`.
- **Nomifactory / Omnifactory:** Recommends `14.23.5.2860`.
- **SevTech: Ages:** Pins `14.23.5.2854` / `14.23.5.2859`.
- **RLCraft v2.9.x:** Ships on `14.23.5.2860`.
- **GregTech: New Horizons (1.12.2 test port):** Built on `14.23.5.2860`.

None of the builds between 2847 and 2864 introduce breaking binary API changes to Forge's public surface, registries, event bus, or capability system.

## 4. Reference Build Justification: Why `14.23.5.2860`
1. **Security & Classpath Cleanliness:** It includes the Log4j CVE fix and resolves the SLF4J binding conflict (#8293) present in 2859.
2. **Ecosystem Parity:** It matches the deployed server binary across modern 1.12.2 modpack launchers (CurseForge, Prism, FTB).
3. **Reproducibility:** All Maven artifacts (installer, universal, mdk) are mirrored and checksummed in Maven Central / Forge repositories.

Build `14.23.5.2860` is pinned as the primary source study reference, while runtime designs must remain compatible across the full `14.23.5.x` family.

## 5. Exact Source Commit Verification for Build 2860
- **Commit:** `d3f01843f7e7a4f613b5e8113d381fd8747b4343`
- **Identification Method:** Correlated commit message `Don't download log4j-slf4j18-impl (#8293)` with Forge build 2860 changelog (`maven.minecraftforge.net/net/minecraftforge/forge/1.12.2-14.23.5.2860/forge-1.12.2-14.23.5.2860-changelog.txt`), which attributes entry `1904167+dualspiral: Don't download log4j-slf4j18-impl (#8293)` to build 2860 on Mon Dec 13 04:36:38 GMT 2021.
- **Diff vs 2864 HEAD (`3effde4f1`):**
  - Only two Java source lines differ: `BiomeDictionary.java` (minor recursion log fix #10725) and `MinecraftFormattingConverter.java` (copyright header).
  - All other diffs are CI scripts (`.github/workflows`, `Jenkinsfile`, Gradle wrapper update).
- **Checkout State:** `third_party_reference/forge/src` is checked out in detached HEAD directly at `d3f01843f7e7a4f613b5e8113d381fd8747b4343`.
- **CodeGraph Status:** Indexed at commit `d3f01843f7e7a4f613b5e8113d381fd8747b4343` (880 files, 24,082 nodes, 48,501 edges).
