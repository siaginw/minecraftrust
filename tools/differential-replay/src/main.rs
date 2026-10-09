//! Independent implementation of the bounded REPLAY_TOY_V1 specification.
//! No Minecraft/Forge implementation or production integration is included.
use std::io::{self, BufRead, Write};

#[derive(Clone, Debug, Eq, Ord, PartialEq, PartialOrd)]
struct Tick(u64, u64, usize, i32);

struct World {
    blocks: [u8; 16],
    x: i32,
    health: i32,
    time: u64,
    serial: u64,
    rng: u32,
    scheduled: Vec<Tick>,
    reverse: bool,
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

fn named(out: &mut Vec<u8>, tag: u8, name: &str) {
    out.push(tag);
    out.extend((name.len() as u16).to_be_bytes());
    out.extend(name.as_bytes());
}

impl World {
    fn save(&self) -> Vec<u8> {
        let mut out = vec![10, 0, 0]; // unnamed root TAG_Compound
        named(&mut out, 3, "x");
        out.extend(self.x.to_be_bytes());
        named(&mut out, 3, "health");
        out.extend(self.health.to_be_bytes());
        named(&mut out, 4, "time");
        out.extend(self.time.to_be_bytes());
        named(&mut out, 4, "rng");
        out.extend(u64::from(self.rng).to_be_bytes());
        named(&mut out, 4, "serial");
        out.extend(self.serial.to_be_bytes());
        named(&mut out, 7, "blocks");
        out.extend(16_i32.to_be_bytes());
        out.extend(self.blocks);
        named(&mut out, 9, "scheduled");
        out.push(4); // list of longs, flattened due/serial/cell/delta
        out.extend(((self.scheduled.len() * 4) as i32).to_be_bytes());
        for tick in &self.scheduled {
            for value in [
                tick.0 as i64,
                tick.1 as i64,
                tick.2 as i64,
                i64::from(tick.3),
            ] {
                out.extend(value.to_be_bytes());
            }
        }
        out.push(0);
        out
    }

    fn mutate(&mut self, cell: usize, value: u8, mutations: &mut Vec<[i32; 3]>) {
        mutations.push([cell as i32, i32::from(self.blocks[cell]), i32::from(value)]);
        self.blocks[cell] = value;
    }

    fn callbacks(&self, kind: &str, target: &mut Vec<String>) {
        for observer in if self.reverse { ["B", "A"] } else { ["A", "B"] } {
            target.push(format!("{observer}:{kind}"));
        }
    }

    fn step(&mut self, line: &str, step: usize, fault: &str) -> Result<Boundary, String> {
        let args: Vec<_> = line.split(' ').collect();
        let number = |i: usize| -> Result<i32, String> {
            args.get(i)
                .ok_or("missing argument")?
                .parse()
                .map_err(|_| "invalid integer".into())
        };
        let mut b = Boundary::default();
        match args[0] {
            "SET" if args.len() == 3 => {
                let (cell, value) = (number(1)?, number(2)?);
                if !(0..16).contains(&cell) || !(0..256).contains(&value) {
                    return Err("SET range".into());
                }
                self.mutate(cell as usize, value as u8, &mut b.mutations);
                b.packets.push(hex(&[2, cell as u8, value as u8]));
            }
            "MOVE" if args.len() == 2 => {
                self.x = self.x.wrapping_add(number(1)?);
                if fault == "reconverge" {
                    self.x = self.x.wrapping_add(match step {
                        1 => 1,
                        2 => -1,
                        _ => 0,
                    });
                }
                let mut packet = vec![1];
                packet.extend(self.x.to_be_bytes());
                b.packets.push(hex(&packet));
            }
            "SCHEDULE" if args.len() == 4 => {
                let (delay, cell, delta) = (number(1)?, number(2)?, number(3)?);
                if !(1..=1000).contains(&delay)
                    || !(0..16).contains(&cell)
                    || !(-255..=255).contains(&delta)
                {
                    return Err("SCHEDULE range".into());
                }
                self.serial += 1;
                self.scheduled.push(Tick(
                    self.time + delay as u64,
                    self.serial,
                    cell as usize,
                    delta,
                ));
                self.scheduled.sort();
            }
            "TICK" if args.len() == 2 => {
                let count = number(1)?;
                if !(1..=100).contains(&count) {
                    return Err("TICK range".into());
                }
                for _ in 0..count {
                    self.time += 1;
                    while self.scheduled.first().is_some_and(|t| t.0 <= self.time) {
                        let tick = self.scheduled.remove(0);
                        let value = (i32::from(self.blocks[tick.2]) + tick.3).rem_euclid(256) as u8;
                        self.mutate(tick.2, value, &mut b.mutations);
                        self.callbacks("DUE", &mut b.callbacks);
                    }
                }
            }
            "RNG" if args.len() == 2 => {
                let bound = number(1)?;
                if !(1..=256).contains(&bound) {
                    return Err("RNG range".into());
                }
                if fault == "rng" {
                    self.rng ^= 1;
                }
                self.rng = self.rng.wrapping_mul(1_664_525).wrapping_add(1_013_904_223);
                let value = (self.rng % bound as u32) as u8;
                self.mutate(0, value, &mut b.mutations);
                b.packets.push(hex(&[3, value]));
            }
            "DIV" if args.len() == 2 => {
                let denominator = number(1)?;
                if denominator == 0 {
                    b.error = true;
                } else if denominator < 0 {
                    return Err("DIV range".into());
                } else {
                    self.health /= denominator;
                }
            }
            "SAVE" if args.len() == 1 => {}
            _ => return Err("unknown command or argument count".into()),
        }
        if !b.error {
            self.callbacks(args[0], &mut b.callbacks);
        }
        if step == 1 {
            match fault {
                "callback-reorder" => b.callbacks.reverse(),
                "callback-missing" => {
                    b.callbacks.pop();
                }
                "callback-extra" => b.callbacks.push("A:EXTRA".into()),
                "packet" => b.packets.push("ff".into()),
                "mutation-missing" => {
                    b.mutations.pop();
                }
                "scheduled-missing" => {
                    self.scheduled.pop();
                }
                "controlled-error" => b.error = false,
                _ => {}
            }
        }
        b.save = hex(&self.save());
        if fault == "save" && step == 1 {
            b.save.push_str("00");
        }
        Ok(b)
    }
}

#[derive(Default)]
struct Boundary {
    mutations: Vec<[i32; 3]>,
    packets: Vec<String>,
    callbacks: Vec<String>,
    save: String,
    error: bool,
}

fn emit(
    world: &World,
    b: &Boundary,
    f: &[&str],
    step: usize,
    line: &str,
    fault: &str,
) -> Result<(), String> {
    let scheduled: Vec<_> = world
        .scheduled
        .iter()
        .map(|t| [t.0 as i64, t.1 as i64, t.2 as i64, i64::from(t.3)])
        .collect();
    let schema = if fault == "schema" {
        "WRONG"
    } else {
        "REPLAY_BOUNDARY_V1"
    };
    let session = if fault == "session" { "00" } else { f[2] };
    let number = if fault == "sequence" { step + 1 } else { step };
    println!("{{\"schema\":\"{schema}\",\"session\":\"{session}\",\"challenge\":\"{}\",\"trace_sha256\":\"{}\",\"step\":{number},\"command\":\"{line}\",\"outcome\":\"{}\",\"error\":{},\"world_mutations\":{:?},\"entity_state\":[{},{}],\"scheduled_ticks\":{:?},\"packet_outputs\":{:?},\"save_state\":\"{}\",\"callbacks\":{:?},\"rng_state\":{}}}",f[3],f[4],if b.error {"CONTROLLED_ERROR"} else {"OK"},if b.error {"\"DIVIDE_BY_ZERO\""} else {"null"},b.mutations,world.x,world.health,scheduled,b.packets,b.save,b.callbacks,world.rng);
    io::stdout().flush().map_err(|e| e.to_string())
}

fn run() -> Result<(), String> {
    let fault = std::env::args().nth(1).unwrap_or_else(|| "none".into());
    if ![
        "none",
        "reconverge",
        "callback-reorder",
        "callback-missing",
        "callback-extra",
        "packet",
        "save",
        "rng",
        "mutation-missing",
        "scheduled-missing",
        "controlled-error",
        "nonzero",
        "empty",
        "malformed",
        "schema",
        "session",
        "sequence",
        "initial-state",
    ]
    .contains(&fault.as_str())
    {
        return Err("unknown injected fault".into());
    }
    let mut lines = io::stdin().lock().lines();
    let first = lines
        .next()
        .ok_or("missing INIT")?
        .map_err(|e| e.to_string())?;
    let f: Vec<_> = first.split(' ').collect();
    if f.len() != 10 || f[0] != "INIT" || f[1] != "REPLAY_TOY_V1" || !["AB", "BA"].contains(&f[9]) {
        return Err("invalid INIT".into());
    }
    if [f[2], f[3], f[4]]
        .iter()
        .any(|s| s.is_empty() || !s.bytes().all(|b| b.is_ascii_hexdigit()))
    {
        return Err("invalid binding".into());
    }
    let blocks: Vec<u8> = f[8]
        .split(',')
        .map(str::parse)
        .collect::<Result<_, _>>()
        .map_err(|_| "blocks")?;
    let mut world = World {
        blocks: blocks.try_into().map_err(|_| "16 cells required")?,
        x: f[6].parse().map_err(|_| "x")?,
        health: f[7].parse().map_err(|_| "health")?,
        time: 0,
        serial: 0,
        rng: f[5].parse().map_err(|_| "seed")?,
        scheduled: vec![],
        reverse: f[9] == "BA",
    };
    if world.health < 0 {
        return Err("health range".into());
    }
    if fault == "initial-state" {
        world.blocks[15] ^= 1;
    }
    emit(
        &world,
        &Boundary {
            save: hex(&world.save()),
            ..Boundary::default()
        },
        &f,
        0,
        "INIT",
        "none",
    )?;
    for (index, line) in lines.enumerate() {
        let step = index + 1;
        let line = line.map_err(|e| e.to_string())?;
        if step == 1 && fault == "nonzero" {
            return Err("controlled adapter failure".into());
        }
        if step == 1 && fault == "empty" {
            return Ok(());
        }
        if step == 1 && fault == "malformed" {
            println!("{{broken");
            io::stdout().flush().map_err(|e| e.to_string())?;
            continue;
        }
        let b = world.step(&line, step, &fault)?;
        emit(&world, &b, &f, step, &line, &fault)?;
    }
    Ok(())
}

fn main() {
    if let Err(error) = run() {
        eprintln!("{error}");
        std::process::exit(2);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn world() -> World {
        World {
            blocks: [0; 16],
            x: 0,
            health: 20,
            time: 0,
            serial: 0,
            rng: 1,
            scheduled: vec![],
            reverse: false,
        }
    }
    #[test]
    fn rng_golden() {
        let mut w = world();
        let b = w.step("RNG 256", 1, "none").unwrap();
        assert_eq!(w.rng, 1_015_568_748);
        assert_eq!(b.mutations, vec![[0, 0, 108]]);
    }
    #[test]
    fn stable_schedule_order() {
        let mut w = world();
        w.step("SCHEDULE 1 3 255", 1, "none").unwrap();
        w.step("SCHEDULE 1 3 2", 2, "none").unwrap();
        assert_eq!(
            w.step("TICK 1", 3, "none").unwrap().mutations,
            vec![[3, 0, 255], [3, 255, 1]]
        );
    }
    #[test]
    fn controlled_error_leaves_state() {
        let mut w = world();
        let before = w.save();
        let b = w.step("DIV 0", 1, "none").unwrap();
        assert!(b.error);
        assert!(b.callbacks.is_empty());
        assert_eq!(before, w.save());
    }
    #[test]
    fn arithmetic_wraps_explicitly() {
        let mut w = world();
        w.x = i32::MAX;
        w.step("MOVE 1", 1, "none").unwrap();
        assert_eq!(w.x, i32::MIN);
    }
    #[test]
    fn invalid_command_fails() {
        assert!(world().step("SET 16 0", 1, "none").is_err());
    }
}
