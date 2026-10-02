//! Benchmark comparing candidate light storage primitives:
//! A. AtomicU8 + word-aligned Java-style CAS
//! B. AtomicU32 packed-word design (512 words, 8 nibbles/word)

use std::sync::atomic::{AtomicU32, AtomicU8, Ordering};
use std::time::Instant;

const NIBBLES_PER_SECTION: usize = 4096;
const BYTES_PER_SECTION: usize = 2048;
const WORDS_PER_SECTION: usize = 512;

struct LightStorageU8 {
    bytes: [AtomicU8; BYTES_PER_SECTION],
}

impl LightStorageU8 {
    fn new() -> Self {
        let raw = [0u8; BYTES_PER_SECTION];
        let bytes: [AtomicU8; BYTES_PER_SECTION] = unsafe { std::mem::transmute(raw) };
        Self { bytes }
    }

    #[inline(always)]
    fn get(&self, idx: usize) -> u8 {
        let b = self.bytes[idx >> 1].load(Ordering::Acquire);
        if (idx & 1) == 0 {
            b & 0x0F
        } else {
            (b >> 4) & 0x0F
        }
    }

    #[inline(always)]
    fn set(&self, idx: usize, val: u8) -> bool {
        let byte_idx = idx >> 1;
        let is_odd = (idx & 1) != 0;
        let val_nibble = val & 0x0F;
        let cell = &self.bytes[byte_idx];
        let mut cur = cell.load(Ordering::Relaxed);
        loop {
            let old_nibble = if is_odd { (cur >> 4) & 0x0F } else { cur & 0x0F };
            if old_nibble == val_nibble {
                return false;
            }
            let next = if is_odd {
                (cur & 0x0F) | (val_nibble << 4)
            } else {
                (cur & 0xF0) | val_nibble
            };
            match cell.compare_exchange_weak(cur, next, Ordering::Release, Ordering::Relaxed) {
                Ok(_) => return true,
                Err(actual) => cur = actual,
            }
        }
    }
}

#[repr(C, align(64))]
struct LightStorageU32 {
    words: [AtomicU32; WORDS_PER_SECTION],
}

impl LightStorageU32 {
    fn new() -> Self {
        let raw = [0u32; WORDS_PER_SECTION];
        let words: [AtomicU32; WORDS_PER_SECTION] = unsafe { std::mem::transmute(raw) };
        Self { words }
    }

    #[inline(always)]
    fn get(&self, idx: usize) -> u8 {
        let word_idx = idx >> 3; // 8 nibbles per u32
        let shift = (idx & 7) << 2; // (idx % 8) * 4
        let w = self.words[word_idx].load(Ordering::Acquire);
        ((w >> shift) & 0x0F) as u8
    }

    #[inline(always)]
    fn set(&self, idx: usize, val: u8) -> bool {
        let word_idx = idx >> 3;
        let shift = (idx & 7) << 2;
        let mask = 0x0F << shift;
        let new_bits = ((val & 0x0F) as u32) << shift;
        let cell = &self.words[word_idx];
        let mut cur = cell.load(Ordering::Relaxed);
        loop {
            let cur_nibble = (cur >> shift) & 0x0F;
            if cur_nibble == (val & 0x0F) as u32 {
                return false;
            }
            let next = (cur & !mask) | new_bits;
            match cell.compare_exchange_weak(cur, next, Ordering::Release, Ordering::Relaxed) {
                Ok(_) => return true,
                Err(actual) => cur = actual,
            }
        }
    }
}

#[test]
fn bench_light_representations() {
    println!("\n=== BENCHMARK: Light Representation (AtomicU8 vs AtomicU32) ===");
    let u8_storage = LightStorageU8::new();
    let u32_storage = LightStorageU32::new();

    let iters = 2_000_000;

    // 1. Sequential Read
    let t0 = Instant::now();
    let mut sum8 = 0u64;
    for i in 0..iters {
        sum8 += u8_storage.get(i % NIBBLES_PER_SECTION) as u64;
    }
    let dur_read8 = t0.elapsed();

    let t0 = Instant::now();
    let mut sum32 = 0u64;
    for i in 0..iters {
        sum32 += u32_storage.get(i % NIBBLES_PER_SECTION) as u64;
    }
    let dur_read32 = t0.elapsed();

    println!("Sequential Reads ({} iters):", iters);
    println!("  AtomicU8:  {:?} ({:.2} ns/op)", dur_read8, dur_read8.as_nanos() as f64 / iters as f64);
    println!("  AtomicU32: {:?} ({:.2} ns/op)", dur_read32, dur_read32.as_nanos() as f64 / iters as f64);
    assert_eq!(sum8, sum32);

    // 2. Sequential Writes (alternating nibble values)
    let t0 = Instant::now();
    for i in 0..iters {
        u8_storage.set(i % NIBBLES_PER_SECTION, ((i ^ (i >> 3)) & 0x0F) as u8);
    }
    let dur_write8 = t0.elapsed();

    let t0 = Instant::now();
    for i in 0..iters {
        u32_storage.set(i % NIBBLES_PER_SECTION, ((i ^ (i >> 3)) & 0x0F) as u8);
    }
    let dur_write32 = t0.elapsed();

    println!("Sequential Writes ({} iters):", iters);
    println!("  AtomicU8:  {:?} ({:.2} ns/op)", dur_write8, dur_write8.as_nanos() as f64 / iters as f64);
    println!("  AtomicU32: {:?} ({:.2} ns/op)", dur_write32, dur_write32.as_nanos() as f64 / iters as f64);

    // Verify parity
    for i in 0..NIBBLES_PER_SECTION {
        assert_eq!(u8_storage.get(i), u32_storage.get(i), "Mismatch at nibble {}", i);
    }

    // 3. Same-Word Contention (Writes targeting nibbles 0..7 in word 0)
    let t0 = Instant::now();
    for i in 0..iters {
        u8_storage.set(i % 8, ((i + 1) & 0x0F) as u8);
    }
    let dur_cont8 = t0.elapsed();

    let t0 = Instant::now();
    for i in 0..iters {
        u32_storage.set(i % 8, ((i + 1) & 0x0F) as u8);
    }
    let dur_cont32 = t0.elapsed();

    println!("Same-Word Contention Writes ({} iters):", iters);
    println!("  AtomicU8:  {:?} ({:.2} ns/op)", dur_cont8, dur_cont8.as_nanos() as f64 / iters as f64);
    println!("  AtomicU32: {:?} ({:.2} ns/op)", dur_cont32, dur_cont32.as_nanos() as f64 / iters as f64);

    // 4. Memory size
    println!("Memory Size per Section Light Array:");
    println!("  AtomicU8:  {} bytes", std::mem::size_of_val(&u8_storage.bytes));
    println!("  AtomicU32: {} bytes", std::mem::size_of_val(&u32_storage.words));
    assert_eq!(std::mem::size_of_val(&u8_storage.bytes), 2048);
    assert_eq!(std::mem::size_of_val(&u32_storage.words), 2048);
}
