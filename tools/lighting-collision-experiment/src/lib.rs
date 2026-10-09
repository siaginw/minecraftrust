pub mod collision;
pub mod light;
use jni::{
    errors::ThrowRuntimeExAndDefault,
    objects::{JByteArray, JClass, JDoubleArray, JIntArray, JLongArray},
    EnvUnowned,
};
use std::time::Instant;
type JResult<T> = jni::errors::Result<T>;

#[no_mangle]
pub extern "system" fn Java_com_rustcraft_h13_Probe_collide<'l>(
    mut env: EnvUnowned<'l>,
    _: JClass<'l>,
    mode: i32,
    callbacks: i32,
    shapes: JDoubleArray<'l>,
    queries: JDoubleArray<'l>,
    out: JLongArray<'l>,
    metrics: JLongArray<'l>,
) -> i32 {
    env.with_env(|env| -> JResult<i32> {
        let start = Instant::now();
        if callbacks != 0 || !(0..=1).contains(&mode) {
            return Ok(-1);
        }
        if env.is_same_object(&out, &metrics)? {
            return Ok(-2);
        }
        let sn = shapes.len(env)?;
        let qn = queries.len(env)?;
        if sn > collision::MAX_SHAPES * 6
            || qn > collision::MAX_QUERIES * 9
            || sn % 6 != 0
            || qn % 9 != 0
            || out.len(env)? < 1 + (qn / 9) * (4 + sn / 6)
            || metrics.len(env)? != 8
        {
            return Ok(-2);
        }
        let mut s = vec![0.0; sn];
        let mut q = vec![0.0; qn];
        shapes.get_region(env, 0, &mut s)?;
        queries.get_region(env, 0, &mut q)?;
        let compute = Instant::now();
        let Some((result, stats)) = collision::run(&s, &q, mode == 1) else {
            return Ok(-3);
        };
        let compute_ns = compute.elapsed().as_nanos() as i64;
        // Metadata is written first: if it fails, no successful result exists.
        metrics.set_region(
            env,
            0,
            &[
                stats.exact_tests as i64,
                stats.bucket_visits as i64,
                stats.index_entries as i64,
                ((sn + qn) * 8) as i64,
                (result.len() * 8) as i64,
                compute_ns,
                start.elapsed().as_nanos() as i64,
                ((sn + qn) * 8) as i64,
            ],
        )?;
        out.set_region(env, 0, &result)?;
        Ok(result.len() as i32)
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

#[no_mangle]
pub extern "system" fn Java_com_rustcraft_h13_Probe_light<'l>(
    mut env: EnvUnowned<'l>,
    _: JClass<'l>,
    mode: i32,
    side: i32,
    callbacks: i32,
    emission: JByteArray<'l>,
    opacity: JByteArray<'l>,
    initial: JByteArray<'l>,
    dirty: JIntArray<'l>,
    out: JByteArray<'l>,
    metrics: JLongArray<'l>,
) -> i32 {
    env.with_env(|env| -> JResult<i32> {
        let start = Instant::now();
        if callbacks != 0 || !(0..=1).contains(&mode) || !(2..=32).contains(&side) {
            return Ok(-1);
        }
        if env.is_same_object(&out, &emission)?
            || env.is_same_object(&out, &opacity)?
            || env.is_same_object(&out, &initial)?
        {
            return Ok(-2);
        }
        let n = (side as usize).pow(3);
        let dn = dirty.len(env)?;
        if emission.len(env)? != n
            || opacity.len(env)? != n
            || initial.len(env)? != n
            || out.len(env)? != n
            || dn > n
            || metrics.len(env)? != 8
        {
            return Ok(-2);
        }
        let mut e = vec![0i8; n];
        let mut o = vec![0i8; n];
        let mut l = vec![0i8; n];
        let mut d = vec![0i32; dn];
        emission.get_region(env, 0, &mut e)?;
        opacity.get_region(env, 0, &mut o)?;
        initial.get_region(env, 0, &mut l)?;
        dirty.get_region(env, 0, &mut d)?;
        let e: Vec<u8> = e.into_iter().map(|v| v as u8).collect();
        let o: Vec<u8> = o.into_iter().map(|v| v as u8).collect();
        let l: Vec<u8> = l.into_iter().map(|v| v as u8).collect();
        let compute = Instant::now();
        let Some((result, stats)) = light::run(side as usize, &e, &o, &l, &d, mode == 1) else {
            return Ok(-3);
        };
        let compute_ns = compute.elapsed().as_nanos() as i64;
        let signed: Vec<i8> = result.into_iter().map(|v| v as i8).collect();
        metrics.set_region(
            env,
            0,
            &[
                stats.neighbor_reads as i64,
                stats.updates as i64,
                stats.queue_peak as i64,
                (3 * n + 4 * dn) as i64,
                n as i64,
                compute_ns,
                start.elapsed().as_nanos() as i64,
                (4 * n + 4 * dn) as i64,
            ],
        )?;
        out.set_region(env, 0, &signed)?;
        Ok(0)
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}
