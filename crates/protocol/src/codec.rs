use std::io::{Read, Write};
use core_types::BlockPos;
use crate::error::ProtocolError;

// ---------------------------------------------------------------------------
// VarInt & VarLong
// ---------------------------------------------------------------------------

/// Computes the exact byte length of a VarInt in wire format (1..=5 bytes).
#[inline]
pub const fn varint_size(mut val: i32) -> usize {
    let mut len = 0;
    loop {
        len += 1;
        val = ((val as u32) >> 7) as i32;
        if val == 0 {
            break;
        }
    }
    len
}

/// Writes a 32-bit integer as a base-128 VarInt (1..=5 bytes) to an `io::Write` target.
#[inline]
pub fn write_varint<W: Write>(mut val: i32, out: &mut W) -> std::io::Result<usize> {
    let mut bytes_written = 0;
    loop {
        let mut temp = (val as u32 & 0x7F) as u8;
        val = ((val as u32) >> 7) as i32;
        if val != 0 {
            temp |= 0x80;
        }
        out.write_all(&[temp])?;
        bytes_written += 1;
        if val == 0 {
            break;
        }
    }
    Ok(bytes_written)
}

/// Reads a 32-bit VarInt from an `io::Read` stream.
/// Rejects if more than 5 bytes are read.
#[inline]
pub fn read_varint<R: Read>(reader: &mut R) -> Result<i32, ProtocolError> {
    let mut num_read = 0;
    let mut result = 0i32;
    let mut buf = [0u8; 1];

    loop {
        if reader.read_exact(&mut buf).is_err() {
            return Err(ProtocolError::UnexpectedEof);
        }
        let read = buf[0];
        let value = (read & 0x7F) as i32;
        if num_read < 5 {
            result |= (value as u32).wrapping_shl((7 * num_read) as u32) as i32;
        }

        num_read += 1;
        if num_read > 5 {
            return Err(ProtocolError::VarIntTooBig);
        }

        if (read & 0x80) == 0 {
            break;
        }
    }

    Ok(result)
}

/// Computes the exact byte length of a VarLong in wire format (1..=10 bytes).
#[inline]
pub const fn varlong_size(mut val: i64) -> usize {
    let mut len = 0;
    loop {
        len += 1;
        val = ((val as u64) >> 7) as i64;
        if val == 0 {
            break;
        }
    }
    len
}

/// Writes a 64-bit integer as a base-128 VarLong (1..=10 bytes) to an `io::Write` target.
#[inline]
pub fn write_varlong<W: Write>(mut val: i64, out: &mut W) -> std::io::Result<usize> {
    let mut bytes_written = 0;
    loop {
        let mut temp = (val as u64 & 0x7F) as u8;
        val = ((val as u64) >> 7) as i64;
        if val != 0 {
            temp |= 0x80;
        }
        out.write_all(&[temp])?;
        bytes_written += 1;
        if val == 0 {
            break;
        }
    }
    Ok(bytes_written)
}

/// Reads a 64-bit VarLong from an `io::Read` stream.
/// Rejects if more than 10 bytes are read.
#[inline]
pub fn read_varlong<R: Read>(reader: &mut R) -> Result<i64, ProtocolError> {
    let mut num_read = 0;
    let mut result = 0i64;
    let mut buf = [0u8; 1];

    loop {
        if reader.read_exact(&mut buf).is_err() {
            return Err(ProtocolError::UnexpectedEof);
        }
        let read = buf[0];
        let value = (read & 0x7F) as i64;
        if num_read < 10 {
            result |= (value as u64).wrapping_shl((7 * num_read) as u32) as i64;
        }

        num_read += 1;
        if num_read > 10 {
            return Err(ProtocolError::VarLongTooBig);
        }

        if (read & 0x80) == 0 {
            break;
        }
    }

    Ok(result)
}

// ---------------------------------------------------------------------------
// Big-Endian Wire Primitives
// ---------------------------------------------------------------------------

#[inline]
pub fn write_bool<W: Write>(val: bool, out: &mut W) -> std::io::Result<()> {
    out.write_all(&[if val { 1 } else { 0 }])
}

#[inline]
pub fn read_bool<R: Read>(reader: &mut R) -> Result<bool, ProtocolError> {
    let mut b = [0u8; 1];
    reader.read_exact(&mut b).map_err(|_| ProtocolError::UnexpectedEof)?;
    Ok(b[0] != 0)
}

#[inline]
pub fn write_u8<W: Write>(val: u8, out: &mut W) -> std::io::Result<()> {
    out.write_all(&[val])
}

#[inline]
pub fn read_u8<R: Read>(reader: &mut R) -> Result<u8, ProtocolError> {
    let mut b = [0u8; 1];
    reader.read_exact(&mut b).map_err(|_| ProtocolError::UnexpectedEof)?;
    Ok(b[0])
}

#[inline]
pub fn write_i8<W: Write>(val: i8, out: &mut W) -> std::io::Result<()> {
    out.write_all(&[val as u8])
}

#[inline]
pub fn read_i8<R: Read>(reader: &mut R) -> Result<i8, ProtocolError> {
    let mut b = [0u8; 1];
    reader.read_exact(&mut b).map_err(|_| ProtocolError::UnexpectedEof)?;
    Ok(b[0] as i8)
}

#[inline]
pub fn write_i16_be<W: Write>(val: i16, out: &mut W) -> std::io::Result<()> {
    out.write_all(&val.to_be_bytes())
}

#[inline]
pub fn read_i16_be<R: Read>(reader: &mut R) -> Result<i16, ProtocolError> {
    let mut b = [0u8; 2];
    reader.read_exact(&mut b).map_err(|_| ProtocolError::UnexpectedEof)?;
    Ok(i16::from_be_bytes(b))
}

#[inline]
pub fn write_u16_be<W: Write>(val: u16, out: &mut W) -> std::io::Result<()> {
    out.write_all(&val.to_be_bytes())
}

#[inline]
pub fn read_u16_be<R: Read>(reader: &mut R) -> Result<u16, ProtocolError> {
    let mut b = [0u8; 2];
    reader.read_exact(&mut b).map_err(|_| ProtocolError::UnexpectedEof)?;
    Ok(u16::from_be_bytes(b))
}

#[inline]
pub fn write_i32_be<W: Write>(val: i32, out: &mut W) -> std::io::Result<()> {
    out.write_all(&val.to_be_bytes())
}

#[inline]
pub fn read_i32_be<R: Read>(reader: &mut R) -> Result<i32, ProtocolError> {
    let mut b = [0u8; 4];
    reader.read_exact(&mut b).map_err(|_| ProtocolError::UnexpectedEof)?;
    Ok(i32::from_be_bytes(b))
}

#[inline]
pub fn write_u32_be<W: Write>(val: u32, out: &mut W) -> std::io::Result<()> {
    out.write_all(&val.to_be_bytes())
}

#[inline]
pub fn read_u32_be<R: Read>(reader: &mut R) -> Result<u32, ProtocolError> {
    let mut b = [0u8; 4];
    reader.read_exact(&mut b).map_err(|_| ProtocolError::UnexpectedEof)?;
    Ok(u32::from_be_bytes(b))
}

#[inline]
pub fn write_i64_be<W: Write>(val: i64, out: &mut W) -> std::io::Result<()> {
    out.write_all(&val.to_be_bytes())
}

#[inline]
pub fn read_i64_be<R: Read>(reader: &mut R) -> Result<i64, ProtocolError> {
    let mut b = [0u8; 8];
    reader.read_exact(&mut b).map_err(|_| ProtocolError::UnexpectedEof)?;
    Ok(i64::from_be_bytes(b))
}

#[inline]
pub fn write_u64_be<W: Write>(val: u64, out: &mut W) -> std::io::Result<()> {
    out.write_all(&val.to_be_bytes())
}

#[inline]
pub fn read_u64_be<R: Read>(reader: &mut R) -> Result<u64, ProtocolError> {
    let mut b = [0u8; 8];
    reader.read_exact(&mut b).map_err(|_| ProtocolError::UnexpectedEof)?;
    Ok(u64::from_be_bytes(b))
}

#[inline]
pub fn write_f32_be<W: Write>(val: f32, out: &mut W) -> std::io::Result<()> {
    out.write_all(&val.to_be_bytes())
}

#[inline]
pub fn read_f32_be<R: Read>(reader: &mut R) -> Result<f32, ProtocolError> {
    let mut b = [0u8; 4];
    reader.read_exact(&mut b).map_err(|_| ProtocolError::UnexpectedEof)?;
    Ok(f32::from_be_bytes(b))
}

#[inline]
pub fn write_f64_be<W: Write>(val: f64, out: &mut W) -> std::io::Result<()> {
    out.write_all(&val.to_be_bytes())
}

#[inline]
pub fn read_f64_be<R: Read>(reader: &mut R) -> Result<f64, ProtocolError> {
    let mut b = [0u8; 8];
    reader.read_exact(&mut b).map_err(|_| ProtocolError::UnexpectedEof)?;
    Ok(f64::from_be_bytes(b))
}

// ---------------------------------------------------------------------------
// Protocol Structures (Position, UUID, String, ByteArray, Slot)
// ---------------------------------------------------------------------------

/// Encodes BlockPos as a single big-endian 64-bit integer.
#[inline]
pub fn write_position<W: Write>(pos: &BlockPos, out: &mut W) -> std::io::Result<()> {
    write_i64_be(pos.to_long(), out)
}

/// Decodes BlockPos from a single big-endian 64-bit integer.
#[inline]
pub fn read_position<R: Read>(reader: &mut R) -> Result<BlockPos, ProtocolError> {
    let raw = read_i64_be(reader)?;
    Ok(BlockPos::from_long(raw))
}

/// Protocol 340 UUID: two big-endian 64-bit integers (most_significant_bits, least_significant_bits).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub struct WireUuid {
    pub most_sig_bits: u64,
    pub least_sig_bits: u64,
}

impl WireUuid {
    pub const fn new(most_sig_bits: u64, least_sig_bits: u64) -> Self {
        Self {
            most_sig_bits,
            least_sig_bits,
        }
    }

    pub const fn from_u128(val: u128) -> Self {
        Self {
            most_sig_bits: (val >> 64) as u64,
            least_sig_bits: val as u64,
        }
    }

    pub const fn to_u128(&self) -> u128 {
        ((self.most_sig_bits as u128) << 64) | (self.least_sig_bits as u128)
    }
}

#[inline]
pub fn write_uuid<W: Write>(uuid: &WireUuid, out: &mut W) -> std::io::Result<()> {
    write_u64_be(uuid.most_sig_bits, out)?;
    write_u64_be(uuid.least_sig_bits, out)
}

#[inline]
pub fn read_uuid<R: Read>(reader: &mut R) -> Result<WireUuid, ProtocolError> {
    let msb = read_u64_be(reader)?;
    let lsb = read_u64_be(reader)?;
    Ok(WireUuid::new(msb, lsb))
}

/// Protocol 340 String:
/// - Prefix: VarInt UTF-8 byte length
/// - Payload: UTF-8 bytes
/// - Write hard cap: 32,767 bytes
/// - Read check: UTF-8 length must not exceed max_chars * 4 bytes, and char count <= max_chars.
pub const MAX_STRING_BYTES: usize = 32767;

pub fn write_string<W: Write>(s: &str, out: &mut W) -> Result<usize, ProtocolError> {
    let bytes = s.as_bytes();
    if bytes.len() > MAX_STRING_BYTES {
        return Err(ProtocolError::StringTooLong {
            length: bytes.len(),
            max: MAX_STRING_BYTES,
        });
    }
    let mut total = write_varint(bytes.len() as i32, out).map_err(|_| ProtocolError::UnexpectedEof)?;
    out.write_all(bytes).map_err(|_| ProtocolError::UnexpectedEof)?;
    total += bytes.len();
    Ok(total)
}

pub fn read_string<R: Read>(reader: &mut R, max_chars: usize) -> Result<String, ProtocolError> {
    let byte_len = read_varint(reader)?;
    if byte_len < 0 {
        return Err(ProtocolError::Custom("negative string length"));
    }
    let byte_len = byte_len as usize;

    if byte_len > max_chars * 4 {
        return Err(ProtocolError::StringTooLong {
            length: byte_len,
            max: max_chars * 4,
        });
    }

    let mut buf = vec![0u8; byte_len];
    reader.read_exact(&mut buf).map_err(|_| ProtocolError::UnexpectedEof)?;

    let s = String::from_utf8(buf).map_err(|_| ProtocolError::InvalidUtf8)?;
    if s.chars().count() > max_chars {
        return Err(ProtocolError::StringTooLong {
            length: s.chars().count(),
            max: max_chars,
        });
    }

    Ok(s)
}

pub fn write_byte_array<W: Write>(bytes: &[u8], out: &mut W) -> std::io::Result<usize> {
    let mut total = write_varint(bytes.len() as i32, out)?;
    out.write_all(bytes)?;
    total += bytes.len();
    Ok(total)
}

pub fn read_byte_array<R: Read>(reader: &mut R, max_len: usize) -> Result<Vec<u8>, ProtocolError> {
    let len = read_varint(reader)?;
    if len < 0 {
        return Err(ProtocolError::Custom("negative byte array length"));
    }
    let len = len as usize;
    if len > max_len {
        return Err(ProtocolError::BufferTooSmall {
            needed: len,
            available: max_len,
        });
    }
    let mut buf = vec![0u8; len];
    reader.read_exact(&mut buf).map_err(|_| ProtocolError::UnexpectedEof)?;
    Ok(buf)
}

/// Protocol 340 ItemStack (Slot):
/// If item id < 0 (-1), slot is empty.
/// Else: short item_id, byte count, short damage, and optional NBT (0 byte for null).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct WireSlot {
    pub item_id: i16,
    pub count: u8,
    pub damage: i16,
    pub nbt_bytes: Option<Vec<u8>>,
}

impl WireSlot {
    pub const EMPTY: Self = Self {
        item_id: -1,
        count: 0,
        damage: 0,
        nbt_bytes: None,
    };

    pub fn is_empty(&self) -> bool {
        self.item_id < 0
    }
}

pub fn write_slot<W: Write>(slot: &WireSlot, out: &mut W) -> std::io::Result<()> {
    if slot.is_empty() {
        write_i16_be(-1, out)?;
    } else {
        write_i16_be(slot.item_id, out)?;
        write_u8(slot.count, out)?;
        write_i16_be(slot.damage, out)?;
        match &slot.nbt_bytes {
            Some(nbt) if !nbt.is_empty() => out.write_all(nbt)?,
            _ => write_u8(0, out)?, // 0 byte indicates no NBT tag
        }
    }
    Ok(())
}

pub fn read_slot<R: Read>(reader: &mut R) -> Result<WireSlot, ProtocolError> {
    let item_id = read_i16_be(reader)?;
    if item_id < 0 {
        return Ok(WireSlot::EMPTY);
    }
    let count = read_u8(reader)?;
    let damage = read_i16_be(reader)?;

    // NBT read: peek first byte
    let tag_header = read_u8(reader)?;
    let nbt_bytes = if tag_header == 0 {
        None
    } else {
        // Tag present: single byte header 0x0A for compound tag
        // Return partial representation or placeholder
        Some(vec![tag_header])
    };

    Ok(WireSlot {
        item_id,
        count,
        damage,
        nbt_bytes,
    })
}
