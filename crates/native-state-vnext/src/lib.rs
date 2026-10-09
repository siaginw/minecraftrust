//! Retained-state foundation only. No JNI or Minecraft ownership integration.
#![forbid(unsafe_code)]

pub mod section;
pub mod store;

#[cfg(test)]
extern crate self as native_state_vnext;
#[cfg(test)]
#[path = "../tests/common/mod.rs"]
mod test_helpers;

pub const fn production_authority_enabled() -> bool {
    false
}
