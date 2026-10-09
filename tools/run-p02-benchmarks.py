import subprocess
import time
import os
import glob
import shutil
import re
import threading
import yaml

JAVA_BIN = r"C:\Program Files\Eclipse Adoptium\jdk-8.0.504.1-hotspot\bin\java.exe"
FORGE_DIR = r"D:\minecraftrust\third_party_reference\forge\server"
VANILLA_DIR = r"D:\minecraftrust\third_party_reference\minecraft\server"
OUTPUT_DIR = r"D:\minecraftrust\benchmarks\baseline\p0-2"
os.makedirs(OUTPUT_DIR, exist_ok=True)

SPAWN_X = 157
SPAWN_Y = 64
SPAWN_Z = -410

def parse_profile_dump(filepath):
    res = {
        "time_span_ms": 0,
        "tick_span": 0,
        "tps": 20.0,
        "mean_mspt": 0.0,
        "sections": {}
    }
    with open(filepath, "r", encoding="utf-8") as f:
        lines = f.readlines()
        
    for line in lines:
        line_clean = line.strip()
        m_time = re.match(r"Time span: (\d+) ms", line_clean)
        if m_time:
            res["time_span_ms"] = int(m_time.group(1))
        m_tick = re.match(r"Tick span: (\d+) ticks", line_clean)
        if m_tick:
            res["tick_span"] = int(m_tick.group(1))
        m_tps = re.match(r"// This is approximately ([\d\.]+) ticks per second", line_clean)
        if m_tps:
            res["tps"] = float(m_tps.group(1))
            
        m_sec = re.match(r"\[(\d+)\]\s*(?:\|\s*)*([a-zA-Z0-9_:.-]+)\s*-\s*([\d\.]+)%/([\d\.]+)%", line_clean)
        if m_sec:
            depth = int(m_sec.group(1))
            name = m_sec.group(2)
            pct_parent = float(m_sec.group(3))
            pct_total = float(m_sec.group(4))
            res["sections"][name] = {
                "depth": depth,
                "pct_parent": pct_parent,
                "pct_total": pct_total
            }
            
    if res["tick_span"] > 0:
        res["mean_mspt"] = round(res["time_span_ms"] / res["tick_span"], 3)
    return res

def parse_gc_log(filepath):
    young_gcs = 0
    young_pause_total = 0.0
    full_gcs = 0
    full_pause_total = 0.0
    
    if not os.path.exists(filepath):
        return {"young_gcs": 0, "full_gcs": 0, "total_pause_ms": 0.0}
        
    with open(filepath, "r", encoding="utf-8") as f:
        for line in f:
            if "Full GC" in line:
                full_gcs += 1
                m = re.search(r"(\d+\.\d+) secs\]", line)
                if m:
                    full_pause_total += float(m.group(1)) * 1000.0
            elif "GC (" in line:
                young_gcs += 1
                m = re.search(r"(\d+\.\d+) secs\]", line)
                if m:
                    young_pause_total += float(m.group(1)) * 1000.0
                    
    return {
        "young_gcs": young_gcs,
        "young_pause_ms": round(young_pause_total, 2),
        "full_gcs": full_gcs,
        "full_pause_ms": round(full_pause_total, 2),
        "total_pause_ms": round(young_pause_total + full_pause_total, 2)
    }

def run_server_workload(name, server_type, server_dir, jar_path, setup_commands, duration_s=20):
    yaml_path = os.path.join(OUTPUT_DIR, f"{name}.yaml")
    if os.path.exists(yaml_path):
        print(f"Skipping {name}: already completed at {yaml_path}")
        with open(yaml_path, "r", encoding="utf-8") as f:
            return yaml.safe_load(f)

    print(f"\n========================================================")
    print(f"RUNNING WORKLOAD: {name} (Type: {server_type})")
    print(f"========================================================")
    
    debug_dir = os.path.join(server_dir, "debug")
    if os.path.exists(debug_dir):
        for f in glob.glob(os.path.join(debug_dir, "*.txt")):
            try:
                os.remove(f)
            except Exception:
                pass
            
    gc_log = os.path.join(OUTPUT_DIR, f"{name}_gc.log")
    if os.path.exists(gc_log):
        try:
            os.remove(gc_log)
        except Exception:
            pass
        
    jfr_file = os.path.join(OUTPUT_DIR, f"{name}.jfr")
    if os.path.exists(jfr_file):
        try:
            os.remove(jfr_file)
        except Exception:
            pass

    cmd = [
        JAVA_BIN,
        "-Xms1G", "-Xmx1G",
        "-XX:+FlightRecorder",
        f"-XX:StartFlightRecording=duration={duration_s + 20}s,filename={jfr_file}",
        "-XX:+PrintGCDetails",
        "-XX:+PrintGCDateStamps",
        f"-Xloggc:{gc_log}",
        "-jar", jar_path,
        "nogui"
    ]
    
    proc = subprocess.Popen(
        cmd,
        cwd=server_dir,
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        bufsize=1
    )
    
    # Continuously drain stdout in a background thread to prevent pipe blocking
    server_ready_event = threading.Event()
    
    def stdout_reader():
        for line in proc.stdout:
            # print("  [LOG]", line.strip())
            if "Done (" in line:
                print("  [SERVER READY]", line.strip())
                server_ready_event.set()
                
    t_reader = threading.Thread(target=stdout_reader, daemon=True)
    t_reader.start()
    
    print("Waiting for server boot...")
    if not server_ready_event.wait(timeout=60):
        print("ERROR: Server boot timed out or failed!")
        proc.kill()
        return None
        
    time.sleep(2)
    
    # Run setup commands
    if setup_commands:
        print(f"Executing {len(setup_commands)} setup commands at spawn ({SPAWN_X}, {SPAWN_Y}, {SPAWN_Z})...")
        for c in setup_commands:
            proc.stdin.write(c + "\n")
            proc.stdin.flush()
            time.sleep(0.05)
        time.sleep(2)
        
    # Start profiling
    print(f"Starting tick profiling (/debug start) for {duration_s}s...")
    proc.stdin.write("debug start\n")
    proc.stdin.flush()
    time.sleep(duration_s)
    
    print("Stopping tick profiling (/debug stop)...")
    proc.stdin.write("debug stop\n")
    proc.stdin.flush()
    time.sleep(2)
    
    # Stop server
    print("Stopping server...")
    proc.stdin.write("stop\n")
    proc.stdin.flush()
    
    try:
        proc.wait(timeout=30)
        print("Server stopped cleanly.")
    except subprocess.TimeoutExpired:
        print("WARNING: Server stop timed out, terminating...")
        proc.kill()
        proc.wait()
        
    # Find profile file
    files = glob.glob(os.path.join(debug_dir, "profile-results-*.txt"))
    if not files:
        print("ERROR: No profile results found in debug directory!")
        return None
        
    prof_file = sorted(files)[-1]
    dest_prof = os.path.join(OUTPUT_DIR, f"{name}_profile.txt")
    shutil.copy2(prof_file, dest_prof)
    
    parsed_prof = parse_profile_dump(dest_prof)
    gc_stats = parse_gc_log(gc_log)
    
    workload_summary = {
        "workload_name": name,
        "server_type": server_type,
        "duration_s": duration_s,
        "time_span_ms": parsed_prof["time_span_ms"],
        "tick_span": parsed_prof["tick_span"],
        "measured_tps": parsed_prof["tps"],
        "mean_mspt": parsed_prof["mean_mspt"],
        "gc_stats": gc_stats,
        "top_subsystems": {
            k: v["pct_total"] for k, v in sorted(
                parsed_prof["sections"].items(),
                key=lambda item: item[1]["pct_total"],
                reverse=True
            )[:15]
        },
        "artifacts": {
            "profile_dump": os.path.basename(dest_prof),
            "jfr_recording": os.path.basename(jfr_file),
            "gc_log": os.path.basename(gc_log)
        }
    }
    
    with open(yaml_path, "w", encoding="utf-8") as yf:
        yaml.dump(workload_summary, yf, default_flow_style=False, sort_keys=False)
        
    print(f"Completed {name}: TPS={parsed_prof['tps']}, MSPT={parsed_prof['mean_mspt']}ms, GCs={gc_stats['young_gcs']}")
    return workload_summary

def main():
    results = {}
    
    # 1. Vanilla Comparison (Idle)
    results["vanilla_idle"] = run_server_workload(
        name="vanilla_idle",
        server_type="vanilla_1.12.2",
        server_dir=VANILLA_DIR,
        jar_path=r"D:\minecraftrust\third_party_reference\minecraft\minecraft_server.1.12.2.jar",
        setup_commands=[],
        duration_s=20
    )
    
    # 2. Workload A: Forge Idle
    results["workload_a_idle"] = run_server_workload(
        name="workload_a_idle",
        server_type="forge_14.23.5.2860",
        server_dir=FORGE_DIR,
        jar_path=r"D:\minecraftrust\third_party_reference\forge\server\forge-1.12.2-14.23.5.2860.jar",
        setup_commands=[],
        duration_s=20
    )
    
    # 3. Workload B: Forge Loaded World (Keep spawn loaded, daytime clear)
    results["workload_b_loaded_world"] = run_server_workload(
        name="workload_b_loaded_world",
        server_type="forge_14.23.5.2860",
        server_dir=FORGE_DIR,
        jar_path=r"D:\minecraftrust\third_party_reference\forge\server\forge-1.12.2-14.23.5.2860.jar",
        setup_commands=["time set day", "weather clear 100000"],
        duration_s=20
    )
    
    # 4. Workload C: Forge Entity Pressure (60 active entities at spawn)
    entity_cmds = [f"summon cow {SPAWN_X} {SPAWN_Y + 1} {SPAWN_Z}" for _ in range(60)]
    results["workload_c_entities"] = run_server_workload(
        name="workload_c_entities",
        server_type="forge_14.23.5.2860",
        server_dir=FORGE_DIR,
        jar_path=r"D:\minecraftrust\third_party_reference\forge\server\forge-1.12.2-14.23.5.2860.jar",
        setup_commands=entity_cmds,
        duration_s=20
    )
    
    # 5. Workload D: Forge TileEntity Pressure (100 active hoppers + chests at spawn)
    tile_cmds = [
        f"fill {SPAWN_X-5} {SPAWN_Y} {SPAWN_Z-5} {SPAWN_X+4} {SPAWN_Y} {SPAWN_Z+4} hopper",
        f"fill {SPAWN_X-5} {SPAWN_Y+1} {SPAWN_Z-5} {SPAWN_X+4} {SPAWN_Y+1} {SPAWN_Z+4} chest"
    ]
    results["workload_d_tileentities"] = run_server_workload(
        name="workload_d_tileentities",
        server_type="forge_14.23.5.2860",
        server_dir=FORGE_DIR,
        jar_path=r"D:\minecraftrust\third_party_reference\forge\server\forge-1.12.2-14.23.5.2860.jar",
        setup_commands=tile_cmds,
        duration_s=20
    )
    
    # 6. Workload E: Forge Block / Scheduled-Tick Pressure (flowing water at spawn)
    block_cmds = [
        f"setblock {SPAWN_X} {SPAWN_Y+15} {SPAWN_Z} water",
        f"fill {SPAWN_X-5} {SPAWN_Y-1} {SPAWN_Z-5} {SPAWN_X+4} {SPAWN_Y-1} {SPAWN_Z+4} air"
    ]
    results["workload_e_block_ticks"] = run_server_workload(
        name="workload_e_block_ticks",
        server_type="forge_14.23.5.2860",
        server_dir=FORGE_DIR,
        jar_path=r"D:\minecraftrust\third_party_reference\forge\server\forge-1.12.2-14.23.5.2860.jar",
        setup_commands=block_cmds,
        duration_s=20
    )
    
    # 7. Workload F: Forge Mixed (30 cows + 50 hoppers + flowing water)
    mixed_cmds = (
        [f"summon pig {SPAWN_X} {SPAWN_Y+1} {SPAWN_Z}" for _ in range(30)] +
        [f"fill {SPAWN_X-3} {SPAWN_Y} {SPAWN_Z-3} {SPAWN_X+3} {SPAWN_Y} {SPAWN_Z+3} hopper"] +
        [f"setblock {SPAWN_X+5} {SPAWN_Y+10} {SPAWN_Z+5} water"]
    )
    results["workload_f_mixed"] = run_server_workload(
        name="workload_f_mixed",
        server_type="forge_14.23.5.2860",
        server_dir=FORGE_DIR,
        jar_path=r"D:\minecraftrust\third_party_reference\forge\server\forge-1.12.2-14.23.5.2860.jar",
        setup_commands=mixed_cmds,
        duration_s=20
    )
    
    # Save aggregate summary
    p02_summary = {
        "timestamp": "2026-09-17T18:50:00-05:00",
        "environment": {
            "jvm": "Temurin 1.8.0_504-b01",
            "heap": "1G",
            "gc": "ParallelGC"
        },
        "workloads": results
    }
    
    summary_file = os.path.join(OUTPUT_DIR, "p0_2_summary.yaml")
    with open(summary_file, "w", encoding="utf-8") as f:
        yaml.dump(p02_summary, f, default_flow_style=False, sort_keys=False)
        
    print(f"\nALL WORKLOADS COMPLETED! Summary written to {summary_file}")

if __name__ == "__main__":
    main()
