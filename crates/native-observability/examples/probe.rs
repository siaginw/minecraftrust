use native_observability::*;
use std::{
    alloc::{GlobalAlloc, Layout, System},
    hint::black_box,
    sync::atomic::{AtomicU64, Ordering},
    time::Instant,
};
struct CountedSystem;
static ALLOC_BYTES: AtomicU64 = AtomicU64::new(0);
static ALLOC_EVENTS: AtomicU64 = AtomicU64::new(0);
static FREED_BYTES: AtomicU64 = AtomicU64::new(0);
// Instrumentation is confined to this probe executable, never the library/production allocator.
unsafe impl GlobalAlloc for CountedSystem {
    unsafe fn alloc(&self, l: Layout) -> *mut u8 {
        let p = unsafe { System.alloc(l) };
        if !p.is_null() {
            ALLOC_BYTES.fetch_add(l.size() as u64, Ordering::Relaxed);
            ALLOC_EVENTS.fetch_add(1, Ordering::Relaxed);
        }
        p
    }
    unsafe fn alloc_zeroed(&self, l: Layout) -> *mut u8 {
        let p = unsafe { System.alloc_zeroed(l) };
        if !p.is_null() {
            ALLOC_BYTES.fetch_add(l.size() as u64, Ordering::Relaxed);
            ALLOC_EVENTS.fetch_add(1, Ordering::Relaxed);
        }
        p
    }
    unsafe fn dealloc(&self, p: *mut u8, l: Layout) {
        unsafe { System.dealloc(p, l) };
        FREED_BYTES.fetch_add(l.size() as u64, Ordering::Relaxed);
    }
    unsafe fn realloc(&self, p: *mut u8, l: Layout, n: usize) -> *mut u8 {
        let new = unsafe { System.realloc(p, l, n) };
        if !new.is_null() {
            ALLOC_BYTES.fetch_add(n as u64, Ordering::Relaxed);
            ALLOC_EVENTS.fetch_add(1, Ordering::Relaxed);
            FREED_BYTES.fetch_add(l.size() as u64, Ordering::Relaxed);
        }
        new
    }
}
#[global_allocator]
static ALLOCATOR: CountedSystem = CountedSystem;
fn main() {
    let samples = if std::env::args().any(|v| v == "--correctness-only") {
        0
    } else {
        10
    };
    let mut registry = Registry::new(2).unwrap();
    let mut disabled = registry.attach(false, None).unwrap();
    let mut enabled = registry.attach(true, None).unwrap();
    let mut collector = Collector::new(Some(16)).unwrap();
    let bytes = ALLOC_BYTES.load(Ordering::Relaxed);
    let events = ALLOC_EVENTS.load(Ordering::Relaxed);
    for i in 0..100_000 {
        collector.record_duration(Stage::Tick, Some(i % MAX_DURATION_NS));
        collector.record_quantity(Quantity::QueueBytes, Some(i));
        collector.record_reason(Reason::Completed);
    }
    let recording_bytes = ALLOC_BYTES.load(Ordering::Relaxed) - bytes;
    let recording_events = ALLOC_EVENTS.load(Ordering::Relaxed) - events;
    assert_eq!((recording_bytes, recording_events), (0, 0));
    let memory = process_memory();
    println!("{{\"kind\":\"coverage\",\"recording_alloc_bytes\":{recording_bytes},\"recording_alloc_events\":{recording_events},\"histogram_counter_bytes\":{},\"trace_kept\":{},\"trace_dropped\":{},\"working_set_bytes\":{},\"private_commit_bytes\":{},\"native_allocator_live_bytes\":null}}",collector.histogram_counter_bytes(),collector.traces().len(),collector.trace_dropped,memory.working_set_bytes.map_or("null".to_owned(),|v|v.to_string()),memory.private_commit_bytes.map_or("null".to_owned(),|v|v.to_string()));
    let ffi = metrics::FfiMetrics::new();
    for sample in 0..samples {
        for order in 0..4 {
            let mode = (sample + order) % 4;
            let rounds = 100_000;
            let start = Instant::now();
            let mut state = 123u64;
            for _ in 0..rounds {
                state = match mode {
                    0 => black_box(state).wrapping_mul(13).rotate_left(7),
                    1 => disabled.measure(Stage::Tick, || {
                        black_box(state).wrapping_mul(13).rotate_left(7)
                    }),
                    2 => enabled.measure(Stage::Tick, || {
                        black_box(state).wrapping_mul(13).rotate_left(7)
                    }),
                    _ => {
                        let mut guard = ffi.begin_call(metrics::Operation::RuntimePing);
                        guard.bytes = metrics::ByteMeasurements::ZERO;
                        guard.fallback_reason = metrics::FallbackReason::None;
                        black_box(state).wrapping_mul(13).rotate_left(7)
                    }
                }
            }
            println!("{{\"kind\":\"timing\",\"sample\":{sample},\"mode\":{mode},\"iterations\":{rounds},\"ns\":{},\"state\":{}}}",start.elapsed().as_nanos(),black_box(state));
        }
    }
    registry.close(&mut enabled).unwrap();
    registry.close(&mut disabled).unwrap();
    assert_eq!(
        registry.aggregate().duration(Stage::Tick).coverage.known,
        samples * 100_000
    );
    let view = sample_h3(&ffi, metrics::Operation::RuntimePing);
    assert_eq!(view.native.call_count, samples * 100_000);
    assert_eq!(view.whole_java_call_ns, None);
}
