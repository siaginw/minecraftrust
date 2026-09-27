use super::*;

#[test]
fn foreign_cancellation_is_rejected_before_local_lookup() {
    let mut origin = scheduler();
    let mut local = scheduler();
    let foreign = submit(&mut origin, 0, 1);
    let local_id = submit(&mut local, 0, 1);
    assert_eq!(local.cancel(foreign), Err(Error::WrongScheduler));
    complete(&mut local, local_id);
    assert_eq!(local.drain_one().unwrap().outcome, Outcome::Committed);
    assert_eq!(local.values(), [3, 2, 3, 4]);
    assert!(origin.drain_one().is_none());
}

#[test]
fn foreign_dispatch_is_rejected_before_local_lookup() {
    let mut origin = scheduler();
    let mut local = scheduler();
    let foreign = submit(&mut origin, 0, 1);
    let local_id = submit(&mut local, 0, 1);
    assert!(matches!(local.dispatch(foreign), Err(Error::WrongScheduler)));
    complete(&mut local, local_id);
    assert_eq!(local.drain_one().unwrap().outcome, Outcome::Committed);
    assert_eq!(local.values(), [3, 2, 3, 4]);
    assert!(origin.drain_one().is_none());
}

fn scheduler() -> Scheduler {
    Scheduler::new(
        Scope {
            session: 1,
            world: 2,
            incarnation: 3,
            registry_epoch: 4,
        },
        vec![1, 2, 3, 4],
        Limits::default(),
    )
    .unwrap()
}
fn transform(start: usize, count: usize) -> Transform {
    Transform {
        start,
        count,
        multiply: 2,
        add: 1,
    }
}
fn submit(s: &mut Scheduler, start: usize, count: usize) -> u64 {
    s.submit(
        &s.qualify_offline().unwrap(),
        transform(start, count),
        Budget::bounded_default(),
    )
    .unwrap()
}
fn complete(s: &mut Scheduler, seq: u64) {
    let c = s.dispatch(seq).unwrap().run();
    s.accept(c).unwrap();
}
fn skipped(s: &mut Scheduler, seq: u64, reason: Reason, before: &[i64]) {
    let d = s.drain_one().unwrap();
    assert_eq!(
        d,
        Decision {
            sequence: seq,
            outcome: Outcome::Skipped(reason),
            tick: s.tick(),
            effects: vec![]
        }
    );
    assert_eq!(s.values(), before);
}

// Independent serial specification: wide arithmetic, snapshots + versions,
// full plan before mutation. It never calls the worker or validator.
struct Oracle {
    values: Vec<i64>,
    versions: Vec<u64>,
}
impl Oracle {
    fn apply(&mut self, seq: u64, t: Transform, snapshot: &[(i64, u64)]) -> Decision {
        let mut effects = Vec::new();
        let stale = snapshot
            .iter()
            .enumerate()
            .any(|(offset, (value, version))| {
                self.values[t.start + offset] != *value
                    || self.versions[t.start + offset] != *version
            });
        let outcome = if stale {
            Outcome::Skipped(Reason::StaleInput)
        } else {
            for (offset, (value, _)) in snapshot.iter().enumerate() {
                let wide = *value as i128 * t.multiply as i128 + t.add as i128;
                assert!((i64::MIN as i128..=i64::MAX as i128).contains(&wide));
                effects.push(Effect {
                    index: t.start + offset,
                    before: *value,
                    after: wide as i64,
                });
            }
            for e in &effects {
                self.values[e.index] = e.after;
                self.versions[e.index] += 1;
            }
            Outcome::Committed
        };
        Decision {
            sequence: seq,
            outcome,
            tick: 0,
            effects,
        }
    }
}

#[test]
fn every_completion_permutation_matches_serial_oracle_at_every_boundary() {
    for a in 0..4 {
        for b in 0..4 {
            for c in 0..4 {
                for d in 0..4 {
                    let order = [a, b, c, d];
                    if order
                        .iter()
                        .enumerate()
                        .any(|(i, x)| order[..i].contains(x))
                    {
                        continue;
                    }
                    for overlap in [false, true] {
                        let mut s = scheduler();
                        let mut oracle = Oracle {
                            values: vec![1, 2, 3, 4],
                            versions: vec![1; 4],
                        };
                        let plans: Vec<_> = (0..4)
                            .map(|i| transform(if overlap { i % 2 } else { i }, 1))
                            .collect();
                        let snapshots: Vec<_> = plans
                            .iter()
                            .map(|t| vec![(oracle.values[t.start], 1)])
                            .collect();
                        let q = s.qualify_offline().unwrap();
                        let ids: Vec<_> = plans
                            .iter()
                            .map(|t| s.submit(&q, *t, Budget::bounded_default()).unwrap())
                            .collect();
                        let mut tickets: Vec<_> = ids
                            .iter()
                            .map(|id| Some(s.dispatch(*id).unwrap()))
                            .collect();
                        for index in order {
                            s.accept(tickets[index].take().unwrap().run()).unwrap();
                        }
                        for index in 0..4 {
                            assert_eq!(
                                s.drain_one().unwrap(),
                                oracle.apply(ids[index], plans[index], &snapshots[index])
                            );
                            assert_eq!(s.values(), oracle.values);
                        }
                        assert!(s.drain_one().is_none());
                        assert_eq!(s.usage().reserved_bytes, 0);
                    }
                }
            }
        }
    }
}

#[test]
fn ready_later_work_cannot_bypass_head() {
    let mut s = scheduler();
    let a = submit(&mut s, 0, 1);
    let b = submit(&mut s, 1, 1);
    complete(&mut s, b);
    assert!(s.drain_one().is_none());
    assert_eq!(s.values(), vec![1, 2, 3, 4]);
    complete(&mut s, a);
    assert_eq!(s.drain_one().unwrap().sequence, a);
    assert_eq!(s.drain_one().unwrap().sequence, b);
}

#[test]
fn cancellation_tombstone_keeps_capacity_until_worker_quiescence() {
    let mut s = scheduler();
    s.limits.max_outstanding = 2;
    let a = submit(&mut s, 0, 1);
    let b = submit(&mut s, 1, 1);
    let held = s.dispatch(a).unwrap();
    complete(&mut s, b);
    let reserved = s.usage().reserved_bytes;
    s.cancel(a).unwrap();
    skipped(&mut s, a, Reason::Cancelled, &[1, 2, 3, 4]);
    assert_eq!(s.usage().retired, 1);
    assert_eq!(s.usage().reserved_bytes, reserved);
    assert_eq!(
        s.submit(
            &s.qualify_offline().unwrap(),
            transform(2, 1),
            Budget::bounded_default()
        ),
        Err(Error::Capacity)
    );
    assert_eq!(s.drain_one().unwrap().outcome, Outcome::Committed);
    let c = submit(&mut s, 2, 1);
    assert_eq!(s.usage().outstanding, 2);
    assert_eq!(
        s.submit(
            &s.qualify_offline().unwrap(),
            transform(3, 1),
            Budget::bounded_default()
        ),
        Err(Error::Capacity)
    );
    let late = held.run();
    assert_eq!(s.accept(late), Err(Error::UnknownSequence));
    s.reap();
    assert_eq!(s.usage().retired, 0);
    complete(&mut s, c);
    assert_eq!(s.drain_one().unwrap().outcome, Outcome::Committed);
    assert_eq!(s.usage().reserved_bytes, 0);
}

#[test]
fn dropped_ticket_and_dropped_completion_are_abandoned() {
    for run in [false, true] {
        let mut s = scheduler();
        let a = submit(&mut s, 0, 2);
        let ticket = s.dispatch(a).unwrap();
        if run {
            drop(ticket.run())
        } else {
            drop(ticket)
        }
        skipped(&mut s, a, Reason::Abandoned, &[1, 2, 3, 4]);
        assert_eq!(s.usage().outstanding, 0);
    }
}

#[test]
fn held_expired_head_remains_charged_and_dispatched() {
    let mut s = scheduler();
    s.limits.max_dispatched = 1;
    let a = submit(&mut s, 0, 1);
    let held = s.dispatch(a).unwrap();
    let b = submit(&mut s, 1, 1);
    s.advance_clock(1000).unwrap();
    skipped(&mut s, a, Reason::Deadline, &[1, 2, 3, 4]);
    assert!(matches!(s.dispatch(b), Err(Error::DispatchCapacity)));
    assert_eq!(s.usage().dispatched, 1);
    drop(held);
    s.reap();
    assert_eq!(s.usage().dispatched, 0);
    skipped(&mut s, b, Reason::Deadline, &[1, 2, 3, 4]);
}

#[test]
fn intake_and_memory_limits_do_not_consume_sequence() {
    let mut s = scheduler();
    let q = s.qualify_offline().unwrap();
    let mut budget = Budget::bounded_default();
    budget.max_payload_bytes = 1;
    assert_eq!(
        s.submit(&q, transform(0, 1), budget),
        Err(Error::MemoryBudget)
    );
    s.limits.max_reserved_bytes = Scheduler::reservation_for(1).unwrap();
    let a = submit(&mut s, 0, 1);
    assert_eq!(a, 0);
    assert_eq!(
        s.submit(&q, transform(1, 1), Budget::bounded_default()),
        Err(Error::MemoryBudget)
    );
    complete(&mut s, a);
    s.drain_one().unwrap();
    assert_eq!(submit(&mut s, 0, 1), 1);
}

#[test]
fn stable_sequence_never_wraps_or_reuses() {
    let mut s = scheduler();
    s.next_sequence = Some(u64::MAX);
    let a = submit(&mut s, 0, 1);
    assert_eq!(a, u64::MAX);
    complete(&mut s, a);
    assert_eq!(s.drain_one().unwrap().sequence, u64::MAX);
    assert_eq!(
        s.submit(
            &s.qualify_offline().unwrap(),
            transform(0, 1),
            Budget::bounded_default()
        ),
        Err(Error::SequenceExhausted)
    );
}

#[test]
fn qualification_is_owner_scope_and_generation_bound() {
    let mut s = scheduler();
    let other = scheduler();
    let q = other.qualify_offline().unwrap();
    assert_eq!(
        s.submit(&q, transform(0, 1), Budget::bounded_default()),
        Err(Error::InvalidQualification)
    );
    let old = s.qualify_offline().unwrap();
    let a = submit(&mut s, 0, 1);
    s.revoke().unwrap();
    skipped(&mut s, a, Reason::Revoked, &[1, 2, 3, 4]);
    assert_eq!(
        s.submit(&old, transform(0, 1), Budget::bounded_default()),
        Err(Error::InvalidQualification)
    );
    let a = submit(&mut s, 0, 1);
    complete(&mut s, a);
    assert_eq!(s.drain_one().unwrap().outcome, Outcome::Committed);
}

#[test]
fn unload_reload_fences_old_incarnation_without_dropping_leases() {
    let mut s = scheduler();
    let old = s.qualify_offline().unwrap();
    let a = submit(&mut s, 0, 1);
    let held = s.dispatch(a).unwrap();
    s.unload();
    s.reload_offline().unwrap();
    assert_eq!(s.scope.incarnation, 4);
    skipped(&mut s, a, Reason::Unloaded, &[1, 2, 3, 4]);
    assert_eq!(s.usage().retired, 1);
    assert_eq!(
        s.submit(&old, transform(0, 1), Budget::bounded_default()),
        Err(Error::InvalidQualification)
    );
    drop(held);
    s.reap();
    let a = submit(&mut s, 0, 1);
    complete(&mut s, a);
    assert_eq!(s.drain_one().unwrap().outcome, Outcome::Committed);
}

#[test]
fn exhausted_generation_and_incarnation_fail_closed() {
    let mut s = scheduler();
    s.generation = u64::MAX;
    let a = submit(&mut s, 0, 1);
    let held = s.dispatch(a).unwrap();
    assert_eq!(s.revoke(), Err(Error::CounterExhausted));
    assert!(held.task.cancelled.load(Ordering::Acquire));
    skipped(&mut s, a, Reason::Revoked, &[1, 2, 3, 4]);
    assert!(matches!(s.qualify_offline(), Err(Error::Unloaded)));
    assert_eq!(s.reload_offline(), Err(Error::CounterExhausted));
    let mut s = scheduler();
    s.scope.incarnation = u64::MAX;
    s.unload();
    assert_eq!(s.reload_offline(), Err(Error::CounterExhausted));
    assert!(!s.loaded);
}

#[test]
fn stale_read_set_rejects_whole_multi_cell_commit() {
    let mut s = scheduler();
    let a = submit(&mut s, 0, 4);
    complete(&mut s, a);
    s.external_write(3, 44).unwrap();
    skipped(&mut s, a, Reason::StaleInput, &[1, 2, 3, 44]);
    let b = submit(&mut s, 0, 4);
    complete(&mut s, b);
    assert_eq!(s.drain_one().unwrap().outcome, Outcome::Committed);
    assert_eq!(s.values(), [3, 5, 7, 89]);
}

#[test]
fn same_value_external_write_still_invalidates_snapshot() {
    let mut s = scheduler();
    let a = submit(&mut s, 0, 1);
    complete(&mut s, a);
    s.external_write(0, 1).unwrap();
    skipped(&mut s, a, Reason::StaleInput, &[1, 2, 3, 4]);
}

#[test]
fn malformed_outputs_are_rejected_without_partial_commit() {
    for fault in 0..7 {
        let mut s = scheduler();
        let a = submit(&mut s, 0, 4);
        let mut c = s.dispatch(a).unwrap().run();
        match fault {
            0 => {
                c.result.as_mut().unwrap().pop();
            }
            1 => c.result.as_mut().unwrap()[3].index = 0,
            2 => c.result.as_mut().unwrap()[3].value = 999,
            3 => c.result.as_mut().unwrap().swap(0, 1),
            4 => c.steps = 0,
            5 => c.steps = 513,
            _ => c
                .result
                .as_mut()
                .unwrap()
                .push(Write { index: 0, value: 3 }),
        }
        s.accept(c).unwrap();
        skipped(&mut s, a, Reason::InvalidResult, &[1, 2, 3, 4]);
    }
}

#[test]
fn arithmetic_failure_cpu_failure_and_panic_are_atomic_then_retry() {
    for reason in [Reason::Arithmetic, Reason::CpuBudget, Reason::WorkerPanic] {
        let mut s = scheduler();
        let mut b = Budget::bounded_default();
        let mut t = transform(0, 4);
        if reason == Reason::Arithmetic {
            t.multiply = i64::MAX / 2;
        }
        if reason == Reason::CpuBudget {
            b.max_steps = 3;
        }
        let a = s.submit(&s.qualify_offline().unwrap(), t, b).unwrap();
        let c = s
            .dispatch(a)
            .unwrap()
            .run_inner(reason == Reason::WorkerPanic);
        s.accept(c).unwrap();
        skipped(&mut s, a, reason, &[1, 2, 3, 4]);
        let b = submit(&mut s, 0, 1);
        complete(&mut s, b);
        assert_eq!(s.drain_one().unwrap().outcome, Outcome::Committed);
    }
}

#[test]
fn deadlines_apply_before_work_and_before_commit() {
    for ready in [false, true] {
        let mut s = scheduler();
        let a = submit(&mut s, 0, 4);
        if ready {
            complete(&mut s, a)
        }
        s.advance_clock(1000).unwrap();
        if !ready {
            complete(&mut s, a)
        }
        skipped(&mut s, a, Reason::Deadline, &[1, 2, 3, 4]);
    }
}

#[test]
fn wall_deadline_is_cooperative_and_deterministically_testable() {
    let mut s = scheduler();
    let a = submit(&mut s, 0, 1);
    Arc::get_mut(&mut s.pending[0].task).unwrap().admitted =
        Instant::now() - Duration::from_secs(11);
    complete(&mut s, a);
    skipped(&mut s, a, Reason::Deadline, &[1, 2, 3, 4]);
}

#[test]
fn first_terminal_controller_event_is_sticky() {
    for first in [Reason::Cancelled, Reason::Unloaded, Reason::Revoked] {
        let mut s = scheduler();
        let a = submit(&mut s, 0, 1);
        complete(&mut s, a);
        match first {
            Reason::Cancelled => s.cancel(a).unwrap(),
            Reason::Unloaded => s.unload(),
            _ => s.revoke().unwrap(),
        }
        s.cancel(a).unwrap();
        s.unload();
        s.revoke().unwrap();
        s.advance_clock(1000).unwrap();
        skipped(&mut s, a, first, &[1, 2, 3, 4]);
    }
}

#[test]
fn combined_invalid_admission_and_commit_precedence_is_explicit() {
    let mut s = scheduler();
    let foreign = scheduler().qualify_offline().unwrap();
    let invalid = Transform {
        start: usize::MAX,
        count: 4,
        multiply: 1,
        add: 0,
    };
    let mut b = Budget::bounded_default();
    b.max_steps = 0;
    assert_eq!(
        s.submit(&foreign, invalid, b),
        Err(Error::InvalidQualification)
    );
    let q = s.qualify_offline().unwrap();
    assert_eq!(s.submit(&q, invalid, b), Err(Error::InvalidRange));
    assert_eq!(s.submit(&q, transform(0, 1), b), Err(Error::InvalidBudget));
    s.unload();
    assert_eq!(s.submit(&foreign, invalid, b), Err(Error::Unloaded));
    for malformed_shape in [false, true] {
        let mut s = scheduler();
        let a = submit(&mut s, 0, 2);
        let mut c = s.dispatch(a).unwrap().run();
        c.result.as_mut().unwrap()[0].value = 999;
        if malformed_shape {
            c.steps = 0;
        }
        s.external_write(1, 20).unwrap();
        s.accept(c).unwrap();
        skipped(
            &mut s,
            a,
            if malformed_shape {
                Reason::InvalidResult
            } else {
                Reason::StaleInput
            },
            &[1, 20, 3, 4],
        );
    }
}

#[test]
fn foreign_completion_cannot_rebind_equal_sequence_numbers() {
    let mut a = scheduler();
    let mut b = scheduler();
    let seq = submit(&mut a, 0, 1);
    let _ = submit(&mut b, 0, 1);
    let c = a.dispatch(seq).unwrap().run();
    assert_eq!(b.accept(c), Err(Error::WrongScheduler));
    skipped(&mut a, seq, Reason::Abandoned, &[1, 2, 3, 4]);
    assert!(b.drain_one().is_none());
}

#[test]
fn cell_version_exhaustion_is_atomic_and_never_wraps() {
    let mut s = scheduler();
    s.cells[3].version = u64::MAX;
    let a = submit(&mut s, 0, 4);
    complete(&mut s, a);
    skipped(&mut s, a, Reason::CellVersionExhausted, &[1, 2, 3, 4]);
    assert_eq!(s.external_write(3, 99), Err(Error::CounterExhausted));
    assert_eq!(s.values(), [1, 2, 3, 4]);
}

#[test]
fn clock_never_wraps_or_goes_backwards() {
    let mut s = scheduler();
    s.advance_clock(u64::MAX).unwrap();
    assert_eq!(s.advance_clock(0), Err(Error::ClockWentBackwards));
    assert_eq!(
        s.submit(
            &s.qualify_offline().unwrap(),
            transform(0, 1),
            Budget::bounded_default()
        ),
        Err(Error::InvalidBudget)
    );
    assert_eq!(s.tick(), u64::MAX);
}

#[test]
fn actual_scoped_workers_match_serial_oracle_at_every_commit() {
    for workers in [1, 2, 4] {
        for _ in 0..16 {
            let mut s = scheduler();
            let mut oracle = Oracle {
                values: vec![1, 2, 3, 4],
                versions: vec![1; 4],
            };
            let mut tickets = Vec::new();
            for i in 0..4 {
                let a = submit(&mut s, i, 1);
                tickets.push(s.dispatch(a).unwrap());
            }
            tickets.reverse();
            for c in execute_scoped(tickets, workers).unwrap() {
                s.accept(c).unwrap();
            }
            for i in 0..4 {
                assert_eq!(
                    s.drain_one().unwrap(),
                    oracle.apply(i as u64, transform(i, 1), &[(i as i64 + 1, 1)])
                );
                assert_eq!(s.values(), oracle.values);
            }
            assert_eq!(s.usage().outstanding, 0);
        }
    }
}

#[test]
fn scheduler_drop_requests_cooperative_cancellation() {
    let mut s = scheduler();
    let a = submit(&mut s, 0, 1);
    let ticket = s.dispatch(a).unwrap();
    drop(s);
    assert!(ticket.task.cancelled.load(Ordering::Acquire));
    assert_eq!(ticket.run().result.unwrap_err(), Reason::Cancelled);
}

#[test]
fn public_bounds_and_authority_are_fixed() {
    assert!(!production_authority_enabled());
    let mut oversized = Vec::with_capacity(100_000);
    oversized.extend([1, 2, 3, 4]);
    let bounded = Scheduler::new(scheduler().scope, oversized, Limits::default()).unwrap();
    assert_eq!(bounded.cells.capacity(), 4);
    assert!(matches!(
        execute_scoped(vec![], 0),
        Err(Error::InvalidConfiguration)
    ));
    let mut s = scheduler();
    let q = s.qualify_offline().unwrap();
    assert_eq!(
        s.submit(&q, transform(0, 257), Budget::bounded_default()),
        Err(Error::InvalidRange)
    );
    let a = submit(&mut s, 0, 1);
    let held = s.dispatch(a).unwrap();
    assert!(matches!(s.dispatch(a), Err(Error::AlreadyDispatched)));
    s.cancel(a).unwrap();
    assert_eq!(s.accept(held.run()), Err(Error::Terminal));
    skipped(&mut s, a, Reason::Cancelled, &[1, 2, 3, 4]);
}
