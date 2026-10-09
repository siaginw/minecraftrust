//! A coarse session/job chooses a concrete adapter before the per-state loop.
//! Synthetic mappings only; not an engine or packet-authority integration.
use mc_1_12_x::{mc_1_12_1::Mc1_12_1, mc_1_12_2::Mc1_12_2, world_format::LegacyStoredState, *};
use rustcraft_core::*;

fn execute<V: Version>() -> Result<(), Box<dyn std::error::Error>> {
    let epoch = RegistryEpoch::new(1, 1)?;
    let air = SemanticStateKey::simple("minecraft:air")?;
    let registry = StateRegistry::new(epoch, vec![air.clone()])?;
    let adapter = LegacyWorldAdapter::<V>::bind(
        &registry,
        vec![StateMapping {
            semantic: air,
            wire: WireStateId(0),
            persistence: LegacyStoredState::new(0, 0)?,
        }],
    )?;
    let states = vec![DenseRuntimeStateId(0); 4096];
    let job = adapter.admit_job(epoch, &states)?;
    let mut wire = vec![WireStateId(u64::MAX); states.len()];
    let written = job.write_wire(&mut wire)?;
    if written != 4096 || wire.iter().any(|id| *id != WireStateId(0)) {
        return Err("unexpected fixture result".into());
    }
    println!(
        "PASS dispatch_once version={} protocol={} states={written}",
        V::METADATA.release,
        V::METADATA.protocol
    );
    Ok(())
}

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let protocol = std::env::args()
        .nth(1)
        .ok_or("expected protocol number")?
        .parse()?;
    match SelectedVersion::for_protocol(protocol)? {
        SelectedVersion::Mc1_12_1 => execute::<Mc1_12_1>(),
        SelectedVersion::Mc1_12_2 => execute::<Mc1_12_2>(),
    }
}
