# Fixture provenance and third-party notices

These fixtures and adapter implementations were written for RustCraft. No
third-party implementation, complete protocol schema or Minecraft binary is
vendored into either crate.

Release numbers, DataVersion numbers, field types, packet IDs and position bit
widths were checked against the primary `PrismarineJS/minecraft-data` repository,
tag `3.112.0`, resolved by `git ls-remote` to immutable commit
`2157a992b9eaf07a292101f1823dfca725e6aea8`. The checked facts and complete source
SHA-256 digests are recorded in `primary-source-review.json`.

- [Release metadata](https://github.com/PrismarineJS/minecraft-data/blob/2157a992b9eaf07a292101f1823dfca725e6aea8/data/pc/common/protocolVersions.json)
- [1.12.1 packet schema](https://github.com/PrismarineJS/minecraft-data/blob/2157a992b9eaf07a292101f1823dfca725e6aea8/data/pc/1.12.1/protocol.json)
- [1.12.2 packet schema](https://github.com/PrismarineJS/minecraft-data/blob/2157a992b9eaf07a292101f1823dfca725e6aea8/data/pc/1.12.2/protocol.json)
- [Upstream license declaration](https://github.com/PrismarineJS/minecraft-data/blob/2157a992b9eaf07a292101f1823dfca725e6aea8/README.md#license)

Upstream declares **MIT** in its README. That declaration also cautions that
some data originates from external wikis and that source-specific licensing may
require revision. We do not treat the declaration as a blanket clearance for
redistributing all upstream datasets. This bounded use records protocol facts
and their provenance. Future vendoring must preserve applicable notices and
review the specific copied material.

`keepalive.tsv` contains 18 manually specified packet-body vectors, independently
checked by `KeepAliveOracle.java` using Java 8 integer operations and JDK
`DataOutputStream.writeLong`. The Java check does not call the Rust encoder and
is not presented as a capture from Minecraft or Forge. Packet framing and
compression are intentionally absent. `positions.tsv` contains three manually
specified field-layout vectors; boundary cases are additionally tested in Rust.
`release-metadata.tsv` records the source-verified constants as independent test
inputs. None of these fixtures establishes gameplay, Forge or modpack support.

`resource-location-review.json` records factual constructor behavior observed in
an existing qualified Clean Forge 1.12.2 class with Java 8 `javap`, plus a primary
Forge documentation reference. It does not vendor the class, decompiled source
or a runtime binary; the raw local inspection remains under `target/`. This
observation informed the decision to keep version-specific name normalization
out of the core. No upstream name parser was copied or implemented here.
