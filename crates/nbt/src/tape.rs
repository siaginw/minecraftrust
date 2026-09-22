//! Zero-allocation binary NBT cursor and path lookup.
//! Traverses raw slices by skipping payload offsets with arithmetic.

use crate::codec::{NbtError, MAX_DEPTH};

pub struct NbtCursor<'a> {
    data: &'a [u8],
    pos: usize,
}

impl<'a> NbtCursor<'a> {
    pub fn new(data: &'a [u8]) -> Self {
        Self { data, pos: 0 }
    }

    pub fn position(&self) -> usize {
        self.pos
    }

    pub fn remaining(&self) -> &'a [u8] {
        &self.data[self.pos..]
    }

    /// Finds a compound entry by key at the current compound depth, skipping others.
    pub fn find_entry(&mut self, target_key: &str) -> Result<Option<(u8, &'a [u8])>, NbtError> {
        while self.pos < self.data.len() {
            let tag_type = self.data[self.pos];
            self.pos += 1;
            if tag_type == 0 {
                return Ok(None); // End of compound
            }

            let key = self.read_str_slice()?;
            let val_start = self.pos;
            self.skip_payload(tag_type, 0)?;
            let val_end = self.pos;

            if key == target_key.as_bytes() {
                return Ok(Some((tag_type, &self.data[val_start..val_end])));
            }
        }
        Err(NbtError::UnexpectedEof)
    }

    fn read_str_slice(&mut self) -> Result<&'a [u8], NbtError> {
        if self.pos + 2 > self.data.len() {
            return Err(NbtError::UnexpectedEof);
        }
        let len = u16::from_be_bytes([self.data[self.pos], self.data[self.pos + 1]]) as usize;
        self.pos += 2;
        if self.pos + len > self.data.len() {
            return Err(NbtError::UnexpectedEof);
        }
        let slice = &self.data[self.pos..self.pos + len];
        self.pos += len;
        Ok(slice)
    }

    pub fn skip_payload(&mut self, tag_type: u8, depth: usize) -> Result<(), NbtError> {
        if depth > MAX_DEPTH {
            return Err(NbtError::DepthExceeded(depth));
        }
        match tag_type {
            0 => Ok(()),
            1 => self.advance(1),
            2 => self.advance(2),
            3 => self.advance(4),
            4 => self.advance(8),
            5 => self.advance(4),
            6 => self.advance(8),
            7 => {
                let len = self.read_i32()? as usize;
                self.advance(len)
            }
            8 => {
                let len = self.read_u16()? as usize;
                self.advance(len)
            }
            9 => {
                if self.pos + 5 > self.data.len() {
                    return Err(NbtError::UnexpectedEof);
                }
                let elem_type = self.data[self.pos];
                self.pos += 1;
                let len = self.read_i32()?;
                if len < 0 {
                    return Err(NbtError::NegativeLength(len));
                }
                for _ in 0..len {
                    self.skip_payload(elem_type, depth + 1)?;
                }
                Ok(())
            }
            10 => {
                loop {
                    if self.pos >= self.data.len() {
                        return Err(NbtError::UnexpectedEof);
                    }
                    let t = self.data[self.pos];
                    self.pos += 1;
                    if t == 0 {
                        break;
                    }
                    let _ = self.read_str_slice()?;
                    self.skip_payload(t, depth + 1)?;
                }
                Ok(())
            }
            11 => {
                let len = self.read_i32()? as usize;
                self.advance(len * 4)
            }
            12 => {
                let len = self.read_i32()? as usize;
                self.advance(len * 8)
            }
            _ => Err(NbtError::InvalidTypeId(tag_type)),
        }
    }

    fn advance(&mut self, n: usize) -> Result<(), NbtError> {
        if self.pos + n > self.data.len() {
            Err(NbtError::UnexpectedEof)
        } else {
            self.pos += n;
            Ok(())
        }
    }

    fn read_u16(&mut self) -> Result<u16, NbtError> {
        if self.pos + 2 > self.data.len() {
            Err(NbtError::UnexpectedEof)
        } else {
            let v = u16::from_be_bytes([self.data[self.pos], self.data[self.pos + 1]]);
            self.pos += 2;
            Ok(v)
        }
    }

    fn read_i32(&mut self) -> Result<i32, NbtError> {
        if self.pos + 4 > self.data.len() {
            Err(NbtError::UnexpectedEof)
        } else {
            let v = i32::from_be_bytes([
                self.data[self.pos],
                self.data[self.pos + 1],
                self.data[self.pos + 2],
                self.data[self.pos + 3],
            ]);
            self.pos += 4;
            Ok(v)
        }
    }
}
