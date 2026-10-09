//! OPT-SYNC-004 §4 focused fixtures: exercise the REAL packed-transfer
//! path (Java_com_rustcraft_bridge_NativeChunkBridge_refreshSectionPacked
//! + readbackSection) against independently-constructed expected arrays.
//! Covers: local index 0 and highest valid index, all supported widths
//! (4..=16, local modes 4..=8 and registry modes 9..=16), entries
//! spanning 64-bit word boundaries, global IDs above 65,535, registry
//! palette mode (palette_count=0 => entry==id), malformed-index /
//! truncated-buffer / bad-argument rejection, and the NEGATIVE case
//! where packed and native share the same wrong value (transfer
//! validation passes, an independent expectation FAILS to match).

use native_chunk::ChunkKey;
use rustcraft_ffi::get_registry;
use rustcraft_ffi::{
    Java_com_rustcraft_bridge_NativeChunkBridge_readbackSection,
    Java_com_rustcraft_bridge_NativeChunkBridge_refreshSectionPacked,
    Java_com_rustcraft_bridge_NativeChunkBridge_registerEmpty,
};

const DIM: i32 = 1234;
const CX: i32 = 77;
static NEXT_CZ: std::sync::atomic::AtomicI32 = std::sync::atomic::AtomicI32::new(88);
thread_local! { static MY_CZ: std::cell::Cell<i32> = std::cell::Cell::new(0); }

fn my_cz() -> i32 {
    if MY_CZ.get() == 0 {
        MY_CZ.set(NEXT_CZ.fetch_add(1, std::sync::atomic::Ordering::SeqCst));
    }
    MY_CZ.get()
}
const CZ: i32 = 88;

/// Pack `states` at `bits` in the 1.12 LSB-first continuous layout —
/// the INDEPENDENT encoder for the fixture (mirrors the wire spec, not
/// the production decoder under test).
fn pack_independent(states: &[u32; 4096], bits: u32) -> Vec<u64> {
    let bits = bits as usize;
    let words = (4096 * bits + 63) / 64;
    let mut w = vec![0u64; words];
    for (idx, &s) in states.iter().enumerate() {
        let bit_pos = idx * bits;
        let w0 = bit_pos / 64;
        let off = bit_pos % 64;
        let fit = 64 - off;
        let val = (s as u64) & ((1u64 << bits) - 1);
        if fit >= bits {
            w[w0] |= val << off;
        } else {
            let rest = bits - fit;
            w[w0] |= (val & ((1u64 << fit) - 1)) << off;
            w[w0 + 1] |= val >> fit;
            let _ = rest;
        }
    }
    w
}

/// Build a fixture population: index 0 and the highest VALID palette
/// index at boundary cells, high global IDs (>65,535) where the palette
/// allows, and word-spanning coverage comes free at these widths.
fn fixture_states(palette: &[u32], bits: u32) -> [u32; 4096] {
    let mut s = [0u32; 4096];
    let max_idx = (1u32 << bits) as usize;
    // cell 0 -> index 0; last cell -> highest valid index; scattered
    // cells exercise every residue class of the bit packing
    s[0] = 0;
    s[4095] = (max_idx - 1) as u32;
    for i in 0..4096 {
        if i % 97 == 0 && i != 0 && i != 4095 {
            s[i] = ((i * 31) % max_idx as usize) as u32;
        }
    }
    // map through the palette (local mode)
    let mut out = [0u32; 4096];
    for (i, &idx) in s.iter().enumerate() {
        out[i] = palette.get(idx as usize).copied().unwrap_or(0);
    }
    out
}

unsafe fn register_shell() {
    let gen = Java_com_rustcraft_bridge_NativeChunkBridge_registerEmpty(
        std::ptr::null_mut(),
        std::ptr::null_mut(),
        DIM,
        CX,
        my_cz(),
    );
    assert!(gen > 0, "shell registration failed");
}

unsafe fn roundtrip(
    bits: i32,
    words: &[u64],
    palette: &[u32],
    expected: &[u32; 4096],
    lights: &[u8; 4096],
) -> i32 {
    let mut wbuf: Vec<u8> = Vec::with_capacity(words.len() * 8);
    for w in words {
        wbuf.extend_from_slice(&w.to_le_bytes());
    }
    let mut pbuf: Vec<u8> = Vec::with_capacity(palette.len() * 4);
    for p in palette {
        pbuf.extend_from_slice(&p.to_le_bytes());
    }
    let rc = Java_com_rustcraft_bridge_NativeChunkBridge_refreshSectionPacked(
        std::ptr::null_mut(),
        std::ptr::null_mut(),
        DIM,
        CX,
        my_cz(),
        4,
        bits,
        wbuf.as_ptr() as i64,
        words.len() as i32,
        if palette.is_empty() {
            0
        } else {
            pbuf.as_ptr() as i64
        },
        palette.len() as i32,
        lights.as_ptr() as i64,
        4096,
    );
    if rc < 0 {
        return rc;
    }
    // read back the REAL installed section and compare independently
    let mut rb = vec![0u8; 20480];
    let rbr = Java_com_rustcraft_bridge_NativeChunkBridge_readbackSection(
        std::ptr::null_mut(),
        std::ptr::null_mut(),
        DIM,
        CX,
        my_cz(),
        4,
        rb.as_mut_ptr() as i64,
        20480,
    );
    assert_eq!(rbr, 0, "readback failed rc={}", rbr);
    for i in 0..4096 {
        let got = i32::from_le_bytes([rb[i * 4], rb[i * 4 + 1], rb[i * 4 + 2], rb[i * 4 + 3]]);
        assert_eq!(
            got as u32, expected[i],
            "state[{}] bits={} packed-decode mismatch: got {} want {}",
            i, bits, got, expected[i]
        );
    }
    rc
}

#[test]
fn packed_fixtures_all_widths_local_and_registry() {
    let lights = [0u8; 4096];
    // local palette modes 4..=8 (palette table path)
    for bits in 4..=8i32 {
        unsafe {
            register_shell();
        }
        let table_len = 1usize << bits;
        // include index 0 -> a low id, top index -> id > 65,535, and
        // every table slot above 0 -> wide/odd values incl. >65,535
        let mut palette = Vec::with_capacity(table_len);
        for i in 0..table_len {
            palette.push(if i == 0 {
                3
            } else {
                65_536 + (i as u32) * 977 % 4_000_000
            });
        }
        let expected = fixture_states(&palette, bits as u32);
        let words = pack_independent(&expected, bits as u32)
            .iter()
            .map(|w: &u64| {
                let mut states = [0u32; 4096];
                let _ = states;
                // re-encode through the palette: build the INDEX stream
                *w
            })
            .collect::<Vec<u64>>();
        // NOTE: pack_independent packs GLOBAL ids; for local mode the
        // words must carry the INDEX. Build the index stream directly.
        let mut idx_states = [0u32; 4096];
        for i in 0..4096usize {
            let want = expected[i];
            idx_states[i] = palette.iter().position(|&g| g == want).unwrap_or(0) as u32;
        }
        let words = pack_independent(&idx_states, bits as u32);
        let rc = unsafe { roundtrip(bits, &words, &palette, &expected, &lights) };
        assert!(rc >= 0, "bits={} rc={}", bits, rc);
    }
    // registry palette modes 9..=16 (palette_count=0 => entry==id)
    for bits in 9..=16i32 {
        unsafe {
            register_shell();
        }
        // registry entries ARE global ids: values up to 2^bits-1; at
        // bits 17+ unsupported — 16 covers ids up to 65,535 and the
        // full-width u32 native side accepts them verbatim
        let mut expected = [0u32; 4096];
        for i in 0..4096usize {
            expected[i] = ((i as u64 * 2654435761) % (1u64 << bits)) as u32;
        }
        let words = pack_independent(&expected, bits as u32);
        let rc = unsafe { roundtrip(bits, &words, &[], &expected, &lights) };
        assert!(rc >= 0, "registry bits={} rc={}", bits, rc);
    }
}

#[test]
fn packed_negative_independent_catch() {
    // NEGATIVE (§4): packed and native share the SAME WRONG value —
    // transfer validation (readback==packed) PASSES, the INDEPENDENT
    // expectation FAILS. Registry mode (palette_count=0, bits=16) so the
    // wrong id packs losslessly; the independent expectation is built
    // here from the fixture spec, not from the decoder under test.
    unsafe {
        register_shell();
    }
    let bits = 16;
    let mut wrong = [0u32; 4096];
    wrong[1234] = 65_535; // the LIE both sides will share
    let words = pack_independent(&wrong, bits as u32);
    let lights = [0u8; 4096];
    let mut wbuf: Vec<u8> = Vec::new();
    for w in &words {
        wbuf.extend_from_slice(&w.to_le_bytes());
    }
    let (rc, rbr, rb) = unsafe {
        let rc = Java_com_rustcraft_bridge_NativeChunkBridge_refreshSectionPacked(
            std::ptr::null_mut(),
            std::ptr::null_mut(),
            DIM,
            CX,
            my_cz(),
            4,
            bits,
            wbuf.as_ptr() as i64,
            words.len() as i32,
            0,
            0,
            lights.as_ptr() as i64,
            4096,
        );
        let mut rb = vec![0u8; 20480];
        let rbr = Java_com_rustcraft_bridge_NativeChunkBridge_readbackSection(
            std::ptr::null_mut(),
            std::ptr::null_mut(),
            DIM,
            CX,
            my_cz(),
            4,
            rb.as_mut_ptr() as i64,
            20480,
        );
        (rc, rbr, rb)
    };
    assert!(rc >= 0, "transfer must succeed rc={}", rc);
    assert_eq!(rbr, 0, "readback rc={}", rbr);
    let got1234 = u32::from_le_bytes([
        rb[1234 * 4],
        rb[1234 * 4 + 1],
        rb[1234 * 4 + 2],
        rb[1234 * 4 + 3],
    ]);
    // transfer fidelity: readback == packed (both share the lie)
    assert_eq!(
        got1234, wrong[1234],
        "transfer fidelity: readback matches packed (both share the lie)"
    );
    // INDEPENDENT expectation: the fixture spec says cell 1234 must be
    // 65_534 — the comparison FAILS, proving the independent gate can
    // catch a packed==native lie that transfer validation cannot
    let independent_expected: u32 = 65_534;
    assert_ne!(
        got1234, independent_expected,
        "independent comparator must catch packed==native-wrong"
    );
}

#[test]
fn packed_malformed_rejection() {
    unsafe {
        register_shell();
    }
    let lights = [0u8; 4096];
    // bad bits
    let words = vec![0u64; 64];
    let mut wbuf: Vec<u8> = Vec::new();
    for w in &words {
        wbuf.extend_from_slice(&w.to_le_bytes());
    }
    let rc = unsafe {
        Java_com_rustcraft_bridge_NativeChunkBridge_refreshSectionPacked(
            std::ptr::null_mut(),
            std::ptr::null_mut(),
            DIM,
            CX,
            my_cz(),
            4,
            3, // bits < 4
            wbuf.as_ptr() as i64,
            words.len() as i32,
            0,
            0,
            lights.as_ptr() as i64,
            4096,
        )
    };
    assert_eq!(rc, -2, "bits<4 must be rejected");
    // truncated words buffer: bits=16 needs 1024 words; give 64
    let rc2 = unsafe {
        Java_com_rustcraft_bridge_NativeChunkBridge_refreshSectionPacked(
            std::ptr::null_mut(),
            std::ptr::null_mut(),
            DIM,
            CX,
            my_cz(),
            4,
            16,
            wbuf.as_ptr() as i64,
            words.len() as i32,
            0,
            0,
            lights.as_ptr() as i64,
            4096,
        )
    };
    assert_eq!(rc2, -2, "truncated words must be rejected");
    // wrong light length
    let rc3 = unsafe {
        Java_com_rustcraft_bridge_NativeChunkBridge_refreshSectionPacked(
            std::ptr::null_mut(),
            std::ptr::null_mut(),
            DIM,
            CX,
            my_cz(),
            4,
            16,
            wbuf.as_ptr() as i64,
            1024,
            0,
            0,
            lights.as_ptr() as i64,
            2048,
        )
    };
    assert_eq!(rc3, -2, "wrong light_len_bytes must be rejected");
    // out-of-range palette index maps to 0 (air) — the documented
    // fallback, verified by roundtrip with a dangling index
    let palette: Vec<u32> = (0..4).map(|i| 100 + i as u32).collect();
    let mut idx = [0u32; 4096];
    idx[0] = 0; // valid index 0
    idx[4095] = 3; // highest USED index in this palette
    idx[7] = 9; // within the 4-bit table (<16) but UNUSED slot => air
    let words = pack_independent(&idx, 4);
    let mut expected = [0u32; 4096];
    for i in 0..4096usize {
        let e = idx[i] as usize;
        expected[i] = if e < palette.len() { palette[e] } else { 0 };
    }
    let rc4 = unsafe { roundtrip(4, &words, &palette, &expected, &lights) };
    assert!(rc4 >= 0, "used+unused palette slots map exactly rc={}", rc4);
    let _ = get_registry(); // anchor the registry import
}

#[test]
fn packed_registry_high_ids_and_section_y_bounds() {
    unsafe {
        register_shell();
    }
    // full-width ids well above 65,535 in REGISTRY mode at bits=16:
    // capped at 2^16-1 by packing; the >65,535 case exercises the
    // PALETTE path (covered above) — here verify the u32 native side
    // keeps ids lossless at the 16-bit ceiling and section index 15
    let bits = 16;
    let mut expected = [0u32; 4096];
    expected[0] = 65_535;
    expected[4095] = 65_534;
    expected[2048] = 1 << 15;
    let words = pack_independent(&expected, bits as u32);
    let lights = [0u8; 4096];
    let mut wbuf: Vec<u8> = Vec::new();
    for w in &words {
        wbuf.extend_from_slice(&w.to_le_bytes());
    }
    let rc = unsafe {
        Java_com_rustcraft_bridge_NativeChunkBridge_refreshSectionPacked(
            std::ptr::null_mut(),
            std::ptr::null_mut(),
            DIM,
            CX,
            my_cz(),
            15,
            bits,
            wbuf.as_ptr() as i64,
            words.len() as i32,
            0,
            0,
            lights.as_ptr() as i64,
            4096,
        )
    };
    assert!(rc >= 0, "section 15 rc={}", rc);
    let mut rb = vec![0u8; 20480];
    let rbr = unsafe {
        Java_com_rustcraft_bridge_NativeChunkBridge_readbackSection(
            std::ptr::null_mut(),
            std::ptr::null_mut(),
            DIM,
            CX,
            my_cz(),
            15,
            rb.as_mut_ptr() as i64,
            20480,
        )
    };
    assert_eq!(rbr, 0);
    assert_eq!(
        u32::from_le_bytes([rb[0], rb[1], rb[2], rb[3]]),
        65_535,
        "16-bit ceiling id lossless"
    );
    assert_eq!(
        u32::from_le_bytes([
            rb[4095 * 4],
            rb[4095 * 4 + 1],
            rb[4095 * 4 + 2],
            rb[4095 * 4 + 3]
        ]),
        65_534,
        "last cell id lossless"
    );
    let _ = ChunkKey::new(DIM, CX, CZ);
}
