//! Java Modified UTF-8 (MUTF-8) encoder and decoder.
//!
//! Handles:
//! - Null byte `\u0000` encoded as 2-byte sequence `0xC0, 0x80`.
//! - Supplementary characters (> U+FFFF) encoded as 6-byte surrogate pairs.
//! - Standard ASCII (1-127) as single bytes.

use std::fmt;

#[derive(Debug, PartialEq, Eq)]
pub enum Mutf8Error {
    UnexpectedEof,
    InvalidByte(u8),
    InvalidSurrogate,
    Utf8Error,
}

impl fmt::Display for Mutf8Error {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{:?}", self)
    }
}

impl std::error::Error for Mutf8Error {}

pub fn decode_mutf8(bytes: &[u8]) -> Result<String, Mutf8Error> {
    let mut out = String::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        let b1 = bytes[i];
        if b1 == 0 {
            return Err(Mutf8Error::InvalidByte(0));
        } else if b1 < 0x80 {
            out.push(b1 as char);
            i += 1;
        } else if b1 & 0xE0 == 0xC0 {
            if i + 1 >= bytes.len() {
                return Err(Mutf8Error::UnexpectedEof);
            }
            let b2 = bytes[i + 1];
            if b2 & 0xC0 != 0x80 {
                return Err(Mutf8Error::InvalidByte(b2));
            }
            if b1 == 0xC0 && b2 == 0x80 {
                out.push('\0');
            } else {
                let cp = (((b1 & 0x1F) as u32) << 6) | ((b2 & 0x3F) as u32);
                if let Some(ch) = char::from_u32(cp) {
                    out.push(ch);
                } else {
                    return Err(Mutf8Error::InvalidSurrogate);
                }
            }
            i += 2;
        } else if b1 & 0xF0 == 0xE0 {
            if i + 2 >= bytes.len() {
                return Err(Mutf8Error::UnexpectedEof);
            }
            let b2 = bytes[i + 1];
            let b3 = bytes[i + 2];
            if b2 & 0xC0 != 0x80 || b3 & 0xC0 != 0x80 {
                return Err(Mutf8Error::InvalidByte(b2));
            }

            // Check if high surrogate (0xED 0xA0..0xAF)
            if b1 == 0xED && (b2 & 0xF0) == 0xA0 {
                // Must be followed by low surrogate (0xED 0xB0..0xBF)
                if i + 5 >= bytes.len() {
                    return Err(Mutf8Error::UnexpectedEof);
                }
                let b4 = bytes[i + 3];
                let b5 = bytes[i + 4];
                let b6 = bytes[i + 5];
                if b4 != 0xED || (b5 & 0xF0) != 0xB0 || (b6 & 0xC0) != 0x80 {
                    return Err(Mutf8Error::InvalidSurrogate);
                }
                let high = (((b1 as u32 & 0x0F) << 12) | ((b2 as u32 & 0x3F) << 6) | (b3 as u32 & 0x3F)) as u32;
                let low = (((b4 as u32 & 0x0F) << 12) | ((b5 as u32 & 0x3F) << 6) | (b6 as u32 & 0x3F)) as u32;
                let cp = 0x10000 + ((high - 0xD800) << 10) + (low - 0xDC00);
                if let Some(ch) = char::from_u32(cp) {
                    out.push(ch);
                } else {
                    return Err(Mutf8Error::InvalidSurrogate);
                }
                i += 6;
            } else {
                let cp = (((b1 & 0x0F) as u32) << 12) | (((b2 & 0x3F) as u32) << 6) | ((b3 & 0x3F) as u32);
                if let Some(ch) = char::from_u32(cp) {
                    out.push(ch);
                } else {
                    return Err(Mutf8Error::InvalidSurrogate);
                }
                i += 3;
            }
        } else {
            return Err(Mutf8Error::InvalidByte(b1));
        }
    }
    Ok(out)
}

pub fn encode_mutf8(s: &str, out: &mut Vec<u8>) {
    for ch in s.chars() {
        let cp = ch as u32;
        if cp == 0 {
            out.push(0xC0);
            out.push(0x80);
        } else if cp <= 0x7F {
            out.push(cp as u8);
        } else if cp <= 0x7FF {
            out.push((0xC0 | ((cp >> 6) & 0x1F)) as u8);
            out.push((0x80 | (cp & 0x3F)) as u8);
        } else if cp <= 0xFFFF {
            out.push((0xE0 | ((cp >> 12) & 0x0F)) as u8);
            out.push((0x80 | ((cp >> 6) & 0x3F)) as u8);
            out.push((0x80 | (cp & 0x3F)) as u8);
        } else {
            // Supplementary character encoded as surrogate pair in MUTF-8
            let sub = cp - 0x10000;
            let high = 0xD800 + ((sub >> 10) & 0x3FF);
            let low = 0xDC00 + (sub & 0x3FF);

            // Encode high surrogate
            out.push((0xE0 | ((high >> 12) & 0x0F)) as u8);
            out.push((0x80 | ((high >> 6) & 0x3F)) as u8);
            out.push((0x80 | (high & 0x3F)) as u8);

            // Encode low surrogate
            out.push((0xE0 | ((low >> 12) & 0x0F)) as u8);
            out.push((0x80 | ((low >> 6) & 0x3F)) as u8);
            out.push((0x80 | (low & 0x3F)) as u8);
        }
    }
}
