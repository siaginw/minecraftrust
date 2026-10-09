"""§16 helper: console setblock mutations against a RUNNING server, settled
on the region-metrics file. Extracted from run_bounded_authority_smoke so
the mutation workload can run inside the integrated composition session."""
import re
import time
from pathlib import Path

METRICS_KEYS = ("lightAuthJobs", "lightAuthCommitted")


def read_counter(metrics_path, key):
    try:
        text = Path(metrics_path).read_text(encoding="utf-8", errors="replace")
    except OSError:
        return 0
    val = 0
    for line in text.splitlines():
        k, _, v = line.partition("=")
        if k == key:
            try:
                val = int(v)
            except ValueError:
                pass
    return val


def find_probe_anchor_simple(text):
    m = None
    for m in re.finditer(
            r"AuthProbe\w*\[/?(?:[0-9.]+):[0-9]+\] logged in with entity"
            r" id \d+ at \((-?[0-9.]+), (-?[0-9.]+), (-?[0-9.]+)\)", text):
        pass
    if m is None:
        return None
    return (int(float(m.group(1))), int(float(m.group(2))),
            int(float(m.group(3))))


def run_light_mutations(process, metrics_path, out_dir, log_path):
    """Drive real setblock mutations via the server console; settle on the
    lightAuth counters. Returns a receipt dict."""
    receipt = {"passed": False, "steps": []}
    text = Path(log_path).read_text(encoding="utf-8", errors="replace")
    anchor = find_probe_anchor_simple(text)
    if anchor is None:
        anchor = (200, 64, 240)
    salt = int(time.time()) % 8
    ax = anchor[0] + salt * 5 - 17
    ay = anchor[1] + 3
    az = anchor[2]
    print(f"[light-mutations] anchor=({ax},{ay},{az})")

    def jobs():
        return read_counter(metrics_path, "lightAuthJobs")

    def committed():
        return read_counter(metrics_path, "lightAuthCommitted")

    def send(cmd):
        try:
            process.stdin.write((cmd + "\n").encode())
            process.stdin.flush()
        except (OSError, ValueError):
            pass

    base = jobs()
    steps = [
        ("torch_place", [f"setblock {ax} {ay} {az} minecraft:torch"]),
        ("torch_remove", [f"setblock {ax} {ay} {az} minecraft:air"]),
        ("glowstone_place", [f"setblock {ax+2} {ay} {az+2} minecraft:glowstone"]),
        ("glowstone_remove", [f"setblock {ax+2} {ay} {az+2} minecraft:air"]),
        ("two_source", [f"setblock {ax-2} {ay} {az-2} minecraft:torch",
                        f"setblock {ax+1} {ay} {az+1} minecraft:glowstone"]),
        ("cleanup", [f"setblock {ax-2} {ay} {az-2} minecraft:air",
                     f"setblock {ax+1} {ay} {az+1} minecraft:air"]),
    ]
    for name, cmds in steps:
        b = jobs()
        for c in cmds:
            send(c)
        time.sleep(2.5)
        a = jobs()
        receipt["steps"].append({"step": name, "before": b, "after": a,
                                 "delta": a - b})
        print(f"[light-mutations] {name}: jobs {b} -> {a} (delta {a-b})")
    receipt["passed"] = all(st["delta"] > 0 for st in receipt["steps"][:2])
    (Path(out_dir) / "light-mutations.json").write_text(
        __import__("json").dumps(receipt, indent=2, sort_keys=True) + "\n")
    print(f"[light-mutations] passed={receipt['passed']} "
          f"committed={read_counter(metrics_path, 'lightAuthCommitted')}")
    return receipt
