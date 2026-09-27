package com.rustcraft.fresh.bootstrap;

import java.io.File;
import java.util.List;
import net.minecraft.launchwrapper.ITweaker;
import net.minecraft.launchwrapper.LaunchClassLoader;
import net.minecraftforge.fml.common.launcher.FMLServerTweaker;

/** Both runtime profiles use the same lifecycle-free bootstrap. */
public final class FreshTweaker implements ITweaker {
    private final FMLServerTweaker delegate = new FMLServerTweaker();
    public void acceptOptions(List<String> args, File game, File assets, String profile) {
        delegate.acceptOptions(args, game, assets, profile);
    }
    public void injectIntoClassLoader(LaunchClassLoader loader) {
        loader.addClassLoaderExclusion("com.rustcraft.fresh.agent.");
        delegate.injectIntoClassLoader(loader);
    }
    public String getLaunchTarget() { return "com.rustcraft.fresh.oracle.FreshOracle"; }
    public String[] getLaunchArguments() { return delegate.getLaunchArguments(); }
}
