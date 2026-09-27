use rustcraft_core::*;

#[test]
fn negative_section_coordinates_use_floor_division() {
    for (block, section) in [
        (-65, -5),
        (-64, -4),
        (-17, -2),
        (-16, -1),
        (-1, -1),
        (0, 0),
        (15, 0),
        (16, 1),
    ] {
        assert_eq!(
            SectionCoord::from_block_y(block).unwrap(),
            SectionCoord(section)
        );
    }
    assert_eq!(
        SectionCoord::from_block_y(i64::MIN),
        Err(CoreError::CoordinateOverflow)
    );
    assert_eq!(
        SectionCoord::from_block_y(i64::MAX),
        Err(CoreError::CoordinateOverflow)
    );
}

#[test]
fn negative_and_taller_world_has_more_than_sixteen_sections() {
    let bounds = WorldBounds::new(SectionCoord(-4), 24).unwrap();
    assert_eq!(
        (bounds.min_block_y(), bounds.max_block_y_exclusive()),
        (-64, 320)
    );
    assert_eq!(bounds.section_index(SectionCoord(-4)), Some(0));
    assert_eq!(bounds.section_index(SectionCoord(19)), Some(23));
    assert_eq!(bounds.section_index(SectionCoord(20)), None);
    assert_eq!(bounds.section_index(SectionCoord(-5)), None);
    assert_eq!(bounds.section_at(23), Some(SectionCoord(19)));
    assert_eq!(bounds.section_at(24), None);
    let all = SectionSelection::new(bounds, (-4..20).rev().map(SectionCoord).collect()).unwrap();
    assert_eq!(all.sections().len(), 24);
    assert_eq!(all.sections()[0], SectionCoord(-4));
    assert_eq!(all.sections()[23], SectionCoord(19));
}

#[test]
fn bounds_reject_empty_and_overflow_without_overflowing_intermediate_math() {
    assert_eq!(
        WorldBounds::new(SectionCoord(0), 0),
        Err(CoreError::EmptyWorld)
    );
    assert_eq!(
        WorldBounds::new(SectionCoord(i32::MAX), 2),
        Err(CoreError::CoordinateOverflow)
    );
    let edge = WorldBounds::new(SectionCoord(i32::MAX), 1).unwrap();
    assert_eq!(edge.max_block_y_exclusive(), 34_359_738_368);
    let wide = WorldBounds::new(SectionCoord(i32::MIN), u32::MAX).unwrap();
    assert_eq!(
        wide.section_at(u32::MAX - 1),
        Some(SectionCoord(i32::MAX - 1))
    );
}

#[test]
fn selection_rejects_duplicates_and_out_of_bounds_before_use() {
    let b = WorldBounds::new(SectionCoord(-4), 24).unwrap();
    assert_eq!(
        SectionSelection::new(b, vec![SectionCoord(-4), SectionCoord(-4)]),
        Err(CoreError::DuplicateSection)
    );
    assert_eq!(
        SectionSelection::new(b, vec![SectionCoord(20)]),
        Err(CoreError::SectionOutsideWorld)
    );
    assert!(SectionSelection::new(b, vec![])
        .unwrap()
        .sections()
        .is_empty());
}

#[test]
fn semantic_identity_canonicalizes_properties_but_preserves_values() {
    let a = SemanticStateKey::new(
        "example:pipe",
        vec![
            ("waterlogged".into(), "false".into()),
            ("facing".into(), "east".into()),
        ],
    )
    .unwrap();
    let b = SemanticStateKey::new(
        "example:pipe",
        vec![
            ("facing".into(), "east".into()),
            ("waterlogged".into(), "false".into()),
        ],
    )
    .unwrap();
    let c = SemanticStateKey::new(
        "example:pipe",
        vec![
            ("facing".into(), "west".into()),
            ("waterlogged".into(), "false".into()),
        ],
    )
    .unwrap();
    assert_eq!(a, b);
    assert_ne!(a, c);
    assert_eq!(a.properties()[0].0, "facing");
    assert_eq!(
        SemanticStateKey::new(
            "example:pipe",
            vec![
                ("facing".into(), "east".into()),
                ("facing".into(), "west".into())
            ]
        ),
        Err(CoreError::DuplicateProperty)
    );
}

#[test]
fn unqualified_names_and_invalid_properties_are_rejected() {
    for key in ["", "air", "example/path"] {
        assert_eq!(
            SemanticStateKey::simple(key),
            Err(CoreError::InvalidResourceKey)
        );
    }
    for pair in [("", "east"), ("facing", ""), ("facing", "east\0")] {
        assert_eq!(
            SemanticStateKey::new("example:pipe", vec![(pair.0.into(), pair.1.into())]),
            Err(CoreError::InvalidProperty)
        );
    }
    assert_eq!(
        BiomeKey::new("example:deep/cave").unwrap().name().as_str(),
        "example:deep/cave"
    );
}

#[test]
fn qualified_identity_preserves_text_without_imposing_a_version_grammar() {
    for (text, namespace, path) in [
        ("Example:Mixed/Path", "Example", "Mixed/Path"),
        ("example:a:b", "example", "a:b"),
        ("example:a b", "example", "a b"),
        ("example:na\u{ef}ve", "example", "na\u{ef}ve"),
        ("example:", "example", ""),
        (":air", "", "air"),
        (":", "", ""),
    ] {
        let key = ResourceKey::parse(text).unwrap();
        assert_eq!(key.as_str(), text);
        assert_eq!(key.namespace(), namespace);
        assert_eq!(key.path(), path);
        assert_eq!(SemanticStateKey::simple(text).unwrap().name(), &key);
        assert_eq!(BiomeKey::new(text).unwrap().name(), &key);
    }
    let upper = SemanticStateKey::simple("Example:Mixed/Path").unwrap();
    let lower = SemanticStateKey::simple("example:mixed/path").unwrap();
    assert_ne!(upper, lower);
    let registry = StateRegistry::new(
        RegistryEpoch::new(12, 1).unwrap(),
        vec![upper.clone(), lower.clone()],
    )
    .unwrap();
    assert_ne!(registry.lookup(&upper), registry.lookup(&lower));
}

#[test]
fn registry_remapping_does_not_change_semantics_and_rejects_old_epoch() {
    let air = SemanticStateKey::simple("minecraft:air").unwrap();
    let stone = SemanticStateKey::simple("minecraft:stone").unwrap();
    let epoch = RegistryEpoch::new(91, 1).unwrap();
    let old = StateRegistry::new(epoch, vec![air.clone(), stone.clone()]).unwrap();
    let new = StateRegistry::new(epoch.checked_next().unwrap(), vec![stone.clone(), air]).unwrap();
    let old_ref = old.lookup(&stone).unwrap();
    let new_ref = new.lookup(&stone).unwrap();
    assert_eq!(old_ref.id, DenseRuntimeStateId(1));
    assert_eq!(new_ref.id, DenseRuntimeStateId(0));
    assert_eq!(old.resolve(old_ref), new.resolve(new_ref));
    assert_eq!(new.resolve(old_ref), Err(CoreError::EpochMismatch));
    let other = StateRegistry::new(RegistryEpoch::new(92, 1).unwrap(), vec![stone]).unwrap();
    assert_eq!(other.resolve(old_ref), Err(CoreError::EpochMismatch));
}

#[test]
fn registry_rejects_duplicates_and_unknown_dense_ids() {
    let stone = SemanticStateKey::simple("minecraft:stone").unwrap();
    let epoch = RegistryEpoch::new(1, 1).unwrap();
    assert!(matches!(
        StateRegistry::new(epoch, vec![stone.clone(), stone.clone()]),
        Err(CoreError::DuplicateState)
    ));
    let registry = StateRegistry::new(epoch, vec![stone]).unwrap();
    assert_eq!(
        registry.resolve(RuntimeStateRef {
            epoch,
            id: DenseRuntimeStateId(65_536)
        }),
        Err(CoreError::UnknownRuntimeState)
    );
    assert_eq!(DenseRuntimeStateId(65_536).0, 65_536); // Runtime width is not a universal u16.
}

#[test]
fn epoch_exhaustion_does_not_wrap_or_reuse_identity() {
    assert_eq!(RegistryEpoch::new(0, 1), Err(CoreError::InvalidEpoch));
    assert_eq!(RegistryEpoch::new(1, 0), Err(CoreError::InvalidEpoch));
    assert_eq!(
        RegistryEpoch::new(1, u64::MAX).unwrap().checked_next(),
        Err(CoreError::EpochExhausted)
    );
}

#[test]
fn volume_biomes_and_large_bounds_are_shape_metadata_without_authority() {
    let capability = VersionCapabilities {
        bounds: WorldBounds::new(SectionCoord(-4), 24).unwrap(),
        biome_layout: BiomeLayout::Volume {
            horizontal_samples_per_section: 4,
            vertical_samples_per_section: 4,
        },
        state_encoding: StateEncodingKind::NamedRegistry,
    };
    assert_eq!(capability.bounds.section_count(), 24);
    assert!(matches!(
        capability.biome_layout,
        BiomeLayout::Volume { .. }
    ));
}
