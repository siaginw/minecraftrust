import subprocess
import time
import os
import glob
import shutil

server_dir = r"D:\minecraftrust\third_party_reference\forge\server"
java_bin = r"C:\Program Files\Eclipse Adoptium\jdk-8.0.504.1-hotspot\bin\java.exe"
bench_dir = r"D:\minecraftrust\benchmarks\baseline"
jfr_file = os.path.join(bench_dir, "baseline_idle.jfr")
gc_log = os.path.join(bench_dir, "gc.log")

cmd = [
    java_bin,
    "-Xms1G", "-Xmx1G",
    "-XX:+FlightRecorder",
    f"-XX:StartFlightRecording=duration=60s,filename={jfr_file}",
    "-XX:+PrintGCDetails",
    "-XX:+PrintGCDateStamps",
    f"-Xloggc:{gc_log}",
    "-jar", "forge-1.12.2-14.23.5.2860.jar",
    "nogui"
]

print("Launching Forge server with JFR and GC logging...")
proc = subprocess.Popen(
    cmd,
    cwd=server_dir,
    stdin=subprocess.PIPE,
    stdout=subprocess.PIPE,
    stderr=subprocess.STDOUT,
    text=True,
    bufsize=1
)

startup_done = False
start_time = time.time()
while True:
    line = proc.stdout.readline()
    if not line:
        break
    print("SERVER:", line.strip())
    if "Done (" in line:
        startup_done = True
        break
    if time.time() - start_time > 60:
        print("Timeout waiting for server startup!")
        break

if startup_done:
    print("Server booted successfully. Waiting 5s for JIT warmup...")
    time.sleep(5)
    print("Sending 'debug start'...")
    proc.stdin.write("debug start\n")
    proc.stdin.flush()
    
    # Read output until debug started
    time.sleep(1)
    print("Profiling for 30 seconds idle...")
    time.sleep(30)
    
    print("Sending 'debug stop'...")
    proc.stdin.write("debug stop\n")
    proc.stdin.flush()
    time.sleep(3)
    
    print("Sending 'stop'...")
    proc.stdin.write("stop\n")
    proc.stdin.flush()

    # Wait for process exit
    proc.wait(timeout=30)
    print("Server stopped with exit code:", proc.returncode)

# Check for debug files
debug_dir = os.path.join(server_dir, "debug")
debug_files = glob.glob(os.path.join(debug_dir, "profile-results-*.txt"))
print(f"Found {len(debug_files)} profile results in {debug_dir}")
for f in debug_files:
    dest = os.path.join(bench_dir, os.path.basename(f))
    shutil.copy2(f, dest)
    print(f"Copied {f} -> {dest}")
