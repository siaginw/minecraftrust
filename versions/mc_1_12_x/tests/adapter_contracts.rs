use mc_1_12_x::{mc_1_12_1::Mc1_12_1, mc_1_12_2::Mc1_12_2, protocol::*, world_format::*, *};
use rustcraft_core::*;

fn hex(s: &str) -> Vec<u8> {
    (0..s.len())
        .step_by(2)
        .map(|i| u8::from_str_radix(&s[i..i + 2], 16).unwrap())
        .collect()
}
fn verify_keepalive<V: Version>(direction: Direction, value: i64, expected: &[u8]) {
    let mut output = [0xa5; 32];
    let len = encode_keepalive_body::<V>(direction, value, &mut output).unwrap();
    assert_eq!(&output[..len], expected);
    assert!(output[len..].iter().all(|b| *b == 0xa5));
    assert_eq!(
        decode_keepalive_body::<V>(direction, expected).unwrap(),
        value
    );
    let mut too_short = vec![0xa5; len - 1];
    assert_eq!(
        encode_keepalive_body::<V>(direction, value, &mut too_short),
        Err(AdapterError::OutputCapacity)
    );
    assert!(too_short.iter().all(|b| *b == 0xa5));
}

#[test]
fn independently_pinned_keepalive_vectors_match_both_specializations() {
    let mut count = 0;
    for line in include_str!("../fixtures/keepalive.tsv")
        .lines()
        .filter(|l| !l.starts_with('#'))
    {
        let fields: Vec<_> = line.split('|').collect();
        let value = fields[2].parse().unwrap();
        let expected = hex(fields[3]);
        let direction = match fields[1] {
            "toClient" => Direction::ToClient,
            "toServer" => Direction::ToServer,
            _ => panic!("direction"),
        };
        match fields[0] {
            "1.12.1" => verify_keepalive::<Mc1_12_1>(direction, value, &expected),
            "1.12.2" => verify_keepalive::<Mc1_12_2>(direction, value, &expected),
            _ => panic!("version"),
        };
        count += 1;
    }
    assert_eq!(count, 18);
}

#[test]
fn release_metadata_is_not_nearest_version_fallback() {
    let rows: Vec<_> = include_str!("../fixtures/release-metadata.tsv")
        .lines()
        .filter(|line| !line.starts_with('#'))
        .collect();
    assert_eq!(rows.len(), 2);
    for (row, metadata) in rows.iter().zip([Mc1_12_1::METADATA, Mc1_12_2::METADATA]) {
        let fields: Vec<_> = row.split('|').collect();
        assert_eq!(metadata.release, fields[0]);
        assert_eq!(metadata.protocol, fields[1].parse::<u32>().unwrap());
        assert_eq!(metadata.data_version, fields[2].parse::<u32>().unwrap());
    }
    assert_eq!(
        SelectedVersion::for_protocol(338),
        Ok(SelectedVersion::Mc1_12_1)
    );
    assert_eq!(
        SelectedVersion::for_protocol(340),
        Ok(SelectedVersion::Mc1_12_2)
    );
    for value in [0, 335, 337, 339, 341, 763] {
        assert_eq!(
            SelectedVersion::for_protocol(value),
            Err(AdapterError::UnsupportedProtocol)
        );
    }
}

#[test]
fn keepalive_versions_are_not_interchangeable() {
    assert_eq!(
        Mc1_12_2::decode_keepalive(&[0]),
        Err(AdapterError::Truncated)
    );
    assert_eq!(
        Mc1_12_1::decode_keepalive(&[0; 8]),
        Err(AdapterError::TrailingBytes)
    );
    for value in [i64::MIN, i64::MAX, 2_147_483_648, -2_147_483_649] {
        let mut out = [0xa5; 16];
        assert_eq!(
            Mc1_12_1::encode_keepalive(value, &mut out),
            Err(AdapterError::KeepAliveOutOfRange)
        );
        assert_eq!(out, [0xa5; 16]);
    }
}

#[test]
fn malformed_keepalives_fail_and_valid_retry_succeeds() {
    assert_eq!(
        Mc1_12_1::decode_keepalive(&[]),
        Err(AdapterError::Truncated)
    );
    assert_eq!(
        Mc1_12_1::decode_keepalive(&[128]),
        Err(AdapterError::Truncated)
    );
    assert_eq!(
        Mc1_12_1::decode_keepalive(&[128; 6]),
        Err(AdapterError::VarIntTooLong)
    );
    assert_eq!(
        Mc1_12_1::decode_keepalive(&[0, 0]),
        Err(AdapterError::TrailingBytes)
    );
    assert_eq!(
        Mc1_12_2::decode_keepalive(&[0; 9]),
        Err(AdapterError::TrailingBytes)
    );
    assert_eq!(
        decode_keepalive_body::<Mc1_12_2>(Direction::ToServer, &[0x1f; 9]),
        Err(AdapterError::WrongPacketId)
    );
    assert_eq!(Mc1_12_1::decode_keepalive(&[0x80, 0]), Ok(0)); // Java allows non-minimal VarInts.
    assert_eq!(
        Mc1_12_1::decode_keepalive(&[0xff, 0xff, 0xff, 0xff, 0x7f]),
        Ok(-1)
    ); // Java int truncation.
    let mut output = [0xa5; 9];
    assert_eq!(
        encode_keepalive_body::<Mc1_12_2>(Direction::ToServer, 300, &mut output).unwrap(),
        9
    );
    assert_eq!(output, [0x0b, 0, 0, 0, 0, 0, 0, 1, 0x2c]);
}

#[test]
fn position_vectors_do_not_depend_on_core_coordinate_packing() {
    for line in include_str!("../fixtures/positions.tsv")
        .lines()
        .filter(|l| !l.starts_with('#'))
    {
        let f: Vec<_> = line.split('|').collect();
        let position = BlockPosition {
            x: f[0].parse().unwrap(),
            y: f[1].parse().unwrap(),
            z: f[2].parse().unwrap(),
        };
        let expected: [u8; 8] = hex(f[3]).try_into().unwrap();
        assert_eq!(encode_position(position).unwrap(), expected);
        assert_eq!(decode_position(expected), position);
    }
    for p in [
        BlockPosition {
            x: -33_554_432,
            y: -2048,
            z: 33_554_431,
        },
        BlockPosition {
            x: 33_554_431,
            y: 2047,
            z: -33_554_432,
        },
    ] {
        assert_eq!(decode_position(encode_position(p).unwrap()), p);
    }
    for p in [
        BlockPosition {
            x: 33_554_432,
            y: 0,
            z: 0,
        },
        BlockPosition {
            x: 0,
            y: 2048,
            z: 0,
        },
        BlockPosition {
            x: 0,
            y: 0,
            z: -33_554_433,
        },
    ] {
        assert_eq!(encode_position(p), Err(AdapterError::UnsupportedCoordinate));
    }
}

#[test]
fn only_legacy_bounds_can_be_represented_by_legacy_mask() {
    let selected = SectionSelection::new(
        capabilities().bounds,
        vec![SectionCoord(15), SectionCoord(5), SectionCoord(0)],
    )
    .unwrap();
    assert_eq!(
        LegacySectionMask::from_selection(&selected).unwrap().bits(),
        0x8021
    );
    for b in [
        WorldBounds::new(SectionCoord(-4), 24).unwrap(),
        WorldBounds::new(SectionCoord(0), 32).unwrap(),
    ] {
        let selected = SectionSelection::new(b, vec![SectionCoord(0)]).unwrap();
        assert_eq!(
            LegacySectionMask::from_selection(&selected),
            Err(AdapterError::UnsupportedBounds)
        );
    }
}

fn registry(epoch: RegistryEpoch) -> StateRegistry {
    StateRegistry::new(
        epoch,
        vec![
            SemanticStateKey::simple("minecraft:stone").unwrap(),
            SemanticStateKey::simple("minecraft:air").unwrap(),
        ],
    )
    .unwrap()
}
fn mapping(stone_wire: u64) -> Vec<StateMapping> {
    vec![
        StateMapping {
            semantic: SemanticStateKey::simple("minecraft:air").unwrap(),
            wire: WireStateId(9),
            persistence: LegacyStoredState::new(0, 0).unwrap(),
        },
        StateMapping {
            semantic: SemanticStateKey::simple("minecraft:stone").unwrap(),
            wire: WireStateId(stone_wire),
            persistence: LegacyStoredState::new(257, 3).unwrap(),
        },
    ]
}

#[test]
fn mapping_separates_runtime_wire_semantic_and_disk_identities() {
    let epoch = RegistryEpoch::new(51, 1).unwrap();
    let registry = registry(epoch);
    let adapter = LegacyWorldAdapter::<Mc1_12_2>::bind(&registry, mapping(65_537)).unwrap();
    let job = adapter
        .admit_job(epoch, &[DenseRuntimeStateId(1), DenseRuntimeStateId(0)])
        .unwrap();
    let mut wire = [WireStateId(999); 3];
    assert_eq!(job.write_wire(&mut wire), Ok(2));
    assert_eq!(
        wire,
        [WireStateId(9), WireStateId(65_537), WireStateId(999)]
    );
    let mut disk = [LegacyStoredState::new(0, 0).unwrap(); 2];
    assert_eq!(job.write_persistence(&mut disk), Ok(2));
    assert_eq!(disk[1].mapping_id(), PersistenceStateId(4115));
    assert_ne!(disk[1].mapping_id().0, wire[1].0);
    assert_eq!(
        (
            disk[1].low_block_byte(),
            disk[1].add_nibble(),
            disk[1].metadata()
        ),
        (1, 1, 3)
    );
}

#[test]
fn palette_context_is_independent_per_world_and_accepts_wire_ids_above_u16() {
    let a = registry(RegistryEpoch::new(61, 1).unwrap());
    let b = registry(RegistryEpoch::new(62, 1).unwrap());
    let low = LegacyWorldAdapter::<Mc1_12_1>::bind(&a, mapping(31)).unwrap();
    let high = LegacyWorldAdapter::<Mc1_12_2>::bind(&b, mapping(1_000_000)).unwrap();
    assert_eq!(low.direct_palette_bits(), 5);
    assert_eq!(high.direct_palette_bits(), 20);
    assert_eq!(low.direct_palette_bits(), 5);
    assert!(matches!(
        low.admit_job(high.epoch(), &[DenseRuntimeStateId(0)]),
        Err(AdapterError::EpochMismatch)
    ));
}

#[test]
fn incomplete_duplicate_extra_and_out_of_range_maps_cannot_bind() {
    let r = registry(RegistryEpoch::new(71, 1).unwrap());
    assert!(matches!(
        LegacyWorldAdapter::<Mc1_12_2>::bind(&r, vec![]),
        Err(AdapterError::MissingStateMapping)
    ));
    let mut duplicate = mapping(17);
    duplicate.push(duplicate[0].clone());
    assert!(matches!(
        LegacyWorldAdapter::<Mc1_12_2>::bind(&r, duplicate),
        Err(AdapterError::DuplicateMapping)
    ));
    let mut extra = mapping(17);
    extra.push(StateMapping {
        semantic: SemanticStateKey::simple("minecraft:dirt").unwrap(),
        wire: WireStateId(99),
        persistence: LegacyStoredState::new(3, 0).unwrap(),
    });
    assert!(matches!(
        LegacyWorldAdapter::<Mc1_12_2>::bind(&r, extra),
        Err(AdapterError::ExtraStateMapping)
    ));
    assert!(matches!(
        LegacyWorldAdapter::<Mc1_12_2>::bind(&r, mapping(u64::MAX)),
        Err(AdapterError::WireStateOutOfRange)
    ));
    assert_eq!(
        LegacyStoredState::new(4096, 0),
        Err(AdapterError::InvalidStoredState)
    );
    assert_eq!(
        LegacyStoredState::new(1, 16),
        Err(AdapterError::InvalidStoredState)
    );
}

#[test]
fn stale_or_invalid_job_cannot_publish_and_capacity_failure_leaves_buffer_intact() {
    let epoch = RegistryEpoch::new(81, 1).unwrap();
    let r = registry(epoch);
    let adapter = LegacyWorldAdapter::<Mc1_12_2>::bind(&r, mapping(17)).unwrap();
    assert!(matches!(
        adapter.admit_job(epoch.checked_next().unwrap(), &[DenseRuntimeStateId(0)]),
        Err(AdapterError::EpochMismatch)
    ));
    assert!(matches!(
        adapter.admit_job(epoch, &[DenseRuntimeStateId(0), DenseRuntimeStateId(9)]),
        Err(AdapterError::UnknownRuntimeState)
    ));
    let job = adapter
        .admit_job(epoch, &[DenseRuntimeStateId(0), DenseRuntimeStateId(1)])
        .unwrap();
    let mut short = [WireStateId(999)];
    assert_eq!(
        job.write_wire(&mut short),
        Err(AdapterError::OutputCapacity)
    );
    assert_eq!(short, [WireStateId(999)]);
    let mut full = [WireStateId(999); 2];
    assert_eq!(job.write_wire(&mut full), Ok(2));
    assert_eq!(full, [WireStateId(17), WireStateId(9)]);
}

#[test]
fn legacy_biomes_require_an_explicit_complete_mapping_and_column_shape() {
    let plains = BiomeKey::new("minecraft:plains").unwrap();
    let cave = BiomeKey::new("example:cave").unwrap();
    let map = LegacyBiomeMap::new(vec![(plains.clone(), 1), (cave.clone(), 255)]).unwrap();
    let mut keys = vec![plains; 256];
    keys[255] = cave;
    let columns = map.columns(&keys).unwrap();
    assert_eq!(columns.as_bytes()[0], 1);
    assert_eq!(columns.as_bytes()[255], 255);
    assert_eq!(
        map.columns(&keys[..64]),
        Err(AdapterError::InvalidBiomeCount)
    );
    keys[0] = BiomeKey::new("unknown:biome").unwrap();
    assert_eq!(map.columns(&keys), Err(AdapterError::MissingBiomeMapping));
    assert!(matches!(
        LegacyBiomeMap::new(vec![(keys[0].clone(), 256)]),
        Err(AdapterError::BiomeIdOutOfRange)
    ));
}
