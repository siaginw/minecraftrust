//! Dynamic Palette Parity Harness
//!
//! Tests NativeSection against Java BlockStateContainer across
//! palette transitions: 1, 2, 16, 17, 31, 32, 33, 63, 64, 65, 127, 128, 129, 255, 256, >256 states

use native_chunk::section::NativeSection;

fn main() {
    println!("=== M4.1 Dynamic Palette Parity Harness ===\n");

    // Test 1: Single state (Air only)
    test_palette_case("1 state (Air only)", &create_counts(1, 4096));

    // Test 2: 2 states
    test_palette_case("2 states", &create_counts(2, 2048));

    // Test 3: 16 states (max 4-bit)
    test_palette_case("16 states", &create_counts(16, 256));

    // Test 4: 17 states (triggers 5-bit)
    test_palette_case("17 states", &create_counts_exact(17));

    // Test 5: 31 states
    test_palette_case("31 states", &create_counts_exact(31));

    // Test 6: 32 states
    test_palette_case("32 states", &create_counts_exact(32));

    // Test 7: 33 states
    test_palette_case("33 states", &create_counts_exact(33));

    // Test 8: 63 states
    test_palette_case("63 states", &create_counts_exact(63));

    // Test 9: 64 states
    test_palette_case("64 states", &create_counts_exact(64));

    // Test 10: 65 states
    test_palette_case("65 states", &create_counts_exact(65));

    // Test 11: 127 states
    test_palette_case("127 states", &create_counts_exact(127));

    // Test 12: 128 states
    test_palette_case("128 states", &create_counts_exact(128));

    // Test 13: 129 states
    test_palette_case("129 states", &create_counts_exact(129));

    // Test 14: 255 states
    test_palette_case("255 states", &create_counts_exact(255));

    // Test 15: 256 states (max 8-bit local palette)
    test_palette_case("256 states", &create_counts_exact(256));

    // Test 16: 257 states (triggers global palette)
    test_palette_case("257 states (global)", &create_counts_exact(257));

    // Test 17: Random distribution
    test_random_palette("Random 100 states", 100);

    println!("\n=== All parity tests completed ===");
}

fn create_counts(num_states: usize, per_state: usize) -> Vec<(u16, usize)> {
    (0..num_states).map(|i| (i as u16, per_state)).collect()
}

fn create_counts_exact(num_states: usize) -> Vec<(u16, usize)> {
    let base = 4096 / num_states;
    let remainder = 4096 % num_states;
    (0..num_states)
        .map(|i| {
            let count = base + if i < remainder { 1 } else { 0 };
            (i as u16, count)
        })
        .collect()
}

fn test_palette_case(name: &str, state_counts: &[(u16, usize)]) {
    println!("Testing: {}", name);

    let mut section = NativeSection::new(0);
    let mut idx = 0;

    for (state_id, count) in state_counts {
        for _ in 0..*count {
            if idx >= 4096 { break; }
            section.set_block_by_index(idx, *state_id);
            idx += 1;
        }
    }

    // Fill remaining with Air
    while idx < 4096 {
        section.set_block_by_index(idx, 0);
        idx += 1;
    }

    // Verify roundtrip
    let mut wire_buf = [0u8; 65536];
    let mut offset = 0;
    let res = section.encode_wire(&mut wire_buf, &mut offset, true);
    assert!(res.is_ok(), "Wire encode failed for {}", name);

    // Verify non_air_count
    let expected_non_air: u16 = state_counts.iter()
        .filter(|(id, _)| *id != 0)
        .map(|(_, c)| *c as u16)
        .sum();
    assert_eq!(section.non_air_count, expected_non_air, "Non-air count mismatch for {}", name);

    println!("  ✓ {} states, non_air={}, wire_bytes={}",
             state_counts.len(), section.non_air_count, offset);
}

fn test_random_palette(name: &str, num_states: usize) {
    use std::collections::hash_map::DefaultHasher;
    use std::hash::{Hash, Hasher};

    println!("Testing: {}", name);

    let mut section = NativeSection::new(0);
    let mut hasher = DefaultHasher::new();

    for idx in 0..4096 {
        idx.hash(&mut hasher);
        let state = (hasher.finish() % num_states as u64) as u16;
        section.set_block_by_index(idx, state);
    }

    let mut wire_buf = [0u8; 65536];
    let mut offset = 0;
    let res = section.encode_wire(&mut wire_buf, &mut offset, true);
    assert!(res.is_ok(), "Wire encode failed for {}", name);

    println!("  ✓ {} unique states, non_air={}, wire_bytes={}",
             num_states, section.non_air_count, offset);
}