package com.rustcraft.offline.bootstrap;
import java.io.File;
import java.util.List;
import net.minecraft.launchwrapper.ITweaker;
import net.minecraft.launchwrapper.LaunchClassLoader;
import net.minecraftforge.fml.common.launcher.FMLServerTweaker;

/**
 * Revelation offline probe bootstrap. Mirrors OfflineTweaker structurally but
 * targets the REVELATION record-and-classify qualification (no exact-chain or
 * exact-mod-set assertions — Revelation's whole inventory is the subject) and
 * registers NO rustcraft transformers: this stage derives the writer profile,
 * it does not install hooks.
 */
public final class RevOfflineTweaker implements ITweaker {
  private final FMLServerTweaker delegate = new FMLServerTweaker();
  @Override public void acceptOptions(List<String> args, File gameDir, File assetsDir, String profile) {
    delegate.acceptOptions(args, gameDir, assetsDir, profile);
  }
  @Override public void injectIntoClassLoader(LaunchClassLoader cl) {
    cl.addClassLoaderExclusion("com.rustcraft.offline.agent.");
    delegate.injectIntoClassLoader(cl);
  }
  @Override public String getLaunchTarget() { return "com.rustcraft.offline.oracle.RevQualifyRuntime"; }
  @Override public String[] getLaunchArguments() { return delegate.getLaunchArguments(); }
}
