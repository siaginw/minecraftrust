package com.rustcraft.observer;

import java.io.File;
import java.util.List;

import net.minecraft.launchwrapper.ITweaker;
import net.minecraft.launchwrapper.LaunchClassLoader;

/**
 * FULL-STACK BENCHMARK (2026-10-09) measurement observer. The ONLY thing
 * this tweaker does is start {@link ObserverMain} — it registers NO
 * transformers and injects nothing, so a launch with this tweaker and no
 * RustCraft artifacts is a clean Java/Forge reference that pays only the
 * sampler's overhead (identical jar + cadence in both arms).
 *
 * Usage (Arm A — clean Java reference):
 *   net.minecraft.launchwrapper.Launch
 *     --tweakClass net.minecraftforge.fml.common.launcher.FMLServerTweaker
 *     --tweakClass com.rustcraft.observer.MeasurementObserverTweaker
 * Arm B appends this same tweaker after the RustCraft tweaker.
 */
public final class MeasurementObserverTweaker implements ITweaker {

    @Override
    public void acceptOptions(List<String> args, File gameDir, File assetsDir,
                              String profile) {
        ObserverMain.start(gameDir);
    }

    @Override
    public void injectIntoClassLoader(LaunchClassLoader classLoader) {
        // deliberately nothing: measurement only, no transformation
    }

    @Override
    public String getLaunchTarget() {
        return null; // FML's tweaker owns the launch target
    }

    @Override
    public String[] getLaunchArguments() {
        return new String[0];
    }
}
