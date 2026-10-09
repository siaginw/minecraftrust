# RUNSCOPE — pre-boot lint + post-run evidence digest

`tools/runscope/` exists to keep campaign boots cheap. Every command is
read-only, stdlib-only, and works on `--json` for agent consumption.

```bash
# BEFORE building the campaign jar / booting a server (~1 s):
python tools/runscope/rustcraft_runscope.py lint

# AFTER a run (replaces the manual grep/JSON archaeology):
python tools/runscope/rustcraft_runscope.py report  target/authority-review/<run-dir>
python tools/runscope/rustcraft_runscope.py report  target/authority-review/<run-dir> --timeline
python tools/runscope/rustcraft_runscope.py compare <runA> <runB>

# Scripted source edits with verification (replaces raw s.replace heredocs):
python tools/runscope/rustcraft_runscope.py patch --file <path> \
    --replace "OLD===NEW" --must-contain "REGEX" --must-not-contain "REGEX"
# Postcondition-only drift check (did an earlier patch survive later edits?):
python tools/runscope/rustcraft_runscope.py patch --file <path> --check \
    --must-contain "Math\.max\(0, depth - 1\)"

# Event-driven wait — replaces `sleep N; grep` (deadline is a ceiling):
python tools/runscope/rustcraft_runscope.py waitfor --file server.log \
    --pattern "LIGHT_MUTATIONS_PASSED" --deadline 300
python tools/runscope/rustcraft_runscope.py waitfor --file server.log \
    --settle cells --stable-for 10 --deadline 120
python tools/runscope/rustcraft_runscope.py waitfor --file server.log \
    --pattern "compared=" --count 2000 --from-start --deadline 600

# First lifecycle divergence between two boots (the §11 hunt, one command):
python tools/runscope/rustcraft_runscope.py bootdiff <logA> <logB>

# Environment check BEFORE a boot (heavy builds / port / disk):
python tools/runscope/rustcraft_runscope.py preflight --port 25565

# machine-readable:
python tools/runscope/rustcraft_runscope.py --json lint
python -m unittest discover -s tools/runscope/tests   # 27 tests, no MC assets
```

## `waitfor` — event-driven waiting (policy: timeout is a ceiling)

Returns the moment the evidence condition is met; never holds for the full
deadline when the condition arrives early. Conditions (combinable, ALL must
hold): `--pattern REGEX [--count N]` (event-count floor) and/or
`--settle KEY [--stable-for S]` (a key=value counter unchanged for S
seconds — matches the change-driven metrics dumper, where silence after a
change IS the settled condition). Default watches only lines appended after
it starts (`--from-start` to include existing content); a file that appears
later is read from its beginning. Exit 0 met / 1 deadline expired / 2 file
never appeared. On timeout it prints what it observed (match counts, last
counter values) so the failure is diagnosable.

## `bootdiff` — first-divergence finder for launch-shape work

Reduces both server.log files to boot-relevant lines (tweaker chain,
coremod discovery, signature warnings, mod scan, mixin subsystem, FML
phases, RustCraft seam lines, boot completion), strips timestamps/thread
decoration, aligns them, and reports: per-category line counts (mixin 100
vs 0 is instantly visible), the tweaker sequences side by side, and the
FIRST divergence with context. This is the §8-§11 normal-vs-custom analysis
in one deterministic command.

## `patch` — verified text patches (the dev8-12 lesson)

Five campaign boots were burned because a scripted `python - <<EOF` chain of
`s.replace()` calls silently restored an old method body, and the failure
surfaced only as "jobs=0 forever" five runs later. `patch` makes that
workflow safe:

- Every `--replace OLD===NEW` must find OLD **exactly once**; `--replace-all`
  requires at least one occurrence. On any failure the file is left
  untouched — no partial application.
- `--must-contain` / `--must-not-contain` postconditions are checked against
  comment-and-string-stripped source for `.java` files (prose can neither
  satisfy nor trip them); replacements operate on raw text.
- The write is atomic (temp file + rename).
- `--check` runs postconditions only — the drift detector to run after later
  scripted edits to prove earlier invariants survived.

Exit code 1 on any failure. This is a hygiene tool: it verifies that a patch
landed as intended, not that the patched code is correct — the boot still
proves that.

## Why: the cost model this tool targets

The block-light authority work (Gate A diag4..13, Gate C dev4..15) spent
one full server boot per debugging iteration — each Gate C boot runs the
~190-mod Revelation pack. Several boots were burned by bug classes that are
statically detectable in seconds. This package checks for exactly those
classes before a boot, and digests run evidence after one.

## `lint` — pre-boot consistency checks

| Check | Catches | Severity | Evidence it comes from |
| --- | --- | --- | --- |
| `jni-pairing` | Java `native` declaration with no exactly-named Rust `Java_*` export (and the reverse). A missing pair is an `UnsatisfiedLinkError` at call time — silently swallowed catches turn it into a leak or a dead path. JNI underscore mangling (`_`→`_1`) is honored. | fatal (live classes) / warning (offline harnesses) / info (unused exports) | `WorldLightBridge`/`PhosphorLightBridge` declared natives under their own class names while the Rust exports bind `LightBatchCtx` (jobs=2402, compared=0 in dev4); `LightBatchCtx`/`RegionReadCtx`/`RegionWriteCtx` declare `closeRaw` while Rust exports `..._close` — every kernel/region context leaked on close behind `catch (Throwable ignore)`. |
| `bytebuffer-endianness` | A direct `ByteBuffer` used with typed accessors (`putInt`/`getLong`/...) without `.order(...)` in the same method. Java buffers are BIG-endian by default; the Rust kernel reads/writes native little-endian. | warning | diag10/11: big-endian `putInt` byte-swapped every staged coordinate → 43,116 bogus mismatches. `RegionWriteCtx.floors()/stats()` still read Rust-written LE u64s as BE. |
| `cross-loader-forname` | `Class.forName` with literal `net.minecraft.*` / `me.jellysquid.*` / notch-style names inside live runtime classes (hooks/bridges/transformers/tweaker). Runtime classes keep SRG names on the LaunchClassLoader; the system/launch loader split makes these binds fail or miss. Offline harnesses (`M4*`, tests, benches) are exempt. | warning | diag6: 276,066 hook errors from deobf-name `Class.forName` before the object-derived reflection rewrite. |
| `ordinal-gate` | Ordinal **equality dispatch** (`== 0`, `!= 0`, `== 1`) in light hooks/transformers. `< 0` validity sentinels are fine and not flagged. | warning | The `EnumSkyBlock` SKY=0/BLOCK=1 gate was written inverted twice (ordinal-keyed instead of name-keyed). |

Sources are comment/string-stripped before matching (prose like "this class
name" cannot fire a false positive), and buffer analysis is scoped to the
declaring method. Exit code is 1 when a `fatal` finding exists, so a build
script can gate on it. Findings are hints with cited evidence, not verdicts —
`--min-severity info` shows dead-export surfaces too.

## `preflight` — environment gate before a boot

Catches the boot-burning environment failures in ~2 s: heavy build
processes running (cargo/rustc/javac — a concurrent `cargo test` stalled a
Gate C boot 114 s and burned it, light-mut-C-dev1), the campaign port
already bound (probe connections black-hole, the bytes_in:0 failure mode),
and disk headroom on the run volume (run dirs hold full server copies).
Exit 1 on blockers; read-only.

## `report` — one-page evidence digest of a run directory

- receipt verdict / exit reason / failure reasons / tier / git sha / wall time
- evidence targets vs observed (`receipt.json`)
- **staged-jar provenance**: sha256 of the jar inside the run's `server/`
  dir vs the current `target/rustcraft-campaign-<target>.jar` → `MATCH` /
  `STALE` (the runner stages exact paths; two debug cycles were invalidated
  by stale-jar staging before being caught manually)
- duplicate mod jars in the staged `mods/` tree
- mutation receipt step table (`light-mutations.json`) with per-step deltas
  and settle times
- **probe digest** (`probe-receipts.json`): per-round verdict, packets,
  bytes, and timings — probe failures were the recurring red herring
- **metrics file** (`region-metrics.txt`): parsed as the authoritative
  counter snapshot (the tweaker rewrites it whole, so it survives the
  process-kill race that loses shutdown log lines — commit 585a425);
  `compare` folds these counters into its deltas
- server.log digest: boot-Done, probe logins/logouts (deduped), teleports,
  setblock rejections, "Can't keep up" count + max-behind, exception
  histogram with first/last line numbers, the last RustCraft light-counter
  lines (payload extracted from the log4j wrapper), parsed counters, and the
  loaded tweaker list

The timeline answers the sequencing traps directly: a probe logout before
the mutation phase, or setblock failures from unloaded chunks, are one
glance instead of log archaeology.

## `compare` — delta two run directories

Counter deltas (receipt evidence + mutation finals), per-side staged-jar
status, lag/setblock-failure counts, and top exception signatures. This is
the dev-iteration diff: e.g. dev13→dev14 shows `phosphorJobs 3→10`,
`phosphorMis 30→0`, `depthLeaksHealed 43` at a glance.

## Provenance honesty

Like the symbol index, runscope output is navigation/diagnostic evidence.
It never certifies authority claims — campaign receipts and event counts
remain the proof standard (AGENTS.md). The lint flags bug *shapes* with
cited prior incidents; a clean lint is not a correctness proof.

## Seam decision log (#1 — the report side; hooks write the file)

The dev13-20 position-decode chase needed one full boot per "why was this
job skipped" question, because the answers only existed as ad-hoc println
traces. The durable fix: the hooks append every jobStart/jobEnd decision to
`<run>/server/seam-decisions.jsonl` — one JSON object per line:

    {"ts": "17:57:41", "hook": "phosphorLight", "decision": "accepted",
     "reason": "in-window+chunk-local+near-anchor",
     "positions": [[196, 70, 283]], "volume": 1234,
     "window": true, "anchor": [196, 70, 283]}
    {"ts": "17:57:42", "hook": "phosphorLight", "decision": "skipped",
     "reason": "far-from-anchor", "first": [0, 0, 0], "window": true}

Conventions: ring-buffer/append is fine (a few KB per session); include
the DECODED positions on skips (the (0,0,0) smoking gun), the skip reason
vocabulary (pre-window / far-from-anchor / multi-chunk / oversized /
cascade), and the mismatch samples with full inputs (cell, 6 neighbors,
opacity, emission, java value, rust value). `report --timeline` picks the
file up automatically (server/ or run root) and interleaves it; the
ring-buffer cost when idle is one file handle and zero writes.

The same shape applies to WorldLightHook skips and mismatch samples
(replacing the 4-slot in-memory ring).

## `decode-solve` — wire-format solver (#3)

Feed captured input->packed pairs; it brute-forces each lane's
(shift, width, bias, signedness), rejects overlapping lanes, emits Java +
Python decoders, and pins the vectors:

    python tools/runscope/decode_solve.py --pairs pairs.json

Validated against the real Phosphor encoding: five captured vectors
recover y<<52 | (x+2^25)<<26 | (z+2^25) exactly (26-bit biased lanes) —
the layout that took three boots and a javap session. Bias beats narrow
two's-complement in the ranking; when a layout is under-determined the
output says so and asks for one extreme-valued vector.

## `expect` — durable seam invariants (the committed regression suite)

`lint` checks generic bug shapes; `expect` checks specific invariants of
specific files, each one derived from a real incident and citing it:

```bash
python tools/runscope/rustcraft_runscope.py expect          # all invariants
python tools/runscope/rustcraft_runscope.py expect --only phosphor-depth-decrement
```

Invariants live in `tools/runscope/expectations.json` (must-contain /
must-not-contain regexes over comment-stripped source). The seeded set
encodes the fixes that must never regress:

| id | invariant | incident |
| --- | --- | --- |
| `phosphor-depth-decrement` | jobEnd decrements DEPTH before the ordinal gate | dev8-12: jobs=0 for a whole session |
| `phosphor-masks-int-accessor` | masks read with getInt, never getLong | dev17/18: all positions decoded (0,0,0) |
| `lightbatch-little-endian` | kernel buffers explicitly LITTLE_ENDIAN | diag10/11: 43k bogus mismatches |
| `worldlight-bridge-delegates` | WorldLightBridge declares no natives | dev4: wrong-class natives |
| `phosphor-bridge-delegates` | PhosphorLightBridge declares no natives | dev4: jobs=2402, compared=0 |
| `worldlight-hook-name-keyed` | enum NAME dispatch, no ordinal equality | diag-era: inverted gate twice |

A failure means a fix regressed OR a file was legitimately redesigned:
update the expectation in the same commit, never silently. Run `lint` +
`expect` before every boot; both exit non-zero on actionable findings.

## Justifying lint findings (`RUNSCOPE-JUSTIFIED:`)

A warning finding (cross-loader forName, bytebuffer order, ordinal gate,
reflection accessor) is suppressed when a comment containing
`RUNSCOPE-JUSTIFIED: <reason>` appears within 8 lines above it. Justified
findings are still printed (with their reason text) so the trail stays
visible, but they do not count as actionable and do not affect the exit
code. FATAL findings (missing JNI exports) are never suppressible — a
missing export cannot be justified away, only fixed.

## `reflection-accessor` check (new, dev17/18)

`Field.getLong` applied to a field fetched by name (`getDeclaredField`) is
flagged as a verify-the-accessor hint: a getLong-on-int throws per call and
a swallowed catch silently defaults every read — the exact mechanism that
decoded every queue position to (0,0,0) in dev17/18 while the hook appeared
healthy.

No off-the-shelf tool covers these checks on Java 8 JNI: JNI mismatch
detection exists only as academic Android tools; Minecraft log analysis
exists as closed AI services (MCDoctor) or in-game plugins; ErrorProne/
SpotBugs custom checks need a Java build integration this repo's tooling
doesn't have; Panama/jextract (which solves binding/endianness by
construction) requires post-8 JDKs. The checks implemented here mirror the
pitfall classes documented in the Android NDK JNI tips and IBM JNI
best-practice guides.

## Prior art (why this is hand-rolled)

No off-the-shelf tool covers these checks on Java 8 JNI: JNI mismatch
detection exists only as academic Android tools; Minecraft log analysis
exists as closed AI services (MCDoctor) or in-game plugins; ErrorProne/
SpotBugs custom checks need a Java build integration this repo's tooling
doesn't have; Panama/jextract (which solves binding/endianness by
construction) requires post-8 JDKs.
