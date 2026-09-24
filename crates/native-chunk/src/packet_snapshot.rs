//! Owned, versioned offline packet input. This is not a live capture adapter.
//! All states remain u32 until explicit native-width/registry checks succeed.
use crate::{NativeChunk, NativeSection, PacketEncodeResult};
use std::collections::BTreeSet;

pub const SNAPSHOT_MAGIC: &[u8; 8] = b"RCSNAP01";
pub const MAX_SNAPSHOT_BYTES: usize = 130 + 16 * (4 + 4096 * 4 + 2048 * 2) + 256;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum SnapshotRejection {
    MalformedSnapshot,
    UnsupportedStorage,
    ExtendedId,
    ChunkReplaced,
    CaptureChanged,
    OffThread,
    UnknownWriter,
    MissingSection,
    MaskMismatch,
    RefcountMismatch,
    Capacity,
    EncodeFailure,
}

impl SnapshotRejection {
    pub fn reason(self) -> &'static str {
        match self {
            Self::MalformedSnapshot => "FALLBACK_MALFORMED_SNAPSHOT",
            Self::UnsupportedStorage => "FALLBACK_UNSUPPORTED_STORAGE",
            Self::ExtendedId => "FALLBACK_EXTENDED_ID",
            Self::ChunkReplaced => "FALLBACK_CHUNK_REPLACED",
            Self::CaptureChanged => "FALLBACK_CAPTURE_CHANGED",
            Self::OffThread => "FALLBACK_OFF_THREAD",
            Self::UnknownWriter => "FALLBACK_UNKNOWN_WRITER",
            Self::MissingSection => "FALLBACK_MISSING_SECTION",
            Self::MaskMismatch => "FALLBACK_MASK_MISMATCH",
            Self::RefcountMismatch => "FALLBACK_REFCOUNT_MISMATCH",
            Self::Capacity => "FALLBACK_CAPACITY",
            Self::EncodeFailure => "FALLBACK_ENCODE_FAILURE",
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct SnapshotMetadata {
    pub version: u16,
    pub offline_scope: u8,
    pub dimension: i32,
    pub chunk_x: i32,
    pub chunk_z: i32,
    pub generation: u64,
    pub requested_mask: u16,
    pub accepted_mask: u16,
    pub full_chunk: bool,
    pub skylight: bool,
    pub storage_model: u8,
    pub global_palette_bits: u8,
    pub event_id: u64,
    pub owner_thread_id: u64,
    pub capture_thread_id: u64,
    pub epoch_start: u64,
    pub epoch_end: u64,
    pub incarnation_start: u64,
    pub incarnation_end: u64,
    /// Opaque identity of the Java-side provenance record, not a stability proof
    /// or an authentication token. Fixture replay verifies its external hashes.
    pub provenance_sha256: [u8; 32],
}

#[derive(Clone)]
struct OwnedSection {
    y: u8,
    states: Vec<u32>,
    block_light: [u8; 2048],
    sky_light: Option<[u8; 2048]>,
}

/// Once accepted, there are no mutable input references or retained-cache
/// handles. Private fields prevent callers from bypassing transport validation.
#[derive(Clone)]
pub struct OwnedPacketSnapshot {
    metadata: SnapshotMetadata,
    sections: Vec<OwnedSection>,
    biomes: Option<[u8; 256]>,
}

impl OwnedPacketSnapshot {
    pub fn metadata(&self) -> &SnapshotMetadata {
        &self.metadata
    }

    pub fn from_transport(input: &[u8]) -> Result<Self, SnapshotRejection> {
        use SnapshotRejection::*;
        if input.len() > MAX_SNAPSHOT_BYTES {
            return Err(MalformedSnapshot);
        }
        let mut r = Reader(input);
        if r.take(8)? != SNAPSHOT_MAGIC {
            return Err(MalformedSnapshot);
        }
        let version = r.u16()?;
        if version != 1 {
            return Err(MalformedSnapshot);
        }
        let flags = r.u8()?;
        if flags & !3 != 0 {
            return Err(MalformedSnapshot);
        }
        let full_chunk = flags & 1 != 0;
        let skylight = flags & 2 != 0;
        let storage_model = r.u8()?;
        if storage_model != 1 && storage_model != 2 {
            return Err(UnsupportedStorage);
        }
        let global_palette_bits = r.u8()?;
        if !(9..=16).contains(&global_palette_bits) {
            return Err(UnsupportedStorage);
        }
        // Only synthetic or qualified clean-Forge owned-oracle inputs. Not proof
        // that an arbitrary caller followed the Java writer/lease protocol.
        let offline_scope = r.u8()?;
        if offline_scope != 1 && offline_scope != 2 {
            return Err(UnknownWriter);
        }
        if r.u16()? != 0 {
            return Err(MalformedSnapshot);
        }
        let dimension = r.u32()? as i32;
        let chunk_x = r.u32()? as i32;
        let chunk_z = r.u32()? as i32;
        let generation = r.u64()?;
        if !positive_java_id(generation) {
            return Err(ChunkReplaced);
        }
        let requested_mask = r.u16()?;
        let accepted_mask = r.u16()?;
        if accepted_mask & !requested_mask != 0 {
            return Err(MaskMismatch);
        }
        let event_id = r.u64()?;
        if !positive_java_id(event_id) {
            return Err(MalformedSnapshot);
        }
        let owner_thread_id = r.u64()?;
        let capture_thread_id = r.u64()?;
        if !positive_java_id(owner_thread_id) || owner_thread_id != capture_thread_id {
            return Err(OffThread);
        }
        let epoch_start = r.u64()?;
        let epoch_end = r.u64()?;
        if epoch_start > i64::MAX as u64 || epoch_start != epoch_end {
            return Err(CaptureChanged);
        }
        let incarnation_start = r.u64()?;
        let incarnation_end = r.u64()?;
        if !positive_java_id(incarnation_start) || incarnation_start != incarnation_end {
            return Err(ChunkReplaced);
        }
        let provenance_sha256 = r.take(32)?.try_into().unwrap();
        let count = r.u16()? as usize;
        let expected = accepted_mask.count_ones() as usize;
        if count < expected {
            return Err(MissingSection);
        }
        if count > expected {
            return Err(MaskMismatch);
        }
        let mut sections = Vec::with_capacity(count);
        for y in 0..16u8 {
            if accepted_mask & (1 << y) == 0 {
                continue;
            }
            if r.u8()? != y || r.u8()? != 0 {
                return Err(MaskMismatch);
            }
            let refcount = r.u16()? as usize;
            if refcount > 4096 {
                return Err(RefcountMismatch);
            }
            let mut states = Vec::with_capacity(4096);
            let mut non_air = 0;
            let mut distinct = BTreeSet::new();
            for _ in 0..4096 {
                let id = r.u32()?;
                if id > u16::MAX as u32 {
                    return Err(ExtendedId);
                }
                if id != 0 {
                    non_air += 1;
                    distinct.insert(id);
                }
                states.push(id);
            }
            if non_air != refcount {
                return Err(RefcountMismatch);
            }
            if full_chunk && non_air == 0 {
                return Err(MaskMismatch);
            }
            // The existing section encoder includes air in its local palette.
            // Once that palette exceeds 256 entries it writes raw global IDs.
            if distinct.len() >= 256 && states.iter().any(|id| *id >= (1u32 << global_palette_bits))
            {
                return Err(ExtendedId);
            }
            let block_light = r.take(2048)?.try_into().unwrap();
            let sky_light = if skylight {
                Some(r.take(2048)?.try_into().unwrap())
            } else {
                None
            };
            sections.push(OwnedSection {
                y,
                states,
                block_light,
                sky_light,
            });
        }
        let biomes = if full_chunk {
            Some(r.take(256)?.try_into().unwrap())
        } else {
            None
        };
        if !r.0.is_empty() {
            return Err(MalformedSnapshot);
        }
        Ok(Self {
            metadata: SnapshotMetadata {
                version,
                offline_scope,
                dimension,
                chunk_x,
                chunk_z,
                generation,
                requested_mask,
                accepted_mask,
                full_chunk,
                skylight,
                storage_model,
                global_palette_bits,
                event_id,
                owner_thread_id,
                capture_thread_id,
                epoch_start,
                epoch_end,
                incarnation_start,
                incarnation_end,
                provenance_sha256,
            },
            sections,
            biomes,
        })
    }

    /// Materialize a private, unregistered NativeChunk for this encode only.
    /// Repeated calls cannot observe later Java arrays or retained native state.
    /// The explicit registry width belongs to the snapshot, not a global setter.
    pub fn encode(&self, output: &mut [u8]) -> Result<PacketEncodeResult, SnapshotRejection> {
        let m = &self.metadata;
        let mut chunk = NativeChunk::new(m.dimension, m.chunk_x, m.chunk_z, m.generation);
        chunk.primary_bit_mask = m.accepted_mask;
        if let Some(biomes) = self.biomes {
            chunk.biomes = biomes;
        }
        for section in &self.sections {
            let mut states = [0u16; 4096];
            for (target, source) in states.iter_mut().zip(&section.states) {
                *target = u16::try_from(*source).map_err(|_| SnapshotRejection::ExtendedId)?;
            }
            let mut native = Box::new(NativeSection::new(section.y));
            native.replace_states(
                &states,
                Some(&section.block_light),
                section.sky_light.as_ref(),
            );
            // Direct private construction avoids retained-registry accounting
            // and preserves present-empty sections in partial packets.
            chunk.sections[section.y as usize] = Some(native);
        }
        let mut offset = 0;
        chunk
            .encode_owned_packet_payload(
                m.skylight,
                m.full_chunk,
                output,
                &mut offset,
                m.global_palette_bits,
            )
            .map_err(|error| match error {
                "Packet mask selects a missing section" => SnapshotRejection::MissingSection,
                "Output buffer overflow" | "Output buffer overflow writing biomes" => {
                    SnapshotRejection::Capacity
                }
                _ => SnapshotRejection::EncodeFailure,
            })
    }
}

fn positive_java_id(id: u64) -> bool {
    id != 0 && id <= i64::MAX as u64
}

struct Reader<'a>(&'a [u8]);
impl<'a> Reader<'a> {
    fn take(&mut self, count: usize) -> Result<&'a [u8], SnapshotRejection> {
        if count > self.0.len() {
            return Err(SnapshotRejection::MalformedSnapshot);
        }
        let (head, tail) = self.0.split_at(count);
        self.0 = tail;
        Ok(head)
    }
    fn u8(&mut self) -> Result<u8, SnapshotRejection> {
        Ok(self.take(1)?[0])
    }
    fn u16(&mut self) -> Result<u16, SnapshotRejection> {
        Ok(u16::from_be_bytes(self.take(2)?.try_into().unwrap()))
    }
    fn u32(&mut self) -> Result<u32, SnapshotRejection> {
        Ok(u32::from_be_bytes(self.take(4)?.try_into().unwrap()))
    }
    fn u64(&mut self) -> Result<u64, SnapshotRejection> {
        Ok(u64::from_be_bytes(self.take(8)?.try_into().unwrap()))
    }
}
