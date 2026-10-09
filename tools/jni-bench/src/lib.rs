#![allow(non_camel_case_types, static_mut_refs)]

use std::ffi::c_void;

pub type JNIEnv = *const *const JNINativeInterface;
pub type jobject = *mut c_void;
pub type jclass = *mut c_void;
pub type jbyteArray = *mut c_void;
pub type jlong = i64;
pub type jint = i32;
pub type jbyte = i8;
pub type jboolean = u8;

#[repr(C)]
pub struct JNINativeInterface {
    pub funcs: [*const c_void; 233],
}

impl JNINativeInterface {
    #[inline(always)]
    pub unsafe fn get_byte_array_region(
        &self,
        env: JNIEnv,
        array: jbyteArray,
        start: jint,
        len: jint,
        buf: *mut jbyte,
    ) {
        let f: extern "system" fn(JNIEnv, jbyteArray, jint, jint, *mut jbyte) =
            std::mem::transmute(self.funcs[200]);
        f(env, array, start, len, buf);
    }

    #[inline(always)]
    pub unsafe fn get_primitive_array_critical(
        &self,
        env: JNIEnv,
        array: jobject,
        is_copy: *mut jboolean,
    ) -> *mut c_void {
        let f: extern "system" fn(JNIEnv, jobject, *mut jboolean) -> *mut c_void =
            std::mem::transmute(self.funcs[222]);
        f(env, array, is_copy)
    }

    #[inline(always)]
    pub unsafe fn release_primitive_array_critical(
        &self,
        env: JNIEnv,
        array: jobject,
        carray: *mut c_void,
        mode: jint,
    ) {
        let f: extern "system" fn(JNIEnv, jobject, *mut c_void, jint) =
            std::mem::transmute(self.funcs[223]);
        f(env, array, carray, mode);
    }

    #[inline(always)]
    pub unsafe fn new_direct_byte_buffer(
        &self,
        env: JNIEnv,
        address: *mut c_void,
        capacity: jlong,
    ) -> jobject {
        let f: extern "system" fn(JNIEnv, *mut c_void, jlong) -> jobject =
            std::mem::transmute(self.funcs[229]);
        f(env, address, capacity)
    }

    #[inline(always)]
    pub unsafe fn get_direct_buffer_address(&self, env: JNIEnv, buf: jobject) -> *mut c_void {
        let f: extern "system" fn(JNIEnv, jobject) -> *mut c_void =
            std::mem::transmute(self.funcs[230]);
        f(env, buf)
    }

    #[inline(always)]
    pub unsafe fn get_direct_buffer_capacity(&self, env: JNIEnv, buf: jobject) -> jlong {
        let f: extern "system" fn(JNIEnv, jobject) -> jlong =
            std::mem::transmute(self.funcs[231]);
        f(env, buf)
    }
}

// Global reuse buffer for target copying
static mut GLOBAL_TARGET_BUFFER: [u8; 1024 * 1024 * 4] = [0u8; 1024 * 1024 * 4];

#[no_mangle]
pub unsafe extern "system" fn Java_com_rustcraft_bench_JniBench_noopCall(
    _env: JNIEnv,
    _class: jclass,
) -> jlong {
    42
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_rustcraft_bench_JniBench_benchByteArrayRegion(
    env: JNIEnv,
    _class: jclass,
    arr: jbyteArray,
    len: jint,
) -> jlong {
    let iface = &**env;
    let size = (len as usize).min(GLOBAL_TARGET_BUFFER.len());
    iface.get_byte_array_region(
        env,
        arr,
        0,
        size as jint,
        GLOBAL_TARGET_BUFFER.as_mut_ptr() as *mut jbyte,
    );
    // Touch every byte of copied buffer to guarantee full transfer checksum
    let slice = &GLOBAL_TARGET_BUFFER[..size];
    let mut sum: jlong = 0;
    for &b in slice {
        sum = sum.wrapping_add(b as jlong);
    }
    sum
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_rustcraft_bench_JniBench_benchByteArrayCritical(
    env: JNIEnv,
    _class: jclass,
    arr: jobject,
    len: jint,
) -> jlong {
    let iface = &**env;
    let ptr = iface.get_primitive_array_critical(env, arr, std::ptr::null_mut());
    if ptr.is_null() {
        return 0;
    }
    let slice = std::slice::from_raw_parts(ptr as *const u8, len as usize);
    let mut sum: jlong = 0;
    // Touch every byte to guarantee full memory scan
    for &b in slice {
        sum = sum.wrapping_add(b as jlong);
    }
    iface.release_primitive_array_critical(env, arr, ptr, 0);
    sum
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_rustcraft_bench_JniBench_benchDirectByteBuffer(
    env: JNIEnv,
    _class: jclass,
    buf: jobject,
    len: jint,
) -> jlong {
    let iface = &**env;
    let ptr = iface.get_direct_buffer_address(env, buf);
    if ptr.is_null() {
        return 0;
    }
    let slice = std::slice::from_raw_parts(ptr as *const u8, len as usize);
    let mut sum: jlong = 0;
    // Touch every byte to guarantee full memory scan
    for &b in slice {
        sum = sum.wrapping_add(b as jlong);
    }
    sum
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_rustcraft_bench_JniBench_benchRustAllocDirectBuffer(
    env: JNIEnv,
    _class: jclass,
    size: jint,
) -> jobject {
    let iface = &**env;
    let ptr = GLOBAL_TARGET_BUFFER.as_mut_ptr() as *mut c_void;
    iface.new_direct_byte_buffer(env, ptr, size as jlong)
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_rustcraft_bench_JniBench_benchPersistentHandle(
    _env: JNIEnv,
    _class: jclass,
    handle: jlong,
    offset: jint,
    len: jint,
) -> jlong {
    let ptr = (handle as *const u8).add(offset as usize);
    let slice = std::slice::from_raw_parts(ptr, len as usize);
    let mut sum: jlong = 0;
    // Touch every byte to guarantee full memory scan
    for &b in slice {
        sum = sum.wrapping_add(b as jlong);
    }
    sum
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_rustcraft_bench_JniBench_getNativeHandle(
    _env: JNIEnv,
    _class: jclass,
) -> jlong {
    GLOBAL_TARGET_BUFFER.as_ptr() as jlong
}
