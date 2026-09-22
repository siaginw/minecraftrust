use std::collections::HashMap;
use std::io::{Cursor, Read};
use crate::mutf8::{decode_mutf8, encode_mutf8};

pub const MAX_DEPTH: usize = 512;

#[derive(Debug, Clone, PartialEq)]
pub enum NbtTag {
    End,
    Byte(i8),
    Short(i16),
    Int(i32),
    Long(i64),
    Float(f32),
    Double(f64),
    ByteArray(Vec<u8>),
    String(String),
    List(Vec<NbtTag>),
    Compound(HashMap<String, NbtTag>),
    IntArray(Vec<i32>),
    LongArray(Vec<i64>),
}

impl NbtTag {
    pub fn type_id(&self) -> u8 {
        match self {
            NbtTag::End => 0,
            NbtTag::Byte(_) => 1,
            NbtTag::Short(_) => 2,
            NbtTag::Int(_) => 3,
            NbtTag::Long(_) => 4,
            NbtTag::Float(_) => 5,
            NbtTag::Double(_) => 6,
            NbtTag::ByteArray(_) => 7,
            NbtTag::String(_) => 8,
            NbtTag::List(_) => 9,
            NbtTag::Compound(_) => 10,
            NbtTag::IntArray(_) => 11,
            NbtTag::LongArray(_) => 12,
        }
    }

    pub fn type_name(id: u8) -> &'static str {
        match id {
            0 => "TAG_End",
            1 => "TAG_Byte",
            2 => "TAG_Short",
            3 => "TAG_Int",
            4 => "TAG_Long",
            5 => "TAG_Float",
            6 => "TAG_Double",
            7 => "TAG_Byte_Array",
            8 => "TAG_String",
            9 => "TAG_List",
            10 => "TAG_Compound",
            11 => "TAG_Int_Array",
            12 => "TAG_Long_Array",
            99 => "Any Numeric Tag",
            _ => "UNKNOWN",
        }
    }

    pub fn as_compound(&self) -> Option<&HashMap<String, NbtTag>> {
        match self {
            NbtTag::Compound(m) => Some(m),
            _ => None,
        }
    }

    pub fn as_compound_mut(&mut self) -> Option<&mut HashMap<String, NbtTag>> {
        match self {
            NbtTag::Compound(m) => Some(m),
            _ => None,
        }
    }

    pub fn as_list(&self) -> Option<&[NbtTag]> {
        match self {
            NbtTag::List(l) => Some(l),
            _ => None,
        }
    }

    pub fn as_str(&self) -> Option<&str> {
        match self {
            NbtTag::String(s) => Some(s),
            _ => None,
        }
    }

    pub fn as_i32(&self) -> Option<i32> {
        match self {
            NbtTag::Byte(b) => Some(*b as i32),
            NbtTag::Short(s) => Some(*s as i32),
            NbtTag::Int(i) => Some(*i),
            NbtTag::Long(l) => Some(*l as i32),
            _ => None,
        }
    }

    pub fn as_i64(&self) -> Option<i64> {
        match self {
            NbtTag::Byte(b) => Some(*b as i64),
            NbtTag::Short(s) => Some(*s as i64),
            NbtTag::Int(i) => Some(*i as i64),
            NbtTag::Long(l) => Some(*l),
            _ => None,
        }
    }
}

#[derive(Debug, PartialEq, Eq)]
pub enum NbtError {
    UnexpectedEof,
    InvalidTypeId(u8),
    InvalidListType(u8),
    NegativeLength(i32),
    DepthExceeded(usize),
    InvalidString(String),
    IoError(String),
    MissingRootCompound,
}

impl std::fmt::Display for NbtError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{:?}", self)
    }
}

impl std::error::Error for NbtError {}

pub struct NbtDecoder;

impl NbtDecoder {
    /// Decodes a root NBT compound, returning (root_name, root_tag).
    pub fn decode(bytes: &[u8]) -> Result<(String, NbtTag), NbtError> {
        let mut cur = Cursor::new(bytes);
        let mut type_byte = [0u8; 1];
        if cur.read_exact(&mut type_byte).is_err() {
            return Err(NbtError::UnexpectedEof);
        }
        if type_byte[0] != 10 {
            return Err(NbtError::MissingRootCompound);
        }
        let root_name = read_string(&mut cur)?;
        let root_compound = read_compound(&mut cur, 0)?;
        Ok((root_name, root_compound))
    }

    /// Decodes a tag payload given its type ID and current recursion depth.
    pub fn decode_payload(cur: &mut Cursor<&[u8]>, type_id: u8, depth: usize) -> Result<NbtTag, NbtError> {
        if depth > MAX_DEPTH {
            return Err(NbtError::DepthExceeded(depth));
        }
        match type_id {
            0 => Ok(NbtTag::End),
            1 => {
                let mut buf = [0u8; 1];
                cur.read_exact(&mut buf).map_err(|_| NbtError::UnexpectedEof)?;
                Ok(NbtTag::Byte(buf[0] as i8))
            }
            2 => {
                let mut buf = [0u8; 2];
                cur.read_exact(&mut buf).map_err(|_| NbtError::UnexpectedEof)?;
                Ok(NbtTag::Short(i16::from_be_bytes(buf)))
            }
            3 => {
                let mut buf = [0u8; 4];
                cur.read_exact(&mut buf).map_err(|_| NbtError::UnexpectedEof)?;
                Ok(NbtTag::Int(i32::from_be_bytes(buf)))
            }
            4 => {
                let mut buf = [0u8; 8];
                cur.read_exact(&mut buf).map_err(|_| NbtError::UnexpectedEof)?;
                Ok(NbtTag::Long(i64::from_be_bytes(buf)))
            }
            5 => {
                let mut buf = [0u8; 4];
                cur.read_exact(&mut buf).map_err(|_| NbtError::UnexpectedEof)?;
                Ok(NbtTag::Float(f32::from_be_bytes(buf)))
            }
            6 => {
                let mut buf = [0u8; 8];
                cur.read_exact(&mut buf).map_err(|_| NbtError::UnexpectedEof)?;
                Ok(NbtTag::Double(f64::from_be_bytes(buf)))
            }
            7 => {
                let mut len_buf = [0u8; 4];
                cur.read_exact(&mut len_buf).map_err(|_| NbtError::UnexpectedEof)?;
                let len = i32::from_be_bytes(len_buf);
                if len < 0 {
                    return Err(NbtError::NegativeLength(len));
                }
                let mut data = vec![0u8; len as usize];
                cur.read_exact(&mut data).map_err(|_| NbtError::UnexpectedEof)?;
                Ok(NbtTag::ByteArray(data))
            }
            8 => {
                let s = read_string(cur)?;
                Ok(NbtTag::String(s))
            }
            9 => {
                let mut elem_type_buf = [0u8; 1];
                cur.read_exact(&mut elem_type_buf).map_err(|_| NbtError::UnexpectedEof)?;
                let elem_type = elem_type_buf[0];

                let mut len_buf = [0u8; 4];
                cur.read_exact(&mut len_buf).map_err(|_| NbtError::UnexpectedEof)?;
                let len = i32::from_be_bytes(len_buf);
                if len < 0 {
                    return Err(NbtError::NegativeLength(len));
                }
                if len > 0 && elem_type == 0 {
                    return Err(NbtError::InvalidListType(0));
                }
                let mut list = Vec::with_capacity(len as usize);
                for _ in 0..len {
                    list.push(Self::decode_payload(cur, elem_type, depth + 1)?);
                }
                Ok(NbtTag::List(list))
            }
            10 => read_compound(cur, depth),
            11 => {
                let mut len_buf = [0u8; 4];
                cur.read_exact(&mut len_buf).map_err(|_| NbtError::UnexpectedEof)?;
                let len = i32::from_be_bytes(len_buf);
                if len < 0 {
                    return Err(NbtError::NegativeLength(len));
                }
                let mut ints = Vec::with_capacity(len as usize);
                let mut buf = [0u8; 4];
                for _ in 0..len {
                    cur.read_exact(&mut buf).map_err(|_| NbtError::UnexpectedEof)?;
                    ints.push(i32::from_be_bytes(buf));
                }
                Ok(NbtTag::IntArray(ints))
            }
            12 => {
                let mut len_buf = [0u8; 4];
                cur.read_exact(&mut len_buf).map_err(|_| NbtError::UnexpectedEof)?;
                let len = i32::from_be_bytes(len_buf);
                if len < 0 {
                    return Err(NbtError::NegativeLength(len));
                }
                let mut longs = Vec::with_capacity(len as usize);
                let mut buf = [0u8; 8];
                for _ in 0..len {
                    cur.read_exact(&mut buf).map_err(|_| NbtError::UnexpectedEof)?;
                    longs.push(i64::from_be_bytes(buf));
                }
                Ok(NbtTag::LongArray(longs))
            }
            _ => Err(NbtError::InvalidTypeId(type_id)),
        }
    }
}

fn read_string(cur: &mut Cursor<&[u8]>) -> Result<String, NbtError> {
    let mut len_buf = [0u8; 2];
    cur.read_exact(&mut len_buf).map_err(|_| NbtError::UnexpectedEof)?;
    let len = u16::from_be_bytes(len_buf) as usize;
    let pos = cur.position() as usize;
    let slice = cur.get_ref();
    if pos + len > slice.len() {
        return Err(NbtError::UnexpectedEof);
    }
    let raw_bytes = &slice[pos..pos + len];
    cur.set_position((pos + len) as u64);
    decode_mutf8(raw_bytes).map_err(|e| NbtError::InvalidString(format!("{:?}", e)))
}

fn read_compound(cur: &mut Cursor<&[u8]>, depth: usize) -> Result<NbtTag, NbtError> {
    if depth > MAX_DEPTH {
        return Err(NbtError::DepthExceeded(depth));
    }
    let mut map = HashMap::new();
    let mut type_buf = [0u8; 1];
    loop {
        cur.read_exact(&mut type_buf).map_err(|_| NbtError::UnexpectedEof)?;
        let tag_type = type_buf[0];
        if tag_type == 0 {
            break;
        }
        let key = read_string(cur)?;
        let tag = NbtDecoder::decode_payload(cur, tag_type, depth + 1)?;
        map.insert(key, tag);
    }
    Ok(NbtTag::Compound(map))
}

pub struct NbtEncoder;

impl NbtEncoder {
    pub fn encode(root_name: &str, root: &NbtTag, out: &mut Vec<u8>) -> Result<(), NbtError> {
        match root {
            NbtTag::Compound(_) => {
                out.push(10); // TAG_Compound ID
                write_string(root_name, out);
                write_compound_payload(root, out, 0)?;
                Ok(())
            }
            _ => Err(NbtError::MissingRootCompound),
        }
    }

    pub fn encode_payload(tag: &NbtTag, out: &mut Vec<u8>, depth: usize) -> Result<(), NbtError> {
        if depth > MAX_DEPTH {
            return Err(NbtError::DepthExceeded(depth));
        }
        match tag {
            NbtTag::End => Ok(()),
            NbtTag::Byte(b) => {
                out.push(*b as u8);
                Ok(())
            }
            NbtTag::Short(s) => {
                out.extend_from_slice(&s.to_be_bytes());
                Ok(())
            }
            NbtTag::Int(i) => {
                out.extend_from_slice(&i.to_be_bytes());
                Ok(())
            }
            NbtTag::Long(l) => {
                out.extend_from_slice(&l.to_be_bytes());
                Ok(())
            }
            NbtTag::Float(f) => {
                out.extend_from_slice(&f.to_be_bytes());
                Ok(())
            }
            NbtTag::Double(d) => {
                out.extend_from_slice(&d.to_be_bytes());
                Ok(())
            }
            NbtTag::ByteArray(bytes) => {
                let len = bytes.len() as i32;
                out.extend_from_slice(&len.to_be_bytes());
                out.extend_from_slice(bytes);
                Ok(())
            }
            NbtTag::String(s) => {
                write_string(s, out);
                Ok(())
            }
            NbtTag::List(list) => {
                if list.is_empty() {
                    out.push(0); // TAG_End element type
                    out.extend_from_slice(&0i32.to_be_bytes());
                } else {
                    let elem_type = list[0].type_id();
                    out.push(elem_type);
                    let len = list.len() as i32;
                    out.extend_from_slice(&len.to_be_bytes());
                    for elem in list {
                        if elem.type_id() != elem_type {
                            return Err(NbtError::InvalidListType(elem.type_id()));
                        }
                        Self::encode_payload(elem, out, depth + 1)?;
                    }
                }
                Ok(())
            }
            NbtTag::Compound(_) => write_compound_payload(tag, out, depth),
            NbtTag::IntArray(ints) => {
                let len = ints.len() as i32;
                out.extend_from_slice(&len.to_be_bytes());
                for i in ints {
                    out.extend_from_slice(&i.to_be_bytes());
                }
                Ok(())
            }
            NbtTag::LongArray(longs) => {
                let len = longs.len() as i32;
                out.extend_from_slice(&len.to_be_bytes());
                for l in longs {
                    out.extend_from_slice(&l.to_be_bytes());
                }
                Ok(())
            }
        }
    }
}

fn write_string(s: &str, out: &mut Vec<u8>) {
    let mut str_bytes = Vec::with_capacity(s.len());
    encode_mutf8(s, &mut str_bytes);
    let len = str_bytes.len().min(65535) as u16;
    out.extend_from_slice(&len.to_be_bytes());
    out.extend_from_slice(&str_bytes[..len as usize]);
}

fn write_compound_payload(compound: &NbtTag, out: &mut Vec<u8>, depth: usize) -> Result<(), NbtError> {
    if depth > MAX_DEPTH {
        return Err(NbtError::DepthExceeded(depth));
    }
    if let NbtTag::Compound(map) = compound {
        // Sort keys deterministically for canonical encoding
        let mut keys: Vec<&String> = map.keys().collect();
        keys.sort();
        for key in keys {
            let tag = &map[key];
            out.push(tag.type_id());
            write_string(key, out);
            NbtEncoder::encode_payload(tag, out, depth + 1)?;
        }
        out.push(0); // TAG_End
        Ok(())
    } else {
        Err(NbtError::MissingRootCompound)
    }
}
