package com.rustcraft.bridge;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;

/**
 * M-CK5-LIVE wiring for the FrameShadowObserver on the REAL production
 * pipeline (research runtime; default OFF; Java remains authoritative for
 * every transmitted byte; Rust observer output is discarded — never written,
 * never transmitted).
 *
 * HOOK POINT: NetworkManager.func_179289_a(int) (setCompressionThreshold) —
 * injected at its RETURNs by FrameShadowHookTransformer, gated by the system
 * property minecraftrust.frame_shadow (default OFF = no transformation at
 * all). The method runs on the server thread once per connection during login
 * (after "compress"/"prepender"/"encoder" exist), so it is the natural
 * install moment; liveInstall then verifies the actual pipeline layout by
 * handler NAME and FAILS CLOSED (skip + counted reason, Java untouched) on
 * any mismatch.
 *
 * This class must not import Minecraft classes (it is loaded inside the
 * transformed NetworkManager's call path): the channel field is read by
 * reflection (SRG field_150746_k, verified by javap).
 */
public final class FrameShadowLiveHook {

    public static final String MODE = System.getProperty("minecraftrust.frame_shadow", "OFF").toUpperCase();
    public static final boolean ENABLED = "LIVE".equals(MODE);

    // bounded live sampling configuration (deterministic)
    static final int SAMPLE_EVERY = Integer.getInteger("minecraftrust.frame_shadow.every", 2);
    static final int MAX_SAMPLE_BYTES = Integer.getInteger("minecraftrust.frame_shadow.maxbytes", 4 << 20);
    static final long MAX_COMPARISONS = Long.getLong("minecraftrust.frame_shadow.maxcomparisons", 5000L);

    public static final AtomicLong HOOK_CALLS = new AtomicLong();
    public static final List<FrameShadowObserver> LIVE = new CopyOnWriteArrayList<>();
    public static volatile String lastError;

    private static volatile Field CHANNEL_FIELD;

    /** Called (transformer-injected) after NetworkManager.setCompressionThreshold. */
    public static void onCompressionThresholdSet(Object networkManager, int threshold) {
        if (!ENABLED) return;
        HOOK_CALLS.incrementAndGet();
        try {
            if (threshold < 0) return; // disabled-compression contract: handler absent; not the live target
            Field f = CHANNEL_FIELD;
            if (f == null) {
                f = networkManager.getClass().getDeclaredField("field_150746_k"); // SRG: Channel channel
                f.setAccessible(true);
                CHANNEL_FIELD = f;
            }
            Channel ch = (Channel) f.get(networkManager);
            if (ch == null || !ch.isActive()) {
                lastError = "channel-inactive-at-hook";
                return;
            }
            FrameShadowObserver.Config cfg = new FrameShadowObserver.Config();
            cfg.enabled = true;
            cfg.sampleEvery = Math.max(1, SAMPLE_EVERY);
            cfg.maxSampleBytes = MAX_SAMPLE_BYTES;
            cfg.maxComparisons = MAX_COMPARISONS;
            cfg.exactBytesForUncompressed = true;
            FrameShadowObserver obs = FrameShadowObserver.liveInstall(ch.pipeline(), cfg);
            if (obs == null) return; // fail-closed: counted + reason inside liveInstall
            // Observers stay in LIVE for the FINAL aggregated dump (bounded by
            // connection count; per-observer counters are the run's evidence).
            // Clean-close accounting happens in the observer's handlerRemoved
            // (the FINAL lifecycle point, after teardown frees the context); a
            // closeFuture listener would fire too early (verified: pre-fix run
            // showed 0 clean closes despite ctx 2/2 balanced at shutdown).
            LIVE.add(obs);
        } catch (Throwable t) {
            lastError = "hook:" + t;
        }
    }

    private static String mismatchDiags() {
        StringBuilder sb = new StringBuilder();
        for (FrameShadowObserver o : LIVE) {
            for (String d : o.MISMATCH_DIAGNOSTICS) {
                if (sb.length() > 0) sb.append(" || ");
                sb.append(d);
            }
        }
        return sb.length() == 0 ? "none" : sb.toString();
    }

    private static String writeTraces() {
        if (!FrameShadowObserver.TRACE) return "off";
        StringBuilder sb = new StringBuilder();
        for (FrameShadowObserver o : LIVE) {
            if (!o.WRITE_TRACE.isEmpty()) {
                if (sb.length() > 0) sb.append(" | ");
                sb.append(o.WRITE_TRACE);
            }
        }
        return sb.length() == 0 ? "empty" : sb.toString();
    }

    /** Aggregated dump for the periodic/shutdown metrics files. */
    public static String dumpMetrics() {
        long observed = 0, selected = 0, completed = 0, matched = 0, mismatched = 0;
        long completedCompressed = 0, completedUncompressed = 0;
        long unpairedFrame = 0, unpairedCapture = 0, errors = 0, created = 0, freed = 0;
        long retainedObs = 0, retainedBytes = 0, sampledBytes = 0;
        long skippedOver = 0, skippedEmpty = 0, skippedLimit = 0, skippedThr = 0, anomaly = 0, stability = 0;
        int disabled = 0;
        StringBuilder reasons = new StringBuilder();
        for (FrameShadowObserver o : LIVE) {
            observed += o.OBSERVED.get(); selected += o.SELECTED.get(); completed += o.COMPLETED.get();
            matched += o.MATCHED.get(); mismatched += o.MISMATCHED.get();
            completedCompressed += o.COMPLETED_COMPRESSED.get(); completedUncompressed += o.COMPLETED_PASSTHROUGH.get();
            unpairedFrame += o.UNPAIRED_FRAME.get(); unpairedCapture += o.UNPAIRED_CAPTURE.get();
            errors += o.OBSERVER_ERRORS.get(); created += o.CTX_CREATED.get(); freed += o.CTX_FREED.get();
            retainedObs += o.RETAINED_OBSERVATIONS.get(); retainedBytes += o.RETAINED_BYTES.get();
            sampledBytes += o.SAMPLED_BYTES.get();
            skippedOver += o.SKIPPED_OVERSIZE.get(); skippedEmpty += o.SKIPPED_EMPTY.get();
            skippedLimit += o.SKIPPED_LIMIT.get(); skippedThr += o.SKIPPED_UNREADABLE_THRESHOLD.get();
            anomaly += o.SKIPPED_FRAME_ANOMALY.get(); stability += o.SKIPPED_STABILITY.get();
            if (o.disabledReason != null && reasons.indexOf(o.disabledReason) < 0) {
                if (reasons.length() > 0) reasons.append('|');
                reasons.append(o.disabledReason);
                disabled++;
            }
        }
        return "mck5live.mode=" + MODE + " hookCalls=" + HOOK_CALLS.get()
                + " installOk=" + FrameShadowObserver.INSTALL_OK.get()
                + " installSkipped=" + FrameShadowObserver.INSTALL_SKIPPED.get()
                + " lastSkipReason=" + FrameShadowObserver.LAST_SKIP_REASON
                + "\nmck5live.channels=" + LIVE.size()
                + " channelsCleanClosed=" + FrameShadowObserver.CHANNELS_CLEAN_CLOSE.get()
                + "\nmck5live.observed=" + observed + " selected=" + selected + " completed=" + completed
                + " completedCompressed=" + completedCompressed + " completedUncompressed=" + completedUncompressed
                + " matched=" + matched + " mismatched=" + mismatched
                + "\nmck5live.unpairedFrame=" + unpairedFrame + " unpairedCapture=" + unpairedCapture
                + " observerErrors=" + errors + " disabledObservers=" + disabled
                + " disableReasons=" + reasons
                + "\nmck5live.skipped over=" + skippedOver + " empty=" + skippedEmpty + " limit=" + skippedLimit
                + " threshold=" + skippedThr + " frameAnomaly=" + anomaly + " stability=" + stability
                + "\nmck5live.ctxCreated=" + created + " ctxFreed=" + freed
                + " retainedObservations=" + retainedObs + " retainedBytes=" + retainedBytes
                + " sampledBytes=" + sampledBytes
                + "\nmck5live.pipelineLayoutVerified=" + FrameShadowObserver.PIPELINE_LAYOUT_VERIFIED
                + "\nmck5live.pipelineLayoutSkipped=" + FrameShadowObserver.PIPELINE_LAYOUT_SKIPPED
                + "\nmck5live.mismatchDiagnostics=" + mismatchDiags()
                + "\nmck5live.writeTrace=" + writeTraces()
                + "\nmck5live.lastError=" + lastError;
    }
}
