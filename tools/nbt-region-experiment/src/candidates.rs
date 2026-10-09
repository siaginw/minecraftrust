use crate::lossless::Scan;
use std::io::Cursor;

pub fn parse_only(parser: &str, bytes: &[u8]) -> Result<(), String> {
    match parser {
        "lossless" => {
            std::hint::black_box(crate::lossless::scan(bytes)?);
        }
        "existing" => {
            std::hint::black_box(nbt::NbtDecoder::decode(bytes).map_err(|e| format!("{e:?}"))?);
        }
        "fastnbt" => {
            let value: fastnbt::Value = fastnbt::from_bytes_with_opts(
                bytes,
                fastnbt::DeOpts::new().max_seq_len(crate::lossless::MAX_SEQUENCE),
            )
            .map_err(|e| e.to_string())?;
            std::hint::black_box(value);
        }
        "simdnbt" => {
            std::hint::black_box(
                simdnbt::owned::read(&mut Cursor::new(bytes)).map_err(|e| e.to_string())?,
            );
        }
        _ => return Err("unknown parser".into()),
    }
    Ok(())
}

pub fn edit(parser: &str, bytes: &[u8], preflight: &Scan) -> Result<Vec<u8>, String> {
    let next = preflight
        .old_value
        .checked_add(1)
        .ok_or("LastUpdate overflow")?;
    match parser {
        "lossless" => crate::lossless::edit(bytes, preflight),
        "existing" => {
            let (name, mut root) = nbt::NbtDecoder::decode(bytes).map_err(|e| format!("{e:?}"))?;
            let value = root
                .as_compound_mut()
                .and_then(|r| r.get_mut("Level"))
                .and_then(|r| r.as_compound_mut())
                .and_then(|r| r.get_mut("LastUpdate"))
                .ok_or("extract path")?;
            if !matches!(value, nbt::NbtTag::Long(v) if *v == preflight.old_value) {
                return Err("extraction mismatch".into());
            }
            *value = nbt::NbtTag::Long(next);
            let mut out = Vec::new();
            nbt::NbtEncoder::encode(&name, &root, &mut out).map_err(|e| format!("{e:?}"))?;
            Ok(out)
        }
        "fastnbt" => {
            let mut root: fastnbt::Value = fastnbt::from_bytes_with_opts(
                bytes,
                fastnbt::DeOpts::new().max_seq_len(crate::lossless::MAX_SEQUENCE),
            )
            .map_err(|e| e.to_string())?;
            let fastnbt::Value::Compound(ref mut fields) = root else {
                return Err("root type".into());
            };
            let Some(fastnbt::Value::Compound(level)) = fields.get_mut("Level") else {
                return Err("Level type".into());
            };
            let Some(fastnbt::Value::Long(value)) = level.get_mut("LastUpdate") else {
                return Err("LastUpdate type".into());
            };
            if *value != preflight.old_value {
                return Err("extraction mismatch".into());
            }
            *value = next;
            // Value omits the root name. Preserve it explicitly when representable.
            let name = nbt::mutf8::decode_mutf8(&preflight.root_name)
                .map_err(|e| format!("root name adapter: {e:?}"))?;
            fastnbt::to_bytes_with_opts(&root, fastnbt::SerOpts::new().root_name(name))
                .map_err(|e| e.to_string())
        }
        "simdnbt" => {
            let root = simdnbt::owned::read(&mut Cursor::new(bytes)).map_err(|e| e.to_string())?;
            if root.is_none() {
                return Err("missing root".into());
            }
            let root = root.unwrap();
            let name = root.name().to_owned();
            let mut fields = root.as_compound();
            let value = fields
                .compound_mut("Level")
                .and_then(|r| r.long_mut("LastUpdate"))
                .ok_or("extract path")?;
            if *value != preflight.old_value {
                return Err("extraction mismatch".into());
            }
            *value = next;
            let mut out = Vec::new();
            simdnbt::owned::Nbt::new(name, fields).write(&mut out);
            Ok(out)
        }
        _ => Err("unknown parser".into()),
    }
}
