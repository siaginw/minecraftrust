//! LIVE in-server region READ engine (RUST_REGION_READ_DECOMPRESSION_AUTHORITY).
//!
//! Contract traced from the vanilla seam `RegionFile.func_76704_a(II)
//! -> java.io.DataInputStream` (synchronized; notch `ayj.a(II)`):
//!
//! 1. out-of-bounds x/z (outside 0..31) -> null
//! 2. entry = offsets[x + z*32]; entry == 0 -> null (MISSING)
//! 3. sectorOffset = entry >>> 8, sectorCount = entry & 255;
//!    sectorOffset + sectorCount > tracked-file-sector-count -> null
//! 4. seek(sectorOffset * 4096); length = readInt();
//!    length > 4096 * sectorCount -> null; length <= 0 -> null
//! 5. type = readByte(): 1 = GZIP, 2 = zlib; anything else -> null
//! 6. ANY IOException anywhere -> null
//!
//! Divergence from vanilla BY DESIGN: vanilla returns a LAZY decompressing
//! stream, so a truncated/invalid stream surfaces only when the caller reads
//! it. The live reader decompresses EAGERLY and full-validates BEFORE any
//! byte reaches Java; on any failure the caller fail-closes to the vanilla
//! body (reproducing vanilla behavior exactly). No partial stream ever
//! escapes (partial_stream_attempts = 0).
//!
//! Coherency: the reader is DISK-DERIVED per call — the location entry is
//! re-read from the file on every read, so commits from any writer (the
//! Rust engine, vanilla fallbacks, other processes) are visible immediately
//! and no cached map can go stale. Overhead: one 4-byte pread per read.

use std::fs::File;
use std::io::{Read, Seek, SeekFrom};
use std::path::{Path, PathBuf};

use super::SECTOR_BYTES;

pub const READ_SUCCESS: i32 = 0;
pub const READ_MISSING: i32 = 1;
pub const READ_CORRUPT_ENTRY: i32 = 2;
pub const READ_UNSUPPORTED_COMPRESSION: i32 = 3;
pub const READ_OUTPUT_TOO_SMALL: i32 = 4;
pub const READ_IO_ERROR: i32 = 5;
pub const READ_NOT_ELIGIBLE: i32 = 6;

/// Decompressed-size sanity cap for a single chunk record. Vanilla streams
/// unbounded; a record above this cap fails closed to vanilla instead of
/// allocating (real chunks are orders of magnitude below).
pub const READ_MAX_DECOMPRESSED: usize = 32 * 1024 * 1024;

pub struct RegionReader {
    path: PathBuf,
    file: File,
}

impl RegionReader {
    pub fn open(path: &Path) -> Result<Self, String> {
        let file = std::fs::OpenOptions::new()
            .read(true)
            .open(path)
            .map_err(|e| e.to_string())?;
        Ok(Self {
            path: path.to_path_buf(),
            file,
        })
    }

    pub fn path(&self) -> &Path {
        &self.path
    }

    /// Read + fully validate + decompress the record at (x, z). On
    /// READ_SUCCESS, `out` is replaced with the exact uncompressed bytes;
    /// on any other status `out` is left unchanged (fail closed). The
    /// location entry is re-read from disk per call (fresh coherency,
    /// goal §11).
    pub fn read_chunk(&self, x: u8, z: u8, out: &mut Vec<u8>) -> Result<i32, String> {
        let file_len = self
            .file
            .metadata()
            .map_err(|_| READ_IO_ERROR.to_string())?
            .len() as usize;
        let total_sectors = file_len.div_ceil(SECTOR_BYTES);
        let index = z as usize * 32 + x as usize;

        // 1. location entry, fresh from disk
        let mut eb = [0u8; 4];
        self.read_at(index * 4, &mut eb)
            .map_err(|_| READ_IO_ERROR.to_string())?;
        let entry = u32::from_be_bytes([eb[0], eb[1], eb[2], eb[3]]);
        let offset = (entry >> 8) as usize;
        let count = (entry & 0xFF) as usize;
        if offset == 0 || count == 0 {
            return Ok(READ_MISSING);
        }
        // vanilla: sectorOffset + sectorCount > tracked size -> null
        if offset + count > total_sectors {
            return Ok(READ_CORRUPT_ENTRY);
        }
        // 2. length prefix; vanilla: length > 4096*count -> null; <= 0 -> null
        let mut lb = [0u8; 4];
        self.read_at(offset * SECTOR_BYTES, &mut lb)
            .map_err(|_| READ_IO_ERROR.to_string())?;
        let length = u32::from_be_bytes([lb[0], lb[1], lb[2], lb[3]]) as usize;
        if length > SECTOR_BYTES * count || length == 0 {
            return Ok(READ_CORRUPT_ENTRY);
        }
        // length counts the type byte; the stream is length-1 bytes
        let mut tb = [0u8; 1];
        self.read_at(offset * SECTOR_BYTES + 4, &mut tb)
            .map_err(|_| READ_IO_ERROR.to_string())?;
        let compression = tb[0];
        if compression != 1 && compression != 2 {
            return Ok(READ_UNSUPPORTED_COMPRESSION);
        }
        let stream_len = length - 1;
        let mut stream = vec![0u8; stream_len];
        self.read_at(offset * SECTOR_BYTES + 5, &mut stream)
            .map_err(|_| READ_IO_ERROR.to_string())?;

        // 3. eager full decompression + strict validation: ANY decompression
        // failure becomes the READ_IO_ERROR status (fail closed, `out`
        // untouched) — never a partial stream and never a native panic
        match decompress_stream(compression, &stream) {
            Ok(mut plain) => {
                std::mem::swap(out, &mut plain);
                Ok(READ_SUCCESS)
            }
            Err(_) => Ok(READ_IO_ERROR),
        }
    }

    fn read_at(&self, offset: usize, buf: &mut [u8]) -> std::io::Result<()> {
        let mut f = &self.file;
        f.seek(SeekFrom::Start(offset as u64))?;
        f.read_exact(buf)
    }
}

/// Full, strict decompression of a region record stream (type dispatched
/// exactly as vanilla func_76704_a: 1 = GZIP, 2 = zlib). Truncated /
/// trailing / oversized streams are errors (the caller fail-closes to
/// vanilla, reproducing vanilla's own lazy failure).
pub fn decompress_stream(compression: u8, stream: &[u8]) -> Result<Vec<u8>, String> {
    match compression {
        1 => {
            // single-member gzip (matches java.util.zip.GZIPInputStream:
            // trailing bytes after the member are ignored by the stream)
            let mut decoder = flate2::read::GzDecoder::new(stream);
            let mut out = Vec::new();
            decoder
                .read_to_end(&mut out)
                .map_err(|e| format!("gzip: {e}"))?;
            if out.len() > READ_MAX_DECOMPRESSED {
                return Err("gzip: exceeds decompression cap".into());
            }
            Ok(out)
        }
        2 => {
            use flate2::{Decompress, FlushDecompress, Status};
            let mut out = Vec::new();
            let mut chunk = vec![0u8; (stream.len().saturating_mul(4)).max(65_536)];
            let mut decoder = Decompress::new(true); // zlib header present
            let mut input: &[u8] = stream;
            // FlushDecompress::None pauses gracefully when the scratch buffer
            // fills (Finish would return BufError instead — caught live as
            // spurious READ_IO_ERROR on high-ratio records); StreamEnd is
            // still reported when the stream completes.
            loop {
                let before_out = decoder.total_out();
                let status = decoder
                    .decompress(input, &mut chunk, FlushDecompress::None)
                    .map_err(|e| format!("zlib: {e}"))?;
                // the decoder consumed a prefix of `input`; advance so the
                // next call continues the stream instead of reprocessing it
                let consumed = decoder.total_in() as usize;
                input = &stream[consumed.min(stream.len())..];
                let produced = (decoder.total_out() - before_out) as usize;
                out.extend_from_slice(&chunk[..produced]);
                if out.len() > READ_MAX_DECOMPRESSED {
                    return Err("zlib: exceeds decompression cap".into());
                }
                match status {
                    Status::StreamEnd => {
                        if decoder.total_in() != stream.len() as u64 {
                            return Err("zlib: trailing bytes after stream end".into());
                        }
                        return Ok(out);
                    }
                    Status::Ok | Status::BufError => {
                        // progress required: either more input must be
                        // consumed or the output must keep draining. If the
                        // input is exhausted before StreamEnd: truncated.
                        if produced == 0 && input.is_empty() {
                            return Err("zlib: truncated stream".into());
                        }
                        if produced == 0 && !input.is_empty() && status == Status::BufError {
                            // scratch full with no drain: grow the scratch
                            chunk = vec![0u8; chunk.len() * 2];
                        }
                    }
                    _ => return Err("zlib: unexpected status".into()),
                }
            }
        }
        _ => Err(format!("unsupported compression type {compression}")),
    }
}
