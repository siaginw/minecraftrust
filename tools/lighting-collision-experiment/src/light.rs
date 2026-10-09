//! Block-light fixed point on an explicitly bounded, callback-free grid.
//! No skylight, chunks, listeners, renderer, scheduler or engine authority.
use std::collections::VecDeque;
#[derive(Default, Debug)]
pub struct Stats {
    pub neighbor_reads: u64,
    pub updates: u64,
    pub queue_peak: usize,
}
fn neighbors(side: usize, i: usize) -> [Option<usize>; 6] {
    let x = i % side;
    let y = (i / side) % side;
    let z = i / (side * side);
    [
        x.checked_sub(1).map(|_| i - 1),
        (x + 1 < side).then_some(i + 1),
        y.checked_sub(1).map(|_| i - side),
        (y + 1 < side).then_some(i + side),
        z.checked_sub(1).map(|_| i - side * side),
        (z + 1 < side).then_some(i + side * side),
    ]
}
fn level(
    side: usize,
    i: usize,
    emission: &[u8],
    opacity: &[u8],
    light: &[u8],
    stats: &mut Stats,
) -> u8 {
    let mut value = emission[i];
    let attenuation = if opacity[i] >= 15 && value > 0 {
        1
    } else {
        opacity[i].max(1)
    };
    if attenuation >= 15 || value == 15 {
        return value;
    }
    for j in neighbors(side, i).into_iter().flatten() {
        stats.neighbor_reads += 1;
        value = value.max(light[j].saturating_sub(attenuation));
        if value == 14 {
            break;
        }
    }
    value
}
pub fn run(
    side: usize,
    emission: &[u8],
    opacity: &[u8],
    initial: &[u8],
    dirty: &[i32],
    optimized: bool,
) -> Option<(Vec<u8>, Stats)> {
    if !(2..=32).contains(&side) {
        return None;
    }
    let n = side * side * side;
    if emission.len() != n
        || opacity.len() != n
        || initial.len() != n
        || emission
            .iter()
            .chain(opacity)
            .chain(initial)
            .any(|v| *v > 15)
        || dirty.len() > n
        || dirty.iter().any(|i| *i < 0 || *i as usize >= n)
    {
        return None;
    }
    let mut stats = Stats::default();
    if !optimized {
        let mut light = vec![0; n];
        for _ in 0..17 {
            let mut next = Vec::with_capacity(n);
            let mut changed = false;
            for i in 0..n {
                let v = level(side, i, emission, opacity, &light, &mut stats);
                changed |= v != light[i];
                stats.updates += u64::from(v != light[i]);
                next.push(v);
            }
            light = next;
            if !changed {
                return Some((light, stats));
            }
        }
        return None;
    }
    let mut light = initial.to_vec();
    let mut queue = VecDeque::<u32>::with_capacity(n);
    let mut queued = vec![0u64; n.div_ceil(64)];
    fn push(i: usize, queue: &mut VecDeque<u32>, queued: &mut [u64]) {
        if queued[i / 64] & (1u64 << (i % 64)) == 0 {
            queued[i / 64] |= 1u64 << (i % 64);
            queue.push_back(i as u32);
        }
    }
    for &i in dirty {
        push(i as usize, &mut queue, &mut queued);
        for j in neighbors(side, i as usize).into_iter().flatten() {
            push(j, &mut queue, &mut queued);
        }
    }
    stats.queue_peak = queue.len();
    let mut steps = 0usize;
    while let Some(i) = queue.pop_front() {
        steps += 1;
        if steps > n * 128 {
            return None;
        }
        let i = i as usize;
        queued[i / 64] &= !(1u64 << (i % 64));
        let v = level(side, i, emission, opacity, &light, &mut stats);
        if v != light[i] {
            stats.updates += 1;
            light[i] = v;
            for j in neighbors(side, i).into_iter().flatten() {
                push(j, &mut queue, &mut queued);
            }
            stats.queue_peak = stats.queue_peak.max(queue.len());
        }
    }
    Some((light, stats))
}
