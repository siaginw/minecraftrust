package com.rustcraft.offline.bootstrap;
import java.io.File;
import java.util.List;
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
  }
  public String getLaunchTarget() { return "com.rustcraft.offline.oracle.QualifyRuntime"; }
  public String[] getLaunchArguments() { return delegate.getLaunchArguments(); }
}
