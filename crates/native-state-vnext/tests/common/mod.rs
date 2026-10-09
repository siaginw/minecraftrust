use native_capability::*;
use native_state_vnext::{section::*, store::*};
use rustcraft_core::*;
use std::sync::Arc;

pub fn token(n: u8) -> OpaqueToken {
    OpaqueToken::model_input([n; 16]).unwrap()
}
pub fn digest(n: u8) -> Digest {
    Digest::model_input([n; 32]).unwrap()
}
pub fn context(incarnation: u64) -> Context {
    Context {
        session: token(1),
        world: token(2),
        incarnation: Epoch::new(incarnation).unwrap(),
    }
}
pub fn layout_policy() -> LayoutPolicy {
    LayoutPolicy {
        promote_unique: 128,
        demote_unique: 32,
        linear_lookup_max: 16,
        cold_sweeps: 2,
        cold_write_budget: 64,
        hot_write_threshold: 2048,
    }
}
pub fn artifact_context() -> ArtifactContext {
    ArtifactContext {
        protocol_version: token(20),
        dimension_rules: token(21),
        recipient_rules: token(22),
    }
}
pub fn exact_type(n: u8) -> ExactType {
    ExactType {
        loader: token(3),
        runtime_class: token(n),
        semantic_sha256: digest(1),
        declaration_order_sha256: digest(2),
    }
}
pub fn data() -> SnapshotData {
    let bounds = WorldBounds::new(SectionCoord(-4), 32).unwrap();
    let section = NativeSection::uniform(DenseRuntimeStateId(0), layout_policy()).unwrap();
    SnapshotData::new(
        NativeChunk::new(bounds, vec![(SectionCoord(-4), section)]).unwrap(),
        vec![0; 32],
        vec![BiomeKey::new("test:plains").unwrap()],
    )
}
pub fn registry() -> Arc<StateRegistry> {
    Arc::new(
        StateRegistry::new(
            RegistryEpoch::new(1, 1).unwrap(),
            (0..256)
                .map(|i| SemanticStateKey::simple(&format!("test:block_{i}")).unwrap())
                .collect(),
        )
        .unwrap(),
    )
}
pub fn fixture() -> (RetainedStore, Handle) {
    let mut store = RetainedStore::new().unwrap();
    let handle = store
        .insert_java(context(1), token(100), registry(), data())
        .unwrap();
    (store, handle)
}
pub fn capability_spec(store: &RetainedStore, handle: &Handle, key: &str) -> CapabilitySpec {
    CapabilitySpec {
        key: key.into(),
        operation: "retained-state-model".into(),
        state: CapabilityState::AuthorityModel,
        ownership_mode: OwnershipMode::NativeExclusiveModel,
        resource: store.resource(handle).unwrap(),
        scope: RuntimeScope {
            context: handle.context().clone(),
            dimension: 0,
            provider: exact_type(4),
            storage_family: "vnext-model".into(),
            registry: RegistryBinding {
                object: token(5),
                epoch: Epoch::new(1).unwrap(),
                content_sha256: digest(3),
                state_width_bits: 32,
            },
            version: VersionBinding {
                adapter: token(6),
                epoch: Epoch::new(1).unwrap(),
                implementation_sha256: digest(4),
            },
        },
        evidence: ModelEvidence {
            profile_id: "non-authorizing-fixture".into(),
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
            revision: Epoch::new(1).unwrap(),
            require_no_tile_entities: true,
        },
        dependencies: vec![],
        revocations: mandatory_revocations(),
    }
}
pub fn bind(store: &mut RetainedStore, handle: &Handle, key: &str) -> CapabilityBinding {
    let spec = capability_spec(store, handle, key);
    let observation = observation(&spec);
    let capability = store.install_capabilities(vec![spec]).unwrap().remove(0);
    CapabilityBinding {
        capability,
        observation,
        registry_epoch: RegistryEpoch::new(1, 1).unwrap(),
    }
}
pub fn observation(spec: &CapabilitySpec) -> Observation {
    Observation {
        operation: spec.operation.clone(),
        resource: spec.resource.clone(),
        scope: spec.scope.clone(),
        receiver_type: exact_type(7),
        receiver_object: token(8),
        state_set_sha256: digest(9),
        state_revision: Epoch::new(1).unwrap(),
        has_tile_entities: false,
    }
}
pub fn rust_owned(
    store: &mut RetainedStore,
    handle: &Handle,
    key: &str,
) -> (Handle, CapabilityBinding) {
    let binding = bind(store, handle, key);
    let ticket = store.begin_native_handoff(handle, binding.clone()).unwrap();
    store.stage_after_quiescence(&ticket).unwrap();
    let outcome = store
        .publish_handoff(ticket, PublicationFault::None)
        .unwrap();
    assert!(matches!(outcome, PublicationOutcome::Committed(_)));
    (outcome.handle().clone(), binding)
}
