//! Offline semantic scheduler foundation. No live qualification or authority.
//! Only the closed, bounded integer Transform operation can execute on workers.
#![forbid(unsafe_code)]

use std::collections::VecDeque;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::{
    atomic::{AtomicBool, AtomicU64, Ordering},
    Arc,
};
use std::time::{Duration, Instant};

pub const fn production_authority_enabled() -> bool {
    false
}
pub const MAX_CELLS: usize = 256;
pub const MAX_OUTSTANDING: usize = 64;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct Scope {
    pub session: u64,
    pub world: u64,
    pub incarnation: u64,
    pub registry_epoch: u64,
}
impl Scope {
    fn valid(self) -> bool {
        self.session != 0 && self.world != 0 && self.incarnation != 0 && self.registry_epoch != 0
    }
}
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct Transform {
    pub start: usize,
    pub count: usize,
    pub multiply: i64,
    pub add: i64,
}
#[derive(Clone, Copy, Debug)]
pub struct Budget {
    pub max_steps: u64,
    pub max_payload_bytes: usize,
    pub deadline_ticks: u64,
    pub wall_timeout: Duration,
}
impl Budget {
    pub fn bounded_default() -> Self {
        Self {
            max_steps: 512,
            max_payload_bytes: 32768,
            deadline_ticks: 1000,
            wall_timeout: Duration::from_secs(10),
        }
    }
}
#[derive(Clone, Copy, Debug)]
pub struct Limits {
    pub max_outstanding: usize,
    pub max_dispatched: usize,
    pub max_reserved_bytes: usize,
}
impl Default for Limits {
    fn default() -> Self {
        Self {
            max_outstanding: 32,
            max_dispatched: 4,
            max_reserved_bytes: 1 << 20,
        }
    }
}
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum Error {
    InvalidConfiguration,
    InvalidQualification,
    Unloaded,
    InvalidRange,
    InvalidBudget,
    Capacity,
    MemoryBudget,
    SequenceExhausted,
    CounterExhausted,
    UnknownSequence,
    AlreadyDispatched,
    DispatchCapacity,
    Terminal,
    WrongScheduler,
    DuplicateResult,
    ClockWentBackwards,
}
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum Reason {
    Cancelled,
    Deadline,
    Revoked,
    Unloaded,
    Abandoned,
    CpuBudget,
    Arithmetic,
    WorkerPanic,
    InvalidResult,
    StaleInput,
    CellVersionExhausted,
}
#[derive(Clone, Debug, Eq, PartialEq)]
pub enum Outcome {
    Committed,
    Skipped(Reason),
}
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct Effect {
    pub index: usize,
    pub before: i64,
    pub after: i64,
}
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct Decision {
    pub sequence: u64,
    pub outcome: Outcome,
    pub tick: u64,
    pub effects: Vec<Effect>,
}
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct Usage {
    pub outstanding: usize,
    pub dispatched: usize,
    pub retired: usize,
    pub reserved_bytes: usize,
}

#[derive(Debug)]
struct Owner;

/// Opaque control identity. Sequence numbers are inspection/trace data only.
/// Clones identify the same task; they do not duplicate its worker lease.
/// ```compile_fail
/// use semantic_scheduler_prototype::Scheduler;
/// fn numeric_control(s: &mut Scheduler) { let _ = s.cancel(0); }
/// ```
/// ```compile_fail
/// use semantic_scheduler_prototype::TaskId;
/// fn forge() -> TaskId { TaskId { sequence: 0 } }
/// ```
#[derive(Clone, Debug)]
pub struct TaskId {
    owner: Arc<Owner>,
    sequence: u64,
}
impl TaskId {
    pub fn sequence(&self) -> u64 {
        self.sequence
    }
}
impl PartialEq for TaskId {
    fn eq(&self, other: &Self) -> bool {
        Arc::ptr_eq(&self.owner, &other.owner) && self.sequence == other.sequence
    }
}
impl Eq for TaskId {}
/// A private model token, never a parsed or live runtime qualification certificate.
#[derive(Clone, Debug)]
pub struct OfflineQualification {
    owner: Arc<Owner>,
    scope: Scope,
    generation: u64,
}
#[derive(Clone, Copy, Debug)]
struct Cell {
    value: i64,
    version: u64,
}
#[derive(Clone, Copy, Debug)]
struct Read {
    index: usize,
    value: i64,
    version: u64,
}
#[derive(Clone, Copy, Debug)]
struct Write {
    index: usize,
    value: i64,
}
struct Task {
    owner: Arc<Owner>,
    scope: Scope,
    generation: u64,
    sequence: u64,
    transform: Transform,
    reads: Box<[Read]>,
    budget: Budget,
    deadline: u64,
    admitted: Instant,
    clock: Arc<AtomicU64>,
    cancelled: AtomicBool,
}
impl Task {
    fn expired(&self) -> bool {
        self.clock.load(Ordering::Acquire) >= self.deadline
            || self.admitted.elapsed() >= self.budget.wall_timeout
    }
}
struct Lease {
    quiescent: Arc<AtomicBool>,
}
impl Drop for Lease {
    fn drop(&mut self) {
        self.quiescent.store(true, Ordering::Release)
    }
}
/// Move-only. A lost/dropped ticket is observable as Abandoned; forgetting it
/// conservatively retains the reservation forever rather than admitting more.
/// ```compile_fail
/// use semantic_scheduler_prototype::WorkerTicket;
/// fn duplicate(ticket: WorkerTicket) { let _ = ticket.clone(); }
/// ```
pub struct WorkerTicket {
    task: Arc<Task>,
    lease: Lease,
}
/// Results have no public constructor or mutable payload access.
/// ```compile_fail
/// use semantic_scheduler_prototype::Completion;
/// fn forge() -> Completion { Completion { steps: 0 } }
/// ```
pub struct Completion {
    task: Arc<Task>,
    result: Result<Vec<Write>, Reason>,
    steps: u64,
    lease: Lease,
}
impl Completion {
    pub fn sequence(&self) -> u64 {
        self.task.sequence
    }
}
enum Status {
    Queued,
    Issued,
    Ready {
        result: Result<Vec<Write>, Reason>,
        steps: u64,
    },
    Terminal(Reason),
}
struct Entry {
    task: Arc<Task>,
    quiescent: Arc<AtomicBool>,
    status: Status,
    reservation: usize,
}

impl WorkerTicket {
    pub fn sequence(&self) -> u64 {
        self.task.sequence
    }
    pub fn run(self) -> Completion {
        self.run_inner(false)
    }
    fn run_inner(self, panic_injection: bool) -> Completion {
        let mut steps = 0;
        let result = catch_unwind(AssertUnwindSafe(|| {
            let task = &self.task;
            if panic_injection {
                panic!("controlled pure worker panic")
            }
            let mut writes = Vec::with_capacity(task.reads.len());
            for read in &task.reads {
                if task.cancelled.load(Ordering::Acquire) {
                    return Err(Reason::Cancelled);
                }
                if task.expired() {
                    return Err(Reason::Deadline);
                }
                if steps >= task.budget.max_steps {
                    return Err(Reason::CpuBudget);
                }
                steps += 1;
                let value = read
                    .value
                    .checked_mul(task.transform.multiply)
                    .ok_or(Reason::Arithmetic)?;
                if steps >= task.budget.max_steps {
                    return Err(Reason::CpuBudget);
                }
                steps += 1;
                let value = value
                    .checked_add(task.transform.add)
                    .ok_or(Reason::Arithmetic)?;
                writes.push(Write {
                    index: read.index,
                    value,
                });
            }
            if task.expired() {
                return Err(Reason::Deadline);
            }
            Ok(writes)
        }))
        .unwrap_or(Err(Reason::WorkerPanic));
        Completion {
            task: self.task,
            result,
            steps,
            lease: self.lease,
        }
    }
}

pub struct Scheduler {
    owner: Arc<Owner>,
    scope: Scope,
    generation: u64,
    loaded: bool,
    exhausted: bool,
    cells: Vec<Cell>,
    limits: Limits,
    next_sequence: Option<u64>,
    clock: Arc<AtomicU64>,
    pending: VecDeque<Entry>,
    retired: Vec<Entry>,
    reserved: usize,
}
impl Scheduler {
    pub fn new(scope: Scope, values: Vec<i64>, limits: Limits) -> Result<Self, Error> {
        if !scope.valid()
            || values.is_empty()
            || values.len() > 4096
            || limits.max_outstanding == 0
            || limits.max_outstanding > MAX_OUTSTANDING
            || limits.max_dispatched == 0
            || limits.max_dispatched > 8
            || limits.max_dispatched > limits.max_outstanding
            || limits.max_reserved_bytes == 0
            || limits.max_reserved_bytes > (1 << 20)
        {
            return Err(Error::InvalidConfiguration);
        }
        // Never retain an arbitrarily over-allocated input Vec's capacity.
        let mut cells = Vec::with_capacity(values.len());
        for value in values {
            cells.push(Cell { value, version: 1 });
        }
        Ok(Self {
            owner: Arc::new(Owner),
            scope,
            generation: 1,
            loaded: true,
            exhausted: false,
            cells,
            limits,
            next_sequence: Some(0),
            clock: Arc::new(AtomicU64::new(0)),
            pending: VecDeque::with_capacity(limits.max_outstanding),
            retired: Vec::with_capacity(limits.max_outstanding),
            reserved: 0,
        })
    }
    pub fn qualify_offline(&self) -> Result<OfflineQualification, Error> {
        if !self.loaded {
            return Err(Error::Unloaded);
        }
        Ok(OfflineQualification {
            owner: self.owner.clone(),
            scope: self.scope,
            generation: self.generation,
        })
    }
    pub fn values(&self) -> Vec<i64> {
        self.cells.iter().map(|c| c.value).collect()
    }
    pub fn tick(&self) -> u64 {
        self.clock.load(Ordering::Acquire)
    }
    pub fn usage(&self) -> Usage {
        Usage {
            outstanding: self.pending.len() + self.retired.len(),
            dispatched: self
                .pending
                .iter()
                .chain(self.retired.iter())
                .filter(|e| !e.quiescent.load(Ordering::Acquire))
                .count(),
            retired: self.retired.len(),
            reserved_bytes: self.reserved,
        }
    }
    pub fn reservation_for(count: usize) -> Option<usize> {
        // Logical payload reservation: inputs, output, validation effects and
        // metadata allowance. Excludes allocator headers, thread stacks and RSS.
        count
            .checked_mul(
                std::mem::size_of::<Read>()
                    + std::mem::size_of::<Write>()
                    + std::mem::size_of::<Effect>(),
            )?
            .checked_add(1024)
    }
    pub fn submit(
        &mut self,
        q: &OfflineQualification,
        t: Transform,
        b: Budget,
    ) -> Result<TaskId, Error> {
        self.reap();
        // Admission precedence is explicit and tested for combined invalid inputs.
        if !self.loaded {
            return Err(Error::Unloaded);
        }
        if !Arc::ptr_eq(&q.owner, &self.owner)
            || q.scope != self.scope
            || q.generation != self.generation
        {
            return Err(Error::InvalidQualification);
        }
        let end = t.start.checked_add(t.count).ok_or(Error::InvalidRange)?;
        if t.count == 0 || t.count > MAX_CELLS || end > self.cells.len() {
            return Err(Error::InvalidRange);
        }
        if b.max_steps == 0
            || b.max_steps > 512
            || b.deadline_ticks == 0
            || b.wall_timeout.is_zero()
            || b.wall_timeout > Duration::from_secs(10)
        {
            return Err(Error::InvalidBudget);
        }
        let deadline = self
            .tick()
            .checked_add(b.deadline_ticks)
            .ok_or(Error::InvalidBudget)?;
        let reservation = Self::reservation_for(t.count).ok_or(Error::MemoryBudget)?;
        if reservation > b.max_payload_bytes {
            return Err(Error::MemoryBudget);
        }
        if self.usage().outstanding >= self.limits.max_outstanding {
            return Err(Error::Capacity);
        }
        let reserved = self
            .reserved
            .checked_add(reservation)
            .ok_or(Error::MemoryBudget)?;
        if reserved > self.limits.max_reserved_bytes {
            return Err(Error::MemoryBudget);
        }
        let sequence = self.next_sequence.ok_or(Error::SequenceExhausted)?;
        let reads = (t.start..end)
            .map(|index| Read {
                index,
                value: self.cells[index].value,
                version: self.cells[index].version,
            })
            .collect::<Vec<_>>()
            .into_boxed_slice();
        let task = Arc::new(Task {
            owner: self.owner.clone(),
            scope: self.scope,
            generation: self.generation,
            sequence,
            transform: t,
            reads,
            budget: b,
            deadline,
            admitted: Instant::now(),
            clock: self.clock.clone(),
            cancelled: AtomicBool::new(false),
        });
        self.pending.push_back(Entry {
            task,
            quiescent: Arc::new(AtomicBool::new(true)),
            status: Status::Queued,
            reservation,
        });
        self.reserved = reserved;
        self.next_sequence = sequence.checked_add(1);
        Ok(TaskId {
            owner: self.owner.clone(),
            sequence,
        })
    }
    pub fn dispatch(&mut self, id: &TaskId) -> Result<WorkerTicket, Error> {
        if !Arc::ptr_eq(&id.owner, &self.owner) {
            return Err(Error::WrongScheduler);
        }
        self.reap();
        let position = self
            .pending
            .iter()
            .position(|e| e.task.sequence == id.sequence)
            .ok_or(Error::UnknownSequence)?;
        if !matches!(self.pending[position].status, Status::Queued) {
            return Err(Error::AlreadyDispatched);
        }
        if self.usage().dispatched >= self.limits.max_dispatched {
            return Err(Error::DispatchCapacity);
        }
        let e = &mut self.pending[position];
        e.quiescent.store(false, Ordering::Release);
        e.status = Status::Issued;
        Ok(WorkerTicket {
            task: e.task.clone(),
            lease: Lease {
                quiescent: e.quiescent.clone(),
            },
        })
    }
    pub fn accept(&mut self, completion: Completion) -> Result<(), Error> {
        if !Arc::ptr_eq(&completion.task.owner, &self.owner) {
            return Err(Error::WrongScheduler);
        }
        let e = self
            .pending
            .iter_mut()
            .find(|e| e.task.sequence == completion.task.sequence)
            .ok_or(Error::UnknownSequence)?;
        if !Arc::ptr_eq(&e.task, &completion.task) {
            return Err(Error::WrongScheduler);
        }
        if matches!(e.status, Status::Terminal(_)) {
            return Err(Error::Terminal);
        }
        if !matches!(e.status, Status::Issued) {
            return Err(Error::DuplicateResult);
        }
        let Completion {
            result,
            steps,
            lease,
            ..
        } = completion;
        e.status = Status::Ready { result, steps };
        drop(lease);
        Ok(())
    }
    pub fn cancel(&mut self, id: &TaskId) -> Result<(), Error> {
        if !Arc::ptr_eq(&id.owner, &self.owner) {
            return Err(Error::WrongScheduler);
        }
        let e = self
            .pending
            .iter_mut()
            .find(|e| e.task.sequence == id.sequence)
            .ok_or(Error::UnknownSequence)?;
        Self::terminate(e, Reason::Cancelled);
        Ok(())
    }
    fn terminate(e: &mut Entry, reason: Reason) {
        if !matches!(e.status, Status::Terminal(_)) {
            e.task.cancelled.store(true, Ordering::Release);
            e.status = Status::Terminal(reason)
        }
    }
    pub fn advance_clock(&mut self, tick: u64) -> Result<(), Error> {
        if tick < self.tick() {
            return Err(Error::ClockWentBackwards);
        }
        self.clock.store(tick, Ordering::Release);
        Ok(())
    }
    pub fn revoke(&mut self) -> Result<(), Error> {
        for e in &mut self.pending {
            Self::terminate(e, Reason::Revoked)
        }
        match self.generation.checked_add(1) {
            Some(next) => {
                self.generation = next;
                Ok(())
            }
            None => {
                self.loaded = false;
                self.exhausted = true;
                Err(Error::CounterExhausted)
            }
        }
    }
    pub fn unload(&mut self) {
        self.loaded = false;
        for e in &mut self.pending {
            Self::terminate(e, Reason::Unloaded)
        }
    }
    pub fn reload_offline(&mut self) -> Result<(), Error> {
        if self.exhausted {
            return Err(Error::CounterExhausted);
        }
        if self.loaded {
            return Err(Error::InvalidConfiguration);
        }
        let incarnation = self
            .scope
            .incarnation
            .checked_add(1)
            .ok_or(Error::CounterExhausted)?;
        self.scope.incarnation = incarnation;
        self.loaded = true;
        Ok(())
    }
    /// Model external mutation. A real adapter must establish writer closure;
    /// this method does not instrument Java writes or qualify their completeness.
    pub fn external_write(&mut self, index: usize, value: i64) -> Result<(), Error> {
        if !self.loaded {
            return Err(Error::Unloaded);
        }
        let cell = self.cells.get_mut(index).ok_or(Error::InvalidRange)?;
        let version = cell.version.checked_add(1).ok_or(Error::CounterExhausted)?;
        *cell = Cell { value, version };
        Ok(())
    }
    pub fn reap(&mut self) {
        let mut i = 0;
        while i < self.retired.len() {
            if self.retired[i].quiescent.load(Ordering::Acquire) {
                let e = self.retired.swap_remove(i);
                self.reserved -= e.reservation
            } else {
                i += 1
            }
        }
    }
    fn validate(&self, e: &Entry, result: &[Write], steps: u64) -> Result<Vec<Effect>, Reason> {
        if !self.loaded {
            return Err(Reason::Unloaded);
        }
        if e.task.scope != self.scope || e.task.generation != self.generation {
            return Err(Reason::Revoked);
        }
        if steps != 2 * e.task.reads.len() as u64
            || steps > e.task.budget.max_steps
            || result.len() != e.task.reads.len()
        {
            return Err(Reason::InvalidResult);
        }
        // Read conflicts dominate output arithmetic/value defects; no partial apply.
        for read in &e.task.reads {
            let cell = self.cells.get(read.index).ok_or(Reason::InvalidResult)?;
            if cell.version != read.version || cell.value != read.value {
                return Err(Reason::StaleInput);
            }
        }
        let mut effects = Vec::with_capacity(result.len());
        for (read, write) in e.task.reads.iter().zip(result) {
            let value = read
                .value
                .checked_mul(e.task.transform.multiply)
                .and_then(|v| v.checked_add(e.task.transform.add))
                .ok_or(Reason::InvalidResult)?;
            if write.index != read.index || write.value != value {
                return Err(Reason::InvalidResult);
            }
            if read.version == u64::MAX {
                return Err(Reason::CellVersionExhausted);
            }
            effects.push(Effect {
                index: read.index,
                before: read.value,
                after: value,
            });
        }
        if e.task.expired() {
            return Err(Reason::Deadline);
        }
        Ok(effects)
    }
    /// One decision at a time makes every commit boundary observable. Pending
    /// head work is never bypassed; cancellation/expiry is an ordered tombstone.
    pub fn drain_one(&mut self) -> Option<Decision> {
        self.reap();
        let e = self.pending.front_mut()?;
        if !matches!(e.status, Status::Terminal(_)) && e.task.expired() {
            Self::terminate(e, Reason::Deadline)
        }
        if matches!(e.status, Status::Issued) && e.quiescent.load(Ordering::Acquire) {
            Self::terminate(e, Reason::Abandoned)
        }
        if matches!(e.status, Status::Queued | Status::Issued) {
            return None;
        }
        let mut e = self.pending.pop_front().unwrap();
        let checked = match &e.status {
            Status::Terminal(reason) => Err(*reason),
            Status::Ready {
                result: Err(reason),
                ..
            } => Err(*reason),
            Status::Ready {
                result: Ok(writes),
                steps,
            } => self.validate(&e, writes, *steps),
            _ => unreachable!(),
        };
        let (outcome, effects) = match checked {
            Ok(effects) => {
                for effect in &effects {
                    let cell = &mut self.cells[effect.index];
                    cell.value = effect.after;
                    cell.version += 1;
                }
                (Outcome::Committed, effects)
            }
            Err(reason) => (Outcome::Skipped(reason), Vec::new()),
        };
        let decision = Decision {
            sequence: e.task.sequence,
            outcome,
            tick: self.tick(),
            effects,
        };
        // Drop ready output promptly, but do not free a reservation owned by an
        // issued ticket or a completion waiting outside this scheduler.
        e.status = Status::Terminal(Reason::Abandoned);
        if e.quiescent.load(Ordering::Acquire) {
            self.reserved -= e.reservation
        } else {
            self.retired.push(e)
        }
        Some(decision)
    }
}

impl Drop for Scheduler {
    fn drop(&mut self) {
        for e in self.pending.iter().chain(self.retired.iter()) {
            e.task.cancelled.store(true, Ordering::Release)
        }
    }
}

/// Bounded std mechanism demonstration. Completion order is deliberately not a
/// semantic promise. No worker receives state access or an arbitrary callback.
pub fn execute_scoped(
    tickets: Vec<WorkerTicket>,
    workers: usize,
) -> Result<Vec<Completion>, Error> {
    if workers == 0 || workers > 8 || tickets.len() > MAX_OUTSTANDING {
        return Err(Error::InvalidConfiguration);
    }
    let count = tickets.len();
    // Capacity is chosen from the checked length, not inherited from a caller.
    let mut intake = VecDeque::with_capacity(count);
    for ticket in tickets {
        intake.push_back(ticket);
    }
    let queue = std::sync::Mutex::new(intake);
    let (tx, rx) = std::sync::mpsc::sync_channel(count.max(1));
    Ok(std::thread::scope(|scope| {
        for _ in 0..workers {
            let tx = tx.clone();
            let queue = &queue;
            scope.spawn(move || loop {
                let ticket = queue.lock().expect("worker intake poisoned").pop_front();
                let Some(ticket) = ticket else { break };
                if tx.send(ticket.run()).is_err() {
                    break;
                }
            });
        }
        drop(tx);
        rx.into_iter().collect::<Vec<_>>()
    }))
}

#[cfg(test)]
mod tests;
