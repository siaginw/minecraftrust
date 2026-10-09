use allocator_experiment::{meter, workload::*};
use std::sync::{
    atomic::{AtomicUsize, Ordering},
    Arc,
};
#[global_allocator]
static ALLOCATOR: meter::Tracking = meter::Tracking;
#[test]
fn full_paths_match_exact_bytes_and_independent_decoders() {
    for seed in 0..5 {
        let f = fixture(seed);
        let drops = Arc::new(AtomicUsize::new(0));
        let a = produce(seed, &f, Scratch::Vec, true, drops.clone()).unwrap();
        let b = produce(seed, &f, Scratch::Arena, true, drops.clone()).unwrap();
        assert_eq!(a.packet, b.packet);
        assert_eq!(a.persistence, b.persistence);
        assert_eq!(a.digest(), b.digest());
        assert_eq!(inflate(&a.persistence).unwrap(), f.expected);
        assert_eq!(a.emitted_mask, 15);
        assert!(b.arena_reserved <= ARENA_LIMIT);
        drop((a, b));
        assert_eq!(drops.load(Ordering::SeqCst), 2)
    }
}
#[test]
fn delivery_and_arena_lifetimes_preserve_results() {
    let fixtures = Arc::new((0..5).map(fixture).collect());
    let mut expected = None;
    for scratch in [Scratch::Vec, Scratch::Arena] {
        for delivery in [Delivery::SameThread, Delivery::CrossThread] {
            let r = batch(Arc::clone(&fixtures), scratch, delivery, true).unwrap();
            assert_eq!(r.drops, 5);
            assert_eq!(r.publications, 5);
            if let Some(h) = expected {
                assert_eq!(r.digest, h)
            } else {
                expected = Some(r.digest)
            }
            assert_eq!(
                r.cross_thread_final_frees,
                if delivery == Delivery::CrossThread {
                    5
                } else {
                    0
                }
            );
        }
    }
}
#[test]
fn arena_capacity_error_preserves_existing_slice() {
    let arena = bumpalo::Bump::new();
    arena.set_allocation_limit(Some(4096));
    let retained = arena.try_alloc_slice_fill_copy(64, 7u8).unwrap();
    assert!(arena.try_alloc_slice_fill_copy(65536, 9u8).is_err());
    assert!(retained.iter().all(|b| *b == 7));
}
#[test]
fn bump_box_runs_owned_destructors_before_reset() {
    struct Owned {
        count: Arc<AtomicUsize>,
        _payload: Vec<u8>,
    }
    impl Drop for Owned {
        fn drop(&mut self) {
            self.count.fetch_add(1, Ordering::SeqCst);
        }
    }
    let count = Arc::new(AtomicUsize::new(0));
    let mut arena = bumpalo::Bump::new();
    {
        let v = bumpalo::boxed::Box::new_in(
            Owned {
                count: count.clone(),
                _payload: vec![4; 4096],
            },
            &arena,
        );
        assert_eq!(Arc::strong_count(&count), 2);
        drop(v);
    }
    assert_eq!(count.load(Ordering::SeqCst), 1);
    assert_eq!(Arc::strong_count(&count), 1);
    arena.reset();
    assert_eq!(count.load(Ordering::SeqCst), 1);
}
#[test]
fn plain_arena_drop_omission_is_explicit_negative_control() {
    struct Borrowed<'a>(&'a AtomicUsize);
    impl Drop for Borrowed<'_> {
        fn drop(&mut self) {
            self.0.fetch_add(1, Ordering::SeqCst);
        }
    }
    let count = AtomicUsize::new(0);
    let mut arena = bumpalo::Bump::new();
    arena.alloc(Borrowed(&count));
    arena.reset();
    assert_eq!(
        count.load(Ordering::SeqCst),
        0,
        "raw bump storage intentionally does not run Drop; never use it for owned resources"
    );
}
#[test]
fn producer_exit_before_last_consumer_free() {
    for seed in 0..16 {
        let count = Arc::new(AtomicUsize::new(0));
        let producer_count = count.clone();
        let owned = std::thread::spawn(move || {
            produce(seed, &fixture(seed), Scratch::Vec, true, producer_count).unwrap()
        })
        .join()
        .unwrap();
        let weak = Arc::downgrade(&owned);
        assert_eq!(count.load(Ordering::SeqCst), 0);
        assert!(owned.digest() != 0);
        drop(owned);
        assert!(weak.upgrade().is_none());
        assert_eq!(count.load(Ordering::SeqCst), 1);
    }
}
#[test]
fn retained_old_snapshots_are_immutable_and_not_arena_borrows() {
    let drops = Arc::new(AtomicUsize::new(0));
    let object = produce(2, &fixture(2), Scratch::Arena, true, drops.clone()).unwrap();
    let old = object.old_views[0].clone();
    let before = old.to_vec();
    drop(object);
    assert_eq!(drops.load(Ordering::SeqCst), 1);
    assert_eq!(&*old, &*before);
    assert!(old.iter().all(|id| id.0 >= BASE));
}
#[test]
fn failed_nbt_edit_drops_unpublished_state() {
    let count = Arc::new(AtomicUsize::new(0));
    let mut f = fixture(1);
    f.compressed.truncate(2);
    assert!(produce(1, &f, Scratch::Arena, true, count.clone()).is_err());
    assert_eq!(count.load(Ordering::SeqCst), 0);
    assert_eq!(Arc::strong_count(&count), 1);
}
#[test]
fn capacity_overflow_does_not_modify_existing_vector() {
    let mut v = vec![1u8, 2, 3];
    assert!(v.try_reserve_exact(usize::MAX).is_err());
    assert_eq!(v, [1, 2, 3]);
}
#[test]
fn alignment_zeroed_reallocation_and_quiescent_meter() {
    use std::alloc::{GlobalAlloc, Layout};
    let layout = Layout::from_size_align(4096, 4096).unwrap();
    let before = meter::snapshot();
    unsafe {
        let p = meter::Tracking.alloc_zeroed(layout);
        assert!(!p.is_null());
        assert_eq!(p as usize % 4096, 0);
        assert!(std::slice::from_raw_parts(p, 4096).iter().all(|b| *b == 0));
        std::ptr::write_bytes(p, 0x5a, 4096);
        let q = meter::Tracking.realloc(p, layout, 8192);
        assert!(!q.is_null());
        assert_eq!(q as usize % 4096, 0);
        assert!(std::slice::from_raw_parts(q, 4096)
            .iter()
            .all(|b| *b == 0x5a));
        meter::Tracking.dealloc(q, Layout::from_size_align(8192, 4096).unwrap());
    }
    let after = meter::snapshot();
    assert_eq!(after.live, before.live);
    assert_eq!(after.allocated - before.allocated, 12288);
    assert_eq!(after.freed - before.freed, 12288);
    assert_eq!(after.reallocations - before.reallocations, 1);
    assert!(!after.overflow);
}
#[test]
fn corruption_is_rejected_without_normalization() {
    let f = fixture(0);
    let mut bytes = f.raw.clone();
    bytes.pop();
    assert!(allocator_experiment::lossless::scan(&bytes).is_err());
    let s = allocator_experiment::lossless::scan(&f.raw).unwrap();
    assert_eq!(s.edit_offset, f.edit_offset);
    assert_eq!(s.data_version, Some(1343));
    assert_eq!(s.max_neid, Some(BASE + 1023));
    assert_eq!(
        allocator_experiment::lossless::edit(&f.raw, &s).unwrap(),
        f.expected
    );
}
#[test]
fn empty_or_oversize_batch_refused() {
    assert!(batch(Arc::new(vec![]), Scratch::Vec, Delivery::SameThread, false).is_err());
    assert!(batch(
        Arc::new((0..33).map(fixture).collect()),
        Scratch::Vec,
        Delivery::SameThread,
        false
    )
    .is_err());
}
