//! Bounded, offline-only model admission. No runtime certificate grants exist.
use std::sync::atomic::{AtomicU64, Ordering};

pub const CELLS: usize = 16;
pub const MAX_BATCH: usize = 32;
static NEXT_WORLD: AtomicU64 = AtomicU64::new(1);

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Error {
    Cell,
    Range,
    Count,
    Unqualified,
    Stale,
    WrongWorld,
    Snapshot,
    Order,
    Conflict,
    InvalidDiff,
    GenerationExhausted,
}

impl Error {
    pub fn code(self) -> &'static str {
        match self {
            Self::Cell => "CELL",
            Self::Range => "RANGE",
            Self::Count => "COUNT",
            Self::Unqualified => "UNQUALIFIED",
            Self::Stale => "STALE",
            Self::WrongWorld => "WRONG_WORLD",
            Self::Snapshot => "SNAPSHOT",
            Self::Order => "ORDER",
            Self::Conflict => "CONFLICT",
            Self::InvalidDiff => "INVALID_DIFF",
            Self::GenerationExhausted => "GENERATION_EXHAUSTED",
        }
    }
}

/// These are the only executable operations. No callback, IO or RNG variants.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PureOp {
    Add {
        cell: usize,
        delta: i32,
    },
    Copy {
        source: usize,
        target: usize,
    },
    Gradient {
        start: usize,
        len: usize,
        base: u16,
        step: i16,
    },
}

/// Private fields prevent callers changing the snapshot after local admission.
/// This admission certifies only the toy model, never Minecraft/Forge behavior.
#[derive(Debug, Clone)]
pub struct QualifiedSnapshot {
    world: u64,
    generation: u64,
    qualification_epoch: u64,
    values: [u16; CELLS],
}

impl QualifiedSnapshot {
    pub fn values(&self) -> [u16; CELLS] {
        self.values
    }
    pub fn generation(&self) -> u64 {
        self.generation
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Effect {
    pub cell: usize,
    pub before: u16,
    pub after: u16,
}

/// Explicit diff with private provenance and effect storage. Getters are read-only.
#[derive(Debug, Clone)]
pub struct Diff {
    snapshot: QualifiedSnapshot,
    ordinal: usize,
    operation: PureOp,
    reads: Vec<(usize, u16)>,
    effects: Vec<Effect>,
}

impl Diff {
    pub fn effects(&self) -> &[Effect] {
        &self.effects
    }
    pub fn reads(&self) -> &[(usize, u16)] {
        &self.reads
    }
}

fn cell(index: usize) -> Result<(), Error> {
    if index < CELLS {
        Ok(())
    } else {
        Err(Error::Cell)
    }
}
fn value(number: i32) -> Result<u16, Error> {
    u16::try_from(number).map_err(|_| Error::Range)
}

pub fn plan(
    snapshot: &QualifiedSnapshot,
    ordinal: usize,
    operation: PureOp,
) -> Result<Diff, Error> {
    if ordinal >= MAX_BATCH {
        return Err(Error::Order);
    }
    let mut reads = Vec::new();
    let mut effects = Vec::new();
    let mut read = |index: usize| {
        if !reads.iter().any(|&(i, _)| i == index) {
            reads.push((index, snapshot.values[index]));
        }
        snapshot.values[index]
    };
    match operation {
        PureOp::Add { cell: index, delta } => {
            cell(index)?;
            let before = read(index);
            let after = value(i32::from(before).checked_add(delta).ok_or(Error::Range)?)?;
            effects.push(Effect {
                cell: index,
                before,
                after,
            });
        }
        PureOp::Copy { source, target } => {
            cell(source)?;
            cell(target)?;
            let after = read(source);
            let before = read(target);
            effects.push(Effect {
                cell: target,
                before,
                after,
            });
        }
        PureOp::Gradient {
            start,
            len,
            base,
            step,
        } => {
            if len == 0 || len > CELLS {
                return Err(Error::Count);
            }
            cell(start)?;
            if start.checked_add(len).ok_or(Error::Cell)? > CELLS {
                return Err(Error::Cell);
            }
            for offset in 0..len {
                let index = start + offset;
                let after = value(i32::from(base) + offset as i32 * i32::from(step))?;
                effects.push(Effect {
                    cell: index,
                    before: read(index),
                    after,
                });
            }
        }
    }
    Ok(Diff {
        snapshot: snapshot.clone(),
        ordinal,
        operation,
        reads,
        effects,
    })
}

#[derive(Debug, PartialEq, Eq)]
pub struct CommitReceipt {
    pub generation: u64,
    pub effects: Vec<Effect>,
    pub values: [u16; CELLS],
}

/// Mutable owner of one model allocation; commit requires exclusive &mut access.
pub struct ModelWorld {
    id: u64,
    generation: u64,
    qualification_epoch: u64,
    admitted: bool,
    values: [u16; CELLS],
}

impl ModelWorld {
    pub fn new(values: [u16; CELLS]) -> Result<Self, Error> {
        let id = NEXT_WORLD
            .fetch_update(Ordering::Relaxed, Ordering::Relaxed, |old| {
                old.checked_add(1)
            })
            .map_err(|_| Error::GenerationExhausted)?;
        Ok(Self {
            id,
            generation: 0,
            qualification_epoch: 0,
            admitted: false,
            values,
        })
    }
    pub fn admit_offline_model(&mut self) -> Result<(), Error> {
        self.qualification_epoch = self
            .qualification_epoch
            .checked_add(1)
            .ok_or(Error::GenerationExhausted)?;
        self.admitted = true;
        Ok(())
    }
    pub fn revoke(&mut self) {
        self.admitted = false;
    }
    pub fn snapshot(&self) -> Result<QualifiedSnapshot, Error> {
        if !self.admitted {
            return Err(Error::Unqualified);
        }
        Ok(QualifiedSnapshot {
            world: self.id,
            generation: self.generation,
            qualification_epoch: self.qualification_epoch,
            values: self.values,
        })
    }
    pub fn values(&self) -> [u16; CELLS] {
        self.values
    }
    pub fn generation(&self) -> u64 {
        self.generation
    }

    /// All validation and effects happen on a staged array. Publication is last.
    /// A rejected batch changes neither values nor generation and emits no receipt.
    pub fn commit_ordered(&mut self, diffs: &[Diff]) -> Result<CommitReceipt, Error> {
        if !self.admitted {
            return Err(Error::Unqualified);
        }
        if diffs.is_empty() || diffs.len() > MAX_BATCH {
            return Err(Error::Count);
        }
        let generation = self
            .generation
            .checked_add(1)
            .ok_or(Error::GenerationExhausted)?;
        let mut staged = self.values;
        let mut emitted = Vec::new();
        for (ordinal, diff) in diffs.iter().enumerate() {
            if diff.effects.is_empty() || diff.effects.len() > CELLS || diff.reads.len() > CELLS {
                return Err(Error::InvalidDiff);
            }
            if diff.ordinal != ordinal {
                return Err(Error::Order);
            }
            if diff.snapshot.world != self.id {
                return Err(Error::WrongWorld);
            }
            if diff.snapshot.generation != self.generation
                || diff.snapshot.qualification_epoch != self.qualification_epoch
            {
                return Err(Error::Stale);
            }
            if diff.snapshot.values != self.values {
                return Err(Error::Snapshot);
            }
            let expected = plan(&diff.snapshot, ordinal, diff.operation)?;
            if diff.reads != expected.reads || diff.effects != expected.effects {
                return Err(Error::InvalidDiff);
            }
            if diff
                .reads
                .iter()
                .any(|&(index, before)| staged[index] != before)
            {
                return Err(Error::Conflict);
            }
            for effect in &diff.effects {
                staged[effect.cell] = effect.after;
                emitted.push(effect.clone());
            }
        }
        self.values = staged;
        self.generation = generation;
        Ok(CommitReceipt {
            generation,
            effects: emitted,
            values: staged,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn world() -> ModelWorld {
        let mut w = ModelWorld::new([0; CELLS]).unwrap();
        w.admit_offline_model().unwrap();
        w
    }
    fn add(s: &QualifiedSnapshot, o: usize, c: usize, d: i32) -> Diff {
        plan(s, o, PureOp::Add { cell: c, delta: d }).unwrap()
    }
    fn unchanged(w: &mut ModelWorld, diffs: &[Diff], error: Error) {
        let before = (w.values(), w.generation());
        assert_eq!(w.commit_ordered(diffs).unwrap_err(), error);
        assert_eq!(before, (w.values(), w.generation()));
    }
    #[test]
    fn unadmitted_snapshot_rejected() {
        let w = ModelWorld::new([0; CELLS]).unwrap();
        assert_eq!(w.snapshot().unwrap_err(), Error::Unqualified);
    }
    #[test]
    fn snapshot_copy_cannot_mutate_source() {
        let w = world();
        let s = w.snapshot().unwrap();
        let mut copied = s.values();
        copied[0] = 99;
        assert_eq!(s.values()[0], 0);
        assert_eq!(w.values()[0], 0);
    }
    #[test]
    fn planning_has_no_mutation() {
        let w = world();
        let s = w.snapshot().unwrap();
        let d = add(&s, 0, 0, 7);
        assert_eq!(
            d.effects(),
            &[Effect {
                cell: 0,
                before: 0,
                after: 7
            }]
        );
        assert_eq!(w.values(), [0; CELLS]);
        assert_eq!(w.generation(), 0);
    }
    #[test]
    fn ordered_nonconflicting_commit() {
        let mut w = world();
        let s = w.snapshot().unwrap();
        let result = w
            .commit_ordered(&[add(&s, 0, 2, 4), add(&s, 1, 0, 3)])
            .unwrap();
        assert_eq!(result.generation, 1);
        assert_eq!(
            result.effects.iter().map(|e| e.cell).collect::<Vec<_>>(),
            vec![2, 0]
        );
        assert_eq!(w.values()[2], 4);
    }
    #[test]
    fn out_of_order_rejected() {
        let mut w = world();
        let s = w.snapshot().unwrap();
        unchanged(&mut w, &[add(&s, 1, 0, 3), add(&s, 0, 1, 4)], Error::Order);
    }
    #[test]
    fn write_conflict_is_atomic() {
        let mut w = world();
        let s = w.snapshot().unwrap();
        unchanged(
            &mut w,
            &[add(&s, 0, 0, 3), add(&s, 1, 0, 4)],
            Error::Conflict,
        );
    }
    #[test]
    fn read_after_write_conflict_is_atomic() {
        let mut w = world();
        let s = w.snapshot().unwrap();
        let copy = plan(
            &s,
            1,
            PureOp::Copy {
                source: 0,
                target: 1,
            },
        )
        .unwrap();
        unchanged(&mut w, &[add(&s, 0, 0, 3), copy], Error::Conflict);
    }
    #[test]
    fn stale_generation_rejected() {
        let mut w = world();
        let s = w.snapshot().unwrap();
        let d = add(&s, 0, 0, 3);
        w.commit_ordered(&[d.clone()]).unwrap();
        unchanged(&mut w, &[d], Error::Stale);
    }
    #[test]
    fn identical_other_world_rejected() {
        let mut a = world();
        let b = world();
        let d = add(&b.snapshot().unwrap(), 0, 0, 3);
        unchanged(&mut a, &[d], Error::WrongWorld);
    }
    #[test]
    fn revocation_and_readmission_invalidate_old_diff() {
        let mut w = world();
        let s = w.snapshot().unwrap();
        let d = add(&s, 0, 0, 3);
        w.revoke();
        unchanged(&mut w, &[d.clone()], Error::Unqualified);
        w.admit_offline_model().unwrap();
        unchanged(&mut w, &[d], Error::Stale);
    }
    #[test]
    fn corrupted_second_diff_does_not_publish_first() {
        let mut w = world();
        let s = w.snapshot().unwrap();
        let mut second = add(&s, 1, 1, 4);
        second.effects[0].after = 99;
        unchanged(&mut w, &[add(&s, 0, 0, 3), second], Error::InvalidDiff);
    }
    #[test]
    fn oversized_effects_rejected() {
        let mut w = world();
        let s = w.snapshot().unwrap();
        let mut d = add(&s, 0, 0, 3);
        d.effects = vec![d.effects[0].clone(); CELLS + 1];
        unchanged(&mut w, &[d], Error::InvalidDiff);
    }
    #[test]
    fn oversized_batch_rejected() {
        let mut w = world();
        let s = w.snapshot().unwrap();
        let d = add(&s, 0, 0, 0);
        unchanged(&mut w, &vec![d; MAX_BATCH + 1], Error::Count);
    }
    #[test]
    fn empty_batch_rejected() {
        unchanged(&mut world(), &[], Error::Count);
    }
    #[test]
    fn gradient_failure_returns_no_partial_diff() {
        let w = world();
        assert_eq!(
            plan(
                &w.snapshot().unwrap(),
                0,
                PureOp::Gradient {
                    start: 0,
                    len: 3,
                    base: 65534,
                    step: 1
                }
            )
            .unwrap_err(),
            Error::Range
        );
        assert_eq!(w.values(), [0; CELLS]);
    }
    #[test]
    fn gradient_golden() {
        let mut w = world();
        let s = w.snapshot().unwrap();
        let d = plan(
            &s,
            0,
            PureOp::Gradient {
                start: 3,
                len: 3,
                base: 9,
                step: -2,
            },
        )
        .unwrap();
        w.commit_ordered(&[d]).unwrap();
        assert_eq!(&w.values()[3..6], &[9, 7, 5]);
    }
    #[test]
    fn generation_exhaustion_is_atomic() {
        let mut w = world();
        w.generation = u64::MAX;
        let d = add(&w.snapshot().unwrap(), 0, 0, 1);
        unchanged(&mut w, &[d], Error::GenerationExhausted);
    }
    #[test]
    fn forged_snapshot_contents_rejected() {
        let mut w = world();
        let mut s = w.snapshot().unwrap();
        s.values[2] = 1;
        unchanged(&mut w, &[add(&s, 0, 0, 1)], Error::Snapshot);
    }
    #[test]
    fn ordinal_bounds() {
        let w = world();
        assert_eq!(
            plan(
                &w.snapshot().unwrap(),
                MAX_BATCH,
                PureOp::Add { cell: 0, delta: 1 }
            )
            .unwrap_err(),
            Error::Order
        );
    }
    #[test]
    fn repeated_planning_is_deterministic() {
        let w = world();
        let s = w.snapshot().unwrap();
        assert_eq!(add(&s, 0, 2, 3).effects(), add(&s, 0, 2, 3).effects());
    }
}
