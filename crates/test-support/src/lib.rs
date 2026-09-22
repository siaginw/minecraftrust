//! Differential and parity test fixtures for Minecraft 1.12.2.

use core_types::ChunkPos;
use nbt::NbtTag;

pub fn create_dummy_chunk_nbt(pos: ChunkPos) -> NbtTag {
    let mut map = std::collections::HashMap::new();
    map.insert("xPos".to_string(), NbtTag::Int(pos.x));
    map.insert("zPos".to_string(), NbtTag::Int(pos.z));
    NbtTag::Compound(map)
}
