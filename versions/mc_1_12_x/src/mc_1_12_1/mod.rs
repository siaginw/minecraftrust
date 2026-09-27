use crate::{protocol, AdapterError, Version, VersionMetadata};
pub struct Mc1_12_1;
impl crate::sealed::Sealed for Mc1_12_1 {}
impl Version for Mc1_12_1 {
    const METADATA: VersionMetadata = VersionMetadata {
        release: "1.12.1",
        protocol: 338,
        data_version: 1241,
    };
    fn encode_keepalive(value: i64, out: &mut [u8]) -> Result<usize, AdapterError> {
        protocol::encode_varint_keepalive(value, out)
    }
    fn decode_keepalive(input: &[u8]) -> Result<i64, AdapterError> {
        protocol::decode_varint_keepalive(input)
    }
}
