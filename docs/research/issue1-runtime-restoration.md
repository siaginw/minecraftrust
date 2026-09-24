# Issue #1 official runtime restoration

Restored on 2026-09-24 for the later validation ladder. These are newly prepared
server distributions, **not restored historical worlds, packet captures, or proof
that any runtime is eligible for native packet authority**. All three targets are
`PREPARED_NOT_LAUNCHED`: the official Forge installers completed successfully,
but no server, Forge oracle, live shadow campaign, or client was started.

## Exact targets and official sources

Each target has separate `downloads/` and `server/` directories outside the
repository. No downloaded binaries, pack contents, worlds, or mapped Minecraft
sources are included in this change.

| Target directory name | Minecraft | Pack | Forge | Preparation result |
| --- | --- | --- | --- | --- |
| `clean-forge-2860` | 1.12.2 | None | 14.23.5.2860 | Installer exit 0; 24 server files |
| `revelation-3.4.0` | 1.12.2 | FTB Revelation 3.4.0 | 14.23.5.2846 | Installer exit 0; all 876 server manifest entries verified |
| `sevtech-3.2.3` | 1.12.2 | SevTech: Ages 3.2.3 | 14.23.5.2860 | Installer exit 0; 1,789 official archive entries extracted and verified |

- [Forge's official 1.12.2 index](https://files.minecraftforge.net/net/minecraftforge/forge/index_1.12.2.html)
  identifies both exact installer versions. Downloads came from Forge's Maven
  service; published installer SHA-1 values were checked before execution.
- [FTB's official server page](https://www.feed-the-beast.com/modpacks/server-files/windows/35-ftb-revelation)
  identifies Revelation 3.4.0 as pack `35`, version `174`. Its
  [version manifest](https://api.feed-the-beast.com/v1/modpacks/public/modpack/35/174)
  explicitly selects Forge **14.23.5.2846**. The restore copied every non-client-only
  file from the manifest's official FTB/CurseForge URLs and checked its SHA-256
  and SHA-1. No newer pack or Forge version was substituted.
- [SevTech's official CurseForge server file](https://www.curseforge.com/minecraft/modpacks/sevtech-ages/files/3570046)
  is file `3570046`, `SevTech_Ages_Server_3.2.3.zip`. It was downloaded from
  [CurseForge's CDN](https://edge.forgecdn.net/files/3570/46/SevTech_Ages_Server_3.2.3.zip).
  The archive's installer and `settings.bat` both identify Forge 14.23.5.2860.
- Minecraft's server URL and SHA-1 came from Mojang's
  [1.12.2 version metadata](https://piston-meta.mojang.com/v1/packages/832d95b9f40699d4961394dcf6cf549e65f15dc5/1.12.2.json).
  Forge libraries were fetched using the pinned installer metadata and official
  Forge/Mojang library endpoints; matching verified inputs were copied between
  the two 2860 targets.

## Recorded SHA-256 identities

| Artifact | SHA-256 |
| --- | --- |
| Mojang `minecraft_server.1.12.2.jar` | `fe1f9274e6dad9191bf6e6e8e36ee6ebc737f373603df0946aafcded0d53167e` |
| Forge 2860 installer | `ea7c33ba95e3993a98d0e9e38168c0759ec323a18675a71d938e1f3f70e6e8e7` |
| Forge 2860 installed jar | `cd3fbf85d7ca744507fd6a37a41b90122d43e616a4f8962332b1b658655e8a64` |
| Forge 2846 installer | `c887459f942b4b31ee387821c85a40a07ec98be165b0dd2db0ed99d59d891ebc` |
| Forge 2846 universal jar | `2484e7e36db090d61e567e75714ce7c16549983516842fe1bd34e809e92efa10` |
| Revelation official version manifest | `33d960217ed76f09ed5f31f082a93a60a4cb7f25b760428fdaf958bc626a16f6` |
| SevTech 3.2.3 server archive | `4adaef5dbff9ffab1fd6f7440772545c0b2e691b163d745c9ef664c0c0131e94` |
| Complete local artifact inventory | `fcd1eb5c7762eab99d7a25cb9f12bb976328e624d2383f578e854fbb0846c123` |

The external restoration receipt contains `runtime-targets.json` and
`restored-artifacts.json`: 2,766 entries with relative paths, byte counts,
SHA-256 values, official source URLs/archive members, and separate classifications
for installer logs and preserved incomplete download output. Incidental output
from the old installer is attributed to its exact installer rather than claiming
an individual fetch URL that it did not log. It also retains the
upstream manifests, download/retry receipts, and successful installer logs.
The root directory and receipt location are recorded locally, not embedded as
machine-specific repository paths.

## Preparation limits and next operator steps

The preparation JVM was Temurin 8u504-b01. FTB's current manifest also names Java
8.0.312+7; that older JVM was not restored, and this is not a claim of an identical
historical JVM environment. The loaded mod list, transformed classes, registry
width, writer participation and live behavior remain unmeasured.

No EULA was accepted, no `eula.txt` or world was created, and no RustCraft bridge
was installed into these targets. No client account/login was needed for these
official server distributions. A later licensed client installation, if required,
must use the operator's authorized launcher/account. Historical capture artifacts
remain absent; the evidence registry was not changed.

Before the Forge oracle lane can pass, qualify an exact mapped test classpath and
record the transformation, registry, pack/config and writer-capability manifests.
Binary restoration alone does not satisfy that lane's manifest prerequisites.
Keep progression as public unit/property tests, synthetic replay/strict decoder,
Java standalone tests, qualified Forge oracle, then separately authorized short
live shadow and modpack shadow. Production authority remains fail-closed.

Reproduction detail: the older Forge 2846 installer uses the process working
directory for `--installServer`; explicitly set that directory after any shell
activation script. The 2860 installer accepts an explicit destination. An initial
partial Mojang download and incidental installer staging outputs were preserved
outside the repository; all final runtime inputs were independently rehashed.
