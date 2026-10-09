# tools/runscope — pre-boot lint + post-run evidence digest

Keeps campaign boots cheap: statically catches the JNI/endianness/loader/
ordinal bug classes that cost real boots during the block-light authority
work, and digests campaign run directories into one page (or a two-run
delta) instead of manual grep/JSON archaeology.

```bash
python tools/runscope/rustcraft_runscope.py lint          # ~1 s, before a boot
python tools/runscope/rustcraft_runscope.py report  target/authority-review/<run>
python tools/runscope/rustcraft_runscope.py compare <runA> <runB>
```

- All commands: read-only, stdlib-only, `--json` supported.
- `lint` exit code 1 on fatal findings → usable as a build-script gate.
- Tests: `python -m unittest discover -s tools/runscope/tests` (synthetic
  fixtures only, no Minecraft assets).
- Full check catalog, evidence citations, and honesty notes:
  `docs/engineering/RUNSCOPE.md`.

## Layout

| Path | Purpose |
| --- | --- |
| `rustcraft_runscope.py` | CLI (`lint`, `report`, `compare`) |
| `bridge_lint.py` | Java/Rust source consistency checks (JNI pairing, ByteBuffer order, cross-loader forName, ordinal gates); comment/string-stripped scanning |
| `run_report.py` | run-directory digester (receipt, staged-jar provenance, timeline, exception histogram, light counters) |
| `tests/test_runscope.py` | unit tests on synthetic trees/run dirs |
