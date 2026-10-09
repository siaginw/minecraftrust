use std::alloc::{GlobalAlloc, Layout, System};
use std::sync::atomic::{AtomicU64, Ordering::Relaxed};
pub struct Counted;
static LIVE: AtomicU64 = AtomicU64::new(0);
static PEAK: AtomicU64 = AtomicU64::new(0);
static REQUESTED: AtomicU64 = AtomicU64::new(0);
static CALLS: AtomicU64 = AtomicU64::new(0);
fn add(size: usize) {
    let live = LIVE.fetch_add(size as u64, Relaxed) + size as u64;
    PEAK.fetch_max(live, Relaxed);
    REQUESTED.fetch_add(size as u64, Relaxed);
    CALLS.fetch_add(1, Relaxed);
}
// Safety: all allocation/deallocation is delegated with unchanged pointer and
// Layout to System. Counters allocate nothing and never touch allocated memory.
unsafe impl GlobalAlloc for Counted {
    unsafe fn alloc(&self, l: Layout) -> *mut u8 {
        let p = unsafe { System.alloc(l) };
        if !p.is_null() {
            add(l.size())
        }
        p
    }
    unsafe fn alloc_zeroed(&self, l: Layout) -> *mut u8 {
        let p = unsafe { System.alloc_zeroed(l) };
        if !p.is_null() {
            add(l.size())
        }
        p
    }
    unsafe fn dealloc(&self, p: *mut u8, l: Layout) {
        LIVE.fetch_sub(l.size() as u64, Relaxed);
        unsafe { System.dealloc(p, l) }
    }
    unsafe fn realloc(&self, p: *mut u8, l: Layout, n: usize) -> *mut u8 {
        let result = unsafe { System.realloc(p, l, n) };
        if !result.is_null() {
            LIVE.fetch_sub(l.size() as u64, Relaxed);
            add(n)
        }
        result
    }
}
#[derive(Clone, Copy)]
pub struct Counters {
    pub live: u64,
    pub peak: u64,
    pub requested: u64,
    pub calls: u64,
}
pub fn counters() -> Counters {
    Counters {
        live: LIVE.load(Relaxed),
        peak: PEAK.load(Relaxed),
        requested: REQUESTED.load(Relaxed),
        calls: CALLS.load(Relaxed),
    }
}
pub fn reset_peak() {
    PEAK.store(LIVE.load(Relaxed), Relaxed)
}
