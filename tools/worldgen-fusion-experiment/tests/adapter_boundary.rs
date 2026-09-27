use mc_1_12_x::{
    mc_1_12_2::Mc1_12_2, world_format::LegacyStoredState, AdapterError, LegacyWorldAdapter,
    StateMapping,
};
use rustcraft_core::{
    DenseRuntimeStateId, RegistryEpoch, SemanticStateKey, StateRegistry, WireStateId,
};

#[test]
fn wide_dense_id_requires_explicit_epoch_bound_version_mapping() {
    let epoch = RegistryEpoch::new(100, 1).unwrap();
    let keys: Vec<_> = (0..=70_000)
        .map(|i| SemanticStateKey::simple(&format!("fixture:state_{i}")).unwrap())
        .collect();
    let registry = StateRegistry::new(epoch, keys.clone()).unwrap();
    let adapter = LegacyWorldAdapter::<Mc1_12_2>::bind(
        &registry,
        keys.into_iter()
            .map(|key| StateMapping {
                semantic: key,
                wire: WireStateId(3),
                persistence: LegacyStoredState::new(42, 7).unwrap(),
            })
            .collect(),
    )
    .unwrap();
    let dense = [DenseRuntimeStateId(70_000)];
    let job = adapter.admit_job(epoch, &dense).unwrap();
    let mut wire = [WireStateId(0)];
    job.write_wire(&mut wire).unwrap();
    assert_eq!(wire, [WireStateId(3)]);
    assert_ne!(wire[0].0, u64::from(70_000u32 as u16));
    let mut persisted = [LegacyStoredState::new(0, 0).unwrap()];
    job.write_persistence(&mut persisted).unwrap();
    assert_eq!(persisted, [LegacyStoredState::new(42, 7).unwrap()]);
    assert!(matches!(
        adapter.admit_job(epoch.checked_next().unwrap(), &dense),
        Err(AdapterError::EpochMismatch)
    ));
    assert!(matches!(
        adapter.admit_job(epoch, &[DenseRuntimeStateId(0xf0000001)]),
        Err(AdapterError::UnknownRuntimeState)
    ));
}
