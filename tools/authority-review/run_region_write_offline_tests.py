#!/usr/bin/env python3
"""RUST_REGION_WRITE_AUTHORITY offline LIVE suite.

Runs RegionWriteLiveHarness against real Gate A region files:
  SHADOW          Rust mirror-writes while vanilla writes the real file;
                  payloads compared on both sides.
  ON_EXPERIMENTAL Rust writes the real file (vanilla body skipped);
                  in-session coherence + fresh-vanilla re-read + rewrite
                  generation-ticket path all verified.
  ON cap=N        mixed Rust/vanilla through one instance.
  ON failEvery=5  FULL-REGION stress: every chunk through the seam with real
                  vanilla fallbacks interleaved between Rust admissions.
  MULTI           three live regions in one JVM (per-path engine isolation).
Cross-process: every ON-produced world is re-read by a FRESH vanilla-only
JVM against the expected hash manifest; every produced .mca is scanned in
Rust (bad=0, no overlap).
"""
from __future__ import annotations

import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
JDK8 = Path(r"C:\Program Files\Eclipse Adoptium\jdk-8.0.504.1-hotspot")
JAVAC = JDK8 / "bin" / "javac.exe"
JAVA = JDK8 / "bin" / "java.exe"
SRG = Path(r"D:\minecraftrust\third_party_reference\minecraft\minecraft_server.1.12.2.srg.jar")
CAMPAIGN = ROOT / "target" / "rustcraft-campaign-A.jar"
DLL = ROOT / "target" / "release" / "rustcraft_ffi.dll"
SRC = ROOT / "tools" / "authority-review" / "RegionWriteLiveHarness.java"
SRC_REGION = ROOT / "target" / "authority-smoke" / "runtimeA" / "world" / "region" / "r.0.0.mca"
WORK = ROOT / "target" / "authority-review" / "region-write"
RT_A = ROOT / "target" / "authority-smoke" / "runtimeA"
LW = RT_A / "libraries" / "net" / "minecraft" / "launchwrapper" / "1.12" / "launchwrapper-1.12.jar"
ASM = RT_A / "libraries" / "org" / "ow2" / "asm" / "asm-debug-all" / "5.2" / "asm-debug-all-5.2.jar"

RV_SRC = r'''
import java.io.*; import java.lang.reflect.Method; import java.nio.file.Files;
public class RV {
  public static void main(String[] a) throws Exception {
    Class<?> rf = Class.forName("net.minecraft.world.chunk.storage.RegionFile");
    Object r = rf.getConstructor(File.class).newInstance(new File(a[0]));
    Method rd = rf.getDeclaredMethod("func_76704_a", int.class, int.class);
    rd.setAccessible(true);
    Method cl = rf.getDeclaredMethod("func_76708_c");
    int n = 0;
    for (String line : Files.readAllLines(java.nio.file.Paths.get(a[1]))) {
      if (line.isEmpty()) continue;
      String[] p = line.split("\\s+");
      int x = Integer.parseInt(p[0]); int z = Integer.parseInt(p[1]);
      String want = p[2];
      DataInputStream in = (DataInputStream) rd.invoke(r, x, z);
      if (in == null) { System.out.println("RV_NULL " + x + "," + z); System.exit(3); }
      java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
      byte[] buf = new byte[16384]; int rr;
      while ((rr = in.read(buf)) > 0) md.update(buf, 0, rr);
      in.close();
      StringBuilder hex = new StringBuilder();
      for (byte b : md.digest()) hex.append(String.format("%02x", b));
      if (!hex.toString().equals(want)) { System.out.println("RV_HASH " + x + "," + z); System.exit(4); }
      n++;
    }
    cl.invoke(r);
    System.out.println("RV_OK n=" + n);
  }
}
'''


def fresh_process_verify(expected: Path, mca: Path) -> bool:
    """FRESH JVM (no transformed classes, hook disabled): every chunk in the
    manifest must decompress to the exact expected bytes."""
    verifier = WORK / "classes"
    src = WORK / "RV.java"
    src.write_text(RV_SRC)
    cp = f"{verifier};{SRG}"
    comp = subprocess.run([str(JAVAC), "-nowarn", "-cp", cp, "-d", str(verifier), str(src)],
                          capture_output=True, text=True)
    if comp.returncode != 0:
        print("RV JAVAC ERROR:\n" + comp.stderr[-2000:])
        return False
    run = subprocess.run([str(JAVA), "-Xmx2G", "-cp", cp, "RV", str(mca), str(expected)],
                         capture_output=True, text=True, timeout=300)
    ok = run.returncode == 0 and "RV_OK" in run.stdout
    tag = mca.parent.parent.name
    print(("[ok] fresh-process verify " if ok else "[ERROR] fresh-process verify ")
          + f"{tag}: {run.stdout.strip()}")
    return ok


def run_mode(mode: str, count: int, cap: int | None = None,
             fail_every: int | None = None) -> int:
    mode_dir = WORK / mode.lower() if cap is None else WORK / f"{mode.lower()}-cap{cap}"
    if fail_every is not None:
        mode_dir = WORK / f"{mode.lower()}-fe{fail_every}"
    if mode_dir.exists():
        shutil.rmtree(mode_dir)
    work_dir = mode_dir / "world" / "region"
    mirror_dir = mode_dir / "mirror"
    work_dir.mkdir(parents=True)
    mirror_dir.mkdir(parents=True)

    src = SRC_REGION
    if mode == "MULTI":
        src = SRC_REGION.parent
    cmd = [str(JAVA), "-Xmx2G",
           "-Djava.library.path=" + str(ROOT / "target" / "release"),
           "-Drustcraft.regionWriteExperiment=true",
           f"-Drustcraft.regionWriteMode={mode}",
           "-Drustcraft.regionWriteMirror=" + str(mirror_dir),
           "-cp", f"{WORK / 'classes'};{CAMPAIGN};{SRG};{LW};{ASM}",
           "RegionWriteLiveHarness", mode, str(SRG), str(src),
           str(work_dir), str(mirror_dir), str(count)]
    if cap is not None:
        cmd.insert(1, f"-Drustcraft.regionWriteCap={cap}")
    if fail_every is not None:
        cmd.insert(1, f"-Drustcraft.regionWriteFailEvery={fail_every}")
    r = subprocess.run(cmd, cwd=str(ROOT), capture_output=True, text=True, timeout=900)
    print(f"--- {mode}" + (f" cap={cap}" if cap else "")
          + (f" failEvery={fail_every}" if fail_every else "") + " ---")
    print("\n".join(line for line in r.stdout.splitlines() if "[harness]" in line
                    or "REGION_WRITE" in line))
    if r.returncode != 0:
        print("HARNESS STDOUT TAIL:\n" + r.stdout[-3000:])
        print("HARNESS STDERR:\n" + r.stderr[-3000:])
        return 1
    return 0


def compile_harness(classes: Path) -> bool:
    r = subprocess.run([str(JAVAC), "-encoding", "UTF-8", "-source", "8", "-target", "8",
                        "-nowarn", "-cp", f"{CAMPAIGN};{SRG};{LW};{ASM}",
                        "-d", str(classes), str(SRC)],
                       capture_output=True, text=True)
    if r.returncode != 0:
        print("JAVAC ERROR:\n" + r.stderr[-5000:])
        return False
    return True


def main() -> int:
    for dep, name in ((CAMPAIGN, "campaign jar"), (DLL, "release DLL"),
                      (SRC_REGION, "source region file")):
        if not dep.is_file():
            print(f"[ERROR] {name} missing: {dep}")
            return 1
    classes = WORK / "classes"
    classes.mkdir(parents=True, exist_ok=True)
    if not compile_harness(classes):
        return 1

    failures = 0
    if run_mode("SHADOW", count=24):
        failures += 1
    if run_mode("ON_EXPERIMENTAL", count=24):
        failures += 1
    # capped run: after the cap, writes must silently fall back to vanilla and
    # the file must stay consistent (the harness verifies final readability)
    if run_mode("ON_EXPERIMENTAL", count=24, cap=10):
        failures += 1
    # FULL-REGION stress: every chunk in the file through the seam, Rust
    # declining every 5th admission so real vanilla fallbacks interleave
    if run_mode("ON_EXPERIMENTAL", count=0, fail_every=5):
        failures += 1
    # MULTI: three live regions in one JVM, per-path engine isolation
    if run_mode("MULTI", count=8):
        failures += 1

    # cross-process: every ON-produced world must survive a FRESH vanilla-only
    # JVM reading it back against the expected hash manifest
    for d in sorted(WORK.glob("on_experimental*")):
        exp = d / "world" / "region" / "expected-hashes.txt"
        if not exp.is_file():
            continue
        if not fresh_process_verify(exp, d / "world" / "region" / "r.0.0.mca"):
            failures += 1

    # every produced .mca must scan clean in Rust (bad=0, no overlap)
    for mca in WORK.rglob("*.mca"):
        out = subprocess.run([str(ROOT / "target" / "release" / "region_tools.exe"),
                              "scan", str(mca)], capture_output=True, text=True)
        line = out.stdout.strip().splitlines()[-1] if out.stdout.strip() else ""
        if "bad=0" not in line or "overlap=false" not in line:
            print(f"[ERROR] scan dirty: {mca}: {line}")
            failures += 1

    if failures:
        print(f"REGION_WRITE_OFFLINE_FAILED ({failures} failing runs)")
        return 1
    print("REGION_WRITE_OFFLINE_PASSED")
    return 0


if __name__ == "__main__":
    sys.exit(main())
