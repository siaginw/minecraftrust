use native_capability::*;
use std::collections::BTreeSet;
use std::fmt::Debug;

fn token(value: u8) -> OpaqueToken {
    OpaqueToken::model_input([value; 16]).unwrap()
}
fn digest(value: u8) -> Digest {
    Digest::model_input([value; 32]).unwrap()
}
fn epoch(value: u64) -> Epoch {
    Epoch::new(value).unwrap()
}
fn context() -> Context {
    Context {
        session: token(1),
        world: token(2),
        incarnation: epoch(1),
    }
}
fn exact_type(value: u8) -> ExactType {
    ExactType {
        loader: token(3),
        runtime_class: token(value),
        semantic_sha256: digest(1),
        declaration_order_sha256: digest(2),
    }
}
fn scope() -> RuntimeScope {
    RuntimeScope {
        context: context(),
        dimension: 0,
        provider: exact_type(4),
        storage_family: "family-A".into(),
        registry: RegistryBinding {
            object: token(5),
            epoch: epoch(1),
            content_sha256: digest(3),
            state_width_bits: 13,
        },
        version: VersionBinding {
            adapter: token(6),
            epoch: epoch(1),
            implementation_sha256: digest(4),
        },
    }
}
fn fixture() -> (ModelRegistry, ResourceHandle) {
    let mut registry = ModelRegistry::new().unwrap();
    let resource = registry.register_resource(context(), token(100)).unwrap();
    (registry, resource)
}
fn spec(key: &str, resource: &ResourceHandle, state: CapabilityState) -> CapabilitySpec {
    let ownership_mode = match state {
        CapabilityState::Java => OwnershipMode::Java,
        CapabilityState::AuthorityModel => OwnershipMode::NativeExclusiveModel,
        _ => OwnershipMode::OwnedSnapshot,
    };
    CapabilitySpec {
        key: key.into(),
        operation: key.into(),
        state,
        ownership_mode,
        resource: resource.clone(),
        scope: scope(),
        evidence: ModelEvidence {
            profile_id: "explicit-model-profile".into(),
            profile_sha256: digest(5),
            certificate_sha256: digest(6),
            writer_evidence_sha256: digest(7),
            lifecycle_evidence_sha256: digest(8),
        },
        receivers: ReceiverRestrictions {
            exact_types: [exact_type(7)].into_iter().collect(),
            exact_object: Some(token(8)),
        },
        states: StateRestrictions {
            allowed_state_set_sha256: digest(9),
            revision: epoch(1),
            require_no_tile_entities: true,
        },
        dependencies: vec![],
        revocations: mandatory_revocations(),
    }
}
fn observation(spec: &CapabilitySpec) -> Observation {
    Observation {
        operation: spec.operation.clone(),
        resource: spec.resource.clone(),
        scope: spec.scope.clone(),
        receiver_type: spec.receivers.exact_types.iter().next().unwrap().clone(),
        receiver_object: spec.receivers.exact_object.unwrap_or(token(8)),
        state_set_sha256: spec.states.allowed_state_set_sha256,
        state_revision: spec.states.revision,
        has_tile_entities: false,
    }
}
fn install(registry: &mut ModelRegistry, spec: CapabilitySpec) -> CapabilityHandle {
    registry.install_batch(vec![spec]).unwrap().remove(0)
}
fn dependency(key: &str, ownership: DependencyOwnership) -> DependencyRequest {
    DependencyRequest {
        key: key.into(),
        minimum_state: CapabilityState::ShadowValidated,
        ownership,
    }
}
fn error<T: Debug>(result: Result<T, Error>, expected: Error) {
    assert_eq!(result.unwrap_err(), expected);
}

#[test]
fn zero_identity_and_epoch_inputs_refuse() {
    error(OpaqueToken::model_input([0; 16]), Error::InvalidIdentity);
    error(Digest::model_input([0; 32]), Error::InvalidIdentity);
    error(Epoch::new(0), Error::InvalidIdentity);
}

#[test]
fn mixed_operations_worlds_and_storage_do_not_share_a_global_boolean() {
    let (mut model, storage) = fixture();
    let framing = model.register_resource(context(), token(101)).unwrap();
    let compression = model.register_resource(context(), token(102)).unwrap();
    let alternate = model.register_resource(context(), token(103)).unwrap();
    let mut twilight_context = context();
    twilight_context.world = token(55);
    let twilight = model
        .register_resource(twilight_context.clone(), token(104))
        .unwrap();
    let frame = spec("framing", &framing, CapabilityState::AuthorityModel);
    let compress = spec("compression", &compression, CapabilityState::AuthorityModel);
    let shadow = spec("chunk-family-A", &storage, CapabilityState::ShadowValidated);
    let mut java = spec("chunk-family-B", &alternate, CapabilityState::Java);
    java.scope.storage_family = "family-B".into();
    let te = spec("tile-entity-chunks", &storage, CapabilityState::Java);
    let light = spec("lighting", &storage, CapabilityState::Java);
    let mut dimension = spec("twilight-chunks", &twilight, CapabilityState::Java);
    dimension.scope.context = twilight_context;
    dimension.scope.dimension = 7;
    dimension.scope.provider = exact_type(55);
    let handles = model
        .install_batch(vec![
            frame.clone(),
            compress.clone(),
            shadow.clone(),
            java.clone(),
            te,
            light,
            dimension,
        ])
        .unwrap();
    model.model_release_java(&framing).unwrap();
    model.model_release_java(&compression).unwrap();
    let frame_lease = model
        .model_claim_native(&handles[0], &observation(&frame))
        .unwrap();
    let compress_lease = model
        .model_claim_native(&handles[1], &observation(&compress))
        .unwrap();
    for (index, case) in [frame, compress, shadow].iter().enumerate() {
        let current = observation(case);
        let ticket = model.prepare(&handles[index], &current).unwrap();
        assert!(!model
            .commit(ticket, &current)
            .unwrap()
            .production_authority());
    }
    error(
        model.prepare(&handles[3], &observation(&java)),
        Error::JavaFallback,
    );
    assert_eq!(
        model.status(&handles[4]),
        Ok(Status::Active(CapabilityState::Java))
    );
    assert_eq!(
        model.status(&handles[5]),
        Ok(Status::Active(CapabilityState::Java))
    );
    assert_eq!(
        model.status(&handles[6]),
        Ok(Status::Active(CapabilityState::Java))
    );
    assert!(!production_authority_enabled());
    model
        .model_release_native_after_quiescence(frame_lease)
        .unwrap();
    model
        .model_release_native_after_quiescence(compress_lease)
        .unwrap();
}

#[test]
fn complete_identity_and_bound_dependency_generations_are_exposed_together() {
    let (mut model, resource) = fixture();
    let leaf = spec(
        "writer-closure",
        &resource,
        CapabilityState::ShadowValidated,
    );
    let mut root = spec("packet", &resource, CapabilityState::ShadowValidated);
    root.dependencies.push(dependency(
        "writer-closure",
        DependencyOwnership::JavaRetained,
    ));
    let handles = model.install_batch(vec![root.clone(), leaf]).unwrap();
    let (identity, dependencies) = model.identity(&handles[0]).unwrap();
    assert_eq!(identity, &root);
    assert_eq!(dependencies[0].handle, handles[1]);
    assert_eq!(dependencies[0].ownership, DependencyOwnership::JavaRetained);
}

#[test]
fn scope_and_operation_mismatches_refuse() {
    let (mut model, resource) = fixture();
    let item = spec("packet", &resource, CapabilityState::ShadowValidated);
    let handle = install(&mut model, item.clone());
    let observed = observation(&item);
    type Mutation = Box<dyn Fn(&mut Observation)>;
    let mutations: Vec<Mutation> = vec![
        Box::new(|o| o.scope.context.session = token(77)),
        Box::new(|o| o.scope.context.world = token(77)),
        Box::new(|o| o.scope.context.incarnation = epoch(2)),
        Box::new(|o| o.scope.dimension = 7),
        Box::new(|o| o.scope.provider.loader = token(77)),
        Box::new(|o| o.scope.provider.runtime_class = token(77)),
        Box::new(|o| o.scope.provider.semantic_sha256 = digest(77)),
        Box::new(|o| o.scope.provider.declaration_order_sha256 = digest(77)),
        Box::new(|o| o.scope.storage_family = "family-B".into()),
        Box::new(|o| o.scope.registry.object = token(77)),
        Box::new(|o| o.scope.registry.epoch = epoch(2)),
        Box::new(|o| o.scope.registry.content_sha256 = digest(77)),
        Box::new(|o| o.scope.registry.state_width_bits = 14),
        Box::new(|o| o.scope.version.adapter = token(77)),
        Box::new(|o| o.scope.version.epoch = epoch(2)),
        Box::new(|o| o.scope.version.implementation_sha256 = digest(77)),
    ];
    for mutate in mutations {
        let mut other = observed.clone();
        mutate(&mut other);
        error(model.prepare(&handle, &other), Error::ScopeMismatch);
    }
    let mut other = observed;
    other.operation = "compression".into();
    error(model.prepare(&handle, &other), Error::OperationMismatch);
}

#[test]
fn receiver_class_loader_order_hash_object_and_state_restrictions_refuse() {
    let (mut model, resource) = fixture();
    let item = spec("packet", &resource, CapabilityState::ShadowValidated);
    let handle = install(&mut model, item.clone());
    let initial = observation(&item);
    let mut cases = vec![];
    let mut o = initial.clone();
    o.receiver_type.loader = token(60);
    cases.push(o);
    let mut o = initial.clone();
    o.receiver_type.runtime_class = token(60);
    cases.push(o);
    let mut o = initial.clone();
    o.receiver_type.declaration_order_sha256 = digest(60);
    cases.push(o);
    let mut o = initial.clone();
    o.receiver_object = token(60);
    cases.push(o);
    for o in cases {
        error(model.prepare(&handle, &o), Error::ReceiverMismatch);
    }
    let mut cases = vec![];
    let mut o = initial.clone();
    o.state_set_sha256 = digest(60);
    cases.push(o);
    let mut o = initial.clone();
    o.state_revision = epoch(2);
    cases.push(o);
    let mut o = initial;
    o.has_tile_entities = true;
    cases.push(o);
    for o in cases {
        error(model.prepare(&handle, &o), Error::StateMismatch);
    }
}

#[test]
fn eligible_is_not_shadow_validated_and_java_is_not_native() {
    let (mut model, resource) = fixture();
    let item = spec("eligible", &resource, CapabilityState::Eligible);
    let handle = install(&mut model, item.clone());
    error(
        model.prepare(&handle, &observation(&item)),
        Error::NotShadowValidated,
    );
    error(
        model.model_claim_native(&handle, &observation(&item)),
        Error::OwnershipRequirement,
    );
}

#[test]
fn malformed_specs_and_missing_revocation_conditions_are_atomic() {
    let (mut model, resource) = fixture();
    let good = spec("good", &resource, CapabilityState::ShadowValidated);
    let mut bad = good.clone();
    bad.key = "bad".into();
    bad.receivers.exact_types.clear();
    error(
        model.install_batch(vec![good.clone(), bad]),
        Error::InvalidSpec,
    );
    let mut bad = good.clone();
    bad.revocations.remove(&RevocationCondition::Registry);
    error(
        model.install_batch(vec![bad]),
        Error::MissingRevocationCondition,
    );
    let mut bad = good.clone();
    bad.scope.registry.state_width_bits = 0;
    error(model.install_batch(vec![bad]), Error::InvalidSpec);
    let mut bad = good.clone();
    bad.evidence.profile_id.clear();
    error(model.install_batch(vec![bad]), Error::InvalidSpec);
    let mut bad = good.clone();
    bad.ownership_mode = OwnershipMode::Java;
    error(model.install_batch(vec![bad]), Error::InvalidSpec);
    install(&mut model, good);
}

#[test]
fn missing_duplicate_and_insufficient_dependencies_refuse() {
    let (mut model, resource) = fixture();
    let leaf = spec("leaf", &resource, CapabilityState::Eligible);
    let mut root = spec("root", &resource, CapabilityState::ShadowValidated);
    root.dependencies
        .push(dependency("leaf", DependencyOwnership::Any));
    error(
        model.install_batch(vec![root.clone()]),
        Error::MissingDependency,
    );
    error(
        model.install_batch(vec![root.clone(), leaf.clone()]),
        Error::InsufficientDependency,
    );
    root.dependencies
        .push(dependency("leaf", DependencyOwnership::Any));
    error(
        model.install_batch(vec![root, leaf.clone()]),
        Error::DuplicateDependency,
    );
    let handle = install(&mut model, leaf.clone());
    error(model.install_batch(vec![leaf]), Error::DuplicateCapability);
    assert_eq!(
        model.status(&handle),
        Ok(Status::Active(CapabilityState::Eligible))
    );
    error(model.install_batch(vec![]), Error::EmptyBatch);
}

#[test]
fn self_cycle_and_multinode_cycle_do_not_publish_any_part() {
    let (mut model, resource) = fixture();
    let mut a = spec("a", &resource, CapabilityState::ShadowValidated);
    a.dependencies
        .push(dependency("a", DependencyOwnership::Any));
    error(model.install_batch(vec![a.clone()]), Error::DependencyCycle);
    let mut b = spec("b", &resource, CapabilityState::ShadowValidated);
    a.dependencies[0].key = "b".into();
    b.dependencies
        .push(dependency("a", DependencyOwnership::Any));
    error(
        model.install_batch(vec![a.clone(), b.clone()]),
        Error::DependencyCycle,
    );
    b.dependencies.clear();
    let handles = model.install_batch(vec![a, b]).unwrap();
    assert_eq!(handles[0].generation(), 1);
    assert_eq!(handles[1].generation(), 1);
}

#[test]
fn cross_incarnation_dependency_is_rejected() {
    let (mut model, resource) = fixture();
    let mut other_context = context();
    other_context.incarnation = epoch(2);
    let other_resource = model
        .register_resource(other_context.clone(), token(110))
        .unwrap();
    let mut leaf = spec("leaf", &other_resource, CapabilityState::ShadowValidated);
    leaf.scope.context = other_context;
    let mut root = spec("root", &resource, CapabilityState::ShadowValidated);
    root.dependencies
        .push(dependency("leaf", DependencyOwnership::Any));
    error(
        model.install_batch(vec![root, leaf]),
        Error::DependencyScopeMismatch,
    );
}

#[test]
fn late_revocation_propagates_transitively_without_affecting_other_operations() {
    let (mut model, resource) = fixture();
    let leaf = spec("leaf", &resource, CapabilityState::ShadowValidated);
    let mut middle = spec("middle", &resource, CapabilityState::ShadowValidated);
    middle
        .dependencies
        .push(dependency("leaf", DependencyOwnership::Any));
    let mut root = spec("root", &resource, CapabilityState::ShadowValidated);
    root.dependencies
        .push(dependency("middle", DependencyOwnership::Any));
    let separate = spec("separate", &resource, CapabilityState::ShadowValidated);
    let handles = model
        .install_batch(vec![leaf.clone(), middle, root.clone(), separate.clone()])
        .unwrap();
    let pending = model.prepare(&handles[2], &observation(&root)).unwrap();
    let independent = model.prepare(&handles[3], &observation(&separate)).unwrap();
    assert_eq!(model.revoke(&handles[0]).unwrap().len(), 3);
    error(model.commit(pending, &observation(&root)), Error::Revoked);
    assert_eq!(
        model.status(&handles[1]),
        Ok(Status::Revoked(RevocationReason::Dependency))
    );
    model.commit(independent, &observation(&separate)).unwrap();
    install(&mut model, leaf);
    assert_eq!(
        model.status(&handles[2]),
        Ok(Status::Revoked(RevocationReason::Dependency))
    );
}

#[test]
fn reinstall_same_identifier_cannot_resurrect_old_ticket_aba() {
    let (mut model, resource) = fixture();
    let item = spec("packet", &resource, CapabilityState::ShadowValidated);
    let old = install(&mut model, item.clone());
    let ticket = model.prepare(&old, &observation(&item)).unwrap();
    model.revoke(&old).unwrap();
    let new = install(&mut model, item.clone());
    assert_eq!(new.generation(), old.generation() + 1);
    error(
        model.commit(ticket, &observation(&item)),
        Error::StaleGeneration,
    );
    error(
        model.prepare(&old, &observation(&item)),
        Error::StaleGeneration,
    );
    let ticket = model.prepare(&new, &observation(&item)).unwrap();
    model.commit(ticket, &observation(&item)).unwrap();
}

#[test]
fn ownership_domains_deduplicate_labels_and_include_initial_java_owner() {
    let (mut model, resource) = fixture();
    assert_eq!(
        model.register_resource(context(), token(100)).unwrap(),
        resource
    );
    assert_eq!(model.owner(&resource), Ok(&MutableOwner::Java));
    let a = spec("storage-A", &resource, CapabilityState::AuthorityModel);
    let mut b = spec("storage-B", &resource, CapabilityState::AuthorityModel);
    b.scope.storage_family = "invented-disjoint-label".into();
    let handles = model.install_batch(vec![a.clone(), b.clone()]).unwrap();
    error(
        model.model_claim_native(&handles[0], &observation(&a)),
        Error::OwnershipConflict,
    );
    model.model_release_java(&resource).unwrap();
    let lease = model
        .model_claim_native(&handles[0], &observation(&a))
        .unwrap();
    error(
        model.model_claim_native(&handles[1], &observation(&b)),
        Error::OwnershipConflict,
    );
    error(
        model.model_restore_java(&resource),
        Error::OwnershipConflict,
    );
    model.model_release_native_after_quiescence(lease).unwrap();
    model.model_restore_java(&resource).unwrap();
}

#[test]
fn dependency_java_and_native_ownership_are_checked_at_use() {
    let (mut model, resource) = fixture();
    let root_resource = model.register_resource(context(), token(111)).unwrap();
    let leaf = spec("storage", &resource, CapabilityState::AuthorityModel);
    let mut java_consumer = spec(
        "java-reader",
        &root_resource,
        CapabilityState::ShadowValidated,
    );
    java_consumer
        .dependencies
        .push(dependency("storage", DependencyOwnership::JavaRetained));
    let mut native_consumer = spec(
        "native-reader",
        &root_resource,
        CapabilityState::ShadowValidated,
    );
    native_consumer
        .dependencies
        .push(dependency("storage", DependencyOwnership::NativeOwner));
    let handles = model
        .install_batch(vec![
            leaf.clone(),
            java_consumer.clone(),
            native_consumer.clone(),
        ])
        .unwrap();
    let ticket = model
        .prepare(&handles[1], &observation(&java_consumer))
        .unwrap();
    error(
        model.prepare(&handles[2], &observation(&native_consumer)),
        Error::OwnershipRequirement,
    );
    model.model_release_java(&resource).unwrap();
    let lease = model
        .model_claim_native(&handles[0], &observation(&leaf))
        .unwrap();
    error(
        model.commit(ticket, &observation(&java_consumer)),
        Error::Revoked,
    );
    let ticket = model
        .prepare(&handles[2], &observation(&native_consumer))
        .unwrap();
    model.model_release_native_after_quiescence(lease).unwrap();
    error(
        model.commit(ticket, &observation(&native_consumer)),
        Error::Revoked,
    );
}

#[test]
fn java_owner_release_restore_aba_invalidates_pending_ticket() {
    let (mut model, resource) = fixture();
    let item = spec("packet", &resource, CapabilityState::ShadowValidated);
    let handle = install(&mut model, item.clone());
    let ticket = model.prepare(&handle, &observation(&item)).unwrap();
    model.model_release_java(&resource).unwrap();
    model.model_restore_java(&resource).unwrap();
    error(
        model.commit(ticket, &observation(&item)),
        Error::OwnerGenerationChanged,
    );
}

#[test]
fn revoked_native_owner_stays_quarantined_until_old_generation_quiesces() {
    let (mut model, resource) = fixture();
    let item = spec("owner", &resource, CapabilityState::AuthorityModel);
    let old = install(&mut model, item.clone());
    model.model_release_java(&resource).unwrap();
    let lease = model.model_claim_native(&old, &observation(&item)).unwrap();
    let ticket = model.prepare(&old, &observation(&item)).unwrap();
    model.revoke(&old).unwrap();
    assert_eq!(
        model.owner(&resource),
        Ok(&MutableOwner::Quarantined(old.clone()))
    );
    let new = install(&mut model, item.clone());
    error(
        model.commit(ticket, &observation(&item)),
        Error::StaleGeneration,
    );
    error(
        model.model_claim_native(&new, &observation(&item)),
        Error::OwnershipConflict,
    );
    error(
        model.model_restore_java(&resource),
        Error::OwnershipConflict,
    );
    model.model_release_native_after_quiescence(lease).unwrap();
    let lease = model.model_claim_native(&new, &observation(&item)).unwrap();
    model.model_release_native_after_quiescence(lease).unwrap();
}

#[test]
fn resource_retirement_and_reincarnation_require_quiescence_even_for_same_root_token() {
    let (mut model, resource) = fixture();
    let item = spec("owner", &resource, CapabilityState::AuthorityModel);
    let old = install(&mut model, item.clone());
    model.model_release_java(&resource).unwrap();
    let lease = model.model_claim_native(&old, &observation(&item)).unwrap();
    let mut next = context();
    next.incarnation = epoch(2);
    error(
        model.register_resource(next.clone(), token(100)),
        Error::OwnershipConflict,
    );
    model
        .invalidate(Invalidation::Incarnation(context()))
        .unwrap();
    error(
        model.register_resource(context(), token(100)),
        Error::RetiredResource,
    );
    error(
        model.register_resource(next.clone(), token(100)),
        Error::OwnershipConflict,
    );
    model.model_release_native_after_quiescence(lease).unwrap();
    let new_resource = model.register_resource(next, token(100)).unwrap();
    assert_ne!(new_resource, resource);
    assert_eq!(model.owner(&new_resource), Ok(&MutableOwner::Java));
    error(model.prepare(&old, &observation(&item)), Error::Revoked);
}

#[test]
fn ended_session_cannot_create_new_resources_under_different_root_tokens() {
    let (mut model, resource) = fixture();
    model
        .invalidate(Invalidation::Session(context().session))
        .unwrap();
    error(
        model.register_resource(context(), token(120)),
        Error::RetiredResource,
    );
    model.model_release_java(&resource).unwrap();
    let mut next = context();
    next.session = token(90);
    model.register_resource(next, token(100)).unwrap();
}

#[test]
fn invalidated_certificate_writer_lifecycle_registry_and_version_cannot_be_reinstalled() {
    let events = vec![
        Invalidation::Certificate(digest(6)),
        Invalidation::WriterEvidence(digest(7)),
        Invalidation::LifecycleEvidence(digest(8)),
        Invalidation::Registry {
            object: token(5),
            epoch: epoch(1),
        },
        Invalidation::Version {
            adapter: token(6),
            epoch: epoch(1),
        },
    ];
    for event in events {
        let (mut model, resource) = fixture();
        let item = spec("packet", &resource, CapabilityState::ShadowValidated);
        let handle = install(&mut model, item.clone());
        let pending = model.prepare(&handle, &observation(&item)).unwrap();
        assert_eq!(model.invalidate(event).unwrap(), vec![handle]);
        error(model.commit(pending, &observation(&item)), Error::Revoked);
        error(model.install_batch(vec![item]), Error::InvalidatedEvidence);
    }
}

#[test]
fn invalidation_before_install_is_a_persistent_tombstone() {
    let (mut model, resource) = fixture();
    assert!(model
        .invalidate(Invalidation::Certificate(digest(6)))
        .unwrap()
        .is_empty());
    let mut item = spec("packet", &resource, CapabilityState::ShadowValidated);
    error(
        model.install_batch(vec![item.clone()]),
        Error::InvalidatedEvidence,
    );
    item.evidence.certificate_sha256 = digest(66);
    install(&mut model, item);
}

#[test]
fn changed_observation_after_prepare_never_commits() {
    let (mut model, resource) = fixture();
    let mut item = spec("packet", &resource, CapabilityState::ShadowValidated);
    item.states.require_no_tile_entities = false;
    let handle = install(&mut model, item.clone());
    let pending = model.prepare(&handle, &observation(&item)).unwrap();
    let mut current = observation(&item);
    current.has_tile_entities = true;
    // Both values satisfy the declared restriction, but the captured observation
    // changed between prepare and commit and must still be rejected.
    error(model.commit(pending, &current), Error::StateMismatch);
}

#[test]
fn foreign_registry_handles_cannot_cross_instance_boundaries() {
    let (mut first, resource) = fixture();
    let item = spec("same-name", &resource, CapabilityState::ShadowValidated);
    let handle = install(&mut first, item.clone());
    let (mut second, _) = fixture();
    error(
        second.prepare(&handle, &observation(&item)),
        Error::ForeignRegistry,
    );
    error(second.install_batch(vec![item]), Error::ForeignRegistry);
    error(second.model_release_java(&resource), Error::ForeignRegistry);
}

#[test]
fn no_implicit_owner_release_when_model_lease_is_dropped() {
    let (mut model, resource) = fixture();
    let item = spec("owner", &resource, CapabilityState::AuthorityModel);
    let handle = install(&mut model, item.clone());
    model.model_release_java(&resource).unwrap();
    let lease = model
        .model_claim_native(&handle, &observation(&item))
        .unwrap();
    drop(lease);
    error(
        model.model_restore_java(&resource),
        Error::OwnershipConflict,
    );
    assert_eq!(
        model.owner(&resource),
        Ok(&MutableOwner::NativeModel(handle))
    );
}

#[test]
fn registry_epoch_invalidation_preserves_independent_registry_branch() {
    let (mut model, resource) = fixture();
    let a = spec("a", &resource, CapabilityState::ShadowValidated);
    let mut b = spec("b", &resource, CapabilityState::ShadowValidated);
    b.scope.registry.object = token(66);
    let handles = model.install_batch(vec![a, b.clone()]).unwrap();
    let ticket = model.prepare(&handles[1], &observation(&b)).unwrap();
    assert_eq!(
        model
            .invalidate(Invalidation::Registry {
                object: token(5),
                epoch: epoch(1)
            })
            .unwrap(),
        vec![handles[0].clone()]
    );
    model.commit(ticket, &observation(&b)).unwrap();
}

#[test]
fn mandatory_revocation_identity_is_not_empty() {
    let conditions: BTreeSet<_> = mandatory_revocations();
    assert_eq!(conditions.len(), 10);
    assert!(conditions.contains(&RevocationCondition::Dependency));
    assert!(conditions.contains(&RevocationCondition::MutableOwner));
}

#[test]
fn quarantine_transition_fences_unrelated_snapshot_ticket_on_same_allocation() {
    let (mut model, resource) = fixture();
    let owner = spec("owner", &resource, CapabilityState::AuthorityModel);
    let snapshot = spec("snapshot", &resource, CapabilityState::ShadowValidated);
    let handles = model
        .install_batch(vec![owner.clone(), snapshot.clone()])
        .unwrap();
    model.model_release_java(&resource).unwrap();
    let lease = model
        .model_claim_native(&handles[0], &observation(&owner))
        .unwrap();
    let ticket = model.prepare(&handles[1], &observation(&snapshot)).unwrap();
    model.revoke(&handles[0]).unwrap();
    error(
        model.commit(ticket, &observation(&snapshot)),
        Error::OwnerGenerationChanged,
    );
    model.model_release_native_after_quiescence(lease).unwrap();
}
