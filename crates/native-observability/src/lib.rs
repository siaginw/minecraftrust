//! Bounded per-worker observability; no optimization or ownership authority.
use hdrhistogram::Histogram;
use std::sync::{
    atomic::{AtomicBool, AtomicU64, Ordering},
    Arc,
};
use std::time::Instant;

pub const SCHEMA: &str = "RUSTCRAFT_OBSERVABILITY_V1";
pub const MAX_DURATION_NS: u64 = 60_000_000_000;
pub const SIGNIFICANT_DIGITS: u8 = 3;
pub const MAX_WORKERS: usize = 8;
pub const TRACE_CAPACITY: usize = 256;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
#[repr(usize)]
pub enum Stage {
    Tick,
    TickWorld,
    TickEntities,
    TickBlockEntities,
    ChunkRead,
    ChunkDecode,
    ChunkGenerate,
    ChunkLight,
    ChunkPublish,
    JniNativeBody,
    JniWholeCall,
    NetworkSerialize,
    NetworkCompress,
    NetworkSend,
    LockWait,
    LockHold,
    CallbackInclusive,
    CallbackExclusive,
}
impl Stage {
    pub const ALL: [Self; 18] = [
        Self::Tick,
        Self::TickWorld,
        Self::TickEntities,
        Self::TickBlockEntities,
        Self::ChunkRead,
        Self::ChunkDecode,
        Self::ChunkGenerate,
        Self::ChunkLight,
        Self::ChunkPublish,
        Self::JniNativeBody,
        Self::JniWholeCall,
        Self::NetworkSerialize,
        Self::NetworkCompress,
        Self::NetworkSend,
        Self::LockWait,
        Self::LockHold,
        Self::CallbackInclusive,
        Self::CallbackExclusive,
    ];
}
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
#[repr(usize)]
pub enum Quantity {
    JniInputBytes,
    JniOutputBytes,
    JniCopiedBytes,
    JniBorrowedBytes,
    JniRetainedBytes,
    NativeAllocationBytes,
    NativeAllocationEvents,
    ProcessWorkingSetBytes,
    JavaHeapUsedBytes,
    JavaHeapCommittedBytes,
    JavaGcCount,
    JavaGcMillis,
    SnapshotRetainedBytes,
    QueueDepth,
    QueueBytes,
    MissedTickDeadlines,
}
impl Quantity {
    pub const COUNT: usize = 16;
    /// Only per-event deltas have additive totals. Gauges/cumulative snapshots do not.
    pub fn is_event_delta(self) -> bool {
        matches!(
            self,
            Self::JniInputBytes
                | Self::JniOutputBytes
                | Self::JniCopiedBytes
                | Self::JniBorrowedBytes
                | Self::JniRetainedBytes
                | Self::NativeAllocationBytes
                | Self::NativeAllocationEvents
                | Self::MissedTickDeadlines
        )
    }
}
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
#[repr(usize)]
pub enum Reason {
    Unknown,
    Completed,
    Disabled,
    MissingAdapter,
    MissingCapability,
    RevokedCapability,
    InvalidIdentity,
    Capacity,
    StaleLifecycle,
    CallbackException,
    ClockInvalid,
    CounterOverflow,
}
impl Reason {
    pub const COUNT: usize = 12;
}
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum Error {
    Capacity,
    ForeignWorker,
    StaleWorker,
    InvalidConfiguration,
    Histogram,
}

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct Coverage {
    pub known: u64,
    pub unknown: u64,
    pub sum: u64,
    pub overflow: bool,
}
impl Coverage {
    pub fn observe(&mut self, value: Option<u64>) {
        match value {
            Some(v) => {
                self.known = add(self.known, 1, &mut self.overflow);
                self.sum = add(self.sum, v, &mut self.overflow)
            }
            None => self.unknown = add(self.unknown, 1, &mut self.overflow),
        }
    }
    pub fn complete_total(self) -> Option<u64> {
        (self.known > 0 && self.unknown == 0 && !self.overflow).then_some(self.sum)
    }
    fn merge(&mut self, other: Self) {
        self.overflow |= other.overflow;
        self.known = add(self.known, other.known, &mut self.overflow);
        self.unknown = add(self.unknown, other.unknown, &mut self.overflow);
        self.sum = add(self.sum, other.sum, &mut self.overflow)
    }
}
fn add(a: u64, b: u64, overflow: &mut bool) -> u64 {
    match a.checked_add(b) {
        Some(v) => v,
        None => {
            *overflow = true;
            u64::MAX
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct QuantileInterval {
    pub lower_ns: u64,
    pub upper_ns: u64,
}
pub struct DurationMetric {
    histogram: Histogram<u64>,
    pub coverage: Coverage,
    pub above_range: u64,
    pub maximum_ns: Option<u64>,
}
impl DurationMetric {
    pub fn record_unknown_count(&mut self, count: u64) {
        self.coverage.unknown = add(self.coverage.unknown, count, &mut self.coverage.overflow);
    }
    pub fn new() -> Result<Self, Error> {
        let mut histogram = Histogram::new_with_bounds(1, MAX_DURATION_NS, SIGNIFICANT_DIGITS)
            .map_err(|_| Error::Histogram)?;
        histogram.auto(false);
        Ok(Self {
            histogram,
            coverage: Coverage::default(),
            above_range: 0,
            maximum_ns: None,
        })
    }
    pub fn record(&mut self, ns: Option<u64>) {
        self.coverage.observe(ns);
        if let Some(v) = ns {
            self.maximum_ns = Some(self.maximum_ns.map_or(v, |old| old.max(v)));
            if v > MAX_DURATION_NS {
                self.above_range = add(self.above_range, 1, &mut self.coverage.overflow)
            } else if self.histogram.record(v).is_err() {
                self.coverage.overflow = true
            }
        }
    }
    /// Exact nearest-rank population semantics, bounded HDR value interval.
    /// Unknown observations or arithmetic overflow invalidate the complete quantile.
    /// Out-of-range observations occupy their true tail ranks, never the last bucket.
    pub fn percentile(&self, percent: u8) -> Option<QuantileInterval> {
        if !(1..=100).contains(&percent)
            || self.coverage.unknown != 0
            || self.coverage.overflow
            || self.coverage.known == 0
        {
            return None;
        }
        let rank = (u128::from(self.coverage.known) * u128::from(percent)).div_ceil(100);
        if rank > u128::from(self.histogram.len()) {
            return None;
        }
        let mut seen = 0u128;
        for bucket in self.histogram.iter_recorded() {
            seen += u128::from(bucket.count_since_last_iteration());
            if seen >= rank {
                let v = bucket.value_iterated_to();
                return Some(QuantileInterval {
                    lower_ns: self.histogram.lowest_equivalent(v),
                    upper_ns: self.histogram.highest_equivalent(v).min(MAX_DURATION_NS),
                });
            }
        }
        None
    }
    pub fn allocated_counter_bytes(&self) -> usize {
        self.histogram.distinct_values() * std::mem::size_of::<u64>()
    }
    pub fn recorded_count(&self) -> u64 {
        self.histogram.len()
    }
    fn merge(&mut self, other: &Self) {
        self.coverage.merge(other.coverage);
        self.above_range = add(
            self.above_range,
            other.above_range,
            &mut self.coverage.overflow,
        );
        self.maximum_ns = match (self.maximum_ns, other.maximum_ns) {
            (Some(a), Some(b)) => Some(a.max(b)),
            (a, b) => a.or(b),
        };
        if self.histogram.add(&other.histogram).is_err() {
            self.coverage.overflow = true
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct TraceEvent {
    pub stage: Stage,
    pub elapsed_ns: Option<u64>,
    pub sequence: u64,
}
pub struct Collector {
    durations: Vec<DurationMetric>,
    quantities: [Coverage; Quantity::COUNT],
    latest: [Option<u64>; Quantity::COUNT],
    reasons: [u64; Reason::COUNT],
    reason_overflow: bool,
    traces: Vec<TraceEvent>,
    trace_every: u64,
    sequence: u64,
    pub trace_dropped: u64,
}
impl Collector {
    pub fn record_missing(&mut self, stage: Stage, count: u64) {
        self.durations[stage as usize].record_unknown_count(count);
    }
    pub fn new(trace_every: Option<u64>) -> Result<Self, Error> {
        if trace_every == Some(0) {
            return Err(Error::InvalidConfiguration);
        }
        let durations = (0..Stage::ALL.len())
            .map(|_| DurationMetric::new())
            .collect::<Result<_, _>>()?;
        Ok(Self {
            durations,
            quantities: [Coverage::default(); Quantity::COUNT],
            latest: [None; Quantity::COUNT],
            reasons: [0; Reason::COUNT],
            reason_overflow: false,
            traces: Vec::with_capacity(TRACE_CAPACITY),
            trace_every: trace_every.unwrap_or(0),
            sequence: 0,
            trace_dropped: 0,
        })
    }
    pub fn duration(&self, stage: Stage) -> &DurationMetric {
        &self.durations[stage as usize]
    }
    pub fn quantity(&self, kind: Quantity) -> Coverage {
        self.quantities[kind as usize]
    }
    /// Sum only actual event deltas; never sum point gauges or cumulative MXBean samples.
    pub fn event_total(&self, kind: Quantity) -> Option<u64> {
        kind.is_event_delta()
            .then(|| self.quantities[kind as usize].complete_total())
            .flatten()
    }
    /// Raw observation coverage; its sum is not an additive resource total for gauge kinds.
    pub fn latest(&self, kind: Quantity) -> Option<u64> {
        self.latest[kind as usize]
    }
    pub fn reason_count(&self, reason: Reason) -> u64 {
        self.reasons[reason as usize]
    }
    pub fn reason_overflowed(&self) -> bool {
        self.reason_overflow
    }
    pub fn traces(&self) -> &[TraceEvent] {
        &self.traces
    }
    pub fn record_duration(&mut self, stage: Stage, value: Option<u64>) {
        self.durations[stage as usize].record(value);
        self.sequence = add(self.sequence, 1, &mut self.reason_overflow);
        if self.trace_every != 0 && self.sequence.is_multiple_of(self.trace_every) {
            if self.traces.len() < TRACE_CAPACITY {
                self.traces.push(TraceEvent {
                    stage,
                    elapsed_ns: value,
                    sequence: self.sequence,
                })
            } else {
                self.trace_dropped = add(self.trace_dropped, 1, &mut self.reason_overflow)
            }
        }
    }
    pub fn record_quantity(&mut self, kind: Quantity, value: Option<u64>) {
        self.quantities[kind as usize].observe(value);
        self.latest[kind as usize] = value
    }
    pub fn record_reason(&mut self, reason: Reason) {
        self.reasons[reason as usize] =
            add(self.reasons[reason as usize], 1, &mut self.reason_overflow)
    }
    pub fn histogram_counter_bytes(&self) -> usize {
        self.durations
            .iter()
            .map(DurationMetric::allocated_counter_bytes)
            .sum()
    }
    fn merge(&mut self, other: &Self) {
        for (a, b) in self.durations.iter_mut().zip(&other.durations) {
            a.merge(b)
        }
        for (a, b) in self.quantities.iter_mut().zip(&other.quantities) {
            a.merge(*b)
        }
        for (a, b) in self.reasons.iter_mut().zip(&other.reasons) {
            *a = add(*a, *b, &mut self.reason_overflow)
        }
        self.reason_overflow |= other.reason_overflow;
        self.trace_dropped = add(
            self.trace_dropped,
            other.trace_dropped,
            &mut self.reason_overflow,
        );
        self.trace_dropped = add(
            self.trace_dropped,
            other.traces.len() as u64,
            &mut self.reason_overflow,
        );
        // Worker-local gauges and trace order cannot be combined into a coherent global instant.
        self.latest = [None; Quantity::COUNT];
    }
}

static NEXT_ORIGIN: AtomicU64 = AtomicU64::new(1);
struct Lease {
    active: AtomicBool,
}
/// Private origin/slot/generation prevents foreign-worker and slot-reuse attribution.
pub struct Worker {
    origin: u64,
    slot: usize,
    generation: u64,
    lease: Arc<Lease>,
    collector: Option<Collector>,
}
impl Worker {
    pub fn enabled(&self) -> bool {
        self.collector.is_some() && self.lease.active.load(Ordering::Acquire)
    }
    pub fn record_duration(&mut self, stage: Stage, value: Option<u64>) -> bool {
        if !self.lease.active.load(Ordering::Acquire) {
            return false;
        }
        if let Some(c) = &mut self.collector {
            c.record_duration(stage, value);
            true
        } else {
            false
        }
    }
    pub fn record_quantity(&mut self, kind: Quantity, value: Option<u64>) -> bool {
        if !self.lease.active.load(Ordering::Acquire) {
            return false;
        }
        if let Some(c) = &mut self.collector {
            c.record_quantity(kind, value);
            true
        } else {
            false
        }
    }
    pub fn record_reason(&mut self, reason: Reason) -> bool {
        if !self.lease.active.load(Ordering::Acquire) {
            return false;
        }
        if let Some(c) = &mut self.collector {
            c.record_reason(reason);
            true
        } else {
            false
        }
    }
    pub fn collector(&self) -> Option<&Collector> {
        self.collector.as_ref()
    }
    /// Disabled path invokes neither clock nor collector. Enabled guard records on unwind.
    pub fn measure<T>(&mut self, stage: Stage, body: impl FnOnce() -> T) -> T {
        if !self.enabled() {
            return body();
        }
        struct Guard<'a> {
            worker: &'a mut Worker,
            stage: Stage,
            start: Instant,
        }
        impl Drop for Guard<'_> {
            fn drop(&mut self) {
                self.worker.record_duration(
                    self.stage,
                    u64::try_from(self.start.elapsed().as_nanos()).ok(),
                );
                if std::thread::panicking() {
                    self.worker.record_reason(Reason::CallbackException);
                }
            }
        }
        let _guard = Guard {
            worker: self,
            stage,
            start: Instant::now(),
        };
        body()
    }
}
struct Slot {
    generation: u64,
    lease: Option<Arc<Lease>>,
    retired: bool,
}
/// Bounded registration and quiescent merge. Closed workers contain no global retained references.
pub struct Registry {
    origin: u64,
    slots: Vec<Slot>,
    aggregate: Collector,
}
impl Registry {
    pub fn new(capacity: usize) -> Result<Self, Error> {
        if !(1..=MAX_WORKERS).contains(&capacity) {
            return Err(Error::Capacity);
        }
        let origin = NEXT_ORIGIN
            .fetch_update(Ordering::Relaxed, Ordering::Relaxed, |n| n.checked_add(1))
            .map_err(|_| Error::Capacity)?;
        Ok(Self {
            origin,
            slots: (0..capacity)
                .map(|_| Slot {
                    generation: 1,
                    lease: None,
                    retired: false,
                })
                .collect(),
            aggregate: Collector::new(None)?,
        })
    }
    pub fn attach(&mut self, enabled: bool, trace_every: Option<u64>) -> Result<Worker, Error> {
        let (slot, s) = self
            .slots
            .iter_mut()
            .enumerate()
            .find(|(_, s)| s.lease.is_none() && !s.retired)
            .ok_or(Error::Capacity)?;
        let collector = if enabled {
            Some(Collector::new(trace_every)?)
        } else {
            None
        };
        let lease = Arc::new(Lease {
            active: AtomicBool::new(true),
        });
        s.lease = Some(lease.clone());
        Ok(Worker {
            origin: self.origin,
            slot,
            generation: s.generation,
            lease,
            collector,
        })
    }
    pub fn close(&mut self, worker: &mut Worker) -> Result<(), Error> {
        if worker.origin != self.origin {
            return Err(Error::ForeignWorker);
        }
        let slot = &mut self.slots[worker.slot];
        if slot.generation != worker.generation
            || !slot
                .lease
                .as_ref()
                .is_some_and(|l| Arc::ptr_eq(l, &worker.lease))
        {
            return Err(Error::StaleWorker);
        }
        worker.lease.active.store(false, Ordering::Release);
        if let Some(c) = worker.collector.take() {
            self.aggregate.merge(&c)
        }
        slot.lease = None;
        match slot.generation.checked_add(1) {
            Some(n) => slot.generation = n,
            None => slot.retired = true,
        }
        Ok(())
    }
    pub fn aggregate(&self) -> &Collector {
        &self.aggregate
    }
    pub fn active_workers(&self) -> usize {
        self.slots.iter().filter(|s| s.lease.is_some()).count()
    }
}
impl Drop for Registry {
    fn drop(&mut self) {
        for s in &self.slots {
            if let Some(l) = &s.lease {
                l.active.store(false, Ordering::Release)
            }
        }
    }
}

/// Real H3 read adapter. This is a cumulative view, never converted into histogram
/// samples or added repeatedly to totals. Full Java/JNI call timing remains unknown.
pub struct JniCumulative {
    pub native: metrics::OperationSnapshot,
    pub whole_java_call_ns: Option<u64>,
}
pub fn sample_h3(source: &metrics::FfiMetrics, operation: metrics::Operation) -> JniCumulative {
    JniCumulative {
        native: source.snapshot(operation),
        whole_java_call_ns: None,
    }
}

/// A point sample of this process working set, not Rust allocator-owned bytes.
#[derive(Clone, Copy, Debug)]
pub struct ProcessMemory {
    pub working_set_bytes: Option<u64>,
    pub private_commit_bytes: Option<u64>,
    pub native_allocator_live_bytes: Option<u64>,
}
#[cfg(windows)]
pub fn process_memory() -> ProcessMemory {
    #[repr(C)]
    struct Counters {
        cb: u32,
        page_fault_count: u32,
        peak_working_set: usize,
        working_set: usize,
        quota_peak_paged: usize,
        quota_paged: usize,
        quota_peak_nonpaged: usize,
        quota_nonpaged: usize,
        pagefile: usize,
        peak_pagefile: usize,
        private_usage: usize,
    }
    #[link(name = "kernel32")]
    extern "system" {
        fn GetCurrentProcess() -> *mut std::ffi::c_void;
        fn K32GetProcessMemoryInfo(
            process: *mut std::ffi::c_void,
            output: *mut Counters,
            size: u32,
        ) -> i32;
    }
    let mut data = Counters {
        cb: std::mem::size_of::<Counters>() as u32,
        page_fault_count: 0,
        peak_working_set: 0,
        working_set: 0,
        quota_peak_paged: 0,
        quota_paged: 0,
        quota_peak_nonpaged: 0,
        quota_nonpaged: 0,
        pagefile: 0,
        peak_pagefile: 0,
        private_usage: 0,
    };
    // SAFETY: pseudo-handle requires no close; output is a valid writable exact-sized C structure.
    let ok = unsafe { K32GetProcessMemoryInfo(GetCurrentProcess(), &mut data, data.cb) } != 0;
    ProcessMemory {
        working_set_bytes: ok.then_some(data.working_set as u64),
        private_commit_bytes: ok.then_some(data.private_usage as u64),
        native_allocator_live_bytes: None,
    }
}
#[cfg(not(windows))]
pub fn process_memory() -> ProcessMemory {
    ProcessMemory {
        working_set_bytes: None,
        private_commit_bytes: None,
        native_allocator_live_bytes: None,
    }
}
