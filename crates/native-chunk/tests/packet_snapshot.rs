use native_chunk::{OwnedPacketSnapshot, SnapshotRejection};

fn transport(mask: u16, sky: bool, full: bool, states: impl Fn(usize) -> u32) -> Vec<u8> {
    let mut out = vec![0; 128];
    out[..8].copy_from_slice(b"RCSNAP01");
    out[8..10].copy_from_slice(&1u16.to_be_bytes());
    out[10] = u8::from(full) | (u8::from(sky) << 1);
    out[11] = 1;
    out[12] = 14;
    out[13] = 1;
    for offset in [28, 40, 48, 56, 80, 88] {
        out[offset..offset + 8].copy_from_slice(&1u64.to_be_bytes());
    }
    out[36..38].copy_from_slice(&mask.to_be_bytes());
    out[38..40].copy_from_slice(&mask.to_be_bytes());
    out.extend_from_slice(&(mask.count_ones() as u16).to_be_bytes());
    let non_air = (0..4096).filter(|&i| states(i) != 0).count() as u16;
    for y in 0..16 {
        if mask & (1 << y) != 0 {
            out.extend_from_slice(&[y, 0]);
            out.extend_from_slice(&non_air.to_be_bytes());
            for i in 0..4096 {
                out.extend_from_slice(&states(i).to_be_bytes());
            }
            out.extend_from_slice(&[y; 2048]);
            if sky {
                out.extend_from_slice(&[255 - y; 2048]);
            }
        }
    }
    if full {
        out.extend_from_slice(&[42; 256]);
    }
    out
}

fn error(input: &[u8]) -> SnapshotRejection {
    OwnedPacketSnapshot::from_transport(input)
        .err()
        .expect("must reject")
}

#[test]
fn sparse_owned_snapshot_survives_input_mutation_and_pairs_result() {
    let mut input = transport(0x8005, true, true, |_| 17);
    let snapshot = OwnedPacketSnapshot::from_transport(&input).unwrap();
    input.fill(0xff);
    let mut a = vec![0; 262144];
    let mut b = a.clone();
    let first = snapshot.encode(&mut a).unwrap();
    let second = snapshot.encode(&mut b).unwrap();
    assert_eq!(first, second);
    assert_eq!(first.emitted_mask, 0x8005);
    assert_eq!(&a[..first.bytes_written], &b[..second.bytes_written]);
    assert_eq!(
        &a[first.bytes_written - 256..first.bytes_written],
        &[42; 256]
    );
}

#[test]
fn missing_historical_shape_and_unselected_sections_reject() {
    let mut input = transport(0x1f, true, true, |_| 17);
    input[36..40].copy_from_slice(&[0, 0x3f, 0, 0x3f]);
    assert_eq!(error(&input), SnapshotRejection::MissingSection);
    let mut input = transport(0x8001, false, false, |_| 1);
    input[36..38].copy_from_slice(&1u16.to_be_bytes());
    assert_eq!(error(&input), SnapshotRejection::MaskMismatch);
}

#[test]
fn malformed_transport_never_becomes_short_success() {
    let input = transport(1, true, true, |_| 1);
    for n in [0, 7, 127, 129, 130, 133, 16400, input.len() - 1] {
        assert!(OwnedPacketSnapshot::from_transport(&input[..n]).is_err());
    }
    let mut trailing = input;
    trailing.push(0);
    assert_eq!(error(&trailing), SnapshotRejection::MalformedSnapshot);
}

#[test]
fn incarnation_epoch_thread_scope_and_storage_guards() {
    for (offset, value, expected) in [
        (95, 2, SnapshotRejection::ChunkReplaced),
        (79, 1, SnapshotRejection::CaptureChanged),
        (63, 2, SnapshotRejection::OffThread),
        (13, 4, SnapshotRejection::UnknownWriter),
        (11, 3, SnapshotRejection::UnsupportedStorage),
        (12, 8, SnapshotRejection::UnsupportedStorage),
    ] {
        let mut input = transport(0, false, false, |_| 0);
        input[offset] = value;
        assert_eq!(error(&input), expected);
    }
    let mut input = transport(0, false, false, |_| 0);
    input[64] = 128;
    input[72] = 128;
    assert_eq!(error(&input), SnapshotRejection::CaptureChanged);
    // Incarnation and generation are independently identified.
    input = transport(0, false, false, |_| 0);
    input[87] = 42;
    input[95] = 42;
    assert!(OwnedPacketSnapshot::from_transport(&input).is_ok());
    // All three admitted provenance scopes retain their exact value (scope 3
    // additionally requires vanilla storage and full_chunk == full mask). No
    // scope is a production publication permit or an authenticated ownership proof.
    for scope in [1, 2, 3] {
        input[13] = scope;
        let snapshot = OwnedPacketSnapshot::from_transport(&input).unwrap();
        assert_eq!(snapshot.metadata().offline_scope, scope);
        assert_eq!(snapshot.encode(&mut []).unwrap().emitted_mask, 0);
    }
}

#[test]
fn wide_ids_reject_without_truncation_and_local_high_u16_remains_exact() {
    assert_eq!(
        error(&transport(1, false, false, |_| 65536)),
        SnapshotRejection::ExtendedId
    );
    let snapshot =
        OwnedPacketSnapshot::from_transport(&transport(1, false, false, |_| 65535)).unwrap();
    let mut out = vec![0; 262144];
    let result = snapshot.encode(&mut out).unwrap();
    assert_eq!(result.emitted_mask, 1);
    // local palette [0,65535], canonical three-byte VarInt, not wrapped to air.
    assert_eq!(&out[..6], &[4, 2, 0, 255, 255, 3]);
    let input = transport(1, false, false, |i| {
        if i == 0 {
            65535
        } else {
            (i % 300) as u32
        }
    });
    assert_eq!(error(&input), SnapshotRejection::ExtendedId);
}

#[test]
fn snapshot_registry_width_is_per_operation_and_retry_is_safe() {
    for bits in [9u8, 16, 14, 9] {
        let mut input = transport(1, false, false, |i| (i % 300) as u32);
        input[12] = bits;
        let snapshot = OwnedPacketSnapshot::from_transport(&input).unwrap();
        assert_eq!(
            snapshot.encode(&mut [0; 1]),
            Err(SnapshotRejection::Capacity)
        );
        let mut out = vec![0; 262144];
        let result = snapshot.encode(&mut out).unwrap();
        assert_eq!(result.emitted_mask, 1);
        assert_eq!(out[0], bits);
        let mut exact = vec![0; result.bytes_written];
        assert_eq!(snapshot.encode(&mut exact), Ok(result));
        assert_eq!(
            snapshot.encode(&mut exact[..result.bytes_written - 1]),
            Err(SnapshotRejection::Capacity)
        );
    }
}

#[test]
fn empty_partial_section_is_present_but_full_empty_selection_rejects() {
    let snapshot =
        OwnedPacketSnapshot::from_transport(&transport(0x8000, false, false, |_| 0)).unwrap();
    assert_eq!(
        snapshot.encode(&mut vec![0; 262144]).unwrap().emitted_mask,
        0x8000
    );
    assert_eq!(
        error(&transport(1, false, true, |_| 0)),
        SnapshotRejection::MaskMismatch
    );
    let snapshot = OwnedPacketSnapshot::from_transport(&transport(0, false, false, |_| 0)).unwrap();
    assert_eq!(snapshot.encode(&mut []).unwrap().bytes_written, 0);
}

#[test]
fn refcount_and_section_order_are_checked() {
    let mut input = transport(1, false, false, |_| 1);
    input[133] = 1;
    assert_eq!(error(&input), SnapshotRejection::RefcountMismatch);
    let mut input = transport(1, false, false, |_| 1);
    input[130] = 5;
    assert_eq!(error(&input), SnapshotRejection::MaskMismatch);
}

#[test]
fn live_shadow_scope_three_is_admitted_with_structural_constraints() {
    // Scope 3: vanilla storage only and full_chunk == (requested_mask == 0xffff).
    let mut full = transport(0xffff, true, true, |i| (i % 17 + 1) as u32);
    full[13] = 3;
    let snapshot = OwnedPacketSnapshot::from_transport(&full).unwrap();
    assert_eq!(snapshot.metadata().offline_scope, 3);
    let mut out = vec![0; 262144];
    let result = snapshot.encode(&mut out).unwrap();
    assert_eq!(result.emitted_mask, 0xffff);
    // A partial filter can never claim a full (or non-full) live packet.
    let mut partial_full = transport(0x1f, true, true, |i| (i % 17 + 1) as u32);
    partial_full[13] = 3;
    assert_eq!(error(&partial_full), SnapshotRejection::MalformedSnapshot);
    let mut full_partial = transport(0xffff, true, false, |i| (i % 17 + 1) as u32);
    full_partial[13] = 3;
    assert_eq!(error(&full_partial), SnapshotRejection::MalformedSnapshot);
    // Scope 3 admits only the vanilla storage model.
    let mut neid = transport(0xffff, true, true, |i| (i % 17 + 1) as u32);
    neid[11] = 2;
    neid[13] = 3;
    assert_eq!(error(&neid), SnapshotRejection::UnsupportedStorage);
    // Scope 3 keeps every existing rejection: extended ids still reject.
    let mut wide = transport(0xffff, true, true, |i| if i == 0 { 70000 } else { 1 });
    wide[13] = 3;
    assert_eq!(error(&wide), SnapshotRejection::ExtendedId);
    // And a bad scope byte is still refused outright.
    let mut bad_scope = transport(0xffff, true, true, |i| (i % 17 + 1) as u32);
    bad_scope[13] = 4;
    assert_eq!(error(&bad_scope), SnapshotRejection::UnknownWriter);
}
