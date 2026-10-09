//! Shared REAL-corpus loader for kernel benches (P0 + OPT-LIGHT-003).
//! Extracted from the campaign world .mca; deterministic (corpus hash).

use native_chunk::{ChunkKey, NativeChunk};
use rustcraft_ffi::get_registry;

pub fn corpus_mca() -> std::path::PathBuf {
    if let Ok(p) = std::env::var("RUSTCRAFT_CORPUS_MCA") {
        return p.into();
    }
    // tests run with cwd = package root; resolve from the workspace root
    let rel = "target/authority-review/zeroStage-A-zsa14/server/world/region/r.0.0.mca";
    let mut dir = std::env::current_dir().unwrap();
    for _ in 0..5 {
        if dir.join("Cargo.toml").is_file() && dir.join("crates").is_dir() {
            return dir.join(rel);
        }
        dir.pop();
    }
    std::path::PathBuf::from(rel)
}

pub fn fnv1a64(bytes: &[u8]) -> u64 {
    let mut h: u64 = 0xcbf29ce484222325;
    for &b in bytes {
        h ^= b as u64;
        h = h.wrapping_mul(0x100000001b3);
    }
    h
}

/// vanilla-ish id → (opacity, emission); unknown → opaque dark.
pub fn light_props(id: u32) -> (u8, u8) {
    match id {
        0 => (0, 0),             // air
        50 => (0, 14),           // torch
        91 => (0, 15),           // glowstone
        10 | 11 => (15, 15),     // lava (flowing|still)
        51 => (0, 15),           // fire
        327 => (0, 15),          // lava? keep table small
        8 | 9 => (3, 0),         // water
        18 | 161 => (1, 0),      // leaves
        20 | 95 | 160 => (0, 0), // glass
        79 => (3, 0),            // ice
        78 => (2, 0),            // snow layer
        106 => (1, 0),           // vines
        _ => (15, 0),            // default solid/dark
    }
}

pub fn build_table(states_present: &[u32]) -> Vec<u8> {
    let max = states_present.iter().copied().max().unwrap_or(1) as usize + 1;
    let mut t = vec![0u8; (max + 1) * 3];
    for &sid in states_present {
        let (op, em) = light_props(sid >> 4);
        let base = sid as usize * 3;
        if base + 2 < t.len() {
            t[base] = 1;
            t[base + 1] = op;
            t[base + 2] = em;
        }
    }
    // always classify air explicitly
    t[0] = 1;
    t[1] = 0;
    t[2] = 0;
    t
}

pub struct LoadedChunk {
    pub primer: Vec<u32>,
    pub light: Vec<u8>, // per-cell block light (0..15)
    pub cx: i32,
    pub cz: i32,
}

pub fn load_chunk(mca: &std::path::Path, cx: i32, cz: i32) -> Option<LoadedChunk> {
    use nbt::NbtDecoder;
    let rec = region_io::read_chunk(mca, (cx & 31) as u8, (cz & 31) as u8).ok()??;
    // ChunkRecord.payload is ALREADY the decompressed NBT stream
    let (_name, root) = NbtDecoder::decode(&rec.payload).ok()?;
    let level = match &root {
        nbt::NbtTag::Compound(c) => match c.get("Level")? {
            nbt::NbtTag::Compound(l) => l,
            _ => return None,
        },
        _ => return None,
    };
    let sections = match level.get("Sections")? {
        nbt::NbtTag::List(xs) => xs,
        _ => return None,
    };
    let mut primer = vec![0u32; 65536];
    let mut light = vec![0u8; 65536];
    let mut states_seen: Vec<u32> = vec![0];
    for sec in sections {
        let sc = match sec {
            nbt::NbtTag::Compound(c) => c,
            _ => continue,
        };
        let y = match sc.get("Y") {
            Some(nbt::NbtTag::Byte(b)) => *b as i32,
            _ => continue,
        };
        if !(0..=15).contains(&y) {
            continue;
        }
        let blocks = match sc.get("Blocks") {
            Some(nbt::NbtTag::ByteArray(b)) if b.len() == 4096 => b,
            _ => continue,
        };
        let add: Option<&Vec<u8>> = match sc.get("Add") {
            Some(nbt::NbtTag::ByteArray(b)) if b.len() == 2048 => Some(b),
            _ => None,
        };
        let bl = match sc.get("BlockLight") {
            Some(nbt::NbtTag::ByteArray(b)) if b.len() == 2048 => b,
            _ => continue,
        };
        for i in 0..4096usize {
            let mut id = blocks[i] as u32 & 0xFF;
            if let Some(a) = add {
                let nib = if i & 1 == 0 {
                    a[i >> 1] & 0x0F
                } else {
                    a[i >> 1] >> 4
                };
                id |= (nib as u32) << 8;
            }
            let state = if id == 0 { 0 } else { id << 4 };
            if state != 0 && !states_seen.contains(&state) {
                states_seen.push(state);
            }
            // anvil index: y<<8|z<<4|x ; primer index: x<<12|z<<8|y
            let sy = (i >> 8) & 15;
            let sz = (i >> 4) & 15;
            let sx = i & 15;
            let pidx = (sx << 12) | (sz << 8) | ((y as usize) * 16 + sy);
            primer[pidx] = state;
            let lv = if i & 1 == 0 {
                bl[i >> 1] & 0x0F
            } else {
                bl[i >> 1] >> 4
            };
            light[pidx] = lv;
        }
    }
    Some(LoadedChunk {
        primer,
        light,
        cx,
        cz,
    })
}

/// Re-solve block light to the fixed point of the corpus table over all
/// loaded chunks (Jacobi relaxation, algorithm-independent — mirrors the
/// differential-fuzz oracle). The corpus baseline must be a CONSISTENT
/// fixed point: OPT-ALG-001's carried entries are sound on consistent
/// baselines, and production baselines (Java-mirrored light) are
/// consistent by construction. Called once after priming, before jobs.
/// Re-solve block light to the fixed point of the corpus table over the
/// loaded region (Jacobi relaxation on a dense grid — algorithm-
/// independent, mirrors the differential-fuzz oracle; boundary outside
/// the loaded set is DARK, matching the zero-stage job's world model).
/// Mutates each LoadedChunk.light in place. The corpus baseline must be
/// a CONSISTENT fixed point: OPT-ALG-001's carried entries are sound on
/// consistent baselines, and production baselines (Java-mirrored light)
/// are consistent by construction. Call once after loading, before any
/// priming; the corpus hash then covers the SOLVED light.
/// Re-solve block light to the fixed point of the corpus table over the
/// loaded region (Jacobi relaxation on a dense grid — algorithm-
/// independent, mirrors the differential-fuzz oracle; the boundary
/// outside the loaded set is DARK, matching the zero-stage job's world
/// model). Mutates each LoadedChunk.light in place. The corpus baseline
/// must be a CONSISTENT fixed point: OPT-ALG-001's carried entries are
/// sound on consistent baselines, and production baselines
/// (Java-mirrored light) are consistent by construction. Call once
/// after loading, before any priming; the corpus hash then covers the
/// SOLVED light.
pub fn solve_baseline_light(loaded: &mut [LoadedChunk], table: &[u8]) {
    if loaded.is_empty() {
        return;
    }
    let mut minx = i32::MAX;
    let mut maxx = i32::MIN;
    let mut minz = i32::MAX;
    let mut maxz = i32::MIN;
    for lc in loaded.iter() {
        minx = minx.min(lc.cx * 16);
        maxx = maxx.max(lc.cx * 16 + 15);
        minz = minz.min(lc.cz * 16);
        maxz = maxz.max(lc.cz * 16 + 15);
    }
    let nx = (maxx - minx + 1) as usize;
    let nz = (maxz - minz + 1) as usize;
    let w = nx * nz;
    let idx =
        |x: i32, y: i32, z: i32| -> usize { (y as usize) * w + (z as usize) * nx + (x as usize) };
    let mut opacity = vec![0u8; w * 256];
    let mut emission = vec![0u8; w * 256];
    let mut light = vec![0u8; w * 256];
    for lc in loaded.iter() {
        for y in 0..256usize {
            for z in 0..16usize {
                for x in 0..16usize {
                    let pidx = (x << 12) | (z << 8) | y;
                    let st = lc.primer[pidx];
                    if st == 0 {
                        continue;
                    }
                    let gx = (lc.cx * 16 + x as i32 - minx) as i32;
                    let gz = (lc.cz * 16 + z as i32 - minz) as i32;
                    let gi = idx(gx, y as i32, gz);
                    let t = st as usize * 3;
                    if t + 2 < table.len() && table[t] == 1 {
                        opacity[gi] = table[t + 1];
                        emission[gi] = table[t + 2];
                    }
                }
            }
        }
    }
    for (i, &e) in emission.iter().enumerate() {
        light[i] = e;
    }
    let nb = |x: i32, y: i32, z: i32| -> bool {
        x >= 0 && (x as usize) < nx && y >= 0 && y < 256 && z >= 0 && (z as usize) < nz
    };
    let mut rounds = 0u32;
    loop {
        rounds += 1;
        assert!(rounds < 10_000, "baseline solve did not converge");
        let mut next = light.clone();
        let mut changed = false;
        for y in 0..256i32 {
            for z in 0..nz as i32 {
                for x in 0..nx as i32 {
                    let gi = idx(x, y, z);
                    let em = emission[gi];
                    let op = opacity[gi];
                    let att = if op >= 15 && em > 0 { 1 } else { op.max(1) };
                    let mut target = em;
                    if att < 15 {
                        for d in [
                            (1i32, 0, 0),
                            (-1, 0, 0),
                            (0, 1, 0),
                            (0, -1, 0),
                            (0, 0, 1),
                            (0, 0, -1),
                        ] {
                            let (qx, qy, qz) = (x + d.0, y + d.1, z + d.2);
                            if !nb(qx, qy, qz) {
                                continue;
                            }
                            let v = light[idx(qx, qy, qz)].saturating_sub(att);
                            if v > target {
                                target = v;
                            }
                        }
                    }
                    if target > 15 {
                        target = 15;
                    }
                    if target != light[gi] {
                        next[gi] = target;
                        changed = true;
                    }
                }
            }
        }
        light = next;
        if !changed {
            break;
        }
    }
    for lc in loaded.iter_mut() {
        for y in 0..256usize {
            for z in 0..16usize {
                for x in 0..16usize {
                    let gx = (lc.cx * 16 + x as i32 - minx) as i32;
                    let gz = (lc.cz * 16 + z as i32 - minz) as i32;
                    let pidx = (x << 12) | (z << 8) | y;
                    lc.light[pidx] = light[idx(gx, y as i32, gz)];
                }
            }
        }
    }
}

pub fn prime_into_registry(lc: &LoadedChunk, dim: i32) {
    let reg = get_registry();
    // remove any stale instance then insert fresh with primer states
    let key = ChunkKey::new(dim, lc.cx, lc.cz);
    let gen = reg.next_generation_id();
    let mut primer_arr = [0u32; 65536];
    primer_arr.copy_from_slice(&lc.primer);
    let nc = NativeChunk::from_primer(dim, lc.cx, lc.cz, &primer_arr, &[0u8; 256], gen);
    reg.insert(nc);
    // push per-section block light (from_primer zeroes light)
    for sy in 0..16usize {
        let mut bl = [0u8; 2048];
        let mut any = false;
        for sy16 in 0..16usize {
            for z in 0..16usize {
                for x in 0..16usize {
                    let y_abs = sy * 16 + sy16;
                    let pidx = (x << 12) | (z << 8) | y_abs;
                    let v = lc.light[pidx];
                    if v != 0 {
                        any = true;
                        let idx = (sy16 << 8) | (z << 4) | x;
                        bl[idx >> 1] |= if idx & 1 == 0 {
                            v & 0x0F
                        } else {
                            (v & 0x0F) << 4
                        };
                    }
                }
            }
        }
        if any {
            let mut states = [0u32; 4096];
            for sy16 in 0..16usize {
                for z in 0..16usize {
                    for x in 0..16usize {
                        let y_abs = sy * 16 + sy16;
                        let pidx = (x << 12) | (z << 8) | y_abs;
                        let idx = (sy16 << 8) | (z << 4) | x;
                        states[idx] = lc.primer[pidx];
                    }
                }
            }
            reg.refresh_section(key, sy as u8, &states, Some(&bl), None);
        }
    }
}
