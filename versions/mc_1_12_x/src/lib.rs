//! Architecture/fixture adapters only. No connection, gameplay, Forge loading,
//! packet authority, persistent chunk ownership or production call sites.

pub mod mc_1_12_1;
pub mod mc_1_12_2;
pub mod protocol;
pub mod world_format;

use rustcraft_core::*;
use std::collections::BTreeMap;
use std::marker::PhantomData;
use world_format::LegacyStoredState;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum AdapterError {
    UnsupportedProtocol,
    UnsupportedBounds,
    UnsupportedCoordinate,
    DuplicateMapping,
    MissingStateMapping,
    ExtraStateMapping,
    WireStateOutOfRange,
    InvalidStoredState,
    EpochMismatch,
    UnknownRuntimeState,
    OutputCapacity,
    KeepAliveOutOfRange,
    Truncated,
    TrailingBytes,
    VarIntTooLong,
    WrongPacketId,
    MissingBiomeMapping,
    BiomeIdOutOfRange,
    InvalidBiomeCount,
}
impl std::fmt::Display for AdapterError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{self:?}")
    }
}
impl std::error::Error for AdapterError {}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct VersionMetadata {
    pub release: &'static str,
    pub protocol: u32,
    pub data_version: u32,
}

mod sealed {
    pub trait Sealed {}
}
/// Marker specialization is chosen once at session/job admission. No per-block
/// dynamic dispatch or version branch is required by a state mapping job.
pub trait Version: sealed::Sealed {
    const METADATA: VersionMetadata;
    fn encode_keepalive(value: i64, output: &mut [u8]) -> Result<usize, AdapterError>;
    fn decode_keepalive(input: &[u8]) -> Result<i64, AdapterError>;
}

pub fn capabilities() -> VersionCapabilities {
    VersionCapabilities {
        bounds: WorldBounds::new(SectionCoord(0), 16).expect("static legacy bounds"),
        biome_layout: BiomeLayout::Columns {
            samples_per_edge: 16,
        },
        state_encoding: StateEncodingKind::LegacyNumericRegistry,
    }
}

/// This u16 mask belongs exclusively to the legacy adapter.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct LegacySectionMask(u16);
impl LegacySectionMask {
    pub fn from_selection(selection: &SectionSelection) -> Result<Self, AdapterError> {
        if selection.bounds() != capabilities().bounds {
            return Err(AdapterError::UnsupportedBounds);
        }
        let mut mask = 0;
        for section in selection.sections() {
            mask |= 1u16 << section.0;
        }
        Ok(Self(mask))
    }
    pub const fn bits(self) -> u16 {
        self.0
    }
}

#[derive(Clone, Debug)]
pub struct StateMapping {
    pub semantic: SemanticStateKey,
    pub wire: WireStateId,
    pub persistence: LegacyStoredState,
}

/// Explicit per-world registry translation. Wire IDs are supplied by the admitted
/// registry, never inferred from runtime indices or persistence metadata.
pub struct LegacyWorldAdapter<V: Version> {
    epoch: RegistryEpoch,
    wire: Vec<WireStateId>,
    persistence: Vec<LegacyStoredState>,
    direct_palette_bits: u8,
    version: PhantomData<V>,
}
impl<V: Version> LegacyWorldAdapter<V> {
    pub fn bind(
        registry: &StateRegistry,
        mappings: Vec<StateMapping>,
    ) -> Result<Self, AdapterError> {
        let mut by_key = BTreeMap::new();
        for mapping in mappings {
            if mapping.wire.0 > i32::MAX as u64 {
                return Err(AdapterError::WireStateOutOfRange);
            }
            if by_key
                .insert(mapping.semantic, (mapping.wire, mapping.persistence))
                .is_some()
            {
                return Err(AdapterError::DuplicateMapping);
            }
        }
        let mut wire = Vec::with_capacity(registry.states().len());
        let mut persistence = Vec::with_capacity(registry.states().len());
        for key in registry.states() {
            let (w, p) = by_key
                .remove(key)
                .ok_or(AdapterError::MissingStateMapping)?;
            wire.push(w);
            persistence.push(p);
        }
        if !by_key.is_empty() {
            return Err(AdapterError::ExtraStateMapping);
        }
        let max = wire.iter().map(|id| id.0).max().unwrap_or(0);
        // Legacy registry bit width is per bound world, never a process global.
        let direct_palette_bits = (64 - max.leading_zeros()).max(5) as u8;
        Ok(Self {
            epoch: registry.epoch(),
            wire,
            persistence,
            direct_palette_bits,
            version: PhantomData,
        })
    }
    pub const fn epoch(&self) -> RegistryEpoch {
        self.epoch
    }
    pub const fn direct_palette_bits(&self) -> u8 {
        self.direct_palette_bits
    }
    pub fn admit_job<'a>(
        &'a self,
        epoch: RegistryEpoch,
        states: &'a [DenseRuntimeStateId],
    ) -> Result<StateMappingJob<'a, V>, AdapterError> {
        if epoch != self.epoch {
            return Err(AdapterError::EpochMismatch);
        }
        if states
            .iter()
            .any(|state| state.0 as usize >= self.wire.len())
        {
            return Err(AdapterError::UnknownRuntimeState);
        }
        Ok(StateMappingJob {
            adapter: self,
            states,
        })
    }
}

pub struct StateMappingJob<'a, V: Version> {
    adapter: &'a LegacyWorldAdapter<V>,
    states: &'a [DenseRuntimeStateId],
}
impl<V: Version> StateMappingJob<'_, V> {
    pub fn write_wire(&self, out: &mut [WireStateId]) -> Result<usize, AdapterError> {
        if out.len() < self.states.len() {
            return Err(AdapterError::OutputCapacity);
        }
        for (dst, id) in out.iter_mut().zip(self.states) {
            *dst = self.adapter.wire[id.0 as usize];
        }
        Ok(self.states.len())
    }
    pub fn write_persistence(&self, out: &mut [LegacyStoredState]) -> Result<usize, AdapterError> {
        if out.len() < self.states.len() {
            return Err(AdapterError::OutputCapacity);
        }
        for (dst, id) in out.iter_mut().zip(self.states) {
            *dst = self.adapter.persistence[id.0 as usize];
        }
        Ok(self.states.len())
    }
}

/// An outer dispatch example. Match once, then run a monomorphized session/job.
/// Unsupported versions have no implicit nearest-version fallback.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum SelectedVersion {
    Mc1_12_1,
    Mc1_12_2,
}
impl SelectedVersion {
    pub fn for_protocol(protocol: u32) -> Result<Self, AdapterError> {
        match protocol {
            338 => Ok(Self::Mc1_12_1),
            340 => Ok(Self::Mc1_12_2),
            _ => Err(AdapterError::UnsupportedProtocol),
        }
    }
}
