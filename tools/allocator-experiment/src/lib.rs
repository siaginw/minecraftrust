//! Isolated allocator experiment; actual project workloads, no production allocator selection.
#[rustfmt::skip]
#[path = "../../nbt-region-experiment/src/lossless.rs"]
pub mod lossless;
pub mod meter;
pub mod workload;

/// A scratch borrow cannot be published past arena destruction.
/// ```compile_fail
/// let escaped = { let arena = bumpalo::Bump::new(); arena.alloc_slice_copy(&[1u8,2,3]) };
/// println!("{:?}", escaped);
/// ```
/// Nor can reset invalidate a still-used view.
/// ```compile_fail
/// let mut arena = bumpalo::Bump::new();
/// let view = arena.alloc_slice_copy(&[1u8,2,3]);
/// arena.reset();
/// println!("{:?}", view);
/// ```
pub struct ScratchLifetimeContract;
