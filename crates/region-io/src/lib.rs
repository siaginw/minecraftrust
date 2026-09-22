//! Anvil format (.mca) region persistence engine.

use core_types::ChunkPos;
use nbt::NbtTag;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub struct RegionPos {
    pub x: i32,
    pub z: i32,
}

impl RegionPos {
    pub const fn from_chunk(chunk: ChunkPos) -> Self {
        Self {
            x: chunk.x >> 5,
            z: chunk.z >> 5,
        }
    }
}

pub trait RegionStorage {
    fn load_chunk_nbt(&mut self, pos: ChunkPos) -> Result<Option<NbtTag>, &'static str>;
    fn save_chunk_nbt(&mut self, pos: ChunkPos, data: &NbtTag) -> Result<(), &'static str>;
}
