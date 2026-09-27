//! Process-local executable instrumentation: requested Rust layouts, not allocator usable size.
use std::alloc::{GlobalAlloc, Layout};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
#[cfg(all(feature = "mi", feature = "rp"))]
compile_error!("choose exactly one experiment backend");
#[cfg(feature = "mi")]
static BACKEND: mimalloc::MiMalloc = mimalloc::MiMalloc;
#[cfg(feature = "rp")]
static BACKEND: rpmalloc::RpMalloc = rpmalloc::RpMalloc;
#[cfg(not(any(feature = "mi", feature = "rp")))]
static BACKEND: std::alloc::System = std::alloc::System;
#[cfg(feature = "mi")]
pub const NAME: &str = "mimalloc-0.1.52";
#[cfg(feature = "rp")]
pub const NAME: &str = "rpmalloc-0.2.2";
#[cfg(not(any(feature = "mi", feature = "rp")))]
pub const NAME: &str = "system";
static ALLOCATED: AtomicU64 = AtomicU64::new(0);
static FREED: AtomicU64 = AtomicU64::new(0);
static EVENTS: AtomicU64 = AtomicU64::new(0);
static FREE_EVENTS: AtomicU64 = AtomicU64::new(0);
static REALLOCS: AtomicU64 = AtomicU64::new(0);
static LIVE: AtomicU64 = AtomicU64::new(0);
static PEAK: AtomicU64 = AtomicU64::new(0);
static FAILED: AtomicU64 = AtomicU64::new(0);
static OVERFLOW: AtomicBool = AtomicBool::new(false);
fn add(counter: &AtomicU64, n: u64) {
    if counter
        .fetch_update(Ordering::Relaxed, Ordering::Relaxed, |v| v.checked_add(n))
        .is_err()
    {
        OVERFLOW.store(true, Ordering::Relaxed);
    }
}
fn allocated(n: usize) {
    add(&ALLOCATED, n as u64);
    add(&EVENTS, 1);
    match LIVE.fetch_update(Ordering::Relaxed, Ordering::Relaxed, |v| {
        v.checked_add(n as u64)
    }) {
        Ok(old) => {
            PEAK.fetch_max(old + n as u64, Ordering::Relaxed);
        }
        Err(_) => OVERFLOW.store(true, Ordering::Relaxed),
    }
}
fn freed(n: usize) {
    add(&FREED, n as u64);
    add(&FREE_EVENTS, 1);
    if LIVE
        .fetch_update(Ordering::Relaxed, Ordering::Relaxed, |v| {
            v.checked_sub(n as u64)
        })
        .is_err()
    {
        OVERFLOW.store(true, Ordering::Relaxed);
    }
}
pub struct Tracking;
// SAFETY: all pointers/layouts pass unchanged to one fixed backend. Only allocation-free atomics
// are touched by instrumentation. Failure preserves original allocation ownership.
unsafe impl GlobalAlloc for Tracking {
    unsafe fn alloc(&self, l: Layout) -> *mut u8 {
        let p = unsafe { BACKEND.alloc(l) };
        if p.is_null() {
            add(&FAILED, 1)
        } else {
            allocated(l.size())
        }
        p
    }
    unsafe fn alloc_zeroed(&self, l: Layout) -> *mut u8 {
        let p = unsafe { BACKEND.alloc_zeroed(l) };
        if p.is_null() {
            add(&FAILED, 1)
        } else {
            allocated(l.size())
        }
        p
    }
    unsafe fn dealloc(&self, p: *mut u8, l: Layout) {
        unsafe { BACKEND.dealloc(p, l) };
        freed(l.size());
    }
    unsafe fn realloc(&self, p: *mut u8, l: Layout, n: usize) -> *mut u8 {
        let q = unsafe { BACKEND.realloc(p, l, n) };
        if q.is_null() {
            add(&FAILED, 1)
        } else {
            freed(l.size());
            allocated(n);
            add(&REALLOCS, 1)
        }
        q
    }
}
#[derive(Clone, Copy, Debug)]
pub struct Counts {
    pub allocated: u64,
    pub freed: u64,
    pub allocation_events: u64,
    pub free_events: u64,
    pub reallocations: u64,
    pub live: u64,
    pub peak: u64,
    pub failures: u64,
    pub overflow: bool,
}
pub fn snapshot() -> Counts {
    Counts {
        allocated: ALLOCATED.load(Ordering::Relaxed),
        freed: FREED.load(Ordering::Relaxed),
        allocation_events: EVENTS.load(Ordering::Relaxed),
        free_events: FREE_EVENTS.load(Ordering::Relaxed),
        reallocations: REALLOCS.load(Ordering::Relaxed),
        live: LIVE.load(Ordering::Relaxed),
        peak: PEAK.load(Ordering::Relaxed),
        failures: FAILED.load(Ordering::Relaxed),
        overflow: OVERFLOW.load(Ordering::Relaxed),
    }
}
/// Quiescent reset of the high-water mark only; cumulative/live counters are never reset.
pub fn reset_peak() {
    PEAK.store(LIVE.load(Ordering::Relaxed), Ordering::Relaxed);
}
pub fn json(c: Counts) -> serde_json::Value {
    serde_json::json!({"requested_allocated_bytes":c.allocated,"requested_freed_bytes":c.freed,"allocation_events":c.allocation_events,"free_events":c.free_events,"reallocations":c.reallocations,"requested_live_bytes":c.live,"requested_peak_bytes":c.peak,"failures":c.failures,"overflow":c.overflow})
}

#[cfg(windows)]
pub fn cpu_ns() -> Option<u64> {
    #[repr(C)]
    #[derive(Default, Clone, Copy)]
    struct FileTime {
        low: u32,
        high: u32,
    }
    #[link(name = "kernel32")]
    extern "system" {
        fn GetCurrentProcess() -> *mut std::ffi::c_void;
        fn GetProcessTimes(
            p: *mut std::ffi::c_void,
            c: *mut FileTime,
            e: *mut FileTime,
            k: *mut FileTime,
            u: *mut FileTime,
        ) -> i32;
    }
    let (mut c, mut e, mut k, mut u) = (
        FileTime::default(),
        FileTime::default(),
        FileTime::default(),
        FileTime::default(),
    );
    // SAFETY: pseudo-handle is valid; all four exact-layout output structures remain alive.
    let ok = unsafe { GetProcessTimes(GetCurrentProcess(), &mut c, &mut e, &mut k, &mut u) };
    let value = |f: FileTime| u64::from(f.low) | (u64::from(f.high) << 32);
    (ok != 0)
        .then(|| value(k).checked_add(value(u))?.checked_mul(100))
        .flatten()
}
#[cfg(not(windows))]
pub fn cpu_ns() -> Option<u64> {
    None
}
