use native_state_vnext::section::*;
use rustcraft_core::{DenseRuntimeStateId, SectionCoord, WorldBounds};

fn policy() -> LayoutPolicy {
    LayoutPolicy {
        promote_unique: 128,
        demote_unique: 32,
        linear_lookup_max: 16,
        cold_sweeps: 2,
        cold_write_budget: 64,
        hot_write_threshold: 2048,
    }
}

#[test]
fn uniform_local_and_dense_preserve_full_u32_ids() {
    for count in [1, 8, 64, 256, 4096] {
        let source: Vec<_> = (0..SECTION_CELLS)
            .map(|i| DenseRuntimeStateId(70_000 + (i % count) as u32))
            .collect();
        let mut section = NativeSection::from_dense(&source, policy()).unwrap();
        assert_eq!(section.dense(), source);
        section.set(4095, DenseRuntimeStateId(u32::MAX)).unwrap();
        assert_eq!(section.get(4095), Ok(DenseRuntimeStateId(u32::MAX)));
        let (palette, indices) = section.canonical_palette();
        for (i, index) in indices.iter().enumerate() {
            assert_eq!(section.get(i).unwrap(), palette[usize::from(*index)]);
        }
    }
}

#[test]
fn independent_dense_reference_matches_seeded_mutations_and_maintenance() {
    for promote in [16, 64, 256, 1024] {
        let config = LayoutPolicy {
            promote_unique: promote,
            demote_unique: promote / 4,
            linear_lookup_max: 8,
            cold_sweeps: 2,
            cold_write_budget: 8,
            hot_write_threshold: 1024,
        };
        let mut section = NativeSection::uniform(DenseRuntimeStateId(0), config).unwrap();
        let mut reference = vec![DenseRuntimeStateId(0); SECTION_CELLS];
        let mut random = 0x8765_4321_u64;
        for step in 0..30_000 {
            random = random
                .wrapping_mul(6364136223846793005)
                .wrapping_add(1442695040888963407);
            let index = ((random >> 32) as usize) % SECTION_CELLS;
            let value = DenseRuntimeStateId(70_000 + (random as u32 % 400));
            let changed = reference[index] != value;
            assert_eq!(section.set(index, value), Ok(changed));
            reference[index] = value;
            if step % 257 == 0 {
                section.maintenance_sweep();
                assert_eq!(section.dense(), reference);
            }
        }
        assert_eq!(section.dense(), reference);
    }
}

#[test]
fn dense_demotion_needs_distinct_low_threshold_and_repeated_cold_sweeps() {
    let source: Vec<_> = (0..SECTION_CELLS)
        .map(|i| DenseRuntimeStateId((i % 128) as u32))
        .collect();
    let mut section = NativeSection::from_dense(&source, policy()).unwrap();
    assert_eq!(section.layout(), Layout::DenseHot);
    for i in 0..SECTION_CELLS {
        section
            .set(i, DenseRuntimeStateId((i % 64) as u32))
            .unwrap();
    }
    for _ in 0..4 {
        section.maintenance_sweep();
    }
    assert_eq!(section.layout(), Layout::DenseHot); // 64 remains above low threshold32.
    for i in 0..SECTION_CELLS {
        section.set(i, DenseRuntimeStateId((i % 8) as u32)).unwrap();
    }
    section.maintenance_sweep();
    assert_eq!(section.layout(), Layout::DenseHot); // hot writes reset.
    section.maintenance_sweep();
    assert_eq!(section.layout(), Layout::DenseHot); // first cold.
    section.maintenance_sweep();
    assert_eq!(section.layout(), Layout::LocalPalette);
    for i in 0..80 {
        section
            .set(i, DenseRuntimeStateId(1000 + i as u32))
            .unwrap();
    }
    assert_eq!(section.layout(), Layout::LocalPalette); // below promotion128.
}

#[test]
fn palette_history_compaction_and_hash_lookup_preserve_canonical_order() {
    let config = LayoutPolicy {
        hot_write_threshold: u64::MAX,
        ..policy()
    };
    let mut section = NativeSection::uniform(DenseRuntimeStateId(7), config).unwrap();
    for value in 0..10_000 {
        section
            .set(0, DenseRuntimeStateId(value + 100_000))
            .unwrap();
    }
    assert_eq!(section.layout(), Layout::LocalPalette);
    assert!(section.stats().linear_lookups > 0);
    assert!(section.stats().hashed_lookups > 0);
    assert!(section.stats().compactions > 0);
    let same = NativeSection::from_dense(&section.dense(), policy()).unwrap();
    assert_eq!(section.canonical_palette(), same.canonical_palette());
    section.set(0, DenseRuntimeStateId(7)).unwrap();
    section.maintenance_sweep();
    assert_eq!(section.layout(), Layout::Uniform);
}

#[test]
fn malformed_policy_length_cell_and_section_coordinates_refuse() {
    let mut config = policy();
    config.demote_unique = config.promote_unique;
    assert_eq!(
        NativeSection::uniform(DenseRuntimeStateId(0), config).unwrap_err(),
        SectionError::InvalidPolicy
    );
    assert_eq!(
        NativeSection::from_dense(&[], policy()).unwrap_err(),
        SectionError::WrongLength
    );
    let mut section = NativeSection::uniform(DenseRuntimeStateId(0), policy()).unwrap();
    assert_eq!(
        section.set(4096, DenseRuntimeStateId(1)),
        Err(SectionError::CellOutsideSection)
    );
    let bounds = WorldBounds::new(SectionCoord(-32), 64).unwrap();
    assert_eq!(
        NativeChunk::new(bounds, vec![(SectionCoord(32), section.clone())]).unwrap_err(),
        SectionError::SectionOutsideWorld
    );
    assert_eq!(
        NativeChunk::new(
            bounds,
            vec![
                (SectionCoord(-32), section.clone()),
                (SectionCoord(-32), section)
            ]
        )
        .unwrap_err(),
        SectionError::DuplicateSection
    );
}

#[test]
fn arbitrary_signed_vertical_bounds_and_sparse_sections_are_supported() {
    let bounds = WorldBounds::new(SectionCoord(-32), 64).unwrap();
    let section = NativeSection::uniform(DenseRuntimeStateId(70000), policy()).unwrap();
    let chunk = NativeChunk::new(
        bounds,
        vec![
            (SectionCoord(-32), section.clone()),
            (SectionCoord(31), section),
        ],
    )
    .unwrap();
    assert_eq!(
        chunk.sections().map(|(y, _)| y).collect::<Vec<_>>(),
        vec![SectionCoord(-32), SectionCoord(31)]
    );
    assert!(chunk.section(SectionCoord(0)).is_none());
    assert_eq!(chunk.bounds(), bounds);
}

#[test]
fn low_cardinality_hot_writes_promote_then_cold_sweeps_demote_with_hysteresis() {
    let config = LayoutPolicy {
        hot_write_threshold: 128,
        cold_write_budget: 8,
        ..policy()
    };
    let mut section = NativeSection::uniform(DenseRuntimeStateId(0), config).unwrap();
    for i in 0..128 {
        section.set(i, DenseRuntimeStateId(1)).unwrap();
    }
    assert_eq!(section.layout(), Layout::DenseHot);
    section.maintenance_sweep();
    assert_eq!(section.layout(), Layout::DenseHot);
    section.maintenance_sweep();
    assert_eq!(section.layout(), Layout::DenseHot);
    section.maintenance_sweep();
    assert_eq!(section.layout(), Layout::LocalPalette);
    for i in 128..200 {
        section.set(i, DenseRuntimeStateId(1)).unwrap();
    }
    assert_eq!(section.layout(), Layout::LocalPalette);
    assert_eq!(section.stats().promotions, 1);
    assert_eq!(section.stats().demotions, 1);
}
