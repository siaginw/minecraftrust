package com.rustcraft.offline.agent;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.util.concurrent.atomic.AtomicLong;

/**
 * RUST_REGION_WRITE_AUTHORITY goal-§4 writer attribution helper.
 *
 * Lives on the BOOTSTRAP classpath (appended by WriterAttributionAgent) so
 * the injected java.io.RandomAccessFile hooks can reach it. Pure JDK: no
 * Minecraft, no Netty, no reflection into app classes.
 *
 * Journal (JSONL, one line per observed region write):
 *   ts thread path pos len frames[..] class=VANILLA_REGIONFILE|RUST|UNKNOWN
 *
 * RUST writes never appear here: the Rust engine owns an OS-level handle and
 * bypasses java.io entirely — its writes are visible only in the shadow
 * comparator. Every JAVA-SIDE write to a region path therefore carries a
 * stack, and any stack WITHOUT RegionFile/anvil frames is a bypass writer.
 */
public final class WriterAttribution {

    public static volatile boolean ENABLED = false;
    static volatile PrintWriter JOURNAL;
    static volatile String FILTER = ".mca";
    public static final AtomicLong EVENTS = new AtomicLong();
    public static final AtomicLong UNKNOWN = new AtomicLong();
    public static final AtomicLong REGIONFILE = new AtomicLong();
    public static final AtomicLong DEEP = new AtomicLong(); // stack walk depth

    private WriterAttribution() { }

    /** Called from injected java.io.RandomAccessFile write paths. */
    public static void rafWrite(Object rafThis, String path, int len) {
        if (!ENABLED || path == null || !path.endsWith(FILTER)) {
            return;
        }
        try {
            long pos = -1;
            try {
                java.io.RandomAccessFile r =
                        (java.io.RandomAccessFile) rafThis;
                pos = r.getFilePointer();
            } catch (Throwable ignore) { }
            StackTraceElement[] st = new Throwable().getStackTrace();
            // collect the first non-JDK caller frames (skip RAF internals +
            // this hook); the first kept frame is the immediate caller
            String owner = "UNKNOWN";
            StringBuilder frames = new StringBuilder();
            int kept = 0;
            for (int i = 0; i < st.length; i++) {
                String cn = st[i].getClassName();
                if (cn.equals("java.io.RandomAccessFile")
                        || cn.equals(WriterAttribution.class.getName())
                        || cn.startsWith("java.") || cn.startsWith("sun.")
                        || cn.startsWith("jdk.")) {
                    continue;
                }
                if (frames.length() > 0) {
                    frames.append('<');
                }
                frames.append(cn).append('.').append(st[i].getMethodName());
                kept++;
                if (cn.endsWith("RegionFile") || cn.equals("ayj")
                        || cn.contains("AnvilChunkLoader")) {
                    owner = "VANILLA_REGIONFILE";
                }
                if (kept >= 10) {
                    break;
                }
            }
            if ("UNKNOWN".equals(owner)) {
                // second pass: any RegionFile-adjacent frame anywhere?
                for (StackTraceElement f : st) {
                    String cn = f.getClassName();
                    if (cn.endsWith("RegionFile") || cn.equals("ayj")) {
                        owner = "VANILLA_REGIONFILE";
                        break;
                    }
                }
            }
            ("VANILLA_REGIONFILE".equals(owner) ? REGIONFILE : UNKNOWN)
                    .incrementAndGet();
            EVENTS.incrementAndGet();
            PrintWriter w = JOURNAL;
            if (w != null) {
                synchronized (WriterAttribution.class) {
                    w.println("{\"ts\":" + System.currentTimeMillis()
                            + ",\"thread\":\"" + Thread.currentThread().getName()
                            + "\",\"path\":\"" + jsonEscape(path)
                            + "\",\"pos\":" + pos
                            + ",\"len\":" + len
                            + ",\"owner\":\"" + owner
                            + "\",\"frames\":\"" + jsonEscape(frames.toString())
                            + "\"}");
                    w.flush();
                }
            }
        } catch (Throwable t) {
            // observation must never break the write
        }
    }

    static String jsonEscape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public static void init(String journalPath) {
        try {
            File f = new File(journalPath);
            if (f.getParentFile() != null) {
                f.getParentFile().mkdirs();
            }
            JOURNAL = new PrintWriter(new FileOutputStream(f, true), false);
            ENABLED = true;
        } catch (Throwable t) {
            ENABLED = false;
        }
    }

    public static String dump() {
        return "writerAttribution events=" + EVENTS.get()
                + " regionfile=" + REGIONFILE.get()
                + " unknown=" + UNKNOWN.get();
    }
}
