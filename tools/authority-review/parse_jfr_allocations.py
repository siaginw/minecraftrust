import subprocess
import re
from collections import Counter

cmd = [r'C:\Program Files\Eclipse Adoptium\jdk-8.0.504.1-hotspot\bin\jfr.exe', 'print', '--events', 'jdk.ObjectAllocationInNewTLAB,jdk.ObjectAllocationOutsideTLAB', r'C:\rustcraft\target\authority-smoke\targetC\server-profile.jfr']
proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, encoding='utf-8', errors='ignore')

alloc_classes = Counter()
alloc_bytes = Counter()
current_class = None
current_bytes = 0

for line in proc.stdout:
    line = line.strip()
    if line.startswith('objectClass ='):
        m = re.search(r'objectClass = ([^\s]+)', line)
        if m:
            current_class = m.group(1)
    elif line.startswith('allocationSize =') or line.startswith('tlabSize ='):
        m = re.search(r'= (\d+)', line)
        if m:
            current_bytes = int(m.group(1))
    elif line == '}' and current_class:
        alloc_classes[current_class] += 1
        alloc_bytes[current_class] += current_bytes
        current_class = None
        current_bytes = 0

proc.wait()

print('=== TOP ALLOCATING CLASSES BY COUNT ===')
for c, cnt in alloc_classes.most_common(20):
    mb = alloc_bytes[c] / (1024 * 1024)
    print(f'{cnt:6d} samples ({mb:8.2f} MB est)  {c}')

print('\n=== TOP ALLOCATING CLASSES BY ESTIMATED BYTES ===')
for c, b in sorted(alloc_bytes.items(), key=lambda x: x[1], reverse=True)[:20]:
    cnt = alloc_classes[c]
    mb = b / (1024 * 1024)
    print(f'{mb:8.2f} MB ({cnt:6d} samples)  {c}')
