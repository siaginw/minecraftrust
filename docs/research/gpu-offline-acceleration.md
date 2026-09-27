# H22.5.C: bounded GPU/offline acceleration experiment

Decision: **PARK GPU work after this bounded proxy.** No GPU dependency or live
server path is integrated. The experiment establishes integer parity and exposes
setup/readback costs. It does not establish Minecraft worldgen, lighting,
pregeneration or map-index performance.

The Windows-only tool uses the existing Direct3D 11 hardware device and MSVC SDK.
It selects a hardware adapter, refuses software fallback, and installs no driver
or package. The recorded host selected an NVIDIA GeForce RTX 5060 Ti, driver
610.74, with 16 logical CPU processors. Background GPU utilization was present;
neither affinity nor exclusive machine access was controlled.

## Workload and validation

Each array contains 4,096, 65,536 or 1,048,576 unsigned 32-bit integers. Zero,
one or 64 repeated mixing rounds vary arithmetic intensity. The construction and
constants are credited to
[hash-prospector lowbias32](https://github.com/skeeto/hash-prospector/blob/396dbe235c94dfc2e9b559fc965bcfda8b6a122c/README.md),
with a seed addition between rounds. Exact Unlicense text and reuse scope are
retained in `tools/gpu-offline-experiment/PROVENANCE.json`. This is a synthetic
integer workload, not gameplay RNG or a Minecraft algorithm.

Three CPU baselines run the same input: a scalar loop, explicit AVX2, and a
persistent pool of up to eight workers using AVX2. Every output element must
equal the scalar reference. A separate Python arbitrary-precision implementation
with explicit u32 masking checks four fixed indices per sample. The GPU shader
uses unsigned integer operations only. A deliberately corrupted shader must
fail at index zero on its first sample, preserving input and every result.

Both final frozen-source campaigns passed all nine cases, each with one warmup
and nine measured repetitions (90 full-array comparisons per campaign). Both
detected the injected fault immediately. Source/tool hashes and original-tree
guards were unchanged. The targeted rerun is
`84e4f6fafdf44506a8340c60a67d7dd8`; the first final campaign is
`4393190159b3402ba0a86b3389d1fc50`.

## Measured boundaries and exploratory results

`gpu_kernel_ms` is the timestamp interval around dispatch. `gpu_whole_ms` starts
before input/parameter upload and ends after blocking readback, CPU memcpy and
unmap. `gpu_wrapper_ms` additionally includes collection of diagnostic timestamp
queries. Device/shader initialization, buffer creation and CPU worker creation
are separate measurements. Array bytes count logical payload only; the separate
16-byte parameter upload and unknown driver/internal traffic are not hidden in
that field. No PCIe traffic counter was collected.

The table uses medians from the targeted rerun, in milliseconds. CPU means the
best **tested** CPU median, not the fastest possible CPU implementation. Nine
samples and an occupied desktop do not qualify statistical tail latency.

| Elements | Rounds | Best tested CPU | GPU upload through readback | GPU wrapper | Largest observed GPU time |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 4,096 | 0 | 0.0003 | 0.0503 | 0.0514 | 0.3078 |
| 4,096 | 1 | 0.0006 | 0.0376 | 0.0382 | 0.2120 |
| 4,096 | 64 | 0.0526 | 0.0383 | 0.0388 | 0.0655 |
| 65,536 | 0 | 0.0044 | 0.0925 | 0.0932 | 0.3204 |
| 65,536 | 1 | 0.0084 | 0.9015 | 0.9032 | 2.0999 |
| 65,536 | 64 | 0.3625 | 0.1796 | 0.1810 | 1.9848 |
| 1,048,576 | 0 | 0.0655 | 0.7222 | 0.7235 | 1.7304 |
| 1,048,576 | 1 | 0.0906 | 0.6925 | 0.6940 | 2.3823 |
| 1,048,576 | 64 | 3.5277 | 0.8720 | 0.8739 | 4.0518 |

Cheap workloads lose. The largest heavy synthetic batch has about a 4.05 ratio
of CPU/GPU medians, but device/shader setup alone took 197.871 ms in that run.
Its observed maximum GPU time exceeded the CPU median. The small heavy case was
near unity in the other final run (0.0702/0.0671 ms), illustrating instability.
These observations support parking live integration, even where warm medians
favor the GPU.

The ratio-of-medians estimate of time outside dispatch is roughly 88–99% across
these cases. It combines driver work, synchronization, transfer, CPU copying and
measurement effects; it is **not** an isolated PCIe measurement. Further kernel
tuning would target a small portion of the observed boundary. The initial run
using only a scalar worker pool overstated the apparent advantage and is retained
as superseded evidence, never a current result.

CPU order is fixed scalar/SIMD/pool, caches are warm, and the pool is capped at
eight workers on a 16-logical-processor host. GPU-versus-CPU order alternates.
There is no whole-server CPU, allocation/RSS, p99, power or real offline pipeline
measurement, so this cannot satisfy a production performance integration gate.

## Candidate decisions and stop condition

Large pregeneration, selected worldgen kernels, bulk lighting and offline
map/index construction are all **PARK pending a qualified complete CPU workload**.
No additional GPU work is required for the migration program. A future proposal
would need a real integer kernel with independently established Java semantics,
enough repeated/offline work to amortize initialization and data movement, an
optimized CPU comparison, and measured whole-operation latency/resource benefit.
Floating-point parity, arbitrary callbacks and live tick latency are unqualified.

The probe caps arrays at 4 MiB each and GPU children at 45 seconds. Query polling
has its own two-second deadline. On timeout the harness retains partial logs and
attempts to terminate only its spawned process tree; this is not an operating
system sandbox. Build processes have a 60-second deadline. SDK headers/import
libraries, transitive developer-shell scripts, firmware and the complete graphics
driver are not fully hash-closed; the selected tools and driver version are
recorded. No server starts or original-worktree writes occur.

Reproduce with:

```powershell
python -B tools/gpu-offline-experiment/run.py --vs-root 'C:\Program Files\Microsoft Visual Studio\18\Community'
```

The machine report is `machine/architecture-hardening/h22-5-gpu-validation.json`.
Its 56 raw artifacts cover the initial missing-header compile failure, the
superseded scalar baseline, intermediate SIMD work, and both final campaigns.
No failed or superseded evidence is promoted or removed. API boundaries follow
the [Direct3D 11 compute documentation](https://learn.microsoft.com/en-us/windows/win32/direct3d11/direct3d-11-advanced-stages-cs-access)
and [functional specification](https://microsoft.github.io/DirectX-Specs/d3d/archive/D3D11_3_FunctionalSpec.htm).
