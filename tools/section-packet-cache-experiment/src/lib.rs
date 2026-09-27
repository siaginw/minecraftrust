//! Isolated Protocol 340 body/cache prototype. No runtime authority or compression.
use native_chunk::chunk::NativeChunk as LegacyChunk;
use native_chunk::section::NativeSection as LegacySection;
use native_state_vnext::section::{LayoutPolicy, NativeSection};
use rustcraft_core::DenseRuntimeStateId;
use std::collections::{BTreeMap, HashMap};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::Arc;

pub const CELLS: usize = 4096;
pub const MAX_BODY: usize = 256 * 1024;
pub const RUNTIME_BASE: u32 = 70_000;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Error {
    MissingSection,
    MissingMapping,
    WireRange,
    Capacity,
    Unsupported,
    Stale,
    Oversized,
    RetentionPressure,
    Exhausted,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub struct Resource {
    pub session: u64,
    pub world: i32,
    pub x: i32,
    pub z: i32,
}
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub struct Key {
    pub resource: Resource,
    pub incarnation: u64,
    pub lifecycle: u64,
    pub owner: u64,
    pub registry: u64,
    pub states: u64,
    pub light: u64,
    pub biomes: u64,
    pub protocol: u32,
    pub dimension_rules: u64,
    pub recipient_rules: u64,
    pub mask: u16,
    pub skylight: bool,
    pub full: bool,
}
impl Key {
    pub fn fixture(mask: u16) -> Self {
        Self {
            resource: Resource {
                session: 1,
                world: 0,
                x: -2,
                z: 3,
            },
            incarnation: 1,
            lifecycle: 1,
            owner: 1,
            registry: 1,
            states: 1,
            light: 1,
            biomes: 1,
            protocol: 340,
            dimension_rules: 1,
            recipient_rules: 0,
            mask,
            skylight: true,
            full: true,
        }
    }
}

pub fn policy(dense: bool) -> LayoutPolicy {
    LayoutPolicy {
        promote_unique: if dense { 4 } else { 256 },
        demote_unique: 2,
        linear_lookup_max: if dense { 3 } else { 16 },
        cold_sweeps: 2,
        cold_write_budget: 4,
        hot_write_threshold: u64::MAX,
    }
}

#[derive(Clone)]
pub struct Section {
    pub data: NativeSection,
    pub block_light: [u8; 2048],
    pub sky_light: [u8; 2048],
}
#[derive(Clone)]
pub struct World {
    pub key: Key,
    pub sections: BTreeMap<u8, Section>,
    pub mapping: BTreeMap<u32, u16>,
    pub biomes: [u8; 256],
}
impl World {
    pub fn fixture(cardinality: usize, mask: u16, dense: bool) -> Self {
        assert!((1..=4096).contains(&cardinality));
        let mapping = (0..cardinality)
            .map(|i| (RUNTIME_BASE + i as u32, (i + 1) as u16))
            .collect();
        let mut sections = BTreeMap::new();
        for y in 0..16 {
            if mask & (1 << y) != 0 {
                let states: Vec<_> = (0..CELLS)
                    .map(|i| {
                        DenseRuntimeStateId(RUNTIME_BASE + ((i + y as usize) % cardinality) as u32)
                    })
                    .collect();
                sections.insert(
                    y,
                    Section {
                        data: NativeSection::from_dense(&states, policy(dense)).unwrap(),
                        block_light: [y; 2048],
                        sky_light: [255 - y; 2048],
                    },
                );
            }
        }
        Self {
            key: Key::fixture(mask),
            sections,
            mapping,
            biomes: [4; 256],
        }
    }
    pub fn wire(&self, id: DenseRuntimeStateId) -> Result<u16, Error> {
        let mapped = *self.mapping.get(&id.0).ok_or(Error::MissingMapping)?;
        if mapped >= 8192 {
            return Err(Error::WireRange);
        }
        match self.key.recipient_rules {
            0 => Ok(mapped),
            1 => Ok(if mapped == 0 { 0 } else { 1 }),
            _ => Err(Error::Unsupported),
        }
    }
    pub fn legacy(&self) -> Result<LegacyChunk, Error> {
        self.validate()?;
        if native_chunk::section::global_palette_bits() != 13 {
            return Err(Error::Unsupported);
        }
        let mut chunk = LegacyChunk::new(
            self.key.resource.world,
            self.key.resource.x,
            self.key.resource.z,
            self.key.lifecycle,
        );
        chunk.biomes = self.biomes;
        for y in 0..16 {
            if self.key.mask & (1 << y) != 0 {
                let s = self.sections.get(&y).ok_or(Error::MissingSection)?;
                let mut states = [0; CELLS];
                for (i, v) in states.iter_mut().enumerate() {
                    *v = self.wire(s.data.get(i).unwrap())?;
                }
                let mut legacy = LegacySection::new(y);
                legacy.replace_states(&states, Some(&s.block_light), Some(&s.sky_light));
                chunk.sections[y as usize] = Some(Box::new(legacy));
            }
        }
        chunk.primary_bit_mask = self.key.mask;
        Ok(chunk)
    }
    fn validate(&self) -> Result<(), Error> {
        if self.key.protocol != 340 || self.key.dimension_rules != u64::from(self.key.skylight) {
            return Err(Error::Unsupported);
        }
        if self.key.recipient_rules > 1 {
            return Err(Error::Unsupported);
        }
        for y in 0..16 {
            if self.key.mask & (1 << y) != 0 && !self.sections.contains_key(&y) {
                return Err(Error::MissingSection);
            }
        }
        Ok(())
    }
    pub fn mutate(&mut self, step: usize) {
        let y = *self.sections.keys().next().unwrap();
        let cardinality = self.mapping.len();
        let index = step % CELLS;
        let old = self.sections[&y].data.get(index).unwrap();
        let next = DenseRuntimeStateId(
            RUNTIME_BASE + ((old.0 as usize - RUNTIME_BASE as usize + 1) % cardinality) as u32,
        );
        if self
            .sections
            .get_mut(&y)
            .unwrap()
            .data
            .set(index, next)
            .unwrap()
        {
            self.key.states = self.key.states.checked_add(1).unwrap();
        }
    }
}

#[derive(Debug)]
pub struct Encoded {
    key: Key,
    emitted_mask: u16,
    bytes: Box<[u8]>,
    digest: u64,
}
impl Encoded {
    pub fn into_bytes(self) -> Box<[u8]> {
        self.bytes
    }
    pub fn key(&self) -> Key {
        self.key
    }
    pub fn emitted_mask(&self) -> u16 {
        self.emitted_mask
    }
    pub fn bytes(&self) -> &[u8] {
        &self.bytes
    }
    pub fn digest(&self) -> u64 {
        self.digest
    }
}
pub fn digest(bytes: &[u8]) -> u64 {
    bytes.iter().fold(0xcbf29ce484222325, |v, b| {
        (v ^ u64::from(*b)).wrapping_mul(0x100000001b3)
    })
}
fn varint(mut value: usize, out: &mut Vec<u8>) {
    loop {
        let b = (value & 127) as u8;
        value >>= 7;
        out.push(b | if value != 0 { 128 } else { 0 });
        if value == 0 {
            break;
        }
    }
}
fn body(key: Key, emitted_mask: u16, payload: Vec<u8>, capacity: usize) -> Result<Encoded, Error> {
    let mut bytes = Vec::with_capacity(payload.len() + 24);
    varint(0x20, &mut bytes);
    bytes.extend(key.resource.x.to_be_bytes());
    bytes.extend(key.resource.z.to_be_bytes());
    bytes.push(u8::from(key.full));
    varint(emitted_mask as usize, &mut bytes);
    varint(payload.len(), &mut bytes);
    bytes.extend(payload);
    varint(0, &mut bytes); // empty tile-entity list only
    if bytes.len() > capacity || bytes.len() > MAX_BODY {
        return Err(Error::Capacity);
    }
    Ok(Encoded {
        key,
        emitted_mask,
        digest: digest(&bytes),
        bytes: bytes.into_boxed_slice(),
    })
}
pub fn encode_legacy(
    world: &World,
    chunk: &mut LegacyChunk,
    capacity: usize,
) -> Result<Encoded, Error> {
    world.validate()?;
    let mut payload = vec![0; MAX_BODY];
    let mut cursor = 0;
    let result = chunk
        .encode_packet_payload(
            world.key.skylight,
            world.key.full,
            &mut payload,
            &mut cursor,
        )
        .map_err(|_| Error::Capacity)?;
    if result.emitted_mask != world.key.mask {
        return Err(Error::Stale);
    }
    payload.truncate(result.bytes_written);
    body(world.key, result.emitted_mask, payload, capacity)
}
pub fn encode(world: &World, capacity: usize) -> Result<Encoded, Error> {
    world.validate()?;
    let mut payload = Vec::new();
    let mut emitted = 0;
    for y in 0..16 {
        if world.key.mask & (1 << y) == 0 {
            continue;
        }
        let section = world.sections.get(&y).ok_or(Error::MissingSection)?;
        let mut palette = vec![0u16];
        let mut lookup = HashMap::new();
        lookup.insert(0u16, 0usize);
        let mut wires = Vec::with_capacity(CELLS);
        let mut indices = Vec::with_capacity(CELLS);
        for i in 0..CELLS {
            let wire = world.wire(section.data.get(i).unwrap())?;
            wires.push(wire);
            let index = if palette.len() <= 16 {
                palette.iter().position(|v| *v == wire)
            } else {
                lookup.get(&wire).copied()
            };
            let index = match index {
                Some(i) => i,
                None => {
                    let i = palette.len();
                    palette.push(wire);
                    lookup.insert(wire, i);
                    i
                }
            };
            indices.push(index as u16);
        }
        let direct = palette.len() > 256;
        let bits = if direct {
            13
        } else {
            (usize::BITS - (palette.len() - 1).leading_zeros()).max(4) as usize
        };
        payload.push(bits as u8);
        varint(if direct { 0 } else { palette.len() }, &mut payload);
        if !direct {
            for wire in &palette {
                varint(*wire as usize, &mut payload);
            }
        }
        let mut words = vec![0u64; (CELLS * bits).div_ceil(64)];
        for i in 0..CELLS {
            let v = u64::from(if direct { wires[i] } else { indices[i] });
            let bit = i * bits;
            let word = bit / 64;
            let shift = bit % 64;
            words[word] |= v << shift;
            if shift + bits > 64 {
                words[word + 1] |= v >> (64 - shift);
            }
        }
        varint(words.len(), &mut payload);
        for word in words {
            payload.extend(word.to_be_bytes());
        }
        payload.extend(section.block_light);
        if world.key.skylight {
            payload.extend(section.sky_light);
        }
        emitted |= 1 << y;
    }
    if world.key.full {
        payload.extend(world.biomes);
    }
    body(world.key, emitted, payload, capacity)
}

#[derive(Default)]
pub struct Accounting {
    live: AtomicUsize,
    peak: AtomicUsize,
}
impl Accounting {
    pub fn live(&self) -> usize {
        self.live.load(Ordering::Relaxed)
    }
    pub fn peak(&self) -> usize {
        self.peak.load(Ordering::Relaxed)
    }
}
pub struct PacketBody {
    bytes: Box<[u8]>,
    emitted_mask: u16,
    digest: u64,
    accounting: Arc<Accounting>,
}
impl PacketBody {
    pub fn bytes(&self) -> &[u8] {
        &self.bytes
    }
    pub fn emitted_mask(&self) -> u16 {
        self.emitted_mask
    }
    pub fn digest(&self) -> u64 {
        self.digest
    }
}
impl Drop for PacketBody {
    fn drop(&mut self) {
        self.accounting
            .live
            .fetch_sub(self.bytes.len(), Ordering::Relaxed);
    }
}
struct Entry {
    body: Arc<PacketBody>,
    used: u64,
}
pub struct Cache {
    entries: BTreeMap<Key, Entry>,
    resident: usize,
    budget: usize,
    live_budget: usize,
    clock: u64,
    accounting: Arc<Accounting>,
    pub evictions: usize,
}
impl Cache {
    pub fn new(budget: usize, live_budget: usize) -> Self {
        assert!(budget > 0 && live_budget >= budget);
        Self {
            entries: BTreeMap::new(),
            resident: 0,
            budget,
            live_budget,
            clock: 0,
            accounting: Arc::default(),
            evictions: 0,
        }
    }
    pub fn accounting(&self) -> Arc<Accounting> {
        self.accounting.clone()
    }
    pub fn resident(&self) -> usize {
        self.resident
    }
    pub fn len(&self) -> usize {
        self.entries.len()
    }
    pub fn is_empty(&self) -> bool {
        self.entries.is_empty()
    }
    pub fn lookup(&mut self, current: Key) -> Result<Option<Arc<PacketBody>>, Error> {
        self.clock = self.clock.checked_add(1).ok_or(Error::Exhausted)?;
        Ok(self.entries.get_mut(&current).map(|e| {
            e.used = self.clock;
            e.body.clone()
        }))
    }
    fn evict(&mut self) -> bool {
        let Some(key) = self
            .entries
            .iter()
            .min_by_key(|(_, e)| e.used)
            .map(|(k, _)| *k)
        else {
            return false;
        };
        let e = self.entries.remove(&key).unwrap();
        self.resident -= e.body.bytes.len();
        self.evictions += 1;
        true
    }
    pub fn invalidate(&mut self, resource: Resource) -> usize {
        let keys: Vec<_> = self
            .entries
            .keys()
            .filter(|k| k.resource == resource)
            .copied()
            .collect();
        let n = keys.len();
        for k in keys {
            let e = self.entries.remove(&k).unwrap();
            self.resident -= e.body.bytes.len();
        }
        n
    }
    pub fn publish(
        &mut self,
        encoded: Encoded,
        current: Key,
    ) -> Result<Arc<PacketBody>, (Error, Box<Encoded>)> {
        if encoded.key != current || encoded.emitted_mask != current.mask {
            return Err((Error::Stale, Box::new(encoded)));
        }
        if encoded.bytes.len() > self.budget {
            return Err((Error::Oversized, Box::new(encoded)));
        }
        if let Some(e) = self.entries.get(&current) {
            return Ok(e.body.clone());
        }
        while self.resident + encoded.bytes.len() > self.budget
            || self.accounting.live() + encoded.bytes.len() > self.live_budget
        {
            if !self.evict() {
                return Err((Error::RetentionPressure, Box::new(encoded)));
            }
        }
        let Some(clock) = self.clock.checked_add(1) else {
            return Err((Error::Exhausted, Box::new(encoded)));
        };
        self.clock = clock;
        let bytes = encoded.bytes.len();
        let live = self.accounting.live.fetch_add(bytes, Ordering::Relaxed) + bytes;
        self.accounting.peak.fetch_max(live, Ordering::Relaxed);
        let packet = Arc::new(PacketBody {
            bytes: encoded.bytes,
            emitted_mask: encoded.emitted_mask,
            digest: encoded.digest,
            accounting: self.accounting.clone(),
        });
        self.entries.insert(
            current,
            Entry {
                body: packet.clone(),
                used: clock,
            },
        );
        self.resident += bytes;
        Ok(packet)
    }
}
