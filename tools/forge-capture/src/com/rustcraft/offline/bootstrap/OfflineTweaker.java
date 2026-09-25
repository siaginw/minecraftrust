package com.rustcraft.offline.bootstrap;
import java.io.File;
import java.util.List;
import net.minecraft.launchwrapper.IClassTransformer;
import net.minecraft.launchwrapper.ITweaker;
import net.minecraft.launchwrapper.LaunchClassLoader;
import net.minecraftforge.fml.common.launcher.FMLServerTweaker;
public final class OfflineTweaker implements ITweaker {
  private final FMLServerTweaker delegate = new FMLServerTweaker();
  public void acceptOptions(List<String> args, File gameDir, File assetsDir, String profile) {
    delegate.acceptOptions(args, gameDir, assetsDir, profile);
  }
  public void injectIntoClassLoader(LaunchClassLoader cl) {
    cl.addClassLoaderExclusion("com.rustcraft.offline.agent.");
    delegate.injectIntoClassLoader(cl);
    registerLiveWriterTransformers(cl);
  }
  /**
   * Issue #1 live-writer transformer verification phase. Registered ONLY when
   * -Drustcraft.liveWriterDiagnostic=true (never in baseline runs; production
   * registration lives in RustCraftCoreMod behind the same default-OFF option).
   * Registered AFTER the FML chain, so the transformer inputs are exactly the
   * qualified pre-hook definitions hash-bound in tools/live-capture/
   * live-shadow-profile.json.
   */
  /**
   * Issue #1 live-writer diagnostic: exclusion only here (BEFORE any rustcraft
   * class can load). The transformers themselves are registered by QualifyRuntime
   * AFTER the complete FML chain exists — they must run last, consuming exactly
   * the qualified post-FML definitions hash-bound in the live-shadow profile.
   */
  private void registerLiveWriterTransformers(LaunchClassLoader cl) {
    if (!Boolean.getBoolean("rustcraft.liveWriterDiagnostic")) return;
    // Scope-correct: only the transformer-support packages load from the parent
    // classpath; the offline harness packages stay in the launch loader exactly
    // like the baseline run (QualifyRuntime must keep launching through it).
    cl.addClassLoaderExclusion("com.rustcraft.bridge.");
    cl.addClassLoaderExclusion("com.rustcraft.coremod.");
    cl.addTransformerExclusion("com.rustcraft.bridge.");
    cl.addTransformerExclusion("com.rustcraft.coremod.");
    cl.addTransformerExclusion("com.rustcraft.livetransformer.");
  }
  public String getLaunchTarget() { return "com.rustcraft.offline.oracle.QualifyRuntime"; }
  public String[] getLaunchArguments() { return delegate.getLaunchArguments(); }
}
