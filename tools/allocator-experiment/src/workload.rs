use crate::lossless;
use bumpalo::Bump;
use compression::{max_output_len, ZlibPacketCompressor, VANILLA_LEVEL};
use flate2::read::ZlibDecoder;
use native_state_vnext::section::{LayoutPolicy, NativeSection, SECTION_CELLS};
use rustcraft_core::DenseRuntimeStateId;
use std::{
    collections::VecDeque,
    io::Read,
    sync::{
        atomic::{AtomicUsize, Ordering},
        Arc,
    },
    thread,
};
pub const BASE: u32 = 65536;
pub const SECTIONS: usize = 4;
pub const RAW_CAP: usize = 65536;
pub const ARENA_LIMIT: usize = 512 * 1024;
pub const MAX_CHUNKS: usize = 32;
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum Scratch {
    Vec,
    Arena,
}
impl Scratch {
    pub fn name(self) -> &'static str {
        match self {
            Self::Vec => "vec",
            Self::Arena => "arena",
        }
    }
}
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum Delivery {
    SameThread,
    CrossThread,
}
impl Delivery {
    pub fn name(self) -> &'static str {
        match self {
            Self::SameThread => "same",
            Self::CrossThread => "cross",
        }
    }
}
fn policy() -> LayoutPolicy {
    LayoutPolicy {
        promote_unique: 129,
        demote_unique: 64,
        linear_lookup_max: 16,
        cold_sweeps: 2,
        cold_write_budget: 8,
        hot_write_threshold: 128,
    }
}
pub fn hash(bytes: &[u8]) -> u64 {
    bytes.iter().fold(0xcbf29ce484222325, |h, b| {
        (h ^ u64::from(*b)).wrapping_mul(0x100000001b3)
    })
}
fn name(out: &mut Vec<u8>, tag: u8, key: &str) {
    out.push(tag);
    out.extend((key.len() as u16).to_be_bytes());
    out.extend(key.bytes());
}
#[derive(Clone)]
pub struct Fixture {
    pub compressed: Vec<u8>,
    pub expected: Vec<u8>,
    pub raw: Vec<u8>,
    pub edit_offset: usize,
}
pub fn fixture(seed: u32) -> Fixture {
    let mut raw = vec![10, 0, 0];
    name(&mut raw, 3, "DataVersion");
    raw.extend(1343i32.to_be_bytes());
    name(&mut raw, 10, "Level");
    name(&mut raw, 4, "LastUpdate");
    let edit_offset = raw.len();
    raw.extend(i64::from(seed).to_be_bytes());
    name(&mut raw, 11, "NEID");
    raw.extend(1024i32.to_be_bytes());
    for i in 0..1024 {
        raw.extend((BASE + i).to_be_bytes());
    }
    name(&mut raw, 10, "fixture:opaque");
    name(&mut raw, 7, "payload");
    let n: i32 = [255, 4096, 32768, 65536][seed as usize % 4];
    raw.extend(n.to_be_bytes());
    raw.extend((0..n).map(|i| ((i as u32 * 17 + seed) % 251) as u8));
    name(&mut raw, 8, "raw_mutf8");
    raw.extend(5u16.to_be_bytes());
    raw.extend([0xc0, 0x80, 0xed, 0xa0, 0x80]);
    name(&mut raw, 5, "raw_nan");
    raw.extend(0x7fc01234u32.to_be_bytes());
    raw.extend([0, 0, 0]);
    let mut expected = raw.clone();
    expected[edit_offset..edit_offset + 8].copy_from_slice(&(i64::from(seed) + 1).to_be_bytes());
    let compressed = compress(&raw).unwrap();
    Fixture {
        compressed,
        expected,
        raw,
        edit_offset,
    }
}
fn compress(bytes: &[u8]) -> Result<Vec<u8>, String> {
    let mut compressor = ZlibPacketCompressor::new(VANILLA_LEVEL).unwrap();
    let mut out = vec![0; max_output_len(bytes.len())];
    let n = compressor
        .compress_into(bytes, &mut out)
        .map_err(|e| format!("compress {e:?}"))?;
    out.truncate(n);
    Ok(out)
}
pub fn inflate(bytes: &[u8]) -> Result<Vec<u8>, String> {
    let mut out = Vec::new();
    ZlibDecoder::new(bytes)
        .take((lossless::MAX_RAW + 1) as u64)
        .read_to_end(&mut out)
        .map_err(|e| e.to_string())?;
    if out.len() > lossless::MAX_RAW {
        return Err("inflate capacity".into());
    }
    Ok(out)
}
fn initial(seed: u32, y: usize, i: usize) -> DenseRuntimeStateId {
    let cardinality = [1, 8, 64, 256, 1024][(seed as usize + y) % 5];
    DenseRuntimeStateId(BASE + (i as u32 * 13 + seed + y as u32) % cardinality)
}
pub struct Published {
    pub packet: Vec<u8>,
    pub persistence: Vec<u8>,
    pub old_views: Vec<Arc<[DenseRuntimeStateId]>>,
    pub new_views: Vec<Arc<[DenseRuntimeStateId]>>,
    pub emitted_mask: u16,
    pub arena_reserved: usize,
    drops: Arc<AtomicUsize>,
}
impl Drop for Published {
    fn drop(&mut self) {
        self.drops.fetch_add(1, Ordering::SeqCst);
    }
}
impl Published {
    pub fn digest(&self) -> u64 {
        let mut h = hash(&self.packet).rotate_left(7) ^ hash(&self.persistence);
        for v in self.old_views.iter().chain(&self.new_views) {
            for id in v.iter() {
                h = h.rotate_left(3) ^ u64::from(id.0);
            }
        }
        h ^ u64::from(self.emitted_mask)
    }
    pub fn retained_payload(&self) -> usize {
        self.packet.len()
            + self.persistence.len()
            + self
                .old_views
                .iter()
                .chain(&self.new_views)
                .map(|v| v.len() * 4)
                .sum::<usize>()
    }
}
fn encoded(
    chunk: &mut native_chunk::NativeChunk,
    expected: &[Vec<u16>],
    raw: &mut [u8],
    out: &mut [u8],
    verify: bool,
) -> Result<(Vec<u8>, u16), String> {
    let mut offset = 0;
    let result = chunk
        .encode_packet_payload(true, true, raw, &mut offset)
        .map_err(str::to_owned)?;
    if result.emitted_mask != 15 || result.bytes_written != offset {
        return Err("mask/result contract".into());
    }
    if verify {
        decode_packet(&raw[..offset], expected)?;
    }
    let mut compressor = ZlibPacketCompressor::new(VANILLA_LEVEL).unwrap();
    let n = compressor
        .compress_into(&raw[..offset], out)
        .map_err(|e| format!("compress {e:?}"))?;
    // Publishing copies out of scratch in both lanes; an arena reference never escapes.
    Ok((out[..n].to_vec(), result.emitted_mask))
}
pub fn produce(
    seed: u32,
    fixture: &Fixture,
    scratch: Scratch,
    verify: bool,
    drops: Arc<AtomicUsize>,
) -> Result<Arc<Published>, String> {
    let mut chunk = native_chunk::NativeChunk::new(0, seed as i32, 0, 1);
    let mut old_views = Vec::with_capacity(SECTIONS);
    let mut new_views = Vec::with_capacity(SECTIONS);
    let mut expected = Vec::with_capacity(SECTIONS);
    for y in 0..SECTIONS {
        let mut reference: Vec<_> = (0..SECTION_CELLS).map(|i| initial(seed, y, i)).collect();
        let mut section = NativeSection::from_dense(&reference, policy())
            .map_err(|e| format!("section {e:?}"))?;
        old_views.push(Arc::from(section.dense()));
        for n in 0..64 {
            let i = (n * 61 + y * 17) % SECTION_CELLS;
            let value = DenseRuntimeStateId(BASE + (n as u32 * 17 + seed) % 1024);
            section.set(i, value).map_err(|e| format!("set {e:?}"))?;
            reference[i] = value;
        }
        section.maintenance_sweep();
        let dense = section.dense();
        if verify && dense != reference {
            return Err("independent dense state mismatch".into());
        }
        let mut wire = vec![0u16; SECTION_CELLS];
        for (i, id) in dense.iter().enumerate() {
            let local = id.0.checked_sub(BASE).ok_or("foreign dense id")?;
            if local >= 1024 {
                return Err("unqualified dense mapping".into());
            }
            wire[i] = (local + 1) as u16;
        }
        let values: &[u16; 4096] = wire.as_slice().try_into().unwrap();
        chunk.refresh_section(y as u8, values, Some(&[0; 2048]), Some(&[0; 2048]));
        expected.push(wire);
        new_views.push(Arc::from(dense));
    }
    let (packet, emitted_mask, arena_reserved) = match scratch {
        Scratch::Vec => {
            let mut raw = vec![0; RAW_CAP];
            let mut out = vec![0; max_output_len(RAW_CAP)];
            let (p, m) = encoded(&mut chunk, &expected, &mut raw, &mut out, verify)?;
            (p, m, 0)
        }
        Scratch::Arena => {
            let mut arena = Bump::new();
            arena.set_allocation_limit(Some(ARENA_LIMIT));
            let raw = arena
                .try_alloc_slice_fill_copy(RAW_CAP, 0u8)
                .map_err(|_| "arena capacity")?;
            let out = arena
                .try_alloc_slice_fill_copy(max_output_len(RAW_CAP), 0u8)
                .map_err(|_| "arena capacity")?;
            let (p, m) = encoded(&mut chunk, &expected, raw, out, verify)?;
            let reserved = arena.allocated_bytes_including_metadata();
            if reserved > ARENA_LIMIT {
                return Err("arena bound".into());
            }
            arena.reset();
            (p, m, reserved)
        }
    };
    let decoded = inflate(&fixture.compressed)?;
    let scan = lossless::scan(&decoded)?;
    if scan.data_version != Some(1343) || scan.max_neid != Some(BASE + 1023) {
        return Err("NBT metadata".into());
    }
    let edited = lossless::edit(&decoded, &scan)?;
    if verify && edited != fixture.expected {
        return Err("first divergence NBT opaque preservation".into());
    }
    let persistence = compress(&edited)?;
    Ok(Arc::new(Published {
        packet,
        persistence,
        old_views,
        new_views,
        emitted_mask,
        arena_reserved,
        drops,
    }))
}
/// Independent bit-by-bit decoder, deliberately not the production packing implementation.
pub fn decode_packet(bytes: &[u8], expected: &[Vec<u16>]) -> Result<(), String> {
    fn varint(bytes: &[u8], p: &mut usize) -> Result<usize, String> {
        let mut value = 0usize;
        for n in 0..5 {
            let b = *bytes.get(*p).ok_or("truncated varint")?;
            *p += 1;
            value |= usize::from(b & 127) << (n * 7);
            if b & 128 == 0 {
                return Ok(value);
            }
        }
        Err("varint length".into())
    }
    let mut p = 0;
    for section in expected {
        let bits = *bytes.get(p).ok_or("bits")? as usize;
        p += 1;
        if !(4..=13).contains(&bits) {
            return Err("bits range".into());
        }
        let count = varint(bytes, &mut p)?;
        let mut palette = Vec::with_capacity(count);
        for _ in 0..count {
            palette.push(varint(bytes, &mut p)?);
        }
        let words = varint(bytes, &mut p)?;
        if words != (4096 * bits).div_ceil(64) {
            return Err("word count".into());
        }
        let data = bytes.get(p..p + words * 8).ok_or("words")?;
        p += words * 8;
        for (i, expected) in section.iter().enumerate() {
            let mut value = 0usize;
            for bit in 0..bits {
                let absolute = i * bits + bit;
                let word = absolute / 64;
                let inner = absolute % 64;
                let byte = data[word * 8 + 7 - inner / 8];
                value |= usize::from((byte >> (inner % 8)) & 1) << bit;
            }
            let wire = if count == 0 {
                value
            } else {
                *palette.get(value).ok_or("palette")?
            };
            if wire != usize::from(*expected) {
                return Err(format!("packet divergence cell={i}"));
            }
        }
        if bytes
            .get(p..p + 4096)
            .ok_or("lights")?
            .iter()
            .any(|b| *b != 0)
        {
            return Err("light mismatch".into());
        }
        p += 4096;
    }
    if bytes
        .get(p..p + 256)
        .ok_or("biomes")?
        .iter()
        .any(|b| *b != 0)
        || p + 256 != bytes.len()
    {
        return Err("biome/trailing mismatch".into());
    }
    Ok(())
}
#[derive(Debug)]
pub struct Batch {
    pub digest: u64,
    pub drops: usize,
    pub max_retained_payload: usize,
    pub arena_reserved_max: usize,
    pub publications: usize,
    pub cross_thread_final_frees: usize,
    pub retained_after_producer_exit_payload: usize,
    pub retained_requested_live: u64,
    pub retained_working_set: Option<u64>,
    pub retained_private_commit: Option<u64>,
}
fn consume(
    items: impl Iterator<Item = Arc<Published>>,
    long_lived: &mut Vec<Arc<Published>>,
) -> (u64, usize, usize) {
    let mut ring = VecDeque::new();
    let mut h = 0u64;
    let mut peak = 0;
    let mut arena = 0;
    for (index, item) in items.enumerate() {
        h = h.rotate_left(7) ^ item.digest();
        arena = arena.max(item.arena_reserved);
        if index % 4 == 0 {
            long_lived.push(item.clone());
        }
        ring.push_back(item);
        if ring.len() > 4 {
            ring.pop_front();
        }
        let retained = ring.iter().map(|v| v.retained_payload()).sum::<usize>()
            + long_lived
                .iter()
                .filter(|v| !ring.iter().any(|r| Arc::ptr_eq(r, v)))
                .map(|v| v.retained_payload())
                .sum::<usize>();
        peak = peak.max(retained);
    }
    drop(ring);
    (h, peak, arena)
}
pub fn batch(
    fixtures: Arc<Vec<Fixture>>,
    scratch: Scratch,
    delivery: Delivery,
    verify: bool,
) -> Result<Batch, String> {
    if fixtures.is_empty() || fixtures.len() > MAX_CHUNKS {
        return Err("batch bound".into());
    }
    let count = fixtures.len();
    let drops = Arc::new(AtomicUsize::new(0));
    let mut long_lived = Vec::new();
    let (digest, max_retained_payload, arena_reserved_max) = match delivery {
        Delivery::SameThread => {
            let mut failure = None;
            let iter = (0..count)
                .map(
                    |i| match produce(i as u32, &fixtures[i], scratch, verify, drops.clone()) {
                        Ok(p) => Some(p),
                        Err(e) => {
                            failure = Some(e);
                            None
                        }
                    },
                )
                .take_while(Option::is_some)
                .flatten();
            let result = consume(iter, &mut long_lived);
            if let Some(e) = failure {
                return Err(e);
            }
            result
        }
        Delivery::CrossThread => {
            let (tx, rx) = std::sync::mpsc::sync_channel(2);
            let producer_drops = drops.clone();
            let worker = thread::spawn(move || -> Result<(), String> {
                for i in 0..count {
                    let item = produce(
                        i as u32,
                        &fixtures[i],
                        scratch,
                        verify,
                        producer_drops.clone(),
                    )?;
                    tx.send(item).map_err(|_| "receiver closed")?;
                }
                Ok(())
            });
            let result = consume(rx.into_iter(), &mut long_lived);
            worker.join().map_err(|_| "producer panic")??;
            result
        }
    };
    // Producer has exited in cross lane. These old views remain valid until the final consumer drops.
    if drops.load(Ordering::SeqCst) + long_lived.len() != count {
        return Err("destructor/refcount mismatch before final release".into());
    }
    for item in &long_lived {
        if Arc::strong_count(item) != 1 {
            return Err("unexpected retained owner".into());
        }
        std::hint::black_box(item.digest());
    }
    let retained_after_producer_exit_payload =
        long_lived.iter().map(|v| v.retained_payload()).sum();
    let retained_requested_live = crate::meter::snapshot().live;
    let retained_memory = native_observability::process_memory();
    drop(long_lived);
    if drops.load(Ordering::SeqCst) != count {
        return Err("destructor mismatch after final release".into());
    }
    Ok(Batch {
        digest,
        drops: count,
        max_retained_payload,
        arena_reserved_max,
        publications: count,
        retained_after_producer_exit_payload,
        retained_requested_live,
        retained_working_set: retained_memory.working_set_bytes,
        retained_private_commit: retained_memory.private_commit_bytes,
        cross_thread_final_frees: if delivery == Delivery::CrossThread {
            count
        } else {
            0
        },
    })
}
