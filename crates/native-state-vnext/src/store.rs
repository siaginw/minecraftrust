//! Serialized retained-state model. All handoffs are simulated; no JVM adapter.
use crate::section::{NativeChunk, SectionError};
use native_capability::{
    CapabilityHandle, CapabilitySpec, CapabilityState, Context, Error as CapabilityError,
    Invalidation, ModelOwnershipLease, ModelRegistry, MutableOwner, Observation, OpaqueToken,
    ResourceHandle, Status, ValidationTicket,
};
use rustcraft_core::{
    BiomeKey, CoreError, DenseRuntimeStateId, RegistryEpoch, RuntimeStateRef, SectionCoord,
    StateRegistry,
};
use std::collections::{BTreeMap, BTreeSet};
use std::sync::{
    atomic::{AtomicU64, Ordering},
    Arc,
};

static NEXT_STORE: AtomicU64 = AtomicU64::new(1);

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum OwnershipState {
    JavaOwned,
    Quiescing,
    Transferring,
    RustOwned,
    MaterializingJava,
    Revoked,
    Retired,
}

#[derive(Clone, Debug, PartialEq, Eq, PartialOrd, Ord)]
pub struct Handle {
    store: u64,
    context: Context,
    slot: u32,
    slot_generation: u64,
    ownership_generation: u64,
}

#[cfg(test)]
mod exhaustion_tests {
    use super::*;
    use crate::test_helpers::*;

    #[test]
    fn ownership_exhaustion_retires_and_burns_slot_before_reuse() {
        let (mut store, java) = fixture();
        let index = store.index(&java).unwrap();
        store.node_mut(index).ownership_generation = u64::MAX;
        let handle = store.handle(index);
        let binding = bind(&mut store, &handle, "native");
        assert_eq!(
            store.begin_native_handoff(&handle, binding).unwrap_err(),
            StoreError::GenerationExhausted
        );
        assert_eq!(store.phase(&handle), Ok(OwnershipState::Retired));
        store.destroy(&handle).unwrap();
        let next = store
            .insert_java(context(2), token(100), registry(), data())
            .unwrap();
        assert_ne!(next.slot(), handle.slot());
    }

    #[test]
    fn slot_generation_exhaustion_never_wraps_or_reuses_slot() {
        let (mut store, java) = fixture();
        let index = store.index(&java).unwrap();
        store.slots[index].generation = u64::MAX;
        let old = store.handle(index);
        store.destroy(&old).unwrap();
        let next = store
            .insert_java(context(2), token(100), registry(), data())
            .unwrap();
        assert_ne!(next.slot(), old.slot());
        assert_eq!(store.phase(&old), Err(StoreError::StaleHandle));
    }

    #[test]
    fn state_light_and_biome_exhaustion_retire_without_partial_mutation() {
        for target in 0..3 {
            let (mut store, java) = fixture();
            let (handle, _) = rust_owned(&mut store, &java, "native");
            let index = store.index(&handle).unwrap();
            let before = store
                .view(&handle, ViewKind::Query, artifact_context())
                .unwrap();
            match target {
                0 => store.node_mut(index).generations.states = u64::MAX,
                1 => store.node_mut(index).generations.light = u64::MAX,
                _ => store.node_mut(index).generations.biomes = u64::MAX,
            }
            let writer = store.begin_write(&handle, WriterSide::Rust).unwrap();
            let result = match target {
                0 => store.set_state(&writer, SectionCoord(-4), 0, DenseRuntimeStateId(1)),
                1 => store.set_light(&writer, vec![1]),
                _ => store.set_biomes(&writer, vec![]),
            };
            assert_eq!(result, Err(StoreError::GenerationExhausted));
            assert_eq!(store.phase(&handle), Ok(OwnershipState::Retired));
            assert_eq!(store.node(index).data.light, before.data().light());
            assert_eq!(store.node(index).data.biomes, before.data().biomes());
            assert_eq!(
                store
                    .node(index)
                    .data
                    .chunk
                    .section(SectionCoord(-4))
                    .unwrap()
                    .get(0),
                Ok(DenseRuntimeStateId(0))
            );
            store.finish_write(writer).unwrap();
            store.destroy(&handle).unwrap();
        }
    }

    #[test]
    fn work_and_writer_nonce_exhaustion_never_issue_reusable_tokens() {
        let (mut store, java) = fixture();
        store.next_nonce = u64::MAX;
        assert_eq!(
            store.begin_write(&java, WriterSide::Java).unwrap_err(),
            StoreError::GenerationExhausted
        );
        assert_eq!(store.phase(&java), Ok(OwnershipState::Retired));
        store.destroy(&java).unwrap();
        let (mut store, java) = fixture();
        let (handle, _) = rust_owned(&mut store, &java, "native");
        let view = store
            .view(&handle, ViewKind::Query, artifact_context())
            .unwrap();
        store.next_nonce = u64::MAX;
        assert_eq!(
            store.queue_work(view).unwrap_err(),
            StoreError::GenerationExhausted
        );
        assert_eq!(store.phase(&handle), Ok(OwnershipState::Retired));
    }
}
impl Handle {
    pub fn context(&self) -> &Context {
        &self.context
    }
    pub fn slot(&self) -> u32 {
        self.slot
    }
    pub fn slot_generation(&self) -> u64 {
        self.slot_generation
    }
    pub fn ownership_generation(&self) -> u64 {
        self.ownership_generation
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord)]
pub struct Generations {
    pub states: u64,
    pub light: u64,
    pub biomes: u64,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord)]
pub enum ViewKind {
    Packet,
    Lighting,
    Persistence,
    Query,
    WorldgenDependency,
}

#[derive(Clone, Debug, PartialEq, Eq, PartialOrd, Ord)]
pub struct ArtifactContext {
    pub protocol_version: OpaqueToken,
    pub dimension_rules: OpaqueToken,
    pub recipient_rules: OpaqueToken,
}

#[derive(Clone, Debug, PartialEq, Eq, PartialOrd, Ord)]
pub struct ViewTag {
    pub handle: Handle,
    pub registry_epoch: RegistryEpoch,
    pub generations: Generations,
    pub kind: ViewKind,
    pub artifact_context: ArtifactContext,
}

#[derive(Clone, Debug)]
pub struct SnapshotData {
    chunk: NativeChunk,
    light: Vec<u8>,
    biomes: Vec<BiomeKey>,
}
impl SnapshotData {
    pub fn new(chunk: NativeChunk, light: Vec<u8>, biomes: Vec<BiomeKey>) -> Self {
        Self {
            chunk,
            light,
            biomes,
        }
    }
    pub fn chunk(&self) -> &NativeChunk {
        &self.chunk
    }
    pub fn light(&self) -> &[u8] {
        &self.light
    }
    pub fn biomes(&self) -> &[BiomeKey] {
        &self.biomes
    }
}

#[derive(Clone, Debug)]
pub struct NativeView {
    tag: ViewTag,
    data: Arc<SnapshotData>,
    registry: Arc<StateRegistry>,
}
impl NativeView {
    pub fn tag(&self) -> &ViewTag {
        &self.tag
    }
    pub fn data(&self) -> &SnapshotData {
        &self.data
    }
    pub fn registry(&self) -> &StateRegistry {
        &self.registry
    }
}

/// Explicit association, not a live certificate/registry identity verifier.
#[derive(Clone, Debug)]
pub struct CapabilityBinding {
    pub capability: CapabilityHandle,
    pub observation: Observation,
    pub registry_epoch: RegistryEpoch,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum WriterSide {
    Java,
    Rust,
}

#[derive(Debug)]
pub struct WriteLease {
    handle: Handle,
    nonce: u64,
    side: WriterSide,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Direction {
    ToRust,
    ToJava,
}

/// Private, non-cloneable transition token. Only its origin store can consume it.
#[derive(Debug)]
pub struct HandoffTicket {
    handle: Handle,
    nonce: u64,
    direction: Direction,
}
impl HandoffTicket {
    pub fn handle(&self) -> &Handle {
        &self.handle
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum PublicationFault {
    None,
    BeforeOwnerSwitch,
    AfterOwnerSwitch,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum PublicationOutcome {
    Committed(Handle),
    RolledBack(Handle),
    Revoked(Handle),
}
impl PublicationOutcome {
    pub fn handle(&self) -> &Handle {
        match self {
            Self::Committed(h) | Self::RolledBack(h) | Self::Revoked(h) => h,
        }
    }
    pub const fn production_authority(&self) -> bool {
        false
    }
}

/// Origin-bound cancellation identity. Nonces never repeat within a store;
/// the pending-job table binds each one to the complete lifecycle/view tag.
/// External callers cannot construct a token from a bare numeric slot.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct WorkId {
    store: u64,
    nonce: u64,
}

#[derive(Debug)]
pub struct WorkTicket {
    id: WorkId,
    view: NativeView,
    capability_ticket: ValidationTicket,
}
impl WorkTicket {
    pub fn id(&self) -> WorkId {
        self.id
    }
    pub fn view(&self) -> &NativeView {
        &self.view
    }
    pub fn complete(self, bytes: Vec<u8>) -> WorkerResult {
        WorkerResult {
            ticket: self,
            bytes,
        }
    }
}
#[derive(Debug)]
pub struct WorkerResult {
    ticket: WorkTicket,
    bytes: Vec<u8>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum StoreError {
    Capability(CapabilityError),
    Registry(CoreError),
    Section(SectionError),
    ForeignStore,
    StaleHandle,
    WrongState,
    Busy,
    InvalidTicket,
    NoCapability,
    DuplicateResource,
    MissingSection,
    GenerationExhausted,
    CancelledWork,
    StaleView,
    RegistryBindingMismatch,
    InvalidatedRegistryEpoch,
}
impl From<CapabilityError> for StoreError {
    fn from(value: CapabilityError) -> Self {
        Self::Capability(value)
    }
}
impl From<CoreError> for StoreError {
    fn from(value: CoreError) -> Self {
        Self::Registry(value)
    }
}
impl From<SectionError> for StoreError {
    fn from(value: SectionError) -> Self {
        Self::Section(value)
    }
}

#[derive(Debug)]
struct Pending {
    nonce: u64,
    direction: Direction,
    candidate: Option<Arc<SnapshotData>>,
    binding: Option<CapabilityBinding>,
}
#[derive(Debug)]
struct Writer {
    nonce: u64,
    side: WriterSide,
}
struct Node {
    context: Context,
    resource: ResourceHandle,
    phase: OwnershipState,
    ownership_generation: u64,
    generations: Generations,
    registry: Arc<StateRegistry>,
    data: Arc<SnapshotData>,
    active_writer: Option<Writer>,
    pending: Option<Pending>,
    binding: Option<CapabilityBinding>,
    native_lease: Option<ModelOwnershipLease>,
    caches: BTreeMap<ViewTag, Arc<[u8]>>,
}
struct Slot {
    generation: u64,
    burned: bool,
    node: Option<Node>,
}

/// Owns the H4 model registry: its owner ledger is the sole mutable-owner model.
/// All operations are serialized with &mut self, not real Java synchronization.
pub struct RetainedStore {
    id: u64,
    capabilities: ModelRegistry,
    slots: Vec<Slot>,
    resources: BTreeMap<ResourceHandle, u32>,
    next_nonce: u64,
    jobs: BTreeMap<u64, ViewTag>,
    registry_tables: BTreeMap<RegistryEpoch, Arc<StateRegistry>>,
    invalidated_registry_epochs: BTreeSet<RegistryEpoch>,
}

impl RetainedStore {
    pub fn new() -> Result<Self, StoreError> {
        let id = NEXT_STORE
            .fetch_update(Ordering::Relaxed, Ordering::Relaxed, |old| {
                old.checked_add(1)
            })
            .map_err(|_| StoreError::GenerationExhausted)?;
        Ok(Self {
            id,
            capabilities: ModelRegistry::new()?,
            slots: Vec::new(),
            resources: BTreeMap::new(),
            next_nonce: 1,
            jobs: BTreeMap::new(),
            registry_tables: BTreeMap::new(),
            invalidated_registry_epochs: BTreeSet::new(),
        })
    }

    pub fn insert_java(
        &mut self,
        context: Context,
        root_allocation: OpaqueToken,
        registry: Arc<StateRegistry>,
        data: SnapshotData,
    ) -> Result<Handle, StoreError> {
        if self.invalidated_registry_epochs.contains(&registry.epoch()) {
            return Err(StoreError::InvalidatedRegistryEpoch);
        }
        if self
            .registry_tables
            .get(&registry.epoch())
            .is_some_and(|bound| bound.states() != registry.states())
        {
            return Err(StoreError::RegistryBindingMismatch);
        }
        for (_, section) in data.chunk.sections() {
            for id in section.dense() {
                registry.resolve(RuntimeStateRef {
                    epoch: registry.epoch(),
                    id,
                })?;
            }
        }
        let slot_index = self
            .slots
            .iter()
            .position(|slot| slot.node.is_none() && !slot.burned)
            .unwrap_or(self.slots.len());
        let slot_id = u32::try_from(slot_index).map_err(|_| StoreError::GenerationExhausted)?;
        let resource = self
            .capabilities
            .register_resource(context.clone(), root_allocation)?;
        if self.resources.contains_key(&resource) {
            return Err(StoreError::DuplicateResource);
        }
        if slot_index == self.slots.len() {
            self.slots.push(Slot {
                generation: 1,
                burned: false,
                node: None,
            });
        }
        self.resources.insert(resource.clone(), slot_id);
        self.registry_tables
            .entry(registry.epoch())
            .or_insert_with(|| registry.clone());
        self.slots[slot_index].node = Some(Node {
            context,
            resource,
            phase: OwnershipState::JavaOwned,
            ownership_generation: 1,
            generations: Generations {
                states: 1,
                light: 1,
                biomes: 1,
            },
            registry,
            data: Arc::new(data),
            active_writer: None,
            pending: None,
            binding: None,
            native_lease: None,
            caches: BTreeMap::new(),
        });
        Ok(self.handle(slot_index))
    }

    fn handle(&self, index: usize) -> Handle {
        let slot = &self.slots[index];
        let node = slot.node.as_ref().expect("occupied slot");
        Handle {
            store: self.id,
            context: node.context.clone(),
            slot: index as u32,
            slot_generation: slot.generation,
            ownership_generation: node.ownership_generation,
        }
    }
    fn life_index(&self, handle: &Handle) -> Result<usize, StoreError> {
        if handle.store != self.id {
            return Err(StoreError::ForeignStore);
        }
        let index = handle.slot as usize;
        let slot = self.slots.get(index).ok_or(StoreError::StaleHandle)?;
        let node = slot.node.as_ref().ok_or(StoreError::StaleHandle)?;
        if slot.generation != handle.slot_generation || node.context != handle.context {
            return Err(StoreError::StaleHandle);
        }
        Ok(index)
    }
    fn index(&self, handle: &Handle) -> Result<usize, StoreError> {
        let index = self.life_index(handle)?;
        if self.node(index).ownership_generation != handle.ownership_generation {
            return Err(StoreError::StaleHandle);
        }
        Ok(index)
    }
    fn node(&self, index: usize) -> &Node {
        self.slots[index].node.as_ref().expect("occupied slot")
    }
    fn node_mut(&mut self, index: usize) -> &mut Node {
        self.slots[index].node.as_mut().expect("occupied slot")
    }
    fn nonce(&mut self, index: usize) -> Result<u64, StoreError> {
        match self.next_nonce.checked_add(1) {
            Some(next) => {
                let old = self.next_nonce;
                self.next_nonce = next;
                Ok(old)
            }
            None => {
                self.exhaust(index);
                Err(StoreError::GenerationExhausted)
            }
        }
    }
    fn next_ownership(&mut self, index: usize) -> Result<(), StoreError> {
        match self.node(index).ownership_generation.checked_add(1) {
            Some(next) => {
                self.node_mut(index).ownership_generation = next;
                self.node_mut(index).caches.clear();
                Ok(())
            }
            None => {
                self.exhaust(index);
                Err(StoreError::GenerationExhausted)
            }
        }
    }
    fn exhaust(&mut self, index: usize) {
        let resource = self.node(index).resource.clone();
        let _ = self
            .capabilities
            .invalidate(Invalidation::Resource(resource));
        self.slots[index].burned = true;
        let node = self.node_mut(index);
        node.phase = OwnershipState::Retired;
        node.pending = None;
        node.caches.clear();
    }

    pub fn resource(&self, handle: &Handle) -> Result<ResourceHandle, StoreError> {
        Ok(self.node(self.index(handle)?).resource.clone())
    }
    pub fn phase(&self, handle: &Handle) -> Result<OwnershipState, StoreError> {
        Ok(self.node(self.index(handle)?).phase)
    }
    pub fn modeled_owner(&self, handle: &Handle) -> Result<&MutableOwner, StoreError> {
        Ok(self
            .capabilities
            .owner(&self.node(self.index(handle)?).resource)?)
    }
    pub fn install_capabilities(
        &mut self,
        specs: Vec<CapabilitySpec>,
    ) -> Result<Vec<CapabilityHandle>, StoreError> {
        Ok(self.capabilities.install_batch(specs)?)
    }
    pub fn revoke_capability(&mut self, handle: &CapabilityHandle) -> Result<(), StoreError> {
        self.capabilities.revoke(handle)?;
        self.synchronize_revocations();
        Ok(())
    }
    pub fn invalidate_capabilities(&mut self, event: Invalidation) -> Result<(), StoreError> {
        self.capabilities.invalidate(event)?;
        self.synchronize_revocations();
        Ok(())
    }
    fn synchronize_revocations(&mut self) {
        for slot in &mut self.slots {
            if let Some(node) = &mut slot.node {
                let binding = node
                    .binding
                    .as_ref()
                    .or_else(|| node.pending.as_ref().and_then(|p| p.binding.as_ref()));
                let invalid = binding.is_some_and(|b| {
                    self.capabilities.status(&b.capability)
                        != Ok(Status::Active(CapabilityState::AuthorityModel))
                }) || self.capabilities.owner(&node.resource).is_err();
                if invalid && node.phase != OwnershipState::Retired {
                    node.phase = OwnershipState::Revoked;
                    node.pending = None;
                    node.caches.clear();
                }
            }
        }
    }

    pub fn begin_write(
        &mut self,
        handle: &Handle,
        side: WriterSide,
    ) -> Result<WriteLease, StoreError> {
        let index = self.index(handle)?;
        let node = self.node(index);
        let expected = if side == WriterSide::Java {
            OwnershipState::JavaOwned
        } else {
            OwnershipState::RustOwned
        };
        if node.phase != expected {
            return Err(StoreError::WrongState);
        }
        if node.active_writer.is_some() {
            return Err(StoreError::Busy);
        }
        self.check_owner(index, side)?;
        let nonce = self.nonce(index)?;
        self.node_mut(index).active_writer = Some(Writer { nonce, side });
        Ok(WriteLease {
            handle: handle.clone(),
            nonce,
            side,
        })
    }
    fn check_owner(&self, index: usize, side: WriterSide) -> Result<(), StoreError> {
        let node = self.node(index);
        match (side, self.capabilities.owner(&node.resource)?) {
            (WriterSide::Java, MutableOwner::Java) => Ok(()),
            (WriterSide::Rust, MutableOwner::NativeModel(holder))
                if node
                    .binding
                    .as_ref()
                    .is_some_and(|b| b.capability == *holder) =>
            {
                let binding = node.binding.as_ref().expect("checked binding");
                self.capabilities
                    .prepare(&binding.capability, &binding.observation)?;
                Ok(())
            }
            _ => Err(StoreError::NoCapability),
        }
    }
    fn writer_index(&self, lease: &WriteLease) -> Result<usize, StoreError> {
        let index = self.life_index(&lease.handle)?;
        let writer = self
            .node(index)
            .active_writer
            .as_ref()
            .ok_or(StoreError::InvalidTicket)?;
        if writer.nonce != lease.nonce || writer.side != lease.side {
            return Err(StoreError::InvalidTicket);
        }
        let phase = self.node(index).phase;
        let allowed = match lease.side {
            WriterSide::Java => {
                matches!(phase, OwnershipState::JavaOwned | OwnershipState::Quiescing)
            }
            WriterSide::Rust => matches!(
                phase,
                OwnershipState::RustOwned | OwnershipState::MaterializingJava
            ),
        };
        if !allowed {
            return Err(StoreError::WrongState);
        }
        self.check_owner(index, lease.side)?;
        Ok(index)
    }
    pub fn finish_write(&mut self, lease: WriteLease) -> Result<(), StoreError> {
        let index = self.life_index(&lease.handle)?;
        let writer = self
            .node(index)
            .active_writer
            .as_ref()
            .ok_or(StoreError::InvalidTicket)?;
        if writer.nonce != lease.nonce || writer.side != lease.side {
            return Err(StoreError::InvalidTicket);
        }
        self.node_mut(index).active_writer = None;
        Ok(())
    }
    pub fn set_state(
        &mut self,
        lease: &WriteLease,
        coordinate: SectionCoord,
        cell: usize,
        state: DenseRuntimeStateId,
    ) -> Result<bool, StoreError> {
        let index = self.writer_index(lease)?;
        let node = self.node(index);
        node.registry.resolve(RuntimeStateRef {
            epoch: node.registry.epoch(),
            id: state,
        })?;
        let section = node
            .data
            .chunk
            .section(coordinate)
            .ok_or(StoreError::MissingSection)?;
        if section.get(cell)? == state {
            return Ok(false);
        }
        let Some(next) = node.generations.states.checked_add(1) else {
            self.exhaust(index);
            return Err(StoreError::GenerationExhausted);
        };
        let node = self.node_mut(index);
        Arc::make_mut(&mut node.data)
            .chunk
            .section_mut(coordinate)
            .expect("checked section")
            .set(cell, state)?;
        node.generations.states = next;
        node.caches.clear();
        Ok(true)
    }
    pub fn set_light(&mut self, lease: &WriteLease, light: Vec<u8>) -> Result<bool, StoreError> {
        let index = self.writer_index(lease)?;
        if self.node(index).data.light == light {
            return Ok(false);
        }
        let Some(next) = self.node(index).generations.light.checked_add(1) else {
            self.exhaust(index);
            return Err(StoreError::GenerationExhausted);
        };
        let node = self.node_mut(index);
        Arc::make_mut(&mut node.data).light = light;
        node.generations.light = next;
        node.caches.clear();
        Ok(true)
    }
    pub fn set_biomes(
        &mut self,
        lease: &WriteLease,
        biomes: Vec<BiomeKey>,
    ) -> Result<bool, StoreError> {
        let index = self.writer_index(lease)?;
        if self.node(index).data.biomes == biomes {
            return Ok(false);
        }
        let Some(next) = self.node(index).generations.biomes.checked_add(1) else {
            self.exhaust(index);
            return Err(StoreError::GenerationExhausted);
        };
        let node = self.node_mut(index);
        Arc::make_mut(&mut node.data).biomes = biomes;
        node.generations.biomes = next;
        node.caches.clear();
        Ok(true)
    }

    pub fn begin_native_handoff(
        &mut self,
        handle: &Handle,
        binding: CapabilityBinding,
    ) -> Result<HandoffTicket, StoreError> {
        let index = self.index(handle)?;
        if self.node(index).phase != OwnershipState::JavaOwned {
            return Err(StoreError::WrongState);
        }
        self.check_owner(index, WriterSide::Java)?;
        if self.node(index).registry.epoch() != binding.registry_epoch {
            return Err(StoreError::RegistryBindingMismatch);
        }
        let (identity, _) = self.capabilities.identity(&binding.capability)?;
        if identity.resource != self.node(index).resource
            || identity.state != CapabilityState::AuthorityModel
        {
            return Err(StoreError::NoCapability);
        }
        let nonce = self.nonce(index)?;
        self.next_ownership(index)?;
        let node = self.node_mut(index);
        node.phase = OwnershipState::Quiescing;
        node.pending = Some(Pending {
            nonce,
            direction: Direction::ToRust,
            candidate: None,
            binding: Some(binding),
        });
        Ok(HandoffTicket {
            handle: self.handle(index),
            nonce,
            direction: Direction::ToRust,
        })
    }
    pub fn begin_java_materialization(
        &mut self,
        handle: &Handle,
    ) -> Result<HandoffTicket, StoreError> {
        let index = self.index(handle)?;
        if self.node(index).phase != OwnershipState::RustOwned {
            return Err(StoreError::WrongState);
        }
        self.check_owner(index, WriterSide::Rust)?;
        let nonce = self.nonce(index)?;
        self.next_ownership(index)?;
        let node = self.node_mut(index);
        node.phase = OwnershipState::MaterializingJava;
        node.pending = Some(Pending {
            nonce,
            direction: Direction::ToJava,
            candidate: None,
            binding: None,
        });
        Ok(HandoffTicket {
            handle: self.handle(index),
            nonce,
            direction: Direction::ToJava,
        })
    }
    fn ticket_index(&self, ticket: &HandoffTicket) -> Result<usize, StoreError> {
        let index = self.index(&ticket.handle)?;
        let pending = self
            .node(index)
            .pending
            .as_ref()
            .ok_or(StoreError::InvalidTicket)?;
        if pending.nonce != ticket.nonce || pending.direction != ticket.direction {
            return Err(StoreError::InvalidTicket);
        }
        Ok(index)
    }
    /// Models a safe-point acknowledgement; no real Java safepoint is implemented.
    pub fn stage_after_quiescence(&mut self, ticket: &HandoffTicket) -> Result<(), StoreError> {
        let index = self.ticket_index(ticket)?;
        let node = self.node(index);
        if node.active_writer.is_some() {
            return Err(StoreError::Busy);
        }
        let expected = if ticket.direction == Direction::ToRust {
            OwnershipState::Quiescing
        } else {
            OwnershipState::MaterializingJava
        };
        if node.phase != expected || node.pending.as_ref().expect("ticket").candidate.is_some() {
            return Err(StoreError::WrongState);
        }
        let candidate = Arc::new((*node.data).clone());
        let node = self.node_mut(index);
        node.pending.as_mut().expect("ticket").candidate = Some(candidate);
        if ticket.direction == Direction::ToRust {
            node.phase = OwnershipState::Transferring;
        }
        Ok(())
    }
    pub fn cancel_handoff(&mut self, ticket: HandoffTicket) -> Result<Handle, StoreError> {
        let index = self.ticket_index(&ticket)?;
        let node = self.node_mut(index);
        node.pending = None;
        node.phase = if ticket.direction == Direction::ToRust {
            OwnershipState::JavaOwned
        } else {
            OwnershipState::RustOwned
        };
        Ok(self.handle(index))
    }
    pub fn publish_handoff(
        &mut self,
        ticket: HandoffTicket,
        fault: PublicationFault,
    ) -> Result<PublicationOutcome, StoreError> {
        let result = self.try_publish_handoff(&ticket, fault);
        if result.is_err() {
            if let Ok(index) = self.ticket_index(&ticket) {
                let owner = self.capabilities.owner(&self.node(index).resource);
                let phase = match (ticket.direction, owner) {
                    (Direction::ToRust, Ok(MutableOwner::Java)) => OwnershipState::JavaOwned,
                    (Direction::ToJava, Ok(MutableOwner::NativeModel(_))) => {
                        OwnershipState::RustOwned
                    }
                    _ => OwnershipState::Revoked,
                };
                self.node_mut(index).pending = None;
                self.node_mut(index).phase = phase;
            }
        }
        self.synchronize_revocations();
        result
    }
    fn try_publish_handoff(
        &mut self,
        ticket: &HandoffTicket,
        fault: PublicationFault,
    ) -> Result<PublicationOutcome, StoreError> {
        let index = self.ticket_index(ticket)?;
        let node = self.node(index);
        if node.active_writer.is_some() {
            return Err(StoreError::Busy);
        }
        if node.pending.as_ref().expect("ticket").candidate.is_none() {
            return Err(StoreError::WrongState);
        }
        if fault == PublicationFault::BeforeOwnerSwitch {
            self.node_mut(index).pending = None;
            self.node_mut(index).phase = if ticket.direction == Direction::ToRust {
                OwnershipState::JavaOwned
            } else {
                OwnershipState::RustOwned
            };
            return Ok(PublicationOutcome::RolledBack(self.handle(index)));
        }
        let resource = node.resource.clone();
        if ticket.direction == Direction::ToRust {
            let binding = node
                .pending
                .as_ref()
                .expect("ticket")
                .binding
                .clone()
                .expect("Rust binding");
            self.capabilities.model_release_java(&resource)?;
            let lease = match self
                .capabilities
                .model_claim_native(&binding.capability, &binding.observation)
            {
                Ok(lease) => lease,
                Err(_) => {
                    let restored = self.capabilities.model_restore_java(&resource).is_ok();
                    self.node_mut(index).pending = None;
                    self.node_mut(index).phase = if restored {
                        OwnershipState::JavaOwned
                    } else {
                        OwnershipState::Revoked
                    };
                    return Ok(if restored {
                        PublicationOutcome::RolledBack(self.handle(index))
                    } else {
                        PublicationOutcome::Revoked(self.handle(index))
                    });
                }
            };
            if fault == PublicationFault::AfterOwnerSwitch {
                self.capabilities
                    .model_release_native_after_quiescence(lease)?;
                self.capabilities.model_restore_java(&resource)?;
                self.node_mut(index).pending = None;
                self.node_mut(index).phase = OwnershipState::JavaOwned;
                return Ok(PublicationOutcome::RolledBack(self.handle(index)));
            }
            let node = self.node_mut(index);
            node.data = node
                .pending
                .take()
                .expect("ticket")
                .candidate
                .expect("staged");
            node.binding = Some(binding);
            node.native_lease = Some(lease);
            node.phase = OwnershipState::RustOwned;
        } else {
            let lease = self
                .node_mut(index)
                .native_lease
                .take()
                .ok_or(StoreError::NoCapability)?;
            if self
                .capabilities
                .model_release_native_after_quiescence(lease)
                .is_err()
                || self.capabilities.model_restore_java(&resource).is_err()
            {
                self.node_mut(index).phase = OwnershipState::Revoked;
                self.node_mut(index).pending = None;
                return Ok(PublicationOutcome::Revoked(self.handle(index)));
            }
            if fault == PublicationFault::AfterOwnerSwitch {
                // The old authority generation has ended; recreating it would be
                // fabricated requalification. Fail closed with neither side active.
                self.capabilities.model_release_java(&resource)?;
                self.node_mut(index).phase = OwnershipState::Revoked;
                self.node_mut(index).pending = None;
                return Ok(PublicationOutcome::Revoked(self.handle(index)));
            }
            let node = self.node_mut(index);
            node.data = node
                .pending
                .take()
                .expect("ticket")
                .candidate
                .expect("staged");
            node.binding = None;
            node.phase = OwnershipState::JavaOwned;
        }
        Ok(PublicationOutcome::Committed(self.handle(index)))
    }

    pub fn view(
        &self,
        handle: &Handle,
        kind: ViewKind,
        artifact_context: ArtifactContext,
    ) -> Result<NativeView, StoreError> {
        let index = self.index(handle)?;
        let node = self.node(index);
        if node.phase != OwnershipState::RustOwned {
            return Err(StoreError::WrongState);
        }
        if node.active_writer.is_some() {
            return Err(StoreError::Busy);
        }
        self.check_owner(index, WriterSide::Rust)?;
        Ok(NativeView {
            tag: ViewTag {
                handle: handle.clone(),
                registry_epoch: node.registry.epoch(),
                generations: node.generations,
                kind,
                artifact_context,
            },
            data: node.data.clone(),
            registry: node.registry.clone(),
        })
    }
    /// Read-only inspection of the simulated Java representation, with the same
    /// lifecycle tags. It cannot enqueue Rust-owned worker publication.
    pub fn java_view(
        &self,
        handle: &Handle,
        artifact_context: ArtifactContext,
    ) -> Result<NativeView, StoreError> {
        let index = self.index(handle)?;
        let node = self.node(index);
        if node.phase != OwnershipState::JavaOwned {
            return Err(StoreError::WrongState);
        }
        if node.active_writer.is_some() {
            return Err(StoreError::Busy);
        }
        self.check_owner(index, WriterSide::Java)?;
        Ok(NativeView {
            tag: ViewTag {
                handle: handle.clone(),
                registry_epoch: node.registry.epoch(),
                generations: node.generations,
                kind: ViewKind::Query,
                artifact_context,
            },
            data: node.data.clone(),
            registry: node.registry.clone(),
        })
    }
    fn validate_tag(&self, tag: &ViewTag) -> Result<usize, StoreError> {
        let index = self.index(&tag.handle)?;
        let node = self.node(index);
        if node.phase != OwnershipState::RustOwned || node.active_writer.is_some() {
            return Err(StoreError::WrongState);
        }
        if node.registry.epoch() != tag.registry_epoch || node.generations != tag.generations {
            return Err(StoreError::StaleView);
        }
        self.check_owner(index, WriterSide::Rust)?;
        Ok(index)
    }
    pub fn queue_work(&mut self, view: NativeView) -> Result<WorkTicket, StoreError> {
        let index = self.validate_tag(&view.tag)?;
        let binding = self
            .node(index)
            .binding
            .as_ref()
            .ok_or(StoreError::NoCapability)?;
        let capability_ticket = self
            .capabilities
            .prepare(&binding.capability, &binding.observation)?;
        let id = self.nonce(index)?;
        self.jobs.insert(id, view.tag.clone());
        Ok(WorkTicket {
            id: WorkId {
                store: self.id,
                nonce: id,
            },
            view,
            capability_ticket,
        })
    }
    pub fn cancel_work(&mut self, id: WorkId) -> bool {
        id.store == self.id && self.jobs.remove(&id.nonce).is_some()
    }
    pub fn publish_work(&mut self, result: WorkerResult) -> Result<Arc<[u8]>, StoreError> {
        let WorkerResult { ticket, bytes } = result;
        if ticket.id.store != self.id || ticket.view.tag.handle.store != self.id {
            return Err(StoreError::ForeignStore);
        }
        let tag = self
            .jobs
            .get(&ticket.id.nonce)
            .ok_or(StoreError::CancelledWork)?;
        if *tag != ticket.view.tag {
            return Err(StoreError::InvalidTicket);
        }
        let tag = self
            .jobs
            .remove(&ticket.id.nonce)
            .expect("validated pending job");
        let index = self.validate_tag(&tag)?;
        let observation = self
            .node(index)
            .binding
            .as_ref()
            .ok_or(StoreError::NoCapability)?
            .observation
            .clone();
        self.capabilities
            .commit(ticket.capability_ticket, &observation)?;
        let bytes: Arc<[u8]> = bytes.into();
        self.node_mut(index).caches.insert(tag, bytes.clone());
        Ok(bytes)
    }
    pub fn cached(&self, view: &NativeView) -> Result<Option<Arc<[u8]>>, StoreError> {
        let index = self.validate_tag(&view.tag)?;
        Ok(self.node(index).caches.get(&view.tag).cloned())
    }
    pub fn invalidate_registry(&mut self, epoch: RegistryEpoch) {
        self.invalidated_registry_epochs.insert(epoch);
        let resources: Vec<_> = self
            .slots
            .iter()
            .filter_map(|slot| slot.node.as_ref())
            .filter(|node| node.registry.epoch() == epoch)
            .map(|node| node.resource.clone())
            .collect();
        for resource in resources {
            let _ = self
                .capabilities
                .invalidate(Invalidation::Resource(resource));
        }
        self.synchronize_revocations();
    }
    /// Destruction requires drained writers. Immutable Arc views may outlive it;
    /// their old generation cannot publish a result or acquire a new writer.
    pub fn destroy(&mut self, handle: &Handle) -> Result<(), StoreError> {
        let index = self.index(handle)?;
        if self.node(index).active_writer.is_some() {
            return Err(StoreError::Busy);
        }
        if self.node(index).pending.is_some() {
            return Err(StoreError::WrongState);
        }
        let resource = self.node(index).resource.clone();
        let _ = self
            .capabilities
            .invalidate(Invalidation::Resource(resource.clone()));
        if let Some(lease) = self.node_mut(index).native_lease.take() {
            self.capabilities
                .model_release_native_after_quiescence(lease)?;
        } else {
            // This is legal only if Java still owns it. Unowned follows a failed
            // materialization; a retired resource's owner query itself is closed.
            let _ = self.capabilities.model_release_java(&resource);
        }
        self.jobs.retain(|_, tag| {
            tag.handle.store != self.id
                || tag.handle.slot != handle.slot
                || tag.handle.slot_generation != handle.slot_generation
        });
        self.resources.remove(&resource);
        self.slots[index].node = None;
        match self.slots[index].generation.checked_add(1) {
            Some(next) => self.slots[index].generation = next,
            None => self.slots[index].burned = true,
        }
        Ok(())
    }
}
