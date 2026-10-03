//! Anvil format (.mca) region persistence engine: reader, writer, and
//! integrity scanner implementing the EXACT 1.12.2 contract derived from
//! the vanilla bytecode (`RegionFile` / `RegionFileCache` /
//! `AnvilChunkLoader.func_183013_b`):
//!
//! - 8 KiB header: 1024 location entries (4 B: 24-bit sector offset in
//!   4-KiB sectors + 8-bit sector count) then 1024 timestamps (4 B BE).
//! - Chunk payload at its sector offset: 4-byte BE length (excluding
//!   itself and the type byte), 1-byte compression type (1=gzip, 2=zlib),
//!   then `len-1` compressed bytes.
//! - A location entry of 0 means "absent".
//! - Header sectors are never allocated to chunks; allocations start at
//!   sector 2.
//!
//! WORLD DATA IS SACRED: the writer validates its full layout (no
//! overlapping sectors, bounds inside the file) before committing, and
//! commits atomically (temp file + sync + rename).

use std::collections::BTreeMap;
use std::fs;
use std::io::Write;
use std::path::{Path, PathBuf};

pub const SECTOR_BYTES: usize = 4096;
pub const HEADER_BYTES: usize = 8192;
pub const LOCATION_ENTRIES: usize = 1024;
pub const MAX_REGION_BYTES: usize = 16 * 1024 * 1024;
/// Vanilla's own decoder cap for a chunk payload.
pub const MAX_PAYLOAD_BYTES: usize = 1024 * 1024;

pub mod live;
pub use live::{
    EngineRegistry, LiveRegionFile, WriteStats, STATUS_BAD_HANDLE, STATUS_CAPACITY_ERROR,
    STATUS_INVALID_RECORD, STATUS_IO_ERROR, STATUS_NOT_ELIGIBLE, STATUS_STALE_GENERATION,
    STATUS_SUCCESS,
};
pub mod live_read;
pub use live_read::{
    decompress_stream, RegionReader, READ_CORRUPT_ENTRY, READ_IO_ERROR, READ_MAX_DECOMPRESSED,
    READ_MISSING, READ_NOT_ELIGIBLE, READ_OUTPUT_TOO_SMALL, READ_SUCCESS,
    READ_UNSUPPORTED_COMPRESSION,
};

#[derive(Debug)]
pub enum RegionError {
    Io(std::io::Error),
    TooSmall,
    TooLarge,
    BadEntry { index: usize },
    Overlap { first: u32 },
    Decompress(String),
    Compress(String),
    OutOfSectors,
    Nbt(String),
}

impl From<std::io::Error> for RegionError {
    fn from(e: std::io::Error) -> Self {
        RegionError::Io(e)
    }
}

impl core::fmt::Display for RegionError {
    fn fmt(&self, f: &mut core::fmt::Formatter<'_>) -> core::fmt::Result {
        match self {
            RegionError::Io(e) => write!(f, "io: {e}"),
            RegionError::TooSmall => write!(f, "file smaller than the 8 KiB header"),
            RegionError::TooLarge => write!(f, "file exceeds the 16 MiB sanity bound"),
            RegionError::BadEntry { index } => write!(f, "invalid location entry {index}"),
            RegionError::Overlap { first } => write!(f, "overlapping sectors at {first}"),
            RegionError::Decompress(e) => write!(f, "decompress: {e}"),
            RegionError::Compress(e) => write!(f, "compress: {e}"),
            RegionError::OutOfSectors => write!(f, "region out of free sectors"),
            RegionError::Nbt(e) => write!(f, "nbt: {e}"),
        }
    }
}

/// zlib (type 2) inflate with the PROVEN single-call bounded pattern
/// (fixed output buffer, strict StreamEnd + total_in + cap checks).
pub fn inflate(compression: u8, input: &[u8], cap: usize) -> Result<Vec<u8>, RegionError> {
    use flate2::{Decompress, FlushDecompress, Status};
    if compression != 2 {
        // type 1 (gzip) is legacy; every live 1.12.2 world uses type 2.
        return Err(RegionError::Decompress(format!(
            "unsupported region compression type {compression}"
        )));
    }
    let mut out = vec![0u8; cap + 1];
    let mut decoder = Decompress::new(true);
    let status = decoder
        .decompress(input, &mut out, FlushDecompress::Finish)
        .map_err(|e| RegionError::Decompress(e.to_string()))?;
    if status != Status::StreamEnd
        || decoder.total_in() != input.len() as u64
        || decoder.total_out() > cap as u64
    {
        return Err(RegionError::Decompress(
            "bounded zlib stream failure (truncated/oversized/trailing)".into(),
        ));
    }
    out.truncate(decoder.total_out() as usize);
    Ok(out)
}

/// zlib-compress (header + adler) at the vanilla default level.
pub fn zlib_compress(input: &[u8], level: u32) -> Result<Vec<u8>, RegionError> {
    use flate2::Compression;
    use std::io::Write as _;
    let mut enc = flate2::write::ZlibEncoder::new(
        Vec::with_capacity(input.len() / 2),
        Compression::new(level),
    );
    enc.write_all(input)
        .map_err(|e| RegionError::Compress(e.to_string()))?;
    enc.finish()
        .map_err(|e| RegionError::Compress(e.to_string()))
}

fn entry_offset(entry: u32) -> Option<usize> {
    let sectors = (entry & 0xFF) as usize;
    let off = (entry >> 8) as usize;
    if off == 0 || sectors == 0 {
        return None;
    }
    Some(off * SECTOR_BYTES)
}

/// One chunk's record from a region file.
pub struct ChunkRecord {
    pub x: u8,
    pub z: u8,
    pub compression: u8,
    /// Exact decompressed payload.
    pub payload: Vec<u8>,
    /// Exact stored bytes: 4-byte BE len + type byte + compressed stream.
    pub raw: Vec<u8>,
}

/// Read one chunk's full record (byte-exact raw + decompressed payload).
pub fn read_chunk(path: &Path, x: u8, z: u8) -> Result<Option<ChunkRecord>, RegionError> {
    let data = fs::read(path)?;
    read_chunk_from(&data, x, z)
}

/// Read one chunk from an in-memory region image.
pub fn read_chunk_from(data: &[u8], x: u8, z: u8) -> Result<Option<ChunkRecord>, RegionError> {
    if data.len() < HEADER_BYTES {
        return Err(RegionError::TooSmall);
    }
    let index = z as usize * 32 + x as usize;
    let b = &data[index * 4..index * 4 + 4];
    let entry = u32::from_be_bytes([b[0], b[1], b[2], b[3]]);
    let Some(offset) = entry_offset(entry) else {
        return Ok(None);
    };
    let sectors = (entry & 0xFF) as usize;
    if offset + sectors * SECTOR_BYTES > data.len() {
        return Err(RegionError::BadEntry { index });
    }
    let chunk = &data[offset..offset + sectors * SECTOR_BYTES];
    if chunk.len() < 5 {
        return Err(RegionError::BadEntry { index });
    }
    let declared = u32::from_be_bytes([chunk[0], chunk[1], chunk[2], chunk[3]]) as usize;
    let compression = chunk[4];
    if declared == 0 || declared > chunk.len() - 4 {
        return Err(RegionError::BadEntry { index });
    }
    let payload = inflate(compression, &chunk[5..4 + declared], MAX_PAYLOAD_BYTES)?;
    let mut raw = Vec::with_capacity(declared + 4);
    raw.extend_from_slice(&chunk[..4 + declared]);
    Ok(Some(ChunkRecord {
        x,
        z,
        compression,
        payload,
        raw,
    }))
}

/// Integrity report for one region file (contract §17).
#[derive(Debug, Default)]
pub struct ScanReport {
    pub path: PathBuf,
    pub file_bytes: u64,
    pub present_chunks: u32,
    pub absent_chunks: u32,
    pub bad_entries: Vec<usize>,
    pub overlapping_sectors: bool,
    pub decompress_failures: Vec<(u8, u8)>,
    pub nbt_parse_failures: Vec<(u8, u8)>,
    pub gzip_chunks: u32,
    pub zlib_chunks: u32,
    pub total_payload_bytes: u64,
}

/// Scan every location entry: bounds, overlap, decompression, NBT validity.
pub fn scan(path: &Path, parse_nbt: bool) -> Result<ScanReport, RegionError> {
    let data = fs::read(path)?;
    scan_from(&data, path.to_path_buf(), parse_nbt)
}

/// Scan an in-memory region image.
pub fn scan_from(data: &[u8], path: PathBuf, parse_nbt: bool) -> Result<ScanReport, RegionError> {
    let mut report = ScanReport {
        path,
        file_bytes: data.len() as u64,
        ..ScanReport::default()
    };
    if data.len() < HEADER_BYTES {
        report.bad_entries.push(usize::MAX);
        return Ok(report);
    }
    let mut occupied: BTreeMap<u32, u32> = BTreeMap::new();
    for index in 0..LOCATION_ENTRIES {
        let b = &data[index * 4..index * 4 + 4];
        let entry = u32::from_be_bytes([b[0], b[1], b[2], b[3]]);
        let Some(offset) = entry_offset(entry) else {
            report.absent_chunks += 1;
            continue;
        };
        report.present_chunks += 1;
        let sectors = (entry & 0xFF) as usize;
        if offset + sectors * SECTOR_BYTES > data.len() || offset < HEADER_BYTES {
            report.bad_entries.push(index);
            continue;
        }
        let start = offset as u32;
        let end = start + sectors as u32;
        for (o2, s2) in occupied.iter() {
            if start < o2 + s2 && *o2 < end {
                report.overlapping_sectors = true;
                break;
            }
        }
        occupied.insert(start, sectors as u32);
        let chunk = &data[offset..offset + sectors * SECTOR_BYTES];
        let declared = u32::from_be_bytes([chunk[0], chunk[1], chunk[2], chunk[3]]) as usize;
        let compression = chunk[4];
        if declared == 0 || declared > chunk.len() - 4 {
            report.bad_entries.push(index);
            continue;
        }
        match compression {
            1 => report.gzip_chunks += 1,
            2 => report.zlib_chunks += 1,
            _ => {
                report.bad_entries.push(index);
                continue;
            }
        }
        let x = (index % 32) as u8;
        let z = (index / 32) as u8;
        match inflate(compression, &chunk[5..4 + declared], MAX_PAYLOAD_BYTES) {
            Ok(payload) => {
                report.total_payload_bytes += payload.len() as u64;
                if parse_nbt && nbt::validate_root_stream(&payload).is_err() {
                    report.nbt_parse_failures.push((x, z));
                }
            }
            Err(_) => report.decompress_failures.push((x, z)),
        }
    }
    Ok(report)
}

/// An open region file image for coordinated writes with exact vanilla layout.
pub struct RegionWriter {
    path: PathBuf,
    data: Vec<u8>,
    locations: [u8; HEADER_BYTES],
    used: Vec<bool>,
}

impl RegionWriter {
    /// Open (or create) a region file image for coordinated writes.
    pub fn open(path: &Path) -> Result<Self, RegionError> {
        let data = if path.exists() {
            fs::read(path)?
        } else {
            vec![0u8; HEADER_BYTES]
        };
        if data.len() < HEADER_BYTES {
            return Err(RegionError::TooSmall);
        }
        if data.len() > MAX_REGION_BYTES {
            return Err(RegionError::TooLarge);
        }
        let total_sectors = data.len().div_ceil(SECTOR_BYTES);
        let mut used = vec![false; total_sectors];
        used[0] = true;
        used[1] = true;
        let mut locations = [0u8; HEADER_BYTES];
        locations.copy_from_slice(&data[..HEADER_BYTES]);
        for index in 0..LOCATION_ENTRIES {
            let b = &locations[index * 4..index * 4 + 4];
            let entry = u32::from_be_bytes([b[0], b[1], b[2], b[3]]);
            if let Some(offset) = entry_offset(entry) {
                let sectors = (entry & 0xFF) as usize;
                for s in 0..sectors {
                    let sector = offset / SECTOR_BYTES + s;
                    if sector < used.len() {
                        if used[sector] {
                            return Err(RegionError::Overlap {
                                first: sector as u32,
                            });
                        }
                        used[sector] = true;
                    }
                }
            }
        }
        Ok(Self {
            path: path.to_path_buf(),
            data,
            locations,
            used,
        })
    }

    /// Write (replace) one chunk's stored record
    /// `raw = 4-byte BE len + type byte + compressed stream`.
    /// Mirrors vanilla `RegionFile.func_76706_a`: free the old run, first-fit
    /// a contiguous free run (skipping header sectors), grow if needed, write
    /// the payload, then update location + timestamp tables.
    pub fn write_chunk(&mut self, x: u8, z: u8, raw: &[u8]) -> Result<(), RegionError> {
        let needed = (raw.len().div_ceil(SECTOR_BYTES)).max(1);
        let index = z as usize * 32 + x as usize;

        let b = &self.locations[index * 4..index * 4 + 4];
        let old_entry = u32::from_be_bytes([b[0], b[1], b[2], b[3]]);
        if let Some(old_offset) = entry_offset(old_entry) {
            let old_sectors = (old_entry & 0xFF) as usize;
            for s in 0..old_sectors {
                let sector = old_offset / SECTOR_BYTES + s;
                if sector < self.used.len() {
                    self.used[sector] = false;
                }
            }
        }

        match self.find_run(needed) {
            Some(start) => self.commit_at(index, start, needed, raw),
            None => {
                // grow by exactly the shortfall (contiguous tail run)
                let current = self.used.len();
                let mut extra = needed;
                for sector in (current - needed.min(current)..current).rev() {
                    if !self.used[sector] {
                        extra = extra.saturating_sub(1);
                    }
                }
                if extra == 0 {
                    return Err(RegionError::OutOfSectors);
                }
                let new_len = current + extra;
                self.data.resize(new_len * SECTOR_BYTES, 0);
                self.used.resize(new_len, false);
                match self.find_run(needed) {
                    Some(start) => self.commit_at(index, start, needed, raw),
                    None => Err(RegionError::OutOfSectors),
                }
            }
        }
    }

    fn find_run(&self, needed: usize) -> Option<usize> {
        let mut run = 0usize;
        for sector in 2..self.used.len() {
            if self.used[sector] {
                run = 0;
            } else {
                run += 1;
                if run == needed {
                    return Some(sector + 1 - needed);
                }
            }
        }
        None
    }

    fn commit_at(
        &mut self,
        index: usize,
        start: usize,
        needed: usize,
        raw: &[u8],
    ) -> Result<(), RegionError> {
        for s in 0..needed {
            self.used[start + s] = true;
        }
        let offset = start * SECTOR_BYTES;
        if offset + needed * SECTOR_BYTES > self.data.len() {
            self.data.resize(offset + needed * SECTOR_BYTES, 0);
        }
        self.data[offset..offset + raw.len()].copy_from_slice(raw);
        let entry = (((offset / SECTOR_BYTES) as u32) << 8) | (needed as u32 & 0xFF);
        self.locations[index * 4..index * 4 + 4].copy_from_slice(&entry.to_be_bytes());
        let ts_index = HEADER_BYTES / 2 + index * 4;
        let now = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_secs() as u32)
            .unwrap_or(0);
        self.locations[ts_index..ts_index + 4].copy_from_slice(&now.to_be_bytes());
        Ok(())
    }

    /// Validate + flush the whole image atomically (temp file + sync + rename).
    pub fn commit(&self) -> Result<(), RegionError> {
        self.validate()?;
        // Merge the location+timestamp tables into the image header.
        let mut image = self.data.clone();
        image[..HEADER_BYTES].copy_from_slice(&self.locations);
        if let Some(parent) = self.path.parent() {
            fs::create_dir_all(parent)?;
        }
        let tmp = self.path.with_extension("mca.tmp");
        {
            let mut f = fs::OpenOptions::new()
                .write(true)
                .create(true)
                .truncate(true)
                .open(&tmp)?;
            f.write_all(&image)?;
            f.sync_all()?;
        }
        fs::rename(&tmp, &self.path)?;
        Ok(())
    }

    /// Full integrity validation of the image (§17 checks).
    pub fn validate(&self) -> Result<(), RegionError> {
        if self.data.len() < HEADER_BYTES {
            return Err(RegionError::TooSmall);
        }
        if self.data.len() > MAX_REGION_BYTES {
            return Err(RegionError::TooLarge);
        }
        let mut occupied: BTreeMap<u32, u32> = BTreeMap::new();
        for index in 0..LOCATION_ENTRIES {
            let b = &self.locations[index * 4..index * 4 + 4];
            let entry = u32::from_be_bytes([b[0], b[1], b[2], b[3]]);
            if let Some(offset) = entry_offset(entry) {
                let sectors = (entry & 0xFF) as u32;
                if offset + (sectors as usize) * SECTOR_BYTES > self.data.len()
                    || offset < HEADER_BYTES
                {
                    return Err(RegionError::BadEntry { index });
                }
                let start = offset as u32;
                let end = start + sectors;
                for (o2, s2) in occupied.iter() {
                    if start < o2 + s2 && *o2 < end {
                        return Err(RegionError::Overlap { first: *o2 });
                    }
                }
                occupied.insert(start, sectors as u32);
            }
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn empty_region_roundtrip_and_scan() {
        let dir = std::env::temp_dir().join("regionio-test");
        fs::create_dir_all(&dir).unwrap();
        let path = dir.join("r.0.0.mca");
        let _ = fs::remove_file(&path);
        let mut w = RegionWriter::open(&path).unwrap();
        w.validate().unwrap();
        w.commit().unwrap();
        let report = scan(&path, true).unwrap();
        assert_eq!(report.present_chunks, 0);
        assert_eq!(report.absent_chunks, 1024);
        assert!(!report.overlapping_sectors);
    }

    #[test]
    fn write_read_scan_roundtrip() {
        let dir = std::env::temp_dir().join("regionio-test");
        fs::create_dir_all(&dir).unwrap();
        let path = dir.join("r.1.1.mca");
        let _ = fs::remove_file(&path);
        let payload = {
            // minimal valid root compound: 0x0A + name "" + one int tag + END
            let mut v = vec![10u8, 0, 0];
            v.extend_from_slice(&[3, 0, 4, b't', b'e', b's', b't']);
            v.extend_from_slice(&42u32.to_be_bytes());
            v.push(0);
            v
        };
        let compressed = zlib_compress(&payload, 6).unwrap();
        let mut raw = Vec::new();
        raw.extend_from_slice(&(compressed.len() as u32 + 1).to_be_bytes());
        raw.push(2);
        raw.extend_from_slice(&compressed);

        let mut w = RegionWriter::open(&path).unwrap();
        w.write_chunk(0, 0, &raw).unwrap();
        w.write_chunk(31, 31, &raw).unwrap();
        w.commit().unwrap();

        let rec = read_chunk(&path, 0, 0).unwrap().unwrap();
        assert_eq!(rec.payload, payload);
        assert_eq!(rec.compression, 2);
        let report = scan(&path, true).unwrap();
        assert_eq!(report.present_chunks, 2);
        assert_eq!(report.bad_entries.len(), 0);
        assert!(!report.overlapping_sectors);
        assert_eq!(report.nbt_parse_failures.len(), 0);

        // overwrite: same chunk twice must not leak sectors
        let mut w2 = RegionWriter::open(&path).unwrap();
        w2.write_chunk(0, 0, &raw).unwrap();
        w2.commit().unwrap();
        let report2 = scan(&path, true).unwrap();
        assert_eq!(report2.present_chunks, 2);
        assert!(!report2.overlapping_sectors);
        let rec2 = read_chunk(&path, 31, 31).unwrap().unwrap();
        assert_eq!(rec2.payload, payload);
    }

    #[test]
    fn corrupt_inputs_are_detected() {
        let dir = std::env::temp_dir().join("regionio-test");
        fs::create_dir_all(&dir).unwrap();
        // truncated file
        let path = dir.join("r.2.2.mca");
        fs::write(&path, vec![0u8; 100]).unwrap();
        let report = scan(&path, false).unwrap();
        assert!(!report.bad_entries.is_empty());
        // entry pointing past EOF
        let mut image = vec![0u8; HEADER_BYTES];
        image[0] = 0;
        image[1] = 0;
        image[2] = 2; // offset sector 2
        image[3] = 1; // 1 sector
        fs::write(&path, &image).unwrap();
        let report2 = scan(&path, false).unwrap();
        assert_eq!(report2.present_chunks, 1);
        assert_eq!(report2.bad_entries.len(), 1);
    }
}
