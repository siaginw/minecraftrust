use state_diff_experiment::{plan, Error, ModelWorld, PureOp, CELLS};
use std::io::{self, BufRead, Write};

fn operation(line: &str) -> Result<PureOp, String> {
    let a: Vec<_> = line.split(' ').collect();
    let number = |i: usize| {
        a.get(i)
            .ok_or("missing integer")?
            .parse::<i32>()
            .map_err(|_| "invalid integer")
    };
    match a[0] {
        "ADD" if a.len() == 3 => Ok(PureOp::Add {
            cell: usize::try_from(number(1)?).map_err(|_| "negative cell")?,
            delta: number(2)?,
        }),
        "COPY" if a.len() == 3 => Ok(PureOp::Copy {
            source: usize::try_from(number(1)?).map_err(|_| "negative source")?,
            target: usize::try_from(number(2)?).map_err(|_| "negative target")?,
        }),
        "GRADIENT" if a.len() == 5 => Ok(PureOp::Gradient {
            start: usize::try_from(number(1)?).map_err(|_| "negative start")?,
            len: usize::try_from(number(2)?).map_err(|_| "negative len")?,
            base: u16::try_from(number(3)?).map_err(|_| "base range")?,
            step: i16::try_from(number(4)?).map_err(|_| "step range")?,
        }),
        _ => Err("unknown operation".into()),
    }
}
fn emit(
    w: &ModelWorld,
    h: &[&str],
    step: usize,
    outcome: Result<Vec<[i32; 3]>, Error>,
) -> Result<(), String> {
    let (status, error, effects) = match outcome {
        Ok(e) => ("COMMITTED", "null".into(), e),
        Err(e) => ("REJECTED", format!("\"{}\"", e.code()), vec![]),
    };
    let status = if step == 0 { "INITIAL" } else { status };
    println!("{{\"schema\":\"STATE_DIFF_BOUNDARY_V1\",\"session\":\"{}\",\"challenge\":\"{}\",\"trace_sha256\":\"{}\",\"qualification\":\"OFFLINE_MODEL_ONLY\",\"production_authority\":false,\"step\":{step},\"status\":\"{status}\",\"error\":{error},\"generation\":{},\"values\":{:?},\"effects\":{:?}}}",h[2],h[3],h[4],w.generation(),w.values(),effects);
    io::stdout().flush().map_err(|e| e.to_string())
}
fn run() -> Result<(), String> {
    let fault = std::env::args().nth(1).unwrap_or_else(|| "none".into());
    if fault != "none" && fault != "--fault-effect-receipt" {
        return Err("unknown experiment flag".into());
    }
    let mut lines = io::stdin().lock().lines();
    let first = lines
        .next()
        .ok_or("missing init")?
        .map_err(|e| e.to_string())?;
    let h: Vec<_> = first.split(' ').collect();
    if h.len() != 6 || h[0] != "INIT" || h[1] != "STATE_DIFF_MODEL_V1" {
        return Err("init schema".into());
    }
    if h[2..5]
        .iter()
        .any(|s| s.is_empty() || !s.bytes().all(|b| b.is_ascii_hexdigit()))
    {
        return Err("binding syntax".into());
    }
    let values: Vec<u16> = h[5]
        .split(',')
        .map(str::parse)
        .collect::<Result<_, _>>()
        .map_err(|_| "values")?;
    let cells: [u16; CELLS] = values.try_into().map_err(|_| "16 cells required")?;
    let mut world = ModelWorld::new(cells).map_err(|e| e.code())?;
    world.admit_offline_model().map_err(|e| e.code())?;
    emit(&world, &h, 0, Ok(vec![]))?;
    for (i, line) in lines.enumerate() {
        let op = operation(&line.map_err(|e| e.to_string())?)?;
        let mut result: Result<Vec<[i32; 3]>, Error> = world
            .snapshot()
            .and_then(|s| plan(&s, 0, op))
            .and_then(|d| world.commit_ordered(&[d]))
            .map(|r| {
                r.effects
                    .iter()
                    .map(|e| [e.cell as i32, i32::from(e.before), i32::from(e.after)])
                    .collect()
            });
        if fault == "--fault-effect-receipt" && i == 0 {
            if let Ok(effects) = &mut result {
                if let Some(effect) = effects.first_mut() {
                    effect[2] ^= 1;
                }
            }
        }
        emit(&world, &h, i + 1, result)?;
    }
    Ok(())
}
fn main() {
    if let Err(error) = run() {
        eprintln!("{error}");
        std::process::exit(2);
    }
}
