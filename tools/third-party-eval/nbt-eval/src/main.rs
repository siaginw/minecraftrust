//! simdnbt vs crates/nbt parity + benchmark on OUR fixture set.
//! Approach: all fixtures are authored in OUR NbtTag model and encoded by
//! OUR encoder (byte-exact reference). Parity = simdnbt decodes those bytes
//! to the same semantic value; and simdnbt's re-encode of its decode
//! roundtrips back through OUR decoder to the same value. No manual
//! construction of simdnbt values (avoids API-version drift).

use std::io::Cursor;
use std::time::Instant;

use nbt::{NbtDecoder, NbtEncoder, NbtTag};
use simdnbt::owned::NbtTag as OTag;

fn enc(t: &NbtTag) -> Vec<u8> {
    let mut v = Vec::new();
    NbtEncoder::encode("root", t, &mut v).unwrap();
    v
}

fn fixtures() -> Vec<(&'static str, NbtTag)> {
    vec![
        ("vanilla", vanilla()),
        ("forge_caps", forge_caps()),
        ("tile_entity", tile_entity()),
        ("longarray_heavy", longarray_heavy()),
        ("large_5x8", large(5, 8)),
        ("large_12x2", large(12, 2)),
        ("chunk_shape", chunk_shape()),
        ("tag_space", tag_space()),
        ("mutf8_edge", mutf8_edge()),
    ]
}

fn vanilla() -> NbtTag {
    let mut m = std::collections::HashMap::new();
    m.insert("byte".into(), NbtTag::Byte(127));
    m.insert("short".into(), NbtTag::Short(-32768));
    m.insert("int".into(), NbtTag::Int(2147483647));
    m.insert("long".into(), NbtTag::Long(-9223372036854775808));
    m.insert("float".into(), NbtTag::Float(3.14159));
    m.insert("double".into(), NbtTag::Double(2.718281828459045));
    m.insert("string".into(), NbtTag::String("hello".into()));
    m.insert("byte_arr".into(), NbtTag::ByteArray(vec![1, 2, 3, 255]));
    m.insert("int_arr".into(), NbtTag::IntArray(vec![-100, 0, 100, 999999]));
    m.insert("long_arr".into(), NbtTag::LongArray(vec![-999999999999, 0, 999999999999]));
    m.insert("list".into(), NbtTag::List(vec![NbtTag::Int(1), NbtTag::Int(2), NbtTag::Int(3)]));
    m.insert("empty_list".into(), NbtTag::List(vec![]));
    let mut nested = std::collections::HashMap::new();
    nested.insert("level".into(), NbtTag::Short(42));
    m.insert("nested".into(), NbtTag::Compound(nested));
    m.insert("empty_compound".into(), NbtTag::Compound(Default::default()));
    NbtTag::Compound(m)
}

fn forge_caps() -> NbtTag {
    let mut m = std::collections::HashMap::new();
    let mut cap = std::collections::HashMap::new();
    cap.insert("Owner".into(), NbtTag::String("player:with\u{0}null".into()));
    cap.insert("Emoji".into(), NbtTag::String("forge \u{1F980} crab".into()));
    let mut slot = std::collections::HashMap::new();
    slot.insert("id".into(), NbtTag::String("minecraft:diamond".into()));
    slot.insert("Count".into(), NbtTag::Byte(64));
    cap.insert("Inv".into(), NbtTag::List(vec![NbtTag::Compound(slot)]));
    m.insert("ForgeCaps\u{0}thermalexpansion".into(), NbtTag::Compound(cap));
    NbtTag::Compound(m)
}

fn tile_entity() -> NbtTag {
    let mut m = std::collections::HashMap::new();
    m.insert("id".into(), NbtTag::String("minecraft:chest".into()));
    m.insert("x".into(), NbtTag::Int(112));
    m.insert("y".into(), NbtTag::Int(64));
    m.insert("z".into(), NbtTag::Int(208));
    m.insert("CustomName".into(), NbtTag::String("Unicode Chest \u{2603}".into()));
    let mut modtag = std::collections::HashMap::new();
    modtag.insert("energy".into(), NbtTag::LongArray((0..64).map(|i| i * 1000003).collect()));
    m.insert("modtag".into(), NbtTag::Compound(modtag));
    NbtTag::Compound(m)
}

fn longarray_heavy() -> NbtTag {
    let mut m = std::collections::HashMap::new();
    m.insert("heightmaps".into(), NbtTag::LongArray(vec![0x0123456789ABCDEF; 37]));
    m.insert("zeroes".into(), NbtTag::LongArray(vec![0; 1024]));
    NbtTag::Compound(m)
}

fn large(depth: usize, breadth: usize) -> NbtTag {
    fn build(depth: usize, breadth: usize) -> NbtTag {
        if depth == 0 { return NbtTag::Int(0); }
        let mut m = std::collections::HashMap::new();
        for i in 0..breadth { m.insert(format!("k{}", i), build(depth - 1, breadth)); }
        NbtTag::Compound(m)
    }
    build(depth, breadth)
}

fn chunk_shape() -> NbtTag {
    let mut m = std::collections::HashMap::new();
    let mut lvl = std::collections::HashMap::new();
    lvl.insert("xPos".into(), NbtTag::Int(7));
    lvl.insert("zPos".into(), NbtTag::Int(13));
    lvl.insert("Sections".into(), NbtTag::List((0..4).map(|i| {
        let mut s = std::collections::HashMap::new();
        s.insert("Y".into(), NbtTag::Byte(i));
        s.insert("Blocks".into(), NbtTag::ByteArray(vec![1u8; 4096]));
        s.insert("Data".into(), NbtTag::ByteArray(vec![2u8; 2048]));
        NbtTag::Compound(s)
    }).collect()));
    lvl.insert("Entities".into(), NbtTag::List(vec![]));
    lvl.insert("TileEntities".into(), NbtTag::List(vec![tile_entity()]));
    lvl.insert("InhabitedTime".into(), NbtTag::Long(123456));
    m.insert("Level".into(), NbtTag::Compound(lvl));
    NbtTag::Compound(m)
}

fn tag_space() -> NbtTag {
    let mut m = std::collections::HashMap::new();
    m.insert("la".into(), NbtTag::LongArray(vec![-1, i64::MAX, i64::MIN]));
    m.insert("ia".into(), NbtTag::IntArray(vec![i32::MAX, i32::MIN]));
    m.insert("list_of_compound".into(), NbtTag::List(vec![NbtTag::Compound(Default::default())]));
    NbtTag::Compound(m)
}

fn mutf8_edge() -> NbtTag {
    let mut m = std::collections::HashMap::new();
    m.insert("nul".into(), NbtTag::String("a\u{0}b".into()));
    m.insert("surrogate_pair".into(), NbtTag::String("\u{1F980}".into()));
    m.insert("del".into(), NbtTag::String("\u{7F}".into()));
    m.insert("nonbmp".into(), NbtTag::String("\u{10FFFF}".into()));
    NbtTag::Compound(m)
}

fn deep_chain(n: usize) -> NbtTag {
    let mut t = NbtTag::Int(0);
    for _ in 0..n {
        let mut m = std::collections::HashMap::new();
        m.insert("d".into(), t);
        t = NbtTag::Compound(m);
    }
    t
}

fn main() {
    let mut pass = 0usize; let mut fail = 0usize; let mut disagree = 0usize;

    println!("== PARITY: simdnbt(borrowed) decodes OUR bytes; simdnbt re-encode decodes back ==");
    for (name, t) in fixtures() {
        let bytes = enc(&t);
        let mut cursor = Cursor::new(&bytes[..]);
        let sim = simdnbt::owned::read(&mut cursor);
        match sim {
            Ok(n) => {
                let p1 = n.name().to_string() == "root";
                // full semantic pass: sim re-encode of its decode, then OUR
                // decoder must recover the exact original tree
                let mut rebytes = Vec::new();
                n.write(&mut rebytes);
                let mut p2 = false;
                if let Ok((n2, t2)) = NbtDecoder::decode(&rebytes) {
                    p2 = n2 == "root" && t2 == t;
                }
                if p1 && p2 { pass += 2; } else { fail += 2; }
                println!("  {:<18} sim_decodes_name={} sim_roundtrip_via_ours={} ({} bytes)",
                    name, p1, p2, bytes.len());
            }
            Err(e) => { fail += 2; println!("  {:<18} simdnbt DECODE ERROR: {:?}", name, e); }
        }
    }

    println!("\n== MALFORMED INPUT behavior (agreement recorded) ==");
    let full = enc(&vanilla());
    let malformed: Vec<(&str, Vec<u8>)> = vec![
        ("empty", vec![]),
        ("truncated_header", { let mut v = full.clone(); v.truncate(5); v }),
        ("truncated_mid", { let mut v = enc(&large(6, 3)); v.truncate(v.len() - 4); v }),
        ("bad_type_id", { let mut v = full.clone(); v[0] = 99; v }),
        ("zero_root_type", { let mut v = vec![0u8, 0, 0]; v.extend_from_slice(&full[3..]); v }),
    ];
    for (name, bytes) in &malformed {
        let ours = NbtDecoder::decode(bytes).is_err();
        let mut cursor = Cursor::new(&bytes[..]);
        let sim = simdnbt::borrow::read(&mut cursor).is_err();
        if ours == sim { pass += 1; println!("  {:<18} both={}", name, if ours {"REJECT"} else {"ACCEPT"}); }
        else { disagree += 1; println!("  {:<18} DISAGREE ours={} sim={}", name, ours, sim); }
    }

    println!("\n== DEPTH (recorded; our codec enforces MAX_DEPTH on encode; vanilla JVM uses stack guard) ==");
    {
        let mut v600 = Vec::new();
        let e600 = NbtEncoder::encode("root", &deep_chain(600), &mut v600).is_err();
        let mut v400 = Vec::new();
        match NbtEncoder::encode("root", &deep_chain(400), &mut v400) {
            Err(e) => println!("  depth=600 ours_encode_rejects={} depth=400 ours_encode_err={:?}", e600, e),
            Ok(()) => {
                let ours_ok = NbtDecoder::decode(&v400).is_ok();
                let mut cursor = Cursor::new(&v400[..]);
                let sim_ok = simdnbt::owned::read(&mut cursor).is_ok();
                if ours_ok != sim_ok { disagree += 1; }
                println!("  depth=600 ours_encode_rejects={} depth=400 ours_decode={} sim_decode={}", e600, ours_ok, sim_ok);
            }
        }
    }

    println!("\n== BENCHMARK (median of 200, 50 warmup) ==");
    println!("fixture,bytes,ours_decode_ns,sim_decode_ns,ours_encode_ns,sim_encode_ns,ours_scan_ns,sim_scan_ns");
    for (name, t) in fixtures() {
        let bytes = enc(&t);
        let mut c2 = Cursor::new(&bytes[..]);
        let sim_owned = simdnbt::owned::read(&mut c2).unwrap();
        let n = 200usize;
        let td_ours = bench(n, || { NbtDecoder::decode(&bytes); });
        let td_sim = bench(n, || { let mut c = Cursor::new(&bytes[..]); simdnbt::owned::read(&mut c); });
        let te_ours = bench(n, || { let mut v = Vec::with_capacity(bytes.len()); NbtEncoder::encode("root", &t, &mut v); });
        let te_sim = bench(n, || { let mut v = Vec::with_capacity(bytes.len()); sim_owned.write(&mut v); });
        let ts_ours = bench(n, || { count_ints_ours(&bytes); });
        let ts_sim = bench(n, || { let mut c = Cursor::new(&bytes[..]); let _ = simdnbt::owned::read(&mut c); });
        println!("{},{},{},{},{},{},{},{}", name, bytes.len(), td_ours, td_sim, te_ours, te_sim, ts_ours, ts_sim);
    }

    println!("\nRESULT parity_pass={} parity_fail={} behavioral_disagreements={}", pass, fail, disagree);
}

fn bench<F: FnMut()>(n: usize, mut f: F) -> u128 {
    for _ in 0..50 { f(); }
    let mut s = Vec::with_capacity(n);
    for _ in 0..n { let t0 = Instant::now(); f(); s.push(t0.elapsed().as_nanos()); }
    s.sort(); s[n / 2]
}


fn count_ints_ours(bytes: &[u8]) -> i32 {
    fn walk(t: &NbtTag) -> i32 {
        match t {
            NbtTag::Int(_) => 1,
            NbtTag::List(v) => v.iter().map(walk).sum(),
            NbtTag::Compound(m) => m.values().map(walk).sum(),
            _ => 0,
        }
    }
    match NbtDecoder::decode(bytes) { Ok((_, t)) => walk(&t), Err(_) => 0 }
}

