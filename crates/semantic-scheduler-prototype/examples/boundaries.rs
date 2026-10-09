//! Public API adapter exercise; no game classes or callbacks.
use semantic_scheduler_prototype::*;

fn emit(args: &[String], boundary: usize, decision: Option<&Decision>, scheduler: &Scheduler) {
    let (sequence, outcome, effects) = match decision {
        None => ("null".to_owned(), "INITIAL".to_owned(), "[]".to_owned()),
        Some(d) => {
            let outcome = match d.outcome {
                Outcome::Committed => "Committed".to_owned(),
                Outcome::Skipped(reason) => format!("{reason:?}"),
            };
            let effects = d
                .effects
                .iter()
                .map(|e| format!("[{},{},{}]", e.index, e.before, e.after))
                .collect::<Vec<_>>()
                .join(",");
            (d.sequence.to_string(), outcome, format!("[{effects}]"))
        }
    };
    println!("{{\"schema\":\"SEMANTIC_SCHEDULER_BOUNDARY_V1\",\"session\":\"{}\",\"challenge\":\"{}\",\"scenario\":\"{}\",\"seed\":{},\"boundary\":{},\"sequence\":{},\"outcome\":\"{}\",\"tick\":{},\"effects\":{},\"values\":{:?},\"production_authority\":false}}",args[1],args[2],args[3],args[4],boundary,sequence,outcome,scheduler.tick(),effects,scheduler.values());
}

fn run(args: &[String]) -> Result<(), String> {
    if args.len() != 5
        || args[1].len() != 32
        || args[2].len() != 64
        || !args[1..=2]
            .iter()
            .all(|s| s.bytes().all(|b| b.is_ascii_hexdigit()))
    {
        return Err("expected session_hex32 challenge_hex64 scenario seed_u64".into());
    }
    let modes = [
        "ordered",
        "conflict",
        "cancel",
        "deadline",
        "revoke",
        "unload",
        "external",
        "budget",
        "arithmetic",
    ];
    if !modes.contains(&args[3].as_str()) {
        return Err("unknown scenario".into());
    }
    let seed = args[4].parse::<u64>().map_err(|_| "invalid seed")?;
    if args[4] != seed.to_string() {
        return Err("seed must be canonical decimal".into());
    }
    let mut initial: Vec<i64> = (0..32).map(|i| (seed % 97) as i64 + i).collect();
    if args[3] == "arithmetic" {
        initial[0] = i64::MAX;
    }
    let scope = Scope {
        session: 1,
        world: 2,
        incarnation: 3,
        registry_epoch: 4,
    };
    let mut scheduler = Scheduler::new(
        scope,
        initial,
        Limits {
            max_dispatched: 8,
            ..Limits::default()
        },
    )
    .map_err(|e| format!("{e:?}"))?;
    emit(args, 0, None, &scheduler);
    for batch in 0..4 {
        let q = scheduler.qualify_offline().unwrap();
        let mut tickets = Vec::new();
        let mut ids = Vec::new();
        for job in 0..8 {
            let start = if args[3] == "conflict" {
                (job % 4) * 4
            } else {
                job * 4
            };
            let mut budget = Budget::bounded_default();
            if args[3] == "budget" && job == 3 {
                budget.max_steps = 3;
            }
            let id = scheduler
                .submit(
                    &q,
                    Transform {
                        start,
                        count: 4,
                        multiply: 2,
                        add: batch as i64 + 1,
                    },
                    budget,
                )
                .unwrap();
            tickets.push(scheduler.dispatch(&id).unwrap());
            ids.push(id);
        }
        tickets.reverse();
        let mut completions = execute_scoped(tickets, 4).unwrap();
        completions.sort_by_key(|c| std::cmp::Reverse(c.sequence()));
        let rotation = (seed % 8) as usize;
        completions.rotate_left(rotation);
        for completion in completions {
            scheduler.accept(completion).unwrap();
        }
        match args[3].as_str() {
            "cancel" => scheduler.cancel(&ids[0]).unwrap(),
            "deadline" => scheduler.advance_clock(scheduler.tick() + 1000).unwrap(),
            "revoke" => scheduler.revoke().unwrap(),
            "unload" => scheduler.unload(),
            "external" => scheduler.external_write(31, -777 - batch as i64).unwrap(),
            _ => {}
        }
        for job in 0..8 {
            let decision = scheduler.drain_one().ok_or("unexpected pending head")?;
            emit(args, 1 + batch * 8 + job, Some(&decision), &scheduler);
        }
        if args[3] == "unload" {
            scheduler.reload_offline().unwrap();
        }
        if scheduler.usage().outstanding != 0 || scheduler.usage().reserved_bytes != 0 {
            return Err("reservation leak".into());
        }
    }
    Ok(())
}
fn main() {
    let args: Vec<_> = std::env::args().collect();
    if let Err(error) = run(&args) {
        eprintln!("{error}");
        std::process::exit(2)
    }
}
