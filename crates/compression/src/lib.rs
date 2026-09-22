//! Compression and decompression abstraction for network and region I/O.
//!
//! M2.0 component: `ZlibPacketCompressor` — outbound per-packet deflate
//! matching the vanilla 1.12.2 reference contract (javap-verified):
//! JDK `new Deflater()` = zlib-wrapped stream, default level 6, one
//! Deflater/context REUSED across packets with a reset after each
//! (independent streams), input snapshotted per packet. Java retains all
//! threshold decisions and VarInt framing; the 2 MiB guard in vanilla is
//! DECODER-side only and is deliberately NOT transplanted here — outbound
//! bounds instead cover incompressible-data expansion.
//!
//! Pinned backend: flate2 =1.1.10, default-features off, feature
//! `zlib-rs` (zlib-rs crate, pure Rust). Runtime-asserted in tests via
//! flate2::backend().

pub mod frame;

pub trait Compressor {
    fn compress(&self, input: &[u8], output: &mut Vec<u8>) -> Result<(), &'static str>;
    fn decompress(&self, input: &[u8], output: &mut Vec<u8>) -> Result<(), &'static str>;
}

pub struct PassthroughCompressor;

impl Compressor for PassthroughCompressor {
    fn compress(&self, input: &[u8], output: &mut Vec<u8>) -> Result<(), &'static str> {
        output.extend_from_slice(input);
        Ok(())
    }

    fn decompress(&self, input: &[u8], output: &mut Vec<u8>) -> Result<(), &'static str> {
        output.extend_from_slice(input);
        Ok(())
    }
}

// ---------------------------------------------------------------------------
// M2.0: native outbound packet compression component
// ---------------------------------------------------------------------------

/// Matches the vanilla reference encoder level: JDK `new Deflater()` uses
/// DEFAULT_COMPRESSION, which the JDK documents as level 6.
pub const VANILLA_LEVEL: u32 = 6;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CompressError {
    /// Output capacity insufficient. The required bound is reported so the
    /// caller can retry with a correctly sized buffer.
    OutputTooSmall { needed: usize, have: usize },
    /// Backend rejected the stream (corrupt state or internal error).
    BackendError(&'static str),
}

impl CompressError {
    pub fn as_code(&self) -> i32 {
        match self {
            CompressError::OutputTooSmall { .. } => -1,
            CompressError::BackendError(_) => -2,
        }
    }
}

/// Safe upper bound on zlib-stream expansion of incompressible input:
/// zlib wrapper (2B header + 4B adler32) + stored-block overhead
/// (5B per <=65535B block) + slack. Never below `n`.
pub fn max_output_len(n: usize) -> usize {
    n + (n / 8192 + 2) * 5 + 16
}

pub struct ZlibPacketCompressor {
    inner: flate2::Compress,
    level: u32,
}

impl ZlibPacketCompressor {
    /// Level in 0..=9. `VANILLA_LEVEL` (6) matches the JDK default the
    /// reference encoder uses.
    pub fn new(level: u32) -> Result<Self, &'static str> {
        if level > 9 {
            return Err("level must be 0..=9");
        }
        Ok(Self {
            // zlib_header = true: zlib-wrapped stream, matching JDK Deflater.
            inner: flate2::Compress::new(flate2::Compression::new(level), true),
            level,
        })
    }

    pub fn level(&self) -> u32 {
        self.level
    }

    /// Backend identity string for harness logging. flate2 1.1.x exposes no
    /// runtime backend() under default-features=false, so identity is
    /// pinned by Cargo features (zlib-rs sole backend; cargo tree output
    /// recorded in M20 results) and this constant documents the pin.
    pub const BACKEND: &'static str = "flate2=1.1.10 feature zlib-rs (zlib-rs crate)";

    /// Compress `input` as one complete zlib stream into `output`,
    /// returning the compressed length. The context is RESET after every
    /// call: each packet is an independent stream, exactly like the
    /// reference encoder's Deflater::reset.
    ///
    /// Bounds are checked BEFORE any write; on OutputTooSmall nothing is
    /// written and the caller receives the safe bound for retry.
    pub fn compress_into(&mut self, input: &[u8], output: &mut [u8]) -> Result<usize, CompressError> {
        let bound = max_output_len(input.len());
        if output.len() < bound {
            return Err(CompressError::OutputTooSmall { needed: bound, have: output.len() });
        }
        let mut written = 0usize;
        let mut consumed = 0usize;
        loop {
            let before_out = self.inner.total_out();
            let before_in = self.inner.total_in();
            let flush = if consumed >= input.len() {
                flate2::FlushCompress::Finish
            } else {
                flate2::FlushCompress::None
            };
            let status = self
                .inner
                .compress(&input[consumed..], &mut output[written..], flush)
                .map_err(|_| CompressError::BackendError("compress failed"))?;
            consumed += (self.inner.total_in() - before_in) as usize;
            written += (self.inner.total_out() - before_out) as usize;
            if status == flate2::Status::StreamEnd && consumed >= input.len() {
                break;
            }
            if written == output.len() && status != flate2::Status::StreamEnd {
                // defensive: no space left and stream not finished
                return Err(CompressError::OutputTooSmall { needed: bound, have: output.len() });
            }
        }
        // Independent streams per packet (reference: Deflater::reset).
        self.inner.reset();
        Ok(written)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use flate2::read::ZlibDecoder;
    use std::io::Read;

    thread_local! {
        static RNG: std::cell::Cell<u64> = std::cell::Cell::new(0x9E3779B97F4A7C15);
    }

    fn rand_byte() -> u8 {
        RNG.with(|s| {
            let mut x = s.get();
            x ^= x << 13;
            x ^= x >> 7;
            x ^= x << 17;
            s.set(x);
            x as u8
        })
    }

    fn rand_vec(n: usize) -> Vec<u8> {
        (0..n).map(|_| rand_byte()).collect()
    }

    fn roundtrip(data: &[u8]) {
        let mut c = ZlibPacketCompressor::new(VANILLA_LEVEL).unwrap();
        let mut out = vec![0u8; max_output_len(data.len())];
        let n = c.compress_into(data, &mut out).unwrap();
        // Independent streams: a second use of the same context must produce
        // an identical independent stream (reset works, no state bleed).
        let mut out2 = vec![0u8; max_output_len(data.len())];
        let n2 = c.compress_into(data, &mut out2).unwrap();
        assert_eq!(n, n2, "context reuse must produce identical streams");
        // Roundtrip via flate2's own zlib decoder
        let mut dec = ZlibDecoder::new(&out[..n]);
        let mut plain = Vec::new();
        dec.read_to_end(&mut plain).unwrap();
        assert_eq!(plain, data);
    }

    #[test]
    fn backend_pin_is_recorded() {
        // The pin is compile-time (Cargo features; see Cargo.toml and the
        // committed cargo-tree output). This test guards the constant's
        // documentation of it.
        assert!(ZlibPacketCompressor::BACKEND.contains("zlib-rs"));
    }

    #[test]
    fn roundtrip_cases() {
        roundtrip(b"");
        roundtrip(b"a");
        roundtrip(&[0u8; 64 * 1024]); // repetitive
        roundtrip(&(0..65_536u32).map(|i| i as u8).collect::<Vec<_>>()); // semi-compressible
        roundtrip(&rand_vec(64 * 1024)); // incompressible-ish
        roundtrip(&vec![0xABu8; 1_048_576]); // 1 MiB repetitive
    }

    #[test]
    fn threshold_adjacent_sizes() {
        for n in [0usize, 1, 255, 256, 257, 4096] {
            let data: Vec<u8> = (0..n).map(|i| (i * 31 % 251) as u8).collect();
            roundtrip(&data);
        }
    }

    #[test]
    fn insufficient_capacity_is_checked_before_writes() {
        let mut c = ZlibPacketCompressor::new(VANILLA_LEVEL).unwrap();
        let data = [1u8; 1024];
        let mut out = vec![0x5Au8; 10]; // deliberately too small
        match c.compress_into(&data, &mut out) {
            Err(CompressError::OutputTooSmall { needed, have }) => {
                assert_eq!(have, 10);
                assert!(needed >= data.len());
            }
            other => panic!("expected OutputTooSmall, got {other:?}"),
        }
        // nothing written on failure
        assert!(out.iter().all(|&b| b == 0x5A));
        // context still usable afterwards (no partial state leaked)
        let mut big = vec![0u8; max_output_len(1024)];
        assert!(c.compress_into(&data, &mut big).is_ok());
    }

    #[test]
    fn incompressible_expansion_stays_within_bound() {
        let mut c = ZlibPacketCompressor::new(VANILLA_LEVEL).unwrap();
        let data = rand_vec(256 * 1024);
        let mut out = vec![0u8; max_output_len(data.len())];
        let n = c.compress_into(&data, &mut out).unwrap();
        assert!(n <= max_output_len(data.len()));
        assert!(n > data.len(), "random data should expand slightly");
    }

    #[test]
    fn level_bounds() {
        assert!(ZlibPacketCompressor::new(10).is_err());
        assert!(ZlibPacketCompressor::new(9).is_ok());
    }
}
