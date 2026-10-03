//! LIVE in-server region write engine (RUST_REGION_WRITE_AUTHORITY).
//!
//! Contract derived from the vanilla seam (`RegionFile.func_76706_a`, entered
//! from `ChunkBuffer.close` with the chunk's local x/z and the raw DEFLATE
//! stream): the 4-byte BE length prefix and the compression-type byte (2) are
//! written by the ENGINE, not by the caller. The payload is
//! OPAQUE_FINAL_REGION_PAYLOAD — never parsed here.
//!
//! Coherency model (task §15/16): the engine holds its own sector map loaded
//! from the file at open. All admitted writes go through the engine (Java's
//! vanilla body is skipped), and vanilla FALLBACK writes are reported back
//! via `note_external_write` so the map never goes stale. The Java hook then
//! mirrors the new location entry into RegionFile's in-memory arrays, keeping
//! them canonical for in-session reads.
//!
//! Crash ordering matches vanilla: payload sectors are written BEFORE the
//! location entry; a crash mid-write leaves the old location pointing at old
//! sectors unless the same-size in-place fast path was used (same guarantee
//! vanilla itself provides).

use std::collections::HashMap;
use std::fs::{self, OpenOptions};
use std::io::{Read, Seek, SeekFrom, Write};
use std::path::{Path, PathBuf};
use std::sync::{Mutex, OnceLock};

use super::{HEADER_BYTES, LOCATION_ENTRIES, MAX_PAYLOAD_BYTES, SECTOR_BYTES};

pub const STATUS_SUCCESS: i32 = 0;
pub const STATUS_STALE_GENERATION: i32 = 1;
pub const STATUS_INVALID_RECORD: i32 = 2;
pub const STATUS_IO_ERROR: i32 = 3;
pub const STATUS_CAPACITY_ERROR: i32 = 4;
pub const STATUS_NOT_ELIGIBLE: i32 = 5;
pub const STATUS_BAD_HANDLE: i32 = 6;

/// Diagnostic capture at the exact CAPACITY_ERROR return sites:
/// (needed, used_len_at_return, extra_or_shortfall, extra_or_shortfall).
pub static DEBUG_CAP_LOCK: std::sync::Mutex<Option<(usize, usize, usize, usize)>> =
    std::sync::Mutex::new(None);

#[derive(Debug, Default, Clone)]
pub struct WriteStats {
    pub rust_selected: u64,
    pub success: u64,
    pub stale_rejected: u64,
    pub failures: u64,
    pub external_syncs: u64,
    pub in_place_reuses: u64,
    pub bytes_written: u64,
    /// verify-before-free found the on-disk entry diverged from the engine's
    /// belief: an uncoordinated writer touched this file (goal §10/§11)
    pub external_write_detected: u64,
}

pub struct LiveRegionFile {
    path: PathBuf,
    file: Mutex<fs::File>,
    inner: Mutex<Inner>,
    pub stats: Mutex<WriteStats>,
}

struct Inner {
    /// sector index -> used
    used: Vec<bool>,
    /// per-chunk (index) committed generation
    generations: Vec<u64>,
    /// per-chunk (index) committed run as the raw location entry
    /// ((offsetSector << 8) | count), 0 = none
    runs: Vec<u32>,
    /// file length in bytes
    len: usize,
    /// goal §11 contamination state: an uncoordinated writer was observed on
    /// this file — authority is refused for the rest of the session (no
    /// automatic re-promotion)
    disqualified: bool,
}

impl LiveRegionFile {
    /// Open (or create) the live region file and load its sector map from
    /// the CURRENT on-disk location table (the file may pre-exist with
    /// thousands of chunks written by vanilla).
    pub fn open(path: &Path) -> Result<Self, String> {
        if let Some(parent) = path.parent() {
            fs::create_dir_all(parent).map_err(|e| e.to_string())?;
        }
        // create(true) WITHOUT truncate: the file usually pre-exists with
        // thousands of vanilla chunks — truncating would destroy the world
        #[allow(clippy::suspicious_open_options)]
        let mut file = OpenOptions::new()
            .read(true)
            .write(true)
            .create(true)
            .open(path)
            .map_err(|e| e.to_string())?;
        let len = file.metadata().map_err(|e| e.to_string())?.len() as usize;
        let data_len = len.max(HEADER_BYTES);
        if len < HEADER_BYTES {
            file.seek(SeekFrom::Start(0)).map_err(|e| e.to_string())?;
            file.write_all(&vec![0u8; HEADER_BYTES - len])
                .map_err(|e| e.to_string())?;
        }
        let total_sectors = data_len.div_ceil(SECTOR_BYTES);
        let mut used = vec![false; total_sectors];
        used[0] = true;
        used[1] = true;
        let mut header = vec![0u8; HEADER_BYTES];
        file.seek(SeekFrom::Start(0)).map_err(|e| e.to_string())?;
        file.read_exact(&mut header).map_err(|e| e.to_string())?;
        let mut runs = vec![0u32; LOCATION_ENTRIES];
        for index in 0..LOCATION_ENTRIES {
            let b = &header[index * 4..index * 4 + 4];
            let entry = u32::from_be_bytes([b[0], b[1], b[2], b[3]]);
            runs[index] = entry;
            let sectors = (entry & 0xFF) as usize;
            let offset = (entry >> 8) as usize;
            if offset == 0 || sectors == 0 {
                continue;
            }
            for s in 0..sectors {
                let sector = offset + s;
                if sector < used.len() {
                    used[sector] = true;
                }
            }
        }
        Ok(Self {
            path: path.to_path_buf(),
            file: Mutex::new(file),
            inner: Mutex::new(Inner {
                used,
                generations: vec![0; LOCATION_ENTRIES],
                runs,
                len: data_len,
                disqualified: false,
            }),
            stats: Mutex::new(WriteStats::default()),
        })
    }

    pub fn path(&self) -> &Path {
        &self.path
    }

    /// goal §11 contamination state (true = an uncoordinated writer was
    /// observed and authority is refused for the session).
    pub fn disqualified(&self) -> bool {
        match self.inner.lock() {
            Ok(inner) => inner.disqualified,
            Err(_) => true, // poisoned: fail closed
        }
    }

    /// Serializes a closure against this engine's writes: the offline
    /// counterpart of the RegionFile monitor that serializes the Java hooks
    /// (both region seams are synchronized, so in production every read and
    /// write of one file already holds that monitor). A live reader attached
    /// to the same engine takes this lock around its disk reads, so a read
    /// observes exactly one committed generation — never a torn mix.
    pub fn read_lock<R>(&self, f: impl FnOnce() -> R) -> Option<R> {
        match self.inner.lock() {
            Ok(_guard) => Some(f()),
            Err(_) => None, // poisoned: reader must fail closed
        }
    }

    /// Diagnostic: (used-map sector count, trailing free run, entries whose
    /// committed run extends beyond the used map). Used by the capacity
    /// fuzz to characterize the small-payload CAPACITY_ERROR state.
    pub fn debug_capacity_state(&self) -> (usize, usize, usize) {
        let inner = match self.inner.lock() {
            Ok(i) => i,
            Err(_) => return (0, 0, 0),
        };
        let tail_free = inner.used.iter().rev().take_while(|&&u| !u).count();
        let beyond = inner
            .runs
            .iter()
            .filter(|&&e| e != 0 && ((e >> 8) as usize + (e & 0xFF) as usize) > inner.used.len())
            .count();
        (inner.used.len(), tail_free, beyond)
    }

    /// Committed generation floors for all 1024 chunks. The Java hook seeds
    /// its per-RegionFile ticket counters from this so a closed-and-reopened
    /// RegionFile instance never re-issues tickets the engine has already
    /// committed (which would all be rejected as STALE forever).
    pub fn generations(&self) -> Vec<u64> {
        match self.inner.lock() {
            Ok(inner) => inner.generations.clone(),
            Err(_) => vec![0; LOCATION_ENTRIES],
        }
    }

    /// Current sector used-map as 0/1 bytes, one per tracked sector. The
    /// Java hook rebuilds RegionFile's in-memory free list
    /// (field_76714_f, `true` = FREE) from this after every engine event:
    /// vanilla's READ path bounds-checks entries against that list's size()
    /// and vanilla's own fallback allocator allocates from it, so a Rust
    /// write that grows the file or occupies sectors must be mirrored or
    /// in-session reads return null and fallback writes clobber Rust records.
    pub fn used_map(&self) -> Vec<u8> {
        match self.inner.lock() {
            Ok(inner) => inner.used.iter().map(|&b| b as u8).collect(),
            Err(_) => Vec::new(),
        }
    }

    /// Record a VANILLA fallback write so the engine's map stays coherent
    /// (task §34 mixed-write stress). `entry` is the raw location entry Java
    /// holds after its own write; `generation` is the Java ticket counter's
    /// new value for this chunk. Java's counter is the SINGLE SOURCE OF
    /// TRUTH for generations — the engine never invents or advances tickets
    /// itself, so the two sides cannot diverge into false STALE rejections.
    pub fn note_external_write(
        &self,
        x: u8,
        z: u8,
        entry: u32,
        generation: u64,
    ) -> Result<(), String> {
        let mut inner = self.inner.lock().map_err(|_| "map poisoned")?;
        let index = z as usize * 32 + x as usize;
        // full resync from the file: vanilla fallback writes may have grown or
        // relocated sectors, and per-entry delta tracking is not exact
        let Inner { used, runs, .. } = &mut *inner;
        Self::resync_state(used, &mut Some(runs), &self.path).map_err(|e| e.to_string())?;
        // the file may have grown beyond what resync saw if the fallback write
        // extended the tail: extend len so find_run growth math stays right
        let end = (entry >> 8) as usize + (entry & 0xFF) as usize;
        if end * SECTOR_BYTES > inner.len {
            inner.len = end * SECTOR_BYTES;
        }
        inner.runs[index] = entry;
        inner.generations[index] = generation;
        drop(inner);
        if let Ok(mut stats) = self.stats.lock() {
            stats.external_syncs = stats.external_syncs.wrapping_add(1);
        }
        Ok(())
    }

    /// Admitted write: allocate + physically write + report the new entry.
    /// `generation` must exceed the last committed generation for this chunk;
    /// otherwise STALE_GENERATION and the file is untouched.
    pub fn write_chunk(
        &self,
        x: u8,
        z: u8,
        payload: &[u8],
        generation: u64,
    ) -> Result<(u32, u32), i32> {
        if payload.len() as u64 + 5 > MAX_PAYLOAD_BYTES as u64 + 5 {
            return Err(STATUS_CAPACITY_ERROR);
        }
        let index = z as usize * 32 + x as usize;
        let needed = (payload.len() + 5).div_ceil(SECTOR_BYTES).max(1);

        // fast path validation + allocation under the inner lock
        let (start, in_place) = {
            let mut inner = self.inner.lock().map_err(|_| STATUS_IO_ERROR)?;
            if inner.disqualified {
                // goal §11: fail closed for the session, no re-promotion
                if let Ok(mut stats) = self.stats.lock() {
                    stats.failures = stats.failures.wrapping_add(1);
                }
                return Err(STATUS_NOT_ELIGIBLE);
            }
            if generation <= inner.generations[index] {
                if let Ok(mut stats) = self.stats.lock() {
                    stats.stale_rejected = stats.stale_rejected.wrapping_add(1);
                }
                return Err(STATUS_STALE_GENERATION);
            }
            // goal §10/§12 VERIFY-BEFORE-FREE: the engine is about to act on
            // its belief about this chunk's run. A writer that bypasses the
            // coordinated seam (note_external_write / the Java hook) would
            // have moved the on-disk entry out from under us — detect it
            // BEFORE the stale belief can free live sectors, resync, and
            // disqualify this file for the session (fail-closed exclusion;
            // post-hoc file watching alone cannot close the race, so the
            // region is excluded from authority instead).
            let claimed = inner.runs[index];
            if claimed != 0 {
                let mut on_disk = [0u8; 4];
                {
                    let mut f = self.file.lock().map_err(|_| STATUS_IO_ERROR)?;
                    f.seek(SeekFrom::Start((index * 4) as u64))
                        .map_err(|_| STATUS_IO_ERROR)?;
                    f.read_exact(&mut on_disk).map_err(|_| STATUS_IO_ERROR)?;
                }
                let disk_entry =
                    u32::from_be_bytes([on_disk[0], on_disk[1], on_disk[2], on_disk[3]]);
                if disk_entry != claimed {
                    if let Ok(mut stats) = self.stats.lock() {
                        stats.external_write_detected =
                            stats.external_write_detected.wrapping_add(1);
                    }
                    let Inner { used, runs, .. } = &mut *inner;
                    Self::resync_state(used, &mut Some(runs), &self.path)?;
                    inner.disqualified = true;
                    if let Ok(mut stats) = self.stats.lock() {
                        stats.failures = stats.failures.wrapping_add(1);
                    }
                    return Err(STATUS_NOT_ELIGIBLE);
                }
            }
            // Free the chunk's previous run BEFORE allocating: without this,
            // every rewrite leaks its old sectors and the file grows without
            // bound (caught by the live tests).
            let old_entry = inner.runs[index];
            let old_sectors = (old_entry & 0xFF) as usize;
            let old_off = (old_entry >> 8) as usize;
            if old_off != 0 && old_sectors != 0 {
                for s in 0..old_sectors {
                    let sector = old_off + s;
                    if sector < inner.used.len() {
                        inner.used[sector] = false;
                    }
                }
            }
            // find_run first-fit, skipping header sectors
            let mut start = None;
            let mut run = 0usize;
            for sector in 2..inner.used.len() {
                if inner.used[sector] {
                    run = 0;
                } else {
                    run += 1;
                    if run == needed {
                        start = Some(sector + 1 - needed);
                        break;
                    }
                }
            }
            let start = match start {
                Some(s) => s,
                None => {
                    // grow by the shortfall of the TRAILING free run: only
                    // trailing contiguous free sectors merge with the newly
                    // added ones, so growing by "free anywhere in the window"
                    // under-grows and the post-growth scan fails (the
                    // small-payload CAPACITY_ERROR caught by the capacity
                    // fuzz — the source of every campaign err4 event).
                    let current = inner.used.len();
                    let trailing_free = inner.used.iter().rev().take_while(|&&u| !u).count();
                    let extra = needed.saturating_sub(trailing_free);
                    if extra == 0 {
                        // unreachable: a trailing run of `needed` would have
                        // been found by the first-fit scan above
                        *DEBUG_CAP_LOCK.lock().unwrap() = Some((needed, current, 0, extra));
                        return Err(STATUS_CAPACITY_ERROR);
                    }
                    let new_len = current + extra;
                    inner.used.resize(new_len, false);
                    inner.len = new_len * SECTOR_BYTES;
                    let mut run = 0usize;
                    let mut found = None;
                    for sector in 2..inner.used.len() {
                        if inner.used[sector] {
                            run = 0;
                        } else {
                            run += 1;
                            if run == needed {
                                found = Some(sector + 1 - needed);
                                break;
                            }
                        }
                    }
                    found.ok_or({
                        if found.is_none() {
                            *DEBUG_CAP_LOCK.lock().unwrap() =
                                Some((needed, inner.used.len(), extra, extra));
                        }
                        STATUS_CAPACITY_ERROR
                    })?
                }
            };
            (start, old_off != 0 && start == old_off)
        };

        // physical write: payload sectors FIRST, then header entry (vanilla order)
        let write_result = (|| -> Result<(), i32> {
            let mut f = self.file.lock().map_err(|_| STATUS_IO_ERROR)?;
            let offset = (start * SECTOR_BYTES) as u64;
            f.seek(SeekFrom::Start(offset))
                .map_err(|_| STATUS_IO_ERROR)?;
            let total = (payload.len() + 1) as u32;
            f.write_all(&total.to_be_bytes())
                .map_err(|_| STATUS_IO_ERROR)?;
            f.write_all(&[2u8]).map_err(|_| STATUS_IO_ERROR)?;
            f.write_all(payload).map_err(|_| STATUS_IO_ERROR)?;
            // pad the final sector
            let written = payload.len() + 5;
            let pad = needed * SECTOR_BYTES - written;
            if pad > 0 {
                f.write_all(&vec![0u8; pad]).map_err(|_| STATUS_IO_ERROR)?;
            }
            // location entry (sector 0) + timestamp (sector 1)
            let entry = ((start as u32) << 8) | (needed as u32 & 0xFF);
            f.seek(SeekFrom::Start((index * 4) as u64))
                .map_err(|_| STATUS_IO_ERROR)?;
            f.write_all(&entry.to_be_bytes())
                .map_err(|_| STATUS_IO_ERROR)?;
            let now = std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .map(|d| d.as_secs() as u32)
                .unwrap_or(0);
            // The timestamp table lives in the SECOND header sector
            // (4096..8192): HEADER_BYTES/2 + index*4 — NOT at HEADER_BYTES,
            // which is where chunk payloads start.
            f.seek(SeekFrom::Start((HEADER_BYTES / 2 + index * 4) as u64))
                .map_err(|_| STATUS_IO_ERROR)?;
            f.write_all(&now.to_be_bytes())
                .map_err(|_| STATUS_IO_ERROR)?;
            f.flush().map_err(|_| STATUS_IO_ERROR)?;
            Ok(())
        })();

        let mut inner = self.inner.lock().map_err(|_| STATUS_IO_ERROR)?;
        if write_result.is_err() {
            // Full resync: the old run was already freed before allocation and
            // the location entry was never updated, so the disk state is the
            // only authority for what is live.
            Self::resync_used(&mut inner.used, &self.path)?;
            return Err(match write_result {
                Err(code) => code,
                Ok(()) => STATUS_IO_ERROR,
            });
        }
        for s in 0..needed {
            if start + s < inner.used.len() {
                inner.used[start + s] = true;
            }
        }
        inner.runs[index] = ((start as u32) << 8) | (needed as u32 & 0xFF);
        inner.generations[index] = generation;
        if inner.len < (start + needed) * SECTOR_BYTES {
            inner.len = (start + needed) * SECTOR_BYTES;
        }
        drop(inner);
        if let Ok(mut stats) = self.stats.lock() {
            stats.rust_selected = stats.rust_selected.wrapping_add(1);
            stats.success = stats.success.wrapping_add(1);
            stats.bytes_written = stats.bytes_written.wrapping_add(payload.len() as u64 + 5);
            if in_place {
                stats.in_place_reuses = stats.in_place_reuses.wrapping_add(1);
            }
        }
        let entry = ((start as u32) << 8) | (needed as u32 & 0xFF);
        Ok((entry, needed as u32))
    }

    /// Fallback notice: a vanilla write completed for this chunk. Rust keeps
    /// its map coherent by re-reading the location entry from disk; the Java
    /// hook passes the new ticket value it committed for this chunk.
    pub fn note_vanilla_fallback(&self, x: u8, z: u8, generation: u64) -> Result<(), i32> {
        let index = z as usize * 32 + x as usize;
        let mut header = [0u8; 4];
        {
            let mut f = self.file.lock().map_err(|_| STATUS_IO_ERROR)?;
            f.seek(SeekFrom::Start((index * 4) as u64))
                .map_err(|_| STATUS_IO_ERROR)?;
            f.read_exact(&mut header).map_err(|_| STATUS_IO_ERROR)?;
        }
        let entry = u32::from_be_bytes(header);
        if entry == 0 {
            return Err(STATUS_IO_ERROR);
        }
        let mut inner = self.inner.lock().map_err(|_| STATUS_IO_ERROR)?;
        // everything else that we believed used but is not in the new run and
        // not referenced by any entry cannot be derived cheaply; full resync:
        {
            let Inner { used, runs, .. } = &mut *inner;
            Self::resync_state(used, &mut Some(runs), &self.path)?;
        }
        let end = (entry >> 8) as usize + (entry & 0xFF) as usize;
        if end * SECTOR_BYTES > inner.len {
            inner.len = end * SECTOR_BYTES;
        }
        inner.generations[index] = generation;
        drop(inner);
        if let Ok(mut stats) = self.stats.lock() {
            stats.external_syncs = stats.external_syncs.wrapping_add(1);
        }
        Ok(())
    }

    pub(crate) fn resync_used(used: &mut Vec<bool>, path: &Path) -> Result<(), i32> {
        let mut runs = None;
        Self::resync_state(used, &mut runs, path)
    }

    pub(crate) fn resync_state(
        used: &mut Vec<bool>,
        runs: &mut Option<&mut Vec<u32>>,
        path: &Path,
    ) -> Result<(), i32> {
        let mut f = fs::File::open(path).map_err(|_| STATUS_IO_ERROR)?;
        let len = f.metadata().map_err(|_| STATUS_IO_ERROR)?.len() as usize;
        let mut header = vec![0u8; HEADER_BYTES];
        f.read_exact(&mut header).map_err(|_| STATUS_IO_ERROR)?;
        let total = len.div_ceil(SECTOR_BYTES);
        let mut fresh = vec![false; total];
        fresh[0] = true;
        fresh[1] = true;
        let mut fresh_runs = vec![0u32; LOCATION_ENTRIES];
        for index in 0..LOCATION_ENTRIES {
            let entry = u32::from_be_bytes([
                header[index * 4],
                header[index * 4 + 1],
                header[index * 4 + 2],
                header[index * 4 + 3],
            ]);
            fresh_runs[index] = entry;
            let sectors = (entry & 0xFF) as usize;
            let offset = (entry >> 8) as usize;
            if offset == 0 || sectors == 0 {
                continue;
            }
            for s in 0..sectors {
                let sector = offset + s;
                if sector < fresh.len() {
                    fresh[sector] = true;
                }
            }
        }
        *used = fresh;
        if let Some(r) = runs.as_deref_mut() {
            r.copy_from_slice(&fresh_runs);
        }
        Ok(())
    }
}

/// Handle registry keyed by canonical path (one engine per live region file).
pub struct EngineRegistry {
    engines: Mutex<HashMap<PathBuf, std::sync::Arc<LiveRegionFile>>>,
}

impl Default for EngineRegistry {
    fn default() -> Self {
        Self {
            engines: Mutex::new(HashMap::new()),
        }
    }
}

impl EngineRegistry {
    pub fn new() -> Self {
        Self::default()
    }

    /// Process-wide registry: exactly one engine per live region file path.
    pub fn global() -> &'static EngineRegistry {
        static REGISTRY: OnceLock<EngineRegistry> = OnceLock::new();
        REGISTRY.get_or_init(EngineRegistry::new)
    }

    pub fn get_or_open(&self, path: &Path) -> Result<std::sync::Arc<LiveRegionFile>, String> {
        let canonical = normalize_key(path);
        let mut engines = self.engines.lock().map_err(|_| "registry poisoned")?;
        if let Some(engine) = engines.get(&canonical) {
            return Ok(std::sync::Arc::clone(engine));
        }
        let engine = std::sync::Arc::new(LiveRegionFile::open(&canonical)?);
        engines.insert(canonical, std::sync::Arc::clone(&engine));
        Ok(engine)
    }

    pub fn remove(&self, path: &Path) {
        let canonical = normalize_key(path);
        if let Ok(mut engines) = self.engines.lock() {
            engines.remove(&canonical);
        }
    }

    /// Existing engine for a path, if one is registered. Unlike get_or_open
    /// this never creates an engine (read-only processes must not).
    pub fn try_get(&self, path: &Path) -> Option<std::sync::Arc<LiveRegionFile>> {
        let canonical = normalize_key(path);
        let engines = self.engines.lock().ok()?;
        engines.get(&canonical).map(std::sync::Arc::clone)
    }
}

/// Registry key normalization. `Path::canonicalize` is asymmetric on Windows:
/// it fails on a not-yet-existing file (first open) and succeeds with a
/// `\\?\`-prefixed path afterwards (second open) — the same file would get
/// two engines. Canonicalize the PARENT (always exists after open's
/// create_dir_all) and join the file name; case-fold on Windows where the
/// filesystem is case-insensitive.
fn normalize_key(path: &Path) -> PathBuf {
    let parent = path.parent().unwrap_or_else(|| Path::new("."));
    let name = path
        .file_name()
        .map(|n| n.to_os_string())
        .unwrap_or_default();
    match parent.canonicalize() {
        Ok(p) => {
            let joined = p.join(name);
            #[cfg(windows)]
            {
                let s = joined.to_string_lossy().to_lowercase();
                PathBuf::from(s)
            }
            #[cfg(not(windows))]
            joined
        }
        Err(_) => path.to_path_buf(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Arc;

    fn temp_region(name: &str) -> PathBuf {
        let dir = std::env::temp_dir().join("regionio-live-test");
        fs::create_dir_all(&dir).unwrap();
        let path = dir.join(name);
        let _ = fs::remove_file(&path);
        path
    }

    fn deflate_frame(payload: &[u8]) -> Vec<u8> {
        // the FFI payload is the raw deflate stream; the engine adds the
        // length+type framing. For tests, just use raw bytes as the stream.
        payload.to_vec()
    }

    #[test]
    fn write_readback_generation_order() {
        let path = temp_region("live-r.0.0.mca");
        let engine = LiveRegionFile::open(&path).unwrap();
        let p1 = deflate_frame(&[1u8; 5000]);
        let (entry1, _) = engine.write_chunk(0, 0, &p1, 1).unwrap();
        assert!(entry1 >> 8 >= 2);
        let mut f = fs::File::open(&path).unwrap();
        let sector_count = (entry1 & 0xFF) as usize;
        let mut sector = vec![0u8; SECTOR_BYTES * sector_count];
        f.seek(SeekFrom::Start(
            ((entry1 >> 8) as u64) * SECTOR_BYTES as u64,
        ))
        .unwrap();
        f.read_exact(&mut sector).unwrap();
        let total = u32::from_be_bytes([sector[0], sector[1], sector[2], sector[3]]) as usize;
        assert_eq!(total, p1.len() + 1);
        assert_eq!(sector[4], 2);
        // `total` counts the type byte; the payload therefore spans
        // [5, 4 + total) inside the sector.
        assert_eq!(&sector[5..total + 4], &p1[..]);
        // stale generation rejected
        assert_eq!(
            engine.write_chunk(0, 0, &p1, 1).unwrap_err(),
            STATUS_STALE_GENERATION
        );
        // fresh generation accepted; the engine may relocate the run, so
        // read back through the RETURNED entry (the new location).
        let p2 = deflate_frame(&[2u8; 3000]);
        let (entry2, _) = engine.write_chunk(0, 0, &p2, 2).unwrap();
        let mut f2 = fs::File::open(&path).unwrap();
        f2.seek(SeekFrom::Start(0)).unwrap();
        let mut header = vec![0u8; HEADER_BYTES];
        f2.read_exact(&mut header).unwrap();
        let on_disk = u32::from_be_bytes([header[0], header[1], header[2], header[3]]);
        assert_eq!(
            on_disk, entry2,
            "on-disk location must match the returned entry"
        );
        let entry = entry2;
        let off = (entry >> 8) as usize * SECTOR_BYTES;
        f2.seek(SeekFrom::Start(off as u64)).unwrap();
        let mut s2 = vec![0u8; SECTOR_BYTES * (entry & 0xFF) as usize];
        f2.read_exact(&mut s2).unwrap();
        let t = u32::from_be_bytes([s2[0], s2[1], s2[2], s2[3]]) as usize;
        assert_eq!(&s2[5..t + 4], &p2[..]);
    }

    #[test]
    fn fallback_resync_then_rust_write() {
        let path = temp_region("live-r.1.1.mca");
        let engine = LiveRegionFile::open(&path).unwrap();
        // vanilla-style fallback write directly to the file (simulated)
        let payload = [9u8; 2048];
        let mut f = fs::OpenOptions::new()
            .read(true)
            .write(true)
            .open(&path)
            .unwrap();
        f.seek(SeekFrom::Start(8192)).unwrap();
        let total = (payload.len() + 1) as u32;
        f.write_all(&total.to_be_bytes()).unwrap();
        f.write_all(&[2u8]).unwrap();
        f.write_all(&payload).unwrap();
        let entry = (2u32 << 8) | 1;
        f.seek(SeekFrom::Start(0)).unwrap();
        f.write_all(&entry.to_be_bytes()).unwrap();
        drop(f);
        // tell the engine; its map must now treat sector 2 as used. The
        // simulated fallback above wrote chunk index 0 == chunk (0, 0); the
        // Java ticket counter for that chunk advanced to 1.
        engine.note_vanilla_fallback(0, 0, 1).unwrap();
        // A DIFFERENT chunk's Rust write must not land on sector 2 (the
        // fallback chunk's run). Rewriting (0,0) itself MAY reuse it in place
        // — that is vanilla's own behavior — so exercise the other chunk.
        let p = deflate_frame(&[7u8; 100]);
        let (entry_other, _) = engine.write_chunk(1, 0, &p, 1).unwrap();
        assert!(
            entry_other >> 8 != 2,
            "Rust must not overwrite the fallback chunk"
        );
        // in-place rewrite of the fallback chunk is legitimate reuse
        let (e_self, _) = engine.write_chunk(0, 0, &p, 2).unwrap();
        assert_eq!(
            e_self >> 8,
            2,
            "same-chunk rewrite should reuse its own run in place"
        );
    }

    #[test]
    fn rewrite_loop_does_not_leak_sectors() {
        let path = temp_region("live-r.2.2.mca");
        let engine = LiveRegionFile::open(&path).unwrap();
        let p = deflate_frame(&[3u8; 4096]); // exactly one sector of payload
        let mut last_entry = 0u32;
        for gen in 1..=200u64 {
            let (entry, _) = engine.write_chunk(0, 0, &p, gen).unwrap();
            last_entry = entry;
        }
        // 200 rewrites of the same chunk: file must stay at header + one run
        let len = fs::metadata(&path).unwrap().len();
        let run_sectors = (last_entry & 0xFF) as u64;
        let expected = ((last_entry >> 8) as u64 + run_sectors) * SECTOR_BYTES as u64;
        assert_eq!(
            len,
            expected.max(HEADER_BYTES as u64),
            "file grew across rewrites (run leak)"
        );
    }

    #[test]
    fn same_size_rewrite_reuses_run() {
        let path = temp_region("live-r.3.3.mca");
        let engine = LiveRegionFile::open(&path).unwrap();
        let p = deflate_frame(&[5u8; 3000]);
        let (e1, _) = engine.write_chunk(0, 0, &p, 1).unwrap();
        let (e2, _) = engine.write_chunk(0, 0, &p, 2).unwrap();
        assert_eq!(e1, e2, "same-size rewrite must reuse the run in place");
        // a different chunk fits into nothing new: file must not have grown
        let len = fs::metadata(&path).unwrap().len();
        assert_eq!(len, ((e1 >> 8) as u64 + 1) * SECTOR_BYTES as u64);
    }

    #[test]
    fn registry_shares_engine_across_first_and_second_open() {
        // Windows canonicalize asymmetry (nonexistent vs existing file) must
        // not yield two engines for one file: the second attach must see the
        // first attach's committed generation floors.
        let path = temp_region("live-r.4.4.mca");
        let e1 = EngineRegistry::global().get_or_open(&path).unwrap();
        e1.write_chunk(0, 0, &deflate_frame(&[1u8; 100]), 1)
            .unwrap();
        let e2 = EngineRegistry::global().get_or_open(&path).unwrap();
        assert!(
            Arc::ptr_eq(&e1, &e2),
            "registry returned two engines for one file"
        );
        assert_eq!(e2.generations()[0], 1, "floors must be shared");
        EngineRegistry::global().remove(&path);
    }
}
