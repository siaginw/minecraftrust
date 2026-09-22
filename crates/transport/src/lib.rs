//! Transport and packet framing abstractions for Protocol 340.

use buffers::NativeBuffer;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ConnectionState {
    Handshake,
    Status,
    Login,
    Play,
}

pub trait PacketFraming {
    fn frame_packet(&self, packet_id: i32, payload: &[u8]) -> NativeBuffer;
    fn unframe_packet(&self, raw: &[u8]) -> Result<(i32, Vec<u8>), &'static str>;
}
