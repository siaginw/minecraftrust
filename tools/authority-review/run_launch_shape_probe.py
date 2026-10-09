#!/usr/bin/env python3
"""§8/§9: boot a NORMAL Revelation launch (no RustCraft coremod/tweaker/
agents beyond the passive probe) and harvest runtime truth:

  [rustcraft-launch-probe] class <name> DEFINED/NOT_DEFINED (+loader/source)
  [rustcraft-launch-probe] transformers count=... {inventory}
  [rustcraft-launch-probe] blackboard keys=...
  mixin/mod provenance greps (mixing lines, coremod list)

The probe is generic: the class list comes from --probe-classes.

The launch shape under test:
  NORMAL   : forge-<v>-universal.jar via -jar, only the probe agent attached
  (the custom shape's LightingEngine status is already proven in campaign
   logs: 'LightingEngine NEVER DEFINED' — harvested by --custom-log)
"""
import argparse
import hashlib
import json
import re
import shutil
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
JDK8 = Path(r"C:\Program Files\Eclipse Adoptium\jdk-8.0.504.1-hotspot")
JAVA = JDK8 / "bin" / "java.exe"
PROBE_JAR = ROOT / "target" / "rustcraft-launch-probe.jar"
RT_C = ROOT / "target" / "authority-smoke" / "runtimeC"
FORGE_UNIVERSAL = "forge-1.12.2-14.23.5.2846-universal.jar"


def wait_for(log_path, pattern, timeout_s, process=None):
    rx = re.compile(pattern.encode() if isinstance(pattern, bytes) else pattern)
    deadline = time.monotonic() + timeout_s
    while time.monotonic() < deadline:
        try:
            text = log_path.read_text(encoding="utf-8", errors="replace")
        except OSError:
            text = ""
        if isinstance(pattern, bytes):
            if rx.search(text.encode("utf-8", errors="replace")):
                return True
        elif rx.search(text):
            return True
        if process is not None and process.poll() is not None:
            return False
        time.sleep(2.0)
    return False


def stop(process, timeout_s=240.0):
    if process.poll() is None:
        try:
            process.stdin.write(b"stop\n")
            process.stdin.flush()
        except (OSError, ValueError):
            pass
        try:
            process.wait(timeout=timeout_s)
        except subprocess.TimeoutExpired:
            process.kill()


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--probe-classes", required=True,
                    help="semicolon list of class names the probe checks")
    ap.add_argument("--dump-classes", default="net.minecraft.world.World",
                    help="semicolon list whose FINAL bytes get dumped")
    ap.add_argument("--delay-s", type=float, default=300.0)
    ap.add_argument("--repeat-s", type=float, default=120.0)
    ap.add_argument("--boot-timeout-s", type=int, default=1500)
    ap.add_argument("--hold-after-done-s", type=float, default=180.0)
    ap.add_argument("--port", type=int, default=25610)
    ap.add_argument("--output", type=Path, required=True)
    ap.add_argument("--custom-log", type=Path, default=None,
                    help="existing custom-launch log to harvest for the "
                         "comparison table")
    args = ap.parse_args()

    if not PROBE_JAR.is_file():
        print("[ERROR] probe jar missing; run build_launch_probe_jar.py",
              file=sys.stderr)
        return 2
    src_server = RT_C  # full Revelation server layout at runtimeC root
    if not (src_server / FORGE_UNIVERSAL).is_file():
        print(f"[ERROR] {src_server / FORGE_UNIVERSAL} missing",
              file=sys.stderr)
        return 2

    out = args.output.resolve()
    if out.exists():
        shutil.rmtree(out)
    out.mkdir(parents=True)
    server = out / "server"
    shutil.copytree(src_server, server, ignore=shutil.ignore_patterns(
        "rustcraft-campaign*.jar", "rustcraft_ffi.dll", "logs", "crash-reports",
        "*.log", "live-shadow*", "region-mirror", "session-*"))
    # deterministic port + offline mode for the probe boot
    props = server / "server.properties"
    lines = []
    if props.is_file():
        lines = [ln for ln in props.read_text(
            encoding="utf-8", errors="replace").splitlines()
            if not ln.startswith("server-port")
            and not ln.startswith("online-mode")]
    lines += [f"server-port={args.port}", "online-mode=false"]
    props.write_text("\n".join(lines) + "\n", encoding="utf-8")

    dumps = out / "final-bytes"
    log_path = out / "server.log"
    handle = log_path.open("wb")
    argv = [
        str(JAVA), "-Xmx6G",
        f"-javaagent:{PROBE_JAR}",
        "-Drustcraft.probe.classes=" + args.probe_classes,
        "-Drustcraft.probe.dumpClasses=" + args.dump_classes,
        f"-Drustcraft.probe.dumpDir={dumps}",
        f"-Drustcraft.probe.delayS={args.delay_s}",
        f"-Drustcraft.probe.repeatS={args.repeat_s}",
        "-jar", FORGE_UNIVERSAL, "nogui",
    ]
    (out / "launch.json").write_text(json.dumps({
        "shape": "NORMAL_REVELATION", "argv": argv,
    }, indent=2) + "\n")
    print(f"[boot] normal Revelation launch on port {args.port}")
    proc = subprocess.Popen(argv, cwd=str(server), stdout=handle,
                            stderr=subprocess.STDOUT, stdin=subprocess.PIPE)
    try:
        done = wait_for(log_path, r"Done \([0-9.]+s\)", args.boot_timeout_s,
                        process=proc)
        if not done:
            print("[ERROR] server never reached Done", file=sys.stderr)
            return 1
        print(f"[boot] Done reached; holding {args.hold_after_done_s:.0f}s "
              f"for the probe (delay {args.delay_s:.0f}s, repeat "
              f"{args.repeat_s:.0f}s)")
        time.sleep(args.hold_after_done_s)
    finally:
        stop(proc)
        handle.close()

    # harvest
    text = log_path.read_text(encoding="utf-8", errors="replace")
    probe_lines = [ln for ln in text.splitlines()
                   if "[rustcraft-launch-probe]" in ln]
    mixing = sorted(set(re.findall(r"Mixing \S+ from (\S+)", text)))
    forge_version = re.search(r"forge-1\.12\.2-([\d.]+)-universal", text)
    lighting_grep = sorted(set(re.findall(
        r"phosphor[a-zA-Z0-9_.$-]*", text)))[:20]
    report = {
        "shape": "NORMAL_REVELATION",
        "forge_version": forge_version.group(1) if forge_version else None,
        "probe_lines": probe_lines,
        "mixin_configs_applied": mixing,
        "phosphor_log_tokens": lighting_grep,
    }
    # final-byte hashes
    hashes = {}
    if dumps.is_dir():
        for p in sorted(dumps.rglob("*.class")):
            rel = p.relative_to(dumps).as_posix()[:-len(".class")]
            hashes[rel] = hashlib.sha256(p.read_bytes()).hexdigest()
    report["final_byte_sha256"] = hashes
    # custom-shape harvest
    if args.custom_log and Path(args.custom_log).is_file():
        ctext = Path(args.custom_log).read_text(
            encoding="utf-8", errors="replace")
        report["custom_launch"] = {
            "lighting_engine_lines": [ln for ln in ctext.splitlines()
                                      if "LightingEngine" in ln][:5],
            "light_experiment_lines": [ln for ln in ctext.splitlines()
                                       if "RustCraft-Light" in ln][:8],
        }
    (out / "launch-shape-report.json").write_text(
        json.dumps(report, indent=2, sort_keys=True) + "\n")
    print(json.dumps(report, indent=2)[:4000])
    print("[OK] launch-shape report written:", out / "launch-shape-report.json")
    return 0


if __name__ == "__main__":
    sys.exit(main())
