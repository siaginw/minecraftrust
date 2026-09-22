//! M-CK3: Rust outbound frame engine (offline, immutable inputs).
//!
//! Input: ONE complete immutable serialized packet body (packet-ID VarInt +
//! fields, exactly as the Java packet serializer produced it). Output: one
//! complete unencrypted outbound Protocol-340 frame:
//!
//!   compression disabled (threshold < 0):
//!     [VarInt(bodyLen)] [body]
//!   compression enabled (threshold >= 0):
//!     [VarInt(innerLen)] [VarInt(dataLen)] [payload]
//!     bodyLen < threshold  -> dataLen = 0, payload = body (verbatim)
//!     bodyLen >= threshold -> dataLen = bodyLen, payload = zlib(body)
//!     innerLen = VarInt-size(dataLen) + payload.len()
//!
//! Contract verified against the installed 1.12.2 handlers (javap):
//! NettyCompressionEncoder (strict `<` threshold branch; zlib-wrapped
//! independent stream per packet, JDK-default level; reused Deflater — our
//! context resets after each packet, the bit-exact-validated M2C contract)
//! and NettyVarint21FrameEncoder (3-byte max length prefix, "unable to fit"
//! above 0x1FFFFF). Java retains negotiation/encryption/socket — the caller
//! supplies the EFFECTIVE threshold per call.
//!
//! Contents are opaque here: no packet reinterpretation, no duplicate packet
//! ID, no chunk-vs-body conflation.

use crate::{ZlibPacketCompressor, VANILLA_LEVEL, max_output_len};

/// Outbound encoder limit: NettyVarint21FrameEncoder rejects bodies that do
/// not fit a 3-byte VarInt (verified "unable to fit" in the installed code).
pub const MAX_FRAME_BODY: usize = 0x1F_FF_FF;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum FrameError {
    /// Caller output buffer too small; `needed` is a safe retry bound.
    OutputTooSmall { needed: usize, have: usize },
    /// Frame body exceeds the 3-byte VarInt limit of the installed encoder.
    BodyTooLarge { len: usize },
    /// Compressor backend rejected the stream.
    Backend(&'static str),
    /// Invalid arguments (empty body or negative-length encoding overflow).
    Invalid(&'static str),
}

impl FrameError {
    pub fn as_code(&self) -> i32 {
        match self {
            FrameError::OutputTooSmall { .. } => -1,
            FrameError::BodyTooLarge { .. } => -3,
            FrameError::Backend(_) => -2,
            FrameError::Invalid(_) => -4,
        }
    }
    /// Retry bound for OutputTooSmall (0 for other errors).
    pub fn needed(&self) -> usize {
        match self {
            FrameError::OutputTooSmall { needed, .. } => *needed,
            _ => 0,
        }
    }
}

/// Protocol-340 VarInt size (1..=5 bytes). Matches crates/protocol's
/// varint_size; kept inline to avoid a compression->protocol dependency.
#[inline]
pub fn varint_size(mut val: usize) -> usize {
    let mut n = 1;
    while val & !0x7F != 0 {
        val >>= 7;
        n += 1;
    }
    n
}

#[inline]
fn write_varint(mut val: usize, out: &mut [u8], pos: &mut usize) {
    loop {
        let mut b = (val & 0x7F) as u8;
        val >>= 7;
        if val != 0 {
            b |= 0x80;
        }
        out[*pos] = b;
        *pos += 1;
        if val == 0 {
            break;
        }
    }
}

/// Reusable outbound frame context. Single-owner (one connection/event loop,
/// mirroring the production encoder-per-channel wiring); independent contexts
/// may run concurrently. Retains compressor + scratch for its lifetime.
pub struct OutboundFrameContext {
    compressor: ZlibPacketCompressor,
    /// Reusable deflate-output scratch (grown on demand, never shrunk).
    comp_scratch: Vec<u8>,
    /// Cumulative growth events (instrumentation for tests/benches).
    pub growth_events: u64,
    /// Cumulative compression decisions taken (threshold >= 0 && >= threshold).
    pub compress_events: u64,
    /// Cumulative passthrough decisions (threshold >= 0 && < threshold).
    pub passthrough_events: u64,
}

impl OutboundFrameContext {
    pub fn new() -> Result<Self, &'static str> {
        Ok(Self {
            compressor: ZlibPacketCompressor::new(VANILLA_LEVEL)?,
            comp_scratch: Vec::new(),
            growth_events: 0,
            compress_events: 0,
            passthrough_events: 0,
        })
    }

    /// Exact output size of the frame for `body` under `threshold`, without
    /// encoding. Also the safe retry bound on OutputTooSmall.
    pub fn frame_len(&self, body_len: usize, threshold: i32) -> Result<usize, FrameError> {
        if body_len == 0 {
            return Err(FrameError::Invalid("empty packet body"));
        }
        if threshold < 0 {
            let inner = body_len;
            if inner > MAX_FRAME_BODY {
                return Err(FrameError::BodyTooLarge { len: inner });
            }
            return Ok(varint_size(inner) + body_len);
        }
        let th = threshold as usize;
        if body_len < th {
            let inner = varint_size(0) + body_len;
            if inner > MAX_FRAME_BODY {
                return Err(FrameError::BodyTooLarge { len: inner });
            }
            return Ok(varint_size(inner) + inner);
        }
        // Compressed path: exact compressed size is only known after deflate.
        // Bound: outer VarInt(<=4) + dataLen VarInt(<=5) + max zlib expansion.
        let bound = 4 + 5 + max_output_len(body_len);
        if varint_size(0) + body_len > MAX_FRAME_BODY {
            return Err(FrameError::BodyTooLarge { len: body_len });
        }
        Ok(bound)
    }

    /// Encode one complete frame from the immutable `body` into `out`,
    /// returning the frame length. On error nothing is a valid frame
    /// (partial output is never a completed frame) and the compressor is
    /// left reset (independent-stream contract holds after errors).
    pub fn encode(&mut self, body: &[u8], threshold: i32, out: &mut [u8]) -> Result<usize, FrameError> {
        if body.is_empty() {
            return Err(FrameError::Invalid("empty packet body"));
        }
        if threshold < 0 {
            // Disabled: [VarInt(bodyLen)][body]
            let inner = body.len();
            if inner > MAX_FRAME_BODY {
                return Err(FrameError::BodyTooLarge { len: inner });
            }
            let total = varint_size(inner) + inner;
            if out.len() < total {
                return Err(FrameError::OutputTooSmall { needed: total, have: out.len() });
            }
            let mut pos = 0;
            write_varint(inner, out, &mut pos);
            out[pos..pos + inner].copy_from_slice(body);
            return Ok(total);
        }

        let th = threshold as usize;
        if body.len() < th {
            // Passthrough: dataLen = 0
            self.passthrough_events += 1;
            let inner = varint_size(0) + body.len();
            if inner > MAX_FRAME_BODY {
                return Err(FrameError::BodyTooLarge { len: inner });
            }
            let total = varint_size(inner) + inner;
            if out.len() < total {
                return Err(FrameError::OutputTooSmall { needed: total, have: out.len() });
            }
            let mut pos = 0;
            write_varint(inner, out, &mut pos);
            write_varint(0, out, &mut pos);
            out[pos..pos + body.len()].copy_from_slice(body);
            return Ok(total);
        }

        // Compressed: [VarInt(inner)][VarInt(bodyLen)][zlib(body)]
        if varint_size(0) + body.len() > MAX_FRAME_BODY {
            return Err(FrameError::BodyTooLarge { len: body.len() });
        }
        self.compress_events += 1;
        let bound = max_output_len(body.len());
        if self.comp_scratch.len() < bound {
            self.comp_scratch.resize(bound, 0);
            self.growth_events += 1;
        }
        let compressed = {
            let scratch = &mut self.comp_scratch[..bound];
            match self.compressor.compress_into(body, scratch) {
                Ok(n) => n,
                Err(e) => return Err(match e {
                    crate::CompressError::OutputTooSmall { needed, have } =>
                        FrameError::OutputTooSmall { needed: needed + 9, have: out.len() },
                    crate::CompressError::BackendError(s) => FrameError::Backend(s),
                }),
            }
        };
        // compressor contract: reset after every packet (success or the
        // guarded bound above leaves nothing partial).

        let inner = varint_size(body.len()) + compressed;
        if inner > MAX_FRAME_BODY {
            return Err(FrameError::BodyTooLarge { len: inner });
        }
        let total = varint_size(inner) + inner;
        if out.len() < total {
            return Err(FrameError::OutputTooSmall { needed: total, have: out.len() });
        }
        let mut pos = 0;
        write_varint(inner, out, &mut pos);
        write_varint(body.len(), out, &mut pos);
        out[pos..pos + compressed].copy_from_slice(&self.comp_scratch[..compressed]);
        Ok(total)
    }

    /// Approximate retained scratch bytes (compressor internal + scratch).
    pub fn retained_bytes(&self) -> usize {
        self.comp_scratch.len() + 64 // 64: fixed compressor context estimate
    }
}

impl Default for OutboundFrameContext {
    fn default() -> Self {
        Self::new().expect("vanilla-level compressor init cannot fail")
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn varint_parse(b: &[u8]) -> (i32, usize) {
        let mut v = 0i32;
        let mut i = 0;
        loop {
            let x = b[i];
            v |= ((x & 0x7F) as i32) << (7 * i);
            i += 1;
            if x & 0x80 == 0 {
                return (v, i);
            }
        }
    }

    #[test]
    fn disabled_frame_layout() {
        let mut ctx = OutboundFrameContext::new().unwrap();
        let body = [1u8, 2, 3, 4, 5];
        let mut out = [0u8; 64];
        let n = ctx.encode(&body, -1, &mut out).unwrap();
        assert_eq!(n, 6);
        let (len, used) = varint_parse(&out);
        assert_eq!(len as usize, body.len());
        assert_eq!(used, 1);
        assert_eq!(&out[1..6], &body);
    }

    #[test]
    fn below_threshold_passthrough() {
        let mut ctx = OutboundFrameContext::new().unwrap();
        let body = vec![7u8; 10];
        let mut out = [0u8; 64];
        let n = ctx.encode(&body, 256, &mut out).unwrap();
        let (inner, u1) = varint_parse(&out);
        assert_eq!((inner, u1), (11, 1));
        let (data_len, u2) = varint_parse(&out[u1..]);
        assert_eq!((data_len, u2), (0, 1));
        assert_eq!(&out[u1 + u2..n], &body[..]);
        assert_eq!(ctx.passthrough_events, 1);
    }

    #[test]
    fn at_threshold_compresses() {
        let mut ctx = OutboundFrameContext::new().unwrap();
        let body = vec![3u8; 256];
        let mut out = vec![0u8; 4096];
        let n = ctx.encode(&body, 256, &mut out).unwrap(); // strict >=: 256 is NOT < 256
        let (inner, u1) = varint_parse(&out);
        let (data_len, _) = varint_parse(&out[u1..]);
        assert_eq!(data_len as usize, body.len());
        assert!(n < body.len() + 8, "repetitive input must compress");
        assert_eq!(ctx.compress_events, 1);
    }

    #[test]
    fn threshold_zero_compresses_everything() {
        let mut ctx = OutboundFrameContext::new().unwrap();
        let body = [9u8; 4];
        let mut out = vec![0u8; 256];
        let n = ctx.encode(&body, 0, &mut out).unwrap();
        assert!(n > 0);
        assert_eq!(ctx.compress_events, 1);
        assert_eq!(ctx.passthrough_events, 0);
    }

    #[test]
    fn consecutive_frames_independent_streams() {
        let mut ctx = OutboundFrameContext::new().unwrap();
        let a = vec![1u8; 1000];
        let b = vec![2u8; 1000];
        let mut fa = vec![0u8; 4096];
        let mut fb = vec![0u8; 4096];
        let na = ctx.encode(&a, 64, &mut fa).unwrap();
        let nb = ctx.encode(&b, 64, &mut fb).unwrap();
        // independently decode both with the Java-side-equivalent inflater semantics
        let dec = |f: &[u8], n: usize| -> Vec<u8> {
            let (inner, u1) = varint_parse(f);
            let (dl, u2) = varint_parse(&f[u1..]);
            assert_eq!(inner as usize + u1, n);
            if dl == 0 {
                f[u1 + u2..n].to_vec()
            } else {
                let mut inf = flate2::read::ZlibDecoder::new(&f[u1 + u2..n]);
                let mut v = Vec::new();
                std::io::Read::read_to_end(&mut inf, &mut v).unwrap();
                v
            }
        };
        assert_eq!(dec(&fa, na), a);
        assert_eq!(dec(&fb, nb), b);
    }

    #[test]
    fn capacity_error_is_clean_and_retryable() {
        let mut ctx = OutboundFrameContext::new().unwrap();
        let body = vec![5u8; 500];
        let mut small = vec![0u8; 4];
        match ctx.encode(&body, 0, &mut small) {
            Err(FrameError::OutputTooSmall { needed, .. }) => {
                let mut big = vec![0u8; needed];
                let n = ctx.encode(&body, 0, &mut big).unwrap();
                assert!(n <= needed);
            }
            other => panic!("expected OutputTooSmall, got {:?}", other),
        }
    }

    #[test]
    fn empty_body_invalid_and_body_too_large() {
        let mut ctx = OutboundFrameContext::new().unwrap();
        let mut out = vec![0u8; 16];
        assert!(matches!(ctx.encode(&[], 256, &mut out), Err(FrameError::Invalid(_))));
        let huge = vec![0u8; MAX_FRAME_BODY + 1];
        assert!(matches!(ctx.encode(&huge, -1, &mut out), Err(FrameError::BodyTooLarge { .. })));
        assert!(matches!(ctx.encode(&huge, 256, &mut out), Err(FrameError::BodyTooLarge { .. })));
    }

    #[test]
    fn varint_boundaries() {
        // boundary values around VarInt length transitions for the outer prefix
        let mut ctx = OutboundFrameContext::new().unwrap();
        for len in [127usize, 128, 16383, 16384, 2097151] {
            let body = vec![1u8; len];
            let mut out = vec![0u8; len + 16];
            let n = ctx.encode(&body, -1, &mut out).unwrap_or_else(|e| panic!("len {} -> {:?}", len, e));
            assert_eq!(n, varint_size(len) + len);
        }
    }
}
