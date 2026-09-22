use protocol::*;
use core_types::BlockPos;
use std::io::Cursor;

#[test]
fn test_varint_golden_vectors() {
    let test_cases: &[(i32, &[u8])] = &[
        (0, &[0x00]),
        (1, &[0x01]),
        (2, &[0x02]),
        (127, &[0x7f]),
        (128, &[0x80, 0x01]),
        (255, &[0xff, 0x01]),
        (25565, &[0xdd, 0xc7, 0x01]),
        (2097151, &[0xff, 0xff, 0x7f]),
        (2147483647, &[0xff, 0xff, 0xff, 0xff, 0x07]),
        (-1, &[0xff, 0xff, 0xff, 0xff, 0x0f]),
        (-2147483648, &[0x80, 0x80, 0x80, 0x80, 0x08]),
    ];

    for &(val, expected_bytes) in test_cases {
        // Size
        assert_eq!(varint_size(val), expected_bytes.len(), "size mismatch for {val}");

        // Write
        let mut out = Vec::new();
        let written = write_varint(val, &mut out).expect("write failed");
        assert_eq!(written, expected_bytes.len());
        assert_eq!(out, expected_bytes, "encoding mismatch for {val}");

        // Read
        let mut cur = Cursor::new(expected_bytes);
        let decoded = read_varint(&mut cur).expect("read failed");
        assert_eq!(decoded, val, "decoding mismatch for {val}");
    }
}

#[test]
fn test_varint_too_big_rejected() {
    // 6 bytes with MSB set = invalid VarInt
    let invalid_bytes = [0x80, 0x80, 0x80, 0x80, 0x80, 0x80];
    let mut cur = Cursor::new(&invalid_bytes);
    let err = read_varint(&mut cur);
    assert_eq!(err, Err(ProtocolError::VarIntTooBig));
}

#[test]
fn test_varlong_golden_vectors() {
    let test_cases: &[(i64, &[u8])] = &[
        (0, &[0x00]),
        (1, &[0x01]),
        (2, &[0x02]),
        (127, &[0x7f]),
        (128, &[0x80, 0x01]),
        (255, &[0xff, 0x01]),
        (2147483647, &[0xff, 0xff, 0xff, 0xff, 0x07]),
        (9223372036854775807, &[0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0x7f]),
        (-1, &[0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0x01]),
        (-9223372036854775808, &[0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x01]),
    ];

    for &(val, expected_bytes) in test_cases {
        assert_eq!(varlong_size(val), expected_bytes.len(), "size mismatch for {val}");

        let mut out = Vec::new();
        let written = write_varlong(val, &mut out).expect("write failed");
        assert_eq!(written, expected_bytes.len());
        assert_eq!(out, expected_bytes, "encoding mismatch for {val}");

        let mut cur = Cursor::new(expected_bytes);
        let decoded = read_varlong(&mut cur).expect("read failed");
        assert_eq!(decoded, val, "decoding mismatch for {val}");
    }
}

#[test]
fn test_varlong_too_big_rejected() {
    let invalid_bytes = [0x80; 11];
    let mut cur = Cursor::new(&invalid_bytes);
    let err = read_varlong(&mut cur);
    assert_eq!(err, Err(ProtocolError::VarLongTooBig));
}

#[test]
fn test_big_endian_primitives() {
    let mut buf = Vec::new();
    write_bool(true, &mut buf).unwrap();
    write_bool(false, &mut buf).unwrap();
    write_u8(0x42, &mut buf).unwrap();
    write_i8(-17, &mut buf).unwrap();
    write_i16_be(-1234, &mut buf).unwrap();
    write_u16_be(5678, &mut buf).unwrap();
    write_i32_be(-100_000, &mut buf).unwrap();
    write_u32_be(300_000, &mut buf).unwrap();
    write_i64_be(-9876543210, &mut buf).unwrap();
    write_u64_be(9876543210, &mut buf).unwrap();
    write_f32_be(3.14159, &mut buf).unwrap();
    write_f64_be(2.718281828459045, &mut buf).unwrap();

    let mut cur = Cursor::new(&buf);
    assert_eq!(read_bool(&mut cur).unwrap(), true);
    assert_eq!(read_bool(&mut cur).unwrap(), false);
    assert_eq!(read_u8(&mut cur).unwrap(), 0x42);
    assert_eq!(read_i8(&mut cur).unwrap(), -17);
    assert_eq!(read_i16_be(&mut cur).unwrap(), -1234);
    assert_eq!(read_u16_be(&mut cur).unwrap(), 5678);
    assert_eq!(read_i32_be(&mut cur).unwrap(), -100_000);
    assert_eq!(read_u32_be(&mut cur).unwrap(), 300_000);
    assert_eq!(read_i64_be(&mut cur).unwrap(), -9876543210);
    assert_eq!(read_u64_be(&mut cur).unwrap(), 9876543210);
    assert!((read_f32_be(&mut cur).unwrap() - 3.14159).abs() < 1e-5);
    assert!((read_f64_be(&mut cur).unwrap() - 2.718281828459045).abs() < 1e-12);
}

#[test]
fn test_position_codec() {
    let positions = &[
        BlockPos::new(0, 0, 0),
        BlockPos::new(100, 64, 200),
        BlockPos::new(-50, 10, -120),
        BlockPos::new(30_000_000, 255, 30_000_000),
        BlockPos::new(-30_000_000, 0, -30_000_000),
    ];

    for pos in positions {
        let mut buf = Vec::new();
        write_position(pos, &mut buf).unwrap();
        assert_eq!(buf.len(), 8);

        let mut cur = Cursor::new(&buf);
        let decoded = read_position(&mut cur).unwrap();
        assert_eq!(&decoded, pos, "Position roundtrip mismatch");
    }
}

#[test]
fn test_uuid_codec() {
    let uuid = WireUuid::new(0x12345678_9ABCDEF0, 0xFEDCBA98_76543210);
    let mut buf = Vec::new();
    write_uuid(&uuid, &mut buf).unwrap();
    assert_eq!(buf.len(), 16);

    let mut cur = Cursor::new(&buf);
    let decoded = read_uuid(&mut cur).unwrap();
    assert_eq!(decoded, uuid);
    assert_eq!(decoded.to_u128(), uuid.to_u128());
}

#[test]
fn test_string_codec() {
    let test_strings = &[
        "",
        "Hello Minecraft 1.12.2",
        "Rustcraft native wire protocol",
        "§cColor §aCodes §rTest",
        "🦀 Ferris in Minecraft!",
    ];

    for &s in test_strings {
        let mut buf = Vec::new();
        write_string(s, &mut buf).unwrap();

        let mut cur = Cursor::new(&buf);
        let decoded = read_string(&mut cur, 32767).unwrap();
        assert_eq!(decoded, s);
    }
}

#[test]
fn test_string_limit_rejection() {
    let s = "abcdefghij"; // 10 chars
    let mut buf = Vec::new();
    write_string(s, &mut buf).unwrap();

    let mut cur = Cursor::new(&buf);
    // Allowed max = 5 chars -> should reject
    let res = read_string(&mut cur, 5);
    assert!(matches!(res, Err(ProtocolError::StringTooLong { .. })));
}

#[test]
fn test_slot_codec() {
    // Empty slot
    let empty = WireSlot::EMPTY;
    let mut buf = Vec::new();
    write_slot(&empty, &mut buf).unwrap();
    assert_eq!(buf, &[-1i8 as u8, -1i8 as u8]); // short -1 = 0xFFFF

    let mut cur = Cursor::new(&buf);
    let decoded_empty = read_slot(&mut cur).unwrap();
    assert!(decoded_empty.is_empty());

    // Present slot (Diamond, count 64, damage 0, no NBT)
    let item = WireSlot {
        item_id: 264, // Diamond
        count: 64,
        damage: 0,
        nbt_bytes: None,
    };
    let mut buf = Vec::new();
    write_slot(&item, &mut buf).unwrap();

    let mut cur = Cursor::new(&buf);
    let decoded_item = read_slot(&mut cur).unwrap();
    assert_eq!(decoded_item.item_id, 264);
    assert_eq!(decoded_item.count, 64);
    assert_eq!(decoded_item.damage, 0);
}
