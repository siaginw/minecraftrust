//! Bounded local fixture adapter. Calls the same owned JNI export as Java.
use native_chunk::{OwnedPacketSnapshot, SnapshotRejection};
use rustcraft_ffi::Java_com_rustcraft_bridge_capture_OwnedSnapshotBridge_encodeOwnedV1;
use std::{fs::OpenOptions, io::Write, path::PathBuf, ptr::null_mut};

fn main() {
    if let Err(error) = run() {
        eprintln!("snapshot_replay: {error}");
        std::process::exit(1);
    }
}

fn reject(reason: &str) {
    println!("{{\"status\":\"REJECT\",\"reason\":\"{reason}\"}}");
}

fn run() -> Result<(), Box<dyn std::error::Error>> {
    let args: Vec<_> = std::env::args_os().skip(1).collect();
    if !(2..=3).contains(&args.len()) {
        return Err("expected input.bin output.bin [capacity]".into());
    }
    let input_path = PathBuf::from(&args[0]);
    let output_path = PathBuf::from(&args[1]);
    if output_path.exists() {
        return Err("output must be a new file".into());
    }
    let capacity = if args.len() == 3 {
        args[2]
            .to_str()
            .ok_or("invalid capacity")?
            .parse::<usize>()?
    } else {
        262144
    };
    if capacity == 0 || capacity > 1048576 {
        return Err("capacity must be 1..1048576".into());
    }
    if std::fs::metadata(&input_path)?.len()
        > native_chunk::packet_snapshot::MAX_SNAPSHOT_BYTES as u64
    {
        reject(SnapshotRejection::MalformedSnapshot.reason());
        return Ok(());
    }
    let input = std::fs::read(input_path)?;
    // Preflight yields a precise admission reason, without doing serialization.
    // Actual successful bytes and both result fields come from the one JNI call.
    if let Err(error) = OwnedPacketSnapshot::from_transport(&input) {
        reject(error.reason());
        return Ok(());
    }
    let mut output = vec![0u8; capacity];
    let raw = unsafe {
        Java_com_rustcraft_bridge_capture_OwnedSnapshotBridge_encodeOwnedV1(
            null_mut(),
            null_mut(),
            input.as_ptr() as i64,
            input.len() as i32,
            output.as_mut_ptr() as i64,
            capacity as i32,
        )
    };
    if raw < 0 {
        reject(if raw == -5 {
            "FALLBACK_CAPACITY"
        } else {
            "FALLBACK_ENCODE_FAILURE"
        });
        return Ok(());
    }
    let bits = raw as u64;
    if bits & !((1u64 << 62) | ((1u64 << 47) - 1)) != 0 || bits & (1u64 << 62) == 0 {
        return Err("malformed native V2 result".into());
    }
    let bytes_written = ((bits >> 16) & 0x7fff_ffff) as usize;
    let emitted_mask = (bits & 0xffff) as u16;
    if bytes_written > capacity || (bytes_written == 0 && emitted_mask != 0) {
        return Err("invalid native result bounds".into());
    }
    let mut file = OpenOptions::new()
        .write(true)
        .create_new(true)
        .open(output_path)?;
    file.write_all(&output[..bytes_written])?;
    println!("{{\"status\":\"PASS\",\"bytes_written\":{bytes_written},\"emitted_mask\":{emitted_mask},\"v2_result\":\"{raw}\"}}");
    Ok(())
}
