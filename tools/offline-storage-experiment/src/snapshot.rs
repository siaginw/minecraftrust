//! Windows-only immutable-from-birth file ownership. No arbitrary-file mapping.
use crate::{invalid, scan_reader, Entry, Header, MAX_BYTES};
use memmap2::{Mmap, MmapOptions};
use sha2::{Digest, Sha256};
use std::fs::{File, OpenOptions};
use std::io::{self, BufReader, Read, Seek, SeekFrom, Write};
#[cfg(windows)]
use std::os::windows::fs::OpenOptionsExt;
use std::path::Path;

/// Owns the sole private file handle from CREATE_NEW until all map borrows end.
/// ```compile_fail
/// use offline_storage_experiment::snapshot::Snapshot;
/// fn early_drop(owner: Snapshot) {
///     let view = owner.map(0, owner.len()).unwrap();
///     drop(owner);
///     println!("{:?}", view.bytes());
/// }
/// ```
/// ```compile_fail
/// use offline_storage_experiment::snapshot::Snapshot;
/// fn mutate(owner: Snapshot) { owner.file.set_len(0).unwrap(); }
/// ```
pub struct Snapshot {
    file: File,
    length: usize,
}
/// No mutable map or file handle is exposed. Mmap drops before the owner borrow.
pub struct View<'a> {
    map: Mmap,
    _owner: &'a Snapshot,
}
impl View<'_> {
    pub fn bytes(&self) -> &[u8] {
        &self.map
    }
}

pub fn exclusive_options() -> io::Result<OpenOptions> {
    #[cfg(windows)]
    {
        let mut o = OpenOptions::new();
        o.read(true).write(true).share_mode(0);
        Ok(o)
    }
    #[cfg(not(windows))]
    {
        Err(io::Error::new(
            io::ErrorKind::Unsupported,
            "only Windows exclusive-from-birth ownership is implemented",
        ))
    }
}
impl Snapshot {
    pub fn copy_from(source: &Path, destination: &Path) -> io::Result<Self> {
        let source = File::open(source)?;
        let source_len = source.metadata()?.len();
        if source_len == 0 || source_len > MAX_BYTES as u64 {
            return Err(invalid("source length limit"));
        }
        let mut input = BufReader::with_capacity(65536, source);
        let mut file = exclusive_options()?.create_new(true).open(destination)?;
        let mut block = [0; 65536];
        let mut length = 0usize;
        loop {
            let count = input.read(&mut block)?;
            if count == 0 {
                break;
            }
            length = length
                .checked_add(count)
                .ok_or_else(|| invalid("copy length overflow"))?;
            if length > MAX_BYTES {
                return Err(invalid("copy byte limit"));
            }
            file.write_all(&block[..count])?;
        }
        if length as u64 != source_len {
            return Err(invalid("source size drift during copy"));
        }
        file.sync_all()?;
        Ok(Self { file, length })
    }
    pub fn len(&self) -> usize {
        self.length
    }
    pub fn is_empty(&self) -> bool {
        self.length == 0
    }
    pub fn sha256(&mut self) -> io::Result<[u8; 32]> {
        self.file.seek(SeekFrom::Start(0))?;
        let mut h = Sha256::new();
        let mut buffer = [0; 65536];
        loop {
            let n = self.file.read(&mut buffer)?;
            if n == 0 {
                break;
            }
            h.update(&buffer[..n]);
        }
        Ok(h.finalize().into())
    }
    pub fn scan_buffered(&mut self) -> io::Result<(Header, Vec<Entry>)> {
        self.file.seek(SeekFrom::Start(0))?;
        scan_reader(BufReader::with_capacity(65536, &mut self.file), self.length)
    }
    pub fn map(&self, offset: u64, length: usize) -> io::Result<View<'_>> {
        let end = offset
            .checked_add(length as u64)
            .ok_or_else(|| invalid("map overflow"))?;
        if length == 0 || end > self.length as u64 || length > MAX_BYTES {
            return Err(invalid("map bounds"));
        }
        // SAFETY: this wrapper created a new file with Windows share_mode(0)
        // before its first write. Thus no prior file handle/writable map can
        // exist. All initialization and sync finish before publication. The
        // only file handle stays private and no method modifies its contents.
        // This borrow retains that exclusive handle for the Mmap lifetime;
        // callers receive only immutable byte slices. Other platforms refuse
        // construction rather than assuming these sharing guarantees.
        let map = unsafe {
            MmapOptions::new()
                .offset(offset)
                .len(length)
                .map(&self.file)
        }?;
        Ok(View { map, _owner: self })
    }
}
