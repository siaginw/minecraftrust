"""Offline transformer verification wrapper (the VerifyError killer).

One transformer iteration used to cost a full campaign boot to learn
"injection breaks class loading" (dev-ON-1: COMPUTE_MAXS writer ->
VerifyError -> boot burned). This compiles (once) a small ASM
CheckClassAdapter harness and runs it in a plain JVM: transformer applied
to the real bytes of the real target class, result verified with full
dataflow analysis — in seconds, offline.

    python tools/runscope/verify_transformer.py \
        --classes-dir target/campaign-coremod-build \
        --jar  <srg-or-binpatched server jar> \
        --target-class amu \
        --transformer com.rustcraft.coremod.CheckLightAuthorityTransformer \
        [--libs-dir <server/libraries>] [--jdk <jdk bin>] [--json]

Exit 0 verified / 2 verify FAILED / 3 transform NOP / 4 setup.
"""

import argparse
import json
import os
import subprocess
import sys

_HERE = os.path.dirname(os.path.abspath(__file__))
_HELPER = os.path.join(_HERE, "java", "TransformVerify.java")
DEFAULT_JDK_CANDIDATES = [
    "C:/Program Files/Eclipse Adoptium/jdk-8.0.504.1-hotspot/bin",
    "C:/Program Files/Eclipse Adoptium/jdk-8.0.452.1-hotspot/bin",
]
DEFAULT_LIBS_CANDIDATES = [
    "target/authority-review/collision-profile-C/server/libraries",
    "target/authority-smoke/runtimeC/libraries",
]


def _find_jdk(jdk_arg):
    if jdk_arg:
        return jdk_arg
    for cand in DEFAULT_JDK_CANDIDATES:
        if os.path.isfile(os.path.join(cand, "javac.exe")) or \
                os.path.isfile(os.path.join(cand, "javac")):
            return cand
    which = shutil_which("javac")
    return os.path.dirname(which) if which else None


def shutil_which(prog):
    from distutils.spawn import find_executable  # stdlib, py<=3.11 ok
    return find_executable(prog)


def _find_cp_jars(libs_dir):
    jars = []
    if libs_dir and os.path.isdir(libs_dir):
        for sub in ("net/minecraft/launchwrapper", "org/ow2/asm"):
            root = os.path.join(libs_dir, sub)
            if os.path.isdir(root):
                for dirpath, _dirs, names in os.walk(root):
                    jars += [os.path.join(dirpath, n) for n in names
                             if n.endswith(".jar")]
    return sorted(set(jars))


def main(repo_root, argv=None):
    ap = argparse.ArgumentParser(prog="runscope verify-transformer")
    ap.add_argument("--classes-dir", required=True,
                    help="compiled transformer classes "
                         "(e.g. target/campaign-coremod-build)")
    ap.add_argument("--jar", required=True,
                    help="server jar holding the ORIGINAL target class "
                         "(srg jar or binpatched, matching the transform "
                         "input shape)")
    ap.add_argument("--target-class", required=True,
                    help="internal name, e.g. amu (notch) or the mapped "
                         "form net.minecraft.world.World — the notch form "
                         "is auto-mapped via the symbol index so gates on "
                         "transformedName fire (retro fix 3)")
    ap.add_argument("--transformer", required=True, help="transformer FQCN")
    ap.add_argument("--libs-dir", help="server libraries dir (launchwrapper"
                                       " + asm discovery)")
    ap.add_argument("--prop", action="append", metavar="key=value",
                    help="JVM system property for the verify run (-D) - "
                         "the clean way to flip property-gated transformers "
                         "(e.g. rustcraft.lightMode=ON_EXPERIMENTAL)")
    ap.add_argument("--set", action="append", metavar="fqcn.FIELD=value",
                    help="set a static field pre-transform WITHOUT running "
                         "the class <clinit> (Unsafe) - e.g. "
                         "com.rustcraft.bridge.LightAuthorityHook.ON=true")
    ap.add_argument("--jdk", help="JDK bin dir (javac/java 8)")
    ap.add_argument("--out-dir",
                    default=os.path.join(repo_root, "target",
                                         "runscope-verify"))
    ap.add_argument("--json", action="store_true")
    ns = ap.parse_args(argv)

    jdk = _find_jdk(ns.jdk)
    if not jdk:
        print("JDK with javac not found; pass --jdk")
        return 4
    javac = os.path.join(jdk, "javac.exe" if os.name == "nt" else "javac")
    java = os.path.join(jdk, "java.exe" if os.name == "nt" else "java")

    libs_dir = ns.libs_dir
    if not libs_dir:
        for cand in DEFAULT_LIBS_CANDIDATES:
            full = os.path.join(repo_root, cand)
            if os.path.isdir(full):
                libs_dir = full
                break
    cp_jars = _find_cp_jars(libs_dir)
    if not cp_jars:
        print("no launchwrapper/asm jars found under %s - pass --libs-dir"
              % (libs_dir or "(none discovered)"))
        return 4

    os.makedirs(ns.out_dir, exist_ok=True)
    helper_class = os.path.join(ns.out_dir, "TransformVerify.class")
    if not os.path.isfile(helper_class) or os.path.getmtime(helper_class) \
            < os.path.getmtime(_HELPER):
        r = subprocess.run(
            [javac, "-encoding", "UTF-8", "-proc:none",
             "-cp", os.pathsep.join(cp_jars),
             "-d", ns.out_dir, _HELPER],
            capture_output=True, text=True)
        if r.returncode != 0:
            print("helper compile failed:\\n" + r.stderr[-1500:])
            return 4

    cp = os.pathsep.join([ns.out_dir, ns.classes_dir, ns.jar] + cp_jars)

    # retro fix 3: transformers gate on the MAPPED transformedName; resolve
    # the notch->mapped class mapping from the symbol index when the
    # target is given in notch form (the class entry in the jar stays
    # notch-named).
    target_entry = ns.target_class
    transformed_name = ns.target_class
    db = os.path.join(repo_root, "target", "symbol-index",
                      "rustcraft-symbols.sqlite")
    if os.path.isfile(db) and "." not in ns.target_class.replace("/", ""):
        try:
            import sqlite3
            con = sqlite3.connect(db)
            row = con.execute(
                "SELECT notch_class, srg_class FROM member_names "
                "WHERE notch_class = ? LIMIT 1",
                (ns.target_class,)).fetchone()
            con.close()
            if row:
                target_entry = row[0]
                transformed_name = row[1].replace("/", ".")
        except Exception:
            pass
    if ns.target_class != transformed_name:
        print(f"[verify-transformer] gate name: {ns.target_class} -> "
              f"transformedName={transformed_name}")

    cmd = [java, "-cp", cp]
    for prop in (ns.prop or ()):
        cmd.append("-D" + prop)
    cmd += ["TransformVerify", ns.classes_dir, ns.jar,
            target_entry, ns.transformer, transformed_name]
    for spec in (getattr(ns, 'set') or ()):
        cmd += ["--set", spec]
    r = subprocess.run(cmd, capture_output=True, text=True, timeout=120)
    output = (r.stdout + r.stderr).strip()
    if ns.json:
        print(json.dumps({"exit": r.returncode, "output": output[-4000:]},
                         indent=1))
    else:
        print(output)
    return r.returncode


if __name__ == "__main__":
    sys.exit(main(os.path.dirname(os.path.dirname(
        os.path.dirname(os.path.abspath(__file__))))))
