package com.rustcraft.fresh.oracle;

import com.google.gson.GsonBuilder;
import com.rustcraft.fresh.agent.DefinitionObserver;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.launchwrapper.Launch;
import net.minecraftforge.fml.relauncher.FMLLaunchHandler;

/** Real final definitions; no mod lifecycle, registry initialization or server. */
public final class FreshOracle {
    private static Map<String, Object> type(Class<?> type) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("class", type.getName()); result.put("loader", DefinitionObserver.loader(type.getClassLoader()));
        result.put("code_source", type.getProtectionDomain().getCodeSource() == null || type.getProtectionDomain().getCodeSource().getLocation() == null ? null : type.getProtectionDomain().getCodeSource().getLocation().toExternalForm());
        return result;
    }
    private static Object field(String type, String name) throws Exception {
        Field field = Class.forName(type).getDeclaredField(name); field.setAccessible(true); return field.get(null);
    }
    public static void main(String[] args) throws Exception {
        if (!"SERVER".equals(FMLLaunchHandler.side().name()) || FMLLaunchHandler.isDeobfuscatedEnvironment())
            throw new IllegalStateException("wrong side or development environment");
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("schema", "FRESH_FORGE_DEFINITION_OBSERVATION_V1");
        result.put("session", System.getProperty("rustcraft.fresh.session"));
        result.put("challenge", System.getProperty("rustcraft.fresh.challenge"));
        result.put("request_sha256", System.getProperty("rustcraft.fresh.requestSha256"));
        result.put("java_version", System.getProperty("java.version"));
        result.put("java_runtime_version", System.getProperty("java.runtime.version"));
        Map<String, Object> forge = new LinkedHashMap<String, Object>();
        for (String name : new String[]{"major", "minor", "rev", "build", "mccversion", "mcpversion"})
            forge.put(name, field("net.minecraftforge.fml.relauncher.FMLInjectionData", name));
        result.put("forge", forge);
        List<Map<String, Object>> transformers = new ArrayList<Map<String, Object>>();
        for (Object transformer : Launch.classLoader.getTransformers()) transformers.add(type(transformer.getClass()));
        result.put("transformers_before", transformers);
        List<Map<String, Object>> plugins = new ArrayList<Map<String, Object>>();
        for (Object wrapper : (Iterable<?>) field("net.minecraftforge.fml.relauncher.CoreModManager", "loadPlugins")) {
            Field f = wrapper.getClass().getDeclaredField("coreModInstance"); f.setAccessible(true);
            Object instance = f.get(wrapper);
            if (instance == null) throw new IllegalStateException("missing coremod instance");
            plugins.add(type(instance.getClass()));
        }
        result.put("coremods", plugins);
        List<Class<?>> loaded = new ArrayList<Class<?>>();
        for (String name : Files.readAllLines(Paths.get(System.getProperty("rustcraft.fresh.required")), StandardCharsets.UTF_8))
            loaded.add(Class.forName(name.replace('/', '.'), false, Launch.classLoader));
        result.put("definitions", DefinitionObserver.verifiedDefinitions(loaded));
        result.put("all_loader_definitions", DefinitionObserver.allDefinitions());
        List<Map<String, Object>> after = new ArrayList<Map<String, Object>>();
        for (Object transformer : Launch.classLoader.getTransformers()) after.add(type(transformer.getClass()));
        result.put("transformers_after", after);
        if (!transformers.equals(after)) throw new IllegalStateException("transformer chain drift during observation");
        result.put("mod_lifecycle_executed", false); result.put("server_main_called", false);
        result.put("production_authority", false);
        result.put("scope", "FRESH_OFFLINE_DEFINITIONS_ONLY_NO_WRITER_OR_LIFECYCLE_CLOSURE");
        Files.write(Paths.get(System.getProperty("rustcraft.fresh.result")), new GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(result).getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW);
        System.out.println("RUSTCRAFT_FRESH_DEFINITIONS_COMPLETE");
    }
}
