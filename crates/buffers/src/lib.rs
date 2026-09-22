//! Shared buffer abstractions for coarse native/JVM transfers.

pub struct NativeBuffer {
    ptr: *mut u8,
    len: usize,
    capacity: usize,
}

impl NativeBuffer {
    pub fn new(capacity: usize) -> Self {
        let mut vec = Vec::with_capacity(capacity);
        let ptr = vec.as_mut_ptr();
        let cap = vec.capacity();
        std::mem::forget(vec);
        Self {
            ptr,
            len: 0,
            capacity: cap,
        }
    }

    pub fn as_slice(&self) -> &[u8] {
        unsafe { std::slice::from_raw_parts(self.ptr, self.len) }
    }

    pub fn as_mut_slice(&mut self) -> &mut [u8] {
        unsafe { std::slice::from_raw_parts_mut(self.ptr, self.len) }
    }

    pub fn len(&self) -> usize {
        self.len
    }

    pub fn is_empty(&self) -> bool {
        self.len == 0
    }

    pub fn capacity(&self) -> usize {
        self.capacity
    }
}

impl Drop for NativeBuffer {
    fn drop(&mut self) {
        if !self.ptr.is_null() && self.capacity > 0 {
            unsafe {
                let _ = Vec::from_raw_parts(self.ptr, self.len, self.capacity);
            }
        }
    }
}

unsafe impl Send for NativeBuffer {}
unsafe impl Sync for NativeBuffer {}
