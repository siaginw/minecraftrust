//! Schema-versioned, bounded JNI/native-body telemetry.
//!
//! Unknown measurements are never zero. Summaries are cumulative observations,
//! not transactional snapshots during concurrent calls; read after quiescence
//! for exact test/benchmark deltas. Times exclude the Java/JNI transition itself.

use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::time::Instant;

pub const FFI_METRICS_SCHEMA_VERSION: u32 = 2;
const OPERATION_SLOTS: usize = 512;

/// Stable operation identifiers, never byte counts. No runtime labels or maps.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
#[repr(u16)]
pub enum Operation {
    RuntimePing = 0,
    EncodeSections = 1,
    EncodeSectionsIntoArray = 2,
    PredictOutputLen = 3,
    CompressionCreate = 20,
    CompressionCompress = 21,
    CompressionFree = 22,
    SpawnCreate = 100,
    SpawnFree = 101,
    SpawnInsert = 102,
    SpawnUpdateBox = 103,
    SpawnRemove = 104,
    SpawnQuery = 105,
    SpawnStats = 106,
    NoiseCreate = 200,
    NoiseFree = 201,
    NoiseGen3d = 202,
    NoiseGen2d = 203,
    NoiseBatchCreate = 210,
    NoiseBatchFree = 211,
    NoiseBatchGen3d = 212,
    NoiseFieldCreate = 220,
    NoiseFieldFree = 221,
    NoiseFieldAssemble = 222,
    NoiseSingleCreate = 223,
    NoiseSingleFree = 224,
    NoiseSingleGen3d = 225,
    NoiseInitCreate = 230,
    NoiseInitFree = 231,
    NoiseInitFieldComplete = 232,
    NoiseInitCreateFromState = 233,
    TerrainSetBlocks = 240,
    TerrainSetBlocksOpt = 241,
    TerrainComplete = 242,
    TerrainSetBlocksCritical = 243,
    ChunkRegisterPrimer = 300,
    ChunkMaterializePrimer = 301,
    ChunkGetPrimaryBitMask = 302,
    ChunkEncodePacketPayload = 303,
    ChunkGetOccupancySummary = 304,
    ChunkStagePersistence = 305,
    ChunkInvalidate = 306,
    ChunkUnload = 307,
    ChunkGetRegisteredCount = 308,
    ChunkMarkMutation = 309,
    ChunkMarkSectionMutation = 310,
    ChunkRefreshSection = 311,
    ChunkGetDirtySections = 312,
    ChunkSetGlobalPaletteBits = 313,
    ChunkFindGeneration = 314,
    ChunkGetRegistryStats = 315,
    ChunkSetBiomes = 316,
    ChunkGetGenerationInfo = 317,
    ChunkRegistryClear = 318,
    ChunkGetBiomes = 319,
    ChunkGetSectionLight = 320,
    ChunkEncodePacketPayloadV2 = 321,
    OwnedSnapshotEncodeV1 = 322,
    ChunkSeedFromTransport = 323,
    ChunkSetBlockState = 324,
    ChunkGetBlockState = 325,
    ChunkGetSectionPointers = 326,
    ChunkGetSectionPointer = 327,
    ChunkGetSectionLightPointers = 328,
    ChunkGetSectionLightPointer = 329,
    ChunkGetBiomesPointer = 330,
    ChunkGetBiome = 331,
    ChunkSetBiome = 332,
    ChunkGetHeightmapPointer = 333,
    ChunkGetHeight = 334,
    ChunkRecomputeHeight = 335,
}

impl Operation {
    pub const ALL: &'static [Self] = &[
        Self::RuntimePing,
        Self::EncodeSections,
        Self::EncodeSectionsIntoArray,
        Self::PredictOutputLen,
        Self::CompressionCreate,
        Self::CompressionCompress,
        Self::CompressionFree,
        Self::SpawnCreate,
        Self::SpawnFree,
        Self::SpawnInsert,
        Self::SpawnUpdateBox,
        Self::SpawnRemove,
        Self::SpawnQuery,
        Self::SpawnStats,
        Self::NoiseCreate,
        Self::NoiseFree,
        Self::NoiseGen3d,
        Self::NoiseGen2d,
        Self::NoiseBatchCreate,
        Self::NoiseBatchFree,
        Self::NoiseBatchGen3d,
        Self::NoiseFieldCreate,
        Self::NoiseFieldFree,
        Self::NoiseFieldAssemble,
        Self::NoiseSingleCreate,
        Self::NoiseSingleFree,
        Self::NoiseSingleGen3d,
        Self::NoiseInitCreate,
        Self::NoiseInitFree,
        Self::NoiseInitFieldComplete,
        Self::NoiseInitCreateFromState,
        Self::TerrainSetBlocks,
        Self::TerrainSetBlocksOpt,
        Self::TerrainComplete,
        Self::TerrainSetBlocksCritical,
        Self::ChunkRegisterPrimer,
        Self::ChunkMaterializePrimer,
        Self::ChunkGetPrimaryBitMask,
        Self::ChunkEncodePacketPayload,
        Self::ChunkGetOccupancySummary,
        Self::ChunkStagePersistence,
        Self::ChunkInvalidate,
        Self::ChunkUnload,
        Self::ChunkGetRegisteredCount,
        Self::ChunkMarkMutation,
        Self::ChunkMarkSectionMutation,
        Self::ChunkRefreshSection,
        Self::ChunkGetDirtySections,
        Self::ChunkSetGlobalPaletteBits,
        Self::ChunkFindGeneration,
        Self::ChunkGetRegistryStats,
        Self::ChunkSetBiomes,
        Self::ChunkGetGenerationInfo,
        Self::ChunkRegistryClear,
        Self::ChunkGetBiomes,
        Self::ChunkGetSectionLight,
        Self::ChunkEncodePacketPayloadV2,
        Self::OwnedSnapshotEncodeV1,
        Self::ChunkSeedFromTransport,
        Self::ChunkSetBlockState,
        Self::ChunkGetBlockState,
        Self::ChunkGetSectionPointers,
        Self::ChunkGetSectionPointer,
        Self::ChunkGetSectionLightPointers,
        Self::ChunkGetSectionLightPointer,
        Self::ChunkGetBiomesPointer,
        Self::ChunkGetBiome,
        Self::ChunkSetBiome,
        Self::ChunkGetHeightmapPointer,
        Self::ChunkGetHeight,
        Self::ChunkRecomputeHeight,
    ];
}

/// Native outcome category. Unknown means the legacy boundary is not classified.
/// None means the observed native operation completed; it does not claim authority.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
#[repr(usize)]
pub enum FallbackReason {
    Unknown = 0,
    None = 1,
    InvalidArgument = 2,
    Capacity = 3,
    CorruptInput = 4,
    MissingState = 5,
    BackendError = 6,
    Panic = 7,
}

impl FallbackReason {
    pub const ALL: [Self; 8] = [
        Self::Unknown,
        Self::None,
        Self::InvalidArgument,
        Self::Capacity,
        Self::CorruptInput,
        Self::MissingState,
        Self::BackendError,
        Self::Panic,
    ];
}

/// Bulk-buffer bytes only: excludes scalar ABI arguments, handles and return codes.
/// input/output are admitted input and successfully published payload bytes.
/// copied counts proven explicit byte copies (not arbitrary CPU load/store traffic).
/// borrowed is the total caller-owned buffer span exposed to Rust, including output
/// capacity; it is NOT transfer volume. retained counts caller buffer bytes retained
/// after return, NOT native state created from input. allocation counts requested
/// native heap bytes for the entire boundary only where fully known (not events/RSS).
#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct ByteMeasurements {
    pub input_bytes: Option<u64>,
    pub output_bytes: Option<u64>,
    pub copied_bytes: Option<u64>,
    pub borrowed_bytes: Option<u64>,
    pub retained_bytes: Option<u64>,
    pub allocation_bytes: Option<u64>,
}

impl ByteMeasurements {
    /// No bulk payload has been admitted yet; native allocation cost is unknown.
    pub const NO_BULK: Self = Self {
        allocation_bytes: None,
        ..Self::ZERO
    };

    /// Only use when control flow proves no payload access, copies or allocation.
    pub const ZERO: Self = Self {
        input_bytes: Some(0),
        output_bytes: Some(0),
        copied_bytes: Some(0),
        borrowed_bytes: Some(0),
        retained_bytes: Some(0),
        allocation_bytes: Some(0),
    };
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct MeasurementSummary {
    pub sum_known: u64,
    pub known_samples: u64,
    pub unknown_samples: u64,
    pub overflowed: bool,
}

impl MeasurementSummary {
    /// A total is publishable only when every completed sample was measured.
    pub fn complete_total(self) -> Option<u64> {
        if self.known_samples == 0 || self.unknown_samples != 0 || self.overflowed {
            None
        } else {
            Some(self.sum_known)
        }
    }
}

struct MeasurementCounter {
    sum_known: AtomicU64,
    known_samples: AtomicU64,
    unknown_samples: AtomicU64,
    overflowed: AtomicBool,
}

impl MeasurementCounter {
    const fn new() -> Self {
        Self {
            sum_known: AtomicU64::new(0),
            known_samples: AtomicU64::new(0),
            unknown_samples: AtomicU64::new(0),
            overflowed: AtomicBool::new(false),
        }
    }

    fn add(&self, value: Option<u64>) {
        match value {
            Some(value) => {
                let previous = self
                    .sum_known
                    .fetch_update(Ordering::Relaxed, Ordering::Relaxed, |old| {
                        Some(old.saturating_add(value))
                    })
                    .unwrap();
                if previous.checked_add(value).is_none() {
                    self.overflowed.store(true, Ordering::Relaxed);
                }
                self.known_samples.fetch_add(1, Ordering::Relaxed);
            }
            None => {
                self.unknown_samples.fetch_add(1, Ordering::Relaxed);
            }
        }
    }

    fn snapshot(&self) -> MeasurementSummary {
        MeasurementSummary {
            sum_known: self.sum_known.load(Ordering::Relaxed),
            known_samples: self.known_samples.load(Ordering::Relaxed),
            unknown_samples: self.unknown_samples.load(Ordering::Relaxed),
            overflowed: self.overflowed.load(Ordering::Relaxed),
        }
    }
}

struct OperationCounters {
    call_count: AtomicU64,
    input_bytes: MeasurementCounter,
    output_bytes: MeasurementCounter,
    copied_bytes: MeasurementCounter,
    borrowed_bytes: MeasurementCounter,
    retained_bytes: MeasurementCounter,
    allocation_bytes: MeasurementCounter,
    elapsed_ns: MeasurementCounter,
    fallback_reason: [AtomicU64; FallbackReason::ALL.len()],
}

impl OperationCounters {
    const fn new() -> Self {
        Self {
            call_count: AtomicU64::new(0),
            input_bytes: MeasurementCounter::new(),
            output_bytes: MeasurementCounter::new(),
            copied_bytes: MeasurementCounter::new(),
            borrowed_bytes: MeasurementCounter::new(),
            retained_bytes: MeasurementCounter::new(),
            allocation_bytes: MeasurementCounter::new(),
            elapsed_ns: MeasurementCounter::new(),
            fallback_reason: [const { AtomicU64::new(0) }; FallbackReason::ALL.len()],
        }
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct OperationSnapshot {
    pub schema_version: u32,
    pub operation_id: u16,
    /// Completed observations; global call_count counts entry, for the legacy ABI.
    pub call_count: u64,
    pub input_bytes: MeasurementSummary,
    pub output_bytes: MeasurementSummary,
    pub copied_bytes: MeasurementSummary,
    pub borrowed_bytes: MeasurementSummary,
    pub retained_bytes: MeasurementSummary,
    pub allocation_bytes: MeasurementSummary,
    pub elapsed_ns: MeasurementSummary,
    /// Fixed array indexed by FallbackReason; no high-cardinality strings.
    pub fallback_reason: [u64; FallbackReason::ALL.len()],
}

pub struct FfiMetrics {
    /// Calls entering instrumented boundaries; preserved for the existing C getter.
    pub call_count: AtomicU64,
    operations: [OperationCounters; OPERATION_SLOTS],
}

impl Default for FfiMetrics {
    fn default() -> Self {
        Self::new()
    }
}

impl FfiMetrics {
    pub const fn new() -> Self {
        Self {
            call_count: AtomicU64::new(0),
            operations: [const { OperationCounters::new() }; OPERATION_SLOTS],
        }
    }

    pub fn begin_call(&self, operation: Operation) -> CallGuard<'_> {
        let started = Instant::now();
        self.call_count.fetch_add(1, Ordering::Relaxed);
        CallGuard {
            metrics: self,
            operation,
            started,
            bytes: ByteMeasurements::default(),
            fallback_reason: FallbackReason::Unknown,
        }
    }

    pub fn snapshot(&self, operation: Operation) -> OperationSnapshot {
        let counters = &self.operations[operation as usize];
        OperationSnapshot {
            schema_version: FFI_METRICS_SCHEMA_VERSION,
            operation_id: operation as u16,
            call_count: counters.call_count.load(Ordering::Relaxed),
            input_bytes: counters.input_bytes.snapshot(),
            output_bytes: counters.output_bytes.snapshot(),
            copied_bytes: counters.copied_bytes.snapshot(),
            borrowed_bytes: counters.borrowed_bytes.snapshot(),
            retained_bytes: counters.retained_bytes.snapshot(),
            allocation_bytes: counters.allocation_bytes.snapshot(),
            elapsed_ns: counters.elapsed_ns.snapshot(),
            fallback_reason: std::array::from_fn(|i| {
                counters.fallback_reason[i].load(Ordering::Relaxed)
            }),
        }
    }
}

#[must_use = "keep the guard alive through the native operation"]
pub struct CallGuard<'a> {
    metrics: &'a FfiMetrics,
    operation: Operation,
    started: Instant,
    pub bytes: ByteMeasurements,
    pub fallback_reason: FallbackReason,
}

impl Drop for CallGuard<'_> {
    fn drop(&mut self) {
        // Stop the interval before flushing the counters. The interval still
        // includes bookkeeping performed while the boundary body was running.
        let elapsed_ns = u64::try_from(self.started.elapsed().as_nanos()).ok();
        let reason = if std::thread::panicking() {
            FallbackReason::Panic
        } else {
            self.fallback_reason
        };
        // An outer catch may have already consumed the unwind. Neither panic
        // machinery allocations nor copies are covered by a normal-path proof.
        if reason == FallbackReason::Panic {
            self.bytes.allocation_bytes = None;
            self.bytes.copied_bytes = None;
        }
        let counters = &self.metrics.operations[self.operation as usize];
        counters.input_bytes.add(self.bytes.input_bytes);
        counters.output_bytes.add(self.bytes.output_bytes);
        counters.copied_bytes.add(self.bytes.copied_bytes);
        counters.borrowed_bytes.add(self.bytes.borrowed_bytes);
        counters.retained_bytes.add(self.bytes.retained_bytes);
        counters.allocation_bytes.add(self.bytes.allocation_bytes);
        counters.elapsed_ns.add(elapsed_ns);
        counters.fallback_reason[reason as usize].fetch_add(1, Ordering::Relaxed);
        counters.call_count.fetch_add(1, Ordering::Relaxed);
    }
}

pub static GLOBAL_FFI_METRICS: FfiMetrics = FfiMetrics::new();

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn operation_ids_never_contribute_to_bytes_and_unknown_is_not_zero() {
        let metrics = FfiMetrics::new();
        drop(metrics.begin_call(Operation::ChunkRegisterPrimer));
        drop(metrics.begin_call(Operation::ChunkGetSectionLight));
        assert_eq!(metrics.call_count.load(Ordering::Relaxed), 2);
        for op in [
            Operation::ChunkRegisterPrimer,
            Operation::ChunkGetSectionLight,
        ] {
            let sample = metrics.snapshot(op);
            assert_eq!(sample.call_count, 1);
            assert_eq!(sample.input_bytes.sum_known, 0);
            assert_eq!(sample.input_bytes.unknown_samples, 1);
            assert_eq!(sample.input_bytes.complete_total(), None);
            assert_eq!(sample.allocation_bytes.complete_total(), None);
            assert_eq!(sample.elapsed_ns.known_samples, 1);
            assert_eq!(sample.schema_version, 2);
        }
    }

    #[test]
    fn partial_accounting_never_becomes_a_complete_total() {
        let metrics = FfiMetrics::new();
        {
            let mut call = metrics.begin_call(Operation::EncodeSections);
            call.bytes.input_bytes = Some(263);
            call.bytes.output_bytes = Some(256);
        }
        drop(metrics.begin_call(Operation::EncodeSections));
        let sample = metrics.snapshot(Operation::EncodeSections);
        assert_eq!(sample.input_bytes.sum_known, 263);
        assert_eq!(sample.input_bytes.known_samples, 1);
        assert_eq!(sample.input_bytes.unknown_samples, 1);
        assert_eq!(sample.input_bytes.complete_total(), None);
    }

    #[test]
    fn explicit_zero_and_unobserved_are_distinct() {
        let metrics = FfiMetrics::new();
        assert_eq!(
            metrics
                .snapshot(Operation::RuntimePing)
                .input_bytes
                .complete_total(),
            None
        );
        {
            let mut call = metrics.begin_call(Operation::RuntimePing);
            call.bytes = ByteMeasurements::ZERO;
            call.fallback_reason = FallbackReason::None;
        }
        let sample = metrics.snapshot(Operation::RuntimePing);
        assert_eq!(sample.input_bytes.complete_total(), Some(0));
        assert_eq!(sample.fallback_reason[FallbackReason::None as usize], 1);
    }

    #[test]
    fn unwind_records_an_unknown_failed_observation() {
        let metrics = FfiMetrics::new();
        let _ = std::panic::catch_unwind(|| {
            let _call = metrics.begin_call(Operation::EncodeSections);
            panic!("telemetry unwind fixture");
        });
        let sample = metrics.snapshot(Operation::EncodeSections);
        assert_eq!(sample.call_count, 1);
        assert_eq!(sample.fallback_reason[FallbackReason::Panic as usize], 1);
        assert_eq!(sample.output_bytes.complete_total(), None);
    }

    #[test]
    fn sum_overflow_is_explicit_not_a_smaller_transfer() {
        let counter = MeasurementCounter::new();
        counter.add(Some(u64::MAX));
        counter.add(Some(1));
        let sample = counter.snapshot();
        assert!(sample.overflowed);
        assert_eq!(sample.sum_known, u64::MAX);
        assert_eq!(sample.complete_total(), None);
    }

    #[test]
    fn contained_panic_invalidates_normal_path_copy_and_allocation_proofs() {
        let metrics = FfiMetrics::new();
        {
            let mut call = metrics.begin_call(Operation::EncodeSections);
            call.bytes = ByteMeasurements::ZERO;
            let result = std::panic::catch_unwind(|| panic!("contained fixture"));
            assert!(result.is_err());
            call.fallback_reason = FallbackReason::Panic;
        }
        let sample = metrics.snapshot(Operation::EncodeSections);
        assert_eq!(sample.allocation_bytes.complete_total(), None);
        assert_eq!(sample.copied_bytes.complete_total(), None);
        assert_eq!(sample.allocation_bytes.unknown_samples, 1);
        assert_eq!(sample.copied_bytes.unknown_samples, 1);
    }

    #[test]
    fn fixed_operation_ids_are_unique_and_in_range() {
        let mut ids = Operation::ALL
            .iter()
            .map(|op| *op as usize)
            .collect::<Vec<_>>();
        let count = ids.len();
        ids.sort_unstable();
        ids.dedup();
        assert_eq!(ids.len(), count);
        assert!(ids.iter().all(|id| *id < OPERATION_SLOTS));
    }

    #[test]
    fn concurrent_known_and_unknown_observations_keep_separate_counts() {
        let metrics = FfiMetrics::new();
        std::thread::scope(|scope| {
            for thread in 0..4 {
                let metrics = &metrics;
                scope.spawn(move || {
                    for _ in 0..50 {
                        let mut call = metrics.begin_call(Operation::NoiseGen3d);
                        if thread % 2 == 0 {
                            call.bytes.output_bytes = Some(6600);
                        }
                    }
                });
            }
        });
        let sample = metrics.snapshot(Operation::NoiseGen3d);
        assert_eq!(sample.call_count, 200);
        assert_eq!(sample.output_bytes.known_samples, 100);
        assert_eq!(sample.output_bytes.unknown_samples, 100);
        assert_eq!(sample.output_bytes.sum_known, 660_000);
        assert_eq!(sample.output_bytes.complete_total(), None);
        assert_eq!(sample.elapsed_ns.known_samples, 200);
    }
}
