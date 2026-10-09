use jni::{objects::JObject, Env};

// Same operations, but return an owned scalar rather than a frame-local reference.
pub fn valid<'caller>(env: &Env<'caller>, object: &JObject<'caller>) -> jni::errors::Result<bool> {
    env.with_local_frame(1, |frame| {
        let local = frame.new_local_ref(object)?;
        frame.is_same_object(&local, object)
    })
}
