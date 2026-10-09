use crate::{protocol, AdapterError, Version, VersionMetadata};
pub struct Mc1_12_2;
impl crate::sealed::Sealed for Mc1_12_2 {}
impl Version for Mc1_12_2 {
    const METADATA: VersionMetadata = VersionMetadata {
        release: "1.12.2",
        protocol: 340,
        data_version: 1343,
    };
    fn encode_keepalive(value: i64, out: &mut [u8]) -> Result<usize, AdapterError> {
        protocol::encode_long_keepalive(value, out)
    }
    fn decode_keepalive(input: &[u8]) -> Result<i64, AdapterError> {
        protocol::decode_long_keepalive(input)
    }
}
