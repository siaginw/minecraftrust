#!/usr/bin/env python3
"""M3J JFR analysis: phase-binned ServerThread profile + caller census.

Input on stdin: `jfr print --events jdk.ExecutionSample,
jdk.ObjectAllocationInNewTLAB,jdk.ObjectAllocationOutsideTLAB <jfr>`.
argv[1]: timeline file with 'LAUNCH <epoch> <HH:MM:SS>' plus
'<BOUNDARY> <HH:MM:SS>' lines (wall clock, same day).

jfr print startTime is a relative clock anchored at the first event;
sample wall = LAUNCH epoch + (startTime - first event startTime).
Phase buckets are labelled by the window FOLLOWING each boundary.
Presence counts samples whose stack contains the frame; self counts
samples where the frame is the leaf. They are reported separately and
never summed.
"""
import collections
import re
import sys

TL_PATH = sys.argv[1]

phases = []  # (boundary_name, wall_epoch)
launch_epoch = None
launch_clock = None
for raw in open(TL_PATH, encoding="utf-8"):
    parts = raw.split()
    if len(parts) >= 3 and parts[0] == "LAUNCH":
        launch_epoch, launch_clock = int(parts[1]), parts[2]
    elif len(parts) == 2:
        phases.append((parts[0], parts[1]))

def secs(clock):
    hh, mm, ss = clock.split(":")
    return int(hh) * 3600 + int(mm) * 60 + float(ss)

launch_clock_secs = secs(launch_clock) if launch_clock else 0.0

def wall_of_clock(clock):
    return launch_epoch + (secs(clock) - launch_clock_secs)

# bucket label = the window AFTER each boundary (BUILD window excluded
# from steady-state shares; relabelled for readability)
LABEL = {
    "BOOT_DONE": "SETTLE", "SETTLE_END": "BOTJOIN", "BOT_READY": "BUILD",
    "BUILD_START": "BUILD", "BUILD_END": "FACTORY_ACTIVE",
    "FACTORY_END": "ENTITY_ACTIVE", "ENTITY_END": "RECOVERY",
    "RECOVERY_END": "TAIL", "STOP": "AFTER_STOP",
}

def phase_of(wall):
    cur = "BOOT"
    for name, clock in phases:
        if wall >= wall_of_clock(clock):
            cur = LABEL.get(name, name)
        else:
            break
    return cur

PHASE_ORDER = ["BOOT", "SETTLE", "BOTJOIN", "BUILD", "FACTORY_ACTIVE",
               "ENTITY_ACTIVE", "RECOVERY", "TAIL", "AFTER_STOP"]

TARGETS = {
    "getBlockState World.func_180495_p": r"World\.func_180495_p",
    "getBlockState Chunk.func_186032_a": r"Chunk\.func_186032_a",
    "getCollisionBoxes World.func_184144_a": r"World\.func_184144_a",
    "collision-scan World.func_191504_a": r"World\.func_191504_a",
    "entity-move Entity.func_70091_d": r"Entity\.func_70091_d",
    "entity-tick EntityLiving.func_70636_d": r"EntityLiving(?:Base)?\.func_70636_d",
    "entity-query func_72839_b": r"World\.func_72839_b",
    "entity-query func_175644_a": r"World\.func_175644_a",
    "TE-tick TileEntity.func_145845_h": r"TileEntity\.func_145845_h",
    "TE-class-list World.func_147448_a": r"World\.func_147448_a",
    "pathfinding PathFinder": r"PathFinder\.func_18633[0-9]_a",
    "pathnav PathNavigate": r"PathNavigate",
    "lighting getLightFor func_180500_c": r"func_180500_c",
    "lighting checkLight func_175669_m": r"func_175669_m|func_175710_j",
    "worldgen ChunkGeneratorOverworld": r"ChunkGeneratorOverworld",
    "worldgen NoiseGenerator": r"NoiseGenerator",
    "worldgen populate func_185931_b": r"func_185931_b",
    "save AnvilChunkLoader saveChunk": r"AnvilChunkLoader\.func_75816_a",
    "save writeChunkToNBT func_75820_a": r"AnvilChunkLoader\.func_75820_a",
    "save NEID hooks": r"fewizz\.neid",
    "hopper TileEntityHopper": r"TileEntityHopper",
    "furnace TileEntityFurnace": r"TileEntityFurnace",
    "inventory Container": r"Container\.",
    "block-neighbor notify func_175622_j": r"World\.func_175622_j",
    "Forge-EventBus post": r"EventBus\.post",
}

n_by_phase = collections.Counter()
st_by_phase = collections.Counter()
self_by_phase = collections.defaultdict(collections.Counter)
target_self = collections.defaultdict(collections.Counter)
target_presence = collections.defaultdict(collections.Counter)
target_callers = collections.defaultdict(lambda: collections.defaultdict(collections.Counter))
alloc_by_phase = collections.defaultdict(collections.Counter)
alloc_n_by_phase = collections.Counter()

tsec = re.compile(r"^\s*startTime = (\d+):(\d+):([\d.]+)$")
tthr = re.compile(r'^\s*(?:sampledThread|eventThread) = "([^"]*)"')
tclass = re.compile(r"^\s*objectClass = (\S+) \(")
frame_rx = re.compile(r"^\s{4,}(\S+)\(.*line: -?\d+$")

cur_wall = None
cur_thread = None
cur_frames = None
cur_alloc_class = None
kind = None
first_start = None


def flush_sample():
    global cur_frames
    if cur_wall is None or cur_frames is None:
        return
    ph = phase_of(cur_wall)
    n_by_phase[ph] += 1
    if (cur_thread or "?") == "Server thread":
        st_by_phase[ph] += 1
        leaf = cur_frames[0] if cur_frames else "?"  # jfr print lists stacks LEAF-FIRST
        self_by_phase[ph][leaf] += 1  # true leaf = hottest innermost frame
        for tname, rx in TARGETS.items():
            hit_idx = [i for i, f in enumerate(cur_frames) if re.search(rx, f)]
            if not hit_idx:
                continue
            i = hit_idx[0]  # deepest occurrence (closest to leaf)
            if i == 0:
                target_self[tname][ph] += 1
            target_presence[tname][ph] += 1
            # frames at higher indices are closer to the ROOT = true callers
            caller = cur_frames[i + 1] if i + 1 < len(cur_frames) else "<root>"
            caller2 = cur_frames[i + 2] if i + 2 < len(cur_frames) else ""
            target_callers[tname][ph][(caller, caller2)] += 1
    cur_frames = None


def flush_alloc():
    global cur_alloc_class
    if cur_wall is None or cur_alloc_class is None:
        return
    ph = phase_of(cur_wall)
    if (cur_thread or "?") == "Server thread":
        alloc_by_phase[ph][cur_alloc_class] += 1
        alloc_n_by_phase[ph] += 1
    cur_alloc_class = None


for raw_line in open(sys.argv[2], encoding="utf-8", errors="replace"):
    line = raw_line.rstrip("\r\n")
    m = tsec.match(line)
    if m:
        rel = int(m.group(1)) * 3600 + int(m.group(2)) * 60 + float(m.group(3))
        if first_start is None:
            first_start = rel
        cur_wall = launch_epoch + (rel - first_start)
        continue
    if line.startswith("jdk.ExecutionSample"):
        flush_sample()
        flush_alloc()
        kind = "exec"
        cur_frames = []
        continue
    if line.startswith("jdk.ObjectAllocation"):
        flush_sample()
        flush_alloc()
        kind = "alloc"
        continue
    m = tthr.match(line)
    if m:
        cur_thread = m.group(1)
        continue
    if kind == "alloc":
        m = tclass.match(line)
        if m:
            cur_alloc_class = m.group(1)
            flush_alloc()
        continue
    if kind == "exec" and cur_frames is not None:
        m = frame_rx.match(line)
        if m:
            cur_frames.append(m.group(1))

flush_sample()
flush_alloc()

print("== phase boundaries (wall) ==")
for name, clock in phases:
    print("  %-14s %s -> bucket %s" % (name, clock, LABEL.get(name, name)))

print("\n== samples per phase (all threads / ServerThread) ==")
for ph in PHASE_ORDER:
    print("  %-14s total=%6d  serverthread=%6d" % (ph, n_by_phase.get(ph, 0), st_by_phase.get(ph, 0)))

print("\n== ServerThread top self frames per phase (top 12) ==")
for ph in PHASE_ORDER:
    c = self_by_phase.get(ph)
    if not c:
        continue
    n = st_by_phase.get(ph, 0)
    print("  -- %s (n=%d)" % (ph, n))
    for f, k in c.most_common(12):
        print("     %5d (%5.1f%%)  %s" % (k, 100.0 * k / max(1, n), f))

print("\n== caller census (ServerThread; presence and self separate) ==")
for tname in TARGETS:
    pres = target_presence.get(tname)
    if not pres or not sum(pres.values()):
        continue
    print("  -- %s" % tname)
    for ph in PHASE_ORDER:
        p_ = pres.get(ph, 0)
        if not p_:
            continue
        s_ = target_self.get(tname, {}).get(ph, 0)
        tops = target_callers[tname][ph].most_common(2)
        tops_s = "; ".join("%s <- %s" % (a, b) for (a, b), _ in tops)
        print("     %-14s presence=%4d self=%4d  callers: %s" % (ph, p_, s_, tops_s))

print("\n== ServerThread allocation leaders per phase (sampled events, top 8) ==")
for ph in PHASE_ORDER:
    c = alloc_by_phase.get(ph)
    if not c:
        continue
    tot = alloc_n_by_phase.get(ph, 0)
    print("  -- %s (n=%d)" % (ph, tot))
    for cls, k in c.most_common(8):
        print("     %5d (%5.1f%%)  %s" % (k, 100.0 * k / max(1, tot), cls))
