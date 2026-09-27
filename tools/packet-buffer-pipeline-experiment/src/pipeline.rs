use crate::allocation::stage;
use aes::cipher::KeyIvInit;
use compression::{max_output_len, ZlibPacketCompressor, VANILLA_LEVEL};
use std::collections::{BTreeMap, HashMap, VecDeque};
use std::io::{self, IoSlice, Write};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{mpsc, Arc, Condvar, Mutex};
use std::thread::{self, JoinHandle};
use std::time::{Duration, Instant};

pub const MAX_BODY: usize = 65536;
pub const MAX_JOBS: usize = 16;
pub const OUTPUT_BUDGET: usize = 1024 * 1024;
pub const BODY_BUDGET: usize = 512 * 1024;
pub const MAX_CONNECTIONS: usize = 4;
static ORIGIN: AtomicU64 = AtomicU64::new(1);
fn origin() -> u64 {
    ORIGIN
        .fetch_update(Ordering::Relaxed, Ordering::Relaxed, |v| v.checked_add(1))
        .expect("origin exhausted")
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Error {
    Backpressure,
    Stale,
    Foreign,
    Closed,
    Invalid,
    Compression,
    Exhausted,
}

#[derive(Default, Clone, Copy, Debug)]
pub struct BudgetStats {
    pub jobs: usize,
    pub bytes: usize,
    pub peak_jobs: usize,
    pub peak_bytes: usize,
}
pub struct Budget {
    max_jobs: usize,
    max_bytes: usize,
    stats: Mutex<BudgetStats>,
}
impl Budget {
    pub fn new(max_jobs: usize, max_bytes: usize) -> Arc<Self> {
        Arc::new(Self {
            max_jobs,
            max_bytes,
            stats: Mutex::new(BudgetStats::default()),
        })
    }
    pub fn stats(&self) -> BudgetStats {
        *self.stats.lock().unwrap()
    }
    fn reserve(self: &Arc<Self>, bytes: usize) -> Result<Lease, Error> {
        let mut s = self.stats.lock().unwrap();
        if s.jobs >= self.max_jobs || bytes > self.max_bytes.saturating_sub(s.bytes) {
            return Err(Error::Backpressure);
        }
        s.jobs += 1;
        s.bytes += bytes;
        s.peak_jobs = s.peak_jobs.max(s.jobs);
        s.peak_bytes = s.peak_bytes.max(s.bytes);
        Ok(Lease {
            budget: self.clone(),
            bytes,
        })
    }
}
struct Lease {
    budget: Arc<Budget>,
    bytes: usize,
}
impl Drop for Lease {
    fn drop(&mut self) {
        let mut s = self.budget.stats.lock().unwrap();
        s.jobs -= 1;
        s.bytes -= self.bytes;
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord)]
pub struct StateKey {
    origin: u64,
    lifecycle: u64,
    registry: u64,
    state: u64,
}
pub struct Snapshot {
    key: StateKey,
    seed: u32,
    data: Vec<u8>,
}
impl Snapshot {
    pub fn new(seed: u32, length: usize) -> Result<Arc<Self>, Error> {
        if !(16..=MAX_BODY).contains(&length) {
            return Err(Error::Invalid);
        }
        let data = (0..length - 16).map(|i| sample(seed, i)).collect();
        Ok(Arc::new(Self {
            key: StateKey {
                origin: origin(),
                lifecycle: 1,
                registry: 1,
                state: 1,
            },
            seed,
            data,
        }))
    }
    pub fn changed(&self) -> Arc<Self> {
        Arc::new(Self {
            key: StateKey {
                state: self.key.state.checked_add(1).expect("state exhausted"),
                ..self.key
            },
            seed: self.seed.wrapping_add(1),
            data: self.data.iter().map(|v| v.wrapping_add(1)).collect(),
        })
    }
    pub fn seed(&self) -> u32 {
        self.seed
    }
    pub fn len(&self) -> usize {
        self.data.len() + 16
    }
    pub fn is_empty(&self) -> bool {
        false
    }
}
pub fn sample(seed: u32, index: usize) -> u8 {
    if seed == 0 {
        return 0x55;
    }
    let mut x = (index as u32).wrapping_add(seed.wrapping_mul(0x9e3779b9));
    x ^= x >> 13;
    x = x.wrapping_mul(0x85ebca6b);
    x ^= x >> 16;
    (x & 255) as u8
}
#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord)]
struct BodyKey {
    state: StateKey,
    rule: u8,
    protocol: u32,
}
pub struct Body {
    key: BodyKey,
    bytes: Vec<u8>,
    _lease: Lease,
}
impl Body {
    pub fn bytes(&self) -> &[u8] {
        &self.bytes
    }
}
pub struct Bodies {
    entries: BTreeMap<BodyKey, Arc<Body>>,
    pub budget: Arc<Budget>,
    pub copied: u64,
    pub generated: u64,
    pub shared_hits: u64,
}
impl Default for Bodies {
    fn default() -> Self {
        Self::new()
    }
}
impl Bodies {
    pub fn new() -> Self {
        Self {
            entries: BTreeMap::new(),
            budget: Budget::new(128, BODY_BUDGET),
            copied: 0,
            generated: 0,
            shared_hits: 0,
        }
    }
    pub fn encode(&mut self, snapshot: &Snapshot, rule: u8) -> Result<Arc<Body>, Error> {
        self.encode_inner(snapshot, rule, true)
    }
    pub fn encode_uncached(&mut self, snapshot: &Snapshot, rule: u8) -> Result<Arc<Body>, Error> {
        self.encode_inner(snapshot, rule, false)
    }
    fn encode_inner(
        &mut self,
        snapshot: &Snapshot,
        rule: u8,
        reuse: bool,
    ) -> Result<Arc<Body>, Error> {
        let _scope = stage(1);
        if rule > 1 {
            return Err(Error::Invalid);
        }
        let key = BodyKey {
            state: snapshot.key,
            rule,
            protocol: 340,
        };
        if let Some(body) = self.entries.get(&key).filter(|_| reuse) {
            self.shared_hits += 1;
            return Ok(body.clone());
        }
        let lease = self.budget.reserve(snapshot.len())?;
        let mut bytes = Vec::with_capacity(snapshot.len());
        // Synthetic packet id 0x7e and fifteen fixture header bytes, not gameplay.
        bytes.push(0x7e);
        bytes.push(rule);
        bytes.extend(snapshot.seed.to_be_bytes());
        bytes.extend(snapshot.key.state.to_be_bytes());
        bytes.extend([0, 0]);
        self.generated += 16;
        if rule == 0 {
            bytes.extend_from_slice(&snapshot.data);
            self.copied += snapshot.data.len() as u64;
        } else {
            bytes.extend(snapshot.data.iter().map(|v| if *v == 0 { 0 } else { 0x33 }));
            self.generated += snapshot.data.len() as u64;
        }
        let body = Arc::new(Body {
            key,
            bytes,
            _lease: lease,
        });
        self.entries.insert(key, body.clone());
        Ok(body)
    }
    pub fn clear(&mut self) {
        self.entries.clear();
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub struct ConnectionId {
    origin: u64,
    slot: u32,
    generation: u64,
}
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub struct Ticket {
    connection: ConnectionId,
    sequence: u64,
    nonce: u64,
}
impl Ticket {
    pub fn connection(&self) -> ConnectionId {
        self.connection
    }
    pub fn sequence(&self) -> u64 {
        self.sequence
    }
}

#[derive(Default)]
pub struct Gate {
    open: Mutex<bool>,
    changed: Condvar,
}
impl Gate {
    pub fn release(&self) {
        *self.open.lock().unwrap() = true;
        self.changed.notify_all();
    }
    fn wait(&self) -> bool {
        let opened = self.open.lock().unwrap();
        *self
            .changed
            .wait_timeout_while(opened, Duration::from_secs(3), |v| !*v)
            .unwrap()
            .0
    }
}
#[derive(Clone, Default)]
pub enum Fault {
    #[default]
    None,
    CompressionFailure,
    Gate(Arc<Gate>),
}
struct Work {
    ticket: Ticket,
    body: Arc<Body>,
    threshold: i32,
    lease: Lease,
    fault: Fault,
}
struct Frame {
    header: [u8; 10],
    header_len: usize,
    payload: Vec<u8>,
    _lease: Lease,
}
impl Frame {
    fn len(&self) -> usize {
        self.header_len + self.payload.len()
    }
}
#[derive(Clone, Copy, Default, Debug)]
pub struct Traffic {
    pub compressed_input: u64,
    pub compressed_output: u64,
    pub passthrough_copied: u64,
    pub initialized_output: u64,
    pub encrypted: u64,
    pub sent: u64,
    pub writes: u64,
    pub os_short_writes: u64,
    pub would_block: u64,
    pub compression_ns: u64,
    pub encryption_ns: u64,
    pub header_generated: u64,
    pub header_copied: u64,
}
impl Traffic {
    fn add(&mut self, b: Self) {
        self.compressed_input += b.compressed_input;
        self.compressed_output += b.compressed_output;
        self.passthrough_copied += b.passthrough_copied;
        self.initialized_output += b.initialized_output;
        self.header_generated += b.header_generated;
        self.header_copied += b.header_copied;
        self.compression_ns += b.compression_ns;
    }
}
pub struct Completion {
    ticket: Ticket,
    result: Result<Frame, Error>,
    traffic: Traffic,
    _failed_lease: Option<Lease>,
}
impl Completion {
    pub fn ticket(&self) -> Ticket {
        self.ticket
    }
    pub fn plaintext(&self) -> Option<Vec<u8>> {
        self.result.as_ref().ok().map(|f| {
            let _scope = stage(7);
            let mut v = Vec::with_capacity(f.len());
            v.extend_from_slice(&f.header[..f.header_len]);
            v.extend_from_slice(&f.payload);
            v
        })
    }
}
pub struct Rejected {
    pub error: Error,
    pub completion: Box<Completion>,
}
fn varint(mut value: usize, output: &mut [u8], cursor: &mut usize) {
    loop {
        let mut byte = (value & 127) as u8;
        value >>= 7;
        if value != 0 {
            byte |= 128
        }
        output[*cursor] = byte;
        *cursor += 1;
        if value == 0 {
            return;
        }
    }
}
fn compress(work: Work, compressor: &mut ZlibPacketCompressor) -> Completion {
    let _scope = stage(2);
    let now = Instant::now();
    let Work {
        ticket,
        body,
        threshold,
        lease,
        fault,
    } = work;
    if matches!(fault, Fault::CompressionFailure)
        || matches!(fault,Fault::Gate(ref gate) if !gate.wait())
    {
        return Completion {
            ticket,
            result: Err(Error::Compression),
            traffic: Traffic::default(),
            _failed_lease: Some(lease),
        };
    }
    let size = body.bytes.len();
    let bound = max_output_len(size);
    let mut payload = vec![0; bound];
    let mut traffic = Traffic {
        initialized_output: bound as u64,
        ..Traffic::default()
    };
    let compressed = threshold >= 0 && size >= threshold as usize;
    let length = if compressed {
        match compressor.compress_into(&body.bytes, &mut payload) {
            Ok(n) => n,
            Err(_) => {
                return Completion {
                    ticket,
                    result: Err(Error::Compression),
                    traffic,
                    _failed_lease: Some(lease),
                }
            }
        }
    } else {
        payload[..size].copy_from_slice(&body.bytes);
        traffic.passthrough_copied = size as u64;
        size
    };
    payload.truncate(length);
    if compressed {
        traffic.compressed_input = size as u64;
        traffic.compressed_output = length as u64;
    }
    let mut inner_header = [0; 5];
    let mut il = 0;
    if threshold >= 0 {
        varint(
            if compressed { size } else { 0 },
            &mut inner_header,
            &mut il,
        );
    }
    let mut header = [0; 10];
    let mut hl = 0;
    varint(il + length, &mut header, &mut hl);
    // Small framing-header copy is explicitly counted separately by the caller.
    header[hl..hl + il].copy_from_slice(&inner_header[..il]);
    hl += il;
    traffic.header_generated = hl as u64;
    traffic.header_copied = il as u64;
    traffic.compression_ns = now.elapsed().as_nanos() as u64;
    Completion {
        ticket,
        result: Ok(Frame {
            header,
            header_len: hl,
            payload,
            _lease: lease,
        }),
        traffic,
        _failed_lease: None,
    }
}
struct Pending {
    frame: Frame,
    offset: usize,
}
enum Ordered {
    Frame(Frame),
    // A skipped sequence still consumes bounded queue space until retired.
    Skip { _lease: Lease },
}
struct Connection {
    id: ConnectionId,
    assign: u64,
    send: u64,
    open: bool,
    cipher: cfb8::Encryptor<aes::Aes128>,
    reorder: BTreeMap<u64, Ordered>,
    output: VecDeque<Pending>,
}

pub struct Hub {
    origin: u64,
    nonce: u64,
    connections: BTreeMap<u32, Connection>,
    running: HashMap<Ticket, bool>,
    sender: Option<mpsc::SyncSender<Work>>,
    receiver: mpsc::Receiver<Completion>,
    workers: Vec<JoinHandle<()>>,
    pub budget: Arc<Budget>,
    pub traffic: Traffic,
    pub discarded: usize,
    pub canceled: usize,
}
impl Hub {
    pub fn new(max_jobs: usize, max_bytes: usize) -> Self {
        assert!((1..=MAX_JOBS).contains(&max_jobs) && max_bytes <= OUTPUT_BUDGET);
        let (sender, receiver) = mpsc::sync_channel::<Work>(max_jobs);
        let receiver = Arc::new(Mutex::new(receiver));
        let (tx, rx) = mpsc::channel();
        let mut workers = Vec::new();
        for _ in 0..2 {
            let r = receiver.clone();
            let t = tx.clone();
            workers.push(thread::spawn(move || {
                let _scope = stage(2);
                let mut compressor = ZlibPacketCompressor::new(VANILLA_LEVEL).unwrap();
                loop {
                    let work = r.lock().unwrap().recv();
                    match work {
                        Ok(w) => {
                            if t.send(compress(w, &mut compressor)).is_err() {
                                break;
                            }
                        }
                        Err(_) => break,
                    }
                }
            }));
        }
        drop(tx);
        Self {
            origin: origin(),
            nonce: 1,
            connections: BTreeMap::new(),
            running: HashMap::new(),
            sender: Some(sender),
            receiver: rx,
            workers,
            budget: Budget::new(max_jobs, max_bytes),
            traffic: Traffic::default(),
            discarded: 0,
            canceled: 0,
        }
    }
    pub fn connect(&mut self, slot: u32, key: [u8; 16]) -> Result<ConnectionId, Error> {
        if !self.connections.contains_key(&slot) && self.connections.len() >= MAX_CONNECTIONS {
            return Err(Error::Backpressure);
        }
        let generation = if let Some(old) = self.connections.get(&slot) {
            if old.open {
                return Err(Error::Invalid);
            }
            old.id.generation.checked_add(1).ok_or(Error::Exhausted)?
        } else {
            1
        };
        let id = ConnectionId {
            origin: self.origin,
            slot,
            generation,
        };
        self.connections.insert(
            slot,
            Connection {
                id,
                assign: 0,
                send: 0,
                open: true,
                cipher: cfb8::Encryptor::new(&key.into(), &key.into()),
                reorder: BTreeMap::new(),
                output: VecDeque::new(),
            },
        );
        Ok(id)
    }
    fn connection_mut(&mut self, id: ConnectionId) -> Result<&mut Connection, Error> {
        if id.origin != self.origin {
            return Err(Error::Foreign);
        }
        let c = self.connections.get_mut(&id.slot).ok_or(Error::Stale)?;
        if c.id != id {
            return Err(Error::Stale);
        }
        if !c.open {
            return Err(Error::Closed);
        }
        Ok(c)
    }
    pub fn submit(
        &mut self,
        id: ConnectionId,
        body: Arc<Body>,
        threshold: i32,
        fault: Fault,
    ) -> Result<Ticket, Error> {
        if threshold < -1 || body.key.protocol != 340 {
            return Err(Error::Invalid);
        }
        let sequence = self.connection_mut(id)?.assign;
        // Detached completion handles cannot create unbounded tracking entries
        // even if a caller abandons one instead of returning it to accept().
        if self.running.len() >= self.budget.max_jobs {
            return Err(Error::Backpressure);
        }
        let next = sequence.checked_add(1).ok_or(Error::Exhausted)?;
        let nonce = self.nonce.checked_add(1).ok_or(Error::Exhausted)?;
        let lease = self.budget.reserve(max_output_len(body.bytes.len()) + 10)?;
        let ticket = Ticket {
            connection: id,
            sequence,
            nonce: self.nonce,
        };
        self.sender
            .as_ref()
            .ok_or(Error::Closed)?
            .try_send(Work {
                ticket,
                body,
                threshold,
                lease,
                fault,
            })
            .map_err(|_| Error::Backpressure)?;
        self.running.insert(ticket, false);
        self.connection_mut(id)?.assign = next;
        self.nonce = nonce;
        Ok(ticket)
    }
    pub fn cancel(&mut self, ticket: Ticket) -> Result<(), Error> {
        if ticket.connection.origin != self.origin {
            return Err(Error::Foreign);
        }
        let value = self.running.get_mut(&ticket).ok_or(Error::Stale)?;
        *value = true;
        self.canceled += 1;
        Ok(())
    }
    pub fn close(&mut self, id: ConnectionId) -> Result<(), Error> {
        let c = self.connection_mut(id)?;
        c.open = false;
        c.output.clear();
        c.reorder.clear();
        for (ticket, canceled) in &mut self.running {
            if ticket.connection == id {
                *canceled = true;
            }
        }
        Ok(())
    }
    pub fn next_completion(&self) -> Result<Completion, Error> {
        self.receiver
            .recv_timeout(Duration::from_secs(4))
            .map_err(|_| Error::Closed)
    }
    pub fn outstanding(&self) -> usize {
        self.running.len()
    }
    pub fn queued(&self, id: ConnectionId) -> usize {
        self.connections
            .get(&id.slot)
            .filter(|c| c.id == id)
            .map_or(0, |c| c.output.len())
    }
    pub fn accept(&mut self, completion: Completion) -> Result<(), Rejected> {
        let _scope = stage(3);
        let ticket = completion.ticket;
        if ticket.connection.origin != self.origin || !self.running.contains_key(&ticket) {
            return Err(Rejected {
                error: if ticket.connection.origin != self.origin {
                    Error::Foreign
                } else {
                    Error::Stale
                },
                completion: Box::new(completion),
            });
        }
        let canceled = self.running.remove(&ticket).unwrap();
        self.traffic.add(completion.traffic);
        let Ok(c) = self.connection_mut(ticket.connection) else {
            self.discarded += 1;
            return Ok(());
        };
        if canceled {
            let lease = match completion.result {
                Ok(frame) => frame._lease,
                Err(_) => completion._failed_lease.expect("failed completion lease"),
            };
            c.reorder
                .insert(ticket.sequence, Ordered::Skip { _lease: lease });
            self.discarded += 1;
        } else {
            match completion.result {
                Ok(frame) => {
                    c.reorder.insert(ticket.sequence, Ordered::Frame(frame));
                }
                Err(_) => {
                    let _ = self.close(ticket.connection);
                    self.discarded += 1;
                    return Ok(());
                }
            }
        }
        self.promote(ticket.connection);
        Ok(())
    }
    fn promote(&mut self, id: ConnectionId) {
        let _scope = stage(4);
        let now = Instant::now();
        let mut encrypted = 0;
        let c = self.connection_mut(id).unwrap();
        while let Some(frame) = c.reorder.remove(&c.send) {
            c.send += 1;
            if let Ordered::Frame(mut frame) = frame {
                c.cipher.encrypt(&mut frame.header[..frame.header_len]);
                c.cipher.encrypt(&mut frame.payload);
                encrypted += frame.len() as u64;
                c.output.push_back(Pending { frame, offset: 0 });
            }
        }
        self.traffic.encrypted += encrypted;
        self.traffic.encryption_ns += now.elapsed().as_nanos() as u64;
    }
    pub fn send_once(
        &mut self,
        id: ConnectionId,
        writer: &mut impl Write,
        quantum: usize,
    ) -> io::Result<bool> {
        let _scope = stage(5);
        if quantum == 0 {
            return Err(io::ErrorKind::InvalidInput.into());
        }
        let c = self
            .connection_mut(id)
            .map_err(|_| io::Error::from(io::ErrorKind::NotConnected))?;
        let Some(pending) = c.output.front_mut() else {
            return Ok(true);
        };
        let frame = &pending.frame;
        let total = frame.len();
        let left = (total - pending.offset).min(quantum);
        let header_offset = pending.offset.min(frame.header_len);
        let hn = (frame.header_len - header_offset).min(left);
        let payload_offset = pending.offset.saturating_sub(frame.header_len);
        let pn = left - hn;
        let slices = [
            IoSlice::new(&frame.header[header_offset..header_offset + hn]),
            IoSlice::new(&frame.payload[payload_offset..payload_offset + pn]),
        ];
        let result = writer.write_vectored(&slices);
        match result {
            Ok(0) => {
                let _ = self.close(id);
                Err(io::ErrorKind::WriteZero.into())
            }
            Ok(n) if n <= left => {
                pending.offset += n;
                let done = pending.offset == total;
                if done {
                    c.output.pop_front();
                }
                let empty = c.output.is_empty();
                self.traffic.writes += 1;
                self.traffic.sent += n as u64;
                if n < left {
                    self.traffic.os_short_writes += 1;
                }
                Ok(empty)
            }
            Ok(_) => {
                let _ = self.close(id);
                Err(io::ErrorKind::InvalidData.into())
            }
            Err(e) if e.kind() == io::ErrorKind::WouldBlock => {
                self.traffic.would_block += 1;
                Ok(false)
            }
            Err(e) if e.kind() == io::ErrorKind::Interrupted => Ok(false),
            Err(e) => {
                let _ = self.close(id);
                Err(e)
            }
        }
    }
    pub fn quiesce(&mut self) {
        self.sender.take();
        for handle in self.workers.drain(..) {
            handle.join().expect("worker panic");
        }
        while let Ok(completion) = self.receiver.try_recv() {
            let _ = self.accept(completion);
        }
    }
}
impl Drop for Hub {
    fn drop(&mut self) {
        self.quiesce();
    }
}
