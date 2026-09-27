use native_observability::*;
fn main() -> Result<(), Box<dyn std::error::Error>> {
    let path = std::env::args().nth(1).ok_or("missing batch path")?;
    if std::fs::metadata(&path)?.len() > 1_048_576 {
        return Err("batch capacity".into());
    }
    let text = std::fs::read_to_string(path)?;
    let mut lines = text.lines();
    if lines.next() != Some("RUSTCRAFT_STAGE_BATCH_V1") {
        return Err("schema".into());
    }
    let mut events = Vec::new();
    let mut samples = 0usize;
    let mut missing_seen = [false; 18];
    let mut losses_started = false;
    for line in lines {
        if events.len() == 4114 {
            return Err("event capacity".into());
        }
        let fields: Vec<_> = line.split('\t').collect();
        if fields.len() != 3 {
            return Err("fields".into());
        }
        let stage = *Stage::ALL.get(fields[1].parse::<usize>()?).ok_or("stage")?;
        let parsed = match fields[0] {
            "S" => {
                if samples == 4096 || losses_started {
                    return Err("sample capacity/order".into());
                }
                samples += 1;
                let v = fields[2].parse::<i64>()?;
                if v < -1 {
                    return Err("negative duration".into());
                }
                (false, stage, (v >= 0).then_some(v as u64))
            }
            "D" => {
                if missing_seen[stage as usize] {
                    return Err("duplicate loss counter".into());
                }
                missing_seen[stage as usize] = true;
                losses_started = true;
                (true, stage, Some(fields[2].parse::<u64>()?))
            }
            _ => return Err("kind".into()),
        };
        events.push(parsed);
    }
    // Entire batch validated before any aggregate mutation or successful output.
    let mut collector = Collector::new(None).map_err(|_| "collector")?;
    for (missing, stage, value) in events {
        if missing {
            collector.record_missing(stage, value.ok_or("missing count")?)
        } else {
            collector.record_duration(stage, value)
        }
    }
    for (stage_index, stage) in Stage::ALL.into_iter().enumerate() {
        let d = collector.duration(stage);
        let q = |p| {
            d.percentile(p).map_or("null".to_owned(), |v| {
                format!("[{},{}]", v.lower_ns, v.upper_ns)
            })
        };
        println!("{{\"stage\":{stage_index},\"known\":{},\"unknown\":{},\"above_range\":{},\"p50_ns\":{},\"p95_ns\":{},\"p99_ns\":{}}}",d.coverage.known,d.coverage.unknown,d.above_range,q(50),q(95),q(99));
    }
    Ok(())
}
