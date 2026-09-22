# Benchmarking and Regression System

## Required Benchmark Levels

### Microbenchmarks
Use for:
- compression
- NBT
- varints
- packet codecs
- region file parsing
- palette operations
- spatial queries

### Component Benchmarks
Use for:
- chunk load/save
- lighting
- pathfinding
- entity lookup
- world persistence

### Full Server Benchmarks
Use:
- deterministic worlds
- scripted clients/bots
- recorded workloads
- large modpack saves

## Metrics

Always capture:
- average MSPT
- p50 MSPT
- p95 MSPT
- p99 MSPT
- max MSPT
- TPS
- CPU utilization
- per-thread utilization
- allocation rate
- heap size
- native memory
- GC count/time
- context switches where practical
- disk reads/writes
- packet throughput
- FFI crossings per tick
- bytes copied across FFI per tick

## Performance Gate

A migration should define expected outcomes, for example:

```yaml
correctness:
  parity_required: true

performance:
  p95_mspt_regression_max_percent: 2
  ffi_calls_per_tick_max: 100
  native_copy_bytes_per_tick_max: 1048576
```

Numbers are subsystem-specific and should be set from evidence.

## Regression Workflow

If performance worsens:

```text
regression
   |
   v
capture profile
   |
   v
find cause
   |
   +--> FFI?
   +--> copying?
   +--> allocation?
   +--> lock?
   +--> algorithm?
   +--> cache?
   +--> I/O?
   |
   v
design fix
   |
   v
retest correctness
   |
   v
rebenchmark
   |
   +--> improved => keep
   |
   +--> not improved => try alternate design
   |
   +--> no viable design => revert and document
```
