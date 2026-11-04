package vdr.bench;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;

/**
 * With -Dvdr.timing=true, one line a second of cumulative JIT and GC time, so a stall can be checked
 * against what this JVM was doing (deploy/vps/diagnose.py). Off by default: reported runs do not
 * carry the extra thread or log lines.
 */
public final class JvmSampler {

    public static final boolean TIMING = Boolean.getBoolean("vdr.timing");

    private JvmSampler() {}

    public static void start(String label) {
        if (!TIMING) return;
        Thread t = new Thread(() -> {
            var compilation = ManagementFactory.getCompilationMXBean();
            var memory = ManagementFactory.getMemoryMXBean();
            while (true) {
                long gcMs = 0;
                for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
                    gcMs += Math.max(0, gc.getCollectionTime());
                }
                System.out.printf("JVM-SAMPLE label=%s epoch_ms=%d compile_ms=%d gc_ms=%d heap_used=%d%n",
                        label, System.currentTimeMillis(), compilation.getTotalCompilationTime(), gcMs,
                        memory.getHeapMemoryUsage().getUsed());
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "jvm-sampler");
        t.setDaemon(true);
        t.start();
    }
}
