use rustcraft_core::DenseRuntimeStateId;
use section_packet_cache_experiment::*;

#[test]
fn complete_packet_matches_current_dense_for_all_layouts_and_sparse_masks() {
    for (cardinality, mask, dense) in [
        (1, 1, false),
        (8, 3, false),
        (64, 0x21, false),
        (1024, 1, false),
        (8, 1, true),
        (1024, 0xffff, false),
        (1, 0, false),
        (15, 1, false),
        (16, 1, false),
        (32, 1, false),
        (128, 1, false),
        (255, 1, false),
        (256, 1, false),
    ] {
        let world = World::fixture(cardinality, mask, dense);
        let mut legacy = world.legacy().unwrap();
        let a = encode(&world, MAX_BODY).unwrap();
        let b = encode_legacy(&world, &mut legacy, MAX_BODY).unwrap();
        assert_eq!(a.emitted_mask(), mask);
        assert_eq!(a.bytes(), b.bytes());
    }
}
#[test]
fn missing_capacity_unsupported_and_retry_never_return_successful_short_body() {
    let mut w = World::fixture(8, 0x3f, false);
    w.sections.remove(&5);
    assert!(matches!(encode(&w, MAX_BODY), Err(Error::MissingSection)));
    w.key.mask = 0x1f;
    assert!(encode(&w, MAX_BODY).is_ok());
    assert!(matches!(encode(&w, 1), Err(Error::Capacity)));
    assert!(encode(&w, MAX_BODY).is_ok());
    w.key.protocol = 338;
    assert!(matches!(encode(&w, MAX_BODY), Err(Error::Unsupported)));
}
#[test]
fn wide_runtime_ids_require_explicit_representable_mapping() {
    let mut w = World::fixture(8, 1, false);
    assert!(w.sections[&0].data.get(0).unwrap().0 > 65535);
    let original = encode(&w, MAX_BODY).unwrap();
    w.mapping.remove(&RUNTIME_BASE);
    assert!(matches!(encode(&w, MAX_BODY), Err(Error::MissingMapping)));
    w.mapping.insert(RUNTIME_BASE, 65535);
    assert!(matches!(encode(&w, MAX_BODY), Err(Error::WireRange)));
    w.mapping.insert(RUNTIME_BASE, 1);
    assert_eq!(encode(&w, MAX_BODY).unwrap().bytes(), original.bytes());
}
#[test]
fn every_cache_identity_dimension_prevents_alias_and_stale_publish() {
    let w = World::fixture(8, 1, false);
    let mut cache = Cache::new(MAX_BODY, MAX_BODY * 2);
    cache.publish(encode(&w, MAX_BODY).unwrap(), w.key).unwrap();
    let mut variants = vec![];
    macro_rules! change {($($field:ident),*)=>{$(let mut k=w.key;k.$field+=1;variants.push(k);)*};}
    change!(
        incarnation,
        lifecycle,
        owner,
        registry,
        states,
        light,
        biomes,
        protocol,
        dimension_rules,
        recipient_rules
    );
    let mut k = w.key;
    k.resource.session += 1;
    variants.push(k);
    let mut k = w.key;
    k.resource.world += 1;
    variants.push(k);
    let mut k = w.key;
    k.resource.x += 1;
    variants.push(k);
    let mut k = w.key;
    k.resource.z += 1;
    variants.push(k);
    let mut k = w.key;
    k.mask ^= 2;
    variants.push(k);
    let mut k = w.key;
    k.full = false;
    variants.push(k);
    let mut k = w.key;
    k.skylight = false;
    variants.push(k);
    for key in variants {
        assert!(cache.lookup(key).unwrap().is_none());
        assert!(matches!(
            cache.publish(encode(&w, MAX_BODY).unwrap(), key),
            Err((Error::Stale, _))
        ));
    }
}
#[test]
fn eviction_cannot_hide_slow_reader_retention_and_budget_blocks_new_adoption() {
    let mut w = World::fixture(8, 1, false);
    let len = encode(&w, MAX_BODY).unwrap().bytes().len();
    let mut cache = Cache::new(len, len * 2);
    let accounting = cache.accounting();
    let first = cache.publish(encode(&w, MAX_BODY).unwrap(), w.key).unwrap();
    w.mutate(0);
    cache.invalidate(w.key.resource);
    let second = cache.publish(encode(&w, MAX_BODY).unwrap(), w.key).unwrap();
    assert_eq!(cache.resident(), len);
    assert_eq!(accounting.live(), 2 * len);
    w.mutate(1);
    cache.invalidate(w.key.resource);
    let pending = encode(&w, MAX_BODY).unwrap();
    let (error, pending) = cache.publish(pending, w.key).err().unwrap();
    assert_eq!(error, Error::RetentionPressure);
    assert_eq!(accounting.live(), 2 * len);
    drop(first);
    let third = cache.publish(*pending, w.key).unwrap();
    assert_eq!(accounting.live(), 2 * len);
    drop(second);
    drop(third);
    drop(cache);
    assert_eq!(accounting.live(), 0);
}
#[test]
fn lru_and_invalidation_are_bounded_and_old_views_remain_immutable() {
    let mut w = World::fixture(8, 1, false);
    let first = encode(&w, MAX_BODY).unwrap();
    let original = first.bytes().to_vec();
    let len = first.bytes().len();
    let mut cache = Cache::new(len * 2, len * 4);
    let old = cache.publish(first, w.key).unwrap();
    let a = w.key;
    w.key.resource.x += 1;
    let b = w.key;
    cache.publish(encode(&w, MAX_BODY).unwrap(), b).unwrap();
    cache.lookup(a).unwrap();
    w.key.resource.x += 1;
    cache.publish(encode(&w, MAX_BODY).unwrap(), w.key).unwrap();
    assert!(cache.lookup(b).unwrap().is_none());
    assert!(cache.lookup(a).unwrap().is_some());
    assert_eq!(cache.len(), 2);
    cache.invalidate(a.resource);
    assert_eq!(old.bytes(), original);
    assert!(cache.lookup(a).unwrap().is_none());
}
#[test]
fn mutations_light_biomes_recipient_and_dimension_change_actual_body() {
    let mut w = World::fixture(64, 1, false);
    let initial = encode(&w, MAX_BODY).unwrap().bytes().to_vec();
    w.mutate(0);
    assert_ne!(encode(&w, MAX_BODY).unwrap().bytes(), initial);
    let a = encode(&w, MAX_BODY).unwrap().bytes().to_vec();
    w.sections.get_mut(&0).unwrap().block_light[0] ^= 1;
    w.key.light += 1;
    assert_ne!(encode(&w, MAX_BODY).unwrap().bytes(), a);
    let a = encode(&w, MAX_BODY).unwrap().bytes().to_vec();
    w.biomes[0] ^= 1;
    w.key.biomes += 1;
    assert_ne!(encode(&w, MAX_BODY).unwrap().bytes(), a);
    w.key.recipient_rules = 1;
    let masked = encode(&w, MAX_BODY).unwrap();
    let mut legacy = w.legacy().unwrap();
    assert_eq!(
        masked.bytes(),
        encode_legacy(&w, &mut legacy, MAX_BODY).unwrap().bytes()
    );
    w.key.skylight = false;
    w.key.dimension_rules = 0;
    assert_eq!(
        encode(&w, MAX_BODY).unwrap().bytes().len() + 2048,
        masked.bytes().len()
    );
}
#[test]
fn palette_history_does_not_change_packet_and_empty_selection_is_explicit() {
    let mut w = World::fixture(8, 1, false);
    let expected = encode(&w, MAX_BODY).unwrap().bytes().to_vec();
    let s = &mut w.sections.get_mut(&0).unwrap().data;
    let old = s.get(1).unwrap();
    s.set(1, DenseRuntimeStateId(RUNTIME_BASE + 7)).unwrap();
    s.set(1, old).unwrap();
    assert_eq!(encode(&w, MAX_BODY).unwrap().bytes(), expected);
    w.key.mask = 0;
    let empty = encode(&w, MAX_BODY).unwrap();
    assert_eq!(empty.emitted_mask(), 0);
    assert_eq!(empty.bytes().len(), 270);
}

#[test]
fn oversized_adoption_keeps_result_owned_and_noop_state_keeps_cached_body() {
    let mut w = World::fixture(1, 1, false);
    let encoded = encode(&w, MAX_BODY).unwrap();
    let length = encoded.bytes().len();
    let mut small = Cache::new(length - 1, MAX_BODY);
    let (error, pending) = small.publish(encoded, w.key).err().unwrap();
    assert_eq!(error, Error::Oversized);
    assert_eq!(small.len(), 0);
    assert_eq!(small.accounting().live(), 0);
    let mut cache = Cache::new(length, length);
    let packet = cache.publish(*pending, w.key).unwrap();
    let before = w.key;
    w.mutate(0);
    assert_eq!(w.key, before);
    assert!(std::sync::Arc::ptr_eq(
        &packet,
        &cache.lookup(w.key).unwrap().unwrap()
    ));
}

#[test]
fn registry_remap_requires_new_identity_and_rebuild_changes_body() {
    let mut w = World::fixture(8, 1, false);
    let mut cache = Cache::new(MAX_BODY, MAX_BODY * 2);
    let old = cache.publish(encode(&w, MAX_BODY).unwrap(), w.key).unwrap();
    w.mapping.insert(RUNTIME_BASE, 8191);
    w.key.registry += 1;
    assert!(cache.lookup(w.key).unwrap().is_none());
    let rebuilt = encode(&w, MAX_BODY).unwrap();
    assert_ne!(old.bytes(), rebuilt.bytes());
    let mut legacy = w.legacy().unwrap();
    assert_eq!(
        rebuilt.bytes(),
        encode_legacy(&w, &mut legacy, MAX_BODY).unwrap().bytes()
    );
}
