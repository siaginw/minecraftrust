//! Non-authorizing capability model. No JNI, production gate, or live token issuer.
//!
//! All supplied evidence and opaque identities are model inputs, not verified grants.
//! AuthorityModel and ownership transitions simulate policy only. See the architecture
//! document for the missing certificate-to-live binding and quiescence adapters.
#![forbid(unsafe_code)]

use std::collections::{BTreeMap, BTreeSet, VecDeque};
use std::num::NonZeroU64;
use std::sync::atomic::{AtomicU64, Ordering};

static NEXT_REGISTRY: AtomicU64 = AtomicU64::new(1);

/// Deliberately constant: this crate cannot authorize an existing execution path.
pub const fn production_authority_enabled() -> bool {
    false
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord)]
pub struct OpaqueToken([u8; 16]);
impl OpaqueToken {
    /// A test/model input. This constructor does not qualify a JVM object or loader.
    pub fn model_input(bytes: [u8; 16]) -> Result<Self, Error> {
        if bytes == [0; 16] {
            return Err(Error::InvalidIdentity);
        }
        Ok(Self(bytes))
    }
}

#[cfg(test)]
mod counter_tests {
    use super::*;

    fn context() -> Context {
        Context {
            session: OpaqueToken::model_input([1; 16]).unwrap(),
            world: OpaqueToken::model_input([2; 16]).unwrap(),
            incarnation: Epoch::new(1).unwrap(),
        }
    }

    #[test]
    fn exhausted_resource_counter_never_publishes_a_partial_resource() {
        let mut model = ModelRegistry::new().unwrap();
        model.next_resource = u64::MAX;
        let result = model.register_resource(context(), OpaqueToken::model_input([3; 16]).unwrap());
        assert_eq!(result, Err(Error::CounterExhausted));
        assert!(model.resources.is_empty());
        assert!(model.resource_ids.is_empty());
        assert!(model.allocation_ids.is_empty());
    }

    #[test]
    fn exhausted_owner_counter_does_not_release_java_or_wrap() {
        let mut model = ModelRegistry::new().unwrap();
        let resource = model
            .register_resource(context(), OpaqueToken::model_input([3; 16]).unwrap())
            .unwrap();
        model
            .resources
            .get_mut(&resource.id)
            .unwrap()
            .owner_generation = u64::MAX;
        assert_eq!(
            model.model_release_java(&resource),
            Err(Error::CounterExhausted)
        );
        assert_eq!(model.owner(&resource), Ok(&MutableOwner::Java));
        assert_eq!(
            model.resource(&resource).unwrap().owner_generation,
            u64::MAX
        );
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord)]
pub struct Digest([u8; 32]);
impl Digest {
    pub fn model_input(bytes: [u8; 32]) -> Result<Self, Error> {
        if bytes == [0; 32] {
            return Err(Error::InvalidIdentity);
        }
        Ok(Self(bytes))
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord)]
pub struct Epoch(NonZeroU64);
impl Epoch {
    pub fn new(value: u64) -> Result<Self, Error> {
        NonZeroU64::new(value)
            .map(Self)
            .ok_or(Error::InvalidIdentity)
    }
}

/// Class names alone cannot fill this identity: actual class and loader tokens
/// and both CANONICAL_ID_V2 digest components are required.
#[derive(Clone, Debug, PartialEq, Eq, PartialOrd, Ord)]
pub struct ExactType {
    pub loader: OpaqueToken,
    pub runtime_class: OpaqueToken,
    pub semantic_sha256: Digest,
    pub declaration_order_sha256: Digest,
}

#[derive(Clone, Debug, PartialEq, Eq, PartialOrd, Ord)]
pub struct Context {
    pub session: OpaqueToken,
    pub world: OpaqueToken,
    pub incarnation: Epoch,
}

#[derive(Clone, Debug, PartialEq, Eq, PartialOrd, Ord)]
struct ResourceKey {
    context: Context,
    root_allocation: OpaqueToken,
}

/// Issued only by this model registry; operation and storage labels cannot split
/// an ownership domain. Alias/root identity qualification is an adapter obligation.
#[derive(Clone, Debug, PartialEq, Eq, PartialOrd, Ord)]
pub struct ResourceHandle {
    registry: u64,
    id: u64,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct RegistryBinding {
    pub object: OpaqueToken,
    pub epoch: Epoch,
    pub content_sha256: Digest,
    pub state_width_bits: u8,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct VersionBinding {
    pub adapter: OpaqueToken,
    pub epoch: Epoch,
    pub implementation_sha256: Digest,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct RuntimeScope {
    pub context: Context,
    pub dimension: i32,
    pub provider: ExactType,
    pub storage_family: String,
    pub registry: RegistryBinding,
    pub version: VersionBinding,
}

/// All hashes remain opaque claims here. H2 JSON cannot be imported as a live grant.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ModelEvidence {
    pub profile_id: String,
    pub profile_sha256: Digest,
    pub certificate_sha256: Digest,
    pub writer_evidence_sha256: Digest,
    pub lifecycle_evidence_sha256: Digest,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ReceiverRestrictions {
    pub exact_types: BTreeSet<ExactType>,
    pub exact_object: Option<OpaqueToken>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct StateRestrictions {
    pub allowed_state_set_sha256: Digest,
    pub revision: Epoch,
    pub require_no_tile_entities: bool,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord)]
pub enum CapabilityState {
    Java,
    Eligible,
    ShadowValidated,
    AuthorityModel,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum OwnershipMode {
    Java,
    OwnedSnapshot,
    NativeExclusiveModel,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum DependencyOwnership {
    Any,
    JavaRetained,
    NativeOwner,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct DependencyRequest {
    pub key: String,
    pub minimum_state: CapabilityState,
    pub ownership: DependencyOwnership,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord)]
pub enum RevocationCondition {
    Certificate,
    WriterEvidence,
    LifecycleEvidence,
    Session,
    Incarnation,
    Registry,
    Version,
    Resource,
    Dependency,
    MutableOwner,
}

/// Mandatory conditions cannot be removed by supplying a narrower policy.
pub fn mandatory_revocations() -> BTreeSet<RevocationCondition> {
    use RevocationCondition::*;
    [
        Certificate,
        WriterEvidence,
        LifecycleEvidence,
        Session,
        Incarnation,
        Registry,
        Version,
        Resource,
        Dependency,
        MutableOwner,
    ]
    .into_iter()
    .collect()
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct CapabilitySpec {
    pub key: String,
    pub operation: String,
    pub state: CapabilityState,
    pub ownership_mode: OwnershipMode,
    pub resource: ResourceHandle,
    pub scope: RuntimeScope,
    pub evidence: ModelEvidence,
    pub receivers: ReceiverRestrictions,
    pub states: StateRestrictions,
    pub dependencies: Vec<DependencyRequest>,
    pub revocations: BTreeSet<RevocationCondition>,
}

#[derive(Clone, Debug, PartialEq, Eq, PartialOrd, Ord)]
pub struct CapabilityHandle {
    registry: u64,
    key: String,
    generation: u64,
}
impl CapabilityHandle {
    pub fn key(&self) -> &str {
        &self.key
    }
    pub fn generation(&self) -> u64 {
        self.generation
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct BoundDependency {
    pub handle: CapabilityHandle,
    pub minimum_state: CapabilityState,
    pub ownership: DependencyOwnership,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum RevocationReason {
    Explicit,
    Event,
    Dependency,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Status {
    Active(CapabilityState),
    Revoked(RevocationReason),
}

#[derive(Clone, Debug)]
struct Entry {
    spec: CapabilitySpec,
    handle: CapabilityHandle,
    dependencies: Vec<BoundDependency>,
    revoked: Option<RevocationReason>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum MutableOwner {
    Java,
    Unowned,
    NativeModel(CapabilityHandle),
    Quarantined(CapabilityHandle),
}

#[derive(Clone, Debug)]
struct Resource {
    key: ResourceKey,
    owner: MutableOwner,
    owner_generation: u64,
    retired: bool,
}

/// A non-cloneable modeled native ownership lease. Dropping/leaking it never
/// silently restores Java ownership; explicit quiescence acknowledgement is needed.
#[derive(Debug)]
pub struct ModelOwnershipLease {
    resource: ResourceHandle,
    holder: CapabilityHandle,
    owner_generation: u64,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Observation {
    pub operation: String,
    pub resource: ResourceHandle,
    pub scope: RuntimeScope,
    pub receiver_type: ExactType,
    pub receiver_object: OpaqueToken,
    pub state_set_sha256: Digest,
    pub state_revision: Epoch,
    pub has_tile_entities: bool,
}

/// Prepared work is not a committed result. Commit consumes the ticket and
/// revalidates its complete capability closure and every ownership generation.
#[derive(Debug)]
pub struct ValidationTicket {
    handle: CapabilityHandle,
    observation: Observation,
    owners: BTreeMap<ResourceHandle, (u64, MutableOwner)>,
}

#[derive(Debug, PartialEq, Eq)]
pub struct ModelCommit {
    pub capability: CapabilityHandle,
    pub operation: String,
}
impl ModelCommit {
    pub const fn production_authority(&self) -> bool {
        false
    }
}

#[derive(Clone, Debug)]
pub enum Invalidation {
    Certificate(Digest),
    WriterEvidence(Digest),
    LifecycleEvidence(Digest),
    Session(OpaqueToken),
    Incarnation(Context),
    Registry { object: OpaqueToken, epoch: Epoch },
    Version { adapter: OpaqueToken, epoch: Epoch },
    Resource(ResourceHandle),
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Error {
    InvalidIdentity,
    InvalidSpec,
    MissingRevocationCondition,
    ForeignRegistry,
    UnknownResource,
    RetiredResource,
    UnknownCapability,
    StaleGeneration,
    Revoked,
    DuplicateCapability,
    MissingDependency,
    DuplicateDependency,
    DependencyCycle,
    DependencyScopeMismatch,
    InsufficientDependency,
    ScopeMismatch,
    OperationMismatch,
    ReceiverMismatch,
    StateMismatch,
    JavaFallback,
    NotShadowValidated,
    OwnershipConflict,
    OwnershipRequirement,
    OwnerGenerationChanged,
    CounterExhausted,
    EmptyBatch,
    InvalidatedEvidence,
}

/// Serialized in-memory model. `&mut self` linearizes install/revoke/ownership/
/// commit; it is not a runtime synchronization bridge to Java or Forge.
pub struct ModelRegistry {
    id: u64,
    resources: BTreeMap<u64, Resource>,
    resource_ids: BTreeMap<ResourceKey, u64>,
    allocation_ids: BTreeMap<OpaqueToken, u64>,
    entries: BTreeMap<String, Entry>,
    invalidations: Vec<Invalidation>,
    next_resource: u64,
}

impl ModelRegistry {
    pub fn new() -> Result<Self, Error> {
        let id = NEXT_REGISTRY
            .fetch_update(Ordering::Relaxed, Ordering::Relaxed, |old| {
                old.checked_add(1)
            })
            .map_err(|_| Error::CounterExhausted)?;
        Ok(Self {
            id,
            resources: BTreeMap::new(),
            resource_ids: BTreeMap::new(),
            allocation_ids: BTreeMap::new(),
            entries: BTreeMap::new(),
            invalidations: Vec::new(),
            next_resource: 1,
        })
    }

    /// Same canonical root allocation/context always returns the same domain,
    /// regardless of operation, profile, storage family, or receiver alias labels.
    /// Every new resource starts with Java as its sole modeled mutable owner.
    pub fn register_resource(
        &mut self,
        context: Context,
        root_allocation: OpaqueToken,
    ) -> Result<ResourceHandle, Error> {
        let key = ResourceKey {
            context,
            root_allocation,
        };
        if self.invalidations.iter().any(|event| match event {
            Invalidation::Session(session) => key.context.session == *session,
            Invalidation::Incarnation(context) => key.context == *context,
            _ => false,
        }) {
            return Err(Error::RetiredResource);
        }
        if let Some(id) = self.resource_ids.get(&key) {
            let handle = ResourceHandle {
                registry: self.id,
                id: *id,
            };
            self.resource(&handle)?;
            return Ok(handle);
        }
        // An old incarnation/session label cannot disguise the same underlying
        // allocation. Rebinding requires retirement AND explicit quiescence.
        if let Some(old_id) = self.allocation_ids.get(&root_allocation) {
            let old = &self.resources[old_id];
            if !old.retired || old.owner != MutableOwner::Unowned {
                return Err(Error::OwnershipConflict);
            }
        }
        let next = self
            .next_resource
            .checked_add(1)
            .ok_or(Error::CounterExhausted)?;
        let id = self.next_resource;
        self.resources.insert(
            id,
            Resource {
                key: key.clone(),
                owner: MutableOwner::Java,
                owner_generation: 1,
                retired: false,
            },
        );
        self.resource_ids.insert(key, id);
        self.allocation_ids.insert(root_allocation, id);
        self.next_resource = next;
        Ok(ResourceHandle {
            registry: self.id,
            id,
        })
    }

    fn resource(&self, handle: &ResourceHandle) -> Result<&Resource, Error> {
        let resource = self.resource_raw(handle)?;
        if resource.retired {
            return Err(Error::RetiredResource);
        }
        Ok(resource)
    }

    fn resource_raw(&self, handle: &ResourceHandle) -> Result<&Resource, Error> {
        if handle.registry != self.id {
            return Err(Error::ForeignRegistry);
        }
        let resource = self
            .resources
            .get(&handle.id)
            .ok_or(Error::UnknownResource)?;
        Ok(resource)
    }

    fn entry(&self, handle: &CapabilityHandle) -> Result<&Entry, Error> {
        if handle.registry != self.id {
            return Err(Error::ForeignRegistry);
        }
        let entry = self
            .entries
            .get(&handle.key)
            .ok_or(Error::UnknownCapability)?;
        if entry.handle != *handle {
            return Err(Error::StaleGeneration);
        }
        if entry.revoked.is_some() {
            return Err(Error::Revoked);
        }
        self.resource(&entry.spec.resource)?;
        Ok(entry)
    }

    pub fn status(&self, handle: &CapabilityHandle) -> Result<Status, Error> {
        if handle.registry != self.id {
            return Err(Error::ForeignRegistry);
        }
        let entry = self
            .entries
            .get(&handle.key)
            .ok_or(Error::UnknownCapability)?;
        if entry.handle != *handle {
            return Err(Error::StaleGeneration);
        }
        Ok(match &entry.revoked {
            Some(reason) => Status::Revoked(reason.clone()),
            None => Status::Active(entry.spec.state),
        })
    }

    /// Returned identity and generation-bound dependencies are immutable views.
    pub fn identity(
        &self,
        handle: &CapabilityHandle,
    ) -> Result<(&CapabilitySpec, &[BoundDependency]), Error> {
        let entry = self.entry(handle)?;
        Ok((&entry.spec, &entry.dependencies))
    }

    pub fn owner(&self, resource: &ResourceHandle) -> Result<&MutableOwner, Error> {
        Ok(&self.resource(resource)?.owner)
    }

    /// Transactional graph installation: no partial publication on malformed
    /// graphs. Replacing a revoked key creates a new generation; descendants
    /// remain revoked until explicitly requalified and reinstalled.
    pub fn install_batch(
        &mut self,
        specs: Vec<CapabilitySpec>,
    ) -> Result<Vec<CapabilityHandle>, Error> {
        if specs.is_empty() {
            return Err(Error::EmptyBatch);
        }
        let mut pending = BTreeMap::new();
        let mut handles = Vec::new();
        for spec in specs {
            self.validate_spec(&spec)?;
            if pending.contains_key(&spec.key) {
                return Err(Error::DuplicateCapability);
            }
            let generation = match self.entries.get(&spec.key) {
                Some(old) if old.revoked.is_none() => return Err(Error::DuplicateCapability),
                Some(old) => old
                    .handle
                    .generation
                    .checked_add(1)
                    .ok_or(Error::CounterExhausted)?,
                None => 1,
            };
            let handle = CapabilityHandle {
                registry: self.id,
                key: spec.key.clone(),
                generation,
            };
            handles.push(handle.clone());
            pending.insert(
                spec.key.clone(),
                Entry {
                    spec,
                    handle,
                    dependencies: Vec::new(),
                    revoked: None,
                },
            );
        }
        for entry in pending.values() {
            for request in &entry.spec.dependencies {
                let dependency = pending
                    .get(&request.key)
                    .or_else(|| self.entries.get(&request.key))
                    .filter(|entry| entry.revoked.is_none())
                    .ok_or(Error::MissingDependency)?;
                if dependency.spec.state < request.minimum_state {
                    return Err(Error::InsufficientDependency);
                }
                if dependency.spec.scope.context != entry.spec.scope.context {
                    return Err(Error::DependencyScopeMismatch);
                }
            }
        }
        let mut all = self.entries.clone();
        all.extend(pending.clone());
        for entry in pending.values_mut() {
            entry.dependencies = entry
                .spec
                .dependencies
                .iter()
                .map(|request| BoundDependency {
                    handle: all[&request.key].handle.clone(),
                    minimum_state: request.minimum_state,
                    ownership: request.ownership,
                })
                .collect();
        }
        all.extend(pending.clone());
        Self::check_cycles(&all)?;
        self.entries.extend(pending);
        Ok(handles)
    }

    fn validate_spec(&self, spec: &CapabilitySpec) -> Result<(), Error> {
        let label = |s: &str| !s.is_empty() && s.len() <= 256 && !s.chars().any(char::is_control);
        if !label(&spec.key)
            || !label(&spec.operation)
            || !label(&spec.scope.storage_family)
            || !label(&spec.evidence.profile_id)
            || spec.receivers.exact_types.is_empty()
            || !(1..=64).contains(&spec.scope.registry.state_width_bits)
        {
            return Err(Error::InvalidSpec);
        }
        if spec.revocations != mandatory_revocations() {
            return Err(Error::MissingRevocationCondition);
        }
        if self.resource(&spec.resource)?.key.context != spec.scope.context {
            return Err(Error::ScopeMismatch);
        }
        if self
            .invalidations
            .iter()
            .any(|event| Self::event_matches(event, spec))
        {
            return Err(Error::InvalidatedEvidence);
        }
        if (spec.state == CapabilityState::Java) != (spec.ownership_mode == OwnershipMode::Java) {
            return Err(Error::InvalidSpec);
        }
        if spec.state == CapabilityState::AuthorityModel
            && spec.ownership_mode != OwnershipMode::NativeExclusiveModel
        {
            return Err(Error::InvalidSpec);
        }
        let mut seen = BTreeSet::new();
        for dependency in &spec.dependencies {
            if !label(&dependency.key) {
                return Err(Error::InvalidSpec);
            }
            if !seen.insert(&dependency.key) {
                return Err(Error::DuplicateDependency);
            }
        }
        Ok(())
    }

    fn check_cycles(entries: &BTreeMap<String, Entry>) -> Result<(), Error> {
        let active: BTreeSet<_> = entries
            .values()
            .filter(|e| e.revoked.is_none())
            .map(|e| e.handle.clone())
            .collect();
        let mut remaining: BTreeMap<_, usize> = entries
            .values()
            .filter(|e| e.revoked.is_none())
            .map(|e| {
                (
                    e.handle.clone(),
                    e.dependencies
                        .iter()
                        .filter(|d| active.contains(&d.handle))
                        .count(),
                )
            })
            .collect();
        let mut ready: VecDeque<_> = remaining
            .iter()
            .filter(|(_, n)| **n == 0)
            .map(|(h, _)| h.clone())
            .collect();
        let mut visited = 0;
        while let Some(handle) = ready.pop_front() {
            visited += 1;
            for entry in entries.values().filter(|e| e.revoked.is_none()) {
                if entry.dependencies.iter().any(|d| d.handle == handle) {
                    let count = remaining.get_mut(&entry.handle).expect("active entry");
                    *count -= 1;
                    if *count == 0 {
                        ready.push_back(entry.handle.clone());
                    }
                }
            }
        }
        if visited == remaining.len() {
            Ok(())
        } else {
            Err(Error::DependencyCycle)
        }
    }

    fn closure(&self, handle: &CapabilityHandle) -> Result<BTreeSet<CapabilityHandle>, Error> {
        let mut seen = BTreeSet::new();
        let mut pending = vec![handle.clone()];
        while let Some(current) = pending.pop() {
            if !seen.insert(current.clone()) {
                continue;
            }
            let entry = self.entry(&current)?;
            for dependency in &entry.dependencies {
                let target = self.entry(&dependency.handle)?;
                if target.spec.state < dependency.minimum_state {
                    return Err(Error::InsufficientDependency);
                }
                let owner = &self.resource(&target.spec.resource)?.owner;
                match dependency.ownership {
                    DependencyOwnership::Any => {}
                    DependencyOwnership::JavaRetained if *owner == MutableOwner::Java => {}
                    DependencyOwnership::NativeOwner
                        if *owner == MutableOwner::NativeModel(target.handle.clone()) => {}
                    _ => return Err(Error::OwnershipRequirement),
                }
                pending.push(dependency.handle.clone());
            }
        }
        Ok(seen)
    }

    fn check_observation(&self, entry: &Entry, observation: &Observation) -> Result<(), Error> {
        if entry.spec.operation != observation.operation {
            return Err(Error::OperationMismatch);
        }
        if entry.spec.resource != observation.resource || entry.spec.scope != observation.scope {
            return Err(Error::ScopeMismatch);
        }
        if !entry
            .spec
            .receivers
            .exact_types
            .contains(&observation.receiver_type)
            || entry
                .spec
                .receivers
                .exact_object
                .is_some_and(|id| id != observation.receiver_object)
        {
            return Err(Error::ReceiverMismatch);
        }
        let states = &entry.spec.states;
        if states.allowed_state_set_sha256 != observation.state_set_sha256
            || states.revision != observation.state_revision
            || states.require_no_tile_entities && observation.has_tile_entities
        {
            return Err(Error::StateMismatch);
        }
        Ok(())
    }

    pub fn prepare(
        &self,
        handle: &CapabilityHandle,
        observation: &Observation,
    ) -> Result<ValidationTicket, Error> {
        let entry = self.entry(handle)?;
        self.check_observation(entry, observation)?;
        if entry.spec.state == CapabilityState::Java {
            return Err(Error::JavaFallback);
        }
        if entry.spec.state < CapabilityState::ShadowValidated {
            return Err(Error::NotShadowValidated);
        }
        if entry.spec.ownership_mode == OwnershipMode::NativeExclusiveModel
            && *self.owner(&entry.spec.resource)? != MutableOwner::NativeModel(handle.clone())
        {
            return Err(Error::OwnershipRequirement);
        }
        let mut owners = BTreeMap::new();
        for member in self.closure(handle)? {
            let resource = &self.entry(&member)?.spec.resource;
            let record = self.resource(resource)?;
            owners.insert(
                resource.clone(),
                (record.owner_generation, record.owner.clone()),
            );
        }
        Ok(ValidationTicket {
            handle: handle.clone(),
            observation: observation.clone(),
            owners,
        })
    }

    /// The caller may publish only a modeled result after this recheck. This
    /// carries no data and is never a production publication or authority token.
    pub fn commit(
        &mut self,
        ticket: ValidationTicket,
        current: &Observation,
    ) -> Result<ModelCommit, Error> {
        self.prepare(&ticket.handle, current)?;
        if ticket.observation != *current {
            return Err(Error::StateMismatch);
        }
        for (resource, (epoch, owner)) in ticket.owners {
            let record = self.resource(&resource)?;
            if record.owner_generation != epoch || record.owner != owner {
                return Err(Error::OwnerGenerationChanged);
            }
        }
        Ok(ModelCommit {
            capability: ticket.handle,
            operation: current.operation.clone(),
        })
    }

    /// Simulation only: an external Java quiescence adapter does not exist yet.
    pub fn model_release_java(&mut self, resource: &ResourceHandle) -> Result<(), Error> {
        if self.resource_raw(resource)?.owner != MutableOwner::Java {
            return Err(Error::OwnershipConflict);
        }
        self.set_owner(resource, MutableOwner::Unowned)?;
        let roots = self
            .entries
            .values()
            .filter(|e| e.revoked.is_none())
            .filter(|entry| {
                entry.dependencies.iter().any(|dependency| {
                    dependency.ownership == DependencyOwnership::JavaRetained
                        && self
                            .entries
                            .get(&dependency.handle.key)
                            .is_some_and(|target| {
                                target.handle == dependency.handle
                                    && target.spec.resource == *resource
                            })
                })
            })
            .map(|entry| entry.handle.clone())
            .collect();
        self.revoke_closure(roots, RevocationReason::Event);
        Ok(())
    }

    pub fn model_restore_java(&mut self, resource: &ResourceHandle) -> Result<(), Error> {
        if self.resource(resource)?.owner != MutableOwner::Unowned {
            return Err(Error::OwnershipConflict);
        }
        self.set_owner(resource, MutableOwner::Java)
    }

    pub fn model_claim_native(
        &mut self,
        handle: &CapabilityHandle,
        observation: &Observation,
    ) -> Result<ModelOwnershipLease, Error> {
        let entry = self.entry(handle)?;
        self.check_observation(entry, observation)?;
        if entry.spec.state != CapabilityState::AuthorityModel
            || entry.spec.ownership_mode != OwnershipMode::NativeExclusiveModel
        {
            return Err(Error::OwnershipRequirement);
        }
        self.closure(handle)?;
        let resource = entry.spec.resource.clone();
        if self.resource(&resource)?.owner != MutableOwner::Unowned {
            return Err(Error::OwnershipConflict);
        }
        self.set_owner(&resource, MutableOwner::NativeModel(handle.clone()))?;
        Ok(ModelOwnershipLease {
            owner_generation: self.resource(&resource)?.owner_generation,
            resource,
            holder: handle.clone(),
        })
    }

    /// Revocation quarantines ownership; only this explicit modeled quiescence
    /// acknowledgement releases it. It never implicitly resumes Java mutation.
    pub fn model_release_native_after_quiescence(
        &mut self,
        lease: ModelOwnershipLease,
    ) -> Result<(), Error> {
        let resource = self.resource_raw(&lease.resource)?;
        if resource.owner_generation != lease.owner_generation {
            return Err(Error::OwnerGenerationChanged);
        }
        match &resource.owner {
            MutableOwner::NativeModel(holder) | MutableOwner::Quarantined(holder)
                if *holder == lease.holder => {}
            _ => return Err(Error::OwnershipConflict),
        }
        self.set_owner(&lease.resource, MutableOwner::Unowned)?;
        // Successful ownership release ends this authority-model generation and
        // every dependent claim. A freshly reinstalled generation sharing its
        // key must not be revoked by the old quarantined holder's completion.
        if self
            .entries
            .get(&lease.holder.key)
            .is_some_and(|entry| entry.handle == lease.holder && entry.revoked.is_none())
        {
            self.revoke_closure(
                [lease.holder].into_iter().collect(),
                RevocationReason::Event,
            );
        }
        Ok(())
    }

    fn set_owner(&mut self, handle: &ResourceHandle, owner: MutableOwner) -> Result<(), Error> {
        let next = self
            .resource_raw(handle)?
            .owner_generation
            .checked_add(1)
            .ok_or(Error::CounterExhausted)?;
        let resource = self
            .resources
            .get_mut(&handle.id)
            .expect("validated resource");
        resource.owner_generation = next;
        resource.owner = owner;
        Ok(())
    }

    pub fn revoke(&mut self, handle: &CapabilityHandle) -> Result<Vec<CapabilityHandle>, Error> {
        self.entry(handle)?;
        Ok(self.revoke_closure(
            [handle.clone()].into_iter().collect(),
            RevocationReason::Explicit,
        ))
    }

    pub fn invalidate(&mut self, event: Invalidation) -> Result<Vec<CapabilityHandle>, Error> {
        if let Invalidation::Resource(ref resource) = event {
            self.resource(resource)?;
        }
        let roots = self
            .entries
            .values()
            .filter(|entry| entry.revoked.is_none())
            .filter(|entry| Self::event_matches(&event, &entry.spec))
            .map(|entry| entry.handle.clone())
            .collect();
        let revoked = self.revoke_closure(roots, RevocationReason::Event);
        self.invalidations.push(event.clone());
        // Retire resource identity permanently on lifecycle end. No ABA by
        // re-registering the same root/incarnation after retirement.
        for resource in self.resources.values_mut() {
            let retire = match &event {
                Invalidation::Session(id) => resource.key.context.session == *id,
                Invalidation::Incarnation(context) => resource.key.context == *context,
                Invalidation::Resource(handle) => self.resource_ids[&resource.key] == handle.id,
                _ => false,
            };
            if retire {
                resource.retired = true;
            }
        }
        Ok(revoked)
    }

    fn event_matches(event: &Invalidation, spec: &CapabilitySpec) -> bool {
        match event {
            Invalidation::Certificate(id) => spec.evidence.certificate_sha256 == *id,
            Invalidation::WriterEvidence(id) => spec.evidence.writer_evidence_sha256 == *id,
            Invalidation::LifecycleEvidence(id) => spec.evidence.lifecycle_evidence_sha256 == *id,
            Invalidation::Session(id) => spec.scope.context.session == *id,
            Invalidation::Incarnation(context) => spec.scope.context == *context,
            Invalidation::Registry { object, epoch } => {
                spec.scope.registry.object == *object && spec.scope.registry.epoch == *epoch
            }
            Invalidation::Version { adapter, epoch } => {
                spec.scope.version.adapter == *adapter && spec.scope.version.epoch == *epoch
            }
            Invalidation::Resource(resource) => spec.resource == *resource,
        }
    }

    fn revoke_closure(
        &mut self,
        mut affected: BTreeSet<CapabilityHandle>,
        root_reason: RevocationReason,
    ) -> Vec<CapabilityHandle> {
        let roots = affected.clone();
        loop {
            let old_len = affected.len();
            for entry in self
                .entries
                .values()
                .filter(|entry| entry.revoked.is_none())
            {
                if entry
                    .dependencies
                    .iter()
                    .any(|dependency| affected.contains(&dependency.handle))
                {
                    affected.insert(entry.handle.clone());
                }
            }
            if old_len == affected.len() {
                break;
            }
        }
        for handle in &affected {
            let entry = self.entries.get_mut(&handle.key).expect("known capability");
            entry.revoked = Some(if roots.contains(handle) {
                root_reason.clone()
            } else {
                RevocationReason::Dependency
            });
        }
        for resource in self.resources.values_mut() {
            if let MutableOwner::NativeModel(holder) = &resource.owner {
                if affected.contains(holder) {
                    // Keep the lease epoch so its holder can acknowledge quiescence;
                    // prepare/commit still rejects the revoked generation immediately.
                    resource.owner = MutableOwner::Quarantined(holder.clone());
                }
            }
        }
        affected.into_iter().collect()
    }
}
