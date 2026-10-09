use crate::snapshot::exclusive_options;
use crate::{index_hash, invalid, AnyResult, Entry, Header, MAX_RECORDS, SCHEMA};
use redb::{
    backends::FileBackend, BackendError, Database, ReadTransaction, ReadableTable,
    ReadableTableMetadata, StorageBackend, TableDefinition,
};
use sha2::{Digest, Sha256};
use std::io;
use std::ops::Bound;
use std::path::Path;

pub const MAX_DATABASE_BYTES: u64 = 64 << 20;
pub const INDEX: TableDefinition<u64, &[u8]> = TableDefinition::new("records-v1");
pub const META: TableDefinition<u64, &[u8]> = TableDefinition::new("metadata-v1");
/// Delegates redb's own locking, while preventing file growth above a hard cap.
#[derive(Debug)]
pub struct CappedBackend {
    inner: FileBackend,
    cap: u64,
}
impl CappedBackend {
    pub fn open(path: &Path, fresh: bool, cap: u64) -> AnyResult<Self> {
        if cap == 0 || cap > MAX_DATABASE_BYTES {
            return Err(invalid("database cap").into());
        }
        let mut options = exclusive_options()?;
        options.create_new(fresh);
        let file = options.open(path)?;
        if file.metadata()?.len() > cap {
            return Err(invalid("existing database exceeds cap").into());
        }
        Ok(Self {
            inner: FileBackend::new(file)?,
            cap,
        })
    }
    fn range(&self, offset: u64, length: usize) -> io::Result<()> {
        if offset
            .checked_add(length as u64)
            .filter(|v| *v <= self.cap)
            .is_none()
        {
            return Err(invalid("database byte cap"));
        }
        Ok(())
    }
}
impl StorageBackend for CappedBackend {
    fn len(&self) -> io::Result<u64> {
        self.inner.len()
    }
    fn read(&self, offset: u64, out: &mut [u8]) -> io::Result<()> {
        self.range(offset, out.len())?;
        self.inner.read(offset, out)
    }
    fn set_len(&self, len: u64) -> io::Result<()> {
        if len > self.cap {
            return Err(invalid("database byte cap"));
        }
        self.inner.set_len(len)
    }
    fn sync_data(&self) -> io::Result<()> {
        self.inner.sync_data()
    }
    fn write(&self, offset: u64, data: &[u8]) -> io::Result<()> {
        self.range(offset, data.len())?;
        self.inner.write(offset, data)
    }
    fn close(&self) -> io::Result<()> {
        self.inner.close()
    }
    fn try_lock_range(&self, a: Bound<u64>, b: Bound<u64>) -> Result<bool, BackendError> {
        self.inner.try_lock_range(a, b)
    }
    fn try_lock_shared_range(&self, a: Bound<u64>, b: Bound<u64>) -> Result<bool, BackendError> {
        self.inner.try_lock_shared_range(a, b)
    }
    fn lock_range(&self, a: Bound<u64>, b: Bound<u64>) -> Result<(), BackendError> {
        self.inner.lock_range(a, b)
    }
    fn lock_shared_range(&self, a: Bound<u64>, b: Bound<u64>) -> Result<(), BackendError> {
        self.inner.lock_shared_range(a, b)
    }
    fn unlock_range(&self, a: Bound<u64>, b: Bound<u64>) -> Result<(), BackendError> {
        self.inner.unlock_range(a, b)
    }
    fn query_lock_range(&self, a: Bound<u64>, b: Bound<u64>) -> Result<bool, BackendError> {
        self.inner.query_lock_range(a, b)
    }
}
pub fn open(path: &Path, fresh: bool) -> AnyResult<Database> {
    let backend = CappedBackend::open(path, fresh, MAX_DATABASE_BYTES)?;
    Ok(Database::builder()
        .set_cache_size(8 << 20)
        .create_with_backend(backend)?)
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct Binding {
    pub source: [u8; 32],
    pub schema: u32,
    pub epoch: u64,
    pub count: u64,
    pub index: [u8; 32],
}
impl Binding {
    pub fn new(header: &Header, source: [u8; 32], entries: &[Entry]) -> Self {
        Self {
            source,
            schema: SCHEMA,
            epoch: header.epoch,
            count: header.count as u64,
            index: index_hash(entries),
        }
    }
    pub fn bytes(&self) -> Vec<u8> {
        let mut out = Vec::with_capacity(84);
        out.extend(self.source);
        out.extend(self.schema.to_le_bytes());
        out.extend(self.epoch.to_le_bytes());
        out.extend(self.count.to_le_bytes());
        out.extend(self.index);
        out
    }
}
pub fn build(db: &Database, binding: &Binding, entries: &[Entry]) -> AnyResult<()> {
    if binding.schema != SCHEMA
        || binding.epoch == 0
        || binding.count == 0
        || binding.count > MAX_RECORDS as u64
        || binding.count != entries.len() as u64
        || index_hash(entries) != binding.index
    {
        return Err(invalid("build binding").into());
    }
    let write = db.begin_write()?;
    {
        let mut table = write.open_table(INDEX)?;
        table.retain(|_, _| false)?;
        for e in entries {
            table.insert(e.key, e.encode_value().as_slice())?;
        }
        let mut meta = write.open_table(META)?;
        meta.retain(|_, _| false)?;
        meta.insert(0, binding.bytes().as_slice())?;
    }
    write.commit()?;
    Ok(())
}
pub fn validate(read: &ReadTransaction, expected: &Binding) -> AnyResult<()> {
    if expected.schema != SCHEMA
        || expected.epoch == 0
        || expected.count == 0
        || expected.count > MAX_RECORDS as u64
    {
        return Err(invalid("expected binding bounds").into());
    }
    let meta = read.open_table(META)?;
    if meta.len()? != 1
        || meta
            .get(0)?
            .ok_or_else(|| invalid("missing metadata"))?
            .value()
            != expected.bytes()
    {
        return Err(invalid("foreign/stale metadata").into());
    }
    let table = read.open_table(INDEX)?;
    if table.len()? != expected.count {
        return Err(invalid("sidecar count").into());
    }
    Ok(())
}
pub fn verify_index(read: &ReadTransaction, expected: &Binding) -> AnyResult<[u8; 32]> {
    validate(read, expected)?;
    let table = read.open_table(INDEX)?;
    let mut h = Sha256::new();
    for (position, row) in table.iter()?.enumerate() {
        let (key, value) = row?;
        let e = Entry::decode_value(key.value(), value.value())?;
        if e.key != position as u64 {
            return Err(invalid("sidecar key order").into());
        }
        h.update(e.key.to_le_bytes());
        h.update(e.encode_value());
    }
    let hash: [u8; 32] = h.finalize().into();
    if hash != expected.index {
        return Err(invalid("sidecar content digest").into());
    }
    Ok(hash)
}
pub fn query_hash(read: &ReadTransaction, expected: &Binding) -> AnyResult<[u8; 32]> {
    validate(read, expected)?;
    let table = read.open_table(INDEX)?;
    let mut h = Sha256::new();
    for i in 0..256u64 {
        let key = (i * 7919 + 17) % expected.count;
        let value = table
            .get(key)?
            .ok_or_else(|| invalid("missing query key"))?;
        let e = Entry::decode_value(key, value.value())?;
        h.update(e.key.to_le_bytes());
        h.update(e.encode_value());
    }
    if table.get(expected.count + 1)?.is_some() {
        return Err(invalid("extra query key").into());
    }
    Ok(h.finalize().into())
}
