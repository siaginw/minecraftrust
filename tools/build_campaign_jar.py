#!/usr/bin/env python3
"""Builds rustcraft-campaign.jar natively on Windows using pinned JDK 8."""
import argparse
import os
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JDK8 = Path(r"C:\Program Files\Eclipse Adoptium\jdk-8.0.504.1-hotspot")
JAVAC = JDK8 / "bin" / "javac.exe"
JAR = JDK8 / "bin" / "jar.exe"

SRG_JAR = Path(r"D:\minecraftrust\third_party_reference\minecraft\minecraft_server.1.12.2.srg.jar")
DEFAULT_OUT_JAR = ROOT / "target" / "rustcraft-campaign.jar"
BUILD_DIR = ROOT / "target" / "campaign-coremod-build"

# §15 critical JNI exports: verified against the DLL bytes before every
# campaign build (an insertion in native_chunk.rs once silently detached
# findGeneration's #[no_mangle] — registers=0, every job failed closed).
CRITICAL_EXPORTS = [
    b"Java_com_rustcraft_bridge_NativeChunkBridge_registerPrimer",
    b"Java_com_rustcraft_bridge_NativeChunkBridge_registerEmpty",
    b"Java_com_rustcraft_bridge_NativeChunkBridge_markSectionAbsent",
    b"Java_com_rustcraft_bridge_NativeChunkBridge_refreshSectionPacked",
    b"Java_com_rustcraft_bridge_NativeChunkBridge_readbackSection",
    b"Java_com_rustcraft_bridge_NativeChunkBridge_findGeneration",
    b"Java_com_rustcraft_bridge_NativeChunkBridge_getBlockLightProbe",
    b"Java_com_rustcraft_bridge_NativeChunkBridge_mirrorBlockLight",
    b"Java_com_rustcraft_bridge_NativeChunkBridge_mirrorBlockState",
    b"Java_com_rustcraft_bridge_LightAuthorityBridge_runZeroStage",
    b"Java_com_rustcraft_bridge_LightAuthorityBridge_runJobNative",
    b"Java_com_rustcraft_bridge_LightAuthorityBridge_runJob",
    b"Java_com_rustcraft_bridge_LightBatchCtx_create",
    b"Java_com_rustcraft_bridge_LightBatchCtx_closeRaw",
    b"Java_com_rustcraft_bridge_RegionReadCtx_closeRaw",
    b"Java_com_rustcraft_bridge_RegionWriteCtx_closeRaw",
]


def verify_critical_exports(dll_bytes):
    """Returns the list of missing critical export names (empty = OK).
    Names are matched NUL-terminated (as they appear in the PE export
    table): substring matching made runJob vacuous — it always matched
    inside runJobNative (§19 self-test catch)."""
    return [e.decode() for e in CRITICAL_EXPORTS
            if e + b"\x00" not in dll_bytes]


def no_mangle_adjacency_lint():
    """JNI-export adjacency lint (retro 2026-10-09): every
    `pub unsafe extern "system" fn Java_...` in the FFI sources must be
    directly preceded by `#[no_mangle]`. Insertions between the attribute
    and the fn silently detach it (happened TWICE: the original §15
    findGeneration incident and this session's registerEmpty insertion —
    both caught only by the DLL-byte gate). Returns offending
    (file, line) list; empty = clean."""
    import re
    offenders = []
    pat = re.compile(r'^pub unsafe extern "system" fn Java_')
    for src in ("crates/ffi/src/native_chunk.rs",
                "crates/ffi/src/zero_stage_job.rs"):
        path = ROOT / src
        if not path.is_file():
            continue
        lines = path.read_text(encoding="utf-8").splitlines()
        for i, line in enumerate(lines):
            if pat.match(line.strip()):
                # the 6 lines above may contain docs/safety comments,
                # but another export fn appearing AFTER the last
                # #[no_mangle] means the attribute was stolen
                window = [l.strip() for l in lines[max(0, i - 6):i]]
                # find the nearest attribute above; if the 1-6 lines above
                # contain another `fn Java_` AFTER the last `#[no_mangle]`,
                # the attribute was stolen
                last_attr = -1
                stolen = False
                for j, wl in enumerate(window):
                    if wl == "#[no_mangle]":
                        last_attr = j
                    if re.match(r"^pub unsafe extern ", wl) or wl.startswith("fn Java_"):
                        if last_attr >= 0:
                            stolen = True
                if last_attr < 0:
                    # no attribute nearby at all
                    offenders.append((src, i + 1))
                elif stolen:
                    offenders.append((src, i + 1))
    return offenders


def export_check_self_test():
    """§19 negative self-test: the export detector must (a) pass on a blob
    containing every critical name and (b) fail for EACH name removed —
    a detector that cannot fail protects nothing. Already caught one real
    hole: substring matching made runJob vacuous against runJobNative."""
    ok_blob = b"\x00".join(e + b"" for e in CRITICAL_EXPORTS) + b"\x00"
    if verify_critical_exports(ok_blob):
        print("SELF-TEST FAIL: full blob reported missing: "
              + str(verify_critical_exports(ok_blob)), file=sys.stderr)
        return 1
    for victim in CRITICAL_EXPORTS:
        hole_blob = b"\x00".join(
            e for e in CRITICAL_EXPORTS if e != victim) + b"\x00"
        missing = verify_critical_exports(hole_blob)
        if missing != [victim.decode()]:
            print("SELF-TEST FAIL: removing " + victim.decode()
                  + " detected as " + str(missing), file=sys.stderr)
            return 1
    print("[export-self-test] PASS: detector fires for each of "
          + str(len(CRITICAL_EXPORTS)) + " critical exports")
    return 0


def build_observer_only(out_jar: Path) -> int:
    """FULL-STACK BENCHMARK Arm A: a jar containing ONLY the measurement
    observer (tweaker + sampler; no coremod manifest, no transformers,
    no RustCraft classes) so the Java reference pays no hidden RustCraft
    costs. Runs the MsptDedupRegression as its build gate."""
    rt = ROOT / "target" / "authority-smoke" / "runtimeC"
    lw = rt / "libraries" / "net" / "minecraft" / "launchwrapper" / "1.12" \
        / "launchwrapper-1.12.jar"
    if not JAVAC.exists():
        print(f"ERROR: javac not found at {JAVAC}", file=sys.stderr)
        return 1
    build = ROOT / "target" / "observer-build"
    if build.exists():
        shutil.rmtree(build)
    build.mkdir(parents=True)
    srcs = [
        ROOT / "tools/bridge/src/com/rustcraft/observer/"
        "MeasurementObserverTweaker.java",
        ROOT / "tools/bridge/src/com/rustcraft/observer/ObserverMain.java",
    ]
    res = subprocess.run(
        [str(JAVAC), "-encoding", "UTF-8", "-source", "8", "-target", "8",
         "-nowarn", "-cp", str(lw), "-d", str(build)] +
        [str(s) for s in srcs],
        capture_output=True, text=True)
    if res.returncode != 0:
        print("OBSERVER COMPILE ERROR:\n" + res.stderr, file=sys.stderr)
        return res.returncode
    res = subprocess.run(
        [str(JDK8 / "bin" / "jar.exe"), "cf", str(out_jar),
         "-C", str(build), "com"],
        capture_output=True, text=True)
    if res.returncode != 0:
        print("OBSERVER JAR ERROR:\n" + res.stderr, file=sys.stderr)
        return res.returncode
    # §7 gate: the tick-identity semantics are unit-tested at build time
    test_src = ROOT / "tools" / "bridge" / "test" / "com" / "rustcraft" \
        / "observer" / "MsptDedupRegression.java"
    tb = ROOT / "target" / "observer-test-classes"
    if tb.exists():
        shutil.rmtree(tb)
    tb.mkdir(parents=True)
    res = subprocess.run(
        [str(JAVAC), "-encoding", "UTF-8", "-source", "8", "-target", "8",
         "-nowarn", "-cp", str(build), "-d", str(tb), str(test_src)],
        capture_output=True, text=True)
    if res.returncode != 0:
        print("MSPT-DEDUP REGRESSION COMPILE ERROR:\n" + res.stderr,
              file=sys.stderr)
        return res.returncode
    res = subprocess.run(
        [str(JDK8 / "bin" / "java.exe"), "-cp",
         str(tb) + ";" + str(build),
         "com.rustcraft.observer.MsptDedupRegression"],
        capture_output=True, text=True)
    sys.stdout.write(res.stdout)
    if res.returncode != 0:
        print("MSPT-DEDUP REGRESSION FAILED:\n" + res.stderr, file=sys.stderr)
        return res.returncode if res.returncode > 0 else 1
    # retro FS-002: standalone validation must exercise the POPULATED data
    # path (an empty-map dump test missed the a[2] read; cost two boots)
    dump_src = ROOT / "tools" / "bridge" / "test" / "com" / "rustcraft" \
        / "observer" / "ObserverDumpRegression.java"
    res = subprocess.run(
        [str(JAVAC), "-encoding", "UTF-8", "-source", "8", "-target", "8",
         "-nowarn", "-cp", str(build), "-d", str(tb), str(dump_src)],
        capture_output=True, text=True)
    if res.returncode != 0:
        print("OBSERVER-DUMP REGRESSION COMPILE ERROR:\n" + res.stderr,
              file=sys.stderr)
        return res.returncode
    res = subprocess.run(
        [str(JDK8 / "bin" / "java.exe"), "-cp",
         str(tb) + ";" + str(build),
         "com.rustcraft.observer.ObserverDumpRegression"],
        capture_output=True, text=True)
    sys.stdout.write(res.stdout)
    if res.returncode != 0:
        print("OBSERVER-DUMP REGRESSION FAILED:\n" + res.stderr,
              file=sys.stderr)
        return res.returncode if res.returncode > 0 else 1
    print(f"[OK] Successfully built {out_jar} "
          f"({out_jar.stat().st_size:,} bytes) — observer only, "
          f"no transformers, no RustCraft classes")
    return 0


def main():
    parser = argparse.ArgumentParser(description="Build RustCraft campaign coremod jar")
    parser.add_argument("--target", choices=["A", "C"], default="C",
                        help="Target server runtime (A=Clean Forge 2860, C=Revelation 2846)")
    parser.add_argument("--output", type=Path, default=None,
                        help="Output jar path (default: target/rustcraft-campaign"
                             "{-C for target C}.jar — the name the campaign runner "
                             "stages for that target)")
    parser.add_argument("--plan", type=Path, default=None,
                        help="Alternative LiveWriterPlan.java file")
    parser.add_argument("--export-self-test", action="store_true",
                        help="Run the §19 negative self-test and exit")
    parser.add_argument("--observer-only", type=Path, default=None,
                        help="Build ONLY the measurement observer jar "
                             "(clean-Java-reference Arm A: ITweaker with no "
                             "transformers + tick-identity MSPT sampler) and "
                             "exit")
    args = parser.parse_args()

    if args.export_self_test:
        return export_check_self_test()

    if args.observer_only is not None:
        return build_observer_only(args.observer_only)

    # retro 2026-10-09: --target C must imply the -C artifact name — the
    # runner stages rustcraft-campaign-C.jar for Gate C, and a plain
    # `--target C` build landing on the A name made the stale-jar guard
    # the only thing standing between two same-named artifacts
    if args.output is None:
        suffix = "-C" if args.target == "C" else ""
        args.output = ROOT / "target" / f"rustcraft-campaign{suffix}.jar"

    # JNI-export adjacency lint (retro): a detached #[no_mangle] is the
    # twice-repeated silent-export bug class — fail BEFORE javac
    adjacency = no_mangle_adjacency_lint()
    if adjacency:
        for src, ln in adjacency:
            print(f"NO-MANGLE-LINT FAIL {src}:{ln} — export fn missing a "
                  f"directly-preceding #[no_mangle]", file=sys.stderr)
        return 1

    # SRG-literal lint (retro fix 2): every field_/func_ id literal in the
    # bridge sources must exist in the symbol index — the zsa13 incident
    # resolved Chunk.sections with field_76647_h (actually Chunk.z). Warn
    # (exit 2 path) when the index is absent; FAIL before javac on unknowns.
    sys.path.insert(0, str(ROOT / "tools" / "runscope"))
    import srg_literal_lint
    findings, db_used = srg_literal_lint.lint(srg_literal_lint.java_sources())
    unknown = [f for f in findings if f["kind"] == "unknown"]
    for f in unknown:
        print(f"SRG-LINT FAIL {f['file']}:{f['line']} {f['id']}: {f['reason']}",
              file=sys.stderr)
    if unknown:
        print("[BUILD-FAILED] srg-lint", file=sys.stderr)
        return 1
    if not db_used:
        print("WARN: symbol index absent — SRG-literal lint skipped",
              file=sys.stderr)

    if not JAVAC.exists():
        print(f"ERROR: javac not found at {JAVAC}", file=sys.stderr)
        return 1

    # §15 DLL export invariant: a native_chunk.rs insertion once detached
    # findGeneration's #[no_mangle] silently (registers=0, all jobs failed
    # closed). Verify critical JNI exports BEFORE building/booting.
    dll = ROOT / "target" / "release" / "rustcraft_ffi.dll"
    if dll.is_file():
        missing = verify_critical_exports(dll.read_bytes())
        if missing:
            print("[BUILD-FAILED] dll-exports", file=sys.stderr)
            print("ERROR: rustcraft_ffi.dll missing exports (rebuild the "
                  "DLL): " + "; ".join(missing), file=sys.stderr)
            return 1
    else:
        print("WARN: rustcraft_ffi.dll not found; export check skipped",
              file=sys.stderr)

    if args.target == "A":
        rt = ROOT / "target" / "authority-smoke" / "runtimeA"
        asm = rt / "libraries" / "org" / "ow2" / "asm" / "asm-debug-all" / "5.2" / "asm-debug-all-5.2.jar"
        lw = rt / "libraries" / "net" / "minecraft" / "launchwrapper" / "1.12" / "launchwrapper-1.12.jar"
        forge = rt / "forge-1.12.2-14.23.5.2860.jar"
    else:
        rt = ROOT / "target" / "authority-smoke" / "runtimeC"
        asm = rt / "libraries" / "org" / "ow2" / "asm" / "asm-all" / "5.2" / "asm-all-5.2.jar"
        lw = rt / "libraries" / "net" / "minecraft" / "launchwrapper" / "1.12" / "launchwrapper-1.12.jar"
        forge = rt / "forge-1.12.2-14.23.5.2846-universal.jar"

    BUILD_DIR.mkdir(parents=True, exist_ok=True)
    for p in BUILD_DIR.iterdir():
        if p.is_dir():
            shutil.rmtree(p)
        else:
            p.unlink()

    if args.plan:
        plan = BUILD_DIR / "LiveWriterPlan.java"
        shutil.copyfile(args.plan, plan)
    elif args.target == "C":
        plan = ROOT / "target/LiveWriterPlan.java"
    else:
        plan = ROOT / "tools/bridge/src/com/rustcraft/coremod/LiveWriterPlan.java"

    notch_jar = ROOT / "target" / "authority-smoke" / "runtimeA" / "minecraft_server.1.12.2.jar"
    cp = f"{forge};{SRG_JAR};{asm};{lw};{notch_jar}"

    sources = [
        ROOT / "tools/bridge/src/com/rustcraft/observer/MeasurementObserverTweaker.java",
        ROOT / "tools/bridge/src/com/rustcraft/observer/ObserverMain.java",
        ROOT / "tools/bridge/src/com/rustcraft/coremod/LiveShadowCoreMod.java",
        ROOT / "tools/bridge/src/com/rustcraft/coremod/RustCraftCoreMod.java",
        ROOT / "tools/bridge/src/com/rustcraft/coremod/FrameShadowHookTransformer.java",
        ROOT / "tools/bridge/src/com/rustcraft/coremod/WorldCollisionProbeTransformer.java",
        ROOT / "tools/bridge/src/com/rustcraft/bridge/FrameShadowLiveHook.java",
        ROOT / "tools/bridge/src/com/rustcraft/bridge/FrameShadowObserver.java",
        ROOT / "tools/bridge/src/com/rustcraft/bridge/CollisionProbe.java",
        ROOT / "tools/bridge/src/com/rustcraft/bridge/WorldgenShadow.java",
        ROOT / "tools/bridge/src/com/rustcraft/coremod/WorldgenShadowTransformer.java",
        ROOT / "tools/bridge/src/com/rustcraft/bridge/FrameAuthorityHandler.java",
        ROOT / "tools/bridge/src/com/rustcraft/coremod/SPacketChunkDataTransformer.java",
        ROOT / "tools/bridge/src/com/rustcraft/coremod/LiveChunkOwnershipTransformer.java",
        ROOT / "tools/bridge/src/com/rustcraft/coremod/LiveChunkPublicationTransformer.java",
        ROOT / "tools/bridge/src/com/rustcraft/coremod/LiveHookSupport.java",
        ROOT / "tools/bridge/src/com/rustcraft/coremod/LiveWriterOrdering.java",
        ROOT / "tools/bridge/src/com/rustcraft/coremod/LiveSessionAdmissionTweaker.java",
        ROOT / "tools/forge-capture/src/com/rustcraft/offline/agent/ObservationAgent.java",
        ROOT / "tools/bridge/src/com/rustcraft/coremod/AsmTreeCompat.java",
        ROOT / "tools/bridge/src/com/rustcraft/coremod/CanonicalClassIdentityV2.java",
        ROOT / "tools/bridge/src/com/rustcraft/coremod/SessionBoundAdmissionPolicy.java",
        ROOT / "tools/bridge/src/com/rustcraft/coremod/SessionBoundIdentityCertificate.java",
        plan,
        ROOT / "tools/bridge/src/com/rustcraft/bridge/capture/CaptureContract.java",
        ROOT / "tools/bridge/src/com/rustcraft/bridge/capture/CaptureSource.java",
        ROOT / "tools/bridge/src/com/rustcraft/bridge/capture/OwnedPacketSnapshot.java",
        ROOT / "tools/bridge/src/com/rustcraft/bridge/capture/SnapshotCapture.java",
        ROOT / "tools/bridge/src/com/rustcraft/bridge/capture/SyntheticCaptureSource.java",
        ROOT / "tools/bridge/src/com/rustcraft/bridge/capture/OwnedSnapshotBridge.java",
        ROOT / "tools/bridge/src/com/rustcraft/bridge/capture/LiveWriterGate.java",
        ROOT / "tools/bridge/src/com/rustcraft/bridge/capture/PrivateBuildTickets.java",
        ROOT / "tools/bridge/src/com/rustcraft/bridge/capture/LiveChunkBindings.java",
        ROOT / "tools/bridge/src/com/rustcraft/bridge/capture/LiveWriterHooks.java",
        ROOT / "tools/bridge/src/com/rustcraft/bridge/capture/LivePacketCapture.java",
        ROOT / "tools/bridge/src/com/rustcraft/bridge/capture/CaptureDraft.java",
        ROOT / "tools/bridge/src/com/rustcraft/bridge/capture/SealedLiveCapture.java",
        ROOT / "tools/bridge/src/com/rustcraft/bridge/capture/LiveComparisonQueue.java",
        ROOT / "tools/bridge/src/com/rustcraft/bridge/capture/LiveForgeCaptureSource.java",
        ROOT / "tools/bridge/src/com/rustcraft/bridge/capture/LiveCaptureScope.java",
        ROOT / "tools/bridge/src/com/rustcraft/bridge/capture/LegacyCaptureScopes.java",
        ROOT / "tools/bridge/src/com/rustcraft/bridge/capture/ShadowScopeGate.java",
        ROOT / "tools/bridge/src/com/rustcraft/bridge/capture/ShadowEventJournal.java",
        ROOT / "tools/bridge/src/com/rustcraft/bridge/capture/RevelationFixtureHarvest.java",
        ROOT / "tools/bridge/src/com/rustcraft/bridge/capture/CampaignTeleportController.java",
        ROOT / "tools/bridge/src/com/rustcraft/bridge/capture/ShadowEventComparator.java",
        ROOT / "tools/bridge/src/com/rustcraft/bridge/capture/SessionCompatibilityContract.java",
        ROOT / "tools/bridge/src/com/rustcraft/bridge/capture/PhaseDScopePolicy.java",
        ROOT / "tools/bridge/src/com/rustcraft/bridge/capture/LiveShadowCampaignConsumer.java",
        ROOT / "tools/bridge/src/com/rustcraft/bridge/capture/PacketAuthorityExperiment.java",
        ROOT / "tools/bridge/src/com/rustcraft/bridge/PacketEncodeResultV2.java",
        ROOT / "tools/bridge/src/com/rustcraft/bridge/NativeChunkPacket.java",
        ROOT / "tools/bridge/src/com/rustcraft/bridge/NativeChunkBridge.java",
        ROOT / "tools/bridge/src/com/rustcraft/bridge/CompressionCtx.java",
        ROOT / "tools/bridge/src/com/rustcraft/bridge/NativeCompressionEncoder.java",
        ROOT / "tools/bridge/src/com/rustcraft/bridge/RegionWriteCtx.java",
        ROOT / "tools/bridge/src/com/rustcraft/bridge/RustRegionWriteHook.java",
        ROOT / "tools/bridge/src/com/rustcraft/coremod/RegionFileAuthorityTransformer.java",
        ROOT / "tools/bridge/src/com/rustcraft/bridge/RegionReadCtx.java",
        ROOT / "tools/bridge/src/com/rustcraft/bridge/RustRegionReadHook.java",
        ROOT / "tools/bridge/src/com/rustcraft/coremod/RegionFileReadTransformer.java",
        ROOT / "tools/bridge/src/com/rustcraft/bridge/PhosphorLightBridge.java",
        ROOT / "tools/bridge/src/com/rustcraft/bridge/PhosphorLightHook.java",
        ROOT / "tools/bridge/src/com/rustcraft/coremod/PhosphorLightTransformer.java",
        ROOT / "tools/bridge/src/com/rustcraft/bridge/WorldLightBridge.java",
        ROOT / "tools/bridge/src/com/rustcraft/bridge/LightAuthorityBridge.java",
        ROOT / "tools/bridge/src/com/rustcraft/bridge/LightAuthorityHook.java",
        ROOT / "tools/bridge/src/com/rustcraft/coremod/CheckLightAuthorityTransformer.java",
        ROOT / "tools/bridge/src/com/rustcraft/bridge/LightBatchCtx.java",
        ROOT / "tools/bridge/src/com/rustcraft/bridge/WorldLightHook.java",
        ROOT / "tools/bridge/src/com/rustcraft/coremod/WorldLightTransformer.java",
        ROOT / "tools/bridge/src/com/rustcraft/bridge/ChunkMutationTracker.java",
        ROOT / "tools/bridge/src/com/rustcraft/bridge/NativeChunkRegistryHook.java",
        ROOT / "tools/bridge/src/com/rustcraft/bridge/M4Coherency.java",
        ROOT / "tools/bridge/src/com/rustcraft/bridge/M4PacketCompare.java",
        ROOT / "tools/bridge/src/com/rustcraft/bridge/M4PacketParityHarness.java",
        ROOT / "tools/bridge/src/com/rustcraft/bridge/M4ValidatorBoundary.java",
        ROOT / "tools/bridge/src/com/rustcraft/bridge/M4LifecycleCases.java",
        ROOT / "tools/bridge/src/com/rustcraft/bridge/M4DeferredTest.java",
        ROOT / "tools/bridge/src/com/rustcraft/bridge/M4ExtractorParity.java",
        ROOT / "tools/bridge/src/com/rustcraft/bridge/M4PacketPerfStudy.java",
        ROOT / "tools/bridge/src/com/rustcraft/bridge/M4NativeStatePayload.java",
        ROOT / "tools/bridge/src/com/rustcraft/bridge/M5ResendDriver.java",
        ROOT / "tools/bridge/src/com/rustcraft/bridge/WorldgenShadow.java",
        ROOT / "tools/bridge/src/com/rustcraft/bridge/OutboundFrameCtx.java",
        ROOT / "tools/bridge/src/com/rustcraft/bridge/StateRegistryLookup.java",
        ROOT / "tools/bridge/src/com/rustcraft/bridge/ChunkStateAuthorityBridge.java",
        ROOT / "tools/bridge/src/com/rustcraft/coremod/ChunkStateAuthorityTransformer.java",
        ROOT / "tools/bridge/src/com/rustcraft/coremod/ChunkMutationTransformer.java",
        ROOT / "tools/bridge/src/com/rustcraft/coremod/NetworkManagerSingleCopyTransformer.java",
        ROOT / "tools/bridge/src/com/rustcraft/coremod/NetworkManagerCompressionTransformer.java",
        ROOT / "tools/bridge/src/com/rustcraft/bridge/RustCompressionEngine.java",
        ROOT / "tools/bridge/src/NativeCompressionEncoderNotch.java",
        ROOT / "tools/bridge/src/com/rustcraft/coremod/NettyPacketEncoderCounterTransformer.java",
        ROOT / "tools/bridge/src/com/rustcraft/bridge/SingleCopyChunkBody.java",
        ROOT / "tools/bridge/src/com/rustcraft/bridge/SingleCopyBodyCodec.java",
        ROOT / "tools/bridge/src/com/rustcraft/bridge/SingleCopyPipeline.java",
        ROOT / "tools/bridge/src/com/rustcraft/qualification/SameProcessAcquisition.java",
        ROOT / "tools/bridge/src/com/rustcraft/qualification/SessionEvidenceFlush.java",
        ROOT / "tools/bridge/src/com/rustcraft/qualification/TransformationChainEvidence.java",
        ROOT / "tools/bridge/src/com/rustcraft/qualification/LoaderTransformChain.java",
        ROOT / "tools/bridge/src/com/rustcraft/qualification/LoaderDefinitionWitness.java",
        ROOT / "tools/bridge/src/com/rustcraft/qualification/CalleeIsolation.java",
        ROOT / "tools/bridge/src/com/rustcraft/livetransformer/FrameRelationWitness.java",
    ]

    javac_cmd = [
        str(JAVAC),
        "-encoding", "UTF-8",
        "-source", "8",
        "-target", "8",
        "-nowarn",
        "-cp", cp,
        "-d", str(BUILD_DIR),
    ] + [str(s) for s in sources]

    print(f"[build] Compiling campaign sources for Target {args.target}...")
    res = subprocess.run(javac_cmd, capture_output=True, text=True)
    if res.returncode != 0:
        # single-line sentinel FIRST: shell chains grepping "OK|error"
        # match the detail lines and exit 0, masking the failure (the
        # stale-jar baseline incident)
        print("[BUILD-FAILED] javac", file=sys.stderr)
        print("JAVAC ERROR:\n" + res.stderr, file=sys.stderr)
        return res.returncode

    meta_inf = BUILD_DIR / "META-INF"
    meta_inf.mkdir(exist_ok=True)
    manifest = meta_inf / "MANIFEST.MF"
    manifest.write_text(
        "Manifest-Version: 1.0\n"
        "FMLCorePlugin: com.rustcraft.coremod.LiveShadowCoreMod\n"
        "Premain-Class: com.rustcraft.offline.agent.ObservationAgent\n",
        encoding="utf-8"
    )

    out_jar = args.output
    out_jar.parent.mkdir(parents=True, exist_ok=True)
    if out_jar.exists():
        out_jar.unlink()

    jar_cmd = [
        str(JAR),
        "cfm",
        str(out_jar),
        str(manifest),
        "-C", str(BUILD_DIR),
        "."
    ]

    print(f"[build] Packaging {out_jar}...")
    res = subprocess.run(jar_cmd, capture_output=True, text=True)
    if res.returncode != 0:
        print("[BUILD-FAILED] jar", file=sys.stderr)
        print("JAR ERROR:\n" + res.stderr, file=sys.stderr)
        return res.returncode

    size = out_jar.stat().st_size
    print(f"[OK] Successfully built {out_jar} ({size:,} bytes)")

    # §6 regression gate: the compare-hash cell key must be injective (the
    # retired packed-long key collided across legal Minecraft coords, so
    # hashes built on it were not evidence). Runs on every build.
    test_src = ROOT / "tools" / "bridge" / "test" / "com" / "rustcraft" \
        / "bridge" / "CellKeyRegression.java"
    test_build = BUILD_DIR / "test-classes"
    test_build.mkdir(exist_ok=True)
    res = subprocess.run(
        [str(JAVAC), "-encoding", "UTF-8", "-source", "8", "-target", "8",
         "-nowarn", "-cp", str(BUILD_DIR), "-d", str(test_build),
         str(test_src)],
        capture_output=True, text=True)
    if res.returncode != 0:
        print("CELLKEY REGRESSION COMPILE ERROR:\n" + res.stderr,
              file=sys.stderr)
        return res.returncode
    res = subprocess.run(
        [str(JDK8 / "bin" / "java.exe"), "-cp",
         str(test_build) + ";" + str(BUILD_DIR),
         "com.rustcraft.bridge.CellKeyRegression"],
        capture_output=True, text=True)
    sys.stdout.write(res.stdout)
    if res.returncode != 0:
        print("CELLKEY REGRESSION FAILED:\n" + res.stderr, file=sys.stderr)
        return res.returncode if res.returncode > 0 else 1

    # OPT-SYNC-006 §9: lease/cache/impl-selector regression (same wiring)
    sync_test_src = ROOT / "tools" / "bridge" / "test" / "com" / "rustcraft" \
        / "bridge" / "SyncLeaseRegression.java"
    res = subprocess.run(
        [str(JAVAC), "-encoding", "UTF-8", "-source", "8", "-target", "8",
         "-nowarn", "-cp", str(BUILD_DIR), "-d", str(test_build),
         str(sync_test_src)],
        capture_output=True, text=True)
    if res.returncode != 0:
        print("SYNCLEASE REGRESSION COMPILE ERROR:\n" + res.stderr,
              file=sys.stderr)
        return res.returncode
    res = subprocess.run(
        [str(JDK8 / "bin" / "java.exe"), "-cp",
         str(test_build) + ";" + str(BUILD_DIR),
         "com.rustcraft.bridge.SyncLeaseRegression"],
        capture_output=True, text=True)
    sys.stdout.write(res.stdout)
    if res.returncode != 0:
        print("SYNCLEASE REGRESSION FAILED:\n" + res.stderr, file=sys.stderr)
        return res.returncode if res.returncode > 0 else 1

    # OPT-FS-002 §4: blockPosY caching regression + offline cost quantifier
    bpy_src = ROOT / "tools" / "bridge" / "test" / "com" / "rustcraft" \
        / "bridge" / "BlockPosYRegression.java"
    res = subprocess.run(
        [str(JAVAC), "-encoding", "UTF-8", "-source", "8", "-target", "8",
         "-nowarn", "-cp", str(BUILD_DIR), "-d", str(test_build),
         str(bpy_src)],
        capture_output=True, text=True)
    if res.returncode != 0:
        print("BLOCKPOSY REGRESSION COMPILE ERROR:\n" + res.stderr,
              file=sys.stderr)
        return res.returncode
    res = subprocess.run(
        [str(JDK8 / "bin" / "java.exe"), "-cp",
         str(test_build) + ";" + str(BUILD_DIR),
         "com.rustcraft.bridge.BlockPosYRegression"],
        capture_output=True, text=True)
    sys.stdout.write(res.stdout)
    if res.returncode != 0:
        print("BLOCKPOSY REGRESSION FAILED:\n" + res.stderr, file=sys.stderr)
        return res.returncode if res.returncode > 0 else 1

    # retro M1-COMPOSE: tiered-ordering semantics (re-encounter invariant)
    tor_src = ROOT / "tools" / "bridge" / "test" / "com" / "rustcraft" \
        / "coremod" / "TieredOrderingRegression.java"
    res = subprocess.run(
        [str(JAVAC), "-encoding", "UTF-8", "-source", "8", "-target", "8",
         "-nowarn", "-cp", str(BUILD_DIR), "-d", str(test_build),
         str(tor_src)],
        capture_output=True, text=True)
    if res.returncode != 0:
        print("TIERED-ORDERING REGRESSION COMPILE ERROR:\n" + res.stderr,
              file=sys.stderr)
        return res.returncode
    res = subprocess.run(
        [str(JDK8 / "bin" / "java.exe"), "-cp",
         str(test_build) + ";" + str(BUILD_DIR),
         "com.rustcraft.coremod.TieredOrderingRegression"],
        capture_output=True, text=True, cwd=str(ROOT))
    sys.stdout.write(res.stdout)
    if res.returncode != 0:
        print("TIERED-ORDERING REGRESSION FAILED:\n" + res.stderr,
              file=sys.stderr)
        return res.returncode if res.returncode > 0 else 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
