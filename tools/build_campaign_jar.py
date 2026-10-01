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


def main():
    parser = argparse.ArgumentParser(description="Build RustCraft campaign coremod jar")
    parser.add_argument("--target", choices=["A", "C"], default="C",
                        help="Target server runtime (A=Clean Forge 2860, C=Revelation 2846)")
    parser.add_argument("--output", type=Path, default=DEFAULT_OUT_JAR,
                        help="Output jar path")
    parser.add_argument("--plan", type=Path, default=None,
                        help="Alternative LiveWriterPlan.java file")
    args = parser.parse_args()

    if not JAVAC.exists():
        print(f"ERROR: javac not found at {JAVAC}", file=sys.stderr)
        return 1

    if args.target == "A":
        rt = Path(r"D:\minecraftrust\machine\targetA\server")
        asm = rt / "libraries" / "org" / "ow2" / "asm" / "asm-debug-all" / "5.2" / "asm-debug-all-5.2.jar"
        lw = rt / "libraries" / "net" / "minecraft" / "launchwrapper" / "1.12" / "launchwrapper-1.12.jar"
        forge = rt / "forge-1.12.2-14.23.5.2860.jar"
    else:
        rt = Path(r"D:\minecraftrust\machine\targetC\server")
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

    cp = f"{forge};{SRG_JAR};{asm};{lw}"

    sources = [
        ROOT / "tools/bridge/src/com/rustcraft/coremod/LiveShadowCoreMod.java",
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
        ROOT / "tools/bridge/src/com/rustcraft/bridge/ChunkMutationTracker.java",
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
        print("JAR ERROR:\n" + res.stderr, file=sys.stderr)
        return res.returncode

    size = out_jar.stat().st_size
    print(f"[OK] Successfully built {out_jar} ({size:,} bytes)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
