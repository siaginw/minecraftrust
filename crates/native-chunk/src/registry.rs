//! Thread-safe native chunk registry and generation handle tracking.

use std::collections::HashMap;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, RwLock};
use crate::chunk::{NativeChunk, ChunkLifecycle};

/// M4.2A E: allocation/retention accounting, process-wide.
pub static STATS_SECTIONS_ALLOCATED: AtomicU64 = AtomicU64::new(0);
pub static STATS_SECTIONS_RELEASED: AtomicU64 = AtomicU64::new(0);
pub static STATS_CHUNKS_EVICTED: AtomicU64 = AtomicU64::new(0);

/// Bytes attributable to one resident section (see memory_model example).
pub const SECTION_RESIDENT_BYTES: u64 = 12368;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub struct ChunkKey {
    /// Owning dimension id. Without it, the same (cx, cz) in two dimensions
    /// alias one native chunk (M4.2A C1).
    pub dim: i32,
    pub cx: i32,
    pub cz: i32,
}

impl ChunkKey {
    #[inline(always)]
    pub fn new(dim: i32, cx: i32, cz: i32) -> Self {
        Self { dim, cx, cz }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ChunkHandle {
    pub key: ChunkKey,
    pub generation_id: u64,
}

pub struct ChunkRegistry {
    chunks: RwLock<HashMap<ChunkKey, Arc<RwLock<NativeChunk>>>>,
    next_generation: RwLock<u64>,
}

impl Default for ChunkRegistry {
    fn default() -> Self {
        Self::new()
    }
}

impl ChunkRegistry {
    pub fn new() -> Self {
        Self {
            chunks: RwLock::new(HashMap::new()),
            next_generation: RwLock::new(1),
        }
    }

    /// Allocates the next generation ID for chunk lifecycle tracking.
    pub fn next_generation_id(&self) -> u64 {
        let mut gen = self.next_generation.write().unwrap();
        let id = *gen;
        *gen += 1;
        id
    }

    /// Stores a newly generated NativeChunk, returning its generation handle.
    pub fn insert(&self, chunk: NativeChunk) -> ChunkHandle {
        let key = ChunkKey::new(chunk.dim, chunk.cx, chunk.cz);
        let gen_id = chunk.generation_id;
        let new_sections = chunk_active_sections(&chunk) as u64;
        let mut map = self.chunks.write().unwrap();
        if let Some(old) = map.get(&key) {
            // replacing a live chunk releases its sections
            STATS_SECTIONS_RELEASED.fetch_add(old.read().unwrap().active_section_count() as u64, Ordering::Relaxed);
        }
        STATS_SECTIONS_ALLOCATED.fetch_add(new_sections, Ordering::Relaxed);
        map.insert(key, Arc::new(RwLock::new(chunk)));
        ChunkHandle { key, generation_id: gen_id }
    }

    /// Retrieves an Arc reference to the NativeChunk if the generation matches and state is valid.
    pub fn get(&self, handle: &ChunkHandle) -> Option<Arc<RwLock<NativeChunk>>> {
        let map = self.chunks.read().unwrap();
        if let Some(chunk_arc) = map.get(&handle.key) {
            let chunk = chunk_arc.read().unwrap();
            if chunk.generation_id == handle.generation_id && chunk.lifecycle != ChunkLifecycle::Invalidated {
                return Some(Arc::clone(chunk_arc));
            }
        }
        None
    }

    /// Marks chunk as invalidated upon Java/mod mutations.
    pub fn invalidate(&self, key: ChunkKey) -> bool {
        let map = self.chunks.read().unwrap();
        if let Some(chunk_arc) = map.get(&key) {
            let mut chunk = chunk_arc.write().unwrap();
            chunk.set_lifecycle(ChunkLifecycle::Invalidated);
            return true;
        }
        false
    }

    /// Marks a chunk as mutated (versioned snapshot model): bumps
    /// mutation_generation and transitions ActiveNative -> Dirty.
    /// Unlike invalidate(), the native data stays present-but-stale
    /// until a refresh; consumers holding an old snapshot token detect it.
    pub fn mark_mutation(&self, key: ChunkKey) -> bool {
        let map = self.chunks.read().unwrap();
        if let Some(chunk_arc) = map.get(&key) {
            let mut chunk = chunk_arc.write().unwrap();
            chunk.mark_mutation();
            return true;
        }
        false
    }

    /// Section-granular dirty mark (M4.1 refresh model). Returns the new
    /// dirty mask, or -1 if the chunk is not registered.
    pub fn mark_section_mutation(&self, key: ChunkKey, section_y: u8) -> Option<u16> {
        let map = self.chunks.read().unwrap();
        if let Some(chunk_arc) = map.get(&key) {
            let mut chunk = chunk_arc.write().unwrap();
            chunk.mark_section_mutation(section_y);
            return Some(chunk.dirty_mask());
        }
        None
    }

    /// Refreshes one section in place from a Java-side snapshot.
    /// Returns the remaining dirty mask, or None if not registered.
    pub fn refresh_section(
        &self,
        key: ChunkKey,
        section_y: u8,
        states: &[u16; 4096],
        block_light: Option<&[u8; 2048]>,
        sky_light: Option<&[u8; 2048]>,
    ) -> Option<u16> {
        let map = self.chunks.read().unwrap();
        if let Some(chunk_arc) = map.get(&key) {
            let mut chunk = chunk_arc.write().unwrap();
            chunk.refresh_section(section_y, states, block_light, sky_light);
            return Some(chunk.dirty_mask());
        }
        None
    }

    /// Read-only view of the backing map (FFI internals).
    pub fn chunks_map(&self) -> &std::sync::RwLock<std::collections::HashMap<ChunkKey, std::sync::Arc<std::sync::RwLock<NativeChunk>>>> {
        &self.chunks
    }

    /// Current per-section dirty mask for a registered chunk.
    pub fn dirty_mask(&self, key: ChunkKey) -> Option<u16> {
        let map = self.chunks.read().unwrap();
        map.get(&key).map(|arc| arc.read().unwrap().dirty_mask())
    }

    /// Evicts an unloading chunk from native registry.
    pub fn remove(&self, key: ChunkKey) -> Option<Arc<RwLock<NativeChunk>>> {
        let mut map = self.chunks.write().unwrap();
        if let Some(arc) = map.remove(&key) {
            STATS_SECTIONS_RELEASED.fetch_add(arc.read().unwrap().active_section_count() as u64, Ordering::Relaxed);
            STATS_CHUNKS_EVICTED.fetch_add(1, Ordering::Relaxed);
            Some(arc)
        } else {
            None
        }
    }

    /// Current generation id for a live (non-invalidated) chunk, or 0.
    /// Consumer entry point when only coordinates are known (M4.2A C2).
    pub fn find_generation(&self, key: ChunkKey) -> u64 {
        let map = self.chunks.read().unwrap();
        match map.get(&key) {
            Some(arc) => {
                let c = arc.read().unwrap();
                if c.lifecycle == crate::chunk::ChunkLifecycle::Invalidated { 0 } else { c.generation_id }
            }
            None => 0,
        }
    }

    /// Retention accounting: (current_chunks, current_sections, retained_bytes_estimate).
    pub fn retention(&self) -> (u64, u64, u64) {
        let map = self.chunks.read().unwrap();
        let mut sections = 0u64;
        for arc in map.values() {
            sections += arc.read().unwrap().active_section_count() as u64;
        }
        (map.len() as u64, sections, sections * SECTION_RESIDENT_BYTES)
    }

    /// Returns count of registered chunks.
    pub fn count(&self) -> usize {
        let map = self.chunks.read().unwrap();
        map.len()
    }

    /// Clears all native chunks with release accounting (M4.2D lifecycle).
    /// Caller must guarantee no active readers hold handles that will be
    /// re-acquired afterwards; in-flight Arc readers keep their chunk alive
    /// until their read completes.
    pub fn clear(&self) {
        let mut map = self.chunks.write().unwrap();
        for arc in map.values() {
            STATS_SECTIONS_RELEASED.fetch_add(arc.read().unwrap().active_section_count() as u64, Ordering::Relaxed);
        }
        map.clear();
    }
}


fn chunk_active_sections(c: &NativeChunk) -> usize {
    c.sections.iter().filter(|s| s.is_some()).count()
}

impl NativeChunk {
    /// Number of resident (allocated) sections.
    pub fn active_section_count(&self) -> usize {
        chunk_active_sections(self)
    }
}

