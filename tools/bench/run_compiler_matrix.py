import subprocess
import time
import os
import re

def test_lto_config(lto_val, cgu_val, label):
    with open('Cargo.toml', 'r', encoding='utf-8') as f:
        orig = f.read()
    
    new_prof = f'''[profile.release]
lto = {lto_val}
codegen-units = {cgu_val}
'''
    modified = re.sub(r'\[profile\.release\][\s\S]*$', new_prof, orig)
    with open('Cargo.toml', 'w', encoding='utf-8') as f:
        f.write(modified)
        
    try:
        t0 = time.time()
        cmd = ['cargo', 'test', '-p', 'native-chunk', '--test', 'cost_model_bench', '--release', '--', '--nocapture']
        proc = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        dt = time.time() - t0
        print(f'=== {label} (Compile + Bench: {dt:.2f}s) ===')
        for l in proc.stdout.splitlines():
            if any(k in l for k in ['Packet Cold', 'Packet Static', 'Packet Single', 'Future-Consumer D']):
                print('  ' + l.strip())
        print()
    finally:
        with open('Cargo.toml', 'w', encoding='utf-8') as f:
            f.write(orig)

if __name__ == '__main__':
    print('Testing ThinLTO vs CGU=16...')
    test_lto_config('"thin"', 1, 'ThinLTO (cgu=1)')
    test_lto_config('false', 16, 'No LTO (cgu=16)')
