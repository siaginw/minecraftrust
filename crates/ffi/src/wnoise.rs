//! M3-WORLDGEN-NOISE JNI surface — ONE coarse call per noise field.

use crate::GLOBAL_FFI_METRICS;
use std::ffi::c_void;
use std::panic::catch_unwind;
use worldgen_noise::{JavaRandom, Octaves};

fn from_handle(h: *mut c_void) -> Option<&'static mut Octaves> {
    if h.is_null() {
        return None;
    }
    Some(unsafe { &mut *(h as *mut Octaves) })
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_rustcraft_interop_WNoiseInterop_create(
    _env: *mut c_void,
    _cls: *mut c_void,
    seed: i64,
    octaves: i32,
) -> *mut c_void {
    GLOBAL_FFI_METRICS.record_call(200);
    catch_unwind(|| {
        let mut rand = JavaRandom::new(seed);
        Box::into_raw(Box::new(Octaves::new(&mut rand, octaves))) as *mut c_void
    })
    .unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_rustcraft_interop_WNoiseInterop_freeRaw(
    _env: *mut c_void,
    _cls: *mut c_void,
    h: *mut c_void,
) {
    GLOBAL_FFI_METRICS.record_call(201);
    if !h.is_null() {
        drop(Box::from_raw(h as *mut Octaves));
    }
}

/// Generate a full 3D field into the caller's direct double buffer.
/// ONE call per field — no per-sample/per-octave crossings.
#[no_mangle]
pub unsafe extern "system" fn Java_com_rustcraft_interop_WNoiseInterop_gen3d(
    _env: *mut c_void,
    _cls: *mut c_void,
    h: *mut c_void,
    x_off: i32,
    y_off: i32,
    z_off: i32,
    x_size: i32,
    y_size: i32,
    z_size: i32,
    x_scale: f64,
    y_scale: f64,
    z_scale: f64,
    out_addr: i64,
    out_len: i32,
) {
    GLOBAL_FFI_METRICS.record_call(202);
    let r = catch_unwind(std::panic::AssertUnwindSafe(|| {
        if let Some(oct) = from_handle(h) {
            let out = std::slice::from_raw_parts_mut(out_addr as *mut f64, out_len as usize);
            let n = (x_size * y_size * z_size) as usize;
            if out_len as usize >= n {
                let v = oct.generate3d(None, x_off, y_off, z_off, x_size, y_size, z_size, x_scale, y_scale, z_scale);
                out[..n].copy_from_slice(&v);
            }
        }
    }));
    let _ = r;
}

/// 2D wrapper field (the vanilla func_76305_a quirk preserved).
#[no_mangle]
pub unsafe extern "system" fn Java_com_rustcraft_interop_WNoiseInterop_gen2d(
    _env: *mut c_void,
    _cls: *mut c_void,
    h: *mut c_void,
    x_off: i32,
    z_off: i32,
    x_size: i32,
    z_size: i32,
    x_scale: f64,
    z_scale: f64,
    dropped: f64,
    out_addr: i64,
    out_len: i32,
) {
    GLOBAL_FFI_METRICS.record_call(203);
    let r = catch_unwind(std::panic::AssertUnwindSafe(|| {
        if let Some(oct) = from_handle(h) {
            let out = std::slice::from_raw_parts_mut(out_addr as *mut f64, out_len as usize);
            let n = (x_size * z_size) as usize;
            if out_len as usize >= n {
                let v = oct.generate2d(None, x_off, z_off, x_size, z_size, x_scale, z_scale, dropped);
                out[..n].copy_from_slice(&v);
            }
        }
    }));
    let _ = r;
}

use worldgen_noise::simd::BatchOctaves;

fn from_batch_handle(h: *mut c_void) -> Option<&'static mut BatchOctaves> {
    if h.is_null() {
        return None;
    }
    Some(unsafe { &mut *(h as *mut BatchOctaves) })
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_rustcraft_interop_WNoiseInterop_batchCreate(
    _env: *mut c_void,
    _cls: *mut c_void,
    seed: i64,
    octaves: i32,
) -> *mut c_void {
    GLOBAL_FFI_METRICS.record_call(210);
    catch_unwind(|| {
        Box::into_raw(Box::new(BatchOctaves::new(seed, octaves))) as *mut c_void
    })
    .unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_rustcraft_interop_WNoiseInterop_batchFreeRaw(
    _env: *mut c_void,
    _cls: *mut c_void,
    h: *mut c_void,
) {
    GLOBAL_FFI_METRICS.record_call(211);
    if !h.is_null() {
        drop(Box::from_raw(h as *mut BatchOctaves));
    }
}

/// ONE coarse call: n chunks x one generator's full 3D field into a direct
/// f64 slab of n*len doubles (chunk-major). Offsets arrive via int-array
/// addresses (per-chunk x and z offsets).
#[no_mangle]
pub unsafe extern "system" fn Java_com_rustcraft_interop_WNoiseInterop_batchGen3d(
    _env: *mut c_void,
    _cls: *mut c_void,
    h: *mut c_void,
    n: i32,
    x_offs_addr: i64,
    z_offs_addr: i64,
    y_off: i32,
    x_size: i32,
    y_size: i32,
    z_size: i32,
    x_scale: f64,
    y_scale: f64,
    z_scale: f64,
    out_addr: i64,
) {
    GLOBAL_FFI_METRICS.record_call(212);
    let r = catch_unwind(std::panic::AssertUnwindSafe(|| {
        if let Some(b) = from_batch_handle(h) {
            let n = n as usize;
            let xo = std::slice::from_raw_parts(x_offs_addr as *const i32, n);
            let zo = std::slice::from_raw_parts(z_offs_addr as *const i32, n);
            let len = (x_size * y_size * z_size) as usize;
            let out = std::slice::from_raw_parts_mut(out_addr as *mut f64, n * len);
            b.generate3d_batch(out, n, xo, y_off, zo, x_size, y_size, z_size,
                               x_scale, y_scale, z_scale);
        }
    }));
    let _ = r;
}

use worldgen_noise::field::{assemble_field, biome_weights, BiomeInputs, FieldSettings};
use worldgen_noise::field::SingleFieldGen;

fn from_field_handle(h: *mut c_void) -> Option<&'static mut FieldSettings> {
    if h.is_null() { return None; }
    Some(unsafe { &mut *(h as *mut FieldSettings) })
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_rustcraft_interop_WNoiseInterop_fieldCreate(
    _env: *mut c_void, _cls: *mut c_void,
    a: f32, b: f32, e: f32, f: f32, g: f32, h: f32, i: f32, j: f32,
    k: f32, l: f32, d: f32, c: f32, m: f32, n: f32, o: f32, p: f32,
    amplified: u8,
) -> *mut c_void {
    GLOBAL_FFI_METRICS.record_call(220);
    catch_unwind(|| {
        Box::into_raw(Box::new(FieldSettings {
            coordinate_scale: a, height_scale: b,
            depth_noise_scale_x: e, depth_noise_scale_exp: f, depth_noise_scale_z: g,
            main_noise_scale_x: h, main_noise_scale_y: i, main_noise_scale_z: j,
            upper_limit_scale: k, lower_limit_scale: l,
            depth_noise_scale: d, main_depth_scale: c,
            biome_depth_weight: m, biome_depth_offset: n,
            biome_scale_weight: o, biome_scale_offset: p,
            amplified: amplified != 0,
        })) as *mut c_void
    }).unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_rustcraft_interop_WNoiseInterop_fieldFreeRaw(
    _env: *mut c_void, _cls: *mut c_void, h: *mut c_void,
) {
    GLOBAL_FFI_METRICS.record_call(221);
    if !h.is_null() { drop(Box::from_raw(h as *mut FieldSettings)); }
}

/// Complete initNoiseField ASSEMBLY in one call: h[25], e/f/g[825],
/// biome floats (100 min-height then 100 variation), q[825] out.
#[no_mangle]
pub unsafe extern "system" fn Java_com_rustcraft_interop_WNoiseInterop_fieldAssemble(
    _env: *mut c_void, _cls: *mut c_void, fh: *mut c_void,
    h_addr: i64, e_addr: i64, f_addr: i64, g_addr: i64,
    biome_addr: i64, q_addr: i64,
) {
    GLOBAL_FFI_METRICS.record_call(222);
    let r = catch_unwind(std::panic::AssertUnwindSafe(|| {
        if let Some(s) = from_field_handle(fh) {
            let h = std::slice::from_raw_parts(h_addr as *const f64, 25);
            let e = std::slice::from_raw_parts(e_addr as *const f64, 825);
            let f = std::slice::from_raw_parts(f_addr as *const f64, 825);
            let g = std::slice::from_raw_parts(g_addr as *const f64, 825);
            let bf = std::slice::from_raw_parts(biome_addr as *const f32, 200);
            let q = std::slice::from_raw_parts_mut(q_addr as *mut f64, 825);
            let biomes = BiomeInputs {
                min_height: bf[..100].to_vec(),
                variation: bf[100..].to_vec(),
            };
            assemble_field(s, &biome_weights(), &biomes, h, e, f, g, q);
        }
    }));
    let _ = r;
}

fn from_single_handle(h: *mut c_void) -> Option<&'static mut SingleFieldGen> {
    if h.is_null() { return None; }
    Some(unsafe { &mut *(h as *mut SingleFieldGen) })
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_rustcraft_interop_WNoiseInterop_singleCreate(
    _env: *mut c_void, _cls: *mut c_void, seed: i64, octaves: i32,
) -> *mut c_void {
    GLOBAL_FFI_METRICS.record_call(223);
    catch_unwind(|| {
        Box::into_raw(Box::new(SingleFieldGen::new(seed, octaves))) as *mut c_void
    }).unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_rustcraft_interop_WNoiseInterop_singleFreeRaw(
    _env: *mut c_void, _cls: *mut c_void, h: *mut c_void,
) {
    GLOBAL_FFI_METRICS.record_call(224);
    if !h.is_null() { drop(Box::from_raw(h as *mut SingleFieldGen)); }
}

/// n=1 optimized: one x-lane field generation into a direct buffer.
#[no_mangle]
pub unsafe extern "system" fn Java_com_rustcraft_interop_WNoiseInterop_singleGen3d(
    _env: *mut c_void, _cls: *mut c_void, h: *mut c_void,
    x_off: i32, y_off: i32, z_off: i32,
    x_size: i32, y_size: i32, z_size: i32,
    x_scale: f64, y_scale: f64, z_scale: f64, out_addr: i64,
) {
    GLOBAL_FFI_METRICS.record_call(225);
    let r = catch_unwind(std::panic::AssertUnwindSafe(|| {
        if let Some(sg) = from_single_handle(h) {
            let len = (x_size * y_size * z_size) as usize;
            let out = std::slice::from_raw_parts_mut(out_addr as *mut f64, len);
            sg.generate3d_single(out, x_off, y_off, z_off, x_size, y_size, z_size,
                                 x_scale, y_scale, z_scale);
        }
    }));
    let _ = r;
}

use worldgen_noise::field_complete::InitNoiseField;

fn from_init_handle(h: *mut c_void) -> Option<&'static mut InitNoiseField> {
    if h.is_null() { return None; }
    Some(unsafe { &mut *(h as *mut InitNoiseField) })
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_rustcraft_interop_WNoiseInterop_initCreate(
    _env: *mut c_void, _cls: *mut c_void,
    seed_depth: i64, seed_main: i64, seed_min: i64, seed_max: i64,
    a: f32, b: f32, e: f32, f: f32, g: f32, h: f32, i: f32, j: f32,
    k: f32, l: f32, d: f32, c: f32, m: f32, n: f32, o: f32, p: f32,
    amplified: u8,
) -> *mut c_void {
    GLOBAL_FFI_METRICS.record_call(230);
    catch_unwind(|| {
        Box::into_raw(Box::new(InitNoiseField::new(seed_depth, seed_main, seed_min, seed_max,
            worldgen_noise::field::FieldSettings {
                coordinate_scale: a, height_scale: b,
                depth_noise_scale_x: e, depth_noise_scale_exp: f, depth_noise_scale_z: g,
                main_noise_scale_x: h, main_noise_scale_y: i, main_noise_scale_z: j,
                upper_limit_scale: k, lower_limit_scale: l,
                depth_noise_scale: d, main_depth_scale: c,
                biome_depth_weight: m, biome_depth_offset: n,
                biome_scale_weight: o, biome_scale_offset: p,
                amplified: amplified != 0,
            }))) as *mut c_void
    }).unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_rustcraft_interop_WNoiseInterop_initFreeRaw(
    _env: *mut c_void, _cls: *mut c_void, h: *mut c_void,
) {
    GLOBAL_FFI_METRICS.record_call(231);
    if !h.is_null() { drop(Box::from_raw(h as *mut InitNoiseField)); }
}

/// THE production boundary: complete terrain-density for ONE chunk in ONE
/// call. biomeAddr = 200 f32 (100 min-height, 100 variation); out = 825 f64.
#[no_mangle]
pub unsafe extern "system" fn Java_com_rustcraft_interop_WNoiseInterop_initFieldComplete(
    _env: *mut c_void, _cls: *mut c_void, h: *mut c_void,
    x4: i32, z4: i32, biome_addr: i64, out_addr: i64,
) {
    GLOBAL_FFI_METRICS.record_call(232);
    let r = catch_unwind(std::panic::AssertUnwindSafe(|| {
        if let Some(g) = from_init_handle(h) {
            let biomes = std::slice::from_raw_parts(biome_addr as *const f32, 200);
            let out = std::slice::from_raw_parts_mut(out_addr as *mut f64, 825);
            g.complete(x4, z4, biomes, out);
        }
    }));
    let _ = r;
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_rustcraft_interop_WNoiseInterop_initCreateFromState(
    _env: *mut c_void, _cls: *mut c_void,
    oct_depth: i32, oct_main: i32, oct_min: i32, oct_max: i32,
    perms_i32_addr: i64, offs_addr: i64,
    a: f32, b: f32, e: f32, f: f32, g: f32, h: f32, i: f32, j: f32,
    k: f32, l: f32, d: f32, c: f32, m: f32, n: f32, o: f32, p: f32,
) -> i64 {
    GLOBAL_FFI_METRICS.record_call(233);
    catch_unwind(std::panic::AssertUnwindSafe(|| {
        let total_levels = (oct_depth + oct_main + oct_min + oct_max) as usize;
        let perms_i32 = std::slice::from_raw_parts(perms_i32_addr as *const i32, total_levels * 512);
        let offs = std::slice::from_raw_parts(offs_addr as *const f64, total_levels * 3);
        // validate + compact to u8
        let mut perms_u8 = Vec::with_capacity(total_levels * 512);
        for &v in perms_i32 {
            if !(0..=255).contains(&v) {
                return 0i64;
            }
            perms_u8.push(v as u8);
        }
        match worldgen_noise::field_complete::InitNoiseField::from_state(
            oct_depth as usize, oct_main as usize, oct_min as usize, oct_max as usize,
            &perms_u8, offs,
            worldgen_noise::field::FieldSettings {
                coordinate_scale: a, height_scale: b,
                depth_noise_scale_x: e, depth_noise_scale_exp: f, depth_noise_scale_z: g,
                main_noise_scale_x: h, main_noise_scale_y: i, main_noise_scale_z: j,
                upper_limit_scale: k, lower_limit_scale: l,
                depth_noise_scale: d, main_depth_scale: c,
                biome_depth_weight: m, biome_depth_offset: n,
                biome_scale_weight: o, biome_scale_offset: p,
                amplified: false,
            },
        ) {
            Some(h) => Box::into_raw(Box::new(h)) as i64,
            None => 0,
        }
    })).unwrap_or(0)
}

// Bridge-named shims: WorldgenShadow (com.rustcraft.bridge) declares its own
// natives; JNI symbols must match THE DECLARING CLASS (the M2C lesson).

#[no_mangle]
pub unsafe extern "system" fn Java_com_rustcraft_bridge_WorldgenShadow_initCreateFromState(
    _env: *mut c_void, _cls: *mut c_void,
    oct_depth: i32, oct_main: i32, oct_min: i32, oct_max: i32,
    perms_i32_addr: i64, offs_addr: i64,
    a: f32, b: f32, e: f32, f: f32, g: f32, h: f32, i: f32, j: f32,
    k: f32, l: f32, d: f32, c: f32, m: f32, n: f32, o: f32, p: f32,
) -> i64 {
    Java_com_rustcraft_interop_WNoiseInterop_initCreateFromState(
        _env, _cls, oct_depth, oct_main, oct_min, oct_max, perms_i32_addr, offs_addr,
        a, b, e, f, g, h, i, j, k, l, d, c, m, n, o, p)
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_rustcraft_bridge_WorldgenShadow_initFieldComplete(
    _env: *mut c_void, _cls: *mut c_void, h: *mut c_void,
    x4: i32, z4: i32, biome_addr: i64, out_addr: i64,
) {
    Java_com_rustcraft_interop_WNoiseInterop_initFieldComplete(_env, _cls, h, x4, z4, biome_addr, out_addr)
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_rustcraft_bridge_WorldgenShadow_initFreeRaw(
    _env: *mut c_void, _cls: *mut c_void, h: *mut c_void,
) {
    Java_com_rustcraft_interop_WNoiseInterop_initFreeRaw(_env, _cls, h)
}

// -------------------------------------------------------------------------
// M3W5: Base terrain placement & ChunkPrimer ownership FFI
// -------------------------------------------------------------------------

#[no_mangle]
pub unsafe extern "system" fn Java_com_rustcraft_interop_WNoiseInterop_terrainSetBlocks(
    _env: *mut c_void, _cls: *mut c_void,
    density_addr: i64, sea_level: i32, stone_id: i32, water_id: i32, primer_out_addr: i64,
) {
    GLOBAL_FFI_METRICS.record_call(240);
    let _ = catch_unwind(std::panic::AssertUnwindSafe(|| {
        if density_addr == 0 || primer_out_addr == 0 { return; }
        let density = &*(density_addr as *const [f64; 825]);
        let primer = &mut *(primer_out_addr as *mut [u16; 65536]);
        worldgen_noise::terrain::set_blocks_in_chunk_parity(
            density, sea_level, stone_id as u16, water_id as u16, primer,
        );
    }));
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_rustcraft_interop_WNoiseInterop_terrainSetBlocksOpt(
    _env: *mut c_void, _cls: *mut c_void,
    density_addr: i64, sea_level: i32, stone_id: i32, water_id: i32, primer_out_addr: i64,
) {
    GLOBAL_FFI_METRICS.record_call(241);
    let _ = catch_unwind(std::panic::AssertUnwindSafe(|| {
        if density_addr == 0 || primer_out_addr == 0 { return; }
        let density = &*(density_addr as *const [f64; 825]);
        let primer = &mut *(primer_out_addr as *mut [u16; 65536]);
        worldgen_noise::terrain::set_blocks_in_chunk_column_major(
            density, sea_level, stone_id as u16, water_id as u16, primer,
        );
    }));
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_rustcraft_interop_WNoiseInterop_terrainComplete(
    _env: *mut c_void, _cls: *mut c_void, h: *mut c_void,
    x4: i32, z4: i32, biome_addr: i64, sea_level: i32, stone_id: i32, water_id: i32, primer_out_addr: i64,
) {
    GLOBAL_FFI_METRICS.record_call(242);
    let _ = catch_unwind(std::panic::AssertUnwindSafe(|| {
        if let Some(g) = from_init_handle(h) {
            if biome_addr == 0 || primer_out_addr == 0 { return; }
            let biomes = std::slice::from_raw_parts(biome_addr as *const f32, 200);
            let primer = &mut *(primer_out_addr as *mut [u16; 65536]);
            worldgen_noise::terrain::generate_terrain_fused(
                g, x4, z4, biomes, sea_level, stone_id as u16, water_id as u16, primer,
            );
        }
    }));
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_rustcraft_interop_WNoiseInterop_terrainSetBlocksCritical(
    env: *mut c_void,
    _cls: *mut c_void,
    density_addr: i64,
    sea_level: i32,
    stone_id: i32,
    water_id: i32,
    output_array: *mut c_void,
) {
    GLOBAL_FFI_METRICS.record_call(243);
    if density_addr == 0 || output_array.is_null() || env.is_null() { return; }
    let table_pp = env as *const *const *const c_void;
    if table_pp.is_null() || (*table_pp).is_null() { return; }
    let table = *table_pp;
    let get_critical: unsafe extern "system" fn(*mut c_void, *mut c_void, *mut c_void) -> *mut c_void =
        std::mem::transmute(*table.add(222));
    let release_critical: unsafe extern "system" fn(*mut c_void, *mut c_void, *mut c_void, i32) =
        std::mem::transmute(*table.add(223));

    let ptr = get_critical(env, output_array, std::ptr::null_mut());
    if ptr.is_null() { return; }

    let density = &*(density_addr as *const [f64; 825]);
    let primer = &mut *(ptr as *mut [u16; 65536]);
    worldgen_noise::terrain::set_blocks_in_chunk_column_major(
        density, sea_level, stone_id as u16, water_id as u16, primer,
    );

    release_critical(env, output_array, ptr, 0);
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_rustcraft_bridge_WorldgenShadow_terrainSetBlocks(
    _env: *mut c_void, _cls: *mut c_void,
    density_addr: i64, sea_level: i32, stone_id: i32, water_id: i32, primer_out_addr: i64,
) {
    Java_com_rustcraft_interop_WNoiseInterop_terrainSetBlocks(
        _env, _cls, density_addr, sea_level, stone_id, water_id, primer_out_addr,
    )
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_rustcraft_bridge_WorldgenShadow_terrainSetBlocksOpt(
    _env: *mut c_void, _cls: *mut c_void,
    density_addr: i64, sea_level: i32, stone_id: i32, water_id: i32, primer_out_addr: i64,
) {
    Java_com_rustcraft_interop_WNoiseInterop_terrainSetBlocksOpt(
        _env, _cls, density_addr, sea_level, stone_id, water_id, primer_out_addr,
    )
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_rustcraft_bridge_WorldgenShadow_terrainComplete(
    _env: *mut c_void, _cls: *mut c_void, h: *mut c_void,
    x4: i32, z4: i32, biome_addr: i64, sea_level: i32, stone_id: i32, water_id: i32, primer_out_addr: i64,
) {
    Java_com_rustcraft_interop_WNoiseInterop_terrainComplete(
        _env, _cls, h, x4, z4, biome_addr, sea_level, stone_id, water_id, primer_out_addr,
    )
}
