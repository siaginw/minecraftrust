# Protocol 340 / Forge 1.12.2 Networking Knowledge

Version-pinned knowledge layer: Minecraft Java Edition **1.12.2**, protocol
**340**, Forge **14.23.5.x** (ref 2860). Built 2026-09-21. No production
protocol behavior changed.

## Index

- [Knowledge report (all sections A–N)](knowledge-report.md)
- [Source manifest + citations](manifest-sources.md)
- [Packet registry (generated IDs) + wire table](packet-registry-wire.md)
- [Per-packet wire layouts](packet-wire-layouts.md)
- [Data-type oracle + Rust coverage](datatypes-oracle.md)
- [Chunk format deep dive](chunk-format-deep-dive.md)
- [Forge/FML networking layer](forge-fml-networking.md)
- [Java implementation map](java-implementation-map.md)
- [Coverage + parity/test matrix](coverage-matrix.md)
- [External implementations](external-implementations.md)
- [CodeGraph relationships](codegraph-relationships.md)

## Registry of record (supporting, in `machine/`)
- `machine/protocol-340-packets.yaml` — 124 packets (ID/class/direction, generated).
- `machine/network-pipeline.yaml` — Netty pipeline spec + live verification.
- `machine/network-state-machine.yaml` — state transitions + Forge substates.
- `machine/fml-handshake.yaml` — FML|HS state machine.
- `machine/reference-versions.yaml` — artifact/version/licenses.

## Skill
`protocol-340-forge-network` (project skills) — specialist operating guide.

## Golden rules
1. 1.12.2/340 only — verify every format against local `third_party_reference` sources.
2. Packet IDs come from `EnumConnectionState.java` registration order, never inferred from names.
3. Forge extensions layer on top of vanilla; label them; never merge unlabeled.
4. Read/decode path is greenfield in Rust — build it with the Python bots as oracle.
