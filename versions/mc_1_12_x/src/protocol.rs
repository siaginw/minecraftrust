//! Protocol 338/340 shapes. Framing/compression remains outside this prototype.
use crate::{AdapterError, Version};
use rustcraft_core::BlockPosition;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum Direction {
    ToClient,
    ToServer,
}
pub const fn keepalive_packet_id(direction: Direction) -> u8 {
    match direction {
        Direction::ToClient => 0x1f,
        Direction::ToServer => 0x0b,
    }
}
pub fn encode_keepalive_body<V: Version>(
    direction: Direction,
    value: i64,
    out: &mut [u8],
) -> Result<usize, AdapterError> {
    let mut payload = [0u8; 8];
    let len = V::encode_keepalive(value, &mut payload)?;
    if out.len() < len + 1 {
        return Err(AdapterError::OutputCapacity);
    }
    out[0] = keepalive_packet_id(direction);
    out[1..len + 1].copy_from_slice(&payload[..len]);
    Ok(len + 1)
}
pub fn decode_keepalive_body<V: Version>(
    direction: Direction,
    input: &[u8],
) -> Result<i64, AdapterError> {
    let (&id, body) = input.split_first().ok_or(AdapterError::Truncated)?;
    if id != keepalive_packet_id(direction) {
        return Err(AdapterError::WrongPacketId);
    }
    V::decode_keepalive(body)
}
pub(crate) fn encode_varint_keepalive(value: i64, out: &mut [u8]) -> Result<usize, AdapterError> {
    let value = i32::try_from(value).map_err(|_| AdapterError::KeepAliveOutOfRange)?;
    let mut remaining = value as u32;
    let mut scratch = [0u8; 5];
    let mut len = 0;
    loop {
        let byte = (remaining & 127) as u8;
        remaining >>= 7;
        scratch[len] = byte | if remaining != 0 { 128 } else { 0 };
        len += 1;
        if remaining == 0 {
            break;
        }
    }
    if out.len() < len {
        return Err(AdapterError::OutputCapacity);
    }
    out[..len].copy_from_slice(&scratch[..len]);
    Ok(len)
}
pub(crate) fn decode_varint_keepalive(input: &[u8]) -> Result<i64, AdapterError> {
    let mut value = 0u32;
    for index in 0..5 {
        let byte = *input.get(index).ok_or(AdapterError::Truncated)?;
        // Java int shifts discard upper bits on the fifth byte. Non-minimal
        // encodings up to five bytes are accepted, as by PacketBuffer VarInt.
        value |= u32::from(byte & 127).wrapping_shl(index as u32 * 7);
        if byte & 128 == 0 {
            if input.len() != index + 1 {
                return Err(AdapterError::TrailingBytes);
            }
            return Ok(i64::from(value as i32));
        }
    }
    Err(AdapterError::VarIntTooLong)
}
pub(crate) fn encode_long_keepalive(value: i64, out: &mut [u8]) -> Result<usize, AdapterError> {
    if out.len() < 8 {
        return Err(AdapterError::OutputCapacity);
    }
    out[..8].copy_from_slice(&value.to_be_bytes());
    Ok(8)
}
pub(crate) fn decode_long_keepalive(input: &[u8]) -> Result<i64, AdapterError> {
    match input.len() {
        0..=7 => Err(AdapterError::Truncated),
        8 => Ok(i64::from_be_bytes(input.try_into().unwrap())),
        _ => Err(AdapterError::TrailingBytes),
    }
}

pub fn encode_position(position: BlockPosition) -> Result<[u8; 8], AdapterError> {
    if !(-33_554_432..=33_554_431).contains(&position.x)
        || !(-33_554_432..=33_554_431).contains(&position.z)
        || !(-2048..=2047).contains(&position.y)
    {
        return Err(AdapterError::UnsupportedCoordinate);
    }
    let packed = ((position.x as u64 & 0x3ff_ffff) << 38)
        | ((position.y as u64 & 0xfff) << 26)
        | (position.z as u64 & 0x3ff_ffff);
    Ok(packed.to_be_bytes())
}
pub fn decode_position(bytes: [u8; 8]) -> BlockPosition {
    let packed = i64::from_be_bytes(bytes);
    BlockPosition {
        x: packed >> 38,
        y: (packed << 26) >> 52,
        z: (packed << 38) >> 38,
    }
}
