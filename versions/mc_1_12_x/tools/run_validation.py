"""Reproduce isolated H5/H5.1 contracts and independent Java fixture checks."""
from __future__ import annotations
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
import uuid

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "tools/testing"))
from hardening_guard import inspect

def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def inputs():
    paths = [ROOT / "Cargo.toml", ROOT / "Cargo.lock", ROOT / "tools/testing/hardening_guard.py",
             ROOT / "machine/architecture-hardening/isolation.json",
             ROOT / "tools/bridge/src/com/rustcraft/bridge/M4NativeStatePayload.java",
             ROOT / "tools/bridge/src/com/rustcraft/bridge/capture/CaptureContract.java",
             ROOT / "docs/architecture/version-adapters.md"]
    for base in ("crates/rustcraft-core", "versions/mc_1_12_x"):
        paths += [p for p in (ROOT / base).rglob("*") if p.is_file()
                  and "target" not in p.relative_to(ROOT / base).parts
                  and "__pycache__" not in p.parts]
    return {p.relative_to(ROOT).as_posix(): digest(p) for p in sorted(set(paths))}

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java-home", type=Path, required=True)
    parser.add_argument("--cargo", type=Path, default=Path(r"C:\Users\Admin\.cargo\bin\cargo.exe"))
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    if ROOT.resolve() != Path(r"D:\minecraftrust-astra-hardening").resolve():
        parser.error("restricted to isolated hardening checkout")
    output = (args.output or ROOT / "target/architecture-hardening" /
              ("h5-version-adapters-" + uuid.uuid4().hex[:10])).resolve()
    if not output.is_relative_to((ROOT / "target").resolve()):
        parser.error("output must be under isolated target/")
    output.mkdir(parents=True, exist_ok=False)
    java = args.java_home.resolve() / "bin/java.exe"
    javac = args.java_home.resolve() / "bin/javac.exe"
    rustup = args.cargo.resolve().parent / "rustup.exe"
    # Resolve the rustup proxies once, then invoke the actual selected toolchain.
    # Hashing cargo.exe's proxy alone does not bind the compiler that runs.
    toolchain = Path(subprocess.check_output([str(rustup), "which", "rustc"], cwd=ROOT,
                                            text=True, timeout=30).strip()).parent
    cargo = toolchain / "cargo.exe"
    git = Path(shutil.which("git") or "git")
    tool_paths = [args.cargo.resolve(), rustup, Path(sys.executable), git.resolve(), java, javac,
                  args.java_home.resolve()/"jre/bin/server/jvm.dll",
                  args.java_home.resolve()/"jre/lib/rt.jar", args.java_home.resolve()/"lib/tools.jar"]
    tool_paths += [toolchain/name for name in ("cargo.exe", "rustc.exe", "rustdoc.exe", "rustfmt.exe",
                                              "clippy-driver.exe", "cargo-fmt.exe", "cargo-clippy.exe")]
    env = dict(os.environ)
    for key in ("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS", "CLASSPATH"):
        env.pop(key, None)
    env["PATH"] = str(toolchain) + os.pathsep + env.get("PATH", "")
    env["RUSTC"] = str(toolchain/"rustc.exe")
    env["RUSTDOC"] = str(toolchain/"rustdoc.exe")
    receipt = {"schema_version": 2, "kind": "H5_VERSION_NEUTRAL_ARCHITECTURE_FIXTURES",
               "started_utc": datetime.now(timezone.utc).isoformat(), "production_authority": False,
               "source_hashes_before": inputs(), "tool_hashes_before": {str(p): digest(p) for p in tool_paths},
               "selected_toolchain": str(toolchain),
               "results": [], "limitations": ["New packages integrated into root workspace; no existing consumer migration.",
                   "No full 1.12.1 gameplay, modern support, Forge/modpack qualification or performance claim.",
                   "Java reference fixture oracle is not a captured Minecraft runtime.",
                   "Tool hashes bind listed executables/JDK runtime files, not every system DLL, linker, SDK or environment dependency."]}
    def save():
        (output / "receipt.json").write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
    def run(name, argv, expect_failure=None):
        argv = list(map(str, argv)); log = output / (name + ".log"); start = time.monotonic()
        with log.open("wb") as stream:
            code = subprocess.run(argv, cwd=ROOT, env=env, stdout=stream, stderr=subprocess.STDOUT).returncode
        text = log.read_text(encoding="utf-8")
        # Keep command-local totals separate; never add an integration suite to
        # an unrelated workspace command's count.
        summaries = re.findall(r"test result: ok\. (\d+) passed; (\d+) failed; (\d+) ignored", text)
        row = {"name": name, "argv": argv, "exit_code": code, "seconds": time.monotonic()-start,
               "log": log.relative_to(ROOT).as_posix(), "log_sha256": digest(log)}
        if summaries:
            row["test_summaries"] = [{"passed":int(a),"failed":int(b),"ignored":int(c)} for a,b,c in summaries]
            row["command_passed_total"] = sum(int(a) for a,_,_ in summaries)
        if expect_failure is not None:
            row["expected_failure_marker"] = expect_failure
            ok = code != 0 and expect_failure in text
        else:
            ok = code == 0
        row["status"] = "PASS" if ok else "FAIL"
        receipt["results"].append(row);save();print(json.dumps(row),flush=True)
        if not ok:raise RuntimeError(name + " failed; see " + str(log))
        return text
    try:
        receipt["isolation_before"] = inspect()
        if receipt["isolation_before"]["status"] != "PASS":raise RuntimeError("isolation guard failed")
        run("cargo-version", [cargo,"--version"])
        run("rustc-version", [toolchain/"rustc.exe","-Vv"])
        run("java-version", [java,"-version"])
        for name, package in (("core","rustcraft-core"),("adapter","mc-1-12-x")):
            manifest = ROOT/"Cargo.toml";target = output/"cargo"/name
            run(name+"-fmt", [cargo,"fmt","--manifest-path",manifest,"-p",package,"--check"])
            common = ["--locked","--offline","--manifest-path",manifest,"-p",package,"--target-dir",target]
            run(name+"-clippy", [cargo,"clippy",*common,"--all-targets","--","-D","warnings"])
            text=run(name+"-tests", [cargo,"test",*common])
            if not re.search(r"test result: ok\. [1-9][0-9]* passed",text):raise RuntimeError("empty tests")
        for protocol in (338,340,339):
            run("dispatch-"+str(protocol),[cargo,"run","--locked","--offline","--manifest-path",
                ROOT/"Cargo.toml","-p","mc-1-12-x","--target-dir",output/"cargo/adapter",
                "--example","dispatch_once","--",protocol],
                expect_failure="UnsupportedProtocol" if protocol==339 else None)
        classes=output/"java-classes";classes.mkdir()
        run("java-compile",[javac,"-proc:none","-encoding","UTF-8","-source","8","-target","8",
            "-d",classes,ROOT/"versions/mc_1_12_x/fixtures/KeepAliveOracle.java"])
        text=run("java-oracle",[java,"-cp",classes,"KeepAliveOracle",ROOT/"versions/mc_1_12_x/fixtures/keepalive.tsv"])
        if "PASS KeepAliveOracle checks=18" not in text:raise RuntimeError("Java oracle did not check all vectors")
        run("diff-check",[git,"diff","--check","--","crates/rustcraft-core","versions/mc_1_12_x","docs/architecture/version-adapters.md"])
        receipt["status"]="PASS"
    except Exception as error:
        receipt["status"]="FAIL";receipt["error"]=type(error).__name__+": "+str(error)
    finally:
        receipt["source_hashes_after"]=inputs();receipt["isolation_after"]=inspect()
        receipt["tool_hashes_after"]={str(p): digest(p) for p in tool_paths}
        receipt["finished_utc"]=datetime.now(timezone.utc).isoformat()
        if (receipt["source_hashes_before"]!=receipt["source_hashes_after"] or
                receipt["tool_hashes_before"]!=receipt["tool_hashes_after"] or receipt["isolation_after"]["status"]!="PASS"):
            receipt["status"]="FAIL";receipt["source_tool_or_isolation_drift"]=True
        save();print(json.dumps({"status":receipt["status"],"receipt":str(output/"receipt.json")}),flush=True)
    return int(receipt["status"]!="PASS")

if __name__=="__main__":raise SystemExit(main())
