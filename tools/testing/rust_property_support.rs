//! Dev-only property harness. Persist concrete minimized inputs, not just seeds.
use proptest::{
    strategy::Strategy,
    test_runner::{Config, TestCaseResult, TestError, TestRunner},
};
use std::path::PathBuf;

pub fn run(
    name: &str,
    strategy: impl Strategy<Value = Vec<u64>>,
    check: impl Fn(&[u64]) -> TestCaseResult,
) {
    let root = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../..");
    let persisted = root.join("target/rustcraft-tests/proptest");
    // Both reviewed fixed regressions and previously shrunk local failures are
    // replayed as exact numeric cases before generating new inputs.
    for directory in [
        root.join("tests/fixtures/issue1-properties"),
        persisted.clone(),
    ] {
        if !directory.exists() {
            continue;
        }
        let mut paths: Vec<_> = std::fs::read_dir(directory)
            .unwrap()
            .map(|e| e.unwrap().path())
            .collect();
        paths.sort();
        for path in paths {
            if path.extension().and_then(|s| s.to_str()) != Some("case") {
                continue;
            }
            let source = std::fs::read_to_string(&path).unwrap();
            let mut lines = source.lines();
            assert_eq!(lines.next(), Some("ISSUE1_PROPERTY_V1"));
            if lines.next() != Some(name) {
                continue;
            }
            let values: Vec<u64> = lines
                .next()
                .unwrap()
                .split(',')
                .map(|v| v.parse().unwrap())
                .collect();
            check(&values).unwrap_or_else(|e| panic!("fixed replay {}: {e}", path.display()));
        }
    }
    let cases = std::env::var("PROPTEST_CASES")
        .ok()
        .map(|v| v.parse::<u32>().expect("PROPTEST_CASES integer"))
        .unwrap_or(64);
    assert!(
        (1..=100_000).contains(&cases),
        "bounded property case count"
    );
    let mut runner = TestRunner::new(Config {
        cases,
        failure_persistence: None,
        max_shrink_iters: 10_000,
        ..Config::default()
    });
    if let Err(error) = runner.run(&strategy, |case| check(&case)) {
        if let TestError::Fail(_, values) = &error {
            std::fs::create_dir_all(&persisted).unwrap();
            let nonce = std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos();
            let path = persisted.join(format!("{name}-{nonce}.case"));
            let data = format!(
                "ISSUE1_PROPERTY_V1\n{name}\n{}\n",
                values
                    .iter()
                    .map(u64::to_string)
                    .collect::<Vec<_>>()
                    .join(",")
            );
            std::fs::write(&path, data).unwrap();
            eprintln!("Minimized concrete case persisted: {}", path.display());
        }
        panic!("{name}: {error}");
    }
}
