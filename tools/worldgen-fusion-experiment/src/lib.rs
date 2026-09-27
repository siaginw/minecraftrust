//! H10 experiment only. No game state authority, production exports or wire IDs.
use jni::{
    errors::ThrowRuntimeExAndDefault,
    objects::{JClass, JDoubleArray, JIntArray, JLongArray},
    EnvUnowned,
};
use rustcraft_core::DenseRuntimeStateId;
use std::{
    collections::BTreeMap,
    sync::{LazyLock, Mutex},
    time::Instant,
};
use worldgen_noise::{JavaRandom, Octaves};

const MAX_SESSIONS: usize = 16;
const EPOCH: i64 = 1;
type JResult<T> = jni::errors::Result<T>;
struct Session {
    a: Octaves,
    b: Octaves,
    retained: Vec<DenseRuntimeStateId>,
}
#[derive(Default)]
struct Store {
    next: i64,
    sessions: BTreeMap<i64, Session>,
    counters: [i64; 7],
}
static STORE: LazyLock<Mutex<Store>> = LazyLock::new(|| Mutex::new(Store::default()));

fn length(height: i32) -> Option<usize> {
    matches!(height, 1 | 17 | 32).then(|| 256 * height as usize)
}
fn fields(s: &Session, x: i32, z: i32, h: i32) -> (Vec<f64>, Vec<f64>) {
    (
        s.a.generate3d(None, x, 0, z, 16, h, 16, 0.125, 0.25, 0.125),
        s.b.generate3d(None, x, 0, z, 16, h, 16, 0.0625, 0.125, 0.0625),
    )
}
/// New synthetic composition, independently specified; not Minecraft terrain code.
pub fn density(a: f64, b: f64, y: usize, shift: f32) -> f64 {
    let first = a * 0.125;
    let second = b * 0.0625;
    let combined = first + second;
    let biased = combined + f64::from(shift);
    biased - (y as f64 * 0.25)
}
pub fn compose(a: &[f64], b: &[f64], h: i32, shift: f32) -> Option<Vec<DenseRuntimeStateId>> {
    let n = length(h)?;
    if a.len() != n
        || b.len() != n
        || !shift.is_finite()
        || a.iter().chain(b).any(|v| !v.is_finite())
    {
        return None;
    }
    Some(
        a.iter()
            .zip(b)
            .enumerate()
            .map(|(i, (&a, &b))| {
                let y = i % h as usize;
                DenseRuntimeStateId(if density(a, b, y, shift) > 0.0 {
                    70_000
                } else if y < 8 {
                    0xf000_0001
                } else {
                    0
                })
            })
            .collect(),
    )
}
fn bits(states: &[DenseRuntimeStateId]) -> Vec<i32> {
    states.iter().map(|s| s.0 as i32).collect()
}

#[no_mangle]
pub extern "system" fn Java_com_rustcraft_fusion_FusionProbe_trace<'l>(
    mut env: EnvUnowned<'l>,
    _: JClass<'l>,
    id: i64,
    x: i32,
    z: i32,
    h: i32,
    shift: f32,
    out: JDoubleArray<'l>,
) -> i32 {
    env.with_env(|env| -> JResult<i32> {
        let s = STORE.lock().unwrap();
        let Some(n) = length(h) else { return Ok(-2) };
        if out.len(env)? != 3 * n || !shift.is_finite() {
            return Ok(-3);
        }
        let Some(session) = s.sessions.get(&id) else {
            return Ok(-1);
        };
        let (a, b) = fields(session, x, z, h);
        let mut all = Vec::with_capacity(3 * n);
        all.extend_from_slice(&a);
        all.extend_from_slice(&b);
        for i in 0..n {
            all.push(density(a[i], b[i], i % h as usize, shift))
        }
        out.set_region(env, 0, &all)?;
        Ok(0)
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

#[no_mangle]
pub extern "system" fn Java_com_rustcraft_fusion_FusionProbe_create<'l>(
    mut env: EnvUnowned<'l>,
    _: JClass<'l>,
    seed: i64,
    epoch: i64,
) -> i64 {
    env.with_env(|_| -> JResult<i64> {
        let mut s = STORE.lock().unwrap();
        if epoch != EPOCH || s.sessions.len() == MAX_SESSIONS || s.next == i64::MAX {
            return Ok(-1);
        }
        s.next += 1;
        let id = s.next;
        let mut random = JavaRandom::new(seed);
        let a = Octaves::new(&mut random, 4);
        let b = Octaves::new(&mut random, 3);
        s.sessions.insert(
            id,
            Session {
                a,
                b,
                retained: Vec::new(),
            },
        );
        Ok(id)
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}
#[no_mangle]
pub extern "system" fn Java_com_rustcraft_fusion_FusionProbe_destroy<'l>(
    mut env: EnvUnowned<'l>,
    _: JClass<'l>,
    id: i64,
) -> i32 {
    env.with_env(|_| -> JResult<i32> {
        Ok(if STORE.lock().unwrap().sessions.remove(&id).is_some() {
            0
        } else {
            -1
        })
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}
#[no_mangle]
pub extern "system" fn Java_com_rustcraft_fusion_FusionProbe_discard<'l>(
    mut env: EnvUnowned<'l>,
    _: JClass<'l>,
    id: i64,
) -> i32 {
    env.with_env(|_| -> JResult<i32> {
        let mut s = STORE.lock().unwrap();
        if let Some(v) = s.sessions.get_mut(&id) {
            v.retained.clear();
            Ok(0)
        } else {
            Ok(-1)
        }
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}
#[no_mangle]
pub extern "system" fn Java_com_rustcraft_fusion_FusionProbe_noise<'l>(
    mut env: EnvUnowned<'l>,
    _: JClass<'l>,
    id: i64,
    x: i32,
    z: i32,
    h: i32,
    a: JDoubleArray<'l>,
    b: JDoubleArray<'l>,
) -> i32 {
    env.with_env(|env| -> JResult<i32> {
        let mut s = STORE.lock().unwrap();
        s.counters[0] += 1;
        let Some(n) = length(h) else { return Ok(-2) };
        if a.len(env)? != n || b.len(env)? != n || env.is_same_object(&a, &b)? {
            return Ok(-3);
        }
        let Some(session) = s.sessions.get(&id) else {
            return Ok(-1);
        };
        let t = Instant::now();
        let (av, bv) = fields(session, x, z, h);
        s.counters[4] += t.elapsed().as_nanos() as i64;
        let t = Instant::now();
        a.set_region(env, 0, &av)?;
        b.set_region(env, 0, &bv)?;
        s.counters[5] += t.elapsed().as_nanos() as i64;
        s.counters[2] += (16 * n) as i64;
        s.counters[3] += (16 * n) as i64;
        Ok(0)
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}
#[no_mangle]
pub extern "system" fn Java_com_rustcraft_fusion_FusionProbe_terrain<'l>(
    mut env: EnvUnowned<'l>,
    _: JClass<'l>,
    h: i32,
    shift: f32,
    a: JDoubleArray<'l>,
    b: JDoubleArray<'l>,
    out: JIntArray<'l>,
) -> i32 {
    env.with_env(|env| -> JResult<i32> {
        let mut s = STORE.lock().unwrap();
        s.counters[0] += 1;
        let Some(n) = length(h) else { return Ok(-2) };
        if a.len(env)? != n || b.len(env)? != n || out.len(env)? != n {
            return Ok(-3);
        }
        let mut av = vec![0.; n];
        let mut bv = vec![0.; n];
        let t = Instant::now();
        a.get_region(env, 0, &mut av)?;
        b.get_region(env, 0, &mut bv)?;
        s.counters[5] += t.elapsed().as_nanos() as i64;
        s.counters[1] += (16 * n) as i64;
        s.counters[3] += (16 * n) as i64;
        let t = Instant::now();
        let Some(states) = compose(&av, &bv, h, shift) else {
            return Ok(-4);
        };
        let result = bits(&states);
        s.counters[4] += t.elapsed().as_nanos() as i64;
        let t = Instant::now();
        out.set_region(env, 0, &result)?;
        s.counters[5] += t.elapsed().as_nanos() as i64;
        s.counters[2] += (4 * n) as i64;
        s.counters[3] += (8 * n) as i64;
        Ok(0)
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}
#[no_mangle]
pub extern "system" fn Java_com_rustcraft_fusion_FusionProbe_fused<'l>(
    mut env: EnvUnowned<'l>,
    _: JClass<'l>,
    id: i64,
    epoch: i64,
    x: i32,
    z: i32,
    h: i32,
    shift: f32,
    middle_observer: i32,
    out: JIntArray<'l>,
) -> i32 {
    env.with_env(|env| -> JResult<i32> {
        let mut s = STORE.lock().unwrap();
        s.counters[0] += 1;
        let Some(n) = length(h) else { return Ok(-2) };
        if out.len(env)? != n {
            return Ok(-3);
        }
        if epoch != EPOCH || middle_observer != 0 || !shift.is_finite() {
            return Ok(-4);
        }
        let Some(session) = s.sessions.get(&id) else {
            return Ok(-1);
        };
        let t = Instant::now();
        let (a, b) = fields(session, x, z, h);
        let Some(states) = compose(&a, &b, h, shift) else {
            return Ok(-4);
        };
        let result = bits(&states);
        s.counters[4] += t.elapsed().as_nanos() as i64;
        let t = Instant::now();
        out.set_region(env, 0, &result)?;
        s.counters[5] += t.elapsed().as_nanos() as i64;
        // Only diagnostic retained data; Java result remains unpublished until callbacks finish.
        s.sessions.get_mut(&id).unwrap().retained = states;
        s.counters[2] += (4 * n) as i64;
        s.counters[3] += (24 * n) as i64;
        Ok(0)
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}
#[no_mangle]
pub extern "system" fn Java_com_rustcraft_fusion_FusionProbe_metrics<'l>(
    mut env: EnvUnowned<'l>,
    _: JClass<'l>,
    out: JLongArray<'l>,
    reset: i32,
) -> i32 {
    env.with_env(|env| -> JResult<i32> {
        let mut s = STORE.lock().unwrap();
        if out.len(env)? != 7 {
            return Ok(-1);
        };
        s.counters[6] = s
            .sessions
            .values()
            .map(|v| v.retained.len() as i64 * 4)
            .sum();
        out.set_region(env, 0, &s.counters)?;
        if reset != 0 {
            s.counters = [0; 7]
        }
        Ok(0)
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn invalid_inputs_rejected() {
        assert!(compose(&[], &[], 32, 0.).is_none());
        assert!(length(256).is_none());
        assert!(length(-1).is_none());
        let a = vec![0.; 256];
        assert!(compose(&a, &a, 1, f32::NAN).is_none());
        let mut b = a.clone();
        b[9] = f64::INFINITY;
        assert!(compose(&a, &b, 1, 0.).is_none());
    }
    #[test]
    fn exact_float_widening_and_u32() {
        let a = vec![0.; 256];
        let states = compose(&a, &a, 1, 1.).unwrap();
        assert!(states.iter().all(|v| v.0 == 70000));
        let wet = compose(&a, &a, 1, -1.).unwrap();
        assert_eq!(wet[0].0, 0xf0000001);
        assert_eq!(bits(&wet)[0] as u32, wet[0].0);
        assert_eq!(
            density(0., 0., 0, f32::from_bits(0x3eaaaaab)).to_bits(),
            f64::from(f32::from_bits(0x3eaaaaab)).to_bits()
        );
    }
    #[test]
    fn all_cells_follow_y_major_inner_order() {
        let n = length(32).unwrap();
        let a = vec![0.; n];
        let states = compose(&a, &a, 32, 0.5).unwrap();
        for (i, v) in states.iter().enumerate() {
            let y = i % 32;
            assert_eq!(
                v.0,
                if y < 2 {
                    70000
                } else if y < 8 {
                    0xf0000001
                } else {
                    0
                }
            );
        }
    }
}
