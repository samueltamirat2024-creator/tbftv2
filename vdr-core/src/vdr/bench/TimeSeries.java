package vdr.bench;

import java.util.Locale;

/**
 * One run, second by second, warm-up included, so a stall can be lined up against GC and checkpoint
 * logs by wall clock. The summary histograms cannot show this, and they never see warm-up at all.
 *
 * <p>Samples go into the second they COMPLETED in, with latency still charged from the scheduled
 * arrival: a stall reads as seconds with little throughput followed by seconds of high latency.
 *
 * <p>Buckets are coarse (up to 12.5% wide) to keep the generator's own footprint near 1 MB. These
 * percentiles locate a stall in time; reported percentiles come from {@link Histogram} only.
 */
public final class TimeSeries {

    /** The revocation burst's slot, after the {@link Bench.OpType} slots. */
    static final int BURST_SLOT = Bench.OpType.values().length;
    private static final int SLOTS = BURST_SLOT + 1;
    private static final int SUB_BITS = 3;
    private static final int BUCKETS = (48 - SUB_BITS) << SUB_BITS;

    private static final class Slot {
        final int[] counts = new int[BUCKETS];
        long total, measured, failed, max;
    }

    private static final class Second {
        final Slot[] slots = new Slot[SLOTS];
        long maxInFlight;

        Slot slot(int i) {
            if (slots[i] == null) slots[i] = new Slot();
            return slots[i];
        }
    }

    private final long startNanos;
    private final long startEpochMs;
    private final Second[] seconds;

    /** @param capacitySeconds later samples land in the last second rather than being lost */
    public TimeSeries(long startNanos, long capacitySeconds) {
        this.startNanos = startNanos;
        this.startEpochMs = System.currentTimeMillis();
        this.seconds = new Second[(int) capacitySeconds];
        for (int i = 0; i < seconds.length; i++) seconds[i] = new Second();
    }

    public long startEpochMs() { return startEpochMs; }

    private Second at(long nowNanos) {
        long s = (nowNanos - startNanos) / 1_000_000_000L;
        return seconds[(int) Math.max(0, Math.min(s, seconds.length - 1))];
    }

    public void record(int slotIndex, long micros, boolean measured, long nowNanos) {
        Second sec = at(nowNanos);
        synchronized (sec) {
            Slot s = sec.slot(slotIndex);
            s.counts[bucket(micros)]++;
            s.total++;
            if (measured) s.measured++;
            if (micros > s.max) s.max = micros;
        }
    }

    public void failure(int slotIndex, long nowNanos) {
        Second sec = at(nowNanos);
        synchronized (sec) { sec.slot(slotIndex).failed++; }
    }

    public void inFlight(long value, long nowNanos) {
        Second sec = at(nowNanos);
        synchronized (sec) { if (value > sec.maxInFlight) sec.maxInFlight = value; }
    }

    /** One JSON object per second that saw anything, for deploy/vps/stats.py. */
    public void appendJson(StringBuilder out, String prefixFields) {
        for (int i = 0; i < seconds.length; i++) {
            Second sec = seconds[i];
            synchronized (sec) {
                boolean any = sec.maxInFlight > 0;
                for (Slot s : sec.slots) any |= s != null;
                if (!any) continue;
                out.append('{').append(prefixFields)
                   .append(",\"sec\":").append(i)
                   .append(",\"epoch_ms\":").append(startEpochMs + i * 1000L)
                   .append(",\"in_flight\":").append(sec.maxInFlight)
                   .append(",\"ops\":{");
                boolean first = true;
                for (int k = 0; k < SLOTS; k++) {
                    Slot s = sec.slots[k];
                    if (s == null) continue;
                    if (!first) out.append(',');
                    first = false;
                    String label = k == BURST_SLOT ? "burst" : Bench.OpType.values()[k].label();
                    out.append(String.format(Locale.ROOT,
                            "\"%s\":{\"n\":%d,\"measured\":%d,\"failed\":%d,\"p50\":%d,\"p99\":%d,\"max\":%d}",
                            label, s.total, s.measured, s.failed, percentile(s, 50), percentile(s, 99), s.max));
                }
                out.append("}}\n");
            }
        }
    }

    private static long percentile(Slot s, double p) {
        if (s.total == 0) return 0;
        long target = (long) Math.ceil(p / 100.0 * s.total), seen = 0;
        for (int i = 0; i < BUCKETS; i++) {
            seen += s.counts[i];
            if (seen >= target) return Math.min(value(i), s.max);
        }
        return s.max;
    }

    private static int bucket(long v) {
        if (v < 0) v = 0;
        if (v < (1L << SUB_BITS)) return (int) v;
        int msb = 63 - Long.numberOfLeadingZeros(v);
        int octave = msb - SUB_BITS + 1;
        int index = (octave << SUB_BITS) + (int) ((v >>> octave) & ((1 << SUB_BITS) - 1));
        return Math.min(index, BUCKETS - 1);
    }

    private static long value(int index) {
        int octave = index >>> SUB_BITS;
        long sub = index & ((1 << SUB_BITS) - 1);
        return octave == 0 ? sub : sub << octave;
    }
}
