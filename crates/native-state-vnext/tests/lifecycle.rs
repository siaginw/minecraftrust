mod common;
use common::*;
use native_capability::{
    CapabilityState, DependencyOwnership, DependencyRequest, MutableOwner, OwnershipMode,
};
use native_state_vnext::{section::*, store::*};
use rustcraft_core::*;
use std::sync::Arc;

fn error<T: std::fmt::Debug>(result: Result<T, StoreError>, expected: StoreError) {
    assert_eq!(result.unwrap_err(), expected);
}
fn state(view: &NativeView, cell: usize) -> DenseRuntimeStateId {
    view.data()
        .chunk()
        .section(SectionCoord(-4))
        .unwrap()
        .get(cell)
        .unwrap()
}

#[test]
fn initial_java_owner_has_single_writer_and_cannot_publish_native_views() {
    let (mut store, handle) = fixture();
    assert_eq!(store.phase(&handle), Ok(OwnershipState::JavaOwned));
    assert_eq!(store.modeled_owner(&handle), Ok(&MutableOwner::Java));
    error(
        store.begin_write(&handle, WriterSide::Rust),
        StoreError::WrongState,
    );
    let lease = store.begin_write(&handle, WriterSide::Java).unwrap();
    error(
        store.begin_write(&handle, WriterSide::Java),
        StoreError::Busy,
    );
    error(
        store.java_view(&handle, artifact_context()),
        StoreError::Busy,
    );
    store
        .set_state(&lease, SectionCoord(-4), 1, DenseRuntimeStateId(12))
        .unwrap();
    store.finish_write(lease).unwrap();
    let view = store.java_view(&handle, artifact_context()).unwrap();
    assert_eq!(state(&view, 1), DenseRuntimeStateId(12));
    error(store.queue_work(view), StoreError::WrongState);
    assert!(!native_state_vnext::production_authority_enabled());
}

#[test]
fn quiescence_freezes_new_writers_and_drains_existing_java_writer_before_copy() {
    let (mut store, handle) = fixture();
    let binding = bind(&mut store, &handle, "native");
    let writer = store.begin_write(&handle, WriterSide::Java).unwrap();
    let ticket = store.begin_native_handoff(&handle, binding).unwrap();
    let current = ticket.handle().clone();
    assert_eq!(store.phase(&current), Ok(OwnershipState::Quiescing));
    error(
        store.begin_write(&handle, WriterSide::Java),
        StoreError::StaleHandle,
    );
    error(
        store.begin_write(&current, WriterSide::Java),
        StoreError::WrongState,
    );
    error(store.stage_after_quiescence(&ticket), StoreError::Busy);
    store
        .set_state(&writer, SectionCoord(-4), 7, DenseRuntimeStateId(23))
        .unwrap();
    store.finish_write(writer).unwrap();
    store.stage_after_quiescence(&ticket).unwrap();
    assert_eq!(store.phase(&current), Ok(OwnershipState::Transferring));
    error(
        store.begin_write(&current, WriterSide::Rust),
        StoreError::WrongState,
    );
    let result = store
        .publish_handoff(ticket, PublicationFault::None)
        .unwrap();
    assert!(matches!(result, PublicationOutcome::Committed(_)));
    let view = store
        .view(result.handle(), ViewKind::Query, artifact_context())
        .unwrap();
    assert_eq!(state(&view, 7), DenseRuntimeStateId(23));
}

#[test]
fn failed_native_publication_rolls_back_exact_java_state_before_and_after_owner_switch() {
    for fault in [
        PublicationFault::BeforeOwnerSwitch,
        PublicationFault::AfterOwnerSwitch,
    ] {
        let (mut store, handle) = fixture();
        let writer = store.begin_write(&handle, WriterSide::Java).unwrap();
        store
            .set_state(&writer, SectionCoord(-4), 9, DenseRuntimeStateId(24))
            .unwrap();
        store.finish_write(writer).unwrap();
        let before = store.java_view(&handle, artifact_context()).unwrap();
        let binding = bind(&mut store, &handle, "native");
        let ticket = store.begin_native_handoff(&handle, binding).unwrap();
        store.stage_after_quiescence(&ticket).unwrap();
        let result = store.publish_handoff(ticket, fault).unwrap();
        assert!(matches!(result, PublicationOutcome::RolledBack(_)));
        assert_eq!(
            store.modeled_owner(result.handle()),
            Ok(&MutableOwner::Java)
        );
        let after = store
            .java_view(result.handle(), artifact_context())
            .unwrap();
        assert_eq!(state(&before, 9), state(&after, 9));
        assert!(
            after.tag().handle.ownership_generation() > before.tag().handle.ownership_generation()
        );
    }
}

#[test]
fn invalid_publication_order_consumes_ticket_but_restores_safe_java_state() {
    let (mut store, handle) = fixture();
    let binding = bind(&mut store, &handle, "native");
    let ticket = store.begin_native_handoff(&handle, binding).unwrap();
    let current = ticket.handle().clone();
    error(
        store.publish_handoff(ticket, PublicationFault::None),
        StoreError::WrongState,
    );
    assert_eq!(store.phase(&current), Ok(OwnershipState::JavaOwned));
    let writer = store.begin_write(&current, WriterSide::Java).unwrap();
    store.finish_write(writer).unwrap();
}

#[test]
fn wrong_registry_binding_refuses_before_any_ownership_transition() {
    let (mut store, handle) = fixture();
    let mut binding = bind(&mut store, &handle, "native");
    binding.registry_epoch = RegistryEpoch::new(1, 2).unwrap();
    error(
        store.begin_native_handoff(&handle, binding),
        StoreError::RegistryBindingMismatch,
    );
    assert_eq!(store.phase(&handle), Ok(OwnershipState::JavaOwned));
}

#[test]
fn bad_observation_at_claim_rolls_back_without_rust_admission() {
    let (mut store, handle) = fixture();
    let mut binding = bind(&mut store, &handle, "native");
    binding.observation.scope.dimension = 99;
    let ticket = store.begin_native_handoff(&handle, binding).unwrap();
    store.stage_after_quiescence(&ticket).unwrap();
    let result = store
        .publish_handoff(ticket, PublicationFault::None)
        .unwrap();
    assert!(matches!(result, PublicationOutcome::RolledBack(_)));
    assert_eq!(
        store.modeled_owner(result.handle()),
        Ok(&MutableOwner::Java)
    );
}

#[test]
fn immutable_views_survive_mutation_without_exposing_mutable_aliases() {
    let (mut store, java) = fixture();
    let (handle, _) = rust_owned(&mut store, &java, "native");
    let old = store
        .view(&handle, ViewKind::Packet, artifact_context())
        .unwrap();
    let writer = store.begin_write(&handle, WriterSide::Rust).unwrap();
    store
        .set_state(&writer, SectionCoord(-4), 0, DenseRuntimeStateId(44))
        .unwrap();
    store.finish_write(writer).unwrap();
    let new = store
        .view(&handle, ViewKind::Packet, artifact_context())
        .unwrap();
    assert_eq!(state(&old, 0), DenseRuntimeStateId(0));
    assert_eq!(state(&new, 0), DenseRuntimeStateId(44));
    assert!(new.tag().generations.states > old.tag().generations.states);
    error(store.queue_work(old), StoreError::StaleView);
}

#[test]
fn all_five_worker_kinds_are_generation_tagged_and_publish_only_current_results() {
    let (mut store, java) = fixture();
    let (handle, _) = rust_owned(&mut store, &java, "native");
    for kind in [
        ViewKind::Packet,
        ViewKind::Lighting,
        ViewKind::Persistence,
        ViewKind::Query,
        ViewKind::WorldgenDependency,
    ] {
        let view = store.view(&handle, kind, artifact_context()).unwrap();
        assert_eq!(view.tag().registry_epoch, RegistryEpoch::new(1, 1).unwrap());
        assert_eq!(view.tag().handle.context(), &context(1));
        let job = store.queue_work(view.clone()).unwrap();
        assert_eq!(
            &*store.publish_work(job.complete(vec![1, 2, 3])).unwrap(),
            &[1, 2, 3]
        );
        assert_eq!(&*store.cached(&view).unwrap().unwrap(), &[1, 2, 3]);
    }
}

#[test]
fn state_light_and_biome_changes_each_reject_queued_results_and_clear_cache() {
    for mutation in 0..3 {
        let (mut store, java) = fixture();
        let (handle, _) = rust_owned(&mut store, &java, "native");
        let old = store
            .view(&handle, ViewKind::Packet, artifact_context())
            .unwrap();
        let cached = store.queue_work(old.clone()).unwrap();
        store.publish_work(cached.complete(vec![7])).unwrap();
        let queued = store.queue_work(old).unwrap();
        let writer = store.begin_write(&handle, WriterSide::Rust).unwrap();
        match mutation {
            0 => {
                store
                    .set_state(&writer, SectionCoord(-4), 0, DenseRuntimeStateId(1))
                    .unwrap();
            }
            1 => {
                store.set_light(&writer, vec![4; 32]).unwrap();
            }
            _ => {
                store
                    .set_biomes(&writer, vec![BiomeKey::new("test:forest").unwrap()])
                    .unwrap();
            }
        }
        store.finish_write(writer).unwrap();
        error(
            store.publish_work(queued.complete(vec![8])),
            StoreError::StaleView,
        );
        let current = store
            .view(&handle, ViewKind::Packet, artifact_context())
            .unwrap();
        assert!(store.cached(&current).unwrap().is_none());
    }
}

#[test]
fn semantic_noop_keeps_generations_and_cached_result() {
    let (mut store, java) = fixture();
    let (handle, _) = rust_owned(&mut store, &java, "native");
    let view = store
        .view(&handle, ViewKind::Packet, artifact_context())
        .unwrap();
    let work = store.queue_work(view.clone()).unwrap();
    store.publish_work(work.complete(vec![1])).unwrap();
    let writer = store.begin_write(&handle, WriterSide::Rust).unwrap();
    assert!(!store
        .set_state(&writer, SectionCoord(-4), 0, DenseRuntimeStateId(0))
        .unwrap());
    assert!(!store.set_light(&writer, vec![0; 32]).unwrap());
    store.finish_write(writer).unwrap();
    assert!(store.cached(&view).unwrap().is_some());
}

#[test]
fn recipient_dimension_protocol_and_view_kind_partition_opaque_artifact_cache() {
    let (mut store, java) = fixture();
    let (handle, _) = rust_owned(&mut store, &java, "native");
    let view = store
        .view(&handle, ViewKind::Packet, artifact_context())
        .unwrap();
    let work = store.queue_work(view.clone()).unwrap();
    store.publish_work(work.complete(vec![1])).unwrap();
    for field in 0..3 {
        let mut other = artifact_context();
        match field {
            0 => other.protocol_version = token(99),
            1 => other.dimension_rules = token(99),
            _ => other.recipient_rules = token(99),
        };
        let other = store.view(&handle, ViewKind::Packet, other).unwrap();
        assert!(store.cached(&other).unwrap().is_none());
    }
    let query = store
        .view(&handle, ViewKind::Query, artifact_context())
        .unwrap();
    assert!(store.cached(&query).unwrap().is_none());
}

#[test]
fn materialization_waits_for_rust_writer_and_recreates_identical_java_state() {
    let (mut store, java) = fixture();
    let (handle, _) = rust_owned(&mut store, &java, "native");
    let writer = store.begin_write(&handle, WriterSide::Rust).unwrap();
    let ticket = store.begin_java_materialization(&handle).unwrap();
    let current = ticket.handle().clone();
    error(
        store.begin_write(&current, WriterSide::Rust),
        StoreError::WrongState,
    );
    error(store.stage_after_quiescence(&ticket), StoreError::Busy);
    store
        .set_state(&writer, SectionCoord(-4), 4, DenseRuntimeStateId(55))
        .unwrap();
    store.finish_write(writer).unwrap();
    store.stage_after_quiescence(&ticket).unwrap();
    let result = store
        .publish_handoff(ticket, PublicationFault::None)
        .unwrap();
    assert!(matches!(result, PublicationOutcome::Committed(_)));
    assert_eq!(store.phase(result.handle()), Ok(OwnershipState::JavaOwned));
    assert_eq!(
        store.modeled_owner(result.handle()),
        Ok(&MutableOwner::Java)
    );
    assert_eq!(
        state(
            &store
                .java_view(result.handle(), artifact_context())
                .unwrap(),
            4
        ),
        DenseRuntimeStateId(55)
    );
    error(
        store.begin_write(&handle, WriterSide::Rust),
        StoreError::StaleHandle,
    );
}

#[test]
fn materialization_failure_before_switch_rolls_back_rust_after_switch_revokes() {
    for fault in [
        PublicationFault::BeforeOwnerSwitch,
        PublicationFault::AfterOwnerSwitch,
    ] {
        let (mut store, java) = fixture();
        let (handle, _) = rust_owned(&mut store, &java, "native");
        let ticket = store.begin_java_materialization(&handle).unwrap();
        store.stage_after_quiescence(&ticket).unwrap();
        let result = store.publish_handoff(ticket, fault).unwrap();
        if fault == PublicationFault::BeforeOwnerSwitch {
            assert!(matches!(result, PublicationOutcome::RolledBack(_)));
            let writer = store
                .begin_write(result.handle(), WriterSide::Rust)
                .unwrap();
            store.finish_write(writer).unwrap();
        } else {
            assert!(matches!(result, PublicationOutcome::Revoked(_)));
            assert_eq!(
                store.modeled_owner(result.handle()),
                Ok(&MutableOwner::Unowned)
            );
            error(
                store.begin_write(result.handle(), WriterSide::Java),
                StoreError::WrongState,
            );
            error(
                store.begin_write(result.handle(), WriterSide::Rust),
                StoreError::WrongState,
            );
        }
    }
}

#[test]
fn native_java_native_roundtrip_requires_new_capability_generation() {
    let (mut store, java) = fixture();
    let (native, binding) = rust_owned(&mut store, &java, "native");
    let ticket = store.begin_java_materialization(&native).unwrap();
    store.stage_after_quiescence(&ticket).unwrap();
    let java = store
        .publish_handoff(ticket, PublicationFault::None)
        .unwrap()
        .handle()
        .clone();
    assert!(store.begin_native_handoff(&java, binding).is_err());
    let (new, _) = rust_owned(&mut store, &java, "native");
    assert!(new.ownership_generation() > native.ownership_generation());
    error(
        store.view(&native, ViewKind::Packet, artifact_context()),
        StoreError::StaleHandle,
    );
}

#[test]
fn capability_revocation_cancels_pending_transfer_and_queued_work() {
    let (mut store, java) = fixture();
    let binding = bind(&mut store, &java, "native");
    let ticket = store.begin_native_handoff(&java, binding.clone()).unwrap();
    let current = ticket.handle().clone();
    store.revoke_capability(&binding.capability).unwrap();
    assert_eq!(store.phase(&current), Ok(OwnershipState::Revoked));
    error(
        store.stage_after_quiescence(&ticket),
        StoreError::InvalidTicket,
    );
    store.destroy(&current).unwrap();
    let java = store
        .insert_java(context(2), token(100), registry(), data())
        .unwrap();
    let (handle, binding) = rust_owned(&mut store, &java, "native");
    let work = store
        .queue_work(
            store
                .view(&handle, ViewKind::Lighting, artifact_context())
                .unwrap(),
        )
        .unwrap();
    store.revoke_capability(&binding.capability).unwrap();
    assert!(matches!(
        store.modeled_owner(&handle),
        Ok(MutableOwner::Quarantined(_))
    ));
    error(
        store.publish_work(work.complete(vec![1])),
        StoreError::WrongState,
    );
}

#[test]
fn dependent_writer_evidence_revocation_closes_native_ownership_transitively() {
    let (mut store, java) = fixture();
    let mut parent = capability_spec(&store, &java, "writer-evidence");
    parent.state = CapabilityState::ShadowValidated;
    parent.ownership_mode = OwnershipMode::OwnedSnapshot;
    let mut child = capability_spec(&store, &java, "native");
    child.dependencies.push(DependencyRequest {
        key: "writer-evidence".into(),
        minimum_state: CapabilityState::ShadowValidated,
        ownership: DependencyOwnership::Any,
    });
    let observed = observation(&child);
    let handles = store.install_capabilities(vec![parent, child]).unwrap();
    let binding = CapabilityBinding {
        capability: handles[1].clone(),
        observation: observed,
        registry_epoch: RegistryEpoch::new(1, 1).unwrap(),
    };
    let ticket = store.begin_native_handoff(&java, binding).unwrap();
    store.stage_after_quiescence(&ticket).unwrap();
    let native = store
        .publish_handoff(ticket, PublicationFault::None)
        .unwrap()
        .handle()
        .clone();
    store.revoke_capability(&handles[0]).unwrap();
    assert_eq!(store.phase(&native), Ok(OwnershipState::Revoked));
    error(
        store.begin_write(&native, WriterSide::Rust),
        StoreError::WrongState,
    );
}

#[test]
fn revoked_active_writer_cannot_mutate_but_can_drain_for_destruction() {
    let (mut store, java) = fixture();
    let (native, binding) = rust_owned(&mut store, &java, "native");
    let writer = store.begin_write(&native, WriterSide::Rust).unwrap();
    store.revoke_capability(&binding.capability).unwrap();
    error(
        store.set_state(&writer, SectionCoord(-4), 0, DenseRuntimeStateId(1)),
        StoreError::WrongState,
    );
    error(store.destroy(&native), StoreError::Busy);
    store.finish_write(writer).unwrap();
    store.destroy(&native).unwrap();
    error(store.phase(&native), StoreError::StaleHandle);
}

#[test]
fn slot_reuse_and_incarnation_changes_never_admit_old_handles_or_results() {
    let (mut store, java) = fixture();
    let (old, _) = rust_owned(&mut store, &java, "native");
    let view = store
        .view(&old, ViewKind::Persistence, artifact_context())
        .unwrap();
    let work = store.queue_work(view.clone()).unwrap();
    store.destroy(&old).unwrap();
    let new = store
        .insert_java(context(2), token(100), registry(), data())
        .unwrap();
    assert_eq!(new.slot(), old.slot());
    assert!(new.slot_generation() > old.slot_generation());
    assert_ne!(new.context(), old.context());
    error(
        store.begin_write(&old, WriterSide::Rust),
        StoreError::StaleHandle,
    );
    error(
        store.publish_work(work.complete(vec![1])),
        StoreError::CancelledWork,
    );
    assert_eq!(state(&view, 0), DenseRuntimeStateId(0)); // immutable allocation remains readable.
}

#[test]
fn registry_invalidation_rejects_old_views_while_retaining_immutable_semantic_lookup() {
    let (mut store, java) = fixture();
    let (native, _) = rust_owned(&mut store, &java, "native");
    let view = store
        .view(&native, ViewKind::Query, artifact_context())
        .unwrap();
    store.invalidate_registry(RegistryEpoch::new(1, 1).unwrap());
    assert_eq!(store.phase(&native), Ok(OwnershipState::Revoked));
    error(store.queue_work(view.clone()), StoreError::WrongState);
    assert_eq!(
        view.registry()
            .resolve(RuntimeStateRef {
                epoch: view.tag().registry_epoch,
                id: DenseRuntimeStateId(0)
            })
            .unwrap()
            .name()
            .as_str(),
        "test:block_0"
    );
}

#[test]
fn invalidated_registry_epoch_cannot_reenter_through_a_new_allocation() {
    let (mut store, java) = fixture();
    store.invalidate_registry(RegistryEpoch::new(1, 1).unwrap());
    store.destroy(&java).unwrap();
    error(
        store.insert_java(context(2), token(101), registry(), data()),
        StoreError::InvalidatedRegistryEpoch,
    );
    let unused_epoch = RegistryEpoch::new(55, 1).unwrap();
    store.invalidate_registry(unused_epoch);
    let previously_unseen = std::sync::Arc::new(
        rustcraft_core::StateRegistry::new(unused_epoch, registry().states().to_vec()).unwrap(),
    );
    error(
        store.insert_java(context(2), token(101), previously_unseen, data()),
        StoreError::InvalidatedRegistryEpoch,
    );
}

#[test]
fn one_epoch_cannot_alias_two_semantic_tables_even_after_destruction() {
    let (mut store, java) = fixture();
    store.destroy(&java).unwrap();
    let mut changed = registry().states().to_vec();
    changed.swap(0, 1);
    let changed = std::sync::Arc::new(
        rustcraft_core::StateRegistry::new(RegistryEpoch::new(1, 1).unwrap(), changed).unwrap(),
    );
    error(
        store.insert_java(context(2), token(101), changed, data()),
        StoreError::RegistryBindingMismatch,
    );
    // Identical table contents under the same epoch need not share an Arc.
    store
        .insert_java(context(2), token(101), registry(), data())
        .unwrap();
}

#[test]
fn explicit_cancellation_and_foreign_results_cannot_consume_local_work() {
    let (mut a, java_a) = fixture();
    let (ha, _) = rust_owned(&mut a, &java_a, "native");
    let (mut b, java_b) = fixture();
    let (hb, _) = rust_owned(&mut b, &java_b, "native");
    let ja = a
        .queue_work(a.view(&ha, ViewKind::Query, artifact_context()).unwrap())
        .unwrap();
    let jb = b
        .queue_work(b.view(&hb, ViewKind::Query, artifact_context()).unwrap())
        .unwrap();
    assert_ne!(ja.id(), jb.id()); // Origin store distinguishes equal local nonce sequences.
    error(
        a.publish_work(jb.complete(vec![2])),
        StoreError::ForeignStore,
    );
    assert_eq!(&*a.publish_work(ja.complete(vec![1])).unwrap(), &[1]);
    let job = a
        .queue_work(a.view(&ha, ViewKind::Query, artifact_context()).unwrap())
        .unwrap();
    assert!(a.cancel_work(job.id()));
    error(
        a.publish_work(job.complete(vec![3])),
        StoreError::CancelledWork,
    );
}

#[test]
fn foreign_cancellation_cannot_remove_same_nonce_work_in_another_store() {
    let (mut a, java_a) = fixture();
    let (ha, _) = rust_owned(&mut a, &java_a, "native");
    let (mut b, java_b) = fixture();
    let (hb, _) = rust_owned(&mut b, &java_b, "native");
    let local = a
        .queue_work(a.view(&ha, ViewKind::Query, artifact_context()).unwrap())
        .unwrap();
    let foreign = b
        .queue_work(b.view(&hb, ViewKind::Query, artifact_context()).unwrap())
        .unwrap();
    assert!(
        !a.cancel_work(foreign.id()),
        "foreign cancellation consumed local work"
    );
    assert_eq!(&*a.publish_work(local.complete(vec![1])).unwrap(), &[1]);
    assert_eq!(&*b.publish_work(foreign.complete(vec![2])).unwrap(), &[2]);
}

#[test]
fn abandoned_writer_lease_fails_closed_instead_of_silently_restoring_mutation() {
    let (mut store, handle) = fixture();
    {
        let _abandoned = store.begin_write(&handle, WriterSide::Java).unwrap();
    }
    error(
        store.begin_write(&handle, WriterSide::Java),
        StoreError::Busy,
    );
    error(store.destroy(&handle), StoreError::Busy);
}

#[test]
fn unknown_states_and_out_of_bounds_writes_do_not_mutate_data() {
    let (mut store, java) = fixture();
    let (handle, _) = rust_owned(&mut store, &java, "native");
    let view = store
        .view(&handle, ViewKind::Query, artifact_context())
        .unwrap();
    let writer = store.begin_write(&handle, WriterSide::Rust).unwrap();
    error(
        store.set_state(&writer, SectionCoord(-4), 0, DenseRuntimeStateId(70000)),
        StoreError::Registry(CoreError::UnknownRuntimeState),
    );
    error(
        store.set_state(&writer, SectionCoord(0), 0, DenseRuntimeStateId(1)),
        StoreError::MissingSection,
    );
    error(
        store.set_state(&writer, SectionCoord(-4), 4096, DenseRuntimeStateId(1)),
        StoreError::Section(SectionError::CellOutsideSection),
    );
    store.finish_write(writer).unwrap();
    assert_eq!(
        view.tag().generations,
        store
            .view(&handle, ViewKind::Query, artifact_context())
            .unwrap()
            .tag()
            .generations
    );
}

#[test]
fn retained_store_accepts_registry_dense_ids_above_u16_without_wire_casting() {
    let mut store = RetainedStore::new().unwrap();
    let registry = Arc::new(
        StateRegistry::new(
            RegistryEpoch::new(1, 1).unwrap(),
            (0..70002)
                .map(|i| SemanticStateKey::simple(&format!("test:block_{i}")).unwrap())
                .collect(),
        )
        .unwrap(),
    );
    let section = NativeSection::uniform(DenseRuntimeStateId(70000), layout_policy()).unwrap();
    let chunk = NativeChunk::new(
        WorldBounds::new(SectionCoord(-4), 32).unwrap(),
        vec![(SectionCoord(-4), section)],
    )
    .unwrap();
    let java = store
        .insert_java(
            context(1),
            token(100),
            registry,
            SnapshotData::new(chunk, vec![], vec![]),
        )
        .unwrap();
    let (handle, _) = rust_owned(&mut store, &java, "native");
    let writer = store.begin_write(&handle, WriterSide::Rust).unwrap();
    store
        .set_state(&writer, SectionCoord(-4), 1, DenseRuntimeStateId(70001))
        .unwrap();
    store.finish_write(writer).unwrap();
    let view = store
        .view(&handle, ViewKind::Persistence, artifact_context())
        .unwrap();
    assert_eq!(state(&view, 1), DenseRuntimeStateId(70001));
}

#[test]
fn same_allocation_cannot_reappear_in_new_incarnation_while_old_owner_lives() {
    let (mut store, java) = fixture();
    let (_native, _) = rust_owned(&mut store, &java, "native");
    assert!(matches!(
        store.insert_java(context(2), token(100), registry(), data()),
        Err(StoreError::Capability(
            native_capability::Error::OwnershipConflict
        ))
    ));
}
