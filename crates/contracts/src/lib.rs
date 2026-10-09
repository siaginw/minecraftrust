//! Coarse-grained Java/Rust boundary work contracts.
//! Strict rule: no per-block or per-entity FFI calls.

use core_types::{BlockPos, BlockStateId, ChunkPos};

pub struct PacketBatch {
    pub packets: Vec<(i32, Vec<u8>)>,
}

pub struct ChunkSnapshot {
    pub pos: ChunkPos,
    pub sections_mask: u16,
    pub block_states: Vec<BlockStateId>,
}

pub struct SaveBatch {
    pub chunks: Vec<ChunkSnapshot>,
}

pub struct BlockMutation {
    pub pos: BlockPos,
    pub new_state: BlockStateId,
}

pub struct MutationBatch {
    pub mutations: Vec<BlockMutation>,
}
