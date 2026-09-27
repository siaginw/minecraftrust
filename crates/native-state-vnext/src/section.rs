//! Layout experiment using u32 dense runtime identities, never wire IDs.
use rustcraft_core::{DenseRuntimeStateId, SectionCoord, WorldBounds};
use std::collections::{BTreeMap, HashMap};

pub const SECTION_CELLS: usize = 16 * 16 * 16;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Layout {
    Uniform,
    LocalPalette,
    DenseHot,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum SectionError {
    InvalidPolicy,
    WrongLength,
    CellOutsideSection,
    SectionOutsideWorld,
    DuplicateSection,
}

/// Explicit experimental policy; there is deliberately no production default.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct LayoutPolicy {
    pub promote_unique: usize,
    pub demote_unique: usize,
    pub linear_lookup_max: usize,
    pub cold_sweeps: u32,
    pub cold_write_budget: u64,
    pub hot_write_threshold: u64,
}
impl LayoutPolicy {
    pub fn validate(self) -> Result<Self, SectionError> {
        if self.demote_unique < 1
            || self.demote_unique >= self.promote_unique
            || self.promote_unique > SECTION_CELLS
            || self.linear_lookup_max == 0
            || self.linear_lookup_max > self.promote_unique
            || self.cold_sweeps == 0
            || self.hot_write_threshold <= self.cold_write_budget
        {
            return Err(SectionError::InvalidPolicy);
        }
        Ok(self)
    }
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct LayoutStats {
    pub promotions: u64,
    pub demotions: u64,
    pub linear_lookups: u64,
    pub hashed_lookups: u64,
    pub compactions: u64,
}

/// Exact vector payload sizes/capacities; excludes enum, allocation headers,
/// HashMap buckets/control bytes and allocator resident-memory overhead.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct PayloadFootprint {
    pub state_bytes: usize,
    pub index_bytes: usize,
    pub count_bytes: usize,
    pub lookup_capacity_entries: usize,
}

#[derive(Clone, Debug)]
struct Palette {
    values: Vec<DenseRuntimeStateId>,
    indices: Vec<u16>,
    counts: Vec<usize>,
    active: usize,
    lookup: Option<HashMap<DenseRuntimeStateId, u16>>,
    writes_since_sweep: u64,
}

#[derive(Clone, Debug)]
enum Representation {
    Uniform(DenseRuntimeStateId),
    Palette(Palette),
    Dense {
        values: Vec<DenseRuntimeStateId>,
        writes_since_sweep: u64,
        cold_streak: u32,
    },
}

#[derive(Clone, Debug)]
pub struct NativeSection {
    policy: LayoutPolicy,
    representation: Representation,
    stats: LayoutStats,
}

impl NativeSection {
    pub fn uniform(value: DenseRuntimeStateId, policy: LayoutPolicy) -> Result<Self, SectionError> {
        Ok(Self {
            policy: policy.validate()?,
            representation: Representation::Uniform(value),
            stats: LayoutStats::default(),
        })
    }

    pub fn from_dense(
        values: &[DenseRuntimeStateId],
        policy: LayoutPolicy,
    ) -> Result<Self, SectionError> {
        policy.validate()?;
        if values.len() != SECTION_CELLS {
            return Err(SectionError::WrongLength);
        }
        let palette = Palette::from_dense(values, policy.linear_lookup_max);
        let representation = if palette.active == 1 {
            Representation::Uniform(values[0])
        } else if palette.active >= policy.promote_unique {
            Representation::Dense {
                values: values.to_vec(),
                writes_since_sweep: 0,
                cold_streak: 0,
            }
        } else {
            Representation::Palette(palette)
        };
        Ok(Self {
            policy,
            representation,
            stats: LayoutStats::default(),
        })
    }

    pub fn layout(&self) -> Layout {
        match self.representation {
            Representation::Uniform(_) => Layout::Uniform,
            Representation::Palette(_) => Layout::LocalPalette,
            Representation::Dense { .. } => Layout::DenseHot,
        }
    }

    pub fn get(&self, index: usize) -> Result<DenseRuntimeStateId, SectionError> {
        if index >= SECTION_CELLS {
            return Err(SectionError::CellOutsideSection);
        }
        Ok(match &self.representation {
            Representation::Uniform(value) => *value,
            Representation::Palette(palette) => palette.values[usize::from(palette.indices[index])],
            Representation::Dense { values, .. } => values[index],
        })
    }

    /// Returns false for a semantic no-op; callers need not invalidate caches.
    pub fn set(&mut self, index: usize, value: DenseRuntimeStateId) -> Result<bool, SectionError> {
        if self.get(index)? == value {
            return Ok(false);
        }
        if let Representation::Uniform(old) = self.representation {
            let values = vec![old; SECTION_CELLS];
            self.representation = Representation::Palette(Palette::from_dense(
                &values,
                self.policy.linear_lookup_max,
            ));
        }
        match &mut self.representation {
            Representation::Uniform(_) => unreachable!(),
            Representation::Dense {
                values,
                writes_since_sweep,
                ..
            } => {
                values[index] = value;
                *writes_since_sweep = writes_since_sweep.saturating_add(1);
            }
            Representation::Palette(palette) => {
                palette.writes_since_sweep = palette.writes_since_sweep.saturating_add(1);
                if palette.values.len() == SECTION_CELLS && !palette.contains(value) {
                    let writes = palette.writes_since_sweep;
                    *palette = Palette::from_dense(&palette.dense(), self.policy.linear_lookup_max);
                    palette.writes_since_sweep = writes;
                    self.stats.compactions = self.stats.compactions.saturating_add(1);
                }
                let selected = if let Some(lookup) = &palette.lookup {
                    self.stats.hashed_lookups = self.stats.hashed_lookups.saturating_add(1);
                    lookup.get(&value).copied()
                } else {
                    self.stats.linear_lookups = self.stats.linear_lookups.saturating_add(1);
                    palette
                        .values
                        .iter()
                        .position(|candidate| *candidate == value)
                        .map(|i| i as u16)
                };
                let selected = match selected {
                    Some(selected) => selected,
                    None => {
                        let selected = u16::try_from(palette.values.len())
                            .expect("at most 4096 palette entries");
                        palette.values.push(value);
                        palette.counts.push(0);
                        if let Some(lookup) = &mut palette.lookup {
                            lookup.insert(value, selected);
                        }
                        selected
                    }
                };
                let old = usize::from(palette.indices[index]);
                palette.counts[old] -= 1;
                if palette.counts[old] == 0 {
                    palette.active -= 1;
                }
                let selected_index = usize::from(selected);
                if palette.counts[selected_index] == 0 {
                    palette.active += 1;
                }
                palette.counts[selected_index] += 1;
                palette.indices[index] = selected;
                if palette.lookup.is_none() && palette.values.len() > self.policy.linear_lookup_max
                {
                    palette.lookup = Some(
                        palette
                            .values
                            .iter()
                            .enumerate()
                            .map(|(index, value)| (*value, index as u16))
                            .collect(),
                    );
                }
                if palette.active >= self.policy.promote_unique
                    || palette.writes_since_sweep >= self.policy.hot_write_threshold
                {
                    let values = palette.dense();
                    self.representation = Representation::Dense {
                        values,
                        writes_since_sweep: palette.writes_since_sweep,
                        cold_streak: 0,
                    };
                    self.stats.promotions = self.stats.promotions.saturating_add(1);
                }
            }
        }
        Ok(true)
    }

    /// Demotion requires low cardinality and repeated cold sweeps. Writes above
    /// the explicit budget reset the streak; promotion and demotion differ.
    pub fn maintenance_sweep(&mut self) {
        match &mut self.representation {
            Representation::Uniform(_) => {}
            Representation::Palette(palette) => {
                palette.writes_since_sweep = 0;
                if palette.active == 1 {
                    self.representation =
                        Representation::Uniform(palette.values[usize::from(palette.indices[0])]);
                    self.stats.demotions = self.stats.demotions.saturating_add(1);
                } else if palette.values.len() > palette.active * 2 {
                    *palette = Palette::from_dense(&palette.dense(), self.policy.linear_lookup_max);
                    self.stats.compactions = self.stats.compactions.saturating_add(1);
                }
            }
            Representation::Dense {
                values,
                writes_since_sweep,
                cold_streak,
            } => {
                let palette = Palette::from_dense(values, self.policy.linear_lookup_max);
                if palette.active <= self.policy.demote_unique
                    && *writes_since_sweep <= self.policy.cold_write_budget
                {
                    *cold_streak = cold_streak.saturating_add(1);
                } else {
                    *cold_streak = 0;
                }
                *writes_since_sweep = 0;
                if *cold_streak >= self.policy.cold_sweeps {
                    self.representation = if palette.active == 1 {
                        Representation::Uniform(values[0])
                    } else {
                        Representation::Palette(palette)
                    };
                    self.stats.demotions = self.stats.demotions.saturating_add(1);
                }
            }
        }
    }

    pub fn dense(&self) -> Vec<DenseRuntimeStateId> {
        match &self.representation {
            Representation::Uniform(value) => vec![*value; SECTION_CELLS],
            Representation::Palette(palette) => palette.dense(),
            Representation::Dense { values, .. } => values.clone(),
        }
    }

    /// Deterministic first-spatial-occurrence ordering, independent of mutation
    /// history, lookup hash seed, or backing representation. Not wire packing.
    pub fn canonical_palette(&self) -> (Vec<DenseRuntimeStateId>, Vec<u16>) {
        let palette = Palette::from_dense(&self.dense(), self.policy.linear_lookup_max);
        (palette.values, palette.indices)
    }

    pub fn stats(&self) -> LayoutStats {
        self.stats
    }
    pub fn footprint(&self) -> PayloadFootprint {
        match &self.representation {
            Representation::Uniform(_) => PayloadFootprint {
                state_bytes: 4,
                index_bytes: 0,
                count_bytes: 0,
                lookup_capacity_entries: 0,
            },
            Representation::Palette(p) => PayloadFootprint {
                state_bytes: p.values.capacity() * 4,
                index_bytes: p.indices.capacity() * 2,
                count_bytes: p.counts.capacity() * std::mem::size_of::<usize>(),
                lookup_capacity_entries: p.lookup.as_ref().map_or(0, HashMap::capacity),
            },
            Representation::Dense { values, .. } => PayloadFootprint {
                state_bytes: values.capacity() * 4,
                index_bytes: 0,
                count_bytes: 0,
                lookup_capacity_entries: 0,
            },
        }
    }
}

impl Palette {
    fn from_dense(values: &[DenseRuntimeStateId], linear_max: usize) -> Self {
        let mut palette = Self {
            values: Vec::new(),
            indices: Vec::with_capacity(SECTION_CELLS),
            counts: Vec::new(),
            active: 0,
            lookup: None,
            writes_since_sweep: 0,
        };
        let mut lookup = HashMap::new();
        for value in values {
            let index = *lookup.entry(*value).or_insert_with(|| {
                let index = palette.values.len() as u16;
                palette.values.push(*value);
                palette.counts.push(0);
                index
            });
            palette.indices.push(index);
            palette.counts[usize::from(index)] += 1;
        }
        palette.active = palette.values.len();
        if palette.active > linear_max {
            palette.lookup = Some(lookup);
        }
        palette
    }
    fn dense(&self) -> Vec<DenseRuntimeStateId> {
        self.indices
            .iter()
            .map(|index| self.values[usize::from(*index)])
            .collect()
    }
    fn contains(&self, value: DenseRuntimeStateId) -> bool {
        self.lookup.as_ref().map_or_else(
            || self.values.contains(&value),
            |lookup| lookup.contains_key(&value),
        )
    }
}

#[derive(Clone, Debug)]
pub struct NativeChunk {
    bounds: WorldBounds,
    sections: BTreeMap<SectionCoord, NativeSection>,
}
impl NativeChunk {
    pub fn new(
        bounds: WorldBounds,
        sections: Vec<(SectionCoord, NativeSection)>,
    ) -> Result<Self, SectionError> {
        let mut selected = BTreeMap::new();
        for (coordinate, section) in sections {
            if !bounds.contains_section(coordinate) {
                return Err(SectionError::SectionOutsideWorld);
            }
            if selected.insert(coordinate, section).is_some() {
                return Err(SectionError::DuplicateSection);
            }
        }
        Ok(Self {
            bounds,
            sections: selected,
        })
    }
    pub fn bounds(&self) -> WorldBounds {
        self.bounds
    }
    pub fn sections(&self) -> impl Iterator<Item = (SectionCoord, &NativeSection)> {
        self.sections.iter().map(|(k, v)| (*k, v))
    }
    pub fn section(&self, coordinate: SectionCoord) -> Option<&NativeSection> {
        self.sections.get(&coordinate)
    }
    pub(crate) fn section_mut(&mut self, coordinate: SectionCoord) -> Option<&mut NativeSection> {
        self.sections.get_mut(&coordinate)
    }
}
