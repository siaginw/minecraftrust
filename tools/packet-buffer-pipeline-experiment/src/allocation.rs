//! Requested Rust allocator traffic, not allocator usable size or OS/kernel copies.
use std::alloc::{GlobalAlloc, Layout, System};
use std::cell::Cell;
use std::sync::atomic::{AtomicU64, Ordering};

pub const STAGES: usize = 8;
pub const NAMES: [&str; STAGES] = [
    "other",
    "retain",
    "compression",
    "ordering",
    "encryption",
    "send",
    "receive",
    "oracle",
];
thread_local! { static STAGE: Cell<usize> = const { Cell::new(0) }; }
struct Meter {
    allocations: AtomicU64,
    requested: AtomicU64,
    deallocations: AtomicU64,
    freed: AtomicU64,
}
impl Meter {
    const fn new() -> Self {
        Self {
            allocations: AtomicU64::new(0),
            requested: AtomicU64::new(0),
            deallocations: AtomicU64::new(0),
            freed: AtomicU64::new(0),
        }
    }
}
static METERS: [Meter; STAGES] = [const { Meter::new() }; STAGES];
fn index() -> usize {
    STAGE.try_with(Cell::get).unwrap_or(0)
}
fn allocated(n: usize) {
    let m = &METERS[index()];
    m.allocations.fetch_add(1, Ordering::Relaxed);
    m.requested.fetch_add(n as u64, Ordering::Relaxed);
}
fn freed(n: usize) {
    let m = &METERS[index()];
    m.deallocations.fetch_add(1, Ordering::Relaxed);
    m.freed.fetch_add(n as u64, Ordering::Relaxed);
}
pub struct TrackingAllocator;
// SAFETY: allocation/deallocation is delegated unchanged to System; metering uses
// allocation-free atomics/TLS and never reads or alters the allocated memory.
unsafe impl GlobalAlloc for TrackingAllocator {
    unsafe fn alloc(&self, layout: Layout) -> *mut u8 {
        let p = unsafe { System.alloc(layout) };
        if !p.is_null() {
            allocated(layout.size());
        }
        p
    }
    unsafe fn alloc_zeroed(&self, layout: Layout) -> *mut u8 {
        let p = unsafe { System.alloc_zeroed(layout) };
        if !p.is_null() {
            allocated(layout.size());
        }
        p
    }
    unsafe fn dealloc(&self, ptr: *mut u8, layout: Layout) {
        freed(layout.size());
        unsafe { System.dealloc(ptr, layout) };
    }
    unsafe fn realloc(&self, ptr: *mut u8, layout: Layout, size: usize) -> *mut u8 {
        let p = unsafe { System.realloc(ptr, layout, size) };
        if !p.is_null() {
            freed(layout.size());
            allocated(size);
        }
        p
    }
}
pub struct Scope(usize);
pub fn stage(stage: usize) -> Scope {
    assert!(stage < STAGES);
    Scope(STAGE.with(|s| s.replace(stage)))
}
impl Drop for Scope {
    fn drop(&mut self) {
        STAGE.with(|s| s.set(self.0));
    }
}
#[derive(Clone, Copy, Debug, Default)]
pub struct Counts {
    pub allocations: u64,
    pub requested: u64,
    pub deallocations: u64,
    pub freed: u64,
}
pub fn snapshot() -> [Counts; STAGES] {
    std::array::from_fn(|i| {
        let m = &METERS[i];
        Counts {
            allocations: m.allocations.load(Ordering::Relaxed),
            requested: m.requested.load(Ordering::Relaxed),
            deallocations: m.deallocations.load(Ordering::Relaxed),
            freed: m.freed.load(Ordering::Relaxed),
        }
    })
}
