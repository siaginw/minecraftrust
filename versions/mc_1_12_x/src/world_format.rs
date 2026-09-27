//! Legacy Anvil identity shape only; no NBT or region-file encoder is installed.
use crate::AdapterError;
use rustcraft_core::BiomeKey;
use rustcraft_core::PersistenceStateId;
use std::collections::BTreeMap;

/// Blocks + optional Add nibble + Data nibble. This mapping is independent of
/// the connection registry's WireStateId; never derive one from the other.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct LegacyStoredState {
    block_id: u16,
    metadata: u8,
}

/// The 2D u8 biome representation is constrained to this legacy adapter.
pub struct LegacyBiomeMap(BTreeMap<BiomeKey, u8>);
impl LegacyBiomeMap {
    pub fn new(entries: Vec<(BiomeKey, u16)>) -> Result<Self, AdapterError> {
        let mut map = BTreeMap::new();
        for (key, value) in entries {
            let id = u8::try_from(value).map_err(|_| AdapterError::BiomeIdOutOfRange)?;
            if map.insert(key, id).is_some() {
                return Err(AdapterError::DuplicateMapping);
            }
        }
        Ok(Self(map))
    }
    pub fn columns(&self, keys: &[BiomeKey]) -> Result<LegacyBiomeColumns, AdapterError> {
        if keys.len() != 256 {
            return Err(AdapterError::InvalidBiomeCount);
        }
        let mut data = [0; 256];
        for (out, key) in data.iter_mut().zip(keys) {
            *out = *self.0.get(key).ok_or(AdapterError::MissingBiomeMapping)?;
        }
        Ok(LegacyBiomeColumns(data))
    }
}
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct LegacyBiomeColumns([u8; 256]);
impl LegacyBiomeColumns {
    pub const fn as_bytes(&self) -> &[u8; 256] {
        &self.0
    }
}
impl LegacyStoredState {
    pub fn new(block_id: u16, metadata: u8) -> Result<Self, AdapterError> {
        if block_id > 4095 || metadata > 15 {
            return Err(AdapterError::InvalidStoredState);
        }
        Ok(Self { block_id, metadata })
    }
    pub const fn block_id(self) -> u16 {
        self.block_id
    }
    pub const fn metadata(self) -> u8 {
        self.metadata
    }
    /// Adapter-local key for a persistence mapping table, not bytes on disk.
    pub const fn mapping_id(self) -> PersistenceStateId {
        PersistenceStateId((self.block_id as u64) << 4 | self.metadata as u64)
    }
    pub const fn low_block_byte(self) -> u8 {
        self.block_id as u8
    }
    pub const fn add_nibble(self) -> u8 {
        (self.block_id >> 8) as u8
    }
}
