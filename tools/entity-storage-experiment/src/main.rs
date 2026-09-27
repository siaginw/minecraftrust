mod accounting;
mod bench;
mod protocol;
mod spatial;
mod storage;
#[global_allocator]
static ALLOCATOR: accounting::Counted = accounting::Counted;
fn dispatch<S: storage::Store>(args: &[String]) -> Result<(), String> {
    match args[1].as_str() {
        "trace" => protocol::trace::<S>(
            &args[5],
            &args[3],
            &args[4],
            args.get(6).map(String::as_str).unwrap_or("none"),
        ),
        "bench" => {
            let n = args[5].parse().map_err(|_| "entity count")?;
            bench::run::<S>(&args[2], n, &args[3], &args[4]);
            Ok(())
        }
        _ => Err("mode".into()),
    }
}
fn main() {
    let args = std::env::args().collect::<Vec<_>>();
    if args.len() < 6
        || args[3].len() != 32
        || args[4].len() != 64
        || !args[3..5]
            .iter()
            .all(|s| s.bytes().all(|b| b.is_ascii_hexdigit()))
    {
        eprintln!("mode backend session challenge input required");
        std::process::exit(2)
    }
    let result = match args[2].as_str() {
        "soa" => dispatch::<storage::Soa>(&args),
        "hecs" => dispatch::<storage::Hecs>(&args),
        "bevy" => dispatch::<storage::Bevy>(&args),
        "shipyard" => dispatch::<storage::Shipyard>(&args),
        _ => Err("backend".into()),
    };
    if let Err(e) = result {
        eprintln!("{}", e);
        std::process::exit(2)
    }
}
