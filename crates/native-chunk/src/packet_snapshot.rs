//! Owned, versioned offline packet input. This is not a live capture adapter.
//! All states remain u32 until explicit native-width/registry checks succeed.
use crate::{ChunkLifecycle, NativeChunk, NativeSection, PacketEncodeResult};
use std::collections::BTreeSet;

pub const SNAPSHOT_MAGIC: &[u8; 8] = b"RCSNAP01";
pub const SNAPSHOT_MAGIC_V2: &[u8; 8] = b"RCSNAP02";
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
    /// V2 telemetry: the SOURCE registry's cardinality and required bit
    /// width. Informational only -- V2 representability depends on the
    /// logical state ids, never on the source width. Zero on V1.
    pub source_registry_size: u32,
    pub source_registry_required_bits: u8,
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
        let magic = r.take(8)?;
        let version = r.u16()?;
        if magic == SNAPSHOT_MAGIC && version == 1 {
            return Self::from_transport_v1(input, r);
        }
        if magic == SNAPSHOT_MAGIC_V2 && version == 2 {
            return Self::from_transport_v2(input, r);
        }
        // Unknown magic or a known magic with an unknown version: fail
        // closed rather than reinterpreting bytes across versions.
        return Err(MalformedSnapshot);
    }

    /// RCSNAP01: unchanged. The header's global palette width IS the
    /// transport width here (the schema-1 body is width-coupled), so the
    /// 9..=16 gate stays exactly as qualified.
    fn from_transport_v1(input: &[u8], mut r: Reader) -> Result<Self, SnapshotRejection> {
        use SnapshotRejection::*;
        let _ = input;
        let version: u16 = 1; // dispatch already matched magic + version
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
        // Synthetic, qualified clean-Forge owned-oracle, or sealed live-SHADOW
        // owned inputs. Scope is provenance, not proof that an arbitrary caller
        // followed the Java writer/lease protocol; scope 3 carries additional
        // structural constraints checked below.
        let offline_scope = r.u8()?;
        if offline_scope != 1 && offline_scope != 2 && offline_scope != 3 {
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
        // Scope 3 (live shadow) admits only the vanilla storage model and only a
        // full-chunk packet whose requested mask is exactly the full 0xffff, per
        // the accepted live-publication contract.
        if offline_scope == 3 {
            if storage_model != 1 {
                return Err(UnsupportedStorage);
            }
            if full_chunk != (requested_mask == 0xffff) {
                return Err(MalformedSnapshot);
            }
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
                source_registry_size: 0,
                source_registry_required_bits: 0,
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

    /// RCSNAP02: the LOGICAL section representation, decoupled from the
    /// source registry's global width. Per section: an explicit u16 logical
    /// palette plus packed first-appearance indices; the header's width
    /// bytes are SOURCE TELEMETRY and never gate representability. A state
    /// id above 65535 cannot be represented in this domain at all -- the
    /// capture path excludes such chunks BEFORE transport, and the u16
    /// palette entries make an out-of-domain value unrepresentable here.
    fn from_transport_v2(input: &[u8], mut r: Reader) -> Result<Self, SnapshotRejection> {
        use SnapshotRejection::*;
        let _ = input;
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
        let source_bits = r.u8()?; // telemetry: any value acceptable
        let offline_scope = r.u8()?;
        if offline_scope != 1 && offline_scope != 2 && offline_scope != 3 {
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
        if offline_scope == 3 {
            if storage_model != 1 {
                return Err(UnsupportedStorage);
            }
            if full_chunk != (requested_mask == 0xffff) {
                return Err(MalformedSnapshot);
            }
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
        if count != expected {
            return Err(if count < expected {
                MissingSection
            } else {
                MaskMismatch
            });
        }
        let source_registry_size = r.u32()?;
        let source_registry_required_bits = r.u8()?;
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
            let palette_len = r.u16()? as usize;
            if palette_len == 0 || palette_len > 4096 {
                return Err(MalformedSnapshot);
            }
            let mut palette = Vec::with_capacity(palette_len);
            let mut distinct = BTreeSet::new();
            for _ in 0..palette_len {
                let id = r.u16()? as u32;
                if !distinct.insert(id) {
                    return Err(MalformedSnapshot); // duplicate palette entry
                }
                palette.push(id);
            }
            let bits = r.u8()? as usize;
            if bits == 0 || bits > 12 || (1usize << bits) < palette_len {
                return Err(MalformedSnapshot);
            }
            let words = r.u16()? as usize;
            if words != (4096 * bits + 63) / 64 {
                return Err(MalformedSnapshot);
            }
            let packed = r.take(words * 8)?;
            let mut word_values = vec![0u64; words];
            for (w, chunk) in packed.chunks_exact(8).enumerate() {
                let mut value = 0u64;
                for byte in chunk {
                    value = (value << 8) | (*byte as u64);
                }
                word_values[w] = value;
            }
            let mut states = Vec::with_capacity(4096);
            let mut non_air = 0usize;
            for cell in 0..4096usize {
                let position = cell * bits;
                let word = position / 64;
                let shift = position % 64;
                let mut value = word_values[word] >> shift;
                if shift + bits > 64 {
                    value |= word_values[word + 1] << (64 - shift);
                }
                let index = (value & ((1u64 << bits) - 1)) as usize;
                if index >= palette_len {
                    return Err(MalformedSnapshot); // index out of palette
                }
                let id = palette[index];
                if id != 0 {
                    non_air += 1;
                }
                states.push(id);
            }
            if non_air != refcount {
                return Err(RefcountMismatch);
            }
            if full_chunk && non_air == 0 {
                return Err(MaskMismatch);
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
                source_registry_size,
                source_registry_required_bits,
                version: 2,
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
                global_palette_bits: source_bits,
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

    /// Materialize a persistent or private NativeChunk from this owned packet snapshot.
    /// Used for initial seeding into the retained chunk registry or for one-shot encoding.
    pub fn to_native_chunk(&self) -> Result<NativeChunk, SnapshotRejection> {
        let m = &self.metadata;
        let mut chunk = NativeChunk::new(m.dimension, m.chunk_x, m.chunk_z, m.generation);
        chunk.primary_bit_mask = m.accepted_mask;
        if let Some(biomes) = self.biomes {
            chunk.set_biomes(&biomes);
        }
        for section in &self.sections {
            let mut states = [0u32; 4096];
            for (target, source) in states.iter_mut().zip(&section.states) {
                // RCSNAP01 wire decoding already rejected >0xFFFF ids at
                // parse time; native storage is full-width so this is a
                // widening copy (u32 -> u32).
                *target = *source;
            }
            let mut native = Box::new(NativeSection::new(section.y));
            native.replace_states(
                &states,
                Some(&section.block_light),
                section.sky_light.as_ref(),
            );
            chunk.sections[section.y as usize] = Some(native);
        }
        chunk.lifecycle = ChunkLifecycle::ActiveNative;
        Ok(chunk)
    }

    /// Materialize a private, unregistered NativeChunk for this encode only.
    /// Repeated calls cannot observe later Java arrays or retained native state.
    /// The explicit registry width belongs to the snapshot, not a global setter.
    pub fn encode(&self, output: &mut [u8]) -> Result<PacketEncodeResult, SnapshotRejection> {
        let m = &self.metadata;
        let direct_bits: u8 = if m.version >= 2 {
            16
        } else {
            m.global_palette_bits
        };
        let mut chunk = self.to_native_chunk()?;
        let mut offset = 0;
        chunk
            .encode_owned_packet_payload(m.skylight, m.full_chunk, output, &mut offset, direct_bits)
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
