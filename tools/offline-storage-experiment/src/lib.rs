//! Generated offline records only. No MCA/NBT storage or runtime authority.
#![deny(unsafe_op_in_unsafe_fn)]
pub mod sidecar;
pub mod snapshot;
use sha2::{Digest, Sha256};
use std::io::{self, Read};

pub const HEADER: usize = 64;
pub const RECORD: usize = 32;
pub const MAX_RECORDS: usize = 131_072;
pub const MAX_BYTES: usize = HEADER + MAX_RECORDS * RECORD;
pub const SCHEMA: u32 = 1;
pub type AnyResult<T> = Result<T, Box<dyn std::error::Error>>;
pub fn invalid(message: &str) -> io::Error {
    io::Error::new(io::ErrorKind::InvalidData, message)
}
pub fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Header {
    pub count: usize,
    pub epoch: u64,
    pub seed: u64,
}
impl Header {
    pub fn parse(bytes: &[u8], length: usize) -> io::Result<Self> {
        if bytes.len() != HEADER
            || &bytes[..8] != b"RCSTOR01"
            || u32::from_le_bytes(bytes[8..12].try_into().unwrap()) != SCHEMA
            || u32::from_le_bytes(bytes[12..16].try_into().unwrap()) != RECORD as u32
            || bytes[40..].iter().any(|b| *b != 0)
        {
            return Err(invalid("header schema"));
        }
        let count = u64::from_le_bytes(bytes[16..24].try_into().unwrap());
        let epoch = u64::from_le_bytes(bytes[24..32].try_into().unwrap());
        let seed = u64::from_le_bytes(bytes[32..40].try_into().unwrap());
        if count == 0
            || count > MAX_RECORDS as u64
            || epoch == 0
            || length != HEADER + count as usize * RECORD
        {
            return Err(invalid("header bounds"));
        }
        Ok(Self {
            count: count as usize,
            epoch,
            seed,
        })
    }
}
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Entry {
    pub key: u64,
    pub offset: u64,
    pub value: i64,
    pub group: u32,
    pub flags: u32,
    pub version: u64,
}
impl Entry {
    pub fn parse(bytes: &[u8], position: usize) -> io::Result<Self> {
        if bytes.len() != RECORD {
            return Err(invalid("record length"));
        }
        let e = Self {
            key: u64::from_le_bytes(bytes[..8].try_into().unwrap()),
            offset: (HEADER + position * RECORD) as u64,
            value: i64::from_le_bytes(bytes[8..16].try_into().unwrap()),
            group: u32::from_le_bytes(bytes[16..20].try_into().unwrap()),
            flags: u32::from_le_bytes(bytes[20..24].try_into().unwrap()),
            version: u64::from_le_bytes(bytes[24..32].try_into().unwrap()),
        };
        if e.group > 255 || e.flags > 15 || e.version == 0 {
            return Err(invalid("record domain"));
        }
        Ok(e)
    }
    pub fn encode_value(&self) -> [u8; 32] {
        let mut out = [0; 32];
        out[..8].copy_from_slice(&self.offset.to_le_bytes());
        out[8..16].copy_from_slice(&self.value.to_le_bytes());
        out[16..20].copy_from_slice(&self.group.to_le_bytes());
        out[20..24].copy_from_slice(&self.flags.to_le_bytes());
        out[24..].copy_from_slice(&self.version.to_le_bytes());
        out
    }
    pub fn decode_value(key: u64, value: &[u8]) -> io::Result<Self> {
        if value.len() != 32 {
            return Err(invalid("sidecar value length"));
        }
        Ok(Self {
            key,
            offset: u64::from_le_bytes(value[..8].try_into().unwrap()),
            value: i64::from_le_bytes(value[8..16].try_into().unwrap()),
            group: u32::from_le_bytes(value[16..20].try_into().unwrap()),
            flags: u32::from_le_bytes(value[20..24].try_into().unwrap()),
            version: u64::from_le_bytes(value[24..].try_into().unwrap()),
        })
    }
}
pub fn finish_index(header: &Header, mut entries: Vec<Entry>) -> io::Result<Vec<Entry>> {
    if entries.len() != header.count {
        return Err(invalid("index count"));
    }
    entries.sort_unstable_by_key(|e| e.key);
    if entries.iter().enumerate().any(|(i, e)| e.key != i as u64) {
        return Err(invalid("duplicate/missing/out-of-range key"));
    }
    Ok(entries)
}
pub fn index_hash(entries: &[Entry]) -> [u8; 32] {
    let mut h = Sha256::new();
    for e in entries {
        h.update(e.key.to_le_bytes());
        h.update(e.encode_value());
    }
    h.finalize().into()
}
pub fn scan_bytes(bytes: &[u8]) -> io::Result<(Header, Vec<Entry>)> {
    let h = Header::parse(
        bytes
            .get(..HEADER)
            .ok_or_else(|| invalid("truncated header"))?,
        bytes.len(),
    )?;
    // Match the buffered scanner's allocation policy: compare access paths,
    // not fallible-iterator Vec growth against a preallocated Vec.
    let mut entries = Vec::with_capacity(h.count);
    for (i, bytes) in bytes[HEADER..].as_chunks::<RECORD>().0.iter().enumerate() {
        entries.push(Entry::parse(bytes, i)?);
    }
    Ok((h, entries))
}
pub fn scan_reader(mut reader: impl Read, length: usize) -> io::Result<(Header, Vec<Entry>)> {
    let mut head = [0; HEADER];
    reader.read_exact(&mut head)?;
    let h = Header::parse(&head, length)?;
    let mut entries = Vec::with_capacity(h.count);
    let mut data = [0; RECORD];
    for i in 0..h.count {
        reader.read_exact(&mut data)?;
        entries.push(Entry::parse(&data, i)?)
    }
    let mut trailing = [0];
    if reader.read(&mut trailing)? != 0 {
        return Err(invalid("trailing data"));
    }
    Ok((h, entries))
}

#[cfg(test)]
mod tests;
