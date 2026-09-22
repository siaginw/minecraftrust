//! Protocol 340 (Minecraft 1.12.2) wire primitives and codec foundation.
//!
//! Provides exact big-endian wire encoding and decoding matching Java Netty
//! `PacketBuffer` semantics:
//! - `VarInt` (1..=5 bytes) & `VarLong` (1..=10 bytes)
//! - Big-endian fixed primitives (`bool`, `u8`, `i8`, `i16`, `i32`, `i64`, `f32`, `f64`)
//! - `Position` (BlockPos packed into 64-bit big-endian integer)
//! - `WireUuid` (two 64-bit big-endian integers: MSB, LSB)
//! - `ProtocolString` (VarInt length-prefixed UTF-8, 32767 cap)
//! - `WireSlot` (ItemStack format with short ID)

pub mod error;
pub mod codec;

pub use error::ProtocolError;
pub use codec::*;
