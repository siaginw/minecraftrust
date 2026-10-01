//! Synthetic native-state checks through the actual JNI export (no JVM/Forge).
use native_chunk::{ChunkHandle, ChunkLifecycle, NativeChunk};
use rustcraft_ffi::{
    get_registry, Java_com_rustcraft_bridge_NativeChunkBridge_encodePacketPayload as legacy_encode,
    Java_com_rustcraft_bridge_NativeChunkBridge_encodePacketPayloadV2 as encode_v2,
    PACKET_V2_SUCCESS_TAG,
};
use std::ptr::null_mut;
use std::sync::atomic::{AtomicI32, Ordering};

const CAPACITY: usize = 256 * 1024;
static NEXT_COORD: AtomicI32 = AtomicI32::new(1);

struct Fixture(ChunkHandle);

impl Fixture {
    fn new(mask: u16) -> Self {
        let registry = get_registry();
        let coordinate = NEXT_COORD.fetch_add(1, Ordering::Relaxed);
        let mut chunk = NativeChunk::new(-20001, coordinate, 0, registry.next_generation_id());
        chunk.biomes.fill(42);
        for y in 0..16 {
            if mask & (1 << y) != 0 {
                chunk.refresh_section(
                    y,
                    &[u16::from(y) + 1; 4096],
                    Some(&[y; 2048]),
                    Some(&[255 - y; 2048]),
                );
            }
        }
        Self(registry.insert(chunk))
    }

    fn call(&self, output: &mut [u8], sky: bool, full: bool) -> i64 {
        unsafe {
            self.raw(
                output.as_mut_ptr() as i64,
                output.len() as i32,
                self.0.generation_id as i64,
                sky as u8,
                full as u8,
            )
        }
    }

    unsafe fn raw(&self, address: i64, capacity: i32, generation: i64, sky: u8, full: u8) -> i64 {
        encode_v2(
            null_mut(),
            null_mut(),
            self.0.key.dim,
            self.0.key.cx,
            self.0.key.cz,
            generation,
            sky,
            full,
            address,
            capacity,
        )
    }
}

impl Drop for Fixture {
    fn drop(&mut self) {
        get_registry().remove(self.0.key);
    }
}

fn success(packed: i64) -> (usize, u16) {
    // Independent Java-compatible bit extraction; failure sign checked first.
    assert!(packed > 0);
    assert_eq!(packed & !0x4000_7fff_ffff_ffff, 0);
    assert_eq!(packed & PACKET_V2_SUCCESS_TAG, PACKET_V2_SUCCESS_TAG);
    let bytes = ((packed >> 16) & 0x7fff_ffff) as usize;
    let mask = (packed & 0xffff) as u16;
    assert!(bytes != 0 || mask == 0);
    (bytes, mask)
}

fn varint(bytes: &[u8], offset: &mut usize) -> usize {
    let mut value = 0usize;
    for shift in (0..35).step_by(7) {
        let byte = bytes[*offset];
        *offset += 1;
        assert!(shift < 28 || byte <= 15);
        value |= usize::from(byte & 127) << shift;
        if byte & 128 == 0 {
            return value;
        }
    }
    panic!("malformed VarInt");
}

fn scan(payload: &[u8], sky: bool, full: bool) -> Vec<u8> {
    let end = if full {
        assert!(payload.len() >= 256);
        let end = payload.len() - 256;
        assert_eq!(&payload[end..], &[42; 256]);
        end
    } else {
        payload.len()
    };
    let mut offset = 0;
    let mut ys = Vec::new();
    while offset < end {
        assert_eq!(payload[offset], 4);
        offset += 1;
        assert_eq!(varint(payload, &mut offset), 2);
        assert_eq!(varint(payload, &mut offset), 0);
        let state = varint(payload, &mut offset);
        assert!((1..=16).contains(&state));
        let y = (state - 1) as u8;
        assert_eq!(varint(payload, &mut offset), 256);
        assert_eq!(&payload[offset..offset + 2048], &[0x11; 2048]);
        offset += 2048;
        assert_eq!(&payload[offset..offset + 2048], &[y; 2048]);
        offset += 2048;
        if sky {
            assert_eq!(&payload[offset..offset + 2048], &[255 - y; 2048]);
            offset += 2048;
        }
        ys.push(y);
    }
    assert_eq!(offset, end);
    ys
}

#[test]
fn jni_v2_success_pairs_real_payload_and_exact_mask() {
    for mask in [0, 0x001f, 0x8421, 0xffff] {
        let fixture = Fixture::new(mask);
        for sky in [false, true] {
            for full in [false, true] {
                let mut output = vec![0xa5; CAPACITY];
                let (count, emitted) = success(fixture.call(&mut output, sky, full));
                assert_eq!(emitted, mask);
                assert_eq!(
                    scan(&output[..count], sky, full),
                    (0..16).filter(|y| mask & (1 << y) != 0).collect::<Vec<_>>()
                );
                assert!(output[count..].iter().all(|byte| *byte == 0xa5));
            }
        }
    }
}

#[test]
fn legacy_length_only_export_keeps_its_abi_and_bytes() {
    let fixture = Fixture::new(0x0021);
    let mut v2 = vec![0; CAPACITY];
    let mut old = vec![0; CAPACITY];
    let (count, mask) = success(fixture.call(&mut v2, true, true));
    let legacy_count = unsafe {
        legacy_encode(
            null_mut(),
            null_mut(),
            fixture.0.key.dim,
            fixture.0.key.cx,
            fixture.0.key.cz,
            fixture.0.generation_id as i64,
            1,
            1,
            old.as_mut_ptr() as i64,
            old.len() as i32,
        )
    };
    assert_eq!(legacy_count, count as i32);
    assert_eq!(&old[..count], &v2[..count]);
    assert_eq!(mask, 0x0021);
}

#[test]
fn missing_selected_section_is_an_error_then_repair_can_retry() {
    let fixture = Fixture::new(0x001f);
    let entry = get_registry().get(&fixture.0).unwrap();
    entry.write().unwrap().primary_bit_mask = 0x003f;
    let mut output = vec![0xa5; CAPACITY];
    assert_eq!(fixture.call(&mut output, true, true), -4);
    assert!(output.iter().all(|byte| *byte == 0xa5));
    entry
        .write()
        .unwrap()
        .refresh_section(5, &[6; 4096], Some(&[5; 2048]), Some(&[250; 2048]));
    let (count, mask) = success(fixture.call(&mut output, true, true));
    assert_eq!(mask, 0x003f);
    assert_eq!(scan(&output[..count], true, true), vec![0, 1, 2, 3, 4, 5]);
}

#[test]
fn missing_handle_never_returns_metadata() {
    let fixture = Fixture::new(1);
    get_registry().remove(fixture.0.key);
    assert_eq!(fixture.call(&mut [0; 16], false, false), -2);
}

#[test]
fn stale_generation_rejects_then_valid_generation_retries() {
    let fixture = Fixture::new(1);
    let mut output = vec![0xa5; CAPACITY];
    for generation in [-1, 0, fixture.0.generation_id as i64 + 1] {
        assert_eq!(
            unsafe {
                fixture.raw(
                    output.as_mut_ptr() as i64,
                    output.len() as i32,
                    generation,
                    0,
                    0,
                )
            },
            -3
        );
        assert!(output.iter().all(|byte| *byte == 0xa5));
    }
    assert_eq!(success(fixture.call(&mut output, false, false)).1, 1);
}

#[test]
fn invalidated_unloading_and_freed_state_are_not_eligible() {
    let fixture = Fixture::new(1);
    let entry = get_registry().get(&fixture.0).unwrap();
    for lifecycle in [
        ChunkLifecycle::Invalidated,
        ChunkLifecycle::Unloading,
        ChunkLifecycle::Freed,
    ] {
        entry.write().unwrap().lifecycle = lifecycle;
        assert_eq!(fixture.call(&mut [0; 16], false, false), -3);
    }
    // Synthetic repair only. This is not an authorization to revive production
    // invalidated state without a coherent refresh and lifecycle protocol.
    entry.write().unwrap().lifecycle = ChunkLifecycle::ActiveNative;
    assert_eq!(
        success(fixture.call(&mut vec![0; CAPACITY], false, false)).1,
        1
    );
}

#[test]
fn capacity_failure_and_late_failure_publish_no_partial_result() {
    let fixture = Fixture::new(1);
    let mut reference = vec![0; CAPACITY];
    let (section_count, _) = success(fixture.call(&mut reference, true, false));
    assert_eq!(fixture.call(&mut [0; 16], true, true), -5);
    let mut partial = vec![0xa5; section_count + 255];
    assert_eq!(fixture.call(&mut partial, true, true), -5);
    // Actual section bytes may have been written. The only return is error -5.
    assert_eq!(&partial[..section_count], &reference[..section_count]);
    let (count, mask) = success(fixture.call(&mut reference, true, true));
    assert_eq!(mask, 1);
    assert_eq!(scan(&reference[..count], true, true), vec![0]);
}

#[test]
fn invalid_numeric_arguments_are_rejected_without_dereference() {
    let fixture = Fixture::new(1);
    let mut output = vec![0xa5; CAPACITY];
    let address = output.as_mut_ptr() as i64;
    for (ptr, capacity, sky, full) in [
        (0, 16, 0, 0),
        (-1, 16, 0, 0),
        (i64::MAX, 16, 0, 0),
        (address, 0, 0, 0),
        (address, -1, 0, 0),
        (address, output.len() as i32, 2, 0),
        (address, output.len() as i32, 0, 255),
    ] {
        assert_eq!(
            unsafe { fixture.raw(ptr, capacity, fixture.0.generation_id as i64, sky, full) },
            -1
        );
        assert!(output.iter().all(|byte| *byte == 0xa5));
    }
    assert_eq!(success(fixture.call(&mut output, false, false)).1, 1);
    // Arbitrary unmapped nonzero pointers are outside the unsafe JNI contract,
    // not panic tests: passing one could cause process-level access violation.
}

#[test]
fn returned_metadata_survives_later_native_mask_change() {
    let fixture = Fixture::new(0x0021);
    let mut output = vec![0; CAPACITY];
    let packed = fixture.call(&mut output, false, true);
    let (count, mask) = success(packed);
    get_registry()
        .get(&fixture.0)
        .unwrap()
        .write()
        .unwrap()
        .refresh_section(5, &[0; 4096], None, None);
    assert_eq!(success(packed), (count, mask));
    assert_eq!(mask, 0x0021);
    assert_eq!(scan(&output[..count], false, true), vec![0, 5]);
    assert_eq!(
        success(fixture.call(&mut vec![0; CAPACITY], false, true)).1,
        1
    );
}

#[test]
fn seed_from_transport_and_retained_encode_v2() {
    let mut transport = Vec::new();
    transport.extend_from_slice(b"RCSNAP02");
    transport.extend_from_slice(&2u16.to_be_bytes()); // version
    transport.push(1); // full=1, skylight=0
    transport.push(1); // storage
    transport.push(14); // source bits
    transport.push(1); // scope
    transport.extend_from_slice(&0u16.to_be_bytes());
    transport.extend_from_slice(&0i32.to_be_bytes()); // dim
    transport.extend_from_slice(&12345i32.to_be_bytes()); // chunk x
    transport.extend_from_slice(&(-6789i32).to_be_bytes()); // chunk z
    transport.extend_from_slice(&1u64.to_be_bytes()); // generation
    transport.extend_from_slice(&0xffffu16.to_be_bytes()); // filter
    transport.extend_from_slice(&1u16.to_be_bytes()); // mask (section 0)
    transport.extend_from_slice(&1u64.to_be_bytes()); // event
    transport.extend_from_slice(&1u64.to_be_bytes()); // owner
    transport.extend_from_slice(&1u64.to_be_bytes()); // capture
    transport.extend_from_slice(&1u64.to_be_bytes()); // epoch start
    transport.extend_from_slice(&1u64.to_be_bytes()); // epoch end
    transport.extend_from_slice(&1u64.to_be_bytes()); // inc start
    transport.extend_from_slice(&1u64.to_be_bytes()); // inc end
    transport.extend_from_slice(&[0u8; 32]); // digest
    assert_eq!(transport.len(), 128);
    transport.extend_from_slice(&1u16.to_be_bytes()); // section count
    transport.extend_from_slice(&8602u32.to_be_bytes()); // registry size
    transport.push(14); // bits

    // Section 0: y=0, states (uniform state 1 = stone)
    transport.push(0); // y
    transport.push(0); // unused
    transport.extend_from_slice(&4096u16.to_be_bytes()); // refcount
    transport.extend_from_slice(&1u16.to_be_bytes()); // palette len = 1
    transport.extend_from_slice(&1u16.to_be_bytes()); // palette[0] = 1
    transport.push(4); // bits = 4
    transport.extend_from_slice(&256u16.to_be_bytes()); // word count = 256
    transport.extend_from_slice(&[0u8; 256 * 8]); // all zeros (index 0)
    transport.extend_from_slice(&[0u8; 2048]); // block light
    // full chunk -> biomes
    transport.extend_from_slice(&[42u8; 256]);

    // 2. Invoke seedFromTransport
    let gen_id = unsafe {
        rustcraft_ffi::Java_com_rustcraft_bridge_NativeChunkBridge_seedFromTransport(
            null_mut(),
            null_mut(),
            transport.as_ptr() as i64,
            transport.len() as i32,
        )
    };
    assert!(gen_id > 0, "seedFromTransport must return valid generation_id, got {}", gen_id);

    // 3. Directly encode from retained state via encode_v2
    let mut out1 = vec![0u8; CAPACITY];
    let res1 = unsafe {
        encode_v2(
            null_mut(),
            null_mut(),
            0,
            12345,
            -6789,
            gen_id,
            0,
            1,
            out1.as_mut_ptr() as i64,
            out1.len() as i32,
        )
    };
    assert!(res1 > 0 && res1 & PACKET_V2_SUCCESS_TAG != 0, "encode_v2 must succeed");
    let (count1, mask1) = success(res1);
    assert_eq!(mask1, 1);
    assert!(count1 > 0);

    // 4. Second encode (hits wire cache)
    let mut out2 = vec![0u8; CAPACITY];
    let res2 = unsafe {
        encode_v2(
            null_mut(),
            null_mut(),
            0,
            12345,
            -6789,
            gen_id,
            0,
            1,
            out2.as_mut_ptr() as i64,
            out2.len() as i32,
        )
    };
    assert_eq!(res1, res2);
    assert_eq!(&out1[..count1], &out2[..count1], "Retained re-encode must match byte-for-byte");
}
