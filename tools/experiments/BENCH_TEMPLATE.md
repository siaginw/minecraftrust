# Rust bench-harness template (retro 2026-10-09)

The P0 corpus bench cost six bring-up cycles to mechanical misses. Copy
this skeleton when adding any `#[test]`-based bench under `crates/*/tests/`.

```rust
// 1. crate import: the package name in Cargo.toml [lib] name — for the
//    ffi crate that is `rustcraft_ffi`, NOT `ffi` (bit kernel_corpus).
use rustcraft_ffi::zero_stage_job::run_zero_stage_job;

// 2. test binaries run with CWD = the PACKAGE root, not the workspace
//    root — resolve data files by walking up to the workspace marker:
fn workspace_root() -> std::path::PathBuf {
    let mut dir = std::env::current_dir().unwrap();
    for _ in 0..5 {
        if dir.join("Cargo.toml").is_file() && dir.join("crates").is_dir() {
            return dir;
        }
        dir.pop();
    }
    panic!("workspace root not found")
}

// 3. API traps already paid for once:
//    - region_io::ChunkRecord.payload is ALREADY decompressed.
//    - nbt::NbtDecoder::decode returns (name, root) where root is the
//      ROOT compound; fetch "Level" from it as a Compound variant.
//    - kernels can fail-closed (negative rc) on LEGAL jobs (deep
//      cascades reaching unloaded chunks): record, don't assert.
//    - digest loops: `for i in 0..rc.max(0) as usize` — never the raw
//      cast of a possibly-negative i32.
//
// 4. A/B variants belong behind cargo features (matrix = rebuilds with
//    different --features; see legacy-overlay / vec-head-fifo), and
//    every variant must print a canonical DIFF_DIGEST line for
//    cross-variant semantic-equality comparison (backlog rule 1:
//    whole-job evidence, not component replays).
```
