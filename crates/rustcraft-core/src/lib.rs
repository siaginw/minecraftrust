//! Version-neutral identities and spatial contracts. No JNI, wire encoding,
//! persistence format, Minecraft version selection, or authority policy.
//!
//! A 16-cubed section is a storage primitive, not a fixed world height. Runtime
//! IDs are dense indices within an immutable registry epoch. They must never be
//! serialized by casting to wire/persistence IDs.

use std::collections::BTreeMap;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum CoreError {
    EmptyWorld,
    CoordinateOverflow,
    InvalidResourceKey,
    InvalidProperty,
    DuplicateProperty,
    InvalidEpoch,
    EpochExhausted,
    DuplicateState,
    RegistryTooLarge,
    EpochMismatch,
    UnknownRuntimeState,
    SectionOutsideWorld,
    DuplicateSection,
}

impl std::fmt::Display for CoreError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{self:?}")
    }
}
impl std::error::Error for CoreError {}

#[derive(Clone, Copy, Debug, Eq, PartialEq, Ord, PartialOrd, Hash)]
pub struct SectionCoord(pub i32);

impl SectionCoord {
    pub fn from_block_y(y: i64) -> Result<Self, CoreError> {
        i32::try_from(y.div_euclid(16))
            .map(Self)
            .map_err(|_| CoreError::CoordinateOverflow)
    }
    pub const fn min_block_y(self) -> i64 {
        self.0 as i64 * 16
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct WorldBounds {
    min: SectionCoord,
    count: u32,
}

impl WorldBounds {
    pub fn new(min: SectionCoord, section_count: u32) -> Result<Self, CoreError> {
        if section_count == 0 {
            return Err(CoreError::EmptyWorld);
        }
        if i64::from(min.0) + i64::from(section_count) - 1 > i64::from(i32::MAX) {
            return Err(CoreError::CoordinateOverflow);
        }
        Ok(Self {
            min,
            count: section_count,
        })
    }
    pub const fn min_section(self) -> SectionCoord {
        self.min
    }
    pub const fn section_count(self) -> u32 {
        self.count
    }
    pub const fn max_section_exclusive(self) -> i64 {
        self.min.0 as i64 + self.count as i64
    }
    pub const fn min_block_y(self) -> i64 {
        self.min.min_block_y()
    }
    pub const fn max_block_y_exclusive(self) -> i64 {
        self.max_section_exclusive() * 16
    }
    pub fn contains_section(self, section: SectionCoord) -> bool {
        self.section_index(section).is_some()
    }
    pub fn section_index(self, section: SectionCoord) -> Option<u32> {
        let index = i64::from(section.0) - i64::from(self.min.0);
        (index >= 0 && index < i64::from(self.count)).then_some(index as u32)
    }
    pub fn section_at(self, index: u32) -> Option<SectionCoord> {
        (index < self.count)
            .then(|| SectionCoord((i64::from(self.min.0) + i64::from(index)) as i32))
    }
}

/// Coordinates have no network packing or world-height assumption.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct BlockPosition {
    pub x: i64,
    pub y: i64,
    pub z: i64,
}

/// A checked set of selected section coordinates; never a universal u16 mask.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct SectionSelection {
    bounds: WorldBounds,
    sections: Vec<SectionCoord>,
}

impl SectionSelection {
    pub fn new(bounds: WorldBounds, mut sections: Vec<SectionCoord>) -> Result<Self, CoreError> {
        if sections.iter().any(|s| !bounds.contains_section(*s)) {
            return Err(CoreError::SectionOutsideWorld);
        }
        sections.sort_unstable();
        if sections.windows(2).any(|pair| pair[0] == pair[1]) {
            return Err(CoreError::DuplicateSection);
        }
        Ok(Self { bounds, sections })
    }
    pub const fn bounds(&self) -> WorldBounds {
        self.bounds
    }
    pub fn sections(&self) -> &[SectionCoord] {
        &self.sections
    }
}

/// Exact qualified identity, not a version-specific resource-name validator.
/// The first colon separates namespace and path; neither component is folded,
/// defaulted, trimmed, or character-filtered. Empty components are representable.
/// An adapter must admit/canonicalize external names before registry construction.
/// This value must never be used directly as a filesystem path.
#[derive(Clone, Debug, Eq, PartialEq, Ord, PartialOrd, Hash)]
pub struct ResourceKey(String);

impl ResourceKey {
    pub fn parse(value: &str) -> Result<Self, CoreError> {
        if !value.contains(':') {
            return Err(CoreError::InvalidResourceKey);
        }
        Ok(Self(value.to_owned()))
    }
    pub fn as_str(&self) -> &str {
        &self.0
    }
    pub fn namespace(&self) -> &str {
        self.0.split_once(':').expect("qualified at construction").0
    }
    pub fn path(&self) -> &str {
        self.0.split_once(':').expect("qualified at construction").1
    }
}

/// Sorted named properties give order-independent semantic identity. Property
/// values remain exact strings, with no metadata or runtime-ID assumptions.
#[derive(Clone, Debug, Eq, PartialEq, Ord, PartialOrd, Hash)]
pub struct SemanticStateKey {
    name: ResourceKey,
    properties: Vec<(String, String)>,
}

impl SemanticStateKey {
    pub fn new(name: &str, mut properties: Vec<(String, String)>) -> Result<Self, CoreError> {
        let name = ResourceKey::parse(name)?;
        if properties.iter().any(|(k, v)| {
            k.is_empty()
                || v.is_empty()
                || k.chars().any(char::is_control)
                || v.chars().any(char::is_control)
        }) {
            return Err(CoreError::InvalidProperty);
        }
        properties.sort_unstable_by(|a, b| a.0.cmp(&b.0));
        if properties.windows(2).any(|p| p[0].0 == p[1].0) {
            return Err(CoreError::DuplicateProperty);
        }
        Ok(Self { name, properties })
    }
    pub fn simple(name: &str) -> Result<Self, CoreError> {
        Self::new(name, vec![])
    }
    pub fn name(&self) -> &ResourceKey {
        &self.name
    }
    pub fn properties(&self) -> &[(String, String)] {
        &self.properties
    }
}

#[derive(Clone, Debug, Eq, PartialEq, Ord, PartialOrd, Hash)]
pub struct BiomeKey(ResourceKey);
impl BiomeKey {
    pub fn new(name: &str) -> Result<Self, CoreError> {
        ResourceKey::parse(name).map(Self)
    }
    pub fn name(&self) -> &ResourceKey {
        &self.0
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq, Ord, PartialOrd, Hash)]
pub struct DenseRuntimeStateId(pub u32);
/// Wire and runtime identities cannot be implicitly interchanged.
/// ```compile_fail
/// use rustcraft_core::{DenseRuntimeStateId, WireStateId};
/// let wire: WireStateId = DenseRuntimeStateId(7);
/// ```
#[derive(Clone, Copy, Debug, Eq, PartialEq, Ord, PartialOrd, Hash)]
pub struct WireStateId(pub u64);
/// An adapter-scoped numeric persistence identity, not a universal disk format.
/// Formats with named palettes persist SemanticStateKey through their adapter.
/// ```compile_fail
/// use rustcraft_core::{PersistenceStateId, WireStateId};
/// let persisted: PersistenceStateId = WireStateId(7);
/// ```
#[derive(Clone, Copy, Debug, Eq, PartialEq, Ord, PartialOrd, Hash)]
pub struct PersistenceStateId(pub u64);

/// The issuer must assign registry_id uniquely within its session/world identity
/// domain. This value is context binding, not an unforgeable authority token.
#[derive(Clone, Copy, Debug, Eq, PartialEq, Ord, PartialOrd, Hash)]
pub struct RegistryEpoch {
    registry_id: u128,
    generation: u64,
}
impl RegistryEpoch {
    pub fn new(registry_id: u128, generation: u64) -> Result<Self, CoreError> {
        if registry_id == 0 || generation == 0 {
            return Err(CoreError::InvalidEpoch);
        }
        Ok(Self {
            registry_id,
            generation,
        })
    }
    pub const fn registry_id(self) -> u128 {
        self.registry_id
    }
    pub const fn generation(self) -> u64 {
        self.generation
    }
    pub fn checked_next(self) -> Result<Self, CoreError> {
        self.generation
            .checked_add(1)
            .map(|generation| Self { generation, ..self })
            .ok_or(CoreError::EpochExhausted)
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct RuntimeStateRef {
    pub epoch: RegistryEpoch,
    pub id: DenseRuntimeStateId,
}

/// Immutable after construction. Rebuild under a new epoch to change assignment.
#[derive(Clone, Debug)]
pub struct StateRegistry {
    epoch: RegistryEpoch,
    states: Vec<SemanticStateKey>,
    by_key: BTreeMap<SemanticStateKey, DenseRuntimeStateId>,
}
impl StateRegistry {
    pub fn new(epoch: RegistryEpoch, states: Vec<SemanticStateKey>) -> Result<Self, CoreError> {
        let mut by_key = BTreeMap::new();
        for (index, state) in states.iter().enumerate() {
            let id =
                DenseRuntimeStateId(u32::try_from(index).map_err(|_| CoreError::RegistryTooLarge)?);
            if by_key.insert(state.clone(), id).is_some() {
                return Err(CoreError::DuplicateState);
            }
        }
        Ok(Self {
            epoch,
            states,
            by_key,
        })
    }
    pub const fn epoch(&self) -> RegistryEpoch {
        self.epoch
    }
    pub fn states(&self) -> &[SemanticStateKey] {
        &self.states
    }
    pub fn lookup(&self, key: &SemanticStateKey) -> Option<RuntimeStateRef> {
        self.by_key.get(key).map(|id| RuntimeStateRef {
            epoch: self.epoch,
            id: *id,
        })
    }
    pub fn resolve(&self, state: RuntimeStateRef) -> Result<&SemanticStateKey, CoreError> {
        if state.epoch != self.epoch {
            return Err(CoreError::EpochMismatch);
        }
        self.states
            .get(state.id.0 as usize)
            .ok_or(CoreError::UnknownRuntimeState)
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum BiomeLayout {
    Columns {
        samples_per_edge: u16,
    },
    Volume {
        horizontal_samples_per_section: u16,
        vertical_samples_per_section: u16,
    },
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum StateEncodingKind {
    LegacyNumericRegistry,
    NamedRegistry,
}

/// Format shape only. This is deliberately separate from H4 capability evidence
/// and ownership admission; these fields cannot grant native execution authority.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct VersionCapabilities {
    pub bounds: WorldBounds,
    pub biome_layout: BiomeLayout,
    pub state_encoding: StateEncodingKind,
}
