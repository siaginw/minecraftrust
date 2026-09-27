use lighting_collision_experiment::{collision, light};

#[test]
fn strict_contact_signed_zero_and_duplicate_shape_order() {
    let shapes = [0., 0., 0., 1., 1., 1., 0., 0., 0., 1., 1., 1.];
    let queries = [0.25, 0.25, 0.25, 0.75, 0.75, 0.75, -0.0, 1., -1.];
    let linear = collision::run(&shapes, &queries, false).unwrap().0;
    let indexed = collision::run(&shapes, &queries, true).unwrap().0;
    assert_eq!(linear, indexed);
    assert_eq!(
        indexed,
        [
            1,
            (-0.0f64).to_bits() as i64,
            1f64.to_bits() as i64,
            (-1f64).to_bits() as i64,
            2,
            0,
            1
        ]
    );
    let a = collision::Aabb::parse(&shapes[..6]).unwrap();
    let b = collision::Aabb::parse(&[1., 0., 0., 2., 1., 1.]).unwrap();
    assert!(!a.intersects(b));
    assert_eq!(a.clip(b, 0, -1.).to_bits(), 0f64.to_bits());
}

#[test]
fn indexed_sweeps_include_touching_boundaries_oversized_and_negative_coordinates() {
    let shapes = [
        -1000., -1000., -1000., 1000., 1000., 1000., -8., 0., 0., -4., 1., 1., 0., 0., 0., 1., 1.,
        1.,
    ];
    for distance in [-64., -4., -0.0, 0., 4., 64.] {
        let q = [-4., 0., 0., -3., 1., 1., distance, 0., 0.];
        assert_eq!(
            collision::run(&shapes, &q, false).unwrap().0,
            collision::run(&shapes, &q, true).unwrap().0
        );
    }
}

#[test]
fn invalid_geometry_rejects_before_a_result() {
    for v in [f64::NAN, f64::INFINITY, -f64::INFINITY, 30_000_011.] {
        assert!(collision::run(&[v, 0., 0., 1., 1., 1.], &[], true).is_none());
    }
    assert!(collision::run(&[2., 0., 0., 1., 1., 1.], &[], false).is_none());
    assert!(collision::run(&[0.], &[], false).is_none());
    assert!(collision::run(&[], &[0.; 10], false).is_none());
    assert!(collision::run(&vec![0.; 4097 * 6], &[], true).is_none());
    assert_eq!(collision::run(&[], &[], true).unwrap().0, [0]);
}

#[test]
fn bucket_optimization_has_equal_results_and_fewer_exact_tests_for_sparse_scene() {
    let mut boxes = Vec::new();
    for i in 0..1000 {
        let x = (i % 100) as f64 * 4.;
        let y = (i / 100) as f64 * 4.;
        boxes.extend([x, y, 0., x + 1., y + 1., 1.]);
    }
    let q = [0.1, 0.1, 0.1, 0.9, 0.9, 0.9, 0., 0., 0.];
    let (a, base) = collision::run(&boxes, &q, false).unwrap();
    let (b, opt) = collision::run(&boxes, &q, true).unwrap();
    assert_eq!(a, b);
    assert!(opt.exact_tests < base.exact_tests / 10);
    assert!(opt.index_entries <= 64 * 1000);
}

#[test]
fn packed_frontier_matches_dense_for_add_remove_overlap_and_opacity_mutations() {
    let side = 18;
    let n = side * side * side;
    let center = 9 + side * (9 + side * 9);
    let mut e = vec![0; n];
    let mut opacity = vec![0; n];
    let mut previous = vec![0; n];
    for (i, value, op) in [
        (center, 15, 15),
        (center + 1, 14, 0),
        (center - 1, 0, 15),
        (center, 0, 0),
        (0, 15, 15),
        (center + 1, 0, 0),
        (0, 0, 15),
        (center - 1, 0, 0),
    ] {
        e[i] = value;
        opacity[i] = op;
        let base = light::run(side, &e, &opacity, &previous, &[i as i32], false).unwrap();
        let opt = light::run(side, &e, &opacity, &previous, &[i as i32], true).unwrap();
        assert_eq!(base.0, opt.0);
        assert!(opt.1.queue_peak <= n);
        previous = base.0;
    }
    assert!(previous.iter().all(|v| *v == 0));
}

#[test]
fn multiple_dirty_seeds_and_initial_batch_are_bounded() {
    let n = 8 * 8 * 8;
    let mut e = vec![0; n];
    let opacity = vec![0; n];
    e[0] = 15;
    e[n - 1] = 9;
    let dirty = (0..n as i32).collect::<Vec<_>>();
    let a = light::run(8, &e, &opacity, &vec![0; n], &dirty, false).unwrap();
    let b = light::run(8, &e, &opacity, &vec![0; n], &dirty, true).unwrap();
    assert_eq!(a.0, b.0);
    assert_eq!(b.1.queue_peak, n);
    assert_eq!(a.0[0], 15);
    assert_eq!(a.0[n - 1], 9);
}

#[test]
fn light_invalid_inputs_reject_and_retry_succeeds() {
    let good = [0; 8];
    let mut bad = good;
    bad[0] = 16;
    assert!(light::run(2, &bad, &good, &good, &[0], true).is_none());
    assert!(light::run(2, &good, &good, &good, &[-1], true).is_none());
    assert!(light::run(2, &good, &good, &good, &[8], true).is_none());
    assert!(light::run(33, &good, &good, &good, &[], true).is_none());
    assert!(light::run(2, &good[..7], &good, &good, &[], true).is_none());
    assert_eq!(
        light::run(2, &good, &good, &good, &[0], true).unwrap().0,
        good
    );
}
