package com.rustcraft.pipelineprobe;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.io.PrintWriter;
import java.io.FileWriter;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Map;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLServerAboutToStartEvent;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPipeline;
import io.netty.util.Attribute;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.EnumConnectionState;

@Mod(modid = "pipelineprobe", name = "PipelineProbe", version = "1.1.0", acceptableRemoteVersions = "*")
public class PipelineProbeMod {

    @Mod.EventHandler
    public void onServerAboutToStart(FMLServerAboutToStartEvent ev) {
        final MinecraftServer srv = ev.getServer();
        Thread t = new Thread(() -> runProbe(srv));
        t.setName("pipeline-probe");
        t.setDaemon(true);
        t.start();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static void runProbe(MinecraftServer server) {
        String outPath = System.getProperty("probe.out",
            Paths.get("pipeline-trace.txt").toAbsolutePath().toString());
        try (PrintWriter out = new PrintWriter(new FileWriter(outPath, true))) {
            out.println("=== probe v1.1 start " + System.currentTimeMillis() + " ===");
            Method m;
            Object networkSystem = null;
            try {
                m = server.getClass().getMethod("func_147137_ag");
                networkSystem = m.invoke(server);
            } catch (Exception e) {
                out.println("getNetworkSystem failed: " + e);
            }
            if (networkSystem == null) { out.println("no networkSystem; abort"); out.flush(); return; }
            Field f = networkSystem.getClass().getDeclaredField("field_151272_f");
            f.setAccessible(true);
            Collection<NetworkManager> managers = (Collection<NetworkManager>) f.get(networkSystem);
            String lastSig = "";
            int iter = 0;
            while (iter++ < 3600) { // 6 minutes @100ms
                Thread.sleep(100);
                String sig = dumpManagers(out, managers, iter);
                if (!sig.equals(lastSig)) {
                    out.println("### PIPELINE CHANGE at poll " + iter);
                    lastSig = sig;
                }
                out.flush();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /** Dumps each manager's pipeline; returns compact signature of all pipelines for change detection. */
    static String dumpManagers(PrintWriter out, Collection<NetworkManager> managers, int iter) {
        StringBuilder sigAll = new StringBuilder();
        out.println("--- poll " + iter + " managers=" + managers.size());
        for (NetworkManager nm : managers) {
            try {
                Field cf = NetworkManager.class.getDeclaredField("field_150746_k");
                cf.setAccessible(true);
                Channel ch = (Channel) cf.get(nm);
                if (ch == null) { out.println("  [no channel]"); continue; }
                ChannelPipeline p = ch.pipeline();
                Object stateObj = null;
                try {
                    Attribute<EnumConnectionState> attr = ch.attr(NetworkManager.field_150739_c);
                    stateObj = attr.get();
                } catch (Throwable ig) {}
                StringBuilder sb = new StringBuilder();
                sb.append("  channel ").append(ch).append('\n');
                sb.append("  active=").append(ch.isActive())
                  .append(" writable=").append(ch.isWritable())
                  .append(" eventLoop=").append(ch.eventLoop())
                  .append(" state=").append(stateObj)
                  .append(" remote=").append(ch.remoteAddress()).append('\n');
                Map<String, ChannelHandler> asMap = p.toMap();
                int pos = 0;
                for (Map.Entry<String, ChannelHandler> e : asMap.entrySet()) {
                    sb.append(String.format("    [%d] %-22s %s%n", pos++, e.getKey(),
                        e.getValue().getClass().getName()));
                    sigAll.append(e.getKey()).append('|');
                }
                sigAll.append(';');
                out.print(sb);
            } catch (Exception e) {
                out.println("  [dump failed: " + e + "]");
            }
        }
        return sigAll.toString();
    }
}
