use std::fmt;

#[derive(Debug, PartialEq, Eq)]
pub enum ProtocolError {
    UnexpectedEof,
    BufferTooSmall { needed: usize, available: usize },
    VarIntTooBig,
    VarLongTooBig,
    StringTooLong { length: usize, max: usize },
    InvalidUtf8,
    Custom(&'static str),
}

impl fmt::Display for ProtocolError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::UnexpectedEof => write!(f, "unexpected end of buffer/stream"),
            Self::BufferTooSmall { needed, available } => {
                write!(f, "buffer too small: needed {needed} bytes, had {available}")
            }
            Self::VarIntTooBig => write!(f, "VarInt is wider than 5 bytes"),
            Self::VarLongTooBig => write!(f, "VarLong is wider than 10 bytes"),
            Self::StringTooLong { length, max } => {
                write!(f, "string length {length} exceeds max allowed {max}")
            }
            Self::InvalidUtf8 => write!(f, "string payload is not valid UTF-8"),
            Self::Custom(msg) => write!(f, "{msg}"),
        }
    }
}

impl std::error::Error for ProtocolError {}
