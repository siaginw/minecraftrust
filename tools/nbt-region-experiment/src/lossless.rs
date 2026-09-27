//! Bounded structural parser. Raw MUTF-8 and uninterpreted payloads are retained.
pub const MAX_RAW: usize = 2 * 1024 * 1024;
pub const MAX_DEPTH: usize = 64;
pub const MAX_NODES: usize = 200_000;
pub const MAX_SEQUENCE: usize = 1_000_000;

// Match the Java DataInput UTF-16 decoding of ASCII field identifiers, including
// accepted overlong encodings. Preserve original bytes; never normalize them.
fn ascii_identity(raw: &[u8]) -> Option<Vec<u8>> {
    let mut out = Vec::new();
    let mut i = 0;
    while i < raw.len() {
        let first = raw[i];
        let (value, width) = if first < 128 {
            (u16::from(first), 1)
        } else if first & 0xe0 == 0xc0 {
            let second = *raw.get(i + 1)?;
            if second & 0xc0 != 0x80 {
                return None;
            }
            ((u16::from(first & 31) << 6) | u16::from(second & 63), 2)
        } else if first & 0xf0 == 0xe0 {
            let second = *raw.get(i + 1)?;
            let third = *raw.get(i + 2)?;
            if second & 0xc0 != 0x80 || third & 0xc0 != 0x80 {
                return None;
            }
            (
                (u16::from(first & 15) << 12)
                    | (u16::from(second & 63) << 6)
                    | u16::from(third & 63),
                3,
            )
        } else {
            return None;
        };
        if value > 127 {
            return None;
        }
        out.push(value as u8);
        i += width;
    }
    Some(out)
}

#[derive(Debug)]
pub struct Scan {
    pub edit_offset: usize,
    pub old_value: i64,
    pub root_name: Vec<u8>,
    pub nodes: usize,
    pub data_version: Option<i32>,
    pub max_neid: Option<u32>,
}

struct Reader<'a> {
    bytes: &'a [u8],
    pos: usize,
    nodes: usize,
    levels: usize,
    edits: Vec<usize>,
    data_version: Option<i32>,
    max_neid: Option<u32>,
}
impl<'a> Reader<'a> {
    fn take(&mut self, length: usize) -> Result<&'a [u8], String> {
        let end = self.pos.checked_add(length).ok_or("length overflow")?;
        let slice = self.bytes.get(self.pos..end).ok_or("truncated payload")?;
        self.pos = end;
        Ok(slice)
    }
    fn byte(&mut self) -> Result<u8, String> {
        Ok(self.take(1)?[0])
    }
    fn name(&mut self) -> Result<&'a [u8], String> {
        let len = u16::from_be_bytes(self.take(2)?.try_into().unwrap()) as usize;
        self.take(len)
    }
    fn length(&mut self) -> Result<usize, String> {
        let n = i32::from_be_bytes(self.take(4)?.try_into().unwrap());
        if n < 0 || n as usize > MAX_SEQUENCE {
            return Err("sequence limit/negative length".into());
        }
        Ok(n as usize)
    }
    fn payload(&mut self, tag: u8, depth: usize, path: &mut Vec<Vec<u8>>) -> Result<(), String> {
        if depth > MAX_DEPTH {
            return Err("depth limit".into());
        }
        self.nodes += 1;
        if self.nodes > MAX_NODES {
            return Err("node limit".into());
        }
        match tag {
            1 => {
                self.take(1)?;
            }
            2 => {
                self.take(2)?;
            }
            3 | 5 => {
                self.take(4)?;
            }
            4 | 6 => {
                self.take(8)?;
            }
            7 | 11 | 12 => {
                let n = self.length()?;
                let width = if tag == 7 {
                    1
                } else if tag == 11 {
                    4
                } else {
                    8
                };
                let raw = self.take(n.checked_mul(width).ok_or("array overflow")?)?;
                if tag == 11 && path.as_slice() == [b"Level".to_vec(), b"NEID".to_vec()] {
                    self.max_neid = raw
                        .as_chunks::<4>()
                        .0
                        .iter()
                        .map(|v| u32::from_be_bytes(*v))
                        .max();
                }
            }
            8 => {
                self.name()?;
            }
            9 => {
                let element = self.byte()?;
                let n = self.length()?;
                if element > 12 || (element == 0 && n != 0) {
                    return Err("invalid list element type".into());
                }
                // Lists do not inherit named-field addressing semantics.
                path.push(Vec::new());
                for _ in 0..n {
                    self.payload(element, depth + 1, path)?;
                }
                path.pop();
            }
            10 => loop {
                let child = self.byte()?;
                if child == 0 {
                    break;
                }
                if child > 12 {
                    return Err("unknown wire tag id".into());
                }
                let raw_name = self.name()?;
                let identity = ascii_identity(raw_name);
                let name = identity.as_deref().unwrap_or(raw_name);
                if path.is_empty() && name == b"Level" {
                    self.levels += 1;
                    if child != 10 {
                        return Err("Level is not compound".into());
                    }
                }
                if path.is_empty() && name == b"DataVersion" && child == 3 {
                    self.data_version = Some(i32::from_be_bytes(
                        self.bytes
                            .get(self.pos..self.pos + 4)
                            .ok_or("truncated DataVersion")?
                            .try_into()
                            .unwrap(),
                    ));
                }
                if path.as_slice() == [b"Level".to_vec()] && name == b"LastUpdate" {
                    if child != 4 {
                        return Err("LastUpdate is not long".into());
                    }
                    self.edits.push(self.pos);
                }
                path.push(name.to_vec());
                self.payload(child, depth + 1, path)?;
                path.pop();
            },
            _ => return Err("unknown wire tag id".into()),
        }
        Ok(())
    }
}

pub fn scan(bytes: &[u8]) -> Result<Scan, String> {
    if bytes.len() > MAX_RAW {
        return Err("NBT byte limit".into());
    }
    let mut r = Reader {
        bytes,
        pos: 0,
        nodes: 0,
        levels: 0,
        edits: Vec::new(),
        data_version: None,
        max_neid: None,
    };
    if r.byte()? != 10 {
        return Err("root must be compound".into());
    }
    let root_name = r.name()?.to_vec();
    r.payload(10, 0, &mut Vec::new())?;
    if r.pos != bytes.len() {
        return Err("trailing NBT bytes".into());
    }
    if r.levels != 1 || r.edits.len() != 1 {
        return Err("missing/ambiguous edit path".into());
    }
    let edit_offset = r.edits[0];
    Ok(Scan {
        edit_offset,
        old_value: i64::from_be_bytes(bytes[edit_offset..edit_offset + 8].try_into().unwrap()),
        root_name,
        nodes: r.nodes,
        data_version: r.data_version,
        max_neid: r.max_neid,
    })
}

pub fn edit(bytes: &[u8], scan: &Scan) -> Result<Vec<u8>, String> {
    let value = scan.old_value.checked_add(1).ok_or("LastUpdate overflow")?;
    let mut out = bytes.to_vec();
    out[scan.edit_offset..scan.edit_offset + 8].copy_from_slice(&value.to_be_bytes());
    Ok(out)
}
