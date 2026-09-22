//! Observability and profiling counters for native subsystems.

use std::sync::atomic::{AtomicU64, Ordering};

pub struct FfiMetrics {
    pub call_count: AtomicU64,
    pub bytes_transferred: AtomicU64,
    pub native_allocations: AtomicU64,
}

impl FfiMetrics {
    pub const fn new() -> Self {
        Self {
            call_count: AtomicU64::new(0),
            bytes_transferred: AtomicU64::new(0),
            native_allocations: AtomicU64::new(0),
        }
    }

    pub fn record_call(&self, bytes: u64) {
        self.call_count.fetch_add(1, Ordering::Relaxed);
        self.bytes_transferred.fetch_add(bytes, Ordering::Relaxed);
    }
}

pub static GLOBAL_FFI_METRICS: FfiMetrics = FfiMetrics::new();
