import subprocess
import re
from collections import Counter

cmd = [r'C:\Program Files\Eclipse Adoptium\jdk-8.0.504.1-hotspot\bin\jfr.exe', 'print', '--events', 'jdk.ExecutionSample', r'C:\rustcraft\target\authority-smoke\targetC\server-profile.jfr']
proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, encoding='utf-8', errors='ignore')

threads = Counter()
top_methods = Counter()
rustcraft_stacks = Counter()
chunk_stacks = Counter()
netty_stacks = Counter()
minecraft_stacks = Counter()

current_thread = None
current_stack = []

for line in proc.stdout:
    line = line.rstrip()
    if 'sampledThread =' in line:
        m = re.search(r'sampledThread = "([^"]+)"', line)
        if m:
            current_thread = m.group(1)
            threads[current_thread] += 1
    elif 'line:' in line or ('(' in line and ')' in line and '.' in line):
        clean = line.strip()
        current_stack.append(clean)
    elif line.startswith('}') and current_thread:
        if current_stack:
            top_frame = current_stack[0]
            top_methods[top_frame] += 1
            for f in current_stack:
                fl = f.lower()
                if 'rustcraft' in fl:
                    rustcraft_stacks[f] += 1
                if 'chunk' in fl or 'storage' in fl or 'extendedblockstorage' in fl:
                    chunk_stacks[f] += 1
                if 'netty' in fl:
                    netty_stacks[f] += 1
                if 'net.minecraft' in fl:
                    minecraft_stacks[f] += 1
        current_thread = None
        current_stack = []

proc.wait()

print('=== TOTAL EXECUTION SAMPLES ===')
print(sum(threads.values()))

print('\n=== TOP SAMPLED THREADS ===')
for t, count in threads.most_common(10):
    print(f'{count:5d}  {t}')

print('\n=== TOP 20 STACK TOP METHODS ===')
for m, count in top_methods.most_common(20):
    print(f'{count:5d}  {m}')

print('\n=== TOP RUSTCRAFT FRAMES ===')
for f, count in rustcraft_stacks.most_common(15):
    print(f'{count:5d}  {f}')

print('\n=== TOP CHUNK/STORAGE FRAMES ===')
for f, count in chunk_stacks.most_common(15):
    print(f'{count:5d}  {f}')

print('\n=== TOP NETTY FRAMES ===')
for f, count in netty_stacks.most_common(15):
    print(f'{count:5d}  {f}')

print('\n=== TOP MINECRAFT SERVER FRAMES ===')
for f, count in minecraft_stacks.most_common(20):
    print(f'{count:5d}  {f}')
