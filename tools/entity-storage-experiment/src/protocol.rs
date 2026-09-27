use crate::spatial::Spatial;
use crate::storage::*;
use std::fmt::Write;

pub fn key_json(k: Key) -> String {
    format!("[{},{},{}]", k.world, k.slot, k.generation)
}
pub fn keys_json(keys: &[Key]) -> String {
    format!(
        "[{}]",
        keys.iter()
            .map(|k| key_json(*k))
            .collect::<Vec<_>>()
            .join(",")
    )
}
pub fn row_json(r: &Row) -> String {
    let mods = r
        .cold
        .mods
        .iter()
        .map(|(id, v)| format!("[{}, {:?}]", id, v))
        .collect::<Vec<_>>()
        .join(",");
    format!("{{\"key\":{},\"position\":{:?},\"velocity\":{:?},\"bounds\":{:?},\"lifecycle\":{},\"behavior\":{},\"capabilities\":{:?},\"nbt\":{:?},\"java_token\":{},\"mods\":[{}],\"extension\":{}}}",key_json(r.key),r.hot.position.0,r.hot.velocity.0,r.hot.bounds.0,r.hot.lifecycle.0,r.hot.behavior.0,r.cold.capabilities,r.cold.nbt,r.cold.java_token,mods,r.extension.map(|x|x.0.to_string()).unwrap_or_else(||"null".into()))
}
pub fn rows_json(rows: &[Row]) -> String {
    let mut text = String::from("[");
    for (i, r) in rows.iter().enumerate() {
        if i > 0 {
            text.push(',')
        }
        text.push_str(&row_json(r));
    }
    text.push(']');
    text
}
fn number(s: &str) -> Result<i64, String> {
    s.parse().map_err(|_| "integer".into())
}
fn key(words: &[&str]) -> Result<Key, String> {
    if words.len() < 4 {
        return Err("key shape".into());
    }
    Ok(Key {
        world: words[1].parse().map_err(|_| "world")?,
        slot: words[2].parse().map_err(|_| "slot")?,
        generation: words[3].parse().map_err(|_| "generation")?,
    })
}
pub fn trace<S: Store>(
    path: &str,
    session: &str,
    challenge: &str,
    fault: &str,
) -> Result<(), String> {
    let metadata = std::fs::metadata(path).map_err(|e| e.to_string())?;
    if metadata.len() > 65536 {
        return Err("input byte budget".into());
    }
    let input = std::fs::read_to_string(path).map_err(|e| e.to_string())?;
    let commands = input.lines().collect::<Vec<_>>();
    if commands.len() > 256 {
        return Err("command budget".into());
    }
    let mut registry = Registry::<S>::new(9);
    let mut faulted = false;
    for (step, command) in std::iter::once("INITIAL")
        .chain(commands.iter().copied())
        .enumerate()
    {
        let w = command.split_whitespace().collect::<Vec<_>>();
        if w.is_empty() {
            return Err("empty command".into());
        }
        let mut status = "OK";
        let mut events = Vec::new();
        let mut query = Vec::new();
        let mut probe = None;
        let mut pairs = Vec::new();
        match w[0] {
            "INITIAL" if step == 0 => {}
            "SPAWN" if w.len() == 3 => {
                let slot = w[1].parse::<usize>().map_err(|_| "slot")?;
                let seed = w[2].parse::<u64>().map_err(|_| "seed")?;
                if slot >= 64 {
                    return Err("trace slot budget".into());
                }
                if let Err(e) = registry.spawn(slot, seed) {
                    status = e
                }
            }
            "DESPAWN" if w.len() == 4 => {
                if let Err(e) = registry.despawn(key(&w)?) {
                    status = e
                }
            }
            "VELOCITY" if w.len() == 7 => {
                if let Err(e) =
                    registry.velocity(key(&w)?, [number(w[4])?, number(w[5])?, number(w[6])?])
                {
                    status = e
                }
            }
            "LIFE" if w.len() == 5 => {
                let v = w[4].parse().map_err(|_| "lifecycle")?;
                if let Err(e) = registry.lifecycle(key(&w)?, v) {
                    status = e
                }
            }
            "EXT" if w.len() == 5 => {
                let v = number(w[4])?;
                if v < -1 {
                    return Err("extension value".into());
                }
                if let Err(e) = registry.extension(
                    key(&w)?,
                    if v == -1 {
                        None
                    } else {
                        Some(Extension(v as u64))
                    },
                ) {
                    status = e
                }
            }
            "PROBE" if w.len() == 4 => {
                probe = registry.read(key(&w)?);
                if probe.is_none() {
                    status = "STALE"
                }
            }
            "TICK" if w.len() == 1 => {
                registry.store.tick();
                events = registry.store.scan().into_iter().map(|(k, _)| k).collect();
                events.sort_unstable();
            }
            "QUERY" if w.len() == 7 => {
                query = Spatial::new(registry.store.scan()).query(
                    [number(w[1])?, number(w[2])?, number(w[3])?],
                    [number(w[4])?, number(w[5])?, number(w[6])?],
                )
            }
            "PAIRS" if w.len() == 1 => pairs = Spatial::new(registry.store.scan()).pairs(),
            _ => return Err("unknown command or arity".into()),
        }
        let mut rows = registry.snapshot();
        if !faulted {
            match fault {
                "missing" if !rows.is_empty() => {
                    rows.pop();
                    faulted = true
                }
                "identity" if !rows.is_empty() => {
                    rows[0].key.generation += 1;
                    faulted = true
                }
                "value" if w[0] == "TICK" && !rows.is_empty() => {
                    rows[0].hot.position.0[0] = rows[0].hot.position.0[0].wrapping_add(1);
                    faulted = true
                }
                "order" if events.len() > 1 => {
                    events.reverse();
                    faulted = true
                }
                _ => {}
            }
        }
        let mut pair_json = String::from("[");
        for (i, (a, b)) in pairs.iter().enumerate() {
            if i > 0 {
                pair_json.push(',')
            }
            write!(pair_json, "[{},{}]", key_json(*a), key_json(*b)).unwrap()
        }
        pair_json.push(']');
        println!("{{\"schema\":\"ENTITY_STORAGE_BOUNDARY_V1\",\"session\":\"{}\",\"challenge\":\"{}\",\"step\":{},\"status\":\"{}\",\"rows\":{},\"events\":{},\"query\":{},\"pairs\":{},\"probe\":{},\"production_authority\":false}}",session,challenge,step,status,rows_json(&rows),keys_json(&events),keys_json(&query),pair_json,probe.as_ref().map(row_json).unwrap_or_else(||"null".into()));
    }
    Ok(())
}

pub fn mix(mut h: u64, value: u64) -> u64 {
    for byte in value.to_le_bytes() {
        h = (h ^ u64::from(byte)).wrapping_mul(1_099_511_628_211)
    }
    h
}
pub fn hot_checksum(rows: &[(Key, Hot)]) -> u64 {
    let mut h = 14_695_981_039_346_656_037;
    for (k, v) in rows {
        for x in [k.world, k.slot as u64, k.generation] {
            h = mix(h, x)
        }
        for x in v
            .position
            .0
            .into_iter()
            .chain(v.velocity.0)
            .chain(v.bounds.0)
        {
            h = mix(h, x as u64)
        }
        h = mix(h, u64::from(v.lifecycle.0));
        h = mix(h, u64::from(v.behavior.0));
    }
    h
}
pub fn state_checksum(rows: &[Row]) -> u64 {
    let mut h = 14_695_981_039_346_656_037;
    for r in rows {
        h = mix(h, hot_checksum(&[(r.key, r.hot)]));
        h = mix(h, r.cold.capabilities.len() as u64);
        for x in &r.cold.capabilities {
            h = mix(h, *x)
        }
        h = mix(h, r.cold.nbt.len() as u64);
        for x in &r.cold.nbt {
            h = mix(h, u64::from(*x))
        }
        h = mix(h, r.cold.java_token);
        h = mix(h, r.cold.mods.len() as u64);
        for (id, bytes) in &r.cold.mods {
            h = mix(h, u64::from(*id));
            h = mix(h, bytes.len() as u64);
            for b in bytes {
                h = mix(h, u64::from(*b))
            }
        }
        h = mix(h, r.extension.map(|x| x.0).unwrap_or(u64::MAX));
    }
    h
}
