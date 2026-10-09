package com.rustcraft.observability;
import net.minecraft.launchwrapper.Launch;
/** Sets loader policy before the JVM verifies any telemetry-signature-bearing probe class. */
public final class OfflineEntry  {
    public static void main(String[] args) throws Throwable  {
        Launch.classLoader.addClassLoaderExclusion("com.rustcraft.telemetry.");
        Class<?> probe = Class.forName("com.rustcraft.observability.OfflineForgeProbe", true, Launch.classLoader);
        try  {
            probe.getMethod("main", String[].class).invoke(null, (Object) args);
        }
        catch (java.lang.reflect.InvocationTargetException wrapped)  {
            throw wrapped.getCause();
        }
    }
}
