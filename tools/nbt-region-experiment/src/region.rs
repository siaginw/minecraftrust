//! Fixture-only, serialized copy-on-write region transactions.
use crate::lossless::MAX_RAW;
use std::sync::atomic::{AtomicU64, Ordering};
static NEXT_WRITER: AtomicU64 = AtomicU64::new(1);
use flate2::{Compression, Decompress, FlushDecompress, Status, write::ZlibEncoder};
use std::{
    fs::{self, File, OpenOptions},
    io::{Read, Write},
    path::{Path, PathBuf},
};

pub const MAX_REGION: usize = 16 * 1024 * 1024;
pub const MAX_COMPRESSED: usize = 1024 * 1024;
pub const SECTOR: usize = 4096;

pub fn read_bounded(path: &Path, limit: usize) -> Result<Vec<u8>, String> {
    let file = File::open(path).map_err(|e| e.to_string())?;
    if file.metadata().map_err(|e| e.to_string())?.len() > limit as u64 {
        return Err("file limit".into());
    }
    let mut out = Vec::new();
    file.take(limit as u64 + 1)
        .read_to_end(&mut out)
        .map_err(|e| e.to_string())?;
    if out.len() > limit {
        return Err("growing file limit".into());
    }
    Ok(out)
}

pub fn validate(bytes: &[u8]) -> Result<(), String> {
    if bytes.len() < 8192 || bytes.len() > MAX_REGION || !bytes.len().is_multiple_of(SECTOR) {
        return Err("region size".into());
    }
    let mut claimed = vec![false; bytes.len() / SECTOR];
    claimed[0] = true;
    claimed[1] = true;
    for slot in 0..1024 {
        let h = &bytes[slot * 4..slot * 4 + 4];
        let offset = ((h[0] as usize) << 16) | ((h[1] as usize) << 8) | h[2] as usize;
        let count = h[3] as usize;
        if offset == 0 && count == 0 {
            continue;
        }
        if offset < 2 || count == 0 || offset + count > claimed.len() {
            return Err("sector bounds".into());
        }
        for sector in &mut claimed[offset..offset + count] {
            if *sector {
                return Err("overlapping sectors".into());
            }
            *sector = true;
        }
        let start = offset * SECTOR;
        let length = u32::from_be_bytes(bytes[start..start + 4].try_into().unwrap()) as usize;
        if !(2..=MAX_COMPRESSED + 1).contains(&length) || length + 4 > count * SECTOR {
            return Err("chunk length".into());
        }
        if bytes[start + 4] != 2 {
            return Err("unsupported compression/external chunk flag".into());
        }
    }
    Ok(())
}

pub fn compressed_chunk(bytes: &[u8], slot: usize) -> Result<&[u8], String> {
    if slot >= 1024 {
        return Err("chunk slot".into());
    }
    let h = &bytes[slot * 4..slot * 4 + 4];
    let start = (((h[0] as usize) << 16) | ((h[1] as usize) << 8) | h[2] as usize) * SECTOR;
    if start == 0 {
        return Err("missing chunk".into());
    }
    let length = u32::from_be_bytes(bytes[start..start + 4].try_into().unwrap()) as usize;
    Ok(&bytes[start + 5..start + 4 + length])
}

pub fn decompress(bytes: &[u8]) -> Result<Vec<u8>, String> {
    if bytes.len() > MAX_COMPRESSED {
        return Err("compressed limit".into());
    }
    // Fixed output capacity prevents a decompression bomb growing allocations.
    let mut out = vec![0; MAX_RAW + 1];
    let mut decoder = Decompress::new(true);
    let status = decoder
        .decompress(bytes, &mut out, FlushDecompress::Finish)
        .map_err(|e| e.to_string())?;
    if status != Status::StreamEnd
        || decoder.total_in() != bytes.len() as u64
        || decoder.total_out() > MAX_RAW as u64
    {
        return Err("bounded zlib stream/trailing data failure".into());
    }
    out.truncate(decoder.total_out() as usize);
    Ok(out)
}

pub fn compress(bytes: &[u8]) -> Result<Vec<u8>, String> {
    if bytes.len() > MAX_RAW {
        return Err("encode output limit".into());
    }
    let mut writer = ZlibEncoder::new(Vec::new(), Compression::new(6));
    writer.write_all(bytes).map_err(|e| e.to_string())?;
    let out = writer.finish().map_err(|e| e.to_string())?;
    if out.len() > MAX_COMPRESSED {
        return Err("compressed output limit".into());
    }
    Ok(out)
}

pub fn replace_chunk(bytes: &[u8], slot: usize, compressed: &[u8]) -> Result<Vec<u8>, String> {
    validate(bytes)?;
    if slot >= 1024 || compressed.is_empty() || compressed.len() > MAX_COMPRESSED {
        return Err("replacement limit".into());
    }
    let sectors = (compressed.len() + 5).div_ceil(SECTOR);
    if sectors > 255 || bytes.len() + sectors * SECTOR > MAX_REGION {
        return Err("region capacity".into());
    }
    let offset = bytes.len() / SECTOR;
    let mut out = bytes.to_vec();
    out.resize(bytes.len() + sectors * SECTOR, 0);
    out[slot * 4..slot * 4 + 4].copy_from_slice(&[
        ((offset >> 16) & 255) as u8,
        ((offset >> 8) & 255) as u8,
        (offset & 255) as u8,
        sectors as u8,
    ]);
    out[bytes.len()..bytes.len() + 4]
        .copy_from_slice(&((compressed.len() + 1) as u32).to_be_bytes());
    out[bytes.len() + 4] = 2;
    out[bytes.len() + 5..bytes.len() + 5 + compressed.len()].copy_from_slice(compressed);
    validate(&out)?;
    Ok(out)
}

pub struct Transaction {
    writer_id: u64,
    generation: u64,
    bytes: Vec<u8>,
}
pub struct Writer {
    id: u64,
    path: PathBuf,
    current: Vec<u8>,
    generation: u64,
}
impl Writer {
    pub fn open(path: &Path) -> Result<Self, String> {
        let current = read_bounded(path, MAX_REGION)?;
        validate(&current)?;
        Ok(Self {
            id: NEXT_WRITER
                .try_update(Ordering::Relaxed, Ordering::Relaxed, |id| id.checked_add(1))
                .map_err(|_| "writer identity exhausted")?,
            path: path.to_owned(),
            current,
            generation: 1,
        })
    }
    pub fn prepare(&self, slot: usize, compressed: &[u8]) -> Result<Transaction, String> {
        Ok(Transaction {
            writer_id: self.id,
            generation: self.generation,
            bytes: replace_chunk(&self.current, slot, compressed)?,
        })
    }
    pub fn commit(&mut self, transaction: Transaction, fault: &str) -> Result<(), String> {
        if transaction.writer_id != self.id || transaction.generation != self.generation {
            return Err("stale ordered write".into());
        }
        let next = self
            .generation
            .checked_add(1)
            .ok_or("writer generation exhausted")?;
        if read_bounded(&self.path, MAX_REGION)? != self.current {
            return Err("external fixture changed".into());
        }
        let temporary = self
            .path
            .with_extension(format!("pending-{}", self.generation));
        let mut file = OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(&temporary)
            .map_err(|e| e.to_string())?;
        let prepared = (|| {
            if fault == "partial-write" {
                file.write_all(&transaction.bytes[..4096])
                    .map_err(|e| e.to_string())?;
                return Err("injected partial write".to_string());
            }
            file.write_all(&transaction.bytes)
                .map_err(|e| e.to_string())?;
            if fault == "before-sync" {
                return Err("injected before sync".to_string());
            }
            file.sync_all().map_err(|e| e.to_string())?;
            if fault == "before-rename" {
                return Err("injected before rename".to_string());
            }
            Ok(())
        })();
        drop(file);
        if let Err(error) = prepared {
            fs::remove_file(&temporary).map_err(|e| e.to_string())?;
            return Err(error);
        }
        // std rename replaces the destination in this fixture's same directory.
        // Filesystem power-loss/directory durability is deliberately not claimed.
        if let Err(error) = fs::rename(&temporary, &self.path) {
            fs::remove_file(&temporary)
                .map_err(|cleanup| format!("rename: {error}; cleanup: {cleanup}"))?;
            return Err(error.to_string());
        }
        self.current = transaction.bytes;
        self.generation = next;
        Ok(())
    }
}
