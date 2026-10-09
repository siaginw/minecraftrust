package com.rustcraft.telemetry;
import java.lang.management.*;
import java.util.*;
/** Cold sampler: negative/unsupported MXBean values remain unknown; GC milliseconds are not nanos. */
public final class MxBeanSampler  {
    public static Long known(long value) {
        return value<0?null:Long.valueOf(value);
    }
    public static Map<String,Object> sample() {
        Map<String,Object> out=new LinkedHashMap<String,Object>();
        try {
            MemoryUsage heap=ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
            out.put("heap_used_bytes",known(heap.getUsed()));
            out.put("heap_committed_bytes",known(heap.getCommitted()));
            out.put("heap_max_bytes",known(heap.getMax()));
        }
        catch(Throwable unavailable) {
            out.put("heap_used_bytes",null);
            out.put("heap_committed_bytes",null);
            out.put("heap_max_bytes",null);
        }
        List<Map<String,Object>> gc=new ArrayList<Map<String,Object>>();
        try {
            List<GarbageCollectorMXBean> beans=ManagementFactory.getGarbageCollectorMXBeans();
            for(GarbageCollectorMXBean bean:beans) {
                if(gc.size()==16)break;
                Map<String,Object> row=new LinkedHashMap<String,Object>();
                row.put("collector",bean.getName());
                row.put("collection_count",known(bean.getCollectionCount()));
                row.put("collection_time_millis",known(bean.getCollectionTime()));
                gc.add(row);
            }
            out.put("gc_coverage",beans.size()>16?"TRUNCATED_UNKNOWN":"MXBEAN_CUMULATIVE");
            out.put("gc_collectors_omitted",Math.max(0,beans.size()-16));
        }
        catch(Throwable unavailable) {
            out.put("gc_coverage","UNKNOWN");
        }
        out.put("gc",gc);
        out.put("process_rss_bytes",null);
        out.put("native_allocator_bytes",null);
        out.put("full_jni_transition_ns",null);
        return out;
    }
}
