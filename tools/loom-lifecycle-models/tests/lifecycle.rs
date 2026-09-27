//! Abstract models of H4/H6 linearization obligations, not their implementation.
//! All synchronization that can affect an invariant uses Loom primitives.
//! The std counter outside each execution records coverage only.

use loom::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use loom::sync::{Arc, Mutex};
use loom::thread;
use std::sync::atomic::{AtomicUsize as Counter, Ordering as CounterOrder};
use std::sync::Arc as CounterRef;

fn check(name: &'static str, scenario: impl Fn() + Sync + Send + 'static) {
    let executions = CounterRef::new(Counter::new(0));
    let observed = executions.clone();
    let mut builder = loom::model::Builder::new();
    builder.max_threads = 3; // main + two workers
    builder.max_branches = 256;
    builder.preemption_bound = Some(3);
    // These fields must override ambient LOOM_* settings. A partial exploration
    // caused by a deadline/permutation cap must never report successful checking.
    builder.max_permutations = None;
    builder.max_duration = None;
    builder.checkpoint_file = None;
    builder.expect_explicit_explore = false;
    builder.location = false;
    builder.log = false;
    builder.check(move || {
        observed.fetch_add(1, CounterOrder::Relaxed);
        scenario();
    });
    println!(
        "MODEL_PASS name={name} executions={} threads=3 preemptions=3 branches=256",
        executions.load(CounterOrder::Relaxed)
    );
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Phase {
    Java,
    Draining,
    Native,
}

struct Handoff {
    phase: Phase,
    active_java_writer: bool,
    owner_generation: usize,
    data: usize,
    published: Option<usize>,
}

fn handoff(split_admission: bool, skip_drain: bool) {
    let state = Arc::new(Mutex::new(Handoff {
        phase: Phase::Java,
        active_java_writer: false,
        owner_generation: 1,
        data: 17,
        published: None,
    }));
    let worker_state = state.clone();
    let writer = thread::spawn(move || {
        let admitted = if split_admission {
            let eligible = worker_state.lock().unwrap().phase == Phase::Java;
            thread::yield_now();
            if eligible {
                let mut state = worker_state.lock().unwrap();
                state.active_java_writer = true;
                // Deliberate check/use gap: owner may already have changed.
                assert_eq!(state.phase, Phase::Java, "ADMISSION_AFTER_FREEZE");
            }
            eligible
        } else {
            let mut state = worker_state.lock().unwrap();
            if state.phase == Phase::Java {
                state.active_java_writer = true;
                true
            } else {
                false
            }
        };
        if admitted {
            thread::yield_now();
            let mut state = worker_state.lock().unwrap();
            assert_ne!(state.phase, Phase::Native, "JAVA_NATIVE_WRITERS_OVERLAP");
            // A writer admitted before freeze may finish while draining even
            // though ownership_generation has changed. It cannot be readopted.
            state.data = 23;
            state.active_java_writer = false;
        }
    });
    let transfer_state = state.clone();
    let transfer = thread::spawn(move || {
        let mut state = transfer_state.lock().unwrap();
        state.phase = Phase::Draining;
        state.owner_generation += 1;
        if !state.active_java_writer || skip_drain {
            // Stage + owner/data publish are one serialized transaction here.
            state.published = Some(state.data);
            state.phase = Phase::Native;
        }
    });
    writer.join().unwrap();
    transfer.join().unwrap();
    let mut state = state.lock().unwrap();
    // A failed/busy publication is retried only after actual writer drainage.
    if state.phase == Phase::Draining {
        assert!(!state.active_java_writer);
        state.published = Some(state.data);
        state.phase = Phase::Native;
    }
    assert!(!state.active_java_writer, "JAVA_NATIVE_WRITERS_OVERLAP");
    assert_eq!(state.owner_generation, 2);
    assert_eq!(
        state.published,
        Some(state.data),
        "PARTIAL_OWNER_PUBLICATION"
    );
}

#[test]
fn handoff_admission_drain() {
    check("handoff_admission_drain", || handoff(false, false));
}
#[test]
#[ignore = "negative control; runner requires ADMISSION_AFTER_FREEZE failure"]
fn broken_handoff_admission_drain() {
    check("broken_handoff_admission_drain", || handoff(true, false));
}

#[test]
#[ignore = "negative control; runner requires JAVA_NATIVE_WRITERS_OVERLAP failure"]
fn broken_handoff_skips_drain() {
    check("broken_handoff_skips_drain", || handoff(false, true));
}

fn owner_candidate_publication(split_publication: bool) {
    let state = Arc::new(Mutex::new((Phase::Draining, 17)));
    let publisher_state = state.clone();
    let publisher = thread::spawn(move || {
        if split_publication {
            publisher_state.lock().unwrap().0 = Phase::Native;
            thread::yield_now();
            publisher_state.lock().unwrap().1 = 23;
        } else {
            *publisher_state.lock().unwrap() = (Phase::Native, 23);
        }
    });
    let reader_state = state.clone();
    let reader = thread::spawn(move || {
        let state = reader_state.lock().unwrap();
        if state.0 == Phase::Native {
            assert_eq!(state.1, 23, "PARTIAL_OWNER_PUBLICATION");
        }
    });
    publisher.join().unwrap();
    reader.join().unwrap();
    assert_eq!(*state.lock().unwrap(), (Phase::Native, 23));
}
#[test]
fn owner_and_candidate_publication() {
    check("owner_and_candidate_publication", || {
        owner_candidate_publication(false)
    });
}
#[test]
#[ignore = "negative control; runner requires PARTIAL_OWNER_PUBLICATION failure"]
fn broken_owner_and_candidate_publication() {
    check("broken_owner_and_candidate_publication", || {
        owner_candidate_publication(true)
    });
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
struct Tag {
    slot_generation: usize,
    owner_generation: usize,
    registry_epoch: usize,
    state_generation: usize,
}

struct Adoption {
    tag: Tag,
    pending: bool,
    revoked: bool,
    artifact: Option<Tag>,
}
impl Adoption {
    fn admits(&self, ticket: Tag) -> bool {
        self.pending && !self.revoked && self.tag == ticket
    }
    fn publish(&mut self, ticket: Tag) {
        assert!(self.admits(ticket), "STALE_RESULT_COMMITTED");
        self.pending = false;
        self.artifact = Some(ticket);
    }
}

#[derive(Clone, Copy)]
enum Invalidation {
    Revoke,
    Cancel,
    StateMutation,
    RegistryChange,
    OwnershipChange,
    UnloadReload,
}

fn adoption(event: Invalidation, split_commit: bool) {
    let ticket = Tag {
        slot_generation: 1,
        owner_generation: 1,
        registry_epoch: 1,
        state_generation: 1,
    };
    let state = Arc::new(Mutex::new(Adoption {
        tag: ticket,
        pending: true,
        revoked: false,
        artifact: None,
    }));
    let worker_state = state.clone();
    let worker = thread::spawn(move || {
        // Abstract an immutable completed result, including an async IO result.
        // Its computation is irrelevant to the final adoption linearization.
        if split_commit {
            let valid = worker_state.lock().unwrap().admits(ticket);
            thread::yield_now();
            if valid {
                // Deliberately use the earlier decision after releasing the
                // lock. The assertion is the independent commit-time oracle.
                worker_state.lock().unwrap().publish(ticket);
            }
        } else {
            let mut state = worker_state.lock().unwrap();
            if state.admits(ticket) {
                state.publish(ticket);
            }
        }
    });
    let invalidation_state = state.clone();
    let invalidator = thread::spawn(move || {
        let mut state = invalidation_state.lock().unwrap();
        match event {
            Invalidation::Revoke => state.revoked = true,
            Invalidation::Cancel => state.pending = false,
            Invalidation::StateMutation => state.tag.state_generation += 1,
            Invalidation::RegistryChange => state.tag.registry_epoch += 1,
            Invalidation::OwnershipChange => state.tag.owner_generation += 1,
            Invalidation::UnloadReload => {
                state.tag.slot_generation += 1;
                state.pending = false;
            }
        }
        // Cancellation is only effective before the result is consumed. A
        // completed artifact remains valid; every identity invalidation clears it.
        if !matches!(event, Invalidation::Cancel) {
            state.artifact = None;
        }
    });
    worker.join().unwrap();
    invalidator.join().unwrap();
    let state = state.lock().unwrap();
    if let Some(tag) = state.artifact {
        assert_eq!(tag, state.tag, "STALE_CACHE_REMAINED");
        assert!(!state.revoked, "REVOKED_CACHE_REMAINED");
    }
}

macro_rules! adoption_cases {
    ($($good:ident, $bad:ident, $event:ident;)*) => {$ (
        #[test]
        fn $good() { check(stringify!($good), || adoption(Invalidation::$event, false)); }
        #[test]
        #[ignore = "negative control; runner requires STALE_RESULT_COMMITTED failure"]
        fn $bad() { check(stringify!($bad), || adoption(Invalidation::$event, true)); }
    )*};
}
adoption_cases! {
    worker_revocation, broken_worker_revocation, Revoke;
    worker_cancellation, broken_worker_cancellation, Cancel;
    worker_state_generation, broken_worker_state_generation, StateMutation;
    worker_registry_epoch, broken_worker_registry_epoch, RegistryChange;
    worker_ownership_generation, broken_worker_ownership_generation, OwnershipChange;
    async_io_unload_adoption, broken_async_io_unload_adoption, UnloadReload;
}

#[derive(Clone, Copy, PartialEq, Eq)]
struct WorkId {
    store: usize,
    nonce: usize,
}

fn origin_bound_cancellation(ignore_origin: bool) {
    let local = WorkId {
        store: 17,
        nonce: 1,
    };
    let foreign = WorkId {
        store: 23,
        nonce: 1,
    };
    let state = Arc::new(Mutex::new((true, false))); // pending, committed
    let cancel_state = state.clone();
    let canceller = thread::spawn(move || {
        let mut state = cancel_state.lock().unwrap();
        if foreign == local || (ignore_origin && foreign.nonce == local.nonce) {
            state.0 = false;
        }
    });
    let worker_state = state.clone();
    let worker = thread::spawn(move || {
        let mut state = worker_state.lock().unwrap();
        if state.0 {
            state.0 = false;
            state.1 = true;
        }
    });
    canceller.join().unwrap();
    worker.join().unwrap();
    assert!(state.lock().unwrap().1, "FOREIGN_WORK_CANCELLED_LOCAL_JOB");
}
#[test]
fn cancellation_checks_origin() {
    check("cancellation_checks_origin", || {
        origin_bound_cancellation(false)
    });
}
#[test]
#[ignore = "negative control; runner requires FOREIGN_WORK_CANCELLED_LOCAL_JOB failure"]
fn broken_cancellation_checks_origin() {
    check("broken_cancellation_checks_origin", || {
        origin_bound_cancellation(true)
    });
}

struct Slot {
    generation: usize,
    payload: Option<usize>,
    retired: bool,
}

fn slot_reuse(ignore_generation: bool) {
    let slot = Arc::new(Mutex::new(Slot {
        generation: 1,
        payload: Some(17),
        retired: false,
    }));
    let reader_slot = slot.clone();
    let reader = thread::spawn(move || {
        let state = reader_slot.lock().unwrap();
        let found = if !state.retired && (ignore_generation || state.generation == 1) {
            state.payload
        } else {
            None
        };
        if let Some(payload) = found {
            assert_eq!(payload, 17, "STALE_HANDLE_ALIASED_NEW_SLOT");
        }
    });
    let unload_slot = slot.clone();
    let unloader = thread::spawn(move || {
        let mut state = unload_slot.lock().unwrap();
        state.payload = None;
        state.generation = 2;
        state.payload = Some(23);
    });
    reader.join().unwrap();
    unloader.join().unwrap();
}

#[test]
fn slot_reuse_generation() {
    check("slot_reuse_generation", || slot_reuse(false));
}
#[test]
#[ignore = "negative control; runner requires STALE_HANDLE_ALIASED_NEW_SLOT failure"]
fn broken_slot_reuse_generation() {
    check("broken_slot_reuse_generation", || slot_reuse(true));
}

fn exhaustion(wrap: bool) {
    let slot = Arc::new(Mutex::new(Slot {
        generation: 2,
        payload: Some(23),
        retired: false,
    }));
    let reader_slot = slot.clone();
    let reader = thread::spawn(move || {
        let state = reader_slot.lock().unwrap();
        // An old generation-one handle must never resolve after exhaustion.
        assert!(
            state.retired || state.generation != 1 || state.payload.is_none(),
            "GENERATION_WRAP_ABA"
        );
    });
    let unload_slot = slot.clone();
    let unloader = thread::spawn(move || {
        let mut state = unload_slot.lock().unwrap();
        state.payload = None;
        if wrap {
            state.generation = 1;
            state.payload = Some(31);
        } else {
            state.retired = true;
        }
    });
    reader.join().unwrap();
    unloader.join().unwrap();
}
#[test]
fn exhausted_generation_retires() {
    check("exhausted_generation_retires", || exhaustion(false));
}
#[test]
#[ignore = "negative control; runner requires GENERATION_WRAP_ABA failure"]
fn broken_exhausted_generation_retires() {
    check("broken_exhausted_generation_retires", || exhaustion(true));
}

fn ready_queue(relaxed: bool) {
    let payload = Arc::new(AtomicUsize::new(0));
    let ready = Arc::new(AtomicBool::new(false));
    let (producer_payload, producer_ready) = (payload.clone(), ready.clone());
    let producer = thread::spawn(move || {
        producer_payload.store(17, Ordering::Relaxed);
        producer_ready.store(
            true,
            if relaxed {
                Ordering::Relaxed
            } else {
                Ordering::Release
            },
        );
    });
    let consumer = thread::spawn(move || {
        if ready.load(if relaxed {
            Ordering::Relaxed
        } else {
            Ordering::Acquire
        }) {
            assert_eq!(payload.load(Ordering::Relaxed), 17, "READY_WITHOUT_PAYLOAD");
        }
    });
    producer.join().unwrap();
    consumer.join().unwrap();
}
#[test]
fn ready_queue_publication() {
    check("ready_queue_publication", || ready_queue(false));
}
#[test]
#[ignore = "negative control; runner requires READY_WITHOUT_PAYLOAD failure"]
fn broken_ready_queue_publication() {
    check("broken_ready_queue_publication", || ready_queue(true));
}

struct Snapshot {
    value: usize,
    reclaimed: Arc<AtomicBool>,
    drops: Arc<AtomicUsize>,
}
impl Drop for Snapshot {
    fn drop(&mut self) {
        self.reclaimed.store(true, Ordering::SeqCst);
        self.drops.fetch_add(1, Ordering::SeqCst);
    }
}
fn snapshot_reclamation(early_reclaim: bool) {
    let reclaimed = Arc::new(AtomicBool::new(false));
    let drops = Arc::new(AtomicUsize::new(0));
    let slot = Arc::new(Mutex::new(Some(Arc::new(Snapshot {
        value: 17,
        reclaimed: reclaimed.clone(),
        drops: drops.clone(),
    }))));
    let reader_slot = slot.clone();
    let reader = thread::spawn(move || {
        let retained = reader_slot.lock().unwrap().clone();
        if let Some(view) = retained {
            thread::yield_now();
            assert!(
                !view.reclaimed.load(Ordering::SeqCst),
                "RECLAIMED_LIVE_SNAPSHOT"
            );
            assert_eq!(view.value, 17);
        }
    });
    let unload_slot = slot.clone();
    let unloader = thread::spawn(move || {
        let old = unload_slot.lock().unwrap().take();
        if early_reclaim {
            // Safe sentinel for a hypothetical free-on-unload policy. No raw
            // pointer/UAF is executed: the model checks its lifetime obligation.
            if let Some(view) = &old {
                view.reclaimed.store(true, Ordering::SeqCst);
            }
        }
        drop(old);
    });
    reader.join().unwrap();
    unloader.join().unwrap();
    assert!(slot.lock().unwrap().is_none());
    assert!(reclaimed.load(Ordering::SeqCst));
    assert_eq!(drops.load(Ordering::SeqCst), 1);
}
#[test]
fn immutable_snapshot_reclamation() {
    check("immutable_snapshot_reclamation", || {
        snapshot_reclamation(false)
    });
}
#[test]
#[ignore = "negative control; runner requires RECLAIMED_LIVE_SNAPSHOT failure"]
fn broken_immutable_snapshot_reclamation() {
    check("broken_immutable_snapshot_reclamation", || {
        snapshot_reclamation(true)
    });
}
