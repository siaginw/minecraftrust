# H17 bounded allocator experiment results

PASS for the isolated in-memory fixture pipeline. Production allocator selection remains unchanged.

36 integration tests, 6 lifetime compile-fail tests, 6 process-harness controls; 36 measured processes, 216 measured rows and 108 warmup rows. Every measured batch released all eight publications and had zero net requested-byte growth. All three backends retained 48 requested bytes after their first cross-thread warmup; attribution remains unknown and final workload-end totals retain it.

| Backend | Scratch | Delivery | Median batch ms | Observed max ms | Median private commit MiB | Requested MiB / batch |
|---|---|---|---:|---:|---:|---:|
| system | vec | same | 9.642 | 10.273 | 2.54 | 13.79 |
| system | vec | cross | 9.487 | 14.191 | 2.43 | 13.79 |
| system | arena | same | 9.556 | 10.939 | 2.57 | 14.39 |
| system | arena | cross | 9.657 | 11.430 | 2.47 | 14.39 |
| mimalloc | vec | same | 7.671 | 10.053 | 22.69 | 13.79 |
| mimalloc | vec | cross | 7.504 | 8.631 | 31.71 | 13.79 |
| mimalloc | arena | same | 7.596 | 9.046 | 22.72 | 14.39 |
| mimalloc | arena | cross | 7.862 | 11.322 | 53.75 | 14.39 |
| rpmalloc | vec | same | 7.545 | 10.924 | 11.32 | 13.79 |
| rpmalloc | vec | cross | 7.777 | 10.990 | 15.32 | 13.79 |
| rpmalloc | arena | same | 7.758 | 9.832 | 11.32 | 14.39 |
| rpmalloc | arena | cross | 7.715 | 10.236 | 15.33 | 14.39 |

Each batch contains eight publications. Eighteen measured samples per lane make both nearest-rank p95 and p99 equal the observed maximum. Private commit is sampled after each batch; working set, Job peak commit, and retained-view memory snapshots remain separate in raw rows. Pure allocator fragmentation is unknown.

Mimalloc and rpmalloc traded lower observed median time for higher process private commit. Bump scratch increased requested allocation bytes and did not consistently improve time. These observations support further workload-specific study, not a global allocator change. The fixture uses real retained section, native packet/compression and strict lossless NBT kernels, but excludes JNI, socket/disk I/O and server execution.

The final source/tool-bound receipt is archived alongside all final raw rows/logs, authored compressed outputs, and the failed development/final attempts. No historical failure was overwritten.
