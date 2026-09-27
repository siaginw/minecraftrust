use native_observability::*;

#[test]
fn quantiles_match_independent_sorted_nearest_ranks() {
    let mut h = DurationMetric::new().unwrap();
    let values: Vec<_> = (1..=10000).map(|i| i * 7919 % 50_000_000).collect();
    for v in &values {
        h.record(Some(*v))
    }
    let mut sorted = values;
    sorted.sort();
    for p in 1..=100 {
        let exact = sorted[(sorted.len() * p as usize).div_ceil(100) - 1];
        let q = h.percentile(p).unwrap();
        assert!(q.lower_ns <= exact && exact <= q.upper_ns);
        assert!(q.upper_ns - q.lower_ns <= exact / 500 + 1)
    }
    assert_eq!(h.coverage.known, 10000)
}
#[test]
fn zero_empty_invalid_quantiles() {
    let mut h = DurationMetric::new().unwrap();
    assert_eq!(h.percentile(50), None);
    h.record(Some(0));
    assert_eq!(
        h.percentile(50),
        Some(QuantileInterval {
            lower_ns: 0,
            upper_ns: 0
        })
    );
    assert_eq!(h.percentile(0), None);
    assert_eq!(h.percentile(101), None)
}
#[test]
fn overflow_tail_is_not_clipped_or_omitted() {
    let mut h = DurationMetric::new().unwrap();
    let bytes = h.allocated_counter_bytes();
    for _ in 0..99 {
        h.record(Some(1))
    }
    h.record(Some(MAX_DURATION_NS + 1));
    assert_eq!(h.recorded_count(), 99);
    assert_eq!(h.above_range, 1);
    assert!(h.percentile(99).is_some());
    assert_eq!(h.percentile(100), None);
    assert_eq!(bytes, h.allocated_counter_bytes());
    assert_eq!(h.maximum_ns, Some(MAX_DURATION_NS + 1));
    h.record(None);
    assert_eq!(h.percentile(50), None)
}
#[test]
fn unknown_and_counter_overflow_never_become_zero() {
    let mut c = Coverage::default();
    assert_eq!(c.complete_total(), None);
    c.observe(Some(0));
    assert_eq!(c.complete_total(), Some(0));
    c.observe(None);
    assert_eq!(c.complete_total(), None);
    let mut c = Coverage::default();
    c.observe(Some(u64::MAX));
    c.observe(Some(1));
    assert!(c.overflow);
    assert_eq!(c.complete_total(), None)
}
#[test]
fn disabled_worker_does_not_record_or_change_callback() {
    let mut r = Registry::new(1).unwrap();
    let mut w = r.attach(false, None).unwrap();
    let mut calls = 0;
    let out = w.measure(Stage::Tick, || {
        calls += 1;
        42
    });
    assert_eq!((calls, out), (1, 42));
    assert!(!w.record_duration(Stage::Tick, Some(1)));
    r.close(&mut w).unwrap();
    assert_eq!(r.aggregate().duration(Stage::Tick).coverage.known, 0)
}
#[test]
fn worker_origins_reuse_and_closed_rejection() {
    let mut a = Registry::new(1).unwrap();
    let mut b = Registry::new(1).unwrap();
    let mut old = a.attach(true, None).unwrap();
    assert_eq!(b.close(&mut old), Err(Error::ForeignWorker));
    old.record_duration(Stage::Tick, Some(11));
    a.close(&mut old).unwrap();
    let mut new = a.attach(true, None).unwrap();
    assert_eq!(a.close(&mut old), Err(Error::StaleWorker));
    assert!(!old.record_duration(Stage::Tick, Some(99)));
    new.record_duration(Stage::Tick, Some(12));
    a.close(&mut new).unwrap();
    assert_eq!(
        a.aggregate()
            .duration(Stage::Tick)
            .coverage
            .complete_total(),
        Some(23)
    )
}
#[test]
fn registry_drop_revokes_worker() {
    let mut r = Registry::new(1).unwrap();
    let mut w = r.attach(true, None).unwrap();
    drop(r);
    assert!(!w.enabled());
    assert!(!w.record_duration(Stage::Tick, Some(1)))
}
#[test]
fn bounded_worker_and_trace_capacity() {
    assert!(matches!(Registry::new(9), Err(Error::Capacity)));
    let mut r = Registry::new(1).unwrap();
    let mut w = r.attach(true, Some(2)).unwrap();
    assert!(matches!(r.attach(true, None), Err(Error::Capacity)));
    for _ in 0..1024 {
        w.record_duration(Stage::LockHold, Some(1));
    }
    let c = w.collector().unwrap();
    assert_eq!(c.traces().len(), 256);
    assert_eq!(c.trace_dropped, 256);
    assert_eq!(c.duration(Stage::LockHold).coverage.known, 1024);
    r.close(&mut w).unwrap();
    assert_eq!(r.active_workers(), 0);
    assert_eq!(r.aggregate().trace_dropped, 512);
}
#[test]
fn all_fixed_families_unknown_coverage_and_gauges() {
    let mut c = Collector::new(None).unwrap();
    for s in Stage::ALL {
        c.record_duration(s, None);
        assert_eq!(c.duration(s).coverage.unknown, 1)
    }
    c.record_quantity(Quantity::QueueBytes, Some(4096));
    c.record_quantity(Quantity::QueueBytes, None);
    assert_eq!(c.latest(Quantity::QueueBytes), None);
    assert_eq!(c.quantity(Quantity::QueueBytes).sum, 4096);
    c.record_reason(Reason::MissingCapability);
    assert_eq!(c.reason_count(Reason::MissingCapability), 1)
}
#[test]
fn h3_adapter_reads_actual_body_contract_without_fabricating_transition() {
    let source = metrics::FfiMetrics::new();
    {
        let mut call = source.begin_call(metrics::Operation::RuntimePing);
        call.bytes = metrics::ByteMeasurements::NO_BULK;
        call.fallback_reason = metrics::FallbackReason::None;
    }
    let a = sample_h3(&source, metrics::Operation::RuntimePing);
    assert_eq!(a.native.call_count, 1);
    assert_eq!(a.native.input_bytes.complete_total(), Some(0));
    assert_eq!(a.native.allocation_bytes.complete_total(), None);
    assert_eq!(a.native.elapsed_ns.known_samples, 1);
    assert_eq!(a.whole_java_call_ns, None);
    let b = sample_h3(&source, metrics::Operation::RuntimePing);
    assert_eq!(a.native.call_count, b.native.call_count)
}
#[test]
fn unwind_preserves_original_panic_and_counts_completion() {
    let mut r = Registry::new(1).unwrap();
    let mut w = r.attach(true, None).unwrap();
    let error = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        w.measure(Stage::ChunkGenerate, || panic!("original"))
    }))
    .unwrap_err();
    assert_eq!(error.downcast_ref::<&str>(), Some(&"original"));
    let c = w.collector().unwrap();
    assert_eq!(c.duration(Stage::ChunkGenerate).coverage.known, 1);
    assert_eq!(c.reason_count(Reason::CallbackException), 1)
}
#[test]
fn real_process_memory_does_not_infer_native_live_allocation() {
    let m = process_memory();
    assert_eq!(m.native_allocator_live_bytes, None);
    #[cfg(windows)]
    {
        assert!(m.working_set_bytes.unwrap() > 0);
        assert!(m.private_commit_bytes.unwrap() > 0);
    }
}

#[test]
fn point_gauges_and_cumulative_samples_are_not_event_totals() {
    let mut c = Collector::new(None).unwrap();
    for v in [1024, 2048] {
        c.record_quantity(Quantity::QueueBytes, Some(v));
    }
    for v in [4, 5] {
        c.record_quantity(Quantity::JavaGcCount, Some(v));
    }
    assert_eq!(c.latest(Quantity::QueueBytes), Some(2048));
    assert_eq!(c.latest(Quantity::JavaGcCount), Some(5));
    assert_eq!(c.event_total(Quantity::QueueBytes), None);
    assert_eq!(c.event_total(Quantity::JavaGcCount), None);
    c.record_quantity(Quantity::JniCopiedBytes, Some(32));
    c.record_quantity(Quantity::JniCopiedBytes, Some(64));
    assert_eq!(c.event_total(Quantity::JniCopiedBytes), Some(96));
}
