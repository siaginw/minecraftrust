use crate::{NbtCursor, NbtDecoder, NbtEncoder, NbtError, NbtTag};
use std::collections::HashMap;

#[test]
fn test_primitive_roundtrip() {
    let mut map = HashMap::new();
    map.insert("byte".to_string(), NbtTag::Byte(127));
    map.insert("short".to_string(), NbtTag::Short(-32768));
    map.insert("int".to_string(), NbtTag::Int(2147483647));
    map.insert("long".to_string(), NbtTag::Long(-9223372036854775808));
    map.insert("float".to_string(), NbtTag::Float(3.14159));
    map.insert("double".to_string(), NbtTag::Double(2.718281828459045));

    let root = NbtTag::Compound(map);
    let mut bytes = Vec::new();
    NbtEncoder::encode("TestRoot", &root, &mut bytes).unwrap();

    let (name, decoded) = NbtDecoder::decode(&bytes).unwrap();
    assert_eq!(name, "TestRoot");
    assert_eq!(root, decoded);
}

#[test]
fn test_mutf8_null_and_surrogates() {
    let mut map = HashMap::new();
    // Test null byte \u0000 and emoji requiring surrogate pair in MUTF-8
    let test_str = "Hello\u{0000}World! 🦀 Minecraft 1.12.2";
    map.insert("greeting".to_string(), NbtTag::String(test_str.to_string()));

    let root = NbtTag::Compound(map);
    let mut bytes = Vec::new();
    NbtEncoder::encode("", &root, &mut bytes).unwrap();

    let (_, decoded) = NbtDecoder::decode(&bytes).unwrap();
    let m = decoded.as_compound().unwrap();
    assert_eq!(m.get("greeting").unwrap().as_str().unwrap(), test_str);
}

#[test]
fn test_arrays_roundtrip() {
    let mut map = HashMap::new();
    map.insert("byte_arr".to_string(), NbtTag::ByteArray(vec![1, 2, 3, 255]));
    map.insert("int_arr".to_string(), NbtTag::IntArray(vec![-100, 0, 100, 999999]));
    map.insert(
        "long_arr".to_string(),
        NbtTag::LongArray(vec![-999999999999, 0, 999999999999]),
    );

    let root = NbtTag::Compound(map);
    let mut bytes = Vec::new();
    NbtEncoder::encode("", &root, &mut bytes).unwrap();

    let (_, decoded) = NbtDecoder::decode(&bytes).unwrap();
    assert_eq!(root, decoded);
}

#[test]
fn test_depth_limit_enforced() {
    let builder = std::thread::Builder::new().stack_size(8 * 1024 * 1024);
    let handler = builder
        .spawn(|| {
            // 1. Verify encoder depth limit
            let mut current = NbtTag::Compound(HashMap::new());
            for i in 0..520 {
                let mut parent = HashMap::new();
                parent.insert(format!("l_{}", i), current);
                current = NbtTag::Compound(parent);
            }
            let mut bytes = Vec::new();
            let enc_res = NbtEncoder::encode("", &current, &mut bytes);
            match enc_res {
                Err(NbtError::DepthExceeded(d)) => assert!(d > 512),
                other => panic!("Expected encoder DepthExceeded, got: {:?}", other),
            }

            // 2. Verify decoder depth limit on crafted stream
            let mut crafted = Vec::new();
            // Root compound
            crafted.push(10);
            crafted.extend_from_slice(&0u16.to_be_bytes()); // empty root name
            for _ in 0..515 {
                crafted.push(10); // child compound
                crafted.extend_from_slice(&1u16.to_be_bytes()); // key len = 1
                crafted.push(b'c');
            }
            crafted.push(0); // close
            let dec_res = NbtDecoder::decode(&crafted);
            match dec_res {
                Err(NbtError::DepthExceeded(d)) => assert!(d > 512),
                other => panic!("Expected decoder DepthExceeded, got: {:?}", other),
            }
        })
        .unwrap();
    handler.join().unwrap();
}

#[test]
fn test_unknown_mod_tag_preservation() {
    let mut map = HashMap::new();
    map.insert("standard_field".to_string(), NbtTag::Int(42));

    // Simulated unknown mod compound
    let mut mod_caps = HashMap::new();
    mod_caps.insert("custom_mod:energy".to_string(), NbtTag::Long(1000000));
    mod_caps.insert(
        "custom_mod:tag_array".to_string(),
        NbtTag::ByteArray(vec![0xAA, 0xBB, 0xCC]),
    );
    map.insert(
        "ForgeCaps".to_string(),
        NbtTag::Compound(mod_caps),
    );

    let root = NbtTag::Compound(map);
    let mut bytes = Vec::new();
    NbtEncoder::encode("", &root, &mut bytes).unwrap();

    let (_, decoded) = NbtDecoder::decode(&bytes).unwrap();
    assert_eq!(root, decoded);
}

#[test]
fn test_tape_cursor_lookup() {
    let mut map = HashMap::new();
    map.insert("xPos".to_string(), NbtTag::Int(10));
    map.insert("zPos".to_string(), NbtTag::Int(-20));
    map.insert("terrainPopulated".to_string(), NbtTag::Byte(1));

    let root = NbtTag::Compound(map);
    let mut bytes = Vec::new();
    NbtEncoder::encode("", &root, &mut bytes).unwrap();

    // Skip root tag (byte 10 + 2-byte empty name = 3 bytes)
    let payload = &bytes[3..];
    let mut cursor = NbtCursor::new(payload);
    let entry = cursor.find_entry("zPos").unwrap();
    assert!(entry.is_some());
    let (tag_type, val_slice) = entry.unwrap();
    assert_eq!(tag_type, 3); // TAG_Int
    assert_eq!(i32::from_be_bytes(val_slice.try_into().unwrap()), -20);
}
