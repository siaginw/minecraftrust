import subprocess
import time
import os
import re
import json
import numpy as np

PROFDATA_BIN = r"C:\Users\Siagi\.rustup\toolchains\stable-x86_64-pc-windows-msvc\lib\rustlib\x86_64-pc-windows-msvc\bin\llvm-profdata.exe"

OPERATIONS = [
    "Chunk Seed (from_transport 8 sec)",
    "State Read (get_block_state AtomicU16)",
    "State Write (Authoritative set_block_state)",
    "Light Read (AtomicU32 block_light)",
    "Light Write (AtomicU32 CAS block_light)",
    "Biome Read (get_biome [u8; 256])",
    "Biome Write (set_biome)",
    "Height Read (get_height [u16; 256])",
    "Height Recompute (Downward Scan)",
    "Packet Cold Encode (populate wire cache)",
    "Packet Static Encode (Wire Cache Hit)",
    "Packet Single Section Dirty",
    "Registry Lookup (ChunkHandle -> Arc)",
    "Neighbor Lookup: 3x3 Neighborhood (9 Chunks)",
    "Neighbor Lookup: 5x5 Neighborhood (25 Chunks)",
    "Future-Consumer A: Collision-like AABB (27 blocks)",
    "Future-Consumer B: Lighting-like 6-Neighbor Query",
    "Future-Consumer C: Pathfinding-like Walk (32 steps)",
    "Future-Consumer D: Storage-like Full Scan (65k states)",
    "Future-Consumer E: Worldgen Bulk Section Fill (4096 writes)",
]

def run_single_bench(extra_env=None):
    env = os.environ.copy()
    if extra_env:
        env.update(extra_env)
    cmd = ["cargo", "test", "-p", "native-chunk", "--test", "cost_model_bench", "--release", "--", "--nocapture"]
    proc = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, env=env)
    if proc.returncode != 0:
        print("ERROR running benchmark:")
        print(proc.stderr)
        return None
    
    # Parse table output
    metrics = {}
    pattern = re.compile(r"^\|\s*(.*?)\s*\|\s*([\d\.]+)\s*\|\s*([\d\.]+)\s*\|\s*([\d\.]+)\s*\|\s*([\d\.]+)\s*\|\s*([\d\.]+\s*M?ops/s)\s*\|$")
    for line in proc.stdout.splitlines():
        line = line.strip()
        m = pattern.match(line)
        if m:
            op_name = m.group(1).strip()
            metrics[op_name] = {
                "p50": float(m.group(2)),
                "p95": float(m.group(3)),
                "p99": float(m.group(4)),
                "avg": float(m.group(5)),
            }
    return metrics

def run_suite(lto_setting, cgu_setting, extra_rustflags="", runs=10, label=""):
    print(f"\n==================================================================")
    print(f"BENCHMARKING: {label} (LTO={lto_setting}, CGU={cgu_setting}, FLAGS='{extra_rustflags}', RUNS={runs})")
    print(f"==================================================================")
    
    with open("Cargo.toml", "r", encoding="utf-8") as f:
        orig_cargo = f.read()
    
    new_prof = f'''[profile.release]
lto = {lto_setting}
codegen-units = {cgu_setting}
'''
    modified_cargo = re.sub(r'\[profile\.release\][\s\S]*$', new_prof, orig_cargo)
    with open("Cargo.toml", "w", encoding="utf-8") as f:
        f.write(modified_cargo)
        
    try:
        env = {}
        if extra_rustflags:
            env["RUSTFLAGS"] = extra_rustflags
            
        # Build first
        print(f"[{label}] Building release binary...")
        t0 = time.time()
        build_cmd = ["cargo", "test", "-p", "native-chunk", "--test", "cost_model_bench", "--release", "--no-run"]
        b_env = os.environ.copy()
        b_env.update(env)
        subprocess.run(build_cmd, check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=b_env)
        print(f"[{label}] Build completed in {time.time()-t0:.2f}s")
        
        all_runs = []
        for r in range(runs):
            m = run_single_bench(env)
            if m:
                all_runs.append(m)
                print(f"  Run {r+1:2d}/{runs}: Static={m.get('Packet Static Encode (Wire Cache Hit)', {}).get('avg', 0):6.1f} ns, "
                      f"Dirty={m.get('Packet Single Section Dirty', {}).get('avg', 0):7.1f} ns, "
                      f"StateRead={m.get('State Read (get_block_state AtomicU16)', {}).get('avg', 0):5.1f} ns, "
                      f"Scan={m.get('Future-Consumer D: Storage-like Full Scan (65k states)', {}).get('avg', 0):7.1f} ns")
                      
        # Aggregate statistics
        stats = {}
        for op in OPERATIONS:
            avgs = [run[op]["avg"] for run in all_runs if op in run]
            p50s = [run[op]["p50"] for run in all_runs if op in run]
            if avgs:
                stats[op] = {
                    "mean_ns": float(np.mean(avgs)),
                    "stddev_ns": float(np.std(avgs)),
                    "median_ns": float(np.median(avgs)),
                    "p95_ns": float(np.percentile(avgs, 95)),
                    "p50_typical_ns": float(np.median(p50s)),
                }
        return stats
    finally:
        with open("Cargo.toml", "w", encoding="utf-8") as f:
            f.write(orig_cargo)

def run_pgo_pipeline(runs=10):
    print(f"\n==================================================================")
    print(f"BENCHMARKING: PGO PIPELINE (Profile-Guided Optimization, RUNS={runs})")
    print(f"==================================================================")
    pgo_dir = os.path.abspath(os.path.join("target", "pgo-data"))
    if os.path.exists(pgo_dir):
        import shutil
        shutil.rmtree(pgo_dir)
    os.makedirs(pgo_dir, exist_ok=True)
    
    # Step 1: Instrument build
    print("[PGO] 1. Compiling instrumented binary...")
    inst_flags = f"-Cprofile-generate={pgo_dir}"
    env1 = os.environ.copy()
    env1["RUSTFLAGS"] = inst_flags
    subprocess.run(["cargo", "test", "-p", "native-chunk", "--test", "cost_model_bench", "--release", "--no-run"],
                   check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=env1)
                   
    # Step 2: Training run
    print("[PGO] 2. Running representative training workload...")
    for _ in range(5):
        subprocess.run(["cargo", "test", "-p", "native-chunk", "--test", "cost_model_bench", "--release", "--", "--nocapture"],
                       check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=env1)
                       
    # Step 3: Merge profdata
    print("[PGO] 3. Merging profdata profiles...")
    merged_prof = os.path.join(pgo_dir, "merged.profdata")
    subprocess.run([PROFDATA_BIN, "merge", "-o", merged_prof, pgo_dir], check=True)
    print(f"[PGO] Created {merged_prof} ({os.path.getsize(merged_prof)} bytes)")
    
    # Step 4: Optimized PGO build and bench
    print("[PGO] 4. Rebuilding with profile-use and benchmarking...")
    use_flags = f"-Cprofile-use={merged_prof}"
    env2 = os.environ.copy()
    env2["RUSTFLAGS"] = use_flags
    subprocess.run(["cargo", "test", "-p", "native-chunk", "--test", "cost_model_bench", "--release", "--no-run"],
                   check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=env2)
                   
    all_runs = []
    for r in range(runs):
        m = run_single_bench(env2)
        if m:
            all_runs.append(m)
            print(f"  PGO Run {r+1:2d}/{runs}: Static={m.get('Packet Static Encode (Wire Cache Hit)', {}).get('avg', 0):6.1f} ns, "
                  f"Dirty={m.get('Packet Single Section Dirty', {}).get('avg', 0):7.1f} ns, "
                  f"StateRead={m.get('State Read (get_block_state AtomicU16)', {}).get('avg', 0):5.1f} ns, "
                  f"Scan={m.get('Future-Consumer D: Storage-like Full Scan (65k states)', {}).get('avg', 0):7.1f} ns")
                  
    stats = {}
    for op in OPERATIONS:
        avgs = [run[op]["avg"] for run in all_runs if op in run]
        p50s = [run[op]["p50"] for run in all_runs if op in run]
        if avgs:
            stats[op] = {
                "mean_ns": float(np.mean(avgs)),
                "stddev_ns": float(np.std(avgs)),
                "median_ns": float(np.median(avgs)),
                "p95_ns": float(np.percentile(avgs, 95)),
                "p50_typical_ns": float(np.median(p50s)),
            }
    return stats

if __name__ == "__main__":
    results = {}
    
    # 1. Fat LTO / CGU 1 (Current baseline)
    results["fat_lto_cgu1"] = run_suite('"fat"', 1, runs=10, label="Fat LTO / CGU 1")
    
    # 2. Thin LTO / CGU 1
    results["thin_lto_cgu1"] = run_suite('"thin"', 1, runs=10, label="Thin LTO / CGU 1")
    
    # 3. No LTO / CGU 16
    results["no_lto_cgu16"] = run_suite('false', 16, runs=10, label="No LTO / CGU 16")
    
    # 4. Target-CPU=native (Local max-performance bench)
    results["target_cpu_native"] = run_suite('"fat"', 1, extra_rustflags="-C target-cpu=native", runs=10, label="Target-CPU=Native / Fat LTO")
    
    # 5. Real PGO
    results["pgo_fat_lto"] = run_pgo_pipeline(runs=10)
    
    out_file = os.path.join("target", "compiler_matrix_rigorous.json")
    with open(out_file, "w") as f:
        json.dump(results, f, indent=2)
    print(f"\n[DONE] Successfully wrote complete statistical compiler matrix to {out_file}")
