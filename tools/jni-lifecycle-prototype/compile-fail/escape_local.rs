use jni::{objects::JObject, Env};

// This must fail: a child-frame local cannot escape to the caller's frame.
pub fn escape<'caller>(env: &Env<'caller>, object: &JObject<'caller>) -> jni::errors::Result<JObject<'caller>> {
    env.with_local_frame(1, |frame| frame.new_local_ref(object))
}
