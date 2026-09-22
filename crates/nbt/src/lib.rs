//! NBT binary tag model and codec contract.
//! Must preserve unknown tags and capability structures.

pub mod mutf8;
pub mod codec;
pub mod tape;
#[cfg(test)]
mod tests;

pub use codec::{NbtDecoder, NbtEncoder, NbtError, NbtTag, MAX_DEPTH};
pub use tape::NbtCursor;

pub trait NbtCodec {
    fn encode(&self, root: &NbtTag, out: &mut Vec<u8>) -> Result<(), NbtError>;
    fn decode(&self, bytes: &[u8]) -> Result<NbtTag, NbtError>;
}

pub struct StandardNbtCodec;

impl NbtCodec for StandardNbtCodec {
    fn encode(&self, root: &NbtTag, out: &mut Vec<u8>) -> Result<(), NbtError> {
        NbtEncoder::encode("", root, out)
    }

    fn decode(&self, bytes: &[u8]) -> Result<NbtTag, NbtError> {
        let (_, tag) = NbtDecoder::decode(bytes)?;
        Ok(tag)
    }
}
