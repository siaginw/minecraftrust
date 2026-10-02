import subprocess
import re
from collections import Counter

def classify_sample(top_frame: str, stack: list[str]) -> str:
    # 1. Direct classification of top frame
    c = classify_single_frame(top_frame)
    if c != 'other':
        return c
    
    # 2. If top frame is a generic JDK utility (e.g. IdentityHashMap, HashMap, ArrayList), attribute to immediate caller
    tl = top_frame.lower()
    if any(k in tl for k in ['hashmap', 'arraylist', 'abstractlist', 'string', 'comparator', 'collections']):
        for caller in stack[1:4]:
            cc = classify_single_frame(caller)
            if cc != 'other':
                return cc
    return 'other'

def classify_single_frame(frame: str) -> str:
    fl = frame.lower()
    if 'com.rustcraft' in fl:
        if 'nativechunk' in fl or 'section' in fl or 'palette' in fl or 'wire' in fl:
            return 'NativeChunk'
        return 'RustCraft bridge'
    elif 'io.netty' in fl:
        return 'Netty'
    elif 'compression' in fl or 'deflater' in fl or 'inflater' in fl or 'zip' in fl:
        return 'compression'
    elif 'anvil' in fl or 'nbt' in fl or 'region' in fl or 'chunkloader' in fl:
        return 'Anvil/NBT'
    elif 'worldgen' in fl or 'gen.structure' in fl or 'mapgen' in fl or 'biome' in fl or 'chunkgenerator' in fl:
        return 'worldgen'
    elif 'net.minecraftforge' in fl or 'fml' in fl:
        return 'Forge'
    elif any(mod in fl for mod in ['slimeknights', 'mantle', 'ic2', 'appeng', 'forestry', 'vazkii', 'crazypants', 
                                  'hellfirepvp', 'astralsorcery', 'actuallyadditions', 'cofh', 'thermal', 
                                  'draconicevolution', 'enderio', 'chisel', 'bdlib', 'biomesoplenty']):
        return 'mods'
    elif 'org.objectweb.asm' in fl or 'launchclassloader' in fl or 'classloader' in fl:
        return 'Classloading/ASM'
    elif 'gc' in fl or 'safepoint' in fl or 'jvm' in fl or 'java.lang.ref' in fl:
        return 'GC/JVM'
    else:
        return 'other'

def parse_jfr(jfr_path):
    cmd = [r'C:\Program Files\Eclipse Adoptium\jdk-8.0.504.1-hotspot\bin\jfr.exe', 'print', '--events', 'jdk.ExecutionSample', jfr_path]
    proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, encoding='utf-8', errors='ignore')

    samples = []
    current_time = None
    current_thread = None
    current_stack = []

    for line in proc.stdout:
        line = line.rstrip()
        if 'startTime =' in line:
            m = re.search(r'startTime = (\d+:\d+:\d+\.\d+)', line)
            if m:
                current_time = m.group(1)
        elif 'sampledThread =' in line:
            m = re.search(r'sampledThread = "([^"]+)"', line)
            if m:
                current_thread = m.group(1)
        elif 'line:' in line or ('(' in line and ')' in line and '.' in line):
            current_stack.append(line.strip())
        elif line.startswith('}') and current_thread:
            if current_stack:
                samples.append({
                    'time': current_time,
                    'thread': current_thread,
                    'top': current_stack[0],
                    'stack': current_stack
                })
            current_time = None
            current_thread = None
            current_stack = []

    proc.wait()
    return samples

def time_to_sec(t_str):
    h, m, s = t_str.split(':')
    return int(h) * 3600 + int(m) * 60 + float(s)

def analyze_window(samples, name, filter_fn):
    window_samples = [s for s in samples if filter_fn(s)]
    total = len(window_samples)
    if total == 0:
        print(f'=== {name} === (0 samples)')
        return {}

    exclusive = Counter()
    inclusive = Counter()

    for s in window_samples:
        top_cat = classify_sample(s['top'], s['stack'])
        exclusive[top_cat] += 1
        
        seen_cats = set()
        for f in s['stack']:
            cat = classify_single_frame(f)
            seen_cats.add(cat)
        for cat in seen_cats:
            inclusive[cat] += 1

    print(f'=== {name} === (Total samples: {total})')
    print('  Category                  Exclusive CPU % (Count)    Inclusive Stack % (Count)')
    print('  ----------------------------------------------------------------------------------')
    cats = ['RustCraft bridge', 'NativeChunk', 'Netty', 'compression', 'Anvil/NBT', 'worldgen', 'Forge', 'mods', 'Classloading/ASM', 'GC/JVM', 'other']
    # Sort by exclusive count descending
    sorted_cats = sorted(set(list(exclusive.keys()) + list(inclusive.keys())), key=lambda c: exclusive[c], reverse=True)
    
    for c in sorted_cats:
        ex_cnt = exclusive[c]
        ex_pct = (ex_cnt / total) * 100.0
        in_cnt = inclusive[c]
        in_pct = (in_cnt / total) * 100.0
        print(f'  {c:25s}  {ex_pct:6.2f}% ({ex_cnt:4d})          {in_pct:6.2f}% ({in_cnt:4d})')
    
    ex_sum = sum(exclusive.values())
    print(f'  SUM EXCLUSIVE: {ex_sum}/{total} ({ex_sum/total*100:.1f}%)\n')
    return exclusive

if __name__ == '__main__':
    samples = parse_jfr(r'C:\rustcraft\target\authority-smoke\targetC\server-profile.jfr')
    t0 = time_to_sec(samples[0]['time'])
    print(f'Loaded {len(samples)} execution samples.')

    # Define phase windows:
    # Done was at 21:57:19 (UTC 20:57:19).
    # t_boot = time_to_sec('20:57:19.000')
    # Probe login was at 21:58:00 (UTC 20:58:00).
    # Probe left at 21:58:24 (UTC 20:58:24).
    t_boot = time_to_sec('20:57:19.000')
    t_probe_in = time_to_sec('20:58:00.000')
    t_probe_out = time_to_sec('20:58:24.000')

    print('\n==================== 1. FULL RUN EXECUTION PROFILE ====================')
    analyze_window(samples, 'FULL RUN (Boot + Probe + Shutdown)', lambda s: True)

    print('==================== 2. STARTUP PHASE (Pre-boot -> Done) ====================')
    analyze_window(samples, 'STARTUP PHASE', lambda s: time_to_sec(s['time']) < t_boot)

    print('==================== 3. POST-BOOT IDLE SETTLE (Done -> Probe Login) ====================')
    analyze_window(samples, 'POST-BOOT IDLE SETTLE', lambda s: t_boot <= time_to_sec(s['time']) < t_probe_in)

    print('==================== 4. STEADY-STATE CHUNK STREAMING / PROBE WORKLOAD ====================')
    analyze_window(samples, 'CHUNK STREAMING & PROBE WORKLOAD', lambda s: t_probe_in <= time_to_sec(s['time']) <= t_probe_out)
