//! Isolated lifecycle experiment; no production RustCraft exports or authority.
use jni::{
    errors::{Error, ThrowRuntimeExAndDefault},
    jni_sig, jni_str,
    objects::{JClass, JObject},
    refs::Global,
    EnvUnowned,
};
use std::sync::Mutex;

static HELD: Mutex<Option<Global<JObject<'static>>>> = Mutex::new(None);

#[no_mangle]
pub extern "system" fn Java_LifecycleProbe_ping<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
) -> i32 {
    env.with_env(|_| -> jni::errors::Result<i32> { Ok(42) })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[no_mangle]
pub extern "system" fn Java_LifecycleProbe_localRefs<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    object: JObject<'local>,
    rounds: i32,
) -> i32 {
    env.with_env(|env| -> jni::errors::Result<i32> {
        if !(0..=10_000).contains(&rounds) {
            return Ok(-1);
        }
        let mut observed = 0;
        for _ in 0..rounds {
            observed += env.with_local_frame(8, |frame| -> jni::errors::Result<i32> {
                let mut same = 0;
                for _ in 0..8 {
                    let local = frame.new_local_ref(&object)?;
                    same += i32::from(frame.is_same_object(&local, &object)?);
                }
                Ok(same)
            })?;
        }
        Ok(observed)
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

#[no_mangle]
pub extern "system" fn Java_LifecycleProbe_callback<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    object: JObject<'local>,
    mode: i32,
) -> i32 {
    env.with_env(|env| -> jni::errors::Result<i32> {
        match mode {
            0 => env
                .call_method(&object, jni_str!("value"), jni_sig!("()I"), &[])?
                .i(),
            1 => env
                .call_method(&object, jni_str!("fail"), jni_sig!("()I"), &[])?
                .i(),
            2 => panic!("controlled JNI lifecycle panic"),
            3 => env
                .call_method(&object, jni_str!("value"), jni_sig!("()J"), &[])?
                .j()
                .map(|n| n as i32),
            _ => Ok(-1),
        }
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

#[no_mangle]
pub extern "system" fn Java_LifecycleProbe_worker<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    object: JObject<'local>,
    fail: u8,
) -> i32 {
    env.with_env(|env| -> jni::errors::Result<i32> {
        let global = env.new_global_ref(&object)?;
        let vm = env.get_java_vm()?;
        let result = std::thread::spawn(move || -> jni::errors::Result<i32> {
            assert!(!vm.is_thread_attached()?);
            let result = vm.attach_current_thread_for_scope(|worker| {
                let name = if fail == 0 {
                    jni_str!("value")
                } else {
                    jni_str!("fail")
                };
                let result = worker
                    .call_method(&global, name, jni_sig!("()I"), &[])
                    .and_then(|v| v.i());
                // Delete our global while attached, including the exception path.
                drop(global);
                result
            });
            assert!(!vm.is_thread_attached()?);
            result
        })
        .join()
        .expect("bounded lifecycle worker panicked");
        match result {
            // Preserve the actual Throwable; the default string-based error policy
            // alone would erase callback exception identity across native threads.
            Err(Error::CaughtJavaException { exception, .. }) => env.throw(&exception).map(|()| 0),
            result => result,
        }
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

#[no_mangle]
pub extern "system" fn Java_LifecycleProbe_sameClass<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    object: JObject<'local>,
    expected: JClass<'local>,
) -> u8 {
    env.with_env(|env| -> jni::errors::Result<u8> {
        let actual = env.get_object_class(&object)?;
        Ok(u8::from(env.is_same_object(&actual, &expected)?))
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

#[no_mangle]
pub extern "system" fn Java_LifecycleProbe_retain<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    object: JObject<'local>,
) -> i32 {
    env.with_env(|env| -> jni::errors::Result<i32> {
        let mut held = HELD.lock().expect("retained reference mutex poisoned");
        if held.is_some() {
            return Ok(-1);
        }
        *held = Some(env.new_global_ref(&object)?);
        Ok(1)
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

#[no_mangle]
pub extern "system" fn Java_LifecycleProbe_release<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
) -> i32 {
    env.with_env(|_| -> jni::errors::Result<i32> {
        let old = HELD
            .lock()
            .expect("retained reference mutex poisoned")
            .take();
        let present = i32::from(old.is_some());
        drop(old);
        Ok(present)
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}
